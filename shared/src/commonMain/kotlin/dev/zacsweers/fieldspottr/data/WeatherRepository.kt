// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.delete
import dev.zacsweers.fieldspottr.touch
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.math.roundToInt
import kotlin.time.Clock.System
import kotlin.time.Duration.Companion.hours
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.buffer
import okio.use

/**
 * Open-Meteo forecast for lower Manhattan. All tracked fields are within a couple miles of each
 * other, so a single city-level forecast is plenty (the Apollo approach: one unobtrusive line of
 * weather, not a weather app).
 */
private const val OPEN_METEO_URL =
  "https://api.open-meteo.com/v1/forecast" +
    "?latitude=40.72&longitude=-73.98" +
    "&timezone=America%2FNew_York" +
    "&temperature_unit=fahrenheit" +
    "&forecast_days=8" +
    "&current=temperature_2m,weather_code" +
    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
    "&hourly=weather_code,precipitation_probability"

private val WEATHER_REFRESH_INTERVAL = 1.hours

/** Probability (%) at or above which we consider an hour "rainy" for UI affordances. */
const val PRECIP_PROBABILITY_THRESHOLD = 40

enum class WeatherCondition(val label: String) {
  CLEAR("Clear"),
  PARTLY_CLOUDY("Partly cloudy"),
  CLOUDY("Cloudy"),
  FOG("Foggy"),
  DRIZZLE("Drizzle"),
  RAIN("Rain"),
  SNOW("Snow"),
  THUNDERSTORM("Thunderstorms");

  val isPrecipitation: Boolean
    get() =
      when (this) {
        DRIZZLE,
        RAIN,
        SNOW,
        THUNDERSTORM -> true
        else -> false
      }

  companion object {
    /** Maps a [WMO weather code](https://open-meteo.com/en/docs) to a coarse condition. */
    fun fromWmoCode(code: Int): WeatherCondition {
      return when (code) {
        0,
        1 -> CLEAR
        2 -> PARTLY_CLOUDY
        3 -> CLOUDY
        45,
        48 -> FOG
        in 51..57 -> DRIZZLE
        in 61..67,
        in 80..82 -> RAIN
        in 71..77,
        85,
        86 -> SNOW
        95,
        96,
        99 -> THUNDERSTORM
        else -> CLOUDY
      }
    }
  }
}

@Immutable data class CurrentWeather(val tempF: Int, val condition: WeatherCondition)

@Immutable
data class DailyForecast(
  val date: LocalDate,
  val condition: WeatherCondition,
  val highF: Int,
  val lowF: Int,
  /** 0-100, or null if unavailable. */
  val precipProbability: Int?,
)

@Immutable
data class HourlyForecast(
  val date: LocalDate,
  val hour: Int,
  val condition: WeatherCondition,
  /** 0-100. */
  val precipProbability: Int,
) {
  val isRainy: Boolean
    get() = condition.isPrecipitation && precipProbability >= PRECIP_PROBABILITY_THRESHOLD
}

@Immutable
data class WeatherForecast(
  val current: CurrentWeather?,
  val daily: ImmutableList<DailyForecast>,
  val hourly: ImmutableList<HourlyForecast>,
) {
  private val dailyByDate by lazy { daily.associateBy { it.date } }
  private val hourlyByDate by lazy { hourly.groupBy { it.date } }

  fun daily(date: LocalDate): DailyForecast? = dailyByDate[date]

  fun hourly(date: LocalDate): List<HourlyForecast> = hourlyByDate[date].orEmpty()

  fun hourlyAt(date: LocalDate, hour: Int): HourlyForecast? {
    return hourlyByDate[date]?.firstOrNull { it.hour == hour }
  }

  /** Max precipitation probability in [startHour, endHour) on [date], or null if no data. */
  fun maxPrecipProbability(date: LocalDate, startHour: Int, endHour: Int): Int? {
    return hourly(date)
      .filter { it.hour in startHour until endHour }
      .maxOfOrNull { it.precipProbability }
  }

  /** First rainy hour at/after [fromHour] on [date], if any. */
  fun nextRainyHour(date: LocalDate, fromHour: Int = 0): HourlyForecast? {
    return hourly(date).firstOrNull { it.hour >= fromHour && it.isRainy }
  }

  /** True if any hour in [startHour, endHour) on [date] is rainy. */
  fun isRainyWindow(date: LocalDate, startHour: Int, endHour: Int): Boolean {
    return hourly(date).any { it.hour in startHour until endHour && it.isRainy }
  }
}

