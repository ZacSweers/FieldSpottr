// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test

class AnimatedRowsTest {
  private val identity: (String) -> Any = { it }

  @Test
  fun `a removed row stays where it was`() {
    assertThat(mergeKeepingRemoved(listOf("a", "b", "c"), listOf("a", "c"), identity))
      .containsExactly("a", "b", "c")
  }

  @Test
  fun `a removed first row stays first`() {
    assertThat(mergeKeepingRemoved(listOf("a", "b"), listOf("b"), identity))
      .containsExactly("a", "b")
  }

  @Test
  fun `added and removed rows keep their places together`() {
    assertThat(mergeKeepingRemoved(listOf("a", "b"), listOf("a", "c"), identity))
      .containsExactly("a", "b", "c")
  }

  @Test
  fun `removing every row keeps them all while they leave`() {
    assertThat(mergeKeepingRemoved(listOf("a", "b"), emptyList(), identity))
      .containsExactly("a", "b")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `a removed row leaves only after its exit finishes`() = runComposeUiTest {
    var items by mutableStateOf(listOf("a", "b", "c"))
    setContent { AnimatedRows(items, keyOf = identity) { Text(it, Modifier.testTag(it)) } }
    onNodeWithTag("b").assertIsDisplayed()

    mainClock.autoAdvance = false
    items = listOf("a", "c")
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeBy(50)
    onNodeWithTag("b").assertExists()

    mainClock.autoAdvance = true
    waitForIdle()
    onNodeWithTag("b").assertDoesNotExist()
    onNodeWithTag("c").assertIsDisplayed()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `an added row shows once it enters`() = runComposeUiTest {
    var items by mutableStateOf(listOf("a"))
    setContent { AnimatedRows(items, keyOf = identity) { Text(it, Modifier.testTag(it)) } }

    items = listOf("a", "b")
    waitForIdle()
    onNodeWithTag("b").assertIsDisplayed()
  }
}
