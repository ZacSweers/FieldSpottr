// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.UNKNOWN_REPLY
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/** How an outbox stores the server's answer to a row that no handle could commit yet. */
class OutboxReplyEncodingTest {

  @Test
  fun `an answer that cannot be encoded stays owed and commits as unknown`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<String>(Unencodable.name)
    val id = rows.insertNew("", "b")
    val data =
      factory.create(
        backgroundScope,
        setOf(Unencodable.exchangingSender { _, _ -> listOf("a", "b") }),
      )
    runCurrent()

    val sent = checkNotNull(rows.entry(id).first())
    assertThat(sent.status).isEqualTo(OutboxStatus.Sent)
    assertThat(sent.committed).isFalse()
    assertThat(sent.isOwed).isTrue()
    // Reported once, by the send
    assertThat(factory.actionFailures.take().single()).isInstanceOf<SerializationException>()

    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val own = TestFailures()
    val store = data.optimisticStore(Unencodable, base = truth, apply = ::addOnce, onFailure = own)
    rows.entry(id).first { it?.committed == true }
    runCurrent()

    // The answer is unknown, so the truth is stale and the change still shows
    assertThat(truth.data.first()?.invalidated).isEqualTo(true)
    assertThat(truth.data.first()?.value).isEqualTo(listOf("a"))
    assertThat(store.data.first()?.value).isEqualTo(listOf("a", "b"))
    own.assertNone()
    factory.assertNoFailures()
  }

  @Test
  fun `an answer with no codec is reported without its value`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<String>(Plain.name)
    val id = rows.insertNew("", "b")
    // A plain key has no reply codec, which the public builders never pair with an answer
    val sender =
      OutboxSender(Plain, { _, _ -> Delivery.Replaced("secret") }, null, null, onFailure = null)

    factory.create(backgroundScope, setOf(sender))
    runCurrent()

    val failure = factory.actionFailures.take().single()
    assertThat(failure.message.orEmpty()).doesNotContain("secret")
    assertThat(failure.message.orEmpty()).contains(id.value)
  }

  @Test
  fun `a null answer stays distinct from an acknowledgment as JSON`() = runTest {
    val stores = InMemoryOutboxStores()

    val replies = commitNullThenAcknowledged(stores)

    assertThat(replies).containsExactly("null", null)
  }

  @Test
  fun `a null answer stays distinct from an acknowledgment as Base64`() = runTest {
    val stores = BinaryReplyStores()

    val replies = commitNullThenAcknowledged(stores)

    // The format writes null as no bytes, which Base64 keeps as empty text
    assertThat(replies).containsExactly("", null)
  }

  @Test
  fun `the unknown marker decodes as neither JSON nor Base64`() {
    val lenient = Json { isLenient = true }

    assertFailsWith<SerializationException> { lenient.parseToJsonElement(UNKNOWN_REPLY) }
    assertFailsWith<IllegalArgumentException> { Base64.decode(UNKNOWN_REPLY) }
  }

  /**
   * Sends a row the server answers with null, then one it acknowledges, with no handle, and then
   * commits both through an optimistic store over [stores]. Asserts the truth the commits leave,
   * and returns the answers as stored, in send order.
   */
  private suspend fun TestScope.commitNullThenAcknowledged(stores: OutboxStores): List<String?> {
    val rows = stores.open(Nullable.name, String.serializer())
    rows.insertNew("", "n")
    rows.insertNew("", "b")
    val failures = TestFailures()
    val factory = DataScope.Factory(onFailure = failures, stores = stores, clock = testClock(START))
    val data =
      factory.create(
        backgroundScope,
        setOf(
          Nullable.deliveringSender { payload, _ ->
            if (payload == "n") {
              Delivery.Replaced(null)
            } else {
              Delivery.Acknowledged
            }
          }
        ),
      )
    runCurrent()
    val replies = rows.entries().first().map { it.reply }

    val truth = QueryStore.inMemory<String?>(Fetched("a", START - 1.hours))
    data.optimisticStore(Nullable, base = truth, apply = ::appendOnce)
    rows.entries().first { it.isEmpty() }

    // n's null answer replaced the truth, and then b applied on top of it
    assertThat(truth.data.first()?.value).isEqualTo("b")
    failures.assertNone()
    return replies
  }

  /** A reply serializer whose encoding always throws, like a value the format cannot hold. */
  private object UnencodableTags : KSerializer<List<String>> {
    private val tags = ListSerializer(String.serializer())

    override val descriptor = tags.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
      throw SerializationException("Cannot encode an answer of ${value.size} tags")
    }

    override fun deserialize(decoder: Decoder): List<String> = tags.deserialize(decoder)
  }

  /**
   * A binary format that writes null as no bytes and any other value as its JSON bytes, so an
   * answer of null reaches the Base64 path as empty text.
   */
  private object NullAsNoBytes : BinaryFormat {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
      val text = Json.encodeToString(serializer, value)
      return if (text == "null") {
        ByteArray(0)
      } else {
        text.encodeToByteArray()
      }
    }

    override fun <T> decodeFromByteArray(
      deserializer: DeserializationStrategy<T>,
      bytes: ByteArray,
    ): T {
      val text =
        if (bytes.isEmpty()) {
          "null"
        } else {
          bytes.decodeToString()
        }
      return Json.decodeFromString(deserializer, text)
    }
  }

  /** In-memory stores whose answers go through [NullAsNoBytes] and Base64. */
  private class BinaryReplyStores(private val rows: OutboxStores = InMemoryOutboxStores()) :
    OutboxStores by rows {
    override fun <R> replyCodec(serializer: KSerializer<R>): PayloadCodec<R> =
      serializer.asCodec(NullAsNoBytes)
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Plain = OutboxKey<String>("plain")
    val Unencodable = OutboxKey.replying("unencodable", String.serializer(), UnencodableTags)
    val Nullable = OutboxKey.replying("nullable", String.serializer(), String.serializer().nullable)

    /** Adds the tag once, so a change applied again changes nothing. */
    fun addOnce(current: List<String>?, tag: String): List<String> =
      if (tag in current.orEmpty()) {
        current.orEmpty()
      } else {
        current.orEmpty() + tag
      }

    /** Appends [letter] once, so a change applied again changes nothing. */
    fun appendOnce(current: String?, letter: String): String =
      if (letter in current.orEmpty()) {
        current.orEmpty()
      } else {
        current.orEmpty() + letter
      }
  }
}
