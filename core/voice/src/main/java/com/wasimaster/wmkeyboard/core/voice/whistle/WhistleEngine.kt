package com.wasimaster.wmkeyboard.core.voice.whistle

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Process-global Cactus Whistle JNI facade. */
object WhistleEngine {
    private val lock = Any()
    private val libraryLoaded = runCatching { System.loadLibrary("cactus_whistle") }.isSuccess
    /** True when the JNI bridge is loadable on this ABI. */
    val ready: Boolean = libraryLoaded && runCatching { nativeAvailable() }.getOrDefault(false)
    /** Alias for ready, useful to callers probing whether offline inference can be used. */
    val available: Boolean get() = ready

    /** Maps a project language ID to Whistle's supported two-letter language code. */
    fun languageCode(languageId: String): String? = WhistleLanguages.normalize(languageId)

    fun supportsLanguage(languageId: String): Boolean = languageCode(languageId) != null

    private var loadedModel: File? = null

    fun warm(modelFile: File) = synchronized(lock) {
        check(ready) { "Cactus Whistle is unavailable on this ABI" }
        require(modelFile.isFile && modelFile.canRead()) { "Model file is not readable" }
        val canonical = modelFile.canonicalFile
        if (loadedModel != canonical) {
            require(WhistleModelStore.hasValidChecksum(canonical)) { "Whistle model failed its SHA-256 check" }
            nativeLoad(canonical.readBytes())?.let { throw IllegalStateException(it) }
            loadedModel = canonical
        }
    }

    /** Input is 16 kHz mono float PCM in [-1, 1], limited to 30 seconds. */
    fun transcribe(
        modelFile: File,
        pcm: FloatArray,
        language: String? = null,
        keywords: List<String> = emptyList(),
    ): String = synchronized(lock) {
        check(ready) { "Cactus Whistle is unavailable on this ABI" }
        require(pcm.isNotEmpty() && pcm.size <= WhistleLanguages.maxSamples && pcm.all { it.isFinite() && it in -1f..1f }) {
            "PCM must contain 1..480000 finite samples in [-1, 1]"
        }
        val mappedLanguage = if (language == null) null else WhistleLanguages.normalize(language)
            ?: throw IllegalArgumentException("Unsupported language: $language")
        warm(modelFile)
        val bias = keywords.map(String::trim).filter(String::isNotEmpty).joinToString("\n").ifEmpty { null }
        val response = nativeTranscribe(pcm, mappedLanguage, bias)
            ?: throw IllegalStateException("Native transcription failed")
        runCatching {
            Json.parseToJsonElement(response).jsonObject["text"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Whistle returned no transcript")
        }.getOrElse { error ->
            if (error is IllegalStateException && error.message == "Whistle returned no transcript") throw error
            throw IllegalStateException(response, error)
        }
    }

    /** The C API has no model-unload call; do not block IME teardown on inference. */
    fun release() = Unit

    private external fun nativeAvailable(): Boolean
    private external fun nativeLoad(model: ByteArray): String?
    private external fun nativeTranscribe(pcm: FloatArray, language: String?, keywords: String?): String?
    private external fun nativeReset()
}

object WhistleLanguages {
    val supported: Set<String> = setOf("en", "de", "fr", "es", "it", "nl", "pl")
    const val maxSamples: Int = 16_000 * 30

    fun normalize(language: String): String? = language.trim().lowercase().substringBefore('-')
        .takeIf { it in supported }
}
