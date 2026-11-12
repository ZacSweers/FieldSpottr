// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.PendingMutation
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.data
import dev.zacsweers.fieldspottr.data.core.inFile
import dev.zacsweers.fieldspottr.data.core.values
import dev.zacsweers.fieldspottr.di.IoDispatcher
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json

/**
 * The user's favorite field groups, changed locally first and sent to [FavoritesApi] after. The
 * truth is the last set fetched from the server, in a DataStore file, and each change is a row of
 * the [FavoritesOutbox] beside it until the server takes it. [favorites] shows the truth with the
 * waiting changes on top, so a tap shows at once, offline too, and a restart shows the same.
 *
 * This repository holds the handle on the outbox. Its sender, [favoritesSender], belongs to the app
 * data scope, so rows stored before a restart send before this repository exists, like in a
 * WorkManager drain on Android. A change sent then reaches the truth when this repository is built.
 *
 * It is the app's first use of [DataScope.optimisticStore], and it exercises the whole path: outbox
 * rows in the database, drains that wait for the network monitor, and a WorkManager drain on
 * Android.
 */
@SingleIn(AppScope::class)
class FavoritesRepository
internal constructor(
  data: DataScope,
  base: QueryStore<Set<String>>,
  private val api: FavoritesApi,
) {

  @Inject
  constructor(
    @ForScope(AppScope::class) data: DataScope,
    appDirs: FSAppDirs,
    json: Json,
    api: FavoritesApi,
    @IoDispatcher io: CoroutineDispatcher,
  ) : this(
    data,
    // The truth's file, kept open for the app's life and read and written on the I/O dispatcher
    QueryStore.inFile<Set<String>>(
      scope = data.scope + io,
      path = { appDirs.userData / "favorites.json" },
      json = json,
      fileSystem = appDirs.fs,
    ),
    api,
  )

  private val store =
    data.optimisticStore(
      FavoritesOutbox,
      base = base,
      // Idempotent: a group set on twice is on once, so a resend or an overlapping fetch is safe
      apply = { current, change ->
        if (change.on) {
          current.orEmpty() + change.group
        } else {
          current.orEmpty() - change.group
        }
      },
    )

  private val query = data.query(store, Freshness.staleAfter(1.hours), fetch = api::favorites)

  /** The favorite groups, local changes included. Collecting it fetches first when stale. */
  val favorites: Flow<Set<String>> = query.data.values().map { it.orEmpty() }

  /** Changes the server has not confirmed, oldest first, for a badge and for retry. */
  val unsynced: Flow<List<PendingMutation<FavoriteChange>>> = store.pending

  /** Turns [group]'s favorite [on] or off. The handle settles once the server has it. */
  fun setFavorite(group: String, on: Boolean): Operation = store.mutate(FavoriteChange(group, on))

  /** Sends a change the server rejected again. */
  fun retry(id: EntryId): Operation = store.retry(id)

  /** Drops a change the server rejected, so [favorites] shows the truth without it. */
  fun discard(id: EntryId): Operation = store.discard(id)
}
