// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestOperation
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test

/** Moves one [TestOperation] from two threads at once, which a common test cannot do. */
class TestOperationThreadsTest {
  private val threads: ExecutorService = Executors.newFixedThreadPool(2)

  @AfterTest
  fun stopThreads() {
    threads.shutdownNow()
  }

  @Test
  fun `exactly one of two threads settles a test operation`() {
    repeat(ROUNDS) {
      val handle = TestOperation()
      val settled = together({ handle.succeed() }, { handle.cancel() })

      assertThat(settled.count { it.isSuccess }).isEqualTo(1)
    }
  }

  @Test
  fun `start never replaces a state another thread settled`() {
    repeat(ROUNDS) {
      val handle = TestOperation()
      together({ handle.start() }, { handle.cancel() })

      assertThat(handle.state.value).isEqualTo(OperationState.Cancelled)
    }
  }

  /** Runs [first] and [second] on two threads released at the same moment. */
  private fun together(first: () -> Unit, second: () -> Unit): List<Result<Unit>> {
    val barrier = CyclicBarrier(2)
    return listOf(first, second)
      .map { move ->
        threads.submit(
          Callable {
            barrier.await()
            runCatching(move)
          }
        )
      }
      .map { it.get(5, TimeUnit.SECONDS) }
  }

  private companion object {
    const val ROUNDS = 1_024
  }
}
