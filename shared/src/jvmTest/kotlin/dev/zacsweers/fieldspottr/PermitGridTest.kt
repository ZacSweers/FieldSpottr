// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.LiveFieldAvailability
import dev.zacsweers.fieldspottr.data.LiveGroupAvailability
import dev.zacsweers.fieldspottr.data.LivePermitBlock
import kotlin.time.Clock
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap

class PermitGridTest {
  private val group = Areas.default.groups.getValue("Baruch")
  private val softball1 = group.fields.single { it.displayName == "Softball 1" }
  private val soccer1 = group.fields.single { it.displayName == "Soccer 1" }

  @Test
  fun `grid normalizes retained live data before rendering`() {
    val availability = liveAvailabilityForGrid(group, rawSoccerPendingAvailability())!!

    val softballBlocks = availability.fields.getValue(softball1).blocks
    assertThat(softballBlocks).hasSize(1)
    val softballBlock = softballBlocks.single()
    assertThat(softballBlock.title).isEqualTo("Pending approval")
    assertThat(softballBlock.startSlot).isEqualTo(28)
    assertThat(softballBlock.endSlot).isEqualTo(32)
    assertThat(softballBlock.isOverlap).isEqualTo(true)
  }

  @Test
  fun `baruch live overlap renders between existing softball reservations`() {
    val availability = liveAvailabilityForGrid(group, rawSoccerPendingAvailability())!!
    val softballLiveField = availability.fields.getValue(softball1)
    val fieldStates =
      listOf(
        reserved(start = 8, end = 14),
        reserved(start = 16, end = 18),
      )

    val items = permitGridColumnItems(fieldStates, softballLiveField)

    val liveBlocks = items.filterIsInstance<PermitGridColumnItem.Block>()
    assertThat(liveBlocks).hasSize(1)
    val block = liveBlocks.single().block
    assertThat(block.startSlot).isEqualTo(28)
    assertThat(block.endSlot).isEqualTo(32)
    assertThat(block.isOverlap).isEqualTo(true)
  }

  @Test
  fun `live overlap is clipped around existing reservations instead of dropped`() {
    val fieldStates = listOf(reserved(start = 8, end = 14))
    val liveField =
      LiveFieldAvailability(
        field = softball1,
        blocks =
          persistentListOf(
            LivePermitBlock(
              startSlot = 16,
              endSlot = 32,
              title = "Overlapping permit",
              org = "",
              status = "Overlapping field permit",
              isOverlap = true,
            )
          ),
        advisories = persistentListOf(),
      )

    val items = permitGridColumnItems(fieldStates, liveField)

    val liveBlocks = items.filterIsInstance<PermitGridColumnItem.Block>()
    assertThat(liveBlocks).hasSize(1)
    val block = liveBlocks.single().block
    assertThat(block.startSlot).isEqualTo(28)
    assertThat(block.endSlot).isEqualTo(32)
    assertThat(block.isOverlap).isEqualTo(true)
  }

  @Test
  fun `grid live normalization is idempotent`() {
    val availability = rawSoccerPendingAvailability()
    val once = liveAvailabilityForGrid(group, availability)!!
    val twice = liveAvailabilityForGrid(group, once)!!

    assertThat(twice.fields.getValue(softball1).blocks).hasSize(1)
  }

  @Test
  fun `grand street field 2 fields share one overlap surface`() {
    val field2 = Areas.default.groups.getValue("Grand Street (Field 2)")

    assertThat(field2.fields.map { it.sharedFields.toList() })
      .isEqualTo(listOf(listOf("field2"), listOf("field2"), listOf("field2")))
  }

  private fun rawSoccerPendingAvailability(): LiveGroupAvailability {
    val pendingBlock =
      LivePermitBlock(
        startSlot = 28,
        endSlot = 32,
        title = "Pending approval",
        org = "Bard High School Early College Manhattan",
        status = "Pending final approval",
        isOverlap = false,
      )
    return LiveGroupAvailability(
      checkedAt = Clock.System.now(),
      fields =
        mapOf(
            soccer1 to
              LiveFieldAvailability(
                field = soccer1,
                blocks = persistentListOf(pendingBlock),
                advisories = persistentListOf(),
              )
          )
          .toImmutableMap(),
    )
  }

  private fun reserved(start: Int, end: Int): Reserved {
    return Reserved(
      start = start,
      end = end,
      timeRange = "$start-$end",
      title = "Reserved",
      org = "Org",
      status = "Issued",
      isBlocked = false,
      isOverlap = false,
    )
  }
}
