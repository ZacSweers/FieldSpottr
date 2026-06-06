// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.generator

import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.Areas
import dev.zacsweers.fieldspottr.data.AvailabilityAreaFeed
import dev.zacsweers.fieldspottr.data.AvailabilityFeedRow
import dev.zacsweers.fieldspottr.data.AvailabilityManifest
import dev.zacsweers.fieldspottr.data.AvailabilityManifestArea
import dev.zacsweers.fieldspottr.data.Field
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val NYC_LIVE_URL = "https://www.nycgovparks.org/api/athletic-fields"
private const val NYC_PARKS_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"

private val nyZone = ZoneId.of("America/New_York")
private val csvFormatter = DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.US)
private val defaultLiveDays = 7L

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  prettyPrintIndent = "  "
  encodeDefaults = true
}

fun main(args: Array<String>) = runBlocking {
  val options = args.options()
  Files.createDirectories(options.outputRoot)

  val areas = Areas.default
  options.outputRoot.resolve("areas.json").writeJson(areas)

  val generatedAt: Long? = null
  val today = LocalDate.now(nyZone)
  val client = HttpClient(OkHttp)
  try {
    val manifestAreas = mutableListOf<AvailabilityManifestArea>()
    val feedsRoot = options.outputRoot.resolve("availability").resolve("areas")
    Files.createDirectories(feedsRoot)

    areas.entries.sortedBy(Area::areaName).forEach { area ->
      val feed = generateFeed(client, area, today, options.liveDays, generatedAt)
      val areaId = area.areaName.slug()
      val relativePath = "availability/areas/$areaId.json"
      val feedPath = options.outputRoot.resolve(relativePath)
      val feedJson = json.encodeToString(feed)
      Files.writeString(feedPath, feedJson)
      manifestAreas +=
        AvailabilityManifestArea(
          areaName = area.areaName,
          areaId = areaId,
          path = relativePath,
          hash = feed.contentHash(),
          generatedAt = feed.generatedAt,
        )
    }

    options.outputRoot
      .resolve("availability")
      .resolve("manifest.json")
      .writeJson(AvailabilityManifest(generatedAt = generatedAt, areas = manifestAreas))
  } finally {
    client.close()
  }
}

private data class Options(val outputRoot: Path, val liveDays: Long)

private fun Array<String>.options(): Options {
  return Options(outputRoot = outputRoot(), liveDays = longOption("live-days") ?: defaultLiveDays)
}

private fun Array<String>.outputRoot(): Path {
  stringOption("output")?.let { return Path.of(it) }
  return Path.of(".")
}

private fun Array<String>.stringOption(name: String): String? {
  val prefix = "--$name="
  firstOrNull { it.startsWith(prefix) }?.let { return it.substringAfter("=") }
  val index = indexOf("--$name")
  return if (index == -1) null else get(index + 1)
}

private fun Array<String>.longOption(name: String): Long? {
  return stringOption(name)?.toLong()
}

private suspend fun generateFeed(
  client: HttpClient,
  area: Area,
  today: LocalDate,
  liveDays: Long,
  generatedAt: Long?,
): AvailabilityAreaFeed {
  val rows =
    (area.csvUrl?.let { csvUrl -> client.fetchNycCsv(csvUrl).toAvailabilityRows(area) }.orEmpty() +
        area.toBbpRows() +
        client.fetchNycLiveRows(area, today, liveDays))
      .mergeAdjacentRows()
      .sortedWith(
        compareBy<AvailabilityFeedRow> { it.groupName }
          .thenBy { it.fieldId }
          .thenBy { it.start }
          .thenBy { it.end }
          .thenBy { it.kind }
          .thenBy { it.title }
      )
  return AvailabilityAreaFeed(areaName = area.areaName, generatedAt = generatedAt, rows = rows)
}

private suspend fun HttpClient.fetchNycCsv(url: String): String {
  return get(url) {
      header(HttpHeaders.UserAgent, NYC_PARKS_USER_AGENT)
      header(HttpHeaders.Accept, "text/csv")
    }
    .body()
}

internal fun String.toAvailabilityRows(area: Area): List<AvailabilityFeedRow> {
  return lineSequence()
    .drop(1)
    .mapNotNull { line ->
      val segments = line.split(",").map { it.removeSurrounding("\"").trim() }
      if (segments.size < 7) return@mapNotNull null
      val start = segments[0]
      val end = segments[1]
      val field = segments[2]
      val type = segments[3]
      val title = segments[4]
      val org = segments[5]
      val status = segments[6]
      val mappedField = area.fieldMappings[field] ?: return@mapNotNull null
      val startMillis = start.toNyEpochMillis()
      val endMillis = end.toNyEpochMillis()
      if (startMillis == endMillis) return@mapNotNull null
      AvailabilityFeedRow(
        areaName = area.areaName,
        groupName = mappedField.group,
        fieldId = field,
        start = startMillis,
        end = endMillis,
        title = title,
        org = org,
        status = status,
        kind = type,
        sourceId = "nyc-parks-csv:${area.areaName}",
      )
    }
    .toList()
}

