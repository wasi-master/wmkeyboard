package com.wasimaster.wmkeyboard.core.clipboard

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a clip holds. [TEXT]/[HTML]/[LINK] are textual (insertable as text);
 * [IMAGE] is a file this app owns; [FILE]/[FOLDER]/[VIDEO] are content URIs
 * owned by the app that did the copying.
 *
 * [VIDEO] is a [FILE] with a card of its own rather than a kind that behaves
 * differently: the bytes stay with the copying app either way — a copied movie
 * is routinely gigabytes, which is exactly what this store must not duplicate —
 * and inserting one goes through the same commitContent path.
 */
@Serializable
enum class ClipKind {
    TEXT, HTML, LINK, IMAGE, FILE, FOLDER, VIDEO;

    /** True when the clip can be committed as plain text. */
    val isTextual: Boolean get() = this == TEXT || this == HTML || this == LINK

    /** True when the clip is a reference to bytes another app owns. */
    val isUriBacked: Boolean get() = this == FILE || this == FOLDER || this == VIDEO
}

/** Open Graph / HTML metadata for a [ClipKind.LINK] clip, fetched on demand. */
@Serializable
data class LinkPreview(
    val title: String = "",
    val description: String = "",
    val siteName: String = "",
    val imageUrl: String = "",
    /** A finished attempt that found nothing; stops us re-fetching every open. */
    val failed: Boolean = false,
) {
    val isEmpty: Boolean get() = title.isBlank() && description.isBlank() && siteName.isBlank()
}

@Serializable
data class ClipItem(
    val id: Long,
    val text: String,
    val pinned: Boolean = false,
    val timestamp: Long,
    val kind: ClipKind = ClipKind.TEXT,
    /** Original HTML markup for [ClipKind.HTML] items; [text] holds the plain-text form. */
    val htmlText: String? = null,
    /** Absolute path of the copied image for [ClipKind.IMAGE] items. */
    val imagePath: String? = null,
    val mimeType: String = "text/plain",
    /** Source content URI for [ClipKind.FILE] and [ClipKind.FOLDER] items. */
    val uriString: String? = null,
    /** Display name of a file/folder clip. */
    val fileName: String? = null,
    /** Byte size of a file clip; -1 when the provider didn't report one. */
    val fileSize: Long = -1,
    /** Fetched metadata for a [ClipKind.LINK] clip; null until fetched. */
    val linkPreview: LinkPreview? = null,
    /** Play length of a [ClipKind.VIDEO] clip in ms; -1 when unknown. */
    val durationMs: Long = -1,
    /**
     * The clip holds something that should not linger: the copying app set
     * Android's sensitive-content flag on it, or it reads as a password or a
     * one-time code (see `ClipSensitivity`).
     *
     * Sensitive clips are drawn masked in the panel and expire on their own
     * short timer rather than the history one — see
     * [ClipboardStore.sensitiveExpiryMillis]. Pinning still overrides both:
     * pinning is an explicit "keep this".
     */
    val sensitive: Boolean = false,
    /**
     * Human-readable label of the app the clip was copied from (falling back to
     * its package name when the label can't be resolved). Null when source
     * tracking is off, unavailable, or the source couldn't be determined.
     */
    val sourceApp: String? = null,
    /**
     * The package [sourceApp] names (#970), so the card can wear that app's
     * icon. Null for a clip from before it was kept, and wherever [sourceApp]
     * is.
     */
    val sourcePackage: String? = null,
    /**
     * When the user last changed this clip's text in the panel's editor, in
     * epoch millis (#576); null for a clip that was never edited. Separate
     * from [timestamp], which an edit leaves alone so the clip keeps its place.
     */
    val editedAt: Long? = null,
    /**
     * Where the user dragged this clip to (#612), as a sort key that takes the
     * place of [timestamp] in [ClipboardStore.items]; null for a clip that was
     * never moved, which sorts by when it was copied. See [orderKey].
     */
    val position: Double? = null,
    /**
     * The user's own labels for this clip (#612), for grouping clips and
     * finding them again: searched with its text and offered as filters.
     * Kept as typed, without a leading `#`, each one at most once.
     */
    val tags: List<String> = emptyList(),
)

/**
 * [raw], a list of tags as typed (#612), made into the list a clip keeps:
 * split at commas and line breaks, a leading `#` and spaces taken off, empty
 * ones dropped, each kept once whatever its case, and each cut to
 * [CLIP_TAG_MAX_LENGTH].
 */
fun parseClipTags(raw: String): List<String> {
    val seen = HashSet<String>()
    return raw.split(',', '\n')
        .map { it.trim().trimStart('#').trim().take(CLIP_TAG_MAX_LENGTH) }
        .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
}

/** The longest one tag may be: a label, not a note. */
const val CLIP_TAG_MAX_LENGTH = 40

