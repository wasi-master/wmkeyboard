package com.wasimaster.wmkeyboard.core.snippets

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a trigger's leading capital is carried into the expansion.
 *
 * Only consulted when [Snippet.propagateCase] is on and the trigger was typed
 * with its first letter capitalized. An all-caps trigger always gives an
 * all-caps expansion whatever this says, since there is nothing else it could
 * reasonably mean.
 *
 * The serial names are Espanso's `uppercase_style` values, so the two formats
 * agree without a translation table.
 */
@Serializable
enum class UppercaseStyle {
    /** "Alh" gives "Although": the first letter only. */
    @SerialName("capitalize")
    CAPITALIZE,

    /** "Alh" gives "Although Etc": every word's first letter. */
    @SerialName("capitalize_words")
    CAPITALIZE_WORDS,

    /** "Alh" gives "ALTHOUGH", the same as an all-caps trigger would. */
    @SerialName("uppercase")
    UPPERCASE,
}

/**
 * What the keyboard does when a matched snippet has more than one thing to say
 * — several expansions of its own, or snippets linked to it.
 *
 * The app-wide answer. A snippet may override it with its own [MultiExpand].
 */
@Serializable
enum class MultiExpandMode {
    /**
     * Nothing is inserted. Every expansion becomes a chip on the suggestion
     * strip and the text stays as it was typed until one is tapped.
     */
    @SerialName("chips_only")
    CHIPS_ONLY,

    /**
     * The first expansion replaces the trigger straight away, exactly as a
     * single-expansion snippet does, and the rest wait on the strip as chips
     * that swap what was just inserted.
     */
    @SerialName("insert_first")
    INSERT_FIRST,
}

/**
 * One snippet's answer to [MultiExpandMode], or [DEFAULT] to follow the app's.
 *
 * A separate enum from [MultiExpandMode] so the app-wide setting can never hold
 * "follow the app-wide setting".
 */
@Serializable
enum class MultiExpand {
    @SerialName("default")
    DEFAULT,

    @SerialName("chips_only")
    CHIPS_ONLY,

    @SerialName("insert_first")
    INSERT_FIRST;

    /** This snippet's mode, given [global] is what the app is set to. */
    fun resolve(global: MultiExpandMode): MultiExpandMode = when (this) {
        DEFAULT -> global
        CHIPS_ONLY -> MultiExpandMode.CHIPS_ONLY
        INSERT_FIRST -> MultiExpandMode.INSERT_FIRST
    }
}

/**
 * A reusable text snippet inserted from the keyboard's snippet panel.
 *
 * A snippet may also carry a trigger that expands it as the user types: either
 * [trigger], one exact word, or [triggerPattern], a regular expression over the
 * words behind the cursor. A snippet that somehow carries both keeps the word,
 * which is the more specific and the cheaper of the two.
 *
 * [confirm] turns that trigger from a rewrite into an offer: the keyboard shows
 * a chip and waits to be tapped rather than replacing what was typed.
 *
 * The optional fields are written only when they are set. Every published pack
 * is a hand-maintained file, and [SnippetFile] encodes defaults, so without that
 * a plain snippet would grow half a dozen empty keys it never uses.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class Snippet(
    val id: Long,
    val label: String,
    val text: String,
    val createdAt: Long = 0,
    /**
     * Word or phrase that, typed on its own and finished with a
     * space/punctuation/enter, auto-expands to [text].
     *
     * May carry a leading run of punctuation, as in `:shrug` or `//date`, and
     * may hold spaces, as in `gr db`. The first form is how nearly every
     * Espanso package spells its triggers; the second is how a trigger becomes
     * a phrase. Both are matched by their own path in [SnippetIndex] rather
     * than by the plain whole-word lookup, because the composing buffer only
     * ever holds the last word; see [SnippetMatcher.splitPrefix]. It must still
     * end in a word: `->` has nothing to look up.
     */
    val trigger: String? = null,
    /**
     * Extra spellings of [trigger], each matched exactly as [trigger] is.
     *
     * Espanso's `triggers:` list maps onto this: the first becomes [trigger] and
     * the rest land here.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val aliases: List<String> = emptyList(),
    /**
     * Carry the trigger's own capitalization into the expansion.
     *
     * With this off, "OMW" and "omw" both expand to whatever [text] says. With
     * it on, "OMW" expands to an all-caps version and "Omw" to a version capitalized
     * per [uppercaseStyle]. Espanso calls this `propagate_case`, and packages
     * that expand ordinary words rather than codes lean on it heavily: without
     * it a package that fixes "a bas" to "à bas" mangles "A Bas" at the start of
     * a sentence.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val propagateCase: Boolean = false,
    /** What a leading capital means when [propagateCase] is on. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val uppercaseStyle: UppercaseStyle = UppercaseStyle.CAPITALIZE,
    /**
     * Regular expression matched against the words behind the cursor. Capture
     * groups reach [text] as `$1` to `$9`; see [SnippetMatcher].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val triggerPattern: String? = null,
    /**
     * How many words back the match may reach. 0 asks for
     * [SnippetMatcher.DEFAULT_WORDS].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val triggerWords: Int = 0,
    /**
     * Ask before expanding. The trigger still matches, but instead of rewriting
     * the text the keyboard offers the expansion as a chip on the suggestion
     * strip and inserts nothing until it is tapped.
     *
     * For text somebody else wrote — a downloaded pack of replies — this is the
     * difference between a keyboard that helps and one that rewrites sentences
     * out from under the person typing them.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val confirm: Boolean = false,
    /**
     * The [SnippetFolder] this snippet belongs to, or 0 for none.
     *
     * Folders are one level deep and a snippet is in at most one of them, so
     * this is an id rather than a list. 0 rather than null because "no folder"
     * is the overwhelmingly common case and a null would be written as an
     * explicit key by every exporter that encodes defaults.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val folderId: Long = 0,
    /**
     * Further things this snippet may insert, beyond [text].
     *
     * [text] is always the first expansion and the default one: everything that
     * inserts a snippet without asking — a panel tap, an add-on preview, an
     * export to a format that has no word for "several" — keeps meaning exactly
     * what it meant before this list existed. Order is meaning, so moving an
     * alternate to the front is how the default changes.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val alternates: List<String> = emptyList(),
    /**
     * Ids of snippets this one leads to, which the strip offers alongside its
     * own expansions.
     *
     * A graph and not a tree: "Tehran" belongs under "Iran", under "Capitals"
     * and under "Cities" at once, so a snippet may have any number of parents.
     * Cycles and ids of snippets that no longer exist are both tolerated when
     * the list is read — see [SnippetStore.childrenOf] — because a store shared
     * by two processes cannot promise otherwise.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val children: List<Long> = emptyList(),
    /** Free-form labels, for finding and filtering a long list. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tags: List<String> = emptyList(),
    /** This snippet's override of the app-wide [MultiExpandMode]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val multiExpand: MultiExpand = MultiExpand.DEFAULT,
) {

    /**
     * Every way this snippet may be typed: [trigger] then each of [aliases],
     * trimmed and with the empty ones dropped.
     *
     * One list rather than two call sites, so nothing that consults triggers can
     * quietly forget about aliases.
     */
    fun spellings(): List<String> {
        val first = trigger?.trim()?.takeIf { it.isNotEmpty() }
        if (aliases.isEmpty()) return listOfNotNull(first)
        val out = ArrayList<String>(aliases.size + 1)
        first?.let(out::add)
        aliases.mapNotNullTo(out) { it.trim().takeIf(String::isNotEmpty) }
        return out
    }

    /**
     * Everything this snippet may insert: [text] then each non-blank
     * [alternates] entry. Never empty, and the first is always the default.
     *
     * One list rather than two call sites, for the same reason [spellings] is
     * one: nothing that inserts a snippet can quietly forget the alternates.
     */
    fun expansions(): List<String> {
        if (alternates.isEmpty()) return listOf(text)
        val out = ArrayList<String>(alternates.size + 1)
        out.add(text)
        alternates.filterTo(out) { it.isNotBlank() }
        return out
    }

    /** True when a match of this snippet has more than one thing to offer. */
    fun hasChoices(): Boolean = alternates.isNotEmpty() || children.isNotEmpty()

    /**
     * True when a match of this snippet offers itself instead of rewriting the
     * text, given [global] is what the app is set to.
     *
     * Two reasons a snippet only offers: it was told to ask first, or it has
     * several things to say and neither it nor the app wants one of them
     * chosen for the user. The index partitions on this and the commit path
     * reads it, so the strip and the space bar always agree.
     */
    fun asks(global: MultiExpandMode): Boolean =
        confirm || (hasChoices() && multiExpand.resolve(global) == MultiExpandMode.CHIPS_ONLY)

    /**
     * This snippet with [expansions] as its expansions: the first becomes
     * [text] and the rest [alternates]. An empty list changes nothing, since a
     * snippet with nothing to insert is not a snippet.
     */
    fun withExpansions(expansions: List<String>): Snippet {
        val kept = expansions.filter { it.isNotBlank() }
        if (kept.isEmpty()) return this
        return copy(text = kept.first(), alternates = kept.drop(1))
    }
}

