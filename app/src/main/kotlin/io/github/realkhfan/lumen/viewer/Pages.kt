package io.github.realkhfan.lumen.viewer

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import io.github.realkhfan.lumen.R
import io.github.realkhfan.lumen.core.Format
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.lumen
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import java.io.IOException
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/** One thing the viewer can show: an item from the media database, or any picture URI. */
sealed class Entry {
    abstract val uri: Uri
    abstract val mime: String
    abstract val isVideo: Boolean
    open val item: MediaItem? get() = null

    class Media(override val item: MediaItem) : Entry() {
        override val uri: Uri = MediaRepo.uriOf(item)
        override val mime: String get() = item.mime
        override val isVideo: Boolean get() = item.isVideo
    }

    class Raw(override val uri: Uri, override val mime: String) : Entry() {
        override val isVideo: Boolean get() = mime.startsWith("video/")
    }
}

/** Decodes a picture no bigger than [maxDim] on its long side. Runs on a background thread. */
object Decoder {
    class Result(val drawable: Drawable, val width: Int, val height: Int)

    @Throws(IOException::class)
    fun decode(resolver: ContentResolver, uri: Uri, maxDim: Int): Result {
        var w = 0
        var h = 0
        val source = ImageDecoder.createSource(resolver, uri)
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // Size is already turned the right way up (EXIF orientation is applied).
            w = info.size.width
            h = info.size.height
            val long = max(w, h)
            if (long > maxDim) {
                val s = maxDim.toFloat() / long
                decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
            }
        }
        return Result(drawable, w, h)
    }
}

