package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.WordCardAction
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * The word card's spelling editor (#138), drawn in the suggestion strip's own
 * row while the card is respelling a word.
 *
 * The card itself is a window over the whole keyboard, so it cannot be typed
 * into; it steps aside and this bar takes its place, exactly as the AI Custom
 * instruction collapses its panel to leave the key rows free. Every keystroke
 * goes into [KeyboardUiState.wordSpell], never into the app behind the
 * keyboard — see `keysTakenByKeyboard`.
 *
 * Tick applies the respelling and brings the card back on the new word; cross
 * leaves the spelling as it was.
 *
 * The draft has a caret and a selection of its own (#204), drawn here and kept
 * by the service: a tap puts the caret, a drag selects, a hold selects the
 * whole word. The spacebar scrub, shift over a selection and a glide all act
 * on them, which is what makes this edit like a text field without being one
 * the IME would have to raise.
 *
 * With motion on, what a key puts into the draft pops in where it landed, the
 * hint and the draft cross-fade when the draft empties or starts, and the tick
 * eases between usable and not. Deleting is never animated: a letter that is
 * gone should be gone.
 */
@Composable
internal fun WordSpellBar(
    state: KeyboardUiState,
    onAction: (WordCardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spell = state.wordSpell ?: return
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings).coerceAtLeast(SPELL_BAR_MIN_HEIGHT))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(kb.suggestionText.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            MotionCrossfade(
                target = spell.draft.isEmpty(),
                animate = animate,
                contentAlignment = Alignment.CenterStart,
                label = "wordSpellDraft",
            ) { empty ->
                if (empty) {
                    // An empty draft cannot be applied, so the bar says what the
                    // keys are for rather than showing a blank box.
                    Text(
                        text = stringResource(R.string.ime_word_spell_hint, spell.word),
                        style = MaterialTheme.typography.bodyMedium,
                        color = kb.suggestionText.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // On its way out (the draft just emptied) it answers no
                    // touch: a drag would select in a draft that is not there.
                    val live = spell.draft.isNotEmpty()
                    SpellDraft(
                        draft = spell.draft,
                        cursor = spell.cursor,
                        selectionStart = spell.selectionStart,
                        selectionEnd = spell.selectionEnd,
                        textColor = kb.suggestionText,
                        accent = kb.accent,
                        animate = animate,
                        onSelect = { start, end ->
                            if (live) onAction(WordCardAction.SelectSpelling(start, end))
                        },
                    )
                }
            }
        }
        // The way in for a spelling the keys cannot type (#1190): the bar is
        // no field of the app's, so the system's own Paste never reaches it.
        SpellBarButton(
            icon = Icons.Outlined.ContentPaste,
            description = stringResource(R.string.ime_word_spell_paste_desc),
            enabled = true,
            tint = kb.suggestionText,
            animate = animate,
        ) {
            feedback()
            onAction(WordCardAction.PasteSpelling)
        }
        SpellBarButton(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.ime_word_spell_cancel_desc),
            enabled = true,
            tint = kb.suggestionText,
            animate = animate,
        ) {
            feedback()
            onAction(WordCardAction.CancelSpelling)
        }
        SpellBarButton(
            icon = Icons.Outlined.Check,
            description = stringResource(R.string.ime_word_spell_apply_desc),
            // A respelling to nothing would delete the word by the back door,
            // and one that changes nothing has nothing to apply.
            enabled = spell.draft.isNotBlank() && spell.draft != spell.word,
            tint = kb.accent,
            animate = animate,
            disabledReason = DisabledReason(stringResource(R.string.ime_disabled_spell_unchanged)),
        ) {
            feedback()
            onAction(WordCardAction.CommitSpelling)
        }
    }
}

/**
 * The draft with its selection shaded and, when nothing is selected, its
 * caret drawn. Touches answer in draft offsets through [onSelect], anchor
 * first; the service owns the result, so what is drawn is always what the
 * next key will edit.
 *
 * With [animate], what a change inserted ([DraftPop]) pops in: drawn apart
 * from the rest, scaled up from small around its own middle and faded in,
 * while the letters either side stay exactly where they are. Drawn, never
 * laid out, so the caret and every touch offset are right from the first
 * frame.
 */
