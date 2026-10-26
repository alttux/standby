package dev.standby.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.standby.L
import kotlin.math.cos
import kotlin.math.sin

enum class Wx { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, FREEZING, SNOW, THUNDER }

object WeatherCodes {
    fun kind(code: Int): Wx = when (code) {
        0, 1 -> Wx.CLEAR
        2 -> Wx.PARTLY
        3 -> Wx.CLOUDY
        45, 48 -> Wx.FOG
        51, 53, 55 -> Wx.DRIZZLE
        56, 57, 66, 67 -> Wx.FREEZING
        61, 63, 80, 81 -> Wx.RAIN
        65, 82 -> Wx.HEAVY_RAIN
        71, 73, 75, 77, 85, 86 -> Wx.SNOW
        95, 96, 99 -> Wx.THUNDER
        else -> Wx.CLOUDY
    }

    fun name(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) L("Sunny", "Солнечно") else L("Clear", "Ясно")
        1 -> if (day) L("Mostly Sunny", "Преим. солнечно") else L("Mostly Clear", "Преим. ясно")
        2 -> L("Partly Cloudy", "Переменная облачность")
        3 -> L("Cloudy", "Облачно")
        45, 48 -> L("Fog", "Туман")
        51, 53, 55 -> L("Drizzle", "Морось")
        56, 57 -> L("Freezing Drizzle", "Ледяная морось")
        61, 80 -> L("Light Rain", "Небольшой дождь")
        63, 81 -> L("Rain", "Дождь")
        65, 82 -> L("Heavy Rain", "Ливень")
        66, 67 -> L("Freezing Rain", "Ледяной дождь")
        71, 85 -> L("Light Snow", "Небольшой снег")
        73 -> L("Snow", "Снег")
        75, 86 -> L("Heavy Snow", "Снегопад")
        77 -> L("Snow Grains", "Снежная крупа")
        95 -> L("Thunderstorm", "Гроза")
        96, 99 -> L("Thunderstorm, Hail", "Гроза с градом")
        else -> "—"
    }
}

/** Multicolour weather glyphs in the spirit of SF Symbols. */
object WeatherIcons {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tmp = Path()
    private val tmp2 = Path()

