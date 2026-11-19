// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.animation.AnimatedNavDecoration
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuit.overlay.rememberOverlayHost
import com.slack.circuit.retained.ExperimentalCircuitRetainedApi
import com.slack.circuit.retained.RetainedValuesStoreProvider
import com.slack.circuit.runtime.ExperimentalCircuitApi
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.popUntil
import com.slack.circuit.sharedelements.SharedElementTransitionLayout
import com.slack.circuitx.gesturenavigation.GestureNavigationDecorationFactory
import dev.zacsweers.fieldspottr.panes.LocalPaneHost
import dev.zacsweers.fieldspottr.panes.LocalPaneWindowSize
import dev.zacsweers.fieldspottr.panes.LocalPaneWindowStart
import dev.zacsweers.fieldspottr.panes.PaneAnimatedNavDecoratorFactory
import dev.zacsweers.fieldspottr.panes.PaneAwareNavigator
import dev.zacsweers.fieldspottr.panes.PaneHost
import dev.zacsweers.fieldspottr.panes.PaneNavDecoration
import dev.zacsweers.fieldspottr.panes.isExpandedWindow
import dev.zacsweers.fieldspottr.theme.FSTheme
import dev.zacsweers.fieldspottr.ui.LocalBottomBarPadding
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(
  ExperimentalSharedTransitionApi::class,
  ExperimentalCircuitRetainedApi::class,
  ExperimentalCircuitApi::class,
)
@Composable
@Inject
fun FieldSpottrApp(
  circuit: Circuit,
  @Assisted modifier: Modifier = Modifier,
  @Assisted onRootPop: () -> Unit,
  /**
   * Back requests from platforms with no system back, such as Escape on desktop. Each closes an
   * open overlay or pops a screen, and never leaves the root screen.
   */
  @Assisted backRequests: Flow<Unit> = emptyFlow(),
  /**
   * Draws the tab navigation, which switches tabs by swapping the back stack's root. iOS turns this
   * off and hosts one app per native tab instead.
   */
  @Assisted showsTabBar: Boolean = true,
  /** The tab the back stack starts on. */
  @Assisted rootScreen: HomeScreen = HomeScreen(),
) {
  // Temporary until Compose Multiplatform supplies a retained-values store.
  // https://issuetracker.google.com/issues/467397537
  RetainedValuesStoreProvider {
    CircuitCompositionLocals(circuit) {
      FSTheme {
        SharedElementTransitionLayout {
          Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            val backStack = rememberSaveableBackStack(rootScreen)
            val overlayHost = rememberOverlayHost()
            val navigator =
              rememberCircuitNavigator(backStack) {
                // Dismiss active overlay on back press instead of popping the navigator
                val overlay = overlayHost.currentOverlayData
                if (overlay != null) {
                  overlay.finish(DatePickerResult(null))
                } else {
                  onRootPop()
                }
              }
            // Wide windows show the root screen beside the screens above it. One back stack backs
            // both layouts, so folding or unfolding only changes how it renders.
            val paneHost = remember {
              PaneHost().apply { placeholder = rootScreen.tab.placeholder }
            }
            val paneNavigator =
              remember(navigator, paneHost) {
                PaneAwareNavigator(navigator) { paneHost.layout.isSplit }
              }
            LaunchedEffect(backRequests, paneNavigator) {
              backRequests.collect {
                val overlay = overlayHost.currentOverlayData
                when {
                  overlay != null -> overlay.finish(DatePickerResult(null))
                  backStack.size > 1 -> paneNavigator.pop()
                }
              }
            }
            val decoration =
              remember(circuit, paneHost) {
                PaneNavDecoration(
                  host = paneHost,
                  delegate =
                    AnimatedNavDecoration(
                      animatedScreenTransforms = circuit.animatedScreenTransforms,
                      // The right pane slides as it opens, so its first screen skips the push,
                      // and a replaced detail crossfades
                      decoratorFactory =
                        PaneAnimatedNavDecoratorFactory(GestureNavigationDecorationFactory()),
                    ),
                )
              }
            // The root record is always a tab's HomeScreen
            val currentTab = (backStack.lastOrNull()?.screen as? HomeScreen)?.tab
            val selectTab: (HomeTab) -> Unit = { tab ->
              if (tab == currentTab) {
                // Reselecting a tab returns to its root
                paneNavigator.popUntil { it is HomeScreen }
              } else {
                // Reserve or release the empty pane with the switch, so the next frame composes the
                // new tab and lays it out at its final width together
                paneHost.placeholder = tab.placeholder
                // Saves this tab's stack and restores the new tab's, so each tab keeps its screens
                paneNavigator.resetRoot(HomeScreen(tab), Navigator.StateOptions.SaveAndRestore)
              }
            }
            CompositionLocalProvider(LocalPaneHost provides paneHost) {
              // Overlays draw above the tab navigation, which is part of the content here
              ContentWithOverlays(overlayHost = overlayHost) {
                TabNavigationLayout(
                  showsTabs = showsTabBar,
                  selectedTab = currentTab,
                  onSelect = selectTab,
                ) {
                  NavigableCircuitContent(
                    navigator = paneNavigator,
                    backStack = backStack,
                    decoration = decoration,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Places the tabs beside [content], following Material's adaptive navigation: a navigation rail on
 * expanded windows (see [isExpandedWindow]), and a navigation bar below [content] otherwise. Both
 * stay visible over every screen. Without [showsTabs], as on iOS where each native tab hosts its
 * own app, [content] fills the window.
 *
 * [content] keeps its place in the composition across layouts, so folding or resizing keeps its
 * state. It gets [LocalBottomBarPadding] for the bottom inset it still covers, and
 * [LocalPaneWindowStart] for the rail before it.
 */
@Composable
private fun TabNavigationLayout(
  showsTabs: Boolean,
  selectedTab: HomeTab?,
  onSelect: (HomeTab) -> Unit,
  content: @Composable () -> Unit,
) {
  // The app fills the window, so these constraints are the window's size
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val windowSize = DpSize(maxWidth, maxHeight)
    val showsRail = showsTabs && isExpandedWindow(windowSize)
    val showsBar = showsTabs && !showsRail
    val isMediumWidth = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    val safeDrawing = WindowInsets.safeDrawing
    val startInset =
      safeDrawing
        .only(WindowInsetsSides.Start)
        .asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current)
    // A fixed width, decided here, so the panes know where they start in the same frame
    val railWidth = NavigationRailWidth + startInset
    val bottomInset = safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.weight(1f).fillMaxWidth()) {
        if (showsRail) {
          TabNavigationRail(
            selectedTab = selectedTab,
            onSelect = onSelect,
            modifier = Modifier.width(railWidth).fillMaxHeight(),
          )
        }
        Box(
          Modifier.weight(1f)
            .fillMaxHeight()
            .then(
              when {
                showsRail -> Modifier.consumeWindowInsets(safeDrawing.only(WindowInsetsSides.Start))
                showsBar -> Modifier.consumeWindowInsets(safeDrawing.only(WindowInsetsSides.Bottom))
                else -> Modifier
              }
            )
        ) {
          CompositionLocalProvider(
            LocalPaneWindowSize provides windowSize,
            LocalPaneWindowStart provides
              if (showsRail) {
                railWidth
              } else {
                0.dp
              },
            // The bar pads itself for the bottom inset, so content above it has none to cover
            LocalBottomBarPadding provides
              if (showsBar) {
                0.dp
              } else {
                bottomInset.calculateBottomPadding()
              },
            content = content,
          )
        }
      }
      if (showsBar) {
        TabNavigationBar(
          selectedTab = selectedTab,
          onSelect = onSelect,
          isMediumWidth = isMediumWidth,
        )
      }
    }
  }
}
