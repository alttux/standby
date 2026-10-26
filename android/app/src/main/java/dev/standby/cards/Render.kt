package dev.standby.cards

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import dev.standby.App
import dev.standby.data.Prefs
import dev.standby.ui.Fonts
import dev.standby.ui.Palette
import dev.standby.ui.blend
import dev.standby.ui.capHeight
import dev.standby.ui.contrastOn
import dev.standby.ui.dp
import dev.standby.ui.fade
import dev.standby.ui.fillPaint
import dev.standby.ui.lum
import dev.standby.ui.strokePaint
import dev.standby.ui.textPaint
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object TimeFmt {
    fun is24(): Boolean = when (Prefs.timeFormat) {
        "24" -> true
        "12" -> false
        else -> DateFormat.is24HourFormat(App.instance)
    }

    fun hm(cal: Calendar): String {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return if (is24()) String.format(Locale.US, "%02d:%02d", h, m)
        else String.format(Locale.US, "%d:%02d", if (h % 12 == 0) 12 else h % 12, m)
    }

    fun hms(cal: Calendar): String = hm(cal) + String.format(Locale.US, ":%02d", cal.get(Calendar.SECOND))

    fun shortDate(cal: Calendar): String =
        SimpleDateFormat("EEE d", Locale.getDefault()).format(cal.time).replace(".", "").uppercase(Locale.getDefault())

    fun longDate(cal: Calendar): String =
        SimpleDateFormat(if (dev.standby.Lang.ru) "EEEE, d MMMM" else "EEEE, MMMM d", Locale.getDefault()).format(cal.time)
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun monthName(cal: Calendar): String =
        SimpleDateFormat("LLLL", Locale.getDefault()).format(cal.time).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

object ClockDraw {
    private val p = fillPaint()
    private val tp = textPaint(Fonts.dMedium, 10f, 0, Paint.Align.CENTER)
    private val shadow = 0x33000000

    fun draw(style: String, c: Canvas, r: RectF, pal: Palette, color: Int, cal: Calendar, seconds: Boolean, date: Boolean) {
        when (style) {
            "analog_classic" -> analogClassic(c, r, pal, color, cal, seconds)
            "analog_bold" -> analogBold(c, r, pal, color, cal, seconds, date)
            "digital_thin" -> digitalThin(c, r, pal, color, cal, seconds, date)
            else -> digitalBold(c, r, pal, color, cal, seconds, date)
        }
    }

    private fun angles(cal: Calendar): Triple<Float, Float, Float> {
        val s = cal.get(Calendar.SECOND).toFloat()
        val m = cal.get(Calendar.MINUTE) + s / 60f
        val h = cal.get(Calendar.HOUR) + m / 60f
        return Triple(h * 30f, m * 6f, s * 6f)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, angle: Float, len: Float, w: Float, neck: Float, color: Int, sh: Boolean) {
        c.save()
        c.rotate(angle, cx, cy)
        if (sh) {
            c.translate(1.5f.dp, 1.5f.dp)
            hand(c, cx, cy, 0f, len, w, neck, shadow, false)
            c.translate(-1.5f.dp, -1.5f.dp)
        }
        p.color = color
        p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(cx - w * 0.22f, cy - neck - w / 2, cx + w * 0.22f, cy), w * 0.22f, w * 0.22f, p)
        c.drawRoundRect(RectF(cx - w / 2, cy - len, cx + w / 2, cy - neck), w / 2, w / 2, p)
        c.restore()
    }

    private fun secondHand(c: Canvas, cx: Float, cy: Float, angle: Float, len: Float, tail: Float, w: Float, color: Int, center: Int) {
        c.save()
        c.rotate(angle, cx, cy)
        val sp = strokePaint(color, w)
        c.drawLine(cx, cy + tail, cx, cy - len, sp)
        p.color = color
        c.drawCircle(cx, cy, w * 2.6f, p)
        p.color = center
        c.drawCircle(cx, cy, w * 1.1f, p)
        c.restore()
    }

    /** Classic StandBy analog face: numerals around, thin minute ticks, accent second hand. */
    fun analogClassic(c: Canvas, r: RectF, pal: Palette, accent: Int, cal: Calendar, seconds: Boolean) {
        val cx = r.centerX()
        val cy = r.centerY()
        val R = min(r.width(), r.height()) / 2f * 0.94f
        val tick = strokePaint(pal.text3, R * 0.012f)
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val hour = i % 5 == 0
            val inner = if (hour) R * 0.92f else R * 0.955f
            tick.color = if (hour) pal.text2 else pal.text3
            tick.strokeWidth = if (hour) R * 0.018f else R * 0.01f
            val sx = sin(a).toFloat()
            val cy0 = -cos(a).toFloat()
            c.drawLine(cx + sx * inner, cy + cy0 * inner, cx + sx * R, cy + cy0 * R, tick)
        }
        tp.typeface = Fonts.dMedium
        tp.textSize = R * 0.2f
        tp.color = pal.text
        tp.textAlign = Paint.Align.CENTER
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val x = cx + sin(a).toFloat() * R * 0.76f
            val y = cy - cos(a).toFloat() * R * 0.76f
            c.drawText(n.toString(), x, y + capHeight(tp) / 2f, tp)
        }
        val (ha, ma, sa) = angles(cal)
        hand(c, cx, cy, ha, R * 0.52f, R * 0.07f, R * 0.12f, pal.text, false)
        hand(c, cx, cy, ma, R * 0.84f, R * 0.07f, R * 0.12f, pal.text, false)
        p.color = pal.text
        c.drawCircle(cx, cy, R * 0.045f, p)
        if (seconds) secondHand(c, cx, cy, sa, R * 0.92f, R * 0.16f, R * 0.014f, accent, pal.bg)
    }

    /** Colourful face filling the whole rect with radial ticks along its edges. */
    fun analogBold(c: Canvas, r: RectF, pal: Palette, face: Int, cal: Calendar, seconds: Boolean, date: Boolean) {
        val m = min(r.width(), r.height())
        p.color = face
        val rad = if (r.width() >= c.width - 1 && r.height() >= c.height - 1) 0f else m * 0.08f
        c.drawRoundRect(r, rad, rad, p)
        val cx = r.centerX()
        val cy = r.centerY()
        val ink = if (lum(face) > 0.45f) blend(pal.red, 0xFF000000.toInt(), 0.45f) else blend(face, 0xFFFFFFFF.toInt(), 0.75f)
        val numeral = if (abs(lum(face) - lum(pal.red)) < 0.12f || face == pal.red) blend(face, 0xFF000000.toInt(), 0.6f) else blend(pal.red, 0xFF000000.toInt(), 0.25f)
        val inset = m * 0.06f
        val hw = r.width() / 2f - inset
        val hh = r.height() / 2f - inset
        val sp = strokePaint(ink, 1f)
        fun edge(i: Int): Triple<Float, Float, Float> {
            val a = Math.toRadians(i * 6.0)
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            val t = min(if (abs(dx) > 1e-4f) hw / abs(dx) else Float.MAX_VALUE, if (abs(dy) > 1e-4f) hh / abs(dy) else Float.MAX_VALUE)
            return Triple(dx, dy, t)
        }
        for (i in 0 until 60) {
            if (i % 15 == 0) continue
            val (dx, dy, t) = edge(i)
            val hour = i % 5 == 0
            val len = if (hour) m * 0.1f else m * 0.035f
            sp.strokeWidth = if (hour) m * 0.014f else m * 0.006f
            sp.color = if (hour) ink else fade(ink, 0.55f)
            c.drawLine(cx + dx * t, cy + dy * t, cx + dx * (t - len), cy + dy * (t - len), sp)
        }
        tp.typeface = Fonts.dBold
        tp.textSize = m * 0.22f
        tp.color = numeral
        tp.textAlign = Paint.Align.CENTER
        listOf(0 to "12", 15 to "3", 30 to "6", 45 to "9").forEach { (i, s) ->
            val (dx, dy, t) = edge(i)
            val inward = if (dy != 0f && abs(dy) > 0.5f) m * 0.11f else tp.measureText(s) * 0.62f
            c.drawText(s, cx + dx * (t - inward), cy + dy * (t - inward) + capHeight(tp) / 2f, tp)
        }
        if (date) {
            tp.typeface = Fonts.dMedium
            tp.textSize = m * 0.058f
            tp.color = ink
            c.drawText(if (seconds) TimeFmt.hms(cal) else TimeFmt.hm(cal), cx - hw * 0.42f, cy + capHeight(tp) / 2f, tp)
            c.drawText(TimeFmt.shortDate(cal), cx + hw * 0.42f, cy + capHeight(tp) / 2f, tp)
        }
        val (ha, ma, sa) = angles(cal)
        val white = 0xFFFFFFFF.toInt()
        hand(c, cx, cy, ha, m * 0.27f, m * 0.04f, m * 0.05f, white, true)
        hand(c, cx, cy, ma, m * 0.42f, m * 0.034f, m * 0.05f, white, true)
        p.color = white
        c.drawCircle(cx, cy, m * 0.026f, p)
        if (seconds) secondHand(c, cx, cy, sa, m * 0.45f, m * 0.08f, m * 0.008f, if (face == pal.yellow) 0xFF000000.toInt() else pal.yellow, face)
    }

    /** Huge heavy digits on a coloured background (StandBy "Digital"). */
    fun digitalBold(c: Canvas, r: RectF, pal: Palette, face: Int, cal: Calendar, seconds: Boolean, date: Boolean) {
        val rad = if (r.width() >= c.width - 1 && r.height() >= c.height - 1) 0f else min(r.width(), r.height()) * 0.08f
        p.color = face
        c.drawRoundRect(r, rad, rad, p)
        val ink = if (lum(face) > 0.3f) 0xFF0C0C0C.toInt() else 0xFFFFFFFF.toInt()
        val pad = r.height() * 0.09f
        if (date) {
            tp.typeface = Fonts.dBold
            tp.textSize = r.height() * 0.075f
            tp.color = ink
            tp.textAlign = Paint.Align.LEFT
            c.drawText(TimeFmt.shortDate(cal), r.left + pad, r.top + pad + capHeight(tp), tp)
        }
        val text = TimeFmt.hm(cal)
        tp.typeface = Fonts.black
        tp.textAlign = Paint.Align.LEFT
        tp.textScaleX = 0.84f
        tp.fontFeatureSettings = "tnum"
        tp.textSize = 100f
        val dotW = 0.18f
        val w100 = tp.measureText(text) + 100f * dotW
        val byW = (r.width() - pad * 2) * 0.96f / w100 * 100f
        val byH = r.height() * (if (date) 0.62f else 0.7f) / 0.727f
        tp.textSize = min(byW, byH)
        tp.color = ink
        val tw = tp.measureText(text)
        val total = tw + tp.textSize * dotW
        val x = r.centerX() - total / 2f
        val baseY = r.centerY() + capHeight(tp) / 2f + (if (date) r.height() * 0.05f else 0f)
        c.drawText(text, x, baseY, tp)
        val showDot = !seconds || cal.get(Calendar.SECOND) % 2 == 0
        if (showDot) {
            p.color = ink
            val dr = tp.textSize * 0.055f
            c.drawCircle(x + tw + tp.textSize * 0.11f, baseY - dr, dr, p)
        }
        tp.textScaleX = 1f
        tp.fontFeatureSettings = null
    }

    /** Thin, airy digits on the theme background. */
    fun digitalThin(c: Canvas, r: RectF, pal: Palette, color: Int, cal: Calendar, seconds: Boolean, date: Boolean) {
        val text = TimeFmt.hm(cal)
        val sec = String.format(Locale.US, "%02d", cal.get(Calendar.SECOND))
        tp.typeface = Fonts.thin
        tp.fontFeatureSettings = "tnum"
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 100f
        val secScale = 0.3f
        val w100 = tp.measureText(text) + if (seconds) tp.measureText(sec) * secScale + 8f else 0f
        val byW = r.width() * 0.9f / w100 * 100f
        val byH = r.height() * (if (date) 0.5f else 0.62f) / 0.727f
        val size = min(byW, byH)
        tp.textSize = size
        tp.color = color
        val tw = tp.measureText(text)
        val sw = if (seconds) tp.measureText(sec) * secScale + size * 0.08f else 0f
        val x = r.centerX() - (tw + sw) / 2f
        val baseY = r.centerY() + capHeight(tp) / 2f - (if (date) r.height() * 0.06f else 0f)
        c.drawText(text, x, baseY, tp)
        if (seconds) {
            tp.typeface = Fonts.light
            tp.textSize = size * secScale
            tp.color = pal.text2
            c.drawText(sec, x + tw + size * 0.08f, baseY, tp)
        }
        tp.fontFeatureSettings = null
        if (date) {
            tp.typeface = Fonts.dMedium
            tp.textSize = min(r.height() * 0.075f, r.width() * 0.06f)
            tp.color = pal.text2
            tp.textAlign = Paint.Align.CENTER
            c.drawText(TimeFmt.longDate(cal), r.centerX(), baseY + r.height() * 0.15f, tp)
        }
    }
}

