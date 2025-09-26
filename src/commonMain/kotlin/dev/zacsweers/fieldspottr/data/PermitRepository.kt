// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.DbArea
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.delete
import dev.zacsweers.fieldspottr.touch
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import dev.zacsweers.fieldspottr.util.component6
import dev.zacsweers.fieldspottr.util.component7
import dev.zacsweers.fieldspottr.util.hashOf
import dev.zacsweers.fieldspottr.util.lazySuspend
import dev.zacsweers.fieldspottr.util.parallelForEach
import dev.zacsweers.fieldspottr.util.toNyInstant
import dev.zacsweers.fieldspottr.util.useLines
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import okio.Path
import okio.buffer
import okio.use

/** The default buffer size when working with buffered streams. */
private const val DEFAULT_BUFFER_SIZE: Int = 8 * 1024

// Lie and say we're a browser. NYC parks doesn't like bots
// Can't really easily get a "real" UA without spinning up UI and doing async JS calls
// on iOS.
private const val USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"

internal val FORMATTER =
  LocalDateTime.Format {
    monthNumber(padding = Padding.NONE)
    char('/')
    dayOfMonth(padding = Padding.NONE)
    char('/')
    year()
    char(' ')
    time(
      LocalTime.Format {
        amPmHour(padding = Padding.NONE)
        char(':')
        minute()
        char(' ')
        amPmMarker("a.m.", "p.m.")
      }
    )
  }

internal fun SqlDriver.createFSDatabase(): FSDatabase {
  return FSDatabase(this)
}

