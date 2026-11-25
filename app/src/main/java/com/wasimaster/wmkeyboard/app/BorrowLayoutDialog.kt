package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.findLayout
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A layout of another language, offered to [LanguageDef] on its detail
 * screen (#464): QWERTZ for English, AZERTY for Dutch, Colemak for German, or
 * a layout the user made for another language.
 */
internal class BorrowableLayout(val id: String, val name: String, val language: LanguageDef) {
    /** Layout name, language endonym and English name, lowercased once for the search box. */
    val searchKey: String = "$name\n${language.displayName}\n${language.englishName}".lowercase()
}

/**
 * Every layout [target] could type on, from the other languages that write its
 * script: the user's own layouts first, from [custom], then the shipped ones in
 * shipped order.
 *
 * A layout belongs to one language, since its language is what picks the
 * dictionary, so a layout of one's own wanted for English and Portuguese both
 * is two copies; this list is how the second one is made in one pick rather
 * than by duplicating it in the editor and changing its language there.
 * Edited shipped layouts are left to their shipped entry, which already copies
 * the edit (see [borrowLayout]).
 *
 * Same script only: the language picks the dictionary, autocorrect and shift
 * rules, and an English dictionary on a Cyrillic grid predicts nothing. Keyman
 * grids are left out because their rules decide what a key types for their own
 * language, and so are the built-ins with a composer of their own (Avro, the
 * phonetic boards), which read a scheme keyed to their language. A layout the
 * language already lists is on its shelf and needs no copy.
 *
 * Reads only the index for the asset layouts, so building the list parses nothing.
 */
internal fun borrowableLayouts(target: LanguageDef, custom: List<LayoutSpec> = emptyList()): List<BorrowableLayout> {
    val own = target.layoutIds.toSet()
    val mine = custom
        .filter {
            !isShippedLayoutId(it.id) && !it.secondary && it.composer == null && it.keyman == null &&
                it.inputMethod == null && it.name.isNotEmpty() && it.langId.isNotEmpty()
        }
        .map { Triple(it.id, it.name, it.langId) }
    val builtIns = BuiltInLayouts.all
        .filter { !it.secondary && it.composer == null && it.keyman == null }
        .map { Triple(it.id, it.name, it.langId) }
    val assets = AssetLayouts.index
        .filter { it.keyman == null && it.name.isNotEmpty() }
        .map { Triple(it.id, it.name, it.langId) }
    return (mine + builtIns + assets).mapNotNull { (id, name, langId) ->
        if (langId == target.id || id in own) return@mapNotNull null
        val language = LanguageRegistry.byId(langId)
        if (language.id == target.id || language.script != target.script) return@mapNotNull null
        BorrowableLayout(id, name, language)
    }
}

/**
 * The name a borrowed layout is stored under: its own name, with the language it
 * came from beside it unless the name already says, so a German "QWERTY" never
 * sits on the English shelf looking like the English one.
 */
private fun borrowedName(layout: BorrowableLayout): String =
    if ('(' in layout.name) layout.name else "${layout.name} (${layout.language.englishName})"

/**
 * Copies [picked] to a layout of the user's own in [target]'s language and
 * switches it on. An earlier copy of the same layout is switched on instead of
 * copied twice. Reads the grid off the main thread: an asset layout is a file.
 *
 * Starts from whatever [findLayout] answers, so an edit the user made to the
 * shipped grid comes along.
 */
internal fun borrowLayout(
    scope: CoroutineScope,
    repository: SettingsRepository,
    settings: LiveSettings,
    target: LanguageDef,
    picked: BorrowableLayout,
    onDone: (name: String) -> Unit,
) {
    scope.launch {
        val name = borrowedName(picked)
        val current = settings.value
        val existing = current.customLayouts.firstOrNull {
            !isShippedLayoutId(it.id) && !it.secondary && it.langId == target.id && it.name == name
        }
        val id = existing?.id ?: run {
            val source: LayoutSpec = withContext(Dispatchers.IO) {
                findLayout(current.customLayouts, picked.id)
            } ?: return@launch
            val id = "custom_${System.currentTimeMillis()}"
            repository.upsertCustomLayout(source.copy(id = id, name = name, langId = target.id))
            id
        }
        repository.setEnabledLayoutIds((settings.value.enabledLayoutIds + id).distinct())
        onDone(name)
    }
}

/**
 * The searchable list behind "Use a layout from another language". Each row is
 * a layout name with its language under it; the search matches either, so
 * "qwertz", "german" and "deutsch" all find QWERTZ.
 */
@Composable
internal fun BorrowLayoutDialog(
    target: LanguageDef,
    custom: List<LayoutSpec>,
    onPick: (BorrowableLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Keyed on the generation too: before the asset index is read the list
    // holds only the built-ins, and must not stay that way.
    val all = remember(target, custom, AssetLayouts.generation) { borrowableLayouts(target, custom) }
    val needle = query.trim().lowercase()
    val shown = remember(all, needle) {
        if (needle.isEmpty()) all else all.filter { needle in it.searchKey }
    }
    val list = rememberLazyListState()
    val rail = rememberScrollRailState(list)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages_borrow_layout_title)) },
        text = {
            Column {
                Text(stringResource(R.string.languages_borrow_layout_body, target.englishName))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.languages_more_layouts_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    CaptionText(
                        if (all.isEmpty()) {
                            stringResource(R.string.languages_borrow_layout_none, target.englishName)
                        } else {
                            stringResource(R.string.languages_more_layouts_search_empty, query.trim())
                        },
                    )
                } else {
                    ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 360.dp)) { rows ->
                        LazyColumn(state = list, modifier = rows) {
                            items(shown, key = { it.id }) { layout ->
                                WmRow(
                                    title = layout.name,
                                    subtitle = layout.language.displayName,
                                    onClick = { onPick(layout) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
