// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import dev.zacsweers.fieldspottr.di.FSGraph
import dev.zacsweers.fieldspottr.theme.secondaryContainerDark
import dev.zacsweers.fieldspottr.theme.secondaryLight
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UITabBarController
import platform.UIKit.UITabBarItem
import platform.UIKit.UITraitCollection
import platform.UIKit.UIUserInterfaceSizeClassCompact
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.colorWithDynamicProvider
import platform.UIKit.tabBarItem
import platform.UIKit.traitOverrides

/**
 * The app's root view controller: a native tab bar with one Compose root per [HomeTab].
 *
 * Each tab keeps its own back stack, as iOS tabs do, so switching tabs never pops a tab's screens.
 * On iOS 26 and later, UIKit draws the bar as a floating Liquid Glass bar over the content. The bar
 * is part of each root's safe area, which Compose reads as window insets, so content can draw
 * behind it and pad for it.
 */
fun makeUiViewController(graph: FSGraph): UIViewController {
  val tabs =
    HomeTab.entries.map { tab ->
      ComposeUIViewController {
        graph.fsApp(onRootPop = {}, showsTabBar = false, rootScreen = HomeScreen(tab = tab))
      }
        .apply {
          tabBarItem =
            UITabBarItem(
              title = tab.label,
              image = UIImage.systemImageNamed(tab.sfSymbolName),
              tag = tab.ordinal.toLong(),
            )
        }
    }
  return UITabBarController().apply {
    setViewControllers(tabs, animated = false)
    // The selected tab takes the view's tint, which is otherwise the system blue
    view.tintColor = TabTintColor
    // iPadOS puts the tab bar at the top in regular widths. A compact width keeps it floating at
    // the bottom, as on iPhone and in the app's Android bar. Compose lays out from the window
    // size, not this size class, so the screens don't change.
    traitOverrides.horizontalSizeClass = UIUserInterfaceSizeClassCompact
  }
}

/**
 * The selected tab's color: the theme's green accent, as Android's navigation bar uses. Dark mode
 * uses the lighter green, which reads on the dark bar.
 */
private val TabTintColor: UIColor =
  dynamicColor(light = secondaryLight, dark = secondaryContainerDark)

private fun dynamicColor(light: Color, dark: Color): UIColor =
  UIColor.colorWithDynamicProvider { traits: UITraitCollection? ->
    if (traits?.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark) {
      dark.toUIColor()
    } else {
      light.toUIColor()
    }
  }

private fun Color.toUIColor(): UIColor =
  UIColor.colorWithRed(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble(),
  )

/**
 * The SF Symbol for each tab, matching the Material icon other platforms show. Apple's tab bar
 * guidance prefers filled symbols. Search has no filled variant.
 */
private val HomeTab.sfSymbolName: String
  get() =
    when (this) {
      HomeTab.FIND_FIELD -> "magnifyingglass"
      HomeTab.AREAS -> "mappin.circle.fill"
      HomeTab.ABOUT -> "info.circle.fill"
    }
