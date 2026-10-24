// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** The handles the core hands out already settled. */
class OperationTest {

  @Test
  fun `a failed handle is already failed with its cause`() = runTest {
    val cause = IllegalArgumentException("bad input")
    val handle = Operation.failed(cause)

    assertThat(handle.state.value).isEqualTo(OperationState.Failed(cause))
    assertThat(handle.await()).isEqualTo(OperationState.Failed(cause))
  }

  @Test
  fun `a cancelled handle is already cancelled`() = runTest {
    assertThat(Operation.cancelled.state.value).isEqualTo(OperationState.Cancelled)
    assertThat(Operation.cancelled.await()).isEqualTo(OperationState.Cancelled)
  }

  @Test
  fun `all over a failed handle fails`() = runTest {
    val cause = IllegalStateException("rejected")
    val both = Operation.all(listOf(Operation.Done, Operation.failed(cause)))

    assertThat(both.state.value).isEqualTo(OperationState.Failed(cause))
    assertThat(both.await()).isEqualTo(OperationState.Failed(cause))
  }
}
