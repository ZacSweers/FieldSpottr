// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.fieldspottr.data.TimeWindow
import dev.zacsweers.fieldspottr.ui.formatHour12
import dev.zacsweers.fieldspottr.util.TextFieldDropdown
import kotlinx.collections.immutable.ImmutableList

/** Custom window names are short so they fit in a chip. */
private const val MAX_WINDOW_NAME_LENGTH = 24

/** A window being created (null [id]) or edited in the sheet. */
private data class WindowDraft(
  val id: String?,
  val name: String,
  val startHour: Int,
  val endHour: Int,
)

/**
 * Bottom sheet that manages Find a Field time windows. It lists the built-in windows and the saved
 * custom windows, and switches to an editor in place to create or edit a custom window. Deleting is
 * explicit through each saved window's delete button.
 *
 * Every change animates. The list and the editor slide between each other while the sheet's height
 * follows, and saved windows grow in and shrink out as they're added and deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeWindowsSheet(
  windows: ImmutableList<TimeWindow>,
  canAddWindow: Boolean,
  onSave: (id: String?, name: String, startHour: Int, endHour: Int) -> Unit,
  onDelete: (id: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var editing by remember { mutableStateOf<WindowDraft?>(null) }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
      AnimatedContent(
        targetState = editing,
        transitionSpec = {
          // The editor comes in from the end, and the list comes back from the start
          val direction =
            if (targetState != null) {
              1
            } else {
              -1
            }
          (slideInHorizontally { width -> direction * width / 4 } + fadeIn()) togetherWith
            (slideOutHorizontally { width -> -direction * width / 4 } + fadeOut()) using
            SizeTransform()
        },
        label = "time windows sheet page",
      ) { draft ->
        // Each page lays its parts out in a column, and AnimatedContent stacks its pages in a box
        Column(Modifier.fillMaxWidth()) {
          if (draft == null) {
            WindowList(
              windows = windows,
              canAddWindow = canAddWindow,
              onNew = { editing = WindowDraft(id = null, name = "", startHour = 17, endHour = 20) },
              onEdit = { window ->
                editing = WindowDraft(window.id, window.name, window.startHour, window.endHour)
              },
              onDelete = onDelete,
            )
          } else {
            WindowEditor(
              draft = draft,
              onCancel = { editing = null },
              onSave = { saved ->
                onSave(saved.id, saved.name, saved.startHour, saved.endHour)
                editing = null
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun WindowList(
  windows: ImmutableList<TimeWindow>,
  canAddWindow: Boolean,
  onNew: () -> Unit,
  onEdit: (TimeWindow) -> Unit,
  onDelete: (String) -> Unit,
) {
  Text("Time windows", style = MaterialTheme.typography.titleLarge)

  SheetSectionLabel("BUILT IN")
  for (window in windows.filter { it.builtIn }) {
    WindowRow(window)
  }

  SheetSectionLabel("SAVED")
  val custom = windows.filterNot { it.builtIn }
  AnimatedVisibility(visible = custom.isEmpty(), enter = RowEnter, exit = RowExit) {
    Text(
      "No saved windows yet",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(vertical = 8.dp),
    )
  }
  AnimatedRows(items = custom, keyOf = TimeWindow::id) { window ->
    WindowRow(window) {
      IconButton(onClick = { onEdit(window) }) {
        Icon(Icons.Outlined.Edit, contentDescription = "Edit ${window.name}")
      }
      IconButton(onClick = { onDelete(window.id) }) {
        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${window.name}")
      }
    }
  }

  Spacer(Modifier.height(16.dp))
  Button(onClick = onNew, enabled = canAddWindow, modifier = Modifier.fillMaxWidth()) {
    Text("New window")
  }
  AnimatedVisibility(visible = !canAddWindow, enter = RowEnter, exit = RowExit) {
    Text(
      "You can save up to ${TimeWindow.MAX_CUSTOM_WINDOWS} windows.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/** Rows grow in and shrink out, so the content below them moves instead of jumping. */
private val RowEnter = expandVertically(expandFrom = Alignment.Top) + fadeIn()
private val RowExit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()

/**
 * Lays out [items] one under another and animates rows in and out with [RowEnter] and [RowExit].
 * Rows present when this first composes show without animating. A removed row stays where it was
 * until its exit finishes.
 */
