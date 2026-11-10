package com.wasimaster.wmkeyboard.app

import android.content.res.Resources
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.emoji.EmojiCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiVariantIndex
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPhase
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundTarget
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.fillRowFor
import com.wasimaster.wmkeyboard.core.layout.arrowRowFor
import com.wasimaster.wmkeyboard.core.layout.numberRowFor
import com.wasimaster.wmkeyboard.core.layout.panelLayers
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.keySound
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.authoredHolds
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.KeyPreviewBandMode
import com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPreviewBand
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyboardPreviewHost
import com.wasimaster.wmkeyboard.ime.ui.keyOverrideId
import com.wasimaster.wmkeyboard.ime.ui.textEditDescription
import com.wasimaster.wmkeyboard.ime.ui.toolLabelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The theme editor's preview as the keyboard itself (issue #148).
 *
 * This is the real [KeyboardScreen] — the same grid, strip, toolbar, popups,
 * press effects, panels and theme crossfade the keyboard draws — fed a state
 * the editor builds instead of the service's: the user's own layout and
 * settings, the theme under edit selected outright, and a [ThemePreviewSandbox]
 * standing in for the field. The old mock-up drew coloured rectangles at phase
 * zero; a user judging a key texture, a popup or a Flow gradient had to leave
 * the editor to see any of them. Here they press a key and watch.
 *
 * Every key goes to the sandbox and nowhere else: nothing is committed, learned
 * or persisted, and a tool that would need the service (voice, the clipboard's
 * history, a web search) opens its panel in the theme and stays inert past
 * that, which is what the panel shows on the real board before it has anything.
 *
 * [miniature] draws the keyboard scaled to fit the editor's pinned strip, at
 * the proportions it has on screen; false draws it at actual size, for the
 * docked view the editor's floating button opens. [sandbox] is hoisted so the
 * two share what has been typed when the user switches between them.
 */
