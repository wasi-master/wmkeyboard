package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.IDN
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * One host call an API 5 plugin made, as a [PluginServices] implementation
 * sees it: already checked against the manifest, the user's grants, the
 * global risky switch and the field's state.
 */
class PluginCall(
    val plugin: InstalledPlugin,
    /** `http.request`, `field.insert`, ... (the table in `native/wm-python/src/host/v5.rs`). */
    val op: String,
    /** The script's arguments. Bytes arrive as base64 strings. */
    val args: JsonObject,
    /** "panel", "float" or "action". */
    val surface: String,
    /** For a streaming op: hands one piece to the script. */
    val emit: (JsonElement) -> Unit,
    /** False once the session that made the call is gone: a long call should stop. */
    val isActive: () -> Boolean,
) {
    fun string(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull
}

/** A refusal or failure the script receives as an exception of [kind]. */
class PluginCallException(
    val kind: String,
    message: String,
    /** A risky permission the user has not granted, offered in the panel. */
    val needs: String? = null,
) : Exception(message)

/**
 * What the keyboard actually does for a plugin. The IME supplies the field,
 * clipboard, haptics, scanners, files and share sheet; the app process the
 * network, AI and secrets; the editor's preview a pretend field. Every method
 * runs on a plugin thread or the host's call pool, never the main thread, and
 * may block.
 */
interface PluginServices {

    /**
     * Answers [call] with a JSON value, or throws [PluginCallException]. A
     * streaming op calls [PluginCall.emit] for each piece before returning
     * its end value.
     */
    fun call(call: PluginCall): JsonElement

    /** Whether the field in front is a password field or incognito text. */
    fun fieldProtected(): Boolean = false

    /** Whether Data saver stops plugins reaching the network. */
    fun dataSaver(): Boolean = false

    companion object {
        /** A host with nothing to offer: every op is unavailable. */
        val NONE: PluginServices = object : PluginServices {
            override fun call(call: PluginCall): JsonElement =
                throw PluginCallException("NotImplementedError", "${call.op} is not available here")
        }
    }
}

/**
 * The keyboard's side of an API 5 plugin's host calls.
 *
 * `libwm_python_jni` has already refused anything the manifest does not
 * declare. This is the second check, in the keyboard's own terms, before any
 * service acts:
 *
 * 1. the permission is declared, and for a [PluginPermission.Tier.RISKY] one,
 *    granted on the plugin's page while [PluginStore.allowRisky] is on;
 * 2. `field.*` and `clipboard.get` never in a password field or incognito, and
 *    the reads only while the plugin handles something the user did in its
 *    panel ([Binding.userEvent]);
 * 3. `http.request` only to https on a host [InstalledPlugin.allowedHosts]
 *    covers, never an IP address, never with Data saver on, and at most
 *    [MAX_REQUESTS_PER_MINUTE] a minute.
 *
 * A call that carries `"async": token` runs on the host's pool and answers as
 * the event `{"type": "result", "token", "value"}` (`stream` for each piece),
 * delivered through [Binding.deliver] to the session's own queue.
 */
object PluginHost {

    const val MAX_REQUESTS_PER_MINUTE = 60
    const val MAX_PENDING = 16

    /** One running session's link to the keyboard. */
    class Binding(
        val plugin: InstalledPlugin,
        val store: PluginStore?,
        val services: PluginServices,
        /** "panel", "float" or "action". */
        val surface: String,
        /** Hands a host-made event to the session's queue. */
        val deliver: (PluginEvent) -> Unit,
    ) {
        /** Set while the session handles an event the user caused in the panel. */
        @Volatile
        var userEvent: Boolean = false

        @Volatile
        var active: Boolean = true

        internal val pending = AtomicInteger(0)
        internal val requests = ArrayDeque<Long>()

        /** Time spent inside blocking host calls, which no watchdog counts against the plugin. */
        private val hostNanos = java.util.concurrent.atomic.AtomicLong(0)

        @Volatile
        private var callStarted = 0L

        internal fun <T> timed(body: () -> T): T {
            callStarted = System.nanoTime()
            try {
                return body()
            } finally {
                hostNanos.addAndGet(System.nanoTime() - callStarted)
                callStarted = 0L
            }
        }

        /** How far a watchdog should push the deadline out: every host call so far, and the one in flight. */
        fun excusedNanos(): Long {
            val started = callStarted
            return hostNanos.get() + if (started != 0L) System.nanoTime() - started else 0L
        }
    }

    private val bindings = ConcurrentHashMap<Long, Binding>()
    private val json = Json { ignoreUnknownKeys = true }

    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "wm-plugin-call").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun bind(handle: Long, binding: Binding) {
        if (handle != 0L) bindings[handle] = binding
    }

    fun unbind(handle: Long) {
        bindings.remove(handle)?.active = false
    }

    fun binding(handle: Long): Binding? = bindings[handle]

    /** `PythonNative.hostCall`: one call from the library, on the plugin thread. Reply JSON. */
    @JvmStatic
    fun call(handle: Long, op: String, argsJson: String): String {
        val binding = bindings[handle] ?: return error("RuntimeError", "this plugin session is closed")
        val args = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return try {
            check(binding, op, args)
            val token = (args["async"] as? JsonPrimitive)?.contentOrNull
            if (token == null) {
                buildJsonObject { put("value", binding.timed { run(binding, op, args) }) }.toString()
            } else {
                async(binding, op, args, token)
                buildJsonObject { put("value", JsonNull) }.toString()
            }
        } catch (refused: PluginCallException) {
            error(refused.kind, refused.message.orEmpty(), refused.needs)
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            error("RuntimeError", failure.message ?: failure.javaClass.simpleName)
        }
    }

    /** Throws when [op] may not run for [binding] right now. */
    internal fun check(binding: Binding, op: String, args: JsonObject) {
        val plugin = binding.plugin
        val permission = PluginPermission.forOp(op)
        if (permission != null) {
            if (!plugin.declares(permission)) {
                throw PluginCallException("PermissionError", "$op needs the '${permission.wire}' permission, which this plugin's manifest does not list")
            }
            if (permission.isRisky) {
                if (binding.store?.allowRisky() == false) {
                    throw PluginCallException("PermissionError", "risky permissions are turned off for all plugins", needs = permission.wire)
                }
                if (!plugin.grants(permission)) {
                    throw PluginCallException("PermissionError", "the user has not turned on '${permission.wire}' for this plugin", needs = permission.wire)
                }
            }
        }
        val group = op.substringBefore('.')
        if (group == "field" || op == "clipboard.get") {
            if (binding.services.fieldProtected()) {
                throw PluginCallException("PermissionError", "plugins cannot read or change password fields, or text in incognito")
            }
        }
        if (op in USER_ONLY && !binding.userEvent) {
            throw PluginCallException("PermissionError", "$op works only while the plugin answers something the user did in its panel")
        }
        if (op == "http.request") checkRequest(binding, args)
    }

    private fun checkRequest(binding: Binding, args: JsonObject) {
        if (binding.services.dataSaver()) {
            throw PluginCallException("ConnectionError", "Data saver is on, so plugins cannot use the internet")
        }
        val url = (args["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val host = requestHost(url) ?: throw PluginCallException("ValueError", "only https addresses with a host name can be requested: $url")
        if (!PluginManifestCodec.hostAllowed(host, binding.plugin.allowedHosts)) {
            throw PluginCallException("PermissionError", "this plugin is not allowed to connect to $host")
        }
        val now = System.currentTimeMillis()
        synchronized(binding.requests) {
            while (binding.requests.isNotEmpty() && now - binding.requests.first() > MINUTE) binding.requests.removeFirst()
            if (binding.requests.size >= MAX_REQUESTS_PER_MINUTE) {
                throw PluginCallException("ConnectionError", "too many requests: at most $MAX_REQUESTS_PER_MINUTE a minute")
            }
            binding.requests.addLast(now)
        }
    }

    /**
     * The host [url] names, lowercase and in ASCII, when it is https and a
     * name rather than an address. Null for anything else.
     */
    fun requestHost(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        val raw = uri.host ?: return null
        val host = runCatching { IDN.toASCII(raw).lowercase().trimEnd('.') }.getOrNull() ?: return null
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return null
        // An address, not a name: four numbers, or anything bracketed.
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        return host.takeIf { PluginManifestCodec.isValidHost(it) }
    }

    private fun run(binding: Binding, op: String, args: JsonObject, emit: (JsonElement) -> Unit = {}): JsonElement {
        val call = PluginCall(binding.plugin, op, args, binding.surface, emit) { binding.active }
        return binding.services.call(call)
    }

    private fun async(binding: Binding, op: String, args: JsonObject, token: String) {
        if (binding.pending.incrementAndGet() > MAX_PENDING) {
            binding.pending.decrementAndGet()
            throw PluginCallException("RuntimeError", "too many calls waiting for an answer: at most $MAX_PENDING")
        }
        val stream = (args["stream"] as? JsonPrimitive)?.contentOrNull == "true"
        pool.execute {
            val reply = try {
                val value = run(binding, op, args) { piece ->
                    if (stream && binding.active) {
                        binding.deliver(PythonSession.hostEvent("stream", mapOf("token" to JsonPrimitive(token), "value" to piece)))
                    }
                }
                value
            } catch (refused: PluginCallException) {
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("kind", refused.kind)
                            put("message", refused.message.orEmpty())
                        },
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("kind", "RuntimeError")
                            put("message", failure.message ?: failure.javaClass.simpleName)
                        },
                    )
                }
            } finally {
                binding.pending.decrementAndGet()
            }
            if (binding.active) {
                binding.deliver(PythonSession.hostEvent("result", mapOf("token" to JsonPrimitive(token), "value" to reply)))
            }
        }
    }

    private fun error(kind: String, message: String, needs: String? = null): String = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("kind", kind)
                put("message", message)
                if (needs != null) put("needs", needs)
            },
        )
    }.toString()

    private val USER_ONLY = setOf("field.selection", "field.before", "field.after", "clipboard.get")
    private const val MINUTE = 60_000L
}
