package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.input.imported.ImportedInputMethods
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.text.Graphemes

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
     * Whether a word read back out of the field may be re-armed as the composing
     * buffer at all — the word-independent half of [resumeBuffer], and the reason
     * it is asked before the field is read.
     *
     * False for every composer whose buffer is not the field's text: Avro's
     * buffer is the roman source of Bengali text that cannot be reversed back
     * into it, Hangul's is jamo, and a conversion IME's is a reading with a whole
     * choice of outputs behind it. Asking this first keeps a blocking read of the
     * focused app off the caret path for the layouts that could never use it.
     *
     * Vietnamese Telex and VNI are the exception among transliterators: their
     * output is a pure function of their keystrokes, so a word already in the
     * field can be spelled back into keys ([resumeBuffer]) and edited further.
     */
    val resumesComposedText: Boolean get() = !isTransliterating

    /**
     * The composing buffer that reproduces [text] — the word the caret was put
     * back into, read out of the field — or null when this composer cannot take
     * it.
     *
     * Only asked when [resumesComposedText] is true. The buffer is the field's
     * text itself for most layouts, so the default is the identity; a
     * transliterator overrides it to spell its own output back into keys, and
     * answers null for anything its keys would not compose exactly, which leaves
     * the word with the read-only treatment it has today.
     */
    fun resumeBuffer(text: String): String? = if (isTransliterating) null else text

    /**
     * The composing buffer after one backspace — what is left of [buffer] once
     * the last thing the user can *see* has been taken off it.
     *
     * Not the same as dropping the last key. A transliterator's buffer holds
     * the keys while the field holds what they spelled, and the last letter on
     * screen may sit several keys back with a tone key or two riding it: one
     * backspace over `huowngs` (`hướng`) has to leave `hướn`, not `hương`.
     *
     * The default drops one key, which is what every composer whose keys *are*
     * its letters needs. Vietnamese overrides it to take the last letter of
     * its output and keep the marks of the letters before it.
     */
    fun backspaceBuffer(buffer: String): String =
        if (buffer.isEmpty()) buffer else buffer.dropLast(1)

    /**
     * The language whose dictionary a phonetic transliterator is ranked against
     * (`"bn"` for Avro), by `LanguageDef.id`: its commit and suggestions route
     * through that language's phonetic index and spelling map rather than
     * committing the rules' output as it stands. Null for every transliterator
     * that composes but has no such dictionary pass (Hangul, Telex).
     */
    val phoneticLanguage: String? get() = null

    /**
     * The language whose word list completes this composer's *output*: the
     * strip offers that language's words beginning with [composeBuffer] of the
     * buffer, and a space still commits the composed text exactly. For a
     * deterministic transliterator (Khipro) whose keys already spell the word,
     * so there is nothing to rank or correct but a word to finish. Null for
     * everything else, including Avro, whose buffer goes through
     * [phoneticLanguage] instead.
     */
    val completionLanguage: String? get() = null

    /**
     * Keys other than letters that a swipe over this layout may pass through as
     * part of a word, by the character they type. Khipro's slicer `/` makes
     * চন্দ্রবিন্দু and খণ্ড-ত, so a word with either is drawn through it (#541).
     * Empty everywhere else: a stroke that grazes the comma is not spelling one.
     */
    val glideKeys: Set<Int> get() = emptySet()

    /**
     * The buffer holds a Japanese kana reading that a kana key types straight
     * in, so a swipe over a board of kana keys can spell one ([KanaGlide],
     * #958). Whether the board on screen really has those keys is the
     * keyboard's question, not this one's: romaji and flick use the same
     * composer.
     */
    val glidesKanaReadings: Boolean get() = false

    /**
     * Whether this composer spells its buffer in roman letters: Avro's, Hindi
     * phonetic's, Khipro's.
     *
     * What lets a word half-typed on a Latin keyboard carry into the buffer
     * when the language is switched mid-word (#522) — the letters already in
     * the field are the very letters this composer would have taken. False for
     * every composer whose keys are its own script (Hangul's jamo, 천지인,
     * Cangjie's strokes, Zhuyin's bopomofo) and for the conversion IMEs, whose
     * buffer stands for a choice of outputs rather than one reading.
     */
    val isRomanBuffer: Boolean get() = false

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
     * Whether a space pressed while a `Key.multitap` run is still open only
     * closes the run, typing nothing. A 천지인 (Cheonjiin) pad needs this: ㄱ is
     * the key ㄱㅋ tapped once, so ㄱ followed by another ㄱ — 먹고 — is ㄱ, space,
     * ㄱ, the way Samsung's pad spells it, and the second space is the real one.
     * Everywhere else a space is a space.
     */
    val spaceEndsMultitap: Boolean get() = false

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
     * [buffersChar] for a composer whose answer depends on whether a word is
     * already being composed: Khipro's comma is part of a word (`j,,` is জ়)
     * but a comma typed between words is only punctuation. The service asks
     * this one; the default defers to [buffersChar].
     */
    fun buffersChar(c: Char, composing: CharSequence): Boolean = buffersChar(c)

    /**
     * Whether [word] is shaped like a word of this composer's script, and so
     * worth remembering when no dictionary has heard of it.
     *
     * The default is yes, which is the only safe answer for a script whose
     * spelling rules are open enough that anything could be a word. A composer
     * overrides it where they are not: a transliterator turns keystrokes into
     * text mechanically, so it will happily produce a spelling that cannot
     * exist and hand it to the personal dictionary as something new the user
     * types. Read only on the learning path — a word that fails still commits
     * exactly as typed.
     */
    fun isPlausibleWord(word: String): Boolean = true

    /**
     * A conversion IME (Chinese Pinyin, Japanese kana→kanji): the roman/kana
     * buffer maps to a *choice* of outputs shown in the suggestion strip, and the
     * user taps one to commit it — unlike a plain transliterator whose buffer has
     * a single deterministic rendering. When true, the strip shows [candidates]
     * instead of dictionary suggestions and a tap commits with no trailing space.
     */
    val isConversion: Boolean get() = false

    /**
     * Whether a reading left without a conversion — ended by a punctuation
     * mark, an arrow key, a caret move or a layout switch rather than by space
     * or a picked candidate — commits as [typedReading] instead of as its best
     * candidates (#616). True for Japanese, whose kana reading is already text
     * the user can mean, the way Mozc and Gboard confirm it; false for the
     * Chinese composers, whose reading is letters or keys nobody means to send.
     */
    val keepsUnconvertedReading: Boolean get() = false

    /**
     * Whether this conversion composer reads its readings straight off the
     * Latin letter keys — full pinyin, romaji, Jyutping — so that the keys
     * spelling a reading could just as well be spelling an English word.
     *
     * What lets the candidate strip put English words beside the conversions
     * when English is one of the language's secondaries (#633). False for
     * every pad whose keys are not the alphabet: T9's digits, Zhuyin's
     * bopomofo, Cangjie's radicals and the strokes.
     */
    val readsLatinLetters: Boolean get() = false

    /**
     * The [CjkDictCatalog] pack this composer converts with, when that pack is
     * not loaded — else null, which is also the answer for every composer that
     * needs no pack at all.
     *
     * The conversion tables are far too big to bundle, so a fresh install has
     * none of them and a conversion composer starts out able to convert
     * nothing: it keeps typing, committing the raw reading, and no character is
     * ever offered. That is indistinguishable on screen from a keyboard that
     * does not work, so the service names the missing pack in a strip chip
     * instead of leaving the user to find the row in Settings (issue #260).
     *
     * Asked per keystroke, so it stays a field read and a size check.
     */
    val missingPack: String? get() = null

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

    /**
     * Whether a held conversion candidate offers [adjustChoice] (#580): show it
     * earlier, show it later, never show it. False for everything that does not
     * keep a learned history to put the answer in.
     */
    val adjustsChoices: Boolean get() = false

    /**
     * A [CjkDictCatalog] pack that would make this composer's candidates
     * better but that it converts without — offered once [missingPack] is
     * null. Null when there is none, or it is already loaded.
     */
    val improvingPack: String? get() = null

    /**
     * Applies the user's [adjustment] to the candidate at [index] of [buffer]'s
     * list, in the same reading space [learnChoice] learns in.
     */
    fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        // Nothing to adjust: no candidate list of its own.
    }

    /**
     * The readings the next undecided part of [buffer] could be, best first, for
     * a keypad whose keys each stand for several letters: a 九宫格 pinyin `9426`
     * could be xiao, zhan, xi, yi… (issue #635). The keyboard lists them beside
     * the keys, and a pick goes through [pickReading]. Empty for every composer
     * whose keys already say which reading they spell.
     */
    fun readings(buffer: String): List<String> = emptyList()

    /**
     * [buffer] with its next undecided part settled as [reading], one of
     * [readings] — still a buffer, so the candidates re-convert from it and the
     * list moves on to the part after. Null when [reading] does not fit there.
     */
    fun pickReading(buffer: String, reading: String): String? = null

    /**
     * Words to offer once a conversion is committed and nothing is composing
     * (联想, "association"): what is likely to follow [context], the text just
     * before the caret, best first. A pick is written as it stands and asked
     * about again with itself added to [context]. Empty for every composer
     * with nothing to predict from.
     */
    fun predictions(context: String, limit: Int): List<String> = emptyList()

    /**
     * Whether [predictions] reads on from any text, not just Han: Mozc follows
     * 今日は as readily as 今日, and keeps its own context
     * ([CjkContext.text]). The keyboard then asks it even when the Han
     * context it passes is empty.
     */
    val predictsFromAnyText: Boolean get() = false

    /** Records that [picked] was taken from [predictions] for [context]. */
    fun learnPrediction(context: String, picked: String) {
        // Nothing to learn: the default composer predicts nothing.
    }

    /**
     * What stays composing once a candidate has consumed the head of the
     * buffer and [rest] is left. Pinyin's stroke filter (`` `hs ``) narrows
     * the list it was typed for, so it goes with that pick rather than
     * narrowing whatever is read next. The default keeps [rest] whole.
     */
    fun remainderAfterCommit(rest: String): String = rest

    /**
     * Whether this composer converts a reading by segments (文節) whose
     * bounds and focus the user can move: Mozc. The keyboard's arrow keys
     * then reach [resizeSegment] and [moveSegment] while a reading composes,
     * instead of moving the caret. False for everything else.
     */
    val segmentsReading: Boolean get() = false

    /**
     * Widens ([delta] > 0) or narrows the focused segment of [buffer] by one
     * reading character, splitting the reading into segments first if it is
     * not yet. From then on [candidates] are that segment's, and
     * [composeBuffer] shows every segment converted. False when nothing
     * changed.
     */
    fun resizeSegment(buffer: String, delta: Int): Boolean = false

    /** Moves the focus [delta] segments along [buffer]'s; false when nothing changed. */
    fun moveSegment(buffer: String, delta: Int): Boolean = false

    /**
     * Where the focused segment sits in [composeBuffer]'s text, for the
     * composing region to mark it; null while [buffer] is not split into
     * segments.
     */
    fun focusedSegment(buffer: String): IntRange? = null

    /**
     * What picking candidate [index] ([chosen]) of [buffer] writes. Usually
     * [chosen]; with a later segment focused, the segments before it go in
     * too, as they show, since a pick consumes the reading from its start.
     */
    fun committedText(buffer: String, index: Int, chosen: String): String = chosen

    /** A transliterator's buffer (roman, or jamo) rendered as script text. */
    fun composeBuffer(buffer: String): String = buffer

    /**
     * Where a caret at [index] in [buffer] sits in [composeBuffer]'s text: the
     * keyboard keeps the field's caret there while a reading is being edited
     * in the middle (#960). The default renders the keys before the caret on
     * their own, which is right wherever a key's output does not depend on the
     * keys after it.
     */
    fun previewOffset(buffer: String, index: Int): Int =
        composeBuffer(buffer.substring(0, index.coerceIn(0, buffer.length))).length

    /**
     * The buffer position for a caret put at [offset] in [composeBuffer]'s
     * text: the last one whose [previewOffset] is not past it, so a caret
     * dropped inside a kana two keys spelled lands in front of both keys.
     */
    fun bufferIndex(buffer: String, offset: Int): Int {
        var found = 0
        for (index in 1..buffer.length) {
            if (previewOffset(buffer, index) > offset) break
            found = index
        }
        return found
    }

    /**
     * What a transliterator commits when the word ends, where that differs
     * from what [composeBuffer] shows while it is being typed. An m17n ITRANS
     * method shows क् for `k` (the next key may add a vowel) and drops that
     * virama once a space closes the word. The default is [composeBuffer].
     */
    fun finishBuffer(buffer: String): String = composeBuffer(buffer)

    /**
     * What Enter commits for a conversion reading: the reading itself,
     * converted to nothing (#514, #515). Japanese gives the kana the user sees,
     * which is how a kana IME confirms hiragana it is not asked to convert.
     * The default is [composeBuffer]; a composer whose composing region shows
     * something other than the keys pressed answers with the keys instead.
     */
    fun typedReading(buffer: String): String = composeBuffer(buffer)

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

