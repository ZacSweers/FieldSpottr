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
}

@Immutable
data class WeekSegment(val startHour: Int, val endHour: Int, val state: WeekSlotState) {
  val durationHours: Int
    get() = endHour - startHour
}

@Immutable
data class WeekDayAvailability(
  val date: LocalDate,
  val segments: ImmutableList<WeekSegment>,
)

@Immutable
data class WeekAvailability(
  val startDate: LocalDate,
  val days: ImmutableList<WeekDayAvailability>,
)

/**
 * Collapses a group's per-field schedules for 7 days starting at [startDate] into a 3-state strip
 * per day: at a given hour, either every field is free, some are, or none are. Subfield overlap
 * semantics (shared physical space) are inherited from [PermitState.fromPermits].
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
          when {
            fieldCount == 0 -> WeekSlotState.BOOKED
            else -> {
              val bookedFields =
                permitState.fields.values.count { fieldStates ->
                  fieldStates.filterIsInstance<Reserved>().any { reserved ->
                    hour >= reserved.start && hour < reserved.end
                  }
                }
              when {
                bookedFields == 0 -> WeekSlotState.ALL_FREE
                bookedFields < fieldCount -> WeekSlotState.SOME_FREE
                else -> WeekSlotState.BOOKED
              }
            }
          }
        }

      WeekDayAvailability(date = date, segments = hourStates.toSegments())
    }

  return WeekAvailability(startDate = startDate, days = days.toImmutableList())
}

private fun List<WeekSlotState>.toSegments(): ImmutableList<WeekSegment> {
  if (isEmpty()) return emptyList<WeekSegment>().toImmutableList()
  val segments = mutableListOf<WeekSegment>()
  var segmentStart = WEEK_VIEW_START_HOUR
  var current = first()
  for ((index, state) in withIndex()) {
    if (index == 0) continue
    val hour = WEEK_VIEW_START_HOUR + index
    if (state != current) {
      segments += WeekSegment(segmentStart, hour, current)
      segmentStart = hour
      current = state
    }
  }
  segments += WeekSegment(segmentStart, WEEK_VIEW_END_HOUR, current)
  return segments.toImmutableList()
}
