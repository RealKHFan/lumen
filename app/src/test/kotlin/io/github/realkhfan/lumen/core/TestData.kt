package io.github.realkhfan.lumen.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

object TestData {
    val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    fun ms(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli()

    fun item(
        id: Long,
        timeMs: Long,
        bucket: Long = 1,
        bucketName: String = "Camera",
        path: String = "DCIM/Camera/",
        video: Boolean = false,
        expiresSec: Long = 0,
    ) = MediaItem(
        id = id, isVideo = video, mime = if (video) "video/mp4" else "image/jpeg",
        dateTakenMs = timeMs, dateAddedSec = timeMs / 1000, dateModifiedSec = timeMs / 1000,
        bucketId = bucket, bucketName = bucketName, relativePath = path, displayName = "IMG_$id.jpg",
        sizeBytes = 1000, width = 4080, height = 3072, durationMs = 0, orientation = 0,
        dateExpiresSec = expiresSec,
    )

    fun labels() = DateLabels(
        today = "Today", yesterday = "Yesterday", locale = Locale.UK, zone = ZONE,
        dayPattern = "EEE d MMM", dayYearPattern = "EEE d MMM yyyy",
        monthPattern = "MMMM", monthYearPattern = "MMMM yyyy", sectionPattern = "MMM yyyy",
    )
}