@Composable
private fun SpellDraft(
    draft: String,
    cursor: Int,
    selectionStart: Int,
    selectionEnd: Int,
    textColor: Color,
    accent: Color,
    animate: Boolean,
    onSelect: (start: Int, end: Int) -> Unit,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    // Spotted in composition, so the frame that brings the letter in already
    // draws it small.
    val pop = remember { DraftPop(draft) }
    pop.observe(draft, animate)
    LaunchedEffect(pop.generation) {
        pop.progress?.animateTo(1f, spring(dampingRatio = POP_DAMPING, stiffness = Spring.StiffnessMedium))
    }
    val select by rememberUpdatedState(onSelect)
    val length by rememberUpdatedState(draft.length)
    val shade = accent.copy(alpha = 0.32f)
    val text = remember(draft, selectionStart, selectionEnd, shade) {
        buildAnnotatedString {
            append(draft)
            if (selectionStart != selectionEnd) {
                addStyle(SpanStyle(background = shade), selectionStart, selectionEnd)
            }
        }
    }
    fun offsetAt(position: Offset): Int =
        layout.value?.getOffsetForPosition(position)?.coerceIn(0, length) ?: length
    BasicText(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, color = textColor),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout.value = it },
        modifier = Modifier
            // The whole box width, so a tap past the last letter lands the
            // caret at the end instead of missing the text.
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offsetAt(it).let { at -> select(at, at) } },
                    // Holding is the quick way to the one thing a word bar
                    // is ever selected for: all of it, ready for shift.
                    onLongPress = { select(0, length) },
                )
            }
            .pointerInput(Unit) {
                var anchor = 0
                detectDragGestures(
                    onDragStart = { anchor = offsetAt(it); select(anchor, anchor) },
                    onDrag = { change, _ ->
                        change.consume()
                        select(anchor, offsetAt(change.position))
                    },
                )
            }
            .drawWithContent {
                val popping = pop.progress
                val p = popping?.value ?: 1f
                val box = layout.value?.takeIf { popping != null && (p < 1f || popping.isRunning) }
                    ?.let { pop.boxIn(it, size.height) }
                if (box == null) {
                    drawContent()
                } else {
                    // Everything but the new letters, as it is…
                    clipRect(box.left, box.top, box.right, box.bottom, clipOp = ClipOp.Difference) {
                        this@drawWithContent.drawContent()
                    }
                    // …then the new letters alone, growing in.
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(
                            Rect(Offset.Zero, size),
                            Paint().apply { alpha = (p * POP_FADE_RATE).coerceIn(0f, 1f) },
                        )
                    }
                    scale(POP_FROM + (1f - POP_FROM) * p, pivot = box.center) {
                        clipRect(box.left, box.top, box.right, box.bottom) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    drawIntoCanvas { it.restore() }
                }
                if (selectionStart != selectionEnd) return@drawWithContent
                val laid = layout.value ?: return@drawWithContent
                val at = cursor.coerceIn(0, laid.layoutInput.text.length)
                val rect = laid.getCursorRect(at)
                drawRect(
                    color = accent,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(CARET_WIDTH.toPx(), rect.height),
                )
            },
    )
}

/**
 * What the last change put into the draft, for [SpellDraft] to pop in: the
 * range of offsets [start] to [end] that is new, found as what lies between
 * the parts the old and new draft share at each end. Only an insertion pops;
 * a deletion or a replacement no longer than what it replaced drops any pop
 * still running.
 *
 * Plain fields, set in composition like [InkDissolve]'s: [progress] is the
 * only thing a frame reads, and it is read in the draw.
 */
@Stable
private class DraftPop(private var seen: String) {
    /** Bumped for every pop; the effect that runs it is keyed on it. */
    var generation = 0
        private set

    /** 0 just typed, 1 settled; null with nothing popping. */
    var progress: Animatable<Float, AnimationVector1D>? = null
        private set

    private var start = 0
    private var end = 0

    fun observe(draft: String, motion: Boolean) {
        if (draft == seen) return
        val before = seen
        seen = draft
        progress = null
        if (!motion || draft.length <= before.length) return
        val head = draft.commonPrefixWith(before).length
        val tail = draft.substring(head).commonSuffixWith(before.substring(head)).length
        if (draft.length - tail <= head) return
        start = head
        end = draft.length - tail
        progress = Animatable(0f)
        generation++
    }

    /** The new letters' strip of the text, full height so no accent is cut, or null once off the text. */
    fun boxIn(layout: TextLayoutResult, height: Float): Rect? {
        val length = layout.layoutInput.text.length
        if (end > length || start >= end) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in start until end) {
            val glyph = layout.getBoundingBox(i)
            left = minOf(left, glyph.left)
            right = maxOf(right, glyph.right)
        }
        return if (right > left) Rect(left, 0f, right, height) else null
    }
}

/**
 * One of the bar's two round buttons. Turning usable or not, it eases between
 * full and dimmed and settles to size, rather than blinking.
 */
@Composable
private fun SpellBarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    animate: Boolean,
    /** What holding the button says while it is greyed out; see [IconHint]. */
    disabledReason: DisabledReason? = null,
    onClick: () -> Unit,
) {
    val spec: AnimationSpec<Float> = if (animate) tween(BUTTON_MS) else snap()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, spec, label = "spellButtonAlpha")
    val scale by animateFloatAsState(if (enabled) 1f else BUTTON_OFF_SCALE, spec, label = "spellButtonScale")
    IconHint(description, disabledReason = disabledReason.takeUnless { enabled }) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(
                icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/**
 * The shortest the bar may be. The strip's own height is what it normally
 * takes, but that height is zero with the toolbar turned off — and the bar
 * has to be readable wherever the card can be opened from.
 */
private val SPELL_BAR_MIN_HEIGHT = 40.dp

private val CARET_WIDTH = 2.dp

/** A new letter grows from [POP_FROM] of its size with a touch of overshoot, opaque by half way. */
private const val POP_FROM = 0.4f
private const val POP_DAMPING = 0.6f
private const val POP_FADE_RATE = 2f

/** A button easing between usable and not, and how small it sits while not. */
private const val BUTTON_MS = 160
private const val BUTTON_OFF_SCALE = 0.88f
