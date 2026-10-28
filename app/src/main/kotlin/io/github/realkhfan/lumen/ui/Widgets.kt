package io.github.realkhfan.lumen.ui

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Floating pill navigation. The active tab shows its label; the others are icons only. */
@SuppressLint("ViewConstructor")
class PillNav(
    context: Context,
    private val palette: Palette,
    private val tabs: List<Tab>,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {
    class Tab(val icon: Int, val iconSelected: Int, val label: String)

    var selected = -1
        private set
    private val views = ArrayList<LinearLayout>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = Ui.rounded(palette.surfaceContainerHigh, dpf(32f))
        elevation = dpf(6f)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(220)
        }
        tabs.forEachIndexed { i, tab ->
            val item = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = dp(56)
                minimumHeight = dp(48)
                setPadding(dp(16), 0, dp(16), 0)
                contentDescription = tab.label
                tooltipText = tab.label
                layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
                addView(Ui.icon(context, tab.icon, palette.onSurfaceVariant))
                addView(Ui.text(context, 14f, palette.onSecondaryContainer, Fonts.medium, tab.label).apply {
                    setPadding(dp(8), 0, 0, 0)
                    visibility = View.GONE
                })
                setOnClickListener { select(i, notify = true) }
            }
            views.add(item)
            addView(item, LayoutParams(-2, dp(48)).apply { if (i > 0) marginStart = dp(2) })
        }
    }

    fun select(index: Int, notify: Boolean) {
        if (index == selected) {
            if (notify) onSelect(index)
            return
        }
        selected = index
        views.forEachIndexed { i, v ->
            val on = i == index
            val icon = v.getChildAt(0) as ImageView
            val label = v.getChildAt(1) as TextView
            icon.setImageResource(if (on) tabs[i].iconSelected else tabs[i].icon)
            icon.imageTintList = ColorStateList.valueOf(if (on) palette.onSecondaryContainer else palette.onSurfaceVariant)
            label.visibility = if (on) View.VISIBLE else View.GONE
            v.isSelected = on
            v.background = Ui.pressable(
                if (on) Ui.rounded(palette.secondaryContainer, dpf(24f)) else null,
                withAlpha(palette.onSurface, 0.12f), dpf(24f),
            )
        }
        if (notify) onSelect(index)
    }

    // ---- Swipe across the pill: slide left for the next tab, right for the previous one.

    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val step = dpf(56f)
    private var downX = 0f
    private var downY = 0f
    private var startTab = 0
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startTab = selected.coerceAtLeast(0)
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!swiping && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(ev.y - downY)) {
                    // Taking over cancels the tap on the item under the finger.
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return swiping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startTab = selected.coerceAtLeast(0)
                swiping = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                if (!swiping && kotlin.math.abs(dx) > slop) swiping = true
                if (swiping) {
                    // Every 56dp of finger travel moves one tab; finger left means the next tab.
                    val moved = ((downX - ev.x) / step).toInt()
                    val target = (startTab + moved).coerceIn(0, tabs.size - 1)
                    if (target != selected) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        select(target, notify = true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}

/** The pill shown instead of the navigation while photos are selected. */
@SuppressLint("ViewConstructor")
class ActionPill(context: Context, private val palette: Palette) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = Ui.rounded(palette.surfaceContainerHigh, dpf(32f))
        elevation = dpf(6f)
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    fun setActions(actions: List<Action>) {
        removeAllViews()
        actions.forEachIndexed { i, a ->
            val fg = if (a.danger) palette.error else palette.onSurface
            val view = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(18), 0)
                minimumHeight = dp(48)
                background = Ui.pressable(
                    if (a.primary) Ui.rounded(palette.secondaryContainer, dpf(24f)) else null,
                    withAlpha(fg, 0.14f), dpf(24f),
                )
                val tint = if (a.primary) palette.onSecondaryContainer else fg
                addView(Ui.icon(context, a.icon, tint, 22))
                addView(Ui.text(context, 14f, tint, Fonts.medium, a.label).apply { setPadding(dp(8), 0, 0, 0) })
                setOnClickListener { a.run() }
            }
            addView(view, LayoutParams(-2, dp(48)).apply { if (i > 0) marginStart = dp(4) })
        }
    }

    class Action(val icon: Int, val label: String, val primary: Boolean = false, val danger: Boolean = false, val run: () -> Unit)
}

