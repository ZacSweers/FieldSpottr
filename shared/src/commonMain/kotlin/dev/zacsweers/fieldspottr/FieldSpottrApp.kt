// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.slack.circuit.sharedelements.SharedElementTransitionLayout
import com.slack.circuitx.gesturenavigation.GestureNavigationDecorationFactory
import dev.zacsweers.fieldspottr.panes.LocalPaneHost
import dev.zacsweers.fieldspottr.panes.PaneAwareNavigator
import dev.zacsweers.fieldspottr.panes.PaneHost
import dev.zacsweers.fieldspottr.panes.PaneNavDecoration
import dev.zacsweers.fieldspottr.theme.FSTheme
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.Inject

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
) {
  // Temporary until Compose Multiplatform supplies a retained-values store.
  // https://issuetracker.google.com/issues/467397537
  RetainedValuesStoreProvider {
    CircuitCompositionLocals(circuit) {
      FSTheme {
        SharedElementTransitionLayout {
          Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
            val backStack = rememberSaveableBackStack(HomeScreen)
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
            val paneHost = remember { PaneHost() }
            val paneNavigator =
              remember(navigator, paneHost) {
                PaneAwareNavigator(navigator) { paneHost.layout.isSplit }
              }
            val decoration =
              remember(circuit, paneHost) {
                PaneNavDecoration(
                  host = paneHost,
                  delegate =
                    AnimatedNavDecoration(
                      animatedScreenTransforms = circuit.animatedScreenTransforms,
                      decoratorFactory = GestureNavigationDecorationFactory(),
                    ),
                )
              }
            CompositionLocalProvider(LocalPaneHost provides paneHost) {
              ContentWithOverlays(overlayHost = overlayHost) {
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
