// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.fieldspottr.data.TimeWindow
import dev.zacsweers.fieldspottr.ui.EmphasizedEasing
import dev.zacsweers.fieldspottr.ui.Visibility
import dev.zacsweers.fieldspottr.ui.VisibilityOff
import dev.zacsweers.fieldspottr.ui.formatHour12
import dev.zacsweers.fieldspottr.util.TextFieldDropdown
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

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
 * Bottom sheet that manages Find a Field time windows. It lists the built-in windows, each with an
 * eye toggle that shows or hides its chip, and the saved custom windows. Deleting is explicit
 * through each saved window's delete button.
 *
 * Every change animates. The New window button grows up into the editor for a new window and
 * shrinks back into the button when the editor closes. A tap anywhere else in the sheet closes that
 * editor like Cancel does. Editing a saved window slides to an editor page and back while the
 * sheet's height follows. Saved windows grow in and shrink out as they're added and deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeWindowsSheet(
  windows: ImmutableList<TimeWindow>,
  hiddenBuiltIns: ImmutableSet<String>,
  canAddWindow: Boolean,
  onSave: (id: String?, name: String, startHour: Int, endHour: Int) -> Unit,
  onDelete: (id: String) -> Unit,
  onSetHidden: (id: String, hidden: Boolean) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var editing by remember { mutableStateOf<WindowDraft?>(null) }
  val save: (WindowDraft) -> Unit = { saved ->
    onSave(saved.id, saved.name, saved.startHour, saved.endHour)
    editing = null
  }
  val newWindowBounds = remember { OutsideTapArea() }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
    Column(
      Modifier.fillMaxWidth()
        .closeOnOutsideTap(
          area = newWindowBounds,
          enabled = editing.let { it != null && it.id == null },
          onClose = { editing = null },
        )
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(bottom = 24.dp)
    ) {
      // A saved window's editor is its own page. A new window's editor grows out of the New window
      // button on the list page instead.
      AnimatedContent(
        targetState = editing?.takeIf { it.id != null },
        transitionSpec = {
          // The editor pushes in from the end, and the list comes back from the start. Full-width
          // slides keep the two pages from overlapping, so no fade is needed.
          val direction =
            if (targetState != null) {
              1
            } else {
              -1
            }
          slideInHorizontally(PageSlideSpec) { width -> direction * width } togetherWith
            slideOutHorizontally(PageSlideSpec) { width -> -direction * width } using
            SizeTransform { _, _ -> PageSizeSpec }
        },
        label = "time windows sheet page",
      ) { draft ->
        // Each page lays its parts out in a column, and AnimatedContent stacks its pages in a box.
        // The pages hold the side padding, so they slide all the way to the sheet's edges.
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
          if (draft == null) {
            WindowList(
              windows = windows,
              hiddenBuiltIns = hiddenBuiltIns,
              canAddWindow = canAddWindow,
              onSetHidden = onSetHidden,
              onEdit = { window ->
                editing = WindowDraft(window.id, window.name, window.startHour, window.endHour)
              },
              onDelete = onDelete,
            ) {
              NewWindowButton(
                draft = editing?.takeIf { it.id == null },
                enabled = canAddWindow,
                onClick = {
                  editing = WindowDraft(id = null, name = "", startHour = 17, endHour = 20)
                },
                modifier = Modifier.onGloballyPositioned { newWindowBounds.inside = it },
              ) { newDraft ->
                WindowEditor(draft = newDraft, onCancel = { editing = null }, onSave = save)
              }
            }
          } else {
            WindowEditor(draft = draft, onCancel = { editing = null }, onSave = save)
          }
        }
      }
    }
  }
}

