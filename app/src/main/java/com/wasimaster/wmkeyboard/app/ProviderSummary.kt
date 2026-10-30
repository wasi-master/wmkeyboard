package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.TavilyDepth
import android.content.Context
import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.grammar.GrammarProviders
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.deeplLive
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryCatalog
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryDownloads
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The one line under a provider's name on its tool's list: what that
 * provider is set up with, from its real settings. A voice engine says which
 * model it uses and how big it is, a server says where it is, a service says
 * whether it has a key, and a model still to be downloaded says so.
 *
 * Built here as parts, without a Context, so the rules are plain functions a
 * test can call; the screen puts the parts into words where it draws them.
 */

/** One piece of a summary line. The pieces are joined with a middle dot. */
internal sealed interface SummaryPart {
    /** A phrase, with its arguments. */
    data class Phrase(@StringRes val res: Int, val args: List<Any>) : SummaryPart {
        constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())
    }

    /** A phrase that counts something. */
    data class Counted(@PluralsRes val res: Int, val count: Int) : SummaryPart

    /** Words that are data, not language: a host, a model id, a nickname. */
    data class Literal(val text: String) : SummaryPart

    /** A size on the phone's storage, written the way the phone writes sizes. */
    data class Size(val bytes: Long) : SummaryPart
}

/** A provider row's line: its parts, and whether it reports something that stops the provider working. */
internal data class ProviderSummary(val parts: List<SummaryPart>, val problem: Boolean = false) {

    /** The line in words. */
    fun resolve(context: Context): String = parts.joinToString(context.getString(R.string.providers_tag_separator)) { part ->
        when (part) {
            is SummaryPart.Phrase -> context.getString(part.res, *part.args.toTypedArray())
            is SummaryPart.Counted -> context.resources.getQuantityString(part.res, part.count, part.count)
            is SummaryPart.Literal -> part.text
            is SummaryPart.Size -> Formatter.formatShortFileSize(context, part.bytes)
        }
    }
}

/** [ProviderSummary.resolve] in the language the screen draws in. */
@Composable
internal fun ProviderSummary.text(): String {
    val context = LocalContext.current
    return remember(this, context) { resolve(context) }
}

/** Where a model a provider runs stands on the phone. */
internal sealed interface ModelState {
    data object Ready : ModelState

    /** [percent] is null while the size is not known yet. */
    data class Downloading(val percent: Int?) : ModelState
    data object Paused : ModelState
    data object Missing : ModelState
}

/**
 * What a kind's line needs that is not in the settings: what is on the
 * phone. Gathered by [rememberProviderFacts], from the same download states
 * the kind's own screen shows, so a change there shows here at once.
 */
internal sealed interface ProviderFacts {
    /**
     * The model the provider will use. [name] is null for a kind with one
     * model, whose name says nothing the provider's does not; [sizeBytes] is
     * 0 when it is not known; [others] counts the other models on the phone.
     */
    data class Model(val name: String?, val sizeBytes: Long, val state: ModelState, val others: Int = 0) : ProviderFacts

    /** No model chosen, and none on the phone or on its way. */
    data object NoModel : ProviderFacts

    /** How many of the kind's languages, packs or dictionaries are on the phone. */
    data class Installed(val count: Int, val unit: InstalledUnit) : ProviderFacts
}

/** What [ProviderFacts.Installed] counts, and how a line says it. */
internal enum class InstalledUnit(@PluralsRes val countRes: Int, @StringRes val noneRes: Int) {
    LANGUAGES(R.plurals.providers_summary_languages, R.string.providers_summary_no_languages),
    PACKS(R.plurals.providers_summary_packs, R.string.providers_summary_no_packs),
    DICTIONARIES(R.plurals.providers_summary_dictionaries, R.string.providers_summary_no_dictionaries),
}

/** How much of a key a line shows: enough to tell two apart, too little to use. */
internal const val KEY_TAIL = 4

/** A server's host, which is what tells two servers of one kind apart. */
internal fun hostOf(url: String): String =
    runCatching { java.net.URI(if ("://" in url) url else "https://$url").host }.getOrNull()?.ifBlank { null } ?: url

/**
 * The line under a row on its list. A problem comes first, since the row
 * is skipped until it is fixed. Then what the kind's own settings say, for
 * the kinds that have them ([kindSummary]), else its setup ([setupParts]).
 */
