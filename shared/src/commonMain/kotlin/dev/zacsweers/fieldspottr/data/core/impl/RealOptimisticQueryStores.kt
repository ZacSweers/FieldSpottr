// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.CancellableAdmission
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeepsStored
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStore
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStores
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PendingMutation
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.isDone
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.update
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [OptimisticQueryStores] over one outbox, whose rows live in [rows]. Each key's store is a view:
 * the key's base store, its truth, with the key's changes in flight applied on top.
 *
 * Views read the rows through one shared subscription to the row list, which can lag the store by
 * any time. So a view trusts what this set did or learned itself over the list. A change is known
 * by the id of its row, which [mutate] makes before the insert and its commit record keeps.
 *
 * For each change it touched, the set keeps what it last did or learned in [SetState.known], or as
 * a commit record in [SetState.committed]. A view decides each such change from that alone, and
 * uses the list only for rows the set knows nothing about, like rows from an earlier process, as
 * [OutboxEntry.shows] says. [plan] orders what a view applies.
 *
 * | State     | Settled by                         | View applies | Forgotten when            |
 * |-----------|------------------------------------|--------------|---------------------------|
 * | Pending   | [mutate]                           | the change   | never, it only moves on   |
 * | HandedOff | an attempt that ended Cancelled    | the change   | list shows row, or passed |
 * | Sent      | an attempt that sent, no record    | the change   | passed                    |
 * | Committed | the commit after a send (a record) | the record   | expired                   |
 * | Failed    | an attempt that failed for good    | nothing      | list shows Failed, passed |
 * | Dropped   | [discard], [clear], or a commit    | nothing      | passed                    |
 * |           | whose [apply] threw                |              |                           |
 *
 * "Passed" means the list showed the row and a later list lacks it. Pending moves on when its
 * commit records it, when its attempt ends, or when [discard] or [clear] drops it. Dropped is
 * final. HandedOff means the owner's scope stopped and the row waits for the next owner. Sent means
 * the server has the change and its commit has not landed, like a commit that failed on a store
 * error and runs again later. A change whose insert failed, or whose owner stopped before the
 * insert, has no row, and the set forgets it at once.
 *
 * A running [retry] or [discard] call puts a provisional [Mark] over the settled state, as [Known]
 * describes. A change with a mark is never forgotten. When its last mark goes, its forget rule
 * applies at once, and a change whose row the list already passed is forgotten then, since no later
 * list may come.
 *
 * HandedOff, Failed and Dropped expire [RECORD_LIFETIME] after the change entered them, for a row
 * the list never shows. HandedOff shows a change, so a timer in the set's scope ends it and its
 * bound holds on an idle set. Failed and Dropped show nothing, so the set checks their expiry only
 * on each new list, commit and fetch write. The set checks commit records only on each commit and
 * fetch write, so an idle set keeps them until its next one. The forget rules make sure the list
 * has caught up with the set first, so the list then agrees with what the set knew.
 *
 * A sent change reaches the truth in two writes, the base store's and the row's status, and no
 * store makes them atomic. Meanwhile a fetch that started before the send can land with a value
 * that lacks the change. So a view shows the change from its [Committed] record in place of its
 * row. Two cases fall outside the records, which is why [apply] must be idempotent, as
 * [checkIdempotent] describes. With several lines on one value, a change whose commit write failed
 * can be carried into the truth by another line's commit and then applied again by its own retry.
 *
 * The query's fetch and the outbox's commit both write a key's truth, and they take turns on the
 * key's [Base.lock].
 */