@Composable
internal fun <T> AnimatedRows(items: List<T>, keyOf: (T) -> Any, row: @Composable (T) -> Unit) {
  // The rows on screen, including removed rows that are still leaving
  var shown by remember { mutableStateOf(items) }
  val initialKeys = remember { items.mapTo(HashSet(), keyOf) }
  val currentKeys = remember(items) { items.mapTo(HashSet(), keyOf) }
  val merged = remember(shown, items) { mergeKeepingRemoved(shown, items, keyOf) }
  SideEffect { shown = merged }
  Column(Modifier.fillMaxWidth()) {
    for (item in merged) {
      val itemKey = keyOf(item)
      key(itemKey) {
        val visibility = remember { MutableTransitionState(itemKey in initialKeys) }
        val isCurrent = itemKey in currentKeys
        SideEffect { visibility.targetState = isCurrent }
        // Drop a removed row once it has finished leaving
        if (!isCurrent && visibility.isIdle && !visibility.currentState) {
          SideEffect { shown = shown.filterNot { keyOf(it) == itemKey } }
        }
        AnimatedVisibility(visibleState = visibility, enter = RowEnter, exit = RowExit) {
          row(item)
        }
      }
    }
  }
}

/**
 * [current] with each row of [previous] that's missing from it put back after the row it followed,
 * so a removed row leaves from where it was.
 */
internal fun <T> mergeKeepingRemoved(
  previous: List<T>,
  current: List<T>,
  keyOf: (T) -> Any,
): List<T> {
  val currentKeys = current.mapTo(HashSet(), keyOf)
  val merged = current.toMutableList()
  var insertAt = 0
  for (item in previous) {
    val itemKey = keyOf(item)
    if (itemKey in currentKeys) {
      insertAt = merged.indexOfFirst { keyOf(it) == itemKey } + 1
    } else {
      merged.add(insertAt, item)
      insertAt++
    }
  }
  return merged
}

@Composable
private fun WindowRow(window: TimeWindow, actions: @Composable () -> Unit = {}) {
  Row(
    modifier = Modifier.fillMaxWidth().height(48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(8.dp),
  ) {
    Text(
      window.name,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      formatHourRange(window.startHour, window.endHour),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    actions()
  }
}

@Composable
private fun WindowEditor(
  draft: WindowDraft,
  onCancel: () -> Unit,
  onSave: (WindowDraft) -> Unit,
) {
  var name by remember(draft) { mutableStateOf(draft.name) }
  var startHour by remember(draft) { mutableStateOf(draft.startHour) }
  var endHour by remember(draft) { mutableStateOf(draft.endHour) }

  Text(
    if (draft.id == null) "New time window" else "Edit time window",
    style = MaterialTheme.typography.titleLarge,
  )
  Spacer(Modifier.height(16.dp))
  OutlinedTextField(
    value = name,
    onValueChange = { name = it.take(MAX_WINDOW_NAME_LENGTH) },
    label = { Text("Name") },
    placeholder = { Text("After work") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  Spacer(Modifier.height(8.dp))
  Row(horizontalArrangement = spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
    TextFieldDropdown(
      label = "From",
      currentValue = startHour,
      allValues = (0..23).toList(),
      setValue = { hour ->
        startHour = hour
        // Keep the end after the start
        if (endHour <= hour) {
          endHour = hour + 1
        }
      },
      displayName = ::hourOptionLabel,
      modifier = Modifier.weight(1f),
    )
    TextFieldDropdown(
      label = "To",
      currentValue = endHour,
      allValues = (startHour + 1..24).toList(),
      setValue = { hour -> endHour = hour },
      displayName = ::hourOptionLabel,
      modifier = Modifier.weight(1f),
    )
  }
  Spacer(Modifier.height(24.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = spacedBy(8.dp, Alignment.End),
  ) {
    TextButton(onClick = onCancel) { Text("Cancel") }
    Button(
      onClick = { onSave(WindowDraft(draft.id, name.trim(), startHour, endHour)) },
      enabled = name.isNotBlank(),
    ) {
      Text("Save")
    }
  }
}

@Composable
private fun SheetSectionLabel(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Medium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    letterSpacing = 1.sp,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

private fun hourOptionLabel(hour: Int): String {
  return if (hour == 24) "Midnight" else formatHour12(hour)
}
