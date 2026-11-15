package com.wasimaster.wmkeyboard.core.theme

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reading a Kyng theme share code: the `KBTH1.` line Kyng's share sheet copies.
 *
 * The code is `KBTH1.` and URL-safe base64 without padding. Its first byte is
 * the format (2) with bit 0 set when the rest is raw deflate. The rest is a
 * list of fields, each a tag byte, a length as a 7-bit varint, then that many
 * bytes:
 *
 * | Tag | Holds |
 * |---|---|
 * | 1 | the theme's name, UTF-8 |
 * | 2 | a second name line, UTF-8; may be absent |
 * | 3 | the catalog picks: pairs of a part byte and a varint index into Kyng's own catalog (style, palette, effect, lighting, backdrop, font…), 0 for the code's own palette |
 * | 4 | the code's own palette: a name length byte (at most 24), the name, then twelve colours as big-endian ARGB ints |
 * | 5–8 | per-part overrides and options keyed to Kyng's catalog |
 *
 * Only tag 4 carries colours of its own. Everything else names entries of
 * Kyng's catalog by number, and that catalog (its palettes, skins, backdrops)
 * is Kyng's own work, which this app does not copy. So a code whose palette is
 * one of Kyng's built-in ones has no colours to bring across, and says so;
 * a code made from a palette the user built in Kyng comes across as a theme
 * of those twelve colours.
 */
object KyngShareCode {

    const val PREFIX = "KBTH1."
    private val CODE = Regex("""KBTH1\.[A-Za-z0-9_-]+""")

    /** The twelve colours tag 4 carries, in its order. */
    enum class Role { BOARD, KEY, KEY_PRESSED, KEY_SPECIAL, KEY_SPECIAL_PRESSED, LABEL, LABEL_SECONDARY, ACCENT, BORDER, SHADOW, PREVIEW_FILL, PREVIEW_LABEL }

    /** A Kyng catalog part a code may name, by the byte it is written as. */
    enum class Part(val code: Int) { THEME(1), STYLE(2), PALETTE(3), DARK_PALETTE(4), MOTION(5), EFFECT(6), LIGHTING(7), SURFACE(8), BACKDROP(9), FONT(10) }

    sealed interface Result {
        data class Converted(
            val theme: ThemeSpec,
            /** The Kyng catalog parts the code also picked, which have no counterpart here. */
            val catalogParts: Set<Part>,
        ) : Result

        /** A well-formed code whose colours are one of Kyng's own palettes. */
        data class CatalogPaletteOnly(val name: String) : Result

        /** A `KBTH1.` code that does not decode. */
        data object Damaged : Result

        /** No `KBTH1.` code in the text at all. */
        data object NotACode : Result
    }

    /** The first share code in [text] (a chat message, a whole post), or null. */
    fun find(text: String): String? = CODE.find(text)?.value

    /** Reads the first code in [text] into a theme with [id]. Never throws. */
    fun read(text: String, id: String): Result {
        val code = find(text) ?: return Result.NotACode
        val fields = runCatching { fields(code) }.getOrNull() ?: return Result.Damaged
        val name = fields[1]?.toString(Charsets.UTF_8)?.let(::clean)
        val picks = fields[3]?.let { runCatching { picks(it) }.getOrNull() } ?: emptyMap()
        val palette = fields[4] ?: return if (fields.containsKey(1) || fields.containsKey(3)) {
            Result.CatalogPaletteOnly(name.orEmpty())
        } else {
            Result.Damaged
        }
        val (paletteName, colours) = palette(palette) ?: return Result.Damaged
        val themeName = name?.takeIf { it.isNotBlank() } ?: paletteName.ifBlank { "Kyng" }
        val catalogParts = picks.filter { (part, index) -> part != Part.PALETTE || index != 0 }.keys
        return Result.Converted(theme(id, themeName, colours), catalogParts)
    }

