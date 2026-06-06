// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.DbArea
import dev.zacsweers.fieldspottr.DbAreaFeed
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.delete
import dev.zacsweers.fieldspottr.touch
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import dev.zacsweers.fieldspottr.util.hashOf
import dev.zacsweers.fieldspottr.util.lazySuspend
import dev.zacsweers.fieldspottr.util.parallelForEach
import dev.zacsweers.fieldspottr.util.toNyInstant
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

internal fun SqlDriver.createFSDatabase(): FSDatabase {
  return FSDatabase(this)
}

@SingleIn(AppScope::class)
class PermitRepository(
  private val appDirs: FSAppDirs,
  private val json: Json,
  private val logger: Logger,
  private val db: suspend () -> FSDatabase,
  private val client: Lazy<HttpClient>,
) {

  @Inject
  constructor(
    sqlDriverFactory: SqlDriverFactory,
    appDirs: FSAppDirs,
    json: Json,
    logger: Logger,
    client: Lazy<HttpClient>,
  ) : this(
    appDirs,
    json,
    logger.withTag("PermitRepository"),
    lazySuspend {
      val driver = sqlDriverFactory.create(FSDatabase.Schema, "fs.db")
      driver.createFSDatabase()
    },
    client,
  )

  private val areasJson by lazy { appDirs.userData / "areas.json" }
  private val manifestJson by lazy { appDirs.userData / "availability" / "manifest.json" }
  private val areaFeedsDir by lazy { appDirs.userData / "availability" / "areas" }

  private val areasStateFlow = MutableStateFlow(Areas.default)

  private val _loadingMessage = MutableStateFlow<String?>(null)

  /** Observable loading/error message for UI to display. */
  val loadingMessage: StateFlow<String?> = _loadingMessage

  // Semaphore channel — only one populateDb runs at a time, concurrent calls are dropped
  private val populateSemaphore = Channel<Unit>(1)

  private fun log(message: String) {
    logger.i { message }
  }

  internal fun loadLocalAreas(): Areas {
    return readValidAreas(areasJson) ?: areasStateFlow.value
  }

  fun areasFlow(): StateFlow<Areas> = areasStateFlow

  fun useBuiltInAreas() {
    log("Forcing built-in areas")
    areasStateFlow.value = Areas.default
  }

  suspend fun populateDb(forceRefresh: Boolean): Boolean {
    // Only one populateDb at a time — concurrent calls are dropped
    if (!populateSemaphore.trySend(Unit).isSuccess) return true
    return try {
      withContext(Dispatchers.IO) {
        log("Starting populateDb with forceRefresh=$forceRefresh")
        // Check if the app version has changed since last fetch
        val currentAppVersion = BuildConfig.VERSION_CODE
        val storedAppVersion =
          db()
            .fsdbQueries
            .getMetadata("last_areas_app_version")
            .executeAsOneOrNull()
            ?.toLongOrNull()
        val appVersionChanged = storedAppVersion != currentAppVersion
        if (appVersionChanged) {
          log("App version changed ($currentAppVersion), will fetch fresh areas")
        }
        refreshAreasJson(forceRefresh = forceRefresh || appVersionChanged)
        val areas = loadLocalAreas()
        log("Loaded areas: ${areas.entries.map { it.areaName }}")
        areasStateFlow.value = areas

        _loadingMessage.value = "Refreshing availability..."
        val manifest = refreshAvailabilityManifest(forceRefresh)
        if (manifest == null) {
          val hasCachedPermits = db().hasAnyPermits()
          _loadingMessage.value =
            if (hasCachedPermits) null
            else "Failed to fetch areas. Please check connection and try again."
          return@withContext hasCachedPermits
        }

        val feedAreas = manifest.areas.filter { it.resolvedAreaName in areas.areaNames }
        val staleFeeds =
          if (forceRefresh) {
            feedAreas
          } else {
            feedAreas.filter { manifestArea ->
              db()
                .fsdbQueries
                .getAreaFeed(manifestArea.resolvedAreaName)
                .executeAsOneOrNull()
                ?.hash != manifestArea.hash
            }
          }
        if (staleFeeds.isNotEmpty()) {
          staleFeeds.parallelForEach(parallelism = staleFeeds.size) { manifestArea ->
            refreshAreaFeed(manifestArea)
          }
        }
        val hasCachedPermits = db().hasAnyPermits()
        _loadingMessage.value =
          if (hasCachedPermits || staleFeeds.isEmpty()) null
          else "Failed to fetch areas. Please check connection and try again."
        db().fsdbQueries.setMetadata("last_areas_app_version", currentAppVersion.toString())
        return@withContext hasCachedPermits || staleFeeds.isEmpty()
      }
    } finally {
      populateSemaphore.tryReceive()
    }
  }

  fun permitDateRangeFlow(): Flow<Pair<LocalDate, LocalDate>?> {
    return flow {
        val result = db().fsdbQueries.permitDateRange().executeAsOne()
        val minMillis = result.minDate
        val maxMillis = result.maxDate
        if (minMillis != null && maxMillis != null) {
          val tz = TimeZone.currentSystemDefault()
          val min = Instant.fromEpochMilliseconds(minMillis).toLocalDateTime(tz).date
          val max = Instant.fromEpochMilliseconds(maxMillis).toLocalDateTime(tz).date
          emit(min to max)
        } else {
          emit(null)
        }
      }
      .flowOn(Dispatchers.IO)
  }

  fun lastUpdateFlow(areaName: String): Flow<Instant?> {
    return flow {
        val millis = db().fsdbQueries.lastAreaUpdate(areaName).executeAsOneOrNull()
        emit(millis?.let { Instant.fromEpochMilliseconds(it) })
      }
      .flowOn(Dispatchers.IO)
  }

  fun allPermitsInWindow(date: LocalDate, startHour: Int, endHour: Int): Flow<List<DbPermit>> {
    val windowStart = windowBoundaryMillis(date, startHour)
    val windowEnd = windowBoundaryMillis(date, endHour)
    return flow {
        emitAll(
          db()
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

  fun permitsFlow(date: LocalDate, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = startTime + 1.days.inWholeMilliseconds
    log("permitsFlow query: date=$date, group=$group, startTime=$startTime, endTime=$endTime")
    return flow {
      emitAll(
        db().fsdbQueries.getPermits(group, startTime, endTime).asFlow().mapToList(Dispatchers.IO)
      )
    }
  }

  private fun readValidAreas(path: Path): Areas? {
    val parsed =
      try {
        if (!appDirs.fs.exists(path)) return null
        json.decodeFromString<Areas>(appDirs.fs.source(path).buffer().use { it.readUtf8() })
      } catch (e: Exception) {
        logger.e(e) { "Failed to decode areas.json" }
        return null
      }
    if (parsed.version > Areas.VERSION) {
      log("Ignoring future areas.json version ${parsed.version}")
      return null
    }
    return mergeWithDefaults(parsed)
  }

  private suspend fun refreshAreasJson(forceRefresh: Boolean) {
    val cached = readValidAreas(areasJson)
    if (cached != null) {
      areasStateFlow.value = cached
    }
    if (!forceRefresh && cached != null && appDirs.fs.exists(areasJson)) return

    val tempPath = tempPathFor(areasJson)
    if (!downloadFile("${BuildConfig.REPO_DATA_BASE_URL}/areas.json", tempPath)) return
    val downloaded = readValidAreas(tempPath)
    if (downloaded == null) {
      appDirs.delete(tempPath)
      return
    }
    replaceDownloadedFile(tempPath, areasJson)
    areasStateFlow.value = downloaded
  }

  private suspend fun refreshAvailabilityManifest(forceRefresh: Boolean): AvailabilityManifest? {
    if (!forceRefresh && appDirs.fs.exists(manifestJson)) {
      decodeAvailabilityManifest(manifestJson)?.let {
        return it
      }
    }

    val tempPath = tempPathFor(manifestJson)
    if (downloadFile("${BuildConfig.REPO_DATA_BASE_URL}/availability/manifest.json", tempPath)) {
      val downloaded = decodeAvailabilityManifest(tempPath)
      if (downloaded != null) {
        replaceDownloadedFile(tempPath, manifestJson)
        return downloaded
      }
      appDirs.delete(tempPath)
    }

    return decodeAvailabilityManifest(manifestJson)
  }

  private fun decodeAvailabilityManifest(path: Path): AvailabilityManifest? {
    return try {
      if (!appDirs.fs.exists(path)) return null
      val manifest =
        json.decodeFromString<AvailabilityManifest>(
          appDirs.fs.source(path).buffer().use { it.readUtf8() }
        )
      manifest.takeIf { it.version <= AvailabilityManifest.VERSION }
    } catch (e: Exception) {
      logger.e(e) { "Failed to decode availability manifest" }
      null
    }
  }

  private suspend fun refreshAreaFeed(manifestArea: AvailabilityManifestArea): Boolean {
    val areaName = manifestArea.resolvedAreaName
    val feedPath = areaFeedsDir / "$areaName.json"
    val tempPath = tempPathFor(feedPath)
    val url = "${BuildConfig.REPO_DATA_BASE_URL}/${manifestArea.resolvedPath}"
    if (!downloadFile(url, tempPath)) {
      return false
    }
    val feed =
      try {
        json.decodeFromString<AvailabilityAreaFeed>(
          appDirs.fs.source(tempPath).buffer().use { it.readUtf8() }
        )
      } catch (e: Exception) {
        logger.e(e) { "Failed to decode availability feed for $areaName" }
        appDirs.delete(tempPath)
        return false
      }
    if (feed.areaName != areaName) {
      log("Ignoring feed for ${feed.areaName}; manifest expected $areaName")
      appDirs.delete(tempPath)
      return false
    }
    val fetchedAt = System.now().toEpochMilliseconds()
    db().replaceAreaFeed(feed, manifestArea, fetchedAt)
    replaceDownloadedFile(tempPath, feedPath)
    return true
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
            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
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

  private fun replaceDownloadedFile(tempPath: Path, targetPath: Path) {
    targetPath.parent?.let(appDirs.fs::createDirectories)
    if (appDirs.fs.exists(targetPath)) {
      appDirs.delete(targetPath)
    }
    appDirs.fs.atomicMove(tempPath, targetPath)
  }

  private fun FSDatabase.hasAnyPermits(): Boolean {
    return fsdbQueries.permitDateRange().executeAsOne().minDate != null
  }

  internal suspend fun importAreaFeed(
    feed: AvailabilityAreaFeed,
    manifestArea: AvailabilityManifestArea,
    fetchedAt: Long,
  ) {
    db().replaceAreaFeed(feed, manifestArea, fetchedAt)
  }

  internal suspend fun FSDatabase.replaceAreaFeed(
    feed: AvailabilityAreaFeed,
    manifestArea: AvailabilityManifestArea,
    fetchedAt: Long,
  ) {
    transaction {
      fsdbQueries.deleteAreaPermits(feed.areaName)
      feed.rows.forEach { row ->
        if (row.end <= row.start) return@forEach
        fsdbQueries.addPermit(
          DbPermit(
            recordId =
              hashOf(
                  row.sourceId ?: manifestArea.hash,
                  row.areaName ?: feed.areaName,
                  row.groupName,
                  row.fieldId,
                  row.start,
                  row.end,
                )
                .toLong(),
            area = row.areaName ?: feed.areaName,
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
      fsdbQueries.updateAreaOp(DbArea(feed.areaName, fetchedAt))
      fsdbQueries.upsertAreaFeed(
        DbAreaFeed(
          name = manifestArea.resolvedAreaName,
          areaName = feed.areaName,
          feedGeneratedAt = feed.generatedAt ?: manifestArea.generatedAt,
          feedFetchedAt = fetchedAt,
          hash = manifestArea.hash,
        )
      )
    }
  }

  fun permitsByGroup(group: String, org: String, start: LocalDate): Flow<List<DbPermit>> {
    return flow {
        emitAll(
          db()
            .fsdbQueries
            .getPermitsByOrg(group, org, start.atStartOfDayInNy().toEpochMilliseconds())
            .asFlow()
            .mapToList(Dispatchers.IO)
        )
      }
      .flowOn(Dispatchers.IO)
  }
}

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
