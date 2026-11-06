package com.wasimaster.wmkeyboard.app.resources

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.app.storage.StorageBar
import com.wasimaster.wmkeyboard.app.storage.StorageSlice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BatterySaver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cached
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DocumentScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Layers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Memory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PieChart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RecordVoiceOver
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.ime.resources.LoadedModel
import com.wasimaster.wmkeyboard.ime.resources.ModelKind
import com.wasimaster.wmkeyboard.ime.resources.ResidentModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * What the keyboard is using right now (#1163): memory, processor time,
 * loaded models with a button to unload each, caches and running services.
 *
 * It measures only while it is on screen, every [SAMPLE_MS], and nothing of
 * it runs anywhere else, so there is no switch to turn it off: closed, it
 * costs nothing. Every figure is either a measurement the platform gives
 * (memory, processor time) or an owner's own size for a model file, and the
 * screen says which.
 */
@Composable
internal fun ResourcesScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf<ResourceSample?>(null) }
    // Bumped by an unload, so the list answers at once rather than at the next tick.
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        var previous = sample
        while (isActive) {
            val next = withContext(Dispatchers.Default) { takeSample(context, previous) }
            sample = next
            previous = next
            delay(SAMPLE_MS)
        }
    }
    var caches by remember { mutableStateOf<List<ResourceProbe.Cache>?>(null) }
    LaunchedEffect(Unit) { caches = withContext(Dispatchers.IO) { ResourceProbe.readCaches(context) } }

    fun unload(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            withContext(Dispatchers.Default) { work() }
            // The engines free on their own threads; give them a moment.
            delay(UNLOAD_SETTLE_MS)
            busy = false
            revision++
        }
    }

    val current = sample
    if (current == null) {
        SettingsGroup { item { WmRow(title = stringResource(R.string.resources_measuring)) } }
        return
    }

    MemoryCard(current.memory)
    ModelsCard(current, busy, onNavigate, onUnload = { id -> unload { ResidentModels.unload(id) } }) {
        unload { ResidentModels.unloadAll() }
    }
    CpuCard(current)
    RunningCard(current.services)
    caches?.let { CachesCard(it, onNavigate) }
    BatteryCard(context, onNavigate)
}

/** One look at everything the screen shows that changes from second to second. */
internal data class ResourceSample(
    val memory: ResourceProbe.Memory,
    val threads: List<ResourceProbe.ThreadTime>,
    val groups: List<GroupCpu>,
    val processCpuMs: Long,
    val uptimeMs: Long,
    val takenAtMs: Long,
    val models: List<LoadedModel>,
    val otherProcesses: List<ResourceProbe.OtherProcess>,
    val services: List<String>,
)

private fun takeSample(context: Context, previous: ResourceSample?): ResourceSample {
    val now = android.os.SystemClock.elapsedRealtime()
    val threads = ResourceProbe.readThreads()
    return ResourceSample(
        memory = ResourceProbe.readMemory(),
        threads = threads,
        groups = groupCpu(
            now = threads,
            previous = previous?.threads,
            intervalMs = previous?.let { now - it.takenAtMs } ?: 0L,
            ticksPerSecond = ResourceProbe.ticksPerSecond,
            mainTid = android.os.Process.myPid(),
        ),
        processCpuMs = ResourceProbe.processCpuMs(),
        uptimeMs = ResourceProbe.processUptimeMs(),
        takenAtMs = now,
        models = ResidentModels.loaded(context),
        otherProcesses = ResourceProbe.otherProcesses(context),
        services = ResourceProbe.runningServices(context),
    )
}

@Composable
private fun MemoryCard(memory: ResourceProbe.Memory) {
    val kb = 1024L
    val parts = listOf(
        Triple(R.string.resources_memory_native, memory.nativeHeapKb, MemoryNative),
        Triple(R.string.resources_memory_java, memory.javaHeapKb, MemoryJava),
        Triple(R.string.resources_memory_graphics, memory.graphicsKb, MemoryGraphics),
        Triple(R.string.resources_memory_code, memory.codeKb, MemoryCode),
        Triple(
            R.string.resources_memory_other,
            (memory.totalPssKb - memory.nativeHeapKb - memory.javaHeapKb - memory.graphicsKb - memory.codeKb)
                .coerceAtLeast(0L),
            MemoryOther,
        ),
    )
    SettingsGroup(
        stringResource(R.string.resources_memory_group),
        info = stringResource(R.string.resources_memory_info),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.resources_memory_total, formatBytes(memory.totalPssKb * kb)),
                    style = MaterialTheme.typography.headlineSmall,
                )
                StorageBar(
                    slices = parts.map { (title, size, color) -> StorageSlice(color, size * kb, key = title.toString()) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        for ((title, size, color) in parts) {
            item {
                WmRow(
                    title = stringResource(title),
                    subtitle = when (title) {
                        R.string.resources_memory_java -> stringResource(
                            R.string.resources_memory_java_detail,
                            formatBytes(memory.javaUsedBytes),
                            formatBytes(memory.javaMaxBytes),
                        )
                        R.string.resources_memory_native -> stringResource(
                            R.string.resources_memory_native_detail,
                            formatBytes(memory.nativeAllocatedBytes),
                        )
                        else -> null
                    },
                    accent = color,
                    trailing = { Text(formatBytes(size * kb), style = MaterialTheme.typography.labelLarge) },
                    trailingOnTitleLine = true,
                )
            }
        }
    }
}

