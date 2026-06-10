// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.ui.WeatherGlyph
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames

enum class GridViewMode {
  DAY,
  WEEK,
}

/**
 * A 7-day availability overview for a field group. Subfields are collapsed into a per-hour 4-state
 * strip per day (all free / some free / booked / closed); tapping a day opens the regular day grid
 * for per-field detail. Cell colors animate so feed refreshes shift softly instead of popping.
 */
@Composable
fun WeekGrid(
  week: WeekAvailability,
  selectedDate: LocalDate,
  modifier: Modifier = Modifier,
  weather: WeatherForecast? = null,
  cornerSlot: (@Composable () -> Unit)? = null,
  onDayClick: (LocalDate) -> Unit,
) {
  Column(modifier.padding(horizontal = 16.dp), verticalArrangement = spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(12.dp)) {
      cornerSlot?.invoke()
      Column {
        Text(
          "Week of ${WeekOfFormat.format(week.startDate)}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          "6am–11pm · tap a day for details",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Row(horizontalArrangement = spacedBy(6.dp)) {
      for (day in week.days) {
        DayColumn(
          day = day,
          isSelected = day.date == selectedDate,
          weather = weather,
          onClick = { onDayClick(day.date) },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = spacedBy(12.dp, alignment = Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      LegendItem(WeekSlotState.ALL_FREE.color(), "All free")
      LegendItem(WeekSlotState.SOME_FREE.color(), "Some free")
      LegendItem(WeekSlotState.BOOKED.color(), "Booked")
      LegendItem(WeekSlotState.CLOSED.color(), "Closed")
    }
  }
}

@Composable
private fun DayColumn(
  day: WeekDayAvailability,
  isSelected: Boolean,
  weather: WeatherForecast?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    shape = MaterialTheme.shapes.medium,
    color =
      if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
      else Color.Transparent,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = spacedBy(4.dp),
    ) {
      Text(
        day.date.dayOfWeek.name.take(3),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        day.date.day.toString(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
      )
      Column(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.small)) {
        for (hourState in day.hourStates) {
          val cellColor by
            animateColorAsState(
              targetValue = hourState.color(),
              animationSpec = tween(300),
              label = "weekCellColor",
            )
          Box(Modifier.fillMaxWidth().weight(1f).background(cellColor))
        }
      }
      val daily = weather?.daily(day.date)
      if (daily != null) {
        WeatherGlyph(
          daily.condition,
          size = 12.dp,
          tint =
            if (daily.condition.isPrecipitation) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
internal fun WeekSlotState.color(): Color {
  return when (this) {
    WeekSlotState.ALL_FREE -> MaterialTheme.colorScheme.secondaryContainer
    WeekSlotState.SOME_FREE -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    WeekSlotState.BOOKED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    WeekSlotState.CLOSED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
  }
}

@Composable
private fun LegendItem(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(4.dp)) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private val WeekOfFormat = LocalDate.Format {
  monthName(MonthNames.ENGLISH_ABBREVIATED)
  chars(" ")
  day()
}
