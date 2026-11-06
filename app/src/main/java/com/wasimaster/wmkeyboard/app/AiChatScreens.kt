package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.app.AiChatPickActivity
import com.wasimaster.wmkeyboard.core.aichat.AiChatFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.focusRequester
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OutlinedFlag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.ime.aichat.AiChatFiles
import com.wasimaster.wmkeyboard.ime.aichat.AiChatImages
import com.wasimaster.wmkeyboard.ime.ui.fileIcon
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.aichat.AiChatConversation
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.support.Support
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPrivacy
import com.wasimaster.wmkeyboard.core.settings.aiThrough
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController.ModelChoice
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdown
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdownColors
import com.wasimaster.wmkeyboard.ime.ui.ChatChain
import com.wasimaster.wmkeyboard.ime.ui.hintRes
import com.wasimaster.wmkeyboard.ime.ui.icon
import com.wasimaster.wmkeyboard.ime.ui.labelRes
import com.wasimaster.wmkeyboard.ime.aichat.AiChatExport
import com.wasimaster.wmkeyboard.core.tools.AiAnswerFormat
import com.wasimaster.wmkeyboard.core.tools.AiChatMode
import com.wasimaster.wmkeyboard.core.tools.AiFollowUps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SuggestionChip
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R as ImeR

/**
 * The chat conversation list: Tools > AI > Chat, and the launcher shortcut's
 * landing screen. Rows come straight from [AiChatStore]; the store is not a
 * flow, so [AiChatController.storeVersion] re-reads it after every change.
 */
