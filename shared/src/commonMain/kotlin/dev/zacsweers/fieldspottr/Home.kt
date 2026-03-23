// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.HomeScreen.Event.ChangeGroup
import dev.zacsweers.fieldspottr.HomeScreen.Event.FilterDate
import dev.zacsweers.fieldspottr.HomeScreen.Event.Refresh
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowEventDetail
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowInfo
import dev.zacsweers.fieldspottr.HomeScreen.Event.ShowLocation
import dev.zacsweers.fieldspottr.HomeScreen.Event.ToggleDefaultGroup
import dev.zacsweers.fieldspottr.HomeScreen.Event.UseBuiltInAreas
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
    val lastUpdated: String?,
    val permitDateRange: Pair<LocalDate, LocalDate>?,
    val isDebug: Boolean,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data object Refresh : Event

    data object UseBuiltInAreas : Event

    data class ShowInfo(val show: Boolean) : Event

    data class ShowEventDetail(val fieldName: String, val index: Int, val event: Reserved) : Event

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
  var defaultGroupMessage by rememberRetained { mutableStateOf<String?>(null) }

  val permitsFlow =
    rememberRetained(selectedDate, selectedGroup) {
      repository.permitsFlow(selectedDate, selectedGroup).map {
        PermitState.fromPermits(it, areas, selectedGroup)
      }
    }
  val permits by permitsFlow.collectAsRetainedState(null)

  // Last updated time for the current area
  val currentAreaName = remember(selectedGroup, areas) { areas.groups[selectedGroup]?.area }
  val lastUpdateFlow =
    rememberRetained(currentAreaName) {
      if (currentAreaName != null) repository.lastUpdateFlow(currentAreaName)
      else kotlinx.coroutines.flow.flowOf(null)
    }
  val lastUpdateInstant by lastUpdateFlow.collectAsRetainedState(null)
  val lastUpdatedText =
    remember(lastUpdateInstant) {
      lastUpdateInstant?.let { instant ->
        val elapsed = System.now() - instant
        when {
          elapsed < 1.minutes -> "Updated just now"
          elapsed < 1.hours -> "Updated ${elapsed.inWholeMinutes}m ago"
          elapsed < 1.days -> "Updated ${elapsed.inWholeHours}h ago"
          else -> "Updated ${elapsed.inWholeDays}d ago"
        }
      }
    }

  // Permit date range for constraining date picker
  val dateRangeFlow = rememberRetained { repository.permitDateRangeFlow() }
  val permitDateRange by dateRangeFlow.collectAsRetainedState(null)

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
    lastUpdated = lastUpdatedText,
    permitDateRange = permitDateRange,
    isDebug = !BuildConfig.IS_RELEASE,
  ) { event ->
    when (event) {
      is Refresh -> {
        forceRefresh = true
        populateDb = true
      }
      UseBuiltInAreas -> {
        repository.useBuiltInAreas()
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
      is ShowEventDetail -> {
        val reservation = event.event
        navigator.goTo(
          PermitDetailsScreen(
            fieldName = event.fieldName,
            index = event.index,
            name = reservation.title,
            group = selectedGroup,
            timeRange = reservation.timeRange,
            org = reservation.org,
            status = reservation.status,
          )
        )
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
          if (state.isDebug) {
            val haptics = LocalHapticFeedback.current
            Box(
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .combinedClickable(
                    onLongClick = {
                      haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                      state.eventSink(UseBuiltInAreas)
                    },
                    onClick = { state.eventSink(Refresh) },
                  ),
              contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
              Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
          } else {
            IconButton(onClick = { state.eventSink(Refresh) }) {
              Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
            }
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
      state.lastUpdated?.let { lastUpdated ->
        Text(
          lastUpdated,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      // Shrink date selector text while dragging, pop back on release
      val datePulse = remember { Animatable(1f) }
      val scope = rememberCoroutineScope()
      val haptics = LocalHapticFeedback.current

      val cornerSlot =
        remember(state.date) {
          movableContentOf {
            DateSelector(
              state.date,
              contentScale = datePulse.value,
              permitDateRange = state.permitDateRange,
            ) { newDate ->
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
        state.date,
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
      ) { fieldName, index, event ->
        state.eventSink(ShowEventDetail(fieldName, index, event))
      }
    }
  }
}
