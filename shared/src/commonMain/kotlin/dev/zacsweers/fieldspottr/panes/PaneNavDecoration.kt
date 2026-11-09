// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
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
 *
 * Every layout decision is made in composition, never read during measure. Shared element
 * transitions add a lookahead pass, and both passes must agree on the pane widths.
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
      val density = LocalDensity.current
      // Hinge bounds are in window coordinates. This decoration fills the window.
      val hinge =
        currentWindowAdaptiveInfo().windowPosture.separatingVerticalHingeBounds.firstOrNull()?.let {
          with(density) { PaneHinge(it.left.toDp(), it.right.toDp()) }
        }
      val panes =
        paneLayout(
          windowSize = DpSize(maxWidth, maxHeight),
          firstAboveRoot = firstAboveRoot?.screen,
          separatingHinge = hinge,
        )
      SideEffect { host.layout = panes }
      val paneArgs =
        remember(args, panes.isSplit) {
          if (panes.isSplit) {
            rightPaneStack(args, visible)
          } else {
            args.transform<T, PaneArg<T>> { PaneArg.Record(it, PaneRole.SINGLE) }
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

      val showsDetail = panes.detailRoot != null || host.emptyDetailMessage != null

      CompositionLocalProvider(LocalPaneLayout provides panes) {
        Layout(
          modifier = Modifier.fillMaxSize(),
          content = {
            if (panes.isSplit) {
              Box(
                Modifier.layoutId(PrimaryPaneId)
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
              VerticalDivider(
                Modifier.layoutId(DividerId),
                color = MaterialTheme.colorScheme.outlineVariant,
              )
            }
            val detailInsets =
              if (panes.isSplit) {
                Modifier.consumeWindowInsets(safeDrawing.only(WindowInsetsSides.Start))
              } else {
                Modifier
              }
            Box(Modifier.layoutId(DetailPaneId).then(detailInsets)) {
              delegate.DecoratedContent(paneArgs, navigator, Modifier.fillMaxSize()) { arg ->
                when (arg) {
                  is PaneArg.Record -> {
                    // The left pane shows the root. The delegate can still ask for it while it
                    // animates out after a layout switch.
                    if (!(panes.isSplit && arg.record.key == root.key)) {
                      // Each entry keeps the role it had in its own stack, so a detail animating
                      // out keeps its chrome while a replacement animates in
                      CompositionLocalProvider(LocalPaneRole provides arg.role) {
                        content(arg.record)
                      }
                    }
                  }
                  PaneArg.Placeholder -> DetailPlaceholder(host.emptyDetailMessage)
                }
              }
            }
          },
        ) { measurables, constraints ->
          val width = constraints.maxWidth
          val height = constraints.maxHeight
          val detail = measurables.first { it.layoutId == DetailPaneId }
          val primary = measurables.firstOrNull { it.layoutId == PrimaryPaneId }
          val divider = measurables.firstOrNull { it.layoutId == DividerId }
          if (primary == null || divider == null) {
            val only = detail.measure(Constraints.fixed(width, height))
            return@Layout layout(width, height) { only.placeRelative(0, 0) }
          }
          // Every child is measured and placed in every branch, at zero width when hidden. Shared
          // element transitions run a lookahead pass, and a child placed without a lookahead
          // measurement throws.
          val dividerWidth =
            if (showsDetail) {
              divider.maxIntrinsicWidth(height)
            } else {
              0
            }
          val hingeSplit = panes.hinge
          val primaryWidth: Int
          val detailStart: Int
          when {
            !showsDetail -> {
              primaryWidth = width
              detailStart = width
            }
            hingeSplit != null -> {
              primaryWidth = hingeSplit.start.roundToPx()
              detailStart = maxOf(hingeSplit.end.roundToPx(), primaryWidth + dividerWidth)
            }
            panes.detailStyle == PaneStyle.SUPPORTING -> {
              val detailWidth = (panes.supportingPaneWidth + endInset).roundToPx()
              primaryWidth = width - detailWidth - dividerWidth
              detailStart = primaryWidth + dividerWidth
            }
            else -> {
              primaryWidth = (startInset + NavigationRailWidth + panes.listPaneWidth).roundToPx()
              detailStart = primaryWidth + dividerWidth
            }
          }
          val primaryPlaceable =
            primary.measure(Constraints.fixed(primaryWidth.coerceIn(0, width), height))
          val dividerPlaceable = divider.measure(Constraints.fixed(dividerWidth, height))
          val detailPlaceable =
            detail.measure(Constraints.fixed((width - detailStart).coerceAtLeast(0), height))
          layout(width, height) {
            primaryPlaceable.placeRelative(0, 0)
            dividerPlaceable.placeRelative(primaryWidth, 0)
            detailPlaceable.placeRelative(detailStart, 0)
          }
        }
      }
    }
  }
}

private const val PrimaryPaneId = "primary"
private const val DividerId = "divider"
private const val DetailPaneId = "detail"

/** An entry in the delegate's stack: a back stack record, or the placeholder below the details. */
internal sealed interface PaneArg<out T : NavArgument> : NavArgument {
  /** A back stack [record], with the [role] it has in the stack it belongs to. */
  data class Record<T : NavArgument>(val record: T, val role: PaneRole) : PaneArg<T> {
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
  val firstAboveRootKey = aboveRoot.lastOrNull()?.key
  val placeholder: PaneArg<T> = PaneArg.Placeholder
  val toArg: (T) -> PaneArg<T> = { record ->
    val role =
      if (record.key == firstAboveRootKey) {
        PaneRole.DETAIL_ROOT
      } else {
        PaneRole.DETAIL_STACKED
      }
    PaneArg.Record(record, role)
  }
  val forward = args.forwardItems.map(toArg)
  if (aboveRoot.isEmpty()) {
    return navStackListOf(forwardItems = forward, activeItem = placeholder)
  }
  return navStackListOf(
    forwardItems = forward,
    activeItem = toArg(aboveRoot.first()),
    backwardItems = aboveRoot.drop(1).map(toArg) + placeholder,
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
