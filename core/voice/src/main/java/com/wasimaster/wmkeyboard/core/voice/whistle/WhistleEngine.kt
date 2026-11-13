package com.wasimaster.wmkeyboard.core.voice.whistle

import android.os.Build
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cactus Whistle, the third dictation engine (#548): one small model for seven
 * languages, run by Cactus's Needle engine through `native/whistle-jni`.
 *
 * The library ships in `src/whistle/jniLibs` for arm64 only, so other ABIs
 * simply fail to load it and [available] is false; there is no stub to keep
 * in step. Play builds carry it in the on-demand `:feature:speech` split with
 * whisper.cpp and ExecuTorch, so [shipped] says whether the build has it and
 * [available] whether it is loaded.
 *
 * F-Droid's build deletes it, since Cactus publishes Needle only as a binary
 * and F-Droid builds everything from source. There it has a gate of its own,
 * [FDROID_GATE], pinned from `fdroidPin` in native/gates.json: the user opts
 * in to downloads and fetches it, or imports the file, the way the other
 * feature libraries arrive, and offline Whisper alone never pulls it in. The engine is process-global and holds one
 * model, loaded on first use and kept: the C API has no unload.
 */
object WhistleEngine {

    private val lock = Any()

    /** The gate F-Droid's build delivers Whistle's library through. */
    const val FDROID_GATE = "whistle"

    /** The gate Whistle's library arrives through: its own on F-Droid, `:feature:speech` elsewhere. */
    val gate: ModuleGate get() = NativeDelivery.gate(FDROID_GATE) ?: FeatureModules.speech

    /** [gate]'s id in native/gates.json and pins.tsv. */
    val gateId: String get() = if (NativeDelivery.gate(FDROID_GATE) != null) FDROID_GATE else "speech"

    private val library = GatedLibrary({ gate }) {
        runCatching { FeatureModules.loadLibrary("wmwhistle") }.isSuccess
    }

    /** Whether the native engine is loaded on this device and build. */
    val available: Boolean get() = library.available

    /**
     * Whether this build and device have the engine, counting a Play split not
     * fetched yet. The split carries it for arm64 alone, so before it is here
     * the process's own ABI is the answer.
     */
    val shipped: Boolean
        get() = library.shipped && (gate.installed || Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")

    private var loadedModel: File? = null

    /**
     * Loads [modelFile] if it is not the one already loaded. Called while the
     * phrase is still being said, so the first clip does not wait for it.
     */
    fun warm(modelFile: File) = synchronized(lock) {
        check(available) { "Whistle is not available on this device" }
        val canonical = modelFile.canonicalFile
        if (loadedModel == canonical) return@synchronized
        require(WhistleModelStore.hasValidChecksum(canonical)) { "The Whistle model is damaged. Download it again." }
        nativeLoad(canonical.readBytes())?.let { throw IllegalStateException(it) }
        loadedModel = canonical
    }

    /**
     * [pcm] (16 kHz mono, in [-1, 1], at most 30 s) as text. [language] is a
     * keyboard language id; null, or one Whistle does not know, lets the model
     * detect it among its seven. [keywords] bias recognition towards the user's
     * own words. Blocking; call off the main thread.
     */
    fun transcribe(
        modelFile: File,
        pcm: FloatArray,
        language: String? = null,
        keywords: List<String> = emptyList(),
    ): String = synchronized(lock) {
        warm(modelFile)
        val clip = if (pcm.size > WhistleLanguages.MAX_SAMPLES) pcm.copyOf(WhistleLanguages.MAX_SAMPLES) else pcm
        val bias = keywords.map(String::trim).filter(String::isNotEmpty).joinToString("\n").ifEmpty { null }
        val response = nativeTranscribe(clip, language?.let(WhistleLanguages::codeFor), bias)
        val json = Json.parseToJsonElement(response).jsonObject
        json["error"]?.let { throw IllegalStateException(it.jsonPrimitive.content) }
        json["text"]?.jsonPrimitive?.content.orEmpty()
    }

    @JvmStatic private external fun nativeLoad(model: ByteArray): String?

    @JvmStatic private external fun nativeTranscribe(pcm: FloatArray, language: String?, keywords: String?): String
}

/** The languages Whistle transcribes, and the keyboard language ids that map to them. */
object WhistleLanguages {
    val codes: Set<String> = setOf("en", "de", "fr", "es", "it", "nl", "pl")

    /** 30 seconds at 16 kHz: the longest clip the engine takes. */
    const val MAX_SAMPLES: Int = 16_000 * 30

    /** Whistle's code for a keyboard language id such as `de` or `en_GB`, or null when it has none. */
    fun codeFor(languageId: String): String? =
        languageId.trim().lowercase().substringBefore('-').substringBefore('_').takeIf { it in codes }

    fun supports(languageId: String): Boolean = codeFor(languageId) != null
}
