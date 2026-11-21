// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.ActionRunner
import dev.zacsweers.fieldspottr.data.core.CancellableOperation
import dev.zacsweers.fieldspottr.data.core.CommandRejectedException
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [ActionRunner] over a map from each key to its newest unsettled entry. Each entry links to the
 * one ahead of it, so a new command finds its turn by walking back along its key. A key stays in
 * the map until every command on it has settled.
 *
 * The same map holds each conflict key with the command that was accepted under it, until that
 * command settles. So one compare-and-set joins, rejects or links a new command, and a rejection
 * reads one map entry however many commands wait.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealActionRunner(
  private val scope: CoroutineScope,
  private val onFailure: (Throwable) -> Unit,
) : ActionRunner {
  // Each ordering key's newest unsettled entry, and each conflict key's accepted command. Updated
  // by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val slots = AtomicReference(persistentHashMapOf<Slot, Entry>())

  /**
   * How many ordering keys and conflict keys still hold a command. For tests, which cannot see the
   * map any other way.
   */
  internal val keysHeld: Int
    get() = slots.load().size

  /**
   * Whether no command on [ordering]'s key is queued, running or settling. A key leaves the map
   * only once every command on it settled, so a true answer means every command submitted on it
   * before this call has finished. [Ordering.Concurrent] has no key and is always idle.
   */
  internal fun isIdle(ordering: Ordering): Boolean {
    val key = ordering.chainKey ?: return true
    return slots.load()[key] == null
  }

  /**
   * A command tagged with its join key, which joins the newest command on its key when that one
   * carries an equal tag. A command without a join key has no tag, so nothing joins it, and that
   * includes every command from [runCancellable].
   */
  override fun run(
    ordering: Ordering,
    joinKey: Any?,
    conflictKey: Any?,
    block: suspend () -> Unit,
  ): Operation {
    require(joinKey == null || ordering.chainKey != null) {
      "A join key needs an ordering with a key, and Ordering.Concurrent has none"
    }
    val joins: (Entry) -> Boolean =
      if (joinKey == null) {
        { false }
      } else {
        { it.tag == joinKey }
      }
    val entry =
      submit(ordering, joinKey, joins, passes = { false }, cancellable = false, conflictKey, block)
    // A settled plain handle, so no cast on it reaches a cancel
    return entry?.handle ?: Operation.failed(CommandRejectedException(checkNotNull(conflictKey)))
  }

  // A new command that joins nothing, so its entry is always the cancellable one built here
  override fun runCancellable(
    ordering: Ordering,
    conflictKey: Any?,
    block: suspend () -> Unit,
  ): CancellableOperation {
    val entry =
      submit(
        ordering,
        tag = null,
        joins = { false },
        passes = { false },
        cancellable = true,
        conflictKey,
        block,
      ) ?: return RejectedOperation(checkNotNull(conflictKey))
    return checkNotNull(entry.cancellableHandle) {
      "A new command's entry has no cancellable handle"
    }
  }

  /**
   * Returns the handle of an unsettled command on [ordering]'s key that [joins] accepts, walking
   * back from the newest over the ones [passes] accepts. Otherwise runs [block] as a new command
   * tagged [tag]. One compare-and-set on the key's newest command decides, so a later command, like
   * a query's invalidation, lines up behind every handle returned before it. The handle cannot
   * cancel the command, so a handle that other callers may join, like a query's fetch, is safe to
   * share.
   */
  internal fun runOrJoin(
    ordering: Ordering,
    tag: Any?,
    joins: (Entry) -> Boolean,
    passes: (Entry) -> Boolean = { false },
    block: suspend () -> Unit,
  ): Operation {
    val entry = submit(ordering, tag, joins, passes, cancellable = false, conflictKey = null, block)
    return checkNotNull(entry) { "A command without a conflict key was rejected" }.handle
  }

  /**
   * The entry [runOrJoin] describes: the command it joined, or a new one it started. Only a new
   * entry built with [cancellable] true has a [Entry.cancellableHandle].
   *
   * Returns null when [conflictKey] rejects the call: a command accepted under an equal key has not
   * settled. The join is tried first, since a joined call adds no command. One compare-and-set
   * takes the conflict key and links the entry, so of two calls with one conflict key at most one
   * is accepted, and a rejected call never changes the map.
   */
  private fun submit(
    ordering: Ordering,
    tag: Any?,
    joins: (Entry) -> Boolean,
    passes: (Entry) -> Boolean,
    cancellable: Boolean,
    conflictKey: Any?,
    block: suspend () -> Unit,
  ): Entry? {
    val key = ordering.chainKey
    val conflict = conflictKey?.let(::ConflictSlot)
    var entry: Entry? = null
    // Serial is a key of its own. Concurrent has none, so it takes this path only for a conflict
    // key.
    while (key != null || conflict != null) {
      val current = slots.load()
      val tail = key?.let { current[it] }
      val joined = tail?.let { joinable(it, joins, passes) }
      if (joined != null) {
        // Never started, so dropping it runs nothing
        entry?.job?.cancel()
        return joined
      }
      // A holder whose handle settled may not have left the map yet, and its key is free already.
      // So a caller that awaits the handle and submits again is never rejected by the command it
      // just waited for.
      val holder = conflict?.let { current[it] }
      if (holder != null && holder.state.value !is OperationState.Terminal) {
        entry?.job?.cancel()
        return null
      }
      val next = entry ?: newEntry(ordering, tag, cancellable, block).also { entry = it }
      // Written before the entry is published, so a newer command that finds it can walk past it
      next.predecessor = tail
      var updated = current
      if (key != null) {
        updated = updated.putting(key, next)
      }
      if (conflict != null) {
        updated = updated.putting(conflict, next)
      }
      if (slots.compareAndSet(current, updated)) {
        break
      }
    }
    val started = entry ?: newEntry(ordering, tag, cancellable, block)
    startLinked(started, key, conflict)
    return started
  }

  /** The command [joins] accepts, looking back from [newest] over the ones [passes] accepts. */
  private fun joinable(
    newest: Entry,
    joins: (Entry) -> Boolean,
    passes: (Entry) -> Boolean,
  ): Entry? {
    var cursor: Entry? = newest
    while (cursor != null && cursor.isJoinable) {
      if (joins(cursor)) {
        return cursor
      }
      if (!passes(cursor)) {
        return null
      }
      cursor = cursor.predecessor
    }
    return null
  }

  /**
   * Starts [entry] once it is linked on [key] and holds [conflict], or at once when it has neither.
   */
  private fun startLinked(entry: Entry, key: ChainKey?, conflict: ConflictSlot?) {
    // Registered once linked, so it also runs for a job that ended before then, like one whose
    // scope was already cancelled
    if (key != null) {
      entry.settled.invokeOnCompletion { releaseSettledTail(key) }
    }
    if (conflict != null) {
      entry.settled.invokeOnCompletion { releaseConflict(conflict, entry) }
    }
    claimPredecessors(entry)
    entry.job?.start()
  }

  /**
   * Frees [conflict] once [holder] has settled, unless a newer command took the key in the
   * meantime, which a call may do as soon as the holder's handle has settled.
   */
  private fun releaseConflict(conflict: ConflictSlot, holder: Entry) {
    slots.update { current ->
      if (current[conflict] === holder) {
        current.removing(conflict)
      } else {
        current
      }
    }
  }

  /**
   * Runs each time a command on [key] settles. While the key's newest entry has settled, this
   * replaces it with the nearest entry behind it that has not settled, or removes the key when no
   * such entry is left. A newest command can settle first when it is cancelled while queued. The
   * key then stays held while an older command still runs, and the next command waits for it.
   *
   * Any settling command may do this work for the key, so the result does not depend on the order
   * the handlers run in. An entry chosen here may settle before the compare-and-set lands. Its own
   * call then ran too early to see it as the newest. So the loop reads the key again after each
   * write, and ends only on an unsettled newest entry or no entry. No settled entry then stays
   * reachable from [slots] as a key's newest.
   */
  private fun releaseSettledTail(key: ChainKey) {
    while (true) {
      val current = slots.load()
      val tail = current[key]
      if (tail == null || !tail.settled.isCompleted) {
        return
      }
      val next = nearestUnsettled(tail)
      val updated =
        if (next == null) {
          current.removing(key)
        } else {
          current.putting(key, next)
        }
      // A removed key has no entry left to check. In every other case the loop reads the key
      // again. After a lost race it sees the newer state. After a write it checks whether the
      // entry it chose settled in the meantime.
      if (slots.compareAndSet(current, updated) && next == null) {
        return
      }
    }
  }

  /**
   * The nearest entry behind [settled] that has not settled, or null when every entry ahead of it
   * has. An entry that settled without starting keeps its link, so the walk passes it. An entry
   * that started cleared its link. It only started once everything ahead of it settled, so the walk
   * may end there.
   */
  private fun nearestUnsettled(settled: Entry): Entry? {
    var cursor = settled.predecessor
    while (cursor != null && cursor.settled.isCompleted) {
      cursor = cursor.predecessor
    }
    return cursor
  }

  /** An entry whose job waits to be started, so nothing runs until it is linked on its key. */
  private fun newEntry(
    ordering: Ordering,
    tag: Any?,
    cancellable: Boolean,
    block: suspend () -> Unit,
  ): Entry {
    val entry = Entry(ordering.mode, tag, this, ordering.chainKey, cancellable)
    val job =
      scope.launch(Holding(entry), CoroutineStart.LAZY) {
        waitForTurn(entry)
        ensureActive()
        // Superseded while waiting
        if (!entry.claim.compareAndSet(Claim.Undecided, Claim.Started)) {
          entry.state.value = OperationState.Cancelled
          return@launch
        }
        // A started entry is a boundary, so drop the history
        entry.predecessor = null
        entry.state.value = OperationState.Running
        try {
          val finished = runScoped(block)
          // Cancelled while it ran: settles Cancelled even when the block returned normally
          ensureActive()
          entry.state.value =
            if (finished) {
              OperationState.Succeeded
            } else {
              OperationState.Cancelled
            }
        } catch (t: Throwable) {
          // Our own cancellation rethrows here
          ensureActive()
          entry.state.value = OperationState.Failed(t)
          onFailure(t)
        }
      }
    entry.job = job
    // Covers every path that skips the body
    job.invokeOnCompletion { cause ->
      entry.state.update { current ->
        when {
          current is OperationState.Terminal -> current
          cause == null -> OperationState.Succeeded
          cause is CancellationException -> OperationState.Cancelled
          else -> OperationState.Failed(cause)
        }
      }
      // A caller may keep the handle for a long time. The job references the block and everything
      // it captured, and the key can be just as large, so both are cleared here. Only
      // requireNotWaitingOnItself reads the key, and await() skips that check once settled.
      entry.job = null
      entry.chainKey = null
      // Runs the callbacks waiting on the settlement, like the key cleanup, first
      entry.settled.complete()
      entry.detachHandle()
    }
    return entry
  }

  /**
   * Replaces what [entry] replaces as soon as it joins its key, so a queued block it replaces never
   * starts, even one dispatched before it. Every entry links to the one ahead before it is
   * published, so this walk reaches each one it replaces before the newer command's submit returns.
   */
  private fun claimPredecessors(entry: Entry) {
    val mode = entry.mode
    if (mode == OnPrevious.Wait || entry.claim.load() != Claim.Undecided) {
      return
    }
    var cursor = entry.predecessor
    while (cursor != null) {
      val target = cursor
      val claim = target.claim.load()
      when {
        claim == Claim.Superseded -> Unit
        // Cancel walks past other modes. A run of Supersedes ends at the first one.
        target.mode != mode && claim == Claim.Undecided ->
          if (mode == OnPrevious.Supersede) {
            return
          }
        claim == Claim.Undecided && target.claim.compareAndSet(Claim.Undecided, Claim.Superseded) ->
          target.job?.cancel()
        // Started, perhaps just now: the boundary. Cancel stops a running one of its mode.
        else -> {
          if (mode == OnPrevious.Cancel && target.mode == OnPrevious.Cancel) {
            target.job?.cancel()
          }
          return
        }
      }
      cursor = target.predecessor
    }
  }

  /**
   * Waits for each entry ahead of [entry], newest first, until one that started has settled. That
   * entry only started once everything ahead of it was done. [claimPredecessors] already replaced
   * or cancelled what this command replaces, so this walk only waits. A Cancel command thus still
   * waits for the commands of other modes ahead of it, and for a cancelled one to finish its
   * cleanup.
   *
   * An entry that settles without starting never runs, so nothing needs it but its link to the
   * entries ahead of it. Once one settles, this command's own link moves past it. So while this
   * command waits for a long one, no settled entry stays reachable through it or through this
   * coroutine, however many commands were replaced or cancelled meanwhile. The link stops at the
   * entry this command waits for, which is still due to run or still running.
   */
  private suspend fun waitForTurn(entry: Entry) {
    var cursor = entry.predecessor
    while (cursor != null) {
      // Returns at once for a replaced or cancelled entry, since its block never starts
      cursor.settled.join()
      if (cursor.claim.load() == Claim.Started) {
        return
      }
      cursor = cursor.predecessor
      entry.predecessor = cursor
    }
  }
}

