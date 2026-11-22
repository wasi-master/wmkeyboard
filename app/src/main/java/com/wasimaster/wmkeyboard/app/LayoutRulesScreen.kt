package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Reorder
import com.wasimaster.wmkeyboard.core.layout.CompiledSequenceRule
import com.wasimaster.wmkeyboard.core.layout.CompiledSequenceRules
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.MaxSequencePatternLength
import com.wasimaster.wmkeyboard.core.layout.SequenceRule
import com.wasimaster.wmkeyboard.core.layout.SequenceRuleError
import com.wasimaster.wmkeyboard.core.layout.SequenceRuleProblem
import com.wasimaster.wmkeyboard.core.layout.SequenceRuleSet
import com.wasimaster.wmkeyboard.core.layout.SequenceRuleSets
import com.wasimaster.wmkeyboard.core.layout.compiledSequenceRules
import com.wasimaster.wmkeyboard.core.layout.copySequenceRuleSet
import com.wasimaster.wmkeyboard.core.layout.findLayout
import com.wasimaster.wmkeyboard.core.layout.isSequenceGroupName
import com.wasimaster.wmkeyboard.core.layout.renameSequenceGroups
import com.wasimaster.wmkeyboard.core.layout.unescapeSequenceText
import com.wasimaster.wmkeyboard.core.layout.usesGroup
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/** The route of [LayoutRulesScreen] for one layout. */
internal fun layoutRulesRoute(layoutId: String): String = "keymap_rules/$layoutId"

/**
 * Layout editor › Typing rules (#1493): the layout's `sequenceRules`, its
 * `characterGroups` and the shipped rule sets it names, without the JSON.
 *
 * Saved as each change is made, the way the grid editor saves, and without the
 * repair pass: a rule left naming a group that was just deleted stays in the
 * list marked with what is wrong, for its author to fix, rather than vanishing.
 * The keyboard skips a rule that does not compile, so nothing types wrong
 * meanwhile. An edit of a shipped layout becomes the user's copy of it, as any
 * other edit does.
 */
