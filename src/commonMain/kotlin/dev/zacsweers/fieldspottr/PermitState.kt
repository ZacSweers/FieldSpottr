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
    ) : FieldState {
      val duration = end - start
    }

    companion object {
      val EMPTY = List(24) { Free }

      fun fromPermits(permits: List<DbPermit>): List<FieldState> {
        if (permits.isEmpty()) {
          return EMPTY
        }

        val sortedPermits = permits.sortedBy { it.start }

        val elements = mutableListOf<FieldState>()
        var currentPermitIndex = 0
        var hour = 0
        while (hour < 24) {
          val permit = sortedPermits[currentPermitIndex]
          val startDateTime = permit.start.toNyLocalDateTime()
          val startHour = startDateTime.hour
          if (startHour == hour) {
            val durationHours = (permit.end - permit.start).milliseconds.inWholeHours.toInt()
            val endTime = startHour + durationHours
            val startTimeString = startDateTime.formatAmPm()
            val endTimeString = permit.end.toNyLocalDateTime().formatAmPm()
            val timeRange = "$startTimeString - $endTimeString"
            elements +=
              Reserved(
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
              )
            hour += durationHours
            if (currentPermitIndex == sortedPermits.lastIndex) {
              // Exhaust and break
              repeat(24 - endTime) { elements += Free }
              break
            } else {
              currentPermitIndex++
            }
          } else {
            // Pad free slots until next permit start
            repeat(startHour - hour) {
              elements += Free
              hour++
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
  }
}
