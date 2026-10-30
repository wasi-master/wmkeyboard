package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.rime.RimeMethod
import com.wasimaster.wmkeyboard.core.input.rime.RimeRuntime
import com.wasimaster.wmkeyboard.core.input.rime.RimeSchemaCatalog
import com.wasimaster.wmkeyboard.core.input.rime.RimeStore
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The two engines a Chinese language can convert on. */
private enum class ChineseEngine { BUILT_IN, RIME }

/** The methods a language's layouts can type through RIME with, in the order the pickers list them. */
private fun methodsFor(langId: String): List<RimeMethod> = when (langId) {
    "yue" -> listOf(RimeMethod.JYUTPING)
    else -> listOf(RimeMethod.PINYIN, RimeMethod.T9_PINYIN, RimeMethod.ZHUYIN, RimeMethod.CANGJIE, RimeMethod.STROKE)
}

private fun methodTitle(method: RimeMethod): Int = when (method) {
    RimeMethod.PINYIN -> R.string.languages_rime_method_pinyin
    RimeMethod.T9_PINYIN -> R.string.languages_rime_method_t9
    RimeMethod.ZHUYIN -> R.string.languages_rime_method_zhuyin
    RimeMethod.CANGJIE -> R.string.languages_rime_method_cangjie
    RimeMethod.STROKE -> R.string.languages_rime_method_stroke
    RimeMethod.JYUTPING -> R.string.languages_rime_method_jyutping
}

/**
 * The language screen's RIME group, for Chinese and Cantonese: Built-in or
 * RIME, and with RIME its library, the schema packs, the user's own schemas,
 * compiling with progress, and which schema each kind of layout uses.
 *
 * Compiling ("deploy" in RIME's words) starts by itself whenever the
 * installed packs and the last compile disagree while this screen is open,
 * so a download or a delete is followed through without another press; the
 * Compile button is there to do it again on demand. Until a compile is done
 * the keyboard keeps converting on the built-in engine.
 */
