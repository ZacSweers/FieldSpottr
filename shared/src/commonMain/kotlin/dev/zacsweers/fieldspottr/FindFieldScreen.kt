// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.calf.ui.progress.AdaptiveCircularProgressIndicator
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import dev.zacsweers.fieldspottr.PermitState.Companion.isBlocked
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.BbpAvailability
import dev.zacsweers.fieldspottr.data.FSPreferencesStore
import dev.zacsweers.fieldspottr.data.Field
import dev.zacsweers.fieldspottr.data.FieldGroup
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.data.TimeWindow
import dev.zacsweers.fieldspottr.data.WeatherCondition
import dev.zacsweers.fieldspottr.data.WeatherForecast
import dev.zacsweers.fieldspottr.data.WeatherRepository
import dev.zacsweers.fieldspottr.panes.LocalPaneLayout
import dev.zacsweers.fieldspottr.ui.DataNote
import dev.zacsweers.fieldspottr.ui.PENDING_DATA_NOTE
import dev.zacsweers.fieldspottr.ui.WeatherGlyph
import dev.zacsweers.fieldspottr.ui.WeatherStrip
import dev.zacsweers.fieldspottr.ui.formatTime12
import dev.zacsweers.fieldspottr.util.ReflowText
import dev.zacsweers.fieldspottr.util.daySwipeable
import dev.zacsweers.fieldspottr.util.nycSunset
import dev.zacsweers.fieldspottr.util.produceNyHour
import dev.zacsweers.fieldspottr.util.rememberDaySwipeState
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import dev.zacsweers.metro.AppScope
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock.System
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

@Immutable
data class FieldAvailability(
  val group: FieldGroup,
  val areaDisplayName: String,
  /** Hours when every field in the group is free. Empty when only some fields are free. */
  val openTimeRange: String,
  val isFullyOpen: Boolean,
  /** If non-null, this field is closed for the window and this is the (short) reason. */
  val closedReason: String? = null,
  /** Fields that are free for more of the window than the whole group, like one free half. */
  val fieldOpenings: ImmutableList<FieldOpening> = persistentListOf(),
)

/** One or more fields in a group, by display name, with the same free [openTimeRange]. */
@Immutable data class FieldOpening(val fieldNames: String, val openTimeRange: String)

@Immutable
data class AvailabilityBuckets(
  val fullyOpen: ImmutableList<FieldAvailability>,
  val partiallyOpen: ImmutableList<FieldAvailability>,
  val closed: ImmutableList<FieldAvailability>,
  /** Some open groups have no pending permit data for the date, so their times may be requested. */
  val isMissingPendingData: Boolean = false,
) {
  val totalOpen: Int
    get() = fullyOpen.size + partiallyOpen.size
}