internal fun providerSummary(row: ResolvedProvider, settings: KeyboardSettings, facts: ProviderFacts?): ProviderSummary {
    problemSummary(row)?.let { return it }
    if (!row.entry.enabled) return ProviderSummary(listOf(SummaryPart.Phrase(R.string.providers_status_off)))
    val type = row.type ?: return ProviderSummary(listOf(SummaryPart.Phrase(R.string.providers_status_unknown)), problem = true)
    return kindSummary(row, type, settings, facts) ?: ProviderSummary(setupParts(row, type))
}

/**
 * What a row is set up with, the line the Setup row on its screen shows:
 * the key, the server, the model typed into the setup sheet. A problem
 * with the setup first, as on the list.
 */
internal fun providerSetupSummary(row: ResolvedProvider): ProviderSummary {
    problemSummary(row)?.let { return it }
    if (!row.entry.enabled) return ProviderSummary(listOf(SummaryPart.Phrase(R.string.providers_status_off)))
    val type = row.type ?: return ProviderSummary(listOf(SummaryPart.Phrase(R.string.providers_status_unknown)), problem = true)
    return ProviderSummary(setupParts(row, type))
}

/** What stops the row working, said as what it needs. */
private fun problemSummary(row: ResolvedProvider): ProviderSummary? {
    val res = when (row.problem ?: return null) {
        ProviderProblem.NEEDS_KEY -> R.string.providers_status_needs_key
        ProviderProblem.NEEDS_SIGN_IN -> when (HomeKind.of(row.typeId)) {
            HomeKind.HUE -> R.string.providers_status_needs_pairing
            HomeKind.GOOGLE_HOME -> R.string.providers_status_needs_grant
            else -> R.string.providers_status_needs_sign_in
        }
        ProviderProblem.NEEDS_SERVER -> R.string.providers_status_needs_server
        ProviderProblem.NEEDS_MODEL -> R.string.providers_status_needs_model
        ProviderProblem.NEEDS_PACKS -> R.string.providers_status_needs_packs
        ProviderProblem.UNAVAILABLE -> R.string.providers_status_unavailable
        ProviderProblem.UNKNOWN -> R.string.providers_status_unknown
    }
    return ProviderSummary(listOf(SummaryPart.Phrase(res)), problem = true)
}

/** The line a kind's own settings make; null for a kind with none, whose setup says it all. */
@Suppress("detekt.CyclomaticComplexMethod")
private fun kindSummary(row: ResolvedProvider, type: ProviderType, settings: KeyboardSettings, facts: ProviderFacts?): ProviderSummary? =
    when (type.id) {
        ProviderTypes.SYSTEM_RECOGNIZER.id -> ProviderSummary(
            listOf(
                SummaryPart.Phrase(
                    if (settings.whisper.systemOnDevice) {
                        R.string.providers_summary_system_on_device
                    } else {
                        R.string.providers_summary_system_online
                    },
                ),
            ),
        )
        ProviderTypes.WHISPER.id, ProviderTypes.WHISTLE.id, ProviderTypes.ASR4ALL.id, ProviderTypes.PARAKEET.id,
        ProviderTypes.MOONSHINE.id, ProviderTypes.SHERPA.id, ProviderTypes.OMNILINGUAL.id, ProviderTypes.NLLB.id,
        ProviderTypes.ON_DEVICE_AI.id, ProviderTypes.OFFLINE_WIKTIONARY.id, ProviderTypes.OFFLINE_WORDNET.id,
        ProviderTypes.OFFLINE_BENGALI.id,
        ProviderTypes.ML_KIT.id, ProviderTypes.BERGAMOT.id, ProviderTypes.OFFLINE_GIFS.id,
        ProviderTypes.IMPORTED_DICTIONARIES.id, ProviderTypes.VOCAB_PACKS.id,
        -> facts?.let(::factsSummary)
        // DeepL's one use besides translating, said where it is turned on.
        ProviderTypes.DEEPL.id -> ProviderSummary(
            setupParts(row, type) + listOfNotNull(
                SummaryPart.Phrase(R.string.providers_summary_deepl_write).takeIf { settings.deeplLive.writeActive },
            ),
        )
        ProviderTypes.TAVILY.id -> ProviderSummary(
            setupParts(row, type) + listOfNotNull(
                SummaryPart.Phrase(tavilyDepthLabel(settings.webSearch.tavilyDepth))
                    .takeIf { settings.webSearch.tavilyDepth != TavilyDepth.BASIC },
            ),
        )
        // Harper is English only, so the kind of English is its choice.
        ProviderTypes.HARPER.id -> ProviderSummary(listOf(SummaryPart.Phrase(settings.grammarDialect.labelRes)))
        else -> null
    }

