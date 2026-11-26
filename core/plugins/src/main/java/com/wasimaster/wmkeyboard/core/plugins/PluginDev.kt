package com.wasimaster.wmkeyboard.core.plugins

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plugin developer mode: the switch on Manage plugins that lets a computer
 * connected over adb install and update plugins ([PluginDevProvider]) and
 * read their logs from the system log.
 *
 * **Off until the user turns it on**, and kept in its own preferences file:
 * `KeyboardSettings` is at its field ceiling, and nothing on the typing path
 * reads this. The value is cached in memory so [PluginLog] can ask on every
 * line without a Context; the provider re-reads the file on every call.
 */
object PluginDevMode {

    const val PREFS_FILE = "plugin_dev"
    private const val KEY_ON = "developer_mode"

    /** The logcat tag every plugin line goes out under while the mode is on. */
    const val LOG_TAG = "WMPlugin"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: Boolean? = null

    /** Called from [PluginDevProvider.onCreate]: remembers the context, reads nothing. */
    fun attach(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, false).also { cached = it }

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        cached = on
    }

    /**
     * Whether [PluginLog] copies lines to logcat. False in a process the
     * provider never started (a JVM test, the `:crash` process) and while
     * the mode is off. The first call reads the preferences, off the main
     * thread: [PluginLog] runs on the plugin's own.
     */
    fun logsToSystem(): Boolean = cached ?: appContext?.let { runCatching { isOn(it) }.getOrDefault(false) } ?: false

    /**
     * One line of [pluginId]'s log to logcat, as `<id>: <line>`, at the level
     * its prefix suggests. Does nothing while the mode is off.
     */
    fun echo(pluginId: String, line: String) {
        if (!logsToSystem()) return
        runCatching { Log.println(priorityOf(line), LOG_TAG, "$pluginId: $line") }
    }

    /** `error:` lines are errors, `stopped:` and `abandoned:` warnings, the rest what the plugin printed. */
    fun priorityOf(line: String): Int = when {
        line.startsWith("error:") -> Log.ERROR
        line.startsWith("stopped:") || line.startsWith("abandoned:") -> Log.WARN
        else -> Log.INFO
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}

/**
 * Who may use [PluginDevProvider]: the adb shell (uid 2000) or root (uid 0),
 * and only while developer mode is on. A pure function so the rule is tested
 * without a device.
 *
 * The uid is judged first, so an app on the phone learns nothing, not even
 * whether developer mode is on.
 */
object PluginDevGate {

    /** `Process.SHELL_UID`, public only from API 29; the value is fixed. */
    const val SHELL_UID = 2000

    /** `Process.ROOT_UID`, public only from API 29. */
    const val ROOT_UID = 0

    enum class Verdict { ALLOWED, NOT_ADB, DEVELOPER_MODE_OFF }

    /** Whether [callingUid] is the adb shell's or root's. */
    fun isAdb(callingUid: Int): Boolean = callingUid == SHELL_UID || callingUid == ROOT_UID

    fun check(callingUid: Int, developerMode: Boolean): Verdict = when {
        !isAdb(callingUid) -> Verdict.NOT_ADB
        !developerMode -> Verdict.DEVELOPER_MODE_OFF
        else -> Verdict.ALLOWED
    }

    /**
     * The SecurityException text for a refusal. `wmpy` looks for the code at
     * the start, so it stays fixed; the words after it are for a person.
     */
    fun refusal(verdict: Verdict): String = when (verdict) {
        Verdict.NOT_ADB -> "wmkb_not_adb: only adb can use this"
        Verdict.DEVELOPER_MODE_OFF ->
            "wmkb_dev_mode_off: plugin developer mode is off. Turn it on in Tools / Plugins / Manage plugins / Plugin developer mode"
        Verdict.ALLOWED -> ""
    }
}

/**
 * The replies [PluginDevProvider] gives, as one line of JSON. `content call`
 * prints a reply Bundle as `Result: Bundle[{json=...}]`; with a single key
 * the CLI can cut the JSON out whatever it holds.
 *
 * Names are the manifest's own, not the user's translation: the reader is
 * the plugin's author at a terminal, and the builders stay plain Kotlin.
 */
object PluginDevReply {

    /** The Bundle key the JSON goes under. */
    const val KEY = "json"

    /** Bumped when a reply changes shape, so an older wmpy can say so. */
    const val PROTOCOL = 1

