package dev.standby.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.standby.L
import dev.standby.R
import dev.standby.data.Mac
import dev.standby.data.Prefs
import dev.standby.data.Series
import dev.standby.ui.Fonts
import dev.standby.ui.Glyphs
import dev.standby.ui.Icons
import dev.standby.ui.Palette
import dev.standby.ui.Themes
import dev.standby.ui.blend
import dev.standby.ui.capHeight
import dev.standby.ui.dp
import dev.standby.ui.ellipsize
import dev.standby.ui.fade
import dev.standby.ui.fillPaint
import dev.standby.ui.lum
import dev.standby.ui.strokePaint
import dev.standby.ui.textPaint
import dev.standby.ui.withAlpha
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** "Mac not connected" placeholder shared by the companion-driven cards. */
private fun drawOffline(c: Canvas, w: Float, h: Float, pal: Palette, what: String) {
    val tp = textPaint(Fonts.semibold, h * 0.065f, pal.text, Paint.Align.CENTER)
    Icons.draw(c, R.drawable.ic_laptop, w / 2, h * 0.36f, h * 0.2f, pal.text3)
    c.drawText(L("Mac not connected", "Mac не подключён"), w / 2, h * 0.58f, tp)
    tp.typeface = Fonts.regular
    tp.textSize = h * 0.045f
    tp.color = pal.text2
    c.drawText(what, w / 2, h * 0.67f, tp)
    c.drawText(L("Run StandBy Companion on your Mac and connect the phone via USB",
        "Запустите StandBy Companion на Mac и подключите телефон по USB"), w / 2, h * 0.74f, tp)
}

private fun bytes(v: Double, perSec: Boolean = false): String {
    val units = if (dev.standby.Lang.ru) listOf("Б", "КБ", "МБ", "ГБ", "ТБ") else listOf("B", "KB", "MB", "GB", "TB")
    var x = v
    var i = 0
    while (x >= 1024 && i < units.size - 1) { x /= 1024; i++ }
    val s = if (x >= 100 || i == 0) x.roundToInt().toString() else String.format(Locale.getDefault(), "%.1f", x)
    return "$s ${units[i]}" + if (perSec) L("/s", "/с") else ""
}

// =============================================================================== stats

@SuppressLint("ViewConstructor")
class StatsCard(ctx: Context) : Card(ctx) {
    override val key = "stats"

    override fun onCompanion(topic: String) {
        if (topic == "stats" || topic == "conn") view.invalidate()
    }

    private val view = object : View(ctx) {
        private val tp = textPaint(Fonts.semibold, 10f, 0)
        private val fp = fillPaint()
        private val lp = strokePaint(0, 2f)
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(pal.bg)
            if (!Mac.connected) {
                drawOffline(c, w, h, pal, L("System stats come from the companion app", "Статистика приходит из приложения-компаньона"))
                return
            }
            val s = Mac.stats
            val gap = h * 0.035f
            val tw = (w - gap * 3) / 2
            val th = (h - gap * 3) / 2
            val n = Prefs.statsRange
            val cores = s?.optInt("cores") ?: 0
            val load = s?.optJSONArray("load")?.optDouble(0)
            tile(c, RectF(gap, gap, gap + tw, gap + th), pal, "CPU", R.drawable.ic_cpu, pal.blue,
                "${Mac.cpu.latest().roundToInt()}%",
                listOfNotNull(if (cores > 0) L("$cores cores", "$cores ядер") else null, load?.let { L("load ", "нагрузка ") + String.format(Locale.US, "%.2f", it) }).joinToString(" · "),
                listOf(Mac.cpu to pal.blue), 100f, n)
            tile(c, RectF(gap * 2 + tw, gap, gap * 2 + tw * 2, gap + th), pal, "GPU", R.drawable.ic_monitor, pal.purple,
                "${Mac.gpu.latest().roundToInt()}%", s?.optString("gpuName").orEmpty(),
                listOf(Mac.gpu to pal.purple), 100f, n)
            val used = s?.optDouble("memUsed") ?: 0.0
            val total = s?.optDouble("memTotal") ?: 0.0
            val swap = s?.optDouble("swapUsed") ?: 0.0
            tile(c, RectF(gap, gap * 2 + th, gap + tw, gap * 2 + th * 2), pal, L("Memory", "Память"), R.drawable.ic_layers, pal.green,
                "${Mac.mem.latest().roundToInt()}%",
                "${bytes(used)} / ${bytes(total)}" + if (swap > 0) " · swap ${bytes(swap)}" else "",
                listOf(Mac.mem to pal.green), 100f, n)
            val rx = Mac.netRx.latest().toDouble()
            val tx = Mac.netTx.latest().toDouble()
            tile(c, RectF(gap * 2 + tw, gap * 2 + th, gap * 2 + tw * 2, gap * 2 + th * 2), pal, L("Network", "Сеть"), R.drawable.ic_swap_vert, pal.orange,
                "↓ ${bytes(rx, true)}", "↑ ${bytes(tx, true)}" + (s?.optInt("battery", -1)?.takeIf { it >= 0 }?.let { " · ${L("battery", "батарея")} $it%" } ?: ""),
                listOf(Mac.netRx to pal.teal, Mac.netTx to pal.orange), null, n)
        }

