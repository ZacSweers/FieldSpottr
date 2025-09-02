// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.calf.ui.datepicker.AdaptiveDatePicker
import com.mohamedrejeb.calf.ui.datepicker.AdaptiveDatePickerState
import com.mohamedrejeb.calf.ui.datepicker.rememberAdaptiveDatePickerState
import com.mohamedrejeb.calf.ui.sheet.AdaptiveBottomSheet
import com.mohamedrejeb.calf.ui.sheet.rememberAdaptiveSheetState
import dev.zacsweers.fieldspottr.util.AutoMeasureText
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
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
    val (currentSelection, setCurrentSelection) = remember { mutableLongStateOf(current) }
    var hideSheet by remember { mutableStateOf(false) }

    val sheetState =
      rememberAdaptiveSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { value -> value != SheetValue.Expanded },
      )

    // TODO track min/max dates available and limit to those
    val datePickerState = rememberAdaptiveDatePickerState(current)
    AdaptiveBottomSheet(
      onDismissRequest = { showDatePicker = false },
      adaptiveSheetState = sheetState,
      containerColor = DatePickerDefaults.colors().containerColor,
    ) {
      val content = remember {
        movableContentOf {
          DatePickerSheetContent(current, datePickerState, setCurrentSelection) { hideSheet = true }
        }
      }
      if (CurrentPlatform == Platform.Native) {
        // Have to wrap in a filled box to make the background match
        Box(Modifier.fillMaxSize().background(DatePickerDefaults.colors().containerColor)) {
          Column(Modifier.fillMaxWidth()) { content() }
        }
      } else {
        content()
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

  Surface(
    enabled = true,
    onClick = { showDatePicker = true },
    modifier =
      modifier.requiredHeightIn(max = 48.dp).aspectRatio(1f, matchHeightConstraintsFirst = true),
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
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
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
          style = MaterialTheme.typography.labelMedium,
          fontSize = fontSize,
          lineHeight = MaterialTheme.typography.labelMedium.fontSize,
          maxLines = 1,
        )
      }
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

@Suppress("ComposeUnstableReceiver", "UnusedReceiverParameter")
@Composable
private fun ColumnScope.DatePickerSheetContent(
  current: Long,
  datePickerState: AdaptiveDatePickerState,
  updateSelection: (Long) -> Unit,
  modifier: Modifier = Modifier,
  hideSheet: () -> Unit,
) {
  AdaptiveDatePicker(
    datePickerState,
    modifier = modifier.fillMaxWidth(),
    headline = { Text("Select a date", Modifier.padding(start = 16.dp)) },
  )
  Row(Modifier.padding(bottom = 16.dp, end = 16.dp), horizontalArrangement = spacedBy(16.dp)) {
    Spacer(Modifier.weight(1f))
    Button(onClick = hideSheet) { Text("Cancel") }
    val confirmEnabled by remember {
      derivedStateOf { datePickerState.selectedDateMillis != current }
    }
    Button(
      onClick = {
        updateSelection(datePickerState.selectedDateMillis ?: current)
        hideSheet()
      },
      enabled = confirmEnabled,
    ) {
      Text("Confirm")
    }
  }
}

private val ShortMonth = LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED) }

@Composable
internal fun surfaceColorAtElevation(color: Color, elevation: Dp): Color =
  MaterialTheme.colorScheme.applyTonalElevation(color, elevation)

@Composable
@ReadOnlyComposable
internal fun ColorScheme.applyTonalElevation(backgroundColor: Color, elevation: Dp): Color {
  val tonalElevationEnabled = LocalTonalElevationEnabled.current
  return if (backgroundColor == surface && tonalElevationEnabled) {
    surfaceColorAtElevation(elevation)
  } else {
    backgroundColor
  }
}