/**
 * Runs [block] in a scope of its own, so coroutines it starts finish before the command settles,
 * and one that fails fails the command instead of the runner's scope. Returns false when the block
 * cancelled that scope itself, which settles the command Cancelled without a report. A cancellation
 * from something the block called, like a timeout, is a failure like any other.
 *
 * When the block throws, this rethrows the same instance, since `coroutineScope` would rethrow a
 * copy when stack trace recovery is on. Its children are cancelled and waited for first. A child
 * whose cleanup throws something else adds it as suppressed, so the block's exception is the one
 * reported.
 */
private suspend fun runScoped(block: suspend () -> Unit): Boolean {
  var thrown: Throwable? = null
  try {
    coroutineScope {
      try {
        block()
      } catch (t: Throwable) {
        // A child that failed, or the block itself, cancelled this scope. coroutineScope then
        // throws the child's failure, or a cancellation.
        ensureActive()
        thrown = t
        // As coroutineScope does when its block throws. It waits for them below.
        coroutineContext.cancelChildren()
      }
    }
  } catch (t: Throwable) {
    // The command's own cancellation, which its caller settles
    currentCoroutineContext().ensureActive()
    val failure = thrown
    when {
      failure != null -> {
        if (t !== failure && t !is CancellationException) {
          failure.addSuppressed(t)
        }
        throw failure
      }
      t is CancellationException -> return false
      // A child that failed
      else -> throw t
    }
  }
  thrown?.let { throw it }
  return true
}