@SingleIn(AppScope::class)
class PermitRepository(
  private val appDirs: FSAppDirs,
  private val json: Json,
  private val logger: Logger,
  private val db: suspend () -> FSDatabase,
) {

  @Inject
  constructor(
    sqlDriverFactory: SqlDriverFactory,
    appDirs: FSAppDirs,
    json: Json,
    logger: Logger,
  ) : this(
    appDirs,
    json,
    logger.withTag("PermitRepository"),
    lazySuspend {
      val driver = sqlDriverFactory.create(FSDatabase.Schema, "fs.db")
      driver.createFSDatabase()
    },
  )

  private val client = lazySuspend { HttpClient() }

  private val areasJson by lazy { appDirs.userData / "areas.json" }

  private val areasStateFlow = MutableStateFlow(Areas.default)

  private fun log(message: String) {
    logger.i { message }
  }

  private fun loadLocalAreas(): Areas {
    return try {
      json.decodeFromString<Areas>(appDirs.fs.source(areasJson).buffer().use { it.readUtf8() })
    } catch (_: Exception) {
      Areas.default
    }
  }

  fun areasFlow(): StateFlow<Areas> = areasStateFlow

  suspend fun populateDb(forceRefresh: Boolean, uiLog: (String) -> Unit): Boolean =
    withContext(Dispatchers.IO) {
      log("Starting populateDb with forceRefresh=$forceRefresh")
      val successful =
        prepareAndDownloadFile(
          "https://raw.githubusercontent.com/ZacSweers/FieldSpottr/main/areas.json",
          areasJson,
          allowCachedVersion = !forceRefresh,
        )
      if (!successful) {
        return@withContext false
      }
      log("Downloaded areas.json")
      val newAreas = loadLocalAreas()
      log("Loaded areas: ${newAreas.entries.map { it.areaName }}")

      areasStateFlow.compareAndSet(newAreas, newAreas)

      val areas = areasStateFlow.value
      val outdated =
        if (forceRefresh) {
          areas.entries
        } else {
          areas.entries.filterNot { db().isAreaUpToDate(it) }
        }
      if (outdated.isEmpty()) {
        return@withContext true
      } else {
        uiLog("Populating DB...")
      }

      // Parallelize, but unfortunately we can't escape the try/catch here
      try {
        outdated.parallelForEach(parallelism = areas.entries.size) { area ->
          log("Processing area: ${area.areaName}")
          val successful = db().populateDbFrom(area)
          log("Area ${area.areaName} processing result: $successful")
          if (!successful) {
            throw Exception()
          }
        }
      } catch (e: Exception) {
        logger.e(e) { "Failed to populate DB" }
        return@withContext false
      }
      return@withContext true
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

  private suspend fun fetchCsv(area: Area): Path? {
    log("Fetching CSV for area ${area.areaName} from ${area.csvUrl}")
    val targetPath = appDirs.userCache / "${area.areaName}.csv"
    val downloaded = prepareAndDownloadFile(area.csvUrl, targetPath, allowCachedVersion = false)
    log("CSV download for ${area.areaName}: $downloaded")
    return targetPath.takeIf { downloaded }
  }

  private suspend fun prepareAndDownloadFile(
    url: String,
    targetPath: Path,
    allowCachedVersion: Boolean,
  ): Boolean {
    if (appDirs.fs.exists(targetPath)) {
      if (allowCachedVersion) {
        return true
      }
      appDirs.delete(targetPath)
    }

    appDirs.touch(targetPath)

    val successful = downloadFile(url, targetPath)
    if (!successful) {
      appDirs.delete(targetPath)
    }
    return successful
  }

  private suspend fun downloadFile(
    url: String,
    targetPath: Path,
    attempt: Int = 0,
    maxAttempts: Int = 5,
  ): Boolean {
    try {
      appDirs.fs.appendingSink(targetPath).buffer().use { sink ->
        client()
          .prepareGet(url) { userAgent(USER_AGENT) }
          .execute { httpResponse ->
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
              downloadFile(url, targetPath, attempt + 1, maxAttempts)
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

    return true
  }

  private suspend fun FSDatabase.isAreaUpToDate(area: Area, now: Instant = System.now()): Boolean {
    // Check area last update in the DB. If it's less than a week old, skip it
    val lastUpdate = transactionWithResult {
      fsdbQueries.lastAreaUpdate(area.areaName).executeAsOneOrNull()
    }
    return lastUpdate != null && Instant.fromEpochMilliseconds(lastUpdate) > now.minus(7.days)
  }

  internal suspend fun FSDatabase.populateDbFrom(area: Area): Boolean {
    val now = System.now()

    val csvFile = fetchCsv(area) ?: return false
    log("Processing CSV file for ${area.areaName}: $csvFile")

    // One single transaction for all ops so it's atomic
    transaction {
      // Clear existing permits if we have new ones
      fsdbQueries.deleteAreaPermits(area.areaName)

      // Insert the new entries
      appDirs.fs.source(csvFile).buffer().useLines { lines ->
        var lineCount = 0
        var permitCount = 0
        lines.drop(1).forEach { line ->
          lineCount++
          val lineSegments = line.split(",").map { it.removeSurrounding("\"").trim() }
          // Sometimes the city just breaks a specific park's permits and return a CSV that says
          // "There is no field usage information available for this park."
          if (lineSegments.size < 7) {
            log("Skipping broken CSV entry $line ($lineSegments) in area $area")
            return@forEach
          }
          val (start, end, field, type, name, org, status) = lineSegments
          if (field !in area.fieldMappings) {
            // Irrelevant field
            return@forEach
          }
          val group = area.fieldMappings.getValue(field).group
          val recordId = hashOf(area.areaName, group, start, end, field)

          val startTime = LocalDateTime.parse(start, FORMATTER)
          val endTime = LocalDateTime.parse(end, FORMATTER)

          if (startTime == endTime) {
            // It's... unclear how this happens, but they do exist. Probably mistakes. Toss them
            // out.
            log("Skipping zero-duration permit: $line")
            return@forEach
          }

          fsdbQueries.addPermit(
            DbPermit(
              recordId = recordId.toLong(),
              area = area.areaName,
              groupName = group,
              start = startTime.toNyInstant().toEpochMilliseconds(),
              end = endTime.toNyInstant().toEpochMilliseconds(),
              fieldId = field,
              type = type,
              name = name,
              org = org,
              status = status,
            )
          )
          permitCount++
        }
        log("Area ${area.areaName}: processed $lineCount lines, created $permitCount permits")
      }

      // Log last update time
      fsdbQueries.updateAreaOp(DbArea(area.areaName, now.toEpochMilliseconds()))
    }
    return true
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
