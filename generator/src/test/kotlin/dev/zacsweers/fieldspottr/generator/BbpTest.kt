// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.generator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.AvailabilityAreaFeed
import dev.zacsweers.fieldspottr.data.AvailabilityFeedRow
import dev.zacsweers.fieldspottr.data.AvailabilityManifest
import dev.zacsweers.fieldspottr.data.AvailabilityManifestArea
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class BbpTest {
  @Test
  fun `source decoder rejects unknown keys`() {
    val fixture = sourceFixture()
    val sourceJson =
      encodeBbpSource(fixture.source)
        .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1,\n  \"unexpected\": true,")
    Files.writeString(fixture.sourceFile, sourceJson)

    val failure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
      }

    assertThat(failure.message.orEmpty()).contains("unknown key")
  }

  @Test
  fun `source validation checks image URI and hash`() {
    val fixture = sourceFixture()
    val invalidHost =
      fixture.writeSource(
        fixture.source.copy(
          imageUrl =
            "https://brooklynbridgepark.org.evil.example/wp-content/uploads/Pier-5-Turf.png"
        )
      )

    val hostFailure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(invalidHost, fixture.root, testToday)
      }
    assertThat(hostFailure.message.orEmpty())
      .contains("imageUrl host must be brooklynbridgepark.org")

    val wrongPier =
      fixture.writeSource(
        fixture.source.copy(
          imageUrl =
            "https://brooklynbridgepark.org/wp-content/uploads/2025/05/Pier-2-Turf-Summer-2026.png"
        )
      )
    val wrongPierFailure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(wrongPier, fixture.root, testToday)
      }
    assertThat(wrongPierFailure.message.orEmpty())
      .contains("imageUrl is not a Pier 5 turf schedule asset")

    val invalidHash = fixture.writeSource(fixture.source.copy(imageSha256 = "0".repeat(64)))
    val hashFailure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(invalidHash, fixture.root, testToday)
      }
    assertThat(hashFailure.message.orEmpty()).contains("imagePath must use the full image SHA-256")
  }

  @Test
  fun `source validation rejects traversal encoded paths PDFs and extension mismatches`() {
    val imageUrls =
      listOf(
        "https://brooklynbridgepark.org/wp-content/uploads/2026/../Pier-5-Turf.png" to
          "traversal segments",
        "https://brooklynbridgepark.org/wp-content/uploads/%50ier-5-Turf.png" to
          "encoded characters",
        "https://brooklynbridgepark.org/wp-content/uploads/Pier-5-Turf.pdf" to
          "supported raster extension",
        "https://brooklynbridgepark.org/wp-content/uploads/Pier-5-Turf.jpg" to
          "extensions must match",
      )

    imageUrls.forEach { (imageUrl, expectedMessage) ->
      val fixture = sourceFixture()
      fixture.writeSource(fixture.source.copy(imageUrl = imageUrl))

      val failure =
        assertFailsWith<IllegalArgumentException> {
          decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
        }

      assertThat(failure.message.orEmpty()).contains(expectedMessage)
    }
  }

  @Test
  fun `source validation treats jpg and jpeg as the same raster type`() {
    val fixture = sourceFixture()
    val jpgImagePath = fixture.source.imagePath.removeSuffix(".png") + ".jpg"
    Files.move(fixture.root.resolve(fixture.source.imagePath), fixture.root.resolve(jpgImagePath))
    fixture.writeSource(
      fixture.source.copy(
        imageUrl = "https://brooklynbridgepark.org/wp-content/uploads/Pier-5-Turf-Summer-2026.jpeg",
        imagePath = jpgImagePath,
      )
    )

    val source = decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)

    assertThat(source.imagePath).isEqualTo(jpgImagePath)
  }

  @Test
  fun `source validation rejects image content that does not match the recorded hash`() {
    val fixture = sourceFixture()
    Files.write(fixture.root.resolve(fixture.source.imagePath), byteArrayOf(9, 8, 7))

    val failure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
      }

    assertThat(failure.message.orEmpty()).contains("imageSha256 does not match")
  }

  @Test
  fun `source validation rejects unsupported schema versions`() {
    val fixture = sourceFixture()
    fixture.writeSource(fixture.source.copy(schemaVersion = 2))

    val failure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
      }

    assertThat(failure.message.orEmpty()).contains("unsupported schemaVersion 2")
  }

  @Test
  fun `source validation rejects malformed and invalid date ranges`() {
    val cases =
      listOf(
        ("not-a-date" to "2026-08-31") to "validFrom is not an ISO date",
        ("2026-08-31" to "2026-08-30") to "validFrom must not be after validTo",
        ("2026-07-30" to "2027-07-31") to "must not exceed one year",
      )

    cases.forEach { (dates, expectedMessage) ->
      val fixture = sourceFixture(validFrom = dates.first, validTo = dates.second)
      val failure =
        assertFailsWith<IllegalArgumentException> {
          decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
        }
      assertThat(failure.message.orEmpty()).contains(expectedMessage)
    }
  }

  @Test
  fun `source validation rejects invalid provenance`() {
    val fixture = sourceFixture()
    val cases =
      listOf(
        fixture.source.provenance.copy(method = "automated") to "method must be manual or openai",
        fixture.source.provenance.copy(extractedAt = "not-an-instant") to
          "extractedAt must be an ISO-8601 instant",
        fixture.source.provenance.copy(method = "openai", model = "") to
          "OpenAI provenance must include model",
        fixture.source.provenance.copy(
          method = "openai",
          model = "gpt-5.6-sol",
          promptVersion = "bbp-pier5-v1",
          responseIds = listOf("resp-1"),
        ) to "two distinct response IDs",
        fixture.source.provenance.copy(responseIds = listOf("resp-1")) to
          "manual provenance must not include response IDs",
      )

    cases.forEach { (provenance, expectedMessage) ->
      fixture.writeSource(fixture.source.copy(provenance = provenance))
      val failure =
        assertFailsWith<IllegalArgumentException> {
          decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
        }
      assertThat(failure.message.orEmpty()).contains(expectedMessage)
    }
  }

  @Test
  fun `source validation rejects unknown fields duplicates overlaps and non-HH-mm times`() {
    val cases =
      listOf(
        listOf(block("MONDAY", listOf("unknown-field"), "09:00", "10:00")) to "unknown field IDs",
        listOf(block("MONDAY", listOf("pier5-field-1", "pier5-field-1"), "09:00", "10:00")) to
          "fieldIds contains duplicates",
        listOf(
          block("MONDAY", listOf("pier5-field-1"), "09:00", "11:00"),
          block("MONDAY", listOf("pier5-field-1"), "10:00", "12:00"),
        ) to "blocks overlap",
        listOf(
          block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
          block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
        ) to "duplicate field intervals",
        listOf(block("MONDAY", listOf("pier5-field-1"), "09:00:00", "10:00")) to
          "is not an HH:mm time",
      )

    cases.forEach { (blocks, expectedMessage) ->
      val fixture = sourceFixture(blocks = blocks)
      val failure =
        assertFailsWith<IllegalArgumentException> {
          decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
        }
      assertThat(failure.message.orEmpty()).contains(expectedMessage)
    }
  }

  @Test
  fun `source validation rejects expired candidates`() {
    val fixture =
      sourceFixture(
        validFrom = "2026-01-01",
        validTo = "2026-07-29",
      )

    val failure =
      assertFailsWith<IllegalArgumentException> {
        decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)
      }

    assertThat(failure.message.orEmpty()).contains("candidate expired on 2026-07-29")
  }

  @Test
  fun `source validation permits an exact one-year cross-year range`() {
    val fixture =
      sourceFixture(
        validFrom = "2026-07-30",
        validTo = "2027-07-30",
      )

    val source = decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)

    assertThat(source.validTo).isEqualTo("2027-07-30")
  }

  @Test
  fun `canonicalization normalizes block order and grouping without merging Monday split`() {
    val fixture =
      sourceFixture(
        blocks =
          listOf(
            block("MONDAY", listOf("pier5-field-3"), "17:00", "23:00"),
            block("MONDAY", listOf("pier5-field-1"), "17:00", "23:00"),
            block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
            block("MONDAY", listOf("pier5-field-2"), "17:00", "23:00"),
          )
      )

    val source = decodeAndValidateBbpSource(fixture.sourceFile, fixture.root, testToday)

    assertThat(source.blocks)
      .containsExactly(
        block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
        block(
          "MONDAY",
          listOf("pier5-field-1", "pier5-field-2", "pier5-field-3"),
          "17:00",
          "23:00",
        ),
      )
  }

  @Test
  fun `Responses request uses the approved model controls and strict image schema`() {
    val body =
      buildBbpOpenAiRequest(
        imageBytes = byteArrayOf(1, 2, 3),
        imageMimeType = "image/png",
        scheduleYear = 2026,
      )
    val request = testJson.parseToJsonElement(body).jsonObject

    assertThat(request["model"]?.jsonPrimitive?.content).isEqualTo("gpt-5.6-sol")
    assertThat(request["store"]?.jsonPrimitive?.boolean).isEqualTo(false)
    assertThat(request["max_output_tokens"]?.jsonPrimitive?.int).isEqualTo(4_096)
    assertThat(request["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
      .isEqualTo("medium")
    val content =
      request["input"]?.jsonArray?.single()?.jsonObject?.get("content")?.jsonArray.orEmpty()
    val image = content.single { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
    assertThat(image.jsonObject["detail"]?.jsonPrimitive?.content).isEqualTo("original")
    assertThat(image.jsonObject["image_url"]?.jsonPrimitive?.content.orEmpty())
      .contains("data:image/png;base64,")
    val format = request["text"]?.jsonObject?.get("format")?.jsonObject
    assertThat(format?.get("strict")?.jsonPrimitive?.boolean).isEqualTo(true)
    val statusEnum =
      format
        ?.get("schema")
        ?.jsonObject
        ?.get("properties")
        ?.jsonObject
        ?.get("status")
        ?.jsonObject
        ?.get("enum")
        ?.jsonArray
        ?.map { it.jsonPrimitive.content }
    assertThat(statusEnum).isEqualTo(listOf("complete", "ambiguous", "not_schedule"))
  }

  @Test
  fun `Responses client retries timeout 408 429 and server failures`() = runBlocking {
    val statuses = listOf(408, 429, 500, 503)
    statuses.forEach { transientStatus ->
      var calls = 0
      val delays = mutableListOf<Long>()
      val client =
        BbpOpenAiClient(
          transport =
            BbpOpenAiTransport {
              calls++
              if (calls == 1) {
                BbpHttpResponse(transientStatus, "")
              } else {
                BbpHttpResponse(200, completedResponse("resp-$transientStatus"))
              }
            },
          retryDelay = delays::add,
        )

      val response = client.transcribe("request body")

      assertThat(response.id).isEqualTo("resp-$transientStatus")
      assertThat(calls).isEqualTo(2)
      assertThat(delays).containsExactly(1_000L)
    }

    var calls = 0
    val exceptionClient =
      BbpOpenAiClient(
        transport =
          BbpOpenAiTransport {
            calls++
            if (calls == 1) throw SocketTimeoutException("timed out")
            BbpHttpResponse(200, completedResponse("resp-timeout"))
          },
        retryDelay = {},
      )
    assertThat(exceptionClient.transcribe("request body").id).isEqualTo("resp-timeout")
    assertThat(calls).isEqualTo(2)
  }

  @Test
  fun `Responses client stops after two retries`() = runBlocking {
    var calls = 0
    val client =
      BbpOpenAiClient(
        transport =
          BbpOpenAiTransport {
            calls++
            BbpHttpResponse(
              429,
              """{"error":{"type":"insufficient_quota","code":"insufficient_quota"}}""",
            )
          },
        retryDelay = {},
      )

    val failure = assertFailsWith<IllegalStateException> { client.transcribe("request body") }

    assertThat(calls).isEqualTo(3)
    assertThat(failure.message.orEmpty()).contains("after 3 attempts")
    assertThat(failure.message.orEmpty()).contains("insufficient_quota")
  }

  @Test
  fun `Responses client does not retry refusals incomplete responses or invalid output`() =
    runBlocking {
      val responses =
        listOf(
          refusalResponse() to "refused",
          incompleteResponse() to "incomplete",
          completedResponse("resp-invalid", structuredOutput = "{}") to "invalid structured output",
        )
      responses.forEach { (body, expectedMessage) ->
        var calls = 0
        val client =
          BbpOpenAiClient(
            transport =
              BbpOpenAiTransport {
                calls++
                BbpHttpResponse(200, body)
              },
            retryDelay = {},
          )

        val failure =
          assertFailsWith<IllegalStateException> {
            client.transcribe("request body")
          }

        assertThat(calls).isEqualTo(1)
        assertThat(failure.message.orEmpty()).contains(expectedMessage)
      }
    }

  @Test
  fun `two independent equivalent transcriptions produce canonical source and diagnostics`() =
    runBlocking {
      val imageFile = Files.createTempFile("bbp-schedule", ".png")
      Files.write(imageFile, byteArrayOf(1, 2, 3))
      val first =
        completeResult(
          blocks =
            listOf(
              block("MONDAY", listOf("pier5-field-3"), "17:00", "23:00"),
              block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
              block(
                "MONDAY",
                listOf("pier5-field-1", "pier5-field-2"),
                "17:00",
                "23:00",
              ),
            )
        )
      val second =
        completeResult(
          blocks =
            listOf(
              block(
                "MONDAY",
                listOf("pier5-field-1", "pier5-field-2", "pier5-field-3"),
                "17:00",
                "23:00",
              ),
              block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
            )
        )
      val responses =
        ArrayDeque(
          listOf(
            BbpHttpResponse(200, completedResponse("resp-1", first)),
            BbpHttpResponse(200, completedResponse("resp-2", second)),
          )
        )
      val diagnostics = mutableListOf<BbpExtractionDiagnostic>()
      val client =
        BbpOpenAiClient(
          transport = BbpOpenAiTransport { responses.removeFirst() },
          retryDelay = {},
        )

      val source =
        transcribeBbpSource(
          imageFile = imageFile,
          imageUrl = validImageUrl,
          sourcePageUrl = validSourcePageUrl,
          scheduleYear = 2026,
          model = "gpt-5.6-sol",
          client = client,
          extractedAt = Instant.parse("2026-07-30T12:00:00Z"),
          today = testToday,
          diagnosticWriter = { _, diagnostic -> diagnostics += diagnostic },
        )

      assertThat(source.blocks)
        .containsExactly(
          block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
          block(
            "MONDAY",
            listOf("pier5-field-1", "pier5-field-2", "pier5-field-3"),
            "17:00",
            "23:00",
          ),
        )
      assertThat(source.provenance.responseIds).containsExactly("resp-1", "resp-2")
      assertThat(source.imagePath).isEqualTo("data/bbp/pier5-${source.imageSha256}.png")
      assertThat(diagnostics.map(BbpExtractionDiagnostic::responseId))
        .containsExactly("resp-1", "resp-2")
      val diagnosticJson = testJson.encodeToString(diagnostics)
      assertThat(diagnosticJson.contains("base64")).isFalse()
      assertThat(diagnosticJson.contains("OPENAI_API_KEY")).isFalse()
    }

  @Test
  fun `two independent semantic disagreements fail`() = runBlocking {
    val imageFile = Files.createTempFile("bbp-schedule", ".png")
    Files.write(imageFile, byteArrayOf(1, 2, 3))
    val responses =
      ArrayDeque(
        listOf(
          BbpHttpResponse(200, completedResponse("resp-1")),
          BbpHttpResponse(
            200,
            completedResponse(
              "resp-2",
              completeResult(
                blocks = listOf(block("MONDAY", listOf("pier5-field-1"), "10:00", "11:00"))
              ),
            ),
          ),
        )
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        transcribeBbpSource(
          imageFile = imageFile,
          imageUrl = validImageUrl,
          sourcePageUrl = validSourcePageUrl,
          scheduleYear = 2026,
          model = "gpt-5.6-sol",
          client =
            BbpOpenAiClient(
              transport = BbpOpenAiTransport { responses.removeFirst() },
              retryDelay = {},
            ),
        )
      }

    assertThat(failure.message.orEmpty()).contains("transcriptions disagreed")
  }

  @Test
  fun `ambiguous transcription fails without a second extraction`() = runBlocking {
    val imageFile = Files.createTempFile("bbp-schedule", ".png")
    Files.write(imageFile, byteArrayOf(1, 2, 3))
    var calls = 0
    val ambiguous =
      BbpTranscriptionResult(
        status = "ambiguous",
        validFrom = null,
        validTo = null,
        blocks = emptyList(),
        issues = listOf("Monday end time is obscured"),
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        transcribeBbpSource(
          imageFile = imageFile,
          imageUrl = validImageUrl,
          sourcePageUrl = validSourcePageUrl,
          scheduleYear = 2026,
          model = "gpt-5.6-sol",
          client =
            BbpOpenAiClient(
              transport =
                BbpOpenAiTransport {
                  calls++
                  BbpHttpResponse(200, completedResponse("resp-1", ambiguous))
                },
              retryDelay = {},
            ),
        )
      }

    assertThat(calls).isEqualTo(1)
    assertThat(failure.message.orEmpty()).contains("status was ambiguous")
  }

  @Test
  fun `BBP-only generation preserves other rows and is deterministic for unchanged source`() {
    val fixture = sourceFixture()
    val baselineRoot = Files.createTempDirectory("bbp-baseline")
    val feedPath = baselineRoot.resolve("availability/areas/brooklyn-bridge-park.json")
    Files.createDirectories(feedPath.parent)
    val otherRow =
      AvailabilityFeedRow(
        areaName = "Brooklyn Bridge Park",
        groupName = "Pier 2",
        fieldId = "pier2-court-1",
        start = 1,
        end = 2,
        title = "Other provider",
        kind = "NYC live",
        sourceId = "other-source",
      )
    val oldBbpRow =
      AvailabilityFeedRow(
        areaName = "Brooklyn Bridge Park",
        groupName = "Pier 5",
        fieldId = "pier5-field-1",
        start = 3,
        end = 4,
        title = "Old schedule",
        kind = "BBP active permits",
        sourceId = "old-bbp-source",
      )
    Files.writeString(
      feedPath,
      testJson.encodeToString(
        AvailabilityAreaFeed(
          areaName = "Brooklyn Bridge Park",
          generatedAt = null,
          rows = listOf(otherRow, oldBbpRow),
        )
      ),
    )
    val manifestPath = baselineRoot.resolve("availability/manifest.json")
    Files.writeString(
      manifestPath,
      testJson.encodeToString(
        AvailabilityManifest(
          generatedAt = null,
          areas =
            listOf(
              AvailabilityManifestArea(
                areaName = "Brooklyn Bridge Park",
                areaId = "brooklyn-bridge-park",
                path = "availability/areas/brooklyn-bridge-park.json",
                hash = "old-hash",
              ),
              AvailabilityManifestArea(
                areaName = "Baruch",
                areaId = "baruch",
                path = "availability/areas/baruch.json",
                hash = "unchanged-hash",
              ),
            ),
        )
      ),
    )
    val firstOutput = Files.createTempDirectory("bbp-output-1")
    val secondOutput = Files.createTempDirectory("bbp-output-2")

    generateBbpOnly(
      sourceFile = fixture.sourceFile,
      imageRoot = fixture.root,
      baselineRoot = baselineRoot,
      outputRoot = firstOutput,
      today = testToday,
    )
    generateBbpOnly(
      sourceFile = fixture.sourceFile,
      imageRoot = fixture.root,
      baselineRoot = baselineRoot,
      outputRoot = secondOutput,
      today = testToday,
    )

    val generatedFeed =
      testJson.decodeFromString<AvailabilityAreaFeed>(
        Files.readString(firstOutput.resolve("availability/areas/brooklyn-bridge-park.json"))
      )
    assertThat(generatedFeed.rows.any { it == otherRow }).isTrue()
    assertThat(generatedFeed.rows.any { it.sourceId == "old-bbp-source" }).isFalse()
    assertThat(generatedFeed.rows.any { it.sourceId == fixture.source.id }).isTrue()
    val generatedManifest =
      testJson.decodeFromString<AvailabilityManifest>(
        Files.readString(firstOutput.resolve("availability/manifest.json"))
      )
    assertThat(generatedManifest.areas.single { it.areaName == "Baruch" }.hash)
      .isEqualTo("unchanged-hash")
    assertThat(generatedManifest.areas.single { it.areaName == "Brooklyn Bridge Park" }.hash)
      .isNotEqualTo("old-hash")
    assertThat(
        Files.readString(firstOutput.resolve("availability/areas/brooklyn-bridge-park.json"))
      )
      .isEqualTo(
        Files.readString(secondOutput.resolve("availability/areas/brooklyn-bridge-park.json"))
      )
    assertThat(Files.readString(firstOutput.resolve("availability/manifest.json")))
      .isEqualTo(Files.readString(secondOutput.resolve("availability/manifest.json")))
    val generatedFileCount =
      Files.walk(firstOutput).use { paths -> paths.filter(Files::isRegularFile).count() }
    assertThat(generatedFileCount).isEqualTo(2L)
  }

  private fun sourceFixture(
    validFrom: String = "2026-06-01",
    validTo: String = "2026-08-31",
    blocks: List<BbpRecurringBlock> = validBlocks(),
  ): SourceFixture {
    val root = Files.createTempDirectory("bbp-source")
    val imageBytes = byteArrayOf(1, 2, 3, 4)
    val imageSha256 = imageBytes.sha256()
    val imagePath = "data/bbp/pier5-$imageSha256.png"
    val resolvedImagePath = root.resolve(imagePath)
    Files.createDirectories(resolvedImagePath.parent)
    Files.write(resolvedImagePath, imageBytes)
    val source =
      BbpPier5Source(
        schemaVersion = 1,
        id = "bbp-pier5-turf-summer-2026",
        sourcePageUrl = validSourcePageUrl,
        imageUrl = validImageUrl,
        imagePath = imagePath,
        imageSha256 = imageSha256,
        scheduleYear = 2026,
        validFrom = validFrom,
        validTo = validTo,
        provenance =
          BbpSourceProvenance(
            method = "manual",
            model = "human",
            promptVersion = "manual-v1",
            responseIds = emptyList(),
            extractedAt = "2026-07-30T12:00:00Z",
          ),
        blocks = blocks,
      )
    val sourceFile = root.resolve("data/bbp/pier5-current.json")
    Files.writeString(sourceFile, testJson.encodeToString(source))
    return SourceFixture(root, sourceFile, source)
  }

  private data class SourceFixture(
    val root: Path,
    val sourceFile: Path,
    val source: BbpPier5Source,
  ) {
    fun writeSource(updated: BbpPier5Source): Path {
      Files.writeString(sourceFile, testJson.encodeToString(updated))
      return sourceFile
    }
  }

  private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
      "%02x".format(byte)
    }
  }

  private fun completedResponse(
    id: String,
    result: BbpTranscriptionResult = completeResult(),
    structuredOutput: String = testJson.encodeToString(result),
  ): String {
    return buildJsonObject {
      put("id", id)
      put("status", "completed")
      putJsonArray("output") {
        add(
          buildJsonObject {
            put("type", "message")
            putJsonArray("content") {
              add(
                buildJsonObject {
                  put("type", "output_text")
                  put("text", structuredOutput)
                }
              )
            }
          }
        )
      }
    }
      .toString()
  }

  private fun refusalResponse(): String {
    return buildJsonObject {
      put("id", "resp-refusal")
      put("status", "completed")
      putJsonArray("output") {
        add(
          buildJsonObject {
            put("type", "message")
            putJsonArray("content") {
              add(
                buildJsonObject {
                  put("type", "refusal")
                  put("refusal", "Cannot process")
                }
              )
            }
          }
        )
      }
    }
      .toString()
  }

  private fun incompleteResponse(): String {
    return buildJsonObject {
      put("id", "resp-incomplete")
      put("status", "incomplete")
      put("incomplete_details", buildJsonObject { put("reason", "max_output_tokens") })
      put("output", JsonArray(emptyList()))
    }
      .toString()
  }

  companion object {
    private val testToday = LocalDate.of(2026, 7, 30)
    private const val validSourcePageUrl = "https://brooklynbridgepark.org/places-to-see/pier-5/"
    private const val validImageUrl =
      "https://brooklynbridgepark.org/wp-content/uploads/2023/07/PIer-5-Turf-Summer-2026.png"

    @OptIn(ExperimentalSerializationApi::class)
    private val testJson = Json {
      ignoreUnknownKeys = false
      encodeDefaults = true
      explicitNulls = true
    }

    private fun validBlocks(): List<BbpRecurringBlock> {
      return listOf(
        block("MONDAY", listOf("pier5-field-1"), "09:00", "10:00"),
        block("MONDAY", listOf("pier5-field-1"), "17:00", "23:00"),
      )
    }

    private fun completeResult(
      blocks: List<BbpRecurringBlock> = validBlocks()
    ): BbpTranscriptionResult {
      return BbpTranscriptionResult(
        status = "complete",
        validFrom = "2026-06-01",
        validTo = "2026-08-31",
        blocks = blocks,
        issues = emptyList(),
      )
    }

    private fun block(
      day: String,
      fieldIds: List<String>,
      start: String,
      end: String,
    ): BbpRecurringBlock {
      return BbpRecurringBlock(day, fieldIds, start, end)
    }
  }
}
