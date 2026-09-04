package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex
import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
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
    bengaliIndex: BengaliPhoneticIndex,
    private val userLexicon: UserLexicon,
    private val spellings: BengaliSpellingMap = BengaliSpellingMap.EMPTY,
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
    fun seedFieldContext(words: List<String>) {
        fieldMix.reset()
        if (secondaryDictionaries.isNotEmpty() || englishAsSecondary) {
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
     * Bengali index, rebuilt when an imported Bengali list arrives so its
     * words become reachable by transliteration too.
     */
    @Volatile
    var bengaliIndex: BengaliPhoneticIndex = bengaliIndex

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
     * Records that the user undid the autocorrect of [typed] into
     * [corrected] (typically by backspacing over it). The exact pair is
     * blocked for this session and penalized across sessions; other
     * corrections of the same typed word are deliberately untouched.
     */
    fun rejectCorrection(typed: String, corrected: String) {
        if (typed.isNotEmpty() && corrected.isNotEmpty()) {
            correctionStats.recordRevert(typed, corrected)
        }
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

    private val glideBeam = GlideBeam()
    private val glideWorkspace = ThreadLocal.withInitial { GlideWorkspace() }

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
    val spellingMap: BengaliSpellingMap get() = spellings

    /**
     * Whether [alphabet] can spell enough of the language now being typed for a
     * glide to mean anything. Only the dictionary tier is asked: the personal
     * lexicon is small and can hold words from whatever the user typed last, so
     * letting it vote would have a handful of leftover English words decide
     * whether Bengali is glidable.
     */
    fun glideCoverage(alphabet: Set<Char>): Float {
        val romanization = glideRomanization
        // Through the romanization when there is one: on Avro the question is
        // whether the Latin grid spells the *romanized* vocabulary, and asking
        // it of the Bengali word list would answer zero and switch off a layout
        // that decodes perfectly well.
        val sources = if (romanization.isEmpty) {
            walkSources().filter { it.tier == FuzzyBeamSearch.Tier.DICTIONARY }
        } else {
            romanization.walkSources()
        }
        return GlideCoverage.measure(sources.map { it.walker }, alphabet)
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
    ): List<GlideBeam.Candidate> {
        val romanization = glideRomanization
        val decoded = glideBeam.decode(
            path = path,
            keys = keys,
            keyWidth = keyWidth,
            sources = if (romanization.isEmpty) walkSources() else romanization.walkSources(),
            ws = glideWorkspace.get(),
            limit = maxOf(limit, GLIDE_RERANK_POOL),
        )
        // On a phonetic layout the stroke spelled a romanization; the words it
        // stands for are what the rest of this — the blacklist, the reranker,
        // the caller — should ever see.
        val words = if (romanization.isEmpty) decoded else romanization.resolve(decoded)
        val kept = words.filterNot { suppressed(it.word) }
        if (kept.isEmpty()) return kept
        return rerankGlide(kept, previousWord, previousWord2, recentWords).take(limit)
    }

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
    /**
     * Whether a decoded stroke is a close enough call to be worth asking about.
     *
     * The measure is the gap between the best candidate and the runner-up, in
     * the same log units everything else is scored in — so it reads directly as
     * a likelihood ratio, and a gap of [AMBIGUOUS_MARGIN] means the two words
     * are within about a factor of three of each other. Below that, calling it
     * for the leader is a coin toss dressed up as a decision.
     *
     * Deliberately not a function of the shape cost. A stroke can be drawn
     * beautifully and still be ambiguous — ক and খ are the same stroke however
     * carefully it is made — and a scruffy stroke with only one word anywhere
     * near it is not ambiguous at all.
     */
    fun glideIsAmbiguous(decoded: List<GlideBeam.Candidate>): Boolean =
        decoded.size >= 2 && decoded[0].score - decoded[1].score < AMBIGUOUS_MARGIN

    private fun rerankGlide(
        decoded: List<GlideBeam.Candidate>,
        previousWord: String?,
        previousWord2: String?,
        recentWords: List<String>,
    ): List<GlideBeam.Candidate> {
        if (reranker === CandidateReranker.NONE || decoded.size < 2) return decoded
        val pool = decoded.map { it.word }
        val reordered = reranker.rerank(
            RerankContext(composing = "", previousWord, recentWords, previousWord2), pool,
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
        val ranked: List<FuzzyBeamSearch.ScoredCandidate>,
    )

    @Volatile
    private var rankedWalk: RankedWalk? = null

    private fun rankedFor(
        lower: String,
        limit: Int,
        touch: List<TouchPoint?>?,
    ): List<FuzzyBeamSearch.ScoredCandidate> {
        val k = maxOf(limit * 2, WALK_K)
        val gen = generation.get()
        val lexGen = userLexicon.mutationCount()
        rankedWalk?.let { cached ->
            if (cached.word == lower && cached.generation == gen &&
                cached.lexMutations == lexGen && cached.k >= k && cached.touch == touch
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
            walkSources(), lower, proximity, k / 2, beamWorkspace.get(), touch = scoring,
        )
        val ranked = dampMismatchedLanguages(walked)
        rankedWalk = RankedWalk(lower, gen, lexGen, k, touch?.let(::ArrayList), ranked)
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
        if (active.isEmpty()) return ranked
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
        // ln(1.0) = 0 while the field mix is neutral, keeping the primary's
        // weights bit-identical to the pre-detection engine.
        val primaryShift = ln(fieldFactorFor(primaryLanguageId))
        add(activeDictionary, primaryShift, FuzzyBeamSearch.Tier.DICTIONARY)
        add(customDictionary, LOG_CUSTOM_WORD_WEIGHT + primaryShift, FuzzyBeamSearch.Tier.DICTIONARY)
        if (englishAsSecondary && !englishSources) {
            val factor = englishSecondaryFactor()
            if (factor > 0) add(dictionary, ln(factor), FuzzyBeamSearch.Tier.DICTIONARY)
        }
        for (t in secondaryDictionaries) {
            val weight = SECONDARY_WORD_WEIGHT * mixConfidence.confidenceFor(t.langId) *
                fieldFactorFor(t.langId)
            if (weight > 0) add(t.source, ln(weight), FuzzyBeamSearch.Tier.DICTIONARY)
        }
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

    private fun inDictionaries(word: String): Boolean =
        activeDictionary.contains(word) || customDictionary.contains(word) ||
            systemDictionary.contains(word) ||
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
        /** Learned words get a large boost so personalization wins quickly. */
        /** Completions scanned per source when building the next-letter map. */
        private const val NEXT_LETTER_SCAN = 24
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
         * How far ahead the best candidate has to be before a swipe commits it
         * without asking, in nats. About a factor of three.
         */
        private const val AMBIGUOUS_MARGIN = 1.1

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
    }

    /**
     * @param composing the word currently being typed (may be empty)
     * @param previousWord last committed word, used for next-word prediction
     * @param avroMode when true, [composing] is romanized Bengali and the
     *        top suggestion is its transliteration
     * @param limit how many candidates to return
     * @param touch per-character tap positions (null entries fall back to
     *        the discrete adjacency model)
     * @param previousWord2 the word before [previousWord], for trigram context
     * @param recentWords the last few committed words, a topical recency bag
     *        for the reranker
     * @param allowRerank whether [reranker] may reorder the head of the list
     *        (never set on the synchronous main-thread call sites)
     */
    fun suggest(
        composing: String,
        previousWord: String?,
        avroMode: Boolean = false,
        limit: Int = 5,
        touch: List<TouchPoint?>? = null,
        previousWord2: String? = null,
        recentWords: List<String> = emptyList(),
        allowRerank: Boolean = false,
    ): List<String> {
        if (composing.isEmpty()) {
            return nextWords(previousWord, previousWord2, limit)
        }
        if (avroMode) {
            return bengaliSuggestions(composing, limit)
        }

        val lower = composing.lowercase()
        val known = inDictionaries(lower) || userLexicon.contains(lower)
        val merged = HashMap<String, Double>()

        // One fuzzy walk covers completions AND corrections over every trie
        // source. Corrections (edited paths) are admitted only when the typed
        // word is unknown, matching the historical gate; pure completions
        // (edits == 0) always participate.
        for (c in rankedFor(lower, limit, touch)) {
            if (c.edits > 0 && known) continue
            merged.merge(c.word, c.score, ::maxOf)
        }
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
            for ((split, score) in splitCandidates(lower)) {
                merged.merge(split, score, ::maxOf)
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

        // Contact words carry their own capitalization ("Wasi"), so the
        // same word can arrive in two cases; keep the better-scored one.
        val byLower = HashMap<String, Pair<String, Double>>()
        for ((word, score) in merged) {
            val key = word.lowercase()
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
                RerankContext(composing, previousWord, recentWords, previousWord2), pool,
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
            .map { if (it.contains('@')) it else matchCase(composing, it) }
    }

    /** Log-space score for the flat (non-trie) sources, comparable with the
     * beam's `logWeight + ln(1 + freq)` shape. */
    private fun flatScore(frequency: Int, weight: Int): Double =
        ln(1.0 + frequency.toDouble() * weight)

    /**
     * A distribution over the character most likely to be typed next, given the
     * word-so-far [prefix]. Each dictionary word that starts with [prefix]
     * contributes its frequency to the single letter that would extend the
     * prefix by one; the personal lexicon counts extra so learned habits bias
     * the keyboard. Values are normalised to 0..1 with the top letter at 1.0.
     * Empty when the prefix is blank or completes to nothing.
     *
     * Deliberately cheap and approximate — it feeds smart key-hit detection,
     * which only nudges boundary taps, so an imperfect distribution is fine.
     */
    fun nextLetterWeights(prefix: String): Map<Char, Float> {
        if (prefix.isEmpty()) return emptyMap()
        val lower = prefix.lowercase()
        val at = lower.length
        val tally = HashMap<Char, Double>()
        fun fold(weight: Double, complete: (String, Int) -> List<Suggestion>) {
            for (s in complete(lower, NEXT_LETTER_SCAN)) {
                // Only genuine extensions; a completion equal to the prefix (the
                // word itself) predicts no next letter.
                if (s.word.length <= at) continue
                val ch = s.word[at].lowercaseChar()
                if (!ch.isLetter()) continue
                tally.merge(ch, s.frequency.toDouble() * weight, Double::plus)
            }
        }
        fold(1.0, activeDictionary::complete)
        fold(USER_WORD_WEIGHT.toDouble(), userLexicon::complete)
        fold(USER_WORD_WEIGHT.toDouble(), systemDictionary::complete)
        fold(CUSTOM_WORD_WEIGHT.toDouble(), customDictionary::complete)
        val max = tally.values.maxOrNull() ?: return emptyMap()
        if (max <= 0.0) return emptyMap()
        return tally.mapValues { (it.value / max).toFloat() }
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

    /**
     * Missing-space fixes: "ofthe" → "of the", scored by the rarer half so
     * two genuinely common words outrank a coincidental split.
     *
     * Also covers the fat-fingered spacebar: a stray [spaceAdjacentKeys]
     * letter between two known words ("amibtomake") was probably a space
     * press that landed on the bottom row, so the split that drops it is
     * offered too — at a discount that mirrors the walk's deletion cost, so
     * an exact split of the same material always outranks a dropped-letter
     * reading of it.
     */
    private fun splitCandidates(word: String): List<Pair<String, Double>> {
        if (word.length < 4 || !word.all { it.isLetter() }) return emptyList()
        val results = ArrayList<Pair<String, Double>>()
        fun freqOf(part: String) = maxOf(
            dictionaryFrequencyOf(part),
            userLexicon.frequencyOf(part) * USER_WORD_WEIGHT,
        )
        for (i in 1 until word.length) {
            val left = word.substring(0, i)
            val leftFreq = freqOf(left)
            if (leftFreq <= 0) continue
            val right = word.substring(i)
            val rightFreq = freqOf(right)
            if (rightFreq > 0) {
                val score = ln(1.0 + minOf(leftFreq, rightFreq) * WEIGHT_SPLIT)
                results.add("$left $right" to score)
            }
            // Boundary char dropped: both halves must be real words of some
            // substance — single-letter halves ("a", "i") explain nearly any
            // string and would fire on every stumble.
            if (i + 1 < word.length - 1 && word[i] in spaceAdjacentKeys && left.length >= 2) {
                val tail = word.substring(i + 1)
                if (tail.length >= 2) {
                    val tailFreq = freqOf(tail)
                    if (tailFreq > 0) {
                        val score = ln(1.0 + minOf(leftFreq, tailFreq) * WEIGHT_SPLIT_DROPPED)
                        results.add("$left $tail" to score)
                    }
                }
            }
        }
        return results
    }

    /**
     * The fixed-spelling map's answer for exactly [composing], or null.
     *
     * The composing preview calls this so a listed spelling shows up while the
     * word is still being typed — "tmr" reads তোমার at the r, not at the
     * space. Only the map layer, deliberately: it is keyed on the whole buffer,
     * so it either hits or it doesn't and the preview never flickers between
     * dictionary siblings on its way to the end of a word. It is also the layer
     * that wins [bengaliSuggestions] outright, so what the preview shows is
     * what a space would commit.
     */
    fun bengaliSpelling(composing: String): String? =
        spellings.lookup(composing).firstOrNull { !suppressed(it) }

    private fun bengaliSuggestions(composing: String, limit: Int): List<String> {
        val phonetic = AvroPhonetic.transliterate(composing)
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
        val siblings = bengaliIndex.lookup(composing)
        val literalFreq = bengaliIndex.frequencyOf(phonetic)
        val topSiblingFreq = siblings.firstOrNull()?.let { bengaliIndex.frequencyOf(it) } ?: 0
        if (literalFreq > 0 && topSiblingFreq < literalFreq * SIBLING_CONFIDENCE) {
            ordered.add(phonetic)
        }
        ordered.addAll(siblings)
        ordered.add(phonetic)
        return ordered.asSequence().filterNot(::suppressed).take(limit).toList()
    }

    private fun nextWords(previousWord: String?, previousWord2: String?, limit: Int): List<String> {
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
        return result
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
    data class CorrectionDecision(val apply: String? = null, val offer: String? = null)

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
     * it, or its score beats the runner-up by [autocorrectConfidence]. A
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
     */
    fun decideCorrection(
        word: String,
        touch: List<TouchPoint?>? = null,
        timingMultiplier: Double = 1.0,
    ): CorrectionDecision {
        val lower = word.lowercase()
        if (lower.length < 3) return NO_CORRECTION
        // An all-caps word is a deliberate acronym or shout, not a typo of a
        // lowercase word — don't "correct" it away when the user asked us not to.
        if (skipAllCapsAutocorrect && isAllCaps(word)) return NO_CORRECTION
        if (inDictionaries(lower) || userLexicon.isEstablished(lower, learnedWordMinCount)) {
            return NO_CORRECTION
        }
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
        val candidates = rankedFor(
            lower, FuzzyBeamSearch.AUTOCORRECT_K / 2, touch,
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
                0 -> c.completedChars == 1 && c.word != lower
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
            if (c.edits == 0) {
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
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                )
                CorrectionStats.Penalty.NONE -> c
            }
        }.sortedWith(
            compareByDescending<FuzzyBeamSearch.ScoredCandidate> { it.score }.thenBy { it.word }
        )

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
            return CorrectionDecision(apply = matchCase(word, bestDict))
        }

        val effectiveConfidence = (
            autocorrectConfidence *
                (if (adaptiveConfidence) correctionStats.confidenceMultiplier() else 1.0) *
                timingMultiplier
            ).coerceIn(MIN_AUTOCORRECT_CONFIDENCE, MAX_AUTOCORRECT_CONFIDENCE)
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
        val penalized = top != null &&
            correctionStats.penalty(lower, top.word) != CorrectionStats.Penalty.NONE
        val single = when {
            top == null -> null
            // A penalized candidate with no competition stays a suggestion:
            // the user already told us once that this exact fix was wrong.
            candidates.size == 1 && penalized -> null
            margin < ln(effectiveConfidence) -> null
            else -> top.word
        }
        if (single != null) return CorrectionDecision(apply = matchCase(word, single))
        // No single word explains the typed string; a missing space might.
        splitCorrection(lower, top?.score, effectiveConfidence)?.let {
            return CorrectionDecision(apply = matchCase(word, it))
        }
        // Nothing was confident enough to apply. Something may still be worth
        // asking about: this is where a correction that was probably right
        // used to be dropped on the floor because "probably" is not enough to
        // rewrite somebody's word behind their back.
        val offer = top
            ?.takeUnless { penalized }
            ?.takeIf { margin >= ln(effectiveConfidence) * OFFER_MARGIN_FRACTION }
            ?.word
        return CorrectionDecision(offer = offer?.let { matchCase(word, it) })
    }

    /**
     * Missing-space autocorrect: "kortehobe" → "korte hobe", including the
     * fat-fingered-space reading ("amibtomake" → "ami tomake"). Considered
     * only after every single-word gate declined, and held to the same
     * confidence discipline: the best split must beat the best single-word
     * candidate, the runner-up split, and the solo floor by the gate margin,
     * with halves of at least two letters. The committed text becomes two
     * words — the IME's learn/revert paths already handle multi-word commits.
     */
    private fun splitCorrection(
        lower: String,
        bestWordScore: Double?,
        effectiveConfidence: Double,
    ): String? {
        if (!autocorrectSplits) return null
        if (lower.length < SPLIT_AUTOCORRECT_MIN_LENGTH) return null
        val splits = splitCandidates(lower)
            .filter { (candidate, _) ->
                candidate.split(' ').all { it.length >= 2 && !suppressed(it) }
            }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }
            )
        val (best, bestScore) = splits.firstOrNull() ?: return null
        // The same pair memory word corrections use: a split the user
        // reverted is never forced on them again.
        if (correctionStats.penalty(lower, best) != CorrectionStats.Penalty.NONE) return null
        val rival = maxOf(
            bestWordScore ?: Double.NEGATIVE_INFINITY,
            splits.getOrNull(1)?.second ?: Double.NEGATIVE_INFINITY,
            SOLO_RUNNER_UP_SCORE,
        )
        if (bestScore - rival < ln(effectiveConfidence)) return null
        return best
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
    private fun isAllCaps(word: String): Boolean =
        word.length > 1 && word.any { it.isLetter() } && word.all { !it.isLetter() || it.isUpperCase() }

    /** Applies the typed word's capitalization pattern to a suggestion. */
    private fun matchCase(typed: String, suggestion: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } ->
            suggestion.uppercase()
        typed.firstOrNull()?.isUpperCase() == true ->
            suggestion.replaceFirstChar { it.uppercase() }
        else -> suggestion
    }
}
