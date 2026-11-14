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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
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
  /** Makes the optimistic change of a row about to be inserted. A throw inserts nothing. */
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
  // key settles them once a handle is attached. Updated by compare-and-set only.
  private val owedKeys = AtomicReference(persistentHashSetOf<String>())
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt. A timer that
  // fired stays until a drain takes its row, so the drain knows the wait is over.
  private val waking = AtomicReference(persistentMapOf<String, Timer>())
  // The unsettled handles following each row's current attempt, so the attempt's outcome reaches
  // them and a recheck finds them. Updated by compare-and-set only, and nothing observes it, so it
  // needs no StateFlow.
  private val follows = AtomicReference(Follows())
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
          // Fired, and the drain it started has not taken the row yet
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
   * committed, and drains their keys, which settles them oldest first per key. Until that read is
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
   */
  fun enqueue(id: EntryId, payload: E, key: String?): OutboxOperation {
    val owner = checkNotNull(attached.load()) { "Outbox $name has no handle" }.owner
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(id, Ordering.Keyed(Enqueue(this, rowKey))) { attempt ->
      owner.apply(payload, id)
      // Once the change is applied, the row goes in or the change is undone, even if the owner
      // stops meanwhile. A store that throws stored nothing, so no send or revert would reach it.
      withContext(NonCancellable) {
        rollbackOnFailure({ owner.revert(payload, id) }) { store.insert(id, rowKey, payload) }
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
        // A head that was backing off takes its timer along, so the next row sends now
        if (cancelTimer(row.key, id)) {
          drain(row.key)
        }
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
   * Sends [key]'s head until none is left, the key backs off or the network drops. Then it settles
   * the rows on [key] owed to the handle, offline too, since that needs no network.
   */
  private suspend fun sendQueued(key: String) {
    sendHeads(key)
    settledOwed(key)
  }

  /** Sends [key]'s head until none is left, the key backs off or the network drops. */
  private suspend fun sendHeads(key: String) {
    while (online.value) {
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
      // The read suspends, so the network may have dropped meanwhile
      if (!online.value) {
        break
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
      // Still backing off, perhaps since before a restart
      if (retryAt != null && retryAt > now && !waited) {
        backOff(key, head.id, retryAt)
        break
      }
      // Claims the row by moving it to Sending. If the row was discarded or retried between the
      // read and this write, the compare-and-set fails and the loop reads the head again.
      if (!store.update(head.copy(status = OutboxStatus.Sending), expected = head.status)) {
        continue
      }
      showFollowers(head.id, OperationState.Running)
      // The claim suspends too, so check the network once more. A send started offline would spend
      // an attempt for nothing, so the row goes back to Queued instead.
      if (!online.value) {
        store.update(head.copy(status = OutboxStatus.Queued), expected = OutboxStatus.Sending)
        showFollowers(head.id, OperationState.Queued)
        break
      }
      val result = runCatchingCancellable { deliver(head.payload, head.id) }
      val failure = result.exceptionOrNull()
      val attempts = head.attempts + 1
      if (failure == null) {
        val sentAt = clock.now()
        // The server has the change, so the row records it even if the owner stops now
        withContext(NonCancellable) { delivered(head, result.getOrThrow(), sentAt) }
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
        store.update(
          head.copy(
            status = OutboxStatus.Queued,
            attempts = attempts,
            lastError = failure.toString(),
            retryAt = nextAt,
          ),
          expected = OutboxStatus.Sending,
        )
        showFollowers(head.id, OperationState.Queued)
        // The next pass checks that the row is still the head before the timer holds the key
        backOff(key, head.id, nextAt)
      }
    }
  }

  /**
   * Records that [head] sent at [sentAt] with the server's answer [delivery]. It runs once per
   * send, NonCancellable, since the server already has the change.
   *
   * With a handle that is ready and no older row on the key still owed, the handle commits the row
   * first, and then one write marks it Sent and committed, with no answer kept. A crash between the
   * two leaves the row Sending, so it sends again, which the idempotency key makes safe.
   *
   * Otherwise the row is written Sent and not committed, with the answer encoded on it, and it is
   * owed to the handle. So is a row whose live commit threw, and then the commit runs again after a
   * pause, as [settleLater] describes. An answer that fails to encode is reported to the sender's
   * handler, and the row is written committed without a commit, since nothing could read the answer
   * back.
   */
  private suspend fun delivered(head: OutboxEntry<E>, delivery: Delivery<*>, sentAt: Instant) {
    val handle = attached.load()
    var retry = false
    if (handle != null && handle.ready && settledOwed(head.key)) {
      if (commit(handle, head, delivery, sentAt, expected = OutboxStatus.Sending)) {
        // Written or lost to a clear, no commit for the row runs again. A write that throws leaves
        // the row Sending, so it sends and commits again, and the handle keeps what it knows.
        writeSent(
          head,
          head.copy(status = OutboxStatus.Sent, committed = true, sentAt = sentAt, reply = null),
        )
        handle.owner.committed(head.id)
        return
      }
      retry = true
    }
    val reply = runCatching { encodeReply(delivery) }
    reply.exceptionOrNull()?.let(onFailure)
    val sent =
      writeSent(
        head,
        head.copy(
          status = OutboxStatus.Sent,
          committed = reply.isFailure,
          sentAt = sentAt,
          reply = reply.getOrNull(),
        ),
      )
    if (sent && reply.isSuccess) {
      if (retry) {
        // Not at once, since the commit just failed
        owedKeys.update { it.adding(head.key) }
        settleLater(head.key)
      } else {
        owe(head.key)
      }
    }
  }

  /**
   * Drains [key] again after a pause, so its owed rows settle once a commit that failed can land.
   * The pause doubles while the key's commits keep failing, up to [STORE_RETRY_MAX], and resets
   * once the key's owed rows all settle. It runs in the engine's scope, so it stops when that scope
   * stops.
   *
   * A key has at most one retry due at a time. A call while one is due does nothing, and until it
   * fires, [settledOwed] leaves the key's owed rows alone, whatever drains the key meanwhile, like
   * an enqueue, a later row's send or a reconnect. None of those can fix the store error that
   * failed the commit, so only the retry's own drain commits again. A key whose commits keep
   * failing makes one attempt per pause, however many rows wait on it.
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
   * Writes [sent] over the Sending [head] on the row's key and settles the handles that follow it.
   * Returns whether it wrote. It does not when something else moved the row, like a clear.
   */
  private suspend fun writeSent(head: OutboxEntry<E>, sent: OutboxEntry<E>): Boolean =
    onRow(head.id) {
      val written = store.update(sent, expected = OutboxStatus.Sending)
      if (written) {
        settleFollowers(head.id, OperationState.Succeeded)
      }
      written
    }

  /** [delivery]'s answer as the text a row keeps, or null for an answer of nothing. */
  private fun encodeReply(delivery: Delivery<*>): String? =
    when (delivery) {
      Delivery.Acknowledged -> null
      is Delivery.Replaced ->
        checkNotNull(replyCodec) { "Outbox $name has no reply codec for ${delivery.value}" }
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
    if (attached.load() != null) {
      settledOwed(key)
    }
  }

  /**
   * Settles [key]'s rows owed to the handle, oldest first, and returns whether none is left. It
   * does nothing without a handle, or when no row on [key] is known to be owed. A sent row is
   * committed with its stored answer and then marked committed. A failed row is marked reverted and
   * then reverted, at most once, on its row key. It reads only [key]'s owed rows.
   *
   * It runs on [key]'s drain lane, so no two settles of a key overlap. A store read that throws is
   * reported to the sender's handler, and so is a row write that throws, like the one that marks a
   * row committed. A commit that throws was reported to the handle's handler. Each of these stops
   * the key there and keeps it marked, so later rows wait, and the key settles again after a pause,
   * as [settleLater] describes.
   */
  private suspend fun settledOwed(key: String): Boolean {
    val handle = attached.load() ?: return true
    if (key !in owedKeys.load()) {
      return true
    }
    // A retry is due, and only its own drain commits again, as settleLater describes
    if (key in retryDue.load()) {
      return false
    }
    // Before the read, so a row marked owed while this reads is settled by the next drain
    owedKeys.update { it.removing(key) }
    try {
      for (row in store.owed(key)) {
        if (!settleOwed(handle, row)) {
          owedKeys.update { it.adding(key) }
          settleLater(key)
          return false
        }
      }
      owedPauses.update { it.removing(key) }
      return true
    } catch (e: CancellationException) {
      owedKeys.update { it.adding(key) }
      throw e
    } catch (e: Exception) {
      owedKeys.update { it.adding(key) }
      onFailure(e)
      settleLater(key)
      return false
    }
  }

  /**
   * Settles one [row] owed to [handle], as [settledOwed] describes, and returns false when its
   * commit threw, so the row stays owed. A sent row drops its stored answer in the write that marks
   * it committed. If that write fails, the row keeps the answer for the next commit.
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
          // The row may have been retried or discarded since the read, and then this writes nothing
          if (store.update(row.copy(reverted = true), expected = row.status)) {
            runCatchingCancellable { handle.owner.revert(row.payload, row.id) }
              .onFailure(handle.onFailure)
          }
        }
      }
      true
    }

  /**
   * The answer stored on [row], or null after reporting to [handle]'s handler when it does not
   * decode, which the handle treats as an unknown answer.
   */
  private fun decodeReply(handle: Attached<E>, row: OutboxEntry<E>): Delivery<*>? {
    val text = row.reply ?: return Delivery.Acknowledged
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
   * With a handle that is ready and no older row on the key still owed, the optimistic change is
   * then undone, at most once per row, and a throw from the revert goes to the handle's handler.
   * Otherwise the row is written not reverted and owed, so the handle reverts it once it attaches.
   */
  private suspend fun fail(head: OutboxEntry<E>, failure: Throwable, attempts: Int) {
    val status = OutboxStatus.Failed(failure.toString())
    val outcome = OperationState.Failed(failure as? PermanentFailure ?: PermanentFailure(failure))
    val handle = attached.load()
    val reverting = handle != null && handle.ready && settledOwed(head.key)
    // The row marked reverted promises an undo attempt, so the write and that attempt finish
    // together even if the owner stops meanwhile. A retry of the row waits for both.
    withContext(NonCancellable) {
      val failed =
        onRow(head.id) {
          withContext(NonCancellable) {
            // Before the write, so a handle that reads the Failed row before it settles gets this
            remember(head.id, FailedRow(status, outcome))
            val reverted = head.reverted || reverting
            val failed =
              store.update(
                head.copy(status = status, attempts = attempts, reverted = reverted),
                expected = OutboxStatus.Sending,
              )
            if (failed) {
              settleFollowers(head.id, outcome)
              try {
                if (reverting && !head.reverted) {
                  runCatchingCancellable { handle.owner.revert(head.payload, head.id) }
                    .onFailure(handle.onFailure)
                }
              } finally {
                onFailure(failure)
              }
            } else {
              forget(head.id)
            }
            failed
          }
        }
      if (failed && !reverting && !head.reverted) {
        owe(head.key)
      }
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
   * Wakes [key]'s drain once the backoff of its head row [id] ends: at [retryAt], or after the
   * policy's bound if that comes first.
   */
  private fun backOff(key: String, id: EntryId, retryAt: Instant) {
    val wakeAt = wakeAt(retryAt, clock.now())
    val timer =
      Timer(id, retryAt, wakeAt, scope.launch(start = CoroutineStart.LAZY) { awaitClock(wakeAt) })
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

  /**
   * Waits until [clock] reaches [deadline], or until [maxWait] has passed, whichever comes first.
   * `delay` follows a monotonic clock, which stops while an Android device sleeps, so this waits in
   * steps of at most [TIMER_STEP] and reads the clock after each. A deadline that passed during
   * sleep then fires within one step of waking. The steps add up to the time waited, so a clock set
   * back meanwhile cannot stretch the wait past [maxWait].
   */
  private suspend fun awaitClock(deadline: Instant) {
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

    /** Follows [row] before anything can settle the attempt, then records it. */
    fun started(row: EntryId) {
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
   * a discard by an optimistic store's clear or a logout's clearAll. Otherwise it shows the row's
   * state. One recheck of a row runs at a time, and a request that comes meanwhile runs once more
   * after it, so a burst of changes to one row costs at most two reads.
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
   */
  private suspend fun recheckOnce(id: EntryId) {
    if (id !in follows.load().byRow) {
      return
    }
    val state = readState(id)
    if (state == null) {
      recheckLater(id)
      return
    }
    if (state !is OperationState.Terminal) {
      recheckedAll(id)
      showFollowers(id, state)
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
          else -> {
            showFollowers(id, again)
            true
          }
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
