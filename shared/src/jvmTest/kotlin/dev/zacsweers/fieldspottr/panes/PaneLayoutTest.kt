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
  fun phonesAndMediumWindowsKeepTheSingleLayout() {
    val sizes =
      listOf(
        phonePortrait,
        phoneLandscape,
        duoFoldedPortrait,
        duoFoldedLandscape,
        duoOpenPortrait,
        iPadMiniPortrait,
        iPadPortrait,
      )
    for (size in sizes) {
      val layout = paneLayout(size, Detail(1), wantsEmptyDetail = true)
      assertThat(layout).isEqualTo(PaneLayout.Single)
    }
  }

  @Test
  fun wideWindowsSplitListDetail() {
    for (size in listOf(duoOpenLandscape, pixelFoldOpen, desktop)) {
      val layout = paneLayout(size, Detail(1), wantsEmptyDetail = false)
      assertThat(layout.isWide).isTrue()
      assertThat(layout.detailStyle).isEqualTo(PaneStyle.LIST_DETAIL)
      assertThat(layout.detailRoot).isEqualTo(Detail(1))
    }
  }

  @Test
  fun wideWindowsSplitSupporting() {
    for (size in listOf(duoOpenLandscape, pixelFoldOpen, desktop)) {
      assertThat(paneLayout(size, Supporting(1), false).detailStyle).isEqualTo(PaneStyle.SUPPORTING)
    }
  }

  @Test
  fun wideWindowWithNoPaneScreenFillsTheWidth() {
    val layout = paneLayout(desktop, Plain, wantsEmptyDetail = true)
    assertThat(layout.isWide).isTrue()
    assertThat(layout.isSplit).isFalse()
    assertThat(layout.detailRoot).isNull()
  }

  @Test
  fun rootAloneReservesThePaneOnlyWhenAsked() {
    assertThat(paneLayout(desktop, null, wantsEmptyDetail = true).detailStyle)
      .isEqualTo(PaneStyle.LIST_DETAIL)
    assertThat(paneLayout(desktop, null, wantsEmptyDetail = false).isSplit).isFalse()
  }

  @Test
  fun paneWidthsScaleWithinBounds() {
    assertThat(paneLayout(pixelFoldOpen, Detail(1), false).listPaneWidth.value)
      .isCloseTo(308.4f, 0.01f)
    assertThat(paneLayout(duoOpenLandscape, Detail(1), false).listPaneWidth.value)
      .isCloseTo(348.4f, 0.01f)
    assertThat(paneLayout(desktop, Detail(1), false).listPaneWidth).isEqualTo(400.dp)
    assertThat(paneLayout(duoOpenLandscape, Detail(1), false).supportingPaneWidth).isEqualTo(320.dp)
    assertThat(paneLayout(desktop, Detail(1), false).supportingPaneWidth.value)
      .isCloseTo(396f, 0.01f)
  }

  @Test
  fun rootIsNeverAPane() {
    assertThat(paneLayout(desktop, Root, wantsEmptyDetail = false).isSplit).isFalse()
  }
}
