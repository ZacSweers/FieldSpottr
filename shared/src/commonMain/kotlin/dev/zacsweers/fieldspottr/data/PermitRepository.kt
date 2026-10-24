// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.DbArea
import dev.zacsweers.fieldspottr.DbAreaFeed
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.keyed
import dev.zacsweers.fieldspottr.di.IoDispatcher
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import dev.zacsweers.fieldspottr.util.hashOf
import dev.zacsweers.fieldspottr.util.toNyInstant
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.SuspendLazy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

private val REPO_DATA_REFRESH_INTERVAL = 1.hours
private val REFRESHING_MESSAGE_DELAY = 300.milliseconds
private const val LAST_AREAS_FETCH_AT = "last_areas_fetch_at"
private const val LAST_AREAS_APP_VERSION = "last_areas_app_version"
private const val LAST_MANIFEST_FETCH_AT = "last_manifest_fetch_at"
private const val LAST_MANIFEST_APP_VERSION = "last_manifest_app_version"

// Really abstracted just for Metro testing
interface PermitRepository {
  /**
   * The areas catalog: the stored catalog merged with the built-in one, or the built-in one until a
   * catalog is stored.
   */
  val areas: Flow<Areas>

  /**
   * A message for the UI while a sync runs, or after a sync failed with no permits stored. Null
   * otherwise.
   */
  val loadingMessage: Flow<String?>

  /** Shows the built-in areas catalog until the next [refresh]. */
  fun useBuiltInAreas()

  /** Syncs whatever is stale: the areas catalog, the availability manifest and each area's feed. */
  fun prewarm()

  /** Syncs everything now, even what is fresh. */
  fun refresh(): Operation

  fun permitsByGroup(group: String, org: String, start: LocalDate): Flow<List<DbPermit>>

  fun permitDateRangeFlow(): Flow<Pair<LocalDate, LocalDate>?>

  fun lastUpdateFlow(areaName: String): Flow<Instant?>

  fun allPermitsInWindow(date: LocalDate, startHour: Int, endHour: Int): Flow<List<DbPermit>>

  fun permitsFlow(date: LocalDate, group: String): Flow<List<DbPermit>>

  fun permitsFlow(date: LocalDate, days: Int, group: String): Flow<List<DbPermit>>
}

