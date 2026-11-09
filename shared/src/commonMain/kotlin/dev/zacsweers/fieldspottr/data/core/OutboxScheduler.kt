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
 * A platform hook that learns when the outboxes running from one factory have rows to send, so the
 * platform can schedule a drain that runs even if the process dies first, like a WorkManager
 * request with a network constraint. The factory calls [rowsWaiting] once per change in the
 * combined [OutboxBacklog] of the outboxes running at the time. The call comes from the outbox that
 * changed, on a background dispatcher, so an implementation should return quickly, and it must
 * tolerate hearing the same state again after a restart.
 *
 * The scheduler is never told that the backlog is empty. The factory only knows about outboxes that
 * are running, so an empty backlog can still leave rows in the store of an outbox the app has not
 * built yet, and a drain cancelled because of it would strand them. A drain that runs and finds
 * nothing to send is cheap, so let it run.
 */
interface OutboxScheduler {
  /**
   * Called when at least one row is waiting to be sent. [OutboxBacklog.Ready] means some row can be
   * sent right now or is being sent. [OutboxBacklog.BackingOff] means every waiting row is backing
   * off after a failed send and the earliest of them may be retried at its deadline, so a drain
   * that runs before then will find nothing to send.
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
 * Suspends until no outbox running from this factory has a row that could be sent right now. It
 * returns [OutboxBacklog.Empty] once every row has been sent, or an [OutboxBacklog.BackingOff]
 * whose deadline is when the remaining rows may be retried, so a worker can schedule itself again
 * for that time. Only running outboxes count, so a worker should build the repositories that own
 * them before calling this. A row whose backoff deadline has passed counts as ready, so a worker
 * that the deadline started waits for its send. While the network is down, queued rows still count
 * as ready and this keeps waiting, so give the worker a network constraint and a timeout.
 */
suspend fun DataScope.Factory.awaitDrained(): OutboxBacklog = backlog.first {
  it !is OutboxBacklog.Ready
}