/** ScrollView that never grows taller than [maxHeight]. */
class CappedScroll(context: Context) : ScrollView(context) {
    var maxHeight = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(size, maxHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * A bottom sheet drawn over the current screen: tap outside, swipe it down or press back to close.
 */
class BottomSheet(private val activity: Activity, private val palette: Palette) {
    private val root: ViewGroup = activity.findViewById(android.R.id.content)
    private val overlay = FrameLayout(activity)
    private val scrim = View(activity)
    private val sheet = LinearLayout(activity)
    private val scroll = CappedScroll(activity)
    var isShowing = false
        private set
    var onDismissed: (() -> Unit)? = null

    init {
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { dismiss() }
        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = GradientDrawable().apply {
            setColor(palette.surfaceContainer)
            val r = activity.dpf(28f)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.isClickable = true
        val handle = FrameLayout(activity).apply {
            addView(View(activity).apply { background = Ui.rounded(palette.outline, activity.dpf(2f)) },
                FrameLayout.LayoutParams(activity.dp(32), activity.dp(4), Gravity.CENTER))
        }
        sheet.addView(handle, LinearLayout.LayoutParams(-1, activity.dp(28)))
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        overlay.onSystemInsets { insets -> sheet.setPadding(0, 0, 0, insets.bottom) }
        installSwipeDown(handle)
    }

    /** [footer] stays pinned under the scrolling content, for the sheet's main button. */
    fun show(content: View, footer: View? = null) {
        if (isShowing) return
        isShowing = true
        scroll.removeAllViews()
        scroll.addView(content)
        while (sheet.childCount > 2) sheet.removeViewAt(2)
        if (footer != null) sheet.addView(footer)
        scroll.maxHeight = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
        sheet.animate().cancel()
        scrim.animate().cancel()
        if (overlay.parent != null) root.removeView(overlay)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        systemInsets(root)?.let { sheet.setPadding(0, 0, 0, it.bottom) }
        overlay.requestApplyInsets()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        sheet.translationY = activity.resources.displayMetrics.heightPixels.toFloat()
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(320).setInterpolator(Motion.ENTER).start()
        }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        scrim.animate().alpha(0f).setDuration(180).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(200).setInterpolator(Motion.EXIT)
            .withEndAction {
                if (!isShowing) root.removeView(overlay)
                onDismissed?.invoke()
            }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installSwipeDown(handle: View) {
        var startY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> sheet.translationY = (e.rawY - startY).coerceAtLeast(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > sheet.height * 0.25f) dismiss()
                    else sheet.animate().translationY(0f).setDuration(180).start()
                }
            }
            true
        }
    }
}

/** Short message near the bottom of the screen, with an optional action such as Undo. */
object Snack {
    private const val TAG = "lumen-snack"

    fun show(parent: FrameLayout, palette: Palette, text: String, bottomMargin: Int, action: String? = null, onAction: (() -> Unit)? = null) {
        parent.findViewWithTag<View>(TAG)?.let { parent.removeView(it) }
        val ctx = parent.context
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            setPadding(ctx.dp(20), 0, ctx.dp(6), 0)
            background = Ui.rounded(palette.onSurface, ctx.dpf(16f))
            elevation = ctx.dpf(6f)
            addView(Ui.text(ctx, 14f, palette.surface, Fonts.body, text), LinearLayout.LayoutParams(0, -2, 1f))
            if (action != null && onAction != null) {
                addView(Ui.textButton(ctx, action, palette.primaryContainer) {
                    onAction()
                    parent.removeView(this)
                })
            } else {
                setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
            }
        }
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(16), 0, ctx.dp(16), bottomMargin)
        }
        bar.tag = TAG
        parent.addView(bar, lp)
        bar.alpha = 0f
        bar.translationY = ctx.dpf(16f)
        bar.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(Motion.ENTER).start()
        bar.postDelayed({
            bar.animate().alpha(0f).setDuration(200).withEndAction { parent.removeView(bar) }.start()
        }, 4000)
    }
}

/**
 * Navigation drawer that slides in from the left (the hamburger menu). Tap outside, swipe it
 * left or press back to close it. Items are added in code, so new entries are one line each.
 */
