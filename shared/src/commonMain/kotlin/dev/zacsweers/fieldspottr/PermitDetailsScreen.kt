// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.ui.Group
import dev.zacsweers.fieldspottr.ui.Schedule
import dev.zacsweers.fieldspottr.util.DragToDismiss
import dev.zacsweers.fieldspottr.util.ReflowText
import dev.zacsweers.fieldspottr.util.formatAmPm
import dev.zacsweers.fieldspottr.util.formatNoAmPm
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import kotlin.time.Clock.System
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@CommonParcelize
data class PermitDetailsScreen(
  val fieldName: String,
  val index: Int,
  val name: String,
  val group: String,
  val timeRange: String,
  val org: String,
  val status: String,
  val orgVisible: Boolean,
) : Screen {
  data class State(
    val fieldName: String,
    val index: Int,
    val name: String,
    val group: String,
    val timeRange: String,
    val org: String,
    val status: String,
    val orgVisible: Boolean,
    val otherPermits: Map<String, List<PermitDayGroup>>?,
    val totalPermitCount: Int,
    val onBack: () -> Unit,
  ) : CircuitUiState
}

@Immutable
data class PermitDayGroup(
  val dayOfMonth: Int,
  val dayOfWeek: String,
  val month: String,
  val isToday: Boolean,
  val events: List<PermitEvent>,
) {
  val key: String = "$month-$dayOfMonth"
  val hasMultipleEvents: Boolean = events.size > 1
  val primaryEvent: PermitEvent = events.first()
}

@Immutable data class PermitEvent(val key: Long, val name: String, val timeRange: String)

