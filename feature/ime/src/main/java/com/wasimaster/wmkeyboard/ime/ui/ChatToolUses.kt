package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.tools.AiMemoryNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolApproval
import com.wasimaster.wmkeyboard.core.tools.AiToolSource
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.core.tools.AiResearch
import com.wasimaster.wmkeyboard.core.tools.AiResearchText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.ime.R

/**
 * The tools a model called for one answer, drawn over that answer: what it
 * searched for and the results it was handed, the pages it read, and the calls
 * that failed and why (#470).
 *
 * Folded to one line by default — "Searched the web for “…”", or "3 tool
 * calls" — because the answer is what the user came for. A tap unfolds it.
 * While a call is running that one line says what is happening right now,
 * with a spinner, which is the whole of what "Using a tool" used to say.
 *
 * Shared by the keyboard's AI panel (chat and actions) and the chat screen in
 * the settings app, like [ChatMarkdown], so colours come in rather than off a
 * theme. Links come out through [onOpen] and [onCopy] for the same reason: the
 * keyboard has no selection handles to offer and opens a browser itself, the
 * app has its own clipboard and URI handler. [onOpen] is only ever handed an
 * http or https address; anything else a model wrote can be copied, never
 * opened.
 *
 * A call to an MCP server's tool that has to ask first (#573) shows a card
 * under the line, open whether or not the record is unfolded: what the tool
 * is, where it runs, the values the model wrote for it, and Deny / Allow once
 * / Always allow, which go out through [onApproval]. A surface that passes no
 * [onApproval] shows the question without the buttons.
 *
 * A call that saved to the AI memory adds a line under the record, open or
 * folded: "Saved to memory", the fact, and Undo, which goes out through
 * [onUndoMemory] with the call's index in [uses]. A surface that passes none
 * shows the line without Undo.
 *
 * With [animate] the record moves: a live one slides in, the spinner shrinks
 * into the mark of what the call did, the header's words cross-fade, the
 * detail unrolls and rolls back up, and a call added while it is open arrives
 * on its own. The keyboard passes its `motion.any`; the app, whose screens do
 * not carry the keyboard's theme, leaves it on. Off, every state is drawn
 * straight, with no animation wrapped round it at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatToolUses(
    uses: List<AiToolActivity>,
    colors: ChatMarkdownColors,
    errorColor: Color,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    onApproval: ((AiToolApproval) -> Unit)? = null,
    animate: Boolean = true,
    onUndoMemory: ((Int) -> Unit)? = null,
) {
    if (uses.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val running = uses.lastOrNull { it.running }
    val asking = running?.takeIf { it.awaitingApproval }
    val failed = uses.count { it.failed }
    val lineHeight = fontSize * LINE_HEIGHT
    val header = when {
        running != null -> toolLine(running)
        uses.size == 1 -> toolLine(uses.single())
        failed > 0 -> stringResource(
            R.string.ime_ai_chat_tool_summary_with_failed,
            pluralStringResource(R.plurals.ime_ai_chat_tool_calls, uses.size, uses.size),
            pluralStringResource(R.plurals.ime_ai_chat_tool_failed_count, failed, failed),
        )
        else -> pluralStringResource(R.plurals.ime_ai_chat_tool_calls, uses.size, uses.size)
    }
    val allFailed = running == null && failed == uses.size
    val headerInk by animateColorAsState(
        targetValue = if (allFailed) errorColor else colors.dim,
        animationSpec = if (animate) tween(MORPH_MS) else snap(),
        label = "toolHeaderInk",
    )
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (animate) tween(EXPAND_MS, easing = FastOutSlowInEasing) else snap(),
        label = "toolChevron",
    )
    // Null while a call runs: the spinner stands where the mark will be.
    val mark = when {
        running != null -> null
        allFailed -> Icons.Outlined.ErrorOutline
        uses.all { it.tool == AiTools.WEB_FETCH && !it.isMcp } -> Icons.Outlined.Language
        uses.all { !it.isMcp && it.tool in WEB_TOOLS } -> Icons.Outlined.Search
        else -> Icons.Outlined.Handyman
    }
    // Only a record that turns up with a call already running is the live
    // one; a stored answer scrolled back into view is just there.
    val arrival = remember { Animatable(if (animate && running != null) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (arrival.value < 1f) arrival.animateTo(1f, tween(ARRIVE_MS, easing = FastOutSlowInEasing))
    }
    // The calls already there when the detail opened unroll with it; any
    // after that arrive one by one.
    val openedWith = remember(expanded) { uses.size }
    Column(modifier = if (animate) modifier.arrival(arrival) else modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = stringResource(
                        if (expanded) R.string.ime_ai_chat_tool_hide_desc else R.string.ime_ai_chat_tool_show_desc,
                    ),
                ) { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderMark(mark, tint = headerInk, spinner = colors.dim, animate = animate)
            Spacer(Modifier.width(5.dp))
            MotionCrossfade(
                target = header,
                animate = animate,
                modifier = Modifier.weight(1f, fill = false),
                contentAlignment = Alignment.CenterStart,
                label = "toolHeader",
            ) { line ->
                Text(
                    line,
                    color = headerInk,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // One chevron turning over rather than two swapped.
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = headerInk,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }
        if (!animate) {
            if (asking != null) ApprovalCard(asking, colors, fontSize, onApproval)
        } else {
            AnimatedContent(
                targetState = asking,
                transitionSpec = {
                    fadeIn(tween(EXPAND_MS, delayMillis = FADE_IN_DELAY_MS)) togetherWith
                        fadeOut(tween(COLLAPSE_MS)) using
                        SizeTransform(clip = true) { _, _ -> tween(EXPAND_MS, easing = FastOutSlowInEasing) }
                },
                contentKey = { it?.let { ask -> "${ask.server}\u0000${ask.tool}\u0000${ask.details}" } },
                label = "toolApproval",
            ) { ask ->
                // Answered, the card is on its way out: a second tap on Allow
                // would answer a question nobody is asking any more.
                if (ask != null) {
                    ApprovalCard(ask, colors, fontSize, onApproval, Modifier.blockTouches(ask != asking))
                }
            }
        }
        if (!animate) {
            if (expanded) {
                ToolUseDetails(uses, openedWith, colors, errorColor, fontSize, onOpen, onCopy, animate = false)
            }
        } else {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(EXPAND_MS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                    fadeIn(tween(EXPAND_MS)),
                exit = shrinkVertically(tween(COLLAPSE_MS), shrinkTowards = Alignment.Top) +
                    fadeOut(tween(COLLAPSE_MS)),
                label = "toolDetail",
            ) {
                ToolUseDetails(
                    uses,
                    openedWith,
                    colors,
                    errorColor,
                    fontSize,
                    onOpen,
                    onCopy,
                    animate = true,
                    // Folding away, its links no longer open anything.
                    modifier = Modifier.blockTouches(!expanded),
                )
            }
        }
        for ((index, use) in uses.withIndex()) {
            val note = use.memory ?: continue
            if (use.running || use.failed) continue
            key("memory", index) {
                MemorySaveLine(note, colors, fontSize, onUndoMemory?.let { undo -> { undo(index) } })
            }
        }
    }
}

/**
 * One save to the AI memory: what was kept, and Undo while it can still be
 * taken back. Drawn whether or not the record is unfolded, since the point
 * of it is that the user sees what the model decided to remember.
 */
