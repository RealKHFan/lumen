package io.github.realkhfan.lumen.viewer

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import io.github.realkhfan.lumen.MainActivity
import io.github.realkhfan.lumen.MediaActions
import io.github.realkhfan.lumen.R
import io.github.realkhfan.lumen.compat.BackHandler
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.core.FolderFilter
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.core.Review
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.lumen
import io.github.realkhfan.lumen.ui.BottomSheet
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Motion
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.Snack
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import io.github.realkhfan.lumen.ui.onSystemInsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen viewer. Opened from Lumen's grids, from other apps ("Open with"), and from the
 * camera's thumbnail. When the phone is locked it only shows what the camera handed over.
 */
class ViewerActivity : Activity() {
    private enum class Mode { BROWSE, BIN, CAMERA, SECURE, EXTERNAL }

    private lateinit var palette: Palette
    private lateinit var back: BackHandler
    private lateinit var root: FrameLayout
    private lateinit var background: View
    private lateinit var pager: MediaPager
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var timeText: TextView
    private lateinit var lockChip: View
    private lateinit var moreButton: View
    private val sheet by lazy { BottomSheet(this, palette) }

    private var mode = Mode.EXTERNAL
    private var entries: MutableList<Entry> = ArrayList()
    private var startIndex = 0
    private var fromRect: Rect? = null
    private var entered = false
    private var exiting = false
    private var overlaysShown = true
    private var bottomInset = 0
    private var pending: Pair<Int, Entry>? = null
    private val onRepoChanged: () -> Unit = { refreshPendingCameraShot() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Compat.skipTransition(this, opening = true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val locked = keyguard?.isKeyguardLocked == true
        val action = intent.action
        val source = intent.getIntExtra(EXTRA_SOURCE, 0)
        mode = when {
            source == SOURCE_BIN -> Mode.BIN
            source != 0 -> Mode.BROWSE
            Review.isSecure(action, locked) -> Mode.SECURE
            Review.isReview(action) -> Mode.CAMERA
            else -> Mode.EXTERNAL
        }
        // Show on top of the lock screen only for the camera's locked hand-off.
        if (mode == Mode.SECURE) setShowWhenLocked(true)
        // Ultra HDR photos from the Pixel camera get their extra brightness.
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        palette = Palette(this)
        Ui.setupWindow(this, darkBackground = true)
        Ui.bars(this)?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fromRect = Compat.parcelableExtra(intent, EXTRA_FROM, Rect::class.java)
        back = BackHandler(this) { onBack() }
        back.enabled = true
        buildUi()
        setContentView(root)
        load(source)
        if (mode == Mode.CAMERA) lumen.repo.addListener(onRepoChanged)
    }

    override fun onPause() {
        super.onPause()
        (pager.currentView() as? VideoPage)?.pause()
    }

    override fun onDestroy() {
        lumen.repo.removeListener(onRepoChanged)
        pager.releaseAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root = FrameLayout(this)
        background = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        pager = MediaPager(this)
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))
            setPadding(dp(4), 0, dp(4), dp(24))
        }
        topBar.addView(Ui.iconButton(this, R.drawable.ic_arrow_back, Color.WHITE, getString(R.string.action_back)) { onBack() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        dateText = Ui.text(this, 16f, Color.WHITE, Fonts.display).apply { maxLines = 1 }
        timeText = Ui.text(this, 13f, 0xCCFFFFFF.toInt(), Fonts.body).apply { maxLines = 1 }
        titles.addView(dateText)
        titles.addView(timeText)
        topBar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        lockChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            minimumHeight = dp(48)
            // Looks 34 dp tall; the inset keeps the touch area at 48 dp.
            background = InsetDrawable(Ui.pressable(Ui.rounded(0x33FFFFFF, dpf(17f)), 0x33FFFFFF, dpf(17f)), 0, dp(7), 0, dp(7))
            addView(Ui.icon(this@ViewerActivity, R.drawable.ic_lock, Color.WHITE, 18))
            addView(Ui.text(this@ViewerActivity, 13f, Color.WHITE, Fonts.medium, getString(R.string.locked_hint)).apply {
                setPadding(dp(6), 0, 0, 0)
            })
            setOnClickListener { withUnlock { } }
            visibility = if (mode == Mode.SECURE) View.VISIBLE else View.GONE
        }
        topBar.addView(lockChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        moreButton = Ui.iconButton(this, R.drawable.ic_more_vert, Color.WHITE, getString(R.string.action_more)) { showMenu(it) }
        topBar.addView(moreButton)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(28), dp(8), 0)
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        buildActions()

        root.onSystemInsets { ins ->
            bottomInset = ins.bottom
            topBar.setPadding(dp(4) + ins.left, ins.top, dp(4) + ins.right, dp(24))
            bottomBar.setPadding(dp(8) + ins.left, dp(28), dp(8) + ins.right, ins.bottom + dp(8))
            bottomBar.post { updateVideoControlsSpace() }
        }

        pager.listener = object : MediaPager.Listener {
            override fun onPageSelected(position: Int) = updateTitle()
            override fun onVerticalDrag(dy: Float) = dragPage(dy)
            override fun onVerticalRelease(dy: Float, velocityY: Float) = releasePage(dy, velocityY)
        }
    }

    private fun buildActions() {
        bottomBar.removeAllViews()
        fun action(icon: Int, label: Int, run: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(64)
                background = Ui.pressable(null, 0x33FFFFFF, dpf(20f))
                contentDescription = getString(label)
                addView(Ui.icon(this@ViewerActivity, icon, Color.WHITE))
                addView(Ui.text(this@ViewerActivity, 12f, Color.WHITE, Fonts.medium, getString(label)).apply {
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1
                })
                setOnClickListener { run() }
            }
            bottomBar.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        when (mode) {
            Mode.BIN -> {
                action(R.drawable.ic_restore_from_trash, R.string.action_restore) { restoreCurrent() }
                action(R.drawable.ic_info, R.string.action_info) { showInfo() }
                action(R.drawable.ic_delete_forever, R.string.action_delete_forever) { deleteCurrent() }
            }
            else -> {
                action(R.drawable.ic_share, R.string.action_share) { withUnlock { shareCurrent() } }
                action(R.drawable.ic_edit, R.string.action_edit) { withUnlock { editCurrent() } }
                action(R.drawable.ic_info, R.string.action_info) { showInfo() }
                action(R.drawable.ic_delete, R.string.action_move_to_bin) { withUnlock { trashCurrent() } }
            }
        }
    }

    // ------------------------------------------------------------------ loading

    private fun load(source: Int) {
        val app = lumen
        val startId = intent.getLongExtra(EXTRA_START_ID, -1)
        val filter = FolderFilter.decode(intent.getStringExtra(EXTRA_FILTER))
        val bucket = intent.getLongExtra(EXTRA_BUCKET, 0)
        val uris = incomingUris()
        val currentMode = mode
        app.decoder.execute {
            var start = 0
            val list: List<Entry> = when (currentMode) {
                Mode.BROWSE, Mode.BIN -> {
                    val items = when (source) {
                        SOURCE_BIN -> app.repo.binBlocking()
                        SOURCE_FOLDER -> app.repo.libraryBlocking().filter { it.bucketId == bucket }
                        else -> filter.apply(app.repo.libraryBlocking())
                    }
                    start = max(0, items.indexOfFirst { it.id == startId })
                    items.map { Entry.Media(it) }
                }
                Mode.SECURE -> {
                    // Only what the camera handed over during this locked session, and only
                    // photos taken just now, whoever sent the request.
                    val now = System.currentTimeMillis()
                    val session = app.secureSession.add(uris.map { it.toString() }, now)
                    session.map { entryFor(Uri.parse(it)) }.filter { e ->
                        val item = e.item
                        if (item != null) Review.freshForLockScreen(item.dateAddedSec, now) else !MediaRepo.isMediaUri(e.uri)
                    }
                }
                Mode.CAMERA, Mode.EXTERNAL -> {
                    val first = uris.firstOrNull()?.let { if (Compat.hasMediaAccess(this)) app.repo.queryOne(it) else null }
                    if (first != null && !first.isPending) {
                        // Show it among the rest of its folder, so you can swipe to older shots.
                        val folder = app.repo.libraryBlocking().filter { it.bucketId == first.bucketId }
                        val i = folder.indexOfFirst { it.id == first.id }
                        if (i >= 0) {
                            start = i
                            folder.map { Entry.Media(it) }
                        } else {
                            listOf<Entry>(Entry.Media(first)) + folder.map { Entry.Media(it) }
                        }
                    } else if (first != null) {
                        val folder = app.repo.libraryBlocking().filter { it.bucketId == first.bucketId && it.id != first.id }
                        listOf<Entry>(Entry.Media(first)) + folder.map { Entry.Media(it) }
                    } else {
                        uris.map { entryFor(it) }
                    }
                }
            }
            app.main.post { show(list, start) }
        }
    }

    /** Pictures handed to us: the intent's data plus any extra ones in its clip. */
    private fun incomingUris(): List<Uri> {
        val out = LinkedHashSet<Uri>()
        intent.data?.let { out.add(it) }
        val clip = intent.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        // Android forbids passing file:// links on to other apps, so Share would crash on them.
        return out.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    /** Background thread: describes any URI, using the media database when possible. */
    private fun entryFor(uri: Uri): Entry {
        if (MediaRepo.isMediaUri(uri)) lumen.repo.queryOne(uri)?.let { return Entry.Media(it) }
        val type = try {
            contentResolver.getType(uri)
        } catch (e: RuntimeException) {
            null
        } ?: intent.type ?: "image/*"
        return Entry.Raw(uri, type)
    }

    private fun show(list: List<Entry>, start: Int) {
        if (isFinishing || isDestroyed) return
        if (list.isEmpty()) {
            finish()
            return
        }
        entries = ArrayList(list)
        startIndex = start.coerceIn(0, entries.lastIndex)
        pager.adapter = pagerAdapter
        pager.setCurrent(startIndex)
        updateTitle()
        if (!entered) {
            entered = true
            pager.post { enterAnimation() }
        }
    }

    /** The camera's newest shot may still be "pending" when we open; swap in the finished one. */
    private fun refreshPendingCameraShot() {
        val i = pager.current
        val e = entries.getOrNull(i) as? Entry.Media ?: return
        if (!e.item.isPending) return
        val uri = e.uri
        lumen.decoder.execute {
            val fresh = lumen.repo.queryOne(uri) ?: return@execute
            if (fresh.isPending) return@execute
            lumen.main.post {
                val idx = entries.indexOfFirst { it is Entry.Media && it.item.id == fresh.id }
                if (idx >= 0) {
                    entries[idx] = Entry.Media(fresh)
                    pager.dataChanged(pager.current)
                    updateTitle()
                }
            }
        }
    }

    private val pagerAdapter = object : MediaPager.Adapter {
        override val count: Int get() = entries.size
        override fun type(position: Int): Int = if (entries[position].isVideo) 1 else 0

        override fun create(type: Int): View = if (type == 1) {
            VideoPage(this@ViewerActivity).also { it.onTap = { toggleOverlays() } }
        } else {
            ImagePage(this@ViewerActivity).also { it.zoom.onTap = { toggleOverlays() } }
        }

        override fun bind(view: View, position: Int, current: Boolean) {
            val e = entries[position]
            when (view) {
                is ImagePage -> view.bind(e, sharp = current, fromCamera = mode == Mode.CAMERA || mode == Mode.SECURE)
                is VideoPage -> {
                    view.bind(e)
                    view.setControlsBottom(bottomBar.height)
                }
            }
        }

        override fun unbind(view: View) {
            when (view) {
                is ImagePage -> view.unbind()
                is VideoPage -> view.release()
            }
        }

        override fun onCurrent(view: View, position: Int) {
            when (view) {
                is ImagePage -> view.sharpen()
                is VideoPage -> {
                    view.setControlsBottom(bottomBar.height)
                    view.play()
                }
            }
        }

        override fun onNotCurrent(view: View) {
            when (view) {
                is ImagePage -> view.zoom.reset()
                is VideoPage -> view.pause()
            }
        }
    }

    private fun current(): Entry? = entries.getOrNull(pager.current)

    private fun updateTitle() {
        val item = current()?.item
        if (item == null) {
            dateText.text = ""
            timeText.text = ""
            return
        }
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(item.timeMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        dateText.text = when (date) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> DateFormat.format(
                DateFormat.getBestDateTimePattern(locale, if (date.year == today.year) "EEEdMMM" else "EEEdMMMy"),
                Date(item.timeMs),
            )
        }
        timeText.text = DateFormat.getTimeFormat(this).format(Date(item.timeMs))
    }

    private fun updateVideoControlsSpace() {
        (pager.currentView() as? VideoPage)?.setControlsBottom(bottomBar.height)
    }

    // ------------------------------------------------------------------ overlays and gestures

    private fun toggleOverlays() = setOverlays(!overlaysShown)

    private fun setOverlays(show: Boolean) {
        overlaysShown = show
        for (v in listOf(topBar, bottomBar)) {
            v.animate().alpha(if (show) 1f else 0f).setDuration(180).withStartAction {
                if (show) v.visibility = View.VISIBLE
            }.withEndAction {
                if (!show) v.visibility = View.INVISIBLE
            }.start()
        }
        (pager.currentView() as? VideoPage)?.setControlsShown(show)
        val c = Ui.bars(this) ?: return
        if (show) c.show(WindowInsets.Type.systemBars()) else c.hide(WindowInsets.Type.systemBars())
    }

    private fun dragPage(dy: Float) {
        val page = pager.currentView() ?: return
        if (dy > 0) {
            val f = (dy / max(1, pager.height)).coerceIn(0f, 1f)
            page.translationY = dy
            page.scaleX = 1f - f * 0.3f
            page.scaleY = page.scaleX
            background.alpha = (1f - f * 1.6f).coerceIn(0f, 1f)
            topBar.alpha = if (overlaysShown) (1f - f * 4f).coerceIn(0f, 1f) else 0f
            bottomBar.alpha = topBar.alpha
        } else {
            page.translationY = dy * 0.3f
        }
    }

    private fun releasePage(dy: Float, vy: Float) {
        val page = pager.currentView() ?: return
        val closing = dy > pager.height * 0.12f || (dy > dp(24) && vy > dpf(1000f))
        if (closing) {
            exit(fromDrag = true)
            return
        }
        page.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.STANDARD).start()
        background.animate().alpha(1f).setDuration(220).start()
        if (overlaysShown) {
            topBar.animate().alpha(1f).setDuration(220).start()
            bottomBar.animate().alpha(1f).setDuration(220).start()
        }
        if (dy < -dp(64) || vy < -dpf(1000f)) showInfo()
    }

    /** Where [page] sits on screen without its own scale and translation. */
    private fun pageOrigin(page: View): FloatArray {
        val loc = IntArray(2)
        pager.getLocationOnScreen(loc)
        return floatArrayOf(loc[0] + page.left - pager.scrollX.toFloat(), loc[1] + page.top.toFloat())
    }

    private fun enterAnimation() {
        val page = pager.currentView() ?: return
        val rect = fromRect
        val zoom = (page as? ImagePage)?.zoom
        if (rect == null || zoom == null || !zoom.hasImage()) {
            background.alpha = 0f
            background.animate().alpha(1f).setDuration(180).start()
            page.alpha = 0f
            page.scaleX = 0.94f
            page.scaleY = 0.94f
            page.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Motion.ENTER).start()
            return
        }
        val shown = zoom.displayRect(RectF())
        val origin = pageOrigin(page)
        val s = max(rect.width() / max(1f, shown.width()), rect.height() / max(1f, shown.height()))
        page.pivotX = shown.centerX()
        page.pivotY = shown.centerY()
        page.scaleX = s
        page.scaleY = s
        page.translationX = rect.exactCenterX() - (origin[0] + shown.centerX())
        page.translationY = rect.exactCenterY() - (origin[1] + shown.centerY())
        background.alpha = 0f
        topBar.alpha = 0f
        bottomBar.alpha = 0f
        page.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(300).setInterpolator(Motion.ENTER).start()
        background.animate().alpha(1f).setDuration(240).start()
        topBar.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
        bottomBar.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
    }

    private fun exit(fromDrag: Boolean = false) {
        if (exiting) return
        exiting = true
        val page = pager.currentView()
        (page as? VideoPage)?.pause()
        val rect = fromRect
        val zoom = (page as? ImagePage)?.zoom
        val finishNow = Runnable {
            finish()
            Compat.skipTransition(this, opening = false)
        }
        if (page != null && rect != null && zoom != null && pager.current == startIndex && !zoom.isZoomed &&
            (mode == Mode.BROWSE || mode == Mode.BIN)
        ) {
            val shown = zoom.displayRect(RectF())
            val origin = pageOrigin(page)
            val baseX = origin[0]
            val baseY = origin[1]
            val s = min(rect.width() / max(1f, shown.width()), rect.height() / max(1f, shown.height()))
            page.pivotX = shown.centerX()
            page.pivotY = shown.centerY()
            page.animate().scaleX(s).scaleY(s)
                .translationX(rect.exactCenterX() - (baseX + shown.centerX()))
                .translationY(rect.exactCenterY() - (baseY + shown.centerY()))
                .alpha(0.85f).setDuration(240).setInterpolator(Motion.STANDARD).withEndAction(finishNow).start()
        } else if (page != null) {
            val y = if (fromDrag) pager.height.toFloat() else page.translationY
            page.animate().translationY(y).alpha(0f).scaleX(page.scaleX * 0.92f).scaleY(page.scaleY * 0.92f)
                .setDuration(200).setInterpolator(Motion.EXIT).withEndAction(finishNow).start()
        } else {
            finishNow.run()
            return
        }
        background.animate().alpha(0f).setDuration(220).start()
        topBar.animate().alpha(0f).setDuration(120).start()
        bottomBar.animate().alpha(0f).setDuration(120).start()
    }

    private fun onBack() {
        if (sheet.isShowing) sheet.dismiss() else exit()
    }

    @Deprecated("Android 12 back handling; Android 13+ uses BackHandler")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation") // only reached on Android 12; 13+ uses BackHandler
    override fun onBackPressed() {
        if (!back.handleLegacy()) super.onBackPressed()
    }

    // ------------------------------------------------------------------ actions

    /** Locked phone: ask to unlock first, then switch to the normal viewer and continue. */
    private fun withUnlock(action: () -> Unit) {
        if (mode != Mode.SECURE) {
            action()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            leaveSecureMode()
            action()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                leaveSecureMode()
                action()
            }
        })
    }

    private fun leaveSecureMode() {
        if (mode != Mode.SECURE) return
        mode = Mode.CAMERA
        lumen.secureSession.clear()
        setShowWhenLocked(false)
        lockChip.visibility = View.GONE
        lumen.repo.start()
        lumen.repo.addListener(onRepoChanged)
        // Now that the phone is unlocked, show the rest of the folder too.
        val e = current() ?: return
        val app = lumen
        app.decoder.execute {
            val item = e.item ?: (if (MediaRepo.isMediaUri(e.uri)) app.repo.queryOne(e.uri) else null) ?: return@execute
            val folder = app.repo.libraryBlocking().filter { it.bucketId == item.bucketId }
            val i = folder.indexOfFirst { it.id == item.id }
            if (i < 0) return@execute
            app.main.post {
                entries = folder.mapTo(ArrayList()) { Entry.Media(it) }
                startIndex = i
                pager.dataChanged(i)
                updateTitle()
            }
        }
    }

    private fun shareCurrent() {
        val e = current() ?: return
        MediaActions.share(this, listOf(e.uri), listOf(e.mime))
    }

    private fun editCurrent() {
        val e = current() ?: return
        MediaActions.edit(this, e.uri, e.mime)
    }

    private fun trashCurrent() {
        val e = current() ?: return
        val item = e.item ?: return
        pending = pager.current to e
        MediaActions.startRequest(this, lumen.repo.trashRequest(listOf(item), true), MediaActions.REQ_TRASH)
    }

    private fun restoreCurrent() {
        val e = current() ?: return
        val item = e.item ?: return
        pending = pager.current to e
        MediaActions.startRequest(this, lumen.repo.trashRequest(listOf(item), false), MediaActions.REQ_RESTORE)
    }

    private fun deleteCurrent() {
        val e = current() ?: return
        val item = e.item ?: return
        pending = pager.current to e
        MediaActions.startRequest(this, lumen.repo.deleteRequest(listOf(item)), MediaActions.REQ_DELETE)
    }

    @Deprecated("Framework callback")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val done = pending ?: return
        if (resultCode != RESULT_OK) return
        val (index, entry) = done
        val snackBottom = bottomBar.height + dp(8)
        when (requestCode) {
            MediaActions.REQ_TRASH -> {
                removeEntry(entry)
                if (!isFinishing) {
                    Snack.show(root, palette, resources.getQuantityString(R.plurals.moved_to_bin, 1, 1), snackBottom,
                        getString(R.string.undo)) {
                        val item = entry.item ?: return@show
                        pending = index to entry
                        MediaActions.startRequest(this, lumen.repo.trashRequest(listOf(item), false), MediaActions.REQ_UNDO)
                    }
                }
            }
            MediaActions.REQ_UNDO -> {
                val at = index.coerceIn(0, entries.size)
                val item = entry.item
                entries.add(at, if (item != null) Entry.Media(item.copy(dateExpiresSec = 0, isTrashed = false)) else entry)
                pager.dataChanged(at)
                updateTitle()
            }
            MediaActions.REQ_RESTORE -> {
                removeEntry(entry)
                if (!isFinishing) Snack.show(root, palette, resources.getQuantityString(R.plurals.restored, 1, 1), snackBottom)
            }
            MediaActions.REQ_DELETE -> {
                removeEntry(entry)
                if (!isFinishing) Snack.show(root, palette, resources.getQuantityString(R.plurals.deleted_forever, 1, 1), snackBottom)
            }
        }
        pending = null
        lumen.repo.reloadNow()
    }

    private fun removeEntry(entry: Entry) {
        val i = entries.indexOf(entry)
        if (i < 0) return
        entries.removeAt(i)
        if (entries.isEmpty()) {
            finish()
            Compat.skipTransition(this, opening = false)
            return
        }
        fromRect = null
        pager.dataChanged(min(i, entries.lastIndex))
        updateTitle()
    }

    private fun showMenu(anchor: View) {
        val e = current() ?: return
        val menu = PopupMenu(this, anchor)
        if (mode != Mode.BIN) {
            menu.menu.add(0, 1, 0, R.string.action_use_as)
            menu.menu.add(0, 2, 1, R.string.action_open_with)
            if (mode == Mode.CAMERA || mode == Mode.EXTERNAL) menu.menu.add(0, 3, 2, R.string.action_open_lumen)
        } else {
            menu.menu.add(0, 2, 1, R.string.action_open_with)
        }
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> withUnlock { MediaActions.useAs(this, e.uri, e.mime) }
                2 -> withUnlock { MediaActions.openWith(this, e.uri, e.mime) }
                3 -> withUnlock {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            true
        }
        menu.show()
    }

    private fun showInfo() {
        val e = current() ?: return
        val app = lumen
        app.decoder.execute {
            val details = Info.read(this, e)
            app.main.post {
                if (isFinishing || current() !== e || sheet.isShowing) return@post
                sheet.show(Info.build(this, palette, e, details) {
                    sheet.dismiss()
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), REQ_LOCATION)
                })
            }
        }
    }

    @Deprecated("Framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_LOCATION) showInfo()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_START_ID = "start_id"
        const val EXTRA_FROM = "from"
        const val EXTRA_FILTER = "filter"
        const val EXTRA_BUCKET = "bucket"
        const val SOURCE_LIBRARY = 1
        const val SOURCE_FOLDER = 2
        const val SOURCE_BIN = 3
        private const val REQ_LOCATION = 201
    }
}
