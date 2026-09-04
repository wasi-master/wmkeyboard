package com.wasimaster.wmkeyboard.core.prediction

import android.content.Context
import android.provider.UserDictionary

/**
 * Android's system personal dictionary ([UserDictionary]), both ways.
 *
 * Reading: [words] hands the whole list to the engine as a known-word source
 * and [shortcuts] its shortcut column. Writing: [add] mirrors words this
 * keyboard learns into it, so other keyboards and the platform spell checker
 * recognize them too. Writing is opt-in — the on-device [UserLexicon] already
 * covers this keyboard on its own — while reading is on by default: a word
 * the user put in the platform dictionary is a word they expect every
 * keyboard to know (#45).
 *
 * The provider only lets the *current* IME (or a spell checker / system app)
 * write, which is exactly the case while the user is typing on this keyboard,
 * so writes normally succeed. Every call is still wrapped defensively: a
 * SecurityException on some OEM build must never take the keyboard down.
 *
 * [UserDictionary.Words.addWord] does not deduplicate, so a session-lived set
 * of already-added words — seeded once from what the dictionary already holds —
 * keeps repeated typing from piling up duplicate rows.
 */
object SystemUserDictionary {

    /** Middle-of-the-road frequency; user-typed words aren't ranking-critical here. */
    private const val FREQUENCY = 250

    private val added = HashSet<String>()
    private var seeded = false

    /**
     * Adds [word] to the system dictionary if it isn't already there. Safe to
     * call from any thread, but does content-provider I/O, so callers should
     * run it off the main thread. No-ops on blank or single-character words.
     */
    fun add(context: Context, word: String) {
        val cleaned = word.trim()
        if (cleaned.length < 2) return
        val key = cleaned.lowercase()
        synchronized(this) {
            seed(context)
            if (!added.add(key)) return
        }
        runCatching {
            // locale = null makes the word valid for every locale, which suits a
            // multi-language keyboard where the same field mixes scripts.
            UserDictionary.Words.addWord(
                context,
                cleaned,
                FREQUENCY,
                null,
                null,
            )
        }
    }

    /**
     * Every word in Android's personal dictionary as a [WordSource], so the
     * keyboard treats them as known: they complete, they are never
     * autocorrected away, and gliding one does not put an "add to
     * dictionary?" chip on the strip (#45). Reads the whole table each call
     * — the list is small and hand-curated — and never throws: an OEM that
     * hides the provider, or a locked boot, yields the empty source.
     *
     * Locale is ignored on purpose. The platform UI files most entries under
     * the device locale even when the word is a name or an acronym that is
     * valid everywhere, and a multi-language keyboard mixes scripts in one
     * field; a word the user went out of their way to add is a word in any
     * language they type.
     */
    fun words(context: Context): WordSource {
        val out = ArrayList<String>()
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.let { out.add(it) }
                    }
                }
            }
        }
        return index(out)
    }

    /**
     * Builds the source [words] returns from raw dictionary rows. Keys are
     * normalised the way the personal lexicon keys its own words, so "AOSP"
     * answers `contains("aosp")` like every other known word; the stored
     * frequency is dropped in favour of a flat 1, because the engine weights
     * this source like the personal lexicon, where 1 means "a word the user
     * typed once" — a 250 (the platform UI's default) would outrank real
     * vocabulary by orders of magnitude. Multi-word entries index as their
     * parts: "on my way" is not a word anyone types as one token, but each
     * part is. Pure, so it is unit-testable off the device.
     */
    fun index(words: Iterable<String>): WordSource {
        val keys = LinkedHashSet<String>()
        for (raw in words) {
            for (part in raw.split(WHITESPACE)) {
                val key = WordKey.of(part.trim())
                if (key.length >= 2) keys.add(key)
            }
        }
        if (keys.isEmpty()) return PackedTrie.EMPTY
        return PackedTrie.of(keys.map { it to 1 })
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * The shortcut → expansion entries in Android's personal dictionary (the
     * SHORTCUT column the platform "Personal dictionary" UI fills in, e.g.
     * "omw" → "on my way"). Lowercased shortcuts. Reads the whole table each
     * call — cheap, small, and the caller caches it — and never throws: an OEM
     * that hides the provider just yields an empty map. Powers
     * [com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings.expandUserDictShortcuts].
     */
    fun shortcuts(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT),
                null,
                null,
                null,
            )?.use { cursor ->
                val wordCol = cursor.getColumnIndex(UserDictionary.Words.WORD)
                val shortcutCol = cursor.getColumnIndex(UserDictionary.Words.SHORTCUT)
                if (wordCol >= 0 && shortcutCol >= 0) {
                    while (cursor.moveToNext()) {
                        val shortcut = cursor.getString(shortcutCol)?.trim()
                        val word = cursor.getString(wordCol)?.trim()
                        if (!shortcut.isNullOrEmpty() && !word.isNullOrEmpty()) {
                            out[shortcut.lowercase()] = word
                        }
                    }
                }
            }
        }
        return out
    }

    /** Loads the words already in the dictionary once, so we don't re-add them. */
    private fun seed(context: Context) {
        if (seeded) return
        seeded = true
        runCatching {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null,
            )?.use { cursor ->
                val col = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (col >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(col)?.let { added.add(it.lowercase()) }
                    }
                }
            }
        }
    }
}
