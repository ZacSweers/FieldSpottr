// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.DbPermit
import dev.zacsweers.fieldspottr.FakeFSAppDirs
import dev.zacsweers.fieldspottr.util.atStartOfDayInNy
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
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
