package dev.standby.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import dev.standby.L
import dev.standby.data.Prefs
import dev.standby.ui.Fonts
import dev.standby.ui.Glyphs
import dev.standby.ui.Themes
import dev.standby.ui.capHeight
import dev.standby.ui.fillPaint
import dev.standby.ui.textPaint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Schedules the next redraw on the next wall-clock second while the card is active. */
private fun View.tickSecond(active: Boolean) {
    if (active) postInvalidateDelayed(1000L - System.currentTimeMillis() % 1000L + 5L)
}

/** Home: clock on one half, month calendar on the other. */
@SuppressLint("ViewConstructor")
class HomeCard(ctx: Context) : Card(ctx) {
    override val key = "home"

    private val view = object : View(ctx) {
        private val tap = TapDetector(ctx) { x, _ ->
            val clockLeft = !Prefs.homeSwap
            if ((x < width / 2f) == clockLeft) {
                Prefs.homeClock = if (Prefs.homeClock == "analog") "digital" else "analog"
                invalidate()
            }
            true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = tap.onTouch(e)

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            val accent = Themes.accent()
            val cal = Calendar.getInstance()
            c.drawColor(pal.bg)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = h * 0.08f
            val left = RectF(pad, pad, w / 2f - pad / 2f, h - pad)
            val right = RectF(w / 2f + pad / 2f, pad, w - pad, h - pad)
            val clockR = if (Prefs.homeSwap) right else left
            val calR = if (Prefs.homeSwap) left else right
            val seconds = Prefs.clockSeconds
            if (Prefs.homeClock == "analog") {
                ClockDraw.analogClassic(c, clockR, pal, accent, cal, seconds)
            } else {
                ClockDraw.digitalThin(c, clockR, pal, pal.text, cal, seconds, true)
            }
            MonthDraw.draw(c, calR, pal, accent, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
            tickSecond(isActiveCard && seconds)
        }
    }

    init { addView(view) }
}

/** Full-screen clock with four faces; tap cycles through them. */
@SuppressLint("ViewConstructor")
class ClockCard(ctx: Context) : Card(ctx) {
    override val key = "clock"

    companion object {
        val STYLES = listOf("digital_bold", "analog_bold", "analog_classic", "digital_thin")
    }

    private val view = object : View(ctx) {
        private val tap = TapDetector(ctx) { _, _ ->
            val i = STYLES.indexOf(Prefs.clockStyle)
            Prefs.clockStyle = STYLES[(i + 1) % STYLES.size]
            invalidate()
            true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = tap.onTouch(e)

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            c.drawColor(pal.bg)
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val style = Prefs.clockStyle
            val face = pal.named(Prefs.clockColor)
            val area = if (style == "analog_classic" || style == "digital_thin") RectF(r).apply { inset(height * 0.06f, height * 0.06f) } else r
            ClockDraw.draw(style, c, area, pal, face, Calendar.getInstance(), Prefs.clockSeconds, Prefs.clockDate)
            tickSecond(isActiveCard && (Prefs.clockSeconds || style == "digital_bold"))
        }
    }

    init { addView(view) }
}

/** Month calendar with navigation and a large date panel. */
@SuppressLint("ViewConstructor")
class CalendarCard(ctx: Context) : Card(ctx) {
    override val key = "calendar"

    private val shown: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var selected: Calendar? = null

    private val prevR = RectF()
    private val nextR = RectF()
    private val titleR = RectF()
    private val todayR = RectF()
    private val gridR = RectF()

    private val tp = textPaint(Fonts.semibold, 10f, 0)
    private val fp = fillPaint()

    override fun onActiveChanged(active: Boolean) {
        if (!active) {
            // return to the current month when the card is left
            shown.timeInMillis = System.currentTimeMillis()
            shown.set(Calendar.DAY_OF_MONTH, 1)
            selected = null
        }
    }

    private val view = object : View(ctx) {
        private val tap = TapDetector(ctx) { x, y -> onTap(x, y) }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = tap.onTouch(e)

        override fun onDraw(c: Canvas) = drawCard(c, width.toFloat(), height.toFloat())
    }

    init { addView(view) }

