package dev.standby.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import dev.standby.App
import kotlin.math.max
import kotlin.math.roundToInt

object Gfx {
    var density = 2f
        private set

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
    }
}

val Number.dp: Float get() = toFloat() * Gfx.density
val Number.idp: Int get() = (toFloat() * Gfx.density).roundToInt()

object Fonts {
    lateinit var regular: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var semibold: Typeface; private set
    lateinit var bold: Typeface; private set
    lateinit var thin: Typeface; private set
    lateinit var light: Typeface; private set
    lateinit var dMedium: Typeface; private set
    lateinit var dBold: Typeface; private set
    lateinit var black: Typeface; private set

    fun init(ctx: Context) {
        fun f(name: String) = try {
            Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        regular = f("inter_regular")
        medium = f("inter_medium")
        semibold = f("inter_semibold")
        bold = f("inter_bold")
        thin = f("interdisplay_thin")
        light = f("interdisplay_light")
        dMedium = f("interdisplay_medium")
        dBold = f("interdisplay_bold")
        black = f("interdisplay_black")
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(x: Int, y: Int) = (x + (y - x) * u).roundToInt()
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b))
    )
}

/** Replaces the alpha channel. */
fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

/** Multiplies the existing alpha channel. */
fun fade(c: Int, a: Float): Int = withAlpha(c, Color.alpha(c) / 255f * a)

fun lum(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

fun contrastOn(bg: Int): Int = if (lum(bg) > 0.62f) 0xFF000000.toInt() else Color.WHITE

fun textPaint(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = tf
        textSize = size
        this.color = color
        textAlign = align
    }

fun fillPaint(color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.FILL
}

fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Draws text so that its visual centre (ascent..descent) sits on cy. */
fun Canvas.drawTextMid(text: String, x: Float, cy: Float, p: Paint) {
    val fm = p.fontMetrics
    drawText(text, x, cy - (fm.ascent + fm.descent) / 2f, p)
}

/** Draws text so that the cap height is centred on cy (better for digits). */
fun Canvas.drawCapsMid(text: String, x: Float, cy: Float, p: Paint) {
    drawText(text, x, cy + capHeight(p) / 2f, p)
}

fun capHeight(p: Paint): Float = p.textSize * 0.727f // Inter cap height ratio

fun ellipsize(text: String, p: TextPaint, width: Float): String =
    TextUtils.ellipsize(text, p, max(0f, width), TextUtils.TruncateAt.END).toString()

/** Sets the text size so that [text] fits [maxW] without exceeding [maxSize]. */
fun Paint.fitWidth(text: String, maxW: Float, maxSize: Float) {
    textSize = maxSize
    val w = measureText(text)
    if (w > maxW && w > 0) textSize = maxSize * maxW / w
}

/** Material-style vector glyphs from res/drawable, tinted on draw. */
object Icons {
    private val cache = HashMap<Int, Drawable>()

    fun get(id: Int): Drawable = cache.getOrPut(id) { App.instance.getDrawable(id)!!.mutate() }

    fun draw(c: Canvas, id: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = get(id)
        val h = size / 2f
        d.setBounds((cx - h).roundToInt(), (cy - h).roundToInt(), (cx + h).roundToInt(), (cy + h).roundToInt())
        d.setTint(color)
        d.draw(c)
    }
}

/** SF-symbol-like glyphs drawn in code (rounded corners via CornerPathEffect). */
object Glyphs {
    private val path = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun prep(color: Int, s: Float) {
        p.color = color
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = s * 0.06f
        p.strokeJoin = Paint.Join.ROUND
        p.pathEffect = CornerPathEffect(s * 0.08f)
    }

    fun play(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prep(color, s)
        path.reset()
        path.moveTo(cx - s * 0.3f, cy - s * 0.4f)
        path.lineTo(cx + s * 0.42f, cy)
        path.lineTo(cx - s * 0.3f, cy + s * 0.4f)
        path.close()
        c.drawPath(path, p)
    }

    fun pause(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prep(color, s)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        val w = s * 0.2f
        val h = s * 0.4f
        val r = s * 0.05f
        c.drawRoundRect(RectF(cx - s * 0.3f, cy - h, cx - s * 0.3f + w, cy + h), r, r, p)
        c.drawRoundRect(RectF(cx + s * 0.1f, cy - h, cx + s * 0.1f + w, cy + h), r, r, p)
    }

    /** Two triangles; dir = 1 forward, -1 backward. */
    fun skip(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, dir: Int) {
        prep(color, s)
        path.reset()
        val h = s * 0.3f
        for (k in 0..1) {
            val x0 = cx + dir * (-s * 0.44f + k * s * 0.44f)
            path.moveTo(x0, cy - h)
            path.lineTo(x0 + dir * s * 0.44f, cy)
            path.lineTo(x0, cy + h)
            path.close()
        }
        c.drawPath(path, p)
    }

    fun chevron(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, dir: Int, width: Float = s * 0.14f) {
        val sp = strokePaint(color, width)
        path.reset()
        path.moveTo(cx - dir * s * 0.18f, cy - s * 0.36f)
        path.lineTo(cx + dir * s * 0.18f, cy)
        path.lineTo(cx - dir * s * 0.18f, cy + s * 0.36f)
        c.drawPath(path, sp)
    }

    fun check(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val sp = strokePaint(color, s * 0.13f)
        path.reset()
        path.moveTo(cx - s * 0.36f, cy + s * 0.02f)
        path.lineTo(cx - s * 0.1f, cy + s * 0.3f)
        path.lineTo(cx + s * 0.38f, cy - s * 0.32f)
        c.drawPath(path, sp)
    }
}
