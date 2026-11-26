package com.wasimaster.wmkeyboard.core.voice

import kotlin.math.sqrt

/**
 * Keeps a clip-based engine (offline Whisper, the transcription server) from
 * typing words nobody said.
 *
 * Whisper never answers "nothing": handed a silent or near-silent window it
 * writes the most likely caption for one, which after training on subtitled
 * video is "Thank you." or "Thanks for watching!", and on steady noise it can
 * fall into repeating one word until the token budget runs out. The system
 * recognizer reports NO_SPEECH for the same audio, so this is the clip
 * engines' own problem, and it is solved on both sides of the model: a clip
 * with nothing in it is never transcribed ([hasSpeech]), and a transcript that
 * is one of the stock phrases over a faint clip is not typed ([clean]).
 *
 * The levels are RMS of the loudest 100 ms, in the recorder's [-1, 1] floats.
 * They sit well under where the panel's level ring first moves (0.02), so
 * nothing the ring reacts to is ever held back.
 */
object VoiceClipGate {

    /** 0.3 s at 16 kHz: shorter than any word, so a tap-and-release, not speech. */
    const val MIN_SAMPLES = 4_800

    /** 100 ms at 16 kHz. */
    private const val WINDOW = 1_600

    /** About -50 dBFS: a quiet room, a pocket, or a microphone handing over zeros. */
    private const val SILENT_LEVEL = 0.003f

    /** About -40 dBFS: something is there, but quieter than anyone speaks into a phone. */
    private const val FAINT_LEVEL = 0.01f

    /** A word or short phrase said this many times running is a decoder loop, not speech. */
    private const val LOOP_REPEATS = 5
    private const val LOOP_MAX_PHRASE = 4

    /** RMS of the loudest [WINDOW] of [pcm]; 0 for a clip shorter than one window. */
    fun peakLevel(pcm: FloatArray): Float {
        var peak = 0.0
        var start = 0
        while (start + WINDOW <= pcm.size) {
            var sumSq = 0.0
            for (i in start until start + WINDOW) sumSq += pcm[i].toDouble() * pcm[i]
            if (sumSq > peak) peak = sumSq
            start += WINDOW
        }
        return sqrt(peak / WINDOW).toFloat()
    }

    /** Whether [pcm] is worth transcribing at all. */
    fun hasSpeech(pcm: FloatArray): Boolean =
        pcm.size >= MIN_SAMPLES && peakLevel(pcm) >= SILENT_LEVEL

    /** Loud enough to transcribe, too quiet to take a stock phrase from at its word. */
    fun isFaint(pcm: FloatArray): Boolean = peakLevel(pcm) < FAINT_LEVEL

    /**
     * What to type for [text], the transcript of a clip that was [faint] or not;
     * empty when it should not be typed at all.
     *
     * "Thank you" is also something people dictate, so a stock phrase is only
     * dropped when the clip gives no reason to believe it. A sound caption
     * ("[Music]", "(coughs)") is dropped whatever the level, since it describes
     * the audio rather than transcribing it.
     */
    fun clean(text: String, faint: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || CAPTION.matches(trimmed)) return ""
        val unlooped = collapseLoops(trimmed)
        if (faint && isStockPhrase(unlooped)) return ""
        return unlooped
    }

    private fun isStockPhrase(text: String): Boolean {
        val key = text.lowercase().replace(NOT_WORD, " ").trim().replace(SPACES, " ")
        return key.isEmpty() || key in STOCK_PHRASES || STOCK_PREFIXES.any { key.startsWith(it) }
    }

    /** Folds a word or short phrase repeated [LOOP_REPEATS] or more times running into one. */
    private fun collapseLoops(text: String): String {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < LOOP_REPEATS) return text
        val keys = words.map { w -> w.lowercase().trim { !it.isLetterOrDigit() } }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var skipped = false
            for (period in 1..LOOP_MAX_PHRASE) {
                var repeats = 1
                while (repeatsAt(keys, i, period, repeats)) repeats++
                if (repeats >= LOOP_REPEATS) {
                    for (k in 0 until period) out.add(words[i + k])
                    i += period * repeats
                    skipped = true
                    break
                }
            }
            if (!skipped) out.add(words[i++])
        }
        return if (out.size == words.size) text else out.joinToString(" ")
    }

    /** Whether the [period] words at [start] occur again as the [repeat]-th copy after them. */
    private fun repeatsAt(keys: List<String>, start: Int, period: Int, repeat: Int): Boolean {
        val from = start + period * repeat
        if (from + period > keys.size) return false
        for (k in 0 until period) {
            if (keys[start + k].isEmpty() || keys[start + k] != keys[from + k]) return false
        }
        return true
    }

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 50 KB across this file's patterns.
    /** A transcript that is nothing but one bracketed or starred sound caption. */
    private val CAPTION by lazy { Regex("""^(\[[^\]]*\]|\([^)]*\)|\*[^*]*\*)[.!?]*$""") }
    private val NOT_WORD by lazy { Regex("""[^\p{L}\p{N}]+""") }
    private val SPACES by lazy { Regex(""" +""") }

    /** Whisper's captions for silence, as they come out after [isStockPhrase] normalizes them. */
    private val STOCK_PHRASES = setOf(
        "you", "so", "okay", "ok", "bye", "bye bye", "thanks", "thank you", "thank you very much",
        "thank you so much", "thanks for watching", "thank you for watching",
        "thanks for listening", "thank you for listening", "please subscribe",
        "like and subscribe", "see you next time", "see you in the next video",
    )
    private val STOCK_PREFIXES = listOf(
        "subtitles by", "subtitled by", "translated by", "transcribed by", "captions by",
    )
}
