package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.providers.ProviderCatalog
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderChannel
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.providers.SameKind
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient

/**
 * The provider lists the user has edited, and the credentials they set up.
 *
 * Nothing is stored until the user changes something. Until then a tool's
 * list is worked out from the settings that came before it ([LegacyProviders]),
 * which is what keeps every install, new or upgraded, doing exactly what it
 * did: the same providers, in the same order, with the same keys. Each tool's
 * list is stored on its own the first time it is edited, so editing the GIF
 * list leaves the translate list following the old settings.
 *
 * [credentials] is null until the first credential is saved; from then on it
 * is the whole truth and the old key fields are no longer read.
 *
 * [legacyAi] and [legacyWhisper] are the AI and voice settings as they were
 * stored, before [withProviderChoices] wrote the chosen provider into them.
 * The old settings are read from these, so a list worked out from them does
 * not change because the provider in use did. Null outside the repository
 * (a test, a default), where the two are the same thing.
 */
data class ProviderSettings(
    val credentials: List<ProviderCredential>? = null,
    val chains: Map<ProviderTool, ProviderChain> = emptyMap(),
    val legacyAi: AiSettings? = null,
    val legacyWhisper: WhisperSettings? = null,
)

/** The AI settings as stored, before the provider in use was written into them. */
private val KeyboardSettings.storedAi: AiSettings get() = providers.legacyAi ?: ai

/** The voice settings as stored, before the engine in use was written into them. */
private val KeyboardSettings.storedWhisper: WhisperSettings get() = providers.legacyWhisper ?: whisper

/** Why a provider in a list cannot be asked. */
enum class ProviderProblem {
    /** A key is needed and there is neither the user's nor the build's. */
    NEEDS_KEY,

    /** A server address is needed. */
    NEEDS_SERVER,

    /** A model name is needed: a vision server for the OCR tool. */
    NEEDS_MODEL,

    /** Offline GIF packs with no pack imported. */
    NEEDS_PACKS,

    /** A home that is set up but not signed in, paired or granted yet. */
    NEEDS_SIGN_IN,

    /** This build cannot run it: no internet permission, no ML Kit, no Google Home APIs. */
    UNAVAILABLE,

    /** Written by a newer build; this one does not know the type. */
    UNKNOWN,
}

/**
 * One row of a tool's list with everything a caller needs to ask it: the key
 * to send (the user's, else the build's), the address, the model.
 */
data class ResolvedProvider(
    val entry: ProviderEntry,
    val type: ProviderType?,
    val credential: ProviderCredential?,
    val apiKey: String,
    val url: String,
    val model: String,
    val problem: ProviderProblem?,
    /**
     * The later rows of this kind, asked in order when this one fails, when
     * the list keeps two of a kind together ([SameKind.FALLBACK]). Only a
     * grouped resolution fills it ([resolveProviders] with `grouped`).
     */
    val backups: List<ResolvedProvider> = emptyList(),
) {
    val key: String get() = entry.key
    val typeId: String get() = entry.typeId
    val usable: Boolean get() = problem == null && type != null

    /** The key in use is the one this build carries, not one the user typed. */
    val usesBuiltInKey: Boolean get() = credential?.apiKey.isNullOrBlank() && type?.hasBuiltInKey == true && entry.primary

    /** This row, then its [backups]: the order a fetch walks. */
    val withBackups: List<ResolvedProvider> get() = if (backups.isEmpty()) listOf(this) else listOf(copy(backups = emptyList())) + backups

    /** What the list shows: the name the user gave the server, else the type's own. */
    val customName: String get() = credential?.name?.trim().orEmpty()
}

/** Every credential in force: the stored list, or the one the old settings make. */
fun KeyboardSettings.providerCredentials(): List<ProviderCredential> =
    providers.credentials ?: LegacyProviders.credentials(this)

/** The credential a single-instance type, or one row, reads. */
fun KeyboardSettings.providerCredential(id: String): ProviderCredential? =
    providerCredentials().firstOrNull { it.id == id }

/** [tool]'s list as it stands: the stored one, or the one the old settings make. */
fun KeyboardSettings.providerChain(
    tool: ProviderTool,
    env: ProviderEnv = ProviderEnvironment.current(),
): ProviderChain = providers.chains[tool]?.withNewTypes(tool) ?: LegacyProviders.chain(this, tool, env)

/**
 * [tool]'s list resolved: every row with its credential and, when it cannot
 * be asked, why. [all] keeps the rows that are off or cannot be asked, for
 * the settings list; without it only what a fetch should ask is left, in order.
 *
 * [pick] is the panel's choice, for a list in [ChainMode.PICK]: then only the
 * row with that key is left, or the first row when the choice is gone (or
 * was never made, ""), followed by the rows of its kind that back it up
 * ([SameKind.FALLBACK]). Null is a caller with no panel, the AI's tools,
 * which walk the whole list whatever the mode.
 *
 * [grouped] is for a caller that shows one entry per provider (the media
 * chips, the panel's chooser): with [SameKind.FALLBACK] and a mode other
 * than [ChainMode.FALLBACK], the rows of one kind become the first of them,
 * carrying the rest as its [ResolvedProvider.backups].
 */