/**
 * One thing the keyboard may insert after a snippet matched: an expansion of
 * the snippet itself, or the default expansion of a snippet linked to it.
 *
 * [text] is already expanded — variables resolved, capture references filled
 * in, casing applied, the `{cursor}` marker stripped — so a caller inserts it
 * verbatim and puts the caret at [cursorOffset].
 */
data class SnippetCandidate(
    /** The snippet the text belongs to: the matched one, or one of its children. */
    val snippetId: Long,
    val label: String,
    val text: String,
    val cursorOffset: Int,
    /** True when this came from a linked snippet rather than the matched one. */
    val viaChild: Boolean,
    /** True when tapping into that linked snippet would show more. */
    val drillable: Boolean,
)

/**
 * A named group of snippets, and the switch that arms or disarms their
 * triggers together.
 *
 * [enabled] is about *automatic* behaviour only: a snippet in a folder that is
 * switched off never expands as you type and never offers itself as a chip,
 * but it is still listed in the snippets panel and still inserts when tapped.
 * That is the whole point of the switch — a folder of work replies that must
 * not fire mid-message is still a folder you want to reach for on purpose.
 *
 * Folders are drawn in list order, which [SnippetStore.reorderFolders] rewrites.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SnippetFolder(
    val id: Long,
    val name: String,
    /** Whether the folder's snippets may expand on their own. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val enabled: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val createdAt: Long = 0,
)

/**
 * User-defined snippets with template variables, persisted as JSON in
 * app-private storage (same offline-first pattern as ClipboardStore).
 *
 * Supported variables, expanded at insertion time — see [SnippetVariable] for
 * the full list, which covers date/time parts, the clipboard, the app being
 * typed into, the current selection, and a `{cursor}` placement marker.
 *
 * Snippets may be grouped into [SnippetFolder]s, one level deep. A folder that
 * is switched off keeps its snippets out of the trigger index and nowhere else;
 * see [SnippetFolder].
 */
