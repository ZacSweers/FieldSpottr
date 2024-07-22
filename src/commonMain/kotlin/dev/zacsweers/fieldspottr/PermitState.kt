// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.zacsweers.fieldspottr.PermitState.FieldState.Companion.padFreeSlots
import dev.zacsweers.fieldspottr.PermitState.FieldState.Companion.withOverlapsFrom
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.Field
import dev.zacsweers.fieldspottr.util.formatAmPm
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

@Stable
data class PermitState(val fields: Map<Field, List<FieldState>>) {
  @Immutable
  sealed interface FieldState {
    data object Free : FieldState

    data class Reserved(
      val start: Int,
      val end: Int,
      val timeRange: String,
      val title: String,
      val org: String,
      val status: String,
      val description: String,
      /** Indicates this is a city permit block. Field is likely unusable. */
      val isBlocked: Boolean,
      /** If true, indicates this is blocked because it overlaps with another permit on the same field. */
      val isOverlap: Boolean,
    ) : FieldState {
      val duration = end - start
    }

    companion object {
      val EMPTY = List(24) { Free }

      fun fromPermits(permits: List<DbPermit>): List<Reserved> {
        if (permits.isEmpty()) {
          return emptyList()
        }

        return permits.map { it.toReserved() }
      }

      private fun DbPermit.toReserved(): Reserved {
        val permit = this
        val startDateTime = permit.start.toNyLocalDateTime()
        val startHour = startDateTime.hour
        val durationHours = (permit.end - permit.start).milliseconds.inWholeHours.toInt()
        val endTime = startHour + durationHours
        val startTimeString = startDateTime.formatAmPm()
        val endTimeString = permit.end.toNyLocalDateTime().formatAmPm()
        val timeRange = "$startTimeString - $endTimeString"
        return Reserved(
          start = startHour,
          end = endTime,
          timeRange = timeRange,
          title = permit.name,
          org = permit.org,
          status = permit.status,
          description =
            """
            $timeRange
            Org: ${permit.org}
            Status: ${permit.status}
          """
              .trimIndent(),
          isBlocked = permit.isBlocked,
          isOverlap = false,
        )
      }

      fun List<Reserved>.withOverlapsFrom(
        field: Field,
        fields: Map<Field, List<Reserved>>
      ): List<Reserved> {
        val allOverlappingFieldPermits = fields.filterKeys {
          it != field &&
            it.group == field.group &&
            it.overlapsWith(field)
        }
          .flatMap { it.value }
          .sortedBy { it.start }
          .ifEmpty { return this@withOverlapsFrom }

        // return a new list with the original fields + merged in overlapping fields that don't overlap with any current elements
        var currentPermitsIndex = 0
        var currentOverlappingPermitsIndex = 0
        val newPermits = mutableListOf<Reserved>()
        while (currentPermitsIndex != size || currentOverlappingPermitsIndex != allOverlappingFieldPermits.size) {
          if (currentOverlappingPermitsIndex == allOverlappingFieldPermits.size) {
            // Fill the remaining current permits and break
            newPermits += drop(currentPermitsIndex)
            break
          } else if (currentPermitsIndex == size) {
            // Fill the remaining overlapping permits and break
            newPermits += allOverlappingFieldPermits.drop(currentOverlappingPermitsIndex)
              .map { it.copy(isOverlap = true) }
            break
          }
          val currentPermit = this[currentPermitsIndex]
          val currentOverlappingPermit = allOverlappingFieldPermits[currentOverlappingPermitsIndex]
          if (currentPermit.start <= currentOverlappingPermit.start) {
            // Add the current permit and increment
            newPermits += currentPermit
            currentPermitsIndex++
            if (currentPermit.end <= currentOverlappingPermit.start) {
              continue
            } else {
              // These permits overlap, ignore the current overlapping permit and increment
              currentOverlappingPermitsIndex++
            }
          } else {
            currentOverlappingPermitsIndex++
            // Next overlap is next
            if (currentOverlappingPermit.end <= currentPermit.start) {
              // This fits completely in, add it
              newPermits += currentOverlappingPermit.copy(isOverlap = true)
              break
            } else {
              // These permits overlap, ignore the current overlapping permit and increment
              break
            }
          }
        }

        // Validation
        check(newPermits.containsAll(this)) {
          """
            New merged permits don't contain all the original permits!
            
            Original: ${this.joinToString()}
            New: ${newPermits.joinToString()}
          """.trimIndent()
        }

        return newPermits
      }

      /** Given an input sequence of reserved permits, pad the [Free] slots between them. */
      fun List<Reserved>.padFreeSlots(): List<FieldState> {
        if (isEmpty()) return EMPTY

        // Don't use Sequence.sortedBy to avoid unnecessary intermediate list
        val sortedPermits = toMutableList().apply { sortBy { it.start } }

        val elements = mutableListOf<FieldState>()
        var currentPermitIndex = 0
        var hour = 0
        while (hour < 24) {
          val permit = sortedPermits[currentPermitIndex]
          when {
            permit.start == hour -> {
              elements += permit
              hour += permit.duration
              if (currentPermitIndex == sortedPermits.lastIndex) {
                // Exhaust and break
                repeat(24 - permit.end) { elements += Free }
                break
              } else {
                currentPermitIndex++
              }
            }
            permit.start > hour -> {
              // Pad free slots until next permit start
              repeat(permit.start - hour) {
                elements += Free
                hour++
              }
            }
            else -> {
              // Overlapping permits. Unclear how this happens tbh, probably a mistake on their
              // side. Skip it.
              println(
                """
                  Overlapping permits:
                  - Previous: ${sortedPermits[currentPermitIndex - 1].run { "$timeRange: $title" }}
                  - Next:  ${permit.timeRange}: ${permit.title}
                """
                  .trimIndent()
              )
              currentPermitIndex++
            }
          }
        }
        return elements
      }
    }
  }

  companion object {
    val EMPTY = fromPermits(emptyList())

    fun fromPermits(dbPermits: List<DbPermit>): PermitState {
      if (dbPermits.isEmpty()) return PermitState(emptyMap())

      val areasByName = Area.entries.associateBy { it.areaName }
      // TODO
      //  get the group ID, get fields for each group, show those too
      val fields =
        dbPermits
          .groupBy { areasByName.getValue(it.area).fieldMappings.getValue(it.fieldId) }
          .mapValues { (_, permits) -> FieldState.fromPermits(permits) }
          .let { permitsByField ->
            if (permitsByField.isEmpty()) return EMPTY

            // Because only one field may have any permits, we still need to load all available
            // fields to show here so that we can show any overlapping permits
            val allFieldsInGroup = Area.groups.getValue(permitsByField.keys.first().group).fields
            val fullMap = buildMap {
              for (field in allFieldsInGroup) {
                put(field, permitsByField[field].orEmpty())
              }
            }

            buildMap {
              for ((field, permits) in fullMap) {
                put(field, permits.withOverlapsFrom(field, fullMap).padFreeSlots())
              }
            }
          }

      return PermitState(fields)
    }

    val DbPermit.isBlocked: Boolean
      get() = name == "Permit Block" && org == "NYC Parks and Recreation"
  }
}
