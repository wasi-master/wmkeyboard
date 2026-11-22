package com.wasimaster.wmkeyboard.core.input.chewing

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * libwmchewing.so: libchewing behind a small JNI bridge (native/chewing-jni).
 * On Play it lives in `:feature:chewing`, on the GitHub build and F-Droid it
 * is delivered on demand, so nothing here may be called before [library]
 * says it is loaded. LGPL-2.1-or-later, and its own library for that reason:
 * it can be replaced on its own. Its dictionary is not in the library:
 * [nativeStart] opens the pack the user downloaded ([ChewingData]).
 *
 * Readings go in as the bopomofo typed, tone marks included; lengths come
 * back in characters of that reading, which are UTF-16 units.
 */
internal object ChewingNative {

    val library = GatedLibrary({ FeatureModules.chewing }) {
        runCatching { FeatureModules.loadLibrary("wmchewing") }.isSuccess
    }

    /** Opens libchewing on [dictFile] with the learned phrases in [userFile]; null, or why it did not. */
    @JvmStatic external fun nativeStart(dictFile: String, userFile: String): String?

    /** Writes out what was learned and closes libchewing. */
    @JvmStatic external fun nativeStop()

    /** The libchewing-data release the open dictionary was compiled from. */
    @JvmStatic external fun nativeDictVersion(): String

    /**
     * [reading] converted: the candidate texts, then the composing text.
     * [info] gets `[count, tail, consumed₀, sentence₀, consumed₁, …]` and
     * must hold `2 + 2 * limit` ints. Null while libchewing is closed.
     */
    @JvmStatic external fun nativeConvert(reading: String, limit: Int, info: IntArray): Array<String>?

    /** Learns that the first [consumed] characters of [reading] mean [phrase]. */
    @JvmStatic external fun nativeLearnPhrase(reading: String, consumed: Int, phrase: String): Boolean

    /** Learns the phrases of conversion [sentence] of the whole of [reading]. */
    @JvmStatic external fun nativeLearnSentence(reading: String, sentence: Int): Boolean

    @JvmStatic external fun nativeSync(): Boolean

    @JvmStatic external fun nativeClearLearned(): Boolean
}
