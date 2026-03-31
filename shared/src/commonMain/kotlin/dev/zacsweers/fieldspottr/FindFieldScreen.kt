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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.sharedelements.SharedElementTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.FieldGroup
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.data.TimeWindow
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.ReflowText
import dev.zacsweers.fieldspottr.util.daySwipeable
import dev.zacsweers.fieldspottr.util.rememberDaySwipeState
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Immutable
data class FieldAvailability(
  val group: FieldGroup,
  val areaDisplayName: String,
  val openTimeRange: String,
  val isFullyOpen: Boolean,
)

@CommonParcelize
data object FindFieldScreen : Screen {
  data class State(
    val selectedDate: LocalDate,
    val selectedWindow: TimeWindow?,
    val isToday: Boolean,
    val lastUpdated: String?,
    val availability: Pair<ImmutableList<FieldAvailability>, ImmutableList<FieldAvailability>>?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data class SelectWindow(val window: TimeWindow?) : Event

    data class SelectDate(val date: LocalDate) : Event

    data class NavigateToArea(val group: String) : Event

    data object Refresh : Event
  }
}

@Composable
fun FindFieldPresenter(repository: PermitRepository, navigator: Navigator): FindFieldScreen.State {
  val areasFlow = rememberRetained { repository.areasFlow() }
  val areas by areasFlow.collectAsRetainedState()
  val today = remember { System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }
  var selectedDate by rememberRetained { mutableStateOf(today) }
  var selectedWindow by rememberRetained { mutableStateOf<TimeWindow?>(TimeWindow.AFTERNOON) }

  val scope = rememberCoroutineScope()

  // Last updated — use the first area as a representative
  val firstAreaName = remember(areas) { areas.entries.firstOrNull()?.areaName }
  val lastUpdateFlow =
    rememberRetained(firstAreaName) {
      if (firstAreaName != null) repository.lastUpdateFlow(firstAreaName) else flowOf(null)
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

  // Availability
  val startHour = selectedWindow?.startHour ?: 0
  val endHour = selectedWindow?.endHour ?: 24
  val availabilityFlow =
    rememberRetained(selectedDate, selectedWindow, areas) {
      repository.allPermitsInWindow(selectedDate, startHour, endHour).map { permits ->
        computeAvailability(permits, areas, startHour, endHour)
      }
    }
  val availability by availabilityFlow.collectAsRetainedState(null)

  return FindFieldScreen.State(
    selectedDate = selectedDate,
    selectedWindow = selectedWindow,
    isToday = selectedDate == today,
    lastUpdated = lastUpdatedText,
    availability = availability,
  ) { event ->
    when (event) {
      is FindFieldScreen.Event.SelectWindow -> selectedWindow = event.window
      is FindFieldScreen.Event.SelectDate -> selectedDate = event.date
      is FindFieldScreen.Event.NavigateToArea -> {
        val group = areas.groups[event.group] ?: return@State
        val area = areas.entries.find { it.areaName == group.area }
        val areaName = area?.displayName ?: group.area
        val subtitle = if (group.name != areaName) areaName else null
        navigator.goTo(
          AreaScreen(initialGroup = event.group, fixedTitle = group.name, fixedSubtitle = subtitle)
        )
      }
      FindFieldScreen.Event.Refresh -> {
        scope.launch { repository.populateDb(forceRefresh = true) }
      }
    }
  }
}

@Composable
fun FindField(state: FindFieldScreen.State, modifier: Modifier = Modifier) {
  val daySwipe = rememberDaySwipeState()

  Column(
    modifier.fillMaxSize().daySwipeable(daySwipe, state.selectedDate) { newDate ->
      state.eventSink(FindFieldScreen.Event.SelectDate(newDate))
    }
  ) {
    // App title + refresh
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "Field Spottr",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = { state.eventSink(FindFieldScreen.Event.Refresh) }) {
        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
      }
    }
    state.lastUpdated?.let {
      Text(
        it,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
      )
    }
    Spacer(Modifier.height(8.dp))

    // Date selector + filter chips row
    Row(
      modifier = Modifier.padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(8.dp),
    ) {
      DateSelector(state.selectedDate, contentScale = daySwipe.contentScale) { newDate ->
        state.eventSink(FindFieldScreen.Event.SelectDate(newDate))
      }
      LazyRow(horizontalArrangement = spacedBy(8.dp)) {
        items(TimeWindow.entries.toList()) { window ->
          FilterChip(
            selected = state.selectedWindow == window,
            onClick = {
              state.eventSink(
                FindFieldScreen.Event.SelectWindow(
                  if (state.selectedWindow == window) null else window
                )
              )
            },
            label = { Text(window.label(state.isToday)) },
          )
        }
      }
    }
    Spacer(Modifier.height(8.dp))

    if (state.availability == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AdaptiveCircularProgressIndicator()
      }
    } else {
      val (fullyOpen, partiallyOpen) = state.availability
      val totalOpen = fullyOpen.size + partiallyOpen.size
      Text(
        "$totalOpen fields open",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
      )
      Spacer(Modifier.height(8.dp))

      if (totalOpen == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            "No open fields in this window",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          if (fullyOpen.isNotEmpty()) {
            item(key = "header-fully-open") { SectionHeader("FULLY OPEN") }
            items(fullyOpen, key = { "full-${it.group.name}" }) { field ->
              FieldAvailabilityRow(
                field = field,
                dotColor = MaterialTheme.colorScheme.primary,
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
) = SharedElementTransitionScope {
  val containerKey = AreaContainerSharedElementKey(field.group.name)
  val contentKey = AreaContentSharedElementKey(field.group.name)
  val navScope = requireAnimatedScope(Navigation)
  Box(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
    // Background — sharedElement, always opaque, no cross-fade
    Spacer(
      Modifier.matchParentSize()
        .sharedElement(
          sharedContentState = rememberSharedContentState(containerKey),
          animatedVisibilityScope = navScope,
        )
        .background(MaterialTheme.colorScheme.surface)
    )
    // Content cross-fades quickly inside the animated bounds.
    // Exit: fully gone by 30%. Enter: appears in the last 50%.
    val earlyEasing = Easing { (it / 0.3f).coerceAtMost(1f) }
    val lateEasing = Easing { ((it - 0.5f) / 0.5f).coerceIn(0f, 1f) }
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .sharedBounds(
            sharedContentState = rememberSharedContentState(contentKey),
            animatedVisibilityScope = navScope,
            enter = fadeIn(tween(easing = lateEasing)),
            exit = fadeOut(tween(easing = earlyEasing)),
            zIndexInOverlay = 1f,
          )
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = spacedBy(12.dp),
    ) {
      // Availability dot
      Box(Modifier.size(10.dp).background(dotColor, CircleShape))

      // Field info
      Column(modifier = Modifier.weight(1f)) {
        ReflowText(
          text = field.group.name,
          sharedElementKey = "area-${field.group.name}",
          sharedElementKeySuffix = "title",
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Bold,
          overflow = TextOverflow.Ellipsis,
        )
        if (field.areaDisplayName != field.group.name) {
          ReflowText(
            text = field.areaDisplayName,
            sharedElementKey = "area-${field.group.name}",
            sharedElementKeySuffix = "subtitle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Time chip
      Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
      ) {
        Text(
          text = field.openTimeRange,
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }

      // Chevron
      Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

internal fun computeAvailability(
  permits: List<DbPermit>,
  areas: Areas,
  startHour: Int,
  endHour: Int,
): Pair<ImmutableList<FieldAvailability>, ImmutableList<FieldAvailability>> {
  val permitsByGroup = permits.groupBy { it.groupName }

  val fullyOpen = mutableListOf<FieldAvailability>()
  val partiallyOpen = mutableListOf<FieldAvailability>()

  for (area in areas.entries) {
    for (group in area.fieldGroups) {
      if (group.closed != null) continue

      val groupPermits = permitsByGroup[group.name].orEmpty()
      if (groupPermits.isEmpty()) {
        fullyOpen.add(
          FieldAvailability(
            group = group,
            areaDisplayName = area.displayName,
            openTimeRange = formatHourRange(startHour, endHour),
            isFullyOpen = true,
          )
        )
      } else {
        val bookedHours = mutableSetOf<Int>()
        for (permit in groupPermits) {
          val pStart = permit.start.toNyLocalDateTime().hour
          val pEnd = permit.end.toNyLocalDateTime().hour.let { if (it == 0) 24 else it }
          for (h in pStart until pEnd) bookedHours.add(h)
        }
        val freeHours = (startHour until endHour).filter { it !in bookedHours }
        if (freeHours.isNotEmpty()) {
          partiallyOpen.add(
            FieldAvailability(
              group = group,
              areaDisplayName = area.displayName,
              openTimeRange = formatFreeRanges(freeHours),
              isFullyOpen = false,
            )
          )
        }
      }
    }
  }

  return fullyOpen.toImmutableList() to partiallyOpen.toImmutableList()
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

private fun formatHourRange(start: Int, end: Int): String {
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
