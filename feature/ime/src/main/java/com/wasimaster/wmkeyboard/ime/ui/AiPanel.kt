package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import com.wasimaster.wmkeyboard.core.tools.AiClient
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.os.SystemClock
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextDecoration
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiMarkdown
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolApproval
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.TextDiff
import com.wasimaster.wmkeyboard.core.tools.visibleAiActions
import com.wasimaster.wmkeyboard.ime.AiChatAction
import com.wasimaster.wmkeyboard.ime.AiUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.delay
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * AI writing tool: one-tap actions (rewrite, summarize, translate …) that
 * run on the focused field's text through the provider configured in the
 * tool's settings (Claude, OpenAI, Gemini, Ollama, LM Studio — BYOK — or a
 * downloaded model running on-device). The result replaces the field or
 * inserts at the cursor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiPanel(
    state: KeyboardUiState,
    onAction: (AiActionSpec) -> Unit,
    onRetry: () -> Unit,
    onRunCustom: () -> Unit,
    onPickModel: (AiProvider, String?) -> Unit,
    onToggleStripMarkdown: () -> Unit,
    onSetShowDiff: (Boolean) -> Unit,
    onReport: () -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
    /** The chat's callback, which also carries the answer to a tool that asks first (#573). */
    onChat: (AiChatAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val ai0 = state.ai
    val onToolApproval = remember(onChat) { { choice: AiToolApproval -> onChat(AiChatAction.ToolApproval(choice)) } }
    val settings = state.settings
    val actions = remember(
        settings.ai.customActions,
        settings.ai.actionOrder,
        settings.ai.hiddenActions,
    ) {
        visibleAiActions(
            settings.ai.customActions,
            settings.ai.actionOrder,
            settings.ai.hiddenActions,
        )
    }

    val modelPicks = if (
        settings.ai.panelModelPicker &&
        (ai0 is AiUi.Idle || ai0 is AiUi.NeedSetup || ai0 is AiUi.NeedModel)
    ) {
        rememberModelPicks(state, onPickModel)
    } else {
        emptyList()
    }
    AiFocusTargets(
        state = state,
        actions = actions,
        modelPicks = modelPicks,
        onAction = onAction,
        onRetry = onRetry,
        onRunCustom = onRunCustom,
        onToggleStripMarkdown = onToggleStripMarkdown,
        onSetShowDiff = onSetShowDiff,
        onReport = onReport,
        onOpenToolSettings = onOpenToolSettings,
    )
    val focusedResult = state.focusedIndex(FocusRegion.RESULTS)

    // Height comes from the FullBleedTool wrapper — the panel replaces the
    // toolbar too, so it gets the key rows plus every hidden bar's height.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (modelPicks.isNotEmpty()) {
            ModelPickerRow(modelPicks, state.focusedIndex(FocusRegion.CHIPS))
        }
        if (settings.ai.panelModelPicker && settings.ai.provider != AiProvider.ON_DEVICE && ai0 is AiUi.Idle) {
            ModelNameRow(state)
        }
        when (val ai = state.ai) {
            AiUi.NeedSetup -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val setupText = if (BuildConfig.ENABLE_LOCAL_LLM) {
                    stringResource(R.string.ime_ai_setup_local_body)
                } else {
                    stringResource(R.string.ime_ai_setup_body)
                }
                Text(
                    setupText,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                ToolPanelChip(
                    stringResource(R.string.ime_ai_open_settings_action),
                    modifier = Modifier.focusRing(focusedResult == 0),
                ) {
                    onOpenToolSettings(ToolbarTool.AI)
                }
            }
            AiUi.NeedModel -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val download = rememberModelDownload()
                if (download != null) {
                    // The download is almost always the reason there is no model
                    // yet, so showing it is more use than telling the user to go
                    // and start the download they already started.
                    ModelDownloadProgress(download, wide = true)
                } else {
                    Text(
                        stringResource(R.string.ime_ai_need_model_body),
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    ToolPanelChip(
                        stringResource(R.string.ime_ai_download_model_action),
                        modifier = Modifier.focusRing(focusedResult == 0),
                    ) {
                        onOpenToolSettings(ToolbarTool.AI)
                    }
                }
            }
            AiUi.Idle -> Column(Modifier.fillMaxSize()) {
                // A download started in the settings app keeps running while the
                // user types, and until now the keyboard gave no sign of it.
                rememberModelDownload()?.let { ModelDownloadProgress(it, wide = false) }
                ActionChips(actions, onAction, enabled = state.aiHasText, focused = focusedResult)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val provider = stringResource(state.settings.ai.provider.labelRes)
                    // An empty field has nothing to act on, so the chips are
                    // greyed out. Say why rather than leaving the user pressing
                    // a dead row, and name an action that still works because
                    // it writes from nothing. The user owns this list, so there
                    // may not be one to name.
                    val writer = actions.firstOrNull { it.worksWithoutText }
                    Text(
                        when {
                            state.aiHasText -> stringResource(R.string.ime_ai_idle_body, provider)
                            writer != null -> stringResource(
                                R.string.ime_ai_idle_empty_body,
                                aiActionLabel(writer),
                            )
                            else -> stringResource(R.string.ime_ai_idle_empty_no_writer_body)
                        },
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                }
            }
            // The Custom action's own prompt: typed on the key rows below (the
            // panel is compact here), run on the field text via Run or Enter.
            // No chip grid — like the dictionary/translate search modes, the
            // header chevron is the way back out.
            is AiUi.CustomInput -> Column(Modifier.fillMaxSize()) {
                val blank = ai.instruction.isEmpty()
                val inputShape = kb.cardShape()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(inputShape)
                        .frostGlass(kb, inputShape, FrostSurface.CHIPS)
                        .background(kb.chip)
                        .chipBorder(kb, inputShape)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        when {
                            !blank -> ai.instruction
                            // The example has to match what the instruction
                            // will actually do: with an empty field there is
                            // no "this" to make a haiku out of.
                            state.aiHasText -> stringResource(R.string.ime_ai_custom_hint_text)
                            else -> stringResource(R.string.ime_ai_custom_hint_empty)
                        },
                        color = if (blank) kb.secondaryText else kb.chipText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Only the instruction is required: with an empty field
                    // Custom writes from scratch rather than transforming.
                    ToolPanelChip(
                        stringResource(R.string.ime_ai_run_action),
                        selected = !blank,
                        enabled = !blank,
                        modifier = Modifier.focusRing(focusedResult == 0),
                    ) { onRunCustom() }
                    Spacer(Modifier.width(8.dp))
                    // The focus switch (#665): the key rows below type into
                    // the app's field instead while it is on.
                    FieldFocusSwitch(LocalCaptureCaret.current, Modifier.padding(end = 8.dp))
                    val provider = stringResource(state.settings.ai.provider.labelRes)
                    Text(
                        if (state.aiHasText) {
                            stringResource(R.string.ime_ai_custom_body, provider)
                        } else {
                            stringResource(R.string.ime_ai_custom_empty_body, provider)
                        },
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
            is AiUi.Loading -> Column(Modifier.fillMaxSize()) {
                ActionChips(actions, onAction, running = ai.action)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AiProgress(ai, state.settings, onToolApproval)
                }
            }
            is AiUi.Error -> Column(Modifier.fillMaxSize()) {
                ActionChips(actions, onAction, enabled = state.aiHasText, focused = focusedResult)
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        ai.message,
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    // The ring's last RESULTS slot, after the action chips.
                    ToolPanelChip(
                        stringResource(CommonR.string.common_retry),
                        modifier = Modifier.focusRing(focusedResult == actions.size),
                    ) { onRetry() }
                }
            }
            // Replace/Insert/retry live in the full-bleed header row (the
            // space reclaimed from the toolbar) — the panel body is all
            // result.
            is AiUi.Ready -> Column(Modifier.fillMaxSize()) {
                val resultScroll = rememberScrollState()
                // What the panel shows is what Replace/Insert will commit —
                // so the checkbox reformats the preview, not only the output.
                val hasMarkdown = remember(ai.result) { AiMarkdown.hasMarkdown(ai.result) }
                val shown = remember(ai.result, ai.stripMarkdown, hasMarkdown) {
                    if (hasMarkdown && ai.stripMarkdown) AiMarkdown.strip(ai.result) else ai.result
                }
                LaunchedEffect(ai.result, ai.generating) {
                    // Follow the streaming text like a terminal tail.
                    if (ai.generating) resultScroll.scrollTo(resultScroll.maxValue)
                }
                // The two views are different lengths, so keeping the offset
                // across a switch lands the reader in the middle of nowhere.
                LaunchedEffect(ai.showDiff) {
                    if (!ai.generating) resultScroll.scrollTo(0)
                }
                val showDiff = ai.showDiff && ai.diffable && !ai.generating
                // Never on the frame: the keyboard draws under a finger, and a
                // comparison of two long texts is long enough to be a visible
                // stall. The plain result shows until this arrives.
                val diff by produceState<TextDiff.Result?>(null, ai.sourceText, shown, showDiff) {
                    value = if (!showDiff) {
                        null
                    } else {
                        // Compare what the panel actually shows. With reasoning
                        // on screen the think block is not part of the answer,
                        // and the markdown checkbox changes the text as well,
                        // which is why `shown` is a key rather than ai.result.
                        val answer = AiThinking.stripped(shown).ifBlank { shown }
                        withContext(Dispatchers.Default) {
                            TextDiff.diff(ai.sourceText, answer)
                        }
                    }
                }
                val diffColors = remember(kb) { kb.diffColors() }
                val resultShape = kb.cardShape()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(resultShape)
                        .frostGlass(kb, resultShape, FrostSurface.CHIPS)
                        .background(kb.chip)
                        .chipBorder(kb, resultShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .verticalScroll(resultScroll),
                ) {
                    // What the model looked up for this answer (#470). Not
                    // part of what Replace or Insert commits.
                    PanelToolUses(ai.tools, Modifier.padding(bottom = 4.dp), onApproval = onToolApproval)
                    val comparison = diff
                    Text(
                        if (showDiff && comparison != null && !comparison.tooLong) {
                            diffAnnotated(comparison, diffColors)
                        } else {
                            // Dim via alpha, not the theme's secondary color —
                            // some themes draw secondary text in the same white.
                            grayThinking(shown, kb.chipText.copy(alpha = 0.45f))
                        },
                        color = kb.chipText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                if (showDiff) {
                    DiffSummary(diff)
                }
                // A cut-off answer looks exactly like a finished one, so the
                // only way the user learns is if the panel says so. Hidden while
                // streaming: a stream in flight is not yet cut off.
                if (ai.truncated && !ai.generating) {
                    Text(
                        stringResource(R.string.ime_ai_truncated_notice),
                        color = kb.accent,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Indices mirror [AiFocusTargets]' CHIPS list: the diff
                    // pair, then the checkbox, then Report.
                    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
                    val diffChips = state.settings.ai.diffView && ai.diffable && !ai.generating
                    val markdownChipIndex = if (diffChips) 2 else 0
                    val reportChipIndex = markdownChipIndex + (if (hasMarkdown) 1 else 0)
                    // A mode, not an attribute of the output, so a pair of
                    // chips rather than a checkbox. Hidden while streaming,
                    // where there is nothing finished to compare.
                    if (diffChips) {
                        ToolPanelChip(
                            stringResource(R.string.ime_ai_view_result),
                            selected = !ai.showDiff,
                            modifier = Modifier.focusRing(focusedChip == 0),
                        ) { onSetShowDiff(false) }
                        Spacer(Modifier.width(4.dp))
                        ToolPanelChip(
                            stringResource(R.string.ime_ai_view_changes),
                            selected = ai.showDiff,
                            modifier = Modifier.focusRing(focusedChip == 1),
                        ) { onSetShowDiff(true) }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (hasMarkdown) {
                        PanelCheckbox(
                            label = stringResource(R.string.ime_ai_strip_markdown_label),
                            checked = ai.stripMarkdown,
                            onToggle = onToggleStripMarkdown,
                            focused = focusedChip == markdownChipIndex,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Anything a model writes needs a way to be reported —
                    // Play asks for one, and a keyboard that can produce
                    // offensive text at the tap of a chip should own that. It
                    // opens a mail draft the user reads before sending; there
                    // is no silent upload. Hidden while still streaming: half
                    // a result is not the thing being complained about.
                    if (!ai.generating) {
                        ToolPanelChip(
                            stringResource(R.string.ime_ai_report_action),
                            modifier = Modifier.focusRing(focusedChip == reportChipIndex),
                        ) { onReport() }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for ((index, action) in actions.withIndex()) {
                        ToolPanelChip(
                            aiActionLabel(action),
                            // By id, never by equality: the running spec is a
                            // snapshot from when the request started, and an
                            // edit made since would un-light the chip.
                            selected = action.id == ai.action.id,
                            // These re-run on the field, so they need field
                            // text — unlike Replace/Insert, which only need
                            // the result already on screen. An action that
                            // writes from nothing is the exception.
                            enabled = state.aiHasText || action.worksWithoutText,
                            modifier = Modifier.focusRing(focusedResult == index),
                        ) { onAction(action) }
                    }
                }
            }
        }
    }
}

/**
 * Single publisher for the ring's CHIPS and RESULTS regions — one owner per
 * region whatever state the panel is in, so a state change can never leave a
 * stale grid behind (the last SideEffect always describes what is on screen).
 * The indices must mirror the drawing order in [AiPanel].
 */
@Composable
private fun AiFocusTargets(
    state: KeyboardUiState,
    actions: List<AiActionSpec>,
    modelPicks: List<ModelPick>,
    onAction: (AiActionSpec) -> Unit,
    onRetry: () -> Unit,
    onRunCustom: () -> Unit,
    onToggleStripMarkdown: () -> Unit,
    onSetShowDiff: (Boolean) -> Unit,
    onReport: () -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
) {
    val ai = state.ai
    val chips: List<() -> Unit> = if (ai is AiUi.Ready) {
        val hasMarkdown = remember(ai.result) { AiMarkdown.hasMarkdown(ai.result) }
        buildList {
            if (state.settings.ai.diffView && ai.diffable && !ai.generating) {
                add { onSetShowDiff(false) }
                add { onSetShowDiff(true) }
            }
            if (hasMarkdown) add(onToggleStripMarkdown)
            if (!ai.generating) add(onReport)
        }
    } else {
        modelPicks.map { it.onClick }
    }
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.CHIPS,
        count = chips.size,
        columns = chips.size.coerceAtLeast(1),
    ) { index -> chips.getOrNull(index)?.invoke() }

    // Mirrors the chips' own enabled logic, so Enter is never stronger than a tap.
    val reRun: (AiActionSpec) -> Unit = { action ->
        if (state.aiHasText || action.worksWithoutText) onAction(action)
    }
    val results: List<() -> Unit> = when (ai) {
        AiUi.NeedSetup -> listOf { onOpenToolSettings(ToolbarTool.AI) }
        // While a download runs the panel shows progress, not a chip.
        AiUi.NeedModel -> if (rememberModelDownload() == null) {
            listOf { onOpenToolSettings(ToolbarTool.AI) }
        } else {
            emptyList()
        }
        AiUi.Idle -> actions.map { { reRun(it) } }
        is AiUi.CustomInput -> listOf { if (ai.instruction.isNotEmpty()) onRunCustom() }
        // The chips on screen are the label of what is in flight, not an offer.
        is AiUi.Loading -> emptyList()
        is AiUi.Error -> actions.map { { reRun(it) } } + listOf(onRetry)
        is AiUi.Ready -> actions.map { { reRun(it) } }
    }
    PanelFocusTarget(
        panel = PanelMode.AI,
        region = FocusRegion.RESULTS,
        count = results.size,
        columns = results.size.coerceAtLeast(1),
    ) { index -> results.getOrNull(index)?.invoke() }
}

/** A model download in flight: its display name and how far it has got. */
private class ModelDownload(val name: String, val bytes: Long, val total: Long)

/**
 * The model download running right now, or null when none is.
 *
 * [LocalLlmDownloadManager] is a process singleton and the settings app shares
 * this process, so the keyboard sees a download the user started over there. It
 * dies with the process, which is the behaviour it always had.
 */
@Composable
private fun rememberModelDownload(): ModelDownload? {
    if (!BuildConfig.ENABLE_LOCAL_LLM) return null
    val states by LocalLlmDownloadManager.states.collectAsState()
    val active = LocalLlmDownloadManager.activeDownload(states) ?: return null
    val (id, status) = active
    val downloading = status as? LocalLlmDownloadManager.DownloadStatus.Downloading ?: return null
    val name = LocalLlmCatalog.byId(id)?.displayName
        ?: id.removePrefix(LocalLlmStore.CUSTOM_PREFIX)
    return ModelDownload(name, downloading.bytes, downloading.total)
}

/**
 * The download readout on the panel. [wide] is the empty-state version, which
 * owns the whole panel; the narrow one is a single line above the action chips,
 * where every dp of height belongs to the chips.
 */
@Composable
private fun ModelDownloadProgress(download: ModelDownload, wide: Boolean) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (wide) 24.dp else 0.dp, vertical = 4.dp),
        horizontalAlignment = if (wide) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            stringResource(R.string.ime_ai_downloading_model, download.name),
            color = kb.secondaryText,
            fontSize = if (wide) 13.sp else 11.sp,
            textAlign = if (wide) TextAlign.Center else TextAlign.Start,
        )
        Spacer(Modifier.height(6.dp))
        if (download.total > 0) {
            LinearProgressIndicator(
                progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) },
                color = kb.accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.ime_ai_download_bytes,
                    LocalLlmDownloadManager.formatBytes(download.bytes),
                    LocalLlmDownloadManager.formatBytes(download.total),
                ),
                color = kb.secondaryText,
                fontSize = 11.sp,
            )
        } else {
            // No Content-Length yet, so there is no fraction to draw.
            LinearProgressIndicator(color = kb.accent, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                LocalLlmDownloadManager.formatBytes(download.bytes),
                color = kb.secondaryText,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Small themed checkbox for a panel — Material3's would ignore the keyboard
 * theme and eat far more vertical room than this row has to give.
 */
@Composable
private fun PanelCheckbox(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    focused: Boolean = false,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(focused)
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) kb.chipActive else kb.chip),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = kb.chipActiveText,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Text(
            label,
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * With verbose thinking on, a reasoning model's stream includes its think
 * block — tint everything from `<think>` through `</think>` (tags included)
 * gray so the reasoning reads as an aside and the answer stands out. Handles
 * the streaming cases: an unclosed block grays to the end, and a close tag
 * with the opener still in the prompt (Qwen 3 style) grays from the start.
 */
internal fun grayThinking(text: String, gray: Color): AnnotatedString {
    val open = text.indexOf("<think>")
    // Search for the closer only after the opener so grayEnd can never precede
    // grayStart (which would crash substring). Still handles the opener-in-
    // prompt case (open == -1) by searching from 0.
    val close = text.indexOf("</think>", startIndex = if (open != -1) open else 0)
    val grayStart = if (open != -1) open else 0
    val grayEnd = when {
        close != -1 -> close + "</think>".length
        open != -1 -> text.length
        else -> -1
    }
    return buildAnnotatedString {
        if (grayEnd == -1) {
            append(text)
        } else {
            append(text.substring(0, grayStart))
            withStyle(SpanStyle(color = gray)) { append(text.substring(grayStart, grayEnd)) }
            append(text.substring(grayEnd))
        }
    }
}

/**
 * Renders a comparison as one styled string.
 *
 * Deliberately not a composable, so it can be tested on the JVM.
 *
 * Every changed run carries a decoration as well as a colour — a strikethrough
 * for what went, an underline and heavier weight for what arrived. Colour alone
 * would be unreadable on a photo background, in a high-contrast theme, and for
 * a colour-blind reader. Whitespace-only runs get a visible stand-in for the
 * same reason: a deleted paragraph break is a real change and an invisible span
 * says nothing.
 *
 * Each changed run is wrapped in the Unicode isolate characters, so a deleted
 * Arabic word cannot reorder the untouched text around it. Those go only into
 * what is drawn, never into [TextDiff.Span.text], which is what Replace
 * commits.
 */
internal fun diffAnnotated(
    result: TextDiff.Result,
    colors: DiffColors,
): AnnotatedString = buildAnnotatedString {
    for (span in result.spans) {
        when (span.op) {
            TextDiff.Op.KEEP -> append(span.text)
            TextDiff.Op.DELETE -> withStyle(
                SpanStyle(
                    color = colors.deleted,
                    textDecoration = TextDecoration.LineThrough,
                ),
            ) {
                append(FIRST_STRONG_ISOLATE)
                append(visibleWhitespace(span.text))
                append(POP_DIRECTIONAL_ISOLATE)
            }
            TextDiff.Op.ADD -> withStyle(
                SpanStyle(
                    color = colors.added,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                append(FIRST_STRONG_ISOLATE)
                append(visibleWhitespace(span.text))
                append(POP_DIRECTIONAL_ISOLATE)
            }
        }
    }
}

/*
 * Escapes rather than the characters themselves: both are invisible, so written
 * literally they are a pair of empty-looking string constants that no reader —
 * and no reviewer — can tell apart or verify. Lint says the same thing under
 * BidiSpoofing.
 */

/** U+2068: the run inside decides its own direction. */
private const val FIRST_STRONG_ISOLATE = "\u2068"

/** U+2069: back to the direction outside. */
private const val POP_DIRECTIONAL_ISOLATE = "\u2069"

/**
 * Draws a run that is nothing but whitespace with stand-in marks, so a changed
 * space or paragraph break is visible. A run with any other character in it is
 * left exactly as it is: the marks are for the case that would otherwise show
 * nothing at all.
 */
private fun visibleWhitespace(text: String): String {
    if (text.isEmpty() || !text.all(Char::isWhitespace)) return text
    return buildString {
        for (ch in text) append(if (ch == '\n') '¶' else '·')
    }
}

/** One entry in the panel's model picker. */
internal class ModelPick(
    // Stable identity across reorders so the LazyRow can animate the selected
    // chip sliding to the front instead of snapping.
    val key: String,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * One-tap switcher across everything usable right now: each configured
 * cloud/server provider, plus every downloaded on-device model. Hidden via
 * the "model picker on the panel" setting, or when there's nothing to
 * switch between.
 *
 * The row scrolls horizontally, so the selected entry is pulled to the front
 * — otherwise the one thing the user needs to see is the one thing off the
 * right edge.
 */
/**
 * The picker's entries, selected first, or an empty list when there is only
 * one model to choose from (a picker with one choice is noise). Hoisted out
 * of [ModelPickerRow] so [AiPanel] can publish the list as the focus ring's
 * CHIPS region with the exact indices the row draws.
 */
@Composable
internal fun rememberModelPicks(
    state: KeyboardUiState,
    onPickModel: (AiProvider, String?) -> Unit,
): List<ModelPick> {
    val filesDir = LocalContext.current.filesDir
    val settings = state.settings
    // Cheap file stats; keyed on ai state so re-opening after a download or
    // delete re-scans.
    val localIds = remember(state.ai, settings.ai.localModelId) {
        LocalLlmCatalog.models
            .filter { LocalLlmStore.isDownloaded(filesDir, it) }
            .map { it.id to it.displayName } +
            LocalLlmStore.customModels(filesDir).map {
                (LocalLlmStore.CUSTOM_PREFIX + it.name) to it.name.substringBeforeLast('.')
            }
    }
    // The AI list's rows that can answer, in the list's order. The on-device
    // row stands for every model downloaded, so it gives one chip per model.
    val rows = remember(settings) { settings.aiRows() }
    val remote = rows.filter { AiProviders.of(it.typeId).let { p -> p != null && p != AiProvider.ON_DEVICE } }
    val local = if (rows.any { it.typeId == ProviderTypes.ON_DEVICE_AI.id }) localIds else emptyList()
    if (remote.size + local.size < 2) return emptyList()

    val selectedLocalId = settings.ai.localModelId
        .takeIf { id -> local.any { it.first == id } }
        ?: local.singleOrNull()?.first

    val picks = local.map { (id, name) ->
        ModelPick(
            key = "local:$id",
            label = name,
            selected = settings.ai.provider == AiProvider.ON_DEVICE && id == selectedLocalId,
            onClick = { onPickModel(AiProvider.ON_DEVICE, id) },
        )
    } + remote.map { row ->
        val provider = AiProviders.of(row.typeId) ?: AiProvider.OPENAI_COMPATIBLE
        ModelPick(
            key = "remote:${row.key}",
            label = row.customName.ifEmpty { row.type?.let { stringResource(it.labelRes) } ?: row.typeId },
            selected = settings.ai.providerKey == row.key,
            // A remote pick hands over the row's key where an on-device one hands over its model.
            onClick = { onPickModel(provider, row.key) },
        )
    }
    // Stable sort: the selected entry moves to the front, everything else
    // keeps catalog order.
    return picks.sortedByDescending { it.selected }
}

/**
 * The name of the model in use, and, once pressed, the ones the provider lists
 * (#1038). A row of its own because the picker above is about providers.
 */
@Composable
private fun ModelNameRow(state: KeyboardUiState) {
    val actions = LocalAiSuggest.current
    val current = remember(state.settings.ai) { AiClient.config(state.settings.ai).model }
    var open by remember { mutableStateOf(false) }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "current") {
            ToolPanelChip(current, selected = open) {
                open = !open
                if (open) actions.onLoadModels()
            }
        }
        if (open) {
            items(state.aiModelList.filter { it != current }, key = { it }) { model ->
                ToolPanelChip(model, selected = false) {
                    open = false
                    actions.onPickModelName(model)
                }
            }
        }
    }
}

@Composable
internal fun ModelPickerRow(picks: List<ModelPick>, focused: Int? = null) {
    val kb = LocalKbTheme.current
    // A LazyRow keyed by model identity so the just-picked chip animates to the
    // front instead of jumping there.
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
                modifier = Modifier.animateItem(),
            )
        }
        itemsIndexed(picks, key = { _, pick -> pick.key }) { index, pick ->
            ToolPanelChip(
                pick.label,
                selected = pick.selected,
                modifier = Modifier
                    .animateItem()
                    .focusRing(index == focused),
                onClick = pick.onClick,
            )
        }
    }
}

