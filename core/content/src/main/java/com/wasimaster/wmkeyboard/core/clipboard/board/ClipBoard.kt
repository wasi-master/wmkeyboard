package com.wasimaster.wmkeyboard.core.clipboard.board

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardSyncSanitizer
import com.wasimaster.wmkeyboard.core.clipboard.capClipText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * One clip on the shared board (#1201): text another device can copy, change
 * or take off. [id] is made where it was sent and never changes; [stampMs]
 * and [stampBy] say who last changed it and when, so two devices that changed
 * the same entry agree on one answer (the later change, the install id
 * breaking a tie). A taken-off entry stays as a [deleted] marker with no text
 * for [ClipBoard.TOMBSTONE_MS], so a device that was offline learns it is gone
 * instead of sending it back.
 */
@Serializable
data class BoardEntry(
    val id: String,
    val text: String = "",
    val tags: List<String> = emptyList(),
    /** The device that sent it, by the name it goes by. */
    val from: String = "",
    val createdAtMs: Long = 0L,
    val stampMs: Long = 0L,
    val stampBy: String = "",
    val deleted: Boolean = false,
)

/** What one device writes to the board's folder: everything it knows of the board. */
@Serializable
data class BoardFile(
    val format: Int = ClipBoard.FORMAT,
    val installId: String = "",
    val device: String = "",
    val writtenAtMs: Long = 0L,
    val entries: List<BoardEntry> = emptyList(),
)

/**
 * This device's copy of the board, kept between passes so it reads offline and
 * a change made offline waits in it. [board] names the folder it is a copy of;
 * a different folder starts a fresh copy. [pending] is a change not yet written there.
 */
@Serializable
data class BoardLocal(
    val board: String = "",
    val entries: List<BoardEntry> = emptyList(),
    val pending: Boolean = false,
    val syncedAtMs: Long = 0L,
    /** Why the last pass failed, a `SinkError` name; empty when it did not. */
    val error: String = "",
)

/**
 * The shared board's rules, apart from any storage so they run on the JVM:
 * the merge, the changes, and what may go on it.
 *
 * Every device keeps one file of its own in the board's folder and reads the
 * others', the way settings sync does, so no two devices ever write the same
 * file and a device that was offline catches up from whichever file it reads.
 * Entries merge one by one, the later change winning.
 *
 * What may go on the board is what the clipboard would send through sync:
 * text, rich text (as its plain text) and links, never a clip marked
 * sensitive, through [ClipboardSyncSanitizer], in both directions.
 */
object ClipBoard {

    const val FORMAT = 1

    /** Live entries kept at most; the oldest past it are taken off. */
    const val MAX_ENTRIES = 200

    /**
     * The most characters one entry carries, well under the clipboard's own
     * cap: every device downloads every entry on every pass, so a board of
     * whole documents would be megabytes each time.
     */
    const val TEXT_MAX = 50_000

    /** How long a taken-off entry's marker is kept before it is forgotten. */
    const val TOMBSTONE_MS = 30L * 24 * 60 * 60 * 1000

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val random = SecureRandom()

    /** A fresh entry id: 16 hex digits, unique enough across a household's phones. */
    fun newId(): String = buildString(ID_HEX) {
        repeat(ID_HEX) { append(HEX[random.nextInt(HEX.length)]) }
    }

    /** The later of two versions of one entry; a tie goes to the larger install id, so every device picks the same. */
    fun later(a: BoardEntry, b: BoardEntry): BoardEntry = when {
        a.stampMs != b.stampMs -> if (a.stampMs > b.stampMs) a else b
        else -> if (a.stampBy >= b.stampBy) a else b
    }

    /** Every list's entries as one, each id once at its latest version. */
    fun merge(lists: List<List<BoardEntry>>): List<BoardEntry> {
        val byId = LinkedHashMap<String, BoardEntry>()
        for (list in lists) {
            for (entry in list) {
                val known = byId[entry.id]
                byId[entry.id] = if (known == null) entry else later(known, entry)
            }
        }
        return byId.values.toList()
    }

    /**
     * [entries] without markers older than [TOMBSTONE_MS], and with at most
     * [MAX_ENTRIES] live ones: the oldest past that become markers themselves,
     * stamped by [me], so other devices take them off too rather than send
     * them back.
     */
    fun prune(entries: List<BoardEntry>, me: String, nowMs: Long): List<BoardEntry> {
        val kept = entries.filterNot { it.deleted && nowMs - it.stampMs > TOMBSTONE_MS }
        val live = kept.filterNot { it.deleted }
        if (live.size <= MAX_ENTRIES) return kept
        val over = live.sortedByDescending { it.createdAtMs }.drop(MAX_ENTRIES).mapTo(HashSet()) { it.id }
        return kept.map { if (it.id in over) tombstone(it, me, nowMs) else it }
    }

    /** What the board shows: live entries, newest first. */
    fun live(entries: List<BoardEntry>): List<BoardEntry> =
        entries.filterNot { it.deleted }.sortedWith(compareByDescending<BoardEntry> { it.createdAtMs }.thenBy { it.id })

    /** [entries] with [text] on top, sent from [device]; null when the text may not go on the board. */
    fun add(
        entries: List<BoardEntry>,
        text: String,
        tags: List<String>,
        device: String,
        me: String,
        nowMs: Long,
    ): Pair<List<BoardEntry>, BoardEntry>? {
        val entry = clean(BoardEntry(newId(), text, tags, device, nowMs, nowMs, me)) ?: return null
        if (entry.text.isBlank()) return null
        return (entries + entry) to entry
    }

    /** [entries] with entry [id] saying [text] now; unchanged when it is gone, the same, or the text may not go. */
    fun edit(entries: List<BoardEntry>, id: String, text: String, me: String, nowMs: Long): List<BoardEntry> =
        entries.map { entry ->
            if (entry.id != id || entry.deleted || entry.text == text) return@map entry
            clean(entry.copy(text = text, stampMs = nowMs, stampBy = me))?.takeIf { it.text.isNotBlank() } ?: entry
        }

    /** [entries] with entry [id] taken off. */
    fun delete(entries: List<BoardEntry>, id: String, me: String, nowMs: Long): List<BoardEntry> =
        entries.map { if (it.id == id && !it.deleted) tombstone(it, me, nowMs) else it }

    private fun tombstone(entry: BoardEntry, me: String, nowMs: Long) =
        entry.copy(text = "", tags = emptyList(), deleted = true, stampMs = nowMs, stampBy = me)

    /**
     * Whether [item] may go on the board, and as what text and tags: what
     * [ClipboardSyncSanitizer] lets a clip travel as, so a picture, a file or
     * a clip marked sensitive never leaves the phone this way either.
     */
    fun portableText(item: ClipItem): Pair<String, List<String>>? {
        if (!item.kind.isTextual || item.sensitive) return null
        val element = runCatching { json.encodeToJsonElement(ClipItem.serializer(), item) }.getOrNull() ?: return null
        val clean = ClipboardSyncSanitizer.portable(element) ?: return null
        return textAndTags(clean)
    }

    /**
     * [entry] as it may be kept: its text through the same sanitizer as a
     * clip from sync (cut to the clipboard's cap, refused when it is not text),
     * or null when it may not be kept at all. A marker passes as it is.
     */
    fun clean(entry: BoardEntry): BoardEntry? {
        if (entry.deleted) return entry.copy(text = "", tags = emptyList())
        if (entry.id.isBlank() || entry.id.length > ID_MAX) return null
        val asClip = JsonObject(
            mapOf(
                "id" to JsonPrimitive(1L),
                "timestamp" to JsonPrimitive(entry.createdAtMs),
                "text" to JsonPrimitive(entry.text),
                "kind" to JsonPrimitive(ClipKind.TEXT.name),
                "tags" to JsonArray(entry.tags.take(TAGS_MAX).map { JsonPrimitive(it.take(TAG_CHARS)) }),
            ),
        )
        val clean = ClipboardSyncSanitizer.portable(asClip) ?: return null
        val (text, tags) = textAndTags(clean) ?: return null
        return entry.copy(text = capClipText(text, TEXT_MAX), tags = tags, from = entry.from.take(DEVICE_CHARS))
    }

    private fun textAndTags(clean: JsonObject): Pair<String, List<String>>? {
        val text = clean["text"]?.jsonPrimitive?.contentOrNull ?: return null
        val tags = (clean["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return text to tags
    }

    fun encode(file: BoardFile): String = json.encodeToString(BoardFile.serializer(), file)

    /** A board file as another device wrote it, each entry cleaned; null when it is not one. */
    fun decode(text: String): BoardFile? = runCatching { json.decodeFromString(BoardFile.serializer(), text) }
        .getOrNull()
        ?.takeIf { it.format >= 1 }
        ?.let { file -> file.copy(entries = file.entries.mapNotNull(::clean)) }

    fun encodeLocal(local: BoardLocal): String = json.encodeToString(BoardLocal.serializer(), local)

    fun decodeLocal(text: String?): BoardLocal? =
        text?.let { runCatching { json.decodeFromString(BoardLocal.serializer(), it) }.getOrNull() }

    private const val HEX = "0123456789abcdef"
    private const val ID_HEX = 16
    private const val ID_MAX = 64
    private const val TAGS_MAX = 20
    private const val TAG_CHARS = 64
    private const val DEVICE_CHARS = 64
}
