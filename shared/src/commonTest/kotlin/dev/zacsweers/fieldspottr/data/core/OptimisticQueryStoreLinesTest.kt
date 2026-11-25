// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.impl.landedCommits
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.append
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Changes of one optimistic value on several lines, which send and commit in any order. Every value
 * is compared as a set, since changes on different lines only commute as a set.
 */
class OptimisticQueryStoreLinesTest {
  private val send = TestSend<Add>()
  private val fetch = TestFetch { listOf("a") }
  // How many more writes of the base store fail for a value that holds each tag
  private val failWrites = mutableMapOf<String, Int>()
  private val truth = QueryStore.inMemory(Fetched(listOf("a"), START))
  private val base =
    QueryStore.of(
      truth.data,
      write = { fetched, keepsStored ->
        val tag = failWrites.entries.firstOrNull { (tag, left) -> left > 0 && tag in fetched.value }
        if (tag != null) {
          tag.setValue(tag.value - 1)
          error("Disk full")
        }
        truth.writeFetched(fetched, keepsStored)
      },
      clear = truth::clearFetched,
    )

  /**
   * Sends C on ordering [c], and its commit fails once on its base write. A forced fetch from after
   * C's send lands with c. Then B sends on ordering [b], and its commit fails twice on its base
   * write, so its first retry fails too. C's retry lands meanwhile. Returns every value the view
   * emitted from B's send through B's first retry, after checking that every change reaches the
   * truth in the end. Each of those values must hold b. With [keyedSet], the store is the [Unit]
   * key of a keyed set.
   */
  private suspend fun TestScope.valuesWhileCommitOwed(
    c: Ordering,
    b: Ordering,
    keyedSet: Boolean = false,
  ): List<Set<String>?> {
    val sender =
      if (keyedSet) {
        UnitTags.sender { change, id -> send(change.mutation, id) }
      } else {
        Tags.sender(send = send)
      }
    val data = testDataScope(clock = testClock(START), senders = setOf(sender))
    val store =
      if (keyedSet) {
        data.optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { base },
          apply = ::addOnce,
        )(Unit)
      } else {
        data.optimisticStore(Tags, base = base, apply = ::addOnce)
      }
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()

