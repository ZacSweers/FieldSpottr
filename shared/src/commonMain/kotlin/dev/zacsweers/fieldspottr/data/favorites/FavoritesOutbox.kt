// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxSender
import dev.zacsweers.fieldspottr.data.core.sender
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.serialization.Serializable

/** One change to the favorites, as the outbox stores and sends it. */
@Serializable data class FavoriteChange(val group: String, val on: Boolean)

/**
 * The favorites outbox. [FavoritesRepository] builds its optimistic store with this key, and the
 * app data scope drains it with [favoritesSender]. "favorites" names the stored rows, so keep it.
 */
internal val FavoritesOutbox = OutboxKey<FavoriteChange>("favorites")

/**
 * Sends each change to [api], with the row's id as the idempotency key. The server answers with
 * nothing, so the store applies the change to its truth itself once the send succeeds.
 */
internal fun favoritesSender(api: FavoritesApi): OutboxSender<*> =
  FavoritesOutbox.sender { change, id ->
    api.setFavorite(change.group, change.on, idempotencyKey = id.value)
  }

/** Adds the favorites sender to the senders the app data scope drains from its creation. */
@ContributesTo(AppScope::class)
interface FavoritesOutboxProvider {
  @Provides
  @IntoSet
  fun provideFavoritesSender(api: FavoritesApi): OutboxSender<*> = favoritesSender(api)
}
