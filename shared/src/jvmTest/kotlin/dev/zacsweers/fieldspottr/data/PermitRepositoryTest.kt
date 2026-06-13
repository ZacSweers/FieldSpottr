// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.BuildConfig
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FakeFSAppDirs
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.hours
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import org.junit.Rule

class PermitRepositoryTest {

  @get:Rule val temporaryDatabase = TemporaryDatabase()

  private val fakeFileSystem = FakeFileSystem()
  private val json = Json { ignoreUnknownKeys = true }
  private val appDirs = FakeFSAppDirs(fakeFileSystem)
  private val httpClient =
    HttpClient(
      MockEngine { request ->
        error("PermitRepositoryTest should not perform network request to ${request.url}")
      }
    )

  private val repository =
    PermitRepositoryImpl(
      appDirs,
      json,
      Logger.Companion,
      suspend { temporaryDatabase.db() },
      lazyOf(httpClient),
    )

  @AfterTest
  fun closeHttpClient() {
    httpClient.close()
  }

  private val testDate = LocalDate(2025, 9, 18)
  private val midnight = testDate.atStartOfDayInNy().toEpochMilliseconds()

  val Int.am: Long
    get() =
      midnight +
        if (this == 12) {
          0
        } else {
          hours.inWholeMilliseconds
        }

  val Int.pm: Long
    get() =
      midnight +
        if (this == 12) {
          hours.inWholeMilliseconds
        } else {
          (this + 12).hours.inWholeMilliseconds
        }

  private val testGroup = "TestGroup"
  private val testOrg = "TestOrg"
  private val rawGithubBaseUrl = BuildConfig.REPO_DATA_BASE_URL

  private val morningPermit =
    DbPermit(
      recordId = 1L,
      area = "TestArea",
      groupName = testGroup,
      start = 9.am,
      end = 11.am,
      fieldId = "Soccer-01",
      type = "Athletic League - Youth",
      name = "Test League",
      org = testOrg,
      status = "Approved",
      isOverlap = 0L,
      advisory = null,
    )

  private val afternoonPermit =
    DbPermit(
      recordId = 2L,
      area = "TestArea",
      groupName = testGroup,
      start = 1.pm,
      end = 4.pm,
      fieldId = "Soccer-01",
      type = "Practice",
      name = "Team Practice",
      org = "Test Team",
      status = "Approved",
      isOverlap = 0L,
      advisory = null,
    )

  private val midnightPermit =
    DbPermit(
      recordId = 3L,
      area = "TestArea",
      groupName = testGroup,
      start = midnight,
      end = 2.am,
      fieldId = "Soccer-01",
      type = "Special",
      name = "Midnight Permit",
      org = "Org1",
      status = "Approved",
      isOverlap = 0L,
      advisory = null,
    )

  private val noonPermit =
    DbPermit(
      recordId = 4L,
      area = "TestArea",
      groupName = testGroup,
      start = 12.pm,
      end = 2.pm,
      fieldId = "Soccer-01",
      type = "Game",
      name = "Noon Game",
      org = "Org2",
      status = "Approved",
      isOverlap = 0L,
      advisory = null,
    )

  private data class TestResponse(
    val body: String,
    val statusCode: HttpStatusCode = HttpStatusCode.OK,
  )

  private fun testRepository(
    routes: MutableMap<String, TestResponse>
  ): Pair<PermitRepository, HttpClient> {
    val client =
      HttpClient(
        MockEngine { request ->
          routes[request.url.toString()]?.let { response ->
            respond(response.body, response.statusCode)
          } ?: respond("", HttpStatusCode.NotFound)
        }
      )
    return PermitRepositoryImpl(
      appDirs,
      json,
      Logger.Companion,
      suspend { temporaryDatabase.db() },
      lazyOf(client),
    ) to client
  }

  private fun rawGithubPath(path: String): String = "$rawGithubBaseUrl/$path"

