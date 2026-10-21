// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import okio.BufferedSource
import okio.use

val BufferedSource.lines: Sequence<String>
  get() = generateSequence(::readUtf8Line)

inline fun BufferedSource.useLines(body: (Sequence<String>) -> Unit) {
  use { body(lines) }
}
