// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A [QueryStore] over a DataStore that holds one fetched value. DataStore writes the file
 * atomically and allows one active instance per file, so build the DataStore once per process.
 */
fun <T> DataStore<Fetched<T>?>.asQueryStore(): QueryStore<T> =
  QueryStore.of(
    data,
    clear = { keepsStored ->
      updateData { current ->
        if (current != null && keepsStored(current.fetchedAt)) {
          current
        } else {
          null
        }
      }
    },
    invalidate = { updateData { it?.copy(fetchedAt = QueryStore.INVALIDATED) } },
  ) { value, fetchedAt, keepsStored ->
    updateData { current ->
      if (current != null && keepsStored(current.fetchedAt)) {
        current
      } else {
        Fetched(value, fetchedAt)
      }
    }
  }

/**
 * A DataStore file that holds one fetched value, for [asQueryStore] or [QueryStore.inFile]. The
 * value is written as JSON through [json] beside its fetch time, so the data stays fresh across
 * restarts. An unreadable file reads as empty, so the next collect fetches. DataStore allows one
 * active instance per file, so build it once per process, and does its file I/O in [scope], so give
 * it a dispatcher that suits blocking I/O, like the app scope plus an I/O dispatcher. Cancelling
 * [scope] releases the file.
 */
fun <T> fetchedDataStore(
  scope: CoroutineScope,
  path: () -> Path,
  serializer: KSerializer<T>,
  json: Json = Json,
  fileSystem: FileSystem = FileSystem.SYSTEM,
): DataStore<Fetched<T>?> =
  DataStoreFactory.create(
    storage = OkioStorage(fileSystem, FetchedSerializer(serializer, json), producePath = path),
    corruptionHandler = ReplaceFileCorruptionHandler { null },
    scope = scope,
  )

/**
 * A [QueryStore] in a file of its own, from [fetchedDataStore]. One file holds one value, so it
 * suits a single query, like a forecast or the user's settings, and a graph-scoped repository
 * builds it once by construction.
 */
fun <T> QueryStore.Companion.inFile(
  scope: CoroutineScope,
  path: () -> Path,
  serializer: KSerializer<T>,
  json: Json = Json,
  fileSystem: FileSystem = FileSystem.SYSTEM,
): QueryStore<T> = fetchedDataStore(scope, path, serializer, json, fileSystem).asQueryStore()

/** [inFile] with the serializer for a `@Serializable` value. */
inline fun <reified T> QueryStore.Companion.inFile(
  scope: CoroutineScope,
  noinline path: () -> Path,
  json: Json = Json,
  fileSystem: FileSystem = FileSystem.SYSTEM,
): QueryStore<T> = inFile(scope, path, serializer<T>(), json, fileSystem)

/** The fetched value and its fetch time in epoch milliseconds, as the file holds them. */
@Serializable private class FetchedRecord<T>(val fetchedAt: Long, val value: T)

/**
 * Reads and writes a [FetchedRecord] as JSON. A blank file, which a cleared store leaves behind,
 * reads as nothing stored.
 */
private class FetchedSerializer<T>(serializer: KSerializer<T>, private val json: Json) :
  OkioSerializer<Fetched<T>?> {
  private val record = FetchedRecord.serializer(serializer)

  override val defaultValue: Fetched<T>? = null

  override suspend fun readFrom(source: BufferedSource): Fetched<T>? {
    val text = source.readUtf8()
    if (text.isBlank()) {
      return null
    }
    val stored =
      try {
        json.decodeFromString(record, text)
      } catch (e: IllegalArgumentException) {
        throw CorruptionException("Unreadable fetched value", e)
      }
    return Fetched(stored.value, Instant.fromEpochMilliseconds(stored.fetchedAt))
  }

  override suspend fun writeTo(t: Fetched<T>?, sink: BufferedSink) {
    if (t == null) {
      return
    }
    val stored = FetchedRecord(t.fetchedAt.toEpochMilliseconds(), t.value)
    sink.writeUtf8(json.encodeToString(record, stored))
  }
}
