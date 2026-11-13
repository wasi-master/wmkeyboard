package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.KeyRuleSheet
import com.wasimaster.wmkeyboard.core.theme.RuleSheetIssue
import com.wasimaster.wmkeyboard.core.theme.RuleSheetResult
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.findThemeSpec
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The rule sheet editor for one theme look. */
internal fun themeRulesRoute(themeId: String): String = "theme_rules/$themeId"

/**
 * A theme's own rule sheet, as text: FlorisBoard-style rules naming keys by
 * code, output, style id or class (see [KeyRuleSheet]).
 *
 * The shared code editor with the JSON highlighting the layout editor uses,
 * and under it, as the author types, how many rules land and why each of the
 * others does not. Draft plus an explicit Apply, like the layout's JSON: a
 * half-typed sheet is not one, and the keyboard should not flicker through
 * every keystroke of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeRuleSheetScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    themeId: String,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.theme_rule_sheet_title)
    val theme = settings.watch { s -> findThemeSpec(themeId, s.customThemes)?.takeIf { s.customThemes.findThemeFamily(themeId) != null } }
    if (theme == null) {
        MissingJsonDocument(title, stringResource(R.string.theme_rule_sheet_missing_message), onBack)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = rememberCodeEditorState(themeId, keepHistory = true) {
        theme.ruleSheet?.takeIf { it.isNotBlank() } ?: EMPTY_SHEET
    }
    val colors = rememberCodeColors()
    var applying by remember { mutableStateOf(false) }
    val text = editor.text
    // Compiled against the stored theme as the author types, after a pause, so
    // the list under the editor always speaks about the text on screen.
    val result by produceState<RuleSheetResult?>(null, text, theme) {
        delay(CHECK_PAUSE_MS)
        value = withContext(Dispatchers.Default) { KeyRuleSheet.compile(theme.copy(ruleSheet = text)) }
    }
    val empty = isEmptySheet(text)
    val apply: () -> Unit = {
        if (!applying) {
            applying = true
            scope.launch {
                val stored = text.takeUnless { isEmptySheet(it) }
                val family = settings.value.customThemes.findThemeFamily(themeId)
                if (family != null) {
                    repository.upsertCustomTheme(family.replacingMember(themeId) { it.copy(ruleSheet = stored) })
                }
                applying = false
                onBack()
            }
        }
    }
    RegisterSettingsCrumb(title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { editor.undo() }, enabled = editor.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
                    }
                    IconButton(onClick = { editor.redo() }, enabled = editor.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(R.string.code_redo_desc))
                    }
                    if (empty) {
                        TextButton(onClick = { editor.replace(EXAMPLE_SHEET) }) {
                            Text(stringResource(R.string.theme_rule_sheet_example_action))
                        }
                    }
                    TextButton(onClick = apply, enabled = !applying) {
                        Text(stringResource(R.string.layout_editor_apply_action))
                    }
                },
            )
        },
    ) { padding ->
        ResizeForKeyboard()
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            CodeSurface(
                state = editor,
                language = JsonCode,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                colors = colors,
            )
            HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(ISSUES_FRACTION)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val compiled = result
                Text(
                    when {
                        empty -> stringResource(R.string.theme_rule_sheet_empty_status)
                        compiled == null -> ""
                        else -> pluralStringResource(R.plurals.theme_rule_sheet_applied_status, compiled.applied, compiled.applied)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!empty) {
                    for (issue in compiled?.issues.orEmpty()) {
                        Text(
                            ruleSheetIssueLine(context, issue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One line of the list under the editor: the rule, then why it does nothing. */
internal fun ruleSheetIssueLine(context: android.content.Context, issue: RuleSheetIssue): String = when (issue.kind) {
    RuleSheetIssue.Kind.UNREADABLE -> context.getString(R.string.theme_rule_sheet_issue_unreadable)
    RuleSheetIssue.Kind.TOO_LONG -> context.getString(R.string.theme_rule_sheet_issue_too_long)
    RuleSheetIssue.Kind.ELEMENT -> context.getString(R.string.theme_rule_sheet_issue_element, issue.rule)
    RuleSheetIssue.Kind.STATE -> context.getString(R.string.theme_rule_sheet_issue_state, issue.rule)
    RuleSheetIssue.Kind.ATTRIBUTE -> context.getString(R.string.theme_rule_sheet_issue_attribute, issue.rule)
    RuleSheetIssue.Kind.KEY -> context.getString(R.string.theme_rule_sheet_issue_key, issue.rule)
    RuleSheetIssue.Kind.PROPERTY ->
        context.getString(R.string.theme_rule_sheet_issue_property, issue.rule, issue.properties.joinToString(", "))
}

/** Whether [text] holds no rule at all: blank, or an empty object. */
internal fun isEmptySheet(text: String): Boolean = text.filterNot { it.isWhitespace() }.let { it.isEmpty() || it == "{}" }

private const val CHECK_PAUSE_MS = 250L
private const val ISSUES_FRACTION = 0.3f

private const val EMPTY_SHEET = "{\n  \n}\n"

/** What Example fills an empty sheet with: one of each way to name a key, and a variable. */
private val EXAMPLE_SHEET = """
    {
      "@defines": {
        "--accent": "#FF5577"
      },
      "key[class=`modifier`]": {
        "background": "#33363C"
      },
      "key[code=10]": {
        "background": "var(--accent)",
        "shape": "circle()"
      },
      "key[code=10]:pressed": {
        "background": "#CC3355"
      },
      "key[output=`q`]": {
        "foreground": "var(--accent)",
        "font-weight": "bold"
      },
      "key-hint[id=`e`]": {
        "foreground": "#BBBBBB"
      }
    }
""".trimIndent() + "\n"