/**
 * What [ClipboardStore.items] sorts a clip by inside its pinned or unpinned
 * group, highest first: where it was dragged to, else when it was copied.
 */
val ClipItem.orderKey: Double get() = position ?: timestamp.toDouble()

/**
 * Everything about a clip that its search query is matched against: the text
 * itself, a file's name, a link's fetched metadata, the source app, and the
 * kind/format words ("image", "png", "folder") so a clip with no text of its
 * own — an image, a screenshot — is still findable by what it is.
 */
fun ClipItem.searchHaystack(): String = searchParts().joinToString(" ")

/**
 * [searchHaystack] before it is joined, one entry per thing a clip is known
 * by. A regex search tries each on its own, so `^` and `$` anchor to the
 * clip's text rather than to wherever it happens to sit in the joined string.
 */
private fun ClipItem.searchParts(): List<String> = buildList {
    add(text)
    fileName?.let(::add)
    linkPreview?.let { preview ->
        if (!preview.failed) {
            add(preview.title)
            add(preview.description)
            add(preview.siteName)
        }
    }
    sourceApp?.let(::add)
    // A tag is found by name, and with the `#` it is shown with (#612).
    for (tag in tags) {
        add(tag)
        add("#$tag")
    }
    add(
        when (kind) {
            ClipKind.TEXT -> "text"
            ClipKind.HTML -> "text rich text html"
            ClipKind.LINK -> "link url"
            ClipKind.IMAGE -> "image picture screenshot"
            ClipKind.FILE -> "file"
            ClipKind.FOLDER -> "folder directory"
            ClipKind.VIDEO -> "video movie clip recording"
        },
    )
    mimeType.substringAfterLast('/').takeIf { it.isNotBlank() }?.let(::add)
}

/**
 * Whether this clip should be shown for [query]. A blank query matches
 * everything; otherwise the query is a case-insensitive substring of
 * [searchHaystack]. The clipboard panel's filter and [ClipboardStore.search]
 * both go through [clipQueryMatcher] so the two can never drift apart.
 */
fun ClipItem.matchesQuery(query: String): Boolean = clipQueryMatcher(query, regex = false)(this)

/**
 * The filter behind [matchesQuery], built once per query so a regex is
 * compiled once rather than once per clip.
 *
 * With [regex] on (#414) the query is a case-insensitive regular expression,
 * found anywhere in any one of a clip's [searchParts]. A pattern that does not
 * compile, which is what one is halfway through being typed (`(htt`, `[0-9`),
 * searches as plain text instead, so the list never blanks out mid-word.
 */
fun clipQueryMatcher(query: String, regex: Boolean): (ClipItem) -> Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return { true }
    val pattern = if (regex) runCatching { Regex(trimmed, RegexOption.IGNORE_CASE) }.getOrNull() else null
    return if (pattern != null) {
        { item -> item.searchParts().any { pattern.containsMatchIn(it) } }
    } else {
        { item -> item.searchHaystack().contains(trimmed, ignoreCase = true) }
    }
}

/**
 * Whether the clipboard panel offers to edit this clip: text of any kind,
 * unless it is a secret, whose text the panel never puts on screen.
 */
val ClipItem.clipEditable: Boolean get() = kind.isTextual && !sensitive

/** Recognises clips that are a bare URL, so they can be shown as links. */
object ClipLinks {

    /**
     * The endings a web address written without `https://` or `www.` is
     * recognised by (#612): `x.com`, `youtu.be`, `wmkeyboard.pages.dev`. A
     * list rather than any two letters, so `notes.txt`, `main.py`, `e.g.` and
     * `Node.js` stay words and file names.
     */
    internal const val BARE_TLDS =
        "com|net|org|io|dev|app|co|me|tv|be|ly|gg|xyz|info|edu|gov|mil|int|biz|ai|cc|ws|" +
            "uk|us|ca|de|fr|es|it|nl|se|no|fi|dk|pl|ru|ua|jp|cn|kr|tw|hk|sg|au|nz|br|mx|ar|cl|za|ng|ke|" +
            "eg|tr|ir|pk|bd|lk|np|id|my|ph|th|vn|ch|at|ie|pt|gr|cz|hu|ro|il|sa|ae|eu|asia|in|" +
            "news|blog|site|online|store|shop|tech|page|link|club|live|wiki|social|art|design|cloud|" +
            "email|space|zone|top|fun|one|pro|fm"

    /** A host made of labels ending in one of [BARE_TLDS], with an optional port. */
    internal const val BARE_HOST = """(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:$BARE_TLDS)(?::\d{1,5})?"""

    private val URL_REGEX = Regex(
        """^(?:(?:https?://|www\.)[^\s<>"']+|$BARE_HOST(?:[/?#][^\s<>"']*)?)$""",
        RegexOption.IGNORE_CASE,
    )