    fun error(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    /**
     * What an install did. [plugin] is the record as the store now holds it,
     * so its grants are the ones carried over from the version it replaced:
     * every risky permission it declares and the user has not granted is
     * named in `riskyOff`.
     */
    fun installed(plugin: InstalledPlugin, replaced: Boolean, reloaded: Boolean, allowRisky: Boolean): String = buildJsonObject {
        put("ok", true)
        put("protocol", PROTOCOL)
        put("action", if (replaced) "updated" else "installed")
        put("id", plugin.id)
        put("name", plugin.name)
        put("version", plugin.version)
        put("enabled", plugin.enabled)
        put("reloaded", reloaded)
        put("allowRisky", allowRisky)
        put("riskyOff", strings(riskyOff(plugin)))
        put("newSinceUpdate", strings(plugin.newSinceUpdate))
    }.toString()

    /** The declared risky permissions not granted, in declaration order. */
    fun riskyOff(plugin: InstalledPlugin): List<String> =
        plugin.declaredPermissions.filter { it.isRisky && it.wire !in plugin.granted }.map { it.wire }

    fun status(
        developerMode: Boolean,
        appVersion: String,
        appVersionCode: Long,
        pluginsOn: Boolean,
        allowRisky: Boolean,
        plugins: List<InstalledPlugin>,
    ): String = buildJsonObject {
        put("ok", true)
        put("protocol", PROTOCOL)
        put("developerMode", developerMode)
        put("appVersion", appVersion)
        put("appVersionCode", appVersionCode)
        put("pluginApi", PluginManifestCodec.API_VERSION)
        put("pluginsOn", pluginsOn)
        put("allowRisky", allowRisky)
        put(
            "plugins",
            JsonArray(
                plugins.map { plugin ->
                    buildJsonObject {
                        put("id", plugin.id)
                        put("name", plugin.name)
                        put("version", plugin.version)
                        put("enabled", plugin.enabled)
                        put("riskyOff", strings(riskyOff(plugin)))
                    }
                },
            ),
        )
    }.toString()

    fun reloaded(id: String, open: Boolean): String = buildJsonObject {
        put("ok", true)
        put("id", id)
        put("reloaded", open)
    }.toString()

    fun log(id: String, lines: List<String>): String = buildJsonObject {
        put("ok", true)
        put("id", id)
        put("lines", strings(lines))
    }.toString()

    fun part(bytes: Int): String = buildJsonObject {
        put("ok", true)
        put("bytes", bytes)
    }.toString()

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    /** For tests: the reply as an object again. */
    fun parse(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
}

/**
 * Archives on their way in, by the token the CLI chose. `content write`
 * streams one through a pipe ([fromPipe]); an Android 7 or 8 shell has no
 * `content write`, so the CLI sends base64 pieces through `content call`
 * instead ([part]). Either way [take] hands over the whole archive once.
 *
 * Bounded: a few uploads at a time, each at most [maxBytes].
 */
class PluginDevUploads(private val maxBytes: Int = MAX_BYTES, private val maxPending: Int = MAX_PENDING) {

    /** One archive. [done] opens once its pipe has closed; a piece-by-piece upload never waits. */
    class Upload(val viaPipe: Boolean) {
        val bytes = ByteArrayOutputStream()

        @Volatile
        var overflow = false

        /** The pipe failed before its end: what arrived is not the archive. */
        @Volatile
        var broken = false

        val done = CountDownLatch(if (viaPipe) 1 else 0)
    }

    /** What [take] found. */
    sealed interface Taken {
        data class Ok(val bytes: ByteArray) : Taken

        data object Unknown : Taken

        data object TooLarge : Taken

        data object StillArriving : Taken

        data object Empty : Taken

        data object Broken : Taken
    }

    private val pending = LinkedHashMap<String, Upload>()

    /** Starts the upload [token] names, dropping the oldest past [maxPending]. Null for a bad token. */
    @Synchronized
    fun begin(token: String, viaPipe: Boolean): Upload? {
        if (!isToken(token)) return null
        pending.remove(token)
        while (pending.size >= maxPending) pending.remove(pending.keys.first())
        return Upload(viaPipe).also { pending[token] = it }
    }

    /** Appends [chunk] to [upload], or marks it overflowed. */
    fun append(upload: Upload, chunk: ByteArray, length: Int = chunk.size) {
        synchronized(upload) {
            if (upload.overflow) return
            if (upload.bytes.size() + length > maxBytes) {
                upload.overflow = true
                upload.bytes.reset()
                return
            }
            upload.bytes.write(chunk, 0, length)
        }
    }

    /**
     * Reads [input] to its end into [upload], then opens its [Upload.done].
     * A read that fails marks the upload [Upload.broken] instead of throwing.
     */
    fun fromPipe(upload: Upload, input: java.io.InputStream) {
        try {
            input.use { stream ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    append(upload, buffer, n)
                }
            }
        } catch (_: java.io.IOException) {
            upload.broken = true
        } finally {
            upload.done.countDown()
        }
    }

    /**
     * One base64 piece, `<token>:<base64>`. The reply's byte count is what
     * the upload holds now; null when [arg] is not a piece.
     */
    fun part(arg: String): Int? {
        val colon = arg.indexOf(':')
        if (colon <= 0) return null
        val token = arg.substring(0, colon)
        if (!isToken(token)) return null
        val bytes = runCatching { kotlin.io.encoding.Base64.decode(arg.substring(colon + 1)) }.getOrNull() ?: return null
        val upload = synchronized(this) { pending[token]?.takeIf { !it.viaPipe } } ?: begin(token, viaPipe = false) ?: return null
        append(upload, bytes)
        return synchronized(upload) { if (upload.overflow) -1 else upload.bytes.size() }
    }

    /** The whole archive [token] names, once; waits up to [waitMillis] for a pipe still open. */
    fun take(token: String, waitMillis: Long = WAIT_MILLIS): Taken {
        val upload = synchronized(this) { pending[token] } ?: return Taken.Unknown
        if (!upload.done.await(waitMillis, TimeUnit.MILLISECONDS)) return Taken.StillArriving
        synchronized(this) { if (pending[token] === upload) pending.remove(token) }
        return synchronized(upload) {
            when {
                upload.broken -> Taken.Broken
                upload.overflow -> Taken.TooLarge
                upload.bytes.size() == 0 -> Taken.Empty
                else -> Taken.Ok(upload.bytes.toByteArray())
            }
        }
    }

    companion object {
        /** An archive's contents are capped at [PluginFile.MAX_TOTAL_BYTES]; the zip's own headers fit in the rest. */
        const val MAX_BYTES = PluginFile.MAX_TOTAL_BYTES.toInt() + 256 * 1024
        const val MAX_PENDING = 4
        const val WAIT_MILLIS = 20_000L
        private const val BUFFER = 16 * 1024
        private val TOKEN = Regex("^[a-z0-9]{8,32}$")

        fun isToken(token: String): Boolean = TOKEN.matches(token)
    }
}
