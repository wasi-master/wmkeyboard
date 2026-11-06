package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The camera's modes (#1156): the photo camera and the three scanners, each
 * still a tool of its own, reached from one another by a rail down the side.
 * [panel] is the panel the mode is; the document scanner has none, since it
 * runs as an activity over the keyboard.
 */
internal enum class CameraMode(val tool: ToolbarTool, val panel: PanelMode?) {
    PHOTO(ToolbarTool.CAMERA, PanelMode.CAMERA),
    QR(ToolbarTool.QR_SCAN, PanelMode.QR_SCAN),
    TEXT(ToolbarTool.OCR, PanelMode.OCR),
    DOCUMENT(ToolbarTool.DOC_SCAN, null),
    ;

    companion object {
        /**
         * The modes the rail offers: those whose tool this build has and the
         * user has not turned off. The mode on screen always stays, so the
         * rail never loses the place it is showing.
         */
        fun available(
            enabled: Collection<ToolbarTool>,
            current: CameraMode,
            supported: (ToolbarTool) -> Boolean = ::isSupportedTool,
        ): List<CameraMode> = entries.filter { it == current || (it.tool in enabled && supported(it.tool)) }
    }
}

/**
 * Whether the rail goes over the camera now: the setting is on, the panel is
 * the plain camera or scanner rather than one opened on a clip's picture or
 * from search for a photo, and there is somewhere else to go.
 */
internal fun cameraModeRailShown(state: KeyboardUiState, modes: List<CameraMode>): Boolean =
    state.settings.camera.modeRail && !state.cameraSearchOnly && state.cameraImage == null &&
        state.ocrImage == null && modes.size > 1

/**
 * [content], the panel of the [current] mode, with the mode rail over its
 * right edge. A tap on a panel mode opens that panel in place of this one;
 * a tap on Document starts the document scanner through [onTool], the same
 * way its own tool does.
 */
@Composable
internal fun CameraModeHost(
    state: KeyboardUiState,
    current: CameraMode,
    onPanelChange: (PanelMode) -> Unit,
    onTool: (ToolbarTool) -> Unit,
    content: @Composable () -> Unit,
) {
    val modes = CameraMode.available(state.settings.enabledTools, current)
    if (!cameraModeRailShown(state, modes)) {
        content()
        return
    }
    val feedback = LocalKeyPressFeedback.current
    Box {
        content()
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (mode in modes) {
                val label = if (mode == CameraMode.PHOTO) {
                    stringResource(R.string.ime_camera_mode_photo)
                } else {
                    stringResource(toolLabelRes(mode.tool))
                }
                val on = mode == current
                IconHint(label) {
                    IconButton(
                        onClick = {
                            if (on) return@IconButton
                            feedback()
                            val panel = mode.panel
                            if (panel != null) onPanelChange(panel) else onTool(mode.tool)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (on) Color.White.copy(alpha = 0.9f) else Color.Transparent)
                            .semantics { selected = on },
                    ) {
                        Icon(
                            toolIcon(mode.tool),
                            contentDescription = label,
                            modifier = Modifier.size(18.dp),
                            tint = if (on) Color.Black else Color.White,
                        )
                    }
                }
            }
        }
    }
}
