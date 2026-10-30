// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import dev.zacsweers.fieldspottr.data.core.testing.QueryStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import okio.BufferedSink
import okio.BufferedSource
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
  // No invalidate, so this covers the default that rewrites the value with a stale time
  override fun TestScope.newStore(): QueryStore<String> {
    val state = MutableStateFlow<Fetched<String>?>(null)
    return QueryStore.of(state, clear = { state.value = null }) { value, fetchedAt ->
      state.value = Fetched(value, fetchedAt)
    }
  }
}

class DataStoreQueryStoreContractTest : QueryStoreContract() {
  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(): QueryStore<String> {
    val directory = "/store".toPath()
    fileSystem.createDirectories(directory)
    return DataStoreFactory.create(
        storage = OkioStorage(fileSystem, FetchedTextSerializer, producePath = { directory / "a" }),
        scope = backgroundScope,
      )
      .asQueryStore()
  }
}

class RecordingQueryStoreContractTest : QueryStoreContract() {
  override fun TestScope.newStore(): QueryStore<String> = RecordingQueryStore()
}

/** The fetch time in epoch milliseconds on the first line, then the value. Empty means nothing. */
private object FetchedTextSerializer : OkioSerializer<Fetched<String>?> {
  override val defaultValue: Fetched<String>? = null

  override suspend fun readFrom(source: BufferedSource): Fetched<String>? {
    val text = source.readUtf8()
    if (text.isEmpty()) {
      return null
    }
    val (millis, value) = text.split('\n', limit = 2)
    return Fetched(value, Instant.fromEpochMilliseconds(millis.toLong()))
  }

  override suspend fun writeTo(t: Fetched<String>?, sink: BufferedSink) {
    if (t != null) {
      sink.writeUtf8("${t.fetchedAt.toEpochMilliseconds()}\n${t.value}")
    }
  }
}
