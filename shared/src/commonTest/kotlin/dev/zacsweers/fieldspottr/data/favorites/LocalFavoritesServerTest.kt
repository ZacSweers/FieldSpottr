// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import dev.zacsweers.fieldspottr.FSAppDirs
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class LocalFavoritesServerTest {
  private val dirs =
    object : FSAppDirs {
      override val fs: FileSystem = FakeFileSystem()
      override val userConfig: Path = "/config".toPath()
      override val userData: Path = "/data".toPath()
      override val userCache: Path = "/cache".toPath()
    }

  @Test
  fun `what one server instance stores, the next one reads, as across a restart`() = runTest {
    val io = StandardTestDispatcher(testScheduler)
    val before = LocalFavoritesServer(dirs, io, Json)
    assertThat(before.favorites()).isEmpty()

    before.setFavorite("Prospect Park", on = true, idempotencyKey = "row-1")
    before.setFavorite("McCarren Park", on = true, idempotencyKey = "row-2")
    before.setFavorite("Prospect Park", on = false, idempotencyKey = "row-3")

    val after = LocalFavoritesServer(dirs, io, Json)
    assertThat(after.favorites()).containsOnly("McCarren Park")
  }
}
