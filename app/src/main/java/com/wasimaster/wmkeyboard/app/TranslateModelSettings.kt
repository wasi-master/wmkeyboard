package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.translate.BergamotCatalog
import com.wasimaster.wmkeyboard.core.translate.BergamotLanguages
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.NllbTranslator
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateLanguages
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import com.wasimaster.wmkeyboard.core.translate.downloadNotified
import kotlinx.coroutines.launch

/**
 * The on-device translation models: what is on the phone, what is coming, and
 * a button for the rest.
 *
 * Two groups rather than one list of sixty. The first holds what this user is
 * likely to want: every model already here or on its way, the language they
 * translate into, and the languages they type in. The second is a fold with
 * everything else, because the language somebody translates *from* is, often
 * enough, exactly one they cannot type.
 *
 * State comes from [OnDeviceTranslator.models], which the keyboard reads too,
 * so a download started in the panel shows as running here and the other way
 * round. Nothing on this screen owns a download; leaving it cancels nothing.
 */
@Composable
internal fun TranslateModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by OnDeviceTranslator.models.collectAsState()
    val module by OnDeviceTranslator.moduleState.collectAsState()
    // Keyed on the module: on Play the first refresh finds no engine to ask,
    // and the one that matters is the one after it arrives.
    LaunchedEffect(module) { OnDeviceTranslator.refresh(context.applicationContext) }

    val downloadDecision = rememberDownloadDecision(settings)
    var confirmMetered by remember { mutableStateOf<String?>(null) }
    var blockedMetered by remember { mutableStateOf(false) }

    // A Play install carries the engine as an on-demand module. Until it is
    // here there are no models to list, only the one thing to fetch first.
    if (module != TranslateModuleState.Installed) {
        TranslateModuleBanner(
            module = module,
            onDownload = {
                when (downloadDecision()) {
                    MeteredDecision.ALLOWED -> OnDeviceTranslator.requestModule()
                    MeteredDecision.ASK -> confirmMetered = MODULE_CONFIRM
                    MeteredDecision.BLOCKED -> blockedMetered = true
                }
            },
        )
        if (confirmMetered == MODULE_CONFIRM) {
            MeteredDownloadDialog(
                detail = stringResource(R.string.tooldetail_translate_module_metered_body),
                onConfirm = {
                    confirmMetered = null
                    OnDeviceTranslator.requestModule()
                },
                onDismiss = { confirmMetered = null },
            )
        }
        if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
        return
    }
    val offerPermission = rememberNotificationPermissionOffer()
    val start: (String) -> Unit = { code ->
        offerPermission()
        OnDeviceTranslator.downloadNotified(
            context = context.applicationContext,
            code = code,
            title = context.getString(
                CommonR.string.common_notify_download_translate,
                TranslateClient.languageName(code),
            ),
        )
    }

    // Picker order, so the two groups read the way every other language list
    // in the tool does. The picker's zh-CN is the model's zh.
    val all = remember {
        TranslateClient.languages.mapNotNull { (code, name) ->
            OfflineTranslateLanguages.modelCode(code)?.let { it to name }
        }.distinctBy { it.first }
    }
    val wanted = settings.watch { s ->
        (listOf(s.translateTargetLang) + s.enabledLanguages.map { it.id })
            .mapNotNull { OfflineTranslateLanguages.modelCode(it) }
            .toSet()
    }
    val (near, far) = all.partition { (code, _) ->
        code in wanted || code == OfflineTranslateLanguages.PIVOT ||
            models[code].let { it is OfflineModelState.Downloaded || it is OfflineModelState.Downloading }
    }

    val row: @Composable (String, String) -> Unit = { code, name ->
        TranslateModelRow(
            code = code,
            name = name,
            state = models[code],
            onDownload = {
                when (downloadDecision()) {
                    MeteredDecision.ALLOWED -> start(code)
                    MeteredDecision.ASK -> confirmMetered = code
                    MeteredDecision.BLOCKED -> blockedMetered = true
                }
            },
            onCancel = { OnDeviceTranslator.cancelDownload(context.applicationContext, code) },
            onDelete = { scope.launch { OnDeviceTranslator.delete(context.applicationContext, code) } },
        )
    }
    SettingsGroup(
        stringResource(R.string.tooldetail_translate_models_group),
        info = stringResource(R.string.tooldetail_translate_models_info),
    ) {
        for ((code, name) in near) item { row(code, name) }
    }
    if (far.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.tooldetail_translate_models_more_group),
            foldKey = "translate_models_more",
        ) {
            for ((code, name) in far) item { row(code, name) }
        }
    }

    confirmMetered?.let { code ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.tooldetail_translate_model_metered_body,
                TranslateClient.languageName(code),
                Formatter.formatShortFileSize(context, OfflineTranslateLanguages.APPROX_MODEL_BYTES),
            ),
            onConfirm = {
                confirmMetered = null
                start(code)
            },
            onDismiss = { confirmMetered = null },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

