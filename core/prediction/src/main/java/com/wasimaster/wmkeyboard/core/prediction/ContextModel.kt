package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.File

/**
 * Where the text is being written, for the context model's conditioning
 * prefix: a chat, an email, a search box, or anywhere else. Pairs with
 * [Register], which says who it is written to.
 */
enum class ContextField { OTHER, CHAT, EMAIL, SEARCH }

/**
 * The neural context model for a language: a small word-level transformer
 * read from a downloaded `.wmlm` (`wm-engine`'s `lm`, through
 * [PredictionNative]), asked once per committed word what comes next.
 *
 * One distribution serves the whole strip. The strip's candidates are
 * scored by lookup ([scores]) and the context reranker folds that in beside
 * the n-gram stores; the next-word strip takes the head of it ([topWords]);
 * a phrase to offer is a few greedy steps ([phrase]); a word already
 * committed is judged against its alternatives with the word after it
 * ([revise]). A word the vocabulary does not hold gets no score (NaN), never
 * a penalty: the lists and the user's own lexicon decide those.
 *
 * Holds the mapped model and one session on it. Calls on the session are
 * serialised in Rust; this side only crosses with arrays. Closed with the
 * engine that holds it, or when the model file is swapped.
 */
class ContextModel private constructor(
    private val model: Long,
    private val session: Long,
    /** The language ids the model's `<lang:n>` tokens stand for, in order. */
    val languages: List<String>,
    val name: String,
    val vocabularySize: Int,
) {

    @Volatile
    private var closed = false

    /** Which of [languages] the keyboard is on, 0 when the model has one language. */
    fun slotOf(languageId: String): Int = languages.indexOf(languageId).coerceAtLeast(0)

    /**
     * Reads the words before the caret, oldest first, under the prefix the
     * caller derives from where it is typing. Cheap when the context grew by
     * a word since the last call; the whole sentence otherwise.
     */
    fun setContext(words: List<String>, register: Register = Register.NEUTRAL, field: ContextField = ContextField.OTHER, lang: Int = 0) {
        if (closed) return
        PredictionNative.lmSetContext(session, register.ordinal, field.ordinal, lang, words.toTypedArray())
    }

    /**
     * ln P(word next) for each of [words], NaN for a word the vocabulary does
     * not hold as one token. Pieces are not spelled here: this is asked per
     * keystroke for the whole strip; [gateView] and [revise] spell them.
     */
    fun scores(words: List<String>): FloatArray {
        if (closed || words.isEmpty()) return FloatArray(words.size) { Float.NaN }
        return PredictionNative.lmScores(session, words.toTypedArray())
    }

    /**
     * [scores] with a word the vocabulary lacks spelled through its pieces, NaN
     * only for a word it cannot spell either (#1016). Up to six forward steps a
     * word, so this is for a stroke's candidates or one leader, never a strip
     * per keystroke. An engine library older than the call answers as [scores].
     */
    fun scoresSpelled(words: List<String>): FloatArray {
        if (closed || words.isEmpty()) return FloatArray(words.size) { Float.NaN }
        return try {
            PredictionNative.lmScoresSpelled(session, words.toTypedArray())
        } catch (_: UnsatisfiedLinkError) {
            scores(words)
        }
    }

    /** The model's view of a word as typed, for the learned gate; see [gateView]. */
    class GateView(val logprob: Float, val rank: Int, val entropy: Float, val piecesLogprob: Float)

    /**
     * The model's view of [typed] where it stands: its log-probability, how
     * many words rank above it (-1 when the model cannot read it), and the
     * entropy of the next-word distribution. Null when closed.
     */
    fun gateView(typed: String): GateView? {
        if (closed) return null
        val out = PredictionNative.lmGateView(session, typed)
        if (out.size < 4) return null
        return GateView(out[0], if (out[1].isNaN()) -1 else out[1].toInt(), out[2], out[3])
    }

    /** The [k] likeliest next words above [minLogprob], best first, with their log-probabilities. */
    fun topWords(k: Int, minLogprob: Float = NEXT_WORD_FLOOR): List<Pair<String, Float>> {
        if (closed || k <= 0) return emptyList()
        val out = PredictionNative.lmTop(session, k, minLogprob)
        @Suppress("UNCHECKED_CAST")
        val words = out[0] as Array<String>
        val scores = out[1] as FloatArray
        return words.indices.map { words[it] to scores[it] }
    }

    /**
     * Up to [maxWords] words the model would write next, each at least
     * [minProb] likely at its step, with the joint log-probability; empty when
     * the model is not sure of even one. The context is left as it was.
     */
    fun phrase(maxWords: Int = 3, minProb: Float = PHRASE_MIN_PROB): Pair<List<String>, Float> {
        if (closed || maxWords <= 0) return emptyList<String>() to 0f
        val out = PredictionNative.lmGenerate(session, maxWords, minProb)
        @Suppress("UNCHECKED_CAST")
        val words = out[0] as Array<String>
        val joint = (out[1] as FloatArray).firstOrNull() ?: 0f
        return words.toList() to joint
    }

    /**
     * How well each of [candidates] fits where the word before [followers]
     * stands, after [context] (oldest first): ln P(candidate | context) plus
     * each follower's log-probability in turn with the candidate in place.
     * NaN for a word the model cannot read. The context is left as it was.
     */
    fun revise(
        context: List<String>,
        candidates: List<String>,
        followers: List<String>,
        register: Register = Register.NEUTRAL,
        field: ContextField = ContextField.OTHER,
        lang: Int = 0,
    ): FloatArray {
        if (closed || candidates.isEmpty()) return FloatArray(candidates.size) { Float.NaN }
        return PredictionNative.lmRevise(
            session, register.ordinal, field.ordinal, lang,
            context.toTypedArray(), candidates.toTypedArray(), followers.toTypedArray(),
        )
    }

    /** Whether the vocabulary holds [word]. */
    fun knows(word: String): Boolean = !closed && PredictionNative.lmKnows(model, word)

    fun close() {
        if (closed) return
        closed = true
        PredictionNative.lmSessionClose(session)
        PredictionNative.lmClose(model)
    }

    companion object {
        /** The least likely a next word may be, in log-probability, to be offered on an empty buffer. */
        const val NEXT_WORD_FLOOR = -8f

        /** How likely each word of an offered phrase must be at its step. */
        const val PHRASE_MIN_PROB = 0.3f

        /** The file a language's model is kept in, under `dict/<langId>/`. */
        const val FILE_NAME = "context.wmlm"

        /** Opens [file], or null when it is missing or not a model this build reads. */
        fun open(file: File): ContextModel? {
            if (!file.isFile) return null
            val model = PredictionNative.lmOpen(file.path)
            if (model == 0L) return null
            val session = PredictionNative.lmSessionOpen(model)
            if (session == 0L) {
                PredictionNative.lmClose(model)
                return null
            }
            val info = PredictionNative.lmInfo(model)
            val out = ContextModel(
                model, session,
                languages = info.getOrNull(1)?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                name = info.getOrNull(0).orEmpty(),
                vocabularySize = info.getOrNull(2)?.toIntOrNull() ?: 0,
            )
            NativeHandles.track(out, session, PredictionNative::lmSessionClose)
            NativeHandles.track(out, model, PredictionNative::lmClose)
            return out
        }
    }
}
