// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zacsweers.fieldspottr.data.Area

@Composable
fun GroupSelector(
  selectedGroup: String,
  modifier: Modifier = Modifier,
  onGroupSelected: (String) -> Unit,
) {
  Row(
    modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    var areasExpanded by remember { mutableStateOf(false) }
    val selectedArea by
      remember(selectedGroup) {
        val areaName = Area.groups.getValue(selectedGroup).area
        mutableStateOf(Area.entries.first { it.areaName == areaName })
      }
    ExposedDropdownMenuBox(
      areasExpanded,
      onExpandedChange = { areasExpanded = it },
      modifier = Modifier.weight(1f),
    ) {
      OutlinedTextField(
        value = selectedArea.displayName,
        modifier = Modifier.menuAnchor().fillMaxWidth(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text("Area") },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = areasExpanded) },
        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
      )
      ExposedDropdownMenu(areasExpanded, { areasExpanded = false }) {
        for (area in Area.entries.sortedBy { it.displayName }) {
          DropdownMenuItem(
            text = {
              val isSelected = area == selectedArea
              Text(area.displayName, fontWeight = if (isSelected) FontWeight.Black else null)
            },
            onClick = {
              areasExpanded = false
              onGroupSelected(area.fieldGroups.first().name)
            },
            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
          )
        }
      }
    }
    if (selectedArea.fieldGroups.size > 1) {
      var groupExpanded by remember { mutableStateOf(false) }
      ExposedDropdownMenuBox(
        groupExpanded,
        onExpandedChange = { groupExpanded = it },
        modifier = Modifier.weight(1f),
      ) {
        OutlinedTextField(
          value = selectedGroup,
          modifier = Modifier.menuAnchor(),
          onValueChange = {},
          readOnly = true,
          singleLine = true,
          label = { Text("Field") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupExpanded) },
          colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(groupExpanded, { groupExpanded = false }) {
          for (group in selectedArea.fieldGroups.sortedBy { it.name }) {
            DropdownMenuItem(
              text = {
                Text(
                  group.name,
                  fontWeight = FontWeight.Black.takeIf { group.name == selectedGroup },
                )
              },
              onClick = {
                onGroupSelected(group.name)
                groupExpanded = false
              },
              contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
          }
        }
      }
    }
  }
}
