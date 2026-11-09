// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.slack.circuit.backstack.rememberSaveableBackStack
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.LocalRecordLifecycle
import com.slack.circuit.foundation.NavigableCircuitContent
import com.slack.circuit.foundation.animation.AnimatedNavDecoration
import com.slack.circuit.foundation.rememberCircuitNavigator
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.ExperimentalCircuitApi
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.presenterOf
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.ui.ui
import com.slack.circuitx.gesturenavigation.GestureNavigationDecorationFactory
import kotlin.test.Test

/** Renders real Circuit navigation through [PaneNavDecoration] at phone and wide window sizes. */
@OptIn(ExperimentalTestApi::class, ExperimentalCircuitApi::class)
class PaneNavDecorationTest {
  private data object Root : Screen

  /** A root that reserves the right pane, like Find a Field. */
  private data object ReservingRoot : Screen

  private data class Detail(val id: Int) : PaneScreen {
    override val paneStyle: PaneStyle
      get() = PaneStyle.LIST_DETAIL
  }

  private data class Supporting(val id: Int) : PaneScreen {
    override val paneStyle: PaneStyle
      get() = PaneStyle.SUPPORTING
  }

  /** A screen opened from a detail, so it stacks in the right pane. */
  private data class Stacked(val id: Int) : Screen

  private data class TestState(val screen: Screen) : CircuitUiState

  private val phone = DpSize(411.dp, 900.dp)
  /** An iPad mini in portrait, which keeps the phone layout. */
  private val portraitTablet = DpSize(744.dp, 1133.dp)
  // The test window is 1024px wide at density 1, so wider sizes would clamp
  private val tablet = DpSize(1000.dp, 700.dp)

  private val circuit =
    Circuit.Builder()
      .addPresenterFactory { screen, _, _ -> presenterOf { TestState(screen) } }
      .addUiFactory { _, _ -> ui<TestState> { state, modifier -> TestUi(state.screen, modifier) } }
      .build()

  private var windowSize by mutableStateOf(phone)
  private lateinit var navigator: Navigator

  private fun ComposeUiTest.setNavContent(root: Screen) {
    setContent {
      CircuitCompositionLocals(circuit) {
        Box(Modifier.size(windowSize)) {
          val backStack = rememberSaveableBackStack(root)
          val circuitNavigator = rememberCircuitNavigator(backStack) {}
          val host = remember { PaneHost() }
          navigator =
            remember(circuitNavigator) {
              PaneAwareNavigator(circuitNavigator) { host.layout.isSplit }
            }
          val decoration = remember {
            PaneNavDecoration(
              host,
              AnimatedNavDecoration(emptyMap(), GestureNavigationDecorationFactory()),
            )
          }
          CompositionLocalProvider(LocalPaneHost provides host) {
            NavigableCircuitContent(navigator, backStack, decoration = decoration)
          }
        }
      }
    }
  }

  private fun ComposeUiTest.assertLabel(tag: String, role: PaneRole, taps: Int) {
    onNodeWithTag(tag).assertTextEquals("$tag role=$role active=true taps=$taps")
  }

  @Test
  fun phoneShowsOnlyTheTopScreen() = runComposeUiTest {
    windowSize = phone
    setNavContent(Root)
    runOnIdle { navigator.goTo(Detail(1)) }

    assertLabel("detail1", PaneRole.SINGLE, taps = 0)
    onAllNodesWithTag("root").assertCountEquals(0)
  }

  @Test
  fun wideWindowShowsRootBesideDetail() = runComposeUiTest {
    windowSize = tablet
    setNavContent(Root)
    runOnIdle { navigator.goTo(Detail(1)) }

    assertLabel("root", PaneRole.PRIMARY, taps = 0)
    assertLabel("detail1", PaneRole.DETAIL_ROOT, taps = 0)

    runOnIdle { navigator.goTo(Stacked(1)) }
    assertLabel("stacked1", PaneRole.DETAIL_STACKED, taps = 0)
    onAllNodesWithTag("root").assertCountEquals(1)
  }

  @Test
  fun mediumWindowKeepsThePhoneLayoutUntilRotated() = runComposeUiTest {
    windowSize = portraitTablet
    setNavContent(ReservingRoot)
    assertLabel("root", PaneRole.SINGLE, taps = 0)
    onAllNodesWithText("Pick one").assertCountEquals(0)

    runOnIdle { navigator.goTo(Supporting(1)) }
    assertLabel("supporting1", PaneRole.SINGLE, taps = 0)
    onAllNodesWithTag("root").assertCountEquals(0)

    windowSize = tablet
    assertLabel("root", PaneRole.PRIMARY, taps = 0)
    assertLabel("supporting1", PaneRole.DETAIL_ROOT, taps = 0)
  }