class SnippetStore(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val snippets: List<Snippet> = emptyList(),
        val folders: List<SnippetFolder> = emptyList(),
    )

    private val snippets = ArrayList<Snippet>()
    private val folders = ArrayList<SnippetFolder>()
    // Unknown keys are ignored so a file written by a newer build still loads.
    // Unknown *enum values* are coerced to the default for the same reason: a
    // single unrecognised word must not turn a whole file of snippets into
    // "this is not a snippet file".
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private var nextId = 1L
    private var nextFolderId = 1L

    /**
     * The triggers, prepared for lookup. Built on first use and thrown away by
     * every mutator, so the keyboard never pays for a scan on the typing path
     * and never has to be told the list changed.
     */
    @Volatile
    private var lookup: SnippetIndex? = null

    /**
     * What the app is set to do with a snippet that has several expansions.
     *
     * The index partitions snippets into the ones that rewrite text and the
     * ones that only offer to, so it has to know this. Set from the IME's
     * settings; until it is, the safe half of the choice applies.
     */
    private var multiExpandMode = MultiExpandMode.CHIPS_ONLY

    init {
        reload()
    }

    @Synchronized
    fun items(): List<Snippet> = snippets.toList()

    @Synchronized
    fun add(label: String, text: String, trigger: String? = null, now: Long = System.currentTimeMillis()): Snippet =
        add(Snippet(id = 0, label = label, text = text, trigger = trigger), now)

    /**
     * Adds [snippet] under a fresh id, keeping every other field it carries.
     *
     * Import and add-on installation both go through here. Rebuilding a snippet
     * out of a handful of named values instead would quietly drop whatever
     * field was added last, with no error and no repair note.
     */
    @Synchronized
    fun add(snippet: Snippet, now: Long = System.currentTimeMillis()): Snippet {
        val added = normalize(snippet, id = nextId++, createdAt = now)
        snippets.add(added)
        lookup = null
        return added
    }

    /**
     * [snippet] as it is stored: trimmed, capped, and with the lists it carries
     * cleaned of blanks, duplicates and links that point at itself.
     *
     * Shared by [add] and [update] so a field can never be normalised on one
     * path and stored raw on the other.
     */
    private fun normalize(snippet: Snippet, id: Long, createdAt: Long): Snippet {
        val trigger = normalizeSpelling(snippet.trigger)
        return snippet.copy(
            id = id,
            label = snippet.label.trim(),
            createdAt = createdAt,
            trigger = trigger,
            aliases = normalizeAliases(snippet.aliases, trigger),
            triggerPattern = normalizeTrigger(snippet.triggerPattern),
            triggerWords = snippet.triggerWords.coerceIn(0, SnippetMatcher.MAX_WORDS),
            folderId = knownFolder(snippet.folderId),
            alternates = normalizeAlternates(snippet.alternates, snippet.text),
            children = normalizeChildren(snippet.children, id),
            tags = normalizeTags(snippet.tags),
        )
    }

    /**
     * Adds a whole file's worth of snippets, recreating the folders they came
     * in and keeping which snippet sat in which one.
     *
     * Folder ids in a file are as untrustworthy as snippet ids — two files
     * written on two phones both start at 1 — so every folder here is created
     * afresh and the snippets are re-pointed at the new ids. A snippet naming a
     * folder the file never declared, and a snippet that named none, both land
     * in [fallbackFolderId]: 0 for an ordinary import, and the pack's own folder
     * when an add-on is being installed.
     *
     * Returns the snippets as stored, in the order they were given.
     */
    @Synchronized
    fun addAll(
        snippets: List<Snippet>,
        folders: List<SnippetFolder> = emptyList(),
        fallbackFolderId: Long = 0,
        now: Long = System.currentTimeMillis(),
    ): List<Snippet> {
        val remapped = HashMap<Long, Long>(folders.size)
        for (folder in folders) {
            remapped[folder.id] = addFolder(folder.name, folder.enabled, now).id
        }
        // Two passes, for the reason the folder ids need one: a file's snippet
        // ids are as untrustworthy as its folder ids, so every link has to be
        // re-pointed at the id this store handed out. The links go in on the
        // second pass because the first has not met the later snippets yet.
        val ids = HashMap<Long, Long>(snippets.size)
        val added = snippets.map { snippet ->
            val stored = add(
                snippet.copy(
                    folderId = remapped[snippet.folderId] ?: fallbackFolderId,
                    children = emptyList(),
                ),
                now,
            )
            // First wins: two files merged into one list may both start at id 1.
            ids.putIfAbsent(snippet.id, stored.id)
            stored
        }
        var relinked = false
        val out = added.mapIndexed { i, stored ->
            // A link to something the file never declared means nothing here:
            // that id belongs to whatever this store happened to store under it.
            val links = snippets[i].children.mapNotNull { ids[it] }
            if (links.isEmpty()) return@mapIndexed stored
            relinked = true
            val linked = stored.copy(children = normalizeChildren(links, stored.id))
            this.snippets[this.snippets.indexOfFirst { it.id == stored.id }] = linked
            linked
        }
        if (relinked) lookup = null
        return out
    }

    /**
     * Rewrites the stored snippet whose id [snippet] carries, from every field
     * [snippet] carries.
     *
     * A whole snippet rather than a list of named values: a snippet has grown
     * fields four times now, and each time the editor's save path silently
     * dropped whichever one the signature had not caught up with. The stored id
     * and creation time win, so an editor need not carry them faithfully.
     */
    @Synchronized
    fun update(snippet: Snippet) {
        val index = snippets.indexOfFirst { it.id == snippet.id }
        if (index < 0) return
        snippets[index] = normalize(snippet, id = snippet.id, createdAt = snippets[index].createdAt)
        lookup = null
    }

    @Synchronized
    fun remove(id: Long) {
        if (!snippets.removeAll { it.id == id }) return
        unlink(setOf(id))
        lookup = null
    }

    /** Drops [ids] from every surviving snippet's [Snippet.children]. */
    private fun unlink(ids: Set<Long>) {
        if (ids.isEmpty()) return
        for (i in snippets.indices) {
            val children = snippets[i].children
            if (children.none { it in ids }) continue
            snippets[i] = snippets[i].copy(children = children.filterNot { it in ids })
        }
    }

    /**
     * Rewrites the stored order to [ids], which is also the order the snippets
     * panel draws in.
     *
     * The list was creation order everywhere, and the panel has no search, so a
     * snippet used every day sank under a year of one-off ones with no way to
     * lift it back. New snippets still append; this is the only thing that
     * moves an existing one.
     *
     * Ids the store does not know are dropped and ids missing from [ids] keep
     * their relative order at the end, so a reorder raced against a delete or
     * an import can neither lose a snippet nor resurrect one.
     */
    @Synchronized
    fun reorder(ids: List<Long>) {
        val byId = snippets.associateBy { it.id }
        val moved = ids.mapNotNull(byId::get)
        val movedIds = moved.mapTo(HashSet()) { it.id }
        val rest = snippets.filter { it.id !in movedIds }
        snippets.clear()
        snippets.addAll(moved)
        snippets.addAll(rest)
        lookup = null
    }

    // ---- folders ---------------------------------------------------------

    @Synchronized
    fun folders(): List<SnippetFolder> = folders.toList()

    @Synchronized
    fun folder(id: Long): SnippetFolder? = folders.firstOrNull { it.id == id }

    /**
     * Adds a folder under a fresh id and returns it.
     *
     * The name is taken as given beyond a trim; an empty one is the caller's
     * problem, since only a screen knows what to call an unnamed folder.
     */
    @Synchronized
    fun addFolder(
        name: String,
        enabled: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): SnippetFolder {
        val added = SnippetFolder(
            id = nextFolderId++,
            name = name.trim(),
            enabled = enabled,
            createdAt = now,
        )
        folders.add(added)
        // A folder arrives switched off often enough — an installed pack that
        // must not fire yet — that the index cannot be assumed still good.
        lookup = null
        return added
    }

    @Synchronized
    fun renameFolder(id: Long, name: String) {
        val index = folders.indexOfFirst { it.id == id }
        if (index >= 0) folders[index] = folders[index].copy(name = name.trim())
    }

    /** Arms or disarms every trigger in the folder. See [SnippetFolder]. */
    @Synchronized
    fun setFolderEnabled(id: Long, enabled: Boolean) {
        val index = folders.indexOfFirst { it.id == id }
        if (index >= 0 && folders[index].enabled != enabled) {
            folders[index] = folders[index].copy(enabled = enabled)
            lookup = null
        }
    }

    /**
     * Deletes a folder. Its snippets survive it and become ungrouped unless
     * [withSnippets], which is the "delete the pack and everything in it" case.
     *
     * Losing a folder must never quietly lose the text inside it, so the
     * surviving-snippets path is the default and the destructive one has to be
     * asked for by name.
     */
    @Synchronized
    fun removeFolder(id: Long, withSnippets: Boolean = false) {
        if (!folders.removeAll { it.id == id }) return
        if (withSnippets) {
            val gone = snippets.mapNotNullTo(HashSet()) { if (it.folderId == id) it.id else null }
            snippets.removeAll { it.folderId == id }
            unlink(gone)
        } else {
            for (i in snippets.indices) {
                if (snippets[i].folderId == id) snippets[i] = snippets[i].copy(folderId = 0)
            }
        }
        lookup = null
    }

    /**
     * Deletes [id] if nothing is left in it.
     *
     * What uninstalling a pack does after removing the pack's own snippets: the
     * folder goes too, unless the user has moved something else into it, in
     * which case it is now theirs.
     */
    @Synchronized
    fun removeFolderIfEmpty(id: Long) {
        if (snippets.none { it.folderId == id }) removeFolder(id)
    }

    /** Rewrites folder order, the order every list of folders draws in. */
    @Synchronized
    fun reorderFolders(ids: List<Long>) {
        val byId = folders.associateBy { it.id }
        val moved = ids.mapNotNull(byId::get)
        val movedIds = moved.mapTo(HashSet()) { it.id }
        val rest = folders.filter { it.id !in movedIds }
        folders.clear()
        folders.addAll(moved)
        folders.addAll(rest)
    }

    /** Moves one snippet into [folderId], or out of every folder when it is 0. */
    @Synchronized
    fun moveToFolder(snippetId: Long, folderId: Long) {
        val index = snippets.indexOfFirst { it.id == snippetId }
        if (index >= 0) {
            snippets[index] = snippets[index].copy(folderId = knownFolder(folderId))
            lookup = null
        }
    }

    /** [id] itself when a folder has it, else 0 — no snippet points at nothing. */
    private fun knownFolder(id: Long): Long =
        if (id != 0L && folders.any { it.id == id }) id else 0L

    /** The stored snippet with this id, or null. */
    @Synchronized
    fun item(id: Long): Snippet? = snippets.firstOrNull { it.id == id }

    /**
     * The snippets [snippet] links to, in the order it lists them.
     *
     * Ids that name nothing, and one that names [snippet] itself, are skipped
     * rather than treated as an error: the store is shared by two processes and
     * a link can outlive what it pointed at.
     *
     * Resolved against every snippet, not only the armed ones. Switching a
     * folder off disarms its *triggers*; a snippet in it that some other
     * snippet points at is still that snippet's content.
     */
    @Synchronized
    fun childrenOf(snippet: Snippet): List<Snippet> {
        if (snippet.children.isEmpty()) return emptyList()
        val seen = HashSet<Long>()
        return snippet.children.mapNotNull { id ->
            if (id == snippet.id || !seen.add(id)) null else snippets.firstOrNull { it.id == id }
        }
    }

    /** Every tag in the store, each once, sorted for a stable filter row. */
    @Synchronized
    fun tags(): List<String> {
        val seen = HashMap<String, String>()
        for (snippet in snippets) {
            for (tag in snippet.tags) seen.putIfAbsent(tag.lowercase(Locale.ROOT), tag)
        }
        return seen.values.sortedBy { it.lowercase(Locale.ROOT) }
    }

    /**
     * How many things a match of [snippet] has to offer: its own expansions
     * plus one for each snippet it links to. 1 for an ordinary snippet.
     *
     * Counted rather than expanded, so a list of tiles can ask it about every
     * snippet it draws.
     */
    @Synchronized
    fun candidateCount(snippet: Snippet): Int =
        snippet.expansions().size + childrenOf(snippet).size

    /**
     * Tells the store what the app is set to do with a snippet that has several
     * expansions. Throws the index away, since it partitions on the answer.
     */
    @Synchronized
    fun setMultiExpandMode(mode: MultiExpandMode) {
        if (multiExpandMode == mode) return
        multiExpandMode = mode
        lookup = null
    }

    /** What the app is currently set to. See [setMultiExpandMode]. */
    @Synchronized
    fun multiExpandMode(): MultiExpandMode = multiExpandMode

    /**
     * True when a match of [snippet] must offer itself rather than rewrite the
     * text: it was told to ask first, or it has more than one thing to say and
     * the resolved mode is [MultiExpandMode.CHIPS_ONLY].
     *
     * The commit path asks this instead of reading [Snippet.confirm] directly,
     * and the index partitions on the same answer, so the two can never
     * disagree about which snippets fire on the space bar.
     */
    @Synchronized
    fun offers(snippet: Snippet): Boolean = snippet.asks(multiExpandMode)

    /**
     * What a match of [snippet] may insert, in the order the strip offers it:
     * this snippet's own expansions, then the default expansion of each snippet
     * it links to.
     *
     * [typed] is the trigger as it was actually typed, which is what decides
     * whether the whole set is re-cased; it applies to the linked snippets too,
     * since the user shouted the word that reached them. [groups] are a pattern
     * match's captures, group 0 first: when they are given, every expansion is
     * templated with the same ones, so `$1` means the same thing in the second
     * expansion as in the first. [blank] is the panel's version, where a
     * capture reference becomes a blank for the user to type into.
     *
     * One level deep, and a snippet is never its own child, so a cycle costs
     * nothing and cannot recurse. Two expansions that come out the same after
     * expansion are offered once.
     */
    @Synchronized
    @Suppress("LongParameterList")
    fun candidates(
        snippet: Snippet,
        typed: String? = null,
        now: Long = System.currentTimeMillis(),
        context: Companion.Context = Companion.Context(),
        groups: List<String?> = emptyList(),
        blank: Boolean = false,
    ): List<SnippetCandidate> {
        val casing = typed?.let { casingFor(snippet, it) } ?: TriggerCasing.NONE
        val out = ArrayList<SnippetCandidate>()
        val seen = HashSet<String>()
        fun emit(source: Snippet, raw: String, viaChild: Boolean) {
            val expanded = expandCandidate(raw, now, context, casing, groups, blank)
            if (!seen.add(expanded.text)) return
            out += SnippetCandidate(
                snippetId = source.id,
                label = source.label,
                text = expanded.text,
                cursorOffset = expanded.cursorOffset,
                viaChild = viaChild,
                drillable = viaChild && source.hasChoices(),
            )
        }
        for (raw in snippet.expansions()) emit(snippet, raw, viaChild = false)
        for (child in childrenOf(snippet)) emit(child, child.expansions().first(), viaChild = true)
        return out
    }

    /**
     * [candidates] for a pattern hit.
     *
     * The first candidate is [match]'s own text rather than the same template
     * expanded a second time: `{random}` and `{uuid}` answer differently every
     * time they are asked, and a chip that claims to have replaced what was
     * inserted has to be talking about the same string.
     */
    @Synchronized
    fun candidates(
        match: SnippetMatch,
        now: Long = System.currentTimeMillis(),
        context: Companion.Context = Companion.Context(),
        blank: Boolean = false,
    ): List<SnippetCandidate> {
        val all = candidates(
            match.snippet,
            now = now,
            context = context,
            groups = match.groups,
            blank = blank,
        )
        if (all.isEmpty()) return all
        val first = all.first().copy(text = match.text, cursorOffset = match.cursorOffset)
        return listOf(first) + all.drop(1).filter { it.text != first.text }
    }

    /**
     * What tapping into a linked snippet shows: that snippet's own expansions
     * and, one level further, the snippets it links to.
     *
     * The trigger's casing is not carried down. A drill is a choice the user
     * made after the word was typed, not part of expanding it.
     */
    @Synchronized
    fun drillIn(
        childId: Long,
        now: Long = System.currentTimeMillis(),
        context: Companion.Context = Companion.Context(),
        blank: Boolean = false,
    ): List<SnippetCandidate> =
        item(childId)?.let { candidates(it, now = now, context = context, blank = blank) }.orEmpty()

    /** One expansion, by whichever of the two paths the caller is on. */
    private fun expandCandidate(
        raw: String,
        now: Long,
        context: Companion.Context,
        casing: TriggerCasing,
        groups: List<String?>,
        blank: Boolean,
    ): Companion.Expanded {
        if (groups.isEmpty() && !blank) {
            return expandWithCursor(raw, now, context, casing)
        }
        // The template path resolves capture references and template variables
        // in one scan, and must not be handed text that has already been
        // through [expand] — see [SnippetMatcher.expandTemplate].
        val expansion = SnippetMatcher.expandTemplate(raw, now, context) { index ->
            if (blank) null else groups.getOrNull(index)
        }
        return Companion.Expanded(
            text = expansion.text,
            cursorOffset = if (blank) expansion.blankCaret else expansion.caret,
        )
    }

    /** The snippet whose trigger matches [word] exactly (case-insensitive), if any. */
    fun matchTrigger(word: String): Snippet? = index().matchTrigger(word)

    /** True when any trigger reaches back past its last word, the prefix path's gate. */
    fun hasPrefixTriggers(): Boolean = index().hasPrefixTriggers

    /** True when some prefix trigger offers itself instead of expanding. */
    fun hasConfirmPrefixTriggers(): Boolean = index().hasConfirmPrefixTriggers

    /** True when [word] could finish a prefix trigger, asked without reading the field. */
    fun couldFinishPrefix(word: String): Boolean = index().prefixCandidates(word).isNotEmpty()

    /**
     * The prefix trigger [word] completes, given [before] is the text in front
     * of it. See [SnippetIndex.matchPrefix].
     */
    fun matchPrefix(word: String, before: CharSequence, confirm: Boolean = false): PrefixTrigger? =
        index().matchPrefix(word, before, confirm)

    /**
     * The pattern snippet that fits the end of [window], or null.
     *
     * See [SnippetIndex.matchPattern] for what the window has to be and what
     * [atFieldStart] promises.
     */
    fun matchPattern(
        window: CharSequence,
        atFieldStart: Boolean = false,
        now: Long = System.currentTimeMillis(),
        context: Companion.Context = Companion.Context(),
        confirm: Boolean = false,
    ): SnippetMatch? = index().matchPattern(window, atFieldStart, now, context, confirm)

    /** True when any snippet carries a pattern, so the keyboard need not look. */
    fun hasPatterns(): Boolean = index().hasPatterns

    /** True when some pattern expands on its own, the commit path's question. */
    fun hasAutoPatterns(): Boolean = index().hasAutoPatterns

    /** True when some pattern offers itself instead, the strip's question. */
    fun hasConfirmPatterns(): Boolean = index().hasConfirmPatterns

    /** True when some plain trigger offers itself instead of expanding. */
    fun hasConfirmTriggers(): Boolean = index().hasConfirmTriggers

    /** True when a word starting with [first] could begin a gated pattern. */
    fun couldStartPattern(first: Char): Boolean = index().let { it.hasUngated || it.couldStartAt(first) }

    /** Ids of the patterns the app stopped for taking too long. */
    fun stoppedPatterns(): Set<Long> = index().stopped()

    private fun index(): SnippetIndex = lookup ?: build()

    @Synchronized
    private fun build(): SnippetIndex =
        lookup ?: SnippetIndex.of(armed(), multiExpandMode).also { lookup = it }

    /**
     * The snippets whose triggers may fire: everything outside a folder that is
     * switched off.
     *
     * Filtering here rather than inside [SnippetIndex] keeps the matcher's one
     * job — decide what the text behind the cursor matches — free of a second
     * notion of whether a snippet counts. A disarmed snippet is simply not in
     * the index, so every one of the index's questions (`hasPatterns`,
     * `couldStartPattern`, the confirm-chip gates) answers correctly without
     * being told about folders at all.
     */
    private fun armed(): List<Snippet> {
        val off = folders.mapNotNullTo(HashSet()) { if (it.enabled) null else it.id }
        if (off.isEmpty()) return snippets.toList()
        return snippets.filter { it.folderId !in off }
    }

    private fun normalizeTrigger(trigger: String?): String? =
        trigger?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A trigger or alias as the index will hold it: trimmed, with every run of
     * whitespace inside it collapsed to one space.
     *
     * A trigger is matched literally against text in a field, so two spaces in
     * the saved spelling would be two spaces the user has to type — and a
     * double space is the one thing a phone keyboard turns into something else.
     * A tab or a newline in an imported trigger is unreachable for the same
     * reason. Not applied to [Snippet.triggerPattern]: there whitespace is
     * regular-expression source, and collapsing it would change what it means.
     */
    private fun normalizeSpelling(trigger: String?): String? =
        trigger?.trim()?.replace(WHITESPACE_RUN, " ")?.takeIf { it.isNotEmpty() }

    /**
     * Trims the aliases, drops the empty ones, and drops any that repeats
     * [trigger] or an earlier alias.
     *
     * A duplicate is dropped rather than rejected: an Espanso `triggers:` list
     * with the same spelling twice is a typo in somebody else's file, not a
     * reason to refuse the whole snippet. [MAX_ALIASES] bounds what one imported
     * match can add to the trigger index.
     */
    private fun normalizeAliases(aliases: List<String>, trigger: String?): List<String> {
        if (aliases.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        trigger?.let { seen.add(it.lowercase(Locale.ROOT)) }
        val out = ArrayList<String>(aliases.size)
        for (alias in aliases) {
            val clean = normalizeSpelling(alias) ?: continue
            if (!seen.add(clean.lowercase(Locale.ROOT))) continue
            out.add(clean)
            if (out.size >= MAX_ALIASES) break
        }
        return out
    }

    /**
     * Drops the blank alternates, and any that repeats [text] or an earlier
     * one. [MAX_ALTERNATES] bounds what one imported match can put on the strip.
     */
    private fun normalizeAlternates(alternates: List<String>, text: String): List<String> {
        if (alternates.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        seen.add(text)
        val out = ArrayList<String>(alternates.size)
        for (alternate in alternates) {
            if (alternate.isBlank()) continue
            if (!seen.add(alternate)) continue
            out.add(alternate)
            if (out.size >= MAX_ALTERNATES) break
        }
        return out
    }

    /**
     * Drops the placeholder id, [self], and repeats.
     *
     * An id no snippet currently has is *kept*: an import adds its snippets one
     * at a time, and a link forward would be lost if it had to name something
     * that already existed. [childrenOf] skips it for as long as it names
     * nothing.
     */
    private fun normalizeChildren(children: List<Long>, self: Long): List<Long> {
        if (children.isEmpty()) return emptyList()
        val seen = HashSet<Long>()
        val out = ArrayList<Long>(children.size)
        for (id in children) {
            if (id == 0L || id == self) continue
            if (!seen.add(id)) continue
            out.add(id)
            if (out.size >= MAX_CHILDREN) break
        }
        return out
    }

    /** Trims the tags, shortens the long ones, and drops repeats ignoring case. */
    private fun normalizeTags(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<String>(tags.size)
        for (tag in tags) {
            val clean = tag.trim().take(MAX_TAG_LENGTH)
            if (clean.isEmpty()) continue
            if (!seen.add(clean.lowercase(Locale.ROOT))) continue
            out.add(clean)
            if (out.size >= MAX_TAGS) break
        }
        return out
    }

    @Synchronized
    fun save() {
        val file = storageFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Snapshot(snippets.toList(), folders.toList())))
        }
    }

    /** Re-reads the backing file (settings app and IME share the store). */
    @Synchronized
    fun reload() {
        snippets.clear()
        folders.clear()
        lookup = null
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            folders.addAll(snapshot.folders)
            // Read after the folders, so a file hand-edited to point a snippet
            // at a folder it never declared lands ungrouped rather than at an
            // id a later addFolder would hand out to something else.
            snippets.addAll(snapshot.snippets.map { it.copy(folderId = knownFolder(it.folderId)) })
        }
        nextId = (snippets.maxOfOrNull { it.id } ?: 0) + 1
        nextFolderId = (folders.maxOfOrNull { it.id } ?: 0) + 1
    }

    companion object {

        /** Any run of whitespace, which a saved trigger spelling holds as one space. */
        private val WHITESPACE_RUN = Regex("\\s+")

        /**
         * Marker for where the cursor should land after insertion. [expand]
         * leaves it in place; [expandWithCursor] strips it and reports the
         * offset. Chosen from a private-use code point so it can never collide
         * with snippet text.
         */
        const val CURSOR_MARKER = "\uE000"

        /** Expansion inputs the IME knows and the settings preview doesn't. */
        data class Context(
            /** Most recent clipboard entry. */
            val clipboard: String? = null,
            /** Label of the app being typed into, e.g. "Messages". */
            val appName: String? = null,
            /** Package name of the app being typed into. */
            val packageName: String? = null,
            /** Text currently selected in the field. */
            val selection: String? = null,
        )

        /** Expanded text plus where the cursor should end up inside it. */
        data class Expanded(val text: String, val cursorOffset: Int)

        /**
         * `{date}`, `{date:pattern}`, `{date+3600}` and `{date-86400:pattern}`.
         *
         * The offset sits before the colon so it can never be mistaken for part
         * of a `SimpleDateFormat` pattern, which may contain almost anything.
         * It is in seconds, which is the unit Espanso's `date` extension uses.
         */
        private val CUSTOM_DATE = Regex("""\{date([+-]\d{1,9})?(?::([^}\n]{1,40}))?\}""")

        /** `{random:one|two|three}`, one alternative picked per insertion. */
        private val RANDOM = Regex("""\{random:([^}\n]{1,400})\}""")

        /** Default pattern for a bare `{date}` or `{date+n}`. */
        private const val DEFAULT_DATE = "d MMM yyyy"

        /**
         * Most aliases one snippet may carry.
         *
         * Generous rather than tight: a snippet is meant to gather every way a
         * person spells a thing, so the cap is here to bound what one hostile
         * imported pack can put in the trigger index, not to ration what
         * somebody types.
         */
        const val MAX_ALIASES = 256

        /** Most alternates one snippet may carry, beyond its default. */
        const val MAX_ALTERNATES = 64

        /** Most snippets one snippet may link to. */
        const val MAX_CHILDREN = 64

        /** Most tags one snippet may carry. */
        const val MAX_TAGS = 32

        /** Longest tag, in characters. */
        const val MAX_TAG_LENGTH = 40

        /** Expands template variables, leaving [CURSOR_MARKER] in place. */
        fun expand(
            text: String,
            now: Long = System.currentTimeMillis(),
            clipboard: String? = null,
            context: Context = Context(),
        ): String {
            val ctx = if (clipboard != null) context.copy(clipboard = clipboard) else context

            // Random first, so an alternative may itself contain a date or any
            // other token. It is the only variable whose value is more template.
            var out = RANDOM.replace(text) { pickRandom(it.groupValues[1]) }

            // Then {date...}, so a literal pattern can't be eaten by {date}.
            out = CUSTOM_DATE.replace(out) { match ->
                val shift = match.groupValues[1].toLongOrNull() ?: 0L
                val pattern = match.groupValues[2].ifEmpty { DEFAULT_DATE }
                format(pattern, now + shift * 1000L)
            }
            val fmt: (String) -> String = { format(it, now) }
            for (variable in SnippetVariable.entries) {
                if (!out.contains(variable.token)) continue
                out = out.replace(variable.token, variable.value(fmt, ctx, now))
            }
            return out
        }

        private fun format(pattern: String, at: Long): String =
            runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at)) }
                .getOrDefault("")

        /**
         * One of the pipe-separated alternatives in [body], or the whole of it
         * when there is only one.
         *
         * `\|` is a literal pipe, so an alternative may contain one.
         */
        private fun pickRandom(body: String): String {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && body.getOrNull(i + 1) == '|' -> {
                        current.append('|')
                        i += 2
                    }
                    c == '|' -> {
                        parts.add(current.toString())
                        current.setLength(0)
                        i++
                    }
                    else -> {
                        current.append(c)
                        i++
                    }
                }
            }
            parts.add(current.toString())
            return parts.random()
        }

        /** Expands, then strips the cursor marker and reports its offset. */
        fun expandWithCursor(
            text: String,
            now: Long = System.currentTimeMillis(),
            context: Context = Context(),
            casing: TriggerCasing = TriggerCasing.NONE,
        ): Expanded {
            // Re-cased before the marker is located, not after: a case mapping
            // may change a string's length (ß uppercases to SS), and the marker
            // is a private-use code point that no mapping touches.
            val expanded = casing.apply(expand(text, now, context = context))
            val index = expanded.indexOf(CURSOR_MARKER)
            if (index < 0) return Expanded(expanded, expanded.length)
            return Expanded(expanded.replace(CURSOR_MARKER, ""), index)
        }

        /**
         * How [snippet] should be re-cased given that its trigger was actually
         * typed as [typed].
         *
         * Matches Espanso's `propagate_case` rule: an all-caps trigger gives an
         * all-caps expansion, a leading capital gives the snippet's own
         * [Snippet.uppercaseStyle], and anything else is left alone. Only the
         * letters count, so a trigger's punctuation is ignored and `:Omw` reads
         * as capitalized.
         */
        fun casingFor(snippet: Snippet, typed: String): TriggerCasing {
            if (!snippet.propagateCase) return TriggerCasing.NONE
            val letters = typed.filter(Char::isLetter)
            if (letters.isEmpty()) return TriggerCasing.NONE
            if (letters.length > 1 && letters.all(Char::isUpperCase)) return TriggerCasing.UPPER
            if (!letters[0].isUpperCase()) return TriggerCasing.NONE
            return when (snippet.uppercaseStyle) {
                UppercaseStyle.UPPERCASE -> TriggerCasing.UPPER
                UppercaseStyle.CAPITALIZE_WORDS -> TriggerCasing.CAPITALIZE_WORDS
                UppercaseStyle.CAPITALIZE -> TriggerCasing.CAPITALIZE
            }
        }
    }
}