/** The line for what is on the phone. A model the provider cannot run without is a problem while it is missing. */
internal fun factsSummary(facts: ProviderFacts): ProviderSummary = when (facts) {
    ProviderFacts.NoModel -> ProviderSummary(listOf(SummaryPart.Phrase(R.string.providers_summary_no_model)), problem = true)
    is ProviderFacts.Installed -> ProviderSummary(
        listOf(
            if (facts.count > 0) SummaryPart.Counted(facts.unit.countRes, facts.count) else SummaryPart.Phrase(facts.unit.noneRes),
        ),
    )
    is ProviderFacts.Model -> {
        val name = facts.name?.let { SummaryPart.Phrase(R.string.providers_summary_model, it) }
        val state = when (val state = facts.state) {
            ModelState.Ready -> if (facts.sizeBytes > 0) SummaryPart.Size(facts.sizeBytes) else null
            is ModelState.Downloading -> state.percent
                ?.let { SummaryPart.Phrase(R.string.providers_summary_downloading, it) }
                ?: SummaryPart.Phrase(R.string.providers_summary_downloading_wait)
            ModelState.Paused -> SummaryPart.Phrase(R.string.providers_summary_paused)
            ModelState.Missing -> SummaryPart.Phrase(R.string.providers_summary_not_downloaded)
        }
        val more = facts.others.takeIf { it > 0 && facts.state == ModelState.Ready }
            ?.let { SummaryPart.Counted(R.plurals.providers_summary_more_models, it) }
        val ready = SummaryPart.Phrase(R.string.providers_summary_model_ready).takeIf { facts.state == ModelState.Ready }
        val parts = listOfNotNull(name ?: ready, state, more)
        ProviderSummary(parts, problem = facts.state == ModelState.Missing)
    }
}

/**
 * What a row was set up with: its server and the engine or model it asks
 * for there, else its key. An AI service names its model too, since two
 * rows of one service often differ in nothing else.
 */
internal fun setupParts(row: ResolvedProvider, type: ProviderType): List<SummaryPart> {
    HomeKind.of(type.id)?.let {
        // A home reads as what it is called, else where it is.
        val name = row.credential?.extra?.get("instance_name")?.takeIf { it.isNotBlank() }
            ?: row.url.takeIf { it.isNotBlank() }?.let(::hostOf)
        return listOf(name?.let { SummaryPart.Literal(it) } ?: SummaryPart.Phrase(R.string.providers_status_signed_in))
    }
    // Said plainly: this one sends what you write away.
    if (type === ProviderTypes.LANGUAGETOOL) {
        val host = hostOf(runCatching { GrammarProviders.serverOf(row).base }.getOrDefault(row.url))
        return listOf(SummaryPart.Phrase(R.string.providers_status_sends_text, host))
    }
    val namesModel = type.credential.model && type.credential.engines.isEmpty() &&
        (ProviderTool.AI in type.serves || ProviderTool.VOICE in type.serves)
    val model = when {
        !namesModel -> null
        row.model.isNotBlank() -> SummaryPart.Phrase(R.string.providers_summary_model, row.model.trim())
        // A service picks its own model when none is named; a server runs the one it has.
        row.url.isBlank() -> SummaryPart.Phrase(R.string.providers_summary_default_model)
        else -> null
    }
    if (row.url.isNotBlank()) {
        // A front end's engine beside its host: two Mozhi rows may differ in nothing else.
        val engine = type.credential.engine(row.model)?.let { SummaryPart.Literal(it.label) }
        return listOfNotNull(SummaryPart.Phrase(R.string.providers_summary_server, hostOf(row.url)), engine, model)
    }
    return listOfNotNull(model, keyPart(row, type))
}

