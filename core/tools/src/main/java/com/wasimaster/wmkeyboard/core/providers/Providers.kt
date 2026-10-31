package com.wasimaster.wmkeyboard.core.providers

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.tools.R
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A tool that can ask more than one service for the same thing, and so keeps
 * a list of providers the user orders, switches on and off, adds to and
 * removes from. The backup tool's locations are the model: a provider is set
 * up once, and the tool asks the ones that are on, in order.
 *
 * [id] is what the settings store: never store [name]. [modes] are the ways
 * the tool may use its list; the first is the default.
 */
enum class ProviderTool(val id: String, val modes: List<ChainMode>, @StringRes val labelRes: Int) {
    GIF("gif", listOf(ChainMode.TABS, ChainMode.MIX, ChainMode.FALLBACK), R.string.core_tools_provider_tool_gif),
    STICKER("sticker", listOf(ChainMode.TABS, ChainMode.MIX, ChainMode.FALLBACK), R.string.core_tools_provider_tool_sticker),
    TRANSLATE("translate", listOf(ChainMode.FALLBACK, ChainMode.PICK), R.string.core_tools_provider_tool_translate),
    DICTIONARY("dictionary", listOf(ChainMode.FALLBACK, ChainMode.PICK), R.string.core_tools_provider_tool_dictionary),
    SYNONYMS("synonyms", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_synonyms),
    VOCAB_AUTOFILL("vocab_autofill", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_vocab),

    /** The web and the image search tools: today one service answers both. */
    SEARCH("search", listOf(ChainMode.FALLBACK, ChainMode.PICK), R.string.core_tools_provider_tool_search),
    /** Always mixed: the photo grid pages through every provider at once and keeps what answered. */
    PHOTOS("photos", listOf(ChainMode.MIX), R.string.core_tools_provider_tool_photos),
    CURRENCY_FIAT("currency_fiat", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_currency_fiat),
    CURRENCY_CRYPTO("currency_crypto", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_currency_crypto),

    /** The smart home panel: every home on the list is connected at once, in list order. */
    SMART_HOME("smart_home", listOf(ChainMode.MIX), R.string.core_tools_provider_tool_smart_home),
    /** The OCR tool's online readers, asked when its engine is Online. The on-device engines stay a setting. */
    OCR("ocr", listOf(ChainMode.FALLBACK, ChainMode.PICK), R.string.core_tools_provider_tool_ocr),

    /** The camera tool's Translate button (#727): the photo comes back with its text translated. */
    PHOTO_TRANSLATE("photo_translate", listOf(ChainMode.FALLBACK, ChainMode.PICK), R.string.core_tools_provider_tool_photo_translate),

    /**
     * The AI tools, the chat and the settings assistant. One answers at a
     * time: the one picked on the AI panel or on the AI page, kept in
     * `AiSettings.providerKey`, so the list is always in [ChainMode.PICK].
     */
    AI("ai", listOf(ChainMode.PICK), R.string.core_tools_provider_tool_ai),

    /**
     * Voice typing's engines. The first that is on and can run is the
     * default engine; a language can still be given another one of its own.
     * The first transcription server on the list is the one a language set
     * to "server" sends its clips to.
     */
    VOICE("voice", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_voice),

    /**
     * The grammar tool's checkers: Harper on the phone, and LanguageTool
     * servers. A check goes to the first row that is on and knows the
     * keyboard's language, and on to the next when that one cannot answer.
     */
    GRAMMAR("grammar", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_grammar),

    /**
     * The sticker generator's engines: the model on the phone, and image
     * services online. The generator draws with the one picked on its screen,
     * or, in [ChainMode.FALLBACK], with the first that is on and answers.
     */
    IMAGE_GEN("image_gen", listOf(ChainMode.PICK, ChainMode.FALLBACK), R.string.core_tools_provider_tool_image_gen),

    /**
     * The Music ID tool's recognisers: the online services, each asked in
     * turn until one names the song. Nothing runs on the phone, so the list
     * starts with every kind off until a key is typed.
     */
    MUSIC("music", listOf(ChainMode.FALLBACK), R.string.core_tools_provider_tool_music),

    /**
     * The speed test's servers: Cloudflare's, and LibreSpeed backends, the
     * user's own or picked from the public list. One run goes to one server,
     * the one chosen on the panel, so the list is always in [ChainMode.PICK].
     */
    SPEED_TEST("speed_test", listOf(ChainMode.PICK), R.string.core_tools_provider_tool_speed_test),
    ;

    /**
     * Whether this list can hold [type] more than once. A server kind always
     * can, each row its own address. A kind set up with a key can here too,
     * for a second key or a second account, on the lists whose callers
     * ask each row by its own credential: not the photo grid or the currency
     * lists, which ask a service by its name, and not the word sources,
     * which have nothing to set up.
     */
    fun allowsAnother(type: ProviderType): Boolean =
        type.multiInstance || (type.repeatable && this in REPEATING)

    /**
     * Whether [SameKind] does anything on this list in [mode]: the media
     * chips and the panel's chooser fold a kind into one, a fetch walks its
     * backups. The smart home panel connects every home, and the photo grid
     * asks by service, so neither has a choice to offer.
     */
    fun foldsSameKind(mode: ChainMode): Boolean =
        (mode == ChainMode.PICK && this != AI) || (mode != ChainMode.FALLBACK && (this == GIF || this == STICKER))

    companion object {
        fun of(id: String): ProviderTool? = entries.firstOrNull { it.id == id }

        private val REPEATING by lazy { setOf(GIF, STICKER, TRANSLATE, SEARCH, OCR, PHOTO_TRANSLATE, AI, IMAGE_GEN, MUSIC) }
    }
}

/**
 * How a tool uses its list.
 *
 * [FALLBACK] asks the first provider that is on, and the next one only when
 * that one fails or knows nothing. [MIX] asks all of them at once and
 * interleaves the answers, keeping what came back when some fail. [TABS] gives
 * each provider a chip of its own, and a fetch asks the one whose chip is on.
 * [PICK] is [TABS] for the tools with one answer rather than a grid: the
 * panel has a chooser of the providers that are on, and only the chosen one
 * is asked. It is stored by name, so a list saved by a later build with a
 * mode this one lacks falls back to the tool's default ([ProviderCodec]).
 */