/**
 * One submitted command: its job, its handle state, its claim and its link to the entry ahead. It
 * keeps its runner and [tag], and its key until it settles, so a wait on its handle can be checked
 * and a later request can tell whether to join it. A query's tag says what kind of request the
 * command is, and a command from [ActionRunner.run] carries its join key.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class Entry(
  val mode: OnPrevious,
  val tag: Any?,
  val runner: RealActionRunner,
  chainKey: ChainKey?,
  cancellable: Boolean = false,
) {
  // Cleared once the job completes
  @Volatile var chainKey: ChainKey? = chainKey
  // Set before the entry is linked. Cleared once the job completes.
  @Volatile var job: Job? = null
  // Observed. The handle is a view over it.
  val state = MutableStateFlow<OperationState>(OperationState.Queued)
  // Completes after job does, cancelled or not, so waiting on it outlives the job reference
  val settled = Job()
  // One CAS decides. Nothing observes it.
  val claim = AtomicReference(Claim.Undecided)
  // Written before the entry is published on its key. Moved past entries ahead that settled
  // without starting while this one waits, and cleared on start. An entry that settles without
  // starting keeps it, so walks from newer entries still reach the ones ahead of it.
  @Volatile var predecessor: Entry? = null
  private val runnerHandle =
    if (cancellable) {
      CancellableRunnerOperation(this)
    } else {
      RunnerOperation(this)
    }

  val handle: Operation
    get() = runnerHandle

  /** The handle as a [CancellableOperation], or null for an entry built without one. */
  val cancellableHandle: CancellableOperation?
    get() = runnerHandle as? CancellableRunnerOperation

  /**
   * Cancels this command for [CancellableOperation.cancel]. A queued command is claimed as
   * superseded, so its block never starts even when its turn comes at the same moment, and the
   * commands behind it walk past it as they do past any superseded one. A running command has its
   * job cancelled, so it settles once its block has unwound. A settled command has no job left, so
   * nothing happens.
   */
  fun cancel() {
    if (claim.compareAndSet(Claim.Undecided, Claim.Superseded)) {
      job?.cancel()
      return
    }
    if (claim.load() == Claim.Started) {
      job?.cancel()
    }
  }

  /** Whether a request may still share this command's outcome. */
  val isJoinable: Boolean
    get() = claim.load() != Claim.Superseded && state.value !is OperationState.Terminal

  /** Whether the block has started, so it may already have read what it works on. */
  val hasStarted: Boolean
    get() = claim.load() == Claim.Started

  /**
   * Lets go of the handle once the command settled and the callbacks waiting on it ran, so a kept
   * handle keeps only its own result, never the runner or the commands around it.
   */
  fun detachHandle() {
    runnerHandle.entry = null
  }
}

