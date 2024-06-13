// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Alignment.Companion.CenterStart
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Alignment.Companion.TopStart
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.EventSection.Single
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.Field
import dev.zacsweers.fieldspottr.data.NYC_TZ
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime

@Composable
fun PermitGrid(
  state: HomeScreen.State,
  modifier: Modifier = Modifier,
  onEventClick: (DbPermit) -> Unit,
) {
  val group = Area.groups.getValue(state.selectedGroup)
  val fields = group.fields
  val numColumns = fields.size + 1
  LazyVerticalGrid(columns = GridCells.Fixed(numColumns), modifier = modifier.padding(16.dp)) {
    items(25 * numColumns) { index ->
      val rowNumber = index / numColumns
      val columnNumber = index % numColumns
      Box(Modifier.aspectRatio(4f / 3)) {
        if (columnNumber == 0 && rowNumber == 0) {
          // Do nothing
        } else if (columnNumber == 0) {
          val adjustedTime = ((rowNumber - 1) % 12).let { if (it == 0) 12 else it }
          val amPm = if (rowNumber <= 12) "AM" else "PM"
          Text(
            "$adjustedTime $amPm",
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(TopStart).fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
          )
          HorizontalDivider(
            modifier = Modifier.align(TopEnd).fillMaxWidth(0.25f),
            thickness = Dp.Hairline,
          )
        } else if (rowNumber == 0) {
          // Name of the field as a header
          Text(
            fields[columnNumber - 1].displayName,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
          )
          HorizontalDivider(modifier = Modifier.align(BottomCenter), thickness = Dp.Hairline)
        } else {
          val event = getEvent(state, fields, columnNumber, rowNumber)
          val eventBefore = getEvent(state, fields, columnNumber, rowNumber - 1)
          val eventAfter = getEvent(state, fields, columnNumber, rowNumber + 1)

          val hasEventBefore = eventBefore != null
          val hasEventAfter = eventAfter != null

          Box {
            event?.let {
              val start =
                remember(event.start) {
                  EventTimeFormatter.format(
                    Instant.fromEpochMilliseconds(event.start).toLocalDateTime(NYC_TZ)
                  )
                }
              val end =
                remember(event.end) {
                  EventTimeFormatter.format(
                    Instant.fromEpochMilliseconds(event.end).toLocalDateTime(NYC_TZ)
                  )
                }
              val section =
                when {
                  !hasEventBefore && !hasEventAfter -> Single
                  !hasEventBefore -> EventSection.Start
                  !hasEventAfter -> EventSection.End
                  else -> EventSection.Middle
                }
              BasicPermitEvent(
                modifier = Modifier.animateItemPlacement(),
                start = start,
                end = end,
                section = section,
                event = it,
                color = Color.Red,
                onEventClick = { onEventClick(event) },
              )
            }
            if (columnNumber == 1 && !hasEventAfter) {
              VerticalDivider(thickness = Dp.Hairline, modifier = Modifier.align(CenterStart))
            }
            VerticalDivider(thickness = Dp.Hairline, modifier = Modifier.align(CenterEnd))
            if (!hasEventAfter) {
              HorizontalDivider(thickness = Dp.Hairline, modifier = Modifier.align(BottomCenter))
            }
          }
        }
      }
    }
  }
}

private fun getEvent(
  state: HomeScreen.State,
  fields: List<Field>,
  columnNumber: Int,
  rowNumber: Int,
): DbPermit? {
  return state.permits?.let {
    it.fields[fields[columnNumber - 1].displayName]?.permits?.let { permits ->
      permits[rowNumber - 1]
    }
  }
}

val EventTimeFormatter =
  LocalDateTime.Format {
    amPmHour(padding = Padding.NONE)
    amPmMarker("am", "pm")
  }

@Composable
fun BasicPermitEvent(
  event: DbPermit,
  color: Color,
  start: String,
  end: String,
  section: EventSection,
  modifier: Modifier = Modifier,
  onEventClick: ((DbPermit) -> Unit)? = null,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(top = section.topPadding, bottom = section.bottomPadding, start = 2.dp, end = 2.dp)
        .clipToBounds()
        .background(
          color,
          shape =
            RoundedCornerShape(
              topStart = section.topCornerPadding,
              topEnd = section.topCornerPadding,
              bottomStart = section.bottomCornerPadding,
              bottomEnd = section.bottomCornerPadding,
            ),
        )
        .padding(2.dp)
        .clickable(enabled = onEventClick != null) { onEventClick!!(event) }
  ) {
    if (section == Single || section == EventSection.Start) {
      Text(
        text = "$start - $end",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Clip,
      )

      Text(
        text = event.name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

enum class EventSection(
  val topCornerPadding: Dp,
  val bottomCornerPadding: Dp,
  val topPadding: Dp,
  val bottomPadding: Dp,
) {
  Single(4.dp, 4.dp, 2.dp, 2.dp),
  Start(4.dp, 0.dp, 2.dp, 0.dp),
  Middle(0.dp, 0.dp, 0.dp, 0.dp),
  End(0.dp, 4.dp, 0.dp, 2.dp),
}