  private fun writeCachedAreas(areaName: String = "TestArea") {
    val path = appDirs.userData / "areas.json"
    fakeFileSystem.createDirectories(path.parent!!)
    fakeFileSystem.sink(path).buffer().use { it.writeUtf8(testAreasJson(areaName)) }
  }

  private fun cachedManifest(hash: String): AvailabilityManifest {
    return AvailabilityManifest(
      areas =
        listOf(
          AvailabilityManifestArea(
            areaName = "TestArea",
            path = "availability/areas/test-area.json",
            hash = hash,
          )
        )
    )
  }

  private fun writeCachedManifest(hash: String) {
    val path = appDirs.userData / "availability" / "manifest.json"
    fakeFileSystem.createDirectories(path.parent!!)
    fakeFileSystem.sink(path).buffer().use {
      it.writeUtf8(json.encodeToString(cachedManifest(hash)))
    }
  }

  private fun downloadedFeed(title: String): AvailabilityAreaFeed {
    return AvailabilityAreaFeed(
      areaName = "TestArea",
      rows =
        listOf(
          AvailabilityFeedRow(
            groupName = testGroup,
            fieldId = "Soccer-01",
            start = 9.am,
            end = 11.am,
            title = title,
            org = testOrg,
          )
        ),
    )
  }

  private fun testAreasJson(areaName: String = "TestArea"): String {
    return json.encodeToString(
      Areas(
        entries =
          persistentListOf(
            Area(areaName = areaName, displayName = areaName, fieldGroups = persistentListOf())
          ),
        version = Areas.VERSION,
      )
    )
  }

  @Test
  fun `permitsFlow returns the correct permits`() = runTest {
    temporaryDatabase.db().transaction {
      temporaryDatabase.db().fsdbQueries.addPermit(morningPermit)
      temporaryDatabase.db().fsdbQueries.addPermit(afternoonPermit)
    }

    val permits = repository.permitsFlow(testDate, testGroup).first()

    assertThat(permits).hasSize(2)
    assertThat(permits[0]).isEqualTo(morningPermit)
    assertThat(permits[1]).isEqualTo(afternoonPermit)
  }

  @Test
  fun `permitsFlow with an empty db is empty`() = runTest {
    val permits = repository.permitsFlow(testDate, testGroup).first()
    assertThat(permits).isEmpty()
  }

  @Test
  fun `allPermitsInWindow returns overlapping permits`() = runTest {
    val eveningPermit =
      morningPermit.copy(recordId = 8L, start = 6.pm, end = 8.pm, name = "Evening Permit")
    val endingAtWindowStart = morningPermit.copy(recordId = 9L, start = 4.pm, end = 6.pm)
    val startingAtWindowEnd =
      morningPermit.copy(recordId = 10L, start = 11.pm, end = 11.pm + 1.hours.inWholeMilliseconds)
    temporaryDatabase.db().transaction {
      temporaryDatabase.db().fsdbQueries.addPermit(eveningPermit)
      temporaryDatabase.db().fsdbQueries.addPermit(endingAtWindowStart)
      temporaryDatabase.db().fsdbQueries.addPermit(startingAtWindowEnd)
    }

    val permits = repository.allPermitsInWindow(testDate, startHour = 18, endHour = 23).first()

    assertThat(permits).isEqualTo(listOf(eveningPermit))
  }

  @Test
  fun `allPermitsInWindow supports midnight end boundary`() = runTest {
    val latePermit =
      morningPermit.copy(
        recordId = 11L,
        start = 11.pm,
        end = 11.pm + 1.hours.inWholeMilliseconds,
        name = "Late Permit",
      )
    temporaryDatabase.db().fsdbQueries.addPermit(latePermit)

    val permits = repository.allPermitsInWindow(testDate, startHour = 18, endHour = 24).first()

    assertThat(permits).isEqualTo(listOf(latePermit))
  }

