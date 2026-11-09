// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen

/** How [PaneAwareNavigator] carries out a `goTo`: pop [pops] screens, then push if [push]. */
internal data class PaneGoTo(val pops: Int, val push: Boolean)

/**
 * Decides how to open [target] when [stack] (top first, root last) is showing.
 *
 * In the split layout the list stays visible beside the right pane. Pushing every pick would stack
 * them all, and back would step through each one. So opening a screen of a type already in the
 * right pane replaces that screen and everything above it, and back returns to the list as in
 * Material's list-detail layouts. Picking the screen that is already open shows it again without a
 * push.
 */
internal fun paneGoTo(stack: List<Screen>, target: Screen, isSplit: Boolean): PaneGoTo {
  if (!isSplit) {
    return PaneGoTo(pops = 0, push = true)
  }
  val aboveRoot = stack.dropLast(1)
  val match = aboveRoot.indexOfLast { it::class == target::class }
  return when {
    match == -1 -> PaneGoTo(pops = 0, push = true)
    aboveRoot[match] == target -> PaneGoTo(pops = match, push = false)
    else -> PaneGoTo(pops = match + 1, push = true)
  }
}

/**
 * Wraps the root [delegate] so that `goTo` follows [paneGoTo] while [isSplit] returns true. All
 * other calls go straight to [delegate].
 */
@Stable
class PaneAwareNavigator(private val delegate: Navigator, private val isSplit: () -> Boolean) :
  Navigator by delegate {
  override fun goTo(screen: Screen): Boolean {
    val plan = paneGoTo(delegate.peekBackStack(), screen, isSplit())
    // One snapshot, so the decoration never renders the stack between the pops and the push
    return Snapshot.withMutableSnapshot {
      repeat(plan.pops) { delegate.pop() }
      if (plan.push) {
        delegate.goTo(screen)
      } else {
        plan.pops > 0
      }
    }
  }
}
