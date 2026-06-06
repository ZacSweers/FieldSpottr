// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

enum class TimeWindow(
  private val todayLabel: String,
  private val otherDayLabel: String,
  val startHour: Int,
  val endHour: Int,
) {
  MORNING("This morning", "Morning", 6, 12),
  AFTERNOON("This afternoon", "Afternoon", 12, 18),
  EVENING("This evening", "Evening", 18, 23);

  fun label(isToday: Boolean): String = if (isToday) todayLabel else otherDayLabel

  companion object {
    fun forHour(hour: Int): TimeWindow? {
      require(hour in 0..23) { "Hour must be between 0 and 23." }
      return entries.firstOrNull { hour in it.startHour..<it.endHour }
    }
  }
}
