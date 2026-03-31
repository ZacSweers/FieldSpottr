// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.datepicker.AdaptiveDatePicker
import com.mohamedrejeb.calf.ui.datepicker.rememberAdaptiveDatePickerState
import com.slack.circuit.overlay.AnimatedOverlay
import com.slack.circuit.overlay.OverlayNavigator
import com.slack.circuit.overlay.OverlayTransitionController
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Overlay
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import dev.zacsweers.fieldspottr.util.PlatformBackHandler
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone.Companion.UTC
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Result wrapper since Circuit Overlay results must be non-null. */
data class DatePickerResult(val date: LocalDate?)

/**
 * A Circuit [AnimatedOverlay] that shows a date picker card. Uses shared elements with the
 * DateSelector button for an expanding/collapsing transition.
 */
class DatePickerOverlay(
  private val currentDate: LocalDate,
  private val yearRange: IntRange,
  private val sharedKey: DateSelectorSharedKey,
) :
  AnimatedOverlay<DatePickerResult>(
    enterTransition = fadeIn(tween(200)),
    exitTransition = fadeOut(tween(200)),
  ) {
  @OptIn(ExperimentalSharedTransitionApi::class)
  @Composable
  override fun AnimatedVisibilityScope.AnimatedContent(
    navigator: OverlayNavigator<DatePickerResult>,
    transitionController: OverlayTransitionController,
  ) = SharedElementTransitionScope {
    val current by remember {
      derivedStateOf { currentDate.atStartOfDayIn(UTC).toEpochMilliseconds() }
    }
    val datePickerState = rememberAdaptiveDatePickerState(current, yearRange = yearRange)

    // Auto-confirm when a new date is selected
    LaunchedEffect(datePickerState.selectedDateMillis) {
      val selected = datePickerState.selectedDateMillis
      if (selected != null && selected != current) {
        val newDate = Instant.fromEpochMilliseconds(selected).toLocalDateTime(UTC).date
        navigator.finish(DatePickerResult(newDate))
      }
    }

    PlatformBackHandler { navigator.finish(DatePickerResult(null)) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      // Scrim
      Box(
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) {
            navigator.finish(DatePickerResult(null))
          }
      )
      // Date picker card — shared bounds matching the DateSelector button
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
        shape = MaterialTheme.shapes.extraLarge,
        color = DatePickerDefaults.colors().containerColor,
        shadowElevation = 8.dp,
        modifier = Modifier.padding(horizontal = 24.dp).then(sharedModifier),
      ) {
        Column(Modifier.fillMaxWidth()) {
          AdaptiveDatePicker(
            datePickerState,
            modifier = Modifier.fillMaxWidth(),
            headline = { Text("Select a date", Modifier.padding(start = 16.dp)) },
          )
        }
      }
    }
  }
}
