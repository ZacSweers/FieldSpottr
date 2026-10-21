// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.DbArea
import dev.zacsweers.fieldspottr.DbAreaFeed
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.DataActions
import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.keyed
import dev.zacsweers.fieldspottr.delete
import dev.zacsweers.fieldspottr.touch
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
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import okio.Path
import okio.buffer
import okio.use

/** The default buffer size when working with buffered streams. */
private const val DEFAULT_BUFFER_SIZE: Int = 8 * 1024
private val REPO_DATA_REFRESH_INTERVAL = 1.hours
private const val LAST_AREAS_FETCH_AT = "last_areas_fetch_at"
private const val LAST_AREAS_APP_VERSION = "last_areas_app_version"
private const val LAST_MANIFEST_FETCH_AT = "last_manifest_fetch_at"
private const val LAST_MANIFEST_APP_VERSION = "last_manifest_app_version"

/** The fetch time of stored data with no usable one. It is stale under every freshness rule. */
private val UNKNOWN_FETCH_TIME = Instant.fromEpochMilliseconds(0)

internal fun SqlDriver.createFSDatabase(): FSDatabase {
  return FSDatabase(this)
}

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
  queries: DataQuery.Factory,
  scope: CoroutineScope,
  private val appDirs: FSAppDirs,
  private val json: Json,
  private val logger: Logger,
  private val db: SuspendLazy<FSDatabase>,
  private val client: Lazy<HttpClient>,
) : PermitRepository {

  @Inject
  constructor(
    queries: DataQuery.Factory,
    @ForScope(AppScope::class) scope: CoroutineScope,
    database: SuspendLazy<FSDatabase>,
    appDirs: FSAppDirs,
    json: Json,
    logger: Logger,
    client: Lazy<HttpClient>,
  ) : this(
    queries,
    scope,
    appDirs,
    json,
    logger.withTag("PermitRepository"),
    database,
    client,
  )

  private val areasJson by lazy { appDirs.userData / "areas.json" }
  private val manifestJson by lazy { appDirs.userData / "availability" / "manifest.json" }
  private val areaFeedsDir by lazy { appDirs.userData / "availability" / "areas" }

  // Each fetch reports its own failure, so a failed sync only summarizes them
  private val actions = DataActions(scope) { logger.w(it) { "Permit data sync failed" } }

  private val areasStore =
    fileStore(
      path = areasJson,
      fetchedAtKey = LAST_AREAS_FETCH_AT,
      appVersionKey = LAST_AREAS_APP_VERSION,
      decode = ::decodeAreas,
    ) {
      json.encodeToString(Areas.serializer(), it)
    }

  private val areasQuery =
    queries.create(
      store = areasStore,
      fetch = ::downloadAreas,
      freshness = Freshness.staleAfter(REPO_DATA_REFRESH_INTERVAL),
    )

  private val manifestStore =
    fileStore(
      path = manifestJson,
      fetchedAtKey = LAST_MANIFEST_FETCH_AT,
      appVersionKey = LAST_MANIFEST_APP_VERSION,
      decode = ::decodeManifest,
    ) {
      json.encodeToString(AvailabilityManifest.serializer(), it)
    }

  private val manifestQuery =
    queries.create(
      store = manifestStore,
      fetch = ::downloadManifest,
      freshness = Freshness.staleAfter(REPO_DATA_REFRESH_INTERVAL),
    )

  // Keyed by manifest entry, so a feed whose hash changed is a new key with nothing stored
  private val feeds =
    queries.keyed(
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
      .map { state ->
        when (state) {
          null,
          OperationState.Succeeded,
          OperationState.Cancelled -> null
          OperationState.Queued,
          OperationState.Running -> "Refreshing availability..."
          is OperationState.Failed ->
            if (hasAnyPermits()) {
              null
            } else {
              "Failed to fetch areas. Please check connection and try again."
            }
        }
      }

  private fun log(message: String) {
    logger.i { message }
  }

  /** The stored catalog merged with the built-in one, read straight from disk. */
  internal fun loadLocalAreas(): Areas {
    return readFile(areasJson)?.let(::decodeAreas)?.let(::mergeWithDefaults) ?: Areas.default
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
    val catalog =
      if (force) {
        Operation.all(listOf(areasQuery.refresh(), manifestQuery.refresh()))
      } else {
        Operation.all(listOf(areasQuery.fetchIfStale(), manifestQuery.fetchIfStale()))
      }
    val catalogState = catalog.await()

    // A failed download leaves the stored catalog and manifest in place, so feeds still sync
    val areaNames =
      (areasStore.data.first()?.value?.let(::mergeWithDefaults) ?: Areas.default).areaNames
    val manifest = manifestStore.data.first()?.value
    val feedsState =
      if (manifest == null) {
        null
      } else {
        val keys = manifest.areas.filter { it.resolvedAreaName in areaNames }
        val feedsSync =
          if (force) {
            feeds.refresh(keys)
          } else {
            feeds.fetchIfStale(keys)
          }
        feedsSync.await()
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
        emitAll(db.await().fsdbQueries.permitDateRange().asFlow().mapToOne(Dispatchers.IO))
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
      .flowOn(Dispatchers.IO)
  }

  override fun lastUpdateFlow(areaName: String): Flow<Instant?> {
    return flow {
        emitAll(
          db.await().fsdbQueries.lastAreaUpdate(areaName).asFlow().mapToOneOrNull(Dispatchers.IO)
        )
      }
      .map { millis -> millis?.let { Instant.fromEpochMilliseconds(it) } }
      .flowOn(Dispatchers.IO)
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
            .mapToList(Dispatchers.IO)
        )
      }
      .flowOn(Dispatchers.IO)
  }

  private fun windowBoundaryMillis(date: LocalDate, hour: Int): Long {
    require(hour in 0..24) { "Hour must be in 0..24: $hour" }
    val boundaryDate = if (hour == 24) date.plus(1, DateTimeUnit.DAY) else date
    val boundaryHour = if (hour == 24) 0 else hour
    return LocalDateTime(boundaryDate, LocalTime(boundaryHour, 0))
      .toNyInstant()
      .toEpochMilliseconds()
  }

  override fun permitsFlow(date: LocalDate, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = date.plus(1, DateTimeUnit.DAY).atStartOfDayInNy().toEpochMilliseconds()
    log("permitsFlow query: date=$date, group=$group, startTime=$startTime, endTime=$endTime")
    return flow {
      emitAll(
        db
          .await()
          .fsdbQueries
          .getPermits(group, startTime, endTime)
          .asFlow()
          .mapToList(Dispatchers.IO)
      )
    }
  }

  /** Like [permitsFlow] but spanning [days] days starting at [date]. */
  override fun permitsFlow(date: LocalDate, days: Int, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = date.plus(days, DateTimeUnit.DAY).atStartOfDayInNy().toEpochMilliseconds()
    return flow {
      emitAll(
        db
          .await()
          .fsdbQueries
          .getPermits(group, startTime, endTime)
          .asFlow()
          .mapToList(Dispatchers.IO)
      )
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
            .mapToList(Dispatchers.IO)
        )
      }
      .flowOn(Dispatchers.IO)
  }

  private suspend fun downloadAreas(): Areas {
    val text = downloadText("${BuildConfig.REPO_DATA_BASE_URL}/areas.json", tempPathFor(areasJson))
    return checkNotNull(decodeAreas(text)) { "Downloaded areas.json is unreadable" }
  }

  private suspend fun downloadManifest(): AvailabilityManifest {
    val text =
      downloadText(
        "${BuildConfig.REPO_DATA_BASE_URL}/availability/manifest.json",
        tempPathFor(manifestJson),
      )
    return checkNotNull(decodeManifest(text)) { "Downloaded availability manifest is unreadable" }
  }

  private suspend fun downloadFeed(area: AvailabilityManifestArea): AreaFeed {
    val areaName = area.resolvedAreaName
    val text =
      downloadText(
        "${BuildConfig.REPO_DATA_BASE_URL}/${area.resolvedPath}",
        tempPathFor(areaFeedsDir / "$areaName.json"),
      )
    val feed = json.decodeFromString<AvailabilityAreaFeed>(text)
    check(feed.areaName == areaName) {
      "Feed for ${feed.areaName} does not match manifest area $areaName"
    }
    return AreaFeed(hash = area.hash, generatedAt = feed.generatedAt, rows = feed.rows)
  }

  /** The text at [url], downloaded through [tempPath]. Nothing stays on disk. */
  private suspend fun downloadText(url: String, tempPath: Path): String {
    return withContext(Dispatchers.IO) {
      try {
        check(downloadFile(url, tempPath)) { "Failed to download $url" }
        appDirs.fs.source(tempPath).buffer().use { it.readUtf8() }
      } finally {
        appDirs.delete(tempPath)
      }
    }
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
   * A JSON file whose fetch time and app version live in the metadata table, held in memory once
   * read. A file with no fetch time, or written by another app version, reads as stale.
   */
  private fun <T : Any> fileStore(
    path: Path,
    fetchedAtKey: String,
    appVersionKey: String,
    decode: (String) -> T?,
    encode: (T) -> String,
  ): QueryStore<T> {
    // Null until the first read loads the file
    val loaded = MutableStateFlow<Loaded<T>?>(null)
    return QueryStore.of(
      data =
        flow {
          if (loaded.value == null) {
            val fromDisk = readStamped(path, fetchedAtKey, appVersionKey, decode)
            // A write that lands first wins, so a slow read never replaces newer data
            loaded.compareAndSet(null, Loaded(fromDisk))
          }
          emitAll(loaded.filterNotNull().map { it.fetched })
        },
      clear = {
        withContext(Dispatchers.IO) { appDirs.delete(path) }
        loaded.value = Loaded(null)
      },
    ) { value, fetchedAt ->
      withContext(Dispatchers.IO) {
        writeAtomically(path, encode(value))
        val database = db.await()
        database.transaction {
          database.fsdbQueries.setMetadata(fetchedAtKey, fetchedAt.toEpochMilliseconds().toString())
          database.fsdbQueries.setMetadata(appVersionKey, BuildConfig.VERSION_CODE.toString())
        }
      }
      loaded.value = Loaded(Fetched(value, fetchedAt))
    }
  }

  private suspend fun <T : Any> readStamped(
    path: Path,
    fetchedAtKey: String,
    appVersionKey: String,
    decode: (String) -> T?,
  ): Fetched<T>? {
    return withContext(Dispatchers.IO) {
      val value = readFile(path)?.let(decode) ?: return@withContext null
      val queries = db.await().fsdbQueries
      val fetchedAt = queries.getMetadata(fetchedAtKey).executeAsOneOrNull()?.toLongOrNull()
      val appVersion = queries.getMetadata(appVersionKey).executeAsOneOrNull()?.toLongOrNull()
      // A new app version may read the data differently, so it fetches again
      val stamp =
        if (fetchedAt != null && appVersion == BuildConfig.VERSION_CODE) {
          Instant.fromEpochMilliseconds(fetchedAt)
        } else {
          UNKNOWN_FETCH_TIME
        }
      Fetched(value, stamp)
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
          emitAll(db.await().fsdbQueries.getAreaFeed(name).asFlow().mapToOneOrNull(Dispatchers.IO))
        }
          // A row for another hash came from an older manifest, so it counts as nothing stored
          .map { row -> row?.takeIf { it.hash == area.hash }?.toFetched() },
      clear = { withContext(Dispatchers.IO) { db.await().clearAreaFeed(name) } },
      invalidate = { withContext(Dispatchers.IO) { db.await().invalidateAreaFeed(name) } },
    ) { feed, fetchedAt ->
      withContext(Dispatchers.IO) {
        db.await().replaceAreaFeed(area, feed, fetchedAt.toEpochMilliseconds())
      }
    }
  }

  private fun readFile(path: Path): String? {
    if (!appDirs.fs.exists(path)) {
      return null
    }
    return appDirs.fs.source(path).buffer().use { it.readUtf8() }
  }

  private fun writeAtomically(path: Path, text: String) {
    val tempPath = tempPathFor(path)
    path.parent?.let(appDirs.fs::createDirectories)
    appDirs.fs.sink(tempPath).buffer().use { it.writeUtf8(text) }
    replaceFile(tempPath, path)
  }

  private suspend fun downloadFile(
    url: String,
    targetPath: Path,
    attempt: Int = 0,
    maxAttempts: Int = 5,
  ): Boolean {
    var successful = true
    try {
      if (appDirs.fs.exists(targetPath)) {
        appDirs.delete(targetPath)
      }
      appDirs.touch(targetPath)
      appDirs.fs.sink(targetPath).buffer().use { sink ->
        client.value.prepareGet(url).execute { httpResponse ->
          if (httpResponse.status == HttpStatusCode.Accepted) {
            if (attempt == maxAttempts) {
              error("Too many retries for $url, giving up")
            }
            val retryAfterSec = httpResponse.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            val wait = retryAfterSec?.seconds ?: 2.seconds
            var url = url
            // Some servers hint a follow-up endpoint while work completes.
            httpResponse.headers[HttpHeaders.Location]?.let { url = it }
            log("Retrying $url after $retryAfterSec seconds")
            delay(wait)
            successful = downloadFile(url, targetPath, attempt + 1, maxAttempts)
            return@execute
          }
          if (httpResponse.status.value !in 200..299) {
            successful = false
            return@execute
          }
          val channel = httpResponse.body<ByteReadChannel>()
          while (!channel.isClosedForRead) {
            val packet = channel.readBuffer(DEFAULT_BUFFER_SIZE.toLong())
            while (!packet.exhausted()) {
              val bytes = packet.readByteArray()
              sink.write(bytes)
            }
          }
        }
      }
    } catch (e: Exception) {
      logger.e(e) { "Failed to download file" }
      return false
    }

    return successful
  }

  private fun tempPathFor(targetPath: Path): Path {
    return targetPath.parent!! / "${targetPath.name}.tmp"
  }

  private fun replaceFile(tempPath: Path, targetPath: Path) {
    targetPath.parent?.let(appDirs.fs::createDirectories)
    if (appDirs.fs.exists(targetPath)) {
      appDirs.delete(targetPath)
    }
    appDirs.fs.atomicMove(tempPath, targetPath)
  }

  private suspend fun hasAnyPermits(): Boolean {
    return withContext(Dispatchers.IO) {
      db.await().fsdbQueries.permitDateRange().executeAsOne().minDate != null
    }
  }

  internal suspend fun importAreaFeed(
    feed: AvailabilityAreaFeed,
    manifestArea: AvailabilityManifestArea,
    fetchedAt: Long,
  ) {
    db
      .await()
      .replaceAreaFeed(
        manifestArea,
        AreaFeed(hash = manifestArea.hash, generatedAt = feed.generatedAt, rows = feed.rows),
        fetchedAt,
      )
  }

  /** Replaces [area]'s permits and its feed row in one transaction. */
  private suspend fun FSDatabase.replaceAreaFeed(
    area: AvailabilityManifestArea,
    feed: AreaFeed,
    fetchedAt: Long,
  ) {
    val rows = checkNotNull(feed.rows) { "Only a downloaded feed carries rows" }
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
            isOverlap = if (row.isOverlap) 1L else 0L,
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
      fsdbQueries.upsertAreaFeed(row.copy(feedFetchedAt = UNKNOWN_FETCH_TIME.toEpochMilliseconds()))
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

/** A file store's contents once read. [fetched] is null when the file holds nothing usable. */
private class Loaded<T>(val fetched: Fetched<T>?)

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
