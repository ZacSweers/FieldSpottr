// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.ui.formatTime12
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Reference times are from NOAA's solar calculator for lower Manhattan, in New York time. */
class SunTest {
  @Test
  fun `winter solstice sunset`() {
    assertNear(nycSunset(LocalDate(2026, 12, 21)), LocalTime(16, 31))
  }

  @Test
  fun `summer solstice sunset in daylight time`() {
    assertNear(nycSunset(LocalDate(2026, 6, 21)), LocalTime(20, 30))
  }

  @Test
  fun `sunset after the fall clock change`() {
    assertNear(nycSunset(LocalDate(2026, 11, 8)), LocalTime(16, 44))
  }

  @Test
  fun `sunset times format with minutes`() {
    assertThat(formatTime12(LocalTime(16, 4))).isEqualTo("4:04pm")
    assertThat(formatTime12(LocalTime(12, 0))).isEqualTo("12:00pm")
    assertThat(formatTime12(LocalTime(0, 5))).isEqualTo("12:05am")
  }

  private fun assertNear(actual: LocalTime, expected: LocalTime) {
    val expectedMinutes = expected.hour * 60 + expected.minute
    assertThat(actual.hour * 60 + actual.minute).isBetween(expectedMinutes - 1, expectedMinutes + 1)
  }
}
