package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.util.PhoneGpu

/**
 * How the keyboard asks [WhisperCpp] for a clip: which languages, how hard to
 * search, how long a clip may run and what to prompt with. Kept apart from the
 * service so each choice is a plain function with a test.
 */
object WhisperCppPlan {

    /**
     * The longest clip a whisper.cpp model is handed. It reads past Whisper's
     * 30-second window by moving the window along, so the clip is bounded by
     * how long anyone dictates in one breath and how long the wait afterwards
     * is, not by the model.
     */
    const val MAX_CLIP_SECONDS = 120

    /** Hypotheses beam search keeps: OpenAI's own default. */
    private const val BEAM = 5

    /** At most this many of the user's words go into a prompt. */
    const val PROMPT_WORDS = 40

    /**
     * Beam search on the sizes where it is cheap beside the encoder, greedy on
     * Medium and Large, whose every decoder step already costs a phone dearly.
     * Turbo has a four-layer decoder, so it searches like the small ones. On
     * LibriSpeech, beam search cut tiny.en's word error rate from 10.2 to 9.4 %
     * on clean speech and from 54 to 49 % with people talking behind it.
     */
    fun beamSize(model: WhisperModel): Int = when (model.size) {
        WhisperSize.TINY, WhisperSize.BASE, WhisperSize.SMALL, WhisperSize.TURBO -> BEAM
        WhisperSize.MEDIUM, WhisperSize.LARGE -> 1
    }

    /**
     * Whether [model] asks for the GPU under [compute] (a [WhisperCompute]
     * value). Auto leaves Tiny on the CPU: its encoder takes a few hundred
     * milliseconds on a phone's cores, about what starting the GPU costs.
     * Auto also stays on the CPU unless the GPU is an [adreno]: the Vulkan
     * build other GPUs take is a large download of its own, and on many Mali
     * GPUs no faster than the cores, so only GPU itself asks for it.
     * Whether the phone can is [WhisperCpp]'s question, not this one.
     */
    fun wantsGpu(model: WhisperModel, compute: String, adreno: Boolean = PhoneGpu.adreno): Boolean = when (compute) {
        WhisperCompute.GPU -> true
        WhisperCompute.CPU -> false
        else -> adreno && model.size != WhisperSize.TINY
    }

    /**
     * The Whisper codes to hand [WhisperCpp.transcribe] for a dictation in
     * [languageIds] (keyboard ids, first is the main one). An English-only
     * model takes none, and a model held to one language
     * ([WhisperModel.decodeAs]) takes that one. Languages Whisper does not
     * know are dropped; when that leaves none, the model detects among all of
     * its own.
     */
    fun languages(model: WhisperModel, languageIds: List<String>): List<String> {
        model.decodeAs?.let { return listOf(it) }
        if (!model.multilingual) return emptyList()
        return languageIds.mapNotNull(WhisperLanguages::codeForLanguage).distinct()
    }

    /**
     * A prompt that leads Whisper towards the spellings in [words], or null when
     * there are none. Whisper reads a prompt as text that came just before the
     * clip, so a short list of names and terms makes them likelier to be
     * written that way, the way FUTO's keyboard feeds its dictionary in.
     */
    fun prompt(model: WhisperModel, words: List<String>): String? =
        if (model.takesPrompt) prompt(words) else null

    /** [prompt] for a model that takes one. */
    fun prompt(words: List<String>): String? {
        val picked = words.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.none(Char::isISOControl) }
            .distinct()
            .take(PROMPT_WORDS)
            .toList()
        if (picked.isEmpty()) return null
        return "Glossary: ${picked.joinToString(", ")}."
    }
}
