package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.File

/**
 * The smart punctuation key's model: a small bidirectional encoder read from
 * a downloaded `.wmpt` (`wm-engine`'s `punct`, through [PredictionNative]),
 * asked on each keystroke which mark the sentence so far is heading for.
 *
 * The answer is a probability for each of [MARKS], in that order: a full
 * stop, a question mark, an exclamation mark, and a comma for a clause the
 * writer goes on from. One model reads every language it was trained on
 * ([languages]) and needs no telling which; a word it does not know is
 * spelled in pieces, so a name or a new word still carries what it can.
 *
 * Holds the mapped model only. Nothing is kept between calls, so one
 * instance serves any thread; the service asks it off the main thread and
 * publishes the mark to the key.
 */
class PunctuationModel private constructor(
    private val handle: Long,
    /** The language ids the model was trained on. */
    val languages: List<String>,
    val name: String,
    val vocabularySize: Int,
) {

    @Volatile
    private var closed = false

    /**
     * The probability of each of [MARKS] for the sentence so far, words
     * oldest first (the word being typed last). An empty array when closed.
     */
    fun predict(words: List<String>): FloatArray {
        if (closed) return FloatArray(0)
        return PredictionNative.punctPredict(handle, words.toTypedArray())
    }

    /** Whether the vocabulary holds [word] as one token. */
    fun knows(word: String): Boolean = !closed && PredictionNative.punctKnows(handle, word)

    fun close() {
        if (closed) return
        closed = true
        PredictionNative.punctClose(handle)
    }

    companion object {
        /** The marks, in the order [predict] answers them. */
        val MARKS: List<String> = listOf(".", "?", "!", ",")
        const val PERIOD = 0
        const val QUESTION = 1
        const val EXCLAIM = 2
        const val COMMA = 3

        /** Opens [file], or null when it is missing or not a model this build reads. */
        fun open(file: File): PunctuationModel? {
            if (!file.isFile) return null
            val handle = PredictionNative.punctOpen(file.path)
            if (handle == 0L) return null
            val info = PredictionNative.punctInfo(handle)
            if (info.getOrNull(2) != MARKS.joinToString("")) {
                PredictionNative.punctClose(handle)
                return null
            }
            val out = PunctuationModel(
                handle,
                languages = info.getOrNull(1)?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                name = info.getOrNull(0).orEmpty(),
                vocabularySize = info.getOrNull(3)?.toIntOrNull() ?: 0,
            )
            NativeHandles.track(out, handle, PredictionNative::punctClose)
            return out
        }

        /**
         * The mark the key should show for [probabilities]: the likeliest of
         * the three end marks, or, with [allowComma], of all four. Null for an
         * empty answer. The three are judged with the comma folded away, so a
         * sentence the model thinks unfinished still gets its likeliest end.
         */
        fun markFor(probabilities: FloatArray, allowComma: Boolean): Int? {
            val among = if (allowComma) MARKS.size else MARKS.size - 1
            if (probabilities.size < among) return null
            var best = 0
            for (i in 1 until among) if (probabilities[i] > probabilities[best]) best = i
            return best
        }
    }
}
