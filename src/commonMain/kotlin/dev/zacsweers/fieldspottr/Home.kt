// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slack.circuit.overlay.LocalOverlayHost
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuitx.overlays.alertDialogOverlay
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.NYC_TZ
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val date: LocalDate,
    val selectedGroup: String,
    val loadingMessage: String?,
    val permits: PermitState?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data object Refresh : Event

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event
  }
}

@Composable
fun HomePresenter(repository: PermitRepository): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  var dbLoaded by rememberRetained { mutableStateOf(false) }
  var forceRefresh by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  var permits by rememberRetained { mutableStateOf<PermitState?>(null) }
  var selectedGroup by rememberRetained { mutableStateOf(Area.entries[0].fieldGroups[0].name) }
  if (!dbLoaded) {
    LaunchedEffect(Unit) {
      repository.populateDb(forceRefresh)
      dbLoaded = true
      forceRefresh = false
    }
    loadingMessage = "Populating DB..."
  } else {
    LaunchedEffect(selectedDate, selectedGroup) {
      loadingMessage = "Loading permits..."
      permits =
        repository.loadPermits(selectedDate, selectedGroup).let { PermitState.fromPermits(it) }
      loadingMessage = null
    }
  }
  return HomeScreen.State(
    date = selectedDate,
    selectedGroup = selectedGroup,
    loadingMessage = loadingMessage,
    permits = permits,
  ) { event ->
    when (event) {
      is HomeScreen.Event.Refresh -> {
        dbLoaded = false
        forceRefresh = true
      }
      is HomeScreen.Event.FilterDate -> {
        selectedDate = event.date
      }
      is HomeScreen.Event.ChangeGroup -> {
        selectedGroup = event.group
      }
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
  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Field Spottr") },
        actions = {
          IconButton(onClick = { state.eventSink(HomeScreen.Event.Refresh) }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
          }
        },
      )
    },
    floatingActionButton = {
      DateSelector(state.date) { newDate -> state.eventSink(HomeScreen.Event.FilterDate(newDate)) }
    },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding), verticalArrangement = spacedBy(16.dp)) {
      GroupSelector2(
        state.selectedGroup,
        modifier = Modifier.align(CenterHorizontally).padding(horizontal = 16.dp),
      ) { newGroup ->
        state.eventSink(HomeScreen.Event.ChangeGroup(newGroup))
      }

      if (state.loadingMessage == null && state.permits == null) {
        Text("No permits found for today: ${state.date}")
      }
      val overlayHost = LocalOverlayHost.current
      val scope = rememberCoroutineScope()
      PermitGrid(state, modifier = Modifier.align(CenterHorizontally)) { event ->
        scope.launch {
          overlayHost.show(
            alertDialogOverlay(
              title = { Text(event.name) },
              text = { Text(event.org) },
              confirmButton = { onClick -> TextButton(onClick) { Text("Done") } },
              dismissButton = { onClick -> TextButton(onClick) { Text("Cancel") } },
            )
          )
        }
      }
    }
  }
}

@Stable
data class PermitState(val fields: Map<String, FieldState>) {
  @Stable
  data class FieldState(val permits: Map<Int, DbPermit>) {
    companion object {
      fun fromPermits(permits: List<DbPermit>): FieldState {
        val timeMappings: Map<Int, DbPermit> = buildMap {
          for (permit in permits.sortedBy { it.start }) {
            val durationHours = (permit.end - permit.start).milliseconds.inWholeHours
            val startHour = Instant.fromEpochMilliseconds(permit.start).toLocalDateTime(NYC_TZ).hour
            for (hour in startHour until startHour + durationHours) {
              put(hour.toInt(), permit)
            }
          }
        }
        return FieldState(timeMappings)
      }
    }
  }

  companion object {
    fun fromPermits(permits: List<DbPermit>): PermitState {
      val areasByName = Area.entries.associateBy { it.areaName }
      val fields =
        permits
          .groupBy { areasByName.getValue(it.area).fieldMappings.getValue(it.fieldId) }
          .mapKeys { it.key.name }
          .mapValues { (_, permits) -> FieldState.fromPermits(permits) }
      return PermitState(fields)
    }
  }
}
