package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.aimemory.AiMemory
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryOutcome
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryStore
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import com.wasimaster.wmkeyboard.ime.aimemory.AiMemoryRecall
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Settings › AI › Memory: the two switches over the AI memory, and every fact
 * in it, to read, edit, delete and add to.
 *
 * The list is the whole of the memory. Nothing a model is handed comes from
 * anywhere else, so what this screen shows is exactly what the AI knows about
 * the user from earlier chats.
 *
 * Every change here drops the lists the open chats picked
 * ([AiChatController.forgetMemoryTails]), so an edit reaches the next message
 * of a conversation already going.
 */
@Composable
internal fun AiMemoryScreen(repository: SettingsRepository, settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null before the first unlock, which the settings app hardly ever sees.
    val store = remember { AiMemoryStore.shared(context) }
    val revision by remember(store) { store?.revision ?: MutableStateFlow(0L) }.collectAsState()
    val memories = remember(revision) { store?.items().orEmpty().sortedByDescending { it.updatedAt } }
    var editing by remember { mutableStateOf<Editing?>(null) }

    val on = settings.watch { it.ai.memory.enabled }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.toolai_memory_enabled_title,
                stringResource(R.string.toolai_memory_enabled_subtitle),
                on,
                info = stringResource(R.string.toolai_memory_enabled_info),
                default = SettingsDefaults.ai.memory.enabled,
            ) { scope.launch { repository.setAiMemoryEnabled(it) } }
        }
        item(visible = on && !OfflineBuild.isThisBuild) {
            ToggleSetting(
                R.string.toolai_memory_cloud_title,
                stringResource(R.string.toolai_memory_cloud_subtitle),
                settings.watch { it.ai.memory.cloud },
                info = stringResource(R.string.toolai_memory_cloud_info),
                default = SettingsDefaults.ai.memory.cloud,
            ) { scope.launch { repository.setAiMemoryCloud(it) } }
        }
    }

    fun changed() {
        AiChatController.forgetMemoryTails()
    }

    SettingsGroup(
        stringResource(R.string.toolai_memory_list_title),
        info = pluralStringResource(R.plurals.toolai_memory_count, memories.size, memories.size),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.toolai_memory_add_title),
                subtitle = stringResource(R.string.toolai_memory_add_subtitle),
                enabled = store != null,
                onClick = { editing = Editing(null) },
            )
        }
        if (memories.isEmpty()) {
            item {
                CaptionText(stringResource(R.string.toolai_memory_empty))
            }
        }
        for (memory in memories) {
            item(key = memory.id) {
                MemoryRow(
                    memory = memory,
                    onEdit = { editing = Editing(memory) },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                store?.forget(memory.id)
                                store?.save()
                            }
                            changed()
                        }
                    },
                )
            }
        }
        item(visible = memories.isNotEmpty(), waitsOnSetting = false) {
            ActionRow(
                title = R.string.toolai_memory_delete_all_title,
                subtitle = stringResource(R.string.toolai_memory_delete_all_subtitle),
                action = stringResource(CommonR.string.common_delete),
                confirm = stringResource(R.string.toolai_memory_delete_all_confirm),
                lock = AppLockTargets["action_delete_ai_memory"],
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        store?.clear()
                        store?.save()
                        // The vectors of the facts go with them.
                        AiMemoryRecall.dropIndex(context)
                    }
                    changed()
                }
            }
        }
    }

    editing?.let { current ->
        MemoryDialog(
            memory = current.memory,
            onDismiss = { editing = null },
            onSave = { text, category ->
                val target = store ?: return@MemoryDialog AiMemoryOutcome.Refused(AiMemoryOutcome.Reason.UNREADABLE)
                val now = System.currentTimeMillis()
                val outcome = if (current.memory == null) {
                    target.remember(text, now, source = AiMemoryStore.SOURCE_MANUAL, category = category)
                } else {
                    target.edit(current.memory.id, text, category, now)
                }
                if (outcome is AiMemoryOutcome.Added || outcome is AiMemoryOutcome.Updated) {
                    scope.launch {
                        withContext(Dispatchers.IO) { target.save() }
                        changed()
                    }
                }
                outcome
            },
        )
    }
}

/** The dialog open on the screen: a new memory when [memory] is null. */
private class Editing(val memory: AiMemory?)

@Composable
private fun MemoryRow(memory: AiMemory, onEdit: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val source = stringResource(
        if (memory.source == AiMemoryStore.SOURCE_MANUAL) {
            R.string.toolai_memory_source_manual
        } else {
            R.string.toolai_memory_source_chat
        },
    )
    val changedOn = dateFormat.format(Date(memory.updatedAt))
    val subtitle = stringResource(R.string.toolai_memory_row_subtitle, source, changedOn)
    WmRow(
        title = memory.text,
        supporting = {
            Column {
                if (memory.category.isNotBlank()) {
                    Text(memory.category, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        swipeActions = listOf(
            SwipeAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit), onClick = onEdit),
            deleteSwipeAction(stringResource(CommonR.string.common_delete), onDelete),
        ),
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
            }
        },
        onClick = onEdit,
    )
}

/**
 * Adding or editing one fact. [onSave] answers with what the store did; the
 * dialog closes on a save and says why not otherwise, so a refused secret is
 * explained where it was typed.
 */
@Composable
private fun MemoryDialog(
    memory: AiMemory?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> AiMemoryOutcome,
) {
    var text by remember { mutableStateOf(memory?.text.orEmpty()) }
    var category by remember { mutableStateOf(memory?.category.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val secret = stringResource(R.string.toolai_memory_error_secret)
    val tooLong = stringResource(R.string.toolai_memory_error_too_long, AiMemoryText.MAX_CHARS)
    val duplicate = stringResource(R.string.toolai_memory_error_duplicate)
    val unreadable = stringResource(R.string.toolai_memory_error_unreadable)
    val full = stringResource(R.string.toolai_memory_error_full)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (memory == null) R.string.toolai_memory_dialog_add_title else R.string.toolai_memory_dialog_edit_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(AiMemoryText.MAX_CHARS + 1)
                        error = null
                    },
                    label = { Text(stringResource(R.string.toolai_memory_fact_label)) },
                    placeholder = { Text(stringResource(R.string.toolai_memory_fact_hint)) },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it.take(AiMemoryStore.MAX_CATEGORY) },
                    label = { Text(stringResource(R.string.toolai_memory_category_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    when (val outcome = onSave(text, category)) {
                        is AiMemoryOutcome.Added, is AiMemoryOutcome.Updated -> onDismiss()
                        // An edit that changed nothing is a save all the same.
                        is AiMemoryOutcome.Duplicate -> if (memory != null) onDismiss() else error = duplicate
                        is AiMemoryOutcome.Refused -> error = when (outcome.reason) {
                            AiMemoryOutcome.Reason.SECRET -> secret
                            AiMemoryOutcome.Reason.TOO_LONG -> tooLong
                            AiMemoryOutcome.Reason.FULL -> full
                            // Empty is what the disabled button stops; a memory
                            // gone while its dialog was open reads as this.
                            else -> unreadable
                        }
                    }
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
