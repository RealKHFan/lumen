package io.github.realkhfan.lumen.core

/**
 * One photo or video as listed by Android's media database.
 * Pure data with no Android types, so the logic around it can be unit-tested on a plain JVM.
 */
data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    val mime: String,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val orientation: Int,
    /** When Android deletes a trashed item for good (seconds), 0 when not trashed. */
    val dateExpiresSec: Long = 0,
    /** Still being written, for example while the camera finishes processing. */
    val isPending: Boolean = false,
    /** In the bin. */
    val isTrashed: Boolean = false,
) {
    /** Time used for sorting and grouping: when it was taken, falling back to when it was saved. */
    val timeMs: Long
        get() = when {
            dateTakenMs > 0 -> dateTakenMs
            dateModifiedSec > 0 -> dateModifiedSec * 1000
            else -> dateAddedSec * 1000
        }

    /** Width and height as the picture is shown, after rotation. */
    val shownWidth: Int get() = if (orientation % 180 != 0) height else width
    val shownHeight: Int get() = if (orientation % 180 != 0) width else height

    val isCameraFolder: Boolean
        get() = relativePath.startsWith("DCIM/Camera", ignoreCase = true)
}

object MediaOrder {
    /** Newest first; ties broken by id so the order stays the same between reloads. */
    val NEWEST_FIRST: Comparator<MediaItem> = Comparator { a, b ->
        val byTime = b.timeMs.compareTo(a.timeMs)
        if (byTime != 0) byTime else b.id.compareTo(a.id)
    }

    /** Bin order: most recently deleted first, which is the item that expires last. */
    val RECENTLY_DELETED_FIRST: Comparator<MediaItem> = Comparator { a, b ->
        val byExpiry = b.dateExpiresSec.compareTo(a.dateExpiresSec)
        if (byExpiry != 0) byExpiry else b.id.compareTo(a.id)
    }
}
