package dev.standby.cards

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
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
import dev.standby.data.TouchBarFrame
import dev.standby.ui.Fonts
import dev.standby.ui.Icons
import dev.standby.ui.Themes
import dev.standby.ui.capHeight
import dev.standby.ui.dp
import dev.standby.ui.ellipsize
import dev.standby.ui.fillPaint
import dev.standby.ui.strokePaint
import dev.standby.ui.textPaint
import dev.standby.ui.withAlpha
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Mac's real Touch Bar (system-rendered, streamed by the companion) laid out in several rows
 * so the 36:1 strip fits the phone's 16:9 screen. Rows are cut in the gaps between buttons.
 */
@SuppressLint("ViewConstructor")
class TouchBarCard(ctx: Context) : Card(ctx) {
    override val key = "touchbar"

    override fun onActiveChanged(active: Boolean) {
        Mac.subscribeTouchBar(active)
    }

    override fun onCompanion(topic: String) {
        when (topic) {
            "touchbar" -> view.invalidate()
            "conn" -> {
                if (Mac.connected && isActiveCard) Mac.subscribeTouchBar(true)
                view.invalidate()
            }
        }
    }

    /** One row: the source columns [srcFrom, srcTo) drawn into [content], inside the black [strip]. */
    private class Row(val srcFrom: Int, val srcTo: Int, val strip: RectF, val content: RectF)

    private val view = object : View(ctx) {
        private val tp = textPaint(Fonts.semibold, 10f, 0)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val fp = fillPaint()
        private var rows: List<Row> = emptyList()
        private var layoutFor: TouchBarFrame? = null
        private var layoutKey = ""
        private var scale = 1f

        // touch state: the press is forwarded once it is clearly not a card swipe
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var touchRow: Row? = null
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var pressed = false
        private var lastMove = 0L
        private var dragging = false
        private val hold = Runnable { if (touchRow != null && !pressed) press() }

        override fun onDraw(c: Canvas) {
            val pal = Themes.p
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(pal.bg)
            val f = Mac.touchBar
            val pad = h * 0.07f

            // header
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = Fonts.bold
            tp.textSize = h * 0.065f
            tp.color = pal.text
            c.drawText("Touch Bar", pad, pad + capHeight(tp), tp)
            val titleW = tp.measureText("Touch Bar")
            var hintW = 0f
            if (f != null && !f.input) {
                tp.textAlign = Paint.Align.RIGHT
                tp.typeface = Fonts.medium
                tp.textSize = h * 0.04f
                tp.color = pal.orange
                val hint = ellipsize(L("Taps need Accessibility on the Mac", "Для нажатий нужен «Универсальный доступ»"), tp, w * 0.5f)
                hintW = tp.measureText(hint) + h * 0.04f
                c.drawText(hint, w - pad, pad + capHeight(tp) * 1.2f, tp)
            }
            if (f != null && f.app.isNotEmpty()) {
                tp.textAlign = Paint.Align.LEFT
                tp.typeface = Fonts.medium
                tp.textSize = h * 0.05f
                tp.color = pal.text2
                val x = pad + titleW + h * 0.025f
                c.drawText(ellipsize("· " + f.app, tp, w - pad - hintW - x), x, pad + h * 0.065f * 0.727f, tp)
            }

            if (f == null) {
                drawPlaceholder(c, w, h)
                rows = emptyList()
                return
            }
            relayout(f, w, h, pad)
            rows.forEachIndexed { i, r ->
                drawStrip(c, r.strip)
                c.save()
                c.clipRect(r.strip)
                c.drawBitmap(f.bitmap, Rect(r.srcFrom, 0, r.srcTo, f.height), r.content, bmpPaint)
                c.restore()
                if (touchRow === r && pressed) {
                    fp.color = 0x18FFFFFF
                    c.drawRoundRect(r.strip, r.strip.height() * 0.2f, r.strip.height() * 0.2f, fp)
                }
            }
        }

        private fun drawPlaceholder(c: Canvas, w: Float, h: Float) {
            val pal = Themes.p
            // an empty strip as a hint of what will appear here
            val sh = h * 0.16f
            val strip = RectF(w * 0.08f, h * 0.36f, w * 0.92f, h * 0.36f + sh)
            drawStrip(c, strip)
            tp.textAlign = Paint.Align.CENTER
            tp.typeface = Fonts.semibold
            tp.textSize = h * 0.06f
            tp.color = pal.text
            val err = Mac.touchBarError
            val title = when {
                !Mac.connected -> L("Mac not connected", "Mac не подключён")
                err.isNotEmpty() -> L("Touch Bar unavailable", "Touch Bar недоступен")
                else -> L("Waiting for the Touch Bar…", "Ожидание Touch Bar…")
            }
            c.drawText(title, w / 2, strip.bottom + h * 0.14f, tp)
            tp.typeface = Fonts.regular
            tp.textSize = h * 0.042f
            tp.color = pal.text2
            val sub = if (Mac.connected && err.isNotEmpty()) "Mac: $err" else L("The companion turns on the Mac's built-in Touch Bar emulation and streams it here",
                "Компаньон включает встроенную в macOS эмуляцию Touch Bar и передаёт её сюда")
            c.drawText(ellipsize(sub, tp, w * 0.9f), w / 2, strip.bottom + h * 0.22f, tp)
            Icons.draw(c, R.drawable.ic_laptop, w / 2, strip.top - h * 0.12f, h * 0.12f, pal.text3)
        }

        /** Glossy black strip, like the Touch Bar glass. */
        private fun drawStrip(c: Canvas, r: RectF) {
            val rad = r.height() * 0.2f
            fp.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF0B0B0D.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, fp)
            fp.shader = null
            val edge = strokePaint(withAlpha(0xFF8E8E93.toInt(), if (Themes.p.dark) 0.28f else 0.45f), 1.dp)
            c.drawRoundRect(r, rad, rad, edge)
            // faint highlight along the top edge
            val hl = strokePaint(0x14FFFFFF, 1.dp)
            c.drawLine(r.left + rad, r.top + 1.5f.dp, r.right - rad, r.top + 1.5f.dp, hl)
        }