@Composable
fun ThemeKeyboardPreview(
    settings: KeyboardSettings,
    theme: ThemeSpec,
    sandbox: MutableState<ThemePreviewSandbox>,
    miniature: Boolean,
    modifier: Modifier = Modifier,
    onHide: () -> Unit = {},
    /**
     * Told where the board itself was laid out, inside the miniature's scale,
     * for a host that measures it (the image-size guide, #397).
     */
    onBoardPositioned: ((LayoutCoordinates) -> Unit)? = null,
    /**
     * Set while the theme editor is picking a part of the keyboard to style
     * (#265): a key, a tool or the strip goes to it instead of to the sandbox.
     */
    picker: PreviewPicker? = null,
    /**
     * The miniature's size on top of the fit, from the grips the editor draws
     * around it (#967): 1 is as wide as the screen allows, less draws the
     * whole keyboard that much smaller. The docked view ignores it.
     */
    previewScale: Float = 1f,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    // The theme under edit, selected the way the keyboard selects one, so the
    // provider inside KeyboardScreen resolves it through the same path the
    // service does. The auto pair is switched off for the same reason a
    // layout's own theme switches it off: the pair vetoes keyboardThemeId, and
    // an editor whose preview shows the pair's pick instead of the draft is an
    // editor with no preview. Floating and one-handed are chrome around the
    // board rather than part of its look, and both need a window to sit in.
    // While picking, a drag across the letters is a finger finding a key, not
    // a word, so glide is off: every key that is let go of is the one picked.
    val picking = picker != null
    val previewSettings = remember(settings, theme.id, picking) {
        settings.copy(
            keyboardThemeId = theme.id,
            autoTheme = settings.autoTheme.copy(enabled = false),
            floatingKeyboard = false,
            floatingKeyboardLandscape = false,
            oneHandedMode = OneHandedMode.OFF,
            gestureTyping = settings.gestureTyping && !picking,
        )
    }
    val form = remember(configuration.smallestScreenWidthDp) {
        DeviceForm.of(configuration.smallestScreenWidthDp)
    }
    val customs = previewSettings.customLayouts
    val current = sandbox.value
    val layoutId = current.layoutId ?: previewSettings.activeLayoutId
    val spec = remember(customs, layoutId) { resolveLayout(customs, layoutId) }
    val layouts = remember(spec, form, previewSettings.numberRow, customs) {
        previewLayoutSet(spec, form, previewSettings.numberRow, customs)
    }
    val script = remember(spec) { spec.script() }
    val composer = remember(spec, script) { composerFor(script, spec.composerType(), spec.langId) }
    val sandboxContext = remember(previewSettings, spec, layouts) {
        SandboxContext(
            cased = script.hasLetterCase,
            clusterShaping = composer.isClusterShaping,
            capitalize = previewSettings.autoText.capitalize,
            activeLayoutId = previewSettings.activeLayoutId,
            enabledLayoutIds = previewSettings.enabledLayoutIds,
            secondaryLayoutIds = layouts.secondaries.keys,
            hasFnLayer = layouts.fn != null,
            capsLockMs = previewSettings.layoutBehavior.shiftCapsLockMs,
        )
    }
    // Shift as auto-capitalization would leave it: armed on an empty buffer,
    // and re-settled when the grid changes underneath, the way the keyboard
    // re-reads the field on a layout switch.
    LaunchedEffect(spec.id, sandboxContext.cased, sandboxContext.capitalize) {
        sandbox.value = sandbox.value.settled(sandboxContext)
    }

    // The emoji panel's catalog, read from the same asset the service reads,
    // and only once the panel is opened: two thousand entries parsed for a
    // preview nobody opens the emoji on would be the worst kind of eager.
    var emojiCatalog by remember { mutableStateOf<List<EmojiEntry>>(emptyList()) }
    var emojiVariants by remember { mutableStateOf(EmojiVariantIndex.empty()) }
    val wantsEmoji = current.panel == PanelMode.EMOJI
    LaunchedEffect(wantsEmoji) {
        if (!wantsEmoji || emojiCatalog.isNotEmpty()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val catalog = context.assets.open("emoji/catalog.tsv").use { EmojiCatalog.load(it) }
                val variants = runCatching {
                    context.assets.open("emoji/variants.tsv").use { EmojiVariantIndex.load(it) }
                }.getOrDefault(EmojiVariantIndex.empty())
                catalog to variants
            }.getOrNull()
        } ?: return@LaunchedEffect
        emojiCatalog = loaded.first
        emojiVariants = loaded.second
    }

    val state = remember(previewSettings, spec, layouts, current, emojiCatalog, emojiVariants) {
        KeyboardUiState(
            settings = previewSettings,
            language = spec.language(),
            script = script,
            composer = composer,
            layoutId = spec.id,
            layoutName = spec.name,
            layouts = layouts,
            layoutMode = current.layoutMode,
            secondaryLayoutId = current.secondaryLayoutId,
            shiftState = current.shift,
            shiftPressedByUser = current.shiftPressedByUser,
            panel = current.panel,
            suggestions = current.suggestions,
            emojiCatalog = emojiCatalog,
            emojiVariants = emojiVariants,
        )
    }
    // The screen wants a flow, as the service hands it one. Published after
    // the composition that built it, so the screen's collector sees each state
    // exactly once.
    val stateFlow = remember { MutableStateFlow(state) }
    SideEffect { stateFlow.value = state }

    // The keys' feedback, as the settings ask for it. The platform's tuned
    // keyboard click stands in for the service's own vibrator pattern; the
    // sound is the real player, playing the theme's own sound where it names
    // one, exactly as the service resolves it.
    val hapticsOn = previewSettings.haptics.enabled
    val soundSettings = previewSettings.sound
    // Through the shared resolver, so the preview and the service cannot
    // disagree about what a theme's sound field means — including the shapes
    // that mean "no sound of my own", which is what [ThemeSpec.keySound]
    // exists to spell out.
    val themeSound = remember(theme.soundStyle, theme.soundCustomId) { theme.keySound() }
    val sound: (KeySoundTarget, KeySoundPhase) -> Unit = remember(context, soundSettings, themeSound) {
        { target, phase ->
            if (soundSettings.enabled &&
                (phase == KeySoundPhase.PRESS || soundSettings.playRelease) &&
                KeySoundPlayer.outputAllows(context, soundSettings.output)
            ) {
                val style = themeSound?.first ?: soundSettings.style
                val id = themeSound?.second
                    ?: if (style == KeySoundStyle.PACK) soundSettings.packId else soundSettings.customId
                KeySoundPlayer.useStream(soundSettings.stream)
                KeySoundPlayer.play(context, style, soundSettings.volume, id, target, phase)
            }
        }
    }
    // Decoded ahead, as the service does at start: otherwise the first presses
    // pay for synthesising the built-in sounds, or play the system click while
    // a pack is still loading.
    LaunchedEffect(soundSettings, themeSound) {
        if (!soundSettings.enabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                KeySoundPlayer.warmUp(context)
                val style = themeSound?.first ?: soundSettings.style
                if (style == KeySoundStyle.PACK) {
                    KeySoundPlayer.preload(context, themeSound?.second ?: soundSettings.packId)
                }
            }
        }
    }
    // The key's own press. The service's handler for this one plays the key
    // sound as well as buzzing, and every ordinary key reaches its sound only
    // through it — [sound] alone is the separate channel for repeats, swipes
    // and the key coming back up. Handing in the buzz alone left the preview
    // silent on every tap.
    val haptic: (KeySoundTarget) -> Unit = remember(view, hapticsOn, sound) {
        { target ->
            sound(target, KeySoundPhase.PRESS)
            if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
    // A cue that is not a keypress (a popup opening): the buzz without the
    // click, as the service's vibrateOnly.
    val plainHaptic: () -> Unit = remember(view, hapticsOn) {
        { if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    }
    // One set of callbacks for the life of the preview. The screen hands
    // several of them to every key, and a fresh lambda per recomposition there
    // is what stops the grid from skipping — the rule the service's own bound
    // references satisfy by being equal across compositions.
    val actions = remember(sandbox) {
        PreviewActions(
            sandbox = sandbox,
            context = mutableStateOf(sandboxContext),
            enabledTools = mutableStateOf(previewSettings.enabledTools),
            onHide = mutableStateOf(onHide),
            picker = mutableStateOf(picker),
            resources = context.resources,
        )
    }
    SideEffect {
        actions.context.value = sandboxContext
        actions.enabledTools.value = previewSettings.enabledTools
        actions.onHide.value = onHide
        actions.picker.value = picker
    }

    // Neither preview has a window behind the board, so neither holds the
    // key-preview band: ~130dp of empty space that the editor's pinned strip
    // paid for out of the board's own room, and that the docked view opened
    // between the sandbox field and the keys. The docked board lets a bubble
    // out over the sections above it, as the real board does over the app; the
    // miniature is clipped to its own rounded corners, so there a bubble is
    // clamped to the top edge rather than cut in half.
    val bandMode = if (miniature) KeyPreviewBandMode.INSIDE else KeyPreviewBandMode.OUTSIDE
    // No window of its own to blur the app behind: the default window-frost
    // host reports none, so a glass theme previews the way it draws on a
    // phone that cannot blur — on its wallpaper band, or at its fallback
    // opacity.
    val keyboard: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalKeyPreviewBand provides bandMode,
            LocalKeyboardPreviewHost provides true,
        ) {
            KeyboardScreen(
                stateFlow = stateFlow,
                onKey = actions.onKey,
                onKeyPressed = haptic,
                onHaptic = plainHaptic,
                onKeySound = sound,
                onText = actions.onInsert,
                onLayoutSelect = actions.onLayoutSelect,
                onSuggestion = actions.onSuggestion,
                onEmoji = actions.onInsert,
                onEmojiQueryTap = {},
                onPunctuation = actions.onInsert,
                onTextArt = actions.onInsert,
                onToolTap = actions.onToolTap,
                onPanelChange = actions.onPanelChange,
                onClipboardItem = {},
                onClipboardPin = {},
                onClipboardDelete = {},
                onSymbolInsert = actions.onInsert,
                onToolInsert = actions.onInsert,
                onHideKeyboard = actions.onHideKeyboard,
            )
        }
    }

    if (miniature) {
        // The frame's pinned strip sits under the app bar, nowhere near the
        // navigation bar, and the docked chrome pads for one all the same:
        // consumed here so the miniature has no phantom band along its floor.
        MiniatureFrame(
            maxHeight = (configuration.screenHeightDp * MINIATURE_HEIGHT_SHARE).dp,
            userScale = previewScale,
            modifier = modifier.consumeWindowInsets(WindowInsets.navigationBars),
        ) {
            // Rounded on the keyboard itself, inside the scale, so the corners
            // are the keyboard's whatever width it settles at.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (onBoardPositioned == null) {
                            Modifier
                        } else {
                            Modifier.onGloballyPositioned(onBoardPositioned)
                        },
                    ),
            ) { keyboard() }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            SandboxField(
                text = current.text,
                reduceMotion = previewSettings.reduceMotion,
                onClear = { sandbox.value = sandbox.value.copy(text = "").settled(sandboxContext) },
            )
            keyboard()
        }
    }
}

