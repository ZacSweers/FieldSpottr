// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.data.OutboxOwner
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.PendingMutation
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.inFile
import dev.zacsweers.fieldspottr.data.core.mutableStore
import dev.zacsweers.fieldspottr.data.core.values
import dev.zacsweers.fieldspottr.di.IoDispatcher
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One change to the favorites, as the outbox stores and sends it. */
@Serializable data class FavoriteChange(val group: String, val on: Boolean)

/**
 * The user's favorite field groups, changed locally first and sent to [FavoritesApi] after. The
 * truth is the last set fetched from the server, in a DataStore file, and each change is an outbox
 * row beside it until the server takes it. [favorites] shows the truth with the waiting changes on
 * top, so a tap shows at once, offline too, and a restart shows the same.
 *
 * This repository is the app's first use of [mutableStore], and it exercises the whole path: outbox
 * rows in the database, drains that wait for the network monitor, and a WorkManager drain on
 * Android.
 */
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<OutboxOwner>())
class FavoritesRepository
internal constructor(
  data: DataScope,
  base: QueryStore<Set<String>>,
  private val api: FavoritesApi,
) : OutboxOwner {

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
    data.mutableStore<Set<String>, FavoriteChange>(
      name = "favorites",
      base = base,
      // Idempotent: a group set on twice is on once, so a resend or an overlapping fetch is safe
      apply = { current, change ->
        if (change.on) {
          current.orEmpty() + change.group
        } else {
          current.orEmpty() - change.group
        }
      },
      // The row's id doubles as the idempotency key. The server sends nothing back, so the store
      // applies the change to the truth itself once the send succeeds.
      send = { change, id -> api.setFavorite(change.group, change.on, idempotencyKey = id.value) },
    )

  private val query =
    data.query(store, fetch = api::favorites, freshness = Freshness.staleAfter(1.hours))

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
