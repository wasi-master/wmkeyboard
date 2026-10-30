package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.TavilyDepth
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import com.wasimaster.wmkeyboard.core.tools.SearxClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ToggleOn
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryCatalog
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import kotlinx.coroutines.launch

/*
 * One provider's own screen (PROVIDER_PAGE_ROUTE), opened from its row on a
 * tool's list. Everything the provider has lives here rather than on the
 * tool's page under the list: the switch, the setup (the editor sheet the row
 * used to open), the kind's own settings (a voice engine's models, a GIF
 * pack list, DeepL Write), its server address on the F-Droid build, which
 * other tools share its key, and Remove.
 */

/** The row [key] names on [tool]'s list: by its own key, else the first row of the kind [key] names. */
internal fun KeyboardSettings.providerRow(tool: ProviderTool, env: ProviderEnv, key: String): ResolvedProvider? {
    val rows = resolveProviders(tool, env, all = true)
    return rows.firstOrNull { it.key == key } ?: rows.firstOrNull { it.typeId == providerKindOf(key) }
}

/** The kind a row key names: a second server's key is its kind's id with a suffix ([ProviderCredential.newId]). */
internal fun providerKindOf(key: String): String = key.substringBefore(':')

/** What the screen's heading shows: the row's name, and what the kind is. */
internal data class ProviderPageHead(val title: String, val subtitle: String?, val type: ProviderType?)

@Composable
internal fun providerPageHead(settings: LiveSettings, tool: ProviderTool, key: String): ProviderPageHead {
    val context = LocalContext.current
    val env = rememberProviderEnv(tool)
    val row = settings.watch { it.providerRow(tool, env, key) }
    val type = row?.type ?: ProviderTypes.of(providerKindOf(key))
    val title = row?.let { providerTitle(context, it) } ?: type?.let { stringResource(it.labelRes) } ?: key
    val subtitle = type?.let {
        val tags = providerTags(it)
        val description = stringResource(it.descriptionRes(tool))
        if (tags.isEmpty()) description else description + "\n" + tags
    }
    return ProviderPageHead(title, subtitle, type)
}

/** The tool page a list is drawn on, for the lists that sit on one. */
internal fun providerHostTool(tool: ProviderTool): ToolbarTool? = when (tool) {
    ProviderTool.GIF -> ToolbarTool.GIF
    ProviderTool.STICKER -> ToolbarTool.STICKER
    ProviderTool.TRANSLATE -> ToolbarTool.TRANSLATE
    ProviderTool.DICTIONARY -> ToolbarTool.DICTIONARY
    ProviderTool.VOCAB_AUTOFILL -> ToolbarTool.VOCABULARY
    ProviderTool.SEARCH -> ToolbarTool.WEB_SEARCH
    ProviderTool.CURRENCY_FIAT, ProviderTool.CURRENCY_CRYPTO -> ToolbarTool.CURRENCY
    ProviderTool.SMART_HOME -> ToolbarTool.HOME_ASSISTANT
    ProviderTool.OCR -> ToolbarTool.OCR
    ProviderTool.PHOTO_TRANSLATE -> ToolbarTool.CAMERA
    ProviderTool.AI -> ToolbarTool.AI
    ProviderTool.GRAMMAR -> ToolbarTool.GRAMMAR
    ProviderTool.MUSIC -> ToolbarTool.MUSIC_ID
    ProviderTool.SPEED_TEST -> ToolbarTool.SPEED_TEST
    ProviderTool.SYNONYMS, ProviderTool.PHOTOS, ProviderTool.VOICE, ProviderTool.IMAGE_GEN -> null
}

/** The screen a list is drawn on. */
internal fun providerHostRoute(tool: ProviderTool): String = providerHostTool(tool)?.let(::toolRoute) ?: when (tool) {
    ProviderTool.SYNONYMS -> "typing/suggestions"
    ProviderTool.PHOTOS -> PHOTO_HUB_ROUTE
    ProviderTool.IMAGE_GEN -> STICKER_GENERATOR_ROUTE
    else -> "voice"
}

/** A provider's screen wears the colour of the screen its list is on. */
@Composable
internal fun providerHostAccent(tool: ProviderTool, settings: LiveSettings): Color? {
    val host = providerHostTool(tool) ?: return SettingsRouteColors[providerHostRoute(tool)]
    return toolAccentColor(host, settings.watch { it.toolColorOverrides })
}

/**
 * Where each kind is called, for the F-Droid build's server fields: on the
 * provider's own screen rather than in a group on the tool's page that named
 * every service whether or not it was on the list.
 */
