// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.foundation.NavEvent
import com.slack.circuit.foundation.onNavEvent
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize

private enum class Tab(val label: String) {
  FIND_FIELD("Find a field"),
  AREAS("Areas"),
  ABOUT("About"),
}

@CommonParcelize
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

@Composable
fun HomePresenter(repository: PermitRepository, navigator: Navigator): HomeScreen.State {
  var selectedTabIndex by rememberRetained { mutableStateOf(0) }

  // Trigger initial DB population
  var populateDb by rememberRetained { mutableStateOf(true) }
  if (populateDb) {
    LaunchedEffect(Unit) {
      repository.populateDb(forceRefresh = false)
      populateDb = false
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
      is HomeScreen.Event.SelectTab -> selectedTabIndex = event.index
    }
  }
}

@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  val onNavEvent: (NavEvent) -> Unit = { state.navigator.onNavEvent(it) }

  LaunchedEffect(state.loadingMessage) {
    if (state.loadingMessage != null) {
      snackbarHostState.showSnackbar(state.loadingMessage, duration = SnackbarDuration.Indefinite)
    }
  }

  Scaffold(
    modifier = modifier,
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
    bottomBar = {
      NavigationBar {
        Tab.entries.forEachIndexed { index, tab ->
          NavigationBarItem(
            selected = state.selectedTabIndex == index,
            onClick = { state.eventSink(HomeScreen.Event.SelectTab(index)) },
            icon = {
              Icon(
                when (tab) {
                  Tab.FIND_FIELD -> Icons.Outlined.Search
                  Tab.AREAS -> Icons.Outlined.Place
                  Tab.ABOUT -> Icons.Outlined.Info
                },
                contentDescription = tab.label,
              )
            },
            label = { Text(tab.label) },
          )
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