/**
 * One unit at the end of [before] by the keyboard's ordinary backspace rule:
 * a code point, never half a surrogate pair, with an invisible trailing part
 * taken together with what it belongs to (see [Graphemes.backspaceLength]).
 */
internal fun defaultDeleteLength(before: CharSequence): Int = Graphemes.backspaceLength(before)

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
 *
 * [langId] decides between transliterators that share a script: Bengali and
 * Assamese are both written in the Bengali script, Hindi and Marathi in
 * Devanagari, Urdu, Persian and Arabic in the Arabic one, and each has its own
 * phonetic rules ([PhoneticComposers]). Without it a phonetic layout falls back
 * to the script's first language — Bengali, Hindi, Urdu — which is what every
 * caller that only knows a script has always had. Pass it whenever there is a
 * layout to read it from; [resolvedComposer] does.
 */
fun composerFor(script: ScriptDef, type: ComposerType, langId: String? = null): Composer = when (type) {
    ComposerType.NONE, ComposerType.DEAD_KEY -> NoComposer
    ComposerType.INDIC_CLUSTER -> IndicClusterComposer(script)
    ComposerType.TRANSLITERATE -> langId?.let(PhoneticComposers::forLanguage) ?: when (script.id) {
        ScriptId.BENGALI -> BengaliTransliterateComposer
        ScriptId.DEVANAGARI -> HindiTransliterateComposer
        ScriptId.ARABIC -> UrduTransliterateComposer
        else -> NoComposer
    }
    ComposerType.HANGUL -> HangulComposer
    ComposerType.CHEONJIIN -> CheonjiinComposer
    ComposerType.NARATGEUL -> NaratgeulComposer
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
    ComposerType.KHIPRO -> if (script.id == ScriptId.BENGALI) KhiproComposer.CLASSIC else NoComposer
    ComposerType.BORNO_PHONETIC -> if (script.id == ScriptId.BENGALI) BornoPhoneticComposer else NoComposer
    ComposerType.KHIPRO_DARK -> if (script.id == ScriptId.BENGALI) KhiproComposer.DARK else NoComposer
}

/**
 * The composer this layout types through: an imported input method's when the
 * layout is bound to one ([LayoutSpec.inputMethod]), otherwise [composerFor]
 * with the layout's own script, composer override and language, so two
 * languages that share a script get their own phonetic rules. A conversion
 * composer then goes past [ConversionEngines], where a native engine the user
 * picked for the language (RIME for Chinese) may stand in for it.
 */
fun LayoutSpec.resolvedComposer(): Composer {
    inputMethod?.let(ImportedInputMethods::composer)?.let { return it }
    val type = composerType()
    return ConversionEngines.resolve(composerFor(script(), type, langId), type, langId)
}
