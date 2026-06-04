// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://www.nycgovparks.org/api/athletic-fields"

private val NYC_TIME_ZONE = TimeZone.of("America/New_York")
private val CACHE_TTL = 15.minutes

@Immutable
data class LiveGroupAvailability(
  val checkedAt: Instant,
  val fields: ImmutableMap<Field, LiveFieldAvailability>,
) {
  companion object {
    fun empty(checkedAt: Instant): LiveGroupAvailability {
      return LiveGroupAvailability(checkedAt, persistentMapOf())
    }
  }
}

@Immutable
data class LiveFieldAvailability(
  val field: Field,
  val blocks: ImmutableList<LivePermitBlock>,
  val advisories: ImmutableList<LivePermitAdvisory>,
)

@Immutable
data class LivePermitBlock(
  val startSlot: Int,
  val endSlot: Int,
  val title: String,
  val org: String,
  val status: String,
  val isOverlap: Boolean,
) {
  val durationSlots: Int
    get() = endSlot - startSlot
}

@Immutable
data class LivePermitAdvisory(
  val startSlot: Int,
  val endSlot: Int,
  val message: String,
) {
  val durationSlots: Int
    get() = endSlot - startSlot
}

@Inject
@SingleIn(AppScope::class)
class LivePermitRepository(
  private val json: Json,
  private val client: HttpClient,
  logger: Logger,
) {
  private val logger = logger.withTag("LivePermitRepository")
  private val cache = mutableMapOf<CacheKey, CacheEntry>()

  suspend fun availability(group: FieldGroup, date: LocalDate): LiveGroupAvailability {
    return withContext(Dispatchers.IO) {
      val checkedAt = System.now()
      val fieldsWithApiIds = group.fields.filter { it.apiLocationId != null }
      if (fieldsWithApiIds.isEmpty()) return@withContext LiveGroupAvailability.empty(checkedAt)

      val fields =
        fieldsWithApiIds
          .associateWith { field -> availability(field, date, checkedAt) }
          .toImmutableMap()
      LiveGroupAvailability(checkedAt, fields).withOverlapsFrom(group)
    }
  }

  private suspend fun availability(
    field: Field,
    date: LocalDate,
    checkedAt: Instant,
  ): LiveFieldAvailability {
    val apiLocationId =
      field.apiLocationId ?: error("Missing live API location id for ${field.name}")
    val cacheKey = CacheKey(apiLocationId, date)
    cache[cacheKey]
      ?.takeIf { checkedAt - it.fetchedAt < CACHE_TTL }
      ?.let {
        return it.availability
      }

    val url = "$BASE_URL?location=$apiLocationId&date=$date"
    logger.i { "Fetching live permit availability for ${field.name}: $url" }
    val response =
      client
        .get(url) {
          headers {
            append(HttpHeaders.UserAgent, NYC_PARKS_USER_AGENT)
            append(HttpHeaders.Accept, "application/json")
          }
        }
        .bodyAsText()

    return parseLiveFieldAvailability(field, date, response, json).also { availability ->
      cache[cacheKey] = CacheEntry(checkedAt, availability)
    }
  }

  private data class CacheKey(val apiLocationId: String, val date: LocalDate)

  private data class CacheEntry(
    val fetchedAt: Instant,
    val availability: LiveFieldAvailability,
  )
}

internal fun LiveGroupAvailability.withOverlapsFrom(group: FieldGroup): LiveGroupAvailability {
  val mergedFields =
    group.fields
      .associateWith { field ->
        val direct = fields[field]
        val overlappingBlocks =
          fields
            .filterKeys { other ->
              other != field && other.group == field.group && other.overlapsWith(field)
            }
            .values
            .flatMap { availability -> availability.blocks }
            .filterNot { block -> block.isOverlap }
            .map { block -> block.copy(isOverlap = true) }
            .mergeVisualOverlapBlocks()

        if (direct == null && overlappingBlocks.isEmpty()) {
          null
        } else {
          LiveFieldAvailability(
            field = field,
            blocks =
              (direct?.blocks.orEmpty() + overlappingBlocks).mergeVisualBlocks().toImmutableList(),
            advisories = direct?.advisories.orEmpty().toImmutableList(),
          )
        }
      }
      .mapNotNull { (field, availability) -> availability?.let { field to it } }
      .toMap()
      .toImmutableMap()

  return copy(fields = mergedFields)
}

