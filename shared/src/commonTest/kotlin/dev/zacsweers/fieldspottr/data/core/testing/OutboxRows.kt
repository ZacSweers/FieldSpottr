// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
