package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.usableTools
import com.wasimaster.wmkeyboard.core.vocab.FieldVisibility
import com.wasimaster.wmkeyboard.core.vocab.ReviewGrade
import com.wasimaster.wmkeyboard.core.vocab.VocabCardField
import com.wasimaster.wmkeyboard.core.vocab.VocabCardFields
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler
import com.wasimaster.wmkeyboard.core.vocab.VocabSense
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VocabBrowseFilter
import com.wasimaster.wmkeyboard.ime.VocabCardUi
import com.wasimaster.wmkeyboard.ime.VocabTab

/**
 * The vocabulary panel's service callbacks, bundled into one value for the
 * reason [SnippetPanelCallbacks] is: the callers sit against the JVM's 64K
 * method-size ceiling, so a new panel may not add parameters to them. The
 * bundle rides [ToolHoldCallbacks.vocab], which already reaches both the
 * strip and the panel host.
 */
data class VocabCallbacks(
    /** Show the card for a lemma, pushing the current one onto the back stack. */
    val onOpen: (String) -> Unit = {},
    /** Pop the back stack, or close the panel when it is empty. */
    val onBack: () -> Unit = {},
    val onTab: (VocabTab) -> Unit = {},
    /** Type text into the editor. */
    val onInsert: (String) -> Unit = {},
    /** A synonym or antonym chip was tapped; the setting decides what happens. */
    val onRelated: (String) -> Unit = {},
    val onMarkLearnt: (String, Boolean) -> Unit = { _, _ -> },
    val onAddToList: (String) -> Unit = {},
    val onFlip: () -> Unit = {},
    val onReview: (ReviewGrade) -> Unit = {},
    val onSpeak: (VocabWord) -> Unit = {},
    /** The word-of-the-day chip was tapped; the caller then taps the tool. */
    val onDailyOpen: () -> Unit = {},
    val onDailyDismiss: () -> Unit = {},
    /** Hand the word to the Dictionary tool. */
    val onFullDictionary: (String) -> Unit = {},
    /** The Dictionary panel's "Add to vocab" chip. */
    val onDictionaryAddToVocab: (String) -> Unit = {},
    val onBrowsePack: (String?) -> Unit = {},
    val onBrowseFilter: (VocabBrowseFilter) -> Unit = {},
)

/** The panel inside its full-bleed frame: tab chips and the Browse search bar in the header. */
@Composable
internal fun VocabPanelHost(
    state: KeyboardUiState,
    callbacks: VocabCallbacks,
    onPanelChange: (PanelMode) -> Unit,
    onQueryTap: () -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val vocab = state.vocab
    FullBleedTool(
        state,
        title = "",
        onClose = { if (vocab.stack.isNotEmpty()) callbacks.onBack() else onPanelChange(PanelMode.VOCABULARY) },
        compact = state.mediaSearchActive,
        compactHeight = 44.dp,
        headerActions = {
            VocabHeader(state, callbacks, onQueryTap)
        },
    ) {
        VocabPanel(state, callbacks, onOpenRoute)
    }
}

@Composable
private fun RowScope.VocabHeader(
    state: KeyboardUiState,
    callbacks: VocabCallbacks,
    onQueryTap: () -> Unit,
) {
    val vocab = state.vocab
    if (vocab.tab == VocabTab.BROWSE) {
        MediaHeaderSearchBar(
            state = state,
            placeholder = stringResource(R.string.ime_vocab_search_hint),
            onQueryTap = onQueryTap,
            activePlaceholder = stringResource(R.string.ime_vocab_search_active_hint),
        )
    } else {
        Spacer(Modifier.weight(1f))
    }
    if (!state.mediaSearchActive) {
        for (tab in VocabTab.entries) {
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(stringResource(tab.labelRes()), selected = vocab.tab == tab) { callbacks.onTab(tab) }
        }
        Spacer(Modifier.width(6.dp))
    }
}

private fun VocabTab.labelRes(): Int = when (this) {
    VocabTab.CARD -> R.string.ime_vocab_tab_card
    VocabTab.BROWSE -> R.string.ime_vocab_tab_browse
    VocabTab.REVIEW -> R.string.ime_vocab_tab_review
}

