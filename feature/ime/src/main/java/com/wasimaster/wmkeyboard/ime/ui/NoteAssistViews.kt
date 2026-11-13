package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Subject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cake
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Call
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Hub
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.NotificationsActive
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Payments
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Place
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.School
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.assist.NoteHighlightKind
import com.wasimaster.wmkeyboard.core.notes.assist.NoteReminder
import com.wasimaster.wmkeyboard.core.notes.assist.RelatedKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.NoteAssistUi
import com.wasimaster.wmkeyboard.ime.R

/**
 * The notes assistant's part of an open note (#1293), between its title and
 * its body: a suggested heading for an untitled note, the summary of a long
 * one, then rows of chips for the key details, the reminders on offer, the
 * related notes and pinned clips, and the study deck. Each part shows only
 * with its setting on and something to say; a model part that is running
 * says so with a spinner rather than holding the note back.
 */
@Composable
internal fun NoteAssistSection(state: KeyboardUiState, note: Note, assist: NoteAssistUi, callbacks: NotesCallbacks) {
    val kb = LocalKbTheme.current
    val settings = state.settings.notes.assist
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (settings.headings && note.title.isBlank()) {
            when {
                assist.headingBusy -> AssistBusyLine(stringResource(R.string.ime_notes_assist_heading_busy))
                assist.heading != null -> HeadingSuggestion(assist.heading, onUse = { callbacks.onAssistHeading(note.id) }) {
                    callbacks.onAssistHeadingDismiss(note.id)
                }
            }
        }
        if (settings.summaries && (assist.summary != null || assist.summaryBusy || (assist.wantsSummary && assist.modelReady))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.weight(1f)) { ShelfLabel(Icons.AutoMirrored.Outlined.Subject, stringResource(R.string.ime_notes_assist_summary_label)) }
                if (!assist.summaryBusy && assist.modelReady && assist.wantsSummary) {
                    AssistPill(
                        label = stringResource(if (assist.summary == null) R.string.ime_notes_assist_summarize_action else R.string.ime_notes_assist_summarize_again_action),
                        icon = if (assist.summary == null) Icons.Outlined.AutoAwesome else Icons.Outlined.Refresh,
                    ) { callbacks.onAssistSummarize(note.id) }
                }
            }
            when {
                assist.summaryBusy -> AssistBusyLine(stringResource(R.string.ime_notes_assist_summary_busy))
                assist.summary != null -> Text(
                    assist.summary,
                    color = kb.modifierKeyText.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .clip(kb.chipShape())
                        .background(kb.accent.copy(alpha = if (kb.dark) 0.12f else 0.08f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            assist.error?.let { Text(it, color = kb.secondaryText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (settings.reminders && assist.reminders.isNotEmpty()) {
            AssistRow(Icons.Outlined.NotificationsActive, stringResource(R.string.ime_notes_assist_reminders_label)) {
                for (reminder in assist.reminders) {
                    AssistPill(
                        label = stringResource(R.string.ime_notes_assist_remind_chip, reminder.title, reminderWhen(reminder)),
                        icon = if (reminder.kind == NoteHighlightKind.BIRTHDAY) Icons.Outlined.Cake else Icons.Outlined.Event,
                        strong = true,
                    ) { callbacks.onAssistRemind(reminder) }
                }
            }
        }
        if (settings.highlights && assist.highlights.isNotEmpty()) {
            AssistRow(Icons.Outlined.Bolt, stringResource(R.string.ime_notes_assist_details_label)) {
                for (detail in assist.highlights) {
                    AssistPill(
                        label = detail.text,
                        icon = highlightIcon(detail.kind),
                        tint = if (detail.kind == NoteHighlightKind.DEADLINE) DEADLINE_RED else null,
                    ) { callbacks.onAssistDetail(detail) }
                }
            }
        }
        if (settings.related && assist.related.isNotEmpty()) {
            AssistRow(Icons.Outlined.Hub, stringResource(R.string.ime_notes_assist_related_label)) {
                for (hit in assist.related) {
                    val untitled = stringResource(R.string.ime_notes_untitled)
                    AssistPill(
                        label = hit.title.ifBlank { untitled },
                        icon = if (hit.kind == RelatedKind.CLIP) Icons.Outlined.PushPin else Icons.Outlined.StickyNote2,
                    ) { callbacks.onAssistRelated(hit) }
                }
            }
        }
        if (settings.decks && (assist.deckCards > 0 || assist.modelReady) && note.body.isNotBlank()) {
            Row(Modifier.padding(top = 6.dp)) {
                AssistPill(
                    label = if (assist.deckCards > 0) {
                        pluralStringResource(R.plurals.ime_notes_assist_deck_action_count, assist.deckCards, assist.deckCards)
                    } else {
                        stringResource(R.string.ime_notes_assist_deck_action)
                    },
                    icon = Icons.Outlined.School,
                ) { callbacks.onAssistDeck(note.id) }
            }
        }
    }
}

/** "Heading: …" with Use and a ✕, under the title that is still the first line. */
@Composable
private fun HeadingSuggestion(heading: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(kb.chipShape())
            .background(kb.accent.copy(alpha = if (kb.dark) 0.14f else 0.10f))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = kb.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.ime_notes_assist_heading_suggestion, heading),
            color = kb.modifierKeyText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AssistPill(stringResource(R.string.ime_notes_assist_heading_use_action), strong = true, onClick = onUse)
        Icon(
            Icons.Outlined.Close,
            contentDescription = stringResource(R.string.ime_notes_assist_heading_dismiss_desc),
            tint = kb.secondaryText,
            modifier = Modifier.padding(start = 2.dp).size(28.dp).clip(kb.chipShape()).clickable(onClick = onDismiss).padding(7.dp),
        )
    }
}

/** A section label over one row of chips scrolling sideways. */
@Composable
private fun AssistRow(icon: ImageVector, label: String, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        ShelfLabel(icon, label)
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { chips() }
    }
}

/** A small chip with an icon in front: the assistant's own, smaller than the panel's action chips. */
@Composable
private fun AssistPill(
    label: String,
    icon: ImageVector? = null,
    strong: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(shape)
            .background(if (strong) kb.accent.copy(alpha = if (kb.dark) 0.24f else 0.16f) else kb.chip)
            .chipBorder(kb, shape)
            .clickable {
                feedback()
                onClick()
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint ?: kb.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(label, color = tint ?: kb.modifierKeyText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A model part at work: a small spinner and what it is doing. */
@Composable
private fun AssistBusyLine(text: String) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(color = kb.accent, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, color = kb.secondaryText, fontSize = 11.sp)
    }
}

/** When a reminder is, as the phone writes dates: "Fri, 21 Nov", with the time when it has one. */
@Composable
private fun reminderWhen(reminder: NoteReminder): String {
    val context = LocalContext.current
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    if (!reminder.allDay) flags = flags or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, reminder.startMillis, flags)
}

private fun highlightIcon(kind: NoteHighlightKind): ImageVector = when (kind) {
    NoteHighlightKind.DEADLINE -> Icons.Outlined.Schedule
    NoteHighlightKind.BIRTHDAY -> Icons.Outlined.Cake
    NoteHighlightKind.DATE -> Icons.Outlined.Event
    NoteHighlightKind.AMOUNT -> Icons.Outlined.Payments
    NoteHighlightKind.PHONE -> Icons.Outlined.Call
    NoteHighlightKind.EMAIL -> Icons.Outlined.Mail
    NoteHighlightKind.LINK -> Icons.Outlined.Link
    NoteHighlightKind.PLACE -> Icons.Outlined.Place
}

/** The deadline chip's colour: the tag palette's red, which reads on light and dark themes. */
private val DEADLINE_RED = Color(0xFFEF5350)
