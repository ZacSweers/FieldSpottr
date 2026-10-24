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
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

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
) : Outbox<E> {
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt.
  private val waking = AtomicReference(persistentMapOf<String, Job>())

  override val entries: Flow<List<OutboxEntry<E>>> = store.entries()

  init {
    scope.launch {
      combine(online, store.entries()) { up, rows ->
          if (up) {
            rows.filter { it.status.isSendable }.mapTo(HashSet()) { it.key }
          } else {
            emptySet()
          }
        }
        .collect { keys -> keys.forEach(::drain) }
    }
  }

  override fun enqueue(payload: E, key: String?, apply: suspend () -> Unit): Operation {
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(Ordering.Keyed(Enqueue(this, rowKey))) {
      apply()
      store.insert(rowKey, payload)
    }
  }

  override fun retry(id: EntryId): Operation =
    tracked(Ordering.Keyed(Row(this, id))) {
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
            waking.load()[row.key]?.cancel()
          }
          else -> Unit
        }
        drain(row.key)
      }
      id
    }

  override fun discard(id: EntryId): Operation =
    actions.run(Ordering.Keyed(Row(this, id))) {
      val row = checkNotNull(store.entry(id).first()) { "$id is gone" }
      val pending = row.status == OutboxStatus.Queued || row.status is OutboxStatus.Failed
      val discarded =
        pending &&
          store.update(
            row.copy(status = OutboxStatus.Discarded, reverted = true),
            expected = row.status,
          )
      check(discarded) { "$id is no longer pending" }
      // A throw fails this handle and reaches the failure handler. The row stays Discarded.
      if (!row.reverted) {
        revert(row.payload)
      }
    }

  override fun operation(id: EntryId): Operation =
    Operation.of(store.entry(id).map { it.toOperationState() }.latched())

  /**
   * One drainer per key. Sends the oldest sendable row until none is left, the network drops or a
   * send fails. Repeated calls coalesce.
   */
  private fun drain(key: String): Operation =
    actions.run(Ordering.Keyed(Drain(this, key), OnPrevious.Supersede)) {
      while (online.value && waking.load()[key]?.isActive != true) {
        val head =
          store.entries().first().firstOrNull { it.key == key && it.status.isSendable } ?: break
        val remaining = head.retryAt?.let { it - clock.now() }
        // Still backing off, perhaps since before a restart
        if (remaining != null && remaining.isPositive()) {
          backOff(key, remaining)
          break
        }
        // Discarded first
        if (!store.update(head.copy(status = OutboxStatus.Sending), expected = head.status)) {
          continue
        }
        val failure = runCatchingCancellable { send(head.payload) }.exceptionOrNull()
        val attempts = head.attempts + 1
        when {
          failure == null ->
            store.update(head.copy(status = OutboxStatus.Sent), expected = OutboxStatus.Sending)
          // Poison pill: fail it, undo its local change, and let the key move on
          failure is PermanentFailure || attempts >= policy.maxAttempts -> {
            store.update(
              head.copy(
                status = OutboxStatus.Failed(failure.toString()),
                attempts = attempts,
                reverted = true,
              ),
              expected = OutboxStatus.Sending,
            )
            // At most once per row. A throw ends this drain and reaches the failure handler.
            if (!head.reverted) {
              revert(head.payload)
            }
          }
          else -> {
            val backoff = policy.delayFor(attempts)
            store.update(
              head.copy(
                status = OutboxStatus.Queued,
                attempts = attempts,
                lastError = failure.toString(),
                retryAt = clock.now() + backoff,
              ),
              expected = OutboxStatus.Sending,
            )
            backOff(key, backoff)
          }
        }
      }
    }

  private fun backOff(key: String, wait: Duration) {
    val timer = scope.launch(start = CoroutineStart.LAZY) { delay(wait) }
    waking.update { it.putting(key, timer) }
    timer.invokeOnCompletion { cause ->
      waking.update { current ->
        if (current[key] === timer) {
          current.removing(key)
        } else {
          current
        }
      }
      // Cancelled by retry(), which drains itself
      if (cause == null) {
        drain(key)
      }
    }
    timer.start()
  }

  /** Queued until [write] lands, then a view over the row whose id it returns. */
  private fun tracked(ordering: Ordering, write: suspend () -> EntryId): Operation {
    val id = CompletableDeferred<EntryId>()
    val written = actions.run(ordering) { id.complete(write()) }
    return Operation.of(
      flow {
        when (val done = written.await()) {
          OperationState.Succeeded -> emitAll(store.entry(id.await()).map { it.toOperationState() })
          // The write itself failed or was cancelled
          else -> emit(done)
        }
      }
        .latched()
    )
  }

  /**
   * Collected in the outbox's scope until the first terminal state, then frozen. A retried row gets
   * a new handle.
   */
  private fun Flow<OperationState>.latched(): StateFlow<OperationState> = transformWhile {
    emit(it)
    it !is OperationState.Terminal
  }
    .stateIn(scope, SharingStarted.Eagerly, OperationState.Queued)

  // Keys this outbox owns on its DataActions. They include the instance, so two outboxes on one
  // runner never chain on each other.
  private data class Enqueue(val owner: RealOutbox<*>, val key: String)

  private data class Drain(val owner: RealOutbox<*>, val key: String)

  private data class Row(val owner: RealOutbox<*>, val id: EntryId)
}

private const val WHOLE_QUEUE = ""

private val OutboxStatus.isSendable: Boolean
  get() = this == OutboxStatus.Queued || this == OutboxStatus.Sending

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
