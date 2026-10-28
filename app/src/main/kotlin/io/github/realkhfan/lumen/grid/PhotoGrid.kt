package io.github.realkhfan.lumen.grid

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.realkhfan.lumen.R
import io.github.realkhfan.lumen.core.Bin
import io.github.realkhfan.lumen.core.DateLabels
import io.github.realkhfan.lumen.core.DragSelection
import io.github.realkhfan.lumen.core.Format
import io.github.realkhfan.lumen.core.GridRow
import io.github.realkhfan.lumen.core.GridZoom
import io.github.realkhfan.lumen.core.Grouping
import io.github.realkhfan.lumen.core.MediaItem
import io.github.realkhfan.lumen.core.Selection
import io.github.realkhfan.lumen.core.Timeline
import io.github.realkhfan.lumen.data.ThumbLoader
import io.github.realkhfan.lumen.lumen
import io.github.realkhfan.lumen.ui.Fonts
import io.github.realkhfan.lumen.ui.Motion
import io.github.realkhfan.lumen.ui.Palette
import io.github.realkhfan.lumen.ui.dp
import io.github.realkhfan.lumen.ui.dpf
import io.github.realkhfan.lumen.ui.withAlpha
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The photo grid used by Photos, a single folder and the Bin. A ListView of rows (date headers
 * and rows of thumbnails) so only what's on screen exists as views, even for 50,000 photos.
 *
 * Gestures: tap opens, long-press selects (keep the finger down and drag to select a range),
 * pinch changes the thumbnail size, and the handle on the right edge scrubs through dates.
 */
@SuppressLint("ViewConstructor")
class PhotoGrid(context: Context, private val palette: Palette, val kind: Kind) : FrameLayout(context) {
    enum class Kind { LIBRARY, FOLDER, BIN }

    interface Listener {
        fun onOpen(index: Int, item: MediaItem, bounds: Rect)
        fun onSelectionChanged(selected: Set<Long>)
        fun onColumnsChanged(columns: Int) {}
        fun onScrolled(awayFromTop: Boolean) {}
    }

    var listener: Listener? = null

    var items: List<MediaItem> = emptyList()
        private set
    private var rows: List<GridRow> = emptyList()

    var columns: Int = GridZoom.DEFAULT
        private set

    var selected: Set<Long> = emptySet()
        private set
    val selectionMode: Boolean get() = selected.isNotEmpty()

    private val thumbs: ThumbLoader = context.lumen.thumbs
    private val gap = max(2, (resources.displayMetrics.density * 1.5f).roundToInt())
    private var cellF = 1f
    private var topInset = 0
    private var bottomInset = 0
    private var labels = makeLabels()
    private var labelsDay = LocalDate.now()
    private var drag: DragSelection? = null

    val list = GridList()
    private val adapter = GridAdapter()
    private val scroller = FastScroller()

