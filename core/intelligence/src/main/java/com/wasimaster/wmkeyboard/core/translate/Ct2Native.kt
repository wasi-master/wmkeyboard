package com.wasimaster.wmkeyboard.core.translate

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of CTranslate2 (libwmct2.so, built from native/ctranslate2-jni)
 * running Meta's NLLB-200 model, the translate tool's third on-device engine.
 * Optional at runtime: GitHub builds fetch it the first time it is asked for,
 * F-Droid builds it from source, Play carries it in the on-demand
 * `:feature:ct2` split.
 *
 * Text crosses as UTF-8 byte arrays (see ct2_jni.cpp for why), and no handle
 * may be used from two threads at once: [NllbTranslator] holds one lock
 * around every call. Every call throws a RuntimeException carrying
 * CTranslate2's message when the engine gives up.
 */
object Ct2Native {
    const val LIBRARY = "wmct2"

    /**
     * The phone's memory below which the engine is not offered: a 6 GB phone
     * reports about 5.5 GB. The model is 620 MB held in memory while the
     * panel translates, and decoding adds ruy's packed copy of the largest
     * weights on top, about 1.25 GB in all (measured on the desktop build);
     * a phone with less would sooner close the app in front than keep it.
     */
    const val MIN_TOTAL_RAM = 5_500_000_000L

    /**
     * A 64-bit process: armeabi-v7a carries a stub that refuses to load (see
     * native/ctranslate2-jni/stub.c), since 600 MB of weights is more than a
     * 32-bit process can count on. False on a plain JVM, whose android.jar
     * throws "not mocked" (the settings search index asks this in unit tests).
     */
    val supported: Boolean get() = runCatching { Process.is64Bit() }.getOrDefault(false)

    /** Whether [context]'s phone has the memory for the model, by [MIN_TOTAL_RAM]. */
    fun enoughMemory(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem >= MIN_TOTAL_RAM
    }

    private val library = GatedLibrary({ FeatureModules.ct2 }) {
        supported && runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build and phone have the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = supported && library.shipped

    /**
     * The model in [modelDir] (CTranslate2's model.bin, config.json and
     * shared_vocabulary.txt, NLLB's sentencepiece.bpe.model), translating on
     * up to [threads] cores. Reads 620 MB into memory; seconds on a phone.
     */
    external fun nativeLoad(modelDir: String, threads: Int): Long

    external fun nativeFree(handle: Long)

    /**
     * Each of [texts] (UTF-8, one sentence each) from [source] into [target],
     * both FLORES-200 codes (`ben_Beng`), in order. [beam] 1 is greedy search.
     */
    external fun nativeTranslate(handle: Long, texts: Array<ByteArray>, source: String, target: String, beam: Int): Array<ByteArray>

    /**
     * For a T5 model such as MADLAD-400, which WM Translate runs on this library: [prefix]
     * (`<2de>`) before each text, decoding from the model's own start token over the whole
     * vocabulary. The keyboard does not call it.
     */
    external fun nativeTranslatePrefixed(handle: Long, texts: Array<ByteArray>, prefix: String, beam: Int): Array<ByteArray>
}
