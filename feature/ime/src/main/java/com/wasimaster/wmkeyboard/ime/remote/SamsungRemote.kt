package com.wasimaster.wmkeyboard.ime.remote

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A Samsung Tizen TV (2016 and later) over its remote-control websocket. The
 * encrypted port 8002 hands out a token the first time the owner allows the
 * phone on the TV, and every later connection shows it; older sets only open
 * the plain port 8001 and ask nothing. Keys go out as `ms.remote.control`
 * clicks with the TV's own `KEY_` names.
 */
class SamsungRemote(private val device: RemoteDevice, private val learn: RemoteLearn) : RemoteSession {

    override val keys: Set<RemoteKey> = RemoteKey.entries.toSet()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var token: String = device.token

    @Volatile
    private var secure: Boolean = device.secure

    override suspend fun open() {
        try {
            connect(secure)
        } catch (e: RemoteException) {
            // A refusal is the owner's answer on the TV; only a dead port is worth the other one.
            if (e.reason != RemoteException.Reason.UNREACHABLE) throw e
            connect(!secure)
        }
    }

    private suspend fun connect(useTls: Boolean) {
        close()
        val opened = CompletableDeferred<Unit>()
        val name = Base64.encodeToString(CLIENT_NAME.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val url = if (useTls) {
            "wss://${device.host}:$TLS_PORT$PATH?name=$name" + (token.takeIf { it.isNotEmpty() }?.let { "&token=$it" } ?: "")
        } else {
            "ws://${device.host}:$PLAIN_PORT$PATH?name=$name"
        }
        val client: OkHttpClient = if (useTls) {
            RemoteLan.pinnedClient(device.certSha256, onSeen = { sha -> if (device.certSha256.isEmpty()) learn { it.copy(certSha256 = sha) } }, readTimeoutS = 0)
        } else {
            RemoteLan.client(readTimeoutS = 0)
        }
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseObject(text) ?: return
                when (message.str("event")) {
                    "ms.channel.connect" -> {
                        val fresh = message.child("data")?.str("token").orEmpty()
                        if (fresh.isNotEmpty() && fresh != token) {
                            token = fresh
                            learn { it.copy(token = fresh, secure = true) }
                        } else if (secure != useTls) {
                            learn { it.copy(secure = useTls) }
                        }
                        secure = useTls
                        opened.complete(Unit)
                    }
                    "ms.channel.unauthorized" -> opened.completeExceptionally(RemoteException(RemoteException.Reason.REFUSED))
                    "ms.channel.timeOut" -> opened.completeExceptionally(RemoteException(RemoteException.Reason.TIMED_OUT))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                opened.completeExceptionally(RemoteException(RemoteException.Reason.UNREACHABLE, cause = t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (socket === webSocket) socket = null
            }
        }
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            // The first time, the TV waits for the owner to press Allow.
            withTimeout(if (token.isEmpty() && useTls) PAIR_TIMEOUT_MS else CONNECT_TIMEOUT_MS) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw RemoteException(RemoteException.Reason.TIMED_OUT, cause = e)
        } catch (e: RemoteException) {
            close()
            throw e
        }
    }

    override suspend fun press(key: RemoteKey) {
        if (socket == null) open()
        val live = socket ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
        val message = buildJsonObject {
            put("method", "ms.remote.control")
            putJsonObject("params") {
                put("Cmd", "Click")
                put("DataOfCmd", keyName(key))
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            }
        }
        val sent = withContext(Dispatchers.IO) { live.send(message.toString()) }
        if (!sent) {
            socket = null
            throw RemoteException(RemoteException.Reason.UNREACHABLE)
        }
    }

    override fun close() {
        socket?.close(NORMAL_CLOSE, null)
        socket = null
    }

    companion object {
        const val TLS_PORT = 8002
        const val PLAIN_PORT = 8001
        private const val PATH = "/api/v2/channels/samsung.remote.control"
        private const val PAIR_TIMEOUT_MS = 35_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val NORMAL_CLOSE = 1000

        fun keyName(key: RemoteKey): String = when (key) {
            RemoteKey.POWER -> "KEY_POWER"
            RemoteKey.VOL_UP -> "KEY_VOLUP"
            RemoteKey.VOL_DOWN -> "KEY_VOLDOWN"
            RemoteKey.MUTE -> "KEY_MUTE"
            RemoteKey.CH_UP -> "KEY_CHUP"
            RemoteKey.CH_DOWN -> "KEY_CHDOWN"
            RemoteKey.INPUT -> "KEY_SOURCE"
            RemoteKey.UP -> "KEY_UP"
            RemoteKey.DOWN -> "KEY_DOWN"
            RemoteKey.LEFT -> "KEY_LEFT"
            RemoteKey.RIGHT -> "KEY_RIGHT"
            RemoteKey.OK -> "KEY_ENTER"
            RemoteKey.BACK -> "KEY_RETURN"
            RemoteKey.HOME -> "KEY_HOME"
            RemoteKey.MENU -> "KEY_MENU"
        }

        /**
         * `GET http://host:8001/api/v2/`: the TV's name, model and Wi-Fi MAC,
         * without pairing. Null when nothing answered.
         */
        suspend fun info(host: String, client: OkHttpClient = RemoteLan.client()): FoundDevice? = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("http://$host:$PLAIN_PORT/api/v2/").get().build()
            val body = runCatching {
                client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: return@withContext null
            val root = parseObject(body) ?: return@withContext null
            val d = root.child("device")
            val name = d?.str("name") ?: root.str("name") ?: "Samsung TV"
            FoundDevice(
                kind = RemoteKind.SAMSUNG,
                host = host,
                name = name.removePrefix("[TV] ").trim(),
                model = d?.str("modelName").orEmpty(),
                mac = d?.str("wifiMac").orEmpty(),
            )
        }
    }
}