    init {
        addView(list, LayoutParams(-1, -1))
        addView(scroller, LayoutParams(-1, -1))
        list.adapter = adapter
        // Set here, after every field exists: ListView calls the listener as soon as it's set.
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                scroller.userScrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (scroller.userScrolling) scroller.onListScroll()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                scroller.onListScroll()
                listener?.onScrolled(!isAtTop())
            }
        })
    }

    private fun isAtTop(): Boolean {
        if (list.firstVisiblePosition > 0) return false
        val child = list.getChildAt(0) ?: return true
        return child.top >= list.paddingTop
    }

    // ---------------------------------------------------------------- public API

    fun setItems(newItems: List<MediaItem>) {
        // Keep what's on screen in place, unless you're at the top: then new photos appear there.
        val anchor = firstVisibleItem()
        val anchorId = if (isAtTop()) null else items.getOrNull(anchor.first)?.id
        items = newItems
        if (selected.isNotEmpty()) {
            val ids = newItems.mapTo(HashSet()) { it.id }
            val kept = selected.filterTo(LinkedHashSet()) { it in ids }
            if (kept.size != selected.size) {
                selected = kept
                listener?.onSelectionChanged(selected)
            }
        }
        rebuild()
        if (anchorId != null) {
            val newIndex = newItems.indexOfFirst { it.id == anchorId }
            if (newIndex >= 0) restoreAnchor(newIndex, anchor.second)
        }
    }

    fun setColumns(n: Int) {
        if (n == columns) return
        columns = n
        rebuild()
    }

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        list.setPadding(0, top, 0, bottom)
        scroller.invalidate()
    }

    /** A view that scrolls with the grid above the first row (the bin's notice). */
    fun setTopView(view: View) {
        view.layoutParams = AbsListView.LayoutParams(-1, -2)
        list.addHeaderView(view, null, false)
    }

    fun clearSelection() = updateSelection(emptySet())

    fun selectedItems(): List<MediaItem> = items.filter { it.id in selected }

    fun scrollToTop() {
        if (list.firstVisiblePosition > 30) list.setSelection(0) else list.smoothScrollToPositionFromTop(0, 0, 220)
    }

    /** Call when the screen comes back: "Today" may have become "Yesterday". */
    fun refreshDates() {
        if (LocalDate.now() != labelsDay) {
            labels = makeLabels()
            rebuild()
        }
    }

    // ---------------------------------------------------------------- rows

    private fun grouping(): Grouping = if (kind == Kind.BIN) Grouping.NONE else GridZoom.grouping(columns)

    private fun rebuild() {
        labelsDay = LocalDate.now()
        rows = Timeline.rows(items, columns, grouping(), labels, labelsDay)
        computeCell(width)
        adapter.notifyDataSetChanged()
    }

    private fun makeLabels(): DateLabels {
        val locale = Locale.getDefault()
        fun best(skeleton: String, fallback: String) = try {
            DateFormat.getBestDateTimePattern(locale, skeleton)
        } catch (e: RuntimeException) {
            fallback
        }
        return try {
            DateLabels(
                today = context.getString(R.string.today),
                yesterday = context.getString(R.string.yesterday),
                locale = locale, zone = ZoneId.systemDefault(),
                dayPattern = best("EEEdMMM", "EEE d MMM"),
                dayYearPattern = best("EEEdMMMy", "EEE d MMM yyyy"),
                monthPattern = best("MMMM", "MMMM"),
                monthYearPattern = best("MMMMy", "MMMM yyyy"),
                sectionPattern = best("MMMy", "MMM yyyy"),
            )
        } catch (e: IllegalArgumentException) {
            DateLabels(
                context.getString(R.string.today), context.getString(R.string.yesterday), locale,
                ZoneId.systemDefault(), "EEE d MMM", "EEE d MMM yyyy", "MMMM", "MMMM yyyy", "MMM yyyy",
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            computeCell(w)
            post { adapter.notifyDataSetChanged() }
        }
    }

    private fun computeCell(width: Int) {
        if (width <= 0) return
        cellF = (width - gap * (columns - 1f)) / columns
    }

    /** (index of the first item on screen, its row's offset from the top of the list). */
    private fun firstVisibleItem(): Pair<Int, Int> {
        for (c in 0 until list.childCount) {
            val v = list.getChildAt(c)
            if (v is RowView && v.bottom > list.paddingTop) {
                val row = v.row ?: continue
                return row.first to v.top
            }
        }
        return 0 to list.paddingTop
    }

    private fun restoreAnchor(itemIndex: Int, top: Int) {
        val row = Timeline.rowOf(rows, itemIndex)
        if (row >= 0) list.setSelectionFromTop(row + list.headerViewsCount, top)
    }

    private fun changeColumns(n: Int, focusX: Float, focusY: Float) {
        var anchor = indexAt(focusX, focusY)
        var top = list.paddingTop
        if (anchor >= 0) {
            for (c in 0 until list.childCount) {
                val v = list.getChildAt(c)
                if (v is RowView && focusY >= v.top && focusY < v.bottom) top = v.top
            }
        } else {
            val first = firstVisibleItem()
            anchor = first.first
            top = first.second
        }
        columns = n
        rebuild()
        restoreAnchor(anchor, top)
        list.alpha = 0.55f
        list.animate().alpha(1f).setDuration(180).setInterpolator(Motion.STANDARD).start()
        list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        listener?.onColumnsChanged(n)
    }

    private fun indexAt(x: Float, y: Float): Int {
        var firstRow: RowView? = null
        var lastRow: RowView? = null
        for (c in 0 until list.childCount) {
            val v = list.getChildAt(c) as? RowView ?: continue
            if (v.row == null) continue
            if (y >= v.top && y < v.bottom) return v.indexAt(x)
            if (firstRow == null) firstRow = v
            lastRow = v
        }
        // Above the first row or below the last one (while auto-scrolling): use the nearest row.
        if (firstRow != null && y < firstRow.top) return firstRow.indexAt(x)
        if (lastRow != null && y >= lastRow.bottom) return lastRow.indexAt(x)
        return -1
    }

    // ---------------------------------------------------------------- selection

    private fun updateSelection(next: Set<Long>) {
        if (next == selected) return
        val wasSelecting = selectionMode
        selected = next
        for (c in 0 until list.childCount) {
            when (val v = list.getChildAt(c)) {
                is RowView -> v.refreshSelection(animate = true)
                is HeaderView -> v.refreshSelection()
            }
        }
        if (wasSelecting != selectionMode) scroller.invalidate()
        listener?.onSelectionChanged(selected)
    }

    private fun onCellClick(cell: ThumbView) {
        val item = cell.item ?: return
        if (selectionMode) {
            updateSelection(if (item.id in selected) selected - item.id else selected + item.id)
        } else {
            listener?.onOpen(cell.index, item, cell.screenBounds())
        }
    }

    private fun onCellLongPress(cell: ThumbView) {
        val item = cell.item ?: return
        val adding = item.id !in selected
        val d = DragSelection(selected, cell.index, adding)
        drag = d
        updateSelection(d.at(cell.index, items))
        // A long-press from TalkBack or a keyboard has no finger to lift, so drag-select would never end.
        if (list.fingerDown) list.startDragSelect() else drag = null
    }

    private fun dragTo(x: Float, y: Float) {
        val d = drag ?: return
        val i = indexAt(x, y)
        if (i >= 0) {
            val next = d.at(i, items)
            if (next != selected) {
                list.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                updateSelection(next)
            }
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is GridRow.Header) 0 else 1
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            when (val row = rows[position]) {
                is GridRow.Header -> (convertView as? HeaderView ?: HeaderView()).also { it.bind(row) }
                is GridRow.Cells -> (convertView as? RowView ?: RowView()).also { it.bind(row) }
            }
    }

    // ---------------------------------------------------------------- list with gestures

    inner class GridList : ListView(context) {
        private var pinching = false
        private var pinchFactor = 1f
        private var dragging = false
        var fingerDown = false
            private set
        private var lastX = 0f
        private var lastY = 0f

        private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchFactor = 1f
                return !dragging
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchFactor *= detector.scaleFactor
                val next = GridZoom.next(columns, pinchFactor)
                if (next != columns) {
                    changeColumns(next, detector.focusX, detector.focusY)
                    pinchFactor = 1f
                }
                return true
            }
        })

        private val autoScroll = object : Runnable {
            override fun run() {
                if (!dragging) return
                val edge = dpf(80f)
                val top = paddingTop + edge
                val bottom = height - paddingBottom - edge
                val speed = dpf(22f)
                val dy = when {
                    lastY < top -> -(((top - lastY) / edge).coerceAtMost(1f) * speed).toInt().coerceAtLeast(2)
                    lastY > bottom -> (((lastY - bottom) / edge).coerceAtMost(1f) * speed).toInt().coerceAtLeast(2)
                    else -> 0
                }
                if (dy != 0 && canScrollList(if (dy > 0) 1 else -1)) {
                    scrollListBy(dy)
                    dragTo(lastX, lastY)
                }
                postOnAnimation(this)
            }
        }

        init {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFocusable = false
            setRecyclerListener { view -> (view as? RowView)?.recycle() }
        }

        fun startDragSelect() {
            dragging = true
            removeCallbacks(autoScroll)
            postOnAnimation(autoScroll)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            fingerDown = ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                // Remember the finger even before a drag starts, so auto-scroll knows where it is.
                lastX = ev.x
                lastY = ev.y
            }
            scale.onTouchEvent(ev)
            if (!pinching && scale.isInProgress && ev.pointerCount > 1) {
                pinching = true
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            if (pinching) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pinching = false
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
            if (dragging) true else super.onInterceptTouchEvent(ev)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastX = ev.x
                    lastY = ev.y
                    dragTo(ev.x, ev.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    drag = null
                    removeCallbacks(autoScroll)
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- rows and cells

    private inner class HeaderView : LinearLayout(context) {
        private var row: GridRow.Header? = null
        private val label = TextView(context).apply {
            setTextColor(palette.onSurface)
            typeface = Fonts.display
            maxLines = 1
        }
        private val check = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.action_select_day)
            setOnClickListener {
                val r = row ?: return@setOnClickListener
                updateSelection(Selection.toggleRange(selected, items, r.first, r.count))
            }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            addView(label, LayoutParams(0, -2, 1f))
            addView(check, LayoutParams(dp(48), dp(48)))
        }

        fun bind(r: GridRow.Header) {
            row = r
            label.text = r.label
            val month = grouping() == Grouping.MONTH
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (month) 20f else 16f)
            layoutParams = AbsListView.LayoutParams(-1, dp(if (month) 60 else 52))
            refreshSelection()
        }

        fun refreshSelection() {
            val r = row ?: return
            check.visibility = if (selectionMode) View.VISIBLE else View.INVISIBLE
            val all = Selection.isRangeSelected(selected, items, r.first, r.count)
            check.setImageResource(if (all) R.drawable.ic_check_circle_fill1 else R.drawable.ic_radio_button_unchecked)
            check.imageTintList = android.content.res.ColorStateList.valueOf(if (all) palette.primary else palette.onSurfaceVariant)
        }
    }

    private inner class RowView : ViewGroup(context) {
        var row: GridRow.Cells? = null

        fun bind(r: GridRow.Cells) {
            row = r
            while (childCount < columns) addView(ThumbView())
            while (childCount > columns) {
                (getChildAt(childCount - 1) as ThumbView).unbind()
                removeViewAt(childCount - 1)
            }
            for (i in 0 until columns) {
                val cell = getChildAt(i) as ThumbView
                if (i < r.count) {
                    cell.visibility = View.VISIBLE
                    cell.bind(r.first + i)
                } else {
                    cell.visibility = View.INVISIBLE
                    cell.unbind()
                }
            }
            val h = cellF.roundToInt() + gap
            val lp = layoutParams
            if (lp == null || lp.height != h) layoutParams = AbsListView.LayoutParams(-1, h)
            requestLayout()
        }

        fun recycle() {
            for (i in 0 until childCount) (getChildAt(i) as ThumbView).unbind()
            row = null
        }

        fun refreshSelection(animate: Boolean) {
            for (i in 0 until childCount) (getChildAt(i) as ThumbView).refreshSelection(animate)
        }

        fun indexAt(x: Float): Int {
            val r = row ?: return -1
            val col = (x / (cellF + gap)).toInt().coerceIn(0, r.count - 1)
            return r.first + col
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cell = cellF.roundToInt()
            for (i in 0 until childCount) {
                val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
                getChildAt(i).measure(spec, spec)
            }
            setMeasuredDimension(w, cell + gap)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cell = cellF.roundToInt()
            for (i in 0 until childCount) {
                val left = (i * (cellF + gap)).roundToInt()
                val right = if (i == columns - 1) r - l else (i * (cellF + gap) + cellF).roundToInt()
                getChildAt(i).layout(left, 0, right, cell)
            }
        }
    }

    private inner class ThumbView : View(context) {
        var index = -1
            private set
        var item: MediaItem? = null
            private set
        private var bitmap: Bitmap? = null
        private var request: ThumbLoader.Request? = null
        private var fade = 1f
        private var selectedness = 0f
        private var selectMode = 0f
        private var animator: ValueAnimator? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Fonts.medium
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            setShadowLayer(dpf(3f), 0f, dpf(0.5f), 0x99000000.toInt())
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2f)
            color = Color.WHITE
            setShadowLayer(dpf(2f), 0f, 0f, 0x66000000)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
        private val src = Rect()
        private val dst = RectF()
        private val clip = Path()
        private val play: Drawable = context.getDrawable(R.drawable.ic_play_arrow_fill1)!!.mutate().apply { setTint(Color.WHITE) }
        private val tick: Drawable = context.getDrawable(R.drawable.ic_check)!!.mutate().apply { setTint(palette.onPrimary) }

        init {
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE),
            )
            setOnClickListener { onCellClick(this) }
            setOnLongClickListener {
                onCellLongPress(this)
                true
            }
        }

        fun bind(i: Int) {
            val next = items[i]
            val same = item?.id == next.id && item?.dateModifiedSec == next.dateModifiedSec
            index = i
            if (!same) {
                request?.cancel()
                item = next
                val px = cellF.roundToInt()
                bitmap = thumbs.cached(next, px)
                fade = 1f
                if (bitmap == null) {
                    request = thumbs.load(next, px) { bmp ->
                        if (item?.id == next.id) {
                            bitmap = bmp
                            fade = 0f
                            ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 140
                                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                                start()
                            }
                        }
                    }
                }
                contentDescription = context.getString(
                    if (next.isVideo) R.string.video_desc else R.string.photo_desc,
                    DateUtils.formatDateTime(context, next.timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                )
            }
            refreshSelection(animate = same)
            invalidate()
        }

        fun unbind() {
            request?.cancel()
            request = null
            animator?.cancel()
            item = null
            bitmap = null
        }

        fun refreshSelection(animate: Boolean) {
            val it = item ?: return
            val targetSel = if (it.id in selected) 1f else 0f
            val targetMode = if (selectionMode) 1f else 0f
            animator?.cancel()
            isSelected = targetSel == 1f
            if (!animate || !isAttachedToWindow) {
                selectedness = targetSel
                selectMode = targetMode
                invalidate()
                return
            }
            if (selectedness == targetSel && selectMode == targetMode) return
            val fromSel = selectedness
            val fromMode = selectMode
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = Motion.STANDARD
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    selectedness = fromSel + (targetSel - fromSel) * f
                    selectMode = fromMode + (targetMode - fromMode) * f
                    invalidate()
                }
                start()
            }
        }

        fun screenBounds(): Rect {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            return Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val current = item ?: return
            val inset = selectedness * dpf(12f)
            dst.set(inset, inset, w - inset, h - inset)
            val radius = selectedness * dpf(16f)
            val save = canvas.save()
            if (radius > 0.5f) {
                clip.reset()
                clip.addRoundRect(dst, radius, radius, Path.Direction.CW)
                canvas.clipPath(clip)
            }
            paint.alpha = 255
            paint.color = palette.placeholder
            canvas.drawRect(dst, paint)
            val bmp = bitmap
            if (bmp != null && !bmp.isRecycled) {
                // Centre-crop the thumbnail into the square cell.
                val bw = bmp.width
                val bh = bmp.height
                val scale = max(dst.width() / bw, dst.height() / bh)
                val sw = (dst.width() / scale).toInt()
                val sh = (dst.height() / scale).toInt()
                src.set((bw - sw) / 2, (bh - sh) / 2, (bw + sw) / 2, (bh + sh) / 2)
                paint.alpha = (fade * 255).toInt()
                canvas.drawBitmap(bmp, src, dst, paint)
            }
            canvas.restoreToCount(save)

            val pad = dpf(6f) + inset
            if (current.isVideo) {
                val text = Format.duration(current.durationMs)
                val tw = badge.measureText(text)
                val baseline = h - pad - dpf(1f)
                canvas.drawText(text, w - pad - tw, baseline, badge)
                val s = dp(18)
                val top = (baseline - badge.textSize * 0.82f - (s - badge.textSize) / 2).toInt()
                play.setBounds((w - pad - tw - s).toInt(), top, (w - pad - tw).toInt(), top + s)
                play.draw(canvas)
            }
            if (kind == Kind.BIN && current.dateExpiresSec > 0) {
                val days = Bin.daysLeft(current.dateExpiresSec, System.currentTimeMillis())
                val text = if (days <= 0) context.getString(R.string.days_left_today)
                else resources.getQuantityString(R.plurals.days_left, days, days)
                canvas.drawText(text, pad, h - pad - dpf(1f), badge)
            }
            if (selectMode > 0.01f) {
                val r = dpf(11f)
                val cx = dpf(8f) + r + inset * 0.4f
                val cy = cx
                if (selectedness > 0.5f) {
                    dot.alpha = (255 * selectMode).toInt()
                    canvas.drawCircle(cx, cy, r, dot)
                    val s = (r * 1.4f).toInt()
                    tick.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                    tick.alpha = (255 * selectMode).toInt()
                    tick.draw(canvas)
                } else {
                    ring.alpha = (255 * selectMode).toInt()
                    canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)
                }
            }
        }
    }

    // ---------------------------------------------------------------- fast scroller

    /** Handle on the right edge. Drag it to jump through time; a bubble shows the month. */
    private inner class FastScroller : View(context) {
        private val thumbW = dpf(6f)
        private val thumbH = dpf(52f)
        private var shown = 0f
        private var dragging = false
        private var fraction = 0f
        private var label = ""
        private var anim: ValueAnimator? = null
        private val hide = Runnable { animateShown(0f) }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
        private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.onPrimary
            typeface = Fonts.display
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        }
        private val rect = RectF()
        private val path = Path()

        /** Only show the handle while you scroll, not when the list moves on its own. */
        var userScrolling = false

        private fun scrollable(): Boolean =
            list.childCount > 0 && list.count > list.childCount * 3

        private fun trackTop() = topInset + dpf(8f)
        private fun trackBottom() = height - bottomInset - dpf(8f) - thumbH
        private fun thumbY() = trackTop() + fraction * max(0f, trackBottom() - trackTop())

        fun onListScroll() {
            if (!scrollable()) {
                if (shown > 0f) animateShown(0f)
                return
            }
            if (!dragging && !userScrolling && shown == 0f) return
            if (!dragging) {
                val first = list.firstVisiblePosition
                val v0 = list.getChildAt(0)
                val partial = if (v0 != null && v0.height > 0) (list.paddingTop - v0.top).toFloat() / v0.height else 0f
                val maxPos = max(1, list.count - list.childCount)
                fraction = ((first + partial) / maxPos).coerceIn(0f, 1f)
                removeCallbacks(hide)
                postDelayed(hide, 1500)
            }
            if (shown < 1f) animateShown(1f)
            invalidate()
        }

        private var animTarget = 0f

        private fun animateShown(target: Float) {
            // Scrolling calls this every frame: restarting the fade each time kept the handle
            // invisible while you scrolled. Only start a fade when the target actually changes.
            val running = anim?.isRunning == true
            if ((running && animTarget == target) || (!running && shown == target)) return
            anim?.cancel()
            animTarget = target
            anim = ValueAnimator.ofFloat(shown, target).apply {
                duration = if (target > shown) 120 else 260
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (shown <= 0.01f) return
            val alpha = (255 * shown).toInt()
            val w = if (dragging) dpf(8f) else thumbW
            val x = width - dpf(6f) - w
            val y = thumbY()
            thumbPaint.alpha = alpha
            rect.set(x, y, x + w, y + thumbH)
            canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint)
            if (dragging && label.isNotEmpty()) {
                val tw = textPaint.measureText(label)
                val bh = dpf(40f)
                val right = x - dpf(10f)
                val left = right - tw - dpf(32f)
                val cy = (y + thumbH / 2).coerceIn(topInset + bh / 2, height - bottomInset - bh / 2)
                rect.set(left, cy - bh / 2, right, cy + bh / 2)
                val r = bh / 2
                val small = dpf(4f)
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, small, small, r, r), Path.Direction.CW)
                bubblePaint.alpha = alpha
                canvas.drawPath(path, bubblePaint)
                textPaint.alpha = alpha
                val fm = textPaint.fontMetrics
                canvas.drawText(label, left + dpf(16f), cy - (fm.ascent + fm.descent) / 2, textPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val y = thumbY()
                    val hit = shown > 0.5f && event.x >= width - dp(48) &&
                        event.y >= y - dp(20) && event.y <= y + thumbH + dp(20)
                    if (!hit) return false
                    dragging = true
                    removeCallbacks(hide)
                    parent.requestDisallowInterceptTouchEvent(true)
                    scrubTo(event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (dragging) scrubTo(event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    postDelayed(hide, 1200)
                    invalidate()
                }
            }
            return dragging
        }

        private fun scrubTo(y: Float) {
            val range = max(1f, trackBottom() - trackTop())
            fraction = ((y - thumbH / 2 - trackTop()) / range).coerceIn(0f, 1f)
            val headers = list.headerViewsCount
            val pos = (fraction * (list.count - 1)).roundToInt()
            list.setSelectionFromTop(pos, list.paddingTop)
            val row = rows.getOrNull((pos - headers).coerceIn(0, max(0, rows.size - 1)))
            val next = row?.section ?: ""
            if (next != label) {
                label = next
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            invalidate()
        }
    }
}
