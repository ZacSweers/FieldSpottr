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

sealed interface OperationState {
  data object Queued : OperationState

  data object Running : OperationState

  sealed interface Terminal : OperationState

  data object Succeeded : Terminal

  data class Failed(val cause: Throwable) : Terminal

  data object Cancelled : Terminal
}

/**
 * Observing a handle never affects the work. Dropping it never cancels the work.
 *
 * A handle settles when its work returns, such as right after a store write. Collectors of that
 * store see the new value on their next dispatch, which can come later. So clear optimistic state
 * from the data a screen observes, or keep that state in the store, instead of clearing it when the
 * handle settles.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface Operation {
  val state: StateFlow<OperationState>

  suspend fun await(): OperationState.Terminal =
    state.filterIsInstance<OperationState.Terminal>().first()

  companion object {
    /** Already succeeded. What a call returns when there is nothing to do. */
    val Done: Operation = SettledOperation(OperationState.Succeeded)

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
        Done
      } else {
        AllOperation(operations.toList())
      }
  }
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

  override suspend fun await(): OperationState.Terminal {
    coroutineScope { parts.map { async { it.await() } }.awaitAll() }
    return super.await()
  }
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
      states.firstOrNull { it is OperationState.Failed }
        ?: states.firstOrNull { it == OperationState.Cancelled }
        ?: OperationState.Succeeded
    states.any { it == OperationState.Running } -> OperationState.Running
    else -> OperationState.Queued
  }
