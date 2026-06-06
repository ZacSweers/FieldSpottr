// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.AvailabilityAreaFeed
import dev.zacsweers.fieldspottr.data.AvailabilityFeedRow
import dev.zacsweers.fieldspottr.data.Field
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

class GeneratorTest {
  @Test
  fun `bbp monday schedule expands field 1 split blocks`() {
    val juneFirst =
      generateBbpPier5Rows().filter { row ->
        row.fieldId == "pier5-field-1" && row.start == nyMillis("2026-06-01T09:00")
      }
    val mondayField1 = generateBbpPier5Rows().filter { row ->
      row.fieldId == "pier5-field-1" && row.start in nyMillis("2026-06-01T00:00")..nyMillis("2026-06-01T23:59")
    }

    assertThat(juneFirst).hasSize(1)
    assertThat(mondayField1.map { it.start to it.end })
      .containsExactly(
        nyMillis("2026-06-01T09:00") to nyMillis("2026-06-01T10:00"),
        nyMillis("2026-06-01T17:00") to nyMillis("2026-06-01T23:00"),
      )
  }

  @Test
  fun `nyc live parser creates hard and advisory rows`() {
    val area = Area("Baruch", "Baruch", fieldGroups = persistentListOf())
    val field = Field("Football-01", "Soccer 1", "Baruch", apiLocationId = "M165-FOOTBALL-1")
    val response =
      """
      {
        "availability": {
          "${nyEpochSecond("2026-06-05T18:00")}": {
            "is_issued": false,
            "permit_holder": "Pending Org",
            "num_pending_permits": 0
          },
          "${nyEpochSecond("2026-06-05T18:30")}": {
            "num_pending_permits": 2
          }
        }
      }
      """
        .trimIndent()

    val rows =
      response.toNycLiveRows(
        area = area,
        groupName = "Baruch",
        field = field,
        startDateInclusive = java.time.LocalDate.of(2026, 6, 5),
        endDateExclusive = java.time.LocalDate.of(2026, 6, 6),
      )

    assertThat(rows).hasSize(2)
    assertThat(rows[0].kind).isEqualTo("NYC live")
    assertThat(rows[0].title).isEqualTo("Pending final approval")
    assertThat(rows[1].kind).isEqualTo("advisory")
    assertThat(rows[1].advisoryText).isEqualTo("2 pending permits")
  }

  @Test
  fun `feed hash ignores generatedAt`() {
    val row =
      AvailabilityFeedRow(
        areaName = "Area",
        groupName = "Group",
        fieldId = "Field",
        start = 1L,
        end = 2L,
        title = "Title",
      )

    val first = AvailabilityAreaFeed("Area", generatedAt = 1L, rows = listOf(row))
    val second = AvailabilityAreaFeed("Area", generatedAt = 2L, rows = listOf(row))

    assertThat(first.contentHash()).isEqualTo(second.contentHash())
  }

  @Test
  fun `area names slug into feed paths`() {
    assertThat("Brooklyn Bridge Park".slug()).isEqualTo("brooklyn-bridge-park")
    assertThat("Peter's Field".slug()).isEqualTo("peter-s-field")
  }
}

private fun nyMillis(value: String): Long {
  return java.time.LocalDateTime
    .parse(value)
    .atZone(java.time.ZoneId.of("America/New_York"))
    .toInstant()
    .toEpochMilli()
}

private fun nyEpochSecond(value: String): Long {
  return java.time.LocalDateTime
    .parse(value)
    .atZone(java.time.ZoneId.of("America/New_York"))
    .toInstant()
    .epochSecond
}
