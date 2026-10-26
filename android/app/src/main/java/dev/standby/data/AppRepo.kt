package dev.standby.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.text.Collator
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class AppEntry(val label: String, val pkg: String, val cls: String, val system: Boolean, val installed: Long) {
    val key get() = "$pkg/$cls"
    val component get() = ComponentName(pkg, cls)
}

object AppRepo {
    @Volatile var apps: List<AppEntry> = emptyList()
        private set

    private lateinit var ctx: Context
    private lateinit var usage: SharedPreferences
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val icons = LruCache<String, Drawable>(300)
    private val loadingIcons = HashSet<String>()

    fun init(c: Context) {
        ctx = c.applicationContext
        usage = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.data?.schemeSpecificPart?.let { pkg -> icons.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { icons.remove(it) } }
                load()
            }
        }, f)
        load()
    }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    fun load() {
        io.execute {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val coll = Collator.getInstance()
            val list = pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map {
                    val ai = it.activityInfo
                    val installed = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: Exception) { 0L }
                    AppEntry(
                        it.loadLabel(pm).toString().trim(), ai.packageName, ai.name,
                        ai.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        installed,
                    )
                }
                .sortedWith { a, b -> coll.compare(a.label, b.label) }
            main.post {
                apps = list
                listeners.forEach { it() }
            }
        }
    }

    /** Returns a cached icon or null, loading it asynchronously and calling [ready] on the main thread. */
    fun icon(e: AppEntry, ready: () -> Unit): Drawable? {
        icons.get(e.key)?.let { return it }
        synchronized(loadingIcons) { if (!loadingIcons.add(e.key)) return null }
        io.execute {
            val d = try { ctx.packageManager.getActivityIcon(e.component) } catch (x: Exception) { ctx.packageManager.defaultActivityIcon }
            main.post {
                icons.put(e.key, d)
                synchronized(loadingIcons) { loadingIcons.remove(e.key) }
                ready()
            }
        }
        return null
    }

    fun launched(e: AppEntry) {
        usage.edit().putInt("n:${e.key}", count(e) + 1).putLong("t:${e.key}", System.currentTimeMillis()).apply()
    }

    fun count(e: AppEntry) = usage.getInt("n:${e.key}", 0)
    fun lastUsed(e: AppEntry) = usage.getLong("t:${e.key}", 0)
    fun resetUsage() = usage.edit().clear().apply()
}
