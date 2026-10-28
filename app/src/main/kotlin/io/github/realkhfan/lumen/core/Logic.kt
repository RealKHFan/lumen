package io.github.realkhfan.lumen.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Android's own bin: items stay for a while (30 days on current Android), then go for good. */
object Bin {
    const val DAY_MS = 86_400_000L

    /** Whole days until the item is deleted for good, rounded up. 0 when expired or unknown. */
    fun daysLeft(expiresSec: Long, nowMs: Long): Int {
        if (expiresSec <= 0) return 0
        val left = expiresSec * 1000 - nowMs
        if (left <= 0) return 0
        return ((left + DAY_MS - 1) / DAY_MS).toInt()
    }
}

/**
 * Long-press, then drag: everything between the first and the current thumbnail is added,
 * or removed when the drag started on an already selected one.
 */
class DragSelection(
    private val base: Set<Long>,
    private val anchor: Int,
    private val adding: Boolean,
) {
    fun at(current: Int, items: List<MediaItem>): Set<Long> {
        if (items.isEmpty()) return base
        val lo = min(anchor, current).coerceIn(0, items.lastIndex)
        val hi = max(anchor, current).coerceIn(0, items.lastIndex)
        val out = LinkedHashSet(base)
        for (i in lo..hi) {
            if (adding) out.add(items[i].id) else out.remove(items[i].id)
        }
        return out
    }
}

object Selection {
    /** Tapping a day header's circle: select the whole day, or clear it if it was all selected. */
    fun toggleRange(selected: Set<Long>, items: List<MediaItem>, first: Int, count: Int): Set<Long> {
        if (count <= 0) return selected
        val ids = (first until min(first + count, items.size)).map { items[it].id }
        val all = ids.all { it in selected }
        return if (all) selected - ids.toSet() else selected + ids
    }

    fun isRangeSelected(selected: Set<Long>, items: List<MediaItem>, first: Int, count: Int): Boolean {
        if (count <= 0 || selected.isEmpty()) return false
        for (i in first until min(first + count, items.size)) if (items[i].id !in selected) return false
        return true
    }
}

/** Text formatting shared by the grid, the viewer and the info panel. */
object Format {
    fun size(bytes: Long, locale: Locale): String {
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("kB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 999.95 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val text = if (value < 10) String.format(locale, "%.1f", value) else String.format(locale, "%.0f", value)
        return text + " " + units[unit]
    }

    fun duration(ms: Long): String {
        val total = max(0L, (ms + 500) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Shutter speed: "1/120 s" for fractions, "2 s" or "1.5 s" for long exposures. */
    fun exposure(seconds: Double, locale: Locale): String? {
        if (seconds <= 0 || seconds.isNaN()) return null
        if (seconds >= 1 || abs(seconds - 1) < 0.001) return trim(seconds, 1, locale) + " s"
        val denominator = (1 / seconds).roundToLong()
        return "1/$denominator s"
    }

    fun aperture(fNumber: Double, locale: Locale): String? =
        if (fNumber > 0) "f/" + trim(fNumber, 2, locale) else null

    fun focalLength(mm: Double, locale: Locale): String? =
        if (mm > 0) trim(mm, 1, locale) + " mm" else null

    fun megapixels(width: Int, height: Int, locale: Locale): String? {
        val mp = width.toLong() * height / 1_000_000.0
        return if (mp >= 0.1) trim(mp, 1, locale) + " MP" else null
    }

    fun resolution(width: Int, height: Int): String? =
        if (width > 0 && height > 0) "$width × $height" else null

    /** Rounds to [decimals] and drops trailing zeros: 1.70 -> "1.7", 2.0 -> "2". */
    fun trim(value: Double, decimals: Int, locale: Locale): String {
        var text = String.format(locale, "%." + decimals + "f", value)
        val sep = String.format(locale, "%.1f", 0.5)[1]
        if (text.indexOf(sep) >= 0) {
            text = text.trimEnd('0').trimEnd(sep)
        }
        return text
    }
}

/**
 * The camera's "show me what I just took" requests. Pixel Camera only sends these to an app
 * installed as com.google.android.apps.photos; other camera apps may use the same actions.
 */
object Review {
    const val ACTION_CAMERA_REVIEW = "com.android.camera.action.REVIEW"
    const val ACTION_REVIEW = "android.provider.action.REVIEW"
    const val ACTION_REVIEW_SECURE = "android.provider.action.REVIEW_SECURE"
    const val ACTION_PHOTOS_REVIEW = "com.google.android.apps.photos.mars.api.ACTION_REVIEW"
    const val ACTION_PHOTOS_REVIEW_SECURE = "com.google.android.apps.photos.mars.api.ACTION_REVIEW_SECURE"

    val ALL = setOf(ACTION_CAMERA_REVIEW, ACTION_REVIEW, ACTION_REVIEW_SECURE, ACTION_PHOTOS_REVIEW, ACTION_PHOTOS_REVIEW_SECURE)
    private val SECURE = setOf(ACTION_REVIEW_SECURE, ACTION_PHOTOS_REVIEW_SECURE)

    fun isReview(action: String?): Boolean = action != null && action in ALL

    /**
     * Secure review shows only what the camera handed over, on top of the lock screen.
     * Used for the secure actions, and for any review request while the phone is locked.
     */
    fun isSecure(action: String?, phoneLocked: Boolean): Boolean =
        action != null && (action in SECURE || (action in ALL && phoneLocked))

    /** How long after a photo is saved it may still be shown on the lock screen. */
    const val LOCK_SCREEN_WINDOW_MS = 30 * 60_000L

    /** Lock screen: only photos saved in the last 30 minutes, never older ones from the library. */
    fun freshForLockScreen(dateAddedSec: Long, nowMs: Long): Boolean =
        dateAddedSec > 0 && nowMs - dateAddedSec * 1000 <= LOCK_SCREEN_WINDOW_MS
}

/**
 * What the camera has shown through Lumen while the phone stayed locked, newest first.
 * Lets you swipe between the shots of one locked session without exposing anything else.
 * Cleared when the phone is unlocked, or when nothing happened for [timeoutMs].
 */
class SecureSession(private val timeoutMs: Long = Review.LOCK_SCREEN_WINDOW_MS, private val maxItems: Int = 200) {
    private var uris: List<String> = emptyList()
    private var lastMs: Long? = null

    private fun expireIfQuiet(nowMs: Long) {
        val last = lastMs
        if (last != null && nowMs - last > timeoutMs) uris = emptyList()
    }

    @Synchronized
    fun add(newest: List<String>, nowMs: Long): List<String> {
        expireIfQuiet(nowMs)
        lastMs = nowMs
        val merged = LinkedHashSet<String>()
        merged.addAll(newest)
        merged.addAll(uris)
        uris = merged.take(maxItems)
        return uris
    }

    @Synchronized
    fun current(nowMs: Long): List<String> {
        expireIfQuiet(nowMs)
        return uris
    }

    @Synchronized
    fun clear() {
        uris = emptyList()
        lastMs = null
    }
}
