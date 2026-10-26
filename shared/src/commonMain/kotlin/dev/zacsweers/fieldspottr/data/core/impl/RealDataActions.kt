// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataActions
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [DataActions] over a map from each key to its newest entry. Each entry links to the one ahead of
 * it, so a new command finds its turn by walking back along its key.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataActions(
  private val scope: CoroutineScope,
  private val onFailure: (Throwable) -> Unit,
) : DataActions {
  // Compare-and-set only. Nothing observes it.
  private val tails = AtomicReference(persistentHashMapOf<ChainKey, Entry>())

  /** How many keys still hold a command. For tests, which cannot see the key map any other way. */
  internal val keysHeld: Int
    get() = tails.load().size

  override fun run(ordering: Ordering, block: suspend () -> Unit): Operation =
    runOrJoin(ordering, tag = null, joins = { false }, block = block)

  /**
   * Returns the handle of an unsettled command on [ordering]'s key that [joins] accepts, looking
   * from the newest command back over the ones [passes] accepts. Otherwise runs [block] as a new
   * command tagged [tag]. One compare-and-set on the key's newest command decides, so a command
   * that joins the key later, like a query's invalidation, always lines up behind every handle
   * returned before it.
   */
  internal fun runOrJoin(
    ordering: Ordering,
    tag: Any?,
    joins: (Entry) -> Boolean,
    passes: (Entry) -> Boolean = { false },
    block: suspend () -> Unit,
  ): Operation {
    val key = ordering.chainKey
    var entry: Entry? = null
    // Serial is a key of its own. Concurrent has none.
    while (key != null) {
      val current = tails.load()
      val tail = current[key]
      val joined = tail?.let { joinable(it, joins, passes) }
      if (joined != null) {
        // Never started, so dropping it runs nothing
        entry?.job?.cancel()
        return joined.handle
      }
      val next = entry ?: newEntry(ordering, tag, block).also { entry = it }
      // Written before the entry is published, so a newer command that finds it can walk past it
      next.predecessor = tail
      if (tails.compareAndSet(current, current.putting(key, next))) {
        break
      }
    }
    return startLinked(entry ?: newEntry(ordering, tag, block), key)
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

  /** Starts [entry] once it is linked on [key], or at once when it has no key. */
  private fun startLinked(entry: Entry, key: ChainKey?): Operation {
    // Registered once linked, so it also runs for a job that ended before then, like one whose
    // scope was already cancelled
    if (key != null) {
      entry.settled.invokeOnCompletion {
        tails.update { m ->
          if (m[key] === entry) {
            m.removing(key)
          } else {
            m
          }
        }
      }
    }
    claimPredecessors(entry)
    entry.job?.start()
    return entry.handle
  }

  /** An entry whose job waits to be started, so nothing runs until it is linked on its key. */
  private fun newEntry(ordering: Ordering, tag: Any?, block: suspend () -> Unit): Entry {
    val entry = Entry(ordering.mode, tag, this, ordering.chainKey)
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
          block()
          // Cancelled while it ran: settles Cancelled even when the block returned normally
          ensureActive()
          entry.state.value = OperationState.Succeeded
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
      // The job reaches the block and everything it captured, and the key can be as large, so a
      // kept handle must keep neither. Only the wait guard reads the key, which a settled handle
      // skips.
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
   * Walks back from the immediate predecessor to the first entry that started. That entry only got
   * there once everything ahead of it was done.
   */
  private suspend fun waitForTurn(entry: Entry) {
    val mode = entry.mode
    // Same-mode entries removed from the line, joined last
    val cancelled = mutableListOf<Entry>()
    // Cancel only: other-mode entries met on the walk
    val survivors = mutableListOf<Entry>()
    // Supersede only replaces the run right behind it
    var superseding = mode == OnPrevious.Supersede
    var cursor = entry.predecessor
    while (cursor != null) {
      val target = cursor
      // The boundary
      if (target.claim.load() == Claim.Started) {
        when {
          mode != OnPrevious.Cancel -> target.settled.join()
          target.mode == OnPrevious.Cancel -> {
            target.job?.cancel()
            cancelled += target
          }
          else -> survivors += target
        }
        break
      }
      // A superseded entry never runs, usually replaced when a newer entry joined the key
      if (target.claim.load() == Claim.Superseded) {
        cancelled += target
        cursor = target.predecessor
        continue
      }
      // Queued and undecided: one branch per case
      when (mode) {
        OnPrevious.Cancel if target.mode == OnPrevious.Cancel -> {
          // Keep its block from starting
          target.claim.compareAndSet(Claim.Undecided, Claim.Superseded)
          target.job?.cancel()
          cancelled += target
        }
        OnPrevious.Cancel -> survivors += target
        // The CAS is tested last, only when the rest hold
        OnPrevious.Supersede if
          superseding &&
            target.mode == OnPrevious.Supersede &&
            target.claim.compareAndSet(Claim.Undecided, Claim.Superseded)
         -> {
          target.job?.cancel()
          cancelled += target
        }
        // Wait, or the end of a run of Supersedes
        else -> {
          superseding = false
          target.settled.join()
          if (target.claim.load() == Claim.Started) {
            break
          }
        }
      }
      cursor = target.predecessor
    }
    for (s in survivors) {
      s.settled.join()
      if (s.claim.load() == Claim.Started) {
        break
      }
    }
    for (c in cancelled) c.settled.join()
  }
}

/**
 * One submitted command: its job, its handle state, its claim and its link to the entry ahead. It
 * keeps its runner and [tag], and its key until it settles, so a wait on its handle can be checked
 * and a query can tell what kind of request it is.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class Entry(
  val mode: OnPrevious,
  val tag: Any?,
  val runner: RealDataActions,
  chainKey: ChainKey?,
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
  // Written before the entry is published on its key. Cleared on start.
  @Volatile var predecessor: Entry? = null
  private val runnerHandle = RunnerOperation(this)

  val handle: Operation
    get() = runnerHandle

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
 * A handle from [DataActions]. Until its command settles it knows the command, so it can tell when
 * waiting on it from inside another command would never end.
 */
@OptIn(DelicateDataLayerApi::class)
private class RunnerOperation(entry: Entry) : Operation {
  override val state: StateFlow<OperationState> = entry.state.asStateFlow()
  // Completes once the command has finished, cancelled or not
  val settled: Job = entry.settled
  // Cleared once settled. Only the wait guard reads it before then.
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
 * Marks a coroutine as running [entry]'s command, so a wait on that command's key can be caught.
 */
private class Holding(val entry: Entry) : AbstractCoroutineContextElement(Holding) {
  companion object Key : CoroutineContext.Key<Holding>
}

/**
 * Fails at once when the caller is a command waiting on its own handle, or on a command that can
 * only run after it on the same key. Such a wait never ends. Waits across keys or across runners
 * pass, since only a full wait-for graph could tell which of those never end. A handle from
 * [Operation.all] awaits each part in a child coroutine, which keeps the caller's [Holding], so
 * each part runs this check itself.
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

/** A read-only handle over a state flow. The runner, queries and outboxes build theirs here. */
@OptIn(DelicateDataLayerApi::class)
internal fun Operation.Companion.of(source: StateFlow<OperationState>): Operation =
  object : Operation {
    override val state = source
  }

/**
 * Runs [handler] once this handle's command has finished, also when it was cancelled before it
 * started. [handler] must be quick and must not throw. Only handles from [DataActions] know when
 * their command finishes, so any other handle fails here.
 */
internal fun Operation.invokeOnSettled(handler: () -> Unit) {
  val settled = (this as? RunnerOperation)?.settled
  requireNotNull(settled) { "$this does not come from DataActions" }
  settled.invokeOnCompletion { handler() }
}

internal enum class Claim {
  Undecided,
  Started,
  Superseded,
}

/** The chain a command joins. Wrapping keeps [Ordering.Serial] from colliding with a caller key. */
internal sealed interface ChainKey {
  data object Serial : ChainKey

  data class Keyed(val key: Any) : ChainKey
}

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
