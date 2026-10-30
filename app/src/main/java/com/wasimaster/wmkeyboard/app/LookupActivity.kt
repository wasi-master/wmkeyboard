package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.settings.AnkiMode
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LookupCardStyle
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.usableTools
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.tools.anki.AnkiDroid
import com.wasimaster.wmkeyboard.core.tools.lookup.LookupScanner
import com.wasimaster.wmkeyboard.core.tools.yomichan.PitchNotation
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.vocab.VocabAutofill
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabSpeaker
import com.wasimaster.wmkeyboard.core.vocab.spokenLocale
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext
import com.wasimaster.wmkeyboard.ime.lookup.LookupLaunch
import com.wasimaster.wmkeyboard.ime.lookup.WordLookup
import com.wasimaster.wmkeyboard.tools.R as ToolsR
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Look up card (#765): a word from any app, defined over that app.
 *
 * Two ways in. "Look up" in another app's text selection menu
 * (`PROCESS_TEXT`) hands over the selection; a longer one becomes a row of
 * word chips, and the first word is looked up. The floating button's Look up
 * hands over the text around a tapped word and where the finger was
 * ([LookupLaunch]); that one can also be shown as a small card beside the
 * word, with the word marked ([LookupCardStyle.POPUP]).
 *
 * The look-up is the Dictionary tool's ([WordLookup]), so the card answers
 * from the same sources in the same order. Japanese and other text written
 * without spaces is scanned Yomichan's way against the imported dictionaries
 * ([LookupScanner]). From the card the word can be heard, copied, kept in My
 * words, asked about, or sent to AnkiDroid.
 */
class LookupActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        val request = readRequest(intent)
        setContent {
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                LookupScreen(request, settings, onClose = ::finish)
            }
        }
    }

    private fun readRequest(intent: Intent?): LookupRequest {
        if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
            return LookupRequest(text = text.take(MAX_TEXT), offset = -1, fromTap = false)
        }
        val text = intent?.getStringExtra(LookupLaunch.EXTRA_TEXT).orEmpty()
        return LookupRequest(
            text = text,
            offset = intent?.getIntExtra(LookupLaunch.EXTRA_OFFSET, -1) ?: -1,
            fromTap = true,
            rects = intent?.getIntArrayExtra(LookupLaunch.EXTRA_RECTS),
            rectsFrom = intent?.getIntExtra(LookupLaunch.EXTRA_RECTS_FROM, 0) ?: 0,
            bounds = intent?.getIntArrayExtra(LookupLaunch.EXTRA_BOUNDS),
            screenMissing = intent?.getBooleanExtra(LookupLaunch.EXTRA_SCREEN_MISSING, false) == true,
        )
    }

    private companion object {
        /** The most of a selection the card reads. */
        const val MAX_TEXT = 2_000
    }
}

/** What the card was opened with. Rectangles are screen pixels, four ints (left, top, right, bottom) each. */
internal class LookupRequest(
    val text: String,
    /** Where the finger was in [text]; -1 for a selection, or a tap that could not be placed on a character. */
    val offset: Int,
    val fromTap: Boolean,
    val rects: IntArray? = null,
    val rectsFrom: Int = 0,
    val bounds: IntArray? = null,
    val screenMissing: Boolean = false,
) {
    /** The rectangles of [span]'s characters that the app reported. */
    fun rectsOf(span: LookupScanner.Span): List<IntArray> {
        val all = rects ?: return emptyList()
        return (span.start until span.end).mapNotNull { index ->
            val at = (index - rectsFrom) * 4
            if (index < rectsFrom || at + 3 >= all.size) null else all.copyOfRange(at, at + 4)
        }.filter { it[2] > it[0] && it[3] > it[1] }
    }
}

/** Where the card is in its look-up. */
private sealed interface CardState {
    data object Idle : CardState
    data class Loading(val word: String) : CardState

    /** The finger was on no word. */
    data object NoWord : CardState
    data class Done(val word: String, val outcome: WordLookup.Outcome) : CardState
}

/** How sending the word to AnkiDroid went. */
private enum class AnkiState { IDLE, BUSY, ADDED, ALREADY, FAILED }

