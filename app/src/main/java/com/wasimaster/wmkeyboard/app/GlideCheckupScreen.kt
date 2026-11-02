package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.assistant.AssistantProposal
import com.wasimaster.wmkeyboard.app.assistant.AssistantProposals
import com.wasimaster.wmkeyboard.app.assistant.AssistantRefusal
import com.wasimaster.wmkeyboard.app.assistant.AssistantSetting
import com.wasimaster.wmkeyboard.app.assistant.CardShape
import com.wasimaster.wmkeyboard.app.assistant.ChangeValues
import com.wasimaster.wmkeyboard.app.assistant.shown
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.prediction.GlideCheckup
import com.wasimaster.wmkeyboard.core.settings.GlideVocabulary
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The glide check-up (issue #966 §5.5): what the keyboard measured about
 * this user's swipes, the one change it proposes with its expected effect,
 * and, once a change is in force, how the swipes since compare.
 *
 * The keyboard owns the evidence (`GlideCheckup` in :core:prediction) and
 * this screen reads its file; every decision goes back through a preference
 * the keyboard acts on, never by writing that file under it. A proposal is
 * an [AssistantProposal] on one of the two settings the check-up may move,
 * so Apply and Undo refuse over a value that moved by hand, the same way the
 * settings assistant's do.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun TypingGlideCheckupSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val measuring = settings.watch { it.gesture.checkupMeasure }
    val version = settings.watch { it.gesture.checkupVersion }
    val decision = settings.watch { it.gesture.checkupDecision }
    val frequencyWeight = settings.watch { it.gesture.frequencyWeight }
    var refresh by remember { mutableIntStateOf(0) }
    val report by produceState<GlideCheckup.Report?>(null, version, decision, refresh) {
        value = repository.glideCheckupReport()
    }
    // The proposal as a card, kept across the report changing under it: once
    // applied, the file no longer carries a proposal, and the card has to go
    // on saying "Applied" with its Undo.
    var change by remember { mutableStateOf<CheckupChange?>(null) }
    LaunchedEffect(report) {
        val proposal = report?.proposal ?: return@LaunchedEffect
        val shown = change
        val replace = shown == null ||
            (shown.proposal.state == AssistantProposal.State.PROPOSED && shown.checkup.candidate.id != proposal.candidate.id)
        if (replace) change = proposalCard(proposal, settings)
    }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.typing_glide_checkup_measure_title,
                stringResource(R.string.typing_glide_checkup_measure_subtitle),
                measuring,
                info = stringResource(R.string.typing_glide_checkup_measure_info),
                default = SettingsDefaults.gesture.checkupMeasure,
            ) { scope.launch { repository.setGestureCheckupMeasure(it) } }
        }
        item {
            val r = report
            WmRow(
                title = stringResource(R.string.typing_glide_checkup_measured_title),
                subtitle = when {
                    r == null || r.strokes == 0 -> stringResource(R.string.typing_glide_checkup_measured_none)
                    else -> stringResource(R.string.typing_glide_checkup_measured, r.strokes, r.currentRight)
                },
            )
        }
    }

    val profile = report?.profile
    if (profile != null && profile.count > 0) {
        SettingsGroup(stringResource(R.string.typing_glide_checkup_profile_title)) {
            item {
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_profile_corner_title),
                    subtitle = stringResource(R.string.typing_glide_checkup_profile_corner_value, keys(profile.cornerCut)),
                )
            }
            item {
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_profile_offset_title),
                    subtitle = stringResource(
                        R.string.typing_glide_checkup_profile_offset_value,
                        keys(abs(profile.offsetX)),
                        stringResource(
                            if (profile.offsetX < 0) {
                                R.string.typing_glide_checkup_profile_left
                            } else {
                                R.string.typing_glide_checkup_profile_right
                            },
                        ),
                        keys(abs(profile.offsetY)),
                        stringResource(
                            if (profile.offsetY < 0) {
                                R.string.typing_glide_checkup_profile_up
                            } else {
                                R.string.typing_glide_checkup_profile_down
                            },
                        ),
                    ),
                )
            }
            item {
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_profile_scale_title),
                    subtitle = stringResource(
                        R.string.typing_glide_checkup_profile_scale_value,
                        (profile.scale * 100).roundToInt(),
                    ),
                )
            }
            item {
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_profile_start_title),
                    subtitle = stringResource(R.string.typing_glide_checkup_profile_start_value, keys(profile.startDrift)),
                )
            }
            item {
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_profile_lift_title),
                    subtitle = stringResource(R.string.typing_glide_checkup_profile_lift_value, keys(profile.earlyLift)),
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.typing_glide_checkup_proposal_title)) {
        val current = change
        val applied = report?.applied
        when {
            current != null -> item {
                ProposalCard(
                    change = current,
                    onApply = {
                        scope.launch {
                            change = applyChange(current, repository, settings)
                        }
                    },
                    onUndo = {
                        scope.launch {
                            change = undoChange(current, repository, settings)
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            repository.decideGlideCheckup("DECLINE", current.checkup.candidate.id)
                            change = current.copy(proposal = current.proposal.copy(state = AssistantProposal.State.DISMISSED))
                        }
                    },
                )
            }
            applied != null -> item {
                ConfirmCard(
                    applied = applied,
                    settings = settings,
                    onKeep = { scope.launch { repository.decideGlideCheckup("SETTLE") } },
                    onUndo = {
                        scope.launch {
                            val target = candidateValue(applied.candidateId) ?: return@launch
                            val (setting, to) = target
                            val stale = setting.read(settings.value) != to
                            if (!stale) setting.write(repository, applied.fromId)
                            repository.decideGlideCheckup("SETTLE")
                        }
                    },
                )
            }
            else -> item {
                val r = report
                WmRow(
                    title = stringResource(R.string.typing_glide_checkup_no_proposal_title),
                    subtitle = when {
                        r == null || r.strokes < GlideCheckup.MIN_STROKES -> stringResource(
                            R.string.typing_glide_checkup_need_more,
                            GlideCheckup.MIN_STROKES - (r?.strokes ?: 0),
                        )
                        else -> stringResource(R.string.typing_glide_checkup_nothing, r.strokes)
                    },
                )
            }
        }
    }

    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.typing_glide_checkup_frequency_title),
                subtitle = if (frequencyWeight > 0f) {
                    stringResource(R.string.typing_glide_checkup_frequency_fitted, decimal(frequencyWeight.toDouble()))
                } else {
                    stringResource(R.string.typing_glide_checkup_frequency_own)
                },
                trailing = if (frequencyWeight > 0f) {
                    {
                        TextButton(onClick = { scope.launch { repository.setGestureFrequencyWeight(0f) } }) {
                            Text(stringResource(CommonR.string.common_reset))
                        }
                    }
                } else {
                    null
                },
            )
        }
        item {
            var confirm by remember { mutableStateOf(false) }
            TextButton(
                onClick = { confirm = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.typing_glide_checkup_reset)) }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.typing_glide_checkup_reset_title)) },
                    text = { Text(stringResource(R.string.typing_glide_checkup_reset_body)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            change = null
                            scope.launch {
                                repository.resetGlideCheckup()
                                refresh++
                            }
                        }) { Text(stringResource(CommonR.string.common_reset)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirm = false }) { Text(stringResource(CommonR.string.common_cancel)) }
                    },
                )
            }
        }
    }
}

