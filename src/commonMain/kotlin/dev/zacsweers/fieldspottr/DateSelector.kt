// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.datepicker.AdaptiveDatePicker
import com.mohamedrejeb.calf.ui.datepicker.rememberAdaptiveDatePickerState
import com.mohamedrejeb.calf.ui.sheet.AdaptiveBottomSheet
import com.mohamedrejeb.calf.ui.sheet.rememberAdaptiveSheetState
import dev.zacsweers.fieldspottr.util.AdaptiveClickableSurface
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone.Companion.UTC
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.toLocalDateTime

@Composable
fun DateSelector(
  currentlySelectedDate: LocalDate,
  modifier: Modifier = Modifier,
  onDateSelected: (LocalDate) -> Unit,
) {
  var showDatePicker by rememberSaveable { mutableStateOf(false) }

  if (showDatePicker) {
    val current by remember {
      derivedStateOf { currentlySelectedDate.atStartOfDayIn(UTC).toEpochMilliseconds() }
    }
    var currentSelection by remember { mutableLongStateOf(current) }
    var hideSheet by remember { mutableStateOf(false) }

    val sheetState =
      rememberAdaptiveSheetState(
        skipPartiallyExpanded = true,
        // TODO remove in 2.0.10 https://issuetracker.google.com/355061541
        confirmValueChange = remember { { true } },
      )

    // TODO track min/max dates available and limit to those
    val datePickerState = rememberAdaptiveDatePickerState(current)
    AdaptiveBottomSheet(
      onDismissRequest = { showDatePicker = false },
      adaptiveSheetState = sheetState,
    ) {
      AdaptiveDatePicker(
        datePickerState,
        modifier = Modifier.fillMaxWidth(),
        headline = { Text("Select a date", Modifier.padding(start = 16.dp)) },
      )
      Row(Modifier.padding(bottom = 16.dp)) {
        Spacer(Modifier.weight(1f))
        AdaptiveButton(onClick = { hideSheet = true }) { Text("Cancel") }
        Spacer(Modifier.width(4.dp))
        val confirmEnabled by remember {
          derivedStateOf { datePickerState.selectedDateMillis != current }
        }
        AdaptiveButton(
          onClick = {
            currentSelection = datePickerState.selectedDateMillis ?: current
            hideSheet = true
          },
          enabled = confirmEnabled,
        ) {
          Text("Confirm")
        }
      }
    }
    if (hideSheet) {
      LaunchedEffect(Unit) {
        sheetState.hide()
        hideSheet = false
        showDatePicker = false
        val selected =
          currentSelection
            .takeIf { it != current }
            ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(UTC).date }
            ?: currentlySelectedDate
        onDateSelected(selected)
      }
    }
  }

  val today = remember { Clock.System.now().toLocalDateTime(UTC).date }

  // Annoyingly verbose means of getting a long press
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
            showDatePicker = true
          }
        }
        is PressInteraction.Cancel -> {
          isLongClick = false
        }
      }
    }
  }

  AdaptiveClickableSurface(
    clickableEnabled = true,
    onClick = { showDatePicker = true },
    modifier = modifier,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
      horizontalAlignment = CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        ShortMonth.format(currentlySelectedDate),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
        style = MaterialTheme.typography.labelMedium,
        lineHeight = MaterialTheme.typography.labelMedium.fontSize,
      )
      Text(
        currentlySelectedDate.dayOfMonth.toString(),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelLarge,
        lineHeight = MaterialTheme.typography.labelLarge.fontSize,
      )
    }
  }
}

private val ShortMonth = LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED) }
