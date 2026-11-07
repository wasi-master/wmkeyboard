package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One plugin's interpreter in `libwm_python`, behind a handle.
 *
 * Every call blocks until the script returns, so it is only ever made on the
 * plugin's own thread. [cancel] is the exception: the watchdog and the host
 * call it from theirs, and the running call stops at its next step.
 *
 * The library writes `storage.json` and `reminders.json` itself, at the paths
 * in the [Config], in the formats [PluginStorage] and [PluginReminders] read.
 */
class PythonSession private constructor(@Volatile private var handle: Long) {

    /**
     * Held across [cancel]'s native call and [close]'s free, never across a
     * script call: a cancel from the watchdog can then never reach a handle
     * that was freed under it, and never waits for the script it is stopping.
     */
    private val handleLock = Any()

    /** What the session may do. Turned into the JSON `create` reads. */
    data class Config(
        val plugin: InstalledPlugin,
        /** `panel` for a plugin's panel, `action` for one selection-action call. */
        val mode: String,
        /** Null keeps storage in memory; the `storage` permission still decides whether there is any. */
        val storagePath: String?,
        val remindersPath: String?,
        val load: PluginLimit = PluginLimit.LOAD,
        val event: PluginLimit = PluginLimit.EVENT,
        val render: PluginLimit = PluginLimit.RENDER,
        val action: PluginLimit = PluginLimit.ACTION,
    ) {
        fun toJson(): String = buildJsonObject {
            put("id", plugin.id)
            put("version", plugin.version)
            put("permissions", buildJsonArray { plugin.permissions.forEach { add(JsonPrimitive(it)) } })
            put("mode", mode)
            put("storagePath", storagePath?.let(::JsonPrimitive) ?: JsonNull)
            put("remindersPath", remindersPath?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "limits",
                buildJsonObject {
                    put("load", limit(load))
                    put("event", limit(event))
                    put("render", limit(render))
                    put("action", limit(action))
                },
            )
        }.toString()

        private fun limit(limit: PluginLimit) = buildJsonObject {
            put("steps", limit.instructions)
            put("millis", limit.wallMillis)
        }

        companion object {
            const val PANEL = "panel"
            const val ACTION = "action"
        }
    }

    /** Why a call failed. [abort] is set when the interpreter stopped the script rather than the script failing. */
    data class Error(val kind: String, val message: String, val line: Int?, val abort: String?) {
        companion object {
            const val COMPILE = "compile"
            const val RUNTIME = "runtime"
            const val ABORT = "abort"
            const val NO_RENDER = "no_render"
            const val NO_TRANSFORM = "no_transform"
            const val INTERNAL = "internal"
        }
    }

    /** One budgeted call a [Result] covers: what it spent against its limits. */
    data class Phase(val phase: String, val usage: PluginBudget.Usage)

    data class Result(
        val ok: Boolean,
        val ui: RenderedUi? = null,
        /** For a transform: the text, or null when it returned None. */
        val value: String? = null,
        val error: Error? = null,
        val log: List<String> = emptyList(),
        val inputs: List<Pair<String, String>> = emptyList(),
        val remindersChanged: Boolean = false,
        val phases: List<Phase> = emptyList(),
    )

    @Synchronized
    fun load(source: String): Result = call { PythonNative.nativeLoad(it, source) }

    @Synchronized
    fun render(): Result = call { PythonNative.nativeRender(it) }

    @Synchronized
    fun event(event: PluginEvent): Result = call { PythonNative.nativeEvent(it, eventJson(event)) }

    @Synchronized
    fun transform(text: String, actionId: String): Result = call { PythonNative.nativeTransform(it, text, actionId) }

    /** Stops the call in flight at its next step. Any thread, and never waits on the session's lock. */
    fun cancel() {
        synchronized(handleLock) {
            val current = handle
            if (current != 0L) PythonNative.nativeCancel(current)
        }
    }

    /** Frees the interpreter. Waits for the call in flight, if any, to return. */
    @Synchronized
    fun close() {
        val current = synchronized(handleLock) {
            handle.also { handle = 0L }
        }
        if (current != 0L) PythonNative.nativeClose(current)
    }

    private inline fun call(body: (Long) -> String): Result {
        val current = handle
        if (current == 0L) return Result(ok = false, error = Error(Error.INTERNAL, "session closed", null, null))
        return decode(body(current))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A session for [config], or null when the library is missing or
         * refused the config. A missing library is asked for on the way out,
         * so the next try finds it.
         */
        fun create(config: Config): PythonSession? {
            if (!PythonNative.available) {
                PythonNative.requestModule()
                return null
            }
            val handle = PythonNative.nativeCreate(config.toJson())
            return if (handle == 0L) null else PythonSession(handle)
        }

        /** `{type, id, value?, index?}`, the dict `on_event` receives. */
        fun eventJson(event: PluginEvent): String = buildJsonObject {
            put("id", event.id)
            when (event) {
                is PluginEvent.Click -> put("type", "click")
                is PluginEvent.ToggleChanged -> {
                    put("type", "toggle")
                    put("value", event.value)
                }

                is PluginEvent.InputChanged -> {
                    put("type", "input_changed")
                    put("value", event.value)
                }

                is PluginEvent.TabSelected -> {
                    put("type", "tab_selected")
                    put("index", event.index)
                }

                is PluginEvent.DateChanged -> {
                    put("type", "date_changed")
                    // None for a cleared date, which is how a script tells "no date".
                    put("value", event.value?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }.toString()

        /** A result as the library wrote it. Anything unreadable is an internal error rather than a crash. */
        fun decode(text: String): Result {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return Result(ok = false, error = Error(Error.INTERNAL, "unreadable result", null, null))
            return Result(
                ok = root.bool("ok") ?: false,
                ui = (root["ui"] as? JsonObject)?.let(PluginUiCodec::fromJson),
                value = (root["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                error = (root["error"] as? JsonObject)?.let { error ->
                    Error(
                        kind = error.string("kind") ?: Error.INTERNAL,
                        message = error.string("message").orEmpty(),
                        line = (error["line"] as? JsonPrimitive)?.intOrNull,
                        abort = error.string("abort"),
                    )
                },
                log = (root["log"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                inputs = (root["inputs"] as? JsonArray)?.mapNotNull { pair ->
                    val items = pair as? JsonArray ?: return@mapNotNull null
                    val id = (items.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val value = (items.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    id to value
                }.orEmpty(),
                remindersChanged = root.bool("remindersChanged") ?: false,
                phases = (root["phases"] as? JsonArray)?.mapNotNull { phase(it) }.orEmpty(),
            )
        }

        private fun phase(element: JsonElement): Phase? {
            val phase = element as? JsonObject ?: return null
            return Phase(
                phase = phase.string("phase") ?: return null,
                usage = PluginBudget.Usage(
                    instructions = phase.long("steps") ?: 0L,
                    instructionLimit = phase.long("stepsLimit") ?: 0L,
                    elapsedNanos = (phase.long("elapsedMicros") ?: 0L) * NANOS_PER_MICRO,
                    wallLimitMillis = phase.long("millisLimit") ?: 0L,
                    running = false,
                ),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        private const val NANOS_PER_MICRO = 1_000L
    }
}