@Serializable
enum class ChainMode(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    FALLBACK(R.string.core_tools_provider_mode_fallback, R.string.core_tools_provider_mode_fallback_desc),
    MIX(R.string.core_tools_provider_mode_mix, R.string.core_tools_provider_mode_mix_desc),
    TABS(R.string.core_tools_provider_mode_tabs, R.string.core_tools_provider_mode_tabs_desc),
    PICK(R.string.core_tools_provider_mode_pick, R.string.core_tools_provider_mode_pick_desc),
}

/**
 * What a list does with two rows of one kind (two KLIPY keys, two DeepL
 * accounts) when its mode is not [ChainMode.FALLBACK], where the whole list
 * is a fallback already.
 *
 * [SEPARATE] keeps them apart: a chip each, a row each in the panel's
 * chooser, a share each of a mixed grid. [FALLBACK] makes them one provider:
 * the first row of the kind is asked, and the later ones only when it fails,
 * which is what a second key for when the first runs out of quota wants.
 * Stored by name, and unset ([ProviderChain.sameKindInForce]) until the user picks.
 */
@Serializable
enum class SameKind(@StringRes val labelRes: Int, @StringRes val descRes: Int) {
    FALLBACK(R.string.core_tools_provider_same_kind_fallback, R.string.core_tools_provider_same_kind_fallback_desc),
    SEPARATE(R.string.core_tools_provider_same_kind_separate, R.string.core_tools_provider_same_kind_separate_desc),
}

/** Whether a provider's form asks for a field, and whether it may stay blank. */
enum class Need { NONE, OPTIONAL, REQUIRED }

/**
 * What setting a provider up asks for. A [key] that is [Need.REQUIRED] can
 * still be blank when the build carries its own ([ProviderType.builtInKey]).
 */
data class CredentialSpec(
    val key: Need = Need.NONE,
    val url: Need = Need.NONE,
    val model: Boolean = false,
    /** [model] must be filled in: a vision server cannot guess which model reads images. */
    val modelRequired: Boolean = false,
    /**
     * The services a front end can pass the text on to, the default first.
     * The pick is stored in [ProviderCredential.model]: like a model, it says
     * what the server should use, and a blank one means the default.
     */
    val engines: List<FrontEndEngine> = emptyList(),
    /**
     * A route of the server's own, for one that does not answer where its
     * address implies (a transcription server on `/inference`). Stored in
     * [ProviderCredential.extra] under [PATH]; blank works it out.
     */
    val path: Boolean = false,
    /**
     * An account name that goes with the key: LanguageTool Premium signs a
     * request with both. Stored in [ProviderCredential.extra] under [USERNAME].
     */
    val username: Boolean = false,
    /**
     * An account id that goes in the address rather than the key: Cloudflare
     * runs a model under `/accounts/{id}/`. Stored in
     * [ProviderCredential.extra] under [ACCOUNT].
     */
    val account: Boolean = false,
    /**
     * A second secret that goes with the key: ACRCloud signs each request
     * with an access key and an access secret. Stored in
     * [ProviderCredential.extra] under [SECRET], and shown masked like the
     * key, which is why it is not [username].
     */
    val secret: Boolean = false,
) {
    val none: Boolean
        get() = key == Need.NONE && url == Need.NONE && !model && engines.isEmpty() && !path && !username && !account && !secret

    /** The engine [stored] names, else the default; null for a kind with no engines. */
    fun engine(stored: String): FrontEndEngine? =
        engines.firstOrNull { it.id == stored.trim() } ?: engines.firstOrNull()

    companion object {
        /** The [ProviderCredential.extra] key a [path] is kept under. */
        const val PATH = "path"

        /** The [ProviderCredential.extra] key a [username] is kept under. */
        const val USERNAME = "username"

        /** The [ProviderCredential.extra] key an [account] is kept under. */
        const val ACCOUNT = "account"

        /** The [ProviderCredential.extra] key a [secret] is kept under. */
        const val SECRET = "secret"
    }
}

/** One service behind a translation front end. [id] is what the front end's API takes; [label] is a brand name. */
data class FrontEndEngine(val id: String, val label: String)

/**
 * One kind of provider: KLIPY, a LibreTranslate server, the vocabulary packs.
 *
 * [id] is stored, so never rename one. Ids that an older list already stored
 * (`DictionarySource.id`, `SynonymSource.id`, a `CurrencyClient.Provider`
 * name in lower case, a `GifSource` name in lower case) are kept as they were,
 * which is what lets an existing list read straight across.
 *
 * [multiInstance] types can be added more than once, each with its own
 * address: two SearXNG instances, a public LibreTranslate behind a private
 * one. The rest exist once, and their one set of credentials is shared by
 * every tool that lists them, so a KLIPY key typed under GIFs is the key
 * stickers use too.
 */
class ProviderType(
    val id: String,
    val serves: Set<ProviderTool>,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val credential: CredentialSpec = CredentialSpec(),
    val multiInstance: Boolean = false,
    /** Works with no network: files or a model on the phone. */
    val onDevice: Boolean = false,
    /** Free software anyone can run, or open data. */
    val freeSoftware: Boolean = false,
    /** A server the user runs themself rather than a public service. */
    val selfHosted: Boolean = false,
    /** The key this build carries, or blank. Read from [BuildConfig], never stored. */
    val builtInKey: String = "",
    /** Never interleaved with another provider's results: GIPHY's API terms, section 1. */
    val ownGrid: Boolean = false,
    /** A tool whose description differs from [descRes]: Wiktionary means one thing to the dictionary, another to synonyms. */
    private val toolDescRes: Map<ProviderTool, Int> = emptyMap(),
    /**
     * The kind whose key a blank key borrows: one OpenAI account pays for
     * both its chat and its pictures, so the key typed for the AI tool works
     * for the sticker generator too. Only a key the user typed, never a
     * build's. A row with neither needs a key.
     */
    val keyFrom: String? = null,
) {
    @StringRes
    fun descriptionRes(tool: ProviderTool): Int = toolDescRes[tool] ?: descRes

    val hasBuiltInKey: Boolean get() = builtInKey.isNotBlank()

    /**
     * Can be set up more than once: a server kind, or a kind with a key, for
     * a second key or account. Whether a list takes it twice is
     * [ProviderTool.allowsAnother].
     */
    val repeatable: Boolean
        get() = multiInstance || credential.key != Need.NONE

    /** Needs nothing typed in to work: no key, no address, or a key the build has. */
    val readyOutOfTheBox: Boolean
        get() = credential.url != Need.REQUIRED && (credential.key != Need.REQUIRED || hasBuiltInKey)

    override fun toString(): String = id
}

/** Every provider type this build knows, in the order the add sheet lists them. */
object ProviderTypes {