private fun List<LivePermitBlock>.mergeVisualBlocks(): List<LivePermitBlock> {
  if (isEmpty()) return emptyList()

  val directBlocks = filterNot { it.isOverlap }
  val overlapBlocks =
    filter { it.isOverlap }
      .filterNot { overlap -> directBlocks.any { direct -> overlap.overlaps(direct) } }
      .mergeVisualOverlapBlocks()

  return (directBlocks + overlapBlocks).sortedWith(
    compareBy<LivePermitBlock> { it.startSlot }.thenBy { it.endSlot }
  )
}

private fun List<LivePermitBlock>.mergeVisualOverlapBlocks(): List<LivePermitBlock> {
  if (isEmpty()) return emptyList()
  return sortedWith(compareBy<LivePermitBlock> { it.startSlot }.thenBy { it.endSlot }).fold(
    mutableListOf()
  ) { result, block ->
    val previous = result.lastOrNull()
    if (
      previous != null &&
        previous.isOverlap &&
        block.isOverlap &&
        previous.endSlot >= block.startSlot
    ) {
      result[result.lastIndex] = previous.copy(endSlot = maxOf(previous.endSlot, block.endSlot))
    } else {
      result += block
    }
    result
  }
}

private fun LivePermitBlock.overlaps(other: LivePermitBlock): Boolean {
  return startSlot < other.endSlot && endSlot > other.startSlot
}

internal fun parseLiveFieldAvailability(
  field: Field,
  date: LocalDate,
  responseBody: String,
  json: Json,
): LiveFieldAvailability {
  val response = json.decodeFromString<LivePermitResponse>(responseBody)
  val blocks = mutableListOf<SlotBlock>()
  val advisories = mutableListOf<SlotAdvisory>()

  response.availability.forEach { (epochSecondsString, slot) ->
    val epochSeconds = epochSecondsString.toLongOrNull() ?: return@forEach
    val startDateTime = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(NYC_TIME_ZONE)
    if (startDateTime.date != date) return@forEach

    val startSlot = startDateTime.hour * 2 + if (startDateTime.minute >= 30) 1 else 0
    val endSlot = startSlot + 1
    val block = slot.toBlock(startSlot, endSlot)
    if (block != null) {
      blocks += block
    } else {
      slot.toAdvisory(startSlot, endSlot)?.let(advisories::add)
    }
  }

  return LiveFieldAvailability(
    field = field,
    blocks = blocks.mergeBlocks().map(SlotBlock::toLivePermitBlock).toImmutableList(),
    advisories =
      advisories.mergeAdvisories().map(SlotAdvisory::toLivePermitAdvisory).toImmutableList(),
  )
}

private fun LivePermitSlot.toBlock(startSlot: Int, endSlot: Int): SlotBlock? {
  val reason =
    when {
      permitIsForOverlappingField == true -> LiveBlockReason.OVERLAPPING_FIELD
      inSeason == false -> LiveBlockReason.OUT_OF_SEASON
      permitType == "Construction" -> LiveBlockReason.CONSTRUCTION
      permitType == "Special Event" -> LiveBlockReason.SPECIAL_EVENT
      isIssued == false -> LiveBlockReason.PENDING_FINAL_APPROVAL
      permitHolder != null || permitType != null || permitNumber != null ->
        LiveBlockReason.ISSUED_PERMIT
      else -> null
    } ?: return null

  return SlotBlock(
    startSlot = startSlot,
    endSlot = endSlot,
    reason = reason,
    permitNumber = permitNumber,
    permitHolder = permitHolder,
    permitType = permitType,
    isOverlap = permitIsForOverlappingField == true,
  )
}

private fun LivePermitSlot.toAdvisory(startSlot: Int, endSlot: Int): SlotAdvisory? {
  val count = numPendingPermits?.takeIf { it > 0 } ?: return null
  return SlotAdvisory(startSlot, endSlot, "There are $count pending permit(s) for this field.")
}

private fun List<SlotBlock>.mergeBlocks(): List<SlotBlock> {
  if (isEmpty()) return emptyList()
  return sortedWith(compareBy<SlotBlock> { it.startSlot }.thenBy { it.endSlot }).fold(
    mutableListOf()
  ) { result, block ->
    val previous = result.lastOrNull()
    if (previous != null && previous.canMergeWith(block)) {
      result[result.lastIndex] = previous.copy(endSlot = block.endSlot)
    } else {
      result += block
    }
    result
  }
}

