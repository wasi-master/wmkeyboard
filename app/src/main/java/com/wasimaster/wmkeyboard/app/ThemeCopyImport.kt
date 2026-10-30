package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.docscan.DocScanNative
import com.wasimaster.wmkeyboard.core.docscan.ThemeCopier
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Below this the theme redrawn over the picture misses enough of it to say so. */
private const val LOW_CONFIDENCE = 0.7f

/** Bigger pictures gain nothing: the copier works at 1440 px across at most. */
private const val MAX_PICTURE_SIDE = 4096

/**
 * [theme] without what [measured] states about sizes. The label size, label weight
 * and tool width belong to the theme editor's Layout group, whose switch reads as
 * on when any field of it is set, so they wait until the sizes are asked for and
 * the group can be filled whole.
 */
private class CopiedTheme(val theme: ThemeSpec, val measured: ThemeSpec, val sizing: CopiedSizing?, val files: List<File>)

/**
 * Picks a screenshot of a keyboard, reads a theme off it, and shows the result
 * before anything is saved. The copier is in the document scanner's on-demand
 * library, for the OpenCV in it, so the flow starts with its download where it
 * has not been fetched.
 */
@Composable
internal fun ThemeCopyDialogs(
    repository: SettingsRepository,
    settings: LiveSettings,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val module by DocScanNative.moduleState.collectAsState()
    val configuration = LocalConfiguration.current
    var picked by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<CopiedTheme?>(null) }
    // The width the theme will be used at, whichever way up the phone is now.
    val widthDp = configuration.smallestScreenWidthDp.toFloat()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { readTheme(context, uri, widthDp) }.getOrNull()
            }
            if (result == null) {
                onMessage(context.getString(R.string.theme_copy_not_found))
                onDismiss()
            } else {
                copied = result
            }
        }
    }
    if (module != ModuleState.Installed) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.theme_copy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.theme_copy_needs_library))
                    DocScanModuleBanner(settings, builtIn = true)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
        return
    }
    LaunchedEffect(Unit) {
        if (!picked) {
            picked = true
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    copied?.let { result ->
        var withSizes by remember { mutableStateOf(false) }
        val current = settings.value
        val density = LocalDensity.current
        val navigationBarDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value }
        AlertDialog(
            onDismissRequest = {
                result.files.forEach { it.delete() }
                onDismiss()
            },
            title = { Text(stringResource(R.string.theme_copy_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ThemePreview(if (withSizes) result.withSizes(current) else result.theme, animatedBadge = false)
                    Spacer(Modifier.height(12.dp))
                    if ((result.sizing?.confidence ?: 0f) < LOW_CONFIDENCE) {
                        Text(stringResource(R.string.theme_copy_low_confidence))
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(stringResource(R.string.theme_copy_body))
                    if (result.sizing != null && !result.sizing.landscape) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = withSizes, onCheckedChange = { withSizes = it })
                            Text(stringResource(R.string.theme_copy_sizes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val sizing = result.sizing
                        val theme = if (withSizes) result.withSizes(current) else result.theme
                        repository.upsertCustomTheme(theme)
                        if (withSizes && sizing != null) repository.applyCopiedSizes(sizing, navigationBarDp)
                        // Saved, not switched to, like the other converted themes.
                        onMessage(context.getString(R.string.theme_copy_done))
                        onDismiss()
                    }
                }) { Text(stringResource(CommonR.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    result.files.forEach { it.delete() }
                    onDismiss()
                }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

private fun readTheme(context: Context, uri: Uri, widthDp: Float): CopiedTheme? {
    val picture = decodePicture(context, uri) ?: return null
    val output = ThemeCopier.copy(picture, widthDp) ?: return null
    val id = "custom_${System.currentTimeMillis()}"
    val dir = themeImagesDir(context)
    val files = mutableListOf<File>()
    fun save(name: String, format: Bitmap.CompressFormat, extension: String): String? {
        val image = output.images[name] ?: return null
        val file = File(dir, "${id}_copied_$name.$extension")
        file.outputStream().use { image.compress(format, 92, it) }
        files += file
        return file.absolutePath
    }
    val theme = ThemeCodec.decode(output.json) ?: return null
    val measured = theme.copy(
        id = id,
        name = context.getString(R.string.theme_copy_default_name),
        backgroundImage = save("backgroundImage", Bitmap.CompressFormat.JPEG, "jpg"),
        keyTexture = save("keyTexture", Bitmap.CompressFormat.PNG, "png"),
    )
    return CopiedTheme(
        measured.copy(fontScale = null, boldKeyLabels = null, toolWidthDp = null),
        measured,
        CopiedSizing.parse(output.json),
        files,
    )
}

private fun decodePicture(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.requireInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.requireInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
}

/**
 * The sizes a theme can pin, so they leave with it when another theme is picked.
 * Every field of the Layout group gets a value, from the picture where it has
 * one and from the settings where it does not, the way the editor's switch does.
 */
private fun CopiedTheme.withSizes(current: KeyboardSettings): ThemeSpec {
    val sizing = sizing ?: return theme
    return theme.copy(
        toolWidthDp = measured.toolWidthDp ?: current.toolbarBehavior.toolWidthDp,
        toolbarHeightDp = sizing.toolbarHeightDp.takeIf { it >= 32f }?.roundToInt()?.coerceAtMost(80) ?: current.toolbarHeightDp,
        popupHeightDp = current.popup.onKeyHeightDp,
        popupFloatingHeightDp = current.popup.floatingHeightDp,
        keyHeightDp = sizing.keyHeightDp.roundToInt().coerceIn(SettingsRepository.KEY_HEIGHT_MIN_DP, SettingsRepository.KEY_HEIGHT_MAX_DP),
        keyGapScale = sizing.keyGapScale.coerceIn(0f, 2f),
        sidePadScale = sizing.sidePadLeftScale.takeIf { it == sizing.sidePadRightScale },
        sidePadLeftScale = sizing.sidePadLeftScale,
        sidePadRightScale = sizing.sidePadRightScale,
        fontScale = measured.fontScale ?: current.fontScale,
        boldKeyLabels = measured.boldKeyLabels ?: current.accessibility.boldLabels,
        hintFontScale = current.layoutBehavior.hintFontScale,
        gestureTrailWidthDp = current.gesture.trailWidthDp,
        gestureTrailOpacity = current.gesture.trailOpacity,
    )
}

/**
 * The sizes no theme can hold: whether there is a number row and how tall, the
 * bottom row, and the room under the keys. The picture's bottom gap includes the
 * navigation bar of the phone it came from, and this phone draws its own.
 */
private suspend fun SettingsRepository.applyCopiedSizes(sizing: CopiedSizing, navigationBarDp: Float) {
    setNumberRow(sizing.numberRow)
    if (sizing.numberRow) setNumberRowHeightDp(sizing.numberRowHeightDp.roundToInt())
    setBottomRowHeightDp(sizing.bottomRowHeightDp.roundToInt())
    if (sizing.fullScreenshot) setBottomPaddingDp((sizing.bottomGapDp - navigationBarDp).roundToInt().coerceAtLeast(0))
}
