package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The built-in art library (`art/art.json`), read without parsing all of it.
 *
 * The file is one JSON object of drawings by id, some 2.4 MB. Parsed whole
 * into a JSON tree it took 28 MB of heap (measured on a CPH2481, 2026-11-26),
 * held for the life of the process from the first scene that drew any one
 * picture. Here the bytes are kept and scanned once for where each drawing
 * starts and ends, and a drawing is parsed when a scene asks for it. The last
 * [PARSED] are kept, since a grid of templates draws the same few again.
 *
 * Not thread-safe: the store calls it under its own lock.
 */
internal class ArtLibrary(private val bytes: ByteArray) {

    /** Each drawing's byte range in [bytes]: start in the high half, end in the low. */
    private val spans: Map<String, Long> = scan(bytes)

    private val parsed = object : LinkedHashMap<String, SceneToLottie.Art>(PARSED, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SceneToLottie.Art>?) = size > PARSED
    }

    val ids: Set<String> get() = spans.keys

    /** The drawing [id], parsed; null when the library has none or it does not read. */
    operator fun get(id: String): SceneToLottie.Art? {
        parsed[id]?.let { return it }
        val span = spans[id] ?: return null
        val start = (span ushr SPAN_SHIFT).toInt()
        val end = span.toInt()
        val item = runCatching {
            LottieDoc.json.parseToJsonElement(String(bytes, start, end - start, Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return null
        val art = artOf(item) ?: return null
        parsed[id] = art
        return art
    }

    companion object {
        private const val PARSED = 32
        private const val LOAD_FACTOR = 0.75f
        private const val SPAN_SHIFT = 32
        private const val LOW_BITS = 0xFFFF_FFFFL

        private const val QUOTE: Byte = 0x22 // "
        private const val BACKSLASH: Byte = 0x5C // \
        private const val COLON: Byte = 0x3A // :
        private const val COMMA: Byte = 0x2C // ,
        private const val OPEN_OBJECT: Byte = 0x7B // {
        private const val CLOSE_OBJECT: Byte = 0x7D // }
        private const val OPEN_ARRAY: Byte = 0x5B // [
        private const val CLOSE_ARRAY: Byte = 0x5D // ]

        /** One drawing's entry as the compiler takes it: its box and its shapes, `ty` first. */
        fun artOf(item: JsonObject): SceneToLottie.Art? {
            val w = (item["w"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return null
            val h = (item["h"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: return null
            val shapes = item["shapes"] as? JsonArray ?: return null
            // "ty" first, or Lottie Android drops everything before it and draws nothing.
            return SceneToLottie.Art(w, h, LottieDoc.typeFirst(shapes) as JsonArray)
        }

        /**
         * Where each value of [bytes]' top-level object lies, by key. Stops at
         * the first thing that is not well formed and returns what it found
         * before it; empty when [bytes] is not an object at all. Bytes of a
         * multi-byte UTF-8 character are all above 0x7F, so they never look
         * like the ASCII punctuation this looks for.
         */
        internal fun scan(bytes: ByteArray): Map<String, Long> {
            val out = HashMap<String, Long>()
            var i = skipSpace(bytes, 0)
            if (i >= bytes.size || bytes[i] != OPEN_OBJECT) return out
            i++
            while (true) {
                i = skipSpace(bytes, i)
                if (i >= bytes.size || bytes[i] != QUOTE) break
                val keyEnd = stringEnd(bytes, i)
                if (keyEnd < 0) break
                val key = keyOf(bytes, i, keyEnd) ?: break
                i = skipSpace(bytes, keyEnd)
                if (i >= bytes.size || bytes[i] != COLON) break
                val start = skipSpace(bytes, i + 1)
                val end = valueEnd(bytes, start)
                if (end <= start) break
                out[key] = (start.toLong() shl SPAN_SHIFT) or (end.toLong() and LOW_BITS)
                i = skipSpace(bytes, end)
                if (i < bytes.size && bytes[i] == COMMA) i++ else break
            }
            return out
        }

        private fun keyOf(bytes: ByteArray, start: Int, end: Int): String? {
            val inner = String(bytes, start + 1, end - start - 2, Charsets.UTF_8)
            if ('\\' !in inner) return inner
            return runCatching { (LottieDoc.json.parseToJsonElement(String(bytes, start, end - start, Charsets.UTF_8)) as? JsonPrimitive)?.content }
                .getOrNull()
        }

        private fun skipSpace(bytes: ByteArray, from: Int): Int {
            var i = from
            while (i < bytes.size && bytes[i].toInt().let { it == 0x20 || it == 0x0A || it == 0x0D || it == 0x09 }) i++
            return i
        }

        /** The index just past the string that opens at [start], or -1 when it never closes. */
        private fun stringEnd(bytes: ByteArray, start: Int): Int {
            var i = start + 1
            while (i < bytes.size) {
                when (bytes[i]) {
                    BACKSLASH -> i += 2
                    QUOTE -> return i + 1
                    else -> i++
                }
            }
            return -1
        }

        /** The index just past the value that starts at [start], or -1 when it is cut off. */
        private fun valueEnd(bytes: ByteArray, start: Int): Int {
            var depth = 0
            var i = start
            while (i < bytes.size) {
                when (bytes[i]) {
                    QUOTE -> {
                        i = stringEnd(bytes, i)
                        if (i < 0) return -1
                        if (depth == 0) return i
                        continue
                    }
                    OPEN_OBJECT, OPEN_ARRAY -> depth++
                    CLOSE_OBJECT, CLOSE_ARRAY -> {
                        // A bare number or word ends at its object's close.
                        if (depth == 0) return i
                        depth--
                        if (depth == 0) return i + 1
                    }
                    COMMA -> if (depth == 0) return i
                }
                i++
            }
            return if (depth == 0) i else -1
        }
    }
}