/**
 * A handle from [ActionRunner]. Until its command settles it knows the command, so it can tell when
 * waiting on it from inside another command would never end.
 */
@OptIn(DelicateDataLayerApi::class)
private open class RunnerOperation(entry: Entry) : Operation {
  override val state: StateFlow<OperationState> = entry.state.asStateFlow()
  // Completes once the command has finished, cancelled or not
  val settled: Job = entry.settled
  // Cleared once the command settles. Until then only requireNotWaitingOnItself reads it.
  @Volatile var entry: Entry? = entry

  override suspend fun await(): OperationState.Terminal {
    // A settled command can no longer be waiting on anything
    (state.value as? OperationState.Terminal)?.let {
      return it
    }
    entry?.let { requireNotWaitingOnItself(it) }
    return super.await()
  }
}

/**
 * A handle from [ActionRunner.runCancellable], which can also cancel its command until the command
 * settles. It reads the entry once, so a call that races the settlement finds either the entry or
 * nothing.
 */
@OptIn(DelicateDataLayerApi::class)
private class CancellableRunnerOperation(entry: Entry) :
  RunnerOperation(entry), CancellableOperation {
  override fun cancel() {
    entry?.cancel()
  }
}

/**
 * Marks a coroutine as running [entry]'s command, so a wait on that command's key can be caught.
 */
