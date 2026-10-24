// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class OutboxStoresTest {

  @Test
  fun `in memory stores never encode a payload`() = runTest {
    val sent = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    val outbox =
      Outbox.Factory(backgroundScope, onFailure = { failures += it })
        .create("notes", UnencodableSerializer, send = { sent += it })

    assertThat(outbox.enqueue("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(sent).containsExactly("a")
    assertThat(failures).isEmpty()
  }

  @Test
  fun `DataStore stores encode payloads in their format`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val store =
      DataStoreOutboxStores(
          backgroundScope,
          directory,
          payloadFormat = TaggedFormat,
          fileSystem = fileSystem,
        )
        .open("notes", String.serializer())

    store.insert("k", "hello")

    assertThat(store.entries().first().single().payload).isEqualTo("hello")
    val log =
      Json.decodeFromString(
        OutboxLog.serializer(),
        fileSystem.read(directory / "notes.outbox") { readUtf8() },
      )
    assertThat(log.rows.single().payload).isEqualTo("tagged:\"hello\"")
  }
}

/** JSON behind a prefix, so a test can tell which format encoded a stored payload. */
internal object TaggedFormat : StringFormat {
  private const val TAG = "tagged:"

  override val serializersModule: SerializersModule = Json.serializersModule

  override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String =
    TAG + Json.encodeToString(serializer, value)

  override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T =
    Json.decodeFromString(deserializer, string.removePrefix(TAG))
}

/** Fails on any use, so a test that passes shows the store never encoded or decoded with it. */
private object UnencodableSerializer : KSerializer<String> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("Unencodable", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: String) = error("Encoded $value")

  override fun deserialize(decoder: Decoder): String = error("Decoded a payload")
}
