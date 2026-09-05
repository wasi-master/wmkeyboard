package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Add
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
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiInputMode
import com.wasimaster.wmkeyboard.core.tools.AiInsertMode
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.orderedAiActions
import com.wasimaster.wmkeyboard.core.tools.visibleAiActions
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The AI tool's settings: provider, credentials, output and prompts. */
@Composable
internal fun AiToolSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    SectionHeader(stringResource(R.string.toolai_ai_provider_title))
    // Nine providers no longer fit a segmented row; chips wrap instead. The
    // order is displayOrder, not the enum's own: new entries can only be
    // appended there, which would put them after "On your device".
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val providers = AiProvider.displayOrder.filter {
            it != AiProvider.ON_DEVICE || BuildConfig.ENABLE_LOCAL_LLM
        }
        for (provider in providers) {
            FilterChip(
                selected = settings.ai.provider == provider,
                onClick = { scope.launch { repository.setAiProvider(provider) } },
                label = { Text(stringResource(provider.labelRes), maxLines = 1) },
            )
        }
    }
    when (settings.ai.provider) {
        AiProvider.ANTHROPIC -> SettingsGroup(
            stringResource(R.string.toolai_ai_anthropic_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_anthropic_key_label),
                    value = settings.ai.anthropicKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_anthropic_key_hint),
                ) { repository.setAiAnthropicKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.anthropicModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.ANTHROPIC,
                    ),
                    default = SettingsDefaults.ai.anthropicModel,
                ) { repository.setAiAnthropicModel(it) }
            }
        }
        AiProvider.OPENAI -> SettingsGroup(
            stringResource(R.string.toolai_ai_openai_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_openai_key_label),
                    value = settings.ai.openAiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_openai_key_hint),
                ) { repository.setAiOpenAiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.openAiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.OPENAI,
                    ),
                    default = SettingsDefaults.ai.openAiModel,
                ) { repository.setAiOpenAiModel(it) }
            }
        }
        AiProvider.GEMINI -> SettingsGroup(
            stringResource(R.string.toolai_ai_gemini_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_gemini_key_label),
                    value = settings.ai.geminiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_gemini_key_hint),
                ) { repository.setAiGeminiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.geminiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.GEMINI,
                    ),
                    default = SettingsDefaults.ai.geminiModel,
                ) { repository.setAiGeminiModel(it) }
            }
        }
        AiProvider.OLLAMA -> SettingsGroup(
            stringResource(R.string.toolai_ai_ollama_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_server_address_label),
                    value = settings.ai.ollamaUrl,
                    hint = stringResource(R.string.toolai_ai_ollama_url_hint),
                    default = SettingsDefaults.ai.ollamaUrl,
                ) { repository.setAiOllamaUrl(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.ollamaModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.OLLAMA,
                    ),
                    default = SettingsDefaults.ai.ollamaModel,
                ) { repository.setAiOllamaModel(it) }
            }
        }
        AiProvider.LM_STUDIO -> SettingsGroup(
            stringResource(R.string.toolai_ai_lm_studio_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_server_address_label),
                    value = settings.ai.lmStudioUrl,
                    hint = stringResource(R.string.toolai_ai_lm_studio_url_hint),
                    default = SettingsDefaults.ai.lmStudioUrl,
                ) { repository.setAiLmStudioUrl(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.lmStudioModel,
                    hint = stringResource(R.string.toolai_ai_lm_studio_model_hint),
                    default = SettingsDefaults.ai.lmStudioModel,
                ) { repository.setAiLmStudioModel(it) }
            }
        }
        AiProvider.XAI -> SettingsGroup(
            stringResource(R.string.toolai_ai_xai_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_xai_key_label),
                    value = settings.ai.xaiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_xai_key_hint),
                ) { repository.setAiXaiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.xaiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.XAI,
                    ),
                    default = SettingsDefaults.ai.xaiModel,
                ) { repository.setAiXaiModel(it) }
            }
        }
        AiProvider.DEEPSEEK -> SettingsGroup(
            stringResource(R.string.toolai_ai_deepseek_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_deepseek_key_label),
                    value = settings.ai.deepSeekKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_deepseek_key_hint),
                ) { repository.setAiDeepSeekKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.deepSeekModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.DEEPSEEK,
                    ),
                    default = SettingsDefaults.ai.deepSeekModel,
                ) { repository.setAiDeepSeekModel(it) }
            }
        }
        AiProvider.OPENAI_COMPATIBLE -> SettingsGroup(
            stringResource(R.string.toolai_ai_compatible_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_compatible_url_label),
                    value = settings.ai.compatibleUrl,
                    hint = stringResource(R.string.toolai_ai_compatible_url_hint),
                    default = SettingsDefaults.ai.compatibleUrl,
                ) { repository.setAiCompatibleUrl(it) }
            }
            item {
                // No default model: there is nothing sensible to guess for a
                // service the app knows nothing about.
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.compatibleModel,
                    hint = stringResource(R.string.toolai_ai_compatible_model_hint),
                    default = SettingsDefaults.ai.compatibleModel,
                ) { repository.setAiCompatibleModel(it) }
            }
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_compatible_key_label),
                    value = settings.ai.compatibleKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_compatible_key_hint),
                ) { repository.setAiCompatibleKey(it) }
            }
        }
        AiProvider.ON_DEVICE -> LocalLlmModelManager(repository, settings)
    }
    if (settings.ai.provider == AiProvider.OLLAMA || settings.ai.provider == AiProvider.LM_STUDIO) {
        CaptionText(stringResource(R.string.toolai_ai_local_server_info))
    }
    if (settings.ai.provider == AiProvider.OPENAI_COMPATIBLE) {
        CaptionText(stringResource(R.string.toolai_ai_compatible_info))
    }
    SettingsGroup(stringResource(R.string.toolai_ai_output_title)) {
        if (settings.ai.provider != AiProvider.ON_DEVICE) {
            item {
                TokenPresetSetting(
                    title = stringResource(R.string.toolai_ai_max_tokens_title),
                    subtitle = stringResource(R.string.toolai_ai_max_tokens_subtitle),
                    value = settings.ai.maxTokens,
                    presets = MaxTokenPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_max_tokens_provider_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiMaxTokens(it) } }
            }
        } else {
            item {
                TokenPresetSetting(
                    title = stringResource(R.string.toolai_ai_local_context_title),
                    subtitle = stringResource(R.string.toolai_ai_local_context_subtitle),
                    value = settings.ai.localContextTokens,
                    presets = LocalContextPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_local_context_model_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiLocalContextTokens(it) } }
            }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.toolai_ai_translate_to_label),
                value = settings.ai.translateTo,
                hint = stringResource(R.string.toolai_ai_translate_to_hint),
                default = SettingsDefaults.ai.translateTo,
            ) { repository.setAiTranslateTo(it) }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_show_thinking_title,
                stringResource(R.string.toolai_ai_show_thinking_subtitle),
                settings.ai.showThinking,
                default = SettingsDefaults.ai.showThinking,
            ) { scope.launch { repository.setAiShowThinking(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_model_picker_title,
                stringResource(R.string.toolai_ai_model_picker_subtitle),
                settings.ai.panelModelPicker,
                default = SettingsDefaults.ai.panelModelPicker,
            ) { scope.launch { repository.setAiPanelModelPicker(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_diff_title,
                stringResource(R.string.toolai_ai_diff_subtitle),
                settings.ai.diffView,
                default = SettingsDefaults.ai.diffView,
            ) { scope.launch { repository.setAiDiffView(it) } }
        }
        if (settings.ai.diffView) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_diff_first_title,
                    stringResource(R.string.toolai_ai_diff_first_subtitle),
                    settings.ai.diffOpensFirst,
                    default = SettingsDefaults.ai.diffOpensFirst,
                ) { scope.launch { repository.setAiDiffOpensFirst(it) } }
            }
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
    }
    SettingsGroup(stringResource(R.string.toolai_ai_actions_group_title)) {
        item {
            val visible = visibleAiActions(
                settings.ai.customActions,
                settings.ai.actionOrder,
                settings.ai.hiddenActions,
            )
            NavRow(
                title = R.string.toolai_ai_actions_title,
                subtitle = stringResource(R.string.toolai_ai_actions_subtitle),
                value = numberFormat.format(visible.size),
                onClick = { onNavigate("ai_actions") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_history_group_title)) {
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
    CaptionText(
        stringResource(
            if (settings.ai.provider == AiProvider.ON_DEVICE) {
                R.string.toolai_ai_on_device_info
            } else {
                R.string.toolai_ai_cloud_info
            },
        ),
    )
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
    title: String,
    subtitle: String,
    value: Int,
    presets: List<Int>,
    unlimitedLabel: String,
    numberFormat: String,
    onPick: (Int) -> Unit,
) {
    HighlightableRow(title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
}
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
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ordered = orderedAiActions(settings.ai.customActions, settings.ai.actionOrder)
    val hidden = settings.ai.hiddenActions.toSet()
    val visibleCount = ordered.count { it.id !in hidden }

    // ReorderSetting takes a plain (T) -> String, which cannot resolve a string
    // resource, so the shipped names are looked up here first.
    val names = ordered.associate { it.id to aiActionName(it) }

    CaptionText(stringResource(R.string.toolai_ai_actions_caption))
    ReorderSetting(
        title = stringResource(R.string.toolai_ai_actions_reorder_title),
        dialogTitle = stringResource(R.string.toolai_ai_actions_reorder_title),
        items = ordered,
        label = { names[it.id].orEmpty() },
        onReordered = { next -> scope.launch { repository.setAiActionOrder(next.map { it.id }) } },
    )
    SettingsGroup {
        for (action in ordered) {
            item {
                val on = action.id !in hidden
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
        item {
            WmRow(
                title = stringResource(R.string.toolai_ai_action_new_title),
                subtitle = stringResource(R.string.toolai_ai_action_new_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    val id = BuiltInAiActions.CUSTOM_PREFIX + System.currentTimeMillis()
                    onNavigate("ai_action_edit/$id")
                },
            )
        }
    }
}
/** A shipped action's translated name, or the name the user gave it. */
@Composable
private fun aiActionName(spec: AiActionSpec): String =
    BuiltInAiActions.labelRes(spec)?.let { stringResource(it) } ?: spec.name
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
    settings: KeyboardSettings,
    actionId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.ai.customActions.firstOrNull { it.id == actionId }
    val builtIn = BuiltInAiActions.byId(actionId)
    val existing = override ?: builtIn
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
    )

    // The prompt is stored either as the whole task, or as the text the
    // instruction box opens with. Both are the same field, so it shows for
    // either job and hides only when the action keeps no prompt at all.
    val showPromptField = !askEachRun || prefillPrompt

    if (builtIn != null) {
        // The stored English name is what a shipped action is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        CaptionText(
            stringResource(R.string.toolai_ai_action_builtin_caption, aiActionName(builtIn)),
        )
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
        if (showPromptField) {
            item {
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
    }
    SettingsGroup(stringResource(R.string.toolai_ai_action_behaviour_title)) {
        item {
            ToggleSetting(
                R.string.toolai_ai_action_ask_title,
                stringResource(R.string.toolai_ai_action_ask_subtitle),
                askEachRun,
            ) { askEachRun = it }
        }
        if (askEachRun) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_prefill_title,
                    stringResource(R.string.toolai_ai_action_prefill_subtitle),
                    prefillPrompt,
                ) { prefillPrompt = it }
            }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_empty_field_title,
                stringResource(R.string.toolai_ai_action_empty_field_subtitle),
                worksWithoutText,
            ) { worksWithoutText = it }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_before_cursor_title,
                stringResource(R.string.toolai_ai_action_before_cursor_subtitle),
                beforeCursor,
            ) { beforeCursor = it }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_append_title,
                stringResource(R.string.toolai_ai_action_append_subtitle),
                append,
            ) { append = it }
        }
        if (!askEachRun) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_output_only_title,
                    stringResource(R.string.toolai_ai_action_output_only_subtitle),
                    outputOnly,
                ) { outputOnly = it }
            }
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_raw_title,
                    stringResource(R.string.toolai_ai_action_raw_subtitle),
                    rawPrompt,
                ) { rawPrompt = it }
            }
        }
    }
    if (showPromptField) {
        // The safety wording is part of every prompt and is not editable, so
        // the only way to make it visible is to show the assembled result. An
        // action that asks each run is framed differently, so with a prefill
        // this previews the instruction path rather than the stored-prompt one.
        SettingsGroup(stringResource(R.string.toolai_ai_action_preview_title)) {
            item {
                Text(
                    if (askEachRun) {
                        AiPrompts.customPrompt(
                            AiPrompts.resolvedTask(draft(), settings.ai.translateTo),
                        )
                    } else {
                        AiPrompts.systemPrompt(draft(), settings.ai.translateTo)
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