private fun SlotBlock.canMergeWith(other: SlotBlock): Boolean {
  return endSlot == other.startSlot &&
    reason == other.reason &&
    (isOverlap && other.isOverlap ||
      permitNumber == other.permitNumber &&
        permitHolder == other.permitHolder &&
        permitType == other.permitType &&
        isOverlap == other.isOverlap)
}

private fun List<SlotAdvisory>.mergeAdvisories(): List<SlotAdvisory> {
  if (isEmpty()) return emptyList()
  return sortedWith(compareBy<SlotAdvisory> { it.startSlot }.thenBy { it.endSlot }).fold(
    mutableListOf()
  ) { result, advisory ->
    val previous = result.lastOrNull()
    if (
      previous != null &&
        previous.endSlot == advisory.startSlot &&
        previous.message == advisory.message
    ) {
      result[result.lastIndex] = previous.copy(endSlot = advisory.endSlot)
    } else {
      result += advisory
    }
    result
  }
}

private fun SlotBlock.toLivePermitBlock(): LivePermitBlock {
  return LivePermitBlock(
    startSlot = startSlot,
    endSlot = endSlot,
    title = title,
    org = permitHolder.orEmpty(),
    status = status,
    isOverlap = isOverlap,
  )
}

private fun SlotAdvisory.toLivePermitAdvisory(): LivePermitAdvisory {
  return LivePermitAdvisory(startSlot, endSlot, message)
}

private val SlotBlock.title: String
  get() =
    when (reason) {
      LiveBlockReason.PENDING_FINAL_APPROVAL -> "Pending approval"
      LiveBlockReason.OVERLAPPING_FIELD -> "Overlapping permit"
      LiveBlockReason.OUT_OF_SEASON -> "Out of season"
      LiveBlockReason.CONSTRUCTION -> "Construction"
      LiveBlockReason.SPECIAL_EVENT -> "Special event"
      LiveBlockReason.ISSUED_PERMIT -> permitType ?: "Live permit"
    }

private val SlotBlock.status: String
  get() =
    when (reason) {
      LiveBlockReason.PENDING_FINAL_APPROVAL -> "Pending final approval"
      LiveBlockReason.OVERLAPPING_FIELD -> "Unavailable due to an overlapping field permit"
      LiveBlockReason.OUT_OF_SEASON -> "Unavailable outside the current permit season"
      LiveBlockReason.CONSTRUCTION -> "Unavailable due to planned construction"
      LiveBlockReason.SPECIAL_EVENT -> "Unavailable due to a special event permit"
      LiveBlockReason.ISSUED_PERMIT ->
        buildString {
          append("Issued permit")
          permitNumber?.let { append(" #").append(it) }
        }
    }

private data class SlotBlock(
  val startSlot: Int,
  val endSlot: Int,
  val reason: LiveBlockReason,
  val permitNumber: Long?,
  val permitHolder: String?,
  val permitType: String?,
  val isOverlap: Boolean,
)

private data class SlotAdvisory(
  val startSlot: Int,
  val endSlot: Int,
  val message: String,
)

private enum class LiveBlockReason {
  PENDING_FINAL_APPROVAL,
  OVERLAPPING_FIELD,
  OUT_OF_SEASON,
  CONSTRUCTION,
  SPECIAL_EVENT,
  ISSUED_PERMIT,
}

@Serializable
private data class LivePermitResponse(
  val availability: Map<String, LivePermitSlot> = emptyMap(),
  val fieldName: String? = null,
  val close: Map<String, String> = emptyMap(),
)

@Serializable
private data class LivePermitSlot(
  @SerialName("in_season") val inSeason: Boolean? = null,
  @SerialName("permit_is_for_overlapping_field") val permitIsForOverlappingField: Boolean? = null,
  @SerialName("num_pending_permits") val numPendingPermits: Int? = null,
  @SerialName("permit_number") val permitNumber: Long? = null,
  @SerialName("is_issued") val isIssued: Boolean? = null,
  @SerialName("permit_holder") val permitHolder: String? = null,
  @SerialName("permit_type") val permitType: String? = null,
)
