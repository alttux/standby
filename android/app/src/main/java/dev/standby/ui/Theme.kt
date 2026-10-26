package dev.standby.ui

import android.content.Context
import android.graphics.Color
import dev.standby.L
import dev.standby.data.Logx
import dev.standby.data.Prefs
import dev.standby.data.WeatherRepo
import org.json.JSONObject
import java.io.File
import java.util.Calendar

class Palette(
    val dark: Boolean,
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val separator: Int,
    val text: Int,
    val text2: Int,
    val text3: Int,
    val accent: Int,
    val red: Int,
    val orange: Int,
    val yellow: Int,
    val green: Int,
    val teal: Int,
    val blue: Int,
    val purple: Int,
    val pink: Int,
) {
    fun named(name: String): Int = when (name) {
        "red" -> red
        "orange" -> orange
        "yellow" -> yellow
        "green" -> green
        "teal" -> teal
        "blue" -> blue
        "purple" -> purple
        "pink" -> pink
        "mono" -> text
        else -> accent
    }

    /** Fill for iOS-style grouped controls (switch track off, segmented track). */
    val fill: Int get() = if (dark) withAlpha(0xFF787880.toInt(), 0.32f) else withAlpha(0xFF787880.toInt(), 0.16f)

    fun toJson(): JSONObject = JSONObject().apply {
        values().forEach { (k, v) -> put(k, String.format("#%08X", v)) }
    }

    private fun values() = listOf(
        "background" to bg, "surface" to surface, "surface2" to surface2, "separator" to separator,
        "text" to text, "text2" to text2, "text3" to text3, "accent" to accent,
        "red" to red, "orange" to orange, "yellow" to yellow, "green" to green,
        "teal" to teal, "blue" to blue, "purple" to purple, "pink" to pink,
    )

    companion object {
        val ACCENTS = listOf("accent", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "mono")

        fun fromJson(o: JSONObject, dark: Boolean, base: Palette): Palette {
            fun c(key: String, fallback: Int): Int = o.optString(key).takeIf { it.isNotEmpty() }?.let {
                try { Color.parseColor(it) } catch (e: Exception) { null }
            } ?: fallback
            return Palette(
                dark = o.optBoolean("dark", dark),
                bg = c("background", base.bg), surface = c("surface", base.surface),
                surface2 = c("surface2", base.surface2), separator = c("separator", base.separator),
                text = c("text", base.text), text2 = c("text2", base.text2), text3 = c("text3", base.text3),
                accent = c("accent", base.accent), red = c("red", base.red), orange = c("orange", base.orange),
                yellow = c("yellow", base.yellow), green = c("green", base.green), teal = c("teal", base.teal),
                blue = c("blue", base.blue), purple = c("purple", base.purple), pink = c("pink", base.pink),
            )
        }
    }
}

class Scheme(val id: String, val name: String, val light: Palette, val dark: Palette, val file: File? = null) {
    val custom get() = file != null

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("light", light.toJson())
        put("dark", dark.toJson())
    }.toString(2)
}

private fun c(hex: String): Int = Color.parseColor(hex)

private fun pal(
    dark: Boolean, bg: String, surface: String, surface2: String, sep: String,
    text: String, text2: String, text3: String, accent: String,
    red: String, orange: String, yellow: String, green: String, teal: String, blue: String, purple: String, pink: String,
) = Palette(dark, c(bg), c(surface), c(surface2), c(sep), c(text), c(text2), c(text3), c(accent),
    c(red), c(orange), c(yellow), c(green), c(teal), c(blue), c(purple), c(pink))

object Themes {
    val iosLight = pal(false, "#F2F2F7", "#FFFFFF", "#E5E5EA", "#C6C6C8", "#000000", "#993C3C43", "#4D3C3C43", "#007AFF",
        "#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#30B0C7", "#007AFF", "#AF52DE", "#FF2D55")
    val iosDark = pal(true, "#000000", "#1C1C1E", "#2C2C2E", "#38383A", "#FFFFFF", "#99EBEBF5", "#4DEBEBF5", "#0A84FF",
        "#FF453A", "#FF9F0A", "#FFD60A", "#30D158", "#40C8E0", "#0A84FF", "#BF5AF2", "#FF375F")

