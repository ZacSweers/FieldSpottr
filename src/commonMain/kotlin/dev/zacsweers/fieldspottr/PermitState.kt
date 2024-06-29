// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.util.formatAmPm
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

@Stable
data class PermitState(val fields: Map<String, List<FieldState>>) {
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
    ) : FieldState {
      val duration = end - start
    }

    companion object {
      val EMPTY = List(24) { Free }

      fun fromPermits(permits: List<DbPermit>): List<FieldState> {
        if (permits.isEmpty()) {
          return EMPTY
        }

        return permits.asSequence().map { it.toReserved() }.padFreeSlots()
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
        )
      }

      /** Given an input sequence of reserved permits, pad the [Free] slots between them. */
      fun Sequence<Reserved>.padFreeSlots(): List<FieldState> {
        // Don't use Sequence.sortedBy to avoid unnecessary intermediate list
        val sortedPermits = toMutableList().apply { sortBy { it.start } }

        if (sortedPermits.isEmpty()) return EMPTY

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

    fun fromPermits(permits: List<DbPermit>): PermitState {
      val areasByName = Area.entries.associateBy { it.areaName }
      val fields =
        permits
          .groupBy { areasByName.getValue(it.area).fieldMappings.getValue(it.fieldId) }
          .mapKeys { it.key.name }
          .mapValues { (_, permits) -> FieldState.fromPermits(permits) }
      return PermitState(fields)
    }

    val DbPermit.isBlocked: Boolean
      get() = name == "Permit Block" && org == "NYC Parks and Recreation"
  }
}
