// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * [DataStoreOutboxStores.clearAll] while a store opens and reads during the clear. It blocks inside
 * a file system call the clear makes, so it runs on real threads and needs the JVM.
 */
class DataStoreOutboxStoresClearTest {
  private val fake = FakeFileSystem()
  private val directory = "/outbox".toPath()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Ends every DataStore the test built, so the next test can open the same files
  @AfterTest fun endScope() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

  @Test
  fun `a store opened while a clear lists the files ends cleared`() = runBlocking {
    // An earlier process left a row in notes.outbox
    fake.createDirectories(directory)
    val earlier = CoroutineScope(Job() + Dispatchers.IO)
    DataStoreOutboxStores(earlier, directory, fileSystem = fake)
      .open("notes", String.serializer())
      .insertNew("k", "a")
    earlier.coroutineContext.job.cancelAndJoin()
    val reader = Channel<List<String>>(Channel.UNLIMITED)
    lateinit var stores: DataStoreOutboxStores
    var opened = false
    // Opens the store and reads the row while the clear lists the directory
    val fileSystem =
      object : ForwardingFileSystem(fake) {
        override fun list(dir: Path): List<Path> {
          if (!opened) {
            opened = true
            val notes = stores.open("notes", String.serializer())
            scope.launch {
              notes.entries().collect { rows -> reader.send(rows.map { it.payload }) }
            }
            assertEquals(listOf("a"), runBlocking { reader.receive() })
          }
          return super.list(dir)
        }
      }
    stores = DataStoreOutboxStores(scope, directory, fileSystem = fileSystem)

    stores.clearAll()

    withTimeout(10.seconds) {
      while (reader.receive().isNotEmpty()) {
        // Waits for the clear to reach the reader
      }
    }
  }
}