    failWrites["c"] = 1
    failWrites["b"] = 2
    store.mutate(Add("c"), c).awaitSucceeded()
    advanceTimeBy(1.milliseconds)
    runCurrent()
    // The server holds c, and this fetch reads it after C's send
    fetch.respond = { listOf("a", "c") }
    query.requestFetch(force = true).awaitSucceeded()
    val withB = seen.size
    store.mutate(Add("b"), b).awaitSucceeded()
    runCurrent()
    // Past C's commit retry, and B's first retry, which fails again
    advanceTimeBy(1.5.seconds)
    runCurrent()
    val during = seen.drop(withB)
    // Every retry lands in the end
    advanceTimeBy(1.minutes)
    runCurrent()
    assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "b", "c"))
    return during
  }

  @Test
  fun `a commit retry on another line never hides a sent change whose commit is owed`() = runTest {
    val during = valuesWhileCommitOwed(c = Ordering.Keyed("y"), b = Ordering.Keyed("x"))

    assertThat(during.filter { "b" !in it.orEmpty() }).isEmpty()
  }

  @Test
  fun `on one line a commit retry never hides a sent change whose commit is owed`() = runTest {
    val during = valuesWhileCommitOwed(c = Ordering.Serial, b = Ordering.Serial)

    assertThat(during.filter { "b" !in it.orEmpty() }).isEmpty()
  }

  @Test
  fun `in a keyed set a commit retry on another line of the key never hides an owed change`() =
    runTest {
      val during =
        valuesWhileCommitOwed(c = Ordering.Keyed("y"), b = Ordering.Keyed("x"), keyedSet = true)

      assertThat(during.filter { "b" !in it.orEmpty() }).isEmpty()
    }

  /**
   * C on one line fails its first base write, and a fetch from after C's send lands with c. B on
   * another line then commits, and its write lands. C's retry writes nothing, since the truth was
   * fetched after C's send, and D on B's line commits last. Every value of the view and the final
   * truth must hold b once. The store applies with [append], which is not idempotent, so a change
   * applied twice shows twice.
   */
  @Test
  fun `a commit that writes nothing leaves another line's stored change applied once`() = runTest {
    val data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    val store = data.optimisticStore(Tags, base = base, apply = ::append)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value } }
    runCurrent()

    failWrites["c"] = 1
    store.mutate(Add("c"), Ordering.Keyed("y")).awaitSucceeded()
    advanceTimeBy(1.milliseconds)
    runCurrent()
    fetch.respond = { listOf("a", "c") }
    query.requestFetch(force = true).awaitSucceeded()
    store.mutate(Add("b"), Ordering.Keyed("x")).awaitSucceeded()
    runCurrent()
    // Past C's commit retry
    advanceTimeBy(1.5.seconds)
    runCurrent()
    store.mutate(Add("d"), Ordering.Keyed("x")).awaitSucceeded()
    advanceTimeBy(1.minutes)
    runCurrent()

    assertThat(seen.filter { value -> value.orEmpty().count { it == "b" } > 1 }).isEmpty()
    assertThat(truth.data.value?.value).isEqualTo(listOf("a", "c", "b", "d"))
  }

  /**
   * The sequence of [valuesWhileCommitOwed] on a single store, with B's commit failing once, and
   * the owner's scope stopped after C's retry and before B's. Returns the view's value 30 minutes
   * later, after checking that B's row is still owed and the truth carries no stale mark.
   */
  private suspend fun TestScope.valueAfterOwnerStops(c: Ordering, b: Ordering): Set<String>? {
    val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
    val data =
      testDataScopeFactory(clock = testClock(START)).create(owner, setOf(Tags.sender(send = send)))
    val store = data.optimisticStore(Tags, base = base, apply = ::addOnce)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    backgroundScope.launch { store.data.collect {} }
    runCurrent()

    failWrites["c"] = 1
    failWrites["b"] = 1
    store.mutate(Add("c"), c).awaitSucceeded()
    advanceTimeBy(1.milliseconds)
    runCurrent()
    fetch.respond = { listOf("a", "c") }
    query.requestFetch(force = true).awaitSucceeded()
    val sent = store.mutate(Add("b"), b)
    sent.awaitSucceeded()
    runCurrent()
    // C's retry runs at one second, B's a millisecond later
    advanceTimeBy(999.milliseconds)
    runCurrent()
    owner.cancel()
    advanceTimeBy(30.minutes)
    runCurrent()

    val row = checkNotNull(data.outboxStore<Add>("tags").entry(sent.id).first())
    assertThat(row.status to row.committed).isEqualTo(OutboxStatus.Sent to false)
    assertThat(truth.data.value?.invalidated).isEqualTo(false)
    return store.data.first()?.value?.toSet()
  }

  @Test
  fun `a sent change of one line still shows once the owner stops after another line's retry`() =
    runTest {
      assertThat(valueAfterOwnerStops(c = Ordering.Keyed("y"), b = Ordering.Keyed("x")))
        .isEqualTo(setOf("a", "b", "c"))
    }

  @Test
  fun `on one line a sent change shows after the owner stops with its commit owed`() = runTest {
    assertThat(valueAfterOwnerStops(c = Ordering.Serial, b = Ordering.Serial))
      .isEqualTo(setOf("a", "b", "c"))
  }

  @Test
  fun `a keyed entry with an empty key joins the serial line of a single store`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Tags.sender(send = send)),
      )
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)

    store.mutate(Add("b"), Ordering.Keyed("")).stored.awaitSucceeded()
    store.mutate(Add("c")).stored.awaitSucceeded()

    // Both rows share the whole queue's row key, so the keyed one is not a line of its own
    assertThat(data.outboxStore<Add>("tags").entries().first().map { it.key })
      .isEqualTo(listOf("", ""))
  }

  @Test
  fun `owed sends on two lines commit after a restart and leave nothing behind`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    val rows = scopes.outboxStore<KeyedMutation<Unit, Add>>("tags")
    // Rows a process sent on two lines and died before it committed either
    for ((line, tag) in listOf("{}/x" to "b", "{}/y" to "c")) {
      val id = rows.insertNew(key = line, payload = KeyedMutation(Unit, Add(tag)))
      val row = checkNotNull(rows.entry(id).first())
      rows.update(
        row.copy(status = OutboxStatus.Sent, sentAt = START + 1.seconds, committed = false),
        expected = OutboxStatus.Queued,
      )
    }
    advanceTimeBy(2.seconds)
    val data = scopes.create(backgroundScope, setOf(UnitTags.sender { _, _ -> }))
    val sets =
      data.optimisticStores<Unit, List<String>, Add>(
        UnitTags,
        base = { truth },
        apply = ::addOnce,
      )
    val store = sets(Unit)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()
    advanceTimeBy(1.seconds)
    runCurrent()

    assertThat(seen.all { it == setOf("a", "b", "c") }).isTrue()
    assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "b", "c"))
    assertThat(rows.owed()).isEmpty()
    advanceTimeBy(15.minutes)
    runCurrent()
    // A fetch write prunes records
    store.writeFetched(Fetched(listOf("a", "b", "c"), data.clock.now())) { _, _ -> false }
    runCurrent()
    assertThat(sets.committedRecords).isEqualTo(0)
    assertThat(sets.landedCommits).isEqualTo(0)
    assertThat(sets.knownChanges).isEqualTo(0)
    data.assertNoFailures()
  }

  @Test
  fun `a clear between two lines' commits keeps the owed change out of the cleared truth`() =
    runTest {
      val data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
      val store = data.optimisticStore(Tags, base = base, apply = ::addOnce)
      val seen = mutableListOf<Set<String>?>()
      backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
      runCurrent()

      failWrites["c"] = 1
      store.mutate(Add("c"), Ordering.Keyed("y")).awaitSucceeded()
      store.mutate(Add("b"), Ordering.Keyed("x")).awaitSucceeded()
      runCurrent()
      assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "b", "c"))
      val beforeClear = seen.size
      store.clear()
      runCurrent()
      advanceTimeBy(1.minutes)
      runCurrent()

      assertThat(truth.data.value).isEqualTo(null)
      assertThat(seen.drop(beforeClear + 1).all { it == null }).isTrue()
      assertThat(data.outboxStore<Add>("tags").owed()).isEmpty()
    }

  @OptIn(DelicateDataLayerApi::class)
  @Test
  fun `three concurrent lines with two failed commit writes and a slow fetch keep every change`() =
    runTest {
      val data =
        testDataScope(
          clock = testClock(START),
          senders = setOf(UnitTags.sender { change, id -> send(change.mutation, id) }),
        )
      val sets =
        data.optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { base },
          apply = ::addOnce,
        )
      val store = sets(Unit)
      val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
      val seen = mutableListOf<Set<String>?>()
      backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
      runCurrent()
      // Reads the server before every send and lands in the middle
      fetch.respond = { listOf("a", "z") }
      fetch.hold()
      val refresh = query.requestFetch(force = true)
      advanceTimeBy(10.milliseconds)

      failWrites["b"] = 1
      failWrites["c"] = 1
      val marks = mutableListOf<Pair<String, Int>>()
      for (tag in listOf("b", "c", "d")) {
        marks += tag to seen.size
        store.mutate(Add(tag), Ordering.Concurrent).awaitSucceeded()
        advanceTimeBy(100.milliseconds)
      }
      fetch.release()
      refresh.awaitSucceeded()
      advanceTimeBy(1.minutes)
      runCurrent()

      for ((tag, from) in marks) {
        assertThat(seen.drop(from).filter { tag !in it.orEmpty() }).isEmpty()
      }
      assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "z", "b", "c", "d"))
      assertThat(data.outboxStore<KeyedMutation<Unit, Add>>("tags").owed()).isEmpty()
      advanceTimeBy(15.minutes)
      store.writeFetched(Fetched(listOf("a", "z", "b", "c", "d"), data.clock.now())) { _, _ ->
        false
      }
      runCurrent()
      assertThat(sets.committedRecords).isEqualTo(0)
      assertThat(sets.landedCommits).isEqualTo(0)
      assertThat(sets.knownChanges).isEqualTo(0)
    }

  /**
   * C sends on ordering [c], and its commit fails once on its base write. A forced fetch from after
   * C's send lands with c. P sends on ordering [p] and fails its first write too, so P's record
   * holds a value whose write is owed. C's retry lands first and writes nothing, since the truth
   * was fetched after C's send. B on ordering [b] commits last. Every value the view emits from
   * each send on must hold that send's change, and once the truth holds every change the set
   * forgets them all.
   */
  private suspend fun TestScope.assertThreeCommitsKeepEveryChange(
    c: Ordering,
    p: Ordering,
    b: Ordering,
  ) {
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(UnitTags.sender { change, id -> send(change.mutation, id) }),
      )
    val sets =
      data.optimisticStores<Unit, List<String>, Add>(UnitTags, base = { base }, apply = ::addOnce)
    val store = sets(Unit)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()

    failWrites["c"] = 1
    failWrites["p"] = 1
    val fromC = seen.size
    store.mutate(Add("c"), c).awaitSucceeded()
    advanceTimeBy(1.milliseconds)
    runCurrent()
    fetch.respond = { listOf("a", "c") }
    query.requestFetch(force = true).awaitSucceeded()
    advanceTimeBy(1.milliseconds)
    val fromP = seen.size
    store.mutate(Add("p"), p).awaitSucceeded()
    runCurrent()
    // C's retry, which writes nothing, lands before P's
    advanceTimeBy(998.milliseconds)
    runCurrent()
    val fromB = seen.size
    store.mutate(Add("b"), b).awaitSucceeded()
    runCurrent()
    advanceTimeBy(10.seconds)
    runCurrent()

    assertThat(seen.drop(fromC).filter { "c" !in it.orEmpty() }).isEmpty()
    assertThat(seen.drop(fromP).filter { "p" !in it.orEmpty() }).isEmpty()
    assertThat(seen.drop(fromB).filter { "b" !in it.orEmpty() }).isEmpty()
    val expected = setOf("a", "b", "c", "p")
    // Every retry lands in the end
    advanceTimeBy(2.minutes)
    runCurrent()
    assertThat(truth.data.value?.value?.toSet()).isEqualTo(expected)
    assertThat(store.data.first()?.value?.toSet()).isEqualTo(expected)
    assertThat(data.outboxStore<KeyedMutation<Unit, Add>>("tags").owed()).isEmpty()
    advanceTimeBy(15.minutes)
    // A fetch write prunes records
    store.writeFetched(Fetched(expected.toList(), data.clock.now())) { _, _ -> false }
    runCurrent()
    assertThat(sets.committedRecords).isEqualTo(0)
    assertThat(sets.landedCommits).isEqualTo(0)
    assertThat(sets.knownChanges).isEqualTo(0)
  }

  @Test
  fun `three lines where the middle commit writes nothing keep every change`() = runTest {
    assertThreeCommitsKeepEveryChange(Ordering.Keyed("y"), Ordering.Keyed("x"), Ordering.Keyed("z"))
  }

  @Test
  fun `on one line the three-commit sequence keeps every change`() = runTest {
    assertThreeCommitsKeepEveryChange(Ordering.Serial, Ordering.Serial, Ordering.Serial)
  }

  /**
   * C on line y sends and its commit write keeps failing, then the owner stops with C's commit
   * owed. A new data scope over the same rows and truth sends B on line x. Every value the new view
   * emits must hold c, every value from B's send on must hold b, and the truth must end with both.
   */
  @Test
  fun `an owner that stops between two lines' sends keeps both changes after a restart`() =
    runTest {
      val scopes = testDataScopeFactory(clock = testClock(START))
      val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
      val sender = UnitTags.sender { change, id -> send(change.mutation, id) }
      val earlier =
        scopes
          .create(owner, setOf(sender))
          .optimisticStores<Unit, List<String>, Add>(UnitTags, base = { base }, apply = ::addOnce)
      backgroundScope.launch { earlier(Unit).data.collect {} }
      runCurrent()
      failWrites["c"] = 100
      earlier(Unit).mutate(Add("c"), Ordering.Keyed("y")).awaitSucceeded()
      runCurrent()
      owner.cancel()
      runCurrent()
      failWrites["c"] = 0

      val later = scopes.create(backgroundScope, setOf(sender))
      val sets =
        later.optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { base },
          apply = ::addOnce,
        )
      val seen = mutableListOf<Set<String>?>()
      backgroundScope.launch { sets(Unit).data.collect { seen += it?.value?.toSet() } }
      runCurrent()
      val fromB = seen.size
      sets(Unit).mutate(Add("b"), Ordering.Keyed("x")).awaitSucceeded()
      runCurrent()
      advanceTimeBy(2.minutes)
      runCurrent()

      assertThat(seen.filter { "c" !in it.orEmpty() }).isEmpty()
      assertThat(seen.drop(fromB).filter { "b" !in it.orEmpty() }).isEmpty()
      assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "b", "c"))
      advanceTimeBy(15.minutes)
      // A fetch write prunes records
      sets(Unit).writeFetched(Fetched(listOf("a", "b", "c"), later.clock.now())) { _, _ -> false }
      runCurrent()
      assertThat(sets.committedRecords).isEqualTo(0)
      assertThat(sets.landedCommits).isEqualTo(0)
      assertThat(sets.knownChanges).isEqualTo(0)
    }

  /**
   * B on line x fails its first base write. Boom on line y throws in apply while it commits, so the
   * set drops it and reports the failure. C on line y then sends. Every value from C's send on must
   * hold b and c, and the truth must end with both.
   */
  @Test
  fun `a commit whose apply throws on one line leaves the other line's changes`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(UnitTags.sender { change, id -> send(change.mutation, id) }),
      )
    var throwing = false
    val store =
      data.optimisticStores<Unit, List<String>, Add>(
        UnitTags,
        base = { base },
        apply = { current, add ->
          if (add.tag == "boom" && throwing) {
            error("boom")
          }
          addOnce(current, add)
        },
      )(Unit)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()

    failWrites["b"] = 1
    store.mutate(Add("b"), Ordering.Keyed("x")).awaitSucceeded()
    throwing = true
    store.mutate(Add("boom"), Ordering.Keyed("y")).awaitSucceeded()
    runCurrent()
    throwing = false
    val fromC = seen.size
    store.mutate(Add("c"), Ordering.Keyed("y")).awaitSucceeded()
    runCurrent()
    advanceTimeBy(2.minutes)
    runCurrent()

    assertThat(seen.drop(fromC).filter { !it.orEmpty().containsAll(setOf("b", "c")) }).isEmpty()
    assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "b", "c"))
    // B's failed write, and boom's apply, which the set reports with apply's exception as the cause
    assertThat(data.actionFailures.take().map { it.cause?.message ?: it.message })
      .containsExactly("Disk full", "boom")
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** A tag list's outbox, whose server answers nothing. */
private val Tags = OutboxKey<Add>("tags")

/** A tag list set keyed by [Unit], whose server answers nothing. */
private val UnitTags = OutboxKey<KeyedMutation<Unit, Add>>("tags")
