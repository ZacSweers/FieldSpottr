// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

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
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PendingMutation
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.isDone
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
 * Views read the rows through one shared subscription to the store's row list, which can lag the
 * store by any time. So a view trusts what this set did or learned itself over the list. Every
 * change has an identity from the moment [mutate] makes it: the id of its row, which [mutate] makes
 * before the insert and which its commit record keeps.
 *
 * For each change it touched, the set keeps what it last did or learned in [SetState.known], or as
 * a commit record in [SetState.committed]. A view decides each such change from that alone, and
 * uses the list only for rows the set knows nothing about, like rows from an earlier process:
 *
 * | State     | Settled by                         | View applies | Forgotten when            |
 * |-----------|------------------------------------|--------------|---------------------------|
 * | Pending   | [mutate]                           | the change   | never, it only moves on   |
 * | HandedOff | an attempt that ended Cancelled    | the change   | list shows row, or passed |
 * | Sent      | an attempt that sent, no record    | the change   | passed                    |
 * | Committed | the commit after a send (a record) | the record   | confirmed and passed      |
 * | Failed    | an attempt that failed for good    | nothing      | list shows Failed, passed |
 * | Dropped   | [discard], [clear], or a send that | nothing      | passed                    |
 * |           | left no record                     |              |                           |
 *
 * "Passed" means the list showed the row and a later list lacks it. Pending moves on when its
 * commit records it, when its attempt ends, or when [discard] or [clear] drops it. Dropped is
 * final. HandedOff means the owner's scope stopped: the row waits for the next owner, so the change
 * keeps showing until the list shows the row. Sent means the server has the change and its commit
 * has not landed, like a commit that failed on a store error and runs again later, so the change
 * keeps showing until the commit's record replaces it. A Dropped change that left no record had a
 * commit whose [apply] threw, or a commit that found its row already discarded. A record is also
 * forgotten when no shared subscription runs and a store read finds its row settled, as described
 * below.
 *
 * On top of the settled state, each running [retry] or [discard] call holds a provisional mark,
 * Pending or Dropped, and the newest mark decides the view. A call removes only its own mark: in
 * the same update that settles the change by its outcome, or alone when the call failed before it
 * changed the row. The view then falls back to the settled state, whatever happened meanwhile. A
 * change with a mark is never forgotten. A mark on a change the set had forgotten starts an entry
 * with no settled state, and the list decides that change again once the mark goes. When the last
 * mark goes, the change's forget rule applies at once, and a change whose row the list already
 * passed is forgotten then, since no later list may come. A change with a record gets no mark,
 * since the record decides its view. A [retry] adds no mark while the newest mark is a [discard]'s.
 *
 * HandedOff, Failed and Dropped expire [RECORD_LIFETIME] after the change entered them, for a row
 * the list never shows. HandedOff, which shows a change, ends by a timer in the set's scope, so its
 * bound holds on an idle set. Failed and Dropped show nothing, so the set checks their expiry only
 * on each new list, commit and fetch write.
 *
 * A change whose insert failed, or whose owner stopped before the insert, has no row, and the set
 * forgets it at once. A change the set forgot is decided by the list: a row in flight applies, and
 * so does a Sent row that waits for its commit unless the truth was fetched after its send. Any
 * other shows nothing. The forget rules make sure the list has caught up with the set first, so the
 * list then agrees with what the set knew. Since [state] holds the list, the known changes and the
 * records together, each move between states is one update, and no view sees half of it.
 *
 * [state] also holds each key's slice of the list, the known changes and the records, and a key's
 * view reads only its slice. An update keeps the slice instance of each key it leaves alone, so a
 * view runs its overlay only when its truth or its own slice changes, and a change on one key costs
 * the views of other keys one identity check.
 *
 * A record is forgotten once a fetch from after its send landed and the list has moved past its
 * row: the list showed the row and a later list lacks it, or no shared subscription runs and a
 * store read finds the row settled. In the last case, a subscription that starts later reads the
 * store after that read. A record without that proof stays until [RECORD_LIFETIME] after its send.
 *
 * A sent change reaches the truth in two writes, the base store's and the row's status, and no
 * store makes them atomic. Meanwhile a fetch that started before the send can land with a value
 * that lacks the change. So the record keeps what the truth held after its commit, and when the
 * send finished. A view shows the record in place of its row, and applies the change again on top
 * of a truth fetched before the send. A fetch that overlapped the send is written with the change
 * folded in, so a restart, which has no records, keeps it too.
 *
 * Records stay small. The newest record on a key keeps the value it wrote and the newest fetch
 * value it was folded into. The newest server answer keeps its value too, since a slow fetch gets
 * it back. The rest keep only the change and its send time.
 *
 * Two cases fall outside the records: a fetch that overlapped the send but read the server after it
 * took the change, and a restart between the truth write and the row's status. In both, [apply]
 * gets a value that already holds the change, so it must be idempotent. The first
 * [IDEMPOTENCE_CHECKS] commits that fold a change check this on the value they folded and report
 * the first failure to [onFailure], once. A change the server answers with a value is never folded,
 * so nothing checks it, and a counter shows twice until a server answer replaces the truth.
 *
 * An [apply] that throws never breaks a view. A view leaves that change out, and the set reports it
 * to [onFailure] once for as long as the set remembers the change: until the set forgets it by the
 * rules above and the list no longer shows its row. A commit whose [apply] throws leaves the truth
 * as it was and marks it stale, so the next fetch reads the server's value, which holds the change.
 *
 * The outbox calls [commit] for each sent change, as this set's [RowOwner]. A live commit runs
 * right after the send, before the row is marked Sent. A row sent while no handle was attached,
 * like in a background drain, is committed when this set attaches, from the answer stored on the
 * row, and is marked committed after. Until then the list shows it Sent and not committed, and
 * views apply it like a row in flight.
 *
 * The query's fetch and the outbox's commit both write a key's truth, and they take turns on the
 * key's lock. A fetch write is dropped when the truth's fetch time moved past it, by the rule the
 * query passes to [QueryStore.writeFetched], so a later commit is never overwritten. A write or
 * clear also waits until the rows requested before it are stored, so it never publishes a value
 * that lacks them.
 */
