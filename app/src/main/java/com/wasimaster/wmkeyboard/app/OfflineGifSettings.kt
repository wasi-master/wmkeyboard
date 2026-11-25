package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks

/**
 * The GIF tool's packs on the device: CleverKeys' offline GIF packs and packs
 * of the user's own GIF and WebP files, which the panel offers under
 * **Offline** with no network and no API key. Each pack installed has a
 * Delete; the row under them fetches more (in the browser) and imports them,
 * a zip of pictures or loose pictures included.
 */
@Composable
internal fun OfflineGifPacksGroup() {
    val context = LocalContext.current
    OfflineGifPacks.attach(context.filesDir)
    var packs by remember { mutableStateOf(OfflineGifPacks.packs()) }
    SettingsGroup(
        stringResource(R.string.offline_gifs_group),
        info = stringResource(R.string.offline_gifs_info),
    ) {
        for (pack in packs) {
            item(key = pack.id) {
                WmRow(
                    title = pack.name,
                    supporting = {
                        Text(
                            pluralStringResource(
                                R.plurals.offline_gifs_pack_detail,
                                pack.gifCount,
                                pack.gifCount,
                                formatBytes(pack.sizeBytes),
                            ),
                        )
                    },
                    swipeActions = listOf(
                        deleteSwipeAction(stringResource(R.string.offline_gifs_delete_desc, pack.name)) {
                            OfflineGifPacks.delete(pack.id)
                            packs = OfflineGifPacks.packs()
                        },
                    ),
                    trailing = {
                        IconButton(
                            onClick = {
                                OfflineGifPacks.delete(pack.id)
                                packs = OfflineGifPacks.packs()
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.offline_gifs_delete_desc, pack.name))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        item {
            OfflineImportRow(
                title = stringResource(R.string.offline_gifs_import_title),
                subtitle = stringResource(R.string.offline_gifs_import_subtitle),
                links = listOf(
                    OfflineLink(stringResource(R.string.offline_gifs_link_label), OfflineGifPacks.PACKS_PAGE),
                ),
                gifPictures = true,
                onImported = { packs = OfflineGifPacks.packs() },
            )
        }
    }
}
