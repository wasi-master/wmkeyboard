package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherClientTest {

    @Test
    fun `parses open-meteo response`() {
        val body = """
            {
              "latitude": 23.75,
              "longitude": 90.375,
              "current": {
                "temperature_2m": 31.4,
                "relative_humidity_2m": 78,
                "apparent_temperature": 38.2,
                "is_day": 1,
                "weather_code": 80,
                "wind_speed_10m": 11.5,
                "wind_direction_10m": 135,
                "surface_pressure": 1004.2,
                "cloud_cover": 75,
                "precipitation": 0.4
              },
              "daily": {
                "temperature_2m_max": [33.1],
                "temperature_2m_min": [27.0],
                "uv_index_max": [8.5],
                "precipitation_probability_max": [65],
                "sunrise": ["2026-07-19T05:16"],
                "sunset": ["2026-07-19T18:49"]
              }
            }
        """.trimIndent()
        val info = WeatherClient.parse(body, now = 42L)
        assertEquals(31.4, info.temperatureC, 1e-9)
        assertEquals(38.2, info.feelsLikeC, 1e-9)
        assertEquals(78, info.humidityPercent)
        assertEquals(11.5, info.windKmh, 1e-9)
        assertEquals(135, info.windDirectionDeg)
        assertEquals(1004.2, info.pressureHpa, 1e-9)
        assertEquals(75, info.cloudCoverPercent)
        assertEquals(0.4, info.precipitationMm, 1e-9)
        assertEquals(80, info.weatherCode)
        assertEquals(true, info.isDay)
        assertEquals(33.1, info.highC, 1e-9)
        assertEquals(27.0, info.lowC, 1e-9)
        assertEquals(8.5, info.uvIndexMax, 1e-9)
        assertEquals(65, info.precipProbabilityPercent)
        assertEquals("05:16", info.sunrise)
        assertEquals("18:49", info.sunset)
        assertEquals(42L, info.fetchedAtMillis)
    }

    @Test
    fun `parses response without the optional fields`() {
        val body = """
            {
              "current": {
                "temperature_2m": 10.0,
                "relative_humidity_2m": 50,
                "apparent_temperature": 9.0,
                "is_day": 0,
                "weather_code": 3,
                "wind_speed_10m": 5.0
              },
              "daily": {
                "temperature_2m_max": [12.0],
                "temperature_2m_min": [4.0],
                "uv_index_max": [null],
                "precipitation_probability_max": [null]
              }
            }
        """.trimIndent()
        val info = WeatherClient.parse(body, now = 1L)
        assertEquals(0, info.windDirectionDeg)
        assertEquals(-1.0, info.pressureHpa, 1e-9)
        assertEquals(-1, info.cloudCoverPercent)
        assertEquals(-1.0, info.uvIndexMax, 1e-9)
        assertEquals(-1, info.precipProbabilityPercent)
        assertEquals("", info.sunrise)
        assertEquals("", info.sunset)
    }

    @Test
    fun `parses tomorrow from a two-day response`() {
        val body = """
            {
              "current": {
                "temperature_2m": 31.4,
                "relative_humidity_2m": 78,
                "apparent_temperature": 38.2,
                "is_day": 1,
                "weather_code": 80,
                "wind_speed_10m": 11.5
              },
              "daily": {
                "temperature_2m_max": [33.1, 29.5],
                "temperature_2m_min": [27.0, 24.2],
                "uv_index_max": [8.5, 7.0],
                "precipitation_probability_max": [65, 80],
                "weather_code": [80, 95],
                "sunrise": ["2026-07-19T05:16"],
                "sunset": ["2026-07-19T18:49"]
              }
            }
        """.trimIndent()
        val info = WeatherClient.parse(body, now = 1L)
        assertEquals(29.5, info.tomorrowHighC!!, 1e-9)
        assertEquals(24.2, info.tomorrowLowC!!, 1e-9)
        assertEquals(95, info.tomorrowCode)
        assertEquals(80, info.tomorrowPrecipProbabilityPercent)
    }

    @Test
    fun `one-day response leaves tomorrow null`() {
        val body = """
            {
              "current": {
                "temperature_2m": 10.0,
                "relative_humidity_2m": 50,
                "apparent_temperature": 9.0,
                "is_day": 0,
                "weather_code": 3,
                "wind_speed_10m": 5.0
              },
              "daily": {
                "temperature_2m_max": [12.0],
                "temperature_2m_min": [4.0]
              }
            }
        """.trimIndent()
        val info = WeatherClient.parse(body, now = 1L)
        assertEquals(null, info.tomorrowHighC)
        assertEquals(null, info.tomorrowCode)
    }

    @Test
    fun `wind cardinal`() {
        assertEquals("N", WeatherClient.windCardinal(0))
        assertEquals("SE", WeatherClient.windCardinal(135))
        assertEquals("N", WeatherClient.windCardinal(350))
        assertEquals("W", WeatherClient.windCardinal(280))
    }

    @Test
    fun `celsius fahrenheit display`() {
        assertEquals(31, WeatherClient.toDisplay(31.4, fahrenheit = false))
        assertEquals(89, WeatherClient.toDisplay(31.4, fahrenheit = true))
        assertEquals(32, WeatherClient.toDisplay(0.0, fahrenheit = true))
    }

    @Test
    fun `parses an hourly forecast and finds the hour a moment falls in`() {
        val body = """
            {
              "hourly": {
                "time": [1763000000, 1763003600, 1763007200],
                "temperature_2m": [21.5, null, 19.0],
                "precipitation_probability": [10, 20, null],
                "weather_code": [2, 3, 61],
                "is_day": [1, 1, 0]
              }
            }
        """.trimIndent()
        val forecast = WeatherClient.parseHourly(body, 23.7f, 90.4f, now = 7L)
        // The hour with no temperature is left out.
        assertEquals(2, forecast.hours.size)
        assertEquals(1_763_000_000_000L, forecast.hours[0].timeMillis)
        assertEquals(21.5, forecast.hours[0].temperatureC, 1e-9)
        assertEquals(10, forecast.hours[0].precipProbabilityPercent)
        assertEquals(-1, forecast.hours[1].precipProbabilityPercent)
        assertEquals(false, forecast.hours[1].isDay)
        assertEquals(7L, forecast.fetchedAtMillis)
        assertEquals(forecast.hours[0], forecast.hourAt(1_763_000_000_000L + 1_800_000L))
        assertEquals(forecast.hours[1], forecast.hourAt(1_763_007_200_000L))
        assertEquals(null, forecast.hourAt(1_763_003_600_000L))
        assertEquals(null, forecast.hourAt(1_762_999_999_999L))
        assertEquals(null, forecast.hourAt(1_763_010_800_000L))
    }
}
