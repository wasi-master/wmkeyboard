package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.through
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.TranscriptionClient
import com.wasimaster.wmkeyboard.core.grammar.GrammarProviders
import com.wasimaster.wmkeyboard.core.grammar.LanguageToolClient
import com.wasimaster.wmkeyboard.core.imagegen.OnlineImageGen
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.tools.BraveSearchClient
import com.wasimaster.wmkeyboard.core.tools.MwmblSearchClient
import com.wasimaster.wmkeyboard.core.tools.CommonsClient
import com.wasimaster.wmkeyboard.core.tools.PhotoQuery
import com.wasimaster.wmkeyboard.core.tools.PhotoSearchClient
import com.wasimaster.wmkeyboard.core.tools.PhotoSource
import com.wasimaster.wmkeyboard.core.tools.SearxClient
import com.wasimaster.wmkeyboard.core.tools.TavilySearchClient
import com.wasimaster.wmkeyboard.core.tools.DeepLClient
import com.wasimaster.wmkeyboard.core.tools.LibreTranslateClient
import com.wasimaster.wmkeyboard.core.tools.LingvaClient
import com.wasimaster.wmkeyboard.core.tools.MozhiClient
import com.wasimaster.wmkeyboard.core.tools.RensClient
import com.wasimaster.wmkeyboard.core.tools.SimplyTranslateClient
import com.wasimaster.wmkeyboard.core.tools.TransliteClient
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TranslateServerClient
import com.wasimaster.wmkeyboard.core.tools.GiphyClient
import com.wasimaster.wmkeyboard.core.tools.KlipyClient
import com.wasimaster.wmkeyboard.app.homeassistant.HaSignIn
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.smarthome.HomeHttp
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueApi
import com.wasimaster.wmkeyboard.core.smarthome.hue.HueUrl
import com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsBackend
import com.wasimaster.wmkeyboard.core.smarthome.switchbot.SwitchBotBackend
import com.wasimaster.wmkeyboard.core.smarthome.tuya.TuyaBackend
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeBackends
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The Test button of the provider editor: the cheapest request each kind of
 * provider answers, made with what is in the editor now rather than what is
 * saved. Null for a kind with nothing worth testing (files on the phone, a
 * service that needs no setup).
 */
internal object ProviderProbes {

