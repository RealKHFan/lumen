package io.github.realkhfan.lumen

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.realkhfan.lumen.compat.BackHandler
import io.github.realkhfan.lumen.compat.Compat
import io.github.realkhfan.lumen.core.Folder
import io.github.realkhfan.lumen.core.FolderFilter
import io.github.realkhfan.lumen.core.Folders
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.data.MediaRepo
import io.github.realkhfan.lumen.data.ThumbLoader
import io.github.realkhfan.lumen.grid.PhotoGrid
import io.github.realkhfan.lumen.ui.ActionPill
import io.github.realkhfan.lumen.ui.BottomSheet
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Motion
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.PillNav
import io.github.realkhfan.lumen.ui.SideDrawer
import io.github.realkhfan.lumen.ui.Snack
import io.github.realkhfan.lumen.ui.Ui
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import io.github.realkhfan.lumen.ui.onSystemInsets
import io.github.realkhfan.lumen.ui.withAlpha
import io.github.realkhfan.lumen.viewer.ViewerActivity

/** Photos, Folders and Bin, with the floating navigation pill. */
class MainActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var back: BackHandler
    private val repo get() = lumen.repo
    private val prefs get() = lumen.prefs

    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var photosPage: FrameLayout
    private lateinit var foldersPage: FrameLayout
    private lateinit var binPage: FrameLayout
    private lateinit var photosGrid: PhotoGrid
    private lateinit var binGrid: PhotoGrid
    private lateinit var foldersGrid: GridView
    private lateinit var photosEmpty: FrameLayout
    private lateinit var foldersEmpty: View
    private lateinit var binEmpty: View
    private lateinit var partialCard: View
    private lateinit var binHint: View

    private lateinit var topBar: FrameLayout
    private lateinit var topBg: View
    private lateinit var closeButton: View
    private lateinit var title: TextView
    private lateinit var chip: LinearLayout
    private lateinit var chipText: TextView
    private lateinit var menuButton: View
    private lateinit var nav: PillNav
    private lateinit var actionPill: ActionPill
    private var gate: View? = null
    private val sheet by lazy { BottomSheet(this, palette) }
    private var drawerCreated = false
    private val drawer by lazy {
        drawerCreated = true
        buildDrawer()
    }

    private var tab = TAB_PHOTOS
    private var bottomInset = 0
    private val scrolled = BooleanArray(3)
    private val foldersAdapter = FoldersAdapter()
    private var pendingItems: List<MediaItem> = emptyList()
    private val onData: () -> Unit = { showData() }

    private var themeGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReport.pending(this) != null) {
            // Last time Lumen crashed: show the report first, so it can be copied and sent.
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        themeGeneration = lumen.themeGeneration
        palette = Palette(this)
        Ui.setupWindow(this, palette.isDark)
        tab = savedInstanceState?.getInt(STATE_TAB, TAB_PHOTOS) ?: TAB_PHOTOS
        back = BackHandler(this) { handleBack() }
        buildUi()
        setContentView(root)
        repo.addListener(onData)
        nav.select(tab, notify = false)
        showTab(tab)
    }

    override fun onResume() {
        super.onResume()
        if (!::root.isInitialized) return
        if (themeGeneration != lumen.themeGeneration) {
            recreate()
            return
        }
        checkAccess()
        photosGrid.refreshDates()
        binGrid.refreshDates()
        updateBinHint()
        partialCard.visibility = if (Compat.isPartialAccess(this)) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (::root.isInitialized) repo.removeListener(onData)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        foldersAdapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        pages = FrameLayout(this)

        photosGrid = PhotoGrid(this, palette, PhotoGrid.Kind.LIBRARY).apply {
            setColumns(prefs.columns)
            listener = gridListener(TAB_PHOTOS)
            setTopView(buildPartialBanner())
        }
        photosEmpty = FrameLayout(this).apply { visibility = View.GONE }
        photosPage = FrameLayout(this).apply {
            addView(photosGrid, FrameLayout.LayoutParams(-1, -1))
            addView(photosEmpty, FrameLayout.LayoutParams(-1, -1))
        }

        foldersGrid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(150)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(20)
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            adapter = foldersAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                openFolder(foldersAdapter.folders[position])
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                    val c = view.getChildAt(0)
                    onPageScrolled(TAB_FOLDERS, first > 0 || (c != null && c.top < view.paddingTop))
                }
            })
        }
        foldersEmpty = Ui.emptyState(this, palette, R.drawable.ic_folder,
            getString(R.string.photos_empty_title), getString(R.string.photos_empty_body)).apply { visibility = View.GONE }
        foldersPage = FrameLayout(this).apply {
            addView(foldersGrid, FrameLayout.LayoutParams(-1, -1))
            addView(foldersEmpty, FrameLayout.LayoutParams(-1, -1))
        }

        binGrid = PhotoGrid(this, palette, PhotoGrid.Kind.BIN).apply {
            setColumns(4)
            listener = gridListener(TAB_BIN)
            setTopView(buildBinBanner())
        }
        binEmpty = Ui.emptyState(this, palette, R.drawable.ic_delete,
            getString(R.string.bin_empty_title), getString(R.string.bin_empty_body)).apply { visibility = View.GONE }
        binPage = FrameLayout(this).apply {
            addView(binGrid, FrameLayout.LayoutParams(-1, -1))
            addView(binEmpty, FrameLayout.LayoutParams(-1, -1))
        }

        for (p in listOf(photosPage, foldersPage, binPage)) pages.addView(p, FrameLayout.LayoutParams(-1, -1))
        root.addView(pages, FrameLayout.LayoutParams(-1, -1))

        buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP))

        nav = PillNav(this, palette, listOf(
            PillNav.Tab(R.drawable.ic_photo_library, R.drawable.ic_photo_library_fill1, getString(R.string.tab_photos)),
            PillNav.Tab(R.drawable.ic_folder, R.drawable.ic_folder_fill1, getString(R.string.tab_folders)),
            PillNav.Tab(R.drawable.ic_delete, R.drawable.ic_delete_fill1, getString(R.string.tab_bin)),
        )) { i ->
            if (i == tab) currentScrollToTop() else showTab(i)
        }
        root.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        actionPill = ActionPill(this, palette).apply { visibility = View.GONE }
        root.addView(actionPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        root.onSystemInsets { ins ->
            bottomInset = ins.bottom
            val barH = dp(64)
            // The bar's background reaches under the status bar; its buttons sit below it.
            topBar.setPadding(ins.left, 0, ins.right, 0)
            topBar.layoutParams.height = ins.top + barH
            topBar.requestLayout()
            pages.setPadding(ins.left, 0, ins.right, 0)
            val gridTop = ins.top + barH
            val gridBottom = ins.bottom + dp(96)
            photosGrid.setInsets(gridTop, gridBottom)
            binGrid.setInsets(gridTop, gridBottom)
            foldersGrid.setPadding(dp(16), gridTop + dp(8), dp(16), gridBottom)
            for (v in listOf(nav, actionPill)) {
                (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = ins.bottom + dp(16)
                v.requestLayout()
            }
            gate?.setPadding(ins.left, ins.top, ins.right, ins.bottom)
        }
    }

    private fun buildTopBar() {
        topBar = FrameLayout(this)
        topBg = View(this).apply {
            setBackgroundColor(withAlpha(palette.surface, 0.96f))
            alpha = 0f
        }
        topBar.addView(topBg, FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        menuButton = Ui.iconButton(this, R.drawable.ic_menu, palette.onSurface, getString(R.string.action_menu)) {
            drawer.open()
            updateBack()
        }
        closeButton = Ui.iconButton(this, R.drawable.ic_close, palette.onSurface, getString(R.string.action_close)) {
            currentGrid()?.clearSelection()
        }.apply { visibility = View.GONE }
        title = Ui.text(this, 24f, palette.onSurface, Fonts.display).apply {
            setPadding(dp(4), 0, 0, 0)
            maxLines = 1
        }
        chipText = Ui.text(this, 14f, palette.onSurface, Fonts.medium).apply { maxLines = 1 }
        chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            minimumHeight = dp(36)
            addView(chipText, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
            addView(Ui.icon(this@MainActivity, R.drawable.ic_expand_more, palette.onSurface, 20))
            setOnClickListener { showFilterSheet() }
        }
        row.addView(menuButton)
        row.addView(closeButton)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(12) })
        topBar.addView(row, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM))
        updateChip()
    }

    /** The hamburger menu: the three sections, then Settings. New entries go here. */
    private fun buildDrawer(): SideDrawer = SideDrawer(this, palette).apply {
        addHeader(R.mipmap.ic_launcher, getString(R.string.app_name))
        addItem(TAB_PHOTOS, R.drawable.ic_photo_library, getString(R.string.tab_photos)) { goToTab(TAB_PHOTOS) }
        addItem(TAB_FOLDERS, R.drawable.ic_folder, getString(R.string.tab_folders)) { goToTab(TAB_FOLDERS) }
        addItem(TAB_BIN, R.drawable.ic_delete, getString(R.string.tab_bin)) { goToTab(TAB_BIN) }
        addDivider()
        addItem(ITEM_SETTINGS, R.drawable.ic_settings, getString(R.string.settings)) {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        setSelected(tab)
        onClosed = { updateBack() }
    }

    private fun goToTab(i: Int) {
        nav.select(i, notify = false)
        if (i != tab) showTab(i)
    }

    private fun card(content: View): FrameLayout = FrameLayout(this).apply {
        setPadding(dp(16), dp(4), dp(16), dp(12))
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }

    private fun buildPartialBanner(): View {
        partialCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(palette.secondaryContainer, dpf(20f))
            setPadding(dp(18), dp(6), dp(6), dp(6))
            addView(Ui.text(this@MainActivity, 14f, palette.onSecondaryContainer, Fonts.body, getString(R.string.partial_banner)),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.textButton(this@MainActivity, getString(R.string.partial_allow_all), palette.onSecondaryContainer) {
                requestPermissions(Compat.mediaPermissions(), REQ_PERMISSION)
            })
            visibility = View.GONE
        }
        return card(partialCard)
    }

    private fun buildBinBanner(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(palette.surfaceContainer, dpf(24f))
            setPadding(dp(20), dp(16), dp(8), dp(6))
        }
        content.addView(Ui.text(this, 14f, palette.onSurfaceVariant, Fonts.body, getString(R.string.bin_banner)).apply {
            setPadding(0, 0, dp(12), 0)
            setLineSpacing(0f, 1.2f)
        })
        binHint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@MainActivity, 14f, palette.onSurface, Fonts.body, getString(R.string.bin_skip_prompts)),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.textButton(this@MainActivity, getString(R.string.bin_turn_on), palette.primary) {
                MediaActions.openMediaManagementSettings(this@MainActivity)
            })
        }
        content.addView(binHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Ui.textButton(this@MainActivity, getString(R.string.action_empty_bin), palette.error) { emptyBin() })
        }
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))
        return card(content)
    }

    private fun updateBinHint() {
        binHint.visibility = if (MediaActions.canSkipPrompts(this)) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ access

    private fun checkAccess() {
        if (Compat.hasMediaAccess(this)) {
            gate?.let { root.removeView(it) }
            gate = null
            topBar.visibility = View.VISIBLE
            updateNavVisibility()
            repo.start()
            showData()
        } else {
            showGate()
        }
    }

    private fun showGate() {
        val denied = prefs.askedForAccess && Compat.mediaPermissions().none { shouldShowRequestPermissionRationale(it) }
        gate?.let { root.removeView(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), 0, dp(36), 0)
            addView(ImageView(this@MainActivity).apply { setImageResource(R.mipmap.ic_launcher) },
                LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(24) })
            addView(Ui.text(this@MainActivity, 30f, palette.onSurface, Fonts.display, getString(R.string.perm_title)).apply {
                gravity = Gravity.CENTER
            })
            addView(Ui.text(this@MainActivity, 16f, palette.onSurfaceVariant, Fonts.body,
                getString(if (denied) R.string.perm_denied_body else R.string.perm_body)).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(Ui.filledButton(this@MainActivity,
                getString(if (denied) R.string.perm_settings else R.string.perm_allow), palette.primary, palette.onPrimary) {
                if (denied) MediaActions.openAppSettings(this@MainActivity)
                else requestPermissions(Compat.mediaPermissions(), REQ_PERMISSION)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(32) })
        }
        val g = FrameLayout(this).apply {
            setBackgroundColor(palette.surface)
            isClickable = true
            addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        gate = g
        root.addView(g, FrameLayout.LayoutParams(-1, -1))
        topBar.visibility = View.GONE
        nav.visibility = View.GONE
        root.requestApplyInsets()
    }

    @Deprecated("Framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_PERMISSION) {
            prefs.askedForAccess = true
            checkAccess()
            repo.reloadNow()
        }
    }

    // ------------------------------------------------------------------ data

    private fun showData() {
        val lib = repo.library ?: return
        val filter = prefs.filter
        val shown = filter.apply(lib)
        photosGrid.setItems(shown)
        photosEmpty.removeAllViews()
        if (shown.isEmpty()) {
            val v = if (!filter.showsEverything && lib.isNotEmpty()) {
                Ui.emptyState(this, palette, R.drawable.ic_folder, getString(R.string.filter_empty_title),
                    getString(R.string.filter_empty_body), getString(R.string.filter_show_everything)) {
                    applyFilter(FolderFilter.EVERYTHING)
                }
            } else {
                Ui.emptyState(this, palette, R.drawable.ic_photo, getString(R.string.photos_empty_title),
                    getString(R.string.photos_empty_body))
            }
            photosEmpty.addView(v, FrameLayout.LayoutParams(-1, -1))
            photosEmpty.visibility = View.VISIBLE
        } else {
            photosEmpty.visibility = View.GONE
        }
        val folders = Folders.from(lib)
        foldersAdapter.set(folders)
        foldersEmpty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        val bin = repo.bin ?: emptyList()
        binGrid.setItems(bin)
        binEmpty.visibility = if (bin.isEmpty()) View.VISIBLE else View.GONE
        binGrid.visibility = if (bin.isEmpty()) View.INVISIBLE else View.VISIBLE
        updateChip()
    }

    private fun applyFilter(filter: FolderFilter) {
        prefs.filter = filter
        showData()
        photosGrid.scrollToTop()
    }

    private fun updateChip() {
        val lib = repo.library ?: emptyList()
        val filter = prefs.filter
        chipText.text = filter.label(Folders.from(lib), getString(R.string.all_folders)) { n ->
            resources.getQuantityString(R.plurals.n_folders, n, n)
        }
        val active = !filter.showsEverything
        chip.background = Ui.pressable(
            Ui.rounded(if (active) palette.secondaryContainer else palette.surfaceContainerHigh, dpf(18f)),
            withAlpha(palette.onSurface, 0.12f), dpf(18f),
        )
        val fg = if (active) palette.onSecondaryContainer else palette.onSurface
        chipText.setTextColor(fg)
        (chip.getChildAt(1) as ImageView).imageTintList = ColorStateList.valueOf(fg)
    }

    // ------------------------------------------------------------------ tabs and selection

    private fun showTab(i: Int) {
        val previous = currentGrid()
        tab = i
        if (previous != null && previous !== currentGrid()) previous.clearSelection()
        val target = when (i) {
            TAB_FOLDERS -> foldersPage
            TAB_BIN -> binPage
            else -> photosPage
        }
        for (p in listOf(photosPage, foldersPage, binPage)) {
            if (p === target) {
                if (p.visibility != View.VISIBLE) {
                    p.alpha = 0f
                    p.visibility = View.VISIBLE
                    p.animate().alpha(1f).setDuration(160).start()
                }
            } else {
                p.visibility = View.GONE
            }
        }
        topBg.animate().alpha(if (scrolled[i]) 1f else 0f).setDuration(120).start()
        if (drawerCreated) drawer.setSelected(i)
        updateSelectionUi()
    }

    private fun currentGrid(): PhotoGrid? = when (tab) {
        TAB_PHOTOS -> photosGrid
        TAB_BIN -> binGrid
        else -> null
    }

    private fun currentScrollToTop() {
        when (tab) {
            TAB_FOLDERS -> foldersGrid.smoothScrollToPositionFromTop(0, 0, 220)
            else -> currentGrid()?.scrollToTop()
        }
    }

    private fun onPageScrolled(page: Int, away: Boolean) {
        if (scrolled[page] == away) return
        scrolled[page] = away
        if (page == tab) topBg.animate().alpha(if (away) 1f else 0f).setDuration(150).start()
    }

    private fun gridListener(page: Int) = object : PhotoGrid.Listener {
        override fun onOpen(index: Int, item: MediaItem, bounds: Rect) = openViewer(page, item, bounds)
        override fun onSelectionChanged(selected: Set<Long>) {
            if (page == tab) updateSelectionUi()
        }
        override fun onColumnsChanged(columns: Int) {
            if (page == TAB_PHOTOS) prefs.columns = columns
        }
        override fun onScrolled(awayFromTop: Boolean) = onPageScrolled(page, awayFromTop)
    }

    private fun updateSelectionUi() {
        val grid = currentGrid()
        val n = grid?.selected?.size ?: 0
        val selecting = n > 0
        closeButton.visibility = if (selecting) View.VISIBLE else View.GONE
        menuButton.visibility = if (selecting) View.GONE else View.VISIBLE
        // The title always says where you are.
        title.text = when {
            selecting -> resources.getQuantityString(R.plurals.selected_count, n, n)
            tab == TAB_FOLDERS -> getString(R.string.tab_folders)
            tab == TAB_BIN -> getString(R.string.tab_bin)
            else -> getString(R.string.tab_photos)
        }
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (selecting) 22f else 24f)
        chip.visibility = if (!selecting && tab == TAB_PHOTOS) View.VISIBLE else View.GONE
        if (selecting) {
            actionPill.setActions(
                if (tab == TAB_BIN) listOf(
                    ActionPill.Action(R.drawable.ic_delete_forever, getString(R.string.action_delete_forever), danger = true) {
                        deleteForever(binGrid.selectedItems())
                    },
                    ActionPill.Action(R.drawable.ic_restore_from_trash, getString(R.string.action_restore), primary = true) {
                        restore(binGrid.selectedItems())
                    },
                ) else listOf(
                    ActionPill.Action(R.drawable.ic_share, getString(R.string.action_share)) {
                        val items = photosGrid.selectedItems()
                        MediaActions.share(this, items.map { MediaRepo.uriOf(it) }, items.map { it.mime })
                    },
                    ActionPill.Action(R.drawable.ic_delete, getString(R.string.action_move_to_bin), primary = true) {
                        moveToBin(photosGrid.selectedItems())
                    },
                ),
            )
        }
        swapPills(selecting)
        updateBack()
    }

    private fun swapPills(selecting: Boolean) {
        val show = if (selecting) actionPill else nav
        val hide = if (selecting) nav else actionPill
        if (gate != null) {
            nav.visibility = View.GONE
            actionPill.visibility = View.GONE
            return
        }
        if (show.visibility != View.VISIBLE) {
            show.visibility = View.VISIBLE
            show.alpha = 0f
            show.translationY = dpf(24f)
            show.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Motion.ENTER).start()
        }
        if (hide.visibility == View.VISIBLE) {
            hide.animate().alpha(0f).translationY(dpf(24f)).setDuration(150).setInterpolator(Motion.EXIT)
                .withEndAction { hide.visibility = View.GONE }.start()
        }
    }

    private fun updateNavVisibility() = swapPills(currentGrid()?.selectionMode == true)

    private fun updateBack() {
        back.enabled = (drawerCreated && drawer.isShowing) || sheet.isShowing ||
            currentGrid()?.selectionMode == true || tab != TAB_PHOTOS
    }

    private fun handleBack() {
        when {
            drawerCreated && drawer.isShowing -> drawer.close()
            sheet.isShowing -> sheet.dismiss()
            currentGrid()?.selectionMode == true -> currentGrid()?.clearSelection()
            tab != TAB_PHOTOS -> {
                nav.select(TAB_PHOTOS, notify = false)
                showTab(TAB_PHOTOS)
            }
        }
        updateBack()
    }

    @Deprecated("Android 12 back handling; Android 13+ uses BackHandler")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!back.handleLegacy()) super.onBackPressed()
    }

    // ------------------------------------------------------------------ actions

    private fun openViewer(page: Int, item: MediaItem, bounds: Rect) {
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_START_ID, item.id)
            .putExtra(ViewerActivity.EXTRA_FROM, bounds)
        if (page == TAB_BIN) {
            intent.putExtra(ViewerActivity.EXTRA_SOURCE, ViewerActivity.SOURCE_BIN)
        } else {
            intent.putExtra(ViewerActivity.EXTRA_SOURCE, ViewerActivity.SOURCE_LIBRARY)
                .putExtra(ViewerActivity.EXTRA_FILTER, prefs.filter.encode())
        }
        startActivity(intent)
    }

    private fun openFolder(folder: Folder) {
        startActivity(Intent(this, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_BUCKET, folder.bucketId)
            .putExtra(FolderActivity.EXTRA_NAME, folder.name))
    }

    private fun moveToBin(items: List<MediaItem>) {
        if (items.isEmpty()) return
        pendingItems = items
        if (!MediaActions.startRequest(this, repo.trashRequest(items, true), MediaActions.REQ_TRASH)) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restore(items: List<MediaItem>) {
        if (items.isEmpty()) return
        pendingItems = items
        MediaActions.startRequest(this, repo.trashRequest(items, false), MediaActions.REQ_RESTORE)
    }

    private fun deleteForever(items: List<MediaItem>) {
        if (items.isEmpty()) return
        pendingItems = items
        MediaActions.startRequest(this, repo.deleteRequest(items), MediaActions.REQ_DELETE)
    }

    private fun emptyBin() = deleteForever(repo.bin ?: emptyList())

    @Deprecated("Framework callback")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val items = pendingItems
        val n = items.size
        val snackBottom = bottomInset + dp(88)
        when (requestCode) {
            MediaActions.REQ_TRASH -> {
                photosGrid.clearSelection()
                if (n > 0) {
                    Snack.show(root, palette, resources.getQuantityString(R.plurals.moved_to_bin, n, n), snackBottom,
                        getString(R.string.undo)) {
                        pendingItems = items
                        MediaActions.startRequest(this, repo.trashRequest(items, false), MediaActions.REQ_UNDO)
                    }
                }
            }
            MediaActions.REQ_RESTORE, MediaActions.REQ_UNDO -> {
                binGrid.clearSelection()
                if (n > 0) Snack.show(root, palette, resources.getQuantityString(R.plurals.restored, n, n), snackBottom)
            }
            MediaActions.REQ_DELETE -> {
                binGrid.clearSelection()
                if (n > 0) Snack.show(root, palette, resources.getQuantityString(R.plurals.deleted_forever, n, n), snackBottom)
            }
        }
        repo.reloadNow()
    }

    // ------------------------------------------------------------------ folder filter sheet

    private fun showFilterSheet() {
        val lib = repo.library ?: return
        val folders = Folders.from(lib)
        var draft = prefs.filter
        val rowUpdaters = ArrayList<() -> Unit>()
        lateinit var apply: TextView

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.text(this, 24f, palette.onSurface, Fonts.display, getString(R.string.filter_title)).apply {
            setPadding(dp(24), 0, dp(24), dp(12))
        })

        fun row(icon: View, name: String, detail: String, isOn: () -> Boolean, onTap: () -> Unit): View {
            val check = ImageView(this)
            val update = {
                val on = isOn()
                check.setImageResource(if (on) R.drawable.ic_check_circle_fill1 else R.drawable.ic_radio_button_unchecked)
                check.imageTintList = ColorStateList.valueOf(if (on) palette.primary else palette.outline)
            }
            rowUpdaters.add(update)
            update()
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(24), dp(8), dp(20), dp(8))
                background = Ui.pressable(null, withAlpha(palette.onSurface, 0.1f), 0f)
                addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, dp(12), 0)
                    addView(Ui.text(this@MainActivity, 16f, palette.onSurface, Fonts.body, name).apply { maxLines = 1 })
                    addView(Ui.text(this@MainActivity, 13f, palette.onSurfaceVariant, Fonts.body, detail).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
                setOnClickListener {
                    onTap()
                    for (u in rowUpdaters) u()
                    apply.text = if (draft.showsEverything) getString(R.string.filter_show_everything)
                    else resources.getQuantityString(R.plurals.filter_apply, draft.bucketIds.size, draft.bucketIds.size)
                }
            }
        }

        val allIcon = FrameLayout(this).apply {
            background = Ui.rounded(palette.secondaryContainer, dpf(12f))
            addView(Ui.icon(this@MainActivity, R.drawable.ic_photo_library, palette.onSecondaryContainer),
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        content.addView(row(allIcon, getString(R.string.filter_everything), getString(R.string.filter_everything_hint),
            { draft.showsEverything }) { draft = FolderFilter.EVERYTHING })
        for (folder in folders) {
            val thumb = roundedThumb(folder.cover, dp(44), dpf(12f))
            content.addView(row(thumb, folder.name, resources.getQuantityString(R.plurals.items_count, folder.count, folder.count),
                { folder.bucketId in draft.bucketIds }) { draft = draft.toggle(folder.bucketId) })
        }

        apply = Ui.filledButton(this, "", palette.primary, palette.onPrimary) {
            applyFilter(draft)
            sheet.dismiss()
        }
        apply.text = if (draft.showsEverything) getString(R.string.filter_show_everything)
        else resources.getQuantityString(R.plurals.filter_apply, draft.bucketIds.size, draft.bucketIds.size)
        val footer = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(apply, FrameLayout.LayoutParams(-1, dp(52)))
        }
        sheet.onDismissed = { updateBack() }
        sheet.show(content, footer)
        updateBack()
    }

    private fun roundedThumb(item: MediaItem, sizePx: Int, radius: Float): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(palette.placeholder, radius)
        clipToOutline = true
        val req = lumen.thumbs.load(item, sizePx) { bmp -> if (bmp != null) setImageBitmap(bmp) }
        tag = req
    }

    // ------------------------------------------------------------------ folders tab

    private inner class FoldersAdapter : BaseAdapter() {
        var folders: List<Folder> = emptyList()
            private set

        fun set(list: List<Folder>) {
            folders = list
            notifyDataSetChanged()
        }

        override fun getCount() = folders.size
        override fun getItem(position: Int): Any = folders[position]
        override fun getItemId(position: Int) = folders[position].bucketId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card = convertView as? FolderCard ?: FolderCard()
            card.bind(folders[position])
            return card
        }
    }

    private inner class FolderCard : LinearLayout(this@MainActivity) {
        private val image = SquareImage()
        private val name = Ui.text(context, 16f, palette.onSurface, Fonts.display).apply { maxLines = 1 }
        private val count = Ui.text(context, 13f, palette.onSurfaceVariant, Fonts.body).apply { maxLines = 1 }
        private var request: ThumbLoader.Request? = null
        private var boundId = Long.MIN_VALUE

        init {
            orientation = VERTICAL
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.background = Ui.rounded(palette.placeholder, dpf(24f))
            image.clipToOutline = true
            image.foreground = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(withAlpha(Color.WHITE, 0.25f)), null, Ui.rounded(Color.WHITE, dpf(24f)),
            )
            addView(image, LayoutParams(-1, -2))
            addView(name, LayoutParams(-1, -2).apply { topMargin = dp(10); marginStart = dp(4) })
            addView(count, LayoutParams(-1, -2).apply { topMargin = dp(2); marginStart = dp(4) })
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }

        fun bind(folder: Folder) {
            name.text = folder.name
            count.text = resources.getQuantityString(R.plurals.items_count, folder.count, folder.count)
            contentDescription = folder.name
            if (boundId == folder.cover.id) return
            boundId = folder.cover.id
            request?.cancel()
            image.setImageDrawable(null)
            val coverId = folder.cover.id
            request = lumen.thumbs.load(folder.cover, dp(200)) { bmp ->
                if (boundId == coverId && bmp != null) image.setImageBitmap(bmp)
            }
        }
    }

    private inner class SquareImage : ImageView(this@MainActivity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
            setMeasuredDimension(measuredWidth, measuredWidth)
        }
    }

    companion object {
        private const val TAB_PHOTOS = 0
        private const val TAB_FOLDERS = 1
        private const val TAB_BIN = 2
        private const val ITEM_SETTINGS = 10
        private const val REQ_PERMISSION = 1
        private const val STATE_TAB = "tab"
    }
}
