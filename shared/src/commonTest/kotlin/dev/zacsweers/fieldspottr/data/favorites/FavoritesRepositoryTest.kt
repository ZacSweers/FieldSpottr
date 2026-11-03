// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.favorites

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class FavoritesRepositoryTest {
  private val api = FakeFavoritesApi()
  private lateinit var data: TestDataScope

  private fun TestScope.repository(online: Boolean = true): FavoritesRepository {
    data = testDataScope(online = online)
    return FavoritesRepository(data, QueryStore.inMemory(), api)
  }

  @Test
  fun `a favorite shows at once and reaches the server with its row's id`() = runTest {
    val repository = repository()
    assertThat(repository.favorites.first()).isEmpty()

    repository.setFavorite("Prospect Park", on = true).awaitSucceeded()

    assertThat(repository.favorites.first()).containsOnly("Prospect Park")
    assertThat(api.server).containsOnly("Prospect Park")
    assertThat(repository.unsynced.first()).isEmpty()
    data.assertNoFailures()
  }

  @Test
  fun `favorites set offline show, wait, and send in order on reconnect`() = runTest {
    val repository = repository(online = false)
    repository.setFavorite("Prospect Park", on = true)
    val last = repository.setFavorite("McCarren Park", on = true)

    assertThat(repository.favorites.first()).containsOnly("Prospect Park", "McCarren Park")
    val waiting = repository.unsynced.first()
    assertThat(waiting.map { it.mutation.group }).containsExactly("Prospect Park", "McCarren Park")
    assertThat(api.server).isEmpty()

    data.online.value = true
    last.awaitSucceeded()

    assertThat(api.server).containsOnly("Prospect Park", "McCarren Park")
    // Each request carried its row's id, so the server can tell a resend from a new change
    assertThat(api.keys).isEqualTo(waiting.map { it.id.value })
    assertThat(repository.unsynced.first()).isEmpty()
    data.assertNoFailures()
  }

  @Test
  fun `a favorite the server rejects drops out, shows as failed, and comes back on retry`() =
    runTest {
      api.send.failPermanently()
      val repository = repository()

      repository.setFavorite("Prospect Park", on = true).awaitFailed<PermanentFailure>()

      assertThat(repository.favorites.first()).isEmpty()
      val failed = repository.unsynced.first().single()
      assertThat(failed.status).isInstanceOf<OutboxStatus.Failed>()

      api.send.succeed()
      repository.retry(failed.id).awaitSucceeded()

      assertThat(repository.favorites.first()).containsOnly("Prospect Park")
      assertThat(api.server).containsOnly("Prospect Park")
    }

  @Test
  fun `a favorite turned off again sends both changes and ends off`() = runTest {
    val repository = repository()
    repository.setFavorite("Prospect Park", on = true)
    repository.setFavorite("Prospect Park", on = false).awaitSucceeded()

    assertThat(repository.favorites.first()).isEmpty()
    assertThat(api.server).isEmpty()
    assertThat(api.send.sent)
      .containsExactly(
        FavoriteChange("Prospect Park", true),
        FavoriteChange("Prospect Park", false),
      )
    data.assertNoFailures()
  }
}

/** A favorites server in memory, whose sends the test holds or fails through [send]. */
private class FakeFavoritesApi : FavoritesApi {
  val send = TestSend<FavoriteChange>()
  val keys = mutableListOf<String>()
  var server: Set<String> = emptySet()

  override suspend fun favorites(): Set<String> = server

  override suspend fun setFavorite(group: String, on: Boolean, idempotencyKey: String) {
    keys += idempotencyKey
    send(FavoriteChange(group, on), EntryId(idempotencyKey))
    server =
      if (on) {
        server + group
      } else {
        server - group
      }
  }
}
