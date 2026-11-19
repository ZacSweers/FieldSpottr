// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxChange
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.UnreadableEntryException
import dev.zacsweers.fieldspottr.data.core.rollbackOnFailure
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * What a handle does with an outbox's rows, as [RealOutbox.attach] takes it. The engine sends the
 * rows, and the handle keeps all local state. Calls for one key run on that key's drain lane, in
 * send order, so a commit never overtakes an older row's commit on its key.
 */
internal interface RowOwner<E> {
  /**
   * Makes the optimistic change of a row about to be inserted. A throw inserts nothing. The owner's
   * stop does not cut it off, so a change is never left half applied with no row to send or undo.
   */
  suspend fun apply(payload: E, id: EntryId)

  /** Undoes the optimistic change of a row that failed for good or was discarded. */
  suspend fun revert(payload: E, id: EntryId)

  /**
   * Takes a sent row into the local state, like an optimistic store that writes the change into its
   * truth. [delivery] is the server's answer, or null when a stored answer cannot be decoded, so
   * the answer is unknown. [entry] was read with status [expected]. The handle checks that the row
   * still has it, under whatever lock its state needs, and does nothing when it has not.
   *
   * A throw means the commit did not land, like on a store error, and the engine keeps the row Sent
   * and not committed and calls this again later with the same row. A failure that a retry cannot
   * fix, like an answer that cannot be decoded, is the handle's to absorb, and it returns normally.
   * After a return the engine marks the row committed. A process that dies between the two calls
   * this again in the next process with the same row, so a commit must be safe to repeat.
   */
  suspend fun commit(
    entry: OutboxEntry<E>,
    delivery: Delivery<*>?,
    sentAt: Instant,
    expected: OutboxStatus,
  )

  /**
   * Row [id] is marked committed after its commit, so no commit for it runs again in this process.
   * A handle that kept state for a commit that may repeat lets it go here.
   */
  fun committed(id: EntryId) {}
}

/**
 * What an optimistic store set needs of its outbox: an enqueue with an id the set made, and the row
 * calls. [AttachedOutbox] provides it, and [keyedByUnit] adapts it for a single store.
 */
internal interface OwnedRows<P> {
  fun enqueue(id: EntryId, payload: P, key: String?): OutboxOperation

  fun retry(id: EntryId): Operation

  fun discard(id: EntryId): Operation
}

/**
 * The [Outbox] a handle builder returns. It delegates to the [engine] that the data scope runs for
 * the key's sender, and makes each row's id before the insert.
 */
@OptIn(DelicateDataLayerApi::class, ExperimentalUuidApi::class)
internal class AttachedOutbox<E>(val engine: RealOutbox<E>) : Outbox<E>, OwnedRows<E> {
  override val entries: Flow<List<OutboxEntry<E>>> = engine.entries

  override fun enqueue(payload: E, key: String?): OutboxOperation =
    engine.enqueue(EntryId(Uuid.random().toString()), payload, key)

  override fun enqueue(id: EntryId, payload: E, key: String?): OutboxOperation =
    engine.enqueue(id, payload, key)

  override fun retry(id: EntryId): Operation = engine.retry(id)

  override fun discard(id: EntryId): Operation = engine.discard(id)

  override fun operation(id: EntryId): Operation = engine.operation(id)
}