  @Test
  fun `ensure time query is inclusive start`() = runTest {
    temporaryDatabase.db().transaction {
      temporaryDatabase.db().fsdbQueries.addPermit(midnightPermit)
      temporaryDatabase.db().fsdbQueries.addPermit(noonPermit)
    }

    // Test the query with the exact parameters used by permitsFlow
    val dayEndMillis = midnight + (24.hours.inWholeMilliseconds)
    val permits =
      temporaryDatabase
        .db()
        .fsdbQueries
        .getPermits(groupName = testGroup, startTime = midnight, endTime = dayEndMillis)
        .executeAsList()

    assertThat(permits).hasSize(2)
    assertThat(permits[0]).isEqualTo(midnightPermit)
    assertThat(permits[1]).isEqualTo(noonPermit)
  }

  @Test
  fun `integration test`() = runTest {
    // Test that the database persists data across different repository calls
    val testPermit =
      morningPermit.copy(
        recordId = 5L,
        start = 10.am,
        end = 12.pm,
        type = "Practice",
        name = "Morning Practice",
        org = testOrg,
      )

    temporaryDatabase.db().fsdbQueries.addPermit(testPermit)

    val permits = repository.permitsFlow(testDate, testGroup).first()
    assertThat(permits).hasSize(1)
    assertThat(permits[0]).isEqualTo(testPermit)

    val permitsByOrg = repository.permitsByGroup(testGroup, testOrg, testDate).first()
    assertThat(permitsByOrg).hasSize(1)
    assertThat(permitsByOrg[0]).isEqualTo(testPermit)
  }

  @Test
  fun `cached areas with older version load built-in areas`() {
    val areasPath = appDirs.userData / "areas.json"
    val oldAreas = Areas(entries = Areas.default.entries, version = Areas.VERSION - 1)
    fakeFileSystem.sink(areasPath).buffer().use { it.writeUtf8(json.encodeToString(oldAreas)) }

    val loaded = repository.loadLocalAreas()
    assertThat(loaded).isSameInstanceAs(Areas.default)
    assertThat(loaded.version).isEqualTo(Areas.VERSION)
    assertThat(
        loaded.entries.single { it.areaName == "Brooklyn Bridge Park" }.fieldGroups.single().name
      )
      .isEqualTo("Pier 5")
  }

  @Test
  fun `cached areas with current version is not stale`() {
    val areasPath = appDirs.userData / "areas.json"
    val currentAreas = Areas(entries = Areas.default.entries, version = Areas.VERSION)
    fakeFileSystem.sink(areasPath).buffer().use { it.writeUtf8(json.encodeToString(currentAreas)) }

    val loaded = repository.loadLocalAreas()
    assertThat(loaded.version).isEqualTo(Areas.VERSION)
    assertThat(loaded.version < Areas.VERSION).isFalse()
  }

  @Test
  fun `cached areas without version decode as current catalog version`() {
    val areasWithoutVersion =
      """
      {
        "entries": []
      }
      """
        .trimIndent()

    val loaded = json.decodeFromString<Areas>(areasWithoutVersion)

    assertThat(loaded.version).isEqualTo(Areas.VERSION)
  }

  @Test
  fun `loadLocalAreas falls back to default when no cached file exists`() {
    val loaded = repository.loadLocalAreas()
    assertThat(loaded).isEqualTo(Areas.default)
  }

  @Test
  fun `mergeWithDefaults preserves built-in areas missing from remote`() {
    // Create a remote Areas with only the first two entries from default
    val defaultAreas = Areas.default
    val subset = defaultAreas.copy(entries = defaultAreas.entries.take(2).toImmutableList())

    val merged = mergeWithDefaults(subset, defaultAreas)

    // Merged should have ALL default areas
    assertThat(merged.entries).hasSize(defaultAreas.entries.size)
    // The first two should be from the remote (unchanged)
    assertThat(merged.entries[0]).isEqualTo(subset.entries[0])
    assertThat(merged.entries[1]).isEqualTo(subset.entries[1])
    // The rest should be filled in from defaults
    val mergedNames = merged.entries.map { it.areaName }.toSet()
    val defaultNames = defaultAreas.entries.map { it.areaName }.toSet()
    assertThat(mergedNames).isEqualTo(defaultNames)
  }

