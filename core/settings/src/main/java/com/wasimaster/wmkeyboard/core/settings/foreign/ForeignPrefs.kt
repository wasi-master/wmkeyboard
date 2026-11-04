package com.wasimaster.wmkeyboard.core.settings.foreign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The preference files other keyboards put in their backups, read into one
 * plain map of key to value.
 *
 * Every keyboard writes the same handful of Java types (boolean, int, long,
 * float, string, string set) and each writes them differently, so the values
 * here are always one of `Boolean`, `Int`, `Long`, `Float`, `Double`, `String`
 * or `Set<String>`, whichever the file said. What a key *means* is
 * [ForeignSettingsMap]'s business, not this file's.
 *
 * **Written from the published file formats, not from any of the projects'
 * source.** HeliBoard, LeanType and FUTO Keyboard are GPL-3.0; what is
 * transcribed here is the shape of a file, the same footing as the layout and
 * theme converters.
 *
 * Every reader returns null for a file that is not its format, and never
 * throws: a backup is user data from somewhere else.
 */
object ForeignPrefs {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    // ---- HeliBoard and LeanType ----

    /**
     * HeliBoard's `preferences.json`, which is not one JSON document but six:
     * a header line naming a type, then one object holding every setting of
     * that type.
     *
     * ```
     * boolean settings
     * {"show_number_row":true,"popup_on":false}
     * int settings
     * {"vibration_duration_settings":-1}
     * ```
     *
     * The type comes from the header, which is the only reason it can be
     * trusted: JSON has no way to tell an int from a float that happens to be
     * whole.
     */
    fun heliSections(text: String): Map<String, Any>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HELI_BOOLEAN) return null
        val out = LinkedHashMap<String, Any>()
        var type: String? = null
        for (line in lines) {
            if (line in HELI_HEADERS) {
                type = line
                continue
            }
            val section = type ?: continue
            val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
            for ((key, element) in obj) {
                val value = when (section) {
                    HELI_BOOLEAN -> (element as? JsonPrimitive)?.booleanOrNull
                    HELI_INT -> (element as? JsonPrimitive)?.intOrNull
                    HELI_LONG -> (element as? JsonPrimitive)?.longOrNull
                    HELI_FLOAT -> (element as? JsonPrimitive)?.doubleOrNull?.toFloat()
                    HELI_STRING -> (element as? JsonPrimitive)?.contentOrNull
                    HELI_STRING_SET -> (element as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.toSet()
                    else -> null
                } ?: continue
                out[key] = value
            }
        }
        return out
    }

    // ---- FUTO Keyboard ----

    /**
     * FUTO's `sharedPreferences.json`: every key wrapped with its type,
     * `{"vibrate_on":{"t":"b","v":true}}`. `n` means the key was removed and
     * carries nothing.
     */
    fun futoSharedPrefs(text: String): Map<String, Any>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val out = LinkedHashMap<String, Any>()
        for ((key, element) in root) {
            val item = element as? JsonObject ?: continue
            val type = (item["t"] as? JsonPrimitive)?.contentOrNull ?: continue
            val raw = item["v"] ?: continue
            val value: Any = when (type) {
                "b" -> (raw as? JsonPrimitive)?.booleanOrNull
                "i" -> (raw as? JsonPrimitive)?.intOrNull
                "l" -> (raw as? JsonPrimitive)?.longOrNull
                "f" -> (raw as? JsonPrimitive)?.doubleOrNull?.toFloat()
                "s" -> (raw as? JsonPrimitive)?.contentOrNull
                "ss" -> (raw as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                else -> null
            } ?: continue
            out[key] = value
        }
        return out
    }

    /**
     * An AndroidX DataStore preferences file, `datastore.preferences_pb`,
     * which is where FUTO keeps most of its settings.
     *
     * The format is a small protocol buffer, read here by hand rather than
     * through DataStore's own serializer: that one is internal to the library
     * and wants an okio source, and the message is four fields deep.
     *
     * ```
     * PreferenceMap { map<string, Value> preferences = 1; }
     * Value { oneof { bool boolean = 1; float float = 2; int32 integer = 3;
     *                 int64 long = 4; string string = 5; StringSet string_set = 6;
     *                 double double = 7; bytes bytes = 8; } }
     * StringSet { repeated string strings = 1; }
     * ```
     */
    fun datastore(bytes: ByteArray): Map<String, Any>? = runCatching {
        val out = LinkedHashMap<String, Any>()
        val reader = Proto(bytes, 0, bytes.size)
        while (reader.more()) {
            val (field, wire) = reader.tag()
            if (field != 1 || wire != WIRE_LENGTH) {
                reader.skip(wire)
                continue
            }
            val entry = reader.message()
            var key: String? = null
            var value: Any? = null
            while (entry.more()) {
                val (f, w) = entry.tag()
                when {
                    f == 1 && w == WIRE_LENGTH -> key = entry.string()
                    f == 2 && w == WIRE_LENGTH -> value = value(entry.message())
                    else -> entry.skip(w)
                }
            }
            if (key != null && value != null) out[key] = value
        }
        out
    }.getOrNull()

    private fun value(reader: Proto): Any? {
        var out: Any? = null
        while (reader.more()) {
            val (field, wire) = reader.tag()
            out = when {
                field == 1 && wire == WIRE_VARINT -> reader.varint() != 0L
                field == 2 && wire == WIRE_FIXED32 -> Float.fromBits(reader.fixed32())
                field == 3 && wire == WIRE_VARINT -> reader.varint().toInt()
                field == 4 && wire == WIRE_VARINT -> reader.varint()
                field == 5 && wire == WIRE_LENGTH -> reader.string()
                field == 6 && wire == WIRE_LENGTH -> {
                    val set = reader.message()
                    val strings = LinkedHashSet<String>()
                    while (set.more()) {
                        val (f, w) = set.tag()
                        if (f == 1 && w == WIRE_LENGTH) strings += set.string() else set.skip(w)
                    }
                    strings
                }
                field == 7 && wire == WIRE_FIXED64 -> Double.fromBits(reader.fixed64())
                else -> {
                    reader.skip(wire)
                    out
                }
            }
        }
        return out
    }

    /** The few protobuf primitives the preferences message needs. */
    private class Proto(private val bytes: ByteArray, private var at: Int, private val end: Int) {
        fun more() = at < end

        fun tag(): Pair<Int, Int> {
            val tag = varint()
            return (tag ushr 3).toInt() to (tag and 7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                check(at < end)
                val b = bytes[at++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            error("varint too long")
        }

        fun fixed32(): Int {
            check(at + 4 <= end)
            var v = 0
            for (i in 0 until 4) v = v or ((bytes[at + i].toInt() and 0xFF) shl (8 * i))
            at += 4
            return v
        }

        fun fixed64(): Long {
            check(at + 8 <= end)
            var v = 0L
            for (i in 0 until 8) v = v or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
            at += 8
            return v
        }

        private fun length(): Int {
            val n = varint()
            check(n >= 0 && at + n <= end)
            return n.toInt()
        }

        fun message(): Proto {
            val n = length()
            val sub = Proto(bytes, at, at + n)
            at += n
            return sub
        }

        fun string(): String {
            val n = length()
            val s = String(bytes, at, n, Charsets.UTF_8)
            at += n
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> at += 8
                WIRE_LENGTH -> at += length()
                WIRE_FIXED32 -> at += 4
                else -> error("wire type $wire")
            }
            check(at <= end)
        }
    }

    // ---- FlorisBoard ----

    /**
     * FlorisBoard's `florisboard-app-prefs.jetpref`: one setting a line,
     * `type;key;value`, with string values in double quotes and `\\`, `\n`,
     * `\r` and `\"` escaped.
     *
     * ```
     * b;keyboard__number_row;true
     * s;theme__day_theme_id;"org.florisboard.themes:floris_day"
     * ```
     */
    fun jetpref(text: String): Map<String, Any>? {
        val out = LinkedHashMap<String, Any>()
        var lines = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            lines++
            val first = line.indexOf(';')
            val second = if (first < 0) -1 else line.indexOf(';', first + 1)
            if (first != 1 || second < 0) continue
            val key = line.substring(first + 1, second)
            val raw = line.substring(second + 1)
            val value: Any = when (line[0]) {
                'b' -> raw.toBooleanStrictOrNull()
                'i' -> raw.toIntOrNull()
                'l' -> raw.toLongOrNull()
                'f' -> raw.toFloatOrNull()
                'd' -> raw.toDoubleOrNull()
                's' -> unquote(raw)
                else -> null
            } ?: continue
            out[key] = value
        }
        return if (lines > 0 && out.isNotEmpty()) out else null
    }

    private fun unquote(raw: String): String? {
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
        val body = raw.substring(1, raw.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                out.append(
                    when (val next = body[i + 1]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> next
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // ---- CleverKeys ----

    /**
     * A CleverKeys settings export: `{"metadata":{…},"preferences":{key:value}}`.
     * JSON is all the type there is, so a whole number comes back as an `Int`
     * and any other number as a `Float`.
     */
    fun cleverKeys(text: String): Map<String, Any>? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (root["metadata"] !is JsonObject) return null
        val prefs = root["preferences"] as? JsonObject ?: return null
        val out = LinkedHashMap<String, Any>()
        for ((key, element) in prefs) {
            val value: Any = when (element) {
                is JsonPrimitive -> when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull!!
                    element.intOrNull != null -> element.intOrNull!!
                    element.doubleOrNull != null -> element.doubleOrNull!!.toFloat()
                    else -> null
                }
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                else -> null
            } ?: continue
            out[key] = value
        }
        return out
    }

    /** Whether [text] is a CleverKeys settings export, without reading it all. */
    fun looksLikeCleverKeys(text: String): Boolean =
        text.contains("\"metadata\"") && text.contains("\"preferences\"") &&
            (text.contains("\"app_version\"") || text.contains("cleverkeys", ignoreCase = true))

    // ---- AnySoftKeyboard ----

    /**
     * AnySoftKeyboard's `AnySoftKeyboardPrefs.xml`. The settings live under
     * the provider named `SharedPreferences`, one `<pref>` each:
     *
     * ```xml
     * <pref><value type="bool"/><value auto_caps="true"/></pref>
     * ```
     *
     * Read with a scan rather than an XML parser: Android's parser cannot be
     * told to refuse a DOCTYPE, and a file this shallow does not need one. A
     * file with a DOCTYPE is refused outright, the rule the other XML readers
     * here follow.
     */
    fun anySoftKeyboard(text: String): Map<String, Any>? {
        if (!looksLikeAnySoftKeyboard(text)) return null
        if (text.contains("<!DOCTYPE", ignoreCase = true)) return null
        val out = LinkedHashMap<String, Any>()
        // Each provider is a top-level <pref> whose first value names it; only
        // the SharedPreferences one holds settings.
        val provider = Regex("""providerId="SharedPreferences"""").find(text) ?: return out
        val tail = text.substring(provider.range.last)
        for (match in ASK_PREF.findAll(tail)) {
            val type = match.groupValues[1]
            val key = unescapeXml(match.groupValues[2])
            val raw = unescapeXml(match.groupValues[3])
            val value: Any = when (type) {
                "bool", "boolean" -> raw.toBooleanStrictOrNull()
                "int", "integer" -> raw.toIntOrNull()
                "long" -> raw.toLongOrNull()
                "float" -> raw.toFloatOrNull()
                "string" -> raw
                else -> null
            } ?: continue
            out[key] = value
        }
        return out
    }

    fun looksLikeAnySoftKeyboard(text: String): Boolean =
        text.trimStart().let { it.startsWith("<?xml") || it.startsWith("<AnySoftKeyboardPrefs") } &&
            text.contains("<AnySoftKeyboardPrefs")

    // ---- Kinetica ----

    /**
     * The settings in a Kinetica backup: its `pref<TAB>type<TAB>key<TAB>value`
     * records, typed by their second field (`bool`, `int`, `string`, `set`).
     * A value is the rest of the line, tabs and all, as Kinetica joins it
     * back; a set is its members joined by commas. Every other record is
     * skipped. Null when [text] is not a Kinetica backup.
     */
    fun kinetica(text: String): Map<String, Any>? {
        val lines = text.lineSequence().map { it.removePrefix(BOM).trimEnd('\r') }.filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size < 2 || header[0] != KINETICA_FORMAT) return null
        val out = LinkedHashMap<String, Any>()
        for (line in lines.drop(1)) {
            val fields = line.split('\t')
            if (fields[0] != "pref" || fields.size < KINETICA_PREF_MIN_FIELDS || fields[2].isEmpty()) continue
            val raw = fields.drop(KINETICA_PREF_MIN_FIELDS).joinToString("\t")
            val value: Any = when (fields[1].lowercase()) {
                "bool" -> raw.toBooleanStrictOrNull()
                "int" -> raw.toIntOrNull()
                "string" -> raw
                "set" -> raw.split(',').filter { it.isNotEmpty() }.toSet()
                else -> null
            } ?: continue
            out[fields[2]] = value
        }
        return out
    }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val ASK_PREF = Regex(
        """<pref>\s*<value\s+type="(\w+)"\s*/>\s*<value\s+([A-Za-z0-9_.\-]+)="([^"]*)"\s*/>\s*</pref>""",
    )

    private const val HELI_BOOLEAN = "boolean settings"
    private const val HELI_INT = "int settings"
    private const val HELI_LONG = "long settings"
    private const val HELI_FLOAT = "float settings"
    private const val HELI_STRING = "string settings"
    private const val HELI_STRING_SET = "string set settings"
    private val HELI_HEADERS = setOf(HELI_BOOLEAN, HELI_INT, HELI_LONG, HELI_FLOAT, HELI_STRING, HELI_STRING_SET)

    private const val BOM = "\uFEFF"
    private const val KINETICA_FORMAT = "kinetica-backup"
    private const val KINETICA_PREF_MIN_FIELDS = 3

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5
}