        /** Splits the frame into rows at button gaps and computes one common scale. */
        private fun relayout(f: TouchBarFrame, w: Float, h: Float, pad: Float) {
            val n = Prefs.touchBarRows.coerceIn(2, 4)
            val key = "$w/$h/$n/${f.width}/${f.empty.contentHashCode()}"
            if (f === layoutFor && key == layoutKey) return
            layoutFor = f
            layoutKey = key
            val segs = splitIntoRows(f, n)
            val top = pad + h * 0.065f + h * 0.07f
            val gap = h * 0.045f
            val stripPadV = f.height * 0.12f
            val availW = w - pad * 2 - h * 0.06f
            val maxSeg = segs.maxOfOrNull { it.second - it.first }?.toFloat() ?: f.width.toFloat()
            val count = max(1, segs.size)
            val availH = h - top - pad * 0.8f - gap * (count - 1)
            scale = min(availW / maxSeg, availH / count / (f.height + stripPadV * 2))
            val stripH = (f.height + stripPadV * 2) * scale
            val blockH = stripH * count + gap * (count - 1)
            var y = top + (h - top - pad * 0.8f - blockH) / 2
            val stripL = pad
            val stripR = w - pad
            rows = segs.mapIndexed { i, (a, b) ->
                val cw = (b - a) * scale
                val inner = h * 0.03f
                // first row hugs the left edge (esc), last row the right edge (Control Strip), others are centred
                val x = when {
                    i == 0 -> stripL + inner
                    i == segs.size - 1 -> stripR - inner - cw
                    else -> (w - cw) / 2
                }
                val strip = RectF(stripL, y, stripR, y + stripH)
                val content = RectF(x, y + stripPadV * scale, x + cw, y + stripPadV * scale + f.height * scale)
                y += stripH + gap
                Row(a, b, strip, content)
            }
        }

