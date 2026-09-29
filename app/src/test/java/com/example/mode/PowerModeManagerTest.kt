package com.example.mode

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DUAL MODE SYSTEM — Requirement E: Power Mode persistence.
 *
 * PowerModeManager must store `power_mode_enabled` in SharedPreferences, default
 * OFF, and survive a simulated restart. A "restart" here means constructing a fresh
 * manager over the same application context AFTER clearing the in-memory singleton,
 * exactly what happens when the process is recreated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PowerModeManagerTest {

  @Before
  fun setUp() {
    PowerModeManagerTestAccess.resetSingleton()
    // Fresh preference store for each test (simulates a clean install or restart
    // with no prior state, where the default must be OFF).
    RuntimeEnvironment.getApplication()
      .getSharedPreferences(PowerModeManagerTestAccess.PREFS_NAME_FOR_TEST, 0)
      .edit().clear().commit()
  }

  @After
  fun tearDown() {
    PowerModeManagerTestAccess.resetSingleton()
  }

  @Test
  fun requirementE_defaultIsOffOnFreshInstall() {
    val manager = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertFalse("Power Mode must default to OFF (Standard Mode)", manager.isPowerModeEnabled())
    assertFalse("StateFlow must also start OFF", manager.powerModeEnabled.value)
  }

  @Test
  fun requirementE_writeOnSurvivesRestart() {
    val first = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    first.setEnabled(true)
    assertTrue(first.isPowerModeEnabled())

    // Simulate restart: drop the singleton, rebuild over the same (persisted) prefs.
    PowerModeManagerTestAccess.resetSingleton()
    val recreated = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertTrue("Power Mode ON must persist across a restart", recreated.isPowerModeEnabled())
    assertTrue(recreated.powerModeEnabled.value)
  }

  @Test
  fun requirementE_writeOffSurvivesRestart() {
    // Start from ON (as if a previous run had enabled it)...
    RuntimeEnvironment.getApplication()
      .getSharedPreferences(PowerModeManagerTestAccess.PREFS_NAME_FOR_TEST, 0)
      .edit().putBoolean(PowerModeManager.KEY_POWER_MODE_ENABLED, true).commit()

    val first = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertTrue(first.isPowerModeEnabled())

    first.setEnabled(false)

    // ...then simulate restart and require OFF.
    PowerModeManagerTestAccess.resetSingleton()
    val recreated = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertFalse("Power Mode OFF must persist across a restart", recreated.isPowerModeEnabled())
  }

  @Test
  fun requirementE_toggleFlipsAndPersists() {
    val manager = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    manager.toggle()
    assertTrue(manager.isPowerModeEnabled())

    PowerModeManagerTestAccess.resetSingleton()
    assertTrue(
      "toggle() result must be persisted",
      PowerModeManager.getInstance(RuntimeEnvironment.getApplication()).isPowerModeEnabled()
    )
  }

  @Test
  fun requirementE_setEnabledIsThreadSafe() {
    val manager = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    val pool = Executors.newFixedThreadPool(4)
    val ready = CountDownLatch(1)
    val done = CountDownLatch(4)
    repeat(4) { i ->
      pool.execute {
        ready.await()
        repeat(50) { manager.setEnabled(i % 2 == 0) }
        done.countDown()
      }
    }
    ready.countDown()
    assertTrue("concurrent toggling must not deadlock", done.await(10, TimeUnit.SECONDS))
    pool.shutdown()

    // Final value must be internally consistent: flow matches the persisted flag.
    PowerModeManagerTestAccess.resetSingleton()
    val recreated = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertEquals(
      "persisted value and flow must agree after concurrent writes",
      recreated.isPowerModeEnabled(),
      recreated.powerModeEnabled.value
    )
  }

  @Test
  fun singletonReturnsSameInstance() {
    val a = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    val b = PowerModeManager.getInstance(RuntimeEnvironment.getApplication())
    assertTrue(a === b)
  }
}

/** Test-only hook exposing the prefs name used by the manager for clean resets. */
object PowerModeManagerTestAccess {
  val PREFS_NAME_FOR_TEST: String = run {
    // Mirror the private constant without making it public API.
    val field = PowerModeManager.Companion::class.java.declaredFields.firstOrNull {
      it.name == "PREFS_NAME"
    }
    field?.isAccessible = true
    field?.get(null) as? String ?: "dvex_power_mode_prefs"
  }

  fun resetSingleton() {
    val field = PowerModeManager::class.java.declaredFields.firstOrNull { it.name == "instance" }
    field?.isAccessible = true
    field?.set(null, null)
  }
}
