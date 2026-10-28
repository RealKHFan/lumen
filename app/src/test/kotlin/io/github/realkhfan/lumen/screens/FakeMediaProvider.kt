package io.github.realkhfan.lumen.screens

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import java.io.File

/**
 * Stands in for Android's media database in screenshot tests: a small, fixed library of
 * photos and videos in a few folders, plus a bin, each drawn as a simple landscape.
 */
class FakeMediaProvider : ContentProvider() {
    class Item(
        val id: Long, val video: Boolean, val takenMs: Long, val bucket: Long, val bucketName: String,
        val path: String, val trashedExpiresSec: Long = 0,
    )

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, signal: CancellationSignal?): Cursor {
        val trashed = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) ?: MediaStore.MATCH_EXCLUDE
        val idArg = queryArgs?.getStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
            ?.takeIf { queryArgs.getString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION)?.contains("_id") == true }
            ?.firstOrNull()?.toLongOrNull()
        val rows = items.filter { item ->
            when {
                idArg != null -> item.id == idArg
                trashed == MediaStore.MATCH_ONLY -> item.trashedExpiresSec > 0
                else -> item.trashedExpiresSec == 0L
            }
        }
        val cols = projection ?: arrayOf(MediaColumns._ID)
        val cursor = MatrixCursor(cols)
        for (item in rows) cursor.addRow(cols.map { column(item, it) })
        return cursor
    }

    private fun column(item: Item, name: String): Any? = when (name) {
        MediaColumns._ID -> item.id
        FileColumns.MEDIA_TYPE -> if (item.video) FileColumns.MEDIA_TYPE_VIDEO else FileColumns.MEDIA_TYPE_IMAGE
        MediaColumns.MIME_TYPE -> if (item.video) "video/mp4" else "image/jpeg"
        MediaColumns.DATE_TAKEN -> item.takenMs
        MediaColumns.DATE_ADDED, MediaColumns.DATE_MODIFIED -> item.takenMs / 1000
        MediaColumns.BUCKET_ID -> item.bucket
        MediaColumns.BUCKET_DISPLAY_NAME -> item.bucketName
        MediaColumns.RELATIVE_PATH -> item.path
        MediaColumns.DISPLAY_NAME -> (if (item.video) "VID_" else "PXL_") + item.id + (if (item.video) ".mp4" else ".jpg")
        MediaColumns.SIZE -> 3_400_000L + item.id * 1000
        MediaColumns.WIDTH -> 4080
        MediaColumns.HEIGHT -> 3072
        MediaColumns.DURATION -> if (item.video) 19_000L + item.id * 1000 else 0L
        MediaColumns.ORIENTATION -> 0
        MediaColumns.DATE_EXPIRES -> item.trashedExpiresSec
        MediaColumns.IS_PENDING -> 0
        MediaColumns.IS_TRASHED -> if (item.trashedExpiresSec > 0) 1 else 0
        else -> null
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, "r"), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    // Synchronized: thumbnail threads ask for the same picture at once, and one must not read a half-written file.
    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.toLongOrNull() ?: 0L
        val file = File(context!!.cacheDir, "fake_$id.png")
        if (!file.exists()) file.outputStream().use { picture(id).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** A little landscape: sky, a hill and a sun, in colours that vary per photo. */
    private fun picture(id: Long): Bitmap {
        val skies = intArrayOf(0xFF8FB4C9.toInt(), 0xFFD8B48C.toInt(), 0xFF9DB4A0.toInt(), 0xFF7A6F8E.toInt(), 0xFFA8B9C9.toInt(), 0xFFE0B58C.toInt())
        val lands = intArrayOf(0xFF4F6D7A.toInt(), 0xFF8C6A4F.toInt(), 0xFF5E7F66.toInt(), 0xFF3F5A6B.toInt(), 0xFF6E8B6B.toInt(), 0xFFB9886F.toInt())
        val i = (id % skies.size).toInt()
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(skies[i])
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF2D8A0.toInt()
        c.drawCircle(120f + (id % 5) * 40f, 90f, 34f, p)
        p.color = lands[(i + 1) % lands.size]
        c.drawOval(-120f, 170f + (id % 3) * 15f, 520f, 520f, p)
        p.color = Color.argb(60, 255, 255, 255)
        c.drawRect(0f, 0f, 400f, 40f, p)
        return bmp
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private val now = System.currentTimeMillis()

        val items: List<Item> = buildList {
            var id = 1L
            // Camera: several shots a day over the last two weeks, some videos.
            for (day in 0 until 14) for (shot in 0 until (3 + day % 4)) {
                add(Item(id, video = id % 7 == 3L, takenMs = now - day * DAY - shot * HOUR - 600_000, bucket = 1,
                    bucketName = "Camera", path = "DCIM/Camera/"))
                id++
            }
            for (n in 0 until 9) add(Item(id++, false, now - n * 2 * DAY - 5 * HOUR, 2, "Screenshots", "Pictures/Screenshots/"))
            for (n in 0 until 6) add(Item(id++, false, now - n * 3 * DAY - 8 * HOUR, 3, "Signal", "Pictures/Signal/"))
            for (n in 0 until 4) add(Item(id++, false, now - 40 * DAY - n * DAY, 4, "Trips", "Pictures/Trips/"))
            // Bin: deleted at different times, so the days-left badges differ.
            for (n in 0 until 6) add(Item(id++, n == 2, now - 50 * DAY, 1, "Camera", "DCIM/Camera/",
                trashedExpiresSec = (now + (29 - n * 5) * DAY) / 1000))
        }
    }
}
