package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryDownloads
import com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryDownloads.ImportResult
import com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryDownloads.Status
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.prediction.VoikkoRuntime
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether [langId]'s Dictionaries group carries [VoikkoRow]: Finnish. */
internal fun offersVoikko(langId: String): Boolean = VoikkoRuntime.supports(langId)

/**
 * The Voikko row on Finnish's Dictionaries group. Download fetches the pinned
 * voikko-fi dictionary from the data repository; Import takes a zip of a
 * `5/mor-<variant>/` folder the user built or fetched. Both need the spelling
 * library, so until it is on the device the row carries the module banner,
 * with its own Download. A build without the library (the offline one) says
 * so and offers nothing. Nothing here is fetched from the keyboard.
 */
@Composable
internal fun VoikkoRow(langId: String, settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    val entry = remember(langId) { VoikkoDictionaryCatalog.forLanguage(langId) }
    val module by VoikkoRuntime.moduleState.collectAsState()
    val states by VoikkoDictionaryDownloads.states.collectAsState()
    LaunchedEffect(langId) { withContext(Dispatchers.IO) { VoikkoDictionaryDownloads.refresh(filesDir, langId) } }
    val status = states[langId] ?: Status.NotInstalled
    // Loading the library maps half a megabyte, so it is asked off the main thread.
    val libraryLoads by produceState<Boolean?>(null, module) {
        value = if (module == ModuleState.Installed) withContext(Dispatchers.IO) { VoikkoRuntime.available } else null
    }
    val ready = module == ModuleState.Installed && libraryLoads == true

    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var meteredAction by remember { mutableStateOf<() -> Unit>({}) }
    fun guarded(action: () -> Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> action()
            MeteredDecision.ASK -> {
                meteredAction = action
                metered = true
            }
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                if (input == null) ImportResult.BROKEN else VoikkoDictionaryDownloads.import(filesDir, langId, input)
            }
            val message = when (result) {
                ImportResult.INSTALLED -> R.string.languages_voikko_import_done
                ImportResult.NOT_A_DICTIONARY -> R.string.languages_voikko_import_not_dictionary
                ImportResult.BROKEN -> R.string.languages_voikko_import_broken
                ImportResult.NO_LIBRARY -> R.string.languages_voikko_not_in_build_subtitle
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val supporting = when {
        module == ModuleState.Installed && libraryLoads == false ->
            stringResource(R.string.languages_voikko_not_in_build_subtitle)
        status is Status.Installed && status.version != null ->
            stringResource(R.string.languages_voikko_installed, status.version!!, formatBytes(status.sizeBytes))
        status is Status.Installed -> stringResource(R.string.languages_voikko_imported, formatBytes(status.sizeBytes))
        status is Status.Downloading -> stringResource(CommonR.string.common_downloading)
        status == Status.Failed -> stringResource(R.string.languages_voikko_failed)
        entry != null -> stringResource(
            R.string.languages_voikko_download_size,
            formatBytes(entry.packedBytes),
            formatBytes(entry.unpackedBytes),
            entry.licence,
        )
        else -> stringResource(R.string.languages_voikko_import_only_subtitle)
    }

    Column {
        WmRow(
            title = stringResource(R.string.languages_voikko_title),
            icon = SettingsRowIcons[R.string.languages_voikko_title],
            supporting = {
                Text(
                    supporting,
                    color = if (status == Status.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            },
            swipeActions = if (status is Status.Installed) {
                listOf(
                    deleteSwipeAction(stringResource(R.string.languages_voikko_delete_desc)) {
                        VoikkoDictionaryDownloads.delete(filesDir, langId)
                    },
                )
            } else {
                emptyList()
            },
            trailing = {
                if (ready) {
                    Row {
                        when (status) {
                            is Status.Installed -> IconButton(onClick = { VoikkoDictionaryDownloads.delete(filesDir, langId) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.languages_voikko_delete_desc),
                                )
                            }
                            is Status.Downloading -> TextButton(onClick = { VoikkoDictionaryDownloads.cancel(langId) }) {
                                Text(stringResource(CommonR.string.common_cancel))
                            }
                            Status.NotInstalled, Status.Failed -> {
                                TextButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                                    Text(stringResource(R.string.languages_speller_import_action))
                                }
                                if (entry != null && InternetPermission.granted) {
                                    TextButton(onClick = { guarded { VoikkoDictionaryDownloads.start(filesDir, langId) } }) {
                                        Text(
                                            stringResource(
                                                if (status == Status.Failed) CommonR.string.common_retry
                                                else CommonR.string.common_download,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (status is Status.Downloading) {
            val progress = if (status.totalBytes > 0L) {
                (status.bytes.toFloat() / status.totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (module != ModuleState.Installed) VoikkoModuleBanner(module) { guarded { VoikkoRuntime.requestModule() } }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                metered = false
                meteredAction()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/** The spelling library's own download: the same module as the GiellaLT speller row's. */
@Composable
private fun VoikkoModuleBanner(module: ModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.languages_speller_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.languages_speller_module_installing_info)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), "spell")
        is ModuleState.Missing -> StateBanner(
            stringResource(
                if (module.failed) R.string.models_whisper_module_failed_info else R.string.languages_voikko_module_missing_info,
            ),
            action = stringResource(
                if (module.failed) CommonR.string.common_retry else CommonR.string.common_download,
            ),
            tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = onDownload,
        )
        ModuleState.Installed -> Unit
    }
}
