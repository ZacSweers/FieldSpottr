// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.Top
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
  val numColumns = fields.size
  val timeColumnWeight = 0.15f
  val columnWeight = (1f - timeColumnWeight) / numColumns
  // Start at the earliest available permit or 9am because permits aren't possible before that
  // anyway
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = 8)
  LaunchedEffect(state.permits) {
    if (state.permits == null) return@LaunchedEffect
    val earliestPermit = state.permits.fields.values.flatMap { it.permits.keys }.minOrNull() ?: 8
    listState.scrollToItem(earliestPermit, 0)
  }
  val isScrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

  // Names of the fields as a header
  Column(modifier) {
    Surface {
      Box {
        Row(modifier = Modifier.padding(16.dp)) {
          Spacer(Modifier.weight(timeColumnWeight))
          for (columnNumber in 0..<numColumns) {
            Text(
              fields[columnNumber].displayName,
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(columnWeight),
            )
          }
        }
        androidx.compose.animation.AnimatedVisibility(
          visible = isScrolled,
          modifier = Modifier.align(BottomCenter),
        ) {
          HorizontalDivider()
        }
      }
    }
    LazyColumn(modifier = Modifier.padding(16.dp), state = listState) {
      for (rowNumber in 0..<24) {
        item(key = rowNumber) {
          Row(Modifier.animateItemPlacement()) {
            // Time marker
            val adjustedTime = ((rowNumber) % 12).let { if (it == 0) 12 else it }
            val amPm = if (rowNumber < 12) "AM" else "PM"
            Text(
              "$adjustedTime $amPm",
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.align(Top).weight(timeColumnWeight).padding(4.dp),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )

            for (columnNumber in 0..<numColumns) {
              val event = getEvent(state, fields, columnNumber, rowNumber)
              val eventBefore = getEvent(state, fields, columnNumber, rowNumber - 1)
              val eventAfter = getEvent(state, fields, columnNumber, rowNumber + 1)

              val hasEventBefore = eventBefore != null && eventBefore == event
              val hasEventAfter = eventAfter != null && eventAfter == event

              Box(Modifier.aspectRatio(4f / 2).weight(columnWeight)) {
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
                  PermitEvent(
                    modifier = Modifier.animateItemPlacement(),
                    start = start,
                    end = end,
                    section = section,
                    event = it,
                    onEventClick = { onEventClick(event) },
                  )
                }
                if (!hasEventAfter) {
                  HorizontalDivider(
                    thickness = Dp.Hairline,
                    modifier = Modifier.align(BottomCenter),
                  )
                }
              }
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
    it.fields[fields[columnNumber].name]?.permits?.let { permits -> permits[rowNumber] }
  }
}

val EventTimeFormatter =
  LocalDateTime.Format {
    amPmHour(padding = Padding.NONE)
    amPmMarker("am", "pm")
  }

@Composable
fun PermitEvent(
  event: DbPermit,
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
          MaterialTheme.colorScheme.tertiaryContainer,
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
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )

      Text(
        text = event.name,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
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
