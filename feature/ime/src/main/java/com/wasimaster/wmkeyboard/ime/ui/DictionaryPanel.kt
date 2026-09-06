package com.wasimaster.wmkeyboard.ime.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import com.wasimaster.wmkeyboard.ime.DictionaryUi
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * English dictionary lookup in the tool viewbox. Opening the tool
 * auto-fills the word at the cursor (per setting); the search chip routes
 * the key rows into the query, like emoji search. Entries render with a
 * serif display face for the headword — dictionary typography, not
 * keyboard typography.
 */
/**
 * Header-row search field for the full-bleed dictionary: sits next to the
 * back button and takes the row's free width. Tapping it routes the key
 * rows into the query, like emoji search.
 */
@Composable
internal fun RowScope.DictionaryHeaderSearchBar(
    state: KeyboardUiState,
    onSearchToggle: () -> Unit,
    onLookup: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(kb.chip)
            .clickable { onSearchToggle() }
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.toolbarIcon,
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = state.dictionaryQuery,
            placeholder = if (state.dictionarySearchActive) {
                stringResource(R.string.ime_dict_search_active_hint)
            } else {
                stringResource(R.string.ime_dict_search_hint)
            },
            active = state.dictionarySearchActive,
            textColor = kb.modifierKeyText,
            placeholderColor = kb.toolbarIcon,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (state.dictionarySearchActive) {
            IconButton(
                onClick = { onLookup(state.dictionaryQuery) },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(R.string.ime_dict_lookup_desc),
                    modifier = Modifier.size(16.dp),
                    tint = kb.accent,
                )
            }
        }
    }
}

@Composable
internal fun DictionaryPanel(
    state: KeyboardUiState,
    onLookup: (String) -> Unit,
    onInsert: (String) -> Unit,
    /** "Add to vocab": the entry goes into the user's "My words" list. */
    onAddToVocab: (String) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // While searching the wrapper collapses to just the header; the
        // body would only flash clipped content, so skip it.
        if (state.dictionarySearchActive) return@Box

        when (val dict = state.dictionary) {
            DictionaryUi.Idle -> DictionaryMessage(stringResource(R.string.ime_dict_idle_info))
            is DictionaryUi.Loading -> DictionaryMessage(
                stringResource(R.string.ime_dict_lookup_progress, dict.word),
            )
            is DictionaryUi.Error -> DictionaryMessage(stringResource(R.string.ime_dict_error))
            is DictionaryUi.NotFound -> DictionaryMessage(
                stringResource(R.string.ime_dict_not_found_empty, dict.word),
            )
            is DictionaryUi.Ready -> DictionaryEntries(state, dict.entries, onLookup, onInsert, onAddToVocab)
        }
    }
}

@Composable
private fun DictionaryMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = LocalKbTheme.current.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun DictionaryEntries(
    state: KeyboardUiState,
    entries: List<DictEntry>,
    onLookup: (String) -> Unit,
    onInsert: (String) -> Unit,
    onAddToVocab: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    // Serif display face for headwords; falls back to the system serif
    // until the downloadable font arrives (or forever, offline).
    val serif = remember { KeyboardFonts.googleFamily("Lora") }
    // One shared player; releasing on dispose stops any playback with the panel.
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Only the headwords are focusable — their action is Insert, and the
    // meanings under them are prose with nothing to activate.
    PanelFocusTarget(
        panel = PanelMode.DICTIONARY,
        count = entries.size,
        columns = 1,
        onActivate = { index -> entries.getOrNull(index)?.let { onInsert(it.word) } },
    )
    val focused = state.focusedIndex()
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focused) { index ->
        // Entry index is not lazy-item index here: each entry contributes its
        // headword plus one item per meaning.
        listState.animateScrollToItem(
            entries.take(index).sumOf { 1 + it.meanings.size },
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, bottom = 10.dp,
        ),
    ) {
        entries.forEachIndexed { entryIndex, entry ->
            item(key = "head$entryIndex") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(entryIndex == focused, RoundedCornerShape(8.dp))
                        .padding(top = if (entryIndex == 0) 2.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.word,
                        color = kb.modifierKeyText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = serif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.phonetic.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.phonetic,
                            color = kb.toolbarIcon,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val audioUrl = entry.audioUrl
                    if (audioUrl != null) {
                        IconButton(
                            onClick = { playPronunciation(player, audioUrl) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = stringResource(R.string.ime_dict_play_desc),
                                modifier = Modifier.size(18.dp),
                                tint = kb.accent,
                            )
                        }
                    }
                    DictionaryChip(
                        label = stringResource(R.string.ime_dict_insert_action),
                        filled = true,
                    ) { onInsert(entry.word) }
                    if (vocabToolOn(state)) {
                        Spacer(Modifier.width(6.dp))
                        DictionaryChip(
                            label = stringResource(R.string.ime_dict_add_vocab_action),
                            filled = false,
                        ) { onAddToVocab(entry.word) }
                    }
                }
            }
            entry.meanings.forEachIndexed { meaningIndex, meaning ->
                item(key = "meaning$entryIndex-$meaningIndex") {
                    DictionaryMeaning(meaning, serif, onLookup)
                }
            }
        }
    }
}

@Composable
private fun DictionaryMeaning(
    meaning: DictMeaning,
    serif: FontFamily,
    onLookup: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    Column(modifier = Modifier.padding(top = 6.dp)) {
        if (meaning.partOfSpeech.isNotEmpty()) {
            Text(
                meaning.partOfSpeech,
                color = kb.accent,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = serif,
                fontWeight = FontWeight.SemiBold,
            )
        }
        meaning.definitions.forEachIndexed { index, definition ->
            Row(modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    "${index + 1}.",
                    color = kb.toolbarIcon,
                    fontSize = 13.sp,
                    modifier = Modifier.width(20.dp),
                )
                Column {
                    Text(
                        definition.text,
                        color = kb.modifierKeyText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                    if (definition.example != null) {
                        Text(
                            "“${definition.example}”",
                            color = kb.toolbarIcon,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = serif,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
        val synonyms = (meaning.synonyms + meaning.definitions.flatMap { it.synonyms }).distinct()
        if (synonyms.isNotEmpty()) {
            WordChipRow(stringResource(R.string.ime_dict_synonyms_label), synonyms, onLookup)
        }
        if (meaning.antonyms.isNotEmpty()) {
            WordChipRow(
                stringResource(R.string.ime_dict_antonyms_label),
                meaning.antonyms,
                onLookup,
            )
        }
    }
}

/** Label plus a scrolling row of tappable related words (tap = look up). */
@Composable
private fun WordChipRow(label: String, words: List<String>, onLookup: (String) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = kb.toolbarIcon, fontSize = 11.sp, modifier = Modifier.width(66.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (word in words.take(12)) {
                DictionaryChip(label = word, filled = false) { onLookup(word) }
            }
        }
    }
}

@Composable
private fun DictionaryChip(label: String, filled: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (filled) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (filled) kb.chipActiveText else kb.chipText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Streams the pronunciation recording; errors just leave silence. */
private fun playPronunciation(player: MediaPlayer, url: String) {
    runCatching {
        player.reset()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(url)
        player.setOnPreparedListener { it.start() }
        player.prepareAsync()
    }
}
