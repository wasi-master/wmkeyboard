package com.wasimaster.wmkeyboard.core.settings

import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Full-config backup: one file that can bundle several independent parts of
 * the app — settings, custom themes, the learned dictionary, clipboard
 * history, snippets, sticker packs, icon packs, imported word lists and the
 * emoji history — each an opt-in [Section].
 *
 * This is the umbrella container. The pieces keep their own on-disk shapes:
 * a section's value is just the JSON that store already writes (the settings
 * map from [SettingsBackup.encodeSettings], a [core.prediction.UserLexicon]
 * snapshot, a clipboard/snippets snapshot, the custom-themes array), embedded
 * verbatim. That keeps this codec ignorant of each store's internals — a new
 * field in any of them rides along without a change here.
 *
 * Distinct from [SettingsBackup]'s standalone `wmsettings.json`, which stays
 * as-is; the import screen accepts both.
 */
object ConfigBackup {

    const val FORMAT = "wmkeyboard-config"
    const val VERSION = 1
    const val FILE_EXTENSION = "wmconfig.json"
    const val MIME_TYPE = "application/json"

    /**
     * The same bundle, encrypted under a passphrase by
     * [com.wasimaster.wmkeyboard.core.settings.BackupCrypto].
     *
     * A separate extension rather than a flag inside the JSON, because the
     * whole file is ciphertext: there is no JSON left to put a flag in. The
     * MIME type is deliberately not `application/json` — a provider handed a
     * `.enc` name with a JSON type will helpfully append `.json` to it.
     */
    const val ENCRYPTED_FILE_EXTENSION = "wmconfig.enc"
    const val ENCRYPTED_MIME_TYPE = "application/octet-stream"

    /** A selectable part of the bundle. [id] is its JSON key, stable on disk. */
    enum class Section(val id: String) {
        SETTINGS("settings"),
        THEMES("themes"),
        DICTIONARY("dictionary"),
        CLIPBOARD("clipboard"),
        SNIPPETS("snippets"),
        STICKERS("stickers"),
        ICONS("icons"),
        WORDLISTS("wordlists"),

        /**
         * Emoji history: recents, use counts, favourites and the skin tone
         * picked per emoji. Small, personal, and the one part of the keyboard's
         * memory that cannot be rebuilt by re-downloading anything.
         */
        EMOJI("emoji"),

        /**
         * The list of addon repositories the user added — not the addons
         * themselves, which are already covered by the sections above (an
         * installed theme is in THEMES, an installed pack in ICONS). This is
         * the bookmark list, which is otherwise the one thing a restore would
         * silently lose.
         */
        ADDONS("addons"),

        /**
         * The typing counts behind the Statistics screen. Like [EMOJI],
         * nothing here can be rebuilt: a new phone starts at zero and the
         * old totals are gone unless they travelled in a bundle.
         */
        STATISTICS("statistics"),

        /**
         * Vocabulary: the packs the catalogue cannot download again (imported
         * files and the user's own lists) and the learning record — which
         * words are learnt, which are due, and when. Appended last, after
         * STATISTICS: the encoder writes sections in declaration order and
         * an older build reads bundles positionally in its tests.
         */
        VOCAB("vocab"),

        /**
         * The swipe style: where this hand lands when it glides, the readings
         * the user corrected, and the shape they draw each word in. Everything
         * the "learn my swipe style" switch governs, which is the same set its
         * Forget deletes.
         *
         * A section of its own rather than a ride inside [DICTIONARY], the way
         * the tap model rides: this one is a few hundred bytes a word across up
         * to a thousand words, so it is worth its own switch. Off by default
         * for [DICTIONARY]'s reason as well, since the shapes are filed under
         * the words they were drawn for.
         *
         * Appended after [VOCAB] for the reason given there: sections encode in
         * declaration order.
         */
        SWIPE("swipe"),

        /**
         * The Notes tool (#560): every note, its tags, and what is in the
         * trash. Like [EMOJI], nothing here can be rebuilt. Locked notes leave
         * only when the bundle carries secrets; see `NotesBackup`. Appended
         * after [SWIPE] for the reason given on [VOCAB].
         */
        NOTES("notes"),

        /**
         * The Clock tool: alarms, timer presets, countdowns and its settings.
         * Not what is running and not the history; see `ClockBackup`.
         * Appended after [NOTES] for the reason given on [VOCAB].
         */
        CLOCK("clock"),

        /**
         * The kaomoji library: every face, its tags, the tag order, the stars
         * and how often each was used. Only once the library was changed or
         * used; an untouched one is the built-in catalog and has no file.
         * Appended after [CLOCK] for the reason given on [VOCAB].
         */
        KAOMOJI("kaomoji"),

