package com.wasimaster.wmkeyboard.core.emoji.mix

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An emoji-mix image address with the parts that change from pair to pair
 * left as placeholders:
 *
 *  - `{left}` / `{right}`: the emoji themselves, percent-encoded
 *  - `{left_hex}` / `{right_hex}`: their keys, `1f62e-200d-1f4a8`
 *  - `{left_u}` / `{right_u}`: their keys `u`-prefixed, `u1f62e-u200d-u1f4a8`
 *  - `{date}`: the batch the pair's art came out in, `20201001`
 *  - `{size}`: the pixel size asked of a service that resizes
 *
 * A user can type a pattern like this, or paste one working address and let
 * [infer] work the pattern out. No host is known in advance: [infer] reads the
 * shape of the path, never its domain.
 */
@JvmInline
value class MixTemplate(val pattern: String) {

    /** Whether the pattern needs a pair's date, which only an index can give. */
    val needsDate: Boolean get() = DATE in pattern

    /** The address of the [left]+[right] mix, given as keys; [date] may be empty when not [needsDate]. */
    fun fill(left: String, right: String, date: String = "", size: Int = DEFAULT_SIZE): String =
        pattern
            .replace(LEFT_U, MixSeq.uForm(left))
            .replace(RIGHT_U, MixSeq.uForm(right))
            .replace(LEFT_HEX, left)
            .replace(RIGHT_HEX, right)
            .replace(LEFT, encode(left))
            .replace(RIGHT, encode(right))
            .replace(DATE, date)
            .replace(SIZE, size.toString())

    /** One address read apart: the [template] it follows and the pair it is the mix of. */
    data class Inferred(val template: MixTemplate, val left: String, val right: String, val date: String)

    companion object {
        const val LEFT = "{left}"
        const val RIGHT = "{right}"
        const val LEFT_HEX = "{left_hex}"
        const val RIGHT_HEX = "{right_hex}"
        const val LEFT_U = "{left_u}"
        const val RIGHT_U = "{right_u}"
        const val DATE = "{date}"
        const val SIZE = "{size}"

        /** What a service that resizes is asked for: big enough to send, small enough to come fast. */
        const val DEFAULT_SIZE = 512

        private const val U_SEQ = "u[0-9a-f]{2,6}(?:-u[0-9a-f]{2,6})*"
        private const val HEX_SEQ = "u?[0-9a-f]{2,6}(?:-u?[0-9a-f]{2,6})*"
        private const val EXT = "(png|webp|gif)"

        // Built on first use: each compiled Regex holds native ICU sets for the
        // life of the process, about 17 KB across this file's patterns.
        /** `…/20201001/u1f603/u1f603_u1f601.png`: the dated layout, the pair's first emoji naming the folder. */
        private val DATED by lazy { Regex("^(.*?)/(\\d{8})/($U_SEQ)/\\3_($U_SEQ)\\.$EXT((?:[?#].*)?)$", RegexOption.IGNORE_CASE) }

        /** `…/1f603_1f601…` anywhere in the last path segment, with or without the `u`s. */
        private val FLAT by lazy { Regex("^(.*/[^/?#]*?|[^/?#]*?)($HEX_SEQ)_($HEX_SEQ)([^/]*)$", RegexOption.IGNORE_CASE) }

        /** The emoji themselves, percent-encoded, either side of the underscore. */
        private val ENCODED by lazy { Regex("^(.*?)((?:%[0-9a-f]{2})+)_((?:%[0-9a-f]{2})+)(.*)$", RegexOption.IGNORE_CASE) }

        private val SIZE_PARAM by lazy { Regex("([?&](?:size|s|w|width)=)\\d+", RegexOption.IGNORE_CASE) }

        /** True when [text] has a placeholder in it, so is a pattern rather than an example. */
        fun isPattern(text: String): Boolean =
            listOf(LEFT, RIGHT, LEFT_HEX, RIGHT_HEX, LEFT_U, RIGHT_U).any { it in text }

        /**
         * The pattern [url] follows and the pair it shows, or null when its
         * path names no pair. The dated layout first (its date is part of the
         * pattern), then two keys joined by `_`, then two encoded emoji. A
         * `size=` parameter becomes `{size}`.
         */
        fun infer(url: String): Inferred? {
            DATED.matchEntire(url)?.let { m ->
                val (prefix, date, left, right, ext, tail) = m.destructured
                val l = MixSeq.parse(left) ?: return@let
                val r = MixSeq.parse(right) ?: return@let
                val pattern = "$prefix/$DATE/$LEFT_U/${LEFT_U}_$RIGHT_U.$ext$tail"
                return Inferred(MixTemplate(pattern), l, r, date)
            }
            FLAT.matchEntire(url)?.let { m ->
                val (prefix, left, right, tail) = m.destructured
                val l = MixSeq.parse(left) ?: return@let
                val r = MixSeq.parse(right) ?: return@let
                val lp = if (left.startsWith("u", ignoreCase = true)) LEFT_U else LEFT_HEX
                val rp = if (right.startsWith("u", ignoreCase = true)) RIGHT_U else RIGHT_HEX
                val pattern = prefix + lp + "_" + rp + SIZE_PARAM.replace(tail) { it.groupValues[1] + SIZE }
                return Inferred(MixTemplate(pattern), l, r, "")
            }
            ENCODED.matchEntire(url)?.let { m ->
                val (prefix, left, right, tail) = m.destructured
                val l = decode(left)?.let(MixSeq::parse) ?: return@let
                val r = decode(right)?.let(MixSeq::parse) ?: return@let
                val pattern = prefix + LEFT + "_" + RIGHT + SIZE_PARAM.replace(tail) { it.groupValues[1] + SIZE }
                return Inferred(MixTemplate(pattern), l, r, "")
            }
            return null
        }

        /**
         * The pair a file in an imported pack is named for, from its path
         * inside the pack: `1f603_1f601.png`, `u1f603_u1f601.webp`,
         * `😃_😁.png`, or the dated layout's `20201001/u1f603/u1f603_u1f601.png`.
         */
        fun pairOfFile(path: String): Inferred? {
            val name = path.substringAfterLast('/')
            if (!name.contains('.') || name.substringAfterLast('.').lowercase() !in IMAGE_EXTENSIONS) return null
            infer(if (path.startsWith("/")) path else "/$path")?.let { return it }
            val stem = name.substringBeforeLast('.')
            val split = stem.indexOf('_').takeIf { it > 0 } ?: return null
            val l = MixSeq.parse(stem.substring(0, split)) ?: return null
            val r = MixSeq.parse(stem.substring(split + 1)) ?: return null
            return Inferred(MixTemplate(path), l, r, "")
        }

        private val IMAGE_EXTENSIONS = setOf("png", "webp", "gif")

        private fun encode(key: String): String =
            URLEncoder.encode(MixSeq.emoji(key).orEmpty(), "UTF-8").replace("+", "%20")

        private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
    }
}
