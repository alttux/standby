package dev.standby.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.standby.L
import dev.standby.R
import dev.standby.cards.ClockCard
import dev.standby.data.AppRepo
import dev.standby.data.Logx
import dev.standby.data.Mac
import dev.standby.data.Prefs
import dev.standby.data.WeatherRepo
import dev.standby.ui.AppearancePicker
import dev.standby.ui.ClockStylePicker
import dev.standby.ui.ColorDots
import dev.standby.ui.Fonts
import dev.standby.ui.Glyphs
import dev.standby.ui.IosSlider
import dev.standby.ui.Palette
import dev.standby.ui.Swatch
import dev.standby.ui.Themes
import dev.standby.ui.dp
import dev.standby.ui.idp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Page(val title: String, val build: PageBuilder.() -> Unit)

/** iPad-style settings: section list on the left, detail pages on the right. */
@SuppressLint("ViewConstructor")
class SettingsPane(ctx: Context, private val host: Host) : LinearLayout(ctx), Mac.Listener {
    interface Host {
        fun setBrightnessPreview(v: Float)
        fun closeSettings()
    }

    private class Section(val id: String, val title: String, val icon: Int, val color: Int)

    private val pal = Themes.p
    private val sections: List<List<Section>> = listOf(
        listOf(
            Section("appearance", L("Appearance", "Оформление"), R.drawable.ic_palette, pal.purple),
            Section("cards", L("Cards", "Карточки"), R.drawable.ic_cards, pal.blue),
        ),
        listOf(
            Section("home", L("Home", "Главный экран"), R.drawable.ic_home, pal.blue),
            Section("clock", L("Clock", "Часы"), R.drawable.ic_clock, pal.orange),
            Section("calendar", L("Calendar", "Календарь"), R.drawable.ic_calendar, pal.red),
            Section("weather", L("Weather", "Погода"), R.drawable.ic_cloud, pal.teal),
        ),
        listOf(
            Section("apps", L("Apps", "Приложения"), R.drawable.ic_apps, pal.purple),
            Section("stats", L("System Stats", "Нагрузка Mac"), R.drawable.ic_bar_chart, pal.green),
            Section("player", L("Player", "Плеер"), R.drawable.ic_music, pal.pink),
            Section("call", L("Calls", "Созвоны"), R.drawable.ic_phone, pal.green),
            Section("touchbar", "Touch Bar", R.drawable.ic_touchbar, 0xFF3A3A3C.toInt()),
        ),
        listOf(
            Section("display", L("Display & Power", "Экран и питание"), R.drawable.ic_sun, pal.blue),
            Section("night", L("Night Mode", "Ночной режим"), R.drawable.ic_moon, pal.purple),
        ),
        listOf(
            Section("companion", L("Mac Companion", "Компаньон для Mac"), R.drawable.ic_laptop, 0xFF8E8E93.toInt()),
            Section("about", L("About", "Об устройстве"), R.drawable.ic_info, 0xFF8E8E93.toInt()),
        ),
    )

    var current = "appearance"
        private set
    private val stack = ArrayList<Page>()
    private val leftBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val leftScroll = ScrollView(ctx)
    private val detail = FrameLayout(ctx)
    private var detailScroll: ScrollView? = null
    private var companionSub: TextView? = null
    private val rowViews = HashMap<String, RowLayout>()

