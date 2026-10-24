// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.PayloadCodec
import dev.zacsweers.fieldspottr.data.core.TaggedFormat
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.data.core.testing.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.metro.suspendLazy
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Rule

class SqlDelightOutboxStoreTest : OutboxStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  private lateinit var clock: TestClock

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    return SqlDelightOutboxStore(
      db = suspendLazy { temporaryDatabase.db() },
      outbox = "test",
      codec = String.serializer().asCodec(Json),
      // Real threads, so the concurrency test reaches the driver
      io = Dispatchers.IO,
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advanceStoreTime(duration: Duration) {
    clock.shift(duration)
  }

  @Test
  fun `the store set encodes payloads in its format`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    val store =
      SqlDelightOutboxStores(db, Dispatchers.IO, payloadFormat = TaggedFormat)
        .open("notes", String.serializer())

    store.insert("k", "hello")

    assertThat(store.entries().first().single().payload).isEqualTo("hello")
    // A second store over the same rows that returns each payload as stored
    val stored =
      object : PayloadCodec<String> {
        override fun encode(payload: String) = payload

        override fun decode(encoded: String) = encoded
      }
    val raw = SqlDelightOutboxStore(db, "notes", stored, Dispatchers.IO)
    assertThat(raw.entries().first().single().payload).isEqualTo("tagged:\"hello\"")
  }
}
