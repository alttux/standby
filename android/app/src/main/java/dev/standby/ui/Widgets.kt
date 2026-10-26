package dev.standby.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.standby.L
import dev.standby.cards.ClockDraw
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/** Tap recogniser used by small custom controls. */
private class Taps(ctx: Context) {
    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    var x = 0f
    var y = 0f
    fun down(e: MotionEvent) { x = e.x; y = e.y }
    fun isTap(e: MotionEvent) = abs(e.x - x) < slop && abs(e.y - y) < slop
}

/** UISwitch look-alike (51×31 pt). */
@SuppressLint("ViewConstructor")
class IosSwitch(ctx: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var checked = initial
        private set
    private var progress = if (initial) 1f else 0f
    private val p = fillPaint()
    private val taps = Taps(ctx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(51.idp, 31.idp)

    fun toggle() {
        checked = !checked
        ValueAnimator.ofFloat(progress, if (checked) 1f else 0f).apply {
            duration = 220
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
        onChange(checked)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> taps.down(e)
            MotionEvent.ACTION_UP -> if (taps.isTap(e)) toggle()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val w = width.toFloat()
        val h = height.toFloat()
        val off = if (pal.dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()
        p.color = blend(off, pal.green, progress)
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        val r = h / 2 - 2.dp
        val cx = h / 2 + (w - h) * progress
        p.color = 0x22000000
        c.drawCircle(cx, h / 2 + 1.dp, r + 0.5f.dp, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, h / 2, r, p)
    }
}

/** UISegmentedControl look-alike. */
@SuppressLint("ViewConstructor")
class Segmented(ctx: Context, private val items: List<String>, initial: Int, private val onSelect: (Int) -> Unit) : View(ctx) {
    var selected = initial
        private set
    private var pos = initial.toFloat()
    private val tp = textPaint(Fonts.medium, 13.dp, 0, Paint.Align.CENTER)
    private val p = fillPaint()
    private val taps = Taps(ctx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        tp.typeface = Fonts.semibold
        val itemW = items.maxOf { tp.measureText(it) } + 26.dp
        val want = (itemW * items.size).toInt()
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(want, MeasureSpec.getSize(widthMeasureSpec))
            else -> want
        }
        setMeasuredDimension(w, 32.idp)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { taps.down(e); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP -> if (taps.isTap(e)) {
                val i = (e.x / width * items.size).toInt().coerceIn(0, items.size - 1)
                if (i != selected) {
                    selected = i
                    ValueAnimator.ofFloat(pos, i.toFloat()).apply {
                        duration = 200
                        addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                        start()
                    }
                    onSelect(i)
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 8.dp
        p.color = pal.fill
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        val iw = w / items.size
        // separators
        p.color = withAlpha(pal.text3, 0.35f)
        for (i in 1 until items.size) {
            if (abs(i - pos) < 1f || abs(i - 1 - pos) < 1f) continue
            c.drawRect(i * iw - 0.5f.dp, h * 0.28f, i * iw + 0.5f.dp, h * 0.72f, p)
        }
        // thumb
        val tr = RectF(pos * iw + 2.dp, 2.dp, (pos + 1) * iw - 2.dp, h - 2.dp)
        p.color = 0x14000000
        c.drawRoundRect(RectF(tr).apply { offset(0f, 1.dp) }, r - 1.dp, r - 1.dp, p)
        p.color = if (pal.dark) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
        c.drawRoundRect(tr, r - 1.dp, r - 1.dp, p)
        items.forEachIndexed { i, s ->
            tp.typeface = if (i == selected) Fonts.semibold else Fonts.medium
            tp.color = pal.text
            c.drawText(ellipsize(s, tp, iw - 8.dp), iw * (i + 0.5f), h / 2 + capHeight(tp) / 2, tp)
        }
    }
}

/** UISlider look-alike; value in 0..1. */
@SuppressLint("ViewConstructor")
class IosSlider(ctx: Context, initial: Float, private val onChange: (Float, Boolean) -> Unit) : View(ctx) {
    var value = initial.coerceIn(0f, 1f)
        private set
    private val p = fillPaint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 34.idp)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = 14.dp
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        value = ((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange(value, done)
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val r = 14.dp
        val cy = height / 2f
        val x = r + (width - 2 * r) * value
        p.color = pal.fill
        c.drawRoundRect(RectF(r, cy - 2.dp, width - r, cy + 2.dp), 2.dp, 2.dp, p)
        p.color = pal.accent
        c.drawRoundRect(RectF(r, cy - 2.dp, x, cy + 2.dp), 2.dp, 2.dp, p)
        p.color = 0x26000000
        c.drawCircle(x, cy + 1.dp, r, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, cy, r - 0.5f.dp, p)
    }
}

/** Row of colour dots (StandBy-style colour picker). */
@SuppressLint("ViewConstructor")
class ColorDots(ctx: Context, private val names: List<String>, initial: String, private val onPick: (String) -> Unit) : View(ctx) {
    private var selected = initial
    private val p = fillPaint()
    private val taps = Taps(ctx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 48.idp)

    private fun step() = max(30.dp, minOf(42.dp, width / names.size.toFloat()))

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> taps.down(e)
            MotionEvent.ACTION_UP -> if (taps.isTap(e)) {
                val i = (e.x / step()).toInt()
                if (i in names.indices) {
                    selected = names[i]
                    invalidate()
                    onPick(selected)
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val s = step()
        val cy = height / 2f
        names.forEachIndexed { i, n ->
            val cx = s * (i + 0.5f)
            val col = pal.named(n)
            if (n == "mono") {
                p.color = pal.text
                c.drawCircle(cx, cy, s * 0.32f, p)
                p.color = pal.surface
                c.drawCircle(cx, cy, s * 0.16f, p)
            } else {
                p.color = col
                c.drawCircle(cx, cy, s * 0.32f, p)
            }
            if (n == "accent") {
                p.color = contrastOn(col)
                c.drawCircle(cx, cy, s * 0.08f, p)
            }
            if (n == selected) {
                val sp = strokePaint(pal.text2, 2.dp)
                c.drawCircle(cx, cy, s * 0.43f, sp)
            }
        }
    }
}

/** Five colour dots previewing a scheme. */
@SuppressLint("ViewConstructor")
class Swatch(ctx: Context, private val scheme: Scheme) : View(ctx) {
    private val p = fillPaint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(86.idp, 20.idp)

    override fun onDraw(c: Canvas) {
        val pal = if (Themes.p.dark) scheme.dark else scheme.light
        val cols = listOf(pal.bg, pal.accent, pal.red, pal.green, pal.yellow)
        val r = 7.dp
        cols.forEachIndexed { i, col ->
            val cx = r + 1.dp + i * 16.dp
            p.color = Themes.p.separator
            c.drawCircle(cx, height / 2f, r + 1.dp, p)
            p.color = col
            c.drawCircle(cx, height / 2f, r, p)
        }
    }
}

/** Light / Dark / Auto preview tiles, like iOS Display & Brightness. */
@SuppressLint("ViewConstructor")
class AppearancePicker(ctx: Context, private val scheme: Scheme, initial: String, private val onPick: (String) -> Unit) : View(ctx) {
    private val modes = listOf("light", "dark", "auto")
    private var selected = initial
    private val p = fillPaint()
    private val tp = textPaint(Fonts.regular, 14.dp, 0, Paint.Align.CENTER)
    private val taps = Taps(ctx)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 150.idp)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> taps.down(e)
            MotionEvent.ACTION_UP -> if (taps.isTap(e)) {
                val i = (e.x / width * 3).toInt().coerceIn(0, 2)
                selected = modes[i]
                invalidate()
                onPick(selected)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val cw = width / 3f
        val tw = minOf(cw * 0.62f, 108.dp)
        val th = tw * 0.62f
        modes.forEachIndexed { i, m ->
            val cx = cw * (i + 0.5f)
            val r = RectF(cx - tw / 2, 14.dp, cx + tw / 2, 14.dp + th)
            fun mock(rect: RectF, pp: Palette) {
                p.color = pp.bg
                c.drawRoundRect(rect, 8.dp, 8.dp, p)
                p.color = pp.text
                val pad = rect.height() * 0.18f
                c.drawCircle(rect.left + pad + rect.height() * 0.2f, rect.centerY(), rect.height() * 0.24f, p)
                p.color = pp.accent
                c.drawRoundRect(RectF(rect.centerX() + 2.dp, rect.top + pad, rect.right - pad, rect.top + pad + 4.dp), 2.dp, 2.dp, p)
                p.color = pp.text3
                for (k in 0..2) c.drawRoundRect(RectF(rect.centerX() + 2.dp, rect.top + pad + 10.dp + k * 7.dp, rect.right - pad - k * 6.dp, rect.top + pad + 13.dp + k * 7.dp), 1.5f.dp, 1.5f.dp, p)
            }
            p.color = pal.separator
            c.drawRoundRect(RectF(r).apply { inset(-1.dp, -1.dp) }, 9.dp, 9.dp, p)
            when (m) {
                "light" -> mock(r, scheme.light)
                "dark" -> mock(r, scheme.dark)
                else -> {
                    c.save(); c.clipRect(r.left, r.top, r.centerX(), r.bottom); mock(r, scheme.light); c.restore()
                    c.save(); c.clipRect(r.centerX(), r.top, r.right, r.bottom); mock(r, scheme.dark); c.restore()
                }
            }
            tp.color = pal.text
            val label = when (m) {
                "light" -> L("Light", "Светлое")
                "dark" -> L("Dark", "Тёмное")
                else -> L("Auto", "Авто")
            }
            c.drawText(label, cx, r.bottom + 22.dp, tp)
            val ry = r.bottom + 44.dp
            if (m == selected) {
                p.color = pal.accent
                c.drawCircle(cx, ry, 11.dp, p)
                Glyphs.check(c, cx, ry, 12.dp, 0xFFFFFFFF.toInt())
            } else {
                c.drawCircle(cx, ry, 11.dp, strokePaint(pal.text3, 1.5f.dp))
            }
        }
    }
}

/** Thumbnails of the four clock faces. */
@SuppressLint("ViewConstructor")
class ClockStylePicker(ctx: Context, private val styles: List<Pair<String, String>>, initial: String, private val onPick: (String) -> Unit) : View(ctx) {
    private var selected = initial
    private val tp = textPaint(Fonts.regular, 13.dp, 0, Paint.Align.CENTER)
    private val taps = Taps(ctx)
    private val sample = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 30) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val tw = (w / 2f - 16.dp)
        setMeasuredDimension(w, (2 * (tw * 0.56f + 30.dp) + 12.dp).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> taps.down(e)
            MotionEvent.ACTION_UP -> if (taps.isTap(e)) {
                val col = if (e.x < width / 2f) 0 else 1
                val row = if (e.y < height / 2f) 0 else 1
                styles.getOrNull(row * 2 + col)?.let {
                    selected = it.first
                    invalidate()
                    onPick(it.first)
                }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        val tw = width / 2f - 16.dp
        val th = tw * 0.56f
        styles.forEachIndexed { i, (id, label) ->
            val x = 8.dp + (i % 2) * (tw + 16.dp)
            val y = 8.dp + (i / 2) * (th + 30.dp)
            val r = RectF(x, y, x + tw, y + th)
            val fp = fillPaint(pal.bg)
            c.drawRoundRect(r, 10.dp, 10.dp, fp)
            c.save()
            c.clipRect(r)
            val face = pal.named(dev.standby.data.Prefs.clockColor)
            val inner = if (id == "analog_classic" || id == "digital_thin") RectF(r).apply { inset(th * 0.06f, th * 0.06f) } else r
            ClockDraw.draw(id, c, inner, pal, face, sample, true, true)
            c.restore()
            if (id == selected) c.drawRoundRect(RectF(r).apply { inset(-2.dp, -2.dp) }, 12.dp, 12.dp, strokePaint(pal.accent, 2.5f.dp))
            else c.drawRoundRect(r, 10.dp, 10.dp, strokePaint(pal.separator, 1.dp))
            tp.color = if (id == selected) pal.accent else pal.text2
            c.drawText(label, r.centerX(), r.bottom + 19.dp, tp)
        }
    }
}