internal val ProviderEndpoints: Map<String, List<ServiceEndpoint>> = mapOf(
    ProviderTypes.KLIPY.id to listOf(ServiceEndpoint.KLIPY),
    ProviderTypes.GIPHY.id to listOf(ServiceEndpoint.GIPHY),
    ProviderTypes.GIFCITIES.id to listOf(ServiceEndpoint.GIFCITIES, ServiceEndpoint.WAYBACK_MACHINE),
    ProviderTypes.GOOGLE_TRANSLATE.id to listOf(ServiceEndpoint.TRANSLATE_GOOGLE, ServiceEndpoint.TRANSLATE_CLOUD),
    ProviderTypes.BRAVE.id to listOf(ServiceEndpoint.BRAVE_SEARCH),
    ProviderTypes.TAVILY.id to listOf(ServiceEndpoint.TAVILY),
    ProviderTypes.UNSPLASH.id to listOf(ServiceEndpoint.UNSPLASH),
    ProviderTypes.PEXELS.id to listOf(ServiceEndpoint.PEXELS),
    ProviderTypes.WIKTIONARY.id to listOf(ServiceEndpoint.KAIKKI),
    ProviderTypes.WIKTIONARY_REST.id to listOf(ServiceEndpoint.WIKTIONARY),
    ProviderTypes.DICTIONARY_API.id to listOf(ServiceEndpoint.DICTIONARY_API),
    ProviderTypes.DATAMUSE.id to listOf(ServiceEndpoint.DATAMUSE),
    ProviderTypes.CURRENCY_API.id to listOf(ServiceEndpoint.CURRENCY_API, ServiceEndpoint.CURRENCY_API_MIRROR),
    ProviderTypes.FRANKFURTER.id to listOf(ServiceEndpoint.FRANKFURTER),
    ProviderTypes.ER_API.id to listOf(ServiceEndpoint.ER_API),
    ProviderTypes.COINBASE.id to listOf(ServiceEndpoint.COINBASE),
    ProviderTypes.COINGECKO.id to listOf(ServiceEndpoint.COINGECKO),
    ProviderTypes.CLOUDFLARE_SPEED.id to listOf(ServiceEndpoint.CLOUDFLARE_SPEED),
    ProviderTypes.LIBRESPEED.id to listOf(ServiceEndpoint.LIBRESPEED_LIST),
    ProviderTypes.ANTHROPIC.id to listOf(ServiceEndpoint.ANTHROPIC),
    ProviderTypes.OPENAI.id to listOf(ServiceEndpoint.OPENAI),
    ProviderTypes.GEMINI.id to listOf(ServiceEndpoint.GEMINI),
    ProviderTypes.XAI.id to listOf(ServiceEndpoint.XAI),
    ProviderTypes.DEEPSEEK.id to listOf(ServiceEndpoint.DEEPSEEK),
    ProviderTypes.BRAVE_ANSWERS.id to listOf(ServiceEndpoint.BRAVE_SEARCH),
)

