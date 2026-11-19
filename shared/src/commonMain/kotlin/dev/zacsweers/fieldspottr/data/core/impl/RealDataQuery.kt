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
import dev.zacsweers.fieldspottr.data.core.QueryState
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.runCatchingCancellable
import dev.zacsweers.fieldspottr.data.core.sameFetched
import dev.zacsweers.fieldspottr.data.core.sameState
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
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. Its
 * watcher runs in [scope] while anyone collects [stateAutoFetch]. [holds] keeps whatever owns this
 * query alive, such as the per-key entry inside a keyed query, for as long as the watcher or a
 * request still needs it.
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
  /**
   * When automatic fetches may run again after failures, as [DataQuery.stateAutoFetch] describes.
   */
  private val holdOff: FetchHoldOff = FetchHoldOff.inMemory(),
  /** A staleness rule the query's owner adds, like a keyed set's invalidateAll(). */
  private val staleMark: StaleMark = StaleMark.None,
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
  // How many observers collect stateAutoFetch, and the watcher that runs while any do. Updated by
  // compare-and-set only.
  private val watching = AtomicReference(Watching(observers = 0, watcher = null))
  // Only commands on this query's key change it. It starts as the failure that still holds off this
  // query's own fetches, if any, since a keyed set's record of that outlives each query it builds
  // for the key, and a new query would otherwise neither fetch nor show why.
  private val progress = MutableStateFlow(Progress<T>(heldFailure()))

  override val stateAutoFetch: Flow<QueryState<T>> = flow {
    StateObserver(fetches = true).observe(this)
  }

  // An observer that starts no check and no watcher, as StateObserver describes
  override val state: Flow<QueryState<T>> = flow { StateObserver(fetches = false).observe(this) }

  override val data: Flow<Fetched<T>?> = store.data.distinctUntilChanged(::sameFetched)

  // Declared after stateAutoFetch, which it reads
  override val dataAutoFetch: Flow<Fetched<T>?> =
    stateAutoFetch.map { it.fetched }.distinctUntilChanged(::sameFetched)

  override val status: StateFlow<FetchStatus> = StatusView(progress)

  /** Sets [status], which ends a write phase and drops the write record. */
  private fun setStatus(next: FetchStatus) {
    progress.value = Progress(next)
  }

  /** The failure that holds off this query's own fetches now as a status, or `Idle`. */
  private fun heldFailure(): FetchStatus =
    holdOff.heldFailure(clock.now(), online.value)?.let { FetchStatus.Failed(it) }
      ?: FetchStatus.Idle

  /**
   * One collector of [stateAutoFetch], or of [state] when [fetches] is false. It merges its own
   * read of the store, the query's [progress] and the end of its first check in one coroutine.
   *
   * It shows the status of [progress], with two exceptions. When its first value was stale or
   * absent and it queued a check, it shows `Fetching` until the check settles or a fetch or a write
   * shows in [progress]. While a fetch writes, a value that is that write's shows as `Idle` at
   * once, never beside `Fetching`. Such a value has a fetch time or flag other than those of the
   * value shown, or the running fetch's own time, a first value included. A change over the same
   * stored value, like an optimistic change, shows with the status as it is, even while the write
   * waits for the store.
   *
   * Once the write settles, this collector lines up with the write's record from [settleWrite].
   * When its latest value matches the record, it shows that value with the new status. Otherwise
   * its read lags the write, so it shows the record's value, starts its read again and drops what
   * the old read still delivers. Without a record it starts its read again and shows the new status
   * with the first value of that read. So it never shows `Idle` before the data of the fetch that
   * set it.
   *
   * With [fetches] false it starts no check and no watcher and is not counted. A fetch reads the
   * store back only while observers are counted, so this collector then takes the path without a
   * record. That keeps the same order of values and statuses with no copy of the value kept for it.
   */
  private inner class StateObserver(private val fetches: Boolean) {
    // Read before the store, so a quiet key at both ends means no command ran around the read
    private val submitted = submissions.load()
    private val idle = actions.isIdle(ordering)
    // The newest write record this collector has lined up with. One that settled before its first
    // read began is in that read.
    private var handled: Wrote<T>? = progress.value.wrote
    // Bumped to start the store read again. Values from an earlier read are dropped.
    private val reads = MutableStateFlow(0)
    // This collector's first check, set when its first status shows Fetching on its behalf
    private val speculation = MutableStateFlow<Operation?>(null)
    // Set once the first value was handled. A collector that fetches was counted in then.
    private var started = false
    private var speculative = false
    // Set once this collector showed the value of the write in progress, so it shows Idle
    private var wroteEarly = false
    // Set while a new read must deliver its first value before a new status shows
    private var rereading = false
    // The newest value from the current read, which may not be shown yet
    private var latest: Fetched<T>? = null
    private var shown: QueryState<T>? = null

    suspend fun observe(out: FlowCollector<QueryState<T>>) {
      try {
        merge(
            storeReads(),
            progress.map { StateEvent.Progressed },
            speculation.filterNotNull().take(1).map { check ->
              check.state.first { it is OperationState.Terminal }
              StateEvent.Checked
            },
          )
          .collect { event ->
            when (event) {
              is StateEvent.Value -> onValue(event, out)
              StateEvent.Progressed -> onProgress(out)
              StateEvent.Checked -> {
                // A failed check keeps its error, and the query's progress reaches this collector
                speculation.value = null
                speculative = false
                onProgress(out)
              }
            }
          }
      } finally {
        if (fetches && started) {
          stopWatching()
        }
      }
    }

    /**
     * The store's values, from one collection of the store per number in [reads]. A new number
     * starts a new collection, and the one before it keeps running until the new one delivers its
     * first value. So a store that counts its collectors, like one from [QueryStore.inMemoryByKey],
     * counts this collector through the whole handoff and never drops its key in between. Each
     * value carries its read's number, and [onValue] drops the values of a read that was replaced.
     *
     * A collection ends once a newer one has delivered a value, or once it ends itself. Either way
     * it then cancels the one before it, so no older collection outlives a newer one.
     */
    private fun storeReads(): Flow<StateEvent.Value<T>> = channelFlow {
      var previous: Job? = null
      reads.collect { read ->
        val before = previous
        previous = launch {
          try {
            var first = true
            store.data.collect { value ->
              send(StateEvent.Value(value, read))
              if (first) {
                first = false
                before?.cancel()
              }
            }
          } finally {
            before?.cancel()
          }
        }
      }
    }

    private suspend fun onValue(event: StateEvent.Value<T>, out: FlowCollector<QueryState<T>>) {
      if (event.read != reads.value) {
        return
      }
      latest = event.value
      if (!started) {
        first(event.value, out)
        return
      }
      rereading = false
      val current = progress.value
      if (
        current.writing &&
          (!sameStamp(event.value, shown?.fetched) || current.isWriteOf(event.value))
      ) {
        wroteEarly = true
      }
      update(current, out)
    }

    /**
     * Shows the first value at once, unless a write settled while the first read ran. That write
     * may be missing from the value, so the collector first lines up with its record, as [settle]
     * does for later values. Without a record read back, nothing shows until a new read delivers
     * its first value.
     *
     * A collector that fetches first checks its own first value, since the data can go stale
     * between collectors. The check is a command of its own, so a collector that leaves after one
     * value, like `first()`, still started it. The first such collector also starts the watcher.
     */
    private suspend fun first(value: Fetched<T>?, out: FlowCollector<QueryState<T>>) {
      started = true
      if (fetches) {
        val quiet = idle && submissions.load() == submitted
        // Read before the check is queued, so the watcher sees a reconnect after that check ran
        // offline, as watchNetwork describes
        val wasOnline = online.value
        // A quiet key had no command queued, running or submitted from before the read until now,
        // so the value already shows every write this query made and a fresh value needs no check.
        // Otherwise the collector joins or queues a check that reads the store again in its turn.
        val check =
          if (quiet && !isStale(value)) {
            null
          } else {
            autoCheck()
          }
        if (check != null) {
          holds.holdUntilSettled(check)
        }
        startWatching(check, value, wasOnline)
        if (check != null && isStale(value)) {
          speculative = true
          speculation.value = check
        }
      }
      val current = progress.value
      if (current.isWriteOf(value)) {
        wroteEarly = true
      }
      update(current, out)
    }

    private suspend fun onProgress(out: FlowCollector<QueryState<T>>) {
      if (!started) {
        return
      }
      val current = progress.value
      if (current.status is FetchStatus.Fetching) {
        speculative = false
      }
      if (!current.writing) {
        wroteEarly = false
      }
      update(current, out)
    }

    /**
     * Lines this collector up with the write record in [current] when it has not yet, else shows
     * [latest] with the status [current] shows, unless a new read is still due.
     */
    private suspend fun update(current: Progress<T>, out: FlowCollector<QueryState<T>>) {
      val wrote = current.wrote
      when {
        wrote != null && wrote !== handled -> settle(wrote, current, out)
        // The new read's first value shows the status then
        rereading -> Unit
        else -> show(latest, current, out)
      }
    }

    /** Lines this collector up with [wrote], the write that settled the fetch [current] shows. */
    private suspend fun settle(
      wrote: Wrote<T>,
      current: Progress<T>,
      out: FlowCollector<QueryState<T>>,
    ) {
      handled = wrote
      speculative = false
      wroteEarly = false
      when {
        !wrote.read -> {
          rereading = true
          reads.value += 1
        }
        sameFetched(latest, wrote.snapshot) -> show(latest, current, out)
        else -> {
          latest = wrote.snapshot
          show(latest, current, out)
          reads.value += 1
        }
      }
    }

    /** Emits [fetched] with the status [current] shows, unless that is what was shown last. */
    private suspend fun show(
      fetched: Fetched<T>?,
      current: Progress<T>,
      out: FlowCollector<QueryState<T>>,
    ) {
      val status =
        when {
          current.writing && wroteEarly -> FetchStatus.Idle
          speculative && current.status !is FetchStatus.Fetching -> FetchStatus.Fetching(false)
          else -> current.status
        }
      val next = QueryState(fetched, status)
      val last = shown
      if (last != null && sameState(last, next)) {
        return
      }
      shown = next
      out.emit(next)
    }
  }

  /**
   * Counts an observer in, and starts the watcher for the first one, whose check is [check], whose
   * first value was [seen], and who saw the network as [wasOnline] before it queued that check.
   */
  private fun startWatching(check: Operation?, seen: Fetched<T>?, wasOnline: Boolean) {
    while (true) {
      val current = watching.load()
      if (current.observers > 0) {
        if (watching.compareAndSet(current, Watching(current.observers + 1, current.watcher))) {
          return
        }
        continue
      }
      // The job takes the block itself, which can drop check, as [watch] describes
      val watcher = scope.launch(start = CoroutineStart.LAZY, block = watch(check, seen, wasOnline))
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
   * observers see it. A value that goes away, or drops to one that was never fetched, like local
   * changes alone, is checked again after the work already queued, so a clear during a fetch's
   * write gets a check after that write. An invalidated value keeps its data and waits for the next
   * collect, as [invalidate] promises. The watcher also checks again on each reconnect, as
   * [watchNetwork] describes.
   *
   * The first observer saw [seen] and made [check], or no check for fresh data. Without a check,
   * the watcher's first value counts as a change from [seen]. With one, the first value needs no
   * check of its own unless that value is nothing stored. [check] may be one the observer joined,
   * which read a value before a clear and so never fetched. So the watcher waits for it, and checks
   * once more if it succeeded and the store is still empty. A failed check, like a fetch while
   * offline, already made its attempt, so the watcher leaves it. [wasOnline] is how the first
   * observer saw the network, for [watchNetwork].
   *
   * A store whose data fails is reported to [onFailure] and collected again after a pause that
   * doubles from [STORE_RETRY_FIRST] up to [STORE_RETRY_MAX], so the failure never reaches the
   * scope's exception handler. Only a collection that stays up for as long as the current pause
   * resets it, so a store that fails right after each value still backs off.
   *
   * Returns the watcher's block, which drops [check] after the first value, since a failed check
   * keeps its error. Pass the block to `launch` as is, since a lambda that calls this would keep
   * [check].
   */
  private fun watch(
    check: Operation?,
    seen: Fetched<T>?,
    wasOnline: Boolean,
  ): suspend CoroutineScope.() -> Unit {
    var firstCheck: Operation? = check
    return watcher@{
      launch { watchNetwork(wasOnline) }
      var previous: Fetched<T>? = seen
      var pause = STORE_RETRY_FIRST
      while (true) {
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
   * Checks again each time [online] turns from false to true, as [stateAutoFetch] describes.
   *
   * The watcher starts to collect [online] some time after the first observer queued its check, and
   * that check can run and fail offline in between. So the first value counts as a reconnect when
   * it is online and [startedOnline], how that observer saw the network, is false. Changes of
   * [online] that come and go before a collector sees them are lost, as with any state flow.
   */
  private suspend fun watchNetwork(startedOnline: Boolean) {
    var wasOnline = startedOnline
    online.collect { up ->
      if (up && !wasOnline) {
        holds.holdUntilSettled(reconnectCheck())
      }
      wasOnline = up
    }
  }

  /**
   * Whether [current] has lost the fetched data that [previous] showed: it went away, or it dropped
   * to a value that was never fetched, like local changes over a truth that a capped store evicted.
   */
  private fun lostFetch(previous: Fetched<T>?, current: Fetched<T>?): Boolean {
    if (previous == null) {
      return false
    }
    if (current == null) {
      return true
    }
    return current.fetchedAt == null && previous.fetchedAt != null
  }

  override fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /**
   * Joins a forced fetch that has not settled, even behind checks queued after it. Else takes over
   * a started check that has not written yet, as [DataQuery] describes. Else fetches now.
   *
   * The search walks past queued invalidations too. A takeover across one is safe, since its forced
   * fetch starts after the invalidate() call, and the invalidation then skips its mark. A forced
   * request never joins a forced request or takeover made before the newest invalidate() call.
   */
  private fun fetchNow(): Operation {
    submissions.update { it + 1 }
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
   * Runs a command's [block] and never leaves [status] on `Fetching`, however it exits, its own
   * cancellation included. Most exits already set `Idle` or `Failed`. This covers the rest, like a
   * takeover that its owner's cancellation reached before the check could set its outcome. No other
   * command on this query's key runs until this one has settled, and the block's child coroutines
   * have finished by the time it returns or throws, so nothing else writes [status] meanwhile.
   */
  private suspend fun settlingStatus(block: suspend () -> Unit) {
    try {
      block()
    } finally {
      if (status.value is FetchStatus.Fetching) {
        setStatus(FetchStatus.Idle)
      }
    }
  }

  /**
   * Joins the newest command on this query's key when it is a forced fetch or a check that
   * [Check.servesRequest]. Else checks the stored time and fetches if stale.
   */
  private fun fetchIfStale(): Operation =
    submitCheck(CheckKind.Requested) {
      it.tag is ForcedFetch || (it.tag as? Check)?.servesRequest == true
    }

  /**
   * A collector's check. Single flight: joins the forced fetch or check that is the newest command
   * on this query's key, else checks the stored time and fetches if stale and not held off.
   */
  private fun autoCheck(): Operation = submitCheck(CheckKind.Automatic) { it.tag is Request }

  /**
   * Checks the stored time once the work already queued is done. It joins only a check that has not
   * started, which reads the store later anyway, and never work that may have read it already.
   */
  private fun recheck(): Operation =
    submitCheck(CheckKind.Automatic) { it.tag is Check && !it.hasStarted }

  /** A check of its own after a reconnect, which never joins one that may skip a failed fetch. */
  private fun reconnectCheck(): Operation = submitCheck(CheckKind.Reconnected) { false }

  /** Submits a check of [kind] on this query's key, or joins the newest command that [joins]. */
  private fun submitCheck(kind: CheckKind, joins: (Entry) -> Boolean): Operation {
    submissions.update { it + 1 }
    val check = Check(kind)
    return actions.runOrJoin(ordering, check, joins = joins) { runCheck(check) }
  }

  /**
   * Records its ticket before it queues the mark, so from that moment a fetch that started earlier
   * writes its value already marked stale, as [fetchOnce] describes. The mark skips when a fetch
   * with a later ticket has written, since marking data newer than the change would only cost
   * another fetch.
   */
  override fun invalidate(): Operation {
    submissions.update { it + 1 }
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
        check.isReplaced || needsFetchUnlessReplaced(check)
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

  /**
   * [needsFetch] for [check], or true as soon as a forced request takes [check] over. A store whose
   * data has emitted no value, like an optimistic store before its first row list, keeps the read
   * waiting. So a takeover cancels the read, and [runCheck] then runs the forced fetch. The read
   * runs in a child coroutine, so it starts at once and a value that is already there wins.
   */
  private suspend fun needsFetchUnlessReplaced(check: Check): Boolean = coroutineScope {
    val read = async(start = CoroutineStart.UNDISPATCHED) { needsFetch(check.kind) }
    select {
      read.onAwait { it }
      check.woken.onJoin {
        read.cancel()
        true
      }
    }
  }

  /** Whether a check of [kind] fetches, from the stored time, the hold-off and the last status. */
  private suspend fun needsFetch(kind: CheckKind): Boolean {
    val stale = isStale(store.data.first())
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
    setStatus(FetchStatus.Fetching(forced = false))
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
      attempt.invokeOnCompletion { check.woken.complete(Unit) }
      check.woken.await()
      if (check.isReplaced) {
        setStatus(FetchStatus.Fetching(forced = true))
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

  private fun isStale(fetched: Fetched<T>?): Boolean {
    val at = fetched?.fetchedAt
    if (at == null || fetched.invalidated) {
      return true
    }
    val now = clock.now()
    // A time in the future means the clock moved back or the record is bad
    return at > now || staleMark.isStale(at, now) || freshness.isStale(at, now)
  }

  /**
   * Ends a successful fetch's write phase. Sets `Idle` with a record of the write for each
   * [StateObserver] to line up with. While any observer is counted, the record holds what the store
   * shows right after the write. With no observer counted, or when that read fails, collectors read
   * again on their own.
   */
  private suspend fun settleWrite() {
    var read = false
    var snapshot: Fetched<T>? = null
    if (watching.load().observers > 0) {
      runCatchingCancellable { store.data.first() }
        .onSuccess {
          read = true
          snapshot = it
        }
    }
    progress.value = Progress(FetchStatus.Idle, wrote = Wrote(snapshot, read))
  }

  /**
   * Fetches and writes. An [automatic] fetch that fails opens the hold-off that [retry] gives, or
   * records that [retry] declined it, noting whether it failed offline. Every success clears it. A
   * [replaceable] fetch leaves [status] to its check, and writes or reports only after it commits,
   * which a forced takeover prevents. A replaceable fetch already taken over never calls [fetch].
   *
   * From the write until [settleWrite], [progress] shows a write phase for [StateObserver].
   *
   * A fetch that started before the newest invalidate() call writes its value with the invalidated
   * flag set, as [DataQuery.invalidate] promises. The flag is decided just before the write, so a
   * call after that leaves the mark to the invalidation's own turn, which is next on this key.
   */
  private suspend fun fetchOnce(
    forced: Boolean,
    automatic: Boolean = false,
    replaceable: Check? = null,
  ) {
    if (replaceable == null) {
      setStatus(FetchStatus.Fetching(forced))
    }
    try {
      if (replaceable != null && replaceable.isReplaced) {
        throw CancellationException("Replaced by a forced fetch")
      }
      val started = tickets.incrementAndFetch()
      val markToken = staleMark.fetchStarted()
      val fetchedAt = clock.now()
      val value = fetch()
      // A fetch superseded while it ran never writes, even one whose fetch ignored cancellation
      currentCoroutineContext().ensureActive()
      if (replaceable != null && !replaceable.commit()) {
        throw CancellationException("Replaced by a forced fetch")
      }
      val outdated = lastInvalidation.load() > started
      progress.value =
        Progress(progress.value.status, writingAt = fetchedAt, writingStale = outdated)
      // Keeps a newer usable value, like a sent change the server already holds, as
      // QueryStore.writeFetched describes. An equal time is this fetch's own or as new, so it is
      // replaced. An outdated fetch is stale itself, so it also keeps a newer invalidated value.
      // Calls the Fetched overload, which every store implements, so a store that delegates the
      // other members still sees this write.
      store.writeFetched(Fetched(value, fetchedAt, invalidated = outdated)) { storedAt, invalidated
        ->
        (outdated || !invalidated) &&
          storedAt != null &&
          storedAt > fetchedAt &&
          storedAt <= clock.now()
      }
      // Kept data is newer still, so an invalidation requested before this fetch started may skip
      lastWrite.update { maxOf(it, started) }
      staleMark.fetchWritten(markToken, fetchedAt, clock.now())
      holdOff.succeeded(clock.now())
      settleWrite()
    } catch (t: Throwable) {
      // Replaced by a forced fetch, whose check sets the status from here on
      if (replaceable != null && !replaceable.commit()) {
        throw t
      }
      // Stopped by its owner, or replaced by a newer forced fetch that starts only after this one
      // ends and then sets its own status
      if (!currentCoroutineContext().isActive) {
        setStatus(FetchStatus.Idle)
        throw t
      }
      // Set before the hold-off, so the status names this failure whatever the policy does
      setStatus(FetchStatus.Failed(t))
      if (automatic) {
        try {
          holdOff.failed(clock.now(), offline = !online.value, cause = t) { failures ->
            retry.holdOffWindow(failures, t, onFailure)
          }
        } catch (policyError: Throwable) {
          // holdOffWindow reports an Exception itself, so only an Error, like TODO()'s, gets here.
          // The fetch's own failure is reported first, as DataQuery.stateAutoFetch describes.
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

    /**
     * Completes on a takeover or once the check's fetch has finished, whichever comes first. Before
     * the check fetches, only a takeover completes it.
     */
    val woken = CompletableDeferred<Unit>()
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

/**
 * What a query's fetches are doing. [writingAt] is the time of a successful fetch while it writes
 * and reads back what it wrote, or null otherwise, and [writingStale] is whether it writes its
 * value marked stale. [wrote] is the record of the write that settled the last fetch, or null when
 * the last change to [status] was something else. Each instance is new, so a state flow of it emits
 * every change.
 */
private class Progress<T>(
  val status: FetchStatus,
  val writingAt: Instant? = null,
  val writingStale: Boolean = false,
  val wrote: Wrote<T>? = null,
) {
  /** Whether a successful fetch writes now, as [writingAt] describes. */
  val writing: Boolean
    get() = writingAt != null

  /**
   * Whether [value] carries the time and flag of the fetch that writes now, so it is that write's
   * value.
   */
  fun isWriteOf(value: Fetched<*>?): Boolean =
    writing && value != null && value.invalidated == writingStale && value.fetchedAt == writingAt
}

/**
 * A fetch's write, settled. [snapshot] is what the store showed right after it, when [read] is
 * true, as [RealDataQuery.settleWrite] describes.
 */
private class Wrote<T>(val snapshot: Fetched<T>?, val read: Boolean)

/** What one collector of [DataQuery.stateAutoFetch] or [DataQuery.state] reacts to. */
private sealed interface StateEvent<out T> {
  /** A value from the collector's read number [read] of the store. */
  class Value<T>(val value: Fetched<T>?, val read: Int) : StateEvent<T>

  /** The query's progress changed. The collector reads the newest. */
  data object Progressed : StateEvent<Nothing>

  /** The collector's first check settled. */
  data object Checked : StateEvent<Nothing>
}

/** [DataQuery.status] as a view of a query's progress, so the two never disagree. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class StatusView(private val progress: StateFlow<Progress<*>>) : StateFlow<FetchStatus> {
  override val value: FetchStatus
    get() = progress.value.status

  override val replayCache: List<FetchStatus>
    get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<FetchStatus>): Nothing {
    progress.map { it.status }.distinctUntilChanged().collect(collector)
    // State flows never complete, so neither does this view
    awaitCancellation()
  }
}

/** Whether [a] and [b] are both absent, or have the same fetch time and invalidated flag. */
private fun sameStamp(a: Fetched<*>?, b: Fetched<*>?): Boolean =
  if (a == null || b == null) {
    a == null && b == null
  } else {
    a.fetchedAt == b.fetchedAt && a.invalidated == b.invalidated
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