/** Whether the row has a key, and whose. */
private fun keyPart(row: ResolvedProvider, type: ProviderType): SummaryPart = when {
    row.usesBuiltInKey -> SummaryPart.Phrase(R.string.providers_summary_key_builtin)
    row.apiKey.length > KEY_TAIL * 2 -> SummaryPart.Phrase(R.string.providers_summary_key_ending, row.apiKey.takeLast(KEY_TAIL))
    row.apiKey.isNotBlank() -> SummaryPart.Phrase(R.string.providers_summary_key_set)
    type.onDevice -> SummaryPart.Phrase(R.string.providers_summary_on_device)
    type.credential.key == Need.NONE -> SummaryPart.Phrase(R.string.providers_summary_no_key_needed)
    else -> SummaryPart.Phrase(R.string.providers_summary_no_key)
}

// ---- What is on the phone, for each kind that runs from files.

/** A download's progress as a whole percentage; null while its size is unknown. */
internal fun percentOf(bytes: Long, total: Long): Int? =
    if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else null

/**
 * Whisper: the model chosen as the fallback, else the biggest one on the
 * phone, which is the one it falls back to with none chosen.
 */
internal fun whisperFacts(modelId: String, states: Map<String, WhisperDownloadManager.DownloadStatus>): ProviderFacts {
    fun stateOf(id: String): ModelState = when (val status = states[id]) {
        WhisperDownloadManager.DownloadStatus.Downloaded -> ModelState.Ready
        is WhisperDownloadManager.DownloadStatus.Downloading -> ModelState.Downloading(percentOf(status.bytes, status.total))
        is WhisperDownloadManager.DownloadStatus.Paused -> ModelState.Paused
        else -> ModelState.Missing
    }
    val onPhone = WhisperCatalog.models.filter { stateOf(it.id) == ModelState.Ready }
    val chosen = WhisperCatalog.byId(modelId)
        ?: onPhone.maxByOrNull { it.sizeBytes }
        ?: WhisperCatalog.models.firstOrNull { stateOf(it.id) is ModelState.Downloading || stateOf(it.id) == ModelState.Paused }
        ?: return ProviderFacts.NoModel
    return ProviderFacts.Model(chosen.displayName, chosen.sizeBytes, stateOf(chosen.id), others = onPhone.count { it.id != chosen.id })
}

/**
 * An ExecuTorch or ONNX engine: nothing is chosen, it runs the biggest of
 * its models on the phone, so that one is named.
 */
internal fun etFacts(engine: EtEngine, states: Map<String, EtDownloadManager.Status>): ProviderFacts {
    val models = EtCatalog.forEngine(engine).filterNot { it.punctuation }
    val onPhone = models.filter { states[it.id] == EtDownloadManager.Status.Downloaded }
    onPhone.maxByOrNull { it.millions }?.let { best ->
        return ProviderFacts.Model(best.label, best.bytes, ModelState.Ready, others = onPhone.size - 1)
    }
    models.firstNotNullOfOrNull { model ->
        when (val status = states[model.id]) {
            is EtDownloadManager.Status.Downloading ->
                ProviderFacts.Model(model.label, model.bytes, ModelState.Downloading(percentOf(status.bytes, status.total)))
            is EtDownloadManager.Status.Paused -> ProviderFacts.Model(model.label, model.bytes, ModelState.Paused)
            else -> null
        }
    }?.let { return it }
    return ProviderFacts.NoModel
}

/** Whistle's one model. */
internal fun whistleFacts(status: WhistleModelDownloadManager.Status, sizeBytes: Long): ProviderFacts = when (status) {
    WhistleModelDownloadManager.Status.Downloaded -> ProviderFacts.Model(null, sizeBytes, ModelState.Ready)
    is WhistleModelDownloadManager.Status.Downloading ->
        ProviderFacts.Model(null, sizeBytes, ModelState.Downloading(percentOf(status.bytes, status.total)))
    is WhistleModelDownloadManager.Status.Paused -> ProviderFacts.Model(null, sizeBytes, ModelState.Paused)
    else -> ProviderFacts.NoModel
}

/**
 * The on-device AI: the model chosen, else the only one on the phone. A
 * model the user brought ([LocalLlmStore.CUSTOM_PREFIX]) is a file that is
 * there by definition, and is named by it.
 */
