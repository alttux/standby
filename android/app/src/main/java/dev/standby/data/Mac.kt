package dev.standby.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import dev.standby.App
import dev.standby.ui.Themes
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fixed-size history of one metric. */
class Series(val capacity: Int = 300) {
    private val data = FloatArray(capacity)
    var size = 0
        private set
    private var head = 0

    fun add(v: Float) {
        data[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** i = 0 is the oldest of the last [n] values. */
    fun last(n: Int, i: Int): Float {
        val count = minOf(n, size)
        return data[(head - count + i + capacity * 2) % capacity]
    }

    fun latest(): Float = if (size == 0) 0f else data[(head - 1 + capacity) % capacity]

    fun max(n: Int): Float {
        var m = 0f
        for (i in 0 until minOf(n, size)) m = maxOf(m, last(n, i))
        return m
    }
}

class Media(
    val app: String, val title: String, val artist: String, val album: String,
    val duration: Float, val position: Float, var playing: Boolean, var volume: Float,
    val artworkId: String, val received: Long = SystemClock.elapsedRealtime(),
) {
    fun positionNow(): Float {
        val p = if (playing) position + (SystemClock.elapsedRealtime() - received) / 1000f else position
        return if (duration > 0) p.coerceIn(0f, duration) else p
    }
}

class CallState(
    val active: Boolean, val app: String, val since: Long, var micMuted: Boolean, var outputMuted: Boolean,
    val inputName: String, val outputName: String,
)

/**
 * Loopback TCP server the macOS companion reaches through `adb forward tcp:P tcp:P`.
 * Protocol: one JSON object per line in both directions (see README).
 */
object Mac {
    @Volatile var connected = false
        private set
    @Volatile var host = ""
        private set
    @Volatile var lastMessage = 0L
        private set

    val cpu = Series()
    val gpu = Series()
    val mem = Series()
    val netRx = Series()
    val netTx = Series()
    @Volatile var stats: JSONObject? = null
        private set
    @Volatile var media: Media? = null
        private set
    @Volatile var artwork: Bitmap? = null
        private set
    private var artworkId = ""
    @Volatile var call: CallState? = null
        private set
    /** Mac output volume 0..1, or -1 when unknown. */
    @Volatile var volume = -1f
        private set
    @Volatile var viz = FloatArray(0)
        private set

    interface Listener { fun onCompanion(topic: String) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var server: ServerThread? = null
    @Volatile private var client: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val pending = ConcurrentHashMap<Int, LinkedBlockingQueue<String>>()
    private val ids = AtomicInteger(1)
    @Volatile private var wantViz = false

    fun listen(l: Listener) { listeners.add(l) }
    fun unlisten(l: Listener) { listeners.remove(l) }

    private fun emit(topic: String) = main.post { listeners.forEach { it.onCompanion(topic) } }

    fun start(port: Int) {
        server?.shutdown()
        server = ServerThread(port).also { it.start() }
    }

    fun send(o: JSONObject) {
        val s = out ?: return
        writer.execute {
            try {
                s.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
                s.flush()
            } catch (e: Exception) {
                Logx.w("send failed", e)
            }
        }
    }

    fun command(action: String, value: Any? = null) {
        Logx.i("cmd $action ${value ?: ""}")
        send(JSONObject().put("type", "cmd").put("action", action).apply { if (value != null) put("value", value) })
        // optimistic local state
        when (action) {
            "media.playpause" -> media?.let { it.playing = !it.playing; emit("media") }
            "volume.set" -> {
                (value as? Number)?.toFloat()?.let { v -> volume = v; media?.volume = v }
                emit("media")
            }
            "call.mic" -> call?.let { it.micMuted = value as? Boolean ?: !it.micMuted; emit("call") }
            "call.output" -> call?.let { it.outputMuted = value as? Boolean ?: !it.outputMuted; emit("call") }
        }
    }

    fun subscribeViz(on: Boolean) {
        wantViz = on
        send(JSONObject().put("type", "subscribe").put("viz", on))
    }

    /** Proxies an HTTP GET through the Mac; blocking. */
    fun httpGet(url: String): String? {
        if (!connected) return null
        val id = ids.getAndIncrement()
        val q = LinkedBlockingQueue<String>(1)
        pending[id] = q
        send(JSONObject().put("type", "http").put("id", id).put("url", url))
        return try {
            q.poll(20, TimeUnit.SECONDS)?.takeIf { it.isNotEmpty() }
        } finally {
            pending.remove(id)
        }
    }

    private fun onLine(line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        lastMessage = System.currentTimeMillis()
        when (o.optString("type")) {
            "hello" -> {
                host = o.optString("name", "Mac")
                if (!connected) {
                    connected = true
                    Logx.i("Companion connected: $host")
                }
                emit("conn")
                if (wantViz) subscribeViz(true)
            }
            "ping" -> send(JSONObject().put("type", "pong").put("id", o.opt("id")))
            "stats" -> {
                cpu.add(o.optDouble("cpu", 0.0).toFloat())
                gpu.add(o.optDouble("gpu", 0.0).toFloat())
                val total = o.optDouble("memTotal", 1.0)
                mem.add((o.optDouble("memUsed", 0.0) / total * 100).toFloat())
                netRx.add(o.optDouble("netRx", 0.0).toFloat())
                netTx.add(o.optDouble("netTx", 0.0).toFloat())
                stats = o
                emit("stats")
            }
            "media" -> {
                volume = o.optDouble("volume", volume.toDouble()).toFloat()
                media = if (!o.optBoolean("available", false)) null else Media(
                    app = o.optString("app"), title = o.optString("title"), artist = o.optString("artist"),
                    album = o.optString("album"), duration = o.optDouble("duration", 0.0).toFloat(),
                    position = o.optDouble("position", 0.0).toFloat(), playing = o.optBoolean("playing"),
                    volume = o.optDouble("volume", 0.5).toFloat(), artworkId = o.optString("artworkId"),
                )
                if (media?.artworkId.isNullOrEmpty() && artwork != null) {
                    artwork = null
                    artworkId = ""
                }
                emit("media")
            }
            "artwork" -> {
                val id = o.optString("id")
                if (id != artworkId) {
                    artworkId = id
                    artwork = try {
                        val bytes = Base64.decode(o.optString("data"), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) { null }
                    emit("media")
                }
            }
            "call" -> {
                call = CallState(
                    active = o.optBoolean("active"), app = o.optString("app"),
                    since = o.optLong("since", 0L), micMuted = o.optBoolean("micMuted"),
                    outputMuted = o.optBoolean("outputMuted"), inputName = o.optString("input"),
                    outputName = o.optString("output"),
                )
                emit("call")
            }
            "viz" -> {
                val a: JSONArray = o.optJSONArray("bands") ?: return
                viz = FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }
                emit("viz")
            }
            "http" -> pending[o.optInt("id")]?.offer(if (o.optInt("status") in 200..299) o.optString("body") else "")
            "theme" -> {
                try {
                    val json = o.optJSONObject("theme")?.toString(2) ?: o.optString("json")
                    val f = Themes.save(App.instance, json, o.optString("file").ifEmpty { null })
                    Logx.i("Theme received: ${f.name}")
                    send(JSONObject().put("type", "ack").put("what", "theme").put("ok", true).put("file", f.absolutePath))
                    emit("theme")
                } catch (e: Exception) {
                    Logx.w("Bad theme", e)
                    send(JSONObject().put("type", "ack").put("what", "theme").put("ok", false).put("error", e.message))
                }
            }
        }
    }

    private fun dropClient(s: Socket) {
        if (client !== s) return
        client = null
        out = null
        connected = false
        media = null
        volume = -1f
        call = null
        viz = FloatArray(0)
        Logx.i("Companion disconnected")
        emit("conn")
    }

    private class ServerThread(val port: Int) : Thread("companion-server") {
        @Volatile private var running = true
        private var ss: ServerSocket? = null

        fun shutdown() {
            running = false
            try { ss?.close() } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
        }

        override fun run() {
            while (running) {
                try {
                    ServerSocket().use { srv ->
                        ss = srv
                        srv.reuseAddress = true
                        srv.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                        Logx.i("Companion server listening on 127.0.0.1:$port")
                        while (running) {
                            val s = srv.accept()
                            try { client?.close() } catch (_: Exception) {}
                            Thread({ handle(s) }, "companion-client").start()
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        Logx.w("Companion server error", e)
                        sleep(2000)
                    }
                }
            }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 20_000
                s.tcpNoDelay = true
                client = s
                out = s.getOutputStream()
                send(JSONObject().put("type", "hello").put("app", "StandBy").put("version", 1)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE))
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (running) {
                    val line = r.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            } catch (e: Exception) {
                // timeout or reset: fall through to cleanup
            } finally {
                try { s.close() } catch (_: Exception) {}
                dropClient(s)
            }
        }
    }
}