/**
 * The engine of one sender's outbox: its rows in a store, with one drainer per key on its runner.
 * The data scope builds it from the sender when the scope is created, and it drains from then on. A
 * handle joins it later through [attach], once per engine.
 *
 * A change costs the outbox one row per key with a waiting row: its watcher follows the store's
 * heads, and a drain reads only its key's head. Handles hold no store subscription of their own.
 * Each reads its row once after it starts following, and then the drain settles it with the
 * attempt's outcome. A change the outbox did not make, like a discard by an optimistic store's
 * clear, reaches a handle through [OutboxStore.changes]. That one subscription names the rows each
 * write ended or deleted, and the outbox rereads only the followed rows among them, wherever they
 * wait.
 *
 * Every handle settles one of these ways: its first read finds the row settled or gone, the drain
 * settles the attempt it follows, [discard] settles the row's handles Cancelled, a recheck after a
 * change finds its row settled or gone, or the scope stops. A failed store read never settles a
 * handle. It is reported and read again later.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealOutbox<E>(
  /** Names this outbox in the failures it reports. */
  private val name: String,
  /** The payload type the rows hold, so a handle's key can be checked against it. */
  val descriptor: SerialDescriptor,
  /** The type of the server's answers, or null for a plain key, also checked against a handle. */
  val replyDescriptor: SerialDescriptor?,
  /** Runs the watchers, the handles' first reads and the backoff timers. */
  private val scope: CoroutineScope,
  /** Sends one payload and returns the server's answer. */
  private val deliver: suspend (E, EntryId) -> Delivery<*>,
  /**
   * Encodes the answer of a row sent while no handle can commit it, or null for a key whose sends
   * answer with nothing.
   */
  private val replyCodec: PayloadCodec<Any?>?,
  /** The rows. An optimistic store set reads and writes them beside this engine. */
  val store: OutboxStore<E>,
  /**
   * Decides whether a failed send retries and how long its row waits. A throw from it is reported,
   * and the row waits [POLICY_FAILURE_DELAY], so a broken policy never drops a change.
   */
  private val policy: RetryPolicy,
  private val online: StateFlow<Boolean>,
  /** Backoff deadlines outlive the process, so they use wall time. */
  private val clock: Clock,
  /**
   * The sender's handler. It hears the drain's failures: a store's failures to report its rows,
   * rows that fail for good, rows that cannot be decoded and a retry policy that throws. The
   * handle's own handler hears its apply, revert and commit failures and its failed commands.
   */
  private val onFailure: (Throwable) -> Unit,
  /**
   * Counts this outbox into the factory's backlog and returns where it reports what the rows still
   * have to send, on every change of the heads and every network change.
   */
  trackBacklog: () -> (OutboxBacklog) -> Unit,
) {
  // The drains, which report to the sender's handler
  private val drains = RealActionRunner(scope, onFailure)
  // The handle's commands and the row steps a drain takes on a row's key, where retry and discard
  // run too. A failed command came from the handle, so it reports to the handle's handler.
  private val actions = RealActionRunner(scope) { failure -> handleFailure(failure) }
  // The handle, once one attached. Set once by compare-and-set.
  private val attached = AtomicReference<Attached<E>?>(null)
  // Keys that may hold rows owed to the handle, as OutboxEntry.isOwed describes. A drain of such a
  // key settles them once a handle is attached. Only a key's own drain lane writes owed rows on it,
  // and that lane clears the mark once they have all settled. Updated by compare-and-set only.
  private val owedKeys = AtomicReference(persistentHashSetOf<String>())
  // Keys whose drain stopped because the network was down, until drainWhenBackOnline drains them
  private val offlineKeys = MutableStateFlow(persistentHashSetOf<String>())
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt. A timer that
  // fired stays until a drain reads its row as the head, so the drain knows the wait is over.
  private val waking = AtomicReference(persistentMapOf<String, Timer>())
  // The unsettled handles following each row's current attempt, so the attempt's outcome reaches
  // them and a recheck finds them. Updated by compare-and-set only, and nothing observes it, so it
  // needs no StateFlow.
  private val follows = AtomicReference(Follows())
  // Rows whose insert has not returned yet, which their handles already follow. A recheck skips
  // them, since a read before the insert finds no row. Updated by compare-and-set only.
  private val insertingRows = AtomicReference(persistentHashSetOf<EntryId>())
  // Set once the scope stops. A handle that starts following after that settles Cancelled itself.
  private val stopped = AtomicBoolean(false)
  // Bounds the handles' row reads in flight, as readRow describes
  private val rowReads = Semaphore(MAX_ROW_READS)
  // The outcome of each row this outbox failed for good, from just before its status write until a
  // retry or discard moves the row on, so a handle that reads the row in this process carries the
  // exception send threw, even one whose read lands before the drain settles it. It keeps the
  // newest MAX_REMEMBERED_FAILURES, oldest first. Updated by compare-and-set only.
  private val failedRows = AtomicReference(persistentMapOf<EntryId, FailedRow>())
  // Rows whose recheck failed to read them, with the pause before the next recheck and whether one
  // is scheduled. Cleared by a recheck that reads its row. Updated by compare-and-set only.
  private val rechecks = AtomicReference(persistentHashMapOf<EntryId, Recheck>())
  // Rows with a recheck running, and whether another was asked for meanwhile. Updated by
  // compare-and-set only.
  private val checking = AtomicReference(persistentHashMapOf<EntryId, Boolean>())
  // How long each key whose drain failed waits before its next try, doubling while it keeps
  // failing. Cleared by a drain that finishes. Updated by compare-and-set only.
  private val failing = AtomicReference(persistentMapOf<String, Duration>())
  // How long each key whose commit failed waits before it settles its owed rows again, doubling
  // while its commits keep failing. Cleared once the key's owed rows all settle. Updated by
  // compare-and-set only.
  private val owedPauses = AtomicReference(persistentMapOf<String, Duration>())
  // Keys with a commit retry due, so each key has at most one. Updated by compare-and-set only.
  private val retryDue = AtomicReference(persistentHashSetOf<String>())
  // The policy's bound on a wait, read once. A policy that throws here is reported and unbounded.
  private val maxWait: Duration =
    try {
      policy.maxDelay.coerceAtLeast(Duration.ZERO)
    } catch (e: Exception) {
      onFailure(e)
      Duration.INFINITE
    }

  /** The store's rows without Sent ones, which the server already has. */
  val entries: Flow<List<OutboxEntry<E>>> =
    store
      .entries()
      .map { rows -> rows.filter { it.status != OutboxStatus.Sent } }
      .distinctUntilChanged()
  // Reports each row the store cannot decode, once per store instance, until the scope stops
  private val undecodable: AutoCloseable = store.onUndecodable { id, cause ->
    onFailure(UnreadableEntryException(name, id, cause))
  }
  // Counted once nothing above can throw, so an outbox that fails to build leaves no backlog that
  // the factory waits to hear from. An outbox that fails here stops listening too.
  private val onBacklog =
    try {
      trackBacklog()
    } catch (e: Throwable) {
      undecodable.close()
      throw e
    }

  init {
    scope.launch { watch() }
    scope.launch { drainWhenBackOnline() }
    scope.launch { watchChanges() }
    // The watcher and drains read only heads, which are Queued or Sending. One read of every row
    // lets the store meet each stored row once, so it hands on a Failed row that does not decode
    // too. A failed read is reported and stops nothing.
    scope.launch { runCatchingCancellable { store.entries().first() }.onFailure(onFailure) }
    // Completes when the scope stops, also when it had stopped before this outbox was built
    scope.launch { awaitCancellation() }.invokeOnCompletion { stop() }
  }

  /**
   * Reports the backlog on every change of the heads and every network change, and starts a drain
   * for each key with a head while online. A store that fails to report its heads is reported and
   * watched again after a pause, or at once on reconnect, so rows already stored still send.
   */
  private suspend fun watch() {
    var pause = STORE_RETRY_FIRST
    var wasUp = false
    while (true) {
      runCatchingCancellable {
          combine(online, store.heads()) { up, heads -> up to heads }
            .collect { (up, heads) ->
              pause = STORE_RETRY_FIRST
              // Offline too, so a platform can schedule a drain for when the network is back
              onBacklog(backlogOf(heads))
              val keys = heads.mapTo(HashSet()) { it.key }
              if (up) {
                keys.forEach(::drain)
                // A timer that fired while offline left its drain undone, and its row may be gone
                if (!wasUp) {
                  (waking.load().keys - keys).forEach(::drain)
                }
              }
              wasUp = up
            }
        }
        .onFailure(onFailure)
      // Rows can send again from a reconnect on, so it ends the pause early
      withTimeoutOrNull(pause) { online.dropWhile { it }.first { it } }
      pause = minOf(pause * 2, STORE_RETRY_MAX)
    }
  }

  /**
   * Drains each key in [offlineKeys] once the network is back. [watch] drains on a reconnect too,
   * but [online] is a state flow, and its collector never sees a drop that ends before it runs. A
   * drain that read [online] during such a drop has stopped, and nothing else would start another.
   *
   * Each wait reads the flow's value anew. One collection of [offlineKeys] would skip a set that
   * was emptied here and filled again with the same keys before the collector looked.
   */
  private suspend fun drainWhenBackOnline() {
    while (true) {
      offlineKeys.first { it.isNotEmpty() }
      online.first { it }
      offlineKeys.getAndUpdate { persistentHashSetOf() }.forEach(::drain)
    }
  }

  /**
   * What [heads] still have to send. Each key waits on its head, so a key whose head backs off
   * holds its later rows too. A head in Sending counts as ready, since a restart sends it again at
   * once, and so does a backoff whose wait is over. A wait ends at the row's deadline, or earlier
   * by the policy's bound, as [RetryPolicy.maxDelay] describes.
   */
  private fun backlogOf(heads: List<OutboxEntry<E>>): OutboxBacklog {
    val now = clock.now()
    val timers = waking.load()
    var earliest: Instant? = null
    for (head in heads) {
      val retryAt = head.retryAt
      if (head.status == OutboxStatus.Sending || retryAt == null || retryAt <= now) {
        return OutboxBacklog.Ready
      }
      val timer = timers[head.key]?.takeIf { it.waitsFor(head) }
      val until =
        when {
          timer == null -> wakeAt(retryAt, now)
          // Fired, and the drain it started has not read the row yet
          !timer.job.isActive -> return OutboxBacklog.Ready
          else -> timer.wakeAt
        }
      if (until <= now) {
        return OutboxBacklog.Ready
      }
      if (earliest == null || until < earliest) {
        earliest = until
      }
    }
    return earliest?.let { OutboxBacklog.BackingOff(it) } ?: OutboxBacklog.Empty
  }

  /**
   * Joins [owner], the handle, to this engine, and returns the handle's [Outbox]. [onFailure],
   * which is already guarded, hears the handle's failures. A second handle throws, since the engine
   * would never run its apply, revert or commit.
   *
   * It then reads the rows owed to a handle, like rows sent in an earlier process that no handle
   * committed, and drains their keys, which settles them in send order per key. Until that read is
   * done, a drain records a sent row as owed and settles it at once, so a live commit never
   * overtakes an older owed row on its key.
   */
  fun attach(owner: RowOwner<E>, onFailure: (Throwable) -> Unit): AttachedOutbox<E> {
    val handle = Attached(owner, onFailure)
    check(attached.compareAndSet(null, handle)) {
      "Outbox $name already has a handle in this data scope. A handle lives as long as its data " +
        "scope and cannot be released, so build one handle per key, in the one owner of its data. " +
        "An owner with a shorter life creates a data scope of its own with its senders."
    }
    scope.launch { readOwed(handle) }
    return AttachedOutbox(this)
  }

  /**
   * Reads the rows owed to [handle] once, marks their keys and drains them, then marks [handle]
   * ready for live commits. Each drain then reads only its own key's owed rows. A failed read is
   * reported and tried again after a pause that doubles.
   */
  private suspend fun readOwed(handle: Attached<E>) {
    var pause = STORE_RETRY_FIRST
    while (true) {
      val read = runCatchingCancellable { store.owed() }
      val rows = read.getOrNull()
      if (rows == null) {
        read.exceptionOrNull()?.let(onFailure)
        delay(pause)
        pause = minOf(pause * 2, STORE_RETRY_MAX)
        continue
      }
      val keys = rows.mapTo(LinkedHashSet()) { it.key }
      owedKeys.update { current -> keys.fold(current) { all, key -> all.adding(key) } }
      handle.ready = true
      keys.forEach(::drain)
      return
    }
  }

  /** Reports [failure] to the handle's handler, or to the sender's when no handle is attached. */
  private fun handleFailure(failure: Throwable) {
    (attached.load()?.onFailure ?: onFailure)(failure)
  }

  /**
   * Runs the handle's apply for [payload], then inserts it as row [id], in one command. The id
   * comes first, so the handle knows it before the row exists.
   *
   * The handle follows the row before the insert. A store can show the row before its insert
   * returns, and a drain can send it and settle its attempt then. The handle hears that first
   * attempt, so a retry that follows never reaches it.
   */
  fun enqueue(id: EntryId, payload: E, key: String?): OutboxOperation {
    val owner = checkNotNull(attached.load()) { "Outbox $name has no handle" }.owner
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(id, Ordering.Keyed(Enqueue(this, rowKey))) { attempt ->
      // The owner's stop cuts off neither the apply nor what follows it. Once the change is
      // applied, the row goes in or the change is undone. A store that throws stored nothing, so no
      // send or revert would reach it.
      withContext(NonCancellable) {
        owner.apply(payload, id)
        attempt.inserting(id)
        val undo: suspend () -> Unit = {
          attempt.notInserted(id)
          owner.revert(payload, id)
        }
        rollbackOnFailure(undo) { store.insert(id, rowKey, payload) }
        // The watcher's list can lag the insert, so a waiter that subscribes once the row is
        // stored still sees work owed
        onBacklog(OutboxBacklog.Ready)
        attempt.started(id)
      }
      // Without waiting for the watcher, which may be pausing after a store failure
      drain(rowKey)
    }
  }

  /**
   * Requeues row [id] on its row key, where a drain also writes the row's final status and settles
   * its handles. So the failed attempt has settled its handles before this reads the row, and this
   * handle follows only the new attempt. It starts following after the lookup and the requeue, so a
   * store that throws fails this handle with that exception.
   */
  fun retry(id: EntryId): Operation =
    tracked(id, Ordering.Keyed(Row(this, id))) { attempt ->
      val row = store.entry(id).first()
      when (row?.status) {
        is OutboxStatus.Failed -> {
          forget(id)
          store.update(
            row.copy(status = OutboxStatus.Queued, attempts = 0, lastError = null, retryAt = null),
            expected = row.status,
          )
        }
        // Backing off: drop the deadline, then the timer
        OutboxStatus.Queued if row.retryAt != null -> {
          store.update(row.copy(retryAt = null), expected = OutboxStatus.Queued)
          cancelTimer(row.key, id)
        }
        else -> Unit
      }
      // A row that is gone still gets a handle, which its first read settles Cancelled
      attempt.started(id)
      row?.let { drain(it.key) }
    }

  // Its caller does not own the command, so the handle cannot cancel it
  fun discard(id: EntryId): Operation =
    actions.run(Ordering.Keyed(Row(this, id))) {
      val row = checkNotNull(store.entry(id).first()) { "$id is gone" }
      val pending = row.status == OutboxStatus.Queued || row.status is OutboxStatus.Failed
      // A row marked reverted promises an undo attempt, so the write that marks it and that attempt
      // finish together even if the owner stops meanwhile
      withContext(NonCancellable) {
        val discarded =
          pending &&
            store.update(
              row.copy(status = OutboxStatus.Discarded, reverted = true),
              expected = row.status,
            )
        check(discarded) { "$id is no longer pending" }
        forget(id)
        settleFollowers(id, OperationState.Cancelled)
        // A head that was backing off takes its timer along, so the next row sends now. The key
        // drains without a timer too, since a drain that read this row as the head may be about to
        // start one for it.
        cancelTimer(row.key, id)
        drain(row.key)
        // A throw fails this handle and reaches the handle's handler. The row stays Discarded.
        if (!row.reverted) {
          checkNotNull(attached.load()) { "Outbox $name has no handle" }
            .owner
            .revert(row.payload, id)
        }
      }
    }

  fun operation(id: EntryId): Operation {
    val state = MutableStateFlow<OperationState>(OperationState.Queued)
    // Before the first read, so a change after the read reaches the handle through a recheck
    follow(id, state)
    val handle = latched(state, { id }) { stateOf(id, readRow(id)) }
    return Operation.of(handle)
  }

  /**
   * One drainer per key. Sends the key's head until none is left, the network drops or a send
   * fails. Repeated calls coalesce. A drain that throws, like on a store failure, is reported and
   * tried again after a pause, since no new row or reconnect may come to start another.
   */
  private fun drain(key: String): Operation =
    drains.run(Ordering.Keyed(Drain(this, key), OnPrevious.Supersede)) {
      try {
        sendQueued(key)
      } catch (t: Throwable) {
        // Not when the owner stopped
        if (currentCoroutineContext().isActive) {
          drainLater(key)
        }
        throw t
      }
      failing.update { it.removing(key) }
    }

  /** Drains [key] again after a pause that doubles while its drains keep failing. */
  private fun drainLater(key: String) {
    val pause =
      failing
        .updateAndFetch { current ->
          val last = current[key]
          current.putting(
            key,
            if (last == null) {
              STORE_RETRY_FIRST
            } else {
              minOf(last * 2, STORE_RETRY_MAX)
            },
          )
        }
        .getValue(key)
    scope.launch {
      delay(pause)
      drain(key)
    }
  }

  /**
   * Settles the rows on [key] owed to the handle, then sends [key]'s head until none is left, the
   * key backs off or the network drops, then settles again. The first settle lets a row owed from
   * before, like one a handle that just attached found, commit before a newer row sends. The second
   * settles the rows the sends left owed. Both run offline too, since a settle needs no network,
   * and both skip a key whose commit retry is due, as [settleLater] describes.
   */
  private suspend fun sendQueued(key: String) {
    settleKey(key)
    sendHeads(key)
    settleKey(key)
  }

  /**
   * Sends [key]'s head until none is left, the key backs off or the network drops.
   *
   * [inSendOrder] commits owed rows by send time, so a row that will be owed records a time later
   * than every owed Sent row on [key]. Two sends can read the same time, and the clock can be set
   * back between them. A call reads the latest of those times at most once, before a send, and only
   * while no [liveHandle] rules such a row out. Only this lane writes owed Sent rows on [key], so
   * after the read the call keeps the time current itself.
   *
   * The read comes before the timer check and the claim. If it throws, or the owner stops while it
   * runs, the key's timer stays and the row is not sent, so no row is sent and then stored without
   * that time.
   *
   * Once a send has ended, [endAttempt] writes the row's new status before the drain reads
   * anything, and the owner's stop does not cut that write off. So the owner's stop leaves a row
   * Sending only when it comes after the row's claim and before its send has ended. A crash or a
   * store that cannot write leaves one too, and the next drain sends it again with the same id.
   */
  private suspend fun sendHeads(key: String) {
    var latestOwed: Instant? = null
    var readOwed = false
    while (true) {
      // A store may not check for cancellation, and a stopped owner claims no further row
      currentCoroutineContext().ensureActive()
      // The one place a drain stops for the network. The checks below come back here.
      if (!online.value) {
        offlineKeys.update { it.adding(key) }
        break
      }
      val head = store.head(key)
      if (head == null) {
        // Nothing waits on the key, so a timer left for a row that was discarded or sent
        // elsewhere goes too
        waking.load()[key]?.let { timer ->
          timer.job.cancel()
          dropTimer(key, timer)
        }
        break
      }
      // Before the timer check and the claim, so a read that throws leaves both as they were
      if (!readOwed && liveHandle(key) == null) {
        latestOwed = latestOwedSend(key)
        readOwed = true
      }
      // The reads suspend, so the network may have dropped meanwhile
      if (!online.value) {
        continue
      }
      // A timer holds the key only while its row is still the head with the same deadline. One
      // left over from a row that was discarded or retried meanwhile is dropped. The timer only
      // wakes the key, and wall time can pass its wake time first, like while the device sleeps,
      // so the clock decides too. A timer that fired ends the wait even when the row's deadline
      // still lies ahead, as after the clock was set back.
      val now = clock.now()
      val timer = waking.load()[key]
      var waited = false
      if (timer != null) {
        if (timer.waitsFor(head)) {
          if (timer.job.isActive && timer.wakeAt > now) {
            break
          }
          waited = true
        }
        timer.job.cancel()
        dropTimer(key, timer)
      }
      val retryAt = head.retryAt
      // Still backing off, perhaps since before a restart. A row left Sending by a dead process
      // already ended its wait, so it sends at once.
      val sending = head.status == OutboxStatus.Sending
      if (retryAt != null && retryAt > now && !waited && !sending) {
        backOff(key, head.id, retryAt)
        break
      }
      // Claims the row by moving it to Sending. The compare-and-set checks the status only. It
      // fails when the row was discarded between the read and this write, and the loop reads the
      // head again. A retry of a backing-off row leaves it Queued, so the claim still lands. The
      // claim ends the row's wait, so it clears the deadline, and a deadline the drain read earlier
      // never comes back.
      val claimed = head.copy(status = OutboxStatus.Sending, retryAt = null)
      if (!store.update(claimed, expected = head.status)) {
        continue
      }
      showFollowers(head.id, OperationState.Running)
      // The claim suspends too, so check the network once more. A send started offline would spend
      // an attempt for nothing, so the row goes back to Queued with no deadline, and sends at once
      // on reconnect.
      if (!online.value) {
        store.update(claimed.copy(status = OutboxStatus.Queued), expected = OutboxStatus.Sending)
        showFollowers(head.id, OperationState.Queued)
        continue
      }
      val result = runCatchingCancellable { deliver(head.payload, head.id) }
      val failure = result.exceptionOrNull()
      val attempts = head.attempts + 1
      if (failure == null) {
        val sentAt = clock.now()
        // One millisecond is the precision the stores keep
        val owedAt = latestOwed?.let { maxOf(sentAt, it + 1.milliseconds) } ?: sentAt
        delivered(head, result.getOrThrow(), sentAt, owedAt)
        // An upper bound from here on. A row committed at once is not owed, and a later time on the
        // next owed row does no harm.
        latestOwed = owedAt
        continue
      }
      // A PermanentFailure fails the row whatever the policy says
      val delay =
        if (failure is PermanentFailure) {
          null
        } else {
          delayAfter(attempts, failure)
        }
      if (delay == null) {
        // Poison pill: fail it, undo its local change, and let the key move on
        fail(head, failure, attempts)
      } else {
        val nextAt = clock.now() + delay
        val waiting =
          head.copy(
            status = OutboxStatus.Queued,
            attempts = attempts,
            lastError = failure.toString(),
            retryAt = nextAt,
          )
        endAttempt(head.id) { store.update(waiting, expected = OutboxStatus.Sending) }
        showFollowers(head.id, OperationState.Queued)
        // The next pass checks that the row is still the head before the timer holds the key
        backOff(key, head.id, nextAt)
      }
    }
  }

  /**
   * Records that [head] sent at [sentAt] with the server's answer [delivery]. It runs once per
   * send.
   *
   * A [liveHandle] commits the row first, and then one write marks it Sent and committed, with no
   * answer kept. The commit is not cut off when the owner stops.
   *
   * Otherwise the row is written Sent and not committed, with the answer encoded on it and [owedAt]
   * as its send time, as [sendHeads] describes, and it is owed to the handle. A key with an older
   * owed row takes this path, so the older row commits first, in the order [settleKey] keeps. A row
   * whose live commit threw is written owed too, and then the commit runs again after a pause, as
   * [settleLater] describes. An answer that fails to encode is reported to the sender's handler
   * once, and the row keeps [UNKNOWN_REPLY] in place of the answer. It is owed like any other row,
   * and its commit runs with the answer unknown.
   */
  private suspend fun delivered(
    head: OutboxEntry<E>,
    delivery: Delivery<*>,
    sentAt: Instant,
    owedAt: Instant,
  ) {
    val handle = liveHandle(head.key)
    if (handle != null) {
      val committed =
        withContext(NonCancellable) {
          val committed = commit(handle, head, delivery, sentAt, expected = OutboxStatus.Sending)
          if (committed) {
            // Written or lost to a clear, no commit for the row runs again. A write that throws
            // leaves the row Sending, so it sends and commits again, and the handle keeps what it
            // knows.
            writeSent(
              head,
              head.copy(
                status = OutboxStatus.Sent,
                committed = true,
                sentAt = sentAt,
                reply = null,
              ),
            )
            handle.owner.committed(head.id)
          }
          committed
        }
      if (committed) {
        return
      }
    }
    val encoded = runCatching { encodeReply(head.id, delivery) }
    encoded.exceptionOrNull()?.let(onFailure)
    val owed =
      head.copy(
        status = OutboxStatus.Sent,
        committed = false,
        sentAt = owedAt,
        reply = encoded.getOrElse { UNKNOWN_REPLY },
      )
    if (writeSent(head, owed)) {
      if (handle != null) {
        // The live commit just failed, so the key settles after a pause and not in the call below
        settleLater(head.key)
      }
      owe(head.key)
    }
  }

  /**
   * Drains [key] again after a pause, so its owed rows settle once a commit that failed can land.
   * The pause doubles while the key's commits keep failing, up to [STORE_RETRY_MAX], and resets
   * once the key's owed rows all settle. It runs in the engine's scope, so it stops when that scope
   * stops.
   *
   * A key has at most one retry due at a time. A call while one is due does nothing, and until it
   * fires, [settleKey] leaves the key's owed rows alone, whatever drains the key meanwhile, like an
   * enqueue, a later row's send or a reconnect. None of those can fix the store error that failed
   * the commit, so only the retry's own drain commits again. A key whose commits keep failing makes
   * one attempt per pause, however many rows wait on it.
   */
  private fun settleLater(key: String) {
    while (true) {
      val due = retryDue.load()
      if (key in due) {
        return
      }
      if (retryDue.compareAndSet(due, due.adding(key))) {
        break
      }
    }
    val pause =
      owedPauses
        .updateAndFetch { current ->
          val last = current[key]
          current.putting(
            key,
            if (last == null) {
              STORE_RETRY_FIRST
            } else {
              minOf(last * 2, STORE_RETRY_MAX)
            },
          )
        }
        .getValue(key)
    scope.launch {
      delay(pause)
      retryDue.update { it.removing(key) }
      drain(key)
    }
  }

  /**
   * The handle when it can take the outcome of a send on [key] at once: it has read the owed rows,
   * and no row on [key] is known to be owed, so nothing older has to settle first. Null means [key]
   * may hold an owed row. The mark in [owedKeys] answers that without a read.
   */
  private fun liveHandle(key: String): Attached<E>? =
    attached.load()?.takeIf { it.ready && key !in owedKeys.load() }

  /** The latest send time among [key]'s owed Sent rows, or null when it holds none. */
  private suspend fun latestOwedSend(key: String): Instant? =
    store.owed(key).filter { it.status == OutboxStatus.Sent }.mapNotNull { it.sentAt }.maxOrNull()

  /**
   * Writes [sent] over the Sending [head] and settles the handles that follow it, as [endAttempt]
   * runs it. Returns whether it wrote. It does not when something else moved the row, like a clear.
   */
  private suspend fun writeSent(head: OutboxEntry<E>, sent: OutboxEntry<E>): Boolean =
    endAttempt(head.id) {
      val written = store.update(sent, expected = OutboxStatus.Sending)
      if (written) {
        settleFollowers(head.id, OperationState.Succeeded)
      }
      written
    }

  /** Row [id]'s answer [delivery] as the text the row keeps, or null for an answer of nothing. */
  private fun encodeReply(id: EntryId, delivery: Delivery<*>): String? =
    when (delivery) {
      Delivery.Acknowledged -> null
      is Delivery.Replaced ->
        checkNotNull(replyCodec) { "Outbox $name has no reply codec for the answer to ${id.value}" }
          .encode(delivery.value)
    }

  /**
   * Marks [key] as holding a row owed to the handle, then settles it if a handle is attached.
   *
   * The order makes sure an owed row never waits for nothing. The caller wrote the row, this marks
   * the key and then reads the handle. [attach] sets the handle and then reads the store. So either
   * this sees the handle and settles the row itself, or the attach's read finds the row and drains
   * its key.
   */
  private suspend fun owe(key: String) {
    owedKeys.update { it.adding(key) }
    settleKey(key)
  }

  /**
   * [owed], one key's owed rows oldest first, with the Sent rows in the order they sent: by
   * [OutboxEntry.sentAt], and in queue order when two share a time, like rows stored by an older
   * app version. Failed rows keep their places. A retried older row can send after a newer one, and
   * the server then holds the older row's change, so its commit comes last too.
   */
  private fun inSendOrder(owed: List<OutboxEntry<E>>): List<OutboxEntry<E>> {
    val sent = owed.filter { it.status == OutboxStatus.Sent }
    if (sent.size < 2) {
      return owed
    }
    // A stable sort, so equal times keep queue order
    val bySendTime = sent.sortedBy { it.sentAt ?: Instant.DISTANT_FUTURE }.iterator()
    return owed.map { row ->
      if (row.status == OutboxStatus.Sent) {
        bySendTime.next()
      } else {
        row
      }
    }
  }

  /**
   * Settles [key]'s rows owed to the handle in the order [inSendOrder] gives. It does nothing
   * without a handle, when no row on [key] is known to be owed, or while a commit retry is due, as
   * [settleLater] describes. A sent row is committed with its stored answer and then marked
   * committed. A failed row is marked reverted and then reverted, at most once, on its row key. It
   * reads only [key]'s owed rows.
   *
   * It runs on [key]'s drain lane, so no two settles of a key overlap, and no row on [key] becomes
   * owed while it runs. The key's mark goes once every row has settled. A store call that throws is
   * reported to the sender's handler, and a commit that throws was reported to the handle's
   * handler. Each stops the key there and leaves it marked, so later rows wait, and the key settles
   * again after a pause, as [settleLater] describes.
   */
  private suspend fun settleKey(key: String) {
    val handle = attached.load() ?: return
    if (key !in owedKeys.load() || key in retryDue.load()) {
      return
    }
    val settled = runCatchingCancellable {
      // A store may not check for cancellation, and a stopped owner reads and settles no
      // further row
      currentCoroutineContext().ensureActive()
      inSendOrder(store.owed(key)).all { row ->
        currentCoroutineContext().ensureActive()
        settleOwed(handle, row)
      }
    }
      .onFailure(onFailure)
      .getOrDefault(false)
    if (settled) {
      owedKeys.update { it.removing(key) }
      owedPauses.update { it.removing(key) }
    } else {
      settleLater(key)
    }
  }

  /**
   * Settles one [row] owed to [handle], as [settleKey] describes, and returns false when its commit
   * threw, so the row stays owed. A sent row drops its stored answer in the write that marks it
   * committed. If that write fails, the row keeps the answer for the next commit.
   */
  private suspend fun settleOwed(handle: Attached<E>, row: OutboxEntry<E>): Boolean =
    withContext(NonCancellable) {
      if (row.status == OutboxStatus.Sent && !row.committed) {
        val sentAt = row.sentAt ?: clock.now()
        if (!commit(handle, row, decodeReply(handle, row), sentAt, expected = OutboxStatus.Sent)) {
          return@withContext false
        }
        // Written or lost to a clear, no commit for the row runs again. A write that throws keeps
        // the row owed with its answer, and the key settles again after a pause. The answer is
        // cleared in the write that marks the row committed, so a commit that runs again reads it.
        store.update(row.copy(committed = true, reply = null), expected = OutboxStatus.Sent)
        handle.owner.committed(row.id)
      } else if (row.status is OutboxStatus.Failed && !row.reverted) {
        onRow(row.id) {
          // The row marked reverted promises an undo attempt, so both finish even if the owner
          // stops. The NonCancellable above does not cover this step, which runs in the owner's
          // scope.
          withContext(NonCancellable) {
            // The row may have been retried or discarded since the read, and then this writes
            // nothing
            if (store.update(row.copy(reverted = true), expected = row.status)) {
              runCatchingCancellable { handle.owner.revert(row.payload, row.id) }
                .onFailure(handle.onFailure)
            }
          }
        }
      }
      true
    }

  /**
   * The answer stored on [row], or null when it is unknown, which the handle treats as an unknown
   * answer. An answer is unknown when [UNKNOWN_REPLY] stands in for one that failed to encode,
   * which the send already reported, or when it does not decode, which is reported to [handle]'s
   * handler here.
   */
  private fun decodeReply(handle: Attached<E>, row: OutboxEntry<E>): Delivery<*>? {
    val text = row.reply ?: return Delivery.Acknowledged
    if (text == UNKNOWN_REPLY) {
      return null
    }
    return try {
      Delivery.Replaced(checkNotNull(replyCodec) { "Outbox $name has no reply codec" }.decode(text))
    } catch (e: Exception) {
      handle.onFailure(
        IllegalStateException(
          "Outbox $name cannot decode the server's answer to row ${row.id.value}, so its handle " +
            "commits the row without it",
          e,
        )
      )
      null
    }
  }

  /**
   * [RowOwner.commit] on [handle], and whether it landed. A throw is reported to the handle's
   * handler once, and the caller keeps the row owed.
   */
  private suspend fun commit(
    handle: Attached<E>,
    row: OutboxEntry<E>,
    delivery: Delivery<*>?,
    sentAt: Instant,
    expected: OutboxStatus,
  ): Boolean = runCatchingCancellable {
    handle.owner.commit(row, delivery, sentAt, expected)
  }
    .onFailure(handle.onFailure)
    .isSuccess

  /**
   * Fails [head] for good after [failure] ended its [attempts]th attempt. Handles that follow the
   * attempt settle with [failure] itself when it is a [PermanentFailure], or else with a
   * [PermanentFailure] whose cause is [failure], so a caller can branch on its type. The sender's
   * handler hears [failure] once.
   *
   * A [liveHandle] then undoes the optimistic change, at most once per row, and a throw from the
   * revert goes to the handle's handler. Otherwise the row is written not reverted and owed, and
   * the handle reverts it in its place among the key's owed rows, as [settleKey] orders them.
   */
  private suspend fun fail(head: OutboxEntry<E>, failure: Throwable, attempts: Int) {
    val status = OutboxStatus.Failed(failure.toString())
    val outcome = OperationState.Failed(failure as? PermanentFailure ?: PermanentFailure(failure))
    val handle = liveHandle(head.key)
    // One step, so the row marked reverted gets its undo attempt, and a retry of the row waits for
    // both
    val failed =
      endAttempt(head.id) {
        // Before the write, so a handle that reads the Failed row before it settles gets this
        remember(head.id, FailedRow(status, outcome))
        val failed =
          store.update(
            head.copy(
              status = status,
              attempts = attempts,
              reverted = head.reverted || handle != null,
            ),
            expected = OutboxStatus.Sending,
          )
        if (failed) {
          settleFollowers(head.id, outcome)
          if (handle != null && !head.reverted) {
            runCatchingCancellable { handle.owner.revert(head.payload, head.id) }
              .onFailure(handle.onFailure)
          }
          onFailure(failure)
        } else {
          forget(head.id)
        }
        failed
      }
    // After the write, so the read of the key's owed rows stops when the owner stops
    if (failed && handle == null && !head.reverted) {
      owe(head.key)
    }
  }

  /**
   * The policy's wait after [failure] ended attempt [attempt], cut to [maxWait], or null to fail
   * the row. A policy that throws is reported, and the row waits [POLICY_FAILURE_DELAY].
   */
  private fun delayAfter(attempt: Int, failure: Throwable): Duration? {
    val delay =
      try {
        policy.delayAfter(attempt, failure)
      } catch (e: Exception) {
        onFailure(e)
        POLICY_FAILURE_DELAY
      }
    return delay?.coerceIn(Duration.ZERO, maxWait)
  }

  /**
   * When a wait for a deadline of [retryAt] that starts at [now] ends: at [retryAt], or after
   * [maxWait] if that comes first.
   */
  private fun wakeAt(retryAt: Instant, now: Instant): Instant =
    if (maxWait.isInfinite()) {
      retryAt
    } else {
      minOf(retryAt, now + maxWait)
    }

  /**
   * Runs [block] on row [id]'s key, where [retry] and [discard] run, and returns what it returned.
   * A drain writes a row's final status and settles the handles that follow it this way, so a retry
   * never starts between the two, and a handle the retry registers hears only the new attempt. A
   * throw from [block] is rethrown here, so the drain ends and reports it once.
   *
   * The step runs in the owner's scope. The owner's stop cancels a step that has not started, and
   * this then throws a CancellationException, also to a caller that waits in NonCancellable. That
   * exception also takes the place of a throw from [block] while the owner stops. A step that must
   * finish once it starts takes NonCancellable inside [block].
   */
  private suspend fun <R> onRow(id: EntryId, block: suspend () -> R): R {
    val result = CompletableDeferred<Result<R>>()
    val command =
      actions.run(Ordering.Keyed(Row(this, id))) {
        result.complete(runCatchingCancellable { block() })
      }
    // A command cancelled before it ran, like when the owner stops, left the row as it was
    command.invokeOnSettled {
      result.complete(Result.failure(CancellationException("The owner stopped before row $id")))
    }
    return result.await().getOrThrow()
  }

  /**
   * Runs [block], the write that ends the attempt of the Sending row [id], and returns what it
   * returned. The owner's stop does not cut it off.
   *
   * While the owner lives, [block] runs as a step on the row's key, as [onRow] describes. So a
   * [retry] never starts between the write and the settle of the row's handles, and one that read
   * the row earlier never writes its old copy over this write. The owner's stop cancels a step that
   * has not started, and the drain then runs [block] itself. That is safe because the drain owns a
   * Sending row: [retry] and [discard] leave it as it is, no command starts after the stop, and a
   * clear that discards the row fails the status check in the write.
   */
  private suspend fun <R> endAttempt(id: EntryId, block: suspend () -> R): R =
    withContext(NonCancellable) {
      var started = false
      try {
        onRow(id) {
          started = true
          // The step runs in the owner's scope, which the NonCancellable above does not cover
          withContext(NonCancellable) { block() }
        }
      } catch (e: CancellationException) {
        if (started) {
          throw e
        }
        block()
      }
    }

  /**
   * Wakes [key]'s drain once the backoff of its head row [id] ends: at [retryAt], or after the
   * policy's bound if that comes first.
   */
  private fun backOff(key: String, id: EntryId, retryAt: Instant) {
    val wakeAt = wakeAt(retryAt, clock.now())
    val wait = scope.launch(start = CoroutineStart.LAZY) { awaitClock(clock, wakeAt, maxWait) }
    val timer = Timer(id, retryAt, wakeAt, wait)
    waking.update { it.putting(key, timer) }
    timer.job.invokeOnCompletion { cause ->
      // Cancelled by retry(), by discard() or by a drain that found it stale or past its wake
      // time, and each of those drains itself. One that fired stays for the drain it starts.
      if (cause == null) {
        drain(key)
      } else {
        dropTimer(key, timer)
      }
    }
    timer.job.start()
  }

  /** Removes [timer] from [key] unless another timer has replaced it. */
  private fun dropTimer(key: String, timer: Timer) {
    waking.update { current ->
      if (current[key] === timer) {
        current.removing(key)
      } else {
        current
      }
    }
  }

  /** Cancels [key]'s timer if it waits for row [id]. A timer for another row keeps waiting. */
  private fun cancelTimer(key: String, id: EntryId): Boolean {
    val timer = waking.load()[key]?.takeIf { it.id == id } ?: return false
    timer.job.cancel()
    dropTimer(key, timer)
    return true
  }

  /**
   * A handle on row [id]: Queued until [write] reports the row through [Attempt.started], then the
   * outcome of that row's current attempt. A command that settles Cancelled after its row was
   * written still follows the row. The handle's `stored` succeeds as soon as the row is reported,
   * and otherwise ends as the write did.
   */
  private fun tracked(
    id: EntryId,
    ordering: Ordering,
    write: suspend (attempt: Attempt) -> Unit,
  ): OutboxOperation {
    val attempt = Attempt(MutableStateFlow(OperationState.Queued))
    val written = actions.run(ordering) { write(attempt) }
    // A write that ends without reporting a row stored nothing, whether it failed or was cancelled
    // before the insert. The state is terminal by the time the command settles.
    written.invokeOnSettled {
      if (!attempt.id.isCompleted) {
        attempt.stored.update { it.settledOr(written.state.value) }
      }
    }
    val state =
      latched(attempt.state, { attempt.row }) {
        val done = written.await()
        if (attempt.id.isCompleted) {
          val row = attempt.id.await()
          stateOf(row, readRow(row))
        } else {
          // The write itself failed or was cancelled
          done
        }
      }
    return RowOperation(id, state, Operation.of(attempt.stored.asStateFlow()))
  }

  /** Where a write reports its row, so the handle follows that row's current attempt. */
  private inner class Attempt(val state: MutableStateFlow<OperationState>) {
    val id = CompletableDeferred<EntryId>()
    @Volatile var row: EntryId? = null
    // Whether the row is in the store, for the handle's stored milestone
    val stored = MutableStateFlow<OperationState>(OperationState.Queued)

    /**
     * Follows [row] before its insert, since a drain can settle the attempt before the insert
     * returns. A recheck skips the row until the insert ends, as [recheckOnce] describes.
     */
    fun inserting(row: EntryId) {
      insertingRows.update { it.adding(row) }
      follow(row, state)
    }

    /** Stops following [row], whose insert stored nothing. */
    fun notInserted(row: EntryId) {
      unfollow(row, state)
      insertingRows.update { it.removing(row) }
    }

    /**
     * Follows [row] before anything can settle the attempt, then records it. A second follow of the
     * same handle changes nothing.
     */
    fun started(row: EntryId) {
      insertingRows.update { it.removing(row) }
      follow(row, state)
      this.row = row
      id.complete(row)
      stored.value = OperationState.Succeeded
    }
  }

  /** A handle over a row's current attempt, with [stored] over the write that put the row in. */
  private class RowOperation(
    override val id: EntryId,
    override val state: StateFlow<OperationState>,
    override val stored: Operation,
  ) : OutboxOperation

  /**
   * [state], after one read of its row by [firstRead] in the outbox's scope. From then on the drain
   * settles it through [settleFollowers], and a recheck settles it when something else changed the
   * row. A read that finds the row settled settles the handle. A read that throws is reported and
   * tried again after a pause, until it works or the handle has settled another way. If the scope
   * stops first, the handle settles Cancelled and the row stays for the next owner. Either way no
   * waiter is left waiting.
   */
  private fun latched(
    state: MutableStateFlow<OperationState>,
    id: () -> EntryId?,
    firstRead: suspend () -> OperationState,
  ): StateFlow<OperationState> {
    val reader = scope.launch {
      var pause = STORE_RETRY_FIRST
      var read: OperationState
      while (true) {
        val result = runCatchingCancellable { firstRead() }
        read = result.getOrNull() ?: OperationState.Queued
        val failure = result.exceptionOrNull() ?: break
        onFailure(failure)
        if (state.value is OperationState.Terminal) {
          break
        }
        delay(pause)
        pause = minOf(pause * 2, STORE_RETRY_MAX)
      }
      // The drain may have moved the handle on already, and a stale read must not move it back
      state.update { current ->
        when {
          current is OperationState.Terminal -> current
          read is OperationState.Terminal || current == OperationState.Queued -> read
          else -> current
        }
      }
      if (state.value is OperationState.Terminal) {
        id()?.let { unfollow(it, state) }
      }
    }
    // Also runs when the scope had already stopped, so the reader never started
    reader.invokeOnCompletion { cause ->
      if (cause != null) {
        state.update { it.settledOr(OperationState.Cancelled) }
        id()?.let { unfollow(it, state) }
      }
    }
    return state.asStateFlow()
  }

  /**
   * Rechecks the followed rows that [OutboxStore.changes] names, as [recheck] describes. Each
   * subscription starts with [OutboxChange.Unknown], which rechecks every followed row, so a change
   * made while the store was not watched still reaches its handles. A store that fails to report
   * its changes is reported and watched again after a pause that doubles while it keeps failing.
   */
  private suspend fun watchChanges() {
    var pause = STORE_RETRY_FIRST
    while (true) {
      runCatchingCancellable {
          store.changes().collect { change ->
            pause = STORE_RETRY_FIRST
            val followed = follows.load().byRow
            when (change) {
              OutboxChange.Unknown -> followed.keys.forEach(::recheck)
              is OutboxChange.Rows ->
                for (id in change.ids) {
                  if (id in followed) {
                    recheck(id)
                  }
                }
            }
          }
        }
        .onFailure(onFailure)
      delay(pause)
      pause = minOf(pause * 2, STORE_RETRY_MAX)
    }
  }

  /**
   * Reads row [id] again and settles its handles if something else settled or removed the row, like
   * a discard by an optimistic store's clear or a logout's clearAll. It only settles handles, and
   * leaves a row that has not settled to the drain. One recheck of a row runs at a time, and a
   * request that comes meanwhile runs once more after it, so a burst of changes to one row costs at
   * most two reads.
   */
  private fun recheck(id: EntryId) {
    val before = checking.fetchAndUpdate { it.putting(id, it.containsKey(id)) }
    if (before.containsKey(id)) {
      return
    }
    scope.launch {
      do {
        recheckOnce(id)
        val again = checking.fetchAndUpdate { current ->
          if (current[id] == true) {
            current.putting(id, false)
          } else {
            current.removing(id)
          }
        }
      } while (again[id] == true)
    }
  }

  /**
   * One recheck of row [id]. The read runs off the row's key, so a slow read never holds up the
   * drain. A row read as settled or gone is read again on its key, where [retry] runs, and settled
   * there, so a retry never lands between that read and the settle, and a handle of the retry's new
   * attempt never hears of an older change.
   *
   * A read that throws settles nothing. It is reported, and the row is rechecked after a pause that
   * doubles while reads keep failing, so its handles settle once reads work even when no other
   * write comes.
   *
   * A row whose insert has not returned is skipped. A read then could find no row and settle its
   * handle Cancelled. The handle reads the row once the insert returns.
   */
  private suspend fun recheckOnce(id: EntryId) {
    if (id !in follows.load().byRow) {
      // No handle waits for the row any more, so its pause goes too
      recheckedAll(id)
      return
    }
    if (id in insertingRows.load()) {
      return
    }
    val state = readState(id)
    if (state == null) {
      recheckLater(id)
      return
    }
    // The drain shows a row's progress itself, and its claim is not on the row's key, so a read
    // off the drain's order could show a state the drain already moved past
    if (state !is OperationState.Terminal) {
      recheckedAll(id)
      return
    }
    val settled =
      onRow(id) {
        when (val again = readState(id)) {
          null -> false
          is OperationState.Terminal -> {
            settleFollowers(id, again)
            true
          }
          else -> true
        }
      }
    if (settled) {
      recheckedAll(id)
    } else {
      recheckLater(id)
    }
  }

  /** Schedules one more recheck of row [id] unless one is already due, after a doubling pause. */
  private fun recheckLater(id: EntryId) {
    val before = rechecks.fetchAndUpdate { current ->
      val last = current[id]
      if (last?.due == true) {
        current
      } else {
        val pause =
          if (last == null) {
            STORE_RETRY_FIRST
          } else {
            minOf(last.pause * 2, STORE_RETRY_MAX)
          }
        current.putting(id, Recheck(pause, due = true))
      }
    }
    if (before[id]?.due == true) {
      return
    }
    val pause = rechecks.load()[id]?.pause ?: STORE_RETRY_FIRST
    scope.launch {
      delay(pause)
      rechecks.update { current ->
        current[id]?.let { current.putting(id, Recheck(it.pause, due = false)) } ?: current
      }
      recheck(id)
    }
  }

  /** Clears row [id]'s pause after a recheck that read the row, unless a recheck is due. */
  private fun recheckedAll(id: EntryId) {
    rechecks.update { current ->
      if (current[id]?.due == false) {
        current.removing(id)
      } else {
        current
      }
    }
  }

  /**
   * Row [id], read once for a handle. At most [MAX_ROW_READS] such reads run at once, so many
   * handles made together, like for a burst of enqueues on many keys, never hold as many store
   * subscriptions.
   */
  private suspend fun readRow(id: EntryId): OutboxEntry<E>? = rowReads.withPermit {
    store.entry(id).first()
  }

  /** Row [id] as a handle's state, or null after reporting a read that threw. */
  private suspend fun readState(id: EntryId): OperationState? = runCatchingCancellable {
    stateOf(id, readRow(id))
  }
    .getOrElse {
      onFailure(it)
      null
    }

  /**
   * [row], row [id] as read, as a handle's state. A Failed row that the drain is still failing
   * gives the outcome with the exception send threw. Otherwise only the stored text is known.
   */
  private fun stateOf(id: EntryId, row: OutboxEntry<E>?): OperationState {
    val status = row?.status
    if (status is OutboxStatus.Failed) {
      // The stored text matches only while the row still holds this failure
      failedRows
        .load()[id]
        ?.takeIf { it.status == status }
        ?.let {
          return it.outcome
        }
    }
    return row.toOperationState()
  }

  /** Remembers [failed] for row [id], dropping the oldest beyond the limit. */
  private fun remember(id: EntryId, failed: FailedRow) {
    failedRows.update { current ->
      var next = current.removing(id).putting(id, failed)
      while (next.size > MAX_REMEMBERED_FAILURES) {
        next = next.removing(next.keys.first())
      }
      next
    }
  }

  /** Forgets row [id]'s outcome, once the row has moved on from it. */
  private fun forget(id: EntryId) {
    failedRows.update { it.removing(id) }
  }

  /** How many backoff timers this outbox holds. For tests, which cannot see a leak otherwise. */
  internal val timersHeld: Int
    get() = waking.load().size

  /** How many rows handles follow. For tests, which cannot see a leak otherwise. */
  internal val rowsFollowed: Int
    get() = follows.load().byRow.size

  /** Follows row [id] with [state]. After the scope stopped, [state] settles Cancelled at once. */
  private fun follow(id: EntryId, state: MutableStateFlow<OperationState>) {
    follows.update { it.following(id, state) }
    if (stopped.load()) {
      state.update { it.settledOr(OperationState.Cancelled) }
      unfollow(id, state)
    }
  }

  private fun unfollow(id: EntryId, state: MutableStateFlow<OperationState>) {
    follows.update { it.unfollowing(id, state) }
  }

  /** Shows [state], which is not terminal, on every handle following row [id]. */
  private fun showFollowers(id: EntryId, state: OperationState) {
    follows.load().byRow[id]?.forEach { follower ->
      follower.update { it.settledOr(state) }
    }
  }

  /**
   * Settles every handle following row [id]'s current attempt with its [outcome], and forgets them,
   * so a later attempt's handles start fresh.
   */
  private fun settleFollowers(id: EntryId, outcome: OperationState) {
    val settled = follows.fetchAndUpdate { it.forgetting(id) }.byRow[id] ?: return
    settled.forEach { state -> state.update { it.settledOr(outcome) } }
  }

  /**
   * Settles every handle Cancelled once the scope stops, and stops hearing of rows the store cannot
   * decode. The rows stay for the next owner, which hears of the rows the store meets after this.
   */
  private fun stop() {
    stopped.store(true)
    val all = follows.exchange(Follows())
    for (followers in all.byRow.values) {
      followers.forEach { state -> state.update { it.settledOr(OperationState.Cancelled) } }
    }
    try {
      undecodable.close()
    } catch (e: Exception) {
      onFailure(e)
    }
  }

  /**
   * The handle joined to this engine, with its handler, already guarded. [ready] is set once the
   * rows owed to it are known, as [attach] describes.
   */
  private class Attached<E>(val owner: RowOwner<E>, val onFailure: (Throwable) -> Unit) {
    @Volatile var ready = false
  }

  // Keys this outbox owns on its ActionRunners. They include the instance, so two outboxes on one
  // runner never chain on each other.
  private data class Enqueue(val owner: RealOutbox<*>, val key: String)

  private data class Drain(val owner: RealOutbox<*>, val key: String)

  private data class Row(val owner: RealOutbox<*>, val id: EntryId)

  /** A row's Failed [status] as written, and the outcome with the exception send threw. */
  private class FailedRow(val status: OutboxStatus.Failed, val outcome: OperationState.Failed)

  /** A row's recheck pause, and whether a recheck after it is scheduled. */
  private class Recheck(val pause: Duration, val due: Boolean)

  /**
   * A backoff timer: the head row it waits for, that row's deadline [retryAt], and [wakeAt], when
   * the wait ends, which the policy's bound can bring before [retryAt].
   */
  private class Timer(val id: EntryId, val retryAt: Instant, val wakeAt: Instant, val job: Job) {
    /**
     * Whether [head] is still the row this timer waits for, with the same deadline. Stores may keep
     * the deadline in epoch milliseconds, so the deadlines compare at that precision.
     */
    fun waitsFor(head: OutboxEntry<*>): Boolean =
      head.id == id && head.retryAt?.toEpochMilliseconds() == retryAt.toEpochMilliseconds()
  }

  /** Every handle that follows a row's current attempt, by row. */
  private class Follows(
    val byRow: PersistentMap<EntryId, PersistentSet<MutableStateFlow<OperationState>>> =
      persistentHashMapOf()
  ) {
    fun following(id: EntryId, state: MutableStateFlow<OperationState>): Follows =
      Follows(byRow.putting(id, (byRow[id] ?: persistentHashSetOf()).adding(state)))

    fun unfollowing(id: EntryId, state: MutableStateFlow<OperationState>): Follows {
      val rest = byRow[id]?.removing(state) ?: return this
      return if (rest.isEmpty()) {
        forgetting(id)
      } else {
        Follows(byRow.putting(id, rest))
      }
    }

    fun forgetting(id: EntryId): Follows = Follows(byRow.removing(id))
  }
}

