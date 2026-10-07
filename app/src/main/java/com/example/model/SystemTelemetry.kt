package com.example.model

/**
 * REAL HUD TELEMETRY — pure parsing/formatting for the values D-VEX displays as
 * live device data.
 *
 * Telemetry audit contract:
 *  - every value rendered from [SystemVitals] originates in an Android system API
 *    (ACTION_BATTERY_CHANGED broadcast, ActivityManager, StatFs, /proc/stat,
 *    thermal zone, ConnectivityManager, PowerManager thermal status);
 *  - an unreadable or not-yet-delivered source yields null, and the UI renders an
 *    honest "--" — an invented default number is never acceptable;
 *  - nothing here reads a clock, fabricates a percentage, or claims a network
 *    generation/security state the device did not report.
 *
 * Pure Kotlin (no Android imports) so every rule is unit-testable on the JVM.
 */
object SystemTelemetry {

  /** One aggregate-cpu snapshot from /proc/stat, in scheduler jiffies. */
  data class CpuSample(val busy: Long, val total: Long)

  /**
   * Parses the aggregate `cpu ` line of /proc/stat
   * (`cpu user nice system idle iowait irq softirq steal ...`).
   * Returns null when the text carries no usable sample — callers must then keep
   * the previous state or show unavailable, never guess.
   */
  fun parseCpuSample(procStatText: String): CpuSample? {
    val line = procStatText.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val parts = line.trim().split(WHITESPACE)
    if (parts.size < 5) return null
    val fields = ArrayList<Long>(parts.size - 1)
    for (raw in parts.drop(1)) {
      fields.add(raw.toLongOrNull() ?: return null)
    }
    val idle = fields[3] + fields.getOrElse(4) { 0L }
    val total = fields.sum()
    if (total <= 0L || idle > total) return null
    return CpuSample(busy = total - idle, total = total)
  }

  /**
   * Busy percentage between two snapshots. Null on the FIRST sample (there is no
   * baseline yet — a single snapshot cannot yield a rate) and when time moved
   * backwards (counter reset).
   */
  fun cpuUsagePercent(previous: CpuSample?, current: CpuSample): Int? {
    val prev = previous ?: return null
    val totalDelta = current.total - prev.total
    if (totalDelta <= 0L) return null
    val busyDelta = current.busy - prev.busy
    return ((busyDelta * 100) / totalDelta).toInt().coerceIn(0, 100)
  }

  /**
   * Battery percentage from ACTION_BATTERY_CHANGED `EXTRA_LEVEL` / `EXTRA_SCALE`.
   * Null when the extras were absent (-1/-1) — the UI shows "--" rather than a
   * hardcoded value.
   */
  fun batteryPercent(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) return null
    return ((level.toLong() * 100L) / scale.toLong()).toInt().coerceIn(0, 100)
  }

  /**
   * Honest network label from the active network's transport capabilities.
   * Deliberately claims nothing beyond the transport actually reported: no "5G",
   * no "SECURE" — those would be invented telemetry.
   */
  fun formatNetworkLabel(
    connected: Boolean,
    isWifi: Boolean,
    isCellular: Boolean,
    isEthernet: Boolean
  ): String = when {
    !connected -> "OFFLINE"
    isWifi -> "WIFI"
    isCellular -> "CELLULAR"
    isEthernet -> "ETHERNET"
    else -> "OTHER"
  }

  /**
   * PowerManager thermal status code -> HUD label
   * (0 NONE -> "NOMINAL", 1 LIGHT, 2 MODERATE, 3 SEVERE, 4..6 "CRITICAL").
   * Null input (API < 29, service missing, unreadable) stays null so the HUD can
   * render "--" instead of pretending the status is optimal.
   */
  fun formatThermalStatus(status: Int?): String? = when (status) {
    null -> null
    0 -> "NOMINAL"
    1 -> "LIGHT"
    2 -> "MODERATE"
    3 -> "SEVERE"
    4, 5, 6 -> "CRITICAL"
    else -> null
  }

  private val WHITESPACE = Regex("\\s+")
}
