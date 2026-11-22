package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks

/**
 * Which build this is, as far as providers go. GitHub is the direct download:
 * neither the Play nor the F-Droid flag. Whether the build may reach the
 * network at all is a separate question, [ProviderEnv.offline], because the
 * no-internet APK is a GitHub build with the permission stripped.
 */
enum class ProviderChannel {
    PLAY, GITHUB, FDROID;

    companion object {
        val current: ProviderChannel
            get() = when {
                BuildConfig.ENABLE_FDROID -> FDROID
                BuildConfig.ENABLE_PLAY_STORE -> PLAY
                else -> GITHUB
            }
    }
}

/** What decides which providers can be listed and asked right now. */
data class ProviderEnv(
    val channel: ProviderChannel,
    /** No internet permission: only providers on the phone can ever answer. */
    val offline: Boolean,
    /** ML Kit translation is compiled into this build (every one but F-Droid's). */
    val onDeviceTranslate: Boolean,
    /** Bergamot can translate here: Android 9 or later, its library in reach, and a network to fetch models. */
    val bergamot: Boolean = false,
    /** NLLB can translate here: a 64-bit phone with the memory for its model, and a network to fetch it. */
    val nllb: Boolean = false,
    /** At least one offline GIF pack is imported. */
    val offlineGifPacks: Boolean,
    /** The Google Home APIs can run: the build carries them and Play services is on the phone. */
    val googleHome: Boolean = false,
    /** The build carries the on-device AI runtime (every one but F-Droid's). */
    val onDeviceAi: Boolean = false,
    /** ML Kit's text recognizer is compiled into this build. */
    val mlKitOcr: Boolean = false,
    /** Tesseract can read here: its library is in this build. */
    val tesseract: Boolean = false,
    /**
     * The dictation engines this build can run, by engine id
     * ([ProviderTypes.VOICE_ENGINES]). Every one unless a caller looked:
     * finding out loads each engine's native library, so only the Voice
     * typing page does (`availableVoiceEngines` in :core:settings), and
     * dictation falls back on its own from an engine that cannot run.
     */
    val voiceEngines: Set<String> = ProviderTypes.VOICE_ENGINES.values.toSet(),
)

/**
 * What lower modules cannot see for themselves. ML Kit lives in
 * `:core:intelligence`, above this module, so the application installs the
 * answer at start-up, the way `BackupClients.install` hands down its client ids.
 */
object ProviderEnvironment {

    @Volatile
    var onDeviceTranslate: Boolean = false
        private set

    @Volatile
    var googleHome: Boolean = false
        private set

    @Volatile
    var bergamot: Boolean = false
        private set

    @Volatile
    var nllb: Boolean = false
        private set

    @Volatile
    var mlKitOcr: Boolean = false
        private set

    @Volatile
    var tesseract: Boolean = false
        private set

    fun install(
        onDeviceTranslate: Boolean,
        googleHome: Boolean = false,
        bergamot: Boolean = false,
        nllb: Boolean = false,
        mlKitOcr: Boolean = false,
        tesseract: Boolean = false,
    ) {
        this.onDeviceTranslate = onDeviceTranslate
        this.googleHome = googleHome
        this.bergamot = bergamot
        this.nllb = nllb
        this.mlKitOcr = mlKitOcr
        this.tesseract = tesseract
    }

    /**
     * [lookAtDisk] false leaves out what takes a look at the files (the
     * offline GIF packs), for a caller that only needs the AI and voice
     * lists: the settings are read through here on every change.
     */
    fun current(lookAtDisk: Boolean = true): ProviderEnv = ProviderEnv(
        channel = ProviderChannel.current,
        offline = !InternetPermission.granted,
        onDeviceTranslate = onDeviceTranslate,
        bergamot = bergamot,
        nllb = nllb,
        offlineGifPacks = lookAtDisk && OfflineGifPacks.hasPacks,
        // The build carries the Home APIs, and the phone has the Play services they run through.
        googleHome = googleHome && PlayServices.available,
        onDeviceAi = BuildConfig.ENABLE_LOCAL_LLM,
        mlKitOcr = mlKitOcr,
        tesseract = tesseract,
    )
}

/**
 * Which provider types a build can add. Every build lists every kind it can
 * run, in the same order ([ProviderTypes.all]), with nothing put first: the
 * no-internet build simply cannot run what needs the network.
 */
object ProviderCatalog {

    /** Whether this build can run [type] at all. */
    fun runnable(type: ProviderType, env: ProviderEnv): Boolean = when {
        env.offline && !type.onDevice -> false
        type === ProviderTypes.ML_KIT && !env.onDeviceTranslate -> false
        type === ProviderTypes.BERGAMOT && !env.bergamot -> false
        type === ProviderTypes.NLLB && !env.nllb -> false
        type === ProviderTypes.GOOGLE_HOME && !env.googleHome -> false
        type === ProviderTypes.ON_DEVICE_AI && !env.onDeviceAi -> false
        type === ProviderTypes.OCR_ML_KIT && !env.mlKitOcr -> false
        type === ProviderTypes.OCR_TESSERACT && !env.tesseract -> false
        // Whether one row can run is the plugin's own state, read per row.
        type === ProviderTypes.PLUGIN -> true
        ProviderTool.VOICE in type.serves -> ProviderTypes.VOICE_ENGINES[type.id] in env.voiceEngines
        else -> true
    }

    /**
     * The types the add sheet offers for [tool]: every runnable one, minus a
     * type already in [chain] that [tool] cannot list twice
     * ([ProviderTool.allowsAnother]).
     */
    fun addable(tool: ProviderTool, chain: ProviderChain, env: ProviderEnv): List<ProviderType> =
        ProviderTypes.serving(tool)
            // Plugin rows are offered one by one, by name: [addablePlugins].
            .filter { it !== ProviderTypes.PLUGIN }
            .filter { runnable(it, env) }
            .filter { tool.allowsAnother(it) || chain.entries.none { entry -> entry.typeId == it.id } }

    /**
     * Whether the offline build leaves [type] out of every list: a service
     * reached over the network. Rows of the phone's own engines stay even
     * when their library or model is missing, since an import brings those.
     * Plugin rows stay too: whether one connects is the plugin's business.
     */
    fun hiddenOffline(type: ProviderType?): Boolean =
        type != null && !type.onDevice && type !== ProviderTypes.PLUGIN

    /**
     * Whether [tool]'s list has anything at all for the offline build to
     * show: a kind that runs on the phone. A list of online services only
     * (photos, synonyms, exchange rates) is left out with its page.
     */
    fun anyOffline(tool: ProviderTool): Boolean = ProviderTypes.serving(tool).any { !hiddenOffline(it) && it !== ProviderTypes.PLUGIN }

    /** The plugin providers for [tool] that [chain] does not list yet, the ones whose plugin is off too. */
    fun addablePlugins(tool: ProviderTool, chain: ProviderChain, env: ProviderEnv): List<PluginProviders.Row> =
        if (env.offline) emptyList() else PluginProviders.rows(tool).filter { !chain.has(it.key) }
}
