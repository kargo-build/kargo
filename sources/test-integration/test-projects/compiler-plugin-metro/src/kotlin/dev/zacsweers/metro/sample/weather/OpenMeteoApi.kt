// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.weather

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

@JvmInline value class ForecastBaseUrl(val value: String)

@JvmInline value class GeocodingBaseUrl(val value: String)

@ContributesBinding(AppScope::class)
@Inject
class OpenMeteoApi(
  private val httpClient: HttpClient,
  private val forecastBaseUrl: ForecastBaseUrl,
  private val geocodingBaseUrl: GeocodingBaseUrl,
) : WeatherApi {

  override suspend fun getWeatherByCoordinates(
    latitude: Double,
    longitude: Double,
  ): WeatherResponse =
    httpClient
      .get("${forecastBaseUrl.value}/v1/forecast") {
        parameter("latitude", latitude)
        parameter("longitude", longitude)
        parameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
        parameter("hourly", "temperature_2m,weather_code")
        parameter("timezone", "auto")
      }
      .body()

  override suspend fun getLocationBySearch(query: String): List<GeocodingResult> =
    httpClient
      .get("${geocodingBaseUrl.value}/v1/search") {
        parameter("name", query)
        parameter("count", 5)
        parameter("language", "en")
      }
      .body<GeocodingResponse>()
      .results
}
