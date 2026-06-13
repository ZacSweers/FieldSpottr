// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.generator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
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
  fun `nyc csv parser rejects html block pages`() {
    val area = Area("Baruch", "Baruch", fieldGroups = persistentListOf())
    val response =
      """
      <!doctype html>
      <html>
        <title>Attention Required</title>
      </html>
      """
        .trimIndent()

    val rows = response.toAvailabilityRowsOrNull(area, source = "test")

    assertThat(rows).isEqualTo(null)
  }

  @Test
  fun `nyc csv parser accepts empty csv responses`() {
    val area = Area("Baruch", "Baruch", fieldGroups = persistentListOf())
    val response = "Start Date,End Date,Field Name,Type,Title,Org,Status\n"

    val rows = response.toAvailabilityRowsOrNull(area, source = "test")

    assertThat(rows).isEqualTo(emptyList<AvailabilityFeedRow>())
  }

  @Test
  fun `nyc live parser ignores html block pages`() {
    val area = Area("Baruch", "Baruch", fieldGroups = persistentListOf())
    val field = Field("Football-01", "Soccer 1", "Baruch", apiLocationId = "M165-FOOTBALL-1")
    val response =
      """
      <!doctype html>
      <html>
        <title>Attention Required</title>
      </html>
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

    assertThat(rows).isEmpty()
  }

  @Test
  fun `nyc live parser reads chrome dumped json pages`() {
    val area = Area("Baruch", "Baruch", fieldGroups = persistentListOf())
    val field = Field("Football-01", "Soccer 1", "Baruch", apiLocationId = "M165-FOOTBALL-1")
    val response =
      """
      <html>
        <head><meta name="color-scheme" content="light dark"></head>
        <body>
          <pre style="word-wrap: break-word; white-space: pre-wrap;">
          {
            "availability": {
              "${nyEpochSecond("2026-06-05T18:00")}": {
                "is_issued": false,
                "permit_holder": "Pending Org",
                "num_pending_permits": 0
              }
            }
          }
          </pre>
        </body>
      </html>
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

    assertThat(rows).hasSize(1)
    assertThat(rows.single().title).isEqualTo("Pending final approval")
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

  @Test
  fun `closures parser tolerates key aliases and skips non-field closures`() {
    val payload =
      """
      [
        {"propid": "M017", "closure_desc": "Soccer field closed for resodding", "date_closed": "2026-06-01", "expected_completion": "2026-06-20"},
        {"PropID": "m165", "description": "Ballfield renovation", "start_date": "06/01/2026"},
        {"propid": "M015", "closure_desc": "Comfort station repairs"},
        {"unrelated": "value"}
      ]
      """
        .trimIndent()

    val closures = payload.toParkClosures()

    assertThat(closures).hasSize(2)
    val corlears = closures.single { it.parkId == "M017" }
    assertThat(corlears.startDate).isEqualTo(java.time.LocalDate.of(2026, 6, 1))
    assertThat(corlears.endDate).isEqualTo(java.time.LocalDate.of(2026, 6, 20))
    val baruch = closures.single { it.parkId == "M165" }
    assertThat(baruch.startDate).isEqualTo(java.time.LocalDate.of(2026, 6, 1))
    assertThat(baruch.endDate).isEqualTo(null)
  }

  @Test
  fun `closures parser fails open on malformed payloads`() {
    assertThat("not json".toParkClosures()).isEmpty()
    assertThat("{}".toParkClosures()).isEmpty()
    assertThat("[]".toParkClosures()).isEmpty()
  }

  @Test
  fun `closure rows are emitted per day and clamped to the horizon`() {
    val area = Areas.default.entries.single { it.areaName == "Corlears Hook" }
    val today = java.time.LocalDate.of(2026, 6, 10)
    val closures =
      listOf(
        ParkClosure(
          parkId = "M017",
          reason = "Athletic field closed for resodding",
          matchText = "Athletic field closed for resodding",
          startDate = java.time.LocalDate.of(2026, 6, 1),
          endDate = null,
        )
      )

    val rows = generateClosureRows(area, closures, today, horizonDays = 3)

    // 2 fields x 4 days (today through today+3)
    assertThat(rows).hasSize(8)
    assertThat(rows.all { it.kind == "closure" && it.status == "Closed" }).isEqualTo(true)
    assertThat(rows.all { it.title == "Closure: Athletic field closed for resodding" })
      .isEqualTo(true)
    assertThat(rows.minOf { it.start }).isEqualTo(nyMillis("2026-06-10T00:00"))
    assertThat(rows.maxOf { it.end }).isEqualTo(nyMillis("2026-06-14T00:00"))
  }

  @Test
  fun `sport-specific closures only block matching fields`() {
    val area = Areas.default.entries.single { it.areaName == "Corlears Hook" }
    val today = java.time.LocalDate.of(2026, 6, 10)
    val closures =
      listOf(
        ParkClosure(
          parkId = "M017",
          reason = "Softball field closed",
          matchText = "Softball field closed",
          startDate = null,
          endDate = java.time.LocalDate.of(2026, 6, 10),
        )
      )

    val rows = generateClosureRows(area, closures, today, horizonDays = 30)

    assertThat(rows.map { it.fieldId }.distinct()).containsExactly("Softball-01")
  }

  @Test
  fun `closures ignore areas without a park id`() {
    val area = Areas.default.entries.single { it.areaName == "West Side Highway" }
    val closures =
      listOf(
        ParkClosure(
          parkId = "M017",
          reason = "Soccer field closed",
          matchText = "Soccer field closed",
          startDate = null,
          endDate = null,
        )
      )

    assertThat(generateClosureRows(area, closures, java.time.LocalDate.of(2026, 6, 10), 30))
      .isEmpty()
  }
}

private val hrpFixture =
  """
  #### June 7–14, 2026

  Pier 25 Turf Field Schedule
  | ![Image 1: Pier 25 Turf Field](https://example.com/pier25.png) |

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

  Pier 40 Courtyard East Schedule
  | ![Image 3: Pier 40 Courtyard East](https://example.com/pier40-east.png) |

  Time | Sun
  6/7 | Mon
  6/8
  --- | --- | ---
  3:00 PM | 3–4:00 PM |
  5:00 PM | |
  6:00 PM | | 5:30 PM–
  7:00 PM | |
  8:00 PM | | 11:30 PM

  For non-emergencies, call 212-242-6427.
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
