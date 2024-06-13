// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.userAgent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import java.util.Objects
import kotlin.io.path.absolutePathString
import kotlin.io.path.appendBytes
import kotlin.io.path.createFile
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
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
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.buffer

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

internal enum class Area(
  val areaName: String,
  val csvUrl: String,
  val fieldGroups: List<FieldGroup>,
) {
  ERP(
    "ERP",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M144/csv",
    listOf(
      FieldGroup(
        "Track",
        listOf(
          Field("Soccer-01A East 6th Street", "Track Field 1", "Track"),
          Field("Soccer-01 East 6th Street", "Track Field 2", "Track"),
          Field("Soccer-01B East 6th Street", "Track Field 3", "Track"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Field 6",
        listOf(
          Field("Baseball-06", "Field 6 (Baseball)", "Field 6"),
          Field("Softball-05", "Field 6 (Baseball)", "Field 6"),
          Field("Soccer-03 Houston St & FDR", "Field 6 (Outfield)", "Field 6"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Baruch",
        listOf(
          Field("Baruch Playground - Softball-01", "Baruch Soccer Field 1", "Baruch"),
          Field("Baruch Playground - Football-01", "Baruch Soccer Field 1", "Baruch"),
          Field("Baruch Playground - Football-02", "Baruch Soccer Field 2", "Baruch"),
          Field("Baruch Playground - Softball-02", "Baruch Soccer Field 2", "Baruch"),
        ),
        "ERP",
      ),
      FieldGroup(
        "Grand Street",
        listOf(
          Field("Soccer-02 Grand Street", "Grand Street", "Grand Street"),
          Field("Grand Street Mini Field-Soccer-03", "Grand Street Mini Field", "Grand Street"),
        ),
        "ERP",
      ),
      FieldGroup("Pier 42", listOf(Field("Pier 42 - Soccer-01", "Pier 42", "Pier 42")), "ERP"),
      FieldGroup(
        "Corlears Hook",
        listOf(
          Field("Corlears Hook Park - Soccer-01", "Corlears Hook (Soccer)", "Corlears Hook"),
          Field("Corlears Hook Park - Softball-01", "Corlears Hook (Softball)", "Corlears Hook"),
        ),
        "ERP",
      ),
    ),
  ),
  PETERS_FIELD(
    "Peter's Field",
    "https://www.nycgovparks.org/permits/field-and-court/issued/M227/csv",
    listOf(
      FieldGroup(
        "Peter's Field",
        listOf(
          Field("Soccer-01", "Peter's Field (Soccer)", "Peter's Field"),
          Field("Softball-01", "Peter's Field (Softball)", "Peter's Field"),
        ),
        "Peter's Field",
      )
    ),
  );

  val fieldMappings: Map<String, Field> by lazy {
    fieldGroups
      .flatMap(FieldGroup::fields)
      .map { field -> field.name to field }
      .associate { it.first to it.second }
  }

  companion object {
    val groups = entries.flatMap { it.fieldGroups }.associateBy { it.name }
  }
}

class PermitRepository(
  private val sqlDriverFactory: SqlDriverFactory,
  private val appDirs: FSAppDirs,
) {
  private val client = HttpClient {}
  private val db by lazy {
    val driver = sqlDriverFactory.create(FSDatabase.Schema, "fs.db")
    FSDatabase(driver).also { FSDatabase.Schema.create(driver) }
  }

  // TODO return a flow emitting log updates?
  suspend fun populateDb() =
    withContext(Dispatchers.IO) {
      for (area in Area.entries) {
        db.populateDbFrom(area)
      }

      val allPermits = db.fsdbQueries.getAllPermits().executeAsList()
      val uniqueFields = allPermits.distinctBy { Objects.hash(it.fieldId, it.area) }
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
      - Earliest permit (local): ${earliestPermit?.start?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(NYC_TZ) }}
      - Latest permit (local): ${latestPermit?.start?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(NYC_TZ) }}
    """
          .trimIndent()
      println(message)
      println(
        "Unique fields: ${uniqueFields.size}.\n${uniqueFields.joinToString("\n") { it.fieldId }}"
      )
    }

  // TODO group by mapped field
  suspend fun loadPermits(date: LocalDate, group: String): List<DbPermit> =
    withContext(Dispatchers.IO) {
      val startTime = date.atStartOfDayIn(NYC_TZ).toEpochMilliseconds()
      val endTime = startTime + 1.days.inWholeMilliseconds
      println("Loading permits from DB for $date")
      println("Start time is $startTime")
      println("End time is $endTime")
      db.fsdbQueries.getPermits(group, startTime, endTime).executeAsList()
    }

  private suspend fun getOrFetchCsv(area: Area): Pair<Boolean, Path> {
    val targetPath = appDirs.userCache / "${area.areaName}.csv"
    if (appDirs.fs.exists(targetPath)) {
      if ((appDirs.fs.metadata(targetPath).size ?: 0) > 0) {
        // If less than a week old use it
        appDirs.fs.metadata(targetPath).lastModifiedAtMillis?.let { lastModifiedAtMillis ->
          if (lastModifiedAtMillis > System.now().minus(7.days).toEpochMilliseconds()) {
            return true to targetPath
          }
        }
      }
      appDirs.fs.delete(targetPath)
    }
    // TODO KMP this
    val file = targetPath.toNioPath()
    file.createFile()
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
            file.appendBytes(bytes)
          }
        }
        println("Saved CSV to ${file.absolutePathString()}")
      }
    return false to targetPath
  }

  private val BufferedSource.lines: Sequence<String>
    get() = generateSequence(::readUtf8Line)

  private fun BufferedSource.useLines(body: (Sequence<String>) -> Unit) {
    use { body(lines) }
  }

  private suspend fun FSDatabase.populateDbFrom(area: Area) {
    // Check area last update in the DB. If it's less than a week old, skip it
    val lastUpdate = transactionWithResult {
      fsdbQueries.lastAreaUpdate(area.areaName).executeAsOneOrNull()
    }
    val now = System.now().minus(7.days)
    if (lastUpdate != null && Instant.fromEpochMilliseconds(lastUpdate) > now.minus(7.days)) {
      println("Skipping ${area.areaName} as it's up to date")
      return
    }

    println("Populating DB from ${area.areaName}")
    val (upToDate, csvFile) = getOrFetchCsv(area)
    //    if (upToDate) return
    println("Deleting existing permits")
    transaction { fsdbQueries.deleteAreaPermits(area.areaName) }

    FileSystem.SYSTEM.source(csvFile).buffer().useLines { lines ->
      lines.drop(1).forEach { line ->
        val (start, end, field, type, name, org, status) =
          line.split(",").map { it.removeSurrounding("\"") }
        if (field !in area.fieldMappings) {
          // Irrelevant field
          return@forEach
        }
        val group = area.fieldMappings.getValue(field).group
        val recordId = Objects.hash(area.areaName, group, start, end, field)

        val startTime = LocalDateTime.parse(start, FORMATTER)
        val endTime = LocalDateTime.parse(end, FORMATTER)

        println("Adding permit for $field from ${area.areaName} in $group")
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

        // Now assert it went through
        // TODO remove
        transaction {
          val permit = fsdbQueries.getPermit(recordId.toLong()).executeAsOneOrNull()
          requireNotNull(permit) { "Permit not found for $recordId" }
        }
      }
    }
    // Log last update time
    transaction { fsdbQueries.updateAreaOp(DbArea(area.areaName, now.toEpochMilliseconds())) }
  }
}

private operator fun <E> List<E>.component6(): E {
  return this[5]
}

private operator fun <E> List<E>.component7(): E {
  return this[6]
}
