package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Privacy › Google libraries: how much of Google's code the keyboard runs, in
 * the builds that carry it. F-Droid's build carries none, so it has no such
 * setting and [inBuild] is false there.
 *
 * Two levels:
 * - [Level.NO_ANALYTICS], the default. Google's libraries run, with what they
 *   would tell Google about their own use cut out of them when the app is
 *   built (`MlKitTelemetry` in :core:intelligence): ML Kit's usage reports,
 *   and the translator's Firebase installation ID and Remote Config fetch.
 * - [Level.NO_LIBRARIES]. None of Google's libraries runs (ML Kit, LiteRT,
 *   LiteRT-LM, Gemini Nano, the Google Home APIs, Google's WebRTC build, Play
 *   services' document scanner, background remover, sign-in and fonts).
 *   Every feature built on one behaves as it does in F-Droid's build: the same
 *   free software stands in (PaddleOCR on MNN, Tesseract, zxing-cpp, the
 *   built-in document scanner, Bergamot, zinnia, whisper.cpp, llama.cpp), and
 *   what has no stand-in there is not offered. Play services counts as
 *   missing ([com.wasimaster.wmkeyboard.core.util.PlayServices]), and the
 *   gates in [FeatureModules.googleLibraries] are put behind [NotInBuild], as
 *   F-Droid's build does with its own.
 *
 * On the Play build the Play Store's own libraries stay at both levels: they
 * deliver the app and its on-demand modules, its updates and its tips.
 * Open-source code from Google that F-Droid's build carries too (AndroidX,
 * Jetpack Compose, Mozc) is not counted.
 *
 * Read once per process, in `Application.onCreate` ([attach]), before any
 * feature asks, so every answer that depends on it, from the gates to the
 * provider lists, stays the same for the life of the process. A change is
 * stored ([store]) and the app restarts: nothing short of that is sure to
 * unload what the old level loaded.
 *
 * Kept, like [NativeConsent], in its own small file on device-protected
 * storage, because the keyboard reads it before the first unlock.
 */
object GoogleLibraries {

    enum class Level(val key: String) {
        NO_ANALYTICS("no_analytics"),
        NO_LIBRARIES("no_libraries"),
        ;

        companion object {
            /** The level stored as [key]; the default for a missing or unknown one. */
            fun of(key: String?): Level = entries.firstOrNull { it.key == key } ?: NO_ANALYTICS
        }
    }

    /** Whether this build carries Google's libraries at all: every one but F-Droid's. */
    val inBuild: Boolean get() = BuildConfig.GOOGLE_SDKS

    private val current = MutableStateFlow(Level.NO_ANALYTICS)

    /** The level this process runs at. */
    val level: StateFlow<Level> = current.asStateFlow()

    /** Whether a feature may run one of Google's libraries now. */
    val usable: Boolean get() = inBuild && current.value != Level.NO_LIBRARIES

    /** Whether the user switched Google's libraries off, in a build that has them. */
    val switchedOff: Boolean get() = inBuild && current.value == Level.NO_LIBRARIES

    private const val PREFS = "google_libraries"
    private const val KEY_LEVEL = "level"

    /** Reads the stored level. First thing in `Application.onCreate`. */
    fun attach(context: Context) {
        if (!inBuild) return
        current.value = runCatching { Level.of(prefs(context).getString(KEY_LEVEL, null)) }
            .getOrDefault(Level.NO_ANALYTICS)
    }

    /**
     * Puts the gates of Google's libraries behind [NotInBuild] when they are
     * switched off. Right after the build's own gates are installed, which
     * would otherwise put a download or a Play module behind them.
     */
    fun applyToGates() {
        if (!switchedOff) return
        for (name in FeatureModules.googleLibraries) FeatureModules.set(name, NotInBuild)
    }

    /** Whether [gate] (a pins.tsv gate name) is one of Google's libraries that are switched off now. */
    fun blocks(gate: String): Boolean = switchedOff && gate in FeatureModules.googleLibraries

    /**
     * Stores [level] for the next process, which the caller then starts.
     * Written at once rather than in the background: the restart that follows
     * would lose a write still in flight.
     */
    fun store(context: Context, level: Level): Boolean =
        runCatching { prefs(context).edit().putString(KEY_LEVEL, level.key).commit() }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