@Composable
fun PermitDetailsPresenter(
  screen: PermitDetailsScreen,
  repository: PermitRepository,
  navigator: Navigator,
): PermitDetailsScreen.State {
  val today = rememberRetained { System.now().toNyLocalDateTime().date }
  val permitsFlow = rememberRetained {
    repository
      .permitsByGroup(screen.group, screen.org, today)
      .map { dbPermits ->
        // Group permits by date, then by month
        dbPermits
          .groupBy { it.start.toNyLocalDateTime().date }
          .map { (date, permitsOnDate) ->
            PermitDayGroup(
              dayOfMonth = date.day,
              dayOfWeek =
                date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
              month = date.month.name.uppercase(),
              isToday = date == today,
              events =
                permitsOnDate.map { dbPermit ->
                  val start = dbPermit.start.toNyLocalDateTime()
                  val end = dbPermit.end.toNyLocalDateTime()
                  PermitEvent(
                    key = dbPermit.recordId,
                    name = dbPermit.name,
                    timeRange = "${start.formatNoAmPm()}–${end.formatAmPm()}",
                  )
                },
            )
          }
          .groupBy { it.month }
      }
      .flowOn(Dispatchers.IO)
  }
  val permits by permitsFlow.collectAsRetainedState(null)
  return PermitDetailsScreen.State(
    screen.fieldName,
    screen.index,
    screen.name,
    screen.group,
    screen.timeRange,
    screen.org,
    screen.status,
    orgVisible = screen.orgVisible,
    otherPermits = permits,
    totalPermitCount =
      remember(permits) { permits?.values?.sumOf { days -> days.sumOf { it.events.size } } ?: 0 },
    onBack = navigator::pop,
  )
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PermitDetails(state: PermitDetailsScreen.State, modifier: Modifier = Modifier) =
  SharedElementTransitionScope {
    val sharedBoundsKey =
      PermitSharedElementKey(state.fieldName, state.index, state.name, state.timeRange, state.org)
    val animatedScope = requireAnimatedScope(Navigation)
    val headerTextColor = MaterialTheme.colorScheme.onSecondaryContainer

    DragToDismiss(onDismiss = state.onBack) {
      // Non-hero elements exit quickly — fully gone by 35% of the transition progress.
      // With predictive back this is gesture-driven, so we use easing rather than duration.
      val earlyEasing = Easing { (it / 0.35f).coerceAtMost(1f) }
      val earlyFadeOut = fadeOut(tween(easing = earlyEasing))
      // Background just fades
      val bgModifier =
        with(animatedScope) { Modifier.animateEnterExit(enter = fadeIn(), exit = earlyFadeOut) }

      // Top bar slides up from behind the header and back down on exit
      val topBarModifier =
        with(animatedScope) {
          Modifier.animateEnterExit(
            enter = fadeIn() + slideInVertically { it },
            exit = earlyFadeOut + slideOutVertically(tween(easing = earlyEasing)) { it },
          )
        }

      // Bottom content slides down and fades
      val bottomContentModifier =
        with(animatedScope) {
          Modifier.animateEnterExit(
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = earlyFadeOut + slideOutVertically(tween(easing = earlyEasing)) { it / 3 },
          )
        }

      Box(modifier.fillMaxSize()) {
        // Background that fades with the transition, behind the hero card
        Box(
          Modifier.matchParentSize().then(bgModifier).background(MaterialTheme.colorScheme.surface)
        )
        Scaffold(
          containerColor = Color.Transparent,
          topBar = {
            CenterAlignedTopAppBar(
              modifier = topBarModifier,
              title = {},
              navigationIcon = {
                IconButton(onClick = state.onBack) {
                  Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
              },
              colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
          },
        ) { innerPadding ->
          val cardShape = MaterialTheme.shapes.large
          Column(Modifier.padding(innerPadding).padding(horizontal = 16.dp)) {
            // Hero header card. No container fade (background stays solid).
            // Detail on top (zIndex=1) so it's visible during back transition.
            Surface(
              onClick = state.onBack,
              modifier =
                Modifier.fillMaxWidth()
                  .sharedBounds(
                    sharedContentState = rememberSharedContentState(sharedBoundsKey),
                    animatedVisibilityScope = animatedScope,
                    clipInOverlayDuringTransition = OverlayClip(cardShape),
                    // Default fade — detail content cross-fades with grid underneath.
                    // Grid provides solid background (its exit is slow), so no alpha dip.
                    enter = fadeIn(),
                    exit = fadeOut(),
                    // Render above grid container (z=0) so detail dissolves over it on back nav
                    zIndexInOverlay = 1f,
                  ),
              shape = cardShape,
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Box(Modifier.padding(16.dp)) {
                Column(verticalArrangement = spacedBy(16.dp)) {
                  ReflowText(
                    text = state.name,
                    sharedElementKey = "permit-${state.fieldName}-${state.index}",
                    sharedElementKeySuffix = "title",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                    color = headerTextColor,
                  )

                  Row(horizontalArrangement = spacedBy(4.dp)) {
                    Icon(
                      Icons.Schedule,
                      contentDescription = "Schedule icon",
                      tint = headerTextColor,
                    )
                    Text(
                      text = state.timeRange,
                      style = MaterialTheme.typography.bodyLarge,
                      color = headerTextColor,
                    )
                  }

                  Row(horizontalArrangement = spacedBy(4.dp)) {
                    Icon(Icons.Group, contentDescription = "Group icon", tint = headerTextColor)
                    ReflowText(
                      text = state.org,
                      sharedElementKey =
                        if (state.orgVisible) "permit-${state.fieldName}-${state.index}" else null,
                      sharedElementKeySuffix = "org",
                      style = MaterialTheme.typography.bodyLarge,
                      color = headerTextColor,
                    )
                  }

                  Row(horizontalArrangement = spacedBy(4.dp)) {
                    Icon(
                      Icons.Default.Check,
                      contentDescription = "Check icon",
                      tint = headerTextColor,
                    )
                    Text(
                      text = "Status: " + state.status,
                      style = MaterialTheme.typography.bodyLarge,
                      color = headerTextColor,
                    )
                  }
                }
              }
            }

            Column(bottomContentModifier) {
              Spacer(Modifier.height(16.dp))

              if (state.otherPermits == null) {
                Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Center) {
                  AdaptiveCircularProgressIndicator()
                }
              } else if (state.otherPermits.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Center) {
                  Text(
                    text = "No upcoming events!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              } else {
                // Header: "Upcoming" + event count
                Row(
                  Modifier.fillMaxWidth().padding(bottom = 8.dp),
                  horizontalArrangement = spacedBy(8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "Upcoming",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                  )
                  Text(
                    text = "${state.totalPermitCount} events",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                HorizontalDivider()

                LazyColumn {
                  for ((month, dayGroups) in state.otherPermits) {
                    // Sticky month header
                    stickyHeader(key = "header-$month") {
                      Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(
                          text = month,
                          style = MaterialTheme.typography.labelMedium,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          letterSpacing = 1.sp,
                          modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        )
                      }
                    }
                    items(dayGroups, key = { it.key }) { dayGroup ->
                      var expanded by remember { mutableStateOf(false) }
                      val toggleExpand =
                        if (dayGroup.hasMultipleEvents) {
                          { expanded = !expanded }
                        } else {
                          null
                        }
                      Column(
                        Modifier.animateItem()
                          .fillMaxWidth()
                          .animateContentSize()
                          .clip(MaterialTheme.shapes.small)
                          .then(
                            if (toggleExpand != null) {
                              Modifier.clickable(onClick = toggleExpand)
                            } else {
                              Modifier
                            }
                          )
                          .padding(horizontal = 8.dp)
                      ) {
                        PermitDayRow(
                          dayGroup = dayGroup,
                          event = dayGroup.primaryEvent,
                          showDot = dayGroup.hasMultipleEvents && !expanded,
                        )
                        // Expanded additional events
                        if (expanded) {
                          dayGroup.events.drop(1).forEach { event ->
                            PermitDayRow(dayGroup = dayGroup, event = event, showDate = false)
                          }
                        }
                        HorizontalDivider(
                          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                      }
                    }
                  }
                }
                Spacer(Modifier.height(16.dp))
              }
            }
          }
        }
      }
    }
  }

@Composable
private fun PermitDayRow(
  dayGroup: PermitDayGroup,
  event: PermitEvent,
  showDate: Boolean = true,
  showDot: Boolean = false,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(12.dp),
  ) {
    if (showDate) {
      // Day number
      Text(
        text = "${dayGroup.dayOfMonth}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(min = 32.dp),
      )
      // Day of week
      Text(
        text = dayGroup.dayOfWeek,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 36.dp),
      )
      // Today badge
      if (dayGroup.isToday) {
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = "today",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    } else {
      // Indent to align with events above
      Spacer(Modifier.widthIn(min = 32.dp))
      Spacer(Modifier.widthIn(min = 36.dp))
    }
    // Time chip
    Surface(
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Text(
        text = event.timeRange,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
    // Event name
    Text(
      text = event.name,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // Multi-event dot indicator
    if (showDot) {
      Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
  }
}
