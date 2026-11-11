package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.topWords
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.PasswordGen
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.PwSettingAction
import com.wasimaster.wmkeyboard.ime.R
import java.security.SecureRandom
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- password / passphrase generator ----

/**
 * Passphrase material, filtered once from the bundled English dictionary
 * (no separate wordlist ships with the app).
 *
 * Read from the compiled `en.wmdict` the keyboard itself maps, through the
 * same device-protected extraction. The plain `en.txt` this used to open left
 * the APK when dictionaries went binary, and the failed open read as an empty
 * list: the passphrase tab showed "…" and 0 bits, and refresh did nothing.
 */
private object PassphraseWords {
    @Volatile private var cached: List<String>? = null

    suspend fun load(context: Context): List<String> = cached ?: withContext(Dispatchers.IO) {
        // Best-first over the trie, not an enumeration of it. `entries()` here
        // built every word of the English list as a `String` in one list —
        // some hundred thousand pairs, eight to ten megabytes — and then sorted
        // the lot by frequency to keep four thousand of them. [topWords] exists
        // for exactly this and says so: it walks the subtree maxima and emits a
        // word only once its own frequency reaches the head of the queue, so
        // the order is the same one the sort produced, without the list.
        val words = DictionaryStore.ensureBundled(DirectBoot.deviceContext(context), "en")
            ?.let { MappedTrie.open(it) }
            ?.topWords(PasswordGen.WORDLIST_LIMIT, accept = PasswordGen::acceptsWord)
            .orEmpty()
        // An empty result is not cached, so a failed extraction (disk full)
        // gets another try the next time the panel opens.
        if (words.isNotEmpty()) cached = words
        words
    }
}

/**
 * Password / passphrase generator. Every option persists (the same
 * controls live in the tool's settings); entropy is a rough bits estimate.
 * Nothing generated here is stored anywhere.
 */
