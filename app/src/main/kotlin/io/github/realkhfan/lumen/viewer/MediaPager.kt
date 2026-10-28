package io.github.realkhfan.lumen.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import io.github.realkhfan.lumen.ui.Motion
import io.github.realkhfan.lumen.ui.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horizontal pager for the viewer. Only three pages exist at once (previous, current, next);
 * they are reused as you swipe. Vertical drags on an unzoomed page are reported to the
 * listener, which uses them for swipe-down-to-close and swipe-up-for-info.
 */
class MediaPager(context: Context) : ViewGroup(context) {
    interface Adapter {
        val count: Int
        fun type(position: Int): Int
        fun create(type: Int): View
        fun bind(view: View, position: Int, current: Boolean)
        fun unbind(view: View)
        fun onCurrent(view: View, position: Int)
        fun onNotCurrent(view: View)
    }

    interface Listener {
        fun onPageSelected(position: Int)
        fun onVerticalDrag(dy: Float)
        fun onVerticalRelease(dy: Float, velocityY: Float)
    }

    var adapter: Adapter? = null
    var listener: Listener? = null
    var current = 0
        private set

    private val gap = dp(20)
    private val slots = HashMap<Int, View>()
    private val recycled = HashMap<Int, ArrayList<View>>()
    private val scroller = OverScroller(context, Motion.STANDARD)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocity: VelocityTracker? = null

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var mode = IDLE
    private var activePointer = -1
    private var currentNotified = -1

    private val stride get() = width + gap

    fun currentView(): View? = slots[current]

    // Lets the video seek bar grab a drag right away instead of losing it to page swipes.
    override fun shouldDelayChildPressedState(): Boolean = false

    /** Jump to [position] without animation. */
    fun setCurrent(position: Int) {
        val a = adapter ?: return
        if (a.count == 0) return
        scroller.forceFinished(true)
        current = position.coerceIn(0, a.count - 1)
        fillSlots()
        if (width > 0) scrollTo(current * stride, 0)
        notifyCurrent()
    }

    /** Call after the adapter's list changed; keeps [position] in view. */
    fun dataChanged(position: Int) {
        val a = adapter ?: return
        for (v in slots.values.toList()) recycle(v)
        slots.clear()
        currentNotified = -1
        if (a.count == 0) return
        setCurrent(position)
    }

    fun releaseAll() {
        for (v in slots.values.toList()) recycle(v)
        slots.clear()
    }