fun KeyboardSettings.resolveProviders(
    tool: ProviderTool,
    env: ProviderEnv = ProviderEnvironment.current(),
    all: Boolean = false,
    pick: String? = null,
    grouped: Boolean = false,
): List<ResolvedProvider> {
    val credentials = providerCredentials()
    val chain = providerChain(tool, env)
    val resolved = chain.entries.map { ProviderResolution.resolve(it, credentials, env) }
    if (all) return resolved
    val asked = resolved.filter { it.entry.enabled && it.usable }
    if (pick != null && chain.mode == ChainMode.PICK) {
        val leads = groupSameKind(asked, chain)
        val chosen = leads.firstOrNull { it.key == pick } ?: leads.firstOrNull() ?: return emptyList()
        return chosen.withBackups
    }
    return if (grouped) groupSameKind(asked, chain) else asked
}

/** [rows] with each kind's later rows folded into its first, when [chain] says so; else as they are. */
private fun groupSameKind(rows: List<ResolvedProvider>, chain: ProviderChain): List<ResolvedProvider> {
    if (chain.mode == ChainMode.FALLBACK || chain.sameKindInForce != SameKind.FALLBACK) return rows
    val byKind = rows.groupBy { it.typeId }
    if (byKind.values.none { it.size > 1 }) return rows
    return byKind.values.map { kind -> kind.first().copy(backups = kind.drop(1)) }
}

/** The rows a panel in [ChainMode.PICK] offers, and which of them is chosen. */
data class ProviderChoices(val rows: List<ResolvedProvider>, val chosen: String) {
    /** Worth a chooser: the list is in pick mode and more than one row is on. */
    val shown: Boolean get() = rows.size > 1
}

/** [tool]'s chooser for a panel, or null when the list does not ask the panel to choose. */
fun KeyboardSettings.providerChoices(
    tool: ProviderTool,
    pick: String?,
    env: ProviderEnv = ProviderEnvironment.current(),
): ProviderChoices? {
    if (providerMode(tool, env) != ChainMode.PICK) return null
    val rows = resolveProviders(tool, env, grouped = true)
    if (rows.isEmpty()) return null
    val chosen = rows.firstOrNull { it.key == pick }?.key ?: rows.first().key
    return ProviderChoices(rows, chosen)
}

/**
 * DeepL as DeepL Write reads it: the switches from [DeepLSettings], the key
 * and the proxy from the shared DeepL credential the translate list sets up.
 */
val KeyboardSettings.deeplLive: DeepLSettings
    get() {
        val credential = providerCredential(ProviderTypes.DEEPL.id)
        return translate.deepl.copy(apiKey = credential?.apiKey.orEmpty(), endpoint = credential?.url.orEmpty())
    }

/**
 * The currency lists as `CurrencyClient.Provider` names, best first, the rows
 * that are off left out. Empty when the user switched every one off, which
 * the callers take as "fetch nothing" rather than the client's defaults.
 */
fun KeyboardSettings.fiatRateProviders(): List<String> =
    resolveProviders(ProviderTool.CURRENCY_FIAT).map { it.typeId.uppercase(java.util.Locale.ROOT) }

/** The same for coin prices. */
fun KeyboardSettings.cryptoRateProviders(): List<String> =
    resolveProviders(ProviderTool.CURRENCY_CRYPTO).map { it.typeId.uppercase(java.util.Locale.ROOT) }

/** How [tool] uses its list. */
fun KeyboardSettings.providerMode(tool: ProviderTool, env: ProviderEnv = ProviderEnvironment.current()): ChainMode =
    providerChain(tool, env).mode

object ProviderResolution {

    fun resolve(entry: ProviderEntry, credentials: List<ProviderCredential>, env: ProviderEnv): ResolvedProvider {
        val type = entry.type
        val credential = credentials.firstOrNull { it.id == entry.credentialKey }
        // The build's key stands in for the type's own credential only: a second one is there to bring its own.
        val apiKey = credential?.apiKey?.trim().orEmpty()
            .ifBlank { if (entry.primary) type?.builtInKey.orEmpty() else "" }
            // Brave Answers borrows the search key the user typed: one Brave
            // account can hold both plans. Never the build's, which pays for searches.
            .ifBlank {
                if (type === ProviderTypes.BRAVE_ANSWERS) {
                    credentials.firstOrNull { it.id == ProviderTypes.BRAVE.id }?.apiKey?.trim().orEmpty()
                } else {
                    ""
                }
            }
            // An image kind borrows the key typed for its AI service (OpenAI, Gemini, xAI).
            .ifBlank { type?.keyFrom?.let { from -> credentials.firstOrNull { it.id == from }?.apiKey?.trim() }.orEmpty() }
        val url = credential?.url?.trim().orEmpty()
        val model = credential?.model?.trim().orEmpty()
        val problem = when {
            type == null -> ProviderProblem.UNKNOWN
            !ProviderCatalog.runnable(type, env) -> ProviderProblem.UNAVAILABLE
            type === ProviderTypes.OFFLINE_GIFS && !env.offlineGifPacks -> ProviderProblem.NEEDS_PACKS
            // DeepL works with a key, a proxy that adds one, or both.
            type === ProviderTypes.DEEPL && apiKey.isBlank() && url.isBlank() -> ProviderProblem.NEEDS_KEY
            // Privatemode the same: a key, or the user's own proxy that holds one.
            type === ProviderTypes.PRIVATEMODE && apiKey.isBlank() && url.isBlank() -> ProviderProblem.NEEDS_KEY
            // Brave Answers with neither its own key nor a search key to borrow.
            type === ProviderTypes.BRAVE_ANSWERS && apiKey.isBlank() -> ProviderProblem.NEEDS_KEY
            // An image kind with neither its own key nor its AI service's to borrow.
            type.keyFrom != null && apiKey.isBlank() -> ProviderProblem.NEEDS_KEY
            type.credential.url == Need.REQUIRED && url.isBlank() -> ProviderProblem.NEEDS_SERVER
            // A home's key is a pairing or a sign-in, which the row says in its own words.
            HomeKind.of(type.id) != null -> homeProblem(entry, type, credential)
            type.credential.modelRequired && model.isBlank() -> ProviderProblem.NEEDS_MODEL
            type.credential.key == Need.REQUIRED && apiKey.isBlank() -> ProviderProblem.NEEDS_KEY
            // A kind signed with two secrets (ACRCloud) needs both; the row says "key" for either.
            type.credential.secret && credential?.extra?.get(CredentialSpec.SECRET).isNullOrBlank() -> ProviderProblem.NEEDS_KEY
            else -> null
        }
        return ResolvedProvider(entry, type, credential, apiKey, url, model, problem)
    }

