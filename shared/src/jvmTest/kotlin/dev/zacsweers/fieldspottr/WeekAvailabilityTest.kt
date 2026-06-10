// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.util.toNyInstant
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class WeekAvailabilityTest {

  private val startDate = LocalDate(2026, 6, 8)
  private val hoursShown = WEEK_VIEW_END_HOUR - WEEK_VIEW_START_HOUR

  @Test
  fun `empty week is all free`() {
    val week = computeWeekAvailability(emptyList(), Areas.default, "Baruch", startDate)

    assertThat(week.days.size).isEqualTo(7)
    for (day in week.days) {
      assertThat(day.hourStates.size).isEqualTo(hoursShown)
      assertThat(day.hourStates.all { it == WeekSlotState.ALL_FREE }).isTrue()
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
    assertThat(monday.stateAt(17)).isEqualTo(WeekSlotState.ALL_FREE)
    assertThat(monday.stateAt(18)).isEqualTo(WeekSlotState.SOME_FREE)
    assertThat(monday.stateAt(19)).isEqualTo(WeekSlotState.SOME_FREE)
    assertThat(monday.stateAt(20)).isEqualTo(WeekSlotState.ALL_FREE)
    // Other days untouched
    assertThat(week.days[1].hourStates.all { it == WeekSlotState.ALL_FREE }).isTrue()
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

    assertThat(week.days.first().stateAt(18)).isEqualTo(WeekSlotState.BOOKED)
    assertThat(week.days.first().stateAt(20)).isEqualTo(WeekSlotState.ALL_FREE)
  }

  @Test
  fun `statically closed groups show as closed all week`() {
    val week = computeWeekAvailability(emptyList(), Areas.default, "Track", startDate)

    for (day in week.days) {
      assertThat(day.hourStates.all { it == WeekSlotState.CLOSED }).isTrue()
    }
  }

  @Test
  fun `closure-blocked hours show as closed, not booked`() {
    val permits =
      listOf(
        permit(
          recordId = 1,
          date = startDate,
          fieldId = "Soccer-01",
          area = "Corlears Hook",
          group = "Corlears Hook",
          startHour = 0,
          endHour = 23,
          name = "Closure: Wet field",
          org = "",
          status = "Closed",
        ),
        permit(
          recordId = 2,
          date = startDate,
          fieldId = "Softball-01",
          area = "Corlears Hook",
          group = "Corlears Hook",
          startHour = 0,
          endHour = 23,
          name = "Closure: Wet field",
          org = "",
          status = "Closed",
        ),
      )

    val week = computeWeekAvailability(permits, Areas.default, "Corlears Hook", startDate)

    assertThat(week.days.first().stateAt(12)).isEqualTo(WeekSlotState.CLOSED)
    assertThat(week.days[1].stateAt(12)).isEqualTo(WeekSlotState.ALL_FREE)
  }

  private fun permit(
    recordId: Long,
    date: LocalDate,
    fieldId: String,
    startHour: Int,
    endHour: Int,
    area: String = "Baruch",
    group: String = "Baruch",
    name: String = "Test Permit",
    org: String = "Test Org",
    status: String = "Approved",
  ): DbPermit {
    return DbPermit(
      recordId = recordId,
      area = area,
      groupName = group,
      start = LocalDateTime(date, LocalTime(startHour, 0)).toNyInstant().toEpochMilliseconds(),
      end = LocalDateTime(date, LocalTime(endHour, 0)).toNyInstant().toEpochMilliseconds(),
      fieldId = fieldId,
      type = "Athletic League",
      name = name,
      org = org,
      status = status,
      isOverlap = 0L,
      advisory = null,
    )
  }
}
