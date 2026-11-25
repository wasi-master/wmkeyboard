package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Memory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.localllm.NpuRuntime
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NpuModelBuild
import com.wasimaster.wmkeyboard.core.modules.NpuModelDownloads
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import java.io.File

/**
 * Compute for one single LiteRT model that has an NPU build for this phone's
 * chip (Whisper Tiny, search by meaning's text encoder): CPU or NPU. The
 * choice is the download: NPU fetches this chip's build (and the NPU runtime
 * that on-device AI uses, after Qualcomm's licence on a build that downloads
 * it), CPU deletes the build again. While it is on the phone the model runs
 * on the NPU, unless it failed there before ([failed]), when the CPU runs it
 * until the next app or system update gives the NPU another try.
 */
@Composable
internal fun NpuBuildChoice(
    @StringRes title: Int,
    info: String,
    build: NpuModelBuild,
    target: File,
    failed: Boolean,
    settings: LiveSettings,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val states by NpuModelDownloads.states.collectAsState()
    // A build deleted with its model (Whisper's Delete) leaves a stale Downloaded behind.
    val status = states[target.path]?.takeUnless { it is NpuModelDownloads.Status.Downloaded && !target.isFile }
        ?: if (target.isFile) NpuModelDownloads.Status.Downloaded else NpuModelDownloads.Status.NotDownloaded
    val decide = rememberDownloadDecision(settings)
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var askQualcomm by remember { mutableStateOf(false) }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    fun requestRuntime() {
        // On Play the runtime is a module of its own; on a build that
        // downloads libraries it is this chip's gates. Both start now rather
        // than at the first use.
        if (NativeDelivery.gate(NpuSupport.GATE) != null) {
            NpuSupport.deliveryGates(build.soc).forEach { NativeDelivery.gate(it)?.requestInstall() }
        } else {
            FeatureModules.npu.requestInstall()
        }
    }

    fun download() {
        when (decide()) {
            MeteredDecision.ALLOWED -> {
                requestRuntime()
                onStart()
            }
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    val npu = status is NpuModelDownloads.Status.Downloading || status is NpuModelDownloads.Status.Downloaded
    val runtimeFailure = remember(status) { if (npu) NpuRuntime.failure(context, build.soc) else null }
    val subtitle = when {
        !npu -> stringResource(R.string.npu_build_subtitle_cpu)
        failed -> stringResource(R.string.npu_build_subtitle_failed)
        else -> stringResource(R.string.npu_build_subtitle_npu, build.soc)
    }
    Column {
        ChoiceSetting(
            title = title,
            subtitle = subtitle,
            info = info,
            options = listOf(
                false to stringResource(R.string.npu_build_cpu),
                true to stringResource(R.string.npu_build_npu, size(build.keptBytes)),
            ),
            selected = npu,
            default = false,
            detail = { choice -> ChoiceDetail(icon = if (choice) Icons.Outlined.Speed else Icons.Outlined.Memory) },
        ) { pickNpu ->
            if (!pickNpu) {
                NpuModelDownloads.delete(target)
                return@ChoiceSetting
            }
            // A build that downloads libraries fetches a Snapdragon's runtime
            // from Qualcomm's own repository, under Qualcomm's licence: the
            // same question Compute = NPU asks for on-device AI.
            if (NativeDelivery.gate(NpuSupport.GATE) != null && NpuSupport.qnnSoc != null &&
                !NativeConsent.has(NativeConsent.QUALCOMM)
            ) {
                askQualcomm = true
                return@ChoiceSetting
            }
            download()
        }
        when {
            status is NpuModelDownloads.Status.Downloading -> StateBanner(
                stringResource(R.string.npu_build_downloading, size(status.bytes), size(status.total)),
                action = stringResource(CommonR.string.common_cancel),
                onAction = { NpuModelDownloads.delete(target) },
            )
            status is NpuModelDownloads.Status.Failed -> StateBanner(
                stringResource(status.messageRes),
                action = stringResource(CommonR.string.common_retry),
                tone = BannerTone.WARNING,
                onAction = { download() },
            )
            runtimeFailure != null -> StateBanner(runtimeFailure, tone = BannerTone.WARNING)
        }
    }
    if (askQualcomm) {
        QualcommLicenceDialog { accepted ->
            askQualcomm = false
            if (accepted) download()
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.npu_build_metered_body, size(build.bytes)),
            onConfirm = {
                metered = false
                requestRuntime()
                onStart()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}