@Composable
private fun ModelsCard(
    sample: ResourceSample,
    busy: Boolean,
    onNavigate: (String) -> Unit,
    onUnload: (String) -> Unit,
    onUnloadAll: () -> Unit,
) {
    SettingsGroup(
        stringResource(R.string.resources_models_group),
        info = stringResource(R.string.resources_models_info),
    ) {
        if (sample.models.isEmpty() && sample.otherProcesses.isEmpty()) {
            item { WmRow(title = stringResource(R.string.resources_models_none)) }
        }
        for (model in sample.models) {
            item {
                WmRow(
                    title = stringResource(titleOf(model.kind)),
                    subtitle = listOfNotNull(
                        model.name,
                        model.bytes?.let { stringResource(R.string.resources_model_size, formatBytes(it)) },
                    ).joinToString(" · ").ifEmpty { null },
                    icon = iconOf(model.kind),
                    trailing = {
                        TextButton(onClick = { onUnload(model.id) }, enabled = !busy) {
                            Text(stringResource(R.string.resources_unload_action))
                        }
                    },
                    onClick = manageRouteOf(model.kind)?.let { route -> { onNavigate(route) } },
                )
            }
        }
        // The sticker generator and search by meaning run apart from the
        // keyboard; their memory is theirs, and each lets go of its model a
        // few minutes after its last use.
        for (process in sample.otherProcesses) {
            item {
                WmRow(
                    title = processTitle(process.name),
                    subtitle = process.pssKb.takeIf { it > 0 }
                        ?.let { stringResource(R.string.resources_process_memory, formatBytes(it * 1024)) },
                    icon = Icons.Outlined.Layers,
                )
            }
        }
        if (sample.models.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilledTonalButton(onClick = onUnloadAll, enabled = !busy) {
                        Text(stringResource(R.string.resources_unload_all_action))
                    }
                    Text(
                        stringResource(R.string.resources_unload_all_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.resources_auto_unload_title),
                subtitle = stringResource(R.string.tooldetail_power_drop_warm_models_subtitle),
                icon = Icons.Outlined.BatterySaver,
                onClick = { onNavigate(POWER_SAVING_ROUTE) },
            )
        }
    }
}

@Composable
private fun CpuCard(sample: ResourceSample) {
    val percent = remember { NumberFormat.getPercentInstance() }
    SettingsGroup(
        stringResource(R.string.resources_cpu_group),
        info = stringResource(R.string.resources_cpu_info),
    ) {
        item {
            WmRow(
                title = stringResource(
                    R.string.resources_cpu_total,
                    DateUtils.formatElapsedTime(sample.processCpuMs / 1000),
                    DateUtils.formatElapsedTime(sample.uptimeMs / 1000),
                ),
                icon = Icons.Outlined.Timer,
            )
        }
        for (group in sample.groups) {
            item {
                WmRow(
                    title = stringResource(titleOf(group.group)),
                    subtitle = listOfNotNull(
                        group.livePercent?.let { stringResource(R.string.resources_cpu_live, percent.format(it / 100.0)) },
                        group.topThreads.joinToString(", ").ifEmpty { null },
                    ).joinToString(" · "),
                    icon = iconOf(group.group),
                    trailing = {
                        Text(
                            DateUtils.formatElapsedTime(group.totalMs / 1000),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    trailingOnTitleLine = true,
                )
            }
        }
    }
}

@Composable
private fun RunningCard(services: List<String>) {
    SettingsGroup(
        stringResource(R.string.resources_running_group),
        info = stringResource(R.string.resources_running_info),
    ) {
        if (services.isEmpty()) item { WmRow(title = stringResource(R.string.resources_running_none)) }
        for (service in services) {
            item { WmRow(title = serviceTitle(service), icon = Icons.Outlined.Speed) }
        }
    }
}

@Composable
private fun CachesCard(caches: List<ResourceProbe.Cache>, onNavigate: (String) -> Unit) {
    SettingsGroup(
        stringResource(R.string.resources_caches_group),
        info = stringResource(R.string.resources_caches_info),
    ) {
        caches.forEachIndexed { i, cache ->
            item {
                WmRow(
                    title = stringResource(
                        when (i) {
                            0 -> R.string.resources_cache_app
                            1 -> R.string.resources_cache_code
                            else -> R.string.resources_cache_external
                        },
                    ),
                    icon = Icons.Outlined.Cached,
                    trailing = { Text(formatBytes(cache.bytes), style = MaterialTheme.typography.labelLarge) },
                    trailingOnTitleLine = true,
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.resources_open_storage),
                icon = Icons.Outlined.PieChart,
                onClick = { onNavigate("storage") },
            )
        }
    }
}

@Composable
private fun BatteryCard(context: Context, onNavigate: (String) -> Unit) {
    SettingsGroup(
        stringResource(R.string.resources_battery_group),
        info = stringResource(R.string.resources_battery_info),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.resources_battery_open),
                icon = Icons.Outlined.BatterySaver,
                onClick = { openBatteryUsage(context) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.resources_power_saving_link),
                subtitle = stringResource(R.string.resources_power_saving_link_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                onClick = { onNavigate(POWER_SAVING_ROUTE) },
            )
        }
    }
}

