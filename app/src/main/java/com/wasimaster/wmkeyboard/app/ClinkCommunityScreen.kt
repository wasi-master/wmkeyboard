package com.wasimaster.wmkeyboard.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.ClinkCommunity
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The preview of one item from Clink's community page, opened by its `clink://community/<id>` link. */
internal fun clinkCommunityRoute(itemId: String): String = "clink_community/$itemId"

private sealed interface ClinkItemLoad {
    data object Loading : ClinkItemLoad
    data class Found(val item: ClinkCommunity.Item, val picture: ImageBitmap?) : ClinkItemLoad
    data object NotFound : ClinkItemLoad
    data class Failed(val error: IOException) : ClinkItemLoad
}

/**
 * What an "Open in Clink" link points at, and the way its file comes in.
 *
 * The item is looked up in the community page's public list, which is all
 * that answers without a Clink account; see [ClinkCommunity]. The lookup goes
 * to the site the link was pressed on and nowhere else. The file itself is the
 * user's to bring: exported from Clink, then picked here, where it goes
 * through the same import as any other file.
 */
@Composable
internal fun ClinkCommunityScreen(itemId: String, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<ClinkItemLoad>(ClinkItemLoad.Loading) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(OpenDocumentAt(PickerPlaces.DOWNLOADS_IMPORT)) { uri ->
        if (uri != null) {
            PickerPlaces.remember(context, PickerPlaces.DOWNLOADS_IMPORT, uri)
            picked = uri
        }
    }

    fun fetch() {
        load = ClinkItemLoad.Loading
        scope.launch { load = withContext(Dispatchers.IO) { findClinkItem(itemId) } }
    }
    LaunchedEffect(itemId) { fetch() }

    when (val current = load) {
        ClinkItemLoad.Loading -> SettingsGroup {
            item { WmRow(title = stringResource(R.string.clink_community_loading)) }
        }

        is ClinkItemLoad.Found -> {
            current.picture?.let { picture ->
                Image(
                    bitmap = picture,
                    contentDescription = current.item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(8.dp))
            }
            val item = current.item
            SettingsGroup {
                item {
                    WmRow(
                        title = item.title.ifEmpty { stringResource(R.string.clink_community_untitled) },
                        subtitle = stringResource(
                            R.string.clink_community_kind_by,
                            clinkKindLabel(item.kind),
                            item.author.ifEmpty { stringResource(R.string.clink_community_unknown_author) },
                        ),
                    )
                }
                if (item.description.isNotEmpty()) {
                    item { WmRow(title = item.description) }
                }
                if (!item.importable) {
                    item {
                        WmRow(
                            title = stringResource(R.string.clink_community_not_importable),
                            icon = Icons.Outlined.Info,
                        )
                    }
                }
            }
        }

        ClinkItemLoad.NotFound -> SettingsGroup {
            item { WmRow(title = stringResource(R.string.clink_community_not_found)) }
        }

        is ClinkItemLoad.Failed -> SettingsGroup {
            item { WmRow(title = ToolHttp.friendlyMessage(context, current.error)) }
            item { WmRow(title = stringResource(CommonR.string.common_retry), onClick = { fetch() }) }
        }
    }

    // The way in, whatever the lookup made of the link: a file exported from
    // Clink imports the same whether or not its preview could be found.
    SettingsGroup(title = stringResource(R.string.clink_community_file_group)) {
        item { WmRow(title = stringResource(R.string.clink_community_file_body)) }
        item {
            WmRow(
                title = stringResource(R.string.clink_community_import_title),
                subtitle = stringResource(R.string.clink_community_import_subtitle),
                icon = Icons.Outlined.FileDownload,
                onClick = { pick.launch(arrayOf("*/*")) },
            )
        }
        item {
            WmRow(
                title = stringResource(R.string.clink_community_open_page),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ClinkCommunity.PAGE_URL)))
                    } catch (_: ActivityNotFoundException) {
                        // No browser: the row does nothing, as a link in a page would.
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    picked?.let { uri -> ImportFileDialog(repository, uri) { picked = null } }
}

/** Pages through the public list, newest first, until [itemId] turns up or the list ends. */
private fun findClinkItem(itemId: String): ClinkItemLoad = try {
    var offset = 0
    var pages = 0
    var outcome: ClinkItemLoad = ClinkItemLoad.NotFound
    while (pages++ < ClinkCommunity.MAX_PAGES) {
        val text = ToolHttp.get(ClinkCommunity.listUrl(offset), source = NetSource.LINK_IMPORT)
        val page = ClinkCommunity.parsePage(text) ?: break
        val item = page.items.firstOrNull { it.id == itemId }
        if (item != null) {
            val picture = ClinkCommunity.previewBytes(item.preview)
                ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                ?.asImageBitmap()
            outcome = ClinkItemLoad.Found(item, picture)
            break
        }
        if (!page.hasMore || page.items.isEmpty()) break
        offset += page.items.size
    }
    outcome
} catch (e: IOException) {
    ClinkItemLoad.Failed(e)
}

@Composable
private fun clinkKindLabel(kind: String): String = when (kind.lowercase()) {
    "theme", "aesthetic" -> stringResource(R.string.clink_community_kind_theme)
    "layout" -> stringResource(R.string.clink_community_kind_layout)
    "sound" -> stringResource(R.string.clink_community_kind_sound)
    "plugin" -> stringResource(R.string.clink_community_kind_plugin)
    "action" -> stringResource(R.string.clink_community_kind_action)
    else -> kind.replaceFirstChar { it.uppercase() }.ifEmpty { stringResource(R.string.clink_community_kind_item) }
}
