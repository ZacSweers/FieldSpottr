// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable

/**
 * A Find a Field time window. Built-in windows are defined here. Custom windows are saved by the
 * user in [FSPreferencesStore].
 *
 * Times are minutes since midnight in New York time, with an exclusive end. Availability is
 * computed per hour, so windows are kept to whole hours.
 */
@Immutable
data class TimeWindow(
  val id: String,
  val name: String,
  val startMinutes: Int,
  val endMinutes: Int,
  val builtIn: Boolean = false,
) {
  init {
    require(startMinutes % 60 == 0 && endMinutes % 60 == 0) { "Windows use whole hours." }
    require(startMinutes in 0..<endMinutes && endMinutes <= MINUTES_PER_DAY) {
      "Invalid window range: $startMinutes-$endMinutes"
    }
  }

  val startHour: Int
    get() = startMinutes / 60

  val endHour: Int
    get() = endMinutes / 60

  /** Built-in windows read "This morning" on today. Custom windows always use their name. */
  fun label(isToday: Boolean): String {
    return if (builtIn && isToday) "This ${name.lowercase()}" else name
  }

  /** Whether this window has ended on today's date once the clock reaches [currentHour]. */
  fun hasPassed(currentHour: Int): Boolean = currentHour >= endHour

  companion object {
    const val MINUTES_PER_DAY = 24 * 60

    /** Saved custom windows are capped so the chip row stays short. */
    const val MAX_CUSTOM_WINDOWS = 3

    val MORNING = builtIn("morning", "Morning", 6, 12)
    val AFTERNOON = builtIn("afternoon", "Afternoon", 12, 18)
    val EVENING = builtIn("evening", "Evening", 18, 23)

    val builtIns: List<TimeWindow> = listOf(MORNING, AFTERNOON, EVENING)

    /** The built-in window that holds [hour], or null outside all of them. */
    fun forHour(hour: Int): TimeWindow? {
      require(hour in 0..23) { "Hour must be between 0 and 23." }
      return builtIns.firstOrNull { hour in it.startHour..<it.endHour }
    }

    fun custom(id: String, name: String, startHour: Int, endHour: Int): TimeWindow {
      return TimeWindow(id, name, startHour * 60, endHour * 60)
    }

    private fun builtIn(id: String, name: String, startHour: Int, endHour: Int): TimeWindow {
      return TimeWindow(id, name, startHour * 60, endHour * 60, builtIn = true)
    }
  }
}