    private fun fillSlots() {
        val a = adapter ?: return
        val wanted = (max(0, current - 1)..min(a.count - 1, current + 1)).toSet()
        for ((pos, view) in slots.entries.toList()) {
            if (pos !in wanted) {
                slots.remove(pos)
                recycle(view)
            }
        }
        for (pos in wanted) {
            val existing = slots[pos]
            if (existing == null) {
                val type = a.type(pos)
                val view = recycled[type]?.removeLastOrNull() ?: a.create(type)
                view.setTag(TAG_TYPE, type)
                slots[pos] = view
                addViewInLayout(view, -1, LayoutParams(-1, -1), true)
                a.bind(view, pos, pos == current)
                view.measure(
                    MeasureSpec.makeMeasureSpec(max(0, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(max(0, height), MeasureSpec.EXACTLY),
                )
            }
        }
        requestLayout()
        invalidate()
    }

    private fun recycle(view: View) {
        adapter?.unbind(view)
        removeViewInLayout(view)
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        val type = view.getTag(TAG_TYPE) as? Int ?: return
        recycled.getOrPut(type) { ArrayList() }.add(view)
    }

    private fun notifyCurrent() {
        val a = adapter ?: return
        if (currentNotified == current) return
        val previous = slots[currentNotified]
        if (previous != null) a.onNotCurrent(previous)
        currentNotified = current
        slots[current]?.let { a.onCurrent(it, current) }
        listener?.onPageSelected(current)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for ((pos, view) in slots) {
            val left = pos * (w + gap)
            view.layout(left, 0, left + w, h)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scroller.forceFinished(true)
        scrollTo(current * (w + gap), 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
            if (scroller.isFinished) settle()
        }
    }

    private fun settle() {
        val a = adapter ?: return
        if (stride <= 0) return
        val pos = (scrollX.toFloat() / stride).roundToInt().coerceIn(0, max(0, a.count - 1))
        if (pos != current) {
            current = pos
            fillSlots()
        }
        notifyCurrent()
    }

    // ---------------------------------------------------------------- touch

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                activePointer = ev.getPointerId(0)
                mode = IDLE
                trackVelocity(ev, reset = true)
                if (!scroller.isFinished) {
                    // Catch a page that's still settling.
                    scroller.forceFinished(true)
                    mode = HORIZONTAL
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(ev)
                if (ev.pointerCount > 1) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                val child = currentView()
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.1f) {
                    val dir = if (dx > 0) -1 else 1
                    if (child == null || !child.canScrollHorizontally(dir)) {
                        mode = HORIZONTAL
                        lastX = ev.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                    val dir = if (dy > 0) -1 else 1
                    if (child == null || !child.canScrollVertically(dir)) {
                        mode = VERTICAL
                        downY = ev.y
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = IDLE
        }
        return mode != IDLE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                activePointer = ev.getPointerId(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(activePointer).takeIf { it >= 0 } ?: 0
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (mode == IDLE) {
                    if (abs(x - downX) > slop) mode = HORIZONTAL
                    else if (abs(y - downY) > slop) {
                        mode = VERTICAL
                        downY = y
                    }
                }
                when (mode) {
                    HORIZONTAL -> {
                        var delta = lastX - x
                        val a = adapter
                        val maxScroll = ((a?.count ?: 1) - 1) * stride
                        val next = scrollX + delta
                        if (next < 0 || next > maxScroll) delta *= 0.35f
                        scrollBy(delta.roundToInt(), 0)
                        lastX = x
                    }
                    VERTICAL -> listener?.onVerticalDrag(y - downY)
                }
            }
            MotionEvent.ACTION_UP -> {
                val v = velocity
                v?.computeCurrentVelocity(1000, maxFling.toFloat())
                val vx = v?.getXVelocity(activePointer) ?: 0f
                val vy = v?.getYVelocity(activePointer) ?: 0f
                when (mode) {
                    HORIZONTAL -> snap(vx)
                    VERTICAL -> listener?.onVerticalRelease(ev.y - downY, vy)
                    else -> Unit
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    HORIZONTAL -> snap(0f)
                    VERTICAL -> listener?.onVerticalRelease(0f, 0f)
                    else -> Unit
                }
                endTouch()
            }
        }
        return true
    }

    private fun snap(vx: Float) {
        val a = adapter ?: return
        if (stride <= 0) return
        val exact = scrollX.toFloat() / stride
        var target = when {
            vx < -minFling * 4 -> current + 1
            vx > minFling * 4 -> current - 1
            else -> exact.roundToInt()
        }
        target = target.coerceIn(max(0, current - 1), min(a.count - 1, current + 1))
        val dx = target * stride - scrollX
        val duration = (220 + abs(dx).toFloat() / max(1, width) * 160).toInt()
        scroller.startScroll(scrollX, 0, dx, 0, duration)
        postInvalidateOnAnimation()
    }

    private fun trackVelocity(ev: MotionEvent, reset: Boolean = false) {
        if (reset) velocity?.clear()
        val v = velocity ?: VelocityTracker.obtain().also { velocity = it }
        v.addMovement(ev)
    }

    private fun endTouch() {
        mode = IDLE
        velocity?.recycle()
        velocity = null
    }

    companion object {
        private const val IDLE = 0
        private const val HORIZONTAL = 1
        private const val VERTICAL = 2
        private val TAG_TYPE = io.github.realkhfan.lumen.R.id.lumen_page_type
    }
}