/**
 * Bergamot's models (Mozilla's Firefox Translations): one row per language
 * direction, with what it costs to download, and the engine's own library
 * first where this build fetches it. Grouped like ML Kit's list: what this
 * user is likely to want, then a fold with the rest.
 *
 * State comes from [BergamotTranslator.models], which the keyboard reads too.
 * A direction downloaded here fetches the library on the way if it is not
 * here yet; nothing on this screen owns a download.
 */
@Composable
internal fun BergamotModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by BergamotTranslator.models.collectAsState()
    LaunchedEffect(Unit) { BergamotTranslator.refresh(context.applicationContext) }
    val downloadDecision = rememberDownloadDecision(settings)
    var confirmMetered by remember { mutableStateOf<String?>(null) }
    var blockedMetered by remember { mutableStateOf(false) }
    val offerPermission = rememberNotificationPermissionOffer()
    val engineName = stringResource(R.string.tooldetail_translate_bergamot_engine)
    val label: (String) -> String = { key -> OfflineTranslation.label(key, TranslateClient::languageName, engineName) }
    val start: (String) -> Unit = { key ->
        offerPermission()
        OfflineTranslation.downloadNotified(
            context = context.applicationContext,
            key = key,
            title = context.getString(CommonR.string.common_notify_download_translate, label(key)),
        )
    }
    val ask: (String) -> Unit = { key ->
        when (downloadDecision()) {
            MeteredDecision.ALLOWED -> start(key)
            MeteredDecision.ASK -> confirmMetered = key
            MeteredDecision.BLOCKED -> blockedMetered = true
        }
    }
    val wanted = settings.watch { s ->
        (listOf(s.translateTargetLang) + s.enabledLanguages.map { it.id })
            .mapNotNull { BergamotLanguages.modelCode(it) }
            .filter { it != BergamotLanguages.PIVOT }
            .toSet()
    }
    val (near, far) = BergamotLanguages.directions.partition { direction ->
        val key = BergamotTranslator.keyOf(direction)
        direction.from in wanted || direction.to in wanted ||
            models[key].let { it is OfflineModelState.Downloaded || it is OfflineModelState.Downloading }
    }
    val row: @Composable (String) -> Unit = { key ->
        TranslateModelRow(
            code = key,
            name = label(key),
            state = models[key],
            sizeBytes = BergamotTranslator.downloadBytes(key),
            builtIn = false,
            onDownload = { ask(key) },
            onCancel = { OfflineTranslation.cancelDownload(context.applicationContext, key) },
            onDelete = { scope.launch { BergamotTranslator.delete(context.applicationContext, key) } },
        )
    }
    // The files of the directions not here yet, each with where it is published (#1035).
    fun links(directions: List<BergamotCatalog.Direction>): List<OfflineLink> =
        directions.filter { models[BergamotTranslator.keyOf(it)] !is OfflineModelState.Downloaded }.flatMap { direction ->
            direction.files.map { OfflineLink("${label(BergamotTranslator.keyOf(direction))} · ${it.name}", it.url) }
        }
    val onImported: () -> Unit = { scope.launch { BergamotTranslator.refresh(context.applicationContext) } }
    // The offline build's way to the library every direction needs.
    if (offlineBuild) {
        val library by FeatureModules.bergamot.state.collectAsState()
        (library as? ModuleState.Missing)?.consent?.let { NativeConsentBanner(it, "bergamot") }
    }
    SettingsGroup(
        stringResource(R.string.tooldetail_translate_bergamot_group),
        info = stringResource(R.string.tooldetail_translate_bergamot_info),
    ) {
        // The library, on a build that downloads it: the one piece every
        // direction needs. Gone from the list once it is here.
        if (!offlineBuild && models[BergamotTranslator.ENGINE_KEY].let { it != null && it !is OfflineModelState.Downloaded }) {
            item { row(BergamotTranslator.ENGINE_KEY) }
        }
        for (direction in near) item { row(BergamotTranslator.keyOf(direction)) }
        item(level = offlineImportLevel) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_bergamot_subtitle),
                links = links(near),
                onImported = onImported,
            )
        }
    }
    if (far.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.tooldetail_translate_bergamot_more_group),
            foldKey = "translate_bergamot_more",
        ) {
            for (direction in far) item { row(BergamotTranslator.keyOf(direction)) }
            item(level = offlineImportLevel) {
                OfflineImportRow(
                    subtitle = stringResource(R.string.offline_import_bergamot_subtitle),
                    links = links(far),
                    onImported = onImported,
                )
            }
        }
    }
    confirmMetered?.let { key ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.tooldetail_translate_model_metered_body,
                label(key),
                Formatter.formatShortFileSize(context, BergamotTranslator.downloadBytes(key)),
            ),
            onConfirm = {
                confirmMetered = null
                start(key)
            },
            onDismiss = { confirmMetered = null },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

