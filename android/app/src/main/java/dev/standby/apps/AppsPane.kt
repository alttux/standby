package dev.standby.apps

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import dev.standby.L
import dev.standby.R
import dev.standby.data.AppEntry
import dev.standby.data.AppRepo
import dev.standby.data.Logx
import dev.standby.data.Prefs
import dev.standby.settings.SettingsPane
import dev.standby.settings.label
import dev.standby.ui.Fonts
import dev.standby.ui.Icons
import dev.standby.ui.Segmented
import dev.standby.ui.Themes
import dev.standby.ui.capHeight
import dev.standby.ui.dp
import dev.standby.ui.ellipsize
import dev.standby.ui.fillPaint
import dev.standby.ui.idp
import dev.standby.ui.textPaint
import java.util.Locale

@SuppressLint("ViewConstructor")
class AppsPane(ctx: Context) : LinearLayout(ctx) {
    private val pal = Themes.p
    private val search: android.widget.EditText = SettingsPane.searchField(ctx, L("Search", "Поиск"))
    private val filters = listOf("all" to L("All", "Все"), "frequent" to L("Frequent", "Частые"), "user" to L("User", "Свои"), "system" to L("System", "Система"))
    private val content = FrameLayout(ctx)
    private val empty = ctx.label(L("No apps", "Нет приложений"), 15f, pal.text2).apply { gravity = Gravity.CENTER }
    private var items: List<AppEntry> = emptyList()
    private val adapter = Adapter()
    private var grid = Prefs.appsLayout == "grid"
    private val listener = { refresh() }