        private fun tile(c: Canvas, r: RectF, pal: Palette, title: String, icon: Int, color: Int, value: String, sub: String,
                         series: List<Pair<Series, Int>>, maxY: Float?, n: Int) {
            val rad = r.height() * 0.14f
            fp.shader = null
            fp.color = pal.surface
            c.drawRoundRect(r, rad, rad, fp)
            val pad = r.height() * 0.1f
            val ih = r.height() * 0.11f
            Icons.draw(c, icon, r.left + pad + ih / 2, r.top + pad + ih / 2, ih, color)
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = Fonts.semibold
            tp.textSize = r.height() * 0.09f
            tp.color = color
            c.drawText(title.uppercase(), r.left + pad + ih * 1.35f, r.top + pad + ih / 2 + capHeight(tp) / 2, tp)
            tp.textAlign = Paint.Align.RIGHT
            tp.typeface = Fonts.dBold
            tp.textSize = r.height() * 0.17f
            tp.color = pal.text
            tp.fontFeatureSettings = "tnum"
            c.drawText(value, r.right - pad, r.top + pad + capHeight(tp) * 0.9f, tp)
            tp.fontFeatureSettings = null
            tp.typeface = Fonts.regular
            tp.textSize = r.height() * 0.075f
            tp.color = pal.text2
            tp.textAlign = Paint.Align.LEFT
            c.drawText(ellipsize(sub, tp, r.width() - pad * 2), r.left + pad, r.bottom - pad * 0.7f, tp)

            // graph
            val g = RectF(r.left + pad, r.top + r.height() * 0.36f, r.right - pad, r.bottom - pad * 0.7f - r.height() * 0.12f)
            val top = maxY ?: max(1024f * 16, series.maxOf { it.first.max(n) } * 1.15f)
            val grid = strokePaint(pal.separator, 1f)
            for (k in 0..2) {
                val y = g.top + g.height() * k / 2f
                c.drawLine(g.left, y, g.right, y, grid)
            }
            series.forEachIndexed { si, (sr, col) ->
                val cnt = min(n, sr.size)
                if (cnt < 2) return@forEachIndexed
                path.reset()
                val step = g.width() / (n - 1)
                val x0 = g.right - step * (cnt - 1)
                for (i in 0 until cnt) {
                    val x = x0 + step * i
                    val y = g.bottom - g.height() * (sr.last(n, i) / top).coerceIn(0f, 1f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                lp.color = col
                lp.strokeWidth = 1.6f.dp
                if (si == 0) {
                    val fill = Path(path)
                    fill.lineTo(g.right, g.bottom)
                    fill.lineTo(x0, g.bottom)
                    fill.close()
                    fp.shader = LinearGradient(0f, g.top, 0f, g.bottom, withAlpha(col, 0.38f), withAlpha(col, 0.02f), Shader.TileMode.CLAMP)
                    c.drawPath(fill, fp)
                    fp.shader = null
                }
                c.drawPath(path, lp)
            }
        }
    }

    init { addView(view) }
}

// =============================================================================== player

@SuppressLint("ViewConstructor")
class PlayerCard(ctx: Context) : Card(ctx) {
    override val key = "player"

