package com.wasimaster.wmkeyboard.core.voice.speech

import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Text to samples with one MMS voice, on ONNX Runtime through libwmsherpa
 * (`vits_jni.cpp`). One voice stays open between requests, since opening the
 * 114 MB model takes most of a second, until another language's takes its
 * place or [release] frees it.
 *
 * Its own thread rather than [SherpaNative.dispatcher]: a sentence takes a
 * moment to synthesise, and dictation on the same thread would stall behind
 * it. The two never share a handle.
 */
object MmsSpeech {

    /**
     * Half the cores, two to four: the vocoder's convolutions split well, a
     * second of speech costs about a second of one fast core to make, and
     * the rest of the phone keeps the other half.
     */
    private val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    // VITS's sampling noise and duration noise, Meta's own defaults for MMS.
    private const val NOISE_SCALE = 0.667f
    private const val NOISE_SCALE_W = 0.8f

    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "mms-tts").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    private var handle = 0L
    private var openCode: String? = null
    private var tokens: MmsTokens? = null
    private var addBlank = true

    /** The open voice's sample rate; MMS voices are all 16 kHz. */
    var sampleRate: Int = 16_000
        private set

    /** Whether the runtime is on this device and build (loads it on first ask). */
    val runtimeAvailable: Boolean get() = SherpaNative.available

    /** Whether this build can run voices at all, counting a module not fetched yet. */
    val runtimeShipped: Boolean get() = SherpaNative.shipped

    /**
     * [text] (one piece from [MmsText.pieces]) read by [voice] at [rate]
     * (1 = the voice's own pace), or null when the voice will not open or has
     * nothing to say. On [dispatcher] only.
     */
    fun synthesize(filesDir: File, voice: MmsVoice, text: String, locale: Locale, rate: Float): FloatArray? {
        if (!open(filesDir, voice)) return null
        val ids = tokens?.encode(text, locale, addBlank) ?: return null
        if (ids.isEmpty()) return null
        val lengthScale = 1f / rate.coerceIn(0.5f, 2f)
        return runCatching {
            SherpaNative.nativeVitsRun(handle, ids, NOISE_SCALE, lengthScale, NOISE_SCALE_W)
        }.getOrNull()
    }

    private fun open(filesDir: File, voice: MmsVoice): Boolean {
        if (handle != 0L && openCode == voice.code) return true
        release()
        if (!MmsVoices.isDownloaded(filesDir, voice) || !runtimeAvailable) return false
        // An older libwmsherpa without the voice entry points throws here
        // rather than at load time; that is "no voice", not a crash.
        val h = try {
            SherpaNative.nativeVits(MmsVoices.modelFile(filesDir, voice).path, threads)
        } catch (_: UnsatisfiedLinkError) {
            0L
        }
        if (h == 0L) return false
        val parsed = runCatching { MmsTokens.parse(MmsVoices.tokensFile(filesDir, voice).readText()) }.getOrNull()
        if (parsed == null) {
            SherpaNative.nativeVitsFree(h)
            return false
        }
        handle = h
        openCode = voice.code
        tokens = parsed
        addBlank = meta(h, "add_blank") != "0"
        sampleRate = meta(h, "sample_rate").toIntOrNull()?.takeIf { it > 0 } ?: 16_000
        return true
    }

    private fun meta(h: Long, key: String): String = String(SherpaNative.nativeVitsMeta(h, key), Charsets.UTF_8)

    /** Closes the open voice. On [dispatcher] only. */
    /** The voice's language code loaded right now, or null; for the Resources screen (#1163). */
    fun loadedVoice(): String? = openCode?.takeIf { handle != 0L }

    fun release() {
        if (handle != 0L) SherpaNative.nativeVitsFree(handle)
        handle = 0L
        openCode = null
        tokens = null
    }

    /** Closes [voice] if it is the open one, before its files go. Any thread. */
    fun forget(voice: MmsVoice) {
        if (openCode != voice.code) return
        runBlocking(dispatcher) { if (openCode == voice.code) release() }
    }
}
