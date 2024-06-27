// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.overlay.OverlayEffect
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuitx.overlays.BottomSheetOverlay
import dev.zacsweers.fieldspottr.HomeScreen.Event.ChangeGroup
import dev.zacsweers.fieldspottr.HomeScreen.Event.ClearEventDetail
import dev.zacsweers.fieldspottr.HomeScreen.Event.FilterDate
import dev.zacsweers.fieldspottr.HomeScreen.Event.Refresh
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowEventDetail
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowInfo
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock.System
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val showInfo: Boolean,
    val date: LocalDate,
    val selectedGroup: String,
    val loadingMessage: String?,
    val permits: PermitState?,
    val detailedEvent: Reserved?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data object Refresh : Event

    data object ClearEventDetail : Event

    data class ShowInfo(val show: Boolean) : Event

    data class ShowEventDetail(val event: Reserved) : Event

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event
  }
}

@Composable
fun HomePresenter(repository: PermitRepository): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  var showInfo by rememberRetained { mutableStateOf(false) }
  var populateDb by rememberRetained { mutableStateOf(false) }
  var forceRefresh by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  var selectedGroup by rememberRetained { mutableStateOf(Area.entries[0].fieldGroups[0].name) }
  var currentlyDetailedEvent by rememberRetained { mutableStateOf<Reserved?>(null) }

  val permitsFlow =
    rememberRetained(selectedDate, selectedGroup) {
      repository
        .permitsFlow(selectedDate, selectedGroup)
        .map(PermitState::fromPermits)
        .flowOn(Dispatchers.IO)
    }
  val permits by permitsFlow.collectAsRetainedState(null)

  if (populateDb) {
    LaunchedEffect(Unit) {
      loadingMessage = "Populating DB..."
      val successful = repository.populateDb(forceRefresh)
      loadingMessage =
        if (successful) {
          null
        } else {
          "Failed to fetch areas. Please check connection and try again."
        }
      forceRefresh = false
      populateDb = false
    }
  }
  return HomeScreen.State(
    showInfo = showInfo,
    date = selectedDate,
    selectedGroup = selectedGroup,
    loadingMessage = loadingMessage,
    permits = permits,
    detailedEvent = currentlyDetailedEvent,
  ) { event ->
    when (event) {
      is Refresh -> {
        forceRefresh = true
        populateDb = true
      }
      is ShowInfo -> {
        showInfo = event.show
      }
      is FilterDate -> {
        selectedDate = event.date
      }
      is ChangeGroup -> {
        selectedGroup = event.group
      }
      ClearEventDetail -> currentlyDetailedEvent = null
      is ShowEventDetail -> currentlyDetailedEvent = event.event
    }
  }
}

@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(state.loadingMessage) {
    state.loadingMessage?.let {
      snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Indefinite)
    }
  }

  if (state.showInfo) {
    OverlayEffect(state.showInfo) { host ->
      host.show(
        BottomSheetOverlay(Unit, onDismiss = { state.eventSink(ShowInfo(false)) }) { _, _ ->
          About()
        }
      )
    }
  } else if (state.detailedEvent != null) {
    OverlayEffect(state.detailedEvent) { host ->
      host.show(
        BottomSheetOverlay(
          state.detailedEvent,
          onDismiss = { state.eventSink(ClearEventDetail) },
        ) { model, _ ->
          CircuitContent(
            PermitDetailsScreen(model.title, model.description, state.selectedGroup, model.org)
          )
        }
      )
    }
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text("Field Spottr", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        },
        actions = {
          IconButton(onClick = { state.eventSink(Refresh) }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
          }
          IconButton(onClick = { state.eventSink(ShowInfo(true)) }) {
            Icon(Icons.Outlined.Info, contentDescription = "Info")
          }
        },
      )
    },
    floatingActionButton = {
      DateSelector(state.date) { newDate -> state.eventSink(FilterDate(newDate)) }
    },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding), verticalArrangement = spacedBy(16.dp)) {
      GroupSelector(
        state.selectedGroup,
        modifier = Modifier.align(CenterHorizontally).padding(horizontal = 16.dp),
      ) { newGroup ->
        state.eventSink(ChangeGroup(newGroup))
      }

      PermitGrid(
        state.selectedGroup,
        state.permits,
        modifier = Modifier.align(CenterHorizontally),
      ) { event ->
        state.eventSink(ShowEventDetail(event))
      }
    }
  }
}
