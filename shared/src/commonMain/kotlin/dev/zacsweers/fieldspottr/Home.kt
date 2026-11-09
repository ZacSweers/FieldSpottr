// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.slack.circuit.runtime.popUntil
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.panes.LocalPaneLayout
import dev.zacsweers.metro.AppScope

private enum class Tab(val label: String) {
  FIND_FIELD("Find a field"),
  AREAS("Areas"),
  ABOUT("About"),
}

@CircuitSerializable(AppScope::class)
data object HomeScreen : Screen {
  data class State(
    val selectedTabIndex: Int,
    val loadingMessage: String?,
    val navigator: Navigator,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data class SelectTab(val index: Int) : Event
  }
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(repository: PermitRepository, navigator: Navigator): HomeScreen.State {
  var selectedTabIndex by retain { mutableStateOf(0) }

  // Sync whatever is stale, once
  var prewarmPending by retain { mutableStateOf(true) }
  if (prewarmPending) {
    LaunchedEffect(Unit) {
      repository.prewarm()
      prewarmPending = false
    }
  }

  // Observe loading state from repository
  val loadingMessage by repository.loadingMessage.collectAsRetainedState(null)

  return HomeScreen.State(
    selectedTabIndex = selectedTabIndex,
    loadingMessage = loadingMessage,
    navigator = navigator,
  ) { event ->
    when (event) {
      is HomeScreen.Event.SelectTab -> {
        // In the split layout the rail stays visible beside open details, so close them before
        // showing another tab. Phones cover the bar with pushed screens, so this pops nothing
        // there.
        navigator.popUntil { it is HomeScreen }
        selectedTabIndex = event.index
      }
    }
  }
}

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  val onNavEvent: (NavEvent) -> Unit = { state.navigator.onNavEvent(it) }

  LaunchedEffect(state.loadingMessage) {
    if (state.loadingMessage != null) {
      snackbarHostState.showSnackbar(state.loadingMessage, duration = SnackbarDuration.Indefinite)
    }
  }

  // Wide windows put the tabs in a rail on the start edge, which leaves the full height for content
  val showsRail = LocalPaneLayout.current.isWide
  Row(modifier.fillMaxSize()) {
    if (showsRail) {
      NavigationRail(
        windowInsets =
          WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
      ) {
        Tab.entries.forEachIndexed { index, tab ->
          NavigationRailItem(
            selected = state.selectedTabIndex == index,
            onClick = { state.eventSink(HomeScreen.Event.SelectTab(index)) },
            icon = { TabIcon(tab) },
            label = { Text(tab.label) },
          )
        }
      }
    }
    Scaffold(
      modifier =
        if (showsRail) {
          Modifier.weight(1f)
            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
        } else {
          Modifier.weight(1f)
        },
      snackbarHost = {
        SnackbarHost(hostState = snackbarHostState) { snackbarData ->
          Snackbar(snackbarData = snackbarData)
        }
      },
      bottomBar = {
        if (!showsRail) {
          NavigationBar {
            Tab.entries.forEachIndexed { index, tab ->
              NavigationBarItem(
                selected = state.selectedTabIndex == index,
                onClick = { state.eventSink(HomeScreen.Event.SelectTab(index)) },
                icon = { TabIcon(tab) },
                label = { Text(tab.label) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Box(Modifier.padding(innerPadding).fillMaxSize()) {
        when (Tab.entries.getOrNull(state.selectedTabIndex) ?: Tab.FIND_FIELD) {
          Tab.FIND_FIELD -> CircuitContent(FindFieldScreen, onNavEvent = onNavEvent)
          Tab.AREAS -> CircuitContent(PermitGridScreen, onNavEvent = onNavEvent)
          Tab.ABOUT -> CircuitContent(AboutScreen, onNavEvent = onNavEvent)
        }
      }
    }
  }
}

@Composable
private fun TabIcon(tab: Tab) {
  Icon(
    when (tab) {
      Tab.FIND_FIELD -> Icons.Outlined.Search
      Tab.AREAS -> Icons.Outlined.Place
      Tab.ABOUT -> Icons.Outlined.Info
    },
    contentDescription = tab.label,
  )
}
