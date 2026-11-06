package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/** What an app's toolbar button shows (#1159): its name and its icon, when the icon could be read. */
class ToolbarAppFace(val label: String, val icon: ImageBitmap?)

/**
 * The apps on the toolbar (#1159), handed down from the service with the
 * other toolbar callbacks. [face] reads an app by its launcher key through
 * the app launcher's list and icon cache, null for an app no longer installed;
 * [onOpen] opens it the way the launcher panel's Open apps setting says.
 */
@Immutable
class ToolbarAppCallbacks(
    val face: suspend (String) -> ToolbarAppFace? = { null },
    val onOpen: (String) -> Unit = {},
)

/** The apps the toolbar shows after its tools and folders, in the user's order (#1159). */
internal fun toolbarApps(state: KeyboardUiState): List<String> = state.settings.launcher.toolbarApps

/**
 * One app on the toolbar (#1159), after the pinned tools and folders: its
 * icon at the size of a tool's, in the launcher panel's icon shape, with its
 * name under it when the bar shows names. A tap opens the app. An app that is
 * gone draws nothing; it comes off the bar from the launcher's hold menu or
 * the tool's settings.
 */
@Composable
internal fun PinnedAppButton(
    key: String,
    state: KeyboardUiState,
    callbacks: ToolbarAppCallbacks,
    modifier: Modifier = Modifier,
) {
    val face by produceState<ToolbarAppFace?>(null, key, callbacks) { value = callbacks.face(key) }
    val app = face ?: return
    val kb = LocalKbTheme.current
    val description = stringResource(R.string.ime_toolbar_app_desc, app.label)
    val iconSize = (kb.toolIconSizeDp + TOOLBAR_APP_ICON_EXTRA_DP).dp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = description) { callbacks.onOpen(key) }
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = app.icon
        val shape = launcherIconShape(state.settings.launcher.iconShape)
        if (icon != null) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .then(if (shape != null) Modifier.clip(shape) else Modifier),
            )
        } else {
            // An icon that would not decode: the name's first letter keeps the slot recognisable.
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Text(
                    app.label.take(1),
                    color = kb.toolbarIcon,
                    fontSize = 14.sp,
                )
            }
        }
        if (state.settings.toolbarLabels) {
            Text(
                app.label,
                fontSize = state.settings.toolbarLabelSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = kb.toolbarIcon,
                modifier = Modifier.widthIn(max = 72.dp),
            )
        }
    }
}

/** An app icon is a filled picture, a tool icon a glyph with air round it: a little more size evens them out. */
private const val TOOLBAR_APP_ICON_EXTRA_DP = 4
