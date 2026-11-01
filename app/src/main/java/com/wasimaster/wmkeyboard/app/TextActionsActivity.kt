package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SelectionMenuItem
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.selection.ContentFlags
import com.wasimaster.wmkeyboard.core.selection.DetectOptions
import com.wasimaster.wmkeyboard.core.selection.DigitScripts
import com.wasimaster.wmkeyboard.core.selection.JsonReformat
import com.wasimaster.wmkeyboard.core.selection.LineEdits
import com.wasimaster.wmkeyboard.core.selection.MacroGates
import com.wasimaster.wmkeyboard.core.selection.Place
import com.wasimaster.wmkeyboard.core.selection.Places
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.core.selection.TextCodecs
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.settings.providerMode
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.OnlineTranslate
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TranslateProviders
import com.wasimaster.wmkeyboard.core.tools.Translation
import com.wasimaster.wmkeyboard.core.tools.visibleAiActions
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.transliteration.Zawgyi
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import com.wasimaster.wmkeyboard.ime.R as ImeR
import com.wasimaster.wmkeyboard.ime.ui.selectionMacroIcon
import com.wasimaster.wmkeyboard.tools.R as ToolsR
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard's actions on text selected in any app (#916).
 *
 * Reached from another app's text selection menu (`PROCESS_TEXT`) through
 * one of two aliases: "WM Keyboard", which opens on the actions, and "WM
 * Translate", which opens on the translation ([TextActionsSelectionMenu]).
 * Nothing has to be typed with this keyboard, or copied into it, first.
 *
 * A sheet over the app: the selected text at the top, then what can be done
 * with it. Translate and the AI actions run here and put their answer in
 * place of the text; so do the selection bar's rewrites (case, lines, the
 * converters), from the same list the keyboard's bar offers, in the order
 * the user gave it. Each answer can be taken further or undone. The actions
 * that open another app (Call, Open, Map) leave from here as the bar's do.
 *
 * What comes back is the app's choice. A field the user can edit gets the
 * result through `setResult`, which the app puts in place of the selection;
 * read-only text ([Intent.EXTRA_PROCESS_TEXT_READONLY]) gets Copy instead.
 */
class TextActionsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }
        val request = TextActionsRequest(
            text = text,
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
            // An alias keeps its own name on the intent that reaches the activity.
            translateFirst = intent.component?.className == TextActionsSelectionMenu.TRANSLATE_ALIAS,
        )
        val repository = SettingsRepository(applicationContext)
        setContent {
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                TextActionsSheet(onClose = ::finish) {
                    TextActionsScreen(request, settings, onReplace = ::replace, onClose = ::finish)
                }
            }
        }
    }

    /** Hands [text] back to the app, which puts it in place of the selection. */
    private fun replace(text: String) {
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }
}

/** What the sheet was opened with. */
private class TextActionsRequest(
    val text: String,
    /** The app will not take a result: Copy, not Replace. */
    val readOnly: Boolean,
    /** Opened from "WM Translate": translate at once. */
    val translateFirst: Boolean,
)

/** A row of choices shown in place of the rewrites, as the bar's ladders are. */
private enum class Ladder { NONE, CASE, FANCY }

/**
 * The selection bar's actions that work outside a text field, from the
 * user's list. Every one either rewrites the text without needing the
 * keyboard (a pure function over it) or hands it to another app. The rest
 * of the bar needs the field itself (Select all, Find, Move up), the
 * keyboard's loaded dictionaries (Bangla, Hindi) or a panel of the
 * keyboard's (QR, Repeat), and is left to the bar.
 */
private val REWRITES = setOf(
    SelectionMacro.FORMAT, SelectionMacro.FANCY,
    SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE, SelectionMacro.LINES_NUMBER,
    SelectionMacro.LINES_BULLET, SelectionMacro.LINES_CHECKBOX,
    SelectionMacro.DIGITS_LATIN, SelectionMacro.ZAWGYI, SelectionMacro.JSON_FORMAT,
    SelectionMacro.BASE64_DECODE, SelectionMacro.URL_DECODE, SelectionMacro.STRIP_TRACKERS,
)