/** The system's battery page, or this app's App info page where a phone has no such screen. */
private fun openBatteryUsage(context: Context) {
    val usage = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(usage)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@StringRes
private fun titleOf(kind: ModelKind): Int = when (kind) {
    ModelKind.LANGUAGE_MODEL -> R.string.resources_model_llm
    ModelKind.VOICE_TYPING -> R.string.resources_model_speech
    ModelKind.PUNCTUATION -> R.string.resources_model_punctuation
    ModelKind.NOISE_REDUCTION -> R.string.resources_model_denoise
    ModelKind.END_OF_SPEECH -> R.string.resources_model_smart_turn
    ModelKind.READ_ALOUD -> R.string.resources_model_read_aloud
    ModelKind.TRANSLATION -> R.string.resources_model_translate
    ModelKind.TEXT_SCANNING -> R.string.resources_model_ocr
    ModelKind.GRAMMAR -> R.string.resources_model_grammar
    ModelKind.GLIDE -> R.string.resources_model_glide
    ModelKind.DIACRITICS -> R.string.resources_model_diacritics
}

private fun iconOf(kind: ModelKind): ImageVector = when (kind) {
    ModelKind.LANGUAGE_MODEL -> Icons.Outlined.Psychology
    ModelKind.VOICE_TYPING, ModelKind.PUNCTUATION, ModelKind.NOISE_REDUCTION, ModelKind.END_OF_SPEECH ->
        Icons.Outlined.Mic
    ModelKind.READ_ALOUD -> Icons.Outlined.RecordVoiceOver
    ModelKind.TRANSLATION -> Icons.Outlined.Translate
    ModelKind.TEXT_SCANNING -> Icons.Outlined.DocumentScanner
    ModelKind.GRAMMAR -> Icons.Outlined.Spellcheck
    ModelKind.GLIDE -> Icons.Outlined.Gesture
    ModelKind.DIACRITICS -> Icons.Outlined.Notes
}

/** Where a model is set up, switched off or deleted. Null where there is no one place for it. */
internal fun manageRouteOf(kind: ModelKind): String? = when (kind) {
    ModelKind.LANGUAGE_MODEL -> "ai"
    ModelKind.VOICE_TYPING, ModelKind.PUNCTUATION, ModelKind.NOISE_REDUCTION, ModelKind.END_OF_SPEECH,
    ModelKind.READ_ALOUD -> "voice"
    ModelKind.TRANSLATION -> "tool/TRANSLATE"
    ModelKind.TEXT_SCANNING -> "tool/OCR"
    ModelKind.GRAMMAR -> "tool/GRAMMAR"
    ModelKind.GLIDE -> "storage/glide_models"
    ModelKind.DIACRITICS -> null
}

@StringRes
private fun titleOf(group: ThreadGroup): Int = when (group) {
    ThreadGroup.MAIN -> R.string.resources_cpu_main
    ThreadGroup.DRAWING -> R.string.resources_cpu_drawing
    ThreadGroup.MODELS -> R.string.resources_cpu_models
    ThreadGroup.BACKGROUND -> R.string.resources_cpu_background
    ThreadGroup.NETWORK -> R.string.resources_cpu_network
    ThreadGroup.RUNTIME -> R.string.resources_cpu_runtime
    ThreadGroup.OTHER -> R.string.resources_cpu_other
}

private fun iconOf(group: ThreadGroup): ImageVector = when (group) {
    ThreadGroup.MODELS -> Icons.Outlined.Memory
    ThreadGroup.DRAWING -> Icons.Outlined.GraphicEq
    else -> Icons.Outlined.Speed
}

@Composable
private fun serviceTitle(className: String): String = when (className.substringAfterLast('.')) {
    "WMKeyboardService" -> stringResource(R.string.resources_service_keyboard)
    "HarperSpellCheckerService" -> stringResource(R.string.resources_service_spell)
    "ScreenContextService" -> stringResource(R.string.resources_service_screen)
    else -> humanServiceName(className)
}

@Composable
private fun processTitle(name: String): String = when (name) {
    "imagegen" -> stringResource(R.string.resources_model_imagegen)
    "semantic" -> stringResource(R.string.resources_model_embedding)
    else -> name
}

private const val SAMPLE_MS = 2_000L
private const val UNLOAD_SETTLE_MS = 400L
private const val POWER_SAVING_ROUTE = "tool/POWER_SAVING"

private val MemoryNative = Color(0xFF7E57C2)
private val MemoryJava = Color(0xFF42A5F5)
private val MemoryGraphics = Color(0xFF26A69A)
private val MemoryCode = Color(0xFF78909C)
private val MemoryOther = Color(0xFFBDBDBD)
