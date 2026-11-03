// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.di.IoDispatcher
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** The favorites backend: the set the server holds, and one change to it. */
interface FavoritesApi {
  /** The field groups the server holds as favorites. */
  suspend fun favorites(): Set<String>

  /**
   * Sets one group's favorite state. [idempotencyKey] identifies the request across resends, so a
   * row sent again after a restart changes nothing twice.
   */
  suspend fun setFavorite(group: String, on: Boolean, idempotencyKey: String)
}

/**
 * Stands in for a favorites service until one exists. The server's set is a JSON file under the
 * app's data directory, so it survives restarts and the truth the store fetches from it agrees with
 * what was sent, and each call takes a moment, as a request would. Setting a favorite is idempotent
 * by nature, so the key is accepted and not needed.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class LocalFavoritesServer(
  appDirs: FSAppDirs,
  @IoDispatcher private val io: CoroutineDispatcher,
  private val json: Json,
) : FavoritesApi {
  private val fs: FileSystem = appDirs.fs
  private val file: Path = appDirs.userData / "local-server" / "favorites.json"
  // One request at a time, as a server's row lock would
  private val requests = Mutex()

  override suspend fun favorites(): Set<String> {
    delay(LATENCY)
    return requests.withLock { read() }
  }

  override suspend fun setFavorite(group: String, on: Boolean, idempotencyKey: String) {
    delay(LATENCY)
    requests.withLock {
      val current = read()
      val next =
        if (on) {
          current + group
        } else {
          current - group
        }
      if (next != current) {
        write(next)
      }
    }
  }

  private suspend fun read(): Set<String> =
    withContext(io) {
      if (fs.exists(file)) {
        json.decodeFromString<Set<String>>(fs.read(file) { readUtf8() })
      } else {
        emptySet()
      }
    }

  private suspend fun write(favorites: Set<String>) {
    withContext(io) {
      fs.createDirectories(checkNotNull(file.parent))
      fs.write(file) { writeUtf8(json.encodeToString(favorites)) }
    }
  }

  private companion object {
    /** Long enough to see a change as pending, short enough not to feel broken. */
    val LATENCY = 600.milliseconds
  }
}
