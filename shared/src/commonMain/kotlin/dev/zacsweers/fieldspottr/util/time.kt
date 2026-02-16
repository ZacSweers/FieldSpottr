// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val NYC_TZ = TimeZone.of("America/New_York")

private val EventTimeFormatter =
  LocalDateTime.Format {
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
