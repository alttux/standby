package dev.standby.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import dev.standby.cards.Card
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Vertical, optionally looping, stack of cards switched with up/down swipes. */
class CardPager(ctx: Context) : FrameLayout(ctx) {
    val cards = ArrayList<Card>()
    var index = 0
        private set
    private var offset = 0f
    var loop = true
    var onIndexChanged: ((Int) -> Unit)? = null
    var onLongPress: ((Card) -> Unit)? = null

    /** True when the pager is on screen (cards pane visible, activity resumed). */
    var shown = false
        set(v) {
            field = v
            updateActive()
        }

    private val cfg = ViewConfiguration.get(ctx)
    private val slop = cfg.scaledTouchSlop
    private val minFling = cfg.scaledMinimumFlingVelocity * 6f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private var dotsAlpha = 0f
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        current()?.let { onLongPress?.invoke(it) }
    }

    private val hideDots = Runnable {
        ValueAnimator.ofFloat(dotsAlpha, 0f).apply {
            duration = 400
            addUpdateListener { dotsAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun current(): Card? = cards.getOrNull(index)

    fun setCards(list: List<Card>, start: Int) {
        cards.forEach { it.setActiveCard(false) }
        removeAllViews()
        cards.clear()
        cards.addAll(list)
        list.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        index = start.coerceIn(0, maxOf(0, list.size - 1))
        offset = 0f
        apply()
        updateActive()
    }

    fun goTo(i: Int) {
        if (i !in cards.indices || i == index) return
        anim?.cancel()
        index = i
        offset = 0f
        apply()
        updateActive()
        onIndexChanged?.invoke(index)
    }

    private fun neighbour(dir: Int): Int {
        val n = cards.size
        if (n < 2) return -1
        val j = index + dir
        return if (loop) (j + n) % n else if (j in 0 until n) j else -1
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        apply()
    }

    private fun apply() {
        val h = height.toFloat()
        val dir = if (offset >= 0) 1 else -1
        val nb = if (offset != 0f) neighbour(dir) else -1
        cards.forEachIndexed { i, c ->
            when (i) {
                index -> {
                    c.translationY = -offset * h
                    val s = 1f - 0.06f * abs(offset)
                    c.scaleX = s; c.scaleY = s
                    c.alpha = 1f - 0.3f * abs(offset)
                    c.visibility = VISIBLE
                }
                nb -> {
                    c.translationY = (dir - offset) * h
                    c.scaleX = 1f; c.scaleY = 1f; c.alpha = 1f
                    c.visibility = VISIBLE
                }
                else -> c.visibility = INVISIBLE
            }
        }
        invalidate()
    }

    private fun updateActive() {
        cards.forEachIndexed { i, c -> c.setActiveCard(shown && i == index) }
    }

    private fun showDots() {
        removeCallbacks(hideDots)
        dotsAlpha = 1f
        invalidate()
        postDelayed(hideDots, 1400)
    }

    private fun track(e: MotionEvent) {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(e)
    }

    private fun begin(e: MotionEvent) {
        vt?.recycle(); vt = null
        track(e)
        downX = e.x; downY = e.y
        dragging = false
        longPressed = false
        removeCallbacks(longPress)
        if (anim?.isRunning == true) {
            anim?.cancel()
            dragging = true
            downY = e.y + offset * height
        } else {
            postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong() + 150)
        }
    }

    private fun maybeStartDrag(e: MotionEvent) {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(longPress)
        if (!dragging && !longPressed && cards.size > 1 && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
            dragging = true
            downY = e.y + offset * height
            parent?.requestDisallowInterceptTouchEvent(true)
            showDots()
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e)
            MotionEvent.ACTION_MOVE -> { track(e); maybeStartDrag(e) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return dragging || longPressed
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e)
            MotionEvent.ACTION_MOVE -> {
                maybeStartDrag(e)
                if (dragging && height > 0) {
                    var o = (downY - e.y) / height
                    if (!loop) {
                        if (index == 0 && o < 0) o *= 0.3f
                        if (index == cards.size - 1 && o > 0) o *= 0.3f
                    }
                    offset = o.coerceIn(-1f, 1f)
                    apply()
                    showDots()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    var target = when {
                        vy < -minFling -> 1f
                        vy > minFling -> -1f
                        else -> offset.roundToInt().toFloat()
                    }
                    if (target != 0f && sign(target) != sign(offset) && offset != 0f) target = 0f
                    if (target != 0f && neighbour(target.toInt()) < 0) target = 0f
                    settle(target)
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun settle(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(offset, target).apply {
            duration = (160 + 260 * abs(target - offset)).toLong()
            interpolator = PathInterpolator(0.2f, 0.9f, 0.3f, 1f)
            addUpdateListener { offset = it.animatedValue as Float; apply() }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (target != 0f) {
                        index = neighbour(target.toInt())
                        offset = 0f
                        apply()
                        updateActive()
                        onIndexChanged?.invoke(index)
                    }
                    showDots()
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val n = cards.size
        if (dotsAlpha <= 0f || n < 2) return
        val p = Themes.p
        val r = 3.5f.dp
        val gap = 13.dp
        val x = width - 12.dp
        val y0 = height / 2f - (n - 1) * gap / 2f
        val pos = index + offset
        for (i in 0 until n) {
            val near = 1f - abs(i - pos).coerceAtMost(1f)
            dotPaint.color = fade(blend(p.text3, p.text, near), dotsAlpha)
            canvas.drawCircle(x, y0 + i * gap, r * (1f + 0.25f * near), dotPaint)
        }
    }
}
