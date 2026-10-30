package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R

/**
 * Every import in the app on one page, for setting the keyboard up after an
 * install (#983). Links to the screens that own them rather than a second copy
 * of their buttons, which take options this page would have to learn: which
 * language a word list is for, whether a backup replaces or merges.
 */
@Composable
internal fun ImportHubScreen(onOpen: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.import_hub_files_group)) {
        item {
            OfflineImportRow(
                title = stringResource(R.string.offline_import_storage_title),
                subtitle = stringResource(R.string.offline_import_storage_subtitle),
                links = emptyList(),
            )
        }
    }
    SettingsGroup(stringResource(R.string.import_hub_screens_group)) {
        for (row in ImportHubRows) {
            item {
                NavRow(
                    row.title,
                    stringResource(row.subtitle),
                    route = row.route,
                    onClick = { onOpen(row.route) },
                )
            }
        }
    }
}

private class ImportHubRow(@StringRes val title: Int, @StringRes val subtitle: Int, val route: String)

private val ImportHubRows = listOf(
    ImportHubRow(R.string.import_hub_backup_title, R.string.import_hub_backup_subtitle, "backup"),
    ImportHubRow(R.string.import_hub_languages_title, R.string.import_hub_languages_subtitle, "languages"),
    ImportHubRow(R.string.import_hub_themes_title, R.string.import_hub_themes_subtitle, "themes"),
    ImportHubRow(R.string.import_hub_icons_title, R.string.import_hub_icons_subtitle, "icons"),
    ImportHubRow(R.string.import_hub_stickers_title, R.string.import_hub_stickers_subtitle, "sticker_packs"),
    ImportHubRow(R.string.import_hub_sounds_title, R.string.import_hub_sounds_subtitle, "keypress"),
    ImportHubRow(R.string.import_hub_vocab_title, R.string.import_hub_vocab_subtitle, "vocab/lists"),
    ImportHubRow(R.string.import_hub_plugins_title, R.string.import_hub_plugins_subtitle, "plugins"),
)