    /** The clip text as a URL, or null when it isn't a single bare link. */
    fun asUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length > 2048 || !URL_REGEX.matches(trimmed)) return null
        val schemed = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        return if (schemed) trimmed else "https://$trimmed"
    }

    /** Host without a leading `www.`, for the site label on a link card. */
    fun host(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            .removePrefix("www.")
}

/**
 * Clipboard history with pinning, persisted as JSON on device.
 *
 * The IME service feeds new clips in via [add]/[addHtml]/[addImage]/[addUri]
 * from its OnPrimaryClipChangedListener. History is bounded two ways at once,
 * and both are the user's to set: unpinned items expire after [expiryMillis]
 * (0 disables expiry), and only the newest [maxItems] unpinned ones are kept
 * (0 keeps them all).
 * Sensitive clips get their own, much shorter [sensitiveExpiryMillis]. Pinned
 * items are exempt from all three — pinning is an explicit "keep this".
 *
 * Image clips reference files the service copies into [imagesDir]; the store
 * owns their lifecycle and deletes the file whenever its item is removed
 * (expiry, cap, delete, clear). File, folder and video clips only record the
 * source URI — the copying app still owns those bytes, so nothing is deleted
 * for them.
 */
class ClipboardStore(
    private val storageFile: File?,
    var expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val imagesDir: File? = null,
    /** When true, [items] lists pinned entries last instead of first. */
    var pinnedLast: Boolean = false,
    /**
     * When true, [items] leaves pinned entries among the others in their own
     * place (#612) and [pinnedLast] does not apply.
     */
    var pinnedInPlace: Boolean = false,
    /** Newest unpinned entries kept; older ones fall off the end. 0 is no cap. */
    var maxItems: Int = DEFAULT_MAX_ITEMS,
    /**
     * How long a clip marked [ClipItem.sensitive] survives (0 disables the
     * shorter leash and leaves it to [expiryMillis]). A password or a one-time
     * code is useful for a minute and a liability for a day.
     */
    var sensitiveExpiryMillis: Long = DEFAULT_SENSITIVE_EXPIRY_MILLIS,
    /**
     * The most characters of text one clip keeps (0 = no limit). A longer copy
     * is stored cut to this length, and its rich-text markup, which no longer
     * matches the cut text, is dropped. What is on the system clipboard itself
     * is never touched.
     */
    var maxTextChars: Int = 0,
    /**
     * Whether a copy that arrives with markup keeps it (#414). Off, every
     * text clip is stored as plain text: the markup is dropped at the door,
     * so the clip wears no "Rich text" tag and pastes as plain text.
     */
    var keepRichText: Boolean = true,
    /**
     * Whether a clip marked [ClipItem.sensitive] stays out of the file (#1215).
     * On, an unpinned secret lives in memory only: [save] leaves it out, so
     * it never reaches the disk or a backup, and a restart forgets it the way
     * its short timer would have a few minutes later. The service turns it on
     * under "Hide it and delete it quickly", the one handling that marks clips
     * sensitive and promises to let go of them; "Keep it like any other clip"
     * marks nothing, so nothing changes there. A pinned secret is still saved:
     * pinning is an explicit "keep this".
     */
    var memoryOnlySensitive: Boolean = false,
) {

    @Serializable
    private data class Snapshot(val items: List<ClipItem> = emptyList())

    private val items = ArrayList<ClipItem>()
    /**
     * Clips taken out by [detach] whose Undo is still on offer. They are gone
     * from [items], so nothing lists, caps or expires them, but an image clip
     * keeps its file until [discard]: deleting it at once would leave Undo
     * nothing to put back.
     */
    private val detached = ArrayList<ClipItem>()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextId = 1L

    /**
     * The clip [forgetCleared] takes away: the one the system clipboard's
     * current copy became, when that copy was a secret. In memory only, like
     * the copy it stands for.
     */
    private var secretOnClipboard: Long? = null

    /** Where [save] writes, so the encode and the write can run off the caller's thread. */
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    companion object {
        const val DEFAULT_EXPIRY_MILLIS = 24L * 60 * 60 * 1000 // 1 day
        const val DEFAULT_MAX_ITEMS = 100
        const val DEFAULT_SENSITIVE_EXPIRY_MILLIS = 5L * 60 * 1000 // 5 minutes

        /**
         * The most characters of text one new clip keeps, whatever
         * [maxTextChars] says (#1215). 0 there still means "no limit of my
         * own", but not "no limit at all": every clip is held in memory, and
         * the whole history is encoded on every save, so one copied book
         * would cost that on every copy after it. FUTO Keyboard ignores
         * copies past the same length. A clip already in the history is never
         * cut by it, and the system clipboard keeps the whole copy.
         */
        const val HARD_MAX_TEXT_CHARS = 500_000

        /**
         * The most bytes of rich-text markup one new clip keeps (#1215). Past
         * it the clip is kept as plain text, as a clip cut by [maxTextChars]
         * is, since markup is usually several times its text.
         */
        const val HARD_MAX_HTML_BYTES = 1_048_576

        /**
         * The largest picture the service copies into the history (#1215).
         * The copy is counted as it streams and dropped, partial file and
         * all, once it passes this, so a huge image cannot fill the storage
         * one clip at a time.
         */
        const val HARD_MAX_IMAGE_BYTES = 32L * 1024 * 1024

        /**
         * How far past the clock [move] puts a clip dragged to the top of its
         * group: about three centuries, so no copy made later sorts above it.
         */
        private const val STICKY_TOP_MILLIS = 1.0e13
    }

    init {
        restore()
    }

    /**
     * Re-reads the stored clips, dropping whatever is held in memory.
     *
     * The settings app can delete the history from the Storage screen while the
     * keyboard is alive and holding the same list, and the next clip copied
     * would save that stale list straight back over the deletion. The clipboard
     * panel calls this on open, the same way the snippet panel does.
     */
    @Synchronized
    fun reload() {
        // A save drawn before this describes the list being thrown away; it
        // must not land over whatever the settings app left in the file.
        snapshotFile?.supersede()
        items.clear()
        nextId = 1L
        restore()
    }

    private fun restore() {
        storageFile?.takeIf { it.exists() }?.let { file ->
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                items.addAll(
                    snapshot.items.filter {
                        when {
                            it.kind == ClipKind.IMAGE -> it.imagePath != null
                            it.kind.isUriBacked -> it.uriString != null
                            else -> true
                        }
                    }
                )
            }
        }
        // A clip waiting on its Undo still owns its id, or a new clip could
        // take it and Undo would put back two clips with one id.
        nextId = ((items + detached).maxOfOrNull { it.id } ?: 0L) + 1
        // Image files whose item is gone (crash between file copy and save).
        // A detached clip's file is not an orphan yet: Undo may still want it.
        // One left behind by a process death is swept on the next start.
        imagesDir?.listFiles()?.let { files ->
            val referenced = (items + detached).mapNotNull { it.imagePath }.toSet()
            files.filter { it.absolutePath !in referenced }.forEach { it.delete() }
        }
    }

    @Synchronized
    fun add(
        text: String,
        sourceApp: String? = null,
        now: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        sourcePackage: String? = null,
    ): ClipItem? =
        addTextual(text, html = null, sourceApp = sourceApp, now = now, sensitive = sensitive, sourcePackage = sourcePackage)

    /** Styled text: [html] is the markup, [text] the plain-text rendering. */
    @Synchronized
    fun addHtml(
        text: String,
        html: String,
        sourceApp: String? = null,
        now: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        sourcePackage: String? = null,
    ): ClipItem? =
        addTextual(text, html = html, sourceApp = sourceApp, now = now, sensitive = sensitive, sourcePackage = sourcePackage)

    /**
     * Registers an image already copied to [imageFile] (inside [imagesDir]).
     * The store takes ownership of the file.
     */
    @Synchronized
    fun addImage(
        imageFile: File,
        mimeType: String,
        sourceApp: String? = null,
        now: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        sourcePackage: String? = null,
    ): ClipItem? {
        if (!imageFile.exists()) return null
        val item = ClipItem(
            id = nextId++,
            text = "",
            timestamp = now,
            kind = ClipKind.IMAGE,
            imagePath = imageFile.absolutePath,
            mimeType = mimeType,
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            sensitive = sensitive,
        )
        items.add(0, item)
        prune(now)
        return item.takeIf { items.contains(item) }
    }

    /**
     * Registers a copied file, folder or video by its source URI. Nothing is
     * copied — the clip is a reference, and inserting it later depends on the
     * URI grant still being valid.
     */
    @Synchronized
    fun addUri(
        uriString: String,
        displayName: String,
        mimeType: String,
        isDirectory: Boolean,
        size: Long = -1,
        sourceApp: String? = null,
        now: Long = System.currentTimeMillis(),
        durationMs: Long = -1,
        sensitive: Boolean = false,
        sourcePackage: String? = null,
    ): ClipItem? {
        if (uriString.isBlank()) return null
        // Re-copying the same file moves it to the top instead of duplicating.
        items.firstOrNull { it.uriString == uriString }?.let { existing ->
            items.remove(existing)
            val refreshed = existing.copy(
                timestamp = now,
                sourceApp = sourceApp ?: existing.sourceApp,
                sourcePackage = sourcePackage ?: existing.sourcePackage,
            )
            items.add(0, refreshed)
            return refreshed
        }
        val item = ClipItem(
            id = nextId++,
            text = displayName,
            timestamp = now,
            kind = when {
                isDirectory -> ClipKind.FOLDER
                mimeType.startsWith("video/") -> ClipKind.VIDEO
                else -> ClipKind.FILE
            },
            mimeType = mimeType,
            uriString = uriString,
            fileName = displayName,
            fileSize = if (isDirectory) -1 else size,
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            durationMs = durationMs,
            sensitive = sensitive,
        )
        items.add(0, item)
        prune(now)
        return item
    }

    private fun addTextual(
        text: String,
        html: String?,
        sourceApp: String?,
        now: Long,
        sensitive: Boolean = false,
        sourcePackage: String? = null,
    ): ClipItem? {
        val whole = text.trim()
        if (whole.isEmpty()) return null
        // A cut can land after a space; the clip should not end in one.
        val trimmed = capClipText(whole, effectiveMaxTextChars(maxTextChars)).trimEnd()
        // Markup for the whole text would paste back what the cut dropped;
        // and none at all when the user has asked for plain text (#414), or
        // when it is past the hard cap (#1215).
        val html = html?.takeIf { keepRichText && trimmed.length == whole.length && it.fitsHtmlCap() }
        val isLink = html == null && ClipLinks.asUrl(trimmed) != null
        // Re-copying an existing item moves it to the top instead of duplicating.
        val existing = items.firstOrNull { it.kind.isTextual && it.text == trimmed }
        if (existing != null) {
            items.remove(existing)
            val refreshed = existing.copy(
                timestamp = now,
                kind = when {
                    html != null -> ClipKind.HTML
                    isLink -> ClipKind.LINK
                    else -> existing.kind
                },
                htmlText = html ?: existing.htmlText,
                sourceApp = sourceApp ?: existing.sourceApp,
                sourcePackage = sourcePackage ?: existing.sourcePackage,
                // Sensitivity only ever tightens on a re-copy: the same text
                // arriving once without the flag is not evidence it is safe.
                sensitive = existing.sensitive || sensitive,
            )
            items.add(0, refreshed)
            return refreshed
        }
        val item = ClipItem(
            id = nextId++,
            text = trimmed,
            timestamp = now,
            kind = when {
                html != null -> ClipKind.HTML
                isLink -> ClipKind.LINK
                else -> ClipKind.TEXT
            },
            htmlText = html,
            mimeType = if (html != null) "text/html" else "text/plain",
            sourceApp = sourceApp,
            sourcePackage = sourcePackage,
            sensitive = sensitive,
        )
        items.add(0, item)
        prune(now)
        return item
    }

    @Synchronized
    fun items(now: Long = System.currentTimeMillis()): List<ClipItem> {
        prune(now)
        if (pinnedInPlace) return items.sortedByDescending { it.orderKey }
        // Within each group (pinned / unpinned) newest-first; [pinnedLast] only
        // flips which group leads. compareBy puts pinned=false first (pinned
        // last); compareByDescending puts pinned=true first (pinned first).
        val byPin = if (pinnedLast) {
            compareBy<ClipItem> { it.pinned }
        } else {
            compareByDescending<ClipItem> { it.pinned }
        }
        return items.sortedWith(byPin.thenByDescending { it.orderKey })
    }

    /**
     * Moves a clip to [toIndex] within its own group, pinned or unpinned, in
     * the order [items] lists them (#612), so the panel keeps it there.
     *
     * The clip gets a [ClipItem.position] between its new neighbours'. Moved
     * to the top of its group, it is put far enough above the clock that the
     * copies made after it still land under it: a clip dragged to the top is
     * one the user wants first, and the next copy should not take that away.
     * Returns whether anything moved.
     */
    @Synchronized
    fun move(id: Long, toIndex: Int, now: Long = System.currentTimeMillis()): Boolean {
        val listed = items(now)
        val item = listed.firstOrNull { it.id == id } ?: return false
        val group = if (pinnedInPlace) listed else listed.filter { it.pinned == item.pinned }
        val from = group.indexOf(item)
        val others = group - item
        val to = toIndex.coerceIn(0, others.size)
        if (to == from) return false
        val above = others.getOrNull(to - 1)
        val below = others.getOrNull(to)
        val key = when {
            above == null && below == null -> return false
            above == null -> maxOf(below!!.orderKey, now.toDouble()) + STICKY_TOP_MILLIS
            below == null -> above.orderKey - 1.0
            else -> (above.orderKey + below.orderKey) / 2.0
        }
        val index = items.indexOfFirst { it.id == id }
        items[index] = items[index].copy(position = key)
        return true
    }

    /**
     * Puts a clip between [newerId] and [olderId] (#612), the clips the panel
     * drew above and below where it was dropped, newest first, so a Reorder
     * view cut down by a filter or turned oldest first still moves a clip
     * where it was put. Either may be null for the end it was dropped at; a
     * clip put above everything stays above the copies made after it, as
     * [move] does. Returns whether anything moved.
     */
    @Synchronized
    fun place(id: Long, newerId: Long?, olderId: Long?, now: Long = System.currentTimeMillis()): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val newer = newerId?.let { n -> items.firstOrNull { it.id == n && it.id != id } }
        val older = olderId?.let { o -> items.firstOrNull { it.id == o && it.id != id } }
        val key = when {
            newer == null && older == null -> return false
            newer == null -> maxOf(older!!.orderKey, now.toDouble()) + STICKY_TOP_MILLIS
            older == null -> newer.orderKey - 1.0
            else -> (newer.orderKey + older.orderKey) / 2.0
        }
        items[index] = items[index].copy(position = key)
        return true
    }

    /**
     * Makes [id] the newest clip, as a fresh copy of it would (#970): its time
     * is now, and a place it was dragged to is let go, so it leads its group.
     * Its pin stays. Returns the clip as it now stands, or null when it is gone.
     */
    @Synchronized
    fun bump(id: Long, now: Long = System.currentTimeMillis()): ClipItem? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val bumped = items[index].copy(timestamp = now, position = null)
        items.removeAt(index)
        items.add(0, bumped)
        return bumped
    }

    /** Most recent textual clip, for snippet {clip} expansion. */
    @Synchronized
    fun latestText(now: Long = System.currentTimeMillis()): String? =
        items(now).firstOrNull { it.kind.isTextual }?.text

    /** Link clips still missing metadata, oldest-first in display order. */
    @Synchronized
    fun linksNeedingPreview(now: Long = System.currentTimeMillis()): List<ClipItem> =
        items(now).filter { it.kind == ClipKind.LINK && it.linkPreview == null }

    @Synchronized
    fun setLinkPreview(id: Long, preview: LinkPreview) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(linkPreview = preview)
    }

    /**
     * Clears the sensitive mark from every clip, for when the user stops
     * treating secrets specially. Without it, the clips marked while hiding was
     * on would stay masked (and uneditable) with no short timer left to take
     * them away. Returns whether anything changed, so the caller saves only then.
     */
    @Synchronized
    fun clearSensitive(): Boolean {
        var changed = false
        for (index in items.indices) {
            if (items[index].sensitive) {
                items[index] = items[index].copy(sensitive = false)
                changed = true
            }
        }
        return changed
    }

    /** Drops every fetched preview, e.g. when the user turns previews off. */
    @Synchronized
    fun clearLinkPreviews() {
        for (index in items.indices) {
            if (items[index].linkPreview != null) items[index] = items[index].copy(linkPreview = null)
        }
    }

    /**
     * Replaces a clip's [ClipItem.tags] (#612). Unlike an edit of its text,
     * nothing else about the clip changes. Returns whether there was such a
     * clip and its tags changed.
     */
    @Synchronized
    fun setTags(id: Long, tags: List<String>): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0 || items[index].tags == tags) return false
        items[index] = items[index].copy(tags = tags)
        return true
    }

    @Synchronized
    fun setPinned(id: Long, pinned: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(pinned = pinned)
    }

    /**
     * Rewrites a textual clip's text, as the user edited it in the panel.
     *
     * The clip keeps its id, its place and its timestamp — an edit is not a
     * fresh copy, and a clip that jumped to the top on being corrected would
     * change every number the panel shows beside the others. It keeps its pin
     * and its sensitivity too. What it loses is whatever the old text implied:
     * rich text's markup (the edit is plain text, so pasting the old markup
     * would paste the old words) and a link's fetched preview. Whether it is a
     * link at all is decided again from the new text.
     *
     * An edit that lands on the text of another clip merges into this one,
     * the way copying the same text twice never makes two entries, keeping
     * the other's pin if it had one.
     *
     * A change stamps [ClipItem.editedAt] with [now], so the panel can say the
     * clip is no longer what was copied (#576).
     *
     * Returns the clip as stored, or null when there is nothing to edit: no
     * such clip, a clip that is not text, or blank text — deleting is what
     * the delete button is for.
     */
    @Synchronized
    fun editText(id: Long, text: String, now: Long = System.currentTimeMillis()): ClipItem? {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val item = items[index]
        if (!item.kind.isTextual) return null
        val trimmed = capClipText(text.trim(), effectiveMaxTextChars(maxTextChars)).trimEnd()
        if (trimmed.isEmpty()) return null
        // Saved without a change: nothing to lose, so rich text keeps its markup.
        if (trimmed == item.text) return item
        val duplicate = items.firstOrNull { it.id != id && it.kind.isTextual && it.text == trimmed }
        val edited = item.copy(
            text = trimmed,
            kind = if (ClipLinks.asUrl(trimmed) != null) ClipKind.LINK else ClipKind.TEXT,
            htmlText = null,
            mimeType = "text/plain",
            linkPreview = null,
            pinned = item.pinned || duplicate?.pinned == true,
            sensitive = item.sensitive || duplicate?.sensitive == true,
            // The other clip's tags come along, as its pin does (#612).
            tags = parseClipTags((item.tags + duplicate?.tags.orEmpty()).joinToString(",")),
            editedAt = now,
        )
        items[index] = edited
        if (duplicate != null) removeWhere { it === duplicate }
        return edited
    }

    @Synchronized
    fun remove(id: Long) {
        removeWhere { it.id == id }
    }

    /**
     * Notes what the system clipboard holds now: [item], the clip its copy
     * became here (null when it went in nowhere), and whether the copying app
     * marked it [secret]. A secret, or a clip kept [ClipItem.sensitive], is
     * the one [forgetCleared] takes away again; any other copy replaces it.
     */
    @Synchronized
    fun systemClipChanged(item: ClipItem?, secret: Boolean) {
        secretOnClipboard = item?.takeIf { secret || it.sensitive }?.id
    }

    /**
     * The system clipboard was emptied while it held a secret's copy: most
     * often the password manager that copied it, wiping it once its time is
     * up. The clip goes from the history too, so the wipe leaves no trace
     * here either. Not a pinned one, since pinning is an explicit "keep
     * this", and not a secret a later copy had already replaced: nothing
     * wiped that one. Returns the clip removed, or null.
     */
    @Synchronized
    fun forgetCleared(): ClipItem? {
        val id = secretOnClipboard ?: return null
        secretOnClipboard = null
        val item = items.firstOrNull { it.id == id && !it.pinned } ?: return null
        removeWhere { it === item }
        return item
    }

    /**
     * Takes a clip out of the history the way [remove] does, but keeps an
     * image clip's file, so [reattach] can put the clip back whole. Every
     * detached clip ends in exactly one of [reattach] or [discard].
     *
     * Returns the clip as it was, or null when there is no such clip.
     */
    @Synchronized
    fun detach(id: Long): ClipItem? {
        val item = items.firstOrNull { it.id == id } ?: return null
        items.remove(item)
        detached.add(item)
        return item
    }

    /**
     * Undoes a [detach]: the clip goes back with its id, pin and timestamp,
     * so it lands in the slot it left rather than at the top.
     *
     * Not when the same text or file was copied again in the meantime: that
     * copy already stands where the clip would, and copying the same thing
     * twice never makes two entries. Expiry and the entry cap still apply, so
     * a clip that ran out while it was away does not come back.
     *
     * Returns the clip as stored, or null when it was not put back.
     */
    @Synchronized
    fun reattach(item: ClipItem, now: Long = System.currentTimeMillis()): ClipItem? {
        val held = detached.firstOrNull { it.id == item.id } ?: return null
        detached.remove(held)
        val copiedAgain = items.any {
            (held.kind.isTextual && it.kind.isTextual && it.text == held.text) ||
                (held.uriString != null && it.uriString == held.uriString)
        }
        if (copiedAgain || (held.kind == ClipKind.IMAGE && held.imagePath?.let { File(it).exists() } != true)) {
            held.imagePath?.let { File(it).delete() }
            return null
        }
        val restored = if (items.any { it.id == held.id }) held.copy(id = nextId++) else held
        items.add(restored)
        prune(now)
        return restored.takeIf { items.contains(it) }
    }

    /** Ends a [detach] for good: an image clip's file is deleted now. */
    @Synchronized
    fun discard(item: ClipItem) {
        val held = detached.firstOrNull { it.id == item.id } ?: return
        detached.remove(held)
        held.imagePath?.let { File(it).delete() }
    }

    @Synchronized
    fun clearUnpinned() {
        removeWhere { !it.pinned }
    }

    /**
     * [clearUnpinned] with an Undo (#371): every unpinned clip is taken out the
     * way [detach] takes one, so each can go back through [reattach] or end in
     * [discard]. Returns them, newest first; empty when every clip is pinned.
     */
    @Synchronized
    fun detachUnpinned(now: Long = System.currentTimeMillis()): List<ClipItem> {
        prune(now)
        val unpinned = items.filter { !it.pinned }.sortedByDescending { it.timestamp }
        items.removeAll { !it.pinned }
        detached.addAll(unpinned)
        return unpinned
    }

    @Synchronized
    fun search(query: String): List<ClipItem> = items().filter { it.matchesQuery(query) }

    /**
     * Writes the history. The list is copied under the store's lock; the
     * encoding — the whole history, every time, text and markup included —
     * and the write happen outside it, so the caller can run this on any
     * thread and the store stays usable meanwhile. Saves on different
     * threads land in the order their copies were taken ([SnapshotFile]).
     */
    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) { file.ticket() to Snapshot(persistedItems()) }
        file.write(ticket) { json.encodeToString(snapshot) }
    }

    /**
     * The clips [save] writes: all of them, less the unpinned secrets while
     * [memoryOnlySensitive] keeps those in memory (#1215).
     */
    private fun persistedItems(): List<ClipItem> =
        if (memoryOnlySensitive) items.filter { !it.sensitive || it.pinned } else items.toList()

    /**
     * Whether the file still holds a clip [save] would now leave out, so
     * turning [memoryOnlySensitive] on can save at once and take any secret an
     * older version wrote off the disk, rather than at the next copy.
     */
    @Synchronized
    fun holdsSecretsOnDisk(): Boolean = memoryOnlySensitive && items.any { it.sensitive && !it.pinned }

    private fun prune(now: Long) {
        // Sensitive clips are swept on their own shorter timer, which is not
        // capped by the history one: a five-minute leash has to hold even when
        // history is set to keep everything forever. [expiresAt] has both.
        removeWhere { item ->
            item.expiresAt(expiryMillis, sensitiveExpiryMillis)?.let { now > it } == true
        }
        // 0 is no cap (#414): the expiry above is then the only bound.
        val cap = maxItems.takeIf { it > 0 } ?: return
        while (items.count { !it.pinned } > cap) {
            val oldest = items.filter { !it.pinned }.minByOrNull { it.timestamp } ?: break
            removeWhere { it === oldest }
        }
    }

    private fun removeWhere(predicate: (ClipItem) -> Boolean) {
        val removed = items.filter(predicate)
        items.removeAll(predicate)
        removed.forEach { item -> item.imagePath?.let { File(it).delete() } }
    }
}

