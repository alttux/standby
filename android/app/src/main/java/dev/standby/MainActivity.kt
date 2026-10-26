package dev.standby

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import dev.standby.apps.AppsPane
import dev.standby.cards.CalendarCard
import dev.standby.cards.CallCard
import dev.standby.cards.Card
import dev.standby.cards.ClockCard
import dev.standby.cards.HomeCard
import dev.standby.cards.PlayerCard
import dev.standby.cards.StatsCard
import dev.standby.cards.WeatherCard
import dev.standby.data.Logx
import dev.standby.data.Mac
import dev.standby.data.Prefs
import dev.standby.data.WeatherRepo
import dev.standby.settings.SettingsPane
import dev.standby.ui.CardPager
import dev.standby.ui.Navigator
import dev.standby.ui.Themes
import dev.standby.ui.dp
import java.util.Calendar

class MainActivity : Activity(), SettingsPane.Host, Mac.Listener, SensorEventListener {
    private lateinit var frame: FrameLayout
    private var nav: Navigator? = null
    private var pager: CardPager? = null
    private var settings: SettingsPane? = null
    private var apps: AppsPane? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var lastTouch = System.currentTimeMillis()
    private var nightOn = false
    private var sensorDark = false
    private var luxSince = 0L
    private var shiftStep = 0
    /** Keeps Wi-Fi out of power save so adb over Wi-Fi stays responsive. */
    private val wifiLock: WifiManager.WifiLock by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "standby:wifi").apply { setReferenceCounted(false) }
    }

    private val prefListener: (String) -> Unit = { key -> onPref(key) }
    private val weatherListener: () -> Unit = {
        pager?.cards?.forEach { it.onWeather() }
        if (Themes.resolve()) rebuild()
    }
    private val rebuildRunnable = Runnable { build() }

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onMinute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        frame = FrameLayout(this)
        setContentView(frame)
        Themes.resolve()
        build()
        Prefs.listen(prefListener)
        WeatherRepo.listen(weatherListener)
        Mac.listen(this)
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateKeepAwakeService()
        Logx.i("Launcher started")
    }

    override fun onDestroy() {
        Prefs.unlisten(prefListener)
        WeatherRepo.unlisten(weatherListener)
        Mac.unlisten(this)
        unregisterReceiver(tick)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI tree

    private fun createCard(key: String): Card = when (key) {
        "weather" -> WeatherCard(this)
        "calendar" -> CalendarCard(this)
        "clock" -> ClockCard(this)
        "stats" -> StatsCard(this)
        "player" -> PlayerCard(this)
        "call" -> CallCard(this)
        else -> HomeCard(this)
    }

    /** (Re)creates all views with the current palette, keeping the navigation state. */
    private fun build() {
        handler.removeCallbacks(rebuildRunnable)
        val navPage = nav?.page ?: 0
        val cardKey = pager?.current()?.key ?: Prefs.lastCard
        val section = settings?.current
        val settingsScroll = settings?.detailScrollY ?: 0
        pager?.shown = false
        frame.removeAllViews()
        val pal = Themes.p
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        frame.setBackgroundColor(pal.bg)

        val cards = Prefs.enabledCards().map { createCard(it) }
        val pg = CardPager(this).apply {
            loop = Prefs.loopCards
            setCards(cards, cards.indexOfFirst { it.key == cardKey }.coerceAtLeast(0))
            onIndexChanged = { Prefs.lastCard = cards[it].key }
            onLongPress = { openSettingsFor(it.key) }
        }
        val st = SettingsPane(this, this).also {
            if (section != null) it.select(section)
            it.restoreScroll(settingsScroll)
        }
        val ap = AppsPane(this)
        val nv = Navigator(this).apply {
            setPanes(st, pg, ap)
            goTo(navPage, false)
            onPageChanged = { onNavPage(it) }
        }
        pager = pg
        settings = st
        apps = ap
        nav = nv
        frame.addView(nv, FrameLayout.LayoutParams(-1, -1))
        applyWindow()
        updateShown()
    }

    private fun rebuild() {
        handler.removeCallbacks(rebuildRunnable)
        handler.post(rebuildRunnable)
    }

    private fun onNavPage(page: Int) {
        if (page != 1) apps?.reset()
        updateShown()
    }

    private fun updateShown() {
        pager?.shown = resumed && nav?.page == 0
    }

    private fun openSettingsFor(cardKey: String) {
        settings?.select(cardKey)
        nav?.goTo(-1)
    }

    override fun closeSettings() {
        nav?.goTo(0)
    }

    // ------------------------------------------------------------------ prefs

    private fun onPref(key: String) {
        when {
            key == "appearance" || key == "scheme" -> {
                Themes.resolve()
                rebuild()
                return
            }
            key == "cards.order" || key == "cards.off" || key == "cards.loop" -> { rebuild(); return }
            key == "cards.last" -> return
            key == "apps.layout" -> apps?.onLayoutPref()
            key.startsWith("apps.") -> apps?.refresh()
            key.startsWith("display.") || key.startsWith("night.") -> {
                updateNight()
                applyWindow()
                updateKeepAwakeService()
            }
            key == "weather.lat" || key == "weather.lon" -> WeatherRepo.refresh()
            key == "call.viz" -> Mac.subscribeViz(Prefs.callViz && pager?.current()?.key == "call" && pager?.shown == true)
        }
        pager?.cards?.forEach { it.refresh() }
    }

    // ------------------------------------------------------------------ window, power, night

    private fun applyWindow() {
        if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = when {
            nightOn && Prefs.nightDim -> 0.02f
            Prefs.brightness >= 0 -> Prefs.brightness
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
        applyImmersive()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        frame.systemUiVisibility = if (Prefs.immersive)
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        else View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun setBrightnessPreview(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    private fun updateKeepAwakeService() {
        val i = Intent(this, KeepAwakeService::class.java)
        if (Prefs.keepAwakeApps) startService(i) else stopService(i)
    }

    private fun updateNight() {
        val on = when (Prefs.nightMode) {
            "schedule" -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val f = Prefs.nightFrom
                val t = Prefs.nightTo
                if (f <= t) h in f until t else h >= f || h < t
            }
            "sensor" -> sensorDark
            else -> false
        }
        if (on == nightOn) return
        nightOn = on
        Logx.i("Night mode ${if (on) "on" else "off"}")
        if (on) {
            // monochrome red: luminance goes to the red channel only
            val m = floatArrayOf(
                0.30f, 0.59f, 0.11f, 0f, 0f,
                0.02f, 0.04f, 0.01f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
            frame.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        } else frame.setLayerType(View.LAYER_TYPE_NONE, null)
        applyWindow()
    }

    private fun registerSensor(on: Boolean) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(this)
        if (on && Prefs.nightMode == "sensor") sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        val wantDark = if (sensorDark) lux < 12f else lux < 3f
        val now = System.currentTimeMillis()
        if (wantDark == sensorDark) {
            luxSince = 0L
            return
        }
        if (luxSince == 0L) luxSince = now
        if (now - luxSince > 5000) {
            sensorDark = wantDark
            luxSince = 0L
            updateNight()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onMinute() {
        if (Themes.resolve()) rebuild()
        updateNight()
        WeatherRepo.refreshIfStale()
        if (Prefs.pixelShift) {
            val pts = arrayOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
            shiftStep = (shiftStep + 1) % pts.size
            frame.translationX = pts[shiftStep].first * 2.dp
            frame.translationY = pts[shiftStep].second * 2.dp
        } else {
            frame.translationX = 0f
            frame.translationY = 0f
        }
        val idle = Prefs.idleReturn
        if (idle > 0 && resumed && System.currentTimeMillis() - lastTouch > idle * 60_000L) {
            if (nav?.page != 0) nav?.goTo(0)
            if ((pager?.index ?: 0) != 0) pager?.goTo(0)
        }
        pager?.cards?.forEach { it.refresh() }
    }

    // ------------------------------------------------------------------ companion

    override fun onCompanion(topic: String) {
        pager?.cards?.forEach { it.onCompanion(topic) }
        if (topic == "conn" && Mac.connected) WeatherRepo.refreshIfStale()
        if (topic == "theme") rebuild()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        wifiLock.acquire()
        if (Themes.resolve()) build()
        applyWindow()
        updateShown()
        registerSensor(true)
        updateNight()
        WeatherRepo.refreshIfStale()
        pager?.cards?.forEach { it.refresh() }
    }

    override fun onPause() {
        resumed = false
        if (!Prefs.keepAwakeApps) wifiLock.release()
        updateShown()
        registerSensor(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (nav?.page != 0) nav?.goTo(0) else pager?.goTo(0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (nav?.page == -1 && settings?.pop() == true) return
        if (nav?.page != 0) nav?.goTo(0)
    }
}
