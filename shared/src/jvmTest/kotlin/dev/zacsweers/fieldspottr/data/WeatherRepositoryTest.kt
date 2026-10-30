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
import kotlinx.serialization.json.Json
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
      "current": {"temperature_2m": 72.6, "weather_code": 2},
      "daily": {
        "time": ["2026-06-10", "2026-06-11"],
        "weather_code": [2, 61],
        "temperature_2m_max": [73.4, 64.2],
        "temperature_2m_min": [58.1, 55.0],
        "precipitation_probability_max": [20, 80]
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
  fun `empty cache fetches and stores the forecast`() = runTest {
    val repository = repository()

    val forecast = repository.forecast.first { it != null }
    assertThat(forecast!!.current!!.tempF).isEqualTo(FETCHED_TEMP)
    assertThat(requests.get()).isEqualTo(1)
    assertThat(readCache().fetchedAt).isEqualTo(START.toEpochMilliseconds())
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
    assertThat(readCache().fetchedAt).isEqualTo(START.toEpochMilliseconds())
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
    return WeatherRepository(
      data,
      weatherStore(appDirs, json, backgroundScope),
      json,
      lazyOf(client),
    )
  }

  private fun writeCache(fetchedAt: Instant) {
    val stored = json.decodeFromString<OpenMeteoResponse>(sampleResponse.replace("72.6", "50.0"))
    val cached = CachedWeather(fetchedAt.toEpochMilliseconds(), stored)
    fs.write(cachePath) { writeUtf8(json.encodeToString(cached)) }
  }

  private fun readCache(): CachedWeather = json.decodeFromString(fs.read(cachePath) { readUtf8() })

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
    const val STORED_TEMP = 50
    const val FETCHED_TEMP = 73
  }
}
