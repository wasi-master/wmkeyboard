package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabProgress
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DailyPick(val word: VocabWord?, val packId: String?, val hasPacks: Boolean)

/**
 * The word-of-the-day card on the settings home. The same draw the keyboard
 * makes (pinned per day in the learning record), so both show one word.
 * Draws a nudge to install a pack when there is none, and nothing at all
 * once every word is learnt.
 */
@Composable
internal fun VocabDailyCard(settings: KeyboardSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val pick by produceState<DailyPick?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val packs = VocabPacks.languages(context.filesDir).flatMap { VocabPacks.load(context.filesDir, it) }
            if (packs.none { it.words.isNotEmpty() }) return@withContext DailyPick(null, null, hasPacks = false)
            val index = VocabIndex.build(packs)
            val progress = VocabProgress(File(context.filesDir, VocabProgress.FILE_PATH))
            val candidates = index.lemmas.filter { !progress.isLearnt(it) }
            val lemma = progress.wordOfTheDay(vocabToday(), candidates)
            progress.save()
            DailyPick(lemma?.let { index.lookup(it) }, lemma?.let { index.packOf(it)?.id }, hasPacks = true)
        }
    }
    val current = pick ?: return
    val accent = toolAccentColor(ToolbarTool.VOCABULARY, settings.toolColorOverrides)
    if (!current.hasPacks) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_vocab_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_vocab_install_body), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { onNavigate(VOCAB_PACKS_ROUTE) }) { Text(stringResource(R.string.home_vocab_install_action)) }
            }
        }
        return
    }
    val word = current.word ?: return
    val speaker = rememberVocabSpeaker()
    val progress = rememberVocabProgress()
    var learnt by remember(word.word) { mutableIntStateOf(if (progress.isLearnt(word.word)) 1 else 0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoStories, contentDescription = null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.home_vocab_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { speakVocabWord(context, settings, speaker, word) }) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = stringResource(R.string.vocab_word_speak_desc))
                }
            }
            Text(word.word, style = MaterialTheme.typography.headlineSmall)
            val line = listOfNotNull(word.pos.firstOrNull(), word.ipaFor(settings.vocabulary.accent), word.respelling).joinToString("  ·  ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(word.definition, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = learnt == 0,
                    onClick = {
                        progress.markLearnt(word.word, true, vocabToday())
                        progress.save()
                        learnt = 1
                    },
                ) { Text(stringResource(if (learnt == 1) R.string.home_vocab_learnt_label else R.string.home_vocab_learnt_action)) }
                TextButton(onClick = { onNavigate(vocabWordRoute(current.packId ?: "all", word.word)) }) {
                    Text(stringResource(R.string.home_vocab_open_action))
                }
            }
        }
    }
}
