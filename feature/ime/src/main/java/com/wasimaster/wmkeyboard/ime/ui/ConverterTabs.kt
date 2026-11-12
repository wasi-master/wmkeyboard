package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The unit and currency converters as two tabs of one panel (#1268), when
 * [com.wasimaster.wmkeyboard.core.settings.ToolboxSettings.converterTabs] is
 * on. They stay two tools: each opens on its own tab, and the tabs switch
 * between the two panels the way the media switch moves between GIFs and
 * stickers. Only while both tools are switched on, shipped and usable, since
 * a tab for a tool the user turned off would open it by the back door.
 */
internal fun converterTabsShown(settings: KeyboardSettings): Boolean =
    settings.toolbox.converterTabs &&
        listOf(ToolbarTool.UNIT_CONVERT, ToolbarTool.CURRENCY).all { tool ->
            tool in settings.enabledTools && isSupportedTool(tool) && isUsableTool(tool, settings)
        }

/** The two tabs, for a converter panel's header. A tab for the open panel does nothing. */
@Composable
internal fun ConverterTabs(open: PanelMode, onPanelChange: (PanelMode) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((panel, label) in listOf(
            PanelMode.UNIT_CONVERT to R.string.ime_converter_tab_units,
            PanelMode.CURRENCY to R.string.ime_tool_currency,
        )) {
            val selected = panel == open
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (selected) kb.chipActive else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab) {
                        if (!selected) onPanelChange(panel)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    color = if (selected) kb.chipActiveText else kb.chipText,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}
