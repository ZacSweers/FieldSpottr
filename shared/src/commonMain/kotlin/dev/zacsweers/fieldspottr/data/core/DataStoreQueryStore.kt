// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import androidx.datastore.core.DataStore

/**
 * A [QueryStore] over a DataStore that holds one fetched value. DataStore writes the file
 * atomically and allows one active instance per file, so build the DataStore once per process.
 */
fun <T> DataStore<Fetched<T>?>.asQueryStore(): QueryStore<T> =
  QueryStore.of(
    data,
    clear = { updateData { null } },
    invalidate = { updateData { it?.copy(fetchedAt = INVALIDATED) } },
  ) { value, fetchedAt ->
    updateData { Fetched(value, fetchedAt) }
  }
