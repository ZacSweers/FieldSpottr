// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/**
 * The combined state of what the outboxes running from one factory still have to send, as
 * [DataScope.Factory.backlog] reports it. Failed rows do not count, since only [Outbox.retry] or
 * [Outbox.discard] moves them.
 */
sealed interface OutboxBacklog {
  /** No running outbox has a row waiting: every row is sent, failed or discarded. */
  data object Empty : OutboxBacklog

  /** At least one row waits. [Ready] and [BackingOff] say whether it can send now. */
  sealed interface Waiting : OutboxBacklog

  /**
   * At least one row can be sent now or is being sent. Whether it actually goes out depends on the
   * network.
   */
  data object Ready : Waiting

  /**
   * Every waiting row is backing off after a failed send. The first of them may send again at
   * [until].
   */
  data class BackingOff(val until: Instant) : Waiting
}

/**
 * A platform hook that learns when a factory's outboxes have rows to send, so the platform can
 * schedule a drain that runs even if the process dies, like a WorkManager request with a network
 * constraint. The factory calls [rowsWaiting] on each change in the combined [OutboxBacklog] of its
 * running outboxes. The call comes on a background dispatcher, so return quickly. After a restart
 * the same state can arrive again.
 *
 * The scheduler never hears that the backlog is empty. The factory knows only the outboxes that
 * run, so rows can still wait in the store of an outbox the app has not built yet. Cancelling the
 * drain would strand them, and a drain that finds nothing is cheap, so let it run.
 */
interface OutboxScheduler {
  /**
   * Called when at least one row waits. [OutboxBacklog.Ready] means some row can send now or is
   * sending. [OutboxBacklog.BackingOff] means every waiting row is backing off, and a drain before
   * the deadline finds nothing to send.
   */
  fun rowsWaiting(backlog: OutboxBacklog.Waiting)

  /**
   * A scheduler that ignores every call. It is the default, and with it rows are sent only while
   * the app process is running.
   */
  object None : OutboxScheduler {
    override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {}
  }
}

/**
 * Suspends until no running outbox from this factory has a row that can send now. It returns
 * [OutboxBacklog.Empty] once every row is sent, or an [OutboxBacklog.BackingOff] whose deadline is
 * when the rest may retry, so a worker can schedule itself for that time.
 *
 * Only running outboxes count, so a worker builds the repositories that own them first. A row whose
 * backoff deadline has passed counts as ready, so a worker that the deadline started waits for its
 * send. While the network is down, queued rows still count as ready and this keeps waiting, so give
 * the worker a network constraint and a timeout.
 */
suspend fun DataScope.Factory.awaitDrained(): OutboxBacklog = backlog.first {
  it !is OutboxBacklog.Ready
}
