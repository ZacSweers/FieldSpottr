// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.clearFetched
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** What a settled handle keeps reachable, checked by walking the object graph from it. */
class HandleRetentionTest {

  @Test
  fun `a settled handle does not keep its command's inputs`() = runTest {
    val input = ByteArray(1 shl 20)
    val handle = testDataScope().newActionRunner().run { check(input.isNotEmpty()) }
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(input)).isFalse()
  }

  @Test
  fun `a settled recheck does not keep the value that went away`() = runTest {
    val cleared = ByteArray(1 shl 20)
    val store = QueryStore.inMemory(Fetched(cleared, TestClock.DEFAULT_START))
    val fetch = TestFetch { ByteArray(1) }
    val query =
      testDataScope().query(store, freshness = Freshness.staleAfter(1.hours), fetch = fetch)
    val collector = backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()

    fetch.hold()
    store.update { null }
    testScheduler.runCurrent()
    // Joins the recheck the collector started for the clear
    val handle = query.requestFetch(force = false)
    fetch.release()
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(handle.reaches(cleared)).isFalse()
  }

  @Test
  fun `a settled handle does not keep its runner's failure handler`() = runTest {
    val reports = ByteArray(1 shl 20)
    val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
    val handle = RealActionRunner(owner, onFailure = { reports[0] = 1 }).run {}
    testScheduler.runCurrent()
    owner.cancel()
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(reports)).isFalse()
  }

  @Test
  fun `a superseded handle does not keep the failure of the command before it`() = runTest {
    val actions = RealActionRunner(backgroundScope, onFailure = {})
    val response = ByteArray(1 shl 20)
    val release = CompletableDeferred<Unit>()
    val failed =
      actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {
        release.await()
        throw ResponseException(response)
      }
    testScheduler.runCurrent()
    // Queued behind the running command, then replaced before it starts
    val superseded = actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {}
    val newest = actions.run(Ordering.Keyed("k", OnPrevious.Supersede)) {}
    release.complete(Unit)
    testScheduler.runCurrent()

    failed.assertFailed<ResponseException>()
    superseded.assertCancelled()
    newest.assertSucceeded()
    assertThat(actions.keysHeld).isEqualTo(0)
    assertThat(superseded.reaches(response)).isFalse()
    // A failed handle keeps its own result
    assertThat(failed.reaches(response)).isTrue()
  }

  @Test
  fun `a query does not keep a failed automatic check after a later refresh`() = runTest {
    val state = MutableStateFlow<Fetched<String>?>(Fetched("old", TestClock.DEFAULT_START))
    val store =
      QueryStore.of(
        state,
        clear = state::clearFetched,
        write = { fetched, keepsStored ->
          state.writeFetched(fetched, keepsStored)
        },
      )
    var thrown: ResponseException? = null
    val fetch =
      TestFetch<String> {
        throw ResponseException(ByteArray(1 shl 20)).also { thrown = it }
      }
    val query =
      DataScope.Factory(onFailure = {}, clock = testClock())
        .create(backgroundScope)
        .query(store, freshness = Freshness.staleAfter(1.hours), fetch = fetch)
    val collector = backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()

    // Emptied under the collector, whose automatic check then fails
    state.value = null
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()
    fetch.respond = { "new" }
    query.requestFetch(force = true)
    testScheduler.runCurrent()

    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
    assertThat(state.value?.value).isEqualTo("new")
    assertThat(query.reaches(checkNotNull(thrown).response)).isFalse()
  }

  @Test
  fun `a query does not keep its first failed check after a refresh under the same collector`() =
    runTest {
      val error = ResponseException(ByteArray(1 shl 20))
      val fetch = TestFetch<String> { throw error }
      val store = QueryStore.inMemory<String>()
      val query =
        DataScope.Factory(onFailure = {}, clock = testClock())
          .create(backgroundScope)
          .query(store, freshness = Freshness.staleAfter(1.hours), fetch = fetch)
      // Stays subscribed throughout, so its watcher outlives the failed check it started with
      val collector = backgroundScope.launch { query.data.collect {} }
      testScheduler.runCurrent()
      assertThat(query.status.value).isEqualTo(FetchStatus.Failed(error))

      fetch.respond = { "fresh" }
      val refresh = query.requestFetch(force = true)
      testScheduler.runCurrent()

      refresh.assertSucceeded()
      assertThat(store.data.value?.value).isEqualTo("fresh")
      assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
      assertThat(collector.isActive).isTrue()
      assertThat(query.reaches(error.response)).isFalse()
    }

  @Test
  fun `a runner does not keep a cancelled command once it settles ahead of a running one`() =
    runTest {
      // A scope of its own, so a walk from the runner cannot reach this test's locals through a
      // parent job
      val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
      val actions = RealActionRunner(owner, onFailure = {})
      val release = CompletableDeferred<Unit>()
      val running = actions.run(Ordering.Keyed("k")) { release.await() }
      testScheduler.runCurrent()
      val cancelled = actions.runCancellable(Ordering.Keyed("k")) {}
      cancelled.cancel()
      testScheduler.runCurrent()

      cancelled.assertCancelled()
      running.assertRunning()
      // The running command still holds the key, and the cancelled one is gone from it
      assertThat(actions.keysHeld).isEqualTo(1)
      assertThat(actions.reaches(running)).isTrue()
      assertThat(actions.reaches(cancelled)).isFalse()

      release.complete(Unit)
      testScheduler.runCurrent()
      running.assertSucceeded()
      assertThat(actions.keysHeld).isEqualTo(0)
      owner.cancel()
    }

  @Test
  fun `a runner keeps none of the commands replaced behind a running one`() = runTest {
    val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    val actions = RealActionRunner(owner, onFailure = {})
    val release = CompletableDeferred<Unit>()
    val saving = Ordering.Keyed("draft", OnPrevious.Supersede)
    val running = actions.run(saving) { release.await() }
    testScheduler.runCurrent()

    // One more than a round number, so a cleanup that runs in batches would leave some behind
    val saves = List(65) { actions.run(saving) {} }
    testScheduler.runCurrent()

    val replaced = saves.dropLast(1)
    replaced.forEach { it.assertCancelled() }
    // Each replaced command is gone, however many came while the first one runs
    assertThat(replaced.count { actions.reaches(it) }).isEqualTo(0)
    assertThat(actions.reaches(saves.last())).isTrue()
    assertThat(actions.reaches(running)).isTrue()

    release.complete(Unit)
    testScheduler.runCurrent()
    saves.last().assertSucceeded()
    assertThat(actions.keysHeld).isEqualTo(0)
    owner.cancel()
  }

  @Test
  fun `a runner keeps no replaced command when each one arrives after the last one waits`() =
    runTest {
      val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
      val actions = RealActionRunner(owner, onFailure = {})
      val release = CompletableDeferred<Unit>()
      val saving = Ordering.Keyed("draft", OnPrevious.Supersede)
      actions.run(saving) { release.await() }
      testScheduler.runCurrent()

      // Each save already waits for the running command when the next one replaces it
      val saves =
        List(65) {
          actions.run(saving) {}.also { testScheduler.runCurrent() }
        }

      assertThat(saves.dropLast(1).count { actions.reaches(it) }).isEqualTo(0)
      release.complete(Unit)
      testScheduler.runCurrent()
      saves.last().assertSucceeded()
      owner.cancel()
    }

  @Test
  fun `a runner keeps no replaced command when replacements come from another thread`() {
    // Commands run on a thread of their own, and this test's thread submits them
    val executor = Executors.newSingleThreadExecutor()
    val owner = CoroutineScope(executor.asCoroutineDispatcher() + Job())
    // Runs everything the runner's thread has queued so far, so each command reached its wait
    val drain = { executor.submit(Runnable {}).get(10, SECONDS) }
    try {
      val actions = RealActionRunner(owner, onFailure = {})
      val release = CompletableDeferred<Unit>()
      val saving = Ordering.Keyed("draft", OnPrevious.Supersede)
      val running = actions.run(saving) { release.await() }
      drain()

      // Each save already waits for the running command when the next one replaces it
      val saves = List(65) { actions.run(saving) {}.also { drain() } }

      running.assertRunning()
      assertThat(saves.dropLast(1).count { actions.reaches(it) }).isEqualTo(0)
      assertThat(actions.reaches(saves.last())).isTrue()
      release.complete(Unit)
      runBlocking { withTimeout(10_000) { saves.last().await() } }
      saves.last().assertSucceeded()
    } finally {
      owner.cancel()
      executor.shutdown()
    }
  }

  @Test
  fun `a runner does not keep a cancelled command between a running and a queued one`() = runTest {
    val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
    val actions = RealActionRunner(owner, onFailure = {})
    val release = CompletableDeferred<Unit>()
    val running = actions.run(Ordering.Keyed("k")) { release.await() }
    testScheduler.runCurrent()
    val cancelled = List(3) { actions.runCancellable(Ordering.Keyed("k")) {} }
    val queued = actions.run(Ordering.Keyed("k")) {}
    testScheduler.runCurrent()

    cancelled.forEach { it.cancel() }
    testScheduler.runCurrent()

    cancelled.forEach { it.assertCancelled() }
    // The queued command now waits for the running one alone
    assertThat(cancelled.count { actions.reaches(it) }).isEqualTo(0)
    assertThat(actions.reaches(queued)).isTrue()
    release.complete(Unit)
    testScheduler.runCurrent()
    running.assertSucceeded()
    queued.assertSucceeded()
    owner.cancel()
  }

  @Test
  fun `a waiting cancel command does not keep a cancelled command of another mode ahead of it`() =
    runTest {
      val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
      val actions = RealActionRunner(owner, onFailure = {})
      val release = CompletableDeferred<Unit>()
      val running = actions.run(Ordering.Keyed("k")) { release.await() }
      testScheduler.runCurrent()
      // Wait commands, which a cancel command waits for but never cancels
      val cancelled = List(3) { actions.runCancellable(Ordering.Keyed("k")) {} }
      val queued = actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {}
      testScheduler.runCurrent()

      cancelled.forEach { it.cancel() }
      testScheduler.runCurrent()

      cancelled.forEach { it.assertCancelled() }
      assertThat(cancelled.count { actions.reaches(it) }).isEqualTo(0)
      assertThat(actions.reaches(queued)).isTrue()
      release.complete(Unit)
      testScheduler.runCurrent()
      running.assertSucceeded()
      queued.assertSucceeded()
      owner.cancel()
    }

  @Test
  fun `a settled handle does not keep its join key or its conflict key`() = runTest {
    val joinKey = ByteArray(1 shl 20)
    val conflictKey = ByteArray(1 shl 20)
    val actions = testDataScope().newActionRunner()
    val handle = actions.run(joinKey = joinKey, conflictKey = conflictKey) {}
    val rejected = actions.run(conflictKey = conflictKey) {}
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(joinKey)).isFalse()
    assertThat(handle.reaches(conflictKey)).isFalse()
    // A rejected handle names the key that rejected it, for the caller to read
    assertThat(rejected.assertFailed<CommandRejectedException>().conflictKey)
      .isSameInstanceAs(conflictKey)
  }

  @Test
  fun `a settled handle does not keep its key`() = runTest {
    val key = ByteArray(1 shl 20)
    val handle = testDataScope().newActionRunner().run(Ordering.Keyed(key)) {}
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(handle.reaches(key)).isFalse()
  }
}

