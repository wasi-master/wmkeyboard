package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantGate
import com.wasimaster.wmkeyboard.core.settings.AI_STRIP_SUGGEST_CHARS_MAX
import com.wasimaster.wmkeyboard.core.settings.AI_STRIP_SUGGEST_CHARS_MIN
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.tools.AiResearchDepth
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiActionSurface
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiReplyTones
import com.wasimaster.wmkeyboard.core.tools.ReplyStyle
import com.wasimaster.wmkeyboard.core.tools.AiInputMode
import com.wasimaster.wmkeyboard.core.tools.AiInsertMode
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.orderedAiActions
import com.wasimaster.wmkeyboard.core.tools.visibleAiActions
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.settings.AiToolRoundsRange
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import com.wasimaster.wmkeyboard.core.settings.toolBlocker
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The AI screen on the settings root (#759): the two AI tools, the chat and
 * the history, which were otherwise reached only through the Tools list.
 *
 * It holds doors, not settings. The provider, key and model stay on the AI
 * writing tools page, because AI reply, the chat and the settings assistant
 * all read them from there, and a second copy here would be two places to
 * keep in step.
 */
@Composable
internal fun AiHubSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val optionsDesc = stringResource(R.string.tools_has_options_desc)
    SettingsGroup(
        stringResource(R.string.toolai_hub_tools_title),
        info = stringResource(R.string.toolai_hub_tools_info),
    ) {
        for (tool in AiHubTools) {
            item {
                // The Tools list's own row, switch and all, so a tool turned
                // on here is the same tool turned on there.
                ToolRow(
                    tool = tool,
                    paint = settings.watch { toolAccentPaint(tool, it) },
                    blocker = settings.watch { toolBlocker(tool, it) },
                    enabled = settings.watch { tool in it.enabledTools },
                    optionsDesc = optionsDesc,
                    onToggle = { on -> scope.launch { repository.setToolEnabled(tool, on) } },
                    onOpen = { onNavigate(toolRoute(tool)) },
                )
            }
        }
    }
    // Not an AI tool, but the same kind of thing: a model on the device, and
    // nobody looks for it under Clipboard or Emoji, which it serves alike.
    SemanticSearchGroup(repository, settings)
    val assistant = settings.watch { SettingsAssistantGate.offered(it) }
    SettingsGroup(stringResource(R.string.toolai_ai_chat_group_title)) {
        item {
            NavRow(
                title = R.string.toolai_ai_chat_nav_title,
                subtitle = stringResource(R.string.toolai_ai_chat_nav_subtitle),
                route = "ai_chat",
                onClick = { onNavigate("ai_chat") },
            )
        }
        // The assistant's own off switch (#830), and its row while it is on.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_hub_settings_assistant_title,
                stringResource(R.string.toolai_hub_settings_assistant_subtitle),
                assistant,
                default = SettingsDefaults.appUi.settingsAssistant,
            ) { on ->
                // Off sends nothing more (#877): a question still out is
                // retired, and its late answer goes nowhere.
                if (!on) com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantSession.stop()
                scope.launch { repository.setSettingsAssistant(on) }
            }
        }
        item(visible = assistant, level = SettingLevel.STANDARD) {
            NavRow(
                title = R.string.settings_assistant_title,
                subtitle = stringResource(R.string.settings_assistant_nav_subtitle),
                route = "settings_assistant",
                onClick = { onNavigate("settings_assistant") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_memory_group_title), level = SettingLevel.STANDARD) {
        item {
            NavRow(
                title = R.string.toolai_memory_screen_title,
                subtitle = stringResource(R.string.toolai_memory_nav_subtitle),
                route = "ai_memory",
                onClick = { onNavigate("ai_memory") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_history_group_title), level = SettingLevel.STANDARD) {
        item {
            NavRow(
                title = R.string.toolai_ai_history_nav_title,
                subtitle = stringResource(R.string.toolai_ai_history_nav_subtitle),
                onClick = { onNavigate("ai_history") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_hub_button_group_title), level = SettingLevel.STANDARD) {
        item {
            ToggleSetting(
                R.string.toolai_hub_chat_button_everywhere_title,
                stringResource(R.string.toolai_hub_chat_button_everywhere_subtitle),
                settings.watch { it.appUi.aiChatButtonEverywhere },
                default = SettingsDefaults.appUi.aiChatButtonEverywhere,
            ) { scope.launch { repository.setAiChatButtonEverywhere(it) } }
        }
    }
    // This screen's own action, whatever the switch above says: the frame
    // leaves the everywhere button off this route so there is only one.
    RegisterFab { AiChatFab({ onNavigate("ai_chat") }) }
}

/** The tools the AI screen lists, in the order the Tools list has them. */
private val AiHubTools = listOf(ToolbarTool.AI, ToolbarTool.AI_REPLY)

/** A millisecond slider for the strip's timings, in steps of 50. */
@Composable
private fun AiStripMsSlider(
    @StringRes title: Int,
    value: Long,
    range: LongRange,
    default: Long,
    onChange: (Long) -> Unit,
) {
    val format = stringResource(R.string.toolai_strip_suggest_ms_value)
    SliderSetting(
        title,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        display = { format.format((it / 50f).roundToInt() * 50) },
        default = default.toFloat(),
    ) { onChange((it / 50f).roundToInt() * 50L) }
}

/** The AI tool's settings: provider, credentials, output and prompts. */
@Composable
internal fun AiToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val assistantOn = settings.watch { SettingsAssistantGate.offered(it) }
    // What decides which groups and rows the screen holds; each row reads its
    // own value.
    val aiProvider = settings.watch { it.ai.provider }
    val diffView = settings.watch { it.ai.diffView }
    // Tool calling (#470). The search row only appears once a search service
    // is set up — a tool whose every answer is "search is not configured"
    // would cost tokens on every run and do nothing.
    val searchReady = settings.watch { hasSearchKey(it) }
    val toolSearch = settings.watch { it.ai.toolWebSearch }
    val toolFetch = settings.watch { it.ai.toolWebFetch }
    // MCP servers (#573): a turned-on server's tools take rounds like the
    // built-in two, so the rounds slider shows for them too.
    val mcpOn = settings.watch { s -> s.ai.mcpServers.any { it.enabled } }
    // The keyboard's own tools (#698), counted the way the runner offers them.
    val builtInsOn = settings.watch { builtInToolsOn(it.ai.toolPolicies) }
    // Where the text goes rides on the list's "?" rather than as a paragraph
    // between the controls. What each kind needs is in its own description.
    val privacyNote = stringResource(
        if (aiProvider == AiProvider.ON_DEVICE) R.string.toolai_ai_on_device_info
        else R.string.toolai_ai_cloud_info,
    )
    // The services, keys, addresses and models are a provider list, like
    // translate's and search's. One row answers at a time: the one in use.
    ProvidersGroup(
        repository,
        settings,
        ProviderTool.AI,
        titleRes = R.string.toolai_ai_providers_title,
        info = stringResource(R.string.toolai_ai_providers_info) + "\n\n" + privacyNote,
    )
    val rows = settings.watch { it.aiRows() }
    val inUse = settings.watch { it.ai.providerKey }
    if (rows.size > 1) {
        val context = LocalContext.current
        SettingsGroup {
            item {
                ChoiceSetting(
                    R.string.toolai_ai_in_use_title,
                    subtitle = stringResource(R.string.toolai_ai_in_use_subtitle),
                    options = rows.map { it.key to providerTitle(context, it) },
                    selected = inUse,
                ) { key ->
                    val provider = rows.firstOrNull { it.key == key }?.let { AiProviders.of(it.typeId) } ?: return@ChoiceSetting
                    scope.launch { repository.setAiProviderKey(key, provider) }
                }
            }
        }
    }
    // The on-device model manager, each service's extra headers and its
    // F-Droid server address are on that provider's own screen.
    SettingsGroup(stringResource(R.string.toolai_ai_output_title)) {
        if (aiProvider != AiProvider.ON_DEVICE) {
            item(level = SettingLevel.ADVANCED) {
                TokenPresetSetting(
                    title = R.string.toolai_ai_max_tokens_title,
                    subtitle = stringResource(R.string.toolai_ai_max_tokens_subtitle),
                    value = settings.watch { it.ai.maxTokens },
                    presets = MaxTokenPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_max_tokens_provider_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiMaxTokens(it) } }
            }
        } else {
            item(level = SettingLevel.ADVANCED) {
                TokenPresetSetting(
                    title = R.string.toolai_ai_local_context_title,
                    subtitle = stringResource(R.string.toolai_ai_local_context_subtitle),
                    value = settings.watch { it.ai.localContextTokens },
                    presets = LocalContextPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_local_context_model_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiLocalContextTokens(it) } }
            }
        }
        item(level = SettingLevel.STANDARD) {
            TextFieldSetting(
                label = stringResource(R.string.toolai_ai_translate_to_label),
                value = settings.watch { it.ai.translateTo },
                hint = stringResource(R.string.toolai_ai_translate_to_hint),
                default = SettingsDefaults.ai.translateTo,
            ) { repository.setAiTranslateTo(it) }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_ai_show_thinking_title,
                stringResource(R.string.toolai_ai_show_thinking_subtitle),
                settings.watch { it.ai.showThinking },
                default = SettingsDefaults.ai.showThinking,
            ) { scope.launch { repository.setAiShowThinking(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_ai_model_picker_title,
                stringResource(R.string.toolai_ai_model_picker_subtitle),
                settings.watch { it.ai.panelModelPicker },
                default = SettingsDefaults.ai.panelModelPicker,
            ) { scope.launch { repository.setAiPanelModelPicker(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_diff_title,
                stringResource(R.string.toolai_ai_diff_subtitle),
                diffView,
                default = SettingsDefaults.ai.diffView,
            ) { scope.launch { repository.setAiDiffView(it) } }
        }
        item(visible = diffView, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_ai_diff_first_title,
                stringResource(R.string.toolai_ai_diff_first_subtitle),
                settings.watch { it.ai.diffOpensFirst },
                default = SettingsDefaults.ai.diffOpensFirst,
            ) { scope.launch { repository.setAiDiffOpensFirst(it) } }
        }
    }
    // The continuation chip in the suggestion strip (#944). The one AI feature
    // that sends text without a press, so the row says what goes and where.
    val stripSuggest = settings.watch { it.ai.stripSuggest }
    val stripWordOnly = settings.watch { it.ai.stripSuggestWordOnly }
    val stripChars = settings.watch { it.ai.stripSuggestChars }
    val stripComposing = settings.watch { it.ai.stripSuggestComposing }
    SettingsGroup(
        stringResource(R.string.toolai_strip_suggest_group_title),
        info = stringResource(R.string.toolai_strip_suggest_info),
    ) {
        item {
            ToggleSetting(
                R.string.toolai_strip_suggest_title,
                when {
                    aiProvider == AiProvider.ON_DEVICE ->
                        stringResource(R.string.toolai_strip_suggest_subtitle_on_device)
                    stripWordOnly -> stringResource(R.string.toolai_strip_suggest_subtitle_word)
                    else -> stringResource(R.string.toolai_strip_suggest_subtitle_cloud, numberFormat.format(stripChars))
                },
                stripSuggest,
                default = SettingsDefaults.ai.stripSuggest,
            ) { scope.launch { repository.setAiStripSuggest(it) } }
        }
        item(visible = stripSuggest, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_strip_suggest_word_only_title,
                stringResource(R.string.toolai_strip_suggest_word_only_subtitle),
                stripWordOnly,
                default = SettingsDefaults.ai.stripSuggestWordOnly,
            ) { scope.launch { repository.setAiStripSuggestWordOnly(it) } }
        }
        item(visible = stripSuggest && !stripWordOnly, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.toolai_strip_suggest_chars_title,
                subtitle = stringResource(R.string.toolai_strip_suggest_chars_subtitle),
                value = stripChars.toFloat(),
                range = AI_STRIP_SUGGEST_CHARS_MIN.toFloat()..AI_STRIP_SUGGEST_CHARS_MAX.toFloat(),
                display = { numberFormat.format((it / 10f).roundToInt() * 10) },
                default = SettingsDefaults.ai.stripSuggestChars.toFloat(),
            ) { scope.launch { repository.setAiStripSuggestChars((it / 10f).roundToInt() * 10) } }
        }
        item(visible = stripSuggest, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_strip_suggest_composing_title,
                stringResource(R.string.toolai_strip_suggest_composing_subtitle),
                stripComposing,
                default = SettingsDefaults.ai.stripSuggestComposing,
            ) { scope.launch { repository.setAiStripSuggestComposing(it) } }
        }
        // Off only with the other on: both off is the main switch's job.
        item(visible = stripSuggest && stripComposing, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_strip_suggest_after_word_title,
                stringResource(R.string.toolai_strip_suggest_after_word_subtitle),
                settings.watch { it.ai.stripSuggestAfterWord },
                default = SettingsDefaults.ai.stripSuggestAfterWord,
            ) { scope.launch { repository.setAiStripSuggestAfterWord(it) } }
        }
        item(visible = stripSuggest, level = SettingLevel.ADVANCED) {
            AiStripMsSlider(
                R.string.toolai_strip_suggest_idle_title,
                settings.watch { it.ai.stripSuggestIdleMs },
                AiStripIdleRange,
                SettingsDefaults.ai.stripSuggestIdleMs,
            ) { ms -> scope.launch { repository.setAiStripSuggestIdle(ms) } }
        }
        item(visible = stripSuggest, level = SettingLevel.ADVANCED) {
            AiStripMsSlider(
                R.string.toolai_strip_suggest_timeout_title,
                settings.watch { it.ai.stripSuggestTimeoutMs },
                AiStripTimeoutRange,
                SettingsDefaults.ai.stripSuggestTimeoutMs,
            ) { ms -> scope.launch { repository.setAiStripSuggestTimeout(ms) } }
        }
        item(visible = stripSuggest && stripComposing, level = SettingLevel.ADVANCED) {
            AiStripMsSlider(
                R.string.toolai_strip_suggest_composing_delay_title,
                settings.watch { it.ai.stripSuggestComposingDelayMs },
                AiStripComposingDelayRange,
                SettingsDefaults.ai.stripSuggestComposingDelayMs,
            ) { ms -> scope.launch { repository.setAiStripSuggestComposingDelay(ms) } }
        }
        item(visible = stripSuggest && stripComposing, level = SettingLevel.ADVANCED) {
            AiStripMsSlider(
                R.string.toolai_strip_suggest_composing_timeout_title,
                settings.watch { it.ai.stripSuggestComposingTimeoutMs },
                AiStripTimeoutRange,
                SettingsDefaults.ai.stripSuggestComposingTimeoutMs,
            ) { ms -> scope.launch { repository.setAiStripSuggestComposingTimeout(ms) } }
        }
    }
    SettingsGroup(
        stringResource(R.string.toolai_ai_tools_group_title),
        info = stringResource(
            if (AiClient.supportsNativeTools(aiProvider)) {
                R.string.toolai_ai_tools_info
            } else {
                // Brave and an on-device model are told about tools in the
                // prompt instead, which smaller models follow badly. Say so
                // here rather than letting it read as a bug.
                R.string.toolai_ai_tools_info_fallback
            },
        ),
        level = SettingLevel.STANDARD,
    ) {
        item(visible = searchReady) {
            ToggleSetting(
                R.string.toolai_ai_tool_search_title,
                stringResource(R.string.toolai_ai_tool_search_subtitle),
                toolSearch,
                default = SettingsDefaults.ai.toolWebSearch,
            ) { scope.launch { repository.setAiToolWebSearch(it) } }
        }
        item(visible = !searchReady) {
            NavRow(
                title = R.string.toolai_ai_tool_search_needs_setup_title,
                subtitle = stringResource(R.string.toolai_ai_tool_search_needs_setup_subtitle),
                onClick = { onNavigate(toolRoute(ToolbarTool.WEB_SEARCH)) },
            )
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_tool_fetch_title,
                stringResource(R.string.toolai_ai_tool_fetch_subtitle),
                toolFetch,
                info = stringResource(R.string.toolai_ai_tool_fetch_info),
                default = SettingsDefaults.ai.toolWebFetch,
            ) { scope.launch { repository.setAiToolWebFetch(it) } }
        }
        item {
            NavRow(
                title = R.string.toolai_ai_builtin_title,
                subtitle = stringResource(R.string.toolai_ai_builtin_subtitle),
                value = numberFormat.format(builtInsOn),
                route = AI_BUILT_IN_TOOLS_ROUTE,
                onClick = { onNavigate(AI_BUILT_IN_TOOLS_ROUTE) },
            )
        }
        item(level = SettingLevel.ADVANCED) {
            // The count is of the servers that are on, which is what decides
            // whether the model is offered anything from them at all.
            val enabledServers = settings.watch { s -> s.ai.mcpServers.count { it.enabled } }
            NavRow(
                title = R.string.toolai_ai_mcp_title,
                subtitle = stringResource(R.string.toolai_ai_mcp_subtitle),
                value = numberFormat.format(enabledServers),
                route = "ai_mcp",
                onClick = { onNavigate("ai_mcp") },
            )
        }
        item(visible = toolFetch || (toolSearch && searchReady) || mcpOn || builtInsOn > 0, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.toolai_ai_tool_rounds_title,
                subtitle = stringResource(R.string.toolai_ai_tool_rounds_subtitle),
                value = settings.watch { it.ai.toolMaxRounds }.toFloat(),
                range = AiToolRoundsRange.first.toFloat()..AiToolRoundsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.toolai_ai_tool_rounds_info),
                default = SettingsDefaults.ai.toolMaxRounds.toFloat(),
            ) { scope.launch { repository.setAiToolMaxRounds(it.roundToInt()) } }
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_chat_group_title)) {
        item {
            NavRow(
                title = R.string.toolai_ai_chat_nav_title,
                subtitle = stringResource(R.string.toolai_ai_chat_nav_subtitle),
                route = "ai_chat",
                onClick = { onNavigate("ai_chat") },
            )
        }
        // Gone while the assistant is turned off (#830).
        item(visible = assistantOn, level = SettingLevel.STANDARD) {
            NavRow(
                title = R.string.settings_assistant_title,
                subtitle = stringResource(R.string.settings_assistant_nav_subtitle),
                route = "settings_assistant",
                onClick = { onNavigate("settings_assistant") },
            )
        }
        // The chat on the keyboard's AI panel (#280). Off by default: a prompt
        // is often more than one line, and the panel has a Send button.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_chat_enter_sends_title,
                stringResource(R.string.toolai_chat_enter_sends_subtitle),
                settings.watch { it.ai.chatEnterSends },
                default = SettingsDefaults.ai.chatEnterSends,
            ) { scope.launch { repository.setAiChatEnterSends(it) } }
        }
        // The model that answered names the chat (#882), on both chats.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_chat_auto_rename_title,
                stringResource(R.string.toolai_chat_auto_rename_subtitle),
                settings.watch { it.ai.chatAutoRename },
                default = SettingsDefaults.ai.chatAutoRename,
            ) { scope.launch { repository.setAiChatAutoRename(it) } }
        }
        // Suggested next questions under an answer, from cloud models only.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_chat_follow_ups_title,
                stringResource(R.string.toolai_chat_follow_ups_subtitle),
                settings.watch { it.ai.chatFollowUps },
                default = SettingsDefaults.ai.chatFollowUps,
            ) { scope.launch { repository.setAiChatFollowUps(it) } }
        }
        // The chat's Deep research mode: how far a report goes, and whether
        // its plan waits for Start.
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.toolai_research_depth_title,
                subtitle = stringResource(R.string.toolai_research_depth_subtitle),
                options = listOf(
                    AiResearchDepth.QUICK to stringResource(R.string.toolai_research_depth_quick),
                    AiResearchDepth.STANDARD to stringResource(R.string.toolai_research_depth_standard),
                    AiResearchDepth.DEEP to stringResource(R.string.toolai_research_depth_deep),
                ),
                selected = settings.watch { it.ai.research.depth },
                default = SettingsDefaults.ai.research.depth,
            ) { scope.launch { repository.setAiResearchDepth(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_research_review_plan_title,
                stringResource(R.string.toolai_research_review_plan_subtitle),
                settings.watch { it.ai.research.reviewPlan },
                default = SettingsDefaults.ai.research.reviewPlan,
            ) { scope.launch { repository.setAiResearchReviewPlan(it) } }
        }
        // "Ask AI" in other apps' selection menus (#897). AskAiSelectionMenu
        // switches the entry from the setting, here or from a backup alike.
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_selection_menu_title,
                stringResource(R.string.toolai_ai_selection_menu_subtitle),
                settings.watch { it.ai.selectionMenu },
                default = SettingsDefaults.ai.selectionMenu,
            ) { scope.launch { repository.setAiSelectionMenu(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_actions_group_title)) {
        item {
            val visibleCount = settings.watch {
                visibleAiActions(
                    it.ai.customActions,
                    it.ai.actionOrder,
                    it.ai.hiddenActions,
                ).size
            }
            NavRow(
                title = R.string.toolai_ai_actions_title,
                subtitle = stringResource(R.string.toolai_ai_actions_subtitle),
                value = numberFormat.format(visibleCount),
                onClick = { onNavigate("ai_actions") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_history_group_title), level = SettingLevel.STANDARD) {
        item {
            // Always reachable, turned on or not, so "Delete all history" does
            // not disappear along with the switch that filled it.
            NavRow(
                title = R.string.toolai_ai_history_nav_title,
                subtitle = stringResource(R.string.toolai_ai_history_nav_subtitle),
                onClick = { onNavigate("ai_history") },
            )
        }
    }
}
/**
 * Response-length steps for a cloud or self-hosted service. They rise by
 * doubling rather than in even steps, because what the user is choosing between
 * is "a paragraph" and "a whole document", not 4,000 versus 5,000 tokens.
 */
private val MaxTokenPresets =
    listOf(1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072)
/**
 * Context-window steps for an on-device model. Far smaller: this is the window
 * the model is loaded with, and a phone pays for every token of it in memory.
 */
private val LocalContextPresets = listOf(1024, 2048, 4096, 8192, 16_384)
/**
 * A token count picked from steps rather than dragged on a slider. `0` selects
 * [unlimitedLabel], which sends no number at all.
 *
 * A slider cannot do this job any more: the range now spans 1,024 to 131,072,
 * and no thumb lands on a useful value across that span. The steps also make
 * "no limit" selectable, which a numeric slider has nowhere to put.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenPresetSetting(
    @StringRes title: Int,
    subtitle: String,
    value: Int,
    presets: List<Int>,
    unlimitedLabel: String,
    numberFormat: String,
    onPick: (Int) -> Unit,
) {
    ControlSetting(title, subtitle = subtitle) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            for (preset in presets) {
                FilterChip(
                    selected = value == preset,
                    onClick = { onPick(preset) },
                    label = { Text(numberFormat.format(preset), maxLines = 1) },
                )
            }
            FilterChip(
                selected = value <= 0,
                onClick = { onPick(0) },
                label = { Text(unlimitedLabel, maxLines = 1) },
            )
        }
    }
}
/**
 * One AI action's editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`ai_action_edit/{actionId}`) is the same string for every
 * action and would hang one key on all of them.
 */
internal fun aiActionEditRoute(actionId: String): String = "ai_action_edit/$actionId"

// ---- AI actions ----

/**
 * The buttons on the AI panel: reorder them, turn them off, edit one, or write
 * a new one.
 *
 * A shipped action is never deleted. Editing one stores a spec under the same
 * id that shadows it, so "Reset" drops that spec and the shipped version comes
 * back; turning one off only takes it off the panel.
 */
@Composable
internal fun AiActionsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    // The AI tool's actions, or the AI reply tool's tones (#234): the same
    // list, editor and storage, each showing only its own surface.
    surface: AiActionSurface = AiActionSurface.TRANSFORM,
) {
    val scope = rememberCoroutineScope()
    val other = if (surface == AiActionSurface.REPLY) AiActionSurface.TRANSFORM else AiActionSurface.REPLY
    // Which rows the group holds, and in what order; each row reads whether
    // its own action is hidden.
    val ordered = settings.watch { orderedAiActions(it.ai.customActions, it.ai.actionOrder, surface) }

    // ReorderSetting takes a plain (T) -> String, which cannot resolve a string
    // resource, so the shipped names are looked up here first.
    val names = ordered.associate { it.id to aiActionName(it) }

    var reordering by rememberSaveable { mutableStateOf(false) }
    val replies = surface == AiActionSurface.REPLY
    SettingsGroup(
        stringResource(if (replies) R.string.toolai_reply_tones_title else R.string.toolai_ai_actions_title),
        info = stringResource(if (replies) R.string.toolai_reply_tones_caption else R.string.toolai_ai_actions_caption),
        action = if (ordered.size > 1) {
            {
            IconButton(onClick = { reordering = !reordering }) {
                Icon(
                    if (reordering) Icons.Outlined.Check else Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.toolai_ai_actions_reorder_title),
                )
            }
            }
        } else null,
    ) {
        item(visible = reordering) {
            ReorderableColumn(
                ordered,
                label = { names[it.id].orEmpty() },
                // One stored order holds both surfaces: the other surface's ids
                // ride along, or reordering tones would forget the actions'
                // order and the other way round.
                onReorder = { next ->
                    val ai = settings.value.ai
                    val kept = orderedAiActions(ai.customActions, ai.actionOrder, other).map { it.id }
                    scope.launch { repository.setAiActionOrder(next.map { it.id } + kept) }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        for (action in ordered) {
            if (reordering) break
            item(key = action.id) {
                val on = settings.watch { action.id !in it.ai.hiddenActions }
                // Delete for an action of your own; for a shipped one that you
                // changed, Reset, which is the same call bringing it back.
                val stored = settings.watch { s -> s.ai.customActions.any { it.id == action.id } }
                val shipped = BuiltInAiActions.byId(action.id) ?: BuiltInAiReplyTones.byId(action.id)
                val editLabel = stringResource(R.string.toolai_ai_action_edit_desc)
                val removeLabel = stringResource(
                    if (shipped != null) R.string.toolai_ai_action_reset_action else R.string.toolai_ai_action_delete_action,
                )
                WmRow(
                    title = names[action.id].orEmpty(),
                    supporting = {
                        Text(
                            aiActionSummary(action),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leading = {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                val ai = settings.value.ai
                                val hidden = ai.hiddenActions.toSet()
                                val visibleCount = orderedAiActions(ai.customActions, ai.actionOrder, surface)
                                    .count { it.id !in hidden }
                                // The panel needs at least one button, or it is
                                // an empty box with no way back to a full one.
                                if (checked || visibleCount > 1) {
                                    scope.launch {
                                        repository.setAiActionHidden(action.id, !checked)
                                    }
                                }
                            },
                        )
                    },
                    swipeActions = buildList {
                        add(SwipeAction(Icons.Outlined.Edit, editLabel) { onNavigate("ai_action_edit/${action.id}") })
                        if (stored) {
                            val remove = { scope.launch { repository.deleteAiAction(action.id) }; Unit }
                            add(
                                if (shipped != null) SwipeAction(Icons.Outlined.Refresh, removeLabel, onClick = remove)
                                else deleteSwipeAction(removeLabel, remove),
                            )
                        }
                    },
                    trailing = {
                        IconButton(onClick = { onNavigate("ai_action_edit/${action.id}") }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    R.string.toolai_ai_action_edit_desc,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
    RegisterAddFab(stringResource(if (replies) R.string.toolai_reply_tone_new else R.string.toolai_ai_action_new_title)) {
        val prefix = if (replies) BuiltInAiReplyTones.CUSTOM_PREFIX else BuiltInAiActions.CUSTOM_PREFIX
        onNavigate("ai_action_edit/" + prefix + System.currentTimeMillis())
    }
}
/** A shipped action's translated name, or the name the user gave it. */
@Composable
internal fun aiActionName(spec: AiActionSpec): String =
    (BuiltInAiActions.labelRes(spec) ?: BuiltInAiReplyTones.labelRes(spec))?.let { stringResource(it) } ?: spec.name
/** The one-line recap under an action's name on the list screen. */
@Composable
private fun aiActionSummary(spec: AiActionSpec): String = when {
    // With a prefill there is a prompt worth showing, even though the action
    // asks each run: it is what the instruction box will open with.
    spec.askEachRun && spec.prefillPrompt && spec.task.isNotBlank() -> spec.task
    spec.askEachRun -> stringResource(R.string.toolai_ai_action_ask_summary)
    spec.task.isBlank() -> stringResource(R.string.toolai_ai_action_no_prompt_summary)
    else -> spec.task
}
/**
 * Write a new action, or edit one. This is also where a prompt is written: the
 * field fills the screen rather than the four lines it used to get, because a
 * prompt is a paragraph and scrolling one through four lines is unusable.
 */
@Composable
internal fun AiActionEditor(
    repository: SettingsRepository,
    settings: LiveSettings,
    actionId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.watch { s -> s.ai.customActions.firstOrNull { it.id == actionId } }
    val builtIn = BuiltInAiActions.byId(actionId) ?: BuiltInAiReplyTones.byId(actionId)
    val existing = override ?: builtIn
    // A tone for the reply tool (#234) or an action for the AI tool: what is
    // stored says, else the shipped entry, else the id a new tone was given.
    val surface = existing?.surface
        ?: if (actionId.startsWith(BuiltInAiReplyTones.CUSTOM_PREFIX)) AiActionSurface.REPLY else AiActionSurface.TRANSFORM
    val tone = surface == AiActionSurface.REPLY
    val defaultName = stringResource(R.string.toolai_ai_action_default_name)

    var name by remember(actionId) { mutableStateOf(existing?.name.orEmpty()) }
    var task by remember(actionId) { mutableStateOf(existing?.task.orEmpty()) }
    var rawPrompt by remember(actionId) { mutableStateOf(existing?.rawPrompt ?: false) }
    var outputOnly by remember(actionId) { mutableStateOf(existing?.outputOnly ?: true) }
    var askEachRun by remember(actionId) { mutableStateOf(existing?.askEachRun ?: false) }
    var prefillPrompt by remember(actionId) { mutableStateOf(existing?.prefillPrompt ?: false) }
    var worksWithoutText by remember(actionId) {
        mutableStateOf(existing?.worksWithoutText ?: false)
    }
    var beforeCursor by remember(actionId) {
        mutableStateOf(existing?.inputMode == AiInputMode.BEFORE_CURSOR)
    }
    var append by remember(actionId) {
        mutableStateOf(existing?.insertMode == AiInsertMode.APPEND)
    }

    fun draft() = AiActionSpec(
        id = actionId,
        name = name.trim().ifEmpty { builtIn?.name ?: defaultName },
        task = task,
        rawPrompt = rawPrompt,
        outputOnly = outputOnly,
        inputMode = if (beforeCursor) AiInputMode.BEFORE_CURSOR else AiInputMode.FIELD,
        insertMode = if (append) AiInsertMode.APPEND else AiInsertMode.REPLACE,
        askEachRun = askEachRun,
        prefillPrompt = prefillPrompt,
        worksWithoutText = worksWithoutText,
        surface = surface,
    )

    // The prompt is stored either as the whole task, or as the text the
    // instruction box opens with. Both are the same field, so it shows for
    // either job and hides only when the action keeps no prompt at all.
    val showPromptField = !askEachRun || prefillPrompt

    if (builtIn != null) {
        // The stored English name is what a shipped action is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        StateBanner(stringResource(R.string.toolai_ai_action_builtin_caption, aiActionName(builtIn)))
    }
    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.toolai_ai_action_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item(visible = showPromptField) {
            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                label = {
                    Text(
                        stringResource(
                            if (askEachRun) {
                                R.string.toolai_ai_action_prefill_label
                            } else {
                                R.string.toolai_ai_action_task_label
                            },
                        ),
                    )
                },
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                askEachRun -> R.string.toolai_ai_action_prefill_hint
                                rawPrompt -> R.string.toolai_ai_action_task_raw_hint
                                else -> R.string.toolai_ai_action_task_hint
                            },
                            AiPrompts.TRANSLATE_TOKEN,
                        ),
                    )
                },
                // No maxLines on purpose: the field grows with the prompt
                // instead of scrolling a paragraph through four lines.
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_action_behaviour_title)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_action_ask_title,
                stringResource(R.string.toolai_ai_action_ask_subtitle),
                askEachRun,
            ) { askEachRun = it }
        }
        item(visible = askEachRun, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_ai_action_prefill_title,
                stringResource(R.string.toolai_ai_action_prefill_subtitle),
                prefillPrompt,
            ) { prefillPrompt = it }
        }
        if (!tone) item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_action_empty_field_title,
                stringResource(R.string.toolai_ai_action_empty_field_subtitle),
                worksWithoutText,
            ) { worksWithoutText = it }
        }
        if (!tone) item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.toolai_ai_action_before_cursor_title,
                stringResource(R.string.toolai_ai_action_before_cursor_subtitle),
                beforeCursor,
            ) { beforeCursor = it }
        }
        if (!tone) item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.toolai_ai_action_append_title,
                stringResource(R.string.toolai_ai_action_append_subtitle),
                append,
            ) { append = it }
        }
        // A tone is always framed by the reply prompt: its guard is what keeps
        // a stranger's message from steering the model, so it has no raw mode.
        if (!askEachRun && !tone) {
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.toolai_ai_action_raw_title,
                    stringResource(R.string.toolai_ai_action_raw_subtitle),
                    rawPrompt,
                ) { rawPrompt = it }
            }
            // A raw prompt is sent as written, frame and all: `AiPrompts`
            // returns the task before it ever reads this, so the switch is
            // inert. It sits under the raw one for that reason.
            if (!rawPrompt) item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.toolai_ai_action_output_only_title,
                    stringResource(R.string.toolai_ai_action_output_only_subtitle),
                    outputOnly,
                ) { outputOnly = it }
            }
        }
    }
    if (showPromptField) {
        // The safety wording is part of every prompt and is not editable, so
        // the only way to make it visible is to show the assembled result. An
        // action that asks each run is framed differently, so with a prefill
        // this previews the instruction path rather than the stored-prompt one.
        SettingsGroup(stringResource(R.string.toolai_ai_action_preview_title), level = SettingLevel.STANDARD) {
            item {
                val translateTo = settings.watch { it.ai.translateTo }
                Text(
                    when {
                        tone -> AiPrompts.replyPrompt(draft(), ReplyStyle(), instruction = task)
                        askEachRun -> AiPrompts.customPrompt(
                            AiPrompts.resolvedTask(draft(), translateTo),
                        )
                        else -> AiPrompts.systemPrompt(draft(), translateTo)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        // For a shipped action this is a reset, not a delete: dropping the
        // stored spec brings the shipped one back.
        if (override != null) {
            TextButton(onClick = {
                scope.launch { repository.deleteAiAction(actionId) }
                onDone()
            }) {
                Icon(
                    if (builtIn != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (builtIn != null) {
                            R.string.toolai_ai_action_reset_action
                        } else {
                            R.string.toolai_ai_action_delete_action
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            // An action with no prompt and no way to ask for one would do
            // nothing at all.
            enabled = askEachRun || task.isNotBlank(),
            onClick = {
                scope.launch { repository.upsertAiAction(draft()) }
                onDone()
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
}
