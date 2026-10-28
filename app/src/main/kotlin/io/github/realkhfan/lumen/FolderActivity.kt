package io.github.realkhfan.lumen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.realkhfan.lumen.compat.BackHandler
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.grid.PhotoGrid
import io.github.realkhfan.lumen.ui.ActionPill
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Motion
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.Snack
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import io.github.realkhfan.lumen.ui.onSystemInsets
import io.github.realkhfan.lumen.ui.withAlpha
import io.github.realkhfan.lumen.viewer.ViewerActivity

/** Everything inside one folder. */
class FolderActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var back: BackHandler
    private lateinit var root: FrameLayout
    private lateinit var grid: PhotoGrid
    private lateinit var topBg: View
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var backButton: View
    private lateinit var closeButton: View
    private lateinit var actions: ActionPill
    private lateinit var empty: View
    private var bucketId = 0L
    private var folderName = ""
    private var bottomInset = 0
    private var pendingItems: List<MediaItem> = emptyList()
    private val onData: () -> Unit = { showData() }

    private var themeGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeGeneration = lumen.themeGeneration
        bucketId = intent.getLongExtra(EXTRA_BUCKET, 0)
        folderName = intent.getStringExtra(EXTRA_NAME) ?: ""
        palette = Palette(this)
        Ui.setupWindow(this, palette.isDark)
        back = BackHandler(this) { grid.clearSelection() }
        buildUi()
        setContentView(root)
        lumen.repo.addListener(onData)
        lumen.repo.start()
        showData()
    }

    override fun onResume() {
        super.onResume()
        if (themeGeneration != lumen.themeGeneration) {
            recreate()
            return
        }
        grid.refreshDates()
    }

    override fun onDestroy() {
        lumen.repo.removeListener(onData)
        super.onDestroy()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        grid = PhotoGrid(this, palette, PhotoGrid.Kind.FOLDER).apply {
            setColumns(lumen.prefs.columns)
            listener = object : PhotoGrid.Listener {
                override fun onOpen(index: Int, item: MediaItem, bounds: Rect) {
                    startActivity(Intent(this@FolderActivity, ViewerActivity::class.java)
                        .putExtra(ViewerActivity.EXTRA_SOURCE, ViewerActivity.SOURCE_FOLDER)
                        .putExtra(ViewerActivity.EXTRA_BUCKET, bucketId)
                        .putExtra(ViewerActivity.EXTRA_START_ID, item.id)
                        .putExtra(ViewerActivity.EXTRA_FROM, bounds))
                }
                override fun onSelectionChanged(selected: Set<Long>) = updateSelectionUi()
                override fun onColumnsChanged(columns: Int) {
                    lumen.prefs.columns = columns
                }
                override fun onScrolled(awayFromTop: Boolean) {
                    topBg.animate().alpha(if (awayFromTop) 1f else 0f).setDuration(150).start()
                }
            }
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = Ui.emptyState(this, palette, R.drawable.ic_folder,
            getString(R.string.folder_empty_title), getString(R.string.folder_empty_body)).apply { visibility = View.GONE }
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))

        val bar = FrameLayout(this)
        topBg = View(this).apply {
            setBackgroundColor(withAlpha(palette.surface, 0.96f))
            alpha = 0f
        }
        bar.addView(topBg, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        backButton = Ui.iconButton(this, R.drawable.ic_arrow_back, palette.onSurface, getString(R.string.action_back)) { finish() }
        closeButton = Ui.iconButton(this, R.drawable.ic_close, palette.onSurface, getString(R.string.action_close)) {
            grid.clearSelection()
        }.apply { visibility = View.GONE }
        title = Ui.text(this, 22f, palette.onSurface, Fonts.display).apply { maxLines = 1 }
        subtitle = Ui.text(this, 13f, palette.onSurfaceVariant, Fonts.body).apply { maxLines = 1 }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(title)
            addView(subtitle)
        }
        row.addView(backButton)
        row.addView(closeButton)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(row, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM))
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP))

        actions = ActionPill(this, palette).apply {
            visibility = View.GONE
            setActions(listOf(
                ActionPill.Action(R.drawable.ic_share, getString(R.string.action_share)) {
                    val items = grid.selectedItems()
                    MediaActions.share(this@FolderActivity, items.map { MediaRepo.uriOf(it) }, items.map { it.mime })
                },
                ActionPill.Action(R.drawable.ic_delete, getString(R.string.action_move_to_bin), primary = true) {
                    val items = grid.selectedItems()
                    pendingItems = items
                    MediaActions.startRequest(this@FolderActivity, lumen.repo.trashRequest(items, true), MediaActions.REQ_TRASH)
                },
            ))
        }
        root.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        root.onSystemInsets { ins ->
            bottomInset = ins.bottom
            bar.setPadding(ins.left, 0, ins.right, 0)
            bar.layoutParams.height = ins.top + dp(64)
            bar.requestLayout()
            grid.setPadding(ins.left, 0, ins.right, 0)
            grid.setInsets(ins.top + dp(64), ins.bottom + dp(96))
            (actions.layoutParams as FrameLayout.LayoutParams).bottomMargin = ins.bottom + dp(16)
            actions.requestLayout()
        }
        updateSelectionUi()
    }

    private fun showData() {
        val lib = lumen.repo.library ?: return
        val items = lib.filter { it.bucketId == bucketId }
        grid.setItems(items)
        // An emptied folder stays open, so its "Moved to bin" Undo can still be used.
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val n = grid.selected.size
        val selecting = n > 0
        backButton.visibility = if (selecting) View.GONE else View.VISIBLE
        closeButton.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            title.text = resources.getQuantityString(R.plurals.selected_count, n, n)
            subtitle.visibility = View.GONE
        } else {
            title.text = folderName
            subtitle.visibility = View.VISIBLE
            val count = grid.items.size
            subtitle.text = resources.getQuantityString(R.plurals.items_count, count, count)
        }
        if (selecting && actions.visibility != View.VISIBLE) {
            actions.visibility = View.VISIBLE
            actions.alpha = 0f
            actions.translationY = dpf(24f)
            actions.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Motion.ENTER).start()
        } else if (!selecting && actions.visibility == View.VISIBLE) {
            actions.animate().alpha(0f).translationY(dpf(24f)).setDuration(150)
                .withEndAction { actions.visibility = View.GONE }.start()
        }
        back.enabled = selecting
    }

    @Deprecated("Android 12 back handling; Android 13+ uses BackHandler")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation") // only reached on Android 12; 13+ uses BackHandler
    override fun onBackPressed() {
        if (!back.handleLegacy()) super.onBackPressed()
    }

    @Deprecated("Framework callback")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val items = pendingItems
        val n = items.size
        when (requestCode) {
            MediaActions.REQ_TRASH -> {
                grid.clearSelection()
                if (n > 0) {
                    Snack.show(root, palette, resources.getQuantityString(R.plurals.moved_to_bin, n, n), bottomInset + dp(24),
                        getString(R.string.undo)) {
                        pendingItems = items
                        MediaActions.startRequest(this, lumen.repo.trashRequest(items, false), MediaActions.REQ_UNDO)
                    }
                }
            }
            MediaActions.REQ_UNDO -> if (n > 0) {
                Snack.show(root, palette, resources.getQuantityString(R.plurals.restored, n, n), bottomInset + dp(24))
            }
        }
        lumen.repo.reloadNow()
    }

    companion object {
        const val EXTRA_BUCKET = "bucket"
        const val EXTRA_NAME = "name"
    }
}