    private var artColor = 0
    private var artFor: Bitmap? = null

    override fun onCompanion(topic: String) {
        if (topic == "media" || topic == "conn") view.invalidate()
    }

    private val view = object : View(ctx) {
        private val tp = textPaint(Fonts.semibold, 10f, 0)
        private val fp = fillPaint()
        private val prevR = RectF()
        private val playR = RectF()
        private val nextR = RectF()
        private val progR = RectF()
        private val volR = RectF()
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var drag = 0 // 1 progress, 2 volume
        private var dragValue = 0f
        private var downX = 0f
        private var downY = 0f
        private var lastVolSent = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val m = Mac.media
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    drag = when {
                        m != null && m.duration > 0 && grow(progR).contains(e.x, e.y) -> 1
                        Mac.volume >= 0 && grow(volR).contains(e.x, e.y) -> 2
                        else -> 0
                    }
                    if (drag != 0) {
                        parent.requestDisallowInterceptTouchEvent(true)
                        updateDrag(e.x)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (drag != 0) {
                    updateDrag(e.x)
                    if (drag == 2 && SystemClock.uptimeMillis() - lastVolSent > 120) {
                        lastVolSent = SystemClock.uptimeMillis()
                        Mac.command("volume.set", dragValue.toDouble())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (drag == 1 && m != null) Mac.command("media.seek", (dragValue * m.duration).toDouble())
                    else if (drag == 2) Mac.command("volume.set", dragValue.toDouble())
                    else if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                        when {
                            grow(playR).contains(e.x, e.y) -> Mac.command("media.playpause")
                            grow(prevR).contains(e.x, e.y) -> Mac.command("media.prev")
                            grow(nextR).contains(e.x, e.y) -> Mac.command("media.next")
                        }
                    }
                    drag = 0
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { drag = 0; invalidate() }
            }
            return true
        }

        private fun grow(r: RectF) = RectF(r).apply { inset(-14.dp, -14.dp) }

        private fun updateDrag(x: Float) {
            val r = if (drag == 1) progR else volR
            dragValue = ((x - r.left) / r.width()).coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            val w = width.toFloat()
            val h = height.toFloat()
            if (!Mac.connected) {
                c.drawColor(pal.bg)
                drawOffline(c, w, h, pal, L("Now playing comes from Music or Spotify on the Mac", "Данные берутся из Music или Spotify на Mac"))
                return
            }
            val m = Mac.media
            val art = Mac.artwork
            if (art !== artFor) {
                artFor = art
                artColor = if (art != null) Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) else 0
            }
            // background
            if (Prefs.playerTint && artColor != 0) {
                val top = blend(artColor, Color.BLACK, if (pal.dark) 0.45f else 0.1f)
                val bottom = blend(artColor, pal.bg, 0.75f)
                fp.shader = LinearGradient(0f, 0f, w, h, top, bottom, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, fp)
                fp.shader = null
            } else c.drawColor(pal.bg)
            val onTint = Prefs.playerTint && artColor != 0
            val fg = if (onTint) (if (lum(blend(artColor, Color.BLACK, 0.3f)) > 0.6f) Color.BLACK else Color.WHITE) else pal.text
            val fg2 = fade(fg, 0.62f)
            val track = fade(fg, 0.22f)

            val pad = h * 0.1f
            val aS = h - pad * 2
            val ar = RectF(pad, pad, pad + aS, pad + aS)
            val rad = aS * 0.07f
            if (art != null) {
                val sh = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val mx = Matrix()
                val sc = max(aS / art.width, aS / art.height)
                mx.setScale(sc, sc)
                mx.postTranslate(ar.left + (aS - art.width * sc) / 2, ar.top + (aS - art.height * sc) / 2)
                sh.setLocalMatrix(mx)
                fp.shader = sh
                c.drawRoundRect(ar, rad, rad, fp)
                fp.shader = null
            } else {
                fp.color = if (onTint) fade(fg, 0.15f) else pal.surface2
                c.drawRoundRect(ar, rad, rad, fp)
                Icons.draw(c, R.drawable.ic_music, ar.centerX(), ar.centerY(), aS * 0.4f, if (onTint) fg2 else pal.text3)
            }

            val x0 = ar.right + pad * 0.9f
            val x1 = w - pad
            val cw = x1 - x0
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.045f
            tp.color = fg2
            var y = pad + capHeight(tp)
            c.drawText((m?.app ?: L("Not playing", "Ничего не играет")).uppercase(), x0, y, tp)
            tp.typeface = Fonts.bold
            tp.textSize = h * 0.085f
            tp.color = fg
            y += h * 0.05f + capHeight(tp)
            c.drawText(ellipsize(m?.title?.ifEmpty { "—" } ?: L("Nothing playing", "Тишина"), tp, cw), x0, y, tp)
            tp.typeface = Fonts.medium
            tp.textSize = h * 0.06f
            tp.color = fg2
            y += h * 0.04f + capHeight(tp)
            c.drawText(ellipsize(listOf(m?.artist, m?.album).filter { !it.isNullOrEmpty() }.joinToString(" — "), tp, cw), x0, y, tp)

            // progress
            val pos = if (drag == 1 && m != null) dragValue * m.duration else m?.positionNow() ?: 0f
            val dur = m?.duration ?: 0f
            val py = y + h * 0.1f
            progR.set(x0, py - 3.dp, x1, py + 3.dp)
            val th = if (drag == 1) 7.dp else 5.dp
            fp.color = track
            c.drawRoundRect(RectF(x0, py - th / 2, x1, py + th / 2), th / 2, th / 2, fp)
            if (dur > 0) {
                fp.color = fg
                c.drawRoundRect(RectF(x0, py - th / 2, x0 + cw * (pos / dur).coerceIn(0f, 1f), py + th / 2), th / 2, th / 2, fp)
            }
            tp.typeface = Fonts.medium
            tp.textSize = h * 0.04f
            tp.color = fg2
            tp.fontFeatureSettings = "tnum"
            if (dur > 0) c.drawText(time(pos), x0, py + h * 0.07f, tp)
            tp.textAlign = Paint.Align.RIGHT
            c.drawText(if (dur > 0) "-" + time(dur - pos) else "", x1, py + h * 0.07f, tp)
            tp.fontFeatureSettings = null

            // transport
            val cy = py + h * 0.2f
            val s = h * 0.11f
            val cx = x0 + cw / 2
            playR.set(cx - s, cy - s, cx + s, cy + s)
            prevR.set(cx - cw * 0.3f - s, cy - s, cx - cw * 0.3f + s, cy + s)
            nextR.set(cx + cw * 0.3f - s, cy - s, cx + cw * 0.3f + s, cy + s)
            if (m?.playing == true) Glyphs.pause(c, cx, cy, s * 1.1f, fg) else Glyphs.play(c, cx + s * 0.06f, cy, s * 1.1f, fg)
            Glyphs.skip(c, prevR.centerX(), cy, s * 0.95f, fg, -1)
            Glyphs.skip(c, nextR.centerX(), cy, s * 0.95f, fg, 1)

            // volume
            val vy = h - pad
            val ic = h * 0.06f
            volR.set(x0 + ic * 1.6f, vy - 3.dp, x1 - ic * 1.6f, vy + 3.dp)
            Icons.draw(c, R.drawable.ic_volume_mute, x0 + ic / 2, vy, ic, fg2)
            Icons.draw(c, R.drawable.ic_volume_up, x1 - ic / 2, vy, ic, fg2)
            val vol = if (drag == 2) dragValue else Mac.volume.coerceAtLeast(0f)
            val vh = if (drag == 2) 7.dp else 5.dp
            fp.color = track
            c.drawRoundRect(RectF(volR.left, vy - vh / 2, volR.right, vy + vh / 2), vh / 2, vh / 2, fp)
            fp.color = fg
            c.drawRoundRect(RectF(volR.left, vy - vh / 2, volR.left + volR.width() * vol.coerceIn(0f, 1f), vy + vh / 2), vh / 2, vh / 2, fp)

            if (isActiveCard && m?.playing == true) postInvalidateDelayed(500)
        }

