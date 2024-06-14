// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.DbArea
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.SqlDriverFactory
import dev.zacsweers.fieldspottr.delete
import dev.zacsweers.fieldspottr.touch
import dev.zacsweers.fieldspottr.util.component6
import dev.zacsweers.fieldspottr.util.component7
import dev.zacsweers.fieldspottr.util.hashOf
import dev.zacsweers.fieldspottr.util.useLines
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareGet
import io.ktor.http.userAgent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import okio.Path
import okio.buffer
import okio.use

/** The default buffer size when working with buffered streams. */
private const val DEFAULT_BUFFER_SIZE: Int = 8 * 1024

private val FORMATTER =
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

internal val NYC_TZ = TimeZone.of("America/New_York")

data class FieldGroup(val name: String, val fields: List<Field>, val area: String)

data class Field(val name: String, val displayName: String, val group: String)

class PermitRepository(
  private val sqlDriverFactory: SqlDriverFactory,
  private val appDirs: FSAppDirs,
  private val logger: (String) -> Unit = {},
) {
  private val client = HttpClient(CIO)

  private val _db: FSDatabase? = null
  private val dbInitMutex = Mutex()

  private suspend fun db(): FSDatabase {
    return _db
      ?: dbInitMutex.withLock {
        val driver = sqlDriverFactory.create(FSDatabase.Schema, "fs.db")
        FSDatabase(driver)
      }
  }

  // TODO return a flow emitting log updates?
  suspend fun populateDb(forceRefresh: Boolean) {
    val db = db()
    for (area in Area.entries) {
      db.populateDbFrom(area, forceRefresh)
    }

    val allPermits = db.fsdbQueries.getAllPermits().executeAsList()
    val uniqueFields = allPermits.distinctBy { hashOf(it.fieldId, it.area) }
    val earliestPermit = allPermits.minByOrNull { it.start }
    val latestPermit = allPermits.maxByOrNull { it.end }
    val message =
      """
        Populated database with permits from
        - ${Area.entries.size} areas
        - ${uniqueFields.size} unique fields
        - ${allPermits.size} total permits
        - Earliest permit: ${earliestPermit?.start}
        - Latest permit: ${latestPermit?.start}
        - Earliest permit (local): ${
          earliestPermit?.start?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(
              NYC_TZ
            )
          }
        }
        - Latest permit (local): ${
          latestPermit?.start?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(
              NYC_TZ
            )
          }
        }
      """
        .trimIndent()
    logger(message)
    logger(
      "Unique fields: ${uniqueFields.size}.\n${uniqueFields.joinToString("\n") { it.fieldId }}"
    )
  }

  suspend fun loadPermits(date: LocalDate, group: String): List<DbPermit> =
    withContext(Dispatchers.IO) {
      val startTime = date.atStartOfDayIn(NYC_TZ).toEpochMilliseconds()
      val endTime = startTime + 1.days.inWholeMilliseconds
      logger("Loading permits from DB for $date")
      logger("Start time is $startTime")
      logger("End time is $endTime")
      db().fsdbQueries.getPermits(group, startTime, endTime).executeAsList()
    }

  private suspend fun getOrFetchCsv(area: Area, forceRefresh: Boolean): Pair<Boolean, Path> {
    val targetPath = appDirs.userCache / "${area.areaName}.csv"
    // TODO handle offline
    if (appDirs.fs.exists(targetPath)) {
      if (!forceRefresh && (appDirs.fs.metadata(targetPath).size ?: 0) > 0) {
        // If less than a week old use it
        appDirs.fs.metadata(targetPath).lastModifiedAtMillis?.let { lastModifiedAtMillis ->
          if (lastModifiedAtMillis > System.now().minus(7.days).toEpochMilliseconds()) {
            return true to targetPath
          }
        }
      }
      appDirs.delete(targetPath)
    }

    // Create the file
    appDirs.touch(targetPath)

    // TODO write to a tmp file first, copy over on success
    appDirs.fs.appendingSink(targetPath).buffer().use { sink ->
      client
        .prepareGet(area.csvUrl) {
          // Lie and say we're a browser. NYC parks doesn't like bots
          // TODO use a real user agent?
          userAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"
          )
        }
        .execute { httpResponse ->
          val channel = httpResponse.body<ByteReadChannel>()
          while (!channel.isClosedForRead) {
            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
            while (!packet.isEmpty) {
              val bytes = packet.readBytes()
              sink.write(bytes)
            }
          }
          logger("Saved CSV to $targetPath")
        }
    }
    return false to targetPath
  }

  private suspend fun FSDatabase.populateDbFrom(area: Area, forceRefresh: Boolean) =
    withContext(Dispatchers.IO) {
      val now = System.now()
      if (!forceRefresh) {
        // Check area last update in the DB. If it's less than a week old, skip it
        val lastUpdate = transactionWithResult {
          fsdbQueries.lastAreaUpdate(area.areaName).executeAsOneOrNull()
        }
        if (lastUpdate != null && Instant.fromEpochMilliseconds(lastUpdate) > now.minus(7.days)) {
          logger("Skipping ${area.areaName} as it's up to date")
          return@withContext
        }
      }

      logger("Populating DB from ${area.areaName}")
      val (upToDate, csvFile) = getOrFetchCsv(area, forceRefresh)
      if (upToDate) return@withContext
      logger("Deleting existing permits")
      transaction { fsdbQueries.deleteAreaPermits(area.areaName) }

      appDirs.fs.source(csvFile).buffer().useLines { lines ->
        lines.drop(1).forEach { line ->
          val (start, end, field, type, name, org, status) =
            line.split(",").map { it.removeSurrounding("\"") }
          if (field !in area.fieldMappings) {
            // Irrelevant field
            return@forEach
          }
          val group = area.fieldMappings.getValue(field).group
          val recordId = hashOf(area.areaName, group, start, end, field)

          val startTime = LocalDateTime.parse(start, FORMATTER)
          val endTime = LocalDateTime.parse(end, FORMATTER)

          logger("Adding permit for $field from ${area.areaName} in $group")
          transaction {
            fsdbQueries.addPermit(
              DbPermit(
                recordId = recordId.toLong(),
                area = area.areaName,
                groupName = group,
                start = startTime.toInstant(NYC_TZ).toEpochMilliseconds(),
                end = endTime.toInstant(NYC_TZ).toEpochMilliseconds(),
                fieldId = field,
                type = type,
                name = name,
                org = org,
                status = status,
              )
            )
          }
        }
      }
      // Log last update time
      transaction { fsdbQueries.updateAreaOp(DbArea(area.areaName, now.toEpochMilliseconds())) }
    }
}
