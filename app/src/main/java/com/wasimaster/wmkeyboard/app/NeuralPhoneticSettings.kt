package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.storage.ConfirmDeleteDialog
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitLatinDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitModels
import com.wasimaster.wmkeyboard.core.xlit.XlitDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Neural suggestions on a phonetic language's page: the switch, and under it
 * where IndicXlit's download stands ([NeuralPhoneticModelRow]). Turning the
 * switch on starts the download, through data saving's say like every model
 * download. One model serves every language, so the row reads the same on
 * each of their pages. Beside them, the other direction's model, which
 * romanized dictation spells with ([RomanizedDictationModelRow]).
 */
@Composable
internal fun NeuralPhoneticToggle(
    langId: String,
    languageName: String,
    settings: LiveSettings,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val download = rememberXlitDownload(settings)
    // The size to quote is what a download fetches now, the runtime included
    // the first time, or the pack's own size once it is here.
    val size = IndicXlitDownloads.bytesToFetch(context).takeIf { it > 0 } ?: IndicXlitModels.NATIVE.bytes
    ToggleSetting(
        R.string.languages_neural_phonetic_title,
        stringResource(R.string.languages_neural_phonetic_subtitle, formatBytes(size)),
        on,
        info = stringResource(R.string.languages_neural_phonetic_info, languageName),
        default = SettingsDefaults.suggestionStrip.neuralPhoneticFor(langId),
    ) { checked ->
        if (checked && !IndicXlitModels.NATIVE.isDownloaded(context.noBackupFilesDir)) download()
        onChange(checked)
    }
}

/**
 * Starts the download of one of IndicXlit's packs as data saving allows: at
 * once, after the metered-connection question, or not at all with the reason
 * shown. The dialogs are drawn where this is called.
 */
@Composable
private fun rememberXlitDownload(
    settings: LiveSettings,
    downloads: XlitDownloads = IndicXlitDownloads,
    pack: IndicXlitModels.Pack = IndicXlitModels.NATIVE,
): () -> Unit {
    val context = LocalContext.current
    val decide = rememberDownloadDecision(settings)
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(
                R.string.languages_neural_phonetic_metered_body,
                formatBytes(downloads.bytesToFetch(context).takeIf { it > 0 } ?: pack.bytes),
            ),
            onConfirm = {
                metered = false
                downloads.start(context)
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
    return {
        when (decide()) {
            MeteredDecision.ALLOWED -> downloads.start(context)
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
}

/**
 * Where the neural suggestions model's download stands; a tap fetches it, or
 * fetches it again after a failure, and once it is here offers to delete it
 * (so does a swipe).
 */
@Composable
internal fun NeuralPhoneticModelRow(settings: LiveSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    XlitModelRow(
        settings, IndicXlitDownloads, IndicXlitModels.NATIVE,
        title = R.string.languages_neural_phonetic_model_title,
        deleteTitle = R.string.languages_neural_phonetic_delete_title,
        deleteBody = R.string.languages_neural_phonetic_delete_body,
    ) { deleteNeuralPhonetic(context, repository, settings.value) }
}

/**
 * Romanized dictation's model (#948), IndicXlit the other way round: Indic
 * script back to Latin letters, for the words a Hinglish or Banglish dictation
 * hears that the spelling lists do not know. No switch: dictation uses it
 * whenever it is here, so the row is the download and the delete.
 */
@Composable
internal fun RomanizedDictationModelRow(settings: LiveSettings) {
    val context = LocalContext.current
    XlitModelRow(
        settings, IndicXlitLatinDownloads, IndicXlitModels.LATIN,
        title = R.string.languages_romanized_dictation_model_title,
        deleteTitle = R.string.languages_romanized_dictation_delete_title,
        deleteBody = R.string.languages_romanized_dictation_delete_body,
        missingRes = R.string.languages_romanized_dictation_model_missing,
    ) { withContext(Dispatchers.IO) { IndicXlitLatinDownloads.delete(context) } }
}

/**
 * Where [pack]'s download stands; a tap fetches it, or fetches it again after
 * a failure, and once it is here offers to delete it (so does a swipe).
 */
@Composable
private fun XlitModelRow(
    settings: LiveSettings,
    downloads: XlitDownloads,
    pack: IndicXlitModels.Pack,
    @StringRes title: Int,
    @StringRes deleteTitle: Int,
    @StringRes deleteBody: Int,
    @StringRes missingRes: Int = R.string.languages_neural_phonetic_model_missing,
    delete: suspend () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val download = rememberXlitDownload(settings, downloads, pack)
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(deleteTitle),
            body = stringResource(deleteBody),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { delete() }
            },
        )
    }
    val status by downloads.status.collectAsState()
    val module by FeatureModules.onnx.state.collectAsState()
    LaunchedEffect(Unit) { downloads.refresh(context) }
    val size = formatBytes(pack.bytes)
    val toFetch = formatBytes(downloads.bytesToFetch(context).takeIf { it > 0 } ?: pack.bytes)
    val value = when (val s = status) {
        is XlitDownloads.Status.Downloaded -> stringResource(R.string.languages_neural_phonetic_model_ready, size)
        is XlitDownloads.Status.Downloading ->
            if (s.total > 0) {
                stringResource(R.string.languages_neural_phonetic_model_downloading, formatBytes(s.bytes), formatBytes(s.total))
            } else {
                stringResource(CommonR.string.common_downloading)
            }
        is XlitDownloads.Status.Failed ->
            if ((module as? ModuleState.Missing)?.consent != null) {
                nativeConsentInfo((module as ModuleState.Missing).consent.orEmpty())
            } else if (s.messageArg.isEmpty()) {
                stringResource(s.messageRes)
            } else {
                stringResource(s.messageRes, s.messageArg)
            }
        XlitDownloads.Status.NotDownloaded -> stringResource(missingRes, toFetch)
    }
    // No click while the pack downloads; no chevron ever, since a tap opens
    // nothing: it fetches, or asks to delete what is here.
    val missing = status is XlitDownloads.Status.NotDownloaded || status is XlitDownloads.Status.Failed
    val here = status is XlitDownloads.Status.Downloaded
    val deleteLabel = stringResource(CommonR.string.common_delete)
    WmRow(
        title = stringResource(title),
        subtitle = value,
        icon = SettingsRowIcons[title],
        highlightKey = title,
        swipeActions = if (here) listOf(deleteSwipeAction(deleteLabel) { confirmDelete = true }) else emptyList(),
        onClick = when {
            missing && !offlineBuild -> download
            here -> ({ confirmDelete = true })
            else -> null
        },
    )    // For a phone that cannot download (#998): the pack's three files, fetched
    // elsewhere and imported here, checked as the download checks them.
    if (missing) {
        OfflineImportRow(
            subtitle = stringResource(R.string.offline_import_xlit_subtitle),
            links = remember(pack) { pack.parts.mapNotNull { part -> IndicXlitModels.url(part)?.let { OfflineLink(part.fileName, it) } } },
            onImported = { downloads.refresh(context) },
        )
    }
}

/**
 * Deletes the pack and turns neural suggestions off on every language that
 * had them: a switch left on with no model behind it would sit there doing
 * nothing until it fetched 13 MB nobody asked for.
 */
internal suspend fun deleteNeuralPhonetic(context: Context, repository: SettingsRepository, settings: KeyboardSettings) {
    for (langId in settings.suggestionStrip.neuralPhoneticLangs) repository.setNeuralPhonetic(langId, false)
    withContext(Dispatchers.IO) { IndicXlitDownloads.delete(context) }
}