    fun draw(c: Canvas, kind: Wx, day: Boolean, cx: Float, cy: Float, s: Float, p: Palette) {
        val sun = p.yellow
        val cloud = Color.WHITE
        val drop = blend(p.teal, Color.WHITE, 0.15f)
        when (kind) {
            Wx.CLEAR -> if (day) sun(c, cx, cy, s, sun) else moon(c, cx, cy, s * 0.95f, 0xFFE5E5EA.toInt())
            Wx.PARTLY -> {
                if (day) sun(c, cx - s * 0.16f, cy - s * 0.14f, s * 0.72f, sun)
                else moon(c, cx - s * 0.12f, cy - s * 0.16f, s * 0.62f, 0xFFE5E5EA.toInt())
                cloud(c, cx + s * 0.06f, cy + s * 0.12f, s * 0.82f, cloud)
            }
            Wx.CLOUDY -> {
                cloud(c, cx + s * 0.14f, cy - s * 0.1f, s * 0.62f, 0xFFB8BEC8.toInt())
                cloud(c, cx - s * 0.04f, cy + s * 0.08f, s * 0.86f, cloud)
            }
            Wx.FOG -> {
                cloud(c, cx, cy - s * 0.12f, s * 0.86f, cloud)
                stroke.color = 0xFFD1D5DB.toInt()
                stroke.strokeWidth = s * 0.07f
                c.drawLine(cx - s * 0.36f, cy + s * 0.26f, cx + s * 0.3f, cy + s * 0.26f, stroke)
                c.drawLine(cx - s * 0.26f, cy + s * 0.4f, cx + s * 0.38f, cy + s * 0.4f, stroke)
            }
            Wx.DRIZZLE -> {
                cloud(c, cx, cy - s * 0.14f, s * 0.86f, cloud)
                fill.color = drop
                for (i in 0..2) c.drawCircle(cx - s * 0.2f + i * s * 0.2f, cy + s * 0.3f + (i % 2) * s * 0.08f, s * 0.045f, fill)
            }
            Wx.RAIN, Wx.HEAVY_RAIN, Wx.FREEZING -> {
                cloud(c, cx, cy - s * 0.14f, s * 0.86f, cloud)
                stroke.color = drop
                stroke.strokeWidth = s * 0.065f
                val n = if (kind == Wx.HEAVY_RAIN) 4 else 3
                val step = s * 0.56f / (n - 1)
                for (i in 0 until n) {
                    val x = cx - s * 0.26f + i * step
                    val y = cy + s * 0.22f
                    c.drawLine(x + s * 0.05f, y, x - s * 0.04f, y + s * 0.2f, stroke)
                }
                if (kind == Wx.FREEZING) {
                    fill.color = Color.WHITE
                    c.drawCircle(cx + s * 0.34f, cy + s * 0.36f, s * 0.05f, fill)
                }
            }
            Wx.SNOW -> {
                cloud(c, cx, cy - s * 0.14f, s * 0.86f, cloud)
                fill.color = Color.WHITE
                for (i in 0..2) flake(c, cx - s * 0.24f + i * s * 0.24f, cy + s * 0.32f + (i % 2) * s * 0.06f, s * 0.08f)
            }
            Wx.THUNDER -> {
                cloud(c, cx, cy - s * 0.14f, s * 0.86f, cloud)
                fill.color = sun
                tmp.reset()
                tmp.moveTo(cx + s * 0.04f, cy + s * 0.1f)
                tmp.lineTo(cx - s * 0.12f, cy + s * 0.32f)
                tmp.lineTo(cx, cy + s * 0.32f)
                tmp.lineTo(cx - s * 0.06f, cy + s * 0.5f)
                tmp.lineTo(cx + s * 0.14f, cy + s * 0.24f)
                tmp.lineTo(cx + s * 0.02f, cy + s * 0.24f)
                tmp.close()
                c.drawPath(tmp, fill)
            }
        }
    }

    fun sun(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, s * 0.19f, fill)
        stroke.color = color
        stroke.strokeWidth = s * 0.07f
        for (i in 0 until 8) {
            val a = Math.PI * i / 4
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.3f, cy + sa * s * 0.3f, cx + ca * s * 0.42f, cy + sa * s * 0.42f, stroke)
        }
    }

    fun moon(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        tmp.reset()
        tmp.addCircle(cx, cy, s * 0.32f, Path.Direction.CW)
        tmp2.reset()
        tmp2.addCircle(cx + s * 0.2f, cy - s * 0.14f, s * 0.27f, Path.Direction.CW)
        tmp.op(tmp2, Path.Op.DIFFERENCE)
        fill.color = color
        c.drawPath(tmp, fill)
    }

    /** Cloud of width w (height ≈ 0.62w) centred on cx, cy. */
    fun cloud(c: Canvas, cx: Float, cy: Float, w: Float, color: Int) {
        tmp.reset()
        tmp.addRoundRect(RectF(cx - w * 0.46f, cy - w * 0.02f, cx + w * 0.46f, cy + w * 0.26f), w * 0.14f, w * 0.14f, Path.Direction.CW)
        tmp.addCircle(cx - w * 0.2f, cy + w * 0.02f, w * 0.18f, Path.Direction.CW)
        tmp.addCircle(cx + w * 0.04f, cy - w * 0.06f, w * 0.25f, Path.Direction.CW)
        tmp.addCircle(cx + w * 0.28f, cy + w * 0.06f, w * 0.16f, Path.Direction.CW)
        tmp.fillType = Path.FillType.WINDING
        fill.color = color
        c.drawPath(tmp, fill)
    }

    private fun flake(c: Canvas, x: Float, y: Float, r: Float) {
        stroke.color = Color.WHITE
        stroke.strokeWidth = r * 0.45f
        for (i in 0 until 3) {
            val a = Math.PI * i / 3
            val dx = (cos(a) * r).toFloat()
            val dy = (sin(a) * r).toFloat()
            c.drawLine(x - dx, y - dy, x + dx, y + dy, stroke)
        }
    }
}
