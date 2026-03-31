// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slack.circuit.overlay.LocalOverlayHost
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Overlay
import com.slack.circuit.sharedelements.SharedTransitionKey
import dev.zacsweers.fieldspottr.util.AutoMeasureText
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone.Companion.UTC
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime

/** Shared element key for a specific DateSelector button ↔ its DatePickerOverlay card. */
data class DateSelectorSharedKey(val id: String) : SharedTransitionKey

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DateSelector(
  currentlySelectedDate: LocalDate,
  modifier: Modifier = Modifier,
  id: String = "default",
  contentScale: Float = 1f,
  permitDateRange: Pair<LocalDate, LocalDate>? = null,
  onDateSelected: (LocalDate) -> Unit,
) = SharedElementTransitionScope {
  val overlayHost = LocalOverlayHost.current
  val scope = rememberCoroutineScope()
  val sharedKey = remember(id) { DateSelectorSharedKey(id) }
  val today = remember { Clock.System.now().toLocalDateTime(UTC).date }

  val yearRange =
    if (permitDateRange != null) {
      permitDateRange.first.year..permitDateRange.second.year
    } else {
      DatePickerDefaults.YearRange
    }

  fun showPicker() {
    scope.launch {
      val result = overlayHost.show(DatePickerOverlay(currentlySelectedDate, yearRange, sharedKey))
      result.date?.let(onDateSelected)
    }
  }

  // Long press to jump to today
  val interactionSource = remember { MutableInteractionSource() }
  val viewConfiguration = LocalViewConfiguration.current
  val haptics = LocalHapticFeedback.current
  LaunchedEffect(interactionSource) {
    var isLongClick = false
    interactionSource.interactions.collectLatest { interaction ->
      when (interaction) {
        is PressInteraction.Press -> {
          isLongClick = false
          delay(viewConfiguration.longPressTimeoutMillis)
          isLongClick = true
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          onDateSelected(today)
        }
        is PressInteraction.Release -> {
          if (!isLongClick) {
            showPicker()
          }
        }
        is PressInteraction.Cancel -> {
          isLongClick = false
        }
      }
    }
  }

  // Shared element only on platforms with Compose-rendered date picker (not iOS native)
  val sharedModifier =
    if (CurrentPlatform != Platform.Native) {
      val overlayScope = requireAnimatedScope(Overlay)
      Modifier.sharedBounds(
        sharedContentState = rememberSharedContentState(sharedKey),
        animatedVisibilityScope = overlayScope,
      )
    } else {
      Modifier
    }
  Surface(
    enabled = true,
    onClick = ::showPicker,
    modifier =
      modifier
        .requiredHeightIn(max = 48.dp)
        .aspectRatio(1f, matchHeightConstraintsFirst = true)
        .then(sharedModifier),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.secondaryContainer,
  ) {
    Column(
      modifier =
        Modifier.padding(vertical = 4.dp, horizontal = 4.dp).graphicsLayer {
          scaleX = contentScale
          scaleY = contentScale
        },
      horizontalAlignment = CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      AutoMeasureText(
        minSize = 11.sp,
        maxSize = MaterialTheme.typography.labelMedium.fontSize,
        textAlign = TextAlign.Center,
      ) { fontSize ->
        Text(
          ShortMonth.format(currentlySelectedDate),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
          style = MaterialTheme.typography.labelMedium,
          fontSize = fontSize,
          lineHeight = MaterialTheme.typography.labelMedium.fontSize,
          maxLines = 1,
        )
      }
      Text(
        currentlySelectedDate.day.toString(),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelLarge,
        lineHeight = MaterialTheme.typography.labelLarge.fontSize,
      )
    }
  }
}

private val ShortMonth = LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED) }