    private fun homeProblem(entry: ProviderEntry, type: ProviderType, credential: ProviderCredential?): ProviderProblem? {
        val kind = HomeKind.of(type.id) ?: return ProviderProblem.UNKNOWN
        val account = HomeAccount(entry.key, kind, url = credential?.url?.trim().orEmpty(), apiKey = credential?.apiKey?.trim().orEmpty(), extra = credential?.extra.orEmpty())
        return when {
            !account.hasServer -> ProviderProblem.NEEDS_SERVER
            !account.signedIn -> ProviderProblem.NEEDS_SIGN_IN
            else -> null
        }
    }
}

/**
 * [tool] is [ProviderTool.SMART_HOME]: every home the panel should connect,
 * in list order. [all] keeps the ones that are off or not signed in, for a
 * settings page that shows them.
 */
fun KeyboardSettings.homes(env: ProviderEnv = ProviderEnvironment.current(), all: Boolean = false): List<HomeAccount> =
    resolveProviders(ProviderTool.SMART_HOME, env, all).mapNotNull { it.toHomeAccount() }

/** The home as the backends read it, or null for a type this build does not know. */
fun ResolvedProvider.toHomeAccount(): HomeAccount? {
    val kind = HomeKind.of(typeId) ?: return null
    return HomeAccount(
        key = key,
        kind = kind,
        name = customName,
        url = url,
        apiKey = apiKey,
        extra = credential?.extra.orEmpty(),
    )
}

/** The first Home Assistant on the list: the one Assist and the AI's "control your home" talk to. */
fun KeyboardSettings.assistHome(env: ProviderEnv = ProviderEnvironment.current()): HomeAccount? =
    homes(env).firstOrNull { it.kind == HomeKind.HOME_ASSISTANT }

/**
 * The lists and credentials the settings before provider lists amount to.
 *
 * Each list here is what the tool asked before, in the order it asked: the
 * GIF list is the providers that had a key, then Commons on F-Droid, then
 * GifCities when its switch was on, then the offline packs. A fresh install
 * reads its defaults through the same path, so new and upgraded installs
 * cannot drift apart.
 */
object LegacyProviders {

