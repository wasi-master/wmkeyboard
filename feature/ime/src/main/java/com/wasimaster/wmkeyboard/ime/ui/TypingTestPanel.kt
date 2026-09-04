package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.TypingTestSettings
import com.wasimaster.wmkeyboard.core.tools.TypingWordPools
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.wasimaster.wmkeyboard.core.tools.CharState
import com.wasimaster.wmkeyboard.core.tools.TypingAchievements
import com.wasimaster.wmkeyboard.core.tools.TypingBests
import com.wasimaster.wmkeyboard.core.tools.TypingHistory
import com.wasimaster.wmkeyboard.core.tools.TypingResult
import com.wasimaster.wmkeyboard.core.tools.TypingTestDurations
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.tools.TypingTestWordCounts
import com.wasimaster.wmkeyboard.core.tools.compareWord
import com.wasimaster.wmkeyboard.core.tools.typingConfigKey
import com.wasimaster.wmkeyboard.core.tools.typingConfigLabel
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.TypingTestAction
import com.wasimaster.wmkeyboard.ime.TypingTestUi
import kotlin.math.roundToInt

/**
 * The typing-speed tool. Two faces sharing one panel: the run itself —
 * which sits above the live key rows, since the user is typing on them —
 * and the results screen, which takes the whole panel once the clock stops.
 *
 * Nothing here owns any test state. The service holds the prompt, the
 * typed words and the clock (see TypingTestUi) so that scoring can never
 * drift from what a recomposition happens to render.
 */
@Composable
internal fun TypingTestPanel(
    state: KeyboardUiState,
    onAction: (TypingTestAction) -> Unit,
) {
    val result = state.typingTest.result
    if (result != null) {
        TypingResultView(state, result, onAction)
    } else {
        TypingRunView(state, onAction)
    }
}

// ---- the run ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypingRunView(state: KeyboardUiState, onAction: (TypingTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val test = state.typingTest
    val settings = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        TypingRunStats(state)
        Spacer(Modifier.height(4.dp))

        // The test's own suggestion row. Not the strip: that belongs to the
        // field behind the panel, and a word picked here must never reach it.
        if (settings.typingTest.suggestions) {
            TypingSuggestionRow(test, onAction)
            Spacer(Modifier.height(4.dp))
        }

        if (test.unavailable) {
            TypingUnavailableNotice(state, Modifier.weight(1f))
        } else {
        // Reduce motion holds the caret solid: no infinite transition is
        // started at all, rather than one running against a zero duration.
        val blink = if (kb.reduceMotion) {
            1f
        } else {
            val animated by rememberInfiniteTransition(label = "caret").animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(620, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "caretAlpha",
            )
            animated
        }

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val scrollState = rememberScrollState()
            val viewport = maxHeight
            val viewportPx = with(LocalDensity.current) { viewport.toPx() }
            // Where the caret's line sits inside the prompt, in content
            // coordinates. Measured against the FlowRow rather than the
            // viewport, so the scroll offset never feeds back into the target
            // the scroll is being driven to.
            var caretTop by remember(test.words) { mutableFloatStateOf(0f) }
            var caretLineHeight by remember(test.words) { mutableFloatStateOf(0f) }
            // The line being typed is parked in the middle of the box rather
            // than merely dragged into view: a caret that only just fits ends
            // up on the bottom line with nothing ahead of it to read, and the
            // next line has to be guessed. The trailing spacer below is what
            // lets the last lines of the prompt reach the middle too.
            val scrollTarget = caretTop - (viewportPx - caretLineHeight) / 2f
            LaunchedEffect(scrollTarget, viewportPx) {
                if (viewportPx <= 0f) return@LaunchedEffect
                val to = scrollTarget.roundToInt().coerceAtLeast(0)
                if (kb.reduceMotion) scrollState.scrollTo(to) else scrollState.animateScrollTo(to)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    // No inter-word gap here: each word carries its own trailing
                    // space (see promptWord), so the space between words is a real
                    // character the caret can land on rather than a layout gap.
                    horizontalArrangement = Arrangement.Start,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (index in test.words.indices) {
                        val wordModifier = if (index == test.wordIndex) {
                            Modifier.onPlaced {
                                caretTop = it.positionInParent().y
                                caretLineHeight = it.size.height.toFloat()
                            }
                        } else {
                            Modifier
                        }
                        Text(
                            promptWord(test, index, kb, blink),
                            fontSize = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            // Untyped text is the resting colour; the spans in
                            // the annotated string override it as the user goes.
                            color = kb.secondaryText.copy(alpha = 0.45f),
                            modifier = wordModifier,
                        )
                    }
                }
                Spacer(Modifier.height(viewport / 2))
            }
        }
        }

        // The settings strip is only useful before the run: once the user
        // starts typing it is dropped, handing its row of height to the prompt.
        if (!test.running) {
            TypingConfigRow(state, onAction, compact = true)
        }
    }
}

