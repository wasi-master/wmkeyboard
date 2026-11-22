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
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.chewing.ChewingData
import com.wasimaster.wmkeyboard.core.input.chewing.ChewingRuntime
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The two engines Zhuyin can convert on. */
private enum class ZhuyinEngine { BUILT_IN, CHEWING }

/**
 * The Chinese screen's Zhuyin engine group: Built-in or Chewing, and with
 * Chewing its library, its dictionary, where it stands, and forgetting what
 * it learnt. libchewing opens by itself once both downloads are here; until
 * then, and before the first unlock, the Zhuyin layout keeps converting on
 * the built-in engine.
 */
@Composable
internal fun ChewingEngineGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    val notifyDownload = rememberDownloadNotifier()
    val chewingOn = settings.watch { it.cjk.chewingZhuyin }
    val module by FeatureModules.chewing.state.collectAsState()
    val status by ChewingRuntime.status.collectAsState()
    val downloads by CjkDictDownloadManager.states.collectAsState()
    val dataStatus = downloads[ChewingData.pack.id] ?: CjkDictDownloadManager.DownloadStatus.NotDownloaded
    var meteredFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    /** Runs [start] now, after the metered-network question, or not at all. */
    fun download(start: () -> Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> start()
            MeteredDecision.ASK -> meteredFor = start
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    fun downloadData() = download {
        CjkDictDownloadManager.startAll(filesDir, ChewingData.packs)
        notifyDownload(ChewingData.pack.id, context.getString(ChewingData.pack.displayNameRes), DownloadProgressFlows.cjkDict(context, ChewingData.pack.id))
    }

    LaunchedEffect(Unit) { CjkDictDownloadManager.refresh(filesDir) }
    // The dictionary landing or going, or the library arriving, changes what
    // Chewing can do; the runtime re-reads the disk and says so through [status].
    LaunchedEffect(dataStatus == CjkDictDownloadManager.DownloadStatus.Downloaded, module, chewingOn) {
        if (chewingOn) ChewingRuntime.recheck(context)
    }

    if (chewingOn) ChewingLibraryBanner(module) { download { FeatureModules.chewing.requestInstall() } }

    SettingsGroup(
        stringResource(R.string.languages_chewing_group_title),
        info = stringResource(R.string.languages_chewing_group_info),
    ) {
        item {
            ChoiceSetting(
                R.string.languages_chewing_engine_title,
                subtitle = stringResource(R.string.languages_chewing_engine_subtitle),
                options = listOf(
                    ZhuyinEngine.BUILT_IN to stringResource(R.string.languages_rime_engine_builtin),
                    ZhuyinEngine.CHEWING to stringResource(R.string.languages_chewing_engine_chewing),
                ),
                selected = if (chewingOn) ZhuyinEngine.CHEWING else ZhuyinEngine.BUILT_IN,
                default = ZhuyinEngine.BUILT_IN,
                info = stringResource(R.string.languages_chewing_engine_info),
            ) { engine ->
                val on = engine == ZhuyinEngine.CHEWING
                scope.launch { repository.setChewingZhuyin(on) }
                if (on) {
                    if (module != ModuleState.Installed) download { FeatureModules.chewing.requestInstall() }
                    if (ChewingData.file(filesDir) == null && dataStatus !is CjkDictDownloadManager.DownloadStatus.Downloading) downloadData()
                }
            }
        }

        item(visible = chewingOn) {
            WmRow(
                title = stringResource(R.string.languages_chewing_status_title),
                icon = SettingsRowIcons[R.string.languages_chewing_status_title],
                subtitle = when (val s = status) {
                    ChewingRuntime.Status.Off -> stringResource(R.string.languages_chewing_status_off)
                    ChewingRuntime.Status.Locked -> stringResource(R.string.languages_chewing_status_locked)
                    ChewingRuntime.Status.NeedsLibrary -> stringResource(R.string.languages_chewing_status_needs_library)
                    ChewingRuntime.Status.NeedsData -> stringResource(R.string.languages_chewing_status_needs_data)
                    ChewingRuntime.Status.Starting -> stringResource(R.string.languages_chewing_status_starting)
                    is ChewingRuntime.Status.Ready -> stringResource(R.string.languages_chewing_status_ready, s.dataVersion)
                    is ChewingRuntime.Status.Failed -> stringResource(R.string.languages_chewing_status_failed, s.detail)
                },
            )
        }

        item(visible = chewingOn, key = ChewingData.pack.id) {
            EnginePackRow(ChewingData.pack, dataStatus, onDeleted = { ChewingRuntime.recheck(context) }) { downloadData() }
        }

        // For a phone that cannot download (#1006): the same file, fetched
        // elsewhere and imported here. The library has its own row in its banner.
        item(visible = chewingOn && dataStatus != CjkDictDownloadManager.DownloadStatus.Downloaded) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_chewing_subtitle),
                links = remember { listOf(OfflineLink(ChewingData.pack.fileName, ChewingData.pack.url)) },
                onImported = { scope.launch { CjkDictDownloadManager.refresh(filesDir) } },
            )
        }

        item(visible = chewingOn) {
            WmRow(
                title = stringResource(R.string.languages_chewing_clear_title),
                icon = SettingsRowIcons[R.string.languages_chewing_clear_title],
                subtitle = stringResource(if (cleared) R.string.languages_chewing_clear_done else R.string.languages_chewing_clear_subtitle),
                onClick = {
                    ChewingRuntime.clearHistory(context)
                    cleared = true
                },
            )
        }
    }

    meteredFor?.let { start ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredFor = null
                start()
            },
            onDismiss = { meteredFor = null },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/** The library's own download, while Chewing is picked and the library is not here. */
@Composable
private fun ChewingLibraryBanner(module: ModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        ModuleState.Installed -> Unit
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.languages_chewing_library_installing,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.languages_chewing_library_installing_unsized)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), "chewing")
        is ModuleState.Missing -> {
            val size = (FeatureModules.chewing as? DownloadedModuleGate)?.missingBytes ?: 0L
            StateBanner(
                when {
                    module.failed -> stringResource(R.string.languages_chewing_library_failed)
                    size > 0L -> stringResource(R.string.languages_chewing_library_missing, Formatter.formatShortFileSize(context, size))
                    else -> stringResource(R.string.languages_chewing_library_missing_unsized)
                },
                action = stringResource(if (module.failed) CommonR.string.common_retry else CommonR.string.common_download),
                tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
                onAction = onDownload,
            )
        }
    }
}
