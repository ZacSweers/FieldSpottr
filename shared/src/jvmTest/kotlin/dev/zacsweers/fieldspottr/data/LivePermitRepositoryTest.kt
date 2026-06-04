// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.util.toNyInstant
import kotlin.time.Clock
import kotlin.test.Test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json

class LivePermitRepositoryTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val date = LocalDate(2026, 6, 2)
  private val field =
    Field(
      name = "Football-01",
      displayName = "Soccer 1",
      group = "Baruch",
      sharedFields = persistentSetOf("field1"),
      apiLocationId = "M165-FOOTBALL-1",
    )

  @Test
  fun `pending final approval blocks the slot`() {
    val availability =
      parseLiveFieldAvailability(
        field,
        date,
        response(
          slot(
            hour = 18,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": false,
                "num_pending_permits": 0,
                "permit_number": 940103,
                "is_issued": false,
                "permit_holder": "Bard High School Early College Manhattan",
                "permit_type": "Sport - Youth"
              }
            """,
          ),
          slot(
            hour = 18,
            minute = 30,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": false,
                "num_pending_permits": 0,
                "permit_number": 940103,
                "is_issued": false,
                "permit_holder": "Bard High School Early College Manhattan",
                "permit_type": "Sport - Youth"
              }
            """,
          ),
        ),
        json,
      )

    assertThat(availability.blocks).hasSize(1)
    val block = availability.blocks.single()
    assertThat(block.startSlot).isEqualTo(36)
    assertThat(block.endSlot).isEqualTo(38)
    assertThat(block.title).isEqualTo("Pending approval")
    assertThat(block.org).isEqualTo("Bard High School Early College Manhattan")
    assertThat(block.status).isEqualTo("Pending final approval")
    assertThat(availability.advisories).isEmpty()
  }

  @Test
  fun `advisory-only pending permits do not create a block`() {
    val availability =
      parseLiveFieldAvailability(
        field,
        date,
        response(
          slot(
            hour = 19,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": false,
                "num_pending_permits": 2,
                "permit_number": null,
                "is_issued": null,
                "permit_holder": null,
                "permit_type": null
              }
            """,
          )
        ),
        json,
      )

    assertThat(availability.blocks).isEmpty()
    assertThat(availability.advisories).hasSize(1)
    val advisory = availability.advisories.single()
    assertThat(advisory.startSlot).isEqualTo(38)
    assertThat(advisory.endSlot).isEqualTo(39)
    assertThat(advisory.message).isEqualTo("There are 2 pending permit(s) for this field.")
  }

  @Test
  fun `overlapping and special event permits are hard blocks`() {
    val availability =
      parseLiveFieldAvailability(
        field,
        date,
        response(
          slot(
            hour = 20,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": true,
                "num_pending_permits": 0,
                "permit_number": 909435,
                "is_issued": true,
                "permit_holder": "Manhattan Youth Recreation and Resources",
                "permit_type": "Sport - Youth"
              }
            """,
          ),
          slot(
            hour = 21,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": false,
                "num_pending_permits": 0,
                "permit_number": 896704,
                "is_issued": true,
                "permit_holder": "PS 64",
                "permit_type": "Special Event"
              }
            """,
          ),
        ),
        json,
      )

    assertThat(availability.blocks.map { it.title })
      .isEqualTo(listOf("Overlapping permit", "Special event"))
    assertThat(availability.blocks.map { it.isOverlap }).isEqualTo(listOf(true, false))
  }

  @Test
  fun `adjacent overlapping permits merge as one unavailable block`() {
    val availability =
      parseLiveFieldAvailability(
        field,
        date,
        response(
          slot(
            hour = 9,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": true,
                "num_pending_permits": 0,
                "permit_number": 909435,
                "is_issued": true,
                "permit_holder": "Manhattan Youth Recreation and Resources",
                "permit_type": "Sport - Youth"
              }
            """,
          ),
          slot(
            hour = 9,
            minute = 30,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": true,
                "num_pending_permits": 0,
                "permit_number": 917391,
                "is_issued": true,
                "permit_holder": "New York Gothams Youth Baseball Inc.",
                "permit_type": "Sport - Youth"
              }
            """,
          ),
        ),
        json,
      )

    assertThat(availability.blocks).hasSize(1)
    val block = availability.blocks.single()
    assertThat(block.startSlot).isEqualTo(18)
    assertThat(block.endSlot).isEqualTo(20)
    assertThat(block.isOverlap).isEqualTo(true)
  }

  @Test
  fun `pending counts on hard blocks do not create separate advisories`() {
    val availability =
      parseLiveFieldAvailability(
        field,
        date,
        response(
          slot(
            hour = 18,
            minute = 0,
            """
              {
                "in_season": true,
                "permit_is_for_overlapping_field": false,
                "num_pending_permits": 2,
                "permit_number": 940103,
                "is_issued": false,
                "permit_holder": "Bard High School Early College Manhattan",
                "permit_type": "Sport - Youth"
              }
            """,
          )
        ),
        json,
      )

    assertThat(availability.blocks).hasSize(1)
    assertThat(availability.advisories).isEmpty()
  }

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

  private fun response(vararg slots: Pair<Long, String>): String {
    return buildString {
      append("""{"availability":{""")
      slots.forEachIndexed { index, (epochSeconds, value) ->
        if (index > 0) append(',')
        append('"').append(epochSeconds).append('"').append(':').append(value.trimIndent())
      }
      append("""},"fieldName":"Football-01","close":{"2026-06-02":"21:00"}}""")
    }
  }

  private fun slot(hour: Int, minute: Int, value: String): Pair<Long, String> {
    val epochSeconds = LocalDateTime(date, LocalTime(hour, minute)).toNyInstant().epochSeconds
    return epochSeconds to value
  }
}
