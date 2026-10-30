package com.wasimaster.wmkeyboard.spell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.ContactsContract
import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.grammar.GrammarChecker
import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.prediction.ContactEmails
import com.wasimaster.wmkeyboard.core.prediction.ContactNames
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.prediction.topWords
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.BreakIterator
import java.util.Locale

/**
 * WM Keyboard as Android's spell checker: the red underline in other apps,
 * and the fixes a tap on it offers.
 *
 * One service, two halves. Spelling comes from the same word lists the strip
 * reads (#511), in every language with a list on the phone: the downloaded
 * list, English's bundled one, and the user's learned words on top. Grammar
 * comes from Harper, for English only and only where the build carries it;
 * its own spelling lints are dropped, because the word lists are the spelling
 * authority and Harper's are case-sensitive ("github" against "GitHub").
 *
 * There used to be two services, one per half, both listed in Android's
 * settings under the same name. The merged one keeps the Harper component's
 * class name: Android remembers the chosen checker by component, and an app
 * cannot change that choice, so renaming it would silently switch spell
 * checking off for everyone who had picked it.
 *
 * Runs in the app's own process, independent of the keyboard, so it works in
 * every app even when WM Keyboard is not the active input method. Nothing runs
 * until the user picks it under the system's spell checker setting.
 */
/**
 * Undoes the old builds' switch-off. A build made without Harper (F-Droid)
 * used to disable the spell checker component, and a component's enabled
 * state survives an update; the merged checker has word lists to answer with
 * in every build, so it belongs in Android's settings everywhere.
 */
object SpellCheckerComponent {

