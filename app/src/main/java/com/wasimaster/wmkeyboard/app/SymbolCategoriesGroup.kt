package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.SymbolCatalog
import kotlinx.coroutines.launch

/** The symbols tool's categories, each opening a box of its symbols to change (#1038). */
@Composable
internal fun SymbolCategoriesGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val overrides = settings.watch { it.symbolOverrides }
    var editing by remember { mutableStateOf<SymbolCatalog.SymbolCategory?>(null) }
    SettingsGroup(stringResource(R.string.tooldetail_symbols_edit_group), level = SettingLevel.ADVANCED) {
        for (category in SymbolCatalog.categories) {
            item(key = "symbols:${category.id}") {
                WmRow(
                    title = stringResource(category.nameRes),
                    subtitle = overrides[category.id] ?: category.symbols.joinToString(" "),
                    onClick = { editing = category },
                )
            }
        }
    }
    editing?.let { category ->
        var text by remember(category.id) {
            mutableStateOf(overrides[category.id] ?: category.symbols.joinToString(" "))
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(category.nameRes)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    supportingText = { Text(stringResource(R.string.tooldetail_symbols_edit_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.setSymbolOverride(category.id, text) }
                        editing = null
                    },
                ) { Text(stringResource(com.wasimaster.wmkeyboard.core.common.R.string.common_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.setSymbolOverride(category.id, "") }
                        editing = null
                    },
                ) { Text(stringResource(R.string.tooldetail_symbols_edit_reset)) }
            },
        )
    }
}
