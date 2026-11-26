package com.wasimaster.wmkeyboard.ime.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An LG webOS TV over its second-screen websocket: `wss://host:3001` on
 * firmware from about 2022, `ws://host:3000` before. The first registration
 * makes the TV ask its owner; accepting hands back a client key that every
 * later registration shows. Buttons go down a second socket, the pointer input
 * socket the TV names on request; power off is a request of its own, and a TV
 * that is off wakes on a Wake-on-LAN packet when "Turn on via Wi-Fi" is set.
 *
 * The registration manifest is the one LG's own remote app uses, as
 * aiowebostv (Apache License 2.0) carries it; newer firmware refuses its
 * signature, so a refused signed registration is retried unsigned, with the
 * same permissions.
 */
class LgRemote(private val device: RemoteDevice, private val learn: RemoteLearn) : RemoteSession {

    override val keys: Set<RemoteKey> = RemoteKey.entries.toSet()

    @Volatile private var main: WebSocket? = null
    @Volatile private var pointer: WebSocket? = null
    @Volatile private var clientKey: String = device.token
    @Volatile private var secure: Boolean = device.secure
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val ids = AtomicInteger(1)

    override suspend fun open() {
        close()
        try {
            connect(secure)
        } catch (e: RemoteException) {
            if (e.reason != RemoteException.Reason.UNREACHABLE) throw e
            connect(!secure)
        }
        // The MAC, for waking it later: asked once, answered only to a registered client.
        if (device.mac.isEmpty()) {
            runCatching {
                val reply = request("ssap://com.webos.service.connectionmanager/getinfo")
                val payload = reply.child("payload")
                val mac = payload?.child("wiredInfo")?.str("macAddress")?.takeIf { it.isNotBlank() }
                    ?: payload?.child("wifiInfo")?.str("macAddress").orEmpty()
                if (mac.isNotBlank()) learn { it.copy(mac = mac) }
            }
        }
    }

