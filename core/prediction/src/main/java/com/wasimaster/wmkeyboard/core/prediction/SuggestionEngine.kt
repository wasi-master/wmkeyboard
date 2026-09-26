package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSource
import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex
import kotlin.math.exp
import kotlin.math.ln

/**
 * A secondary-language word list paired with the id of the language it belongs
 * to, so [SuggestionEngine] can attribute committed words back to a language and
 * adapt how strongly that language participates in the mix.
 */
data class SecondaryDictionary(val langId: String, val source: WordSource)

/**
 * Produces the suggestion-bar candidates for the word being composed.
 *
 * Sources, merged and ranked by frequency:
 *  - prefix completions and trie-guided fuzzy corrections (one shared
 *    [FuzzyBeamSearch] walk over every dictionary and the user lexicon)
 *    when the typed word is not in the dictionary;
 *  - learned bigrams (backed by bundled seed pairs) for next-word
 *    prediction when composition is empty;
 *  - Bengali transliteration of the romanized composition when the Avro
 *    input mode is active, ranked against the Bengali dictionary so that
 *    common words (আছি) outrank raw phonetics (আসি).
 */
class SuggestionEngine(
    dictionary: WordSource,
    bengaliIndex: PhoneticIndex,
    private val userLexicon: UserLexicon,
    private val spellings: SpellingMap = SpellingMap.EMPTY,
    private val seedBigrams: SeedBigrams = SeedBigrams.EMPTY,
    private val mixConfidence: LanguageMixConfidence = LanguageMixConfidence(),
) {

    /**
     * Epoch for every input the fuzzy walk depends on. Any setter below that
     * changes what the walk would return bumps it, invalidating [rankedWalk].
     */
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /**
     * The primary (bundled or downloaded) dictionary. A var so the IME can
     * swap in a bigger downloaded English list the moment its download
     * finishes, without rebuilding the engine.
     */
    @Volatile
    private var dictionaryField: WordSource = dictionary
    var dictionary: WordSource
        get() = dictionaryField
        set(value) {
            dictionaryField = value
            generation.incrementAndGet()
        }

    /**
     * Contact-name words, swapped in whenever the contacts permission and
     * setting allow (loaded async, cleared when the setting turns off).
     */
    @Volatile
    var contacts: ContactNames = ContactNames.EMPTY

    /**
     * Contact email addresses, completed as whole tokens ("john" →
     * john.doe@gmail.com) when the user opts in. Fed from the service on the
     * same Contacts permission as [contacts]; cleared when the setting is off.
     */
    @Volatile
    var contactEmails: ContactEmails = ContactEmails.EMPTY

    /**
     * Words from the labels of installed apps, so app names complete while
     * typing ("sign" → Signal). Swapped in when the setting allows, cleared
     * when it turns off.
     */
    @Volatile
    var apps: AppNames = AppNames.EMPTY

    /**
     * Adjacency map for typo weighting, following the active Latin layout
     * (set by the IME on input-mode changes; AZERTY/Dvorak fat-fingers land
     * on different neighbours than QWERTY's).
     */
    /**
     * Key-center model of the live layout in key-width units, fed by the IME
     * alongside proximity. Null (or a tap list of nulls) falls back to the
     * discrete adjacency weights, so hardware keyboards, pasted text and
     * re-armed words behave exactly as before.
     */
    @Volatile
    private var touchModelField: KeyTouchModel? = null
    var touchModel: KeyTouchModel?
        get() = touchModelField
        set(value) {
            touchModelField = value
            generation.incrementAndGet()
        }

    @Volatile
    private var proximityField: KeyProximity = KeyProximity.QWERTY
    var proximity: KeyProximity
        get() = proximityField
        set(value) {
            // The IME rebuilds KeyProximity on every settings emission; only a
            // genuinely different layout invalidates cached walk results.
            if (value == proximityField) return
            proximityField = value
            generation.incrementAndGet()
        }

    /**
     * Whether the bundled English word list and seed bigrams participate.
     * Off for Latin languages without a bundled dictionary (French, German,
     * Spanish): completions and corrections then come only from the user's
     * learned lexicon and contacts, and English autocorrect never mangles
     * their words.
     */
    @Volatile
    private var englishSourcesField: Boolean = true
    var englishSources: Boolean
        get() = englishSourcesField
        set(value) {
            englishSourcesField = value
            generation.incrementAndGet()
        }

    /**
     * Word list the user imported for the language now being typed (empty
     * when they have imported none). Swapped by the IME on every input-mode
     * change, so an imported French list never leaks into English.
     *
     * Unlike [dictionary] this is not gated by [englishSources]: it is the
     * whole point of the feature that French, German and Spanish — which
     * ship no bundled list — can get completions this way.
     */
    @Volatile
    private var customDictionaryField: WordSource = PackedTrie.EMPTY
    var customDictionary: WordSource
        get() = customDictionaryField
        set(value) {
            customDictionaryField = value
            generation.incrementAndGet()
        }

    /**
     * Android's personal dictionary — the list under System settings →
     * Languages & input → Dictionary — read in as one more known-word source
     * (#45). Fed by the IME from [SystemUserDictionary.words] and refreshed
     * when the platform reports a change; empty when the setting is off.
     *
     * Weighted and tiered like the personal lexicon rather than a dictionary:
     * these are the user's own words, so they should win against the bundled
     * list the way a learned word does, and — like the lexicon — they do not
     * vote on glide coverage. Not gated by [englishSources] or by language:
     * a locale-less entry is valid everywhere, and a name or acronym the user
     * added under one locale is not something to unlearn under another.
     */
    @Volatile
    private var systemDictionaryField: WordSource = PackedTrie.EMPTY
    var systemDictionary: WordSource
        get() = systemDictionaryField
        set(value) {
            systemDictionaryField = value
            generation.incrementAndGet()
        }

    /**
     * Surface spellings for the platform dictionary's capitalized entries
     * ("boston" -> "Boston"), from [SystemUserDictionary.Entries.shapes].
     *
     * Set alongside [systemDictionary] and deliberately outside the walk's
     * [generation]: casing decides how a candidate is *written*, never which
     * candidates the walk finds, so a change here must not throw away cached
     * walk results.
     */
    @Volatile
    var systemWordCases: Map<String, String> = emptyMap()

    /**
     * Text-expansion triggers — snippet triggers and the platform dictionary's
     * shortcuts — that a glide may decode to (#170). Build with
     * [triggerSource].
     *
     * Walked by [glide] only. A trigger is not a word: typing "omw" must not
     * complete to it or keep it from being corrected, and the typed path
     * already reaches the trigger through the composing buffer. A stroke has
     * no buffer, so without this the decoder could never read "omw" at all.
     * Rides the user tier at the lexicon's weight, like [systemDictionary], so
     * a learned-words-only glide still finds it.
     */
    @Volatile
    private var glideTriggersField: WordSource = PackedTrie.EMPTY
    var glideTriggers: WordSource
        get() = glideTriggersField
        set(value) {
            glideTriggersField = value
            generation.incrementAndGet()
        }

    /**
     * Dictionaries for the user's secondary languages, consulted alongside the
     * primary so a bilingual typist gets both without switching. These are the
     * freq-1 imported lists, weighted below every primary source; a word valid
     * in one is never autocorrected away. Each is tagged with its language id so
     * its share of the strip adapts to how much the user actually types it (see
     * [mixConfidence] / [recordUsage]).
     */
    @Volatile
    private var secondaryDictionariesField: List<SecondaryDictionary> = emptyList()
    var secondaryDictionaries: List<SecondaryDictionary>
        get() = secondaryDictionariesField
        set(value) {
            secondaryDictionariesField = value
            generation.incrementAndGet()
        }

    /**
     * Language id of the primary (on-screen) language, so a committed word the
     * primary already covers is attributed to it rather than mistaken for
     * secondary-language use, and so learned words tagged with a different
     * language are damped (see [rankedFor]). Blank when no language is set
     * (tests). Bumps the walk generation: the damp is applied inside the
     * cached walk, so a language switch must invalidate it.
     */
    @Volatile
    private var primaryLanguageIdField: String = ""
    var primaryLanguageId: String
        get() = primaryLanguageIdField
        set(value) {
            if (value == primaryLanguageIdField) return
            primaryLanguageIdField = value
            generation.incrementAndGet()
        }

    /**
     * True when English is a secondary language and the primary is not: the
     * bundled English list then participates at a fraction of its frequency
     * (it carries real frequencies, unlike the freq-1 [secondaryDictionaries]),
     * covering the common "native language + English" pairing. Its share is
     * scaled by the adaptive confidence for "en" like any other secondary.
     */
    @Volatile
    private var englishAsSecondaryField: Boolean = false
    var englishAsSecondary: Boolean
        get() = englishAsSecondaryField
        set(value) {
            englishAsSecondaryField = value
            generation.incrementAndGet()
        }

    /**
     * How far the per-field language detection may shift the mix, as the
     * maximum log-space handicap/boost (see [FieldLanguageMix]). 0 turns
     * detection off entirely; the FIELD_SHIFT_* companion constants are the
     * calibrated strengths the settings screen offers. At full evidence a
     * language the field is clearly written in gains up to `e^shift` weight
     * while the others lose the same, so at the stronger settings a
     * detected secondary language genuinely takes over ranking — and with
     * it the autocorrect targets — from the on-screen primary.
     */
    @Volatile
    private var fieldDetectionShiftField: Double = 0.0
    var fieldDetectionShift: Double
        get() = fieldDetectionShiftField
        set(value) {
            if (value == fieldDetectionShiftField) return
            fieldDetectionShiftField = value
            generation.incrementAndGet()
        }

    /**
     * The words already sitting in the field the user just entered, and every
     * word committed there since. Owned by the engine (not injected) because
     * classifying a word needs the very dictionaries the engine holds.
     */
    private val fieldMix = FieldLanguageMix()

    /**
     * Seed the per-field language mix from the words already in the field —
     * oldest first, so the decay leaves the words nearest the caret in
     * charge. Called by the IME when it enters a field; a field it cannot
     * read seeds empty, which keeps the mix neutral until the user types.
     */
    fun seedFieldContext(words: List<String>, prior: FieldLanguageMix.Prior? = null) {
        fieldMix.reset()
        if (secondaryDictionaries.isNotEmpty() || englishAsSecondary) {
            // The app's habit goes in first, so the field's own words decay
            // it the way they decay any older word (see AppLanguageMix).
            if (prior != null) fieldMix.seedPrior(prior)
            for (word in words) {
                val lower = word.lowercase()
                if (lower.isNotEmpty()) fieldMix.record(languagesOwning(lower))
            }
        }
        generation.incrementAndGet()
    }

    /** Forget the field mix (leaving the field, or detection turned off). */
    fun clearFieldContext() {
        fieldMix.reset()
        generation.incrementAndGet()
    }

    /**
     * Words recently committed in the app now being typed in — an in-memory
     * recency overlay from the IME, giving each app's own vocabulary a small
     * ranking edge there. Read post-cache, so no generation bump.
     */
    @Volatile
    var contextWords: Set<String> = emptySet()

    /**
     * Optional reordering model over the ranked top candidates; see
     * [CandidateReranker]. Applied only when the caller passes
     * `allowRerank = true` — the synchronous main-thread call sites never do.
     */
    @Volatile
    var reranker: CandidateReranker = CandidateReranker.NONE

    /**
     * Downloaded corpus n-grams for the active language ([NgramPack.EMPTY]
     * until a pack lands). Corpus context: consulted below every personal
     * store, and its counts are damped so one habitual personal pair beats
     * any population prior.
     */
    @Volatile
    var ngramPack: NgramPack = NgramPack.EMPTY

    /**
     * Avro's backend. Bengali is the one phonetic language whose index and
     * spelling map arrive through the constructor, which is history rather than
     * design: every other scheme lives in [extraPhonetic].
     */
    private val bengaliBackend = PhoneticBackend(PhoneticSchemes.BENGALI, bengaliIndex, spellings)

    /**
     * Bengali index, rebuilt when an imported Bengali list arrives so its
     * words become reachable by transliteration too.
     */
    var bengaliIndex: PhoneticIndex
        get() = bengaliBackend.index
        set(value) {
            bengaliBackend.index = value
        }

    /**
     * Backends of the phonetic languages other than Bengali that are loaded,
     * by language id. Replaced whole when one is added, dropped or rebuilt; a
     * backend's own index can also be swapped in place when only its word list
     * changed.
     */
    @Volatile
    var extraPhonetic: Map<String, PhoneticBackend> = emptyMap()

    /**
     * What a buffer typed on [languageId]'s phonetic layout is resolved
     * through, or null when [languageId] names no phonetic scheme. A scheme
     * whose data has not been loaded still answers — with its rules alone —
     * because a phonetic layout that commits Latin is never what was asked for.
     */
    fun phoneticBackend(languageId: String?): PhoneticBackend? = when (languageId) {
        null -> null
        bengaliBackend.scheme.languageId -> bengaliBackend
        else -> extraPhonetic[languageId]
            ?: PhoneticSchemes.forLanguage(languageId)?.let { PhoneticBackend(it, PhoneticIndex.EMPTY) }
    }

    /**
     * Whether a phonetic layout may commit an English word as English: `hello`
     * typed on Avro stays hello, where it used to come out হ্যালো. Decided per
     * buffer by [PhoneticScriptVerdict]; off, the layout only ever commits its
     * own script and English is something the strip offers. Inert unless
     * English is a secondary language of the layout's ([englishAsSecondary]).
     */
    @Volatile
    private var phoneticAutoEnglishField: Boolean = false
    var phoneticAutoEnglish: Boolean
        get() = phoneticAutoEnglishField
        set(value) {
            if (value == phoneticAutoEnglishField) return
            phoneticAutoEnglishField = value
            generation.incrementAndGet()
        }

    /**
     * Phonetic languages whose space bar commits the letter-for-letter reading
     * rather than a dictionary word that sounds like it. Siblings still fill
     * the strip behind the literal, so "asi" commits আসি and offers আছি; the
     * fixed-spelling map has its own switch and is not affected.
     */
    @Volatile
    private var phoneticSiblingsOffField: Set<String> = emptySet()
    var phoneticSiblingsOff: Set<String>
        get() = phoneticSiblingsOffField
        set(value) {
            if (value == phoneticSiblingsOffField) return
            phoneticSiblingsOffField = value
            generation.incrementAndGet()
        }

    /**
     * Whether a phonetic layout's strip keeps its first two chips fixed — the
     * buffer as typed in Latin letters, then the rules' reading of it — and
     * what fills the rest; null is the ordinary strip, whose head is whatever
     * a space commits. Only the strip moves: a space commits exactly what it
     * would have ([phoneticCommit]). Not part of the walk, so no generation.
     */
    @Volatile
    var phoneticFixedStrip: PhoneticStripSource? = null

    /** The spellings the user has overruled the script of; see [recordScriptChoice]. */
    @Volatile
    var scriptChoices: PhoneticScriptChoices = PhoneticScriptChoices()

    /**
     * English's corpus n-grams while English rides as a secondary language,
     * so the word after `hello` on Avro is predicted from English rather than
     * from a Bengali pack that has never seen it. [NgramPack.EMPTY] otherwise.
     */
    @Volatile
    var secondaryEnglishNgramPack: NgramPack = NgramPack.EMPTY

    /** Whether English takes part on a phonetic layout at all. */
    private val phoneticMixing: Boolean get() = englishAsSecondary && !englishSources

    /**
     * How much the winning candidate must outscore the runner-up before
     * autocorrect fires, set from the user's confidence slider. Higher is
     * stricter: fewer corrections, but fewer wrong ones. Defaults to
     * [DEFAULT_AUTOCORRECT_CONFIDENCE].
     */
    @Volatile
    var autocorrectConfidence: Double = DEFAULT_AUTOCORRECT_CONFIDENCE

    /**
     * When on, [shouldAutocorrect] may return a two-word split ("kortehobe" →
     * "korte hobe") when no single-word correction fires and both halves are
     * known words that clear the same confidence gate. Off by default — the
     * IME turns it on from the user's setting; the standalone spell checker
     * judges isolated words and leaves it off.
     */
    @Volatile
    var autocorrectSplits: Boolean = false

    /**
     * When on, a composing word carrying exactly one digit may be corrected
     * by a same-length substitution at that digit ("as3" → "ase", a
     * number-row slip above the intended letter). Off by default: only the
     * IME path that actually buffers number-row digits into words sets it,
     * so the spell checker never rewrites genuine alphanumerics.
     */
    @Volatile
    var digitSlipCorrections: Boolean = false

    /**
     * Whether a word typed without its apostrophe may be read as the word
     * that has one — "Fix missing apostrophes", from the settings.
     *
     * Read here rather than only at the call that commits, because the strip
     * has to agree with the space bar: with the setting off the reading is
     * not offered either, and with it on it leads. Defaults to on, which is
     * the setting's own default and the right answer for the spell checker,
     * which never reaches the IME's settings at all.
     */
    @Volatile
    var apostropheFixes: Boolean = true

    /**
     * Letters flanking the spacebar on the active layout's bottom row: a
     * stray one of these between two known words is read as a fat-fingered
     * space by [splitCandidates]. Defaults to the QWERTY family's set.
     */
    @Volatile
    var spaceAdjacentKeys: String = SPACE_ADJACENT_DEFAULT

    /**
     * Words the user never wants offered. Stored lowercased; any candidate
     * whose lowercase form is in here is dropped from the strip and never used
     * as an autocorrect target. This only suppresses *suggesting* the word —
     * the user can still type and commit it. Empty by default.
     */
    @Volatile
    var blacklist: Set<String> = emptySet()

    /**
     * Per-word rank adjustments from the word card (#99): key -> steps in
     * [WordRanks.MIN_STEPS]..[WordRanks.MAX_STEPS], fed from
     * [WordRanks.snapshot]. Each step moves the word by [RANK_OFFSET_STEP]
     * in log-score — the unit one engine rank is worth to the reranker
     * ([NgramReranker.RANK_STEP]) — so +1 is roughly "one place up against
     * equals". It re-ranks and never hides: a word pushed to the bottom still
     * surfaces when nothing else matches, and the blacklist is what hides
     * one. Autocorrect never reads it: a rank the user asked for on the strip
     * is not a licence to rewrite what they typed.
     */
    @Volatile
    var rankOffsets: Map<String, Int> = emptyMap()

    /** [word]'s adjustment in log-score, 0.0 for the common case of none. */
    private fun rankOffset(word: String): Double {
        val steps = rankOffsets[word.lowercase()] ?: return 0.0
        return steps * RANK_OFFSET_STEP
    }

    /**
     * When on, words on the bundled [offensiveWords] set are treated like the
     * blacklist: never offered in the strip and never used as an autocorrect
     * target, so the keyboard won't suggest or "correct" a neutral typo into a
     * slur. The user can still type and commit any of them verbatim — this only
     * suppresses *suggesting* them. Off lets them suggest like any other word.
     */
    @Volatile
    var blockOffensiveWords: Boolean = false

    /**
     * The bundled set of potentially-offensive words, lowercased. Only consulted
     * when [blockOffensiveWords] is on. Empty until the service loads it.
     */
    @Volatile
    var offensiveWords: Set<String> = emptySet()

    /**
     * When on, a word typed entirely in capitals (SHOUTING, or an acronym like
     * ASAP, OFC) is never autocorrected — those are deliberate, and "fixing"
     * them to a lowercase dictionary word is almost always wrong. Off treats
     * all-caps like any other word. Set from the user's setting.
     */
    @Volatile
    var skipAllCapsAutocorrect: Boolean = true

    /**
     * How many times a word has to be typed before being learned protects it
     * from autocorrect. Set from the user's setting.
     *
     * 1 is what the keyboard always did: one committed word was permanently
     * exempt, which is fine for a name and wrong for a typo. Raising it means
     * a word has to be typed more than once before autocorrect leaves it
     * alone. Ranking is unaffected either way.
     */
    @Volatile
    var learnedWordMinCount: Int = 1

    /**
     * Autocorrect's memory of its own mistakes: per-pair revert penalties and
     * the fired/reverted ratio behind [adaptiveConfidence]. The default is a
     * memory-only instance (null file), so tests and locked-boot sessions get
     * the session-scoped guarantees with no storage; the IME swaps in the
     * persisted store from attachPersonalStores.
     */
    @Volatile
    var correctionStats: CorrectionStats = CorrectionStats(null)

    /**
     * What the user's own fixes have taught: the pairs [decideCorrection]
     * consults first, and the slips behind them, which reach the walk as
     * [editHabits]. Read after the walk, so no generation bump. Memory-only by
     * default; the IME swaps in the persisted store from attachPersonalStores.
     */
    @Volatile
    var correctionMemory: CorrectionMemory = CorrectionMemory(null)

    /**
     * This user's learned slips, pricing the walk's edits ([EditHabits]).
     * Structural equality on the setter: the IME rebuilds the snapshot every
     * time a fix settles, and only a changed one may invalidate the cached
     * walk.
     */
    @Volatile
    private var editHabitsField: EditHabits = EditHabits.NONE
    var editHabits: EditHabits
        get() = editHabitsField
        set(value) {
            if (value == editHabitsField) return
            editHabitsField = value
            generation.incrementAndGet()
        }

    /**
     * Bigram witness for a taught fix of a real word ("form" → "from"): built
     * over the same personal, corpus and seed counts the revision chip uses.
     */
    private val contextAdvisor: RevisionAdvisor by lazy {
        RevisionAdvisor(userLexicon, seedBigrams) { ngramPack }
    }

    /**
     * What the user did with the words earlier glides gave them, as a nudge
     * on a decode's scores (issue #52). Memory-only by default, like
     * [correctionStats]; the IME swaps in the persisted store.
     */
    @Volatile
    var glideOutcomes: GlideOutcomes = GlideOutcomes(null)

    /**
     * When on, the effective confidence gate is scaled by the user's recent
     * revert rate — a keyboard being corrected-then-undone often demands more
     * certainty before forcing anything. The slider setting stays the anchor.
     */
    @Volatile
    var adaptiveConfidence: Boolean = true

    /**
     * Register of the field being typed into, set by the IME from the target
     * app and field kind when the user enables register priors. CASUAL nudges
     * chat-speak up, FORMAL pushes it down; NEUTRAL (always, when the setting
     * is off) changes nothing. Ranking only — never gates what commits.
     */
    @Volatile
    var register: Register = Register.NEUTRAL

    /**
     * Records that the user undid the autocorrect of [typed] into [corrected].
     * The exact pair is blocked for the run of typing and penalized across
     * sessions; other corrections of the same typed word are untouched.
     *
     * [deliberate] is false for a verdict read back off the field once the text
     * settled, rather than a backspace pressed on the correction itself. Those
     * carry a trap: a user who never noticed the fix, went back to correct
     * their own typo and produced the same typo again leaves the field looking
     * exactly like a rejection. So an indirect verdict whose surviving spelling
     * is not a word at all is thrown away rather than believed — nobody stands
     * by a spelling no dictionary and no personal store has ever seen, and the
     * one reading that fits is that the typo was reproduced. A genuinely
     * personal word (a name, a nickname, a transliteration) reaches
     * [isKnownWord] on its own through [PendingLearn] after a few sightings,
     * and its rejections count in full from then on.
     *
     * [survivor] is the spelling actually standing where the fix was. It is
     * [typed] for the settle verdict above; when the user rewrote the fix into
     * a *third* word by hand it is that word, and being a real word it passes
     * the gate — the correction was wrong, whatever the original typo was.
     */
    fun rejectCorrection(
        typed: String,
        corrected: String,
        deliberate: Boolean = true,
        survivor: String = typed,
    ) {
        if (typed.isEmpty() || corrected.isEmpty()) return
        if (!deliberate && correctionStats.memory != UndoMemory.STRICT && !isKnownWord(survivor)) {
            return
        }
        correctionStats.recordRevert(typed, corrected, deliberate = deliberate)
        // A fix the user taught and has now undone: the penalty above stops it
        // firing, and this keeps the memory (and its viewer) honest about it.
        correctionMemory.unteach(typed, corrected)
    }

    private val emptyTrie: WordSource = PackedTrie.EMPTY

    /** True when [word] is on the suggestion blacklist (case-insensitive). */
    private fun blacklisted(word: String): Boolean =
        blacklist.isNotEmpty() && word.lowercase() in blacklist

    /** True when the offensive filter is on and [word] is a blocked word. */
    private fun offensive(word: String): Boolean =
        blockOffensiveWords && offensiveWords.isNotEmpty() && word.lowercase() in offensiveWords

    /**
     * True when [word] must not be offered or used as an autocorrect target,
     * for either reason (user blacklist or the offensive-words filter).
     */
    private fun suppressed(word: String): Boolean = blacklisted(word) || offensive(word)

    /** The bundled dictionary, or an empty one when [englishSources] is off. */
    private val activeDictionary: WordSource
        get() = if (englishSources) dictionary else emptyTrie

    /**
     * Fraction of its real frequency at which bundled English participates as a
     * secondary language: the base [SECONDARY_ENGLISH_DIVISOR] scaled by how
     * much the user actually mixes English in. At the neutral (untrained)
     * confidence this is exactly `1 / SECONDARY_ENGLISH_DIVISOR`, matching the
     * old fixed behaviour.
     */
    private fun englishSecondaryFactor(): Double =
        mixConfidence.confidenceFor(EN) / SECONDARY_ENGLISH_DIVISOR * fieldFactorFor(EN)

    /** Adaptive weight for a secondary language's imported list. */
    private fun secondaryWeight(langId: String): Int =
        (SECONDARY_WORD_WEIGHT * mixConfidence.confidenceFor(langId) * fieldFactorFor(langId))
            .toInt()

    /**
     * The languages taking part in the current mix: the primary, bundled
     * English when it rides as a secondary, and each secondary list.
     */
    private fun mixLanguageIds(): List<String> {
        val ids = ArrayList<String>(secondaryDictionaries.size + 2)
        if (primaryLanguageId.isNotEmpty()) ids.add(primaryLanguageId)
        if (englishAsSecondary && !englishSources && EN !in ids) ids.add(EN)
        for (t in secondaryDictionaries) if (t.langId !in ids) ids.add(t.langId)
        return ids
    }

    /**
     * Every language of the mix whose dictionary knows [lower], including the
     * language a learned word is tagged with. A word valid in several is
     * evidence for all of them at once — which is exactly why it moves the
     * field mix nowhere.
     */
    /**
     * The languages of the mix that know [word] — the same classification the
     * field mix uses, for callers keeping a tally of their own (the per-app
     * habit). Empty with no mix configured, so a monolingual keyboard records
     * nothing.
     */
    fun owningLanguages(word: String): Set<String> =
        if (secondaryDictionaries.isEmpty() && !englishAsSecondary) emptySet() else languagesOwning(word.lowercase())

    private fun languagesOwning(lower: String): Set<String> {
        val owners = HashSet<String>(4)
        if (primaryLanguageId.isNotEmpty() &&
            (activeDictionary.contains(lower) || customDictionary.contains(lower))
        ) {
            owners.add(primaryLanguageId)
        }
        if (englishAsSecondary && !englishSources && dictionary.contains(lower)) owners.add(EN)
        for (t in secondaryDictionaries) if (t.source.contains(lower)) owners.add(t.langId)
        userLexicon.languageOf(lower)?.let { owners.add(it) }
        if (primaryLanguageId.isNotEmpty() && primaryLanguageId !in owners) {
            val backend = phoneticBackend(primaryLanguageId)
            when {
                backend == null -> Unit
                // The language's own list. For Bangla and Hindi that list
                // reaches the engine as the phonetic index and as nothing
                // else — there is no trie of it among the walk sources — so
                // without this no Bangla word typed on Avro was ever counted
                // as Bangla, and the field could only be seen leaning English.
                backend.index.frequencyOf(lower) > 0 -> owners.add(primaryLanguageId)
                // A listed loanword is a word of both: `ok` and `phone` are as
                // much Banglish as English, so writing one says nothing about
                // which language the sentence is in. Counted for English alone,
                // `ok to bolo` had তো coming out as "to".
                backend.spellings.isLoanword(lower) -> owners.add(primaryLanguageId)
                // A language running on its rules with nothing downloaded has
                // no list to know its own words by, so its script stands in.
                // Only then: with a list, a native word the list does not have
                // is as likely a collision the keyboard just got wrong (ঈ for
                // "I"), and counting it would have the mistake vote for itself.
                backend.index.isEmpty && lower.any(backend.scheme.isNative) -> owners.add(primaryLanguageId)
            }
        }
        return owners
    }

    /**
     * Weight multiplier the field's own words earn [langId]: above 1 when the
     * field is being written in it, below 1 when it is clearly being written
     * in another language of the mix, exactly 1 while there is no evidence —
     * so an empty field behaves as if detection did not exist. Cached per
     * walk generation; every mutation of the field mix bumps the generation.
     */
    private fun fieldFactorFor(langId: String): Double {
        if (langId.isEmpty()) return 1.0
        val gen = generation.get()
        fieldFactorCache?.let { (cachedGen, factors) ->
            if (cachedGen == gen) return factors[langId] ?: 1.0
        }
        val factors = computeFieldFactors()
        fieldFactorCache = gen to factors
        return factors[langId] ?: 1.0
    }

    @Volatile
    private var fieldFactorCache: Pair<Long, Map<String, Double>>? = null

    /**
     * One multiplier per mix language, from each one's share of the field's
     * classified words relative to its strongest rival: `e^(shift·ramp·delta)`
     * with delta in [-1, 1]. A pure-Banglish field at full evidence boosts
     * bn_rom by `e^shift` and damps English by the same, bridging the raw
     * frequency gap between a freq-1 romanized list and the bundled English
     * corpus; a 50/50 field moves nothing.
     */
    private fun computeFieldFactors(): Map<String, Double> {
        val shift = fieldDetectionShift
        if (shift <= 0.0) return emptyMap()
        val shares = fieldMix.shares() ?: return emptyMap()
        val langs = mixLanguageIds()
        if (langs.size < 2) return emptyMap()
        val factors = HashMap<String, Double>(langs.size * 2)
        for (lang in langs) {
            var rival = 0.0
            for (other in langs) {
                if (other != lang) rival = maxOf(rival, shares.shareOf(other))
            }
            val delta = shares.shareOf(lang) - rival
            if (delta != 0.0) factors[lang] = exp(shift * shares.ramp * delta)
        }
        return factors
    }

    /**
     * The language the field is being written in right now: the primary
     * unless detection is on and another mix language clearly dominates the
     * field's words. This is what the learned-word damp treats as "active",
     * so the user's Banglish habits stop being handicapped the moment the
     * field itself turns Banglish.
     */
    private fun detectedLanguageId(): String {
        val active = primaryLanguageId
        if (active.isEmpty() || fieldDetectionShift <= 0.0) return active
        val shares = fieldMix.shares() ?: return active
        var top = active
        var topShare = shares.shareOf(active)
        for (lang in mixLanguageIds()) {
            val share = shares.shareOf(lang)
            if (share > topShare) {
                top = lang
                topShare = share
            }
        }
        return if (topShare - shares.shareOf(active) >= DETECTED_MARGIN) top else active
    }

    /** English's bundled frequency when it is a secondary language, else 0. */
    private fun secondaryEnglishFrequencyOf(word: String): Int =
        if (englishAsSecondary && !englishSources) {
            (dictionary.frequencyOf(word) * englishSecondaryFactor()).toInt()
        } else {
            0
        }

    private val beam = FuzzyBeamSearch()
    private val beamWorkspace = ThreadLocal.withInitial { BeamWorkspace() }

    /**
     * The weights both glide decoders are built from — the shipped
     * [GlideBeam.Tuning] with whatever the user has moved on top.
     *
     * Held rather than read per stroke because [GlideBeam] takes its tuning at
     * construction. It holds nothing else (the workspace is the caller's), so
     * replacing one costs an allocation and no state, which is what makes a
     * setting that changes a weight cheap enough to apply this way.
     */
    @Volatile
    private var glideTuning = GlideBeam.Tuning.DEFAULT

    @Volatile
    private var glideBeam = GlideBeam(glideTuning)
    private val glideWorkspace = ThreadLocal.withInitial { GlideWorkspace() }

    /**
     * The decoder a deep search runs on: the same weights with the vocabulary
     * cap off, so a stroke the capped decode read wrongly gets a second look
     * at every word the dictionary holds (issue #52). Never the default: the
     * cap exists because the tail costs common words their accuracy, and a
     * deep search is the one moment the user has said the common word was
     * not what they meant.
     */
    @Volatile
    private var deepGlideBeam = GlideBeam(glideTuning.copy(vocabularyRank = 0))

    /**
     * Point both decoders at [next], rebuilding them only when something
     * actually moved.
     *
     * The whole tuning at once, because the settings that reach here arrive
     * together: the caller reads one `GestureSettings` and turns it into one
     * set of weights (`GestureSettings.glideTuning()` in :core:settings). The
     * deep decoder takes the same weights with the vocabulary cap dropped,
     * which is the one thing it deliberately ignores.
     */
    fun tuneGlide(next: GlideBeam.Tuning) {
        if (next == glideTuning) return
        glideTuning = next
        glideBeam = GlideBeam(next)
        deepGlideBeam = GlideBeam(next.copy(vocabularyRank = 0))
    }

    /**
     * The same, one weight at a time: every argument defaults to what the
     * engine is already using, so a caller that knows about one setting does
     * not have to know about the others. The three radii are the decoder's
     * tolerances, exposed as settings by #222; [vocabularyRank] is how much of
     * the dictionary a swipe may answer with.
     */
    fun tuneGlide(
        startRadius: Float = glideTuning.startRadius,
        endRadius: Float = glideTuning.endRadius,
        nearRadius: Float = glideTuning.nearRadius,
        vocabularyRank: Int = glideTuning.vocabularyRank,
    ) {
        tuneGlide(
            glideTuning.copy(
                startRadius = startRadius,
                endRadius = endRadius,
                nearRadius = nearRadius,
                vocabularyRank = vocabularyRank,
            ),
        )
    }

    /**
     * How many of a dictionary's commonest words a swipe may decode to, 0 for
     * all of them — [GlideBeam.Tuning.vocabularyRank]. A shorthand for
     * [tuneGlide], kept because it reads as a property at the call sites.
     */
    var glideVocabularyRank: Int
        get() = glideTuning.vocabularyRank
        set(value) {
            tuneGlide(vocabularyRank = value)
        }

    /**
     * The romanization a glide is decoded through, when the layout's keys and
     * its output are different alphabets — Avro, where the grid is QWERTY and
     * the text is Bengali. [RomanizedIndex.EMPTY] everywhere else, which is the
     * ordinary case of decoding the language's own word lists directly.
     */
    @Volatile
    var glideRomanization: RomanizedIndex = RomanizedIndex.EMPTY

    /** The curated Bengali spelling map this engine was built with, so the IME
     * can rebuild the romanization without reloading the assets behind it. */
    val spellingMap: SpellingMap get() = spellings

    /**
     * Whether the language being typed has a word list at all — bundled or
     * downloaded and imported — as opposed to enough of one for a layout. A
     * language without one cannot glide whatever the grid, and the keyboard
     * says so rather than staying silent (#219). Secondary languages and the
     * personal lexicon do not count: a French keyboard with no French list is
     * the case being asked about, however many English words are loaded.
     */
    fun hasLanguageWords(): Boolean =
        (activeDictionary.walkers() + customDictionary.walkers()).any { it.maxSubtree(it.root) > 0 }

    /**
     * Whether [alphabet] can spell enough of the language now being typed for a
     * glide to mean anything. Only the dictionary tier is asked: the personal
     * lexicon is small and can hold words from whatever the user typed last, so
     * letting it vote would have a handful of leftover English words decide
     * whether Bengali is glidable.
     *
     * And only the language's own lists, not its secondaries' (#272). Each
     * source is sampled at its own top, so a secondary language used to vote
     * with as many words as the primary: Polish riding on English put 1,500
     * words full of ł, ą and ż in front of a grid with no keys for them, the
     * share fell under the threshold, and a swipe on a layout that spells
     * English perfectly came out as a tap. A secondary's words the grid cannot
     * draw are simply not decoded, which is all it costs. The secondaries are
     * asked only when the language has no list of its own, where readiness
     * earned on their words is what lets the missing-list chip say so (#219).
     *
     * Nor the lists the user imported, while the language has a bundled or
     * downloaded list to answer for it (#288). An import voted the same way a
     * secondary did, with 1,500 words of its own: a compiled dictionary an
     * older version had copied in unread, or an English list kept under
     * Arabic, put that many words no Arabic grid can draw beside a list the
     * grid spells 99.7 % of, and glide went off with nothing on screen to say
     * why. It cuts both ways on purpose: a handful of imported Latin words
     * does not switch glide on for a grid that cannot spell the language
     * either. With no list of its own, or set to its imported lists alone
     * (#28), the imports are all that is known of the language and they
     * answer, as before. See [GlideCoverage] for the rule in one place.
     */
    fun glideCoverage(alphabet: Set<Int>): Float {
        val romanization = glideRomanization
        // Through the romanization when there is one: on Avro the question is
        // whether the Latin grid spells the *romanized* vocabulary, and asking
        // it of the Bengali word list would answer zero and switch off a layout
        // that decodes perfectly well.
        if (!romanization.isEmpty) {
            return GlideCoverage.measure(romanization.walkSources().map { it.walker }, alphabet)
        }
        val own = (activeDictionary.ownWalkers() + customDictionary.ownWalkers())
            .filter { it.maxSubtree(it.root) > 0 }
        val voters = when {
            own.isNotEmpty() -> own
            hasLanguageWords() -> dictionarySources(primaryLanguageId).map { it.walker }
            else -> dictionarySources(null).map { it.walker }
        }
        return GlideCoverage.measure(voters, alphabet)
    }

    /**
     * Decodes a glide stroke against exactly the word sources typing already
     * uses — bundled, imported, secondaries and the personal lexicon, at the
     * same weights.
     *
     * Glide used to run off its own flat English word list, which is why a
     * learned word reached the strip immediately but the swipe decoder only
     * after a cache rebuild, and why nothing but English could be glided at all.
     * Sharing [walkSources] retires both problems: whatever the user can type,
     * they can now swipe, in whatever language and script the sources hold.
     *
     * The result then goes through the same [reranker] the strip uses, so a
     * swipe gets the full context model — trigrams, the downloaded corpus pack,
     * seed pairs and recency — where it previously had only the user lexicon's
     * follower counts for the immediately preceding word.
     *
     * [deep] is the second look a user asks for by undoing the word the first
     * decode gave them: the vocabulary cap comes off and the search keeps
     * [GLIDE_DEEP_POOL] words instead of [GLIDE_RERANK_POOL], so the walk runs
     * further down the lattice before its floor closes. Slower and noisier
     * than the ordinary decode, which is why it waits to be asked.
     *
     * [tiers] narrows which word sources answer at all, for the sandbox
     * policies (see `GlideSandboxPolicy`): null is every source, the ordinary
     * decode. A stroke decoded against
     * [FuzzyBeamSearch.Tier.USER] alone is answering out of the words this
     * user has actually written, which is the whole point — a dictionary that
     * is not in the search cannot out-fit the word that was meant. Ignored on
     * a romanized layout, where the sources are the romanization's own and the
     * tiers of the word lists behind it are not a distinction the index keeps.
     */
    @Suppress("LongParameterList")
    fun glide(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        limit: Int = 4,
        previousWord: String? = null,
        previousWord2: String? = null,
        recentWords: List<String> = emptyList(),
        deep: Boolean = false,
        previousWord3: String? = null,
        shapes: GlideShapeSource? = null,
        tiers: Set<FuzzyBeamSearch.Tier>? = null,
        lookAhead: Int = 0,
    ): List<GlideBeam.Candidate> {
        val romanization = glideRomanization
        val sources = if (romanization.isEmpty) {
            (walkSources() + glideTriggerSources())
                .let { all -> if (tiers == null) all else all.filter { it.tier in tiers } }
        } else {
            romanization.walkSources()
        }
        if (sources.isEmpty()) return emptyList()
        val decoded = (if (deep) deepGlideBeam else glideBeam).decode(
            path = path,
            keys = keys,
            keyWidth = keyWidth,
            sources = sources,
            ws = glideWorkspace.get(),
            limit = maxOf(limit, if (deep) GLIDE_DEEP_POOL else GLIDE_RERANK_POOL),
            shapes = shapes,
            // Never on a phonetic layout. There the stroke spells a
            // romanization and the answer is Bengali, so a guess would be
            // counted in Latin characters the user never sees and applied to a
            // word whose length has nothing to do with them. Guessing ahead in
            // that pipeline needs its own design, not this one bolted on.
            lookAhead = if (romanization.isEmpty) lookAhead else 0,
        )
        // On a phonetic layout the stroke spelled a romanization; the words it
        // stands for are what the rest of this — the blacklist, the reranker,
        // the caller — should ever see.
        val words = if (romanization.isEmpty) decoded else romanization.resolve(decoded)
        val kept = shiftGlideScores(words.filterNot { suppressed(it.word) })
        if (kept.isEmpty()) return kept
        return rerankGlide(kept, previousWord, previousWord2, previousWord3, recentWords)
            // One word per spelling, whatever source it came from (#172). The
            // decoder keys its results on each trie's own spelling, and the
            // platform dictionary stores "boston" where a word list may store
            // "Boston"; both walk the same stroke, and `displayForm` below
            // would then put the platform capital back on the lower one — two
            // identical chips. Folded here, before the slice, so the strip is
            // not left a word short; first wins, which is the better score.
            .distinctBy { WordKey.of(it.word) }
            .take(limit)
            // The whole complaint behind #44: a swipe knew the word but not
            // the capital, so every proper noun had to be re-picked off the
            // strip. Applied after the rerank, which — like the decoder and
            // the blacklist above it — matches on keys.
            .map { c ->
                val display = displayForm(c.word)
                if (display == c.word) {
                    c
                } else {
                    GlideBeam.Candidate(display, c.score, c.shapeCost, c.tier, c.ahead)
                }
            }
    }

    /**
     * Applies the user's rank adjustments ([rankOffsets]) — the same flat
     * shift [suggest] gives typed candidates — and what they did with earlier
     * readings of a stroke ([glideOutcomes]) to a stroke's candidates, and
     * puts them back in score order. Matches on keys, like the blacklist.
     *
     * In nats on the decoder's own scores rather than as a reorder after the
     * context rerank, so a preference the user has taught also widens the
     * gap the ambiguity picker measures: a stroke they have corrected three
     * times stops asking. A rank adjustment is ten times anything the
     * outcomes can say, so where the user put a word by hand always wins.
     */
    private fun shiftGlideScores(decoded: List<GlideBeam.Candidate>): List<GlideBeam.Candidate> {
        if (decoded.isEmpty()) return decoded
        val nudges = glideOutcomes.view().adjustments(decoded.map { it.word })
        if (rankOffsets.isEmpty() && nudges == null) return decoded
        var moved = false
        val shifted = decoded.mapIndexed { i, c ->
            val shift = rankOffset(c.word) + (nudges?.get(i) ?: 0.0)
            if (shift == 0.0) {
                c
            } else {
                moved = true
                GlideBeam.Candidate(c.word, c.score + shift, c.shapeCost, c.tier, c.ahead)
            }
        }
        return if (moved) shifted.sortedByDescending { it.score } else decoded
    }

    /**
     * The words that start with [drawn], nearest in spelling to [word] first —
     * what the strip offers beside a word a lift finished early (issue #168).
     *
     * A finger that lifts part way through a word, on the strength of a
     * guess, has done the same thing as a tap on a suggestion: it took the
     * word it was shown. The stroke's other readings are then no use to it —
     * they are words that end where the finger stopped, and the finger did
     * not stop at the end of anything — while what it may still want is a
     * neighbour of the word it took: the plural, the possessive, the verb
     * form, the word one letter shorter that the guess ran past. All of
     * those share the letters the stroke drew, and the longer the run of
     * letters they share with the word itself, the nearer they are to it, so
     * that is the order: shared prefix with [word] first, then the same
     * weight the decoder gives a word. [word] itself is left out.
     *
     * Empty on a phonetic layout, where a guess is never made.
     */
    fun glideKin(drawn: String, word: String, limit: Int): List<String> {
        if (drawn.isEmpty() || limit <= 0 || !glideRomanization.isEmpty) return emptyList()
        val self = WordKey.of(word)
        val best = HashMap<String, Double>()
        val spelling = HashMap<String, String>()
        for (src in walkSources()) {
            for (s in TrieCompleter.complete(src.walker, drawn, GLIDE_KIN_SCAN)) {
                val key = WordKey.of(s.word)
                if (key.isEmpty() || key == self || suppressed(s.word)) continue
                val score = src.logWeight + ln(1.0 + s.frequency) + rankOffset(s.word)
                val prior = best[key]
                if (prior == null || score > prior) {
                    best[key] = score
                    spelling[key] = s.word
                }
            }
        }
        val lower = word.lowercase()
        return best.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Double>> {
                    spelling.getValue(it.key).lowercase().commonPrefixWith(lower).length
                }.thenByDescending { it.value }.thenBy { it.key }
            )
            .take(limit)
            .map { displayForm(spelling.getValue(it.key)) }
    }

    /**
     * Lays [word] over [path] the way the decoder would and says where along
     * the stroke each of its keys was visited — what the hand model learns
     * from (issue #52). Against [keys] as handed in, which the caller makes
     * the grid *as drawn* so a consistent miss reads as the same offset
     * whatever the decode was already correcting for. Null when the word
     * cannot be laid on that grid: a romanized stroke's Bengali answer, a
     * restored apostrophe the grid has no key for.
     */
    fun alignGlide(
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
    ): GlideBeam.Alignment? = glideBeam.align(word, path, keys, keyWidth, glideWorkspace.get())

    /**
     * [path] as the shape store keeps a stroke, for the learning buffer to
     * carry until its word settles (issue #52). Null for a stroke too short
     * to be a glide.
     */
    fun glideShapeOf(path: List<GesturePoint>, keyWidth: Float): ByteArray? =
        glideBeam.sampleShape(path, keyWidth, glideWorkspace.get())

    /**
     * Reorders a decoded stroke's candidates by context, defended the way
     * [suggest] defends its own rerank: only words the decoder actually
     * produced may appear, and anything the model does not mention keeps its
     * decoded order behind those it does.
     *
     * Autocorrect-style caution does not apply here — a glide has no "what the
     * user literally typed" to preserve. Every candidate is already the
     * decoder's guess, so reordering guesses costs nothing that was ever
     * certain.
     */
    private fun rerankGlide(
        decoded: List<GlideBeam.Candidate>,
        previousWord: String?,
        previousWord2: String?,
        previousWord3: String?,
        recentWords: List<String>,
    ): List<GlideBeam.Candidate> {
        if (reranker === CandidateReranker.NONE || decoded.size < 2) return decoded
        val pool = decoded.map { it.word }
        val reordered = reranker.rerank(
            RerankContext(composing = "", previousWord, recentWords, previousWord2, previousWord3), pool,
        ) ?: return decoded
        val byWord = decoded.associateBy { it.word }
        val moved = reordered.mapNotNull(byWord::get)
        return moved + decoded.filterNot { it.word in reordered }
    }

    /**
     * The last walk's ranked result and everything it depended on. One
     * keystroke asks the same question twice — [suggest] builds the strip,
     * then [shouldAutocorrect] (precomputing what a space would commit) asks
     * for the same word moments later — so answering the second ask from the
     * first halves the per-keystroke walk cost. Single @Volatile slot: racing
     * threads at worst both recompute the same immutable value.
     */
    private class RankedWalk(
        val word: String,
        val generation: Long,
        val lexMutations: Long,
        val k: Int,
        /** Defensive copy of the tap list; compared structurally (element
         * identity) so in-place mutation of the caller's buffer misses. */
        val touch: List<TouchPoint?>?,
        /** The key sets the walk read, compared by value for the same reason. */
        val keys: KeySets?,
        val ranked: List<FuzzyBeamSearch.ScoredCandidate>,
    )

    @Volatile
    private var rankedWalk: RankedWalk? = null

    private fun rankedFor(
        lower: String,
        limit: Int,
        touch: List<TouchPoint?>?,
        keys: KeySets? = null,
    ): List<FuzzyBeamSearch.ScoredCandidate> {
        val k = maxOf(limit * 2, WALK_K)
        val gen = generation.get()
        val lexGen = userLexicon.mutationCount()
        rankedWalk?.let { cached ->
            if (cached.word == lower && cached.generation == gen &&
                cached.lexMutations == lexGen && cached.k >= k && cached.touch == touch &&
                cached.keys == keys
            ) {
                return cached.ranked
            }
        }
        val model = touchModelField
        val scoring = if (model != null && touch != null && touch.any { it != null }) {
            FuzzyBeamSearch.TouchScoring(model, touch)
        } else {
            null
        }
        // search() sizes its own result list as max(limit * 2, AUTOCORRECT_K);
        // k / 2 makes that exactly k.
        val walked = beam.search(
            walkSources(), lower, proximity, k / 2, beamWorkspace.get(),
            touch = scoring, habits = editHabitsField, keys = keys,
        )
        val ranked = dampMismatchedLanguages(walked)
        rankedWalk = RankedWalk(lower, gen, lexGen, k, touch?.let(::ArrayList), keys, ranked)
        return ranked
    }

    /**
     * Damp learned-only words tagged with a language other than the active
     * one, so Bengali-romanized habits learned under bn_rom stop crowding the
     * English strip (and vice versa). Applies only to pure-user candidates —
     * anything a dictionary also knows is a real word of the active language
     * — and only to tagged words: untagged (legacy, settings-app) words
     * belong to every language. The damp is a ranking handicap, not a ban;
     * a strong habit still surfaces when nothing else fits.
     */
    private fun dampMismatchedLanguages(
        ranked: List<FuzzyBeamSearch.ScoredCandidate>,
    ): List<FuzzyBeamSearch.ScoredCandidate> {
        // Relative to the *detected* language, not the on-screen one: in a
        // field the mix says is Banglish, it is the English-tagged habits
        // that crowd, and the Banglish ones that belong.
        val active = detectedLanguageId()
        if (active.isEmpty() || ranked.isEmpty()) return ranked
        val anyNeedDamp = ranked.any { c ->
            c.dictScore == Double.NEGATIVE_INFINITY &&
                c.userScore != Double.NEGATIVE_INFINITY &&
                userLexicon.languageOf(c.word).let { it != null && it != active }
        }
        if (!anyNeedDamp) return ranked
        var changed = false
        val damped = ranked.map { c ->
            val pureUser = c.dictScore == Double.NEGATIVE_INFINITY &&
                c.userScore != Double.NEGATIVE_INFINITY
            if (!pureUser) return@map c
            val tag = userLexicon.languageOf(c.word) ?: return@map c
            if (tag == active) return@map c
            changed = true
            FuzzyBeamSearch.ScoredCandidate(
                c.word, c.score - LANG_MISMATCH_DAMP, c.editCost, c.edits,
                c.completedChars, c.tier, c.dictScore, c.userScore - LANG_MISMATCH_DAMP,
                c.accents,
            )
        }
        if (!changed) return ranked
        return damped.sortedWith(
            compareByDescending<FuzzyBeamSearch.ScoredCandidate> { it.score }.thenBy { it.word }
        )
    }

    /**
     * The weighted trie sources one fuzzy walk covers. Built per call —
     * cheap — with each language's mix confidence read once, not once per
     * candidate (LanguageMixConfidence is synchronized; per-candidate reads
     * were the one real lock-contention point on the hot path).
     */
    private fun walkSources(): List<FuzzyBeamSearch.WalkSource> {
        val sources = ArrayList<FuzzyBeamSearch.WalkSource>()
        fun add(wordSource: WordSource, logWeight: Double, tier: FuzzyBeamSearch.Tier) {
            for (walker in wordSource.walkers()) {
                sources.add(FuzzyBeamSearch.WalkSource(walker, logWeight, tier))
            }
        }
        sources.addAll(dictionarySources(null))
        for (walker in userLexicon.walkers()) {
            sources.add(
                FuzzyBeamSearch.WalkSource(walker, LOG_USER_WORD_WEIGHT, FuzzyBeamSearch.Tier.USER)
            )
        }
        // The platform's personal dictionary rides the user tier at the
        // lexicon's weight: every entry is frequency 1, i.e. a word the user
        // typed once.
        for (walker in systemDictionary.walkers()) {
            sources.add(
                FuzzyBeamSearch.WalkSource(walker, LOG_USER_WORD_WEIGHT, FuzzyBeamSearch.Tier.USER)
            )
        }
        return sources
    }

    /**
     * The dictionary tier of [walkSources], at the weights the mix gives it,
     * narrowed to [onlyLang] when that is not null.
     *
     * One place owns those weights so a narrowed lookup and the full walk
     * cannot drift apart: an elision's ratio weighs the word after the prefix
     * against the fused spelling, and the two have to be measured on the same
     * scale or a secondary language's reading quietly outranks a primary's.
     */
    private fun dictionarySources(onlyLang: String?): List<FuzzyBeamSearch.WalkSource> {
        val sources = ArrayList<FuzzyBeamSearch.WalkSource>()
        fun add(wordSource: WordSource, logWeight: Double) {
            for (walker in wordSource.walkers()) {
                sources.add(FuzzyBeamSearch.WalkSource(walker, logWeight, FuzzyBeamSearch.Tier.DICTIONARY))
            }
        }
        // ln(1.0) = 0 while the field mix is neutral, keeping the primary's
        // weights bit-identical to the pre-detection engine.
        val primaryShift = ln(fieldFactorFor(primaryLanguageId))
        if (onlyLang == null || onlyLang == primaryLanguageId) {
            add(activeDictionary, primaryShift)
            add(customDictionary, LOG_CUSTOM_WORD_WEIGHT + primaryShift)
        }
        if (englishAsSecondary && !englishSources && (onlyLang == null || onlyLang == EN)) {
            val factor = englishSecondaryFactor()
            if (factor > 0) add(dictionary, ln(factor))
        }
        for (t in secondaryDictionaries) {
            if (onlyLang != null && t.langId != onlyLang) continue
            val weight = SECONDARY_WORD_WEIGHT * mixConfidence.confidenceFor(t.langId) *
                fieldFactorFor(t.langId)
            if (weight > 0) add(t.source, ln(weight))
        }
        return sources
    }

    /** [glideTriggers] as walk sources, at the user tier. */
    private fun glideTriggerSources(): List<FuzzyBeamSearch.WalkSource> =
        glideTriggers.walkers().map {
            FuzzyBeamSearch.WalkSource(it, LOG_USER_WORD_WEIGHT, FuzzyBeamSearch.Tier.USER)
        }

    /**
     * Whether anything at all could complete a word in the language now being
     * typed: a bundled list that participates, an imported one, a secondary
     * language's, or words the user has learned.
     *
     * Asked by the IME before it re-arms a word the caret landed on as the
     * composing region — an underline that no source can ever complete is worse
     * than leaving the text alone. Built on exactly the sources [walkSources]
     * walks, so it can never promise completions the walk would not produce:
     * every language ships the same empty imported trie until a download lands,
     * and a source with no words is not a source.
     *
     * A walker's root bound is the emptiness test the fuzzy walk itself uses —
     * a subtree whose best frequency is zero holds nothing the beam would keep.
     */
    val hasWordSources: Boolean
        get() = walkSources().any { it.walker.maxSubtree(it.walker.root) > 0 }

    /** Best frequency for a word across the primary, secondary and platform lists. */
    private fun dictionaryFrequencyOf(word: String): Int = maxOf(
        activeDictionary.frequencyOf(word),
        weighted(customDictionary.frequencyOf(word), CUSTOM_WORD_WEIGHT),
        weighted(systemDictionary.frequencyOf(word), USER_WORD_WEIGHT),
        secondaryEnglishFrequencyOf(word),
        secondaryDictionaries.maxOfOrNull {
            weighted(it.source.frequencyOf(word), secondaryWeight(it.langId))
        } ?: 0,
    )

    /**
     * Frequency × weight, widened to Long and clamped to the Int range.
     * Imported frequency lists (OpenSubtitles-style raw counts) can carry
     * tens of millions; a plain Int×Int would overflow negative and sink the
     * most common words to the bottom of the suggestions.
     */
    private fun weighted(frequency: Int, weight: Int): Int =
        (frequency.toLong() * weight).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Whether a lower-case [word] is in any list the keyboard cannot edit —
     * the active, imported, platform and secondary dictionaries — as opposed
     * to the personal lexicon. The strip's delete action asks this to know
     * whether forgetting a word is enough or the never-suggest list has to
     * finish the job (#99) — with [includePlatform] off, since it can take a
     * word out of Android's dictionary itself.
     */
    fun inDictionaries(word: String, includePlatform: Boolean = true): Boolean =
        activeDictionary.contains(word) || customDictionary.contains(word) ||
            (includePlatform && systemDictionary.contains(word)) ||
            (englishAsSecondary && !englishSources && dictionary.contains(word)) ||
            secondaryDictionaries.any { it.source.contains(word) }

    /**
     * Whether [word] is one the keyboard already knows — from any loaded
     * dictionary, Android's personal dictionary, the user's own lexicon,
     * their contacts or their installed apps.
     *
     * This is the gate in front of learning: a known word committed once is
     * ordinary evidence and is counted straight away, while an unknown one has
     * to earn its place (see [PendingLearn]). Same membership the autocorrect
     * exemption in [shouldAutocorrect] asks about, which is the point — a word
     * that would not be corrected is a word there is nothing to be careful
     * about.
     */
    fun isKnownWord(word: String): Boolean {
        val lower = word.lowercase()
        return inDictionaries(lower) || userLexicon.contains(lower) ||
            contacts.contains(lower) || apps.contains(lower)
    }

    /**
     * Whether a loaded wordlist spells [word] in lower case, which is what
     * lets the case vote treat it as a known lower-case word rather than a new
     * one waiting for its first capital (#154). A platform dictionary entry
     * the user typed with a capital ("Boston") says the opposite, so it does
     * not count; contacts and app labels are left out for the same reason
     * [displayForm] leaves them out.
     */
    fun spellsInLowerCase(word: String): Boolean {
        val lower = word.lowercase()
        return lower !in systemWordCases && inDictionaries(lower)
    }

    /**
     * Where [word] comes from, for the word card (#99). Reads every source
     * once and ranks the word in each frequency list it is in; the first rank
     * query on a list builds that list's histogram (see [RankFloorCache]), so
     * call it off the main thread. Pure lookup: it neither walks nor caches.
     */
    fun describe(word: String): WordFacts {
        val key = word.lowercase()
        fun pack(langId: String, source: WordSource): PackFact? {
            val frequency = source.frequencyOf(key)
            if (frequency <= 0) return null
            val walkers = source.walkers()
            val rank = walkers.mapNotNull { w -> w.rankOfFrequency(frequency).takeIf { it > 0 } }
                .minOrNull() ?: 0
            val size = walkers.maxOfOrNull { it.vocabularySize() } ?: 0
            return PackFact(langId, frequency, rank, size)
        }
        val primary = pack(primaryLanguageId.ifBlank { EN }, activeDictionary)
        val secondaryEnglish = if (englishAsSecondary && !englishSources) pack(EN, dictionary) else null
        val secondary = secondaryDictionaries.mapNotNull { pack(it.langId, it.source) } +
            listOfNotNull(secondaryEnglish)
        val learned = if (userLexicon.contains(key)) {
            LearnedFact(
                count = userLexicon.frequencyOf(key),
                langId = userLexicon.languageOf(key),
                display = userLexicon.displayOf(key),
                casePinned = userLexicon.isCasePinned(key),
            )
        } else {
            null
        }
        return WordFacts(
            key = key,
            primary = primary,
            secondary = secondary,
            customFrequency = customDictionary.frequencyOf(key),
            system = systemDictionary.contains(key),
            learned = learned,
            contact = contacts.contains(key),
            app = apps.contains(key),
            blacklisted = blacklisted(key),
            rankOffset = rankOffsets[key] ?: 0,
        )
    }

    /**
     * Attribute one committed [word] to the language in the active mix that owns
     * it, feeding [mixConfidence] so the secondary tier's weighting tracks real
     * use. A no-op unless a secondary mix is configured, so a monolingual user
     * pays nothing. Words the primary already covers count toward the primary,
     * which is how a lightly-used secondary ends up damped relative to it.
     */
    fun recordUsage(word: String) {
        if (secondaryDictionaries.isEmpty() && !englishAsSecondary) return
        val lower = word.lowercase()
        // The field mix takes every owner at once — an ambiguous word is
        // evidence for each of its languages and so moves the mix nowhere —
        // while the long-term confidence keeps its exclusive attribution.
        fieldMix.record(languagesOwning(lower))
        val langId = when {
            activeDictionary.contains(lower) || customDictionary.contains(lower) ||
                userLexicon.contains(lower) -> primaryLanguageId
            englishAsSecondary && !englishSources && dictionary.contains(lower) -> EN
            else -> secondaryDictionaries.firstOrNull { it.source.contains(lower) }?.langId
                ?: primaryLanguageId
        }
        mixConfidence.record(langId)
        // Confidences weight the walk sources; recorded use invalidates
        // cached walk results.
        generation.incrementAndGet()
    }

    companion object {
        /** Language id of bundled English, the only special-cased secondary. */
        private const val EN = "en"

        /**
         * Log-score worth of one step of [rankOffsets]. One nat, the same
         * unit the reranker treats one engine rank as
         * ([NgramReranker.RANK_STEP]): ten steps span e^10, which reaches
         * from the rarest word in a downloaded list to its commonest.
         */
        private const val RANK_OFFSET_STEP = 1.0
        /**
         * [triggers] as a [glideTriggers] source: lowercased the way
         * snippet triggers are matched, flat frequency 1 like every user-tier
         * entry, and nothing shorter than two characters, which a stroke
         * cannot tell from a tap.
         */
        fun triggerSource(triggers: Iterable<String>): WordSource {
            val keys = triggers.map { it.trim().lowercase() }.filter { it.length >= 2 }.distinct()
            return if (keys.isEmpty()) PackedTrie.EMPTY else PackedTrie.of(keys.map { it to 1 })
        }

        /** Learned words get a large boost so personalization wins quickly. */
        private const val USER_WORD_WEIGHT = 500

        /**
         * Imported word lists usually carry no frequency column, so every
         * word lands at 1 and would rank below the bundled list's rarest
         * tail. This lifts them to roughly mid-dictionary — present and
         * correctable, without outranking words the user actually types.
         */
        private const val CUSTOM_WORD_WEIGHT = 100

        /** Imported secondary-language lists rank below the primary custom list. */
        private const val SECONDARY_WORD_WEIGHT = 40

        /** English-as-secondary participates at a fraction of its real frequency. */
        private const val SECONDARY_ENGLISH_DIVISOR = 2
        /** Per-occurrence weight of a contact-name word (counts are tiny). */
        private const val CONTACT_WEIGHT = 3000

        /**
         * A matched contact email is an exact prefix of one of the user's own
         * addresses, so it wins strongly over ordinary completions.
         */
        private const val CONTACT_EMAIL_WEIGHT = 4000

        /**
         * Don't offer email completions for a single-letter prefix — that
         * would list every address the moment a letter is typed.
         */
        private const val CONTACT_EMAIL_MIN_PREFIX = 2

        /**
         * App-label words rank below contacts: you type a friend's name far
         * more often than an app's, and app labels contain ordinary words
         * ("Files", "Photos", "Clock") that must not outrank the dictionary.
         */
        private const val APP_WEIGHT = 400
        /** Split suggestions score slightly under their rarer half. */
        private const val WEIGHT_SPLIT = 0.8

        /** Split weight when a stray boundary letter had to be dropped: ~1
         * nat under [WEIGHT_SPLIT], pricing the dropped letter like a far
         * substitution rather than a plain deletion. Tuned against the eval
         * harness — at deletion-cost pricing (0.55) these splits crowded
         * genuine one-edit corrections out of the strip's top-1 (-0.45pt);
         * the halves must together be ~3x more frequent than the correction
         * they outrank, which is what "both halves are clearly words" means. */
        private const val WEIGHT_SPLIT_DROPPED = 0.3

        /** Letters that flank the spacebar on a QWERTY-family bottom row. */
        const val SPACE_ADJACENT_DEFAULT = "cvbnm"

        /** How far below its key's centre, in key widths, a tap must land
         * before the letter it typed may be read as a spacebar miss. A quarter
         * key: the lower third of a phone row, where a finger reaching for the
         * spacebar catches the row above, and outside the scatter of a tap
         * aimed at the letter itself. */
        const val SPACE_SLIP_MIN_DROP = 0.25f

        /** Shortest typed run a split autocorrect may rewrite: two 2-letter
         * halves plus margin — below this, splits stay strip suggestions. */
        private const val SPLIT_AUTOCORRECT_MIN_LENGTH = 5

        /**
         * Autocorrect fires only when the best candidate outscores the
         * runner-up by this factor; anything closer is ambiguous and only
         * suggested, never forced.
         */
        const val DEFAULT_AUTOCORRECT_CONFIDENCE = 4.0

        /** Slider bounds: 1.5 corrects eagerly, 10 only on near-certainty. */
        const val MIN_AUTOCORRECT_CONFIDENCE = 1.5
        const val MAX_AUTOCORRECT_CONFIDENCE = 10.0

        /** Nothing to do: neither applied nor offered. */
        val NO_CORRECTION = CorrectionDecision()

        /**
         * A Bengali phonetic sibling only outranks the literal
         * transliteration (which is what the composing preview shows) when
         * it is at least this many times more frequent. আছি (6900) beats
         * আসি (2300) for "asi", but হল (1986) never steals "holO" from
         * হলো (1900).
         */
        private const val SIBLING_CONFIDENCE = 2.0

        /** Chips a strip is assumed to show when the caller does not say. */
        const val DEFAULT_PHONETIC_SLOTS = 3

        /**
         * How common a word the user taught the keyboard under English counts
         * as, on [PhoneticScriptVerdict]'s scale. It has no corpus frequency,
         * but it was typed and kept several times, which no rare word was.
         */
        private const val LEARNED_LATIN_COMMONNESS = 0.6

        private val NATIVE_UNCONTESTED =
            PhoneticScriptVerdict.Verdict(PhoneticScript.NATIVE, contested = false)

        /** Log-space forms of the source weights, fed to the fuzzy walk. */
        private val LOG_USER_WORD_WEIGHT = ln(USER_WORD_WEIGHT.toDouble())
        private val LOG_CUSTOM_WORD_WEIGHT = ln(CUSTOM_WORD_WEIGHT.toDouble())

        /**
         * Synthetic runner-up score when a correction has no competition at
         * all: an unopposed but weak candidate — a rare word reached by an
         * expensive edit — must clear `SOLO_RUNNER_UP_SCORE + ln(confidence)`
         * or stay a suggestion. Tuned so a mid-frequency word one far slip
         * away (hallo -> hello at frequency 70) still fires at the default
         * confidence, while a lone two-edit hit on a rare word does not.
         */
        private const val SOLO_RUNNER_UP_SCORE = 1.0

        /**
         * How many times commoner an accented twin must be before the
         * accentless spelling stops counting as a word of its own; see
         * [accentShadowed]. The Polish stand-ins sit at 50 to 90 times (`juz`,
         * `sie`, `moze`). The real pairs sit well under 20: `ze`/`że` at 7,
         * Spanish `mas`/`más` at 15.
         */
        private const val ACCENT_SHADOW_RATIO = 20.0

        /**
         * How many times the best one-edit fix must outscore a listed spelling,
         * edit cost included, before that spelling counts as a typo the corpus
         * kept (#244); see [typoShadowed]. The English list's typos sit far
         * above it in raw counts: `wheee` against `where` at 22,000, `thw`
         * against `the` at 690,000, `teh` at 180,000. At 100 it would take
         * real rare words as well: `cress` (`dress`), `votive` (`motive`).
         */
        private const val TYPO_SHADOW_RATIO = 1_000.0

        /**
         * How far down its list a word must rank before [typoShadowed] may call
         * it a typo: the size of the Small download. The Small list does not
         * hold a word ranked below this at all, so it was corrected like any
         * unknown word, and the larger lists stop protecting it only where it
         * is a thousand times rarer than its fix. Every word the Small list
         * and the bundled one hold stays a word.
         */
        private const val TYPO_SHADOW_MIN_RANK = 50_000

        /**
         * How many times commoner the word after an elided prefix must be
         * than the fused spelling before that spelling stops counting as a
         * word of its own and reads as the elision (#215); see
         * [elisionReading]. The French list's fused stand-ins sit far above
         * it — `cest` against `est` at 5,000, `jai` against `ai` at 1,400,
         * `quil` against `il` at 15,000 — while the real words that happen to
         * split sit well under: `lune` against `une` at 136, `mont` against
         * `ont` at 181, `tas` against `as` at 48. Also the price a known
         * fused spelling's elided reading pays in the strip, so that `tas`
         * leads `t'as` and `cest` trails `c'est` by the same margin.
         */
        private const val ELISION_SHADOW_RATIO = 200.0

        /**
         * How many times commoner a hyphenated compound has to be than its
         * fused spelling for the strip to offer it over a fused spelling some
         * list holds; see [hyphenReading]. `что-то` against `чтото` is 1,800,
         * `well-paid` against `wellpaid` about 64; `on-line` against `online` is far
         * under 1 and is never offered.
         */
        private const val HYPHEN_SHADOW_RATIO = 50.0

        /** Shortest fused spelling [hyphenReading] tries to split. */
        private const val HYPHEN_READING_MIN_LENGTH = 4

        /** Most parts [knownCompound] vouches for: `mother-in-law`, not a sentence of hyphens. */
        private const val MAX_COMPOUND_PARTS = 4

        /**
         * How many times commoner than the word after its prefix a fused
         * spelling may be and still have the elision offered on the strip.
         * `lune` at 20,000 keeps *l'une* (`une` at 2.7 million) and `deux`
         * at 300,000 keeps *d'eux* (`eux` at 92,000); `quand` at a million
         * loses *qu'and* (an English `and` at a few thousand).
         */
        private const val ELISION_OFFER_FLOOR = 10.0

        /**
         * How far an English contraction leads the spelling it repairs in
         * the strip; see [contractionReading].
         *
         * A margin rather than a ranking, because there is nothing to rank:
         * [Apostrophes] answers from a table that already refuses every form
         * with a second reading, and the space bar is going to commit that
         * answer whatever the lists think of it. The number only has to be
         * bigger than the gap a frequency list can open between the two
         * spellings — the downloadable English list has `thats` at 3,866 and
         * `that's` at 2,116 — and it is `ln` of a factor, so this is a
         * thousandfold.
         */
        private const val CONTRACTION_LEAD = 7.0

        /**
         * Share of the silent-replacement margin a candidate has to clear to
         * be *offered* instead.
         *
         * Well under half, because the two decisions are not the same
         * question. Applying a correction has to be nearly certain, since
         * being wrong rewrites what somebody wrote and they may not notice.
         * Offering one costs a chip they can ignore, so it is worth doing on
         * much weaker evidence — everything in this band used to be discarded
         * silently. It is a fraction of the *effective* margin rather than a
         * constant so that every knob above it (the confidence slider, the
         * adaptive multiplier, typing rhythm) moves this bar with it.
         */
        const val OFFER_MARGIN_FRACTION = 0.35

        /**
         * A word at most this long wears its correction on its face: three or
         * four letters, one of them different, and the eye catches it.
         */
        private const val PLAIN_WORD_LENGTH = 4

        /**
         * ...and this many letters past that hides one completely. A swapped
         * letter in the middle of "accommodation" goes by unread in a way it
         * never does in "teh".
         */
        private const val PLAIN_WORD_SPAN = 6.0

        /**
         * How much of [CorrectionDecision.complexity] the edit's own cost is
         * worth, the rest going to word length. Weighted towards the edit
         * because it answers the sharper question: whether the keyboard can
         * explain the slip at all.
         */
        private const val COMPLEXITY_SHAPE_WEIGHT = 0.7

        /** Shape of the learned-bigram context boost on completions. */
        private const val CONTEXT_BIGRAM_BETA = 0.5

        /** Cap: habitual pairs may re-rank the strip, never bury an exact
         * high-frequency match (ln 4 — a 4x multiplicative equivalent). */
        private val MAX_CONTEXT_BOOST = ln(4.0)

        /** Seed pairs are weaker evidence than the user's own habits. */
        private val SEED_CONTEXT_BOOST = ln(1.5)

        /** Handicap on a once-reverted pair: the x0.25 of the old design. */
        private val PAIR_PENALTY = ln(4.0)

        /** Recency edge for words typed recently in the same app. */
        private val APP_RECENCY_BOOST = ln(1.3)

        /** Handicap on a learned-only word tagged with another language: a
         * 3x frequency disadvantage, enough to stop cross-language crowding
         * without ever hiding a genuinely strong habit. */
        private val LANG_MISMATCH_DAMP = ln(3.0)

        /**
         * Calibrated [fieldDetectionShift] strengths. Each is the maximum
         * log-space swing per language; the detected and rival languages
         * move in opposite directions, so the effective bridge between them
         * is `e^(2·shift)`. Gentle re-orders the strip without letting the
         * detected language's typo targets overtake the primary's common
         * words; balanced hands ranking over for all but the most common
         * rival words while the ×4 autocorrect confidence gate absorbs the
         * contested middle; aggressive is a full role swap that out-bridges
         * even top-frequency English against a freq-1 romanized list.
         */
        const val FIELD_SHIFT_OFF = 0.0
        const val FIELD_SHIFT_GENTLE = 1.4
        const val FIELD_SHIFT_BALANCED = 2.6
        const val FIELD_SHIFT_AGGRESSIVE = 4.0

        /**
         * Share lead over the primary a mix language needs before the
         * learned-word damp treats it as the field's language. A third keeps
         * a genuinely mixed field on the primary's side.
         */
        private const val DETECTED_MARGIN = 0.34

        /** Register prior shifts: chat-speak's edge in casual fields, and its
         * (heavier) handicap in formal ones — misranking "lol" upward in an
         * email costs more than missing it in a chat. */
        private val CASUAL_INFORMAL_BOOST = ln(1.5)
        private val FORMAL_INFORMAL_DAMP = ln(2.5)

        // Join-chip guards: the joined word must be a reasonably common word
        // and clearly beat the rarer of its parts (mirror of WEIGHT_SPLIT's
        // conservatism, inverted).
        private const val JOIN_MAX_LENGTH = 24
        private const val JOIN_MIN_FREQ = 50
        private const val JOIN_CONFIDENCE = 1.25

        /** How many ranked candidates a reranker may reorder. */
        private const val RERANK_POOL = 8

        /**
         * How deep a glide decodes before reranking. Deeper than the four
         * candidates the strip shows, so context has something to reorder —
         * a swipe's right answer is regularly the decoder's second or third
         * guess, which is exactly the case a context model is there to fix.
         */
        private const val GLIDE_RERANK_POOL = 8

        /**
         * Completions read per source when a lift finishes a word early
         * ([glideKin]). The commonest words under a prefix, of which the
         * strip then shows the few nearest the word taken; wide enough that
         * a plural or possessive ranked well below the word itself is still
         * in the pool, narrow enough to stay a bounded walk.
         */
        private const val GLIDE_KIN_SCAN = 32

        /**
         * The pool a deep search keeps. Twice the ordinary one: the decoder's
         * internal K is twice its limit, so this holds the walk's floor open
         * for 32 words rather than 16 and lets the words behind the leaders
         * — the ones a first decode pruned as not worth finishing — through.
         */
        private const val GLIDE_DEEP_POOL = 16

        /**
         * How far ahead the best candidate has to be before a swipe commits it
         * without asking, in nats. About a factor of three. The default for
         * [glideIsAmbiguous], and the one tier of the user's sensitivity
         * setting that reads exactly as every stroke was judged before there
         * was a setting.
         */
        const val AMBIGUOUS_MARGIN = 1.1

        /**
         * Whether a decoded stroke is a close enough call to be worth asking about.
         *
         * The measure is the gap between the best candidate and the runner-up, in
         * the same log units everything else is scored in — so it reads directly as
         * a likelihood ratio, and a gap of [AMBIGUOUS_MARGIN] means the two words
         * are within about a factor of three of each other. Below that, calling it
         * for the leader is a coin toss dressed up as a decision.
         *
         * [margin] is the user's tier. `Double.POSITIVE_INFINITY` means any stroke
         * with two readings at all is a close call; it needs no special case,
         * because every finite gap is under it.
         *
         * Deliberately not a function of the shape cost. A stroke can be drawn
         * beautifully and still be ambiguous — ক and খ are the same stroke however
         * carefully it is made — and a scruffy stroke with only one word anywhere
         * near it is not ambiguous at all.
         */
        fun glideIsAmbiguous(
            decoded: List<GlideBeam.Candidate>,
            margin: Double = AMBIGUOUS_MARGIN,
        ): Boolean = decoded.size >= 2 && decoded[0].score - decoded[1].score < margin

        /**
         * How deep the fuzzy walk ranks for the suggest path. The post-walk
         * context boosts can only promote candidates the walk emitted, and
         * ContextMissRateTest measured that single-error targets land in the
         * walk's ranks 9-32 often enough to matter (11% of rarest-quartile
         * targets fall outside the top-8; under 1.2% fall outside the top-32).
         * Autocorrect deliberately still judges only the top
         * [FuzzyBeamSearch.AUTOCORRECT_K]: its confidence gate compares the
         * top pair, and deeper candidates should neither become silent
         * replacements nor stand in as runner-ups.
         */
        private const val WALK_K = 32

        /** Corpus n-gram counts divided by this before joining the personal
         * evidence scale: one personal use ~ this many corpus sightings. */
        private const val PACK_COUNT_SCALE = 50

        /**
         * How far below the board's best word a candidate may score and still
         * float, in the beam's log-space units — about a fifty-fold frequency
         * ratio. Without a floor, sparse mode always paints exactly as many
         * words as it is allowed, two of them rubbish, and the board never goes
         * quiet; the Z10 shows nothing when it knows nothing.
         */
        private const val OCTOPUS_SCORE_SPREAD = 4.0

        /**
         * No octopus until this much has been typed. After a single letter the
         * completions are essentially the unigram list, so every key lights up
         * with a word that says nothing about what the user is writing.
         */
        private const val OCTOPUS_MIN_PREFIX = 2

        /** The strip's words arrive order-ranked, not scored; this is the
         * synthetic step between consecutive places. */
        private const val OCTOPUS_STRIP_STEP = 0.5

        /**
         * How far down the ranked list a *correction* may still float. The
         * strip's own depth: past it the engine is no longer offering a
         * plausible reading of the buffer, it is offering its next-best guess,
         * and those read as noise sitting beside real completions.
         */
        private const val OCTOPUS_CORRECTION_DEPTH = 5

        /** Dense-mode filler sits this far below the worst ranked candidate, so
         * the words the engine actually ranked keep their own keys and the fan
         * only fills what is left. */
        private const val OCTOPUS_DENSE_GAP = 1.0

        /** The step between consecutive filler words, which preserves their
         * order inside the band [OCTOPUS_DENSE_GAP] opens. */
        private const val OCTOPUS_DENSE_STEP = 0.01
    }

    /**
     * @param composing the word currently being typed (may be empty)
     * @param previousWord last committed word, used for next-word prediction
     * @param phoneticLanguage the language of the phonetic layout [composing]
     *        was typed on (`"bn"` for Avro), when there is one: [composing] is
     *        then a romanization and the suggestions are that language's words
     * @param limit how many candidates to return
     * @param touch per-character tap positions (null entries fall back to
     *        the discrete adjacency model)
     * @param previousWord2 the word before [previousWord], for trigram context
     * @param recentWords the last few committed words, a topical recency bag
     *        for the reranker
     * @param allowRerank whether [reranker] may reorder the head of the list
     *        (never set on the synchronous main-thread call sites)
     * @param keys which letters each keystroke could have meant, on a keyboard
     *        that puts several on a key (null on every 1:1 board)
     * @param previousWord3 the word before [previousWord2], for the reranker's
     *        2-skip bigrams (#195)
     * @param phoneticSlots how many chips the strip shows, on a phonetic layout
     *        that mixes English in: the other script's best is pinned to the
     *        last of them rather than left somewhere off the end
     */
    fun suggest(
        composing: String,
        previousWord: String?,
        phoneticLanguage: String? = null,
        limit: Int = 5,
        touch: List<TouchPoint?>? = null,
        previousWord2: String? = null,
        recentWords: List<String> = emptyList(),
        allowRerank: Boolean = false,
        keys: KeySets? = null,
        previousWord3: String? = null,
        phoneticSlots: Int = DEFAULT_PHONETIC_SLOTS,
    ): List<String> {
        if (composing.isEmpty()) {
            return nextWords(previousWord, previousWord2, limit, previousWord3)
        }
        phoneticBackend(phoneticLanguage)?.let { backend ->
            val latinCompletions = {
                suggest(
                    composing, previousWord, phoneticLanguage = null, limit = limit, touch = touch,
                    previousWord2 = previousWord2, recentWords = recentWords, keys = keys,
                    previousWord3 = previousWord3,
                )
            }
            phoneticFixedStrip?.let { source ->
                return fixedPhoneticStrip(
                    backend, composing, previousWord, limit, phoneticSlots, source, latinCompletions,
                )
            }
            if (!phoneticMixing) return phoneticSuggestions(backend, composing, limit)
            return phoneticStrip(backend, composing, previousWord, limit, phoneticSlots, latinCompletions)
        }

        val lower = composing.lowercase()
        // On an ambiguous board the buffer holds anchor letters, not what the
        // user spelled: `adg` is three keypresses, not a word, and asking the
        // dictionary about it would answer a question nobody asked. Nothing is
        // ever "known as typed" there, so every reading the walk finds — all of
        // which come back at zero edits — reaches the strip.
        val ambiguous = keys?.isAmbiguous == true
        // A typo the corpus kept is not known: the strip has to show the fix
        // the space bar is about to make (#244).
        val known = !ambiguous && (
            (inDictionaries(lower) || userLexicon.contains(lower)) && !typoShadowed(lower, touch, keys) ||
                knownCompound(lower)
            )
        val merged = HashMap<String, Double>()

        // One fuzzy walk covers completions AND corrections over every trie
        // source. Corrections (edited paths) are admitted only when the typed
        // word is unknown, matching the historical gate; pure completions
        // (edits == 0) always participate.
        for (c in rankedFor(lower, limit, touch, keys)) {
            if (c.edits > 0 && known) continue
            merged.merge(c.word, c.score, ::maxOf)
        }
        // A word typed without its apostrophe — "thats" for that's, "cest"
        // for c'est — reads as the spelling that has one. The lists cannot
        // offer it themselves: they were tokenised at the apostrophe, so they
        // hold the fused misspelling as a word and the real spelling hardly
        // at all (#215, #240).
        if (!ambiguous) {
            apostropheReading(lower)?.let { merged.merge(it.spelling, it.score, ::maxOf) }
            // The same for a compound typed without its hyphen: "чтото" is
            // *что-то*, "wellpaid" is *well-paid*.
            hyphenReading(lower)?.let { (spelling, score) -> merged.merge(spelling, score, ::maxOf) }
        }
        // The prefix sources read the buffer literally, so they sit out an
        // ambiguous decode: `adg` is not the start of anybody's name, and
        // completing it would be answering about characters the user never
        // chose. (Reaching contacts and app names through the key sets wants
        // the walk, not a prefix probe — they are not walk sources yet.)
        if (!ambiguous) {
            for (s in contacts.complete(lower, limit)) {
                merged.merge(s.word, flatScore(s.frequency, CONTACT_WEIGHT), ::maxOf)
            }
            // Whole contact emails complete from their local part; short prefixes
            // are ignored so a single letter doesn't dump the address book.
            if (lower.length >= CONTACT_EMAIL_MIN_PREFIX) {
                for (email in contactEmails.complete(lower, limit)) {
                    merged.merge(email, flatScore(1, CONTACT_EMAIL_WEIGHT), ::maxOf)
                }
            }
            for (s in apps.complete(lower, limit)) {
                merged.merge(s.word, flatScore(s.frequency, APP_WEIGHT), ::maxOf)
            }
            if (!known) {
                for (split in splitCandidates(lower, touch)) {
                    merged.merge(split.text, split.score, ::maxOf)
                }
            }
        }

        // Context re-rank: a candidate the user has typed after [previousWord]
        // before (or that the seed pairs know as a follower) gets a bounded
        // log-space boost. Completions historically ignored context entirely;
        // this is one map hit per candidate on the async path.
        val prev = previousWord?.lowercase()
        if (prev != null) {
            val prev2 = previousWord2?.lowercase()
            for (entry in merged.entries) {
                val candidate = entry.key.lowercase()
                val count = maxOf(
                    userLexicon.bigramCount(prev, candidate),
                    // The two-word context is rarer and stronger evidence;
                    // its raw count rides the same bounded boost curve.
                    if (prev2 != null) {
                        userLexicon.trigramCount(prev2, prev, candidate) * 2
                    } else {
                        0
                    },
                    // Corpus counts are damped so a personal pair typed once
                    // outranks a population prior seen dozens of times.
                    ngramPack.bigramCount(prev, candidate) / PACK_COUNT_SCALE,
                    if (prev2 != null) {
                        ngramPack.trigramCount(prev2, prev, candidate) * 2 / PACK_COUNT_SCALE
                    } else {
                        0
                    },
                )
                val boost = when {
                    count > 0 -> minOf(
                        ln(1.0 + CONTEXT_BIGRAM_BETA * ln(1.0 + count)),
                        MAX_CONTEXT_BOOST,
                    )
                    englishSources && seedBigrams.follows(prev, candidate) -> SEED_CONTEXT_BOOST
                    else -> 0.0
                }
                if (boost > 0.0) entry.setValue(entry.value + boost)
            }
        }

        // Words recently typed in this very app get a small recency edge.
        if (contextWords.isNotEmpty()) {
            for (entry in merged.entries) {
                if (entry.key.lowercase() in contextWords) {
                    entry.setValue(entry.value + APP_RECENCY_BOOST)
                }
            }
        }

        // Register prior: chat-speak rises in messaging fields, sinks in
        // formal ones. Bounded like every other boost — it re-ranks, never
        // hides; "lol" still surfaces in an email if nothing else matches.
        if (register != Register.NEUTRAL) {
            val shift = if (register == Register.CASUAL) {
                CASUAL_INFORMAL_BOOST
            } else {
                -FORMAL_INFORMAL_DAMP
            }
            for (entry in merged.entries) {
                if (entry.key.lowercase() in RegisterVocabulary.informal) {
                    entry.setValue(entry.value + shift)
                }
            }
        }

        // The user's own say (#99), after every evidence-based boost so it
        // is worth the same wherever the word came from.
        applyRankOffsets(merged)

        // Contact words carry their own capitalization ("Wasi"), so the
        // same word can arrive in two cases; keep the better-scored one.
        // Folded through [WordKey] rather than a bare lowercase: a word list
        // and the platform dictionary can spell the same Bengali word with
        // and without the nukta composed, and those render as one word (#172).
        val byLower = HashMap<String, Pair<String, Double>>()
        for ((word, score) in merged) {
            val key = WordKey.of(word)
            val current = byLower[key]
            if (current == null || score > current.second) byLower[key] = word to score
        }
        val ranked = byLower.values
            .sortedWith(
                // Deterministic: score, then word — HashMap iteration order
                // must never decide a tie.
                compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }
            )
            .asSequence()
            .map { it.first }
            .filterNot(::suppressed)
            .take(maxOf(limit, RERANK_POOL))
            .toList()

        // Optional model pass over the head of the list; null keeps our order.
        val reordered = if (allowRerank && reranker !== CandidateReranker.NONE) {
            val pool = ranked.take(RERANK_POOL)
            reranker.rerank(
                RerankContext(composing, previousWord, recentWords, previousWord2, previousWord3), pool,
            )
                ?.filter { it in pool }
                ?.let { it + ranked.filterNot(it::contains) }
        } else {
            null
        }

        return (reordered ?: ranked)
            .take(limit)
            // Emails are stored verbatim; case-matching the typed prefix would
            // corrupt the address ("John" -> "John.doe@..."). Commit as stored.
            // Everything else is written the way the user writes it, then
            // re-cased to follow what they have typed so far — the typed
            // pattern still wins, so a deliberate "BOSTON" is not undone.
            .map { if (it.contains('@')) it else matchCase(composing, displayForm(it)) }
    }

    /** Adds each candidate's [rankOffsets] shift to its score in place. */
    private fun applyRankOffsets(merged: HashMap<String, Double>) {
        if (rankOffsets.isEmpty()) return
        for (entry in merged.entries) {
            val shift = rankOffset(entry.key)
            if (shift != 0.0) entry.setValue(entry.value + shift)
        }
    }

    /** Log-space score for the flat (non-trie) sources, comparable with the
     * beam's `logWeight + ln(1 + freq)` shape. */
    private fun flatScore(frequency: Int, weight: Int): Double =
        ln(1.0 + frequency.toDouble() * weight)

    /**
     * A distribution over the character most likely to be typed next, given the
     * word-so-far [prefix]. Each edge leaving [prefix]'s node scores the
     * frequency of the best word under it (the stored maxSubtree), so a letter
     * weighs what its likeliest word does, not the sum of every word it leads
     * to; the personal lexicon counts extra so learned habits bias the
     * keyboard. Values are normalised to 0..1 with the top letter at 1.0.
     * Empty when the prefix is blank or completes to nothing.
     *
     * Deliberately cheap and approximate — one descent and one edge read per
     * source, no completion walk — because it runs every keystroke and feeds
     * smart key-hit detection, which only nudges boundary taps.
     */
    fun nextLetterWeights(prefix: String): Map<Char, Float> {
        if (prefix.isEmpty()) return emptyMap()
        val lower = prefix.lowercase()
        val tally = HashMap<Char, Double>()
        val buf = ChildBuffer()
        fun fold(weight: Double, walkers: List<TrieWalker>) {
            for (walker in walkers) {
                var node = walker.root
                var found = true
                for (i in 0 until lower.length) {
                    node = walker.child(node, lower[i])
                    if (node < 0) {
                        found = false
                        break
                    }
                }
                if (!found) continue
                val count = walker.childrenInto(node, buf)
                for (i in 0 until count) {
                    val ch = buf.labels[i].lowercaseChar()
                    if (!ch.isLetter()) continue
                    val childNode = buf.nodes[i]
                    val freq = walker.maxSubtree(childNode)
                    if (freq > 0) {
                        tally.merge(ch, freq.toDouble() * weight, Double::plus)
                    }
                }
            }
        }
        fold(1.0, activeDictionary.walkers())
        fold(USER_WORD_WEIGHT.toDouble(), userLexicon.walkers())
        fold(USER_WORD_WEIGHT.toDouble(), systemDictionary.walkers())
        fold(CUSTOM_WORD_WEIGHT.toDouble(), customDictionary.walkers())
        val max = tally.values.maxOrNull() ?: return emptyMap()
        if (max <= 0.0) return emptyMap()
        return tally.mapValues { (it.value / max).toFloat() }
    }

    /**
     * The words that float over the keys: discussion #102's octopus, the
     * BlackBerry Z10's "In-Letter" prediction.
     *
     * Every word is hung off *the key you would press next to reach it*, so
     * flicking up on that key is the same gesture as pressing it, only
     * finished. That one rule covers all three kinds — a completion of what is
     * typed, a correction of it, and, on an empty buffer, a prediction of the
     * whole next word over its first letter — because all three are "the first
     * place this word stops agreeing with the buffer" (see [assignOctopus]).
     *
     * Deliberately *not* a branch inside [suggest]. It is off by default, and
     * folding it in would charge every user for it and put dense mode's
     * fan-out under `suggest`'s latency ceiling. Sparse mode is close to free
     * anyway: called right after [suggest] with the same `touch` and `keys`, it
     * reads the memoised ranked walk rather than repeating it.
     *
     * @param composing the word currently being typed (may be empty)
     * @param previousWord last committed word, for the empty-buffer case
     * @param previousWord2 the word before it, for trigram context
     * @param keys which letters each keystroke could have meant, on a board
     *        that puts several on a key
     * @param limit how many words may float at once — the density setting, 3
     *        for a Z10-sparse board and up to one per key
     * @param kinds which of [OctopusKind] the user allows on the keys
     * @param dense whether to fan the tries for keys the ranked candidates
     *        left empty; also lifts the score floor, since filling the board is
     *        the whole point of asking
     * @param pool the strip's own final words, best first — the candidates
     *        this hangs off keys before it considers any of its own
     * @param keyOf code point to the anchor code point of the key that types
     *        it, or -1 when this board cannot type it in one press
     * @param perKey how many words one key may carry, stacked (#136)
     */
    fun octopusWords(
        composing: String,
        previousWord: String?,
        previousWord2: String? = null,
        keys: KeySets? = null,
        limit: Int = 4,
        kinds: Set<OctopusKind> = OctopusKind.entries.toSet(),
        dense: Boolean = false,
        pool: List<String> = emptyList(),
        keyOf: (Int) -> Int,
        perKey: Int = 1,
    ): List<OctopusWord> {
        if (limit <= 0 || kinds.isEmpty()) return emptyList()
        // Dense mode means "fill the board", so the quietening floor that makes
        // sparse mode feel like a Z10 would be working against it.
        val spread = if (dense) Double.POSITIVE_INFINITY else OCTOPUS_SCORE_SPREAD

        if (composing.isEmpty()) {
            if (OctopusKind.NEXT_WORD !in kinds) return emptyList()
            val words = pool.ifEmpty { nextWords(previousWord, previousWord2, limit * 2) }
            return assignOctopus(
                "", ranked(words, OctopusKind.NEXT_WORD), keys, keyOf, limit, spread, perKey,
            )
        }

        val lower = composing.lowercase()
        // The strip's own words first, in the strip's own order. They are the
        // answer the whole engine worked out — the fuzzy walk plus every
        // context boost, the personal ranks, contacts and app names, and the
        // reranker — and the walk alone is only its raw first half. Ranking the
        // keys off that half was the bug: the board disagreed with the strip
        // beside it, and the strip was the one that was right.
        val candidates = ArrayList<OctopusCandidate>()
        for ((place, word) in pool.withIndex()) {
            // A word that carries on from the buffer is a completion; one that
            // does not is the engine offering a fix.
            val kind = if (word.lowercase().startsWith(lower)) {
                OctopusKind.COMPLETION
            } else {
                OctopusKind.CORRECTION
            }
            if (kind !in kinds) continue
            // A completion is worth floating however far down the list it sits:
            // it genuinely does carry on from what has been typed, and the key
            // it lands on is a key nothing better wanted. A correction is not.
            // It is the engine's guess at what was *meant*, and its twentieth
            // guess is noise — that is where "cop" came from beside four good
            // completions of "comp". So corrections only float from the head of
            // the list, the part the strip itself would have shown.
            if (kind == OctopusKind.CORRECTION && place >= OCTOPUS_CORRECTION_DEPTH) continue
            candidates.add(OctopusCandidate(word, -place * OCTOPUS_STRIP_STEP, kind))
        }
        // Nothing else is added. The raw walk was tried here, to fill the keys
        // the strip's words left bare, and it is what made the feature feel
        // useless: beside "help" and "held" it hung "helen", "helicopter" and
        // "helmet" on the free keys, and for "comp" it offered "cop" — a
        // correction — next to four good completions. Those are words the
        // ranking had already decided against.
        //
        // So the board goes quiet instead. The density is a cap on how many
        // words may float, never a quota to be met: three good words and room
        // for six means three words. The way to fill more keys is to look
        // further down the *same* ranked list, which is what the caller does by
        // asking [suggest] for a deeper one — every word in it has been through
        // the whole engine. Dense mode is the one exception, and it is asking
        // for the board to be filled in so many words.
        if (dense && OctopusKind.COMPLETION in kinds && lower.length >= OCTOPUS_MIN_PREFIX) {
            candidates.addAll(octopusFan(lower, candidates))
        }
        if (candidates.isEmpty()) return emptyList()
        return assignOctopus(composing, candidates, keys, keyOf, limit, spread, perKey)
            // Written the way the user writes it, then re-cased to follow what
            // they have typed — the same treatment the strip gives, so the word
            // drawn over the key is character-for-character the word that will
            // be committed and the two-tone split lands on the right glyph.
            .map { it.copy(word = matchCase(composing, displayForm(it.word))) }
    }

    /** An order-ranked list as scored candidates, spaced to clear the floor. */
    private fun ranked(words: List<String>, kind: OctopusKind): List<OctopusCandidate> =
        words.mapIndexed { place, word ->
            OctopusCandidate(word, -place * OCTOPUS_STRIP_STEP, kind)
        }

    /**
     * Dense mode's filler: the best word under every key that could extend
     * [lower], scored below everything [ranked] already claimed so the words
     * the engine really ranked keep their own keys and the fan only fills what
     * is left.
     */
    private fun octopusFan(
        lower: String,
        ranked: List<OctopusCandidate>,
    ): List<OctopusCandidate> {
        val best = HashMap<String, Double>()
        for (source in walkSources()) {
            for ((_, found) in octopusTrieFan(source.walker, lower)) {
                if (suppressed(found.word)) continue
                // The beam's own shape, so a filler word from the personal
                // lexicon outranks a rarer one from the bundled list for the
                // same reason it would in a walk.
                val score = source.logWeight + ln(1.0 + found.frequency.toDouble())
                best.merge(found.word, score, ::maxOf)
            }
        }
        if (best.isEmpty()) return emptyList()
        val claimed = ranked.mapTo(HashSet()) { it.word.lowercase() }
        val ceiling = (ranked.minOfOrNull { it.score } ?: 0.0) - OCTOPUS_DENSE_GAP
        return best.entries
            .filterNot { it.key.lowercase() in claimed }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .mapIndexed { place, entry ->
                OctopusCandidate(
                    entry.key, ceiling - place * OCTOPUS_DENSE_STEP, OctopusKind.COMPLETION,
                )
            }
    }

    /**
     * The inverse of a split: the previous word and the word being composed
     * concatenate into something more plausible than the parts — "some" +
     * "thing" -> "something". Chip-only (never an autocorrect: it rewrites
     * text already committed to the field) and deliberately conservative:
     * the joined word must be reasonably common and beat the rarer part by a
     * clear margin, so "a" + "nd" doesn't offer "and" on every stumble.
     */
    fun joinCandidate(previousWord: String?, composing: String): String? {
        val prev = previousWord?.lowercase() ?: return null
        if (WordContext.isSentinel(prev)) return null
        val lower = composing.lowercase()
        if (lower.length < 2 || prev.isEmpty()) return null
        if (!prev.all { it.isLetter() } || !lower.all { it.isLetter() }) return null
        val joined = prev + lower
        if (joined.length > JOIN_MAX_LENGTH || suppressed(joined)) return null
        fun freqOf(word: String) = maxOf(
            dictionaryFrequencyOf(word),
            weighted(userLexicon.frequencyOf(word), USER_WORD_WEIGHT),
        )
        val joinedFreq = freqOf(joined)
        if (joinedFreq < JOIN_MIN_FREQ) return null
        val rarerPart = minOf(freqOf(prev), freqOf(lower))
        if (joinedFreq * JOIN_CONFIDENCE <= rarerPart) return null
        return joined
    }

    /** One reading of a typed run as two words; [dropped] when a boundary
     * letter had to go to get there. */
    private class SplitReading(val text: String, val score: Double, val dropped: Boolean)

    /**
     * Missing-space fixes: "ofthe" → "of the", scored by the rarer half so
     * two genuinely common words outrank a coincidental split.
     *
     * Also covers the fat-fingered spacebar: a stray [spaceAdjacentKeys]
     * letter between two known words ("amibtomake") may have been a space
     * press that landed on the bottom row, so the split that drops it is
     * offered too — at a discount that mirrors the walk's deletion cost, so
     * an exact split of the same material always outranks a dropped-letter
     * reading of it. That reading is a claim about where a finger landed,
     * and it is only made when the tap says so ([leansToSpacebar]): the word
     * lists have gaps ("config", "inbox"), and with no tap to consult the
     * reading fired on every unlisted word that happened to break into two
     * listed ones around a bottom-row letter ("co fig", "in ox"). A letter
     * with no tap behind it — a glide, a hardware key, pasted text — was not
     * fat-fingered onto the bottom row either.
     *
     * A learned word anchors a half only once it is established: a spelling
     * seen once is not evidence that the user meant it here.
     */
    private fun splitCandidates(word: String, touch: List<TouchPoint?>?): List<SplitReading> {
        if (word.length < 4 || !word.all { it.isLetter() }) return emptyList()
        val results = ArrayList<SplitReading>()
        val taps = touch?.takeIf { it.size == word.length }
        fun freqOf(part: String): Int {
            val learned = if (userLexicon.isEstablished(part, learnedWordMinCount)) {
                userLexicon.frequencyOf(part) * USER_WORD_WEIGHT
            } else {
                0
            }
            return maxOf(dictionaryFrequencyOf(part), learned)
        }
        for (i in 1 until word.length) {
            val left = word.substring(0, i)
            val leftFreq = freqOf(left)
            if (leftFreq <= 0) continue
            val right = word.substring(i)
            // A one-letter second half is not a word the user meant to
            // separate, it is what a corpus tokenised at the apostrophe left
            // behind. The downloadable English list has `'s` as its sixth
            // commonest token and a bare `s` at 110,000, `t` at 72,000 and
            // `don` at four million; the Italian one has `s` at 28,000. With
            // any of them loaded, "thats" reads as `that` + `s` and comes
            // back as "that s" — which is what #240 reported after adding
            // the contraction to their dictionary by hand. No split in any
            // language ends on a single letter, while the left half must
            // stay open to one ("alot" is *a lot*), so the rule goes here.
            val rightFreq = if (right.length >= 2) freqOf(right) else 0
            if (rightFreq > 0) {
                val score = ln(1.0 + minOf(leftFreq, rightFreq) * WEIGHT_SPLIT)
                results.add(SplitReading("$left $right", score, dropped = false))
            }
            // Boundary char dropped: both halves must be real words of some
            // substance — single-letter halves ("a", "i") explain nearly any
            // string and would fire on every stumble.
            if (i + 1 < word.length - 1 && word[i] in spaceAdjacentKeys && left.length >= 2 &&
                leansToSpacebar(word[i], taps?.get(i))
            ) {
                val tail = word.substring(i + 1)
                if (tail.length >= 2) {
                    val tailFreq = freqOf(tail)
                    if (tailFreq > 0) {
                        // A key this hand is known to hit for the space bar
                        // prices the dropped letter closer to an exact split.
                        val slip = editHabitsField.spaceSlip(word[i]) / EditHabits.MAX_SHRINK
                        val weight = WEIGHT_SPLIT_DROPPED + (WEIGHT_SPLIT - WEIGHT_SPLIT_DROPPED) * slip
                        val score = ln(1.0 + minOf(leftFreq, tailFreq) * weight)
                        results.add(SplitReading("$left $tail", score, dropped = true))
                    }
                }
            }
        }
        return results
    }

    /**
     * Whether the tap that typed [ch] landed low on its key, toward the
     * spacebar, by at least [SPACE_SLIP_MIN_DROP] key widths. A finger aimed
     * at the spacebar that caught the row above lands near that row's bottom
     * edge; one aimed at the letter lands around its centre. Centres are the
     * touch model's, so a hand whose taps sit low on every key (adapt to
     * taps) is measured against where it actually types. False with no
     * model, no tap for this letter, or a letter the model does not know.
     */
    private fun leansToSpacebar(ch: Char, tap: TouchPoint?): Boolean {
        if (tap == null) return false
        val center = touchModelField?.center(ch) ?: return false
        return tap.y - center.y >= SPACE_SLIP_MIN_DROP
    }

    /**
     * Whether [left] followed by [right] is a pair the keyboard has seen —
     * in the user's own typing or the language's n-gram pack.
     */
    private fun knownPhrase(left: String, right: String): Boolean =
        userLexicon.bigramCount(left, right) > 0 || ngramPack.bigramCount(left, right) > 0

    /**
     * The fixed-spelling map's answer for exactly [composing], or null.
     *
     * The composing preview calls this so a listed spelling shows up while the
     * word is still being typed — "tmr" reads তোমার at the r, not at the
     * space. Only the map layer, deliberately: it is keyed on the whole buffer,
     * so it either hits or it doesn't and the preview never flickers between
     * dictionary siblings on its way to the end of a word. It is also the layer
     * that wins [phoneticSuggestions] outright, so what the preview shows is
     * what a space would commit.
     *
     * @param languageId the language of the phonetic layout being typed on
     */
    fun phoneticSpelling(languageId: String, composing: String): String? =
        phoneticBackend(languageId)?.spellings?.lookup(composing)?.firstOrNull { !suppressed(it) }

    private fun phoneticSuggestions(backend: PhoneticBackend, composing: String, limit: Int): List<String> {
        val spellings = backend.spellings
        val index = backend.index
        val phonetic = backend.scheme.transliterate(composing)
        val ordered = LinkedHashSet<String>()
        // Listed spellings win outright — loanwords like "keyboard" → কিবোর্ড,
        // and chat shorthand like "tmr" → তোমার whose vowels were never typed.
        // Neither is reachable from the rules, so the map goes first.
        ordered.addAll(spellings.lookup(composing))
        // Phonetic siblings from the dictionary (আছি for "asi") outrank the
        // literal transliteration only when clearly more common — the commit
        // path takes the first entry, and the preview showed the literal, so
        // a near-tie sibling silently replacing it reads as a bug (হলো
        // becoming হল). A literal that isn't a dictionary word at all always
        // yields to siblings.
        // Switched off for the language, the literal always leads and the
        // siblings are only offered.
        val siblings = index.lookup(composing)
        val literalFreq = index.frequencyOf(phonetic)
        val topSiblingFreq = siblings.firstOrNull()?.let { index.frequencyOf(it) } ?: 0
        if (backend.scheme.languageId in phoneticSiblingsOff ||
            (literalFreq > 0 && topSiblingFreq < literalFreq * SIBLING_CONFIDENCE)
        ) {
            ordered.add(phonetic)
        }
        ordered.addAll(siblings)
        // The literal reading, then whatever else the rules think the spelling
        // could mean. For a scheme whose rules are never undecided (Avro) that
        // is the literal again and adds nothing; for one that may be running
        // with no word list behind it, the other readings are all there is to
        // offer in place of the siblings a dictionary would have found.
        ordered.add(phonetic)
        ordered.addAll(backend.scheme.variants(composing))
        return ordered.asSequence().filterNot(::suppressed).take(limit).toList()
    }

    /**
     * What a space commits for [composing] on [languageId]'s phonetic layout,
     * and what it would have committed in the other script.
     *
     * @param output the text the space bar writes
     * @param script the script [output] is in
     * @param alternate the other script's best, or null when there is none
     *        worth a flip (see [PhoneticScriptVerdict.Verdict.contested])
     */
    class PhoneticCommit(val output: String, val script: PhoneticScript, val alternate: String?)

    /**
     * The commit for [composing], without the strip around it: no fuzzy walk,
     * so it is cheap enough for the composing preview and a synchronous commit.
     * The head of [suggest]'s list for the same buffer is always [PhoneticCommit.output]
     * — both ask [scriptVerdict] — which is what lets the preview show it early.
     */
    fun phoneticCommit(languageId: String, composing: String, previousWord: String? = null): PhoneticCommit? {
        val backend = phoneticBackend(languageId) ?: return null
        if (composing.isEmpty()) return null
        val native = phoneticSuggestions(backend, composing, 1).firstOrNull()
            ?: backend.scheme.transliterate(composing)
        if (!phoneticMixing) return PhoneticCommit(native, PhoneticScript.NATIVE, alternate = null)
        val verdict = scriptVerdict(backend, composing, previousWord)
        val latin = latinForm(composing)
        return if (verdict.script == PhoneticScript.LATIN) {
            PhoneticCommit(latin, PhoneticScript.LATIN, native.takeIf { verdict.contested })
        } else {
            PhoneticCommit(native, PhoneticScript.NATIVE, latin.takeIf { verdict.contested })
        }
    }

    /** Which script a space would commit [composing] in; see [phoneticCommit]. */
    fun phoneticScript(languageId: String, composing: String, previousWord: String? = null): PhoneticScript {
        val backend = phoneticBackend(languageId) ?: return PhoneticScript.NATIVE
        if (!phoneticMixing || composing.isEmpty()) return PhoneticScript.NATIVE
        return scriptVerdict(backend, composing, previousWord).script
    }

    /**
     * The English a space would commit for [composing], or null when it would
     * commit the layout's own script. The composing preview's question, asked
     * on the main thread at every keystroke, so it stops at the verdict and
     * never builds the native list [phoneticCommit] needs for its alternate.
     */
    fun phoneticLatinPreview(languageId: String, composing: String, previousWord: String? = null): String? =
        if (phoneticScript(languageId, composing, previousWord) == PhoneticScript.LATIN) {
            latinForm(composing)
        } else {
            null
        }

    /**
     * The user took [script] for [spelling] where the verdict had chosen the
     * other one: the chip, or the backspace that flips a commit. Remembered
     * per spelling, so the same word is not got wrong the same way twice.
     */
    fun recordScriptChoice(languageId: String, spelling: String, script: PhoneticScript) {
        scriptChoices.record(languageId, spelling, script)
        generation.incrementAndGet()
    }

    /** The buffer as an English word: as typed, or in the case the user's own lexicon keeps it in. */
    private fun latinForm(composing: String): String = displayForm(composing)

    @Volatile
    private var scriptVerdictCache: Pair<String, PhoneticScriptVerdict.Verdict>? = null

    private fun scriptVerdict(
        backend: PhoneticBackend,
        composing: String,
        previousWord: String?,
    ): PhoneticScriptVerdict.Verdict {
        if (!phoneticAutoEnglish) return NATIVE_UNCONTESTED
        return readScript(backend, composing, previousWord)
    }

    /**
     * Which script [composing] reads as, whether or not a space is allowed to
     * act on it: [scriptVerdict] without the auto-English gate. The fixed strip
     * asks it to decide which language leads its suggestions, which is a
     * question about the word, not about what the space bar may do.
     */
    private fun readScript(
        backend: PhoneticBackend,
        composing: String,
        previousWord: String?,
    ): PhoneticScriptVerdict.Verdict {
        // A romanization is letters. Anything else in the buffer is the
        // scheme's own notation, and so is a capital past the first: Avro's T,
        // D, N and O are letters in their own right, and nobody reaches for
        // shift in the middle of an English word.
        if (!composing.all { it in 'a'..'z' || it in 'A'..'Z' }) return NATIVE_UNCONTESTED
        if (composing.drop(1).any { it.isUpperCase() }) return NATIVE_UNCONTESTED
        val cacheKey = "${backend.scheme.languageId}:${generation.get()}:$composing:${previousWord.orEmpty()}"
        scriptVerdictCache?.let { (key, verdict) -> if (key == cacheKey) return verdict }
        val verdict = PhoneticScriptVerdict.decide(scriptEvidence(backend, composing, previousWord))
        scriptVerdictCache = cacheKey to verdict
        return verdict
    }

    private fun scriptEvidence(
        backend: PhoneticBackend,
        composing: String,
        previousWord: String?,
    ): PhoneticScriptVerdict.Evidence {
        val lower = composing.lowercase()
        val languageId = backend.scheme.languageId
        val index = backend.index
        // English: the bundled list, or a word the user has taught the
        // keyboard under English. A word on the never-suggest list is not one
        // the keyboard volunteers in either script.
        val latin = when {
            suppressed(lower) -> null
            dictionary.contains(lower) ->
                PhoneticScriptVerdict.commonness(dictionary.frequencyOf(lower), englishMaxFrequency())
            userLexicon.contains(lower) && userLexicon.languageOf(lower) == EN -> LEARNED_LATIN_COMMONNESS
            else -> null
        }
        // The language's own: a listed spelling or the rules' reading being a
        // dictionary word is exact; a fold sibling is a looser claim.
        val forms = backend.spellings.lookup(composing).filterNot(::suppressed)
        val loanword = forms.isNotEmpty() && backend.spellings.isLoanword(composing)
        val exact = maxOf(
            forms.maxOfOrNull { index.frequencyOf(it) } ?: 0,
            index.frequencyOf(backend.scheme.transliterate(composing)),
        )
        val folded = index.matchStrength(composing)
        val native = when {
            exact > 0 || folded > 0 -> maxOf(
                PhoneticScriptVerdict.commonness(exact, index.maxFrequency),
                PhoneticScriptVerdict.foldOnly(PhoneticScriptVerdict.commonness(folded, index.maxFrequency)),
            )
            forms.isNotEmpty() && !loanword -> PhoneticScriptVerdict.UNRANKED_LISTED
            else -> null
        }
        val shares = if (fieldDetectionShift > 0.0) fieldMix.shares() else null
        val context = shares?.let {
            (it.shareOf(EN) - it.shareOf(languageId)) * it.ramp *
                (fieldDetectionShift / FIELD_SHIFT_BALANCED).coerceAtMost(1.0)
        } ?: 0.0
        val prev = previousWord?.lowercase()?.takeIf { it.isNotEmpty() }
        // Which way the word before pulls. A pair English writes (`i am`,
        // `how are`) against a pair the language's own context knows for the
        // reading it would commit; both known and they cancel. The sentence
        // start counts only through the user's own habit: the bundled openers
        // list `Are` and `So`, and আরে opens a Bangla message as readily.
        var pair = 0
        if (prev != null && latin != null && englishPair(prev, lower)) pair++
        if (prev != null && native != null) {
            val reading = phoneticSuggestions(backend, composing, 1).firstOrNull()
            if (reading != null && nativePair(prev, reading)) pair--
        }
        return PhoneticScriptVerdict.Evidence(
            latinCommonness = latin,
            nativeCommonness = native,
            loanword = loanword,
            contextDelta = context,
            choice = scriptChoices.choiceFor(languageId, lower),
            pair = pair,
            afterEnglish = prev != null && !WordContext.isSentinel(prev) &&
                dictionary.contains(prev) && !backend.spellings.isLoanword(prev),
            pronoun = composing == "I",
        )
    }

    private fun englishPair(prev: String, word: String): Boolean {
        if (userLexicon.bigramCount(prev, word) > 0) return true
        if (WordContext.isSentinel(prev)) return false
        return seedBigrams.nextWords(prev).any { it.equals(word, ignoreCase = true) } ||
            secondaryEnglishNgramPack.bigramCount(prev, word) > 0
    }

    private fun nativePair(prev: String, reading: String): Boolean =
        userLexicon.bigramCount(prev, reading) > 0 ||
            (!WordContext.isSentinel(prev) && ngramPack.bigramCount(prev, reading) > 0)

    @Volatile
    private var englishMaxCache: Pair<WordSource, Int>? = null

    /** The top frequency of the English list, the scale its words are read against. */
    private fun englishMaxFrequency(): Int {
        val source = dictionary
        englishMaxCache?.let { (cached, max) -> if (cached === source) return max }
        val max = source.walkers().maxOfOrNull { it.maxSubtree(it.root) } ?: 0
        englishMaxCache = source to max
        return max
    }

    /**
     * The strip of a phonetic layout that English is mixed into. Its head is
     * always what a space commits ([phoneticCommit]); the rest follows the
     * language the field is being written in.
     *
     *  - The layout's own script leads and the field is not English: the list
     *    is what it always was, with the buffer as typed pinned to the last
     *    visible chip. One tap writes any word in Latin letters, and the
     *    siblings a Bengali typist actually reaches for keep their places.
     *  - The field has turned English but the commit has not (auto-English is
     *    off): the native word stays first, because that is what the space bar
     *    does, and English takes the rest.
     *  - English leads: the buffer, its completions, and the native word pinned
     *    where the Latin one would have been.
     *
     * [latinCompletions] is the ordinary walk, and is only run where English
     * has chips to fill — a Bengali sentence costs what it always cost.
     */
    private fun phoneticStrip(
        backend: PhoneticBackend,
        composing: String,
        previousWord: String?,
        limit: Int,
        slots: Int,
        latinCompletions: () -> List<String>,
    ): List<String> {
        // Never empty: with every reading on the never-suggest list the rules'
        // own is still what a space commits, and the head has to say so.
        val native = phoneticSuggestions(backend, composing, limit)
            .ifEmpty { listOf(backend.scheme.transliterate(composing)) }
        val literal = latinForm(composing)
        val latinLeads = scriptVerdict(backend, composing, previousWord).script == PhoneticScript.LATIN
        val pin = (slots - 1).coerceIn(1, maxOf(1, limit - 1))
        if (!latinLeads && detectedLanguageId() != EN) {
            return pinned(native, literal, pin).take(limit)
        }
        val latin = LinkedHashSet<String>()
        latin.add(literal)
        for (word in latinCompletions()) {
            if (!word.equals(literal, ignoreCase = true)) latin.add(word)
        }
        val ordered = if (latinLeads) {
            val top = native.firstOrNull()
            val head = if (top == null) latin.toList() else pinned(latin.toList(), top, pin)
            head + native.drop(1)
        } else {
            native.take(1) + latin + native.drop(1)
        }
        return ordered.distinct().take(limit)
    }

    /**
     * The strip of a phonetic layout set to keep its first two chips still:
     * the buffer as typed in Latin letters, then the rules' own reading of it
     * (Avro's `ami` → আমি, letter for letter, before any dictionary has a
     * say), then [source]'s suggestions. The two never trade places and never
     * leave, so a tap on the left is always the English and the one beside it
     * always the transliteration.
     *
     * The head is therefore not what a space commits here; the caller asks
     * [phoneticCommit] for that instead of reading it off the list.
     */
    private fun fixedPhoneticStrip(
        backend: PhoneticBackend,
        composing: String,
        previousWord: String?,
        limit: Int,
        slots: Int,
        source: PhoneticStripSource,
        latinCompletions: () -> List<String>,
    ): List<String> {
        val literal = latinForm(composing)
        val reading = backend.scheme.transliterate(composing)
        val fixed = listOf(literal, reading).distinct()
        fun isFixed(word: String) = word == reading || word.equals(literal, ignoreCase = true)
        val want = limit + fixed.size
        val native = { phoneticSuggestions(backend, composing, want).filterNot(::isFixed) }
        val english = { englishCompletions(composing, want, latinCompletions).filterNot(::isFixed) }
        val rest = when (source) {
            PhoneticStripSource.NATIVE -> native()
            PhoneticStripSource.ENGLISH -> english()
            PhoneticStripSource.SMART -> {
                val englishLeads = detectedLanguageId() == EN ||
                    readScript(backend, composing, previousWord).script == PhoneticScript.LATIN
                val (lead, other) = if (englishLeads) english() to native() else native() to english()
                // The other language's best on the last chip on screen, as the
                // ordinary mixed strip pins it; with a single free chip there
                // is no room, and the leader keeps it.
                val visible = slots - fixed.size
                val top = other.firstOrNull()
                if (top != null && visible >= 2) {
                    pinned(lead, top, visible - 1) + other.drop(1)
                } else {
                    lead + other
                }
            }
        }
        return (fixed + rest).distinct().take(limit)
    }

    /**
     * English words for [composing] typed on a phonetic layout: [walk], the
     * ordinary fuzzy walk, and — when English is not among the layout's
     * secondary languages, so the walk has only the user's own words to read —
     * the bundled English list by prefix, so that picking English for the
     * fixed strip is never picking an empty one.
     */
    private fun englishCompletions(composing: String, limit: Int, walk: () -> List<String>): List<String> {
        // Avro's own notation (`,,` for a hasant, `^` for a chandrabindu) and
        // digits spell no English word; asking the dictionary would only
        // answer a question nobody typed.
        if (!composing.all { it in 'a'..'z' || it in 'A'..'Z' }) return emptyList()
        val words = LinkedHashSet<String>()
        words.addAll(walk())
        if (!phoneticMixing) {
            for (s in dictionary.complete(composing.lowercase(), limit)) {
                words.add(matchCase(composing, displayForm(s.word)))
            }
        }
        return words.asSequence().filterNot(::suppressed).take(limit).toList()
    }

    /** [list] with [item] at index [at], or at the end when the list is shorter. */
    private fun pinned(list: List<String>, item: String, at: Int): List<String> {
        val rest = list.filterNot { it == item }
        val index = at.coerceAtMost(rest.size)
        return rest.subList(0, index) + item + rest.subList(index, rest.size)
    }

    private fun nextWords(
        previousWord: String?,
        previousWord2: String?,
        limit: Int,
        previousWord3: String? = null,
    ): List<String> {
        val prev = previousWord?.lowercase() ?: return emptyList()
        val ordered = LinkedHashSet<String>()
        // Most specific first: the two-word context, when known, beats the
        // bigram tail ("I was" -> "going" over everything "was" alone knows).
        previousWord2?.lowercase()?.let { prev2 ->
            ordered.addAll(userLexicon.nextWordsAfter(prev2, prev, limit))
        }
        // Learned bigrams next — the user's own phrases always beat the
        // bundled seed pairs, which only cover the cold start.
        ordered.addAll(userLexicon.nextWords(prev, limit))
        // A contact's name chains through the strip: "Wasi" offers "Mollik".
        ordered.addAll(contacts.nextWords(prev))
        // The user's own gappy habits (#195): what has followed the word two
        // back one word later, then the word three back two words later —
        // "how can someone" still offers "help". Personal, so above the
        // corpus and the seeds; gappy, so below every direct follower.
        previousWord2?.lowercase()?.let { prev2 ->
            ordered.addAll(userLexicon.skip1Followers(prev2, limit))
        }
        previousWord3?.lowercase()?.let { prev3 ->
            ordered.addAll(userLexicon.skip2Followers(prev3, limit))
        }
        // Corpus n-grams (downloaded pack): below everything personal, above
        // the bundled seeds they supersede. The trigram context first.
        if (!ngramPack.isEmpty) {
            previousWord2?.lowercase()?.let { prev2 ->
                ordered.addAll(ngramPack.nextWordsAfter(prev2, prev, limit))
            }
            ordered.addAll(ngramPack.nextWords(prev, limit))
        }
        // Seed bigrams are English pairs; they only cold-start English modes.
        if (englishSources) ordered.addAll(seedBigrams.nextWords(prev))
        // English riding as a secondary: after one of its words the primary's
        // pack has nothing to say (`hello` on Avro), so English's own context
        // answers instead. Only after a word English owns, so it never talks
        // over the primary's followers.
        if (englishAsSecondary && !englishSources && dictionary.contains(prev)) {
            val englishPack = secondaryEnglishNgramPack
            if (!englishPack.isEmpty) {
                previousWord2?.lowercase()?.let { prev2 ->
                    ordered.addAll(englishPack.nextWordsAfter(prev2, prev, limit))
                }
                ordered.addAll(englishPack.nextWords(prev, limit))
            }
            ordered.addAll(seedBigrams.nextWords(prev))
        }
        // Skip-gram rescue: an unknown prev (a just-typed name, a typo) has
        // no followers anywhere and the strip would go quiet. Treat it as
        // transparent and backfill from the word before it — "met Priya"
        // still offers what tends to follow "met". Appended after every
        // direct source, so genuine followers of prev always rank first.
        if (ordered.size < limit && previousWord2 != null) {
            val prev2 = previousWord2.lowercase()
            ordered.addAll(userLexicon.nextWords(prev2, limit))
            if (!ngramPack.isEmpty) ordered.addAll(ngramPack.nextWords(prev2, limit))
        }
        var result = ordered.asSequence()
            .filterNot(::suppressed)
            // Belt and braces: the sentence-start sentinel is context, never
            // an offer — nothing should ever have learned it as a follower.
            .filterNot { WordContext.isSentinel(it) }
            .take(limit)
            .toList()
        // Formal fields: chat-speak yields its slot when anything else is
        // on offer (order-based here — this path carries no scores).
        if (register == Register.FORMAL) {
            result = result.sortedBy { it.lowercase() in RegisterVocabulary.informal }
        }
        // The user's rank adjustments (#99), order-based like the register
        // above: a lifted follower leads, a sunk one trails, ties keep their
        // source order.
        if (rankOffsets.isNotEmpty()) {
            result = result.sortedByDescending { rankOffsets[it.lowercase()] ?: 0 }
        }
        // Last, so nothing above has to reason about case: the ordering, the
        // sentinel filter and the blacklist all work on keys. The set above
        // holds keys from the lexicon beside surface spellings from the
        // contacts ("boston" and "Boston"), and restoring the capital makes
        // them one word — so the fold comes after the restore (#172).
        return result.map(::displayForm).distinctBy(WordKey::of)
    }

    /**
     * What should happen to a word on commit.
     *
     * [apply] is a silent replacement, the only outcome autocorrect had before.
     * [offer] is a candidate that came close to that bar without clearing it,
     * for the caller to put on the strip as a chip. The two are never both set:
     * a correction confident enough to apply is not also asked about.
     *
     * An offer costs a wrong guess nothing, which is the point. The silent
     * gate has to be conservative because getting it wrong rewrites what
     * somebody wrote, so everything just short of it used to be thrown away.
     */
    data class CorrectionDecision(
        val apply: String? = null,
        val offer: String? = null,
        /**
         * How far [apply] cleared the confidence gate, 0 (sitting exactly on
         * the bar) to 1 (two independent sources naming the same word, or a
         * margin far past what was asked for). 0 when nothing is applied.
         */
        val certainty: Double = 0.0,
        /**
         * How far [apply] strays from what was typed, 0 (a neighbouring-key
         * slip in a short word) to 1 (a letter no fat finger explains, or a
         * word split in two). 0 when nothing is applied.
         */
        val complexity: Double = 0.0,
    ) {
        /**
         * How unremarkable this correction is: near 1 for one the user will
         * not think twice about, near 0 for one worth a second look.
         *
         * The two halves are independent reasons to look twice, so they
         * multiply rather than average: a sure fix to a plain typo is
         * obvious, and either an unsure one *or* a far-reaching one stops
         * being obvious no matter how good the other half is.
         *
         * 0 when nothing was applied, which is what the callers that only
         * ever ask about a correction that fired want anyway.
         */
        val obviousness: Double get() = certainty * (1.0 - complexity)
    }

    /**
     * The correction [word] should be silently replaced with on commit, or
     * null when it should be left alone. [decideCorrection] without the offer.
     */
    fun shouldAutocorrect(
        word: String,
        touch: List<TouchPoint?>? = null,
        timingMultiplier: Double = 1.0,
    ): String? = decideCorrection(word, touch, timingMultiplier).apply

    /**
     * What to do with [word], which a space or enter is about to commit.
     *
     * Nothing at all when the word is known (a known word — bundled, imported
     * or learned — is never corrected away). Otherwise a candidate is applied
     * only if the dictionaries and the user's lexicon independently agree on
     * it, or its score beats the runner-up by `AutocorrectSettings.confidence`. A
     * candidate that clears [OFFER_MARGIN_FRACTION] of that same margin
     * without reaching it is offered instead.
     *
     * @param word the composing word a space or enter is about to commit
     * @param touch per-character tap positions, as in [suggest]
     * @param timingMultiplier scales the confidence gate from the typing
     *        rhythm of this word: fast, sloppy bursts (< 1.0) correct more
     *        eagerly, slow deliberate typing (> 1.0) demands near-certainty.
     *        1.0 — the default, and always when the setting is off — keeps
     *        the gate exactly at the slider value.
     * @param previousWord the word before this one, for the one case that
     *        needs it: a fix the user has taught for a spelling that is itself
     *        a word ("form" → "from"), which only fires when the context
     *        agrees. Null — a sentence start, or a caller without it — never
     *        applies such a fix.
     */
    fun decideCorrection(
        word: String,
        touch: List<TouchPoint?>? = null,
        timingMultiplier: Double = 1.0,
        previousWord: String? = null,
        keys: KeySets? = null,
    ): CorrectionDecision {
        val lower = word.lowercase()
        if (lower.length < 3) return NO_CORRECTION
        // An all-caps word is a deliberate acronym or shout, not a typo of a
        // lowercase word — don't "correct" it away when the user asked us not to.
        if (skipAllCapsAutocorrect && isAllCaps(word)) return NO_CORRECTION
        val ordinary = decideOrdinary(word, lower, touch, timingMultiplier, keys)
        return withTaughtFix(word, lower, previousWord, ordinary)
    }

    /**
     * Lays what the user has taught about [lower] over the engine's own
     * decision [ordinary].
     *
     * A fix made by hand [CorrectionMemory.APPLY_AT] times is applied outright;
     * one made once is offered, and nothing *else* is applied over it — the
     * user has shown what they meant, and a different guess is the mistake
     * they were correcting. The engine's own decision stands when it agrees.
     * A spelling that is itself a word gives way only after
     * [CorrectionMemory.APPLY_KNOWN_AT] fixes and with the bigram context as a
     * second witness ([RevisionAdvisor.precedes]); a pair the user has since
     * undone is the penalty memory's to hold back, and is left to it.
     */
    private fun withTaughtFix(
        word: String,
        lower: String,
        previousWord: String?,
        ordinary: CorrectionDecision,
    ): CorrectionDecision {
        val taught = correctionMemory.fixFor(lower) ?: return ordinary
        val fixed = taught.fixed
        if (fixed == lower || fixed.split(' ').any { suppressed(it) }) return ordinary
        if (correctionStats.penalty(lower, fixed) != CorrectionStats.Penalty.NONE) return ordinary
        val knownTyped = inDictionaries(lower) ||
            userLexicon.isEstablished(lower, learnedWordMinCount) ||
            contacts.contains(lower) || apps.contains(lower)
        fun applied() = CorrectionDecision(
            apply = matchCase(word, fixed),
            // Two fixes is sure enough to act on and unsure enough to show the
            // undo chip for; the certainty climbs with every fix after.
            certainty = taught.count.toDouble() / (taught.count + 1),
            complexity = complexityOfEdit(lower, fixed),
        )
        if (knownTyped) {
            if (taught.count < CorrectionMemory.APPLY_AT ||
                !contextAdvisor.precedes(previousWord, lower, fixed)
            ) {
                return ordinary
            }
            return if (taught.count >= CorrectionMemory.APPLY_KNOWN_AT) {
                applied()
            } else {
                CorrectionDecision(offer = matchCase(word, fixed))
            }
        }
        if (taught.count >= CorrectionMemory.APPLY_AT) return applied()
        if (ordinary.apply?.equals(fixed, ignoreCase = true) == true) return ordinary
        return CorrectionDecision(offer = matchCase(word, fixed))
    }

    /**
     * Whether the word lists hold [lower] only as the accentless spelling of a
     * far commoner word (#200).
     *
     * Frequency lists come from real text, and real text is full of Polish
     * typed without its accents. The Polish list holds `juz` 12,683 times
     * beside `już` 690,940 times, and `sie` beside `się`. As known words those
     * spellings stopped the fix from ever firing. A spelling that its accented
     * twin outnumbers [ACCENT_SHADOW_RATIO] times over is that twin typed
     * without the long-press. Under the ratio both spellings are words people
     * mean: Polish `ze` and `że`, Spanish `mas` and `más`.
     *
     * A word in Android's personal dictionary was put there by the user, and
     * is never shadowed. [customDictionary] earns no such exemption: for every
     * language but English and Bengali it is where the *downloaded* list
     * lives, beside anything imported, so exempting it exempted the very list
     * that holds `juz`.
     */
    private fun accentShadowed(lower: String, touch: List<TouchPoint?>?): Boolean {
        if (systemDictionary.contains(lower)) return false
        // The same walk decideOrdinary ranks, so this reads the memoised result.
        val ranked = rankedFor(lower, FuzzyBeamSearch.AUTOCORRECT_K / 2, touch)
        val twin = ranked
            .filter { it.edits == 0 && it.completedChars == 0 && it.accents > 0 }
            // The accent price given back, so the ratio is between the two
            // frequencies alone.
            .maxOfOrNull { it.dictScore + it.accents * FuzzyBeamSearch.COST_ACCENT }
            ?: return false
        // Read from the lists directly. A stand-in is rare next to its twin,
        // and the walk's top ranks fill with the twin's own inflections long
        // before they reach it: `mowie` never makes the top 32 for `mówię`.
        val typed = dictionaryScore(lower)
        if (typed == Double.NEGATIVE_INFINITY) return false
        return twin - typed >= ln(ACCENT_SHADOW_RATIO)
    }

    /**
     * Whether the word lists hold [lower] only as a typo the corpus kept (#244).
     *
     * The downloadable lists are counted from subtitles, and subtitles are
     * full of typos. The English one holds `wheee` 60 times, `thw` 33 times
     * and `teh` 124 times, so on anything bigger than the Small download
     * those spellings were known words and were never corrected. The bundled
     * list and the Small one stop above them, which is why only a bigger
     * download broke the fix.
     *
     * A spelling is such a typo when it ranks below [TYPO_SHADOW_MIN_RANK] in
     * every list that holds it and a one-edit fix outscores it
     * [TYPO_SHADOW_RATIO] times over. A word in Android's personal dictionary
     * was put there by the user, and is never shadowed.
     */
    private fun typoShadowed(lower: String, touch: List<TouchPoint?>?, keys: KeySets? = null): Boolean {
        if (systemDictionary.contains(lower)) return false
        var typed = Double.NEGATIVE_INFINITY
        val holders = ArrayList<Pair<TrieWalker, Int>>(2)
        for (src in walkSources()) {
            if (src.tier != FuzzyBeamSearch.Tier.DICTIONARY) continue
            val walker = src.walker
            var node = walker.root
            for (ch in lower) {
                node = walker.child(node, ch)
                if (node < 0) break
            }
            if (node < 0 || !walker.isWord(node)) continue
            val frequency = walker.frequency(node)
            typed = maxOf(typed, src.logWeight + ln(1.0 + frequency))
            holders.add(walker to frequency)
        }
        if (holders.isEmpty()) return false
        // The same walk the strip and decideOrdinary rank, so this reads the
        // memoised result.
        val fix = rankedFor(lower, FuzzyBeamSearch.AUTOCORRECT_K / 2, touch, keys)
            .take(FuzzyBeamSearch.AUTOCORRECT_K)
            .filter { it.edits == 1 && it.completedChars == 0 && !suppressed(it.word) }
            .maxOfOrNull { it.dictScore }
            ?: return false
        if (fix - typed < ln(TYPO_SHADOW_RATIO)) return false
        // Last, because the first rank asked of a list builds its histogram.
        return holders.all { (walker, frequency) -> walker.rankOfFrequency(frequency) > TYPO_SHADOW_MIN_RANK }
    }

    /**
     * Just [langId]'s own dictionary sources, out of the mix's.
     *
     * An elision is a fact about one language: `d'` in front of an Italian
     * word. Asked against every list at once, the Italian grammar reads the
     * English *dart* as `d'art`, because the mix happens to hold an English
     * *art* for it to point at. So the word after the prefix is looked up
     * here, in the language whose grammar admitted the split, while whether
     * the fused spelling is a *word* stays a question for the whole mix —
     * English knowing `dart` is exactly the reason not to rewrite it (#240).
     */
    private fun languageSources(langId: String): List<FuzzyBeamSearch.WalkSource> =
        dictionarySources(langId)

    /**
     * [lower]'s best score in the dictionary-tier walk sources, on the walk's
     * own scale; NEGATIVE_INFINITY when no list holds it.
     */
    private fun dictionaryScore(lower: String): Double = dictionaryScore(lower, walkSources())

    private fun dictionaryScore(lower: String, sources: List<FuzzyBeamSearch.WalkSource>): Double {
        var best = Double.NEGATIVE_INFINITY
        for (src in sources) {
            if (src.tier != FuzzyBeamSearch.Tier.DICTIONARY) continue
            val walker = src.walker
            var node = walker.root
            for (ch in lower) {
                node = walker.child(node, ch)
                if (node < 0) break
            }
            if (node >= 0 && walker.isWord(node)) {
                best = maxOf(best, src.logWeight + ln(1.0 + walker.frequency(node)))
            }
        }
        return best
    }

    /**
     * [lower]'s best reading in the dictionary tier with any accents the
     * typist left off put back, and its score on the walk's scale: "etait"
     * finds "était" as readily as "était" does. Null when no list holds
     * either. A direct descent rather than a walk: it is asked once per
     * keystroke for the word after an elided prefix, and the only branching
     * is a letter's accented twins.
     */
    private fun accentedLookup(
        lower: String,
        sources: List<FuzzyBeamSearch.WalkSource> = walkSources(),
    ): Pair<String, Double>? {
        var bestWord: String? = null
        var best = Double.NEGATIVE_INFINITY
        val children = ChildBuffer()
        val spelled = StringBuilder(lower.length)
        for (src in sources) {
            if (src.tier != FuzzyBeamSearch.Tier.DICTIONARY) continue
            val walker = src.walker
            fun descend(node: Int, pos: Int) {
                if (pos == lower.length) {
                    if (!walker.isWord(node)) return
                    val score = src.logWeight + ln(1.0 + walker.frequency(node))
                    if (score > best) {
                        best = score
                        bestWord = spelled.toString()
                    }
                    return
                }
                val expected = lower[pos]
                // The matching edges are gathered before any descent, since
                // the child buffer is shared down the recursion.
                val count = walker.childrenInto(node, children)
                var next: ArrayList<Pair<Char, Int>>? = null
                for (i in 0 until count) {
                    val label = children.labels[i]
                    if (label != expected && !Accents.isAccentOf(label, expected)) continue
                    (next ?: ArrayList<Pair<Char, Int>>(2).also { next = it }).add(label to children.nodes[i])
                }
                for ((label, child) in next ?: return) {
                    spelled.append(label)
                    descend(child, pos + 1)
                    spelled.setLength(pos)
                }
            }
            spelled.setLength(0)
            descend(walker.root, 0)
        }
        return bestWord?.let { it to best }
    }

    /**
     * [lower] read as a word whose apostrophe was left out, whichever way
     * this language forms one: an English contraction from [Apostrophes]'
     * table, an elision from the word lists and [Elisions]' grammar (#215).
     * Null when the language has neither route, or neither route fires.
     *
     * One entry point for both, because both answer the same question and
     * both have to be asked in the same places. They were not: the table was
     * read at commit and nowhere else, so the strip went on showing `thats`
     * while the space bar was about to type `that's`, and the user — who
     * watches the strip — read that as the fix not working at all (#240).
     */
    /**
     * [lower] read as a compound typed without its hyphen — `чтото` for
     * *что-то*, `wellpaid` for *well-paid* — with its score on the walk's
     * scale, or null.
     *
     * The walk cannot be left to find it. It would, as a one-letter insertion,
     * but only for a spelling no list holds, and the big lists hold the fused
     * form as a word: the Russian one counts `чтото` 89 times against
     * `что-то`'s 162,833, so the typed spelling was "known", corrections were
     * never asked for, and the strip had nothing to offer but what was typed.
     * So this asks for itself, one split at a time, and offers the compound
     * when the fused spelling is unknown or a stand-in the compound outnumbers
     * [HYPHEN_SHADOW_RATIO] times over — `online` stays itself, never
     * *on-line*.
     *
     * The strip only. Whether the space bar rewrites it is the corrector's
     * call, made the way it makes every other ([typoShadowed]).
     */
    private fun hyphenReading(lower: String): Pair<String, Double>? {
        if (lower.length < HYPHEN_READING_MIN_LENGTH || lower.length > JOIN_MAX_LENGTH) return null
        if (!lower.all { WordContext.isWordChar(it) }) return null
        val typed = dictionaryScore(lower)
        var best: Pair<String, Double>? = null
        for (at in 1 until lower.length) {
            val compound = lower.substring(0, at) + '-' + lower.substring(at)
            val score = dictionaryScore(compound)
            if (score == Double.NEGATIVE_INFINITY || suppressed(compound)) continue
            if (typed != Double.NEGATIVE_INFINITY && score - typed < ln(HYPHEN_SHADOW_RATIO)) continue
            if (best == null || score > best.second) best = compound to score
        }
        return best
    }

    /**
     * Whether [lower] is a hyphenated compound every part of which is a word:
     * `hello-world`, `красно-белый`. Not a typo to correct, whatever the lists
     * say about the whole — they hold only the compounds common enough to
     * have been counted, and a compound is made up on the spot far more often
     * than it is looked up.
     */
    private fun knownCompound(lower: String): Boolean {
        if ('-' !in lower) return false
        val parts = lower.split('-')
        return parts.size in 2..MAX_COMPOUND_PARTS &&
            parts.all { it.isNotEmpty() && (inDictionaries(it) || userLexicon.contains(it)) }
    }

    private fun apostropheReading(lower: String): ElisionReading? {
        if (!apostropheFixes) return null
        var best: ElisionReading? = null
        for (langId in mixLanguageIds()) {
            val reading = contractionReading(lower, langId) ?: elisionReading(lower, langId)
            if (reading != null && (best == null || reading.score > best.score)) best = reading
        }
        return best
    }

    /**
     * [lower] read as an English contraction typed without its apostrophe:
     * `thats` is *that's*, `dont` is *don't* (#128, #240).
     *
     * A table rather than the lists, because the lists cannot answer it —
     * they are tokenised at the apostrophe like every other corpus, so they
     * hold `thats` as a word and `that's` as a rarer one, and the commoner
     * spelling is the wrong one. [Apostrophes] already refuses every form
     * that is a word in its own right (*its*, *were*, *well*), so a hit here
     * is certain in a way an elision reading never is: it leads the typed
     * spelling in the strip, by [CONTRACTION_LEAD], exactly as it overrides
     * it at commit. A strip that disagreed with the space bar would be the
     * bug this fixes.
     */
    private fun contractionReading(lower: String, langId: String): ElisionReading? {
        if (!Apostrophes.servesLanguage(langId)) return null
        val fixed = Apostrophes.fix(lower) ?: return null
        val scored = maxOf(finiteScore(fixed.lowercase()), finiteScore(lower))
        return ElisionReading(fixed, scored + CONTRACTION_LEAD, shadowed = true)
    }

    /** [dictionaryScore] with an unknown word's negative infinity read as zero. */
    private fun finiteScore(word: String): Double =
        dictionaryScore(word).takeIf { it > Double.NEGATIVE_INFINITY } ?: 0.0

    /**
     * [lower] read as an elision typed without its apostrophe (#215): the
     * spelling with the apostrophe, its score for the strip, and whether it
     * is the reading to *commit* — the fused spelling is unknown to every
     * list, or known only as a stand-in the word after the prefix outnumbers
     * [ELISION_SHADOW_RATIO] times over, the way an accentless stand-in is
     * judged. Null where the language does not elide, or the grammar
     * ([Elisions]) admits no split, or the lists hold no word for the rest.
     *
     * The score is the rest's own when the fused spelling is unknown — an
     * elision explains every key pressed, and an edit that drops the prefix
     * does not — and that minus the ratio's log when it is a word, so a real
     * word that happens to split (`tas`, `lune`) leads its elided reading in
     * the strip by the same margin that keeps it from being corrected.
     */
    private fun elisionReading(lower: String, langId: String): ElisionReading? {
        val rules = Elisions.rulesFor(langId) ?: return null
        val splits = rules.splits(lower)
        if (splits.isEmpty()) return null
        // The word after the prefix has to be a word of *this* language; the
        // fused spelling being a word is a question for the whole mix. See
        // [languageSources].
        val own = languageSources(langId)
        if (own.isEmpty()) return null
        val typed = dictionaryScore(lower)
        val price = ln(ELISION_SHADOW_RATIO)
        var best: ElisionReading? = null
        for (split in splits) {
            val (word, score) = rules.respelled(split)
                ?.let { spelled ->
                    dictionaryScore(spelled, own).takeIf { it > Double.NEGATIVE_INFINITY }?.let { spelled to it }
                }
                ?: accentedLookup(split.rest, own)
                ?: continue
            if (suppressed(word)) continue
            // A spelling no word begins with is an elision whatever a list
            // has counted: `aujourdhui` is never a word, and `hui` is never
            // anything else, so the ratio between them says nothing.
            val known = typed != Double.NEGATIVE_INFINITY && !rules.alwaysElides(split.prefix)
            // A word far commoner than what follows it is not offered the
            // split at all: `quand` is not shown *qu'and* because a list has
            // an English "and" in it somewhere.
            if (known && typed - score > ln(ELISION_OFFER_FLOOR)) continue
            val reading = ElisionReading(
                spelling = split.spell(word),
                score = if (known) score - price else score,
                shadowed = !known || score - typed >= price,
            )
            if (best == null || reading.score > best.score) best = reading
        }
        return best
    }

    private class ElisionReading(val spelling: String, val score: Double, val shadowed: Boolean)

    /**
     * The apostrophe [word] was typed without, or null when it needs none:
     * "thats" → "that's", "cest" → "c'est", "quil" → "qu'il" (#215, #240).
     *
     * Every language's route in one call — the English table, an elision
     * language's word lists — so its callers do not have to know which one
     * the keyboard is on. Applied at commit, ahead of autocorrect, and to a
     * glide's readings. Only a spelling that is not vouched for as a word of
     * its own is rewritten; see [apostropheReading].
     */
    fun elide(word: String): String? {
        val reading = apostropheReading(word.lowercase()) ?: return null
        if (!reading.shadowed) return null
        return matchCase(word, reading.spelling).takeIf { it != word }
    }

    /**
     * The engine's own verdict on [word], from the dictionaries and the walk
     * alone; see [decideCorrection] for the contract.
     */
    private fun decideOrdinary(
        word: String,
        lower: String,
        touch: List<TouchPoint?>?,
        timingMultiplier: Double,
        keys: KeySets? = null,
    ): CorrectionDecision {
        // A shadowed spelling is protected by neither the lists nor the
        // lexicon. The lexicon learned it only because a list vouched for it.
        val known = inDictionaries(lower) || userLexicon.isEstablished(lower, learnedWordMinCount)
        if (known && !accentShadowed(lower, touch) && !typoShadowed(lower, touch, keys)) {
            return NO_CORRECTION
        }
        if (knownCompound(lower)) return NO_CORRECTION
        // Contact and app names are known words too — never "corrected" away.
        if (contacts.contains(lower) || apps.contains(lower)) return NO_CORRECTION
        // Digits: exactly one digit may be a number-row slip (when the IME
        // buffers those); anything more digit-heavy is deliberate input —
        // codes, model numbers — and is never rewritten.
        val digits = lower.count { it.isDigit() }
        if (digits > 0 && (!digitSlipCorrections || digits > 1)) return NO_CORRECTION

        // The walk ranks WALK_K deep for the strip's context boosts, but the
        // silent-replacement decision stays on the same top-8 it has always
        // judged: a rank-20 word must never fire as a correction, nor may it
        // appear as the runner-up that tightens (or loosens) the gate.
        val shaped = rankedFor(
            lower, FuzzyBeamSearch.AUTOCORRECT_K / 2, touch, keys,
        ).take(FuzzyBeamSearch.AUTOCORRECT_K).filter { c ->
            // Silent replacement only trusts classic one-edit shapes: a single
            // edit within one character of the typed length, or the
            // one-extra-letter completion the old insert-at-end edit produced.
            // Two-edit words and edited-then-completed words stay in the strip
            // — and, crucially, they don't stand in as runner-ups that block
            // an otherwise-unopposed correction. A suppressed word
            // (blacklisted or offensive) is never a target either.
            // Exact shapes only: a pure edit with no completion tail, or the
            // one-extra-letter pure completion. An edited-then-completed
            // inflection ("questiom" -> "questions") must be neither a target
            // nor the runner-up that blocks the real fix.
            val correctionShaped = when (c.edits) {
                // The typed word with its accents put back is a fix in place:
                // same letters, same length.
                0 -> if (c.accents > 0) {
                    c.completedChars == 0
                } else {
                    c.completedChars == 1 && c.word != lower
                }
                1 -> c.completedChars == 0
                else -> false
            }
            // A digit-carrying word is only ever fixed in place: same length,
            // letters untouched, the digit swapped for a letter. A deletion
            // ("room3" -> "room") or completion reading would rewrite text
            // the user typed on purpose.
            correctionShaped && !suppressed(c.word) &&
                (digits == 0 || digitSubShape(lower, c.word))
        }.map { c ->
            // A one-extra-letter completion rides the walk at zero cost, but
            // as a *correction* it is the old insert-at-end edit and must
            // carry that edit's weight — both as a target and as the
            // runner-up that gates someone else's correction.
            if (c.edits == 0 && c.accents == 0) {
                FuzzyBeamSearch.ScoredCandidate(
                    c.word, c.score - FuzzyBeamSearch.COST_INSERT_ADJACENT,
                    FuzzyBeamSearch.COST_INSERT_ADJACENT, 1, 0, c.tier,
                    c.dictScore - FuzzyBeamSearch.COST_INSERT_ADJACENT,
                    c.userScore - FuzzyBeamSearch.COST_INSERT_ADJACENT,
                )
            } else {
                c
            }
        }.mapNotNull { c ->
            // Pair penalties: an exact correction the user undid is blocked
            // (never a target, never the runner-up that gates another fix);
            // a once-reverted pair fights with a heavy handicap and loses
            // its shortcut privileges.
            when (correctionStats.penalty(lower, c.word)) {
                CorrectionStats.Penalty.BLOCKED -> null
                CorrectionStats.Penalty.PENALIZED -> FuzzyBeamSearch.ScoredCandidate(
                    c.word, c.score - PAIR_PENALTY, c.editCost, c.edits,
                    c.completedChars, c.tier,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, c.accents,
                )
                // A pair on probation keeps its honest score, because the only
                // thing it is here to do is clear the offer margin, and a
                // handicap would quietly make sure it never did. What stops it
                // applying itself is the explicit bar below; what stops the
                // two-source shortcut is the same pair of dead scores the
                // penalized case uses.
                CorrectionStats.Penalty.PROBATION -> FuzzyBeamSearch.ScoredCandidate(
                    c.word, c.score, c.editCost, c.edits,
                    c.completedChars, c.tier,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, c.accents,
                )
                CorrectionStats.Penalty.NONE -> c
            }
        }.sortedWith(
            compareByDescending<FuzzyBeamSearch.ScoredCandidate> { it.score }.thenBy { it.word }
        )
        // A reading that spends every keystroke as pressed and only puts
        // accents back explains the word completely, and one that calls a key
        // mistyped does not. So an edited reading is no rival to it, however
        // common: `sie` is `się`, not a slip for `nie`, and `zona` is `żona`,
        // not `ona` with a stray letter (#200). Accent readings still compete
        // among themselves, so `zle` stays open between `źle` and `żle`.
        val candidates = shaped.filter { it.edits == 0 && it.accents > 0 }.ifEmpty { shaped }

        // Two independent sources naming the same word is confidence enough
        // on its own.
        var bestDict: String? = null
        var bestDictScore = Double.NEGATIVE_INFINITY
        var bestUser: String? = null
        var bestUserScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            if (c.dictScore > bestDictScore) {
                bestDictScore = c.dictScore
                bestDict = c.word
            }
            if (c.userScore > bestUserScore) {
                bestUserScore = c.userScore
                bestUser = c.word
            }
        }
        if (bestDict != null && bestUser != null && bestDict == bestUser) {
            return CorrectionDecision(
                apply = matchCase(word, bestDict),
                // Nothing this engine can say is more certain than the
                // bundled dictionaries and the user's own lexicon arriving at
                // the same word independently.
                certainty = 1.0,
                complexity = complexityOf(lower, candidates.first { it.word == bestDict }),
            )
        }

        val effectiveConfidence = (
            autocorrectConfidence *
                (if (adaptiveConfidence) correctionStats.confidenceMultiplier() else 1.0) *
                timingMultiplier
            ).coerceIn(MIN_AUTOCORRECT_CONFIDENCE, MAX_AUTOCORRECT_CONFIDENCE)
        val gate = ln(effectiveConfidence)
        val top = candidates.firstOrNull()
        // With no runner-up, a synthetic floor stands in: an unopposed but weak
        // candidate (rare word reached by an expensive edit) must not fire just
        // because nothing else was nearby.
        val margin = if (top == null) {
            Double.NEGATIVE_INFINITY
        } else {
            top.score - (candidates.getOrNull(1)?.score ?: SOLO_RUNNER_UP_SCORE)
        }
        // A candidate the user has already rejected once. It still ranks, but
        // it neither fires nor gets asked about: being told twice is worse
        // than not being helped.
        val topPenalty = if (top == null) {
            CorrectionStats.Penalty.NONE
        } else {
            correctionStats.penalty(lower, top.word)
        }
        val penalized = topPenalty != CorrectionStats.Penalty.NONE
        // A retired pair on probation is the one exception to "rejected once,
        // never asked again": it has been quiet for months of saves, and the
        // alternative is a word that stays wrong forever with nothing ever
        // saying why.
        val probation = topPenalty == CorrectionStats.Penalty.PROBATION
        val single = when {
            top == null -> null
            // Probation buys a question, never an answer. This pair is still
            // retired; it may only reach the offer below.
            probation -> null
            // A penalized candidate with no competition stays a suggestion:
            // the user already told us once that this exact fix was wrong.
            candidates.size == 1 && penalized -> null
            margin < gate -> null
            else -> top.word
        }
        if (single != null && top != null) {
            return CorrectionDecision(
                apply = matchCase(word, single),
                certainty = certaintyOf(margin, gate),
                complexity = complexityOf(lower, top),
            )
        }
        // No single word explains the typed string; a missing space might.
        splitCorrection(lower, top?.score, effectiveConfidence, touch)?.let {
            return CorrectionDecision(
                apply = matchCase(word, it),
                // A split held to the same margin as any other correction, so
                // it is as certain as they come — but it is also the one
                // correction that changes how many words the sentence has,
                // which no reader misses and no finger slip explains. The
                // complexity carries the whole verdict here.
                certainty = 1.0,
                complexity = 1.0,
            )
        }
        // Nothing was confident enough to apply. Something may still be worth
        // asking about: this is where a correction that was probably right
        // used to be dropped on the floor because "probably" is not enough to
        // rewrite somebody's word behind their back.
        // Being rejected once keeps a pair off the chip. Probation is the one
        // way back onto it.
        val mayBeOffered = probation || !penalized
        val offer = top
            ?.takeIf { mayBeOffered }
            ?.takeIf { margin >= gate * OFFER_MARGIN_FRACTION }
            ?.word
        return CorrectionDecision(offer = offer?.let { matchCase(word, it) })
    }

    /**
     * How far a correction cleared its bar, mapped onto 0..1.
     *
     * 0 sits exactly on the gate; half the scale is one whole extra gate's
     * worth of margin, and it flattens towards 1 from there. A ratio rather
     * than a fixed scale so that moving the confidence slider moves what
     * counts as "sure" with it — a correction that scraped past a demanding
     * gate is no surer than one that scraped past a lenient one.
     */
    private fun certaintyOf(margin: Double, gate: Double): Double {
        val surplus = (margin - gate).coerceAtLeast(0.0)
        return surplus / (surplus + gate)
    }

    /**
     * How far [candidate] strays from the typed [lower], on 0..1.
     *
     * Two things make a correction worth a second look. The edit's own cost
     * says whether the keyboard can explain the slip at all: a neighbouring
     * key or a transposition is a finger landing badly, while a far
     * substitution is a letter the user reached for on purpose. And the
     * word's length says how easy the change is to miss — a swapped letter in
     * the middle of a long word goes by unread in a way a three-letter fix
     * never does.
     */
    private fun complexityOf(
        lower: String,
        candidate: FuzzyBeamSearch.ScoredCandidate,
    ): Double {
        val shape = (candidate.editCost / FuzzyBeamSearch.COST_SUB_FAR).coerceIn(0.0, 1.0)
        val length = ((lower.length - PLAIN_WORD_LENGTH) / PLAIN_WORD_SPAN).coerceIn(0.0, 1.0)
        return COMPLEXITY_SHAPE_WEIGHT * shape + (1.0 - COMPLEXITY_SHAPE_WEIGHT) * length
    }

    /**
     * [complexityOf] for a taught fix, which has no walk behind it to price
     * the edit: one slip reads as a near one, two as a far one, and the word's
     * length weighs in exactly as it does for the engine's own corrections.
     */
    private fun complexityOfEdit(lower: String, fixed: String): Double {
        val edits = EditOps.distance(lower, fixed)
        val shape = (edits.toDouble() / CorrectionMemory.MAX_EDITS).coerceIn(0.0, 1.0)
        val length = ((lower.length - PLAIN_WORD_LENGTH) / PLAIN_WORD_SPAN).coerceIn(0.0, 1.0)
        return COMPLEXITY_SHAPE_WEIGHT * shape + (1.0 - COMPLEXITY_SHAPE_WEIGHT) * length
    }

    /**
     * Missing-space autocorrect: "kortehobe" → "korte hobe", including the
     * fat-fingered-space reading ("amibtomake" → "ami tomake"). Considered
     * only after every single-word gate declined, and held to the same
     * confidence discipline: the best split must beat the best single-word
     * candidate, the runner-up split, and the solo floor by the gate margin,
     * with halves of at least two letters. The committed text becomes two
     * words — the IME's learn/revert paths already handle multi-word commits.
     *
     * A dropped-letter reading is applied only when its halves are a phrase
     * the keyboard has seen together ([knownPhrase]). It deletes a letter the
     * user typed and changes the sentence's word count on the strength of
     * one low tap, and an unlisted word that happens to break into two listed
     * ones is far commoner than a spacebar miss that lands between exactly
     * those two. Until the pair is known it stays a strip suggestion.
     */
    private fun splitCorrection(
        lower: String,
        bestWordScore: Double?,
        effectiveConfidence: Double,
        touch: List<TouchPoint?>?,
    ): String? {
        if (!autocorrectSplits) return null
        if (lower.length < SPLIT_AUTOCORRECT_MIN_LENGTH) return null
        val splits = splitCandidates(lower, touch)
            .filter { reading ->
                val halves = reading.text.split(' ')
                halves.all { it.length >= 2 && !suppressed(it) } &&
                    (!reading.dropped || knownPhrase(halves[0], halves[1]))
            }
            .sortedWith(compareByDescending<SplitReading> { it.score }.thenBy { it.text })
        val best = splits.firstOrNull() ?: return null
        // The same pair memory word corrections use: a split the user
        // reverted is never forced on them again.
        if (correctionStats.penalty(lower, best.text) != CorrectionStats.Penalty.NONE) return null
        val rival = maxOf(
            bestWordScore ?: Double.NEGATIVE_INFINITY,
            splits.getOrNull(1)?.score ?: Double.NEGATIVE_INFINITY,
            SOLO_RUNNER_UP_SCORE,
        )
        if (best.score - rival < ln(effectiveConfidence)) return null
        return best.text
    }

    /** True when [candidate] is [typed] with its single digit swapped for a
     * letter and every other character untouched. */
    private fun digitSubShape(typed: String, candidate: String): Boolean {
        if (candidate.length != typed.length) return false
        for (i in typed.indices) {
            val t = typed[i]
            if (t.isDigit()) {
                if (candidate[i].isDigit()) return false
            } else if (candidate[i] != t) {
                return false
            }
        }
        return true
    }

    /** True when [word] has letters and every one of them is uppercase. */
    private fun isAllCaps(word: String): Boolean = isAllCapsWord(word)

    /**
     * The spelling a candidate should be offered in.
     *
     * Every trie here is keyed lower case, so a word the user writes with a
     * capital comes back off a completion or a swipe stripped of it and has to
     * be re-picked from the strip every single time (#44). This puts it back
     * from the two stores that record how the user themselves spells a word:
     * their own learned-word case memory, and the platform dictionary they
     * typed the entry into by hand.
     *
     * Only lower-case candidates are touched. A candidate that already carries
     * case came from a source that knows better than this does — a contact
     * name, an app label, an email address — and re-deciding it here would
     * throw that away.
     *
     * Contacts and app labels deliberately do *not* feed this. They already
     * hand their own completions over capitalized, and consulting them for
     * every candidate would capitalize ordinary words that happen to be
     * somebody's name or an app's ("Will", "Photos", "Files").
     */
    fun displayOf(word: String): String {
        if (word.isEmpty()) return word
        val key = word.lowercase()
        if (key != word) return word
        return userLexicon.displayOf(key) ?: systemWordCases[key] ?: word
    }

    private fun displayForm(word: String): String = displayOf(word)

    /**
     * Applies the typed word's capitalization pattern to a suggestion. Letters
     * are judged by code point — see `WordCase.kt` — or a cased script outside
     * the BMP reads as all capitals and every suggestion for it is shouted.
     */
    private fun matchCase(typed: String, suggestion: String): String = when {
        typed.codePointCount(0, typed.length) > 1 && lettersAllUpper(typed) ->
            suggestion.uppercase()
        startsUpperCase(typed) -> capitalizeFirst(suggestion)
        else -> suggestion
    }
}
