package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.media.gallery.AlbumKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GallerySentHistory
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryStore
import com.wasimaster.wmkeyboard.core.settings.GalleryCaption
import com.wasimaster.wmkeyboard.core.settings.GalleryFallback
import com.wasimaster.wmkeyboard.core.settings.GallerySettings
import com.wasimaster.wmkeyboard.core.settings.GalleryTapAction
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.gallery.immich.ImmichClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Tools → Gallery (#708): the photo access, how the grid behaves, how a send
 * is sized and where it goes, and what of a photo stays behind. A Play build
 * has no grid (it opens Android's picker), so its browsing rows are left out.
 */
@Composable
internal fun GalleryToolSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val d = SettingsDefaults.gallery
    fun update(change: (GallerySettings) -> GallerySettings) {
        scope.launch { repository.updateGallery(change) }
    }

    if (ChannelFeatures.GALLERY_BROWSER) {
        var access by remember { mutableStateOf(GalleryStore.access(context)) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = GalleryStore.access(context) }
        val ask = rememberDisclosedPermissionRequest(PermissionDisclosures.GALLERY) { access = GalleryStore.access(context) }
        SettingsGroup(stringResource(R.string.gallery_access_group)) {
            item {
                ActionRow(
                    R.string.gallery_access_title,
                    subtitle = stringResource(
                        when (access) {
                            GalleryStore.Access.FULL -> R.string.gallery_access_full
                            GalleryStore.Access.PARTIAL -> R.string.gallery_access_partial
                            GalleryStore.Access.NONE -> R.string.gallery_access_none
                        },
                    ),
                    action = stringResource(
                        if (access == GalleryStore.Access.FULL) R.string.gallery_access_change else R.string.gallery_access_allow,
                    ),
                ) { ask() }
            }
        }
        SettingsGroup(stringResource(R.string.gallery_browse_group), level = SettingLevel.STANDARD) {
            item {
                ChoiceSetting(
                    R.string.gallery_tap_title,
                    subtitle = stringResource(R.string.gallery_tap_subtitle),
                    options = listOf(
                        GalleryTapAction.OPEN to stringResource(R.string.gallery_tap_open),
                        GalleryTapAction.SELECT to stringResource(R.string.gallery_tap_select),
                    ),
                    selected = settings.watch { it.gallery.tapAction },
                    default = d.tapAction,
                ) { v -> update { it.copy(tapAction = v) } }
            }
            item {
                ChoiceSetting(
                    R.string.gallery_columns_title,
                    subtitle = stringResource(R.string.gallery_columns_subtitle),
                    options = (GallerySettings.MIN_COLUMNS..GallerySettings.MAX_COLUMNS).map { it to it.toString() },
                    selected = settings.watch { it.gallery.columns },
                    default = d.columns,
                ) { v -> update { it.copy(columns = v) } }
            }
            item {
                ToggleSetting(
                    R.string.gallery_camera_tile_title,
                    stringResource(R.string.gallery_camera_tile_subtitle),
                    settings.watch { it.gallery.cameraTile },
                    default = d.cameraTile,
                ) { v -> update { it.copy(cameraTile = v) } }
            }
            item {
                ToggleSetting(
                    R.string.gallery_show_videos_title,
                    stringResource(R.string.gallery_show_videos_subtitle),
                    settings.watch { it.gallery.showVideos },
                    default = d.showVideos,
                ) { v -> update { it.copy(showVideos = v) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.gallery_scrubber_title,
                    stringResource(R.string.gallery_scrubber_subtitle),
                    settings.watch { it.gallery.dateScrubber },
                    default = d.dateScrubber,
                ) { v -> update { it.copy(dateScrubber = v) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.gallery_start_expanded_title,
                    stringResource(R.string.gallery_start_expanded_subtitle),
                    settings.watch { it.gallery.startExpanded },
                    default = d.startExpanded,
                ) { v -> update { it.copy(startExpanded = v) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.gallery_remember_album_title,
                    stringResource(R.string.gallery_remember_album_subtitle),
                    settings.watch { it.gallery.rememberAlbum },
                    default = d.rememberAlbum,
                ) { v -> update { it.copy(rememberAlbum = v) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.gallery_haptics_title,
                    stringResource(R.string.gallery_haptics_subtitle),
                    settings.watch { it.gallery.haptics },
                    default = d.haptics,
                ) { v -> update { it.copy(haptics = v) } }
            }
            // #970: folders left out of the gallery altogether.
            item(level = SettingLevel.ADVANCED) {
                val hidden = settings.watch { it.gallery.hiddenFolders }
                var picking by remember { mutableStateOf(false) }
                NavRow(
                    R.string.gallery_folders_title,
                    subtitle = stringResource(R.string.gallery_folders_subtitle),
                    value = if (hidden.isEmpty()) {
                        stringResource(R.string.gallery_folders_all)
                    } else {
                        context.resources.getQuantityString(R.plurals.gallery_folders_hidden, hidden.size, hidden.size)
                    },
                ) { picking = true }
                if (picking) {
                    GalleryFoldersDialog(
                        hidden = hidden,
                        onChange = { next -> update { it.copy(hiddenFolders = next) } },
                        onDismiss = { picking = false },
                    )
                }
            }
        }
    }

    ImmichSettingsGroup(settings) { change -> update(change) }

    SettingsGroup(stringResource(R.string.gallery_send_group)) {
        item {
            ChoiceSetting(
                R.string.gallery_quality_title,
                subtitle = stringResource(R.string.gallery_quality_subtitle),
                options = listOf(
                    GalleryQuality.ORIGINAL to stringResource(R.string.gallery_quality_original),
                    GalleryQuality.HD to stringResource(R.string.gallery_quality_hd),
                    GalleryQuality.STANDARD to stringResource(R.string.gallery_quality_standard),
                    GalleryQuality.SMALL to stringResource(R.string.gallery_quality_small),
                ),
                selected = settings.watch { it.gallery.quality },
                default = d.quality,
            ) { v -> update { it.copy(quality = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.gallery_max_selection_title,
                subtitle = stringResource(R.string.gallery_max_selection_subtitle),
                options = GallerySettings.SELECTION_CHOICES.map { it to it.toString() },
                selected = settings.watch { it.gallery.maxSelection }.let { n ->
                    GallerySettings.SELECTION_CHOICES.minByOrNull { kotlin.math.abs(it - n) } ?: d.maxSelection
                },
                default = d.maxSelection,
            ) { v -> update { it.copy(maxSelection = v) } }
        }
        if (ChannelFeatures.GALLERY_BROWSER) {
            item(level = SettingLevel.STANDARD) {
                ChoiceSetting(
                    R.string.gallery_caption_title,
                    subtitle = stringResource(R.string.gallery_caption_subtitle),
                    options = listOf(
                        GalleryCaption.AFTER to stringResource(R.string.gallery_caption_after),
                        GalleryCaption.BEFORE to stringResource(R.string.gallery_caption_before),
                        GalleryCaption.OFF to stringResource(R.string.gallery_caption_off),
                    ),
                    selected = settings.watch { it.gallery.caption },
                    default = d.caption,
                ) { v -> update { it.copy(caption = v) } }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.gallery_fallback_title,
                subtitle = stringResource(R.string.gallery_fallback_subtitle),
                options = listOf(
                    GalleryFallback.SHARE_TO_APP to stringResource(R.string.gallery_fallback_share),
                    GalleryFallback.CLIPBOARD to stringResource(R.string.gallery_fallback_clipboard),
                    GalleryFallback.ASK to stringResource(R.string.gallery_fallback_ask),
                ),
                selected = settings.watch { it.gallery.fallback },
                default = d.fallback,
            ) { v -> update { it.copy(fallback = v) } }
        }
        if (ChannelFeatures.GALLERY_BROWSER) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.gallery_close_after_send_title,
                    stringResource(R.string.gallery_close_after_send_subtitle),
                    settings.watch { it.gallery.closeAfterSend },
                    default = d.closeAfterSend,
                ) { v -> update { it.copy(closeAfterSend = v) } }
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.gallery_save_edited_title,
                    stringResource(R.string.gallery_save_edited_subtitle),
                    settings.watch { it.gallery.saveEdited },
                    default = d.saveEdited,
                ) { v -> update { it.copy(saveEdited = v) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.gallery_privacy_group)) {
        item {
            ToggleSetting(
                R.string.gallery_strip_location_title,
                stringResource(R.string.gallery_strip_location_subtitle),
                settings.watch { it.gallery.stripLocation },
                default = d.stripLocation,
            ) { v -> update { it.copy(stripLocation = v) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.gallery_strip_all_title,
                stringResource(R.string.gallery_strip_all_subtitle),
                settings.watch { it.gallery.stripAllMetadata },
                default = d.stripAllMetadata,
            ) { v -> update { it.copy(stripAllMetadata = v) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.gallery_random_names_title,
                stringResource(R.string.gallery_random_names_subtitle),
                settings.watch { it.gallery.randomFileNames },
                default = d.randomFileNames,
            ) { v -> update { it.copy(randomFileNames = v) } }
        }
        if (ChannelFeatures.GALLERY_BROWSER) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.gallery_history_title,
                    stringResource(R.string.gallery_history_subtitle),
                    settings.watch { it.gallery.keepHistory },
                    default = d.keepHistory,
                ) { v -> update { it.copy(keepHistory = v) } }
            }
            item(level = SettingLevel.STANDARD) {
                ActionRow(
                    R.string.gallery_history_clear_title,
                    subtitle = stringResource(R.string.gallery_history_clear_subtitle),
                    action = stringResource(CommonR.string.common_delete),
                    confirm = stringResource(R.string.gallery_history_clear_confirm),
                ) {
                    scope.launch { withContext(Dispatchers.IO) { GallerySentHistory.get(context).clear() } }
                }
            }
        }
    }
}

/**
 * The user's Immich server (#1205): its address, an API key, and a check that
 * asks the server who the key belongs to. In every build, Play's included: the
 * server is browsed in the keyboard's own panel, which needs no photo
 * permission.
 */
@Composable
private fun ImmichSettingsGroup(settings: LiveSettings, update: ((GallerySettings) -> GallerySettings) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val url = settings.watch { it.gallery.immichUrl }
    val key = settings.watch { it.gallery.immichKey }
    var checking by remember { mutableStateOf(false) }
    var result by remember(url, key) { mutableStateOf<String?>(null) }
    SettingsGroup(stringResource(R.string.gallery_immich_group), info = stringResource(R.string.gallery_immich_info)) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.gallery_immich_url_title),
                value = url,
                hint = stringResource(R.string.gallery_immich_url_hint),
                default = "",
            ) { v -> update { it.copy(immichUrl = v) } }
        }
        item {
            ApiKeyField(
                label = stringResource(R.string.gallery_immich_key_title),
                value = key,
                builtInAvailable = false,
                emptyHint = stringResource(R.string.gallery_immich_key_hint),
                wmPassTitle = "Immich",
            ) { v -> update { it.copy(immichKey = v) } }
        }
        if (url.isNotBlank() && key.isNotBlank()) {
            item {
                ActionRow(
                    R.string.gallery_immich_check_title,
                    subtitle = result ?: stringResource(R.string.gallery_immich_check_subtitle),
                    action = stringResource(R.string.gallery_immich_check_action),
                ) {
                    if (checking) return@ActionRow
                    checking = true
                    scope.launch {
                        val client = ImmichClient(url, key)
                        result = if (client.base == null) {
                            context.getString(R.string.gallery_immich_check_bad_url)
                        } else {
                            withContext(Dispatchers.IO) {
                                runCatching { client.me() }.fold(
                                    onSuccess = { me -> context.getString(R.string.gallery_immich_check_ok, me.name.ifBlank { me.email }) },
                                    onFailure = { e ->
                                        val failure = e as? ImmichClient.Failure
                                        if (failure?.refused == true) {
                                            context.getString(R.string.gallery_immich_check_refused, failure.code)
                                        } else {
                                            context.getString(R.string.gallery_immich_check_failed, e.message ?: e.javaClass.simpleName)
                                        }
                                    },
                                )
                            }
                        }
                        checking = false
                    }
                }
            }
        }
    }
}

/**
 * The phone's photo folders, each with a tick for whether the gallery shows it
 * (#970). A folder the tool cannot read now (no access) is not listed, but a
 * hidden one stays hidden.
 */
@Composable
private fun GalleryFoldersDialog(hidden: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    val folders by produceState<List<GalleryAlbum>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { GalleryStore.albums(context, videos = true, sentIds = emptyList()) }.getOrDefault(emptyList())
                .filter { it.kind == AlbumKind.BUCKET }
                .sortedBy { it.path.ifEmpty { it.name.orEmpty() }.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_folders_title)) },
        text = {
            val list = folders
            when {
                list == null -> Unit
                list.isEmpty() -> Text(stringResource(R.string.gallery_folders_none))
                else -> Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.gallery_folders_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val term = query.trim()
                    val found = list.filter {
                        term.isEmpty() || it.path.contains(term, ignoreCase = true) ||
                            it.name.orEmpty().contains(term, ignoreCase = true)
                    }
                    // By the folder at the top of each path (DCIM, Pictures,
                    // Android...), since a flat list of every app's folder is long.
                    val roots = found.groupBy { it.path.substringBefore('/').ifEmpty { it.name.orEmpty() } }.toSortedMap()
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        for ((root, inside) in roots) {
                            val isOpen = term.isNotEmpty() || root in open || inside.size == 1
                            item(key = "root:$root") {
                                val allShown = inside.none { it.id in hidden }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { open = if (root in open) open - root else open + root }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = allShown,
                                        onCheckedChange = { on ->
                                            val ids = inside.map { it.id }.toSet()
                                            onChange(if (on) hidden - ids else hidden + ids)
                                        },
                                    )
                                    Text(
                                        stringResource(R.string.gallery_folders_row, root, inside.sumOf { it.count }),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                                    )
                                    Text(if (isOpen) "▾" else "▸")
                                }
                            }
                            if (isOpen) {
                                items(inside, key = { it.id }) { folder ->
                                    val shown = folder.id !in hidden
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onChange(if (shown) hidden + folder.id else hidden - folder.id) }
                                            .padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(checked = shown, onCheckedChange = null)
                                        Text(
                                            stringResource(
                                                R.string.gallery_folders_row,
                                                folder.path.substringAfter('/', folder.name.orEmpty()).ifEmpty { folder.name.orEmpty() },
                                                folder.count,
                                            ),
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_done)) } },
    )
}
