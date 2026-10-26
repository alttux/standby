package dev.standby.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.standby.Lang
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class Hour(val time: Long, val temp: Float, val code: Int, val day: Boolean, val precip: Int)
class Day(val time: Long, val code: Int, val max: Float, val min: Float, val precip: Int)

class WeatherData(
    val temp: Float, val feels: Float, val humidity: Int, val wind: Float, val code: Int, val day: Boolean,
    val hours: List<Hour>, val days: List<Day>, val sunrise: Long, val sunset: Long,
    val tz: TimeZone, val fetched: Long,
)

class Place(val name: String, val region: String, val lat: Double, val lon: Double)

object WeatherRepo {
    @Volatile var data: WeatherData? = null
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var loading = false
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ctx: Context
    private const val REFRESH_MS = 30 * 60 * 1000L

    fun init(c: Context) {
        ctx = c.applicationContext
        val cached = Prefs.sp.getString("weather.cache", null)
        if (cached != null) data = try { parse(cached, Prefs.sp.getLong("weather.cacheTime", 0)) } catch (e: Exception) { null }
    }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    fun hasLocation() = !Prefs.lat.isNaN() && !Prefs.lon.isNaN()

    /** Sunrise/sunset of today in epoch millis, if known. */
    fun sunTimes(): Pair<Long, Long>? = data?.takeIf { it.sunrise > 0 && it.sunset > 0 }?.let { it.sunrise to it.sunset }

    fun refreshIfStale() {
        val d = data
        if (d == null || System.currentTimeMillis() - d.fetched > REFRESH_MS || error != null) refresh()
    }

    fun refresh() {
        if (!hasLocation() || loading) return
        loading = true
        val lat = Prefs.lat
        val lon = Prefs.lon
        io.execute {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m" +
                    "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
                    "&wind_speed_unit=ms&timezone=auto&forecast_days=8"
                val body = Http.get(url)
                val now = System.currentTimeMillis()
                val parsed = parse(body, now)
                Prefs.sp.edit().putString("weather.cache", body).putLong("weather.cacheTime", now).apply()
                main.post {
                    data = parsed
                    error = null
                    loading = false
                    Logx.i("Weather updated")
                    listeners.forEach { it() }
                }
            } catch (e: Exception) {
                Logx.w("Weather failed", e)
                main.post {
                    error = e.message ?: e.javaClass.simpleName
                    loading = false
                    listeners.forEach { it() }
                }
            }
        }
    }

    fun clear() {
        data = null
        Prefs.sp.edit().remove("weather.cache").apply()
        listeners.forEach { it() }
    }

    fun search(query: String, cb: (List<Place>?, String?) -> Unit) {
        io.execute {
            try {
                val lang = if (Lang.ru) "ru" else "en"
                val body = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(query, "UTF-8") + "&count=12&language=$lang&format=json")
                val arr = JSONObject(body).optJSONArray("results")
                val list = ArrayList<Place>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val region = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
                    list.add(Place(o.optString("name"), region, o.optDouble("latitude"), o.optDouble("longitude")))
                }
                main.post { cb(list, null) }
            } catch (e: Exception) {
                main.post { cb(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun parse(body: String, fetched: Long): WeatherData {
        val o = JSONObject(body)
        val offset = o.optInt("utc_offset_seconds", 0)
        val tz = TimeZone.getTimeZone(o.optString("timezone", "GMT")).let {
            if (it.id == "GMT" && offset != 0) TimeZone.getTimeZone("GMT").apply { rawOffset = offset * 1000 } else it
        }
        val fmtH = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
        val fmtD = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val cur = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val hours = ArrayList<Hour>()
        for (i in 0 until ht.length()) {
            hours.add(Hour(
                fmtH.parse(ht.getString(i))!!.time,
                h.getJSONArray("temperature_2m").optDouble(i, 0.0).toFloat(),
                h.getJSONArray("weather_code").optInt(i, 0),
                h.getJSONArray("is_day").optInt(i, 1) == 1,
                h.optJSONArray("precipitation_probability")?.optInt(i, 0) ?: 0,
            ))
        }
        val d = o.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = ArrayList<Day>()
        var sunrise = 0L
        var sunset = 0L
        val today = fmtD.format(java.util.Date(fetched))
        for (i in 0 until dt.length()) {
            days.add(Day(
                fmtD.parse(dt.getString(i))!!.time,
                d.getJSONArray("weather_code").optInt(i, 0),
                d.getJSONArray("temperature_2m_max").optDouble(i, 0.0).toFloat(),
                d.getJSONArray("temperature_2m_min").optDouble(i, 0.0).toFloat(),
                d.optJSONArray("precipitation_probability_max")?.optInt(i, 0) ?: 0,
            ))
            if (dt.getString(i) == today) {
                sunrise = try { fmtH.parse(d.getJSONArray("sunrise").getString(i))!!.time } catch (e: Exception) { 0L }
                sunset = try { fmtH.parse(d.getJSONArray("sunset").getString(i))!!.time } catch (e: Exception) { 0L }
            }
        }
        return WeatherData(
            temp = cur.optDouble("temperature_2m", 0.0).toFloat(),
            feels = cur.optDouble("apparent_temperature", 0.0).toFloat(),
            humidity = cur.optInt("relative_humidity_2m", 0),
            wind = cur.optDouble("wind_speed_10m", 0.0).toFloat(),
            code = cur.optInt("weather_code", 0),
            day = cur.optInt("is_day", 1) == 1,
            hours = hours, days = days, sunrise = sunrise, sunset = sunset, tz = tz, fetched = fetched,
        )
    }

    // Formatting helpers --------------------------------------------------------------

    fun temp(c: Float, unit: String = Prefs.units): String = when (unit) {
        "f" -> "${(c * 9f / 5f + 32f).roundToInt()}°"
        "k" -> "${(c + 273.15f).roundToInt()}K"
        else -> "${c.roundToInt()}°"
    }

    fun wind(ms: Float): String = when (Prefs.wind) {
        "kmh" -> "${(ms * 3.6f).roundToInt()} ${if (Lang.ru) "км/ч" else "km/h"}"
        "mph" -> "${(ms * 2.23694f).roundToInt()} mph"
        else -> "${ms.roundToInt()} ${if (Lang.ru) "м/с" else "m/s"}"
    }
}
