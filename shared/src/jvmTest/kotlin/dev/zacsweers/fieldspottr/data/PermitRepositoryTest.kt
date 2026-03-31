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
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FakeFSAppDirs
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
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
  private val repository =
    PermitRepository(appDirs, json, Logger.Companion, suspend { temporaryDatabase.db() })

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
    )

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
  fun `cached areas with older version is stale`() {
    val areasPath = appDirs.userData / "areas.json"
    val oldAreas = Areas(entries = Areas.default.entries, version = Areas.VERSION - 1)
    fakeFileSystem.sink(areasPath).buffer().use { it.writeUtf8(json.encodeToString(oldAreas)) }

    val loaded = repository.loadLocalAreas()
    assertThat(loaded.version).isEqualTo(Areas.VERSION - 1)
    assertThat(loaded.version < Areas.VERSION).isTrue()
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
