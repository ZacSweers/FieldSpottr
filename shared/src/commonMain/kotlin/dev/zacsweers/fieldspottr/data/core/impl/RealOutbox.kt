// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.ActionRunner
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
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
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
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
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Outbox] over a store's rows, with one drainer per key on its runner.
 *
 * A change costs the outbox one row per key with a waiting row: its watcher follows the store's
 * heads, and a drain reads only its key's head. Handles hold no store subscription of their own.
 * Each reads its row once after it starts following, and then the drain settles it with the
 * attempt's outcome. A change the outbox did not make, like a discard by an optimistic store's
 * clear, reaches a handle through a sweep of its key. A sweep runs when the key's head changes and
 * reads the key's followed rows up to the first one still waiting, so a handle on a row behind a
 * waiting head hears of such a change once the key moves past the head.
 *
 * Every handle settles one of these ways: its first read finds the row settled or gone, the drain
 * settles the attempt it follows, [discard] settles the row's handles Cancelled, a sweep finds its
 * row settled, or the scope stops. A failed store read never settles a handle. It is reported and
 * read again later.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealOutbox<E>(
  /** Names this outbox in the failures it reports. */
  private val name: String,
  /** Runs the watchers, the handles' first reads and the backoff timers. */
  private val scope: CoroutineScope,
  private val actions: ActionRunner,
  private val send: suspend (E, EntryId) -> Unit,
  private val revert: suspend (E) -> Unit,
  private val store: OutboxStore<E>,
  /**
   * Decides whether a failed send retries and how long its row waits. A throw from it is reported,
   * and the row waits [POLICY_FAILURE_DELAY], so a broken policy never drops a change.
   */
  private val policy: RetryPolicy,
  private val online: StateFlow<Boolean>,
  /** Backoff deadlines outlive the process, so they use wall time. */
  private val clock: Clock,
  /**
   * Hears a store's failures to report its rows, rows that fail for good and rows that cannot be
   * decoded, which no command's handle would carry.
   */
  private val onFailure: (Throwable) -> Unit,
  /**
   * Counts this outbox into the factory's backlog and returns where it reports what the rows still
   * have to send, on every change of the heads and every network change.
   */
  trackBacklog: () -> (OutboxBacklog) -> Unit,
) : Outbox<E> {
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt. A timer that
  // fired stays until a drain takes its row, so the drain knows the wait is over.
  private val waking = AtomicReference(persistentMapOf<String, Timer>())
  // The unsettled handles following each row's current attempt, by row and by key, so the
  // attempt's outcome reaches them and a sweep finds them. Updated by compare-and-set only, and
  // nothing observes it, so it needs no StateFlow.
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
  // Keys whose sweep failed to read a row, with the pause before the next sweep and whether one is
  // scheduled. Cleared by a sweep that reads every row it needs. Updated by compare-and-set only.
  private val resweeps = AtomicReference(persistentHashMapOf<String, Resweep>())
  // How long each key whose drain failed waits before its next try, doubling while it keeps
  // failing. Cleared by a drain that finishes. Updated by compare-and-set only.
  private val failing = AtomicReference(persistentMapOf<String, Duration>())
  // The policy's bound on a wait, read once. A policy that throws here is reported and unbounded.
  private val maxWait: Duration =
    try {
      policy.maxDelay.coerceAtLeast(Duration.ZERO)
    } catch (e: Exception) {
      onFailure(e)
      Duration.INFINITE
    }

  override val entries: Flow<List<OutboxEntry<E>>> = store.entries()
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
    // The watcher and drains read only heads, which are Queued or Sending. One read of every row
    // lets the store meet each stored row once, so it hands on a Failed row that does not decode
    // too. A failed read is reported and stops nothing.
    scope.launch { runCatchingCancellable { store.entries().first() }.onFailure(onFailure) }
    // Completes when the scope stops, also when it had stopped before this outbox was built
    scope.launch { awaitCancellation() }.invokeOnCompletion { stop() }
  }

  /**
   * Reports the backlog on every change of the heads and every network change, starts a drain for
   * each key with a head while online, and sweeps each key whose head changed. A store that fails
   * to report its heads is reported and watched again after a pause, or at once on reconnect, so
   * rows already stored still send.
   */
  private suspend fun watch() {
    var pause = STORE_RETRY_FIRST
    // The heads of the last pass, to find the keys whose head changed
    var last = emptyMap<String, OutboxEntry<E>>()
    var wasUp = false
    while (true) {
      runCatchingCancellable {
          combine(online, store.heads()) { up, heads -> up to heads }
            .collect { (up, heads) ->
              pause = STORE_RETRY_FIRST
              // Offline too, so a platform can schedule a drain for when the network is back
              onBacklog(backlogOf(heads))
              val current = heads.associateBy { it.key }
              if (up) {
                current.keys.forEach(::drain)
                // A timer that fired while offline left its drain undone, and its row may be gone
                if (!wasUp) {
                  (waking.load().keys - current.keys).forEach(::drain)
                }
              }
              wasUp = up
              val moved = (last.keys + current.keys).filter { last[it] != current[it] }
              last = current
              moved.forEach { sweep(it) }
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

  override fun enqueue(payload: E, key: String?, apply: suspend () -> Unit): OutboxOperation {
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(Ordering.Keyed(Enqueue(this, rowKey))) { attempt ->
      apply()
      // Once the change is applied, the row goes in or the change is undone, even if the owner
      // stops meanwhile. A store that throws stored nothing, so no send or revert would reach it.
      withContext(NonCancellable) {
        val id = rollbackOnFailure({ revert(payload) }) { store.insert(rowKey, payload) }
        attempt.started(id, rowKey)
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
  override fun retry(id: EntryId): Operation =
    tracked(Ordering.Keyed(Row(this, id))) { attempt ->
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
      attempt.started(id, row?.key)
      row?.let { drain(it.key) }
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
        forget(id)
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
    follow(id, key = null, state)
    val handle =
      latched(state, { id }) {
        val row = readRow(id)
        if (row == null) {
          OperationState.Cancelled
        } else {
          // Now that the key is known, sweeps of that key find this handle. A change between the
          // read and this could have been swept before, so the row is read again after it.
          follows.update { it.placing(id, row.key) }
          stateOf(id, readRow(id))
        }
      }
    return Operation.of(handle)
  }

  /**
   * One drainer per key. Sends the key's head until none is left, the network drops or a send
   * fails. Repeated calls coalesce. A drain that throws, like on a store failure, is reported and
   * tried again after a pause, since no new row or reconnect may come to start another.
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

  /** Sends [key]'s head until none is left, the key backs off or the network drops. */
  private suspend fun sendQueued(key: String) {
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
      val failure = runCatchingCancellable { send(head.payload, head.id) }.exceptionOrNull()
      val attempts = head.attempts + 1
      if (failure == null) {
        onRow(head.id) {
          val sent =
            store.update(head.copy(status = OutboxStatus.Sent), expected = OutboxStatus.Sending)
          if (sent) {
            settleFollowers(head.id, OperationState.Succeeded)
          }
        }
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
   * Fails [head] for good after [failure] ended its [attempts]th attempt. Handles that follow the
   * attempt settle with [failure] itself when it is a [PermanentFailure], or else with a
   * [PermanentFailure] whose cause is [failure], so a caller can branch on its type. The failure
   * handler hears [failure] once. Then the optimistic change is undone, at most once per row.
   */
  private suspend fun fail(head: OutboxEntry<E>, failure: Throwable, attempts: Int) {
    val status = OutboxStatus.Failed(failure.toString())
    val outcome = OperationState.Failed(failure as? PermanentFailure ?: PermanentFailure(failure))
    // The row marked reverted promises an undo attempt, so the write and that attempt finish
    // together even if the owner stops meanwhile. A retry of the row waits for both.
    withContext(NonCancellable) {
      onRow(head.id) {
        withContext(NonCancellable) {
          // Before the write, so a handle that reads the Failed row before it settles gets this
          remember(head.id, FailedRow(status, outcome))
          val failed =
            store.update(
              head.copy(status = status, attempts = attempts, reverted = true),
              expected = OutboxStatus.Sending,
            )
          if (failed) {
            settleFollowers(head.id, outcome)
            try {
              // A throw ends this drain and reaches the failure handler too
              if (!head.reverted) {
                revert(head.payload)
              }
            } finally {
              onFailure(failure)
            }
          } else {
            forget(head.id)
          }
        }
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
   * Queued until [write] reports its row through [Attempt.started], then the outcome of that row's
   * current attempt. A command that settles Cancelled after its row was written still follows the
   * row. The handle's `stored` succeeds as soon as the row is reported, and otherwise ends as the
   * write did.
   */
  private fun tracked(
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
          val id = attempt.id.await()
          stateOf(id, readRow(id))
        } else {
          // The write itself failed or was cancelled
          done
        }
      }
    return RowOperation(state, Operation.of(attempt.stored.asStateFlow()))
  }

  /** Where a write reports its row, so the handle follows that row's current attempt. */
  private inner class Attempt(val state: MutableStateFlow<OperationState>) {
    val id = CompletableDeferred<EntryId>()
    @Volatile var row: EntryId? = null
    // Whether the row is in the store, for the handle's stored milestone
    val stored = MutableStateFlow<OperationState>(OperationState.Queued)

    /** Follows [row] on [key] before anything can settle the attempt, then records it. */
    fun started(row: EntryId, key: String?) {
      follow(row, key, state)
      this.row = row
      id.complete(row)
      stored.value = OperationState.Succeeded
    }
  }

  /** A handle over a row's current attempt, with [stored] over the write that put the row in. */
  private class RowOperation(
    override val state: StateFlow<OperationState>,
    override val stored: Operation,
  ) : OutboxOperation

  /**
   * [state], after one read of its row by [firstRead] in the outbox's scope. From then on the drain
   * settles it through [settleFollowers], and a sweep settles it when something else changed the
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
   * Settles the handles following [key]'s rows whose rows something else settled, like a discard by
   * an optimistic store's clear or a logout's clearAll. It reads the rows in the order they were
   * followed and stops at the first one still waiting, so each pass reads one waiting row, and each
   * settled row is read until its handles settle. A row settles on its row key, where [retry] runs,
   * so a retry never lands between the read and the settle.
   *
   * A read that throws settles nothing. It is reported, and the key is swept again after a pause
   * that doubles while reads keep failing, so the handles settle once reads work even when the
   * key's head never changes again.
   */
  private suspend fun sweep(key: String) {
    while (true) {
      val id = follows.load().byKey[key]?.firstOrNull()
      if (id == null) {
        sweptAll(key)
        return
      }
      val state = readState(id)
      if (state == null) {
        sweepLater(key)
        return
      }
      if (state !is OperationState.Terminal) {
        showFollowers(id, state)
        sweptAll(key)
        return
      }
      val settled =
        onRow(id) {
          when (val again = readState(id)) {
            null -> null
            is OperationState.Terminal -> {
              settleFollowers(id, again)
              true
            }
            else -> {
              showFollowers(id, again)
              false
            }
          }
        }
      when (settled) {
        null -> {
          sweepLater(key)
          return
        }
        false -> {
          sweptAll(key)
          return
        }
        true -> Unit
      }
    }
  }

  /** Schedules one more sweep of [key] unless one is already due, after a doubling pause. */
  private fun sweepLater(key: String) {
    val before = resweeps.fetchAndUpdate { current ->
      val last = current[key]
      if (last?.due == true) {
        current
      } else {
        val pause =
          if (last == null) {
            STORE_RETRY_FIRST
          } else {
            minOf(last.pause * 2, STORE_RETRY_MAX)
          }
        current.putting(key, Resweep(pause, due = true))
      }
    }
    if (before[key]?.due == true) {
      return
    }
    val pause = resweeps.load()[key]?.pause ?: STORE_RETRY_FIRST
    scope.launch {
      delay(pause)
      resweeps.update { current ->
        current[key]?.let { current.putting(key, Resweep(it.pause, due = false)) } ?: current
      }
      sweep(key)
    }
  }

  /** Clears [key]'s pause after a sweep that read every row it needed, unless a sweep is due. */
  private fun sweptAll(key: String) {
    resweeps.update { current ->
      if (current[key]?.due == false) {
        current.removing(key)
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

  /**
   * Follows row [id] on [key] with [state]. A null key leaves the row out of sweeps until
   * [Follows.placing] adds it. After the scope stopped, [state] settles Cancelled at once.
   */
  private fun follow(id: EntryId, key: String?, state: MutableStateFlow<OperationState>) {
    follows.update { it.following(id, key, state) }
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
    follows.load().byRow[id]?.states?.forEach { follower ->
      follower.update { it.settledOr(state) }
    }
  }

  /**
   * Settles every handle following row [id]'s current attempt with its [outcome], and forgets them,
   * so a later attempt's handles start fresh.
   */
  private fun settleFollowers(id: EntryId, outcome: OperationState) {
    val settled = follows.fetchAndUpdate { it.forgetting(id) }.byRow[id] ?: return
    settled.states.forEach { state -> state.update { it.settledOr(outcome) } }
  }

  /**
   * Settles every handle Cancelled once the scope stops, and stops hearing of rows the store cannot
   * decode. The rows stay for the next owner, which hears of the rows the store meets after this.
   */
  private fun stop() {
    stopped.store(true)
    val all = follows.exchange(Follows())
    for (followers in all.byRow.values) {
      followers.states.forEach { state -> state.update { it.settledOr(OperationState.Cancelled) } }
    }
    try {
      undecodable.close()
    } catch (e: Exception) {
      onFailure(e)
    }
  }

  // Keys this outbox owns on its ActionRunner. They include the instance, so two outboxes on one
  // runner never chain on each other.
  private data class Enqueue(val owner: RealOutbox<*>, val key: String)

  private data class Drain(val owner: RealOutbox<*>, val key: String)

  private data class Row(val owner: RealOutbox<*>, val id: EntryId)

  /** A row's Failed [status] as written, and the outcome with the exception send threw. */
  private class FailedRow(val status: OutboxStatus.Failed, val outcome: OperationState.Failed)

  /** A key's sweep pause, and whether a sweep after it is scheduled. */
  private class Resweep(val pause: Duration, val due: Boolean)

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

  /** The handles following one row's current attempt, and the row's key once known. */
  private class Followers(
    val key: String?,
    val states: PersistentSet<MutableStateFlow<OperationState>>,
  )

  /**
   * Every handle that follows a row, by row in [byRow], and the followed rows of each key in
   * [byKey], in the order they were first followed. For rows this outbox inserted, that is their
   * send order, which lets a sweep stop at the first row still waiting.
   */
  private class Follows(
    val byRow: PersistentMap<EntryId, Followers> = persistentHashMapOf(),
    val byKey: PersistentMap<String, PersistentSet<EntryId>> = persistentHashMapOf(),
  ) {
    fun following(id: EntryId, key: String?, state: MutableStateFlow<OperationState>): Follows {
      val current = byRow[id]
      val rowKey = key ?: current?.key
      val states = (current?.states ?: persistentHashSetOf()).adding(state)
      return Follows(byRow.putting(id, Followers(rowKey, states)), byKey.listing(rowKey, id))
    }

    /** Records [key] for row [id] if it is still followed, so sweeps of [key] find it. */
    fun placing(id: EntryId, key: String): Follows {
      val current = byRow[id] ?: return this
      if (current.key == key) {
        return this
      }
      return Follows(byRow.putting(id, Followers(key, current.states)), byKey.listing(key, id))
    }

    fun unfollowing(id: EntryId, state: MutableStateFlow<OperationState>): Follows {
      val current = byRow[id] ?: return this
      val rest = current.states.removing(state)
      return if (rest.isEmpty()) {
        forgetting(id)
      } else {
        Follows(byRow.putting(id, Followers(current.key, rest)), byKey)
      }
    }

    fun forgetting(id: EntryId): Follows {
      val current = byRow[id] ?: return this
      val key = current.key ?: return Follows(byRow.removing(id), byKey)
      val rest = byKey[key]?.removing(id)
      return Follows(
        byRow.removing(id),
        if (rest.isNullOrEmpty()) {
          byKey.removing(key)
        } else {
          byKey.putting(key, rest)
        },
      )
    }

    private fun PersistentMap<String, PersistentSet<EntryId>>.listing(
      key: String?,
      id: EntryId,
    ): PersistentMap<String, PersistentSet<EntryId>> =
      if (key == null) {
        this
      } else {
        // An ordered set, so the first id is the one followed first
        putting(key, (this[key] ?: persistentSetOf()).adding(id))
      }
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
  get() = (this as RealOutbox<*>).timersHeld

/** How many rows an outbox's handles follow. For tests, which cannot see a leak otherwise. */
internal val Outbox<*>.rowsFollowed: Int
  get() = (this as RealOutbox<*>).rowsFollowed
