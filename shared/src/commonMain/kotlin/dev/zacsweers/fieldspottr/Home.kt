// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.foundation.NavEvent
import com.slack.circuit.foundation.onNavEvent
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.panes.DetailPlaceholder
import dev.zacsweers.fieldspottr.panes.LocalPaneHost
import dev.zacsweers.fieldspottr.panes.PaneHost
import dev.zacsweers.fieldspottr.ui.LocalBottomBarPadding
import dev.zacsweers.metro.AppScope

/** The app's top-level destinations. Each one has its own back stack. */
enum class HomeTab(
  val label: String,
  /**
   * The [PaneHost.placeholder] beside this tab on wide windows, or null when the tab fills the
   * width. Each tab keeps one lambda instance, so setting it again changes nothing.
   */
  val placeholder: (@Composable () -> Unit)? = null,
) {
  FIND_FIELD(
    "Find a field",
    placeholder = { DetailPlaceholder("Pick a field to see its schedule") },
  ),
  AREAS("Areas"),
  ABOUT("About"),
}

/**
 * The root of one tab's back stack. Switching tabs swaps the root with Circuit's saved back stacks,
 * so each tab keeps the screens opened in it.
 */
@CircuitSerializable(AppScope::class)
data class HomeScreen(val tab: HomeTab = HomeTab.entries.first()) : Screen {
  data class State(val tab: HomeTab, val loadingMessage: String?, val navigator: Navigator) :
    CircuitUiState
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(
  screen: HomeScreen,
  repository: PermitRepository,
  navigator: Navigator,
): HomeScreen.State {
  // Sync whatever is stale, once. Each tab's root asks, and the repository runs one sync at a time
  // and skips fresh data.
  var prewarmPending by retain { mutableStateOf(true) }
  if (prewarmPending) {
    LaunchedEffect(Unit) {
      repository.prewarm()
      prewarmPending = false
    }
  }

  // Observe loading state from repository
  val loadingMessage by repository.loadingMessage.collectAsRetainedState(null)

  return HomeScreen.State(tab = screen.tab, loadingMessage = loadingMessage, navigator = navigator)
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  val onNavEvent: (NavEvent) -> Unit = { state.navigator.onNavEvent(it) }
  val paneHost = LocalPaneHost.current
  // Keeps the empty pane reservation in step with the tab. The tab bar also sets it when it
  // switches
  // tabs, so the first frame of the new tab lays out at its final width.
  SideEffect { paneHost?.placeholder = state.tab.placeholder }

  LaunchedEffect(state.loadingMessage) {
    if (state.loadingMessage != null) {
      snackbarHostState.showSnackbar(state.loadingMessage, duration = SnackbarDuration.Indefinite)
    }
  }

  val bottomBarPadding = LocalBottomBarPadding.current
  Scaffold(
    modifier = modifier.fillMaxSize(),
    // Content draws to the bottom and pads its own scrolling content with LocalBottomBarPadding, so
    // the bottom inset doesn't apply here
    contentWindowInsets =
      ScaffoldDefaults.contentWindowInsets.only(
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
      ),
    snackbarHost = {
      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = bottomBarPadding),
      ) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
  ) { innerPadding ->
    // The tab content covers the bottom inset through LocalBottomBarPadding, so it's consumed here
    Box(
      Modifier.padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .fillMaxSize()
    ) {
      when (state.tab) {
        HomeTab.FIND_FIELD -> CircuitContent(FindFieldScreen, onNavEvent = onNavEvent)
        HomeTab.AREAS -> CircuitContent(PermitGridScreen, onNavEvent = onNavEvent)
        HomeTab.ABOUT -> CircuitContent(AboutScreen, onNavEvent = onNavEvent)
      }
    }
  }
}
