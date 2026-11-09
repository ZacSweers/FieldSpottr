// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.navigation.NavStackList
import com.slack.circuit.runtime.navigation.navStackListOf
import com.slack.circuit.runtime.screen.PopResult
import com.slack.circuit.runtime.screen.Screen
import kotlin.test.Test

class PaneAwareNavigatorTest {
  private data object Home : Screen

  private data class Area(val id: Int) : Screen

  private data class Permit(val id: Int) : Screen

  @Test
  fun singleLayoutAlwaysPushes() {
    assertThat(paneGoTo(listOf(Area(1), Home), Area(2), isSplit = false))
      .isEqualTo(PaneGoTo(pops = 0, push = true))
  }

  @Test
  fun newTypePushesOverTheDetail() {
    assertThat(paneGoTo(listOf(Area(1), Home), Permit(1), isSplit = true))
      .isEqualTo(PaneGoTo(pops = 0, push = true))
  }

  @Test
  fun sameTypeReplacesTheDetail() {
    assertThat(paneGoTo(listOf(Area(1), Home), Area(2), isSplit = true))
      .isEqualTo(PaneGoTo(pops = 1, push = true))
  }

  @Test
  fun sameTypeReplacesEverythingAboveIt() {
    assertThat(paneGoTo(listOf(Permit(1), Area(1), Home), Area(2), isSplit = true))
      .isEqualTo(PaneGoTo(pops = 2, push = true))
  }

  @Test
  fun openScreenShowsAgainWithoutAPush() {
    assertThat(paneGoTo(listOf(Area(1), Home), Area(1), isSplit = true))
      .isEqualTo(PaneGoTo(pops = 0, push = false))
    assertThat(paneGoTo(listOf(Permit(1), Area(1), Home), Area(1), isSplit = true))
      .isEqualTo(PaneGoTo(pops = 1, push = false))
  }

  @Test
  fun rootIsNeverReplaced() {
    assertThat(paneGoTo(listOf(Home), Home, isSplit = true))
      .isEqualTo(PaneGoTo(pops = 0, push = true))
  }

  @Test
  fun navigatorReplacesInSplitLayout() {
    val fake = FakeNavigator(Home)
    var split = false
    val navigator = PaneAwareNavigator(fake) { split }

    navigator.goTo(Area(1))
    split = true
    navigator.goTo(Permit(1))
    navigator.goTo(Area(2))

    assertThat(fake.stack).containsExactly(Area(2), Home)
  }

  @Test
  fun navigatorPushesInSingleLayout() {
    val fake = FakeNavigator(Home)
    val navigator = PaneAwareNavigator(fake) { false }

    navigator.goTo(Area(1))
    navigator.goTo(Permit(1))

    assertThat(fake.stack).containsExactly(Permit(1), Area(1), Home)
  }

  /** A list-backed navigator. [stack] is top first. */
  private class FakeNavigator(root: Screen) : Navigator {
    val stack = mutableListOf(root)

    override fun goTo(screen: Screen): Boolean {
      stack.add(0, screen)
      return true
    }

    override fun forward(): Boolean = false

    override fun backward(): Boolean = false

    override fun pop(result: PopResult?): Screen? {
      return if (stack.size > 1) {
        stack.removeAt(0)
      } else {
        null
      }
    }

    override fun peek(): Screen? = stack.firstOrNull()

    override fun peekBackStack(): List<Screen> = stack.toList()

    override fun peekNavStack(): NavStackList<Screen>? = navStackListOf(stack)

    override fun resetRoot(newRoot: Screen, options: Navigator.StateOptions): List<Screen> {
      val old = stack.toList()
      stack.clear()
      stack.add(newRoot)
      return old
    }
  }
}
