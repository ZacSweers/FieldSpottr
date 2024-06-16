// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import dev.zacsweers.fieldspottr.PermitState.FieldState
import dev.zacsweers.fieldspottr.PermitState.FieldState.Free
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Area
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.Padding

const val TIME_COLUMN_WEIGHT = 0.15f

@Composable
fun PermitGrid(
  selectedGroup: String,
  permits: PermitState?,
  modifier: Modifier = Modifier,
  onEventClick: (Reserved) -> Unit = {},
) {
  val group = Area.groups.getValue(selectedGroup)
  val numColumns = group.fields.size

  val columnWeight = (1f - TIME_COLUMN_WEIGHT) / numColumns
  val itemHeight = 50.dp

  // Start at the earliest available permit or 9am because permits aren't possible before
  // that anyway
  val scrollState = rememberScrollState()
  val density = LocalDensity.current
  LaunchedEffect(permits) {
    if (permits == null) return@LaunchedEffect
    val earliestPermit =
      permits.fields.values
        .flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
        .minOrNull() ?: 8
    scrollState.animateScrollTo(density.run { (earliestPermit * itemHeight).roundToPx() })
  }
  val isScrolled by remember { derivedStateOf { scrollState.value > 0 } }

  // Names of the fields as a header
  Column(modifier) {
    Surface {
      Box {
        Row(modifier = Modifier.padding(16.dp)) {
          Spacer(Modifier.weight(TIME_COLUMN_WEIGHT))
          for (columnNumber in 0..<numColumns) {
            Text(
              group.fields[columnNumber].displayName,
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

    Row(
      modifier =
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).verticalScroll(scrollState)
    ) {
      // Time column
      Column(Modifier.weight(TIME_COLUMN_WEIGHT)) {
        for (rowNumber in 0..<24) {
          Box(Modifier.height(itemHeight)) {
            // Time marker
            val adjustedTime = ((rowNumber) % 12).let { if (it == 0) 12 else it }
            val amPm = if (rowNumber < 12) "AM" else "PM"
            Text(
              "$adjustedTime $amPm",
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.align(TopEnd).padding(4.dp),
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )
          }
        }
      }

      val fields = permits?.fields ?: PermitState.EMPTY.fields
      for (field in group.fields) {
        val fieldStates = fields[field.name] ?: FieldState.EMPTY
        Column(Modifier.weight(columnWeight)) {
          for (fieldState in fieldStates) {
            val height =
              when (fieldState) {
                Free -> itemHeight
                is Reserved -> itemHeight * fieldState.duration
              }
            Box(Modifier.height(height)) {
              if (fieldState is Reserved) {
                PermitEvent(event = fieldState, onEventClick = { onEventClick(fieldState) })
              }
              HorizontalDivider(thickness = Dp.Hairline, modifier = Modifier.align(BottomCenter))
            }
          }
        }
      }
    }
  }
}

val EventTimeFormatter =
  LocalDateTime.Format {
    amPmHour(padding = Padding.NONE)
    amPmMarker("am", "pm")
  }

@Composable
fun PermitEvent(
  event: Reserved,
  modifier: Modifier = Modifier,
  onEventClick: ((Reserved) -> Unit)? = null,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(top = 2.dp, bottom = 2.dp, start = 2.dp, end = 2.dp)
        .clipToBounds()
        .clickable(enabled = onEventClick != null) { onEventClick!!(event) }
        .background(MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(4.dp))
        .padding(4.dp)
  ) {
    Text(
      text = event.title,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onTertiaryContainer,
    )

    Text(
      text = event.org,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f),
    )
  }
}
