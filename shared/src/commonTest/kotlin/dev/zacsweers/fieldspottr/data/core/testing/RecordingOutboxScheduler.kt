// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.collections.immutable.persistentListOf

/**
 * An [OutboxScheduler] that records each call, in order. Pass it to [testDataScopeFactory]. Safe to
 * write from any thread, since outboxes report on their dispatcher.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecordingOutboxScheduler : OutboxScheduler {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val heard = AtomicReference(persistentListOf<OutboxBacklog.Waiting>())

  /** What the scheduler was told, oldest first. */
  val calls: List<OutboxBacklog.Waiting>
    get() = heard.load()

  override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {
    heard.update { it.adding(backlog) }
  }
}
