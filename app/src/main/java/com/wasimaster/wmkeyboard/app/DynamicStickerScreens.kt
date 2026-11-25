package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.DynExactMode
import com.wasimaster.wmkeyboard.core.settings.DynGenericMode
import com.wasimaster.wmkeyboard.core.settings.DynMaxInTrayRange
import com.wasimaster.wmkeyboard.core.settings.DynMaxWordsRange
import com.wasimaster.wmkeyboard.core.settings.DynOfferOrder
import com.wasimaster.wmkeyboard.core.settings.DynStickerFeed
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynFill
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynFonts
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynKind
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynMatcher
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynPackFile
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynPackMeta
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynRenderer
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynStickerStore
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynTemplate
import com.wasimaster.wmkeyboard.core.stickers.dynamic.PackSource
import com.wasimaster.wmkeyboard.ime.dynstickers.DynRoutes
import com.wasimaster.wmkeyboard.ime.dynstickers.DynStickerFeeds
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The settings app's renderer for dynamic sticker previews: the same engine
 * the keyboard draws with, its fonts resolved the same way.
 */
internal object DynAppRender {
    @Volatile
    private var pair: Pair<DynFonts, DynRenderer>? = null

    private fun get(context: Context): Pair<DynFonts, DynRenderer> = pair ?: synchronized(this) {
        pair ?: run {
            val app = context.applicationContext
            val store = DynStickerStore.get(app)
            val fonts = DynFonts(app, store) { id -> KeyboardFonts.typeface(app, id) }
            fonts to DynRenderer(store, fonts)
        }.also { pair = it }
    }

    fun renderer(context: Context): DynRenderer = get(context).second

    /** The face a sticker names [font] by, as the renderer finds it. Blocking: a downloadable one may wait. */
    fun typeface(context: Context, font: String): android.graphics.Typeface = get(context).first.typeface(null, font)

    /** After a font was installed: faces are looked up again. */
    fun fontsChanged(context: Context) = get(context).first.clear()
}

/** The two faces the built-in templates are drawn in, pinned to one release of each. */
internal object DynStickerFonts {
    data class Pinned(val family: String, val url: String, val sha256: String, val size: Long, val author: String)

    private const val COMMIT = "83c62e30d87daa70216dab6b984d00d20d96d585"

    val DYNAPUFF = Pinned(
        family = "DynaPuff",
        url = "https://raw.githubusercontent.com/google/fonts/$COMMIT/ofl/dynapuff/DynaPuff%5Bwdth,wght%5D.ttf",
        sha256 = "c3752fc6dd8aaa46a88637f061a9a4e6ab44c02c5f0afd65528b91ae8ff6f948",
        size = 212_600L,
        author = "Toshi Omagari, Jennifer Daniel",
    )

    val ANTON = Pinned(
        family = "Anton",
        url = "https://raw.githubusercontent.com/google/fonts/$COMMIT/ofl/anton/Anton-Regular.ttf",
        sha256 = "a4ba3a92350ebb031da0cb47630ac49eb265082ca1bc0450442f4a83ab947cab",
        size = 170_812L,
        author = "Vernon Adams",
    )

    val ALL = listOf(DYNAPUFF, ANTON)

    fun installed(context: Context, family: String): Boolean =
        FontStore.get(context).textFonts().any { it.name.equals(family, ignoreCase = true) }

    /** Downloads [font] and puts it in the font library, where every template finds it by name. */
    suspend fun install(context: Context, font: Pinned): Boolean = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "dynstickers/fonts/${font.family}.ttf")
        target.parentFile?.mkdirs()
        runCatching {
            FileDownloader.fetchVerified(font.url, target, font.size, font.sha256, NetSource.DOWNLOAD_FONT)
            val result = target.inputStream().use { FontFile.import(it, FontStore.get(context), font.family, author = font.author) }
            result is FontImportResult.Imported
        }.getOrDefault(false).also {
            target.delete()
            DynAppRender.fontsChanged(context)
        }
    }
}

/**
 * Emoji › Dynamic stickers: the words being typed drawn into sticker and meme
 * templates. When and how many the sticker row offers, how they are sent,
 * the faces they draw in, the packs, the feeds the user added, and the way in
 * to the builder.
 */