class SideDrawer(private val activity: Activity, private val palette: Palette) {
    private val root: ViewGroup = activity.findViewById(android.R.id.content)
    private val overlay = DrawerFrame()
    private val scrim = View(activity)
    private val panel = LinearLayout(activity)
    private val items = ArrayList<Pair<Int, LinearLayout>>()
    private var selectedId = -1
    var isShowing = false
        private set
    var onClosed: (() -> Unit)? = null

    init {
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { close() }
        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        panel.background = GradientDrawable().apply {
            setColor(palette.surfaceContainer)
            val r = activity.dpf(28f)
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        val m = activity.resources.displayMetrics
        val width = minOf((m.widthPixels * 0.82f).toInt(), activity.dp(320))
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        overlay.onSystemInsets { ins -> panel.setPadding(ins.left, ins.top + activity.dp(12), 0, ins.bottom + activity.dp(12)) }
    }

    /** App icon and name at the top of the drawer. */
    fun addHeader(iconRes: Int, title: String) {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(16), activity.dp(20))
            addView(ImageView(activity).apply { setImageResource(iconRes) },
                LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))
            addView(Ui.text(activity, 24f, palette.onSurface, Fonts.display, title).apply {
                setPadding(activity.dp(14), 0, 0, 0)
            })
        }
        panel.addView(header)
    }

    fun addItem(id: Int, iconRes: Int, label: String, onClick: () -> Unit) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(16), 0, activity.dp(16), 0)
            contentDescription = label
            addView(Ui.icon(activity, iconRes, palette.onSurfaceVariant))
            addView(Ui.text(activity, 15f, palette.onSurfaceVariant, Fonts.medium, label).apply {
                setPadding(activity.dp(12), 0, 0, 0)
            })
            setOnClickListener {
                close()
                onClick()
            }
        }
        items.add(id to row)
        panel.addView(row, LinearLayout.LayoutParams(-1, activity.dp(56)).apply {
            setMargins(activity.dp(12), 0, activity.dp(12), 0)
        })
        paint(row, id == selectedId)
    }

    fun addDivider() {
        panel.addView(Ui.divider(activity, palette.outlineVariant).apply {
            layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, activity.dp(1) / 2)).apply {
                setMargins(activity.dp(28), activity.dp(8), activity.dp(28), activity.dp(8))
            }
        })
    }

    /** Highlights the section you're in. */
    fun setSelected(id: Int) {
        selectedId = id
        for ((itemId, row) in items) paint(row, itemId == id)
    }

    private fun paint(row: LinearLayout, on: Boolean) {
        val fg = if (on) palette.onSecondaryContainer else palette.onSurfaceVariant
        row.background = Ui.pressable(
            if (on) Ui.rounded(palette.secondaryContainer, activity.dpf(28f)) else null,
            withAlpha(palette.onSurface, 0.12f), activity.dpf(28f),
        )
        (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(fg)
        (row.getChildAt(1) as TextView).setTextColor(fg)
        row.isSelected = on
    }

    fun open() {
        if (isShowing) return
        isShowing = true
        panel.animate().cancel()
        scrim.animate().cancel()
        if (overlay.parent != null) root.removeView(overlay)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        systemInsets(root)?.let { panel.setPadding(it.left, it.top + activity.dp(12), 0, it.bottom + activity.dp(12)) }
        overlay.requestApplyInsets()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        val w = (panel.layoutParams as FrameLayout.LayoutParams).width.toFloat()
        panel.translationX = -w
        panel.animate().translationX(0f).setDuration(300).setInterpolator(Motion.ENTER).start()
    }

    fun close() {
        if (!isShowing) return
        isShowing = false
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220).setInterpolator(Motion.EXIT)
            .withEndAction {
                if (!isShowing) root.removeView(overlay)
                onClosed?.invoke()
            }.start()
    }

    /** Lets you swipe the drawer closed to the left. */
    private inner class DrawerFrame : FrameLayout(activity) {
        private val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    if (dx < -slop && kotlin.math.abs(dx) > kotlin.math.abs(ev.y - downY)) dragging = true
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(ev)
            val dx = (ev.x - downX).coerceAtMost(0f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    panel.translationX = dx
                    scrim.alpha = (1f + dx / maxOf(1, panel.width)).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (-dx > panel.width * 0.3f) close()
                    else {
                        panel.animate().translationX(0f).setDuration(180).start()
                        scrim.animate().alpha(1f).setDuration(180).start()
                    }
                }
            }
            return true
        }
    }
}
