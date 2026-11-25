// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
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
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class OutboxStoresTest {

  @Test
  fun `in memory stores never encode a payload`() = runTest {
    val send = TestSend<String>()
    val notes = OutboxKey("notes", UnencodableSerializer)
    val data = testDataScope(senders = setOf(notes.sender(send = send)))
    val outbox = data.outbox(notes)

    assertThat(outbox.enqueue("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
    data.assertNoFailures()
  }

  @Test
  fun `an outbox over a store of its own sends the rows already in it`() = runTest {
    val send = TestSend<String>()
    val store = inMemoryOutboxStore<String>()
    store.insertNew("k", "a")
    val rows = OutboxKey<String>("store")
    val data = testDataScope(senders = setOf(rows.sender(store = store, send = send)))

    val outbox = data.outbox(rows)

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
    val first = scopes.create(owner, setOf(Notes.sender(send = send))).outbox(Notes)
    first.enqueue("a")
    // Offline, so the row waits
    first.entries.first { it.isNotEmpty() }
    owner.coroutineContext.job.cancelAndJoin()

    val second = scopes.create(backgroundScope, setOf(Notes.sender(send = send))).outbox(Notes)
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

    stores.open("../outside", String.serializer()).insertNew("k", "a")

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
    store.insertNew("k", "a")

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

    store.insertNew("k", "hello")

    assertThat(store.entries().first().single().payload).isEqualTo("hello")
    val log =
      Json.decodeFromString(
        OutboxLog.serializer(),
        fileSystem.read(directory / "notes.outbox") { readUtf8() },
      )
    assertThat(log.rows.single().payload).isEqualTo("tagged:\"hello\"")
  }

  @Test
  fun `DataStore stores clear their own files and files an earlier process left`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    fileSystem.write(directory / "old.outbox") { writeUtf8("{}") }
    fileSystem.write(directory / "keep.txt") { writeUtf8("keep") }
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    val notes = stores.open("notes", String.serializer())
    notes.insertNew("k", "a")

    stores.clearAll()

    assertThat(notes.entries().first()).isEmpty()
    assertThat(fileSystem.list(directory).map { it.name })
      .containsExactlyInAnyOrder("keep.txt", "notes.outbox")
  }

  /** A directory that was never created, like on a first run, holds no files to clear. */
  @Test
  fun `DataStore stores clear when their directory does not exist`() = runTest {
    val fileSystem = FakeFileSystem()
    // A real file system always has its root, which lists no outbox directory
    fileSystem.createDirectories("/".toPath())
    val directory = "/outbox".toPath()
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    val notes = stores.open("notes", String.serializer())
    assertThat(notes.entries().first()).isEmpty()

    stores.clearAll()

    assertThat(notes.entries().first()).isEmpty()
    // The opened store still works after the clear
    notes.insertNew("k", "a")
    assertThat(notes.entries().first().map { it.payload }).containsExactly("a")
  }

  @Test
  fun `DataStore stores fail to clear a directory they cannot list and clear nothing`() = runTest {
    val fake = FakeFileSystem()
    val directory = "/outbox".toPath()
    fake.createDirectories(directory)
    fake.write(directory / "old.outbox") { writeUtf8("{}") }
    val fileSystem = UnlistableFileSystem(fake, directory)
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    val notes = stores.open("notes", String.serializer())
    notes.insertNew("k", "a")

    val failure = assertFailsWith<IOException> { stores.clearAll() }

    assertThat(failure.message).isEqualTo("disk error")
    assertThat(fake.exists(directory / "old.outbox")).isTrue()
    assertThat(notes.entries().first().map { it.payload }).containsExactly("a")
  }

  @Test
  fun `DataStore stores fail to clear when their directory is a file`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.write(directory) { writeUtf8("not a directory") }
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)

    assertFailsWith<IOException> { stores.clearAll() }

    assertThat(fileSystem.read(directory) { readUtf8() }).isEqualTo("not a directory")
  }

  @Test
  fun `DataStore stores fail to clear a directory whose parent cannot be read and clear nothing`() =
    runTest {
      val fake = FakeFileSystem()
      val directory = "/data/outbox".toPath()
      fake.createDirectories(directory)
      fake.write(directory / "old.outbox") { writeUtf8("{}") }
      val fileSystem = InaccessibleParentFileSystem(fake, directory)
      val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
      val notes = stores.open("notes", String.serializer())
      notes.insertNew("k", "a")
      fileSystem.failing = true

      val failure = assertFailsWith<IOException> { stores.clearAll() }

      assertThat(failure).isSameInstanceAs(fileSystem.listingError)
      assertThat(fake.exists(directory / "old.outbox")).isTrue()
      assertThat(notes.entries().first().map { it.payload }).containsExactly("a")
    }

  @Test
  fun `DataStore stores read and delete files on the dispatcher of their scope`() = runTest {
    val fake = FakeFileSystem()
    val directory = "/data/outbox".toPath()
    fake.createDirectories(directory)
    fake.write(directory / "old.outbox") { writeUtf8("{}") }
    val io = MarkingDispatcher(StandardTestDispatcher(testScheduler))
    val fileSystem = DispatcherRecordingFileSystem(fake, io)
    val stores = DataStoreOutboxStores(backgroundScope + io, directory, fileSystem = fileSystem)
    stores.open("notes", String.serializer()).insertNew("k", "a")

    stores.clearAll()
    // A missing directory makes the clear list its ancestors
    fake.deleteRecursively(directory)
    stores.clearAll()

    assertThat(fileSystem.calls)
      .containsExactly(
        "list $directory" to true,
        "delete ${directory / "old.outbox"}" to true,
        "list $directory" to true,
        "list ${directory.parent}" to true,
      )
  }

  @Test
  fun `DataStore stores reject a second name that differs only in case`() = runTest {
    val fileSystem = FakeFileSystem()
    val directory = "/outbox".toPath()
    fileSystem.createDirectories(directory)
    val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
    stores.open("Upload", String.serializer())

    val failure =
      assertFailsWith<IllegalArgumentException> { stores.open("upload", String.serializer()) }

    assertThat(failure.message.orEmpty()).contains("Upload")
    assertThat(failure.message.orEmpty()).contains("upload")
  }

  @Test
  fun `DataStore stores clear a store listed in another case on a file system that ignores case`() =
    runTest {
      val fileSystem = CaseFoldingFileSystem(FakeFileSystem())
      val directory = "/outbox".toPath()
      fileSystem.createDirectories(directory)
      val stores = DataStoreOutboxStores(backgroundScope, directory, fileSystem = fileSystem)
      val upload = stores.open("Upload", String.serializer())
      upload.insertNew("k", "a")

      stores.clearAll()

      assertThat(upload.entries().first()).isEmpty()
      // A later write keeps only its own row
      upload.insertNew("k", "b")
      assertThat(upload.entries().first().map { it.payload }).containsExactly("b")
    }

  /**
   * [delegate] as a file system that ignores case, like the default one on iOS and macOS. It keeps
   * each file under its name in lower case, so every spelling of a name finds the same file.
   */
  private class CaseFoldingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
      val parent = path.parent ?: return path
      return parent / path.name.lowercase()
    }
  }

  /**
   * [delegate] where the parent of [directory] cannot be read once [failing] is set. Okio on JVM
   * and Android then reports [directory] as missing, and its metadata is null.
   */
  private class InaccessibleParentFileSystem(delegate: FileSystem, private val directory: Path) :
    ForwardingFileSystem(delegate) {
    var failing = false
    val listingError = IOException("$directory: No such file or directory")

    override fun list(dir: Path): List<Path> {
      if (failing && dir == directory) {
        throw listingError
      }
      if (failing && dir == directory.parent) {
        throw IOException("$dir: Permission denied")
      }
      return super.list(dir)
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
      if (failing && (path == directory || path == directory.parent)) {
        return null
      }
      return super.metadataOrNull(path)
    }
  }

  /** [delegate] that marks while a block it dispatched runs, so a test can tell where code runs. */
  private class MarkingDispatcher(private val delegate: CoroutineDispatcher) :
    CoroutineDispatcher() {
    var running = false
      private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      delegate.dispatch(
        context,
        Runnable {
          running = true
          try {
            block.run()
          } finally {
            running = false
          }
        },
      )
    }
  }

  /**
   * [delegate] that records each directory listing and each delete of an outbox file, with whether
   * it ran on [io].
   */
  private class DispatcherRecordingFileSystem(
    delegate: FileSystem,
    private val io: MarkingDispatcher,
  ) : ForwardingFileSystem(delegate) {
    val calls = mutableListOf<Pair<String, Boolean>>()

    override fun list(dir: Path): List<Path> {
      calls += "list $dir" to io.running
      return super.list(dir)
    }

    override fun delete(path: Path, mustExist: Boolean) {
      if (path.name.endsWith(".outbox")) {
        calls += "delete $path" to io.running
      }
      super.delete(path, mustExist)
    }
  }

  /**
   * [delegate] with [directory] that exists and cannot be listed, like one the app lost permission
   * to read. [listOrNull] returns null for it, as Okio does for any directory it cannot list.
   */
  private class UnlistableFileSystem(delegate: FileSystem, private val directory: Path) :
    ForwardingFileSystem(delegate) {
    override fun list(dir: Path): List<Path> {
      if (dir == directory) {
        throw IOException("disk error")
      }
      return super.list(dir)
    }

    override fun listOrNull(dir: Path): List<Path>? {
      if (dir == directory) {
        return null
      }
      return super.listOrNull(dir)
    }
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

/** The outbox most of these tests open. */
private val Notes = OutboxKey<String>("notes")
