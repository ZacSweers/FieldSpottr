// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** The rules every [OutboxStore] keeps, so the outbox behaves the same over any of them. */
abstract class OutboxStoreContract {
  protected abstract fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String>

  /** Moves the store's notion of time forward. */
  protected abstract fun advance(duration: Duration)

  @Test
  fun `insert queues rows oldest first`() = runTest {
    val store = newStore(1.minutes)

    val a = store.insert("k", "a")
    val b = store.insert("k", "b")

    assertThat(a).isNotEqualTo(b)
    assertThat(store.entries().first().map { it.payload }).containsExactly("a", "b")
    assertThat(store.entry(a).first()?.status).isEqualTo(OutboxStatus.Queued)
  }

  @Test
  fun `update writes only from the expected status`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insert("k", "a")
    val row = checkNotNull(store.entry(id).first())

    assertThat(store.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued)).isTrue()
    assertThat(store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)).isFalse()
    assertThat(store.entry(id).first()?.status).isEqualTo(OutboxStatus.Sending)

    val failed =
      row.copy(
        status = OutboxStatus.Failed("boom"),
        attempts = 2,
        lastError = "boom",
        reverted = true,
      )
    assertThat(store.update(failed, OutboxStatus.Sending)).isTrue()
    assertThat(store.entry(id).first()).isEqualTo(failed)
  }

  @Test
  fun `concurrent inserts and updates all land`() = runTest {
    val store = newStore(1.minutes)

    // On other threads, the way an outbox drains several keys at once
    val ids =
      withContext(Dispatchers.Default) {
        List(20) { i -> async { store.insert("key$i", "payload$i") } }.awaitAll()
      }
    val rows = ids.map { checkNotNull(store.entry(it).first()) }
    val updated =
      withContext(Dispatchers.Default) {
        rows
          .map { row ->
            async { store.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued) }
          }
          .awaitAll()
      }

    assertThat(updated.all { it }).isTrue()
    val entries = store.entries().first()
    assertThat(entries.map { it.payload }.toSet()).isEqualTo(List(20) { "payload$it" }.toSet())
    assertThat(entries.all { it.status == OutboxStatus.Sending }).isTrue()
  }

  @Test
  fun `a settled row stays readable until it expires and a write prunes it`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insert("k", "a")
    val row = checkNotNull(store.entry(id).first())

    store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)
    assertThat(store.entries().first()).isEmpty()
    assertThat(store.entry(id).first()?.status).isEqualTo(OutboxStatus.Sent)

    advance(2.minutes)
    store.insert("k", "b")
    assertThat(store.entry(id).first()).isNull()
  }
}

class InMemoryOutboxStoreTest : OutboxStoreContract() {
  private val time = TestTimeSource()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> =
    inMemoryOutboxStore(keepSettledFor, time)

  override fun advance(duration: Duration) {
    time += duration
  }
}

class DataStoreOutboxStoreTest : OutboxStoreContract() {
  private val clock = FakeClock(Instant.parse("2026-06-10T12:00:00Z"))
  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    return DataStoreOutboxStore(
      dataStore = outboxDataStore(backgroundScope, { directory / "test.outbox" }, fileSystem),
      codec = String.serializer().asCodec(Json),
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advance(duration: Duration) {
    clock.now += duration
  }
}
