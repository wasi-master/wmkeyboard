package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    }

    private val json = Json { prettyPrint = true }
    private val parser = Json { ignoreUnknownKeys = true }

    private val byId = Section.entries.associateBy { it.id }

    fun encode(
        appVersion: Int,
        appVersionName: String,
        sections: Map<Section, JsonElement>,
    ): String {
        val root = buildJsonObject {
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
        return json.encodeToString(JsonObject.serializer(), root)
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
