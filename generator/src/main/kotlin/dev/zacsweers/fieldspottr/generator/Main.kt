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
private const val HRP_FIELDS_URL = "https://hudsonriverpark.org/visit/events/permits/fields/"
private const val NYC_PARKS_USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"
private const val BROWSER_LIKE_USER_AGENT =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

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
      val areaId = area.areaName.slug()
      val relativePath = "availability/areas/$areaId.json"
      val feedPath = options.outputRoot.resolve(relativePath)
      val existingFeed = feedPath.decodeFeedOrNull()
      val feed = generateFeed(client, area, today, options.liveDays, generatedAt, options, existingFeed)
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

private data class Options(val outputRoot: Path, val liveDays: Long, val hrpSourceFile: Path?)

private fun Array<String>.options(): Options {
  return Options(
    outputRoot = outputRoot(),
    liveDays = longOption("live-days") ?: defaultLiveDays,
    hrpSourceFile = stringOption("hrp-source-file")?.let(Path::of),
  )
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
  options: Options,
  existingFeed: AvailabilityAreaFeed?,
): AvailabilityAreaFeed {
  val hrpRows = client.fetchHrpRows(area, options.hrpSourceFile)
  val preservedHrpRows =
    if (area.areaName == "West Side Highway" && hrpRows == null) {
      existingFeed?.rows.orEmpty().also {
        if (it.isNotEmpty()) {
          System.err.println("Preserving previous West Side Highway feed rows")
        }
      }
    } else {
      emptyList()
    }
  val rows =
    (area.csvUrl?.let { csvUrl -> client.fetchNycCsv(csvUrl).toAvailabilityRows(area) }.orEmpty() +
        area.toBbpRows() +
        (hrpRows ?: preservedHrpRows) +
        client.fetchNycLiveRows(area, today, liveDays))
      .mergeAdjacentRows()
      .sortedWith(feedRowComparator)
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

private suspend fun HttpClient.fetchHrpRows(
  area: Area,
  sourceFile: Path?,
): List<AvailabilityFeedRow>? {
  if (area.areaName != "West Side Highway") return emptyList()
  val response =
    if (sourceFile != null && Files.exists(sourceFile)) {
      Files.readString(sourceFile)
    } else {
      try {
        get(HRP_FIELDS_URL) {
            header(HttpHeaders.UserAgent, BROWSER_LIKE_USER_AGENT)
            header(HttpHeaders.Accept, "text/html")
          }
          .body<String>()
      } catch (e: Exception) {
        System.err.println("Failed to fetch Hudson River Park field schedule: $e")
        return null
      }
    }
  if (response.isCloudflareBlockPage()) {
    System.err.println("Hudson River Park field schedule fetch was blocked")
    return null
  }
  val rows = response.toHrpRows(area)
  if (rows.isEmpty()) {
    System.err.println("Hudson River Park field schedule produced no rows")
    return null
  }
  return rows
}

internal fun String.toHrpRows(area: Area): List<AvailabilityFeedRow> {
  if (area.areaName != "West Side Highway") return emptyList()
  val fieldIdsByTitle = area.fieldGroups.flatMap { group -> group.fields.map { it.name to it } }.toMap()
  val text = toScheduleText()
  val year = Regex("""\b(\d{4})\b""").findAll(text).lastOrNull()?.value?.toIntOrNull()
  val rows = mutableListOf<AvailabilityFeedRow>()
  val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
  for ((index, line) in lines.withIndex()) {
    val field = hrpFields[line.removePrefix("Image:").trim()] ?: continue
    val scheduleLines =
      lines.drop(index + 1).takeWhile { !it.startsWith("Image:") && it !in hrpFields.keys }
    val dates = scheduleLines.dateColumns(year ?: continue)
    if (dates.isEmpty()) continue
    rows += scheduleLines.toHrpScheduleRows(field, fieldIdsByTitle, dates)
  }
  return rows
}

private fun String.toScheduleText(): String {
  return replace(Regex("""(?is)<img\b[^>]*alt=["']([^"']+)["'][^>]*>"""), "\nImage: $1\n")
    .replace(Regex("""(?is)<br\s*/?>"""), "\n")
    .replace(Regex("""(?is)</t[dh]>"""), " | ")
    .replace(Regex("""(?is)</tr>"""), "\n")
    .replace(Regex("""(?is)<[^>]+>"""), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&#8211;", "–")
    .replace("&#8212;", "–")
    .replace(Regex("""[ \t]+"""), " ")
}

internal fun String.isCloudflareBlockPage(): Boolean {
  if (!contains("Cloudflare", ignoreCase = true)) return false
  val blockSignals =
    listOf(
      "Attention Required",
      "Sorry, you have been blocked",
      "cf-error-details",
      "cf-wrapper",
      "Cloudflare Ray ID",
      "Why have I been blocked?",
      "Performance & security by",
    )
  return blockSignals.count { contains(it, ignoreCase = true) } >= 2
}

private data class HrpField(
  val fieldId: String,
  val title: String,
  val sourceTitle: String = title,
)

private val hrpFields =
  listOf(
      HrpField("pier25-turf-field", "Pier 25 Turf Field"),
      HrpField("pier26-sports-court", "Pier 26 Sports Court"),
      HrpField("pier40-courtyard-east", "Pier 40 Courtyard East"),
      HrpField("pier40-courtyard-west", "Pier 40 Courtyard West"),
      HrpField("pier40-indoor-youth-field", "Pier 40 Indoor Youth Field"),
      HrpField("pier40-rooftop-field-1", "Pier 40 Rooftop Field #1"),
      HrpField("pier40-rooftop-field-2", "Pier 40 Rooftop Field #2"),
      HrpField("pier40-rooftop-field-2", "Pier 40 Rooftop Field #2 – Youth Only", "Pier 40 Rooftop Field #2"),
      HrpField("gansevoort-peninsula-athletic-field", "Gansevoort Peninsula Athletic Field"),
      HrpField("gansevoort-peninsula-athletic-field", "Gansevoort Peninsula Playing Field"),
      HrpField("chelsea-waterside-athletic-field", "Chelsea Waterside Athletic Field"),
      HrpField("chelsea-waterside-athletic-field", "Chelsea Waterside Field"),
    )
    .associateBy(HrpField::title)

private fun List<String>.dateColumns(year: Int): List<LocalDate> {
  val dateRegex = Regex("""\b(\d{1,2})/(\d{1,2})\b""")
  val tokens = take(12).flatMap { line -> dateRegex.findAll(line).map { it.groupValues } }.take(8)
  if (tokens.isEmpty()) return emptyList()
  var inferredYear = year
  var previousMonth = tokens.first()[1].toInt()
  return tokens.mapIndexed { index, token ->
    val month = token[1].toInt()
    val day = token[2].toInt()
    if (index == 0 && month == 12 && tokens.last()[1].toInt() == 1) inferredYear = year - 1
    if (index > 0 && month < previousMonth) inferredYear += 1
    previousMonth = month
    LocalDate.of(inferredYear, month, day)
  }
}

private fun List<String>.toHrpScheduleRows(
  hrpField: HrpField,
  fieldsById: Map<String, Field>,
  dates: List<LocalDate>,
): List<AvailabilityFeedRow> {
  val field = fieldsById[hrpField.fieldId] ?: return emptyList()
  val rows = mutableListOf<AvailabilityFeedRow>()
  val pendingStarts = Array<LocalTime?>(dates.size) { null }
  for (line in this) {
    if (!line.contains("|")) continue
    val cells = line.split("|").map { it.trim() }
    if (cells.firstOrNull()?.toLocalTimeOrNull() == null) continue
    cells.drop(1).take(dates.size).forEachIndexed { column, cell ->
      val normalized = cell.removeSuffix(" |").trim()
      val completeRange = normalized.toCompleteTimeRange()
      when {
        completeRange != null -> {
          rows += hrpField.toRow(field, dates[column], completeRange.first, completeRange.second)
          pendingStarts[column] = null
        }
        normalized.endsWith("–") || normalized.endsWith("-") -> {
          pendingStarts[column] = normalized.dropLast(1).trim().toLocalTimeOrNull()
        }
        normalized.toLocalTimeOrNull() != null && pendingStarts[column] != null -> {
          rows +=
            hrpField.toRow(
              field,
              dates[column],
              pendingStarts[column]!!,
              normalized.toLocalTimeOrNull()!!,
            )
          pendingStarts[column] = null
        }
      }
    }
  }
  return rows
}

private fun HrpField.toRow(
  field: Field,
  date: LocalDate,
  start: LocalTime,
  end: LocalTime,
): AvailabilityFeedRow {
  val endDate = if (end <= start) date.plusDays(1) else date
  return AvailabilityFeedRow(
    areaName = "West Side Highway",
    groupName = field.group,
    fieldId = field.name,
    start = date.atTime(start).atZone(nyZone).toInstant().toEpochMilli(),
    end = endDate.atTime(end).atZone(nyZone).toInstant().toEpochMilli(),
    title = sourceTitle,
    org = "Hudson River Park",
    status = "Reserved",
    kind = "HRP weekly schedule",
    sourceId = "hrp-weekly-field-schedule:${fieldId}:${date}",
  )
}

private fun String.toCompleteTimeRange(): Pair<LocalTime, LocalTime>? {
  val match =
    Regex("""^(\d{1,2})(?::(\d{2}))?\s*(AM|PM)?\s*[–-]\s*(\d{1,2})(?::(\d{2}))?\s*(AM|PM)$""")
      .find(this)
      ?: return null
  val endMeridiem = match.groupValues[6]
  val startMeridiem = match.groupValues[3].ifBlank { endMeridiem }
  val start = time(match.groupValues[1], match.groupValues[2], startMeridiem)
  val end = time(match.groupValues[4], match.groupValues[5], endMeridiem)
  return start to end
}

private fun String.toLocalTimeOrNull(): LocalTime? {
  val match = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(AM|PM)$""").find(this) ?: return null
  return time(match.groupValues[1], match.groupValues[2], match.groupValues[3])
}

private fun time(hourText: String, minuteText: String, meridiem: String): LocalTime {
  val hour = hourText.toInt()
  val minute = minuteText.ifBlank { "00" }.toInt()
  val normalizedHour =
    when (meridiem) {
      "AM" -> if (hour == 12) 0 else hour
      "PM" -> if (hour == 12) 12 else hour + 12
      else -> error("Unexpected meridiem $meridiem")
    }
  return LocalTime.of(normalizedHour, minute)
}

private fun String.toNyEpochMillis(): Long {
  val normalized = replace("a.m.", "AM").replace("p.m.", "PM")
  return LocalDateTime.parse(normalized, csvFormatter).atZone(nyZone).toInstant().toEpochMilli()
}

private fun List<AvailabilityFeedRow>.mergeAdjacentRows(): List<AvailabilityFeedRow> {
  return sortedWith(feedRowComparator)
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

private val feedRowComparator =
  compareBy<AvailabilityFeedRow> { it.areaName.orEmpty() }
    .thenBy { it.groupName }
    .thenBy { it.fieldId }
    .thenBy { it.start }
    .thenBy { it.end }
    .thenBy { it.kind }
    .thenBy { it.title }
    .thenBy { it.org }
    .thenBy { it.status }
    .thenBy { it.sourceId.orEmpty() }
    .thenBy { it.advisoryText.orEmpty() }

internal fun AvailabilityAreaFeed.contentHash(): String {
  return json.encodeToString(copy(generatedAt = null)).sha256()
}

private fun Path.decodeFeedOrNull(): AvailabilityAreaFeed? {
  return try {
    if (!Files.exists(this)) return null
    json.decodeFromString<AvailabilityAreaFeed>(Files.readString(this))
  } catch (_: Exception) {
    null
  }
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
