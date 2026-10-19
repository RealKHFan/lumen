package io.github.realkhfan.lumen.core

import io.github.realkhfan.lumen.core.TestData.item
import io.github.realkhfan.lumen.core.TestData.ms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BinTest {
    private val now = 1_800_000_000_000L

    @Test
    fun countsWholeDaysRoundedUp() {
        assertEquals(30, Bin.daysLeft(now / 1000 + 30 * 86_400, now))
        assertEquals(30, Bin.daysLeft(now / 1000 + 29 * 86_400 + 1, now))
        assertEquals(1, Bin.daysLeft(now / 1000 + 60, now))
    }

    @Test
    fun expiredOrUnknownIsZero() {
        assertEquals(0, Bin.daysLeft(now / 1000 - 10, now))
        assertEquals(0, Bin.daysLeft(0, now))
    }

    @Test
    fun binOrderIsMostRecentlyDeletedFirst() {
        val a = item(1, 0, expiresSec = 100)
        val b = item(2, 0, expiresSec = 300)
        val c = item(3, 0, expiresSec = 200)
        assertEquals(listOf(2L, 3L, 1L), listOf(a, b, c).sortedWith(MediaOrder.RECENTLY_DELETED_FIRST).map { it.id })
    }
}

class SelectionTest {
    private val items = (1L..10L).map { item(it, ms(2026, 10, 1) - it * 1000) }

    @Test
    fun dragAddsRangeInBothDirections() {
        val down = DragSelection(emptySet(), 2, adding = true)
        assertEquals(setOf(3L, 4L, 5L, 6L), down.at(5, items))
        val up = DragSelection(emptySet(), 5, adding = true)
        assertEquals(setOf(4L, 5L, 6L), up.at(3, items))
    }

    @Test
    fun dragBackShrinksRangeAndKeepsEarlierSelection() {
        val drag = DragSelection(setOf(10L), 0, adding = true)
        assertEquals(setOf(10L, 1L, 2L, 3L, 4L), drag.at(3, items))
        assertEquals(setOf(10L, 1L), drag.at(0, items))
    }

    @Test
    fun dragFromSelectedItemRemoves() {
        val drag = DragSelection((1L..10L).toSet(), 4, adding = false)
        assertEquals((1L..10L).toSet() - setOf(5L, 6L, 7L), drag.at(6, items))
    }

    @Test
    fun dragClampsOutOfRangeIndexes() {
        assertEquals(setOf(9L, 10L), DragSelection(emptySet(), 8, adding = true).at(99, items))
        assertEquals(emptySet<Long>(), DragSelection(emptySet(), 0, adding = true).at(3, emptyList()))
    }

    @Test
    fun headerSelectsWholeDayThenClears() {
        val once = Selection.toggleRange(emptySet(), items, 2, 3)
        assertEquals(setOf(3L, 4L, 5L), once)
        assertTrue(Selection.isRangeSelected(once, items, 2, 3))
        val partly = setOf(3L)
        assertEquals(setOf(3L, 4L, 5L), Selection.toggleRange(partly, items, 2, 3))
        assertEquals(emptySet<Long>(), Selection.toggleRange(once, items, 2, 3))
        assertFalse(Selection.isRangeSelected(partly, items, 2, 3))
    }
}

class FormatTest {
    private val uk = Locale.UK
    private val de = Locale.GERMANY

    @Test
    fun sizes() {
        assertEquals("999 B", Format.size(999, uk))
        assertEquals("1.0 kB", Format.size(1000, uk))
        assertEquals("850 kB", Format.size(850_000, uk))
        assertEquals("4.2 MB", Format.size(4_200_000, uk))
        assertEquals("4,2 MB", Format.size(4_200_000, de))
        assertEquals("12 MB", Format.size(12_340_000, uk))
        assertEquals("1.0 GB", Format.size(999_999_999, uk))
        assertEquals("3.5 GB", Format.size(3_500_000_000, uk))
    }

