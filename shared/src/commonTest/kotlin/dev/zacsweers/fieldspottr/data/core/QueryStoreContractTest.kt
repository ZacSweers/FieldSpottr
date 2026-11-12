// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.testing.QueryStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.clearFetched
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.serializer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

// The core's query stores, each checked against the same contract

class InMemoryQueryStoreContractTest : QueryStoreContract() {
  override fun TestScope.newStore(): QueryStore<String> = QueryStore.inMemory()
}

class InMemoryByKeyQueryStoreContractTest : QueryStoreContract() {
  override fun TestScope.newStore(): QueryStore<String> =
    QueryStore.inMemoryByKey<String, String>()("key")
}

class ComposedQueryStoreContractTest : QueryStoreContract() {
  // No invalidate, so this covers the default that rewrites the value with the flag set
  override fun TestScope.newStore(): QueryStore<String> {
    val state = MutableStateFlow<Fetched<String>?>(null)
    return QueryStore.of(
      state,
      clear = state::clearFetched,
      write = { fetched, keepsStored ->
        state.writeFetched(fetched, keepsStored)
      },
    )
  }
}

class DataStoreQueryStoreContractTest : QueryStoreContract() {
  private val fileSystem = FakeFileSystem()
  private val path = "/store/a.json".toPath()
  // The scope of the DataStore open now. DataStore allows one active instance per file, so a
  // reopen cancels it first.
  private var open: Job? = null

  override fun TestScope.newStore(): QueryStore<String> {
    fileSystem.createDirectories(path.parent!!)
    return openStore()
  }

  override suspend fun TestScope.reopen(store: QueryStore<String>): QueryStore<String> {
    open?.cancelAndJoin()
    return openStore()
  }

  private fun TestScope.openStore(): QueryStore<String> {
    val job = Job(backgroundScope.coroutineContext[Job])
    open = job
    return QueryStore.inFile(
      scope = backgroundScope + job,
      path = { path },
      serializer = String.serializer(),
      fileSystem = fileSystem,
    )
  }
}

class RecordingQueryStoreContractTest : QueryStoreContract() {
  override fun TestScope.newStore(): QueryStore<String> = RecordingQueryStore()
}
