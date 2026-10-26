package dev.standby.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** Horizontal three-pane container: settings (-1) · cards (0) · apps (+1). */
class Navigator(ctx: Context) : FrameLayout(ctx) {
    private val panes = arrayOfNulls<View>(3)
    var pos = 0f
        private set
    var page = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null

    private val cfg = ViewConfiguration.get(ctx)
    private val slop = cfg.scaledTouchSlop
    private val minFling = cfg.scaledMinimumFlingVelocity * 6f
    private var downX = 0f
    private var downY = 0f
    private var startPos = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    fun setPanes(settings: View, cards: View, apps: View) {
        removeAllViews()
        listOf(settings, cards, apps).forEachIndexed { i, v ->
            panes[i] = v
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        apply()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        apply()
    }

    private fun setPos(p: Float) {
        pos = p
        apply()
    }

    private fun apply() {
        val w = width.toFloat()
        for (i in 0..2) {
            val v = panes[i] ?: continue
            val rel = (i - 1) - pos
            v.translationX = rel * w
            v.visibility = if (abs(rel) < 0.999f) VISIBLE else INVISIBLE
        }
    }

    fun goTo(target: Int, animate: Boolean = true) {
        anim?.cancel()
        val t = target.coerceIn(-1, 1)
        if (!animate || width == 0) {
            setPos(t.toFloat())
            finish(t)
            return
        }
        val dist = abs(t - pos)
        anim = ValueAnimator.ofFloat(pos, t.toFloat()).apply {
            duration = (180 + 220 * dist).toLong()
            interpolator = PathInterpolator(0.2f, 0.9f, 0.3f, 1f)
            addUpdateListener { setPos(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) finish(t) }
            })
            start()
        }
    }

    private fun finish(t: Int) {
        val changed = t != page
        page = t
        if (changed) onPageChanged?.invoke(t)
    }

    private fun allowed(dx: Float) = when (page) {
        -1 -> dx < 0
        1 -> dx > 0
        else -> true
    }

    private fun track(e: MotionEvent) {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
    }

    private fun startDragIfNeeded(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.4f && allowed(dx)) {
            dragging = true
            anim?.cancel()
            downX = e.x
            startPos = pos
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = null
                track(e)
                downX = e.x; downY = e.y
                dragging = false
                if (anim?.isRunning == true) {
                    anim?.cancel()
                    startPos = pos
                    dragging = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                if (!dragging) startDragIfNeeded(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) { vt?.recycle(); vt = null }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) startDragIfNeeded(e)
                if (dragging && width > 0) {
                    val base = page.toFloat()
                    var p = startPos - (e.x - downX) / width
                    val lo = maxOf(-1f, base - 1f)
                    val hi = minOf(1f, base + 1f)
                    // rubber band beyond the outermost panes
                    if (p < lo) p = lo - (lo - p) * 0.25f
                    if (p > hi) p = hi + (p - hi) * 0.25f
                    setPos(p.coerceIn(lo - 0.1f, hi + 0.1f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val target = when {
                        vx < -minFling && pos > page - 0.05f -> page + 1
                        vx > minFling && pos < page + 0.05f -> page - 1
                        else -> pos.roundToInt()
                    }.coerceIn(page - 1, page + 1)
                    goTo(target)
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}