@Composable
internal fun MemorySaveLine(note: AiMemoryNote, colors: ChatMarkdownColors, fontSize: TextUnit, onUndo: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.dim.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(start = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = colors.dim, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        val label = when {
            note.undone -> stringResource(R.string.ime_ai_memory_undone)
            note.added -> stringResource(R.string.ime_ai_memory_saved)
            else -> stringResource(R.string.ime_ai_memory_updated)
        }
        Text(
            "$label · ${note.text}",
            color = colors.dim,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 4.dp),
        )
        if (onUndo != null && !note.undone) {
            Text(
                stringResource(R.string.ime_ai_memory_undo),
                color = colors.text,
                fontSize = fontSize,
                lineHeight = fontSize * LINE_HEIGHT,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onUndo)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            Spacer(Modifier.width(6.dp))
        }
    }
}

/**
 * The header's leading mark: a spinner while a call runs, then the kind of
 * tool it was, or the failure. The spinner shrinks away as the mark pops in
 * where it stood, so a call finishing reads as the spinner settling into its
 * answer rather than one picture swapped for another.
 */
@Composable
private fun HeaderMark(mark: ImageVector?, tint: Color, spinner: Color, animate: Boolean) {
    if (!animate) {
        HeaderMarkFace(mark, tint, spinner)
        return
    }
    AnimatedContent(
        targetState = mark,
        transitionSpec = {
            val popIn = fadeIn(tween(MARK_IN_MS)) +
                scaleIn(spring(dampingRatio = MARK_POP_DAMPING, stiffness = Spring.StiffnessMedium), MARK_FROM)
            val shrinkOut = fadeOut(tween(MARK_OUT_MS)) + scaleOut(tween(MARK_OUT_MS), MARK_FROM)
            popIn togetherWith shrinkOut using null
        },
        contentAlignment = Alignment.Center,
        label = "toolMark",
    ) { face -> HeaderMarkFace(face, tint, spinner) }
}