    @Suppress("UNUSED_PARAMETER")
    fun probe(
        context: Context,
        tool: ProviderTool,
        type: ProviderType,
        credential: ProviderCredential,
    ): (suspend () -> Unit)? {
        // The key the provider would be asked with: the typed one, else the build's.
        val key = credential.apiKey.ifBlank { type.builtInKey }
        // A sticker engine: a request that lists models or reads the account, so nothing is drawn or paid for.
        if (tool == ProviderTool.IMAGE_GEN) {
            if (!OnlineImageGen.probes(type.id)) return null
            return {
                withContext(Dispatchers.IO) {
                    // A blank key borrows the one typed for the kind's AI service, as the generator would.
                    val asked = key.ifBlank {
                        type.keyFrom?.let { from ->
                            SettingsRepository(context.applicationContext).settings.first().providerCredential(from)?.apiKey?.trim()
                        }.orEmpty()
                    }
                    OnlineImageGen.probe(
                        ResolvedProvider(
                            ProviderEntry(type.id, credential.id), type, credential,
                            apiKey = asked, url = credential.url, model = credential.model, problem = null,
                        ),
                    )
                }
            }
        }
        val sticker = tool == ProviderTool.STICKER
        val call: (() -> Unit)? = when (type.id) {
            ProviderTypes.KLIPY.id -> {
                { KlipyClient.search(PROBE_WORD, key, sticker, GifContentFilter.HIGH, limit = 1) }
            }
            ProviderTypes.GIPHY.id -> {
                { GiphyClient.search(PROBE_WORD, key, sticker, GifContentFilter.HIGH, limit = 1) }
            }
            ProviderTypes.COMMONS.id -> {
                { CommonsClient.searchGifs(PROBE_WORD, limit = 1, endpoint = credential.url) }
            }
            ProviderTypes.GOOGLE_TRANSLATE.id -> {
                { TranslateClient.translate(text = PROBE_WORD, targetLang = PROBE_TARGET, apiKey = key, sourceLang = PROBE_SOURCE) }
            }
            ProviderTypes.DEEPL.id -> {
                {
                    DeepLClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, apiKey = key, endpoint = credential.url, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.LIBRETRANSLATE.id -> {
                {
                    LibreTranslateClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, apiKey = key, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.MOZHI.id -> {
                {
                    MozhiClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, engine = credential.model, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.SIMPLYTRANSLATE.id -> {
                {
                    SimplyTranslateClient.translate(
                        text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, engine = credential.model, source = PROBE_SOURCE,
                    )
                }
            }
            ProviderTypes.LINGVA.id -> {
                { LingvaClient.translate(text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, source = PROBE_SOURCE) }
            }
            ProviderTypes.TRANSLITE.id -> {
                { TransliteClient.translate(text = PROBE_WORD, target = PROBE_TARGET, endpoint = credential.url, source = PROBE_SOURCE) }
            }
            // Rens has no request that costs nothing but a photo; its page having the form is the check.
            ProviderTypes.RENS.id -> {
                { RensClient.ping(credential.url) }
            }
            // The language list costs nothing and says the address is a LanguageTool server.
            ProviderTypes.LANGUAGETOOL.id -> {
                {
                    val server = GrammarProviders.serverOf(
                        ResolvedProvider(
                            ProviderEntry(type.id, credential.id), type, credential,
                            apiKey = credential.apiKey, url = credential.url, model = "", problem = null,
                        ),
                    )
                    check(LanguageToolClient.languages(server).isNotEmpty()) { "no languages" }
                }
            }
            ProviderTypes.TRANSLATE_SERVER.id -> {
                {
                    TranslateServerClient.translate(
                        text = PROBE_WORD,
                        target = PROBE_TARGET,
                        url = credential.url,
                        model = credential.model,
                        apiKey = key,
                        source = PROBE_SOURCE,
                        isActive = { true },
                    )
                }
            }
            ProviderTypes.SEARXNG.id -> {
                { SearxClient.webSearch(PROBE_WORD, credential.url, 1, true) }
            }
            ProviderTypes.TAVILY.id -> {
                { TavilySearchClient.webSearch(PROBE_WORD, key, 1, true) }
            }
            ProviderTypes.BRAVE.id -> {
                { BraveSearchClient.webSearch(PROBE_WORD, key, 1, true) }
            }
            ProviderTypes.MWMBL.id -> {
                { MwmblSearchClient.webSearch(PROBE_WORD, 1) }
            }
            // One second of silence: it transcribes to nothing, so an answer at all is the pass.
            // Sent as dictation sends a clip, so a server that cannot read Opus is found out here.
            ProviderTypes.TRANSCRIPTION_SERVER.id -> {
                {
                    TranscriptionClient.transcribe(
                        credential.url, key, credential.model, null,
                        FloatArray(WhisperMel.SAMPLE_RATE),
                        path = credential.extra[CredentialSpec.PATH].orEmpty(),
                    )
                }
            }
            else -> aiProbe(type, credential)
        }
        if (call != null) return { withContext(Dispatchers.IO) { call() } }
        homeProbe(context, type, credential)?.let { return it }
        // The photo services page through their own client, which suspends.
        val photo = when (type.id) {
            ProviderTypes.UNSPLASH.id -> PhotoSource.UNSPLASH
            ProviderTypes.PEXELS.id -> PhotoSource.PEXELS
            else -> return null
        }
        return { PhotoSearchClient.page(photo, PhotoQuery(text = PROBE_WORD, perPage = 1), key) }
    }

    private fun probe(block: suspend () -> Unit): suspend () -> Unit = block

    /**
     * A one-line question to an AI service, capped at a few tokens: the
     * cheapest request that proves the key, the address and the model
     * together. Null for the on-device model, which is tested by running it.
     */
    private fun aiProbe(type: ProviderType, credential: ProviderCredential): (() -> Unit)? {
        val provider = AiProviders.of(type.id)?.takeIf { it != AiProvider.ON_DEVICE } ?: return null
        val row = ResolvedProvider(
            entry = ProviderEntry(type.id, credential.id.takeIf { it != type.id }),
            type = type,
            credential = credential,
            apiKey = credential.apiKey,
            url = credential.url,
            model = credential.model,
            problem = null,
        )
        val config = AiClient.config(AiSettings(provider = provider).through(row))
        return {
            AiClient.completeStreaming(
                config, system = "", user = PROBE_WORD, maxTokens = AI_PROBE_TOKENS,
                onPhase = {}, onPartial = {},
            )
        }
    }

    /** The cheapest request each home answers with the key it holds; null for a home with nothing to ask yet. */
    private fun homeProbe(context: Context, type: ProviderType, credential: ProviderCredential): (suspend () -> Unit)? = when (type.id) {
        ProviderTypes.HOME_ASSISTANT.id -> {
            if (credential.apiKey.isBlank() && credential.extra[HomeKeys.ACCESS_TOKEN].isNullOrBlank()) {
                null
            } else {
                probe { HaSignIn.test(context, credential).getOrThrow() }
            }
        }
        ProviderTypes.OPENHAB.id -> {
            val base = credential.url.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
            probe { HomeBackends.http(NetSource.OPENHAB).get("$base/rest/", HomeHttp.bearer(credential.apiKey)).require() }
        }
        ProviderTypes.HUE.id -> {
            val base = HueUrl.base(credential.url)
            if (base == null || credential.apiKey.isBlank()) {
                null
            } else {
                probe { HueApi(HomeBackends.hueHttp(credential.extra[HomeKeys.CERT_SHA256]), { base }, { credential.apiKey }).bridge() ?: error("no bridge") }
            }
        }
        ProviderTypes.SMARTTHINGS.id -> {
            val token = credential.apiKey.ifBlank { credential.extra[HomeKeys.ACCESS_TOKEN].orEmpty() }
            if (token.isBlank()) {
                null
            } else {
                probe { HomeBackends.http(NetSource.SMARTTHINGS).get("${SmartThingsBackend.API}/locations", HomeHttp.bearer(token)).require() }
            }
        }
        ProviderTypes.TUYA.id -> {
            val account = credential.homeAccountFor(HomeKind.TUYA)
            if (!account.ready) null else probe { TuyaBackend.probe(HomeBackends.http(NetSource.TUYA), account) }
        }
        ProviderTypes.SWITCHBOT.id -> {
            val account = credential.homeAccountFor(HomeKind.SWITCHBOT)
            if (!account.ready) null else probe { SwitchBotBackend.probe(HomeBackends.http(NetSource.SWITCHBOT), account) }
        }
        else -> null
    }

    private fun ProviderCredential.homeAccountFor(kind: HomeKind): HomeAccount =
        HomeAccount(id, kind, url = url.trim(), apiKey = apiKey.trim(), extra = extra)

    /** Enough for a word back; a reasoning model may spend it all thinking, which still answers. */
    private const val AI_PROBE_TOKENS = 16

    /** A word every provider has something for. */
    private const val PROBE_WORD = "hello"
    private const val PROBE_SOURCE = "en"
    private const val PROBE_TARGET = "es"
}
