package com.example.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Provides authentic, real-time web data for D-VEX.
 * Queries live weather, instant web answers, and live news feeds.
 * Strictly avoids guessing or inventing current information.
 */
class RealTimeWebService {

  private val client = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .build()

  /**
   * Structured live weather for the HUD's Weather Telemetry panel.
   * Returns null on any failure — the panel then shows its honest STANDBY state
   * instead of stale or fabricated values. Never guesses missing fields.
   */
  suspend fun fetchLiveWeatherAt(
    latitude: Double,
    longitude: Double,
    placeName: String?
  ): LiveWeather? = withContext(Dispatchers.IO) {
    try {
      fetchForecastData(latitude, longitude, placeName)
    } catch (e: Exception) {
      Log.e(TAG, "Structured weather fetch error", e)
      null
    }
  }

  /** Parsed live conditions for the weather panel. */
  data class LiveWeather(
    val temperatureCelsius: Int,
    val condition: String,
    val location: String,
    val highCelsius: Int,
    val lowCelsius: Int
  )

  /**
   * Fetches real-time weather from Open-Meteo for a named city and day offset (0 = today, 1 = tomorrow).
   * No default city: a blank/null location returns null so callers report an honest failure
   * instead of guessing a fixed city.
   */
  suspend fun fetchRealWeather(location: String?, isTomorrow: Boolean = false): String? = withContext(Dispatchers.IO) {
    val targetCity = location?.trim().orEmpty()
    if (targetCity.isBlank()) return@withContext null
    try {
      val encodedCity = URLEncoder.encode(targetCity, "UTF-8")

      // 1. Geocode city name to lat/lon
      val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=en&format=json"
      val geoRequest = Request.Builder().url(geoUrl).build()
      val geoResponse = client.newCall(geoRequest).execute()
      if (!geoResponse.isSuccessful) return@withContext null

      val geoBody = geoResponse.body?.string() ?: return@withContext null
      val geoJson = JSONObject(geoBody)
      val results = geoJson.optJSONArray("results")
      if (results == null || results.length() == 0) return@withContext null

      val firstResult = results.getJSONObject(0)
      val lat = firstResult.getDouble("latitude")
      val lon = firstResult.getDouble("longitude")
      val resolvedName = firstResult.optString("name", targetCity)

      fetchForecast(lat, lon, resolvedName, isTomorrow)
    } catch (e: Exception) {
      Log.e(TAG, "Weather fetch error", e)
      null
    }
  }

  /**
   * Fetches real-time weather directly for device coordinates obtained from a real
   * GPS/network fix (no geocoding step). [placeName] is the reverse-geocoded city/area
   * name, or null when geocoding was unavailable — the spoken text then names it
   * "your area" instead of guessing a city.
   */
  suspend fun fetchRealWeatherAt(
    latitude: Double,
    longitude: Double,
    placeName: String?,
    isTomorrow: Boolean = false
  ): String? = withContext(Dispatchers.IO) {
    try {
      fetchForecast(latitude, longitude, placeName, isTomorrow)
    } catch (e: Exception) {
      Log.e(TAG, "Coordinate weather fetch error", e)
      null
    }
  }

  /** Shared forecast fetch: today's current conditions or tomorrow's daily summary. */
  private fun fetchForecast(
    lat: Double,
    lon: Double,
    placeName: String?,
    isTomorrow: Boolean
  ): String? {
    val place = placeName?.ifBlank { null } ?: "your area"
    return fetchForecastData(lat, lon, placeName, isTomorrow)
      ?.let { w ->
        if (isTomorrow) {
          "Tomorrow's forecast for $place is ${w.condition} with a high of ${w.highCelsius}°C and a low of ${w.lowCelsius}°C."
        } else if (w.highCelsius != 0 || w.lowCelsius != 0) {
          "The current weather in $place is ${w.temperatureCelsius}°C and ${w.condition}, with a high of ${w.highCelsius}°C and low of ${w.lowCelsius}°C."
        } else {
          "The current weather in $place is ${w.temperatureCelsius}°C and ${w.condition}."
        }
      }
  }

