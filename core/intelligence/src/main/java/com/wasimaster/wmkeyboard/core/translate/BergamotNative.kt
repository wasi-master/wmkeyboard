package com.wasimaster.wmkeyboard.core.translate

import android.os.Build
import android.os.Process
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of Bergamot (libwmbergamot.so, built from native/bergamot-jni):
 * Mozilla's bergamot-translator, the engine inside Firefox Translations, on
 * Marian and Google's ruy. Optional at runtime: GitHub builds fetch it the
 * first time it is asked for, F-Droid builds it from source, Play carries it
 * in the on-demand `:feature:bergamot` split.
 *
 * Text crosses as UTF-8 byte arrays (see bergamot_jni.cpp for why), and no
 * handle may be used from two threads at once: [BergamotTranslator] holds
 * one lock around every call. Every call but [nativeUnzstd] throws a
 * RuntimeException carrying Marian's message when the engine gives up.
 */
object BergamotNative {
    const val LIBRARY = "wmbergamot"

    /**
     * Android 9. Marian's path library calls iconv and glob, which bionic has
     * only from API 28, so the library is built for it and is never loaded on
     * an older phone, where the linker would refuse it anyway.
     */
    const val MIN_SDK = 28

    /**
     * Android 9 or later, in a 64-bit process: Marian's ARM path needs AArch64
     * (sse2neon), so armeabi-v7a carries a stub that refuses to load (see
     * native/bergamot-jni/stub.c).
     */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK && Process.is64Bit()

    private val library = GatedLibrary({ FeatureModules.bergamot }) {
        supported && runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build and phone have the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = supported && library.shipped

    /** A translation service; translates on the calling thread. [cacheSize] sentences are remembered. */
    external fun nativeNewService(cacheSize: Int): Long

    external fun nativeFreeService(handle: Long)

    /**
     * One direction's model from its unpacked files. [vocabs] holds one path
     * (a shared vocabulary) or two (source, then target). [int8shiftAll] for
     * an `intgemm8.bin` model; the catalogue's `intgemm.alphas.bin` ones take false.
     */
    external fun nativeLoadModel(model: String, lex: String?, vocabs: Array<String>, int8shiftAll: Boolean): Long

    external fun nativeFreeModel(handle: Long)

    /** Each of [texts] (UTF-8) translated with [model], in order. */
    external fun nativeTranslate(service: Long, model: Long, texts: Array<ByteArray>): Array<ByteArray>

    /** Each of [texts] through [first] then [second] in one pass, in order. */
    external fun nativePivot(service: Long, first: Long, second: Long, texts: Array<ByteArray>): Array<ByteArray>

    /** Unpacks a zstd file into [target], streaming. Null on success, else what went wrong. */
    external fun nativeUnzstd(source: String, target: String): String?
}
