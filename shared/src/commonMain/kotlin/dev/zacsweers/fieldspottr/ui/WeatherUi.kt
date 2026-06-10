// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.data.WeatherCondition
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlinx.datetime.LocalDate

/** A small, flat weather glyph drawn with Canvas (no icon dependencies). */
@Composable
fun WeatherGlyph(
  condition: WeatherCondition,
  modifier: Modifier = Modifier,
  size: Dp = 16.dp,
  tint: Color = LocalContentColor.current,
) {
  Canvas(modifier.size(size)) {
    when (condition) {
      WeatherCondition.CLEAR -> drawSun(tint, center, this.size.minDimension * 0.5f)
      WeatherCondition.PARTLY_CLOUDY -> {
        val s = this.size.minDimension
        drawSun(tint, Offset(s * 0.36f, s * 0.36f), s * 0.30f)
        drawCloud(tint, Rect(s * 0.18f, s * 0.40f, s * 0.98f, s * 0.88f))
      }
      WeatherCondition.CLOUDY,
      WeatherCondition.FOG -> {
        val s = this.size.minDimension
        drawCloud(tint, Rect(s * 0.05f, s * 0.18f, s * 0.95f, s * 0.72f))
        if (condition == WeatherCondition.FOG) {
          val stroke = s * 0.09f
          drawLine(
            tint,
            Offset(s * 0.15f, s * 0.84f),
            Offset(s * 0.85f, s * 0.84f),
            stroke,
            StrokeCap.Round,
          )
          drawLine(
            tint,
            Offset(s * 0.25f, s * 0.97f),
            Offset(s * 0.75f, s * 0.97f),
            stroke,
            StrokeCap.Round,
          )
        }
      }
      WeatherCondition.DRIZZLE,
      WeatherCondition.RAIN -> {
        val s = this.size.minDimension
        drawCloud(tint, Rect(s * 0.05f, s * 0.08f, s * 0.95f, s * 0.62f))
        val drops = if (condition == WeatherCondition.DRIZZLE) 2 else 3
        drawDrops(tint, drops)
      }
      WeatherCondition.SNOW -> {
        val s = this.size.minDimension
        drawCloud(tint, Rect(s * 0.05f, s * 0.08f, s * 0.95f, s * 0.62f))
        val r = s * 0.06f
        drawCircle(tint, r, Offset(s * 0.32f, s * 0.78f))
        drawCircle(tint, r, Offset(s * 0.62f, s * 0.92f))
        drawCircle(tint, r, Offset(s * 0.78f, s * 0.74f))
      }
      WeatherCondition.THUNDERSTORM -> {
        val s = this.size.minDimension
        drawCloud(tint, Rect(s * 0.05f, s * 0.05f, s * 0.95f, s * 0.58f))
        val bolt =
          Path().apply {
            moveTo(s * 0.56f, s * 0.58f)
            lineTo(s * 0.38f, s * 0.82f)
            lineTo(s * 0.52f, s * 0.82f)
            lineTo(s * 0.44f, s * 1.00f)
            lineTo(s * 0.68f, s * 0.74f)
            lineTo(s * 0.54f, s * 0.74f)
            lineTo(s * 0.66f, s * 0.58f)
            close()
          }
        drawPath(bolt, tint)
      }
    }
  }
}

private fun DrawScope.drawSun(tint: Color, center: Offset, radius: Float) {
  val coreRadius = radius * 0.52f
  drawCircle(tint, coreRadius, center)
  val stroke = radius * 0.16f
  for (i in 0 until 8) {
    val angle = i * (PI_F / 4f)
    val dir = Offset(cos(angle), sin(angle))
    drawLine(
      color = tint,
      start = center + dir * (radius * 0.70f),
      end = center + dir * radius,
      strokeWidth = stroke,
      cap = StrokeCap.Round,
    )
  }
}

