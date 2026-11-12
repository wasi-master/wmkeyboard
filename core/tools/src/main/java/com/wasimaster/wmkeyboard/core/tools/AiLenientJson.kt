package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON as a small model writes it, read into real JSON (#1281).
 *
 * A two-billion-parameter model asked for `{"query":"rain"}` writes something
 * close to it and rarely the thing itself: single quotes, a trailing comma,
 * keys with no quotes (Gemma's own call format has none), Python's `True` and
 * `None`, a quote inside a value it never escaped. Strict JSON turns every one
 * of those into a call that silently never ran. This reader takes them all,
 * and hands back ordinary [JsonElement]s, so everything past it (the tool's
 * own argument checks, the activity record) sees one shape.
 *
 * It is only ever the fallback: well-formed input goes through the real parser
 * first and comes back exactly as it would have.
 */
internal object AiLenientJson {

    /** [text] as JSON, or null when even this reader can make nothing of it. */
    fun parse(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // The real parser takes an unquoted top-level literal too, and would
        // hand `'Run'` back with its quotes on; only a start JSON itself could
        // have is trusted to it.
        if (trimmed[0] in "{[\"" || trimmed in LITERALS || NUMBER.matches(trimmed)) {
            runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { return it }
        }
        return runCatching {
            val reader = Reader(normalise(trimmed))
            val value = reader.value(stops = "", depth = 0)
            value.takeIf { reader.restIsBlank() }
        }.getOrNull()
    }

    /**
     * Gemma 4 writes its string quotes as a special token, `<|"|>`, which a
     * runtime that prints special tokens hands over verbatim; and a model
     * that learnt its quotes from prose writes curly ones.
     */
    private fun normalise(text: String): String =
        text.replace("<|\"|>", "\"").replace('“', '"').replace('”', '"')

    private class Reader(private val s: String) {
        private var i = 0

        fun restIsBlank(): Boolean = s.substring(i).trim().trimEnd(',', ';').isBlank()

        fun value(stops: String, depth: Int): JsonElement {
            require(depth <= MAX_DEPTH)
            skipSpace()
            require(i < s.length)
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"', '\'' -> JsonPrimitive(str())
                else -> bare(stops)
            }
        }

        private fun obj(depth: Int): JsonObject {
            i++
            val map = LinkedHashMap<String, JsonElement>()
            while (true) {
                skipSpace()
                require(i < s.length)
                when (s[i]) {
                    '}' -> {
                        i++
                        return JsonObject(map)
                    }
                    ',', ';' -> {
                        i++
                        continue
                    }
                }
                val key = if (s[i] == '"' || s[i] == '\'') str() else bareKey()
                skipSpace()
                require(i < s.length && (s[i] == ':' || s[i] == '='))
                i++
                map[key] = value(stops = ",}", depth = depth + 1)
            }
        }

        private fun arr(depth: Int): JsonArray {
            i++
            val list = ArrayList<JsonElement>()
            while (true) {
                skipSpace()
                require(i < s.length)
                when (s[i]) {
                    ']' -> {
                        i++
                        return JsonArray(list)
                    }
                    ',' -> {
                        i++
                        continue
                    }
                }
                list.add(value(stops = ",]", depth = depth + 1))
            }
        }

        /**
         * A quoted string in either kind of quote. A quote only ends it when
         * what follows could follow a value (a comma, a colon, a closing
         * bracket, the end), so `'Tomorrow's alarm'` and `"the "best" one"`
         * both read as the model meant them.
         */
        private fun str(): String {
            val quote = s[i++]
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    i = escape(out, i + 1)
                    continue
                }
                if (c == quote && closes(i + 1)) {
                    i++
                    return out.toString()
                }
                out.append(c)
                i++
            }
            // Never closed: what there is of it is what the model said.
            return out.toString()
        }

        /** Appends the escape at [at] (just past the backslash); returns where reading resumes. */
        private fun escape(out: StringBuilder, at: Int): Int {
            when (val c = s[at]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = s.substring(at + 1, minOf(s.length, at + 5))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        return at + 5
                    }
                    out.append(c)
                }
                else -> out.append(c)
            }
            return at + 1
        }

        private fun closes(from: Int): Boolean {
            var j = from
            while (j < s.length && s[j].isWhitespace()) j++
            return j >= s.length || s[j] in ",:}])"
        }

        private fun bareKey(): String {
            val start = i
            while (i < s.length && s[i] != ':' && s[i] != '=' && s[i] != ',' && s[i] != '}') i++
            val key = s.substring(start, i).trim()
            require(key.isNotEmpty() && key.none { it == '{' || it == '[' || it == '"' || it == '\'' })
            return key
        }

        /** A value with no quotes: a number, a yes/no, a null, or else a string. */
        private fun bare(stops: String): JsonElement {
            val start = i
            while (i < s.length && s[i] !in stops) i++
            val token = s.substring(start, i).trim()
            require(token.isNotEmpty())
            return when (token) {
                "true", "True", "TRUE" -> JsonPrimitive(true)
                "false", "False", "FALSE" -> JsonPrimitive(false)
                "null", "None", "nil", "NULL" -> JsonNull
                else -> if (NUMBER.matches(token)) {
                    token.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(token.toDouble())
                } else {
                    JsonPrimitive(token)
                }
            }
        }

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    /** JSON's own number grammar: `05` is not a number, so "05:00" and "007" stay strings. */
    private val NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")

    private val LITERALS = setOf("true", "false", "null")

    private const val MAX_DEPTH = 32
}