@Composable
internal fun VocabPanel(
    state: KeyboardUiState,
    callbacks: VocabCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.mediaSearchActive) return@Box
        val vocab = state.vocab
        if (!vocab.available) {
            VocabMessage(
                text = stringResource(R.string.ime_vocab_no_packs_info),
                action = stringResource(R.string.ime_vocab_manage_action),
            ) { onOpenRoute(VOCAB_PACKS_ROUTE) }
            return@Box
        }
        when (vocab.tab) {
            VocabTab.CARD -> when (val card = vocab.card) {
                VocabCardUi.Idle -> VocabMessage(
                    text = stringResource(R.string.ime_vocab_idle_info),
                    action = stringResource(R.string.ime_vocab_tab_browse),
                ) { callbacks.onTab(VocabTab.BROWSE) }
                is VocabCardUi.NotFound -> VocabMessage(
                    text = stringResource(R.string.ime_vocab_not_found, card.word),
                    action = stringResource(R.string.ime_vocab_tab_browse),
                ) { callbacks.onTab(VocabTab.BROWSE) }
                is VocabCardUi.Ready -> VocabCard(state, card, callbacks, onOpenRoute)
            }
            VocabTab.BROWSE -> VocabBrowse(state, callbacks)
            VocabTab.REVIEW -> VocabReview(state, callbacks)
        }
    }
}

@Composable
private fun VocabMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val kb = LocalKbTheme.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = kb.toolbarIcon, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            VocabChip(label = action, filled = true, onClick = onAction)
        }
    }
}

// ---- the card ----