  @Test
  fun `mergeWithDefaults returns remote unchanged when it has all areas`() {
    val defaultAreas = Areas.default
    val merged = mergeWithDefaults(defaultAreas, defaultAreas)

    // Same instance — no merge needed
    assertThat(merged).isSameInstanceAs(defaultAreas)
  }

  @Test
  fun `loadLocalAreas merges cached subset with defaults`() {
    // Write a cached areas.json with only the first two entries
    val subset = Areas.default.copy(entries = Areas.default.entries.take(2).toImmutableList())
    val areasPath = appDirs.userData / "areas.json"
    fakeFileSystem.sink(areasPath).buffer().use { it.writeUtf8(json.encodeToString(subset)) }

    val loaded = repository.loadLocalAreas()

    // Should have all default areas, not just the cached subset
    val loadedNames = loaded.entries.map { it.areaName }.toSet()
    val defaultNames = Areas.default.entries.map { it.areaName }.toSet()
    assertThat(loadedNames).isEqualTo(defaultNames)
  }

  @Test
  fun `mergeWithDefaults remote updates override built-in`() {
    val defaultAreas = Areas.default
    // Create remote with the same areas but modified displayName on first entry
    val modified = defaultAreas.entries[0].copy(displayName = "Modified Name")
    val remote =
      defaultAreas.copy(
        entries = (listOf(modified) + defaultAreas.entries.drop(1)).toImmutableList()
      )

    val merged = mergeWithDefaults(remote, defaultAreas)

    // Remote's modified entry should be used, not the default
    assertThat(merged.entries[0].displayName).isEqualTo("Modified Name")
  }

  @Test
  fun `mergeWithDefaults ignores stale remote updates`() {
    val defaultAreas = Areas.default
    val modified = defaultAreas.entries[0].copy(displayName = "Modified Name")
    val staleRemote =
      defaultAreas.copy(
        entries = (listOf(modified) + defaultAreas.entries.drop(1)).toImmutableList(),
        version = Areas.VERSION - 1,
      )

    val merged = mergeWithDefaults(staleRemote, defaultAreas)

    assertThat(merged.entries[0].displayName).isEqualTo(defaultAreas.entries[0].displayName)
    assertThat(merged.version).isEqualTo(Areas.VERSION)
  }

  @Test
  fun `mergeWithDefaults preserves remote-only areas from stale remote`() {
    val defaultAreas = Areas.default
    val remoteOnly =
      Area(
        areaName = "External",
        displayName = "External",
        fieldGroups = kotlinx.collections.immutable.persistentListOf(),
      )
    val staleRemote =
      Areas(entries = kotlinx.collections.immutable.persistentListOf(remoteOnly), version = 1)

    val merged = mergeWithDefaults(staleRemote, defaultAreas)

    assertThat(merged.entries.last()).isEqualTo(remoteOnly)
    assertThat(merged.entries).hasSize(defaultAreas.entries.size + 1)
  }

  @Test
  fun `area feed import writes permits and feed metadata`() = runTest {
    val feed =
      AvailabilityAreaFeed(
        areaName = "TestArea",
        generatedAt = 200L,
        rows =
          listOf(
            AvailabilityFeedRow(
              groupName = testGroup,
              fieldId = "Soccer-01",
              start = 9.am,
              end = 11.am,
              title = "Generated Permit",
              org = testOrg,
              status = "Approved",
              kind = "generated",
              sourceId = "fixture",
            )
          ),
      )
    val manifestArea =
      AvailabilityManifestArea(areaName = "TestArea", hash = "hash", generatedAt = 100L)

    repository.importAreaFeed(feed, manifestArea, fetchedAt = 300L)

    val permits =
      temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList().sortedBy { it.start }
    assertThat(permits).hasSize(1)
    assertThat(permits[0].area).isEqualTo("TestArea")
    assertThat(permits[0].groupName).isEqualTo(testGroup)
    assertThat(permits[0].fieldId).isEqualTo("Soccer-01")
    assertThat(permits[0].name).isEqualTo("Generated Permit")
    assertThat(permits[0].isOverlap).isEqualTo(0L)
    assertThat(permits[0].advisory).isEqualTo(null)

    val metadata = temporaryDatabase.db().fsdbQueries.getAreaFeed("TestArea").executeAsOneOrNull()
    assertThat(metadata?.feedGeneratedAt).isEqualTo(200L)
    assertThat(metadata?.feedFetchedAt).isEqualTo(300L)
    assertThat(metadata?.hash).isEqualTo("hash")
  }