@SingleIn(AppScope::class)
@Inject
class WeatherRepository(
  private val appDirs: FSAppDirs,
  private val json: Json,
  logger: Logger,
  private val client: Lazy<HttpClient>,
) {
  private val logger = logger.withTag("WeatherRepository")
  private val cachePath by lazy { appDirs.userData / "weather.json" }

  private val forecastStateFlow = MutableStateFlow<WeatherForecast?>(null)

  /** Latest known forecast, or null if nothing has been loaded yet. */
  val forecast: StateFlow<WeatherForecast?> = forecastStateFlow

  // Only one refresh at a time - concurrent calls are dropped
  private val refreshSemaphore = Channel<Unit>(1)

  suspend fun refresh(forceRefresh: Boolean = false) {
    if (!refreshSemaphore.trySend(Unit).isSuccess) return
    try {
      withContext(Dispatchers.IO) {
        val now = System.now().toEpochMilliseconds()
        val cached = readCache()
        if (cached != null && forecastStateFlow.value == null) {
          forecastStateFlow.value = cached.response.toWeatherForecast()
        }
        val isFresh =
          cached != null && now - cached.fetchedAt < WEATHER_REFRESH_INTERVAL.inWholeMilliseconds
        if (!forceRefresh && isFresh) return@withContext

        val body =
          try {
            val response = client.value.get(OPEN_METEO_URL)
            if (response.status.value !in 200..299) {
              logger.i { "Weather fetch failed: ${response.status}" }
              return@withContext
            }
            response.bodyAsText()
          } catch (e: Exception) {
            logger.e(e) { "Failed to fetch weather" }
            return@withContext
          }
        val response =
          try {
            json.decodeFromString<OpenMeteoResponse>(body)
          } catch (e: Exception) {
            logger.e(e) { "Failed to decode weather response" }
            return@withContext
          }
        forecastStateFlow.value = response.toWeatherForecast()
        writeCache(CachedWeather(fetchedAt = now, response = response))
      }
    } finally {
      refreshSemaphore.tryReceive()
    }
  }

  private fun readCache(): CachedWeather? {
    return try {
      if (!appDirs.fs.exists(cachePath)) return null
      json.decodeFromString<CachedWeather>(
        appDirs.fs.source(cachePath).buffer().use { it.readUtf8() }
      )
    } catch (e: Exception) {
      logger.e(e) { "Failed to read weather cache" }
      null
    }
  }

  private fun writeCache(cached: CachedWeather) {
    try {
      if (appDirs.fs.exists(cachePath)) {
        appDirs.delete(cachePath)
      }
      appDirs.touch(cachePath)
      appDirs.fs.sink(cachePath).buffer().use { it.writeUtf8(json.encodeToString(cached)) }
    } catch (e: Exception) {
      logger.e(e) { "Failed to write weather cache" }
    }
  }
}

@Serializable
internal data class CachedWeather(val fetchedAt: Long, val response: OpenMeteoResponse)

@Serializable
internal data class OpenMeteoResponse(
  val current: OpenMeteoCurrent? = null,
  val daily: OpenMeteoDaily? = null,
  val hourly: OpenMeteoHourly? = null,
)

@Serializable
internal data class OpenMeteoCurrent(
  @SerialName("temperature_2m") val temperature: Double? = null,
  @SerialName("weather_code") val weatherCode: Int? = null,
)

@Serializable
internal data class OpenMeteoDaily(
  val time: List<String> = emptyList(),
  @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
  @SerialName("temperature_2m_max") val temperatureMax: List<Double?> = emptyList(),
  @SerialName("temperature_2m_min") val temperatureMin: List<Double?> = emptyList(),
  @SerialName("precipitation_probability_max")
  val precipProbabilityMax: List<Int?> = emptyList(),
)

@Serializable
internal data class OpenMeteoHourly(
  val time: List<String> = emptyList(),
  @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
  @SerialName("precipitation_probability") val precipProbability: List<Int?> = emptyList(),
)

internal fun OpenMeteoResponse.toWeatherForecast(): WeatherForecast {
  val currentWeather =
    current?.let { c ->
      val temp = c.temperature ?: return@let null
      val code = c.weatherCode ?: return@let null
      CurrentWeather(tempF = temp.roundToInt(), condition = WeatherCondition.fromWmoCode(code))
    }

  val dailyForecasts =
    daily?.time.orEmpty().mapIndexedNotNull { i, dateString ->
      val date = dateString.toLocalDateOrNull() ?: return@mapIndexedNotNull null
      val code = daily?.weatherCode?.getOrNull(i) ?: return@mapIndexedNotNull null
      val high = daily.temperatureMax.getOrNull(i) ?: return@mapIndexedNotNull null
      val low = daily.temperatureMin.getOrNull(i) ?: return@mapIndexedNotNull null
      DailyForecast(
        date = date,
        condition = WeatherCondition.fromWmoCode(code),
        highF = high.roundToInt(),
        lowF = low.roundToInt(),
        precipProbability = daily.precipProbabilityMax.getOrNull(i),
      )
    }

  val hourlyForecasts =
    hourly?.time.orEmpty().mapIndexedNotNull { i, timeString ->
      val dateTime = timeString.toLocalDateTimeOrNull() ?: return@mapIndexedNotNull null
      val code = hourly?.weatherCode?.getOrNull(i) ?: return@mapIndexedNotNull null
      HourlyForecast(
        date = dateTime.date,
        hour = dateTime.hour,
        condition = WeatherCondition.fromWmoCode(code),
        precipProbability = hourly.precipProbability.getOrNull(i) ?: 0,
      )
    }

  return WeatherForecast(
    current = currentWeather,
    daily = dailyForecasts.toImmutableList(),
    hourly = hourlyForecasts.toImmutableList(),
  )
}

private fun String.toLocalDateOrNull(): LocalDate? {
  return try {
    LocalDate.parse(this)
  } catch (_: Exception) {
    null
  }
}

private fun String.toLocalDateTimeOrNull(): LocalDateTime? {
  return try {
    LocalDateTime.parse(this)
  } catch (_: Exception) {
    null
  }
}
