// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import com.slack.circuit.foundation.NavDecoration
import com.slack.circuit.foundation.ProvideRecordLifecycle
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.navigation.NavArgument
import com.slack.circuit.runtime.navigation.NavStackList
import com.slack.circuit.runtime.navigation.navStackListOf
import com.slack.circuit.runtime.navigation.transform
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.sharedelements.ProvideAnimatedTransitionScope
import com.slack.circuit.sharedelements.SharedElementTransitionScope.AnimatedScope.Navigation

/**
 * Shows the root screen beside the screens above it when [paneLayout] splits the window, and passes
 * the whole stack to [delegate] otherwise.
 *
 * Circuit keeps one back stack in both layouts. A fold or unfold only changes how the stack
 * renders, so records never move between stacks and keep their retained and saved state.
 *
 * In the split layout:
 * - The left pane shows the root record.
 * - The right pane passes the records above the root to [delegate], with a placeholder below them.
 *   Back gestures and their animations act on the right pane only, and swiping back from the first
 *   detail reveals the placeholder, like the secondary column of an iPad split view.
 *
 * The right pane always renders through the same [delegate] call, so a layout switch never remounts
 * the open detail. Circuit wraps each record's content in movable content, so the root keeps its
 * state when it moves between the left pane and [delegate]. Each record renders in one place only,
 * because two calls would reuse the same saved state keys.
 */
@Stable
class PaneNavDecoration(private val host: PaneHost, private val delegate: NavDecoration) :
  NavDecoration {

  @Composable
  override fun <T : NavArgument> DecoratedContent(
    args: NavStackList<T>,
    navigator: Navigator,
    modifier: Modifier,
    content: @Composable (T) -> Unit,
  ) {
    BoxWithConstraints(modifier) {
      val root = args.root
      // Top first, root last. Forward items stay hidden, as Circuit shows the active record.
      val visible = remember(args) { listOf(args.active) + args.backwardItems }
      val firstAboveRoot = visible.getOrNull(visible.lastIndex - 1)
      val layout =
        paneLayout(
          windowSize = DpSize(maxWidth, maxHeight),
          firstAboveRoot = firstAboveRoot?.screen,
          wantsEmptyDetail = host.emptyDetailMessage != null,
        )
      SideEffect { host.layout = layout }
      val paneArgs =
        remember(args, layout.isSplit) {
          if (layout.isSplit) {
            rightPaneStack(args, visible)
          } else {
            args.transform<T, PaneArg<T>> { PaneArg.Record(it) }
          }
        }

      val layoutDirection = LocalLayoutDirection.current
      val safeDrawing = WindowInsets.safeDrawing
      val startInset =
        safeDrawing
          .only(WindowInsetsSides.Start)
          .asPaddingValues()
          .calculateStartPadding(layoutDirection)
      val endInset =
        safeDrawing
          .only(WindowInsetsSides.End)
          .asPaddingValues()
          .calculateEndPadding(layoutDirection)

      CompositionLocalProvider(LocalPaneLayout provides layout) {
        Row(Modifier.fillMaxSize()) {
          val style = layout.detailStyle
          if (style != null) {
            val primaryWidth =
              when (style) {
                PaneStyle.LIST_DETAIL ->
                  Modifier.width(startInset + NavigationRailWidth + layout.listPaneWidth)
                PaneStyle.SUPPORTING -> Modifier.weight(1f)
              }
            Box(
              primaryWidth
                .fillMaxHeight()
                .consumeWindowInsets(safeDrawing.only(WindowInsetsSides.End))
            ) {
              // Circuit marks only the top record active and pauses the rest. The root stays
              // visible here, so it stays active.
              ProvideRecordLifecycle(isActive = true) {
                StaticNavigationScope {
                  CompositionLocalProvider(LocalPaneRole provides PaneRole.PRIMARY) {
                    content(root)
                  }
                }
              }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
          val detailWidth =
            if (style == PaneStyle.SUPPORTING) {
              Modifier.width(layout.supportingPaneWidth + endInset)
            } else {
              Modifier.weight(1f)
            }
          val detailInsets =
            if (style != null) {
              Modifier.consumeWindowInsets(safeDrawing.only(WindowInsetsSides.Start))
            } else {
              Modifier
            }
          Box(detailWidth.fillMaxHeight().then(detailInsets)) {
            delegate.DecoratedContent(paneArgs, navigator, Modifier.fillMaxSize()) { arg ->
              when (arg) {
                is PaneArg.Record -> {
                  val record = arg.record
                  when {
                    !layout.isSplit -> content(record)
                    // The left pane shows the root. The delegate can still ask for it while it
                    // animates out after a layout switch.
                    record.key == root.key -> Unit
                    else -> {
                      val role =
                        if (record.key == firstAboveRoot?.key) {
                          PaneRole.DETAIL_ROOT
                        } else {
                          PaneRole.DETAIL_STACKED
                        }
                      CompositionLocalProvider(LocalPaneRole provides role) { content(record) }
                    }
                  }
                }
                PaneArg.Placeholder -> DetailPlaceholder(host.emptyDetailMessage)
              }
            }
          }
        }
      }
    }
  }
}

/** An entry in the delegate's stack: a back stack record, or the placeholder below the details. */
internal sealed interface PaneArg<out T : NavArgument> : NavArgument {
  data class Record<T : NavArgument>(val record: T) : PaneArg<T> {
    override val key: String
      get() = record.key

    override val screen: Screen
      get() = record.screen
  }

  data object Placeholder : PaneArg<Nothing> {
    override val key: String = "fieldspottr-pane-placeholder"
    override val screen: Screen = PanePlaceholderScreen
  }
}

/** The placeholder's screen. It never enters the back stack. */
internal data object PanePlaceholderScreen : Screen

/** The records above the root from [visible] (top first), over a placeholder. */
private fun <T : NavArgument> rightPaneStack(
  args: NavStackList<T>,
  visible: List<T>,
): NavStackList<PaneArg<T>> {
  val aboveRoot = visible.dropLast(1)
  val placeholder: PaneArg<T> = PaneArg.Placeholder
  val forward = args.forwardItems.map<T, PaneArg<T>> { PaneArg.Record(it) }
  if (aboveRoot.isEmpty()) {
    return navStackListOf(forwardItems = forward, activeItem = placeholder)
  }
  return navStackListOf(
    forwardItems = forward,
    activeItem = PaneArg.Record(aboveRoot.first()),
    backwardItems = aboveRoot.drop(1).map<T, PaneArg<T>> { PaneArg.Record(it) } + placeholder,
  )
}

/**
 * Gives [content] a Navigation animation scope that never animates. The root's shared elements need
 * one while it sits in the left pane, outside the delegate's transitions.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StaticNavigationScope(content: @Composable () -> Unit) {
  AnimatedVisibility(
    visible = true,
    modifier = Modifier.fillMaxSize(),
    enter = EnterTransition.None,
    exit = ExitTransition.None,
  ) {
    ProvideAnimatedTransitionScope(Navigation, this) { content() }
  }
}
