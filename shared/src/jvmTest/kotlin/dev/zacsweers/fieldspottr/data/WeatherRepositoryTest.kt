// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

class WeatherRepositoryTest {

  private val json = Json { ignoreUnknownKeys = true }

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
    assertThat(forecast.current!!.condition).isEqualTo(WeatherCondition.PARTLY_CLOUDY)

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
}
