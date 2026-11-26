package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds TVs and players on the local network: one SSDP search for Roku,
 * Samsung and LG, and an mDNS browse for Android TV's remote service, for a
 * few seconds. Each find is named by asking the device itself where it has an
 * open info endpoint (Roku, Samsung); LG says its name in the SSDP answer and
 * Android TV in its service name.
 */
object RemoteDiscovery {

    suspend fun scan(context: Context, onFound: (FoundDevice) -> Unit) = coroutineScope {
        val seen = ConcurrentHashMap.newKeySet<String>()
        fun found(device: FoundDevice) {
            if (seen.add(device.kind.id + "|" + device.host)) onFound(device)
        }
        val mdns = AtvMdns(context)
        mdns.start { found(it) }
        try {
            val replies = withContext(Dispatchers.IO) { RemoteLan.ssdp(context, listOf(ROKU_ST, SAMSUNG_ST, LG_ST)) }
            replies.map { reply ->
                async(Dispatchers.IO) {
                    val st = reply["st"].orEmpty()
                    when {
                        st.equals(ROKU_ST, ignoreCase = true) -> RokuRemote.info(reply.host) ?: FoundDevice(RemoteKind.ROKU, reply.host, "Roku")
                        st.equals(SAMSUNG_ST, ignoreCase = true) -> SamsungRemote.info(reply.host) ?: FoundDevice(RemoteKind.SAMSUNG, reply.host, "Samsung TV")
                        st.equals(LG_ST, ignoreCase = true) -> FoundDevice(RemoteKind.LG, reply.host, lgName(reply) ?: "LG TV")
                        else -> null
                    }
                }
            }.awaitAll().filterNotNull().forEach { found(it) }
            // mDNS answers trickle in after SSDP's window.
            delay(MDNS_GRACE_MS)
        } finally {
            mdns.stop()
        }
    }

    private fun lgName(reply: RemoteLan.SsdpReply): String? =
        reply["dlnadevicename.lge.com"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.takeIf { it.isNotBlank() }

    /** Checks an address typed by hand: what answers there, for the name the row will carry. */
    suspend fun probe(kind: RemoteKind, host: String): FoundDevice? = when (kind) {
        RemoteKind.ROKU -> RokuRemote.info(host)
        RemoteKind.SAMSUNG -> SamsungRemote.info(host)
        else -> null
    }

    private const val ROKU_ST = "roku:ecp"
    private const val SAMSUNG_ST = "urn:samsung.com:device:RemoteControlReceiver:1"
    private const val LG_ST = "urn:lge-com:service:webos-second-screen:1"
    private const val MDNS_GRACE_MS = 1500L

    /** Android TV's remote service, `_androidtvremote2._tcp`, by Android's own mDNS. */
    private class AtvMdns(context: Context) {
        private val app = context.applicationContext
        private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        private var discovery: NsdManager.DiscoveryListener? = null
        private var multicast: WifiManager.MulticastLock? = null

        fun start(onFound: (FoundDevice) -> Unit) {
            val manager = nsd ?: return
            runCatching {
                val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicast = wifi?.createMulticastLock("wmkb-remote-mdns")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    @Suppress("DEPRECATION") // resolveService: its replacement is API 34, and this floor is 24.
                    runCatching {
                        manager.resolveService(
                            service,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    val host = info.host?.hostAddress ?: return
                                    onFound(FoundDevice(RemoteKind.ANDROID_TV, host, info.serviceName ?: "Android TV"))
                                }
                            },
                        )
                    }
                }
            }
            runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { discovery = listener }
        }

        fun stop() {
            discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
            discovery = null
            runCatching { multicast?.release() }
            multicast = null
        }

        companion object {
            const val SERVICE_TYPE = "_androidtvremote2._tcp"
        }
    }
}