/**
 * The row on the Glide typing page that opens the check-up, with where it
 * stands as its subtitle: off, measuring, a change ready, a change being
 * checked, or nothing to change.
 */
@Composable
internal fun GlideCheckupRow(repository: SettingsRepository, settings: LiveSettings, onOpen: () -> Unit) {
    val measuring = settings.watch { it.gesture.checkupMeasure }
    val version = settings.watch { it.gesture.checkupVersion }
    val decision = settings.watch { it.gesture.checkupDecision }
    val report by produceState<GlideCheckup.Report?>(null, version, decision, measuring) {
        value = repository.glideCheckupReport()
    }
    val r = report
    val applied = r?.applied
    val subtitle = when {
        applied != null && applied.done -> stringResource(R.string.typing_glide_checkup_state_checked)
        applied != null -> stringResource(
            R.string.typing_glide_checkup_state_checking, applied.strokesAfter, GlideCheckup.CONFIRM_WINDOW,
        )
        r?.proposal != null -> stringResource(R.string.typing_glide_checkup_state_ready)
        !measuring -> stringResource(R.string.typing_glide_checkup_state_off)
        r == null -> stringResource(R.string.typing_glide_checkup_subtitle)
        r.strokes < GlideCheckup.MIN_STROKES -> stringResource(
            R.string.typing_glide_checkup_state_collecting, r.strokes, GlideCheckup.MIN_STROKES,
        )
        else -> stringResource(R.string.typing_glide_checkup_state_nothing, r.strokes)
    }
    NavRow(
        title = R.string.typing_glide_checkup_title,
        subtitle = subtitle,
        route = "typing/gestures/glide/checkup",
        onClick = onOpen,
    )
}