private const val WHOLE_QUEUE = ""

/**
 * What [OutboxEntry.reply] holds when the server's answer failed to encode, so the commit runs with
 * the answer unknown. The JSON and Base64 codecs never write it, since JSON never starts with `}`
 * and Base64 has no `}` and no space. An app version from before it existed fails to decode it, and
 * so it takes the same unknown-answer path.
 */
internal const val UNKNOWN_REPLY = "} unknown reply"

// How long the watcher, or a key whose drain failed, waits before reading a failing store again,
// doubling up to the cap
private val STORE_RETRY_FIRST = 1.seconds
private val STORE_RETRY_MAX = 1.minutes

// The longest a backoff timer waits before it reads the wall clock again
private val TIMER_STEP = 1.minutes

// The most row reads for handles that run at once
private const val MAX_ROW_READS = 4

// The most failures for good an outbox remembers with the exception send threw
private const val MAX_REMEMBERED_FAILURES = 64

// How long a row waits when the retry policy throws instead of deciding
private val POLICY_FAILURE_DELAY = 1.minutes

/**
 * Waits until [clock] reaches [deadline], or until [maxWait] has passed, whichever comes first.
 * `delay` follows a monotonic clock, which stops while an Android device sleeps, so this waits in
 * steps of at most [TIMER_STEP] and reads the clock after each. A deadline that passed during sleep
 * then fires within one step of waking. The steps add up to the time waited, so a clock set back
 * meanwhile cannot stretch the wait past [maxWait].
 */
