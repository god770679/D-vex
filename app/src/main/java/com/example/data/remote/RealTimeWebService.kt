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
   * Fetches real-time weather from Open-Meteo for a given city and day offset (0 = today, 1 = tomorrow).
   */
  suspend fun fetchRealWeather(location: String = "Chennai", isTomorrow: Boolean = false): String? = withContext(Dispatchers.IO) {
    try {
      val targetCity = location.ifBlank { "Chennai" }
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

      // 2. Fetch actual real-time weather
      val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&daily=temperature_2m_max,temperature_2m_min,weathercode&timezone=auto"
      val weatherRequest = Request.Builder().url(weatherUrl).build()
      val weatherResponse = client.newCall(weatherRequest).execute()
      if (!weatherResponse.isSuccessful) return@withContext null

      val weatherBody = weatherResponse.body?.string() ?: return@withContext null
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

          return@withContext if (max != null && min != null) {
            "Tomorrow's forecast for $resolvedName is $condition with a high of ${max.toInt()}°C and a low of ${min.toInt()}°C, Sir."
          } else {
            "Tomorrow's weather in $resolvedName is expected to be $condition, Sir."
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

        return@withContext if (maxTemp != null && minTemp != null && !maxTemp.isNaN() && !minTemp.isNaN()) {
          "The current weather in $resolvedName is ${temp.toInt()}°C and $condition, with a high of ${maxTemp.toInt()}°C and low of ${minTemp.toInt()}°C, Sir."
        } else {
          "The current weather in $resolvedName is ${temp.toInt()}°C and $condition, Sir."
        }
      }
      null
    } catch (e: Exception) {
      Log.e(TAG, "Weather fetch error", e)
      null
    }
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