    /** The code's fields by tag; throws when the code is not one. */
    internal fun fields(code: String): Map<Int, ByteArray> {
        require(code.startsWith(PREFIX))
        val raw = base64Url(code.substring(PREFIX.length))
        require(raw.isNotEmpty() && raw[0].toInt() and 0xFE == 2)
        val body = raw.copyOfRange(1, raw.size)
        val payload = if (raw[0].toInt() and 1 == 1) inflate(body) else body
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i < payload.size) {
            val tag = payload[i++].toInt() and 0xFF
            var length = 0
            var shift = 0
            while (true) {
                require(i < payload.size && shift < 28)
                val b = payload[i++].toInt() and 0xFF
                length = length or ((b and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            require(length >= 0 && i + length <= payload.size)
            out.putIfAbsent(tag, payload.copyOfRange(i, i + length))
            i += length
        }
        return out
    }

    private fun picks(bytes: ByteArray): Map<Part, Int> {
        val out = LinkedHashMap<Part, Int>()
        var i = 0
        while (i < bytes.size) {
            val part = bytes[i++].toInt() and 0xFF
            var index = 0
            var shift = 0
            while (true) {
                require(i < bytes.size && shift < 28)
                val b = bytes[i++].toInt() and 0xFF
                index = index or ((b and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            Part.entries.firstOrNull { it.code == part }?.let { out[it] = index }
        }
        return out
    }

    /** Tag 4: its name and its twelve colours, or null when it is the wrong size. */
    internal fun palette(bytes: ByteArray): Pair<String, Map<Role, Int>>? {
        if (bytes.isEmpty()) return null
        val nameLength = bytes[0].toInt() and 0xFF
        if (nameLength > MAX_NAME_BYTES || bytes.size != 1 + nameLength + Role.entries.size * 4) return null
        val name = clean(String(bytes, 1, nameLength, Charsets.UTF_8))
        var at = 1 + nameLength
        val colours = Role.entries.associateWith {
            val c = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            at += 4
            c
        }
        return name to colours
    }

    /**
     * Twelve Kyng colours as a theme. Kyng's function keys have a pressed
     * shade of their own and this app derives one, so KEY_SPECIAL_PRESSED is
     * the one colour with nowhere to go. Its border and shadow colours are
     * kept for the editor, at no width and no lift: in Kyng the style decides
     * whether either is drawn, and the style is catalog art.
     */
    internal fun theme(id: String, name: String, c: Map<Role, Int>): ThemeSpec {
        fun argb(role: Role): Long = c.getValue(role).toLong() and 0xFFFFFFFFL
        val accent = argb(Role.ACCENT)
        return ThemeSpec(
            id = id,
            name = name,
            dark = luminance(c.getValue(Role.BOARD)) < 0.5,
            boardBackground = argb(Role.BOARD),
            keyBackground = argb(Role.KEY),
            pressedKeyBackground = argb(Role.KEY_PRESSED),
            modifierKeyBackground = argb(Role.KEY_SPECIAL),
            keyText = argb(Role.LABEL),
            modifierKeyText = argb(Role.LABEL),
            hintText = argb(Role.LABEL_SECONDARY),
            accent = accent,
            enterKeyBackground = accent,
            enterKeyText = if (luminance(c.getValue(Role.ACCENT)) < 0.5) 0xFFFFFFFFL else 0xFF000000L,
            keyBorderColor = argb(Role.BORDER),
            keyShadowColor = argb(Role.SHADOW),
            popupBackground = argb(Role.PREVIEW_FILL),
            popupText = argb(Role.PREVIEW_LABEL),
        )
    }

    private fun luminance(argb: Int): Double {
        fun ch(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun clean(text: String): String = text.filterNot { Character.isISOControl(it) }.trim()

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_PAYLOAD) throw DataFormatException("too big")
            }
            // A cut-off code stops inflating early; half a theme is a damaged code.
            if (!inflater.finished()) throw DataFormatException("truncated")
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** URL-safe base64, padding optional; java.util.Base64 is API 26 and this runs on 24. */
    private fun base64Url(text: String): ByteArray {
        val s = text.trimEnd('=')
        val out = ByteArrayOutputStream(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in s) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> throw IllegalArgumentException("not base64url")
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private const val MAX_NAME_BYTES = 24
    private const val MAX_PAYLOAD = 64 * 1024
}
