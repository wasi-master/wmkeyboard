package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.core.addons.ImportLink
import com.wasimaster.wmkeyboard.core.addons.TelegramStickerDownloads
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramSticker
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramStickerFormat
import com.wasimaster.wmkeyboard.core.stickers.telegram.TelegramStickerSet
import com.wasimaster.wmkeyboard.core.stickers.telegram.TgsFrames
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The Telegram sticker sets screen. */
internal const val TELEGRAM_STICKERS_ROUTE = "telegram_stickers"

/** The bot that makes bots, where a token comes from. */
private const val BOTFATHER_URL = "https://t.me/BotFather"

/** The preview of one Telegram set. A set name is letters, digits and underscores only. */
internal fun telegramPackRoute(setName: String): String = "telegram_pack/$setName"

/**
 * Telegram sticker sets, brought into the user's own packs.
 *
 * Telegram has no anonymous way to a set the way Signal's sticker storage is
 * one. The Bot API is the door that needs no Telegram account, and it needs a
 * bot, so the screen is the token first and the link second. A bot is free,
 * the user makes it with @BotFather, and the token is theirs: there is no
 * built-in one, because a token shared by every install would be a token
 * every install could have revoked.
 *
 * Like the Signal screen, the link field starts empty and is never filled
 * from the clipboard, and there is no list of sets of the app's own choosing.
 */
