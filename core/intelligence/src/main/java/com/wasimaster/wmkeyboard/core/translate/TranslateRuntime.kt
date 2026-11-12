package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the app and ML Kit's translator. `OnDeviceTranslator` (the
 * facade every caller uses) reaches its implementation, `MlKitTranslateRuntime`,
 * only by reflection — see [TranslateModule.BRIDGE_CLASS] — because the class
 * lives in a different place per channel, exactly as the LiteRT-LM runtime
 * does (see `LlmRuntime`):
 *
 * - Play builds: in the on-demand `:feature:translate` module, downloaded the
 *   first time somebody asks for on-device translation. ML Kit's translator is
 *   about 16 MB of native code per ABI, and most installs never turn it on.
 * - Every other build but F-Droid's: compiled straight into `:core:intelligence` (the
 *   `src/translatebridge` source directory), where Play's delivery does not
 *   exist to fetch it later.
 *
 * No type here may come from ML Kit's translate or language-id libraries: this
 * interface is what the base APK compiles against in Play builds, where those
 * libraries are absent. Everything that needs no ML Kit type stays on the
 * facade's side of the line: the shared model states, the download watchdog,
 * the source-language plan, the line splitting.
 */
interface TranslateRuntime {

    /** Model codes on the device, English not included. Throws when ML Kit does not answer. */
    suspend fun downloadedModels(): Set<String>

    /**
     * Starts fetching [code]'s model and hands back the fetch. ML Kit's own
     * Task underneath cannot be cancelled and reports no progress, so the
     * facade polls [PendingDownload.isComplete] while it watches the bytes
     * arrive some other way.
     */
    fun startDownload(code: String): PendingDownload

    /** Removes [code]'s model, and with it any fetch ML Kit had going for it. */
    suspend fun deleteModel(code: String)

    /** Every language the identifier considers possible for [text], however unlikely. */
    suspend fun identify(text: String): List<OfflineTranslatePlan.Candidate>

    /**
     * Translates each of [bodies] from [source] to [target] (model codes), in
     * order. Keeps one translator pair loaded between calls; the caller
     * serialises calls, so the implementation does not.
     */
    suspend fun translate(source: String, target: String, bodies: List<String>): List<String>

    /** The model codes of the loaded translator pair, or null with none loaded. */
    fun loadedPair(): Pair<String, String>?

    /**
     * Whether a model is open: the translator pair or the language identifier.
     * The runtime itself stays once ML Kit is set up, since even asking which
     * models are on the phone needs it, so its being there says nothing.
     */
    val holdsModels: Boolean get() = loadedPair() != null

    /** Closes the loaded translator pair. A model file cannot be deleted while it is open. */
    fun closeTranslator()

    /** Closes everything: the translator pair and the language identifier. */
    fun release()

    /** One model fetch in flight. */
    interface PendingDownload {
        val isComplete: Boolean

        /** Returns when the fetch succeeded; throws what it failed with. */
        suspend fun await()
    }
}

/** Where the on-demand module stands on this install. */
sealed interface TranslateModuleState {

    /** The runtime is here: always, outside Play; after the download, on Play. */
    data object Installed : TranslateModuleState

    /** Not here. [failed] when the last attempt to fetch it went wrong. */
    data class Missing(val failed: Boolean = false) : TranslateModuleState

    /** On its way from Play. Both figures are 0 before Play reports any. */
    data class Installing(val bytes: Long = 0, val totalBytes: Long = 0) : TranslateModuleState
}

/**
 * Whether the runtime's home is present on this install, and how to fetch it
 * when it is not. The default says "always present", which is correct for
 * every channel except Play: sideloaded builds embed the bridge, and F-Droid's
 * never reaches this code. Play builds swap in a SplitInstall-backed gate
 * at startup — see `installTranslateDelivery` in `:app`.
 *
 * A flow rather than the LLM gate's bare boolean, because this module is asked
 * for from a panel that stays open while it arrives: the panel shows the
 * download and translates the moment it lands, instead of saying "try again".
 */
interface TranslateModuleGate {
    val state: StateFlow<TranslateModuleState>

    /** Idempotent; safe to call while a download is already running. */
    fun requestInstall()
}

object TranslateModule {

    /** FQN `OnDeviceTranslator` instantiates reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.translate.bridge.MlKitTranslateRuntime"

    @Volatile
    var gate: TranslateModuleGate = object : TranslateModuleGate {
        override val state: StateFlow<TranslateModuleState> =
            MutableStateFlow(TranslateModuleState.Installed)

        override fun requestInstall() = Unit
    }

    /** Builds the runtime. Throws when the bridge class is not loadable yet. */
    internal fun load(context: Context): TranslateRuntime =
        Class.forName(BRIDGE_CLASS, true, context.classLoader)
            .getDeclaredConstructor()
            .newInstance() as TranslateRuntime
}