  @Test
  fun `area feed import preserves overlap and advisory rows`() = runTest {
    val feed =
      AvailabilityAreaFeed(
        areaName = "TestArea",
        rows =
          listOf(
            AvailabilityFeedRow(
              groupName = testGroup,
              fieldId = "Soccer-01",
              start = 1.pm,
              end = 2.pm,
              title = "Overlapping field permit",
              status = "Permit #123",
              kind = "NYC live",
              isOverlap = true,
            ),
            AvailabilityFeedRow(
              groupName = testGroup,
              fieldId = "Soccer-01",
              start = 2.pm,
              end = 3.pm,
              title = "Pending permits",
              status = "2 pending permits",
              kind = "advisory",
              advisoryText = "2 pending permits",
            ),
          ),
      )
    val manifestArea = AvailabilityManifestArea(areaName = "TestArea", hash = "hash")

    repository.importAreaFeed(feed, manifestArea, fetchedAt = 300L)

    val permits =
      temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList().sortedBy { it.start }
    assertThat(permits).hasSize(2)
    assertThat(permits[0].isOverlap).isEqualTo(1L)
    assertThat(permits[0].advisory).isEqualTo(null)
    assertThat(permits[1].type).isEqualTo("advisory")
    assertThat(permits[1].advisory).isEqualTo("2 pending permits")
  }

  @Test
  fun `area feed import replaces prior rows for the area`() = runTest {
    temporaryDatabase.db().fsdbQueries.addPermit(morningPermit)
    val feed =
      AvailabilityAreaFeed(
        areaName = "TestArea",
        rows =
          listOf(
            AvailabilityFeedRow(
              groupName = testGroup,
              fieldId = "Soccer-01",
              start = 1.pm,
              end = 2.pm,
              title = "Replacement Permit",
            )
          ),
      )
    val manifestArea = AvailabilityManifestArea(areaName = "TestArea", hash = "hash")

    repository.importAreaFeed(feed, manifestArea, fetchedAt = 300L)

    val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
    assertThat(permits).hasSize(1)
    assertThat(permits[0].name).isEqualTo("Replacement Permit")
    assertThat(permits[0].start).isEqualTo(1.pm)
  }

  @Test
  fun `populateDb imports manifest area feed`() = runTest {
    val routes =
      mutableMapOf(
        rawGithubPath("areas.json") to TestResponse(testAreasJson()),
        rawGithubPath("availability/manifest.json") to
          TestResponse(
            json.encodeToString(
              AvailabilityManifest(
                areas =
                  listOf(
                    AvailabilityManifestArea(
                      areaName = "TestArea",
                      path = "availability/areas/test-area.json",
                      hash = "hash-1",
                      generatedAt = 100L,
                    )
                  )
              )
            )
          ),
        rawGithubPath("availability/areas/test-area.json") to
          TestResponse(
            json.encodeToString(
              AvailabilityAreaFeed(
                areaName = "TestArea",
                generatedAt = 200L,
                rows =
                  listOf(
                    AvailabilityFeedRow(
                      groupName = testGroup,
                      fieldId = "Soccer-01",
                      start = 9.am,
                      end = 11.am,
                      title = "Downloaded Permit",
                      org = testOrg,
                    )
                  ),
              )
            )
          ),
      )
    val (refreshRepository, client) = testRepository(routes)
    try {
      assertThat(refreshRepository.populateDb(forceRefresh = true)).isTrue()

      val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
      assertThat(permits).hasSize(1)
      assertThat(permits[0].name).isEqualTo("Downloaded Permit")

      val metadata = temporaryDatabase.db().fsdbQueries.getAreaFeed("TestArea").executeAsOne()
      assertThat(metadata.hash).isEqualTo("hash-1")
      assertThat(metadata.feedGeneratedAt).isEqualTo(200L)
    } finally {
      client.close()
    }
  }

