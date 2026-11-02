package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.storage.FeatureLibraryInfo
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.localllm.LlamaCpp
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NativePin
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One job a phone can have a library for. Usually one gate; the NPU's shared
 * libraries and this chip's stub and skel are separate gates that only work
 * together, so they read as one row. [bytes] is 0 on Play, which publishes no
 * sizes before a download starts.
 */
internal class FeatureLibraryRow(
    val id: String,
    val info: FeatureLibraryInfo,
    val gates: List<String>,
    val files: List<String>,
    val bytes: Long,
    val licences: List<String>,
)

/** What the Feature libraries page lists on this build and this phone. */
internal object FeatureLibraryList {

    const val ROUTE = "feature_libraries"

    /** The NPU's gate; each chip's own is this with `-<arch>` after it. */
    private const val NPU = "npu"
    private const val GPU_LLAMA = "llamagpu"

    /** Every library this phone can use. Empty on a build that carries them all in its APK. */
    val rows: List<FeatureLibraryRow> by lazy {
        val chip = NpuSupport.soc?.let(NpuSupport::runtimeLibraries).orEmpty()
        val gpu = LlamaCpp.gpuLibraryUsable
        if (NativeDelivery.active) {
            fromPins(NativeDelivery.pins(), chip, gpu, offline = !InternetPermission.granted)
        } else {
            val modules = FeatureModules.playModules.filter { FeatureModules.gate(it)?.onDemand == true }
            fromModules(modules, chip.isNotEmpty(), gpu)
        }
    }

    /** Whether the page has anything to show, for the rows that lead to it. */
    val offered: Boolean get() = rows.isNotEmpty()

    /**
     * A build with native delivery. A library the phone could never run is
     * left out: another chip's NPU files, the OpenCL build without an Adreno
     * GPU, and on the offline build the engines whose models it cannot get.
     * "Download all" would fetch them for nothing.
     */
    fun fromPins(
        pins: List<NativePin>,
        chipLibraries: List<String>,
        gpuUsable: Boolean,
        offline: Boolean,
    ): List<FeatureLibraryRow> = pins
        .filter { pin ->
            when {
                offline && pin.gate in OFFLINE_USELESS_GATES -> false
                pin.gate == GPU_LLAMA -> gpuUsable
                pin.gate == NPU -> chipLibraries.isNotEmpty()
                pin.gate.startsWith("$NPU-") -> pin.name in chipLibraries
                else -> true
            }
        }
        .groupBy { if (it.gate.startsWith("$NPU-")) NPU else it.gate }
        .mapNotNull { (id, group) ->
            val info = FeatureLibraryInfo.byGate[id] ?: return@mapNotNull null
            FeatureLibraryRow(
                id = id,
                info = info,
                gates = group.map { it.gate }.distinct(),
                files = group.map { it.name },
                bytes = group.sumOf { it.size },
                licences = group.mapNotNull { it.licence }.distinct(),
            )
        }

    /** The Play build: whole modules, with no file names or sizes until Play reports them. */
    fun fromModules(modules: List<String>, hasNpu: Boolean, gpuUsable: Boolean): List<FeatureLibraryRow> = modules
        .filter { module ->
            when (module) {
                NPU -> hasNpu
                GPU_LLAMA -> gpuUsable
                else -> true
            }
        }
        .mapNotNull { module ->
            val info = FeatureLibraryInfo.byGate[module] ?: return@mapNotNull null
            FeatureLibraryRow(module, info, listOf(module), emptyList(), 0L, emptyList())
        }

    fun gateOf(name: String): ModuleGate? = NativeDelivery.gate(name) ?: FeatureModules.gate(name)

    fun isHere(row: FeatureLibraryRow): Boolean = row.gates.all { gateOf(it)?.installed != false }

    /** How many libraries are not on the phone, for what a restored backup says. */
    fun missingCount(): Int = rows.count { !isHere(it) }

    /** The ids of the rows on the phone, as gates finish, fail and get imported. */
    fun here(rows: List<FeatureLibraryRow>): Flow<Set<String>> {
        val states = rows.flatMap { it.gates }.distinct().mapNotNull(::gateOf).map { it.state }
        return combine(states) { _ -> idsHere(rows) }.distinctUntilChanged()
    }

    fun idsHere(rows: List<FeatureLibraryRow>): Set<String> = rows.filter(::isHere).mapTo(HashSet()) { it.id }
}

/**
 * Fetches libraries one after another, for **Download all**: every gate at once
 * would be a dozen connections each. It outlives the page, so leaving it does
 * not strand the rest of the list. A library that fails is passed over and its
 * row says so.
 */
internal object FeatureLibraryQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    fun download(gates: List<ModuleGate>) {
        if (!mutableRunning.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                for (gate in gates) gate.awaitInstalled()
            } finally {
                mutableRunning.value = false
            }
        }
    }
}

/** The row that opens the page, on every screen that leads to it. */
@Composable
internal fun FeatureLibrariesNavRow() {
    val navigate = LocalSettingsNavigate.current
    NavRow(
        R.string.feature_libraries_title,
        stringResource(R.string.feature_libraries_subtitle),
        route = FeatureLibraryList.ROUTE,
        icon = Icons.Outlined.Extension,
        onClick = { navigate(FeatureLibraryList.ROUTE) },
    )
}

