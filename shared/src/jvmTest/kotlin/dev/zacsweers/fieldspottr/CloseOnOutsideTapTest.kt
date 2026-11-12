// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CloseOnOutsideTapTest {
  private var closes = 0
  private var outsideClicks = 0
  private var insideClicks = 0

  /** An outside button above an inside one, in a column that closes on outside taps. */
  private fun ComposeUiTest.setUp(enabled: Boolean = true) {
    setContent {
      val area = remember { OutsideTapArea() }
      Column(Modifier.size(300.dp).closeOnOutsideTap(area, enabled) { closes++ }) {
        Box(Modifier.size(100.dp).testTag("outside").clickable { outsideClicks++ })
        Box(
          Modifier.size(100.dp)
            .testTag("inside")
            .onGloballyPositioned { area.inside = it }
            .clickable { insideClicks++ }
        )
      }
    }
  }

  @Test
  fun `a tap outside closes without pressing what's under it`() = runComposeUiTest {
    setUp()
    onNodeWithTag("outside").performClick()
    assertThat(closes).isEqualTo(1)
    assertThat(outsideClicks).isEqualTo(0)
  }

  @Test
  fun `a tap inside doesn't close`() = runComposeUiTest {
    setUp()
    onNodeWithTag("inside").performClick()
    assertThat(closes).isEqualTo(0)
    assertThat(insideClicks).isEqualTo(1)
  }

  @Test
  fun `a drag outside doesn't close`() = runComposeUiTest {
    setUp()
    onNodeWithTag("outside").performTouchInput { swipeDown() }
    assertThat(closes).isEqualTo(0)
  }

  @Test
  fun `taps pass through while disabled`() = runComposeUiTest {
    setUp(enabled = false)
    onNodeWithTag("outside").performClick()
    assertThat(closes).isEqualTo(0)
    assertThat(outsideClicks).isEqualTo(1)
  }
}
