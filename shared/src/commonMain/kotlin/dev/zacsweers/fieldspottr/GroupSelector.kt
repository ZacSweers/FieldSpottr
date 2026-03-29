// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.util.TextFieldDropdown

@Composable
fun GroupSelector(
  selectedGroup: String,
  areas: Areas,
  modifier: Modifier = Modifier,
  onGroupSelected: (String) -> Unit,
) {
  Row(
    modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    val selectedArea by
      remember(selectedGroup, areas) {
        val areaName = areas.groups[selectedGroup]?.area
        mutableStateOf(
          areas.entries.firstOrNull { it.areaName == areaName } ?: areas.entries.first()
        )
      }
    val areasList = remember { areas.entries.sortedBy { it.displayName } }
    TextFieldDropdown(
      label = "Area",
      currentValue = selectedArea,
      allValues = areasList,
      modifier = Modifier.weight(1f),
      setValue = { area -> onGroupSelected(area.fieldGroups.first().name) },
      displayName = { it.displayName },
      subtitle = { it.subtitle },
    )
    if (selectedArea.fieldGroups.size > 1) {
      val sortedGroups = remember(selectedArea) { selectedArea.fieldGroups.sortedBy { it.name } }
      val selectedFieldGroup =
        remember(selectedGroup) { areas.groups[selectedGroup] } ?: return
      TextFieldDropdown(
        label = "Field",
        currentValue = selectedFieldGroup,
        allValues = sortedGroups,
        modifier = Modifier.weight(1f),
        setValue = { group -> onGroupSelected(group.name) },
        displayName = { it.name },
        subtitle = { it.closed?.let { reason -> "Closed: $reason" } },
      )
    }
  }
}
