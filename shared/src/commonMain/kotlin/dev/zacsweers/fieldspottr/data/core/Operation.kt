// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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

  @OptIn(DelicateDataLayerApi::class)
  companion object {
    /**
     * A read-only handle over a state flow. The runner, [DataQuery] and the outbox build theirs
     * here.
     */
    internal fun of(source: StateFlow<OperationState>): Operation =
      object : Operation {
        override val state = source
      }

    /** Already succeeded. What a call returns when there is nothing to do. */
    val Done: Operation = of(MutableStateFlow(OperationState.Succeeded).asStateFlow())

    /** One handle over [operations]. Settles once all of them have: failed if any failed. */
    fun all(operations: Collection<Operation>): Operation =
      if (operations.isEmpty()) {
        Done
      } else {
        AllOperation(operations.toList())
      }
  }
}

/** [Operation.all]'s handle. It keeps its parts, so awaiting it can check each one. */
@OptIn(DelicateDataLayerApi::class)
internal class AllOperation(val parts: List<Operation>) : Operation {
  override val state: StateFlow<OperationState> = AllState(parts.map { it.state })

  override suspend fun await(): OperationState.Terminal {
    requireNotWaitingOnItself()
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
