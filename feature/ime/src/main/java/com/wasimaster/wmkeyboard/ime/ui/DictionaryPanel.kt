package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.providers.ProviderTool
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
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.tools.yomichan.PitchNotation
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import com.wasimaster.wmkeyboard.core.vocab.VocabSpeaker
import com.wasimaster.wmkeyboard.core.vocab.spokenLocale
import com.wasimaster.wmkeyboard.ime.DictionaryUi
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext

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
            .frostGlass(kb, RoundedCornerShape(18.dp), FrostSurface.CHIPS)
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
            IconHint(stringResource(R.string.ime_dict_lookup_desc)) {
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
    // Pick mode (#727): which dictionary answers.
    if (!state.dictionarySearchActive) {
        ProviderChooser(ProviderTool.DICTIONARY, state.settings, Modifier.padding(end = 6.dp))
    }
    // Ask AI about everything the lookup found (#352), or copy it (#348); a
    // long press on a definition does either for just that.
    val ready = state.dictionary as? DictionaryUi.Ready
    if (ready != null && !state.dictionarySearchActive && ready.entries.isNotEmpty()) {
        val word = ready.entries.first().word
        AskAiChip(
            stringResource(R.string.ime_ask_ai_label_dict, word),
            modifier = Modifier.padding(end = 4.dp),
        ) { AskAiContext.dictionary(ready.entries) }
        // The same text, to the clipboard (#348).
        CopyTextChip(Modifier.padding(end = 6.dp)) { AskAiContext.dictionary(ready.entries) }
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
            // Another app on the phone may know it (#970): Aard 2, ColorDict, Kiwix.
            is DictionaryUi.NotFound -> DictionaryMessage(
                stringResource(R.string.ime_dict_not_found_empty, dict.word),
                lookupWord = dict.word,
            )
            is DictionaryUi.NotOnDevice -> DictionaryMessage(
                stringResource(R.string.ime_dict_not_on_device, dict.word),
                lookupWord = dict.word,
            )
            DictionaryUi.NoSources -> DictionaryMessage(stringResource(R.string.ime_dict_no_sources))
            is DictionaryUi.Ready -> DictionaryEntries(state, dict, onLookup, onInsert, onAddToVocab)
        }
    }
}

@Composable
private fun DictionaryMessage(text: String, lookupWord: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = LocalKbTheme.current.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (lookupWord != null) LookupAppChip(Modifier.padding(top = 8.dp)) { lookupWord }
    }
}

@Composable
private fun DictionaryEntries(
    state: KeyboardUiState,
    ready: DictionaryUi.Ready,
    onLookup: (String) -> Unit,
    onInsert: (String) -> Unit,
    onAddToVocab: (String) -> Unit,
) {
    val entries: List<DictEntry> = ready.entries
    val source: DictionarySource = ready.source
    val kb = LocalKbTheme.current
    // Serif display face for headwords; falls back to the system serif
    // until the downloadable font arrives (or forever, offline).
    val serif = remember { KeyboardFonts.googleFamily("Lora") }
    // One shared speaker, made on the first tap; shutting it down stops any
    // playback with the panel.
    val context = LocalContext.current
    val speaker = remember { lazy { VocabSpeaker(context) } }
    DisposableEffect(Unit) { onDispose { if (speaker.isInitialized()) speaker.value.shutdown() } }
    val vocab = state.settings.vocabulary

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
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRing(entryIndex == focused, RoundedCornerShape(8.dp))
                            .padding(top = if (entryIndex == 0) 2.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            // The kana over a Japanese headword (#765), furigana-style.
                            if (entry.reading.isNotEmpty()) {
                                Text(
                                    entry.reading,
                                    color = kb.toolbarIcon,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                entry.word,
                                color = kb.modifierKeyText,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = serif,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                        // Every entry: the source's recording when there is one and
                        // the build can fetch it, and the phone's voice otherwise.
                        IconHint(stringResource(R.string.ime_dict_play_desc)) {
                            IconButton(
                                onClick = {
                                    speaker.value.speak(
                                        entry.word,
                                        entry.audioUrl?.takeIf { InternetPermission.granted },
                                        vocab.ttsRate,
                                        vocab.ttsPitch,
                                        entry.spokenLocale(),
                                    )
                                },
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
                    // Pitch accent and frequency, from an imported dictionary (#765).
                    DictionaryPitchLine(entry)
                    // The word in the user's language, from a vocabulary pack's
                    // translations (#651). Inside the headword's item, so the
                    // focus scroll's item count is unchanged.
                    // The translations below (#764) say the same and more.
                    val meaning = ready.meaning
                    if (entryIndex == 0 && meaning != null && ready.translations.isEmpty()) VocabMeaningText(meaning)
                }
            }
            entry.meanings.forEachIndexed { meaningIndex, meaning ->
                item(key = "meaning$entryIndex-$meaningIndex") {
                    DictionaryMeaning(meaning, serif, onLookup, entry.word)
                }
            }
        }
        // The first entry's word in the user's languages (#764), after every
        // entry so the focus scroll's item count is unchanged.
        if (ready.translations.isNotEmpty() || ready.translationsLoading) {
            item(key = "translations") {
                DictionaryTranslations(ready, serif, onInsert)
            }
        }
        // Which source answered: the user's order decides, and Wiktionary's
        // licence asks for the credit.
        item(key = "source") {
            // An imported dictionary names itself; the rest name the source.
            val origins = entries.map { it.origin }.filter { it.isNotEmpty() }.distinct()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.ime_dict_from,
                        if (origins.isEmpty()) stringResource(source.labelRes) else origins.joinToString(", "),
                    ),
                    color = kb.toolbarIcon,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                // The same word in another app's dictionary (#970).
                LookupAppChip { entries.first().word }
            }
        }
    }
}

