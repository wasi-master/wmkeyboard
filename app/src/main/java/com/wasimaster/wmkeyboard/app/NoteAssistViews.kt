package com.wasimaster.wmkeyboard.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cake
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Call
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Place
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistCache
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistPrompts
import com.wasimaster.wmkeyboard.core.notes.assist.NoteAssistRules
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlight
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlightKind
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlights
import com.wasimaster.wmkeyboard.core.notes.assist.NoteRelated
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminder
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminders
import com.wasimaster.wmkeyboard.core.notes.assist.NoteStudyCards
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedHit
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedKind
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedSource
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.NotesAssistSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import com.wasimaster.wmkeyboard.core.vocab.FlashcardImport
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings screen of the on-device models, where one is downloaded. */
private const val AI_ROUTE = "ai"

// ---- settings ----

/**
 * The notes assistant's settings (#1293), on the Notes tool's page. One
 * switch over all of it, off until the user turns it on; its parts show
 * only while it is on. The parts that need a model say so, and link to
 * where one is downloaded, while none is installed.
 */
@Composable
internal fun NotesAssistSettingsGroup(repository: SettingsRepository, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val assist = settings.watch { it.notes.assist }
    val localModelId = settings.watch { it.ai.localModelId }
    val d = SettingsDefaults.notes.assist
    val modelReady = remember(localModelId) { noteModel(context, localModelId) != null }
    val change: (((NotesAssistSettings) -> NotesAssistSettings)) -> Unit = { edit -> scope.launch { repository.updateNotesAssist(edit) } }
    val needsModel = DisabledReason(stringResource(R.string.tooldetail_notes_assist_needs_model), route = AI_ROUTE)
    SettingsGroup(stringResource(R.string.tooldetail_notes_assist_group)) {
        item {
            ToggleSetting(
                R.string.tooldetail_notes_assist_title,
                stringResource(R.string.tooldetail_notes_assist_subtitle),
                assist.enabled,
                info = stringResource(R.string.tooldetail_notes_assist_info),
                default = d.enabled,
            ) { on -> change { it.copy(enabled = on) } }
        }
        if (assist.enabled) {
            if (!modelReady) {
                item {
                    NavRow(
                        R.string.tooldetail_notes_assist_model_title,
                        subtitle = stringResource(R.string.tooldetail_notes_assist_model_subtitle),
                        route = AI_ROUTE,
                    ) { onNavigate(AI_ROUTE) }
                }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_headings_title,
                    stringResource(R.string.tooldetail_notes_assist_headings_subtitle),
                    assist.headings && modelReady,
                    enabled = modelReady,
                    disabledReason = needsModel,
                    default = d.headings,
                ) { on -> change { it.copy(headings = on) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_summaries_title,
                    stringResource(R.string.tooldetail_notes_assist_summaries_subtitle),
                    assist.summaries && modelReady,
                    enabled = modelReady,
                    disabledReason = needsModel,
                    default = d.summaries,
                ) { on -> change { it.copy(summaries = on) } }
            }
            if (assist.summaries && modelReady) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_notes_assist_summary_words_title,
                        subtitle = stringResource(R.string.tooldetail_notes_assist_summary_words_subtitle),
                        options = NoteAssistPrompts.SUMMARY_WORD_CHOICES.map { it to pluralStringResource(R.plurals.tooldetail_notes_assist_words, it, it) },
                        selected = assist.summaryWords.let { words -> NoteAssistPrompts.SUMMARY_WORD_CHOICES.minByOrNull { kotlin.math.abs(it - words) } ?: words },
                        default = d.summaryWords,
                    ) { words -> change { it.copy(summaryWords = words) } }
                }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_highlights_title,
                    stringResource(R.string.tooldetail_notes_assist_highlights_subtitle),
                    assist.highlights,
                    default = d.highlights,
                ) { on -> change { it.copy(highlights = on) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_reminders_title,
                    stringResource(R.string.tooldetail_notes_assist_reminders_subtitle),
                    assist.reminders,
                    default = d.reminders,
                ) { on -> change { it.copy(reminders = on) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_related_title,
                    stringResource(R.string.tooldetail_notes_assist_related_subtitle),
                    assist.related,
                    info = stringResource(R.string.tooldetail_notes_assist_related_info),
                    default = d.related,
                ) { on -> change { it.copy(related = on) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_notes_assist_decks_title,
                    stringResource(R.string.tooldetail_notes_assist_decks_subtitle),
                    assist.decks,
                    default = d.decks,
                ) { on -> change { it.copy(decks = on) } }
            }
        }
    }
    // Off forgets what the model wrote: the answers are copies of the notes.
    LaunchedEffect(assist.enabled) {
        if (!assist.enabled) {
            withContext(Dispatchers.IO) { NoteAssistCache(NoteAssistCache.fileIn(context.noBackupFilesDir)).clear() }
        }
    }
}