@Composable
private fun VocabCard(
    state: KeyboardUiState,
    card: VocabCardUi.Ready,
    callbacks: VocabCallbacks,
    onOpenRoute: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val settings = state.settings.vocabulary
    val word = card.word
    val serif = remember { KeyboardFonts.googleFamily("Lora") }
    val fields = remember(settings.cardFields) { VocabCardFields.resolve(settings.cardFields) }
    fun shown(field: VocabCardField) = fields[field] == FieldVisibility.KEYBOARD
    val actions = listOf(
        stringResource(R.string.ime_vocab_insert_action) to { callbacks.onInsert(word.word) },
        (if (card.inMyList) stringResource(R.string.ime_vocab_in_list_label) else stringResource(R.string.ime_vocab_add_list_action)) to
            { if (!card.inMyList) callbacks.onAddToList(word.word) },
        (if (card.learnt) stringResource(R.string.ime_vocab_unlearnt_action) else stringResource(R.string.ime_vocab_learnt_action)) to
            { callbacks.onMarkLearnt(word.word, !card.learnt) },
        stringResource(R.string.ime_vocab_full_dictionary_action) to { callbacks.onFullDictionary(word.word) },
    )
    PanelFocusTarget(
        panel = PanelMode.VOCABULARY,
        count = actions.size,
        columns = actions.size,
        region = FocusRegion.ACTIONS,
        onActivate = { index -> actions.getOrNull(index)?.second?.invoke() },
    )
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val listState = rememberLazyListState()
    val attestedLabel = word.attested?.let { stringResource(R.string.ime_vocab_attested_label, it) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp),
    ) {
        item(key = "head") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            word.word,
                            color = kb.modifierKeyText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = serif,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        for (pos in word.pos.take(3)) {
                            Spacer(Modifier.width(6.dp))
                            VocabPill(pos, serif)
                        }
                    }
                    val ipa = word.ipaFor(settings.accent).takeIf { shown(VocabCardField.IPA) }
                    val respelling = word.respelling.takeIf { shown(VocabCardField.RESPELLING) }
                    if (ipa != null || respelling != null) {
                        Text(
                            listOfNotNull(ipa, respelling).joinToString("  ·  "),
                            color = kb.toolbarIcon,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { callbacks.onSpeak(word) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(R.string.ime_vocab_speak_desc),
                        modifier = Modifier.size(20.dp),
                        tint = kb.accent,
                    )
                }
                if (card.learnt) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.ime_vocab_learnt_action),
                        modifier = Modifier.size(20.dp),
                        tint = kb.accent,
                    )
                } else if (card.box != null && card.box > 0) {
                    VocabPill(stringResource(R.string.ime_vocab_box_label, card.box), serif)
                }
            }
        }
        if (shown(VocabCardField.SOURCES) && card.sources.isNotEmpty()) {
            item(key = "sources") {
                Text(
                    card.sources.joinToString(" · "),
                    color = kb.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                actions.forEachIndexed { index, (label, action) ->
                    VocabChip(
                        label = label,
                        filled = index == 0,
                        focused = focusedAction == index,
                        onClick = action,
                    )
                }
            }
        }
        itemsIndexed(word.senses) { index, sense ->
            VocabSenseRow(index, sense, serif, ::shown)
        }
        val related = buildList {
            if (shown(VocabCardField.SYNONYMS) && word.synonyms.isNotEmpty()) {
                add(R.string.ime_vocab_synonyms_label to word.synonyms)
            }
            if (shown(VocabCardField.ANTONYMS) && word.antonyms.isNotEmpty()) {
                add(R.string.ime_vocab_antonyms_label to word.antonyms)
            }
            if (shown(VocabCardField.FAMILY) && word.familyWords.isNotEmpty()) {
                add(R.string.ime_vocab_family_label to word.familyWords)
            }
            if (shown(VocabCardField.HYPERNYMS) && (word.hypernyms + word.hyponyms).isNotEmpty()) {
                add(R.string.ime_vocab_hypernyms_label to (word.hypernyms + word.hyponyms))
            }
        }
        items(related, key = { "rel${it.first}" }) { (labelRes, words) ->
            VocabWordChipRow(stringResource(labelRes), words, callbacks)
        }
        if (shown(VocabCardField.ORIGIN) && word.origin.isNotEmpty()) {
            item(key = "origin") {
                Text(
                    word.origin.joinToString(" ← ") { "${it.lang} ${it.word}" },
                    color = kb.toolbarIcon,
                    fontSize = 12.sp,
                    fontFamily = serif,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        val translation = shown(VocabCardField.TRANSLATIONS).let { on ->
            if (!on) null else {
                val wanted = settings.translationLangList.ifEmpty {
                    state.settings.enabledLanguages.map { it.id }.filter { it != "en" }
                }
                wanted.firstNotNullOfOrNull { code -> word.translations[code]?.let { code to it } }
            }
        }
        if (translation != null) {
            item(key = "translation") {
                val (code, glosses) = translation
                Text(
                    "$code · " + glosses.w.joinToString(", ") + (glosses.r.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString(", ")})" } ?: ""),
                    color = kb.modifierKeyText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        val sections = buildList {
            if (shown(VocabCardField.ETYMOLOGY) && !word.etymology.isNullOrBlank()) {
                add(R.string.ime_vocab_etymology_label to word.etymology!!)
            }
            if (shown(VocabCardField.MNEMONIC) && !word.mnemonic.isNullOrBlank()) {
                add(R.string.ime_vocab_mnemonic_label to word.mnemonic!!)
            }
            val extras = buildList {
                if (shown(VocabCardField.ROOT) && !word.root.isNullOrBlank()) add(word.root!!)
                if (shown(VocabCardField.ATTESTED) && attestedLabel != null) add(attestedLabel)
                if (shown(VocabCardField.HYPHENATION) && word.hyphenation.size > 1) add(word.hyphenation.joinToString("·"))
                if (shown(VocabCardField.RHYMES) && !word.rhymes.isNullOrBlank()) add(word.rhymes!!)
                if (shown(VocabCardField.FORMS) && word.forms.isNotEmpty()) add(word.forms.joinToString(", "))
            }
            if (extras.isNotEmpty()) add(R.string.ime_vocab_more_label to extras.joinToString("  ·  "))
        }
        items(sections, key = { "sec${it.first}" }) { (labelRes, body) ->
            VocabCollapsible(stringResource(labelRes), body, word.word)
        }
        item(key = "manage") {
            Text(
                stringResource(R.string.ime_vocab_manage_action),
                color = kb.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenRoute(VOCAB_TOOL_ROUTE) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<T>,
    crossinline content: @Composable (Int, T) -> Unit,
) {
    for (index in items.indices) {
        item(key = "sense$index") { content(index, items[index]) }
    }
}

@Composable
private fun VocabSenseRow(
    index: Int,
    sense: VocabSense,
    serif: FontFamily,
    shown: (VocabCardField) -> Boolean,
) {
    val kb = LocalKbTheme.current
    Row(modifier = Modifier.padding(top = if (index == 0) 8.dp else 5.dp)) {
        Text(
            "${index + 1}.",
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            modifier = Modifier.width(20.dp),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sense.pos.isNotEmpty()) {
                    Text(
                        sense.pos,
                        color = kb.accent,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        fontFamily = serif,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (shown(VocabCardField.TAGS)) {
                    for (tag in sense.tags.take(3)) {
                        Spacer(Modifier.width(5.dp))
                        VocabPill(tag, serif)
                    }
                }
            }
            Text(sense.definition, color = kb.modifierKeyText, fontSize = 13.sp, lineHeight = 17.sp)
            if (shown(VocabCardField.EXAMPLES) && sense.example != null) {
                Text(
                    "“${sense.example}”",
                    color = kb.toolbarIcon,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontStyle = FontStyle.Italic,
                    fontFamily = serif,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (shown(VocabCardField.QUOTATIONS)) {
                for (quote in sense.quotations) {
                    Text(
                        "“${quote.text}” — ${quote.ref}",
                        color = kb.toolbarIcon,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = serif,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (shown(VocabCardField.TOPICS) && sense.topics.isNotEmpty()) {
                Text(sense.topics.joinToString(", "), color = kb.toolbarIcon, fontSize = 11.sp)
            }
        }
    }
}

/** Label plus a scrolling row of related words: tap follows the setting, hold always inserts. */
@Composable
private fun VocabWordChipRow(label: String, words: List<String>, callbacks: VocabCallbacks) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = kb.toolbarIcon, fontSize = 11.sp, modifier = Modifier.width(66.dp))
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (word in words.take(12)) {
                VocabChip(
                    label = word,
                    filled = false,
                    onClick = { callbacks.onRelated(word) },
                    onLongClick = { callbacks.onInsert(word) },
                )
            }
        }
    }
}

@Composable
private fun VocabCollapsible(label: String, body: String, key: String) {
    val kb = LocalKbTheme.current
    var expanded by remember(key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = kb.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = kb.toolbarIcon,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            body,
            color = if (expanded) kb.modifierKeyText else kb.toolbarIcon,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VocabPill(text: String, serif: FontFamily) {
    val kb = LocalKbTheme.current
    Text(
        text,
        color = kb.accent,
        fontSize = 10.sp,
        fontStyle = FontStyle.Italic,
        fontFamily = serif,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(kb.accent.copy(alpha = if (kb.dark) 0.18f else 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun VocabChip(
    label: String,
    filled: Boolean,
    focused: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Box(
        modifier = Modifier
            .focusRing(focused, shape)
            .clip(shape)
            .background(if (filled) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { { feedback(); it() } },
            )
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

// ---- browse ----

@Composable
private fun VocabBrowse(state: KeyboardUiState, callbacks: VocabCallbacks) {
    val kb = LocalKbTheme.current
    val browse = state.vocab.browse
    val query = state.mediaQuery.trim().lowercase()
    val rows = remember(browse.rows, query) {
        if (query.isEmpty()) browse.rows else browse.rows.filter { it.word.contains(query) || it.definition.contains(query, ignoreCase = true) }
    }
    val chips = listOf<Pair<String, () -> Unit>>(
        stringResource(R.string.ime_vocab_filter_all_packs) to { callbacks.onBrowsePack(null) },
    ) + browse.packs.map { pack -> pack.name to { callbacks.onBrowsePack(pack.id) } }
    PanelFocusTarget(
        panel = PanelMode.VOCABULARY,
        count = chips.size,
        columns = chips.size,
        region = FocusRegion.CHIPS,
        onActivate = { index -> chips.getOrNull(index)?.second?.invoke() },
    )
    PanelFocusTarget(
        panel = PanelMode.VOCABULARY,
        count = rows.size,
        columns = 1,
        onActivate = { index -> rows.getOrNull(index)?.let { callbacks.onOpen(it.word) } },
    )
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex()
    val listState = rememberLazyListState()
    ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEachIndexed { index, (label, action) ->
                val selected = if (index == 0) browse.packId == null else browse.packs.getOrNull(index - 1)?.id == browse.packId
                VocabChip(label = label, filled = selected, focused = focusedChip == index, onClick = action)
            }
            Spacer(Modifier.width(6.dp))
            for (filter in VocabBrowseFilter.entries) {
                VocabChip(
                    label = stringResource(filter.labelRes()),
                    filled = browse.filter == filter,
                    onClick = { callbacks.onBrowseFilter(filter) },
                )
            }
        }
        if (rows.isEmpty()) {
            VocabMessage(stringResource(R.string.ime_vocab_browse_empty))
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(rows.size, key = { rows[it].word }) { index ->
                val row = rows[index]
                val learnt = row.word in browse.learnt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(focusedRow == index, RoundedCornerShape(8.dp))
                        .clickable { callbacks.onOpen(row.word) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.word, color = kb.modifierKeyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            listOfNotNull(row.pos.firstOrNull(), row.definition.takeIf { it.isNotBlank() }).joinToString(" · "),
                            color = kb.toolbarIcon,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (learnt) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun VocabBrowseFilter.labelRes(): Int = when (this) {
    VocabBrowseFilter.ALL -> R.string.ime_vocab_filter_all
    VocabBrowseFilter.UNLEARNT -> R.string.ime_vocab_filter_unlearnt
    VocabBrowseFilter.LEARNT -> R.string.ime_vocab_filter_learnt
}

// ---- review ----

@Composable
private fun VocabReview(state: KeyboardUiState, callbacks: VocabCallbacks) {
    val kb = LocalKbTheme.current
    val review = state.vocab.review
    val settings = state.settings.vocabulary
    val serif = remember { KeyboardFonts.googleFamily("Lora") }
    val current = review.current
    if (current == null) {
        VocabMessage(
            text = stringResource(if (review.done > 0) R.string.ime_vocab_review_done else R.string.ime_vocab_review_empty),
            action = stringResource(R.string.ime_vocab_tab_browse),
        ) { callbacks.onTab(VocabTab.BROWSE) }
        return
    }
    val grades = if (settings.scheduler == VocabScheduler.SM2) {
        listOf(ReviewGrade.AGAIN, ReviewGrade.HARD, ReviewGrade.GOOD, ReviewGrade.EASY)
    } else {
        listOf(ReviewGrade.AGAIN, ReviewGrade.GOOD, ReviewGrade.EASY)
    }
    val actions: List<Pair<String, () -> Unit>> = if (review.flipped) {
        grades.map { grade -> stringResource(grade.labelRes()) to { callbacks.onReview(grade) } }
    } else {
        listOf(stringResource(R.string.ime_vocab_review_flip_hint) to { callbacks.onFlip() })
    }
    PanelFocusTarget(
        panel = PanelMode.VOCABULARY,
        count = actions.size,
        columns = actions.size,
        region = FocusRegion.ACTIONS,
        onActivate = { index -> actions.getOrNull(index)?.second?.invoke() },
    )
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val rotation by animateFloatAsState(if (review.flipped) 180f else 0f, label = "flip")
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(
            stringResource(R.string.ime_vocab_review_progress, review.done + 1, review.total),
            color = kb.toolbarIcon,
            fontSize = 11.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
                .graphicsLayer {
                    rotationY = if (state.settings.reduceMotion) 0f else rotation
                    cameraDistance = 12f * density
                }
                .clip(kb.cardShape())
                .background(kb.chip)
                .chipBorder(kb, kb.cardShape())
                .clickable { callbacks.onFlip() }
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val mirrored = !state.settings.reduceMotion && rotation > 90f
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { rotationY = if (mirrored) 180f else 0f },
            ) {
                if (!review.flipped) {
                    Text(current.word, color = kb.modifierKeyText, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = serif)
                    val ipa = current.ipaFor(settings.accent)
                    if (ipa != null) Text(ipa, color = kb.toolbarIcon, fontSize = 13.sp)
                    IconButton(onClick = { callbacks.onSpeak(current) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Text(current.word, color = kb.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = serif)
                    Text(
                        current.definition,
                        color = kb.modifierKeyText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    current.senses.firstOrNull()?.example?.let {
                        Text(
                            "“$it”",
                            color = kb.toolbarIcon,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic,
                            fontFamily = serif,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            actions.forEachIndexed { index, (label, action) ->
                VocabChip(label = label, filled = review.flipped && index == actions.lastIndex, focused = focused == index, onClick = action)
            }
        }
    }
}

private fun ReviewGrade.labelRes(): Int = when (this) {
    ReviewGrade.AGAIN -> R.string.ime_vocab_review_again
    ReviewGrade.HARD -> R.string.ime_vocab_review_hard
    ReviewGrade.GOOD -> R.string.ime_vocab_review_good
    ReviewGrade.EASY -> R.string.ime_vocab_review_easy
}

// ---- the strip's word-of-the-day chip ----

/** A narrow accent chip like the smart chip, with a close button: shown once a day. */
@Composable
internal fun VocabDailyChip(
    word: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(tint.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .chipBorder(kb, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onOpen()
                }
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.School, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            Text(
                stringResource(R.string.ime_smart_vocab_daily, word),
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
        IconButton(onClick = { feedback(); onDismiss() }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_smart_vocab_daily_dismiss_desc),
                tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Settings routes the panel links to; `SettingsRoutesTest` scans these constants. */
internal const val VOCAB_PACKS_ROUTE = "vocab/packs"
internal const val VOCAB_TOOL_ROUTE = "tool/VOCABULARY"

/** Whether the vocabulary tool is on, for the Dictionary panel's "Add to vocab" chip. */
internal fun vocabToolOn(state: KeyboardUiState): Boolean =
    ToolbarTool.VOCABULARY in usableTools(state.settings)