        /**
         * Button groups are separated by ≥ 12 px of background (buttons inside a group by 2–4 px, see REPORT).
         * Consecutive groups are distributed over at most [n] rows so that the widest row is as narrow as
         * possible (linear partition), which never splits a group such as ⏪ ▶ ⏩.
         * Returns pixel ranges [from, to) with a small margin.
         */
        private fun splitIntoRows(f: TouchBarFrame, n: Int): List<Pair<Int, Int>> {
            val e = f.empty
            val groups = ArrayList<IntArray>()
            var x = 0
            while (x < e.size) {
                if (e[x]) { x++; continue }
                var end = x
                var y = x
                while (y < e.size) {
                    if (!e[y]) { end = y; y++; continue }
                    var g = y
                    while (g < e.size && e[g]) g++
                    if (g - y >= 12 || g == e.size) break
                    y = g
                }
                groups.add(intArrayOf(x, end + 1))
                x = end + 1
            }
            if (groups.isEmpty()) return emptyList()
            val g = groups.size
            val k = min(n, g)
            fun width(i: Int, j: Int) = groups[j][1] - groups[i][0]   // groups i..j inclusive
            // best[r][j]: minimal max-width placing groups 0..j into r+1 rows; split[r][j]: first group of the last row
            val best = Array(k) { IntArray(g) { Int.MAX_VALUE } }
            val split = Array(k) { IntArray(g) }
            for (j in 0 until g) best[0][j] = width(0, j)
            for (r in 1 until k) for (j in r until g) for (i in r..j) {
                val v = max(best[r - 1][i - 1], width(i, j))
                if (v < best[r][j]) { best[r][j] = v; split[r][j] = i }
            }
            val ranges = ArrayList<Pair<Int, Int>>()
            var j = g - 1
            for (r in k - 1 downTo 0) {
                val i = if (r == 0) 0 else split[r][j]
                ranges.add(0, groups[i][0] to groups[j][1])
                j = i - 1
            }
            val m = (f.height * 0.12f).toInt()
            return ranges.map { (a, b) -> max(0, a - m) to min(f.width, b + m) }
        }

        // ---------------------------------------------------------------- input

        private fun rowAt(y: Float): Row? = rows.firstOrNull { y >= it.strip.top - 6.dp && y <= it.strip.bottom + 6.dp }

        /** Touch Bar point (origin bottom-left, as DFRFoundationPostEventWithMouseActivity expects). */
        private fun toPoint(r: Row, x: Float, y: Float): Pair<Float, Float>? {
            val f = Mac.touchBar ?: return null
            // a tap stays inside its row; a drag continues past the row's ends along the whole bar,
            // so a scrubber split over two rows (e.g. the share list) can be scrolled all the way
            val raw = r.srcFrom + (x - r.content.left) / scale
            val px = if (pressed && dragging) raw.coerceIn(0f, f.width - 1f) else raw.coerceIn(r.srcFrom.toFloat(), r.srcTo - 1f)
            val py = ((y - r.content.top) / scale).coerceIn(0f, f.height - 1f)
            return px * f.pw / f.width to f.ph - py * f.ph / f.height
        }

        private fun send(phase: String, x: Float, y: Float) {
            val r = touchRow ?: return
            val p = toPoint(r, x, y) ?: return
            Mac.touchBarTouch(phase, p.first, p.second)
        }

        private fun press() {
            pressed = true
            parent?.requestDisallowInterceptTouchEvent(true)
            send("down", downX, downY)
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchRow = rowAt(e.y)
                    pressed = false
                    dragging = false
                    downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis()
                    if (touchRow != null) {
                        // own the gesture on a strip, so horizontal swipes scroll the Touch Bar instead of
                        // switching launcher panes; vertical swipes are handed back to the card pager below
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // a held finger (e.g. long-press in the Control Strip) is forwarded after a short delay
                        postDelayed(hold, 180)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchRow == null) return true
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (!pressed) {
                        if (dy > slop && dy > dx) {           // a card swipe: give the gesture back to the pager
                            removeCallbacks(hold)
                            touchRow = null
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return true
                        }
                        if (dx > slop) {                      // scrubber scroll or slider drag
                            dragging = true
                            press()
                        }
                    } else if (dx > slop) dragging = true
                    if (pressed && SystemClock.uptimeMillis() - lastMove > 30) {
                        lastMove = SystemClock.uptimeMillis()
                        send("move", e.x, e.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(hold)
                    val r = touchRow
                    if (r != null) {
                        if (!pressed) {
                            // a quick tap: the Touch Bar ignores a release that arrives together with the press
                            send("down", downX, downY)
                            val p = toPoint(r, e.x, e.y)
                            if (p != null) postDelayed({ Mac.touchBarTouch("up", p.first, p.second) }, 60)
                        } else send("up", e.x, e.y)
                    }
                    touchRow = null
                    pressed = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold)
                    if (pressed) send("up", e.x, e.y)
                    touchRow = null
                    pressed = false
                    invalidate()
                }
            }
            return true
        }
    }

    init { addView(view) }
}