private fun DrawScope.drawCloud(tint: Color, rect: Rect) {
  val h = rect.height
  val path =
    Path().apply {
      // Base
      addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
          left = rect.left,
          top = rect.bottom - h * 0.55f,
          right = rect.right,
          bottom = rect.bottom,
          radiusX = h * 0.28f,
          radiusY = h * 0.28f,
        )
      )
      // Puffs
      addOval(
        Rect(
          center = Offset(rect.left + rect.width * 0.34f, rect.bottom - h * 0.52f),
          radius = h * 0.30f,
        )
      )
      addOval(
        Rect(
          center = Offset(rect.left + rect.width * 0.64f, rect.bottom - h * 0.60f),
          radius = h * 0.38f,
        )
      )
    }
  drawPath(path, tint)
}

private fun DrawScope.drawDrops(tint: Color, count: Int) {
  val s = size.minDimension
  val stroke = s * 0.09f
  val xs = listOf(0.30f, 0.55f, 0.78f).take(count)
  for ((i, x) in xs.withIndex()) {
    val yOffset = if (i % 2 == 0) 0f else s * 0.08f
    drawLine(
      color = tint,
      start = Offset(s * x, s * 0.72f + yOffset),
      end = Offset(s * (x - 0.07f), s * 0.94f + yOffset),
      strokeWidth = stroke,
      cap = StrokeCap.Round,
    )
  }
}

/** A small water droplet glyph. */
@Composable
fun DropletGlyph(
  modifier: Modifier = Modifier,
  size: Dp = 12.dp,
  tint: Color = LocalContentColor.current,
) {
  Canvas(modifier.size(size)) {
    val s = this.size.minDimension
    val path =
      Path().apply {
        moveTo(s * 0.5f, s * 0.02f)
        cubicTo(s * 0.5f, s * 0.02f, s * 0.10f, s * 0.50f, s * 0.10f, s * 0.68f)
        cubicTo(s * 0.10f, s * 0.92f, s * 0.28f, s * 1.0f, s * 0.5f, s * 1.0f)
        cubicTo(s * 0.72f, s * 1.0f, s * 0.90f, s * 0.92f, s * 0.90f, s * 0.68f)
        cubicTo(s * 0.90f, s * 0.50f, s * 0.5f, s * 0.02f, s * 0.5f, s * 0.02f)
        close()
      }
    drawPath(path, tint, style = Stroke(width = s * 0.12f, cap = StrokeCap.Round))
  }
}

private const val PI_F = 3.1415927f

/**
 * One unobtrusive line of weather: current (or daily) temp + condition on the left, a rain callout
 * on the right when relevant.
 */
@Composable
fun WeatherStrip(
  forecast: WeatherForecast,
  date: LocalDate,
  isToday: Boolean,
  modifier: Modifier = Modifier,
) {
  val daily = forecast.daily(date) ?: return
  val current = forecast.current
  val (temp, condition) =
    if (isToday && current != null) {
      current.tempF to current.condition
    } else {
      daily.highF to daily.condition
    }

  val rainCallout =
    remember(forecast, date, isToday) {
      val fromHour = if (isToday) currentNyHour() else 6
      forecast.nextRainyHour(date, fromHour)?.let { hourly ->
        val timing = if (hourly.hour <= fromHour) "now" else "after ${formatHour12(hourly.hour)}"
        "${hourly.precipProbability}% $timing"
      }
    }

  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
    ) {
      WeatherGlyph(condition, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        text = "$temp° ${condition.label}",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.weight(1f))
      if (rainCallout != null) {
        DropletGlyph(tint = MaterialTheme.colorScheme.tertiary)
        Text(
          text = rainCallout,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
    }
  }
}

internal fun currentNyHour(): Int {
  return Clock.System.now().toEpochMilliseconds().toNyLocalDateTime().hour
}

internal fun formatHour12(hour: Int): String {
  val h12 = ((hour % 12).takeIf { it != 0 } ?: 12)
  val amPm = if (hour < 12 || hour == 24) "am" else "pm"
  return "$h12$amPm"
}
