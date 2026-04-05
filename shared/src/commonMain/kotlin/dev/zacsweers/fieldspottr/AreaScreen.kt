// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import dev.zacsweers.fieldspottr.PermitState.FieldState.Reserved
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FSPreferencesStore
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.CurrentPlatform
import dev.zacsweers.fieldspottr.util.Platform
import dev.zacsweers.fieldspottr.util.Platform.Native
import dev.zacsweers.fieldspottr.util.ReflowText
import dev.zacsweers.fieldspottr.util.extractCoordinatesFromUrl
import dev.zacsweers.metro.AppScope
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data class AreaScreen(
  val initialGroup: String? = null,
  /** If set, locks to this group and shows the title/subtitle instead of the dropdown. */
  val fixedTitle: String? = null,
  val fixedSubtitle: String? = null,
) : Screen {
  data class State(
    val date: LocalDate,
    val areas: Areas,
    val selectedGroup: String,
    val fixedTitle: String?,
    val fixedSubtitle: String?,
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

    data class ShowEventDetail(
      val fieldName: String,
      val index: Int,
      val event: Reserved,
      val orgVisible: Boolean,
    ) : Event

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event

    data object ShowLocation : Event

    data object ToggleDefaultGroup : Event

    data object NavigateBack : Event
  }
}

