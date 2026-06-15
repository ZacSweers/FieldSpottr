// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private const val DRAG_THRESHOLD = 100f

@Stable
class DaySwipeState internal constructor(internal val pulse: Animatable<Float, *>) {
  /** Scale factor for the DateSelector - shrinks during drag, springs back on release. */
  val contentScale: Float
    get() = pulse.value

  internal var dragOffset by mutableFloatStateOf(0f)
  internal var thresholdDirection by mutableIntStateOf(0)
}

@Composable
fun rememberDaySwipeState(): DaySwipeState {
  return remember { DaySwipeState(Animatable(1f)) }
}

/**
 * Modifier that adds horizontal swipe-to-change-day behavior. Swiping right goes back one day,
 * swiping left goes forward one day.
 */
@Composable
fun Modifier.daySwipeable(
  state: DaySwipeState,
  currentDate: LocalDate,
  onDateChanged: (LocalDate) -> Unit,
): Modifier {
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current

  return this.draggable(
    state =
      rememberDraggableState { delta ->
        state.dragOffset += delta
        val direction =
          when {
            state.dragOffset > DRAG_THRESHOLD -> -1
            state.dragOffset < -DRAG_THRESHOLD -> 1
            else -> 0
          }
        if (direction != 0 && direction != state.thresholdDirection) {
          state.thresholdDirection = direction
          haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
      },
    orientation = Orientation.Horizontal,
    onDragStarted = {
      state.dragOffset = 0f
      state.thresholdDirection = 0
      haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
      scope.launch { state.pulse.animateTo(0.8f, tween(150)) }
    },
    onDragStopped = {
      if (state.dragOffset > DRAG_THRESHOLD) {
        onDateChanged(currentDate.minus(1, DateTimeUnit.DAY))
      } else if (state.dragOffset < -DRAG_THRESHOLD) {
        onDateChanged(currentDate.plus(1, DateTimeUnit.DAY))
      }
      state.dragOffset = 0f
      state.thresholdDirection = 0
      scope.launch {
        state.pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
      }
    },
  )
}
