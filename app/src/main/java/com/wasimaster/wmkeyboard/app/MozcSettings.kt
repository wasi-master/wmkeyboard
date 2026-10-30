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
import com.wasimaster.wmkeyboard.core.input.mozc.MozcData
import com.wasimaster.wmkeyboard.core.input.mozc.MozcRuntime
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The two engines Japanese can convert on. */
private enum class JapaneseEngine { BUILT_IN, MOZC }

/**
 * The Japanese screen's engine group: Built-in or Mozc, and with Mozc its
 * library, its dictionary, where it stands, and forgetting what it learnt.
 * Mozc opens by itself once both downloads are here; until then, and before
 * the first unlock, the keyboard keeps converting on the built-in engine.
 */
@Composable
internal fun MozcEngineGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    val notifyDownload = rememberDownloadNotifier()
    val mozcOn = settings.watch { it.cjk.mozcJapanese }
    val module by FeatureModules.mozc.state.collectAsState()
    val status by MozcRuntime.status.collectAsState()
    val downloads by CjkDictDownloadManager.states.collectAsState()
    val dataStatus = downloads[MozcData.pack.id] ?: CjkDictDownloadManager.DownloadStatus.NotDownloaded
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
        CjkDictDownloadManager.startAll(filesDir, MozcData.packs)
        notifyDownload(MozcData.pack.id, context.getString(MozcData.pack.displayNameRes), DownloadProgressFlows.cjkDict(context, MozcData.pack.id))
    }

    LaunchedEffect(Unit) { CjkDictDownloadManager.refresh(filesDir) }
    // The dictionary landing or going, or the library arriving, changes what
    // Mozc can do; the runtime re-reads the disk and says so through [status].
    LaunchedEffect(dataStatus == CjkDictDownloadManager.DownloadStatus.Downloaded, module, mozcOn) {
        if (mozcOn) MozcRuntime.recheck(context)
    }

    if (mozcOn) MozcLibraryBanner(module) { download { FeatureModules.mozc.requestInstall() } }

    SettingsGroup(
        stringResource(R.string.languages_mozc_group_title),
        info = stringResource(R.string.languages_mozc_group_info),
    ) {
        item {
            ChoiceSetting(
                R.string.languages_mozc_engine_title,
                subtitle = stringResource(R.string.languages_mozc_engine_subtitle),
                options = listOf(
                    JapaneseEngine.BUILT_IN to stringResource(R.string.languages_rime_engine_builtin),
                    JapaneseEngine.MOZC to stringResource(R.string.languages_mozc_engine_mozc),
                ),
                selected = if (mozcOn) JapaneseEngine.MOZC else JapaneseEngine.BUILT_IN,
                default = JapaneseEngine.BUILT_IN,
            ) { engine ->
                val on = engine == JapaneseEngine.MOZC
                scope.launch { repository.setMozcJapanese(on) }
                if (on) {
                    if (module != ModuleState.Installed) download { FeatureModules.mozc.requestInstall() }
                    if (MozcData.file(filesDir) == null && dataStatus !is CjkDictDownloadManager.DownloadStatus.Downloading) downloadData()
                }
            }
        }

        item(visible = mozcOn) {
            WmRow(
                title = stringResource(R.string.languages_mozc_status_title),
                icon = SettingsRowIcons[R.string.languages_mozc_status_title],
                subtitle = when (val s = status) {
                    MozcRuntime.Status.Off -> stringResource(R.string.languages_mozc_status_off)
                    MozcRuntime.Status.Locked -> stringResource(R.string.languages_mozc_status_locked)
                    MozcRuntime.Status.NeedsLibrary -> stringResource(R.string.languages_mozc_status_needs_library)
                    MozcRuntime.Status.NeedsData -> stringResource(R.string.languages_mozc_status_needs_data)
                    MozcRuntime.Status.Starting -> stringResource(R.string.languages_mozc_status_starting)
                    is MozcRuntime.Status.Ready -> stringResource(R.string.languages_mozc_status_ready, s.dataVersion)
                    is MozcRuntime.Status.Failed -> stringResource(R.string.languages_mozc_status_failed, s.detail)
                },
            )
        }

        item(visible = mozcOn, key = MozcData.pack.id) {
            EnginePackRow(MozcData.pack, dataStatus, onDeleted = { MozcRuntime.recheck(context) }) { downloadData() }
        }

        // For a phone that cannot download (#1006): the same file, fetched
        // elsewhere and imported here. The library has its own row in its banner.
        item(visible = mozcOn && dataStatus != CjkDictDownloadManager.DownloadStatus.Downloaded) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_mozc_subtitle),
                links = remember { listOf(OfflineLink(MozcData.pack.fileName, MozcData.pack.url)) },
                onImported = { scope.launch { CjkDictDownloadManager.refresh(filesDir) } },
            )
        }

        item(visible = mozcOn) {
            WmRow(
                title = stringResource(R.string.languages_mozc_clear_title),
                icon = SettingsRowIcons[R.string.languages_mozc_clear_title],
                subtitle = stringResource(if (cleared) R.string.languages_mozc_clear_done else R.string.languages_mozc_clear_subtitle),
                onClick = {
                    MozcRuntime.clearHistory(context)
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

/** The library's own download, while Mozc is picked and the library is not here. */
@Composable
private fun MozcLibraryBanner(module: ModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        ModuleState.Installed -> Unit
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.languages_mozc_library_installing,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.languages_mozc_library_installing_unsized)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), "mozc")
        is ModuleState.Missing -> {
            val size = (FeatureModules.mozc as? DownloadedModuleGate)?.missingBytes ?: 0L
            StateBanner(
                when {
                    module.failed -> stringResource(R.string.languages_mozc_library_failed)
                    size > 0L -> stringResource(R.string.languages_mozc_library_missing, Formatter.formatShortFileSize(context, size))
                    else -> stringResource(R.string.languages_mozc_library_missing_unsized)
                },
                action = stringResource(if (module.failed) CommonR.string.common_retry else CommonR.string.common_download),
                tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
                onAction = onDownload,
            )
        }
    }
}