@Composable
internal fun PasswordPanel(
    state: KeyboardUiState,
    onSetting: (PwSettingAction) -> Unit,
    onInsert: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val settings = state.settings
    val context = LocalContext.current
    val random = remember { SecureRandom() }
    var regenerateKey by remember { mutableIntStateOf(0) }

    val wordlist by produceState(initialValue = emptyList(), Unit) {
        value = PassphraseWords.load(context)
    }

    val passphraseSpec = PasswordGen.PassphraseSpec(
        words = settings.passwordGenerator.ppWordCount,
        separator = settings.passwordGenerator.ppSeparator,
        capitalize = settings.passwordGenerator.ppCapitalize,
        includeDigit = settings.passwordGenerator.ppIncludeDigit,
    )
    val passwordSpec = PasswordGen.PasswordSpec(
        length = settings.passwordGenerator.pwLength,
        upper = settings.passwordGenerator.pwUppercase,
        digits = settings.passwordGenerator.pwDigits,
        symbols = settings.passwordGenerator.pwSymbols,
        excludeAmbiguous = settings.passwordGenerator.pwExcludeAmbiguous,
        symbolPool = settings.toolLimits.passwordSymbols,
    )
    val pinMode = settings.passwordGenerator.pinMode
    val passphraseMode = !pinMode && settings.passwordGenerator.pwPassphraseMode
    val pinLength = settings.passwordGenerator.pinLength
    val pinAvoidWeak = settings.passwordGenerator.pinAvoidWeak
    val generated = remember(
        pinMode, passphraseMode, passwordSpec, passphraseSpec, pinLength, pinAvoidWeak, wordlist, regenerateKey,
    ) {
        when {
            pinMode -> PasswordGen.pin(pinLength, pinAvoidWeak, random)
            passphraseMode -> PasswordGen.passphrase(wordlist, passphraseSpec, random)
            else -> PasswordGen.password(passwordSpec, random)
        }
    }
    val entropy = when {
        pinMode -> PasswordGen.pinEntropyBits(pinLength)
        passphraseMode -> PasswordGen.passphraseEntropyBits(passphraseSpec, wordlist.size)
        else -> PasswordGen.passwordEntropyBits(passwordSpec)
    }

    // Read here rather than in the Copy chip's click handler: that lambda is
    // not composable, so it cannot resolve a string itself.
    val copiedMessage = stringResource(R.string.ime_password_copied_toast)

    fun copyGenerated() {
        if (generated.isEmpty()) return
        // Marked a secret (#1215), so Android's copy preview hides it and a
        // clipboard manager that reads the flag treats it as one.
        KeyboardClipboard.copy(context, generated, "Password", sensitive = true)
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    // Ring regions: the result card's regenerate/copy/insert, then the
    // flattened option list (steppers count as two slots each). The header
    // tabs publish CHIPS from the KeyboardScreen header block.
    PanelFocusTarget(
        panel = PanelMode.PASSWORD_GEN,
        region = FocusRegion.ACTIONS,
        count = 3,
        columns = 3,
    ) { index ->
        when (index) {
            0 -> regenerateKey++
            1 -> copyGenerated()
            2 -> if (generated.isNotEmpty()) onInsert(generated)
        }
    }
    val pg = settings.passwordGenerator
    val optionEntries: List<() -> Unit> = if (pinMode) {
        listOf(
            { onSetting(PwSettingAction.PinLength(pg.pinLength - 1)) },
            { onSetting(PwSettingAction.PinLength(pg.pinLength + 1)) },
            { onSetting(PwSettingAction.PinAvoidWeak(!pg.pinAvoidWeak)) },
        )
    } else if (!passphraseMode) {
        listOf(
            { onSetting(PwSettingAction.Length(pg.pwLength - 1)) },
            { onSetting(PwSettingAction.Length(pg.pwLength + 1)) },
            { onSetting(PwSettingAction.Upper(!pg.pwUppercase)) },
            { onSetting(PwSettingAction.Digits(!pg.pwDigits)) },
            { onSetting(PwSettingAction.Symbols(!pg.pwSymbols)) },
            { onSetting(PwSettingAction.ExcludeAmbiguous(!pg.pwExcludeAmbiguous)) },
        )
    } else {
        val separators = listOf("-", ".", "_", " ", "")
        val next = separators[(separators.indexOf(pg.ppSeparator) + 1).mod(separators.size)]
        listOf(
            { onSetting(PwSettingAction.Words(pg.ppWordCount - 1)) },
            { onSetting(PwSettingAction.Words(pg.ppWordCount + 1)) },
            { onSetting(PwSettingAction.Separator(next)) },
            { onSetting(PwSettingAction.Capitalize(!pg.ppCapitalize)) },
            { onSetting(PwSettingAction.IncludeDigit(!pg.ppIncludeDigit)) },
        )
    }
    PanelFocusTarget(
        panel = PanelMode.PASSWORD_GEN,
        region = FocusRegion.RESULTS,
        count = optionEntries.size,
        columns = optionEntries.size,
    ) { index -> optionEntries.getOrNull(index)?.invoke() }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedOption = state.focusedIndex(FocusRegion.RESULTS)

    // The Password/Passphrase tabs live in the FullBleedTool header next to
    // the back button; the body starts at the generated result.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        val cardShape = kb.cardShape()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(cardShape)
                .frostGlass(kb, cardShape, FrostSurface.CARDS)
                .background(kb.chip)
                .chipBorder(kb, cardShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A new value scrambles into place. Display only: Copy and
            // Insert below act on [generated] itself, so a tap mid-scramble
            // still takes the real password.
            ScrambledText(generated.ifEmpty { "…" }, animate = kb.motion.any) { shown ->
                Text(
                    shown,
                    color = kb.chipText,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            IconHint(stringResource(R.string.ime_password_regenerate_desc)) {
                IconButton(
                    onClick = { regenerateKey++ },
                    modifier = Modifier
                        .size(30.dp)
                        .focusRing(focusedAction == 0, CircleShape),
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.ime_password_regenerate_desc),
                        tint = kb.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(CommonR.string.common_copy),
                modifier = Modifier.focusRing(focusedAction == 1),
            ) { copyGenerated() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(R.string.ime_password_insert_action),
                modifier = Modifier.focusRing(focusedAction == 2),
            ) {
                if (generated.isNotEmpty()) onInsert(generated)
            }
        }
        // The strength figure runs to its new value with the scramble rather
        // than jumping, so a length step reads as more (or less) strength.
        val shownEntropy by animateIntAsState(
            entropy,
            animationSpec = if (kb.motion.any) tween(SCRAMBLE_MS) else snap(),
            label = "entropy",
        )
        Text(
            stringResource(R.string.ime_password_entropy_info, shownEntropy),
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.End)
                .padding(bottom = 2.dp),
        )
        // Easter egg: one generation in five hundred also cites xkcd 936. A
        // caption beside the result, never an input to it — the roll is keyed
        // on the generated value but the value itself is untouched.
        val showXkcd = remember(generated) { generated.isNotEmpty() && Random.nextInt(500) == 0 }
        if (showXkcd) {
            Text(
                stringResource(R.string.ime_password_egg_info),
                color = kb.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(bottom = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Ring indices mirror [optionEntries]: the stepper's − and + are
            // slots 0 and 1, then the chips in drawing order.
            if (pinMode) {
                StepperRow(
                    label = stringResource(R.string.ime_password_length_label),
                    value = pinLength,
                    focusedMinus = focusedOption == 0,
                    focusedPlus = focusedOption == 1,
                    onChange = { onSetting(PwSettingAction.PinLength(it)) },
                )
                ToolPanelChip(
                    stringResource(R.string.ime_password_pin_avoid_weak_label),
                    selected = pinAvoidWeak,
                    modifier = Modifier.focusRing(focusedOption == 2),
                ) {
                    onSetting(PwSettingAction.PinAvoidWeak(!pinAvoidWeak))
                }
            } else if (!passphraseMode) {
                StepperRow(
                    label = stringResource(R.string.ime_password_length_label),
                    value = settings.passwordGenerator.pwLength,
                    focusedMinus = focusedOption == 0,
                    focusedPlus = focusedOption == 1,
                    onChange = { onSetting(PwSettingAction.Length(it)) },
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolPanelChip(
                        "A–Z",
                        selected = settings.passwordGenerator.pwUppercase,
                        modifier = Modifier.focusRing(focusedOption == 2),
                    ) {
                        onSetting(PwSettingAction.Upper(!settings.passwordGenerator.pwUppercase))
                    }
                    ToolPanelChip(
                        "0–9",
                        selected = settings.passwordGenerator.pwDigits,
                        modifier = Modifier.focusRing(focusedOption == 3),
                    ) {
                        onSetting(PwSettingAction.Digits(!settings.passwordGenerator.pwDigits))
                    }
                    ToolPanelChip(
                        "#!&",
                        selected = settings.passwordGenerator.pwSymbols,
                        modifier = Modifier.focusRing(focusedOption == 4),
                    ) {
                        onSetting(PwSettingAction.Symbols(!settings.passwordGenerator.pwSymbols))
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_exclude_ambiguous_label),
                        selected = settings.passwordGenerator.pwExcludeAmbiguous,
                        modifier = Modifier.focusRing(focusedOption == 5),
                    ) {
                        onSetting(PwSettingAction.ExcludeAmbiguous(!settings.passwordGenerator.pwExcludeAmbiguous))
                    }
                }
            } else {
                StepperRow(
                    label = stringResource(R.string.ime_password_words_label),
                    value = settings.passwordGenerator.ppWordCount,
                    focusedMinus = focusedOption == 0,
                    focusedPlus = focusedOption == 1,
                    onChange = { onSetting(PwSettingAction.Words(it)) },
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val separators = listOf("-", ".", "_", " ", "")
                    val next = separators[
                        (separators.indexOf(settings.passwordGenerator.ppSeparator) + 1).mod(separators.size),
                    ]
                    val separator = settings.passwordGenerator.ppSeparator
                    val separatorName = if (separator.isEmpty()) {
                        stringResource(CommonR.string.common_none)
                    } else {
                        "“$separator”"
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_separator_label, separatorName),
                        modifier = Modifier.focusRing(focusedOption == 2),
                    ) { onSetting(PwSettingAction.Separator(next)) }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_capitalize_label),
                        selected = settings.passwordGenerator.ppCapitalize,
                        modifier = Modifier.focusRing(focusedOption == 3),
                    ) {
                        onSetting(PwSettingAction.Capitalize(!settings.passwordGenerator.ppCapitalize))
                    }
                    ToolPanelChip(
                        stringResource(R.string.ime_password_add_digit_label),
                        selected = settings.passwordGenerator.ppIncludeDigit,
                        modifier = Modifier.focusRing(focusedOption == 4),
                    ) {
                        onSetting(PwSettingAction.IncludeDigit(!settings.passwordGenerator.ppIncludeDigit))
                    }
                }
            }
        }
    }
}

