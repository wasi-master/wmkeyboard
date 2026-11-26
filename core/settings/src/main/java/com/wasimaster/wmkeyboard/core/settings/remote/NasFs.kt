package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.NasConfig
import com.wasimaster.wmkeyboard.core.settings.sink.NasSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * What [SynologyFs] and [QnapFs] share: the sign-in, the client and the
 * certificate rule all come from the backup's own sink for the location
 * ([S]), so the Files tool reaches the NAS exactly as the backup does. A NAS
 * whose certificate is not the pinned one is refused in the TLS handshake,
 * before any password is sent.
 *
 * The session id is kept between calls and asked for again once when the NAS
 * says it expired; any other refusal is a [Refused], which the store treats
 * as a failed call.
 *
 * Paths are `/<shared folder>/<a>/<b>`: the place's top is the list of
 * shared folders, which only the NAS's own settings can make, rename or
 * remove, so every change there is refused here.
 */
abstract class NasFs<S : NasSink>(
    protected val config: NasConfig,
    protected val sink: S,
) : RemoteFs {

    /** A refusal the NAS put in its answer, by its own code; [expired] when a new session is worth one try. */
    class Refused(val code: Int, val expired: Boolean, what: String) : IOException("$what -> error $code")

    protected val json = Json { ignoreUnknownKeys = true }

    protected val client: OkHttpClient by lazy { sink.http }

    /**
     * The same client, pin included, given longer to answer: after the last
     * byte of a large upload the NAS still has to put the file in place
     * before it replies, and a minute is not always enough.
     */
    protected val slowClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(SLOW_REPLY_MINUTES, TimeUnit.MINUTES).build()
    }

    private var sid: String? = null

    /** Signs in and returns the session id; throws when the NAS refuses or wants a two-step code. */
    protected abstract fun signIn(): String

    /** Whether a refusal [code] means the file or folder is not there. */
    protected abstract fun isMissing(code: Int): Boolean

    protected fun base(): HttpUrl = sink.address()

    /** Runs [block] with a session, signing in again once if the NAS says the old one expired. */
    protected fun <T> session(block: (String) -> T): T {
        val current = sid ?: signIn().also { sid = it }
        return try {
            block(current)
        } catch (failure: Refused) {
            if (!failure.expired) throw failure
            sid = null
            block(signIn().also { sid = it })
        }
    }

    /** One request; anything but a 2xx is a failure, and the answer is closed after [read]. */
    protected fun <T> send(request: Request, what: String, via: OkHttpClient = client, read: (Response) -> T): T =
        via.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$what -> HTTP ${response.code}")
            read(response)
        }

    protected fun elementOf(response: Response, what: String): JsonElement =
        runCatching { json.parseToJsonElement(response.body.string()) }.getOrNull()
            ?: throw IOException("$what: the NAS answered with something that is not JSON")

    protected fun objectOf(response: Response, what: String): JsonObject =
        (elementOf(response, what) as? JsonObject) ?: throw IOException("$what: the NAS answered with an unexpected shape")

    /**
     * The start of an answer that says it is JSON, read without using it up,
     * so a download can tell the NAS's error answer from a file that is
     * itself JSON. Null when it is not a JSON object.
     */
    protected fun peekObject(response: Response): JsonObject? =
        runCatching { json.parseToJsonElement(response.peekBody(PEEK_BYTES).string()).jsonObject }.getOrNull()

    /** `/share/a/b` for the names of [path]. */
    protected fun pathOf(path: List<String>): String = "/" + path.joinToString("/")

    /** A one-element JSON array, the form the NAS takes for parameters that may hold several paths or names. */
    protected fun array(value: String): String = JsonArray(listOf(JsonPrimitive(value))).toString()

    override fun stat(path: List<String>): RemoteEntry? {
        CloudFsHttp.checkNames(path)
        if (path.isEmpty()) return RemoteEntry(name = "", isDirectory = true)
        // From the folder's listing rather than a "get info" call: the listing
        // is the one call every version answers the same way, and a missing
        // folder above is plainly "nothing here".
        val siblings = try {
            list(path.dropLast(1))
        } catch (failure: Refused) {
            if (isMissing(failure.code)) return null
            throw failure
        }
        return siblings.firstOrNull { it.name == path.last() }
    }

    /**
     * Refuses when [dir] already holds [name], ignoring case and leaving out
     * [except] (the thing being renamed). Asked right before a rename or a
     * move, so a name taken since the store looked is never written over,
     * whatever the NAS would have done.
     */
    protected fun ensureFree(dir: List<String>, name: String, except: String? = null) {
        val clash = list(dir).any { it.name != except && it.name.equals(name, ignoreCase = true) }
        if (clash) throw IOException("\"$name\" is already taken there")
    }

    /** A shared folder, or the list of them, which only the NAS's own settings change. */
    protected fun refuseShareLevel(path: List<String>, what: String) {
        if (path.size < 2) throw IOException("$what: shared folders are managed on the NAS itself")
    }

    override fun close() {
        // A NAS session ends on its own after a while idle; signing out here
        // would be one more request on a connection that may be the broken one.
        sid = null
    }

    companion object {
        private const val SLOW_REPLY_MINUTES = 10L
        private const val PEEK_BYTES = 64L * 1024

        /** Records the certificate a first connection saw, exactly as the backup's run does. */
        internal fun certRecorder(context: Context, location: BackupLocation): (String) -> Unit {
            val app = context.applicationContext
            val id = location.id
            return { pin -> AutoBackupRunner.storeCertPin(app, id, pin) }
        }
    }
}
