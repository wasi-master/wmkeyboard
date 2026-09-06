package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import android.util.JsonReader
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How much disk a recognition model will take, worked out before it is
 * fetched.
 *
 * ML Kit's own API says nothing about model sizes — `RemoteModelManager`
 * hands back a Task and no numbers — but the digital-ink library ships the
 * figures as assets, and the resource merger drops them at the root of ours:
 *
 *  - `packmapping.pb` maps each of its 725 language tags to the two or three
 *    packs that tag needs (a recogniser spec, a TFLite recogniser, and for
 *    most languages a compact FST language model).
 *  - `manifest.json` lists all 808 packs with a download URL, a compressed
 *    size and an installed size.
 *
 * Between them the panel can say "9.1 MB of 24.6 MB" instead of spinning an
 * indeterminate ring. The numbers are real and vary a lot — Bengali installs
 * 24.6 MB, English (US) 25.8 MB, Chinese 7.9 MB — so this is also what the
 * "needs a download of about N" copy is built from.
 */
object HandwritingModelSizes {

    private const val PACK_MAPPING_ASSET = "packmapping.pb"
    private const val MANIFEST_ASSET = "manifest.json"

    /** The repeated entry at the top level of `packmapping.pb`. */
    private const val ENTRY_FIELD = 1

    /** Field numbers inside one entry: the tag, then the packs it needs. */
    private const val TAG_FIELD = 1
    private val PACK_FIELDS = 5..7

    /** Length-delimited: the only wire type either file's protos use. */
    private const val WIRE_LENGTH_DELIMITED = 2L

    /** Tag to installed size. Small, and a download asks for it repeatedly. */
    private val cache = mutableMapOf<String, Long>()

    /**
     * Bytes [tag]'s model occupies once installed, or 0 when ML Kit ships no
     * figures for it — an unknown size is reported as unknown rather than
     * guessed, and the panel falls back to counting megabytes without a bar.
     *
     * Slightly pessimistic where a language shares a pack with one already on
     * the device: every Latin script reuses the same 4.0 MB recogniser, so a
     * second Latin language downloads less than this says. Overstating the
     * remaining work is the safe direction for a progress bar.
     */
    suspend fun installedBytes(context: Context, tag: String): Long {
        synchronized(cache) { cache[tag] }?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            runCancellable {
                val packs = packsFor(context, tag)
                if (packs.isEmpty()) 0L else installedBytesOf(context, packs)
            }.getOrDefault(0L)
        }
        synchronized(cache) { cache[tag] = bytes }
        return bytes
    }

    /** The pack names [tag] needs, empty when the mapping does not list it. */
    private fun packsFor(context: Context, tag: String): Set<String> {
        val data = context.assets.open(PACK_MAPPING_ASSET).use { it.readBytes() }
        val cursor = ProtoCursor(data)
        while (!cursor.exhausted) {
            val entry = cursor.nextLengthDelimited() ?: return emptySet()
            if (entry.field != ENTRY_FIELD) continue
            packsIn(data, entry, tag)?.let { return it }
        }
        return emptySet()
    }

    /** One entry's packs, or null when it is some other language's entry. */
    private fun packsIn(data: ByteArray, entry: Span, tag: String): Set<String>? {
        val cursor = ProtoCursor(data, entry.from, entry.until)
        val packs = mutableSetOf<String>()
        var matched = false
        while (!cursor.exhausted) {
            val field = cursor.nextLengthDelimited() ?: return null
            val value = String(data, field.from, field.until - field.from, Charsets.UTF_8)
            when (field.field) {
                TAG_FIELD -> if (value == tag) matched = true else return null
                in PACK_FIELDS -> packs += value
            }
        }
        return if (matched) packs else null
    }

    /** Sums the installed size of [packs], streaming past the other 800. */
    private fun installedBytesOf(context: Context, packs: Set<String>): Long {
        var total = 0L
        context.assets.open(MANIFEST_ASSET).use { stream ->
            JsonReader(stream.reader()).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    if (json.nextName() != "packs") {
                        json.skipValue()
                        continue
                    }
                    json.beginArray()
                    while (json.hasNext()) {
                        total += packSize(json, packs)
                    }
                    json.endArray()
                }
                json.endObject()
            }
        }
        return total
    }

    /** One pack object's size, or 0 when it is not one we are waiting for. */
    private fun packSize(json: JsonReader, packs: Set<String>): Long {
        var name: String? = null
        var size = 0L
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "name" -> name = json.nextString()
                "size" -> size = json.nextLong()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (name in packs) size else 0L
    }

    /** A length-delimited field: its number and where its bytes sit. */
    private data class Span(val field: Int, val from: Int, val until: Int)

    /**
     * Just enough protobuf to walk these two files: every field in them is
     * length-delimited, so anything else means the format has moved on and
     * the caller should fall back to not knowing the size.
     */
    private class ProtoCursor(
        private val data: ByteArray,
        private var offset: Int = 0,
        private val end: Int = data.size,
    ) {
        val exhausted: Boolean get() = offset >= end

        fun nextLengthDelimited(): Span? {
            val key = varint() ?: return null
            if (key and WIRE_BITS != WIRE_LENGTH_DELIMITED) return null
            val length = (varint() ?: return null).toInt()
            if (length < 0 || offset + length > end) return null
            val span = Span((key shr FIELD_SHIFT).toInt(), offset, offset + length)
            offset += length
            return span
        }

        private fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (offset < end && shift <= MAX_SHIFT) {
                // Masked, not just widened: a byte over 0x7F is negative as a
                // Kotlin Byte and sign-extends into every high bit.
                val byte = data[offset++].toLong() and BYTE_MASK
                result = result or ((byte and PAYLOAD_MASK) shl shift)
                if (byte and CONTINUATION_MASK == 0L) return result
                shift += VARINT_BITS
            }
            return null
        }

        private companion object {
            const val WIRE_BITS = 7L
            const val FIELD_SHIFT = 3
            const val BYTE_MASK = 0xFFL
            const val PAYLOAD_MASK = 0x7FL
            const val CONTINUATION_MASK = 0x80L
            const val VARINT_BITS = 7
            const val MAX_SHIFT = 63
        }
    }
}
