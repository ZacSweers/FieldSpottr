// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.impl.inMemoryOutboxStore
import dev.zacsweers.fieldspottr.data.core.testing.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class InMemoryOutboxStoreTest : OutboxStoreContract() {
  private val time = TestTimeSource()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> =
    inMemoryOutboxStore(keepSettledFor, time)

  override fun advanceStoreTime(duration: Duration) {
    time += duration
  }
}

class DataStoreOutboxStoreTest : OutboxStoreContract() {
  private lateinit var clock: TestClock
  private val fileSystem = FakeFileSystem()

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
    clock = testClock(Instant.parse("2026-06-10T12:00:00Z"))
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    return DataStoreOutboxStore(
      dataStore = outboxDataStore(backgroundScope, { directory / "test.outbox" }, fileSystem),
      codec = String.serializer().asCodec(Json),
      keepSettledFor = keepSettledFor,
      clock = clock,
    )
  }

  override fun advanceStoreTime(duration: Duration) {
    clock.shift(duration)
  }
}
