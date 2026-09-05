package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId

/**
 * Turns keystrokes into committed text for scripts that need more than a 1:1
 * append. Generalises the gates that used to be hard-coded to Bengali
 * (`isPhonetic`, `isFixedBengali`) and the dead-key path behind one type, chosen
 * from a layout's script and optional composer override — so a new complex
 * script is a registry entry, not another branch in the service.
 */
interface Composer {

    /**
     * How many chars to remove from the end of [before] to delete one visual
     * unit: a whole grapheme cluster for complex scripts, a surrogate pair or a
     * single char otherwise.
     */
    fun deleteLength(before: CharSequence): Int = defaultDeleteLength(before)

    /**
     * A transliterator (Avro, Hangul): its composing buffer *is* the input
     * method, so it must run even in password fields and with the suggestion
     * strip off, where plain suggestion-composing does not.
     */
    val isTransliterating: Boolean get() = false

    /**
     * Specifically the Bengali phonetic transliterator (Avro): its commit and
     * suggestions route through the Bengali dictionary path. Other
     * transliterators (Hangul) compose but do not, so this stays false for them.
     */
    val isBengaliPhonetic: Boolean get() = false

    /**
     * A fixed complex-script layout (Probhat, and later Devanagari, Tamil …):
     * types script characters directly and shapes clusters / contextual vowel
     * forms. The registry-era replacement for `isFixedBengali`.
     */
    val isClusterShaping: Boolean get() = false

    /**
     * Whether digit keys feed the composing buffer instead of committing it and
     * typing the digit. Vietnamese VNI needs this — its tones and marks are
     * spelled with the digits 0–9, which the transducer consumes — as does T9
     * pinyin, whose whole alphabet is digits.
     */
    val bufferDigits: Boolean get() = false

    /**
     * Whether a digit may *begin* a composing buffer, not merely continue one.
     * VNI's digits are tone marks applied to a syllable already being typed, so a
     * digit on an empty buffer is a literal digit there; T9 pinyin is the opposite
     * — every keystroke is a digit, so gating on a non-empty buffer would make the
     * first key of every word commit as a number. Only meaningful with
     * [bufferDigits].
     */
    val digitsStartBuffer: Boolean get() = false

    /**
     * Whether [c] is a non-letter this composer still takes into its buffer. The
     * buffer otherwise admits only letters, the apostrophe and (with
     * [bufferDigits]) digits, which is exactly right for spelling-based methods
     * — but 笔画 stroke input spells with `*`, its wildcard stroke. Without this
     * the wildcard key commits whatever is composing and types a literal star
     * instead of widening the search.
     */
    fun buffersChar(c: Char): Boolean = false

    /**
     * A conversion IME (Chinese Pinyin, Japanese kana→kanji): the roman/kana
     * buffer maps to a *choice* of outputs shown in the suggestion strip, and the
     * user taps one to commit it — unlike a plain transliterator whose buffer has
     * a single deterministic rendering. When true, the strip shows [candidates]
     * instead of dictionary suggestions and a tap commits with no trailing space.
     */
    val isConversion: Boolean get() = false

    /**
     * The candidate conversions of [buffer] for a conversion IME, best first
     * (Pinyin → Hanzi words, kana → kanji). Empty for every non-conversion
     * composer. The composing region still shows [composeBuffer].
     */
    fun candidates(buffer: String): List<String> = emptyList()

    /**
     * [candidates] widened for a paging surface that shows more than the strip.
     * The default narrows the base list, which is right for every composer whose
     * ranking has no limit to raise.
     *
     * Implementations must keep the shorter list a prefix of the longer one —
     * `candidates(b, 100).take(12) == candidates(b, 12)` — or the strip and the
     * expanded grid would disagree about which candidate is which, and
     * [consumedForIndex] would resolve a tap against the wrong one. The way to
     * guarantee it is to rank once to a fixed depth and truncate, never to let
     * the requested depth change the ordering.
     */
    fun candidates(buffer: String, limit: Int): List<String> = candidates(buffer).take(limit)

