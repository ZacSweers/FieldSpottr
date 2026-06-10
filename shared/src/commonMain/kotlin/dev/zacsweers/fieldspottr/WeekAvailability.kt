// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Immutable
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Hours shown in the week view. Fields are effectively unusable outside this range. */
internal const val WEEK_VIEW_START_HOUR = 6
internal const val WEEK_VIEW_END_HOUR = 23

enum class WeekSlotState {
  ALL_FREE,
  SOME_FREE,
  BOOKED,
  /** Booked entirely by city blocks/closures rather than regular permits. */
  CLOSED,
}

@Immutable
data class WeekDayAvailability(
  val date: LocalDate,
  /** One state per hour in [WEEK_VIEW_START_HOUR, WEEK_VIEW_END_HOUR). */
  val hourStates: ImmutableList<WeekSlotState>,
) {
  fun stateAt(hour: Int): WeekSlotState = hourStates[hour - WEEK_VIEW_START_HOUR]
}

@Immutable
data class WeekAvailability(
  val startDate: LocalDate,
  val days: ImmutableList<WeekDayAvailability>,
)

/**
 * Collapses a group's per-field schedules for 7 days starting at [startDate] into a per-hour
 * 4-state strip per day: at a given hour, every field is free, some are, none are, or the group is
 * outright closed (city blocks/closures only). Subfield overlap semantics (shared physical space)
 * are inherited from [PermitState.fromPermits].
 */
internal fun computeWeekAvailability(
  permits: List<DbPermit>,
  areas: Areas,
  group: String,
  startDate: LocalDate,
): WeekAvailability {
  val fieldCount = areas.groups[group]?.fields?.size ?: 0
  val permitsByDate = permits.groupBy { it.start.toNyLocalDateTime().date }

  val days =
    (0 until 7).map { offset ->
      val date = startDate.plus(offset, DateTimeUnit.DAY)
      val dayPermits = permitsByDate[date].orEmpty()
      val permitState = PermitState.fromPermits(dayPermits, areas, group)

      val hourStates =
        (WEEK_VIEW_START_HOUR until WEEK_VIEW_END_HOUR).map { hour ->
          if (fieldCount == 0) {
            WeekSlotState.BOOKED
          } else {
            // For each field, the reservation (if any) covering this hour
            val coveringReservations =
              permitState.fields.values.map { fieldStates ->
                fieldStates.filterIsInstance<Reserved>().firstOrNull { reserved ->
                  hour >= reserved.start && hour < reserved.end
                }
              }
            val bookedFields = coveringReservations.count { it != null }
            when {
              bookedFields == 0 -> WeekSlotState.ALL_FREE
              bookedFields < fieldCount -> WeekSlotState.SOME_FREE
              coveringReservations.all { it != null && it.isBlocked } -> WeekSlotState.CLOSED
              else -> WeekSlotState.BOOKED
            }
          }
        }

      WeekDayAvailability(date = date, hourStates = hourStates.toImmutableList())
    }

  return WeekAvailability(startDate = startDate, days = days.toImmutableList())
}