    init {
        orientation = VERTICAL
        setBackgroundColor(pal.bg)
        setPadding(16.idp, 12.idp, 16.idp, 0)
        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(search.parent as View, LayoutParams(0, 36.idp, 1f))
        val seg = Segmented(ctx, filters.map { it.second }, filters.indexOfFirst { it.first == Prefs.appsFilter }.coerceAtLeast(0)) {
            Prefs.appsFilter = filters[it].first
            refresh()
        }
        bar.addView(seg, LayoutParams(300.idp, -2).apply { leftMargin = 12.idp })
        val toggle = object : View(ctx) {
            override fun onDraw(c: Canvas) = Icons.draw(c, if (grid) R.drawable.ic_list else R.drawable.ic_apps, width / 2f, height / 2f, 22.dp, pal.accent)
        }
        toggle.setOnClickListener {
            Prefs.appsLayout = if (grid) "list" else "grid"
            toggle.invalidate()
        }
        bar.addView(toggle, LayoutParams(40.idp, 36.idp).apply { leftMargin = 6.idp })
        addView(bar, LayoutParams(-1, -2))
        addView(content, LayoutParams(-1, 0, 1f).apply { topMargin = 10.idp })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = refresh()
            override fun afterTextChanged(s: Editable?) {}
        })
        search.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH && items.size == 1) launch(items[0], null)
            hideKeyboard()
            true
        }
        buildList()
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppRepo.listen(listener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        AppRepo.unlisten(listener)
    }

    /** Called when the pane is left. */
    fun reset() {
        if (search.text.isNotEmpty()) search.setText("")
        hideKeyboard()
        search.clearFocus()
    }

    fun onLayoutPref() {
        val g = Prefs.appsLayout == "grid"
        if (g != grid) {
            grid = g
            buildList()
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun buildList() {
        content.removeAllViews()
        val lv: AbsListView = if (grid) GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = 84.idp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 6.idp
        } else ListView(context).apply {
            divider = null
            background = GradientDrawable().apply { setColor(pal.surface); cornerRadius = 10.dp }
            clipToOutline = true
        }
        lv.selector = ColorDrawable(0)
        lv.isVerticalScrollBarEnabled = false
        lv.clipToPadding = false
        lv.setPadding(0, 0, 0, 16.idp)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, v, pos, _ -> launch(items[pos], v) }
        lv.setOnItemLongClickListener { _, v, pos, _ -> menu(items[pos], v); true }
        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideKeyboard()
            }
            override fun onScroll(view: AbsListView, a: Int, b: Int, c: Int) {}
        })
        content.addView(lv, FrameLayout.LayoutParams(-1, if (grid) -1 else -2))
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        adapter.notifyDataSetChanged()
    }

    fun refresh() {
        val q = search.text.toString().trim().lowercase(Locale.getDefault())
        val hidden = Prefs.hiddenSet()
        var list = AppRepo.apps.filter { it.key !in hidden }
        if (q.isNotEmpty()) list = list.filter { it.label.lowercase(Locale.getDefault()).contains(q) || it.pkg.contains(q) }
        list = when (Prefs.appsFilter) {
            "frequent" -> list.filter { AppRepo.count(it) > 0 }.sortedByDescending { AppRepo.count(it) }
            "user" -> list.filter { !it.system }
            "system" -> list.filter { it.system }
            else -> list
        }
        if (Prefs.appsFilter != "frequent") list = when (Prefs.appsSort) {
            "usage" -> list.sortedWith(compareByDescending<AppEntry> { AppRepo.count(it) }.thenBy { it.label.lowercase() })
            "recent" -> list.sortedByDescending { it.installed }
            else -> list
        }
        items = list
        empty.visibility = if (list.isEmpty()) VISIBLE else GONE
        adapter.notifyDataSetChanged()
    }

    private fun launch(a: AppEntry, from: View?) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            context.startActivity(i, opts)
            AppRepo.launched(a)
            Logx.i("Launch ${a.pkg}")
        } catch (e: Exception) {
            Toast.makeText(context, L("Can't open ", "Не удалось открыть ") + a.label, Toast.LENGTH_SHORT).show()
        }
    }

    /** iOS-like context menu. */
    private fun menu(a: AppEntry, anchor: View) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { setColor(pal.surface2); cornerRadius = 13.dp }
            clipToOutline = true
            elevation = 12.dp
        }
        val pw = PopupWindow(box, 230.idp, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        fun item(title: String, icon: Int, color: Int = pal.text, action: () -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.idp, 0, 14.idp, 0)
                minimumHeight = 44.idp
                setOnClickListener { pw.dismiss(); action() }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pal.separator))
                }
            }
            row.addView(context.label(title, 15f, color), LayoutParams(0, -2, 1f))
            row.addView(object : View(context) {
                override fun onDraw(c: Canvas) = Icons.draw(c, icon, width / 2f, height / 2f, 18.dp, color)
            }, LayoutParams(22.idp, 22.idp))
            if (box.childCount > 0) box.addView(View(context).apply { setBackgroundColor(pal.separator) }, LayoutParams(-1, 1))
            box.addView(row)
        }
        box.addView(context.label(a.label, 13f, pal.text2).apply { setPadding(16.idp, 10.idp, 16.idp, 8.idp) })
        item(L("App info", "О приложении"), R.drawable.ic_info) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item(L("Hide", "Скрыть"), R.drawable.ic_eye_off) { Prefs.setHidden(a.key, true); refresh() }
        if (!a.system) item(L("Uninstall", "Удалить"), R.drawable.ic_delete, pal.red) {
            @Suppress("DEPRECATION")
            context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.showAsDropDown(anchor, 0, -anchor.height / 3)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (grid) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? Cell)?.takeIf { it.gridMode == grid } ?: Cell(parent.context, grid)
            v.bind(items[position], position)
            return v
        }
    }

    /** One app; draws icon and label itself to stay light. */
    private inner class Cell(ctx: Context, val gridMode: Boolean) : View(ctx) {
        private var app: AppEntry? = null
        private var index = 0
        private val tp = textPaint(Fonts.regular, if (gridMode) 11.5f.dp else 15.dp, pal.text, if (gridMode) Paint.Align.CENTER else Paint.Align.LEFT)
        private val sep = fillPaint(pal.separator)

        init {
            layoutParams = AbsListView.LayoutParams(-1, if (gridMode) 92.idp else 52.idp)
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(pal.surface2); cornerRadius = if (gridMode) 14.dp else 0f
                })
            }
        }

        fun bind(a: AppEntry, i: Int) {
            app = a
            index = i
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val a = app ?: return
            val icon = AppRepo.icon(a) { if (app === a) invalidate() }
            if (gridMode) {
                val s = 52.dp
                val cx = width / 2f
                icon?.let {
                    it.setBounds((cx - s / 2).toInt(), 8.idp, (cx + s / 2).toInt(), (8.dp + s).toInt())
                    it.draw(c)
                }
                c.drawText(ellipsize(a.label, tp, width - 8.dp), cx, 8.dp + s + 8.dp + capHeight(tp), tp)
            } else {
                val s = 34.dp
                val y = height / 2f
                icon?.let {
                    it.setBounds(14.idp, (y - s / 2).toInt(), (14.dp + s).toInt(), (y + s / 2).toInt())
                    it.draw(c)
                }
                val x = 14.dp + s + 14.dp
                c.drawText(ellipsize(a.label, tp, width - x - 16.dp), x, y + capHeight(tp) / 2, tp)
                if (index > 0) c.drawRect(x, 0f, width.toFloat(), 0.5f.dp, sep)
            }
        }
    }
}