@Composable
private fun DictionaryMeaning(
    meaning: DictMeaning,
    serif: FontFamily,
    onLookup: (String) -> Unit,
    word: String,
) {
    val kb = LocalKbTheme.current
    val askLabel = stringResource(R.string.ime_ask_ai_label_dict, word)
    val ask = remember(word, askLabel) { AskAiSource(AskAiContext.dictionarySource(word), askLabel) }
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
                    // A long press selects: Ask AI, Copy or Insert (#352).
                    SelectableText(
                        definition.text,
                        color = kb.modifierKeyText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        ask = ask,
                    )
                    if (definition.example != null) {
                        SelectableText(
                            "“${definition.example}”",
                            color = kb.toolbarIcon,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = serif,
                            modifier = Modifier.padding(top = 1.dp),
                            ask = ask,
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

/**
 * The word in other languages (#764): one row per language, its name and a
 * scrolling row of chips. A chip types its translation at the cursor; the
 * romanisation, when the script has one, rides in the chip after a dot.
 */
@Composable
private fun DictionaryTranslations(
    ready: DictionaryUi.Ready,
    serif: FontFamily,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            stringResource(R.string.ime_dict_translations_label),
            color = kb.accent,
            fontSize = 14.sp,
            fontStyle = FontStyle.Italic,
            fontFamily = serif,
            fontWeight = FontWeight.SemiBold,
        )
        if (ready.translations.isEmpty()) {
            Text(
                stringResource(R.string.ime_dict_translations_loading),
                color = kb.toolbarIcon,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        for ((code, translation) in ready.translations) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    VocabLanguages.displayName(code),
                    color = kb.toolbarIcon,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(66.dp),
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    for (gloss in translation.glosses()) {
                        val label = gloss.roman?.let { "${gloss.word} · $it" } ?: gloss.word
                        DictionaryChip(label = label, filled = false) { onInsert(gloss.word) }
                    }
                }
            }
        }
    }
}

/**
 * The pitch accent and frequency an imported dictionary gives (#765), on one
 * quiet line under the headword: たべꜜる [2] · Frequency 1234. Nothing for
 * an entry with neither.
 */
@Composable
private fun DictionaryPitchLine(entry: DictEntry) {
    val reading = entry.reading.ifEmpty { entry.word }
    val parts = entry.pitch.map { PitchNotation.format(reading, it) } +
        listOfNotNull(entry.frequency.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ime_dict_frequency, it) })
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = LocalKbTheme.current.toolbarIcon,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
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
            .frostGlass(kb, shape, FrostSurface.CHIPS)
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
