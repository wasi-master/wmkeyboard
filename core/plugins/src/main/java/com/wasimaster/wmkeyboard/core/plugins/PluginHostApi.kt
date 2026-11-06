package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import kotlin.math.abs
import kotlin.math.floor

/**
 * The `wm` table — everything a plugin can reach outside its own arithmetic.
 *
 * The complete list is `wm.storage` and `wm.reminders` (each only when
 * declared), `wm.json`, `wm.log` and `wm.ui.set_input`. That is not an
 * abridged summary; it is the whole API. A selection action's call
 * ([forAction]) gets the same table without `wm.ui`, since there is no panel
 * for it to write into.
 *
 * There is no `wm.text`, no `wm.clipboard` and no `wm.http`, and their absence
 * is the security model rather than a backlog. A keyboard plugin that could read
 * the text field and also open a socket is a keylogger with extra steps, and no
 * consent dialog makes that untrue. Text reaches a plugin only when the user
 * types or pastes into one of its own visible inputs, or selects text and taps
 * one of its action chips, which hands `transform` that selection as an
 * argument. Results leave only when the user taps the host's own Insert button
 * on an output widget, or as the string `transform` returns.
 *
 * [PluginHostApiTest] asserts the reachable surface against an explicit list, so
 * a future accidental addition fails the build rather than shipping quietly.
 */
