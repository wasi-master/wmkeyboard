package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of `libwmdivvungrammar.so` (native/divvungrammar-jni):
 * divvun-runtime, which runs GiellaLT's grammar checker bundles (`.drb`). Its
 * own on-demand gate, `divvungrammar`, fetched the first time a bundle is
 * downloaded or checked with. The library is GPL-3.0 (divvun-runtime's VISL
 * CG-3 port), unlike the rest of the app.
 *
 * Strings come back tagged: `ok:` before an answer, `err:` before the reason
 * a call failed. Calls on one handle are serialised natively.
 */
internal object DivvunGrammarNative {
    const val LIBRARY = "wmdivvungrammar"

    private val library = GatedLibrary({ FeatureModules.divvunGrammar }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    val available: Boolean get() = library.available

    /** A handle on the bundle at [path], or 0 when it does not open. Blocking: loads its pipeline. */
    @JvmStatic external fun nativeOpen(path: String): Long

    /** Why the bundle at [path] does not open, or null when it does. */
    @JvmStatic external fun nativeOpenError(path: String): String?

    @JvmStatic external fun nativeClose(handle: Long)

    /**
     * The grammar checker's JSON for [text], tagged: `{"errors": [{"form",
     * "start", "end", "error_id", "title", "description", "suggestions"}]}`
     * with UTF-16 offsets, its messages in the first of [locales] (comma
     * separated) the bundle has.
     */
    @JvmStatic external fun nativeCheck(handle: Long, text: String, locales: String): String?

    /** The locales the bundle's messages come in, a tagged JSON array. */
    @JvmStatic external fun nativeMessageLocales(handle: Long): String?
}
