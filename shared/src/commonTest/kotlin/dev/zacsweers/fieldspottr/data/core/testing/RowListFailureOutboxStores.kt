// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.InMemoryOutboxStores
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.KSerializer

/**
 * In-memory outbox stores whose row lists fail on demand, like a database that cannot read its
 * rows. While [failRowLists] is true, each row list throws: a new read throws before its first
 * list, and a read that already runs throws as soon as the flag is set. Every other call works.
 */
class RowListFailureOutboxStores : OutboxStores {
  /** The stores without the failures, to read what is stored now. */
  val delegate = InMemoryOutboxStores()

  // A state flow, so a row list that already runs sees the flag change
  private val failing = MutableStateFlow(false)

  /** Whether each row list throws, as the class describes. */
  var failRowLists: Boolean
    get() = failing.value
    set(value) {
      failing.value = value
    }

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    val rows = delegate.open(name, serializer)
    return object : OutboxStore<E> by rows {
      override fun entries(): Flow<List<OutboxEntry<E>>> =
        combine(rows.entries(), failing) { list, fail ->
          if (fail) {
            throw IllegalStateException("Disk error")
          }
          list
        }
    }
  }

  override suspend fun clearAll() = delegate.clearAll()
}
