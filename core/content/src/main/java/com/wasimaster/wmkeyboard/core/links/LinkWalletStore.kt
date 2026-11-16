package com.wasimaster.wmkeyboard.core.links

import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One saved link: a name to show on its button and the address it opens (#1214). */
@Serializable
data class SavedLink(
    val id: Long,
    val title: String,
    val url: String,
    val addedAt: Long = 0L,
)

/**
 * The Link wallet (#1214): links the user keeps as buttons in the keyboard,
 * a tap opening each one in the browser. No sync and no account; one JSON file
 * in app-private storage, in the order the user put them, written through
 * [SnapshotFile] like the to-do list's. The contract is the same as
 * [com.wasimaster.wmkeyboard.core.todo.TodoStore]'s: reload on open, save
 * after each change.
 */
class LinkWalletStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val links: List<SavedLink> = emptyList(),
    )

    private val links = ArrayList<SavedLink>()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private var nextId = 1L
    private val snapshotFile = storageFile?.let(::SnapshotFile)
    private var seenStamp = 0L

    init {
        reload()
    }

    /** Every link, in the user's order. */
    @Synchronized
    fun all(): List<SavedLink> = links.toList()

    @Synchronized
    fun item(id: Long): SavedLink? = links.firstOrNull { it.id == id }

    /**
     * Adds [url] under [title] at the end of the list. The address is tidied
     * by [normalise]; null when it is not one, or once the wallet is full.
     * A blank title falls back to the address's host.
     */
    @Synchronized
    fun add(title: String, url: String, now: Long = System.currentTimeMillis()): SavedLink? {
        if (links.size >= MAX_LINKS) return null
        val clean = normalise(url) ?: return null
        val id = SnippetStore.freshId(nextId, now)
        nextId = id + 1
        val added = SavedLink(id, cleanTitle(title, clean), clean, now)
        links.add(added)
        return added
    }

    /** New name and address for link [id]; null when it is gone or the address is not one. */
    @Synchronized
    fun update(id: Long, title: String, url: String): SavedLink? {
        val index = links.indexOfFirst { it.id == id }
        if (index < 0) return null
        val clean = normalise(url) ?: return null
        val changed = links[index].copy(title = cleanTitle(title, clean), url = clean)
        links[index] = changed
        return changed
    }

    /** Moves link [id] [by] places, earlier for a negative number; false when it cannot move. */
    @Synchronized
    fun move(id: Long, by: Int): Boolean {
        val from = links.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + by).coerceIn(0, links.lastIndex)
        if (to == from) return false
        links.add(to, links.removeAt(from))
        return true
    }

    /** Takes link [id] out; returns it with the place it had, for Undo. */
    @Synchronized
    fun delete(id: Long): Pair<Int, SavedLink>? {
        val index = links.indexOfFirst { it.id == id }
        if (index < 0) return null
        return index to links.removeAt(index)
    }

    /** Puts back a link [delete] took, at the place it had. */
    @Synchronized
    fun restore(index: Int, link: SavedLink) {
        if (links.any { it.id == link.id }) return
        links.add(index.coerceIn(0, links.size), link)
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) { file.ticket() to Snapshot(1, links.toList()) }
        if (file.write(ticket) { json.encodeToString(snapshot) }) {
            synchronized(this) { seenStamp = file.file.lastModified() }
        }
    }

    /** Re-reads the backing file, dropping whatever is in memory. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        links.clear()
        val file = storageFile
        if (file != null && file.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                snapshot.links.mapNotNullTo(links) { link ->
                    normalise(link.url)?.let { link.copy(url = it, title = cleanTitle(link.title, it)) }
                }
            }
            seenStamp = file.lastModified()
        } else {
            seenStamp = 0L
        }
        nextId = (links.maxOfOrNull { it.id } ?: 0L) + 1
    }

    /** Reloads when another process (a restore) rewrote the file; true when it did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        if ((storageFile?.lastModified() ?: 0L) == seenStamp) return false
        reload()
        return true
    }

    companion object {
        /** Where the wallet lives under filesDir; the backup reads the same file. */
        const val FILE_PATH = "links/links.json"

        /** Most links the wallet holds. */
        const val MAX_LINKS = 500

        /** Longest a button's name gets. */
        const val MAX_TITLE_LENGTH = 80

        /** Longest an address gets. */
        const val MAX_URL_LENGTH = 4000

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /** A bare host, a dotted name with no spaces in it: "example.com", "docs.foo.org/a". */
        private val BARE_HOST = Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?([/?#]\\S*)?$")

        /**
         * [text] as an address the browser can open, or null. Trimmed; a bare
         * host gets `https://` in front; anything with a scheme of its own
         * (`http`, `https`, `mailto`, `geo`, an app's own) is kept as it is,
         * except `javascript:` and `file:`, which a tap must never run or
         * read. Whitespace inside an address means it is not one.
         */
        fun normalise(text: String): String? {
            val t = text.trim()
            if (t.isEmpty() || t.length > MAX_URL_LENGTH || t.any { it.isWhitespace() }) return null
            if (SCHEME.containsMatchIn(t)) {
                val scheme = t.substringBefore(':').lowercase()
                if (scheme == "javascript" || scheme == "file" || scheme == "data" || scheme == "content") return null
                // "localhost:8080" reads as a scheme to the pattern; it is a host.
                if (t.substringAfter(':').firstOrNull()?.isDigit() == true && scheme !in KNOWN_SCHEMES) {
                    return "http://$t"
                }
                return if (scheme == "http" || scheme == "https") {
                    t.takeIf { it.substringAfter("://", "").isNotEmpty() }
                } else {
                    t
                }
            }
            return if (BARE_HOST.matches(t)) "https://$t" else null
        }

        /** The first link-looking run in [text], for an Add that starts from a copied message. */
        fun firstLinkIn(text: String): String? {
            val whole = normalise(text)
            if (whole != null) return whole
            return text.split(Regex("\\s+")).firstNotNullOfOrNull { word ->
                val trimmed = word.trim('(', ')', '<', '>', '"', '\'', ',', '.', ';')
                if (trimmed.contains("://") || BARE_HOST.matches(trimmed)) normalise(trimmed) else null
            }
        }

        /** The host of [url] without a leading "www.", for a button nobody named. */
        fun hostOf(url: String): String {
            val rest = url.substringAfter("://", url.substringAfter(':'))
            val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
            return host.removePrefix("www.").ifEmpty { url }
        }

        private fun cleanTitle(title: String, url: String): String =
            title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_LENGTH).ifEmpty { hostOf(url).take(MAX_TITLE_LENGTH) }

        private val KNOWN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo", "market", "intent")
    }
}