/**
 * A labelled − value + row. With [holdSteps] the two buttons also step while
 * held, faster the longer the hold (see [repeatOnHold]), for a count that
 * may be a long way from where it is.
 */
@Composable
internal fun StepperRow(
    label: String,
    value: Int,
    focusedMinus: Boolean = false,
    focusedPlus: Boolean = false,
    holdSteps: Boolean = false,
    labelWidth: Dp = 52.dp,
    onChange: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(labelWidth))
        ToolPanelChip(
            "−",
            modifier = Modifier
                .repeatOnHold(holdSteps) { onChange(value - 1) }
                .focusRing(focusedMinus),
        ) { onChange(value - 1) }
        Text(
            "$value",
            color = kb.modifierKeyText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        ToolPanelChip(
            "+",
            modifier = Modifier
                .repeatOnHold(holdSteps) { onChange(value + 1) }
                .focusRing(focusedPlus),
        ) { onChange(value + 1) }
    }
}

// ---- QR code generator ----

/**
 * QR code of content the user types into the panel itself (keystrokes reroute
 * into the shared search buffer via [PanelMode.hasMediaSearch], so the key
 * rows show below). It opens seeded with the field text as a convenience, but
 * from there it is independent of the focused field. Send commits the code as
 * a PNG.
 */
