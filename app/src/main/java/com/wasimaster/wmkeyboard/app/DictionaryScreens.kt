package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.prediction.PendingLearn
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import kotlinx.coroutines.launch

// ---- personal dictionary ----

/**
 * Word count past which the personal dictionary grows a search field. Below
 * it the list is short enough to scan, and a search box would be furniture.
 */
private const val DICTIONARY_SEARCH_THRESHOLD = 12
/** Rows the word lists draw per page; the next page is a tap away. */
private const val WORD_LIST_PAGE = 100
/** The "Show N more" row at the foot of a paged word list. */
@Composable
private fun ShowMoreWordsRow(remaining: Int, onClick: () -> Unit) {
    WmRow(
        title = pluralStringResource(R.plurals.backup_word_list_show_more, remaining, remaining),
        icon = Icons.Outlined.ExpandMore,
        onClick = onClick,
    )
}
/**
 * The learned-words file, edited directly from the settings app. Every
 * change bumps the DataStore lexicon version so the IME (which holds its
 * own in-memory copy) reloads from disk instead of clobbering the edit.
 */
@Composable
internal fun DictionarySettings(repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, "learning/user_lexicon.json") }
    // UserLexicon's constructor reads and JSON-parses the whole learned-words
    // file, so it (and every save) runs on Dispatchers.IO, never in composition
    // or on a click handler. The list draws empty for a moment then fills in.
    var lexicon by remember { mutableStateOf<UserLexicon?>(null) }
    var words by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var showTidy by remember { mutableStateOf(false) }
    // The row being edited (#47): its spelling and weight, as they are now.
    var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(Unit) {
        val lex = withContext(Dispatchers.IO) { UserLexicon(file) }
        words = lex.allWords().sortedByDescending { it.second }
        lexicon = lex
    }

    fun persist(mutate: (UserLexicon) -> Unit) {
        val lex = lexicon ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = lex.allWords().mapTo(HashSet()) { it.first }
                mutate(lex)
                lex.save()
                // A word deleted here must not walk back in on the sightings
                // it had already collected: the waiting room is its own file,
                // so it is told separately (#48).
                val gone = before - lex.allWords().mapTo(HashSet()) { it.first }
                if (gone.isNotEmpty()) {
                    PendingLearn(java.io.File(context.filesDir, "learning/pending_learn.json")).apply {
                        for (word in gone) forget(word)
                        save()
                    }
                }
            }
            words = lex.allWords().sortedByDescending { it.second }
            repository.bumpLexiconVersion()
        }
    }

    // Words seen exactly once. Older versions learned every word the first time
    // it was committed, so for anyone upgrading this is where the swipe
    // misfires and mistyped words are — the clean-out the dictionary needed and
    // had no way to do short of deleting entries one at a time. Words the user
    // added by hand carry a boost far above 1 and are never in here.
    val seenOnce = remember(words) { words.filter { it.second <= 1 }.map { it.first } }
    RegisterAddFab(stringResource(R.string.backup_add_word_action)) { showAdd = true }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (seenOnce.isNotEmpty()) {
            OutlinedButton(onClick = { showTidy = true }) {
                Text(stringResource(R.string.backup_tidy_words_action))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // The lexicon holds up to 10,000 words and used to render as one flat
    // count-sorted list, which made finding a single word to delete a scroll
    // through everything the keyboard has ever learned.
    var query by remember { mutableStateOf("") }
    if (words.size > DICTIONARY_SEARCH_THRESHOLD || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(CommonR.string.common_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(CommonR.string.common_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val shown = remember(words, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) words else words.filter { needle in it.first.lowercase() }
    }
    if (words.isEmpty()) {
        CaptionText(stringResource(R.string.backup_dictionary_empty))
    } else if (shown.isEmpty()) {
        CaptionText(stringResource(R.string.backup_dictionary_no_matches, query))
    }
    // Drawn in pages. The screen is not a lazy list (see WmScreen), so every
    // row here is composed at once, and a dictionary of thousands of words
    // ran the app out of memory on the way in (#75).
    var visible by remember(shown) { mutableIntStateOf(WORD_LIST_PAGE) }
    SettingsGroup {
        for ((word, count) in shown.take(visible)) {
            item {
                WmRow(
                    title = word,
                    subtitle = if (count >= 200) {
                        stringResource(R.string.backup_dictionary_added_subtitle)
                    } else {
                        pluralStringResource(R.plurals.backup_dictionary_seen_count, count, count)
                    },
                    trailing = {
                        IconButton(onClick = { persist { it.forget(word) } }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.backup_delete_word_desc, word),
                            )
                        }
                    },
                    onClick = { editing = word to count },
                )
            }
        }
        if (shown.size > visible) {
            item { ShowMoreWordsRow(shown.size - visible) { visible += WORD_LIST_PAGE } }
        }
    }

    editing?.let { (word, count) ->
        EditWordDialog(
            word = word,
            weight = count,
            onDismiss = { editing = null },
            onConfirm = { newWord, newWeight ->
                persist { lex ->
                    // Respell first, so the weight lands on the word that is
                    // left. A respelling that merges into an existing word
                    // ends at the typed weight rather than the sum, which is
                    // the number the dialog showed as the outcome.
                    val target = if (lex.rename(word, newWord)) newWord else word
                    lex.setCount(target, newWeight)
                }
                editing = null
            },
        )
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.backup_add_word_title)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.backup_word_field_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        persist { it.addWord(input.trim()) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    if (showTidy) {
        AlertDialog(
            onDismissRequest = { showTidy = false },
            title = { Text(stringResource(R.string.backup_tidy_words_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.backup_tidy_words_body,
                        seenOnce.size,
                        seenOnce.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist { it.forgetAll(seenOnce) }
                        showTidy = false
                    },
                ) { Text(stringResource(CommonR.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showTidy = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * Size of one press of the weight stepper: a single count under 10, a tenth
 * of the next power of ten above it. Ten presses cover each decade, so a
 * word can go from one sighting to "added" territory without a keyboard.
 */
private fun weightStep(weight: Int): Int = when {
    weight < 10 -> 1
    weight < 100 -> 10
    weight < 1_000 -> 100
    weight < 10_000 -> 1_000
    weight < 100_000 -> 10_000
    else -> 100_000
}
/**
 * The edit dialog a personal dictionary row opens (#47): respell the word,
 * and raise or lower its weight. The weight is the same count the row's
 * subtitle reads out, typed directly or stepped a decade at a time. [onConfirm]
 * gets the trimmed spelling and a weight already inside the lexicon's bounds.
 */
@Composable
private fun EditWordDialog(
    word: String,
    weight: Int,
    onDismiss: () -> Unit,
    onConfirm: (word: String, weight: Int) -> Unit,
) {
    var spelling by remember(word) { mutableStateOf(word) }
    var weightText by remember(weight) { mutableStateOf(weight.toString()) }
    val parsed = weightText.trim().toIntOrNull()
    val weightValid = parsed != null && parsed in 1..UserLexicon.MAX_COUNT
    // A respelling that folds to a blank is not a word; one over the length
    // cap would be dropped on the floor by the lexicon, so refuse it here
    // rather than closing as though it worked.
    val spellingValid = spelling.isNotBlank() && spelling.trim().length <= UserLexicon.MAX_WORD_LENGTH
    fun step(direction: Int) {
        val now = parsed ?: weight
        val size = if (direction < 0) weightStep(now - 1) else weightStep(now)
        weightText = (now + direction * size).coerceIn(1, UserLexicon.MAX_COUNT).toString()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_edit_word_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = spelling,
                    onValueChange = { spelling = it },
                    label = { Text(stringResource(R.string.backup_word_field_label)) },
                    singleLine = true,
                    isError = !spellingValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { step(-1) },
                        enabled = (parsed ?: weight) > 1,
                    ) {
                        Icon(
                            Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.backup_weight_lower_desc),
                        )
                    }
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.backup_word_weight_label)) },
                        singleLine = true,
                        isError = !weightValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { step(1) },
                        enabled = (parsed ?: weight) < UserLexicon.MAX_COUNT,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.backup_weight_raise_desc),
                        )
                    }
                }
                Text(
                    if (weightValid) {
                        stringResource(R.string.backup_word_weight_info)
                    } else {
                        stringResource(R.string.backup_word_weight_error, UserLexicon.MAX_COUNT)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (weightValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = spellingValid && weightValid,
                onClick = { onConfirm(spelling.trim(), parsed ?: weight) },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}
// ---- suggestion blacklist ----

/**
 * The never-suggest word list, stored in settings. A blacklisted word is kept
 * out of the suggestion strip and never used as an autocorrect target, but can
 * still be typed and committed normally. Matched case-insensitively.
 */
@Composable
internal fun BlacklistSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val words = remember(settings.suggestionBlacklist) {
        settings.suggestionBlacklist.sorted()
    }
    var showAdd by remember { mutableStateOf(false) }

    RegisterAddFab(stringResource(R.string.backup_add_word_action)) { showAdd = true }
    // Same shape as the personal dictionary above it: a search box once the
    // list is long enough to need one, and pages rather than every row at
    // once — hundreds of blacklisted words composed in one go ran the app
    // out of memory (#75).
    var query by remember { mutableStateOf("") }
    if (words.size > DICTIONARY_SEARCH_THRESHOLD || query.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(CommonR.string.common_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(CommonR.string.common_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    val shown = remember(words, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) words else words.filter { needle in it }
    }
    var visible by remember(shown) { mutableIntStateOf(WORD_LIST_PAGE) }
    if (words.isEmpty()) {
        CaptionText(stringResource(R.string.backup_blacklist_empty))
    } else if (shown.isEmpty()) {
        CaptionText(stringResource(R.string.backup_blacklist_no_matches, query))
    } else {
        // Per-row deletion is the only other way out of here, and the list is
        // DataStore-backed so the Storage screen has nothing to offer either.
        SettingsGroup {
            item {
                ActionRow(
                    title = R.string.backup_blacklist_clear_title,
                    subtitle = pluralStringResource(
                        R.plurals.backup_blacklist_clear_subtitle,
                        words.size,
                        words.size,
                    ),
                    action = stringResource(CommonR.string.common_clear),
                    confirm = stringResource(R.string.backup_blacklist_clear_confirm),
                    lock = AppLockTargets["action_clear_blacklist"],
                ) { scope.launch { repository.clearSuggestionBlacklist() } }
            }
        }
    }
    SettingsGroup {
        for (word in shown.take(visible)) {
            item {
                WmRow(
                    title = word,
                    trailing = {
                        IconButton(onClick = {
                            scope.launch { repository.removeSuggestionBlacklistWord(word) }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.backup_delete_word_desc, word),
                            )
                        }
                    },
                )
            }
        }
        if (shown.size > visible) {
            item { ShowMoreWordsRow(shown.size - visible) { visible += WORD_LIST_PAGE } }
        }
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.backup_add_word_title)) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.backup_word_field_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        scope.launch { repository.addSuggestionBlacklistWord(input) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
// ---- clipboard phone formats ----

/**
 * The phone-number shapes the clipboard detector keeps.
 *
 * With no format in the list every number-shaped run of digits becomes a chip,
 * which is the only thing the detector can do before it knows where the user
 * lives, and where its false positives come from: an invoice total and a
 * tracking id have the same shape as a phone number. One format ends that.
 *
 * A format is a mask, and the user writes it by giving a number they copy
 * often. The dial code stays literal and every other digit becomes an X, which
 * they can type back over to pin a digit their numbers always have.
 */
@Composable
internal fun PhoneFormatSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val formats = remember(settings.clipboard.phoneFormats) {
        settings.clipboard.phoneFormats.sorted()
    }
    val masks = remember(formats) { PhoneFormats.parseAll(formats) }
    var showAdd by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf("") }

    RegisterAddFab(stringResource(R.string.phoneformats_add_action)) { showAdd = true }
    if (formats.isEmpty()) {
        CaptionText(stringResource(R.string.phoneformats_empty))
    }
    SettingsGroup {
        for (format in formats) {
            item {
                WmRow(
                    title = format,
                    icon = Icons.Outlined.Phone,
                    trailing = {
                        IconButton(onClick = {
                            scope.launch { repository.removeClipboardPhoneFormat(format) }
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.phoneformats_delete_desc,
                                    format,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
    // A format is a promise about numbers the user cannot see from here, so the
    // screen lets them put one in and watch the answer.
    Text(
        stringResource(R.string.phoneformats_test_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
    )
    OutlinedTextField(
        value = sample,
        onValueChange = { sample = it },
        label = { Text(stringResource(R.string.phoneformats_test_field_label)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (sample.isNotBlank()) {
        val kept = PhoneFormats.matches(sample, masks)
        Text(
            stringResource(
                when {
                    masks.isEmpty() -> R.string.phoneformats_test_all
                    kept -> R.string.phoneformats_test_match
                    else -> R.string.phoneformats_test_no_match
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (kept || masks.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Spacer(Modifier.height(16.dp))

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        val mask = remember(input) { phoneMaskFrom(input) }
        val previewFormat = stringResource(R.string.phoneformats_preview)
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.phoneformats_add_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.phoneformats_add_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.phoneformats_field_label)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mask != null) {
                            previewFormat.format(mask)
                        } else {
                            stringResource(R.string.phoneformats_preview_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = mask != null,
                    onClick = {
                        val value = mask ?: return@TextButton
                        scope.launch { repository.addClipboardPhoneFormat(value) }
                        showAdd = false
                    },
                ) { Text(stringResource(CommonR.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * The mask [raw] stands for, from one field that takes both spellings: a
 * format if the user wrote one (it has an X in it), and otherwise a number to
 * make a format out of. Null while the field holds neither yet.
 */
private fun phoneMaskFrom(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val written = trimmed.any { it == 'X' || it == 'x' || it == '#' }
    return if (written) PhoneFormats.canonical(trimmed) else PhoneFormats.fromExample(trimmed)
}
