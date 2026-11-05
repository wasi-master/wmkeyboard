package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperLanguages
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleLanguages
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore
import java.io.File

/**
 * One engine, and for Whisper, the ExecuTorch engines and Sherpa one model, that can
 * take dictation in a language now. [modelLabel] is the model's own name and
 * is never translated; the engine's name is a string resource the UI owns.
 */
data class VoiceChoice(val engine: String, val modelId: String? = null, val modelLabel: String? = null) {
    /** The value stored per language: `engine`, or `engine:modelId`. */
    val key: String get() = if (modelId.isNullOrEmpty()) engine else "$engine:$modelId"
}

/**
 * What dictation in one language can run on, for the per-language engine
 * setting and the voice panel's engine chip: the same list in both, so the
 * two can never offer different things. Only what works today is listed, which
 * means only downloaded models.
 */
object VoiceChoices {
    const val SYSTEM = "system"
    const val WHISTLE = "whistle"
    const val WHISPER = "whisper"
    const val SERVER = "server"

    /** The order engines are offered in, everywhere they are listed. */
    val ENGINE_ORDER: List<String> = listOf(SYSTEM, WHISTLE, WHISPER) + EtEngine.entries.map { it.id } + SERVER

    /**
     * The choices for [languageId] on this device. [whisperEnabled] is the
     * build's own switch for offline Whisper; [serverConfigured] is whether the
     * transcription server has an address.
     */
    fun choices(
        filesDir: File,
        languageId: String,
        whisperEnabled: Boolean,
        serverConfigured: Boolean,
    ): List<VoiceChoice> = forLanguage(
        languageId = languageId,
        whistleReady = WhistleEngine.shipped && WhistleModelStore.isDownloaded(filesDir),
        whisperModels = if (whisperEnabled) WhisperStore.downloadedModels(filesDir) else emptyList(),
        etModels = EtEngine.entries.filter { it.runtimeShipped }.flatMap { EtStore.downloaded(filesDir, it) },
        serverConfigured = serverConfigured,
    )

    /** [choices] over an already-known set of downloads. */
    fun forLanguage(
        languageId: String,
        whistleReady: Boolean,
        whisperModels: List<WhisperModel>,
        etModels: List<EtModel>,
        serverConfigured: Boolean,
    ): List<VoiceChoice> = buildList {
        add(VoiceChoice(SYSTEM))
        if (whistleReady && WhistleLanguages.supports(languageId)) add(VoiceChoice(WHISTLE))
        val code = WhisperLanguages.codeForLanguage(languageId)
        if (code != null) {
            whisperModels.filter { it.covers(code) }
                .forEach { add(VoiceChoice(WHISPER, it.id, it.displayName)) }
        }
        for (engine in EtEngine.entries) {
            if (!engine.supports(languageId)) continue
            etModels.filter { it.engine == engine && it.supports(languageId) }
                .sortedWith(EtModel.ORDER.reversed())
                .forEach { add(VoiceChoice(engine.id, it.id, it.label)) }
        }
        if (serverConfigured) add(VoiceChoice(SERVER))
    }
}
