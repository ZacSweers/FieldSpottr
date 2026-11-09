// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.data.Location
import dev.zacsweers.fieldspottr.data.buildAreas
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DataFreshnessTest {
  private val now = Instant.parse("2026-11-08T12:00:00Z")
  private val areas = buildAreas {
    for (name in listOf("A", "B", "C")) {
      area(name = name, displayName = "Area $name") {
        group(name = name, location = Location(gmaps = "", amaps = "")) {
          field(csvName = "Field", displayName = "Field")
        }
      }
    }
  }
    .entries

  @Test
  fun `nothing downloaded has no freshness`() {
    assertThat(dataFreshness(areas, emptyMap(), now)).isNull()
  }

  @Test
  fun `the oldest area sets the age`() {
    val updates = mapOf("A" to now - 5.minutes, "B" to now - 3.hours, "C" to now - 1.hours)
    assertThat(dataFreshness(areas, updates, now))
      .isEqualTo(DataFreshness("Updated 3h ago", isStale = false))
  }

  @Test
  fun `one stale area is named`() {
    val updates = mapOf("A" to now - 1.hours, "B" to now - 40.hours, "C" to now - 1.hours)
    assertThat(dataFreshness(areas, updates, now))
      .isEqualTo(DataFreshness("Area B hasn't updated in 40h", isStale = true))
  }

  @Test
  fun `an area that never downloaded is stale`() {
    val updates = mapOf("A" to now - 1.hours, "B" to now - 1.hours)
    assertThat(dataFreshness(areas, updates, now))
      .isEqualTo(DataFreshness("Area C hasn't loaded", isStale = true))
  }

  @Test
  fun `several stale areas are counted`() {
    val updates = mapOf("A" to now - 1.hours, "B" to now - 40.hours, "C" to now - 72.hours)
    assertThat(dataFreshness(areas, updates, now))
      .isEqualTo(DataFreshness("2 areas haven't updated in over a day", isStale = true))
  }

  @Test
  fun `every area stale shows the oldest age`() {
    val updates = mapOf("A" to now - 40.hours, "B" to now - 50.hours, "C" to now - 72.hours)
    assertThat(dataFreshness(areas, updates, now))
      .isEqualTo(DataFreshness("Updated 3d ago", isStale = true))
  }
}
