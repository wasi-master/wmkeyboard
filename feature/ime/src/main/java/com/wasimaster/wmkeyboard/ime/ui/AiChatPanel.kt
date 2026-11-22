package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Input
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AudioFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatQuote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureAsPdf
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Screenshot
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VideoFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OutlinedFlag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.app.ChannelFeatures
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.app.AiChatPickActivity
import com.wasimaster.wmkeyboard.core.aichat.AiChatConversation
import com.wasimaster.wmkeyboard.core.aichat.AiChatFile
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiAnswerFormat
import com.wasimaster.wmkeyboard.core.tools.AiChatMode
import com.wasimaster.wmkeyboard.core.tools.AiFollowUps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController.ModelChoice
import com.wasimaster.wmkeyboard.ime.aichat.AiChatImages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR

/*
 * The AI panel's chat mode (#280): a conversation with the model, on the
 * keyboard, next to the field the answer is for.
 *
 * What it draws is AiChatController's — the saved transcript and the one live
 * answer — read here exactly as the chat screen in the settings app reads them,
 * so the two are one chat on two surfaces. What it adds is what only a keyboard
 * has: a composer its own keys write into, the field behind it to quote from,
 * and Insert, which is the point of chatting here at all.
 *
 * No SelectionContainer anywhere. Selection handles and the copy toolbar are
 * popup windows, and a popup over an IME window is the untrusted-touch trap;
 * a whole answer, or one code block, is copied by a button instead.
 */

/**
 * How tall the panel asks to stand while the composer has the keys under it.
 * More than the other panels ask for: a conversation is read, not glanced at
 * (#352). What the screen cannot spare comes off the panel, never off the
 * keys: the body clamps it to the room left above them.
 */
internal val AiChatCompactHeight = 320.dp

/** How far the chat grows the keyboard while nothing is being typed; was 120dp before #352. */
internal val AiChatExtraHeight = 200.dp

/** The header's mode switch. [base] indices 0 and 1 of the ACTIONS region are these two. */
@Composable
internal fun AiModeSwitch(state: KeyboardUiState, focusedAction: Int?, onChat: (AiChatAction) -> Unit) {
    ToolPanelChip(
        stringResource(R.string.ime_ai_mode_actions),
        selected = !state.aiChat.open,
        modifier = Modifier.focusRing(focusedAction == 0),
    ) { onChat(AiChatAction.SetMode(chat = false)) }
    Spacer(Modifier.width(4.dp))
    ToolPanelChip(
        stringResource(R.string.ime_ai_mode_chat),
        selected = state.aiChat.open,
        modifier = Modifier.focusRing(focusedAction == 1),
    ) { onChat(AiChatAction.SetMode(chat = true)) }
    Spacer(Modifier.width(6.dp))
}

/** How many ACTIONS slots [AiChatHeaderActions] draws, after the mode switch. */
internal const val AiChatHeaderActionCount = 3

/**
 * The rest of the header in chat mode: which conversation this is (a tap opens
 * the list), a new chat, and the way over to the app. Slots [base]..[base]+2 of
 * the ACTIONS region; [activateAiChatHeader] is the same three for Enter.
 */
@Composable
internal fun RowScope.AiChatHeaderActions(
    state: KeyboardUiState,
    focusedAction: Int?,
    base: Int,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val version by AiChatController.storeVersion.collectAsState()
    val title = remember(version, state.aiChat.conversationId) {
        AiChatController.store(context).get(state.aiChat.conversationId)?.title.orEmpty()
    }.ifBlank { stringResource(R.string.ime_ai_chat_untitled) }
    val shape = kb.chipShape()
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(end = 4.dp)
            .clip(shape)
            .focusRing(focusedAction == base)
            .background(if (state.aiChat.showSessions) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable { onChat(AiChatAction.ToggleSessions) }
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (state.aiChat.showSessions) kb.chipActiveText else kb.chipText
        Text(
            title,
            color = ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = stringResource(R.string.ime_ai_chat_sessions_desc),
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
    }
    ChatIconButton(
        Icons.Outlined.Add,
        stringResource(R.string.ime_ai_chat_new),
        modifier = Modifier.focusRing(focusedAction == base + 1, CircleShape),
    ) { onChat(AiChatAction.NewChat) }
    Spacer(Modifier.width(4.dp))
    ChatIconButton(
        Icons.AutoMirrored.Outlined.OpenInNew,
        stringResource(R.string.ime_ai_chat_open_in_app_desc),
        modifier = Modifier.focusRing(focusedAction == base + 2, CircleShape),
    ) { onChat(AiChatAction.OpenInApp()) }
    Spacer(Modifier.width(4.dp))
}

/** Enter on slot [index] (0-based within the chat's own three) of the header. */
internal fun activateAiChatHeader(index: Int, onChat: (AiChatAction) -> Unit) {
    when (index) {
        0 -> onChat(AiChatAction.ToggleSessions)
        1 -> onChat(AiChatAction.NewChat)
        2 -> onChat(AiChatAction.OpenInApp())
    }
}

@Composable
internal fun AiChatPanel(
    state: KeyboardUiState,
    onChat: (AiChatAction) -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val context = LocalContext.current
    val chat = state.aiChat
    val store = AiChatController.store(context)
    val version by AiChatController.storeVersion.collectAsState()
    val run by AiChatController.run.collectAsState()
    // Cheap file stats, keyed on the store so a model that finished
    // downloading while the panel was up is offered without a reopen.
    val choices = remember(version, state.settings.ai, state.settings.providers, chat.showSessions) {
        AiChatController.choices(context, state.settings)
    }
    val choice = remember(choices, chat.modelKey, version) {
        choices.firstOrNull { it.key == chat.modelKey } ?: AiChatController.initialChoice(store, choices)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        when {
            choices.isEmpty() -> {
                ChatFocusTargets(emptyList(), listOf { onOpenToolSettings(ToolbarTool.AI) })
                NoBackend(state) { onOpenToolSettings(ToolbarTool.AI) }
            }
            chat.showSessions -> {
                val conversations = remember(version) { store.items() }
                Sessions(state, conversations, onChat)
            }
            else -> {
                val conversation = remember(version, chat.conversationId) { store.get(chat.conversationId) }
                val live = run?.takeIf { it.conversationId == chat.conversationId }
                // The picker steps aside while typing: every dp there belongs
                // to the conversation and the composer.
                if (!chat.composing && choices.size > 1) {
                    val seeing = remember(choices, state.settings.ai, state.settings.providers) {
                        choices.filter { com.wasimaster.wmkeyboard.ime.aichat.PictureDescriber.seesPictures(context, state.settings, it) }.mapTo(HashSet()) { it.key }
                    }
                    ModelRow(choices, choice, enabled = run == null, onChat, seeing)
                }
                val reasoning = remember(choice, state.settings.ai, state.settings.providers) {
                    AiChatController.reasoningSwitch(state.settings, choice)
                }
                Transcript(
                    state = state,
                    messages = conversation?.messages.orEmpty(),
                    live = live,
                    busy = run != null,
                    reasoning = reasoning,
                    onChat = onChat,
                    modifier = Modifier.weight(1f),
                )
                // Deep research needs a model that calls tools and a search
                // provider; the line over the box says which is missing.
                val researchBlock = remember(chat.mode, choice, state.settings.ai, state.settings.providers) {
                    if (AiChatMode.of(chat.mode) == AiChatMode.RESEARCH) AiChatController.researchBlock(state.settings, choice) else null
                }
                Composer(
                    state = state,
                    generating = run != null,
                    reasoning = reasoning,
                    // Pictures alone are a message, as on the chat screen.
                    canSend = choice != null && run == null && researchBlock == null &&
                        (chat.draft.isNotBlank() || chat.images.isNotEmpty() || chat.files.isNotEmpty()),
                    onChat = onChat,
                    notice = researchBlock,
                )
            }
        }
    }
}

/**
 * The ring's CHIPS and RESULTS regions, published from one place whatever the
 * panel is showing, for the reason [AiPanel]'s own publisher gives: the last
 * SideEffect always describes what is on screen.
 */
@Composable
private fun ChatFocusTargets(chips: List<() -> Unit>, results: List<() -> Unit>) {
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.CHIPS,
        count = chips.size,
        columns = chips.size.coerceAtLeast(1),
    ) { index -> chips.getOrNull(index)?.invoke() }
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.RESULTS,
        count = results.size,
        columns = results.size.coerceAtLeast(1),
    ) { index -> results.getOrNull(index)?.invoke() }
}

