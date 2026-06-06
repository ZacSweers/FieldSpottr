// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.LiveFieldAvailability
import dev.zacsweers.fieldspottr.data.LiveGroupAvailability
import dev.zacsweers.fieldspottr.data.LivePermitAdvisory
import dev.zacsweers.fieldspottr.data.LivePermitBlock
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.time.Clock
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

internal data class PermitData(
  val permits: PermitState,
  val liveAvailability: LiveGroupAvailability?,
)

internal val DbPermit.isAvailabilityOverlay: Boolean
  get() = isOverlap != 0L || advisory != null || type == "advisory"

internal fun List<DbPermit>.availabilityOverlays(
  areas: Areas,
  selectedGroup: String,
): LiveGroupAvailability? {
  val group = areas.groups[selectedGroup] ?: return null
  val areasByName = areas.entries.associateBy { it.areaName }
  val fields =
    mapNotNull { permit ->
        if (!permit.isAvailabilityOverlay) return@mapNotNull null
        val field = areasByName[permit.area]?.fieldMappings?.get(permit.fieldId)
        if (field == null || field.group != group.name) return@mapNotNull null
        field to permit
      }
      .groupBy({ it.first }, { it.second })
      .mapValues { (field, permits) ->
        val blocks = mutableListOf<LivePermitBlock>()
        val advisories = mutableListOf<LivePermitAdvisory>()
        for (permit in permits) {
          val startSlot = permit.start.halfHourSlot()
          val endSlot = permit.end.halfHourSlot()
          if (endSlot <= startSlot) continue
          val advisory = permit.advisory
          if (advisory != null) {
            advisories += LivePermitAdvisory(startSlot, endSlot, advisory)
          } else {
            blocks +=
              LivePermitBlock(
                startSlot = startSlot,
                endSlot = endSlot,
                title = permit.name,
                org = permit.org,
                status = permit.status,
                isOverlap = permit.isOverlap != 0L,
              )
          }
        }
        LiveFieldAvailability(
          field = field,
          blocks = blocks.sortedBy { it.startSlot }.toImmutableList(),
          advisories = advisories.sortedBy { it.startSlot }.toImmutableList(),
        )
      }
      .filterValues { it.blocks.isNotEmpty() || it.advisories.isNotEmpty() }

  if (fields.isEmpty()) return null
  return LiveGroupAvailability(Clock.System.now(), fields.toImmutableMap())
}

private fun Long.halfHourSlot(): Int {
  val time = toNyLocalDateTime()
  return time.hour * 2 + if (time.minute >= 30) 1 else 0
}