  @Test
  fun reservedPaneShowsPlaceholderUntilAPick() = runComposeUiTest {
    windowSize = tablet
    setNavContent(ReservingRoot)
    onNodeWithText("Pick one").assertExists()

    runOnIdle { navigator.goTo(Detail(1)) }
    assertLabel("detail1", PaneRole.DETAIL_ROOT, taps = 0)
    onAllNodesWithText("Pick one").assertCountEquals(0)

    runOnIdle { navigator.pop() }
    onNodeWithText("Pick one").assertExists()
  }

  @Test
  fun rootWithoutReservationFillsWideWindow() = runComposeUiTest {
    windowSize = tablet
    setNavContent(Root)
    assertLabel("root", PaneRole.PRIMARY, taps = 0)
    onNodeWithTag("root-container").assertWidthIsEqualTo(tablet.width)
  }

  @Test
  fun reservationSizesTheListPane() = runComposeUiTest {
    windowSize = tablet
    setNavContent(ReservingRoot)
    // Rail plus the list pane, with no window insets in tests
    onNodeWithTag("root-container").assertWidthIsEqualTo(expectedListPaneWidth())
  }

  @Test
  fun reservationAppliesOnTheFirstFrame() = runComposeUiTest {
    windowSize = tablet
    mainClock.autoAdvance = false
    setNavContent(ReservingRoot)
    mainClock.advanceTimeByFrame()
    onNodeWithTag("root-container").assertWidthIsEqualTo(expectedListPaneWidth())
  }

  @Test
  fun replacedDetailKeepsItsRoleWhileLeaving() = runComposeUiTest {
    windowSize = tablet
    setNavContent(ReservingRoot)
    runOnIdle { navigator.goTo(Detail(1)) }
    assertLabel("detail1", PaneRole.DETAIL_ROOT, taps = 0)

    mainClock.autoAdvance = false
    runOnIdle { navigator.goTo(Detail(2)) }
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeByFrame()
    // Both are composed mid-transition, and the leaving one hasn't become a stacked screen
    onNodeWithTag("detail1").assertTextContains("role=DETAIL_ROOT", substring = true)
    onNodeWithTag("detail2").assertTextContains("role=DETAIL_ROOT", substring = true)
  }

  @Test
  fun foldAndUnfoldKeepState() = runComposeUiTest {
    windowSize = tablet
    setNavContent(ReservingRoot)
    onNodeWithTag("root-tap").performClick()
    onNodeWithTag("root-tap").performClick()
    assertLabel("root", PaneRole.PRIMARY, taps = 2)

    windowSize = phone
    assertLabel("root", PaneRole.SINGLE, taps = 2)

    windowSize = tablet
    assertLabel("root", PaneRole.PRIMARY, taps = 2)

    runOnIdle { navigator.goTo(Detail(1)) }
    onNodeWithTag("detail1-tap").performClick()
    assertLabel("detail1", PaneRole.DETAIL_ROOT, taps = 1)

    windowSize = phone
    assertLabel("detail1", PaneRole.SINGLE, taps = 1)
    onAllNodesWithTag("root").assertCountEquals(0)

    windowSize = tablet
    assertLabel("detail1", PaneRole.DETAIL_ROOT, taps = 1)
    assertLabel("root", PaneRole.PRIMARY, taps = 2)
  }

  @Test
  fun pickingAgainReplacesTheDetail() = runComposeUiTest {
    windowSize = tablet
    setNavContent(ReservingRoot)
    runOnIdle {
      navigator.goTo(Detail(1))
      navigator.goTo(Detail(2))
    }
    assertLabel("detail2", PaneRole.DETAIL_ROOT, taps = 0)
    onAllNodesWithTag("detail1").assertCountEquals(0)

    runOnIdle { navigator.pop() }
    onNodeWithText("Pick one").assertExists()
  }

  private fun expectedListPaneWidth() =
    NavigationRailWidth + paneLayout(tablet, firstAboveRoot = null).listPaneWidth

  @Composable
  private fun TestUi(screen: Screen, modifier: Modifier) {
    val tag =
      when (screen) {
        Root,
        ReservingRoot -> "root"
        is Detail -> "detail${screen.id}"
        is Supporting -> "supporting${screen.id}"
        is Stacked -> "stacked${screen.id}"
        else -> error("Unexpected screen $screen")
      }
    if (screen == ReservingRoot) {
      ReserveEmptyDetailPane("Pick one")
    }
    var taps by rememberSaveable { mutableIntStateOf(0) }
    val role = LocalPaneRole.current
    val active = LocalRecordLifecycle.current.isActive
    Column(modifier.fillMaxSize().testTag("$tag-container")) {
      Text("$tag role=$role active=$active taps=$taps", Modifier.testTag(tag))
      Button(onClick = { taps++ }, modifier = Modifier.testTag("$tag-tap")) { Text("Tap") }
    }
  }
}