@OptIn(DelicateDataLayerApi::class, ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
internal class RealOptimisticQueryStores<K : Any, T, M>(
  private val rows: OutboxStore<KeyedMutation<K, M>>,
  /** Joins this set to the outbox engine over [rows] as its handle, once, to build [outbox]. */
  attach: (RowOwner<KeyedMutation<K, M>>) -> OwnedRows<KeyedMutation<K, M>>,
  /**
   * The default line of a key's rows, which a [Ordering.Keyed] change extends. Keys that are equal
   * must give the same line, so they share one order however their `toString` reads.
   */
  private val orderKey: (K) -> String,
  /**
   * Whether the sender can answer with a value. Such a set takes [Ordering.Serial] only, since two
   * lines could commit their answers out of order and an older answer would replace a newer one.
   */
  private val replies: Boolean,
  private val base: (key: K) -> QueryStore<T>,
  private val apply: (current: T?, mutation: M) -> T?,
  /** The clock the queries' fetch times come from, so a send time compares against them. */
  private val clock: Clock,
  /**
   * Hears an [apply] that is not idempotent, an [apply] that throws, a failed commit, a failure to
   * read the row list, and a cleanup that failed after a fetch write.
   */
  private val onFailure: (Throwable) -> Unit,
  /**
   * The context each view computes its overlay in: the data scope's dispatcher with no job
   * attached, so [apply] never runs on a collector's thread, such as the main thread.
   */
  private val context: CoroutineContext,
) : OptimisticQueryStores<K, T, M> {
  private val state = MutableStateFlow(SetState<K, T, M>())
  // Whether an apply that is not idempotent has been reported. Once is enough to fix it.
  private val reportedApply = AtomicBoolean(false)
  private val idempotenceChecks = AtomicInt(0)
  // One base store per key, shared by the key's views and its commits, so a store that keeps state
  // of its own, like one that caches what it loaded, sees every write. Kept for as long as this set
  // lives, as optimisticStores promises. Updated by compare-and-set only.
  private val bases = AtomicReference(persistentHashMapOf<K, Base<T>>())
  // Changes whose rows are still being written, by key, so a write or clear can wait for the ones
  // requested before it. Each entry goes once its row write settles, so an idle set keeps none.
  // Updated by compare-and-set only.
  private val storing = AtomicReference(persistentListOf<Storing<K>>())
  // The changes whose commit wrote the truth while their rows may still be owed, each with whether
  // the row list has shown the row since, so a commit that runs again after its row write failed
  // never applies the change twice. An entry has no expiry, since retries can run for any time. It
  // goes once the row is done: when the engine marks it committed, when the list showed the row and
  // then lacks it, or when a commit finds the row done or gone. Updated by compare-and-set only.
  private val landed = AtomicReference(persistentHashMapOf<EntryId, Boolean>())

  // Runs the shared row subscription and the watchers that follow handles. It has no job of the
  // data scope's, so views keep reading the rows after that scope stops.
  private val sharing = CoroutineScope(context + SupervisorJob())

  /**
   * The shared subscription to the row list. It writes each list into [state] and emits nothing.
   * [SetState.watching] is set before the store is read and cleared once it stops, so a store read
   * can tell whether a list read before it may still arrive.
   *
   * A store that fails to list its rows is reported and read again after a pause. Views keep the
   * last list meanwhile, and emit nothing before the first one. Once nothing collects, the list in
   * [state] is dropped, since nothing keeps it current.
   */
  private val rowWatch: SharedFlow<Unit> =
    flow<Unit> {
        edit { it.copy(watching = true) }
        rows.entries().collect { list ->
          val now = clock.now()
          edit { it.withRows(list, now) }
          forgetLanded(list)
        }
      }
      .retryWhen { cause, attempt ->
        onFailure(cause)
        delay(retryPause(attempt))
        true
      }
      .onCompletion { edit { it.withoutRows() } }
      .shareIn(sharing, SharingStarted.WhileSubscribed())

  /** Keeps [rowWatch] running for as long as it is collected. It emits once, on subscription. */
  private val watching: Flow<Unit> = rowWatch.onSubscription { emit(Unit) }

  /** [state] once it holds the rows, kept current for as long as this is collected. */
  private val snapshots: Flow<SetState<K, T, M>> =
    combineTransform(watching, state) { _, current ->
      if (current.rows != null) {
        emit(current)
      }
    }

  // Last, since the engine can call commit as soon as this set attaches, like on an eager
  // dispatcher, and the commit reads everything above
  private val outbox: OwnedRows<KeyedMutation<K, M>> = attach(Owner())

  /**
   * This set as the handle of its outbox. It makes no change on insert and undoes none on revert,
   * since views read both from the rows. It commits each sent change into the truth.
   *
   * A live commit runs right after the send, before the row is marked Sent. A row sent while no
   * handle was attached, like in a background drain, is committed from its stored answer once this
   * set attaches.
   */
  private inner class Owner : RowOwner<KeyedMutation<K, M>> {
    override suspend fun apply(payload: KeyedMutation<K, M>, id: EntryId) {}

    override suspend fun revert(payload: KeyedMutation<K, M>, id: EntryId) {}

    /**
     * [commit] for a sent row. A store error throws, and the engine retries as [RowOwner.commit]
     * describes. Meanwhile the change shows, from its record or from the sent row.
     */
    override suspend fun commit(
      entry: OutboxEntry<KeyedMutation<K, M>>,
      delivery: Delivery<*>?,
      sentAt: Instant,
      expected: OutboxStatus,
    ) {
      @Suppress("UNCHECKED_CAST")
      commit(entry.id, entry.payload, delivery as Delivery<T>?, sentAt, expected)
    }

    override fun committed(id: EntryId) {
      landed.update { it.removing(id) }
    }
  }

  /** Forgets each landed commit whose row [list] no longer shows after it showed it. */
  private fun forgetLanded(list: List<OutboxEntry<KeyedMutation<K, M>>>) {
    if (landed.load().isEmpty()) {
      return
    }
    val ids = list.mapTo(HashSet()) { it.id }
    landed.update { current ->
      var next = current
      for ((change, shown) in current) {
        if (change in ids) {
          if (!shown) {
            next = next.putting(change, true)
          }
        } else if (shown) {
          next = next.removing(change)
        }
      }
      next
    }
  }

  /** How many landed commits the set remembers. For tests. */
  internal val landedCommits: Int
    get() = landed.load().size

  /** How many sent changes are still on record. For tests. */
  internal val committedRecords: Int
    get() = state.value.committed.size

  /** How many records still keep a copy of the value. For tests. */
  internal val committedValues: Int
    get() = state.value.committed.count { it.hasValue }

  /** How many changes the set remembers apart from records. For tests. */
  internal val knownChanges: Int
    get() = state.value.known.size

  /** How many reported apply failures the set remembers. For tests. */
  internal val reportedApplies: Int
    get() = state.value.reported.size

  override fun invoke(key: K): OptimisticQueryStore<T, M> = KeyStore(key, baseFor(key))

  /**
   * [key]'s base store, built on first use. The entry goes into [bases] before its store opens, so
   * only the entry that wins a race calls [base], and a caller that loses waits for that one open.
   */
  private fun baseFor(key: K): Base<T> {
    while (true) {
      val current = bases.load()
      current[key]?.let {
        return it
      }
      val created = Base { base(key) }
      if (bases.compareAndSet(current, current.putting(key, created))) {
        return created
      }
    }
  }

  /**
   * Gives the change its row id, records it as Pending, then enqueues its row and follows it. The
   * row's write is registered in [storing] before the change is recorded, so a clear that finds the
   * change also waits for its row, even while this call has not enqueued it yet. A refusal of
   * [ordering] throws before the change is recorded, or from the enqueue, which forgets it. So does
   * a refused [admission]. A rejected change fails its stored milestone, so it is forgotten like a
   * change whose row could not be stored.
   */
  override fun mutate(
    key: K,
    mutation: M,
    ordering: Ordering,
    admission: CancellableAdmission,
  ): OutboxOperation {
    require(!replies || ordering == Ordering.Serial) {
      "This store's sender can answer with a value, so its changes take Ordering.Serial only. " +
        "Two lines could commit their answers out of order. Use Ordering.Serial."
    }
    val id = EntryId(Uuid.random().toString())
    val payload = KeyedMutation(key, mutation)
    val write = Storing(key)
    storing.update { it.adding(write) }
    edit { it.knowing(id, Known(Kind.Pending, payload)) }
    val handle =
      try {
        outbox.enqueue(id, payload, ordering, line = orderKey(key), admission)
      } catch (e: Throwable) {
        // No row will exist, so nothing waits for it and nothing shows the change
        write.started.complete(Operation.failed(e))
        storing.update { it.remove(write) }
        edit { it.forgetting(id) }
        throw e
      }
    write.started.complete(handle.stored)
    sharing.launch {
      val stored = handle.stored.await()
      storing.update { it.remove(write) }
      if (stored != OperationState.Succeeded) {
        // No row exists, so nothing can show it later
        edit { it.forgetting(id) }
      } else {
        // Only while the change is still this call's Pending. Anything else this set did wins.
        settle(id, handle.await().kindAfter(), mark = null) { it.settled == Kind.Pending }
      }
    }
    return handle
  }

  /**
   * The state a row is in after its attempt ended this way. A commit that landed replaced what the
   * set knew with a record, so a success the set still knows waits for its commit.
   */
  private fun OperationState.Terminal.kindAfter(): Kind =
    when (this) {
      OperationState.Succeeded -> Kind.Sent
      is OperationState.Failed -> Kind.Failed
      OperationState.Cancelled -> Kind.HandedOff
    }

  /**
   * Settles [change] in [kind] in one update, if [applies] to what the set knows of it, and removes
   * [mark], the provisional mark of the call that learned this. A HandedOff change gets a timer
   * that forgets it when it expires.
   */
  private fun settle(
    change: EntryId,
    kind: Kind,
    mark: Mark?,
    applies: (Known<K, M>) -> Boolean = { true },
  ) {
    val now = clock.now()
    val timer = if (kind == Kind.HandedOff) expiryTimer(change) else null
    val next = edit { s ->
      val known = s.known[change]
      when {
        known == null -> s
        !applies(known) -> s.unmarking(change, mark)
        else -> s.settling(change, kind, now, timer, mark)
      }
    }
    if (timer != null) {
      if (next.known[change]?.expiry === timer) {
        timer.start()
      } else {
        timer.cancel()
      }
    }
  }

  /**
   * A timer, not yet started, that forgets [change] [RECORD_LIFETIME] from now if it is still the
   * change's timer then. It runs on [context], so a test's virtual time drives it.
   */
  private fun expiryTimer(change: EntryId): Job =
    sharing.launch(start = CoroutineStart.LAZY) {
      delay(RECORD_LIFETIME)
      val self = coroutineContext[Job]
      edit { s ->
        if (self != null && s.known[change]?.expiry === self) {
          s.expiring(change)
        } else {
          s
        }
      }
    }

  /**
   * [state].update that also cancels the expiry timer of each change the update forgot or moved out
   * of HandedOff. The transform may run more than once, so it must have no side effects. An update
   * that keeps the known map instance, like most new lists, keeps every timer, so it skips the
   * scan.
   */
  private inline fun edit(transform: (SetState<K, T, M>) -> SetState<K, T, M>): SetState<K, T, M> {
    while (true) {
      val old = state.value
      val new = transform(old)
      if (state.compareAndSet(old, new)) {
        if (old.known === new.known) {
          return new
        }
        for ((change, known) in old.known) {
          val timer = known.expiry ?: continue
          if (new.known[change]?.expiry !== timer) {
            timer.cancel()
          }
        }
        return new
      }
    }
  }

  /** Waits until every change for [key] requested so far is in the store, or failed to be. */
  private suspend fun awaitStored(key: K) {
    for (entry in storing.load()) {
      if (entry.key == key) {
        entry.started.await().await()
      }
    }
  }

  override fun pending(key: K): Flow<List<PendingMutation<M>>> =
    snapshots
      .map { state ->
        state
          .slice(key)
          ?.rows
          .orEmpty()
          .filter { state.listsPending(it) }
          .map { row -> row.toPending() }
      }
      .distinctUntilChanged()

  override val pendingCount: Flow<Int> =
    snapshots
      .map { state -> state.rows.orEmpty().count { state.listsPending(it) } }
      .distinctUntilChanged()

  /**
   * Whether [pending] and [pendingCount] list [row]: the server has not confirmed its change, and
   * the set does not know the change as Dropped. So a discard hides the row as it hides the change
   * in views.
   */
  private fun SetState<K, T, M>.listsPending(row: OutboxEntry<KeyedMutation<K, M>>): Boolean =
    row.status != OutboxStatus.Sent && known[row.id]?.kind != Kind.Dropped

  /**
   * Puts a provisional Pending mark of this call on row [id]'s change, so views show it from now,
   * then requeues the row. When the new attempt ends, the call's mark goes and the change settles
   * by the outcome, in one update. If the requeue itself fails, like on a store error, only the
   * mark goes, and the view falls back to the change's settled state, whatever happened meanwhile.
   */
  override fun retry(id: EntryId): Operation {
    val mark = Mark(Kind.Pending)
    val change = mark(id, mark)
    val operation = outbox.retry(id)
    if (change != null) {
      sharing.launch {
        val outcome = operation.await()
        // A send that failed for good fails with a PermanentFailure. Any other failure means the
        // requeue itself failed, so the row is as it was.
        if (outcome is OperationState.Failed && outcome.cause !is PermanentFailure) {
          edit { it.unmarking(change, mark) }
        } else {
          settle(change, outcome.kindAfter(), mark)
        }
      }
    }
    return operation
  }

  /**
   * Puts a provisional Dropped mark of this call on row [id]'s change, so no view shows it from now
   * however late the list is, then discards the row. Once the discard succeeds, the change settles
   * Dropped. If it fails, as when the row already sends, only the call's mark goes.
   */
  override fun discard(id: EntryId): Operation {
    val mark = Mark(Kind.Dropped)
    val change = mark(id, mark)
    val operation = outbox.discard(id)
    if (change != null) {
      sharing.launch {
        if (operation.await() == OperationState.Succeeded) {
          settle(change, Kind.Dropped, mark)
        } else {
          edit { it.unmarking(change, mark) }
        }
      }
    }
    return operation
  }

  /**
   * Puts [mark] on row [id]'s change and returns the change, or null when the set cannot tell or
   * leaves the change unmarked. The set finds the change by the row id that the list or its own
   * knowledge holds.
   *
   * A change with a commit record gets no mark, since the record decides its view. A lagging list
   * can still show the row of such a change, and a mark would start an entry that nothing forgets.
   * A change whose row the list shows Sent gets none either, since the server has it. A retry would
   * settle it Sent, which shows it where the list would not, like over a truth fetched after its
   * send, and a discard would hide it until the discard fails. A retry adds no mark when the change
   * is Dropped, since Dropped is final, or when the newest mark is a running discard's, so the
   * retry cannot show the change while the discard settles.
   */
  private fun mark(id: EntryId, mark: Mark): EntryId? {
    var change: EntryId? = null
    edit { s ->
      change = id.takeUnless {
        (it !in s.known && it !in s.listed) ||
          s.listed[it]?.status == OutboxStatus.Sent ||
          s.committed.any { record -> record.change == it } ||
          (mark.kind == Kind.Pending && s.known[it]?.kind == Kind.Dropped)
      }
      change?.let { s.marking(it, mark) } ?: s
    }
    return change
  }

  /**
   * Moves the change in [payload], row [id], into the truth after a successful send that finished
   * at [sentAt]. [result] is the server's answer, or null when a stored answer could not be
   * decoded. It does nothing unless the row's status is still [expected], and for a Sent row,
   * unless it is not committed yet. So a commit repeated after a crash, or one that a clear
   * overtook, does nothing more.
   *
   * A server answer replaces the truth as fresh data. Otherwise the change is applied to the truth
   * with the earlier sent changes a fetch may have dropped put back, and the truth keeps its fetch
   * time. A truth fetched after the send already holds the change, so it stays as it is, and the
   * record keeps no value, since that truth may lack another line's change whose write is owed.
   *
   * Two failures end the commit without the change written, since a retry cannot fix them, and both
   * mark the truth stale, so the next fetch brings the server's value with the change in it. An
   * [apply] that throws is reported, and the change settles Dropped. An unknown answer leaves a
   * record without a value, which puts the change back on every truth fetched before the send until
   * a fetch from after the send replaces it, or until the record expires and the stale mark alone
   * corrects the value. A fetch from before the send that lands later keeps the stale mark, as
   * [KeyStore.land] describes. A stale mark that fails throws, so the commit runs again.
   *
   * The record replaces what the set knew of the change in one update, found by the row id, so this
   * holds even when the row sent before its insert returned.
   *
   * This runs on the key's lock, so no fetch through a view lands between the read and the write.
   * Another writer that shares the base store can, so the write keeps a value fetched after the
   * send, by the same rule.
   */
  private suspend fun commit(
    id: EntryId,
    payload: KeyedMutation<K, M>,
    result: Delivery<T>?,
    sentAt: Instant,
    expected: OutboxStatus,
  ) {
    val key = payload.key
    val change = id
    val base = baseFor(key)
    base.lock.withLock {
      val row = rows.entry(id).first()
      if (
        row == null || row.status != expected || (expected == OutboxStatus.Sent && row.committed)
      ) {
        landed.update { it.removing(change) }
        return
      }
      // A landed commit owes only its row write, which the engine repeats
      if (change in landed.load()) {
        return
      }
      val current = base.store.data.first()
      // Without this change's own record, which a commit that failed after it may have left
      val sent = state.value.slice(key)?.committed.orEmpty().filter { it.change != change }
      // A truth fetched after the send read the server with the change in it. The write below
      // still replaces one that was invalidated since, as every conditional write does.
      val fetchedSinceSend: KeepsStored = { storedAt, invalidated ->
        !invalidated && storedAt != null && storedAt.isAfterSend(sentAt)
      }
      val alreadyFetched = current?.fetchedAt?.isAfterSend(sentAt) == true
      if (result == null) {
        // Mark it stale first, so a mark that fails throws before the record
        base.store.invalidate()
        val record =
          Committed<K, T, M>(
            change = change,
            key = key,
            mutation = payload.mutation,
            sentAt = sentAt,
            truth = null,
            replaced = false,
            hasValue = false,
            answerUnknown = true,
          )
        prune(key, current?.fetchedAt) { s -> s.withRecord(record) }
        return
      }
      val replaced = result is Delivery.Replaced
      // An invalidate since a commit for this answer failed marked the answer on record stale
      val stale =
        state.value.slice(key)?.committed.orEmpty().any {
          it.change == change && it.truth?.invalidated == true
        }
      val next =
        when {
          result is Delivery.Replaced -> Fetched(result.value, sentAt, invalidated = stale)
          // The record keeps no value. The stored truth may lack a change of another line whose
          // commit write is still owed, and a record with a value tells views that the truth holds
          // every record before it. Without one, views judge the other records as they did before
          // this commit.
          alreadyFetched -> null
          else -> {
            val folded =
              try {
                applied(withSent(current, sent), payload.mutation)
              } catch (e: Exception) {
                // Mark it stale first, so a mark that fails throws and the commit runs again
                base.store.invalidate()
                val now = clock.now()
                edit { it.dropping(change, payload, now) }
                reportApply(change, e) {
                  "apply threw for change ${change.value} while the store committed it, so " +
                    "the truth keeps its value and is marked stale"
                }
                return
              }
            checkIdempotent(folded, payload.mutation, change)
            folded
          }
        }
      val record =
        Committed(
          change = change,
          key = key,
          mutation = payload.mutation,
          sentAt = sentAt,
          truth = next,
          replaced = replaced,
          hasValue = replaced || !alreadyFetched,
        )
      // Views show this in place of the row and the change's known state from here, so the writes
      // below never show as a gap
      prune(key, current?.fetchedAt) { s -> s.withRecord(record) }
      if (!alreadyFetched) {
        if (next == null) {
          base.store.clearFetched(fetchedSinceSend)
        } else {
          base.store.writeFetched(next, fetchedSinceSend)
        }
      }
      landed.update { it.putting(change, false) }
      // This record's value tells views that the store holds every change before it too, so the
      // key's older records need only what a slow fetch folds back in. A record without a value
      // comes from a commit that wrote nothing, so it changes no other record, and an older record
      // whose write landed stays held by its own value.
      if (record.hasValue) {
        edit { s ->
          s.updatingRecords(key) {
            if (it.change != change) {
              it.withoutValue(newerAnswer = replaced)
            } else {
              it
            }
          }
        }
      }
    }
  }

  /**
   * Reports [mutation], row [change]'s, once per set if applying it again to [folded], which
   * already holds it, changes the value. The report names the row id, since a payload can hold
   * private data. Only the first [IDEMPOTENCE_CHECKS] commits of the set that fold a change run it.
   * An [apply] that throws here is reported as a failed check, since the truth holds the change.
   *
   * Two cases fall outside the records and pass [apply] such a value, and the store cannot tell: a
   * fetch that overlapped the send but read the server after it took the change, and a restart
   * between the truth write and the row's status. With several lines on one value, a change whose
   * commit write failed can be carried into the truth by another line's commit and then applied
   * again by its own retry. So a change that fails this would show twice. A change the server
   * answers with a value is never folded, so nothing checks it, and a counter shows twice until a
   * server answer replaces the truth.
   */
  private fun checkIdempotent(folded: Fetched<T>?, mutation: M, change: EntryId) {
    if (reportedApply.load() || idempotenceChecks.fetchAndIncrement() >= IDEMPOTENCE_CHECKS) {
      return
    }
    val again =
      try {
        apply(folded?.value, mutation)
      } catch (e: Exception) {
        if (reportedApply.compareAndSet(expectedValue = false, newValue = true)) {
          onFailure(
            IllegalStateException(
              "apply threw when the store applied change ${change.value} again to check that " +
                "apply is idempotent. The change is in the truth. Make apply return a value that already " +
                "holds the change unchanged.",
              e,
            )
          )
        }
        return
      }
    if (again == folded?.value) {
      return
    }
    if (reportedApply.compareAndSet(expectedValue = false, newValue = true)) {
      onFailure(
        IllegalStateException(
          "apply is not idempotent for change ${change.value}: applied to a value that " +
            "already holds the change, it changed the value again. A store built with send " +
            "needs a change that is safe to apply again, so make apply return such a value " +
            "unchanged, like an append that skips an item already there. Or build the store " +
            "with exchange, where the value is an estimate until the server's answer corrects it."
        )
      )
    }
  }

  /**
   * Prunes after a truth for [key] fetched at [fetchedAt], as [SetState.confirming] describes.
   * [also] runs in the same update, like adding a commit's record.
   *
   * While no shared subscription runs, a confirmed record that the list has not passed gets a store
   * read of its row. The update uses that read only if still no subscription runs, so no list read
   * before the store read can arrive later.
   */
  private suspend fun prune(
    key: K,
    fetchedAt: Instant?,
    also: (SetState<K, T, M>) -> SetState<K, T, M> = { it },
  ) {
    val now = clock.now()
    val before = state.value
    val unproven =
      if (before.watching) {
        emptyList()
      } else {
        before.committed.filter {
          !it.passed && (it.confirmed || it.confirmedBy(key, fetchedAt)) && !it.isExpired(now)
        }
      }
    val settled = HashSet<EntryId>()
    for (record in unproven) {
      val row = rows.entry(record.change).first()
      if (row == null || row.isDone) {
        settled += record.change
      }
    }
    edit { s ->
      also(s.confirming(key, fetchedAt, now, settledRows = settled.takeUnless { s.watching }))
    }
  }

  /**
   * Discards [key]'s unconfirmed rows, sending ones included, by writing their status. It adds each
   * row to [discarded] once its write landed or it found the row done or gone, so a caller still
   * knows which rows went when this throws part way. That works whether or not the outbox still
   * runs, like after a logout cancelled its scope. Handles settle Cancelled through the row, and
   * the outbox moves the key on.
   *
   * A row left sending by a stopped owner would send again under the next owner, so it goes too. A
   * drain still sending it finds the row discarded, and its [commit] does nothing. The drainer can
   * move a row meanwhile, so each compare-and-set retries until it wins or the row is settled. A
   * row cannot become Sent and committed meanwhile, since that waits for [commit], which needs the
   * caller's lock. A Sent row that waits for its commit is marked committed without one, so the old
   * change never reaches the cleared truth. The same write drops the row's stored answer, since no
   * commit reads it now.
   */
  private suspend fun discardPending(
    key: K,
    discarded: MutableMap<EntryId, OutboxEntry<KeyedMutation<K, M>>>,
  ) {
    // From the store itself: a row stored a moment ago may not have reached the shared rows yet,
    // and a row missed here would still send after the clear
    for (row in rows.entries().first()) {
      if (row.payload.key != key) {
        continue
      }
      var current: OutboxEntry<KeyedMutation<K, M>>? = row
      while (current != null && !current.isDone) {
        val dropped =
          if (current.status == OutboxStatus.Sent) {
            current.copy(committed = true, reply = null)
          } else {
            current.copy(status = OutboxStatus.Discarded, reverted = true)
          }
        if (rows.update(dropped, expected = current.status)) {
          break
        }
        current = rows.entry(row.id).first()
      }
      discarded[row.id] = row
    }
  }

  /**
   * [stored] with every sent change it may lack put back, oldest first, as [sentPlan] plans it. A
   * change whose [apply] throws is left out.
   */
  private fun withSent(stored: Fetched<T>?, sent: List<Committed<K, T, M>>): Fetched<T>? =
    run(sentPlan(stored, sent, ArrayList()))

  /**
   * The plan that puts back on [stored] every sent change it may lack, oldest first. It adds those
   * changes to [changes] and returns the plan over them.
   *
   * [stored] holds a sent change when that change's commit or a later one wrote it, since each
   * commit carries the earlier changes, or when it was fetched after the send. Any other sent
   * change is missing: [stored] came from a fetch that read the server too early, or from a commit
   * whose write has not landed. A missing server answer holds everything sent before it, so the
   * newest one replaces [stored], and only the changes after it are applied again.
   */
  private fun sentPlan(
    stored: Fetched<T>?,
    sent: List<Committed<K, T, M>>,
    changes: MutableList<Planned<M>>,
  ): Plan<T, M> {
    // The newest record whose change the store holds. Every record up to it is in the truth.
    val held = sent.indexOfLast { it.isHeldBy(stored) }
    val missing = sent.filterIndexed { index, record ->
      index > held && stored?.fetchedAt.let { it == null || it <= record.sentAt }
    }
    val answer = missing.indexOfLast { it.replaced && it.hasValue }
    val start =
      if (answer >= 0) {
        missing[answer].truth
      } else {
        stored
      }
    // An answer after this one gave up its value to a newer answer that has since gone, which
    // leaves its change to apply again like a send's
    for (record in missing.drop(answer + 1)) {
      changes += Planned(record.change, record.mutation)
    }
    return Plan(start, changes)
  }

  /**
   * The plan of a key's view of [stored] with its [slice]: records first, then the list's rows in
   * queue order, then the changes the set knows that the list does not show yet, in the order the
   * set learned of them. Each change applies at most once, and whether it applies follows the table
   * in the class KDoc.
   *
   * A listed row applies in its queue position whatever the set knows of it, so a retry or a
   * discard that teaches the set a change never moves it past a newer row. A known change missing
   * from the list goes last. Its insert has not returned or the list lags, and a line's inserts run
   * in order, so its row comes after every row of its line the list shows. A row of another line
   * can apply in either order, which is why changes on different lines must commute.
   */
  private fun plan(stored: Fetched<T>?, slice: KeySlice<K, T, M>?): Plan<T, M> {
    if (slice == null) {
      return Plan(stored, emptyList())
    }
    val changes = ArrayList<Planned<M>>()
    val plan = sentPlan(stored, slice.committed, changes)
    val recorded = slice.committed.mapTo(HashSet()) { it.change }
    val listed = HashSet<EntryId>(slice.rows.size)
    for (row in slice.rows) {
      val change = row.id
      listed += change
      if (change in recorded) {
        continue
      }
      val shows = slice.known[change]?.kind?.shows ?: row.shows(stored)
      if (shows) {
        changes += Planned(change, row.payload.mutation)
      }
    }
    for ((change, known) in slice.known) {
      if (known.kind?.shows != true || change in recorded || change in listed) {
        continue
      }
      changes += Planned(change, known.payload.mutation)
    }
    return plan
  }

  /** The value [plan] gives: its start with each of its changes applied in order. */
  private fun run(plan: Plan<T, M>): Fetched<T>? {
    var result = plan.start
    for (planned in plan.changes) {
      result = appliedOrSkipped(result, planned.mutation, planned.change)
    }
    return result
  }

  /**
   * [stored] with [mutation] applied, keeping its fetch time and invalidated flag, or never fetched
   * over nothing stored. Null when the change removes the value. Throws what [apply] throws.
   */
  private fun applied(stored: Fetched<T>?, mutation: M): Fetched<T>? {
    val value = apply(stored?.value, mutation) ?: return null
    return stored?.copy(value = value) ?: Fetched(value, fetchedAt = null)
  }

  /** [applied], or [stored] as it is when [apply] throws, which [reportApply] reports. */
  private fun appliedOrSkipped(stored: Fetched<T>?, mutation: M, change: EntryId): Fetched<T>? =
    try {
      applied(stored, mutation)
    } catch (e: Exception) {
      reportApply(change, e) {
        "apply threw for change ${change.value}, so the store leaves the change out"
      }
      stored
    }

  /**
   * Reports that [apply] threw [e] for [change], once for as long as the set remembers it: until
   * the set forgets the change and the list no longer shows its row. [SetState.reported] keeps that
   * entry, so a new collector does not report it again. The decision is made on the current state,
   * so a view that runs late never reports a change the set already forgot.
   */
  private fun reportApply(change: EntryId, e: Exception, message: () -> String) {
    var report = false
    edit { s ->
      report = change !in s.reported && s.remembers(change)
      if (report) {
        s.copy(reported = s.reported.putting(change, change in s.listed))
      } else {
        s
      }
    }
    if (report) {
      onFailure(IllegalStateException(message(), e))
    }
  }

  private fun OutboxEntry<KeyedMutation<K, M>>.toPending() =
    PendingMutation(id, payload.mutation, status, attempts, lastError, retryAt)

  /** [key]'s view: the truth with the key's sent, stored and local changes on top. */
  private inner class KeyStore(private val key: K, private val base: Base<T>) :
    OptimisticQueryStore<T, M> {
    private val truth = base.store

    override val data: Flow<Fetched<T>?> = flow {
      // What this collection's last overlay was given and planned. The overlay reads only the
      // truth and the key's slice, so the same truth and slice instance give the last value again,
      // or a value that the next truth replaces, and are skipped by an identity check. A slice
      // change that leaves the plan as it was, like a row that moves from Queued to Sending, runs
      // no apply. A state without rows runs no overlay and keeps these, so the next list compares
      // against the inputs of the last value sent.
      var lastEmitted: Fetched<T>? = null
      var lastSlice: KeySlice<K, T, M>? = null
      var lastPlan: Plan<T, M>? = null
      // The combine collects the truth and the state in two coroutines, so the state it passes can
      // be older or newer than the truth it passes. So the state only wakes the view, and the
      // overlay reads the newest state, which holds every record and fold mark of the truths this
      // collection has seen, since each lands in the state before its truth write.
      val views =
        combineTransform(truth.data, state, watching) { emitted, _, _ ->
          val current = state.value
          if (current.rows == null) {
            return@combineTransform
          }
          val slice = current.slice(key)
          if (lastPlan != null && emitted === lastEmitted && slice === lastSlice) {
            return@combineTransform
          }
          val plan = plan(emitted, slice)
          lastEmitted = emitted
          lastSlice = slice
          if (plan == lastPlan) {
            return@combineTransform
          }
          lastPlan = plan
          emit(run(plan))
        }
      emitAll(views)
    }
      .distinctUntilChanged()
      .flowOn(context)

    /** The query's fetch write, as [land] describes. */
    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
      land(fetched, keepsStored)
    }

    /** [writeFetched] for a value that is gone. */
    override suspend fun clearFetched(keepsStored: KeepsStored) {
      land(null, keepsStored)
    }

    /**
     * Stores [fetched], or removes the truth when it is null, unless [keepsStored] says the truth
     * should stay, like for a value older than a commit. It first waits until the changes requested
     * before it are stored, so the value it publishes carries them. The truth's own conditional
     * write or removal checks again, for a writer that shares the base store without this lock.
     *
     * The value may lack a send that finished after the fetch started, which only its in-memory
     * record shows. So the truth takes those changes too, as [withSent] puts them back, and a
     * restart still shows them. A [fetched] marked invalidated lands marked, whatever the sent
     * changes do to its value. So does one that lands while a change sent since its fetch has an
     * unknown answer, since its value may be an estimate.
     *
     * After a [fetched] value, it prunes on the key's lock, so the confirmation of an earlier fetch
     * never applies after a later write.
     */
    private suspend fun land(fetched: Fetched<T>?, keepsStored: KeepsStored) {
      awaitStored(key)
      base.lock.withLock {
        val stored = truth.data.first()
        if (stored == null || !keepsStored(stored.fetchedAt, stored.invalidated)) {
          // A value that is gone or never fetched counts as older than every send
          val sentSince =
            state.value.slice(key)?.committed.orEmpty().filter { record ->
              fetched?.fetchedAt.let { it == null || record.sentAt >= it }
            }
          val folded = withSent(fetched, sentSince)
          // A server answer on record replaces the fetched value with its own, which is not
          // stale. A write marked stale stays stale all the same. So does a write while a change
          // sent since its fetch has an unknown answer, so the truth never holds an estimate as
          // fresh.
          val stale = fetched?.invalidated == true || sentSince.any { it.answerUnknown }
          val next =
            if (stale && folded != null && !folded.invalidated) {
              folded.copy(invalidated = true)
            } else {
              folded
            }
          // Before the write, so views count every change on record as in the folded truth once it
          // lands, and never apply one twice
          val newest = sentSince.lastOrNull()
          if (newest != null) {
            edit { s ->
              s.updatingRecords(key) {
                if (it.change == newest.change) {
                  it.alsoHeldBy(next)
                } else {
                  it
                }
              }
            }
          }
          if (next == null) {
            truth.clearFetched(keepsStored)
          } else {
            truth.writeFetched(next, keepsStored)
          }
        }
        pruneAfter(fetched)
      }
    }

    /**
     * The prune after a write of [fetched], and nothing after a clear. The prune only cleans up, so
     * a prune that fails, like on a row read error, is reported to [onFailure] and the write still
     * succeeds. What it would have dropped waits for a later prune or its expiry.
     */
    private suspend fun pruneAfter(fetched: Fetched<T>?) {
      if (fetched != null) {
        runCatchingCancellable { prune(key, fetched.fetchedAt) }.onFailure(onFailure)
      }
    }

    /**
     * Discards the key's rows, then in one update drops its records and settles Dropped each change
     * whose row it discarded and each change whose record it dropped, so no view shows one after
     * this returns however late the list is. A change stored after the rows were read keeps its row
     * and its state.
     *
     * Each change the set knew of when this began goes too, also when the store no longer holds its
     * row, like after a logout deleted every row.
     *
     * When a row write fails or the caller is cancelled part way, each change whose row it already
     * discarded settles Dropped before this throws. The key's records, the other changes and the
     * truth stay as they are, and a second clear finishes the job.
     */
    override suspend fun clear() {
      // Before the wait, so the row write of each of these changes is in storing already, and a row
      // missing from the read below is gone
      val before = state.value.slice(key)?.known?.keys.orEmpty().toList()
      // A change still being stored would land after the rows below were read and then send
      awaitStored(key)
      base.lock.withLock {
        val discarded = HashMap<EntryId, OutboxEntry<KeyedMutation<K, M>>>()
        try {
          discardPending(key, discarded)
        } catch (e: Throwable) {
          // The rows discarded so far stay discarded, so their changes leave the views now,
          // however late the list is
          val now = clock.now()
          edit { s ->
            discarded.entries.fold(s) { next, (change, row) ->
              next.dropping(change, row.payload, now)
            }
          }
          throw e
        }
        val now = clock.now()
        edit { s ->
          var next = s.updatingRecords(key) { null }
          for ((change, row) in discarded) {
            next = next.dropping(change, row.payload, now)
          }
          for (change in before) {
            val entry = s.known[change]
            if (entry != null && change !in discarded) {
              next = next.dropping(change, entry.payload, now)
            }
          }
          for (record in s.slice(key)?.committed.orEmpty()) {
            next = next.dropping(record.change, KeyedMutation(record.key, record.mutation), now)
          }
          next
        }
        truth.clear()
      }
    }

    /**
     * Marks the truth stale on the key's lock, so a commit that read the truth before this cannot
     * write it back fresh. A server answer on record shows in place of a truth fetched before its
     * send, like while its commit's write is still owed, so it takes the mark too.
     */
    override suspend fun invalidate() {
      base.lock.withLock {
        truth.invalidate()
        edit { s -> s.updatingRecords(key) { it.invalidated() } }
      }
    }

    override fun mutate(
      mutation: M,
      ordering: Ordering,
      admission: CancellableAdmission,
    ): OutboxOperation = this@RealOptimisticQueryStores.mutate(key, mutation, ordering, admission)

    override val pending: Flow<List<PendingMutation<M>>> =
      this@RealOptimisticQueryStores.pending(key)

    override fun retry(id: EntryId): Operation =
      otherKey(id) ?: this@RealOptimisticQueryStores.retry(id)

    override fun discard(id: EntryId): Operation =
      otherKey(id) ?: this@RealOptimisticQueryStores.discard(id)

    /**
     * A failed handle when the set knows row [id] belongs to another key, else null. A row the set
     * does not know passes, like before its first row list.
     */
    private fun otherKey(id: EntryId): Operation? {
      val s = state.value
      val owner =
        s.known[id]?.payload?.key
          ?: s.listed[id]?.payload?.key
          ?: s.committed.firstOrNull { it.change == id }?.key
      return if (owner == null || owner == key) {
        null
      } else {
        Operation.failed(IllegalArgumentException("Row ${id.value} belongs to another key"))
      }
    }
  }

  /** What the set last did or learned of a change, as the table in the class KDoc lists. */
  private enum class Kind(val shows: Boolean) {
    Pending(shows = true),
    HandedOff(shows = true),
    Sent(shows = true),
    Failed(shows = false),
    Dropped(shows = false),
  }

  /**
   * What the set knows of one change. [settled] is the state it last learned, or null when only
   * provisional marks hold the entry and the list decides once they go. [marks] are the provisional
   * marks of [retry] and [discard] calls still running, newest last. Each call removes only its own
   * mark. [payload]'s key names the slice that holds the entry, [shown] says the list has shown its
   * row, and [since], when it left Pending, starts its expiry. It keeps no handle, since the outbox
   * and the watcher in [mutate] follow the row. A slice compares entries by identity, so the set
   * replaces an entry only when what it knows of the change changes.
   */
  private class Known<K, M>(
    val settled: Kind?,
    val payload: KeyedMutation<K, M>,
    val shown: Boolean = false,
    val since: Instant? = null,
    val marks: PersistentList<Mark> = persistentListOf(),
    val expiry: Job? = null,
  ) {
    /** The state that decides the view: the newest mark, else the settled state. */
    val kind: Kind?
      get() = marks.lastOrNull()?.kind ?: settled

    fun copy(
      settled: Kind? = this.settled,
      payload: KeyedMutation<K, M> = this.payload,
      shown: Boolean = this.shown,
      since: Instant? = this.since,
      marks: PersistentList<Mark> = this.marks,
      expiry: Job? = this.expiry,
    ): Known<K, M> = Known(settled, payload, shown, since, marks, expiry)

    fun isExpired(now: Instant): Boolean = since != null && now - since >= RECORD_LIFETIME

    /**
     * Whether no mark holds this change and the list agrees with its settled state, by the table's
     * forget rules without expiry. [row] is the change's row in the list, and [passed] says the
     * list showed the row and lacks it now.
     */
    fun listAgrees(row: OutboxEntry<KeyedMutation<K, M>>?, passed: Boolean): Boolean =
      marks.isEmpty() &&
        when (settled) {
          null -> true
          Kind.Pending -> false
          Kind.HandedOff -> row != null || passed
          Kind.Sent -> passed
          Kind.Failed -> row?.status is OutboxStatus.Failed || passed
          Kind.Dropped -> passed
        }
  }

  /** A provisional mark of one [retry] or [discard] call. It compares by identity. */
  private class Mark(val kind: Kind)

  /**
   * What one key's view reads of a [SetState]: the key's [rows] in the list, the [known] changes of
   * the key in the order the set learned of them, and the key's records in commit order.
   */
  private class KeySlice<K, T, M>(
    val rows: List<OutboxEntry<KeyedMutation<K, M>>> = emptyList(),
    val known: PersistentMap<EntryId, Known<K, M>> = persistentMapOf(),
    val committed: PersistentList<Committed<K, T, M>> = persistentListOf(),
  ) {
    val isEmpty: Boolean
      get() = rows.isEmpty() && known.isEmpty() && committed.isEmpty()

    fun copy(
      rows: List<OutboxEntry<KeyedMutation<K, M>>> = this.rows,
      known: PersistentMap<EntryId, Known<K, M>> = this.known,
      committed: PersistentList<Committed<K, T, M>> = this.committed,
    ): KeySlice<K, T, M> = KeySlice(rows, known, committed)

    /**
     * Whether [other] gives every overlay the same value: equal rows, and the same entry and record
     * instances in the same order. Entries and records never change in place.
     */
    fun sameAs(other: KeySlice<K, T, M>): Boolean {
      if (rows != other.rows || known.size != other.known.size || committed != other.committed) {
        return false
      }
      val others = other.known.entries.iterator()
      for ((change, entry) in known) {
        val next = others.next()
        if (change != next.key || entry !== next.value) {
          return false
        }
      }
      return true
    }
  }

  /**
   * Everything a view reads besides the truth, as one value, so each move between states is one
   * update and no view sees half of it. A plain class, so a state flow compares it by identity and
   * never walks the lists.
   *
   * Each key with rows, known changes or records has a [KeySlice] that holds its part of [rows],
   * [known] and [committed], and a key's view reads only its slice. An update keeps the old slice
   * instance of a key when the new one is [KeySlice.sameAs] it. So a change on one key costs the
   * views of other keys one identity check.
   *
   * @property rows the store's rows as the shared subscription last read them, oldest first, or
   *   null while that subscription has no list.
   * @property listed the row ids in [rows], with their rows.
   * @property watching whether the shared subscription runs, from before its first store read until
   *   it stops.
   * @property known what the set knows of each change it touched, in the order it learned of them.
   * @property committed sent changes in commit order.
   * @property reported the changes whose [apply] threw and was reported, each with whether the list
   *   has shown its row since.
   */
  private class SetState<K, T, M>(
    val rows: List<OutboxEntry<KeyedMutation<K, M>>>? = null,
    val listed: Map<EntryId, OutboxEntry<KeyedMutation<K, M>>> = emptyMap(),
    val watching: Boolean = false,
    val known: PersistentMap<EntryId, Known<K, M>> = persistentMapOf(),
    val committed: PersistentList<Committed<K, T, M>> = persistentListOf(),
    val reported: PersistentMap<EntryId, Boolean> = persistentHashMapOf(),
    private val slices: PersistentMap<K, KeySlice<K, T, M>> = persistentHashMapOf(),
  ) {
    /** This state with another [watching] flag or [reported] map. No slice holds either. */
    fun copy(
      watching: Boolean = this.watching,
      reported: PersistentMap<EntryId, Boolean> = this.reported,
    ): SetState<K, T, M> = SetState(rows, listed, watching, known, committed, reported, slices)

    /** [key]'s slice, or null when the set has no rows, known changes or records for it. */
    fun slice(key: K): KeySlice<K, T, M>? = slices[key]

    /**
     * The slices with [slice] as [key]'s, or without one for [key] when [slice] is empty. A slice
     * that is [KeySlice.sameAs] the current one keeps the current instance.
     */
    private fun slicesWith(key: K, slice: KeySlice<K, T, M>): PersistentMap<K, KeySlice<K, T, M>> {
      val old = slices[key]
      return when {
        slice.isEmpty -> slices.removing(key)
        old != null && old.sameAs(slice) -> slices
        else -> slices.putting(key, slice)
      }
    }

    /**
     * This state with the values given, and every key's slice built again from them. It walks every
     * key, so it serves the updates that can touch many, like a new list.
     */
    private fun rebuilt(
      rows: List<OutboxEntry<KeyedMutation<K, M>>>? = this.rows,
      listed: Map<EntryId, OutboxEntry<KeyedMutation<K, M>>> = this.listed,
      watching: Boolean = this.watching,
      known: PersistentMap<EntryId, Known<K, M>> = this.known,
      committed: PersistentList<Committed<K, T, M>> = this.committed,
      reported: PersistentMap<EntryId, Boolean> = this.reported,
    ): SetState<K, T, M> {
      val rowsByKey = rows.orEmpty().groupBy { it.payload.key }
      val knownByKey = HashMap<K, PersistentMap.Builder<EntryId, Known<K, M>>>()
      for ((change, entry) in known) {
        val entries =
          knownByKey.getOrPut(entry.payload.key) {
            persistentMapOf<EntryId, Known<K, M>>().builder()
          }
        entries[change] = entry
      }
      val recordsByKey = HashMap<K, PersistentList.Builder<Committed<K, T, M>>>()
      for (record in committed) {
        val records =
          recordsByKey.getOrPut(record.key) { persistentListOf<Committed<K, T, M>>().builder() }
        records += record
      }
      val next = persistentHashMapOf<K, KeySlice<K, T, M>>().builder()
      for (key in slices.keys + rowsByKey.keys + knownByKey.keys + recordsByKey.keys) {
        val slice =
          KeySlice(
            rowsByKey[key].orEmpty(),
            knownByKey[key]?.build() ?: persistentMapOf(),
            recordsByKey[key]?.build() ?: persistentListOf(),
          )
        val old = slices[key]
        if (old != null && old.sameAs(slice)) {
          next[key] = old
        } else if (!slice.isEmpty) {
          next[key] = slice
        }
      }
      return SetState(rows, listed, watching, known, committed, reported, next.build())
    }

    /**
     * This state with each of [key]'s records replaced by what [transform] returns for it, or
     * removed when that is null. Other keys' records and slices stay.
     */
    fun updatingRecords(
      key: K,
      transform: (Committed<K, T, M>) -> Committed<K, T, M>?,
    ): SetState<K, T, M> {
      val records =
        committed
          .mapNotNull {
            if (it.key == key) {
              transform(it)
            } else {
              it
            }
          }
          .toPersistentList()
      val own = records.filter { it.key == key }.toPersistentList()
      val slice = (slices[key] ?: KeySlice()).copy(committed = own)
      return SetState(rows, listed, watching, known, records, reported, slicesWith(key, slice))
    }

    /** Whether the set still holds [change] in some form, or the list shows its row. */
    fun remembers(change: EntryId): Boolean =
      change in known || change in listed || committed.any { it.change == change }

    /** This state with [entry] as what the set knows of [change], in its key's slice too. */
    fun knowing(change: EntryId, entry: Known<K, M>): SetState<K, T, M> {
      val key = entry.payload.key
      val slice = slices[key] ?: KeySlice()
      return SetState(
        rows,
        listed,
        watching,
        known.putting(change, entry),
        committed,
        reported,
        slicesWith(key, slice.copy(known = slice.known.putting(change, entry))),
      )
    }

    /** This state without what the set knows of [change], in its key's slice too. */
    private fun unknowing(change: EntryId): SetState<K, T, M> {
      val entry = known[change] ?: return this
      val key = entry.payload.key
      val slice = slices[key] ?: KeySlice()
      return SetState(
        rows,
        listed,
        watching,
        known.removing(change),
        committed,
        reported,
        slicesWith(key, slice.copy(known = slice.known.removing(change))),
      )
    }

    /**
     * This state with [mark] on [change], starting an entry from the list's row when the set does
     * not know the change. A state with neither stays as it is.
     */
    fun marking(change: EntryId, mark: Mark): SetState<K, T, M> {
      val entry =
        known[change]
          ?: Known(settled = null, payload = (listed[change] ?: return this).payload, shown = true)
      return knowing(change, entry.copy(marks = entry.marks.adding(mark)))
    }

    /** This state without [mark] on [change]. */
    fun unmarking(change: EntryId, mark: Mark?): SetState<K, T, M> {
      val entry = known[change] ?: return this
      if (mark == null || mark !in entry.marks) {
        return this
      }
      return knowing(change, entry.copy(marks = entry.marks.removing(mark))).normalized(change)
    }

    /**
     * This state with [change] settled in [kind] at [now], without [mark]. Dropped is final, and
     * settling Dropped removes every mark, since no call can bring a discarded row back. [timer] is
     * the expiry timer of a HandedOff change.
     */
    fun settling(
      change: EntryId,
      kind: Kind,
      now: Instant,
      timer: Job?,
      mark: Mark?,
    ): SetState<K, T, M> {
      val entry = known[change] ?: return this
      val settled =
        if (entry.settled == Kind.Dropped) {
          Kind.Dropped
        } else {
          kind
        }
      val marks =
        when {
          settled == Kind.Dropped -> persistentListOf()
          mark != null -> entry.marks.removing(mark)
          else -> entry.marks
        }
      val next =
        entry.copy(
          settled = settled,
          // Each settle restarts the expiry, also into the same kind, so the lazy check and a
          // HandedOff timer count from the same moment. An outcome that Dropped ignores does not.
          since =
            if (entry.settled == Kind.Dropped && kind != Kind.Dropped) {
              entry.since
            } else {
              now
            },
          marks = marks,
          expiry = if (settled == Kind.HandedOff) timer ?: entry.expiry else null,
        )
      return knowing(change, next).normalized(change)
    }

    /** This state with [change] settled Dropped at [now], whatever the set knew of it. */
    fun dropping(change: EntryId, payload: KeyedMutation<K, M>, now: Instant): SetState<K, T, M> {
      val entry = known[change]
      return knowing(
        change,
        Known(
          Kind.Dropped,
          entry?.payload ?: payload,
          shown = entry?.shown ?: (change in listed),
          since = now,
        ),
      )
    }

    /** This state once [change]'s expiry ran out: the list decides it once no mark holds it. */
    fun expiring(change: EntryId): SetState<K, T, M> {
      val entry = known[change] ?: return this
      if (entry.marks.isEmpty()) {
        return forgetting(change)
      }
      return knowing(change, entry.copy(settled = null, expiry = null))
    }

    /**
     * This state with [change] forgotten if no mark holds it and the current list already agrees
     * with its settled state, by the table's forget rules.
     */
    fun normalized(change: EntryId): SetState<K, T, M> {
      val entry = known[change] ?: return this
      val row = listed[change]
      val passed = rows != null && entry.shown && row == null
      return if (entry.listAgrees(row, passed)) {
        forgetting(change)
      } else {
        this
      }
    }

    /**
     * This state without what the set knows of [change], and without its reported failure when no
     * other form of it remains.
     */
    fun forgetting(change: EntryId): SetState<K, T, M> {
      if (change !in known) {
        return this
      }
      val next = unknowing(change)
      return next.copy(reported = next.trimmedReports(listOf(change)))
    }

    /** [reported] without [changes] that no form holds anymore and the list does not show. */
    private fun trimmedReports(changes: Collection<EntryId>): PersistentMap<EntryId, Boolean> {
      var result = reported
      for (change in changes) {
        if (change in result && !remembers(change)) {
          result = result.removing(change)
        }
      }
      return result
    }

    /**
     * This state with [list] as the rows. Each known change and record notes whether [list] shows
     * its row, and goes when the table's rule says the list has caught up with it, or expired at
     * [now]. A reported failure goes once its change left every form after the list showed it.
     */
    fun withRows(
      list: List<OutboxEntry<KeyedMutation<K, M>>>,
      now: Instant,
    ): SetState<K, T, M> {
      val listed = LinkedHashMap<EntryId, OutboxEntry<KeyedMutation<K, M>>>()
      for (row in list) {
        listed[row.id] = row
      }
      var known = known
      for ((change, entry) in this.known) {
        val row = listed[change]
        val passed = entry.shown && row == null
        // A Sent commit may be retried for longer than any lifetime, so only its row decides
        val expires =
          entry.settled != Kind.Pending && entry.settled != Kind.Sent && entry.isExpired(now)
        if (entry.listAgrees(row, passed) || (entry.marks.isEmpty() && expires)) {
          known = known.removing(change)
        } else if (row != null && !entry.shown) {
          known = known.putting(change, entry.copy(shown = true))
        }
      }
      val records =
        committed
          .map { record ->
            val inList = record.change in listed
            val passed = record.passed || (record.shown && !inList)
            record.marked(shown = record.shown || inList, passed = passed)
          }
          .toPersistentList()
      var reported = reported
      for ((change, shown) in this.reported) {
        val inList = change in listed
        val held = change in known || records.any { it.change == change }
        val heldBefore = change in this.known || committed.any { it.change == change }
        // Gone only once it left every form now, or the list showed its row and lacks it now
        if (!inList && !held && (shown || heldBefore)) {
          reported = reported.removing(change)
        } else if (inList && !shown) {
          reported = reported.putting(change, true)
        }
      }
      return rebuilt(
        rows = list,
        listed = listed,
        known = known,
        committed = records,
        reported = reported,
      )
    }

    /**
     * This state once the shared subscription stopped, which leaves no list to trust. What the set
     * knows, its records and its reports stay.
     */
    fun withoutRows(): SetState<K, T, M> =
      rebuilt(rows = null, listed = emptyMap(), watching = false)

    /** This state with [record] added in place of what the set knew of its change. */
    fun withRecord(record: Committed<K, T, M>): SetState<K, T, M> {
      val marked = record.marked(shown = record.change in listed, passed = false)
      val next = unknowing(record.change)
      val slice = next.slices[record.key] ?: KeySlice()
      // A commit that runs again after a failure replaces the record the failed one left
      val others = committed.removeAll { it.change == record.change }
      val ownOthers = slice.committed.removeAll { it.change == record.change }
      return SetState(
        rows,
        listed,
        watching,
        next.known,
        others.adding(marked),
        reported,
        next.slicesWith(record.key, slice.copy(committed = ownOthers.adding(marked))),
      )
    }

    /**
     * This state after a truth for [key] fetched at [fetchedAt]: [key]'s records it confirms are
     * marked, and records expired at [now] go. So do known changes that expired. [settledRows]
     * holds the changes whose rows a store read found settled while no subscription ran, which
     * counts as passed.
     */
    fun confirming(
      key: K,
      fetchedAt: Instant?,
      now: Instant,
      settledRows: Set<EntryId>?,
    ): SetState<K, T, M> {
      val records =
        committed
          .map {
            val passed = it.passed || (settledRows != null && it.change in settledRows)
            it.marked(confirmed = it.confirmed || it.confirmedBy(key, fetchedAt), passed = passed)
          }
          .filter { !it.isExpired(now) }
          .toPersistentList()
      val gone =
        committed.map { it.change }.filter { change -> records.none { it.change == change } }
      var known = known
      val expired = ArrayList<EntryId>()
      for ((change, entry) in this.known) {
        val waits = entry.settled == Kind.Pending || entry.settled == Kind.Sent
        if (entry.marks.isEmpty() && !waits && entry.isExpired(now)) {
          known = known.removing(change)
          expired += change
        }
      }
      val next = rebuilt(known = known, committed = records)
      return next.copy(reported = next.trimmedReports(gone + expired))
    }
  }

  /**
   * A sent change: its row id [change], what the truth held after its commit, and when the send
   * finished.
   *
   * A truth fetched after [sentAt] holds the change. On a truth fetched before, a view puts the
   * change back: [truth] itself when [replaced] says the server answered with it, else the change
   * applied again. [folded] is the value the newest overlapping fetch was written as.
   *
   * Records stay small. Only the newest record on a key keeps its value and its fold, and a record
   * of a server answer keeps its answer while it is the newest answer, since a slow fetch gets it
   * back. The rest keep only the change and its send time, as [withoutValue] and [marked] decide.
   *
   * [shown] says the list showed the row, [passed] that the list has moved past it, and [confirmed]
   * that a fetch from after the send landed. [answerUnknown] says the server's answer could not be
   * decoded, so a truth with this change folded in is an estimate. A record goes when it expires.
   */
  private class Committed<K, T, M>(
    val change: EntryId,
    val key: K,
    val mutation: M,
    val sentAt: Instant,
    val truth: Fetched<T>?,
    val replaced: Boolean,
    val folded: Fold<T>? = null,
    val hasValue: Boolean = true,
    val shown: Boolean = false,
    val passed: Boolean = false,
    val confirmed: Boolean = false,
    val answerUnknown: Boolean = false,
  ) {
    private fun copy(
      truth: Fetched<T>? = this.truth,
      folded: Fold<T>? = this.folded,
      hasValue: Boolean = this.hasValue,
      shown: Boolean = this.shown,
      passed: Boolean = this.passed,
      confirmed: Boolean = this.confirmed,
    ): Committed<K, T, M> =
      Committed(
        change,
        key,
        mutation,
        sentAt,
        truth,
        replaced,
        folded,
        hasValue,
        shown,
        passed,
        confirmed,
        answerUnknown,
      )

    /**
     * This record with its list and fetch marks set. Once it is passed and confirmed, it drops its
     * folds and, unless it keeps a server answer, its own value, as [withoutValue] says. Only a
     * late reader would need them.
     */
    fun marked(
      shown: Boolean = this.shown,
      passed: Boolean = this.passed,
      confirmed: Boolean = this.confirmed,
    ): Committed<K, T, M> =
      if (shown == this.shown && passed == this.passed && confirmed == this.confirmed) {
        this
      } else if (passed && confirmed) {
        copy(shown = shown, passed = true, confirmed = true).withoutValue(newerAnswer = false)
      } else {
        copy(shown = shown, passed = passed, confirmed = confirmed)
      }

    /** Whether a truth for [key] fetched at [fetchedAt] read the server after this send. */
    fun confirmedBy(key: K, fetchedAt: Instant?): Boolean =
      this.key == key && fetchedAt != null && fetchedAt > sentAt

    /**
     * Whether [stored] already holds this change: its commit wrote it, or the newest fetch folded
     * it in. A record that gave up its value cannot tell, and a newer record answers in its place.
     * A record of an unknown answer never had a value, and only a fold tells that a truth holds it.
     * A stored null is a value, so it never matches nothing stored.
     */
    fun isHeldBy(stored: Fetched<T>?): Boolean {
      return (hasValue && (truth == null) == (stored == null) && truth?.value == stored?.value) ||
        folded?.holds(stored) == true
    }

    /** This record with its server answer marked stale. A record without one stays as it is. */
    fun invalidated(): Committed<K, T, M> =
      if (replaced && truth != null && !truth.invalidated) {
        copy(truth = truth.copy(invalidated = true))
      } else {
        this
      }

    /**
     * This record, held by [next], a fetch's value with this change folded in, from now on. [next]
     * no longer comes from after the send, so the record is no longer confirmed.
     */
    fun alsoHeldBy(next: Fetched<T>?): Committed<K, T, M> =
      copy(folded = Fold(next), confirmed = false)

    /**
     * This record without its copies of the value, once a newer commit on its key is in the store
     * or the record is passed and confirmed. It always drops its folds. A replaced record keeps its
     * answer, since that is the change a view puts back, until [newerAnswer] says the newer commit
     * is a server answer too. A fetch that lacks the older answer lacks the newer one as well, and
     * a view puts the newer one back in its place.
     */
    fun withoutValue(newerAnswer: Boolean): Committed<K, T, M> {
      val keepsAnswer = !hasValue || (replaced && !newerAnswer)
      return if (keepsAnswer && folded == null) {
        this
      } else if (keepsAnswer) {
        copy(folded = null)
      } else {
        copy(truth = null, folded = null, hasValue = false)
      }
    }

    /** Whether the send is old enough at [now] that no fetch from before it still runs. */
    fun isExpired(now: Instant): Boolean = now - sentAt >= RECORD_LIFETIME
  }

  /**
   * A key's base store and the lock its two writers, fetch and commit, take turns on. [open] runs
   * on the first read of [store], once, and a caller on another thread waits for it.
   */
  private class Base<T>(open: () -> QueryStore<T>) {
    val store: QueryStore<T> by lazy(open)
    val lock = Mutex()
  }

  /**
   * A change requested for [key]. [started] gets the handle of its row write once the outbox took
   * the row, and that handle settles once the row is in the store or failed to be.
   */
  private class Storing<K>(val key: K) {
    val started = CompletableDeferred<Operation>()
  }

  /**
   * The value a fetch was written as, or nothing stored when the folded changes removed it. It
   * keeps the value alone, without the fetch time, and tells a stored null apart from nothing
   * stored.
   */
  private class Fold<T>(written: Fetched<T>?) {
    private val present = written != null
    private val value = written?.value

    /** Whether [stored] holds this fold's value: both present with equal values, or both absent. */
    fun holds(stored: Fetched<T>?): Boolean = (stored != null) == present && stored?.value == value
  }

  /**
   * What a view computes its value from: the value it [start]s from and the [changes] it applies on
   * top, in order. Equal plans give equal values, since [apply] reads nothing else. The start
   * compares by identity, which is cheap and never calls two different values equal.
   */
  private class Plan<T, M>(
    val start: Fetched<T>?,
    val changes: List<Planned<M>>,
  ) {
    override fun equals(other: Any?): Boolean =
      other is Plan<*, *> && start === other.start && changes == other.changes

    override fun hashCode(): Int = changes.hashCode()
  }

  /** One change a [Plan] applies: its row id and its mutation. */
  private data class Planned<M>(val change: EntryId, val mutation: M)
}

