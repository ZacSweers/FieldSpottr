// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.MutableQueryStore
import dev.zacsweers.fieldspottr.data.core.MutableQueryStores
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PendingMutation
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.isSettled
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MutableQueryStores] over one outbox, whose rows live in [rows]. Each key's store is a view: the
 * key's base store, its truth, with that key's rows in flight applied on top.
 *
 * A sent change reaches the truth in two writes, the base store's and the row's status, which no
 * store makes atomic, and a fetch that started before the send finished can land after both with a
 * server state that lacks the change. So the set keeps a record of each sent change in [committed]:
 * what the truth held once its commit landed, and when the send finished. A view shows the record
 * in place of its row while the row is still there, and applies the change again on top of any
 * truth fetched before the send, so the value never shows the change twice or loses it until a
 * fetch from after the send says what the server holds. A fetch that overlapped the send is written
 * with the change folded in, so a restart, which has no records, keeps it too.
 *
 * Only a key's newest record keeps a copy of the value, which is enough to tell that the truth
 * holds it and every change before it. The older ones keep just the change and its send time, which
 * is all a slow fetch needs. A record goes once a fetch from after its send lands, or once it is
 * old enough that no fetch from before its send should still be running, so a key that is never
 * fetched again does not keep it for good.
 *
 * Two cases stay outside what the records can tell. A fetch that overlapped the send may have read
 * the server after it took the change, and a process that dies between the truth write and the
 * row's status relaunches with the row still sending and the change already in the truth. In both,
 * [apply] gets a value that already holds the change, so [apply] must be idempotent, as
 * [dev.zacsweers.fieldspottr.data.core.mutableStore] requires. Each commit checks this on the value
 * it folded, and the first change found not to be idempotent is reported to [onFailure], once.
 *
 * Each key's truth has two writers, the query's fetch and the outbox's commit, and they take turns
 * on the key's lock. A fetch write is dropped when the truth's fetch time has moved past the fetch
 * meanwhile, the rule the query itself applies before it writes, applied here where the writers
 * meet, so a commit from a later time is never written over. A write or clear also waits for the
 * rows requested before it to be in the store, so the value it publishes never lacks a change
 * already made.
 */
