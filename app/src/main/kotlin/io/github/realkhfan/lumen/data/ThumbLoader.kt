package io.github.realkhfan.lumen.data

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Handler
import android.os.OperationCanceledException
import android.util.LruCache
import android.util.Size
import io.github.realkhfan.lumen.LumenApp
import io.github.realkhfan.lumen.core.MediaItem
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Thumbnails come from Android's own thumbnail cache (ContentResolver.loadThumbnail), so they
 * are fast and nothing extra is stored. A memory cache keeps recently shown ones, and the
 * newest request runs first, which keeps fast scrolling snappy.
 */
class ThumbLoader(context: Context, private val main: Handler) {
    private val resolver = context.contentResolver

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val pool = ThreadPoolExecutor(
        3, 3, 30, TimeUnit.SECONDS, LifoQueue(), LumenApp.backgroundThreads("thumb"),
    ).apply { allowCoreThreadTimeOut(true) }

    class Request internal constructor(private val signal: CancellationSignal) {
        internal var future: Future<*>? = null
        @Volatile
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
            signal.cancel()
            future?.cancel(false)
        }
    }

    /** Thumbnail sizes used, so the same picture isn't kept at many sizes. */
    fun bucket(px: Int): Int = when {
        px <= 200 -> 200
        px <= 360 -> 360
        else -> 640
    }

    fun cached(item: MediaItem, px: Int): Bitmap? = cache.get(key(item, bucket(px)))

    /** Any cached size, biggest first; used by the viewer as an instant placeholder. */
    fun bestCached(item: MediaItem): Bitmap? =
        cache.get(key(item, 640)) ?: cache.get(key(item, 360)) ?: cache.get(key(item, 200))

    fun load(item: MediaItem, px: Int, done: (Bitmap?) -> Unit): Request {
        val size = bucket(px)
        val k = key(item, size)
        val signal = CancellationSignal()
        val request = Request(signal)
        cache.get(k)?.let {
            done(it)
            return request
        }
        request.future = pool.submit {
            if (request.cancelled) return@submit
            val bitmap = try {
                resolver.loadThumbnail(MediaRepo.uriOf(item), Size(size, size), signal)
            } catch (e: IOException) {
                null
            } catch (e: OperationCanceledException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (bitmap != null) cache.put(k, bitmap)
            main.post { if (!request.cancelled) done(bitmap) }
        }
        return request
    }

    private fun key(item: MediaItem, size: Int) = "${item.id}:${item.dateModifiedSec}:$size"

    /** A queue that hands out the most recently added task first. */
    private class LifoQueue : LinkedBlockingDeque<Runnable>(), BlockingQueue<Runnable> {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
        override fun add(element: Runnable): Boolean {
            addFirst(element)
            return true
        }
    }
}