@Composable
private fun HeaderMarkFace(mark: ImageVector?, tint: Color, spinner: Color) {
    if (mark == null) {
        CircularProgressIndicator(
            color = spinner,
            strokeWidth = 1.5.dp,
            modifier = Modifier.size(11.dp),
        )
    } else {
        Icon(mark, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
    }
}

/**
 * The unfolded record, one block per call. A call from [openedWith] on was
 * added while the record was open, and slides in on its own; each block
 * eases to its new height when its results land.
 */
@Composable
private fun ToolUseDetails(
    uses: List<AiToolActivity>,
    openedWith: Int,
    colors: ChatMarkdownColors,
    errorColor: Color,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((index, use) in uses.withIndex()) {
            key(index) {
                val arrival = remember { Animatable(if (animate && index >= openedWith) 0f else 1f) }
                LaunchedEffect(Unit) {
                    if (arrival.value < 1f) arrival.animateTo(1f, tween(ARRIVE_MS, easing = FastOutSlowInEasing))
                }
                ToolUseDetail(
                    use = use,
                    // A single call's line is the header already.
                    showLine = uses.size > 1,
                    colors = colors,
                    errorColor = errorColor,
                    fontSize = fontSize,
                    onOpen = onOpen,
                    onCopy = onCopy,
                    modifier = if (animate) {
                        Modifier.arrival(arrival).animateContentSize(tween(MORPH_MS))
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolUseDetail(
    use: AiToolActivity,
    showLine: Boolean,
    colors: ChatMarkdownColors,
    errorColor: Color,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ToolUseLines(use, showLine, colors, errorColor, fontSize, onOpen, onCopy)
    }
}

/** What [ToolUseDetail] holds: the call's line when wanted, then its results or its error. */
@Composable
private fun ToolUseLines(
    use: AiToolActivity,
    showLine: Boolean,
    colors: ChatMarkdownColors,
    errorColor: Color,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val small = fontSize * SMALL
    if (showLine) {
        Text(
            toolLine(use),
            color = if (use.failed) errorColor else colors.text,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    when {
        use.failed -> Text(
            use.error,
            color = errorColor.copy(alpha = errorColor.alpha * 0.85f),
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
        )
        use.running -> Unit
        use.isMcp -> if (use.target.isNotEmpty()) {
            Text(
                use.target,
                color = colors.dim,
                fontSize = small,
                lineHeight = small * LINE_HEIGHT,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        (use.tool == AiTools.WEB_SEARCH || use.tool == AiTools.IMAGE_SEARCH) && use.sources.isEmpty() -> Text(
            stringResource(R.string.ime_ai_chat_tool_no_results),
            color = colors.dim,
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
        )
        else -> for (source in use.sources) {
            // A page read is shown by its whole address: which page, exactly,
            // is the thing to check. A search result only needs its site.
            SourceLine(
                source = source,
                fullAddress = use.tool == AiTools.WEB_FETCH,
                colors = colors,
                fontSize = fontSize,
                onOpen = onOpen,
                onCopy = onCopy,
            )
        }
    }
}

/** One page: its title, and where it is. Tap opens it, a long press copies the address. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SourceLine(
    source: AiToolSource,
    fullAddress: Boolean,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val openable = AiToolActivity.openableUrl(source.url)
    val small = fontSize * SMALL
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClickLabel = stringResource(
                    if (openable != null) R.string.ime_ai_chat_tool_open_desc else R.string.ime_ai_chat_tool_copy_desc,
                ),
                onClick = { if (openable != null) onOpen(openable) else onCopy(source.url) },
                onLongClick = { onCopy(source.url) },
            )
            .padding(vertical = 2.dp, horizontal = 2.dp),
    ) {
        Text(
            source.title.ifBlank { source.domain },
            color = colors.text,
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (fullAddress) source.url else source.domain,
            color = colors.dim,
            fontSize = small * 0.92f,
            lineHeight = small * LINE_HEIGHT,
            maxLines = if (fullAddress) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One call in a line: what is happening while it runs, what happened once it
 * has, and what did not happen when it failed.
 */
@Composable
internal fun toolLine(use: AiToolActivity): String {
    if (use.isMcp) {
        return when {
            use.awaitingApproval -> stringResource(R.string.ime_ai_chat_tool_mcp_waiting, use.tool, use.server)
            use.running -> stringResource(R.string.ime_ai_chat_tool_mcp_running, use.tool, use.server)
            use.failed -> stringResource(R.string.ime_ai_chat_tool_mcp_failed, use.tool, use.server)
            else -> stringResource(R.string.ime_ai_chat_tool_mcp_done, use.tool, use.server)
        }
    }
    builtInLabels[use.tool]?.let { return builtInLine(use, it) }
    val target = use.target
    val page = when {
        use.tool != AiTools.WEB_FETCH -> ""
        use.sources.isNotEmpty() -> use.sources.first().domain
        target.isNotEmpty() -> AiToolActivity.domainOf(target)
        else -> ""
    }
    return when (use.tool) {
        AiTools.WEB_SEARCH -> when {
            target.isEmpty() && use.running -> stringResource(AiPhase.USING_TOOL.labelRes)
            target.isEmpty() -> stringResource(
                R.string.ime_ai_chat_tool_use_failed,
                stringResource(R.string.ime_ai_chat_tool_name_search),
            )
            use.running -> stringResource(R.string.ime_ai_chat_tool_searching, target)
            use.failed -> stringResource(R.string.ime_ai_chat_tool_search_failed, target)
            else -> stringResource(R.string.ime_ai_chat_tool_searched, target)
        }
        AiTools.WEB_FETCH -> when {
            page.isEmpty() && use.running -> stringResource(AiPhase.USING_TOOL.labelRes)
            page.isEmpty() -> stringResource(
                R.string.ime_ai_chat_tool_use_failed,
                stringResource(R.string.ime_ai_chat_tool_name_fetch),
            )
            use.running -> stringResource(R.string.ime_ai_chat_tool_reading, page)
            use.failed -> stringResource(R.string.ime_ai_chat_tool_read_failed, page)
            else -> stringResource(R.string.ime_ai_chat_tool_read, page)
        }
        // A tool the model made up: there is nothing to say but its name.
        else -> if (use.running) {
            stringResource(AiPhase.USING_TOOL.labelRes)
        } else {
            stringResource(R.string.ime_ai_chat_tool_use_failed, use.tool)
        }
    }
}

/** What the chat calls one of the keyboard's own tools (#698), and says while and after it runs. */
private class ToolLabels(
    @StringRes val name: Int,
    /** While it runs; %1$s is [AiToolActivity.target]. */
    @StringRes val running: Int,
    /** Once it has run; %1$s is [AiToolActivity.target]. */
    @StringRes val done: Int,
)

private val builtInLabels: Map<String, ToolLabels> = mapOf(
    AiTools.IMAGE_SEARCH to ToolLabels(
        R.string.ime_ai_tool_name_image_search,
        R.string.ime_ai_tool_image_search_running,
        R.string.ime_ai_tool_image_search_done,
    ),
    AiTools.WEATHER to ToolLabels(
        R.string.ime_ai_tool_name_weather,
        R.string.ime_ai_tool_weather_running,
        R.string.ime_ai_tool_weather_done,
    ),
    AiTools.WM_DOCS to ToolLabels(
        R.string.ime_ai_tool_name_wm_docs,
        R.string.ime_ai_tool_wm_docs_running,
        R.string.ime_ai_tool_wm_docs_done,
    ),
    AiTools.WIKIPEDIA to ToolLabels(
        R.string.ime_ai_tool_name_wikipedia,
        R.string.ime_ai_tool_wikipedia_running,
        R.string.ime_ai_tool_wikipedia_done,
    ),
    AiTools.DEFINE_WORD to ToolLabels(
        R.string.ime_ai_tool_name_define_word,
        R.string.ime_ai_tool_define_word_running,
        R.string.ime_ai_tool_define_word_done,
    ),
    AiTools.TRANSLATE to ToolLabels(
        R.string.ime_ai_tool_name_translate,
        R.string.ime_ai_tool_translate_running,
        R.string.ime_ai_tool_translate_done,
    ),
    AiTools.CONVERT_CURRENCY to ToolLabels(
        R.string.ime_ai_tool_name_convert_currency,
        R.string.ime_ai_tool_convert_running,
        R.string.ime_ai_tool_convert_done,
    ),
    AiTools.CONVERT_UNITS to ToolLabels(
        R.string.ime_ai_tool_name_convert_units,
        R.string.ime_ai_tool_convert_running,
        R.string.ime_ai_tool_convert_done,
    ),
    AiTools.CALCULATE to ToolLabels(
        R.string.ime_ai_tool_name_calculate,
        R.string.ime_ai_tool_calculate_running,
        R.string.ime_ai_tool_calculate_done,
    ),
    AiTools.CALENDAR_EVENTS to ToolLabels(
        R.string.ime_ai_tool_name_calendar_events,
        R.string.ime_ai_tool_calendar_events_running,
        R.string.ime_ai_tool_calendar_events_done,
    ),
    AiTools.SEARCH_NOTES to ToolLabels(
        R.string.ime_ai_tool_name_search_notes,
        R.string.ime_ai_tool_search_notes_running,
        R.string.ime_ai_tool_search_notes_done,
    ),
    AiTools.SEARCH_SNIPPETS to ToolLabels(
        R.string.ime_ai_tool_name_search_snippets,
        R.string.ime_ai_tool_search_snippets_running,
        R.string.ime_ai_tool_search_snippets_done,
    ),
    AiTools.SEARCH_CLIPBOARD to ToolLabels(
        R.string.ime_ai_tool_name_search_clipboard,
        R.string.ime_ai_tool_search_clipboard_running,
        R.string.ime_ai_tool_search_clipboard_done,
    ),
    AiTools.CURRENT_LOCATION to ToolLabels(
        R.string.ime_ai_tool_name_current_location,
        R.string.ime_ai_chat_tool_builtin_running,
        R.string.ime_ai_chat_tool_builtin_done,
    ),
    AiTools.HOME_ASSISTANT to ToolLabels(
        R.string.ime_ai_tool_name_home_assistant,
        R.string.ime_ai_tool_home_assistant_running,
        R.string.ime_ai_tool_home_assistant_done,
    ),
    AiTools.HOME_ASSISTANT_STATES to ToolLabels(
        R.string.ime_ai_tool_name_home_assistant_states,
        R.string.ime_ai_tool_home_assistant_states_running,
        R.string.ime_ai_tool_home_assistant_states_done,
    ),
    AiTools.ADD_CALENDAR_EVENT to ToolLabels(
        R.string.ime_ai_tool_name_add_calendar_event,
        R.string.ime_ai_tool_add_calendar_event_running,
        R.string.ime_ai_tool_add_calendar_event_done,
    ),
    AiTools.REMEMBER to ToolLabels(
        R.string.ime_ai_tool_name_remember,
        R.string.ime_ai_tool_remember_running,
        R.string.ime_ai_tool_remember_done,
    ),
    AiTools.FORGET to ToolLabels(
        R.string.ime_ai_tool_name_forget,
        R.string.ime_ai_tool_forget_running,
        R.string.ime_ai_tool_forget_done,
    ),
    AiTools.ADD_NOTE to ToolLabels(
        R.string.ime_ai_tool_name_add_note,
        R.string.ime_ai_tool_add_note_running,
        R.string.ime_ai_tool_add_note_done,
    ),
    AiTools.SET_ALARM to ToolLabels(
        R.string.ime_ai_tool_name_set_alarm,
        R.string.ime_ai_tool_set_alarm_running,
        R.string.ime_ai_tool_set_alarm_done,
    ),
    AiTools.START_TIMER to ToolLabels(
        R.string.ime_ai_tool_name_start_timer,
        R.string.ime_ai_tool_start_timer_running,
        R.string.ime_ai_tool_start_timer_done,
    ),
    AiTools.OPEN_APP to ToolLabels(
        R.string.ime_ai_tool_name_open_app,
        R.string.ime_ai_tool_open_app_running,
        R.string.ime_ai_tool_open_app_done,
    ),
    AiTools.MEDIA_CONTROL to ToolLabels(
        R.string.ime_ai_tool_name_media_control,
        R.string.ime_ai_chat_tool_builtin_running,
        R.string.ime_ai_chat_tool_builtin_done,
    ),
    AiTools.SEND_TO_PC to ToolLabels(
        R.string.ime_ai_tool_name_send_to_pc,
        R.string.ime_ai_tool_send_to_pc_running,
        R.string.ime_ai_tool_send_to_pc_done,
    ),
    AiTools.READ_IMAGE_TEXT to ToolLabels(
        R.string.ime_ai_tool_name_read_image_text,
        R.string.ime_ai_tool_read_image_text_running,
        R.string.ime_ai_tool_read_image_text_done,
    ),
    AiTools.DESCRIBE_IMAGE to ToolLabels(
        R.string.ime_ai_tool_name_describe_image,
        R.string.ime_ai_tool_describe_image_running,
        R.string.ime_ai_tool_describe_image_done,
    ),
    AiTools.REVERSE_IMAGE_SEARCH to ToolLabels(
        R.string.ime_ai_tool_name_reverse_image_search,
        R.string.ime_ai_tool_reverse_image_search_running,
        R.string.ime_ai_tool_reverse_image_search_done,
    ),
    AiTools.RENAME_CHAT to ToolLabels(
        R.string.ime_ai_tool_name_rename_chat,
        R.string.ime_ai_tool_rename_chat_running,
        R.string.ime_ai_tool_rename_chat_done,
    ),
    // Deep research's own steps, drawn among its searches and pages.
    AiTools.RESEARCH_PLAN to ToolLabels(
        R.string.ime_ai_tool_name_research_plan,
        R.string.ime_ai_tool_research_plan_running,
        R.string.ime_ai_tool_research_plan_done,
    ),
    AiTools.RESEARCH_REVIEW to ToolLabels(
        R.string.ime_ai_tool_name_research_review,
        R.string.ime_ai_tool_research_review_running,
        R.string.ime_ai_tool_research_review_done,
    ),
)

/** The search tools, whose record wears the search mark. */
private val WEB_TOOLS = setOf(AiTools.WEB_SEARCH, AiTools.WEB_FETCH, AiTools.IMAGE_SEARCH)

/**
 * One of the keyboard's own tools as people call it: "Weather", "Calculator".
 * The name the model used, for anything this keyboard did not hand out.
 */
@Composable
fun aiToolName(tool: String): String = builtInLabels[tool]?.let { stringResource(it.name) } ?: tool

/** [toolLine] for one of the keyboard's own tools. */
@Composable
private fun builtInLine(use: AiToolActivity, labels: ToolLabels): String {
    val name = stringResource(labels.name)
    // A model writes the language as a code; the line reads it as a name.
    val target = if (use.tool == AiTools.TRANSLATE && use.target.isNotEmpty()) {
        TranslateClient.languageName(use.target)
    } else {
        use.target
    }
    // Labels that are the generic pair take the tool's name, not a target.
    val generic = labels.running == R.string.ime_ai_chat_tool_builtin_running
    return when {
        use.awaitingApproval -> stringResource(R.string.ime_ai_chat_tool_builtin_waiting, name)
        use.tool == AiTools.RESEARCH_PLAN && use.error == AiResearch.DECLINED -> stringResource(R.string.ime_ai_research_plan_declined)
        use.failed -> stringResource(R.string.ime_ai_chat_tool_use_failed, name)
        (target.isEmpty() || generic) && use.running -> stringResource(R.string.ime_ai_chat_tool_builtin_running, name)
        target.isEmpty() || generic -> stringResource(R.string.ime_ai_chat_tool_builtin_done, name)
        use.running -> stringResource(labels.running, target)
        else -> stringResource(labels.done, target)
    }
}

/**
 * The question an MCP call asks before it runs (#573): which tool, on which
 * server, with which values, and the three answers. The values are shown in
 * full, as the model wrote them, because they are what the user is agreeing
 * to send.
 */
@Composable
internal fun ApprovalCard(
    use: AiToolActivity,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    onApproval: ((AiToolApproval) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val small = fontSize * SMALL
    // A chat's new name (#882) is a yes or a no about one name, so the card
    // asks just that, with no "always".
    val rename = use.tool == AiTools.RENAME_CHAT && !use.isMcp
    // A research plan (the Review-the-plan setting) is a yes or a no about
    // the whole plan, which is shown as its checklist.
    val plan = use.tool == AiTools.RESEARCH_PLAN && !use.isMcp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (rename) {
            Text(
                stringResource(R.string.ime_ai_chat_rename_ask, use.details),
                color = colors.text,
                fontSize = fontSize,
                lineHeight = fontSize * LINE_HEIGHT,
                fontWeight = FontWeight.Medium,
            )
            if (onApproval != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApprovalButton(stringResource(R.string.ime_ai_chat_rename_decline), colors, small, strong = false) {
                        onApproval(AiToolApproval.DENY)
                    }
                    ApprovalButton(stringResource(R.string.ime_ai_chat_rename_accept), colors, small, strong = true) {
                        onApproval(AiToolApproval.ONCE)
                    }
                }
            }
            return@Column
        }
        if (plan) {
            Text(
                stringResource(R.string.ime_ai_research_plan_ask),
                color = colors.text,
                fontSize = fontSize,
                lineHeight = fontSize * LINE_HEIGHT,
                fontWeight = FontWeight.Medium,
            )
            ResearchChecklist(use.details, colors, small)
            if (onApproval != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApprovalButton(stringResource(R.string.ime_ai_research_plan_cancel), colors, small, strong = false) {
                        onApproval(AiToolApproval.DENY)
                    }
                    ApprovalButton(stringResource(R.string.ime_ai_research_plan_start), colors, small, strong = true) {
                        onApproval(AiToolApproval.ONCE)
                    }
                }
            }
            return@Column
        }
        Text(
            if (use.isMcp) {
                stringResource(R.string.ime_ai_chat_tool_mcp_ask, use.tool, use.server)
            } else {
                stringResource(R.string.ime_ai_chat_tool_builtin_ask, aiToolName(use.tool))
            },
            color = colors.text,
            fontSize = fontSize,
            lineHeight = fontSize * LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
        )
        Text(
            use.details.ifEmpty { stringResource(R.string.ime_ai_chat_tool_mcp_ask_no_values) },
            color = colors.dim,
            fontSize = small,
            lineHeight = small * LINE_HEIGHT,
            fontFamily = if (use.details.isEmpty()) null else FontFamily.Monospace,
            maxLines = APPROVAL_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        if (onApproval != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ApprovalButton(stringResource(R.string.ime_ai_chat_tool_mcp_deny), colors, small, strong = false) {
                    onApproval(AiToolApproval.DENY)
                }
                ApprovalButton(stringResource(R.string.ime_ai_chat_tool_mcp_allow_once), colors, small, strong = true) {
                    onApproval(AiToolApproval.ONCE)
                }
                ApprovalButton(stringResource(R.string.ime_ai_chat_tool_mcp_allow_always), colors, small, strong = false) {
                    onApproval(AiToolApproval.ALWAYS)
                }
            }
        }
    }
}

/**
 * A research plan's sub-questions, one a line, each ticked once it has been
 * searched and read for: the run's to-do list, the way a coding agent shows
 * its own. [checklist] is [AiResearchText.checklist]'s text.
 */
@Composable
internal fun ResearchChecklist(checklist: String, colors: ChatMarkdownColors, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val items = remember(checklist) { AiResearchText.checklistItems(checklist) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (question, done) ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (done) colors.text else colors.dim,
                    modifier = Modifier.padding(top = 1.dp).size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    question,
                    color = if (done) colors.dim else colors.text,
                    fontSize = fontSize,
                    lineHeight = fontSize * LINE_HEIGHT,
                )
            }
        }
    }
}

/**
 * One answer on [ApprovalCard]. Drawn here rather than as a Material button:
 * the card is shared by the keyboard, whose colours come from its theme, and
 * the app, whose colours come from Material.
 */
@Composable
private fun ApprovalButton(
    label: String,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    strong: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = colors.text,
        fontSize = fontSize,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, colors.text.copy(alpha = if (strong) 0.7f else 0.3f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Opens one page from the keyboard, as the Wikipedia panel opens an article:
 * a new task, since an input method has none of its own. Only ever handed an
 * address [AiToolActivity.openableUrl] passed.
 */
internal fun openToolLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * A record, or a call inside one, sliding up into place as it fades in.
 * ModulateAlpha so the fade never cuts off a line drawn past the block's edge.
 */
internal fun Modifier.arrival(progress: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    val p = progress.value
    if (p >= 1f) return@graphicsLayer
    alpha = p
    translationY = (1f - p) * ARRIVE_RISE.toPx()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

private const val LINE_HEIGHT = 1.35f

/** A live record or a new call sliding in, and how far it rises. */
private const val ARRIVE_MS = 180
private val ARRIVE_RISE = 6.dp

/** The detail unrolling and rolling back up; the approval card comes and goes with it. */
private const val EXPAND_MS = 200
private const val COLLAPSE_MS = 150
private const val FADE_IN_DELAY_MS = 40

/** The header's ink turning to the error colour, and a call's block easing to its new height. */
private const val MORPH_MS = 180

/** The spinner shrinking out, and the mark popping in from [MARK_FROM] with a touch of overshoot. */
private const val MARK_IN_MS = 140
private const val MARK_OUT_MS = 100
private const val MARK_FROM = 0.4f
private const val MARK_POP_DAMPING = 0.6f

/** Lines of arguments the approval card shows before it cuts them. */
private const val APPROVAL_LINES = 8
private const val SMALL = 0.92f
