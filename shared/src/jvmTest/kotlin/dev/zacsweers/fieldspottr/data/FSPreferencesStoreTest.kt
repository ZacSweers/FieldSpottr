// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import dev.zacsweers.fieldspottr.FakeFSAppDirs
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath

class FSPreferencesStoreTest {
  private val store =
    FSPreferencesStore(
      FakeFSAppDirs(FileSystem.SYSTEM, Files.createTempDirectory("fs-prefs").toOkioPath()),
      Json { ignoreUnknownKeys = true },
    )

  @Test
  fun `no custom windows by default`() = runTest {
    assertThat(store.customTimeWindows.first()).isEmpty()
  }

  @Test
  fun `custom windows keep creation order and edit in place`() = runTest {
    val afterWork = TimeWindow.custom("a", "After work", 17, 20)
    val practice = TimeWindow.custom("b", "Practice", 15, 17)
    store.saveCustomTimeWindow(afterWork)
    store.saveCustomTimeWindow(practice)
    assertThat(store.customTimeWindows.first()).containsExactly(afterWork, practice)

    val edited = afterWork.copy(name = "Late", endMinutes = 22 * 60)
    store.saveCustomTimeWindow(edited)
    assertThat(store.customTimeWindows.first()).containsExactly(edited, practice)
  }

  @Test
  fun `deleting a custom window removes only that window`() = runTest {
    val afterWork = TimeWindow.custom("a", "After work", 17, 20)
    val practice = TimeWindow.custom("b", "Practice", 15, 17)
    store.saveCustomTimeWindow(afterWork)
    store.saveCustomTimeWindow(practice)

    store.deleteCustomTimeWindow("a")
    assertThat(store.customTimeWindows.first()).containsExactly(practice)
  }
}
