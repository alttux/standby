package dev.standby.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object Prefs {
    lateinit var sp: SharedPreferences
        private set

    val CARD_KEYS = listOf("home", "weather", "calendar", "clock", "stats", "player", "call")

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) listeners.forEach { it(key) }
    }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("standby", Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(spListener)
    }

    fun listen(l: (String) -> Unit) { listeners.add(l) }
    fun unlisten(l: (String) -> Unit) { listeners.remove(l) }

    // Appearance
    var appearance by str("appearance", "dark")          // light | dark | auto
    var scheme by str("scheme", "ios")
    var accent by str("accent", "red")                   // see Palette.ACCENTS

    // Cards
    var cardOrder by str("cards.order", CARD_KEYS.joinToString(","))
    var cardsOff by str("cards.off", "")
    var loopCards by bool("cards.loop", true)
    var idleReturn by int("cards.idleReturn", 0)        // minutes, 0 = never
    var lastCard by str("cards.last", "home")

    // Home
    var homeClock by str("home.clock", "analog")         // analog | digital
    var homeSwap by bool("home.swap", false)

    // Clock
    var clockStyle by str("clock.style", "digital_bold") // analog_classic | analog_bold | digital_bold | digital_thin
    var clockColor by str("clock.color", "green")
    var clockSeconds by bool("clock.seconds", true)
    var clockDate by bool("clock.date", true)
    var timeFormat by str("clock.format", "auto")        // auto | 12 | 24

    // Calendar
    var firstDay by str("cal.firstDay", "auto")          // auto | mon | sun
    var weekNumbers by bool("cal.weekNumbers", false)
    var weekends by bool("cal.weekends", true)

    // Weather
    var units by str("weather.units", "c")               // c | f | k
    var wind by str("weather.wind", "ms")                // kmh | ms | mph
    var lat by float("weather.lat", Float.NaN)
    var lon by float("weather.lon", Float.NaN)
    var city by str("weather.city", "")
    var weatherAnim by bool("weather.anim", true)
    var weatherDaily by bool("weather.daily", false)

    // Apps
    var appsLayout by str("apps.layout", "grid")         // grid | list
    var appsSort by str("apps.sort", "name")             // name | usage | recent
    var appsFilter by str("apps.filter", "all")          // all | frequent | user | system
    var hiddenApps by str("apps.hidden", "")

    // Stats / player / call
    var statsRange by int("stats.range", 120)            // seconds of history
    var playerTint by bool("player.tint", true)
    var callViz by bool("call.viz", true)
    var vizStyle by str("call.vizStyle", "bars")         // bars | wave

    // Display & power
    var keepScreenOn by bool("display.keepOn", true)
    var keepAwakeApps by bool("display.keepAwakeApps", false)
    var brightness by float("display.brightness", -1f)   // -1 = system
    var pixelShift by bool("display.pixelShift", true)
    var immersive by bool("display.immersive", true)

    // Night mode
    var nightMode by str("night.mode", "off")            // off | schedule | sensor
    var nightFrom by int("night.from", 22)
    var nightTo by int("night.to", 7)
    var nightDim by bool("night.dim", true)

    // Companion
    var port by int("companion.port", 47800)

    fun enabledCards(): List<String> {
        val off = cardsOff.split(',').filter { it.isNotEmpty() }.toSet()
        return orderedCards().filter { it !in off }.ifEmpty { listOf("home") }
    }

    fun orderedCards(): List<String> {
        val order = cardOrder.split(',').filter { it in CARD_KEYS }.distinct()
        return order + CARD_KEYS.filter { it !in order }
    }

    fun setCardEnabled(key: String, on: Boolean) {
        val off = cardsOff.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (on) off.remove(key) else off.add(key)
        cardsOff = off.joinToString(",")
    }

    fun moveCard(key: String, delta: Int) {
        val list = orderedCards().toMutableList()
        val i = list.indexOf(key)
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.removeAt(i)
        list.add(j, key)
        cardOrder = list.joinToString(",")
    }

    fun hiddenSet(): Set<String> = hiddenApps.split('|').filter { it.isNotEmpty() }.toSet()

    fun setHidden(key: String, hidden: Boolean) {
        val s = hiddenSet().toMutableSet()
        if (hidden) s.add(key) else s.remove(key)
        hiddenApps = s.joinToString("|")
    }

    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(k, value).apply() }
    }

    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(k, value).apply() }
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(k, value).apply() }
    }

    private fun float(k: String, d: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { sp.edit().putFloat(k, value).apply() }
    }
}
