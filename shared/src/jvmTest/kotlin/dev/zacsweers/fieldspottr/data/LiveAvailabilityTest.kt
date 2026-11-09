// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Clock
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

class LiveAvailabilityTest {
  @Test
  fun `live blocks propagate to overlapping fields`() {
    val softball =
      Field(
        name = "Softball-01",
        displayName = "Softball 1",
        group = "Baruch",
        sharedFields = persistentSetOf("field1"),
        apiLocationId = "M165-BASEBALL-1",
      )
    val soccer =
      Field(
        name = "Football-01",
        displayName = "Soccer 1",
        group = "Baruch",
        sharedFields = persistentSetOf("field1"),
        apiLocationId = "M165-FOOTBALL-1",
      )
    val group =
      FieldGroup(
        name = "Baruch",
        fields = persistentListOf(softball, soccer),
        area = "Baruch",
        location = Location("", ""),
      )
    val pendingBlock =
      LivePermitBlock(
        startSlot = 28,
        endSlot = 32,
        title = "Pending approval",
        org = "Bard High School Early College Manhattan",
        status = "Pending final approval",
        isOverlap = false,
      )

    val availability =
      LiveGroupAvailability(
          checkedAt = Clock.System.now(),
          fields =
            mapOf(
                soccer to
                  LiveFieldAvailability(
                    field = soccer,
                    blocks = listOf(pendingBlock).toImmutableList(),
                    advisories = persistentListOf(),
                  )
              )
              .toImmutableMap(),
        )
        .withOverlapsFrom(group)

    assertThat(availability.fields[soccer]!!.blocks.single().isOverlap).isEqualTo(false)
    val softballBlock = availability.fields[softball]!!.blocks.single()
    assertThat(softballBlock.title).isEqualTo("Pending approval")
    assertThat(softballBlock.startSlot).isEqualTo(28)
    assertThat(softballBlock.endSlot).isEqualTo(32)
    assertThat(softballBlock.isOverlap).isEqualTo(true)
  }
}
