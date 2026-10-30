package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
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
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.semantic.SemanticDownloads
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SemanticSearchMode
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Search by meaning on the AI screen: one choice that is also the download
 * (Off, Text, Text and pictures, each with its size), the download's state
 * under it, and the two rows that only mean something once the model is here.
 * Picking a mode starts its download, after the metered-connection question
 * every other model download asks.
 */
@Composable
internal fun SemanticSearchGroup(repository: SettingsRepository, settings: LiveSettings) {
    if (!SemanticModels.SUPPORTED) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = settings.watch { it.semanticSearch.mode }
    val states by SemanticDownloads.states.collectAsState()
    // Read here, in composition: the group builder below is not composable.
    val onDisk = remember(states) { SemanticModels.bytesOnDisk(context.noBackupFilesDir) }
    val module by FeatureModules.litert.state.collectAsState()
    val decide = rememberDownloadDecision(settings)
    var metered by remember { mutableStateOf<SemanticModels.Pack?>(null) }
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { SemanticDownloads.refresh(context) }

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val textBytes = SemanticModels.bytes(SemanticModels.Pack.TEXT)
    val pictureBytes = textBytes + SemanticModels.bytes(SemanticModels.Pack.PICTURES)
    val pack = when (mode) {
        SemanticSearchMode.OFF -> null
        SemanticSearchMode.TEXT -> SemanticModels.Pack.TEXT
        SemanticSearchMode.TEXT_AND_PICTURES -> SemanticModels.Pack.PICTURES
    }
    fun download(target: SemanticModels.Pack) {
        when (decide()) {
            MeteredDecision.ALLOWED -> SemanticDownloads.start(context, target)
            MeteredDecision.ASK -> metered = target
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    SettingsGroup(
        stringResource(R.string.semantic_group_title),
        info = stringResource(R.string.semantic_group_info),
    ) {
        item {
            ChoiceSetting(
                R.string.semantic_mode_title,
                subtitle = stringResource(R.string.semantic_mode_subtitle),
                info = stringResource(R.string.semantic_mode_info),
                options = listOf(
                    SemanticSearchMode.OFF to stringResource(R.string.semantic_mode_off),
                    SemanticSearchMode.TEXT to stringResource(R.string.semantic_mode_text, size(textBytes)),
                    SemanticSearchMode.TEXT_AND_PICTURES to stringResource(R.string.semantic_mode_pictures, size(pictureBytes)),
                ),
                selected = mode,
                default = SettingsDefaults.semanticSearch.mode,
            ) { picked ->
                scope.launch { repository.updateSemanticSearch { it.copy(mode = picked) } }
                when (picked) {
                    SemanticSearchMode.OFF -> SemanticModels.Pack.entries.forEach(SemanticDownloads::cancel)
                    SemanticSearchMode.TEXT -> download(SemanticModels.Pack.TEXT)
                    SemanticSearchMode.TEXT_AND_PICTURES -> download(SemanticModels.Pack.PICTURES)
                }
            }
        }
        if (pack != null) {
            item {
                val status = states[pack] ?: SemanticDownloads.Status.NotDownloaded
                // The text pack comes first when pictures are asked for; its
                // bar is the one moving until it is done.
                val textStatus = states[SemanticModels.Pack.TEXT]
                val shown = if (pack == SemanticModels.Pack.PICTURES && textStatus is SemanticDownloads.Status.Downloading) textStatus else status
                when {
                    module is ModuleState.Missing && (module as ModuleState.Missing).consent != null ->
                        NativeConsentBanner((module as ModuleState.Missing).consent.orEmpty(), "litert")
                    shown is SemanticDownloads.Status.Downloading -> StateBanner(
                        stringResource(R.string.semantic_status_downloading, size(shown.bytes), size(shown.total)),
                        action = stringResource(CommonR.string.common_cancel),
                        onAction = { SemanticModels.Pack.entries.forEach(SemanticDownloads::cancel) },
                    )
                    shown is SemanticDownloads.Status.Failed -> StateBanner(
                        if (shown.messageArg.isEmpty()) stringResource(shown.messageRes)
                        else stringResource(shown.messageRes, shown.messageArg),
                        action = if (offlineBuild) null else stringResource(CommonR.string.common_retry),
                        tone = BannerTone.WARNING,
                        onAction = { download(pack) },
                    )
                    shown is SemanticDownloads.Status.Downloaded && SemanticDownloads.ready(context, pack) ->
                        StateBanner(stringResource(R.string.semantic_status_ready))
                    offlineBuild -> StateBanner(stringResource(R.string.offline_no_download_info))
                    else -> StateBanner(
                        stringResource(R.string.semantic_status_missing),
                        action = stringResource(CommonR.string.common_download),
                        onAction = { download(pack) },
                    )
                }
            }
        }
        if (mode == SemanticSearchMode.TEXT_AND_PICTURES) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.semantic_tag_stickers_title,
                    stringResource(R.string.semantic_tag_stickers_subtitle),
                    settings.watch { it.semanticSearch.tagStickers },
                    default = SettingsDefaults.semanticSearch.tagStickers,
                ) { on -> scope.launch { repository.updateSemanticSearch { it.copy(tagStickers = on) } } }
            }
        }
        if (onDisk > 0L) {
            item(level = SettingLevel.STANDARD) {
                ActionRow(
                    R.string.semantic_delete_title,
                    subtitle = stringResource(R.string.semantic_delete_subtitle, size(onDisk)),
                    action = stringResource(R.string.semantic_delete_action),
                    confirm = stringResource(R.string.semantic_delete_confirm),
                ) {
                    SemanticDownloads.delete(context, SemanticModels.Pack.TEXT)
                    scope.launch { repository.updateSemanticSearch { it.copy(mode = SemanticSearchMode.OFF) } }
                }
            }
        }
    }
    metered?.let { target ->
        MeteredDownloadDialog(
            detail = stringResource(R.string.semantic_metered_body),
            onConfirm = {
                metered = null
                SemanticDownloads.start(context, target)
            },
            onDismiss = { metered = null },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}