    val builtIn: List<Scheme> = listOf(
        Scheme("ios", "iOS", iosLight, iosDark),
        Scheme("catppuccin", "Catppuccin",
            pal(false, "#E6E9EF", "#EFF1F5", "#CCD0DA", "#BCC0CC", "#4C4F69", "#6C6F85", "#9CA0B0", "#8839EF",
                "#D20F39", "#FE640B", "#DF8E1D", "#40A02B", "#179299", "#1E66F5", "#8839EF", "#EA76CB"),
            pal(true, "#1E1E2E", "#313244", "#45475A", "#585B70", "#CDD6F4", "#A6ADC8", "#6C7086", "#CBA6F7",
                "#F38BA8", "#FAB387", "#F9E2AF", "#A6E3A1", "#94E2D5", "#89B4FA", "#CBA6F7", "#F5C2E7")),
        Scheme("gruvbox", "Gruvbox",
            pal(false, "#F2E5BC", "#FBF1C7", "#EBDBB2", "#D5C4A1", "#3C3836", "#665C54", "#928374", "#AF3A03",
                "#9D0006", "#AF3A03", "#B57614", "#79740E", "#427B58", "#076678", "#8F3F71", "#B16286"),
            pal(true, "#1D2021", "#282828", "#3C3836", "#504945", "#EBDBB2", "#BDAE93", "#7C6F64", "#FE8019",
                "#FB4934", "#FE8019", "#FABD2F", "#B8BB26", "#8EC07C", "#83A598", "#D3869B", "#D3869B")),
        Scheme("nord", "Nord",
            pal(false, "#E5E9F0", "#ECEFF4", "#D8DEE9", "#C5CDDA", "#2E3440", "#4C566A", "#7B88A1", "#5E81AC",
                "#BF616A", "#D08770", "#C49A3A", "#7C9A5E", "#8FBCBB", "#5E81AC", "#B48EAD", "#B48EAD"),
            pal(true, "#2E3440", "#3B4252", "#434C5E", "#4C566A", "#ECEFF4", "#D8DEE9", "#7B88A1", "#88C0D0",
                "#BF616A", "#D08770", "#EBCB8B", "#A3BE8C", "#8FBCBB", "#81A1C1", "#B48EAD", "#B48EAD")),
        Scheme("dracula", "Dracula",
            pal(false, "#F5F1DC", "#FFFBEB", "#ECE6CC", "#D8D2B8", "#1F1F1F", "#6C664B", "#9C9670", "#644AC9",
                "#CB3A2A", "#A34D14", "#846E15", "#14710A", "#036A96", "#036A96", "#644AC9", "#A3144D"),
            pal(true, "#21222C", "#282A36", "#44475A", "#4D5066", "#F8F8F2", "#BFBFB8", "#6272A4", "#BD93F9",
                "#FF5555", "#FFB86C", "#F1FA8C", "#50FA7B", "#8BE9FD", "#8BE9FD", "#BD93F9", "#FF79C6")),
        Scheme("solarized", "Solarized",
            pal(false, "#EEE8D5", "#FDF6E3", "#E4DDC8", "#D3CBB7", "#073642", "#586E75", "#93A1A1", "#268BD2",
                "#DC322F", "#CB4B16", "#B58900", "#859900", "#2AA198", "#268BD2", "#6C71C4", "#D33682"),
            pal(true, "#002B36", "#073642", "#0E4555", "#2A5965", "#EEE8D5", "#93A1A1", "#586E75", "#268BD2",
                "#DC322F", "#CB4B16", "#B58900", "#859900", "#2AA198", "#268BD2", "#6C71C4", "#D33682")),
        Scheme("tokyonight", "Tokyo Night",
            pal(false, "#D5D6DB", "#E1E2E7", "#C4C8DA", "#B4B8CC", "#3760BF", "#6172B0", "#848CB5", "#2E7DE9",
                "#F52A65", "#B15C00", "#8C6C3E", "#587539", "#118C74", "#2E7DE9", "#9854F1", "#D20065"),
            pal(true, "#16161E", "#1A1B26", "#292E42", "#3B4261", "#C0CAF5", "#A9B1D6", "#565F89", "#7AA2F7",
                "#F7768E", "#FF9E64", "#E0AF68", "#9ECE6A", "#7DCFFF", "#7AA2F7", "#BB9AF7", "#FF007C")),
        Scheme("rosepine", "Rosé Pine",
            pal(false, "#F2E9E1", "#FFFAF3", "#F4EDE8", "#DFDAD9", "#575279", "#797593", "#9893A5", "#907AA9",
                "#B4637A", "#D7827E", "#EA9D34", "#286983", "#56949F", "#286983", "#907AA9", "#D7827E"),
            pal(true, "#191724", "#1F1D2E", "#26233A", "#403D52", "#E0DEF4", "#908CAA", "#6E6A86", "#C4A7E7",
                "#EB6F92", "#EBBCBA", "#F6C177", "#31748F", "#9CCFD8", "#31748F", "#C4A7E7", "#EBBCBA")),
    )

