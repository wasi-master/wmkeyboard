package com.wasimaster.wmkeyboard.ime.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * A Roku player or Roku TV over the External Control Protocol: one HTTP POST
 * per press to port 8060, nothing to pair. Roku OS answers 403 while
 * Settings › System › Advanced system settings › Control by mobile apps is
 * set to Disabled.
 */
class RokuRemote(private val device: RemoteDevice, private val client: OkHttpClient = RemoteLan.client()) : RemoteSession {

    override val keys: Set<RemoteKey> = RemoteKey.entries.filter { keyName(it) != null }.toSet()

    override suspend fun open() {
        info(device.host, client) ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
    }

    override suspend fun press(key: RemoteKey) {
        val name = keyName(key) ?: return
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://${device.host}:$PORT/keypress/$name")
                .post("".toRequestBody(null))
                .build()
            val code = try {
                client.newCall(request).execute().use { it.code }
            } catch (e: IOException) {
                throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
            }
            when {
                code == FORBIDDEN -> throw RemoteException(RemoteException.Reason.BLOCKED)
                code !in 200..299 -> throw RemoteException(RemoteException.Reason.REFUSED, "HTTP $code")
            }
        }
    }

    override fun close() = Unit

    companion object {
        const val PORT = 8060

        /** The ECP key names. Roku has no single input key, so Input is left out. */
        fun keyName(key: RemoteKey): String? = when (key) {
            RemoteKey.POWER -> "Power"
            RemoteKey.VOL_UP -> "VolumeUp"
            RemoteKey.VOL_DOWN -> "VolumeDown"
            RemoteKey.MUTE -> "VolumeMute"
            RemoteKey.CH_UP -> "ChannelUp"
            RemoteKey.CH_DOWN -> "ChannelDown"
            RemoteKey.INPUT -> null
            RemoteKey.UP -> "Up"
            RemoteKey.DOWN -> "Down"
            RemoteKey.LEFT -> "Left"
            RemoteKey.RIGHT -> "Right"
            RemoteKey.OK -> "Select"
            RemoteKey.BACK -> "Back"
            RemoteKey.HOME -> "Home"
            // The star button, the options menu of whatever is on screen.
            RemoteKey.MENU -> "Info"
        }

        /** `GET /query/device-info`: the name the owner gave the Roku, or its model. Null when nothing answered. */
        suspend fun info(host: String, client: OkHttpClient = RemoteLan.client()): FoundDevice? = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("http://$host:$PORT/query/device-info").get().build()
            val body = runCatching {
                client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: return@withContext null
            val name = tag(body, "user-device-name") ?: tag(body, "friendly-device-name") ?: tag(body, "default-device-name")
            val model = tag(body, "friendly-model-name") ?: tag(body, "model-name").orEmpty()
            FoundDevice(RemoteKind.ROKU, host, name ?: model.ifEmpty { "Roku" }, model, tag(body, "wifi-mac") ?: tag(body, "ethernet-mac").orEmpty())
        }

        internal fun tag(xml: String, name: String): String? =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(::unescape)

        private fun unescape(s: String): String =
            s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private const val FORBIDDEN = 403
    }
}