    fun credentials(s: KeyboardSettings): List<ProviderCredential> = buildList {
        fun key(type: ProviderType, value: String) {
            if (value.isNotBlank()) add(ProviderCredential(id = type.id, typeId = type.id, apiKey = value.trim()))
        }
        key(ProviderTypes.KLIPY, s.gif.klipyApiKey)
        key(ProviderTypes.GIPHY, s.gif.giphyApiKey)
        key(ProviderTypes.GOOGLE_TRANSLATE, s.translateApiKey)
        key(ProviderTypes.TAVILY, s.webSearch.tavilyApiKey)
        key(ProviderTypes.BRAVE, s.webSearch.braveApiKey)
        key(ProviderTypes.UNSPLASH, s.photoBackground.unsplashApiKey)
        key(ProviderTypes.PEXELS, s.photoBackground.pexelsApiKey)
        val deepl = s.translate.deepl
        if (deepl.configured) {
            add(
                ProviderCredential(
                    id = ProviderTypes.DEEPL.id,
                    typeId = ProviderTypes.DEEPL.id,
                    apiKey = deepl.apiKey.trim(),
                    url = deepl.endpoint.trim(),
                ),
            )
        }
        val server = s.translate.server
        if (server.configured) {
            add(
                ProviderCredential(
                    id = ProviderTypes.TRANSLATE_SERVER.id,
                    typeId = ProviderTypes.TRANSLATE_SERVER.id,
                    apiKey = server.apiKey.trim(),
                    url = server.url.trim(),
                    model = server.model.trim(),
                ),
            )
        }
        val hosted = s.selfHosted
        if (hosted.libreTranslateUrl.isNotBlank()) {
            add(
                ProviderCredential(
                    id = ProviderTypes.LIBRETRANSLATE.id,
                    typeId = ProviderTypes.LIBRETRANSLATE.id,
                    apiKey = hosted.libreTranslateApiKey.trim(),
                    url = hosted.libreTranslateUrl.trim(),
                ),
            )
        }
        if (hosted.searxUrl.isNotBlank()) {
            add(ProviderCredential(id = ProviderTypes.SEARXNG.id, typeId = ProviderTypes.SEARXNG.id, url = hosted.searxUrl.trim()))
        }
        if (hosted.commonsUrl.isNotBlank()) {
            add(ProviderCredential(id = ProviderTypes.COMMONS.id, typeId = ProviderTypes.COMMONS.id, url = hosted.commonsUrl.trim()))
        }
        aiCredentials(s.storedAi)
        // The voice tool's one transcription server, from before it kept a list (#286).
        val voice = s.storedWhisper
        if (voice.serverUrl.isNotBlank()) {
            add(
                ProviderCredential(
                    id = ProviderTypes.TRANSCRIPTION_SERVER.id,
                    typeId = ProviderTypes.TRANSCRIPTION_SERVER.id,
                    apiKey = voice.serverKey.trim(),
                    url = voice.serverUrl.trim(),
                    model = voice.serverModel.trim(),
                    extra = voice.serverPath.trim().takeIf { it.isNotEmpty() }?.let { mapOf(CredentialSpec.PATH to it) }.orEmpty(),
                ),
            )
        }
        // The OCR tool's one online reader, from before it kept a list (#469).
        val scanner = s.scanner
        if (scanner.ocrOnlineUrl.isNotBlank()) {
            add(
                ProviderCredential(
                    id = ProviderTypes.VISION_OCR.id,
                    typeId = ProviderTypes.VISION_OCR.id,
                    apiKey = scanner.ocrOnlineKey.trim(),
                    url = scanner.ocrOnlineUrl.trim(),
                    model = scanner.ocrOnlineModel.trim(),
                ),
            )
        }
    }

    /** One credential per AI service the old settings had a key or an address for, with its model. */
    private fun MutableList<ProviderCredential>.aiCredentials(ai: AiSettings) {
        fun service(type: ProviderType, key: String = "", url: String = "", model: String = "") {
            if (key.isBlank() && url.isBlank()) return
            this.add(ProviderCredential(id = type.id, typeId = type.id, apiKey = key.trim(), url = url.trim(), model = model.trim()))
        }
        service(ProviderTypes.ANTHROPIC, key = ai.anthropicKey, model = ai.anthropicModel)
        service(ProviderTypes.OPENAI, key = ai.openAiKey, model = ai.openAiModel)
        service(ProviderTypes.GEMINI, key = ai.geminiKey, model = ai.geminiModel)
        service(ProviderTypes.XAI, key = ai.xaiKey, model = ai.xaiModel)
        service(ProviderTypes.DEEPSEEK, key = ai.deepSeekKey, model = ai.deepSeekModel)
        service(ProviderTypes.BRAVE_ANSWERS, key = ai.braveKey)
        service(ProviderTypes.OLLAMA, url = ai.ollamaUrl, model = ai.ollamaModel)
        service(ProviderTypes.LM_STUDIO, url = ai.lmStudioUrl, model = ai.lmStudioModel)
        service(ProviderTypes.OPENAI_COMPATIBLE, key = ai.compatibleKey, url = ai.compatibleUrl, model = ai.compatibleModel)
    }

