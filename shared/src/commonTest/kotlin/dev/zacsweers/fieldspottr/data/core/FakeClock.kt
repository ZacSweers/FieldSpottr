// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Clock
import kotlin.time.Instant

internal class FakeClock(var now: Instant) : Clock {
  override fun now(): Instant = now
}