/**
 * Permits from the repo data feeds, stored in the database. A sync refreshes three things, each
 * with its own fetch time: the areas catalog, the availability manifest, and one feed per area. A
 * failed download keeps what is stored, and only that part fetches again on the next sync. Reads
 * observe the database, so they show the last synced data while a sync runs.
 *
 * ```
 * prewarm() / refresh()
 *   |
 *   v
 * sync (DataActions, one at a time) ----> its handle ----> loadingMessage
 *   |
 *   |  each query downloads from REPO_DATA_BASE_URL and writes its own store
 *   |
 *   +--> areasQuery    --> areas.json + fetch time     --> areas
 *   +--> manifestQuery --> manifest.json + fetch time
 *   |
 *   |  once both settle, for each manifest entry in the catalog
 *   v
 * feeds[entry]         --> dbPermit + dbAreaFeed       --> permitsFlow(), allPermitsInWindow(),
 *                          (one transaction per area)      permitsByGroup(), lastUpdateFlow()
 * ```
 *
 * A feed is keyed by its manifest entry, so a new hash is a new key with nothing stored and fetches
 * even within the hour.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PermitRepositoryImpl
internal constructor(
  data: DataScope,
  private val appDirs: FSAppDirs,
  private val json: Json,
  private val logger: Logger,
  private val db: SuspendLazy<FSDatabase>,
  private val client: Lazy<HttpClient>,
  private val io: CoroutineDispatcher,
) : PermitRepository {

  @Inject
  constructor(
    @ForScope(AppScope::class) data: DataScope,
    database: SuspendLazy<FSDatabase>,
    appDirs: FSAppDirs,
    json: Json,
    logger: Logger,
    client: Lazy<HttpClient>,
    @IoDispatcher io: CoroutineDispatcher,
  ) : this(
    data,
    appDirs,
    json,
    logger.withTag("PermitRepository"),
    database,
    client,
    io,
  )

  private val areasJson by lazy { appDirs.userData / "areas.json" }
  private val manifestJson by lazy { appDirs.userData / "availability" / "manifest.json" }

  // Each fetch reports its own failure, so a failed sync only summarizes them
  private val actions = data.newActions { logger.w(it) { "Permit data sync failed" } }

  private val areasStore =
    StampedFileStore(
      fs = appDirs.fs,
      path = areasJson,
      db = db,
      io = io,
      fetchedAtKey = LAST_AREAS_FETCH_AT,
      appVersionKey = LAST_AREAS_APP_VERSION,
      decode = ::decodeAreas,
    ) {
      json.encodeToString(Areas.serializer(), it)
    }

  private val areasQuery =
    data.query(
      store = areasStore,
      fetch = ::downloadAreas,
      freshness = Freshness.staleAfter(REPO_DATA_REFRESH_INTERVAL),
    )

  private val manifestStore =
    StampedFileStore(
      fs = appDirs.fs,
      path = manifestJson,
      db = db,
      io = io,
      fetchedAtKey = LAST_MANIFEST_FETCH_AT,
      appVersionKey = LAST_MANIFEST_APP_VERSION,
      decode = ::decodeManifest,
    ) {
      json.encodeToString(AvailabilityManifest.serializer(), it)
    }

  private val manifestQuery =
    data.query(
      store = manifestStore,
      fetch = ::downloadManifest,
      freshness = Freshness.staleAfter(REPO_DATA_REFRESH_INTERVAL),
    )

  // Keyed by manifest entry, so a feed whose hash changed is a new key with nothing stored
  private val feeds =
    data.keyed(
      store = ::feedStore,
      fetch = ::downloadFeed,
      freshness = Freshness.staleAfter(REPO_DATA_REFRESH_INTERVAL),
    )

  private val showBuiltInAreas = MutableStateFlow(false)

  // The handle of the sync started last, for the loading message
  private val latestSync = MutableStateFlow<Operation?>(null)

  override val areas: Flow<Areas> =
    combine(areasStore.data, showBuiltInAreas) { stored, builtIn ->
      if (builtIn || stored == null) {
        Areas.default
      } else {
        mergeWithDefaults(stored.value)
      }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  override val loadingMessage: Flow<String?> =
    latestSync
      .flatMapLatest { sync -> sync?.state ?: flowOf(null) }
      .transformLatest { state ->
        when (state) {
          null,
          OperationState.Succeeded,
          OperationState.Cancelled -> emit(null)
          OperationState.Queued,
          OperationState.Running -> {
            // A sync that finds everything fresh settles inside this, so its message never shows
            delay(REFRESHING_MESSAGE_DELAY)
            emit("Refreshing availability...")
          }
          is OperationState.Failed ->
            if (hasAnyPermits()) {
              emit(null)
            } else {
              emit("Failed to fetch areas. Please check connection and try again.")
            }
        }
      }

  private fun log(message: String) {
    logger.i { message }
  }

  /** The stored catalog merged with the built-in one, read straight from disk. */
  internal fun loadLocalAreas(): Areas {
    return appDirs.fs.readTextOrNull(areasJson)?.let(::decodeAreas)?.let(::mergeWithDefaults)
      ?: Areas.default
  }

  override fun useBuiltInAreas() {
    log("Forcing built-in areas")
    showBuiltInAreas.value = true
  }

  override fun prewarm() {
    syncIfStale()
  }

  /** The sync [prewarm] starts, with its handle. */
  internal fun syncIfStale(): Operation = sync(force = false)

  override fun refresh(): Operation {
    showBuiltInAreas.value = false
    return sync(force = true)
  }

  /**
   * One sync at a time. A refresh cancels an older refresh and queues behind a sync already
   * running. The queries underneath keep their own single flight.
   */
  private fun sync(force: Boolean): Operation {
    val onPrevious =
      if (force) {
        OnPrevious.Cancel
      } else {
        OnPrevious.Wait
      }
    return actions
      .run(Ordering.Keyed(SyncKey, onPrevious)) { syncNow(force) }
      .also { latestSync.value = it }
  }

  private suspend fun syncNow(force: Boolean) {
    log("Starting sync with force=$force")
    // The catalog and the manifest load together. Feeds wait for both, since they decide which
    // feeds exist.
    val catalogState =
      Operation.all(listOf(areasQuery.requestFetch(force), manifestQuery.requestFetch(force)))
        .await()

    // A failed download leaves the stored catalog and manifest in place, so feeds still sync
    val areaNames =
      (areasStore.data.first()?.value?.let(::mergeWithDefaults) ?: Areas.default).areaNames
    val manifest = manifestStore.data.first()?.value
    val feedsState =
      if (manifest == null) {
        null
      } else {
        val keys = manifest.areas.filter { it.resolvedAreaName in areaNames }
        feeds.requestFetch(keys, force).await()
      }

    val failure =
      listOfNotNull(catalogState, feedsState)
        .filterIsInstance<OperationState.Failed>()
        .firstOrNull()
    if (failure != null) {
      throw failure.cause
    }
  }

  override fun permitDateRangeFlow(): Flow<Pair<LocalDate, LocalDate>?> {
    return flow {
        emitAll(db.await().fsdbQueries.permitDateRange().asFlow().mapToOne(io))
      }
      .map { result ->
        val minMillis = result.minDate
        val maxMillis = result.maxDate
        if (minMillis != null && maxMillis != null) {
          val tz = TimeZone.currentSystemDefault()
          val min = Instant.fromEpochMilliseconds(minMillis).toLocalDateTime(tz).date
          val max = Instant.fromEpochMilliseconds(maxMillis).toLocalDateTime(tz).date
          min to max
        } else {
          null
        }
      }
      .flowOn(io)
  }

  override fun lastUpdateFlow(areaName: String): Flow<Instant?> {
    return flow {
        emitAll(db.await().fsdbQueries.lastAreaUpdate(areaName).asFlow().mapToOneOrNull(io))
      }
      .map { millis -> millis?.let { Instant.fromEpochMilliseconds(it) } }
      .flowOn(io)
  }

  override fun allPermitsInWindow(
    date: LocalDate,
    startHour: Int,
    endHour: Int,
  ): Flow<List<DbPermit>> {
    val windowStart = windowBoundaryMillis(date, startHour)
    val windowEnd = windowBoundaryMillis(date, endHour)
    return flow {
        emitAll(
          db
            .await()
            .fsdbQueries
            .getPermitsInTimeWindow(windowEnd = windowEnd, windowStart = windowStart)
            .asFlow()
            .mapToList(io)
        )
      }
      .flowOn(io)
  }

  private fun windowBoundaryMillis(date: LocalDate, hour: Int): Long {
    require(hour in 0..24) { "Hour must be in 0..24: $hour" }
    val boundaryDate =
      if (hour == 24) {
        date.plus(1, DateTimeUnit.DAY)
      } else {
        date
      }
    val boundaryHour =
      if (hour == 24) {
        0
      } else {
        hour
      }
    return LocalDateTime(boundaryDate, LocalTime(boundaryHour, 0))
      .toNyInstant()
      .toEpochMilliseconds()
  }

  override fun permitsFlow(date: LocalDate, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = date.plus(1, DateTimeUnit.DAY).atStartOfDayInNy().toEpochMilliseconds()
    log("permitsFlow query: date=$date, group=$group, startTime=$startTime, endTime=$endTime")
    return flow {
      emitAll(db.await().fsdbQueries.getPermits(group, startTime, endTime).asFlow().mapToList(io))
    }
  }

  /** Like [permitsFlow] but spanning [days] days starting at [date]. */
  override fun permitsFlow(date: LocalDate, days: Int, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = date.plus(days, DateTimeUnit.DAY).atStartOfDayInNy().toEpochMilliseconds()
    return flow {
      emitAll(db.await().fsdbQueries.getPermits(group, startTime, endTime).asFlow().mapToList(io))
    }
  }

  override fun permitsByGroup(group: String, org: String, start: LocalDate): Flow<List<DbPermit>> {
    return flow {
        emitAll(
          db
            .await()
            .fsdbQueries
            .getPermitsByOrg(group, org, start.atStartOfDayInNy().toEpochMilliseconds())
            .asFlow()
            .mapToList(io)
        )
      }
      .flowOn(io)
  }

  private suspend fun downloadAreas(): Areas {
    val text = downloadText("${BuildConfig.REPO_DATA_BASE_URL}/areas.json")
    return checkNotNull(decodeAreas(text)) { "Downloaded areas.json is unreadable" }
  }

  private suspend fun downloadManifest(): AvailabilityManifest {
    val text = downloadText("${BuildConfig.REPO_DATA_BASE_URL}/availability/manifest.json")
    return checkNotNull(decodeManifest(text)) { "Downloaded availability manifest is unreadable" }
  }

  private suspend fun downloadFeed(area: AvailabilityManifestArea): AreaFeed {
    val areaName = area.resolvedAreaName
    val text = downloadText("${BuildConfig.REPO_DATA_BASE_URL}/${area.resolvedPath}")
    val feed = json.decodeFromString<AvailabilityAreaFeed>(text)
    check(feed.areaName == areaName) {
      "Feed for ${feed.areaName} does not match manifest area $areaName"
    }
    return AreaFeed(hash = area.hash, generatedAt = feed.generatedAt, rows = feed.rows)
  }

  /**
   * The text at [url]. A 202 means the server is still preparing it, so this waits as long as the
   * server asks, up to [maxAttempts] times.
   */
  private suspend fun downloadText(url: String, attempt: Int = 0, maxAttempts: Int = 5): String {
    val response = client.value.get(url)
    if (response.status == HttpStatusCode.Accepted) {
      check(attempt < maxAttempts) { "Too many retries for $url, giving up" }
      val retryAfterSec = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
      // Some servers hint a follow-up endpoint while work completes
      val next = response.headers[HttpHeaders.Location] ?: url
      log("Retrying $next after $retryAfterSec seconds")
      delay(retryAfterSec?.seconds ?: 2.seconds)
      return downloadText(next, attempt + 1, maxAttempts)
    }
    check(response.status.isSuccess()) { "Failed to download $url: ${response.status}" }
    return response.bodyAsText()
  }

  /** The raw catalog, or null when it is unreadable or from a newer catalog version. */
  private fun decodeAreas(text: String): Areas? {
    val parsed =
      try {
        json.decodeFromString<Areas>(text)
      } catch (e: Exception) {
        logger.e(e) { "Failed to decode areas.json" }
        return null
      }
    if (parsed.version > Areas.VERSION) {
      log("Ignoring future areas.json version ${parsed.version}")
      return null
    }
    return parsed
  }

  /** The manifest, or null when it is unreadable or from a newer manifest version. */
  private fun decodeManifest(text: String): AvailabilityManifest? {
    return try {
      json.decodeFromString<AvailabilityManifest>(text).takeIf {
        it.version <= AvailabilityManifest.VERSION
      }
    } catch (e: Exception) {
      logger.e(e) { "Failed to decode availability manifest" }
      null
    }
  }

  /**
   * The stored feed for [area], read from the area feed table. Its permits are written in the same
   * transaction as its row there.
   */
  private fun feedStore(area: AvailabilityManifestArea): QueryStore<AreaFeed> {
    val name = area.resolvedAreaName
    return QueryStore.of(
      data = flow {
          emitAll(db.await().fsdbQueries.getAreaFeed(name).asFlow().mapToOneOrNull(io))
        }
          // A row for another hash came from an older manifest, so it counts as nothing stored
          .map { row -> row?.takeIf { it.hash == area.hash }?.toFetched() },
      clear = { withContext(io) { db.await().clearAreaFeed(name) } },
      invalidate = { withContext(io) { db.await().invalidateAreaFeed(name) } },
    ) { feed, fetchedAt ->
      withContext(io) {
        db.await().replaceAreaFeed(area, feed, fetchedAt.toEpochMilliseconds())
      }
    }
  }

  private suspend fun hasAnyPermits(): Boolean {
    return withContext(io) {
      db.await().fsdbQueries.permitDateRange().executeAsOne().minDate != null
    }
  }

  internal suspend fun importAreaFeed(
    feed: AvailabilityAreaFeed,
    manifestArea: AvailabilityManifestArea,
    fetchedAt: Long,
  ) {
    withContext(io) {
      db
        .await()
        .replaceAreaFeed(
          manifestArea,
          AreaFeed(hash = manifestArea.hash, generatedAt = feed.generatedAt, rows = feed.rows),
          fetchedAt,
        )
    }
  }

  /** Replaces [area]'s permits and its feed row in one transaction. */
  private suspend fun FSDatabase.replaceAreaFeed(
    area: AvailabilityManifestArea,
    feed: AreaFeed,
    fetchedAt: Long,
  ) {
    val rows = checkNotNull(feed.rows) { "Only a downloaded feed has rows" }
    val areaName = area.resolvedAreaName
    transaction {
      fsdbQueries.deleteAreaPermits(areaName)
      rows.forEach { row ->
        if (row.end <= row.start) {
          return@forEach
        }
        fsdbQueries.addPermit(
          DbPermit(
            recordId =
              hashOf(
                  row.sourceId ?: area.hash,
                  row.areaName ?: areaName,
                  row.groupName,
                  row.fieldId,
                  row.start,
                  row.end,
                )
                .toLong(),
            area = row.areaName ?: areaName,
            groupName = row.groupName,
            start = row.start,
            end = row.end,
            fieldId = row.fieldId,
            type = row.kind,
            name = row.title,
            org = row.org,
            status = row.status,
            isOverlap =
              if (row.isOverlap) {
                1L
              } else {
                0L
              },
            advisory = row.advisoryText,
          )
        )
      }
      fsdbQueries.updateAreaOp(DbArea(areaName, fetchedAt))
      fsdbQueries.upsertAreaFeed(
        DbAreaFeed(
          name = areaName,
          areaName = areaName,
          feedGeneratedAt = feed.generatedAt ?: area.generatedAt,
          feedFetchedAt = fetchedAt,
          hash = area.hash,
        )
      )
    }
  }

  private suspend fun FSDatabase.clearAreaFeed(name: String) {
    transaction {
      fsdbQueries.deleteAreaPermits(name)
      fsdbQueries.deleteAreaFeed(name)
    }
  }

  private suspend fun FSDatabase.invalidateAreaFeed(name: String) {
    transaction {
      val row = fsdbQueries.getAreaFeed(name).executeAsOneOrNull() ?: return@transaction
      val stale = row.copy(feedFetchedAt = QueryStore.INVALIDATED.toEpochMilliseconds())
      fsdbQueries.upsertAreaFeed(stale)
    }
  }

  private fun DbAreaFeed.toFetched(): Fetched<AreaFeed> =
    Fetched(
      AreaFeed(hash = hash, generatedAt = feedGeneratedAt, rows = null),
      Instant.fromEpochMilliseconds(feedFetchedAt),
    )

  private data object SyncKey
}