        private fun time(sec: Float): String {
            val s = max(0, sec.roundToInt())
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }

    init { addView(view) }
}

// =============================================================================== calls

@SuppressLint("ViewConstructor")
class CallCard(ctx: Context) : Card(ctx) {
    override val key = "call"

    private val shown = FloatArray(32)

    override fun onActiveChanged(active: Boolean) {
        Mac.subscribeViz(active && Prefs.callViz)
    }

    override fun onCompanion(topic: String) {
        when (topic) {
            "call" -> view.invalidate()
            "conn" -> {
                if (Mac.connected && isActiveCard) Mac.subscribeViz(Prefs.callViz)
                view.invalidate()
            }
        }
    }

    private val view = object : View(ctx) {
        private val tp = textPaint(Fonts.semibold, 10f, 0)
        private val fp = fillPaint()
        private val micR = RectF()
        private val outR = RectF()
        private val path = Path()
        private val tap = TapDetector(ctx) { x, y ->
            val st = Mac.call
            when {
                RectF(micR).apply { inset(-10.dp, -10.dp) }.contains(x, y) -> Mac.command("call.mic", !(st?.micMuted ?: false))
                RectF(outR).apply { inset(-10.dp, -10.dp) }.contains(x, y) -> Mac.command("call.output", !(st?.outputMuted ?: false))
            }
            invalidate()
            true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = tap.onTouch(e)

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(pal.bg)
            if (!Mac.connected) {
                drawOffline(c, w, h, pal, L("Call controls need the companion app", "Управление созвонами работает через компаньон"))
                return
            }
            val st = Mac.call
            val pad = h * 0.08f
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = Fonts.bold
            tp.textSize = h * 0.08f
            tp.color = pal.text
            val title = when {
                st == null -> L("Calls", "Созвоны")
                st.active -> st.app.ifEmpty { L("Microphone in use", "Микрофон используется") }
                else -> L("No active call", "Нет активного созвона")
            }
            c.drawText(ellipsize(title, tp, w * 0.6f), pad, pad + capHeight(tp), tp)
            tp.typeface = Fonts.medium
            tp.textSize = h * 0.05f
            tp.color = if (st?.active == true) pal.green else pal.text2
            val sub = if (st?.active == true && st.since > 0) {
                val s = ((System.currentTimeMillis() - st.since) / 1000).coerceAtLeast(0)
                L("In call · ", "Идёт созвон · ") + String.format(Locale.US, "%d:%02d", s / 60, s % 60)
            } else L("Mic and sound controls work system-wide", "Микрофон и звук управляются для всей системы")
            c.drawText(sub, pad, pad + h * 0.08f + h * 0.03f + capHeight(tp), tp)
            tp.textAlign = Paint.Align.RIGHT
            tp.typeface = Fonts.regular
            tp.textSize = h * 0.042f
            tp.color = pal.text3
            if (st != null) {
                c.drawText(ellipsize(L("Mic: ", "Микрофон: ") + st.inputName, tp, w * 0.35f), w - pad, pad + capHeight(tp), tp)
                c.drawText(ellipsize(L("Output: ", "Вывод: ") + st.outputName, tp, w * 0.35f), w - pad, pad + h * 0.06f + capHeight(tp), tp)
            }

            // visualizer
            val micMuted = st?.micMuted == true
            val target = Mac.viz
            val n = shown.size
            var moving = false
            val half = (n - 1) / 2f
            val avg = if (target.isEmpty()) 0f else target.average().toFloat()
            for (i in 0 until n) {
                // symmetric layout: voice formants (~300–500 Hz) in the centre, highs towards the edges;
                // mixing in the overall level keeps the centre alive for any source
                val d = abs(i - half) / half
                val band = if (target.isEmpty()) 0f
                else target[((0.3f + 0.7f * d) * (target.size - 1)).roundToInt().coerceIn(0, target.size - 1)]
                val src = if (!Prefs.callViz) 0f else (0.6f * band + 0.4f * avg * (1.15f - 0.3f * d)).coerceIn(0f, 1f)
                val k = if (src > shown[i]) 0.55f else 0.18f
                shown[i] += (src - shown[i]) * k
                if (abs(src - shown[i]) > 0.005f) moving = true
            }
            val vTop = h * 0.3f
            val vBot = h * 0.62f
            val mid = (vTop + vBot) / 2
            val col = if (micMuted) pal.text3 else pal.green
            if (Prefs.vizStyle == "wave") {
                path.reset()
                val step = (w - pad * 2) / (n - 1)
                for (i in 0 until n) {
                    val x = pad + step * i
                    val a = max(0.02f, shown[i]) * (vBot - vTop) / 2
                    if (i == 0) path.moveTo(x, mid - a) else path.lineTo(x, mid - a)
                }
                for (i in n - 1 downTo 0) {
                    val x = pad + step * i
                    val a = max(0.02f, shown[i]) * (vBot - vTop) / 2
                    path.lineTo(x, mid + a)
                }
                path.close()
                fp.shader = LinearGradient(0f, vTop, 0f, vBot, intArrayOf(withAlpha(col, 0.3f), col, withAlpha(col, 0.3f)), null, Shader.TileMode.CLAMP)
                c.drawPath(path, fp)
                fp.shader = null
            } else {
                val bw = (w - pad * 2) / n
                fp.color = col
                for (i in 0 until n) {
                    val v = shown[i]
                    val bh = max(bw * 0.5f, v * (vBot - vTop))
                    val x = pad + bw * i + bw * 0.2f
                    c.drawRoundRect(RectF(x, mid - bh / 2, x + bw * 0.6f, mid + bh / 2), bw * 0.3f, bw * 0.3f, fp)
                }
            }

            // buttons
            val r = h * 0.11f
            val by = h - pad - r - h * 0.06f
            micR.set(w / 2 - r * 3.2f - r, by - r, w / 2 - r * 3.2f + r, by + r)
            outR.set(w / 2 + r * 3.2f - r, by - r, w / 2 + r * 3.2f + r, by + r)
            button(c, micR, micMuted, if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                if (micMuted) L("Unmute", "Вкл. микрофон") else L("Mute", "Выкл. микрофон"), pal)
            val outMuted = st?.outputMuted == true
            button(c, outR, outMuted, if (outMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                if (outMuted) L("Sound on", "Вкл. звук") else L("Sound off", "Выкл. звук"), pal)

            if (isActiveCard && (moving || st?.active == true)) postInvalidateDelayed(if (moving) 33 else 1000)
        }

        private fun button(c: Canvas, r: RectF, on: Boolean, icon: Int, label: String, pal: Palette) {
            fp.color = if (on) pal.text else pal.surface2
            c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fp)
            Icons.draw(c, icon, r.centerX(), r.centerY(), r.width() * 0.46f, if (on) pal.bg else pal.text)
            tp.textAlign = Paint.Align.CENTER
            tp.typeface = Fonts.medium
            tp.textSize = height * 0.042f
            tp.color = pal.text2
            c.drawText(label, r.centerX(), r.bottom + height * 0.065f, tp)
        }
    }

    init { addView(view) }
}