/**
 * Share of the screen's height the miniature may take. The mock-up it replaces
 * was about a quarter of a phone's height; a working keyboard earns a little
 * more, and stops well short of pushing the first section off the screen.
 * Raised from 0.30 so that on a phone the cap does not bind and the miniature
 * fills the width it is given (#769): held to 30%, it drew narrower than the
 * screen with a margin either side, every key a size the keyboard never is.
 */
private const val MINIATURE_HEIGHT_SHARE = 0.40f

/**
 * The callbacks the keyboard is given, held for the life of one preview so
 * their identity never changes under the grid. Everything they read that can
 * change — the reduction context, the tool list, the hide action — is read
 * through a state at call time rather than captured.
 */
private class PreviewActions(
    private val sandbox: MutableState<ThemePreviewSandbox>,
    val context: MutableState<SandboxContext>,
    val enabledTools: MutableState<List<ToolbarTool>>,
    val onHide: MutableState<() -> Unit>,
    val picker: MutableState<PreviewPicker?>,
    private val resources: Resources,
) {
    val onKey: (Key) -> Unit = { key ->
        val pick = picker.value
        if (pick != null) {
            keyPick(key, resources)?.let(pick.onKey)
        } else {
            sandbox.value = sandbox.value.onKey(key, context.value, SystemClock.uptimeMillis())
        }
    }
    val onInsert: (String) -> Unit = { text ->
        if (picker.value == null) sandbox.value = sandbox.value.inserted(text, context.value)
    }
    val onSuggestion: (String) -> Unit = { word ->
        val pick = picker.value
        if (pick != null) {
            pick.onStrip()
        } else {
            sandbox.value = sandbox.value.onSuggestion(word, context.value)
        }
    }
    val onLayoutSelect: (String) -> Unit = { id ->
        if (picker.value == null) sandbox.value = sandbox.value.selectingLayout(id)
    }
    // The toolbox launcher and the bar's back chevron are the toolbar's own
    // chrome, so while picking they pick the toolbar.
    val onPanelChange: (PanelMode) -> Unit = { panel ->
        val pick = picker.value
        if (pick != null) {
            pick.onToolbar()
        } else {
            sandbox.value = sandbox.value.onPanel(panel)
        }
    }
    val onToolTap: (ToolbarTool) -> Unit = { tool ->
        val pick = picker.value
        when {
            pick != null -> pick.onToolbar()
            tool == ToolbarTool.HIDE_KEYBOARD -> onHide.value()
            tool in enabledTools.value -> sandbox.value = sandbox.value.onTool(tool)
        }
    }
    val onHideKeyboard: () -> Unit = { onHide.value() }
}

