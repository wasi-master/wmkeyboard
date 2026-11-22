package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.location.LocationAccess
import com.wasimaster.wmkeyboard.core.semantic.Embeddings
import com.wasimaster.wmkeyboard.core.semantic.SemanticDownloads
import com.wasimaster.wmkeyboard.core.semantic.SemanticModels
import com.wasimaster.wmkeyboard.core.tools.AiCalendarMode
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendar
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.homes
import com.wasimaster.wmkeyboard.core.settings.hasSearchKey
import com.wasimaster.wmkeyboard.core.tools.AiBuiltInTool
import com.wasimaster.wmkeyboard.core.tools.AiToolGroup
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.core.tools.McpToolPolicy
import kotlinx.coroutines.launch

/** The route of [AiBuiltInToolSettings]. */
internal const val AI_BUILT_IN_TOOLS_ROUTE = "ai_tools"

/** What the settings call one of the keyboard's own AI tools, and what it says under it. */
internal class BuiltInToolText(@StringRes val title: Int, @StringRes val subtitle: Int)

/** Every tool in [AiTools.builtIns] has an entry; one without would have no row. */
internal val builtInToolText: Map<String, BuiltInToolText> = mapOf(
    AiTools.WM_DOCS to BuiltInToolText(
        R.string.toolai_ai_builtin_wm_docs_title,
        R.string.toolai_ai_builtin_wm_docs_subtitle,
    ),
    AiTools.IMAGE_SEARCH to BuiltInToolText(
        R.string.toolai_ai_builtin_image_search_title,
        R.string.toolai_ai_builtin_image_search_subtitle,
    ),
    AiTools.WEATHER to BuiltInToolText(
        R.string.toolai_ai_builtin_weather_title,
        R.string.toolai_ai_builtin_weather_subtitle,
    ),
    AiTools.WIKIPEDIA to BuiltInToolText(
        R.string.toolai_ai_builtin_wikipedia_title,
        R.string.toolai_ai_builtin_wikipedia_subtitle,
    ),
    AiTools.DEFINE_WORD to BuiltInToolText(
        R.string.toolai_ai_builtin_define_word_title,
        R.string.toolai_ai_builtin_define_word_subtitle,
    ),
    AiTools.TRANSLATE to BuiltInToolText(
        R.string.toolai_ai_builtin_translate_title,
        R.string.toolai_ai_builtin_translate_subtitle,
    ),
    AiTools.CONVERT_CURRENCY to BuiltInToolText(
        R.string.toolai_ai_builtin_convert_currency_title,
        R.string.toolai_ai_builtin_convert_currency_subtitle,
    ),
    AiTools.CONVERT_UNITS to BuiltInToolText(
        R.string.toolai_ai_builtin_convert_units_title,
        R.string.toolai_ai_builtin_convert_units_subtitle,
    ),
    AiTools.CALCULATE to BuiltInToolText(
        R.string.toolai_ai_builtin_calculate_title,
        R.string.toolai_ai_builtin_calculate_subtitle,
    ),
    AiTools.CALENDAR_EVENTS to BuiltInToolText(
        R.string.toolai_ai_builtin_calendar_events_title,
        R.string.toolai_ai_builtin_calendar_events_subtitle,
    ),
    AiTools.SEARCH_NOTES to BuiltInToolText(
        R.string.toolai_ai_builtin_search_notes_title,
        R.string.toolai_ai_builtin_search_notes_subtitle,
    ),
    AiTools.SEARCH_SNIPPETS to BuiltInToolText(
        R.string.toolai_ai_builtin_search_snippets_title,
        R.string.toolai_ai_builtin_search_snippets_subtitle,
    ),
    AiTools.SEARCH_CLIPBOARD to BuiltInToolText(
        R.string.toolai_ai_builtin_search_clipboard_title,
        R.string.toolai_ai_builtin_search_clipboard_subtitle,
    ),
    AiTools.CURRENT_LOCATION to BuiltInToolText(
        R.string.toolai_ai_builtin_current_location_title,
        R.string.toolai_ai_builtin_current_location_subtitle,
    ),
    AiTools.HOME_ASSISTANT_STATES to BuiltInToolText(
        R.string.toolai_ai_builtin_home_assistant_states_title,
        R.string.toolai_ai_builtin_home_assistant_states_subtitle,
    ),
    AiTools.HOME_ASSISTANT to BuiltInToolText(
        R.string.toolai_ai_builtin_home_assistant_title,
        R.string.toolai_ai_builtin_home_assistant_subtitle,
    ),
    AiTools.ADD_CALENDAR_EVENT to BuiltInToolText(
        R.string.toolai_ai_builtin_add_calendar_event_title,
        R.string.toolai_ai_builtin_add_calendar_event_subtitle,
    ),
    AiTools.ADD_NOTE to BuiltInToolText(
        R.string.toolai_ai_builtin_add_note_title,
        R.string.toolai_ai_builtin_add_note_subtitle,
    ),
    AiTools.SET_ALARM to BuiltInToolText(
        R.string.toolai_ai_builtin_set_alarm_title,
        R.string.toolai_ai_builtin_set_alarm_subtitle,
    ),
    AiTools.START_TIMER to BuiltInToolText(
        R.string.toolai_ai_builtin_start_timer_title,
        R.string.toolai_ai_builtin_start_timer_subtitle,
    ),
    AiTools.OPEN_APP to BuiltInToolText(
        R.string.toolai_ai_builtin_open_app_title,
        R.string.toolai_ai_builtin_open_app_subtitle,
    ),
    AiTools.MEDIA_CONTROL to BuiltInToolText(
        R.string.toolai_ai_builtin_media_control_title,
        R.string.toolai_ai_builtin_media_control_subtitle,
    ),
    AiTools.SEND_TO_PC to BuiltInToolText(
        R.string.toolai_ai_builtin_send_to_pc_title,
        R.string.toolai_ai_builtin_send_to_pc_subtitle,
    ),
    AiTools.READ_IMAGE_TEXT to BuiltInToolText(
        R.string.toolai_ai_builtin_read_image_text_title,
        R.string.toolai_ai_builtin_read_image_text_subtitle,
    ),
    AiTools.DESCRIBE_IMAGE to BuiltInToolText(
        R.string.toolai_ai_builtin_describe_image_title,
        R.string.toolai_ai_builtin_describe_image_subtitle,
    ),
    AiTools.REVERSE_IMAGE_SEARCH to BuiltInToolText(
        R.string.toolai_ai_builtin_reverse_image_search_title,
        R.string.toolai_ai_builtin_reverse_image_search_subtitle,
    ),
)