class PluginHostApi(
    private val plugin: InstalledPlugin,
    private val log: PluginLog,
    /** Present only when the manifest declared `storage`. */
    private val storage: PluginStorage?,
    /** Collects `wm.ui.set_input` calls; the runtime applies them after the handler returns. */
    private val setInput: (String, String) -> Unit,
    /** True once this session has been abandoned; every call then refuses. */
    private val revoked: () -> Boolean,
    /** Hears every `wm.log` line as it is written, for the plugin editor's console. */
    private val onLine: (String) -> Unit = {},
    /** Present only when the manifest declared `reminders`. */
    private val reminders: PluginReminders? = null,
    /** A selection action's call rather than a panel session: no `wm.ui`. */
    private val forAction: Boolean = false,
) {

    companion object {
        /** Bumped when the `wm` surface changes in a way a script can notice. */
        const val API_VERSION = PluginManifestCodec.API_VERSION

        private const val MAX_JSON_DEPTH = 24
        private const val MAX_JSON_CHARS = 256 * 1024
        private const val MAX_INPUT_CHARS = 8 * 1024
        private const val MILLIS_PER_SECOND = 1000L

        /** 2^53: past this a double can no longer hold every integer exactly. */
        private const val MAX_EXACT_INTEGER = 9007199254740992.0

        private val json = Json { ignoreUnknownKeys = true }
    }

    fun table(): LuaTable {
        val wm = LuaTable()
        wm.set("api_version", API_VERSION)
        wm.set("plugin_id", plugin.id)
        wm.set("plugin_version", plugin.version)
        wm.set("log", function { args -> logLine(args) })
        wm.set("json", jsonTable())
        if (!forAction) wm.set("ui", uiTable())
        if (storage != null) wm.set("storage", storageTable(storage))
        if (reminders != null) wm.set("reminders", remindersTable(reminders))
        return wm
    }

    // ---- wm.log ----------------------------------------------------------

    private fun logLine(args: Varargs): Varargs {
        guard()
        val line = args.arg1().tojstring().take(PluginLog.MAX_LINE)
        log.add(line)
        onLine(line)
        return LuaValue.NONE
    }

    // ---- wm.ui -----------------------------------------------------------

    private fun uiTable(): LuaTable {
        val ui = LuaTable()
        ui.set("set_input", function { args ->
            guard()
            val id = args.arg(1).optjstring("")
            val text = args.arg(2).optjstring("").take(MAX_INPUT_CHARS)
            if (id.isNotEmpty()) setInput(id, text)
            LuaValue.NONE
        })
        return ui
    }

    // ---- wm.storage ------------------------------------------------------

    private fun storageTable(store: PluginStorage): LuaTable {
        val table = LuaTable()
        table.set("get", function { args ->
            guard()
            store.get(args.arg(1).optjstring(""))?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
        })
        table.set("set", function { args ->
            guard()
            val key = args.arg(1).optjstring("")
            val value = args.arg(2).optjstring("")
            when (val failure = store.set(key, value)) {
                null -> LuaValue.TRUE
                else -> LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(failure))
            }
        })
        table.set("remove", function { args ->
            guard()
            store.remove(args.arg(1).optjstring(""))
            LuaValue.NONE
        })
        table.set("keys", function {
            guard()
            val keys = LuaTable()
            store.keys().forEachIndexed { index, key -> keys.set(index + 1, LuaValue.valueOf(key)) }
            keys
        })
        return table
    }

    // ---- wm.reminders ----------------------------------------------------

    /**
     * Times are seconds since the epoch, the unit `os.time` gives, so a script
     * never converts. The host keeps milliseconds; whole seconds are what cross.
     */
    private fun remindersTable(list: PluginReminders): LuaTable {
        val table = LuaTable()
        table.set("set", function { args ->
            guard()
            val id = args.arg(1).optjstring("")
            val time = args.arg(2)
            val text = args.arg(3).optjstring("")
            val failure = if (!time.isnumber() || !time.todouble().isFinite()) {
                "the time must be a number of seconds, as os.time() gives"
            } else {
                list.set(id, secondsToMillis(time.todouble()), text)
            }
            if (failure == null) LuaValue.TRUE else LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(failure))
        })
        table.set("cancel", function { args ->
            guard()
            LuaValue.valueOf(list.cancel(args.arg(1).optjstring("")))
        })
        table.set("list", function {
            guard()
            val out = LuaTable()
            list.list().forEachIndexed { index, reminder ->
                val entry = LuaTable()
                entry.set("id", reminder.id)
                entry.set("time", LuaValue.valueOf((reminder.at / MILLIS_PER_SECOND).toDouble()))
                entry.set("text", reminder.text)
                out.set(index + 1, entry)
            }
            out
        })
        return table
    }

    /** Whole seconds to milliseconds, saturating rather than wrapping: a far-off time is refused, not moved. */
    private fun secondsToMillis(seconds: Double): Long {
        val whole = floor(seconds)
        val limit = Long.MAX_VALUE / MILLIS_PER_SECOND
        return when {
            whole >= limit -> Long.MAX_VALUE
            whole <= -limit -> Long.MIN_VALUE
            else -> whole.toLong() * MILLIS_PER_SECOND
        }
    }

    // ---- wm.json ---------------------------------------------------------

    private fun jsonTable(): LuaTable {
        val table = LuaTable()
        table.set("decode", function { args ->
            guard()
            val text = args.arg(1).optjstring("")
            if (text.length > MAX_JSON_CHARS) {
                return@function LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("that JSON is too large"))
            }
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: return@function LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("that isn't valid JSON"))
            toLua(element, 0)
        })
        table.set("encode", function { args ->
            guard()
            val element = runCatching { toJson(args.arg(1), 0) }.getOrNull()
                ?: return@function LuaValue.varargsOf(
                    LuaValue.NIL,
                    LuaValue.valueOf("that value can't be turned into JSON"),
                )
            val text = element.toString()
            if (text.length > MAX_JSON_CHARS) {
                LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("that value is too large to encode"))
            } else {
                LuaValue.valueOf(text)
            }
        })
        return table
    }

    private fun toLua(element: JsonElement, depth: Int): LuaValue {
        if (depth > MAX_JSON_DEPTH) return LuaValue.NIL
        return when (element) {
            is JsonNull -> LuaValue.NIL
            is JsonPrimitive -> when {
                element.isString -> LuaValue.valueOf(element.content)
                element.content == "true" -> LuaValue.TRUE
                element.content == "false" -> LuaValue.FALSE
                else -> element.content.toDoubleOrNull()
                    ?.let { LuaValue.valueOf(it) } ?: LuaValue.valueOf(element.content)
            }

            is JsonArray -> {
                val table = LuaTable()
                element.forEachIndexed { index, item -> table.set(index + 1, toLua(item, depth + 1)) }
                table
            }

            is JsonObject -> {
                val table = LuaTable()
                for ((key, value) in element) table.set(key, toLua(value, depth + 1))
                table
            }
        }
    }

    /**
     * Lua tables have no notion of "array" or "object", so the rule is the usual
     * one: a table whose keys are exactly 1..n encodes as an array, anything
     * else as an object. Depth is capped, which is also what stops a cyclic
     * table from encoding forever.
     */
    private fun toJson(value: LuaValue, depth: Int): JsonElement {
        if (depth > MAX_JSON_DEPTH) error("too deep")
        return when {
            value.isnil() -> JsonNull
            value.isboolean() -> JsonPrimitive(value.toboolean())
            // Whole numbers encode as whole numbers. Lua has one number type, so
            // writing every value as a double would turn an id of 7 into "7.0"
            // and break every round trip through this pair of functions.
            value.isnumber() -> {
                val number = value.todouble()
                if (number.isFinite() && number == floor(number) && abs(number) < MAX_EXACT_INTEGER) {
                    JsonPrimitive(number.toLong())
                } else {
                    JsonPrimitive(number)
                }
            }
            value.isstring() -> JsonPrimitive(value.tojstring())
            value.istable() -> tableToJson(value.checktable(), depth)
            else -> error("unsupported value")
        }
    }

    private fun tableToJson(table: LuaTable, depth: Int): JsonElement {
        val length = table.length()
        val keys = table.keys()
        val isArray = length > 0 && keys.size == length && keys.all { it.isint() }
        return if (isArray) {
            buildJsonArray {
                for (i in 1..length) add(toJson(table.get(i), depth + 1))
            }
        } else {
            buildJsonObject {
                for (key in keys) put(key.tojstring(), toJson(table.get(key), depth + 1))
            }
        }
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * Refuses every call once the session has been abandoned.
     *
     * A thread the watchdog gave up on may still be running — luaj offers no way
     * to kill a thread stuck in Java-side code — so the host side is revoked
     * instead. It cannot write storage, cannot touch the log, and cannot reach
     * the UI, which leaves it burning CPU and nothing else until the process
     * ends.
     */
    private fun guard() {
        if (revoked()) throw PluginAbort(PluginAbortReason.CANCELLED)
    }

    private fun function(body: (Varargs) -> Varargs): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = body(args)
        }
}