    fun chain(s: KeyboardSettings, tool: ProviderTool, env: ProviderEnv): ProviderChain {
        val credentials = s.providerCredentials()
        fun hasKey(type: ProviderType): Boolean =
            credentials.any { it.id == type.id && it.apiKey.isNotBlank() } || type.hasBuiltInKey
        fun has(type: ProviderType): Boolean = credentials.any { it.id == type.id }
        fun entry(type: ProviderType, on: Boolean = true) = ProviderEntry(type.id, enabled = on)
        val fdroid = env.channel == ProviderChannel.FDROID
        val mediaMode = when (s.gif.sourceMode) {
            GifSourceMode.TABS -> ChainMode.TABS
            GifSourceMode.MIX -> ChainMode.MIX
        }
        return when (tool) {
            ProviderTool.GIF -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.KLIPY)) add(entry(ProviderTypes.KLIPY))
                    if (hasKey(ProviderTypes.GIPHY)) add(entry(ProviderTypes.GIPHY))
                    if (fdroid) add(entry(ProviderTypes.COMMONS))
                    if (s.gif.gifCities) add(entry(ProviderTypes.GIFCITIES))
                    add(entry(ProviderTypes.OFFLINE_GIFS))
                },
                mediaMode,
            )
            // Commons used to join the sticker list on F-Droid too, and never
            // had a sticker to show; it is left out here.
            ProviderTool.STICKER -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.KLIPY)) add(entry(ProviderTypes.KLIPY))
                    if (hasKey(ProviderTypes.GIPHY)) add(entry(ProviderTypes.GIPHY))
                    add(entry(ProviderTypes.STICKER_PACKS))
                },
                mediaMode,
            )
            ProviderTool.TRANSLATE -> translateChain(s, env, ::has)
            ProviderTool.DICTIONARY -> ProviderChain(
                s.dictionarySources.map { ProviderEntry(it.source.id, enabled = it.enabled) },
            )
            ProviderTool.SYNONYMS -> ProviderChain(
                s.suggestionStrip.synonymSources.map { ProviderEntry(it.source.id, enabled = it.enabled) },
            )
            ProviderTool.VOCAB_AUTOFILL -> ProviderChain(
                listOf(entry(ProviderTypes.WIKTIONARY), entry(ProviderTypes.WIKTIONARY_REST), entry(ProviderTypes.DICTIONARY_API)),
            )
            // The more deliberate the setup, the earlier: a named SearXNG
            // instance, then a Tavily key only the user can have put there,
            // then Brave, whose key may be the build's.
            ProviderTool.SEARCH -> ProviderChain(
                buildList {
                    if (has(ProviderTypes.SEARXNG)) add(entry(ProviderTypes.SEARXNG))
                    if (hasKey(ProviderTypes.TAVILY)) add(entry(ProviderTypes.TAVILY))
                    if (hasKey(ProviderTypes.BRAVE)) add(entry(ProviderTypes.BRAVE))
                },
            )
            ProviderTool.PHOTOS -> ProviderChain(
                buildList {
                    if (hasKey(ProviderTypes.UNSPLASH)) add(entry(ProviderTypes.UNSPLASH))
                    if (hasKey(ProviderTypes.PEXELS)) add(entry(ProviderTypes.PEXELS))
                    if (fdroid) add(entry(ProviderTypes.COMMONS))
                },
                ChainMode.MIX,
            )
            ProviderTool.CURRENCY_FIAT ->
                currencyChain(s.rateSources.fiatProviders, tool, CurrencyClient.Provider.fiatDefaults(fdroid))
            ProviderTool.CURRENCY_CRYPTO ->
                currencyChain(s.rateSources.cryptoProviders, tool, CurrencyClient.Provider.cryptoDefaults(fdroid))
            // Nothing came before the list: a home is only ever added by the user.
            ProviderTool.SMART_HOME -> ProviderChain(emptyList(), ChainMode.MIX)
            // Only the reader the user set up: nothing reads a photo online
            // that the user did not name.
            ProviderTool.OCR -> ProviderChain(
                buildList { if (has(ProviderTypes.VISION_OCR)) add(entry(ProviderTypes.VISION_OCR)) },
            )
            // New with #727: empty until a Rens server is added.
            ProviderTool.PHOTO_TRANSLATE -> ProviderChain()
            ProviderTool.AI -> aiChain(s.storedAi, env, ::has)
            ProviderTool.VOICE -> voiceChain(s.storedWhisper, ::has)
            // Harper alone, as before the list. LanguageTool is listed so it
            // can be found, but off: it sends the text away, so it waits
            // until the user switches it on.
            ProviderTool.GRAMMAR -> ProviderChain(
                listOf(entry(ProviderTypes.HARPER), entry(ProviderTypes.LANGUAGETOOL, on = false)),
            )
            // The phone's models alone, as before the list. The two free
            // services are listed so they can be found, but off: they send
            // the prompt away, so they wait until the user switches them on.
            ProviderTool.IMAGE_GEN -> ProviderChain(
                listOf(
                    entry(ProviderTypes.ON_DEVICE_IMAGE),
                    entry(ProviderTypes.POLLINATIONS, on = false),
                    entry(ProviderTypes.AI_HORDE, on = false),
                ),
                ChainMode.PICK,
            )
            // Nothing came before the list, and nothing runs on the phone:
            // the services are listed so they can be found, but off until a
            // key is typed, since each is sent the clip.
            ProviderTool.MUSIC -> ProviderChain(
                listOf(
                    entry(ProviderTypes.AUDD, on = false),
                    entry(ProviderTypes.ACRCLOUD, on = false),
                    entry(ProviderTypes.AUDIOTAG, on = false),
                    entry(ProviderTypes.RAPID_SHAZAM, on = false),
                ),
            )
            // New with the tool: Cloudflare alone, which needs nothing set up.
            // A LibreSpeed server is only ever added by the user.
            ProviderTool.SPEED_TEST -> ProviderChain(listOf(entry(ProviderTypes.CLOUDFLARE_SPEED)), ChainMode.PICK)
        }
    }

    /**
     * Before: one provider chosen from chips, each with its own key. Every
     * service that was set up becomes a row, in the order the chips were
     * drawn, so the panel's model picker offers what it offered before; the
     * chosen one stays chosen through `AiSettings.provider`. A chip picked
     * but never given a key is left out: there is nothing to ask it with.
     */
    private fun aiChain(ai: AiSettings, env: ProviderEnv, has: (ProviderType) -> Boolean): ProviderChain {
        val rows = AiProvider.displayOrder.mapNotNull { provider ->
            val type = AiProviders.type(provider)
            val listed = when (provider) {
                // The search key counted only once Brave was the chosen provider (#438).
                AiProvider.BRAVE -> has(type) || (ai.provider == AiProvider.BRAVE && ai.braveSearchKey.isNotBlank())
                AiProvider.ON_DEVICE -> env.onDeviceAi && (ai.provider == AiProvider.ON_DEVICE || ai.localModelId.isNotBlank())
                else -> has(type)
            }
            ProviderEntry(type.id).takeIf { listed }
        }
        return ProviderChain(rows, ChainMode.PICK)
    }

    /**
     * Before: one engine, and a server's address kept for the "server"
     * choice. The engine leads; the server follows when it is set up and was
     * not the engine, for the languages sent to it; the phone's recognizer
     * comes last, which is what dictation fell back on when the engine could
     * not take a language.
     */
    private fun voiceChain(whisper: WhisperSettings, has: (ProviderType) -> Boolean): ProviderChain {
        val engine = ProviderTypes.voiceType(whisper.engine) ?: ProviderTypes.SYSTEM_RECOGNIZER
        val rows = buildList {
            add(ProviderEntry(engine.id))
            if (engine !== ProviderTypes.TRANSCRIPTION_SERVER && has(ProviderTypes.TRANSCRIPTION_SERVER)) {
                add(ProviderEntry(ProviderTypes.TRANSCRIPTION_SERVER.id))
            }
            if (engine !== ProviderTypes.SYSTEM_RECOGNIZER) add(ProviderEntry(ProviderTypes.SYSTEM_RECOGNIZER.id))
        }
        return ProviderChain(rows)
    }

    /**
     * Before: behind Online a fixed order. The user's own server, when set,
     * answered alone; then DeepL, then LibreTranslate, then Google. On the
     * device is the list's first row; the engine switch (On device, Online,
     * Automatic) still decides which part of the list is asked, as it always
     * has, so it stays out of the list itself.
     *
     * The server answering alone is kept by switching off what comes after
     * it: falling through to Google would send text the user meant for their
     * own machine to someone else's.
     */
    private fun translateChain(s: KeyboardSettings, env: ProviderEnv, has: (ProviderType) -> Boolean): ProviderChain {
        val online = buildList {
            val server = has(ProviderTypes.TRANSLATE_SERVER)
            if (server) add(ProviderEntry(ProviderTypes.TRANSLATE_SERVER.id))
            if (has(ProviderTypes.DEEPL) && s.translate.deepl.translate) add(ProviderEntry(ProviderTypes.DEEPL.id, enabled = !server))
            if (has(ProviderTypes.LIBRETRANSLATE)) add(ProviderEntry(ProviderTypes.LIBRETRANSLATE.id, enabled = !server))
            add(ProviderEntry(ProviderTypes.GOOGLE_TRANSLATE.id, enabled = !server))
        }
        // The phone's engines first, ML Kit before Bergamot where both run:
        // ML Kit was the one row before Bergamot came, and Bergamot is the
        // on-device engine wherever ML Kit is not. NLLB last of the three:
        // it covers what the others cannot, at seconds a sentence.
        val device = listOfNotNull(
            ProviderEntry(ProviderTypes.ML_KIT.id).takeIf { env.onDeviceTranslate },
            ProviderEntry(ProviderTypes.BERGAMOT.id).takeIf { env.bergamot },
            ProviderEntry(ProviderTypes.NLLB.id).takeIf { env.nllb },
        )
        return ProviderChain(device + online)
    }

    /**
     * The old lists stored `CurrencyClient.Provider` names; a type id is the
     * same name in lower case. A list with nothing usable in it fell back to
     * the defaults, and still does.
     */
    private fun currencyChain(names: List<String>, tool: ProviderTool, defaults: List<String>): ProviderChain {
        fun ids(from: List<String>) = from.map { it.lowercase() }
            .distinct()
            .filter { id -> ProviderTypes.of(id)?.serves?.contains(tool) == true }
        return ProviderChain(ids(names).ifEmpty { ids(defaults) }.map { ProviderEntry(it) })
    }
}