@Composable
private fun WindowList(
  windows: ImmutableList<TimeWindow>,
  hiddenBuiltIns: ImmutableSet<String>,
  canAddWindow: Boolean,
  onSetHidden: (id: String, hidden: Boolean) -> Unit,
  onEdit: (TimeWindow) -> Unit,
  onDelete: (String) -> Unit,
  newWindowButton: @Composable () -> Unit,
) {
  Text("Time windows", style = MaterialTheme.typography.titleLarge)

  SheetSectionLabel("BUILT IN")
  for (window in windows.filter { it.builtIn }) {
    val isShown = window.id !in hiddenBuiltIns
    WindowRow(window, isDimmed = !isShown) {
      // An open eye in the primary color while the chip shows, and a crossed-out eye once hidden
      IconToggleButton(
        checked = isShown,
        onCheckedChange = { show -> onSetHidden(window.id, !show) },
      ) {
        Crossfade(targetState = isShown, label = "built-in window visibility") { shown ->
          Icon(
            if (shown) {
              Icons.Visibility
            } else {
              Icons.VisibilityOff
            },
            contentDescription = "Show ${window.name}",
          )
        }
      }
    }
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
  newWindowButton()
  AnimatedVisibility(visible = !canAddWindow, enter = RowEnter, exit = RowExit) {
    Text(
      "You can save up to ${TimeWindow.MAX_CUSTOM_WINDOWS} windows.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

/**
 * The New window button. Tapping it grows the button up into [editor] for [draft], and closing the
 * editor shrinks it back down into the button. The sheet sits on the bottom of the screen, so the
 * button's container grows upward and uncovers the editor from its top edge.
 */
@Composable
private fun NewWindowButton(
  draft: WindowDraft?,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  editor: @Composable (WindowDraft) -> Unit,
) {
  val buttonColors = ButtonDefaults.buttonColors()
  // The sheet is surfaceContainerLow, so the highest container is the one that reads as a card on
  // it
  val editorColor = MaterialTheme.colorScheme.surfaceContainerHighest
  val opening = updateTransition(draft, label = "new window")
  val containerColor by
    opening.animateColor(transitionSpec = { tween(ExpandMillis, easing = EmphasizedEasing) }) {
      state ->
      when {
        state != null -> editorColor
        enabled -> buttonColors.containerColor
        else -> buttonColors.disabledContainerColor
      }
    }
  val cornerRadius by
    opening.animateDp(transitionSpec = { tween(ExpandMillis, easing = EmphasizedEasing) }) { state
      ->
      if (state != null) {
        16.dp
      } else {
        ButtonDefaults.MinHeight / 2
      }
    }
  // The container wraps the content, so it grows and shrinks with the content's size animation
  Box(modifier.fillMaxWidth().clip(RoundedCornerShape(cornerRadius)).background(containerColor)) {
    NewWindowContent(opening, enabled, buttonColors, onClick, editor)
  }
}

/** The button's label or the editor, faded through while the container resizes around them. */
@Composable
private fun NewWindowContent(
  opening: Transition<WindowDraft?>,
  enabled: Boolean,
  buttonColors: ButtonColors,
  onClick: () -> Unit,
  editor: @Composable (WindowDraft) -> Unit,
) {
  opening.AnimatedContent(
    modifier = Modifier.fillMaxWidth(),
    transitionSpec = {
      // The label leaves before the editor shows, so the two never blend
      fadeIn(tween(100, delayMillis = 50)) togetherWith
        fadeOut(tween(50)) using
        SizeTransform { _, _ -> tween(ExpandMillis, easing = EmphasizedEasing) }
    },
    contentAlignment = Alignment.BottomCenter,
    contentKey = { it != null },
  ) { state ->
    if (state == null) {
      Box(
        Modifier.fillMaxWidth()
          .heightIn(min = ButtonDefaults.MinHeight)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .padding(ButtonDefaults.ContentPadding),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "New window",
          style = MaterialTheme.typography.labelLarge,
          color =
            if (enabled) {
              buttonColors.contentColor
            } else {
              buttonColors.disabledContentColor
            },
        )
      }
    } else {
      // Scrolls the whole editor into view once it has grown, for sheets too short to show it all
      val requester = remember { BringIntoViewRequester() }
      val hasGrown = transition.currentState == EnterExitState.Visible
      LaunchedEffect(hasGrown) {
        if (hasGrown) {
          requester.bringIntoView()
        }
      }
      Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).padding(16.dp)) {
        editor(state)
      }
    }
  }
}

/**
 * The element that [closeOnOutsideTap] protects, inside the element that listens for taps. Set
 * [inside] from the protected element's `onGloballyPositioned`. It's a plain holder, so layout
 * changes don't recompose.
 */
internal class OutsideTapArea {
  var inside: LayoutCoordinates? = null

  /** The listening element, which [closeOnOutsideTap] sets. Tap positions are relative to it. */
  var container: LayoutCoordinates? = null
}

/**
 * Calls [onClose] when [enabled] and a tap lands in this element outside [area], like a tap beside
 * an open editor. The tap stops here, so it can't also press a button under it. A drag past the
 * touch slop is left alone, so scrolling still works.
 */
@Composable
internal fun Modifier.closeOnOutsideTap(
  area: OutsideTapArea,
  enabled: Boolean,
  onClose: () -> Unit,
): Modifier {
  val currentOnClose by rememberUpdatedState(onClose)
  if (!enabled) {
    return this
  }
  return onGloballyPositioned { area.container = it }
    .pointerInput(area) {
      awaitEachGesture {
        // The initial pass sees the tap before the children under it do
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val outer = area.container ?: return@awaitEachGesture
        val inner = area.inside?.takeIf { it.isAttached }
        if (inner != null && outer.localBoundingBoxOf(inner).contains(down.position)) {
          return@awaitEachGesture
        }
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
          if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            return@awaitEachGesture
          }
          if (!change.pressed) {
            change.consume()
            currentOnClose()
            return@awaitEachGesture
          }
        }
      }
    }
}

/** How long the New window button takes to grow into the editor or shrink back. */
private const val ExpandMillis = 200

/** The list and the editor slide between each other quickly, and the sheet resizes with them. */
private const val PageTransitionMillis = 250
private val PageSlideSpec = tween<IntOffset>(PageTransitionMillis, easing = EmphasizedEasing)
private val PageSizeSpec = tween<IntSize>(PageTransitionMillis, easing = EmphasizedEasing)

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

/** A window's name and hours, followed by [actions]. A dimmed row is a hidden built-in. */
@Composable
private fun WindowRow(
  window: TimeWindow,
  modifier: Modifier = Modifier,
  isDimmed: Boolean = false,
  actions: @Composable () -> Unit = {},
) {
  val textAlpha by
    animateFloatAsState(
      targetValue =
        if (isDimmed) {
          0.5f
        } else {
          1f
        },
      label = "window row text alpha",
    )
  Row(
    modifier = modifier.fillMaxWidth().height(48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = spacedBy(8.dp),
  ) {
    Text(
      window.name,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).graphicsLayer { alpha = textAlpha },
    )
    Text(
      formatHourRange(window.startHour, window.endHour),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.graphicsLayer { alpha = textAlpha },
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
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
