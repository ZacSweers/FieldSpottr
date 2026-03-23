// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import dev.zacsweers.fieldspottr.PermitState.FieldState
import dev.zacsweers.fieldspottr.PermitState.FieldState.Free
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.util.AutoMeasureText
import dev.zacsweers.fieldspottr.util.ReflowText
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

const val TIME_COLUMN_WEIGHT = 0.15f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PermitGrid(
  selectedGroup: String,
  permits: PermitState?,
  areas: Areas,
  selectedDate: LocalDate,
  modifier: Modifier = Modifier,
  cornerSlot: (@Composable () -> Unit)? = null,
  onEventClick: (fieldName: String, index: Int, Reserved) -> Unit = { _, _, _ -> },
) {
  val group = areas.groups.getValue(selectedGroup)
  val numColumns = group.fields.size

  val columnWeight = (1f - TIME_COLUMN_WEIGHT) / numColumns
  val itemHeight = 50.dp

  // Start at the earliest available permit or 8am
  val density = LocalDensity.current
  val initialEarliestPermit =
    remember(permits) {
      permits
        ?.fields
        ?.values
        ?.flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
        ?.minOrNull() ?: 8
    }

  val initialScrollPx =
    remember(initialEarliestPermit) {
      density.run { (initialEarliestPermit * itemHeight).roundToPx() }
    }

  val scrollState = rememberScrollState(initial = initialScrollPx)
  LaunchedEffect(permits) {
    if (permits == null) return@LaunchedEffect
    val earliestPermit =
      permits.fields.values
        .flatMap { it.filterIsInstance<Reserved>().map(Reserved::start) }
        .minOrNull() ?: 8
    scrollState.animateScrollTo(density.run { (earliestPermit * itemHeight).roundToPx() })
  }
  val isScrolled by remember { derivedStateOf { scrollState.value > 0 } }

  Column(modifier) {
    // Names of the fields as a header
    Surface {
      Box {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
          verticalAlignment = CenterVertically,
        ) {
          if (cornerSlot == null) {
            Spacer(Modifier.weight(TIME_COLUMN_WEIGHT))
          } else {
            Box(Modifier.weight(TIME_COLUMN_WEIGHT)) { cornerSlot() }
          }
          for (columnNumber in 0..<numColumns) {
            val defaultTextStyle = MaterialTheme.typography.titleMedium
            val textAlign = TextAlign.Center
            AutoMeasureText(
              modifier = Modifier.weight(columnWeight).fillMaxWidth(),
              minSize = 12.sp,
              maxSize = defaultTextStyle.fontSize,
              textAlign = textAlign,
            ) { fontSize ->
              Text(
                group.fields[columnNumber].displayName,
                textAlign = textAlign,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                style = defaultTextStyle,
                fontSize = fontSize,
              )
            }
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
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
          .verticalScroll(scrollState)
          .nowIndicator(selectedDate, itemHeight)
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
          var reservedIndex = 0
          for (fieldState in fieldStates) {
            val height =
              when (fieldState) {
                Free -> itemHeight
                is Reserved -> itemHeight * fieldState.duration
              }
            Box(Modifier.height(height)) {
              if (fieldState is Reserved) {
                val currentIndex = reservedIndex
                key(permits) {
                  SharedElementTransitionScope {
                    val skipEntryAnimation = isTransitionActive
                    val staggerDelay = currentIndex * 30L
                    val animProgress = remember { Animatable(if (skipEntryAnimation) 1f else 0f) }
                    LaunchedEffect(Unit) {
                      if (skipEntryAnimation) return@LaunchedEffect
                      delay(staggerDelay)
                      animProgress.animateTo(1f, tween(300))
                    }
                    PermitEvent(
                      fieldName = field.displayName,
                      index = currentIndex,
                      event = fieldState,
                      modifier =
                        Modifier.graphicsLayer {
                          alpha = animProgress.value
                          translationY = (1f - animProgress.value) * 12f
                        },
                      onEventClick = { onEventClick(field.displayName, currentIndex, fieldState) },
                    )
                  }
                }
                reservedIndex++
              }
              HorizontalDivider(modifier = Modifier.align(BottomCenter))
            }
          }
        }
      }
    }
  }
}

