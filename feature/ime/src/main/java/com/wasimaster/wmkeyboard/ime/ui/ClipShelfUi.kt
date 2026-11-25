package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R

/**
 * The clipboards the panel can show (#1542), as the switcher lists them: the
 * main clipboard first, under [mainName], then the user's named ones in their
 * order. Empty while there are no named ones, which is what keeps the
 * switcher and Move to clipboard out of the panel for everyone who never
 * makes a second clipboard.
 */
internal fun clipShelfChoices(
    named: List<com.wasimaster.wmkeyboard.core.settings.NamedClipboard>,
    mainName: String,
): List<Pair<String, String>> =
    if (named.isEmpty()) emptyList() else listOf("" to mainName) + named.map { it.id to it.name }

/**
 * The ▾ at the head of the clipboard's header (#1542): the open clipboard's
 * name, and a press lists the others to switch to.
 */
@Composable
internal fun ClipShelfButton(
    choices: List<Pair<String, String>>,
    open: String,
    counts: Map<String, Int>,
    menuOpen: MutableState<Boolean>,
    focused: Boolean,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val name = choices.firstOrNull { it.first == open }?.second ?: choices.firstOrNull()?.second.orEmpty()
    val description = stringResource(R.string.ime_clip_shelf_switch_desc, name)
    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(max = 36.dp)
                .fillMaxHeight()
                .widthIn(max = 150.dp)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                }
                .clickable { menuOpen.value = !menuOpen.value }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (menuOpen.value) {
            ClipShelfMenu(
                choices = choices,
                current = open,
                counts = counts,
                onPick = { id ->
                    menuOpen.value = false
                    onPick(id)
                },
                onManage = {
                    menuOpen.value = false
                    onManage()
                },
                onDismiss = { menuOpen.value = false },
            )
        }
    }
}

/**
 * A list of clipboards in the hold popups' style (#1542): the switcher's,
 * with each one's count and a tick on the open one, and Move to clipboard's,
 * which leaves out the clipboard the clips are already in. [onManage] adds
 * the row to the settings page where clipboards are made; null leaves it out.
 */
@Composable
internal fun ClipShelfMenu(
    choices: List<Pair<String, String>>,
    current: String?,
    counts: Map<String, Int>,
    onPick: (String) -> Unit,
    onManage: (() -> Unit)?,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val kb = LocalKbTheme.current
    ClipPopupRegistration(onDismiss)
    Popup(popupPositionProvider = rememberAboveAnchorPopup(flipBelow = true), onDismissRequest = onDismiss) {
        GlassPopupBackdrop(kb, FrostSurface.MENUS)
        Surface(
            shape = kb.menuShape(),
            color = kb.popupFill(FrostSurface.MENUS),
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(min = 200.dp, max = 320.dp)
                .frostGlass(kb, kb.menuShape(), FrostSurface.MENUS),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                if (title != null) {
                    Text(
                        title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = kb.popupText.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(choices, key = { it.first }) { (id, name) ->
                        val on = id == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) { selected = on }
                                .clickable { onPick(id) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = kb.popupText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            counts[id]?.let { count ->
                                Text(
                                    count.toString(),
                                    fontSize = 12.sp,
                                    color = kb.popupText.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (on) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = kb.accent,
                                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                                )
                            } else {
                                Spacer(Modifier.width(26.dp))
                            }
                        }
                    }
                }
                if (onManage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManage)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = kb.popupText.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.ime_clip_shelf_manage),
                            fontSize = 14.sp,
                            color = kb.popupText,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The name of the clipboard a clip from another one is in, small, in its card's footer (#1542). */
@Composable
internal fun ClipShelfLabel(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.widthIn(max = 96.dp),
    )
}
