package com.wasimaster.wmkeyboard.ime.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.files.FileCategory
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindInPage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.DateFilter
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.FilterChip
import com.wasimaster.wmkeyboard.ime.files.SearchFilters
import com.wasimaster.wmkeyboard.ime.files.SizeFilter
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip

/** The kinds a search narrows to: the categories, apart from large files, which the size chip covers. */
private val TYPE_CHOICES = listOf(
    FileCategory.IMAGES, FileCategory.VIDEOS, FileCategory.AUDIO, FileCategory.DOCUMENTS, FileCategory.APKS, FileCategory.ARCHIVES,
)

/**
 * The filters under the search box (6A): a type, a size and a date, each a
 * chip whose choices open in a row beneath it, and "In contents", which reads
 * text files for the words as well as their names.
 */
@Composable
internal fun SearchFiltersRow(filters: SearchFilters, act: (FileAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolPanelChip(
            filters.category?.let { stringResource(categoryLabel(it)) } ?: stringResource(R.string.ime_files_filter_any_type),
            selected = filters.category != null || filters.open == FilterChip.TYPE,
        ) { act(FileAction.OpenFilter(FilterChip.TYPE)) }
        ToolPanelChip(
            stringResource(sizeLabel(filters.size)),
            selected = filters.size != SizeFilter.ANY || filters.open == FilterChip.SIZE,
        ) { act(FileAction.OpenFilter(FilterChip.SIZE)) }
        ToolPanelChip(
            stringResource(dateLabel(filters.date)),
            selected = filters.date != DateFilter.ANY || filters.open == FilterChip.DATE,
        ) { act(FileAction.OpenFilter(FilterChip.DATE)) }
        ToolPanelChip(stringResource(R.string.ime_files_filter_contents), selected = filters.inContents) { act(FileAction.ToggleInContents) }
    }
    when (filters.open) {
        FilterChip.TYPE -> ChoiceRow {
            ToolPanelChip(stringResource(R.string.ime_files_filter_any_type), selected = filters.category == null) { act(FileAction.SetCategoryFilter(null)) }
            TYPE_CHOICES.forEach { category ->
                ToolPanelChip(stringResource(categoryLabel(category)), selected = filters.category == category) { act(FileAction.SetCategoryFilter(category)) }
            }
        }
        FilterChip.SIZE -> ChoiceRow {
            SizeFilter.entries.forEach { size ->
                ToolPanelChip(stringResource(sizeLabel(size)), selected = filters.size == size) { act(FileAction.SetSizeFilter(size)) }
            }
        }
        FilterChip.DATE -> ChoiceRow {
            DateFilter.entries.forEach { date ->
                ToolPanelChip(stringResource(dateLabel(date)), selected = filters.date == date) { act(FileAction.SetDateFilter(date)) }
            }
        }
        null -> Unit
    }
}

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier.fillMaxWidth().background(kb.chip.copy(alpha = 0.35f)).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

private fun sizeLabel(size: SizeFilter): Int = when (size) {
    SizeFilter.ANY -> R.string.ime_files_filter_any_size
    SizeFilter.SMALL -> R.string.ime_files_filter_small
    SizeFilter.MEDIUM -> R.string.ime_files_filter_medium
    SizeFilter.LARGE -> R.string.ime_files_filter_large
}

private fun dateLabel(date: DateFilter): Int = when (date) {
    DateFilter.ANY -> R.string.ime_files_filter_any_time
    DateFilter.TODAY -> R.string.ime_files_filter_today
    DateFilter.WEEK -> R.string.ime_files_filter_week
    DateFilter.MONTH -> R.string.ime_files_filter_month
    DateFilter.YEAR -> R.string.ime_files_filter_year
}

/**
 * The searches made lately, while the box is open and still empty; the first
 * letter typed puts the results in their place (the owner's addition to 6A).
 */
@Composable
internal fun RecentSearches(searches: List<String>, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    if (searches.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FindInPage, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ime_files_search_start), color = kb.secondaryText, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel(stringResource(R.string.ime_files_section_searches)) }
        items(searches, key = { it }) { text ->
            Row(
                Modifier.fillMaxWidth().clickable { act(FileAction.UseSearch(text)) }.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, color = kb.suggestionText, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_files_search_forget),
                    tint = kb.secondaryText,
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable { act(FileAction.ForgetSearch(text)) }.padding(8.dp),
                )
            }
        }
    }
}
