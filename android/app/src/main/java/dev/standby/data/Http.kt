package dev.standby.data

import android.content.Context
import android.net.ConnectivityManager
import dev.standby.App
import dev.standby.R
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Tiny HTTP client. Android 6 does not trust the current Let's Encrypt roots (used by
 * open-meteo.com), so the ISRG roots are bundled. If the device is offline or TLS fails,
 * the request is proxied through the macOS companion, and finally plain HTTP is tried.
 */
object Http {
    private val factory: SSLSocketFactory? by lazy {
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, id ->
                App.instance.resources.openRawResource(id).use { ks.setCertificateEntry("isrg$i", cf.generateCertificate(it)) }
            }
            val bundled = tm(ks)
            val system = tm(null)
            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }.socketFactory
        } catch (e: Exception) {
            Logx.w("TLS setup failed", e)
            null
        }
    }

    private fun tm(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    fun online(): Boolean {
        val cm = App.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    /** Blocking GET; call off the main thread. */
    /** After a failed direct request, go through the Mac first for a while. */
    @Volatile private var directBrokenUntil = 0L

    fun get(https: String): String {
        var last: Exception? = null
        val skipDirect = Mac.connected && System.currentTimeMillis() < directBrokenUntil
        if (online() && !skipDirect) {
            try { return direct(https) } catch (e: Exception) {
                last = e
                directBrokenUntil = System.currentTimeMillis() + 30 * 60_000L
                Logx.w("HTTPS failed", e)
            }
        }
        if (Mac.connected) {
            Mac.httpGet(https)?.let { return it }
        }
        if (online()) {
            try { return direct(https.replaceFirst("https://", "http://")) } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException(if (Mac.connected) "companion request failed" else "offline")
    }

    private fun direct(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) factory?.let { c.sslSocketFactory = it }
        c.connectTimeout = 6_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "StandBy-Android")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
