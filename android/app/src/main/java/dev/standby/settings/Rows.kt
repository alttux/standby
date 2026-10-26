package dev.standby.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.standby.ui.Fonts
import dev.standby.ui.Glyphs
import dev.standby.ui.IosSwitch
import dev.standby.ui.Segmented
import dev.standby.ui.Themes
import dev.standby.ui.dp
import dev.standby.ui.fillPaint
import dev.standby.ui.idp

fun Context.label(text: String, size: Float = 15f, color: Int = Themes.p.text, tf: android.graphics.Typeface = Fonts.regular): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(color)
        typeface = tf
        includeFontPadding = false
    }

/** Rounded square icon tile with a white glyph, as in iOS Settings. */
@SuppressLint("ViewConstructor")
class IconTile(ctx: Context, private val icon: Int, private val color: Int, private val size: Int = 28) : View(ctx) {
    private val p = fillPaint()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size.idp, size.idp)
    override fun onDraw(c: Canvas) {
        p.color = color
        val s = width.toFloat()
        c.drawRoundRect(0f, 0f, s, s, s * 0.23f, s * 0.23f, p)
        dev.standby.ui.Icons.draw(c, icon, s / 2, s / 2, s * 0.64f, 0xFFFFFFFF.toInt())
    }
}

/** Chevron / checkmark accessory. */
@SuppressLint("ViewConstructor")
class Accessory(ctx: Context, private val kind: Int) : View(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(if (kind == CHECK) 22.idp else 14.idp, 22.idp)
    override fun onDraw(c: Canvas) {
        val pal = Themes.p
        if (kind == CHEVRON) Glyphs.chevron(c, width / 2f, height / 2f, 13.dp, pal.text3, 1, 2.2f.dp)
        else Glyphs.check(c, width / 2f, height / 2f, 16.dp, pal.accent)
    }
    companion object {
        const val CHEVRON = 0
        const val CHECK = 1
    }
}

/** A single grouped-table cell; draws its own top separator. */
class RowLayout(ctx: Context) : LinearLayout(ctx) {
    var separator = false
    var sepInset = 16.dp
    private val sp = Paint().apply { strokeWidth = 0.5f.dp }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 44.idp
        setPadding(16.idp, 6.idp, 14.idp, 6.idp)
        setWillNotDraw(false)
    }

    fun clickable(on: () -> Unit) {
        val pal = Themes.p
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pal.surface2))
            addState(intArrayOf(), ColorDrawable(0))
        }
        setOnClickListener { on() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (separator) {
            sp.color = Themes.p.separator
            canvas.drawLine(sepInset, 0f, width.toFloat(), 0f, sp)
        }
    }
}

class GroupBuilder(val pane: SettingsPane, val box: LinearLayout) {
    val ctx: Context get() = pane.context

    private fun add(row: RowLayout): RowLayout {
        row.separator = box.childCount > 0
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    fun row(
        title: String, sub: String? = null, icon: Int = 0, iconColor: Int = 0, value: String? = null,
        trailing: View? = null, accessory: Int = -1, titleColor: Int = Themes.p.text, center: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): RowLayout {
        val r = RowLayout(ctx)
        if (icon != 0) {
            r.addView(IconTile(ctx, icon, iconColor), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12.idp })
            r.sepInset = 56.dp
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(title, 15f, titleColor).apply {
            if (center) gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        if (sub != null) texts.addView(ctx.label(sub, 12.5f, Themes.p.text2).apply { setPadding(0, 3.idp, 0, 0) })
        r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) r.addView(ctx.label(value, 15f, Themes.p.text2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = 180.idp
            setPadding(8.idp, 0, 6.idp, 0)
        })
        if (trailing != null) r.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8.idp })
        if (accessory >= 0) r.addView(Accessory(ctx, accessory), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 4.idp })
        if (onClick != null) r.clickable(onClick)
        return add(r)
    }

    fun switch(title: String, value: Boolean, sub: String? = null, icon: Int = 0, iconColor: Int = 0, rebuild: Boolean = false, set: (Boolean) -> Unit): RowLayout {
        lateinit var sw: IosSwitch
        sw = IosSwitch(ctx, value) {
            set(it)
            if (rebuild) pane.rebuildPageSoon()
        }
        return row(title, sub, icon, iconColor, trailing = sw).apply { clickable { sw.toggle() } }
    }

    fun segmented(title: String?, options: List<Pair<String, String>>, value: String, rebuild: Boolean = false, set: (String) -> Unit): RowLayout {
        val seg = Segmented(ctx, options.map { it.second }, options.indexOfFirst { it.first == value }.coerceAtLeast(0)) {
            set(options[it].first)
            if (rebuild) pane.rebuildPageSoon()
        }
        if (title == null) {
            val r = RowLayout(ctx)
            r.addView(seg, LinearLayout.LayoutParams(0, -2, 1f))
            return add(r)
        }
        return row(title, trailing = seg)
    }

    fun nav(title: String, value: String? = null, icon: Int = 0, iconColor: Int = 0, sub: String? = null, onClick: () -> Unit) =
        row(title, sub, icon, iconColor, value = value, accessory = Accessory.CHEVRON, onClick = onClick)

    fun check(title: String, checked: Boolean, sub: String? = null, trailing: View? = null, onClick: () -> Unit) =
        row(title, sub, trailing = trailing, accessory = if (checked) Accessory.CHECK else -1, onClick = onClick)

    fun button(title: String, color: Int = Themes.p.accent, onClick: () -> Unit) = row(title, titleColor = color, onClick = onClick)

    fun info(title: String, value: String) = row(title, value = value)

    fun custom(v: View, padded: Boolean = true): RowLayout {
        val r = RowLayout(ctx)
        if (!padded) r.setPadding(0, 0, 0, 0)
        r.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        return add(r)
    }
}

class PageBuilder(val pane: SettingsPane, val box: LinearLayout) {
    val ctx: Context get() = pane.context

    fun group(header: String? = null, footer: String? = null, block: GroupBuilder.() -> Unit) {
        val pal = Themes.p
        if (header != null) box.addView(ctx.label(header.uppercase(), 12.5f, pal.text2).apply {
            setPadding(16.idp, 18.idp, 16.idp, 7.idp)
        })
        else box.addView(View(ctx), LinearLayout.LayoutParams(-1, 16.idp))
        val g = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                cornerRadius = 10.dp
            }
            clipToOutline = true
        }
        GroupBuilder(pane, g).block()
        box.addView(g, LinearLayout.LayoutParams(-1, -2))
        if (footer != null) box.addView(ctx.label(footer, 12.5f, pal.text2).apply {
            setPadding(16.idp, 7.idp, 16.idp, 4.idp)
            setLineSpacing(2.dp, 1f)
        })
    }

    fun view(v: View) = box.addView(v, LinearLayout.LayoutParams(-1, -2))
}