/**
 * The NLLB-200 model: one row for the 630 MB model, and the CTranslate2
 * library first where this build fetches it. The model's licence (CC BY-NC
 * 4.0) is shown before the first download, and agreeing is remembered
 * ([NllbTranslator.licenceAccepted]); the panel asks the same way.
 */
@Composable
internal fun NllbModelManager(settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by NllbTranslator.models.collectAsState()
    LaunchedEffect(Unit) { NllbTranslator.refresh(context.applicationContext) }
    val downloadDecision = rememberDownloadDecision(settings)
    var confirmMetered by remember { mutableStateOf<String?>(null) }
    var blockedMetered by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<String?>(null) }
    val offerPermission = rememberNotificationPermissionOffer()
    val modelName = stringResource(R.string.tooldetail_translate_nllb_model)
    val label: (String) -> String = { key ->
        if (key == NllbTranslator.MODEL_KEY) modelName else OfflineTranslation.NLLB_ENGINE_NAME
    }
    val start: (String) -> Unit = { key ->
        offerPermission()
        NllbTranslator.downloadNotified(
            context = context.applicationContext,
            key = key,
            title = context.getString(CommonR.string.common_notify_download_translate, label(key)),
        )
    }
    val ask: (String) -> Unit = { key ->
        when {
            !NllbTranslator.licenceAccepted -> asking = key
            else -> when (downloadDecision()) {
                MeteredDecision.ALLOWED -> start(key)
                MeteredDecision.ASK -> confirmMetered = key
                MeteredDecision.BLOCKED -> blockedMetered = true
            }
        }
    }
    val row: @Composable (String) -> Unit = { key ->
        TranslateModelRow(
            code = key,
            name = label(key),
            state = models[key],
            sizeBytes = NllbTranslator.downloadBytes(key),
            builtIn = false,
            onDownload = { ask(key) },
            onCancel = { NllbTranslator.cancelDownload(key) },
            onDelete = { scope.launch { NllbTranslator.delete(context.applicationContext) } },
        )
    }
    SettingsGroup(
        stringResource(R.string.tooldetail_translate_nllb_group),
        info = stringResource(R.string.tooldetail_translate_nllb_info),
    ) {
        if (models[NllbTranslator.ENGINE_KEY].let { it != null && it !is OfflineModelState.Downloaded }) {
            item { row(NllbTranslator.ENGINE_KEY) }
        }
        item { row(NllbTranslator.MODEL_KEY) }
    }
    asking?.let { key ->
        NllbLicenceDialog { accepted ->
            asking = null
            if (accepted) ask(key)
        }
    }
    confirmMetered?.let { key ->
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.tooldetail_translate_model_metered_body,
                label(key),
                Formatter.formatShortFileSize(context, NllbTranslator.downloadBytes(key)),
            ),
            onConfirm = {
                confirmMetered = null
                start(key)
            },
            onDismiss = { confirmMetered = null },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

/**
 * NLLB-200's licence, before the phone fetches the model. It comes from a
 * Hugging Face repository under Meta's terms rather than from this project,
 * so the user receives it under them and agrees here. [onDone] is called
 * with whether they accepted.
 */