object MonthDraw {
    private val tp = textPaint(Fonts.dMedium, 10f, 0, Paint.Align.CENTER)
    private val p = fillPaint()

    fun firstDow(): Int = when (Prefs.firstDay) {
        "mon" -> Calendar.MONDAY
        "sun" -> Calendar.SUNDAY
        else -> Calendar.getInstance().firstDayOfWeek
    }

    private class Geo(val cell: Float, val x0: Float, val gridTop: Float, val lead: Int, val days: Int, val rows: Int, val titleTop: Float, val wk: Int)

    private fun geo(r: RectF, year: Int, month: Int, title: Boolean): Geo {
        val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
        val lead = (cal.get(Calendar.DAY_OF_WEEK) - firstDow() + 7) % 7
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = ceil((lead + days) / 7f).toInt()
        val wk = if (Prefs.weekNumbers) 1 else 0
        val cols = 7 + wk
        val titleRows = if (title) 1.3f else 0f
        val cell = min(r.width() / cols, r.height() / (rows + 0.85f + titleRows))
        val total = cell * (rows + 0.85f + titleRows)
        val top = r.top + (r.height() - total) / 2f
        val x0 = r.left + (r.width() - cell * cols) / 2f + wk * cell
        return Geo(cell, x0, top + cell * (titleRows + 0.85f), lead, days, rows, top, wk)
    }

