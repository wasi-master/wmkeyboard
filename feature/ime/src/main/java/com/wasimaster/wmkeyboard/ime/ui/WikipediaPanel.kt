package com.wasimaster.wmkeyboard.ime.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloseFullscreen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInFull
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.tools.WikipediaClient
import com.wasimaster.wmkeyboard.ime.WikiUi
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext

/** Tabs on an open Wikipedia article. */
private enum class WikiTab(@StringRes val labelRes: Int) {
    SUMMARY(R.string.ime_wiki_tab_summary_label),
    LINKS(R.string.ime_wiki_tab_links_label),
    FULL(R.string.ime_wiki_tab_full_label),
}

/**
 * Wikipedia as a full-bleed tool: the search bar in the header next to the
 * way back, and the rows above the keys handed to the article. The query
 * types on the key rows like the other search panels; while it does, the
 * panel collapses to the header.
 */
@Composable
internal fun WikipediaPanelHost(
    state: KeyboardUiState,
    onClose: () -> Unit,
    onQueryTap: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onLoadLinks: () -> Unit,
    onLoadFull: () -> Unit,
    onInsert: (String) -> Unit,
) {
    // An open article can grow the panel up the screen to read in (#348).
    // Panel-local: it resets with the panel, and search results never grow.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reading = state.wiki is WikiUi.Article && !state.mediaSearchActive
    FullBleedTool(
        state, title = "",
        onClose = onClose,
        // As tall as the screen allows: the FullBleedTool fit cuts it down to
        // the share of the screen a panel may take.
        extraHeight = if (expanded && reading) LocalConfiguration.current.screenHeightDp.dp else 0.dp,
        compact = state.mediaSearchActive,
        compactHeight = FullBleedHeaderHeight,
        headerActions = {
            MediaHeaderSearchBar(
                state = state,
                placeholder = stringResource(R.string.ime_wiki_search_hint),
                activePlaceholder = stringResource(R.string.ime_wiki_search_active_hint),
                onQueryTap = onQueryTap,
            )
        },
    ) {
        WikipediaPanel(
            state = state,
            expanded = expanded,
            onExpand = { expanded = !expanded },
            onRetry = onRetry,
            onOpen = onOpen,
            onBack = onBack,
            onLoadLinks = onLoadLinks,
            onLoadFull = onLoadFull,
            onInsert = onInsert,
        )
    }
}

/**
 * Wikipedia in the floating box (#1265): the panel's search bar over the
 * same results and articles. No collapse while the query is typed, since the
 * keys are the keyboard's and not under it, and no growing up the screen,
 * since the box is as big as it was dragged.
 */
@Composable
internal fun WikipediaBoxPanel(
    state: KeyboardUiState,
    onQueryTap: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onLoadLinks: () -> Unit,
    onLoadFull: () -> Unit,
    onInsert: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FullBleedHeaderHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaHeaderSearchBar(
                state = state,
                placeholder = stringResource(R.string.ime_wiki_search_hint),
                activePlaceholder = stringResource(R.string.ime_wiki_search_active_hint),
                onQueryTap = onQueryTap,
            )
        }
        androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            WikipediaPanel(
                state = state,
                expanded = false,
                onExpand = null,
                onRetry = onRetry,
                onOpen = onOpen,
                onBack = onBack,
                onLoadLinks = onLoadLinks,
                onLoadFull = onLoadFull,
                onInsert = onInsert,
            )
        }
    }
}

/**
 * Wikipedia in the tool viewbox: search results, then an article with
 * summary, its outgoing links and the full text — each insertable at the
 * cursor.
 */