@Composable
private fun NllbLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.tooldetail_translate_nllb_licence_title)) },
        text = {
            Text(
                stringResource(
                    R.string.tooldetail_translate_nllb_licence_body,
                    Formatter.formatShortFileSize(context, NllbTranslator.modelBytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.NLLB, true)
                onDone(true)
            }) { Text(stringResource(R.string.tooldetail_translate_nllb_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.NLLB]?.let { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) { Text(stringResource(R.string.tooldetail_translate_nllb_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/** Stands in for a model code in the metered confirm, for the module itself. */
private const val MODULE_CONFIRM = "\u0000module"

/**
 * The engine's own download, on a Play install that has not fetched it. Live
 * state with the button that fixes it, which is what a banner is for.
 */
@Composable
private fun TranslateModuleBanner(module: TranslateModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        is TranslateModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.tooldetail_translate_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.tooldetail_translate_module_installing_info)
            },
        )
        is TranslateModuleState.Missing -> StateBanner(
            stringResource(
                if (module.failed) {
                    R.string.tooldetail_translate_module_failed_info
                } else {
                    R.string.tooldetail_translate_module_missing_info
                },
            ),
            action = stringResource(
                if (module.failed) CommonR.string.common_retry else CommonR.string.common_download,
            ),
            tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = onDownload,
        )
        TranslateModuleState.Installed -> Unit
    }
}

@Composable
private fun TranslateModelRow(
    code: String,
    name: String,
    state: OfflineModelState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    sizeBytes: Long = OfflineTranslateLanguages.APPROX_MODEL_BYTES,
    builtIn: Boolean = code == OfflineTranslateLanguages.PIVOT,
) {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    // What the model is in now, read at tap time by the actions below.
    val latest = rememberUpdatedState(state)
    WmRow(
        title = name,
        swipeActions = if (!builtIn && state is OfflineModelState.Downloaded) {
            listOf(
                deleteSwipeAction(stringResource(R.string.privacy_handwriting_delete_desc, name), onDelete),
            )
        } else {
            emptyList()
        },
        subtitle = when {
            // Every model translates through English, so English is part of
            // the engine and not a thing to fetch or remove.
            builtIn -> stringResource(R.string.tooldetail_translate_model_builtin_label)
            state == null -> stringResource(R.string.privacy_handwriting_status_checking)
            state is OfflineModelState.Downloaded ->
                stringResource(R.string.privacy_handwriting_status_downloaded)
            state is OfflineModelState.Downloading -> when {
                state.bytes <= 0L -> stringResource(R.string.privacy_handwriting_status_preparing)
                state.totalBytes > 0L -> stringResource(
                    R.string.privacy_handwriting_status_downloading_of_total,
                    Formatter.formatShortFileSize(context, state.bytes),
                    Formatter.formatShortFileSize(context, state.totalBytes),
                )
                else -> stringResource(
                    R.string.privacy_handwriting_status_downloading,
                    Formatter.formatShortFileSize(context, state.bytes),
                )
            }
            state is OfflineModelState.Missing && state.failed ->
                stringResource(R.string.privacy_handwriting_status_failed)
            else -> stringResource(
                R.string.privacy_handwriting_status_missing_sized,
                Formatter.formatShortFileSize(context, sizeBytes),
            )
        },
        trailing = {
            if (!builtIn) {
                // The row's one control changes with the model: a spinner while
                // it is looked up, then Download, then progress with a cancel,
                // then a bin. Cross-faded from one to the next, keyed on which
                // of those it is so the progress ticking along does not fade.
                // Keyed on the code as well: the groups are not lazy lists, so
                // when a model moves between them this slot can be handed a
                // different language, which must start still rather than
                // cross-fade out of the one that was here.
                key(code) {
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it?.let { shown -> shown::class } },
                        contentAlignment = Alignment.CenterEnd,
                        transitionSpec = { stateSwapTransform(reduceMotion) },
                        label = "translateModelAction",
                    ) { shown ->
                        // From [shown], not [state]: the control on its way
                        // out keeps drawing the state it belonged to. It also
                        // still takes taps while it fades, so each action first
                        // checks the model is still in that state: a quick
                        // second tap on Download must not start it twice.
                        val kind = shown?.let { it::class }
                        fun live(action: () -> Unit): () -> Unit = {
                            if (latest.value?.let { it::class } == kind) action()
                        }
                        TranslateModelAction(shown, name, live(onDownload), live(onCancel), live(onDelete))
                    }
                }
            }
        },
    )
}

/** The control at the end of a [TranslateModelRow], for one [state] of its model. */
@Composable
private fun TranslateModelAction(
    state: OfflineModelState?,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (state) {
        null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        is OfflineModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
            val fraction = state.fraction
            if (fraction != null) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(
                        R.string.tooldetail_translate_model_cancel_desc,
                        name,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is OfflineModelState.Downloaded -> IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.privacy_handwriting_delete_desc, name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is OfflineModelState.Missing -> if (!offlineBuild) TextButton(onClick = onDownload) {
            Text(
                stringResource(
                    if (state.failed) CommonR.string.common_retry else CommonR.string.common_download,
                ),
            )
        }
    }
}
