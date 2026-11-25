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
 *   C is Cancel     cancels A and B where they are Cancel too, then runs once A and B finish
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
  /**
   * Runs [block] as one command with [ordering]. The handle follows that command, and no holder of
   * it can cancel the command. The command still stops when the runner's scope stops, or when a
   * newer command replaces it through [OnPrevious.Cancel] or [OnPrevious.Supersede].
   *
   * [admission] says what the call does when a matching command has not settled. With
   * [Admission.Queue], the default, the command lines up as [ordering] says.
   *
   * [Admission.Join] lets callers that ask for the same work one after another share one run of it.
   * The call looks at the newest command on [ordering]'s key that has not settled. When that
   * command was run with an equal join key, the call starts nothing and returns that command's
   * handle, whatever [OnPrevious] it asked for. Any other command in that place ends the search, so
   * a join never crosses a command in between:
   * ```
   * k:  [read A, "fetch"] <-- [write B] <-- [read C, "fetch"]
   *
   *   C runs after B and reads again. Without B, C returns A's handle.
   * ```
   *
   * So put a read and the writes it must follow on one key. A caller that stops waiting on a shared
   * handle changes nothing for the others, since no holder can cancel the command. A command from
   * [runCancellable] is never joined. A join key needs a key to look on, so [Ordering.Concurrent]
   * with [Admission.Join] throws [IllegalArgumentException].
   *
   * [Admission.Reject] rejects the call while a command run with an equal reject key on this runner
   * has not settled, whatever the ordering of either. A rejected call starts nothing and leaves
   * every accepted command in its place. Its handle is already [OperationState.Failed] with a
   * [CommandRejectedException], and no failure handler hears of it. Commands that must never be
   * pending together share one reject key. The key is free again once its command settles,
   * cancelled or not.
   *
   * A join key is compared with `equals` to the newest command's join key alone. A reject key is
   * looked up by `hashCode` and `equals`, like a map key. So a call costs the same however many
   * commands wait.
   */
  fun run(
    ordering: Ordering = Ordering.Serial,
    admission: Admission = Admission.Queue,
    block: suspend () -> Unit,
  ): Operation

  /**
   * [run], with a handle that can also cancel the command, as [CancellableOperation.cancel]
   * describes. An owner that returns this handle to its callers lets them cancel the command.
   *
   * It takes [Admission.Queue] or [Admission.Reject] as [run] does. A rejected call's handle is
   * already Failed, and its `cancel` does nothing. It takes no [Admission.Join], since one holder's
   * cancel would stop the command for every caller that shares it.
   */
  fun runCancellable(
    ordering: Ordering = Ordering.Serial,
    admission: CancellableAdmission = Admission.Queue,
    block: suspend () -> Unit,
  ): CancellableOperation

  companion object
}

/**
 * What a call to [ActionRunner.run] or [ActionRunner.runCancellable] does when a matching command
 * has not settled, as [ActionRunner.run] describes.
 */
sealed interface Admission {
  /** Starts a new command, which lines up as its ordering says. The default. */
  data object Queue : CancellableAdmission

  /**
   * Returns the handle of the newest unsettled command on the ordering's key when that command was
   * run with an equal join [key]. Otherwise starts a new command. It needs an ordering with a key,
   * so [Ordering.Concurrent] with it throws [IllegalArgumentException].
   */
  data class Join(val key: Any) : Admission

  /**
   * Fails the call while a command run with an equal reject [key] on the same runner has not
   * settled, whatever the ordering of either. Otherwise starts a new command that holds the key
   * until it settles.
   */
  data class Reject(val key: Any) : CancellableAdmission
}

/**
 * The admissions [ActionRunner.runCancellable] takes. It leaves out [Admission.Join], since one
 * holder's cancel would stop a shared command for every caller that shares it.
 */
sealed interface CancellableAdmission : Admission

/**
 * Why a command never ran: when it was submitted, another command with an equal reject [key] had
 * not settled on its runner. It is the cause of the Failed handle that [ActionRunner.run] and
 * [ActionRunner.runCancellable] return for a call that [Admission.Reject] rejected, so a caller can
 * tell a rejection from a command that ran and failed.
 */
class CommandRejectedException(val key: Any) :
  IllegalStateException("A command with reject key $key has not settled yet")

/**
 * Runs commands where each new one cancels the one before it, like a search field's queries. Every
 * command runs on one key with [OnPrevious.Cancel], so a queued command never starts and the next
 * one waits until a running one finishes its cleanup. Build one per field with
 * [newConflatedActionRunner], since commands on one runner cancel each other.
 */
fun interface ConflatedActionRunner {
  /**
   * Runs [block] as one command that cancels the one before it. The handle can also cancel the
   * command, as [ActionRunner.runCancellable] describes.
   */
  fun runCancellable(block: suspend () -> Unit): CancellableOperation

  /**
   * [runCancellable], with a handle that no holder can cancel, as with [ActionRunner.run]. By
   * default it wraps the cancellable handle in a plain view of it.
   */
  fun run(block: suspend () -> Unit): Operation = runCancellable(block).withoutCancel()
}

/**
 * A new [ConflatedActionRunner] in this data scope. Its commands cancel only each other, so one
 * runner never cancels or waits on another. Failed commands go to [onFailure], or to the factory's
 * `onFailure` handler when it is null, as with [DataScope.newActionRunner].
 */
fun DataScope.newConflatedActionRunner(
  onFailure: ((Throwable) -> Unit)? = null
): ConflatedActionRunner {
  val actions = newActionRunner(onFailure)
  val ordering = Ordering.Keyed(Unit, OnPrevious.Cancel)
  return object : ConflatedActionRunner {
    override fun run(block: suspend () -> Unit): Operation = actions.run(ordering, block = block)

    override fun runCancellable(block: suspend () -> Unit): CancellableOperation =
      actions.runCancellable(ordering, block = block)
  }
}