@Composable
private fun WikipediaPanel(
    state: KeyboardUiState,
    /** The panel grown up the screen for reading (#348). */
    expanded: Boolean,
    /** Grows or shrinks it; null where there is no growing (the floating box, #1265). */
    onExpand: (() -> Unit)?,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onLoadLinks: () -> Unit,
    onLoadFull: () -> Unit,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    Column(modifier = Modifier.fillMaxSize()) {
        // Search mode: only the header's query bar shows, keys underneath type into it.
        if (state.mediaSearchActive) return@Column

        when (val wiki = state.wiki) {
            WikiUi.Idle -> WikiMessage(
                stringResource(R.string.ime_wiki_idle_info, state.settings.webSearch.wikiLanguage),
            )
            WikiUi.Loading -> WikiMessage(stringResource(CommonR.string.common_loading))
            is WikiUi.Error -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    wiki.message,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                ToolPanelChip(stringResource(CommonR.string.common_retry)) { onRetry() }
            }
            is WikiUi.SearchResults -> {
                if (wiki.results.isEmpty()) {
                    // An offline Wikipedia such as Kiwix may have it (#970).
                    WikiMessage(stringResource(R.string.ime_wiki_no_results_empty, wiki.query), lookupWord = wiki.query)
                } else {
                    // Published from inside this branch, so the panel-local tab
                    // state never has to be hoisted to say what is on screen.
                    PanelFocusTarget(
                        panel = PanelMode.WIKIPEDIA,
                        count = wiki.results.size,
                        columns = 1,
                        onActivate = { index ->
                            wiki.results.getOrNull(index)?.let { onOpen(it.title) }
                        },
                    )
                    val focused = state.focusedIndex()
                    val listState = rememberLazyListState()
                    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        items(wiki.results.size) { index ->
                            val result = wiki.results[index]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpen(result.title) }
                                    .focusRing(index == focused, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    result.title,
                                    color = kb.modifierKeyText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (result.description.isNotBlank()) {
                                    Text(
                                        result.description,
                                        color = kb.secondaryText,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is WikiUi.Article -> WikiArticle(
                focusedLink = state.focusedIndex(),
                expanded = expanded,
                onExpand = onExpand,
                wiki = wiki,
                markdownLinks = state.settings.webSearch.wikiLinksMarkdown,
                lang = state.settings.webSearch.wikiLanguage,
                onBack = onBack,
                onOpen = onOpen,
                onLoadLinks = onLoadLinks,
                onLoadFull = onLoadFull,
                onInsert = onInsert,
            )
        }
    }
}

@Composable
private fun WikiArticle(
    wiki: WikiUi.Article,
    /** The link the hardware focus ring is on, or null in touch mode. */
    focusedLink: Int?,
    /** The panel grown up the screen for reading (#348). */
    expanded: Boolean,
    /** Grows or shrinks it; null where there is no growing (the floating box, #1265). */
    onExpand: (() -> Unit)?,
    markdownLinks: Boolean,
    lang: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onLoadLinks: () -> Unit,
    onLoadFull: () -> Unit,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    var tab by rememberSaveable(wiki.summary.title) { mutableStateOf(WikiTab.SUMMARY) }
    val linkText = remember(wiki.summary, markdownLinks) {
        if (markdownLinks) "[${wiki.summary.title}](${wiki.summary.url})" else wiki.summary.url
    }
    // Ask AI (#352): about a passage, from a long press on the text, or about
    // the whole article from the header chip.
    val askLabel = stringResource(R.string.ime_ask_ai_label_wiki, wiki.summary.title)
    val ask = remember(wiki.summary.title, askLabel) {
        AskAiSource(AskAiContext.wikipediaSource(wiki.summary.title), askLabel)
    }
    // Quote (#348): a selected passage with the article it came from, as a
    // link when links are inserted as markdown, else with the bare address.
    val quoteMarkdown = stringResource(R.string.ime_wiki_quote_markdown)
    val quotePlain = stringResource(R.string.ime_wiki_quote_plain)
    val quote = remember(wiki.summary, markdownLinks, quoteMarkdown, quotePlain) {
        { passage: String ->
            String.format(
                if (markdownLinks) quoteMarkdown else quotePlain,
                passage.trim(),
                wiki.summary.title,
                wiki.summary.url,
            )
        }
    }
    val context = LocalContext.current
    val select: (WikiTab) -> Unit = { wikiTab ->
        tab = wikiTab
        when (wikiTab) {
            WikiTab.LINKS -> if (wiki.links == null) onLoadLinks()
            WikiTab.FULL -> if (wiki.fullText == null) onLoadFull()
            WikiTab.SUMMARY -> {}
        }
    }
    val tabs = WikiTab.entries

    Column(Modifier.fillMaxSize().swipeTabs(tab.ordinal, tabs.size) { select(tabs[it]) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wiki.canGoBack) {
                IconHint(stringResource(R.string.ime_wiki_back_desc)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.ime_wiki_back_desc),
                            tint = kb.toolbarIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                wiki.summary.title,
                color = kb.modifierKeyText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Read it bigger, here or in the browser (#348).
            if (onExpand != null) {
                IconHint(
                    stringResource(
                        if (expanded) R.string.ime_wiki_shrink_desc else R.string.ime_wiki_expand_desc,
                    ),
                ) {
                    IconButton(onClick = onExpand, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (expanded) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                            contentDescription = stringResource(
                                if (expanded) R.string.ime_wiki_shrink_desc else R.string.ime_wiki_expand_desc,
                            ),
                            tint = kb.toolbarIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (wiki.summary.url.isNotBlank()) {
                IconHint(stringResource(R.string.ime_wiki_open_browser_desc)) {
                    IconButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(wiki.summary.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.ime_wiki_open_browser_desc),
                            tint = kb.toolbarIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        // The tabs, then what can be done with the whole article: Ask AI
        // (#352) and Copy (#348). A row of their own, since the title's row
        // has no room left for five chips on a phone.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (wikiTab in WikiTab.entries) {
                ToolPanelChip(stringResource(wikiTab.labelRes), selected = tab == wikiTab) { select(wikiTab) }
            }
            Spacer(Modifier.width(8.dp))
            AskAiChip(askLabel) { AskAiContext.wikipedia(wiki.summary, wiki.fullText) }
            CopyTextChip {
                // Formulas written out in Unicode: a text field cannot typeset TeX (#937).
                WikipediaClient.plainMath(
                    (wiki.fullText?.takeIf { it.isNotBlank() } ?: wiki.summary.extract).trim(),
                ) + "\n\n" + wiki.summary.url
            }
            // The article in an offline reader such as Kiwix or Aard 2 (#970).
            LookupAppChip { wiki.summary.title }
        }
        when (tab) {
            WikiTab.SUMMARY -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolPanelChip(stringResource(R.string.ime_wiki_insert_summary_action)) {
                            if (wiki.summary.extract.isNotBlank()) onInsert(wiki.summary.extract)
                        }
                        ToolPanelChip(stringResource(R.string.ime_wiki_insert_link_action)) {
                            onInsert(linkText)
                        }
                        ToolPanelChip(stringResource(R.string.ime_wiki_insert_title_action)) {
                            onInsert(wiki.summary.title)
                        }
                    }
                }
                if (wiki.summary.description.isNotBlank()) {
                    item {
                        Text(
                            wiki.summary.description,
                            color = kb.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                item {
                    if (wiki.summary.extract.isBlank()) {
                        Text(
                            stringResource(R.string.ime_wiki_no_summary_empty),
                            color = kb.modifierKeyText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        // A long press selects: Ask AI, Copy or Insert (#352).
                        SelectableText(
                            wiki.summary.extract,
                            color = kb.modifierKeyText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 4.dp),
                            ask = ask,
                            quote = quote,
                        )
                    }
                }
            }
            WikiTab.LINKS -> when {
                wiki.loadingExtra && wiki.links == null ->
                    WikiMessage(stringResource(R.string.ime_wiki_links_progress))
                wiki.links.isNullOrEmpty() ->
                    WikiMessage(stringResource(R.string.ime_wiki_links_empty))
                else -> {
                    PanelFocusTarget(
                        panel = PanelMode.WIKIPEDIA,
                        count = wiki.links.size,
                        columns = 1,
                        onActivate = { index -> wiki.links.getOrNull(index)?.let(onOpen) },
                    )
                    val focused = focusedLink
                    val listState = rememberLazyListState()
                    ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
                    LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    items(wiki.links.size) { index ->
                        val title = wiki.links[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpen(title) }
                                .focusRing(index == focused, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                color = kb.modifierKeyText,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            ToolPanelChip(stringResource(R.string.ime_wiki_insert_action)) {
                                val url = com.wasimaster.wmkeyboard.core.tools.WikipediaClient
                                    .articleUrl(title, lang)
                                onInsert(if (markdownLinks) "[$title]($url)" else url)
                            }
                        }
                    }
                }
                }
            }
            WikiTab.FULL -> when {
                wiki.loadingExtra && wiki.fullText == null ->
                    WikiMessage(stringResource(R.string.ime_wiki_full_progress))
                wiki.fullText.isNullOrBlank() ->
                    WikiMessage(stringResource(R.string.ime_wiki_full_error))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    // Paragraph-per-item keeps huge articles scrollable; each
                    // paragraph is insertable on its own with a tap, and a
                    // long press selects out of it (#352).
                    val paragraphs = wiki.fullText.split("\n\n").filter { it.isNotBlank() }
                    items(paragraphs.size) { index ->
                        val paragraph = paragraphs[index]
                        // A paragraph with a formula in it is typeset, the
                        // way a chat answer's maths is (#937); what it
                        // inserts has the formula written out in Unicode.
                        if (WikipediaClient.hasMath(paragraph)) {
                            ChatMarkdown(
                                paragraph,
                                ChatMarkdownColors(text = kb.modifierKeyText, dim = kb.secondaryText, codeBackground = kb.chip),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                fontSize = 13.sp,
                                onBlockTap = { onInsert(WikipediaClient.plainMath(paragraph)) },
                                selectable = true,
                                ask = ask,
                            )
                            return@items
                        }
                        SelectableText(
                            paragraph,
                            color = if (paragraph.startsWith("==")) kb.accent else kb.modifierKeyText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            ask = ask,
                            quote = quote,
                            onTap = { onInsert(paragraph) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiMessage(text: String, lookupWord: String? = null) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = LocalKbTheme.current.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (lookupWord != null) LookupAppChip(Modifier.padding(top = 8.dp)) { lookupWord }
    }
}
