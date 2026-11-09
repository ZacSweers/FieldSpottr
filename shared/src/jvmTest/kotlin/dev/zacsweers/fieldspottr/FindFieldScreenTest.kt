// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.BbpAvailability
import dev.zacsweers.fieldspottr.data.Location
import dev.zacsweers.fieldspottr.data.TimeWindow
import dev.zacsweers.fieldspottr.data.buildAreas
import dev.zacsweers.fieldspottr.util.toNyInstant
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class FindFieldScreenTest {

  private val date = LocalDate(2026, 6, 1)

  @Test
  fun `current time window maps by local hour`() {
    assertThat(TimeWindow.forHour(5)).isEqualTo(null)
    assertThat(TimeWindow.forHour(6)).isEqualTo(TimeWindow.MORNING)
    assertThat(TimeWindow.forHour(11)).isEqualTo(TimeWindow.MORNING)
    assertThat(TimeWindow.forHour(12)).isEqualTo(TimeWindow.AFTERNOON)
    assertThat(TimeWindow.forHour(17)).isEqualTo(TimeWindow.AFTERNOON)
    assertThat(TimeWindow.forHour(18)).isEqualTo(TimeWindow.EVENING)
    assertThat(TimeWindow.forHour(22)).isEqualTo(TimeWindow.EVENING)
    assertThat(TimeWindow.forHour(23)).isEqualTo(null)
  }

  @Test
  fun `today's window starts at the current hour`() {
    assertThat(searchHours(TimeWindow.MORNING, isToday = true, currentHour = 10))
      .isEqualTo(SearchHours(TimeWindow.MORNING, 10, 12))
    assertThat(searchHours(TimeWindow.EVENING, isToday = true, currentHour = 10))
      .isEqualTo(SearchHours(TimeWindow.EVENING, 18, 23))
    assertThat(searchHours(null, isToday = true, currentHour = 15))
      .isEqualTo(SearchHours(null, 15, 24))
  }

  @Test
  fun `a passed window on today falls back to the current window`() {
    assertThat(searchHours(TimeWindow.MORNING, isToday = true, currentHour = 14))
      .isEqualTo(SearchHours(TimeWindow.AFTERNOON, 14, 18))
    // No window holds 11pm, so the rest of the day is searched
    assertThat(searchHours(TimeWindow.EVENING, isToday = true, currentHour = 23))
      .isEqualTo(SearchHours(null, 23, 24))
  }

  @Test
  fun `custom windows are searched like built-ins`() {
    val afterWork = TimeWindow.custom("custom-1", "After work", 17, 20)
    assertThat(searchHours(afterWork, isToday = false, currentHour = 9))
      .isEqualTo(SearchHours(afterWork, 17, 20))
    assertThat(searchHours(afterWork, isToday = true, currentHour = 18))
      .isEqualTo(SearchHours(afterWork, 18, 20))
    assertThat(searchHours(afterWork, isToday = true, currentHour = 20))
      .isEqualTo(SearchHours(TimeWindow.EVENING, 20, 23))
  }

  @Test
  fun `a deleted selected window falls back to the current window`() {
    val afterWork = TimeWindow.custom("custom-1", "After work", 17, 20)
    val windows = TimeWindow.builtIns + afterWork
    assertThat(resolveSelectedWindow("custom-1", windows, currentHour = 9)).isEqualTo(afterWork)
    assertThat(resolveSelectedWindow("custom-1", TimeWindow.builtIns, currentHour = 9))
      .isEqualTo(TimeWindow.MORNING)
    assertThat(resolveSelectedWindow(null, windows, currentHour = 9)).isEqualTo(null)
  }

  @Test
  fun `windows must use whole hours in order`() {
    assertFailure { TimeWindow("x", "Half", 17 * 60 + 30, 19 * 60) }
    assertFailure { TimeWindow.custom("x", "Backwards", 20, 17) }
    assertFailure { TimeWindow.custom("x", "Too late", 20, 25) }
  }

  @Test
  fun `other days search the full window`() {
    assertThat(searchHours(TimeWindow.MORNING, isToday = false, currentHour = 14))
      .isEqualTo(SearchHours(TimeWindow.MORNING, 6, 12))
    assertThat(searchHours(null, isToday = false, currentHour = 14))
      .isEqualTo(SearchHours(null, 0, 24))
  }

  @Test
  fun `find field does not recommend BBP without coverage`() {
    val unavailable =
      permit(
        recordId = 1,
        area = "Brooklyn Bridge Park",
        group = "Pier 5",
        fieldId = "pier5-field-1",
        startHour = 0,
        endHour = 23,
        type = BbpAvailability.UNAVAILABLE_KIND,
      )
    for (permits in listOf(emptyList(), listOf(unavailable))) {
      val buckets = computeAvailability(permits, Areas.default, 18, 23)
      assertThat(buckets.fullyOpen.none { it.group.name == "Pier 5" }).isTrue()
      assertThat(buckets.partiallyOpen.none { it.group.name == "Pier 5" }).isTrue()
      assertThat(buckets.fullyOpen.any { it.group.name == "Baruch" }).isTrue()
    }
  }

  @Test
  fun `find field ignores coverage markers when recommending verified BBP gaps`() {
    val coverage =
      permit(
        recordId = 1,
        area = "Brooklyn Bridge Park",
        group = "Pier 5",
        fieldId = "pier5-field-1",
        startHour = 0,
        endHour = 23,
        type = BbpAvailability.COVERAGE_KIND,
      )
    val buckets = computeAvailability(listOf(coverage), Areas.default, 18, 23)
    assertThat(buckets.fullyOpen.any { it.group.name == "Pier 5" }).isTrue()
  }

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
  fun `a free surface keeps a booked group partially open`() {
    // Field 1 at Baruch is booked all evening, but field 2 is free
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Baruch",
          group = "Baruch",
          fieldId = "Softball-01",
          startHour = 18,
          endHour = 23,
        )
      )

    val baruch =
      computeAvailability(permits, Areas.default, 18, 23).partiallyOpen.single {
        it.group.name == "Baruch"
      }
    assertThat(baruch.openTimeRange).isEqualTo("")
    assertThat(baruch.fieldOpenings).containsExactly(FieldOpening("Soccer 2, Softball 2", "6–11pm"))
  }

  @Test
  fun `a booked half leaves the other half free longer than the whole field`() {
    // Independent halves, as scripts/add_nyc_park.py models them
    val areas = buildAreas {
      area(name = "Park", displayName = "Park") {
        group(name = "Field", location = Location(gmaps = "", amaps = "")) {
          field(csvName = "Soccer-01A", displayName = "West Half", sharedFields = setOf("a"))
          field(csvName = "Soccer-01", displayName = "Whole Field", sharedFields = setOf("a", "b"))
          field(csvName = "Soccer-01B", displayName = "East Half", sharedFields = setOf("b"))
        }
      }
    }
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Park",
          group = "Field",
          fieldId = "Soccer-01A",
          startHour = 18,
          endHour = 20,
        )
      )

    val field = computeAvailability(permits, areas, 18, 23).partiallyOpen.single()
    assertThat(field.openTimeRange).isEqualTo("8–11pm")
    assertThat(field.fieldOpenings).containsExactly(FieldOpening("East Half", "6–11pm"))
  }

  @Test
  fun `halves that share a token block each other`() {
    // Bushwick's halves share the "bushwick inlet field" token, like the grid's overlap model
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Bushwick Inlet Park",
          group = "Bushwick Inlet Park",
          fieldId = westHalfFieldId(),
          startHour = 18,
          endHour = 20,
        )
      )

    val bushwick =
      computeAvailability(permits, Areas.default, 18, 23).partiallyOpen.single {
        it.group.name == "Bushwick Inlet Park"
      }
    assertThat(bushwick.openTimeRange).isEqualTo("8–11pm")
    assertThat(bushwick.fieldOpenings).isEmpty()
  }

  @Test
  fun `fields free only with the whole group add no openings`() {
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Baruch",
          group = "Baruch",
          fieldId = "Softball-01",
          startHour = 18,
          endHour = 20,
        ),
        permit(
          recordId = 2,
          area = "Baruch",
          group = "Baruch",
          fieldId = "Softball-02",
          startHour = 18,
          endHour = 20,
        ),
      )

    val baruch =
      computeAvailability(permits, Areas.default, 18, 23).partiallyOpen.single {
        it.group.name == "Baruch"
      }
    assertThat(baruch.openTimeRange).isEqualTo("8–11pm")
    assertThat(baruch.fieldOpenings).isEmpty()
  }

  private fun westHalfFieldId(): String {
    return Areas.default.groups
      .getValue("Bushwick Inlet Park")
      .fields
      .single {
        it.displayName == "West Half"
      }
      .name
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

  @Test
  fun `statically closed groups are bucketed as closed`() {
    val buckets = computeAvailability(emptyList(), Areas.default, 18, 23)

    val track = buckets.closed.single { it.group.name == "Track" }
    assertThat(track.closedReason).isEqualTo("Closed for reconstruction")
    assertThat(buckets.fullyOpen.none { it.group.name == "Track" }).isTrue()
  }

  @Test
  fun `fields fully covered by closure blocks are bucketed as closed with reason`() {
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Corlears Hook",
          group = "Corlears Hook",
          fieldId = "Soccer-01",
          startHour = 0,
          endHour = 23,
          type = "closure",
          name = "Closure: Wet field",
          org = "",
          status = "Closed",
        ),
        permit(
          recordId = 2,
          area = "Corlears Hook",
          group = "Corlears Hook",
          fieldId = "Softball-01",
          startHour = 0,
          endHour = 23,
          type = "closure",
          name = "Closure: Wet field",
          org = "",
          status = "Closed",
        ),
      )

    val buckets = computeAvailability(permits, Areas.default, 18, 23)

    val corlears = buckets.closed.single { it.group.name == "Corlears Hook" }
    assertThat(corlears.closedReason).isEqualTo("Wet field")
    assertThat(buckets.partiallyOpen.none { it.group.name == "Corlears Hook" }).isTrue()
  }

  @Test
  fun `fields fully booked by regular permits are not bucketed as closed`() {
    val permits =
      listOf(
        permit(
          recordId = 1,
          area = "Corlears Hook",
          group = "Corlears Hook",
          fieldId = "Soccer-01",
          startHour = 18,
          endHour = 23,
        ),
        permit(
          recordId = 2,
          area = "Corlears Hook",
          group = "Corlears Hook",
          fieldId = "Softball-01",
          startHour = 18,
          endHour = 23,
        ),
      )

    val buckets = computeAvailability(permits, Areas.default, 18, 23)

    assertThat(buckets.closed.none { it.group.name == "Corlears Hook" }).isTrue()
    assertThat(buckets.fullyOpen.none { it.group.name == "Corlears Hook" }).isTrue()
    assertThat(buckets.partiallyOpen.none { it.group.name == "Corlears Hook" }).isTrue()
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
    type: String = "Athletic League",
    name: String = "Test Permit",
    org: String = "Test Org",
    status: String = "Approved",
  ): DbPermit {
    return DbPermit(
      recordId = recordId,
      area = area,
      groupName = group,
      start =
        LocalDateTime(date, LocalTime(startHour, startMinute)).toNyInstant().toEpochMilliseconds(),
      end = LocalDateTime(date, LocalTime(endHour, endMinute)).toNyInstant().toEpochMilliseconds(),
      fieldId = fieldId,
      type = type,
      name = name,
      org = org,
      status = status,
      isOverlap = 0L,
      advisory = null,
    )
  }
}