private suspend fun HttpClient.fetchNycLiveRows(
  area: Area,
  today: LocalDate,
  liveDays: Long,
): List<AvailabilityFeedRow> {
  if (liveDays <= 0) return emptyList()

  val windowEndExclusive = today.plusDays(liveDays)
  val rows = mutableListOf<AvailabilityFeedRow>()
  for (group in area.fieldGroups) {
    for (field in group.fields) {
      val apiLocationId = field.apiLocationId ?: continue
      var date = today
      while (date < windowEndExclusive) {
        val response =
          try {
            get("$NYC_LIVE_URL?location=$apiLocationId&date=$date") {
                header(HttpHeaders.UserAgent, NYC_PARKS_USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
              }
              .body<String>()
          } catch (e: Exception) {
            System.err.println("Failed to fetch NYC live data for $apiLocationId on $date: $e")
            date = date.plusDays(7)
            continue
          }
        rows += response.toNycLiveRows(area, group.name, field, today, windowEndExclusive)
        date = date.plusDays(7)
      }
    }
  }
  return rows
}

internal fun String.toNycLiveRows(
  area: Area,
  groupName: String,
  field: Field,
  startDateInclusive: LocalDate,
  endDateExclusive: LocalDate,
): List<AvailabilityFeedRow> {
  val response = json.decodeFromString<LivePermitResponse>(this)
  return response.availability.mapNotNull { (epochSecondsString, slot) ->
    val epochSeconds = epochSecondsString.toLongOrNull() ?: return@mapNotNull null
    val start = Instant.ofEpochSecond(epochSeconds).atZone(nyZone)
    if (start.toLocalDate() < startDateInclusive || start.toLocalDate() >= endDateExclusive) {
      return@mapNotNull null
    }
    val end = start.plusMinutes(30)
    val block = slot.toLiveBlock()
    if (block != null) {
      AvailabilityFeedRow(
        areaName = area.areaName,
        groupName = groupName,
        fieldId = field.name,
        start = start.toInstant().toEpochMilli(),
        end = end.toInstant().toEpochMilli(),
        title = block.title,
        org = block.org,
        status = block.status,
        kind = "NYC live",
        sourceId = "nyc-parks-live:${field.apiLocationId}",
      )
    } else {
      slot.toLiveAdvisory()?.let { advisory ->
        AvailabilityFeedRow(
          areaName = area.areaName,
          groupName = groupName,
          fieldId = field.name,
          start = start.toInstant().toEpochMilli(),
          end = end.toInstant().toEpochMilli(),
          title = "Pending permits",
          status = advisory,
          kind = "advisory",
          sourceId = "nyc-parks-live:${field.apiLocationId}",
          advisoryText = advisory,
        )
      }
    }
  }
}

private fun LivePermitSlot.toLiveBlock(): LiveBlock? {
  return when {
    permitIsForOverlappingField == true ->
      LiveBlock(
        title = "Overlapping field permit",
        org = permitHolder ?: "",
        status = permitNumber?.let { "Permit #$it" } ?: "Overlapping field",
      )

    inSeason == false -> LiveBlock(title = "Out of season", org = "", status = "Closed")
    permitType == "Construction" ->
      LiveBlock(title = "Construction", org = permitHolder ?: "", status = "Closed")

    permitType == "Special Event" ->
      LiveBlock(title = "Special event", org = permitHolder ?: "", status = "Closed")

    isIssued == false ->
      LiveBlock(title = "Pending final approval", org = permitHolder ?: "", status = "Pending")

    permitHolder != null || permitType != null || permitNumber != null ->
      LiveBlock(
        title = permitHolder ?: permitType ?: "Issued permit",
        org = permitHolder ?: "",
        status = permitNumber?.let { "Permit #$it" } ?: permitType ?: "Issued permit",
      )

    else -> null
  }
}

private fun LivePermitSlot.toLiveAdvisory(): String? {
  val pending = numPendingPermits ?: return null
  return if (pending > 0) "$pending pending permit${if (pending == 1) "" else "s"}" else null
}

private data class LiveBlock(val title: String, val org: String, val status: String)

@Serializable
private data class LivePermitResponse(val availability: Map<String, LivePermitSlot> = emptyMap())

@Serializable
private data class LivePermitSlot(
  @SerialName("in_season") val inSeason: Boolean? = null,
  @SerialName("is_issued") val isIssued: Boolean? = null,
  @SerialName("num_pending_permits") val numPendingPermits: Int? = null,
  @SerialName("permit_holder") val permitHolder: String? = null,
  @SerialName("permit_is_for_overlapping_field") val permitIsForOverlappingField: Boolean? = null,
  @SerialName("permit_number") val permitNumber: Long? = null,
  @SerialName("permit_type") val permitType: String? = null,
)

private fun Area.toBbpRows(): List<AvailabilityFeedRow> {
  if (areaName != "Brooklyn Bridge Park") return emptyList()
  return generateBbpPier5Rows()
}

internal fun generateBbpPier5Rows(): List<AvailabilityFeedRow> {
  val validFrom = LocalDate.of(2026, 6, 1)
  val validTo = LocalDate.of(2026, 8, 31)
  return generateSequence(validFrom) { it.plusDays(1) }
    .takeWhile { !it.isAfter(validTo) }
    .flatMap { date ->
      bbpPier5Schedule.getValue(date.dayOfWeek).flatMap { block ->
        block.fieldIds.map { fieldId ->
          AvailabilityFeedRow(
            areaName = "Brooklyn Bridge Park",
            groupName = "Pier 5 Turf",
            fieldId = fieldId,
            start = date.atTime(block.start).atZone(nyZone).toInstant().toEpochMilli(),
            end = date.atTime(block.end).atZone(nyZone).toInstant().toEpochMilli(),
            title = "Pier 5 turf schedule",
            org = "Brooklyn Bridge Park",
            status = "In use",
            kind = "BBP schedule",
            sourceId = "bbp-pier5-turf-summer-2026",
          )
        }
      }
    }
    .toList()
}

private data class RecurringBlock(
  val fieldIds: List<String>,
  val start: LocalTime,
  val end: LocalTime,
)

private val allPier5Fields = listOf("pier5-field-1", "pier5-field-2", "pier5-field-3")

private val bbpPier5Schedule =
  mapOf(
    DayOfWeek.SUNDAY to listOf(block(allPier5Fields, "08:00", "23:00")),
    DayOfWeek.MONDAY to
      listOf(
        block("pier5-field-1", "09:00", "10:00"),
        block("pier5-field-1", "17:00", "23:00"),
        block("pier5-field-2", "15:00", "23:00"),
        block("pier5-field-3", "19:00", "23:00"),
      ),
    DayOfWeek.TUESDAY to listOf(block(allPier5Fields, "16:00", "23:00")),
    DayOfWeek.WEDNESDAY to
      listOf(
        block("pier5-field-1", "16:00", "23:00"),
        block("pier5-field-2", "17:00", "23:00"),
        block("pier5-field-3", "17:00", "23:00"),
      ),
    DayOfWeek.THURSDAY to
      listOf(
        block("pier5-field-1", "09:00", "10:00"),
        block("pier5-field-1", "18:00", "23:00"),
        block("pier5-field-2", "16:00", "23:00"),
        block("pier5-field-3", "18:00", "23:00"),
      ),
    DayOfWeek.FRIDAY to
      listOf(
        block("pier5-field-1", "19:00", "23:00"),
        block("pier5-field-2", "17:00", "23:00"),
        block("pier5-field-3", "19:00", "23:00"),
      ),
    DayOfWeek.SATURDAY to listOf(block(allPier5Fields, "08:00", "23:00")),
  )

private fun block(fieldId: String, start: String, end: String): RecurringBlock {
  return block(listOf(fieldId), start, end)
}

private fun block(fieldIds: List<String>, start: String, end: String): RecurringBlock {
  return RecurringBlock(fieldIds, LocalTime.parse(start), LocalTime.parse(end))
}

private fun String.toNyEpochMillis(): Long {
  val normalized = replace("a.m.", "AM").replace("p.m.", "PM")
  return LocalDateTime.parse(normalized, csvFormatter).atZone(nyZone).toInstant().toEpochMilli()
}

private fun List<AvailabilityFeedRow>.mergeAdjacentRows(): List<AvailabilityFeedRow> {
  return sortedWith(
      compareBy<AvailabilityFeedRow> { it.areaName.orEmpty() }
        .thenBy { it.groupName }
        .thenBy { it.fieldId }
        .thenBy { it.start }
        .thenBy { it.end }
    )
    .fold(mutableListOf()) { result, row ->
      val previous = result.lastOrNull()
      if (previous != null && previous.canMergeWith(row)) {
        result[result.lastIndex] = previous.copy(end = row.end)
      } else {
        result += row
      }
      result
    }
}

private fun AvailabilityFeedRow.canMergeWith(other: AvailabilityFeedRow): Boolean {
  return areaName == other.areaName &&
    groupName == other.groupName &&
    fieldId == other.fieldId &&
    end == other.start &&
    title == other.title &&
    org == other.org &&
    status == other.status &&
    kind == other.kind &&
    sourceId == other.sourceId &&
    advisoryText == other.advisoryText
}

internal fun AvailabilityAreaFeed.contentHash(): String {
  return json.encodeToString(copy(generatedAt = null)).sha256()
}

private inline fun <reified T> Path.writeJson(value: T) {
  Files.writeString(this, json.encodeToString(value))
}

internal fun String.slug(): String {
  return lowercase()
    .map { char -> if (char.isLetterOrDigit()) char else '-' }
    .joinToString("")
    .replace(Regex("-+"), "-")
    .trim('-')
}

private fun String.sha256(): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
  return digest.joinToString("") { byte -> "%02x".format(byte) }
}