/**
 * Edits the settings screens make, as pure functions of the current settings:
 * each returns the whole new list and the whole new credential list, which
 * [SettingsRepository.saveProviders] writes in one transaction.
 */
object ProviderEdits {

    data class Change(
        val tool: ProviderTool?,
        val chain: ProviderChain?,
        val credentials: List<ProviderCredential>?,
    )

    /** Saves [credential] (new or changed) and, when [tool] is given, adds a row for it there. */
    fun saveCredential(
        s: KeyboardSettings,
        credential: ProviderCredential,
        tool: ProviderTool?,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val current = s.providerCredentials()
        val next = if (current.any { it.id == credential.id }) {
            current.map { if (it.id == credential.id) credential else it }
        } else {
            current + credential
        }
        if (tool == null) return Change(null, null, next)
        val entry = ProviderEntry(
            typeId = credential.typeId,
            credentialId = credential.id.takeIf { it != credential.typeId },
        )
        val chain = s.providerChain(tool, env)
        if (chain.has(entry.key)) return Change(tool, chain, next)
        return Change(tool, withNewRow(tool, chain, entry), next)
    }

    /**
     * A new row goes to the end, except the user's own translation server:
     * the point of running one is that the text goes nowhere else, so it goes
     * first and the other online rows are switched off, as they were when the
     * server was a single address field. The user can switch them back on.
     */
    private fun withNewRow(tool: ProviderTool, chain: ProviderChain, entry: ProviderEntry): ProviderChain {
        if (tool != ProviderTool.TRANSLATE || entry.typeId != ProviderTypes.TRANSLATE_SERVER.id) return chain.plus(entry)
        val (device, online) = chain.entries.partition { it.type?.onDevice == true }
        return chain.copy(entries = device + entry + online.map { it.copy(enabled = false) })
    }

