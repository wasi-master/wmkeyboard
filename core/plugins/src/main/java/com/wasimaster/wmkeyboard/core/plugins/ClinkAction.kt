package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * A Clink action (`.clinkext`), read as a plugin.
 *
 * Clink is an iOS keyboard. Its actions are small JSON files whose `source` is
 * a Python function, `def transform(text)`, run by PyMini, a small Python
 * interpreter; this app carries a port of it (`core.plugins.python`). One looks
 * like this:
 *
 * ```json
 * {"id":"repo-reverse","name":"Reverse","icon":"arrow.left.arrow.right",
 *  "summary":"Flip the text backwards","source":"def transform(text):\n    return text[::-1]",
 *  "input":"word","replacesInput":true,"enabled":true}
 * ```
 *
 * Each becomes an actions-only plugin with one action, written in Python,
 * asking only for [PluginPermission.SelectedText]. Converted on the way in,
 * by [PluginFile], so the catalogue, the file picker, the pre-install
 * disclosure and the store all meet an ordinary plugin and need nothing of
 * their own, the way a Clink sound pack is re-zipped into this app's format.
 *
 * Clink's `input` says what the action reads on iOS: the current word, the
 * text before the cursor, or nothing. Here every action runs from the
 * selection bar, so all of them read the selection, except `none`, which is
 * handed an empty string as Clink would. `replacesInput: false` keeps the
 * selection and puts the result after it.
 */
object ClinkAction {

    /** The plugin id every converted action starts with, so its origin shows. */
    const val ID_PREFIX = "clink."

    /** The id of the one action a converted plugin declares. */
    const val ACTION_ID = "transform"

    private const val CLINK_INPUT_NONE = "none"
    private const val SHORT_HASH = 12
    private const val MAX_SLUG = 64 - ID_PREFIX.length

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether [text] is a Clink action: a JSON object with a name and a `transform` in its source. */
    fun looksLikeClinkAction(text: String): Boolean = parse(text) != null

    /**
     * [text] as a plugin manifest and its Python source, or null when it is
     * not a Clink action. [bytes] are the file as downloaded, whose hash
     * becomes the version, so an update is offered whenever the file changes.
     */
    fun toPlugin(text: String, bytes: ByteArray = text.toByteArray()): Pair<PluginManifest, String>? {
        val root = parse(text) ?: return null
        val name = root.string("name").orEmpty().trim()
        val source = root.string("source").orEmpty()
        val summary = root.string("summary").orEmpty()
        val slug = slug(root.string("id").orEmpty()).ifEmpty { slug(name) }.ifEmpty { "action" }
        val manifest = PluginManifest(
            format = PluginManifestCodec.FORMAT,
            id = ID_PREFIX + slug.padEnd(2, '0'),
            name = name,
            pluginVersion = "#" + sha256(bytes).take(SHORT_HASH),
            author = "Clink",
            description = summary,
            apiVersion = PluginManifestCodec.API_VERSION,
            entry = PluginManifestCodec.DEFAULT_PYTHON_ENTRY,
            permissions = listOf(PluginPermission.SelectedText.wire),
            language = PluginManifestCodec.LANGUAGE_PYTHON,
            panel = false,
            actions = listOf(
                PluginActionSpec(
                    id = ACTION_ID,
                    name = name,
                    summary = summary,
                    icon = root.string("icon").orEmpty(),
                    replaces = (root["replacesInput"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    input = if (root.string("input")?.trim() == CLINK_INPUT_NONE) {
                        PluginActionSpec.INPUT_NONE
                    } else {
                        PluginActionSpec.INPUT_SELECTION
                    },
                ),
            ),
        )
        return manifest to source
    }

    /**
     * [input] as a plugin archive: passed through when it is anything but a
     * Clink action, converted to a `.wmplugin` when it is one. Only the first
     * bytes are looked at before deciding, so an archive streams on as it
     * always did; a JSON file is read whole, up to [cap] bytes.
     */
    internal fun normalise(input: InputStream, cap: Int): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(SNIFF_BYTES)
        val head = ByteArray(SNIFF_BYTES)
        val got = buffered.read(head).coerceAtLeast(0)
        buffered.reset()
        val first = head.take(got).firstOrNull { it.toInt().toChar() !in " \t\r\n" }
        // A ZIP starts with "PK"; only text that opens a JSON object can be a Clink action.
        if (first != '{'.code.toByte() && first != BOM_FIRST) return buffered
        val bytes = readCapped(buffered, cap)
        val text = bytes.decodeToString().removePrefix("\uFEFF")
        val (manifest, source) = toPlugin(text, bytes) ?: return ByteArrayInputStream(bytes)
        val out = ByteArrayOutputStream()
        PluginFile.write(out, manifest, source)
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun parse(text: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(text.trimStart().removePrefix("\uFEFF")) }.getOrNull()
            as? JsonObject ?: return null
        val name = root.string("name")
        val source = root.string("source")
        if (name.isNullOrBlank() || source == null) return null
        if (!TRANSFORM.containsMatchIn(source)) return null
        // A plugin manifest has a name too, but never a source.
        if (root.string("format") == PluginManifestCodec.FORMAT) return null
        return root
    }

    /** Lowercase letters, digits, dots, dashes and underscores; anything else becomes a dash. */
    private fun slug(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(MAX_SLUG)
            .trim('-', '.', '_')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < cap) {
            val n = input.read(buffer, 0, minOf(buffer.size, cap - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val TRANSFORM = Regex("""(^|\n)\s*def\s+transform\s*\(""")

    /** Enough to get past a byte-order mark and some leading whitespace. */
    private const val SNIFF_BYTES = 64

    /** The first byte of a UTF-8 byte-order mark. */
    private const val BOM_FIRST = 0xEF.toByte()
}
