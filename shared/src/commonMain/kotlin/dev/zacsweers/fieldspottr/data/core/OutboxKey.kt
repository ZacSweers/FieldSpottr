// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Names one outbox and its payload type [E], and joins the outbox's two halves: the [OutboxSender]
 * that sends its rows and the handle that writes them, like [DataScope.outbox] or
 * [DataScope.optimisticStore]. [R] is the type of the server's answer, for a key built with
 * [replying]. A plain key has `R = Nothing`, and it fits a store of any value type.
 *
 * A data scope drains every sender it was created with, from the moment it is created, so rows
 * stored before a restart send even before any repository is built. The repository builds the
 * handle later with the same key, and the handle keeps all local state: the optimistic change, the
 * commit of a sent change to the truth, and the revert of a failed one.
 *
 * Keep the payload type, the key and the sender's provider in one file per feature:
 * ```
 * // FavoritesOutbox.kt
 * @Serializable data class FavoriteChange(val group: String, val on: Boolean)
 *
 * internal val FavoritesOutbox = OutboxKey<FavoriteChange>("favorites")
 *
 * @ContributesTo(AppScope::class)
 * interface FavoritesOutboxProvider {
 *   @Provides @IntoSet
 *   fun favoritesSender(api: FavoritesApi): OutboxSender<*> =
 *     FavoritesOutbox.sender { change, id ->
 *       api.setFavorite(change.group, change.on, idempotencyKey = id.value)
 *     }
 * }
 *
 * // FavoritesRepository.kt
 * private val store =
 *   data.optimisticStore(FavoritesOutbox, base = base, apply = { current, change -> ... })
 * ```
 *
 * [name] is the on-disk name of the outbox's rows. Renaming the `val` that holds the key changes
 * nothing stored. Changing [name] strands the rows stored under the old name, since no sender
 * drains them anymore.
 *
 * @property name the outbox's name in its store set. For a sender over the factory's
 *   [OutboxStores], one data scope factory runs one sender per name at a time. A sender with a
 *   store of its own is checked only within its data scope.
 * @property serializer stores each payload as a row.
 */
class OutboxKey<E, out R>
@PublishedApi
internal constructor(
  val name: String,
  val serializer: KSerializer<E>,
  /** Encodes the server's answers, or null for a plain key, whose sends answer with nothing. */
  @PublishedApi internal val replySerializer: KSerializer<@UnsafeVariance R>?,
) {
  override fun toString(): String = "OutboxKey($name)"

  companion object
}

/**
 * A key for the outbox called [name], whose server answers each send with nothing. Its sender is
 * built with [sender].
 *
 * ```
 * internal val NotesOutbox = OutboxKey<NoteChange>("notes")
 * ```
 */
inline fun <reified E> OutboxKey(name: String): OutboxKey<E, Nothing> =
  OutboxKey(name, serializer<E>())

/** [OutboxKey] with an explicit [serializer], for a payload type without a reified serializer. */
fun <E> OutboxKey(name: String, serializer: KSerializer<E>): OutboxKey<E, Nothing> =
  OutboxKey(name, serializer, replySerializer = null)

/**
 * A key for the outbox called [name], whose server can answer a send with a value of type [R], like
 * the count a counter holds after an increment. Its sender is built with [exchangingSender] or
 * [deliveringSender], and an optimistic store over it needs a value type of [R].
 *
 * A row sent while no handle can commit it keeps the answer until a handle attaches. A sender over
 * the factory's [OutboxStores] encodes it with [OutboxStores.replyCodec]. A sender with a store of
 * its own encodes it as JSON.
 *
 * ```
 * internal val CounterOutbox = OutboxKey.replying<CounterChange, Int>("counter")
 * ```
 */
inline fun <reified E, reified R> OutboxKey.Companion.replying(name: String): OutboxKey<E, R> =
  replying(name, serializer<E>(), serializer<R>())

/** [replying] with explicit serializers, for types without a reified serializer. */
fun <E, R> OutboxKey.Companion.replying(
  name: String,
  serializer: KSerializer<E>,
  replySerializer: KSerializer<R>,
): OutboxKey<E, R> = OutboxKey(name, serializer, replySerializer)
