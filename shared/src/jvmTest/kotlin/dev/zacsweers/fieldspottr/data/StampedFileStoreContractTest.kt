// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.QueryStoreContract
import dev.zacsweers.metro.suspendLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Rule

class StampedFileStoreContractTest : QueryStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(): QueryStore<String> =
    StampedFileStore(
      fs = fileSystem,
      path = "/data/value.txt".toPath(),
      db = suspendLazy { temporaryDatabase.db() },
      // Real threads, the way the app's stores run their blocking calls
      io = Dispatchers.IO,
      fetchedAtKey = "valueFetchedAt",
      appVersionKey = "valueAppVersion",
      decode = { it },
      encode = { it },
    )
}
