// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.Location
import dev.zacsweers.fieldspottr.data.buildAreas
import dev.zacsweers.fieldspottr.util.toNyInstant
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class FindFieldScreenTest {

  private val date = LocalDate(2026, 6, 1)

  @Test
  fun `baruch evening permits are partially open`() {
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Baruch",
          group = "Baruch",
          fieldId = "Softball-02",
          startHour = 18,
          endHour = 20,
        ),
        permit(
          recordId = 2,
          area = "Baruch",
          group = "Baruch",
          fieldId = "Softball-01",
          startHour = 18,
          startMinute = 30,
          endHour = 20,
        ),
      )

    val (fullyOpen, partiallyOpen) = computeAvailability(permits, Areas.default, 18, 23)

    assertThat(
        fullyOpen.any { it.areaDisplayName == "Baruch Playground" && it.group.name == "Baruch" }
      )
      .isFalse()
    assertThat(
        partiallyOpen
          .single { it.areaDisplayName == "Baruch Playground" && it.group.name == "Baruch" }
          .openTimeRange
      )
      .isEqualTo("8–11pm")
  }

  @Test
  fun `availability scopes permits by area and group`() {
    val areas = buildAreas {
      area(name = "Area A", displayName = "Area A", csvUrl = "") {
        group(name = "Shared", location = Location(gmaps = "", amaps = "")) {
          field(csvName = "Field-A", displayName = "Field")
        }
      }
      area(name = "Area B", displayName = "Area B", csvUrl = "") {
        group(name = "Shared", location = Location(gmaps = "", amaps = "")) {
          field(csvName = "Field-B", displayName = "Field")
        }
      }
    }
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Area B",
          group = "Shared",
          fieldId = "Field-B",
          startHour = 18,
          endHour = 20,
        )
      )

    val (fullyOpen, partiallyOpen) = computeAvailability(permits, areas, 18, 23)

    assertThat(fullyOpen.map { it.areaDisplayName to it.group.name })
      .isEqualTo(listOf("Area A" to "Shared"))
    assertThat(partiallyOpen.map { it.areaDisplayName to it.group.name to it.openTimeRange })
      .isEqualTo(listOf(("Area B" to "Shared") to "8–11pm"))
  }

  private fun permit(
    recordId: Long,
    area: String,
    group: String,
    fieldId: String,
    startHour: Int,
    endHour: Int,
    startMinute: Int = 0,
    endMinute: Int = 0,
  ): DbPermit {
    return DbPermit(
      recordId = recordId,
      area = area,
      groupName = group,
      start =
        LocalDateTime(date, LocalTime(startHour, startMinute)).toNyInstant().toEpochMilliseconds(),
      end = LocalDateTime(date, LocalTime(endHour, endMinute)).toNyInstant().toEpochMilliseconds(),
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
