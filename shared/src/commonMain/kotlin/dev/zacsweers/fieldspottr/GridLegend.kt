// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.LivePermitBlock
import dev.zacsweers.fieldspottr.theme.fsColorScheme

/** What a block in the day grid shows. Each kind has one color pair, used by the grid blocks. */
internal enum class GridBlockKind(val label: String) {
  PERMIT("Permit"),
  PENDING_APPROVAL("Pending approval"),
  PENDING_REQUEST("Pending request"),
  SHARED("Shared field in use"),
  CLOSED("Closed"),
  UNKNOWN("Unknown"),
}

internal val Reserved.gridKind: GridBlockKind
  get() =
    when {
      isUnavailable -> GridBlockKind.UNKNOWN
      isBlocked -> GridBlockKind.CLOSED
      isOverlap -> GridBlockKind.SHARED
      else -> GridBlockKind.PERMIT
    }

internal val LivePermitBlock.gridKind: GridBlockKind
  get() =
    when {
      isOverlap -> GridBlockKind.SHARED
      title == "Pending approval" -> GridBlockKind.PENDING_APPROVAL
      status.startsWith("Issued permit") -> GridBlockKind.PERMIT
      else -> GridBlockKind.CLOSED
    }

@Composable
internal fun GridBlockKind.containerColor(): Color {
  return when (this) {
    GridBlockKind.PERMIT -> MaterialTheme.colorScheme.secondaryContainer
    GridBlockKind.PENDING_APPROVAL -> MaterialTheme.fsColorScheme.pendingContainer
    GridBlockKind.PENDING_REQUEST -> MaterialTheme.colorScheme.tertiaryContainer
    GridBlockKind.SHARED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
    GridBlockKind.CLOSED -> MaterialTheme.colorScheme.errorContainer
    GridBlockKind.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
  }
}

@Composable
internal fun GridBlockKind.contentColor(): Color {
  return when (this) {
    GridBlockKind.PERMIT -> MaterialTheme.colorScheme.onSecondaryContainer
    GridBlockKind.PENDING_APPROVAL -> MaterialTheme.fsColorScheme.onPendingContainer
    GridBlockKind.PENDING_REQUEST -> MaterialTheme.colorScheme.onTertiaryContainer
    GridBlockKind.SHARED -> MaterialTheme.colorScheme.onSurfaceVariant
    GridBlockKind.CLOSED -> MaterialTheme.colorScheme.onErrorContainer
    GridBlockKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
  }
}

/**
 * A color swatch and its label, for the week grid's key. Translucent colors get an outline so they
 * show on any surface.
 */
@Composable
internal fun LegendItem(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = spacedBy(4.dp)) {
    val swatch = Modifier.size(8.dp).background(color, CircleShape)
    Box(
      if (color.alpha < 1f) {
        swatch.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
      } else {
        swatch
      }
    )
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
