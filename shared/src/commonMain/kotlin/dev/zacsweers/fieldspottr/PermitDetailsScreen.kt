// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.datetime.number

@CommonParcelize
data class PermitDetailsScreen(
  val fieldName: String,
  val index: Int,
  val name: String,
  val group: String,
  val timeRange: String,
  val org: String,
  val status: String,
) : Screen {
  data class State(
    val fieldName: String,
    val index: Int,
    val name: String,
    val group: String,
    val timeRange: String,
    val org: String,
    val status: String,
    val otherPermits: Map<String, List<OtherPermit>>?,
    val onBack: () -> Unit,
  ) : CircuitUiState
}

@Immutable
data class OtherPermit(val key: Long, val name: String, val date: String, val timeRange: String)

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
        dbPermits
          .map { dbPermit ->
            val start = dbPermit.start.toNyLocalDateTime()
            val end = dbPermit.end.toNyLocalDateTime()
            OtherPermit(
              key = dbPermit.recordId,
              name = dbPermit.name,
              date = "${start.date.month.number}/${start.date.day}",
              timeRange = "${start.formatNoAmPm()}—${end.formatAmPm()}",
            )
          }
          .groupBy { it.date }
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
    otherPermits = permits,
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
      // Fade out non-hero elements quickly — fully gone by 40% of the transition progress.
      // With predictive back this is gesture-driven, so we use easing rather than duration.
      val earlyFadeOut = fadeOut(tween(easing = Easing { (it / 0.35f).coerceAtMost(1f) }))
      val fadeModifier =
        with(animatedScope) {
          Modifier.animateEnterExit(enter = fadeIn(), exit = earlyFadeOut)
        }
      Box(modifier.fillMaxSize()) {
        // Background that fades with the transition, behind the hero card
        Box(
          Modifier.matchParentSize()
            .then(fadeModifier)
            .background(MaterialTheme.colorScheme.surface)
        )
        Scaffold(
          containerColor = Color.Transparent,
          topBar = {
            CenterAlignedTopAppBar(
              modifier = fadeModifier,
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
            // Hero header card — shared bounds target, green to match grid item
            Surface(
              onClick = state.onBack,
              modifier =
                Modifier.fillMaxWidth()
                  .sharedBounds(
                    sharedContentState = rememberSharedContentState(sharedBoundsKey),
                    animatedVisibilityScope = animatedScope,
                    clipInOverlayDuringTransition = OverlayClip(cardShape),
                  ),
              shadowElevation = 2.dp,
              shape = cardShape,
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Box(Modifier.padding(16.dp)) {
                Column(verticalArrangement = spacedBy(16.dp)) {
                  ReflowText(
                    text = state.name,
                    sharedElementKey = "permit-${state.fieldName}-${state.index}-title",
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
                    Text(
                      text = "Org: " + state.org,
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

            Column(fadeModifier) {
              Spacer(Modifier.height(16.dp))

              if (state.otherPermits == null) {
                Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Center) {
                  AdaptiveCircularProgressIndicator()
                }
              } else if (state.otherPermits.isNotEmpty()) {
                LazyColumn {
                  for ((date, otherPermits) in state.otherPermits) {
                    item(key = date) {
                      Box(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Text(
                          text = date,
                          style = MaterialTheme.typography.titleLarge,
                          fontWeight = FontWeight.Medium,
                        )
                      }
                    }
                    items(otherPermits, key = { it.key }) { permit ->
                      Column(Modifier.animateItem().fillMaxWidth().padding(start = 16.dp)) {
                        Text(
                          text = permit.timeRange,
                          fontWeight = FontWeight.Medium,
                          color = MaterialTheme.colorScheme.onSurface,
                          style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                          permit.name,
                          color = MaterialTheme.colorScheme.onSurface,
                          style = MaterialTheme.typography.bodyMedium,
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
