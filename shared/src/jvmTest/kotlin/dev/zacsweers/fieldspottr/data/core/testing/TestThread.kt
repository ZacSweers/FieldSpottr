// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import kotlin.concurrent.thread

/**
 * Runs [block] on a new thread and keeps what it throws, so [joinOrFail] can fail the test with it.
 * A plain thread that throws only prints its stack trace, and the test goes on.
 */
class TestThread(block: () -> Unit) {
  // Declared before the thread starts, so this initializer never overwrites the worker's write.
  // The join in joinOrFail makes the write visible to the test thread.
  private var failure: Throwable? = null

  private val thread = thread {
    try {
      block()
    } catch (t: Throwable) {
      failure = t
    }
  }

  /**
   * Waits up to [timeoutMillis] for the block to end. Fails when the block still runs then, and
   * rethrows what the block threw.
   */
  fun joinOrFail(timeoutMillis: Long) {
    thread.join(timeoutMillis)
    check(!thread.isAlive) { "${thread.name} still runs after $timeoutMillis ms" }
    failure?.let { throw AssertionError("${thread.name} failed", it) }
  }
}
