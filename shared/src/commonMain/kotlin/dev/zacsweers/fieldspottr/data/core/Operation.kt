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

/** Observing a handle never affects the work. Dropping it never cancels the work. */
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
        of(AllState(operations.map { it.state }))
      }
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