/**
 * Why there is no prompt: the language has no word list, or its layout
 * converts keystrokes into something the test cannot score. Sits where the
 * prompt would, so the config row below stays where the user expects it.
 */
@Composable
private fun TypingUnavailableNotice(state: KeyboardUiState, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val text = if (state.composer.isConversion) {
        stringResource(R.string.ime_typing_converts_info)
    } else {
        stringResource(R.string.ime_typing_no_words_info, state.language.displayName)
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = kb.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * Up to three suggestion chips for the word being typed — or, between words,
 * the next-word predictions — from the same engine the strip uses. A tap
 * finishes the word with the chip's text. The row keeps its height while
 * empty so the prompt does not jump as candidates come and go.
 */
@Composable
private fun TypingSuggestionRow(test: TypingTestUi, onAction: (TypingTestAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (word in test.suggestions.take(3)) {
            ToolPanelChip(
                label = word,
                modifier = Modifier.weight(1f, fill = false),
            ) { onAction(TypingTestAction.Suggestion(word)) }
        }
    }
}

/**
 * Mistake red. Matches the grammar panel's "correctness" colour rather
 * than inventing a second wrong-looking red, and lightens on dark themes
 * so it stays legible against a dark board.
 */
private fun errorColor(kb: KbTheme): Color =
    if (kb.dark) Color(0xFFEF7070) else Color(0xFFD64545)

/**
 * One prompt word, coloured character by character against what the user
 * typed for it. The word holding the caret also carries the caret itself,
 * drawn as a background block on the character about to be typed.
 *
 * [blink] is the caret's alpha, driven by a single animation in the parent
 * — one transition for the panel rather than one per word on screen.
 */
private fun promptWord(
    test: TypingTestUi,
    index: Int,
    kb: KbTheme,
    blink: Float,
): AnnotatedString {
    val expected = test.words[index]
    val live = index == test.wordIndex
    val typed = when {
        live -> test.current
        index < test.typedWords.size -> test.typedWords[index].typed
        else -> null
    }

    // Untouched words are plain text — no spans, no per-character work for
    // the hundred-odd words still ahead of the caret. The trailing space is
    // the gap to the next word, kept even here so a word never changes width
    // when the caret reaches it.
    if (typed == null) return AnnotatedString("$expected ")

    val states = compareWord(expected, typed, live = live)
    val caretAt = if (live) typed.length else -1
    val wrong = errorColor(kb)
    return buildAnnotatedString {
        val chars = expected + typed.drop(expected.length)
        for ((i, state) in states.withIndex()) {
            val style = when (state) {
                CharState.CORRECT -> SpanStyle(color = kb.keyText)
                CharState.WRONG -> SpanStyle(
                    color = wrong,
                    textDecoration = TextDecoration.Underline,
                )
                CharState.EXTRA -> SpanStyle(color = wrong.copy(alpha = 0.7f))
                CharState.MISSING -> SpanStyle(
                    color = wrong.copy(alpha = 0.5f),
                    textDecoration = TextDecoration.LineThrough,
                )
                CharState.PENDING -> SpanStyle(color = kb.secondaryText.copy(alpha = 0.45f))
            }
            val caret = if (i == caretAt) {
                style.copy(background = kb.accent.copy(alpha = 0.55f * blink))
            } else {
                style
            }
            withStyleSafe(caret) { append(chars.getOrElse(i) { ' ' }) }
        }
        // The trailing space is always rendered — it is the gap to the next
        // word, not an extra character. When the caret is parked past the last
        // letter (word fully typed, or an overshoot) it lands on this space,
        // so finishing a word never grows the word or shifts the layout.
        val parked = caretAt >= states.size
        val spaceStyle = if (parked) {
            SpanStyle(background = kb.accent.copy(alpha = 0.55f * blink))
        } else {
            SpanStyle()
        }
        withStyleSafe(spaceStyle) { append(" ") }
    }
}

/** [AnnotatedString.Builder.withStyle] without the experimental opt-in noise. */
private inline fun AnnotatedString.Builder.withStyleSafe(
    style: SpanStyle,
    block: AnnotatedString.Builder.() -> Unit,
) {
    val index = pushStyle(style)
    block()
    pop(index)
}

/** Live speed, progress and accuracy across the top of a running test. */
@Composable
private fun TypingRunStats(state: KeyboardUiState) {
    val kb = LocalKbTheme.current
    val test = state.typingTest
    val settings = state.settings

    val elapsedSeconds = test.elapsedMs / 1000.0
    val liveWpm = test.samples.lastOrNull()?.wpm ?: 0.0
    val accuracy = if (test.totalKeystrokes > 0) {
        test.correctKeystrokes * 100.0 / test.totalKeystrokes
    } else {
        100.0
    }

    // The headline number: seconds left when a clock is running the test,
    // words remaining when a word count is.
    val headline: String
    val headlineLabel: String
    val progress: Float
    when (settings.typingTest.mode) {
        TypingTestMode.TIME -> {
            val left = (settings.typingTest.duration - elapsedSeconds).coerceAtLeast(0.0)
            headline = left.roundToInt().toString()
            headlineLabel = stringResource(R.string.ime_typing_time_left_label)
            progress = (elapsedSeconds / settings.typingTest.duration).coerceIn(0.0, 1.0).toFloat()
        }
        else -> {
            val total = test.words.size.coerceAtLeast(1)
            headline = "${test.wordIndex}/$total"
            headlineLabel = stringResource(R.string.ime_typing_words_left_label)
            progress = (test.wordIndex.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                headline,
                color = kb.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                " $headlineLabel",
                color = kb.secondaryText,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            InlineStat(
                stringResource(R.string.ime_typing_wpm_label),
                liveWpm.roundToInt().toString(),
            )
            Spacer(Modifier.width(14.dp))
            InlineStat(
                stringResource(R.string.ime_typing_accuracy_short_label),
                "${accuracy.roundToInt()}%",
            )
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(progress)
    }
}

@Composable
private fun InlineStat(label: String, value: String) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = kb.keyText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            " $label",
            color = kb.secondaryText,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val kb = LocalKbTheme.current
    // Animated so the time bar glides instead of stepping with the ticker.
    // Reduce motion takes the stepping: a progress bar advancing in ticks is
    // still legible, and the glide is the part that is motion.
    val width by animateFloatAsState(
        progress,
        animationSpec = if (kb.reduceMotion) snap() else spring(),
        label = "typingProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(kb.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(width.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(kb.accent),
        )
    }
}

// ---- results ----

@Composable
private fun TypingResultView(
    state: KeyboardUiState,
    result: TypingResult,
    onAction: (TypingTestAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val settings = state.settings
    val best = remember(settings.typingTest.bests, result.configKey) {
        TypingBests.decode(settings.typingTest.bests)[result.configKey]
    }
    val history = remember(settings.typingTest.history) {
        TypingHistory.decode(settings.typingTest.history)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                result.wpm.roundToInt().toString(),
                color = kb.accent,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                " " + stringResource(R.string.ime_typing_wpm_label),
                color = kb.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            if (state.typingTest.personalBest) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(kb.accent.copy(alpha = 0.18f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.ime_typing_new_best_label),
                        color = kb.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else if (best != null) {
                Text(
                    stringResource(R.string.ime_typing_best_info, best.roundToInt()),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile(
                stringResource(R.string.ime_typing_accuracy_label),
                "${result.accuracy.roundToInt()}%",
                Modifier.weight(1f),
            )
            StatTile(
                stringResource(R.string.ime_typing_raw_label),
                result.raw.roundToInt().toString(),
                Modifier.weight(1f),
            )
            StatTile(
                stringResource(R.string.ime_typing_consistency_label),
                "${result.consistency.roundToInt()}%",
                Modifier.weight(1f),
            )
            StatTile(
                stringResource(R.string.ime_typing_time_label),
                "${result.seconds.roundToInt()}s",
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.ime_typing_char_counts_info,
                result.correctChars,
                result.incorrectChars,
                result.extraChars,
                result.missedChars,
            ),
            color = kb.secondaryText,
            fontSize = 11.sp,
        )

        // Unlocked achievement badges — the stored set plus whatever this run
        // just earned (the store write is async, so the union covers the gap).
        // Newly earned ones borrow the "New best" badge's accent treatment.
        val earnedNow = state.typingTest.earnedAchievements
        val unlocked = remember(settings.typingTest.achievements, earnedNow) {
            TypingAchievements.decode(settings.typingTest.achievements) + earnedNow
        }
        if (unlocked.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.ime_typing_achievements_label),
                color = kb.secondaryText,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (id in TypingAchievements.ALL) {
                    if (id !in unlocked) continue
                    val isNew = id in earnedNow
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isNew) kb.accent.copy(alpha = 0.18f) else kb.chip)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(achievementLabelRes(id)),
                            color = if (isNew) kb.accent else kb.chipText,
                            fontSize = 11.sp,
                            fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isNew) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                stringResource(R.string.ime_typing_achievement_new_label),
                                color = kb.accent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // Two samples is the minimum that makes a line rather than a dot.
        if (result.samples.size >= 2) {
            Spacer(Modifier.height(8.dp))
            WpmGraph(result, modifier = Modifier.fillMaxWidth().height(74.dp))
            Row {
                LegendDot(stringResource(R.string.ime_typing_wpm_label), kb.accent)
                Spacer(Modifier.width(10.dp))
                LegendDot(stringResource(R.string.ime_typing_raw_label), kb.secondaryText)
                Spacer(Modifier.weight(1f))
                val config = typingConfigLabel(
                    context,
                    result.mode,
                    settings.typingTest.duration,
                    settings.typingTest.wordCount,
                )
                // English goes unnamed, the way the record keys leave it;
                // any other language is part of what the score means.
                val languageId = state.typingTest.languageId
                Text(
                    if (languageId == "en") {
                        config
                    } else {
                        stringResource(
                            R.string.ime_typing_config_language_label,
                            config,
                            LanguageRegistry.byId(languageId).displayName,
                        )
                    },
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                )
            }
        }

        if (history.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(
                    R.plurals.ime_typing_history_count,
                    history.size,
                    history.size,
                ),
                color = kb.secondaryText,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(3.dp))
            HistoryBars(history, modifier = Modifier.fillMaxWidth().height(26.dp))
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolPanelChip(stringResource(R.string.ime_typing_again_action), selected = true) {
                onAction(TypingTestAction.Restart)
            }
            ToolPanelChip(stringResource(R.string.ime_typing_insert_score_action)) {
                onAction(TypingTestAction.InsertResult)
            }
        }
        Spacer(Modifier.height(6.dp))
        TypingConfigRow(state, onAction, compact = false)
        Spacer(Modifier.height(6.dp))
    }
}

/** The badge label for one achievement id. */
@StringRes
private fun achievementLabelRes(id: String): Int = when (id) {
    TypingAchievements.WPM_100 -> R.string.ime_typing_achievement_wpm100_label
    TypingAchievements.PERFECT -> R.string.ime_typing_achievement_perfect_label
    TypingAchievements.PANGRAM -> R.string.ime_typing_achievement_pangram_label
    else -> R.string.ime_typing_achievement_tests50_label
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(kb.chip)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(value, color = kb.keyText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(label, color = kb.secondaryText, fontSize = 9.sp)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(" $label", color = kb.secondaryText, fontSize = 10.sp)
    }
}

/**
 * Speed over the course of the run: corrected WPM as a filled line, raw
 * WPM behind it, and a mark on every second that contained a mistake.
 */
@Composable
private fun WpmGraph(result: TypingResult, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val samples = result.samples
    val peak = samples.maxOf { maxOf(it.wpm, it.raw) }.coerceAtLeast(10.0)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun x(index: Int) = w * index / (samples.size - 1).coerceAtLeast(1)
        fun y(value: Double) = h - (h * (value / peak)).toFloat()

        // Three faint gridlines to give the curve a sense of scale.
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(
                color = kb.divider,
                start = Offset(0f, h * fraction),
                end = Offset(w, h * fraction),
                strokeWidth = 1f,
            )
        }

        fun path(values: List<Double>): Path = Path().apply {
            values.forEachIndexed { index, value ->
                val point = Offset(x(index), y(value))
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }

        drawPath(
            path(samples.map { it.raw }),
            color = kb.secondaryText.copy(alpha = 0.45f),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round),
        )
        drawPath(
            path(samples.map { it.wpm }),
            color = kb.accent,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )

        // Error markers: the second a mistake landed in, not the running
        // total, so a clean stretch after a bad word shows as clean.
        var previousErrors = 0
        samples.forEachIndexed { index, sample ->
            if (sample.errors > previousErrors) {
                drawCircle(
                    color = errorColor(kb),
                    radius = 2.5f,
                    center = Offset(x(index), y(sample.wpm)),
                )
            }
            previousErrors = sample.errors
        }
    }
}

