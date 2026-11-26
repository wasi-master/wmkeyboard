package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import android.net.wifi.WifiManager
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.track
import okhttp3.OkHttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The Remote tool's traffic on the local network, every piece of it a row in
 * the network activity log under [NetSource.REMOTE]: HTTP and websockets
 * through OkHttp with the internet gate and the log's interceptor, the UDP of
 * discovery and Wake-on-LAN, and Android TV's TLS sockets (counted where they
 * open, in [AndroidTvRemote]).
 */
object RemoteLan {

    /** For HTTP and websockets to a device on the local network. [readTimeoutS] 0 holds a websocket open. */
    fun client(readTimeoutS: Long = READ_TIMEOUT_S): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(InternetGate)
        .addNetworkInterceptor(NetLogInterceptor(NetSource.REMOTE, route = { it.encodedPath }))
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(readTimeoutS, TimeUnit.SECONDS)
        .pingInterval(PING_S, TimeUnit.SECONDS)
        .build()

    /**
     * The same over a TV's own certificate. TVs sign theirs themselves, so no
     * store knows it and no hostname matches it: the first one seen is pinned
     * through [onSeen], and a later connection that shows another is refused.
     */
    fun pinnedClient(pin: String, onSeen: (String) -> Unit, readTimeoutS: Long = READ_TIMEOUT_S): OkHttpClient {
        val trust = PinningTrust(pin, onSeen)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return client(readTimeoutS).newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** A TLS context that presents [keys] (Android TV's client certificate) and pins the TV's. */
    fun tlsContext(keys: Array<KeyManager>?, trust: PinningTrust): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keys, arrayOf(trust), SecureRandom()) }

    /** Trusts the first certificate a TV shows, then only that one. */
    class PinningTrust(pin: String, private val onSeen: (String) -> Unit) : X509TrustManager {
        private val pinned = pin.trim().lowercase().takeIf { it.isNotEmpty() }

        /** The leaf the TV showed on this connection, for Android TV's pairing hash. */
        @Volatile
        var seen: X509Certificate? = null
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            val sha = sha256(leaf.encoded)
            if (pinned != null && sha != pinned) throw CertificateException("the TV's certificate changed")
            seen = leaf
            onSeen(sha)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The Wake-on-LAN magic packet for [mac], broadcast to port 9: six 0xFF
     * bytes and the address sixteen times. A TV that keeps its network up in
     * standby (Samsung's "Power on with mobile", LG's "Turn on via Wi-Fi")
     * wakes on it; one that does not stays off.
     */
    fun wake(mac: String): Boolean {
        val bytes = macBytes(mac) ?: return false
        val payload = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(bytes, 0, payload, 6 + i * 6, 6)
        val call = NetLog.callTo(NetSource.REMOTE, method = "WAKE", scheme = "udp", host = BROADCAST, port = WOL_PORT, route = null)
        return call.track {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(BROADCAST), WOL_PORT))
            }
            it.sent(payload.size.toLong())
            true
        }
    }

    internal fun macBytes(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        val out = ByteArray(6)
        for (i in 0 until 6) out[i] = (hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return out
    }

    /** One answer to an SSDP search: who answered, and the headers they sent. */
    data class SsdpReply(val host: String, val headers: Map<String, String>) {
        operator fun get(name: String): String? = headers[name.lowercase()]
    }

    /**
     * An SSDP search on the local network for each of [targets], collecting the
     * answers for [windowMs]. Answers come back to the sending port, unicast,
     * but some phones drop them without a multicast lock, so one is held for
     * the window.
     */
    fun ssdp(context: Context, targets: List<String>, windowMs: Int = SSDP_WINDOW_MS): List<SsdpReply> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("wmkb-remote")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val call = NetLog.callTo(NetSource.REMOTE, method = "SEARCH", scheme = "udp", host = SSDP_GROUP, port = SSDP_PORT, route = null)
        try {
            return call.track { log ->
                val out = LinkedHashMap<String, SsdpReply>()
                DatagramSocket(null as java.net.SocketAddress?).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    socket.soTimeout = SSDP_READ_MS
                    val group = InetAddress.getByName(SSDP_GROUP)
                    repeat(2) {
                        for (target in targets) {
                            val message = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_GROUP:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                            val bytes = message.toByteArray(Charsets.US_ASCII)
                            socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                            log.sent(bytes.size.toLong())
                        }
                    }
                    val deadline = System.currentTimeMillis() + windowMs
                    val buffer = ByteArray(BUFFER)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        log.received(packet.length.toLong())
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val host = packet.address?.hostAddress ?: continue
                        val reply = SsdpReply(host, headers(text))
                        val key = host + "|" + (reply["st"] ?: "")
                        out.putIfAbsent(key, reply)
                    }
                }
                out.values.toList()
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    internal fun headers(text: String): Map<String, String> = text.split("\r\n", "\n")
        .drop(1)
        .mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        .toMap()

    private const val CONNECT_TIMEOUT_S = 4L
    private const val READ_TIMEOUT_S = 8L
    private const val PING_S = 20L
    private const val BROADCAST = "255.255.255.255"
    private const val WOL_PORT = 9
    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_WINDOW_MS = 3000
    private const val SSDP_READ_MS = 400
    private const val BUFFER = 4096
}
