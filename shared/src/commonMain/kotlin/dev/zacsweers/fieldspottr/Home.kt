// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.sheet.AdaptiveBottomSheet
import com.mohamedrejeb.calf.ui.sheet.rememberAdaptiveSheetState
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.HomeScreen.Event.ChangeGroup
import dev.zacsweers.fieldspottr.HomeScreen.Event.ClearEventDetail
import dev.zacsweers.fieldspottr.HomeScreen.Event.FilterDate
import dev.zacsweers.fieldspottr.HomeScreen.Event.Refresh
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowEventDetail
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowInfo
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowLocation
import dev.zacsweers.fieldspottr.HomeScreen.Event.ToggleDefaultGroup
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FSPreferencesStore
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import dev.zacsweers.fieldspottr.util.Platform.Native
import dev.zacsweers.fieldspottr.util.extractCoordinatesFromUrl
import kotlin.time.Clock.System
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val showInfo: Boolean,
    val date: LocalDate,
    val areas: Areas,
    val selectedGroup: String,
    val isDefaultGroup: Boolean,
    val loadingMessage: String?,
    val defaultGroupMessage: String?,
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

    data object ToggleDefaultGroup : Event
  }
}

@Composable
fun HomePresenter(
  repository: PermitRepository,
  preferencesStore: FSPreferencesStore,
  navigator: Navigator,
): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  val areasFlow = rememberRetained { repository.areasFlow() }
  val areas by areasFlow.collectAsRetainedState()
  var showInfo by rememberRetained { mutableStateOf(false) }
  var populateDb by rememberRetained { mutableStateOf(true) }
  var forceRefresh by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  val defaultGroup by preferencesStore.defaultGroup.collectAsRetainedState(null)
  var selectedGroup by rememberRetained { mutableStateOf(areas.entries[0].fieldGroups[0].name) }
  // Apply the persisted default group once DataStore has loaded
  LaunchedEffect(Unit) {
    val saved = preferencesStore.defaultGroup.first()
    if (saved != null && saved in areas.groups) {
      selectedGroup = saved
    }
  }
  var currentlyDetailedEvent by rememberRetained { mutableStateOf<Reserved?>(null) }
  var defaultGroupMessage by rememberRetained { mutableStateOf<String?>(null) }

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
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  return HomeScreen.State(
    showInfo = showInfo,
    areas = areas,
    date = selectedDate,
    selectedGroup = selectedGroup,
    isDefaultGroup = defaultGroup == selectedGroup,
    loadingMessage = loadingMessage,
    defaultGroupMessage = defaultGroupMessage,
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
      is ShowEventDetail -> {
        currentlyDetailedEvent = event.event
      }
      ToggleDefaultGroup -> {
        val isClearing = defaultGroup == selectedGroup
        val newDefault = if (isClearing) null else selectedGroup
        defaultGroupMessage =
          if (isClearing) {
            "Cleared default group"
          } else {
            "Set $selectedGroup as default"
          }
        scope.launch { preferencesStore.setDefaultGroup(newDefault) }
      }
      ShowLocation -> {
        val location = areas.groups.getValue(selectedGroup).location
        val coords = extractCoordinatesFromUrl(location.gmaps, location.amaps)
        if (CurrentPlatform != Platform.Jvm && coords != null) {
          val (lat, lon) = coords
          navigator.goTo(
            ScaffoldScreen(
              title = selectedGroup,
              contentScreen =
                LocationMapScreen(
                  latitude = lat,
                  longitude = lon,
                  title = selectedGroup,
                  gmapsUrl = location.gmaps,
                  amapsUrl = location.amaps,
                ),
            )
          )
        } else {
          // Fallback to opening URL if we can't extract coordinates
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
}

@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(state.loadingMessage) {
    state.loadingMessage?.let {
      snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Indefinite)
    }
  }
  LaunchedEffect(state.defaultGroupMessage) {
    state.defaultGroupMessage?.let {
      snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
    }
  }

  // Info bottom sheet
  if (state.showInfo) {
    val infoSheetState =
      rememberAdaptiveSheetState(skipPartiallyExpanded = false, confirmValueChange = { true })

    LaunchedEffect(state.showInfo) {
      if (state.showInfo) {
        infoSheetState.show()
      }
    }

    AdaptiveBottomSheet(
      onDismissRequest = { state.eventSink(ShowInfo(false)) },
      adaptiveSheetState = infoSheetState,
    ) {
      About(modifier = if (CurrentPlatform == Native) Modifier.padding(top = 24.dp) else Modifier)
    }
  }

  // Event detail bottom sheet
  state.detailedEvent?.let { event ->
    val detailSheetState =
      rememberAdaptiveSheetState(skipPartiallyExpanded = false, confirmValueChange = { true })

    LaunchedEffect(event) { detailSheetState.show() }

    AdaptiveBottomSheet(
      onDismissRequest = { state.eventSink(ClearEventDetail) },
      adaptiveSheetState = detailSheetState,
    ) {
      Box(Modifier.fillMaxSize().background(BottomSheetDefaults.ContainerColor)) {
        CircuitContent(
          PermitDetailsScreen(
            name = event.title,
            group = state.selectedGroup,
            timeRange = event.timeRange,
            status = event.status,
            org = event.org,
          ),
          modifier = if (CurrentPlatform == Native) Modifier.padding(top = 24.dp) else Modifier,
        )
      }
    }
  }

  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Scaffold(
    modifier =
      modifier.focusRequester(focusRequester).onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
          when {
            (keyEvent.isMetaPressed || keyEvent.isCtrlPressed) && keyEvent.key == Key.R -> {
              state.eventSink(Refresh)
              true
            }
            else -> false
          }
        } else {
          false
        }
      },
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
          IconButton(onClick = { state.eventSink(ToggleDefaultGroup) }) {
            Icon(
              Icons.Filled.Star,
              contentDescription =
                if (state.isDefaultGroup) {
                  "Clear default group"
                } else {
                  "Set ${state.selectedGroup} as default"
                },
              tint =
                if (state.isDefaultGroup) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
          }
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
      // Shrink date selector text while dragging, pop back on release
      val datePulse = remember { Animatable(1f) }
      val scope = rememberCoroutineScope()
      val haptics = LocalHapticFeedback.current

      val cornerSlot =
        remember(state.date) {
          movableContentOf {
            DateSelector(state.date, contentScale = datePulse.value) { newDate ->
              state.eventSink(FilterDate(newDate))
            }
          }
        }

      // Swipe left/right to navigate days
      var dragOffset by remember { mutableFloatStateOf(0f) }
      val draggableState = rememberDraggableState { delta -> dragOffset += delta }

      PermitGrid(
        state.selectedGroup,
        state.permits,
        state.areas,
        cornerSlot = cornerSlot,
        modifier =
          Modifier.align(CenterHorizontally)
            .weight(1f)
            .draggable(
              state = draggableState,
              orientation = Orientation.Horizontal,
              onDragStarted = {
                dragOffset = 0f
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { datePulse.animateTo(0.8f, tween(150)) }
              },
              onDragStopped = {
                if (dragOffset > 100f) {
                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  state.eventSink(FilterDate(state.date.minus(1, DateTimeUnit.DAY)))
                } else if (dragOffset < -100f) {
                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  state.eventSink(FilterDate(state.date.plus(1, DateTimeUnit.DAY)))
                }
                dragOffset = 0f
                scope.launch {
                  datePulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
              },
            ),
      ) { event ->
        state.eventSink(ShowEventDetail(event))
      }
    }
  }
}
