// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataActions
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.rollbackOnFailure
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [Outbox] over a store's rows, with one drainer per key on its runner. */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealOutbox<E>(
  /** Runs the row views and the backoff timers. */
  private val scope: CoroutineScope,
  private val actions: DataActions,
  private val send: suspend (E) -> Unit,
  private val revert: suspend (E) -> Unit,
  private val store: OutboxStore<E>,
  private val policy: RetryPolicy,
  private val online: StateFlow<Boolean>,
  /** Backoff deadlines outlive the process, so they use wall time. */
  private val clock: Clock,
  /** Hears a store's failures to report its rows, which no command's handle would carry. */
  private val onFailure: (Throwable) -> Unit,
) : Outbox<E> {
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt.
  private val waking = AtomicReference(persistentMapOf<String, Timer>())
  // The unsettled handles following each row's current attempt, so the attempt's outcome reaches
  // them even when their view of the row falls behind. Compare-and-set only. Nothing observes it.
  private val following =
    AtomicReference(persistentHashMapOf<EntryId, PersistentSet<MutableStateFlow<OperationState>>>())
  // How long each key whose drain failed waits before its next try, doubling while it keeps
  // failing. Cleared by a drain that finishes. Compare-and-set only.
  private val failing = AtomicReference(persistentMapOf<String, Duration>())

  override val entries: Flow<List<OutboxEntry<E>>> = store.entries()

  init {
    scope.launch { watch() }
  }

  /**
   * Starts a drain for each key with a sendable row while online. A store that fails to report its
   * rows is reported and watched again after a pause, or at once on reconnect, so rows already
   * stored still send.
   */
  private suspend fun watch() {
    var pause = STORE_RETRY_FIRST
    while (true) {
      runCatchingCancellable {
          combine(online, store.entries()) { up, rows ->
              if (up) {
                rows.filter { it.status.isSendable }.mapTo(HashSet()) { it.key }
              } else {
                emptySet()
              }
            }
            .collect { keys ->
              pause = STORE_RETRY_FIRST
              keys.forEach(::drain)
            }
        }
        .onFailure(onFailure)
      // Rows can send again from a reconnect on, so it ends the pause early
      withTimeoutOrNull(pause) { online.dropWhile { it }.first { it } }
      pause = minOf(pause * 2, STORE_RETRY_MAX)
    }
  }

  override fun enqueue(payload: E, key: String?, apply: suspend () -> Unit): Operation {
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(Ordering.Keyed(Enqueue(this, rowKey))) { attempt ->
      apply()
      // Once the change is applied, the row goes in or the change is undone, even if the owner
      // stops meanwhile. A store that throws stored nothing, so no send or revert would reach it.
      withContext(NonCancellable) {
        attempt.started(rollbackOnFailure({ revert(payload) }) { store.insert(rowKey, payload) })
      }
      // Without waiting for the watcher, which may be pausing after a store failure
      drain(rowKey)
    }
  }

  override fun retry(id: EntryId): Operation =
    tracked(Ordering.Keyed(Row(this, id))) { attempt ->
      // Before the requeue, so the new attempt's outcome reaches this handle
      attempt.started(id)
      store.entry(id).first()?.let { row ->
        when (row.status) {
          is OutboxStatus.Failed ->
            store.update(
              row.copy(
                status = OutboxStatus.Queued,
                attempts = 0,
                lastError = null,
                retryAt = null,
              ),
              expected = row.status,
            )
          // Backing off: drop the deadline, then the timer
          OutboxStatus.Queued if row.retryAt != null -> {
            store.update(row.copy(retryAt = null), expected = OutboxStatus.Queued)
            cancelTimer(row.key, id)
          }
          else -> Unit
        }
        drain(row.key)
      }
    }

  override fun discard(id: EntryId): Operation =
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
        settleFollowers(id, OperationState.Cancelled)
        // A head that was backing off takes its timer along, so the next row sends now
        if (cancelTimer(row.key, id)) {
          drain(row.key)
        }
        // A throw fails this handle and reaches the failure handler. The row stays Discarded.
        if (!row.reverted) {
          revert(row.payload)
        }
      }
    }

  override fun operation(id: EntryId): Operation {
    val state = MutableStateFlow<OperationState>(OperationState.Queued)
    follow(id, state)
    return Operation.of(latched(state, { id }, store.entry(id).map { it.toOperationState() }))
  }

  /**
   * One drainer per key. Sends the oldest sendable row until none is left, the network drops or a
   * send fails. Repeated calls coalesce. A drain that throws, like on a store failure, is reported
   * and tried again after a pause, since no new row or reconnect may come to start another.
   */
  private fun drain(key: String): Operation =
    actions.run(Ordering.Keyed(Drain(this, key), OnPrevious.Supersede)) {
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
   * Sends [key]'s oldest sendable row until none is left, the key backs off or the network drops.
   */
  private suspend fun sendQueued(key: String) {
    while (online.value) {
      val head =
        store.entries().first().firstOrNull { it.key == key && it.status.isSendable } ?: break
      // The read suspends, so the network may have dropped meanwhile
      if (!online.value) {
        break
      }
      // A timer holds the key only while its row is still the head with the same deadline. One
      // left over from a row that was discarded or retried meanwhile is dropped. The timer only
      // wakes the key, and wall time can pass the deadline first, like while the device sleeps,
      // so the clock decides.
      val timer = waking.load()[key]
      if (timer != null && timer.job.isActive) {
        if (timer.waitsFor(head) && timer.deadline > clock.now()) {
          break
        }
        timer.job.cancel()
      }
      val retryAt = head.retryAt
      // Still backing off, perhaps since before a restart
      if (retryAt != null && retryAt > clock.now()) {
        backOff(key, head.id, retryAt)
        break
      }
      // Discarded first
      if (!store.update(head.copy(status = OutboxStatus.Sending), expected = head.status)) {
        continue
      }
      // So does the claim. A send started offline would spend an attempt for nothing.
      if (!online.value) {
        store.update(head.copy(status = OutboxStatus.Queued), expected = OutboxStatus.Sending)
        break
      }
      val failure = runCatchingCancellable { send(head.payload) }.exceptionOrNull()
      val attempts = head.attempts + 1
      when {
        failure == null ->
          if (
            store.update(head.copy(status = OutboxStatus.Sent), expected = OutboxStatus.Sending)
          ) {
            settleFollowers(head.id, OperationState.Succeeded)
          }
        // Poison pill: fail it, undo its local change, and let the key move on
        failure is PermanentFailure || attempts >= policy.maxAttempts -> {
          val status = OutboxStatus.Failed(failure.toString())
          // The row marked reverted promises an undo attempt, so the write and that attempt
          // finish together even if the owner stops meanwhile
          withContext(NonCancellable) {
            val failed =
              store.update(
                head.copy(status = status, attempts = attempts, reverted = true),
                expected = OutboxStatus.Sending,
              )
            if (failed) {
              settleFollowers(head.id, OperationState.Failed(PermanentFailure(status.cause)))
              // At most once per row. A throw ends this drain and reaches the failure handler.
              if (!head.reverted) {
                revert(head.payload)
              }
            }
          }
        }
        else -> {
          val retryAt = clock.now() + policy.delayFor(attempts)
          store.update(
            head.copy(
              status = OutboxStatus.Queued,
              attempts = attempts,
              lastError = failure.toString(),
              retryAt = retryAt,
            ),
            expected = OutboxStatus.Sending,
          )
          // The next pass checks that the row is still the head before the timer holds the key
          backOff(key, head.id, retryAt)
        }
      }
    }
  }

  /** Wakes [key]'s drain at [deadline], when the backoff of its head row [id] ends. */
  private fun backOff(key: String, id: EntryId, deadline: Instant) {
    val wait = deadline - clock.now()
    val timer = Timer(id, deadline, scope.launch(start = CoroutineStart.LAZY) { delay(wait) })
    waking.update { it.putting(key, timer) }
    timer.job.invokeOnCompletion { cause ->
      waking.update { current ->
        if (current[key] === timer) {
          current.removing(key)
        } else {
          current
        }
      }
      // Cancelled by retry(), by discard() or by a drain that found it stale or past its deadline,
      // and each of those drains itself
      if (cause == null) {
        drain(key)
      }
    }
    timer.job.start()
  }

  /** Cancels [key]'s timer if it waits for row [id]. A timer for another row keeps waiting. */
  private fun cancelTimer(key: String, id: EntryId): Boolean {
    val timer = waking.load()[key]?.takeIf { it.id == id } ?: return false
    timer.job.cancel()
    return true
  }

  /**
   * Queued until [write] reports its row through [Attempt.started], then a view over that row. A
   * command that settles Cancelled after its row was written still follows the row.
   */
  private fun tracked(ordering: Ordering, write: suspend (attempt: Attempt) -> Unit): Operation {
    val attempt = Attempt(MutableStateFlow(OperationState.Queued))
    val written = actions.run(ordering) { write(attempt) }
    val rowStates = flow {
      val done = written.await()
      if (attempt.id.isCompleted) {
        emitAll(store.entry(attempt.id.await()).map { it.toOperationState() })
      } else {
        // The write itself failed or was cancelled
        emit(done)
      }
    }
    return Operation.of(latched(attempt.state, { attempt.row }, rowStates))
  }

  /** Where a write reports its row, so the handle follows that row's current attempt. */
  private inner class Attempt(val state: MutableStateFlow<OperationState>) {
    val id = CompletableDeferred<EntryId>()
    @Volatile var row: EntryId? = null

    /** Follows [row] before anything can settle the attempt, then records it. */
    fun started(row: EntryId) {
      follow(row, state)
      this.row = row
      id.complete(row)
    }
  }

  /**
   * [state], following [rowStates] in the outbox's scope until the first terminal state, then
   * frozen. The attempt's outcome can settle it first, through [settleFollowers], when the view of
   * the row is behind. A retried row gets a new handle. When the scope stops first, the handle
   * settles Cancelled and the row stays for the next owner. When the store fails to report the row,
   * the handle settles Failed. Either way no waiter is left waiting.
   */
  private fun latched(
    state: MutableStateFlow<OperationState>,
    id: () -> EntryId?,
    rowStates: Flow<OperationState>,
  ): StateFlow<OperationState> {
    val collector = scope.launch {
      runCatchingCancellable {
        rowStates
          .transformWhile { next ->
            state.update { it.settledOr(next) }
            emit(Unit)
            state.value !is OperationState.Terminal
          }
          .collect()
      }
        .onFailure { cause -> state.update { it.settledOr(OperationState.Failed(cause)) } }
    }
    // Also runs when the scope had already stopped, so the collector never started
    collector.invokeOnCompletion {
      state.update { it.settledOr(OperationState.Cancelled) }
      id()?.let { unfollow(it, state) }
    }
    return state.asStateFlow()
  }

  private fun follow(id: EntryId, state: MutableStateFlow<OperationState>) {
    following.update { it.putting(id, (it[id] ?: persistentHashSetOf()).adding(state)) }
  }

  private fun unfollow(id: EntryId, state: MutableStateFlow<OperationState>) {
    following.update { current ->
      val rest = current[id]?.removing(state) ?: return@update current
      if (rest.isEmpty()) {
        current.removing(id)
      } else {
        current.putting(id, rest)
      }
    }
  }

  /**
   * Settles every handle following row [id]'s current attempt with its [outcome], and forgets them,
   * so a later attempt's handles start fresh.
   */
  private fun settleFollowers(id: EntryId, outcome: OperationState) {
    val settled = following.fetchAndUpdate { it.removing(id) }[id] ?: return
    settled.forEach { state -> state.update { it.settledOr(outcome) } }
  }

  // Keys this outbox owns on its DataActions. They include the instance, so two outboxes on one
  // runner never chain on each other.
  private data class Enqueue(val owner: RealOutbox<*>, val key: String)

  private data class Drain(val owner: RealOutbox<*>, val key: String)

  private data class Row(val owner: RealOutbox<*>, val id: EntryId)

  /** A backoff timer, and the head row and deadline it waits for. */
  private class Timer(val id: EntryId, val deadline: Instant, val job: Job) {
    /**
     * Whether [head] is still the row this timer waits for, with the same deadline. Stores may keep
     * the deadline in epoch milliseconds, so the deadlines compare at that precision.
     */
    fun waitsFor(head: OutboxEntry<*>): Boolean =
      head.id == id && head.retryAt?.toEpochMilliseconds() == deadline.toEpochMilliseconds()
  }
}

private const val WHOLE_QUEUE = ""

// How long the watcher, or a key whose drain failed, waits before reading a failing store again,
// doubling up to the cap
private val STORE_RETRY_FIRST = 1.seconds
private val STORE_RETRY_MAX = 1.minutes

private val OutboxStatus.isSendable: Boolean
  get() = this == OutboxStatus.Queued || this == OutboxStatus.Sending

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