@Composable
private fun LookupScreen(request: LookupRequest, settings: LiveSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = request.text
    // A tap is one word; a selection longer than one word gets a chip per word.
    val words = remember(text) { if (request.offset >= 0 || text.isBlank()) emptyList() else LookupScanner.words(text) }
    var scan by remember { mutableStateOf<LookupScanner.Scan?>(null) }
    var state by remember { mutableStateOf<CardState>(CardState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun lookAt(offset: Int) {
        job?.cancel()
        job = scope.launch {
            val found = withContext(Dispatchers.IO) {
                YomichanDictionaries.attach(context.filesDir)
                LookupScanner.scan(text, offset)
            }
            scan = found
            if (found == null) {
                state = CardState.NoWord
                return@launch
            }
            val word = found.span.of(text)
            state = CardState.Loading(word)
            val current = settings.value
            val outcome = if (found.entries.isNotEmpty()) {
                WordLookup.found(found.entries, DictionarySource.IMPORTED, current)
            } else {
                WordLookup.lookUp(word, current, context.filesDir, unlocked = isUnlocked(context))
            }
            state = CardState.Done(word, outcome)
            if (outcome is WordLookup.Outcome.Found && outcome.translationsPending) {
                val translations = WordLookup.translationsFor(outcome.entries.first(), current)
                state = CardState.Done(word, outcome.copy(translations = translations, translationsPending = false))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!request.screenMissing && text.isNotBlank()) {
            lookAt(if (request.offset >= 0) request.offset else words.firstOrNull()?.start ?: 0)
        }
    }

    val style = settings.watch { it.lookup.cardStyle }
    val marked = scan?.span?.let(request::rectsOf).orEmpty()
    val anchor = anchorOf(marked) ?: request.bounds?.takeIf { it.size == 4 }
    val card: @Composable () -> Unit = {
        LookupCard(
            request = request,
            settings = settings,
            words = words,
            scan = scan,
            state = state,
            onWord = ::lookAt,
            onClose = onClose,
        )
    }
    BackHandler(onBack = onClose)
    if (style == LookupCardStyle.POPUP && request.fromTap && anchor != null) {
        LookupPopup(anchor, marked, onClose, card)
    } else {
        LookupSheet(onClose, card)
    }
}

private fun isUnlocked(context: Context): Boolean =
    context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

/** The rectangle around every one of [rects], or null for none. */
private fun anchorOf(rects: List<IntArray>): IntArray? {
    if (rects.isEmpty()) return null
    return intArrayOf(rects.minOf { it[0] }, rects.minOf { it[1] }, rects.maxOf { it[2] }, rects.maxOf { it[3] })
}

/** A short sheet from the bottom, the app showing above it; a tap there closes it. */
@Composable
private fun LookupSheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    val noRipple = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * SHEET_HEIGHT)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.navigationBarsPadding()) { content() }
        }
    }
}

/**
 * The card beside the word, as Yomichan shows it: below the word when there
 * is room, else above, kept on screen. The word itself is marked with the
 * theme's accent over the app. No scrim: the app stays as it was, and a tap
 * anywhere off the card closes it.
 */
