package io.github.realkhfan.lumen.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import android.util.Log
import io.github.realkhfan.lumen.LumenApp
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.core.MediaOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Reads photos and videos from Android's media database (MediaStore) and keeps the latest
 * list in memory. Lumen never copies, moves or uploads files itself: moving to the bin,
 * restoring and deleting all go through Android's own requests.
 */
class MediaRepo(private val context: Context, private val main: Handler) {
    private val resolver: ContentResolver = context.contentResolver
    private val worker = Executors.newSingleThreadExecutor(LumenApp.backgroundThreads("media"))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var watching = false
    private var generation = 0

    /** Everything on the phone except the bin, newest first. Null until the first load. */
    @Volatile
    var library: List<MediaItem>? = null
        private set

    /** The bin, most recently deleted first. */
    @Volatile
    var bin: List<MediaItem>? = null
        private set

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = scheduleReload()
    }
    private val reload = Runnable { reloadNow() }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Starts watching for changes and loads the lists if needed. */
    fun start() {
        if (!Compat.hasMediaAccess(context)) return
        if (!watching) {
            for (uri in watchedUris()) resolver.registerContentObserver(uri, true, observer)
            watching = true
        }
        if (library == null) reloadNow()
    }

    fun scheduleReload() {
        main.removeCallbacks(reload)
        main.postDelayed(reload, 300)
    }

    fun reloadNow() {
        main.removeCallbacks(reload)
        val gen = ++generation
        worker.execute {
            val lib = query(trashed = false).sortedWith(MediaOrder.NEWEST_FIRST)
            val trashed = query(trashed = true).sortedWith(MediaOrder.RECENTLY_DELETED_FIRST)
            main.post {
                if (gen != generation) return@post
                library = lib
                bin = trashed
                for (l in listeners) l()
            }
        }
    }

    /** Loads the library on the calling (background) thread if it isn't in memory yet. */
    fun libraryBlocking(): List<MediaItem> =
        library ?: query(trashed = false).sortedWith(MediaOrder.NEWEST_FIRST)

    fun binBlocking(): List<MediaItem> =
        bin ?: query(trashed = true).sortedWith(MediaOrder.RECENTLY_DELETED_FIRST)

    private fun watchedUris(): List<Uri> = listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
    )

    private fun query(trashed: Boolean): List<MediaItem> {
        if (!Compat.hasMediaAccess(context)) return emptyList()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        return try {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), PROJECTION, args, null)
                ?.use { readAll(it) } ?: emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media query failed", e)
            emptyList()
        }
    }

    /** Looks up one item by its media URI, including ones the camera is still finishing. */
    fun queryOne(uri: Uri): MediaItem? {
        if (!isMediaUri(uri)) return null
        val id = try {
            ContentUris.parseId(uri)
        } catch (e: RuntimeException) {
            return null
        }
        if (id < 0) return null
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaColumns._ID} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return try {
            resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), PROJECTION, args, null)
                ?.use { readAll(it).firstOrNull() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Lookup failed for $uri", e)
            null
        }
    }

    private fun readAll(c: Cursor): List<MediaItem> {
        val id = c.getColumnIndexOrThrow(MediaColumns._ID)
        val type = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val mime = c.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
        val taken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
        val added = c.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
        val modified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
        val bucket = c.getColumnIndexOrThrow(MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndexOrThrow(MediaColumns.BUCKET_DISPLAY_NAME)
        val path = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
        val name = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
        val size = c.getColumnIndexOrThrow(MediaColumns.SIZE)
        val width = c.getColumnIndexOrThrow(MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaColumns.HEIGHT)
        val duration = c.getColumnIndexOrThrow(MediaColumns.DURATION)
        val orientation = c.getColumnIndexOrThrow(MediaColumns.ORIENTATION)
        val expires = c.getColumnIndexOrThrow(MediaColumns.DATE_EXPIRES)
        val pending = c.getColumnIndexOrThrow(MediaColumns.IS_PENDING)
        val trashed = c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)
        val out = ArrayList<MediaItem>(c.count)
        while (c.moveToNext()) {
            val isVideo = c.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO
            out.add(
                MediaItem(
                    id = c.getLong(id),
                    isVideo = isVideo,
                    mime = c.getString(mime) ?: if (isVideo) "video/*" else "image/*",
                    dateTakenMs = c.getLong(taken),
                    dateAddedSec = c.getLong(added),
                    dateModifiedSec = c.getLong(modified),
                    bucketId = c.getLong(bucket),
                    bucketName = c.getString(bucketName) ?: "",
                    relativePath = c.getString(path) ?: "",
                    displayName = c.getString(name) ?: "",
                    sizeBytes = c.getLong(size),
                    width = c.getInt(width),
                    height = c.getInt(height),
                    durationMs = c.getLong(duration),
                    orientation = c.getInt(orientation),
                    dateExpiresSec = c.getLong(expires),
                    isPending = c.getInt(pending) != 0,
                    isTrashed = c.getInt(trashed) != 0,
                ),
            )
        }
        return out
    }

    /** Ask Android to move items to the bin, or back out of it. Returns null if not possible. */
    fun trashRequest(items: Collection<MediaItem>, toBin: Boolean): PendingIntent? = request {
        MediaStore.createTrashRequest(resolver, items.map { uriOf(it) }, toBin)
    }

    /** Ask Android to delete items for good. */
    fun deleteRequest(items: Collection<MediaItem>): PendingIntent? = request {
        MediaStore.createDeleteRequest(resolver, items.map { uriOf(it) })
    }

    private inline fun request(make: () -> PendingIntent): PendingIntent? = try {
        make()
    } catch (e: RuntimeException) {
        Log.w(TAG, "MediaStore request failed", e)
        null
    }

    companion object {
        private const val TAG = "Lumen"

        private val PROJECTION = arrayOf(
            MediaColumns._ID, FileColumns.MEDIA_TYPE, MediaColumns.MIME_TYPE,
            MediaColumns.DATE_TAKEN, MediaColumns.DATE_ADDED, MediaColumns.DATE_MODIFIED,
            MediaColumns.BUCKET_ID, MediaColumns.BUCKET_DISPLAY_NAME, MediaColumns.RELATIVE_PATH,
            MediaColumns.DISPLAY_NAME, MediaColumns.SIZE, MediaColumns.WIDTH, MediaColumns.HEIGHT,
            MediaColumns.DURATION, MediaColumns.ORIENTATION, MediaColumns.DATE_EXPIRES, MediaColumns.IS_PENDING,
            MediaColumns.IS_TRASHED,
        )

        fun uriOf(item: MediaItem): Uri {
            val uri = ContentUris.withAppendedId(
                if (item.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                item.id,
            )
            if (!item.isTrashed && !item.isPending) return uri
            // Android hides binned and unfinished files unless the URI asks for them explicitly.
            val b = uri.buildUpon()
            if (item.isTrashed) b.appendQueryParameter("includeTrashed", "1")
            if (item.isPending) b.appendQueryParameter("includePending", "1")
            return b.build()
        }

        fun isMediaUri(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY
    }
}