/**
 * Where the preview's presses go while the theme editor is picking the part of
 * the keyboard to style (#265), instead of into the sandbox.
 */
@Stable
class PreviewPicker(
    /** A key: the style entry it answers to, and what to call it. */
    val onKey: (KeyPick) -> Unit,
    /** A tool, the toolbox launcher or the bar's chevron. */
    val onToolbar: () -> Unit,
    /** A word on the suggestion strip. */
    val onStrip: () -> Unit,
)

/**
 * A key picked on the preview: [id] is the single-key style entry the picker
 * writes (`keyOverrideId`, the most specific name the key answers to), and
 * [title] is the key as the user knows it — its letter, its tool, its
 * operation — for the sheet's heading.
 */
@Immutable
data class KeyPick(val id: String, val title: String)

/** The pick for [key], or null for a key no style can name (a blank letter key). */
private fun keyPick(key: Key, resources: Resources): KeyPick? {
    val id = keyOverrideId(key) ?: return null
    val title = when (val action = key.action) {
        KeyAction.Text -> key.label.uppercase()
        is KeyAction.Edit -> resources.getString(textEditDescription(action.op))
        is KeyAction.Tool -> resources.getString(toolLabelRes(action.tool))
        else -> key.label.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?.let { "${humanisedOverrideId(id)} ($it)" }
            ?: humanisedOverrideId(id)
    }
    return KeyPick(id, title)
}

