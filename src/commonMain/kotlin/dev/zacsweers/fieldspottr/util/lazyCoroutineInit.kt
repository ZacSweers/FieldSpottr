// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A suspending lazy initializer. */
fun <T> lazySuspend(initializer: suspend () -> T): suspend () -> T = LazySuspend(initializer)

private class LazySuspend<T>(private val initializer: suspend () -> T) : suspend () -> T {
  private val mutex = Mutex()
  private var _value: T? = null

  override suspend fun invoke(): T {
    return mutex.withLock { _value ?: initializer().also { _value = it } }
  }
}
