// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.ContentAlpha
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
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
import io.github.alexzhirkevich.LocalContentColor
import io.github.alexzhirkevich.cupertino.CupertinoSurface
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveHorizontalDivider
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveSurface
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveWidget
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme

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
    AdaptiveSurface {
      Box {
        Row(modifier = Modifier.padding(16.dp)) {
          Spacer(Modifier.weight(TIME_COLUMN_WEIGHT))
          for (columnNumber in 0..<numColumns) {
            Text(
              group.fields[columnNumber].displayName,
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(columnWeight),
              style = MaterialTheme.typography.titleSmall,
            )
          }
        }
        androidx.compose.animation.AnimatedVisibility(
          visible = isScrolled,
          modifier = Modifier.align(BottomCenter),
        ) {
          AdaptiveHorizontalDivider()
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
        val fieldStates = fields[field] ?: FieldState.EMPTY
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
              AdaptiveHorizontalDivider(modifier = Modifier.align(BottomCenter))
            }
          }
        }
      }
    }
  }
}

@Composable
fun PermitEvent(
  event: Reserved,
  modifier: Modifier = Modifier,
  onEventClick: ((Reserved) -> Unit)? = null,
) {
  val isOverlap = event.isOverlap
  val containerColor =
    if (event.isBlocked) {
      MaterialTheme.colorScheme.errorContainer
    } else if (isOverlap) {
      MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    } else {
      MaterialTheme.colorScheme.tertiaryContainer
    }
  AdaptiveClickableSurface(
    clickableEnabled = onEventClick != null && !isOverlap,
    onClick = { onEventClick!!(event) },
    modifier = modifier.fillMaxSize().padding(4.dp).clipToBounds(),
    color = containerColor,
    shape = RoundedCornerShape(4.dp),
  ) {
    val vertArrangement = if (isOverlap) Arrangement.Center else Arrangement.Top
    val horizontalAlignment = if (isOverlap) Alignment.CenterHorizontally else Alignment.Start
    Column(
      modifier = Modifier.fillMaxSize().padding(4.dp),
      verticalArrangement = vertArrangement,
      horizontalAlignment = horizontalAlignment,
    ) {
      val textColor =
        if (event.isBlocked) {
          MaterialTheme.colorScheme.onErrorContainer
        } else {
          MaterialTheme.colorScheme.onTertiaryContainer
        }

      if (!isOverlap) {
        Text(
          text = event.title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          overflow = TextOverflow.Ellipsis,
          color = textColor,
        )
      }

      if (isOverlap) {
        Text(
          text = "Overlapping permit",
          style = MaterialTheme.typography.bodySmall,
          fontStyle = FontStyle.Italic,
          fontWeight = FontWeight.Medium,
          color = LocalContentColor.current.copy(ContentAlpha.high),
          textAlign = TextAlign.Center,
        )
      } else {
        Text(
          text = event.org,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          overflow = TextOverflow.Ellipsis,
          color = textColor.copy(alpha = 0.5f),
        )
      }
    }
  }
}

@Composable
private fun AdaptiveClickableSurface(
  clickableEnabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  color: Color = Color.Unspecified,
  contentColor: Color = Color.Unspecified,
  shadowElevation: Dp = 0.dp,
  content: @Composable () -> Unit,
) {
  if (!clickableEnabled) {
    AdaptiveSurface(modifier, shape, color, contentColor, shadowElevation, content)
  } else {
    AdaptiveWidget(
      material = {
        Surface(
          onClick = onClick,
          modifier = modifier,
          shape = shape,
          color = color.takeOrElse { MaterialTheme.colorScheme.surface },
          contentColor = contentColor.takeOrElse { MaterialTheme.colorScheme.onSurface },
          shadowElevation = shadowElevation,
          content = content,
        )
      },
      cupertino = {
        CupertinoSurface(
          onClick = onClick,
          modifier = modifier,
          shape = shape,
          color = color.takeOrElse { CupertinoTheme.colorScheme.systemBackground },
          contentColor = contentColor.takeOrElse { LocalContentColor.current },
          //        shadowElevation = shadowElevation,
          content = content,
        )
      },
    )
  }
}
