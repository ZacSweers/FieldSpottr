// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.enqueue
import dev.zacsweers.fieldspottr.data.core.impl.MAX_ERROR_CHARS
import dev.zacsweers.fieldspottr.data.core.sender
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Outbox rows under Android's database driver, which reads each row through a cursor window. A row
 * larger than the window cannot be read by any query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SqlDelightOutboxStoreCursorWindowTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun `a row with every text column at its limit reads back through every query`() =
    runBlocking<Unit> {
      val driver = AndroidSqliteDriver(FSDatabase.Schema.synchronous(), context, "outbox-limits.db")
      try {
        val store =
          SqlDelightOutboxStores(FSDatabase(driver).outboxRowQueries, Dispatchers.IO)
            .open("notes", String.serializer())
        // With the two JSON quotes and the one-byte key, the payload and key are exactly the cap
        val payload = "x".repeat(MAX_OUTBOX_TEXT_BYTES - 3)
        val reply = "y".repeat(MAX_OUTBOX_TEXT_BYTES)
        // The longest error text the outbox keeps, in three-byte chars
        val error = "€".repeat(MAX_ERROR_CHARS)
        val id = EntryId("full")
        store.insert(id, "k", payload)
        val inserted = checkNotNull(store.entry(id).first())
        store.update(
          inserted.copy(lastError = error, reply = reply),
          expected = OutboxStatus.Queued,
        )

        // Queued, so it is the key's head
        assertThat(store.heads().first().map { it.id }).isEqualTo(listOf(id))
        assertThat(store.head("k")?.id).isEqualTo(id)
        assertThat(store.entries().first().map { it.id }).isEqualTo(listOf(id))
        val queued = checkNotNull(store.entry(id).first())
        assertThat(queued.payload).isEqualTo(payload)
        assertThat(queued.reply).isEqualTo(reply)

        // Failed with its revert owed, so the cause is stored too
        assertThat(
            store.update(queued.copy(status = OutboxStatus.Failed(error)), expected = queued.status)
          )
          .isTrue()
        assertThat(store.owed("k").map { it.id }).isEqualTo(listOf(id))
        assertThat(store.entries().first().map { it.id }).isEqualTo(listOf(id))
        assertThat(store.entry(id).first()?.status).isNotNull().isInstanceOf<OutboxStatus.Failed>()
      } finally {
        driver.close()
      }
    }

  /** Why the cap exists. With no cap, no query can read a row larger than the window. */
  @Test
  fun `a row above the cursor window cannot be read`() =
    runBlocking<Unit> {
      val driver = AndroidSqliteDriver(FSDatabase.Schema.synchronous(), context, "outbox-window.db")
      try {
        val store =
          SqlDelightOutboxStores(
              FSDatabase(driver).outboxRowQueries,
              Dispatchers.IO,
              maxTextBytes = Int.MAX_VALUE,
            )
            .open("notes", String.serializer())
        store.insert(EntryId("big"), "k", "x".repeat(3_000_000))

        assertFailsWith<SQLiteBlobTooBigException> { store.heads().first() }
      } finally {
        driver.close()
      }
    }

  @Test
  fun `a payload too large to read back is refused at enqueue and another key still sends`() =
    runBlocking<Unit> {
      val driver =
        AndroidSqliteDriver(FSDatabase.Schema.synchronous(), context, "outbox-oversize.db")
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val stores = SqlDelightOutboxStores(FSDatabase(driver).outboxRowQueries, Dispatchers.IO)
        val sent = MutableStateFlow(listOf<String>())
        val failures = MutableStateFlow(listOf<Throwable>())
        val notes = OutboxKey<String>("notes")
        val outbox =
          DataScope.Factory(
              onFailure = { failure -> failures.update { it + failure } },
              stores = stores,
            )
            .create(scope, setOf(notes.sender { payload, _ -> sent.update { it + payload } }))
            .outbox(notes, onFailure = {})

        // Larger than the cursor window
        val big = outbox.enqueue("x".repeat(3_000_000), key = "k1")
        val small = outbox.enqueue("small", key = "k2")

        assertThat(withTimeout(20.seconds) { big.stored.await() })
          .isInstanceOf<OperationState.Failed>()
          .prop(OperationState.Failed::cause)
          .isInstanceOf<IllegalArgumentException>()
        assertThat(withTimeout(20.seconds) { small.await() }).isEqualTo(OperationState.Succeeded)
        assertThat(sent.value).isEqualTo(listOf("small"))
        // No drain met an unreadable row
        assertThat(failures.value).isEmpty()
      } finally {
        scope.cancel()
        driver.close()
      }
    }
}