/**
 * The one-line count under a comparison. Says "parts", not "words": the pieces
 * are words for prose and single characters for a script written without
 * spaces, and calling either one a word would be wrong half the time.
 */
@Composable
private fun DiffSummary(diff: TextDiff.Result?) {
    val kb = LocalKbTheme.current
    val text = when {
        diff == null -> return
        diff.tooLong -> stringResource(R.string.ime_ai_diff_too_long)
        diff.identical -> stringResource(R.string.ime_ai_diff_identical)
        // Each count inflects on its own, so each is its own plural and the
        // summary resource only joins the two halves.
        else -> stringResource(
            R.string.ime_ai_diff_summary,
            pluralStringResource(R.plurals.ime_ai_diff_added, diff.added, diff.added),
            pluralStringResource(R.plurals.ime_ai_diff_deleted, diff.deleted, diff.deleted),
        )
    }
    Text(
        text,
        color = kb.secondaryText,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A shipped action's translated name, or the name the user gave it. */
@Composable
internal fun aiActionLabel(spec: AiActionSpec): String =
    BuiltInAiActions.labelRes(spec)?.let { stringResource(it) } ?: spec.name

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionChips(
    actions: List<AiActionSpec>,
    onAction: (AiActionSpec) -> Unit,
    running: AiActionSpec? = null,
    enabled: Boolean = true,
    focused: Int? = null,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Only the empty field has a reason worth a hold: a chip greyed out
        // behind a running one is waiting its turn, which the lit chip says.
        val noText = DisabledReason(stringResource(R.string.ime_ai_idle_empty_no_writer_body))
        for ((index, action) in actions.withIndex()) {
            val isRunning = running != null && action.id == running.id
            ToolPanelChip(
                aiActionLabel(action),
                selected = isRunning,
                // The running chip stays lit rather than dimming — it is the
                // label of what is in flight, not an offer to press.
                enabled = (enabled || action.worksWithoutText) && (running == null || isRunning),
                modifier = Modifier.focusRing(index == focused),
                disabledReason = noText.takeIf { running == null },
            ) {
                if (running == null) onAction(action)
            }
        }
    }
}

/**
 * What a request is actually doing, in place of the old anonymous spinner.
 * Three things a bare indeterminate bar cannot say: which step it is on, how
 * long it has been there, and — for a reasoning model that emits nothing
 * visible for entire seconds — that anything is arriving at all.
 */
@Composable
private fun AiProgress(
    ai: AiUi.Loading,
    settings: KeyboardSettings,
    onToolApproval: (AiToolApproval) -> Unit,
) {
    val kb = LocalKbTheme.current
    val onDevice = settings.ai.provider == AiProvider.ON_DEVICE
    val steps = buildList {
        add(AiPhase.PREPARING)
        // On-device has no connection to make, so those steps would sit
        // unreachable for the whole run.
        if (!onDevice) {
            add(AiPhase.CONNECTING)
            add(AiPhase.WAITING)
        }
        // Only promise a reasoning step for a model that reasons — otherwise
        // every plain request ends on a step it never reaches. The id sniff can
        // be wrong, so a run that *is* reasoning shows it regardless.
        if (AiClient.expectsReasoning(settings.ai) || ai.phase == AiPhase.THINKING) {
            add(AiPhase.THINKING)
        }
    }

    // Ticks once a second purely to redraw the elapsed readout. Keyed on the
    // run's start so a retry restarts the clock instead of continuing it.
    var elapsedSeconds by remember(ai.startedAtMs) { mutableIntStateOf(0) }
    LaunchedEffect(ai.startedAtMs) {
        if (ai.startedAtMs == 0L) return@LaunchedEffect
        while (true) {
            elapsedSeconds = ((SystemClock.uptimeMillis() - ai.startedAtMs) / 1000).toInt()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            headline(ai, onDevice),
            color = kb.modifierKeyText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // Determinate: the bar's position means the step, so it stops implying
        // a completion estimate nobody can give.
        LinearProgressIndicator(
            progress = { ai.phase.fraction },
            color = kb.accent,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for ((index, step) in steps.withIndex()) {
                if (index > 0) {
                    Text("›", color = kb.secondaryText.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                StepLabel(
                    label = stringResource(step.labelRes),
                    done = step.fraction < ai.phase.fraction,
                    current = step == ai.phase,
                )
            }
        }
        val detail = detail(ai, elapsedSeconds)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = kb.secondaryText, fontSize = 11.sp)
        }
        // Calls already made, while the model reads what they returned. A
        // running one is the headline instead, unless it is asking to be
        // allowed: then its question and buttons have to be on screen.
        if (ai.tools.isNotEmpty() && (ai.tools.none { it.running } || ai.tools.any { it.awaitingApproval })) {
            Spacer(Modifier.height(6.dp))
            PanelToolUses(ai.tools, onApproval = onToolApproval)
        }
    }
}

/** [ChatToolUses] in the panel's colours; a page opens in the browser, an address copies. */
@Composable
private fun PanelToolUses(
    uses: List<AiToolActivity>,
    modifier: Modifier = Modifier,
    onApproval: ((AiToolApproval) -> Unit)? = null,
) {
    if (uses.isEmpty()) return
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    ChatToolUses(
        uses = uses,
        colors = ChatMarkdownColors(
            text = kb.chipText,
            // Dimmed by alpha, as the reasoning is: some themes draw their
            // secondary text in the same white.
            dim = kb.chipText.copy(alpha = kb.chipText.alpha * 0.6f),
            codeBackground = kb.chipText.copy(alpha = 0.10f),
        ),
        errorColor = kb.accent,
        onOpen = { openToolLink(context, it) },
        onCopy = { KeyboardClipboard.copy(context, it) },
        modifier = modifier,
        fontSize = 11.sp,
        onApproval = onApproval,
        animate = kb.motion.any,
    )
}

/** One step in [AiProgress]'s breadcrumb: done, in progress, or still ahead. */
@Composable
private fun StepLabel(label: String, done: Boolean, current: Boolean) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = kb.accent,
                modifier = Modifier.size(10.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            label,
            color = when {
                current -> kb.accent
                done -> kb.secondaryText
                // Steps not reached yet are dimmer than finished ones, so the
                // row reads left-to-right as progress.
                else -> kb.secondaryText.copy(alpha = 0.45f)
            },
            fontSize = 11.sp,
            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** The one-line "what's happening" above [AiProgress]'s bar. */
@Composable
private fun headline(ai: AiUi.Loading, onDevice: Boolean): String {
    val action = aiActionLabel(ai.action)
    // A running tool says what it is doing: "Searching the web for …".
    ai.tools.lastOrNull { it.running }?.let { return toolLine(it) }
    return when {
        ai.phase == AiPhase.THINKING -> stringResource(R.string.ime_ai_progress_thinking)
        onDevice -> stringResource(R.string.ime_ai_progress_on_device, action)
        ai.phase == AiPhase.WAITING -> stringResource(R.string.ime_ai_progress_waiting, action)
        else -> stringResource(R.string.ime_ai_progress_running, action)
    }
}

/**
 * The sub-line under the steps: elapsed time, plus the reasoning size while
 * thinking. Suppressed for the first second, where a timer reading "0s" is
 * noise on a request that is about to come straight back.
 */
@Composable
private fun detail(ai: AiUi.Loading, elapsedSeconds: Int): String? {
    val time = if (elapsedSeconds >= 1) {
        stringResource(R.string.ime_ai_progress_elapsed, elapsedSeconds)
    } else {
        null
    }
    if (ai.phase != AiPhase.THINKING || ai.thinkingChars <= 0) return time
    val reasoning = pluralStringResource(
        R.plurals.ime_ai_progress_reasoning_chars,
        ai.thinkingChars,
        ai.thinkingChars,
    )
    return if (time == null) {
        reasoning
    } else {
        stringResource(R.string.ime_ai_progress_detail, reasoning, time)
    }
}