internal fun localLlmFacts(modelId: String, states: Map<String, LocalLlmDownloadManager.DownloadStatus>): ProviderFacts {
    if (modelId.startsWith(LocalLlmStore.CUSTOM_PREFIX)) {
        return ProviderFacts.Model(modelId.removePrefix(LocalLlmStore.CUSTOM_PREFIX), 0, ModelState.Ready)
    }
    fun stateOf(id: String): ModelState = when (val status = states[id]) {
        LocalLlmDownloadManager.DownloadStatus.Downloaded -> ModelState.Ready
        is LocalLlmDownloadManager.DownloadStatus.Downloading -> ModelState.Downloading(percentOf(status.bytes, status.total))
        is LocalLlmDownloadManager.DownloadStatus.Paused -> ModelState.Paused
        else -> ModelState.Missing
    }
    val chosen = LocalLlmCatalog.byId(modelId)
        ?: LocalLlmCatalog.models.firstOrNull { stateOf(it.id) == ModelState.Ready }
        ?: LocalLlmCatalog.models.firstOrNull { stateOf(it.id) is ModelState.Downloading || stateOf(it.id) == ModelState.Paused }
        ?: return ProviderFacts.NoModel
    return ProviderFacts.Model(chosen.displayName, chosen.sizeBytes, stateOf(chosen.id))
}

/** An offline dictionary: one file, downloaded or not. */
internal fun offlineDictionaryFacts(status: OfflineDictionaryDownloads.Status?): ProviderFacts = when (status) {
    is OfflineDictionaryDownloads.Status.Downloaded -> ProviderFacts.Model(null, status.sizeBytes, ModelState.Ready)
    is OfflineDictionaryDownloads.Status.Downloading ->
        ProviderFacts.Model(null, 0, ModelState.Downloading(percentOf(status.bytes, status.totalBytes)))
    OfflineDictionaryDownloads.Status.Queued -> ProviderFacts.Model(null, 0, ModelState.Downloading(null))
    else -> ProviderFacts.Model(null, 0, ModelState.Missing)
}

/**
 * How many languages an on-device translator has on the phone. English is
 * every engine's pivot and is always there, so it is not counted; a
 * Bergamot model is one direction ("de-en"), and both directions of one
 * language count once.
 */
internal fun translateLanguages(models: Map<String, OfflineModelState>, keyPrefix: String = ""): ProviderFacts.Installed {
    val languages = models.filterValues { it is OfflineModelState.Downloaded }.keys
        .filter { it.startsWith(keyPrefix) }
        .flatMap { it.removePrefix(keyPrefix).split('-') }
        .filter { it.isNotBlank() && it != ENGLISH && it != ENGINE_KEY_SUFFIX }
        .toSet()
    return ProviderFacts.Installed(languages.size, InstalledUnit.LANGUAGES)
}

/** The pivot language every on-device translator keeps. */
private const val ENGLISH = "en"

/** The part of an engine-library key after its prefix (`bergamot:engine`), which is no language. */
private const val ENGINE_KEY_SUFFIX = "engine"

/**
 * What [kinds] have on the phone, by kind id, for the lines of a list.
 * Each kind reads the download state its own screen reads, so a model
 * fetched or deleted there, or one still on its way, shows on the list as
 * it happens. A kind not on the list costs nothing: nothing is read for it.
 */
