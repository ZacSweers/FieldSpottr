// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.overlay.OverlayEffect
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuitx.overlays.BottomSheetOverlay
import dev.zacsweers.fieldspottr.HomeScreen.Event.*
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import dev.zacsweers.fieldspottr.util.Platform.Native
import kotlinx.coroutines.flow.map
import kotlin.time.Clock.System
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val showInfo: Boolean,
    val date: LocalDate,
    val areas: Areas,
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

    data object ShowLocation : Event
  }
}

/**
 * The CM implementation of ModalBottomSheet on non-android platforms is extremely janky and crashy,
 * so just make those go to new screens.
 *
 * TODO consider using AdaptiveBottomSheet from Calf once nested scrolling works
 * https://github.com/MohamedRejeb/Calf/issues/9
 */
private val USE_BOTTOM_SHEETS = CurrentPlatform == Platform.Android

@Composable
fun HomePresenter(navigator: Navigator, repository: PermitRepository): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  val areasFlow = rememberRetained { repository.areasFlow() }
  val areas by areasFlow.collectAsRetainedState()
  var showInfo by rememberRetained { mutableStateOf(false) }
  var populateDb by rememberRetained { mutableStateOf(true) }
  var forceRefresh by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  var selectedGroup by rememberRetained { mutableStateOf(areas.entries[0].fieldGroups[0].name) }
  var currentlyDetailedEvent by rememberRetained { mutableStateOf<Reserved?>(null) }

  val permitsFlow =
    rememberRetained(selectedDate, selectedGroup) {
      repository.permitsFlow(selectedDate, selectedGroup).map { PermitState.fromPermits(it, areas) }
    }
  val permits by permitsFlow.collectAsRetainedState(null)

  if (populateDb) {
    LaunchedEffect(Unit) {
      val successful = repository.populateDb(forceRefresh) { loadingMessage = it }
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
  val uriHandler = LocalUriHandler.current
  return HomeScreen.State(
    showInfo = showInfo,
    areas = areas,
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
        if (USE_BOTTOM_SHEETS) {
          showInfo = event.show
        } else {
          navigator.goTo(ScaffoldScreen(title = "", contentScreen = AboutScreen))
        }
      }
      is FilterDate -> {
        selectedDate = event.date
      }
      is ChangeGroup -> {
        selectedGroup = event.group
      }
      ClearEventDetail -> currentlyDetailedEvent = null
      is ShowEventDetail -> {
        if (USE_BOTTOM_SHEETS) {
          currentlyDetailedEvent = event.event
        } else {
          navigator.goTo(
            ScaffoldScreen(
              title = "Permit Details",
              contentScreen =
                PermitDetailsScreen(
                  name = event.event.title,
                  description = event.event.description,
                  group = selectedGroup,
                  org = event.event.org,
                ),
            )
          )
        }
      }

      ShowLocation -> {
        val location = areas.groups.getValue(selectedGroup).location
        val url =
          when (CurrentPlatform) {
            Native -> location.amaps
            else -> location.gmaps
          }
        uriHandler.openUri(url)
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

  if (state.showInfo) {
    OverlayEffect(state.showInfo) {
      show(
        BottomSheetOverlay(Unit, onDismiss = { state.eventSink(ShowInfo(false)) }) { _, _ ->
          About()
        }
      )
    }
  } else if (state.detailedEvent != null) {
    OverlayEffect(state.detailedEvent) {
      show(
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
          val interactionSource = remember { MutableInteractionSource() }
          Box(
            Modifier.clickable(interactionSource = interactionSource, indication = null) {
              state.eventSink(ShowInfo(true))
            }
          ) {
            Text("Field Spottr", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
          }
        },
        actions = {
          IconButton(onClick = { state.eventSink(ShowLocation) }) {
            Icon(Icons.Outlined.Place, contentDescription = "Location")
          }
          IconButton(onClick = { state.eventSink(Refresh) }) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
          }
        },
      )
    },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
    floatingActionButton = {
      if (state.permits?.fields.orEmpty().isEmpty()) {
        ExtendedFloatingActionButton(onClick = {}) { Text("No permits today!") }
      }
    },
  ) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding), verticalArrangement = spacedBy(8.dp)) {
      GroupSelector(state.selectedGroup, state.areas) { newGroup ->
        state.eventSink(ChangeGroup(newGroup))
      }
      val cornerSlot =
        remember(state.date) {
          movableContentOf {
            DateSelector(state.date) { newDate -> state.eventSink(FilterDate(newDate)) }
          }
        }

      PermitGrid(
        state.selectedGroup,
        state.permits,
        state.areas,
        cornerSlot = cornerSlot,
        modifier = Modifier.align(CenterHorizontally).weight(1f),
      ) { event ->
        state.eventSink(ShowEventDetail(event))
      }
    }
  }
}
