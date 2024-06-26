// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.zacsweers.fieldspottr.util.Wigglable
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone.Companion.UTC
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

@Composable
fun DateSelector(
  currentDate: LocalDate,
  modifier: Modifier = Modifier,
  onDateSelected: (LocalDate) -> Unit,
) {
  var showDatePicker by rememberSaveable { mutableStateOf(false) }
  if (showDatePicker) {
    val current = currentDate.atStartOfDayIn(UTC).toEpochMilliseconds()
    val datePickerState = rememberDatePickerState(current)
    val confirmEnabled by remember { derivedStateOf { datePickerState.selectedDateMillis != null } }

    DatePickerDialog(
      modifier = modifier,
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            showDatePicker = false
            val selected =
              Instant.fromEpochMilliseconds(datePickerState.selectedDateMillis!!)
                .toLocalDateTime(UTC)
                .date
            onDateSelected(selected)
          },
          enabled = confirmEnabled,
        ) {
          Text("Confirm")
        }
      },
      dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
    ) {
      DatePicker(datePickerState)
    }
  }
  val currentDate by rememberUpdatedState(currentDate)
  Wigglable(currentDate) {
    ExtendedFloatingActionButton(
      onClick = { showDatePicker = true },
      text = { Text(currentDate.toString()) },
      icon = { Icon(Icons.Default.DateRange, contentDescription = "Select date") },
    )
  }
}
