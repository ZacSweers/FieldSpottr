// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * Runs commands in [scope] with the ordering each one declares.
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
 * Commands run on [scope]'s dispatcher, the way `launch` runs on its scope's. So pass a scope on
 * `Dispatchers.Default`, not on Main: a Main scope, like Android's process lifecycle scope, would
 * run every command on the main thread. Blocking work inside a command moves itself with
 * `withContext`, as [QueryStore] describes.
 */
@OptIn(ExperimentalAtomicApi::class)
class DataActions(private val scope: CoroutineScope, private val onFailure: (Throwable) -> Unit) {
  // Compare-and-set only. Nothing observes it.
  private val tails = AtomicReference(persistentHashMapOf<ChainKey, Entry>())

  fun run(ordering: Ordering = Ordering.Serial, block: suspend () -> Unit): Operation =
    prepare(ordering, block).commit()

  /**
   * Phase one: the entry and its handle, unpublished. Nothing waits on it and nothing runs until
   * [Prepared.commit].
   */
  internal fun prepare(ordering: Ordering, block: suspend () -> Unit): Prepared =
    Prepared(ordering, block)

  internal inner class Prepared(
    private val ordering: Ordering,
    private val block: suspend () -> Unit,
  ) {
    private val entry = Entry(ordering.mode)
    val operation: Operation = RunnerOperation(entry, this@DataActions, ordering.chainKey)

    /**
     * Phase two: publish on the key and start. Call at most once. An uncommitted command is simply
     * dropped.
     */
    fun commit(): Operation {
      entry.job =
        scope.launch(Holding(entry, this@DataActions, ordering.chainKey), CoroutineStart.LAZY) {
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
            entry.state.value = OperationState.Succeeded
          } catch (t: Throwable) {
            // Our own cancellation rethrows here
            ensureActive()
            entry.state.value = OperationState.Failed(t)
            onFailure(t)
          }
        }
      // Covers every path that skips the body
      entry.job.invokeOnCompletion { cause ->
        entry.state.update { current ->
          when {
            current is OperationState.Terminal -> current
            cause == null -> OperationState.Succeeded
            cause is CancellationException -> OperationState.Cancelled
            else -> OperationState.Failed(cause)
          }
        }
        entry.settled.complete()
      }
      // Serial is a key of its own. Concurrent has none.
      ordering.chainKey?.let { key ->
        entry.predecessor = tails.fetchAndUpdate { it.putting(key, entry) }[key]
        entry.job.invokeOnCompletion {
          tails.update { m ->
            if (m[key] === entry) {
              m.removing(key)
            } else {
              m
            }
          }
        }
      }
      entry.published.complete(Unit)
      entry.job.start()
      return operation
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
    var cursor = entry.awaitPredecessor()
    while (cursor != null) {
      val target = cursor
      // The boundary
      if (target.claim.load() == Claim.Started) {
        when {
          mode != OnPrevious.Cancel -> target.job.join()
          target.mode == OnPrevious.Cancel -> {
            target.job.cancel()
            cancelled += target
          }
          else -> survivors += target
        }
        break
      }
      // A superseded entry never runs
      if (target.claim.load() == Claim.Superseded) {
        cursor = target.awaitPredecessor()
        continue
      }
      // Queued and undecided: one branch per case
      when (mode) {
        OnPrevious.Cancel if target.mode == OnPrevious.Cancel -> {
          // Keep its block from starting
          target.claim.compareAndSet(Claim.Undecided, Claim.Superseded)
          target.job.cancel()
          cancelled += target
        }
        OnPrevious.Cancel -> survivors += target
        // The CAS is tested last, only when the rest hold
        OnPrevious.Supersede if
          superseding &&
            target.mode == OnPrevious.Supersede &&
            target.claim.compareAndSet(Claim.Undecided, Claim.Superseded)
         -> {
          target.job.cancel()
          cancelled += target
        }
        // Wait, or the end of a run of Supersedes
        else -> {
          superseding = false
          target.job.join()
          if (target.claim.load() == Claim.Started) {
            break
          }
        }
      }
      cursor = target.awaitPredecessor()
    }
    for (s in survivors) {
      s.job.join()
      if (s.claim.load() == Claim.Started) {
        break
      }
    }
    for (c in cancelled) c.job.join()
  }
}

/** One submitted command: its job, its handle state, its claim and its link to the entry ahead. */
@OptIn(ExperimentalAtomicApi::class)
internal class Entry(val mode: OnPrevious) {
  lateinit var job: Job
  // Observed. The handle is a view over it.
  val state = MutableStateFlow<OperationState>(OperationState.Queued)
  // Completes after job does, cancelled or not. It exists from prepare on, so a handle handed out
  // before commit can still wait on it.
  val settled = Job()
  // One CAS decides. Nothing observes it.
  val claim = AtomicReference(Claim.Undecided)
  // Written before published completes. Cleared on start.
  var predecessor: Entry? = null
  // A successor may be dispatched before commit() returns
  val published = CompletableDeferred<Unit>()

  suspend fun awaitPredecessor(): Entry? {
    published.await()
    return predecessor
  }
}

/**
 * A handle from [DataActions]. It knows its command, so it can tell when the command's job
 * completes and when waiting on it from inside another command would never end.
 */
@OptIn(DelicateDataLayerApi::class)
private class RunnerOperation(val entry: Entry, val runner: DataActions, val chainKey: ChainKey?) :
  Operation {
  override val state: StateFlow<OperationState> = entry.state.asStateFlow()

  override suspend fun await(): OperationState.Terminal {
    requireNotWaitingOnItself()
    return super.await()
  }
}

/**
 * Marks a coroutine as running [entry]'s command, so a wait on that command's key can be caught.
 */
private class Holding(val entry: Entry, val runner: DataActions, val chainKey: ChainKey?) :
  AbstractCoroutineContextElement(Holding) {
  companion object Key : CoroutineContext.Key<Holding>
}

/**
 * Fails at once when the caller is a command and this handle's command can only run after it on the
 * same key, which includes the caller's own handle. Such a wait never ends. Waits across keys or
 * across runners pass, since only a full wait-for graph could tell which of those never end.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun Operation.requireNotWaitingOnItself() {
  when (this) {
    is RunnerOperation -> {
      val holding = currentCoroutineContext()[Holding] ?: return
      val sameKey = holding.runner === runner && chainKey != null && holding.chainKey == chainKey
      // While a command holds its key, anything not yet started on that key is queued behind it
      check(!sameKey || (entry !== holding.entry && entry.claim.load() != Claim.Undecided)) {
        "A command awaited a command queued behind it on the same key, which would never finish. " +
          "Await it after the command returns, or do not await it."
      }
    }
    is AllOperation -> parts.forEach { it.requireNotWaitingOnItself() }
    else -> Unit
  }
}

/**
 * Runs [handler] once this handle's command has finished, also when it was cancelled before it
 * started. [handler] must be quick and must not throw. Only handles from [DataActions] know when
 * their command finishes, so any other handle fails here.
 */
internal fun Operation.invokeOnSettled(handler: () -> Unit) {
  val settled = (this as? RunnerOperation)?.entry?.settled
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
