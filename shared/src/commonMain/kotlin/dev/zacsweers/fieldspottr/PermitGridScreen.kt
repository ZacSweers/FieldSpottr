// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FSPreferencesStore
import dev.zacsweers.fieldspottr.data.LiveGroupAvailability
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.data.WeatherCondition
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.data.WeatherRepository
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.favorites.FavoritesRepository
import dev.zacsweers.fieldspottr.ui.DataNote
import dev.zacsweers.fieldspottr.ui.PENDING_DATA_NOTE
import dev.zacsweers.fieldspottr.ui.WeatherGlyph
import dev.zacsweers.fieldspottr.ui.currentNyHour
import dev.zacsweers.fieldspottr.ui.formatHour12
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform.Native
import dev.zacsweers.metro.AppScope
import kotlin.time.Clock.System
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CircuitSerializable(AppScope::class)
data object PermitGridScreen : Screen {
  data class State(
    val areas: Areas,
    val date: LocalDate,
    val selectedGroup: String,
    val isDefaultGroup: Boolean,
    val defaultGroupMessage: String?,
    val permits: PermitState?,
    val liveAvailability: LiveGroupAvailability?,
    /** The date is today or later and the group has no pending permit data for it. */
    val isMissingPendingData: Boolean,
    val lastUpdated: DataFreshness?,
    val permitDateRange: Pair<LocalDate, LocalDate>?,
    val weather: WeatherForecast?,
    val viewMode: GridViewMode,
    val week: WeekAvailability?,
    val isDebug: Boolean,
    val favorite: Favorite,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  /**
   * The selected group's favorite state, with the local change shown before the server confirms it,
   * and the favorites changes across every group the server has not confirmed.
   */
  data class Favorite(
    val on: Boolean,
    /** A change to this group is still waiting on the server. */
    val pending: Boolean,
    val unsynced: Int,
    /** At least one change was rejected, so a retry is offered. */
    val failed: Boolean,
  )

  sealed interface Event {
    data object Refresh : Event

    data object ToggleFavorite : Event

    /** Sends the rejected favorites changes again. */
    data object RetryFavorites : Event

    data object UseBuiltInAreas : Event

    data object ToggleViewMode : Event

    data class SelectWeekDay(val date: LocalDate) : Event

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

@CircuitInject(PermitGridScreen::class, AppScope::class)
@Composable
fun PermitGridPresenter(
  repository: PermitRepository,
  weatherRepository: WeatherRepository,
  favoritesRepository: FavoritesRepository,
  preferencesStore: FSPreferencesStore,
  navigator: Navigator,
): PermitGridScreen.State {
  val areas by repository.areas.collectAsRetainedState(Areas.default)

  var gridDate by retain {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  val scope = rememberCoroutineScope()
  val defaultGroup by preferencesStore.defaultGroup.collectAsRetainedState(null)
  var selectedGroup by retain { mutableStateOf(areas.entries[0].fieldGroups[0].name) }
  var userHasChangedGroup by retain { mutableStateOf(false) }
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
    retain(gridDate, selectedGroup) {
      repository.permitsFlow(gridDate, selectedGroup).map {
        PermitData(
          permits = PermitState.fromPermits(it, areas, selectedGroup),
          liveAvailability = it.availabilityOverlays(areas, selectedGroup),
          isMissingPendingData =
            areas.groups[selectedGroup]?.let { group -> it.isMissingPendingData(group) } == true,
        )
      }
    }
  val permitData by permitsFlow.collectAsRetainedState(null)

  val currentAreaName = remember(selectedGroup, areas) { areas.groups[selectedGroup]?.area }
  val lastUpdateFlow =
    retain(currentAreaName) {
      if (currentAreaName != null) repository.lastUpdateFlow(currentAreaName) else flowOf(null)
    }
  val lastUpdateInstant by lastUpdateFlow.collectAsRetainedState(null)
  val lastUpdatedText =
    remember(lastUpdateInstant) {
      lastUpdateInstant?.let { instant ->
        val elapsed = System.now() - instant
        DataFreshness(updatedText(elapsed), isStale = elapsed >= STALE_DATA_AGE)
      }
    }

  val dateRangeFlow = retain { repository.permitDateRangeFlow() }
  val permitDateRange by dateRangeFlow.collectAsRetainedState(null)

  // Weather
  val weather by weatherRepository.forecast.collectAsRetainedState(null)

  // Favorites, local changes included, and the changes the server has not confirmed
  val favorites by favoritesRepository.favorites.collectAsRetainedState(emptySet())
  val unsyncedFavorites by favoritesRepository.unsynced.collectAsRetainedState(emptyList())
  val favorite =
    remember(selectedGroup, favorites, unsyncedFavorites) {
      PermitGridScreen.Favorite(
        on = selectedGroup in favorites,
        pending = unsyncedFavorites.any { it.mutation.group == selectedGroup },
        unsynced = unsyncedFavorites.size,
        failed = unsyncedFavorites.any { it.status is OutboxStatus.Failed },
      )
    }

  // Week view
  var viewMode by retain { mutableStateOf(GridViewMode.DAY) }
  val weekFlow =
    retain(gridDate, selectedGroup, viewMode, areas) {
      if (viewMode == GridViewMode.WEEK) {
        repository.permitsFlow(gridDate, days = 7, group = selectedGroup).map { permits ->
          computeWeekAvailability(
            permits,
            areas,
            selectedGroup,
            gridDate,
            today = System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
          )
        }
      } else {
        flowOf(null)
      }
    }
  val week by weekFlow.collectAsRetainedState(null)

  val uriHandler = LocalUriHandler.current
  return PermitGridScreen.State(
    areas = areas,
    date = gridDate,
    selectedGroup = selectedGroup,
    isDefaultGroup = defaultGroup == selectedGroup,
    defaultGroupMessage = defaultGroupMessage,
    permits = permitData?.permits,
    liveAvailability = permitData?.liveAvailability,
    isMissingPendingData =
      permitData?.isMissingPendingData == true &&
        gridDate >= System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    lastUpdated = lastUpdatedText,
    permitDateRange = permitDateRange,
    weather = weather,
    viewMode = viewMode,
    week = week,
    isDebug = !BuildConfig.IS_RELEASE,
    favorite = favorite,
  ) { event ->
    when (event) {
      PermitGridScreen.Event.Refresh -> {
        repository.refresh()
        weatherRepository.refresh()
      }
      PermitGridScreen.Event.ToggleFavorite -> {
        favoritesRepository.setFavorite(selectedGroup, on = !favorite.on)
      }
      PermitGridScreen.Event.RetryFavorites -> {
        for (change in unsyncedFavorites) {
          if (change.status is OutboxStatus.Failed) {
            favoritesRepository.retry(change.id)
          }
        }
      }
      PermitGridScreen.Event.UseBuiltInAreas -> repository.useBuiltInAreas()
      PermitGridScreen.Event.ToggleViewMode -> {
        viewMode =
          when (viewMode) {
            GridViewMode.DAY -> GridViewMode.WEEK
            GridViewMode.WEEK -> GridViewMode.DAY
          }
      }
      is PermitGridScreen.Event.SelectWeekDay -> {
        gridDate = event.date
        viewMode = GridViewMode.DAY
      }
      is PermitGridScreen.Event.FilterDate -> {
        gridDate = event.date
      }
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

@CircuitInject(PermitGridScreen::class, AppScope::class)
@Composable
fun PermitGrid(state: PermitGridScreen.State, modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    containerColor = Color.Transparent,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { innerPadding ->
    Column(
      modifier = Modifier.padding(innerPadding).fillMaxSize(),
      verticalArrangement = spacedBy(8.dp),
    ) {
      // Action buttons row
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(0.dp, alignment = Alignment.End),
      ) {
        // Rain callout for the selected date, when relevant
        val rainCallout =
          remember(state.weather, state.date) {
            val today = System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val fromHour = if (state.date == today) currentNyHour() else 6
            state.weather?.nextRainyHour(state.date, fromHour)?.let { hourly ->
              "Rain ~${formatHour12(hourly.hour)}"
            }
          }
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
          if (rainCallout != null) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = spacedBy(4.dp),
            ) {
              WeatherGlyph(
                WeatherCondition.RAIN,
                size = 14.dp,
                tint = MaterialTheme.colorScheme.tertiary,
              )
              Text(
                rainCallout,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
              )
            }
          }
        }
        IconButton(onClick = { state.eventSink(PermitGridScreen.Event.ToggleViewMode) }) {
          Icon(
            Icons.Outlined.DateRange,
            contentDescription =
              if (state.viewMode == GridViewMode.WEEK) "Show day view" else "Show week view",
            tint =
              if (state.viewMode == GridViewMode.WEEK) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
        IconButton(onClick = { state.eventSink(PermitGridScreen.Event.ToggleFavorite) }) {
          Icon(
            if (state.favorite.on) {
              Icons.Filled.Favorite
            } else {
              Icons.Outlined.FavoriteBorder
            },
            contentDescription =
              if (state.favorite.on) {
                "Remove ${state.selectedGroup} from favorites"
              } else {
                "Add ${state.selectedGroup} to favorites"
              },
            tint =
              when {
                // The server has not confirmed this group's change yet
                state.favorite.pending -> MaterialTheme.colorScheme.tertiary
                state.favorite.on -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
              },
          )
        }
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
          lastUpdated.text,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (lastUpdated.isStale) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
      // The week view marks each day instead
      if (state.isMissingPendingData && state.viewMode == GridViewMode.DAY) {
        DataNote(PENDING_DATA_NOTE, modifier = Modifier.padding(horizontal = 16.dp))
      }
      if (state.favorite.unsynced > 0) {
        val noun =
          if (state.favorite.unsynced == 1) {
            "favorite change"
          } else {
            "favorite changes"
          }
        val failed = state.favorite.failed
        Text(
          if (failed) {
            "${state.favorite.unsynced} $noun failed to sync. Tap to retry."
          } else {
            "Syncing ${state.favorite.unsynced} $noun"
          },
          style = MaterialTheme.typography.labelSmall,
          color =
            if (failed) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.tertiary
            },
          modifier =
            Modifier.padding(horizontal = 16.dp).clickable(enabled = failed) {
              state.eventSink(PermitGridScreen.Event.RetryFavorites)
            },
        )
      }

      // Retain scroll separately per group/date. The first load can jump to the first permit, but
      // navigating away and back should restore wherever the user left the grid.
      val gridVerticalScrollState = retain(state.selectedGroup, state.date) { ScrollState(0) }
      val gridHorizontalScrollState = retain(state.selectedGroup, state.date) { ScrollState(0) }
      var autoScrollToFirstPermit by
        retain(state.selectedGroup, state.date) { mutableStateOf(true) }

      val cornerSlot =
        remember(state.date, state.weather) {
          movableContentOf {
            DateSelector(
              state.date,
              id = "grid",
              permitDateRange = state.permitDateRange,
              weather = state.weather,
            ) { newDate ->
              state.eventSink(PermitGridScreen.Event.FilterDate(newDate))
            }
          }
        }

      // Soft cross-fade + scale between day and week views
      AnimatedContent(
        targetState = state.viewMode,
        transitionSpec = {
          (fadeIn(tween(220, delayMillis = 60)) +
              scaleIn(initialScale = 0.97f, animationSpec = tween(220, delayMillis = 60)))
            .togetherWith(fadeOut(tween(90)))
        },
        modifier = Modifier.weight(1f),
        label = "gridViewMode",
      ) { mode ->
        when (mode) {
          GridViewMode.WEEK -> {
            // Slide between weeks when the start date changes, fade on first load
            AnimatedContent(
              targetState = state.week,
              contentKey = { it?.startDate },
              transitionSpec = {
                val from = initialState?.startDate
                val to = targetState?.startDate
                if (from != null && to != null && from != to) {
                  val forward = to > from
                  (slideInHorizontally(tween(250)) { full ->
                      if (forward) full / 6 else -full / 6
                    } + fadeIn(tween(250)))
                    .togetherWith(
                      slideOutHorizontally(tween(200)) { full ->
                        if (forward) -full / 6 else full / 6
                      } + fadeOut(tween(150))
                    )
                } else {
                  fadeIn(tween(250)).togetherWith(fadeOut(tween(150)))
                }
              },
              label = "weekDate",
            ) { week ->
              if (week != null) {
                WeekGrid(
                  week = week,
                  selectedDate = state.date,
                  weather = state.weather,
                  cornerSlot = {
                    DateSelector(
                      state.date,
                      id = "grid-week",
                      permitDateRange = state.permitDateRange,
                      weather = state.weather,
                    ) { newDate ->
                      state.eventSink(PermitGridScreen.Event.FilterDate(newDate))
                    }
                  },
                  modifier = Modifier.fillMaxSize(),
                ) { date ->
                  state.eventSink(PermitGridScreen.Event.SelectWeekDay(date))
                }
              } else {
                Box(Modifier.fillMaxSize())
              }
            }
          }
          GridViewMode.DAY -> {
            PermitGrid(
              state.selectedGroup,
              state.permits,
              state.areas,
              state.date,
              liveAvailability = state.liveAvailability,
              weather = state.weather,
              cornerSlot = cornerSlot,
              modifier = Modifier.fillMaxSize(),
              verticalScrollState = gridVerticalScrollState,
              horizontalScrollState = gridHorizontalScrollState,
              autoScrollToFirstPermit = autoScrollToFirstPermit,
              onAutoScrolledToFirstPermit = { autoScrollToFirstPermit = false },
            ) { fieldName, index, event, orgVisible ->
              state.eventSink(
                PermitGridScreen.Event.ShowEventDetail(fieldName, index, event, orgVisible)
              )
            }
          }
        }
      }
    }
  }
}
