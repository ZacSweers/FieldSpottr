// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.panes

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.slack.circuit.runtime.screen.Screen
import kotlin.test.Test

class PaneLayoutTest {
  private data object Root : Screen

  private data object Plain : Screen

  private data class Detail(val id: Int) : PaneScreen {
    override val paneStyle: PaneStyle
      get() = PaneStyle.LIST_DETAIL
  }

  private data class Supporting(val id: Int) : PaneScreen {
    override val paneStyle: PaneStyle
      get() = PaneStyle.SUPPORTING
  }

  private val phonePortrait = DpSize(411.dp, 923.dp)
  private val phoneLandscape = DpSize(923.dp, 411.dp)
  private val duoFoldedPortrait = DpSize(466.dp, 678.dp)
  private val duoFoldedLandscape = DpSize(678.dp, 466.dp)
  private val duoOpenPortrait = DpSize(669.dp, 951.dp)
  private val duoOpenLandscape = DpSize(951.dp, 669.dp)
  private val iPadMiniPortrait = DpSize(744.dp, 1133.dp)
  private val iPadPortrait = DpSize(820.dp, 1180.dp)
  private val pixelFoldOpen = DpSize(851.dp, 883.dp)
  private val desktop = DpSize(1200.dp, 800.dp)

  @Test
  fun phonesAndNarrowWindowsKeepTheSingleLayout() {
    val sizes =
      listOf(
        phonePortrait,
        phoneLandscape,
        duoFoldedPortrait,
        duoFoldedLandscape,
        duoOpenPortrait,
      )
    for (size in sizes) {
      val layout = paneLayout(size, Detail(1))
      assertThat(layout).isEqualTo(PaneLayout.Single)
    }
  }

  @Test
  fun splitStartsAt700dpWithMediumHeight() {
    assertThat(paneLayout(DpSize(699.dp, 900.dp), Detail(1))).isEqualTo(PaneLayout.Single)
    assertThat(paneLayout(DpSize(700.dp, 900.dp), Detail(1)).isSplit).isTrue()
    // Wide enough, but too short, as phones in landscape are
    assertThat(paneLayout(DpSize(800.dp, 479.dp), Detail(1))).isEqualTo(PaneLayout.Single)
  }

  @Test
  fun wideWindowsSplitListDetail() {
    val sizes = listOf(iPadMiniPortrait, iPadPortrait, duoOpenLandscape, pixelFoldOpen, desktop)
    for (size in sizes) {
      val layout = paneLayout(size, Detail(1))
      assertThat(layout.isWide).isTrue()
      assertThat(layout.detailStyle).isEqualTo(PaneStyle.LIST_DETAIL)
      assertThat(layout.detailRoot).isEqualTo(Detail(1))
    }
  }

  @Test
  fun wideWindowsSplitSupporting() {
    for (size in listOf(duoOpenLandscape, pixelFoldOpen, desktop)) {
      assertThat(paneLayout(size, Supporting(1)).detailStyle).isEqualTo(PaneStyle.SUPPORTING)
    }
  }

  @Test
  fun wideWindowWithNoPaneScreenFillsTheWidth() {
    val layout = paneLayout(desktop, Plain)
    assertThat(layout.isWide).isTrue()
    assertThat(layout.isSplit).isFalse()
    assertThat(layout.detailRoot).isNull()
  }

  @Test
  fun rootAloneKeepsThePanesWithNothingOpen() {
    val layout = paneLayout(desktop, null)
    assertThat(layout.detailStyle).isEqualTo(PaneStyle.LIST_DETAIL)
    assertThat(layout.detailRoot).isNull()
  }

  @Test
  fun separatingHingeSplitsThePanes() {
    val hinge = PaneHinge(425.dp, 426.dp)
    assertThat(paneLayout(pixelFoldOpen, Detail(1), hinge).hinge).isEqualTo(hinge)
    assertThat(paneLayout(pixelFoldOpen, Supporting(1), hinge).hinge).isEqualTo(hinge)
  }

  @Test
  fun hingeIgnoredWithoutPanesOrRoom() {
    val middle = PaneHinge(425.dp, 426.dp)
    // Folded or medium windows keep the phone layout
    assertThat(paneLayout(duoOpenPortrait, Detail(1), PaneHinge(334.dp, 335.dp)).hinge).isNull()
    // A plain screen fills the window
    assertThat(paneLayout(pixelFoldOpen, Plain, middle).hinge).isNull()
    // Too close to either edge for a pane
    assertThat(paneLayout(pixelFoldOpen, Detail(1), PaneHinge(200.dp, 201.dp)).hinge).isNull()
    assertThat(paneLayout(pixelFoldOpen, Detail(1), PaneHinge(700.dp, 701.dp)).hinge).isNull()
  }

  @Test
  fun paneWidthsScaleWithinBounds() {
    assertThat(paneLayout(pixelFoldOpen, Detail(1)).listPaneWidth.value).isCloseTo(340.4f, 0.01f)
    assertThat(paneLayout(duoOpenLandscape, Detail(1)).listPaneWidth.value).isCloseTo(380.4f, 0.01f)
    assertThat(paneLayout(desktop, Detail(1)).listPaneWidth).isEqualTo(400.dp)
    assertThat(paneLayout(duoOpenLandscape, Detail(1)).supportingPaneWidth).isEqualTo(320.dp)
    assertThat(paneLayout(desktop, Detail(1)).supportingPaneWidth.value).isCloseTo(396f, 0.01f)
  }

  @Test
  fun navigationBesideThePanesKeepsTheWindowClassButNarrowsThePanes() {
    // A 96dp rail leaves the open Pixel Fold's panes 755dp, under the 840dp expanded width
    val panesWidth = pixelFoldOpen.width - 96.dp
    val layout = paneLayout(pixelFoldOpen, Detail(1), panesWidth = panesWidth)
    assertThat(layout.isWide).isTrue()
    assertThat(layout.listPaneWidth.value).isCloseTo(302f, 0.01f)
    // Hinges are measured from the panes' start. The fold sits 329dp in, with room on both sides.
    val fold = PaneHinge(329.dp, 330.dp)
    assertThat(paneLayout(pixelFoldOpen, Detail(1), fold, panesWidth = panesWidth).hinge)
      .isEqualTo(fold)
    // 264dp in leaves too little room before the hinge
    assertThat(paneLayout(pixelFoldOpen, Detail(1), PaneHinge(264.dp, 265.dp), panesWidth).hinge)
      .isNull()
  }

  @Test
  fun rootIsNeverAPane() {
    assertThat(paneLayout(desktop, Root).isSplit).isFalse()
  }
}
