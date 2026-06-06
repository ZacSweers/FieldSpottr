// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.AvailabilityAreaFeed
import dev.zacsweers.fieldspottr.data.AvailabilityFeedRow
import dev.zacsweers.fieldspottr.data.Field
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf

class GeneratorTest {
  @Test
  fun `bbp monday schedule expands field 1 split blocks`() {
    val juneFirst =
      generateBbpPier5Rows().filter { row ->
        row.fieldId == "pier5-field-1" && row.start == nyMillis("2026-06-01T09:00")
      }
    val mondayField1 =
      generateBbpPier5Rows().filter { row ->
        row.fieldId == "pier5-field-1" &&
          row.start in nyMillis("2026-06-01T00:00")..nyMillis("2026-06-01T23:59")
      }

    assertThat(juneFirst).hasSize(1)
    assertThat(juneFirst.single().groupName).isEqualTo("Pier 5")
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
          },
          "${nyEpochSecond("2026-06-05T19:00")}": {
            "permit_is_for_overlapping_field": true,
            "permit_holder": "Downtown Little League",
            "permit_number": 123
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

    assertThat(rows).hasSize(3)
    assertThat(rows[0].kind).isEqualTo("NYC live")
    assertThat(rows[0].title).isEqualTo("Pending final approval")
    assertThat(rows[1].kind).isEqualTo("advisory")
    assertThat(rows[1].advisoryText).isEqualTo("2 pending permits")
    assertThat(rows[2].title).isEqualTo("Overlapping field permit")
    assertThat(rows[2].isOverlap).isEqualTo(true)
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

  @Test
  fun `bbp schedule image discovery canonicalizes optimized images and chooses latest season`() {
    val springImage =
      "https://www.brooklynbridgepark.org/nitropack_static/assets/images/optimized/rev/" +
        "brooklynbridgepark.org/wp-content/uploads/2023/07/PIer-5-Turf-Spring-2026-e111-1024x461.png"
    val summerImage =
      "https:\\/\\/www.brooklynbridgepark.org\\/nitropack_static\\/assets\\/images\\/" +
        "optimized\\/rev\\/brooklynbridgepark.org\\/wp-content\\/uploads\\/2023\\/07\\/" +
        "PIer-5-Turf-Summer-2026-e222-300x135.png"
    val volleyballImage =
      "https://brooklynbridgepark.org/wp-content/uploads/2023/07/" +
        "Volleyball-Court-Schedule-Summer-2026-e333.png"
    val page =
      """
      <html>
        <img src="$springImage">
        <img srcset="$summerImage 300w">
        <img src="$volleyballImage">
      </html>
      """
        .trimIndent()

    assertThat(page.findBbpPier5ScheduleImageUrl())
      .isEqualTo(
        "https://brooklynbridgepark.org/wp-content/uploads/2023/07/PIer-5-Turf-Summer-2026-e222.png"
      )
  }

  @Test
  fun `cloudflare block detection ignores normal email protection snippets`() {
    val page =
      """
      <html>
        <a href="/cdn-cgi/l/email-protection#abc">[email protected]</a>
        <span>Performance content unrelated to a block page</span>
      </html>
      """
        .trimIndent()

    assertThat(page.isCloudflareBlockPage()).isEqualTo(false)
  }

  @Test
  fun `cloudflare block detection accepts block page variants`() {
    val page =
      """
      <html>
        <title>Attention Required! | Cloudflare</title>
        <div class="cf-error-details-wrapper">
          <h1>Sorry, you have been blocked</h1>
          <span>Cloudflare Ray ID: abc</span>
        </div>
      </html>
      """
        .trimIndent()

    assertThat(page.isCloudflareBlockPage()).isEqualTo(true)
  }

  @Test
  fun `hrp weekly schedule parser expands table rows`() {
    val area = Areas.default.entries.single { it.areaName == "West Side Highway" }
    val rows = hrpFixture.toHrpRows(area)

    assertThat(rows.map { row -> row.fieldId to (row.start to row.end) })
      .containsExactly(
        "pier25-turf-field" to (nyMillis("2026-06-07T08:00") to nyMillis("2026-06-07T12:00")),
        "pier40-courtyard-east" to (nyMillis("2026-06-07T15:00") to nyMillis("2026-06-07T16:00")),
        "pier40-courtyard-east" to (nyMillis("2026-06-08T17:30") to nyMillis("2026-06-08T23:30")),
      )
    assertThat(rows[0].areaName).isEqualTo("West Side Highway")
    assertThat(rows[0].groupName).isEqualTo("Pier 25")
    assertThat(rows[0].title).isEqualTo("Busy (Active permits)")
    assertThat(rows[0].kind).isEqualTo("HRP active permits")
  }
}

private val hrpFixture =
  """
  #### June 7–14, 2026

  Image: Pier 25 Turf Field

  Time | Sun
  6/7 | Mon
  6/8
  --- | --- | ---
  6:00 AM | |
  7:00 AM | |
  8:00 AM | |
  9:00 AM | 8:00 AM– |
  10:00 AM | 12:00 PM |
  11:00 AM | |

  Image: Pier 40 Courtyard East

  Time | Sun
  6/7 | Mon
  6/8
  --- | --- | ---
  3:00 PM | 3–4:00 PM |
  5:00 PM | |
  6:00 PM | | 5:30 PM–
  7:00 PM | |
  8:00 PM | | 11:30 PM
  """
    .trimIndent()

private fun nyMillis(value: String): Long {
  return java.time.LocalDateTime.parse(value)
    .atZone(java.time.ZoneId.of("America/New_York"))
    .toInstant()
    .toEpochMilli()
}

private fun nyEpochSecond(value: String): Long {
  return java.time.LocalDateTime.parse(value)
    .atZone(java.time.ZoneId.of("America/New_York"))
    .toInstant()
    .epochSecond
}