internal suspend fun awaitClock(
  clock: Clock,
  deadline: Instant,
  maxWait: Duration = Duration.INFINITE,
) {
  var waited = Duration.ZERO
  while (true) {
    val left = deadline - clock.now()
    if (left <= Duration.ZERO || waited >= maxWait) {
      return
    }
    val step = minOf(left, TIMER_STEP, maxWait - waited)
    delay(step)
    waited += step
  }
}

/** This state if it is terminal, else [next]. A settled handle never changes again. */
private fun OperationState.settledOr(next: OperationState): OperationState =
  if (this is OperationState.Terminal) {
    this
  } else {
    next
  }

private fun OutboxEntry<*>?.toOperationState(): OperationState =
  when (val status = this?.status) {
    OutboxStatus.Queued -> OperationState.Queued
    OutboxStatus.Sending -> OperationState.Running
    OutboxStatus.Sent -> OperationState.Succeeded
    is OutboxStatus.Failed -> OperationState.Failed(PermanentFailure(status.cause))
    // Discarded, or pruned before this view saw it
    OutboxStatus.Discarded,
    null -> OperationState.Cancelled
  }

/** How many backoff timers an outbox holds. For tests, which cannot see a leak otherwise. */
internal val Outbox<*>.timersHeld: Int
  get() = (this as AttachedOutbox<*>).engine.timersHeld

/** How many rows an outbox's handles follow. For tests, which cannot see a leak otherwise. */
internal val Outbox<*>.rowsFollowed: Int
  get() = (this as AttachedOutbox<*>).engine.rowsFollowed