/**
 * How long a record or a known change that left Pending stays when nothing proves it can go, like a
 * key that is never fetched again or a row the shared list never showed. The record exists for a
 * fetch that started before the send and lands after it, and both exist for a list that lags the
 * store. A fetch or a list running this late is far past any network timeout.
 */
private val RECORD_LIFETIME = 10.minutes

/**
 * How many commits of a set check that [RealOptimisticQueryStores]'s apply is idempotent. A broken
 * apply shows on the first commits that fold a change, and the check costs one more apply each.
 */
internal const val IDEMPOTENCE_CHECKS = 16

/** How long the shared row subscription waits after its [attempt]th failure, from 0. */
private fun retryPause(attempt: Long): Duration =
  minOf(1.seconds * (1 shl attempt.coerceAtMost(6L).toInt()), 1.minutes)

/**
 * Whether a row the set knows nothing about shows its change over [stored]: a row Queued or
 * Sending, or a Sent row that waits for its commit, unless [stored] was fetched after its send and
 * so already holds it.
 */
private fun OutboxEntry<*>.shows(stored: Fetched<*>?): Boolean =
  when (status) {
    OutboxStatus.Queued,
    OutboxStatus.Sending -> true
    OutboxStatus.Sent -> {
      val sentAt = sentAt
      !committed && (sentAt == null || stored?.fetchedAt?.isAfterSend(sentAt) != true)
    }
    else -> false
  }