/**
 * When [this] clip expires, in epoch millis, or null when it never does: a
 * pinned clip, or history set to keep everything with no shorter leash for a
 * sensitive one. [expiryMillis] and [sensitiveExpiryMillis] are the store's
 * own; 0 turns either off. The one rule the store prunes by and the panel's
 * "expires in" label reads, so the two cannot disagree.
 */
fun ClipItem.expiresAt(expiryMillis: Long, sensitiveExpiryMillis: Long): Long? {
    if (pinned) return null
    val history = if (expiryMillis > 0) timestamp + expiryMillis else null
    val leash = if (sensitive && sensitiveExpiryMillis > 0) timestamp + sensitiveExpiryMillis else null
    return listOfNotNull(history, leash).minOrNull()
}

/**
 * The length [ClipboardStore] cuts a new clip to: the user's own [max] when
 * that is set and shorter, else [ClipboardStore.HARD_MAX_TEXT_CHARS] (#1215).
 */
fun effectiveMaxTextChars(max: Int): Int =
    if (max > 0) minOf(max, ClipboardStore.HARD_MAX_TEXT_CHARS) else ClipboardStore.HARD_MAX_TEXT_CHARS

/** Whether this markup is within [ClipboardStore.HARD_MAX_HTML_BYTES] (#1215). */
internal fun String.fitsHtmlCap(): Boolean =
    // A UTF-8 byte is at most four per char; most markup never needs the count.
    length * 4L <= ClipboardStore.HARD_MAX_HTML_BYTES ||
        toByteArray(Charsets.UTF_8).size <= ClipboardStore.HARD_MAX_HTML_BYTES

