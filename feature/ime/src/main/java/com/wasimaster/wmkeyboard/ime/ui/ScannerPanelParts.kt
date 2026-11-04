package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import android.content.Context
import android.text.format.Formatter
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.modules.ModuleGate

// What the camera scanners share: the text scanner (src/google, ML Kit's
// readers) and the QR scanner (QrScanPanel.kt, either reader).

/**
 * A scanner's reading library on a build with native delivery: [gate]'s,
 * which is ML Kit's bundled text and barcode readers ([FeatureModules.scanner])
 * unless the QR scanner reads with zxing-cpp ([FeatureModules.zxing]). Fetched
 * the first time the scanner opens. The same shape as the text scanner's pack
 * prompt, data saver asked on the button.
 */
@Composable
internal fun ScannerLibraryPrompt(state: KeyboardUiState, gate: ModuleGate = FeatureModules.scanner) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val feedback = LocalKeyPressFeedback.current
    val module by gate.state.collectAsState()
    var meteredAsked by remember { mutableStateOf(false) }
    var meteredBlocked by remember { mutableStateOf(false) }
    val size = (gate as? DownloadedModuleGate)?.missingBytes ?: 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val current = module
        if (current is ModuleState.Installing) {
            if (current.totalBytes > 0L) {
                CircularProgressIndicator(
                    progress = { (current.bytes.toFloat() / current.totalBytes).coerceIn(0f, 1f) },
                    color = kb.accent,
                )
            } else {
                CircularProgressIndicator(color = kb.accent)
            }
            Text(
                stringResource(R.string.ime_scanner_library_downloading),
                color = kb.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        val consent = (current as? ModuleState.Missing)?.consent
        Text(
            when {
                consent == NativeConsent.IMPORT -> stringResource(R.string.ime_scanner_library_import)
                consent != null -> stringResource(R.string.ime_scanner_library_consent)
                meteredBlocked -> stringResource(R.string.ime_metered_off_body)
                meteredAsked -> stringResource(R.string.ime_metered_ask_body)
                (current as? ModuleState.Missing)?.failed == true -> stringResource(OfflineBuild.library(R.string.ime_scanner_library_failed))
                else -> stringResource(R.string.ime_scanner_library_body, Formatter.formatShortFileSize(context, size))
            },
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (!meteredBlocked && consent == null) {
            ScannerPromptButton(
                Icons.Outlined.FileDownload,
                stringResource(
                    when {
                        meteredAsked -> R.string.ime_metered_allow_action
                        (current as? ModuleState.Missing)?.failed == true -> CommonR.string.common_retry
                        else -> CommonR.string.common_download
                    },
                ),
            ) {
                feedback()
                when (state.dataSaver.decide(MeteredFeature.DOWNLOADS)) {
                    MeteredDecision.ALLOWED -> gate.requestInstall()
                    MeteredDecision.BLOCKED -> meteredBlocked = true
                    MeteredDecision.ASK -> if (meteredAsked) gate.requestInstall() else meteredAsked = true
                }
            }
        }
    }
}

@Composable
internal fun ScannerPromptButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(kb.toolRadiusDp.dp))
            .background(kb.toolCircleActive)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = kb.toolCircleActiveIcon)
        Text(
            label,
            color = kb.toolCircleActiveIcon,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Re-runs [onResume] when the keyboard comes back to the foreground — the
 * camera-permission dialog lives in a trampoline activity, so the panel
 * has to re-check the permission once it regains focus.
 */
@Composable
internal fun PermissionRecheck(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** The camera-permission explainer + "Allow the camera" button. */
@Composable
internal fun CameraPermissionPrompt(message: String, onRequestPermission: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(kb.keyRadiusDp.dp))
                .background(kb.toolCircleActive)
                .pointerInput(Unit) { detectTapGestures { onRequestPermission() } }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.ime_scanner_permission_action),
                color = kb.toolCircleActiveIcon,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Back camera, else front, else null — scanners never need a lens switch. */
internal fun backOrFrontSelector(provider: ProcessCameraProvider): CameraSelector? = when {
    provider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    provider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> null
}

internal fun copyPlainText(context: Context, text: String) {
    KeyboardClipboard.copy(context, text, "scanned text")
}
