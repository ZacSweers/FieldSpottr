// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingVerticalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * transitions add a lookahead pass, and both passes must agree on the pane widths. The one
 * exception is the right pane's edge, which animates when a pane opens, closes, or changes width.
 * Both passes read its position from the same frame, so they still agree.
 */
@Stable
class PaneNavDecoration(
  private val host: PaneHost,
  private val delegate: NavDecoration,
  /**
   * Moves the right pane's edge when a pane opens, closes, or changes width. The left pane measures
   * again on each frame of it. Pass `snap()` to resize it in one frame.
   */
  private val animationSpec: FiniteAnimationSpec<Float> = DefaultPaneAnimationSpec,
) : NavDecoration {

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
      // This decoration spans the window's height and ends at its end edge, and navigation can sit
      // before its start, so the window's size and coordinates are offset by that navigation
      val windowStart = LocalPaneWindowStart.current
      val hinge =
        currentWindowAdaptiveInfoV2()
          .windowPosture
          .separatingVerticalHingeBounds
          .firstOrNull()
          ?.let {
            with(density) { PaneHinge(it.left.toDp() - windowStart, it.right.toDp() - windowStart) }
          }
      val windowSize =
        LocalPaneWindowSize.current.takeIf { it.isSpecified }
          ?: DpSize(maxWidth + windowStart, maxHeight)
      val panes =
        paneLayout(
          windowSize = windowSize,
          firstAboveRoot = firstAboveRoot?.screen,
          separatingHinge = hinge,
          panesWidth = maxWidth,
        )
      SideEffect { host.layout = panes }
      val placeholder = host.placeholder
      val placeholderShown = placeholder != null
      val paneArgs =
        remember(args, panes.isSplit, placeholderShown) {
          if (panes.isSplit) {
            endPaneStack(args, visible, placeholderShown)
          } else {
            args.transform<T, PaneArg<T>> { PaneArg.Record(it, PaneRole.SINGLE) }
          }
        }

      val layoutDirection = LocalLayoutDirection.current
      val safeDrawing = WindowInsets.safeDrawing
      // Navigation before this decoration already covers that much of the start inset
      val startInset =
        (safeDrawing
            .only(WindowInsetsSides.Start)
            .asPaddingValues()
            .calculateStartPadding(layoutDirection) - windowStart)
          .coerceAtLeast(0.dp)
      val endInset =
        safeDrawing
          .only(WindowInsetsSides.End)
          .asPaddingValues()
          .calculateEndPadding(layoutDirection)

      val showsDetail = panes.detailRoot != null || placeholderShown
      // Where the right pane starts, in pixels, while it moves to its target
      val detailEdge = remember { PaneEdge() }
      val animationScope = rememberCoroutineScope()
      // The hinge of the last measure, to tell a hinge that moved from a pane that opened
      val lastHinge = remember { arrayOfNulls<PaneHinge>(1) }

      val paneState =
        PaneState(
          detailRoot = panes.detailRoot,
          paneCount =
            if (panes.isSplit && showsDetail) {
              2
            } else {
              1
            },
        )
      CompositionLocalProvider(LocalPaneState provides paneState) {
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
            Box(
              Modifier.layoutId(DetailPaneId)
                .then(detailInsets)
                // The right pane stays at its final layout position and slides with a draw offset.
                // Content inside it then sees one stable position.
                .graphicsLayer {
                  val offset =
                    if (panes.isSplit) {
                      detailEdge.offsetFromTarget
                    } else {
                      0f
                    }
                  // Edges are measured from the start edge, which is on the right in RTL
                  translationX =
                    if (layoutDirection == LayoutDirection.Rtl) {
                      -offset
                    } else {
                      offset
                    }
                }
            ) {
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
                  is PaneArg.Placeholder -> placeholder?.invoke()
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
            // If the next layout splits at this width, the left pane shrinks from here and the
            // right pane slides in
            detailEdge.snapTo(width, width)
            lastHinge[0] = panes.hinge
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
              primaryWidth = (startInset + panes.listPaneWidth).roundToPx()
              detailStart = primaryWidth + dividerWidth
            }
          }
          // The right pane's edge moves to detailStart over a few frames, so the left pane resizes
          // while the right pane slides. A hinge that moves jumps there, since the panes must
          // never cross it.
          val hingeMoved = lastHinge[0] != hingeSplit
          lastHinge[0] = hingeSplit
          val currentDetailStart =
            detailEdge.animateTo(
              newTarget = detailStart,
              width = width,
              coroutineScope = animationScope,
              animationSpec = animationSpec,
              snap = hingeMoved,
            )
          // The divider or hinge between the panes keeps its width while the edge moves
          val gapWidth = detailStart - primaryWidth
          val currentPrimaryWidth = (currentDetailStart - gapWidth).coerceIn(0, width)
          // The right pane keeps its full width while it slides, so its content doesn't reflow on
          // the way. After it closes, it keeps its last width, off screen.
          val targetDetailWidth = (width - detailStart).coerceAtLeast(0)
          if (targetDetailWidth > 0) {
            detailEdge.lastPaneWidth = targetDetailWidth
          }
          val primaryPlaceable = primary.measure(Constraints.fixed(currentPrimaryWidth, height))
          val dividerPlaceable = divider.measure(Constraints.fixed(dividerWidth, height))
          val detailPlaceable = detail.measure(Constraints.fixed(detailEdge.lastPaneWidth, height))
          layout(width, height) {
            primaryPlaceable.placeRelative(0, 0)
            dividerPlaceable.placeRelative(currentPrimaryWidth, 0)
            // At its target. The draw offset from detailEdge makes it slide.
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

  /**
   * The entry below the records of the right pane.
   *
   * @param shown The app draws a placeholder, so the right pane is visible while this entry is on
   *   top. Without one, the pane opens and closes with the first record. The key stays the same
   *   either way, so turning the placeholder on or off plays no transition.
   */
  data class Placeholder(val shown: Boolean) : PaneArg<Nothing> {
    override val key: String
      get() = "fieldspottr-pane-placeholder"

    override val screen: Screen
      get() = PanePlaceholderScreen
  }
}

/** The placeholder's screen. It never enters the back stack. */
internal data object PanePlaceholderScreen : Screen

/**
 * The records above the root from [visible] (top first), over a placeholder.
 *
 * The placeholder is the root of this stack. The delegate then treats the first record in the right
 * pane as a push over it, so that record gets an enter transition, and a pop back to the root alone
 * plays as a pop to the placeholder.
 */
private fun <T : NavArgument> endPaneStack(
  args: NavStackList<T>,
  visible: List<T>,
  placeholderShown: Boolean,
): NavStackList<PaneArg<T>> {
  val aboveRoot = visible.dropLast(1)
  val firstAboveRootKey = aboveRoot.lastOrNull()?.key
  val placeholder: PaneArg<T> = PaneArg.Placeholder(placeholderShown)
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

/**
 * The position of a pane edge, in pixels from the start of the decoration. It moves to each new
 * target with an animation, and jumps when the decoration first lays out or changes width, like on
 * a rotation or a fold.
 *
 * Measure reads [animateTo], so the layout measures again on each frame of the animation. The
 * lookahead pass of shared element transitions reads the same position in the same frame, so both
 * passes agree on the pane widths.
 */
@Stable
private class PaneEdge {
  private var position by mutableFloatStateOf(0f)
  private var animation: Job? = null
  private var initialized = false
  private var target = 0
  private var layoutWidth = -1

  /** The last width of the pane that starts at this edge, kept for after the pane closes. */
  var lastPaneWidth = 0

  /**
   * How far the edge is from its target, in pixels. The pane draws with this offset, so it slides
   * while its layout position stays at the target.
   */
  val offsetFromTarget: Float
    get() = position - target

  /** Moves the edge to [newPosition] with no animation. */
  fun snapTo(newPosition: Int, width: Int) {
    if (!initialized || target != newPosition || layoutWidth != width) {
      animation?.cancel()
      position = newPosition.toFloat()
      target = newPosition
      layoutWidth = width
      initialized = true
    }
  }

  /**
   * Returns where the edge is in this frame on its way to [newTarget].
   *
   * @param snap Jumps to [newTarget], for a move that corrects the layout.
   */
  fun animateTo(
    newTarget: Int,
    width: Int,
    coroutineScope: CoroutineScope,
    animationSpec: FiniteAnimationSpec<Float>,
    snap: Boolean,
  ): Int {
    // First layout, or the decoration changed width: nothing sensible to animate from
    if (!initialized || layoutWidth != width || snap || animationSpec is SnapSpec) {
      snapTo(newTarget, width)
      return newTarget
    }
    // A new target at the same width, like a pane that opened. Start from where the edge is now,
    // which also covers a target that changes mid-animation.
    if (target != newTarget) {
      val from = position
      target = newTarget
      animation?.cancel()
      animation = coroutineScope.launch {
        animate(from, newTarget.toFloat(), animationSpec = animationSpec) { value, _ ->
          position = value
        }
      }
    }
    return position.roundToInt()
  }
}

/** The default pane edge animation. Its threshold is one pixel, the unit of an edge position. */
internal val DefaultPaneAnimationSpec: FiniteAnimationSpec<Float> =
  spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)
