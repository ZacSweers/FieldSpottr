// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

private const val DRAG_DISMISS_THRESHOLD = 0.4f
private const val MIN_SCALE = 0.85f
private const val DRAG_DIVISOR = 1000f

/**
 * A composable container that adds drag-to-dismiss behavior, scaling content down as the user drags
 * and dismissing when a threshold is reached. Inspired by the DragDismissLayout from
 * nickbutcher/plaid.
 *
 * Combines [draggable] (for direct drag on non-scrollable areas like headers) with [nestedScroll]
 * (to capture overscroll from inner scrollable content like LazyColumn).
 * - Dragging down scales content from 1.0 down to [MIN_SCALE].
 * - Releasing past [DRAG_DISMISS_THRESHOLD] triggers [onDismiss].
 * - Releasing before the threshold springs the scale back to 1.0.
 */
@Composable
fun DragToDismiss(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var dragProgress by remember { mutableFloatStateOf(0f) }
  val dragDismissAnim = remember { Animatable(0f) }
  val currentDragProgress = if (dragDismissAnim.isRunning) dragDismissAnim.value else dragProgress
  val scale = 1f - (currentDragProgress * (1f - MIN_SCALE))

  fun settle() {
    if (dragProgress > DRAG_DISMISS_THRESHOLD) {
      onDismiss()
    } else if (dragProgress > 0f) {
      scope.launch {
        dragDismissAnim.snapTo(dragProgress)
        dragProgress = 0f
        dragDismissAnim.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
      }
    }
  }

  // Direct drag for non-scrollable areas (header, toolbar, etc.)
  val draggableState = rememberDraggableState { delta ->
    dragProgress = (dragProgress + delta / DRAG_DIVISOR).coerceIn(0f, 1f)
  }

  // Nested scroll to capture overscroll from inner scrollable children
  val nestedScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If already dragging to dismiss and user scrolls up, reduce drag first
        if (dragProgress > 0f && available.y < 0f) {
          val consumed = available.y / DRAG_DIVISOR
          dragProgress = (dragProgress + consumed).coerceAtLeast(0f)
          return Offset(0f, available.y)
        }
        return Offset.Zero
      }

      override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
      ): Offset {
        // Inner list is at the top, leftover downward scroll drives dismiss.
        // Only when the inner list didn't consume anything in this axis — this
        // means the list was already at the top, not that it just reached the top
        // mid-fling. Also ignore fling-sourced overscroll entirely.
        if (
          available.y > 0f &&
          consumed.y == 0f &&
          source == NestedScrollSource.UserInput
        ) {
          dragProgress = (dragProgress + available.y / DRAG_DIVISOR).coerceIn(0f, 1f)
          return Offset(0f, available.y)
        }
        return Offset.Zero
      }

      override suspend fun onPreFling(available: Velocity): Velocity {
        // Only consume velocity when actively dragging to dismiss.
        // Otherwise, let the inner scrollable content handle the fling.
        if (dragProgress > 0f) {
          settle()
          return available
        }
        return Velocity.Zero
      }
    }
  }

  Box(
    modifier =
      modifier
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .nestedScroll(nestedScrollConnection)
        .draggable(
          state = draggableState,
          orientation = Orientation.Vertical,
          onDragStopped = { settle() },
        )
  ) {
    content()
  }
}