@Composable
private fun ModelRow(
    choices: List<ModelChoice>,
    choice: ModelChoice?,
    enabled: Boolean,
    onChat: (AiChatAction) -> Unit,
    /** The keys of the choices known to read pictures, which carry a mark. */
    seeing: Set<String> = emptySet(),
) {
    val kb = LocalKbTheme.current
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "label") {
            Text(
                stringResource(R.string.ime_ai_model_label),
                color = kb.secondaryText,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
        // The one in use first, as the actions' picker has it: the row
        // scrolls, and the choice in force should never be the one off screen.
        items(choices.sortedByDescending { it == choice }, key = { it.key }) { option ->
            ToolPanelChip(
                modelLabel(option),
                selected = option == choice,
                enabled = enabled,
                modifier = Modifier.animateItem(),
                mark = Icons.Outlined.Image.takeIf { option.key in seeing },
                markLabel = stringResource(R.string.ime_ai_model_reads_pictures),
            ) { onChat(AiChatAction.PickModel(option.key)) }
        }
    }
}

@Composable
private fun modelLabel(choice: ModelChoice): String =
    if (choice.provider == AiProvider.ON_DEVICE) {
        AiChatController.localModelLabel(choice.localModelId)
    } else {
        choice.name.ifEmpty { stringResource(choice.provider.labelRes) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transcript(
    state: KeyboardUiState,
    messages: List<AiChatMessage>,
    live: AiChatController.ChatRun?,
    busy: Boolean,
    /** The composer shows its Reasoning switch; see [ComposerSlots]. */
    reasoning: Boolean,
    onChat: (AiChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = state.aiChat
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Which bubble has its actions out. The newest answer always has; a tap
    // brings out any other's. Forgotten with the conversation.
    var selected by remember(chat.conversationId) { mutableStateOf<Int?>(null) }
    var copied by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_FLASH_MS)
            copied = null
        }
    }

    // reverseLayout: index 0 is the newest, at the bottom.
    val away by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48 }
    }
    // A new message is always gone to. A growing answer is followed only by a
    // reader who is already at the bottom: scrolling up to reread is a request
    // to be left there.
    LaunchedEffect(messages.size, live != null) { listState.scrollToItem(0) }
    LaunchedEffect(live?.partial?.length) { if (!away) listState.scrollToItem(0) }

    val last = messages.lastOrNull()
    val lastAnswer = messages.lastIndex.takeIf {
        !busy && last != null && last.role == AiChatMessage.ROLE_ASSISTANT
    }
    val copy: (Int, String) -> Unit = { index, text ->
        onChat(AiChatAction.Copy(text))
        copied = index
    }
    ChatFocusTargets(
        chips = composerTargets(state, busy, reasoning, onChat),
        results = when {
            lastAnswer == null || last == null -> emptyList()
            last.failed -> listOf { onChat(AiChatAction.Retry) }
            else -> listOf(
                { onChat(AiChatAction.Insert(last.content)) },
                { copy(messages.lastIndex, last.content) },
                { onChat(AiChatAction.Regenerate) },
            )
        },
    )
    val focusedResult = state.focusedIndex(FocusRegion.RESULTS)

    Box(modifier = modifier.fillMaxWidth()) {
        if (messages.isEmpty() && live == null) {
            EmptyChat(state, onChat)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // A tap on the conversation puts the keys away, the way a
                    // tap outside a field does anywhere else.
                    .clickable(
                        enabled = chat.composing,
                        indication = null,
                        interactionSource = null,
                    ) { onChat(AiChatAction.BlurComposer) },
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (live != null) {
                    item(key = "live") { StreamingBubble(live, onChat) }
                }
                itemsIndexed(
                    messages.asReversed(),
                    // The position in the stored transcript: stable while the
                    // conversation only grows, which is all it does under a
                    // reader's eyes.
                    key = { reversed, _ -> messages.lastIndex - reversed },
                ) { reversed, message ->
                    val index = messages.lastIndex - reversed
                    val newest = index == messages.lastIndex
                    MessageBubble(
                        message = message,
                        actionsOut = !busy && (selected == index || (newest && index == lastAnswer)),
                        copied = copied == index,
                        // Only the newest turn can be redone or taken back:
                        // doing either mid-transcript would answer out of order.
                        canRegenerate = newest && !busy,
                        canEdit = !busy && message.role == AiChatMessage.ROLE_USER &&
                            index == messages.indexOfLast { it.role == AiChatMessage.ROLE_USER },
                        focusedAction = focusedResult.takeIf { newest },
                        onTap = { selected = if (selected == index) null else index },
                        onCopy = { copy(index, it) },
                        onReport = { onChat(AiChatAction.Report(index)) },
                        onChat = onChat,
                        onUndoMemory = { use -> onChat(AiChatAction.UndoMemory(index, use)) },
                        onShare = { onChat(AiChatAction.Share(index)) },
                        followUps = if (newest && !busy) message.followUps else emptyList(),
                    )
                }
            }
            if (away) {
                ToolPanelChip(
                    "↓ " + stringResource(R.string.ime_ai_chat_latest),
                    selected = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                ) { scope.launch { listState.animateScrollToItem(0) } }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: AiChatMessage,
    actionsOut: Boolean,
    copied: Boolean,
    canRegenerate: Boolean,
    canEdit: Boolean,
    focusedAction: Int?,
    onTap: () -> Unit,
    onCopy: (String) -> Unit,
    onReport: () -> Unit,
    onChat: (AiChatAction) -> Unit,
    onUndoMemory: (Int) -> Unit,
    onShare: () -> Unit = {},
    /** Questions the model suggested asking next; a tap puts one in the composer. */
    followUps: List<String> = emptyList(),
) {
    val kb = LocalKbTheme.current
    val fromUser = message.role == AiChatMessage.ROLE_USER
    val ink = if (fromUser) kb.chipActiveText else answerInk(kb)
    val colors = ChatMarkdownColors(
        text = ink,
        dim = ink.copy(alpha = ink.alpha * 0.6f),
        codeBackground = ink.copy(alpha = 0.10f),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Row {
            if (fromUser) Spacer(Modifier.width(BUBBLE_INSET))
            // The user's turn is a bubble at the right; an answer is the page
            // itself (#882), full width and on the panel, as long as it runs.
            Column(
                modifier = if (fromUser) {
                    Modifier
                        .weight(1f, fill = false)
                        .clip(bubbleShape(fromUser))
                        .frostGlass(kb, bubbleShape(fromUser), FrostSurface.CHIPS)
                        .background(kb.chipActive)
                        .combinedClickable(onClick = onTap, onLongClick = { onCopy(message.content) })
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = onTap, onLongClick = { onCopy(message.content) })
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                },
            ) {
                // The pictures a message carried, over its words (#698).
                if (message.images.isNotEmpty()) {
                    ChatPictures(message.images, MESSAGE_PICTURE_SIZE, Modifier.padding(bottom = 5.dp))
                }
                if (message.files.isNotEmpty()) ChatFiles(message.files, ink, Modifier.padding(bottom = 5.dp))
                // How the answer came to be, over it: the reasoning, then each
                // tool call, as steps down to the answer (#882).
                Chain(message.reasoning, live = false, message.toolUses, colors, onChat, onUndoMemory)
                when {
                    message.failed -> Text(message.error, color = kb.accent, fontSize = 13.sp, lineHeight = 18.sp)
                    // The user's own words are drawn as written: an asterisk
                    // they typed is an asterisk.
                    // A message of pictures alone has no words to draw.
                    fromUser -> if (message.content.isNotEmpty()) {
                        Text(message.content, color = ink, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    else -> ChatMarkdown(message.content, colors, codeActions = { block ->
                        CodeAction(stringResource(CommonR.string.common_copy), colors) { onCopy(block.code) }
                        CodeAction(stringResource(R.string.ime_ai_insert), colors) {
                            onChat(AiChatAction.Insert(block.code, raw = true))
                        }
                    })
                }
                if (message.attachment.isNotEmpty()) {
                    Caption(
                        pluralStringResource(
                            R.plurals.ime_ai_chat_attachment_sent,
                            message.attachment.length,
                            message.attachment.length,
                        ),
                        colors.dim,
                    )
                }
                if (message.stopped) Caption(stringResource(R.string.ime_ai_chat_stopped), colors.dim)
            }
        }
        if (actionsOut) {
            MessageActions(message, copied, canRegenerate, canEdit, focusedAction, onCopy, onReport, onChat, onShare)
        }
        if (followUps.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                followUps.forEach { question ->
                    ToolPanelChip(question) { onChat(AiChatAction.Starter(question, attach = false)) }
                }
            }
        }
    }
}

/**
 * What can be done with one message, under it: plain icons with no chip
 * behind them (#882), each named by a long press. The indices mirror
 * [Transcript]'s RESULTS list.
 */
@Composable
private fun MessageActions(
    message: AiChatMessage,
    copied: Boolean,
    canRegenerate: Boolean,
    canEdit: Boolean,
    focusedAction: Int?,
    onCopy: (String) -> Unit,
    onReport: () -> Unit,
    onChat: (AiChatAction) -> Unit,
    onShare: () -> Unit = {},
) {
    val fromUser = message.role == AiChatMessage.ROLE_USER
    Row(
        modifier = Modifier.padding(top = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.failed) {
            if (canRegenerate) {
                MessageAction(
                    Icons.Outlined.Refresh,
                    stringResource(R.string.ime_ai_chat_retry),
                    Modifier.focusRing(focusedAction == 0, CircleShape),
                ) { onChat(AiChatAction.Retry) }
            }
            return@Row
        }
        // Insert leads on an answer, which is what the user is here to take
        // away. Their own message has no use for it.
        if (!fromUser) {
            MessageAction(
                Icons.AutoMirrored.Outlined.Input,
                stringResource(R.string.ime_ai_insert),
                Modifier.focusRing(focusedAction == 0, CircleShape),
            ) { onChat(AiChatAction.Insert(message.content)) }
        }
        MessageAction(
            if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
            stringResource(if (copied) R.string.ime_ai_chat_copied else CommonR.string.common_copy),
            Modifier.focusRing(!fromUser && focusedAction == 1, CircleShape),
        ) { onCopy(message.content) }
        if (!fromUser && canRegenerate) {
            MessageAction(
                Icons.Outlined.Refresh,
                stringResource(R.string.ime_ai_chat_regenerate),
                Modifier.focusRing(focusedAction == 2, CircleShape),
            ) { onChat(AiChatAction.Regenerate) }
        }
        if (canEdit) {
            MessageAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit)) { onChat(AiChatAction.EditLast) }
        }
        // Out to another app as a Markdown file: a report keeps its sources.
        if (!fromUser && message.content.isNotBlank()) {
            MessageAction(Icons.Outlined.Share, stringResource(R.string.ime_ai_chat_share)) { onShare() }
        }
        // Play asks for a way to report generated content on every surface that
        // shows it; the other channels have no such rule, as on the chat screen.
        if (!fromUser && BuildConfig.ENABLE_PLAY_STORE && message.content.isNotBlank()) {
            MessageAction(Icons.Outlined.OutlinedFlag, stringResource(R.string.ime_ai_chat_report_action)) { onReport() }
        }
    }
}

