// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.fieldspottr.data.Area

@Composable
fun GroupSelector2(
  selectedGroup: String,
  modifier: Modifier = Modifier,
  onGroupSelected: (String) -> Unit,
) {
  val groups by remember {
    mutableStateOf(Area.groups.keys.sorted())
  }
  val selectedIndex by remember(selectedGroup) {
    derivedStateOf { groups.indexOf(selectedGroup) }
  }
  PrimaryScrollableTabRow(
    selectedTabIndex = selectedIndex,
    edgePadding = 12.dp,
    modifier = modifier,
    divider = { },
    indicator = { tabPositions ->
      Box(
        Modifier
          .tabIndicatorOffset(tabPositions[selectedIndex])
          .fillMaxSize()
          .padding(horizontal = 2.dp)
          .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp))
      )
    },
  ) {
    for ((i, group) in groups.withIndex()) {
      GroupTabText(
        group,
        selected = i == selectedIndex,
        onTabSelected = { onGroupSelected(group) }
      )
    }
  }
}

private val textModifier = Modifier
  .padding(vertical = 6.dp, horizontal = 4.dp)

val SmallHeadingStyle = TextStyle(
  fontSize = 16.sp,
  fontWeight = FontWeight(600),
  letterSpacing = 0.5.sp,
)

@Composable
private fun GroupTabText(
  group: String,
  selected: Boolean,
  onTabSelected: () -> Unit,
) {
  Tab(
    modifier = Modifier
      .padding(horizontal = 2.dp)
      .clip(RoundedCornerShape(16.dp)),
    selected = selected,
    onClick = onTabSelected,
  ) {
    Text(
      modifier = textModifier,
      text = group,
      style = SmallHeadingStyle
    )
  }
}