    @Volatile var custom: List<Scheme> = emptyList()
        private set

    /** The palette all views draw with; refreshed by [resolve]. */
    var p: Palette = iosDark
        private set

    fun all(): List<Scheme> = builtIn + custom

    fun find(id: String): Scheme = all().firstOrNull { it.id == id } ?: builtIn[0]

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "themes").apply { mkdirs() }

    fun reload(ctx: Context) {
        val list = ArrayList<Scheme>()
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            try {
                list.add(parse(f.readText(), f))
            } catch (e: Exception) {
                Logx.w("Theme ${f.name} skipped", e)
            }
        }
        custom = list
        Logx.i("Loaded ${list.size} custom theme(s) from ${dir(ctx)}")
    }

    fun parse(text: String, file: File?): Scheme {
        val o = JSONObject(text)
        val id = o.optString("id").ifEmpty { file?.nameWithoutExtension ?: "custom" }
        val name = o.optString("name").ifEmpty { id }
        val lo = o.optJSONObject("light")
        val dk = o.optJSONObject("dark")
        require(lo != null || dk != null) { "theme needs a \"light\" or \"dark\" object" }
        val light = Palette.fromJson(lo ?: dk!!, false, iosLight)
        val dark = Palette.fromJson(dk ?: lo!!, true, iosDark)
        return Scheme(if (builtIn.any { it.id == id }) "custom-$id" else id, name, light, dark, file ?: File(""))
    }

    /** Saves a scheme JSON (used by "create from current" and companion pushes). */
    fun save(ctx: Context, json: String, fileName: String? = null): File {
        val s = parse(json, null)
        val f = File(dir(ctx), (fileName ?: s.id).replace(Regex("[^A-Za-z0-9._-]"), "_").removeSuffix(".json") + ".json")
        f.writeText(json)
        reload(ctx)
        return f
    }

    fun createFromCurrent(ctx: Context): File {
        val base = find(Prefs.scheme)
        var n = 1
        while (File(dir(ctx), "my-theme-$n.json").exists()) n++
        val s = Scheme("my-theme-$n", "${L("My theme", "Моя тема")} $n", base.light, base.dark)
        return save(ctx, s.toJson(), "my-theme-$n")
    }

    fun isDarkNow(): Boolean = when (Prefs.appearance) {
        "light" -> false
        "dark" -> true
        else -> {
            val now = System.currentTimeMillis()
            val sun = WeatherRepo.sunTimes()
            if (sun != null) now < sun.first || now > sun.second
            else Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 7 || it >= 20 }
        }
    }

    /** Recomputes [p]; returns true if it changed. */
    fun resolve(): Boolean {
        val s = find(Prefs.scheme)
        val next = if (isDarkNow()) s.dark else s.light
        val changed = next !== p
        p = next
        return changed
    }

    fun accent(): Int = p.named(Prefs.accent)
}
