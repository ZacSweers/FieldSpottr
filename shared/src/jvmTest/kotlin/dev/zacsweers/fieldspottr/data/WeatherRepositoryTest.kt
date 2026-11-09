// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.FakeFSAppDirs
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.inFile
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okio.fakefilesystem.FakeFileSystem

class WeatherRepositoryTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val fs = FakeFileSystem()
  private val appDirs = FakeFSAppDirs(fs)
  private val cachePath = appDirs.userData / "weather.json"
  private lateinit var data: TestDataScope
  private val requests = AtomicInteger()
  private var responseStatus = HttpStatusCode.OK
  private var responseGate = CompletableDeferred(Unit)
  private val client =
    HttpClient(
      MockEngine {
        requests.incrementAndGet()
        responseGate.await()
        respond(
          sampleResponse,
          responseStatus,
          headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    )

  @AfterTest
  fun closeHttpClient() {
    client.close()
  }

  private val sampleResponse =
    """
    {
      "utc_offset_seconds": -14400,
      "current": {"temperature_2m": 72.6, "weather_code": 2},
      "daily": {
        "time": ["2026-06-10", "2026-06-11"],
        "weather_code": [2, 61],
        "temperature_2m_max": [73.4, 64.2],
        "temperature_2m_min": [58.1, 55.0],
        "precipitation_probability_max": [20, 80],
        "sunset": ["2026-06-10T20:28", "2026-06-11T20:29"]
      },
      "hourly": {
        "time": ["2026-06-10T15:00", "2026-06-10T18:00", "2026-06-10T19:00", "2026-06-11T12:00"],
        "weather_code": [1, 61, 61, 63],
        "precipitation_probability": [5, 60, 70, 90]
      }
    }
    """
      .trimIndent()

  private val date = LocalDate(2026, 6, 10)

  @Test
  fun `open-meteo response maps to forecast`() {
    val forecast = json.decodeFromString<OpenMeteoResponse>(sampleResponse).toWeatherForecast()

    assertThat(forecast.current).isNotNull()
    assertThat(forecast.current!!.tempF).isEqualTo(73)
    assertThat(forecast.current.condition).isEqualTo(WeatherCondition.PARTLY_CLOUDY)

    val daily = forecast.daily(date)
    assertThat(daily).isNotNull()
    assertThat(daily!!.highF).isEqualTo(73)
    assertThat(daily.lowF).isEqualTo(58)
    assertThat(daily.precipProbability).isEqualTo(20)
    assertThat(daily.sunset).isEqualTo(LocalTime(20, 28))

    assertThat(forecast.hourlyAt(date, 15)!!.condition).isEqualTo(WeatherCondition.CLEAR)
    assertThat(forecast.hourlyAt(date, 18)!!.precipProbability).isEqualTo(60)
  }

  @Test
  fun `rainy hour helpers respect threshold and window`() {
    val forecast = json.decodeFromString<OpenMeteoResponse>(sampleResponse).toWeatherForecast()

    // 3pm is clear with 5% - not rainy. First rainy hour is 6pm.
    assertThat(forecast.nextRainyHour(date, fromHour = 12)!!.hour).isEqualTo(18)
    assertThat(forecast.nextRainyHour(date, fromHour = 19)!!.hour).isEqualTo(19)
    assertThat(forecast.nextRainyHour(date, fromHour = 20)).isNull()

    assertThat(forecast.isRainyWindow(date, 12, 18)).isFalse()
    assertThat(forecast.isRainyWindow(date, 18, 23)).isTrue()
  }

  @Test
  fun `wmo codes map to coarse conditions`() {
    assertThat(WeatherCondition.fromWmoCode(0)).isEqualTo(WeatherCondition.CLEAR)
    assertThat(WeatherCondition.fromWmoCode(3)).isEqualTo(WeatherCondition.CLOUDY)
    assertThat(WeatherCondition.fromWmoCode(45)).isEqualTo(WeatherCondition.FOG)
    assertThat(WeatherCondition.fromWmoCode(53)).isEqualTo(WeatherCondition.DRIZZLE)
    assertThat(WeatherCondition.fromWmoCode(65)).isEqualTo(WeatherCondition.RAIN)
    assertThat(WeatherCondition.fromWmoCode(80)).isEqualTo(WeatherCondition.RAIN)
    assertThat(WeatherCondition.fromWmoCode(75)).isEqualTo(WeatherCondition.SNOW)
    assertThat(WeatherCondition.fromWmoCode(95)).isEqualTo(WeatherCondition.THUNDERSTORM)
  }

  @Test
  fun `malformed entries are skipped`() {
    val response =
      json.decodeFromString<OpenMeteoResponse>(
        """
        {
          "daily": {
            "time": ["not-a-date", "2026-06-11"],
            "weather_code": [2, 61],
            "temperature_2m_max": [73.4, 64.2],
            "temperature_2m_min": [58.1, 55.0],
            "precipitation_probability_max": [20, 80]
          }
        }
        """
          .trimIndent()
      )
    val forecast = response.toWeatherForecast()

    assertThat(forecast.current).isNull()
    assertThat(forecast.daily.size).isEqualTo(1)
    assertThat(forecast.daily.single().date).isEqualTo(LocalDate(2026, 6, 11))
  }

  @Test
  fun `times across a daylight saving change read as new york time`() {
    // Open-Meteo answered in EST, so it wrote the times before the November 1 change an hour early
    val forecast =
      json
        .decodeFromString<OpenMeteoResponse>(
          """
          {
            "utc_offset_seconds": -18000,
            "daily": {
              "time": ["2026-10-31", "2026-11-02"],
              "weather_code": [2, 2],
              "temperature_2m_max": [60.0, 58.0],
              "temperature_2m_min": [45.0, 44.0],
              "sunset": ["2026-10-31T16:53", "2026-11-02T16:51"]
            },
            "hourly": {
              "time": ["2026-10-31T14:00", "2026-11-02T14:00"],
              "weather_code": [61, 61],
              "precipitation_probability": [70, 70]
            }
          }
          """
            .trimIndent()
        )
        .toWeatherForecast()

    assertThat(forecast.daily(LocalDate(2026, 10, 31))!!.sunset).isEqualTo(LocalTime(17, 53))
    assertThat(forecast.daily(LocalDate(2026, 11, 2))!!.sunset).isEqualTo(LocalTime(16, 51))
    assertThat(forecast.hourly(LocalDate(2026, 10, 31)).single().hour).isEqualTo(15)
    assertThat(forecast.hourly(LocalDate(2026, 11, 2)).single().hour).isEqualTo(14)
  }

  @Test
  fun `stored forecasts without an offset keep their times`() {
    val forecast =
      json
        .decodeFromString<OpenMeteoResponse>(
          """
          {
            "daily": {
              "time": ["2026-06-10"],
              "weather_code": [2],
              "temperature_2m_max": [73.4],
              "temperature_2m_min": [58.1],
              "sunset": ["2026-06-10T20:28"]
            }
          }
          """
            .trimIndent()
        )
        .toWeatherForecast()

    assertThat(forecast.daily(date)!!.sunset).isEqualTo(LocalTime(20, 28))
  }

  @Test
  fun `empty cache fetches and stores the forecast`() = runTest {
    val repository = repository()

    val forecast = repository.forecast.first { it != null }
    assertThat(forecast!!.current!!.tempF).isEqualTo(FETCHED_TEMP)
    assertThat(requests.get()).isEqualTo(1)
    assertThat(cachedAt()).isEqualTo(START.toEpochMilliseconds())
  }

  @Test
  fun `fresh cache is not fetched on collect`() = runTest {
    writeCache(fetchedAt = START - 10.minutes)
    val repository = repository()

    assertThat(repository.forecast.first()!!.current!!.tempF).isEqualTo(STORED_TEMP)
    // A refresh queues behind the collector's staleness check, so this counts only its own request
    repository.refresh().awaitSucceeded()
    assertThat(requests.get()).isEqualTo(1)
  }

  @Test
  fun `fresh cache from before the sunset is fetched again`() = runTest {
    writeCache(fetchedAt = START - 10.minutes, withOffset = false)
    val repository = repository()

    val forecast = repository.forecast.first { it?.current?.tempF == FETCHED_TEMP }
    assertThat(forecast!!.daily(date)!!.sunset).isEqualTo(LocalTime(20, 28))
    assertThat(requests.get()).isEqualTo(1)
  }

  @Test
  fun `stale cache shows the stored forecast, then the fetched one`() = runTest {
    writeCache(fetchedAt = START - 2.hours)
    responseGate = CompletableDeferred()
    val repository = repository()

    repository.forecast.filterNotNull().test {
      assertThat(awaitItem().current!!.tempF).isEqualTo(STORED_TEMP)
      responseGate.complete(Unit)
      assertThat(awaitItem().current!!.tempF).isEqualTo(FETCHED_TEMP)
    }
    assertThat(requests.get()).isEqualTo(1)
  }

  @Test
  fun `collectors share one fetch`() = runTest {
    responseGate = CompletableDeferred()
    val repository = repository()

    val collectors = List(3) { async { repository.forecast.first { it != null } } }
    testScheduler.runCurrent()
    responseGate.complete(Unit)
    collectors.awaitAll()
    assertThat(requests.get()).isEqualTo(1)
  }

  @Test
  fun `refresh fetches even when the cache is fresh`() = runTest {
    writeCache(fetchedAt = START - 10.minutes)
    val repository = repository()

    repository.refresh().awaitSucceeded()
    assertThat(requests.get()).isEqualTo(1)
    assertThat(cachedAt()).isEqualTo(START.toEpochMilliseconds())
    assertThat(repository.forecast.first()!!.current!!.tempF).isEqualTo(FETCHED_TEMP)
  }

  @Test
  fun `failed fetch keeps the stored forecast and fails the handle`() = runTest {
    writeCache(fetchedAt = START - 10.minutes)
    responseStatus = HttpStatusCode.InternalServerError
    val repository = repository()

    repository.refresh().awaitFailed<Throwable>()
    data.fetchFailures.assertSingle<Throwable>()
    assertThat(repository.forecast.first()!!.current!!.tempF).isEqualTo(STORED_TEMP)
  }

  @Test
  fun `unreadable cache is treated as empty`() = runTest {
    fs.write(cachePath) { writeUtf8("not json") }
    val repository = repository()

    val forecast = repository.forecast.first { it != null }
    assertThat(forecast!!.current!!.tempF).isEqualTo(FETCHED_TEMP)
    assertThat(requests.get()).isEqualTo(1)
  }

  private fun TestScope.repository(): WeatherRepository {
    data = testDataScope(clock = testClock(START))
    val store =
      QueryStore.inFile<OpenMeteoResponse>(backgroundScope, { cachePath }, json, fileSystem = fs)
    return WeatherRepository(data, store, json, lazyOf(client))
  }

  /**
   * Writes the file as the store does: the response under "value" beside its fetch time. Without
   * [withOffset], it matches a forecast stored before the app read Open-Meteo's offset.
   */
  private fun writeCache(fetchedAt: Instant, withOffset: Boolean = true) {
    var response = sampleResponse.replace("72.6", "50.0")
    if (!withOffset) {
      response = response.replace("\"utc_offset_seconds\": -14400,", "")
    }
    val stored = json.parseToJsonElement(response)
    val record = buildJsonObject {
      put("fetchedAt", fetchedAt.toEpochMilliseconds())
      put("value", stored)
    }
    fs.write(cachePath) { writeUtf8(record.toString()) }
  }

  /** The fetch time the file holds, in epoch milliseconds. */
  private fun cachedAt(): Long =
    json
      .parseToJsonElement(fs.read(cachePath) { readUtf8() })
      .jsonObject
      .getValue("fetchedAt")
      .jsonPrimitive
      .long

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
    const val STORED_TEMP = 50
    const val FETCHED_TEMP = 73
  }
}