    private val MEDIA = setOf(ProviderTool.GIF, ProviderTool.STICKER)

    val KLIPY = ProviderType(
        "klipy", MEDIA, R.string.core_tools_gif_source_klipy, R.string.core_tools_provider_klipy_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.KLIPY_API_KEY,
    )
    val GIPHY = ProviderType(
        "giphy", MEDIA, R.string.core_tools_gif_source_giphy, R.string.core_tools_provider_giphy_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.GIPHY_API_KEY, ownGrid = true,
    )

    /**
     * Wikimedia Commons, or another MediaWiki. A blank address is Commons
     * itself, which is why the one entry needs no credential at all.
     */
    val COMMONS = ProviderType(
        "commons", setOf(ProviderTool.GIF, ProviderTool.PHOTOS),
        R.string.core_tools_provider_commons, R.string.core_tools_provider_commons_desc,
        CredentialSpec(url = Need.OPTIONAL), multiInstance = true, freeSoftware = true,
    )
    val GIFCITIES = ProviderType(
        "gifcities", setOf(ProviderTool.GIF),
        R.string.core_tools_gif_source_gifcities, R.string.core_tools_provider_gifcities_desc,
    )
    val OFFLINE_GIFS = ProviderType(
        "offline_gif_packs", setOf(ProviderTool.GIF),
        R.string.core_tools_provider_offline_gifs, R.string.core_tools_provider_offline_gifs_desc,
        onDevice = true,
    )
    val STICKER_PACKS = ProviderType(
        "sticker_packs", setOf(ProviderTool.STICKER),
        R.string.core_tools_gif_source_local, R.string.core_tools_provider_sticker_packs_desc,
        onDevice = true,
    )

    val ML_KIT = ProviderType(
        "mlkit", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_mlkit, R.string.core_tools_provider_mlkit_desc,
        onDevice = true,
    )
    /**
     * Mozilla's Bergamot engine with the Firefox Translations models: on the
     * phone like ML Kit, but free software that needs no Google services,
     * so it is also the on-device engine of F-Droid and de-Googled phones.
     */
    val BERGAMOT = ProviderType(
        "bergamot", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_bergamot, R.string.core_tools_provider_bergamot_desc,
        onDevice = true, freeSoftware = true,
    )

