package io.github.realkhfan.lumen.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import io.github.realkhfan.lumen.ui.Motion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows one picture fitted to the screen. Pinch or double-tap to zoom, drag to pan, fling.
 *
 * The picture is laid out in "logical" pixels (its real size), so a low-resolution placeholder
 * can be swapped for the sharp version without the zoom jumping.
 */
class ZoomImageView(context: Context) : View(context) {
    var onTap: (() -> Unit)? = null

    private var drawable: Drawable? = null
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val fling = OverScroller(context)
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    val isZoomed: Boolean get() = scale > 1.02f

    fun setImage(d: Drawable?, logicalWidth: Int, logicalHeight: Int, keepZoom: Boolean) {
        drawable?.callback = null
        (drawable as? Animatable)?.stop()
        drawable = d
        d?.callback = this
        val sizeChanged = logicalWidth != imageWidth || logicalHeight != imageHeight
        imageWidth = logicalWidth
        imageHeight = logicalHeight
        if (!keepZoom || sizeChanged) reset()
        (d as? Animatable)?.start()
        invalidate()
    }

    fun hasImage() = drawable != null

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    fun reset() {
        anim?.cancel()
        fling.forceFinished(true)
        scale = 1f
        tx = 0f
        ty = 0f
        invalidate()
    }

    private fun fit(): Float =
        if (imageWidth <= 0 || imageHeight <= 0 || width == 0 || height == 0) 1f
        else min(width / imageWidth.toFloat(), height / imageHeight.toFloat())

    private fun maxScale(): Float {
        val onePixel = 1f / fit()
        return max(3f, onePixel * 2f).coerceAtMost(16f)
    }

    /** Where the picture is drawn, in this view's coordinates. */
    fun displayRect(out: RectF = RectF()): RectF {
        if (imageWidth <= 0 || imageHeight <= 0) {
            out.set(0f, 0f, width.toFloat(), height.toFloat())
            return out
        }
        val s = fit() * scale
        val w = imageWidth * s
        val h = imageHeight * s
        val l = (width - w) / 2 + tx
        val t = (height - h) / 2 + ty
        out.set(l, t, l + w, t + h)
        return out
    }

    private fun limits(forScale: Float): Pair<Float, Float> {
        val s = fit() * forScale
        return max(0f, (imageWidth * s - width) / 2) to max(0f, (imageHeight * s - height) / 2)
    }

    private fun clamp() {
        val (mx, my) = limits(scale)
        tx = tx.coerceIn(-mx, mx)
        ty = ty.coerceIn(-my, my)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return
        if (imageWidth <= 0 || imageHeight <= 0) return
        val s = fit() * scale
        val r = displayRect(rect)
        val save = canvas.save()
        canvas.translate(r.left, r.top)
        canvas.scale(s, s)
        d.setBounds(0, 0, imageWidth, imageHeight)
        d.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun computeScroll() {
        if (fling.computeScrollOffset()) {
            tx = fling.currX.toFloat()
            ty = fling.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    /** Scale to [target] keeping the picture point under (fx, fy) in place. */
    private fun translationFor(target: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val f = fit()
        val r = displayRect(rect)
        val s0 = f * scale
        val px = (fx - r.left) / s0
        val py = (fy - r.top) / s0
        val s1 = f * target
        val w1 = imageWidth * s1
        val h1 = imageHeight * s1
        return (fx - px * s1 - (width - w1) / 2) to (fy - py * s1 - (height - h1) / 2)
    }

    private fun animateTo(targetScale: Float, targetX: Float, targetY: Float) {
        anim?.cancel()
        fling.forceFinished(true)
        val s0 = scale
        val x0 = tx
        val y0 = ty
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = Motion.STANDARD
            addUpdateListener {
                val p = it.animatedValue as Float
                scale = s0 + (targetScale - s0) * p
                tx = x0 + (targetX - x0) * p
                ty = y0 + (targetY - y0) * p
                invalidate()
            }
            start()
        }
    }

    private fun settle() {
        val target = scale.coerceIn(1f, maxScale())
        val ratio = if (scale == 0f) 1f else target / scale
        var nx = tx * ratio
        var ny = ty * ratio
        val (mx, my) = limits(target)
        nx = nx.coerceIn(-mx, mx)
        ny = ny.coerceIn(-my, my)
        if (target != scale || nx != tx || ny != ty) animateTo(target, nx, ny)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (imageWidth <= 0) return false
            anim?.cancel()
            fling.forceFinished(true)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (scale * detector.scaleFactor).coerceIn(0.7f, maxScale() * 1.25f)
            val (nx, ny) = translationFor(target, detector.focusX, detector.focusY)
            scale = target
            tx = nx
            ty = ny
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = settle()
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            fling.forceFinished(true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (imageWidth <= 0) return false
            if (isZoomed) {
                animateTo(1f, 0f, 0f)
            } else {
                val f = fit()
                val fill = max(width / imageWidth.toFloat(), height / imageHeight.toFloat()) / f
                val target = max(2.5f, if (fill > 1.2f) fill else 0f).coerceAtMost(maxScale())
                var (nx, ny) = translationFor(target, e.x, e.y)
                val (mx, my) = limits(target)
                nx = nx.coerceIn(-mx, mx)
                ny = ny.coerceIn(-my, my)
                animateTo(target, nx, ny)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            tx -= distanceX
            ty -= distanceY
            clamp()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isZoomed) return false
            val (mx, my) = limits(scale)
            fling.fling(
                tx.roundToInt(), ty.roundToInt(), velocityX.roundToInt(), velocityY.roundToInt(),
                -mx.roundToInt(), mx.roundToInt(), -my.roundToInt(), my.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (scale < 1f) settle()
        }
        return true
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (!isZoomed) return false
        val r = displayRect(rect)
        return if (direction > 0) r.right > width + 1f else r.left < -1f
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (!isZoomed) return false
        val r = displayRect(rect)
        return if (direction > 0) r.bottom > height + 1f else r.top < -1f
    }
}
