package com.wasimaster.wmkeyboard.core.transliteration

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of vidyut-lipi (libwmlipi.so, built from native/lipi-jni), the
 * transliterator behind [Lipi]. Optional at runtime: GitHub and F-Droid
 * builds fetch it the first time it is asked for, Play builds carry it in the
 * on-demand `:feature:lipi` split, and until then [available] is false.
 */
internal object LipiNative {
    const val LIBRARY = "wmlipi"

    private val library = GatedLibrary({ FeatureModules.lipi }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a Play split not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /**
     * [text] from the scheme named [from] to the one named [to], both
     * vidyut's `Scheme` variant names ([LipiScheme.native]). Null for an
     * unknown name.
     */
    external fun nativeTransliterate(text: String, from: String, to: String): String?
}