@Composable
@Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
internal fun rememberProviderFacts(kinds: Set<String>, settings: LiveSettings): Map<String, ProviderFacts> {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val facts = HashMap<String, ProviderFacts>()
    if (ProviderTypes.WHISPER.id in kinds) {
        val states by WhisperDownloadManager.states.collectAsState()
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { WhisperDownloadManager.refresh(filesDir) } }
        facts[ProviderTypes.WHISPER.id] = whisperFacts(settings.watch { it.whisper.modelId }, states)
    }
    if (ProviderTypes.WHISTLE.id in kinds) {
        val status by WhistleModelDownloadManager.status.collectAsState()
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { WhistleModelDownloadManager.refresh(filesDir) } }
        facts[ProviderTypes.WHISTLE.id] = whistleFacts(status, WhistleModelStore.MODEL_BYTES)
    }
    val etKinds = kinds.mapNotNull(EtEngine::byId)
    if (etKinds.isNotEmpty()) {
        val states by EtDownloadManager.states.collectAsState()
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { EtDownloadManager.refresh(filesDir) } }
        for (engine in etKinds) facts[engine.id] = etFacts(engine, states)
    }
    if (ProviderTypes.ON_DEVICE_AI.id in kinds) {
        val states by LocalLlmDownloadManager.states.collectAsState()
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { LocalLlmDownloadManager.refresh(filesDir) } }
        facts[ProviderTypes.ON_DEVICE_AI.id] = localLlmFacts(settings.watch { it.ai.localModelId }, states)
    }
    if (ProviderTypes.ML_KIT.id in kinds && OnDeviceTranslator.offered) {
        val models by OnDeviceTranslator.models.collectAsState()
        val module by OnDeviceTranslator.moduleState.collectAsState()
        LaunchedEffect(module) { OnDeviceTranslator.refresh(context.applicationContext) }
        facts[ProviderTypes.ML_KIT.id] = translateLanguages(models)
    }
    if (ProviderTypes.BERGAMOT.id in kinds && BergamotTranslator.available) {
        val models by BergamotTranslator.models.collectAsState()
        LaunchedEffect(Unit) { BergamotTranslator.refresh(context.applicationContext) }
        facts[ProviderTypes.BERGAMOT.id] = translateLanguages(models, BergamotTranslator.KEY_PREFIX)
    }
    if (ProviderTypes.NLLB.id in kinds && NllbTranslator.available) {
        val models by NllbTranslator.models.collectAsState()
        LaunchedEffect(Unit) { NllbTranslator.refresh(context.applicationContext) }
        facts[ProviderTypes.NLLB.id] = when (val state = models[NllbTranslator.MODEL_KEY]) {
            OfflineModelState.Downloaded -> ProviderFacts.Model(null, 0, ModelState.Ready)
            is OfflineModelState.Downloading ->
                ProviderFacts.Model(null, 0, ModelState.Downloading(state.fraction?.let { (it * 100).toInt() }))
            else -> ProviderFacts.Model(null, 0, ModelState.Missing)
        }
    }
    val dictionaries = listOf(
        ProviderTypes.OFFLINE_WIKTIONARY.id to OfflineDictionaryCatalog.WIKTIONARY,
        ProviderTypes.OFFLINE_WORDNET.id to OfflineDictionaryCatalog.OEWN,
        ProviderTypes.OFFLINE_BENGALI.id to OfflineDictionaryCatalog.BENGALI_WIKTIONARY,
    ).filter { it.first in kinds }
    if (dictionaries.isNotEmpty()) {
        val states by OfflineDictionaryDownloads.states.collectAsState()
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { OfflineDictionaryDownloads.refresh(filesDir) } }
        for ((kind, pack) in dictionaries) facts[kind] = offlineDictionaryFacts(states[pack.id])
    }
    // The rest are counts of files, read off the main thread.
    if (ProviderTypes.OFFLINE_GIFS.id in kinds) {
        val packs by produceState<Int?>(null) {
            value = withContext(Dispatchers.IO) {
                OfflineGifPacks.attach(filesDir)
                OfflineGifPacks.packs().size
            }
        }
        packs?.let { facts[ProviderTypes.OFFLINE_GIFS.id] = ProviderFacts.Installed(it, InstalledUnit.PACKS) }
    }
    if (ProviderTypes.IMPORTED_DICTIONARIES.id in kinds) {
        val version by YomichanDictionaries.changes.collectAsState()
        val installed by produceState<Int?>(null, version) {
            value = withContext(Dispatchers.IO) {
                YomichanDictionaries.attach(filesDir)
                YomichanDictionaries.installed().size
            }
        }
        installed?.let { facts[ProviderTypes.IMPORTED_DICTIONARIES.id] = ProviderFacts.Installed(it, InstalledUnit.DICTIONARIES) }
    }
    if (ProviderTypes.VOCAB_PACKS.id in kinds) {
        // The packs fetched from the catalog; a pack of the user's own words is not one of them.
        val packs by produceState<Int?>(null) {
            value = withContext(Dispatchers.IO) {
                VocabPacks.languages(filesDir).sumOf { lang -> VocabPacks.files(filesDir, lang).count { !VocabPacks.isUserPack(it) } }
            }
        }
        packs?.let { facts[ProviderTypes.VOCAB_PACKS.id] = ProviderFacts.Installed(it, InstalledUnit.PACKS) }
    }
    return facts
}
