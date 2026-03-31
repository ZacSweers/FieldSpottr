// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FSPreferencesStore
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import dev.zacsweers.fieldspottr.util.Platform.Native
import dev.zacsweers.fieldspottr.util.daySwipeable
import dev.zacsweers.fieldspottr.util.extractCoordinatesFromUrl
import dev.zacsweers.fieldspottr.util.rememberDaySwipeState
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object PermitGridScreen : Screen {
  data class State(
    val areas: Areas,
    val date: LocalDate,
    val selectedGroup: String,
    val isDefaultGroup: Boolean,
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

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event

    data class ShowEventDetail(
      val fieldName: String,
      val index: Int,
      val event: Reserved,
      val orgVisible: Boolean,
    ) : Event

    data object ShowLocation : Event

    data object ToggleDefaultGroup : Event
  }
}

@Composable
fun PermitGridPresenter(
  repository: PermitRepository,
  preferencesStore: FSPreferencesStore,
  navigator: Navigator,
): PermitGridScreen.State {
  val areasFlow = rememberRetained { repository.areasFlow() }
  val areas by areasFlow.collectAsRetainedState()

  var gridDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  val scope = rememberCoroutineScope()
  val defaultGroup by preferencesStore.defaultGroup.collectAsRetainedState(null)
  var selectedGroup by rememberRetained { mutableStateOf(areas.entries[0].fieldGroups[0].name) }
  var userHasChangedGroup by rememberRetained { mutableStateOf(false) }
  LaunchedEffect(areas) {
    if (selectedGroup !in areas.groups) {
      selectedGroup = areas.entries[0].fieldGroups[0].name
      userHasChangedGroup = false
    }
  }
  LaunchedEffect(defaultGroup) {
    val saved = defaultGroup
    if (!userHasChangedGroup && saved != null && saved in areas.groups) {
      selectedGroup = saved
    }
  }
  var defaultGroupMessage by remember { mutableStateOf<String?>(null) }

  val permitsFlow =
    rememberRetained(gridDate, selectedGroup) {
      repository.permitsFlow(gridDate, selectedGroup).map {
        PermitState.fromPermits(it, areas, selectedGroup)
      }
    }
  val permits by permitsFlow.collectAsRetainedState(null)

  val currentAreaName = remember(selectedGroup, areas) { areas.groups[selectedGroup]?.area }
  val lastUpdateFlow =
    rememberRetained(currentAreaName) {
      if (currentAreaName != null) repository.lastUpdateFlow(currentAreaName) else flowOf(null)
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

  val dateRangeFlow = rememberRetained { repository.permitDateRangeFlow() }
  val permitDateRange by dateRangeFlow.collectAsRetainedState(null)

  val uriHandler = LocalUriHandler.current
  return PermitGridScreen.State(
    areas = areas,
    date = gridDate,
    selectedGroup = selectedGroup,
    isDefaultGroup = defaultGroup == selectedGroup,
    defaultGroupMessage = defaultGroupMessage,
    permits = permits,
    lastUpdated = lastUpdatedText,
    permitDateRange = permitDateRange,
    isDebug = !BuildConfig.IS_RELEASE,
  ) { event ->
    when (event) {
      PermitGridScreen.Event.Refresh -> {
        scope.launch { repository.populateDb(forceRefresh = true) }
      }
      PermitGridScreen.Event.UseBuiltInAreas -> repository.useBuiltInAreas()
      is PermitGridScreen.Event.FilterDate -> gridDate = event.date
      is PermitGridScreen.Event.ChangeGroup -> {
        selectedGroup = event.group
        userHasChangedGroup = true
      }
      is PermitGridScreen.Event.ShowEventDetail -> {
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
            orgVisible = event.orgVisible,
          )
        )
      }
      PermitGridScreen.Event.ToggleDefaultGroup -> {
        val isClearing = defaultGroup == selectedGroup
        val newDefault = if (isClearing) null else selectedGroup
        defaultGroupMessage =
          if (isClearing) "Cleared default group" else "Set $selectedGroup as default"
        scope.launch { preferencesStore.setDefaultGroup(newDefault) }
      }
      PermitGridScreen.Event.ShowLocation -> {
        val location = areas.groups[selectedGroup]?.location ?: return@State
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
fun PermitGridContent(state: PermitGridScreen.State, modifier: Modifier = Modifier) {
  val daySwipe = rememberDaySwipeState()
  Column(modifier = modifier, verticalArrangement = spacedBy(8.dp)) {
    // Action buttons row
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      horizontalArrangement = spacedBy(0.dp, alignment = Alignment.End),
    ) {
      IconButton(onClick = { state.eventSink(PermitGridScreen.Event.ToggleDefaultGroup) }) {
        Icon(
          Icons.Filled.Star,
          contentDescription =
            if (state.isDefaultGroup) "Clear default group"
            else "Set ${state.selectedGroup} as default",
          tint =
            if (state.isDefaultGroup) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
      }
      IconButton(onClick = { state.eventSink(PermitGridScreen.Event.ShowLocation) }) {
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
                  state.eventSink(PermitGridScreen.Event.UseBuiltInAreas)
                },
                onClick = { state.eventSink(PermitGridScreen.Event.Refresh) },
              ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
        }
      } else {
        IconButton(onClick = { state.eventSink(PermitGridScreen.Event.Refresh) }) {
          Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
        }
      }
    }

    GroupSelector(state.selectedGroup, state.areas) { newGroup ->
      state.eventSink(PermitGridScreen.Event.ChangeGroup(newGroup))
    }
    state.lastUpdated?.let { lastUpdated ->
      Text(
        lastUpdated,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
      )
    }

    val cornerSlot =
      remember(state.date, daySwipe.contentScale) {
        movableContentOf {
          DateSelector(
            state.date,
            contentScale = daySwipe.contentScale,
            permitDateRange = state.permitDateRange,
          ) { newDate ->
            state.eventSink(PermitGridScreen.Event.FilterDate(newDate))
          }
        }
      }

    PermitGrid(
      state.selectedGroup,
      state.permits,
      state.areas,
      state.date,
      cornerSlot = cornerSlot,
      modifier =
        Modifier.align(CenterHorizontally).weight(1f).daySwipeable(daySwipe, state.date) { newDate
          ->
          state.eventSink(PermitGridScreen.Event.FilterDate(newDate))
        },
    ) { fieldName, index, event, orgVisible ->
      state.eventSink(PermitGridScreen.Event.ShowEventDetail(fieldName, index, event, orgVisible))
    }
  }
}