@OptIn(DelicateDataLayerApi::class, ExperimentalAtomicApi::class)
internal class RealMutableQueryStores<K : Any, T, M>(
  private val rows: OutboxStore<KeyedMutation<K, M>>,
  /** Builds the outbox over [rows] that drains with the send it is given. */
  outbox: (send: suspend (KeyedMutation<K, M>, EntryId) -> Unit) -> Outbox<KeyedMutation<K, M>>,
  private val base: (key: K) -> QueryStore<T>,
  private val apply: (current: T?, mutation: M) -> T?,
  private val deliver: suspend (key: K, mutation: M, id: EntryId) -> Delivery<T>,
  /** The clock the queries' fetch times come from, so a send time compares against them. */
  private val clock: Clock,
  /** Hears an [apply] that is not idempotent, once. */
  private val onFailure: (Throwable) -> Unit,
  /**
   * The context each view computes its overlay in: the data scope's dispatcher with no job
   * attached, so [apply] never runs on a collector's thread, such as the main thread.
   */
  private val context: CoroutineContext,
) : MutableQueryStores<K, T, M> {
  // Sent changes in commit order, kept until a fetch from after the send confirms each one or it
  // expires
  private val committed = MutableStateFlow(persistentListOf<Committed<K, T, M>>())
  // Whether an apply that is not idempotent has been reported. Once is enough to fix it.
  private val reportedApply = AtomicBoolean(false)
  // One base store per key, shared by the key's views and its commits, so a store that keeps state
  // of its own, like one that caches what it loaded, sees every write. Kept for as long as this set
  // lives, as mutableStores promises. Updated by compare-and-set only.
  private val bases = AtomicReference(persistentHashMapOf<K, Base<T>>())
  // Changes whose rows are still being written, by key, so a write or clear can wait for the ones
  // requested before it. Settled entries are dropped as new ones come in. Updated by
  // compare-and-set only.
  private val storing = AtomicReference(persistentListOf<Storing<K>>())
  private val outbox = outbox(::send)

  /** How many sent changes are still on record. For tests, which cannot see pruning otherwise. */
  internal val committedRecords: Int
    get() = committed.value.size

  /** How many records still keep a copy of the value. For tests, which cannot see it otherwise. */
  internal val committedValues: Int
    get() = committed.value.count { it.hasValue }

  override fun invoke(key: K): MutableQueryStore<T, M> = KeyStore(key, baseFor(key))

  /** [key]'s base store, built on first use. The copy that loses a race was never written to. */
  private fun baseFor(key: K): Base<T> {
    while (true) {
      val current = bases.load()
      current[key]?.let {
        return it
      }
      val created = Base(base(key))
      if (bases.compareAndSet(current, current.putting(key, created))) {
        return created
      }
    }
  }

  override fun mutate(key: K, mutation: M): OutboxOperation {
    val handle = outbox.enqueue(KeyedMutation(key, mutation), key = key.toString())
    storing.update { all ->
      all
        .filter { it.stored.state.value !is OperationState.Terminal }
        .toPersistentList()
        .adding(Storing(key, handle.stored))
    }
    return handle
  }

  /** Waits until every change for [key] requested so far is in the store, or failed to be. */
  private suspend fun awaitStored(key: K) {
    for (entry in storing.load()) {
      if (entry.key == key) {
        entry.stored.await()
      }
    }
  }

  override fun pending(key: K): Flow<List<PendingMutation<M>>> =
    rows
      .entries()
      .map { all -> all.filter { it.payload.key == key }.map { it.toPending() } }
      .distinctUntilChanged()

  override val pendingCount: Flow<Int> = rows.entries().map { it.size }.distinctUntilChanged()

  override fun retry(id: EntryId): Operation = outbox.retry(id)

  override fun discard(id: EntryId): Operation = outbox.discard(id)

  /**
   * The outbox's send: the remote call through [deliver], with the row's id as the request's
   * idempotency key, then the change's move into the truth. A commit that fails, like a base store
   * whose disk is full, is reported to [onFailure] and the send still succeeds, since the server
   * has the change and another attempt would only send it again. The change's record keeps it in
   * the view until a fetch from after the send replaces the truth, or until the record expires.
   */
  private suspend fun send(payload: KeyedMutation<K, M>, id: EntryId) {
    val result = deliver(payload.key, payload.mutation, id)
    val sentAt = clock.now()
    // The server has the change, so the truth takes it even if this owner stops now
    withContext(NonCancellable) {
      runCatchingCancellable { commit(id, payload, result, sentAt) }.onFailure(onFailure)
    }
  }

  /**
   * Moves the change in [payload], row [id], into the truth after a successful send. When the
   * server answered with a value, that is what it holds now, and it replaces the truth as fresh
   * data. When it answered with nothing, the change is applied to the truth as the view currently
   * shows it, which already includes earlier sent changes that a fetch may have left out, and the
   * truth keeps its old fetch time. The one exception is a fetch that started after the send
   * finished: it read the server with the change already in it, so the truth is left as it is.
   *
   * Runs on the key's lock, so no fetch write lands between the read and the write here. A row that
   * a clear discarded while the send ran has no truth to land in, so its change is dropped.
   */
  private suspend fun commit(
    id: EntryId,
    payload: KeyedMutation<K, M>,
    result: Delivery<T>,
    sentAt: Instant,
  ) {
    val key = payload.key
    val base = baseFor(key)
    base.lock.withLock {
      if (rows.entry(id).first()?.status != OutboxStatus.Sending) {
        return
      }
      val current = base.store.data.first()
      val sent = committed.value.filter { it.key == key }
      val alreadyFetched = current != null && current.fetchedAt > sentAt
      val replaced = result is Delivery.Replaced
      val next =
        when {
          result is Delivery.Replaced -> Fetched(result.value, sentAt)
          alreadyFetched -> current
          else -> {
            val folded = applied(withSent(current, sent), payload.mutation)
            checkIdempotent(folded, payload.mutation)
            folded
          }
        }
      // Views show this in place of the row from here, so the writes below never show as a gap.
      // This key's records that the truth already confirms can go, and so can expired ones.
      val live = rows.entries().first().mapTo(HashSet()) { it.id }
      val now = clock.now()
      committed.update { all ->
        all
          .filter { keeps(it, key, live, current?.fetchedAt, now) }
          .toPersistentList()
          .adding(Committed(id, key, payload.mutation, sentAt, next, replaced))
      }
      if (!alreadyFetched) {
        if (next == null) {
          base.store.clear()
        } else {
          base.store.write(next.value, next.fetchedAt)
        }
      }
      // The store holds this record's value now, which tells views that it holds every change
      // before it too. So the key's older records need only what a slow fetch folds back in.
      committed.update { all ->
        all
          .map {
            if (it.key == key && it.id != id) {
              it.withoutValue()
            } else {
              it
            }
          }
          .toPersistentList()
      }
    }
  }

  /**
   * Reports [mutation] once if applying it to [folded], which already holds it, changes the value.
   * That is the value a fetch that overlapped the send, or a resend after a restart, can hand
   * [apply], and the store cannot tell it from one that lacks the change, so such a change would
   * show twice until a fetch from after the send replaced it. The check costs one more [apply] per
   * commit, which the contract keeps cheap.
   */
  private fun checkIdempotent(folded: Fetched<T>?, mutation: M) {
    if (reportedApply.load()) {
      return
    }
    val again = apply(folded?.value, mutation)
    if (again == folded?.value) {
      return
    }
    if (reportedApply.compareAndSet(expectedValue = false, newValue = true)) {
      onFailure(
        IllegalStateException(
          "apply is not idempotent for $mutation: applied to a value that already holds the " +
            "change, it changed the value again. Make apply return such a value unchanged, like " +
            "an append that skips an item already there, or build the store with exchange so the " +
            "server's value replaces the truth."
        )
      )
    }
  }

  /**
   * Whether [record] still matters at [now] given a truth for [key] fetched at [fetchedAt]: its row
   * is still among [live], or it has not expired and is another key's or newer than the truth.
   */
  private fun keeps(
    record: Committed<K, T, M>,
    key: K,
    live: Set<EntryId>,
    fetchedAt: Instant?,
    now: Instant,
  ): Boolean {
    if (record.id in live) {
      return true
    }
    if (record.isExpired(now)) {
      return false
    }
    return record.key != key || fetchedAt == null || fetchedAt <= record.sentAt
  }

  /**
   * Drops [key]'s records that a truth fetched at [fetchedAt] confirms, once their rows have left,
   * and every key's expired records. Called after a fetch writes through a view.
   */
  private suspend fun prune(key: K, fetchedAt: Instant) {
    val now = clock.now()
    if (committed.value.none { (it.key == key && it.sentAt < fetchedAt) || it.isExpired(now) }) {
      return
    }
    val live = rows.entries().first().mapTo(HashSet()) { it.id }
    committed.update { all ->
      all.filter { keeps(it, key, live, fetchedAt, now) }.toPersistentList()
    }
  }

  /**
   * Discards [key]'s rows the server has not confirmed, sending ones included, by writing their
   * status, so they drop out whether or not the outbox still runs, like after its scope was
   * cancelled for a logout. Handles that follow them settle Cancelled through the row, and the
   * outbox's watcher moves the key on. A row left sending by an owner that stopped would send again
   * under the next owner, so it goes too. A drain still sending it finds the row gone when it
   * reports the result, and [commit] drops the change. The drainer can move a row meanwhile, by
   * claiming it, backing it off or requeueing it for a retry, so each compare-and-set goes round
   * until it wins or the row is settled. A row cannot become Sent meanwhile, since that waits for
   * [commit], which waits for the key's lock that the caller holds.
   */
  private suspend fun discardPending(key: K) {
    for (row in rows.entries().first()) {
      if (row.payload.key != key) {
        continue
      }
      var current: OutboxEntry<KeyedMutation<K, M>>? = row
      while (current != null && !current.status.isSettled) {
        val discarded = current.copy(status = OutboxStatus.Discarded, reverted = true)
        if (rows.update(discarded, expected = current.status)) {
          break
        }
        current = rows.entry(row.id).first()
      }
    }
  }

  /**
   * [stored] with every sent change it may be missing put back, oldest first. A sent change is
   * already in [stored] in two cases. Either [stored] is the value that the change's own commit, or
   * a later one, wrote, since each commit folds onto the view and so carries the earlier changes
   * with it. Or [stored] was fetched after the change was sent, so the server already included it.
   * Every other sent change is missing, because [stored] came from a fetch that read the server too
   * early or from a commit whose write has not landed in the store yet. A missing change the server
   * answered with a value is put back as that value, which holds everything sent before it, since
   * applying the change again could only rebuild the client's version of it.
   */
  private fun withSent(stored: Fetched<T>?, sent: List<Committed<K, T, M>>): Fetched<T>? {
    // The newest record whose change the store holds. Every record up to it is in the truth.
    val held = sent.indexOfLast { it.isHeldBy(stored) }
    var result = stored
    sent.forEachIndexed { index, record ->
      val inTruth = index <= held || (stored != null && stored.fetchedAt > record.sentAt)
      if (inTruth) {
        return@forEachIndexed
      }
      result =
        if (record.replaced) {
          record.truth
        } else {
          applied(result, record.mutation)
        }
    }
    return result
  }

  /**
   * [stored] with [sent] changes it may lack and then [rows], oldest first, applied on top. A row
   * with a record is accounted for by the record, so it is not applied again.
   */
  private fun overlay(
    stored: Fetched<T>?,
    rows: List<OutboxEntry<KeyedMutation<K, M>>>,
    sent: List<Committed<K, T, M>>,
  ): Fetched<T>? {
    var result = withSent(stored, sent)
    for (row in rows) {
      if (sent.any { it.id == row.id }) {
        continue
      }
      result = applied(result, row.payload.mutation)
    }
    return result
  }

  /**
   * [stored] with [mutation] applied, keeping its fetch time, or [QueryStore.UNFETCHED] over
   * nothing stored. Null when the change removes the value.
   */
  private fun applied(stored: Fetched<T>?, mutation: M): Fetched<T>? {
    val value = apply(stored?.value, mutation) ?: return null
    return Fetched(value, stored?.fetchedAt ?: QueryStore.UNFETCHED)
  }

  private fun OutboxEntry<KeyedMutation<K, M>>.toPending() =
    PendingMutation(id, payload.mutation, status, attempts, lastError, retryAt)

  /**
   * [key]'s view: the truth with the key's sent and in-flight changes on top. The overlay runs on
   * [context], and only a value that differs from the last crosses to the collector.
   */
  private inner class KeyStore(private val key: K, private val base: Base<T>) :
    MutableQueryStore<T, M> {
    private val truth = base.store

    override val data: Flow<Fetched<T>?> =
      combine(truth.data, rows.entries(), committed) { stored, all, sent ->
          overlay(
            stored,
            all.filter { it.payload.key == key && it.status.isInFlight },
            sent.filter { it.key == key },
          )
        }
        .distinctUntilChanged()
        .flowOn(context)

    /**
     * The query's fetch write, once the changes requested before it are in the store, so the value
     * it publishes carries them. On the key's lock, a value older than what a commit wrote
     * meanwhile is dropped, as the query would have dropped it had it seen the commit first. A
     * truth time ahead of the clock is a bad record, which the fetch replaces.
     *
     * A send that finished after the fetch started may be missing from the value, and only its
     * in-memory record would show it. So the truth takes those changes too, as [withSent] puts them
     * back, and a restart that has no records still shows them.
     */
    override suspend fun write(value: T, fetchedAt: Instant) {
      awaitStored(key)
      base.lock.withLock {
        val storedAt = truth.data.first()?.fetchedAt
        val newerStored = storedAt != null && storedAt > fetchedAt && storedAt <= clock.now()
        if (!newerStored) {
          val sentSince = committed.value.filter { it.key == key && it.sentAt >= fetchedAt }
          val next = withSent(Fetched(value, fetchedAt), sentSince)
          // Before the write, so views count every change on record as in the folded truth both
          // before and after it lands, and never apply one twice
          sentSince.lastOrNull()?.let { newest ->
            committed.update { all ->
              all.map { if (it === newest) it.alsoHeldBy(next) else it }.toPersistentList()
            }
          }
          if (next == null) {
            truth.clear()
          } else {
            truth.write(next.value, next.fetchedAt)
          }
        }
      }
      prune(key, fetchedAt)
    }

    override suspend fun clear() {
      // A change still being stored would land after the rows below were read and then send
      awaitStored(key)
      base.lock.withLock {
        discardPending(key)
        // Nothing sent before the clear belongs on top of what comes after it
        committed.update { all -> all.filter { it.key != key }.toPersistentList() }
        truth.clear()
      }
    }

    // On the key's lock, so a commit that read the truth before this cannot write it back fresh
    override suspend fun invalidate() {
      base.lock.withLock { truth.invalidate() }
    }

    override fun mutate(mutation: M): OutboxOperation =
      this@RealMutableQueryStores.mutate(key, mutation)

    override val pending: Flow<List<PendingMutation<M>>> = this@RealMutableQueryStores.pending(key)

    override fun retry(id: EntryId): Operation = this@RealMutableQueryStores.retry(id)

    override fun discard(id: EntryId): Operation = this@RealMutableQueryStores.discard(id)
  }

  /**
   * A sent change: its row, what the truth held once its commit landed, and when the send finished.
   * A truth fetched after [sentAt] holds the change. One fetched before may not, so a view puts the
   * change back on top of it: [truth] itself when the server answered with that value, and
   * [replaced] says so, else the change applied again. [foldedInto] holds the values a fetch that
   * overlapped the send was written as, with this change and the ones before it folded in.
   */
  private class Committed<K, T, M>(
    val id: EntryId,
    val key: K,
    val mutation: M,
    val sentAt: Instant,
    val truth: Fetched<T>?,
    val replaced: Boolean,
    val foldedInto: List<T?> = emptyList(),
    val hasValue: Boolean = true,
  ) {
    /**
     * Whether [stored] already holds this change: its commit wrote it, or a fetch folded it in. A
     * record without its value cannot tell, and a newer record answers in its place.
     */
    fun isHeldBy(stored: Fetched<T>?): Boolean =
      hasValue && (truth?.value == stored?.value || stored?.value in foldedInto)

    /** This record, also held by [folded], a fetch's value with this change folded in. */
    fun alsoHeldBy(folded: Fetched<T>?): Committed<K, T, M> =
      Committed(id, key, mutation, sentAt, truth, replaced, foldedInto + folded?.value, hasValue)

    /**
     * This record without its copies of the value, once a newer commit on its key is in the store.
     * A replaced record keeps its value, since that is the change a view puts back.
     */
    fun withoutValue(): Committed<K, T, M> =
      if (replaced || !hasValue) {
        this
      } else {
        Committed(id, key, mutation, sentAt, truth = null, replaced, hasValue = false)
      }

    /** Whether the send is old enough at [now] that no fetch from before it still runs. */
    fun isExpired(now: Instant): Boolean = now - sentAt >= RECORD_LIFETIME
  }

  /** A key's base store and the lock its two writers, fetch and commit, take turns on. */
  private class Base<T>(val store: QueryStore<T>) {
    val lock = Mutex()
  }

  /** A change requested for [key], and whether its row is in the store yet. */
  private class Storing<K>(val key: K, val stored: Operation)
}

/**
 * How long a sent change stays on record when no fetch confirms it, like on a key that is never
 * fetched again. The record exists for a fetch that started before the send and lands after it, and
 * a fetch running this long is far past any network timeout.
 */
private val RECORD_LIFETIME = 10.minutes

/** Queued or Sending: the row's change still shows, and it has not been rejected. */
private val OutboxStatus.isInFlight: Boolean
  get() = this == OutboxStatus.Queued || this == OutboxStatus.Sending

/**
 * How many sent changes a set still has on record. For tests, which cannot see pruning otherwise.
 */
internal val MutableQueryStores<*, *, *>.committedRecords: Int
  get() = (this as RealMutableQueryStores<*, *, *>).committedRecords

/**
 * How many of a set's records keep a copy of the value. For tests, which cannot see it otherwise.
 */
internal val MutableQueryStores<*, *, *>.committedValues: Int
  get() = (this as RealMutableQueryStores<*, *, *>).committedValues