/**
 * Whether a truth fetched at this time read the server after a send that finished at [sentAt]. It
 * compares epoch milliseconds, the precision a durable store keeps the send time in, so a commit
 * that runs again from the stored time decides the same way.
 */
private fun Instant.isAfterSend(sentAt: Instant): Boolean =
  toEpochMilliseconds() > sentAt.toEpochMilliseconds()

/**
 * How many sent changes a set still has on record. For tests, which cannot see pruning otherwise.
 */
internal val OptimisticQueryStores<*, *, *>.committedRecords: Int
  get() = (this as RealOptimisticQueryStores<*, *, *>).committedRecords

/**
 * How many of a set's records keep a copy of the value. For tests, which cannot see it otherwise.
 */
internal val OptimisticQueryStores<*, *, *>.committedValues: Int
  get() = (this as RealOptimisticQueryStores<*, *, *>).committedValues

/** How many landed commits a set remembers. For tests, which cannot see a leak otherwise. */
internal val OptimisticQueryStores<*, *, *>.landedCommits: Int
  get() = (this as RealOptimisticQueryStores<*, *, *>).landedCommits

/** How many changes a set remembers apart from its records. For tests. */
internal val OptimisticQueryStores<*, *, *>.knownChanges: Int
  get() = (this as RealOptimisticQueryStores<*, *, *>).knownChanges

/** How many reported apply failures a set remembers. For tests. */
internal val OptimisticQueryStores<*, *, *>.reportedApplies: Int
  get() = (this as RealOptimisticQueryStores<*, *, *>).reportedApplies