        /**
         * What the AI remembers about the user: the facts on Settings › AI ›
         * Memory. Off by default everywhere, and never in sync (see
         * [syncable]): the list is gathered from the user's chats, and it
         * leaves the phone only in a backup the user told to carry it.
         * Appended after [KAOMOJI] for the reason given on [VOCAB].
         */
        AI_MEMORY("ai_memory"),

        /**
         * The To-do tool (#920): every task and step, and the task-app
         * deletes still to make. Typed by hand, so nothing rebuilds it. The
         * task-app sync's own record stays on the phone. Appended after
         * [AI_MEMORY] for the reason given on [VOCAB].
         */
        TODO("todo"),
        ;

        /**
         * Whether sync may carry this section between devices. Everything but
         * [AI_MEMORY], which goes into a backup or nowhere.
         */
        val syncable: Boolean get() = this != AI_MEMORY
    }

    private val json = Json { prettyPrint = true }
    private val parser = Json { ignoreUnknownKeys = true }

    private val byId = Section.entries.associateBy { it.id }

    fun encode(
        appVersion: Int,
        appVersionName: String,
        sections: Map<Section, JsonElement>,
    ): String = json.encodeToString(JsonObject.serializer(), root(appVersion, appVersionName, sections))

    /**
     * [encode], written straight to [out]. A bundle carrying sticker packs
     * runs to tens of megabytes; as a string it is held once in the builder,
     * again as the finished string, and a third time as the bytes written out,
     * which was enough to run a 256 MB heap out of memory (#693).
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun encodeTo(
        out: OutputStream,
        appVersion: Int,
        appVersionName: String,
        sections: Map<Section, JsonElement>,
    ) {
        json.encodeToStream(JsonObject.serializer(), root(appVersion, appVersionName, sections), out)
    }

    /** The two fields [isComplete] reads; everything else is skipped, not built. */
    @Serializable
    private data class BundleHead(val format: String = "", val version: Int = VERSION)

    /**
     * Whether [input] is a bundle this build can read, whole to its last byte,
     * without building the bundle: a parsed copy costs far more heap than the
     * file (#1080). A truncated file fails on the missing end of the document.
     * The caller closes the stream.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun isComplete(input: InputStream): Boolean = runCatching {
        val head = parser.decodeFromStream(BundleHead.serializer(), input)
        head.format == FORMAT && head.version <= VERSION
    }.getOrElse { failure ->
        // Not this file's fault, and it should be reported as what it is.
        if (failure is OutOfMemoryError) throw failure
        false
    }

    private fun root(
        appVersion: Int,
        appVersionName: String,
        sections: Map<Section, JsonElement>,
    ): JsonObject =
        buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("appVersion", JsonPrimitive(appVersion))
            put("appVersionName", JsonPrimitive(appVersionName))
            put(
                "sections",
                buildJsonObject {
                    // Stable order, independent of the caller's set iteration.
                    for (section in Section.entries) {
                        sections[section]?.let { put(section.id, it) }
                    }
                },
            )
        }

    /**
     * A list section's decoded contents, or null when the decode plainly failed.
     *
     * Codecs in this app answer an empty list for every kind of failure alike —
     * malformed JSON, a field a newer app added and made required, a file that
     * arrived truncated. An empty list is also a legitimate answer, so the two
     * can only be told apart by looking at what went in: nothing decoded out of
     * something is a failure, and it must not be mistaken for a section that
     * says "the user has none of these".
     *
     * It matters because these sections restore by replacing. Read the failure
     * as an empty list and the restore reports success while deleting
     * everything the section was supposed to be carrying.
     */
    fun <T> decodedList(decoded: List<T>?, encodedSize: Int): List<T>? =
        decoded?.takeIf { it.isNotEmpty() || encodedSize == 0 }

    /** A decoded bundle: which sections it carried and their raw payloads. */
    data class Parsed(
        val appVersion: Int,
        val sections: Map<Section, JsonElement>,
    ) {
        fun has(section: Section): Boolean = section in sections
    }

    /**
     * Parses [text], or null when it is not a full-config bundle this app can
     * read.
     *
     * A bundle whose [VERSION] is newer than ours is refused rather than
     * half-applied. Most sections restore by replacing what is there, and only
     * the settings section can roll itself back, so there is no safe way to
     * discover halfway through that the file was not meant for us.
     */
    fun decode(text: String): Parsed? {
        val root = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: VERSION
        if (version > VERSION) return null
        val sectionsObj = runCatching { root.getValue("sections").jsonObject }.getOrNull() ?: return null
        val sections = LinkedHashMap<Section, JsonElement>()
        for ((id, element) in sectionsObj) {
            byId[id]?.let { sections[it] = element }
        }
        return Parsed(
            appVersion = (root["appVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
            sections = sections,
        )
    }
}
