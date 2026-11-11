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
 * A command replaces or cancels only predecessors in its own mode, and waits for the rest. This
 * happens on submit, so a replaced block never starts, even one dispatched first. Keys never wait
 * on each other. [Ordering.Serial] is one key of its own, and [Ordering.Concurrent] has none.
 *
 * A command settles once its block returns and every coroutine it started in its own context has
 * finished. If one of those fails, the command fails with it and the runner's failure handler hears
 * it. The runner's scope keeps running.
 *
 * Commands run on the dispatcher of the runner's scope, like `launch`. So build the runner on a
 * `Dispatchers.Default` scope. A Main scope, like Android's process lifecycle scope, would run
 * every command on the main thread. A command moves blocking work with `withContext`, as
 * [QueryStore] describes.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface ActionRunner {
  /** Runs [block] as one command with [ordering]. The handle follows that command. */
  fun run(ordering: Ordering = Ordering.Serial, block: suspend () -> Unit): Operation

  companion object
}

/**
 * Runs commands where each new one cancels the one before it, like a search field's queries. Every
 * command runs on one key with [OnPrevious.Cancel], so a queued command never starts and the next
 * one waits until a running one finishes its cleanup. Build one per field with
 * [newConflatedActionRunner], since commands on one runner cancel each other.
 */
fun interface ConflatedActionRunner {
  /** Runs [block] as one command that cancels the one before it. The handle follows it. */
  fun run(block: suspend () -> Unit): Operation
}

/**
 * A new [ConflatedActionRunner] in this data scope. Its commands cancel only each other, so one
 * runner never cancels or waits on another.
 */
fun DataScope.newConflatedActionRunner(): ConflatedActionRunner {
  val actions = newActionRunner()
  val ordering = Ordering.Keyed(Unit, OnPrevious.Cancel)
  return ConflatedActionRunner { block -> actions.run(ordering, block) }
}
