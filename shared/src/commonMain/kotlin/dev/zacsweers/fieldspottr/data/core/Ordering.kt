// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

/**
 * How a write lines up with the other writes of its receiver: a runner's commands, an outbox's
 * entries, or one optimistic value's changes. Writes on one line run one at a time, in order, and
 * lines never wait on each other.
 *
 * [Serial] puts the write on the receiver's default line. For a runner and an outbox that is one
 * line for all its writes, and for an [OptimisticQueryStores] set it is the line of the change's
 * key. [Keyed] puts it on the line of its key, which in a set is within the change's key. An entry
 * takes a `String` key with [OnPrevious.Wait] only. [Concurrent] puts the write on a line of its
 * own.
 */
sealed interface Ordering {
  /** One at a time, in call order. The default for writes. */
  data object Serial : Ordering

  /**
   * One at a time per [key], while other keys run alongside. Use an entity's id, so its writes keep
   * their order and never wait on another entity's. [onPrevious] says what this command does to
   * commands on the key that chose the same mode and have not finished.
   */
  data class Keyed(val key: Any, val onPrevious: OnPrevious = OnPrevious.Wait) : Ordering

  /**
   * No ordering: the command runs at once, beside every other command, and nothing waits for it.
   * Opt in only for a command that touches nothing another command touches.
   */
  @DelicateDataLayerApi data object Concurrent : Ordering
}

/** What a new command does to predecessors on its key that declared the same mode. */
enum class OnPrevious {
  /**
   * Queues behind them, so commands on one key run one at a time while other keys run alongside.
   */
  Wait,
  /**
   * Replaces the ones that have not started yet and waits for one that has, so only the newest
   * queued command runs.
   */
  Supersede,
  /**
   * Cancels them whether they are queued or running. Suits a refresh or a search, where only the
   * newest request matters.
   */
  Cancel,
}