/** One of [MessageActions]' icons: no fill, no border, the answer's own ink. */
@Composable
private fun MessageAction(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = answerInk(kb).copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** A code block's header action; the search panel's answer draws its code blocks the same way (#879). */
@Composable
internal fun CodeAction(label: String, colors: ChatMarkdownColors, onClick: () -> Unit) {
    Text(
        label,
        color = colors.text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun Caption(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
}

/** [ChatChain] in the answer's colours, its links opened and copied the keyboard's way. */
@Composable
private fun Chain(
    reasoning: String,
    live: Boolean,
    uses: List<AiToolActivity>,
    colors: ChatMarkdownColors,
    onChat: (AiChatAction) -> Unit,
    /** Undo for a memory save; only a stored answer has one to take back. */
    onUndoMemory: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    ChatChain(
        reasoning = reasoning,
        reasoningLive = live,
        uses = uses,
        colors = colors,
        errorColor = LocalKbTheme.current.accent,
        onOpen = { openToolLink(context, it) },
        onCopy = { onChat(AiChatAction.Copy(it)) },
        // Only a running call ever asks, so a stored answer's record never
        // shows the buttons.
        onApproval = { onChat(AiChatAction.ToolApproval(it)) },
        animate = LocalKbTheme.current.motion.any,
        onUndoMemory = onUndoMemory,
    )
}

/** The answer forming: its text as it arrives, or what the model is doing until there is some. */
@Composable
private fun StreamingBubble(run: AiChatController.ChatRun, onChat: (AiChatAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ink = answerInk(kb)
    val colors = ChatMarkdownColors(
        text = ink,
        dim = ink.copy(alpha = ink.alpha * 0.6f),
        codeBackground = ink.copy(alpha = 0.10f),
    )
    Row(Modifier.fillMaxWidth()) {
        // Drawn as the finished answer will be: full width, no bubble (#882).
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 2.dp, vertical = 3.dp),
        ) {
            // The reasoning so far and the calls so far, the running one
            // saying what it is doing, as the steps the stored answer keeps.
            val parts = remember(run.partial, run.implicitThink) { AiThinking.parts(run.partial, run.implicitThink) }
            val toolRunning = run.tools.any { it.running }
            Chain(parts.reasoning, live = parts.thinking, run.tools, colors, onChat)
            when {
                // The answer as markdown, so its tables and maths take shape
                // while it streams.
                AiFollowUps.visible(parts.output).isNotBlank() -> ChatMarkdown(AiFollowUps.visible(parts.output), colors)
                // The steps above already say what is happening: the model is
                // reasoning, or a tool is running.
                parts.thinking || toolRunning -> Unit
                else -> {
                    Text(
                        stringResource(run.phase.labelRes),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.size(5.dp))
                    LinearProgressIndicator(color = kb.accent, modifier = Modifier.width(96.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(state: KeyboardUiState, onChat: (AiChatAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = state.aiChat.composing,
                indication = null,
                interactionSource = null,
            ) { onChat(AiChatAction.BlurComposer) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Text handed over by another tool (#352) is what this chat is about.
        val fromTool = state.aiChat.attachment?.label?.isNotEmpty() == true
        Text(
            stringResource(if (fromTool) R.string.ime_ai_chat_ask_hint else R.string.ime_ai_chat_empty_hint),
            color = kb.secondaryText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (fromTool) {
            // Offered while typing too: the composer takes the keys the moment
            // the text arrives, and these are the questions most asked of it.
            val explain = stringResource(R.string.ime_ai_chat_ask_explain_prompt)
            val summary = stringResource(R.string.ime_ai_chat_ask_summary_prompt)
            val words = stringResource(R.string.ime_ai_chat_ask_words_prompt)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_explain)) {
                    onChat(AiChatAction.Starter(explain, attach = false))
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_summary)) {
                    onChat(AiChatAction.Starter(summary, attach = false))
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_ask_words)) {
                    onChat(AiChatAction.Starter(words, attach = false))
                }
            }
        } else if (!state.aiChat.composing) {
            // Openings, for the blank page. Not while typing: the user has begun.
            val reply = stringResource(R.string.ime_ai_chat_starter_reply_prompt)
            val explain = stringResource(R.string.ime_ai_chat_starter_explain_prompt)
            val write = stringResource(R.string.ime_ai_chat_starter_write_prompt)
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // The two about "this" need a this: text in the field to quote.
                if (state.aiHasText && !state.secureField) {
                    ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_reply)) {
                        onChat(AiChatAction.Starter(reply, attach = true))
                    }
                    ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_explain)) {
                        onChat(AiChatAction.Starter(explain, attach = true))
                    }
                }
                ToolPanelChip(stringResource(R.string.ime_ai_chat_starter_write)) {
                    onChat(AiChatAction.Starter(write, attach = false))
                }
            }
        }
    }
}

/**
 * Pictures and files can be added here (#698, #882): never in incognito,
 * whose chats are not kept, and never over a password field, the same rule
 * the field's own text keeps.
 */
private fun picturesOffered(state: KeyboardUiState): Boolean = !state.incognitoOn && !state.secureField

/** One button of the composer's "+" menu (#882), in the order the menu draws them. */
private enum class AttachItem { IMAGE, FILE, PHOTO, CLIPBOARD, SCREENSHOT, FIELD, PASTE }

/**
 * The "+" menu's buttons for this field. The picture and file sources are left
 * out where pictures are not offered; a Play build declares no photos
 * permission, so it has no screenshots to offer.
 */
private fun attachItems(state: KeyboardUiState): List<AttachItem> = buildList {
    if (picturesOffered(state)) {
        add(AttachItem.IMAGE)
        add(AttachItem.FILE)
        add(AttachItem.PHOTO)
        add(AttachItem.CLIPBOARD)
        if (ChannelFeatures.SCREENSHOT_CLIPS) add(AttachItem.SCREENSHOT)
    }
    add(AttachItem.FIELD)
    add(AttachItem.PASTE)
}

private fun attachAction(item: AttachItem): AiChatAction = when (item) {
    AttachItem.IMAGE -> AiChatAction.PickFromSystem(AiChatPickActivity.MODE_IMAGES)
    AttachItem.FILE -> AiChatAction.PickFromSystem(AiChatPickActivity.MODE_FILES)
    AttachItem.PHOTO -> AiChatAction.PickFromSystem(AiChatPickActivity.MODE_PHOTO)
    AttachItem.CLIPBOARD -> AiChatAction.AddPicture(fromClipboard = true)
    AttachItem.SCREENSHOT -> AiChatAction.AddPicture(fromClipboard = false)
    AttachItem.FIELD -> AiChatAction.ToggleAttachment
    AttachItem.PASTE -> AiChatAction.Paste
}

/**
 * The composer's ring slots, in the order [Composer] draws them: the field,
 * then its controls along the bottom (the Reasoning switch where [reasoning]
 * says the model has one, "+", Send), then the menu's buttons while it is up;
 * -1 is a slot not on screen.
 */
private class ComposerSlots(state: KeyboardUiState, reasoning: Boolean) {
    val field = 0
    val reasoning = if (reasoning) 1 else -1
    private val shift = if (reasoning) 1 else 0
    val options = 1 + shift
    val plus = 2 + shift
    val send = 3 + shift
    val menu: List<AttachItem> = if (state.aiChat.attachMenu) attachItems(state) else emptyList()
    fun of(item: AttachItem): Int = menu.indexOf(item).let { if (it < 0) -1 else 4 + shift + it }
}

/** What Enter does on each of the composer's ring slots. Mirrors [ComposerSlots]. */
private fun composerTargets(
    state: KeyboardUiState,
    generating: Boolean,
    reasoning: Boolean,
    onChat: (AiChatAction) -> Unit,
): List<() -> Unit> = buildList<() -> Unit> {
    val slots = ComposerSlots(state, reasoning)
    add { onChat(if (state.aiChat.composing) AiChatAction.BlurComposer else AiChatAction.FocusComposer) }
    if (slots.reasoning >= 0) add { onChat(AiChatAction.ToggleReasoning) }
    add { onChat(AiChatAction.ToggleOptions) }
    add { onChat(AiChatAction.ToggleAttachMenu) }
    add { onChat(if (generating) AiChatAction.Stop else AiChatAction.Send) }
    slots.menu.forEach { item -> add { onChat(attachAction(item)) } }
}

/**
 * Pictures in a row (#698): the ones waiting to go with the next message, each
 * with a cross to take it back off, or the ones a sent message carried. A
 * picture whose file has gone shows as an empty tile rather than not at all,
 * so the numbers a model uses still line up with what is on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatPictures(
    names: List<String>,
    size: Dp,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        names.forEach { name ->
            key(name) {
                val file = remember(name) { AiChatImages.file(context, name) }
                Box {
                    AsyncImage(
                        model = file,
                        contentDescription = stringResource(R.string.ime_ai_chat_picture_item_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(size)
                            .clip(RoundedCornerShape(8.dp))
                            .background(kb.chip),
                    )
                    onRemove?.let { remove ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(kb.chip)
                                .clickable { remove(name) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.ime_ai_chat_picture_remove_desc),
                                tint = kb.chipText,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The composer's "+" menu (#882), over it: a row of icons, each named by a
 * long press. Not a sheet: a popup over an input method's window is the
 * untrusted-touch trap, so it opens in place, the way the old picture row
 * did. Image, File and Photo go through the system's own pickers, which take
 * the field away for as long as they are up; the clipboard's picture and the
 * newest screenshot are the reply tool's sources, which do not. The line
 * under it says why the last one asked for did not arrive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachMenu(state: KeyboardUiState, slots: ComposerSlots, onChat: (AiChatAction) -> Unit) {
    val kb = LocalKbTheme.current
    val chat = state.aiChat
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            slots.menu.forEach { item ->
                val (icon, label) = when (item) {
                    AttachItem.IMAGE -> Icons.Outlined.PhotoLibrary to R.string.ime_ai_chat_attach_image
                    AttachItem.FILE -> Icons.Outlined.AttachFile to R.string.ime_ai_chat_attach_file
                    AttachItem.PHOTO -> Icons.Outlined.PhotoCamera to R.string.ime_ai_chat_attach_photo
                    AttachItem.CLIPBOARD -> Icons.Outlined.Image to R.string.ime_ai_chat_picture_clipboard
                    AttachItem.SCREENSHOT -> Icons.Outlined.Screenshot to R.string.ime_ai_chat_picture_screenshot
                    AttachItem.FIELD -> Icons.Outlined.FormatQuote to
                        if (chat.attachment != null) R.string.ime_ai_chat_attach_remove_desc else R.string.ime_ai_chat_attach_desc
                    AttachItem.PASTE -> Icons.Outlined.ContentPaste to CommonR.string.common_paste
                }
                val enabled = when (item) {
                    AttachItem.IMAGE, AttachItem.PHOTO, AttachItem.CLIPBOARD, AttachItem.SCREENSHOT ->
                        chat.images.size < AiChatStore.MAX_IMAGES
                    AttachItem.FILE -> chat.files.size < AiChatStore.MAX_FILES
                    // Nothing to quote from an empty field, and never from a password.
                    AttachItem.FIELD -> chat.attachment != null || (state.aiHasText && !state.secureField)
                    AttachItem.PASTE -> true
                }
                ChatIconButton(
                    icon,
                    stringResource(label),
                    active = item == AttachItem.FIELD && chat.attachment != null,
                    enabled = enabled,
                    size = 38.dp,
                    modifier = Modifier.focusRing(focused == slots.of(item), CircleShape),
                ) { onChat(attachAction(item)) }
            }
        }
        chat.pictureNotice?.let { notice ->
            Text(
                stringResource(notice),
                color = kb.secondaryText,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The files of a message (#882): one chip each, with its name, waiting over
 * the composer with a cross to take it back off, or in a sent bubble.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatFiles(
    files: List<AiChatFile>,
    ink: Color,
    modifier: Modifier = Modifier,
    onRemove: ((AiChatFile) -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        files.forEach { file ->
            val shape = RoundedCornerShape(8.dp)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(ink.copy(alpha = 0.10f))
                    .padding(start = 7.dp, end = if (onRemove != null) 2.dp else 8.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(fileIcon(file), contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    file.name,
                    color = ink,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                onRemove?.let { remove ->
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { remove(file) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ime_ai_chat_file_remove_desc),
                            tint = kb.chipText,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The mark a file's chip wears, by what it is. */
fun fileIcon(file: AiChatFile): ImageVector = when {
    file.mime.startsWith("audio/") -> Icons.Outlined.AudioFile
    file.mime.startsWith("video/") -> Icons.Outlined.VideoFile
    file.mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.Outlined.Description
}

@Composable
private fun Composer(
    state: KeyboardUiState,
    generating: Boolean,
    reasoning: Boolean,
    canSend: Boolean,
    onChat: (AiChatAction) -> Unit,
    /** Why the next message cannot be sent in this mode, as a string resource. */
    notice: Int? = null,
) {
    val kb = LocalKbTheme.current
    val chat = state.aiChat
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    val slots = ComposerSlots(state, reasoning)
    val shape = kb.cardShape()
    val mode = AiChatMode.of(chat.mode)
    val format = AiAnswerFormat.of(chat.format)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
    ) {
        if (chat.attachMenu) AttachMenu(state, slots, onChat)
        if (chat.optionsMenu) OptionsMenu(mode, format, onChat)
        notice?.let {
            Text(
                stringResource(it),
                color = kb.accent,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
            )
        }
        // Shown wherever they were picked: a picture already chosen is not taken
        // back because the field behind turned into a password.
        if (chat.images.isNotEmpty()) {
            ChatPictures(
                chat.images,
                DRAFT_PICTURE_SIZE,
                Modifier.padding(bottom = 5.dp),
                onRemove = { onChat(AiChatAction.RemovePicture(it)) },
            )
        }
        if (chat.files.isNotEmpty()) {
            ChatFiles(
                chat.files,
                kb.chipText,
                Modifier.padding(bottom = 5.dp),
                onRemove = { onChat(AiChatAction.RemoveFile(it)) },
            )
        }
        // One box (#882): what is being written, and under it the controls
        // that belong to it, the Reasoning switch at the start and "+" and
        // Send at the end. A tap on the box anywhere but a control is a tap
        // on the field.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .focusRing(focused == slots.field, shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .clickable(enabled = !chat.composing, indication = null, interactionSource = null) {
                    onChat(AiChatAction.FocusComposer)
                }
                .padding(start = 10.dp, end = 5.dp, top = 7.dp, bottom = 5.dp),
        ) {
            chat.attachment?.let { attachment ->
                Text(
                    when {
                        // A tool's text is named by the tool it came from (#352).
                        attachment.label.isNotEmpty() -> stringResource(
                            if (attachment.fromSelection) {
                                R.string.ime_ai_chat_attached_tool_selection
                            } else {
                                R.string.ime_ai_chat_attached_tool
                            },
                            attachment.label,
                        )
                        else -> pluralStringResource(
                            if (attachment.fromSelection) {
                                R.plurals.ime_ai_chat_attached_selection
                            } else {
                                R.plurals.ime_ai_chat_attached_field
                            },
                            attachment.text.length,
                            attachment.text.length,
                        )
                    },
                    color = kb.accent,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp, end = 5.dp),
                )
            }
            val hint = stringResource(mode.hintRes)
            if (chat.composing) {
                // The caret, and a tap that moves it, come from the capture
                // ladder's own editor; this field is one of its buffers.
                ClipEditText(
                    text = chat.draft,
                    placeholder = hint,
                    textColor = kb.chipText,
                    placeholderColor = kb.secondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 5.dp)
                        .heightIn(min = 20.dp, max = COMPOSER_MAX_HEIGHT),
                )
            } else {
                Text(
                    chat.draft.ifEmpty { hint },
                    color = if (chat.draft.isEmpty()) kb.secondaryText else kb.chipText,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 5.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reasoning) {
                    ReasoningSwitch(
                        on = state.settings.ai.chatReasoning,
                        modifier = Modifier.focusRing(focused == slots.reasoning, CircleShape),
                    ) { onChat(AiChatAction.ToggleReasoning) }
                }
                Spacer(Modifier.weight(1f))
                // The mode and format, named on the button while either is
                // not the plain chat's, so a research run is never a surprise.
                val custom = mode != AiChatMode.CHAT || format != AiAnswerFormat.AUTO
                if (custom) {
                    Text(
                        listOfNotNull(
                            stringResource(mode.labelRes).takeIf { mode != AiChatMode.CHAT },
                            stringResource(format.labelRes).takeIf { format != AiAnswerFormat.AUTO },
                        ).joinToString(" · "),
                        color = kb.accent,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                    )
                }
                ChatIconButton(
                    if (mode == AiChatMode.CHAT) Icons.Outlined.Tune else mode.icon,
                    stringResource(R.string.ime_ai_chat_mode_desc),
                    active = chat.optionsMenu || custom,
                    modifier = Modifier.focusRing(focused == slots.options, CircleShape),
                ) { onChat(AiChatAction.ToggleOptions) }
                Spacer(Modifier.width(6.dp))
                ChatIconButton(
                    Icons.Outlined.Add,
                    stringResource(R.string.ime_ai_chat_attach_menu_desc),
                    active = chat.attachMenu,
                    modifier = Modifier.focusRing(focused == slots.plus, CircleShape),
                ) { onChat(AiChatAction.ToggleAttachMenu) }
                Spacer(Modifier.width(6.dp))
                ChatIconButton(
                    if (generating) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                    stringResource(if (generating) R.string.ime_ai_chat_stop else R.string.ime_ai_chat_send),
                    active = true,
                    enabled = generating || canSend,
                    modifier = Modifier.focusRing(focused == slots.send, CircleShape),
                ) { onChat(if (generating) AiChatAction.Stop else AiChatAction.Send) }
            }
        }
    }
}