/**
 * [text] cut to [max] characters, one fewer rather than splitting a surrogate
 * pair; unchanged when it fits or [max] is 0 or less (no limit).
 */
fun capClipText(text: String, max: Int): String {
    if (max <= 0 || text.length <= max) return text
    var end = max
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/**
 * The most characters of a clip the panel hands to text layout. Laying out a
 * paragraph measures all of it, however few lines are drawn, so a clip of a
 * whole book would otherwise cost a book's worth of shaping on every panel
 * open. Far more than any preview line count can show.
 */
const val CLIP_PREVIEW_CHAR_CAP = 4_000

/**
 * The part of [text] a preview of [lines] lines can show: up to the line break
 * after the one past the last shown line, and never past [cap]. One extra line
 * is kept so the text still overflows, and the preview still ends in an
 * ellipsis when there is more.
 */
fun clipPreviewText(text: String, lines: Int, cap: Int = CLIP_PREVIEW_CHAR_CAP): String {
    val shown = lines.coerceAtLeast(1)
    val limit = minOf(text.length, cap)
    var breaks = 0
    var end = limit
    for (i in 0 until limit) {
        if (text[i] == '\n' && ++breaks > shown) {
            end = i
            break
        }
    }
    if (end >= text.length) return text
    return capClipText(text, end.coerceAtLeast(1))
}
