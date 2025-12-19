// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuit.sharedelements.SharedElementTransitionLayout
import com.slack.circuitx.gesturenavigation.GestureNavigationDecorationFactory
import dev.zacsweers.fieldspottr.theme.FSTheme
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.Inject

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
@Inject
fun FieldSpottrApp(circuit: Circuit, @Assisted modifier: Modifier = Modifier, @Assisted onRootPop: () -> Unit) {
  CircuitCompositionLocals(circuit) {
    FSTheme {
      SharedElementTransitionLayout(modifier) {
        Surface(color = MaterialTheme.colorScheme.background) {
          val backStack = rememberSaveableBackStack(HomeScreen)
          val navigator = rememberCircuitNavigator(backStack) { onRootPop() }
          ContentWithOverlays {
            NavigableCircuitContent(
              navigator = navigator,
              backStack = backStack,
              decoratorFactory = GestureNavigationDecorationFactory(onBackInvoked = navigator::pop),
            )
          }
        }
      }
    }
  }
}