    /** Adds a row that needs nothing set up, or reuses a credential that exists. */
    fun addEntry(
        s: KeyboardSettings,
        tool: ProviderTool,
        entry: ProviderEntry,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val chain = s.providerChain(tool, env)
        return Change(tool, if (chain.has(entry.key)) chain else withNewRow(tool, chain, entry), null)
    }

    fun setChain(tool: ProviderTool, chain: ProviderChain): Change = Change(tool, chain, null)

    /**
     * Takes the row out of [tool]'s list. Its credential goes too when no
     * other tool's list still reads it, so a removed server does not linger
     * with its password in the backups.
     */
    fun removeEntry(
        s: KeyboardSettings,
        tool: ProviderTool,
        key: String,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): Change {
        val chain = s.providerChain(tool, env)
        val entry = chain.entries.firstOrNull { it.key == key } ?: return Change(null, null, null)
        val next = chain.without(key)
        val stillUsed = ProviderTool.entries.filter { it != tool }
            .any { other -> s.providerChain(other, env).entries.any { it.credentialKey == entry.credentialKey } }
        val credentials = s.providerCredentials()
        val dropped = if (stillUsed || credentials.none { it.id == entry.credentialKey }) {
            null
        } else {
            credentials.filterNot { it.id == entry.credentialKey }
        }
        return Change(tool, next, dropped)
    }

    /** The other tools whose lists read [credentialId], for "Also used by". */
    fun usedBy(
        s: KeyboardSettings,
        credentialId: String,
        except: ProviderTool?,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): List<ProviderTool> = ProviderTool.entries.filter { it != except }
        .filter { tool -> s.providerChain(tool, env).entries.any { it.credentialKey == credentialId } }
}

/** The AI kinds, each with the [AiProvider] the AI client speaks to it as. */
object AiProviders {

    private val byType: Map<String, AiProvider> = mapOf(
        ProviderTypes.ANTHROPIC.id to AiProvider.ANTHROPIC,
        ProviderTypes.OPENAI.id to AiProvider.OPENAI,
        ProviderTypes.GEMINI.id to AiProvider.GEMINI,
        ProviderTypes.XAI.id to AiProvider.XAI,
        ProviderTypes.DEEPSEEK.id to AiProvider.DEEPSEEK,
        ProviderTypes.BRAVE_ANSWERS.id to AiProvider.BRAVE,
        ProviderTypes.OLLAMA.id to AiProvider.OLLAMA,
        ProviderTypes.LM_STUDIO.id to AiProvider.LM_STUDIO,
        ProviderTypes.OPENAI_COMPATIBLE.id to AiProvider.OPENAI_COMPATIBLE,
        ProviderTypes.PRIVATEMODE.id to AiProvider.PRIVATEMODE,
        ProviderTypes.ON_DEVICE_AI.id to AiProvider.ON_DEVICE,
    )

    fun of(typeId: String): AiProvider? = byType[typeId]

    fun type(provider: AiProvider): ProviderType =
        byType.entries.first { it.value == provider }.key.let { ProviderTypes.of(it)!! }
}

/**
 * The dictation engines this build and phone can run, by engine id: what
 * the voice list's page offers and marks. Loads each engine's native library
 * to find out, so it is for that page, not for every settings read.
 */
fun availableVoiceEngines(): Set<String> = buildSet {
    add(ProviderTypes.ENGINE_SYSTEM)
    add(ProviderTypes.ENGINE_SERVER)
    if (isWhisperEnabled()) add("whisper")
    // No library off arm64 or where F-Droid stripped it (#548).
    if (com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine.shipped) add("whistle")
    // The ExecuTorch runtime behind Asr4All, Parakeet and Moonshine (#289).
    if (com.wasimaster.wmkeyboard.core.voice.executorch.EtNative.shipped) {
        add("asr4all")
        add("parakeet")
        add("moonshine")
    }
    // sherpa-onnx on ONNX Runtime, the `onnx` gate: Sherpa's streaming
    // models and Meta's Omnilingual.
    if (com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative.shipped) {
        add("sherpa")
        add("omnilingual")
    }
    // Alpha Cephei's library, the `vosk` gate (#1165); not in F-Droid's build.
    if (com.wasimaster.wmkeyboard.core.voice.vosk.VoskNative.shipped) add("vosk")
}

/**
 * The environment the AI and voice choices are made in, on every settings
 * read: nothing looked at on the disk, and every voice engine taken as one
 * that can run. Dictation already falls back from an engine that cannot, as
 * it did when the engine was a single setting.
 */
fun choiceEnv(): ProviderEnv = ProviderEnvironment.current(lookAtDisk = false)

/** The AI rows that can answer: on, and set up. */
fun KeyboardSettings.aiRows(env: ProviderEnv = choiceEnv()): List<ResolvedProvider> =
    resolveProviders(ProviderTool.AI, env)

