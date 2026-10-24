// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.collections.immutable.persistentListOf

/**
 * Records the failures a failure handler receives, for a test to assert on. Pass it where a handler
 * goes, like `onFailure = failures`. It records from any thread, since stores and fetches can fail
 * on an I/O dispatcher.
 */
@OptIn(ExperimentalAtomicApi::class)
class TestFailures : (Throwable) -> Unit {
  // Compare-and-set only. Nothing observes it.
  private val recorded = AtomicReference(persistentListOf<Throwable>())

  /** Every failure so far, oldest first. */
  val all: List<Throwable>
    get() = recorded.load()

  override fun invoke(failure: Throwable) {
    recorded.update { it.adding(failure) }
  }

  /** The failures so far, which this then forgets, so a later assertion sees only new ones. */
  fun take(): List<Throwable> = recorded.exchange(persistentListOf())

  /** Fails the test if anything failed, with the first failure as the cause. */
  fun assertNone() {
    val current = all
    if (current.isNotEmpty()) {
      fail("Expected no failures, but got ${current.size}: $current", current.first())
    }
  }

  /** Fails the test unless exactly one failure was recorded and it is a [T]. Returns it. */
  inline fun <reified T : Throwable> assertSingle(): T {
    val current = all
    if (current.size != 1) {
      fail("Expected one failure, but got ${current.size}: $current", current.firstOrNull())
    }
    return assertIs<T>(current.single())
  }
}
