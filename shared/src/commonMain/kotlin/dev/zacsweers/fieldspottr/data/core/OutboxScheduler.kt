// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/**
 * The combined state of what the outboxes running from one factory still have to send, as
 * [DataScope.Factory.backlog] reports it. An outbox runs while a data scope created with its
 * [OutboxSender] lives. Failed entries do not count, since only [Outbox.retry] or [Outbox.discard]
 * moves them. Sent entries that wait for a handle's commit do not count either, since the server
 * has them.
 */
sealed interface OutboxBacklog {
  /** No running outbox has an entry waiting: every entry is sent, failed or discarded. */
  data object Empty : OutboxBacklog

  /** At least one entry waits. [Ready] and [BackingOff] say whether it can send now. */
  sealed interface Waiting : OutboxBacklog

  /**
   * At least one entry can be sent now or is being sent. Whether it actually goes out depends on
   * the network.
   */
  data object Ready : Waiting

  /**
   * Every waiting entry is backing off after a failed send. The first of them may send again at
   * [until].
   */
  data class BackingOff(val until: Instant) : Waiting
}

/**
 * A platform hook that learns when a factory's outboxes have entries to send, so the platform can
 * schedule a drain that runs even if the process dies, like a WorkManager request with a network
 * constraint. The factory calls [rowsWaiting] on each change in the combined [OutboxBacklog] of its
 * running outboxes. The call comes on a background dispatcher, or on the thread that creates a data
 * scope or ends one, so return quickly. After a restart the same state can arrive again. A call
 * that throws is reported, and the next report makes it again, also with the same state.
 *
 * The scheduler never hears that the backlog is empty. The factory knows only the outboxes that
 * run, so entries can still wait under a name whose sender no created data scope has yet.
 * Cancelling the drain would strand them, and a drain that finds nothing is cheap, so let it run.
 */
interface OutboxScheduler {
  /**
   * Called when at least one entry waits. [OutboxBacklog.Ready] means some entry can send now or is
   * sending. [OutboxBacklog.BackingOff] means every waiting entry is backing off, and a drain
   * before the deadline finds nothing to send.
   */
  fun rowsWaiting(backlog: OutboxBacklog.Waiting)

  /**
   * A scheduler that ignores every call. It is the default, and with it entries are sent only while
   * the app process is running.
   */
  object None : OutboxScheduler {
    override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {}
  }
}

/**
 * Suspends until no running outbox from this factory has an entry that can send now. It returns
 * [OutboxBacklog.Empty] once every entry is sent, or an [OutboxBacklog.BackingOff] whose deadline
 * is when the rest may retry, so a worker can schedule itself for that time.
 *
 * Only running outboxes count, so a worker first creates the data scope with the app's senders, if
 * the app has not yet. No repository is needed, since senders drain without handles. An entry whose
 * backoff deadline has passed counts as ready, so a worker that the deadline started waits for its
 * send. While the network is down, queued entries still count as ready and this keeps waiting, so
 * give the worker a network constraint and a timeout. To wait for one change, await its handle.
 */
suspend fun DataScope.Factory.awaitDrained(): OutboxBacklog = backlog.first {
  it !is OutboxBacklog.Ready
}
