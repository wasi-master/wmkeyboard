package com.wasimaster.wmkeyboard.ime.resources

import android.content.Context
import com.wasimaster.wmkeyboard.core.grammar.GrammarChecker
import com.wasimaster.wmkeyboard.core.input.composer.ArabicTashkeel
import com.wasimaster.wmkeyboard.core.input.composer.HebrewNikud
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcr
import com.wasimaster.wmkeyboard.core.ocr.TesseractOcr
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.voice.StrongDenoise
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation
import com.wasimaster.wmkeyboard.core.voice.sherpa.OmnilingualEngine
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaEngine
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.core.voice.speech.MmsSpeech
import com.wasimaster.wmkeyboard.core.voice.vad.SmartTurn
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperEngine
import kotlinx.coroutines.withContext
import java.io.File

/** What a loaded model is for; the Resources screen names and links each. */
enum class ModelKind {
    LANGUAGE_MODEL,
    VOICE_TYPING,
    PUNCTUATION,
    NOISE_REDUCTION,
    END_OF_SPEECH,
    READ_ALOUD,
    TRANSLATION,
    TEXT_SCANNING,
    GRAMMAR,
    GLIDE,
    DIACRITICS,
}

/**
 * One model in this process's memory right now.
 *
 * [bytes] is what its owner can say about its size: the file or folder it was
 * loaded from, or the download it came in. It is an estimate of the memory it
 * holds, never a measurement: Android does not report memory per library, and
 * a model can hold more than its file while it works (a language model's
 * context) or less (a file mapped and only partly read).
 */
data class LoadedModel(
    val id: String,
    val kind: ModelKind,
    /** The model's own name where its owner knows it: a file, a folder, a language pair. */
    val name: String?,
    val bytes: Long?,
)

/**
 * Every model the keyboard can hold in memory, asked one by one whether it is
 * loaded, for the Resources screen (#1163); and how to let go of each.
 *
 * Nothing here loads anything or keeps anything alive. [loaded] only reads
 * flags the engines already keep, so asking costs a handful of field reads and
 * one file size per loaded model. Each unload is the owner's own `release`,
 * run where the owner says it must run, and each owner already skips the
 * release while it is in use (a dictation, a translation, a generation), so an
 * unload can be late but never pulls a model out from under its work.
 *
 * Models that run in their own process (the sticker generator, the
 * search-by-meaning index) are not here: this process cannot see into theirs.
 * The screen lists those processes separately, by their memory.
 */
object ResidentModels {

    /**
     * Set by the keyboard service while it runs: frees everything its idle
     * release frees, with its own checks for a dictation in progress and the
     * panel on screen. Null while no keyboard service is running.
     */
    @Volatile
    var keyboardRelease: (() -> Unit)? = null

    private class Part(
        val id: String,
        val kind: ModelKind,
        val probe: (Context) -> LoadedModel?,
        val release: suspend () -> Unit,
    )

    private fun model(id: String, kind: ModelKind, file: File?, bytes: Long? = null) =
        LoadedModel(id, kind, file?.name, bytes ?: file?.let(::sizeOf))

