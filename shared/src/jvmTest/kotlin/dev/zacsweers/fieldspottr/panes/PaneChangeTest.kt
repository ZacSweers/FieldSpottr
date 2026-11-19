// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.slack.circuit.runtime.navigation.NavArgument
import com.slack.circuit.runtime.navigation.NavStackList
import com.slack.circuit.runtime.navigation.navStackListOf
import com.slack.circuit.runtime.screen.Screen
import kotlin.test.Test

/** Which right pane changes [PaneAnimatedNavDecoratorFactory] gives its own transitions. */
class PaneChangeTest {
  private data class TestScreen(val id: Int) : Screen

  private data class TestRecord(val id: Int) : NavArgument {
    override val key: String
      get() = "record$id"

    override val screen: Screen
      get() = TestScreen(id)
  }

  private fun record(id: Int, role: PaneRole): PaneArg<TestRecord> =
    PaneArg.Record(TestRecord(id), role)

  private fun stack(
    active: PaneArg<TestRecord>,
    vararg backward: PaneArg<TestRecord>,
  ): NavStackList<PaneArg<TestRecord>> =
    navStackListOf(
      forwardItems = emptyList(),
      activeItem = active,
      backwardItems = backward.toList(),
    )

  @Test
  fun firstDetailOverAHiddenPlaceholderOpensThePane() {
    val hidden = PaneArg.Placeholder(shown = false)
    val change = paneChange(stack(hidden), stack(record(1, PaneRole.DETAIL_ROOT), hidden))
    assertThat(change).isEqualTo(PaneChange.OPEN)
  }

  @Test
  fun firstDetailOverAShownPlaceholderReplacesIt() {
    val shown = PaneArg.Placeholder(shown = true)
    val change = paneChange(stack(shown), stack(record(1, PaneRole.DETAIL_ROOT), shown))
    assertThat(change).isEqualTo(PaneChange.REPLACE)
  }

  @Test
  fun anotherDetailReplacesTheOpenOne() {
    val placeholder = PaneArg.Placeholder(shown = true)
    val change =
      paneChange(
        stack(record(1, PaneRole.DETAIL_ROOT), placeholder),
        stack(record(2, PaneRole.DETAIL_ROOT), placeholder),
      )
    assertThat(change).isEqualTo(PaneChange.REPLACE)
  }

  @Test
  fun aScreenPushedOverTheDetailKeepsTheDelegateTransition() {
    val placeholder = PaneArg.Placeholder(shown = true)
    val detail = record(1, PaneRole.DETAIL_ROOT)
    val change =
      paneChange(stack(detail, placeholder), stack(record(2, PaneRole.DETAIL_STACKED), detail))
    assertThat(change).isNull()
  }

  @Test
  fun aPopBackToThePlaceholderKeepsTheDelegateTransition() {
    val placeholder = PaneArg.Placeholder(shown = true)
    val change = paneChange(stack(record(1, PaneRole.DETAIL_ROOT), placeholder), stack(placeholder))
    assertThat(change).isNull()
  }

  @Test
  fun aSwitchFromTheSingleLayoutKeepsTheDelegateTransition() {
    val change =
      paneChange(
        stack(record(1, PaneRole.SINGLE)),
        stack(record(1, PaneRole.DETAIL_ROOT), PaneArg.Placeholder(shown = true)),
      )
    assertThat(change).isNull()
  }
}