@CircuitSerializable(AppScope::class)
data object FindFieldScreen : Screen {
  data class State(
    val selectedDate: LocalDate,
    /** The window in effect, after a passed window on today falls back to the current one. */
    val selectedWindow: TimeWindow?,
    val isToday: Boolean,
    /** The current New York hour. On today, search hours and window chips start here. */
    val currentHour: Int,
    /** Built-in windows followed by saved custom windows. */
    val windows: ImmutableList<TimeWindow>,
    val isManagingWindows: Boolean,
    /** The selected date is today or later and some open groups lack pending permit data. */
    val isMissingPendingData: Boolean,
    val freshness: DataFreshness?,
    /** Sunset in New York time on [selectedDate]. */
    val sunset: LocalTime,
    /** A refresh the user started is running. */
    val isRefreshing: Boolean,
    val availability: AvailabilityBuckets?,
    val weather: WeatherForecast?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState {
    val customWindows: List<TimeWindow>
      get() = windows.filterNot { it.builtIn }

    val canAddWindow: Boolean
      get() = customWindows.size < TimeWindow.MAX_CUSTOM_WINDOWS

    /** The window chips to show. See [visibleWindows]. */
    val visibleWindows: List<TimeWindow>
      get() = visibleWindows(windows, isToday, currentHour)
  }

  sealed interface Event {
    /** Selects the window with [windowId], or the whole day when null. */
    data class SelectWindow(val windowId: String?) : Event

    data class ShowWindowManager(val show: Boolean) : Event

    /** Saves a custom window. A null [id] creates a new window and selects it. */
    data class SaveWindow(val id: String?, val name: String, val startHour: Int, val endHour: Int) :
      Event

    data class DeleteWindow(val id: String) : Event

    data class SelectDate(val date: LocalDate) : Event

    data class NavigateToArea(val group: String) : Event

    data object Refresh : Event
  }
}

@CircuitInject(FindFieldScreen::class, AppScope::class)
@Composable
fun FindFieldPresenter(
  repository: PermitRepository,
  weatherRepository: WeatherRepository,
  preferencesStore: FSPreferencesStore,
  navigator: Navigator,
): FindFieldScreen.State {
  val areas by repository.areas.collectAsRetainedState(Areas.default)
  val now by produceNyHour()
  val today = now.date
  val currentHour = now.hour
  val scope = rememberCoroutineScope()
  var selectedDate by retain { mutableStateOf(today) }

  val customWindows by preferencesStore.customTimeWindows.collectAsRetainedState(emptyList())
  val windows = remember(customWindows) { (TimeWindow.builtIns + customWindows).toImmutableList() }
  var selectedWindowId by retain { mutableStateOf(TimeWindow.forHour(currentHour)?.id) }
  var isManagingWindows by retain { mutableStateOf(false) }
  val selectedWindow = resolveSelectedWindow(selectedWindowId, windows, currentHour)
  // When the date rolls over at midnight, a selection of the old "today" moves to the new one.
  var lastToday by retain { mutableStateOf(today) }
  LaunchedEffect(today) {
    if (selectedDate == lastToday) {
      selectedDate = today
    }
    lastToday = today
  }
  val hours = searchHours(selectedWindow, isToday = selectedDate == today, currentHour)

  // Freshness across every area, recomputed when downloads land and at least hourly
  val lastUpdatesFlow = retain { repository.lastUpdatesFlow() }
  val lastUpdates by lastUpdatesFlow.collectAsRetainedState(emptyMap())
  val freshness =
    remember(lastUpdates, areas, now) { dataFreshness(areas.entries, lastUpdates, System.now()) }
  var isRefreshing by remember { mutableStateOf(false) }

  // Availability
  val availabilityFlow =
    retain(selectedDate, hours, areas) {
      repository.allPermitsInWindow(selectedDate, hours.startHour, hours.endHour).map { permits ->
        computeAvailability(permits, areas, hours.startHour, hours.endHour)
      }
    }
  val availability by availabilityFlow.collectAsRetainedState(null)

  // Weather
  val weather by weatherRepository.forecast.collectAsRetainedState(null)

  return FindFieldScreen.State(
    selectedDate = selectedDate,
    selectedWindow = hours.window,
    isToday = selectedDate == today,
    currentHour = currentHour,
    windows = windows,
    isManagingWindows = isManagingWindows,
    isMissingPendingData = availability?.isMissingPendingData == true && selectedDate >= today,
    freshness = freshness,
    sunset = remember(selectedDate) { nycSunset(selectedDate) },
    isRefreshing = isRefreshing,
    availability = availability,
    weather = weather,
  ) { event ->
    when (event) {
      is FindFieldScreen.Event.SelectWindow -> selectedWindowId = event.windowId
      is FindFieldScreen.Event.ShowWindowManager -> isManagingWindows = event.show
      is FindFieldScreen.Event.SaveWindow -> {
        val isNew = event.id == null
        if (isNew && customWindows.size >= TimeWindow.MAX_CUSTOM_WINDOWS) return@State
        val window =
          TimeWindow.custom(
            id = event.id ?: "custom-${System.now().toEpochMilliseconds()}",
            name = event.name.trim(),
            startHour = event.startHour,
            endHour = event.endHour,
          )
        if (isNew) {
          selectedWindowId = window.id
        }
        scope.launch { preferencesStore.saveCustomTimeWindow(window) }
      }
      is FindFieldScreen.Event.DeleteWindow -> {
        if (selectedWindowId == event.id) {
          selectedWindowId = TimeWindow.forHour(currentHour)?.id
        }
        scope.launch { preferencesStore.deleteCustomTimeWindow(event.id) }
      }
      is FindFieldScreen.Event.SelectDate -> selectedDate = event.date
      is FindFieldScreen.Event.NavigateToArea -> {
        val group = areas.groups[event.group] ?: return@State
        val area = areas.entries.find { it.areaName == group.area }
        val areaName = area?.displayName ?: group.area
        val subtitle = if (group.name != areaName) areaName else null
        navigator.goTo(
          AreaScreen(
            initialGroup = event.group,
            fixedTitle = group.name,
            fixedSubtitle = subtitle,
            selectedDate =
              selectedDate.atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds,
          )
        )
      }
      FindFieldScreen.Event.Refresh -> {
        isRefreshing = true
        weatherRepository.refresh()
        scope.launch {
          try {
            repository.refresh().await()
          } finally {
            isRefreshing = false
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@CircuitInject(FindFieldScreen::class, AppScope::class)
@Composable
fun FindField(state: FindFieldScreen.State, modifier: Modifier = Modifier) {
  val daySwipe = rememberDaySwipeState()
  val paneLayout = LocalPaneLayout.current
  val openArea = paneLayout.detailRoot as? AreaScreen
  val openGroup = openArea?.initialGroup
  // An open grid beside the list follows the list's date. The grid's own date chip changes only
  // the grid.
  val currentOpenArea by rememberUpdatedState(openArea)
  LaunchedEffect(state.selectedDate) {
    val area = currentOpenArea ?: return@LaunchedEffect
    val group = area.initialGroup ?: return@LaunchedEffect
    val listDate = state.selectedDate.atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds
    if (area.selectedDate != listDate) {
      state.eventSink(FindFieldScreen.Event.NavigateToArea(group))
    }
  }

  Column(
    modifier.fillMaxSize().daySwipeable(daySwipe, state.selectedDate) { newDate ->
      state.eventSink(FindFieldScreen.Event.SelectDate(newDate))
    }
  ) {
    // App title. Pull down on the list to refresh.
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "Field Spottr",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.weight(1f),
      )
    }
    // Updated-at, weather, and sunset on one quiet line that wraps on narrow screens
    val hasWeather = state.weather?.daily(state.selectedDate) != null
    FlowRow(
      modifier = Modifier.padding(horizontal = 16.dp),
      horizontalArrangement = spacedBy(6.dp),
    ) {
      state.freshness?.let { freshness ->
        Text(
          freshness.text,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (freshness.isStale) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
      if (state.freshness != null && hasWeather) {
        Text(
          "·",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      state.weather?.let { weather ->
        WeatherStrip(forecast = weather, date = state.selectedDate, isToday = state.isToday)
      }
      if (state.freshness != null || hasWeather) {
        Text(
          "·",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        "Sunset ${formatTime12(state.sunset)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(8.dp))

    // Date selector + filter chips row
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
    ) {
      DateSelector(
        state.selectedDate,
        id = "faf",
        contentScale = daySwipe.contentScale,
        weather = state.weather,
      ) { newDate ->
        state.eventSink(FindFieldScreen.Event.SelectDate(newDate))
      }
      LazyRow(horizontalArrangement = spacedBy(8.dp)) {
        items(state.visibleWindows, key = { it.id }) { window ->
          val startHour =
            if (state.isToday) max(window.startHour, state.currentHour) else window.startHour
          val isRainy =
            state.weather?.isRainyWindow(state.selectedDate, startHour, window.endHour) == true
          FilterChip(
            selected = state.selectedWindow == window,
            onClick = {
              state.eventSink(
                FindFieldScreen.Event.SelectWindow(
                  if (state.selectedWindow == window) null else window.id
                )
              )
            },
            label = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = spacedBy(4.dp),
              ) {
                Text(window.label(state.isToday))
                if (isRainy) {
                  WeatherGlyph(
                    WeatherCondition.RAIN,
                    size = 12.dp,
                    tint = MaterialTheme.colorScheme.tertiary,
                  )
                }
              }
            },
          )
        }
        item(key = "manage-windows") {
          FilterChip(
            selected = false,
            onClick = { state.eventSink(FindFieldScreen.Event.ShowWindowManager(true)) },
            label = { Icon(Icons.Outlined.Add, contentDescription = "Manage time windows") },
          )
        }
      }
    }
    Spacer(Modifier.height(8.dp))

    if (state.isManagingWindows) {
      TimeWindowsSheet(
        windows = state.windows,
        canAddWindow = state.canAddWindow,
        onSave = { id, name, startHour, endHour ->
          state.eventSink(FindFieldScreen.Event.SaveWindow(id, name, startHour, endHour))
        },
        onDelete = { id -> state.eventSink(FindFieldScreen.Event.DeleteWindow(id)) },
        onDismiss = { state.eventSink(FindFieldScreen.Event.ShowWindowManager(false)) },
      )
    }

    // Pull to refresh works on every state below, so the list is always the scrolling child
    PullToRefreshBox(
      isRefreshing = state.isRefreshing,
      onRefresh = { state.eventSink(FindFieldScreen.Event.Refresh) },
      modifier = Modifier.fillMaxSize(),
    ) {
      if (state.availability == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          AdaptiveCircularProgressIndicator()
        }
      } else {
        val (fullyOpen, partiallyOpen, closed) = state.availability
        val totalOpen = state.availability.totalOpen
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          item(key = "summary") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
              Text(
                "$totalOpen fields open",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (state.isMissingPendingData) {
                DataNote(PENDING_DATA_NOTE, modifier = Modifier.padding(top = 4.dp))
              }
            }
          }
          if (totalOpen == 0 && closed.isEmpty()) {
            item(key = "empty") {
              Box(
                Modifier.fillParentMaxWidth().fillParentMaxHeight(0.7f),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  "No open fields in this window",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          if (fullyOpen.isNotEmpty()) {
            item(key = "header-fully-open") { SectionHeader("FULLY OPEN") }
            items(fullyOpen, key = { "full-${it.group.name}" }) { field ->
              FieldAvailabilityRow(
                field = field,
                dotColor = MaterialTheme.colorScheme.primary,
                isSelected = field.group.name == openGroup,
                opensInPane = paneLayout.isSplit,
                onClick = {
                  state.eventSink(FindFieldScreen.Event.NavigateToArea(field.group.name))
                },
                modifier = Modifier.animateItem(),
              )
            }
          }
          if (partiallyOpen.isNotEmpty()) {
            item(key = "header-partially-open") { SectionHeader("PARTIALLY OPEN") }
            items(partiallyOpen, key = { "partial-${it.group.name}" }) { field ->
              FieldAvailabilityRow(
                field = field,
                dotColor = MaterialTheme.colorScheme.tertiary,
                isSelected = field.group.name == openGroup,
                opensInPane = paneLayout.isSplit,
                onClick = {
                  state.eventSink(FindFieldScreen.Event.NavigateToArea(field.group.name))
                },
                modifier = Modifier.animateItem(),
              )
            }
          }
          if (closed.isNotEmpty()) {
            item(key = "header-closed") { SectionHeader("CLOSED") }
            items(closed, key = { "closed-${it.group.name}" }) { field ->
              FieldAvailabilityRow(
                field = field,
                dotColor = MaterialTheme.colorScheme.error,
                isSelected = field.group.name == openGroup,
                opensInPane = paneLayout.isSplit,
                onClick = {
                  state.eventSink(FindFieldScreen.Event.NavigateToArea(field.group.name))
                },
                modifier = Modifier.animateItem(),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    letterSpacing = 1.sp,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
  )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FieldAvailabilityRow(
  field: FieldAvailability,
  dotColor: androidx.compose.ui.graphics.Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  /** The row's group is open in the pane beside the list. */
  isSelected: Boolean = false,
  /**
   * Tapping opens the area in the pane beside the list, as in the split layout. The row stays
   * visible, so it doesn't grow into the area screen or show a push chevron, and its highlight sits
   * inset from the pane edges.
   */
  opensInPane: Boolean = false,
) = SharedElementTransitionScope {
  val containerKey = AreaContainerSharedElementKey(field.group.name)
  val contentKey = AreaContentSharedElementKey(field.group.name)
  val navScope = requireAnimatedScope(Navigation)
  val sharesElements = !opensInPane
  val reflowKey = if (sharesElements) "area-${field.group.name}" else null
  // Content stays 16dp from the list edge either way
  val inset = if (opensInPane) 8.dp else 0.dp
  Box(
    modifier
      .fillMaxWidth()
      .padding(horizontal = inset)
      .clip(MaterialTheme.shapes.medium)
      .clickable(onClick = onClick)
  ) {
    // Background — sharedElement, always opaque, no cross-fade
    Spacer(
      Modifier.matchParentSize()
        .then(
          if (sharesElements) {
            Modifier.sharedElement(
              sharedContentState = rememberSharedContentState(containerKey),
              animatedVisibilityScope = navScope,
            )
          } else {
            Modifier
          }
        )
        .background(
          if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
          } else {
            MaterialTheme.colorScheme.surface
          }
        )
    )
    // Content cross-fades quickly inside the animated bounds.
    // Exit: fully gone by 30%. Enter: appears in the last 50%.
    val earlyEasing = Easing { (it / 0.3f).coerceAtMost(1f) }
    val lateEasing = Easing { ((it - 0.5f) / 0.5f).coerceIn(0f, 1f) }
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .then(
            if (sharesElements) {
              Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(contentKey),
                animatedVisibilityScope = navScope,
                enter = fadeIn(tween(easing = lateEasing)),
                exit = fadeOut(tween(easing = earlyEasing)),
                zIndexInOverlay = 1f,
              )
            } else {
              Modifier
            }
          )
          .padding(horizontal = 16.dp - inset, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(12.dp),
    ) {
      // Availability dot
      Box(Modifier.size(10.dp).background(dotColor, CircleShape))

      // Field info
      Column(modifier = Modifier.weight(1f)) {
        ReflowText(
          text = field.group.name,
          sharedElementKey = reflowKey,
          sharedElementKeySuffix = "title",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Bold,
          overflow = TextOverflow.Ellipsis,
        )
        if (field.areaDisplayName != field.group.name) {
          ReflowText(
            text = field.areaDisplayName,
            sharedElementKey = reflowKey,
            sharedElementKeySuffix = "subtitle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        for (opening in field.fieldOpenings) {
          Text(
            "${opening.fieldNames} · ${opening.openTimeRange}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            // Two lines fit the free times next to a wide time chip
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Time chip (or closed badge)
      if (field.closedReason != null) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.errorContainer,
        ) {
          Text(
            text =
              if (field.closedReason.length <= 24 && field.closedReason != "Closed") {
                "Closed · ${field.closedReason}"
              } else {
                "Closed"
              },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      } else {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            // Only some fields are free, and the opening lines list them
            text = field.openTimeRange.ifEmpty { "Some fields" },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      // Chevron
      if (!opensInPane) {
        Icon(
          Icons.AutoMirrored.Outlined.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The windows worth offering as chips. On today, windows that have already ended are hidden, so the
 * windows still ahead and the manage chip stay on screen.
 */
internal fun visibleWindows(
  windows: List<TimeWindow>,
  isToday: Boolean,
  currentHour: Int,
): List<TimeWindow> {
  return if (isToday) windows.filterNot { it.hasPassed(currentHour) } else windows
}

/**
 * The window for [selectedId] among [windows], or null for the whole day when [selectedId] is null.
 * A selected window that no longer exists, like a deleted custom window, falls back to the built-in
 * window for [currentHour].
 */
internal fun resolveSelectedWindow(
  selectedId: String?,
  windows: List<TimeWindow>,
  currentHour: Int,
): TimeWindow? {
  if (selectedId == null) return null
  return windows.find { it.id == selectedId } ?: TimeWindow.forHour(currentHour)
}

/** The window Find a Field searches and its hours, with an exclusive [endHour]. */
internal data class SearchHours(val window: TimeWindow?, val startHour: Int, val endHour: Int)

/**
 * Resolves the hours to search for [selected], where null means the whole day. On today, hours
 * before [currentHour] drop off the start, and a window that has already ended falls back to the
 * window that holds [currentHour], or to the rest of the day when no window holds it.
 */
internal fun searchHours(selected: TimeWindow?, isToday: Boolean, currentHour: Int): SearchHours {
  if (!isToday) {
    return SearchHours(selected, selected?.startHour ?: 0, selected?.endHour ?: 24)
  }
  val window =
    if (selected != null && selected.hasPassed(currentHour)) {
      TimeWindow.forHour(currentHour)
    } else {
      selected
    }
  return SearchHours(
    window = window,
    startHour = max(window?.startHour ?: 0, currentHour),
    endHour = window?.endHour ?: 24,
  )
}

internal fun computeAvailability(
  permits: List<DbPermit>,
  areas: Areas,
  startHour: Int,
  endHour: Int,
): AvailabilityBuckets {
  val permitsByAreaAndGroup = permits.groupBy { it.area to it.groupName }

  val fullyOpen = mutableListOf<FieldAvailability>()
  val partiallyOpen = mutableListOf<FieldAvailability>()
  val closed = mutableListOf<FieldAvailability>()
  var isMissingPendingData = false

  for (area in areas.entries) {
    for (group in area.fieldGroups) {
      if (group.closed != null) {
        closed.add(
          FieldAvailability(
            group = group,
            areaDisplayName = area.displayName,
            openTimeRange = "",
            isFullyOpen = false,
            closedReason = group.closed,
          )
        )
        continue
      }

      val groupPermits = permitsByAreaAndGroup[area.areaName to group.name].orEmpty()
      // Unverified BBP schedules cannot be recommended as available fields.
      if (
        area.areaName == "Brooklyn Bridge Park" &&
          group.name == "Pier 5" &&
          groupPermits.none { it.type == BbpAvailability.COVERAGE_KIND }
      )
        continue
      val bookedHours =
        bookedHoursForGroup(
          permits = groupPermits,
          area = area,
          startHour = startHour,
          endHour = endHour,
        )
      val groupMissesPendingData = groupPermits.isMissingPendingData(group)
      if (bookedHours.isEmpty()) {
        isMissingPendingData = isMissingPendingData || groupMissesPendingData
        fullyOpen.add(
          FieldAvailability(
            group = group,
            areaDisplayName = area.displayName,
            openTimeRange = formatHourRange(startHour, endHour),
            isFullyOpen = true,
          )
        )
      } else {
        val freeHours = (startHour until endHour).filter { it !in bookedHours }
        val openings =
          fieldOpenings(
            freeHoursByField(groupPermits, area, group, startHour, endHour),
            groupFreeHours = freeHours,
          )
        if (freeHours.isNotEmpty() || openings.isNotEmpty()) {
          isMissingPendingData = isMissingPendingData || groupMissesPendingData
          partiallyOpen.add(
            FieldAvailability(
              group = group,
              areaDisplayName = area.displayName,
              openTimeRange = formatFreeRanges(freeHours),
              isFullyOpen = false,
              fieldOpenings = openings,
            )
          )
        } else {
          // Fully booked. If city block/closure rows alone cover the whole window, surface the
          // group as closed (with a reason) instead of silently dropping it.
          val blockedPermits = groupPermits.filter { it.isBlocked }
          if (blockedPermits.isNotEmpty()) {
            val blockedHours =
              bookedHoursForGroup(
                permits = blockedPermits,
                area = area,
                startHour = startHour,
                endHour = endHour,
              )
            if ((startHour until endHour).all { it in blockedHours }) {
              val reason =
                blockedPermits.firstOrNull()?.name?.removePrefix("Closure: ")?.takeIf {
                  it.isNotBlank()
                } ?: "Closed"
              closed.add(
                FieldAvailability(
                  group = group,
                  areaDisplayName = area.displayName,
                  openTimeRange = "",
                  isFullyOpen = false,
                  closedReason = reason,
                )
              )
            }
          }
        }
      }
    }
  }

  return AvailabilityBuckets(
    fullyOpen = fullyOpen.toImmutableList(),
    partiallyOpen = partiallyOpen.toImmutableList(),
    closed = closed.toImmutableList(),
    isMissingPendingData = isMissingPendingData,
  )
}

private fun bookedHoursForGroup(
  permits: List<DbPermit>,
  area: Area,
  startHour: Int,
  endHour: Int,
): Set<Int> {
  if (permits.isEmpty()) return emptySet()

  val bookedHours = mutableSetOf<Int>()
  for (permit in permits) {
    if (!permit.isBooking) continue
    if (permit.fieldId !in area.fieldMappings) continue
    bookedHours += permit.bookedHours(startHour, endHour)
  }
  return bookedHours
}

/**
 * Free hours in [startHour, endHour) for each field in [group]. A permit blocks its own field and
 * every field that shares a surface with it (see `Field.overlapsWith`), so one booked half leaves
 * the other half free but blocks the whole field.
 */
private fun freeHoursByField(
  permits: List<DbPermit>,
  area: Area,
  group: FieldGroup,
  startHour: Int,
  endHour: Int,
): Map<Field, List<Int>> {
  val bookedByPermitField = mutableMapOf<Field, MutableSet<Int>>()
  for (permit in permits) {
    if (!permit.isBooking) continue
    val field = area.fieldMappings[permit.fieldId] ?: continue
    bookedByPermitField.getOrPut(field) { mutableSetOf() } += permit.bookedHours(startHour, endHour)
  }
  return group.fields.associateWith { field ->
    val booked = bookedByPermitField.filterKeys { it.overlapsWith(field) }.values.flatten().toSet()
    (startHour until endHour).filter { it !in booked }
  }
}

/**
 * Openings for fields that are free for more hours than [groupFreeHours], the hours when the whole
 * group is free. Fields with the same free hours share one opening, in catalog order.
 */
private fun fieldOpenings(
  freeHoursByField: Map<Field, List<Int>>,
  groupFreeHours: List<Int>,
): ImmutableList<FieldOpening> {
  return freeHoursByField.entries
    .filter { (_, free) -> free.isNotEmpty() && free != groupFreeHours }
    .groupBy({ it.value }, { it.key.displayName })
    .map { (free, names) -> FieldOpening(names.joinToString(", "), formatFreeRanges(free)) }
    .toImmutableList()
}

/** Whether this row books its field. Live overlays and coverage markers do not. */
private val DbPermit.isBooking: Boolean
  get() = !isAvailabilityOverlay && !isCoverageMarker

/**
 * The hours in [startHour, endHour) that this permit touches, counting a partial hour as booked.
 */
private fun DbPermit.bookedHours(startHour: Int, endHour: Int): IntRange {
  val permitStart = start.toNyLocalDateTime()
  val permitEnd = end.toNyLocalDateTime()
  val permitEndHour =
    when {
      permitEnd.hour == 0 && permitEnd.date > permitStart.date -> 24
      permitEnd.minute > 0 || permitEnd.second > 0 || permitEnd.nanosecond > 0 -> permitEnd.hour + 1
      else -> permitEnd.hour
    }
  return max(startHour, permitStart.hour) until min(endHour, permitEndHour)
}

private fun formatFreeRanges(freeHours: List<Int>): String {
  if (freeHours.isEmpty()) return ""
  val ranges = mutableListOf<Pair<Int, Int>>()
  var rangeStart = freeHours.first()
  var prev = rangeStart
  for (h in freeHours.drop(1)) {
    if (h == prev + 1) {
      prev = h
    } else {
      ranges.add(rangeStart to prev + 1)
      rangeStart = h
      prev = h
    }
  }
  ranges.add(rangeStart to prev + 1)
  return ranges.joinToString(", ") { (s, e) -> formatHourRange(s, e) }
}

internal fun formatHourRange(start: Int, end: Int): String {
  fun formatHour(h: Int): String {
    val h12 = if (h == 0 || h == 24) 12 else if (h > 12) h - 12 else h
    val ampm = if (h < 12 || h == 24) "am" else "pm"
    return "$h12$ampm"
  }

  val startAmPm = if (start < 12) "am" else "pm"
  val endAmPm = if (end < 12 || end == 24) "am" else "pm"
  val startH12 = if (start == 0 || start == 24) 12 else if (start > 12) start - 12 else start
  val endH12 = if (end == 0 || end == 24) 12 else if (end > 12) end - 12 else end

  return if (startAmPm == endAmPm) {
    "$startH12–$endH12$endAmPm"
  } else {
    "${formatHour(start)}–${formatHour(end)}"
  }
}