/**
 * The AI row in use: the one picked on the panel or the AI page, else the
 * first of the provider chosen before the list existed, else the first.
 */
fun KeyboardSettings.aiChoice(env: ProviderEnv = choiceEnv()): ResolvedProvider? {
    val rows = aiRows(env)
    return rows.firstOrNull { it.key == storedAi.providerKey }
        ?: rows.firstOrNull { AiProviders.of(it.typeId) == storedAi.provider }
        ?: rows.firstOrNull()
}

/**
 * These AI settings as the client reads them for [row]: its provider, and
 * its key, address and model in that provider's own fields. Brave's field
 * takes only a key typed for it; the client borrows the search key itself.
 */
fun AiSettings.through(row: ResolvedProvider): AiSettings {
    val provider = AiProviders.of(row.typeId) ?: return this
    val base = copy(provider = provider, providerKey = row.key, listed = true)
    return when (provider) {
        AiProvider.ANTHROPIC -> base.copy(anthropicKey = row.apiKey, anthropicModel = row.model)
        AiProvider.OPENAI -> base.copy(openAiKey = row.apiKey, openAiModel = row.model)
        AiProvider.GEMINI -> base.copy(geminiKey = row.apiKey, geminiModel = row.model)
        AiProvider.XAI -> base.copy(xaiKey = row.apiKey, xaiModel = row.model)
        AiProvider.DEEPSEEK -> base.copy(deepSeekKey = row.apiKey, deepSeekModel = row.model)
        AiProvider.BRAVE -> base.copy(braveKey = row.credential?.apiKey?.trim().orEmpty())
        AiProvider.OLLAMA -> base.copy(ollamaUrl = row.url, ollamaModel = row.model)
        AiProvider.LM_STUDIO -> base.copy(lmStudioUrl = row.url, lmStudioModel = row.model)
        AiProvider.OPENAI_COMPATIBLE -> base.copy(compatibleUrl = row.url, compatibleKey = row.apiKey, compatibleModel = row.model)
        AiProvider.PRIVATEMODE -> base.copy(privatemodeKey = row.apiKey, privatemodeModel = row.model, privatemodeProxyUrl = row.url)
        AiProvider.ON_DEVICE -> base
    }
}

/**
 * The AI settings through the row [key], for a picker that sends one
 * conversation somewhere other than the provider in use (the chat, the
 * settings assistant) without changing it. Null when that row cannot answer.
 */
fun KeyboardSettings.aiThrough(key: String, env: ProviderEnv = choiceEnv()): AiSettings? =
    aiRows(env).firstOrNull { it.key == key }?.let { ai.through(it) }

/** The voice rows that can run, in the order the list has them. */
fun KeyboardSettings.voiceRows(env: ProviderEnv = choiceEnv()): List<ResolvedProvider> =
    resolveProviders(ProviderTool.VOICE, env)

/**
 * The settings with the providers in use written where the code that runs
 * them reads: [AiSettings] through the AI row chosen, [WhisperSettings] with
 * the first voice engine that can run and the first transcription server.
 * The repository applies it to every settings object it reads, so the AI
 * client, the voice engines and every screen see one answer.
 *
 * With no AI row to answer, [AiSettings.listed] goes false and the client
 * reports the AI as not set up, whatever old keys are still stored. With no
 * voice engine on, the phone's recognizer answers, which is what a
 * microphone with nothing behind it would otherwise not do at all.
 */
fun KeyboardSettings.withProviderChoices(env: ProviderEnv = choiceEnv()): KeyboardSettings {
    val stored = copy(providers = providers.copy(legacyAi = storedAi, legacyWhisper = storedWhisper))
    val choice = stored.aiChoice(env)
    val ai = if (choice != null) {
        stored.ai.through(choice)
    } else {
        stored.ai.copy(
            listed = false,
            provider = if (stored.ai.provider == AiProvider.ON_DEVICE) AiProvider.ANTHROPIC else stored.ai.provider,
        )
    }
    val voices = stored.voiceRows(env)
    val server = voices.firstOrNull { it.typeId == ProviderTypes.TRANSCRIPTION_SERVER.id }
    val whisper = stored.whisper.copy(
        engine = voices.firstNotNullOfOrNull { ProviderTypes.VOICE_ENGINES[it.typeId] } ?: ProviderTypes.ENGINE_SYSTEM,
        serverUrl = server?.url.orEmpty(),
        serverKey = server?.apiKey.orEmpty(),
        serverModel = server?.model.orEmpty(),
        serverPath = server?.credential?.extra?.get(CredentialSpec.PATH)?.trim().orEmpty(),
    )
    return stored.copy(ai = ai, whisper = whisper)
}

/**
 * [chain] with [engine]'s row first and on, added when the list does not
 * have it: what "Use the phone's recognizer" on the voice panel does to a
 * list the user has edited. Null for an engine id no type runs.
 */
fun ProviderChain.preferringEngine(engine: String): ProviderChain? {
    val type = ProviderTypes.voiceType(engine) ?: return null
    val existing = entries.firstOrNull { it.typeId == type.id }
    val row = (existing ?: ProviderEntry(type.id)).copy(enabled = true)
    return copy(entries = listOf(row) + entries.filterNot { it.key == row.key })
}