@Composable
internal fun AiChatListScreen(
    anim: AnimatedVisibilityScope? = null,
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onAutoNew: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val conversations = remember(version) { store.items() }
    var confirmDelete by remember { mutableStateOf<AiChatConversation?>(null) }
    val reduceMotion = LocalReduceMotion.current
    // A long press starts picking chats (#882); a chat deleted meanwhile
    // leaves the selection with it.
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selected = picked.filterTo(HashSet()) { id -> conversations.any { it.id == id } }
    val selecting = selected.isNotEmpty()
    var confirmBulk by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AiChatConversation?>(null) }
    BackHandler(enabled = selecting) { picked = emptySet() }
    if (selecting) {
        RegisterDock {
            ChatSelectionBar(
                count = selected.size,
                allPicked = selected.size == conversations.size,
                onClear = { picked = emptySet() },
                onSelectAll = { picked = conversations.mapTo(HashSet()) { it.id } },
                onDelete = { confirmBulk = true },
            )
        }
    }

    // Nothing to list yet: skip the empty list and land in a chat directly.
    // The caller replaces this screen in the back stack, and a chat only
    // writes its conversation on the first send, so backing straight out
    // leaves nothing behind.
    LaunchedEffect(Unit) {
        if (store.isEmpty()) onAutoNew()
    }

    WmLazyScreen(
        anim = anim,
        title = stringResource(R.string.home_screen_ai_chat_title),
        onBack = onBack,
        route = "ai_chat",
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(ImeR.string.ime_ai_chat_new),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Button(
                    onClick = onNewChat,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(ImeR.string.ime_ai_chat_new))
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Text(
                        stringResource(ImeR.string.ime_ai_chat_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                // A deleted chat fades out and the ones under it close the
                // gap, rather than the list redrawing one row shorter. The
                // list is newest first, so a chat that moves up glides there.
                ConversationRow(
                    conversation = conversation,
                    selecting = selecting,
                    checked = conversation.id in selected,
                    onClick = {
                        if (selecting) picked = selected.toggled(conversation.id) else onOpenChat(conversation.id)
                    },
                    onLongClick = { picked = selected.toggled(conversation.id) },
                    onRename = { renaming = conversation },
                    onDelete = { confirmDelete = conversation },
                    modifier = listItemMotion(reduceMotion),
                )
            }
        }
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = {
                Text(pluralStringResource(R.plurals.toolai_ai_chat_delete_many_title, selected.size, selected.size))
            },
            text = { Text(stringResource(R.string.toolai_ai_chat_delete_many_body)) },
            confirmButton = {
                TextButton(onClick = {
                    AiChatController.deleteConversations(context, selected)
                    picked = emptySet()
                    confirmBulk = false
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulk = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }

    renaming?.let { chat ->
        RenameChatDialog(
            current = chat.title,
            onDismiss = { renaming = null },
            onRename = { title ->
                AiChatController.renameConversation(context, chat.id, title)
                renaming = null
            },
        )
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.toolai_ai_chat_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.toolai_ai_chat_delete_body,
                        doomed.title.ifBlank {
                            stringResource(ImeR.string.ime_ai_chat_untitled)
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AiChatController.deleteConversation(context, doomed.id)
                    confirmDelete = null
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: AiChatConversation,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = conversation.title.ifBlank { stringResource(ImeR.string.ime_ai_chat_untitled) }
    // While picking chats the row is a checkbox, and a swipe is one more
    // thing a finger meant for the next tick could set off.
    SwipeActionsRow(
        actions = listOf(deleteSwipeAction(stringResource(CommonR.string.common_delete), onDelete)),
        modifier = modifier,
        enabled = !selecting,
    ) {
        Box(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            WmRow(
                title = title,
                subtitle = DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
                leading = if (selecting) ({ Checkbox(checked = checked, onCheckedChange = { onClick() }) }) else null,
                trailing = if (selecting) {
                    null
                } else {
                    {
                        Row {
                            IconButton(onClick = onRename) {
                                Icon(
                                    Icons.Outlined.DriveFileRenameOutline,
                                    contentDescription = stringResource(ImeR.string.ime_ai_chat_rename_desc, title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(CommonR.string.common_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/** The bar under a selection of chats (#882): how many, Select all, and Delete. */
@Composable
private fun ChatSelectionBar(
    count: Int,
    allPicked: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
            }
            Text(
                pluralStringResource(ImeR.plurals.ime_ai_chat_selected_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll, enabled = !allPicked) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(CommonR.string.common_select_all))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(ImeR.string.ime_ai_chat_delete_selected))
            }
        }
    }
}

/** A chat's new name (#882), typed over the old one. */
@Composable
private fun RenameChatDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ImeR.string.ime_ai_chat_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.copy(text = it.text.take(AiChatStore.MAX_RENAME)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.text) }, enabled = name.text.isNotBlank()) {
                Text(stringResource(ImeR.string.ime_ai_chat_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private fun Set<Long>.toggled(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * One conversation. Owns its whole Scaffold (SettingsSearchScreen precedent)
 * rather than the collapsing-bar frame: the transcript is a `reverseLayout`
 * list anchored to the composer, and inverted scroll deltas fight the
 * exit-until-collapsed bar.
 *
 * [conversationId] < 0 means a fresh chat; the conversation is created in the
 * store on the first send, so backing out of an empty chat leaves no row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatScreen(
    settings: LiveSettings,
    conversationId: Long,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    /**
     * Text the first message carries: the screen the floating AI button was
     * pressed over, or a selection sent with Ask AI (#697). Shown above the
     * composer, where it can be read or removed before anything is sent.
     */
    initialAttachment: String = "",
    /** What the attachment is, for its line above the composer; blank for the plain count. */
    attachmentLabel: String = "",
    /**
     * Drawn as the Ask AI sheet over another app (#697) rather than as a
     * settings screen: no status-bar padding at the top, a close glyph
     * rather than a back arrow.
     */
    inSheet: Boolean = false,
    /** A line under the model chips, such as why no screen came along. */
    notice: (@Composable () -> Unit)? = null,
    /**
     * What the message box starts with, unsent: a question the assistant's
     * menu (#1143) wrote for the user to send or change.
     */
    initialDraft: String = "",
) {
    val context = LocalContext.current
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val run by AiChatController.run.collectAsState()
    var activeId by rememberSaveable { mutableLongStateOf(conversationId) }
    val conversation = remember(version, activeId) {
        activeId.takeIf { it >= 0 }?.let { store.get(it) }
    }
    val messages = conversation?.messages.orEmpty()

    // Everything usable right now: downloaded local models + configured
    // remote providers. Keyed on version so a chat opened right after a
    // download sees the new model.
    val ai = settings.watch { it.ai }
    val providers = settings.watch { it.providers }
    val choices = remember(version, ai, providers) { AiChatController.choices(context, settings.value) }
    var choice by remember(choices) {
        mutableStateOf(AiChatController.initialChoice(store, choices))
    }

    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    // The message box's mode and answer format: kept from one message to the
    // next, so a run of research questions stays research.
    var modeId by rememberSaveable { mutableStateOf(AiChatMode.CHAT.id) }
    var formatId by rememberSaveable { mutableStateOf(AiAnswerFormat.AUTO.id) }
    val mode = AiChatMode.of(modeId)
    val format = AiAnswerFormat.of(formatId)
    val researchBlock = if (mode == AiChatMode.RESEARCH) AiChatController.researchBlock(settings.value, choice) else null
    // Text that came with a message from the keyboard's chat, where the field
    // being written in can be quoted (#280). This screen has no field to quote
    // from, but a message taken back for editing keeps what it carried.
    var draftAttachment by rememberSaveable { mutableStateOf(initialAttachment) }
    var attachmentOpen by rememberSaveable { mutableStateOf(false) }
    // Pictures to send with the next message (#698), by the names the chat
    // keeps them under, one per line so the list survives a rotation.
    var draftImagesText by rememberSaveable { mutableStateOf("") }
    val draftImages = draftImagesText.lines().filter { it.isNotEmpty() }
    // Files to send with it (#882), kept as JSON so the list survives a rotation.
    var draftFilesJson by rememberSaveable { mutableStateOf("") }
    val draftFiles = remember(draftFilesJson) { decodeFiles(draftFilesJson) }
    val scope = rememberCoroutineScope()
    var attachSheet by remember { mutableStateOf(false) }
    val takeIn: (List<Uri>, Boolean) -> Unit = { uris, asFiles ->
        if (uris.isNotEmpty()) {
            scope.launch {
                // Scaled, read or copied in off the main thread: a phone photo
                // takes a moment to decode, a recording to copy.
                val (images, files, notice) = withContext(Dispatchers.IO) {
                    importPicks(context, uris, asFiles, keep = store.persist, draftImages.size, draftFiles.size)
                }
                draftImagesText = (draftImages + images).joinToString("\n")
                draftFilesJson = encodeFiles(draftFiles + files)
                if (notice != 0) Toast.makeText(context, context.getString(notice), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AiChatStore.MAX_IMAGES),
    ) { uris -> takeIn(uris, false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        takeIn(uris, true)
    }
    val cameraFile = remember { File(File(context.cacheDir, "ai_chat_camera"), "photo.jpg") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken && cameraFile.length() > 0) takeIn(listOf(Uri.fromFile(cameraFile)), false)
    }
    val launchCamera: () -> Unit = {
        runCatching {
            cameraFile.parentFile?.mkdirs()
            cameraFile.delete()
            takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.clipboard", cameraFile))
        }
    }
    // The app declares the camera permission for its camera tool, and Android
    // then refuses the capture intent to an app that holds it not.
    val cameraGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, context.getString(ImeR.string.ime_ai_chat_photo_no_permission), Toast.LENGTH_SHORT).show()
        }
    }
    val listState = rememberLazyListState()

    // The session mirrors the native KV cache; keep it only while some chat
    // screen is alive, so an abandoned chat frees the conversation.
    DisposableEffect(activeId) {
        onDispose {
            if (!AiChatController.isGenerating) {
                activeId.takeIf { it >= 0 }?.let { AiChatController.closeSessionFor(it) }
            }
        }
    }

    // Pinned to the newest message while streaming: index 0 in reverseLayout.
    LaunchedEffect(run?.partial?.length, messages.size) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    // This screen builds its own bar rather than the house one, so it has to
    // name itself in the path: the AI settings page opens from here, and its
    // strip has to be able to come back through this chat.
    val chatName = conversation?.title?.ifBlank { null }
        ?: stringResource(R.string.home_screen_ai_chat_title)
    RegisterSettingsCrumb(chatName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        if (inSheet) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(CommonR.string.common_close),
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(CommonR.string.common_back),
                            )
                        }
                    }
                },
                actions = {
                    val privacy = choice?.takeIf { it.provider == AiProvider.PRIVATEMODE }
                        ?.let { picked -> settings.value.aiThrough(picked.rowKey)?.let(AiClient::privacy) }
                    if (privacy != null) PrivacyLock(privacy)
                },
                windowInsets = if (inSheet) WindowInsets(0) else TopAppBarDefaults.windowInsets,
            )
        },
        // A sheet starts below the status bar already; only the sides and
        // the navigation bar under it are still the window's to pad.
        contentWindowInsets = if (inSheet) {
            WindowInsets.navigationBars
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        containerColor = if (inSheet) Color.Transparent else MaterialTheme.colorScheme.background,
    ) { padding ->
        // The app targets SDK 36, so from Android 15 the window is drawn edge to
        // edge and no longer resizes for the keyboard. Without imePadding nothing
        // makes room, and the system pans the whole window up until the focused
        // composer clears the keyboard — the top bar and the newest messages go
        // off the top of the screen (#143). Stopping the column at the keyboard
        // instead shrinks the reverseLayout transcript from its top, so the
        // newest message stays on screen. The Scaffold's bottom inset is
        // consumed first so the navigation bar is not counted twice. The
        // window is told not to pan as well, or the composer rises twice.
        ResizeForKeyboard()
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            if (choices.isEmpty()) {
                NoBackendState(onOpenAiSettings)
                return@Column
            }
            ModelChips(
                choices = choices,
                choice = choice,
                enabled = run == null,
                onPick = { choice = it },
            )
            notice?.invoke()
            // Only the newest turn can be redone or taken back: doing either
            // mid-transcript would answer out of order.
            val idle = run == null
            val lastUser = messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                run?.takeIf { it.conversationId == activeId }?.let { live ->
                    item(key = "live") { StreamingBubble(live) }
                }
                itemsIndexed(messages.asReversed()) { reversedIndex, message ->
                    // asReversed() is a view, so the position in the stored
                    // transcript is the mirror of this one.
                    val index = messages.lastIndex - reversedIndex
                    val newest = index == messages.lastIndex
                    val regenerate: () -> Unit = {
                        choice?.let { picked ->
                            AiChatController.regenerate(context, settings.value, activeId, picked)
                        }
                    }
                    val edit: () -> Unit = {
                        AiChatController.editLast(context, activeId)?.let { taken ->
                            draft = taken.content
                            modeId = taken.chatMode.id
                            formatId = taken.answerFormat.id
                            draftAttachment = taken.attachment
                            draftImagesText = taken.images.joinToString("\n")
                            draftFilesJson = encodeFiles(taken.files)
                        }
                    }
                    MessageBubble(
                        message = message,
                        onRetry = retryFor(message, messages) {
                            choice?.let { picked ->
                                AiChatController.retry(context, settings.value, activeId, picked)
                            }
                        },
                        onRegenerate = regenerate.takeIf {
                            idle && newest && !message.failed &&
                                message.role == AiChatMessage.ROLE_ASSISTANT
                        },
                        onEdit = edit.takeIf { idle && index == lastUser },
                        onReport = reportFor(
                            context = context,
                            message = message,
                            messages = messages,
                            index = index,
                        ),
                        onUndoMemory = { use -> AiChatController.undoMemory(context, activeId, index, use) },
                        onShare = { kind: AiChatExport.Kind ->
                            val question = messages.take(index).lastOrNull { it.role == AiChatMessage.ROLE_USER }?.content.orEmpty()
                            val shared = AiChatExport.share(
                                context, kind, conversation?.title.orEmpty(), question, message.content,
                                open = { AppShareSheet.start(context, it) },
                            )
                            if (!shared) {
                                Toast.makeText(context, context.getString(ImeR.string.ime_ai_chat_share_failed), Toast.LENGTH_SHORT).show()
                            }
                        }.takeIf { message.role == AiChatMessage.ROLE_ASSISTANT && !message.failed && message.content.isNotBlank() },
                        // Suggested next questions, under the newest answer only:
                        // a tap puts one in the box, to send or change.
                        followUps = if (idle && newest) message.followUps else emptyList(),
                        onFollowUp = { draft = it },
                    )
                }
                if (messages.isEmpty() && run == null && draftAttachment.isNotEmpty()) {
                    // Something to ask about already (#697): the keyboard's
                    // Ask AI openings, one tap from a question.
                    item { AskStarters { draft = it } }
                } else if (messages.isEmpty() && run == null) {
                    item {
                        Text(
                            stringResource(ImeR.string.ime_ai_chat_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (draftAttachment.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        attachmentLabel.ifBlank {
                            pluralStringResource(
                                ImeR.plurals.ime_ai_chat_attachment_sent,
                                draftAttachment.length,
                                draftAttachment.length,
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BubbleAction(if (attachmentOpen) R.string.ask_ai_attachment_less else R.string.ask_ai_attachment_view) {
                        attachmentOpen = !attachmentOpen
                    }
                    BubbleAction(CommonR.string.common_delete) {
                        draftAttachment = ""
                        attachmentOpen = false
                    }
                }
                // What is about to be sent, readable in full before it is.
                if (attachmentOpen) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                    ) {
                        Text(
                            draftAttachment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
            if (draftImages.isNotEmpty()) {
                ChatPictures(
                    names = draftImages,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    onRemove = { name -> draftImagesText = (draftImages - name).joinToString("\n") },
                )
            }
            if (draftFiles.isNotEmpty()) {
                ChatFileChips(
                    files = draftFiles,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    onRemove = { file -> draftFilesJson = encodeFiles(draftFiles - file) },
                )
            }
            researchBlock?.let { why ->
                Text(
                    stringResource(why),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                generating = run != null,
                canSend = choice != null && researchBlock == null &&
                    (draft.isNotBlank() || draftImages.isNotEmpty() || draftFiles.isNotEmpty()),
                mode = mode,
                format = format,
                onMode = { modeId = it.id },
                onFormat = { formatId = it.id },
                onSend = {
                    val picked = choice ?: return@Composer
                    val id = activeId.takeIf { it >= 0 }
                        ?: store.newConversation(System.currentTimeMillis()).id
                            .also { activeId = it }
                    AiChatController.send(
                        context, settings.value, id, picked, draft, draftAttachment, draftImages, files = draftFiles,
                        mode = mode, format = format,
                    )
                    draft = ""
                    draftAttachment = ""
                    draftImagesText = ""
                    draftFilesJson = ""
                    attachmentOpen = false
                },
                onStop = { AiChatController.stop(context) },
                onAttach = { attachSheet = true }.takeIf { run == null },
                reasoning = settings.watch { it.ai.chatReasoning }
                    .takeIf { AiChatController.reasoningSwitch(settings.value, choice) },
                onReasoning = { on -> scope.launch { SettingsRepository(context.applicationContext).setAiChatReasoning(on) } },
            )
        }
    }

    if (attachSheet) {
        AttachSheet(
            canAddPictures = draftImages.size < AiChatStore.MAX_IMAGES,
            canAddFiles = draftFiles.size < AiChatStore.MAX_FILES,
            onDismiss = { attachSheet = false },
            onImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFiles = { pickFiles.launch(AiChatPickActivity.FILE_TYPES) },
            onPhoto = {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    cameraGrant.launch(Manifest.permission.CAMERA)
                }
            },
            onPaste = {
                pasteText(context)?.let { clip -> draft = if (draft.isEmpty()) clip else draft + clip }
            },
        )
    }
}

/**
 * The "+" sheet under the message box (#882): pictures from the gallery, a
 * file, a photo, and the clipboard's text. The keyboard's chat has the same
 * menu, plus what only a keyboard can reach: the field's own text, the
 * clipboard's picture and the newest screenshot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    canAddPictures: Boolean,
    canAddFiles: Boolean,
    onDismiss: () -> Unit,
    onImages: () -> Unit,
    onFiles: () -> Unit,
    onPhoto: () -> Unit,
    onPaste: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AttachSheetItem(Icons.Outlined.PhotoLibrary, ImeR.string.ime_ai_chat_attach_image, canAddPictures) {
                onDismiss()
                onImages()
            }
            AttachSheetItem(Icons.Outlined.AttachFile, ImeR.string.ime_ai_chat_attach_file, canAddFiles) {
                onDismiss()
                onFiles()
            }
            AttachSheetItem(Icons.Outlined.PhotoCamera, ImeR.string.ime_ai_chat_attach_photo, canAddPictures) {
                onDismiss()
                onPhoto()
            }
            AttachSheetItem(Icons.Outlined.ContentPaste, CommonR.string.common_paste, true) {
                onDismiss()
                onPaste()
            }
        }
    }
}

/** One of [AttachSheet]'s buttons: an icon, named under it in small print. */
@Composable
private fun AttachSheetItem(icon: ImageVector, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = stringResource(label))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The clipboard's text, or null when it holds none. */
private fun pasteText(context: Context): String? = runCatching {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()?.takeIf { it.isNotEmpty() }

private val fileListJson = Json { ignoreUnknownKeys = true }

private fun encodeFiles(files: List<AiChatFile>): String =
    if (files.isEmpty()) "" else fileListJson.encodeToString(ListSerializer(AiChatFile.serializer()), files)

private fun decodeFiles(text: String): List<AiChatFile> =
    if (text.isEmpty()) emptyList() else runCatching {
        fileListJson.decodeFromString(ListSerializer(AiChatFile.serializer()), text)
    }.getOrDefault(emptyList())

/**
 * What picking brought in: pictures scaled into the chat's folder, files read
 * or copied, and why the last one that did not arrive did not, as a string
 * resource (0 when everything did). Blocking.
 */
private fun importPicks(
    context: Context,
    uris: List<Uri>,
    asFiles: Boolean,
    keep: Boolean,
    haveImages: Int,
    haveFiles: Int,
): Triple<List<String>, List<AiChatFile>, Int> {
    val images = ArrayList<String>()
    val files = ArrayList<AiChatFile>()
    var notice = 0
    for (uri in uris) {
        val imported = if (asFiles) AiChatFiles.import(context, uri, keep) else AiChatFiles.Imported.Picture
        when (imported) {
            AiChatFiles.Imported.Picture -> when {
                haveImages + images.size >= AiChatStore.MAX_IMAGES -> notice = ImeR.string.ime_ai_chat_attach_full
                else -> AiChatImages.import(context, uri, keep)?.let(images::add)
                    ?: run { notice = ImeR.string.ime_ai_chat_picture_unreadable }
            }
            is AiChatFiles.Imported.Done ->
                if (haveFiles + files.size >= AiChatStore.MAX_FILES) {
                    notice = ImeR.string.ime_ai_chat_attach_full
                } else {
                    files += imported.file
                }
            AiChatFiles.Imported.TooBig -> notice = ImeR.string.ime_ai_chat_file_too_big
            AiChatFiles.Imported.Unreadable -> notice = ImeR.string.ime_ai_chat_file_unreadable
        }
    }
    return Triple(images, files, notice)
}

/** A message's files (#882), one chip each; [onRemove] adds a cross for the ones not sent yet. */
@Composable
private fun ChatFileChips(files: List<AiChatFile>, modifier: Modifier = Modifier, onRemove: ((AiChatFile) -> Unit)? = null) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(files) { file ->
            AssistChip(
                onClick = { onRemove?.invoke(file) },
                label = { Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                leadingIcon = { Icon(fileIcon(file), contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = onRemove?.let {
                    {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(ImeR.string.ime_ai_chat_file_remove_desc),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

private fun copyText(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("", text))
    }
}

/**
 * The Retry action for a failed assistant turn: resends the user message
 * right before it. Only the newest failed message gets one — retrying an old
 * failure mid-transcript would answer out of order.
 */
private fun retryFor(
    message: AiChatMessage,
    messages: List<AiChatMessage>,
    onRetry: () -> Unit,
): (() -> Unit)? {
    if (!message.failed || message !== messages.lastOrNull()) return null
    if (messages.none { it.role == AiChatMessage.ROLE_USER }) return null
    return onRetry
}

/**
 * The Report action for an answer the model produced.
 *
 * Play's AI-generated content policy asks for an in-app route to report a bad
 * generation on every surface that shows one, and this screen is the settings
 * app's only such surface — the keyboard's own AI panel has its own button
 * (`WMKeyboardService.onAiReport`), and this mirrors it so both reports arrive
 * in the same shape. Nothing is sent from here: [Support.email] opens a draft
 * in the user's mail app, quoting the prompt and the answer, which the user
 * reads and can edit or abandon before it goes anywhere.
 *
 * Null outside Play Store builds, since the button exists for Play's policy and
 * the GitHub and F-Droid builds have no such requirement. Null too for anything
 * that is not a finished answer: the user's own messages are not generated
 * content, and a failed turn has an error where the answer would be.
 */
private fun reportFor(
    context: Context,
    message: AiChatMessage,
    messages: List<AiChatMessage>,
    index: Int,
): (() -> Unit)? {
    if (!BuildConfig.ENABLE_PLAY_STORE) return null
    if (message.role != AiChatMessage.ROLE_ASSISTANT) return null
    if (message.failed || message.content.isBlank()) return null
    return {
        val sent = Support.email(
            context,
            "WM Keyboard: AI chat report",
            Support.aiGenerationReport(
                action = "Chat",
                provider = providerLabel(context, message.provider),
                model = message.model,
                input = promptFor(messages, index),
                output = message.content,
            ),
        )
        if (!sent) {
            Toast.makeText(
                context,
                context.getString(R.string.about_no_email_app_error, Support.EMAIL),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * The user message the answer at [index] replies to. Walks backwards rather
 * than assuming `index - 1`: a retried turn can leave a failed assistant
 * message between the prompt and the answer that finally worked.
 */
private fun promptFor(messages: List<AiChatMessage>, index: Int): String =
    (index - 1 downTo 0).asSequence()
        .map(messages::get)
        .firstOrNull { it.role == AiChatMessage.ROLE_USER }
        ?.promptText()
        .orEmpty()

/**
 * The stored provider name as the user sees it. A stored record keeps the enum
 * *name* so it survives a rename, so a provider this build no longer knows
 * falls back to the raw string rather than dropping out of the report.
 */
private fun providerLabel(context: Context, stored: String): String =
    AiProvider.entries.firstOrNull { it.name == stored }
        ?.let { context.getString(it.labelRes) }
        ?: stored.ifBlank { "(unknown)" }

/**
 * The lock the chat shows while Privatemode answers (#1145). A tap says
 * what it means, which is never "verified": on the phone the hardware
 * attestation is not checked, and through a proxy only the proxy checks it.
 */
@Composable
private fun PrivacyLock(privacy: AiPrivacy) {
    if (privacy == AiPrivacy.PLAIN) return
    val context = LocalContext.current
    val explanation = stringResource(
        if (privacy == AiPrivacy.PROXY) R.string.toolai_ai_chat_privacy_proxy else R.string.toolai_ai_chat_privacy_phone,
    )
    IconButton(onClick = { Toast.makeText(context, explanation, Toast.LENGTH_LONG).show() }) {
        Icon(Icons.Outlined.Lock, contentDescription = explanation)
    }
}

@Composable
private fun ModelChips(
    choices: List<ModelChoice>,
    choice: ModelChoice?,
    enabled: Boolean,
    onPick: (ModelChoice) -> Unit,
) {
    if (choices.size < 2) return
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(choices, key = { it.key }) { option ->
            FilterChip(
                selected = choice == option,
                enabled = enabled,
                onClick = { onPick(option) },
                label = {
                    Text(
                        if (option.provider == AiProvider.ON_DEVICE) {
                            AiChatController.localModelLabel(option.localModelId)
                        } else {
                            option.name.ifEmpty { stringResource(option.provider.labelRes) }
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiChatMessage,
    onRetry: (() -> Unit)?,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onUndoMemory: ((Int) -> Unit)? = null,
    /** Shares the answer as a file or text; null on anything that is not a finished answer. */
    onShare: ((AiChatExport.Kind) -> Unit)? = null,
    followUps: List<String> = emptyList(),
    onFollowUp: (String) -> Unit = {},
) {
    val fromUser = message.role == AiChatMessage.ROLE_USER
    val context = LocalContext.current
    val copy: (String) -> Unit = { text -> copyText(context, text) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        // The user's turn is a bubble at the right; an answer is the page
        // itself (#882), full width with no background, as long as it runs.
        // A failed turn keeps its error colour so it still reads as one.
        Surface(
            shape = if (fromUser) {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
            } else {
                RoundedCornerShape(12.dp)
            },
            color = when {
                message.failed -> MaterialTheme.colorScheme.errorContainer
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            },
            modifier = if (fromUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = if (fromUser || message.failed) 12.dp else 4.dp, vertical = 8.dp)) {
                if (message.images.isNotEmpty()) {
                    ChatPictures(message.images, Modifier.padding(bottom = 6.dp))
                }
                if (message.files.isNotEmpty()) ChatFileChips(message.files, Modifier.padding(bottom = 6.dp))
                // How the answer came to be, over it (#882): the reasoning,
                // then each tool call — kept on a failed turn too, since a run
                // that died after searching still searched.
                if (!fromUser) {
                    val ink = when {
                        message.failed -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Chain(message.reasoning, live = false, message.toolUses, ink, copy, onUndoMemory)
                }
                if (message.failed) {
                    Text(
                        message.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    onRetry?.let { MessageAction(Icons.Outlined.Refresh, ImeR.string.ime_ai_chat_retry, it) }
                } else {
                    val ink = if (fromUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    // Selectable here, where the keyboard's own chat cannot be:
                    // an activity window can hold selection handles, an IME
                    // window cannot.
                    SelectionContainer {
                        if (fromUser) {
                            // The user's own words are drawn as written.
                            Text(message.content, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            // The same renderer the keyboard's chat draws with,
                            // so an answer looks the same on both.
                            ChatMarkdown(
                                message.content,
                                ChatMarkdownColors(
                                    text = ink,
                                    dim = ink.copy(alpha = 0.65f),
                                    codeBackground = ink.copy(alpha = 0.10f),
                                ),
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                codeActions = { block ->
                                    BubbleAction(CommonR.string.common_copy) { copy(block.code) }
                                },
                            )
                        }
                    }
                    if (message.attachment.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                ImeR.plurals.ime_ai_chat_attachment_sent,
                                message.attachment.length,
                                message.attachment.length,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.65f),
                        )
                    }
                    if (message.stopped) {
                        Text(
                            stringResource(ImeR.string.ime_ai_chat_stopped),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Plain icons with no fill behind them (#882): under an
                    // answer at its start, under the user's bubble at its end.
                    Row(modifier = Modifier.align(if (fromUser) Alignment.End else Alignment.Start)) {
                        MessageAction(Icons.Outlined.ContentCopy, CommonR.string.common_copy) { copy(message.content) }
                        onRegenerate?.let { MessageAction(Icons.Outlined.Refresh, ImeR.string.ime_ai_chat_regenerate, it) }
                        onEdit?.let { MessageAction(Icons.Outlined.Edit, CommonR.string.common_edit, it) }
                        onShare?.let { share -> ShareAction(share) }
                        // Only ever on an answer in a Play Store build;
                        // reportFor returns null for everything else.
                        onReport?.let { MessageAction(Icons.Outlined.OutlinedFlag, ImeR.string.ime_ai_chat_report_action, it) }
                    }
                    if (followUps.isNotEmpty()) FollowUpChips(followUps, onFollowUp)
                }
            }
        }
    }
}

/** [ChatChain] in Material colours: a page opens in the browser, an address copies. */
@Composable
private fun Chain(
    reasoning: String,
    live: Boolean,
    uses: List<AiToolActivity>,
    ink: Color,
    onCopy: (String) -> Unit,
    onUndoMemory: ((Int) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    ChatChain(
        reasoning = reasoning,
        reasoningLive = live,
        uses = uses,
        colors = ChatMarkdownColors(
            text = ink,
            dim = ink.copy(alpha = 0.7f),
            codeBackground = ink.copy(alpha = 0.10f),
        ),
        errorColor = MaterialTheme.colorScheme.error,
        onOpen = { url -> runCatching { uriHandler.openUri(url) } },
        onCopy = onCopy,
        fontSize = MaterialTheme.typography.labelMedium.fontSize,
        textSize = MaterialTheme.typography.bodyMedium.fontSize,
        // The same controller runs the keyboard's chat, so a question asked
        // here is the one asked there; either screen can answer it (#573).
        onApproval = { AiChatController.answerTool(it) },
        onUndoMemory = onUndoMemory,
    )
}

/** One of the icons under a message: no fill, named for TalkBack. */
@Composable
private fun MessageAction(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One of the small text buttons: a code block's Copy, and the attachment line's. */
@Composable
private fun BubbleAction(@StringRes label: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The answer forming, drawn as the finished one will be (#882): the reasoning
 * and the tool calls so far as folded steps, then the answer, its tables and
 * maths taking shape as they stream. Before any of it, a progress line.
 */
@Composable
private fun StreamingBubble(run: AiChatController.ChatRun) {
    val parts = AiThinking.parts(run.partial, run.implicitThink)
    val context = LocalContext.current
    val toolRunning = run.tools.any { it.running }
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Chain(parts.reasoning, live = parts.thinking, run.tools, ink, { copyText(context, it) })
        val shown = AiFollowUps.visible(parts.output)
        when {
            shown.isNotBlank() -> ChatMarkdown(
                shown,
                ChatMarkdownColors(
                    text = ink,
                    dim = ink.copy(alpha = 0.65f),
                    codeBackground = ink.copy(alpha = 0.10f),
                ),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            )
            // The steps above already say what is happening.
            parts.thinking || toolRunning -> Unit
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(run.phase.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Pictures in a row (#698): the ones about to be sent, each with a way to take
 * it back off, or the ones a sent message carried. A picture whose file has
 * gone shows as an empty tile rather than not at all, so the numbers a model
 * uses still line up with what is on screen.
 */
@Composable
private fun ChatPictures(names: List<String>, modifier: Modifier = Modifier, onRemove: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(names) { name ->
            Box {
                AsyncImage(
                    model = AiChatImages.file(context, name),
                    contentDescription = stringResource(R.string.ai_chat_picture_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(PICTURE_SIZE)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                onRemove?.let { remove ->
                    IconButton(
                        onClick = { remove(name) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ai_chat_picture_remove_desc),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                        )
                    }
                }
            }
        }
    }
}

private val PICTURE_SIZE = 72.dp

/**
 * The message box (#882): one rounded box holding what is being written, and
 * under it the controls that belong to it, the Reasoning switch at the start
 * and "+" and Send at the end, the same as the keyboard's chat.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Opens the "+" sheet; null while an answer is coming in. */
    onAttach: (() -> Unit)? = null,
    /** The Reasoning switch's state; null hides it, for a model it does nothing for. */
    reasoning: Boolean? = null,
    onReasoning: (Boolean) -> Unit = {},
    mode: AiChatMode = AiChatMode.CHAT,
    format: AiAnswerFormat = AiAnswerFormat.AUTO,
    onMode: (AiChatMode) -> Unit = {},
    onFormat: (AiAnswerFormat) -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 6.dp)) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(stringResource(mode.hintRes)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The switches scroll sideways rather than squeeze the buttons
                // at the end off a narrow screen.
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (reasoning != null) {
                        FilterChip(
                            selected = reasoning,
                            onClick = { onReasoning(!reasoning) },
                            label = { Text(stringResource(ImeR.string.ime_ai_chat_reasoning)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                    PickerChip(
                        selected = mode != AiChatMode.CHAT,
                        icon = mode.icon,
                        label = stringResource(mode.labelRes),
                        description = stringResource(ImeR.string.ime_ai_chat_mode_desc),
                        options = AiChatMode.entries.map { Triple(it, it.icon, stringResource(it.labelRes)) },
                        onPick = onMode,
                    )
                    PickerChip(
                        selected = format != AiAnswerFormat.AUTO,
                        icon = format.icon,
                        label = stringResource(format.labelRes),
                        description = stringResource(ImeR.string.ime_ai_chat_format_desc),
                        options = AiAnswerFormat.entries.map { Triple(it, it.icon, stringResource(it.labelRes)) },
                        onPick = onFormat,
                    )
                }
                IconButton(onClick = { onAttach?.invoke() }, enabled = onAttach != null) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(ImeR.string.ime_ai_chat_attach_menu_desc))
                }
                if (generating) {
                    FilledIconButton(onClick = onStop) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = stringResource(ImeR.string.ime_ai_chat_stop),
                        )
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = stringResource(ImeR.string.ime_ai_chat_send),
                        )
                    }
                }
            }
        }
    }
}

/** A chip that opens a menu of [options] under it: the message box's mode and format. */
@Composable
private fun <T> PickerChip(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    description: String,
    options: List<Triple<T, ImageVector, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, mark, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    leadingIcon = { Icon(mark, contentDescription = null) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

/** The Share icon under an answer, with its menu: a Markdown file, a PDF, or the text. */
@Composable
private fun ShareAction(onShare: (AiChatExport.Kind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MessageAction(Icons.Outlined.Share, ImeR.string.ime_ai_chat_share) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                AiChatExport.Kind.MARKDOWN to ImeR.string.ime_ai_chat_share_markdown,
                AiChatExport.Kind.PDF to ImeR.string.ime_ai_chat_share_pdf,
                AiChatExport.Kind.TEXT to ImeR.string.ime_ai_chat_share_text,
            ).forEach { (kind, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onShare(kind)
                    },
                )
            }
        }
    }
}

/** The questions a model suggested asking next, one chip each; a tap puts one in the message box. */
@Composable
private fun FollowUpChips(questions: List<String>, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(ImeR.string.ime_ai_chat_follow_ups_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        questions.forEach { question ->
            SuggestionChip(
                onClick = { onPick(question) },
                label = { Text(question, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/**
 * Openings for a chat that starts with something to ask about (#697): the
 * keyboard's Ask AI chips, which put their question in the composer rather
 * than sending it, so it can still be changed.
 */
@Composable
private fun AskStarters(onPick: (String) -> Unit) {
    val starters = listOf(
        ImeR.string.ime_ai_chat_ask_explain to ImeR.string.ime_ai_chat_ask_explain_prompt,
        ImeR.string.ime_ai_chat_ask_summary to ImeR.string.ime_ai_chat_ask_summary_prompt,
        ImeR.string.ime_ai_chat_ask_words to ImeR.string.ime_ai_chat_ask_words_prompt,
    ).map { (label, prompt) -> stringResource(label) to stringResource(prompt) }
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(
            stringResource(ImeR.string.ime_ai_chat_ask_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(starters) { (label, prompt) ->
                FilterChip(selected = false, onClick = { onPick(prompt) }, label = { Text(label) })
            }
        }
    }
}

/** Neither a downloaded model nor a configured provider: point at setup. */
@Composable
private fun NoBackendState(onOpenAiSettings: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                stringResource(ImeR.string.ime_ai_chat_no_backend_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(ImeR.string.ime_ai_chat_no_backend_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenAiSettings) {
                Text(stringResource(ImeR.string.ime_ai_chat_setup_button))
            }
        }
    }
}
