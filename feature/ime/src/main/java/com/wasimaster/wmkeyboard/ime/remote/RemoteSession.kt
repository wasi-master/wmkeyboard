package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Why a remote could not do what it was asked. */
class RemoteException(val reason: Reason, message: String? = null, cause: Throwable? = null) : Exception(message ?: reason.name, cause) {
    enum class Reason {
        /** Nothing answered at the address: the TV is off, asleep, or on another network. */
        UNREACHABLE,

        /** The TV no longer knows this phone: pair it again. */
        NOT_PAIRED,

        /** The TV said no: the prompt was declined, or the code was wrong. */
        REFUSED,

        /** A Roku with Control by mobile apps switched off. */
        BLOCKED,

        /** No answer in time: a prompt on the TV that nobody accepted. */
        TIMED_OUT,
    }
}

/**
 * A connection to one remote, opened when the panel shows it and closed when
 * the panel goes. Infrared needs no connection; the network kinds hold a
 * websocket or a TLS socket open so a press goes out at once.
 */
interface RemoteSession {
    /** The pad keys this remote can send. */
    val keys: Set<RemoteKey>

    /** Connects; throws [RemoteException] when the device cannot be reached or refuses. */
    suspend fun open()

    suspend fun press(key: RemoteKey)

    fun close()
}

/**
 * What a session learnt and wants remembered: a token, a pin, a MAC. Applied
 * to the stored device under its id.
 */
typealias RemoteLearn = ((RemoteDevice) -> RemoteDevice) -> Unit

object RemoteSessions {

    /** A session for [device], or null for a kind this build does not know. */
    fun create(context: Context, device: RemoteDevice, learn: RemoteLearn): RemoteSession? = when (device.remoteKind) {
        RemoteKind.IR -> IrSession(context.applicationContext, device)
        RemoteKind.ROKU -> RokuRemote(device)
        RemoteKind.SAMSUNG -> SamsungRemote(device, learn)
        RemoteKind.LG -> LgRemote(device, learn)
        RemoteKind.ANDROID_TV -> AndroidTvRemote(context.applicationContext, device, learn)
        null -> null
    }
}

/** The phone's own emitter: nothing to open, a code per key from the brand set or the imported ones. */
private class IrSession(private val context: Context, private val device: RemoteDevice) : RemoteSession {
    override val keys: Set<RemoteKey> = IrCatalog.keys(context, device)

    override suspend fun open() = Unit

    override suspend fun press(key: RemoteKey) {
        val code = IrCatalog.code(context, device, key) ?: return
        send(code)
    }

    suspend fun send(code: String) {
        val signal = IrCodes.parse(code) ?: return
        withContext(Dispatchers.IO) { IrBlaster.send(context, signal) }
    }

    override fun close() = Unit
}

/** An imported extra button, sent through the emitter. */
suspend fun sendIrCode(context: Context, code: String): Boolean {
    val signal = IrCodes.parse(code) ?: return false
    return withContext(Dispatchers.IO) { IrBlaster.send(context.applicationContext, signal) }
}

internal val RemoteJson = Json { ignoreUnknownKeys = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

internal fun parseObject(text: String): JsonObject? = runCatching { RemoteJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

/** The name a remote shows a TV when it asks to be allowed. */
internal const val CLIENT_NAME = "WM Keyboard"

/** The settings app's side of pairing: for every kind but Android TV, connecting once is the pairing. */
object RemotePairing {

    /**
     * Opens [id]'s session and lets it go again. Samsung and LG ask their owner
     * on the first connection and hand back a key, which the session stores;
     * Roku only has to answer. Throws [RemoteException] as the session does.
     */
    suspend fun connectOnce(context: Context, id: String) {
        val device = RemoteStore.get(id) ?: return
        val session = RemoteSessions.create(context, device) { edit -> RemoteStore.update(context, id, edit) } ?: return
        try {
            session.open()
        } finally {
            withContext(Dispatchers.IO) { session.close() }
        }
    }

    /** Sends one key from the settings app: the "try it" of a code set or a pasted code. */
    suspend fun tryCode(context: Context, code: String): Boolean = sendIrCode(context, code)
}