    /**
     * Meta's NLLB-200 on CTranslate2: on the phone too, one 630 MB model for
     * 200 languages in any direction, for the pairs Bergamot has no model
     * for. Slower and far larger than Bergamot, so it comes after it. The
     * engine is free software; the weights are CC BY-NC 4.0, so not
     * [freeSoftware].
     */
    val NLLB = ProviderType(
        "nllb", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_nllb, R.string.core_tools_provider_nllb_desc,
        onDevice = true,
    )
    val TRANSLATE_SERVER = ProviderType(
        "translate_server", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_translate_server, R.string.core_tools_provider_translate_server_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true),
        multiInstance = true, selfHosted = true,
    )

    /** A key, a proxy address, or both; [ProviderResolution] asks for at least one. */
    val DEEPL = ProviderType(
        "deepl", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_deepl, R.string.core_tools_provider_deepl_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.OPTIONAL),
    )
    val LIBRETRANSLATE = ProviderType(
        "libretranslate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_libretranslate, R.string.core_tools_provider_libretranslate_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED),
        multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /*
     * Front ends (#726): free servers that pass the text on to Google, DeepL
     * and others, so those see the instance rather than the phone. Each is
     * added by the address of an instance, as often as the user likes; none
     * has a default one. Not [selfHosted]: most people use a public instance.
     */
    val MOZHI = ProviderType(
        "mozhi", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_mozhi, R.string.core_tools_provider_mozhi_desc,
        CredentialSpec(
            url = Need.REQUIRED,
            engines = listOf(
                FrontEndEngine("google", "Google"),
                FrontEndEngine("deepl", "DeepL"),
                FrontEndEngine("duckduckgo", "DuckDuckGo"),
                FrontEndEngine("yandex", "Yandex"),
                FrontEndEngine("reverso", "Reverso"),
            ),
        ),
        multiInstance = true, freeSoftware = true,
    )
    val SIMPLYTRANSLATE = ProviderType(
        "simplytranslate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_simplytranslate, R.string.core_tools_provider_simplytranslate_desc,
        CredentialSpec(
            url = Need.REQUIRED,
            engines = listOf(
                FrontEndEngine("google", "Google"),
                FrontEndEngine("reverso", "Reverso"),
                FrontEndEngine("iciba", "iCIBA"),
            ),
        ),
        multiInstance = true, freeSoftware = true,
    )
    val LINGVA = ProviderType(
        "lingva", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_lingva, R.string.core_tools_provider_lingva_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true,
    )
    val TRANSLITE = ProviderType(
        "translite", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_translite, R.string.core_tools_provider_translite_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true,
    )

    /**
     * Rens (#727), a free front end to Google Lens: reads the text in a photo
     * for the OCR tool, and gives the photo back translated for the camera.
     */
    val RENS = ProviderType(
        "rens", setOf(ProviderTool.OCR, ProviderTool.PHOTO_TRANSLATE),
        R.string.core_tools_provider_rens, R.string.core_tools_provider_rens_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true,
        toolDescRes = mapOf(ProviderTool.PHOTO_TRANSLATE to R.string.core_tools_provider_rens_translate_desc),
    )

    /** A model that reads images through an OpenAI-compatible API (#469), the OCR tool's first online reader. */
    val VISION_OCR = ProviderType(
        "vision_ocr", setOf(ProviderTool.OCR),
        R.string.core_tools_provider_vision_ocr, R.string.core_tools_provider_vision_ocr_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true, modelRequired = true),
        multiInstance = true, selfHosted = true,
    )

    /** Google's keyless endpoint, or Cloud Translation v2 once a key is set. */
    val GOOGLE_TRANSLATE = ProviderType(
        "google_translate", setOf(ProviderTool.TRANSLATE),
        R.string.core_tools_provider_google_translate, R.string.core_tools_provider_google_translate_desc,
        CredentialSpec(key = Need.OPTIONAL), builtInKey = BuildConfig.TRANSLATE_API_KEY,
    )

    private val WORDS = setOf(ProviderTool.DICTIONARY, ProviderTool.SYNONYMS)

    val VOCAB_PACKS = ProviderType(
        "vocab_packs", WORDS,
        R.string.core_tools_dictionary_source_vocab_packs, R.string.core_tools_dictionary_source_vocab_packs_desc,
        onDevice = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_vocab_packs_desc),
    )

    /** kaikki.org's copy of Wiktionary. */
    val WIKTIONARY = ProviderType(
        "wiktionary", WORDS + ProviderTool.VOCAB_AUTOFILL,
        R.string.core_tools_dictionary_source_wiktionary, R.string.core_tools_dictionary_source_wiktionary_desc,
        freeSoftware = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_wiktionary_desc),
    )
    val WIKTIONARY_REST = ProviderType(
        "wiktionary_rest", setOf(ProviderTool.DICTIONARY, ProviderTool.VOCAB_AUTOFILL),
        R.string.core_tools_dictionary_source_wiktionary_rest, R.string.core_tools_dictionary_source_wiktionary_rest_desc,
        freeSoftware = true,
    )
    val DICTIONARY_API = ProviderType(
        "dictionary_api", WORDS + ProviderTool.VOCAB_AUTOFILL,
        R.string.core_tools_dictionary_source_dictionary_api, R.string.core_tools_dictionary_source_dictionary_api_desc,
        freeSoftware = true,
        toolDescRes = mapOf(ProviderTool.SYNONYMS to R.string.core_tools_synonym_source_dictionary_api_desc),
    )

    /** The offline dictionaries (#665): the ids are their `DictionarySource` ids. */
    val OFFLINE_WIKTIONARY = ProviderType(
        "offline_wiktionary", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_offline_wiktionary, R.string.core_tools_dictionary_source_offline_wiktionary_desc,
        onDevice = true, freeSoftware = true,
    )
    val OFFLINE_WORDNET = ProviderType(
        "offline_wordnet", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_offline_wordnet, R.string.core_tools_dictionary_source_offline_wordnet_desc,
        onDevice = true, freeSoftware = true,
    )
    val OFFLINE_BENGALI = ProviderType(
        "offline_bn_wiktionary", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_offline_bengali, R.string.core_tools_dictionary_source_offline_bengali_desc,
        onDevice = true, freeSoftware = true,
    )
    /** The Yomichan / Yomitan dictionaries the user imported (#765); the id is its `DictionarySource` id. */
    val IMPORTED_DICTIONARIES = ProviderType(
        "imported_dictionaries", setOf(ProviderTool.DICTIONARY),
        R.string.core_tools_dictionary_source_imported, R.string.core_tools_dictionary_source_imported_desc,
        onDevice = true,
    )
    val DATAMUSE = ProviderType(
        "datamuse", setOf(ProviderTool.SYNONYMS),
        R.string.core_tools_synonym_source_datamuse, R.string.core_tools_synonym_source_datamuse_desc,
    )
    val SIMILAR_WORDS = ProviderType(
        "similar_words", setOf(ProviderTool.SYNONYMS),
        R.string.core_tools_synonym_source_similar_words, R.string.core_tools_synonym_source_similar_words_desc,
    )

    val SEARXNG = ProviderType(
        "searxng", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_searxng, R.string.core_tools_provider_searxng_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true, selfHosted = true,
    )
    val TAVILY = ProviderType(
        "tavily", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_tavily, R.string.core_tools_provider_tavily_desc,
        CredentialSpec(key = Need.REQUIRED),
    )
    val BRAVE = ProviderType(
        "brave", setOf(ProviderTool.SEARCH),
        R.string.core_tools_provider_brave, R.string.core_tools_provider_brave_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.BRAVE_API_KEY,
    )

    val UNSPLASH = ProviderType(
        "unsplash", setOf(ProviderTool.PHOTOS),
        R.string.core_tools_photo_source_unsplash, R.string.core_tools_provider_unsplash_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.UNSPLASH_API_KEY,
    )
    val PEXELS = ProviderType(
        "pexels", setOf(ProviderTool.PHOTOS),
        R.string.core_tools_photo_source_pexels, R.string.core_tools_provider_pexels_desc,
        CredentialSpec(key = Need.REQUIRED), builtInKey = BuildConfig.PEXELS_API_KEY,
    )

    private val FIAT = setOf(ProviderTool.CURRENCY_FIAT)
    private val COINS = setOf(ProviderTool.CURRENCY_CRYPTO)

    val ER_API = ProviderType(
        "er_api", FIAT, R.string.core_tools_provider_er_api, R.string.core_tools_provider_er_api_desc,
    )
    val FRANKFURTER = ProviderType(
        "frankfurter", FIAT, R.string.core_tools_provider_frankfurter, R.string.core_tools_provider_frankfurter_desc,
        freeSoftware = true,
    )
    val ALLRATESTODAY = ProviderType(
        "allratestoday", FIAT, R.string.core_tools_provider_allratestoday, R.string.core_tools_provider_allratestoday_desc,
    )
    val COINBASE = ProviderType(
        "coinbase", FIAT + COINS, R.string.core_tools_provider_coinbase, R.string.core_tools_provider_coinbase_desc,
    )
    val CURRENCY_API = ProviderType(
        "currency_api", FIAT + COINS, R.string.core_tools_provider_currency_api, R.string.core_tools_provider_currency_api_desc,
        freeSoftware = true,
    )
    val COINGECKO = ProviderType(
        "coingecko", COINS, R.string.core_tools_provider_coingecko, R.string.core_tools_provider_coingecko_desc,
    )

    // ---- Smart home. The ids are `HomeKind.typeId` in :core:homeassistant; keep them in step.

    private val HOMES = setOf(ProviderTool.SMART_HOME)

    /**
     * The address is the home one; the remote address, the sign-in tokens and
     * what the server said about itself live in the credential's `extra`. A
     * pasted long-lived token is the key.
     */
    val HOME_ASSISTANT = ProviderType(
        "home_assistant", HOMES, R.string.core_tools_provider_home_assistant, R.string.core_tools_provider_home_assistant_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.OPTIONAL), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /** Google's Home APIs through Play services; the grant lives in `extra`. Only where the build carries the OAuth client. */
    val GOOGLE_HOME = ProviderType(
        "google_home", HOMES, R.string.core_tools_provider_google_home, R.string.core_tools_provider_google_home_desc,
    )

    /**
     * A browser sign-in fills `extra`; a pasted personal access token (24
     * hours) is the key. One row per account, as often as there are
     * accounts: a home, a parent's house, an office.
     */
    val SMARTTHINGS = ProviderType(
        "smartthings", HOMES, R.string.core_tools_provider_smartthings, R.string.core_tools_provider_smartthings_desc,
        CredentialSpec(key = Need.OPTIONAL), multiInstance = true,
    )

    /** A Hue bridge on the LAN: the address, and the application key pairing gives. */
    val HUE = ProviderType(
        "hue", HOMES, R.string.core_tools_provider_hue, R.string.core_tools_provider_hue_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.REQUIRED), multiInstance = true, selfHosted = true,
    )

    val OPENHAB = ProviderType(
        "openhab", HOMES, R.string.core_tools_provider_openhab, R.string.core_tools_provider_openhab_desc,
        CredentialSpec(url = Need.REQUIRED, key = Need.OPTIONAL), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /**
     * Smart Life and Tuya Smart, through the user's own Tuya Developer
     * Platform project: the address is its data centre, picked from a list in
     * the row's setup; the key its Access Secret; the Access ID in `extra`.
     */
    val TUYA = ProviderType(
        "tuya", HOMES, R.string.core_tools_provider_tuya, R.string.core_tools_provider_tuya_desc,
        CredentialSpec(key = Need.REQUIRED), multiInstance = true,
    )

    /** The token from the SwitchBot app's developer options is the key; its secret key is in `extra`. */
    val SWITCHBOT = ProviderType(
        "switchbot", HOMES, R.string.core_tools_provider_switchbot, R.string.core_tools_provider_switchbot_desc,
        CredentialSpec(key = Need.REQUIRED), multiInstance = true,
    )

    // ---- AI. Each maps to one `AiProvider` in :core:settings (`AiProviders`); keep them in step.

    private val AI = setOf(ProviderTool.AI)

    val ANTHROPIC = ProviderType(
        "anthropic", AI, R.string.core_tools_provider_anthropic, R.string.core_tools_provider_anthropic_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val OPENAI = ProviderType(
        "openai", AI, R.string.core_tools_provider_openai, R.string.core_tools_provider_openai_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val GEMINI = ProviderType(
        "gemini", AI, R.string.core_tools_provider_gemini, R.string.core_tools_provider_gemini_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val XAI = ProviderType(
        "xai", AI, R.string.core_tools_provider_xai, R.string.core_tools_provider_xai_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val DEEPSEEK = ProviderType(
        "deepseek", AI, R.string.core_tools_provider_deepseek, R.string.core_tools_provider_deepseek_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )

    /**
     * Brave's Answers API, not its search: the two plans have keys of their
     * own, so it is a kind apart from [BRAVE]. A blank key borrows the search
     * one the user typed ([ProviderResolution]), never the build's, which is
     * why the key is optional here and a row with neither needs a key.
     */
    val BRAVE_ANSWERS = ProviderType(
        "brave_answers", AI, R.string.core_tools_provider_brave_answers, R.string.core_tools_provider_brave_answers_desc,
        CredentialSpec(key = Need.OPTIONAL),
    )
    val OLLAMA = ProviderType(
        "ollama", AI, R.string.core_tools_provider_ollama, R.string.core_tools_provider_ollama_desc,
        CredentialSpec(url = Need.REQUIRED, model = true), multiInstance = true, freeSoftware = true, selfHosted = true,
    )
    val LM_STUDIO = ProviderType(
        "lm_studio", AI, R.string.core_tools_provider_lm_studio, R.string.core_tools_provider_lm_studio_desc,
        CredentialSpec(url = Need.REQUIRED, model = true), multiInstance = true, selfHosted = true,
    )

    /** Any other service with OpenAI's chat shape: OpenRouter, Groq, Mistral, a gateway at home. */
    val OPENAI_COMPATIBLE = ProviderType(
        "openai_compatible", AI, R.string.core_tools_provider_openai_compatible, R.string.core_tools_provider_openai_compatible_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true, modelRequired = true), multiInstance = true,
    )

    /** The downloaded model the AI page's model manager picked; only where the build carries the runtime. */
    val ON_DEVICE_AI = ProviderType(
        "on_device_ai", AI, R.string.core_tools_provider_on_device_ai, R.string.core_tools_provider_on_device_ai_desc,
        onDevice = true,
    )

    // ---- Voice typing. The ids map to the engine ids `WhisperSettings.engine` stores ([VOICE_ENGINES]).

    private val VOICE = setOf(ProviderTool.VOICE)

    val SYSTEM_RECOGNIZER = ProviderType(
        "system_recognizer", VOICE, R.string.core_tools_provider_system_recognizer, R.string.core_tools_provider_system_recognizer_desc,
    )
    val WHISTLE = ProviderType(
        "whistle", VOICE, R.string.core_tools_provider_whistle, R.string.core_tools_provider_whistle_desc,
        onDevice = true,
    )
    val WHISPER = ProviderType(
        "whisper", VOICE, R.string.core_tools_provider_whisper, R.string.core_tools_provider_whisper_desc,
        onDevice = true, freeSoftware = true,
    )
    val ASR4ALL = ProviderType(
        "asr4all", VOICE, R.string.core_tools_provider_asr4all, R.string.core_tools_provider_asr4all_desc,
        onDevice = true,
    )
    val PARAKEET = ProviderType(
        "parakeet", VOICE, R.string.core_tools_provider_parakeet, R.string.core_tools_provider_parakeet_desc,
        onDevice = true, freeSoftware = true,
    )
    val MOONSHINE = ProviderType(
        "moonshine", VOICE, R.string.core_tools_provider_moonshine, R.string.core_tools_provider_moonshine_desc,
        onDevice = true, freeSoftware = true,
    )
    val SHERPA = ProviderType(
        "sherpa", VOICE, R.string.core_tools_provider_sherpa, R.string.core_tools_provider_sherpa_desc,
        onDevice = true, freeSoftware = true,
    )
    val OMNILINGUAL = ProviderType(
        "omnilingual", VOICE, R.string.core_tools_provider_omnilingual, R.string.core_tools_provider_omnilingual_desc,
        onDevice = true, freeSoftware = true,
    )

    /** Anything that speaks OpenAI's `/audio/transcriptions`: speaches, whisper.cpp's server, Groq. */
    val TRANSCRIPTION_SERVER = ProviderType(
        "transcription_server", VOICE, R.string.core_tools_provider_transcription_server, R.string.core_tools_provider_transcription_server_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true, path = true), multiInstance = true, selfHosted = true,
    )

    // ---- Grammar.

    private val GRAMMAR = setOf(ProviderTool.GRAMMAR)

    /** The bundled engine: English only, and nothing leaves the phone. */
    val HARPER = ProviderType(
        "harper", GRAMMAR, R.string.core_tools_provider_harper, R.string.core_tools_provider_harper_desc,
        onDevice = true, freeSoftware = true,
    )

    /**
     * A LanguageTool server: the public API when the address is blank, a
     * self-hosted one (LanguageTool is LGPL and ships a server) when it is
     * not. A username and key together are a Premium account. Free software,
     * but not [selfHosted]: most people use the public one, and it reads
     * the text.
     */
    val LANGUAGETOOL = ProviderType(
        "languagetool", GRAMMAR, R.string.core_tools_provider_languagetool, R.string.core_tools_provider_languagetool_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.OPTIONAL, username = true),
        multiInstance = true, freeSoftware = true,
    )

    // ---- The sticker generator. Online kinds send the prompt (in English) to the service.

    private val IMAGES = setOf(ProviderTool.IMAGE_GEN)

    /** The models the generator downloads or imports, run on this phone's CPU or NPU. */
    val ON_DEVICE_IMAGE = ProviderType(
        "on_device_image", IMAGES, R.string.core_tools_provider_on_device_image, R.string.core_tools_provider_on_device_image_desc,
        onDevice = true,
    )

    /**
     * Pollinations: no key at all on its old host, which picks the model
     * itself; a key from enter.pollinations.ai opens its newer API and every
     * model it lists. The service is open source (MIT).
     */
    val POLLINATIONS = ProviderType(
        "pollinations", IMAGES, R.string.core_tools_provider_pollinations, R.string.core_tools_provider_pollinations_desc,
        CredentialSpec(key = Need.OPTIONAL, model = true), freeSoftware = true,
    )

    /** AI Horde: volunteers' graphics cards, queued. Anonymous without a key, ahead in the queue with a free one. */
    val AI_HORDE = ProviderType(
        "ai_horde", IMAGES, R.string.core_tools_provider_ai_horde, R.string.core_tools_provider_ai_horde_desc,
        CredentialSpec(key = Need.OPTIONAL, model = true), freeSoftware = true,
    )

    /** OpenAI's GPT Image models, which can draw on a transparent background. Borrows the AI tool's OpenAI key. */
    val OPENAI_IMAGE = ProviderType(
        "openai_image", IMAGES, R.string.core_tools_provider_openai_image, R.string.core_tools_provider_openai_image_desc,
        CredentialSpec(key = Need.OPTIONAL, model = true), keyFrom = "openai",
    )

    /** Gemini's image models (Nano Banana). Borrows the AI tool's Gemini key. */
    val GEMINI_IMAGE = ProviderType(
        "gemini_image", IMAGES, R.string.core_tools_provider_gemini_image, R.string.core_tools_provider_gemini_image_desc,
        CredentialSpec(key = Need.OPTIONAL, model = true), keyFrom = "gemini",
    )

    /** xAI's Grok Imagine. Borrows the AI tool's xAI key. */
    val XAI_IMAGE = ProviderType(
        "xai_image", IMAGES, R.string.core_tools_provider_xai_image, R.string.core_tools_provider_xai_image_desc,
        CredentialSpec(key = Need.OPTIONAL, model = true), keyFrom = "xai",
    )
    val STABILITY = ProviderType(
        "stability", IMAGES, R.string.core_tools_provider_stability, R.string.core_tools_provider_stability_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val BFL = ProviderType(
        "bfl", IMAGES, R.string.core_tools_provider_bfl, R.string.core_tools_provider_bfl_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val RECRAFT = ProviderType(
        "recraft", IMAGES, R.string.core_tools_provider_recraft, R.string.core_tools_provider_recraft_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )

    /* Hosts of many models: the model field names any text-to-image model they run. */
    val REPLICATE = ProviderType(
        "replicate", IMAGES, R.string.core_tools_provider_replicate, R.string.core_tools_provider_replicate_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val FAL = ProviderType(
        "fal", IMAGES, R.string.core_tools_provider_fal, R.string.core_tools_provider_fal_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val TOGETHER = ProviderType(
        "together", IMAGES, R.string.core_tools_provider_together, R.string.core_tools_provider_together_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )
    val HUGGING_FACE = ProviderType(
        "hugging_face", IMAGES, R.string.core_tools_provider_hugging_face, R.string.core_tools_provider_hugging_face_desc,
        CredentialSpec(key = Need.REQUIRED, model = true),
    )

    /** Workers AI: an API token as the key, the account id beside it. */
    val CLOUDFLARE_AI = ProviderType(
        "cloudflare_ai", IMAGES, R.string.core_tools_provider_cloudflare_ai, R.string.core_tools_provider_cloudflare_ai_desc,
        CredentialSpec(key = Need.REQUIRED, model = true, account = true),
    )

    /**
     * Automatic1111's web UI started with `--api`, and the forks that keep its
     * API: Forge, reForge, SD.Next. A key of `user:password` is its
     * `--api-auth`; any other key goes as a bearer token, for a proxy.
     */
    val AUTOMATIC1111 = ProviderType(
        "automatic1111", IMAGES, R.string.core_tools_provider_automatic1111, R.string.core_tools_provider_automatic1111_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true),
        multiInstance = true, freeSoftware = true, selfHosted = true,
    )
    val COMFYUI = ProviderType(
        "comfyui", IMAGES, R.string.core_tools_provider_comfyui, R.string.core_tools_provider_comfyui_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true),
        multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /** Anything with OpenAI's `/images/generations`: LocalAI, DeepInfra, Nebius, a gateway at home. */
    val OPENAI_COMPATIBLE_IMAGE = ProviderType(
        "openai_compatible_image", IMAGES,
        R.string.core_tools_provider_openai_compatible_image, R.string.core_tools_provider_openai_compatible_image_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED, model = true, modelRequired = true), multiInstance = true,
    )

    // ---- Music ID: the recognisers a clip from the microphone is asked of.

    private val MUSIC = setOf(ProviderTool.MUSIC)

    /** AudD's recognition API: a token from its dashboard, a few hundred requests free, then paid. */
    val AUDD = ProviderType(
        "audd", MUSIC, R.string.core_tools_provider_audd, R.string.core_tools_provider_audd_desc,
        CredentialSpec(key = Need.REQUIRED),
    )

    /** ACRCloud: the project's host as the address, its access key as the key, its access secret beside it. */
    val ACRCLOUD = ProviderType(
        "acrcloud", MUSIC, R.string.core_tools_provider_acrcloud, R.string.core_tools_provider_acrcloud_desc,
        CredentialSpec(key = Need.REQUIRED, url = Need.REQUIRED, secret = true),
    )

    /** AudioTag.info: a personal key, a handful of recognitions a day free. */
    val AUDIOTAG = ProviderType(
        "audiotag", MUSIC, R.string.core_tools_provider_audiotag, R.string.core_tools_provider_audiotag_desc,
        CredentialSpec(key = Need.REQUIRED),
    )

    /**
     * Shazam through an unofficial RapidAPI listing: a RapidAPI key. Listed
     * off, and said plainly to be unofficial: Apple offers no API an open
     * source keyboard could ship, and the listing can change under it.
     */
    val RAPID_SHAZAM = ProviderType(
        "rapid_shazam", MUSIC, R.string.core_tools_provider_rapid_shazam, R.string.core_tools_provider_rapid_shazam_desc,
        CredentialSpec(key = Need.REQUIRED),
    )

    /**
     * A server of the user's own that speaks AudD's request and answer: a
     * multipart `file` and the same JSON back. What a self-hosted
     * fingerprinter (Panako, Olaf, audfprint) sits behind.
     */
    val MUSIC_SERVER = ProviderType(
        "music_server", MUSIC, R.string.core_tools_provider_music_server, R.string.core_tools_provider_music_server_desc,
        CredentialSpec(key = Need.OPTIONAL, url = Need.REQUIRED), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    // ---- The speed test.

    private val SPEED = setOf(ProviderTool.SPEED_TEST)

    /** speed.cloudflare.com: no key, no account, and it names the network and the data centre that answered. */
    val CLOUDFLARE_SPEED = ProviderType(
        "cloudflare_speed", SPEED, R.string.core_tools_provider_cloudflare_speed, R.string.core_tools_provider_cloudflare_speed_desc,
    )

    /**
     * A LibreSpeed backend (free software, GPL): one the user runs, or one of
     * the volunteers' from the public list, which the row's own screen picks
     * from and writes onto the credential (`LibreSpeedServers` keys in `extra`).
     */
    val LIBRESPEED = ProviderType(
        "librespeed", SPEED, R.string.core_tools_provider_librespeed, R.string.core_tools_provider_librespeed_desc,
        CredentialSpec(url = Need.REQUIRED), multiInstance = true, freeSoftware = true, selfHosted = true,
    )

    /** Voice type id → the engine id dictation stores and runs. */
    val VOICE_ENGINES: Map<String, String> by lazy {
        mapOf(
            SYSTEM_RECOGNIZER.id to ENGINE_SYSTEM,
            WHISTLE.id to "whistle",
            WHISPER.id to "whisper",
            ASR4ALL.id to "asr4all",
            PARAKEET.id to "parakeet",
            MOONSHINE.id to "moonshine",
            SHERPA.id to "sherpa",
            OMNILINGUAL.id to "omnilingual",
            TRANSCRIPTION_SERVER.id to ENGINE_SERVER,
        )
    }

    const val ENGINE_SYSTEM = "system"
    const val ENGINE_SERVER = "server"

    /** The voice type that runs [engine], or null for an engine id this build does not know. */
    fun voiceType(engine: String): ProviderType? =
        VOICE_ENGINES.entries.firstOrNull { it.value == engine }?.key?.let(::of)

    val all: List<ProviderType> = listOf(
        KLIPY, GIPHY, COMMONS, GIFCITIES, OFFLINE_GIFS, STICKER_PACKS,
        ML_KIT, BERGAMOT, NLLB, TRANSLATE_SERVER, DEEPL, LIBRETRANSLATE, MOZHI, SIMPLYTRANSLATE, LINGVA, TRANSLITE, GOOGLE_TRANSLATE,
        VOCAB_PACKS, WIKTIONARY, WIKTIONARY_REST, DICTIONARY_API, OFFLINE_WIKTIONARY, OFFLINE_WORDNET, OFFLINE_BENGALI,
        IMPORTED_DICTIONARIES,
        DATAMUSE, SIMILAR_WORDS,
        SEARXNG, TAVILY, BRAVE,
        UNSPLASH, PEXELS,
        ER_API, FRANKFURTER, ALLRATESTODAY, COINBASE, CURRENCY_API, COINGECKO,
        HOME_ASSISTANT, GOOGLE_HOME, SMARTTHINGS, HUE, OPENHAB, TUYA, SWITCHBOT,
        VISION_OCR, RENS,
        ANTHROPIC, OPENAI, GEMINI, XAI, DEEPSEEK, BRAVE_ANSWERS, OLLAMA, LM_STUDIO, OPENAI_COMPATIBLE, ON_DEVICE_AI,
        SYSTEM_RECOGNIZER, WHISTLE, WHISPER, ASR4ALL, PARAKEET, MOONSHINE, SHERPA, OMNILINGUAL, TRANSCRIPTION_SERVER,
        HARPER, LANGUAGETOOL,
        ON_DEVICE_IMAGE, POLLINATIONS, AI_HORDE, OPENAI_IMAGE, GEMINI_IMAGE, XAI_IMAGE, STABILITY, BFL, RECRAFT,
        REPLICATE, FAL, TOGETHER, HUGGING_FACE, CLOUDFLARE_AI, AUTOMATIC1111, COMFYUI, OPENAI_COMPATIBLE_IMAGE,
        AUDD, ACRCLOUD, AUDIOTAG, RAPID_SHAZAM, MUSIC_SERVER,
        CLOUDFLARE_SPEED, LIBRESPEED,
    )

    private val byId: Map<String, ProviderType> = all.associateBy { it.id }

    fun of(id: String): ProviderType? = byId[id]

    fun serving(tool: ProviderTool): List<ProviderType> = all.filter { tool in it.serves }
}

/**
 * What one provider was set up with: a key, an address, a model. Shared by
 * every tool that lists the provider. A single-instance type's first
 * credential has the type's own id, which is what the callers that want "the"
 * DeepL key read; a second one of it, and every [ProviderType.multiInstance]
 * one, gets a fresh id ([newId]). [name] is the nickname that tells two apart.
 *
 * A blank [apiKey] on a type with a built-in key means "use the build's key",
 * on the type's own credential only: a second instance exists to bring a key.
 *
 * [extra] holds what the four fields cannot: a sign-in's refresh token and
 * expiry, a second address, a bridge id. Its keys are the type's business
 * (`HomeKeys` for the smart home kinds); a reader never sees a key it does
 * not know, and an older build reads a credential without the field as empty.
 */
@Serializable
data class ProviderCredential(
    val id: String,
    val typeId: String,
    val name: String = "",
    val apiKey: String = "",
    val url: String = "",
    val model: String = "",
    val extra: Map<String, String> = emptyMap(),
) {
    val type: ProviderType? get() = ProviderTypes.of(typeId)

    companion object {
        private val random = SecureRandom()

        /** A fresh id for one more server of a multi-instance type. */
        fun newId(typeId: String): String {
            val bytes = ByteArray(4).also(random::nextBytes)
            return typeId + ":" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * One row of a tool's list. [credentialId] is null for a type with nothing to
 * set up and for a single-instance type, whose credential is found by its
 * type id; [key] is what tells two rows of one list apart.
 */
@Serializable
data class ProviderEntry(
    val typeId: String,
    val credentialId: String? = null,
    val enabled: Boolean = true,
) {
    val key: String get() = credentialId ?: typeId
    val type: ProviderType? get() = ProviderTypes.of(typeId)

    /** The credential this row reads: its own, or its single-instance type's. */
    val credentialKey: String get() = credentialId ?: typeId

    /** Reads the type's own credential, the one a build's key can stand in for. */
    val primary: Boolean get() = credentialKey == typeId
}

/** A tool's list: the providers in the order they are asked, and how they are used. */
@Serializable
data class ProviderChain(
    val entries: List<ProviderEntry> = emptyList(),
    val mode: ChainMode = ChainMode.FALLBACK,
    /**
     * The type ids this tool could list when the list was saved. A type a
     * later build adds is not among them, which is how [withNewTypes] tells
     * a source that is new from one the user removed.
     */
    val known: List<String> = emptyList(),
    /**
     * Two rows of one kind: kept apart, or one the other's fallback. Only
     * read outside [ChainMode.FALLBACK]. Null until the user picks; see [sameKindInForce].
     */
    val sameKind: SameKind? = null,
) {
    fun has(key: String): Boolean = entries.any { it.key == key }

    /** Some kind is on the list more than once, which is when [sameKind] matters. */
    val repeatsKind: Boolean get() = entries.groupBy { it.typeId }.any { it.value.size > 1 }

    /**
     * [sameKind], or the default for what is repeated: two servers of a
     * server kind (two wikis, two SearXNG instances) were always separate
     * and stay so, while a second key for one service is there as a backup.
     */
    val sameKindInForce: SameKind
        get() = sameKind ?: if (
            entries.groupBy { it.typeId }.any { (typeId, rows) -> rows.size > 1 && ProviderTypes.of(typeId)?.multiInstance == false }
        ) {
            SameKind.FALLBACK
        } else {
            SameKind.SEPARATE
        }

    fun without(key: String): ProviderChain = copy(entries = entries.filterNot { it.key == key })

    fun plus(entry: ProviderEntry): ProviderChain =
        if (has(entry.key)) this else copy(entries = entries + entry)

    fun toggled(key: String, on: Boolean): ProviderChain =
        copy(entries = entries.map { if (it.key == key) it.copy(enabled = on) else it })

    /** Stamped on saving: every type [tool] can list in this build. */
    fun stamped(tool: ProviderTool): ProviderChain = copy(known = ProviderTypes.serving(tool).map { it.id })

    /**
     * The word sources (dictionary, synonyms, vocabulary) a later build added
     * join the end of a saved list, switched on, as they did before the lists
     * were provider lists: an update adds a backup rather than hiding it.
     * Only kinds that need nothing set up, and never one the user removed.
     */
    fun withNewTypes(tool: ProviderTool): ProviderChain {
        if (known.isEmpty()) return this
        if (tool == ProviderTool.TRANSLATE) return withNewDeviceEngines()
        if (tool !in APPENDING) return this
        val added = ProviderTypes.serving(tool)
            .filter { it.id !in known && it.credential.none && !it.multiInstance }
            .filter { type -> entries.none { it.typeId == type.id } }
        return if (added.isEmpty()) this else copy(entries = entries + added.map { ProviderEntry(it.id) })
    }

    /**
     * A translate list saved before an on-device engine existed gains it
     * right after the engines it already has (first, with none), switched
     * on: the engine switch keeps it away from an Online translation, and
     * at the end of the list Automatic would never reach it. Text sent to
     * the phone goes nowhere, so this cannot break a "my server only" list.
     */
    private fun withNewDeviceEngines(): ProviderChain {
        val added = ProviderTypes.serving(ProviderTool.TRANSLATE)
            .filter { it.onDevice && it.id !in known }
            .filter { type -> entries.none { it.typeId == type.id } }
        if (added.isEmpty()) return this
        val at = entries.indexOfLast { ProviderTypes.of(it.typeId)?.onDevice == true } + 1
        return copy(entries = entries.take(at) + added.map { ProviderEntry(it.id) } + entries.drop(at))
    }

    // Not private: the serialization plugin hangs `serializer()` on the
    // companion, and ProviderCodec has to reach it.
    companion object {
        private val APPENDING = setOf(ProviderTool.DICTIONARY, ProviderTool.SYNONYMS, ProviderTool.VOCAB_AUTOFILL)
    }
}

/**
 * The two preference strings. A type id this build does not know is kept on
 * reading, not dropped: a downgrade and an upgrade again should not lose it.
 * Resolution skips it instead ([ProviderResolution]).
 */
object ProviderCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    private val chainsSerializer = MapSerializer(String.serializer(), ProviderChain.serializer())
    private val credentialsSerializer = ListSerializer(ProviderCredential.serializer())

    fun encodeCredentials(credentials: List<ProviderCredential>): String =
        json.encodeToString(credentialsSerializer, credentials)

    /** Null for nothing stored or a value that cannot be read, so the caller falls back to the old settings. */
    fun decodeCredentials(value: String?): List<ProviderCredential>? {
        if (value.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(credentialsSerializer, value) }.getOrNull()
    }

    fun encodeChains(chains: Map<ProviderTool, ProviderChain>): String =
        json.encodeToString(chainsSerializer, chains.mapKeys { it.key.id })

    /** A tool id this build does not know is dropped; there is nothing to show it in. */
    fun decodeChains(value: String?): Map<ProviderTool, ProviderChain> {
        if (value.isNullOrBlank()) return emptyMap()
        val raw = runCatching { json.decodeFromString(chainsSerializer, value) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<ProviderTool, ProviderChain>()
        for ((id, chain) in raw) {
            val tool = ProviderTool.of(id) ?: continue
            // A mode this tool does not offer (written by a later build) falls back to its default.
            out[tool] = if (chain.mode in tool.modes) chain else chain.copy(mode = tool.modes.first())
        }
        return out
    }
}