  @Test
  fun `populateDb failed area feed keeps prior area rows`() = runTest {
    temporaryDatabase.db().fsdbQueries.addPermit(morningPermit)
    val routes =
      mutableMapOf(
        rawGithubPath("areas.json") to TestResponse(testAreasJson()),
        rawGithubPath("availability/manifest.json") to
          TestResponse(
            json.encodeToString(
              AvailabilityManifest(
                areas =
                  listOf(
                    AvailabilityManifestArea(
                      areaName = "TestArea",
                      path = "availability/areas/test-area.json",
                      hash = "hash-2",
                    )
                  )
              )
            )
          ),
        rawGithubPath("availability/areas/test-area.json") to
          TestResponse("{}", HttpStatusCode.InternalServerError),
      )
    val (refreshRepository, client) = testRepository(routes)
    try {
      assertThat(refreshRepository.populateDb(forceRefresh = true)).isTrue()

      val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
      assertThat(permits).hasSize(1)
      assertThat(permits[0]).isEqualTo(morningPermit)
    } finally {
      client.close()
    }
  }

  @Test
  fun `populateDb skips network when repo data is newer than an hour`() = runTest {
    val now = System.now().toEpochMilliseconds()
    writeCachedAreas()
    writeCachedManifest("hash-1")
    temporaryDatabase
      .db()
      .fsdbQueries
      .setMetadata("last_areas_app_version", BuildConfig.VERSION_CODE.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_areas_fetch_at", now.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_manifest_fetch_at", now.toString())
    repository.importAreaFeed(
      downloadedFeed("Cached Permit"),
      cachedManifest("hash-1").areas.single(),
      now,
    )

    assertThat(repository.populateDb(forceRefresh = false)).isTrue()

    val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
    assertThat(permits).hasSize(1)
    assertThat(permits.single().name).isEqualTo("Cached Permit")
  }

  @Test
  fun `populateDb refreshes area feeds older than an hour`() = runTest {
    val oldFetch = System.now().toEpochMilliseconds() - 2.hours.inWholeMilliseconds
    writeCachedAreas()
    writeCachedManifest("hash-1")
    temporaryDatabase
      .db()
      .fsdbQueries
      .setMetadata("last_areas_app_version", BuildConfig.VERSION_CODE.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_areas_fetch_at", oldFetch.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_manifest_fetch_at", oldFetch.toString())
    repository.importAreaFeed(
      downloadedFeed("Cached Permit"),
      cachedManifest("hash-1").areas.single(),
      oldFetch,
    )
    val routes =
      mutableMapOf(
        rawGithubPath("areas.json") to TestResponse(testAreasJson()),
        rawGithubPath("availability/manifest.json") to
          TestResponse(json.encodeToString(cachedManifest("hash-1"))),
        rawGithubPath("availability/areas/test-area.json") to
          TestResponse(json.encodeToString(downloadedFeed("Hourly Refresh Permit"))),
      )
    val (refreshRepository, client) = testRepository(routes)
    try {
      assertThat(refreshRepository.populateDb(forceRefresh = false)).isTrue()

      val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
      assertThat(permits).hasSize(1)
      assertThat(permits.single().name).isEqualTo("Hourly Refresh Permit")
    } finally {
      client.close()
    }
  }