    fun draw(c: Canvas, r: RectF, pal: Palette, accent: Int, year: Int, month: Int, selected: Int = -1, title: Boolean = true) {
        val g = geo(r, year, month, title)
        val cell = g.cell
        val now = Calendar.getInstance()
        val todayDay = if (now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) == month) now.get(Calendar.DAY_OF_MONTH) else -1
        if (title) {
            val cal = Calendar.getInstance().apply { clear(); set(year, month, 1) }
            tp.typeface = Fonts.dBold
            tp.textSize = cell * 0.5f
            tp.color = accent
            tp.textAlign = Paint.Align.LEFT
            c.drawText(TimeFmt.monthName(cal).uppercase(), g.x0 + cell * 0.18f, g.titleTop + cell * 0.55f + capHeight(tp) / 2f, tp)
        }
        // weekday header
        val syms = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
        tp.typeface = Fonts.semibold
        tp.textSize = cell * 0.34f
        tp.textAlign = Paint.Align.CENTER
        val hy = g.gridTop - cell * 0.42f
        for (i in 0 until 7) {
            val dow = (firstDow() - 1 + i) % 7 + 1
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            tp.color = if (weekend && Prefs.weekends) pal.text3 else pal.text2
            val s = syms[dow].replace(".", "").take(1).uppercase()
            c.drawText(s, g.x0 + cell * (i + 0.5f), hy + capHeight(tp) / 2f, tp)
        }
        // days
        tp.typeface = Fonts.dMedium
        tp.textSize = cell * 0.44f
        tp.fontFeatureSettings = "tnum"
        val day = Calendar.getInstance()
        for (d in 1..g.days) {
            val idx = g.lead + d - 1
            val col = idx % 7
            val row = idx / 7
            val x = g.x0 + cell * (col + 0.5f)
            val y = g.gridTop + cell * (row + 0.5f)
            val dow = (firstDow() - 1 + col) % 7 + 1
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            var color = if (weekend && Prefs.weekends) pal.text2 else pal.text
            if (d == todayDay) {
                p.color = accent
                c.drawCircle(x, y, cell * 0.42f, p)
                color = contrastOn(accent)
                tp.typeface = Fonts.dBold
            } else if (d == selected) {
                val sp = strokePaint(accent, cell * 0.06f)
                c.drawCircle(x, y, cell * 0.4f, sp)
            }
            tp.color = color
            c.drawText(d.toString(), x, y + capHeight(tp) / 2f, tp)
            tp.typeface = Fonts.dMedium
            if (g.wk == 1 && (col == 0 || d == 1)) {
                day.clear(); day.firstDayOfWeek = firstDow(); day.minimalDaysInFirstWeek = 4; day.set(year, month, d)
                tp.textSize = cell * 0.3f
                tp.color = pal.text3
                c.drawText(day.get(Calendar.WEEK_OF_YEAR).toString(), g.x0 - cell * 0.5f, y + capHeight(tp) / 2f, tp)
                tp.textSize = cell * 0.44f
            }
        }
        tp.fontFeatureSettings = null
    }

    /** Returns the day of month under (x, y) or -1. */
    fun dayAt(r: RectF, year: Int, month: Int, title: Boolean, x: Float, y: Float): Int {
        val g = geo(r, year, month, title)
        val col = ((x - g.x0) / g.cell).toInt()
        val row = ((y - g.gridTop) / g.cell).toInt()
        if (x < g.x0 || y < g.gridTop || col > 6 || row >= g.rows) return -1
        val d = row * 7 + col - g.lead + 1
        return if (d in 1..g.days) d else -1
    }
}