    /**
     * How many chars of the input [buffer] the [chosen] candidate consumed —
     * the linchpin of prefix commit. Picking 你 for `nihao` consumes only the
     * `ni` (2), so a commit deletes those chars and re-converts the `hao` tail
     * instead of wiping the whole buffer. Whole-buffer composers (and the raw
     * fallback, where [chosen] is the reading itself) consume everything, so the
     * default returns [buffer]'s length.
     */
    fun consumedFor(buffer: String, chosen: String): Int = buffer.length

    /**
     * [consumedFor] resolved by strip position rather than by text.
     *
     * A candidate's text does not identify it: `ja_kana` genuinely lists 行 under
     * い, いき, ゆき and こう, so for the buffer `ikitai` the same string is
     * reachable at a one-mora span and a two-mora one. Matching by text picks
     * whichever comes first and silently eats a mora the user never chose. The
     * index is unambiguous, and the caller always has it.
     */
    fun consumedForIndex(buffer: String, index: Int): Int =
        consumedFor(buffer, candidates(buffer).getOrElse(index) { "" })

    /**
     * Records that the candidate at [index] was the one the user wanted, so the
     * same reading offers it first from now on ([CjkLearning]).
     *
     * The composer does this rather than the service because only it knows which
     * *reading* the pick covered — a commit consumes a prefix of the buffer, and
     * that prefix is the key worth learning, not the whole thing — and which
     * reading space it belongs to. A no-op for everything that does not convert.
     */
    fun learnChoice(buffer: String, index: Int) {
        // Nothing to learn: the default composer has no candidate list to reorder.
    }

    /** A transliterator's buffer (roman, or jamo) rendered as script text. */
    fun composeBuffer(buffer: String): String = buffer

    /**
     * What a key typing [key] would write, given the roman [buffer] already
     * composing — the ক a `k` writes at a word start, and after a consonant
     * either the ্ক it adds or the ক্ক that leaves ([wholeCluster]). The
     * keyboard draws it as a corner hint so a roman grid says what script it
     * is about to type.
     *
     * Null means "no useful preview": every composer whose keys already show
     * what they commit. An empty string is a different answer — the key adds
     * nothing visible (Avro's inherent "o" after a consonant) — and the caller
     * draws no hint for it either way.
     */
    fun keyPreview(buffer: String, key: String, wholeCluster: Boolean): String? = null

    /**
     * The form a just-typed character takes given the character before the
     * cursor — a Bengali vowel key becoming its kar / glide / independent form.
     * Identity for scripts without contextual forms.
     */
    fun contextualForm(text: String, before: Char?): String = text
}

/** One visual unit at the end of [before]: a surrogate pair, else one char. */
internal fun defaultDeleteLength(before: CharSequence): Int {
    if (before.isEmpty()) return 0
    val last = before.length - 1
    return if (last >= 1 && Character.isSurrogatePair(before[last - 1], before[last])) 2 else 1
}

/**
 * No special composing: Latin, Cyrillic, Greek. Dead-key accent fusion is a
 * separate service-level state machine over combining marks (see `DeadKeys`),
 * not a per-character transform, so `ComposerType.DEAD_KEY` maps here too.
 */
object NoComposer : Composer

/**
 * The [Composer] a layout uses, from its resolved [script] and [type]
 * (`LayoutSpec.composerType()`). Unknown/unbuilt composers degrade to
 * [NoComposer] so the layout still types.
 */
fun composerFor(script: ScriptDef, type: ComposerType): Composer = when (type) {
    ComposerType.NONE, ComposerType.DEAD_KEY -> NoComposer
    ComposerType.INDIC_CLUSTER -> IndicClusterComposer(script)
    ComposerType.TRANSLITERATE -> when (script.id) {
        ScriptId.BENGALI -> BengaliTransliterateComposer
        else -> NoComposer
    }
    ComposerType.HANGUL -> HangulComposer
    ComposerType.TELEX -> VietnameseTelexComposer
    ComposerType.VNI -> VietnameseVniComposer
    ComposerType.ROMAJI -> JapaneseComposer
    ComposerType.PINYIN -> PinyinComposer
    ComposerType.STROKE -> StrokeComposer
    ComposerType.T9_PINYIN -> T9PinyinComposer
    ComposerType.ZHUYIN -> ZhuyinComposer
    ComposerType.CANGJIE -> CangjieComposer
    ComposerType.CANGJIE_QUICK -> CangjieQuickComposer
    ComposerType.JYUTPING -> JyutpingComposer
}