@Composable
internal fun DynamicStickersScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { DynStickerStore.get(context) }
    val revision by store.revision.collectAsState()
    val packs = remember(revision) { store.packs() }
    val state = remember(revision) { store.state() }
    val enabled = settings.watch { it.gif.dynamic.enabled }
    val inTray = settings.watch { it.gif.dynamic.inTray }
    val feeds = settings.watch { it.gif.dynamic.feeds }
    val decision = rememberDownloadDecision(settings)
    var pendingNetwork by remember { mutableStateOf<(() -> Unit)?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var addingFeed by remember { mutableStateOf(false) }
    var openFeed by remember { mutableStateOf<String?>(null) }
    var newPack by remember { mutableStateOf(false) }
    var choosingFont by remember { mutableStateOf(false) }
    var busyFeeds by remember { mutableStateOf(setOf<String>()) }
    var busyFonts by remember { mutableStateOf(setOf<String>()) }
    var fontsVersion by remember { mutableStateOf(0) }

    fun online(work: () -> Unit) {
        when (decision()) {
            MeteredDecision.ALLOWED -> work()
            MeteredDecision.ASK -> pendingNetwork = work
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    fun refreshFeed(feed: DynStickerFeed, force: Boolean) {
        busyFeeds = busyFeeds + feed.id
        scope.launch {
            repository.updateDynStickerFeed(DynStickerFeeds.refresh(context, store, feed, force))
            busyFeeds = busyFeeds - feed.id
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { importDynamicStickerFile(context, store, uri) }
            Toast.makeText(context, dynImportMessage(context, result), Toast.LENGTH_LONG).show()
            if (result is DynStickerStore.ImportResult.Imported) onNavigate(DynRoutes.pack(result.pack.id))
        }
    }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.dynsticker_enabled_title,
                stringResource(R.string.dynsticker_enabled_subtitle),
                enabled,
                default = SettingsDefaults.gif.dynamic.enabled,
                info = stringResource(R.string.dynsticker_enabled_info),
            ) { scope.launch { repository.setDynStickersEnabled(it) } }
        }
        item(visible = enabled) {
            NavRow(
                title = R.string.dynsticker_new_template_title,
                subtitle = stringResource(R.string.dynsticker_new_template_subtitle),
            ) { onNavigate(DynRoutes.NEW) }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_typing_title)) {
        item(visible = enabled) {
            ToggleSetting(
                R.string.dynsticker_in_tray_title,
                stringResource(R.string.dynsticker_in_tray_subtitle),
                inTray,
                default = SettingsDefaults.gif.dynamic.inTray,
                info = stringResource(R.string.dynsticker_in_tray_info),
            ) { scope.launch { repository.setDynStickersInTray(it) } }
        }
        item(visible = enabled && inTray) {
            ToggleSetting(
                R.string.dynsticker_match_phrase_title,
                stringResource(R.string.dynsticker_match_phrase_subtitle),
                settings.watch { it.gif.dynamic.matchPhrase },
                default = SettingsDefaults.gif.dynamic.matchPhrase,
            ) { scope.launch { repository.setDynStickersMatchPhrase(it) } }
        }
        item(visible = enabled && inTray) {
            ToggleSetting(
                R.string.dynsticker_match_inside_title,
                stringResource(R.string.dynsticker_match_inside_subtitle),
                settings.watch { it.gif.dynamic.matchInside },
                default = SettingsDefaults.gif.dynamic.matchInside,
            ) { scope.launch { repository.setDynStickersMatchInside(it) } }
        }
        item(visible = enabled && inTray) {
            ToggleSetting(
                R.string.dynsticker_match_feelings_title,
                stringResource(R.string.dynsticker_match_feelings_subtitle),
                settings.watch { it.gif.dynamic.matchFeelings },
                default = SettingsDefaults.gif.dynamic.matchFeelings,
                info = stringResource(R.string.dynsticker_match_feelings_info),
            ) { scope.launch { repository.setDynStickersMatchFeelings(it) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.dynsticker_generic_title,
                options = listOf(
                    DynGenericMode.OFF to stringResource(R.string.dynsticker_generic_off),
                    DynGenericMode.AFTER_MATCH to stringResource(R.string.dynsticker_generic_after),
                    DynGenericMode.ALWAYS to stringResource(R.string.dynsticker_generic_always),
                ),
                selected = settings.watch { it.gif.dynamic.generic },
                default = SettingsDefaults.gif.dynamic.generic,
                info = stringResource(R.string.dynsticker_generic_info),
            ) { scope.launch { repository.setDynStickersGeneric(it) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.dynsticker_max_words_title,
                value = settings.watch { it.gif.dynamic.maxWords }.toFloat(),
                range = DynMaxWordsRange.first.toFloat()..DynMaxWordsRange.last.toFloat(),
                display = { n -> context.resources.getQuantityString(R.plurals.dynsticker_words, n.roundToInt(), n.roundToInt()) },
                default = SettingsDefaults.gif.dynamic.maxWords.toFloat(),
                steps = DynMaxWordsRange.last - DynMaxWordsRange.first - 1,
                info = stringResource(R.string.dynsticker_max_words_info),
            ) { scope.launch { repository.setDynStickersMaxWords(it.roundToInt()) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.dynsticker_max_in_tray_title,
                value = settings.watch { it.gif.dynamic.maxInTray }.toFloat(),
                range = DynMaxInTrayRange.first.toFloat()..DynMaxInTrayRange.last.toFloat(),
                display = { n -> n.roundToInt().toString() },
                default = SettingsDefaults.gif.dynamic.maxInTray.toFloat(),
                steps = DynMaxInTrayRange.last - DynMaxInTrayRange.first - 1,
            ) { scope.launch { repository.setDynStickersMaxInTray(it.roundToInt()) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.dynsticker_memes_in_tray_title,
                stringResource(R.string.dynsticker_memes_in_tray_subtitle),
                settings.watch { it.gif.dynamic.memesInTray },
                default = SettingsDefaults.gif.dynamic.memesInTray,
            ) { scope.launch { repository.setDynStickersMemesInTray(it) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.dynsticker_animate_in_tray_title,
                stringResource(R.string.dynsticker_animate_in_tray_subtitle),
                settings.watch { it.gif.dynamic.animateInTray },
                default = SettingsDefaults.gif.dynamic.animateInTray,
            ) { scope.launch { repository.setDynStickersAnimateInTray(it) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.dynsticker_style_title,
                options = listOf(
                    StickerSuggestStyle.STRIP to stringResource(R.string.dynsticker_style_strip),
                    StickerSuggestStyle.CHIP to stringResource(R.string.dynsticker_style_chip),
                    StickerSuggestStyle.TRAY to stringResource(R.string.dynsticker_style_tray),
                ),
                selected = settings.watch { it.gif.dynamic.style },
                default = SettingsDefaults.gif.dynamic.style,
                info = stringResource(R.string.dynsticker_style_info),
            ) { scope.launch { repository.setDynStickersStyle(it) } }
        }
        item(visible = enabled && inTray, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.dynsticker_order_title,
                options = listOf(
                    DynOfferOrder.AFTER_OWN to stringResource(R.string.dynsticker_order_after),
                    DynOfferOrder.FIRST to stringResource(R.string.dynsticker_order_first),
                    DynOfferOrder.ONLY_WHEN_NOTHING_ELSE to stringResource(R.string.dynsticker_order_only),
                ),
                selected = settings.watch { it.gif.dynamic.order },
                default = SettingsDefaults.gif.dynamic.order,
                info = stringResource(R.string.dynsticker_order_info),
            ) { scope.launch { repository.setDynStickersOrder(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_sending_title)) {
        item(visible = enabled) {
            ToggleSetting(
                R.string.dynsticker_send_animated_title,
                stringResource(R.string.dynsticker_send_animated_subtitle),
                settings.watch { it.gif.dynamic.sendAnimated },
                default = SettingsDefaults.gif.dynamic.sendAnimated,
                info = stringResource(R.string.dynsticker_send_animated_info),
            ) { scope.launch { repository.setDynStickersSendAnimated(it) } }
        }
        item(visible = enabled) {
            ToggleSetting(
                R.string.dynsticker_panel_typed_title,
                stringResource(R.string.dynsticker_panel_typed_subtitle),
                settings.watch { it.gif.dynamic.panelUsesTyped },
                default = SettingsDefaults.gif.dynamic.panelUsesTyped,
            ) { scope.launch { repository.setDynStickersPanelUsesTyped(it) } }
        }
        item(visible = enabled, level = SettingLevel.STANDARD) {
            val override = settings.watch { it.gif.dynamic.fontOverride }
            NavRow(
                title = R.string.dynsticker_font_override_title,
                subtitle = stringResource(R.string.dynsticker_font_override_subtitle),
                value = fontOverrideLabel(context, override),
            ) { choosingFont = true }
        }
        // The exact engine is a download of its own; its choice shows once it is here.
        item(visible = enabled && DynRenderer.installedExact != null, level = SettingLevel.ADVANCED, waitsOnSetting = false) {
            ChoiceSetting(
                title = R.string.dynsticker_exact_title,
                options = listOf(
                    DynExactMode.NEVER to stringResource(R.string.dynsticker_exact_never),
                    DynExactMode.IMPORTED to stringResource(R.string.dynsticker_exact_imported),
                    DynExactMode.ALWAYS to stringResource(R.string.dynsticker_exact_always),
                ),
                selected = settings.watch { it.gif.dynamic.exactEngine },
                default = SettingsDefaults.gif.dynamic.exactEngine,
                info = stringResource(R.string.dynsticker_exact_info),
            ) { scope.launch { repository.setDynStickersExact(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_fonts_title), info = stringResource(R.string.dynsticker_fonts_info)) {
        for (font in DynStickerFonts.ALL) {
            item(visible = enabled && !OfflineBuild.isThisBuild, key = font.family, waitsOnSetting = false) {
                val have = remember(fontsVersion) { DynStickerFonts.installed(context, font.family) }
                WmRow(
                    title = font.family,
                    subtitle = when {
                        font.family in busyFonts -> stringResource(R.string.dynsticker_font_downloading)
                        have -> stringResource(R.string.dynsticker_font_installed)
                        else -> stringResource(
                            R.string.dynsticker_font_download,
                            android.text.format.Formatter.formatShortFileSize(context, font.size),
                        )
                    },
                    trailing = {
                        if (font.family in busyFonts) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    },
                    onClick = if (have || font.family in busyFonts) {
                        null
                    } else {
                        {
                            online {
                                busyFonts = busyFonts + font.family
                                scope.launch {
                                    val ok = DynStickerFonts.install(context, font)
                                    busyFonts = busyFonts - font.family
                                    fontsVersion++
                                    if (!ok) Toast.makeText(context, R.string.dynsticker_font_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_packs_title)) {
        for (pack in packs) {
            item(visible = enabled, key = pack.id) {
                val on = pack.id !in state.disabledPacks
                WmRow(
                    title = pack.name.ifBlank { pack.id },
                    subtitle = packSummary(pack),
                    trailing = {
                        Switch(checked = on, onCheckedChange = { store.setPackEnabled(pack.id, it) })
                    },
                    onClick = { onNavigate(DynRoutes.pack(pack.id)) },
                )
            }
        }
        item(visible = enabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { newPack = true }) { Text(stringResource(R.string.dynsticker_new_pack_action)) }
                OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.dynsticker_import_action))
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_feeds_title), info = stringResource(R.string.dynsticker_feeds_info)) {
        if (feeds.isEmpty()) {
            item(visible = enabled) {
                Text(
                    stringResource(R.string.dynsticker_feeds_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        for (feed in feeds) {
            item(visible = enabled, key = feed.id) {
                WmRow(
                    title = feed.name.ifBlank { DynStickerFeeds.hostOf(feed.url) },
                    subtitle = feedSummary(feed),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (feed.id in busyFeeds) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                            }
                            Switch(
                                checked = feed.enabled,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        repository.setDynStickerFeeds(
                                            settings.value.gif.dynamic.feeds.map { if (it.id == feed.id) it.copy(enabled = on) else it },
                                        )
                                    }
                                    store.packsOfFeed(feed.id).forEach { store.setPackEnabled(it.id, on) }
                                },
                            )
                        }
                    },
                    onClick = { openFeed = feed.id },
                )
            }
        }
        item(visible = enabled && !OfflineBuild.isThisBuild, waitsOnSetting = false) {
            NavRow(
                title = R.string.dynsticker_add_feed_title,
                subtitle = stringResource(R.string.dynsticker_add_feed_subtitle),
            ) { addingFeed = true }
        }
        item(visible = enabled && feeds.isNotEmpty() && !OfflineBuild.isThisBuild, level = SettingLevel.ADVANCED, waitsOnSetting = false) {
            ToggleSetting(
                R.string.dynsticker_auto_refresh_title,
                stringResource(R.string.dynsticker_auto_refresh_subtitle),
                settings.watch { it.gif.dynamic.autoRefresh },
                default = SettingsDefaults.gif.dynamic.autoRefresh,
            ) { scope.launch { repository.setDynStickersAutoRefresh(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_storage_title)) {
        item(visible = enabled, level = SettingLevel.STANDARD, waitsOnSetting = false) {
            var size by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) {
                size = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "dynstickers").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
            }
            NavRow(
                title = R.string.dynsticker_clear_cache_title,
                subtitle = stringResource(R.string.dynsticker_clear_cache_subtitle),
                value = size?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) { File(context.cacheDir, "dynstickers").deleteRecursively() }
                    size = 0L
                }
            }
        }
    }

    if (addingFeed) {
        AddFeedDialog(
            onDismiss = { addingFeed = false },
            onAdd = { links ->
                addingFeed = false
                val existing = settings.value.gif.dynamic.feeds
                val fresh = links.filter { link -> existing.none { it.url == link } }
                    .map { DynStickerFeed(id = DynStickerFeeds.newId(), url = it) }
                if (fresh.isEmpty()) return@AddFeedDialog
                scope.launch {
                    repository.setDynStickerFeeds(existing + fresh)
                    online { fresh.forEach { refreshFeed(it, force = true) } }
                }
            },
        )
    }
    openFeed?.let { id ->
        val feed = feeds.firstOrNull { it.id == id }
        if (feed == null) {
            openFeed = null
        } else {
            FeedDialog(
                feed = feed,
                packs = packs.filter { it.feedId == feed.id },
                onDismiss = { openFeed = null },
                onRefresh = {
                    openFeed = null
                    online { refreshFeed(feed, force = true) }
                },
                onRemove = {
                    openFeed = null
                    scope.launch {
                        repository.setDynStickerFeeds(settings.value.gif.dynamic.feeds.filter { it.id != id })
                        withContext(Dispatchers.IO) { store.packsOfFeed(id).forEach { store.deletePack(it.id) } }
                    }
                },
                onOpenPack = { packId ->
                    openFeed = null
                    onNavigate(DynRoutes.pack(packId))
                },
            )
        }
    }
    if (newPack) {
        NameDialog(
            title = stringResource(R.string.dynsticker_new_pack_action),
            initial = "",
            onDismiss = { newPack = false },
            onDone = { name ->
                newPack = false
                scope.launch {
                    val made = withContext(Dispatchers.IO) { store.createPack(name) }
                    if (made != null) onNavigate(DynRoutes.pack(made.id))
                }
            },
        )
    }
    if (choosingFont) {
        FontOverrideDialog(
            current = settings.value.gif.dynamic.fontOverride,
            onDismiss = { choosingFont = false },
            onPick = { id ->
                choosingFont = false
                scope.launch { repository.setDynStickersFont(id) }
            },
        )
    }
    pendingNetwork?.let { work ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                pendingNetwork = null
                work()
            },
            onDismiss = { pendingNetwork = null },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/**
 * Reads a picked file into the store: a zip (our own pack, a template set's
 * zip, a dotLottie file) as a pack, a Lottie document (plain or gzipped) as a
 * template in a new pack of its own.
 */
internal fun importDynamicStickerFile(context: Context, store: DynStickerStore, uri: Uri, intoPackId: String? = null): DynStickerStore.ImportResult {
    val name = displayNameOf(context, uri)
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return DynStickerStore.ImportResult.Failed
    val zip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    return if (zip) {
        store.importArchive(bytes.inputStream(), PackSource.USER, name.substringBeforeLast('.').ifBlank { "Templates" })
    } else {
        store.importDocument(bytes, name.ifBlank { "template.json" }, intoPackId)
    }
}

internal fun dynImportMessage(context: Context, result: DynStickerStore.ImportResult): String = when (result) {
    is DynStickerStore.ImportResult.Imported -> context.resources.getQuantityString(
        R.plurals.dynsticker_imported, result.templates, result.templates, result.pack.name,
    )
    DynStickerStore.ImportResult.NoTemplates -> context.getString(R.string.dynsticker_import_none)
    DynStickerStore.ImportResult.TooMany -> context.getString(R.string.dynsticker_import_too_many)
    DynStickerStore.ImportResult.Failed -> context.getString(R.string.dynsticker_import_failed)
}

private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

@Composable
private fun packSummary(pack: DynPackMeta): String {
    val count = pluralStringResource(R.plurals.dynsticker_templates, pack.templates.size, pack.templates.size)
    val source = stringResource(
        when (pack.source) {
            PackSource.BUILTIN -> R.string.dynsticker_source_builtin
            PackSource.USER -> R.string.dynsticker_source_user
            PackSource.FEED -> R.string.dynsticker_source_feed
            PackSource.ADDON -> R.string.dynsticker_source_addon
        },
    )
    return stringResource(R.string.dynsticker_pack_summary, count, source)
}

@Composable
private fun feedSummary(feed: DynStickerFeed): String {
    val error = feed.error
    return when {
        error != null -> stringResource(R.string.dynsticker_feed_failed, error)
        feed.refreshedAt == 0L -> stringResource(R.string.dynsticker_feed_not_fetched)
        else -> pluralStringResource(R.plurals.dynsticker_feed_packs, feed.packIds.size, feed.packIds.size)
    }
}

private fun fontOverrideLabel(context: Context, id: String): String = when {
    id.isBlank() -> context.getString(R.string.dynsticker_font_own)
    id.contains(':') -> KeyboardFonts.displayName(context, id)
    else -> id
}

@Composable
private fun AddFeedDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val links = text.lines().map { it.trim() }.filter { it.startsWith("https://", ignoreCase = true) }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dynsticker_add_feed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dynsticker_add_feed_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dynsticker_feed_links_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(links) }, enabled = links.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.dynsticker_add_feeds_action, links.size.coerceAtLeast(1), links.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

@Composable
private fun FeedDialog(
    feed: DynStickerFeed,
    packs: List<DynPackMeta>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onOpenPack: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feed.name.ifBlank { DynStickerFeeds.hostOf(feed.url) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(feedSummary(feed), style = MaterialTheme.typography.bodyMedium)
                Text(feed.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (feed.version.isNotBlank()) {
                    Text(
                        stringResource(R.string.dynsticker_feed_version, feed.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (pack in packs) {
                    TextButton(onClick = { onOpenPack(pack.id) }) { Text(pack.name.ifBlank { pack.id }) }
                }
                if (!OfflineBuild.isThisBuild) {
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.dynsticker_feed_refresh_action)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.dynsticker_feed_remove_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dynsticker_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** Every face a template can be drawn in instead of its own: the two pinned ones, the system's, the library's. */
@Composable
private fun FontOverrideDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val options = remember {
        buildList {
            add("" to context.getString(R.string.dynsticker_font_own))
            DynStickerFonts.ALL.forEach { add(it.family to it.family) }
            add("sans-serif-black" to context.getString(R.string.dynsticker_font_bold))
            add("serif" to context.getString(R.string.dynsticker_font_serif))
            add("monospace" to context.getString(R.string.dynsticker_font_mono))
            add("cursive" to context.getString(R.string.dynsticker_font_script))
            FontStore.get(context).textFonts()
                .filter { font -> DynStickerFonts.ALL.none { it.family.equals(font.name, ignoreCase = true) } }
                .forEach { add(it.name to it.name) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dynsticker_font_override_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((id, label) in options) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = id == current, onClick = { onPick(id) })
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

/** A template drawn with [text] (its own words when blank), as the keyboard would draw it. */
@Composable
internal fun DynTemplateThumb(template: DynTemplate, text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision by DynStickerStore.get(context).revision.collectAsState()
    val image by produceState<ImageBitmap?>(null, template.key, text, revision) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val fill = if (text.isBlank()) DynFill(emptyMap()) else DynMatcher.fillFor(template, text) ?: DynFill(emptyMap())
                DynAppRender.renderer(context).prepare(template, fill)?.use { prepared ->
                    prepared.render(prepared.posterProgress, THUMB_PX).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val drawn = image
        if (drawn == null) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Image(drawn, contentDescription = template.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

private const val THUMB_PX = 256
private const val GRID_COLUMNS = 3

/**
 * One pack: its templates drawn with a test text, each switched on or off,
 * starred, opened in the builder, copied or deleted; and the pack itself
 * renamed, exported or deleted.
 */
@Composable
internal fun DynamicStickerPackScreen(packId: String, onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { DynStickerStore.get(context) }
    val revision by store.revision.collectAsState()
    val pack = remember(revision) { store.pack(packId) }
    val templates = remember(revision) { store.allTemplates().filter { it.packId == packId } }
    val state = remember(revision) { store.state() }
    // Empty: each template shows its own words until something is typed here.
    var testText by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (pack == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val own = pack.source == PackSource.USER
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { store.export(packId, it) } == true }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) R.string.dynsticker_export_done else R.string.dynsticker_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importInto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { importDynamicStickerFile(context, store, uri, intoPackId = packId) }
            Toast.makeText(context, dynImportMessage(context, result), Toast.LENGTH_LONG).show()
        }
    }

    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pack.name.ifBlank { pack.id }, style = MaterialTheme.typography.titleMedium)
                Text(packSummary(pack), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val credits = listOf(pack.author, pack.version, pack.license).filter { it.isNotBlank() }.joinToString(" · ")
                if (credits.isNotBlank()) {
                    Text(credits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dynsticker_test_text_label)) },
                placeholder = { Text(stringResource(R.string.dynsticker_test_text_placeholder)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    SettingsGroup(pluralStringResource(R.plurals.dynsticker_templates, templates.size, templates.size)) {
        for (row in templates.chunked(GRID_COLUMNS)) {
            item(key = row.first().key) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (template in row) {
                        val on = template.key !in state.disabledTemplates
                        Column(modifier = Modifier.weight(1f).clickable { open = template.key }) {
                            Box {
                                DynTemplateThumb(
                                    template,
                                    testText,
                                    Modifier.fillMaxWidth().aspectRatio(1f).then(if (on) Modifier else Modifier.background(MaterialTheme.colorScheme.surface)),
                                )
                                if (template.key in state.favourites) {
                                    Icon(
                                        Icons.Outlined.Star,
                                        contentDescription = stringResource(R.string.dynsticker_favourite),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                                    )
                                }
                            }
                            Text(
                                template.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!on) {
                                Text(
                                    stringResource(R.string.dynsticker_template_off),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (templates.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.dynsticker_pack_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.dynsticker_pack_actions_title)) {
        item(visible = own, waitsOnSetting = false) {
            NavRow(title = R.string.dynsticker_new_template_title, subtitle = stringResource(R.string.dynsticker_new_template_subtitle)) {
                onNavigate(DynRoutes.builder("$packId/${DynRoutes.NEW_TEMPLATE}"))
            }
        }
        item(visible = own, waitsOnSetting = false) {
            NavRow(title = R.string.dynsticker_import_into_title, subtitle = stringResource(R.string.dynsticker_import_into_subtitle)) {
                importInto.launch(arrayOf("*/*"))
            }
        }
        item(visible = own, waitsOnSetting = false) {
            NavRow(title = R.string.dynsticker_rename_title) { renaming = true }
        }
        item(visible = pack.source != PackSource.BUILTIN, waitsOnSetting = false) {
            NavRow(title = R.string.dynsticker_export_title, subtitle = stringResource(R.string.dynsticker_export_subtitle)) {
                exportFile.launch("${pack.name.ifBlank { pack.id }}.${DynPackFile.FILE_EXTENSION}")
            }
        }
        item(visible = pack.source != PackSource.BUILTIN, waitsOnSetting = false) {
            NavRow(title = R.string.dynsticker_delete_pack_title) { deleting = true }
        }
    }

    open?.let { key ->
        val template = templates.firstOrNull { it.key == key }
        if (template == null) {
            open = null
        } else {
            TemplateDialog(
                template = template,
                own = own,
                on = key !in state.disabledTemplates,
                favourite = key in state.favourites,
                canMoveUp = templates.indexOf(template) > 0,
                onDismiss = { open = null },
                onToggle = { store.setTemplateEnabled(key, it) },
                onFavourite = { store.setFavourite(key, it) },
                onEdit = {
                    open = null
                    onNavigate(DynRoutes.builder(key))
                },
                onDuplicate = {
                    open = null
                    scope.launch {
                        val copied = withContext(Dispatchers.IO) {
                            val target = if (own) packId else myTemplatesPack(context, store)
                            target?.let { store.duplicateTemplate(key, it) }
                        }
                        Toast.makeText(
                            context,
                            if (copied != null) R.string.dynsticker_duplicated else R.string.dynsticker_duplicate_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onMoveUp = {
                    open = null
                    val ids = templates.map { it.meta.id }.toMutableList()
                    val at = ids.indexOf(template.meta.id)
                    if (at > 0) {
                        ids.add(at - 1, ids.removeAt(at))
                        scope.launch(Dispatchers.IO) { store.reorderTemplates(packId, ids) }
                    }
                },
                onDelete = {
                    open = null
                    scope.launch(Dispatchers.IO) { store.deleteTemplate(packId, template.meta.id) }
                },
            )
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.dynsticker_rename_title),
            initial = pack.name,
            onDismiss = { renaming = false },
            onDone = { name ->
                renaming = false
                scope.launch(Dispatchers.IO) { store.renamePack(packId, name) }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.dynsticker_delete_pack_title)) },
            text = { Text(stringResource(R.string.dynsticker_delete_pack_body, pack.name.ifBlank { pack.id })) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deletePack(packId) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.dynsticker_delete_action)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

/** The user's own pack copies land in: the first one there is, or a new "My templates". */
internal fun myTemplatesPack(context: Context, store: DynStickerStore): String? =
    store.packs().firstOrNull { it.source == PackSource.USER }?.id
        ?: store.createPack(context.getString(R.string.dynsticker_my_templates))?.id

@Composable
@Suppress("LongParameterList")
private fun TemplateDialog(
    template: DynTemplate,
    own: Boolean,
    on: Boolean,
    favourite: Boolean,
    canMoveUp: Boolean,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFavourite: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                DynTemplateThumb(template, "", Modifier.size(140.dp).align(Alignment.CenterHorizontally))
                val kind = stringResource(if (template.kind == DynKind.MEME) R.string.dynsticker_kind_meme else R.string.dynsticker_kind_sticker)
                Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (template.meta.match.phrases.isNotEmpty()) {
                    Text(
                        stringResource(R.string.dynsticker_phrases_line, template.meta.match.phrases.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (template.meta.match.generic) {
                    Text(stringResource(R.string.dynsticker_generic_line), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dynsticker_template_on), modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = onToggle)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dynsticker_favourite), modifier = Modifier.weight(1f))
                    Switch(checked = favourite, onCheckedChange = onFavourite)
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(if (own) R.string.dynsticker_edit_action else R.string.dynsticker_edit_copy_action))
                }
                TextButton(onClick = onDuplicate) { Text(stringResource(R.string.dynsticker_duplicate_action)) }
                if (own && canMoveUp) TextButton(onClick = onMoveUp) { Text(stringResource(R.string.dynsticker_move_up_action)) }
            }
        },
        confirmButton = {
            if (own) TextButton(onClick = onDelete) { Text(stringResource(R.string.dynsticker_delete_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}