@Composable
private inline fun <T> withDensity(block: Density.() -> T): T {
  val density = LocalDensity.current
  return with(density) { block() }
}

/** Draws a dashed "now" indicator line at the current time, animated in left-to-right. */
@Composable
private fun Modifier.nowIndicator(selectedDate: LocalDate, itemHeight: Dp): Modifier {
  val now = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()) }
  val isToday = selectedDate == now.date
  if (!isToday) return this

  val nowOffsetPx = withDensity { ((now.hour + now.minute / 60f) * itemHeight).toPx() }
  val lineColor = MaterialTheme.colorScheme.tertiaryContainer
  val strokePx = withDensity { 2.dp.toPx() }
  val dashPx = withDensity { 6.dp.toPx() }
  val gapPx = withDensity { 4.dp.toPx() }

  var previousDate by remember { mutableStateOf(selectedDate) }
  val progress = remember { Animatable(if (isToday) 1f else 0f) }
  LaunchedEffect(selectedDate) {
    val dateChanged = selectedDate != previousDate
    previousDate = selectedDate
    if (dateChanged) {
      progress.snapTo(0f)
      delay(150L)
      progress.animateTo(1f, tween(400))
    } else {
      progress.snapTo(1f)
    }
  }

  return drawWithContent {
    drawContent()
    val endX = size.width * progress.value
    drawLine(
      color = lineColor,
      start = Offset(0f, nowOffsetPx),
      end = Offset(endX, nowOffsetPx),
      strokeWidth = strokePx,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx)),
    )
  }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PermitEvent(
  fieldName: String,
  index: Int,
  event: Reserved,
  modifier: Modifier = Modifier,
  onEventClick: ((Reserved) -> Unit)? = null,
) = SharedElementTransitionScope {
  val isOverlap = event.isOverlap
  val containerColor =
    if (event.isBlocked) {
      MaterialTheme.colorScheme.errorContainer
    } else if (isOverlap) {
      MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    }
  val isClickable = onEventClick != null && !isOverlap && !event.isBlocked
  val itemShape = MaterialTheme.shapes.large
  val sharedBoundsModifier =
    if (isClickable) {
      val sharedBoundsKey =
        PermitSharedElementKey(
          fieldName,
          index,
          event.title,
          event.timeRange,
          event.org,
          isOverlap = isOverlap,
        )
      Modifier.sharedBounds(
        sharedContentState = rememberSharedContentState(sharedBoundsKey),
        animatedVisibilityScope = requireAnimatedScope(Navigation),
        clipInOverlayDuringTransition = OverlayClip(itemShape),
      )
    } else {
      Modifier
    }
  Surface(
    enabled = isClickable,
    onClick = { onEventClick!!(event) },
    modifier = modifier.fillMaxSize().padding(4.dp).then(sharedBoundsModifier),
    color = containerColor,
    shape = itemShape,
  ) {
    if (isOverlap) return@Surface
    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
      val textColor =
        if (event.isBlocked) {
          MaterialTheme.colorScheme.onErrorContainer
        } else {
          MaterialTheme.colorScheme.onSecondaryContainer
        }

      ReflowText(
        text = event.title,
        sharedElementKey = if (isClickable) "permit-${fieldName}-${index}-title" else null,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
        color = textColor,
      )

      var maxOrgLines by remember { mutableIntStateOf(3) }
      if (maxOrgLines > 0) {
        Text(
          text = event.org,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          overflow = TextOverflow.Ellipsis,
          color = textColor.copy(alpha = 0.5f),
          maxLines = maxOrgLines,
          onTextLayout = {
            if (it.didOverflowHeight && CurrentPlatform != Platform.Android) {
              maxOrgLines--
            }
          },
        )
      }
    }
  }
}
