// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeepsStored
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * A JSON file whose fetch time, invalidated flag and app version live in the metadata table under
 * [fetchedAtKey], [invalidatedKey] and [appVersionKey]. The file is read once and then held in
 * memory. A file with no fetch time, or one another app version wrote, reads as stale, since a new
 * version may read it differently.
 */
internal class StampedFileStore<T : Any>(
  private val fs: FileSystem,
  /**
   * Resolves the file's path. It runs on [io] at the first file access, since resolving an app
   * directory can create it.
   */
  path: () -> Path,
  private val db: SuspendLazy<FSDatabase>,
  /** Runs the file and database calls, which block. */
  private val io: CoroutineDispatcher,
  private val fetchedAtKey: String,
  private val invalidatedKey: String,
  private val appVersionKey: String,
  private val decode: (String) -> T?,
  private val encode: (T) -> String,
) : QueryStore<T> {
  private val path by lazy(path)
  // Null until the first read loads the file
  private val loaded = MutableStateFlow<Loaded<T>?>(null)
  // Takes turns between the calls that change the file, so a check and its write are one step
  private val writes = Mutex()

  override val data: Flow<Fetched<T>?> = flow {
    if (loaded.value == null) {
      val fromDisk = readStamped()
      // A write that lands first wins, so a slow read never replaces newer data
      loaded.compareAndSet(null, Loaded(fromDisk))
    }
    emitAll(loaded.filterNotNull().map { it.fetched })
  }

  // The lock makes the check and the write one update, since this instance owns the file
  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    writes.withLock {
      val stored = data.first()
      if (stored == null || !keepsStored(stored.fetchedAt, stored.invalidated)) {
        store(fetched)
      }
    }
  }

  override suspend fun clearFetched(keepsStored: KeepsStored) {
    writes.withLock {
      val stored = data.first()
      if (stored == null || !keepsStored(stored.fetchedAt, stored.invalidated)) {
        remove()
      }
    }
  }

  // Only the flag changes, so the file and its fetch time stay as they are
  override suspend fun invalidate() {
    writes.withLock {
      val current = data.first() ?: return
      withContext(io) { db.await().fsdbQueries.setMetadata(invalidatedKey, true.toString()) }
      loaded.value = Loaded(current.copy(invalidated = true))
    }
  }

  /** Deletes the file, then shows nothing stored. Callers hold [writes]. */
  private suspend fun remove() {
    withContext(io) { fs.delete(path) }
    loaded.value = Loaded(null)
  }

  /** Writes [fetched]'s value and its stamp, then shows it. Callers hold [writes]. */
  private suspend fun store(fetched: Fetched<T>) {
    withContext(io) {
      writeAtomically(encode(fetched.value))
      stamp(fetched.fetchedAt, fetched.invalidated)
    }
    loaded.value = Loaded(fetched)
  }

  /** Stores the fetch time, or removes it when null, with the flag and this app version. */
  private suspend fun stamp(fetchedAt: Instant?, invalidated: Boolean) {
    val database = db.await()
    database.transaction {
      val queries = database.fsdbQueries
      if (fetchedAt == null) {
        queries.deleteMetadata(fetchedAtKey)
      } else {
        queries.setMetadata(fetchedAtKey, fetchedAt.toEpochMilliseconds().toString())
      }
      queries.setMetadata(invalidatedKey, invalidated.toString())
      queries.setMetadata(appVersionKey, BuildConfig.VERSION_CODE.toString())
    }
  }

  /**
   * The file with its stamp. A missing fetch time reads as never fetched. A file another app
   * version wrote keeps its fetch time and reads as invalidated.
   */
  private suspend fun readStamped(): Fetched<T>? {
    return withContext(io) {
      val value = fs.readTextOrNull(path)?.let(decode) ?: return@withContext null
      val queries = db.await().fsdbQueries
      val fetchedAt = queries.getMetadata(fetchedAtKey).executeAsOneOrNull()?.toLongOrNull()
      val invalidated = queries.getMetadata(invalidatedKey).executeAsOneOrNull() == true.toString()
      val appVersion = queries.getMetadata(appVersionKey).executeAsOneOrNull()?.toLongOrNull()
      Fetched(
        value,
        fetchedAt?.let(Instant::fromEpochMilliseconds),
        invalidated = invalidated || appVersion != BuildConfig.VERSION_CODE,
      )
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