  @Test
  fun `populateDb failed hourly area feed refresh keeps prior rows`() = runTest {
    val oldFetch = System.now().toEpochMilliseconds() - 2.hours.inWholeMilliseconds
    writeCachedAreas()
    writeCachedManifest("hash-1")
    temporaryDatabase
      .db()
      .fsdbQueries
      .setMetadata("last_areas_app_version", BuildConfig.VERSION_CODE.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_areas_fetch_at", oldFetch.toString())
    temporaryDatabase.db().fsdbQueries.setMetadata("last_manifest_fetch_at", oldFetch.toString())
    repository.importAreaFeed(
      downloadedFeed("Cached Permit"),
      cachedManifest("hash-1").areas.single(),
      oldFetch,
    )
    val routes =
      mutableMapOf(
        rawGithubPath("areas.json") to TestResponse(testAreasJson()),
        rawGithubPath("availability/manifest.json") to
          TestResponse(json.encodeToString(cachedManifest("hash-1"))),
        rawGithubPath("availability/areas/test-area.json") to
          TestResponse("{}", HttpStatusCode.InternalServerError),
      )
    val (refreshRepository, client) = testRepository(routes)
    try {
      assertThat(refreshRepository.populateDb(forceRefresh = false)).isTrue()

      val permits = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
      assertThat(permits).hasSize(1)
      assertThat(permits.single().name).isEqualTo("Cached Permit")
    } finally {
      client.close()
    }
  }

  @Test
  fun `populateDb malformed downloaded areas keeps cached catalog`() = runTest {
    val cachedAreas =
      Areas(
        entries =
          persistentListOf(
            Area(
              areaName = "CachedArea",
              displayName = "Cached Area",
              fieldGroups = persistentListOf(),
            )
          ),
        version = Areas.VERSION,
      )
    val areasPath = appDirs.userData / "areas.json"
    fakeFileSystem.sink(areasPath).buffer().use { it.writeUtf8(json.encodeToString(cachedAreas)) }
    val routes =
      mutableMapOf(
        rawGithubPath("areas.json") to TestResponse("{"),
        rawGithubPath("availability/manifest.json") to
          TestResponse(json.encodeToString(AvailabilityManifest())),
      )
    val (refreshRepository, client) = testRepository(routes)
    try {
      assertThat(refreshRepository.populateDb(forceRefresh = true)).isTrue()

      assertThat(
          refreshRepository.areasFlow().value.entries.map { it.areaName }.contains("CachedArea")
        )
        .isTrue()
      assertThat(fakeFileSystem.source(areasPath).buffer().use { it.readUtf8() })
        .isEqualTo(json.encodeToString(cachedAreas))
    } finally {
      client.close()
    }
  }

  @Test
  fun `area deletion`() = runTest {
    val area1Permit =
      DbPermit(
        recordId = 6L,
        area = "Area1",
        groupName = "Group1",
        start = 1000L,
        end = 2000L,
        fieldId = "Field1",
        type = "Type1",
        name = "Permit1",
        org = "Org1",
        status = "Approved",
        isOverlap = 0L,
        advisory = null,
      )

    val area2Permit =
      DbPermit(
        recordId = 7L,
        area = "Area2",
        groupName = "Group2",
        start = 3000L,
        end = 4000L,
        fieldId = "Field2",
        type = "Type2",
        name = "Permit2",
        org = "Org2",
        status = "Approved",
        isOverlap = 0L,
        advisory = null,
      )

    temporaryDatabase.db().transaction {
      temporaryDatabase.db().fsdbQueries.addPermit(area1Permit)
      temporaryDatabase.db().fsdbQueries.addPermit(area2Permit)
    }

    val allPermitsBefore = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
    assertThat(allPermitsBefore).hasSize(2)

    temporaryDatabase.db().fsdbQueries.deleteAreaPermits("Area1")

    val allPermitsAfter = temporaryDatabase.db().fsdbQueries.getAllPermits().executeAsList()
    assertThat(allPermitsAfter).hasSize(1)
    assertThat(allPermitsAfter[0]).isEqualTo(area2Permit)
  }
}