/** Each group's heading and the "?" under it. */
internal val AiToolGroup.titleRes: Int
    @StringRes get() = when (this) {
        AiToolGroup.INFO -> R.string.toolai_ai_builtin_info_group_title
        AiToolGroup.DATA -> R.string.toolai_ai_builtin_data_group_title
        AiToolGroup.PICTURE -> R.string.toolai_ai_builtin_picture_group_title
        AiToolGroup.ACTION -> R.string.toolai_ai_builtin_action_group_title
    }

private val AiToolGroup.infoRes: Int
    @StringRes get() = when (this) {
        AiToolGroup.INFO -> R.string.toolai_ai_builtin_info_group_info
        AiToolGroup.DATA -> R.string.toolai_ai_builtin_data_group_info
        AiToolGroup.PICTURE -> R.string.toolai_ai_builtin_picture_group_info
        AiToolGroup.ACTION -> R.string.toolai_ai_builtin_action_group_info
    }

/**
 * The keyboard's own tools a model may use (#698), each with the same Run /
 * Ask / Off an MCP server's tools have, grouped by what they touch.
 */
@Composable
internal fun AiBuiltInToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val searchReady = settings.watch { hasSearchKey(it) }
    val kdeOn = settings.watch { it.kdeConnect.enabled }
    val haOn = settings.watch { it.homeAssistant.enabled && it.homes().isNotEmpty() }
    val locationBuilt = remember { LocationAccess.declared(context) }
    val meaningOn = settings.watch { it.semanticSearch.enabled }
    val meaningStates by SemanticDownloads.states.collectAsState()
    val meaningReady = remember(meaningOn, meaningStates) { meaningOn && SemanticModels.SUPPORTED && Embeddings.available(context) }
    val calendarAddOn = settings.watch { s ->
        AiTools.builtIn(AiTools.ADD_CALENDAR_EVENT)?.let { AiTools.policyOf(it, s.ai.toolPolicies) } != McpToolPolicy.OFF
    }
    for (group in AiToolGroup.entries) {
        val tools = AiTools.builtIns.filter { it.group == group && it.name in builtInToolText }
        if (tools.isEmpty()) continue
        SettingsGroup(stringResource(group.titleRes), info = stringResource(group.infoRes)) {
            for (tool in tools) {
                // The same test the runner makes: a tool with nothing behind
                // it is not offered, so it gets no row either.
                val shown = when (tool.name) {
                    AiTools.IMAGE_SEARCH -> searchReady
                    AiTools.SEND_TO_PC -> kdeOn
                    AiTools.HOME_ASSISTANT, AiTools.HOME_ASSISTANT_STATES -> haOn
                    AiTools.CURRENT_LOCATION -> locationBuilt
                    AiTools.SEARCH_CLIPBOARD -> meaningReady
                    else -> true
                }
                item(visible = shown, key = tool.name) {
                    val policy = settings.watch { AiTools.policyOf(tool, it.ai.toolPolicies) }
                    BuiltInToolRow(tool, policy) { picked ->
                        scope.launch { repository.setAiToolPolicy(tool.name, picked) }
                    }
                }
                if (tool.name == AiTools.ADD_CALENDAR_EVENT) {
                    item(visible = calendarAddOn, key = "calendar_mode") {
                        CalendarModeRows(repository, settings)
                    }
                }
            }
        }
    }
}

