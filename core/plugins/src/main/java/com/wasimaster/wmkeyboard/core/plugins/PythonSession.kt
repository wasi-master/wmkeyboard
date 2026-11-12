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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One plugin's interpreter in `libwm_python_jni`, behind a handle.
 *
 * Every call blocks until the script returns, so it is only ever made on the
 * plugin's own thread. [cancel] is the exception: the watchdog and the host
 * call it from theirs, and the running call stops at its next step.
 *
 * The library writes `storage.json` and `reminders.json` itself, at the paths
 * in the [Config], in the formats [PluginStorage] and [PluginReminders] read.
 */
class PythonSession private constructor(@Volatile private var handle: Long, private val packageApi: Boolean) {

    /** The native handle, for [PluginHost] to tell sessions apart in host calls. */
    val id: Long get() = handle

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
        /** API 5: the plugin's other modules, `{"helpers.py": source}`. */
        val modules: Map<String, String> = emptyMap(),
        /** API 5: what `plugin.locale`, `plugin.theme` and `plugin.surface` say. */
        val locale: String = java.util.Locale.getDefault().toLanguageTag(),
        val dark: Boolean = false,
        /** "panel", "float" or "action". */
        val surface: String = mode,
    ) {
        fun toJson(): String = buildJsonObject {
            put("id", plugin.id)
            put("version", plugin.version)
            // The declared list: the library refuses anything outside it. Grants
            // are the keyboard's to check, on every call (PluginHost).
            put("permissions", buildJsonArray { plugin.permissions.forEach { add(JsonPrimitive(it)) } })
            put("mode", mode)
            put("apiVersion", plugin.apiVersion)
            if (plugin.isPackage) {
                put("files", buildJsonObject { for ((name, source) in modules) put(name, source) })
                put("settings", plugin.settingsJson())
                put(
                    "context",
                    buildJsonObject {
                        put("locale", locale)
                        put("theme", if (dark) "dark" else "light")
                        put("surface", surface)
                    },
                )
            }
            put("storagePath", storagePath?.let(::JsonPrimitive) ?: JsonNull)
            put("remindersPath", remindersPath?.let(::JsonPrimitive) ?: JsonNull)
            put("zone", ZoneTable.current())
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
        /** API 5: what the plugin's last render reported about itself. */
        val host: HostState? = null,
        /** API 5: permissions a call lacked a grant for, to offer in the panel. */
        val needs: List<String> = emptyList(),
        /** The Python exception kind of a failure, `"PermissionError"`. */
        val exception: String? = null,
    )

    /** An API 5 plugin's own state, as `wm._host` reports it after each render. */
    data class HostState(
        val timers: List<Timer> = emptyList(),
        val commands: List<Command> = emptyList(),
        val canBack: Boolean = false,
        val title: String? = null,
        /** Host calls still waiting for an answer. */
        val pending: Int = 0,
    ) {
        data class Timer(val id: String, val seconds: Double, val repeat: Boolean)

        data class Command(val id: String, val label: String, val icon: String?)
    }

    @Synchronized
    fun load(source: String): Result = call { PythonNative.nativeLoad(it, source) }

    @Synchronized
    fun render(): Result = call { PythonNative.nativeRender(it) }

    @Synchronized
    fun event(event: PluginEvent): Result = call { PythonNative.nativeEvent(it, eventJson(event, packageApi)) }

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

        /** The `type` of every event [eventJson] sends, in the order the editor offers them. */
        val EVENT_TYPES = listOf("click", "toggle", "input_changed", "tab_selected", "date_changed")

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
            return if (handle == 0L) null else PythonSession(handle, config.plugin.isPackage)
        }

        /**
         * The event as the library reads it. API 4: `{type, id, value?, index?}`,
         * the dict `on_event` receives. API 5: what `wm._host` routes to the
         * widget's callback (`click`, `change`, `submit`, `select`, ...).
         */
        fun eventJson(event: PluginEvent, packageApi: Boolean = false): String {
            if (event is PluginEvent.Host) return event.json
            if (packageApi) return packageEventJson(event)
            return legacyEventJson(event)
        }

        private fun packageEventJson(event: PluginEvent): String = buildJsonObject {
            put("id", event.id)
            when (event) {
                is PluginEvent.Click -> put("type", "click")
                is PluginEvent.ToggleChanged -> {
                    put("type", "change")
                    put("value", event.value)
                }
                is PluginEvent.InputChanged -> {
                    put("type", "change")
                    put("value", event.value)
                }
                is PluginEvent.TabSelected -> {
                    put("type", "change")
                    put("value", event.index)
                }
                is PluginEvent.DateChanged -> {
                    put("type", "change")
                    put("value", event.value?.let(::JsonPrimitive) ?: JsonNull)
                }
                is PluginEvent.Submitted -> {
                    put("type", "submit")
                    put("value", event.value)
                }
                is PluginEvent.ValueChanged -> {
                    put("type", "change")
                    when (event.value) {
                        "true" -> put("value", true)
                        "false" -> put("value", false)
                        else -> put("value", event.value)
                    }
                }
                is PluginEvent.NumberChanged -> {
                    put("type", "change")
                    val v = event.value
                    if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) put("value", v.toLong()) else put("value", v)
                }
                is PluginEvent.ChipsChanged -> {
                    put("type", "change")
                    if (event.multi) {
                        put("value", buildJsonArray { event.values.forEach { add(JsonPrimitive(it)) } })
                    } else {
                        put("value", event.values.firstOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
                is PluginEvent.Selected -> {
                    put("type", "select")
                    put("index", event.index)
                }
                is PluginEvent.ItemAction -> {
                    put("type", "item_action")
                    put("value", event.label)
                }
                is PluginEvent.Tapped -> {
                    put("type", "tap")
                    put("x", event.x)
                    put("y", event.y)
                }
                is PluginEvent.Dragged -> {
                    put("type", "drag")
                    put("x", event.x)
                    put("y", event.y)
                    put("dx", event.dx)
                    put("dy", event.dy)
                    put("phase", event.phase)
                }
                is PluginEvent.KeyPressed -> {
                    put("type", "press")
                    put("value", event.key)
                }
                is PluginEvent.Host -> Unit
            }
        }.toString()

        /** A host-made event: `{"type": type, ...fields}`. */
        fun hostEvent(type: String, fields: Map<String, JsonElement> = emptyMap()): PluginEvent.Host {
            val json = buildJsonObject {
                put("type", type)
                for ((k, v) in fields) put(k, v)
            }
            return PluginEvent.Host(type, json.toString(), (fields["id"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }

        private fun legacyEventJson(event: PluginEvent): String = buildJsonObject {
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

                // API 5 widgets never appear in an API 4 panel; anything else is a click.
                else -> put("type", "click")
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
                host = (root["host"] as? JsonObject)?.let(::hostState),
                needs = (root["needs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                exception = (root["error"] as? JsonObject)?.string("exception"),
            )
        }

        private fun hostState(o: JsonObject): HostState = HostState(
            timers = (o["timers"] as? JsonArray).orEmpty().mapNotNull { t ->
                val obj = t as? JsonObject ?: return@mapNotNull null
                HostState.Timer(
                    id = obj.string("id") ?: return@mapNotNull null,
                    seconds = (obj["seconds"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                    repeat = obj.bool("repeat") ?: false,
                )
            }.take(MAX_TIMERS),
            commands = (o["commands"] as? JsonArray).orEmpty().mapNotNull { c ->
                val obj = c as? JsonObject ?: return@mapNotNull null
                HostState.Command(
                    id = obj.string("id") ?: return@mapNotNull null,
                    label = PluginManifestCodec.displayText(obj.string("label").orEmpty(), MAX_COMMAND_LABEL),
                    icon = obj.string("icon"),
                )
            }.take(MAX_COMMANDS),
            canBack = o.bool("canBack") ?: false,
            title = o.string("title")?.let { PluginManifestCodec.displayText(it, PluginManifestCodec.MAX_NAME) },
            pending = (o["pending"] as? JsonPrimitive)?.intOrNull ?: 0,
        )

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
        private const val MAX_TIMERS = 16
        private const val MAX_COMMANDS = 3
        private const val MAX_COMMAND_LABEL = 24
    }
}

/**
 * The device's time zone as the interpreter reads it: every change of UTC
 * offset from 1970 to twenty years ahead, as `[fromUnixSeconds, offsetSeconds]`
 * rows. `libwm_python_jni` carries no zone database, and asking the JVM from
 * inside a script would be a callback across JNI on every `time` call, so the
 * table goes in with the config instead. Built once per zone, by sampling
 * `TimeZone` a day apart and narrowing each change to the second; a few
 * milliseconds, and a few dozen rows for a zone with summer time.
 */
internal object ZoneTable {

    @Volatile
    private var cached: Pair<String, JsonArray>? = null

    fun current(): JsonArray {
        val zone = java.util.TimeZone.getDefault()
        cached?.let { (id, table) -> if (id == zone.id) return table }
        return build(zone).also { cached = zone.id to it }
    }

    fun build(zone: java.util.TimeZone, until: Long = System.currentTimeMillis() + TWENTY_YEARS): JsonArray {
        val rows = ArrayList<JsonArray>()
        var at = 0L
        var offset = zone.getOffset(at)
        rows += row(Long.MIN_VALUE / MILLIS, offset)
        while (at < until) {
            val next = at + DAY
            val nextOffset = zone.getOffset(next)
            if (nextOffset != offset) {
                // The change is somewhere in (at, next]: narrow it to the second.
                var low = at
                var high = next
                while (high - low > MILLIS) {
                    val mid = (low + high) / 2
                    if (zone.getOffset(mid) == offset) low = mid else high = mid
                }
                rows += row(high / MILLIS, nextOffset)
                offset = nextOffset
            }
            at = next
        }
        return JsonArray(rows)
    }

    private fun row(fromSeconds: Long, offsetMillis: Int) =
        JsonArray(listOf(JsonPrimitive(fromSeconds), JsonPrimitive(offsetMillis / MILLIS.toInt())))

    private const val MILLIS = 1_000L
    private const val DAY = 24L * 60 * 60 * MILLIS
    private const val TWENTY_YEARS = 20L * 366 * DAY
}