@Composable
internal fun QrGeneratorPanel(
    state: KeyboardUiState,
    onSend: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val content = state.mediaQuery
    // Preview renders small and cheap; the inserted PNG uses the size setting.
    val qrMaxChars = state.settings.toolLimits.qrMaxChars
    val bitmap = remember(content, state.settings.scanner.qrEcc, qrMaxChars) {
        QrCodeGen.bitmap(
            content,
            sizePx = 384,
            ecc = state.settings.scanner.qrEcc.name,
            maxChars = qrMaxChars,
        )
    }
    // Typing already reaches the content buffer; the ring only needs Send.
    PanelFocusTarget(
        panel = PanelMode.QR_GEN,
        region = FocusRegion.ACTIONS,
        count = if (bitmap != null) 1 else 0,
        columns = 1,
    ) { if (bitmap != null) onSend() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // Editable content line with a faked caret — keys reroute into the
        // buffer, so the platform draws no cursor of its own.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .frostGlass(kb, RoundedCornerShape(18.dp), FrostSurface.CHIPS)
                .background(kb.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchQueryText(
                query = content.replace("\n", " "),
                placeholder = stringResource(R.string.ime_qr_content_hint),
                active = state.mediaSearchActive,
                textColor = kb.suggestionText,
                placeholderColor = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (content.length > qrMaxChars) {
                            pluralStringResource(
                                R.plurals.ime_qr_too_long_error,
                                content.length,
                                content.length,
                            )
                        } else {
                            stringResource(R.string.ime_qr_empty)
                        },
                        color = kb.secondaryText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.ime_qr_image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(4.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ToolPanelChip(
                        stringResource(R.string.ime_qr_send_action),
                        modifier = Modifier.focusRing(
                            state.focusedIndex(FocusRegion.ACTIONS) == 0,
                        ),
                    ) { onSend() }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(
                            R.plurals.ime_qr_meta_info,
                            content.length,
                            content.length,
                            state.settings.scanner.qrEcc.name,
                        ),
                        color = kb.secondaryText,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}