/**
 * A proposal from the check-up as the assistant's model of one: the setting
 * it moves, the value it held when measured and the value it would take,
 * beside the check-up's own numbers.
 */
private data class CheckupChange(
    val proposal: AssistantProposal,
    val checkup: GlideCheckup.Proposal,
    val note: AssistantRefusal? = null,
)

/** The setting a candidate moves and the value it would set, or null for a candidate no setting answers to. */
private fun candidateValue(candidateId: String): Pair<AssistantSetting, String>? {
    val setting: AssistantSetting
    val to: String
    when {
        candidateId.startsWith("vocab:") -> {
            val rank = candidateId.removePrefix("vocab:").toIntOrNull() ?: return null
            setting = AssistantSetting.byName("glide_vocabulary") ?: return null
            to = GlideVocabulary.entries.firstOrNull { it.rank == rank }?.name ?: return null
        }
        candidateId.startsWith("freq:") -> {
            val weight = candidateId.removePrefix("freq:").toDoubleOrNull() ?: return null
            setting = AssistantSetting.byName("glide_frequency_weight") ?: return null
            to = AssistantSetting.frequencyPercent(weight.toFloat()).toString()
        }
        else -> return null
    }
    return setting to to
}

private fun proposalCard(proposal: GlideCheckup.Proposal, settings: LiveSettings): CheckupChange? {
    val (setting, to) = candidateValue(proposal.candidate.id) ?: return null
    val from = setting.read(settings.value)
    return CheckupChange(AssistantProposal(id = 0, setting = setting, from = from, to = to), proposal)
}

/** Apply, under the assistant's rules: never over a value that moved since the measurement. */
private suspend fun applyChange(change: CheckupChange, repository: SettingsRepository, settings: LiveSettings): CheckupChange {
    val refusal = AssistantProposals.applyRefusal(change.proposal, settings.value)
    if (refusal != null) {
        val stale = refusal == AssistantRefusal.CHANGED_SINCE
        return change.copy(
            proposal = if (stale) change.proposal.copy(state = AssistantProposal.State.STALE) else change.proposal,
            note = refusal,
        )
    }
    // The keyboard is told first, so the swipes under the new value count as
    // "after" from the first one.
    repository.decideGlideCheckup("APPLY", "${change.checkup.candidate.id}|${change.proposal.from}")
    val written = runCatching { change.proposal.setting.write(repository, change.proposal.to) }.isSuccess
    if (!written) return change.copy(note = AssistantRefusal.WRITE_FAILED)
    return change.copy(proposal = change.proposal.copy(state = AssistantProposal.State.APPLIED), note = null)
}

/** Undo, only while the setting still holds what Apply wrote. */
private suspend fun undoChange(change: CheckupChange, repository: SettingsRepository, settings: LiveSettings): CheckupChange {
    val refusal = AssistantProposals.undoRefusal(change.proposal, settings.value)
    if (refusal != null) return change.copy(note = refusal)
    val written = runCatching { change.proposal.setting.write(repository, change.proposal.from) }.isSuccess
    if (!written) return change.copy(note = AssistantRefusal.WRITE_FAILED)
    repository.decideGlideCheckup("SETTLE")
    return change.copy(proposal = change.proposal.copy(state = AssistantProposal.State.UNDONE), note = null)
}

