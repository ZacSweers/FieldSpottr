// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Width of the collapsed Material 3 Expressive navigation rail, before window insets. */
val NavigationRailWidth = 96.dp

/**
 * The tabs in a Material 3 Expressive navigation bar, for compact and medium windows.
 *
 * Medium windows center the items and put each label beside its icon, as Material's flexible
 * navigation bar does in medium windows. The bar pads itself for the bottom inset.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabNavigationBar(
  selectedTab: HomeTab?,
  onSelect: (HomeTab) -> Unit,
  isMediumWidth: Boolean,
  modifier: Modifier = Modifier,
) {
  ShortNavigationBar(
    modifier = modifier,
    arrangement =
      if (isMediumWidth) {
        ShortNavigationBarArrangement.Centered
      } else {
        ShortNavigationBarArrangement.EqualWeight
      },
  ) {
    for (tab in HomeTab.entries) {
      ShortNavigationBarItem(
        selected = tab == selectedTab,
        onClick = { onSelect(tab) },
        icon = { TabIcon(tab, selected = tab == selectedTab) },
        label = { Text(tab.label) },
        iconPosition =
          if (isMediumWidth) {
            NavigationItemIconPosition.Start
          } else {
            NavigationItemIconPosition.Top
          },
      )
    }
  }
}

/**
 * The tabs in a collapsed Material 3 Expressive navigation rail, for wide windows. The rail pads
 * itself for the start and vertical insets.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabNavigationRail(
  selectedTab: HomeTab?,
  onSelect: (HomeTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  WideNavigationRail(
    modifier = modifier,
    windowInsets =
      WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
  ) {
    for (tab in HomeTab.entries) {
      WideNavigationRailItem(
        selected = tab == selectedTab,
        onClick = { onSelect(tab) },
        icon = { TabIcon(tab, selected = tab == selectedTab) },
        label = { Text(tab.label) },
        railExpanded = false,
      )
    }
  }
}

/** Filled icons mark the selected tab, as Material's navigation components recommend. */
@Composable
private fun TabIcon(tab: HomeTab, selected: Boolean) {
  Icon(
    when (tab) {
      HomeTab.FIND_FIELD -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
      HomeTab.AREAS -> if (selected) Icons.Filled.Place else Icons.Outlined.Place
      HomeTab.ABOUT -> if (selected) Icons.Filled.Info else Icons.Outlined.Info
    },
    // The label names the tab
    contentDescription = null,
  )
}