@OptIn(DelicateDataLayerApi::class, ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
internal class RealOptimisticQueryStores<K : Any, T, M>(
  private val rows: OutboxStore<KeyedMutation<K, M>>,
  /**
   * Joins this set to the outbox engine over [rows] as its handle. It runs once, after everything
   * else here is built, since the engine can call [commit] at once.
   */
  attach: (RowOwner<KeyedMutation<K, M>>) -> OwnedRows<KeyedMutation<K, M>>,
  /**
   * The ordering key of a key's rows. Keys that are equal must give the same ordering key, so they
   * share one order however their `toString` reads.
   */
  private val orderKey: (K) -> String,
  private val base: (key: K) -> QueryStore<T>,
  private val apply: (current: T?, mutation: M) -> T?,
  /** The clock the queries' fetch times come from, so a send time compares against them. */
  private val clock: Clock,
  /**
   * Hears an [apply] that is not idempotent, once, an [apply] that throws, once per change, a
   * failed commit, and a failure to read the row list.
   */
  private val onFailure: (Throwable) -> Unit,
  /**
   * The context each view computes its overlay in: the data scope's dispatcher with no job
   * attached, so [apply] never runs on a collector's thread, such as the main thread.
   */
  private val context: CoroutineContext,
) : OptimisticQueryStores<K, T, M> {
  // The row list, what the set knows of each change it touched, the commit records and the
  // reported apply failures, with each key's slice of them, in one value so that each update
  // changes them together
  private val state = MutableStateFlow(SetState<K, T, M>())
  // Whether an apply that is not idempotent has been reported. Once is enough to fix it.
  private val reportedApply = AtomicBoolean(false)
  // How many commits have run the idempotence check, which stops at IDEMPOTENCE_CHECKS
  private val idempotenceChecks = AtomicInt(0)
  // One base store per key, shared by the key's views and its commits, so a store that keeps state
  // of its own, like one that caches what it loaded, sees every write. Kept for as long as this set
  // lives, as optimisticStores promises. Updated by compare-and-set only.
  private val bases = AtomicReference(persistentHashMapOf<K, Base<T>>())
  // Changes whose rows are still being written, by key, so a write or clear can wait for the ones
  // requested before it. Settled entries are dropped as new ones come in. Updated by
  // compare-and-set only.
  private val storing = AtomicReference(persistentListOf<Storing<K>>())
  // The changes whose commit wrote the truth while their rows may still be owed, each with whether
  // the row list has shown the row since, so a commit that runs again after its row write failed
  // never applies the change twice. An entry has no expiry, since retries can run for any time. It
  // goes once the row is done: when the list showed the row and then lacks it, or when a commit
  // finds the row done or gone. Updated by compare-and-set only.
  private val landed = AtomicReference(persistentHashMapOf<EntryId, Boolean>())

  // Runs the shared row subscription and the watchers that follow handles. It has no job of the
  // data scope's, so views keep reading the rows after that scope stops. The subscription stops
  // when nothing collects, and each watcher ends when its handle settles.
  private val sharing = CoroutineScope(context + SupervisorJob())

  /**
   * The shared subscription to the row list. It writes each list into [state] and emits nothing.
   * [SetState.watching] is set before the store is read and cleared once it stops, so a store read
   * can tell whether a list read before it may still arrive.
   *
   * A store that fails to list its rows is reported and read again after a pause. Once the views
   * have a list, they keep it meanwhile. Before the first list, views emit nothing until a read
   * succeeds. Once nothing collects, the list in [state] is dropped, since nothing keeps it
   * current. What the set knows of its changes stays.
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
   */
  private inner class Owner : RowOwner<KeyedMutation<K, M>> {
    override suspend fun apply(payload: KeyedMutation<K, M>, id: EntryId) {}

    override suspend fun revert(payload: KeyedMutation<K, M>, id: EntryId) {}

    /**
     * [commit] for a sent row. A store error, like a row read that fails or a truth write on a full
     * disk, throws, and the engine reports it to [onFailure], keeps the row Sent and not committed
     * and commits it again after a pause. Meanwhile the change shows, from its record or from the
     * sent row. A commit whose truth write landed before the row write failed does not apply the
     * change again, as [landed] records.
     *
     * Two failures end the commit without the change written, since a retry cannot fix them. An
     * [apply] that throws marks the truth stale and leaves the change out of the view, since no
     * view can apply it. An answer that cannot be decoded marks the truth stale too, and a record
     * without a value applies the change once on top of every truth fetched before the send, until
     * a fetch from after the send, which holds the change, replaces it. A stale mark that fails
     * throws, so the commit runs again.
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

    // No commit for the row runs again, so what this set knew of its landed truth write can go
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

  /** How many landed commits the set remembers. For tests, which cannot see a leak otherwise. */
  internal val landedCommits: Int
    get() = landed.load().size

  /** How many sent changes are still on record. For tests, which cannot see pruning otherwise. */
  internal val committedRecords: Int
    get() = state.value.committed.size

  /** How many records still keep a copy of the value. For tests, which cannot see it otherwise. */
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

  /** Gives the change its row id, records it as Pending, then enqueues its row and follows it. */
  override fun mutate(key: K, mutation: M): OutboxOperation {
    val id = EntryId(Uuid.random().toString())
    val payload = KeyedMutation(key, mutation)
    edit { it.knowing(id, Known(Kind.Pending, payload)) }
    val handle = outbox.enqueue(id, payload, key = orderKey(key))
    edit { s ->
      val known = s.known[id]
      if (known?.settled == Kind.Pending) {
        s.knowing(id, known.copy(handle = handle))
      } else {
        s
      }
    }
    storing.update { all ->
      all
        .filter { it.stored.state.value !is OperationState.Terminal }
        .toPersistentList()
        .adding(Storing(key, handle.stored))
    }
    sharing.launch {
      if (handle.stored.await() != OperationState.Succeeded) {
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
   * The state a row is in after its attempt ended this way. A success whose commit landed already
   * left a record in place of what the set knew, so a change the set still knows is sent and waits
   * for its commit, and keeps showing. A cancel means the owner stopped and the row waits for the
   * next one.
   */
  private fun OperationState.Terminal.kindAfter(): Kind =
    when (this) {
      OperationState.Succeeded -> Kind.Sent
      is OperationState.Failed -> Kind.Failed
      OperationState.Cancelled -> Kind.HandedOff
    }

  /**
   * Settles [change] in [kind] in one update, if [applies] to what the set knows of it, and removes
   * [mark], the provisional mark of the call that learned this. Dropped is final, so a change in it
   * stays. A HandedOff change gets a timer that forgets it when it expires.
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
   * change's timer then. It runs on [context], so a test's virtual time drives it. [edit] cancels
   * it when the change leaves HandedOff earlier.
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
   * of HandedOff. The transform may run more than once, so it must have no side effects.
   */
  private inline fun edit(transform: (SetState<K, T, M>) -> SetState<K, T, M>): SetState<K, T, M> {
    while (true) {
      val old = state.value
      val new = transform(old)
      if (state.compareAndSet(old, new)) {
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
        entry.stored.await()
      }
    }
  }

  // Sent rows wait only for their commit, and the server already has them
  override fun pending(key: K): Flow<List<PendingMutation<M>>> =
    snapshots
      .map { state ->
        state
          .slice(key)
          ?.rows
          .orEmpty()
          .filter { it.status != OutboxStatus.Sent }
          .map { row -> row.toPending() }
      }
      .distinctUntilChanged()

  override val pendingCount: Flow<Int> =
    snapshots
      .map { state -> state.rows.orEmpty().count { it.status != OutboxStatus.Sent } }
      .distinctUntilChanged()

  /**
   * Puts a provisional Pending mark of this call on row [id]'s change, so views show it from now,
   * then requeues the row. The set finds the change by the row id that the list or its own
   * knowledge holds. When the new attempt ends, the call's mark goes and the change settles by the
   * outcome, in one update. If the requeue itself fails, like on a store error, only the mark goes,
   * and the view falls back to the change's settled state, whatever happened meanwhile. A change
   * already settled Dropped gets no mark, since Dropped is final, and neither does a change whose
   * newest mark is a running discard's, a change with a commit record, or a change whose row the
   * list shows Sent.
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
   * Dropped. If it fails, as when the row already sends, only the call's mark goes, and the view
   * falls back to the change's settled state. A change with a commit record, or whose row the list
   * shows Sent, gets no mark.
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
   * leaves the change unmarked.
   *
   * A change with a commit record gets no mark, since the record decides its view. A lagging list
   * can still show the row of such a change, and a mark would start an entry that nothing forgets.
   * A change whose row the list shows Sent gets none either: the server has it, so a retry would
   * settle it Dropped and a discard would hide it until it fails. A retry adds no mark when the
   * change is Dropped, or when the newest mark is a running discard's, so the retry cannot show the
   * change while the discard settles.
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
   * A server answer replaces the truth as fresh data. Otherwise the change is applied to the view's
   * current value, which already holds earlier sent changes that a fetch may have dropped, and the
   * truth keeps its fetch time. A truth fetched after the send already holds the change, so it
   * stays as it is. When [apply] throws here, or the answer is unknown, the commit writes nothing,
   * marks the change Dropped and the truth stale, so the next fetch brings the server's value with
   * the change in it. A throw from [apply] is reported.
   *
   * The record replaces what the set knew of the change in one update, found by the row id, so this
   * holds even when the row sent before its insert returned.
   *
   * This runs on the key's lock, so no fetch through a view lands between the read and the write.
   * Another writer that shares the base store can, so the write keeps a value fetched after the
   * send, by the same rule. A row that a clear discarded during the send is dropped.
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
      // This commit wrote the truth already and only its row write failed, so applying the change
      // again would count it twice. The engine repeats only the row write.
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
        // The answer is unknown, so the truth is marked stale, and a record without a value keeps
        // the change applied on top of every truth fetched before the send until a later fetch
        // replaces it. A stale mark that fails throws before the record, so the commit runs again.
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
          )
        prune(key, current?.fetchedAt) { s -> s.withRecord(record) }
        return
      }
      val replaced = result is Delivery.Replaced
      val next =
        when {
          result is Delivery.Replaced -> Fetched(result.value, sentAt)
          alreadyFetched -> current
          else -> {
            val folded =
              try {
                applied(withSent(current, sent), payload.mutation)
              } catch (e: Exception) {
                // The stale mark first, so a mark that fails throws and the commit runs again
                base.store.invalidate()
                val now = clock.now()
                edit { it.dropping(change, payload, now) }
                reportApply(change, e) {
                  "apply threw for ${payload.mutation} while the store committed it, so the " +
                    "truth keeps its value and is marked stale"
                }
                return
              }
            checkIdempotent(folded, payload.mutation)
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
        )
      // Views show this in place of the row and the change's known state from here, so the writes
      // below never show as a gap. This key's records that the truth already confirms can go once
      // the list has passed their rows, and so can expired ones.
      prune(key, current?.fetchedAt) { s -> s.withRecord(record) }
      if (!alreadyFetched) {
        if (next == null) {
          base.store.clearFetched(fetchedSinceSend)
        } else {
          base.store.writeFetched(next, fetchedSinceSend)
        }
      }
      landed.update { it.putting(change, false) }
      // The store holds this record's value now, which tells views that it holds every change
      // before it too. So the key's older records need only what a slow fetch folds back in.
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

  /**
   * Reports [mutation] once if applying it again to [folded], which already holds it, changes the
   * value. A fetch that overlapped the send, or a resend after a restart, can pass [apply] such a
   * value, and the store cannot tell. So a change that fails this would show twice. The check costs
   * one more [apply] per commit, so only the first [IDEMPOTENCE_CHECKS] commits of the set that
   * fold a change run it. An [apply] that throws here is reported once as a failed check, since the
   * change is already in the truth.
   */
  private fun checkIdempotent(folded: Fetched<T>?, mutation: M) {
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
              "apply threw when the store applied $mutation again to check that apply is " +
                "idempotent. The change is in the truth. Make apply return a value that already " +
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
          "apply is not idempotent for $mutation: applied to a value that already holds the " +
            "change, it changed the value again. A store built with send needs a change that is " +
            "safe to apply again, so make apply return such a value unchanged, like an append " +
            "that skips an item already there. Or build the store with exchange, where the " +
            "value is an estimate until the server's answer corrects it."
        )
      )
    }
  }

  /**
   * Prunes records and expired known changes after a truth for [key] fetched at [fetchedAt]: it
   * marks [key]'s records that the truth confirms, then drops each record that is confirmed and
   * proven passed, and each expired record or known change. [also] runs in the same update, like
   * adding a commit's record.
   *
   * A confirmed record that the list has not passed yet gets a store read of its row, but only
   * while no shared subscription runs. The update drops it only if that still holds, so no list
   * read before the store read can arrive later.
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
   * Discards [key]'s unconfirmed rows, sending ones included, by writing their status, and returns
   * the changes it discarded. That works whether or not the outbox still runs, like after a logout
   * cancelled its scope. Handles settle Cancelled through the row, and the outbox moves the key on.
   *
   * A row left sending by a stopped owner would send again under the next owner, so it goes too. A
   * drain still sending it finds the row gone, and [commit] drops the change. The drainer can move
   * a row meanwhile, so each compare-and-set retries until it wins or the row is settled. A row
   * cannot become Sent and committed meanwhile, since that waits for [commit], which needs the
   * caller's lock. A Sent row that waits for its commit is marked committed without one, so the old
   * change never reaches the cleared truth.
   */
  private suspend fun discardPending(key: K): Map<EntryId, OutboxEntry<KeyedMutation<K, M>>> {
    val discarded = HashMap<EntryId, OutboxEntry<KeyedMutation<K, M>>>()
    // From the store itself: a row stored a moment ago may not have reached the shared rows yet,
    // and a row missed here would still send after the clear
    for (row in rows.entries().first()) {
      if (row.payload.key != key) {
        continue
      }
      discarded[row.id] = row
      var current: OutboxEntry<KeyedMutation<K, M>>? = row
      while (current != null && !current.isDone) {
        val dropped =
          if (current.status == OutboxStatus.Sent) {
            current.copy(committed = true)
          } else {
            current.copy(status = OutboxStatus.Discarded, reverted = true)
          }
        if (rows.update(dropped, expected = current.status)) {
          break
        }
        current = rows.entry(row.id).first()
      }
    }
    return discarded
  }

  /**
   * [stored] with every sent change it may lack put back, oldest first.
   *
   * [stored] holds a sent change when that change's commit or a later one wrote it, since each
   * commit carries the earlier changes, or when it was fetched after the send. Any other sent
   * change is missing: [stored] came from a fetch that read the server too early, or from a commit
   * whose write has not landed. A missing server answer holds everything sent before it, so the
   * newest one replaces [stored], and only the changes after it are applied again. A change whose
   * [apply] throws is left out.
   */
  private fun withSent(stored: Fetched<T>?, sent: List<Committed<K, T, M>>): Fetched<T>? {
    // The newest record whose change the store holds. Every record up to it is in the truth.
    val held = sent.indexOfLast { it.isHeldBy(stored) }
    val missing = sent.filterIndexed { index, record ->
      index > held && stored?.fetchedAt.let { it == null || it <= record.sentAt }
    }
    val answer = missing.indexOfLast { it.replaced && it.hasValue }
    var result =
      if (answer >= 0) {
        missing[answer].truth
      } else {
        stored
      }
    // An answer after this one gave up its value to a newer answer that has since gone, which
    // leaves its change to apply again like a send's
    for (record in missing.drop(answer + 1)) {
      result = appliedOrSkipped(result, record.mutation, record.change)
    }
    return result
  }

  /**
   * A key's view of [stored] with its [slice], by the table in the class KDoc: records first, then
   * the list's rows in flight that the set knows nothing about, then the changes the set knows as
   * Pending or HandedOff, in the order the set learned of them. It reads nothing but its arguments
   * and [apply], so the same truth and slice give the same value. A null slice gives [stored].
   */
  private fun overlay(stored: Fetched<T>?, slice: KeySlice<K, T, M>?): Fetched<T>? {
    if (slice == null) {
      return stored
    }
    val recorded = slice.committed.mapTo(HashSet()) { it.change }
    var result = withSent(stored, slice.committed)
    for (row in slice.rows) {
      val change = row.id
      if (!row.shows(stored) || change in recorded || change in slice.known) {
        continue
      }
      result = appliedOrSkipped(result, row.payload.mutation, change)
    }
    for ((change, known) in slice.known) {
      if (known.kind?.shows != true || change in recorded) {
        continue
      }
      result = appliedOrSkipped(result, known.payload.mutation, change)
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

  /**
   * [applied], or [stored] as it is when [apply] throws, which is reported once for [change], the
   * change's identity.
   */
  private fun appliedOrSkipped(stored: Fetched<T>?, mutation: M, change: EntryId): Fetched<T>? =
    try {
      applied(stored, mutation)
    } catch (e: Exception) {
      reportApply(change, e) { "apply threw for $mutation, so the store leaves the change out" }
      stored
    }

  /**
   * Reports that [apply] threw [e] for [change], once for as long as the set remembers it. The
   * decision is made on the current state, so a view that runs late never reports a change the set
   * already forgot. [SetState.reported] keeps the entry until the set forgets the change and the
   * list no longer shows its row, so a new collector does not report it again.
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

  /**
   * [key]'s view: the truth with the key's sent, stored and local changes on top. The overlay runs
   * on [context], only when the truth or the key's slice changed, and only a value that differs
   * from the last crosses to the collector.
   */
  private inner class KeyStore(private val key: K, private val base: Base<T>) :
    OptimisticQueryStore<T, M> {
    private val truth = base.store

    override val data: Flow<Fetched<T>?> = flow {
      // What this collection's last overlay read. The overlay reads only the truth and the
      // key's slice, and a state update keeps the slice instance of each key it leaves alone.
      // So the same truth and slice instance would give the last value again, which
      // distinctUntilChanged drops anyway, and the update is skipped. A state without rows
      // runs no overlay and keeps these, so the next list compares against the inputs of the
      // last value sent.
      var ran = false
      var lastStored: Fetched<T>? = null
      var lastSlice: KeySlice<K, T, M>? = null
      // The truth and the state are combined in one step, so a commit's record, which lands in
      // the state before the commit writes the truth, reaches the overlay no later than that
      // write
      val views =
        combineTransform(truth.data, state, watching) { stored, current, _ ->
          if (current.rows == null) {
            return@combineTransform
          }
          val slice = current.slice(key)
          if (ran && stored === lastStored && slice === lastSlice) {
            return@combineTransform
          }
          ran = true
          lastStored = stored
          lastSlice = slice
          emit(overlay(stored, slice))
        }
      emitAll(views)
    }
      .distinctUntilChanged()
      .flowOn(context)

    /**
     * The query's fetch write. It waits until the changes requested before it are stored, so the
     * value it publishes carries them. On the key's lock it drops a value that [keepsStored] says
     * to drop, like one older than a commit. The truth's own [QueryStore.writeFetched] checks
     * again, for a writer that shares the base store without this lock.
     *
     * The value may lack a send that finished after the fetch started, which only its in-memory
     * record shows. So the truth takes those changes too, as [withSent] puts them back, and a
     * restart still shows them.
     */
    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
      land(fetched, keepsStored)
      prune(key, fetched.fetchedAt)
    }

    /**
     * [writeFetched] for a value that is gone. It has no fetch time to tell which sends it may have
     * missed, so it counts as older than all of them, and every change on record goes back on top.
     */
    override suspend fun clearFetched(keepsStored: KeepsStored) {
      land(null, keepsStored)
    }

    /**
     * Stores [fetched], or removes the truth when it is null, with the sent changes it may lack
     * folded in, unless [keepsStored] says the truth should stay. The truth's own conditional write
     * or removal checks again, for a writer that shares the base store without this lock.
     */
    private suspend fun land(fetched: Fetched<T>?, keepsStored: KeepsStored) {
      awaitStored(key)
      base.lock.withLock {
        val stored = truth.data.first()
        if (stored == null || !keepsStored(stored.fetchedAt, stored.invalidated)) {
          // A value never fetched counts as older than every send, like a value that is gone
          val sentSince =
            state.value.slice(key)?.committed.orEmpty().filter { record ->
              fetched?.fetchedAt.let { it == null || record.sentAt >= it }
            }
          val next = withSent(fetched, sentSince)
          // Before the write, so views count every change on record as in the folded truth both
          // before and after it lands, and never apply one twice
          sentSince.lastOrNull()?.let { newest ->
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
      }
    }

    /**
     * Discards the key's rows, then in one update drops its records and settles Dropped each change
     * whose row it discarded and each change whose record it dropped, so no view shows one after
     * this returns however late the list is. A change stored after the rows were read keeps its row
     * and its state.
     */
    override suspend fun clear() {
      // A change still being stored would land after the rows below were read and then send
      awaitStored(key)
      base.lock.withLock {
        val discarded = discardPending(key)
        val now = clock.now()
        edit { s ->
          var next = s.updatingRecords(key) { null }
          for ((change, row) in discarded) {
            next = next.dropping(change, row.payload, now)
          }
          for (record in s.slice(key)?.committed.orEmpty()) {
            next = next.dropping(record.change, KeyedMutation(record.key, record.mutation), now)
          }
          next
        }
        truth.clear()
      }
    }

    // On the key's lock, so a commit that read the truth before this cannot write it back fresh
    override suspend fun invalidate() {
      base.lock.withLock { truth.invalidate() }
    }

    override fun mutate(mutation: M): OutboxOperation =
      this@RealOptimisticQueryStores.mutate(key, mutation)

    override val pending: Flow<List<PendingMutation<M>>> =
      this@RealOptimisticQueryStores.pending(key)

    override fun retry(id: EntryId): Operation = this@RealOptimisticQueryStores.retry(id)

    override fun discard(id: EntryId): Operation = this@RealOptimisticQueryStores.discard(id)
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
   * marks of [retry] and [discard] calls still running, newest last, and the newest one decides the
   * view. Each call removes only its own mark. The entry also keeps its [payload], whose key names
   * the slice that holds the entry, its [handle] while Pending from [mutate], whether the list has
   * shown its row, [since], when it left Pending, which starts its expiry, and the [expiry] timer
   * of a HandedOff change.
   */
  private class Known<K, M>(
    val settled: Kind?,
    val payload: KeyedMutation<K, M>,
    val handle: OutboxOperation? = null,
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
      handle: OutboxOperation? = this.handle,
      shown: Boolean = this.shown,
      since: Instant? = this.since,
      marks: PersistentList<Mark> = this.marks,
      expiry: Job? = this.expiry,
    ): Known<K, M> = Known(settled, payload, handle, shown, since, marks, expiry)

    fun isExpired(now: Instant): Boolean = since != null && now - since >= RECORD_LIFETIME
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
   * Everything a view reads besides the truth, as one value, so a move between states is seen
   * whole. A plain class, so a state flow compares it by identity and never walks the lists.
   *
   * Each key with rows, known changes or records has a [KeySlice] that holds its part of [rows],
   * [known] and [committed]. Each update that changes those three also builds the slice of each key
   * it touched, and keeps the old slice instance when the new one is [KeySlice.sameAs] it. So a key
   * keeps its slice instance through every update that leaves its part alone, and its views skip
   * such an update by an identity check.
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
     * This state with [mark] on [change], starting an entry from the list if needed. The set finds
     * [change] in what it knows or in the list, so the list holds the row of a change the set does
     * not know. A state with neither stays as it is.
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
          handle = null,
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
     * This state with [change] forgotten if no mark holds it and the list already agrees with its
     * settled state, as the table's forget rules say. A change whose row the list showed and the
     * current list lacks has passed, as [withRows] decides, so a change that leaves Pending after
     * that moment is forgotten here, since no later list may come.
     */
    fun normalized(change: EntryId): SetState<K, T, M> {
      val entry = known[change] ?: return this
      if (entry.marks.isNotEmpty()) {
        return this
      }
      val row = listed[change]
      val passed = rows != null && entry.shown && row == null
      val agrees =
        when (entry.settled) {
          null -> true
          Kind.Pending -> false
          Kind.HandedOff -> row != null || passed
          Kind.Sent -> passed
          Kind.Failed -> row?.status is OutboxStatus.Failed || passed
          Kind.Dropped -> passed
        }
      return if (agrees) {
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
        val forget =
          entry.marks.isEmpty() &&
            when (entry.settled) {
              null -> true
              Kind.Pending -> false
              Kind.HandedOff -> row != null || passed || entry.isExpired(now)
              // Its commit may be retried for longer than any lifetime, so only its row decides
              Kind.Sent -> passed
              Kind.Failed -> row?.status is OutboxStatus.Failed || passed || entry.isExpired(now)
              Kind.Dropped -> passed || entry.isExpired(now)
            }
        if (forget) {
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
          .filter { !(it.passed && it.confirmed) }
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
     * marked, and records that are confirmed and passed, or expired at [now], go. So do known
     * changes that expired. [settledRows] holds the changes whose rows a store read found settled
     * while no subscription ran, which counts as passed.
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
          .filter { !(it.passed && it.confirmed) && !it.isExpired(now) }
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
   * applied again. [folded] is the value the newest overlapping fetch was written as. Each such
   * write replaces the last, so older ones, like large response bodies, can go. A view that still
   * sees an older one, from a store whose emissions run late, applies the change again, which an
   * idempotent apply leaves as it is.
   *
   * [shown] says the list showed the row, [passed] that the list has moved past it, and [confirmed]
   * that a fetch from after the send landed. A record goes once both of the last two hold.
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
      )

    /** This record with its list and fetch marks set. */
    fun marked(
      shown: Boolean = this.shown,
      passed: Boolean = this.passed,
      confirmed: Boolean = this.confirmed,
    ): Committed<K, T, M> =
      if (shown == this.shown && passed == this.passed && confirmed == this.confirmed) {
        this
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
     */
    fun isHeldBy(stored: Fetched<T>?): Boolean {
      val value = stored?.value
      return (hasValue && truth?.value == value) || (folded != null && folded.value == value)
    }

    /** This record, held by [next], a fetch's value with this change folded in, from now on. */
    fun alsoHeldBy(next: Fetched<T>?): Committed<K, T, M> = copy(folded = Fold(next?.value))

    /**
     * This record without its copies of the value, once a newer commit on its key is in the store.
     * A replaced record keeps its value, since that is the change a view puts back, until
     * [newerAnswer] says the newer commit is a server answer too. A fetch that lacks the older
     * answer lacks the newer one as well, and a view puts the newer one back in its place.
     */
    fun withoutValue(newerAnswer: Boolean): Committed<K, T, M> =
      if (!hasValue || (replaced && !newerAnswer)) {
        this
      } else {
        copy(truth = null, folded = null, hasValue = false)
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

  /** A change requested for [key], and whether its row is in the store yet. */
  private class Storing<K>(val key: K, val stored: Operation)

  /** A value a fetch was written as, which may be null when the folded changes removed it. */
  private class Fold<T>(val value: T?)
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
