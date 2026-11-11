// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import androidx.compose.runtime.Immutable
import dev.zacsweers.fieldspottr.FSAppDirs
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.inFile
import dev.zacsweers.fieldspottr.data.core.refresh
import dev.zacsweers.fieldspottr.data.core.values
import dev.zacsweers.fieldspottr.di.IoDispatcher
import dev.zacsweers.fieldspottr.util.toNyLocalDateTime
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ForScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
    "precipitation_probability_max,sunset" +
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
  /** Sunset in New York time, or null if unavailable. */
  val sunset: LocalTime? = null,
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
class WeatherRepository
internal constructor(
  data: DataScope,
  store: QueryStore<OpenMeteoResponse>,
  private val json: Json,
  private val client: Lazy<HttpClient>,
) {

  @Inject
  constructor(
    @ForScope(AppScope::class) data: DataScope,
    appDirs: FSAppDirs,
    json: Json,
    client: Lazy<HttpClient>,
    @IoDispatcher io: CoroutineDispatcher,
  ) : this(
    data,
    // The cache file, kept open for the app's life and read and written on the I/O dispatcher
    QueryStore.inFile<OpenMeteoResponse>(
      scope = data.scope + io,
      path = { appDirs.userData / "weather.json" },
      json = json,
      fileSystem = appDirs.fs,
    ),
    json,
    client,
  )

  private val query =
    data.query(
      store = store,
      freshness = Freshness.staleAfter(WEATHER_REFRESH_INTERVAL),
      fetch = ::fetchForecast,
    )

  /** Latest known forecast, or null until one loads. Collecting it fetches first when stale. */
  val forecast: Flow<WeatherForecast?> = query.data.values().map { it?.toWeatherForecast() }

  init {
    // A forecast stored before the app read Open-Meteo's offset has no sunset, and it can stay
    // fresh for an hour after an update. Replace it once, so the sunset shows right away.
    data.scope.launch {
      val stored = query.stored.first()
      if (stored != null && stored.value.utcOffsetSeconds == null) {
        query.requestFetch(force = true)
      }
    }
  }

  /** Fetches now, even when the stored forecast is fresh. */
  fun refresh(): Operation = query.refresh()

  private suspend fun fetchForecast(): OpenMeteoResponse {
    val response = client.value.get(OPEN_METEO_URL)
    check(response.status.isSuccess()) { "Weather fetch failed: ${response.status}" }
    return json.decodeFromString<OpenMeteoResponse>(response.bodyAsText())
  }
}

@Serializable
internal data class OpenMeteoResponse(
  /**
   * The offset of every local time in the response. Open-Meteo applies the one in effect when it
   * answers, even to times on the far side of a daylight saving change. Null in forecasts stored
   * before the app read it.
   */
  @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int? = null,
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
  @SerialName("precipitation_probability_max") val precipProbabilityMax: List<Int?> = emptyList(),
  val sunset: List<String?> = emptyList(),
)

@Serializable
internal data class OpenMeteoHourly(
  val time: List<String> = emptyList(),
  @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
  @SerialName("precipitation_probability") val precipProbability: List<Int?> = emptyList(),
)

internal fun OpenMeteoResponse.toWeatherForecast(): WeatherForecast {
  val currentWeather = current?.let { c ->
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
        sunset = daily.sunset.getOrNull(i)?.toNyLocalDateTimeOrNull(utcOffsetSeconds)?.time,
      )
    }

  val hourlyForecasts =
    hourly?.time.orEmpty().mapIndexedNotNull { i, timeString ->
      val dateTime =
        timeString.toNyLocalDateTimeOrNull(utcOffsetSeconds) ?: return@mapIndexedNotNull null
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

/**
 * Reads an Open-Meteo local time as New York time, through the response's [utcOffsetSeconds]. A
 * time stored without an offset is already New York time, except across a daylight saving change.
 */
private fun String.toNyLocalDateTimeOrNull(utcOffsetSeconds: Int?): LocalDateTime? {
  val local = toLocalDateTimeOrNull() ?: return null
  if (utcOffsetSeconds == null) {
    return local
  }
  return local.toInstant(UtcOffset(seconds = utcOffsetSeconds)).toNyLocalDateTime()
}