    @Test
    fun durations() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("0:07", Format.duration(7_200))
        assertEquals("1:05", Format.duration(65_000))
        assertEquals("1:02:03", Format.duration(3_723_000))
    }

    @Test
    fun cameraSettings() {
        assertEquals("1/120 s", Format.exposure(1 / 120.0, uk))
        assertEquals("1/8 s", Format.exposure(0.125, uk))
        assertEquals("2 s", Format.exposure(2.0, uk))
        assertEquals("1.5 s", Format.exposure(1.5, uk))
        assertNull(Format.exposure(0.0, uk))
        assertEquals("f/1.68", Format.aperture(1.68, uk))
        assertEquals("f/2", Format.aperture(2.0, uk))
        assertEquals("f/2,2", Format.aperture(2.2, de))
        assertEquals("6.9 mm", Format.focalLength(6.9, uk))
        assertEquals("12.5 MP", Format.megapixels(4080, 3072, uk))
        assertEquals("50.1 MP", Format.megapixels(8160, 6144, uk))
        assertNull(Format.megapixels(10, 10, uk))
        assertEquals("4080 × 3072", Format.resolution(4080, 3072))
        assertNull(Format.resolution(0, 3072))
    }

    @Test
    fun rotatedDimensions() {
        val portrait = item(1, 0).copy(width = 4080, height = 3072, orientation = 90)
        assertEquals(3072 to 4080, portrait.shownWidth to portrait.shownHeight)
        val upright = portrait.copy(orientation = 180)
        assertEquals(4080 to 3072, upright.shownWidth to upright.shownHeight)
    }
}

class ReviewTest {
    @Test
    fun recognisesEveryCameraAction() {
        for (a in listOf(
            "com.android.camera.action.REVIEW",
            "android.provider.action.REVIEW",
            "android.provider.action.REVIEW_SECURE",
            "com.google.android.apps.photos.mars.api.ACTION_REVIEW",
            "com.google.android.apps.photos.mars.api.ACTION_REVIEW_SECURE",
        )) assertTrue(a, Review.isReview(a))
        assertFalse(Review.isReview("android.intent.action.VIEW"))
        assertFalse(Review.isReview(null))
    }

    @Test
    fun secureWhenAskedOrWhenLocked() {
        assertTrue(Review.isSecure(Review.ACTION_REVIEW_SECURE, phoneLocked = false))
        assertTrue(Review.isSecure(Review.ACTION_PHOTOS_REVIEW_SECURE, phoneLocked = false))
        assertTrue(Review.isSecure(Review.ACTION_CAMERA_REVIEW, phoneLocked = true))
        assertFalse(Review.isSecure(Review.ACTION_CAMERA_REVIEW, phoneLocked = false))
        // Opening a picture from another app never counts as a locked camera session.
        assertFalse(Review.isSecure("android.intent.action.VIEW", phoneLocked = true))
        assertFalse(Review.isSecure(null, phoneLocked = true))
    }

    @Test
    fun secureSessionKeepsNewestFirstWithoutDuplicates() {
        val s = SecureSession(timeoutMs = 1000)
        assertEquals(listOf("a"), s.add(listOf("a"), 0))
        assertEquals(listOf("b", "a"), s.add(listOf("b"), 100))
        assertEquals(listOf("a", "b"), s.add(listOf("a"), 200))
        assertEquals(listOf("a", "b"), s.current(300))
    }

    @Test
    fun secureSessionForgetsAfterTimeoutOrUnlock() {
        val s = SecureSession(timeoutMs = 1000)
        s.add(listOf("a"), 0)
        assertEquals(listOf("b"), s.add(listOf("b"), 5000))
        assertTrue(s.current(10_000).isEmpty())
        s.add(listOf("c"), 20_000)
        s.clear()
        assertTrue(s.current(20_001).isEmpty())
    }

    @Test
    fun secureSessionIsCapped() {
        val s = SecureSession(maxItems = 3)
        (1..5).forEach { s.add(listOf("u$it"), it.toLong()) }
        assertEquals(listOf("u5", "u4", "u3"), s.current(6))
    }
}
