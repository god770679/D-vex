package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TELEMETRY AUDIT — real parsing/formatting rules for every value the HUD shows.
 *
 *  - no fabricated defaults: SystemVitals starts fully null (the old 89% / 42% /
 *    68% / 54% / 38°C constants are gone and must never return);
 *  - unparseable sources yield null so the UI can render an honest "--";
 *  - battery comes from ACTION_BATTERY_CHANGED extras, CPU from two real /proc/stat
 *    samples, network from transport capabilities, thermal from PowerManager —
 *    nothing here invents a number or a claim.
 */
class SystemTelemetryTest {

  // --- CPU: /proc/stat parsing + delta usage ---

  @Test
  fun parseCpuSampleReadsTheAggregateCpuLine() {
    val sample = SystemTelemetry.parseCpuSample(
      "cpu  100 0 50 800 25 5 10 0 0 0\ncpu0 50 0 25 400 10 2 5 0 0 0"
    )
    // total = 100+0+50+800+25+5+10+0+0+0 = 990; idle = 800+25 = 825; busy = 165.
    assertEquals(SystemTelemetry.CpuSample(busy = 165L, total = 990L), sample)
  }

  @Test
  fun parseCpuSampleRejectsMissingOrGarbageInput() {
    assertNull(SystemTelemetry.parseCpuSample(""))
    assertNull(SystemTelemetry.parseCpuSample("not a stat file"))
    assertNull(SystemTelemetry.parseCpuSample("cpu 1 2 3"))
    assertNull(SystemTelemetry.parseCpuSample("cpu a b c d e"))
    // Aggregate line must be the "cpu " prefix, not "cpu0"/"cpu1" detail lines.
    assertNull(SystemTelemetry.parseCpuSample("cpu0 10 0 10 80 0 0 0 0 0"))
  }

  @Test
  fun cpuUsageNeedsTwoRealSamplesAndNeverGuesses() {
    val first = SystemTelemetry.CpuSample(busy = 100L, total = 1000L)
    val second = SystemTelemetry.CpuSample(busy = 150L, total = 1100L)

    // First sample: no baseline -> null (UI shows "--"), never an invented 42%.
    assertNull(SystemTelemetry.cpuUsagePercent(null, first))
    // 50 busy-delta over 100 total-delta = 50%.
    assertEquals(50, SystemTelemetry.cpuUsagePercent(first, second))
    // Counter reset / no time passed -> null, not a negative or bogus figure.
    assertNull(SystemTelemetry.cpuUsagePercent(second, first))
    assertNull(SystemTelemetry.cpuUsagePercent(first, first))
  }

  // --- Battery: ACTION_BATTERY_CHANGED extras ---

  @Test
  fun batteryPercentComesFromLevelAndScaleExtras() {
    assertEquals(42, SystemTelemetry.batteryPercent(42, 100))
    assertEquals(0, SystemTelemetry.batteryPercent(0, 100))
    assertEquals(100, SystemTelemetry.batteryPercent(100, 100))
    // Common Android scale of 20 (percent points x level/20 mapping):
    assertEquals(50, SystemTelemetry.batteryPercent(10, 20))
  }

  @Test
  fun batteryPercentIsNullWhenTheBroadcastCarriesNoUsableData() {
    // Absent extras arrive as -1/-1 — must be null (honest "--"), never -1 or 0.
    assertNull(SystemTelemetry.batteryPercent(-1, -1))
    assertNull(SystemTelemetry.batteryPercent(50, 0))
    assertNull(SystemTelemetry.batteryPercent(50, -1))
    assertNull(SystemTelemetry.batteryPercent(-5, 100))
  }

  // --- Network label: honest transport only ---

  @Test
  fun networkLabelReportsOnlyWhatTheDeviceActuallyHas() {
    assertEquals("OFFLINE", SystemTelemetry.formatNetworkLabel(false, false, false, false))
    assertEquals("WIFI", SystemTelemetry.formatNetworkLabel(true, true, false, false))
    assertEquals("CELLULAR", SystemTelemetry.formatNetworkLabel(true, false, true, false))
    assertEquals("ETHERNET", SystemTelemetry.formatNetworkLabel(true, false, false, true))
    assertEquals("OTHER", SystemTelemetry.formatNetworkLabel(true, false, false, false))
    // The old fabricated "5G SECURE" claim can never be produced from real data.
    assertFalse(
      SystemTelemetry.formatNetworkLabel(true, false, true, false).contains("5G")
    )
  }

  // --- Thermal status ---

  @Test
  fun thermalStatusLabelMapsRealStatusCodes() {
    assertEquals("NOMINAL", SystemTelemetry.formatThermalStatus(0))
    assertEquals("LIGHT", SystemTelemetry.formatThermalStatus(1))
    assertEquals("MODERATE", SystemTelemetry.formatThermalStatus(2))
    assertEquals("SEVERE", SystemTelemetry.formatThermalStatus(3))
    assertEquals("CRITICAL", SystemTelemetry.formatThermalStatus(4))
    assertEquals("CRITICAL", SystemTelemetry.formatThermalStatus(6))
    // Unsupported/unreadable -> null, so the HUD shows "--", never "OPTIMAL".
    assertNull(SystemTelemetry.formatThermalStatus(null))
    assertNull(SystemTelemetry.formatThermalStatus(99))
  }

  // --- The model itself: zero fabricated defaults ---

  @Test
  fun systemVitalsStartsEmptyWithNoFabricatedDefaults() {
    val fresh = SystemVitals()
    assertNull(fresh.cpuUsagePercent)
    assertNull(fresh.cpuTempCelsius)
    assertNull(fresh.ramUsagePercent)
    assertNull(fresh.storageUsagePercent)
    assertNull(fresh.batteryPercent)
    assertNull(fresh.isCharging)
    assertNull(fresh.networkLabel)
    assertNull(fresh.thermalStatus)
    assertFalse(fresh.hasAnyData)
  }

  @Test
  fun systemVitalsHasAnyDataTracksRealReadingsOnly() {
    assertFalse(SystemVitals().hasAnyData)
    assertTrue(SystemVitals(batteryPercent = 42).hasAnyData)
    assertTrue(SystemVitals(networkLabel = "WIFI").hasAnyData)
    assertTrue(SystemVitals(cpuTempCelsius = 37).hasAnyData)
    assertTrue(SystemVitals(isCharging = false).hasAnyData)
  }
}