    private suspend fun connect(useTls: Boolean) {
        val http = if (useTls) {
            RemoteLan.pinnedClient(device.certSha256, onSeen = { sha -> if (device.certSha256.isEmpty()) learn { it.copy(certSha256 = sha) } }, readTimeoutS = 0)
        } else {
            RemoteLan.client(readTimeoutS = 0)
        }
        val opened = CompletableDeferred<Unit>()
        val url = if (useTls) "wss://${device.host}:$TLS_PORT" else "ws://${device.host}:$PLAIN_PORT"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = parseObject(text) ?: return
                val id = message.str("id")
                if (id == REGISTER_ID) {
                    val waiting = registering ?: return
                    when (message.str("type")) {
                        // PROMPT: the TV is asking its owner; the answer is the next message.
                        "response" -> Unit
                        "registered" -> {
                            val key = message.child("payload")?.str("client-key").orEmpty().ifEmpty { clientKey }
                            if (key.isEmpty()) {
                                waiting.completeExceptionally(RemoteException(RemoteException.Reason.REFUSED))
                            } else {
                                waiting.complete(key)
                            }
                        }
                        "error" -> waiting.completeExceptionally(RemoteException(RemoteException.Reason.REFUSED, message.str("error")))
                    }
                    return
                }
                if (id != null) pending.remove(id)?.complete(message)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (main === webSocket) main = null
                val e = RemoteException(RemoteException.Reason.UNREACHABLE, cause = t)
                opened.completeExceptionally(e)
                registering?.completeExceptionally(e)
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (main === webSocket) main = null
            }
        }
        val socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
        main = socket
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
        } catch (e: RemoteException) {
            close()
            throw e
        }
        val key = register(socket, signed = true) ?: register(socket, signed = false) ?: run {
            close()
            throw RemoteException(RemoteException.Reason.REFUSED)
        }
        if (key != clientKey || secure != useTls) {
            clientKey = key
            learn { it.copy(token = key, secure = useTls) }
        }
        secure = useTls
        val path = request("ssap://com.webos.service.networkinput/getPointerInputSocket").child("payload")?.str("socketPath")
            ?: throw RemoteException(RemoteException.Reason.REFUSED, "no input socket")
        pointer = http.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (pointer === webSocket) pointer = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (pointer === webSocket) pointer = null
            }
        })
    }

    /** The registration waiting on its answer; the main socket's listener completes it. */
    @Volatile private var registering: CompletableDeferred<String>? = null

    /**
     * Sends the registration and waits for the client key. A prompt waits on
     * the owner for up to a minute. Null when a signed registration was
     * refused, which newer firmware does to the signature: try unsigned.
     */
    private suspend fun register(socket: WebSocket, signed: Boolean): String? {
        val answer = CompletableDeferred<String>()
        registering = answer
        if (!socket.send(registrationMessage(signed, clientKey))) throw RemoteException(RemoteException.Reason.UNREACHABLE)
        return try {
            withTimeout(if (clientKey.isEmpty()) PAIR_TIMEOUT_MS else CONNECT_TIMEOUT_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw RemoteException(RemoteException.Reason.TIMED_OUT, cause = e)
        } catch (e: RemoteException) {
            if (e.reason == RemoteException.Reason.REFUSED && signed) null else throw e
        } finally {
            if (registering === answer) registering = null
        }
    }

    private suspend fun request(uri: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val socket = main ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
        val id = "wmkb_" + ids.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val message = buildJsonObject {
            put("id", id)
            put("type", "request")
            put("uri", uri)
            put("payload", payload)
        }
        if (!socket.send(message.toString())) {
            pending.remove(id)
            throw RemoteException(RemoteException.Reason.UNREACHABLE)
        }
        return try {
            withTimeout(CONNECT_TIMEOUT_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw RemoteException(RemoteException.Reason.TIMED_OUT, cause = e)
        }
    }

    override suspend fun press(key: RemoteKey) {
        if (main == null) open()
        if (key == RemoteKey.POWER) {
            request("ssap://system/turnOff")
            return
        }
        val socket = pointer ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
        val sent = withContext(Dispatchers.IO) { socket.send("type:button\nname:${buttonName(key)}\n\n") }
        if (!sent) {
            pointer = null
            throw RemoteException(RemoteException.Reason.UNREACHABLE)
        }
    }

    override fun close() {
        pointer?.close(NORMAL_CLOSE, null)
        main?.close(NORMAL_CLOSE, null)
        pointer = null
        main = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    companion object {
        const val TLS_PORT = 3001
        const val PLAIN_PORT = 3000
        private const val REGISTER_ID = "register_0"
        private const val PAIR_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val NORMAL_CLOSE = 1000

        fun buttonName(key: RemoteKey): String = when (key) {
            RemoteKey.POWER -> "POWER"
            RemoteKey.VOL_UP -> "VOLUMEUP"
            RemoteKey.VOL_DOWN -> "VOLUMEDOWN"
            RemoteKey.MUTE -> "MUTE"
            RemoteKey.CH_UP -> "CHANNELUP"
            RemoteKey.CH_DOWN -> "CHANNELDOWN"
            RemoteKey.INPUT -> "INPUT_HUB"
            RemoteKey.UP -> "UP"
            RemoteKey.DOWN -> "DOWN"
            RemoteKey.LEFT -> "LEFT"
            RemoteKey.RIGHT -> "RIGHT"
            RemoteKey.OK -> "ENTER"
            RemoteKey.BACK -> "BACK"
            RemoteKey.HOME -> "HOME"
            RemoteKey.MENU -> "MENU"
        }

        /** The registration, signed or with the signed block's permissions folded into the plain list. */
        internal fun registrationMessage(signed: Boolean, clientKey: String): String {
            val manifest = parseObject(MANIFEST) ?: JsonObject(emptyMap())
            val body = if (signed) {
                manifest
            } else {
                val signedPermissions = manifest.child("signed")?.get("permissions")
                val merged = (manifest["permissions"].stringList() + signedPermissions.stringList()).distinct().sorted()
                JsonObject(
                    manifest.filterKeys { it != "signatures" && it != "signed" } +
                        ("permissions" to kotlinx.serialization.json.JsonArray(merged.map { JsonPrimitive(it) })),
                )
            }
            val payload = buildJsonObject {
                put("forcePairing", false)
                put("pairingType", "PROMPT")
                if (clientKey.isNotEmpty()) put("client-key", clientKey)
                put("manifest", body)
            }
            return buildJsonObject {
                put("type", "register")
                put("id", REGISTER_ID)
                put("payload", payload)
            }.toString()
        }

        private fun kotlinx.serialization.json.JsonElement?.stringList(): List<String> =
            (this as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

        /** LG's remote app manifest, from aiowebostv's handshake.py (Apache License 2.0). */
        private const val MANIFEST = """{"appVersion":"1.1","manifestVersion":1,"permissions":["LAUNCH","LAUNCH_WEBAPP","APP_TO_APP","CLOSE","TEST_OPEN","TEST_PROTECTED","CONTROL_AUDIO","CONTROL_DISPLAY","CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","CONTROL_POWER","CONTROL_TV_SCREEN","READ_APP_STATUS","READ_CURRENT_CHANNEL","READ_INPUT_DEVICE_LIST","READ_NETWORK_STATE","READ_RUNNING_APPS","READ_TV_CHANNEL_LIST","WRITE_NOTIFICATION_TOAST","READ_POWER_STATE","READ_COUNTRY_INFO","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_SETTINGS"],"signatures":[{"signature":"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw==","signatureVersion":1}],"signed":{"appId":"com.lge.test","created":"20140509","localizedAppNames":{"":"LG Remote App","ko-KR":"리모컨 앱","zxx-XX":"ЛГ Rэmоtэ AПП"},"localizedVendorNames":{"":"LG Electronics"},"permissions":["TEST_SECURE","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_LGE_SDX","READ_NOTIFICATIONS","SEARCH","WRITE_SETTINGS","WRITE_NOTIFICATION_ALERT","CONTROL_POWER","READ_CURRENT_CHANNEL","READ_RUNNING_APPS","READ_UPDATE_INFO","UPDATE_FROM_REMOTE_APP","READ_LGE_TV_INPUT_EVENTS","READ_TV_CURRENT_TIME"],"serial":"2f930e2d2cfe083771f68e4fe7bb07","vendorId":"com.lge"}}"""
    }
}
