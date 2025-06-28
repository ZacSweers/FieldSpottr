// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
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
import io.ktor.http.userAgent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

private val FORMATTER =
  LocalDateTime.Format {
    monthNumber(padding = Padding.NONE)
    char('/')
    day(padding = Padding.NONE)
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

@Inject
@SingleIn(AppScope::class)
class PermitRepository(
  private val sqlDriverFactory: SqlDriverFactory,
  private val appDirs: FSAppDirs,
  private val json: Json,
) {
  private val client = lazySuspend { HttpClient() }

  private val db = lazySuspend {
    val driver = sqlDriverFactory.create(FSDatabase.Schema, "fs.db")
    FSDatabase(driver)
  }

  private val areasJson by lazy { appDirs.userData / "areas.json" }

  private val areasStateFlow = MutableStateFlow(Areas.default)

  private fun loadLocalAreas(): Areas {
    return try {
      json.decodeFromString<Areas>(appDirs.fs.source(areasJson).buffer().use { it.readUtf8() })
    } catch (_: Exception) {
      Areas.default
    }
  }

  fun areasFlow(): StateFlow<Areas> = areasStateFlow

  suspend fun populateDb(forceRefresh: Boolean, log: (String) -> Unit): Boolean =
    withContext(Dispatchers.IO) {
      downloadFile(
        "https://raw.githubusercontent.com/ZacSweers/FieldSpottr/main/areas.json",
        areasJson,
        allowCachedVersion = !forceRefresh,
      )
      val newAreas = loadLocalAreas()

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
        log("Populating DB...")
      }

      // Parallelize, but unfortunately we can't escape the try/catch here
      try {
        outdated.parallelForEach(parallelism = areas.entries.size) { area ->
          val successful = db().populateDbFrom(area)
          if (!successful) {
            throw Exception()
          }
        }
      } catch (e: Exception) {
        println("Failed to populate DB:\n${e.stackTraceToString()}")
        return@withContext false
      }
      return@withContext true
    }

  fun permitsFlow(date: LocalDate, group: String): Flow<List<DbPermit>> {
    val startTime = date.atStartOfDayInNy().toEpochMilliseconds()
    val endTime = startTime + 1.days.inWholeMilliseconds
    return flow {
      emitAll(
        db().fsdbQueries.getPermits(group, startTime, endTime).asFlow().mapToList(Dispatchers.IO)
      )
    }
  }

  private suspend fun fetchCsv(area: Area): Path? {
    val targetPath = appDirs.userCache / "${area.areaName}.csv"
    val downloaded =
      downloadFile(
        area.csvUrl,
        targetPath,
        allowCachedVersion = false,
        // Lie and say we're a browser. NYC parks doesn't like bots
        // Can't really easily get a "real" UA without spinning up UI and doing async JS calls
        // on iOS.
        userAgent =
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3",
      )
    return targetPath.takeIf { downloaded }
  }

  private suspend fun downloadFile(
    url: String,
    targetPath: Path,
    allowCachedVersion: Boolean,
    userAgent: String? = null,
  ): Boolean {
    if (appDirs.fs.exists(targetPath)) {
      if (allowCachedVersion) {
        return true
      }
      appDirs.delete(targetPath)
    }

    appDirs.touch(targetPath)

    try {
      appDirs.fs.appendingSink(targetPath).buffer().use { sink ->
        client()
          .prepareGet(url) { userAgent?.let { userAgent(it) } }
          .execute { httpResponse ->
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
      println("Failed to download file:\n${e.stackTraceToString()}")
      appDirs.delete(targetPath)
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

  private suspend fun FSDatabase.populateDbFrom(area: Area): Boolean {
    val now = System.now()

    val csvFile = fetchCsv(area) ?: return false

    // One single transaction for all ops so it's atomic
    transaction {
      // Clear existing permits if we have new ones
      fsdbQueries.deleteAreaPermits(area.areaName)

      // Insert the new entries
      appDirs.fs.source(csvFile).buffer().useLines { lines ->
        lines.drop(1).forEach { line ->
          val lineSegments = line.split(",").map { it.removeSurrounding("\"").trim() }
          // Sometimes the city just breaks a specific park's permits and return a CSV that says
          // "There is no field usage information available for this park."
          if (lineSegments.size < 7) {
            println("Skipping broken CSV entry $line ($lineSegments) in area $area")
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
            println("Skipping zero-duration permit: $line")
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
        }
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
