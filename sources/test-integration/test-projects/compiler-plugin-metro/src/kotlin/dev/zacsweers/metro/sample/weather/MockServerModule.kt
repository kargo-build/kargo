/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.zacsweers.metro.sample.weather

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

@ContributesTo(AppScope::class)
interface MockServerModule {
  companion object {
    @Provides
    @SingleIn(AppScope::class)
    fun provideMockWebServer(): MockWebServer {
      val server = MockWebServer()
      server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val target = request.target
            return when {
              target.startsWith("/v1/search") -> MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = GEOCODING_RESPONSE,
              )
              target.startsWith("/v1/forecast") -> MockResponse(
                code = 200,
                headers = headersOf("Content-Type", "application/json"),
                body = FORECAST_RESPONSE,
              )
              else -> MockResponse(code = 404)
            }
          }
        }
      server.start()
      return server
    }

    @Provides
    fun provideForecastBaseUrl(server: MockWebServer): ForecastBaseUrl =
      ForecastBaseUrl(server.url("/").toString().trimEnd('/'))

    @Provides
    fun provideGeocodingBaseUrl(server: MockWebServer): GeocodingBaseUrl =
      GeocodingBaseUrl(server.url("/").toString().trimEnd('/'))
  }
}

private val GEOCODING_RESPONSE =
  """
  {
    "results": [
      {
        "name": "New York",
        "latitude": 40.7143,
        "longitude": -74.006,
        "country": "United States",
        "admin1": "New York"
      }
    ]
  }
  """
    .trimIndent()

private val FORECAST_RESPONSE =
  """
  {
    "current": {
      "temperature_2m": 22.5,
      "relative_humidity_2m": 65.0,
      "weather_code": 0,
      "wind_speed_10m": 12.0
    },
    "hourly": {
      "time": [
        "2025-01-01T00:00", "2025-01-01T01:00", "2025-01-01T02:00", "2025-01-01T03:00",
        "2025-01-01T04:00", "2025-01-01T05:00", "2025-01-01T06:00", "2025-01-01T07:00",
        "2025-01-01T08:00", "2025-01-01T09:00", "2025-01-01T10:00", "2025-01-01T11:00",
        "2025-01-01T12:00", "2025-01-01T13:00", "2025-01-01T14:00", "2025-01-01T15:00",
        "2025-01-01T16:00", "2025-01-01T17:00", "2025-01-01T18:00", "2025-01-01T19:00",
        "2025-01-01T20:00", "2025-01-01T21:00", "2025-01-01T22:00", "2025-01-01T23:00"
      ],
      "temperature_2m": [
        20.0, 20.5, 21.0, 21.5, 22.0, 22.5, 23.0, 23.5,
        24.0, 24.5, 25.0, 25.5, 26.0, 25.5, 25.0, 24.5,
        24.0, 23.5, 23.0, 22.5, 22.0, 21.5, 21.0, 20.5
      ],
      "weather_code": [
        0, 0, 0, 0, 0, 0, 1, 1,
        1, 2, 2, 2, 3, 3, 2, 2,
        1, 1, 0, 0, 0, 0, 0, 0
      ]
    }
  }
  """
    .trimIndent()