@Composable
internal fun RimeEngineGroup(
    langId: String,
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val scope = rememberCoroutineScope()
    val notifyDownload = rememberDownloadNotifier()
    val rimeOn = settings.watch { langId in it.cjk.rimeLanguages }
    val module by FeatureModules.rime.state.collectAsState()
    val status by RimeRuntime.status.collectAsState()
    val downloads by CjkDictDownloadManager.states.collectAsState()
    var meteredFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var imports by remember { mutableStateOf(RimeStore.imports(filesDir)) }

    /** Runs [start] now, after the metered-network question, or not at all. */
    fun download(start: () -> Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> start()
            MeteredDecision.ASK -> meteredFor = start
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    LaunchedEffect(langId) {
        CjkDictDownloadManager.refresh(filesDir)
        RimeRuntime.recheck(context)
    }
    // A pack landing or going, or the library arriving, changes what RIME can
    // run; the runtime re-reads the disk and says so through [status].
    val rimeDownloaded = RimeSchemaCatalog.packs.map { downloads[it.id] == CjkDictDownloadManager.DownloadStatus.Downloaded }
    LaunchedEffect(rimeDownloaded, module) {
        if (rimeOn) RimeRuntime.recheck(context)
    }
    // Compile as soon as there is something to compile and nothing still on its way.
    val anyDownloading = RimeSchemaCatalog.packs.any { downloads[it.id] is CjkDictDownloadManager.DownloadStatus.Downloading }
    LaunchedEffect(status, anyDownloading, module, rimeOn) {
        if (rimeOn && status == RimeRuntime.Status.NeedsDeploy && !anyDownloading && module == ModuleState.Installed) {
            RimeRuntime.deploy(context)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importMessage = withContext(Dispatchers.IO) {
                val staged = File(context.cacheDir, "rime_import.zip")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { input.copyTo(it) }
                    }
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "schemas"
                    val imported = RimeStore.import(filesDir, staged, name)
                    context.getString(R.string.languages_rime_import_done, imported.schemas.joinToString(", ").ifEmpty { imported.name })
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    context.getString(R.string.languages_rime_import_failed)
                } finally {
                    staged.delete()
                }
            }
            imports = RimeStore.imports(filesDir)
            RimeRuntime.recheck(context)
        }
    }

    if (rimeOn) RimeLibraryBanner(module) { download { FeatureModules.rime.requestInstall() } }

    SettingsGroup(
        stringResource(R.string.languages_rime_group_title),
        info = stringResource(R.string.languages_rime_group_info),
    ) {
        item {
            ChoiceSetting(
                R.string.languages_rime_engine_title,
                subtitle = stringResource(R.string.languages_rime_engine_subtitle),
                options = listOf(
                    ChineseEngine.BUILT_IN to stringResource(R.string.languages_rime_engine_builtin),
                    ChineseEngine.RIME to stringResource(R.string.languages_rime_engine_rime),
                ),
                selected = if (rimeOn) ChineseEngine.RIME else ChineseEngine.BUILT_IN,
                default = ChineseEngine.BUILT_IN,
            ) { engine ->
                val on = engine == ChineseEngine.RIME
                scope.launch { repository.setRimeLanguage(langId, on) }
                if (on && module != ModuleState.Installed) download { FeatureModules.rime.requestInstall() }
            }
        }

        item(visible = rimeOn) {
            RimeStatusRow(status, enabled = module == ModuleState.Installed && !RimeRuntime.deploying) {
                RimeRuntime.deploy(context)
            }
        }

        val packs = listOf(RimeSchemaCatalog.BASE) + RimeSchemaCatalog.forLang(langId).map { it.pack }
        for (pack in packs) {
            item(visible = rimeOn, key = pack.id) {
                EnginePackRow(
                    pack,
                    downloads[pack.id] ?: CjkDictDownloadManager.DownloadStatus.NotDownloaded,
                    onDeleted = { RimeRuntime.recheck(context) },
                ) {
                    download {
                        val wanted = RimeSchemaCatalog.withBase(pack)
                        CjkDictDownloadManager.startAll(filesDir, wanted)
                        for (each in wanted) {
                            notifyDownload(each.id, context.getString(each.displayNameRes), DownloadProgressFlows.cjkDict(context, each.id))
                        }
                    }
                }
            }
        }

        for (name in imports) {
            item(visible = rimeOn, key = "import:$name") {
                val label = stringResource(R.string.languages_rime_remove_import_desc, name)
                val remove = {
                    scope.launch {
                        withContext(Dispatchers.IO) { RimeStore.removeImport(filesDir, name) }
                        imports = RimeStore.imports(filesDir)
                        RimeRuntime.recheck(context)
                    }
                    Unit
                }
                WmRow(
                    title = name,
                    subtitle = stringResource(R.string.languages_rime_imported_subtitle),
                    swipeActions = listOf(deleteSwipeAction(label, remove)),
                    trailing = {
                        IconButton(onClick = remove) {
                            Icon(Icons.Outlined.Delete, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }

        item(visible = rimeOn) {
            WmRow(
                title = stringResource(R.string.languages_rime_import_title),
                icon = SettingsRowIcons[R.string.languages_rime_import_title],
                subtitle = importMessage ?: stringResource(R.string.languages_rime_import_subtitle),
                trailing = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                        Text(stringResource(CommonR.string.common_import))
                    }
                },
            )
        }

        // One picker per kind of layout, once something is compiled to pick.
        val ready = status as? RimeRuntime.Status.Ready
        for (method in methodsFor(langId)) {
            item(visible = rimeOn && ready != null && ready.schemas.isNotEmpty(), key = "method:${method.name}") {
                val schemas = ready?.schemas.orEmpty()
                ChoiceSetting(
                    methodTitle(method),
                    info = stringResource(R.string.languages_rime_method_info),
                    options = listOf("" to stringResource(R.string.languages_rime_method_auto)) +
                        schemas.map { it.id to if (it.name == it.id) it.id else "${it.name} · ${it.id}" },
                    selected = settings.watch { it.cjk.rimeSchemas[method.name] }?.takeIf { id -> schemas.any { it.id == id } } ?: "",
                    default = "",
                ) { id -> scope.launch { repository.setRimeSchema(method.name, id.ifEmpty { null }) } }
            }
        }

        item(visible = rimeOn) {
            WmRow(
                title = stringResource(R.string.languages_rime_delete_all_title),
                icon = SettingsRowIcons[R.string.languages_rime_delete_all_title],
                subtitle = stringResource(R.string.languages_rime_delete_all_subtitle),
                enabled = !RimeRuntime.deploying,
                onClick = {
                    RimeRuntime.deleteAll(context)
                    imports = emptyList()
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

/** The library's own download, while RIME is picked and the library is not here. */
@Composable
private fun RimeLibraryBanner(module: ModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        ModuleState.Installed -> Unit
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.languages_rime_library_installing,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.languages_rime_library_installing_unsized)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), "rime")
        is ModuleState.Missing -> {
            val size = (FeatureModules.rime as? DownloadedModuleGate)?.missingBytes ?: 0L
            StateBanner(
                when {
                    module.failed -> stringResource(R.string.languages_rime_library_failed)
                    size > 0L -> stringResource(R.string.languages_rime_library_missing, Formatter.formatShortFileSize(context, size))
                    else -> stringResource(R.string.languages_rime_library_missing_unsized)
                },
                action = stringResource(if (module.failed) CommonR.string.common_retry else CommonR.string.common_download),
                tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
                onAction = onDownload,
            )
        }
    }
}

/** What RIME is doing, with a progress bar while it compiles and the Compile button otherwise. */
@Composable
private fun RimeStatusRow(status: RimeRuntime.Status, enabled: Boolean, onDeploy: () -> Unit) {
    val text = when (status) {
        RimeRuntime.Status.Off -> stringResource(R.string.languages_rime_status_off)
        RimeRuntime.Status.Locked -> stringResource(R.string.languages_rime_status_locked)
        RimeRuntime.Status.NeedsLibrary -> stringResource(R.string.languages_rime_status_needs_library)
        RimeRuntime.Status.NeedsDeploy -> stringResource(R.string.languages_rime_status_needs_deploy)
        is RimeRuntime.Status.Deploying -> status.current?.let {
            stringResource(R.string.languages_rime_status_deploying, it, status.done + 1, status.total)
        } ?: stringResource(R.string.languages_rime_status_deploying_start)
        is RimeRuntime.Status.Ready -> stringResource(
            R.string.languages_rime_status_ready,
            status.schemas.joinToString(", ") { it.name },
        )
        is RimeRuntime.Status.Failed -> stringResource(R.string.languages_rime_status_failed, status.detail)
    }
    WmRow(
        title = stringResource(R.string.languages_rime_status_title),
        icon = SettingsRowIcons[R.string.languages_rime_status_title],
        subtitle = text,
        supporting = (status as? RimeRuntime.Status.Deploying)?.let { deploying ->
            {
                if (deploying.total > 0) {
                    LinearProgressIndicator(
                        progress = { deploying.done.toFloat() / deploying.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        },
        trailing = {
            if (status !is RimeRuntime.Status.Deploying && status != RimeRuntime.Status.Off && status != RimeRuntime.Status.Locked) {
                TextButton(enabled = enabled, onClick = onDeploy) {
                    Text(stringResource(R.string.languages_rime_deploy_action))
                }
            }
        },
    )
}

/**
 * Download and delete for one of a conversion engine's packs (a RIME schema
 * pack, Mozc's dictionary), the same row the conversion dictionaries use.
 * [onDeleted] tells the engine its files went.
 */
@Composable
internal fun EnginePackRow(
    pack: CjkDictPack,
    status: CjkDictDownloadManager.DownloadStatus,
    onDeleted: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val name = stringResource(pack.displayNameRes)
    val deleteLabel = stringResource(R.string.languages_cjk_delete_desc, name)
    val delete = {
        CjkDictDownloadManager.delete(filesDir, pack)
        onDeleted()
    }
    WmRow(
        title = name,
        subtitle = packStatusLabel(pack, status),
        swipeActions = if (status == CjkDictDownloadManager.DownloadStatus.Downloaded) {
            listOf(deleteSwipeAction(deleteLabel, delete))
        } else {
            emptyList()
        },
        trailing = {
            when (status) {
                is CjkDictDownloadManager.DownloadStatus.Downloading ->
                    IconButton(onClick = { CjkDictDownloadManager.cancel() }) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                CjkDictDownloadManager.DownloadStatus.Downloaded ->
                    IconButton(onClick = delete) {
                        Icon(Icons.Outlined.Delete, contentDescription = deleteLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                CjkDictDownloadManager.DownloadStatus.NotDownloaded,
                is CjkDictDownloadManager.DownloadStatus.Paused,
                is CjkDictDownloadManager.DownloadStatus.Failed,
                -> TextButton(enabled = pack.available && InternetPermission.granted && !CjkDictDownloadManager.isBusy, onClick = onDownload) {
                    Text(
                        if (status is CjkDictDownloadManager.DownloadStatus.Paused) {
                            stringResource(R.string.languages_resume_action)
                        } else {
                            stringResource(CommonR.string.common_download)
                        },
                    )
                }
            }
        },
    )
}
