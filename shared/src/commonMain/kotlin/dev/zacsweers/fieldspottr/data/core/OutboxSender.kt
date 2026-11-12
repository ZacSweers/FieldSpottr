// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

/**
 * Sends the rows of the outbox that [key] names. A sender holds no state, so it can exist before
 * any repository does. The app contributes each sender into a `Set<OutboxSender<*>>` and hands the
 * set to [DataScope.Factory.create], and the data scope drains each sender's rows from then on.
 *
 * Build one with [sender], [exchangingSender] or [deliveringSender]:
 * - [sender] for a server that answers each send with nothing. Most outboxes use it.
 * - [exchangingSender] for a server that answers each send with the value it now holds, like a
 *   counter. An optimistic store replaces its truth with that value.
 * - [deliveringSender] for a server that answers some sends with nothing and others with a value.
 *
 * Each data scope takes one sender per key name, and [DataScope.Factory.create] throws for two. A
 * sender that no handle uses is legal: its rows still send, and the rows a handle must still commit
 * or revert wait in the store until a handle attaches.
 *
 * The sender's work runs on the data scope's dispatcher, usually `Dispatchers.Default`. A send that
 * blocks a thread moves that part to an I/O dispatcher with `withContext`. Throw [PermanentFailure]
 * for a rejection that retrying cannot fix.
 */
class OutboxSender<E>
internal constructor(
  /** The outbox this sender drains. */
  val key: OutboxKey<E, *>,
  /** Sends one payload with its row's id and returns the server's answer. */
  internal val deliver: suspend (payload: E, id: EntryId) -> Delivery<*>,
  /** The rows, or null to open them from the factory's [OutboxStores] by [OutboxKey.name]. */
  internal val store: OutboxStore<E>?,
  /** Decides whether a failed send retries and when, or null for the factory's policy. */
  internal val retry: RetryPolicy?,
  /**
   * Hears the drain's failures: the failure that ends a row for good, once per row, rows that
   * cannot be decoded, store errors in the drain and a retry policy that throws. A send that fails
   * and will retry is not reported. Null uses the factory's handler.
   */
  internal val onFailure: ((Throwable) -> Unit)?,
) {
  override fun toString(): String = "OutboxSender(${key.name})"
}

/**
 * A sender for a server that answers each send with nothing. Once [send] returns, the row is sent,
 * and an optimistic store applies the change to its truth with its own `apply`.
 *
 * ```
 * @Provides @IntoSet
 * fun notesSender(api: NotesApi): OutboxSender<*> =
 *   NotesOutbox.sender { change, id -> api.save(change, idempotencyKey = id.value) }
 * ```
 *
 * @param store keeps the rows in place of the factory's store set, like rows in an app table.
 *   Nothing claims such a store across data scopes, so give each data scope its own.
 * @param retry decides whether a failed send retries and when. Null uses the factory's policy.
 * @param onFailure hears the drain's failures, as [OutboxSender] describes. Null uses the factory's
 *   handler. The handle has a handler of its own.
 * @param send delivers one payload with its row's id. The id stays the same across attempts and
 *   restarts, so pass it to the server as the idempotency key.
 */
fun <E> OutboxKey<E, *>.sender(
  store: OutboxStore<E>? = null,
  retry: RetryPolicy? = null,
  onFailure: ((Throwable) -> Unit)? = null,
  send: suspend (payload: E, id: EntryId) -> Unit,
): OutboxSender<E> =
  OutboxSender(
    key = this,
    deliver = { payload, id ->
      send(payload, id)
      Delivery.Acknowledged
    },
    store = store,
    retry = retry,
    onFailure = onFailure,
  )

/**
 * A sender for a server that answers each send with the value it now holds. An optimistic store
 * replaces its truth with that value as fresh data. Use it for a change that cannot be applied
 * twice safely, like a counter's increment, or for an answer with fields the client cannot compute.
 * The key must come from [OutboxKey.replying], and this throws for a plain key.
 *
 * ```
 * internal val CounterOutbox = OutboxKey.replying<CounterChange, Int>("counter")
 *
 * @Provides @IntoSet
 * fun counterSender(api: CounterApi): OutboxSender<*> =
 *   CounterOutbox.exchangingSender { change, id -> api.add(change.by, idempotencyKey = id.value) }
 * ```
 *
 * [store], [retry] and [onFailure] work as in [sender].
 */
fun <E, R> OutboxKey<E, R>.exchangingSender(
  store: OutboxStore<E>? = null,
  retry: RetryPolicy? = null,
  onFailure: ((Throwable) -> Unit)? = null,
  exchange: suspend (payload: E, id: EntryId) -> R,
): OutboxSender<E> {
  requireReplies("exchangingSender")
  return OutboxSender(
    key = this,
    deliver = { payload, id -> Delivery.Replaced(exchange(payload, id)) },
    store = store,
    retry = retry,
    onFailure = onFailure,
  )
}

/**
 * The general sender, for a server that answers some sends with nothing and others with a value.
 * [deliver] returns [Delivery.Acknowledged] to have an optimistic store apply the change itself, or
 * [Delivery.Replaced] to replace the truth with the server's value. The key must come from
 * [OutboxKey.replying], and this throws for a plain key. Use [sender] or [exchangingSender] when
 * every send answers the same way.
 *
 * ```
 * @Provides @IntoSet
 * fun draftsSender(api: DraftsApi): OutboxSender<*> =
 *   DraftsOutbox.deliveringSender { change, id ->
 *     when (val saved = api.save(change, idempotencyKey = id.value)) {
 *       null -> Delivery.Acknowledged
 *       else -> Delivery.Replaced(saved)
 *     }
 *   }
 * ```
 *
 * [store], [retry] and [onFailure] work as in [sender].
 */
fun <E, R> OutboxKey<E, R>.deliveringSender(
  store: OutboxStore<E>? = null,
  retry: RetryPolicy? = null,
  onFailure: ((Throwable) -> Unit)? = null,
  deliver: suspend (payload: E, id: EntryId) -> Delivery<R>,
): OutboxSender<E> {
  requireReplies("deliveringSender")
  return OutboxSender(this, deliver, store, retry, onFailure)
}

/**
 * Throws unless this key has a reply serializer. A plain key fits a reply builder through its `R =
 * Nothing`, so only this check stops a send whose answer has no codec.
 */
private fun OutboxKey<*, *>.requireReplies(builder: String) {
  require(replySerializer != null) {
    "$builder needs a key from OutboxKey.replying, so the outbox can store the server's answer. " +
      "$name is a plain key. Build it with OutboxKey.replying, or use sender."
  }
}