/** A failure that carries the response that caused it, like a server error body. */
private class ResponseException(val response: ByteArray) : Exception("Rejected")

/**
 * Whether [target] is reachable from this object through instance fields, arrays, collections, maps
 * and atomic references. JDK fields that reflection cannot open are read through those interfaces
 * instead.
 */
internal fun Any.reaches(target: Any): Boolean {
  val seen = IdentityHashMap<Any, Unit>()
  val queue = ArrayDeque<Any>()
  queue += this
  while (queue.isNotEmpty()) {
    val next = queue.removeFirst()
    if (next === target) {
      return true
    }
    // Classes, threads and loaders lead to everything, and none of them is how a handle holds data
    if (next is Class<*> || next is Thread || next is ClassLoader || seen.put(next, Unit) != null) {
      continue
    }
    check(seen.size < 1_000_000) { "The graph from $this is too large to walk" }
    when (next) {
      is Array<*> -> next.filterNotNullTo(queue)
      is Collection<*> -> next.filterNotNullTo(queue)
      is Map<*, *> -> next.forEach { (key, value) -> listOfNotNull(key, value).toCollection(queue) }
      is AtomicReference<*> -> next.get()?.let(queue::add)
    }
    var type: Class<*>? = next.javaClass
    while (type != null && !type.isArray) {
      for (field in type.declaredFields) {
        val readable =
          !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive && field.trySetAccessible()
        if (readable) {
          field.get(next)?.let(queue::add)
        }
      }
      type = type.superclass
    }
  }
  return false
}
