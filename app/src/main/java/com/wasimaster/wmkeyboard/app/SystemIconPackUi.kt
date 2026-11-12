package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.SystemIconPack
import com.wasimaster.wmkeyboard.core.icons.SystemIconPacks
import com.wasimaster.wmkeyboard.core.icons.SystemPackIndex
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.ui.ResolvedIcon
import com.wasimaster.wmkeyboard.ime.ui.resolveSystemPackIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launcher icon packs installed on the phone (#1275), by name. Empty while
 * they are looked up and when there are none.
 */
@Composable
internal fun rememberSystemIconPacks(): List<SystemIconPack> {
    val context = LocalContext.current
    val packs by produceState(emptyList<SystemIconPack>(), context) {
        value = withContext(Dispatchers.IO) { SystemIconPacks.installed(context) }
    }
    return packs
}

/**
 * Picks one drawable out of an installed launcher icon pack (#1275): the
 * "Installed" tab of the icon picker, and the picker of the App icons screen.
 * [selected] and what [onPick] gets are `s:` sources (see
 * [SystemIconPacks.source]).
 *
 * One pack at a time, chosen by chips over a searchable grid; a pack such as
 * Arcticons draws thousands of icons. [leadFor], a package name, puts the
 * icons the pack itself gives that app first, which is usually the one wanted.
 */
@Composable
internal fun SystemPackIconGrid(
    packs: List<SystemIconPack>,
    selected: String?,
    onPick: (String) -> Unit,
    leadFor: String? = null,
) {
    val context = LocalContext.current
    if (packs.isEmpty()) {
        Text(
            stringResource(R.string.systempacks_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val picked = SystemIconPacks.parseSource(selected)
    var packName by remember(packs) {
        mutableStateOf(picked?.first?.takeIf { p -> packs.any { it.packageName == p } } ?: packs.first().packageName)
    }
    var query by remember { mutableStateOf("") }
    val index by produceState<SystemPackIndex?>(null, packName) {
        value = null
        value = withContext(Dispatchers.IO) { SystemIconPacks.index(context, packName) }
    }
    val shown = remember(index, query, leadFor) {
        val all = index?.drawables.orEmpty()
        val lead = leadFor?.let { index?.drawablesFor(it) }.orEmpty()
        val needle = query.trim().replace(' ', '_')
        val ordered = if (lead.isEmpty()) all else (lead + all).distinct()
        if (needle.isEmpty()) ordered else ordered.filter { it.contains(needle, ignoreCase = true) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (packs.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for (pack in packs) {
                    FilterChip(
                        selected = pack.packageName == packName,
                        onClick = { packName = pack.packageName },
                        label = { Text(pack.label, maxLines = 1) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.plugins_icons_picker_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            index == null -> Text(
                stringResource(R.string.systempacks_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
            index?.drawables.isNullOrEmpty() -> Text(
                stringResource(R.string.systempacks_pack_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shown.isEmpty() -> Text(stringResource(R.string.plugins_icons_picker_no_match, query.trim()))
            else -> {
                val gridState = rememberLazyGridState()
                // Opens on the icon already chosen, as the built-in tab does.
                LaunchedEffect(shown) {
                    val at = picked?.takeIf { it.first == packName }?.let { shown.indexOf(it.second) } ?: -1
                    if (at >= 0) gridState.scrollToItem(at)
                }
                val rail = rememberScrollRailState(gridState)
                ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 320.dp)) { cells ->
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(IconGridCellMinWidth),
                        modifier = cells,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(shown, key = { it }) { name ->
                            val source = SystemIconPacks.source(packName, name)
                            IconGridCell(
                                name = name.replace('_', ' '),
                                selected = source == selected,
                                onClick = { onPick(source) },
                            ) { ResolvedGlyph(rememberSystemPackIcon(source)) }
                        }
                    }
                }
            }
        }
    }
}

/** One installed pack's drawable from its `s:` [source], read off the main thread; null while it loads. */
@Composable
internal fun rememberSystemPackIcon(source: String?): ResolvedIcon? {
    val context = LocalContext.current
    val icon by produceState<ResolvedIcon?>(null, source) {
        value = source?.let { withContext(Dispatchers.IO) { resolveSystemPackIcon(context, it) } }
    }
    return icon
}

/** The name of the installed pack an `s:` [source] comes from, or null when it is gone. */
internal fun systemPackLabel(packs: List<SystemIconPack>, source: String?): String? {
    val pkg = SystemIconPacks.parseSource(source)?.first ?: return null
    return packs.firstOrNull { it.packageName == pkg }?.label
}