    private val parts: List<Part> = listOf(
        Part(
            "llm", ModelKind.LANGUAGE_MODEL,
            { LocalLlmEngine.loadedModel()?.let { model("llm", ModelKind.LANGUAGE_MODEL, it) } },
            { LocalLlmEngine.release() },
        ),
        Part(
            "whisper", ModelKind.VOICE_TYPING,
            { WhisperEngine.loadedModel()?.let { model("whisper", ModelKind.VOICE_TYPING, it) } },
            { WhisperEngine.release() },
        ),
        Part(
            "whisper_cpp", ModelKind.VOICE_TYPING,
            { WhisperCpp.loadedModel()?.let { model("whisper_cpp", ModelKind.VOICE_TYPING, it) } },
            { WhisperCpp.release() },
        ),
        Part(
            "sherpa", ModelKind.VOICE_TYPING,
            { SherpaEngine.loadedModel()?.let { model("sherpa", ModelKind.VOICE_TYPING, it) } },
            { withContext(SherpaNative.dispatcher) { SherpaEngine.release() } },
        ),
        Part(
            "omnilingual", ModelKind.VOICE_TYPING,
            { OmnilingualEngine.loadedModel()?.let { model("omnilingual", ModelKind.VOICE_TYPING, it) } },
            { withContext(SherpaNative.dispatcher) { OmnilingualEngine.release() } },
        ),
        Part(
            "executorch", ModelKind.VOICE_TYPING,
            { if (EtNative.isOpen) LoadedModel("executorch", ModelKind.VOICE_TYPING, null, null) else null },
            // The running dictation owns its programs, and only the keyboard
            // knows whether one is running; it checks before it lets go. With
            // no keyboard running there is no dictation either.
            { keyboardRelease?.invoke() ?: withContext(EtNative.dispatcher) { EtNative.release() } },
        ),
        Part(
            "punctuation", ModelKind.PUNCTUATION,
            {
                if (DictationPunctuation.isOpen) {
                    model("punctuation", ModelKind.PUNCTUATION, DictationPunctuation.loadedModel())
                } else {
                    null
                }
            },
            { withContext(SherpaNative.dispatcher) { DictationPunctuation.release() } },
        ),
        Part(
            "denoise", ModelKind.NOISE_REDUCTION,
            { if (StrongDenoise.isLoaded) LoadedModel("denoise", ModelKind.NOISE_REDUCTION, null, StrongDenoise.BYTES) else null },
            { StrongDenoise.release() },
        ),
        Part(
            "smart_turn", ModelKind.END_OF_SPEECH,
            { SmartTurn.loadedModel()?.let { model("smart_turn", ModelKind.END_OF_SPEECH, it) } },
            { SmartTurn.release() },
        ),
        Part(
            "mms_tts", ModelKind.READ_ALOUD,
            { MmsSpeech.loadedVoice()?.let { LoadedModel("mms_tts", ModelKind.READ_ALOUD, it, null) } },
            { withContext(MmsSpeech.dispatcher) { MmsSpeech.release() } },
        ),
        Part(
            "nllb", ModelKind.TRANSLATION,
            { if (NllbTranslator.isLoaded) LoadedModel("nllb", ModelKind.TRANSLATION, "NLLB", NllbTranslator.modelBytes) else null },
            { OfflineTranslation.release() },
        ),
        Part(
            "bergamot", ModelKind.TRANSLATION,
            {
                val pairs = BergamotTranslator.loadedPairs()
                if (pairs.isEmpty()) {
                    null
                } else {
                    LoadedModel(
                        "bergamot", ModelKind.TRANSLATION, pairs.joinToString(", "),
                        pairs.sumOf { runCatching { BergamotTranslator.downloadBytes(it) }.getOrDefault(0L) }
                            .takeIf { it > 0 },
                    )
                }
            },
            { OfflineTranslation.release() },
        ),
        Part(
            "mlkit_translate", ModelKind.TRANSLATION,
            { if (OnDeviceTranslator.isLoaded) LoadedModel("mlkit_translate", ModelKind.TRANSLATION, null, null) else null },
            { OfflineTranslation.release() },
        ),
        Part(
            "tesseract", ModelKind.TEXT_SCANNING,
            { context ->
                TesseractOcr.loadedPack()?.let { pack ->
                    val file = File(OcrPacks.dataDir(context.filesDir), "$pack.traineddata")
                    LoadedModel("tesseract", ModelKind.TEXT_SCANNING, pack, file.length().takeIf { it > 0 })
                }
            },
            { TesseractOcr.release() },
        ),
        Part(
            "paddle", ModelKind.TEXT_SCANNING,
            { if (PaddleOcr.isLoaded) LoadedModel("paddle", ModelKind.TEXT_SCANNING, "PaddleOCR", null) else null },
            { PaddleOcr.release() },
        ),
        Part(
            "grammar", ModelKind.GRAMMAR,
            { if (GrammarChecker.isLoaded) LoadedModel("grammar", ModelKind.GRAMMAR, "Harper", null) else null },
            { GrammarChecker.release() },
        ),
        Part(
            "futo_swipe", ModelKind.GLIDE,
            { if (FutoSwipe.isLoaded) LoadedModel("futo_swipe", ModelKind.GLIDE, null, FutoSwipe.bytes) else null },
            { FutoSwipe.release() },
        ),
        Part(
            "tashkeel", ModelKind.DIACRITICS,
            { if (ArabicTashkeel.isLoaded) LoadedModel("tashkeel", ModelKind.DIACRITICS, "Arabic", null) else null },
            { ArabicTashkeel.release() },
        ),
        Part(
            "nikud", ModelKind.DIACRITICS,
            { if (HebrewNikud.isLoaded) LoadedModel("nikud", ModelKind.DIACRITICS, "Hebrew", null) else null },
            { HebrewNikud.release() },
        ),
    )

    /**
     * The models loaded right now. Reads file sizes, so off the main thread.
     * A probe that throws (a library half-installed) counts as not loaded.
     */
    fun loaded(context: Context): List<LoadedModel> =
        parts.mapNotNull { part -> runCatching { part.probe(context) }.getOrNull() }

    /** Lets go of the model [id]. A no-op for an id that is not one, or a model in use. */
    suspend fun unload(id: String) {
        parts.firstOrNull { it.id == id }?.let { runCatching { it.release() } }
    }

    /**
     * Lets go of every model, through the keyboard while it runs so its checks
     * apply and its caches go too, and model by model otherwise.
     */
    suspend fun unloadAll() {
        val viaKeyboard = keyboardRelease
        if (viaKeyboard != null) {
            viaKeyboard()
            // The keyboard's own release leaves out the few it never loads
            // eagerly enough to bother with; they go here.
            for (id in listOf("paddle", "futo_swipe", "mms_tts")) unload(id)
        } else {
            for (part in parts) runCatching { part.release() }
        }
    }

    private fun sizeOf(file: File): Long? = when {
        file.isFile -> file.length()
        file.isDirectory -> file.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.sumOf { it.length() }
        else -> null
    }?.takeIf { it > 0 }
}
