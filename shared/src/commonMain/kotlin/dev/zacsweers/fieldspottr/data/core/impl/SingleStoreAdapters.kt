// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OutboxChange
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxOperation
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A single optimistic store's rows hold the change itself, so its sender gets the change. The store
// set behind it works on KeyedMutation rows, and these adapters show it the rows with a Unit key.

/** This row with its change as a [KeyedMutation] with a `Unit` key. */
private fun <M> OutboxEntry<M>.keyed(): OutboxEntry<KeyedMutation<Unit, M>> =
  withPayload(KeyedMutation(Unit, payload))

/** This row with the change of its [KeyedMutation] as the payload. */
private fun <M> OutboxEntry<KeyedMutation<Unit, M>>.unkeyed(): OutboxEntry<M> =
  withPayload(payload.mutation)

/** This row with [payload] in place of its own. */
private fun <E, P> OutboxEntry<E>.withPayload(payload: P): OutboxEntry<P> =
  OutboxEntry(
    id = id,
    key = key,
    payload = payload,
    status = status,
    attempts = attempts,
    lastError = lastError,
    reverted = reverted,
    retryAt = retryAt,
    committed = committed,
    sentAt = sentAt,
    reply = reply,
  )

/** These rows of changes as rows of [KeyedMutation]s with a `Unit` key. */
internal fun <M> OutboxStore<M>.keyedByUnit(): OutboxStore<KeyedMutation<Unit, M>> {
  val rows = this
  return object : OutboxStore<KeyedMutation<Unit, M>> {
    override suspend fun insert(id: EntryId, key: String, payload: KeyedMutation<Unit, M>) =
      rows.insert(id, key, payload.mutation)

    override suspend fun update(
      entry: OutboxEntry<KeyedMutation<Unit, M>>,
      expected: OutboxStatus,
    ): Boolean = rows.update(entry.unkeyed(), expected)

    override fun entries(): Flow<List<OutboxEntry<KeyedMutation<Unit, M>>>> =
      rows.entries().map { list -> list.map { it.keyed() } }

    override suspend fun owed(): List<OutboxEntry<KeyedMutation<Unit, M>>> =
      rows.owed().map { it.keyed() }

    override suspend fun owed(key: String): List<OutboxEntry<KeyedMutation<Unit, M>>> =
      rows.owed(key).map { it.keyed() }

    override fun entry(id: EntryId): Flow<OutboxEntry<KeyedMutation<Unit, M>>?> =
      rows.entry(id).map { it?.keyed() }

    override fun heads(): Flow<List<OutboxEntry<KeyedMutation<Unit, M>>>> =
      rows.heads().map { list -> list.map { it.keyed() } }

    override suspend fun head(key: String): OutboxEntry<KeyedMutation<Unit, M>>? =
      rows.head(key)?.keyed()

    // Row ids are the same either way
    override fun changes(): Flow<OutboxChange> = rows.changes()

    override fun onUndecodable(report: (id: EntryId, cause: Throwable) -> Unit): AutoCloseable =
      rows.onUndecodable(report)
  }
}

/** This outbox of changes, taking [KeyedMutation]s with a `Unit` key. */
internal fun <M> OwnedRows<M>.keyedByUnit(): OwnedRows<KeyedMutation<Unit, M>> {
  val outbox = this
  return object : OwnedRows<KeyedMutation<Unit, M>> {
    override fun enqueue(
      id: EntryId,
      payload: KeyedMutation<Unit, M>,
      key: String?,
    ): OutboxOperation = outbox.enqueue(id, payload.mutation, key)

    override fun retry(id: EntryId): Operation = outbox.retry(id)

    override fun discard(id: EntryId): Operation = outbox.discard(id)
  }
}

/** This owner of [KeyedMutation] rows with a `Unit` key, as an owner of the changes themselves. */
internal fun <M> RowOwner<KeyedMutation<Unit, M>>.forChange(): RowOwner<M> {
  val owner = this
  return object : RowOwner<M> {
    override suspend fun apply(payload: M, id: EntryId) =
      owner.apply(KeyedMutation(Unit, payload), id)

    override suspend fun revert(payload: M, id: EntryId) =
      owner.revert(KeyedMutation(Unit, payload), id)

    override suspend fun commit(
      entry: OutboxEntry<M>,
      delivery: Delivery<*>?,
      sentAt: Instant,
      expected: OutboxStatus,
    ) = owner.commit(entry.keyed(), delivery, sentAt, expected)

    override fun committed(id: EntryId) = owner.committed(id)
  }
}