/**
 * An entry name the way a list reads it: `LANGUAGESWITCH` as "Languageswitch",
 * `EDIT_PAGE_UP` as "Edit page up". The fallback for names with no label of
 * their own; the editor's list names the common ones properly.
 */
fun humanisedOverrideId(id: String): String =
    id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * The grids the preview's keyboard can reach, compiled the way the service
 * compiles them for a text field — and with every grid's own theme taken off.
 * A layout that names a theme (issue #61) would otherwise paint the preview
 * in that theme rather than the one being edited, and the editor is the one
 * place the draft must win.
 */
private fun previewLayoutSet(
    spec: LayoutSpec,
    form: DeviceForm,
    numberRowShown: Boolean,
    customs: List<LayoutSpec>,
): LayoutSet {
    val safe = spec.repair().spec
    val letters = safe.compile(LayoutLayer.LETTERS)
    val expand = form.isTablet && safe.tabletExpand
    val gridWidth = if (expand) tabletGridWidth(letters, form) else null
    return LayoutSet(
        letters = (if (gridWidth != null) letters.expandForTablet(form, numberRowShown) else letters)
            .unthemed(),
        symbols = safe.compile(LayoutLayer.SYMBOLS).unthemed(),
        symbolsShifted = safe.compile(LayoutLayer.SYMBOLS_SHIFTED).unthemed(),
        fn = safe.layer(LayoutLayer.FN)?.let { safe.compile(LayoutLayer.FN).unthemed() },
        number = safe.layer(LayoutLayer.NUMBER)?.let { safe.compile(LayoutLayer.NUMBER).unthemed() },
        // A panel's own theme off too (#779): a Numpad tab naming a theme
        // painted the open pad in it, not the draft.
        panels = safe.panelLayers.mapValues { (kind, grid) ->
            val unthemed = if (grid.themeId == null) grid else grid.copy(themeId = null)
            PanelLayoutSpec(kind, unthemed, appearance = safe.appearance)
        },
        numberRows = buildMap {
            safe.numberRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS_SHIFTED)?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
            safe.numberRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
        },
        symbolsFillRow = safe.fillRowFor(LayoutLayer.SYMBOLS),
        arrowRows = buildMap {
            safe.arrowRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
            safe.arrowRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
            safe.arrowRowFor(LayoutLayer.SYMBOLS_SHIFTED)?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
            safe.arrowRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
        },
        gridWidth = gridWidth,
        // Null rather than the layout's: a panel with no theme of its own falls
        // back to this one (see `screenThemeId`), so a themed layout drew every
        // panel the preview opened — the Numpad first among them (#779) — in its
        // theme while the letters showed the draft.
        themeId = null,
        secondaries = secondaryLayouts(customs).associate { secondary ->
            secondary.id to secondary.repair().spec.compile(LayoutLayer.LETTERS).unthemed()
        },
        authoredHolds = authoredHolds(spec, letters),
    )
}