/** A photo page: zoomable picture, with a spinner while the camera is still finishing it. */
@SuppressLint("ViewConstructor")
class ImagePage(context: Context) : FrameLayout(context) {
    val zoom = ZoomImageView(context)
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        visibility = View.GONE
    }
    private val message = Ui.text(context, 15f, 0xDDFFFFFF.toInt(), Fonts.body).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private var entry: Entry? = null
    private var task: Future<*>? = null
    private var token = 0
    private var loadedMax = 0
    private var loadingMax = 0
    private var attempts = 0
    private var waitForCamera = false

    init {
        addView(zoom, LayoutParams(-1, -1))
        addView(progress, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        addView(message, LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dp(72) })
    }

    /**
     * [sharp] decodes at full detail (the page on screen); neighbours use screen size to save
     * memory. [fromCamera] retries for a while if the camera hasn't finished the file yet.
     */
    fun bind(e: Entry, sharp: Boolean, fromCamera: Boolean) {
        val same = entry === e
        entry = e
        waitForCamera = fromCamera
        if (!same) {
            cancel()
            loadedMax = 0
            loadingMax = 0
            attempts = 0
            message.visibility = View.GONE
            val item = e.item
            val placeholder = item?.let { context.lumen.thumbs.bestCached(it) }
            if (placeholder != null) {
                val w = if (item.shownWidth > 0) item.shownWidth else placeholder.width
                val h = if (item.shownHeight > 0) item.shownHeight else placeholder.height
                zoom.setImage(BitmapDrawable(resources, placeholder), w, h, keepZoom = false)
            } else {
                zoom.setImage(null, item?.shownWidth ?: 0, item?.shownHeight ?: 0, keepZoom = false)
            }
        }
        load(if (sharp) SHARP else screenMax())
    }

    fun sharpen() = load(SHARP)

    fun unbind() {
        cancel()
        entry = null
        loadedMax = 0
        loadingMax = 0
        zoom.setImage(null, 0, 0, keepZoom = false)
    }

    private fun screenMax(): Int {
        val m = resources.displayMetrics
        return max(m.widthPixels, m.heightPixels).coerceIn(1080, SHARP)
    }

    private fun cancel() {
        token++
        task?.cancel(true)
        task = null
        removeCallbacks(retry)
    }

    private val retry = Runnable { load(max(loadedMax, screenMax()), force = true) }

    private fun load(maxDim: Int, force: Boolean = false) {
        val e = entry ?: return
        if (!force && maxDim <= max(loadedMax, loadingMax)) return
        val my = ++token
        loadingMax = maxDim
        if (!zoom.hasImage()) showProgress(true)
        val resolver = context.contentResolver
        task = context.lumen.decoder.submit {
            val result = try {
                Decoder.decode(resolver, e.uri, maxDim)
            } catch (ex: IOException) {
                null
            } catch (ex: RuntimeException) {
                null
            } catch (ex: OutOfMemoryError) {
                null
            }
            post {
                if (my != token || entry !== e) return@post
                loadingMax = 0
                if (result != null) {
                    loadedMax = maxDim
                    showProgress(false)
                    message.visibility = View.GONE
                    zoom.setImage(result.drawable, result.width, result.height, keepZoom = zoom.hasImage())
                } else if (waitForCamera && attempts < 30) {
                    // Pixel Camera hands over the photo before it has finished processing it.
                    attempts++
                    showProgress(true)
                    if (attempts > 2) {
                        message.text = context.getString(R.string.processing)
                        message.visibility = View.VISIBLE
                    }
                    postDelayed(retry, 600)
                } else if (!zoom.hasImage()) {
                    showProgress(false)
                    message.text = context.getString(R.string.cant_open)
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showProgress(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun canScrollHorizontally(direction: Int) = zoom.canScrollHorizontally(direction)
    override fun canScrollVertically(direction: Int) = zoom.canScrollVertically(direction)

    companion object {
        /** Long side for the picture on screen: full detail for 12 MP, plenty to zoom into 50 MP. */
        const val SHARP = 4096
    }
}

/** A video page: poster, big play button, then playback with simple controls. */
@SuppressLint("ViewConstructor")
class VideoPage(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    var onTap: (() -> Unit)? = null

    private val texture = TextureView(context)
    private val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val playButton = FrameLayout(context).apply {
        background = Ui.rounded(0x66000000, dpf(36f))
        addView(Ui.icon(context, R.drawable.ic_play_arrow_fill1, Color.WHITE, 40), LayoutParams(dp(40), dp(40), Gravity.CENTER))
        contentDescription = context.getString(R.string.action_play)
    }
    private val controls = LinearLayout(context)
    private val toggle: ImageView
    private val time: TextView
    private val total: TextView
    private val seek = SeekBar(context)
    private val mute: ImageView

    private var entry: Entry? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantPlay = false
    private var muted = false
    private var videoW = 0
    private var videoH = 0
    private var focus: AudioFocusRequest? = null
    private var thumbRequest: io.github.realkhfan.lumen.data.ThumbLoader.Request? = null

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (prepared) {
                seek.progress = p.currentPosition
                time.text = Format.duration(p.currentPosition.toLong())
            }
            if (p.isPlaying) postDelayed(this, 200)
        }
    }

    init {
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(-1, -1))
        addView(poster, LayoutParams(-1, -1))
        addView(playButton, LayoutParams(dp(72), dp(72), Gravity.CENTER))
        playButton.setOnClickListener { play() }

        toggle = Ui.icon(context, R.drawable.ic_pause_fill1, Color.WHITE).apply {
            setOnClickListener { if (player?.isPlaying == true) pause() else play() }
            contentDescription = context.getString(R.string.action_pause)
        }
        time = Ui.text(context, 13f, Color.WHITE, Fonts.medium, "0:00")
        total = Ui.text(context, 13f, Color.WHITE, Fonts.medium, "0:00")
        seek.progressTintList = ColorStateList.valueOf(Color.WHITE)
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser && prepared) {
                    player?.seekTo(value.toLong(), MediaPlayer.SEEK_PREVIOUS_SYNC)
                    time.text = Format.duration(value.toLong())
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                if (prepared) player?.seekTo(bar.progress.toLong(), MediaPlayer.SEEK_CLOSEST)
            }
        })
        mute = Ui.icon(context, R.drawable.ic_volume_up, Color.WHITE).apply {
            contentDescription = context.getString(R.string.action_mute)
            setOnClickListener { setMuted(!muted) }
        }
        controls.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(toggle, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(time)
            addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
            addView(total)
            addView(mute, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
            visibility = View.GONE
        }
        for (v in listOf(toggle, mute)) {
            v.scaleType = ImageView.ScaleType.CENTER
            v.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        }
        addView(controls, LayoutParams(-1, dp(56), Gravity.BOTTOM))
        setOnClickListener { onTap?.invoke() }
    }

    fun bind(e: Entry) {
        if (entry === e) return
        release()
        entry = e
        poster.alpha = 1f
        poster.setImageDrawable(null)
        playButton.visibility = View.VISIBLE
        controls.visibility = View.GONE
        val item = e.item
        if (item != null) {
            videoW = item.shownWidth
            videoH = item.shownHeight
            total.text = Format.duration(item.durationMs)
            val thumbs = context.lumen.thumbs
            val cached = thumbs.bestCached(item)
            if (cached != null) poster.setImageBitmap(cached)
            thumbRequest = thumbs.load(item, 640) { bmp -> if (entry === e && bmp != null) poster.setImageBitmap(bmp) }
        }
    }

    /** Space to keep free at the bottom for the viewer's own buttons. */
    fun setControlsBottom(px: Int) {
        (controls.layoutParams as LayoutParams).bottomMargin = px
        controls.requestLayout()
    }

    fun setControlsShown(shown: Boolean) {
        if (player == null) return
        controls.animate().alpha(if (shown) 1f else 0f).setDuration(150).withEndAction {
            controls.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        }.start()
        if (shown) controls.visibility = View.VISIBLE
    }

    fun play() {
        val e = entry ?: return
        wantPlay = true
        val st = texture.surfaceTexture ?: return
        playButton.visibility = View.GONE
        controls.visibility = View.VISIBLE
        controls.alpha = 1f
        requestAudio()
        val existing = player
        if (existing != null) {
            if (prepared) {
                existing.start()
                post(ticker)
                toggle.setImageResource(R.drawable.ic_pause_fill1)
            }
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(context, e.uri)
            val s = Surface(st)
            surface = s
            mp.setSurface(s)
        } catch (ex: IOException) {
            mp.release()
            showError()
            return
        } catch (ex: RuntimeException) {
            mp.release()
            showError()
            return
        }
        mp.setOnPreparedListener {
            prepared = true
            seek.max = it.duration
            total.text = Format.duration(it.duration.toLong())
            if (muted) it.setVolume(0f, 0f)
            if (wantPlay) {
                it.start()
                post(ticker)
            }
        }
        mp.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) {
                videoW = w
                videoH = h
                fitVideo()
            }
        }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.animate().alpha(0f).setDuration(120).start()
            false
        }
        mp.setOnCompletionListener {
            toggle.setImageResource(R.drawable.ic_play_arrow_fill1)
            playButton.visibility = View.VISIBLE
            it.seekTo(0)
            seek.progress = 0
            time.text = Format.duration(0)
        }
        mp.setOnErrorListener { _, _, _ ->
            showError()
            true
        }
        player = mp
        toggle.setImageResource(R.drawable.ic_pause_fill1)
        mp.prepareAsync()
    }

    fun pause() {
        wantPlay = false
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
        toggle.setImageResource(R.drawable.ic_play_arrow_fill1)
        abandonFocus()
    }

    fun release() {
        wantPlay = false
        removeCallbacks(ticker)
        thumbRequest?.cancel()
        thumbRequest = null
        player?.let {
            it.setOnErrorListener(null)
            it.release()
        }
        player = null
        prepared = false
        surface?.release()
        surface = null
        abandonFocus()
        entry = null
        controls.visibility = View.GONE
        playButton.visibility = View.VISIBLE
        poster.alpha = 1f
    }

    private fun setMuted(on: Boolean) {
        muted = on
        val v = if (on) 0f else 1f
        player?.setVolume(v, v)
        mute.setImageResource(if (on) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        mute.contentDescription = context.getString(if (on) R.string.action_unmute else R.string.action_mute)
    }

    private fun requestAudio() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
        focus = req
        audio?.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun showError() {
        player?.release()
        player = null
        prepared = false
        playButton.visibility = View.GONE
        controls.visibility = View.GONE
        android.widget.Toast.makeText(context, R.string.cant_open, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun fitVideo() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
        val s = min(vw / videoW, vh / videoH)
        val m = Matrix()
        m.setScale(videoW * s / vw, videoH * s / vh, vw / 2, vh / 2)
        texture.setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitVideo() }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        fitVideo()
        if (wantPlay) play()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fitVideo()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val keep = entry
        release()
        entry = keep
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
