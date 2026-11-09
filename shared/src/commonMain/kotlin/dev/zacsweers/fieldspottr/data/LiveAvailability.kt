// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/**
 * Live NYC Parks activity for one field group, drawn over the issued-permit grid.
 *
 * The generator fetches the NYC Parks live API and writes these blocks into the availability feeds
 * as overlay rows. The app only reads them from the database (see `availabilityOverlays`). Slots
 * are half-hour indexes from midnight in New York time.
 */
@Immutable
data class LiveGroupAvailability(
  val checkedAt: Instant,
  val fields: ImmutableMap<Field, LiveFieldAvailability>,
)

@Immutable
data class LiveFieldAvailability(
  val field: Field,
  val blocks: ImmutableList<LivePermitBlock>,
  val advisories: ImmutableList<LivePermitAdvisory>,
)

@Immutable
data class LivePermitBlock(
  val startSlot: Int,
  val endSlot: Int,
  val title: String,
  val org: String,
  val status: String,
  val isOverlap: Boolean,
) {
  val durationSlots: Int
    get() = endSlot - startSlot
}

@Immutable
data class LivePermitAdvisory(
  val startSlot: Int,
  val endSlot: Int,
  val message: String,
) {
  val durationSlots: Int
    get() = endSlot - startSlot
}

/**
 * Copies issued live blocks onto sibling fields that share a physical surface, as overlap blocks.
 */
internal fun LiveGroupAvailability.withOverlapsFrom(group: FieldGroup): LiveGroupAvailability {
  val mergedFields =
    group.fields
      .associateWith { field ->
        val direct = fields[field]
        val overlappingBlocks =
          fields
            .filterKeys { other ->
              other != field && other.group == field.group && other.overlapsWith(field)
            }
            .values
            .flatMap { availability -> availability.blocks }
            .filterNot { block -> block.isOverlap }
            .map { block -> block.copy(isOverlap = true) }
            .mergeVisualOverlapBlocks()

        if (direct == null && overlappingBlocks.isEmpty()) {
          null
        } else {
          LiveFieldAvailability(
            field = field,
            blocks =
              (direct?.blocks.orEmpty() + overlappingBlocks).mergeVisualBlocks().toImmutableList(),
            advisories = direct?.advisories.orEmpty().toImmutableList(),
          )
        }
      }
      .mapNotNull { (field, availability) -> availability?.let { field to it } }
      .toMap()
      .toImmutableMap()

  return copy(fields = mergedFields)
}

private fun List<LivePermitBlock>.mergeVisualBlocks(): List<LivePermitBlock> {
  if (isEmpty()) return emptyList()

  val directBlocks = filterNot { it.isOverlap }
  val overlapBlocks = filter {
    it.isOverlap
  }
    .filterNot { overlap -> directBlocks.any { direct -> overlap.overlaps(direct) } }
    .mergeVisualOverlapBlocks()

  return (directBlocks + overlapBlocks).sortedWith(
    compareBy<LivePermitBlock> { it.startSlot }.thenBy { it.endSlot }
  )
}

private fun List<LivePermitBlock>.mergeVisualOverlapBlocks(): List<LivePermitBlock> {
  if (isEmpty()) return emptyList()
  return sortedWith(compareBy<LivePermitBlock> { it.startSlot }.thenBy { it.endSlot }).fold(
    mutableListOf()
  ) { result, block ->
    val previous = result.lastOrNull()
    if (
      previous != null &&
        previous.isOverlap &&
        block.isOverlap &&
        previous.endSlot >= block.startSlot
    ) {
      result[result.lastIndex] = previous.copy(endSlot = maxOf(previous.endSlot, block.endSlot))
    } else {
      result += block
    }
    result
  }
}

private fun LivePermitBlock.overlaps(other: LivePermitBlock): Boolean {
  return startSlot < other.endSlot && endSlot > other.startSlot
}