    init {
        orientation = HORIZONTAL
        setBackgroundColor(pal.bg)
        leftScroll.isVerticalScrollBarEnabled = false
        leftScroll.addView(leftBox)
        leftScroll.setBackgroundColor(if (pal.dark) pal.bg else pal.bg)
        addView(leftScroll, LayoutParams(0, -1, 0.36f))
        addView(View(ctx).apply { setBackgroundColor(pal.separator) }, LayoutParams(1, -1))
        addView(detail, LayoutParams(0, -1, 0.64f))
        buildLeft()
        showPage()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Mac.listen(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Mac.unlisten(this)
    }

    override fun onCompanion(topic: String) {
        if (topic == "conn") {
            companionSub?.text = companionStatus()
            if (current == "companion" && stack.isEmpty()) rebuildPageSoon()
        }
    }

    private fun companionStatus() = if (Mac.connected) L("Connected · ", "Подключено · ") + Mac.host
    else L("Not connected", "Не подключено")

    // --------------------------------------------------------------- left column

    private fun buildLeft() {
        leftBox.removeAllViews()
        leftBox.setPadding(12.idp, 14.idp, 12.idp, 24.idp)
        leftBox.addView(context.label(L("Settings", "Настройки"), 28f, pal.text, Fonts.bold).apply {
            setPadding(6.idp, 4.idp, 0, 8.idp)
        })
        // companion card, like the Apple ID cell
        val pb = PageBuilder(this, leftBox)
        pb.group {
            val r = row(L("StandBy Companion", "StandBy Companion"), companionStatus(), R.drawable.ic_laptop, 0xFF636366.toInt()) {
                select("companion")
            }
            companionSub = (r.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            rowViews["companion-card"] = r
        }
        sections.forEach { grp ->
            pb.group {
                grp.forEach { s ->
                    rowViews[s.id] = row(s.title, icon = s.icon, iconColor = s.color) { select(s.id) }
                }
            }
        }
        highlight()
    }

    private fun highlight() {
        rowViews.forEach { (id, r) ->
            if (id == "companion-card") return@forEach
            val sel = id == current
            r.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pal.surface2))
                addState(intArrayOf(), ColorDrawable(if (sel) pal.accent else 0))
            }
            ((r.getChildAt(1) as LinearLayout).getChildAt(0) as TextView).setTextColor(if (sel) dev.standby.ui.contrastOn(pal.accent) else pal.text)
        }
    }

    val detailScrollY: Int get() = detailScroll?.scrollY ?: 0

    fun restoreScroll(y: Int) {
        detailScroll?.let { sc -> sc.post { sc.scrollTo(0, y) } }
    }

    fun select(id: String) {
        current = id
        stack.clear()
        highlight()
        showPage()
        hideKeyboard()
    }

    // --------------------------------------------------------------- detail

    fun push(page: Page) {
        stack.add(page)
        showPage()
    }

    /** Returns true if a sub-page was popped. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.size - 1)
        hideKeyboard()
        showPage()
        return true
    }

    private val rebuildRunnable = Runnable { showPage(keepScroll = true) }

    fun rebuildPageSoon() {
        removeCallbacks(rebuildRunnable)
        postDelayed(rebuildRunnable, 260)
    }

    private fun showPage(keepScroll: Boolean = false) {
        val y = if (keepScroll) detailScroll?.scrollY ?: 0 else 0
        val page = stack.lastOrNull() ?: page(current)
        detail.removeAllViews()
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        // navigation bar
        val bar = FrameLayout(context)
        if (stack.isNotEmpty()) {
            val back = TextView(context).apply {
                text = "  " + (if (stack.size > 1) stack[stack.size - 2].title else page(current).title)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                setTextColor(pal.accent)
                typeface = Fonts.regular
                gravity = Gravity.CENTER_VERTICAL
                setPadding(22.idp, 0, 12.idp, 0)
                setOnClickListener { pop() }
            }
            val chevron = object : View(context) {
                override fun onDraw(c: android.graphics.Canvas) = Glyphs.chevron(c, 14.dp, height / 2f, 18.dp, pal.accent, -1, 2.6f.dp)
            }
            bar.addView(chevron, FrameLayout.LayoutParams(28.idp, -1))
            bar.addView(back, FrameLayout.LayoutParams(-2, -1))
        }
        bar.addView(context.label(page.title, 16f, pal.text, Fonts.semibold).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        col.addView(bar, LayoutParams(-1, 46.idp))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(20.idp, 0, 20.idp, 28.idp)
        }
        PageBuilder(this, box).apply(page.build)
        scroll.addView(box)
        col.addView(scroll, LayoutParams(-1, 0, 1f))
        detail.addView(col)
        detailScroll = scroll
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()

    private fun title(id: String) = sections.flatten().firstOrNull { it.id == id }?.title ?: ""

    private val colorNames: Map<String, String> = mapOf(
        "accent" to L("Theme accent", "Акцент темы"), "red" to L("Red", "Красный"), "orange" to L("Orange", "Оранжевый"),
        "yellow" to L("Yellow", "Жёлтый"), "green" to L("Green", "Зелёный"), "teal" to L("Teal", "Бирюзовый"),
        "blue" to L("Blue", "Синий"), "purple" to L("Purple", "Фиолетовый"), "pink" to L("Pink", "Розовый"), "mono" to L("Mono", "Моно"),
    )

    // --------------------------------------------------------------- pages

    private fun page(id: String): Page = when (id) {
        "appearance" -> Page(title(id)) {
            val scheme = Themes.find(Prefs.scheme)
            group(L("Appearance", "Режим")) {
                custom(AppearancePicker(ctx, scheme, Prefs.appearance) { Prefs.appearance = it })
            }
            box.addView(ctx.label(L("Auto switches at sunrise and sunset (from the weather location), otherwise at 7:00 and 20:00.",
                "«Авто» переключает тему на восходе и закате (по городу погоды), иначе в 7:00 и 20:00."), 12.5f, pal.text2).apply { setPadding(16.idp, 7.idp, 16.idp, 0) })
            group(L("Color scheme", "Цветовая схема")) {
                Themes.all().forEach { s ->
                    check(s.name, s.id == Prefs.scheme, if (s.custom) L("Custom", "Своя") else null, Swatch(ctx, s)) { Prefs.scheme = s.id }
                }
            }
            group(L("Accent", "Акцентный цвет"), L("Used for the calendar, analog clock hands and highlights.",
                "Используется в календаре, стрелках часов и выделениях.")) {
                custom(ColorDots(ctx, Palette.ACCENTS, Prefs.accent) { Prefs.accent = it })
            }
            val dir = Themes.dir(ctx)
            group(L("Custom themes", "Свои темы"), L("A theme is a JSON file with \"light\" and/or \"dark\" colour sets. Put it into the folder below (adb push) or send it from the Mac companion (menu → Send theme…). See README for the format.",
                "Тема — это JSON-файл с наборами цветов \"light\" и/или \"dark\". Положите его в папку ниже (adb push) или отправьте из компаньона на Mac (меню → Отправить тему…). Формат описан в README.")) {
                button(L("Create from current scheme", "Создать из текущей схемы")) {
                    val f = Themes.createFromCurrent(ctx)
                    toast(L("Saved ", "Сохранено: ") + f.absolutePath)
                    Prefs.scheme = Themes.parse(f.readText(), f).id
                }
                button(L("Reload themes", "Перезагрузить темы")) {
                    Themes.reload(ctx)
                    rebuildPageSoon()
                    toast(L("Themes: ", "Тем: ") + Themes.custom.size)
                }
                row(L("Folder", "Папка"), dir.absolutePath)
                if (Themes.find(Prefs.scheme).custom) button(L("Delete current theme", "Удалить текущую тему"), pal.red) {
                    Themes.find(Prefs.scheme).file?.delete()
                    Themes.reload(ctx)
                    Prefs.scheme = "ios"
                }
            }
        }

        "cards" -> Page(title(id)) {
            val names = cardNames()
            val order = Prefs.orderedCards()
            val off = Prefs.cardsOff.split(',').toSet()
            group(L("Cards", "Карточки"), L("Swipe up and down to switch cards. Long-press a card to open its settings. Arrows change the order.",
                "Свайп вверх/вниз переключает карточки. Долгое нажатие открывает их настройки. Стрелки меняют порядок.")) {
                order.forEachIndexed { i, k ->
                    val arrows = LinearLayout(ctx).apply {
                        orientation = HORIZONTAL
                        addView(arrowButton(-1, i > 0) { Prefs.moveCard(k, -1); rebuildPageSoon() })
                        addView(arrowButton(1, i < order.size - 1) { Prefs.moveCard(k, 1); rebuildPageSoon() })
                    }
                    val sw = dev.standby.ui.IosSwitch(ctx, k !in off) { Prefs.setCardEnabled(k, it) }
                    row(names[k] ?: k, trailing = LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(arrows)
                        addView(sw, LayoutParams(-2, -2).apply { leftMargin = 10.idp })
                    })
                }
            }
            group {
                switch(L("Loop cards", "Зациклить карточки"), Prefs.loopCards) { Prefs.loopCards = it }
                segmented(L("Return to first card", "Возврат к первой"),
                    listOf("0" to L("Never", "Нет"), "1" to "1 m", "5" to "5 m", "15" to "15 m"), Prefs.idleReturn.toString()) {
                    Prefs.idleReturn = it.toInt()
                }
            }
        }

        "home" -> Page(title(id)) {
            group(L("Clock", "Часы"), L("Tap the clock on the home card to switch quickly.", "Нажмите на часы на главной карточке, чтобы быстро переключить вид.")) {
                segmented(null, listOf("analog" to L("Analog", "Аналоговые"), "digital" to L("Digital", "Цифровые")), Prefs.homeClock) { Prefs.homeClock = it }
                switch(L("Clock on the right", "Часы справа"), Prefs.homeSwap) { Prefs.homeSwap = it }
                switch(L("Seconds", "Секунды"), Prefs.clockSeconds) { Prefs.clockSeconds = it }
            }
            group(L("Accent", "Акцент")) {
                custom(ColorDots(ctx, Palette.ACCENTS, Prefs.accent) { Prefs.accent = it })
            }
        }

        "clock" -> Page(title(id)) {
            val styleNames = listOf(
                "digital_bold" to L("Bold", "Крупные"), "analog_bold" to L("Dial", "Циферблат"),
                "analog_classic" to L("Classic", "Классика"), "digital_thin" to L("Thin", "Тонкие"),
            )
            group(L("Face", "Циферблат"), L("Tap the clock card to cycle faces.", "Нажатие на карточку часов переключает циферблат.")) {
                custom(ClockStylePicker(ctx, styleNames, Prefs.clockStyle) { Prefs.clockStyle = it }, padded = false)
            }
            group(L("Color", "Цвет")) {
                custom(ColorDots(ctx, Palette.ACCENTS, Prefs.clockColor) { Prefs.clockColor = it; rebuildPageSoon() })
            }
            group {
                segmented(L("Time format", "Формат"), listOf("auto" to L("Auto", "Авто"), "12" to "12h", "24" to "24h"), Prefs.timeFormat) { Prefs.timeFormat = it }
                switch(L("Seconds", "Секунды"), Prefs.clockSeconds) { Prefs.clockSeconds = it }
                switch(L("Date", "Дата"), Prefs.clockDate) { Prefs.clockDate = it }
            }
        }

        "calendar" -> Page(title(id)) {
            group {
                segmented(L("Week starts on", "Начало недели"), listOf("auto" to L("Auto", "Авто"), "mon" to L("Mon", "Пн"), "sun" to L("Sun", "Вс")), Prefs.firstDay) { Prefs.firstDay = it }
                switch(L("Week numbers", "Номера недель"), Prefs.weekNumbers) { Prefs.weekNumbers = it }
                switch(L("Dim weekends", "Приглушать выходные"), Prefs.weekends) { Prefs.weekends = it }
            }
            group(L("Accent", "Акцент")) {
                custom(ColorDots(ctx, Palette.ACCENTS, Prefs.accent) { Prefs.accent = it })
            }
        }

        "weather" -> Page(title(id)) {
            group(L("Location", "Местоположение")) {
                nav(L("City", "Город"), Prefs.city.ifEmpty { L("Not set", "Не выбран") }, R.drawable.ic_place, pal.blue) { push(cityPage()) }
            }
            group(L("Units", "Единицы")) {
                segmented(L("Temperature", "Температура"), listOf("c" to "°C", "f" to "°F", "k" to "K"), Prefs.units) { Prefs.units = it }
                segmented(L("Wind", "Ветер"), listOf("ms" to L("m/s", "м/с"), "kmh" to L("km/h", "км/ч"), "mph" to "mph"), Prefs.wind) { Prefs.wind = it }
            }
            group(footer = L("Tap the weather card to switch between hourly and 7-day forecast.", "Нажатие на карточку погоды переключает почасовой и недельный прогноз.")) {
                switch(L("Animated background", "Анимированный фон"), Prefs.weatherAnim) { Prefs.weatherAnim = it }
                segmented(L("Forecast", "Прогноз"), listOf("h" to L("Hourly", "По часам"), "d" to L("7 days", "7 дней")), if (Prefs.weatherDaily) "d" else "h") { Prefs.weatherDaily = it == "d" }
            }
            val d = WeatherRepo.data
            val status = when {
                WeatherRepo.loading -> L("Loading…", "Загрузка…")
                d != null -> L("Updated ", "Обновлено ") + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(d.fetched))
                else -> L("No data", "Нет данных")
            } + (WeatherRepo.error?.let { "\n" + L("Last error: ", "Последняя ошибка: ") + it } ?: "")
            group(footer = status + "\n" + L("Data: Open-Meteo.com (CC BY 4.0). Without internet on the phone, requests go through the Mac companion.",
                "Данные: Open-Meteo.com (CC BY 4.0). Если на телефоне нет интернета, запросы идут через компаньон на Mac.")) {
                button(L("Refresh now", "Обновить сейчас")) { WeatherRepo.refresh(); rebuildPageSoon() }
            }
        }

        "apps" -> Page(title(id)) {
            group {
                segmented(L("Layout", "Вид"), listOf("grid" to L("Grid", "Сетка"), "list" to L("List", "Список")), Prefs.appsLayout) { Prefs.appsLayout = it }
                segmented(L("Sort", "Сортировка"), listOf("name" to L("Name", "Имя"), "usage" to L("Usage", "Частота"), "recent" to L("New", "Новые")), Prefs.appsSort) { Prefs.appsSort = it }
            }
            group(footer = L("Swipe left on the main screen to open the app list. Long-press an app for more actions.",
                "Свайп влево на главном экране открывает список приложений. Долгое нажатие на приложение — дополнительные действия.")) {
                nav(L("Hidden apps", "Скрытые приложения"), Prefs.hiddenSet().size.toString()) { push(hiddenPage()) }
                button(L("Reset usage statistics", "Сбросить статистику запусков"), pal.red) { AppRepo.resetUsage(); toast(L("Done", "Готово")) }
            }
        }

        "stats" -> Page(title(id)) {
            group(L("Graph range", "Период графиков")) {
                segmented(null, listOf("60" to "1 min", "120" to "2 min", "300" to "5 min"), Prefs.statsRange.toString()) { Prefs.statsRange = it.toInt() }
            }
            companionNote(this, L("CPU, GPU, memory and network load of the Mac are sent by StandBy Companion once per second.",
                "Загрузка CPU, GPU, памяти и сети Mac приходит из StandBy Companion раз в секунду."))
        }

        "player" -> Page(title(id)) {
            group {
                switch(L("Tint background from artwork", "Фон по цвету обложки"), Prefs.playerTint) { Prefs.playerTint = it }
            }
            companionNote(this, L("Supported players: Apple Music and Spotify. Play/pause, next/previous, seeking and the system volume are controlled on the Mac.",
                "Поддерживаются Apple Music и Spotify. Пауза, перемотка, переключение треков и системная громкость управляются на Mac."))
        }

        "touchbar" -> Page(title(id)) {
            group(L("Rows", "Строки"), L("The Touch Bar is split between buttons into several rows to fit the screen.",
                "Touch Bar делится между кнопками на несколько строк, чтобы уместиться на экране.")) {
                segmented(null, listOf("2" to "2", "3" to "3", "4" to "4"), Prefs.touchBarRows.toString()) { Prefs.touchBarRows = it.toInt() }
            }
            companionNote(this, L("This is the Touch Bar macOS itself renders (the frontmost app's buttons and the Control Strip), the same private API the Xcode Touch Bar simulator uses. Taps need Accessibility access for StandBy Companion on the Mac.",
                "Это Touch Bar, который рисует сама macOS (кнопки активного приложения и Control Strip), через тот же приватный API, что и симулятор Touch Bar из Xcode. Для нажатий дайте StandBy Companion «Универсальный доступ» на Mac."))
        }

        "call" -> Page(title(id)) {
            group {
                switch(L("Audio visualizer", "Аудиовизуализатор"), Prefs.callViz) { Prefs.callViz = it }
                segmented(L("Style", "Стиль"), listOf("bars" to L("Bars", "Столбцы"), "wave" to L("Wave", "Волна")), Prefs.vizStyle) { Prefs.vizStyle = it }
            }
            companionNote(this, L("Mute buttons toggle the Mac's default microphone and output device, so they work with any app (Zoom, Meet, Teams, Telegram…). The visualizer shows your microphone level; the call is detected when an app is using the microphone.",
                "Кнопки отключают микрофон и звук устройства по умолчанию на Mac, поэтому работают в любом приложении (Zoom, Meet, Teams, Telegram…). Визуализатор показывает уровень микрофона; созвон определяется по использованию микрофона."))
        }

        "display" -> Page(title(id)) {
            group(footer = L("The launcher keeps the screen on while it is visible. \"Also in other apps\" holds a wake lock everywhere.",
                "Лаунчер не даёт экрану погаснуть, пока он открыт. «И в других приложениях» удерживает экран включённым везде.")) {
                switch(L("Keep screen on", "Не выключать экран"), Prefs.keepScreenOn) { Prefs.keepScreenOn = it }
                switch(L("Also in other apps", "И в других приложениях"), Prefs.keepAwakeApps) { Prefs.keepAwakeApps = it }
            }
            group(L("Brightness", "Яркость")) {
                switch(L("System brightness", "Системная яркость"), Prefs.brightness < 0, rebuild = true) { Prefs.brightness = if (it) -1f else 0.6f }
                if (Prefs.brightness >= 0) custom(IosSlider(ctx, Prefs.brightness) { v, done ->
                    val b = 0.02f + v * 0.98f
                    host.setBrightnessPreview(b)
                    if (done) Prefs.brightness = b
                })
            }
            group(footer = L("Pixel shift moves the content by a few pixels every minute to prevent burn-in.",
                "Сдвиг пикселей раз в минуту немного смещает изображение, чтобы избежать выгорания.")) {
                switch(L("Pixel shift", "Сдвиг пикселей"), Prefs.pixelShift) { Prefs.pixelShift = it }
                switch(L("Hide system bars", "Скрывать системные панели"), Prefs.immersive) { Prefs.immersive = it }
            }
        }

        "night" -> Page(title(id)) {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val hasSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
            val opts = mutableListOf("off" to L("Off", "Выкл."), "schedule" to L("Schedule", "Расписание"))
            if (hasSensor) opts.add("sensor" to L("Ambient light", "Датчик света"))
            group(footer = L("Night mode renders everything in dim red, like StandBy on iPhone.", "Ночной режим окрашивает всё в тусклый красный, как StandBy на iPhone.") +
                if (!hasSensor) "\n" + L("This device has no light sensor.", "На этом устройстве нет датчика освещённости.") else "") {
                segmented(null, opts, Prefs.nightMode, rebuild = true) { Prefs.nightMode = it }
            }
            if (Prefs.nightMode == "schedule") group(L("Schedule", "Расписание")) {
                nav(L("From", "С"), hour(Prefs.nightFrom)) { push(hourPage(L("From", "С"), Prefs.nightFrom) { Prefs.nightFrom = it }) }
                nav(L("To", "До"), hour(Prefs.nightTo)) { push(hourPage(L("To", "До"), Prefs.nightTo) { Prefs.nightTo = it }) }
            }
            if (Prefs.nightMode != "off") group {
                switch(L("Lower brightness", "Снижать яркость"), Prefs.nightDim) { Prefs.nightDim = it }
            }
        }

        "companion" -> Page(title(id)) {
            group(L("Status", "Состояние")) {
                info(L("Connection", "Подключение"), if (Mac.connected) L("Connected", "Подключено") else L("Waiting", "Ожидание"))
                if (Mac.connected) info("Mac", Mac.host)
                if (Mac.lastMessage > 0) info(L("Last message", "Последнее сообщение"), SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(Mac.lastMessage)))
            }
            group(L("Port", "Порт"), L("The companion runs `adb forward tcp:PORT tcp:PORT` and connects to 127.0.0.1. Use the same port in the Mac app.",
                "Компаньон выполняет `adb forward tcp:PORT tcp:PORT` и подключается к 127.0.0.1. Укажите тот же порт в приложении на Mac.")) {
                val field = EditText(ctx).apply {
                    setText(Prefs.port.toString())
                    inputType = InputType.TYPE_CLASS_NUMBER
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                    setTextColor(pal.text)
                    background = null
                    typeface = Fonts.regular
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                    gravity = Gravity.END
                    setOnEditorActionListener { v, _, _ ->
                        val p = v.text.toString().toIntOrNull()
                        if (p != null && p in 1024..65535) {
                            Prefs.port = p
                            Mac.start(p)
                            toast(L("Listening on port ", "Слушаю порт ") + p)
                        }
                        hideKeyboard()
                        true
                    }
                }
                row(L("TCP port", "TCP-порт"), trailing = field)
                button(L("Restart server", "Перезапустить сервер")) { Mac.start(Prefs.port); toast(L("Restarted", "Перезапущено")) }
            }
            group {
                nav(L("Launcher log", "Журнал лаунчера")) { push(logPage()) }
            }
        }

        else -> Page(title("about")) {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            group {
                info(L("Version", "Версия"), pi.versionName ?: "")
                info(L("Device", "Устройство"), "${Build.MANUFACTURER} ${Build.MODEL}")
                info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            group {
                button(L("Choose default launcher", "Выбрать лаунчер по умолчанию")) {
                    try { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                button(L("Android settings", "Настройки Android")) {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            box.addView(ctx.label("StandBy · " + L("a desk clock launcher.", "лаунчер-настольные часы.") +
                "\nInter font © The Inter Project Authors (SIL OFL 1.1). Weather © Open-Meteo.com (CC BY 4.0).", 12.5f, pal.text2).apply {
                setPadding(16.idp, 12.idp, 16.idp, 0)
            })
        }
    }

    private fun companionNote(b: PageBuilder, text: String) {
        b.group(footer = text) {
            row(L("Mac companion", "Компаньон Mac"), if (Mac.connected) Mac.host else L("Not connected", "Не подключён"), R.drawable.ic_laptop, 0xFF636366.toInt(),
                accessory = Accessory.CHEVRON) { select("companion") }
        }
    }

    private fun arrowButton(dir: Int, enabled: Boolean, on: () -> Unit) = object : View(context) {
        init {
            if (enabled) setOnClickListener { on() }
            layoutParams = LayoutParams(30.idp, 30.idp)
        }
        override fun onDraw(c: android.graphics.Canvas) {
            val col = if (enabled) pal.accent else pal.text3
            c.save()
            c.rotate(if (dir < 0) -90f else 90f, width / 2f, height / 2f)
            Glyphs.chevron(c, width / 2f, height / 2f, 14.dp, col, 1, 2.2f.dp)
            c.restore()
        }
    }

    fun cardNames() = mapOf(
        "home" to L("Clock + calendar", "Часы + календарь"), "weather" to L("Weather", "Погода"),
        "calendar" to L("Calendar", "Календарь"), "clock" to L("Clock", "Часы"), "stats" to L("System stats", "Нагрузка Mac"),
        "player" to L("Player", "Плеер"), "call" to L("Calls", "Созвоны"), "touchbar" to "Touch Bar",
    )

    private fun hour(h: Int) = String.format(Locale.US, "%02d:00", h)

    private fun hourPage(t: String, value: Int, set: (Int) -> Unit) = Page(t) {
        group {
            for (h in 0..23) check(hour(h), h == value) { set(h); pop() }
        }
    }

    private fun cityPage(): Page = Page(L("City", "Город")) {
        val results = LinearLayout(ctx).apply { orientation = VERTICAL }
        val status = ctx.label("", 12.5f, pal.text2).apply { setPadding(16.idp, 8.idp, 16.idp, 0) }
        val field = searchField(ctx, L("Search city", "Поиск города"))
        fun run() {
            val q = field.text.toString().trim()
            if (q.length < 2) return
            status.text = L("Searching…", "Поиск…")
            hideKeyboard()
            WeatherRepo.search(q) { list, err ->
                results.removeAllViews()
                status.text = when {
                    err != null -> L("Search failed: ", "Ошибка поиска: ") + err
                    list.isNullOrEmpty() -> L("Nothing found", "Ничего не найдено")
                    else -> ""
                }
                if (!list.isNullOrEmpty()) PageBuilder(this@SettingsPane, results).group {
                    list.forEach { pl ->
                        row(pl.name, pl.region, accessory = -1) {
                            Prefs.lat = pl.lat.toFloat()
                            Prefs.lon = pl.lon.toFloat()
                            Prefs.city = pl.name
                            WeatherRepo.clear()
                            WeatherRepo.refresh()
                            Logx.i("Location set to ${pl.name}")
                            pop()
                        }
                    }
                }
            }
        }
        field.setOnEditorActionListener { _, a, ev ->
            if (a == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { run(); true } else false
        }
        box.addView(View(ctx), LayoutParams(-1, 12.idp))
        view(field.parent as View)
        view(status)
        view(results)
        if (Prefs.city.isNotEmpty()) group(L("Current", "Текущий")) {
            info(Prefs.city, String.format(Locale.US, "%.2f, %.2f", Prefs.lat, Prefs.lon))
        }
    }

    private fun hiddenPage(): Page = Page(L("Hidden apps", "Скрытые приложения")) {
        val hidden = Prefs.hiddenSet()
        group(footer = L("Hidden apps don't appear in the app list.", "Скрытые приложения не отображаются в списке.")) {
            AppRepo.apps.forEach { a ->
                switch(a.label, a.key in hidden) { Prefs.setHidden(a.key, it) }
            }
        }
    }

    private fun logPage(): Page = Page(L("Launcher log", "Журнал лаунчера")) {
        group {
            button(L("Refresh", "Обновить")) { rebuildPageSoon() }
        }
        val tv = ctx.label(Logx.snapshot().reversed().joinToString("\n"), 11f, pal.text2).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16.idp, 12.idp, 16.idp, 0)
        }
        view(tv)
    }

    companion object {
        /** iOS search bar: rounded field with a magnifier. Returns the EditText (wrapped in a FrameLayout). */
        fun searchField(ctx: Context, hint: String): EditText {
            val pal = Themes.p
            val wrap = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(pal.fill); cornerRadius = 10.dp }
            }
            val icon = object : View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) =
                    dev.standby.ui.Icons.draw(c, R.drawable.ic_search, width / 2f, height / 2f, 18.dp, pal.text2)
            }
            wrap.addView(icon, FrameLayout.LayoutParams(34.idp, -1))
            val et = EditText(ctx).apply {
                this.hint = hint
                setHintTextColor(pal.text2)
                setTextColor(pal.text)
                background = null
                typeface = Fonts.regular
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                setPadding(34.idp, 0, 10.idp, 0)
            }
            wrap.addView(et, FrameLayout.LayoutParams(-1, 36.idp))
            return et
        }
    }
}
