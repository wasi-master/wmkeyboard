package com.wasimaster.wmkeyboard.ime.handwriting.onyx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.ime.HandwritingUi

/** Ordinary and F-Droid builds carry no Onyx SDK and always use Compose ink. */
object OnyxInkSupport {
    val deviceSupported: Boolean = false
    val runtimeAvailable: Boolean = false
}

object OnyxInkLifecycle {
    fun suspendAll() = Unit
    fun resumeAll() = Unit
    fun pulseForExternalUpdate() = Unit
}

@Composable
@Suppress("UNUSED_PARAMETER", "LongParameterList")
internal fun OnyxInkCanvas(
    modifier: Modifier,
    style: OnyxPenStyle,
    chrome: OnyxChromeRegions,
    handwriting: HandwritingUi,
    inputState: OnyxInputState,
    onStrokeStart: () -> Unit,
    onStrokeCancel: () -> Unit,
    onStroke: (HwStroke, IntSize) -> Unit,
    onStylusActivity: (Long) -> Unit,
    onFailure: () -> Unit,
) = Unit