private class Holding(val entry: Entry) : AbstractCoroutineContextElement(Holding) {
  companion object Key : CoroutineContext.Key<Holding>
}

/**
 * Fails at once when a command waits on its own handle, or on a command that can only run after it
 * on the same key, since such a wait never ends. Waits across keys or runners pass, since only a
 * full wait-for graph could tell which of those never end. [Operation.all] awaits each part in a
 * child coroutine that keeps the caller's [Holding], so each part runs this check too.
 */
@OptIn(ExperimentalAtomicApi::class)
private suspend fun requireNotWaitingOnItself(entry: Entry) {
  val holding = currentCoroutineContext()[Holding]?.entry ?: return
  // Any ordering, Concurrent included
  check(entry !== holding) {
    "A command awaited its own handle, which would never finish. Await it after the command " +
      "returns, or do not await it."
  }
  val sameKey =
    holding.runner === entry.runner && entry.chainKey != null && holding.chainKey == entry.chainKey
  // While a command holds its key, anything not yet started on that key is queued behind it
  check(!sameKey || entry.claim.load() != Claim.Undecided) {
    "A command awaited a command queued behind it on the same key, which would never finish. " +
      "Await it after the command returns, or do not await it."
  }
}

/**
 * The handle [ActionRunner.runCancellable] returns for a call that its conflict key rejected:
 * already failed, with nothing to cancel.
 */
@OptIn(DelicateDataLayerApi::class)
private class RejectedOperation(conflictKey: Any) : CancellableOperation {
  override val state: StateFlow<OperationState> =
    MutableStateFlow(OperationState.Failed(CommandRejectedException(conflictKey))).asStateFlow()

  override fun cancel() {}
}

/** A read-only handle over a state flow. The runner, queries and outboxes build theirs here. */
@OptIn(DelicateDataLayerApi::class)
internal fun Operation.Companion.of(source: StateFlow<OperationState>): Operation =
  object : Operation {
    override val state = source
  }

/**
 * Runs [handler] once this handle's command has finished, also when it was cancelled before it
 * started. [handler] must be quick and must not throw. Only handles from [ActionRunner] know when
 * their command finishes, so any other handle fails here.
 */
internal fun Operation.invokeOnSettled(handler: () -> Unit) {
  val settled = (this as? RunnerOperation)?.settled
  requireNotNull(settled) { "$this does not come from ActionRunner" }
  settled.invokeOnCompletion { handler() }
}

internal enum class Claim {
  Undecided,
  Started,
  Superseded,
}

/** What the runner's map is keyed by: an ordering key's chain, or a conflict key. */
internal sealed interface Slot

/** The chain a command joins. Wrapping keeps [Ordering.Serial] from colliding with a caller key. */
internal sealed interface ChainKey : Slot {
  data object Serial : ChainKey

  data class Keyed(val key: Any) : ChainKey
}

/** A caller's conflict key. Wrapping keeps it apart from an ordering key that equals it. */
private data class ConflictSlot(val key: Any) : Slot

@OptIn(DelicateDataLayerApi::class)
internal val Ordering.chainKey: ChainKey?
  get() =
    when (this) {
      Ordering.Serial -> ChainKey.Serial
      is Ordering.Keyed -> ChainKey.Keyed(key)
      // No chain, starts at once
      Ordering.Concurrent -> null
    }

internal val Ordering.mode: OnPrevious
  get() =
    if (this is Ordering.Keyed) {
      onPrevious
    } else {
      OnPrevious.Wait
    }