/**
 * The proposal: the setting, its values now and proposed, the expected
 * effect on this user's own swipes in words, and Apply, Not now and Undo
 * under the same notes the assistant's cards carry.
 */
@Composable
private fun ProposalCard(
    change: CheckupChange,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val proposal = change.proposal
    val effect = change.checkup
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CardShape,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(proposal.setting.titleRes), style = MaterialTheme.typography.titleMedium)
            ChangeValues(shown(proposal.setting, proposal.from), shown(proposal.setting, proposal.to))
            Text(
                stringResource(
                    R.string.typing_glide_checkup_effect,
                    effect.strokes, effect.candidateRight, effect.currentRight, effect.more, effect.fewer,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val status = when {
                change.note == AssistantRefusal.WRITE_FAILED -> R.string.settings_assistant_write_failed
                change.note == AssistantRefusal.CHANGED_SINCE && proposal.state == AssistantProposal.State.APPLIED ->
                    R.string.settings_assistant_undo_refused
                change.note == AssistantRefusal.CHANGED_SINCE || proposal.state == AssistantProposal.State.STALE ->
                    R.string.typing_glide_checkup_stale
                proposal.state == AssistantProposal.State.APPLIED -> R.string.settings_assistant_applied
                proposal.state == AssistantProposal.State.UNDONE -> R.string.settings_assistant_undone
                proposal.state == AssistantProposal.State.DISMISSED -> R.string.settings_assistant_dismissed
                else -> 0
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (status != 0) {
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                when (proposal.state) {
                    AssistantProposal.State.PROPOSED -> {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.typing_glide_checkup_not_now)) }
                        Spacer(Modifier.width(4.dp))
                        FilledTonalButton(onClick = onApply) { Text(stringResource(R.string.settings_assistant_apply)) }
                    }
                    AssistantProposal.State.APPLIED ->
                        TextButton(onClick = onUndo) { Text(stringResource(R.string.settings_assistant_undo)) }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * A change in force: how far its window has run, and once it is full, the
 * correction rate before and after, with Keep and Undo. Undo is offered the
 * whole way, since the setting is the user's to take back at any time.
 */
@Composable
private fun ConfirmCard(
    applied: GlideCheckup.Applied,
    settings: LiveSettings,
    onKeep: () -> Unit,
    onUndo: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val target = candidateValue(applied.candidateId)
    val holds = target != null && target.first.read(settings.value) == target.second
    Surface(
        shape = CardShape,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.typing_glide_checkup_confirm_title), style = MaterialTheme.typography.titleMedium)
            if (target != null) {
                ChangeValues(shown(target.first, applied.fromId), shown(target.first, target.second))
            }
            Text(
                if (applied.done) {
                    stringResource(
                        R.string.typing_glide_checkup_confirm_rates,
                        applied.correctionsAfter, applied.strokesAfter,
                        applied.correctionsBefore, applied.strokesBefore,
                    )
                } else {
                    stringResource(
                        R.string.typing_glide_checkup_confirm_progress,
                        applied.strokesAfter, GlideCheckup.CONFIRM_WINDOW,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (applied.done) {
                Text(
                    stringResource(
                        if (applied.worse) {
                            R.string.typing_glide_checkup_confirm_worse
                        } else {
                            R.string.typing_glide_checkup_confirm_fine
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (!holds) {
                    Text(
                        stringResource(R.string.settings_assistant_undo_refused),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (holds) {
                    TextButton(onClick = onUndo) { Text(stringResource(R.string.settings_assistant_undo)) }
                    Spacer(Modifier.width(4.dp))
                }
                FilledTonalButton(onClick = onKeep) { Text(stringResource(R.string.typing_glide_checkup_keep)) }
            }
        }
    }
}

/** A distance in key widths, to one decimal. */
private fun keys(value: Double): String = decimal(value)

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