private val DOORS = setOf(
    SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
    SelectionMacro.OPEN, SelectionMacro.ADD_CONTACT, SelectionMacro.MAP, SelectionMacro.CALENDAR,
)

/** What the sheet offers for one text: the rewrites and the doors, in the user's order. */
private class SheetOffer(
    val kind: SelectionKind,
    val content: ContentFlags,
    val rewrites: List<SelectionMacro>,
    val doors: List<SelectionMacro>,
    val cases: List<SelectionMacro>,
)

/**
 * The bar's own reading of [text] ([SelectionMacros.offer]), cut to what the
 * sheet can run. Past [MAX_READ] the text is not read for what it is, as the
 * bar does not read a selection that long either; the rewrites that need no
 * reading are still offered.
 */
private fun sheetOffer(context: Context, text: String, settings: KeyboardSettings): SheetOffer {
    val prefs = settings.selectionMacros
    val masks = settings.clipboard.phoneFormats.toList()
    val long = text.length > MAX_READ
    val kind = if (prefs.detectEntities && !long) SelectionMacros.detect(text, masks) else SelectionKind.TEXT
    val allowed = prefs.macros.filterTo(mutableSetOf()) { it in REWRITES || it in DOORS }
    // As on the bar: an entity offers Format only when it would change
    // something that Remove trackers does not already say.
    val formatted = if (kind == SelectionKind.TEXT) null else SelectionMacros.format(text, kind, masks)
    val formattable = kind == SelectionKind.TEXT || (
        formatted != null && !(SelectionMacro.STRIP_TRACKERS in allowed && formatted == SelectionMacros.stripTrackers(text))
        )
    val content = if (long) {
        ContentFlags.NONE
    } else {
        SelectionMacros.detectContent(
            text,
            DetectOptions(
                dateTime = SelectionMacro.CALENDAR in allowed,
                place = SelectionMacro.MAP in allowed,
                nowMillis = System.currentTimeMillis(),
                extraTrackers = settings.clipboard.extraTrackers,
            ),
        )
    }
    val gates = MacroGates(
        whatsAppInstalled = isInstalled(context, WHATSAPP_PACKAGE),
        qrAvailable = false,
        formattable = formattable,
        // There is no field around the text here, so nothing to widen to.
        wholeField = true,
        ttsAvailable = false,
        content = content,
    )
    val row = SelectionMacros.offer(kind, allowed, gates, prefs.order)
    return SheetOffer(
        kind = kind,
        content = content,
        rewrites = row.filter { it in REWRITES },
        doors = row.filter { it in DOORS },
        cases = SelectionMacros.caseLadder(prefs.macros),
    )
}

private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }
}.isSuccess