@Composable
internal fun TelegramStickersScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    var showLink by remember { mutableStateOf(false) }
    val token = settings.watch { it.gif.telegramBotToken }
    val tokenOk = TelegramStickerSet.tokenOrNull(token) != null

    SettingsGroup(
        stringResource(R.string.import_telegram_section_title),
        info = stringResource(R.string.import_telegram_caption),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.import_telegram_link_title),
                subtitle = stringResource(
                    if (tokenOk) R.string.import_telegram_link_subtitle else R.string.import_telegram_link_needs_token,
                ),
                icon = Icons.Outlined.Link,
                accent = routeAccent("sticker_packs"),
                enabled = tokenOk,
                highlightKey = R.string.import_telegram_link_title,
                onClick = { showLink = true },
            )
        }
    }

    SettingsGroup(
        stringResource(R.string.import_telegram_bot_section_title),
        info = stringResource(R.string.import_telegram_bot_section_info),
    ) {
        item {
            ApiKeyField(
                label = stringResource(R.string.import_telegram_token_label),
                value = token,
                builtInAvailable = false,
                emptyHint = stringResource(R.string.import_telegram_token_hint),
                wmPassTitle = "Telegram bot",
            ) { repository.setTelegramBotToken(it) }
        }
        if (token.isNotBlank() && !tokenOk) {
            item {
                Text(
                    stringResource(R.string.import_telegram_token_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.import_telegram_botfather_title),
                subtitle = stringResource(R.string.import_telegram_botfather_subtitle),
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_telegram_botfather_title,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(BOTFATHER_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        if (BuildConfig.ENABLE_FDROID) {
            serverItems(repository, settings, endpoints = listOf(ServiceEndpoint.TELEGRAM_BOT_API))
        }
    }

    if (showLink) {
        TelegramLinkDialog(
            onDismiss = { showLink = false },
            onOpen = { name ->
                showLink = false
                onNavigate(telegramPackRoute(name))
            },
        )
    }
}

/**
 * The set named in [text]: a link in any form [ImportLink] reads, or a bare
 * set name, which is what is left when someone copies only the end of one.
 */
private fun setNameIn(text: String): String? {
    val trimmed = text.trim()
    ImportLink.telegramStickers(trimmed)?.let { return it.setName }
    return trimmed.takeIf { TelegramStickerSet.isSetName(it) }
}

@Composable
private fun TelegramLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val name = remember(text) { setNameIn(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_telegram_link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_telegram_link_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_link_field_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank() && name == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.import_telegram_link_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { name?.let(onOpen) }, enabled = name != null) {
                Text(stringResource(R.string.import_telegram_open_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Where the preview has got to with the set. */
private sealed interface SetLoad {
    data object Waiting : SetLoad
    data object Loading : SetLoad
    data class Loaded(val set: TelegramStickerSet) : SetLoad
    data class Failed(val text: String, val needsToken: Boolean = false) : SetLoad
}

/** An import in flight: which half of it, and how far along. */
private data class SetProgress(val phase: TelegramStickerDownloads.Phase, val done: Int, val total: Int)

/**
 * One Telegram set, shown before it is added.
 *
 * Nothing is kept until the user presses Add. The grid shows still stickers
 * as they are and animated ones playing; a video sticker shows the still
 * Telegram made of it, because playing VP9 with its transparency is what the
 * import itself does and is too slow for a grid. Every file lands in the
 * cache [TelegramStickerDownloads] keeps, which the import then reads.
 */
@Composable
internal fun TelegramPackScreen(
    setName: String,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    val cacheDir = remember { context.cacheDir }
    val token = settings.watch { it.gif.telegramBotToken }

    var load by remember { mutableStateOf<SetLoad>(SetLoad.Waiting) }
    var askMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SetProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val mine = remember(revision) {
        store.packs().firstOrNull { it.source == StickerPack.telegramSource(setName) }
    }
    // The stickers ticked for adding (#965), by unique id: all of them once
    // the set loads, and only those are downloaded and converted.
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }

    val notFound = stringResource(R.string.import_telegram_error_not_found)
    val badToken = stringResource(R.string.import_telegram_error_bad_token)
    val noToken = stringResource(R.string.import_telegram_error_no_token)

    fun fetch() {
        if (TelegramStickerSet.tokenOrNull(token) == null) {
            load = SetLoad.Failed(noToken, needsToken = true)
            return
        }
        load = SetLoad.Loading
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                TelegramStickerDownloads.trimCache(cacheDir)
                TelegramStickerDownloads.stickerSet(token, setName)
            }
            load = when (outcome) {
                is TelegramStickerDownloads.SetOutcome.Ok -> {
                    chosen = outcome.set.stickers.mapTo(HashSet()) { it.uniqueId }
                    SetLoad.Loaded(outcome.set)
                }
                TelegramStickerDownloads.SetOutcome.NotFound -> SetLoad.Failed(notFound)
                TelegramStickerDownloads.SetOutcome.BadToken -> SetLoad.Failed(badToken, needsToken = true)
                is TelegramStickerDownloads.SetOutcome.Failed ->
                    SetLoad.Failed(ToolHttp.friendlyMessage(context, outcome.error))
            }
        }
    }

    LaunchedEffect(setName) {
        // Without a token there is nothing to ask, so nothing to ask about.
        if (TelegramStickerSet.tokenOrNull(token) == null) {
            fetch()
            return@LaunchedEffect
        }
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> fetch()
            MeteredDecision.ASK -> askMetered = true
            MeteredDecision.BLOCKED -> blockedMetered = true
        }
    }

    fun add(set: TelegramStickerSet) {
        val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
        val only = chosen
        progress = SetProgress(TelegramStickerDownloads.Phase.DOWNLOADING, 0, only.size)
        scope.launch {
            val text = try {
                TelegramStickerDownloads.import(
                    store, token, set, fallbackName, cacheDir, only,
                ) { phase, done, total -> progress = SetProgress(phase, done, total) }
                    .describe(context)
            } catch (e: IOException) {
                ToolHttp.friendlyMessage(context, e)
            }
            progress = null
            revision++
            message = text
        }
    }

    when (val current = load) {
        SetLoad.Waiting, SetLoad.Loading -> SettingsGroup {
            item { WmRow(title = stringResource(R.string.import_telegram_loading)) }
        }

        is SetLoad.Failed -> SettingsGroup {
            item { WmRow(title = current.text) }
            item {
                if (current.needsToken) {
                    WmRow(
                        title = stringResource(R.string.import_telegram_open_settings),
                        onClick = { onNavigate(TELEGRAM_STICKERS_ROUTE) },
                    )
                } else {
                    WmRow(
                        title = stringResource(CommonR.string.common_retry),
                        onClick = { fetch() },
                    )
                }
            }
        }

        is SetLoad.Loaded -> LoadedSet(
            set = current.set,
            token = token,
            cacheDir = cacheDir,
            mine = mine,
            progress = progress,
            chosen = chosen,
            onChosenChange = { chosen = it },
            onAdd = { add(current.set) },
            onOpenMine = { mine?.let { onNavigate(stickerPackRoute(it.id)) } },
        )
    }

    if (blockedMetered) {
        MeteredBlockedDialog {
            blockedMetered = false
            onBack()
        }
    }
    if (askMetered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                askMetered = false
                fetch()
            },
            onDismiss = {
                askMetered = false
                onBack()
            },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun LoadedSet(
    set: TelegramStickerSet,
    token: String,
    cacheDir: File,
    mine: StickerPack?,
    progress: SetProgress?,
    chosen: Set<String>,
    onChosenChange: (Set<String>) -> Unit,
    onAdd: () -> Unit,
    onOpenMine: () -> Unit,
) {
    val count = set.stickers.size
    // Ticking is for a set that is not in yet and not on its way in.
    val picking = mine == null && progress == null
    val moving = set.stickers.any { it.format != TelegramStickerFormat.STATIC }
    SettingsGroup(set.title.ifBlank { stringResource(R.string.import_telegram_untitled) }) {
        item {
            WmRow(
                title = pluralStringResource(R.plurals.import_sticker_count, count, count),
                subtitle = if (moving) stringResource(R.string.import_telegram_animated_note) else null,
            )
        }
    }

    SettingsGroup {
        item {
            when {
                progress != null -> WmRow(
                    title = stringResource(
                        when (progress.phase) {
                            TelegramStickerDownloads.Phase.DOWNLOADING -> R.string.import_telegram_downloading
                            TelegramStickerDownloads.Phase.ADDING -> R.string.import_telegram_adding
                        },
                        progress.done,
                        progress.total,
                    ),
                    icon = Icons.Outlined.Add,
                    accent = routeAccent("sticker_packs"),
                    enabled = false,
                    supporting = {
                        LinearProgressIndicator(
                            progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    },
                )

                mine != null -> WmRow(
                    title = stringResource(R.string.import_telegram_added_title),
                    icon = SettingsRowIcons[R.string.import_telegram_added_title],
                    subtitle = stringResource(R.string.import_telegram_added_subtitle, mine.name),
                    accent = routeAccent("sticker_packs"),
                    onClick = onOpenMine,
                )

                else -> WmRow(
                    title = if (chosen.size == count) {
                        stringResource(R.string.import_telegram_add_title)
                    } else {
                        pluralStringResource(R.plurals.import_stickers_add_chosen, chosen.size, chosen.size)
                    },
                    subtitle = stringResource(R.string.import_telegram_add_subtitle),
                    icon = Icons.Outlined.Add,
                    accent = routeAccent("sticker_packs"),
                    enabled = chosen.isNotEmpty(),
                    onClick = onAdd,
                )
            }
        }
    }

    if (picking && count > 0) {
        StickerPickBar(
            chosen = chosen.size,
            total = count,
            onAll = { onChosenChange(set.stickers.mapTo(HashSet()) { it.uniqueId }) },
            onNone = { onChosenChange(emptySet()) },
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        )
    }

    val loader = rememberMediaImageLoader()
    val unnamed = stringResource(R.string.import_sticker_desc_fallback)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(((count + 2) / 3 * 104).coerceAtMost(640).dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(set.stickers, key = { it.uniqueId }) { sticker ->
            val preview by produceState<SetPreview?>(null, set.name, sticker.uniqueId) {
                value = withContext(Dispatchers.IO) { runCatching { preview(token, set.name, sticker, cacheDir) }.getOrNull() }
            }
            StickerPickCell(
                checked = if (picking) sticker.uniqueId in chosen else null,
                onClick = if (picking) ({ onChosenChange(chosen.withToggled(sticker.uniqueId)) }) else null,
            ) {
                val label = sticker.emoji.ifBlank { unnamed }
                when (val shown = preview) {
                    is SetPreview.Lottie -> LottiePreview(shown.animation, label)
                    else -> AsyncImage(
                        model = (shown as? SetPreview.Picture)?.file,
                        contentDescription = label,
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** What one cell of the grid shows. */
private sealed interface SetPreview {
    /** A file any image loader reads: a still sticker, or the still of a video one. */
    class Picture(val file: File) : SetPreview

    /** An animated sticker, parsed and ready to play. */
    class Lottie(val animation: TgsFrames.Animation) : SetPreview
}

/** Fetches what the cell for [sticker] shows. Blocking. */
private fun preview(token: String, setName: String, sticker: TelegramSticker, cacheDir: File): SetPreview? =
    when (sticker.format) {
        TelegramStickerFormat.STATIC ->
            TelegramStickerDownloads.sticker(token, setName, sticker, cacheDir)?.let(SetPreview::Picture)

        TelegramStickerFormat.ANIMATED ->
            TelegramStickerDownloads.sticker(token, setName, sticker, cacheDir)
                ?.let { TgsFrames.load(it.readBytes()) }
                ?.let(SetPreview::Lottie)
                // A file Lottie cannot read still has Telegram's still of it.
                ?: TelegramStickerDownloads.thumbnail(token, setName, sticker, cacheDir)?.let(SetPreview::Picture)

        TelegramStickerFormat.VIDEO ->
            TelegramStickerDownloads.thumbnail(token, setName, sticker, cacheDir)?.let(SetPreview::Picture)
    }

/**
 * An animated Telegram sticker, playing, in a plain `ImageView` like the
 * Signal preview's animated PNGs. The drawable is driven by the display's
 * frame clock rather than by the view, so it is stopped by hand when the
 * cell goes away.
 */
@Composable
private fun LottiePreview(animation: TgsFrames.Animation, label: String) {
    val drawable = remember(animation) { runCatching { animation.drawable() }.getOrNull() }
    AndroidView(
        factory = { context ->
            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        },
        update = { view ->
            view.contentDescription = label
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
        },
        onRelease = { view ->
            (view.drawable as? android.graphics.drawable.Animatable)?.stop()
            view.setImageDrawable(null)
        },
        modifier = Modifier.fillMaxSize(),
    )
}