    /** Writes only when the state is wrong, so a process start pays one read. */
    fun restore(context: Context) {
        val component = ComponentName(context, HarperSpellCheckerService::class.java)
        val pm = context.packageManager
        runCatching {
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}

class HarperSpellCheckerService : SpellCheckerService() {

    override fun createSession(): Session = WMSpellSession(applicationContext)

    private class WMSpellSession(private val context: Context) : Session() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Null when this session's language has no word list to check with. */
        private var engine: SuggestionEngine? = null

        /**
         * The scripts this session's list is written in. The session's language
         * can be a different one from the text: a language pinned on Android's
         * screen, an old build, or a keyboard that names none. A Russian word in
         * an English session then has nothing to be checked against, and red
         * under every word is the wrong answer to that.
         */
        private var listScripts: Set<Character.UnicodeScript> = emptySet()

        /** Whether Harper's grammar half answers for this session at all. */
        private var grammar = false

        /**
         * Mirrors the user's grammar dialect. Read through a collected flow
         * rather than per request: the framework calls in on a binder thread
         * and expects a prompt answer.
         */
        @Volatile
        private var dialectOrdinal: Int = GrammarDialect.AMERICAN.ordinal

        /**
         * Still flag problems (the underline stays) but hand the framework no
         * replacements and the "mark but don't show suggestions UI" flag.
         * Only honoured on Android 12+ (API 31), where
         * [SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS] exists.
         */
        @Volatile
        private var noSuggestionsUi = false

        /** Whether Harper has been warmed at least once this session. */
        @Volatile
        private var warmed = false

        /**
         * Contact indices, loaded in this process so names count as spelled
         * right even when the keyboard is not running. Empty until the setting
         * is on and the Contacts permission granted; memory only.
         */
        @Volatile
        private var contactNames: ContactNames = ContactNames.EMPTY

        @Volatile
        private var contactEmails: ContactEmails = ContactEmails.EMPTY

        /** Latches the setting so contacts load once, not per settings change. */
        @Volatile
        private var contactsEnabled = false

        override fun onCreate() {
            val langId = languageOf(locale)
            val list = wordListFor(context, langId)
            engine = list?.let { engineFor(context, langId, it) }
            listScripts = list?.let { scriptsOf(it.topWords(SCRIPT_SAMPLE_WORDS)) }.orEmpty()
            grammar = langId == "en"
            val repository = SettingsRepository(context)
            scope.launch {
                repository.settings.collect { settings ->
                    val ordinal = settings.grammarDialect.ordinal
                    val dialectChanged = ordinal != dialectOrdinal
                    dialectOrdinal = ordinal
                    noSuggestionsUi = settings.spellCheckerNoSuggestions
                    // Warm on the first value too: building Harper's rule set
                    // takes ~100 ms, which would otherwise land on the first check.
                    if (grammar && (dialectChanged || !warmed) && GrammarChecker.available) {
                        warmed = true
                        GrammarChecker.warmUp(ordinal)
                    }
                    if (settings.suggestionSources.contacts) {
                        if (!contactsEnabled) {
                            contactsEnabled = true
                            loadContacts()
                        }
                    } else {
                        contactsEnabled = false
                        contactNames = ContactNames.EMPTY
                        contactEmails = ContactEmails.EMPTY
                    }
                }
            }
        }

        override fun onClose() {
            scope.cancel()
        }

        /**
         * Per-word checking: the framework hands over one word with no
         * sentence around it, so only the word lists have anything to say.
         */
        override fun onGetSuggestions(textInfo: TextInfo, suggestionsLimit: Int): SuggestionsInfo =
            checkWord(textInfo.text.orEmpty(), suggestionsLimit).withIds(textInfo)

        /**
         * Sentence-level checking: every word through the word lists, then
         * Harper's grammar lints for English on top. A lint that overlaps a
         * misspelt word is dropped, so one span never carries two menus.
         */
        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<out TextInfo>,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo> = Array(textInfos.size) { i ->
            val info = textInfos[i]
            val text = info.text.orEmpty()
            val problems = ArrayList<Problem>()
            val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(text) }
            var start = words.first()
            var end = words.next()
            while (end != BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (word.any(Char::isLetter)) {
                    val result = checkWord(word, suggestionsLimit)
                    if (result.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0) {
                        problems += Problem(start, end, result)
                    }
                }
                start = end
                end = words.next()
            }
            if (grammar && text.isNotBlank() && GrammarChecker.available) {
                for (lint in lint(text)) {
                    if (lint.end <= lint.start || lint.isSpelling()) continue
                    if (problems.any { it.start < lint.end && lint.start < it.end }) continue
                    val span = text.substring(lint.start, lint.end)
                    if (isKnownContact(span)) continue
                    problems += Problem(lint.start, lint.end, grammarInfo(span, lint, suggestionsLimit))
                }
            }
            problems.sortBy { it.start }
            SentenceSuggestionsInfo(
                problems.map { it.info.withIds(info) }.toTypedArray(),
                problems.map { it.start }.toIntArray(),
                problems.map { it.end - it.start }.toIntArray(),
            )
        }

        private class Problem(val start: Int, val end: Int, val info: SuggestionsInfo)

        private fun checkWord(raw: String, limit: Int): SuggestionsInfo {
            val word = raw.trim().trim('\'', '’')
            val engine = engine
            if (engine == null || word.isEmpty() || !word.any(Char::isLetter) || word.any(Char::isDigit)) {
                return SuggestionsInfo(0, EMPTY)
            }
            if (!isInScripts(word, listScripts)) return SuggestionsInfo(0, EMPTY)
            // A word that is one of the user's contacts is spelled right by
            // definition: never flag a name the phone knows.
            if (isKnownContact(word)) return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY)
            val ranked = synchronized(engine) {
                if (engine.isKnownWord(word)) return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY)
                val fix = engine.shouldAutocorrect(word)
                // A contact whose name the typo is close to leads the menu:
                // fixing a mistyped name to the right contact is the common case.
                (contactCorrections(word, limit) + listOfNotNull(fix) +
                    engine.suggest(word.lowercase(), previousWord = null, limit = limit + 2))
                    .filterNot { it.equals(word, ignoreCase = true) }
                    .distinct()
                    .take(limit.coerceAtLeast(1))
            }
            return problemInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, ranked)
        }

        private fun lint(text: String): List<GrammarLint> =
            // The framework's call is synchronous and already off the main
            // thread; GrammarChecker hops to its own single native thread.
            runBlocking { GrammarChecker.check(text, dialectOrdinal) }

        private fun grammarInfo(span: String, lint: GrammarLint, limit: Int): SuggestionsInfo {
            val replacements = lint.suggestions.mapNotNull { spellReplacementFor(span, it) }
                .filter { it.isNotBlank() && it != span }
                .distinct()
                .take(limit.coerceAtLeast(1))
            // The grammar mark exists from Android 12; below it the only mark
            // there is a typo's.
            val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR
            } else {
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            }
            return problemInfo(kind, replacements)
        }

        /**
         * A flagged span's answer, in "underline only" mode stripped of its
         * replacements and carrying the flag that asks the host to skip the
         * correction popup.
         */
        private fun problemInfo(kind: Int, replacements: List<String>): SuggestionsInfo {
            if (noSuggestionsUi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SuggestionsInfo(kind or SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS, EMPTY)
            }
            var attrs = kind
            if (replacements.isNotEmpty()) attrs = attrs or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(attrs, replacements.toTypedArray())
        }

        /**
         * Reads contact names and email addresses into the in-memory indices.
         * No-op without the permission: the settings app requests it, and the
         * flow reloads once the setting is turned on after a grant.
         */
        private fun loadContacts() {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val names = ArrayList<String>()
                        context.contentResolver.query(
                            ContactsContract.Contacts.CONTENT_URI,
                            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                            null, null, null,
                        )?.use { cursor ->
                            val column = cursor.getColumnIndexOrThrow(
                                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                            )
                            while (cursor.moveToNext()) {
                                cursor.getString(column)?.let { names.add(it) }
                            }
                        }
                        val emails = ArrayList<String>()
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                            null, null, null,
                        )?.use { cursor ->
                            val column = cursor.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Email.ADDRESS
                            )
                            while (cursor.moveToNext()) {
                                cursor.getString(column)?.let { emails.add(it) }
                            }
                        }
                        ContactNames.fromNames(names) to ContactEmails.fromAddresses(emails)
                    }.getOrNull()
                }
                if (loaded != null) {
                    contactNames = loaded.first
                    contactEmails = loaded.second
                }
            }
        }

        /** True when [span] is a contact name word or a known contact address. */
        private fun isKnownContact(span: String): Boolean {
            val s = span.trim().lowercase()
            if (s.isEmpty()) return false
            return if ('@' in s) contactEmails.contains(s) else contactNames.contains(s)
        }

        /**
         * Contact names close to [span] (a prefix of one, or a single typo
         * away), kept in their source capitalization for the correction menu.
         */
        private fun contactCorrections(span: String, limit: Int): List<String> {
            if (contactNames.isEmpty || limit <= 0) return emptyList()
            val s = span.lowercase()
            if (s.length < 2) return emptyList()
            val out = LinkedHashSet<String>()
            for (suggestion in contactNames.complete(s, limit)) {
                if (!suggestion.word.equals(span, ignoreCase = true)) out.add(suggestion.word)
            }
            if (out.size < limit) {
                for (variant in editsOne(s)) {
                    val display = contactNames.displayOf(variant) ?: continue
                    if (!display.equals(span, ignoreCase = true)) out.add(display)
                    if (out.size >= limit) break
                }
            }
            return out.take(limit).toList()
        }

        private fun SuggestionsInfo.withIds(info: TextInfo): SuggestionsInfo =
            apply { setCookieAndSequence(info.cookie, info.sequence) }
    }

    private companion object {
        val EMPTY = emptyArray<String>()

        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

        /** Enough of a list's commonest words to see every script it is written in. */
        const val SCRIPT_SAMPLE_WORDS = 200

        /** Harper's own spelling lints, which the word lists answer instead. */
        fun GrammarLint.isSpelling(): Boolean =
            kind.equals("Spelling", ignoreCase = true) || kind.equals("Typo", ignoreCase = true)

        fun languageOf(locale: String?): String =
            locale.orEmpty().replace('_', '-').substringBefore('-').lowercase()
                .ifEmpty { Locale.getDefault().language }

        fun isUnlocked(context: Context): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

        fun wordListFor(context: Context, langId: String): WordSource? {
            val downloaded: WordSource? = if (isUnlocked(context)) {
                MappedTrie.open(DictionaryStore.downloadedFile(context.filesDir, langId))
            } else {
                null
            }
            val bundled = { DictionaryStore.ensureBundled(DirectBoot.deviceContext(context), langId)?.let { MappedTrie.open(it) } }
            return downloaded ?: if (langId == "en") bundled() else null
        }

        fun engineFor(context: Context, langId: String, list: WordSource): SuggestionEngine {
            val lexicon = UserLexicon(if (isUnlocked(context)) File(context.filesDir, "learning/user_lexicon.json") else null)
            return if (langId == "en") {
                SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), lexicon)
            } else {
                SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), lexicon).apply {
                    englishSources = false
                    customDictionary = list
                }
            }.apply { primaryLanguageId = langId }
        }

        /** Every string one edit (Norvig) from [word]; deletions first. */
        fun editsOne(word: String): Sequence<String> = sequence {
            for (i in word.indices) {
                yield(word.substring(0, i) + word.substring(i + 1))
            }
            for (i in 0 until word.length - 1) {
                yield(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
            }
            for (i in word.indices) {
                for (c in ALPHABET) yield(word.substring(0, i) + c + word.substring(i + 1))
            }
            for (i in 0..word.length) {
                for (c in ALPHABET) yield(word.substring(0, i) + c + word.substring(i))
            }
        }
    }
}
