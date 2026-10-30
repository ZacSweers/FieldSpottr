// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.impl.inMemoryOutboxStore
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
    val send = TestSend<String>()
    val data = testDataScope()
    val outbox = data.outbox("notes", UnencodableSerializer, send = send)

    assertThat(outbox.enqueue("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
    data.assertNoFailures()
  }

  @Test
  fun `a second running outbox on one name fails`() = runTest {
    val data = testDataScope()
    data.outbox<String>("notes", send = {})

    assertFailsWith<IllegalStateException> { data.outbox<String>("notes", send = {}) }
  }

  @Test
  fun `an outbox over a store of its own sends the rows already in it`() = runTest {
    val send = TestSend<String>()
    val data = testDataScope()
    val store = inMemoryOutboxStore<String>()
    store.insert("k", "a")

    val outbox = data.outbox(store, send = send)

    outbox.entries.first { it.isEmpty() }
    assertThat(send.sent).containsExactly("a")
    data.assertNoFailures()
  }

  @Test
  fun `an outbox built again after its scope ends sends the rows it left`() = runTest {
    val send = TestSend<String>()
    val scopes = testDataScopeFactory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val first = scopes.create(owner).outbox<String>("notes", send = send)
    first.enqueue("a")
    // Offline, so the row waits
    first.entries.first { it.isNotEmpty() }
    owner.coroutineContext.job.cancelAndJoin()

    val second = scopes.create(backgroundScope).outbox<String>("notes", send = send)
    scopes.online.value = true

    second.entries.first { it.isEmpty() }
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `DataStore stores keep every name inside their directory`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)

    stores.open("../outside", String.serializer()).insert("k", "a")

    assertThat(fileSystem.exists("/outside.outbox".toPath())).isFalse()
    assertThat(fileSystem.list(directory).map { it.name }).containsExactly("..%2Foutside.outbox")
  }

  @Test
  fun `DataStore stores give one store per name`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)

    val store = stores.open("notes", String.serializer())
    store.insert("k", "a")

    assertThat(stores.open("notes", String.serializer())).isSameInstanceAs(store)
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
