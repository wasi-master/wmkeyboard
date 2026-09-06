package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.vocab.ReviewGrade
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Flashcards in the settings app: everything due today plus the daily goal
 * of new words, one card at a time. The keyboard panel runs the same queue
 * in miniature; both write the same learning record.
 */
@Composable
internal fun VocabReviewScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val index = rememberVocabIndex(0)
    val progress = rememberVocabProgress()
    val speaker = rememberVocabSpeaker()
    val today = remember { vocabToday() }
    var ahead by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<VocabWord>?>(null) }
    var position by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf(IntArray(ReviewGrade.entries.size)) }

    if (index == null) {
        CaptionText(stringResource(CommonR.string.common_loading), Modifier.padding(16.dp))
        return
    }
    if (queue == null) {
        val day = if (ahead) today + 1 else today
        val due = progress.dueWords(day, index.lemmas)
        val fresh = progress.unseen(index.lemmas).shuffled(java.util.Random(today.toLong())).take(settings.vocabulary.dailyGoal)
        queue = (due + fresh).mapNotNull { index.lookup(it) }
        position = 0
    }
    val cards = queue.orEmpty()
    val current = cards.getOrNull(position)

    RegisterPinned {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { if (cards.isEmpty()) 0f else position.toFloat() / cards.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            CaptionText(
                if (cards.isEmpty()) stringResource(R.string.vocab_review_empty_title) else stringResource(R.string.vocab_review_progress, position.coerceAtMost(cards.size), cards.size),
            )
        }
    }

    if (index.isEmpty) {
        SettingsGroup {
            item { CaptionText(stringResource(R.string.vocab_browse_no_packs_body)) }
            item { NavRow(R.string.tooldetail_vocab_packs_title, route = VOCAB_PACKS_ROUTE) { onNavigate(VOCAB_PACKS_ROUTE) } }
        }
        return
    }

    if (current == null) {
        SettingsGroup(stringResource(if (position > 0) R.string.vocab_review_done_title else R.string.vocab_review_empty_title)) {
            if (position > 0) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (grade in ReviewGrade.entries) {
                            val n = counts[grade.ordinal]
                            if (grade == ReviewGrade.HARD && settings.vocabulary.scheduler == VocabScheduler.LEITNER && n == 0) continue
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(n.toString(), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(grade.labelRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                item { CaptionText(stringResource(R.string.vocab_review_empty_body)) }
            }
            item {
                WmRow(title = stringResource(R.string.vocab_review_ahead_action), subtitle = stringResource(R.string.vocab_review_ahead_subtitle), onClick = {
                    ahead = true
                    queue = null
                    counts = IntArray(ReviewGrade.entries.size)
                })
            }
            item { NavRow(R.string.tooldetail_vocab_browse_title, route = VOCAB_BROWSE_ROUTE) { onNavigate(VOCAB_BROWSE_ROUTE) } }
        }
        return
    }

    val rotation by animateFloatAsState(if (flipped) 180f else 0f, label = "flip")
    val reduce = settings.reduceMotion
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .height(360.dp)
            .graphicsLayer {
                rotationY = if (reduce) 0f else rotation
                cameraDistance = 12f * density
            }
            .clickable { flipped = !flipped },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        val mirrored = !reduce && rotation > 90f
        Box(Modifier.graphicsLayer { rotationY = if (mirrored) 180f else 0f }.padding(24.dp), contentAlignment = Alignment.Center) {
            if (!flipped) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current.word, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    current.ipaFor(settings.vocabulary.accent)?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { speakVocabWord(context, settings, speaker, current) }) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.vocab_word_speak_desc))
                    }
                    Text(stringResource(R.string.vocab_review_flip_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current.word, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    current.pos.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic) }
                    Text(current.definition, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 5)
                    current.senses.firstOrNull()?.example?.let {
                        Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    }
                }
            }
        }
    }

    val grades = if (settings.vocabulary.scheduler == VocabScheduler.SM2) {
        listOf(ReviewGrade.AGAIN, ReviewGrade.HARD, ReviewGrade.GOOD, ReviewGrade.EASY)
    } else {
        listOf(ReviewGrade.AGAIN, ReviewGrade.GOOD, ReviewGrade.EASY)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!flipped) {
            FilledTonalButton(onClick = { flipped = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vocab_review_flip_hint))
            }
        } else {
            for (grade in grades) {
                val preview = progress.preview(current.word, grade, today, settings.vocabulary.scheduler)
                val days = (preview.dueDay - today).coerceAtLeast(0)
                val label = stringResource(grade.labelRes()) + "\n" + pluralStringResource(R.plurals.vocab_review_days, days, days)
                if (grade == ReviewGrade.GOOD) {
                    FilledTonalButton(onClick = { grade(progress, current, grade, today, settings) { counts[grade.ordinal]++; position++; flipped = false } }, modifier = Modifier.weight(1f)) {
                        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(onClick = { grade(progress, current, grade, today, settings) { counts[grade.ordinal]++; position++; flipped = false } }, modifier = Modifier.weight(1f)) {
                        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        androidx.compose.material3.TextButton(onClick = {
            progress.markLearnt(current.word, true, today)
            progress.save()
            position++
            flipped = false
        }) { Text(stringResource(R.string.vocab_review_mark_learnt_action)) }
        Spacer(Modifier.width(4.dp))
        androidx.compose.material3.TextButton(onClick = { onNavigate(vocabWordRoute(index.packOf(current.word)?.id ?: "all", current.word)) }) {
            Text(stringResource(R.string.vocab_review_open_action))
        }
    }
    Spacer(Modifier.height(24.dp))
    @Suppress("UNUSED_VARIABLE")
    val unused = repository
}

private fun grade(
    progress: com.wasimaster.wmkeyboard.core.vocab.VocabProgress,
    word: VocabWord,
    grade: ReviewGrade,
    today: Int,
    settings: KeyboardSettings,
    then: () -> Unit,
) {
    progress.review(word.word, grade, today, settings.vocabulary.scheduler)
    progress.save()
    then()
}

private fun ReviewGrade.labelRes(): Int = when (this) {
    ReviewGrade.AGAIN -> R.string.vocab_review_again
    ReviewGrade.HARD -> R.string.vocab_review_hard
    ReviewGrade.GOOD -> R.string.vocab_review_good
    ReviewGrade.EASY -> R.string.vocab_review_easy
}
