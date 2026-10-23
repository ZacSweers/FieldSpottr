// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.data.core.FakeClock
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStoreContract
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.metro.suspendLazy
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Rule

class SqlDelightOutboxStoreTest : OutboxStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  private val clock = FakeClock(Instant.parse("2026-06-10T12:00:00Z"))

  override fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String> {
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

  override fun advance(duration: Duration) {
    clock.now += duration
  }
}
