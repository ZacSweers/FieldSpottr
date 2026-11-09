// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val NYC_TZ = TimeZone.of("America/New_York")

private val EventTimeFormatter = LocalDateTime.Format {
  amPmHour(padding = Padding.NONE)
  amPmMarker("am", "pm")
}

private val EventTimeNoAmPmFormatter = LocalDateTime.Format { amPmHour(padding = Padding.NONE) }

fun LocalDateTime.formatAmPm(): String {
  return EventTimeFormatter.format(this)
}

fun LocalDateTime.formatNoAmPm(): String {
  return EventTimeNoAmPmFormatter.format(this)
}

fun LocalDateTime.toNyInstant(): Instant {
  return toInstant(NYC_TZ)
}

fun LocalDate.atStartOfDayInNy(): Instant {
  return atStartOfDayIn(NYC_TZ)
}

fun Instant.toNyLocalDateTime(): LocalDateTime {
  return toLocalDateTime(NYC_TZ)
}

fun Long.toNyLocalDateTime(): LocalDateTime {
  val startDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(NYC_TZ)
  return startDateTime
}

/** The current New York time, truncated to the hour. */
fun currentNyHourDateTime(clock: Clock = Clock.System): LocalDateTime {
  val now = clock.now().toLocalDateTime(NYC_TZ)
  return LocalDateTime(now.date, LocalTime(now.hour, 0))
}

/**
 * The current New York time truncated to the hour, updated at the start of each hour. Screens that
 * stay open across an hour or midnight read this so "today" and "now" do not go stale.
 */
@Composable
fun produceNyHour(clock: Clock = Clock.System): State<LocalDateTime> {
  return produceState(currentNyHourDateTime(clock), clock) {
    while (true) {
      val nextHour = currentNyHourDateTime(clock).toNyInstant() + 1.hours
      delay((nextHour - clock.now()).coerceAtLeast(1.seconds))
      value = currentNyHourDateTime(clock)
    }
  }
}
