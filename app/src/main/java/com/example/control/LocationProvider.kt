package com.example.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Real device location for D-VEX live information (weather, etc.).
 *
 * Honesty contract (Bug 4 fix):
 * - Returns [DeviceLocation] only from a genuine device fix (last-known or active).
 * - Never fabricates coordinates or a city. When location cannot be determined,
 *   [getLocationResult] returns the exact failure reason so the caller can report
 *   PERMISSION_REQUIRED or ERROR honestly instead of guessing a default city.
 * - Fast path: last-known GPS/network fix, accepted when fresh (< [STALE_MS]).
 * - Slow path: one-shot active fix from GPS or network provider, bounded by a
 *   [ACTIVE_FIX_TIMEOUT_MS] timeout so voice responses stay snappy.
 */
class LocationProvider(private val context: Context) {

  /** A real device location fix, optionally reverse-geocoded to a city/area name. */
  data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val cityName: String? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null
  )

  /** Exact, honest reason a location could not be provided. Throwable so it flows through kotlin.Result. */
  sealed class LocationError(message: String) : Exception(message) {
    object PermissionDenied : LocationError("permission_denied")
    object ProvidersOff : LocationError("location_providers_disabled")
    object Timeout : LocationError("location_timeout")
    data class Failed(val detail: String) : LocationError("location_failed: $detail")
  }

  private val locationManager =
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  fun areProvidersEnabled(): Boolean {
    val lm = locationManager ?: return false
    return try {
      lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: SecurityException) {
      false
    }
  }

  /**
   * Suspends until a real fix is available or a bounded failure occurs.
   * Resolution order: fresh last-known fix -> active one-shot fix -> reverse geocode.
   */
  suspend fun getLocationResult(): Result<DeviceLocation> {
    if (!hasPermission()) return Result.failure(LocationError.PermissionDenied)
    val lm = locationManager
      ?: return Result.failure(LocationError.Failed("location service unavailable"))
    if (!areProvidersEnabled()) return Result.failure(LocationError.ProvidersOff)

    // 1. Fast path: recent last-known fix avoids a cold GPS wait.
    val lastKnown = getBestLastKnown(lm)
    if (lastKnown != null && isFresh(lastKnown)) {
      Log.i(TAG, "Using fresh last-known fix: ${lastKnown.latitude},${lastKnown.longitude}")
      return Result.success(toDeviceLocation(lastKnown))
    }

    // 2. Active one-shot fix with a hard timeout (no indefinite listening).
    val activeFix = withTimeoutOrNull(ACTIVE_FIX_TIMEOUT_MS) { requestSingleUpdate(lm) }
    val chosen = activeFix ?: lastKnown
      ?: return Result.failure(LocationError.Timeout)

    if (activeFix == null) {
      Log.w(TAG, "Active fix timed out; falling back to stale last-known fix")
    }
    return Result.success(toDeviceLocation(chosen))
  }

  /** Convenience wrapper returning a plain [DeviceLocation] or null on failure. */
  suspend fun getLocation(): DeviceLocation? = getLocationResult().getOrNull()

  private fun getBestLastKnown(lm: LocationManager): Location? {
    val providers = try {
      lm.getProviders(true)
    } catch (e: SecurityException) {
      return null
    }
    var best: Location? = null
    for (provider in providers) {
      val loc = try {
        lm.getLastKnownLocation(provider)
      } catch (e: SecurityException) {
        null
      } ?: continue
      if (best == null || loc.accuracy < best.accuracy) best = loc
    }
    return best
  }

  private fun isFresh(location: Location): Boolean =
    System.currentTimeMillis() - location.time <= STALE_MS

  /**
   * Requests a single update from the first provider that produces a fix
   * (GPS preferred, network fallback). Never blocks the caller's thread.
   */
  private suspend fun requestSingleUpdate(lm: LocationManager): Location? {
    val providers = listOfNotNull(
      runCatching { if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null }.getOrNull(),
      runCatching { if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null }.getOrNull()
    )
    if (providers.isEmpty()) return null

    return suspendCancellableCoroutine { cont ->
      // Explicit 4-method implementation (minSdk 24): LocationListener's extra
      // callbacks only gained default implementations in API 30, so SAM conversion
      // would crash with AbstractMethodError on older devices.
      val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
          if (cont.isActive) cont.resume(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
      }
      var registered = false

      // Classic requestSingleUpdate: available on every supported API level
      // (minSdk 24+) and works identically from API 24 through 36.
      try {
        for (provider in providers) {
          @Suppress("DEPRECATION")
          lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
          registered = true
        }
      } catch (e: Exception) {
        Log.w(TAG, "requestSingleUpdate failed: ${e.message}")
        if (cont.isActive) cont.resume(null)
        return@suspendCancellableCoroutine
      }

      cont.invokeOnCancellation {
        try {
          lm.removeUpdates(listener)
        } catch (e: Exception) {
          Log.w(TAG, "removeUpdates failed: ${e.message}")
        }
      }

      if (!registered && cont.isActive) {
        cont.resume(null)
      }
    }
  }

  /** Reverse-geocodes coordinates to a human-readable city/area name (best effort). */
  private suspend fun toDeviceLocation(location: Location): DeviceLocation =
    withContext(Dispatchers.IO) {
      val cityName = reverseGeocode(location.latitude, location.longitude)
      DeviceLocation(
        latitude = location.latitude,
        longitude = location.longitude,
        cityName = cityName,
        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
      )
    }

  private fun reverseGeocode(lat: Double, lon: Double): String? {
    if (!Geocoder.isPresent()) return null
    return try {
      @Suppress("DEPRECATION")
      val addresses = Geocoder(context, Locale.getDefault())
        .getFromLocation(lat, lon, 1)
      addresses?.firstOrNull()?.let { address ->
        address.locality
          ?: address.subAdminArea
          ?: address.adminArea
          ?: address.featureName
      }
    } catch (e: Exception) {
      Log.w(TAG, "Reverse geocode failed: ${e.message}")
      null
    }
  }

  companion object {
    private const val TAG = "[D-VEX][LOCATION]"

    /** Last-known fixes older than this are considered stale. */
    private const val STALE_MS = 2 * 60 * 1000L

    /** Upper bound for an active one-shot fix so commands never hang. */
    private const val ACTIVE_FIX_TIMEOUT_MS = 6_000L
  }
}
