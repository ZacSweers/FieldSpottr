// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

/** Where a handle's work stands. A [Terminal] state never changes once reached. */
sealed interface OperationState {
  /** Submitted and waiting for its turn. */
  data object Queued : OperationState

  /** Started and not finished yet. */
  data object Running : OperationState

  /** A state the work ends in. [Operation.await] returns one of these. */
  sealed interface Terminal : OperationState

  /** The work returned normally. */
  data object Succeeded : Terminal

  /** The work threw [cause]. */
  data class Failed(val cause: Throwable) : Terminal

  /** The work was cancelled, superseded or discarded, so its outcome is unknown. */
  data object Cancelled : Terminal
}

/**
 * A handle on one unit of work, like a command submitted to [ActionRunner] or a query's fetch.
 * [state] says where the work stands, and [await] suspends until it settles. Observing the handle
 * changes nothing, and dropping it never cancels the work.
 *
 * A handle settles as soon as its work returns, like right after a store write. The store's
 * collectors may see the new value a moment later. So a screen derives optimistic state from the
 * data it observes, or keeps it in the store, and does not clear it when the handle settles.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface Operation {
  /** Where the work stands now. It settles once, in a terminal state, and then never changes. */
  val state: StateFlow<OperationState>

  /**
   * Suspends until the work settles and returns how it ended. It returns a state instead of
   * throwing, so callers branch with `when` and cannot forget the failure case. Called from inside
   * a command, it throws [IllegalStateException] at once when the handle is that command's own, or
   * belongs to a command queued behind it on the same key, since that wait would never end.
   */
  suspend fun await(): OperationState.Terminal =
    state.filterIsInstance<OperationState.Terminal>().first()

  companion object {
    /** Already succeeded. What a call returns when there is nothing to do. */
    val succeeded: Operation = SettledOperation(OperationState.Succeeded)

    /**
     * Already cancelled. What a call returns when it drops its work before starting, like a request
     * that an earlier one already covers.
     */
    val cancelled: Operation = SettledOperation(OperationState.Cancelled)

    /**
     * Already failed with [cause]. What a call returns when it rejects its input before any work
     * starts. No failure handler hears about it, so the caller reports [cause] if it should be
     * logged.
     */
    fun failed(cause: Throwable): Operation = SettledOperation(OperationState.Failed(cause))

    /** One handle over [operations]. Settles once all of them have: failed if any failed. */
    fun all(operations: Collection<Operation>): Operation =
      if (operations.isEmpty()) {
        succeeded
      } else {
        AllOperation(operations.toList())
      }

    /** [all] over handles given one by one. */
    fun all(vararg operations: Operation): Operation = all(operations.asList())
  }
}

/**
 * A handle on a command from [ActionRunner.run] or [ConflatedActionRunner.run], which its holder
 * can also cancel. Other handles, like a query's fetch or an outbox send, stay plain [Operation]s,
 * since other callers may share the work they follow.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface CancellableOperation : Operation {
  /**
   * Queued: the command never starts. Running: its block is cancelled. Settled: nothing happens.
   * Returns at once. [await] returns after the block has unwound.
   *
   * A cancelled command settles [OperationState.Cancelled], no failure handler hears about it, and
   * the next command on its line or key runs. It is safe to call from any thread, and more than
   * once.
   */
  fun cancel()
}

/**
 * A view of this handle that follows the same work and does not implement [CancellableOperation],
 * so no cast reaches `cancel`.
 */
internal fun Operation.withoutCancel(): Operation = PlainOperation(this)

/** [withoutCancel]'s view. It forwards [await], so the source's own checks still run. */
@OptIn(DelicateDataLayerApi::class)
private class PlainOperation(private val source: Operation) : Operation {
  override val state: StateFlow<OperationState>
    get() = source.state

  override suspend fun await(): OperationState.Terminal = source.await()
}

/**
 * [Operation.await], throwing the cause when the work failed. For a command that composes other
 * handles and should fail when one of them does, like a sync over [Operation.all] of its fetches. A
 * cancelled handle is returned, since its outcome is unknown and throwing a cancellation from here
 * would cancel the caller.
 */
suspend fun Operation.awaitOrThrow(): OperationState.Terminal {
  val state = await()
  if (state is OperationState.Failed) {
    throw state.cause
  }
  return state
}

/** A handle whose work ended before anyone got it. Its state never changes. */
@OptIn(DelicateDataLayerApi::class)
private class SettledOperation(terminal: OperationState.Terminal) : Operation {
  override val state: StateFlow<OperationState> = MutableStateFlow(terminal).asStateFlow()
}

/**
 * [Operation.all]'s handle. Awaiting it awaits each part in a child coroutine. A child keeps the
 * caller's context, so a part whose own await checks who is waiting still sees the caller, and a
 * part that could never finish fails the whole await at once.
 */
@OptIn(DelicateDataLayerApi::class)
private class AllOperation(private val parts: List<Operation>) : Operation {
  override val state: StateFlow<OperationState> = AllState(parts.map { it.state })

  override suspend fun await(): OperationState.Terminal =
    combinedTerminal(coroutineScope { parts.map { async { it.await() } }.awaitAll() })
}

/**
 * A state derived from [sources] on demand, so a combined handle needs no scope of its own. Each
 * source stops changing once terminal, so the combined state does too.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class AllState(private val sources: List<StateFlow<OperationState>>) :
  StateFlow<OperationState> {
  override val value: OperationState
    get() = combinedState(sources.map { it.value })

  override val replayCache: List<OperationState>
    get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<OperationState>): Nothing {
    combine(sources) { combinedState(it.asList()) }.distinctUntilChanged().collect(collector)
    // State flows never complete, so neither does the combination
    awaitCancellation()
  }
}

private fun combinedState(states: List<OperationState>): OperationState =
  when {
    states.all { it is OperationState.Terminal } ->
      combinedTerminal(states.filterIsInstance<OperationState.Terminal>())
    states.any { it == OperationState.Running } -> OperationState.Running
    else -> OperationState.Queued
  }

/** The first failure in input order, else cancelled if any part was, else succeeded. */
private fun combinedTerminal(states: List<OperationState.Terminal>): OperationState.Terminal =
  states.firstOrNull { it is OperationState.Failed }
    ?: states.firstOrNull { it == OperationState.Cancelled }
    ?: OperationState.Succeeded
