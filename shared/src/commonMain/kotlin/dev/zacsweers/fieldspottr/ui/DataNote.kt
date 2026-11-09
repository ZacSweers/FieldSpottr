// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.ui

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shown on dates without NYC live data, the only source of pending permit requests. */
internal const val PENDING_DATA_NOTE = "Pending permit requests aren't shown for this date"

/** A quiet one-line note about the data behind the current view, with a leading info icon. */
@Composable
internal fun DataNote(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(4.dp),
  ) {
    Icon(
      Icons.Outlined.Info,
      contentDescription = null,
      tint = color,
      modifier = Modifier.size(14.dp),
    )
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
  }
}
