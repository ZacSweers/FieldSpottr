// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slack.circuit.overlay.AnimatedOverlay
import com.slack.circuit.overlay.OverlayNavigator
import com.slack.circuit.overlay.OverlayTransitionController
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Overlay
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.ui.WeatherGlyph
import dev.zacsweers.fieldspottr.util.PlatformBackHandler
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Result wrapper since Circuit Overlay results must be non-null. */
data class QuickDatePickerResult(val date: LocalDate? = null, val showFullPicker: Boolean = false)

/**
 * A lightweight 7-day date picker overlay with an optional weather forecast per day. Shows before
 * the system date picker (which is rough on iOS); a "More" cell escapes to the full picker.
 */
class QuickDatePickerOverlay(
  private val currentDate: LocalDate,
  private val today: LocalDate,
  private val weather: WeatherForecast?,
  private val sharedKey: DateSelectorSharedKey,
) :
  AnimatedOverlay<QuickDatePickerResult>(
    enterTransition = fadeIn(tween(200)),
    exitTransition = fadeOut(tween(200)),
  ) {
  @OptIn(ExperimentalSharedTransitionApi::class)
  @Composable
  override fun AnimatedVisibilityScope.AnimatedContent(
    navigator: OverlayNavigator<QuickDatePickerResult>,
    transitionController: OverlayTransitionController,
  ) = SharedElementTransitionScope {
    PlatformBackHandler { navigator.finish(QuickDatePickerResult()) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      // Scrim
      Box(
        Modifier.fillMaxSize()
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) {
            navigator.finish(QuickDatePickerResult())
          }
      )
      // Pure Compose content - morphs from the DateSelector button on all platforms
      val overlayScope = requireAnimatedScope(Overlay)
      val sharedModifier =
        Modifier.sharedBounds(
          sharedContentState = rememberSharedContentState(sharedKey),
          animatedVisibilityScope = overlayScope,
        )
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.padding(horizontal = 24.dp).then(sharedModifier),
      ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = spacedBy(8.dp)) {
          Text(
            "Pick a date",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
          )
          val days = (0 until 7).map { today.plus(it, DateTimeUnit.DAY) }
          for (rowDays in days.plusNull().chunked(4)) {
            Row(horizontalArrangement = spacedBy(8.dp)) {
              for (day in rowDays) {
                if (day == null) {
                  MoreCell(
                    modifier = Modifier.weight(1f),
                    onClick = {
                      navigator.finish(QuickDatePickerResult(showFullPicker = true))
                    },
                  )
                } else {
                  DayCell(
                    date = day,
                    isSelected = day == currentDate,
                    weather = weather,
                    modifier = Modifier.weight(1f),
                    onClick = { navigator.finish(QuickDatePickerResult(date = day)) },
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

/** Appends a null sentinel for the "More" cell. */
private fun List<LocalDate>.plusNull(): List<LocalDate?> = this + listOf(null)

@Composable
private fun DayCell(
  date: LocalDate,
  isSelected: Boolean,
  weather: WeatherForecast?,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val daily = weather?.daily(date)
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    color =
      if (isSelected) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = spacedBy(2.dp),
    ) {
      Text(
        date.dayOfWeek.name.take(3),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        date.day.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      if (daily != null) {
        WeatherGlyph(daily.condition, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
          "${daily.highF}°",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val precip = daily.precipProbability
        if (precip != null && precip >= 30) {
          Text(
            "$precip%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        } else {
          Spacer(Modifier.height(MaterialTheme.typography.labelSmall.lineHeight.value.dp / 2))
        }
      }
    }
  }
}

@Composable
private fun MoreCell(modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = spacedBy(2.dp),
    ) {
      Spacer(Modifier.height(14.dp))
      Icon(
        Icons.Outlined.DateRange,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "More",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
