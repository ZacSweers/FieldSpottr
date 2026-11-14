// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxChange
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Inserts a Queued row on [key] with a new id and returns the id, as an outbox does before it
 * inserts. For tests that write rows straight into a store.
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun <E> OutboxStore<E>.insertNew(key: String, payload: E): EntryId {
  val id = EntryId(Uuid.random().toString())
  insert(id, key, payload)
  return id
}

/**
 * Marks row [id] Discarded straight in the store, as another writer does, like an optimistic
 * store's clear. Fails if the row is gone or the write loses a race.
 */
suspend fun <E> OutboxStore<E>.discardDirectly(id: EntryId) {
  move(id, OutboxStatus.Discarded)
}

/** Writes [status] to row [id] from the status it has. Fails if the row is gone or moved on. */
suspend fun <E> OutboxStore<E>.move(id: EntryId, status: OutboxStatus) {
  val row = checkNotNull(entry(id).first()) { "${id.value} is gone" }
  check(update(row.copy(status = status), expected = row.status)) {
    "${id.value} moved on before the write"
  }
}

/** Collects this store's [OutboxStore.changes] in [scope] into a channel that keeps every one. */
fun OutboxStore<*>.changesIn(scope: CoroutineScope): ReceiveChannel<OutboxChange> {
  val changes = Channel<OutboxChange>(Channel.UNLIMITED)
  scope.launch { changes().collect { changes.send(it) } }
  return changes
}

/**
 * Receives changes until they have named every row of [ids]. [OutboxChange.Unknown] covers every
 * row when [orUnknown] is true and fails otherwise, for writes a store can name.
 */
suspend fun ReceiveChannel<OutboxChange>.awaitNamed(ids: Set<EntryId>, orUnknown: Boolean) {
  val left = ids.toMutableSet()
  while (left.isNotEmpty()) {
    when (val change = receive()) {
      is OutboxChange.Rows -> left -= change.ids
      OutboxChange.Unknown -> {
        check(orUnknown) { "The store did not name ${left.map { it.value }}" }
        left.clear()
      }
    }
  }
}