/**
 * How the model's calendar adds land (#698): in the calendar app's own editor
 * for the user to save, or straight in the calendar. The second asks for
 * WRITE_CALENDAR, through the disclosure, and then which calendar to use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarModeRows(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mode = settings.watch { it.ai.toolCalendarMode }
    val canWrite = rememberGrantState { DeviceCalendar.canWrite(it) }
    var granted by remember { mutableStateOf(false) }
    val request = rememberDisclosedPermissionRequest(PermissionDisclosures.CALENDAR_WRITE) { granted = true }
    val writable = canWrite || granted
    WmRow(
        title = stringResource(R.string.toolai_ai_builtin_calendar_mode_title),
        icon = SettingsRowIcons[R.string.toolai_ai_builtin_calendar_mode_title],
        highlightKey = R.string.toolai_ai_builtin_calendar_mode_title,
        supporting = {
            Column {
                Text(stringResource(R.string.toolai_ai_builtin_calendar_mode_subtitle))
                val modes = AiCalendarMode.entries
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    modes.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = mode == choice,
                            onClick = {
                                scope.launch { repository.setAiToolCalendarMode(choice) }
                                if (choice == AiCalendarMode.DIRECT && !writable) request()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            label = { Text(stringResource(choice.labelRes), maxLines = 1) },
                        )
                    }
                }
                if (mode == AiCalendarMode.DIRECT && !writable) {
                    TextButton(onClick = request) {
                        Text(stringResource(R.string.toolai_ai_builtin_calendar_allow_action))
                    }
                }
                if (mode == AiCalendarMode.DIRECT && writable) {
                    val calendars = remember(writable) { DeviceCalendar.writableCalendars(context) }
                    val chosen = settings.watch { it.ai.toolCalendarId }
                    val shownId = calendars.firstOrNull { it.id == chosen }?.id ?: calendars.firstOrNull()?.id
                    if (calendars.isEmpty()) {
                        Text(
                            stringResource(R.string.toolai_ai_builtin_calendar_none),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            for (calendar in calendars) {
                                FilterChip(
                                    selected = calendar.id == shownId,
                                    onClick = { scope.launch { repository.setAiToolCalendarId(calendar.id) } },
                                    label = { Text(calendar.name.ifBlank { calendar.account }, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private val AiCalendarMode.labelRes: Int
    @StringRes get() = when (this) {
        AiCalendarMode.OPEN_APP -> R.string.toolai_ai_builtin_calendar_mode_open_label
        AiCalendarMode.DIRECT -> R.string.toolai_ai_builtin_calendar_mode_direct_label
    }

/** How many of the keyboard's own tools a model is offered: the count on the row that opens the screen. */
internal fun builtInToolsOn(policies: Map<String, McpToolPolicy>): Int =
    AiTools.builtIns.count { AiTools.policyOf(it, policies) != McpToolPolicy.OFF }

@Composable
private fun BuiltInToolRow(tool: AiBuiltInTool, policy: McpToolPolicy, onPick: (McpToolPolicy) -> Unit) {
    val text = builtInToolText.getValue(tool.name)
    WmRow(
        title = stringResource(text.title),
        highlightKey = text.title,
        supporting = {
            Column {
                Text(stringResource(text.subtitle))
                ToolPolicyChoices(policy, onPick, Modifier.padding(top = 8.dp))
            }
        },
    )
}
