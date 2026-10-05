package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values that can be asserted without a device: the snapshot carries only what Android reports. */
class DvexContextSnapshotTest {

  @Test
  fun fullyAvailableSnapshotExposesForegroundPackageAndAccessibility() {
    val snapshot = DvexContextSnapshot(
      foregroundPackage = "com.google.android.youtube",
      accessibilityConnected = true
    )

    assertTrue(snapshot.hasPackage)
    assertTrue(snapshot.isFullyAvailable)
    assertFalse(snapshot.isUnavailable)
    assertEquals("com.google.android.youtube", snapshot.foregroundPackage)
    assertEquals(true, snapshot.accessibilityConnected)
  }

  @Test
  fun packageKnownWithoutAccessibilityIsHonest() {
    val snapshot = DvexContextSnapshot(
      foregroundPackage = "com.android.chrome",
      accessibilityConnected = null
    )

    assertTrue(snapshot.hasPackage)
    assertFalse(snapshot.isFullyAvailable)
    assertFalse(snapshot.isUnavailable)
    assertEquals("com.android.chrome", snapshot.foregroundPackage)
    assertNull(snapshot.accessibilityConnected)
  }

  @Test
  fun unavailablePermissionReportsNullNothingInvented() {
    // A real device may report no foreground app (no usage access / no recent activity).
    // The snapshot must say "not known", never invent an app name or task description.
    val snapshot = DvexContextSnapshot(
      foregroundPackage = null,
      accessibilityConnected = null
    )

    assertFalse(snapshot.hasPackage)
    assertFalse(snapshot.isFullyAvailable)
    assertTrue(snapshot.isUnavailable)
    assertNull(snapshot.foregroundPackage)
    assertNull(snapshot.accessibilityConnected)
  }

  @Test
  fun noPackageIsHonestEvenWithAccessibilityConnected() {
    // Accessibility granted does not imply a foreground app was observed.
    val snapshot = DvexContextSnapshot(
      foregroundPackage = null,
      accessibilityConnected = true
    )

    assertFalse(snapshot.hasPackage)
    assertFalse(snapshot.isFullyAvailable)
    assertFalse(snapshot.isUnavailable)
    assertNull(snapshot.foregroundPackage)
    assertEquals(true, snapshot.accessibilityConnected)
  }

  @Test
  fun nullConstructorsAreStableAndCopyable() {
    val original = DvexContextSnapshot(
      foregroundPackage = "com.google.android.youtube",
      accessibilityConnected = true
    )

    val copy = original.copy(foregroundPackage = null)

    assertEquals("com.google.android.youtube", original.foregroundPackage)
    assertEquals(true, original.accessibilityConnected)
    assertEquals(null, copy.foregroundPackage)
  }
}
