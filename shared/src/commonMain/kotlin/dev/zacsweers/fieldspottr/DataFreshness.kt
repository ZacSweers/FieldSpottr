// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Immutable
import dev.zacsweers.fieldspottr.data.Area
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Data refreshes daily, so a download older than this means refreshes are failing. */
internal val STALE_DATA_AGE = 36.hours

/** A one-line summary of how recently the app downloaded availability data. */
@Immutable data class DataFreshness(val text: String, val isStale: Boolean)

/**
 * Summarizes the last download time in [updates] for each of [areas], or null before any area has
 * downloaded. The age shown is the oldest area's, so an area that keeps failing to download is not
 * hidden behind fresh ones. When only some areas are older than [STALE_DATA_AGE], the text names
 * them instead. An area that never downloaded counts as stale.
 */
internal fun dataFreshness(
  areas: List<Area>,
  updates: Map<String, Instant>,
  now: Instant,
): DataFreshness? {
  val ages = areas.associateWith { area -> updates[area.areaName]?.let { now - it } }
  val oldest = ages.values.filterNotNull().maxOrNull() ?: return null
  val staleAreas = ages.filterValues { age -> age == null || age >= STALE_DATA_AGE }.keys
  return when {
    staleAreas.isEmpty() -> DataFreshness(updatedText(oldest), isStale = false)
    staleAreas.size == areas.size -> DataFreshness(updatedText(oldest), isStale = true)
    staleAreas.size == 1 -> {
      val area = staleAreas.single()
      val age = ages[area]
      val text =
        if (age == null) {
          "${area.displayName} hasn't loaded"
        } else {
          "${area.displayName} hasn't updated in ${shortAge(age)}"
        }
      DataFreshness(text, isStale = true)
    }
    else -> DataFreshness("${staleAreas.size} areas haven't updated in over a day", isStale = true)
  }
}

/** "Updated 3h ago" for data downloaded [elapsed] ago. */
internal fun updatedText(elapsed: Duration): String {
  return when {
    elapsed < 1.minutes -> "Updated just now"
    elapsed < 1.hours -> "Updated ${elapsed.inWholeMinutes}m ago"
    elapsed < 1.days -> "Updated ${elapsed.inWholeHours}h ago"
    else -> "Updated ${elapsed.inWholeDays}d ago"
  }
}

private fun shortAge(age: Duration): String {
  return if (age < 2.days) "${age.inWholeHours}h" else "${age.inWholeDays}d"
}