    private fun onTap(x: Float, y: Float): Boolean {
        when {
            prevR.contains(x, y) -> shown.add(Calendar.MONTH, -1)
            nextR.contains(x, y) -> shown.add(Calendar.MONTH, 1)
            titleR.contains(x, y) || todayR.contains(x, y) -> {
                shown.timeInMillis = System.currentTimeMillis()
                shown.set(Calendar.DAY_OF_MONTH, 1)
                selected = null
            }
            else -> {
                val d = MonthDraw.dayAt(gridR, shown.get(Calendar.YEAR), shown.get(Calendar.MONTH), false, x, y)
                if (d > 0) selected = (shown.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
            }
        }
        view.invalidate()
        return true
    }

    private fun drawCard(c: Canvas, w: Float, h: Float) {
        val pal = Themes.p
        val accent = Themes.accent()
        c.drawColor(pal.bg)
        val pad = h * 0.08f
        val now = Calendar.getInstance()
        val day = selected ?: now
        val isToday = selected == null || (day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR))

        // Left: big date panel
        val lw = w * 0.36f
        val loc = Locale.getDefault()
        tp.textAlign = Paint.Align.LEFT
        tp.typeface = Fonts.semibold
        tp.textSize = h * 0.075f
        tp.color = accent
        var y = pad + capHeight(tp)
        c.drawText(SimpleDateFormat("EEEE", loc).format(day.time).replaceFirstChar { it.titlecase(loc) }, pad, y, tp)
        tp.typeface = Fonts.dBold
        tp.textSize = h * 0.42f
        tp.color = pal.text
        y += h * 0.05f + capHeight(tp)
        c.drawText(day.get(Calendar.DAY_OF_MONTH).toString(), pad - tp.textSize * 0.04f, y, tp)
        tp.typeface = Fonts.dMedium
        tp.textSize = h * 0.07f
        tp.color = pal.text2
        y += h * 0.07f + capHeight(tp)
        c.drawText(TimeFmt.monthName(day) + " " + day.get(Calendar.YEAR), pad, y, tp)
        tp.typeface = Fonts.medium
        tp.textSize = h * 0.05f
        tp.color = pal.text3
        y += h * 0.06f + capHeight(tp)
        val wk = (day.clone() as Calendar).apply { firstDayOfWeek = MonthDraw.firstDow(); minimalDaysInFirstWeek = 4 }
        c.drawText(L("Week ${wk.get(Calendar.WEEK_OF_YEAR)} · Day ${day.get(Calendar.DAY_OF_YEAR)}",
            "Неделя ${wk.get(Calendar.WEEK_OF_YEAR)} · День ${day.get(Calendar.DAY_OF_YEAR)}"), pad, y, tp)

        val viewingCurrent = shown.get(Calendar.YEAR) == now.get(Calendar.YEAR) && shown.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        if (!isToday || !viewingCurrent) {
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.05f
            val label = L("Today", "Сегодня")
            val bw = tp.measureText(label) + h * 0.08f
            val bh = h * 0.1f
            todayR.set(pad, h - pad - bh, pad + bw, h - pad)
            fp.color = pal.surface2
            c.drawRoundRect(todayR, bh / 2, bh / 2, fp)
            tp.color = accent
            tp.textAlign = Paint.Align.CENTER
            c.drawText(label, todayR.centerX(), todayR.centerY() + capHeight(tp) / 2f, tp)
        } else todayR.setEmpty()

        // Right: header with arrows + grid
        val rx = lw + pad * 0.5f
        val headH = h * 0.14f
        tp.typeface = Fonts.bold
        tp.textSize = h * 0.07f
        tp.color = pal.text
        tp.textAlign = Paint.Align.LEFT
        val title = TimeFmt.monthName(shown) + " " + shown.get(Calendar.YEAR)
        c.drawText(title, rx + h * 0.02f, pad + headH / 2f + capHeight(tp) / 2f, tp)
        titleR.set(rx, pad, rx + tp.measureText(title) + h * 0.04f, pad + headH)
        val ar = headH * 0.42f
        nextR.set(w - pad - ar * 2, pad + headH / 2 - ar, w - pad, pad + headH / 2 + ar)
        prevR.set(nextR.left - ar * 2.6f, nextR.top, nextR.left - ar * 0.6f, nextR.bottom)
        fp.color = pal.surface2
        c.drawCircle(prevR.centerX(), prevR.centerY(), ar, fp)
        c.drawCircle(nextR.centerX(), nextR.centerY(), ar, fp)
        Glyphs.chevron(c, prevR.centerX() - ar * 0.06f, prevR.centerY(), ar * 0.9f, accent, -1)
        Glyphs.chevron(c, nextR.centerX() + ar * 0.06f, nextR.centerY(), ar * 0.9f, accent, 1)
        gridR.set(rx, pad + headH, w - pad, h - pad * 0.6f)
        val sel = selected?.takeIf { it.get(Calendar.YEAR) == shown.get(Calendar.YEAR) && it.get(Calendar.MONTH) == shown.get(Calendar.MONTH) }
        MonthDraw.draw(c, gridR, pal, accent, shown.get(Calendar.YEAR), shown.get(Calendar.MONTH), sel?.get(Calendar.DAY_OF_MONTH) ?: -1, false)
    }
}
