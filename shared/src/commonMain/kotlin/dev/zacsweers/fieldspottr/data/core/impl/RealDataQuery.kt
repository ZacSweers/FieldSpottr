// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. Its
 * watcher runs in [scope] while anyone collects [data]. [holds] keeps whatever owns this query
 * alive, such as the per-key entry inside a keyed query, for as long as the watcher or a request
 * still needs it.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataQuery<T>(
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock,
  private val scope: CoroutineScope,
  /** Hears each failed fetch. */
  private val onFetchFailure: (Throwable) -> Unit,
  /**
   * Hears every other failure, like an invalidate() whose store throws, a store whose data fails
   * while the watcher collects it, or a [retry] policy that throws.
   */
  private val onFailure: (Throwable) -> Unit,
  /** The network state. A change from offline to online makes a collected query check again. */
  private val online: StateFlow<Boolean> = MutableStateFlow(true),
  /** How long automatic fetches hold off after failures, as [holdOffWindow] describes. */
  private val retry: RetryPolicy,
  /** When automatic fetches may run again after failures, as [DataQuery.data] describes. */
  private val holdOff: FetchHoldOff = FetchHoldOff.inMemory(),
  private val holds: QueryHolds = QueryHolds.None,
) : DataQuery<T> {
  private val actions = RealActionRunner(scope, ::report)
  // Private, so only this query's commands chain on it
  private val key = Any()
  private val ordering = Ordering.Keyed(key)
  // Counts the commands this query submits, so a collector can tell whether one was submitted while
  // it read the store. Only ever compared for equality, so wrapping is harmless.
  private val submissions = AtomicInt(0)
  // Orders invalidation requests, forced requests, takeovers and fetch starts, since clock times
  // can tie. Each takes the next ticket.
  private val tickets = AtomicInt(0)
  // The ticket of the newest invalidate() request. Only moves forward.
  private val lastInvalidation = AtomicInt(0)
  // The start ticket of the newest fetch that wrote. Only moves forward.
  private val lastWrite = AtomicInt(0)
  // How many observers collect data, and the watcher that runs while any do. Updated by
  // compare-and-set only.
  private val watching = AtomicReference(Watching(observers = 0, watcher = null))

  /**
   * The store's values, from one collection of the store per observer. Each observer checks the
   * first value it gets, since the data can go stale between observers, as [checkOnSubscribe]
   * describes. The check is a command of its own, so an observer that leaves after one value, like
   * `first()`, still started it. The first observer then starts the watcher, which can wait for
   * that check, as [watch] describes.
   */
  override val data: Flow<Fetched<T>?> = flow {
    // Read before the store, so a quiet key at both ends means no command ran around the read
    val submitted = submissions.load()
    val idle = actions.isIdle(ordering)
    var counted = false
    try {
      store.data.collect { current ->
        if (!counted) {
          counted = true
          val quiet = idle && submissions.load() == submitted
          val check = checkOnSubscribe(current, quiet)
          if (check != null) {
            holds.holdUntilSettled(check)
          }
          startWatching(check, current)
        }
        emit(current)
      }
    } finally {
      if (counted) {
        stopWatching()
      }
    }
  }

  override val stored: Flow<Fetched<T>?> = store.data

  /**
   * Starts a check for an observer whose first value is [current], or returns null when none is
   * needed. When the key was [quiet], no command was queued, running or submitted from before the
   * observer's read until now, so [current] already shows every write this query made. A fresh
   * value then needs no check and no second read. Otherwise the observer joins or queues a check
   * that reads the store again when its turn comes.
   */
  private fun checkOnSubscribe(current: Fetched<T>?, quiet: Boolean): Operation? =
    if (quiet && !isStale(current?.fetchedAt)) {
      null
    } else {
      autoCheck()
    }

  /**
   * Counts an observer in, and starts the watcher for the first one, whose check is [check] and
   * whose first value was [seen].
   */
  private fun startWatching(check: Operation?, seen: Fetched<T>?) {
    while (true) {
      val current = watching.load()
      if (current.observers > 0) {
        if (watching.compareAndSet(current, Watching(current.observers + 1, current.watcher))) {
          return
        }
        continue
      }
      // The job takes the block itself, which can drop check, as [watch] describes
      val watcher = scope.launch(start = CoroutineStart.LAZY, block = watch(check, seen))
      if (watching.compareAndSet(current, Watching(observers = 1, watcher))) {
        // The watcher takes a hold on the owner, which also covers the requests the watcher makes
        if (holds.tryAcquire()) {
          watcher.invokeOnCompletion { holds.release() }
          watcher.start()
        } else {
          watcher.cancel()
        }
        return
      }
      watcher.cancel()
    }
  }

  /** Counts an observer out, and stops the watcher after the last one. */
  private fun stopWatching() {
    while (true) {
      val current = watching.load()
      val last = current.observers == 1
      val next =
        if (last) {
          Watching(observers = 0, watcher = null)
        } else {
          Watching(current.observers - 1, current.watcher)
        }
      if (watching.compareAndSet(current, next)) {
        if (last) {
          current.watcher?.cancel()
        }
        return
      }
    }
  }

  /**
   * One ordered view of the store for every observer, so each change starts one check however many
   * observers see it. A value that goes away, or keeps only local changes with
   * [QueryStore.UNFETCHED], is checked again after the work already queued, so a clear during a
   * fetch's write gets a check after that write. An invalidated value keeps its data and waits for
   * the next collect, as [invalidate] promises. The watcher also checks again on each reconnect, as
   * [watchNetwork] describes.
   *
   * The first observer saw [seen] and made [check], or no check for fresh data. Without a check,
   * the watcher's first value counts as a change from [seen]. With one, the first value needs no
   * check of its own unless that value is nothing stored. [check] may be one the observer joined,
   * which read a value before a clear and so never fetched. So the watcher waits for it, and checks
   * once more if it succeeded and the store is still empty. A failed check, like a fetch while
   * offline, already made its attempt, so the watcher leaves it.
   *
   * A store whose data fails is reported to [onFailure] and collected again after a pause that
   * doubles from [STORE_RETRY_FIRST] up to [STORE_RETRY_MAX], so the failure never reaches the
   * scope's exception handler. A collection that stays up for as long as the current pause resets
   * it, so a store that fails right after each value still backs off.
   *
   * Returns the watcher's block, which drops [check] after the first value, since a failed check
   * keeps its error. Pass the block to `launch` as is, since a lambda that calls this would keep
   * [check].
   */
  private fun watch(check: Operation?, seen: Fetched<T>?): suspend CoroutineScope.() -> Unit {
    // Read for the first value only, then cleared
    var firstCheck: Operation? = check
    return watcher@{
      launch { watchNetwork() }
      var previous: Fetched<T>? = seen
      var pause = STORE_RETRY_FIRST
      while (true) {
        // Resets the pause once this collection has stayed up for as long as the pause, so a store
        // that fails right after each value still backs off
        val settled = launch {
          delay(pause)
          pause = STORE_RETRY_FIRST
        }
        val collected = runCatchingCancellable {
          store.data.collect { current ->
            val initial = firstCheck
            if (initial != null) {
              firstCheck = null
              val emptyAfterCheck =
                current == null &&
                  initial.await() == OperationState.Succeeded &&
                  store.data.first() == null
              if (emptyAfterCheck) {
                holds.holdUntilSettled(recheck())
              }
            } else if (lostFetch(previous, current)) {
              holds.holdUntilSettled(recheck())
            }
            previous = current
          }
        }
        // Joined, so the pause it may have reset is visible here
        settled.cancelAndJoin()
        // A store's data never completes. One that does has nothing more to show.
        val failure = collected.exceptionOrNull() ?: return@watcher
        firstCheck = null
        onFailure(failure)
        delay(pause)
        pause = minOf(pause * 2, STORE_RETRY_MAX)
      }
    }
  }

  /**
   * Checks again each time [online] turns from false to true. The check fetches when the data is
   * stale or the last fetch failed, whatever the hold-off says, so a screen recovers from an
   * offline failure as soon as the network returns.
   */
  private suspend fun watchNetwork() {
    var wasOnline: Boolean? = null
    online.collect { up ->
      if (up && wasOnline == false) {
        holds.holdUntilSettled(reconnectCheck())
      }
      wasOnline = up
    }
  }

  /** Whether [current] has lost the fetched data that [previous] showed. */
  private fun lostFetch(previous: Fetched<T>?, current: Fetched<T>?): Boolean {
    if (previous == null) {
      return false
    }
    if (current == null) {
      return true
    }
    return current.fetchedAt == QueryStore.UNFETCHED && previous.fetchedAt != QueryStore.UNFETCHED
  }

  override val status: StateFlow<FetchStatus>
    field = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

  override fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /**
   * Joins a forced fetch that has not settled, even behind checks queued after it. Else takes over
   * a started check that has not written yet, which cancels that check's unforced fetch, if any,
   * and runs a forced fetch in its place. The check's handle then follows that forced fetch, so its
   * callers never see it cancelled. Else fetches now. See [requestFetch].
   *
   * The search walks past queued invalidations too. A takeover across one is safe, since its forced
   * fetch starts after the invalidate() call, and the invalidation then skips its mark, as
   * [invalidate] describes. A forced request never joins a forced request or takeover made before
   * the newest invalidate() call, so a later request never joins a fetch from before the data
   * changed.
   */
  private fun fetchNow(): Operation {
    submissions.incrementAndFetch()
    val forced = ForcedFetch(tickets.incrementAndFetch())
    return actions.runOrJoin(
      Ordering.Keyed(key, OnPrevious.Cancel),
      forced,
      joins = { entry ->
        when (val tag = entry.tag) {
          is ForcedFetch -> tag.requested > lastInvalidation.load()
          is Check ->
            entry.hasStarted &&
              tag.joinForced(lastInvalidation.load()) { tickets.incrementAndFetch() }
          else -> false
        }
      },
      passes = { it.tag is Check || it.tag === Invalidation },
    ) {
      settlingStatus { fetchOnce(forced = true) }
    }
  }

  /**
   * Runs a command's [block] and makes sure it never leaves [status] on `Fetching`, whatever way it
   * exits, its own cancellation included. Most exits already set `Idle` or `Failed`. This covers
   * the rest, like a takeover that its owner's cancellation reached before the check could set its
   * outcome. No other command on this query's key runs until this one has settled, and the block's
   * child coroutines have finished by the time it returns or throws, so nothing else writes
   * [status] meanwhile.
   */
  private suspend fun settlingStatus(block: suspend () -> Unit) {
    try {
      block()
    } finally {
      if (status.value is FetchStatus.Fetching) {
        status.value = FetchStatus.Idle
      }
    }
  }

  /**
   * Joins the newest command on this query's key when it is a forced fetch, a check that does not
   * hold off, or a check that is fetching. Else checks the stored time and fetches if stale. A
   * check that may still hold off is not joined, since an explicit request never holds off.
   */
  private fun fetchIfStale(): Operation {
    submissions.incrementAndFetch()
    val check = Check(CheckKind.Requested)
    return actions.runOrJoin(
      ordering,
      check,
      joins = { it.tag is ForcedFetch || (it.tag as? Check)?.servesRequest == true },
    ) {
      runCheck(check)
    }
  }

  /**
   * A collector's check. Single flight: joins the forced fetch or check that is the newest command
   * on this query's key, else checks the stored time and fetches if stale and not held off.
   */
  private fun autoCheck(): Operation {
    submissions.incrementAndFetch()
    val check = Check(CheckKind.Automatic)
    return actions.runOrJoin(ordering, check, joins = { it.tag is Request }) { runCheck(check) }
  }

  /**
   * Checks the stored time once the work already queued is done. It joins only a check that has not
   * started, which reads the store later anyway, and never work that may have read it already.
   */
  private fun recheck(): Operation {
    submissions.incrementAndFetch()
    val check = Check(CheckKind.Automatic)
    return actions.runOrJoin(
      ordering,
      check,
      joins = { it.tag is Check && !it.hasStarted },
    ) {
      runCheck(check)
    }
  }

  /** A check of its own after a reconnect, which never joins one that may skip a failed fetch. */
  private fun reconnectCheck(): Operation {
    submissions.incrementAndFetch()
    val check = Check(CheckKind.Reconnected)
    return actions.runOrJoin(ordering, check, joins = { false }) { runCheck(check) }
  }

  /**
   * The newest command on the key from here on, so a later request waits for it and checks the
   * store again instead of joining a fetch that started before the data changed.
   *
   * When its turn comes, it skips the mark if a fetch that started after this call has written,
   * like a forced fetch that took over an unforced one queued ahead of it. That data is already
   * newer than the change, so marking it would only cost another fetch. Tickets decide "after",
   * since clock times can tie.
   */
  override fun invalidate(): Operation {
    submissions.incrementAndFetch()
    val ticket = tickets.incrementAndFetch()
    lastInvalidation.update { maxOf(it, ticket) }
    return actions.runOrJoin(ordering, Invalidation, joins = { false }) {
      if (lastWrite.load() < ticket) {
        store.invalidate()
      }
    }
  }

  /**
   * Runs [check] on this query's key, so it never races a fetch. A forced request can take the
   * check over until it writes or decides not to fetch. A check taken over runs a forced fetch.
   */
  private suspend fun runCheck(check: Check) = settlingStatus {
    val fetches =
      try {
        check.isReplaced || needsFetch(check.kind)
      } catch (t: Throwable) {
        // A check taken over while it read still owes its forced fetch, unless it was stopped
        if (check.commit()) {
          throw t
        }
        currentCoroutineContext().ensureActive()
        true
      }
    when {
      check.isReplaced -> fetchOnce(forced = true)
      fetches -> fetchReplaceable(check)
      !check.commit() -> fetchOnce(forced = true)
    }
  }

  /** Whether a check of [kind] fetches, from the stored time, the hold-off and the last status. */
  private suspend fun needsFetch(kind: CheckKind): Boolean {
    val stale = isStale(store.data.first()?.fetchedAt)
    return when (kind) {
      CheckKind.Requested -> stale
      CheckKind.Automatic -> stale && !holdOff.holdsOff(clock.now(), online.value)
      CheckKind.Reconnected ->
        (stale || status.value is FetchStatus.Failed) &&
          !holdOff.declines(clock.now(), online.value)
    }
  }

  /**
   * An unforced fetch that a forced request can replace while it runs. The fetch runs in a child
   * coroutine, so the check can wake on a takeover even while the fetch ignores cancellation. On a
   * takeover the check shows the forced status at once, cancels the child and waits for it to
   * finish, and then runs a forced fetch. The child cannot write once replaced, as [fetchOnce]
   * describes. The check and its child never write [status] at once: the child writes its outcome
   * only after it commits, and the check writes again only after a takeover, which stops the child
   * from committing.
   */
  private suspend fun fetchReplaceable(check: Check) {
    check.fetching = true
    // Taken over since runCheck looked, so no unforced fetch starts only to be cancelled
    if (check.isReplaced) {
      fetchOnce(forced = true)
      return
    }
    status.value = FetchStatus.Fetching(forced = false)
    var failure: Throwable? = null
    coroutineScope {
      // Undispatched, so a fetch that never suspends finishes in the check's own dispatch
      val attempt =
        launch(start = CoroutineStart.UNDISPATCHED) {
          try {
            fetchOnce(
              forced = false,
              automatic = check.kind != CheckKind.Requested,
              replaceable = check,
            )
          } catch (t: Throwable) {
            failure = t
          }
        }
      attempt.invokeOnCompletion { check.wake() }
      check.awaitWake()
      if (check.isReplaced) {
        status.value = FetchStatus.Fetching(forced = true)
        attempt.cancelAndJoin()
      }
    }
    if (check.isReplaced) {
      fetchOnce(forced = true)
      return
    }
    // Rethrown out here as the same instance, which coroutineScope could copy
    failure?.let { throw it }
  }

  private fun isStale(at: Instant?): Boolean {
    if (at == null || at <= QueryStore.INVALIDATED) {
      return true
    }
    val now = clock.now()
    // A time in the future means the clock moved back or the record is bad
    return at > now || freshness.isStale(at, now)
  }

  /**
   * Fetches and writes. An [automatic] fetch that fails opens the hold-off that [retry] gives, or
   * records that [retry] declined it, noting whether it failed offline. Every success clears it. A
   * [replaceable] fetch leaves [status] to its check, and writes or reports only after it commits,
   * which a forced takeover prevents. A replaceable fetch already taken over never calls [fetch]. A
   * failed fetch sets [status] before the hold-off, so an [Error] from [retry] cannot hide the
   * fetch's own failure.
   */
  private suspend fun fetchOnce(
    forced: Boolean,
    automatic: Boolean = false,
    replaceable: Check? = null,
  ) {
    if (replaceable == null) {
      status.value = FetchStatus.Fetching(forced)
    }
    try {
      if (replaceable != null && replaceable.isReplaced) {
        throw CancellationException("Replaced by a forced fetch")
      }
      val started = tickets.incrementAndFetch()
      val fetchedAt = clock.now()
      val value = fetch()
      // A fetch superseded while it ran never writes, even one whose fetch ignored cancellation
      currentCoroutineContext().ensureActive()
      if (replaceable != null && !replaceable.commit()) {
        throw CancellationException("Replaced by a forced fetch")
      }
      // A fetch also never overwrites data that another writer put in the store meanwhile with a
      // newer fetch time, like a second query's fetch or a sent change the server already holds.
      // The store checks and writes in one update, so such a write cannot land between the two. An
      // equal time is this fetch's own or as new, so it is replaced. A fetch time ahead of the
      // clock is a bad record, which isStale treats as stale, so the fetch replaces that too.
      store.writeFetched(value, fetchedAt) { storedAt ->
        storedAt > fetchedAt && storedAt <= clock.now()
      }
      // Kept data is newer still, so an invalidation requested before this fetch started may skip
      lastWrite.update { maxOf(it, started) }
      holdOff.succeeded(clock.now())
      status.value = FetchStatus.Idle
    } catch (t: Throwable) {
      // Replaced by a forced fetch, whose check sets the status from here on
      if (replaceable != null && !replaceable.commit()) {
        throw t
      }
      // Stopped by its owner, or replaced by a newer forced fetch that starts only after this one
      // ends and then sets its own status
      if (!currentCoroutineContext().isActive) {
        status.value = FetchStatus.Idle
        throw t
      }
      // Set before the hold-off, so the status names this failure whatever the policy does
      status.value = FetchStatus.Failed(t)
      if (automatic) {
        try {
          holdOff.failed(clock.now(), offline = !online.value) { failures ->
            retry.holdOffWindow(failures, t, onFailure)
          }
        } catch (policyError: Throwable) {
          // holdOffWindow reports an Exception itself, so only an Error, like TODO()'s, gets here.
          // The fetch's own failure is reported first, then the Error fails the command and goes to
          // the failure handler, and no hold-off is recorded.
          onFetchFailure(t)
          throw policyError
        }
      }
      // The handle settles Failed and the failure reporter hears it
      throw t
    }
  }

  /**
   * Sends a failed command's [cause] to [onFetchFailure] when a fetch threw it, and to [onFailure]
   * otherwise. A failed fetch sets [status] to its failure before its command settles, and no other
   * command on this query's key starts before then, so the status still names it when the runner
   * reports.
   */
  private fun report(cause: Throwable) {
    if ((status.value as? FetchStatus.Failed)?.cause === cause) {
      onFetchFailure(cause)
    } else {
      onFailure(cause)
    }
  }

  /** What a request on this query's key is, so a later request can tell whether to join it. */
  private sealed interface Request

  /** A forced fetch's tag, with the ticket of its request. */
  private class ForcedFetch(val requested: Int) : Request

  /** An invalidation's tag, which a forced request's search walks past. */
  private object Invalidation

  /** Who asked for a check, which decides when it fetches. */
  private enum class CheckKind {
    /** An explicit unforced request: fetches when stale. */
    Requested,
    /** A collector or the watcher: fetches when stale, unless failures hold it off. */
    Automatic,
    /**
     * The watcher after a reconnect: fetches when stale or when the last fetch failed, unless the
     * policy declined that failure.
     */
    Reconnected,
  }

  /**
   * A check's tag, one per command, which also lets a forced request take the check over. One
   * compare-and-set decides between the check committing and a takeover: a check commits before it
   * writes, reports a failure, or decides not to fetch, and a takeover only wins before then.
   */
  private class Check(val kind: CheckKind) : Request {
    private val state = AtomicReference<CheckState>(CheckState.Open)
    // Completes on a takeover or once the check's fetch has finished, whichever comes first
    private val woken = CompletableDeferred<Unit>()
    // Set once the check decided to fetch, so an explicit request may join it
    @Volatile var fetching = false

    val isReplaced: Boolean
      get() = state.load() is CheckState.Replaced

    /**
     * Whether an explicit unforced request may share this check's outcome. A check that may still
     * hold off cannot, and a reconnect check holds off after a declined failure.
     */
    val servesRequest: Boolean
      get() = kind == CheckKind.Requested || fetching || isReplaced

    /**
     * Whether a forced request may share this check's outcome. An open check is taken over with a
     * ticket from [nextTicket], so its forced fetch starts after every invalidation requested so
     * far. A check taken over earlier is joined only when that takeover came after the invalidation
     * with ticket [lastInvalidation]. A committed check is never joined.
     */
    fun joinForced(lastInvalidation: Int, nextTicket: () -> Int): Boolean {
      while (true) {
        when (val current = state.load()) {
          CheckState.Open -> {
            if (state.compareAndSet(current, CheckState.Replaced(nextTicket()))) {
              woken.complete(Unit)
              return true
            }
          }
          is CheckState.Replaced -> return current.at > lastInvalidation
          CheckState.Committed -> return false
        }
      }
    }

    /** Commits the check's own outcome. False once a forced request has taken it over. */
    fun commit(): Boolean =
      state.compareAndSet(CheckState.Open, CheckState.Committed) ||
        state.load() == CheckState.Committed

    fun wake() {
      woken.complete(Unit)
    }

    suspend fun awaitWake() {
      woken.await()
    }
  }

  /** Where a check stands for a takeover. */
  private sealed interface CheckState {
    /** Not committed yet, so a forced request can take it over. */
    data object Open : CheckState

    /** Writing, reporting or done without a fetch, so it keeps its own outcome. */
    data object Committed : CheckState

    /** Taken over by a forced request whose takeover got ticket [at]. */
    class Replaced(val at: Int) : CheckState
  }

  private class Watching(val observers: Int, val watcher: Job?)
}

/** The first pause before the watcher collects a failed store again. */
private val STORE_RETRY_FIRST = 1.seconds

/** The longest pause before the watcher collects a failed store again. */
private val STORE_RETRY_MAX = 1.minutes

/**
 * Holds that keep a query's owner alive while the query still needs it. The owner is whatever
 * created the query and may drop it once it is unused, such as the per-key entry inside a keyed
 * query. A hold can be taken only while the owner is already held, so a hold never revives an owner
 * that was dropped.
 */
internal interface QueryHolds {
  /** Takes a hold, or returns false once the owner was dropped. */
  fun tryAcquire(): Boolean

  fun release()

  /** Holds the owner until [operation]'s command finishes, cancelled or not. */
  fun holdUntilSettled(operation: Operation)

  /** A query without an owner to keep. */
  object None : QueryHolds {
    override fun tryAcquire(): Boolean = true

    override fun release() {}

    override fun holdUntilSettled(operation: Operation) {}
  }
}
