@file:OptIn(ExperimentalMaterial3Api::class)

package dev.zacsweers.fieldspottr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Alignment.Companion.CenterEnd
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterStart
import androidx.compose.ui.Alignment.Companion.TopEnd
import androidx.compose.ui.Alignment.Companion.TopStart
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.overlay.ContentWithOverlays
import com.slack.circuit.overlay.LocalOverlayHost
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.presenter.presenterOf
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuitx.overlays.alertDialogOverlay
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import dev.zacsweers.fieldspottr.theme.FSTheme
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDateTime.Companion
import kotlinx.datetime.TimeZone
import kotlinx.datetime.TimeZone.Companion.UTC
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.char
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime

@Composable
fun FieldSpottrApp(permitRepository: PermitRepository, onRootPop: () -> Unit) {
  FSTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      val circuit = remember {
        Circuit.Builder()
          .addPresenter<HomeScreen, HomeScreen.State> { _, _, _ ->
            presenterOf { HomePresenter(permitRepository) }
          }
          .addUi<HomeScreen, HomeScreen.State> { state, modifier -> Home(state, modifier) }
          .build()
      }
      val backStack = rememberSaveableBackStack(HomeScreen)
      val navigator = rememberCircuitNavigator(backStack) { onRootPop() }
      CircuitCompositionLocals(circuit) {
        ContentWithOverlays {
          NavigableCircuitContent(navigator = navigator, backStack = backStack)
        }
      }
    }
  }
}

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val date: LocalDate,
    val selectedGroup: String,
    val loadingMessage: String?,
    val permits: PermitState?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data object Refresh : Event

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event
  }
}