@Composable
internal fun LayoutRulesScreen(repository: SettingsRepository, settings: LiveSettings, layoutId: String) {
    val layout = settings.watch { findLayout(it.customLayouts, layoutId) }
    if (layout == null) {
        CaptionText(stringResource(R.string.layout_rules_missing))
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groups = layout.characterGroups.orEmpty()
    val rules = layout.sequenceRules.orEmpty()
    val sets = layout.ruleSets.orEmpty()
    val compiled = remember(groups, rules, sets) { layout.compiledSequenceRules() }
    val problems = remember(groups, rules) {
        CompiledSequenceRules.compile(groups, rules).problems.associateBy { it.index }
    }

    fun save(update: (LayoutSpec) -> LayoutSpec) {
        val next = update(layout)
        scope.launch {
            repository.upsertCustomLayout(
                next.copy(
                    characterGroups = next.characterGroups?.takeIf { it.isNotEmpty() },
                    sequenceRules = next.sequenceRules?.takeIf { it.isNotEmpty() },
                    ruleSets = next.ruleSets?.takeIf { it.isNotEmpty() },
                ),
            )
        }
    }

    // The rule being edited: an index into [rules], or -1 for a new one.
    var editingRule by remember { mutableStateOf<Int?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var viewingSet by remember { mutableStateOf<String?>(null) }

    CaptionText(stringResource(R.string.layout_rules_intro))

    SettingsGroup(stringResource(R.string.layout_rules_try_group)) {
        item {
            var typed by rememberSaveable { mutableStateOf("") }
            val run = remember(typed, compiled) { compiled?.run(typed) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.layout_rules_try_label)) },
                    supportingText = { Text(stringResource(R.string.layout_rules_try_help)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (typed.isNotEmpty()) {
                    Text(
                        stringResource(R.string.layout_rules_try_result, run?.text ?: typed),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val last = run?.lastRule
                    Text(
                        if (last == null) {
                            stringResource(R.string.layout_rules_try_none)
                        } else {
                            stringResource(R.string.layout_rules_try_last, ruleText(last.rule), sourceName(last))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_rules_own_group)) {
        if (rules.isEmpty()) {
            item { CaptionText(stringResource(R.string.layout_rules_none)) }
        }
        rules.forEachIndexed { index, rule ->
            item(key = "rule-$index-${rule.pattern}") {
                val deleteLabel = stringResource(CommonR.string.common_delete)
                val editLabel = stringResource(CommonR.string.common_edit)
                val problem = problems[index]
                WmRow(
                    title = ruleText(rule),
                    subtitle = when {
                        problem != null -> problemText(problem)
                        rule.undo -> stringResource(R.string.layout_rules_undo_subtitle)
                        else -> null
                    },
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, editLabel) { editingRule = index },
                        deleteSwipeAction(deleteLabel) { save { it.copy(sequenceRules = rules.filterIndexed { i, _ -> i != index }) } },
                    ),
                    onClick = { editingRule = index },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.layout_rules_add_rule),
                icon = Icons.Outlined.Add,
                highlightKey = R.string.layout_rules_add_rule,
                onClick = { editingRule = -1 },
            )
        }
        if (rules.size > 1) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_rules_reorder),
                    subtitle = stringResource(R.string.layout_rules_reorder_subtitle),
                    icon = Icons.Outlined.Reorder,
                    highlightKey = R.string.layout_rules_reorder,
                    onClick = { reordering = true },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_rules_groups_group)) {
        if (groups.isEmpty()) {
            item { CaptionText(stringResource(R.string.layout_rules_groups_none)) }
        }
        groups.forEach { (name, items) ->
            item(key = "group-$name") {
                val deleteLabel = stringResource(CommonR.string.common_delete)
                val editLabel = stringResource(CommonR.string.common_edit)
                WmRow(
                    title = name,
                    subtitle = items.joinToString(" ") { shown(it) },
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, editLabel) { editingGroup = name },
                        deleteSwipeAction(deleteLabel) { save { it.copy(characterGroups = groups - name) } },
                    ),
                    onClick = { editingGroup = name },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.layout_rules_add_group),
                icon = Icons.Outlined.Add,
                highlightKey = R.string.layout_rules_add_group,
                onClick = { addingGroup = true },
            )
        }
    }

    val shipped = remember { SequenceRuleSets.ids().mapNotNull { SequenceRuleSets.byId(it) } }
    if (shipped.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.layout_rules_sets_group),
            info = stringResource(R.string.layout_rules_sets_info),
        ) {
            shipped.forEach { set ->
                item(key = "set-${set.id}") {
                    ToggleSetting(
                        title = set.name.ifEmpty { set.id },
                        subtitle = set.description.ifEmpty {
                            pluralStringResource(R.plurals.layout_rules_set_rules, set.sequenceRules.size, set.sequenceRules.size)
                        },
                        checked = set.id in sets,
                    ) { on -> save { it.copy(ruleSets = if (on) (sets + set.id).distinct() else sets - set.id) } }
                }
                if (set.id in sets) {
                    item(key = "set-view-${set.id}") {
                        val count = set.sequenceRules.size
                        WmRow(
                            title = stringResource(R.string.layout_rules_set_view),
                            subtitle = pluralStringResource(R.plurals.layout_rules_set_rules, count, count),
                            icon = Icons.Outlined.ContentCopy,
                            onClick = { viewingSet = set.id },
                        )
                    }
                }
            }
        }
    }

    editingRule?.let { index ->
        RuleDialog(
            initial = rules.getOrNull(index),
            groups = groups,
            onDismiss = { editingRule = null },
            onDelete = rules.getOrNull(index)?.let {
                { ->
                    editingRule = null
                    save { s -> s.copy(sequenceRules = rules.filterIndexed { i, _ -> i != index }) }
                }
            },
        ) { rule ->
            editingRule = null
            save { it.copy(sequenceRules = if (index < 0) rules + rule else rules.mapIndexed { i, r -> if (i == index) rule else r }) }
        }
    }

    if (addingGroup || editingGroup != null) {
        val name = editingGroup
        GroupDialog(
            initialName = name,
            initialItems = name?.let { groups[it] }.orEmpty(),
            taken = groups.keys - setOfNotNull(name),
            usedBy = name?.let { n -> rules.count { it.usesGroup(n) } } ?: 0,
            onDismiss = {
                addingGroup = false
                editingGroup = null
            },
            onDelete = name?.let {
                { ->
                    editingGroup = null
                    save { s -> s.copy(characterGroups = groups - it) }
                }
            },
        ) { newName, items ->
            addingGroup = false
            editingGroup = null
            save { s ->
                // A rename carries the rules that named the group along with it.
                val renamed = if (name != null && name != newName) {
                    rules.map { r ->
                        r.copy(
                            pattern = renameSequenceGroups(r.pattern, mapOf(name to newName)),
                            replace = renameSequenceGroups(r.replace, mapOf(name to newName)),
                        )
                    }
                } else {
                    rules
                }
                val nextGroups = LinkedHashMap<String, List<String>>()
                for ((k, v) in groups) nextGroups[if (k == name) newName else k] = if (k == name) items else v
                if (name == null) nextGroups[newName] = items
                s.copy(characterGroups = nextGroups, sequenceRules = renamed)
            }
        }
    }

    if (reordering) {
        ReorderDialog(
            title = stringResource(R.string.layout_rules_reorder),
            items = rules,
            label = { ruleText(it) },
            onConfirm = { ordered ->
                reordering = false
                save { it.copy(sequenceRules = ordered) }
            },
            onDismiss = { reordering = false },
        )
    }

    viewingSet?.let { id ->
        val set = shipped.firstOrNull { it.id == id }
        if (set == null) {
            viewingSet = null
        } else {
            SetDialog(set, onDismiss = { viewingSet = null }) {
                viewingSet = null
                val (mergedGroups, mergedRules) = copySequenceRuleSet(set, groups, rules)
                save { it.copy(characterGroups = mergedGroups, sequenceRules = mergedRules, ruleSets = sets - set.id) }
                android.widget.Toast.makeText(context, R.string.layout_rules_set_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** What [LayoutEditorScreens]' Typing rules row says under its title. */
@Composable
internal fun layoutRulesSummary(layout: LayoutSpec): String {
    val own = layout.sequenceRules.orEmpty().size
    val names = layout.ruleSets.orEmpty().map { id -> SequenceRuleSets.byId(id)?.name?.takeIf { it.isNotEmpty() } ?: id }
    val count = if (own > 0) pluralStringResource(R.plurals.layout_editor_rules_subtitle_count, own, own) else null
    val usesSets = if (names.isNotEmpty()) stringResource(R.string.layout_editor_rules_subtitle_sets, names.joinToString(", ")) else null
    return when {
        count != null && usesSets != null -> stringResource(R.string.layout_editor_rules_subtitle_both, count, usesSets)
        count != null -> count
        usesSets != null -> usesSets
        else -> stringResource(R.string.layout_editor_rules_subtitle_none)
    }
}

/** A rule as one line: its pattern, an arrow, its replacement, marks drawn on ◌. */
private fun ruleText(rule: SequenceRule): String = "${shown(rule.pattern)} → ${shown(rule.replace)}"

/**
 * [text] for a label: a combining mark with nothing to sit on (at the start,
 * after a space, or after a placeholder's brace) drawn on a dotted circle, so
 * ่ reads as ◌่ rather than landing on the arrow or the brace beside it.
 */
internal fun shown(text: String): String {
    if (text.isEmpty()) return "∅"
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val type = Character.getType(cp)
        val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        val prev = out.lastOrNull()
        if (mark && (prev == null || prev == ' ' || prev == '}' || prev == '{')) out.append('◌')
        out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

@Composable
private fun sourceName(rule: CompiledSequenceRule): String {
    val source = rule.source ?: return stringResource(R.string.layout_rules_source_own)
    return SequenceRuleSets.byId(source)?.name?.takeIf { it.isNotEmpty() } ?: source
}

@Composable
private fun problemText(problem: SequenceRuleProblem): String = when (problem.error) {
    SequenceRuleError.EMPTY_PATTERN -> stringResource(R.string.layout_rules_error_empty)
    SequenceRuleError.UNKNOWN_GROUP -> stringResource(R.string.layout_rules_error_unknown_group, problem.detail)
    SequenceRuleError.BAD_PLACEHOLDER -> stringResource(R.string.layout_rules_error_bad_placeholder, problem.detail)
    SequenceRuleError.PATTERN_TOO_LONG -> stringResource(R.string.layout_rules_error_too_long, MaxSequencePatternLength)
    SequenceRuleError.NO_SUCH_SLOT -> stringResource(R.string.layout_rules_error_no_slot, problem.detail)
    SequenceRuleError.UNEVEN_MAP -> stringResource(R.string.layout_rules_error_uneven_map, problem.detail)
}

@Composable
private fun RuleDialog(
    initial: SequenceRule?,
    groups: Map<String, List<String>>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SequenceRule) -> Unit,
) {
    var pattern by remember { mutableStateOf(TextFieldValue(initial?.pattern.orEmpty())) }
    var replace by remember { mutableStateOf(TextFieldValue(initial?.replace.orEmpty())) }
    var undo by remember { mutableStateOf(initial?.undo ?: false) }
    // Which field a group chip writes into: the one last focused.
    var intoReplace by remember { mutableStateOf(false) }
    val rule = SequenceRule(unescapeSequenceText(pattern.text), unescapeSequenceText(replace.text), undo)
    val problem = remember(rule, groups) { CompiledSequenceRules.compile(groups, listOf(rule)).problems.firstOrNull() }

    fun insert(value: TextFieldValue, piece: String): TextFieldValue {
        val start = value.selection.min
        val text = value.text.substring(0, start) + piece + value.text.substring(value.selection.max)
        return TextFieldValue(text, TextRange(start + piece.length))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.layout_rules_rule_add_title else R.string.layout_rules_rule_edit_title))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.layout_rules_pattern_label)) },
                    isError = problem != null && pattern.text.isNotEmpty(),
                    supportingText = { Text(stringResource(R.string.layout_rules_pattern_help)) },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) intoReplace = false },
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.layout_rules_replace_label)) },
                    supportingText = { Text(stringResource(R.string.layout_rules_replace_help)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).onFocusChanged { if (it.isFocused) intoReplace = true },
                )
                if (groups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.layout_rules_insert_group),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        groups.keys.forEach { name ->
                            AssistChip(
                                onClick = {
                                    if (intoReplace) replace = insert(replace, "{$name}") else pattern = insert(pattern, "{$name}")
                                },
                                label = { Text("{$name}") },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.layout_rules_undo_label), modifier = Modifier.weight(1f))
                    Switch(checked = undo, onCheckedChange = { undo = it })
                }
                if (problem != null && pattern.text.isNotEmpty()) {
                    Text(
                        problemText(problem),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(rule) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(CommonR.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun GroupDialog(
    initialName: String?,
    initialItems: List<String>,
    taken: Set<String>,
    usedBy: Int,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var items by remember { mutableStateOf(initialItems.joinToString(" ")) }
    val parsed = items.split(' ', '\t', '\n').filter { it.isNotEmpty() }.map(::unescapeSequenceText)
    val clash = name in taken
    val valid = isSequenceGroupName(name) && !clash && parsed.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initialName == null) R.string.layout_rules_group_add_title else R.string.layout_rules_group_edit_title))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.layout_rules_group_name_label)) },
                    isError = clash || (name.isNotEmpty() && !isSequenceGroupName(name)),
                    supportingText = {
                        Text(stringResource(if (clash) R.string.layout_rules_group_name_taken else R.string.layout_rules_group_name_help))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = items,
                    onValueChange = { items = it },
                    label = { Text(stringResource(R.string.layout_rules_group_items_label)) },
                    supportingText = { Text(stringResource(R.string.layout_rules_group_items_help)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                if (parsed.isNotEmpty()) {
                    Text(
                        parsed.joinToString("  ") { shown(it) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (usedBy > 0) {
                    CaptionText(pluralStringResource(R.plurals.layout_rules_group_used, usedBy, usedBy))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(name, parsed) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(CommonR.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun SetDialog(set: SequenceRuleSet, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(set.name.ifEmpty { set.id }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (set.description.isNotEmpty()) {
                    Text(set.description, style = MaterialTheme.typography.bodyMedium)
                }
                set.characterGroups.forEach { (name, items) ->
                    Text(
                        "{$name}  " + items.joinToString(" ") { shown(it) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                set.sequenceRules.forEach { rule ->
                    Text(ruleText(rule), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringResource(R.string.layout_rules_set_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}
