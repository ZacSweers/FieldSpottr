// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.util.formatAmPm
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import io.github.alexzhirkevich.cupertino.adaptive.AdaptiveCircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock.System

@CommonParcelize
data class PermitDetailsScreen(
  val name: String,
  val description: String,
  val group: String,
  val org: String,
) : Screen {
  data class State(
    val name: String,
    val description: String,
    val otherPermits: List<OtherPermit>?,
  ) : CircuitUiState
}

@Immutable
data class OtherPermit(val key: Long, val name: String, val date: String, val timeRange: String)

@Composable
fun PermitDetailsPresenter(
  screen: PermitDetailsScreen,
  repository: PermitRepository,
): PermitDetailsScreen.State {
  val today = rememberRetained { System.now().toNyLocalDateTime().date }
  val permitsFlow = rememberRetained {
    repository
      .permitsByGroup(screen.group, screen.org, today)
      .map { dbPermits ->
        dbPermits.map { dbPermit ->
          val start = dbPermit.start.toNyLocalDateTime()
          val end = dbPermit.end.toNyLocalDateTime()
          OtherPermit(
            key = dbPermit.recordId,
            name = dbPermit.name,
            date = "${start.date.monthNumber}/${start.date.dayOfMonth}",
            timeRange = "${start.formatAmPm()}-${end.formatAmPm()}",
          )
        }
      }
      .flowOn(Dispatchers.IO)
  }
  val permits by permitsFlow.collectAsRetainedState(null)
  return PermitDetailsScreen.State(screen.name, screen.description, otherPermits = permits)
}

@Composable
fun PermitDetails(state: PermitDetailsScreen.State, modifier: Modifier = Modifier) {
  Column(modifier.padding(16.dp)) {
    Text(
      text = state.name,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      overflow = TextOverflow.Ellipsis,
    )

    Spacer(Modifier.height(16.dp))

    Text(text = state.description, style = MaterialTheme.typography.bodyLarge)

    Spacer(Modifier.height(16.dp))

    if (state.otherPermits == null) {
      Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Center) {
        AdaptiveCircularProgressIndicator()
      }
    } else if (state.otherPermits.isNotEmpty()) {
      HorizontalDivider()
      LazyColumn {
        items(state.otherPermits, key = { it.key }) { permit ->
          Column(Modifier.padding(top = 16.dp).animateItemPlacement().fillMaxWidth()) {
            Row {
              Text(
                permit.date,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
              )
              Spacer(Modifier.width(4.dp))
              Text(
                permit.timeRange,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
              )
            }
            Text(
              permit.name,
              color = MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}