@Composable
fun HomePresenter(repository: PermitRepository): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  var dbLoaded by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  var permits by rememberRetained { mutableStateOf<PermitState?>(null) }
  var selectedGroup by rememberRetained { mutableStateOf(Area.entries[0].fieldGroups[0].name) }
  if (!dbLoaded) {
    LaunchedEffect(Unit) {
      repository.populateDb()
      dbLoaded = true
    }
    loadingMessage = "Populating DB..."
  } else {
    LaunchedEffect(selectedDate, selectedGroup) {
      loadingMessage = "Loading permits..."
      permits =
        repository.loadPermits(selectedDate, selectedGroup).let { PermitState.fromPermits(it) }
      loadingMessage = null
    }
  }
  return HomeScreen.State(
    date = selectedDate,
    selectedGroup = selectedGroup,
    loadingMessage = loadingMessage,
    permits = permits,
  ) { event ->
    when (event) {
      is HomeScreen.Event.Refresh -> {
        dbLoaded = false
      }
      is HomeScreen.Event.FilterDate -> {
        selectedDate = event.date
      }
      is HomeScreen.Event.ChangeGroup -> {
        selectedGroup = event.group
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
  Scaffold(
    modifier = modifier,
    topBar = { CenterAlignedTopAppBar(title = { Text("Field Spottr") }) },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
  ) { innerPadding ->
    // TODO
    //  - Show a calendar view with the current date as a picker
    //  - Move progress to a snackbar?
    Column(Modifier.padding(innerPadding), verticalArrangement = spacedBy(16.dp)) {
      Row(
        horizontalArrangement = spacedBy(16.dp),
        modifier = Modifier.align(CenterHorizontally).padding(horizontal = 16.dp),
      ) {
        // TODO why doesn't this save
        DateSelector(state.date) { newDate ->
          state.eventSink(HomeScreen.Event.FilterDate(newDate))
        }

        // TODO why does this take the whole space
        GroupSelector(state.selectedGroup) { newGroup ->
          state.eventSink(HomeScreen.Event.ChangeGroup(newGroup))
        }
      }

      if (state.loadingMessage == null && state.permits == null) {
        Text("No permits found for today: ${state.date}")
      }
      val overlayHost = LocalOverlayHost.current
      val scope = rememberCoroutineScope()
      PermitGrid(state, modifier = Modifier.align(CenterHorizontally)) { event ->
        scope.launch {
          overlayHost.show(
            alertDialogOverlay(
              title = { Text(event.name) },
              text = { Text(event.org) },
              confirmButton = { onClick -> TextButton(onClick) { Text("Done") } },
              dismissButton = { onClick -> TextButton(onClick) { Text("Cancel") } },
            )
          )
        }
      }
    }
  }
}

@Composable
fun PermitGrid(
  state: HomeScreen.State,
  modifier: Modifier = Modifier,
  onEventClick: (DbPermit) -> Unit,
) {
  // TODO
  //  - Show a calendar view with the current date as a picker
  //  - Move progress to a snackbar?
  val group = Area.groups.getValue(state.selectedGroup)
  val fields = group.fields
  val numColumns = fields.size + 1
  LazyVerticalGrid(columns = GridCells.Fixed(numColumns), modifier = modifier.padding(16.dp)) {
    items(25 * numColumns) { index ->
      val rowNumber = index / numColumns
      val columnNumber = index % numColumns
      if (columnNumber == 0 && rowNumber == 0) {
        // Do nothing
      } else if (columnNumber == 0) {
        val adjustedTime = ((rowNumber - 1) % 12).let { if (it == 0) 12 else it }
        val amPm = if (rowNumber <= 12) "AM" else "PM"
        Box(Modifier.fillMaxWidth()) {
          Text(
            "$adjustedTime $amPm",
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(TopStart).fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
          )
          HorizontalDivider(
            modifier = Modifier.align(TopEnd).fillMaxWidth(0.25f),
            thickness = Dp.Hairline,
          )
        }
      } else if (rowNumber == 0) {
        // Name of the field as a header
        Box {
          Text(
            fields[columnNumber - 1].displayName,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
          )
          HorizontalDivider(modifier = Modifier.align(BottomCenter), thickness = Dp.Hairline)
        }
      } else {
        val event =
          state.permits?.let {
            state.permits.fields[fields[columnNumber - 1].displayName]?.permits?.let { permits ->
              permits[rowNumber - 1]
            }
          }

        Box(Modifier.width(150.dp).height(50.dp)) {
          event?.let {
            BasicPermitEvent(event = it, color = Color.Red, onEventClick = { onEventClick(event) })
          }
          if (columnNumber == 1) {
            VerticalDivider(thickness = Dp.Hairline, modifier = Modifier.align(CenterStart))
          }
          VerticalDivider(thickness = Dp.Hairline, modifier = Modifier.align(CenterEnd))
          HorizontalDivider(thickness = Dp.Hairline, modifier = Modifier.align(BottomCenter))
        }
      }
    }
  }
}

val EventTimeFormatter = LocalDateTime.Format {
  // "h:mm a"
  hour()
  char(':')
  minute()
  char(' ')
  amPmMarker("am", "pm")
}

@Composable
fun BasicPermitEvent(
  event: DbPermit,
  color: Color,
  modifier: Modifier = Modifier,
  onEventClick: ((DbPermit) -> Unit)? = null,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(2.dp)
        .clipToBounds()
        .background(
          color,
          shape =
            RoundedCornerShape(4.dp),
        )
        .padding(4.dp)
        .clickable(enabled = onEventClick != null) { onEventClick!!(event) }
  ) {
    Text(
      text =
        "${EventTimeFormatter.format(Instant.fromEpochMilliseconds(event.start).toLocalDateTime(NYC_TZ))} - ${EventTimeFormatter.format(Instant.fromEpochMilliseconds(event.end).toLocalDateTime(NYC_TZ))}",
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Clip,
    )

    Text(
      text = event.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    Text(
      text = event.org,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun DateSelector(
  currentDate: LocalDate,
  modifier: Modifier = Modifier,
  onDateSelected: (LocalDate) -> Unit,
) {
  var showDatePicker by rememberSaveable { mutableStateOf(false) }
  if (showDatePicker) {
    val current = currentDate.atStartOfDayIn(UTC).toEpochMilliseconds()
    val datePickerState = rememberDatePickerState(current)
    val confirmEnabled by remember { derivedStateOf { datePickerState.selectedDateMillis != null } }

    DatePickerDialog(
      modifier = modifier,
      onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(
          onClick = {
            showDatePicker = false
            val selected =
              Instant.fromEpochMilliseconds(datePickerState.selectedDateMillis!!)
                .toLocalDateTime(UTC)
                .date
            onDateSelected(selected)
          },
          enabled = confirmEnabled,
        ) {
          Text("Confirm")
        }
      },
      dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
    ) {
      DatePicker(datePickerState)
    }
  }
  OutlinedButton(onClick = { showDatePicker = true }) { Text(currentDate.toString()) }
}

@Composable
fun GroupSelector(
  selectedGroup: String,
  modifier: Modifier = Modifier,
  onGroupSelected: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    modifier = modifier,
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    OutlinedTextField(
      value = selectedGroup,
      modifier = Modifier.menuAnchor().wrapContentWidth(),
      textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      for (group in Area.groups.keys) {
        DropdownMenuItem(
          text = {
            Text(
              text = group,
              // TODO why do none of these work?
              style = LocalTextStyle.current.copy(textAlign = TextAlign.End),
              textAlign = TextAlign.End,
            )
          },
          onClick = {
            expanded = false
            onGroupSelected(group)
          },
        )
      }
    }
  }
}

@Stable
data class PermitState(val fields: Map<String, FieldState>) {
  @Stable
  data class FieldState(val permits: Map<Int, DbPermit>) {
    companion object {
      fun fromPermits(permits: List<DbPermit>): FieldState {
        val timeMappings: Map<Int, DbPermit> = buildMap {
          for (permit in permits.sortedBy { it.start }) {
            val durationHours = (permit.end - permit.start).milliseconds.inWholeHours
            val startHour = Instant.fromEpochMilliseconds(permit.start).toLocalDateTime(NYC_TZ).hour
            for (hour in startHour until startHour + durationHours) {
              put(hour.toInt(), permit)
            }
          }
        }
        return FieldState(timeMappings)
      }
    }
  }

  companion object {
    fun fromPermits(permits: List<DbPermit>): PermitState {
      val areasByName = Area.entries.associateBy { it.areaName }
      val fields =
        permits
          .groupBy { areasByName.getValue(it.area).fieldMappings.getValue(it.fieldId) }
          .mapKeys { it.key.displayName }
          .mapValues { (_, permits) -> FieldState.fromPermits(permits) }
      return PermitState(fields)
    }
  }
}