/** The body of a provider's screen. [key] is a row key or a kind's id; see [providerRow]. */
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun ProviderPage(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ProviderTool,
    key: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val env = rememberProviderEnv(tool)
    val row = settings.watch { it.providerRow(tool, env, key) }
    val type = row?.type ?: ProviderTypes.of(providerKindOf(key))
    var editing by remember { mutableStateOf<ProviderEditTarget?>(null) }
    var removing by remember { mutableStateOf<ResolvedProvider?>(null) }
    fun save(change: ProviderEdits.Change) {
        scope.launch { repository.saveProviders(change) }
    }
    if (type == null) {
        // A kind a newer build added, or a link to one this build lacks.
        StateBanner(stringResource(R.string.providers_status_unknown))
        if (row != null) {
            SettingsGroup {
                item {
                    WmRow(
                        title = stringResource(R.string.providers_remove),
                        icon = Icons.Outlined.Delete,
                        onClick = { removing = row },
                    )
                }
            }
        }
    } else {
        val toolLabel = stringResource(tool.labelRes)
        val hasSetup = !type.credential.none || HomeKind.of(type.id) != null
        fun setUp() {
            editing = ProviderEditTarget(
                type = type,
                credential = row?.credential ?: ProviderCredential(row?.entry?.credentialKey ?: type.id, type.id),
                isNew = row == null,
                row = row,
            )
        }
        when {
            row == null -> StateBanner(
                stringResource(R.string.providers_page_not_listed, toolLabel),
                action = stringResource(R.string.providers_page_add_action),
            ) {
                when (val pick = pickProvider(type, null, settings.value.providerChain(tool, env))) {
                    is ProviderPick.Add -> save(ProviderEdits.addEntry(settings.value, tool, pick.entry, env))
                    is ProviderPick.Edit -> editing = pick.target
                }
            }
            row.entry.enabled && row.problem != null -> StateBanner(
                providerStatus(row).first,
                action = if (hasSetup) stringResource(R.string.providers_page_fix_action) else null,
                tone = BannerTone.WARNING,
                onAction = if (hasSetup) ::setUp else null,
            )
        }
        if (row != null) {
            val setup = if (hasSetup) providerSetup(row) else null
            SettingsGroup {
                item {
                    ToggleSetting(
                        title = stringResource(R.string.providers_page_on_title),
                        subtitle = stringResource(
                            if (row.entry.enabled) R.string.providers_page_on_subtitle else R.string.providers_page_off_subtitle,
                            toolLabel,
                        ),
                        checked = row.entry.enabled,
                        icon = Icons.Outlined.ToggleOn,
                    ) { on ->
                        save(ProviderEdits.setChain(tool, settings.value.providerChain(tool, env).toggled(row.key, on)))
                    }
                }
                if (setup != null) {
                    item {
                        NavRow(
                            title = stringResource(R.string.providers_page_setup_title),
                            subtitle = setup,
                            icon = Icons.Outlined.Key,
                        ) { setUp() }
                    }
                }
            }
        }
        // The kind's own settings: what used to be appended to the tool's page.
        ProviderOwnSettings(repository, settings, tool, type, row, onNavigate)
        ServerFieldsGroup(repository, settings, endpoints = ProviderEndpoints[type.id].orEmpty(), level = SettingLevel.ADVANCED)
        val usedBy = settings.watch { s ->
            row?.let { ProviderEdits.usedBy(s, it.entry.credentialKey, tool, env) }.orEmpty()
        }
        SettingsGroup {
            item(visible = usedBy.isNotEmpty()) {
                WmRow(
                    title = stringResource(R.string.providers_page_used_by_title),
                    subtitle = usedBy.joinToString(", ") { context.getString(it.labelRes) },
                    icon = Icons.Outlined.Link,
                )
            }
            if (row != null) {
                item {
                    WmRow(
                        title = stringResource(R.string.providers_remove),
                        subtitle = stringResource(R.string.providers_page_remove_subtitle, toolLabel),
                        icon = Icons.Outlined.Delete,
                        onClick = { removing = row },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        ProviderEditorSheet(
            repository = repository,
            tool = tool,
            target = target,
            usedBy = ProviderEdits.usedBy(settings.value, target.credential.id, tool, env),
            onSave = { credential ->
                editing = null
                save(ProviderEdits.saveCredential(settings.value, credential, if (target.isNew) tool else null, env))
            },
            onRemove = target.row?.let { listed ->
                {
                    editing = null
                    removing = listed
                }
            },
            onDismiss = { editing = null },
        )
    }
    removing?.let { listed ->
        ProviderRemoveDialog(
            settings = settings,
            tool = tool,
            env = env,
            row = listed,
            onConfirm = { change ->
                removing = null
                save(change)
                onBack()
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * What the row is set up with, said the way its list row says it, whether or
 * not it is on: the setup is the same either way.
 */
@Composable
private fun providerSetup(row: ResolvedProvider): String =
    if (row.type?.credential?.none == true && HomeKind.of(row.typeId) == null) {
        stringResource(R.string.providers_page_setup_none)
    } else {
        providerStatus(row.copy(entry = row.entry.copy(enabled = true))).first
    }

/**
 * The settings a kind has besides its setup, drawn on its own screen. Each
 * kind's branch is the group its tool's page used to carry under the list.
 * A kind with nothing of its own draws nothing.
 */
@Composable
@Suppress("UNUSED_PARAMETER", "detekt.CyclomaticComplexMethod", "detekt.LongMethod")
internal fun ProviderOwnSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ProviderTool,
    type: ProviderType,
    row: ResolvedProvider?,
    onNavigate: (String) -> Unit,
) {
    when (type.id) {
        // ---- Voice typing: what the voice screen used to stack under its dictation rows.
        ProviderTypes.SYSTEM_RECOGNIZER.id -> SystemRecognizerSettings(repository, settings)
        ProviderTypes.WHISPER.id -> if (isWhisperEnabled()) {
            WhisperOfflineSettings(repository, settings)
            WhisperModelManager(repository, settings)
        }
        ProviderTypes.WHISTLE.id -> if (WhistleEngine.shipped) WhistleModelManager(settings)
        ProviderTypes.ASR4ALL.id, ProviderTypes.PARAKEET.id, ProviderTypes.MOONSHINE.id,
        ProviderTypes.SHERPA.id, ProviderTypes.OMNILINGUAL.id,
        -> EtEngine.byId(type.id)?.takeIf { it.runtimeShipped }?.let {
            EtModelManager(it, settings)
            if (it == EtEngine.SHERPA) SherpaCustomModels(settings)
        }
        ProviderTypes.TRANSCRIPTION_SERVER.id -> VoiceServerSettings(repository, settings)
        // ---- Translate: the model lists that sat above the list, and DeepL's own use besides translating.
        ProviderTypes.ML_KIT.id -> if (OnDeviceTranslator.offered) TranslateModelManager(settings)
        ProviderTypes.BERGAMOT.id -> if (BergamotTranslator.available) BergamotModelManager(settings)
        ProviderTypes.NLLB.id -> if (NllbTranslator.available) NllbModelManager(settings)
        ProviderTypes.DEEPL.id -> DeepLWriteSettingsGroup(repository, settings)
        // ---- Media, search and words: groups the tools' pages drew under their lists.
        ProviderTypes.OFFLINE_GIFS.id -> OfflineGifPacksGroup()
        ProviderTypes.TAVILY.id -> TavilySettings(repository, settings)
        ProviderTypes.SEARXNG.id -> SearxSettings(repository, settings, row)
        ProviderTypes.VOCAB_PACKS.id -> SettingsGroup {
            // The packs source is only as good as the packs installed (#665).
            item {
                NavRow(
                    R.string.tooldetail_dictionary_packs_title,
                    stringResource(R.string.tooldetail_dictionary_packs_subtitle),
                    onClick = { onNavigate("vocab/packs") },
                )
            }
        }
        ProviderTypes.OFFLINE_WIKTIONARY.id -> OfflineDictionariesGroup(settings, listOf(OfflineDictionaryCatalog.WIKTIONARY))
        ProviderTypes.OFFLINE_WORDNET.id -> OfflineDictionariesGroup(settings, listOf(OfflineDictionaryCatalog.OEWN))
        ProviderTypes.OFFLINE_BENGALI.id ->
            OfflineDictionariesGroup(settings, listOf(OfflineDictionaryCatalog.BENGALI_WIKTIONARY))
        ProviderTypes.IMPORTED_DICTIONARIES.id -> ImportedDictionariesGroup()
        // ---- The speed test: a LibreSpeed row picks its server from the public list.
        ProviderTypes.LIBRESPEED.id -> LibreSpeedServerSettings(repository, settings, row)
        // ---- AI: the model on the phone, and the headers a service is sent with (#648).
        ProviderTypes.ON_DEVICE_AI.id -> LocalLlmModelManager(repository, settings)
        else -> if (tool == ProviderTool.AI) {
            AiProviders.of(type.id)?.takeIf { it != AiProvider.ON_DEVICE }?.let { AiProviderHeadersGroup(repository, settings, it) }
        }
    }
}

/** A [TavilyDepth]'s name, on its settings row and in the list's summary. */
internal fun tavilyDepthLabel(depth: TavilyDepth): Int = when (depth) {
    TavilyDepth.ULTRA_FAST -> R.string.tooldetail_tavily_depth_ultra_fast
    TavilyDepth.FAST -> R.string.tooldetail_tavily_depth_fast
    TavilyDepth.BASIC -> R.string.tooldetail_tavily_depth_basic
    TavilyDepth.ADVANCED -> R.string.tooldetail_tavily_depth_advanced
}

private fun tavilyDepthDesc(depth: TavilyDepth): Int = when (depth) {
    TavilyDepth.ULTRA_FAST -> R.string.tooldetail_tavily_depth_ultra_fast_desc
    TavilyDepth.FAST -> R.string.tooldetail_tavily_depth_fast_desc
    TavilyDepth.BASIC -> R.string.tooldetail_tavily_depth_basic_desc
    TavilyDepth.ADVANCED -> R.string.tooldetail_tavily_depth_advanced_desc
}

/** Tavily's search depth. */
@Composable
private fun TavilySettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    SettingsGroup(
        stringResource(R.string.tooldetail_tavily_group),
        info = stringResource(R.string.tooldetail_tavily_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ChoiceSetting(
                R.string.tooldetail_tavily_advanced_title,
                subtitle = stringResource(R.string.tooldetail_tavily_advanced_subtitle),
                options = TavilyDepth.entries.map { it to stringResource(tavilyDepthLabel(it)) },
                selected = settings.watch { it.webSearch.tavilyDepth },
                info = stringResource(R.string.tooldetail_tavily_advanced_info),
                default = SettingsDefaults.webSearch.tavilyDepth,
                detail = { depth -> ChoiceDetail(stringResource(tavilyDepthDesc(depth))) },
            ) { scope.launch { repository.setSearchTavilyDepth(it) } }
        }
    }
}

/**
 * Which of a SearXNG instance's categories the search panel offers (#934).
 * The list is the instance's own, from its `/config`; until it answers, or
 * when it cannot, the usual ones stand in. A category chosen earlier stays
 * listed even when the instance no longer names it, so it can be turned off.
 */
@Composable
private fun SearxSettings(repository: SettingsRepository, settings: LiveSettings, row: ResolvedProvider?) {
    val scope = rememberCoroutineScope()
    val instance = row?.url.orEmpty()
    val listed by produceState<List<String>?>(null, instance) {
        value = if (instance.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { runCatching { SearxClient.categories(instance) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
        }
    }
    SettingsGroup(stringResource(R.string.tooldetail_searx_group), level = SettingLevel.STANDARD) {
        item {
            val chosen = settings.watch { it.webSearch.searxCategories }
            val names = ((listed ?: SearxClient.USUAL_CATEGORIES) + chosen)
                .distinct()
                .filter { it != SearxClient.GENERAL && it != SearxClient.IMAGES }
            MultiChoiceSetting(
                R.string.tooldetail_searx_categories_title,
                subtitle = stringResource(
                    if (listed == null) R.string.tooldetail_searx_categories_fallback
                    else R.string.tooldetail_searx_categories_subtitle,
                ),
                info = stringResource(R.string.tooldetail_searx_categories_info),
                options = names.map { it to SearxClient.categoryLabel(it) },
                selected = chosen.toSet(),
                default = emptySet(),
            ) { picked ->
                // Kept in the list's order, which is the order the chips show in.
                scope.launch { repository.setSearchSearxCategories(names.filter { it in picked }) }
            }
        }
    }
}

/** The phone's recognizer: which app's service it is, and whether it may go online. */
@Composable
private fun SystemRecognizerSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picked = settings.watch { it.whisper.systemService }
    SettingsGroup {
        // #993: any app's recognition service, not only the phone's default.
        item(level = SettingLevel.STANDARD) {
            val installed = remember { com.wasimaster.wmkeyboard.core.voice.recognitionServices(context) }
            val defaultLabel = stringResource(R.string.voice_system_service_default)
            val options = listOf("" to defaultLabel) +
                installed.map { (component, label) -> component.flattenToString() to label } +
                // A pick whose app has gone stays listed, so it can be changed back.
                listOfNotNull(
                    picked.takeIf { it.isNotBlank() && installed.none { (c, _) -> c.flattenToString() == it } }
                        ?.let { it to stringResource(R.string.voice_system_service_missing) },
                )
            ChoiceSetting(
                R.string.voice_system_service_title,
                subtitle = stringResource(R.string.voice_system_service_subtitle),
                info = stringResource(R.string.voice_system_service_info),
                options = options,
                selected = picked,
                default = SettingsDefaults.whisper.systemService,
            ) { scope.launch { repository.setVoiceSystemService(it) } }
        }
        item(level = SettingLevel.STANDARD, visible = picked.isBlank()) {
            ToggleSetting(
                R.string.voice_system_on_device_title,
                stringResource(R.string.voice_system_on_device_subtitle),
                settings.watch { it.whisper.systemOnDevice },
                info = stringResource(R.string.voice_system_info) + "\n\n" + stringResource(R.string.voice_system_on_device_info),
                default = SettingsDefaults.whisper.systemOnDevice,
            ) { scope.launch { repository.setVoiceSystemOnDevice(it) } }
        }
    }
}

/** Whisper's translate-to-English switch, above its models. */
@Composable
private fun WhisperOfflineSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.voice_offline_group), level = SettingLevel.ADVANCED) {
        item {
            ToggleSetting(
                R.string.voice_translate_title,
                stringResource(R.string.voice_translate_subtitle),
                settings.watch { it.whisper.translate },
                default = SettingsDefaults.whisper.translate,
            ) { scope.launch { repository.setWhisperTranslate(it) } }
        }
    }
}