/**
 * An area's feed as its query sees it. [rows] is set on a fresh download and null when read back,
 * because the rows live in the permits table.
 */
internal data class AreaFeed(
  val hash: String,
  val generatedAt: Long?,
  val rows: List<AvailabilityFeedRow>?,
)

/**
 * Merges [remote] areas with [Areas.default]. Remote is authoritative for areas it includes, but
 * only when it is at least as new as the built-in catalog. Built-in areas not present in remote are
 * preserved. This prevents crashes when app code references fields that the remote JSON doesn't
 * include yet.
 */
internal fun mergeWithDefaults(remote: Areas, defaults: Areas = Areas.default): Areas {
  if (remote.version < defaults.version) {
    val defaultAreaNames = defaults.entries.map { it.areaName }.toSet()
    val remoteOnly = remote.entries.filter { it.areaName !in defaultAreaNames }
    return if (remoteOnly.isEmpty()) {
      defaults
    } else {
      defaults.copy(entries = (defaults.entries + remoteOnly).toImmutableList())
    }
  }

  val remoteAreaNames = remote.entries.map { it.areaName }.toSet()
  val missingFromRemote = defaults.entries.filter { it.areaName !in remoteAreaNames }
  return if (missingFromRemote.isEmpty()) {
    remote
  } else {
    remote.copy(entries = (remote.entries + missingFromRemote).toImmutableList())
  }
}

private val Areas.areaNames: Set<String>
  get() = entries.map { it.areaName }.toSet()