/** What [SnippetStore.casingFor] decided a typed trigger asks for. */
enum class TriggerCasing {
    NONE,
    UPPER,
    CAPITALIZE,
    CAPITALIZE_WORDS;

    fun apply(text: String): String {
        val locale = Locale.getDefault()
        return when (this) {
            NONE -> text
            UPPER -> text.uppercase(locale)
            CAPITALIZE -> capitalize(text, locale, everyWord = false)
            CAPITALIZE_WORDS -> capitalize(text, locale, everyWord = true)
        }
    }

    /**
     * Upper-cases the first letter, and every word's first letter when
     * [everyWord]. The rest of the text is left exactly as the snippet wrote it:
     * a snippet that deliberately contains an acronym keeps it.
     */
    private fun capitalize(text: String, locale: Locale, everyWord: Boolean): String {
        val out = StringBuilder(text.length)
        var atStart = true
        for (i in text.indices) {
            val c = text[i]
            if (atStart && c.isLetter()) {
                out.append(c.uppercase(locale))
                atStart = false
                if (!everyWord) {
                    // Indexed against the source, not the builder: an uppercase
                    // mapping may be longer than what it replaced (ß gives SS),
                    // so the builder's length is not a position in [text].
                    out.append(text, i + 1, text.length)
                    return out.toString()
                }
                continue
            }
            out.append(c)
            if (everyWord && !c.isLetterOrDigit() && c != '\'') atStart = true
        }
        return out.toString()
    }
}

