package dev.standby

import android.app.Application
import dev.standby.data.AppRepo
import dev.standby.data.Mac
import dev.standby.data.Prefs
import dev.standby.data.WeatherRepo
import dev.standby.ui.Fonts
import dev.standby.ui.Gfx
import dev.standby.ui.Themes
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Gfx.init(this)
        Fonts.init(this)
        Themes.reload(this)
        WeatherRepo.init(this)
        AppRepo.init(this)
        Mac.start(Prefs.port)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

object Lang {
    val ru: Boolean = Locale.getDefault().language == "ru"
}

/** Inline localisation: English and Russian strings live side by side. */
fun L(en: String, ru: String): String = if (Lang.ru) ru else en
