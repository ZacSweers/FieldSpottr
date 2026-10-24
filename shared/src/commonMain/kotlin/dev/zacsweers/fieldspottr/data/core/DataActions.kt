// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

/**
 * Runs commands with the ordering each one declares.
 *
 * ```
 * run(Keyed(k, mode)) { ... } --> Operation: Queued, Running, then Succeeded, Failed or Cancelled
 *
 * Commands on one key form a line, oldest first. A new command C joins behind A and B:
 *
 *   k:  [A, running] <-- [B, queued] <-- [C, new]
 *
 *   C is Wait       runs once A and B finish
 *   C is Supersede  drops B if B is Supersede too, then runs once A finishes
 *   C is Cancel     cancels A and B where they are Cancel too, then runs
 * ```
 *
 * A command replaces or cancels only predecessors of its own mode, and waits for the rest. Keys
 * never wait on each other. [Ordering.Serial] is one key of its own, and [Ordering.Concurrent] has
 * none.
 *
 * Commands run on the dispatcher of the runner's scope, the way `launch` runs on its scope's. So
 * build a runner on a scope on `Dispatchers.Default`, not on Main: a Main scope, like Android's
 * process lifecycle scope, would run every command on the main thread. Blocking work inside a
 * command moves itself with `withContext`, as [QueryStore] describes.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataActions {
  /** Runs [block] as one command with [ordering]. The handle follows that command. */
  fun run(ordering: Ordering = Ordering.Serial, block: suspend () -> Unit): Operation

  companion object
}