/** Recent scores as bars, newest on the right, tallest = fastest. */
@Composable
private fun HistoryBars(history: List<Double>, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val peak = history.max().coerceAtLeast(1.0)
    Canvas(modifier = modifier) {
        val gap = 2f
        val barWidth = ((size.width - gap * (history.size - 1)) / history.size).coerceAtLeast(1f)
        history.forEachIndexed { index, value ->
            val height = (size.height * (value / peak)).toFloat().coerceAtLeast(1f)
            // The latest run is the one being read about; the rest are context.
            val color = if (index == history.lastIndex) kb.accent else kb.accent.copy(alpha = 0.3f)
            drawRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
            )
        }
    }
}

// ---- shared controls ----

/**
 * Mode, length, the punctuation/numbers switches, the glide and suggestion
 * options, and the language. Changing any of them deals a new prompt, so
 * the row is deliberately small and out of the way during a run — it is a
 * settings strip, not a scoreboard.
 */
@Composable
private fun TypingConfigRow(
    state: KeyboardUiState,
    onAction: (TypingTestAction) -> Unit,
    compact: Boolean,
) {
    val options = state.settings.typingTest
    val scroll = rememberScrollState()
    // Only the shipped lists carry quotations. A pool built out of a
    // dictionary has none, and a Quote run there would quietly be a word
    // run, so the chip dims to say the mode is not on offer.
    val hasQuotes = TypingWordPools.bundled(state.language.id)?.hasQuotes == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(vertical = if (compact) 1.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (mode in TypingTestMode.entries) {
            ToolPanelChip(
                label = when (mode) {
                    TypingTestMode.TIME -> stringResource(R.string.ime_typing_mode_time_label)
                    TypingTestMode.WORDS -> stringResource(R.string.ime_typing_mode_words_label)
                    TypingTestMode.QUOTE -> stringResource(R.string.ime_typing_mode_quote_label)
                },
                selected = options.mode == mode,
                enabled = mode != TypingTestMode.QUOTE || hasQuotes,
            ) { onAction(TypingTestAction.Mode(mode)) }
        }
        Spacer(Modifier.width(3.dp))
        when (options.mode) {
            TypingTestMode.TIME -> for (seconds in TypingTestDurations) {
                ToolPanelChip(
                    label = "${seconds}s",
                    selected = options.duration == seconds,
                ) { onAction(TypingTestAction.Duration(seconds)) }
            }
            TypingTestMode.WORDS -> for (count in TypingTestWordCounts) {
                ToolPanelChip(
                    label = "$count",
                    selected = options.wordCount == count,
                ) { onAction(TypingTestAction.WordCount(count)) }
            }
            // A quote is whatever length it is; the punctuation and number
            // switches do not apply to it either.
            TypingTestMode.QUOTE -> Unit
        }
        if (options.mode != TypingTestMode.QUOTE) {
            Spacer(Modifier.width(3.dp))
            ToolPanelChip(
                label = stringResource(R.string.ime_typing_punctuation_label),
                selected = options.punctuation,
            ) {
                onAction(TypingTestAction.Punctuation(!options.punctuation))
            }
            ToolPanelChip(
                label = stringResource(R.string.ime_typing_numbers_label),
                selected = options.numbers,
            ) {
                onAction(TypingTestAction.Numbers(!options.numbers))
            }
        }
        Spacer(Modifier.width(3.dp))
        // Glide is offered only where this language and layout can be glided
        // at all — a word list, a letter layout, enough keys — and dims
        // rather than hides elsewhere, so the option is not mistaken for
        // missing. The switch stays as set; it just has nothing to do.
        ToolPanelChip(
            label = stringResource(R.string.ime_typing_glide_label),
            selected = options.glide,
            enabled = state.glideReady,
        ) {
            onAction(TypingTestAction.Glide(!options.glide))
        }
        ToolPanelChip(
            label = stringResource(R.string.ime_typing_suggestions_label),
            selected = options.suggestions,
        ) {
            onAction(TypingTestAction.Suggestions(!options.suggestions))
        }
        Spacer(Modifier.width(3.dp))
        TypingLanguageChip(state, onAction)
    }
}

