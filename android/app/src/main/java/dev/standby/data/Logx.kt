package dev.standby.data

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat (tag "StandBy", read by the macOS companion) plus a small in-app ring buffer. */
object Logx {
    const val TAG = "StandBy"
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) = add('I', msg)
    fun w(msg: String, t: Throwable? = null) = add('W', if (t != null) "$msg: ${t.javaClass.simpleName} ${t.message}" else msg)

    private fun add(level: Char, msg: String) {
        if (level == 'W') Log.w(TAG, msg) else Log.i(TAG, msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())} $level $msg")
            while (lines.size > 300) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
