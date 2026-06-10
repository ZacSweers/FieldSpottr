// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.util.toNyInstant
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class WeekAvailabilityTest {

  private val startDate = LocalDate(2026, 6, 8)

  @Test
  fun `empty week is all free`() {
    val week = computeWeekAvailability(emptyList(), Areas.default, "Baruch", startDate)

    assertThat(week.days.size).isEqualTo(7)
    for (day in week.days) {
      assertThat(day.segments.size).isEqualTo(1)
      val segment = day.segments.single()
      assertThat(segment.startHour).isEqualTo(WEEK_VIEW_START_HOUR)
      assertThat(segment.endHour).isEqualTo(WEEK_VIEW_END_HOUR)
      assertThat(segment.state).isEqualTo(WeekSlotState.ALL_FREE)
    }
  }

  @Test
  fun `shared-field permit marks hours as some free`() {
    // A permit on Softball-01 also blocks Soccer 1 (shared physical space), so 2 of 4 fields are
    // booked 6-8pm on the first day.
    val permits =
      listOf(
        permit(
          recordId = 1,
          date = startDate,
          fieldId = "Softball-01",
          startHour = 18,
          endHour = 20,
        )
      )

    val week = computeWeekAvailability(permits, Areas.default, "Baruch", startDate)

    val monday = week.days.first()
    assertThat(monday.segments.map { Triple(it.startHour, it.endHour, it.state) })
      .isEqualTo(
        listOf(
          Triple(WEEK_VIEW_START_HOUR, 18, WeekSlotState.ALL_FREE),
          Triple(18, 20, WeekSlotState.SOME_FREE),
          Triple(20, WEEK_VIEW_END_HOUR, WeekSlotState.ALL_FREE),
        )
      )
    // Other days untouched
    assertThat(week.days[1].segments.single().state).isEqualTo(WeekSlotState.ALL_FREE)
  }

  @Test
  fun `all fields booked marks hours as booked`() {
    val permits =
      listOf(
        permit(recordId = 1, date = startDate, fieldId = "Softball-01", startHour = 18, endHour = 20),
        permit(recordId = 2, date = startDate, fieldId = "Football-01", startHour = 18, endHour = 20),
        permit(recordId = 3, date = startDate, fieldId = "Football-02", startHour = 18, endHour = 20),
        permit(recordId = 4, date = startDate, fieldId = "Softball-02", startHour = 18, endHour = 20),
      )

    val week = computeWeekAvailability(permits, Areas.default, "Baruch", startDate)

    val monday = week.days.first()
    assertThat(monday.segments.first { it.startHour == 18 }.state).isEqualTo(WeekSlotState.BOOKED)
  }

  @Test
  fun `closed groups are fully booked`() {
    val week = computeWeekAvailability(emptyList(), Areas.default, "Track", startDate)

    for (day in week.days) {
      assertThat(day.segments.single().state).isEqualTo(WeekSlotState.BOOKED)
    }
  }

  private fun permit(
    recordId: Long,
    date: LocalDate,
    fieldId: String,
    startHour: Int,
    endHour: Int,
  ): DbPermit {
    return DbPermit(
      recordId = recordId,
      area = "Baruch",
      groupName = "Baruch",
      start = LocalDateTime(date, LocalTime(startHour, 0)).toNyInstant().toEpochMilliseconds(),
      end = LocalDateTime(date, LocalTime(endHour, 0)).toNyInstant().toEpochMilliseconds(),
      fieldId = fieldId,
      type = "Athletic League",
      name = "Test Permit",
      org = "Test Org",
      status = "Approved",
      isOverlap = 0L,
      advisory = null,
    )
  }
}
