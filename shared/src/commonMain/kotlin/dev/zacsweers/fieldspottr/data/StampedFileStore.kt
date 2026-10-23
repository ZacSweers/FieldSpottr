// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.metro.SuspendLazy
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * A JSON file whose fetch time and app version live in the metadata table under [fetchedAtKey] and
 * [appVersionKey]. The file is read once and then held in memory. A file with no fetch time, or one
 * another app version wrote, reads as stale, since a new version may read it differently.
 */
internal class StampedFileStore<T : Any>(
  private val fs: FileSystem,
  private val path: Path,
  private val db: SuspendLazy<FSDatabase>,
  /** Runs the file and database calls, which block. */
  private val io: CoroutineDispatcher,
  private val fetchedAtKey: String,
  private val appVersionKey: String,
  private val decode: (String) -> T?,
  private val encode: (T) -> String,
) : QueryStore<T> {
  // Null until the first read loads the file
  private val loaded = MutableStateFlow<Loaded<T>?>(null)

  override val data: Flow<Fetched<T>?> = flow {
    if (loaded.value == null) {
      val fromDisk = readStamped()
      // A write that lands first wins, so a slow read never replaces newer data
      loaded.compareAndSet(null, Loaded(fromDisk))
    }
    emitAll(loaded.filterNotNull().map { it.fetched })
  }

  override suspend fun write(value: T, fetchedAt: Instant) {
    withContext(io) {
      writeAtomically(encode(value))
      stamp(fetchedAt)
    }
    loaded.value = Loaded(Fetched(value, fetchedAt))
  }

  override suspend fun clear() {
    withContext(io) { fs.delete(path) }
    loaded.value = Loaded(null)
  }

  // Only the fetch time changes, so the file stays as it is
  override suspend fun invalidate() {
    val current = data.first() ?: return
    withContext(io) { stamp(QueryStore.INVALIDATED) }
    loaded.value = Loaded(current.copy(fetchedAt = QueryStore.INVALIDATED))
  }

  private suspend fun stamp(fetchedAt: Instant) {
    val database = db.await()
    database.transaction {
      database.fsdbQueries.setMetadata(fetchedAtKey, fetchedAt.toEpochMilliseconds().toString())
      database.fsdbQueries.setMetadata(appVersionKey, BuildConfig.VERSION_CODE.toString())
    }
  }

  private suspend fun readStamped(): Fetched<T>? {
    return withContext(io) {
      val value = fs.readTextOrNull(path)?.let(decode) ?: return@withContext null
      val queries = db.await().fsdbQueries
      val fetchedAt = queries.getMetadata(fetchedAtKey).executeAsOneOrNull()?.toLongOrNull()
      val appVersion = queries.getMetadata(appVersionKey).executeAsOneOrNull()?.toLongOrNull()
      val stamp =
        if (fetchedAt != null && appVersion == BuildConfig.VERSION_CODE) {
          Instant.fromEpochMilliseconds(fetchedAt)
        } else {
          QueryStore.INVALIDATED
        }
      Fetched(value, stamp)
    }
  }

  /** Writes beside the file, then moves into place, so a reader never sees half a file. */
  private fun writeAtomically(text: String) {
    val parent = checkNotNull(path.parent) { "$path has no parent directory" }
    val tempPath = parent / "${path.name}.tmp"
    fs.createDirectories(parent)
    fs.sink(tempPath).buffer().use { it.writeUtf8(text) }
    fs.delete(path)
    fs.atomicMove(tempPath, path)
  }

  /** The file's contents once read. [fetched] is null when the file holds nothing usable. */
  private class Loaded<T>(val fetched: Fetched<T>?)
}

/** The text of the file at [path], or null when there is no such file. */
internal fun FileSystem.readTextOrNull(path: Path): String? {
  if (!exists(path)) {
    return null
  }
  return source(path).buffer().use { it.readUtf8() }
}