/**
 * The template variables a snippet may contain. Kept as an enum so the
 * expander and the settings screen's reference table can never drift apart.
 *
 * Two variables take an argument and so are handled separately in
 * [SnippetStore.expand] rather than listed here: `{date:pattern}` with any
 * SimpleDateFormat pattern and an optional seconds offset
 * (`{date+86400:dd/MM/yy}` is tomorrow), and `{random:one|two|three}`, which
 * picks one alternative per insertion.
 */
enum class SnippetVariable(
    val token: String,
    /** What the settings screen shows next to the token. */
    @StringRes val descriptionRes: Int,
) {
    DATE("{date}", R.string.core_content_snippet_var_date_info),
    TIME("{time}", R.string.core_content_snippet_var_time_info),
    TIME12("{time12}", R.string.core_content_snippet_var_time12_info),
    DATETIME("{datetime}", R.string.core_content_snippet_var_datetime_info),
    ISODATE("{isodate}", R.string.core_content_snippet_var_isodate_info),
    ISOTIME("{isotime}", R.string.core_content_snippet_var_isotime_info),
    WEEKDAY("{weekday}", R.string.core_content_snippet_var_weekday_info),
    DAY("{day}", R.string.core_content_snippet_var_day_info),
    MONTH("{month}", R.string.core_content_snippet_var_month_info),
    YEAR("{year}", R.string.core_content_snippet_var_year_info),
    TIMEZONE("{timezone}", R.string.core_content_snippet_var_timezone_info),
    TIMESTAMP("{timestamp}", R.string.core_content_snippet_var_timestamp_info),
    CLIP("{clip}", R.string.core_content_snippet_var_clip_info),
    SELECTION("{selection}", R.string.core_content_snippet_var_selection_info),
    APP("{app}", R.string.core_content_snippet_var_app_info),
    PACKAGE("{package}", R.string.core_content_snippet_var_package_info),
    UUID("{uuid}", R.string.core_content_snippet_var_uuid_info),
    CURSOR("{cursor}", R.string.core_content_snippet_var_cursor_info);

    internal fun value(
        fmt: (String) -> String,
        context: SnippetStore.Companion.Context,
        now: Long,
    ): String = when (this) {
        DATE -> fmt("d MMM yyyy")
        TIME -> fmt("HH:mm")
        TIME12 -> fmt("h:mm a")
        DATETIME -> fmt("d MMM yyyy HH:mm")
        ISODATE -> fmt("yyyy-MM-dd")
        ISOTIME -> fmt("yyyy-MM-dd'T'HH:mm:ssXXX")
        WEEKDAY -> fmt("EEEE")
        DAY -> fmt("d")
        MONTH -> fmt("MMMM")
        YEAR -> fmt("yyyy")
        TIMEZONE -> fmt("zzzz")
        TIMESTAMP -> (now / 1000).toString()
        CLIP -> context.clipboard.orEmpty()
        SELECTION -> context.selection.orEmpty()
        APP -> context.appName.orEmpty()
        PACKAGE -> context.packageName.orEmpty()
        UUID -> java.util.UUID.randomUUID().toString()
        CURSOR -> SnippetStore.CURSOR_MARKER
    }
}