@Composable
private fun TextActionsScreen(
    request: TextActionsRequest,
    settings: LiveSettings,
    onReplace: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = settings.watch { it }
    // Every answer is kept, the original first, so Undo walks back through them.
    val history = remember { mutableStateListOf(request.text) }
    val text = history.last()
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorOpensTranslate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var partial by remember { mutableStateOf<String?>(null) }
    var runSeq by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var ladder by remember { mutableStateOf(Ladder.NONE) }
    // What the Fancy ladder styles, and whether its last pick is on top:
    // another pick replaces that one rather than styling it again.
    var fancySource by remember { mutableStateOf("") }
    var fancyPicked by remember { mutableStateOf(false) }
    // Data Saver's question is answered by tapping the action again.
    var meteredGranted by remember { mutableStateOf(false) }

    fun push(out: String?, said: String? = null) {
        error = null
        if (out == null || out == history.last()) return
        history += out
        note = said
    }

    fun launchAction(label: String, block: suspend () -> Unit) {
        job?.cancel()
        val seq = ++runSeq
        busy = label
        error = null
        errorOpensTranslate = false
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (seq == runSeq) {
                    error = failure.message?.takeIf { it.isNotBlank() } ?: context.getString(CommonR.string.common_error_generic)
                    errorOpensTranslate = failure is TranslateSetupException
                }
            } finally {
                if (seq == runSeq) {
                    busy = null
                    partial = null
                }
            }
        }
    }

    val translateOn = ToolbarTool.TRANSLATE in current.enabledTools
    val translateLabel = stringResource(ContentR.string.core_content_selection_macro_translate)
    fun translate() = launchAction(translateLabel) {
        val source = text
        val translation = translateText(context, current, source)
        val from = translation.detectedSource
        if (translation.text.trim() == source.trim()) {
            val target = TranslateClient.languageName(current.translateTargetLang)
            throw IOException(context.getString(R.string.text_actions_already_in, target))
        }
        push(
            translation.text,
            if (from.isBlank()) {
                context.getString(R.string.text_actions_translated)
            } else {
                context.getString(R.string.text_actions_translated_from, TranslateClient.languageName(from))
            },
        )
    }
    LaunchedEffect(Unit) { if (request.translateFirst && translateOn) translate() }

    val aiOn = ToolbarTool.AI in current.enabledTools
    val aiReady = aiOn && aiCanRun(current, context.filesDir)
    val aiActions = if (!aiReady) {
        emptyList()
    } else {
        visibleAiActions(current.ai.customActions, current.ai.actionOrder, current.ai.hiddenActions)
            .filter { !it.askEachRun }
            // Beside the Translate chip, the AI's own translate is a second
            // chip of the same name; the bar calls it AI translate for that.
            .filter { !(translateOn && it.id == BuiltInAiActions.TRANSLATE_ID) }
    }
    fun runAi(spec: AiActionSpec, label: String) = launchAction(label) {
        val source = text
        val seq = runSeq
        val answer = withContext(Dispatchers.IO) {
            runAiAction(
                context, current, spec, source,
                meteredGranted = meteredGranted,
                onMeteredAsk = { meteredGranted = true },
                isActive = { seq == runSeq },
            ) { raw ->
                val shown = AiThinking.stripped(raw).trim()
                scope.launch { if (seq == runSeq && shown.isNotEmpty()) partial = shown }
            }
        }
        if (seq == runSeq) push(answer, label)
    }

    val offer = remember(text, current) { sheetOffer(context, text, current) }

    fun rewrite(macro: SelectionMacro) {
        val masks = current.clipboard.phoneFormats.toList()
        val label = context.getString(macro.labelRes)
        when (macro) {
            SelectionMacro.FORMAT ->
                if (offer.kind == SelectionKind.TEXT) ladder = Ladder.CASE else push(SelectionMacros.format(text, offer.kind, masks), label)
            SelectionMacro.FANCY -> {
                fancySource = text
                fancyPicked = false
                ladder = Ladder.FANCY
            }
            SelectionMacro.LINES_SORT -> push(LineEdits.sort(text), label)
            SelectionMacro.LINES_DEDUPE -> push(LineEdits.dedupe(text), label)
            SelectionMacro.LINES_NUMBER -> push(LineEdits.number(text), label)
            SelectionMacro.LINES_BULLET -> push(LineEdits.bullet(text), label)
            SelectionMacro.LINES_CHECKBOX -> push(LineEdits.checkbox(text), label)
            SelectionMacro.DIGITS_LATIN -> push(DigitScripts.toAsciiDigits(text), label)
            SelectionMacro.JSON_FORMAT -> push(JsonReformat.toggle(text), label)
            SelectionMacro.BASE64_DECODE -> push(TextCodecs.base64Decode(text), label)
            SelectionMacro.URL_DECODE -> push(TextCodecs.urlDecode(text), label)
            SelectionMacro.STRIP_TRACKERS -> push(SelectionMacros.stripTrackersInText(text, current.clipboard.extraTrackers), label)
            SelectionMacro.ZAWGYI -> launchAction(label) {
                val source = text
                push(withContext(Dispatchers.Default) { Zawgyi.convertEitherWay(source) }, label)
            }
            else -> {}
        }
    }

    BackHandler(enabled = ladder != Ladder.NONE) { ladder = Ladder.NONE }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)) {
        // The text, as it stands after the last action. Long text scrolls
        // inside its box, so the actions stay where the thumb is.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                SelectionContainer {
                    Text(
                        (partial ?: text).let { if (it.length > MAX_SHOWN) it.take(MAX_SHOWN) + "…" else it },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        StatusLine(
            busy = busy,
            note = note,
            error = error,
            onOpenTranslate = if (errorOpensTranslate) {
                { openTool(context, ToolbarTool.TRANSLATE) }
            } else {
                null
            },
            hint = if (request.readOnly && history.size > 1) stringResource(R.string.text_actions_read_only_hint) else null,
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            when (ladder) {
                Ladder.CASE -> LadderRow(onBack = { ladder = Ladder.NONE }) {
                    offer.cases.forEach { macro ->
                        val label = stringResource(macro.labelRes)
                        ActionChip(label, null, enabled = busy == null) { push(SelectionMacros.applyCase(text, macro), label) }
                    }
                }
                Ladder.FANCY -> LadderRow(onBack = { ladder = Ladder.NONE }) {
                    FancyStyles.all.forEach { style ->
                        ActionChip(style.sample, null, enabled = busy == null) {
                            if (fancyPicked && history.size > 1) history.removeAt(history.lastIndex)
                            val before = history.size
                            push(FancyStyles.transform(fancySource, style), style.name)
                            fancyPicked = history.size > before
                        }
                    }
                }
                Ladder.NONE -> {
                    ActionGroup(stringResource(R.string.text_actions_group_language)) {
                        val menuOff = current.selectionMacros.systemMenuOff
                        if (translateOn && SelectionMenuItem.TRANSLATE !in menuOff) {
                            ActionChip(translateLabel, Icons.Outlined.Translate, enabled = busy == null) { translate() }
                        }
                        aiActions.forEach { spec ->
                            val label = BuiltInAiActions.labelRes(spec)?.let { stringResource(it) } ?: spec.name
                            ActionChip(label, Icons.Outlined.AutoFixHigh, enabled = busy == null) { runAi(spec, label) }
                        }
                        // The chat, with the text as its first message's attachment.
                        if (aiOn && SelectionMenuItem.ASK_AI !in menuOff) {
                            ActionChip(stringResource(R.string.ask_ai_label), null, enabled = busy == null) {
                                startQuietly(context, Intent(context, AskAiActivity::class.java).forText(text))
                            }
                        }
                        if (SelectionMenuItem.LOOK_UP !in menuOff) {
                            ActionChip(stringResource(R.string.lookup_label), null, enabled = busy == null) {
                                startQuietly(context, Intent(context, LookupActivity::class.java).forText(text))
                            }
                        }
                        if (ToolbarTool.NOTES in current.enabledTools && SelectionMenuItem.SAVE_NOTE !in menuOff) {
                            ActionChip(stringResource(R.string.text_actions_save_note), null, enabled = busy == null) {
                                startQuietly(
                                    context,
                                    Intent(context, NoteShareActivity::class.java)
                                        .setAction(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, text),
                                )
                            }
                        }
                    }
                    if (offer.rewrites.isNotEmpty()) {
                        ActionGroup(stringResource(R.string.text_actions_group_rewrite)) {
                            offer.rewrites.forEach { macro ->
                                ActionChip(stringResource(macro.labelRes), selectionMacroIcon(macro), enabled = busy == null) {
                                    rewrite(macro)
                                }
                            }
                        }
                    }
                    if (offer.doors.isNotEmpty()) {
                        ActionGroup(stringResource(R.string.text_actions_group_open)) {
                            offer.doors.forEach { macro ->
                                ActionChip(stringResource(macro.labelRes), selectionMacroIcon(macro), enabled = busy == null) {
                                    openDoor(context, macro, text, offer, current)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Undo on the left; what to do with the result on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    job?.cancel()
                    runSeq++
                    busy = null
                    partial = null
                    if (history.size > 1) history.removeAt(history.lastIndex)
                    note = null
                    error = null
                    fancyPicked = false
                },
                enabled = history.size > 1 || busy != null,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(CommonR.string.common_undo))
            }
            IconButton(onClick = { share(context, text) }) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(CommonR.string.common_share))
            }
            Spacer(Modifier.weight(1f))
            val copy = {
                copyText(context, text)
                onClose()
            }
            if (request.readOnly) {
                Button(onClick = copy, enabled = busy == null) { Text(stringResource(CommonR.string.common_copy)) }
            } else {
                OutlinedButton(onClick = copy, enabled = busy == null) { Text(stringResource(CommonR.string.common_copy)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onReplace(text) }, enabled = busy == null && history.size > 1) {
                    Text(stringResource(R.string.text_actions_replace_action))
                }
            }
        }
    }
}

/** What is running, what the last answer was, or why it failed; one line under the text. */
@Composable
private fun StatusLine(busy: String?, note: String?, error: String?, onOpenTranslate: (() -> Unit)?, hint: String?) {
    val line = when {
        busy != null -> stringResource(R.string.text_actions_running, busy)
        error != null -> error
        note != null -> note
        else -> hint
    } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (busy == null && error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (busy == null && error != null && onOpenTranslate != null) {
            TextButton(onClick = onOpenTranslate) { Text(stringResource(R.string.text_actions_open_translate_action)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionGroup(title: String, chips: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

/** A ladder's choices with the way back to the actions in front of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LadderRow(onBack: () -> Unit, chips: @Composable () -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.text_actions_back_desc))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) { chips() }
    }
}

@Composable
private fun ActionChip(label: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) } },
    )
}

/** A short sheet from the bottom, the app showing above it; a tap there closes it. */
@Composable
private fun TextActionsSheet(onClose: () -> Unit, content: @Composable () -> Unit) {
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
                // Taps inside the sheet are the sheet's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.navigationBarsPadding()) { content() }
        }
    }
}

private fun Intent.forText(text: String): Intent =
    setAction(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

/** Starts the first of [intents] that anything answers; says so when nothing does. */
private fun startQuietly(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, ImeR.string.ime_selection_macro_no_app_toast, Toast.LENGTH_SHORT).show()
}

/** The selection bar's doors, as the keyboard service opens them. */
private fun openDoor(context: Context, macro: SelectionMacro, text: String, offer: SheetOffer, settings: KeyboardSettings) {
    val dial by lazy { SelectionMacros.dialDigits(text, settings.clipboard.phoneFormats.toList()) }
    when (macro) {
        SelectionMacro.CALL -> startQuietly(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial")))
        SelectionMacro.SMS -> startQuietly(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$dial")))
        SelectionMacro.WHATSAPP -> startQuietly(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$dial")))
        SelectionMacro.EMAIL -> startQuietly(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${text.trim()}")))
        SelectionMacro.OPEN -> if (offer.kind == SelectionKind.EMAIL) {
            startQuietly(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("mailto:${text.trim()}")),
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${text.trim()}")),
            )
        } else {
            startQuietly(context, Intent(Intent.ACTION_VIEW, Uri.parse(SelectionMacros.openableUrl(text.trim()))))
        }
        SelectionMacro.ADD_CONTACT -> {
            val extra = if (offer.kind == SelectionKind.PHONE) {
                ContactsContract.Intents.Insert.PHONE
            } else {
                ContactsContract.Intents.Insert.EMAIL
            }
            startQuietly(
                context,
                Intent(ContactsContract.Intents.Insert.ACTION)
                    .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(extra, text.trim()),
                Intent(Intent.ACTION_INSERT_OR_EDIT)
                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(extra, text.trim()),
            )
        }
        SelectionMacro.MAP -> {
            val place = offer.content.place ?: Place.Address(text.trim())
            val query = when (place) {
                is Place.Coordinates -> "${place.point.lat},${place.point.lng}"
                is Place.Address -> place.text
            }
            startQuietly(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse(Places.geoUri(place))),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))),
            )
        }
        // The calendar app's own editor, filled in: the keyboard's Calendar
        // tool composer is a keyboard panel, with no keyboard open here.
        SelectionMacro.CALENDAR -> {
            val hit = offer.content.dateTime ?: return
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, hit.startMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, hit.endMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, hit.allDay)
                .putExtra(CalendarContract.Events.TITLE, text.trim().take(MAX_EVENT_TITLE))
            if (hit.zoneNamed) intent.putExtra(CalendarContract.Events.EVENT_TIMEZONE, hit.zoneId)
            startQuietly(context, intent)
        }
        else -> {}
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (!AppShareSheet.start(context, send)) startQuietly(context, Intent.createChooser(send, null))
}

private fun copyText(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("", text))
    }
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.text_actions_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The settings app on [tool]'s page, over this sheet. */
private fun openTool(context: Context, tool: ToolbarTool) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TOOL, tool.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** A translation that only the Translate tool's settings can make work: models to download, an engine to install. */
private class TranslateSetupException(message: String) : IOException(message)

/**
 * [text] in the Translate tool's target language, through the tool's own
 * list in its order: an on-device row asks that engine, and each run of
 * online rows is one fallback walk, the first answer winning, as the
 * keyboard's panel does it. Throws with the words to show when nothing
 * translates; an on-device failure the user can fix in settings outranks a
 * network one, as there.
 */
private suspend fun translateText(context: Context, settings: KeyboardSettings, text: String): Translation {
    if (text.length > TranslateClient.MAX_CHARS) {
        throw IOException(context.getString(R.string.text_actions_translate_too_long, TranslateClient.MAX_CHARS))
    }
    val target = settings.translateTargetLang
    val engine = if (OfflineTranslation.available) settings.translate.engine else TranslateEngine.ONLINE
    val picking = settings.providerMode(ProviderTool.TRANSLATE) == ChainMode.PICK
    val rows = TranslateProviders.rows(settings, engine, pick = if (picking) ProviderPicks.of(ProviderTool.TRANSLATE) else null)
    val hints = settings.enabledLanguages.map { it.id }
    var offline: OfflineTranslateResult? = null
    var online: Throwable? = null
    var index = 0
    while (index < rows.size) {
        if (rows[index].type?.onDevice == true) {
            val local = OfflineTranslation.engineOf(rows[index].typeId)
                ?.let { OfflineTranslation.translate(it, context.applicationContext, text, target, hints = hints) }
            if (local is OfflineTranslateResult.Success) return Translation(text = local.text, detectedSource = local.source)
            if (local != null &&
                (offline == null || (offline !is OfflineTranslateResult.NeedsModels && local is OfflineTranslateResult.NeedsModels))
            ) {
                offline = local
            }
            index++
            continue
        }
        val run = rows.drop(index).takeWhile { it.type?.onDevice != true }
        val answer = withContext(Dispatchers.IO) { runCatching { OnlineTranslate.translate(run, text, target) } }
        answer.getOrNull()?.let { return it }
        online = answer.exceptionOrNull()
        index += run.size
    }
    val fixable = offline is OfflineTranslateResult.NeedsModels || offline is OfflineTranslateResult.ModuleMissing
    if (offline != null && (online == null || fixable)) throw offlineFailure(context, offline)
    throw IOException(
        when (val failure = online) {
            null, is OnlineTranslate.NoProviderException -> context.getString(ToolsR.string.core_tools_provider_none_on)
            else -> if (ToolHttp.isUnreachable(failure)) {
                context.getString(CommonR.string.common_error_network)
            } else {
                context.getString(ImeR.string.ime_service_translate_error)
            }
        },
    )
}

private fun offlineFailure(context: Context, result: OfflineTranslateResult): IOException = when (result) {
    is OfflineTranslateResult.NeedsModels, OfflineTranslateResult.ModuleMissing ->
        TranslateSetupException(context.getString(R.string.text_actions_translate_setup))
    is OfflineTranslateResult.Unsupported -> IOException(
        if (result.romanized) {
            context.getString(ImeR.string.ime_translate_offline_romanized_error, TranslateClient.languageName(result.language))
        } else {
            context.getString(ImeR.string.ime_translate_offline_unsupported_error, TranslateClient.languageName(result.language))
        },
    )
    OfflineTranslateResult.Undetermined -> IOException(context.getString(R.string.text_actions_translate_undetermined))
    is OfflineTranslateResult.Failed, is OfflineTranslateResult.Success ->
        IOException(context.getString(ImeR.string.ime_translate_offline_failed_error))
}

/** Whether an AI action can run: a provider set up, or a model on the phone for the on-device one. */
private fun aiCanRun(settings: KeyboardSettings, filesDir: File): Boolean = when (settings.ai.provider) {
    AiProvider.ON_DEVICE -> BuildConfig.ENABLE_LOCAL_LLM && localModel(settings, filesDir) != null
    else -> AiClient.isConfigured(settings.ai)
}

/** The downloaded model the AI tool runs on: the chosen one, or the only one there is. */
private fun localModel(settings: KeyboardSettings, filesDir: File): File? =
    LocalLlmStore.selectedModelFile(filesDir, settings.ai.localModelId)
        ?: LocalLlmStore.soleDownloadedId(filesDir)?.let { LocalLlmStore.selectedModelFile(filesDir, it) }

/**
 * One AI action over [text], as the keyboard's AI panel runs it, without its
 * tools or memory: the answer is about this text alone. Blocking; call on
 * an IO dispatcher. [onPartial] gets the answer as it streams in.
 *
 * Data Saver is asked as it is for the chat. Its question ([MeteredDecision.ASK])
 * is put as the error, and answered by running the action again
 * ([meteredGranted]).
 */
private fun runAiAction(
    context: Context,
    settings: KeyboardSettings,
    spec: AiActionSpec,
    text: String,
    meteredGranted: Boolean,
    onMeteredAsk: () -> Unit,
    isActive: () -> Boolean,
    onPartial: (String) -> Unit,
): String {
    val system = AiPrompts.systemPrompt(spec, settings.ai.translateTo)
    val raw = if (settings.ai.provider == AiProvider.ON_DEVICE) {
        val model = localModel(settings, context.filesDir) ?: throw IOException(context.getString(ImeR.string.ime_ai_error_model_missing))
        LocalLlmEngine.generate(
            context.applicationContext, model, settings.ai.localBackend, settings.ai.localContextTokens,
            system, text, onPartial,
        )
    } else {
        val network = NetworkWatcher(context.applicationContext).apply { refresh() }.state.value
        val decision = DataSaverStatus(
            active = settings.dataSaver.appliesTo(network),
            settings = settings.dataSaver,
            grants = if (meteredGranted) setOf(MeteredFeature.CLOUD_AI) else emptySet(),
        ).decide(MeteredFeature.CLOUD_AI)
        if (decision == MeteredDecision.ASK) {
            onMeteredAsk()
            throw IOException(context.getString(R.string.text_actions_metered_ask))
        }
        if (decision != MeteredDecision.ALLOWED) throw IOException(context.getString(ImeR.string.ime_ai_error_metered_blocked))
        AiClient.completeStreaming(
            config = AiClient.config(settings.ai),
            system = system,
            user = text,
            maxTokens = AiClient.effectiveMaxTokens(settings.ai),
            onPhase = {},
            onPartial = onPartial,
            isActive = isActive,
        ).text
    }
    return AiThinking.stripped(raw).trim().ifEmpty { throw IOException(context.getString(ImeR.string.ime_ai_error_empty_result)) }
}

/** Past this a text is not read for what it is (a link, a date), as on the bar. */
private const val MAX_READ = 4_000

/** The most of the text the sheet draws; the actions still take all of it. */
private const val MAX_SHOWN = 5_000

private const val MAX_EVENT_TITLE = 200

private const val WHATSAPP_PACKAGE = "com.whatsapp"

/** How much of the screen the sheet may take, from the bottom. */
private const val SHEET_HEIGHT = 0.85f

/** The dim over the app above the sheet. */
private const val SCRIM_ALPHA = 0.32f
