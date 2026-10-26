// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

  @Test
  fun `a status a newer version wrote reads as failed and can be requeued`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    val store =
      SqlDelightOutboxStore(db, "future", String.serializer().asCodec(Json), Dispatchers.IO)
    val id = store.insert("k", "a")
    // As a newer version would write it
    db
      .await()
      .outboxRowQueries
      .compareAndSet(
        status = "archived",
        cause = null,
        attempts = 0,
        lastError = null,
        reverted = 0,
        retryAt = null,
        settledAt = null,
        outbox = "future",
        id = id.value,
        expectedStatus = "queued",
        expectedCause = null,
      )
    val row = checkNotNull(store.entry(id).first())

    assertThat(row.status).isInstanceOf<OutboxStatus.Failed>()
    assertThat(store.update(row.copy(status = OutboxStatus.Queued), expected = row.status)).isTrue()
    assertThat(store.entry(id).first()?.status).isEqualTo(OutboxStatus.Queued)
  }

  @Test
  fun `an insert whose caller is cancelled during the write returns the id`() = runTest {
    val db = suspendLazy { temporaryDatabase.db() }
    lateinit var caller: Job
    // Cancels the caller from inside the transaction, which still commits
    val cancelling =
      object : PayloadCodec<String> {
        override fun encode(payload: String) = payload.also { caller.cancel() }

        override fun decode(encoded: String) = encoded
      }
    val store = SqlDelightOutboxStore(db, "cancel", cancelling, Dispatchers.IO)
    var inserted: Result<EntryId>? = null

    caller = launch { inserted = runCatching { store.insert("k", "a") } }
    caller.join()

    val rows = store.entries().first()
    assertThat(rows).hasSize(1)
    assertThat(inserted?.getOrNull()).isEqualTo(rows.single().id)
  }
}