private fun KeyboardLayout.unthemed(): KeyboardLayout =
    if (themeId == null) this else copy(themeId = null)

/**
 * Lays [content] out at the width of the screen — the width the keyboard has
 * when it is up — and draws it scaled down to fit the width on offer and
 * [maxHeight], then by [userScale] on top. It takes only the width it draws
 * in; the caller centres it. Scaling the drawn result rather than measuring the
 * keyboard narrower is what keeps the proportions honest: a key is as tall
 * for its width here as it is under the user's thumb, only smaller.
 *
 * The scale goes on the placement layer, so the keys are hit-tested through it
 * and a press on the miniature lands on the key it looks like it landed on.
 */
@Composable
private fun MiniatureFrame(
    maxHeight: Dp,
    userScale: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    Layout(content = { Box { content() } }, modifier = modifier) { measurables, constraints ->
        val fullWidth = screenWidthDp.dp.roundToPx().coerceAtLeast(1)
        // Bounded by the screen, as the keyboard's own window bounds it: a
        // panel that fills what it is given must not be given infinity.
        val placeable = measurables.first().measure(
            Constraints(
                minWidth = fullWidth,
                maxWidth = fullWidth,
                maxHeight = screenHeightDp.dp.roundToPx().coerceAtLeast(1),
            ),
        )
        val maxHeightPx = maxHeight.roundToPx().coerceAtLeast(1)
        val scale = minOf(
            1f,
            constraints.maxWidth.toFloat() / placeable.width.coerceAtLeast(1),
            maxHeightPx.toFloat() / placeable.height.coerceAtLeast(1),
        ) * userScale.coerceIn(0f, 1f)
        val width = (placeable.width * scale).roundToInt()
        val height = (placeable.height * scale).roundToInt()
        // Its own width rather than all that is on offer, so the editor's
        // resize grips land on the keyboard's corners and not the screen's.
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/**
 * The field the docked keyboard types into: the current line of the sandbox,
 * a caret, and a way to clear it. Drawn in the app's own colours rather than
 * the theme's, so it reads as the app above the keyboard — which is what it
 * stands in for — and not as part of the board.
 */
@Composable
private fun SandboxField(text: String, reduceMotion: Boolean, onClear: () -> Unit) {
    val line = text.substringAfterLast('\n')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The caret sits before a hint and after typed text, where a
            // field puts it.
            if (line.isEmpty()) {
                Caret(reduceMotion)
                Text(
                    stringResource(R.string.ime_theme_preview_field_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                Text(
                    line,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Caret(reduceMotion)
            }
        }
        if (text.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_theme_preview_clear_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A text caret that blinks, or holds still for a reader who asked for less motion. */
@Composable
private fun Caret(reduceMotion: Boolean) {
    val alpha = if (reduceMotion) {
        1f
    } else {
        val blink = rememberInfiniteTransition(label = "caret")
        blink.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = CARET_PERIOD_MS
                    1f at 0 using LinearEasing
                    1f at CARET_PERIOD_MS / 2 - 1 using LinearEasing
                    0f at CARET_PERIOD_MS / 2 using LinearEasing
                    0f at CARET_PERIOD_MS - 1 using LinearEasing
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "caretAlpha",
        ).value
    }
    Box(
        modifier = Modifier
            .padding(start = 1.dp)
            .width(2.dp)
            .height(20.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary),
    )
}

private const val CARET_PERIOD_MS = 1000