/**
 * Every native library this phone can use on one page: what each is for, whether
 * it is here, and a way to get the missing ones without finding each feature's
 * own screen first. A row opens the settings of the feature it belongs to.
 */
@Composable
internal fun FeatureLibrariesScreen(settings: LiveSettings) {
    val rows = FeatureLibraryList.rows
    if (rows.isEmpty()) {
        StateBanner(stringResource(R.string.feature_libraries_built_in))
        return
    }
    val context = LocalContext.current
    val agreements by NativeConsent.agreements.collectAsState()
    val running by FeatureLibraryQueue.running.collectAsState()
    val here by remember(rows) { FeatureLibraryList.here(rows) }.collectAsState(
        initial = remember(rows) { FeatureLibraryList.idsHere(rows) },
    )
    // Missing first, fixed when the page opens: a row must not jump away from
    // under the finger the moment its download finishes.
    val ordered = remember(rows) { rows.sortedBy(FeatureLibraryList::isHere) }
    val canFetch = InternetPermission.granted &&
        (!NativeConsent.downloadsNeedOptIn || NativeConsent.DOWNLOADS in agreements)

    // A download waiting on the data saver's answer.
    var meteredFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var licenceFor by remember { mutableStateOf<FeatureLibraryRow?>(null) }
    fun asking(start: () -> Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> start()
            MeteredDecision.ASK -> meteredFor = start
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    // A library behind a licence not accepted yet stays out of the bulk
    // download: accepting is that row's own press.
    val fetchable = rows.filter { it.id !in here && agreements.containsAll(it.licences) }
    val fetchBytes = remember(here, agreements) { fetchable.sumOf { row -> NativeDelivery.missing(row.gates).sumOf { it.size } } }

    if (InternetPermission.granted) {
        SettingsGroup {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.feature_libraries_summary, here.size, rows.size))
                    Text(
                        stringResource(R.string.feature_libraries_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (canFetch && fetchable.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                val gates = fetchable.flatMap { it.gates }.mapNotNull(FeatureLibraryList::gateOf)
                                asking { FeatureLibraryQueue.download(gates) }
                            },
                            enabled = !running,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(
                                when {
                                    running -> stringResource(R.string.feature_libraries_downloading)
                                    fetchBytes > 0L -> stringResource(R.string.feature_libraries_download_all_sized, formatBytes(fetchBytes))
                                    else -> stringResource(R.string.feature_libraries_download_all)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // The opt-in, the licence and the offline build's import, where this build has them.
    if (!InternetPermission.granted || NativeConsent.downloadsNeedOptIn || rows.any { it.licences.isNotEmpty() }) {
        SettingsGroup { item { FeatureLibrariesRows() } }
    }

    SettingsGroup {
        for (row in ordered) {
            item {
                FeatureLibraryRowView(row, canFetch) {
                    if (agreements.containsAll(row.licences)) {
                        asking { row.gates.forEach { FeatureLibraryList.gateOf(it)?.requestInstall() } }
                    } else {
                        licenceFor = row
                    }
                }
            }
        }
    }

    licenceFor?.let { row ->
        QualcommLicenceDialog { accepted ->
            licenceFor = null
            if (accepted) asking { row.gates.forEach { FeatureLibraryList.gateOf(it)?.requestInstall() } }
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

@Composable
private fun FeatureLibraryRowView(row: FeatureLibraryRow, canFetch: Boolean, onDownload: () -> Unit) {
    val navigate = LocalSettingsNavigate.current
    val states = row.gates.mapNotNull(FeatureLibraryList::gateOf).map { it.state.collectAsState().value }
    val installed = states.all { it == ModuleState.Installed }
    val installing = states.filterIsInstance<ModuleState.Installing>()
    val failed = states.any { it is ModuleState.Missing && it.failed && it.consent == null }
    val files = row.files.joinToString(", ")
    WmRow(
        title = stringResource(row.info.description),
        supporting = {
            Column {
                if (files.isNotEmpty()) {
                    Text(if (row.bytes > 0L) stringResource(R.string.offline_import_native_label, files, formatBytes(row.bytes)) else files)
                }
                Text(
                    stringResource(
                        when {
                            installed -> R.string.feature_libraries_state_here
                            installing.isNotEmpty() -> R.string.feature_libraries_downloading
                            failed -> R.string.feature_libraries_state_failed
                            else -> R.string.feature_libraries_state_missing
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed && !installed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (installing.isNotEmpty()) {
                    val total = installing.sumOf { it.totalBytes }
                    if (total > 0L) {
                        LinearProgressIndicator(
                            progress = { (installing.sumOf { it.bytes }.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        },
        trailing = {
            when {
                installed -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.feature_libraries_state_here),
                    tint = MaterialTheme.colorScheme.primary,
                )
                installing.isEmpty() && canFetch -> TextButton(onClick = onDownload) {
                    Text(stringResource(if (failed) CommonR.string.common_retry else CommonR.string.common_download))
                }
            }
        },
        onClick = { navigate(row.info.route) },
    )
}