/**
 * The row of modes and answer formats over the composer, in place of a
 * popup for the reason [AttachMenu] gives: chips that scroll sideways, the
 * mode's first, the one in force lit.
 */
@Composable
private fun OptionsMenu(mode: AiChatMode, format: AiAnswerFormat, onChat: (AiChatAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(AiChatMode.entries) { option ->
                ToolPanelChip(stringResource(option.labelRes), selected = option == mode) {
                    onChat(AiChatAction.SetChatMode(option.id))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(AiAnswerFormat.entries) { option ->
                ToolPanelChip(stringResource(option.labelRes), selected = option == format) {
                    onChat(AiChatAction.SetAnswerFormat(option.id))
                }
            }
        }
    }
}

/**
 * The Reasoning switch at the start of the composer's controls (#882): a
 * small pill, lit while the model is asked to reason. It is the chat's own
 * setting, the same one the app's chat shows, and only here for a model it
 * does something for.
 */
@Composable
private fun ReasoningSwitch(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(50)
    val ink = if (on) kb.chipActiveText else kb.chipText.copy(alpha = kb.chipText.alpha * 0.75f)
    val label = stringResource(R.string.ime_ai_chat_reasoning)
    val state = stringResource(if (on) R.string.ime_ai_chat_reasoning_on_desc else R.string.ime_ai_chat_reasoning_off_desc)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (on) kb.chipActive else Color.Transparent)
            .border(1.dp, ink.copy(alpha = if (on) 0f else 0.35f), shape)
            .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = { onClick() })
            .semantics { stateDescription = state }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = ink, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * The conversation list, in place of the transcript. A long press on a chat
 * starts picking (#882): taps then tick chats on and off, and the row over
 * the list has Select all and Delete. The pencil on a chat types a new name
 * for it in place, with the keys under the panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sessions(
    state: KeyboardUiState,
    conversations: List<AiChatConversation>,
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val chat = state.aiChat
    val picked = chat.picked.filterTo(HashSet()) { id -> conversations.any { it.id == id } }
    val picking = picked.isNotEmpty()
    // Delete is two taps on the same chip, not a dialog: a dialog over an IME
    // window is a popup, and the list has nowhere to put one. [ALL_PICKED]
    // stands for the selection's Delete.
    var doomed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(doomed) {
        if (doomed != null) {
            delay(DELETE_CONFIRM_MS)
            doomed = null
        }
    }
    val top: List<() -> Unit> = if (picking) {
        listOf(
            { onChat(AiChatAction.ClearPicks) },
            { onChat(AiChatAction.PickAll) },
            {
                if (doomed == ALL_PICKED) {
                    doomed = null
                    onChat(AiChatAction.DeletePicked)
                } else {
                    doomed = ALL_PICKED
                }
            },
        )
    } else {
        listOf({ onChat(AiChatAction.NewChat) }, { onChat(AiChatAction.OpenInApp(list = true)) })
    }
    ChatFocusTargets(
        chips = emptyList(),
        results = top + conversations.map { conversation ->
            {
                if (picking) onChat(AiChatAction.TogglePick(conversation.id)) else onChat(AiChatAction.Open(conversation.id))
            }
        },
    )
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        item(key = "top") {
            if (picking) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatIconButton(
                        Icons.Outlined.Close,
                        stringResource(CommonR.string.common_cancel),
                        modifier = Modifier.focusRing(focused == 0, CircleShape),
                    ) { onChat(AiChatAction.ClearPicks) }
                    Text(
                        pluralStringResource(R.plurals.ime_ai_chat_selected_count, picked.size, picked.size),
                        color = kb.chipText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    ChatIconButton(
                        Icons.Outlined.SelectAll,
                        stringResource(CommonR.string.common_select_all),
                        enabled = picked.size < conversations.size,
                        modifier = Modifier.focusRing(focused == 1, CircleShape),
                    ) { onChat(AiChatAction.PickAll) }
                    val confirming = doomed == ALL_PICKED
                    ToolPanelChip(
                        stringResource(if (confirming) R.string.ime_ai_chat_delete_confirm else CommonR.string.common_delete),
                        selected = confirming,
                        modifier = Modifier.focusRing(focused == 2),
                    ) { top[2]() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ToolPanelChip(
                        stringResource(R.string.ime_ai_chat_new),
                        selected = true,
                        modifier = Modifier.focusRing(focused == 0),
                    ) { onChat(AiChatAction.NewChat) }
                    ToolPanelChip(
                        stringResource(R.string.ime_ai_chat_open_in_app),
                        modifier = Modifier.focusRing(focused == 1),
                    ) { onChat(AiChatAction.OpenInApp(list = true)) }
                }
            }
        }
        if (conversations.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.ime_ai_chat_list_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
        itemsIndexed(conversations, key = { _, conversation -> conversation.id }) { index, conversation ->
            val current = conversation.id == chat.conversationId
            val checked = conversation.id in picked
            val shape = kb.cardShape()
            if (chat.renaming == conversation.id) {
                RenameRow(chat.renameDraft, onChat, Modifier.animateItem())
                return@itemsIndexed
            }
            // The swipe deletes at once: reaching the red button is already the
            // second deliberate step the chip's two taps stand in for. Not
            // while picking, where a finger meant for the next tick could set
            // it off.
            KbSwipeActionsRow(
                actions = listOf(
                    kbDeleteSwipeAction(stringResource(CommonR.string.common_delete)) {
                        doomed = null
                        onChat(AiChatAction.Delete(conversation.id))
                    },
                ),
                reduceMotion = state.settings.reduceMotion,
                modifier = Modifier.animateItem(),
                enabled = !picking,
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .focusRing(focused == index + top.size, shape)
                    .frostGlass(kb, shape, FrostSurface.CARDS)
                    .background(if (checked) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .combinedClickable(
                        onClick = {
                            if (picking) {
                                onChat(AiChatAction.TogglePick(conversation.id))
                            } else {
                                onChat(AiChatAction.Open(conversation.id))
                            }
                        },
                        onLongClick = { onChat(AiChatAction.TogglePick(conversation.id)) },
                    )
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (checked) kb.chipActiveText else kb.chipText
                if (picking) {
                    Icon(
                        if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        conversation.title.ifBlank { stringResource(R.string.ime_ai_chat_untitled) },
                        color = if (current && !checked) kb.accent else ink,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
                        color = if (checked) ink.copy(alpha = ink.alpha * 0.7f) else kb.secondaryText,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                    )
                }
                if (!picking) {
                    val title = conversation.title.ifBlank { stringResource(R.string.ime_ai_chat_untitled) }
                    MessageAction(
                        Icons.Outlined.DriveFileRenameOutline,
                        stringResource(R.string.ime_ai_chat_rename_desc, title),
                    ) { onChat(AiChatAction.StartRename(conversation.id)) }
                    Spacer(Modifier.width(4.dp))
                    val confirming = doomed == conversation.id
                    ToolPanelChip(
                        stringResource(
                            if (confirming) R.string.ime_ai_chat_delete_confirm else CommonR.string.common_delete,
                        ),
                        selected = confirming,
                    ) {
                        if (confirming) {
                            doomed = null
                            onChat(AiChatAction.Delete(conversation.id))
                        } else {
                            doomed = conversation.id
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * A chat's name being typed (#882), in its row's place: the keyboard's own
 * field, with the keys under the panel, and a tick to keep it and a cross to
 * drop it. Enter keeps it too, and Back drops it.
 */
@Composable
private fun RenameRow(draft: String, onChat: (AiChatAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip)
            .border(1.5.dp, kb.accent, shape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipEditText(
            text = draft,
            placeholder = stringResource(R.string.ime_ai_chat_rename_hint),
            textColor = kb.chipText,
            placeholderColor = kb.secondaryText,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 20.dp, max = 40.dp),
        )
        ChatIconButton(Icons.Outlined.Close, stringResource(CommonR.string.common_cancel)) {
            onChat(AiChatAction.CancelRename)
        }
        Spacer(Modifier.width(4.dp))
        ChatIconButton(
            Icons.Outlined.Check,
            stringResource(R.string.ime_ai_chat_rename),
            active = true,
            enabled = draft.isNotBlank(),
        ) { onChat(AiChatAction.CommitRename) }
    }
}

/** Neither a downloaded model nor a provider: the way to the settings that fix it. */
@Composable
private fun NoBackend(state: KeyboardUiState, onOpenSettings: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.ime_ai_chat_no_backend_body),
            color = kb.secondaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.size(8.dp))
        ToolPanelChip(
            stringResource(R.string.ime_ai_open_settings_action),
            modifier = Modifier.focusRing(state.focusedIndex(FocusRegion.RESULTS) == 0),
        ) { onOpenSettings() }
    }
}

/** A round icon button in the chip colours: the composer's and the header's. */
@Composable
private fun ChatIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // Scaled from the theme's own alpha, as ToolPanelChip dims: some themes
    // draw chips translucent to begin with.
    fun Color.dim() = if (enabled) this else copy(alpha = alpha * 0.4f)
    IconHint(description, modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .frostGlass(kb, CircleShape, FrostSurface.CHIPS)
                .background((if (active) kb.chipActive else kb.chip).dim())
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = (if (active) kb.chipActiveText else kb.chipText).dim(),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * An answer's ink. It stands on the panel itself rather than on a chip, so it
 * takes the colour the panel's own headlines are drawn in.
 */
private fun answerInk(kb: KbTheme): Color = kb.modifierKeyText

private fun bubbleShape(fromUser: Boolean) = RoundedCornerShape(
    topStart = 14.dp,
    topEnd = 14.dp,
    bottomStart = if (fromUser) 14.dp else 4.dp,
    bottomEnd = if (fromUser) 4.dp else 14.dp,
)

/** Kept clear left of the user's bubble, so whose turn it is reads from the margin. */
private val BUBBLE_INSET = 36.dp
private val COMPOSER_MAX_HEIGHT = 78.dp
private val DRAFT_PICTURE_SIZE = 48.dp
private val MESSAGE_PICTURE_SIZE = 56.dp
private const val COPIED_FLASH_MS = 1200L
private const val DELETE_CONFIRM_MS = 3000L

/** What the two-tap Delete is waiting on when it is the selection's, not one chat's. */
private const val ALL_PICKED = Long.MIN_VALUE
