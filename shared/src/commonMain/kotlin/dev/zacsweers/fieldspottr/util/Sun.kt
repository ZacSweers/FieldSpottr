// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.util

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Lower Manhattan. Every tracked field is within a few miles, so one sunset fits them all. */
private const val NYC_LATITUDE = 40.72
private const val NYC_LONGITUDE = -73.98

/** Julian dates of the J2000 epoch (2000-01-01 12:00 UTC) and the Unix epoch. */
private const val J2000 = 2451545.0
private const val UNIX_EPOCH_JULIAN_DATE = 2440587.5
private const val MINUTES_PER_DAY = 1440.0

/**
 * Sunset in New York time on [date] for lower Manhattan, computed with NOAA's
 * [solar calculator](https://gml.noaa.gov/grad/solcalc/calcdetails.html) equations. It needs no
 * network, so it covers any date the grid shows, and it matches NOAA to about a minute.
 */
fun nycSunset(date: LocalDate): LocalTime {
  // The sun's position depends on the time of sunset, so refine from a late-afternoon guess
  var utcMinutes = 22.0 * 60
  repeat(3) { utcMinutes = sunsetUtcMinutes(date, utcMinutes) }
  val sunset = date.atStartOfDayIn(TimeZone.UTC) + (utcMinutes * 60_000).roundToLong().milliseconds
  return sunset.toNyLocalDateTime().time
}

/** Sunset on [date] in minutes after midnight UTC, with the sun placed at [atUtcMinutes]. */
private fun sunsetUtcMinutes(date: LocalDate, atUtcMinutes: Double): Double {
  val julianDay = date.toEpochDays() + UNIX_EPOCH_JULIAN_DATE + atUtcMinutes / MINUTES_PER_DAY
  val t = (julianDay - J2000) / 36525.0
  val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
  val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
  val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
  val center =
    sin(meanAnomaly.rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
      sin(2 * meanAnomaly.rad) * (0.019993 - 0.000101 * t) +
      sin(3 * meanAnomaly.rad) * 0.000289
  val omega = 125.04 - 1934.136 * t
  val apparentLongitude = meanLongitude + center - 0.00569 - 0.00478 * sin(omega.rad)
  val meanObliquity =
    23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
  val obliquity = meanObliquity + 0.00256 * cos(omega.rad)
  val declination = asin(sin(obliquity.rad) * sin(apparentLongitude.rad))

  val y = tan(obliquity.rad / 2).let { it * it }
  val equationOfTime =
    4 *
      (y * sin(2 * meanLongitude.rad) - 2 * eccentricity * sin(meanAnomaly.rad) +
          4 * eccentricity * y * sin(meanAnomaly.rad) * cos(2 * meanLongitude.rad) -
          0.5 * y * y * sin(4 * meanLongitude.rad) -
          1.25 * eccentricity * eccentricity * sin(2 * meanAnomaly.rad))
        .deg
  // 90.833 degrees accounts for refraction and the sun's radius at the horizon
  val hourAngle =
    acos(
        cos(90.833.rad) / (cos(NYC_LATITUDE.rad) * cos(declination)) -
          tan(NYC_LATITUDE.rad) * tan(declination)
      )
      .deg
  val solarNoon = 720 - 4 * NYC_LONGITUDE - equationOfTime
  return solarNoon + 4 * hourAngle
}

private val Double.rad: Double
  get() = this * PI / 180.0

private val Double.deg: Double
  get() = this * 180.0 / PI