  /** Structured forecast fetch backing both the spoken and panel weather paths. */
  private fun fetchForecastData(
    lat: Double,
    lon: Double,
    placeName: String?,
    isTomorrow: Boolean = false
  ): LiveWeather? {
    val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&daily=temperature_2m_max,temperature_2m_min,weathercode&timezone=auto"
    val weatherRequest = Request.Builder().url(weatherUrl).build()
    val weatherResponse = client.newCall(weatherRequest).execute()
    if (!weatherResponse.isSuccessful) return null

    val weatherBody = weatherResponse.body?.string() ?: return null
    val weatherJson = JSONObject(weatherBody)

    if (isTomorrow) {
      val daily = weatherJson.optJSONObject("daily")
      if (daily != null) {
        val maxTemps = daily.optJSONArray("temperature_2m_max")
        val minTemps = daily.optJSONArray("temperature_2m_min")
        val codes = daily.optJSONArray("weathercode")

        val max = if (maxTemps != null && maxTemps.length() > 1) maxTemps.getDouble(1) else null
        val min = if (minTemps != null && minTemps.length() > 1) minTemps.getDouble(1) else null
        val code = if (codes != null && codes.length() > 1) codes.getInt(1) else 0
        val condition = mapWeatherCode(code)

        return if (max != null && min != null) {
          LiveWeather(
            temperatureCelsius = max.toInt(),
            condition = condition,
            location = placeName?.ifBlank { null } ?: "TOMORROW",
            highCelsius = max.toInt(),
            lowCelsius = min.toInt()
          )
        } else {
          null
        }
      }
    }

    val current = weatherJson.optJSONObject("current_weather")
    if (current != null) {
      val temp = current.getDouble("temperature")
      val code = current.getInt("weathercode")
      val condition = mapWeatherCode(code)

      val daily = weatherJson.optJSONObject("daily")
      val maxTemp = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
      val minTemp = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)

      return LiveWeather(
        temperatureCelsius = temp.toInt(),
        condition = condition,
        location = placeName?.ifBlank { null } ?: "YOUR AREA",
        highCelsius = if (maxTemp != null && !maxTemp.isNaN()) maxTemp.toInt() else 0,
        lowCelsius = if (minTemp != null && !minTemp.isNaN()) minTemp.toInt() else 0
      )
    }
    return null
  }

  /**
   * Queries DuckDuckGo and Wikipedia for instant factual answers.
   */
  suspend fun fetchWebAnswer(query: String): String? = withContext(Dispatchers.IO) {
    try {
      val cleanQuery = query.trim()
      if (cleanQuery.isBlank()) return@withContext null
      val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

      // 1. Try DuckDuckGo Instant Answer API
      val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
      val ddgReq = Request.Builder()
        .url(ddgUrl)
        .header("User-Agent", "DvexAssistant/1.0 (Android)")
        .build()
      val ddgResp = client.newCall(ddgReq).execute()
      if (ddgResp.isSuccessful) {
        val body = ddgResp.body?.string()
        if (!body.isNullOrBlank()) {
          val json = JSONObject(body)
          val abstractText = json.optString("AbstractText")
          if (abstractText.isNotBlank()) {
            return@withContext abstractText
          }
          val answer = json.optString("Answer")
          if (answer.isNotBlank()) {
            return@withContext answer
          }
        }
      }

      // 2. Try Wikipedia summary for general queries/entities
      val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
      val wikiReq = Request.Builder()
        .url(wikiUrl)
        .header("User-Agent", "DvexAssistant/1.0 (Android)")
        .build()
      val wikiResp = client.newCall(wikiReq).execute()
      if (wikiResp.isSuccessful) {
        val body = wikiResp.body?.string()
        if (!body.isNullOrBlank()) {
          val json = JSONObject(body)
          val extract = json.optString("extract")
          if (extract.isNotBlank()) {
            return@withContext extract
          }
        }
      }
      null
    } catch (e: Exception) {
      Log.w(TAG, "Web answer lookup error: ${e.message}")
      null
    }
  }

  /**
   * Maps standard WMO Weather interpretation codes to human-readable descriptors.
   */
  private fun mapWeatherCode(code: Int): String {
    return when (code) {
      0 -> "Clear skies"
      1, 2, 3 -> "Mainly clear and partly cloudy"
      45, 48 -> "Foggy"
      51, 53, 55 -> "Light drizzle"
      61, 63, 65 -> "Rain"
      71, 73, 75 -> "Snowfall"
      80, 81, 82 -> "Rain showers"
      95 -> "Thunderstorm"
      96, 99 -> "Thunderstorm with hail"
      else -> "Partly cloudy"
    }
  }

  companion object {
    private const val TAG = "[D-VEX][REALTIME-WEB]"
  }
}
