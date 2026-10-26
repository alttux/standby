package dev.standby.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.standby.L
import dev.standby.data.Prefs
import dev.standby.data.WeatherRepo
import dev.standby.ui.Fonts
import dev.standby.ui.Themes
import dev.standby.ui.WeatherCodes
import dev.standby.ui.WeatherIcons
import dev.standby.ui.Wx
import dev.standby.ui.blend
import dev.standby.ui.capHeight
import dev.standby.ui.ellipsize
import dev.standby.ui.fillPaint
import dev.standby.ui.strokePaint
import dev.standby.ui.textPaint
import dev.standby.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class WeatherCard(ctx: Context) : Card(ctx) {
    override val key = "weather"

    private val sky = SkyView(ctx)
    private val content = ContentView(ctx)

    init {
        addView(sky)
        addView(content)
    }

    override fun onWeather() = refresh()

    override fun onActiveChanged(active: Boolean) {
        if (active) WeatherRepo.refreshIfStale()
    }

    private fun condition(): Pair<Wx, Boolean> {
        val d = WeatherRepo.data ?: return Wx.CLEAR to true
        return WeatherCodes.kind(d.code) to d.day
    }

    // ------------------------------------------------------------------ sky

    private inner class SkyView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = strokePaint(Color.WHITE, 1f)
        private val rnd = Random(7)
        private val stars = FloatArray(60 * 3) { rnd.nextFloat() }
        private val drops = FloatArray(80 * 3) { rnd.nextFloat() }
        private val flakes = FloatArray(70 * 4) { rnd.nextFloat() }
        private val clouds = FloatArray(6 * 3) { rnd.nextFloat() }
        private var lastFrame = 0L
        private var flash = 0f
        private var nextFlash = 0L
        private var cloudSprite: Bitmap? = null
        private var gradKey = ""

        private fun sprite(): Bitmap = cloudSprite ?: Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG)
            listOf(floatArrayOf(70f, 76f, 52f), floatArrayOf(128f, 58f, 62f), floatArrayOf(186f, 78f, 50f), floatArrayOf(128f, 86f, 56f)).forEach { (x, y, r) ->
                bp.shader = RadialGradient(x, y, r, intArrayOf(0x99FFFFFF.toInt(), 0x44FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r, bp)
            }
            cloudSprite = b
        }

        private fun skyColors(kind: Wx, day: Boolean): Pair<Int, Int> {
            val (top, bottom) = when (kind) {
                Wx.CLEAR -> if (day) 0xFF2A64C2 to 0xFF5B9BE6 else 0xFF0A0F24 to 0xFF27365F
                Wx.PARTLY -> if (day) 0xFF4176BC to 0xFF86AEDD else 0xFF121A31 to 0xFF374766
                Wx.CLOUDY -> if (day) 0xFF5B7591 to 0xFF97ACC2 else 0xFF1D2731 to 0xFF3B4957
                Wx.FOG -> if (day) 0xFF76838F to 0xFFB2BDC7 else 0xFF283036 to 0xFF49535C
                Wx.DRIZZLE, Wx.RAIN, Wx.HEAVY_RAIN, Wx.FREEZING -> if (day) 0xFF3B4F63 to 0xFF6C8093 else 0xFF121922 to 0xFF2B3744
                Wx.SNOW -> if (day) 0xFF7890AA to 0xFFC0D0E1 else 0xFF233142 to 0xFF4B5C70
                Wx.THUNDER -> if (day) 0xFF2A2D47 to 0xFF535A78 else 0xFF14152A to 0xFF34385A
            }
            val scheme = Prefs.scheme
            if (scheme == "ios") return top.toInt() to bottom.toInt()
            // tint the sky towards the active colour scheme
            val tint = Themes.find(scheme).dark.blue
            return blend(top.toInt(), tint, 0.22f) to blend(bottom.toInt(), tint, 0.22f)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val (kind, day) = condition()
            val (top, bottom) = skyColors(kind, day)
            val key = "$top/$bottom/$w/$h"
            if (key != gradKey) {
                p.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
                gradKey = key
            }
            p.alpha = 255
            c.drawRect(0f, 0f, w, h, p)

            val animate = Prefs.weatherAnim && isActiveCard
            val now = SystemClock.uptimeMillis()
            val t = if (animate) now / 1000f else 0f
            val dt = if (lastFrame == 0L || !animate) 0f else min(0.1f, (now - lastFrame) / 1000f)
            lastFrame = now
            val g = Paint(Paint.ANTI_ALIAS_FLAG)

            // sun or moon glow
            if (kind == Wx.CLEAR || kind == Wx.PARTLY) {
                val pulse = 1f + 0.04f * sin(t * 0.6f)
                val r = h * 0.95f * pulse
                val a = if (day) 0x55 else 0x22
                g.shader = RadialGradient(w * 0.16f, -h * 0.12f, r, intArrayOf((a shl 24) or 0xFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, g)
            }
            // stars
            if (!day && (kind == Wx.CLEAR || kind == Wx.PARTLY)) {
                g.shader = null
                for (i in 0 until 60) {
                    val x = stars[i * 3] * w
                    val y = stars[i * 3 + 1] * h * 0.62f
                    val ph = stars[i * 3 + 2] * 6.28f
                    g.color = withAlpha(Color.WHITE, 0.25f + 0.45f * (0.5f + 0.5f * sin(t * (0.6f + stars[i * 3 + 2]) + ph)))
                    c.drawCircle(x, y, 0.6f + stars[i * 3 + 2] * 1.4f, g)
                }
            }
            // clouds
            val nClouds = when (kind) {
                Wx.CLEAR -> 0
                Wx.PARTLY -> 3
                else -> 6
            }
            if (nClouds > 0) {
                val sp = sprite()
                val cp = Paint(Paint.FILTER_BITMAP_FLAG)
                cp.alpha = when (kind) {
                    Wx.PARTLY -> 120
                    Wx.CLOUDY, Wx.FOG -> 150
                    else -> 90
                }
                for (i in 0 until nClouds) {
                    val cw = w * (0.45f + clouds[i * 3 + 2] * 0.4f)
                    val ch = cw * 0.5f
                    val speed = 6f + clouds[i * 3 + 2] * 10f
                    val span = w + cw
                    val x = ((clouds[i * 3] * span + t * speed) % span) - cw
                    val y = clouds[i * 3 + 1] * h * (if (kind == Wx.FOG) 0.9f else 0.45f) - ch * 0.3f
                    c.drawBitmap(sp, null, RectF(x, y, x + cw, y + ch), cp)
                }
            }
            // precipitation
            when (kind) {
                Wx.RAIN, Wx.HEAVY_RAIN, Wx.DRIZZLE, Wx.FREEZING, Wx.THUNDER -> {
                    val n = when (kind) { Wx.HEAVY_RAIN, Wx.THUNDER -> 80; Wx.DRIZZLE -> 35; else -> 55 }
                    line.strokeWidth = if (kind == Wx.DRIZZLE) 1.2f else 1.6f
                    line.color = withAlpha(Color.WHITE, 0.32f)
                    for (i in 0 until n) {
                        val speed = 0.9f + drops[i * 3 + 2] * 0.6f
                        drops[i * 3 + 1] = (drops[i * 3 + 1] + dt * speed) % 1.1f
                        val x = drops[i * 3] * (w + h * 0.15f)
                        val y = drops[i * 3 + 1] * h * 1.1f - h * 0.05f
                        val len = h * (if (kind == Wx.DRIZZLE) 0.025f else 0.05f) * speed
                        c.drawLine(x, y, x - len * 0.18f, y + len, line)
                    }
                }
                Wx.SNOW -> {
                    g.shader = null
                    for (i in 0 until 70) {
                        val sp = 0.06f + flakes[i * 4 + 2] * 0.08f
                        flakes[i * 4 + 1] = (flakes[i * 4 + 1] + dt * sp) % 1.05f
                        val x = flakes[i * 4] * w + sin(t * 0.8f + flakes[i * 4 + 3] * 6.28f) * h * 0.02f
                        val y = flakes[i * 4 + 1] * h * 1.05f - h * 0.02f
                        g.color = withAlpha(Color.WHITE, 0.5f + flakes[i * 4 + 2] * 0.4f)
                        c.drawCircle(x, y, 1.2f + flakes[i * 4 + 2] * 2.4f, g)
                    }
                }
                else -> {}
            }
            if (kind == Wx.THUNDER && animate) {
                if (now > nextFlash) {
                    flash = 1f
                    nextFlash = now + 5000 + rnd.nextInt(7000)
                }
                if (flash > 0f) {
                    g.shader = null
                    g.color = withAlpha(Color.WHITE, flash * 0.35f)
                    c.drawRect(0f, 0f, w, h, g)
                    flash = max(0f, flash - dt * 3f)
                }
            }
            if (animate) {
                val frame = when (kind) {
                    Wx.RAIN, Wx.HEAVY_RAIN, Wx.DRIZZLE, Wx.FREEZING, Wx.THUNDER, Wx.SNOW -> 33L
                    else -> 66L
                }
                postInvalidateDelayed(frame)
            } else lastFrame = 0L
        }
    }

    // ------------------------------------------------------------------ content

    private inner class ContentView(ctx: Context) : View(ctx) {
        private val tp = textPaint(Fonts.semibold, 10f, Color.WHITE)
        private val dot = fillPaint()
        private val tap = TapDetector(ctx) { _, _ ->
            if (WeatherRepo.data == null || WeatherRepo.error != null) WeatherRepo.refresh()
            else Prefs.weatherDaily = !Prefs.weatherDaily
            invalidate()
            true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = tap.onTouch(e)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = h * 0.075f
            val pal = Themes.p
            val d = WeatherRepo.data
            val white = Color.WHITE
            val dim = withAlpha(white, 0.75f)
            val loc = Locale.getDefault()

            // clock in the top right corner, like the reference
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.065f
            tp.color = white
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(TimeFmt.hm(Calendar.getInstance()), w - pad, pad + capHeight(tp), tp)

            if (d == null) {
                tp.textAlign = Paint.Align.CENTER
                tp.typeface = Fonts.semibold
                tp.textSize = h * 0.065f
                val msg = when {
                    !WeatherRepo.hasLocation() -> L("Choose a location", "Выберите город")
                    WeatherRepo.loading -> L("Loading weather…", "Загрузка погоды…")
                    WeatherRepo.error != null -> L("Weather unavailable", "Погода недоступна")
                    else -> L("Loading weather…", "Загрузка погоды…")
                }
                c.drawText(msg, w / 2, h / 2, tp)
                tp.typeface = Fonts.regular
                tp.textSize = h * 0.045f
                tp.color = dim
                val sub = when {
                    !WeatherRepo.hasLocation() -> L("Settings → Weather → Location", "Настройки → Погода → Город")
                    WeatherRepo.error != null -> L("Tap to retry · ", "Нажмите, чтобы повторить · ") + WeatherRepo.error
                    else -> ""
                }
                c.drawText(ellipsize(sub, tp, w - pad * 2), w / 2, h / 2 + h * 0.08f, tp)
                return
            }

            val today = d.days.firstOrNull { sameDay(it.time, System.currentTimeMillis(), d.tz) } ?: d.days.firstOrNull()

            // condition line
            val iconS = h * 0.075f
            WeatherIcons.draw(c, WeatherCodes.kind(d.code), d.day, pad + iconS / 2, pad + iconS * 0.5f, iconS, pal)
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.058f
            tp.color = white
            c.drawText(ellipsize(WeatherCodes.name(d.code, d.day), tp, w * 0.5f), pad + iconS * 1.25f, pad + iconS * 0.5f + capHeight(tp) / 2, tp)
            tp.typeface = Fonts.medium
            tp.textSize = h * 0.052f
            val hl = if (today != null) "H:${WeatherRepo.temp(today.max)}  L:${WeatherRepo.temp(today.min)}".let {
                if (dev.standby.Lang.ru) it.replace("H:", "Макс.: ").replace("L:", "мин.: ") else it
            } else ""
            val y2 = pad + iconS + h * 0.03f + capHeight(tp)
            c.drawText(hl, pad, y2, tp)

            // big temperature
            tp.typeface = Fonts.light
            tp.textSize = h * 0.34f
            c.drawText(WeatherRepo.temp(d.temp), pad - tp.textSize * 0.04f, y2 + h * 0.04f + capHeight(tp), tp)

            // details on the right
            tp.textAlign = Paint.Align.RIGHT
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.05f
            var ry = pad + h * 0.065f * 0.727f + h * 0.05f + capHeight(tp)
            c.drawText(ellipsize(Prefs.city, tp, w * 0.4f), w - pad, ry, tp)
            tp.typeface = Fonts.regular
            tp.textSize = h * 0.045f
            tp.color = dim
            listOf(
                L("Feels like ", "Ощущается как ") + WeatherRepo.temp(d.feels),
                L("Humidity ", "Влажность ") + d.humidity + "%",
                L("Wind ", "Ветер ") + WeatherRepo.wind(d.wind),
            ).forEach {
                ry += h * 0.07f
                c.drawText(it, w - pad, ry, tp)
            }
            if (WeatherRepo.error != null || System.currentTimeMillis() - d.fetched > 3 * 3600_000L) {
                ry += h * 0.07f
                tp.textSize = h * 0.038f
                c.drawText(L("Updated ", "Обновлено ") + SimpleDateFormat("d MMM HH:mm", loc).format(Date(d.fetched)), w - pad, ry, tp)
            }

            // bottom strip: hourly or 7 days
            val stripTop = h * 0.6f
            val stripBottom = h - pad - h * 0.05f
            val sh = stripBottom - stripTop
            val daily = Prefs.weatherDaily
            data class Col(val label: String, val kind: Wx, val day: Boolean, val temp: String, val low: String?, val precip: Int)
            val cols = ArrayList<Col>()
            if (!daily) {
                val now = System.currentTimeMillis()
                val i0 = d.hours.indexOfLast { it.time <= now }.coerceAtLeast(0)
                val fmt = SimpleDateFormat(if (TimeFmt.is24()) "HH" else "ha", Locale.US).apply { timeZone = d.tz }
                var i = i0
                while (cols.size < 6 && i < d.hours.size) {
                    val hr = d.hours[i]
                    val label = if (i == i0) L("Now", "Сейчас") else fmt.format(Date(hr.time)).let { if (TimeFmt.is24()) it else it.uppercase() }
                    cols.add(Col(label, WeatherCodes.kind(hr.code), hr.day, WeatherRepo.temp(if (i == i0) d.temp else hr.temp), null, hr.precip))
                    i += 2
                }
            } else {
                val start = d.days.indexOf(today).coerceAtLeast(0)
                val fmt = SimpleDateFormat("EEE", loc).apply { timeZone = d.tz }
                for (k in start until min(d.days.size, start + 7)) {
                    val dd = d.days[k]
                    val label = if (k == start) L("Today", "Сегодня") else fmt.format(Date(dd.time)).replace(".", "").replaceFirstChar { it.titlecase(loc) }
                    cols.add(Col(label, WeatherCodes.kind(dd.code), true, WeatherRepo.temp(dd.max), WeatherRepo.temp(dd.min), dd.precip))
                }
            }
            if (cols.isNotEmpty()) {
                val cw = (w - pad * 2) / cols.size
                cols.forEachIndexed { i, col ->
                    val cx = pad + cw * (i + 0.5f)
                    tp.textAlign = Paint.Align.CENTER
                    tp.typeface = Fonts.semibold
                    tp.textSize = h * 0.048f
                    tp.color = white
                    c.drawText(ellipsize(col.label, tp, cw), cx, stripTop + capHeight(tp), tp)
                    val iy = stripTop + sh * 0.45f
                    WeatherIcons.draw(c, col.kind, col.day, cx, iy, h * 0.1f, pal)
                    if (col.precip >= 30) {
                        tp.typeface = Fonts.semibold
                        tp.textSize = h * 0.034f
                        tp.color = blend(pal.teal, white, 0.35f)
                        c.drawText("${col.precip}%", cx, iy + h * 0.085f, tp)
                    }
                    tp.typeface = Fonts.medium
                    tp.textSize = h * 0.055f
                    tp.color = white
                    if (col.low == null) {
                        c.drawText(col.temp, cx, stripBottom, tp)
                    } else {
                        val a = col.temp + " "
                        val tw = tp.measureText(a)
                        tp.textSize = h * 0.045f
                        val lw = tp.measureText(col.low)
                        tp.textSize = h * 0.055f
                        tp.textAlign = Paint.Align.LEFT
                        val x0 = cx - (tw + lw) / 2
                        c.drawText(a, x0, stripBottom, tp)
                        tp.textSize = h * 0.045f
                        tp.color = withAlpha(white, 0.6f)
                        c.drawText(col.low, x0 + tw, stripBottom, tp)
                    }
                }
            }
            // page dots (hourly / daily)
            val dy = h - pad * 0.55f
            for (i in 0..1) {
                dot.color = withAlpha(white, if ((i == 1) == daily) 0.95f else 0.4f)
                c.drawCircle(w / 2 + (i - 0.5f) * h * 0.035f, dy, h * 0.009f, dot)
            }
        }

        private fun sameDay(a: Long, b: Long, tz: java.util.TimeZone): Boolean {
            val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = tz }
            return f.format(Date(a)) == f.format(Date(b))
        }
    }
}
