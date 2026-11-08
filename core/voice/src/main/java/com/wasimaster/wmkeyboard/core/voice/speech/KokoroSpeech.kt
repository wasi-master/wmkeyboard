package com.wasimaster.wmkeyboard.core.voice.speech

import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Text to samples with Kokoro ([KokoroVoices]) through sherpa-onnx's offline
 * TTS in libwmsherpa (`kokoro_jni.cpp`). The model stays open between
 * requests (opening its 114 MB takes about a second) until [release]; a
 * change of language reopens it with that language's lexicon first.
 *
 * Runs on [MmsSpeech.dispatcher], the speech-out thread, so a voice of either
 * kind is only ever used from one thread.
 */
object KokoroSpeech {

    private val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private var handle = 0L
    private var openLanguage: KokoroVoices.Language? = null

    /** Kokoro's rate: 24 kHz for every speaker. */
    var sampleRate: Int = 24_000
        private set

    /**
     * [text] (one piece from [MmsText.pieces]) in [locale] read by the speaker
     * picked for its language at [rate] (1 = the voice's pace), or null when
     * the model will not open or has nothing to say. On [MmsSpeech.dispatcher] only.
     */
    fun synthesize(filesDir: File, text: String, locale: Locale, rate: Float): FloatArray? {
        val language = KokoroVoices.languageFor(locale) ?: return null
        if (!open(filesDir, language)) return null
        val said = if (language == KokoroVoices.Language.ZH) text else KokoroText.normalize(text)
        if (said.isBlank()) return null
        val speaker = KokoroVoices.chosen(filesDir, language)
        return runCatching {
            SherpaNative.nativeKokoroRun(handle, said.toByteArray(Charsets.UTF_8), speaker.id, rate.coerceIn(0.5f, 2f))
        }.getOrNull()
    }

    private fun open(filesDir: File, language: KokoroVoices.Language): Boolean {
        if (handle != 0L && openLanguage == language) return true
        release()
        if (!KokoroVoices.isDownloaded(filesDir) || !SherpaNative.available) return false
        // An older libwmsherpa without the Kokoro entry points throws here
        // rather than at load time; that is "no voice", not a crash.
        val h = try {
            SherpaNative.nativeKokoro(
                KokoroVoices.file(filesDir, KokoroVoices.MODEL).path,
                KokoroVoices.file(filesDir, KokoroVoices.VOICES).path,
                KokoroVoices.file(filesDir, KokoroVoices.TOKENS).path,
                KokoroVoices.lexicons(filesDir, language),
                KokoroVoices.ruleFsts(filesDir),
                language.lang,
                threads,
            )
        } catch (_: UnsatisfiedLinkError) {
            0L
        }
        if (h == 0L) return false
        handle = h
        openLanguage = language
        sampleRate = SherpaNative.nativeKokoroSampleRate(h).takeIf { it > 0 } ?: 24_000
        return true
    }

    /** The language the model is open for, or null; for the Resources screen. */
    fun loaded(): String? = openLanguage?.takeIf { handle != 0L }?.lang

    /** Closes the model. On [MmsSpeech.dispatcher] only. */
    fun release() {
        if (handle != 0L) SherpaNative.nativeKokoroFree(handle)
        handle = 0L
        openLanguage = null
    }

    /** Closes the model before its files go. Any thread. */
    fun forget() {
        if (handle == 0L) return
        runBlocking(MmsSpeech.dispatcher) { release() }
    }
}
