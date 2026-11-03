// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
    val both = Operation.all(listOf(Operation.succeeded, Operation.failed(cause)))

    assertThat(both.state.value).isEqualTo(OperationState.Failed(cause))
    assertThat(both.await()).isEqualTo(OperationState.Failed(cause))
  }

  @Test
  fun `all takes handles one by one`() = runTest {
    assertThat(Operation.all().await()).isEqualTo(OperationState.Succeeded)
    assertThat(Operation.all(Operation.succeeded, Operation.cancelled).await())
      .isEqualTo(OperationState.Cancelled)
  }

  @Test
  fun `awaitOrThrow throws a failure's cause and returns the other outcomes`() = runTest {
    val cause = IllegalStateException("rejected")

    val thrown = assertFailsWith<IllegalStateException> { Operation.failed(cause).awaitOrThrow() }

    assertThat(thrown).isSameInstanceAs(cause)
    assertThat(Operation.succeeded.awaitOrThrow()).isEqualTo(OperationState.Succeeded)
    assertThat(Operation.cancelled.awaitOrThrow()).isEqualTo(OperationState.Cancelled)
  }
}