@CircuitInject(AreaScreen::class, AppScope::class)
@Composable
fun AreaPresenter(
  screen: AreaScreen,
  repository: PermitRepository,
  preferencesStore: FSPreferencesStore,
  navigator: Navigator,
): AreaScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  val areasFlow = rememberRetained { repository.areasFlow() }
  val areas by areasFlow.collectAsRetainedState()
  val scope = rememberCoroutineScope()
  val defaultGroup by preferencesStore.defaultGroup.collectAsRetainedState(null)
  var selectedGroup by rememberRetained {
    mutableStateOf(screen.initialGroup ?: areas.entries[0].fieldGroups[0].name)
  }
  var userHasChangedGroup by rememberRetained { mutableStateOf(screen.initialGroup != null) }

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
    rememberRetained(selectedDate, selectedGroup) {
      repository.permitsFlow(selectedDate, selectedGroup).map {
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
  return AreaScreen.State(
    areas = areas,
    date = selectedDate,
    selectedGroup = selectedGroup,
    fixedTitle = screen.fixedTitle,
    fixedSubtitle = screen.fixedSubtitle,
    isDefaultGroup = defaultGroup == selectedGroup,
    defaultGroupMessage = defaultGroupMessage,
    permits = permits,
    lastUpdated = lastUpdatedText,
    permitDateRange = permitDateRange,
    isDebug = !BuildConfig.IS_RELEASE,
  ) { event ->
    when (event) {
      is AreaScreen.Event.Refresh -> {
        scope.launch { repository.populateDb(forceRefresh = true) }
      }
      AreaScreen.Event.UseBuiltInAreas -> {
        repository.useBuiltInAreas()
      }
      is AreaScreen.Event.FilterDate -> {
        selectedDate = event.date
      }
      is AreaScreen.Event.ChangeGroup -> {
        selectedGroup = event.group
        userHasChangedGroup = true
      }
      is AreaScreen.Event.ShowEventDetail -> {
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
      AreaScreen.Event.ToggleDefaultGroup -> {
        val isClearing = defaultGroup == selectedGroup
        val newDefault = if (isClearing) null else selectedGroup
        defaultGroupMessage =
          if (isClearing) "Cleared default group" else "Set $selectedGroup as default"
        scope.launch { preferencesStore.setDefaultGroup(newDefault) }
      }
      AreaScreen.Event.ShowLocation -> {
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
      AreaScreen.Event.NavigateBack -> navigator.pop()
    }
  }
}

@CircuitInject(AreaScreen::class, AppScope::class)
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AreaUi(state: AreaScreen.State, modifier: Modifier = Modifier) = SharedElementTransitionScope {
  val animatedScope = requireAnimatedScope(Navigation)
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(state.defaultGroupMessage) {
    state.defaultGroupMessage?.let {
      snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
    }
  }

  // Background fades quickly - fully gone by 35% of the transition progress
  val earlyEasing = Easing { (it / 0.35f).coerceAtMost(1f) }
  val bgModifier =
    with(animatedScope) {
      Modifier.animateEnterExit(enter = fadeIn(), exit = fadeOut(tween(easing = earlyEasing)))
    }

  val isDetail = state.fixedTitle != null
  Box(modifier.fillMaxSize()) {
    if (isDetail) {
      // Background - sharedElement, always opaque
      val containerKey = AreaContainerSharedElementKey(state.selectedGroup)
      Spacer(
        Modifier.matchParentSize()
          .sharedElement(
            sharedContentState = rememberSharedContentState(containerKey),
            animatedVisibilityScope = animatedScope,
          )
          .background(MaterialTheme.colorScheme.surface)
      )
    } else {
      // Screen background - fades with navigation (no shared element)
      Box(Modifier.matchParentSize().then(bgModifier).background(MaterialTheme.colorScheme.surface))
    }
    // Content - sharedBounds when detail (cross-fades inside animated bounds)
    val contentModifier =
      if (isDetail) {
        val contentKey = AreaContentSharedElementKey(state.selectedGroup)
        // Exit: fully gone by 30%. Enter: appears in the last 15%.
        val earlyEasing = Easing { (it / 0.3f).coerceAtMost(1f) }
        val lateEasing = Easing { ((it - 0.5f) / 0.5f).coerceIn(0f, 1f) }
        Modifier.sharedBounds(
          sharedContentState = rememberSharedContentState(contentKey),
          animatedVisibilityScope = animatedScope,
          enter = fadeIn(tween(easing = lateEasing)),
          exit = fadeOut(tween(easing = earlyEasing)),
          // Above background in overlay
          zIndexInOverlay = 1f,
        )
      } else {
        Modifier
      }
    Scaffold(
      modifier = contentModifier,
      containerColor = Color.Transparent,
      topBar = {
        TopAppBar(
          navigationIcon = {
            IconButton(onClick = { state.eventSink(AreaScreen.Event.NavigateBack) }) {
              Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
          },
          title = {
            if (state.fixedTitle != null) {
              Column(horizontalAlignment = Alignment.Start) {
                ReflowText(
                  text = state.fixedTitle,
                  sharedElementKey = "area-${state.selectedGroup}",
                  sharedElementKeySuffix = "title",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                )
                if (state.fixedSubtitle != null) {
                  ReflowText(
                    text = state.fixedSubtitle,
                    sharedElementKey = "area-${state.selectedGroup}",
                    sharedElementKeySuffix = "subtitle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          },
          actions = {
            // Only show star/location/refresh when browsing areas, not from FAF detail
            if (state.fixedTitle == null) {
              IconButton(onClick = { state.eventSink(AreaScreen.Event.ToggleDefaultGroup) }) {
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
            }
            IconButton(onClick = { state.eventSink(AreaScreen.Event.ShowLocation) }) {
              Icon(Icons.Outlined.Place, contentDescription = "Location")
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
        if (state.fixedTitle == null) {
          GroupSelector(state.selectedGroup, state.areas) { newGroup ->
            state.eventSink(AreaScreen.Event.ChangeGroup(newGroup))
          }
        }
        if (state.fixedTitle == null) {
          state.lastUpdated?.let { lastUpdated ->
            Text(
              lastUpdated,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp),
            )
          }
        }

        val datePulse = remember { Animatable(1f) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current

        val cornerSlot =
          remember(state.date) {
            movableContentOf {
              DateSelector(
                state.date,
                id = "area",
                contentScale = datePulse.value,
                permitDateRange = state.permitDateRange,
              ) { newDate ->
                state.eventSink(AreaScreen.Event.FilterDate(newDate))
              }
            }
          }

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
                    state.eventSink(
                      AreaScreen.Event.FilterDate(state.date.minus(1, DateTimeUnit.DAY))
                    )
                  } else if (dragOffset < -100f) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    state.eventSink(
                      AreaScreen.Event.FilterDate(state.date.plus(1, DateTimeUnit.DAY))
                    )
                  }
                  dragOffset = 0f
                  scope.launch {
                    datePulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                  }
                },
              ),
        ) { fieldName, index, event, orgVisible ->
          state.eventSink(AreaScreen.Event.ShowEventDetail(fieldName, index, event, orgVisible))
        }
      }
    }
  }
}
