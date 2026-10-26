// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A handle the test settles by hand, for a fake that returns [Operation], like a repository fake
 * that a presenter test drives. It starts Queued. [start] moves it to Running and is optional.
 * Settling it twice throws, since that means the fake is wrong. Each move is one compare-and-set,
 * so when two threads settle it at once, exactly one wins and the other throws.
 */
@OptIn(DelicateDataLayerApi::class)
class TestOperation : Operation {

  override val state: StateFlow<OperationState>
    field = MutableStateFlow<OperationState>(OperationState.Queued)

  /** Moves a queued handle to Running. */
  fun start() {
    check(state.compareAndSet(OperationState.Queued, OperationState.Running)) {
      "start() needs a queued handle, but it is ${state.value}"
    }
  }

  fun succeed() {
    settle(OperationState.Succeeded)
  }

  fun fail(cause: Throwable) {
    settle(OperationState.Failed(cause))
  }

  fun cancel() {
    settle(OperationState.Cancelled)
  }

  private fun settle(terminal: OperationState.Terminal) {
    while (true) {
      val current = state.value
      check(current !is OperationState.Terminal) { "Already settled as $current" }
      if (state.compareAndSet(current, terminal)) {
        return
      }
    }
  }
}