/** The on-device model the assistant runs: the AI tool's pick, or the only one on the phone; null for none. */
internal fun noteModel(context: Context, localModelId: String): File? =
    LocalLlmStore.selectedModelFile(context.filesDir, localModelId)
        ?: LocalLlmStore.soleDownloadedId(context.filesDir)?.let { LocalLlmStore.selectedModelFile(context.filesDir, it) }

// ---- the editor's card ----

/** What the rules found in the note, worked out off the main thread. */
private class RuleFindings(
    val highlights: List<NoteHighlight> = emptyList(),
    val reminders: List<NoteReminder> = emptyList(),
    val related: List<RelatedHit> = emptyList(),
    val cards: List<FlashcardImport.Card> = emptyList(),
)

/**
 * The notes assistant over the note being edited in the app (#1293): the
 * heading it suggests, the summary of a long note, the key details, the
 * reminders on offer, the related notes and pinned clips, and the study
 * deck. The model parts run once when the note opens, if a model is
 * installed and nothing is kept for this text, and again on request; the
 * rules re-run as the text changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteAssistCard(
    assist: NotesAssistSettings,
    ai: AiSettings,
    state: NotesScreenState,
    noteId: Long,
    title: String,
    body: String,
    updatedAt: Long,
    locked: Boolean,
    onUseHeading: (String) -> Unit,
    onOpenNote: (Long) -> Unit,
    onNavigate: (String) -> Unit,
) {
    if (!assist.enabled || locked) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember { NoteAssistCache(NoteAssistCache.fileIn(context.noBackupFilesDir)) }
    val model = remember(ai.localModelId) { noteModel(context, ai.localModelId) }
    // The note as the model and the rules read it: the draft, not the stored copy.
    val note = Note(id = noteId, body = body, title = title, updatedAt = updatedAt)
    var heading by remember(noteId) { mutableStateOf<String?>(null) }
    var summary by remember(noteId) { mutableStateOf<String?>(null) }
    var headingBusy by remember { mutableStateOf(false) }
    var summaryBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deckOpen by remember { mutableStateOf(false) }

    val findings by produceState(RuleFindings(), body, title, assist, state.active) {
        // The typing settles before the rules run again.
        delay(RULES_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { ruleFindings(context, note, assist, state) }
    }

    /** Asks the model for what the note still needs; [forceSummary] asks again for a kept one. */
    fun runModel(forceSummary: Boolean, forceHeading: Boolean = false) {
        val file = model ?: return
        val current = Note(id = noteId, body = body, title = title, updatedAt = updatedAt)
        val wantHeading = assist.headings && NoteAssistRules.wantsHeading(current) &&
            (forceHeading || (cache.heading(current) == null && !cache.headingDismissed(current)))
        val wantSummary = assist.summaries && NoteAssistRules.wantsSummary(current, assist.summaryWords) &&
            (forceSummary || cache.summary(current) == null)
        if (!wantHeading && !wantSummary) return
        headingBusy = wantHeading
        summaryBusy = wantSummary
        error = null
        scope.launch {
            val input = NoteAssistPrompts.noteInput(current.title, current.body)
            try {
                if (wantHeading) {
                    heading = withContext(Dispatchers.IO) {
                        LocalLlmEngine.generate(context.applicationContext, file, ai.localBackend, ai.localContextTokens, NoteAssistPrompts.headingSystem(), input)
                    }.let(NoteAssistPrompts::cleanHeading)?.also { cache.putHeading(current, it) }
                    headingBusy = false
                }
                if (wantSummary) {
                    val written = withContext(Dispatchers.IO) {
                        LocalLlmEngine.generate(context.applicationContext, file, ai.localBackend, ai.localContextTokens, NoteAssistPrompts.summarySystem(), input)
                    }.let(NoteAssistPrompts::cleanSummary)
                    if (written != null) {
                        summary = written
                        cache.putSummary(current, written)
                    }
                    summaryBusy = false
                }
                withContext(Dispatchers.IO) { cache.save() }
            } catch (e: IOException) {
                error = e.message
            } finally {
                headingBusy = false
                summaryBusy = false
            }
        }
    }

    // What is kept for this text shows at once; what is missing is asked for once, on open.
    LaunchedEffect(noteId) {
        withContext(Dispatchers.IO) { cache.reloadIfChanged() }
        if (assist.headings && title.isBlank()) heading = cache.heading(note)
        if (assist.summaries) summary = cache.summary(note)
        runModel(forceSummary = false)
    }

    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (assist.headings && title.isBlank()) {
                when {
                    headingBusy -> BusyLine(stringResource(R.string.notes_assist_heading_busy))
                    heading != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.notes_assist_heading_suggestion, heading.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { heading?.let(onUseHeading) }) { Text(stringResource(R.string.notes_assist_heading_use_action)) }
                        TextButton(onClick = {
                            cache.dismissHeading(note)
                            heading = null
                            scope.launch(Dispatchers.IO) { cache.save() }
                        }) { Text(stringResource(R.string.notes_assist_heading_dismiss_action)) }
                    }
                    model != null && NoteAssistRules.wantsHeading(note) -> AssistAction(stringResource(R.string.notes_assist_heading_action), Icons.Outlined.AutoAwesome) {
                        runModel(forceSummary = false, forceHeading = true)
                    }
                }
            }
            val wantsSummary = assist.summaries && NoteAssistRules.wantsSummary(note, assist.summaryWords)
            if (wantsSummary || summary != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.notes_assist_summary_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (model != null && !summaryBusy && wantsSummary) {
                        TextButton(onClick = { runModel(forceSummary = true) }) {
                            Icon(if (summary == null) Icons.Outlined.AutoAwesome else Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (summary == null) R.string.notes_assist_summarize_action else R.string.notes_assist_summarize_again_action))
                        }
                    }
                }
                when {
                    summaryBusy -> BusyLine(stringResource(R.string.notes_assist_summary_busy))
                    summary != null -> Text(summary.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    model == null -> Text(
                        stringResource(R.string.notes_assist_needs_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (assist.reminders && findings.reminders.isNotEmpty()) {
                SectionLabel(stringResource(R.string.notes_assist_reminders_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (reminder in findings.reminders) {
                        AssistChip(
                            onClick = { remind(context, reminder) },
                            label = { Text(stringResource(R.string.notes_assist_remind_chip, reminder.title, reminderWhen(context, reminder)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(if (reminder.kind == NoteHighlightKind.BIRTHDAY) Icons.Outlined.Cake else Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                            },
                        )
                    }
                }
            }
            if (assist.highlights && findings.highlights.isNotEmpty()) {
                SectionLabel(stringResource(R.string.notes_assist_details_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (detail in findings.highlights) {
                        AssistChip(
                            onClick = { openDetail(context, detail) },
                            label = { Text(detail.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    detailIcon(detail.kind),
                                    contentDescription = null,
                                    tint = if (detail.kind == NoteHighlightKind.DEADLINE) DEADLINE_RED else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
            if (assist.related && findings.related.isNotEmpty()) {
                SectionLabel(stringResource(R.string.notes_assist_related_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (hit in findings.related) {
                        AssistChip(
                            onClick = {
                                when (hit.kind) {
                                    RelatedKind.NOTE -> onOpenNote(hit.id)
                                    RelatedKind.CLIP -> copyText(context, hit.title)
                                }
                            },
                            label = { Text(hit.title.ifBlank { stringResource(R.string.notes_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(
                                    if (hit.kind == RelatedKind.CLIP) Icons.Outlined.PushPin else Icons.Outlined.StickyNote2,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }
            }
            if (assist.decks && body.isNotBlank() && (findings.cards.isNotEmpty() || model != null)) {
                AssistAction(
                    if (findings.cards.isNotEmpty()) {
                        pluralStringResource(R.plurals.notes_assist_deck_action_count, findings.cards.size, findings.cards.size)
                    } else {
                        stringResource(R.string.notes_assist_deck_action)
                    },
                    Icons.Outlined.School,
                ) { deckOpen = true }
            }
        }
    }

    if (deckOpen) {
        StudyDeckDialog(
            note = note,
            ruleCards = findings.cards,
            model = model,
            ai = ai,
            onDismiss = { deckOpen = false },
            onNavigate = onNavigate,
        )
    }
}

/**
 * The deck before it is made: how many cards, the first few, and Make. With
 * a model on the phone and few cards in the note's own words, "Ask the
 * model" writes cards out of its prose instead.
 */
@Composable
private fun StudyDeckDialog(
    note: Note,
    ruleCards: List<FlashcardImport.Card>,
    model: File?,
    ai: AiSettings,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf(ruleCards) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<String?>(null) }
    val name = note.displayTitle().ifEmpty { stringResource(R.string.notes_untitled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_assist_deck_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    made != null -> Text(made.orEmpty())
                    busy -> BusyLine(stringResource(R.string.notes_assist_deck_busy))
                    cards.isEmpty() -> Text(stringResource(R.string.notes_assist_deck_none))
                    else -> {
                        Text(pluralStringResource(R.plurals.notes_assist_deck_count, cards.size, cards.size, name))
                        for (card in cards.take(DECK_PREVIEW)) {
                            Text(
                                stringResource(R.string.notes_assist_deck_card, card.term, card.definition),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (made == null && !busy && model != null && cards.size < MODEL_DECK_BELOW) {
                    TextButton(onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val answer = withContext(Dispatchers.IO) {
                                    LocalLlmEngine.generate(
                                        context.applicationContext, model, ai.localBackend, ai.localContextTokens,
                                        NoteAssistPrompts.deckSystem(), NoteAssistPrompts.noteInput(note.title, note.body),
                                    )
                                }
                                val written = NoteStudyCards.fromModel(AiThinking.stripped(answer))
                                if (written.size > cards.size) cards = written
                            } catch (e: IOException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(stringResource(R.string.notes_assist_deck_model_action)) }
                }
            }
        },
        confirmButton = {
            if (made != null) {
                TextButton(onClick = {
                    onDismiss()
                    onNavigate(VOCAB_LISTS_ROUTE)
                }) { Text(stringResource(R.string.notes_assist_deck_open_action)) }
            } else {
                TextButton(enabled = cards.isNotEmpty() && !busy, onClick = {
                    scope.launch {
                        val pack = NoteStudyCards.toPack(cards, name)
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { VocabPacks.write(context.filesDir, pack, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) }.isSuccess
                        }
                        if (ok) {
                            made = context.resources.getQuantityString(R.plurals.notes_assist_deck_made, pack.words.size, pack.words.size, pack.meta.name)
                        } else {
                            error = context.getString(R.string.notes_assist_deck_error)
                        }
                    }
                }) { Text(stringResource(R.string.notes_assist_deck_make_action)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

/** The rules' half, for [note]: key details, reminders, related items and the cards it spells out. */
private fun ruleFindings(context: Context, note: Note, assist: NotesAssistSettings, state: NotesScreenState): RuleFindings {
    val zone = TimeZone.getDefault()
    val now = System.currentTimeMillis()
    val anchor = note.updatedAt.takeIf { it > 0 } ?: now
    val found = if (assist.highlights || assist.reminders) NoteHighlights.find(note.body, anchor, zone, Locale.getDefault()) else emptyList()
    val related = if (assist.related && note.body.isNotBlank()) {
        val notes = state.active.filter { NoteAssistRules.readable(it) && it.id != note.id }
            .map { RelatedSource(RelatedKind.NOTE, it.id, it.displayTitle(), it.body) }
        val clips = pinnedClips(context).map { RelatedSource(RelatedKind.CLIP, it.first, it.second.lineSequence().firstOrNull().orEmpty().take(CLIP_TITLE), it.second) }
        NoteRelated.related(RelatedSource(RelatedKind.NOTE, note.id, note.displayTitle(), note.body), notes + clips)
    } else {
        emptyList()
    }
    return RuleFindings(
        highlights = if (assist.highlights) found else emptyList(),
        reminders = if (assist.reminders) NoteReminders.upcoming(found, now, zone) else emptyList(),
        related = related,
        cards = if (assist.decks) NoteStudyCards.fromText(note.body) else emptyList(),
    )
}

/**
 * The pinned text clips, read from the keyboard's history file without
 * writing to it: their ids and texts. A clip masked as a secret is left out.
 */
private fun pinnedClips(context: Context): List<Pair<Long, String>> =
    runCatching {
        ClipboardStore(File(context.filesDir, CLIPBOARD_FILE), expiryMillis = 0L)
            .items()
            .filter { it.pinned && !it.sensitive && it.kind.isTextual && it.text.isNotBlank() }
            .map { it.id to it.text }
    }.getOrDefault(emptyList())

/** A reminder handed to the calendar app's new-event screen, yearly for a birthday. */
private fun remind(context: Context, reminder: NoteReminder) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, reminder.startMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, reminder.endMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, reminder.allDay)
        .putExtra(CalendarContract.Events.TITLE, reminder.title)
    reminder.rrule?.let { intent.putExtra(CalendarContract.Events.RRULE, it) }
    start(context, intent)
}

/** A key detail tapped: a number dials, an address writes, a link opens, the rest is copied. */
private fun openDetail(context: Context, detail: NoteHighlight) {
    when (detail.kind) {
        NoteHighlightKind.PHONE -> start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(detail.text))))
        NoteHighlightKind.EMAIL -> start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + detail.text)))
        NoteHighlightKind.LINK -> {
            val url = if (detail.text.contains("://")) detail.text else "https://" + detail.text
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        else -> copyText(context, detail.text)
    }
}

private fun start(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.notes_assist_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
    Toast.makeText(context, R.string.notes_assist_copied, Toast.LENGTH_SHORT).show()
}

private fun reminderWhen(context: Context, reminder: NoteReminder): String {
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    if (!reminder.allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, reminder.startMillis, flags)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun AssistAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun BusyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(2.dp))
}

private fun detailIcon(kind: NoteHighlightKind): ImageVector = when (kind) {
    NoteHighlightKind.DEADLINE -> Icons.Outlined.Schedule
    NoteHighlightKind.BIRTHDAY -> Icons.Outlined.Cake
    NoteHighlightKind.DATE -> Icons.Outlined.Event
    NoteHighlightKind.AMOUNT -> Icons.Outlined.Payments
    NoteHighlightKind.PHONE -> Icons.Outlined.Call
    NoteHighlightKind.EMAIL -> Icons.Outlined.Mail
    NoteHighlightKind.LINK -> Icons.Outlined.Link
    NoteHighlightKind.PLACE -> Icons.Outlined.Place
}

private val DEADLINE_RED = Color(0xFFE53935)
private const val RULES_DEBOUNCE_MS = 500L
private const val CLIP_TITLE = 60
private const val CLIPBOARD_FILE = "clipboard/history.json"
private const val DECK_PREVIEW = 5

/** Fewer cards than this in the note's own words, and the dialog offers to ask the model. */
private const val MODEL_DECK_BELOW = 5