@Composable
private fun LookupPopup(anchor: IntArray, marked: List<IntArray>, onClose: () -> Unit, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (rect in marked) {
                drawRoundRect(
                    color = accent.copy(alpha = HIGHLIGHT_ALPHA),
                    topLeft = Offset(rect[0].toFloat(), rect[1].toFloat()),
                    size = Size((rect[2] - rect[0]).toFloat(), (rect[3] - rect[1]).toFloat()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        Layout(
            content = {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                ) { content() }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val margin = 12.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val width = minOf(constraints.maxWidth - margin * 2, POPUP_WIDTH.roundToPx())
            val card = measurables.single().measure(
                Constraints(maxWidth = width, maxHeight = (constraints.maxHeight * POPUP_HEIGHT).toInt()),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val below = anchor[3] + gap
                val y = if (below + card.height <= constraints.maxHeight - margin) {
                    below
                } else {
                    max(margin, anchor[1] - gap - card.height)
                }
                val centre = (anchor[0] + anchor[2]) / 2
                val x = (centre - card.width / 2).coerceIn(margin, max(margin, constraints.maxWidth - margin - card.width))
                card.place(x, y)
            }
        }
    }
}

@Composable
private fun LookupCard(
    request: LookupRequest,
    settings: LiveSettings,
    words: List<LookupScanner.Span>,
    scan: LookupScanner.Scan?,
    state: CardState,
    onWord: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (request.screenMissing) {
            ScreenReadingOff()
            return@Column
        }
        if (words.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (span in words) {
                    FilterChip(
                        selected = scan?.span?.let { span.start >= it.start && span.start < it.end } == true,
                        onClick = { onWord(span.start) },
                        label = { Text(span.of(request.text), maxLines = 1) },
                    )
                }
            }
        }
        when (state) {
            CardState.Idle -> Unit
            is CardState.Loading -> {
                Text(
                    stringResource(R.string.lookup_loading, state.word),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            CardState.NoWord -> Message(stringResource(R.string.lookup_no_word))
            is CardState.Done -> when (val outcome = state.outcome) {
                is WordLookup.Outcome.Found -> {
                    LookupEntries(outcome)
                    LookupActions(request, settings, scan, state.word, outcome, onClose)
                }
                WordLookup.Outcome.NotFound -> Message(stringResource(R.string.lookup_not_found, state.word))
                WordLookup.Outcome.NotOnDevice -> Message(stringResource(R.string.lookup_not_on_device, state.word))
                WordLookup.Outcome.Failed -> Message(stringResource(R.string.lookup_failed))
                WordLookup.Outcome.NoSources -> Message(stringResource(R.string.lookup_no_sources))
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Opened from the floating button with screen reading off: what Look up needs, and the way to it. */
@Composable
private fun ScreenReadingOff() {
    val context = LocalContext.current
    Text(stringResource(R.string.lookup_screen_off), style = MaterialTheme.typography.bodyMedium)
    TextButton(
        onClick = {
            context.startActivity(
                SpecialAccessActivity.intent(context, SpecialAccess.SCREEN_CONTENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    ) { Text(stringResource(R.string.ask_ai_screen_off_action)) }
}

@Composable
private fun LookupEntries(found: WordLookup.Outcome.Found) {
    val context = LocalContext.current
    val speaker = remember { lazy { VocabSpeaker(context) } }
    DisposableEffect(Unit) {
        onDispose {
            if (speaker.isInitialized()) speaker.value.shutdown()
        }
    }
    found.entries.forEachIndexed { index, entry ->
        if (index > 0) Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (entry.reading.isNotEmpty()) {
                    Text(entry.reading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(entry.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (entry.phonetic.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.phonetic,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = { pronounce(speaker.value, entry) }) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.lookup_play_desc, entry.word))
            }
        }
        val pitchAndFrequency = entry.pitch.map { PitchNotation.format(entry.reading.ifEmpty { entry.word }, it) } +
            listOfNotNull(entry.frequency.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lookup_frequency, it) })
        if (pitchAndFrequency.isNotEmpty()) {
            Text(
                pitchAndFrequency.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (meaning in entry.meanings) {
            if (meaning.partOfSpeech.isNotEmpty()) {
                Text(
                    meaning.partOfSpeech,
                    style = MaterialTheme.typography.labelLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            meaning.definitions.forEachIndexed { number, definition ->
                Row(Modifier.padding(top = 3.dp)) {
                    Text("${number + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
                    Column {
                        Text(definition.text, style = MaterialTheme.typography.bodyMedium)
                        definition.example?.let {
                            Text(
                                "“$it”",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (found.translations.isNotEmpty()) {
        Text(
            stringResource(R.string.lookup_translations),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        for ((code, translation) in found.translations) {
            Text(
                VocabLanguages.displayName(code) + ": " + translation.glosses().joinToString(", ") { gloss ->
                    gloss.roman?.let { "${gloss.word} (${it})" } ?: gloss.word
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    val origins = found.entries.map { it.origin }.filter { it.isNotEmpty() }.distinct()
    Text(
        stringResource(
            R.string.lookup_from,
            if (origins.isEmpty()) stringResource(found.source.labelRes) else origins.joinToString(", "),
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** The source's recording when it has one, else the phone's voice in the dictionary's language. */
private fun pronounce(speaker: VocabSpeaker, entry: DictEntry) {
    speaker.speak(entry.word, entry.audioUrl?.takeIf { InternetPermission.granted }, 1f, 1f, entry.spokenLocale())
}

@Composable
private fun LookupActions(
    request: LookupRequest,
    settings: LiveSettings,
    scan: LookupScanner.Scan?,
    word: String,
    found: WordLookup.Outcome.Found,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools = settings.watch { usableTools(it) }
    var anki by remember(found) { mutableStateOf(AnkiState.IDLE) }
    val note = remember(found) { ankiNote(context, request, scan, found) }

    fun addToAnki() {
        anki = AnkiState.BUSY
        scope.launch {
            val result = withContext(Dispatchers.IO) { AnkiDroid.add(context, note, settings.value.lookup.ankiDeck) }
            anki = when (result) {
                AnkiDroid.Result.Added -> AnkiState.ADDED
                AnkiDroid.Result.AlreadyThere -> AnkiState.ALREADY
                else -> AnkiState.FAILED
            }
        }
    }
    val askAnki = rememberDisclosedPermissionRequest(PermissionDisclosures.ANKIDROID, onGranted = ::addToAnki)

    fun onAnki() {
        if (!AnkiDroid.isInstalled(context)) {
            Toast.makeText(context, R.string.lookup_anki_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val mode = settings.value.lookup.ankiMode
        val direct = AnkiDroid.hasPermission(context)
        when {
            mode == AnkiMode.EDITOR || (mode == AnkiMode.AUTO && !direct) -> {
                val intent = AnkiDroid.editorIntent(context, note)
                if (intent == null) {
                    Toast.makeText(context, R.string.lookup_anki_missing, Toast.LENGTH_SHORT).show()
                } else {
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    onClose()
                }
            }
            direct -> addToAnki()
            else -> askAnki()
        }
    }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { if (anki == AnkiState.IDLE || anki == AnkiState.FAILED) onAnki() },
            label = {
                Text(
                    stringResource(
                        when (anki) {
                            AnkiState.IDLE -> R.string.lookup_anki_add
                            AnkiState.BUSY -> R.string.lookup_anki_adding
                            AnkiState.ADDED -> R.string.lookup_anki_added
                            AnkiState.ALREADY -> R.string.lookup_anki_already
                            AnkiState.FAILED -> R.string.lookup_anki_failed
                        },
                    ),
                )
            },
        )
        if (ToolbarTool.VOCABULARY in tools) {
            AssistChip(onClick = { addToMyWords(context, word, found) }, label = { Text(stringResource(R.string.lookup_my_words)) })
        }
        AssistChip(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(word, AskAiContext.dictionary(found.entries)))
            },
            label = { Text(stringResource(CommonR.string.common_copy)) },
        )
        if (ToolbarTool.AI in tools) {
            AssistChip(
                onClick = {
                    context.startActivity(
                        Intent(context, AskAiActivity::class.java)
                            .setAction(Intent.ACTION_PROCESS_TEXT)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, AskAiContext.dictionary(found.entries))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    onClose()
                },
                label = { Text(stringResource(R.string.ask_ai_label)) },
            )
        }
    }
}

/** The card's word as an AnkiDroid note: the first entry, the sentence it was in when there is one. */
private fun ankiNote(context: Context, request: LookupRequest, scan: LookupScanner.Scan?, found: WordLookup.Outcome.Found): AnkiDroid.Note {
    val entry = found.entries.first()
    val meaning = found.entries.filter { it.word == entry.word && it.reading == entry.reading }
        .flatMap { it.meanings }
        .joinToString("<br>") { meaning ->
            val definitions = meaning.definitions.take(MAX_ANKI_DEFINITIONS).joinToString("; ") { TextUtils.htmlEncode(it.text) }
            if (meaning.partOfSpeech.isEmpty()) definitions else "<i>${TextUtils.htmlEncode(meaning.partOfSpeech)}</i> $definitions"
        }
    val sentence = scan?.let { LookupScanner.sentence(request.text, it.span) }
        ?.takeIf { it.length > entry.word.length && it.length <= MAX_SENTENCE }
        .orEmpty()
    return AnkiDroid.Note(
        word = entry.word,
        reading = entry.reading,
        pitch = entry.pitch.joinToString(" ") { PitchNotation.format(entry.reading.ifEmpty { entry.word }, it) },
        meaning = meaning,
        sentence = TextUtils.htmlEncode(sentence),
        source = entry.origin.ifEmpty { context.getString(found.source.labelRes) },
    )
}

/** Into the vocabulary tool's My words list, in the dictionary's language. */
private fun addToMyWords(context: Context, word: String, found: WordLookup.Outcome.Found) {
    val lemma = VocabPackFile.normalizeLemma(found.entries.first().word.ifEmpty { word }) ?: return
    val record = VocabAutofill.fromDictionary(found.entries, lemma) ?: return
    val origin = found.entries.first().origin
    val language = if (origin.isEmpty()) {
        "en"
    } else {
        YomichanDictionaries.installed().firstOrNull { it.info.title == origin }?.info?.sourceLanguage?.ifEmpty { null } ?: "en"
    }
    val name = context.getString(ToolsR.string.core_tools_vocab_my_words)
    val added = VocabPacks.addToMyWords(context.filesDir, language, record, name, 0, "")
    Toast.makeText(context, if (added) R.string.lookup_my_words_added else R.string.lookup_my_words_already, Toast.LENGTH_SHORT).show()
}

private const val SHEET_HEIGHT = 0.7f
private const val SCRIM_ALPHA = 0.24f
private const val HIGHLIGHT_ALPHA = 0.3f
private val POPUP_WIDTH = 360.dp
private const val POPUP_HEIGHT = 0.45f
private const val MAX_ANKI_DEFINITIONS = 5
private const val MAX_SENTENCE = 300
