// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.datetime.LocalTime

class FormatTime12Test {
  @Test
  fun `times format with minutes`() {
    assertThat(formatTime12(LocalTime(16, 4))).isEqualTo("4:04pm")
    assertThat(formatTime12(LocalTime(12, 0))).isEqualTo("12:00pm")
    assertThat(formatTime12(LocalTime(0, 5))).isEqualTo("12:05am")
  }
}