/**
 * The language the test runs in — the keyboard's — with a popup of every
 * language the enabled layouts cover. Picking one switches the keyboard to
 * that language's first enabled layout, which is what re-deals the prompt:
 * the test cannot be in a language the keys on screen do not type.
 */
@Composable
private fun TypingLanguageChip(state: KeyboardUiState, onAction: (TypingTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.ime_typing_language_desc)
    val settings = state.settings
    // One entry per language, carrying the first enabled layout that types it.
    val choices = remember(settings.enabledLayoutIds, settings.customLayouts) {
        val byLanguage = LinkedHashMap<String, Pair<LanguageDef, String>>()
        for (layoutId in settings.enabledLayoutIds) {
            val language = resolveLayout(settings.customLayouts, layoutId).language()
            if (language.id !in byLanguage) byLanguage[language.id] = language to layoutId
        }
        byLanguage.values.toList()
    }
    Box {
        ToolPanelChip(
            label = state.language.displayName,
            modifier = Modifier.semantics { contentDescription = description },
        ) { open = true }
        if (open) {
            val shape = kb.menuShape()
            Popup(onDismissRequest = { open = false }) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 240.dp)
                        .heightIn(max = 220.dp)
                        .clip(shape)
                        .background(kb.popup)
                        .popupBorder(kb, shape)
                        .padding(vertical = 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for ((language, layoutId) in choices) {
                        val current = language.id == state.language.id
                        Text(
                            language.displayName,
                            color = if (current) kb.accent else kb.popupText,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    if (!current) onAction(TypingTestAction.Language(layoutId))
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The best score for the settings currently selected — shown in the panel
 * header so there is a target in view before the run starts. Takes a
 * [context] because the label it returns is a string resource, and the
 * keyboard's [languageId] because records are kept per language.
 */
internal fun typingHeaderBest(
    context: Context,
    options: TypingTestSettings,
    languageId: String,
): String? {
    val key = typingConfigKey(options.mode, options.duration, options.wordCount, languageId)
    val best = TypingBests.decode(options.bests)[key] ?: return null
    return context.getString(R.string.ime_typing_best_info, best.roundToInt())
}
