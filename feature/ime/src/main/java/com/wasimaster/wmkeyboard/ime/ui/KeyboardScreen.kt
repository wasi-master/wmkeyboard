package com.wasimaster.wmkeyboard.ime.ui

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PhotoSizeSelectActual
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.VerticalDivider
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentWithReceiverOf
import android.content.res.Configuration
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sizingValuesFor
import com.wasimaster.wmkeyboard.core.settings.activeThemeSpec
import com.wasimaster.wmkeyboard.core.settings.applyThemeOverrides
import com.wasimaster.wmkeyboard.core.settings.resolvedFor
import com.wasimaster.wmkeyboard.core.input.MorseCode
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import android.os.SystemClock
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.keySpelling
import com.wasimaster.wmkeyboard.core.accessibility.KeyboardPassthrough
import com.wasimaster.wmkeyboard.core.settings.ScreenReaderMode
import kotlinx.coroutines.delay
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntity
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.clipboard.matchesQuery
import com.wasimaster.wmkeyboard.core.emoji.EmojiNames
import com.wasimaster.wmkeyboard.core.emoji.EmojiVariantIndex
import com.wasimaster.wmkeyboard.core.emoji.TextArt
import com.wasimaster.wmkeyboard.core.text.EmojiGraphemes
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import androidx.compose.ui.graphics.lerp
import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.core.theme.brush
import com.wasimaster.wmkeyboard.core.ui.ToolPaint
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import com.wasimaster.wmkeyboard.core.grammar.GrammarFix
import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.handwriting.HwPoint
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.script.FancyStyle
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.TextDirection
import com.wasimaster.wmkeyboard.core.script.mapDigits
import com.wasimaster.wmkeyboard.core.script.resolveNumeralDigits
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.LatinAccents
import com.wasimaster.wmkeyboard.core.settings.EmojiBarContent
import com.wasimaster.wmkeyboard.core.settings.EmojiBarCountRange
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.sourceChar
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.HoldRepeatCursorTools
import com.wasimaster.wmkeyboard.core.settings.KeyPopupSettings
import com.wasimaster.wmkeyboard.core.settings.KeyRepeatSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditingSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isOwnRow
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolboxPageSizeRange
import com.wasimaster.wmkeyboard.core.settings.ToolboxSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.usableTools
import com.wasimaster.wmkeyboard.core.settings.toolOpensScreen
import com.wasimaster.wmkeyboard.core.settings.toolboxPage
import com.wasimaster.wmkeyboard.core.settings.toolboxPageCount
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.HintModifiers
import com.wasimaster.wmkeyboard.core.tools.HintPlan
import com.wasimaster.wmkeyboard.core.tools.HintSurface
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.tools.buildHintPlan
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import com.wasimaster.wmkeyboard.core.tools.resolvedToolLetters
import com.wasimaster.wmkeyboard.core.tools.suggestionSlotOrder
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.symbolChipLabel
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.WebResult
import com.wasimaster.wmkeyboard.ime.AiUi
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.isNumericPad
import com.wasimaster.wmkeyboard.ime.hasMediaSearch
import com.wasimaster.wmkeyboard.ime.HandwritingStatus
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PluginPanelUi
import com.wasimaster.wmkeyboard.ime.ModifierState
import com.wasimaster.wmkeyboard.ime.authoredNumberRow
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.SymbolCatalog
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.ime.PwSettingAction
import com.wasimaster.wmkeyboard.ime.TypingTestAction
import com.wasimaster.wmkeyboard.ime.VoiceBarAction
import com.wasimaster.wmkeyboard.ime.voiceChipOnly
import com.wasimaster.wmkeyboard.ime.SizingAction
import com.wasimaster.wmkeyboard.ime.SoundHapticAction
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.ShiftState
import com.wasimaster.wmkeyboard.ime.SnippetOffer
import com.wasimaster.wmkeyboard.ime.displayCaseForShift
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.ClipboardKeyAction
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPhase
import com.wasimaster.wmkeyboard.core.feedback.KeySoundRole
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.drawnFontScale
import com.wasimaster.wmkeyboard.core.layout.drawnLabel
import com.wasimaster.wmkeyboard.core.layout.drawnLabelScale
import com.wasimaster.wmkeyboard.core.layout.fallbackLabel
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
import com.wasimaster.wmkeyboard.core.layout.expandNumberRowForTablet
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.hasRowSpans
import com.wasimaster.wmkeyboard.core.layout.KeySlot
import com.wasimaster.wmkeyboard.core.layout.roleIn
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.sidePadFor
import com.wasimaster.wmkeyboard.core.layout.spanBands
import com.wasimaster.wmkeyboard.core.layout.spanSlots
import com.wasimaster.wmkeyboard.core.layout.Layouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import java.io.File

/**
 * Fired at pointer-down on any key so feedback (haptics) lands on press,
 * not on release when the key's action commits.
 */
internal val LocalKeyPressFeedback = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Haptic only, no key sound — for cues that are not a keypress. The emoji
 * long-press popup uses it: the click sound would announce an insertion the
 * long press deliberately did not make.
 */
internal val LocalHapticFeedback = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Key sound only, no haptic — the sound-preserving fallback for events whose
 * vibration a fine-grained toggle has turned off (space press, backspace-swipe
 * word delete, held-key repeat). Firing this instead of the full press feedback
 * keeps the click sound while dropping just the buzz.
 */
internal val LocalKeySound = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * [LocalKeyPressFeedback] and [LocalKeySound] again, told *which* key.
 *
 * A sound pack can carry a separate set of recordings per [KeySoundRole], so
 * the key grid — and only the key grid — needs to say what it just pressed.
 * Everything else that fires key feedback (panel buttons, the suggestion strip,
 * the toolbar) is not a key on the board and goes on using the two role-less
 * locals above, which this file provides bound to [KeySoundRole.DEFAULT].
 *
 * Two more locals rather than changing the type of the existing two: those have
 * thirty-odd call sites across the panels, none of which have a [Key] in scope
 * or any business inventing one.
 */
internal val LocalKeyRoleFeedback = staticCompositionLocalOf<(KeySoundRole) -> Unit> { {} }

/**
 * [LocalKeySound] told which key, and which half of the keystroke; see
 * [LocalKeyRoleFeedback].
 *
 * The phase is here rather than on [LocalKeyRoleFeedback] because only the
 * sound has two halves: a key coming back up on a mechanical board makes a
 * noise, and does not buzz.
 */
internal val LocalKeyRoleSound =
    staticCompositionLocalOf<(KeySoundRole, KeySoundPhase) -> Unit> { { _, _ -> } }

/**
 * Sink for the A/C/V/X clipboard long-press shortcuts, provided once at the
 * root so it does not have to thread through every key-grid layer.
 */
internal val LocalClipboardKeyAction = staticCompositionLocalOf<(ClipboardKeyAction) -> Unit> { {} }

/**
 * Whether a backspace press would still delete anything (text before the
 * cursor, a selection, or an active search query). Held-backspace repeat
 * loops poll this and stop once the field is empty, instead of buzzing
 * away at nothing.
 */
internal val LocalCanDelete = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * Like [LocalCanDelete] but always about the real text field, even while a
 * panel search is active and the backspace key is editing a query — for
 * controls that delete from the field directly (the emoji search bar's
 * backspace).
 */
internal val LocalCanDeleteField = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * [LocalCanDelete] for the ⌦ key: whether anything remains *after* the cursor.
 * Its own local because the two run out at opposite ends of the text — a caret
 * parked at the end of a full field can still backspace forever and has
 * nothing to forward-delete.
 */
internal val LocalCanForwardDelete = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * Deletes the word before the cursor. Fired per step of a sideways drag on
 * the backspace key; provided at the root like [LocalCanDelete] so it does
 * not have to thread through every key-grid layer.
 */
internal val LocalDeleteWord = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Steps the text cursor vertically (sign = direction, magnitude = steps).
 * Fired by the spacebar's optional 2-D touchpad slide; provided at the root
 * like [LocalDeleteWord] so it does not thread through every key-grid layer.
 */
internal val LocalCursorMoveVertical = staticCompositionLocalOf<(Int) -> Unit> { {} }

/**
 * Dismisses the keyboard. Provided at the root so the spacebar's optional
 * swipe-down-to-hide gesture can reach it without threading a callback down
 * through the key grid.
 */
internal val LocalHideKeyboard = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Whether TalkBack (or another explore-by-touch service) is currently
 * driving the screen. Resolved once at the root rather than per key —
 * every key would otherwise register its own listener.
 */
internal val LocalTouchExploration = staticCompositionLocalOf { false }

/**
 * Whether the app's touch-exploration pass-through service is running, so the
 * keyboard's window really does get the raw touch stream under a screen
 * reader. [ScreenReaderMode.PASSTHROUGH] falls back to
 * [ScreenReaderMode.EXPLORE] without it — the user may have picked the mode
 * but never granted the service, and keys that neither announce nor honour
 * explore-by-touch would be worse than either mode alone.
 */
internal val LocalPassthroughService = staticCompositionLocalOf { false }

/**
 * Live touch-exploration state. The keyboard has to react to this while
 * running, not only at start: users toggle TalkBack with a shortcut mid-task
 * and the key gesture handling has to swap over with it.
 */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The icon for the enter action the focused field asked for
 * (EditorInfo.imeOptions). Shared, because a panel that draws its own enter
 * button has to agree with the key rows — a search field showing a magnifier on
 * one and a return arrow on the other is worse than either choice alone.
 *
 * [EnterAction.CUSTOM] has no icon of its own by design: the app supplied
 * wording instead, so callers with room for text draw
 * [KeyboardUiState.enterActionLabel], and everyone else falls back to the
 * newline glyph — which is what onEnter does with a blank label anyway.
 */
internal fun enterActionIcon(action: EnterAction): ImageVector = IconDefaults.forEnterAction(action)

/**
 * What a screen reader says for one key, before it is worded.
 *
 * Held as a resource id rather than as finished text so that [keyVisual] stays
 * free of an Android context — it is a plain function on purpose, see
 * [KeyVisual] — and so a locale change re-reads every key.
 */
@Immutable
internal data class SpokenLabel(
    /** The wording. 0 when the key speaks [text] as it is (a letter, a symbol). */
    @StringRes val textRes: Int = 0,
    /** What the key itself supplies: its label, or wording the app chose. */
    val text: String = "",
    /** A name that fills the one argument of [textRes]; 0 when it takes none. */
    @StringRes val argRes: Int = 0,
) {
    fun resolve(resources: Resources): String = when {
        textRes == 0 -> text
        argRes != 0 -> resources.getString(textRes, resources.getString(argRes))
        else -> resources.getString(textRes)
    }
}

/** [SpokenLabel.resolve] for a caller that is already drawing. */
@Composable
private fun SpokenLabel.resolved(): String = resolve(LocalContext.current.resources)

/** What to call the enter key in this field, for screen readers. */
internal fun enterActionSpoken(state: KeyboardUiState): SpokenLabel =
    when (state.effectiveEnterAction) {
        EnterAction.SEARCH -> SpokenLabel(R.string.ime_enter_search)
        EnterAction.SEND -> SpokenLabel(R.string.ime_enter_send)
        EnterAction.GO -> SpokenLabel(R.string.ime_enter_go)
        EnterAction.NEXT -> SpokenLabel(R.string.ime_enter_next)
        EnterAction.PREVIOUS -> SpokenLabel(R.string.ime_enter_previous)
        EnterAction.DONE -> SpokenLabel(R.string.ime_enter_done)
        EnterAction.CUSTOM -> state.enterActionLabel
            ?.let { SpokenLabel(text = it) }
            ?: SpokenLabel(R.string.ime_enter_default)
        EnterAction.DEFAULT -> SpokenLabel(R.string.ime_enter_default)
    }

/** The enter key's name, worded for a caller that draws it. */
@Composable
internal fun enterActionName(state: KeyboardUiState): String = enterActionSpoken(state).resolved()

/**
 * What a screen reader should call this key. Punctuation and whitespace get
 * spoken names because TalkBack either skips them or reads them as silence,
 * which makes a symbol layout unusable by ear.
 */
private fun spokenLabel(key: Key, state: KeyboardUiState): SpokenLabel = when (key.action) {
    KeyAction.Space -> SpokenLabel(R.string.ime_key_space)
    KeyAction.Delete -> SpokenLabel(R.string.ime_key_delete)
    KeyAction.ForwardDelete -> SpokenLabel(R.string.ime_key_forward_delete)
    KeyAction.Enter -> enterActionSpoken(state)
    // Named for what it does, not "enter": on the field this key appears for,
    // "enter" is the word for the key beside it that sends.
    KeyAction.Newline -> SpokenLabel(R.string.ime_key_newline)
    KeyAction.Shift -> when (state.shiftState) {
        ShiftState.CAPS_LOCK -> SpokenLabel(R.string.ime_key_caps_lock_on)
        ShiftState.ON -> SpokenLabel(R.string.ime_key_shift_on)
        ShiftState.OFF -> SpokenLabel(R.string.ime_key_shift)
    }
    KeyAction.CapsLock -> SpokenLabel(
        if (state.shiftState == ShiftState.CAPS_LOCK) {
            R.string.ime_key_caps_lock_on
        } else {
            R.string.ime_key_caps_lock
        },
    )
    KeyAction.LanguageSwitch -> SpokenLabel(R.string.ime_key_language_switch)
    KeyAction.InputMethodPicker -> SpokenLabel(R.string.ime_key_input_method_picker)
    KeyAction.Emoji -> SpokenLabel(R.string.ime_key_emoji)
    is KeyAction.Mod -> {
        val nameRes = when ((key.action as KeyAction.Mod).key) {
            ModifierKey.CTRL -> R.string.ime_key_modifier_control
            ModifierKey.ALT -> R.string.ime_key_modifier_alt
            ModifierKey.META -> R.string.ime_key_modifier_meta
        }
        when (state.modifiers[(key.action as KeyAction.Mod).key]) {
            ModifierState.LOCKED -> SpokenLabel(R.string.ime_key_modifier_locked, argRes = nameRes)
            ModifierState.ARMED -> SpokenLabel(R.string.ime_key_modifier_on, argRes = nameRes)
            ModifierState.OFF -> SpokenLabel(nameRes)
        }
    }
    // The six the action picker offers by name get spoken names: their labels are
    // bare glyphs (⇥, ←, →) that TalkBack reads as symbol noise or skips outright,
    // and the tablet grid puts three of them on screen at once.
    is KeyAction.SendKey -> sendKeyNames[(key.action as KeyAction.SendKey).keyCode]
        ?.let { SpokenLabel(it) }
        ?: if (key.label.isBlank()) {
            SpokenLabel(R.string.ime_key_generic)
        } else {
            SpokenLabel(text = key.label)
        }
    // A tool key draws the tool's icon and usually carries no label at all, so
    // its name is the tool's own — the same words the toolbar speaks.
    is KeyAction.Tool -> if (key.label.isBlank()) {
        SpokenLabel(toolLabelRes((key.action as KeyAction.Tool).tool))
    } else {
        SpokenLabel(text = key.label)
    }
    else -> {
        val label = displayLabel(key, state)
        punctuationNames[label]?.let { SpokenLabel(it) } ?: SpokenLabel(text = label)
    }
}

/**
 * Spoken names for the key codes a layout can bind by name. Keyed on the raw
 * code rather than `KeyEvent.KEYCODE_*` to match [KeyAction.fallbackLabel],
 * which draws the glyphs these name.
 */
private val sendKeyNames = mapOf(
    61 to R.string.ime_key_tab,
    111 to R.string.ime_key_escape,
    19 to R.string.ime_key_arrow_up,
    20 to R.string.ime_key_arrow_down,
    21 to R.string.ime_key_arrow_left,
    22 to R.string.ime_key_arrow_right,
)

private val punctuationNames = mapOf(
    "." to R.string.ime_punct_period,
    "," to R.string.ime_punct_comma,
    "?" to R.string.ime_punct_question_mark,
    "!" to R.string.ime_punct_exclamation_mark,
    "'" to R.string.ime_punct_apostrophe,
    "\"" to R.string.ime_punct_quote,
    ";" to R.string.ime_punct_semicolon,
    ":" to R.string.ime_punct_colon,
    "-" to R.string.ime_punct_hyphen,
    "_" to R.string.ime_punct_underscore,
    "/" to R.string.ime_punct_slash,
    "\\" to R.string.ime_punct_backslash,
    "(" to R.string.ime_punct_left_parenthesis,
    ")" to R.string.ime_punct_right_parenthesis,
    "[" to R.string.ime_punct_left_bracket,
    "]" to R.string.ime_punct_right_bracket,
    "{" to R.string.ime_punct_left_brace,
    "}" to R.string.ime_punct_right_brace,
    "@" to R.string.ime_punct_at_sign,
    "#" to R.string.ime_punct_hash,
    "$" to R.string.ime_punct_dollar_sign,
    "%" to R.string.ime_punct_percent,
    "&" to R.string.ime_punct_ampersand,
    "*" to R.string.ime_punct_asterisk,
    "+" to R.string.ime_punct_plus,
    "=" to R.string.ime_punct_equals,
    "<" to R.string.ime_punct_less_than,
    ">" to R.string.ime_punct_greater_than,
    "|" to R.string.ime_punct_vertical_bar,
    "~" to R.string.ime_punct_tilde,
    "^" to R.string.ime_punct_caret,
    "`" to R.string.ime_punct_backtick,
)

/** Root composable for the IME. Renders [KeyboardUiState] and forwards input. */
@Composable
fun KeyboardScreen(
    stateFlow: StateFlow<KeyboardUiState>,
    /**
     * Where the open panel publishes what its hardware focus ring can move
     * over. Owned by the service, which reads it on every arrow key.
     */
    panelFocus: PanelFocusController = remember { PanelFocusController() },
    onKey: (Key) -> Unit,
    // Role-carrying, so a sound pack can play a different recording for the
    // spacebar. The parameter *count* is load-bearing: this argument list
    // already compiles to a method at the JVM's 64K ceiling, so these two grow
    // a type rather than growing the list. See [LocalKeyRoleFeedback].
    onKeyPressed: (KeySoundRole) -> Unit = {},
    onHaptic: () -> Unit = { onKeyPressed(KeySoundRole.DEFAULT) },
    onKeySound: (KeySoundRole, KeySoundPhase) -> Unit = { _, _ -> },
    onText: (String) -> Unit = {},
    onGesture: (List<GesturePoint>, List<KeyCenter>, Float, String?) -> Unit =
        { _, _, _, _ -> },
    onGesturePreview: (List<GesturePoint>, List<KeyCenter>, Float) -> Unit = { _, _, _ -> },
    onGestureWords: (List<List<GesturePoint>>, List<KeyCenter>, Float) -> Unit = { _, _, _ -> },
    onKeyTouch: (Float, Float) -> Unit = { _, _ -> },
    onTouchKeys: (List<KeyCenter>) -> Unit = {},
    onCursorMove: (Int) -> Unit = {},
    onCursorMoveVertical: (Int) -> Unit = {},
    onLayoutSelect: (String) -> Unit = {},
    onClipboardKey: (ClipboardKeyAction) -> Unit = {},
    canDelete: () -> Boolean = { true },
    canDeleteField: () -> Boolean = { true },
    canForwardDelete: () -> Boolean = { true },
    onDeleteWord: () -> Unit = {},
    onSuggestion: (String) -> Unit,
    onJoinSuggestion: () -> Unit = {},
    onRevisionSuggestion: () -> Unit = {},
    /** A conversion candidate tapped, with its position — see Composer.consumedForIndex. */
    onCandidate: (String, Int) -> Unit = { text, _ -> onSuggestion(text) },
    /** Open the expanded candidate grid. */
    onCandidatesExpand: () -> Unit = {},
    onEmoji: (String) -> Unit,
    onEmojiVariant: (String, String) -> Unit = { _, v -> onEmoji(v) },
    onEmojiFavourite: (String) -> Unit = {},
    /**
     * An emoji candidate from the strip. The flag is true when it was *held*
     * rather than tapped, which runs the opposite of the configured insert
     * mode — hold to keep the word when tapping would replace it, and the
     * other way round. See `WMKeyboardService.onEmojiSuggestionTapped`.
     */
    onEmojiSuggestion: (String, Boolean) -> Unit = { emoji, _ -> onEmoji(emoji) },
    onPunctuation: (String) -> Unit = {},
    onEmojiQueryTap: () -> Unit,
    onEmojiRecentsClear: () -> Unit = {},
    onEmojiRecentRemove: (String) -> Unit = {},
    onEmojiFavouritesReorder: (List<String>) -> Unit = {},
    /** An emoji cell was held: fetch its animated version, if it has one. */
    onEmojiLongPress: (String) -> Unit = {},
    /** That popup closed: drop the preview. */
    onEmojiLongPressEnd: () -> Unit = {},
    /** Send the animated version of the held emoji as a GIF. */
    onAnimatedEmojiSend: (String) -> Unit = {},
    /** Send the held emoji itself, drawn in the emoji font, as a sticker. */
    onEmojiStickerSend: (String) -> Unit = {},
    onEmojiSearchFieldDelete: () -> Unit = {},
    /**
     * The button-mode emoji row was unfolded. The service holds the usage
     * ranking still while a history surface is on screen, so this is its cue
     * to hand the row a fresh one.
     */
    onEmojiRowShown: () -> Unit = {},
    /** A kaomoji or emoticon tapped in the emoji panel's text-art tabs. */
    onTextArt: (String) -> Unit = {},
    onTextEdit: (TextEditAction) -> Unit = {},
    /**
     * One entry point for every toolbar/toolbox tool — opens its panel or runs
     * its action. Owned by the service so a physical-keyboard shortcut and a tap
     * cannot drift apart.
     */
    onToolTap: (ToolbarTool) -> Unit = {},
    onPanelChange: (PanelMode) -> Unit,
    onClipboardItem: (ClipItem) -> Unit,
    onClipboardSticker: (ClipItem) -> Unit = {},
    onClipboardPin: (ClipItem) -> Unit,
    onClipboardDelete: (ClipItem) -> Unit,
    onClipboardSearchToggle: () -> Unit = {},
    onClipboardSuggestionDismiss: () -> Unit = {},
    onClipboardEntity: (ClipEntity) -> Unit = {},
    onOtpAccept: (NotificationOtp) -> Unit = {},
    onOtpDismiss: () -> Unit = {},
    snippetPanel: SnippetPanelCallbacks = SnippetPanelCallbacks(),
    onOneHanded: (OneHandedMode) -> Unit = {},
    /** Persists the dock side for one orientation (landscape flag, side). */
    onOneHandedSide: (Boolean, OneHandedSide) -> Unit = { _, _ -> },
    onFloatingChange: (Boolean) -> Unit = {},
    onFloatingMoved: (Float, Float) -> Unit = { _, _ -> },
    onSizingAction: (SizingAction) -> Unit = {},
    onFloatingBounds: (IntRect) -> Unit = {},
    onToolbarToolsChange: (List<ToolbarTool>) -> Unit = {},
    onToolboxOrderChange: (List<ToolbarTool>) -> Unit = {},
    toolHold: ToolHoldCallbacks = ToolHoldCallbacks(),
    onToolboxHintDismiss: () -> Unit = {},
    onWeatherRefresh: () -> Unit = {},
    onCameraSend: (java.io.File) -> Unit = {},
    onCameraPermissionRequest: () -> Unit = {},
    onCalendarPermissionRequest: () -> Unit = {},
    onScannedInsert: (String) -> Unit = {},
    onScannedUrlOpen: (String) -> Unit = {},
    onVoiceToggle: () -> Unit = {},
    onVoicePermissionRequest: () -> Unit = {},
    onVoiceUndo: () -> Unit = {},
    onVoiceModelDownload: () -> Unit = {},
    onWhisperTranslateToggle: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    onVoiceUseSystemEngine: () -> Unit = {},
    /** Rail keys plus the collapsed bar's commands — one slot, see [VoiceBarAction]. */
    onVoiceRailKey: (VoiceBarAction) -> Unit = {},
    onMediaPlayPause: () -> Unit = {},
    onMediaNext: () -> Unit = {},
    onMediaPrevious: () -> Unit = {},
    onMediaSeek: (Long) -> Unit = {},
    onMediaAccessRequest: () -> Unit = {},
    onMediaResume: () -> Unit = {},
    onDictionaryLookup: (String) -> Unit = {},
    onDictionarySearchToggle: () -> Unit = {},
    onDictionaryInsert: (String) -> Unit = {},
    onThemeSelect: (String) -> Unit = {},
    onIconPackSelect: (String) -> Unit = {},
    onSoundHaptic: (SoundHapticAction) -> Unit = {},
    onHandwritingStroke: (HwStroke, IntSize) -> Unit = { _, _ -> },
    onKeyboardHandwritingStroke: (HwStroke, IntSize) -> Unit = { _, _ -> },
    onHandwritingUndo: () -> Unit = {},
    onHandwritingDownload: () -> Unit = {},
    onMediaQueryTap: () -> Unit = {},
    onMediaRetry: () -> Unit = {},
    onGifSelect: (GifItem) -> Unit = {},
    onGifSourceSelect: (GifSource) -> Unit = {},
    onGifCategorySelect: (String) -> Unit = {},
    onMediaLongPress: (GifItem) -> Unit = {},
    onStickerPackFilter: (String?) -> Unit = {},
    onStickerSaveToPack: (GifItem, String?) -> Unit = { _, _ -> },
    onMediaCopy: (GifItem) -> Unit = {},
    onMediaReport: (GifItem) -> Unit = {},
    onMediaActionDismiss: () -> Unit = {},
    onWebResult: (WebResult) -> Unit = {},
    onWebResultOpen: (WebResult) -> Unit = {},
    onImageResult: (ImageResult) -> Unit = {},
    onImageResultLink: (ImageResult) -> Unit = {},
    onTranslateTarget: (String) -> Unit = {},
    onTranslateReplace: () -> Unit = {},
    onTranslateInsert: () -> Unit = {},
    onGrammarFix: (GrammarLint, GrammarFix) -> Unit = { _, _ -> },
    onGrammarFixAll: () -> Unit = {},
    onGrammarDismiss: (GrammarLint) -> Unit = {},
    onGrammarDialect: (GrammarDialect) -> Unit = {},
    onGrammarFocus: (GrammarLint) -> Unit = {},
    onWikiOpen: (String) -> Unit = {},
    onWikiBack: () -> Unit = {},
    onWikiLoadLinks: () -> Unit = {},
    onWikiLoadFull: () -> Unit = {},
    onSymbolInsert: (String) -> Unit = {},
    onSymbolSetSelect: (String) -> Unit = {},
    onFancyStyleSelect: (String) -> Unit = {},
    onModeSelect: (String?) -> Unit = {},
    onToolInsert: (String) -> Unit = {},
    // Bundled: KeyboardScreen's caller sits against the JVM's 64K
    // method-size ceiling, where each parameter costs generated bytecode.
    converter: ConverterCallbacks = ConverterCallbacks(),
    onPwSetting: (PwSettingAction) -> Unit = {},
    onTypingTestAction: (TypingTestAction) -> Unit = {},
    onQrSend: () -> Unit = {},
    onAiAction: (com.wasimaster.wmkeyboard.core.tools.AiActionSpec) -> Unit = {},
    onAiReplace: () -> Unit = {},
    onAiInsert: () -> Unit = {},
    onAiRetry: () -> Unit = {},
    onAiRunCustom: () -> Unit = {},
    onAiPickModel: (com.wasimaster.wmkeyboard.core.settings.AiProvider, String?) -> Unit = { _, _ -> },
    onAiToggleStripMarkdown: () -> Unit = {},
    onAiSetShowDiff: (Boolean) -> Unit = {},
    onAiReport: () -> Unit = {},
    onOpenToolSettings: (ToolbarTool) -> Unit = {},
    onOpenRoute: (String) -> Unit = {},
    onPluginOpen: (String) -> Unit = {},
    onPluginBack: () -> Unit = {},
    onPluginEvent: (PluginEvent) -> Unit = {},
    onPluginInputFocus: (String?) -> Unit = {},
    onPluginPaste: (String) -> Unit = {},
    onPluginCopy: (String) -> Unit = {},
    launcher: LauncherPanelCallbacks = LauncherPanelCallbacks(),
    onDismissInlineSuggestions: () -> Unit = {},
    /**
     * Close button of whichever hardware overlay is up: the shortcut legend or
     * the language list. One callback, not two — they never show together, and
     * [KeyboardScreen]'s caller sits against the JVM's 64K method-size ceiling,
     * where every added parameter costs generated bytecode.
     */
    onPickerDismiss: () -> Unit = {},
    /** Smart chip tapped: type the answer over the text that triggered it. */
    onSmartAccept: () -> Unit = {},
    /** Smart chip's tool button: clear the trigger and stage the prefill. */
    onSmartOpen: () -> Unit = {},
    /**
     * An ask-first chip on the strip was answered: true accepted it, false
     * turned it down. One callback for the snippet offer and the add-word
     * offer both — they never share the strip, and the service picks by state,
     * because [KeyboardScreen]'s caller sits against the JVM's 64K method-size
     * ceiling where every added parameter costs generated bytecode.
     */
    onStripOfferAction: (Boolean) -> Unit = {},
    /** A tool panel has read [KeyboardUiState.toolPrefill]. */
    onToolPrefillConsumed: () -> Unit = {},
    /** Dismiss the keyboard — the hide-keyboard tool and the toolbar swipe-down. */
    onHideKeyboard: () -> Unit = {},
) {
    val rawState by stateFlow.collectAsState()

    // Sizing is resolved once, here, for the screen shape we are actually
    // drawing on: a folded phone in landscape can want a shorter key than
    // the same phone upright, and a tablet wants neither. Everything below
    // reads `state.settings.keyHeightDp` as before and never learns that
    // screen variants exist.
    val configuration = LocalConfiguration.current
    val variant = ScreenVariant.of(
        landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        // Smallest dimension, not the current width: a phone turned sideways
        // is wide but still a phone, while an opened foldable is wide either
        // way round.
        unfolded = configuration.smallestScreenWidthDp >= ScreenVariant.UNFOLDED_MIN_DP,
    )
    // Resolved on its own, and remembered on the settings rather than on the
    // whole state: the keys downstream take `settings` as a parameter and Compose
    // reads it as unstable (it is full of lists and maps), so it is compared by
    // instance. Folding this into the state.copy below handed out a fresh
    // settings object on every keystroke whenever the variant had an override,
    // which is enough on its own to stop every key from skipping.
    //
    // Overlay order: the active theme's layout overrides first, then the
    // screen-variant sizing — a per-screen override the user set by hand is
    // more specific than the theme and wins. The spec here is resolved by the
    // same helpers KeyboardThemeProvider uses, so the theme that paints the
    // board and the one that reshapes it are always the same theme.
    val systemDark = isSystemInDarkTheme()
    val darkSlot = rememberAutoThemeDarkSlot(rawState.settings, systemDark)
    val activeSpec = remember(rawState.settings, darkSlot) {
        rawState.settings.activeThemeSpec(darkSlot)
    }
    val settings = remember(rawState.settings, variant, activeSpec) {
        rawState.settings.applyThemeOverrides(activeSpec).resolvedFor(variant)
    }
    // The layout's own font, which is deliberately NOT part of that chain. The
    // chain produces one KeyboardSettings for the whole board, and a layout's
    // type is per grid: its label size is per *layer* on top of that, and rides
    // the compiled KeyboardLayout down to the keys instead (see keyVisual). Only
    // the font is layout-wide, so only the font is read here.
    val layoutFontId = remember(rawState.settings.customLayouts, rawState.layoutId) {
        resolveLayout(rawState.settings.customLayouts, rawState.layoutId).appearance?.fontId
    }
    // The inline resize tool's live preview. Stored-space values, folded in
    // AFTER the resolve so a drag previews exactly what Done would persist;
    // null (resting, and every non-resize frame) short-circuits to the same
    // `settings` instance, so the block above keeps its skipping behaviour.
    val resizePreview = remember { mutableStateOf<ResizeValues?>(null) }
    LaunchedEffect(rawState.resize) { if (!rawState.resize) resizePreview.value = null }
    val preview = if (rawState.resize) resizePreview.value else null
    val previewScale = if (preview == null) 1f else {
        rawState.settings.sizingOverrides[variant]?.keyboardScale ?: 1f
    }
    val shown = remember(settings, preview, previewScale) {
        if (preview == null) settings else settings.copy(
            keyHeightDp = (preview.keyHeightDp * previewScale).roundToInt(),
            numberRowHeightDp = (preview.numberRowHeightDp * previewScale).roundToInt(),
            bottomPaddingDp = preview.bottomPaddingDp,
        )
    }
    val state = remember(rawState, shown) { rawState.copy(settings = shown) }
    val resizeSession = if (!rawState.resize) null else {
        remember(rawState.settings, variant, resizePreview) {
            val values = rawState.settings.sizingValuesFor(variant)
            val entry = ResizeValues(
                keyHeightDp = values.keyHeightDp ?: rawState.settings.keyHeightDp,
                numberRowHeightDp = values.numberRowHeightDp
                    ?: rawState.settings.numberRowHeightDp,
                bottomPaddingDp = values.bottomPaddingDp ?: rawState.settings.bottomPaddingDp,
            )
            ResizeSession(
                entry = entry,
                keyboardScale = values.keyboardScale ?: 1f,
                maxBottomPaddingDp = SettingsRepository.MAX_BOTTOM_PADDING_DP,
                preview = resizePreview,
                onCommit = { result ->
                    onSizingAction(resizeCommitAction(variant, entry, result))
                },
            )
        }
    }

    // Resolved off the main thread, so the first frame or two after a cold
    // start draw the built-in icons and a pack swaps in behind them.
    val iconSet by rememberIconSet(state.settings.icons)

    val body: @Composable ColumnScope.(KeyboardUiState) -> Unit = { bodyState ->
        CompositionLocalProvider(
            LocalIconSet provides iconSet,
            LocalKeyPressFeedback provides remember(onKeyPressed) {
                { onKeyPressed(KeySoundRole.DEFAULT) }
            },
            LocalHapticFeedback provides onHaptic,
            LocalKeySound provides remember(onKeySound) {
                { onKeySound(KeySoundRole.DEFAULT, KeySoundPhase.PRESS) }
            },
            LocalKeyRoleFeedback provides onKeyPressed,
            LocalKeyRoleSound provides onKeySound,
            LocalClipboardKeyAction provides onClipboardKey,
            LocalCanDelete provides canDelete,
            LocalCanDeleteField provides canDeleteField,
            LocalCanForwardDelete provides canForwardDelete,
            LocalDeleteWord provides onDeleteWord,
            LocalCursorMoveVertical provides onCursorMoveVertical,
            LocalHideKeyboard provides onHideKeyboard,
            LocalTouchExploration provides rememberTouchExploration(),
            LocalPassthroughService provides
                KeyboardPassthrough.serviceConnected.collectAsState().value,
            LocalPanelFocus provides panelFocus,
        ) {
            KeyboardBody(
                state = bodyState,
                onEmojiRowShown = onEmojiRowShown,
                onDismissInlineSuggestions = onDismissInlineSuggestions,
                onPickerDismiss = onPickerDismiss,
                onSmartAccept = onSmartAccept,
                onSmartOpen = onSmartOpen,
                onStripOfferAction = onStripOfferAction,
                onToolPrefillConsumed = onToolPrefillConsumed,
                onHideKeyboard = onHideKeyboard,
                onKey = onKey,
                onText = onText,
                onGesture = onGesture,
                onGesturePreview = onGesturePreview,
                onGestureWords = onGestureWords,
                onKeyTouch = onKeyTouch,
                onTouchKeys = onTouchKeys,
                onCursorMove = onCursorMove,
                onLayoutSelect = onLayoutSelect,
                onSuggestion = onSuggestion,
                onJoinSuggestion = onJoinSuggestion,
                onRevisionSuggestion = onRevisionSuggestion,
                onCandidate = onCandidate,
                onCandidatesExpand = onCandidatesExpand,
                onEmoji = onEmoji,
                onEmojiVariant = onEmojiVariant,
                onEmojiFavourite = onEmojiFavourite,
                onEmojiSuggestion = onEmojiSuggestion,
                onPunctuation = onPunctuation,
                onEmojiQueryTap = onEmojiQueryTap,
                onEmojiRecentsClear = onEmojiRecentsClear,
                onEmojiRecentRemove = onEmojiRecentRemove,
                onEmojiFavouritesReorder = onEmojiFavouritesReorder,
                onEmojiLongPress = onEmojiLongPress,
                onEmojiLongPressEnd = onEmojiLongPressEnd,
                onAnimatedEmojiSend = onAnimatedEmojiSend,
                onEmojiStickerSend = onEmojiStickerSend,
                onEmojiSearchFieldDelete = onEmojiSearchFieldDelete,
                onTextArt = onTextArt,
                onTextEdit = onTextEdit,
                onPanelChange = onPanelChange,
                onClipboardItem = onClipboardItem,
                onClipboardSticker = onClipboardSticker,
                onClipboardPin = onClipboardPin,
                onClipboardDelete = onClipboardDelete,
                onClipboardSearchToggle = onClipboardSearchToggle,
                onClipboardSuggestionDismiss = onClipboardSuggestionDismiss,
                onClipboardEntity = onClipboardEntity,
                onOtpAccept = onOtpAccept,
                onOtpDismiss = onOtpDismiss,
                snippetPanel = snippetPanel,
                onToolTap = onToolTap,
                onToolbarToolsChange = onToolbarToolsChange,
                onToolboxOrderChange = onToolboxOrderChange,
                toolHold = toolHold,
                onToolboxHintDismiss = onToolboxHintDismiss,
                onWeatherRefresh = onWeatherRefresh,
                onCameraSend = onCameraSend,
                onCameraPermissionRequest = onCameraPermissionRequest,
                onCalendarPermissionRequest = onCalendarPermissionRequest,
                onScannedInsert = onScannedInsert,
                onScannedUrlOpen = onScannedUrlOpen,
                onVoiceToggle = onVoiceToggle,
                onVoicePermissionRequest = onVoicePermissionRequest,
                onVoiceUndo = onVoiceUndo,
                onVoiceModelDownload = onVoiceModelDownload,
                onWhisperTranslateToggle = onWhisperTranslateToggle,
                onOpenVoiceSettings = onOpenVoiceSettings,
                onVoiceUseSystemEngine = onVoiceUseSystemEngine,
                onVoiceRailKey = onVoiceRailKey,
                onMediaPlayPause = onMediaPlayPause,
                onMediaNext = onMediaNext,
                onMediaPrevious = onMediaPrevious,
                onMediaSeek = onMediaSeek,
                onMediaAccessRequest = onMediaAccessRequest,
                onMediaResume = onMediaResume,
                onDictionaryLookup = onDictionaryLookup,
                onDictionarySearchToggle = onDictionarySearchToggle,
                onDictionaryInsert = onDictionaryInsert,
                onThemeSelect = onThemeSelect,
                onIconPackSelect = onIconPackSelect,
                onSoundHaptic = onSoundHaptic,
                onHandwritingStroke = onHandwritingStroke,
                onKeyboardHandwritingStroke = onKeyboardHandwritingStroke,
                onHandwritingUndo = onHandwritingUndo,
                onHandwritingDownload = onHandwritingDownload,
                onMediaQueryTap = onMediaQueryTap,
                onMediaRetry = onMediaRetry,
                onGifSelect = onGifSelect,
                onGifSourceSelect = onGifSourceSelect,
                onGifCategorySelect = onGifCategorySelect,
                onMediaLongPress = onMediaLongPress,
                onStickerPackFilter = onStickerPackFilter,
                onStickerSaveToPack = onStickerSaveToPack,
                onMediaCopy = onMediaCopy,
                onMediaReport = onMediaReport,
                onMediaActionDismiss = onMediaActionDismiss,
                onWebResult = onWebResult,
                onWebResultOpen = onWebResultOpen,
                onImageResult = onImageResult,
                onImageResultLink = onImageResultLink,
                onTranslateTarget = onTranslateTarget,
                onTranslateReplace = onTranslateReplace,
                onTranslateInsert = onTranslateInsert,
                onGrammarFix = onGrammarFix,
                onGrammarFixAll = onGrammarFixAll,
                onGrammarDismiss = onGrammarDismiss,
                onGrammarDialect = onGrammarDialect,
                onGrammarFocus = onGrammarFocus,
                onWikiOpen = onWikiOpen,
                onWikiBack = onWikiBack,
                onWikiLoadLinks = onWikiLoadLinks,
                onWikiLoadFull = onWikiLoadFull,
                onSymbolInsert = onSymbolInsert,
                onSymbolSetSelect = onSymbolSetSelect,
                onFancyStyleSelect = onFancyStyleSelect,
                onModeSelect = onModeSelect,
                onToolInsert = onToolInsert,
                converter = converter,
                onPwSetting = onPwSetting,
                onTypingTestAction = onTypingTestAction,
                onQrSend = onQrSend,
                onAiAction = onAiAction,
                onAiReplace = onAiReplace,
                onAiInsert = onAiInsert,
                onAiRetry = onAiRetry,
                onAiRunCustom = onAiRunCustom,
                onAiPickModel = onAiPickModel,
                onAiToggleStripMarkdown = onAiToggleStripMarkdown,
                onAiSetShowDiff = onAiSetShowDiff,
                onAiReport = onAiReport,
                onOpenToolSettings = onOpenToolSettings,
                onOpenRoute = onOpenRoute,
                onPluginOpen = onPluginOpen,
                onPluginBack = onPluginBack,
                onPluginEvent = onPluginEvent,
                onPluginInputFocus = onPluginInputFocus,
                onPluginPaste = onPluginPaste,
                onPluginCopy = onPluginCopy,
                launcher = launcher,
            )
        }
    }

    // The body is emitted from several mutually exclusive places below — docked,
    // one-handed left, one-handed right, floating — and every one of them is a
    // different slot in the composition. Called plainly, moving between them is
    // a dispose plus a fresh compose, which throws away everything the open
    // panel remembered: toggling the floating keyboard from the toolbox sent
    // its grid back to the top, and the same went for every other panel's
    // scroll, search field and pager page.
    //
    // movableContent makes the move a move: the nodes and their remembered
    // state travel to the new parent intact. The lambda is remembered once (it
    // has to be, or it is a new content identity every frame), so it reads the
    // live `body` through a holder rather than capturing the one it was born
    // with. `body` is structurally identical every recomposition, so calling
    // the latest instance at the same slot keeps the remembers inside it.
    val currentBody by rememberUpdatedState(body)
    val movableBody = remember {
        movableContentWithReceiverOf<ColumnScope, KeyboardUiState> { bodyState ->
            currentBody(bodyState)
        }
    }

    val rotationStates by PhotoBackgroundManager.rotationStates.collectAsState()
    KeyboardThemeProvider(
        settings = state.settings,
        rotationStates = rotationStates,
        layoutFontId = layoutFontId,
    ) {
        // The collapsed voice bar takes the whole window over: no keyboard, no
        // strips, just the pill. It outranks floating mode because it is the
        // temporary state — restoring the keyboard lands back in whichever
        // chrome the settings ask for.
        //
        // The two hand over with a slide, not a cut. Collapsing keeps the
        // keyboard composed while it slides down out of the window (the bar
        // enters beneath it with its own rise); restoring slides it back up
        // inside its own, already-resized window — the bar side is not kept
        // composed there, because its full-screen box would hold the window
        // tall and squeeze the app for the whole animation.
        val barTarget = voiceBarTakesWindow(state)
        val transitionTheme = LocalKbTheme.current
        val collapse = remember { Animatable(if (barTarget) 1f else 0f) }
        LaunchedEffect(barTarget, transitionTheme.reduceMotion) {
            val target = if (barTarget) 1f else 0f
            if (transitionTheme.reduceMotion) {
                collapse.snapTo(target)
            } else if (collapse.value != target) {
                collapse.animateTo(
                    target,
                    tween(VoiceBarTransitionMs, easing = FastOutSlowInEasing),
                )
            }
        }
        val chromeHeight = remember { mutableIntStateOf(0) }
        Box(contentAlignment = Alignment.BottomCenter) {
            if (barTarget) {
                VoiceBarLayer(
                    state = state,
                    onToggle = onVoiceToggle,
                    onUndo = onVoiceUndo,
                    onRequestPermission = onVoicePermissionRequest,
                    onOpenVoiceSettings = onOpenVoiceSettings,
                    onRestoreKeyboard = { onToolTap(ToolbarTool.VOICE) },
                    onAction = onVoiceRailKey,
                    onLayoutSelect = onLayoutSelect,
                )
            }
            // The keyboard stays composed until the slide has fully carried it
            // out. Judged from the animation's value, not a flag an effect
            // sets: an effect runs after the composition that flipped the
            // target, so a flag unmounted the keyboard for one frame and
            // remounted it — a visible blink right as the slide began. The
            // short-circuit keeps the value unread (no per-frame recompose)
            // whenever the keyboard is staying anyway.
            if (!barTarget || collapse.value < 1f) {
                Box(
                    modifier = Modifier
                        .onSizeChanged { chromeHeight.intValue = it.height }
                        // Draw-phase read: the slide costs redraws, never a
                        // recomposition per frame.
                        .graphicsLayer {
                            translationY = collapse.value * chromeHeight.intValue
                        },
                ) {
                    if (state.settings.floatingKeyboard) {
                        // Floating mode: the compose root spans the whole IME
                        // window with no background; the service restricts the
                        // touchable region to the panel so everything else
                        // falls through to the app behind.
                        FloatingKeyboardFrame(
                            state = state,
                            onDock = { onFloatingChange(false) },
                            onMoved = onFloatingMoved,
                            onResized = { widthDp, heightScale ->
                                onSizingAction(SizingAction.Floating(widthDp, heightScale))
                            },
                            onBounds = onFloatingBounds,
                            content = { heightScale ->
                                // Key height carries the whole layout (panels
                                // included), so scaling it scales the
                                // keyboard's height.
                                val scaled = if (heightScale == 1f) state else state.copy(
                                    settings = state.settings.copy(
                                        keyHeightDp =
                                            (state.settings.keyHeightDp * heightScale).roundToInt(),
                                        numberRowHeightDp =
                                            (state.settings.numberRowHeightDp * heightScale).roundToInt(),
                                    ),
                                )
                                movableBody(scaled)
                            },
                        )
                    } else {
                        DockedKeyboardFrame(
                            state = state,
                            landscape =
                                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                            onOneHanded = onOneHanded,
                            onOneHandedSide = onOneHandedSide,
                            resize = resizeSession,
                            body = movableBody,
                        )
                    }
                }
            }
        }
    }
}

/** How long the keyboard takes to slide out for the voice bar, or back in. */
private const val VoiceBarTransitionMs = 260

/**
 * Docked chrome: the board background plus the width, alignment and
 * one-handed arrangement around the keyboard body — the everyday counterpart
 * of [FloatingKeyboardFrame].
 */
@Composable
private fun DockedKeyboardFrame(
    state: KeyboardUiState,
    landscape: Boolean,
    onOneHanded: (OneHandedMode) -> Unit,
    onOneHandedSide: (Boolean, OneHandedSide) -> Unit,
    resize: ResizeSession? = null,
    body: @Composable ColumnScope.(KeyboardUiState) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        BoardBackground(LocalKbTheme.current)
        // navigationBarsPadding keeps the bottom key row clear of the
        // gesture-navigation bar on edge-to-edge (SDK 35+) IME windows.
        val oneHanded = state.settings.oneHandedMode
        val ohProfile = state.settings.oneHanded.forLandscape(landscape)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // Extra breathing room above the gesture bar, adjustable
                // in Settings → Appearance.
                .padding(bottom = state.settings.bottomPaddingDp.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Flip to the other side: update the live mode and remember the
            // new side as this orientation's default.
            val flipSide: () -> Unit = {
                val next =
                    if (oneHanded == OneHandedMode.LEFT) OneHandedSide.RIGHT
                    else OneHandedSide.LEFT
                onOneHandedSide(landscape, next)
                onOneHanded(next.toMode())
            }
            if (oneHanded == OneHandedMode.OFF) {
                // Resizable width: below 100% the keyboard shrinks and sits
                // at the chosen edge (or centered).
                // Side-padding (A50) shaves an equal fraction off each side
                // on top of the width setting, narrowing the keys toward the
                // centre for thumb reach; it rides on the same slack/centering
                // machinery below.
                val arrangement = dockedWidthArrangement(state.settings)
                if (arrangement.leftSlack > 0.001f) {
                    Spacer(modifier = Modifier.weight(arrangement.leftSlack))
                }
                Column(modifier = Modifier.weight(arrangement.widthFraction)) { body(state) }
                if (arrangement.rightSlack > 0.001f) {
                    Spacer(modifier = Modifier.weight(arrangement.rightSlack))
                }
            } else {
                // One-handed: dock to the live side with this orientation's
                // width and height scale. The weights sum to 1 so the body
                // is exactly `widthFraction` of the screen and any leftover
                // beyond the rail becomes centre-ward slack.
                val widthFraction = (ohProfile.widthPercent / 100f).coerceIn(0.30f, 0.90f)
                val leftover = 1f - widthFraction
                val railWeight = ONE_HANDED_RAIL_WEIGHT.coerceAtMost(leftover)
                val slack = (leftover - railWeight).coerceAtLeast(0f)
                val ohState = if (ohProfile.heightScale >= 100) state else state.copy(
                    settings = state.settings.copy(
                        keyHeightDp =
                            (state.settings.keyHeightDp * ohProfile.heightScale / 100).coerceAtLeast(1),
                        numberRowHeightDp =
                            (state.settings.numberRowHeightDp * ohProfile.heightScale / 100).coerceAtLeast(1),
                    ),
                )
                val rail = @Composable {
                    OneHandedRail(
                        current = oneHanded,
                        onFlip = flipSide,
                        onExit = { onOneHanded(OneHandedMode.OFF) },
                        modifier = Modifier.weight(railWeight),
                    )
                }
                if (oneHanded == OneHandedMode.RIGHT) {
                    if (slack > 0.001f) Spacer(modifier = Modifier.weight(slack))
                    rail()
                    Column(modifier = Modifier.weight(widthFraction)) { body(ohState) }
                } else {
                    Column(modifier = Modifier.weight(widthFraction)) { body(ohState) }
                    rail()
                    if (slack > 0.001f) Spacer(modifier = Modifier.weight(slack))
                }
            }
        }
        // Last in the Box, so the chrome floats over the dimmed keyboard.
        // The service turns one-handed mode off before entering the resize
        // mode, so the overlay only ever mirrors the plain docked arrangement.
        if (resize != null) ResizeOverlay(session = resize, state = state)
    }
}

/**
 * How the docked keyboard's width settings become Row weights: the keyboard
 * takes [widthFraction] of the window with [leftSlack]/[rightSlack] of empty
 * space around it. Shared with [ResizeOverlay] so the resize outline hugs
 * exactly the rectangle the keys are laid out in.
 */
internal class DockedWidthArrangement(
    val widthFraction: Float,
    val leftSlack: Float,
    val rightSlack: Float,
)

internal fun dockedWidthArrangement(settings: KeyboardSettings): DockedWidthArrangement {
    val sidePad = settings.layoutBehavior.sidePadScale.coerceIn(0f, 0.3f)
    val widthFraction =
        (settings.keyboardWidthPercent / 100f * (1f - 2f * sidePad)).coerceAtLeast(0.2f)
    val slack = 1f - widthFraction
    val leftSlack = when (settings.keyboardAlignment) {
        KeyboardAlignment.LEFT -> 0f
        KeyboardAlignment.CENTER -> slack / 2f
        KeyboardAlignment.RIGHT -> slack
    }
    return DockedWidthArrangement(widthFraction, leftSlack, slack - leftSlack)
}

/**
 * The collapsed voice bar owns the window right now. A panel forced open (a
 * hardware shortcut can do this) or a password field puts the keyboard back
 * without disarming the bar. The service's insets check mirrors this
 * predicate — keep the two in step.
 */
private fun voiceBarTakesWindow(state: KeyboardUiState): Boolean =
    state.voice.bar && state.panel == PanelMode.NONE && !state.secureField

/**
 * Floating mode chrome: a detached, elevated panel holding the regular
 * keyboard body, movable by its drag handle and resizable from the corner
 * handle. Position is kept as fractions of the free space so it survives
 * rotation; width in dp. Both persist via the callbacks on gesture end.
 */
@Composable
private fun FloatingKeyboardFrame(
    state: KeyboardUiState,
    onDock: () -> Unit,
    onMoved: (Float, Float) -> Unit,
    onResized: (Int, Float) -> Unit,
    onBounds: (IntRect) -> Unit,
    content: @Composable ColumnScope.(Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxWidthPx = constraints.maxWidth
        val boxHeightPx = constraints.maxHeight
        val maxWidthDp = (with(density) { boxWidthPx.toDp().value } - 16f)
            .coerceAtLeast(FLOATING_MIN_WIDTH_DP)

        // Panel size in px, written from layout. Never read during composition
        // — see the deferred reads below — so a resize settling over a couple
        // of layout passes does not drag the keyboard through a recomposition
        // for each one.
        val panelSize = remember { mutableStateOf(IntSize.Zero) }
        // Settled drag position in px; null = follow the persisted fractions.
        // Reset when the window size changes (rotation) so the fractions
        // re-anchor the panel.
        val dragOffset = remember(boxWidthPx, boxHeightPx) { mutableStateOf<Offset?>(null) }
        // How far the finger has carried the panel away from [dragOffset] in
        // the gesture running right now, folded back into it when the gesture
        // ends. Read from the draw phase (the layer below), never from
        // placement, so following the finger costs a redraw per frame and no
        // window layout pass at all.
        //
        // Issue #6: driven from placement, every drag after the first left the
        // panel sitting at its old spot for the whole gesture and then
        // teleported it to where the finger had ended up. A placement-phase
        // move needs the IME window to run a layout pass per frame, and an IME
        // window can be left with its layout requests swallowed — after which
        // the panel only catches up when something forces a pass by hand,
        // which is exactly what publishing the settled bounds below does. The
        // draw phase has no such dependency.
        val dragShift = remember { mutableStateOf(Offset.Zero) }
        // Width is a layout input only, so it too is read from a measure
        // lambda rather than from composition: dragging the grip re-measures
        // the panel instead of recomposing the entire keyboard per frame.
        val liveWidthDp = remember(state.settings.floatingWidthDp) {
            mutableFloatStateOf(
                state.settings.floatingWidthDp.toFloat()
                    .coerceIn(FLOATING_MIN_WIDTH_DP, maxWidthDp),
            )
        }
        // Height is the one value a resize cannot keep out of composition: it
        // scales the key heights, which is a settings change. Quantised (see
        // the gesture below) so a drag costs a handful of recompositions
        // rather than one per frame.
        var liveHeightScale by remember(state.settings.floatingHeightScale) {
            mutableFloatStateOf(state.settings.floatingHeightScale)
        }
        // Everything a gesture needs but nothing else reads: kept off the
        // snapshot so touching it invalidates nothing.
        val gesture = remember { FloatingGesture() }

        fun slackX() = (boxWidthPx - panelSize.value.width).coerceAtLeast(0).toFloat()
        fun slackY() = (boxHeightPx - panelSize.value.height).coerceAtLeast(0).toFloat()
        fun currentOffset(): Offset {
            val live = dragOffset.value
            val raw = live ?: Offset(
                state.settings.floatingXFraction * slackX(),
                state.settings.floatingYFraction * slackY(),
            )
            return Offset(raw.x.coerceIn(0f, slackX()), raw.y.coerceIn(0f, slackY()))
        }
        fun publishBounds() = gesture.bounds?.let(onBounds)

        Surface(
            modifier = Modifier
                // Placement-scope read: moving the panel re-places one node.
                // Read at composition scope (the old `val offset = …`) it
                // recomposed the whole keyboard on every drag frame, which is
                // what made dragging feel like it was catching.
                .offset {
                    val o = currentOffset()
                    IntOffset(o.x.roundToInt(), o.y.roundToInt())
                }
                // Measure-scope read of the live width, for the same reason.
                .layout { measurable, constraints ->
                    val widthPx = with(density) {
                        liveWidthDp.floatValue.coerceIn(FLOATING_MIN_WIDTH_DP, maxWidthDp).dp.roundToPx()
                    }
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = widthPx, maxWidth = widthPx),
                    )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                // Draw-scope reads, both of them. The live drag rides here
                // rather than on the offset above (see [dragShift]), and the
                // panel is invisible for the first frame, before it has been
                // measured and placed from real sizes — which avoids a flash
                // at a wrong position, and costs a redraw rather than a
                // recomposition.
                .graphicsLayer {
                    val shift = dragShift.value
                    translationX = shift.x
                    translationY = shift.y
                    alpha = if (panelSize.value == IntSize.Zero) 0f else 1f
                }
                .onGloballyPositioned { coords ->
                    panelSize.value = coords.size
                    val position = coords.positionInWindow()
                    gesture.bounds = IntRect(
                        offset = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
                        size = coords.size,
                    )
                    // Publishing mid-gesture forces a decor-view layout pass
                    // per frame just to update the touchable region, and the
                    // region cannot matter while a finger is already captured.
                    // The end of the gesture publishes the settled bounds.
                    if (!gesture.active) publishBounds()
                },
            shape = RoundedCornerShape(18.dp),
            // The theme paints the panel (color + optional image); Surface
            // just supplies the shape, clip and shadow.
            color = Color.Transparent,
            shadowElevation = 10.dp,
        ) {
            Box {
                BoardBackground(LocalKbTheme.current)
                Column {
                    FloatingHandleBar(
                        onDock = onDock,
                        onDragStart = {
                            gesture.active = true
                            // The panel's top-left is pinned in px for the whole
                            // drag, as it is for a resize: the shift below is
                            // measured from it, and the fractions it would
                            // otherwise fall back to are a DataStore round trip
                            // behind the finger.
                            dragOffset.value = currentOffset()
                            dragShift.value = Offset.Zero
                        },
                        onDragBy = { delta ->
                            val base = dragOffset.value ?: return@FloatingHandleBar
                            val shift = dragShift.value
                            // Clamped as a whole position, then stored back as a
                            // shift, so the panel stops at the window edge
                            // instead of the finger walking a shift off-screen.
                            dragShift.value = Offset(
                                (base.x + shift.x + delta.x).coerceIn(0f, slackX()) - base.x,
                                (base.y + shift.y + delta.y).coerceIn(0f, slackY()) - base.y,
                            )
                        },
                        onDragEnd = {
                            gesture.active = false
                            val base = dragOffset.value ?: return@FloatingHandleBar
                            val shift = dragShift.value
                            val end = Offset(
                                (base.x + shift.x).coerceIn(0f, slackX()),
                                (base.y + shift.y).coerceIn(0f, slackY()),
                            )
                            // Hand the travel over to layout in one go: the
                            // offset takes the panel's new home and the shift
                            // returns to zero together, so both phases land in
                            // the same frame and the panel never blinks back.
                            dragOffset.value = end
                            dragShift.value = Offset.Zero
                            // The panel was never re-placed during the drag, so
                            // the measured rectangle is a whole gesture behind:
                            // move it by hand. Publishing it is also what forces
                            // the window layout pass that lands the offset above.
                            gesture.bounds = gesture.bounds?.translate(
                                (end.x - base.x).roundToInt(),
                                (end.y - base.y).roundToInt(),
                            )
                            publishBounds()
                            onMoved(
                                if (slackX() > 0f) end.x / slackX() else 0.5f,
                                if (slackY() > 0f) end.y / slackY() else 0.5f,
                            )
                        },
                        onResizeStart = {
                            gesture.active = true
                            // The panel's own top-left is pinned for the whole
                            // resize. Left on the fractions it would slide as
                            // the slack shrank under it — the panel wandering
                            // away from the finger, which read as the resize
                            // being broken rather than as re-anchoring.
                            dragOffset.value = currentOffset()
                            gesture.widthDp = liveWidthDp.floatValue
                            gesture.heightScale = liveHeightScale
                            // The unscaled height is measured once, here. Read
                            // per frame from the live panel it was a feedback
                            // loop: the size it divides by is a frame behind
                            // the scale it produced, so the grip accelerated
                            // away from the finger and oscillated.
                            gesture.baseHeightPx =
                                if (liveHeightScale > 0f) panelSize.value.height / liveHeightScale else 0f
                        },
                        onResizeBy = { delta ->
                            gesture.widthDp = (gesture.widthDp + with(density) { delta.x.toDp().value })
                                .coerceIn(FLOATING_MIN_WIDTH_DP, maxWidthDp)
                            // The grip sits on the panel's TOP bar, so dragging
                            // up (negative y) grows the panel — hence the minus.
                            if (gesture.baseHeightPx > 0f) {
                                gesture.heightScale = (gesture.heightScale - delta.y / gesture.baseHeightPx)
                                    .coerceIn(FLOATING_MIN_HEIGHT_SCALE, FLOATING_MAX_HEIGHT_SCALE)
                            }
                            // Snapped before it reaches layout. The finger
                            // moves a pixel at a time; re-measuring a whole
                            // keyboard for a pixel is work nobody can see, and
                            // the height step keeps its recompositions down to
                            // one per visible change.
                            liveWidthDp.floatValue =
                                quantize(gesture.widthDp, FLOATING_WIDTH_STEP_DP)
                                    .coerceIn(FLOATING_MIN_WIDTH_DP, maxWidthDp)
                            liveHeightScale =
                                quantize(gesture.heightScale, FLOATING_HEIGHT_STEP)
                                    .coerceIn(FLOATING_MIN_HEIGHT_SCALE, FLOATING_MAX_HEIGHT_SCALE)
                        },
                        onResizeEnd = {
                            gesture.active = false
                            publishBounds()
                            onResized(liveWidthDp.floatValue.roundToInt(), liveHeightScale)
                            // The pin above is in px, so persist it too or the
                            // panel jumps back to the old fractions the next
                            // time they are applied.
                            val end = dragOffset.value ?: return@FloatingHandleBar
                            onMoved(
                                if (slackX() > 0f) end.x / slackX() else 0.5f,
                                if (slackY() > 0f) end.y / slackY() else 0.5f,
                            )
                        },
                    )
                    content(liveHeightScale)
                }
            }
        }
    }
}

/** Scratch state for one move/resize gesture. Deliberately not snapshot state. */
private class FloatingGesture {
    /** A finger is on the drag pill or the grip right now. */
    var active = false
    /** Latest measured panel bounds, published when the gesture ends. */
    var bounds: IntRect? = null
    /** Panel height at scale 1, measured once at the start of a resize. */
    var baseHeightPx = 0f
    /** Unsnapped width the finger has travelled to. */
    var widthDp = 0f
    /** Unsnapped height scale the finger has travelled to. */
    var heightScale = 1f
}

private fun quantize(value: Float, step: Float): Float = (value / step).roundToInt() * step

// The one-handed rail's share of the screen width. Kept small so the body
// gets its full configured width; shrinks only if the width leaves less room.
private const val ONE_HANDED_RAIL_WEIGHT = 0.16f
private const val FLOATING_MIN_WIDTH_DP = 240f
private const val FLOATING_MIN_HEIGHT_SCALE = 0.6f
private const val FLOATING_MAX_HEIGHT_SCALE = 1.6f

// Resize granularity. A pixel-exact resize re-measures (width) or recomposes
// (height) the whole keyboard for a change too small to see; these are the
// coarsest steps that still read as continuous under a moving finger.
private const val FLOATING_WIDTH_STEP_DP = 4f
private const val FLOATING_HEIGHT_STEP = 0.02f

/**
 * Handle row on top of the floating panel: dock button, drag pill, resize grip.
 *
 * The two corner buttons are drawn as tool circles — same shape from the theme's
 * tool radius, same [KbTheme.toolCircle] fill, same [KbTheme.toolbarIcon] glyph
 * colour — because they sit directly above a keyboard full of them. Left on the
 * bare Material palette they were the only two things on the panel that ignored
 * the theme, and on anything but a plain grey board they read as leftovers from
 * another app.
 */
@Composable
private fun FloatingHandleBar(
    onDock: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onResizeStart: () -> Unit,
    onResizeBy: (Offset) -> Unit,
    onResizeEnd: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.toolShape()
    val buttonFill = if (kb.toolRadiusDp > 0) kb.toolCircle else Color.Transparent
    // Same outline the tools on the bar wear, for the same reason the fill and
    // the shape are shared: these two buttons sit above a row of them.
    val buttonOutline = if (kb.toolBorder != null && kb.toolBorderWidthDp > 0f) {
        Modifier.border(kb.toolBorderWidthDp.dp, kb.toolBorder, shape)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FloatingHandleBarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Docking returns the full-width keyboard, so a fullscreen glyph reads
        // right; the old down-arrow looked like a download button.
        Box(
            modifier = Modifier
                .size(FloatingHandleButton)
                .background(buttonFill, shape)
                .then(buttonOutline)
                .clip(shape)
                .clickable(onClick = onDock),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Fullscreen,
                contentDescription = stringResource(R.string.ime_floating_dock_desc),
                modifier = Modifier.size(FloatingHandleGlyph),
                tint = kb.toolbarIcon,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDragBy(amount)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(5.dp)
                    .background(kb.toolbarIcon.copy(alpha = 0.35f), RoundedCornerShape(2.5.dp)),
            )
        }
        Box(
            modifier = Modifier
                .size(FloatingHandleButton)
                .background(buttonFill, shape)
                .then(buttonOutline)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onResizeStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            onResizeBy(amount)
                        },
                        onDragEnd = onResizeEnd,
                        onDragCancel = onResizeEnd,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Classic grip lines instead of OpenInFull, whose diagonal
            // arrows read as a "go fullscreen" button rather than a
            // drag-to-resize handle.
            val gripColor = kb.toolbarIcon
            val resizeDesc = stringResource(R.string.ime_floating_resize_desc)
            Canvas(
                modifier = Modifier
                    .size(FloatingHandleGlyph)
                    .semantics { contentDescription = resizeDesc },
            ) {
                val stroke = 1.75.dp.toPx()
                drawLine(
                    gripColor,
                    Offset(size.width * 0.15f, size.height),
                    Offset(size.width, size.height * 0.15f),
                    stroke, cap = StrokeCap.Round,
                )
                drawLine(
                    gripColor,
                    Offset(size.width * 0.6f, size.height),
                    Offset(size.width, size.height * 0.6f),
                    stroke, cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val FloatingHandleBarHeight = 38.dp
private val FloatingHandleButton = 32.dp
private val FloatingHandleGlyph = 19.dp

/** Side rail shown in one-handed mode: swap sides or return to full width. */
@Composable
private fun OneHandedRail(
    current: OneHandedMode,
    onFlip: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onFlip) {
            Icon(
                if (current == OneHandedMode.LEFT) {
                    Icons.AutoMirrored.Outlined.ArrowForward
                } else {
                    Icons.AutoMirrored.Outlined.ArrowBack
                },
                contentDescription = stringResource(R.string.ime_one_handed_flip_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onExit) {
            Icon(
                Icons.Outlined.Fullscreen,
                contentDescription = stringResource(R.string.ime_one_handed_exit_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- top bar: suggestions or toolbar ----

/**
 * One duration for every toolbar transition: the strip/toolbar chevron
 * rotation, the back chevron's enter and exit, and the icon slides.
 *
 * They all fire off the same two taps — flip the bar, open a panel — so any
 * two that disagree read as one animation lagging the other rather than as
 * two separate animations. The icon slides used to run on a much softer
 * spring and were still travelling long after the chevron had settled.
 */
private const val ToolbarMotionMs = 140

/**
 * How long the surface leaving the bar — the tools, or the candidates — takes
 * to dissolve before the other one takes the row.
 *
 * Shorter than [ToolbarMotionMs] because it is spent before the flip rather
 * than during it: the whole exchange costs this plus the arrival, and a longer
 * exit would make a bar flip feel like it was waiting on something. Long enough
 * to read as a fade at 120Hz, which is all it has to be — the eye is following
 * the emoji, which does not fade and carries straight through the swap.
 */
private const val ToolbarExitMs = 90

/**
 * The matching spring for anything that slides within the toolbar. Tuned to
 * settle in about [ToolbarMotionMs] so it lands with the fades.
 */
private val ToolbarSlideSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = IntOffset(1, 1),
)

/**
 * How far a re-placed icon may have travelled and still be animated, when
 * there is nothing better to size the limit against. Past this a move is
 * taken for a layout jump — a scroll, a page flip — and sliding to catch up
 * with one reads as lag, so it snaps instead.
 */
private const val PlacementSlideCap = 160f

/**
 * The same limit for an icon that lives in the toolbar, as a fraction of the
 * bar's width.
 *
 * A fixed cap is wrong there. The bar spreads its buttons over equal weighted
 * cells, so inserting one — the back chevron, when a panel opens — moves every
 * icon, and the leftmost moves the furthest: from the middle of the first cell
 * of n to the middle of the second of n+1. That is a quarter of the bar at its
 * worst (a bar holding only the toolbox button), well past [PlacementSlideCap]
 * on any phone. So the toolbox teleported into place on every panel open while
 * the icons beside it — which travel less the further right they sit — slid.
 * One icon snapping in a row of four sliding ones is the "jumpy" toolbar.
 *
 * Sized to clear that worst case with room to spare, while still refusing to
 * animate a move on the order of the whole bar.
 */
private const val ToolbarSlideCapFraction = 0.35f

/**
 * A vertical move larger than this means the row itself shifted rather than an
 * icon moving along it — a panel opened, the emoji row appeared or folded away.
 * Sliding into a row that has moved reads as lag, so those snap. Small enough
 * to ignore the sub-pixel rounding that weighted cells produce between passes.
 */
private const val ToolbarRowShiftPx = 4

/**
 * The strip's candidates fade in when they arrive and out when they leave,
 * rather than snapping. The fade-in is held a beat behind the emoji's slide
 * (the icon leads the eye into the strip) and runs long enough to read as a
 * fade rather than a flash; the fade-out is quicker, since it has to finish
 * inside the settle beat before the toolbar takes the row (see emptySettled).
 */
private const val StripContentStaggerMs = 45
private const val StripContentFadeInMs = 200
private const val StripContentFadeOutMs = 110

/**
 * How long the strip waits, after the candidates go empty, before it starts
 * hiding them. Fast typing empties the strip for a frame or two between
 * keystrokes (the engine clears then refills across the async compute), and
 * hiding on the first empty made the candidates pulse out and back in on every
 * keystroke. The hide is deferred by this beat; a fresh candidate landing
 * inside it cancels the pending hide, so a continuous typing burst never
 * flickers. Sized under the space of one relaxed keystroke so a genuine stop
 * still clears promptly.
 */
private const val StripHideDebounceMs = 180

/**
 * Issue-A tools fade: the toolbox and pinned tools materialise as the toolbar
 * takes over from the strip. Held back a beat so the emoji — which slides
 * across rather than fading — clears the toolbox slot first, instead of the
 * two overlapping mid-animation.
 */
private const val ToolbarToolsStaggerMs = 55

/**
 * Full-bleed return fade: the whole toolbar (emoji included) fades in when a
 * full-bleed panel closes and rebuilds the bar. Slower than the in-place
 * [ToolbarMotionMs] slide — nothing is moving to carry the eye, so a brisk
 * fade reads as an instant pop; a longer one lets the bar settle in.
 */
private const val FullBleedReturnFadeMs = 260

/**
 * The last candidates the strip actually drew, so a field that empties fades
 * them out instead of blanking.
 *
 * Deliberately not snapshot state: writing snapshot state from a composable
 * that reads it in the same pass is what forced the suggestion bar to compose
 * twice per keystroke. Nothing else observes these, and [TopBar] re-runs on
 * every publish of the ui state, so a plain object read in the pass that wrote
 * it is both correct and one composition cheaper.
 */
private class HeldCandidates(state: KeyboardUiState) {
    var suggestions: List<String> = state.suggestions
        private set
    var emojiSuggestions: List<String> = state.emojiSuggestions
        private set
    var punctuation: List<String> = state.punctuationSuggestions
        private set
    var inlineEmoji: Boolean = state.inlineEmoji
        private set

    fun advance(state: KeyboardUiState) {
        suggestions = state.suggestions
        emojiSuggestions = state.emojiSuggestions
        punctuation = state.punctuationSuggestions
        inlineEmoji = state.inlineEmoji
    }
}

@Composable
private fun TopBar(
    state: KeyboardUiState,
    /**
     * Whether the tools' own row is open, when the placement gives them one. The
     * bar reads the placement off the settings itself; only the row's open state
     * has to come in, because the row is drawn by the caller.
     */
    toolsRowOpen: Boolean = false,
    onToolsRowToggle: () -> Unit = {},
    onSuggestion: (String) -> Unit,
    onJoinSuggestion: () -> Unit = {},
    onRevisionSuggestion: () -> Unit = {},
    /** A conversion candidate tapped, with its position — see Composer.consumedForIndex. */
    onCandidate: (String, Int) -> Unit = { text, _ -> onSuggestion(text) },
    /** Open the expanded candidate grid. */
    onCandidatesExpand: () -> Unit = {},
    onEmoji: (String) -> Unit,
    onEmojiSuggestion: (String, Boolean) -> Unit,
    onPunctuation: (String) -> Unit = {},
    onPanelChange: (PanelMode) -> Unit,
    onToolTap: (ToolbarTool) -> Unit,
    drag: ToolDragController,
    onVoiceToggle: () -> Unit = {},
    onVoiceUndo: () -> Unit = {},
    onVoicePermissionRequest: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
    /** Strip's collapse button: switch dictation to the collapsed bar. */
    onVoiceCollapse: () -> Unit = {},
    onDismissInlineSuggestions: () -> Unit = {},
    onSmartAccept: () -> Unit = {},
    onSmartOpen: () -> Unit = {},
    onStripOfferAction: (Boolean) -> Unit = {},
    onClipboardSuggestion: (ClipItem) -> Unit = {},
    onClipboardSuggestionDismiss: () -> Unit = {},
    onClipboardEntity: (ClipEntity) -> Unit = {},
    onOtpAccept: (NotificationOtp) -> Unit = {},
    onOtpDismiss: () -> Unit = {},
    onEmojiRowShown: () -> Unit = {},
    /** Downward flick on the strip: dismiss the keyboard (opt-in). */
    onSwipeDownHide: () -> Unit = {},
) {
    // "Show the toolbar instead" while suggestions are up; resets once the
    // suggestions go away so the bar returns to candidates next time.
    var toolbarOverride by remember { mutableStateOf(false) }
    // Button-mode emoji row: a toolbar toggle swaps the strip for emojis.
    var emojiBarOpen by remember { mutableStateOf(false) }
    // A smart chip counts as strip content: without it the bar would flip
    // to the toolbar the moment word candidates ran out, taking the answer
    // to what was just typed with it. The recently-copied paste chip counts
    // the same way, so an idle strip holds it instead of flipping to the tools.
    val recentClipChip = state.settings.clipboard.suggestRecent && state.clipboardSuggestion != null
    val hasSuggestions = state.suggestions.isNotEmpty() ||
        state.emojiSuggestions.isNotEmpty() || state.smart != null || recentClipChip ||
        // The one-time-code chip counts as strip content for the same reason
        // the paste chip does: it lands exactly when nothing is being typed.
        state.otpSuggestion != null ||
        // So does a snippet waiting to be let in: a pattern's offer arrives on
        // the space that ends a sentence, which is precisely when the word
        // candidates are gone and the toolbar would otherwise take the row.
        state.snippetOffer != null ||
        // And so does a word asking to be learned: it arrives on the space
        // that ended the word, with the candidates for that word gone.
        state.learnOffer != null ||
        // A morse sequence being tapped out counts as strip content: the
        // toolbar taking the row would hide the one live view of the chord.
        // Its SOS easter-egg note counts the same way, or the toolbar would
        // cover the joke in the exact pause it appears in.
        state.morsePending.isNotEmpty() || state.morseSosEgg ||
        // Inline chips count too, in both lanes. Neither arrives while the user
        // is typing — a login field has no word candidates and a smart reply
        // comes before you have written anything — so without this the toolbar
        // would be the resting view exactly when the chips land, and they would
        // only ever be seen by someone who had turned the suggestion bar on
        // permanently.
        state.autofillChips.isNotEmpty() || state.smartReplyChips.isNotEmpty()
    // Suggestions-first mode keeps the strip as the resting state (an empty
    // strip plus the chevron into the toolbar); the override then survives
    // idle gaps and instead resets when fresh candidates arrive.
    val suggestionsFirst = state.settings.suggestionStrip.suggestionsFirst && state.settings.suggestions
    // The emoji panel is already all emojis — showing the row too would be
    // redundant, so opening the panel folds the row away.
    //
    // The fold is a derived flag read in the same pass, and the stored one is
    // cleared in an effect. Assigning to it inline instead wrote state that
    // this same composable reads further down, which forces a second
    // composition on the frame the panel opens — the row drew one frame at
    // its old size before folding, which is the flicker seen when the emoji
    // panel comes up.
    val emojiRowSuppressed =
        state.settings.emojiBarMode != EmojiBarMode.BUTTON || state.panel == PanelMode.EMOJI
    LaunchedEffect(emojiRowSuppressed) {
        if (emojiRowSuppressed) emojiBarOpen = false
    }
    // Committing a word can empty the strip for the moment it takes the
    // next-word predictions to land; flipping to the toolbar for that gap
    // makes the whole bar flicker on every space. The toolbar only takes
    // over once the strip has stayed empty for a beat.
    //
    // The beat is the hide debounce (which absorbs the typing-burst gaps, see
    // [StripHideDebounceMs]) plus the content fade-out, so the candidates
    // finish fading before the toolbar takes the row rather than being cut
    // mid-fade. During the debounce the strip still shows the last candidates
    // (held behind alpha 1), not an empty bar, so this no longer reads as the
    // keyboard stalling on a blank strip.
    var emptySettled by remember { mutableStateOf(true) }
    // One effect owns both the settle beat and the override reset, so the
    // override's live value can be read before it is cleared — two effects
    // racing on the same key left showToolbar reading a half-updated pair.
    LaunchedEffect(hasSuggestions) {
        if (hasSuggestions) {
            emptySettled = false
            // Suggestions-first rests on the strip, so a hand-opened toolbar
            // override only clears once fresh candidates actually arrive.
            if (suggestionsFirst) toolbarOverride = false
        } else {
            // Is the hand-opened toolbar the surface right now? Captured before
            // the reset just below clears it.
            val leavingOverrideToolbar = toolbarOverride && !suggestionsFirst
            // Candidates left: drop the override so the next ones show as
            // candidates again instead of staying hidden behind the toolbar.
            if (!suggestionsFirst) toolbarOverride = false
            if (leavingOverrideToolbar) {
                // Already on the toolbar the user opened by hand, and with no
                // suggestions the resting surface is the toolbar too — so hand
                // straight across, no settle gap. Delaying instead collapsed
                // the bar to an empty strip for that beat and bounced back: the
                // flip-flop that flung the emoji out to the row edge and popped
                // every other tool.
                emptySettled = true
            } else {
                delay((StripHideDebounceMs + StripContentFadeOutMs).toLong())
                emptySettled = true
            }
        }
    }
    // Where the tools live. With a row of their own this bar is the suggestion
    // strip and only that: the surface never flips, so the emoji handoff, the
    // dissolve and the settle beat below all sit at their resting values and cost
    // nothing. The chevron stays, as the opener for that row.
    val placement = state.settings.toolbarBehavior.placement
    val toolsOwnRow = placement.isOwnRow
    val wantToolbar = !toolsOwnRow &&
        (
            state.panel != PanelMode.NONE || toolbarOverride ||
                (!hasSuggestions && emptySettled && !suggestionsFirst)
            )

    // The surface the bar is actually drawing, which lags [wantToolbar] by one
    // [ToolbarExitMs] fade.
    //
    // The two surfaces are branches of an `if`, so the one being replaced is
    // disposed on the frame the decision lands: seven tool icons, or a row of
    // candidates, gone between one frame and the next while the emoji — the one
    // thing that survives the swap — took another 140ms to slide into its new
    // spot. A single icon crawling across an otherwise empty bar is what reads
    // as the flip being broken. Holding the old surface for a short fade first
    // makes the exchange a dissolve, and the emoji's slide starts from a bar
    // that still had something in it.
    //
    // Not a Crossfade, and not both branches at once: the emoji exists in both
    // and hands its position from one node to the other (see [SharedPlacement]),
    // which needs the old node gone before the new one is placed.
    var showToolbar by remember { mutableStateOf(wantToolbar) }
    val barExit = remember { Animatable(1f) }
    LaunchedEffect(wantToolbar, state.settings.reduceMotion) {
        if (wantToolbar == showToolbar) {
            // Flipped back inside the fade — the surface that was leaving is
            // staying after all, so bring it back rather than leaving it dim.
            if (barExit.value != 1f) barExit.animateTo(1f, tween(ToolbarExitMs))
            return@LaunchedEffect
        }
        if (state.settings.reduceMotion) {
            barExit.snapTo(1f)
            showToolbar = wantToolbar
            return@LaunchedEffect
        }
        barExit.animateTo(0f, tween(ToolbarExitMs, easing = LinearEasing))
        showToolbar = wantToolbar
        // The arriving surface runs its own fade-in, so this goes back to
        // transparent-to-it on the same frame the swap happens.
        barExit.snapTo(1f)
    }

    // Previous toolbar state, advanced after each frame commits; drives the
    // synchronous reveal blank for the tools fade below.
    var prevShowToolbar by remember { mutableStateOf(showToolbar) }
    SideEffect { prevShowToolbar = showToolbar }

    // The strip renders [shownSuggestions]/[shownEmojiSuggestions] — the last
    // non-empty candidates — rather than the live state, so a cleared field
    // fades the old candidates out over [StripContentFadeOutMs] instead of
    // blanking them the instant state empties. They refresh whenever real
    // candidates arrive and are held (behind alpha 0) once they leave.
    val suggestionsShowing = state.suggestions.isNotEmpty() || state.emojiSuggestions.isNotEmpty()
    // Candidates are on screen only while the strip itself holds the row — not
    // when the toolbar or a panel does. The fade keys on this, not on the
    // candidates alone: candidates can already exist, hidden behind the
    // toolbar, so when the strip retakes the row (a toolbox close, the chevron
    // toggled back) they never "arrive" — a presence-keyed fade wouldn't fire
    // and they'd snap on at full strength over the still-sliding emoji.
    val stripContentVisible = !showToolbar && suggestionsShowing
    // Held in a plain object and advanced *here*, in the composition that sees
    // the new candidates, rather than in four snapshot states written from an
    // effect afterwards. That is the same hazard the emoji row above documents
    // (see [emojiRowSuppressed]) and it was costing more here: those writes
    // invalidated this composable, which had only just finished running, so
    // every keystroke composed the whole bar twice — the chevron's
    // AnimatedVisibility, the chip loops, and [LatinSuggestionChips], which is
    // a subcompose layout and the most expensive thing on the strip. This
    // composable takes the ui state and so re-runs on every publish, which is
    // what lets the values live outside the snapshot system without going
    // stale. The non-empty gate is unchanged: it is what holds the last
    // candidates on screen for the fade-out instead of blanking them.
    val held = remember { HeldCandidates(state) }
    if (state.suggestions.isNotEmpty() || state.emojiSuggestions.isNotEmpty()) {
        held.advance(state)
    }
    val shownSuggestions = held.suggestions
    val shownEmojiSuggestions = held.emojiSuggestions
    // Held alongside the candidates: the fade-out must keep drawing the row it
    // faded in, or a cleared ":tada" buffer would flip the emoji back to text
    // chips for the length of the fade.
    val shownInlineEmoji = held.inlineEmoji
    // Punctuation chips are held alongside the words so they fade out with them
    // rather than blanking. The service only fills them when word candidates
    // are present, so they follow the same non-empty gate.
    val shownPunctuation = held.punctuation
    // Fade in when the strip shows its candidates, out when it stops. Keyed on
    // strip visibility (see [stripContentVisible]), so it fires both when
    // candidates land while the strip is up and when the strip retakes the row
    // from the toolbar with candidates already present — the latter used to
    // snap them on over the sliding emoji. It never re-fires mid-word, since
    // the engine updates candidates in place without emptying first. Initialised
    // to the current state so a strip that opens already showing doesn't fade in
    // from nothing.
    val stripContentAlpha = remember { Animatable(if (stripContentVisible) 1f else 0f) }
    LaunchedEffect(stripContentVisible, state.settings.reduceMotion) {
        if (state.settings.reduceMotion) {
            // No fade, but the hide still debounces so a typing-burst gap
            // doesn't blink the strip off and back on. Deferring a snap adds no
            // motion, so reduce-motion is honoured.
            if (stripContentVisible) {
                stripContentAlpha.snapTo(1f)
            } else {
                delay(StripHideDebounceMs.toLong())
                stripContentAlpha.snapTo(0f)
            }
        } else if (stripContentVisible) {
            // Let the emoji lead into the strip before the words follow. Linear
            // rather than the default eased curve, which front-loads the ramp
            // and made even a long fade read as an instant pop.
            delay(StripContentStaggerMs.toLong())
            stripContentAlpha.animateTo(1f, tween(StripContentFadeInMs, easing = LinearEasing))
        } else {
            // Defer the hide: the effect is keyed on visibility, so candidates
            // landing (or the strip retaking the row) inside the debounce cancel
            // this and restart the fade-in branch — the pulse fast typing caused.
            delay(StripHideDebounceMs.toLong())
            stripContentAlpha.animateTo(0f, tween(StripContentFadeOutMs, easing = LinearEasing))
        }
    }

    // The toolbar's tools are freshly composed when it takes over from the
    // strip, so [animatePlacement] settles them in place with no motion — they
    // would pop while the emoji (which hands its position across) slides. So
    // they fade in instead. Two shapes:
    //
    //  - In-place strip→toolbar flip: the emoji slides its position across (its
    //    throughline), and the other tools fade in held a beat behind so the
    //    icon clears the toolbox slot first (see [ToolbarToolsStaggerMs]). The
    //    surviving node keeps its old alpha of 1, so [toolbarJustRevealed]
    //    blanks its first frame rather than the initial value below.
    //
    //  - Fresh mount (returning from a full-bleed gif/emoji panel disposes and
    //    rebuilds the whole bar): nothing is sliding, everything simply
    //    appears, so the whole toolbar — emoji included — fades in together, no
    //    stagger. [toolsFade] starts at 0 so it fades from blank instead of
    //    painting one frame opaque, snapping to 0, and refading (the jitter).
    val toolsFade = remember {
        Animatable(if (showToolbar && !state.settings.reduceMotion) 0f else 1f)
    }
    // Whether the emoji joins the tools' fade (fresh mount) or sits it out and
    // slides (in-place flip). Seeded for a mount that opens on the toolbar.
    var toolsFadeMounted by remember { mutableStateOf(false) }
    var emojiFadesWithTools by remember {
        mutableStateOf(showToolbar && !state.settings.reduceMotion)
    }
    val toolbarJustRevealed = !prevShowToolbar && showToolbar && !state.settings.reduceMotion
    LaunchedEffect(showToolbar, state.settings.reduceMotion) {
        val freshMount = !toolsFadeMounted
        toolsFadeMounted = true
        if (!showToolbar || state.settings.reduceMotion) {
            emojiFadesWithTools = false
            toolsFade.snapTo(1f)
        } else {
            // A mount fades the emoji in with the tools; a later in-place flip
            // lets it slide instead and trails the tools behind it. The mount
            // fade is slower ([FullBleedReturnFadeMs]) since nothing slides to
            // carry the eye; the in-place one matches the emoji's slide.
            emojiFadesWithTools = freshMount
            toolsFade.snapTo(0f)
            if (!freshMount) delay(ToolbarToolsStaggerMs.toLong())
            toolsFade.animateTo(1f, tween(if (freshMount) FullBleedReturnFadeMs else ToolbarMotionMs))
        }
    }
    // [barExit] is the dissolve the tools run when the strip is taking the row
    // back; [toolsFade] is the one they run arriving. Only ever one of the two
    // is away from 1, so the product is whichever is in play.
    val toolContentAlpha = {
        if (toolbarJustRevealed) 0f else toolsFade.value * barExit.value
    }
    /** The candidates' alpha: their own fade, dimmed by the bar's dissolve. */
    val stripContentFade = { stripContentAlpha.value * barExit.value }

    // The suggestion strip (and the toolbar that shares its bar) lays out
    // right-to-left for RTL scripts — Arabic, Hebrew, Persian, Urdu, Thaana —
    // so the first/best candidate sits on the right, where an RTL reader's eye
    // starts. Only this bar is flipped; the key grid is a sibling composable
    // and stays left-to-right.
    val stripDirection = if (state.script.direction == TextDirection.RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides stripDirection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings))
            // A deliberate downward flick anywhere on the strip dismisses the
            // keyboard. A tool's own reorder is a hold-then-drag, so it fires
            // its long-press first and never reaches this detector; a quick
            // flick never trips the long-press, so the two don't collide.
            .then(
                if (state.settings.toolbarBehavior.swipeDownHide) {
                    Modifier.pointerInput(onSwipeDownHide) {
                        val threshold = ToolbarSwipeHideThreshold.toPx()
                        var travelled = 0f
                        var fired = false
                        detectVerticalDragGestures(
                            onDragStart = { travelled = 0f; fired = false },
                            onDragEnd = { travelled = 0f; fired = false },
                            onDragCancel = { travelled = 0f; fired = false },
                        ) { _, dragAmount ->
                            travelled += dragAmount
                            if (!fired && travelled > threshold) {
                                fired = true
                                onSwipeDownHide()
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val feedback = LocalKeyPressFeedback.current
        // Compact dictation bar takes over the whole strip while active;
        // the keys below stay usable for fixing recognition errors.
        //
        // Interactive voice typing gets a microphone button instead and the
        // strip carries on: there the keys are used all through the session,
        // so the candidates have to stay reachable. See [VoiceMicChip].
        if (state.voice.strip) {
            if (state.voiceChipOnly()) {
                VoiceMicChip(state = state, onToggle = onVoiceToggle)
            } else {
                VoiceStripBar(
                    state = state,
                    onToggle = onVoiceToggle,
                    onUndo = onVoiceUndo,
                    onRequestPermission = onVoicePermissionRequest,
                    onOpenVoiceSettings = onOpenVoiceSettings,
                    onCollapse = onVoiceCollapse,
                    // The tool tap toggles the strip, so it also closes it.
                    onClose = { onToolTap(ToolbarTool.VOICE) },
                    modifier = Modifier.weight(1f),
                )
                return@Row
            }
        }
        if (emojiBarOpen && !emojiRowSuppressed && !hasSuggestions) {
            EmojiBarStrip(
                state = state,
                onEmoji = onEmoji,
                onOpenPanel = { onPanelChange(PanelMode.EMOJI) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    feedback()
                    emojiBarOpen = false
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_emoji_row_close_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Row
        }
        val motionMs = if (state.settings.reduceMotion) 0 else ToolbarMotionMs
        // One chevron that spins between the two directions, rather than
        // two icons swapped instantly: the rotation is what tells the
        // user the bar flipped, since the strip and the toolbar look
        // nothing alike and a hard cut reads as a redraw.
        //
        // Both the rotation state and the visibility gate live out here,
        // above the guard. Inside it they were useless: the bar auto-flips
        // to the toolbar at the moment the strip runs dry, which is the same
        // moment `hasSuggestions` goes false and the guard drops the button.
        // The node died on the very frame the rotation was meant to play, so
        // the chevron vanished mid-turn instead of turning.
        // Keyed on the decision, not on the drawn surface: the turn is the
        // acknowledgement of the tap, so it starts on the frame the tap lands
        // and runs through the outgoing dissolve rather than waiting it out.
        // With the tools on their own row the chevron opens and closes that row
        // instead of flipping this one, so the turn tracks the row's state.
        val chevronTurn by animateFloatAsState(
            targetValue = if (if (toolsOwnRow) toolsRowOpen else wantToolbar) 180f else 0f,
            animationSpec = if (state.settings.reduceMotion) snap() else tween(ToolbarMotionMs),
            label = "chevronTurn",
        )
        // While any panel is open the toolbar is forced on and shows its own
        // back chevron, so the suggestions-toggle chevron would sit next to
        // it doing nothing — tools don't need suggestions. Hide it.
        //
        // Fade only, never expandHorizontally/shrinkHorizontally. A width
        // animation here re-measures the whole toolbar on every frame of the
        // transition, and the pinned icons track their position through
        // [animatePlacement], which reads its target in onPlaced — so each of
        // those frames handed every icon a new target and restarted its
        // spring. That is what made opening the bar look like the icons were
        // shivering. Fading keeps the layout change atomic: one reflow, one
        // spring per icon.
        // The exit releases the slot at once instead of fading. AnimatedVisibility
        // holds a child's space for the whole exit, so a fade here meant the
        // strip swapped for the toolbar immediately, the chevron then sat
        // fading in a 36dp gap, and only 140ms later did that gap close and
        // shove everything sideways a second time. Two layout jumps around one
        // decision is the jitter. One jump, and the icons spring into it.
        //
        // Tied to the strip being up, not to there being suggestions. Those
        // are not the same instant: the strip stays for a beat after the last
        // suggestion goes (see emptySettled), and keying on suggestions alone
        // pulled the chevron out at the front of that beat. Its 36dp then
        // vanished from the middle of a strip that was still on screen, so
        // everything left of it — the emoji icon included — slid across
        // before the handoff to the toolbar had even begun, and the icon
        // started its slide from a position it had already been shoved out of.
        // Now the chevron leaves on the same frame the toolbar arrives.
        //
        // Both halves of that are why the test is written against the surface
        // being *drawn* and gated on [wantToolbar]. The bar decides its flip one
        // dissolve before it draws it, and the chevron reads inputs that change
        // with the decision — so on the plain "type into an empty bar" flip it
        // used to appear the instant a candidate arrived, claim its 36dp out of
        // a toolbar that was still on screen, and shove every tool right; the
        // emoji then slid right, was handed to the strip, and slid back left
        // past where it started. One decision, and the row moves once, when the
        // surface it belongs to arrives.
        AnimatedVisibility(
            visible = when (placement) {
                // The row is always there; there is nothing for a chevron to do.
                ToolbarPlacement.ALWAYS_ROW -> false
                // The chevron *is* the way to that row, so it is always offered.
                ToolbarPlacement.ON_DEMAND_ROW -> true
                ToolbarPlacement.STRIP -> !showToolbar ||
                    (
                        wantToolbar && state.panel == PanelMode.NONE &&
                            (hasSuggestions || suggestionsFirst)
                        )
            },
            enter = fadeIn(tween(motionMs)),
            exit = ExitTransition.None,
        ) {
            IconButton(
                onClick = {
                    feedback()
                    if (toolsOwnRow) onToolsRowToggle() else toolbarOverride = !toolbarOverride
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = stringResource(
                        if (if (toolsOwnRow) toolsRowOpen else showToolbar) {
                            R.string.ime_suggestions_show_desc
                        } else {
                            R.string.ime_toolbar_show_desc
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronTurn },
                )
            }
        }
        if (showToolbar) {
            // The emoji never joins the dissolve on the way out: it is the one
            // icon that survives the swap, and it has to be solid at the moment
            // it hands its position to the strip's copy.
            ToolbarRow(
                state, onPanelChange, onToolTap, drag, toolContentAlpha,
                fadeEmoji = emojiFadesWithTools && wantToolbar,
            )
            if (state.settings.emojiBarMode == EmojiBarMode.BUTTON) {
                IconButton(
                    onClick = {
                        feedback()
                        emojiBarOpen = true
                        onEmojiRowShown()
                    },
                    modifier = Modifier
                        .size(36.dp)
                        // Fades in with the rest of the tools on strip→toolbar.
                        .graphicsLayer { alpha = toolContentAlpha() },
                ) {
                    Icon(
                        Icons.Outlined.EmojiEmotions,
                        contentDescription = stringResource(R.string.ime_emoji_row_show_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            if (state.settings.emojiToolbar && ToolbarTool.EMOJI in state.settings.enabledTools) {
                // The width the bar would give this icon, so the two copies are
                // the same shape. Nothing constrains it here, so at a tool width
                // wider than a toolbar cell the strip's copy came out at the
                // full setting while the pinned one was capped at its cell — the
                // icon changed size mid-slide, and since the slide tracks left
                // edges its centre jumped by half the difference first. See
                // [ToolDragController.pinnedToolWidthPx].
                val pinnedWidth = with(LocalDensity.current) {
                    drag.pinnedToolWidthPx.takeIf { it > 0 }?.toDp()
                }
                ToolCircle(
                    slot = IconSlots.CHROME_EMOJI_SHORTCUT,
                    description = stringResource(R.string.ime_tool_emoji),
                    active = false,
                    // Same icon the toolbar pins: it slides between the two
                    // spots instead of vanishing here and reappearing there.
                    modifier = Modifier
                        .then(
                            if (pinnedWidth != null) {
                                Modifier.widthIn(max = pinnedWidth)
                            } else {
                                Modifier
                            },
                        )
                        .animateSharedPlacement(
                            drag.emojiPlacement,
                            enabled = !state.settings.reduceMotion,
                        ) { drag.bodyCoords },
                    longPressLabel = stringResource(R.string.ime_tool_emoji),
                    // Matches the toolbar's pinned emoji footprint, so the
                    // shared-placement slide lands on an identical shape.
                    wide = true,
                ) { onToolTap(ToolbarTool.EMOJI) }
            }
            // Autofill chips take the whole strip while they are up: they
            // answer the field directly ("use this saved login"), which beats
            // any word the dictionary could offer, and they are transient —
            // dismissed, or gone as soon as the field is left. Smart replies
            // off the same API do *not* get this treatment; they are handled
            // further down, beside the words.
            if (state.autofillChips.isNotEmpty()) {
                InlineChipRow(
                    chips = state.autofillChips,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { onDismissInlineSuggestions() }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ime_autofill_dismiss_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                return@Row
            }
            // A dead key is armed: show which accent the next letter will
            // take, otherwise the keyboard looks like it swallowed a press.
            state.pendingDeadKey?.let { accent ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = accent,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // The morse sequence being tapped out, plus the letter it spells so
            // far — the same "show the armed input" job as the dead-key hint
            // above, sized up because it is the primary feedback while typing
            // morse (the keys themselves all look alike).
            if (state.morsePending.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    val spelled = MorseCode.decode(
                        state.morsePending.map { if (it == '·') '.' else '-' }.joinToString(""),
                    )
                    Text(
                        text = if (spelled != null) {
                            "${state.morsePending}   $spelled"
                        } else {
                            state.morsePending
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                }
                return@Row
            }
            // Easter egg: S, O, S was just keyed. Below the live readout on
            // purpose — feedback for the letter being tapped outranks a joke —
            // so it shows in the pauses and retires by itself after a few
            // seconds.
            if (state.morseSosEgg) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.ime_morse_sos_egg_info),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                return@Row
            }
            // A one-time code lifted from a just-arrived notification. It
            // outranks every chip below: the code is the reason the user is on
            // this field, and it expires while everything else can wait. Only
            // the autofill lane above beats it — the platform may be offering
            // the same code with more context than the keyboard has.
            val otpSuggestion = state.otpSuggestion
            if (otpSuggestion != null) {
                val otpShares = suggestionsShowing || state.smartReplyChips.isNotEmpty()
                OtpSuggestionChip(
                    otp = otpSuggestion,
                    onAccept = { onOtpAccept(otpSuggestion) },
                    onDismiss = onOtpDismiss,
                    stretch = !otpShares,
                    modifier = if (otpShares) {
                        Modifier.widthIn(max = 180.dp).padding(horizontal = 4.dp)
                    } else {
                        Modifier.weight(1f).padding(horizontal = 4.dp)
                    },
                )
                if (!otpShares) return@Row
            }
            // A snippet whose trigger matched but which was told to ask first.
            // It shares the row rather than taking it: what the user typed is
            // still perfectly good text, and the word candidates are how they
            // carry on typing it if the answer is no.
            val snippetOffer = state.snippetOffer
            if (snippetOffer != null) {
                val offerShares = suggestionsShowing || state.smart != null
                SnippetOfferChip(
                    offer = snippetOffer,
                    onAccept = { onStripOfferAction(true) },
                    stretch = !offerShares,
                    modifier = if (offerShares) {
                        Modifier.widthIn(max = 200.dp).padding(horizontal = 4.dp)
                    } else {
                        Modifier.weight(1f).padding(horizontal = 4.dp)
                    },
                )
                if (!offerShares) return@Row
            }
            // A word nothing recognises, asking whether it belongs in the
            // dictionary. Shares the row like the snippet offer above and for
            // the same reason: the word is already typed and perfectly good
            // text either way, so the candidates keep working while it waits.
            val learnOffer = state.learnOffer
            if (snippetOffer == null && learnOffer != null) {
                val learnShares = suggestionsShowing || state.smart != null
                LearnWordChip(
                    word = learnOffer,
                    onAccept = { onStripOfferAction(true) },
                    onDecline = { onStripOfferAction(false) },
                    stretch = !learnShares,
                    modifier = if (learnShares) {
                        Modifier.widthIn(max = 210.dp).padding(horizontal = 4.dp)
                    } else {
                        Modifier.weight(1f).padding(horizontal = 4.dp)
                    },
                )
                if (!learnShares) return@Row
            }
            // A recognised sum/conversion answers the text directly, so it
            // takes the whole strip the way autofill chips do. A keyword
            // chip ("wiki" → open Wikipedia) only claims the space it needs,
            // because the word being typed may simply be that word.
            val smart = state.smart
            val keywordChip = smart != null && smart.kind in SmartSuggest.narrowKinds
            if (smart != null) {
                // Opening runs in two halves: the service clears the trigger
                // text and stages the prefill, then the tool is tapped the
                // ordinary way so panel routing stays in one place.
                val open = {
                    onSmartOpen()
                    onToolTap(smart.tool)
                }
                SmartSuggestionChip(
                    hit = smart,
                    reduceMotion = state.settings.reduceMotion,
                    icon = toolIcon(smart.tool),
                    modifier = if (keywordChip) {
                        Modifier.padding(start = 4.dp)
                    } else {
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    },
                    // A wide chip with nothing to type (the weather answer)
                    // opens its tool instead: the whole face is one door.
                    onAccept = { if (keywordChip || smart.insert == null) open() else onSmartAccept() },
                    onOpen = open,
                )
            }
            if (smart != null && !keywordChip) return@Row
            // Platform smart replies. They share the row rather than claiming
            // it: a proposed reply is a suggestion like any other, and the user
            // may well be about to type something else entirely.
            val smartReplies = state.smartReplyChips
            // Recently-copied paste chip (Gboard style): takes the idle strip
            // when there are no candidates, one tap from pasting the last copy.
            // Word candidates always win the row, so it never hides a suggestion.
            val recentClip = state.clipboardSuggestion
            // A verification SMS is the one copy where the whole clip is not
            // what you want pasted, so the chip offers the code out of it
            // instead — dashed, to say it is a piece of the clip and not the
            // clip. Only codes get this: a copied link or number is already
            // exactly what the ordinary chip would paste.
            val chipOtp = if (recentClip != null && state.settings.clipboard.detectEntities) {
                remember(recentClip.id, recentClip.text) {
                    ClipEntities.extract(recentClip.text, recentClip.id)
                        .firstOrNull { it.kind == ClipEntityKind.OTP }
                        ?.takeIf { it.value != recentClip.text.trim() }
                }
            } else {
                null
            }
            // Replies crowd the row the same way word candidates do, so the
            // paste chip gives way to its narrow form when both are present
            // rather than stretching across a strip it now shares.
            val clipChipShares = suggestionsShowing || smartReplies.isNotEmpty()
            if (recentClipChip && smart == null) {
                ClipboardSuggestionChip(
                    clip = recentClip,
                    otp = chipOtp,
                    onPaste = {
                        if (chipOtp != null) onClipboardEntity(chipOtp)
                        else onClipboardSuggestion(recentClip)
                    },
                    onDismiss = onClipboardSuggestionDismiss,
                    stretch = !clipChipShares,
                    modifier = if (clipChipShares) {
                        Modifier.widthIn(max = 160.dp).padding(horizontal = 4.dp)
                    } else {
                        Modifier.weight(1f).padding(horizontal = 4.dp)
                    },
                )
                if (!clipChipShares) return@Row
            }
            // Nothing typed yet: the replies are the strip, so they take the
            // rest of the row and carry the dismiss ✕ the way the autofill lane
            // does. This is the case they exist for — the message is on screen,
            // the field is empty, and the reply is the whole answer.
            if (smartReplies.isNotEmpty() && !suggestionsShowing) {
                InlineChipRow(
                    chips = smartReplies,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { onDismissInlineSuggestions() }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ime_smart_replies_dismiss_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                return@Row
            }
            // Latin gets three wide chips; a conversion IME gets a scrolling
            // row. Three is the right number when the engine is choosing for you
            // and you only overrule it now and then, but a pinyin reading is
            // genuinely ambiguous — the composer offers a dozen candidates and
            // picking among them *is* the typing. Splitting here rather than
            // widening the shared row keeps the Latin strip exactly as it was.
            if (shownInlineEmoji) {
                // A ":tada" buffer: emoji, in the emoji font, as many as fit
                // the scroll rather than the three slots words get.
                InlineEmojiChips(
                    emojis = shownSuggestions,
                    enabled = suggestionsShowing,
                    alpha = stripContentFade,
                    onEmoji = onSuggestion,
                )
            } else if (state.composer.isConversion) {
                CandidateStrip(
                    candidates = shownSuggestions,
                    enabled = suggestionsShowing,
                    alpha = stripContentFade,
                    textScale = state.settings.suggestionStrip.textScale,
                    hints = if (suggestionsShowing) suggestionHintPlan(state) else null,
                    onCandidate = onCandidate,
                    onExpand = onCandidatesExpand,
                )
            } else {
                // The join chip leads the row, visually apart from the three
                // word slots: it rewrites text already in the field, which a
                // plain suggestion never does. The revision chip shares the
                // slot (they can't coexist: join needs a composing word,
                // revision needs an empty one) and the same look, for the
                // same reason — both rewrite committed text on tap. So does
                // the near-miss correction chip, behind both: a confusable the
                // follower has already proved wrong is better evidence than a
                // correction that only came close.
                val join = state.joinSuggestion
                val revision = state.revisionSuggestion
                val rewriteChip = join ?: revision ?: state.correctionOffer
                if (rewriteChip != null && suggestionsShowing) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer { alpha = stripContentFade() }
                            .padding(vertical = 8.dp, horizontal = 2.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                if (join != null) onJoinSuggestion() else onRevisionSuggestion()
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = rewriteChip,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                        )
                    }
                }
                LatinSuggestionChips(
                    candidates = shownSuggestions,
                    enabled = suggestionsShowing,
                    alpha = stripContentFade,
                    slotCount = state.settings.suggestionStrip.slotCount,
                    textScale = state.settings.suggestionStrip.textScale,
                    scrollable = state.settings.suggestionStrip.scrollable,
                    textPadding = state.settings.suggestionStrip.chipPadding.dp,
                    centerPrimaryEnabled = state.settings.suggestionStrip.suggestionPrimaryCenter,
                    shiftState = state.shiftState,
                    // Only while the live candidates are the ones on screen: the
                    // strip holds the last set behind alpha 0, and a key promised
                    // against a faded word would commit something else.
                    hints = if (suggestionsShowing) suggestionHintPlan(state) else null,
                    onSuggestion = onSuggestion,
                )
            }
            // Emoji candidates ride along after the words: typing "birthday"
            // puts 🎂 🎉 🥳 🎁 one tap away. Held set, so they fade out with
            // the words rather than vanishing; taps gated to the live ones.
            //
            // Holding one runs the *other* insert mode: the setting decides
            // which of "replace the word" and "keep the word" a tap does, and
            // the hold is the escape hatch for the one time you want the other,
            // without a trip to settings. The label says which, read from the
            // live setting, so TalkBack announces the action rather than "long
            // press".
            //
            // The label is resolved inside the guard, not above it: the strip
            // recomposes on every keystroke, and most of those have no emoji
            // candidates to label.
            if (shownEmojiSuggestions.isNotEmpty()) {
                val holdLabel = stringResource(
                    if (state.settings.emojiInsertMode == EmojiInsertMode.APPEND) {
                        R.string.ime_emoji_suggestion_hold_replace
                    } else {
                        R.string.ime_emoji_suggestion_hold_keep
                    },
                )
                for (emoji in shownEmojiSuggestions.take(4)) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer { alpha = stripContentFade() }
                            .combinedClickable(
                                enabled = suggestionsShowing,
                                onLongClickLabel = holdLabel,
                                onLongClick = { onEmojiSuggestion(emoji, true) },
                                onClick = { onEmojiSuggestion(emoji, false) },
                            )
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = LocalEmojiShaper.current.shape(emoji),
                            fontSize = 22.sp,
                            fontFamily = emojiFamilyFor(emoji),
                        )
                    }
                }
            }
            // Replies ride the tail once there are words to share with, capped
            // in width so the candidates keep their three slots — the weighted
            // word row is measured after unweighted children, so an unbounded
            // scroll row here would quietly eat the whole strip.
            if (smartReplies.isNotEmpty()) {
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                InlineChipRow(
                    chips = smartReplies,
                    modifier = Modifier.widthIn(max = 180.dp).fillMaxHeight(),
                )
            }
            // Quick-punctuation chips ride the tail (the service leaves the list
            // empty whenever an emoji prediction claimed it, so the two never
            // fight for the row). A leading divider sets them off from the words.
            if (shownPunctuation.isNotEmpty()) {
                VerticalDivider(
                    modifier = Modifier
                        .height(20.dp)
                        .graphicsLayer { alpha = stripContentFade() },
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                for (mark in shownPunctuation) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer { alpha = stripContentFade() }
                            .clickable(enabled = suggestionsShowing) { onPunctuation(mark) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mark,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * A scrolling row of inline-suggestion chips — the views another process
 * inflated for us. Shared by both lanes: password-manager chips, which take
 * the whole strip, and platform smart replies, which take whatever the caller
 * gives them.
 *
 * The views arrive fully built, so this only positions them; anything that
 * looks like styling would be a guess at what the sending app drew.
 */
@Composable
private fun InlineChipRow(
    chips: List<android.view.View>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in chips) {
            AndroidView(
                // The same chip view can be re-hosted — a reply moves between
                // the idle row and the tail row as candidates come and go — and
                // a view that still remembers its old container throws on the
                // way in, so detach it first.
                factory = {
                    (chip.parent as? android.view.ViewGroup)?.removeView(chip)
                    chip
                },
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/**
 * The inline emoji-search row: what a ":tada" composing buffer puts in the
 * strip.
 *
 * Unlike [LatinSuggestionChips] this scrolls and sizes its cells to the emoji,
 * because a shortcode search is a search — ":sm" has a dozen answers worth
 * scanning, and three word-shaped slots would hide nine of them. Drawn through
 * [LocalEmojiFontFamily]/[LocalEmojiShaper] like every other emoji surface, so
 * a chosen emoji font applies here too.
 */
@Composable
private fun RowScope.InlineEmojiChips(
    emojis: List<String>,
    enabled: Boolean,
    alpha: () -> Float,
    onEmoji: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { this.alpha = alpha() },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keyed by position, not by the emoji: a duplicate key is a hard crash
        // in LazyRow, and the results come from a generated catalog no runtime
        // check guarantees is duplicate-free.
        itemsIndexed(emojis, key = { index, emoji -> "$index $emoji" }) { _, emoji ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(enabled = enabled) { onEmoji(emoji) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Drawn as this font spells it; the tap still commits the
                    // standard form (see [EmojiFontShaping]).
                    text = LocalEmojiShaper.current.shape(emoji),
                    fontSize = 22.sp,
                    fontFamily = emojiFamilyFor(emoji),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The Latin suggestion strip: the top [slotCount] candidates splitting the bar
 * evenly (Gboard style), so each gets the largest possible tap target.
 *
 * [candidates] is the *held* set, so a cleared field fades the last words out
 * rather than blanking them; [enabled] gates taps to the live ones. [alpha] is
 * read inside a graphics layer so the fade does not recompose the row.
 */
@Composable
private fun RowScope.LatinSuggestionChips(
    candidates: List<String>,
    enabled: Boolean,
    alpha: () -> Float,
    slotCount: Int,
    /** Multiplier on the suggestion text, from the settings slider. */
    textScale: Float,
    /**
     * Draw every word at its natural width and let the row scroll, instead of
     * shrinking words into equal fixed slots. A slot still gets at least its
     * equal share, so short words fill the strip the same either way.
     */
    scrollable: Boolean = false,
    /** Breathing room on each side of a word inside its slot. */
    textPadding: Dp = SuggestionTextPadding,
    centerPrimaryEnabled: Boolean,
    shiftState: ShiftState,
    /** The hotkey badges, or null when no physical keyboard is asking for them. */
    hints: HintPlan? = null,
    onSuggestion: (String) -> Unit,
) {
    // BoxWithConstraints, not a bare Row, because the chips shrink long words to
    // fit and that needs the slot width up front. One subcomposition covers
    // every slot: they carry equal weight, so each is the same width.
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // Fades in a beat behind the emoji's slide as candidates arrive,
            // and out as they leave (see [stripContentAlpha]).
            .graphicsLayer { this.alpha = alpha() },
    ) {
        val ranked = candidates.take(slotCount)
        // Gboard convention: the primary candidate sits in the middle slot with
        // the runner-up on its left. The commit path still uses the engine's
        // order — this is display-only.
        val centerPrimary = centerPrimaryEnabled && ranked.size >= 2
        val shown = if (centerPrimary) {
            listOf(ranked[1], ranked[0]) + ranked.drop(2)
        } else {
            ranked
        }
        val primaryIndex = if (centerPrimary) 1 else 0
        val slotWidth = if (shown.isEmpty()) {
            0.dp
        } else {
            (maxWidth - SuggestionDividerWidth * (shown.size - 1)) / shown.size
        }
        val textWidth = (slotWidth - textPadding * 2).coerceAtLeast(0.dp)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val baseStyle = LocalTextStyle.current
        // The user's own text scale rides on whichever base is in force, so a
        // theme that sets its own suggestion size is scaled rather than replaced.
        val baseSize = (
            if (baseStyle.fontSize.isSpecified) baseStyle.fontSize else SuggestionFontSize
            ) * textScale
        val shaper = LocalEmojiShaper.current
        // Scroll mode: a fresh set of candidates starts at the left edge again,
        // otherwise the strip could be sitting at the far end of the previous
        // word's overflow and show nothing of the new primary.
        val scrollState = rememberScrollState()
        if (scrollable) {
            LaunchedEffect(shown) { scrollState.scrollTo(0) }
        }
        Row(
            modifier = if (scrollable) {
                Modifier.fillMaxSize().horizontalScroll(scrollState)
            } else {
                Modifier.fillMaxSize()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEachIndexed { index, suggestion ->
                if (index > 0) {
                    VerticalDivider(
                        modifier = Modifier.height(20.dp),
                        thickness = SuggestionDividerWidth,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                // Fixed mode splits the width by weight. Scroll mode cannot
                // (weights are meaningless under unbounded width), so each
                // slot floors at the equal share and grows with its word.
                val slotModifier = if (scrollable) {
                    Modifier.widthIn(min = slotWidth)
                } else {
                    Modifier.weight(1f)
                }
                Box(
                    modifier = slotModifier
                        .fillMaxHeight()
                        .clickable(enabled = enabled) { onSuggestion(suggestion) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Counted by slot, so the strip always reads 1 2 3 from the
                    // left even with the primary centred. The plan holds the
                    // rank each slot is drawing (see [suggestionDisplayOrder]).
                    val hint = hints?.label(HintSurface.SUGGESTION, index)
                    if (hint != null) {
                        HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                    // A chip is sometimes an emoji rather than a word — a learned
                    // bigram can predict one ("you" → ❤️). Those have to be drawn
                    // in the chosen emoji font, and in the spelling that font
                    // wants, or they fall back to the system emoji.
                    val isEmoji = remember(suggestion) { EmojiGraphemes.isEmojiOnly(suggestion) }
                    // Follows the live shift state, so pressing shift re-cases
                    // the strip (matching the committed word).
                    val display = if (isEmoji) {
                        shaper.shape(suggestion)
                    } else {
                        displayCaseForShift(suggestion, shiftState)
                    }
                    val family = if (isEmoji) emojiFamilyFor(suggestion) else null
                    val weight =
                        if (index == primaryIndex) FontWeight.SemiBold else FontWeight.Normal
                    // Re-measured only when the word, its styling or the slot
                    // width actually change — not on every keystroke that leaves
                    // this chip alone.
                    val fit = if (scrollable) {
                        // Natural width: nothing to fit, the row scrolls instead.
                        SuggestionTextFit(1f, 1f)
                    } else remember(display, family, weight, textWidth, baseSize, baseStyle) {
                        val measured = measurer.measure(
                            text = AnnotatedString(display),
                            style = baseStyle.merge(
                                TextStyle(
                                    fontSize = baseSize,
                                    fontFamily = family,
                                    fontWeight = weight,
                                ),
                            ),
                            maxLines = 1,
                            softWrap = false,
                        ).size.width
                        fitSuggestionText(
                            measuredWidthPx = measured.toFloat(),
                            availableWidthPx = with(density) { textWidth.toPx() },
                        )
                    }
                    Text(
                        text = display,
                        modifier = Modifier.padding(horizontal = textPadding),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = baseSize * fit.fontScale,
                        softWrap = false,
                        // The default 0.5sp tracking is dead width once a word is
                        // already being squeezed.
                        letterSpacing = if (fit.condensed) 0.sp else baseStyle.letterSpacing,
                        fontFamily = family,
                        fontWeight = weight,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (fit.condensed) {
                            baseStyle.copy(
                                textGeometricTransform =
                                    TextGeometricTransform(scaleX = fit.scaleX),
                            )
                        } else {
                            baseStyle
                        },
                    )
                }
            }
        }
    }
}

/** Divider thickness between suggestion slots; subtracted from the usable slot width. */
private val SuggestionDividerWidth = 1.dp

/** Breathing room on each side of a suggestion word inside its slot; the shipped default. */
private val SuggestionTextPadding = 6.dp

/** Fallback base size when the ambient text style doesn't name one (Material's `bodyLarge`). */
private val SuggestionFontSize = 16.sp

/**
 * How small a suggestion's font may go, as a fraction of the base size — 16sp
 * down to about 11sp. Past this the word condenses horizontally instead, because
 * shrinking further costs legibility faster than it buys width.
 */
private const val MinSuggestionFontScale = 0.68f

/**
 * How far a suggestion may be squeezed horizontally. Below roughly 0.8 the
 * letterforms visibly distort, so anything still too long after this ellipsizes.
 */
private const val MinSuggestionScaleX = 0.8f

/**
 * How a suggestion word is scaled down to fit its slot.
 *
 * [fontScale] multiplies the base font size; [scaleX] condenses the glyphs
 * horizontally on top of that. `SuggestionTextFit(1f, 1f)` means the word fits
 * as-is and neither is applied.
 */
internal data class SuggestionTextFit(val fontScale: Float, val scaleX: Float) {
    /** True once the word is being squeezed horizontally, not merely shrunk. */
    val condensed: Boolean get() = scaleX < 1f
}

/**
 * Works out how to fit a suggestion of [measuredWidthPx] into [availableWidthPx].
 *
 * Two stages, in the order that costs the least legibility: shrink the point
 * size first (text width is near enough linear in font size, so the ratio of the
 * two widths *is* the scale), and only once that hits [MinSuggestionFontScale]
 * start condensing the glyphs. Together the two floors fit a word about 1.8× the
 * slot width; longer than that and the caller's ellipsis takes over.
 */
internal fun fitSuggestionText(
    measuredWidthPx: Float,
    availableWidthPx: Float,
    minFontScale: Float = MinSuggestionFontScale,
    minScaleX: Float = MinSuggestionScaleX,
): SuggestionTextFit {
    if (availableWidthPx <= 0f || measuredWidthPx <= 0f || measuredWidthPx <= availableWidthPx) {
        return SuggestionTextFit(1f, 1f)
    }
    val needed = availableWidthPx / measuredWidthPx
    val fontScale = needed.coerceAtLeast(minFontScale)
    val scaleX = (needed / fontScale).coerceIn(minScaleX, 1f)
    return SuggestionTextFit(fontScale, scaleX)
}

/**
 * The conversion-IME candidate row: pinyin, zhuyin, kana, jyutping, cangjie and
 * stroke candidates, scrolling horizontally.
 *
 * Deliberately unlike [LatinSuggestionChips] in three ways, all for the same
 * reason — here the candidate list *is* the input method, not a hint about it:
 *  - it scrolls, because the composers rank a dozen candidates and a reading
 *    like `xian` genuinely has that many distinct characters behind it;
 *  - chips are sized to their text and packed from the left, so the best
 *    candidate is always in the same place. Splitting the bar evenly would put
 *    a two-candidate list in two different spots than a three-candidate one;
 *  - the primary is never moved to the centre. That convention reads as
 *    "the middle one is the safe default", which is wrong when the space bar
 *    commits index 0 and the eye has to track which chip that is.
 *
 * No shift re-casing either: Han characters and kana have no case.
 */
@Composable
private fun RowScope.CandidateStrip(
    candidates: List<String>,
    enabled: Boolean,
    alpha: () -> Float,
    /** Same multiplier the Latin strip uses; the two are one row in two modes. */
    textScale: Float,
    /** The hotkey badges, or null when no physical keyboard is asking for them. */
    hints: HintPlan? = null,
    onCandidate: (String, Int) -> Unit,
    onExpand: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { this.alpha = alpha() },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(candidates, key = { index, text -> "$index $text" }) { index, suggestion ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(min = CandidateChipMinWidth)
                    .fillMaxHeight()
                    // By position, not text: the composer works out how much of
                    // the buffer to eat from where the chip sat.
                    .clickable(enabled = enabled) { onCandidate(suggestion, index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = suggestion,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = CandidateFontSize * textScale,
                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val hint = hints?.label(HintSurface.SUGGESTION, index)
                if (hint != null) {
                    HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
    // Outside the scrolling row on purpose, so the way to see the rest of the
    // candidates cannot itself scroll off the end of the candidates.
    IconButton(onClick = onExpand, enabled = enabled) {
        Icon(
            Icons.Outlined.ArrowDropDown,
            contentDescription = stringResource(R.string.ime_candidates_more_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The candidate strip's overflow: every candidate the composer ranked, wrapped
 * over as many rows as it takes.
 *
 * Covers the keys while it is open, which is what Sogou, Baidu and QQ all do —
 * at this point you are choosing a character, not typing one. A [FlowRow] rather
 * than a grid because candidates run from one glyph to four and fixed columns
 * would leave ragged gaps; not lazy, because a hundred short chips is nothing.
 */
@Composable
private fun CandidateGridPanel(
    state: KeyboardUiState,
    onCandidate: (String, Int) -> Unit,
) {
    val textScale = state.settings.suggestionStrip.textScale
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyRowsHeight(state))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            state.expandedCandidates.forEachIndexed { index, candidate ->
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .widthIn(min = CandidateChipMinWidth)
                        .height(CandidateGridRowHeight)
                        .clickable { onCandidate(candidate, index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = candidate,
                        modifier = Modifier.padding(horizontal = 10.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = CandidateFontSize * textScale,
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Row height in the expanded grid — a comfortable tap target for one glyph. */
private val CandidateGridRowHeight = 44.dp

/** Tap-target floor for a candidate chip; single Hanzi would be narrower. */
private val CandidateChipMinWidth = 44.dp

/** Han and kana need more size than Latin to stay legible at strip height. */
private val CandidateFontSize = 20.sp

/**
 * The recently-copied paste chip shown on the suggestion strip: an accent pill
 * that pastes the last copied text on tap, with a trailing dismiss button. Styled
 * to match [SmartSuggestionChip] so the two chips read as one family.
 */
@Composable
private fun ClipboardSuggestionChip(
    clip: ClipItem,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    /** Set when the chip offers a code out of [clip] rather than all of it. */
    otp: ClipEntity? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    // The fragment chip is an outline, not a solid — same language as the
    // clipboard panel's own fragment chips, and unmistakably not the ordinary
    // "paste what you copied" pill.
    val fill = if (otp != null) Color.Transparent else tint.copy(alpha = if (kb.dark) 0.20f else 0.11f)

    val bitmap by produceState<ImageBitmap?>(initialValue = null, clip.imagePath) {
        if (clip.kind == ClipKind.IMAGE && clip.imagePath != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(clip.imagePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 64 &&
                        bounds.outHeight / (sample * 2) >= 64
                    ) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(clip.imagePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }.getOrNull()
            }
        } else {
            value = null
        }
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .then(
                if (otp != null) {
                    Modifier.dashedOutline(tint.copy(alpha = 0.6f), 50.dp)
                } else {
                    Modifier.border(1.dp, tint.copy(alpha = 0.32f), RoundedCornerShape(50))
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = stretch)
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onPaste()
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val clipBitmap = bitmap
            if (clip.kind == ClipKind.IMAGE && clipBitmap != null) {
                Image(
                    bitmap = clipBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.22f)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            otp != null -> Icons.Outlined.Password
                            clip.kind == ClipKind.IMAGE -> Icons.Outlined.Image
                            else -> Icons.Outlined.ContentPaste
                        },
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            if (otp != null) {
                // "CODE" earns its space here: without it the chip is a bare
                // number, and the user has no way to tell it was lifted out of
                // the message rather than being the whole copy.
                Text(
                    text = stringResource(R.string.ime_clip_chip_code_badge),
                    color = tint.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            val textToDisplay = when {
                otp != null -> otp.value
                clip.kind == ClipKind.IMAGE -> stringResource(
                    // "System UI" is the package label Android gives a
                    // screenshot, not wording anyone reads.
                    if (clip.sourceApp == "System UI") {
                        R.string.ime_clip_chip_screenshot
                    } else {
                        R.string.ime_clip_chip_image
                    },
                )
                clip.text.isNotBlank() -> clip.text
                else -> stringResource(R.string.ime_clip_chip_item)
            }
            Text(
                text = textToDisplay,
                // suggestionText, not keyText: this chip sits on the strip,
                // over the board. A theme whose keys invert the board — dark
                // letters on cream caps over a near-black board — draws the
                // key colour invisible here.
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = if (otp != null) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable {
                    feedback()
                    onDismiss()
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_clip_chip_dismiss_desc),
                tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * The one-time-code chip: a code found in a just-arrived notification, one tap
 * from typed. Dashed outline in the same language as the clipboard fragment
 * chips — this is a piece lifted out of something, not typed text — plus the
 * posting app's name, so the user can tell whose code they are about to trust
 * before they trust it.
 */
@Composable
private fun OtpSuggestionChip(
    otp: NotificationOtp,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(50))
            .dashedOutline(tint.copy(alpha = 0.6f), 50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = stretch)
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onAccept()
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Password,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = stringResource(R.string.ime_clip_chip_code_badge),
                color = tint.copy(alpha = 0.85f),
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = otp.code,
                // On the board, so the board's text colour — see the same
                // note in [ClipboardSuggestionChip].
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = otp.sourceApp,
                color = kb.suggestionText.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable {
                    feedback()
                    onDismiss()
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_otp_chip_dismiss_desc),
                tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * The snippet chip: a snippet whose trigger matched and which was marked "ask
 * first", one tap from inserted.
 *
 * It shows the text it would put in rather than the snippet's name. The name is
 * how the panel lists it, and for a pack of replies it is a category ("Thank-you
 * letter"); what matters here is the sentence about to be typed for you. Solid
 * fill in the [SmartSuggestionChip] language — this is text the user set up,
 * not a piece lifted out of somewhere else.
 */
@Composable
private fun SnippetOfferChip(
    offer: SnippetOffer,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    // One line, so the newlines in a signature or a letter have to become
    // spaces; a snippet with any in it would otherwise show only its first.
    val preview = remember(offer.text) { offer.text.replace(WHITESPACE_RUN, " ").trim() }
    // Accent-tinted like the smart chip — it is an offer, not a word — but the
    // outline follows the theme's chip shape.
    val chipShape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(chipShape)
            .background(tint.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, tint.copy(alpha = 0.32f), chipShape)
            .clickable {
                feedback()
                onAccept()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = stretch)
                .fillMaxHeight()
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    toolIcon(ToolbarTool.SNIPPETS),
                    contentDescription = stringResource(R.string.ime_snippet_offer_desc, offer.label),
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = preview,
                // Strip text, not key text: the chip sits on the board, and a
                // theme may ink its keys against it.
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * The add-word chip: a word nothing recognises, asking to be let into the
 * personal dictionary.
 *
 * Same accent language as the snippet offer above, with the word shown as the
 * user typed it — the whole question is whether that spelling is right, so
 * nothing here tidies it up. Two targets: the word itself accepts, the ✕
 * declines and stops the keyboard asking about that word again.
 */
@Composable
private fun LearnWordChip(
    word: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    val chipShape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(chipShape)
            .background(tint.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, tint.copy(alpha = 0.32f), chipShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = stretch)
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onAccept()
                }
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.LibraryAdd,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = stringResource(R.string.ime_learn_word_offer, word),
                // Strip text, not key text: the chip sits on the board, and a
                // theme may ink its keys against it.
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 7.dp)
                .background(tint.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onDecline()
                }
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_learn_word_never_desc, word),
                tint = tint.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Any run of spaces, tabs or newlines, folded to one space for a preview. */
private val WHITESPACE_RUN = Regex("""\s+""")

/**
 * Fallback content for the dedicated emoji row before any usage exists.
 *
 * Longer than the row fits on purpose: the row scrolls by default
 * ([EmojiSettings.barScrollable]), so a starter set the length of one screen
 * would leave nothing to swipe to and make a fresh install look like scrolling
 * was broken. Written as neutral bases — [visibleEmojiBarItems] applies the
 * default skin tone to this list, and only to this list, because history
 * entries are already exact sequences.
 */
private val DEFAULT_BAR_EMOJIS = listOf(
    "😂", "❤️", "🤣", "👍", "😭", "🙏", "😍", "🥰", "😊", "🎉", "😅", "🔥",
    "🥺", "😁", "💯", "✨", "🙌", "👏", "🤔", "😎", "😢", "🤗", "💪", "👌",
)

/**
 * Height of the dedicated emoji row. The emoji panel absorbs it while
 * open (the row hides there), so the keyboard's total height never
 * changes when switching between keys and the emoji panel.
 */
private val EmojiBarHeight = 40.dp

/**
 * The dedicated emoji row (Gboard style): favourites and/or most-used
 * emojis one tap from any screen, with a launcher into the full panel.
 * Used as its own row (ALWAYS) or swapped into the strip (BUTTON).
 */
@Composable
private fun EmojiBarStrip(
    state: KeyboardUiState,
    onEmoji: (String) -> Unit,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Favourites already lead the recents/frequents lists (EmojiUsage pins
    // them), so each content mode is a straight pick. History entries are
    // exact sequences already; only the starter set is written as neutral
    // bases, so it is the one that needs the skin tone applied.
    val emojis = visibleEmojiBarItems(state)
    val scrollable = state.settings.emoji.barScrollable
    val count = state.settings.emoji.barCount.coerceIn(EmojiBarCountRange)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(EmojiBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenPanel, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.EmojiEmotions,
                contentDescription = stringResource(R.string.ime_emoji_panel_open_desc),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // One slot width drives both layouts: `count` emoji span the leftover
        // width either way, and the glyphs shrink into their slot, so a
        // tighter setting packs more in instead of clipping or overflowing.
        // Scrolling only decides whether the emoji past those slots are
        // reachable — off (the default), the row is a fixed set of taps and a
        // sideways swipe can't slide it out from under a finger.
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val slot = maxWidth / count
            val fontScale = LocalDensity.current.fontScale
            // Cap at the historical 24sp; below that the glyph is sized in dp
            // (slot-relative) and converted back, so a large system font scale
            // can't push emoji wider than their slot.
            val fontSize = minOf(24f, slot.value * 0.74f / fontScale).sp
            val hints = armedHintPlan(state)
            if (scrollable) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    itemsIndexed(emojis) { index, emoji ->
                        EmojiBarCell(
                            emoji, slot, fontSize, onEmoji,
                            hint = hints?.label(HintSurface.EMOJI_ROW, index),
                        )
                    }
                }
            } else {
                // SpaceEvenly spreads the cells when there are fewer emoji
                // than slots, so a short favourites list doesn't huddle left.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    emojis.take(count).forEachIndexed { index, emoji ->
                        EmojiBarCell(
                            emoji, slot, fontSize, onEmoji,
                            hint = hints?.label(HintSurface.EMOJI_ROW, index),
                        )
                    }
                }
            }
        }
    }
}

/** One tappable emoji slot of [EmojiBarStrip], sized by the row's packing. */
@Composable
private fun EmojiBarCell(
    emoji: String,
    width: Dp,
    fontSize: TextUnit,
    onEmoji: (String) -> Unit,
    hint: String? = null,
) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            // Drawn as this font spells it; onEmoji still commits the standard form.
            text = LocalEmojiShaper.current.shape(emoji),
            modifier = Modifier
                .width(width)
                // Lifted so the badge sits under the emoji rather than over it;
                // the cell's own size is unchanged.
                .offset(y = if (hint != null) -(HintBadgeHeight / 2) else 0.dp)
                .clickable { onEmoji(emoji) }
                .padding(vertical = 6.dp),
            fontSize = fontSize,
            fontFamily = emojiFamilyFor(emoji),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (hint != null) {
            HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Height of the dedicated symbol row (chips are text, not emoji).
 *
 * The shipped value, and still the fallback for the Fancy Text strip, which
 * mirrors this row but is not the same row. The symbol row itself reads
 * [RowSettings.symbolRowHeightDp] so it can be matched to the number row.
 */
internal val SymbolRowHeight = 40.dp

/**
 * What to call one symbol set. A shipped set that still carries its shipped
 * name is drawn from resources; a set the user made or renamed keeps the name
 * the user typed.
 */
@Composable
private fun symbolSetName(set: SymbolSet): String =
    BuiltInSymbolSets.nameRes(set)?.let { stringResource(it) } ?: set.name

/**
 * Properties for the strip pickers' [DropdownMenu]s.
 *
 * A menu is its own window, and Material's default properties make that window
 * focusable. Raised from an app that is only a window, that is harmless; raised
 * from the IME it takes input focus away from the editor the keyboard is typing
 * into. The app sees its field lose focus and hides the keyboard, then shows it
 * again once focus comes back — and the restart tears the menu's composition
 * down mid-flight, so the picker either flickers shut or comes back with the
 * keyboard behind it gone. How visible that is depends on how the target window
 * reacts to losing focus, which is why the row pickers look fine in most apps
 * and misbehave in dialogs and in this app's own settings search.
 *
 * Every other popup the keyboard raises is non-focusable for this reason (see
 * [LanguagePickerPopup], `GrammarDialectPicker`); these two menus were the
 * outliers. Tapping outside still dismisses: Compose asks for outside touches
 * whether or not the window is focusable.
 */
private val MenuPopupProperties = PopupProperties(focusable = false)

/** Pins a popup to the keyboard window's own corner, whatever its anchor. */
private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * Swallows the tap that closes an open strip picker.
 *
 * A window that takes no focus also takes no touch grab, so the tap that
 * dismisses the menu carries on to whatever is underneath — and under these
 * strips is the key grid, which would type a character on the way out. This
 * covers the keyboard with an invisible window that takes that tap instead.
 * The menu's own window is added after this one and so sits above it.
 */
@Composable
private fun StripMenuScrim(onDismiss: () -> Unit) {
    Popup(
        popupPositionProvider = WindowOriginPositionProvider,
        properties = MenuPopupProperties,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // On press, not on tap: a dismissing finger that then drags
                // must not leave the menu open under it.
                .pointerInput(Unit) { detectTapGestures(onPress = { onDismiss() }) },
        )
    }
}

/**
 * The dedicated symbol row: one symbol set's characters and snippets a tap
 * away, with a picker chip on the left switching between the enabled sets
 * (or the sets the active keyboard mode prescribes).
 */
@Composable
private fun SymbolRowStrip(
    state: KeyboardUiState,
    onInsert: (String) -> Unit,
    onSetSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    // An edited built-in is stored as a custom set under the built-in's id;
    // resolveSymbolSets shadows the shipped one rather than listing both.
    val allSets = resolveSymbolSets(settings.customSymbolSets)
    val enabledSets = settings.symbolRowSetIds
        .mapNotNull { id -> allSets.firstOrNull { it.id == id } }
        .ifEmpty { BuiltInSymbolSets.sets }
    val active = activeSymbolSet(state)
    var pickerOpen by remember { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(settings.rows.symbolRowHeightDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only offer the picker when there is something to switch to.
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabledSets.size > 1) {
                        feedback()
                        pickerOpen = true
                    }
                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (enabledSets.size > 1) {
                    Text(
                        symbolSetName(active),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.ime_symbol_set_switch_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pickerOpen) StripMenuScrim(onDismiss = { pickerOpen = false })
            DropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
                properties = MenuPopupProperties,
            ) {
                for (set in enabledSets) {
                    DropdownMenuItem(
                        text = { Text(symbolSetName(set)) },
                        trailingIcon = if (set.id == active.id) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            pickerOpen = false
                            onSetSelect(set.id)
                        },
                    )
                }
            }
        }
        val hints = armedHintPlan(state)
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            itemsIndexed(active.chars) { index, symbol ->
                val hint = hints?.label(HintSurface.SYMBOL_ROW, index)
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = symbolChipLabel(symbol),
                        modifier = Modifier
                            // Lifted so the badge sits under the character
                            // instead of across it; the cell keeps its size.
                            .offset(y = if (hint != null) -(HintBadgeHeight / 2) else 0.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onInsert(symbol) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (hint != null) {
                        HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/** Height of the Fancy Text style strip (mirrors [SymbolRowHeight]). */
internal val FancyRowHeight = 40.dp

/**
 * The Fancy Text style in force at draw time, or null outside the fancy
 * layout — the render-side twin of the service's own resolver, and the gate
 * every fancy branch in this file checks.
 */
internal fun fancyStyleFor(state: KeyboardUiState): FancyStyle? =
    if (state.language.id == FancyStyles.LANG_ID) {
        FancyStyles.byId(
            state.activeFancyStyleId ?: state.settings.layoutBehavior.fancyStyleId,
        )
    } else {
        null
    }

/**
 * The style picker for Fancy Text: a strip over the keys, shown only while
 * the fancy layout is active, with each style's chip written in the style
 * itself (𝐁𝐨𝐥𝐝, 𝘐𝘵𝘢𝘭𝘪𝘤, 𝔉𝔯𝔞𝔨𝔱𝔲𝔯 …). Modeled on [SymbolRowStrip]: a
 * left-hand dropdown naming the current style in plain text, then the
 * scrollable WYSIWYG chips. Not a [BarRow] — that enum is serialized, and an
 * older build reading an unknown constant would crash; this row simply
 * appears with the layout, unordered and unconfigurable.
 */
@Composable
private fun FancyStyleStrip(
    state: KeyboardUiState,
    active: FancyStyle,
    onStyleSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    // Land on the active chip when the strip appears — with 22 styles the
    // selected one is otherwise likely off-screen to the right.
    //
    // Seeded into the list's initial index rather than scrolled to from a
    // LaunchedEffect. An effect runs after the first layout, so the strip drew
    // one frame from the top of the list and then jumped to the active style —
    // and it appears on the same frame the fancy layout does, which is exactly
    // when the eye is already on it.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = FancyStyles.all
            .indexOfFirst { it.id == active.id }
            .coerceAtLeast(0),
    )
    // The chips are Latin glyphs whatever the system locale; mirroring the
    // strip under RTL would put the list order at odds with the layout.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(FancyRowHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            feedback()
                            pickerOpen = true
                        }
                        .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        active.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = stringResource(R.string.ime_fancy_style_switch_desc),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pickerOpen) StripMenuScrim(onDismiss = { pickerOpen = false })
                DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                    properties = MenuPopupProperties,
                ) {
                    for (style in FancyStyles.all) {
                        DropdownMenuItem(
                            text = { Text(style.name) },
                            trailingIcon = if (style.id == active.id) {
                                {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                pickerOpen = false
                                onStyleSelect(style.id)
                            },
                        )
                    }
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lazyRowItems(FancyStyles.all) { style ->
                    val selected = style.id == active.id
                    val kb = LocalKbTheme.current
                    val chipShape = kb.chipShape()
                    Text(
                        text = style.sample,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clip(chipShape)
                            .background(if (selected) kb.chipActive else Color.Transparent)
                            .then(
                                if (selected) Modifier.chipBorder(kb, chipShape) else Modifier,
                            )
                            .clickable { onStyleSelect(style.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            // The samples are astral soup to TalkBack; speak
                            // the plain name instead.
                            .semantics { contentDescription = style.name },
                        fontSize = 14.sp,
                        color = if (selected) kb.chipActiveText else kb.suggestionText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The Modes panel: pick which keyboard mode is active. "Automatic" follows
 * the per-app and per-field bindings; picking a mode by hand overrides them
 * until the user switches to another app. Modes are created and edited in
 * the settings app (long-press the tool).
 */
@Composable
private fun ModesPanel(
    state: KeyboardUiState,
    onModeSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val height = keyRowsHeight(state)
    val modes = state.settings.keyboardModes
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (modes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.ime_modes_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ToolPanelChip(
                    stringResource(R.string.ime_modes_settings_desc),
                    selected = true,
                    onClick = onOpenSettings,
                )
            }
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ime_modes_manual_note),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ToolCircle(
                slot = IconSlots.forTool(ToolbarTool.SETTINGS),
                description = stringResource(R.string.ime_modes_settings_desc),
                active = false,
                onClick = onOpenSettings,
            )
        }
        // Index 0 is the leading "Automatic" row, so a mode sits at its own
        // position plus one — the same offset the activate lambda undoes.
        PanelFocusTarget(
            panel = PanelMode.MODES,
            count = modes.size + 1,
            columns = 1,
            onActivate = { index ->
                if (index == 0) onModeSelect(null) else modes.getOrNull(index - 1)?.let { onModeSelect(it.id) }
            },
        )
        val focused = state.focusedIndex()
        val listState = rememberLazyListState()
        ScrollFocusIntoView(focused) { listState.animateScrollToItem(it) }
        // Hoisted out of the list: the summaries are worded per mode, and a
        // LazyColumn's content block is not a composable scope.
        val resources = LocalContext.current.resources
        val autoTitle = stringResource(CommonR.string.common_auto)
        val autoSubtitle = stringResource(R.string.ime_modes_auto_subtitle)
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            item {
                ModeRow(
                    title = autoTitle,
                    subtitle = autoSubtitle,
                    icon = Icons.Outlined.AutoAwesome,
                    selected = state.activeModeId == null,
                    focused = focused == 0,
                ) { onModeSelect(null) }
            }
            itemsIndexed(modes) { index, mode ->
                ModeRow(
                    title = mode.name,
                    subtitle = modeSummary(resources, mode),
                    icon = ModeIcons.icon(mode.icon),
                    selected = state.activeModeId == mode.id,
                    focused = focused == index + 1,
                ) { onModeSelect(mode.id) }
            }
        }
    }
}

/**
 * One-line recap of what a mode changes and when it kicks in.
 *
 * Each part is its own resource, written the way it reads at the start of the
 * line: the parts are joined, so no part may be re-cased afterwards — the
 * first letter of a translated word is not the compiler's to change.
 */
private fun modeSummary(resources: Resources, mode: KeyboardMode): String {
    val parts = mutableListOf<String>()
    mode.emojiBarMode?.let {
        parts += resources.getString(
            when (it) {
                EmojiBarMode.OFF -> R.string.ime_mode_summary_emoji_row_off
                EmojiBarMode.BUTTON -> R.string.ime_mode_summary_emoji_button
                EmojiBarMode.ALWAYS -> R.string.ime_mode_summary_emoji_row
            },
        )
    }
    mode.toolbarTools?.let {
        val plural = if (mode.toolbarToolsAppend) {
            R.plurals.ime_mode_summary_pinned_tools_added
        } else {
            R.plurals.ime_mode_summary_pinned_tools
        }
        parts += resources.getQuantityString(plural, it.size, it.size)
    }
    mode.symbolRowEnabled?.let {
        parts += resources.getString(
            if (it) R.string.ime_mode_summary_symbol_row else R.string.ime_mode_summary_symbol_row_off,
        )
    }
    if (mode.apps.isNotEmpty()) {
        parts += resources.getQuantityString(
            R.plurals.ime_mode_summary_apps, mode.apps.size, mode.apps.size,
        )
    }
    if (mode.fieldKinds.isNotEmpty()) {
        // FieldKind has no name of its own yet, so the enum names stand in.
        parts += resources.getString(
            R.string.ime_mode_summary_fields,
            mode.fieldKinds.joinToString(", ") { it.name.lowercase() },
        )
    }
    return if (parts.isEmpty()) {
        resources.getString(R.string.ime_mode_summary_none)
    } else {
        parts.joinToString(" · ")
    }
}

@Composable
private fun ModeRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Fill and outline both, since the accent alone already means
            // "this is the active mode" on the icon and the title weight.
            .focusRing(focused, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 14.dp)
                .size(22.dp),
            // The active mode's icon carries the accent; the rest stay quiet
            // so the list reads as names first, icons second.
            tint = if (selected) kb.accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = stringResource(R.string.ime_modes_active_desc),
                modifier = Modifier.size(20.dp),
                tint = kb.accent,
            )
        }
    }
}

// ---- customizable toolbar & toolbox ----

internal fun toolIcon(tool: ToolbarTool): ImageVector = IconDefaults.forTool(tool)

/**
 * What to call one tool. Every surface that names a tool reads this: the
 * toolbar, the toolbox, the hardware shortcut legend and the settings screens.
 * Resolve it where the name is drawn.
 */
@StringRes
internal fun toolLabelRes(tool: ToolbarTool): Int = when (tool) {
    ToolbarTool.EMOJI -> R.string.ime_tool_emoji
    ToolbarTool.CLIPBOARD -> R.string.ime_tool_clipboard
    ToolbarTool.SNIPPETS -> R.string.ime_tool_snippets
    ToolbarTool.TEXT_EDIT -> R.string.ime_tool_text_edit
    ToolbarTool.ONE_HANDED -> R.string.ime_tool_one_handed
    ToolbarTool.SPLIT -> R.string.ime_tool_split
    ToolbarTool.FLOATING -> R.string.ime_tool_floating
    ToolbarTool.RESIZE -> R.string.ime_tool_resize
    ToolbarTool.SETTINGS -> R.string.ime_tool_settings
    ToolbarTool.FLASHLIGHT -> R.string.ime_tool_flashlight
    ToolbarTool.COMPASS -> R.string.ime_tool_compass
    ToolbarTool.LEVEL -> R.string.ime_tool_level
    ToolbarTool.UNDO -> R.string.ime_tool_undo
    ToolbarTool.REDO -> R.string.ime_tool_redo
    ToolbarTool.MOON_PHASE -> R.string.ime_tool_moon_phase
    ToolbarTool.WEATHER -> R.string.ime_tool_weather
    ToolbarTool.CALENDAR -> R.string.ime_tool_calendar
    ToolbarTool.INCOGNITO -> R.string.ime_tool_incognito
    ToolbarTool.POWER_SAVING -> R.string.ime_tool_power_saving
    ToolbarTool.THEMES -> R.string.ime_tool_themes
    ToolbarTool.AUTOCORRECT -> R.string.ime_tool_autocorrect
    ToolbarTool.SOUND_HAPTICS -> R.string.ime_tool_sound_haptics
    ToolbarTool.NUMPAD -> R.string.ime_tool_numpad
    ToolbarTool.HANDWRITING -> R.string.ime_tool_handwriting
    ToolbarTool.CAMERA -> R.string.ime_tool_camera
    ToolbarTool.DICTIONARY -> R.string.ime_tool_dictionary
    ToolbarTool.TRANSLATE -> R.string.ime_tool_translate
    ToolbarTool.GIF -> R.string.ime_tool_gif
    ToolbarTool.STICKER -> R.string.ime_tool_sticker
    ToolbarTool.WEB_SEARCH -> R.string.ime_tool_web_search
    ToolbarTool.IMAGE_SEARCH -> R.string.ime_tool_image_search
    ToolbarTool.OCR -> R.string.ime_tool_ocr
    ToolbarTool.QR_SCAN -> R.string.ime_tool_qr_scan
    ToolbarTool.DOC_SCAN -> R.string.ime_tool_doc_scan
    ToolbarTool.VOICE -> R.string.ime_tool_voice
    ToolbarTool.GRAMMAR -> R.string.ime_tool_grammar
    ToolbarTool.WIKIPEDIA -> R.string.ime_tool_wikipedia
    ToolbarTool.SYMBOLS -> R.string.ime_tool_symbols
    ToolbarTool.CALCULATOR -> R.string.ime_tool_calculator
    ToolbarTool.UNIT_CONVERT -> R.string.ime_tool_unit_convert
    ToolbarTool.CURRENCY -> R.string.ime_tool_currency
    ToolbarTool.QR_GEN -> R.string.ime_tool_qr_gen
    ToolbarTool.PASSWORD_GEN -> R.string.ime_tool_password_gen
    ToolbarTool.TYPING_TEST -> R.string.ime_tool_typing_test
    ToolbarTool.MEDIA_CONTROL -> R.string.ime_tool_media_control
    ToolbarTool.PLUGINS -> R.string.ime_tool_plugins
    ToolbarTool.APP_LAUNCHER -> R.string.ime_tool_app_launcher
    ToolbarTool.AI -> R.string.ime_tool_ai
    ToolbarTool.MODES -> R.string.ime_tool_modes
    ToolbarTool.FANCY -> R.string.ime_tool_fancy
    ToolbarTool.CURSOR_LEFT -> R.string.ime_tool_cursor_left
    ToolbarTool.CURSOR_RIGHT -> R.string.ime_tool_cursor_right
    ToolbarTool.CURSOR_WORD_LEFT -> R.string.ime_tool_cursor_word_left
    ToolbarTool.CURSOR_WORD_RIGHT -> R.string.ime_tool_cursor_word_right
    ToolbarTool.CURSOR_UP -> R.string.ime_tool_cursor_up
    ToolbarTool.CURSOR_DOWN -> R.string.ime_tool_cursor_down
    ToolbarTool.CURSOR_HOME -> R.string.ime_tool_cursor_home
    ToolbarTool.CURSOR_END -> R.string.ime_tool_cursor_end
    ToolbarTool.HIDE_KEYBOARD -> R.string.ime_tool_hide_keyboard
    ToolbarTool.PAGE_UP -> R.string.ime_tool_page_up
    ToolbarTool.PAGE_DOWN -> R.string.ime_tool_page_down
    ToolbarTool.SELECT_WORD -> R.string.ime_tool_select_word
    ToolbarTool.SELECT_LINE -> R.string.ime_tool_select_line
    ToolbarTool.SELECT_MODE -> R.string.ime_tool_select_mode
}

/** [toolLabelRes], worded for a caller that is already drawing. */
@Composable
internal fun toolLabel(tool: ToolbarTool): String = stringResource(toolLabelRes(tool))

private fun toolActive(tool: ToolbarTool, state: KeyboardUiState): Boolean = when (tool) {
    ToolbarTool.EMOJI -> state.panel == PanelMode.EMOJI
    ToolbarTool.CLIPBOARD -> state.panel == PanelMode.CLIPBOARD
    ToolbarTool.SNIPPETS -> state.panel == PanelMode.SNIPPETS
    ToolbarTool.TEXT_EDIT -> state.panel == PanelMode.TEXT_EDIT
    ToolbarTool.ONE_HANDED -> state.settings.oneHandedMode != OneHandedMode.OFF
    ToolbarTool.SPLIT -> state.settings.splitKeyboard
    ToolbarTool.FLOATING -> state.settings.floatingKeyboard
    ToolbarTool.RESIZE -> state.resize
    ToolbarTool.SETTINGS -> false
    ToolbarTool.FLASHLIGHT -> state.torchOn
    ToolbarTool.COMPASS -> state.panel == PanelMode.COMPASS
    ToolbarTool.LEVEL -> state.panel == PanelMode.LEVEL
    ToolbarTool.UNDO -> false
    ToolbarTool.REDO -> false
    ToolbarTool.MOON_PHASE -> state.panel == PanelMode.MOON_PHASE
    ToolbarTool.WEATHER -> state.panel == PanelMode.WEATHER
    ToolbarTool.CALENDAR -> state.panel == PanelMode.CALENDAR
    ToolbarTool.INCOGNITO -> state.incognitoOn
    ToolbarTool.POWER_SAVING -> state.powerSavingOn
    ToolbarTool.THEMES -> state.panel == PanelMode.THEMES
    ToolbarTool.AUTOCORRECT -> state.settings.autocorrect
    ToolbarTool.SOUND_HAPTICS -> state.panel == PanelMode.SOUND_HAPTICS
    ToolbarTool.NUMPAD -> state.panel == PanelMode.NUMPAD
    ToolbarTool.HANDWRITING -> state.panel == PanelMode.HANDWRITING
    ToolbarTool.CAMERA -> state.panel == PanelMode.CAMERA
    ToolbarTool.DICTIONARY -> state.panel == PanelMode.DICTIONARY
    ToolbarTool.TRANSLATE -> state.panel == PanelMode.TRANSLATE
    ToolbarTool.GIF -> state.panel == PanelMode.GIF
    ToolbarTool.STICKER -> state.panel == PanelMode.STICKER
    ToolbarTool.WEB_SEARCH -> state.panel == PanelMode.WEB_SEARCH
    ToolbarTool.IMAGE_SEARCH -> state.panel == PanelMode.IMAGE_SEARCH
    ToolbarTool.OCR -> state.panel == PanelMode.OCR
    ToolbarTool.QR_SCAN -> state.panel == PanelMode.QR_SCAN
    ToolbarTool.DOC_SCAN -> false
    ToolbarTool.VOICE -> state.panel == PanelMode.VOICE || state.voice.strip || state.voice.bar
    ToolbarTool.GRAMMAR -> state.panel == PanelMode.GRAMMAR
    ToolbarTool.WIKIPEDIA -> state.panel == PanelMode.WIKIPEDIA
    ToolbarTool.SYMBOLS -> state.panel == PanelMode.SYMBOLS
    ToolbarTool.CALCULATOR -> state.panel == PanelMode.CALCULATOR
    ToolbarTool.UNIT_CONVERT -> state.panel == PanelMode.UNIT_CONVERT
    ToolbarTool.CURRENCY -> state.panel == PanelMode.CURRENCY
    ToolbarTool.QR_GEN -> state.panel == PanelMode.QR_GEN
    ToolbarTool.PASSWORD_GEN -> state.panel == PanelMode.PASSWORD_GEN
    ToolbarTool.TYPING_TEST -> state.panel == PanelMode.TYPING_TEST
    ToolbarTool.MEDIA_CONTROL -> state.panel == PanelMode.MEDIA_CONTROL
    ToolbarTool.PLUGINS -> state.panel == PanelMode.PLUGINS
    ToolbarTool.APP_LAUNCHER -> state.panel == PanelMode.APP_LAUNCHER
    ToolbarTool.AI -> state.panel == PanelMode.AI
    ToolbarTool.MODES -> state.panel == PanelMode.MODES || state.activeModeId != null
    // Lit while the fancy layout is the one typing, however it got there:
    // the tool and the 🌐 key reach the same place.
    ToolbarTool.FANCY -> state.language.id == FancyStyles.LANG_ID
    // Lit while a caret move would extend the selection, however that was
    // asked for: this tool, a hold on it, or the panel's own Select key. The
    // tool is the only indicator the mode has outside that panel, so it reads
    // the whole answer rather than its own flag.
    ToolbarTool.SELECT_MODE -> state.selectingText
    // Stateless one-shot moves, like undo/redo: nothing to stay lit for.
    ToolbarTool.CURSOR_LEFT, ToolbarTool.CURSOR_RIGHT,
    ToolbarTool.CURSOR_WORD_LEFT, ToolbarTool.CURSOR_WORD_RIGHT,
    ToolbarTool.CURSOR_UP, ToolbarTool.CURSOR_DOWN,
    ToolbarTool.CURSOR_HOME, ToolbarTool.CURSOR_END,
    ToolbarTool.PAGE_UP, ToolbarTool.PAGE_DOWN,
    ToolbarTool.SELECT_WORD, ToolbarTool.SELECT_LINE,
    // A one-shot action too — it hides the keyboard, nothing to keep lit.
    ToolbarTool.HIDE_KEYBOARD -> false
}

/**
 * Live state of a toolbar-customization drag. Bounds and positions are all
 * in window-root coordinates; the ghost is drawn relative to the keyboard
 * body's origin. Drops on the toolbar insert at the slot under the finger,
 * drops on the toolbox grid reorder the toolbox (unpinning first when the
 * tool came off the bar), drops anywhere else send a toolbar tool back to
 * the toolbox at its remembered rank.
 *
 * [barSlot] and [boxSlot] are the live drop preview: whichever is non-null
 * is where the tool would land right now, and the owning row/grid renders
 * a ghost stand-in there so the surrounding icons make room ahead of the
 * drop.
 */
private class ToolDragController {
    var dragging by mutableStateOf<ToolbarTool?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    /** Toolbar insertion slot under the finger, or null when off the bar. */
    var barSlot by mutableStateOf<Int?>(null)
        private set
    /** Toolbox grid slot under the finger, or null when off the grid. */
    var boxSlot by mutableStateOf<Int?>(null)
        private set
    private var fromToolbar = false
    var toolbarBounds: Rect? = null
    /**
     * Width, in px, that the toolbar last gave one of its buttons.
     *
     * Not the same as the theme's tool width. The bar spreads its buttons over
     * equal weighted cells, so a tool width wider than a cell is silently capped
     * at the cell — and the emoji shortcut the *strip* draws sits in no cell at
     * all, so it took the full width and the two copies of one icon came out
     * different sizes. At the top of the width slider that is a 60px jump on
     * every strip↔toolbar flip, right as the icon is meant to be sliding
     * smoothly between the two spots. Recorded from the toolbox launcher, which
     * is drawn whenever the bar is and shares the tools' cell.
     */
    var pinnedToolWidthPx: Int = 0
    /** Keyboard-body coordinates; the anchor for tool placement animations. */
    var bodyCoords: LayoutCoordinates? = null
    /**
     * Shared home of the emoji icon. It rides here rather than in [TopBar]
     * because the strip's copy and the toolbar's pinned copy are different
     * nodes: the handoff needs a holder that outlives both.
     */
    val emojiPlacement = SharedPlacement()
    /**
     * The stored pinned-tool list, in the order it is saved back (mirrored for
     * an RTL bar; see KeyboardBody). Every commit is a rewrite of this.
     */
    var currentTools: List<ToolbarTool> = emptyList()
    /**
     * The subset of [currentTools] the bar actually draws — a pinned tool the
     * user has disabled, or one this build does not ship, is stored but never
     * shown. Slots are counted against this list, because it is the one the
     * finger is pointing at; [end] then translates the slot back into a
     * position in the stored list, so hiding a tool never unpins it.
     */
    var visibleTools: List<ToolbarTool> = emptyList()
    var onCommit: (List<ToolbarTool>) -> Unit = {}
    /** Haptic tick when the drop target changes: slot to slot, or on/off the bar. */
    var onSnap: () -> Unit = {}
    /** Hold without dragging past the slop: open the tool's settings page. */
    var onOpenSettings: (ToolbarTool) -> Unit = {}

    /**
     * Runs the tool a hold was remapped to (see `holdActionFor`). Routed through
     * the controller alongside [onOpenSettings] rather than captured per tool:
     * both are the same gesture's outcome, and the tool cells are recomposed on
     * every frame of a drag, so a captured lambda would restart their pointer
     * handlers.
     */
    var onHoldAction: (ToolbarTool) -> Unit = {}

    /**
     * The Selection mode tool held down (true) and let go (false). On the
     * controller for the same reason [onHoldAction] is, and because the release
     * has to reach the service even when the finger travels far enough to turn
     * the gesture into a reorder.
     */
    var onSelectionHold: (Boolean) -> Unit = {}

    // Toolbox geometry and data, registered by ToolboxPanel while it is
    // open (a drag can only happen with the toolbox open). The viewport is
    // the visible panel box; content coords are the scrolling grid column,
    // so slot math follows the scroll position for free.
    var toolboxViewport: Rect? = null
    var toolboxContentCoords: LayoutCoordinates? = null
    var toolboxCellSize: Size = Size.Zero
    var toolboxColumns: Int = 1
    /**
     * Index the visible page starts at, when the toolbox is paginated. The
     * registered content coords belong to that one page, so its first cell is
     * slot [toolboxPageStart], not slot 0. Zero for the scrolling toolbox,
     * where the content is the whole grid.
     */
    var toolboxPageStart: Int = 0
    /**
     * How many slots that page holds — the drop is clamped to it so a drag
     * that wanders past the last pill on page 2 lands at the end of page 2
     * rather than at the end of the toolbox.
     */
    var toolboxPageLength: Int = Int.MAX_VALUE
    /** The tools the toolbox grid is showing, in toolbox order. */
    var toolboxTools: List<ToolbarTool> = emptyList()
    /** Complete ordering over every tool; reorders rewrite this. */
    var toolboxOrder: List<ToolbarTool> = emptyList()
    var onOrderCommit: (List<ToolbarTool>) -> Unit = {}

    fun start(tool: ToolbarTool, fromBar: Boolean, at: Offset) {
        dragging = tool
        fromToolbar = fromBar
        position = at
        barSlot = slotAt(at)
        boxSlot = if (barSlot == null) toolboxSlotAt(at) else null
    }

    fun move(to: Offset) {
        position = to
        val bar = slotAt(to)
        val box = if (bar == null) toolboxSlotAt(to) else null
        if (bar != barSlot || box != boxSlot) {
            barSlot = bar
            boxSlot = box
            onSnap()
        }
    }

    fun cancel() {
        dragging = null
        barSlot = null
        boxSlot = null
    }

    fun end() {
        val tool = dragging ?: return
        val bar = slotAt(position)
        val box = if (bar == null) toolboxSlotAt(position) else null
        cancel()
        if (bar != null) {
            onCommit(barWith(tool, bar))
        } else if (box != null) {
            // Dropped on the grid: place it at that spot in the toolbox
            // order — and off the bar first, when that's where it came from.
            if (fromToolbar) onCommit(currentTools - tool)
            onOrderCommit(orderWith(tool, box))
        } else if (fromToolbar && toolboxViewport != null) {
            // Off-bar drops unpin only while the toolbox is open (its
            // viewport is registered) — a reorder drag that wanders off the
            // bar with no toolbox in sight just snaps back.
            onCommit(currentTools - tool)
        }
    }

    /**
     * Insertion slot under [at], or null when off the toolbar. The bar's hit
     * box is inflated so a drop just above/below it still counts.
     */
    private fun slotAt(at: Offset): Int? {
        val tool = dragging ?: return null
        val bar = toolbarBounds?.inflate(30f) ?: return null
        if (!bar.contains(at)) return null
        val without = visibleTools - tool
        if (without.isEmpty()) return 0
        return (((at.x - bar.left) / bar.width) * (without.size + 1))
            .toInt()
            .coerceIn(0, without.size)
    }

    /**
     * The stored pinned list with [tool] moved to display slot [slot] of the
     * bar. The same trick [orderWith] uses for the toolbox: the slot indexes
     * the drawn tools, so the drawn tool it lands in front of is what pins the
     * position down in a stored list that may hold tools the bar never drew.
     */
    private fun barWith(tool: ToolbarTool, slot: Int): List<ToolbarTool> {
        val shown = visibleTools - tool
        val successor = shown.getOrNull(slot.coerceIn(0, shown.size))
        val bar = currentTools.toMutableList().apply { remove(tool) }
        val at = successor?.let { bar.indexOf(it) }?.takeIf { it >= 0 } ?: bar.size
        bar.add(at, tool)
        return bar
    }

    /**
     * Toolbox grid slot under [at], or null when off the visible panel. The
     * grid scrolls, so the position converts through the content column's
     * live coordinates rather than a captured rect.
     */
    private fun toolboxSlotAt(at: Offset): Int? {
        val tool = dragging ?: return null
        val viewport = toolboxViewport ?: return null
        if (!viewport.contains(at)) return null
        val coords = toolboxContentCoords?.takeIf { it.isAttached } ?: return null
        val cell = toolboxCellSize
        if (cell.width <= 0f || cell.height <= 0f) return null
        val origin = coords.positionInRoot()
        val columns = toolboxColumns.coerceAtLeast(1)
        val count = (toolboxTools - tool).size
        val col = ((at.x - origin.x) / cell.width).toInt().coerceIn(0, columns - 1)
        val row = ((at.y - origin.y) / cell.height).toInt().coerceAtLeast(0)
        // The cell is local to whatever registered the coords: the whole grid
        // when the toolbox scrolls, one page when it paginates.
        val local = (row * columns + col).coerceIn(0, toolboxPageLength)
        return (toolboxPageStart + local).coerceIn(0, count)
    }

    /** The full tool order with [tool] moved to display slot [slot] of the grid. */
    private fun orderWith(tool: ToolbarTool, slot: Int): List<ToolbarTool> {
        // The grid previews the drop with the dragged tool removed, so the
        // slot indexes that list; the displayed tool it lands in front of
        // anchors the position in the complete order.
        val displayed = toolboxTools - tool
        val successor = displayed.getOrNull(slot.coerceIn(0, displayed.size))
        val order = toolboxOrder.toMutableList().apply { remove(tool) }
        val at = successor?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        order.add(at, tool)
        return order
    }
}

/**
 * Distance a held tool has to travel before the gesture counts as a move
 * rather than a stationary hold. Generous on purpose: a hold meant to open
 * the tool's settings drifts a few pixels under any real thumb, and landing
 * in "moved the tool" because of that is the more annoying misfire.
 */
private val ToolDragSlop = 24.dp

/**
 * How often a held toolbar tool repeats its move, or null for the tools (and
 * the settings) where holding means something else.
 *
 * The interval is the text-editing tool's repeat speed: the same key held down
 * at the same rate, whether the user reached it through the panel or pinned it
 * to the bar.
 */
private fun holdRepeatMs(tool: ToolbarTool, state: KeyboardUiState): Long? =
    if (tool in HoldRepeatCursorTools && state.settings.textEditing.cursorToolsRepeatOnHold) {
        state.settings.textEditing.repeatMs.toLong()
    } else {
        null
    }

/**
 * The tool a press and hold on [tool] runs instead of opening its settings page,
 * or null when the hold keeps its original job.
 *
 * Toolbar only, and never for a tool whose hold already repeats — that hold is
 * spoken for, and the tools it covers are the caret moves, where a repeat is the
 * whole point. Reads through to a tool rather than a separate action vocabulary:
 * "holding this does what tapping that does" needs nothing new to be dispatched,
 * and every tool is already a target.
 */
private fun holdActionFor(
    tool: ToolbarTool,
    fromToolbar: Boolean,
    state: KeyboardUiState,
): ToolbarTool? = if (!fromToolbar || holdRepeatMs(tool, state) != null || holdArmsSelection(tool, fromToolbar, state)) {
    null
} else {
    state.settings.toolbarBehavior.holdActions[tool]
}

/**
 * Whether a press and hold on [tool] turns selection mode on for as long as the
 * finger stays down — true for the Selection mode tool on the toolbar, unless
 * the user has given that hold back.
 *
 * The third thing a stationary hold can be, beside repeating a caret move and
 * running a bound action, and it excludes the other two the same way they
 * exclude each other. Toolbar only: in the toolbox a hold reaches every tool's
 * settings page, and there is nothing to select behind an open panel anyway.
 */
private fun holdArmsSelection(
    tool: ToolbarTool,
    fromToolbar: Boolean,
    state: KeyboardUiState,
): Boolean = fromToolbar &&
    tool == ToolbarTool.SELECT_MODE &&
    state.settings.textEditing.selectionModeHold

/**
 * Wires long-press-drag onto a tool. Three outcomes from one gesture: a tap
 * runs [onTap]; a hold that never travels past [ToolDragSlop] opens the
 * tool's settings page — or runs [holdAction] when the user has given that
 * tool one; a hold that does travel picks the tool up and drops it wherever it
 * lands (reorder, pin, or unpin).
 *
 * [holdRepeatMs] replaces the middle one for the caret tools (see
 * [HoldRepeatCursorTools]): a stationary hold runs [onTap] again every
 * [holdRepeatMs] instead, so the cursor keeps moving for as long as the finger
 * is down. Null everywhere else, and on those tools the settings page is a hold
 * away in the toolbox instead.
 *
 * [holdArms] replaces it in the same way for the Selection mode tool: the hold
 * turns selection mode on while the finger is down and the release turns it off
 * (see [holdArmsSelection]). The release reaches the service on the drag path
 * too, or the mode would be left on by a reorder.
 *
 * Whatever the hold means, the pick-up itself waits for the finger to travel
 * past the slop. Only then does the cell wash out and the scope pill appear:
 * a hold that stays put is one of the three outcomes above, not a reorder, and
 * must not look like one while it is being held.
 *
 * The tap is dispatched from here rather than from a `clickable` on the tool
 * itself: a `clickable` sits deeper in the modifier chain, so it saw the
 * release first and fired its own click on top of every hold.
 */
@Composable
private fun DraggableTool(
    tool: ToolbarTool,
    fromToolbar: Boolean,
    enabled: Boolean,
    drag: ToolDragController,
    onTap: () -> Unit,
    holdRepeatMs: Long? = null,
    /** What a stationary hold runs, or null to open the tool's settings page. */
    holdAction: ToolbarTool? = null,
    /** Whether a stationary hold arms selection mode for as long as it lasts. */
    holdArms: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val feedback = LocalKeyPressFeedback.current
    val scope = rememberCoroutineScope()
    // Read through a holder rather than keying pointerInput on the lambda:
    // a fresh lambda every recomposition would restart the handler, and the
    // drop preview recomposes this row on every frame of a drag.
    val tapAction by rememberUpdatedState(onTap)
    content(
        Modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Keyed on the interval too: it comes from a setting, so the handler
            // has to be rebuilt when it changes. A Long changes far more rarely
            // than the lambda above, which is why that one goes through a holder.
            .pointerInput(enabled, tool, holdRepeatMs, holdArms) {
                if (!enabled) return@pointerInput
                // Raw press-and-hold, mirroring the key rows' handler, instead
                // of detectDragGesturesAfterLongPress: its long-press never
                // fired inside the IME window on device, so tools could not
                // be dragged at all. An external timer plus a plain event
                // loop is the pattern already proven by the repeat keys.
                val dragSlop = ToolDragSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Travel is measured in root coordinates, not from raw
                    // node-relative deltas: the row reflows around the drop
                    // preview while the finger is down, and a node that moves
                    // under a still finger reports deltas of its own.
                    val downRoot = origin + down.position
                    var rootPos = downRoot
                    var longPressed = false
                    var dragged = false
                    var released = false
                    var scrolled = false
                    // Whether selection mode is on because of *this* hold, so
                    // the release turns off exactly what the press turned on.
                    var armed = false
                    val timer = scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        // The buzz tells the user the long-press registered.
                        // The pick-up itself waits for travel (see the drag
                        // slop below), for every kind of hold: starting it here
                        // parked a drag ghost and the scope pill under a finger
                        // that was only holding the button, so a hold that
                        // ended as a bound action or the settings page first
                        // washed its own cell out and flashed a "changing the
                        // tool order" notice for a reorder nobody made (#31).
                        feedback()
                        longPressed = true
                        if (holdArms) {
                            // Selection mode for as long as the finger stays
                            // down. The bar can still be rearranged from this
                            // button: travel picks it up like any other.
                            armed = true
                            drag.onSelectionHold(true)
                        } else if (holdRepeatMs != null) {
                            // The first move lands with no delay of its own —
                            // the long-press timeout has already served as the
                            // repeat's start delay.
                            while (true) {
                                tapAction()
                                delay(holdRepeatMs)
                            }
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                // Swallow the release once the hold is ours so
                                // nothing downstream treats it as a tap too.
                                if (longPressed) change.consume()
                                break
                            }
                            rootPos = origin + change.position
                            val travel = (rootPos - downRoot).getDistance()
                            if (!longPressed) {
                                // Real drift before the hold registers is a
                                // scroll (toolbox grid) — hand the gesture back.
                                if (travel > viewConfiguration.touchSlop) {
                                    scrolled = true
                                    break
                                }
                            } else {
                                change.consume()
                                if (travel > dragSlop && !dragged) {
                                    dragged = true
                                    // Travel means this was a reorder all along,
                                    // so the tool picks itself up from here. A
                                    // repeating tool stops repeating; the moves
                                    // it already made stand — they are caret
                                    // moves, undone by moving the caret back.
                                    timer.cancel()
                                    // The mode ends here rather than on the
                                    // drop: from this point the gesture is
                                    // about the toolbar, not the text.
                                    if (armed) {
                                        armed = false
                                        drag.onSelectionHold(false)
                                    }
                                    drag.start(tool, fromToolbar, rootPos)
                                }
                                // Nothing to move until the finger has travelled:
                                // no drag has been started, and drag.move would
                                // tick the drop haptic anyway.
                                if (dragged) drag.move(rootPos)
                            }
                        }
                    } finally {
                        timer.cancel()
                        // Whatever ended the gesture — a release, a drop, the
                        // handler being torn down mid-hold — the mode this press
                        // armed ends with it. Nothing else here can be trusted
                        // to run: the branches below are exclusive.
                        if (armed) {
                            armed = false
                            drag.onSelectionHold(false)
                        }
                        when {
                            !longPressed -> {
                                drag.cancel()
                                if (released && !scrolled) tapAction()
                            }
                            dragged -> drag.end()
                            // A hold that armed selection mode has already done
                            // its work, and there is nothing to drop. Its
                            // settings page is a hold away in the toolbox.
                            holdArms -> drag.cancel()
                            // Same for a repeating hold. Its settings page is a
                            // hold away in the toolbox, which never repeats.
                            holdRepeatMs != null -> drag.cancel()
                            // A hold that never travelled past the slop is a
                            // distinct gesture: the action the user bound to it,
                            // or the tool's settings page when they bound none.
                            else -> {
                                drag.cancel()
                                val bound = holdAction
                                if (bound != null) drag.onHoldAction(bound) else drag.onOpenSettings(tool)
                            }
                        }
                    }
                }
            }
    )
}

/**
 * Slides this element to its new position when layout around it changes —
 * pinned tools shuffle smoothly instead of jumping when the toolbox chevron
 * shows up or a tool is (un)pinned. Fast, no-bounce spring: quick but not
 * sudden.
 *
 * The position is measured against [anchor] (the keyboard body) rather than
 * the immediate parent: the toolbar nests rows inside rows and its weighted
 * cells resize, so an icon's parent-relative position barely moves while
 * its on-screen position shifts a lot. Anchoring at the body captures the
 * whole motion in one spring, so nothing snaps at animation start. Falls
 * back to parent-relative when no anchor is available.
 *
 * [enabled] is the reduce-motion switch. It has to snap rather than skip the
 * modifier: opening a panel adds the back chevron, which shifts every pinned
 * icon, so a disabled slide still needs to track the new position or the
 * icons would sit at their old offsets.
 *
 * [inRow] marks a node that only ever slides along a single row — everything
 * on the toolbar. Those get the row rules [animateSharedPlacement] already
 * used for the emoji tool: a vertical move snaps (the row itself has moved,
 * see [ToolbarRowShiftPx]) and a horizontal one may travel as far as the bar
 * is wide (see [ToolbarSlideCapFraction]). The toolbox grid leaves it false —
 * its cells legitimately move between rows when the grid reflows, and its own
 * scroll and pagination are exactly the jumps [PlacementSlideCap] is there to
 * refuse.
 */
private fun Modifier.animatePlacement(
    enabled: Boolean = true,
    inRow: Boolean = false,
    anchor: () -> LayoutCoordinates? = { null },
): Modifier =
    composed {
        val scope = rememberCoroutineScope()
        val animatable = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
        // The displacement drawn right now, set synchronously from onPlaced.
        // Everything below hangs on this: onPlaced runs in the layout phase,
        // by which point a layout-phase offset for this frame has already
        // been decided, and the coroutine that starts the spring does not run
        // until the next one. So the icon was drawn once at its destination,
        // then jumped back to where it came from and slid in — a one-frame
        // flash on every icon, every time the bar changed. Draw runs after
        // layout in the same frame, so a draw-phase displacement written here
        // lands before the icon is ever painted undisplaced.
        var immediate by remember { mutableStateOf<IntOffset?>(null) }
        var lastTarget by remember { mutableStateOf<IntOffset?>(null) }
        // Whether [lastTarget] was measured against the anchor or against the
        // parent. The anchor is published from an onGloballyPositioned, which
        // runs AFTER this node's first placement, so the first target is always
        // parent-relative and every later one anchor-relative. Two different
        // spaces: subtracting one from the other invents a move nothing made.
        var lastAnchored by remember { mutableStateOf(false) }
        this
            .onPlaced { coords ->
                val anchorCoords = anchor()?.takeIf { it.isAttached }
                val target = (
                    anchorCoords?.localPositionOf(coords, Offset.Zero)
                        ?: coords.positionInParent()
                    ).round()
                val anchored = anchorCoords != null
                val previous = lastTarget.takeIf { lastAnchored == anchored }
                lastTarget = target
                lastAnchored = anchored
                // First placement settles in place: a fresh icon has nowhere to
                // have travelled from. So does the first one after the anchor
                // turned up, for the same reason — that target and the one
                // before it are in different spaces.
                //
                // This is what made a freshly opened toolbox slide its pills
                // around: parent-relative, every pill sits at ~(0,0) inside its
                // own cell, so the second pass handed each one a "move" equal to
                // its own slot in the grid. Column offsets and the lower rows
                // were past [PlacementSlideCap] and snapped, but the left column
                // of row 2 is exactly one pill-pitch down — inside the cap — so
                // that one pill, and only that one, slid in from the row above.
                if (previous == null || previous == target) return@onPlaced
                val delta = previous - target
                // A row-bound icon that moved vertically did not move: its row
                // did, and sliding after a row that has already arrived reads
                // as lag. Snap — and snap for every icon in the bar alike. This
                // was [animateSharedPlacement]'s rule alone, so opening the
                // emoji panel over an always-on emoji row (the row folds away,
                // the whole bar rises) left the emoji tool and the toolbox
                // standing still while the clipboard and settings icons drifted
                // in diagonally from where the old row had been.
                val rowShifted = inRow &&
                    (delta.x == 0 || kotlin.math.abs(delta.y) > ToolbarRowShiftPx)
                val jump = delta.toOffset().getDistance()
                // Deadband, and the big-jump escape. The toolbar's cells are
                // weighted, so their widths land on fractions that round
                // differently between passes; nothing travels a single pixel
                // on purpose here, so a move that small is rounding, not
                // motion. A move past the cap is a layout jump (a scroll, a
                // toolbox page flip) and animating it reads as lag.
                val cap = placementCap(inRow, anchorCoords)
                if (!enabled || rowShifted || jump < 2f || jump > cap) {
                    immediate = null
                    scope.launch { animatable.snapTo(IntOffset.Zero) }
                    return@onPlaced
                }
                // Carry any displacement still in flight, so a change that
                // lands mid-slide continues from where the icon actually is
                // rather than restarting from the new delta alone.
                val start = delta + (immediate ?: animatable.value)
                immediate = start
                scope.launch {
                    animatable.snapTo(start)
                    immediate = null
                    animatable.animateTo(IntOffset.Zero, ToolbarSlideSpring)
                }
            }
            .graphicsLayer {
                val shift = immediate ?: animatable.value
                translationX = shift.x.toFloat()
                translationY = shift.y.toFloat()
            }
    }

/**
 * The furthest a re-placed icon may have travelled and still be slid rather
 * than snapped.
 *
 * A toolbar icon is measured against the bar it sits in ([anchorCoords] is the
 * keyboard body, so its width is the bar's), because what the bar asks of its
 * icons scales with how wide it is; see [ToolbarSlideCapFraction]. Anything
 * else, and a bar with no anchor to measure, falls back to [PlacementSlideCap].
 */
private fun placementCap(inRow: Boolean, anchorCoords: LayoutCoordinates?): Float {
    if (!inRow) return PlacementSlideCap
    val width = anchorCoords?.size?.width?.toFloat() ?: return PlacementSlideCap
    return (width * ToolbarSlideCapFraction).coerceAtLeast(PlacementSlideCap)
}

/**
 * Where an icon that lives in more than one branch of the tree last sat.
 *
 * [animatePlacement] can only animate a node that survives the layout
 * change, and the emoji icon does not: the strip draws its own copy and
 * the toolbar draws another as a pinned tool, so flipping between them
 * disposes one node and composes a different one. Parking the last
 * body-relative position outside both lets the arriving node start from
 * where the leaving one stood, which is the whole illusion.
 */
private class SharedPlacement {
    var last: IntOffset? = null

    /** Whether [last] was measured against the anchor or against a parent. */
    var lastAnchored: Boolean = false

    /**
     * When the node holding [last] was disposed, or 0 while one still holds it.
     *
     * This is what separates a handoff from a reappearance. A handoff is one
     * node being disposed and another composed inside a single frame, so the
     * arriving node finds a stamp a frame or two old. A reappearance — the
     * icon's whole host went away, because a full-bleed panel takes the top
     * bar with it, and came back seconds later — finds a stale one, and
     * sliding in from a position that old is a phantom: the icon flies in
     * from wherever it happened to sit before the panel opened.
     *
     * It has to be dispose time, not the time [last] was written. Writing it
     * on placement looks equivalent and is not: a node is only re-placed when
     * layout invalidates, so the strip's emoji icon stamps once when it
     * appears and then sits untouched for as long as the user types. Every
     * real handoff then read as ancient and refused to animate, which is
     * exactly the icon snapping into place instead of sliding.
     */
    var vacatedAtNanos: Long = 0L
}

/** Two frames at 60Hz, the window a real strip/toolbar handoff lands in. */
private const val SharedPlacementMaxAgeNanos = 33_000_000L

/**
 * Slides this element in from wherever [shared] was last seen, then keeps
 * [shared] pointing at its own position. The counterpart to
 * [animatePlacement] for an icon that changes parents rather than moving
 * within one.
 */
private fun Modifier.animateSharedPlacement(
    shared: SharedPlacement,
    enabled: Boolean = true,
    anchor: () -> LayoutCoordinates? = { null },
): Modifier =
    composed {
        val scope = rememberCoroutineScope()
        val offset = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
        var immediate by remember { mutableStateOf<IntOffset?>(null) }
        // Stamp the moment this node leaves, so whichever node replaces it can
        // tell a handoff from a reappearance. onDispose runs while changes are
        // applied, ahead of the layout pass that places the arriving node, so
        // the stamp is always there in time.
        DisposableEffect(shared) {
            onDispose { shared.vacatedAtNanos = System.nanoTime() }
        }
        // This node's own last position, so a re-placement within one parent
        // (the bar reflowing under it) animates like [animatePlacement] does,
        // not just the cross-parent handoff. Without this the emoji snapped
        // whenever the toolbar reshuffled — every other icon slid but it.
        var lastTarget by remember { mutableStateOf<IntOffset?>(null) }
        // Anchor-relative or parent-relative, for this node and for the shared
        // stamp alike. The anchor arrives from an onGloballyPositioned a pass
        // late, so a first position is parent-relative and everything after it
        // is not; comparing across the two would invent a move. See the same
        // note in [animatePlacement].
        var lastAnchored by remember { mutableStateOf(false) }
        this
            .onPlaced { coords ->
                val anchorCoords = anchor()?.takeIf { it.isAttached }
                val position = (
                    anchorCoords?.localPositionOf(coords, Offset.Zero)
                        ?: coords.positionInParent()
                    ).round()
                val anchored = anchorCoords != null
                if (lastTarget == position && lastAnchored == anchored) return@onPlaced
                val ownPrevious = lastTarget.takeIf { lastAnchored == anchored }
                val sharedPrevious = shared.last?.takeIf { shared.lastAnchored == anchored }
                val vacatedAt = shared.vacatedAtNanos
                val first = ownPrevious == null
                lastTarget = position
                lastAnchored = anchored
                shared.last = position
                shared.lastAnchored = anchored
                // Claim the slot: while this node lives there is no vacancy.
                shared.vacatedAtNanos = 0L
                if (!enabled) return@onPlaced
                // A cross-parent handoff: this node just appeared and the
                // sibling it replaces vacated within the last frame or two
                // (see [SharedPlacement.vacatedAtNanos]). It slides in from
                // wherever that sibling stood, however far along the row.
                val handoff = first && sharedPrevious != null && vacatedAt != 0L &&
                    System.nanoTime() - vacatedAt <= SharedPlacementMaxAgeNanos
                // Where this placement travelled *from*: the vacated slot for a
                // handoff, else this node's own previous spot for an ordinary
                // reflow. A fresh node with neither has nowhere to come from.
                val previous = when {
                    handoff -> sharedPrevious
                    !first -> ownPrevious
                    else -> null
                } ?: return@onPlaced
                if (previous == position) return@onPlaced
                val raw = previous - position
                // A handoff travels along the bar and nowhere else, so only the
                // horizontal part of it is real. The two nodes are not the same
                // shape — the toolbar's pinned emoji is a 30dp circle with its
                // name under it, the strip's is a bare 38dp one — so their tops
                // sit a few pixels apart and the vertical rule below read that
                // as "the row moved" and snapped. Which is why the icon stopped
                // sliding across the moment toolbar labels were switched on.
                val delta = if (handoff) IntOffset(raw.x, 0) else raw
                // A slide along the bar never changes height; a vertical move
                // means the rows themselves shifted (a panel opened, the emoji
                // row appeared), which animating reads as lag — snap it.
                if (delta.x == 0 || kotlin.math.abs(delta.y) > ToolbarRowShiftPx) {
                    immediate = null
                    scope.launch { offset.snapTo(IntOffset.Zero) }
                    return@onPlaced
                }
                // A reflow nudge is small; a jump past the bar's own cap is a
                // layout change (a scroll, a resize), not motion. The handoff is
                // exempt — it is deliberately a long slide.
                val jump = delta.toOffset().getDistance()
                if (!handoff && (jump < 2f || jump > placementCap(true, anchorCoords))) {
                    immediate = null
                    scope.launch { offset.snapTo(IntOffset.Zero) }
                    return@onPlaced
                }
                // Carry any displacement still in flight so a change that lands
                // mid-slide continues from where the icon actually is. This is
                // what keeps the drawn position continuous when a handoff is
                // followed a frame later by the layout settling to its final
                // slot: the seed offset re-anchors to the new target instead of
                // snapping the icon out to the row's edge.
                val start = delta + (immediate ?: offset.value)
                // Drawn this frame, not next; see the note in animatePlacement.
                immediate = start
                scope.launch {
                    offset.snapTo(start)
                    immediate = null
                    offset.animateTo(IntOffset.Zero, ToolbarSlideSpring)
                }
            }
            .graphicsLayer {
                val shift = immediate ?: offset.value
                translationX = shift.x.toFloat()
                translationY = shift.y.toFloat()
            }
    }

/**
 * One round tool button; the circle radius comes from the theme (0 = bare
 * icon). With [longPressLabel] set, holding the button pops the tool's name
 * above it — the toolbar shows bare icons, so this is how a user finds out
 * what one does without tapping it.
 */
@Composable
private fun ToolCircle(
    slot: String,
    description: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    longPressLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    // False when an ancestor owns the whole gesture (see DraggableTool):
    // a clickable here would sit deeper in the chain and steal the release.
    interactive: Boolean = true,
    // Inactive-icon tint override (the tool's accent colour). Null keeps the
    // theme's toolbar-icon colour; the active state always wins over this.
    tint: Color? = null,
    // The gradient to paint the inactive icon with instead of [tint], when the
    // gradient tool colours are on. Same precedence: active still wins.
    tintBrush: Brush? = null,
    // When set, the tool's name is drawn under the icon (toolbar labels). The
    // long-press tooltip is then redundant and suppressed.
    label: String? = null,
    labelSizeSp: Int = 9,
    // Draws this button as a drop preview — washed out, outlined, "will go
    // here" — while keeping the footprint of the real thing. Same look as
    // [GhostToolCircle], but it follows the label setting, and a bare circle
    // standing in for a labelled button is a whole row's worth of height
    // missing from the preview.
    ghost: Boolean = false,
    // Bar buttons stretch to the theme's tool width (38 = today's circle);
    // panel headers and grids keep the fixed circle regardless of the setting.
    wide: Boolean = false,
    // The physical key that opens this tool, drawn as a badge over the bottom of
    // the button. An overlay, never an extra child: the badge appears the moment
    // the picker arms, and a button that grew a row then would shove the whole
    // keyboard down under the user's hands.
    hint: String? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.toolShape()
    val background = when {
        ghost -> kb.toolCircleActive.copy(alpha = 0.22f)
        active -> kb.toolCircleActive
        kb.toolRadiusDp > 0 -> kb.toolCircle
        else -> Color.Transparent
    }
    val outline = when {
        // The ghost's own dashed-out look wins: it is saying "will go here",
        // not wearing the theme's outline.
        ghost -> Modifier.border(1.dp, kb.toolbarIcon.copy(alpha = 0.35f), shape)
        kb.toolBorder != null && kb.toolBorderWidthDp > 0f ->
            Modifier.border(kb.toolBorderWidthDp.dp, kb.toolBorder, shape)
        else -> Modifier
    }
    var showLabel by remember { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    val click = if (!interactive) {
        Modifier
    } else if (longPressLabel == null && onLongPress == null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier.pointerInput(longPressLabel, onLongPress != null) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = {
                    feedback()
                    if (onLongPress != null) onLongPress() else showLabel = true
                },
            )
        }
    }
    val fullTint = if (active) kb.toolCircleActiveIcon else (tint ?: kb.toolbarIcon)
    val iconTint = if (ghost) fullTint.copy(alpha = 0.45f) else fullTint
    val iconBrush = if (active || ghost) null else tintBrush
    if (label != null) {
        // Labelled variant (toolbar labels): icon in its circle, name beneath.
        Column(
            modifier = modifier.then(click),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = (if (wide) (kb.toolWidthDp - 8).coerceAtLeast(30) else 30).dp,
                        height = 30.dp,
                    )
                    .clip(shape)
                    .background(background, shape)
                    .then(outline),
                contentAlignment = Alignment.Center,
            ) {
                SlotIcon(
                    slot,
                    contentDescription = description,
                    modifier = Modifier
                        // Not [ToolIconSize]: this box is 30 dp, not 38, because
                        // the name underneath has to fit in the same toolbar
                        // height. A 22 dp glyph lifted clear of the hint badge
                        // would leave the box through the top. The name grew
                        // instead — which is what is read here anyway.
                        .size(20.dp)
                        // Lifted, not shrunk, so the badge below has room inside
                        // a box whose size must not change (see [HintBadge]).
                        .offset(y = if (hint != null) -(HintBadgeHeight / 2) else 0.dp),
                    tint = iconTint,
                    brush = iconBrush,
                )
                // Under the icon, over the name: the badge is temporary and the
                // name is what the labels setting was turned on for, so the
                // badge borrows the name's top edge rather than the icon's face.
                if (hint != null) {
                    HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            Text(
                label,
                fontSize = labelSizeSp.sp,
                lineHeight = (labelSizeSp + 1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (ghost) fullTint.copy(alpha = 0.5f) else fullTint,
                modifier = Modifier.padding(top = 1.dp, start = 1.dp, end = 1.dp),
            )
        }
        return
    }
    Box(
        modifier = modifier
            .size(width = (if (wide) kb.toolWidthDp else 38).dp, height = 38.dp)
            .clip(shape)
            .background(background, shape)
            .then(outline)
            .then(click),
        contentAlignment = Alignment.Center,
    ) {
        SlotIcon(
            slot,
            contentDescription = description,
            modifier = Modifier
                .size(ToolIconSize)
                // The icon steps up by half the badge's height so the badge sits
                // under it rather than across it. The button's own 38 dp box is
                // untouched, so nothing on the bar moves.
                .offset(y = if (hint != null) -(HintBadgeHeight / 2) else 0.dp),
            tint = iconTint,
            brush = iconBrush,
        )
        if (hint != null) {
            HintBadge(hint, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (showLabel && longPressLabel != null) {
            LaunchedEffect(Unit) {
                delay(1200)
                showLabel = false
            }
            Popup(
                popupPositionProvider = rememberAboveAnchorPopup(),
                onDismissRequest = { showLabel = false },
            ) {
                Surface(
                    shape = kb.popupShape(),
                    color = kb.popup,
                    border = kb.popupSurfaceBorder(),
                    shadowElevation = elevationFor(kb.popupShapeKind, 6.dp),
                ) {
                    Text(
                        longPressLabel,
                        color = kb.popupText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The drop-preview stand-in rendered at the slot a dragged tool would land
 * in: same footprint as [ToolCircle], drawn washed out so it reads as
 * "will go here" rather than "is here". Keyed placement animation makes it
 * slide from slot to slot as the finger moves.
 */
@Composable
private fun GhostToolCircle(
    tool: ToolbarTool,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val shape = kb.toolShape()
    Box(
        modifier = modifier
            .size(width = (if (wide) kb.toolWidthDp else 38).dp, height = 38.dp)
            .background(kb.toolCircleActive.copy(alpha = 0.22f), shape)
            .border(1.dp, kb.toolbarIcon.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        SlotIcon(
            IconSlots.forTool(tool),
            contentDescription = null,
            modifier = Modifier.size(ToolIconSize),
            tint = kb.toolbarIcon.copy(alpha = 0.45f),
        )
    }
}

/**
 * The glyph inside a tool button, on the bar and in the toolbox alike.
 *
 * It was 20 dp in a 38 dp button, which left the icon floating in a lot of
 * empty circle: at a glance a toolbox page read as a field of identical
 * bubbles, and picking one out meant looking at each in turn rather than
 * seeing the shape. 22 dp still clears the button's edge — and the hint badge
 * that overlays its bottom — while giving the glyph enough of the circle to
 * be recognised on the way past.
 */
private val ToolIconSize = 22.dp

/**
 * The tools on a row of their own, above the suggestion strip — the row
 * [ToolbarPlacement.ON_DEMAND_ROW] and [ToolbarPlacement.ALWAYS_ROW] add.
 *
 * A plain [Row] of the same height as the strip wrapping the same [ToolbarRow]
 * the strip hosts in the shared arrangement, so the two placements draw the same
 * tools, the same widths, and the same drag behaviour. No fade: nothing is being
 * exchanged here — the row is either there or it is not, and its own appearance
 * is the animation.
 *
 * RTL scripts mirror it exactly as they mirror the strip; the drag controller is
 * already mirrored to match (see [KeyboardBody]).
 */
@Composable
private fun ToolsRow(
    state: KeyboardUiState,
    onPanelChange: (PanelMode) -> Unit,
    onToolTap: (ToolbarTool) -> Unit,
    drag: ToolDragController,
) {
    val direction = if (state.script.direction == TextDirection.RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topBarHeight(state.settings)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarRow(state, onPanelChange, onToolTap, drag)
        }
    }
}

/**
 * The toolbar itself: fixed toolbox launcher, then the user's tools —
 * spread across the free space when the greedy setting is on, packed to
 * the left otherwise.
 */
@Composable
private fun RowScope.ToolbarRow(
    state: KeyboardUiState,
    onPanelChange: (PanelMode) -> Unit,
    onToolTap: (ToolbarTool) -> Unit,
    drag: ToolDragController,
    // Opacity for the tools while the toolbar fades in. On an in-place
    // strip→toolbar flip the emoji is exempt — it hands its position across and
    // slides instead of fading — so this rides the toolbox launcher and pinned
    // tools only. On a fresh mount nothing slides, so [fadeEmoji] folds the
    // emoji into the same fade and the whole bar comes up together.
    contentAlpha: () -> Float = { 1f },
    fadeEmoji: Boolean = false,
) {
    val customizing = state.panel == PanelMode.TOOLBOX
    // Scrolling wants the tools at their natural width, so it overrides the
    // greedy even-spread (which would keep them all on screen and shrinking).
    val scrollable = state.settings.toolbarBehavior.scrollable
    val greedy = state.settings.toolbarBehavior.greedy && !scrollable
    val labels = state.settings.toolbarLabels
    val labelSize = state.settings.toolbarLabelSize
    val motion = !state.settings.reduceMotion
    // Enter and exit share one duration so the back chevron takes the same
    // time to leave as it took to arrive; a shorter exit made closing a panel
    // finish ahead of the icons still sliding back into the freed slot.
    val enterMs = if (motion) ToolbarMotionMs else 0
    // RTL scripts read the bar right-to-left, so the pinned tools mirror. The
    // drag controller mirrors its copy in lockstep (see KeyboardBody), so slot
    // hit-testing stays aligned with what's drawn.
    val tools = visibleToolbarTools(state)
    drag.visibleTools = tools
    // While a drag is live the bar previews the drop by MOVING the dragged
    // tool's own cell to the slot under the finger and drawing it as a ghost
    // there. Not by inserting a separate ghost entry: the dragged cell has to
    // stay composed (it hosts the pointer handler driving the drag — dropping
    // it disposed that handler the instant the hold registered, which sent
    // every long-press down the "open settings" path instead), so an inserted
    // ghost made the row one cell longer than the row the drop commits to.
    // Every icon between the tool's old slot and the new one then previewed a
    // slot to the side of where it actually ended up.
    //
    // A keyed move is enough here. Row re-measures and re-places its children
    // on a pure reorder — unlike FlowRow, which is why the toolbox grid has to
    // re-key its ghost per slot instead (see [ToolboxGrid]).
    val dragTool = drag.dragging
    val ghostSlot = drag.barSlot
    val displayTools: List<ToolbarTool> = if (dragTool != null && ghostSlot != null) {
        (tools - dragTool).toMutableList().apply {
            add(ghostSlot.coerceIn(0, size), dragTool)
        }
    } else {
        tools
    }
    // Slot 0 is the toolbox launcher, so a tool's badge is its place in `tools`
    // plus one. Dropped entirely during a drag: the dragged cell may have come
    // from the toolbox and not be in `tools` at all, and a badge indexed off the
    // preview order would label cells with their neighbours' keys.
    val hints = if (dragTool == null) armedHintPlan(state) else null
    val panelOpen = state.panel != PanelMode.NONE

    // In greedy mode every button — chevron, toolbox and tools alike — is an
    // equal-weight cell, so the whole bar is one evenly spaced grid instead
    // of fixed buttons on the left with the tools spread over the leftover.
    val leading: @Composable (Modifier) -> Unit = { cell ->
        // With any tool panel open, one tap on the chevron returns to the keys.
        // The transition state keeps the cell composed until the exit
        // animation finishes, then drops it entirely so a weighted cell
        // doesn't linger as an invisible gap.
        val chevronVisible = remember { MutableTransitionState(false) }
        chevronVisible.targetState = panelOpen
        if (chevronVisible.currentState || chevronVisible.targetState) {
            AnimatedVisibility(
                visibleState = chevronVisible,
                modifier = if (greedy) cell else Modifier,
                // Reduce motion collapses the durations to zero rather than
                // dropping AnimatedVisibility: the transition state still has
                // to run its lifecycle or the weighted cell never releases.
                // Both branches animate paint only, never measured size. The
                // greedy one already did (its slot is weighted, so the chevron
                // scales inside a cell that appears at full width); the other
                // used to expand/shrink its width, which re-measured the row
                // on every frame and made the pinned icons chase a target that
                // moved under them for the whole transition. See the matching
                // note on the suggestions chevron in TopBar.
                enter = if (greedy) {
                    // The weighted slot can't grow, so the chevron itself
                    // scales and fades into it.
                    scaleIn(tween(enterMs)) + fadeIn(tween(enterMs))
                } else {
                    fadeIn(tween(enterMs))
                },
                // Instant, for the same reason as the suggestions chevron: an
                // exit transition holds this cell's width (its whole weighted
                // slot, in greedy mode) until it finishes, so closing a tool
                // played the chevron out, paused, and only then let the icons
                // move — which is why closing felt broken while opening, where
                // the slot is claimed on the first frame, felt fine. Dropping
                // it at once makes the icons' spring the closing animation.
                exit = ExitTransition.None,
            ) {
                Box(
                    if (greedy) Modifier.fillMaxSize() else cell,
                    contentAlignment = Alignment.Center,
                ) {
                    ToolCircle(
                        slot = IconSlots.CHROME_PANEL_BACK,
                        description = stringResource(R.string.ime_panel_back_desc),
                        active = false,
                        longPressLabel = stringResource(R.string.ime_panel_back_desc),
                        wide = true,
                    ) { onPanelChange(state.panel) }
                }
            }
        }
        Box(cell, contentAlignment = Alignment.Center) {
            ToolCircle(
                slot = IconSlots.CHROME_TOOLBOX,
                description = stringResource(R.string.ime_toolbox_desc),
                active = customizing,
                modifier = Modifier
                    // The width a pinned button actually gets, published for the
                    // strip's emoji shortcut to match (see [pinnedToolWidthPx]).
                    .onSizeChanged { drag.pinnedToolWidthPx = it.width }
                    .animatePlacement(enabled = motion, inRow = true) { drag.bodyCoords }
                    // Under the slide, never over it: a fade renders offscreen
                    // and clips to its own node, so a stationary fade around a
                    // displaced icon cuts a slice off it. See the tool cells.
                    .graphicsLayer { alpha = contentAlpha() },
                longPressLabel = stringResource(R.string.ime_toolbox_desc),
                wide = true,
                hint = hints?.label(HintSurface.TOOLBAR, 0),
            ) { onPanelChange(PanelMode.TOOLBOX) }
        }
    }
    val toolCells: @Composable RowScope.() -> Unit = {
        for (tool in displayTools) {
            key(tool) {
                val cell = if (greedy) {
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                } else {
                    Modifier.padding(horizontal = 3.dp)
                }
                // On an in-place flip the emoji slides and stays opaque while
                // the rest fade (see [contentAlpha]); on a fresh mount ([fadeEmoji])
                // nothing slides, so it fades in with them.
                //
                // The alpha rides the *icon*, not the cell around it. A
                // graphicsLayer below full opacity renders to an offscreen
                // buffer the size of its node, and that buffer clips — so with
                // the fade on the cell, an icon that [animatePlacement] had
                // displaced out of its cell (which happens whenever the bar
                // reflows mid-fade: a chevron claiming or releasing its slot)
                // was drawn with a slice missing and a hard vertical edge where
                // the cell ended. On the icon's own node the buffer travels
                // with it and there is nothing to clip.
                val fadeThis = tool != ToolbarTool.EMOJI || fadeEmoji
                Box(cell, contentAlignment = Alignment.Center) {
                    // Drag is always live: hold-and-drag reorders the bar
                    // (or unpins into an open toolbox); a hold that never
                    // moves opens the tool's settings page instead — or,
                    // on the caret tools, repeats the move for as long as
                    // the finger is down. Only here: the toolbox keeps the
                    // hold-for-settings gesture for every tool, which is
                    // what makes those pages still reachable.
                    DraggableTool(
                        tool,
                        fromToolbar = true,
                        enabled = true,
                        drag = drag,
                        onTap = { onToolTap(tool) },
                        holdRepeatMs = holdRepeatMs(tool, state),
                        holdAction = holdActionFor(tool, fromToolbar = true, state = state),
                        holdArms = holdArmsSelection(tool, fromToolbar = true, state = state),
                    ) { dragModifier ->
                        ToolCircle(
                            slot = IconSlots.forTool(tool),
                            description = toolLabel(tool),
                            active = toolActive(tool, state),
                            label = if (labels) toolLabel(tool) else null,
                            labelSizeSp = labelSize,
                            // This cell IS the drop preview while it is the one
                            // being dragged: it has moved to the slot under the
                            // finger and draws washed out there, so the row on
                            // screen is exactly the row the drop commits. The
                            // solid icon to look at is the floating one under
                            // the finger.
                            ghost = tool == dragTool,
                            wide = true,
                            hint = hints?.label(HintSurface.TOOLBAR, tools.indexOf(tool) + 1),
                            // The icon itself animates, anchored at the
                            // keyboard body: cells are weighted so their
                            // widths snap, and only body-relative tracking
                            // sees the true on-screen motion.
                            // The emoji tool also exists on the
                            // suggestion strip, so it hands its position
                            // across that swap instead of tracking only
                            // its own node.
                            modifier = dragModifier
                                .then(
                                    if (tool == ToolbarTool.EMOJI) {
                                        Modifier.animateSharedPlacement(
                                            drag.emojiPlacement,
                                            enabled = motion,
                                        ) { drag.bodyCoords }
                                    } else {
                                        Modifier.animatePlacement(
                                            enabled = motion,
                                            inRow = true,
                                        ) { drag.bodyCoords }
                                    },
                                )
                                // Inside the slide, so the fade's buffer travels
                                // with the icon rather than clipping it.
                                .then(
                                    if (fadeThis) {
                                        Modifier.graphicsLayer { alpha = contentAlpha() }
                                    } else {
                                        Modifier
                                    },
                                ),
                            interactive = false,
                        ) {}
                    }
                }
            }
        }
    }
    if (greedy) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            // The tools sub-row carries a weight equal to its cell count, so
            // its cells end up exactly as wide as the leading buttons' cells.
            // It still exists (zero tools aside) as the drag-drop target.
            Row(
                modifier = Modifier
                    .weight(displayTools.size.coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .onGloballyPositioned { drag.toolbarBounds = it.boundsInRoot() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                toolCells()
            }
        }
    } else {
        leading(Modifier.padding(horizontal = 3.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // Weighted so it claims exactly the free width; the scroll then
                // lets the pinned tools overflow that width instead of packing
                // to fit. Reordering by drag still works — the toolbox is the
                // simpler place to rearrange a long, scrolling bar.
                .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .onGloballyPositioned { drag.toolbarBounds = it.boundsInRoot() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolCells()
            // A weighted spacer can't live inside a horizontal scroll (infinite
            // width); packed-to-fit mode still needs it to left-align the tools.
            if (!scrollable) Spacer(modifier = Modifier.weight(1f))
        }
    }
    if (state.incognitoOn) {
        SlotIcon(
            IconSlots.CHROME_INCOGNITO,
            contentDescription = stringResource(R.string.ime_incognito_on_desc),
            modifier = Modifier
                .padding(end = 6.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Gboard-style toolbox: every tool that is not on the toolbar, shown in a
 * labeled grid ordered by [KeyboardSettings.toolboxOrder] (most-used-first
 * until the user rearranges it). Tap to use a tool in place; hold and drag
 * it up onto the toolbar to pin it, or around the grid to reorder. Toolbar
 * tools drag down here to unpin — at the spot they're dropped.
 *
 * Two shapes, chosen by [ToolboxSettings.layout]: round icons with a caption,
 * or [ToolPill] rows. Either can scroll as one long grid or break into swiped
 * pages; that is [ToolboxSettings.paginate], and the cells are the same
 * [ToolboxGrid] either way.
 */
@Composable
private fun ToolboxPanel(
    state: KeyboardUiState,
    onToolTap: (ToolbarTool) -> Unit,
    onHintDismiss: () -> Unit,
    drag: ToolDragController,
) {
    val height = keyRowsHeight(state)
    // First open: always show the drag hint. After it was dismissed once,
    // resurface it only rarely as a reminder. Rolled once per panel open.
    val rareReminder = remember { Random.nextFloat() < 0.03f }
    var hintVisible by remember(state.settings.toolboxHintDismissed) {
        mutableStateOf(!state.settings.toolboxHintDismissed || rareReminder)
    }
    // The registered geometry outlives the panel unless cleared, and a
    // stale viewport would let a later drag "drop on the toolbox" with the
    // panel long gone.
    DisposableEffect(drag) {
        onDispose {
            drag.toolboxViewport = null
            drag.toolboxContentCoords = null
            drag.toolboxPageStart = 0
            drag.toolboxPageLength = Int.MAX_VALUE
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { drag.toolboxViewport = it.boundsInRoot() },
    ) {
        // A slim header carrying the drag hint until it's dismissed. Resetting
        // the pinned tools now lives in Settings → Appearance ("Reset pinned
        // tools"), so the toolbox no longer shows its own reset control.
        if (hintVisible) {
            val activeMode = state.settings.keyboardModes
                .firstOrNull { it.id == state.activeModeId }
                ?.takeIf { state.settings.modeToolOrderEdits && it.ownsToolOrder }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // With a mode on, the arrangement being edited is that mode's
                // own — say so, or the same keyboard looking different in the
                // next app reads as the drag being lost.
                val dragHint = stringResource(R.string.ime_toolbox_hint_drag)
                Text(
                    if (activeMode != null) {
                        stringResource(R.string.ime_toolbox_hint_mode, activeMode.name) +
                            " " + dragHint
                    } else {
                        dragHint
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        hintVisible = false
                        onHintDismiss()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.ime_toolbox_hint_dismiss_desc),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Toolbox order is a complete ranking over every tool; the grid
        // shows the available subset in that order.
        val available = visibleToolboxTools(state)
        val toolbox = state.settings.toolbox
        val pills = toolbox.layout == ToolboxLayout.PILLS
        // The two layouts count their columns separately: a pill needs room for
        // a name, so its comfortable width is nothing like an icon's.
        val columns = if (pills) {
            toolbox.pillColumns.coerceAtLeast(1)
        } else {
            state.settings.toolboxColumns.coerceAtLeast(1)
        }
        drag.toolboxTools = available
        drag.toolboxOrder = state.settings.toolboxOrder
        drag.toolboxColumns = columns
        // Drop preview: the dragged tool LEAVES the list and a ghost marks the
        // slot it would land in. That is one hole, not two, and — the reason
        // it has to be done this way — it is a removal plus an insertion.
        //
        // A permutation does not work here. Keeping the tool in the list and
        // moving it to the target slot reads identically in the composition,
        // but Compose moves the composition groups without re-placing the
        // laid-out children, so the grid never reflowed: verified on device,
        // where a whole drag produced exactly one layout pass and every cell
        // kept its original x. The old code only appeared to shuffle because
        // inserting a ghost was a structural change.
        //
        // Removing the tool means its cell is disposed mid-gesture, which is
        // why the drag is no longer hosted there — see the pointerInput on the
        // grid below. The cells are pure visuals now.
        val dragTool = drag.dragging
        val boxSlot = drag.boxSlot
        val display: List<ToolbarTool?> = if (dragTool != null && boxSlot != null) {
            // `available - dragTool` is a no-op when the drag came from the
            // toolbar, so both origins land on one ghost and one hole.
            val without = available - dragTool
            ArrayList<ToolbarTool?>(without).apply {
                add(boxSlot.coerceIn(0, without.size), null)
            }
        } else {
            available
        }
        if (display.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.ime_toolbox_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        // Indexed against `available`, never `display`: the drop preview inserts
        // a null ghost slot into `display`, which would shift every index past it
        // and open the wrong tool. The ring is hidden mid-drag anyway (below),
        // but the count published here must match what Enter will resolve.
        PanelFocusTarget(
            panel = PanelMode.TOOLBOX,
            count = available.size,
            columns = columns,
            onActivate = { index -> available.getOrNull(index)?.let(onToolTap) },
        )
        val focusedTool = state.focusedIndex().takeIf { drag.dragging == null }
        if (!toolbox.paginate) {
            // One long grid the user scrolls: every cell is at its absolute
            // slot, so the drop math needs no page offset.
            drag.toolboxPageStart = 0
            drag.toolboxPageLength = Int.MAX_VALUE
            ToolboxGrid(
                state = state,
                drag = drag,
                display = display,
                pageStart = 0,
                columns = columns,
                pills = pills,
                dragTool = dragTool,
                focusedSlot = focusedTool,
                registerGeometry = true,
                onToolTap = onToolTap,
            )
            return@Column
        }
        // Paginated: fixed pages to swipe through instead of a scroll. The
        // ghost takes a slot like anything else, so a drop preview near a page
        // boundary pushes the last tool onto the next page — which is what the
        // drop itself would do, so the preview stays honest.
        val pageSize = toolbox.pageSize.coerceIn(ToolboxPageSizeRange)
        val pageCount = toolboxPageCount(display.size, pageSize)
        val pager = rememberPagerState(pageCount = { pageCount })
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            // A page is cheap to build, but building the ones nobody has
            // swiped towards is still work the first frame doesn't need.
            beyondViewportPageCount = 0,
        ) { page ->
            // Only the page in front publishes to the drag controller: its
            // neighbours stay composed through a swipe, and two pages claiming
            // the drop geometry would race over one set of coordinates.
            val current = page == pager.currentPage
            if (current) {
                drag.toolboxPageStart = page * pageSize
                drag.toolboxPageLength = pageSize
            }
            ToolboxGrid(
                state = state,
                drag = drag,
                display = toolboxPage(display, page, pageSize),
                pageStart = page * pageSize,
                columns = columns,
                pills = pills,
                dragTool = dragTool,
                focusedSlot = focusedTool.takeIf { current },
                registerGeometry = current,
                onToolTap = onToolTap,
            )
        }
        PageDots(count = pageCount, current = pager.currentPage)
        // The hardware focus ring walks the whole toolbox, so following it here
        // means turning to the page it walked onto.
        LaunchedEffect(focusedTool, pageSize, pageCount) {
            val index = focusedTool ?: return@LaunchedEffect
            val target = (index / pageSize).coerceIn(0, pageCount - 1)
            if (target != pager.currentPage) pager.animateScrollToPage(target)
        }
    }
}

/**
 * One screenful of toolbox cells — the whole grid when the toolbox scrolls,
 * a single page when it paginates — plus the one gesture handler that owns
 * every cell in it.
 *
 * The handler is hoisted above the cells rather than sitting on each one, and
 * that is what lets a cell be disposed mid-drag: the handler outlives any cell,
 * so the dragged tool can leave the list and the rest can close up behind it.
 * One FlowRow, so a part-full last line still lines up with the ones above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolboxGrid(
    state: KeyboardUiState,
    drag: ToolDragController,
    /** This page's cells: tools, with a null where the drop ghost goes. */
    display: List<ToolbarTool?>,
    /** Absolute slot of this page's first cell; 0 for the scrolling toolbox. */
    pageStart: Int,
    columns: Int,
    pills: Boolean,
    dragTool: ToolbarTool?,
    /** Absolute slot the hardware focus ring is on, or null. */
    focusedSlot: Int?,
    /** Whether this grid is the one a drop should be measured against. */
    registerGeometry: Boolean,
    onToolTap: (ToolbarTool) -> Unit,
) {
    // The placement anchor is this scrolling content column, NOT the keyboard
    // body: relative to the body every icon "moves" on every scroll frame,
    // which made each one restart its placement spring per frame (a
    // coroutine-and-relayout storm that tanked scroll fps). Relative to the
    // content, scrolling is a no-op for the animation.
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val feedback = LocalKeyPressFeedback.current
    // Once for the grid, not once per cell: this walks the tool lists and
    // builds two maps, and a forty-cell toolbox would have done it forty times
    // for one answer. Null with no picker armed, and during a drag — a drag
    // inserts a ghost into `display`, and every slot past it would then be
    // labelled with its neighbour's key.
    val hints = if (dragTool == null) armedHintPlan(state) else null
    val scope = rememberCoroutineScope()
    val tapTool by rememberUpdatedState(onToolTap)
    // Read through holders, and key the handler on nothing. `display` is a
    // fresh list on every recomposition and the drop preview recomposes this
    // panel on every frame of a drag, so keying pointerInput on it would tear
    // down and restart the very gesture it is running — the same trap the
    // per-cell handler documented.
    val cellsNow by rememberUpdatedState(display)
    val columnsNow by rememberUpdatedState(columns)
    val registerNow by rememberUpdatedState(registerGeometry)
    // Through a holder for the same reason, rather than as a pointerInput key:
    // the grid's handler is keyed on nothing at all, and a settings change is
    // not a reason to be the one thing that restarts it mid-gesture.
    val toolboxRepeatNow by rememberUpdatedState(state.settings.textEditing.toolboxRepeatTools)
    val repeatMsNow by rememberUpdatedState(state.settings.textEditing.repeatMs.toLong())
    val gridScroll = rememberScrollState()
    // No lazy state to scroll by index, so scroll by row: every cell reports
    // the same size, and the drag controller already collects it.
    ScrollFocusIntoView(focusedSlot) { index ->
        val rowHeight = drag.toolboxCellSize.height
        if (rowHeight > 0f) {
            val target = (((index - pageStart) / columns) * rowHeight).coerceAtLeast(0f)
            gridScroll.animateScrollTo(target.toInt().coerceIn(0, gridScroll.maxValue))
        }
    }
    FlowRow(
        modifier = Modifier
            .verticalScroll(gridScroll)
            .onGloballyPositioned {
                gridCoords = it
                gridOrigin = it.positionInRoot()
                if (registerNow) drag.toolboxContentCoords = it
            }
            .pointerInput(Unit) {
                val dragSlop = ToolDragSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Which tool was grabbed is resolved once, from where the
                    // finger landed. The grid is uniform, so this is the same
                    // arithmetic the drop target uses. Nothing has moved yet at
                    // this point: a drag is not live, so the list on screen is
                    // still the plain one, ghost-free.
                    val cell = drag.toolboxCellSize
                    if (cell.width <= 0f || cell.height <= 0f) return@awaitEachGesture
                    val cols = columnsNow
                    val col = (down.position.x / cell.width).toInt()
                    val row = (down.position.y / cell.height).toInt()
                    val tool = cellsNow
                        .getOrNull(row * cols + col)
                        ?.takeIf { col in 0 until cols && down.position.x >= 0f }
                        ?: return@awaitEachGesture
                    // Root coordinates throughout: the grid reflows around the
                    // drop preview while the finger is down, and a node that
                    // moves under a still finger reports deltas of its own.
                    val downRoot = gridOrigin + down.position
                    var rootPos = downRoot
                    var longPressed = false
                    var dragged = false
                    var released = false
                    var scrolled = false
                    // Resolved with the tool, from the set as it stands now. A
                    // tool the user opted in repeats here exactly as it does on
                    // the toolbar, and gives up this grid's hold-for-settings
                    // to do it; every other tool keeps it.
                    val holdRepeatMs =
                        repeatMsNow.takeIf { tool in toolboxRepeatNow && tool in HoldRepeatCursorTools }
                    val timer = scope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        feedback()
                        longPressed = true
                        // Pick-up held back until the finger travels, as on the
                        // toolbar: starting it here parked a drag ghost and the
                        // scope pill under a finger that was only holding a
                        // cell down — for its settings page, or for a repeat.
                        if (holdRepeatMs != null) {
                            while (true) {
                                tapTool(tool)
                                delay(holdRepeatMs)
                            }
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                released = true
                                if (longPressed) change.consume()
                                break
                            }
                            rootPos = gridOrigin + change.position
                            val travel = (rootPos - downRoot).getDistance()
                            if (!longPressed) {
                                // Drift before the hold registers is a scroll,
                                // or a page swipe — hand the gesture back.
                                if (travel > viewConfiguration.touchSlop) {
                                    scrolled = true
                                    break
                                }
                            } else {
                                change.consume()
                                if (travel > dragSlop && !dragged) {
                                    dragged = true
                                    // Travel means a reorder after all, so the
                                    // tool picks itself up from here (and a
                                    // repeating one stops repeating).
                                    timer.cancel()
                                    drag.start(tool, false, rootPos)
                                }
                                // Nothing to move until the finger has travelled:
                                // no drag has been started, and drag.move would
                                // tick the drop haptic anyway.
                                if (dragged) drag.move(rootPos)
                            }
                        }
                    } finally {
                        timer.cancel()
                        when {
                            !longPressed -> {
                                drag.cancel()
                                if (released && !scrolled) tapTool(tool)
                            }
                            dragged -> drag.end()
                            // A repeating hold has already done its work, and
                            // there is nothing to drop.
                            holdRepeatMs != null -> drag.cancel()
                            // A hold that never travelled past the slop is a
                            // distinct gesture: open the tool's settings.
                            else -> {
                                drag.cancel()
                                drag.onOpenSettings(tool)
                            }
                        }
                    }
                }
            },
        maxItemsInEachRow = columns,
    ) {
        display.forEachIndexed { slot, tool ->
            // The ghost's key encodes its slot so a move is a structural
            // remove+add, not a same-key reorder. FlowRow re-measures and
            // re-places its children on a structural change, but on a pure
            // reorder it moves the composition groups while leaving every
            // laid-out child at its old position — verified on device, where
            // the grid reflowed once (the dragged tool leaving) and then
            // froze for the rest of the drag: the ghost never moved and the
            // icons never made room. Re-keying per slot forces the reflow on
            // every step. (The toolbar is a Row, which re-places on reorder,
            // so its ghost keeps one stable key.)
            key(tool ?: "box-ghost-${pageStart + slot}") {
                Box(
                    modifier = Modifier
                        .toolboxCellWidth(columns)
                        // Every cell is the same size, so whichever reported
                        // last feeds the slot math. That is an invariant the
                        // tap and drop maths depend on, not an observation:
                        // width is fixed by toolboxCellWidth and height by the
                        // label's fixed line count. Anything put in a cell that
                        // can change its height breaks hit testing for every
                        // row below it.
                        .onGloballyPositioned { drag.toolboxCellSize = it.size.toSize() },
                    contentAlignment = Alignment.Center,
                ) {
                    // Pure visuals — the grid's own pointerInput owns the
                    // gesture, so nothing here has to survive the dragged
                    // tool leaving the list.
                    val ghost = tool == null
                    // dragTool is never null when a ghost entry exists.
                    val shown = tool ?: dragTool ?: return@Box
                    // `slot` indexes this page, which holds the plain tools
                    // whenever the ring is drawn at all — focusedSlot is null
                    // for the whole of a drag, and only a drag inserts a ghost.
                    val focused = focusedSlot == pageStart + slot
                    val paint = toolAccentPaint(shown, state.settings)
                    val hint = hints?.label(HintSurface.TOOLBOX, pageStart + slot)
                    // Anchored at the scrolling content (see gridCoords), NOT
                    // the keyboard body: body-relative, every scroll frame
                    // moved every icon and restarted its spring — a per-frame
                    // coroutine storm that tanked scroll fps. Content-relative,
                    // scrolling is a no-op; reorders still slide.
                    val placement = Modifier
                        .animatePlacement(enabled = !state.settings.reduceMotion) { gridCoords }
                    if (pills) {
                        ToolPill(
                            tool = shown,
                            active = toolActive(shown, state),
                            labelSize = toolboxLabelSize(state),
                            paint = paint,
                            filled = state.settings.toolbox.pillFilled,
                            ghost = ghost,
                            hint = hint,
                            modifier = placement
                                .padding(horizontal = 3.dp, vertical = 3.dp)
                                .focusRing(focused, RoundedCornerShape(pillRadius())),
                        )
                        return@Box
                    }
                    Column(
                        modifier = placement
                            .focusRing(focused, RoundedCornerShape(12.dp))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (ghost) {
                            GhostToolCircle(shown)
                        } else {
                            ToolCircle(
                                slot = IconSlots.forTool(shown),
                                description = toolLabel(shown),
                                active = toolActive(shown, state),
                                interactive = false,
                                tint = paint?.color,
                                tintBrush = paint?.brush,
                                hint = hint,
                            ) {}
                        }
                        Text(
                            toolLabel(shown),
                            // The name is what the grid is actually read by —
                            // the icons are close cousins of one another and
                            // the words are not — so it carries the same
                            // weight as a suggestion chip rather than the
                            // caption size it used to have.
                            fontSize = toolboxLabelSize(state),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = if (ghost) 0.5f else 1f),
                            textAlign = TextAlign.Center,
                            // Always two lines, never one and never three, so
                            // every cell is the same height. The grid's tap and
                            // drop maths divide a touch by one cell size to get
                            // a slot, which is only true of a uniform grid: when
                            // a long name wrapped and a short one did not, rows
                            // differed in height, the error accumulated downwards
                            // and a tap landed on the tool below — further out
                            // the further down you were, until the index ran off
                            // the end and the last tools stopped opening at all.
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 4.dp, start = 2.dp, end = 2.dp)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A toolbox cell's share of the row: the grid width divided [columns] ways,
 * rounded DOWN.
 *
 * `fillMaxWidth(1f / columns)` rounds to nearest, so for every width where the
 * remainder crosses half a pixel each cell rounded up and the row overflowed by
 * a pixel or two — and FlowRow answered by wrapping a column early. Fixed width
 * that is invisible; live-resizing a floating keyboard it was the columns
 * snapping between 4 and 3 and back for every few pixels of drag. Integer
 * division cannot overflow the row, so the count only changes when the setting
 * does.
 */
private fun Modifier.toolboxCellWidth(columns: Int): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth / columns).coerceAtLeast(1)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The size of a tool's name under its icon in the toolbox grid, when the user
 * has not set one. [ToolboxSettings.labelSizeOr] resolves the setting against
 * the toolbar's own label size.
 */
private val ToolLabelSize = 12.5.sp

/** The caption size for this toolbox, resolving the "follow the toolbar" default. */
@Composable
private fun toolboxLabelSize(state: KeyboardUiState): TextUnit {
    val chosen = state.settings.toolbox.labelSizeSp
    return if (chosen > 0) chosen.sp else ToolLabelSize
}

/** How tall a toolbox pill is. Half of it is the radius that makes the ends round. */
private val PillHeight = 44.dp

/**
 * A pill's corner radius, from the same "Tool circle radius" setting the round
 * tool buttons use. That slider is scaled for the 38 dp circle, so it is
 * stretched onto the taller pill: at the slider's top the ends are fully round,
 * at 0 the pill is a rectangle, and everything between tracks the circles.
 */
@Composable
private fun pillRadius(): Dp {
    val toolRadius = LocalKbTheme.current.toolRadiusDp
    return (PillHeight / 2) * (toolRadius / ToolCircleRadiusMax.toFloat()).coerceIn(0f, 1f)
}

/**
 * The top of the "Tool circle radius" slider (`appearance_tool_circle_title`).
 * The circles are 38 dp, so 20 already rounds them completely.
 */
private const val ToolCircleRadiusMax = 20

/**
 * One tool as a wide row: icon, name, and — for the tools that open a panel or
 * an activity rather than acting on the spot — a chevron on the trailing edge.
 * The chevron is the whole point of the layout: at a glance, "Themes" reads as
 * somewhere to go and "Flashlight" as something that just happens.
 *
 * With [filled] on, the tool's colour becomes the pill's background and the
 * icon and label flip to whatever reads on it. That is usually white, but not
 * always — a pale accent (the flashlight's amber) takes near-black instead,
 * because a white-on-amber label is a label nobody can read. With the gradient
 * tool colours on, the fill is the gradient and the contrast is worked out
 * against its near end.
 */
@Composable
private fun ToolPill(
    tool: ToolbarTool,
    active: Boolean,
    /** Caption size, resolved by the caller from the toolbox settings. */
    labelSize: TextUnit,
    /** The tool's colour, or null when colourful tool icons are off. */
    paint: ToolPaint?,
    filled: Boolean,
    ghost: Boolean,
    modifier: Modifier = Modifier,
    /** The physical key that opens this tool; see [ToolCircle]'s own `hint`. */
    hint: String? = null,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(pillRadius())
    // Nothing to fill a pill with when the colours are switched off.
    val fill = paint?.takeIf { filled }
    val background = when {
        ghost -> kb.toolCircleActive.copy(alpha = 0.22f)
        fill != null -> fill.color
        active -> kb.toolCircleActive
        else -> kb.toolCircle
    }
    val backgroundBrush = if (ghost) null else fill?.brush
    val content = when {
        fill != null -> maxContrastOn(fill.color)
        active -> kb.toolCircleActiveIcon
        else -> kb.toolbarIcon
    }
    // Only the icon carries the accent in the unfilled pill; the label stays
    // the theme's text colour so a wall of coloured words never happens.
    val iconTint = when {
        fill != null -> content
        active -> kb.toolCircleActiveIcon
        else -> paint?.color ?: kb.toolbarIcon
    }
    // A filled pill already wears the gradient, so its icon is flat contrast
    // colour; an unfilled one is where the gradient has somewhere to go.
    val iconBrush = if (fill != null || active || ghost) null else paint?.brush
    val fade = if (ghost) 0.45f else 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PillHeight)
            .clip(shape)
            .then(
                if (backgroundBrush != null) Modifier.background(backgroundBrush, shape)
                else Modifier.background(background, shape),
            )
            .then(
                if (ghost) {
                    Modifier.border(1.dp, kb.toolbarIcon.copy(alpha = 0.35f), shape)
                } else {
                    Modifier
                }
            )
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlotIcon(
            IconSlots.forTool(tool),
            contentDescription = null,
            modifier = Modifier.size(ToolIconSize),
            tint = iconTint.copy(alpha = fade),
            brush = iconBrush,
        )
        Text(
            toolLabel(tool),
            fontSize = labelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = content.copy(alpha = if (ghost) 0.5f else 1f),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        // A pill is a row, so its badge sits at the end beside the chevron
        // rather than under an icon. Same footprint either way.
        if (hint != null) HintBadge(hint, modifier = Modifier.padding(end = 4.dp))
        if (toolOpensScreen(tool)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = content.copy(alpha = if (ghost) 0.3f else 0.6f),
            )
        }
    }
}

/**
 * Which page of the paginated toolbox is in front. Deliberately not tappable:
 * dots this small are a poor target, and the pages are one swipe apart.
 */
@Composable
private fun PageDots(count: Int, current: Int) {
    if (count <= 1) return
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { page ->
            val on = page == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (on) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) kb.toolCircleActiveIcon else kb.toolbarIcon.copy(alpha = 0.3f),
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * Panels that take over the whole keyboard: the toolbar (plus any emoji or
 * symbol row) hides while they're open and the panel absorbs that height.
 * The right fit for tools that want the room and never involve typing or
 * hopping to another tool mid-use — sensors, reference views, converters.
 */
private val FullBleedPanels = setOf(
    PanelMode.OCR, PanelMode.QR_SCAN, PanelMode.CALCULATOR, PanelMode.CURRENCY,
    PanelMode.UNIT_CONVERT, PanelMode.CALENDAR, PanelMode.AI,
    PanelMode.TRANSLATE, PanelMode.WEB_SEARCH, PanelMode.IMAGE_SEARCH,
    PanelMode.DICTIONARY, PanelMode.SYMBOLS, PanelMode.MEDIA_CONTROL,
    PanelMode.APP_LAUNCHER, PanelMode.THEMES, PanelMode.SNIPPETS,
)

/**
 * Whether [panel] takes the whole keyboard right now. Emoji and the media
 * panels (GIF, stickers) are full-bleed by choice rather than by nature:
 * they can pay for the toolbar's row with their own header — category tabs
 * for emoji, the search box for media — so the setting is on by default but
 * can be turned off by anyone who wants the toolbar within reach.
 */
private fun isFullBleedPanel(panel: PanelMode, settings: KeyboardSettings): Boolean = when (panel) {
    PanelMode.EMOJI -> settings.emojiFullBleed
    PanelMode.GIF, PanelMode.STICKER -> settings.mediaFullBleed
    PanelMode.CLIPBOARD -> settings.clipboard.fullBleed
    else -> panel in FullBleedPanels
}

/**
 * Height of everything a full-bleed panel hides (toolbar plus any emoji or
 * symbol row) — the panel absorbs it so opening one never resizes the
 * keyboard window. Shared with the scanner panels, which draw their own
 * chrome instead of using [FullBleedTool].
 */
internal fun fullBleedHiddenRows(state: KeyboardUiState): Dp =
    topBarHeight(state.settings) +
        (if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp) +
        (if (state.settings.symbolRowEnabled) state.settings.rows.symbolRowHeightDp.dp else 0.dp) +
        // The fancy style strip hides under a full-bleed panel like the rows
        // above it, so its height rides along — it depends on the active
        // layout, which is why this takes the state and not just settings.
        (if (fancyStyleFor(state) != null) FancyRowHeight else 0.dp)

/**
 * Chrome for a full-bleed tool: a slim header (back button + tool name)
 * standing in for the hidden toolbar, then the tool filling everything
 * else. The wrapper's height is the key rows plus every row the full-bleed
 * mode hid, so opening one never resizes the keyboard window — the tool
 * gets the reclaimed space instead.
 */
@Composable
internal fun FullBleedTool(
    state: KeyboardUiState,
    title: String,
    onClose: () -> Unit,
    // Grows the keyboard window upward beyond the normal keyboard height —
    // for tools (AI, converters) whose content is worth more vertical room.
    extraHeight: Dp = 0.dp,
    // While the tool's search box is being typed into, the key rows render
    // below the panel; the panel collapses to [compactHeight] so the two
    // fit together (same trick as the media panels' search mode).
    compact: Boolean = false,
    compactHeight: Dp = 132.dp,
    // Fills the header's free width (after the back button + title) with the
    // tool's own controls, so the reclaimed toolbar row does real work.
    // With an empty [title] the actions own the whole row — search bars and
    // tab strips sit right next to the back button.
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    // Off for the panels that are not really full-bleed: the toolbar is still
    // on screen above them and already carries a way back, so a second back
    // button in the header is just a duplicate eating header width.
    showBack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    val height = if (compact) {
        compactHeight
    } else {
        keyRowsHeight(state) + fullBleedHiddenRows(state) + extraHeight
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                ToolCircle(
                    slot = IconSlots.CHROME_PANEL_BACK,
                    description = stringResource(R.string.ime_panel_back_desc),
                    active = false,
                    onClick = onClose,
                )
            }
            if (title.isNotEmpty()) {
                Text(
                    title,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (headerActions != null) {
                if (title.isNotEmpty()) Spacer(Modifier.weight(1f))
                headerActions()
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { content() }
    }
}

/**
 * Toolbar + panels + key rows, wrapped in a Box so the tool-drag ghost can
 * float over everything while the toolbox is open.
 */
@Composable
private fun KeyboardBody(
    state: KeyboardUiState,
    onDismissInlineSuggestions: () -> Unit,
    onPickerDismiss: () -> Unit,
    onSmartAccept: () -> Unit,
    onSmartOpen: () -> Unit,
    onStripOfferAction: (Boolean) -> Unit,
    onToolPrefillConsumed: () -> Unit,
    onHideKeyboard: () -> Unit,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onGesture: (List<GesturePoint>, List<KeyCenter>, Float, String?) -> Unit,
    onGesturePreview: (List<GesturePoint>, List<KeyCenter>, Float) -> Unit,
    onGestureWords: (List<List<GesturePoint>>, List<KeyCenter>, Float) -> Unit,
    onKeyTouch: (Float, Float) -> Unit = { _, _ -> },
    onTouchKeys: (List<KeyCenter>) -> Unit = {},
    onCursorMove: (Int) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onSuggestion: (String) -> Unit,
    onJoinSuggestion: () -> Unit = {},
    onRevisionSuggestion: () -> Unit = {},
    /** A conversion candidate tapped, with its position — see Composer.consumedForIndex. */
    onCandidate: (String, Int) -> Unit = { text, _ -> onSuggestion(text) },
    /** Open the expanded candidate grid. */
    onCandidatesExpand: () -> Unit = {},
    onEmoji: (String) -> Unit,
    onEmojiVariant: (String, String) -> Unit,
    onEmojiFavourite: (String) -> Unit,
    onEmojiSuggestion: (String, Boolean) -> Unit,
    onPunctuation: (String) -> Unit,
    onEmojiQueryTap: () -> Unit,
    onEmojiRecentsClear: () -> Unit,
    onEmojiRecentRemove: (String) -> Unit,
    onEmojiFavouritesReorder: (List<String>) -> Unit,
    onEmojiLongPress: (String) -> Unit,
    onEmojiLongPressEnd: () -> Unit,
    onAnimatedEmojiSend: (String) -> Unit,
    onEmojiStickerSend: (String) -> Unit,
    onEmojiSearchFieldDelete: () -> Unit,
    onTextArt: (String) -> Unit,
    onTextEdit: (TextEditAction) -> Unit,
    onPanelChange: (PanelMode) -> Unit,
    onClipboardItem: (ClipItem) -> Unit,
    onClipboardSticker: (ClipItem) -> Unit,
    onClipboardPin: (ClipItem) -> Unit,
    onClipboardDelete: (ClipItem) -> Unit,
    onClipboardSearchToggle: () -> Unit,
    onClipboardSuggestionDismiss: () -> Unit,
    onClipboardEntity: (ClipEntity) -> Unit,
    onOtpAccept: (NotificationOtp) -> Unit,
    onOtpDismiss: () -> Unit,
    onEmojiRowShown: () -> Unit,
    snippetPanel: SnippetPanelCallbacks,
    onToolTap: (ToolbarTool) -> Unit,
    onToolbarToolsChange: (List<ToolbarTool>) -> Unit,
    onToolboxOrderChange: (List<ToolbarTool>) -> Unit,
    toolHold: ToolHoldCallbacks,
    onToolboxHintDismiss: () -> Unit,
    onWeatherRefresh: () -> Unit,
    onCameraSend: (java.io.File) -> Unit,
    onCameraPermissionRequest: () -> Unit,
    onCalendarPermissionRequest: () -> Unit,
    onScannedInsert: (String) -> Unit,
    onScannedUrlOpen: (String) -> Unit,
    onVoiceToggle: () -> Unit,
    onVoicePermissionRequest: () -> Unit,
    onVoiceUndo: () -> Unit,
    onVoiceModelDownload: () -> Unit,
    onWhisperTranslateToggle: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onVoiceUseSystemEngine: () -> Unit,
    onVoiceRailKey: (VoiceBarAction) -> Unit,
    onMediaPlayPause: () -> Unit,
    onMediaNext: () -> Unit,
    onMediaPrevious: () -> Unit,
    onMediaSeek: (Long) -> Unit,
    onMediaAccessRequest: () -> Unit,
    onMediaResume: () -> Unit,
    onDictionaryLookup: (String) -> Unit,
    onDictionarySearchToggle: () -> Unit,
    onDictionaryInsert: (String) -> Unit,
    onThemeSelect: (String) -> Unit,
    onIconPackSelect: (String) -> Unit,
    onSoundHaptic: (SoundHapticAction) -> Unit,
    onHandwritingStroke: (HwStroke, IntSize) -> Unit,
    onKeyboardHandwritingStroke: (HwStroke, IntSize) -> Unit,
    onHandwritingUndo: () -> Unit,
    onHandwritingDownload: () -> Unit,
    onMediaQueryTap: () -> Unit,
    onMediaRetry: () -> Unit,
    onGifSelect: (GifItem) -> Unit,
    onGifSourceSelect: (GifSource) -> Unit,
    onGifCategorySelect: (String) -> Unit,
    onMediaLongPress: (GifItem) -> Unit,
    onStickerPackFilter: (String?) -> Unit,
    onStickerSaveToPack: (GifItem, String?) -> Unit,
    onMediaCopy: (GifItem) -> Unit,
    onMediaReport: (GifItem) -> Unit,
    onMediaActionDismiss: () -> Unit,
    onWebResult: (WebResult) -> Unit,
    onWebResultOpen: (WebResult) -> Unit,
    onImageResult: (ImageResult) -> Unit,
    onImageResultLink: (ImageResult) -> Unit,
    onTranslateTarget: (String) -> Unit,
    onTranslateReplace: () -> Unit,
    onTranslateInsert: () -> Unit,
    onGrammarFix: (GrammarLint, GrammarFix) -> Unit,
    onGrammarFixAll: () -> Unit,
    onGrammarDismiss: (GrammarLint) -> Unit,
    onGrammarDialect: (GrammarDialect) -> Unit,
    onGrammarFocus: (GrammarLint) -> Unit,
    onWikiOpen: (String) -> Unit,
    onWikiBack: () -> Unit,
    onWikiLoadLinks: () -> Unit,
    onWikiLoadFull: () -> Unit,
    onSymbolInsert: (String) -> Unit,
    onSymbolSetSelect: (String) -> Unit,
    onFancyStyleSelect: (String) -> Unit,
    onModeSelect: (String?) -> Unit,
    onToolInsert: (String) -> Unit,
    converter: ConverterCallbacks,
    onPwSetting: (PwSettingAction) -> Unit,
    onTypingTestAction: (TypingTestAction) -> Unit,
    onQrSend: () -> Unit,
    onAiAction: (com.wasimaster.wmkeyboard.core.tools.AiActionSpec) -> Unit,
    onAiReplace: () -> Unit,
    onAiInsert: () -> Unit,
    onAiRetry: () -> Unit,
    onAiRunCustom: () -> Unit,
    onAiPickModel: (com.wasimaster.wmkeyboard.core.settings.AiProvider, String?) -> Unit,
    onAiToggleStripMarkdown: () -> Unit,
    onAiSetShowDiff: (Boolean) -> Unit,
    onAiReport: () -> Unit,
    onOpenToolSettings: (ToolbarTool) -> Unit,
    onOpenRoute: (String) -> Unit = {},
    onPluginOpen: (String) -> Unit = {},
    onPluginBack: () -> Unit = {},
    onPluginEvent: (PluginEvent) -> Unit = {},
    onPluginInputFocus: (String?) -> Unit = {},
    onPluginPaste: (String) -> Unit = {},
    onPluginCopy: (String) -> Unit = {},
    launcher: LauncherPanelCallbacks = LauncherPanelCallbacks(),
) {
    val drag = remember { ToolDragController() }
    // Mirror the drag's view of the bar when the tools read RTL, then flip the
    // committed order back to storage order — the bar is drawn reversed but the
    // saved list is always left-to-right.
    val readsRtl = toolbarReadsRtl(state)
    drag.currentTools =
        if (readsRtl) state.settings.toolbarTools.reversed() else state.settings.toolbarTools
    drag.onCommit =
        if (readsRtl) { tools -> onToolbarToolsChange(tools.reversed()) } else onToolbarToolsChange
    drag.onOrderCommit = onToolboxOrderChange
    drag.onSnap = LocalKeyPressFeedback.current
    drag.onOpenSettings = toolHold.onSettings
    drag.onSelectionHold = toolHold.onSelectionHold
    // A remapped hold runs the bound tool through the service's own dispatcher
    // rather than [onToolTap]: that one refuses any tool the toolbar does not
    // list, and the tool the user bound a hold to is very often one they never
    // put on the bar — that being the point of binding it (#31).
    drag.onHoldAction = toolHold.onHoldAction
    var bodyOrigin by remember { mutableStateOf(Offset.Zero) }
    // Kept alongside the origin so the drag-scope pill can park itself against
    // the bottom of the keyboard when the toolbar end is where the finger is.
    var bodyHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                bodyOrigin = it.positionInRoot()
                bodyHeightPx = it.size.height
                drag.bodyCoords = it
            },
    ) {
        Column {
            // The dedicated always-on emoji row (Gboard style) sits between
            // the strip and the keys — or on top of everything, per setting;
            // the emoji panel already is emojis, so it yields there.
            // Full-bleed panels swallow the toolbar row (and any emoji or
            // symbol row) too: the tool absorbs those rows' height, so it
            // gets every pixel the keyboard owns. OCR draws its own chrome;
            // the rest get the [FullBleedTool] back-header wrapper.
            // Lock-screen privacy: with the setting on and the keyguard up, drop
            // the whole top strip (suggestions + toolbar, so the clipboard tool
            // and paste chip go with it) and block the clipboard panel, keeping
            // copied text and pinned tools off a screen anyone can wake.
            val lockHidden = barLockHidden(state)
            // Same deal for the clipboard panel's search bar: the panel shrinks
            // to its search field plus a couple of result rows and the keys come
            // back underneath, so the query is actually typeable. Not while the
            // lock screen has blocked the panel — the keys are already up in its
            // place there, and a second set would double them.
            val clipboardSearching = barClipboardSearching(state)
            // The searching clipboard panel steps out of full-bleed: search
            // already owns the toolbar row's height, and the keys below need
            // their usual rows around them.
            val fullBleed = barFullBleed(state)
            // The symbols panel already is special characters — the row
            // would be redundant there, so it yields like the emoji row.
            val showEmojiRow = emojiRowVisible(state)
            val showSymbolRow = symbolRowVisible(state)
            // The rows stack in the user's chosen order (Rows settings).
            // While an emoji search is typing, the toolbar is dead weight —
            // hide it and let the panel spend the height on result rows.
            val emojiSearching = state.panel == PanelMode.EMOJI && state.emojiSearchActive
            // Tools on a row of their own (see [ToolbarPlacement]). The row is
            // drawn here, above the strip, rather than inside TopBar: the two are
            // then plain siblings, each with its own height, and the strip's
            // surface-swap machinery is switched off instead of being taught to
            // draw two things at once.
            //
            // ON_DEMAND_ROW is the same row behind the strip's chevron, so the
            // open/closed flag lives out here where both can reach it. It resets
            // whenever the placement changes, or a row opened under one
            // arrangement would still be open under another that has no way to
            // close it.
            val placement = state.settings.toolbarBehavior.placement
            var toolsRowOpen by remember(placement) { mutableStateOf(false) }
            val toolsRowShown = placement.isOwnRow &&
                (placement == ToolbarPlacement.ALWAYS_ROW || toolsRowOpen) &&
                state.settings.toolbarBehavior.enabled &&
                !fullBleed && !emojiSearching && !clipboardSearching && !lockHidden
            for (row in state.settings.barOrder) {
                when (row) {
                    // Disabling the toolbar drops the whole strip — suggestions
                    // and tools alike — so the keys claim its height.
                    BarRow.TOPBAR -> if (
                        state.settings.toolbarBehavior.enabled && !fullBleed &&
                        !emojiSearching && !clipboardSearching && !lockHidden
                    ) {
                        if (toolsRowShown) {
                            ToolsRow(state, onPanelChange, onToolTap, drag)
                        }
                        TopBar(
                            state,
                            toolsRowOpen = toolsRowOpen,
                            onToolsRowToggle = { toolsRowOpen = !toolsRowOpen },
                            onSuggestion = onSuggestion,
                            onJoinSuggestion = onJoinSuggestion,
                            onRevisionSuggestion = onRevisionSuggestion,
                            onCandidate = onCandidate,
                            onCandidatesExpand = onCandidatesExpand,
                            onEmoji = onEmoji,
                            onEmojiSuggestion = onEmojiSuggestion,
                            onPunctuation = onPunctuation,
                            onPanelChange = onPanelChange,
                            onToolTap = onToolTap,
                            drag = drag,
                            onVoiceToggle = onVoiceToggle,
                            onVoiceUndo = onVoiceUndo,
                            onVoicePermissionRequest = onVoicePermissionRequest,
                            onOpenVoiceSettings = onOpenVoiceSettings,
                            onVoiceCollapse = {
                                onVoiceRailKey(
                                    VoiceBarAction.SwitchSurface(VoiceBarSettings.MODE_BAR),
                                )
                            },
                            onDismissInlineSuggestions = onDismissInlineSuggestions,
                            onSmartAccept = onSmartAccept,
                            onSmartOpen = onSmartOpen,
                            onStripOfferAction = onStripOfferAction,
                            onClipboardSuggestion = onClipboardItem,
                            onClipboardSuggestionDismiss = onClipboardSuggestionDismiss,
                            onClipboardEntity = onClipboardEntity,
                            onOtpAccept = onOtpAccept,
                            onOtpDismiss = onOtpDismiss,
                            onEmojiRowShown = onEmojiRowShown,
                            onSwipeDownHide = onHideKeyboard,
                        )
                    }
                    BarRow.EMOJI -> if (showEmojiRow) {
                        EmojiBarStrip(
                            state = state,
                            onEmoji = onEmoji,
                            onOpenPanel = { onPanelChange(PanelMode.EMOJI) },
                        )
                    }
                    BarRow.SYMBOL -> if (showSymbolRow) {
                        SymbolRowStrip(
                            state = state,
                            onInsert = onToolInsert,
                            onSetSelect = onSymbolSetSelect,
                        )
                    }
                }
            }
            // The Fancy Text style strip rides with its layout rather than
            // with barOrder (a serialized enum an older build must still
            // decode), so it renders after the ordered rows, closest to the
            // keys whose glyphs it changes.
            val fancyStyle = fancyStyleFor(state)
            if (!fullBleed && fancyStyle != null) {
                FancyStyleStrip(
                    state = state,
                    active = fancyStyle,
                    onStyleSelect = onFancyStyleSelect,
                )
            }
            // Deliberately NOT animated. A fade here was tried and reverted:
            // an alpha on this subtree covers the key rows as well as the
            // panels, so every panel close briefly rendered a translucent
            // keyboard with the app's own text field showing through it, and
            // the alpha had to be applied a frame after the new content was
            // already on screen, which flashed it at full strength first.
            // Animating the swap needs the panels to be layered rather than
            // exchanged; until then the cut is the honest option.
        // A panel that has left the composition must stop claiming the focus
        // ring: its counts and its activate lambdas outlive it by a frame
        // otherwise, and the service would act on an item nothing is drawing.
        val focusController = LocalPanelFocus.current
        DisposableEffect(state.panel) {
            onDispose { focusController.reset() }
        }
        when (if (lockHidden && state.panel == PanelMode.CLIPBOARD) PanelMode.NONE else state.panel) {
                PanelMode.EMOJI -> EmojiPanel(
                    state, onEmoji, onEmojiVariant, onEmojiFavourite, onEmojiQueryTap, onEmojiRecentsClear,
                    onRecentRemove = onEmojiRecentRemove,
                    onFavouritesReorder = onEmojiFavouritesReorder,
                    onLongPress = onEmojiLongPress,
                    onLongPressEnd = onEmojiLongPressEnd,
                    onAnimatedSend = onAnimatedEmojiSend,
                    onStickerSend = onEmojiStickerSend,
                    onSearchFieldDelete = onEmojiSearchFieldDelete,
                    onTextArt = onTextArt,
                    onKey = onKey,
                    // Toggling the open panel closes it — back to the keys.
                    onClose = { onPanelChange(PanelMode.EMOJI) },
                )
                PanelMode.CLIPBOARD -> if (
                    state.settings.clipboard.fullBleed && !state.clipboardSearchActive
                ) {
                    // Full-bleed (opt-in): the toolbar row becomes the back
                    // header and the reclaimed rows show more history cards.
                    // Search steps back to the plain panel — its collapsed
                    // form already shares the screen with the keys.
                    FullBleedTool(
                        state, stringResource(R.string.ime_tool_clipboard),
                        onClose = { onPanelChange(PanelMode.CLIPBOARD) },
                    ) {
                        ClipboardPanel(
                            state, onClipboardItem, onClipboardSticker, onClipboardPin,
                            onClipboardDelete,
                            onClipboardSearchToggle = onClipboardSearchToggle,
                            onClipboardEntity = onClipboardEntity,
                            onKey = onKey,
                            onClose = { onPanelChange(PanelMode.CLIPBOARD) },
                            fullBleed = true,
                        )
                    }
                } else {
                    ClipboardPanel(
                        state, onClipboardItem, onClipboardSticker, onClipboardPin,
                        onClipboardDelete,
                        onClipboardSearchToggle = onClipboardSearchToggle,
                        onClipboardEntity = onClipboardEntity,
                        onKey = onKey,
                        // Toggling the open panel closes it — back to the keys.
                        onClose = { onPanelChange(PanelMode.CLIPBOARD) },
                    )
                }
                // The snippet cards are two columns of wrapped text, so the
                // rows the toolbar gives up are worth a whole extra pair of
                // them. The editor lives in settings, so the header's action
                // slot carries the gear the panel used to draw itself.
                //
                // Inside a folder the header carries that folder's name and its
                // back button goes up a level rather than out of the panel —
                // the same back the hardware key and the system key perform.
                PanelMode.SNIPPETS -> {
                    val openFolder = state.openSnippetFolder()
                    FullBleedTool(
                        state,
                        openFolder?.name ?: stringResource(R.string.ime_tool_snippets),
                        onClose = {
                            if (openFolder == null) {
                                onPanelChange(PanelMode.SNIPPETS)
                            } else {
                                snippetPanel.onFolderOpen(null)
                            }
                        },
                        headerActions = {
                            ToolCircle(
                                slot = IconSlots.forTool(ToolbarTool.SETTINGS),
                                description = stringResource(R.string.ime_snippets_settings_desc),
                                active = false,
                                onClick = { onOpenToolSettings(ToolbarTool.SNIPPETS) },
                            )
                        },
                    ) {
                        SnippetsPanel(
                            state, snippetPanel,
                            onOpenSettings = { onOpenToolSettings(ToolbarTool.SNIPPETS) },
                        )
                    }
                }
                PanelMode.TEXT_EDIT -> TextEditPanel(state, onTextEdit)
                PanelMode.TOOLBOX -> ToolboxPanel(state, onToolTap, onToolboxHintDismiss, drag)
                // Regular panels (toolbar stays visible): the sensors read
                // fine at keyboard height and the toolbar keeps tool-hopping
                // one tap away.
                PanelMode.COMPASS -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(keyRowsHeight(state)),
                ) { CompassPanel(state) }
                PanelMode.LEVEL -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(keyRowsHeight(state)),
                ) { LevelPanel(state) }
                PanelMode.MOON_PHASE -> MoonPhasePanel(state)
                PanelMode.WEATHER -> WeatherPanel(
                    state = state,
                    onRefresh = onWeatherRefresh,
                    // The prompt is about the weather location, so land on the
                    // weather tool's own page rather than the settings root.
                    onOpenSettings = { onOpenToolSettings(ToolbarTool.WEATHER) },
                )
                // The month grid and the selected day's event list are fighting
                // over the same rows, so the calendar buys itself another band
                // of height the way the grammar strip and the typing test do.
                PanelMode.CALENDAR -> FullBleedTool(
                    state, stringResource(R.string.ime_tool_calendar),
                    onClose = { onPanelChange(PanelMode.CALENDAR) },
                    extraHeight = 140.dp,
                ) {
                    CalendarPanel(
                        state,
                        onRequestPermission = onCalendarPermissionRequest,
                        onPrefillConsumed = onToolPrefillConsumed,
                    )
                }
                PanelMode.THEMES -> ThemesPanel(
                    state,
                    onThemeSelect,
                    onIconPackSelect,
                    onOpenRoute = onOpenRoute,
                    onClose = { onPanelChange(PanelMode.THEMES) },
                )
                PanelMode.SOUND_HAPTICS -> SoundHapticsPanel(state, onSoundHaptic)
                PanelMode.NUMPAD -> NumpadPanel(state, onText, onKey)
                PanelMode.CANDIDATES -> CandidateGridPanel(state, onCandidate)
                PanelMode.HANDWRITING -> if (BuildConfig.ENABLE_ML_KIT_HANDWRITING) {
                    HandwritingPanel(
                        state = state,
                        onStroke = onHandwritingStroke,
                        onUndoStroke = onHandwritingUndo,
                        onDownloadModel = onHandwritingDownload,
                        onKey = onKey,
                        onLayoutSelect = onLayoutSelect,
                        onClose = { onPanelChange(PanelMode.HANDWRITING) },
                    )
                } else {
                    onPanelChange(PanelMode.SNIPPETS)
                }
                PanelMode.CAMERA -> CameraPanel(
                    state = state,
                    onSend = onCameraSend,
                    onRequestPermission = onCameraPermissionRequest,
                    // Toggling the open panel closes it.
                    onClose = { onPanelChange(PanelMode.CAMERA) },
                )
                PanelMode.OCR -> if (BuildConfig.ENABLE_ML_KIT_SCANNERS) {
                    OcrPanel(
                        state = state,
                        onInsert = onScannedInsert,
                        onRequestPermission = onCameraPermissionRequest,
                        onClose = { onPanelChange(PanelMode.OCR) },
                    )
                } else {
                    onPanelChange(PanelMode.SNIPPETS)
                }
                PanelMode.QR_SCAN -> if (BuildConfig.ENABLE_ML_KIT_SCANNERS) {
                    QrScanPanel(
                        state = state,
                        onInsert = onScannedInsert,
                        onOpenUrl = onScannedUrlOpen,
                        onRequestPermission = onCameraPermissionRequest,
                        onClose = { onPanelChange(PanelMode.QR_SCAN) },
                    )
                } else {
                    onPanelChange(PanelMode.SNIPPETS)
                }
                PanelMode.VOICE -> VoicePanel(
                    state = state,
                    onToggle = onVoiceToggle,
                    onUndo = onVoiceUndo,
                    onRequestPermission = onVoicePermissionRequest,
                    onDownloadModel = onVoiceModelDownload,
                    onToggleTranslate = onWhisperTranslateToggle,
                    onOpenVoiceSettings = onOpenVoiceSettings,
                    onUseSystemEngine = onVoiceUseSystemEngine,
                    onRailKey = onVoiceRailKey,
                    onLayoutSelect = onLayoutSelect,
                    onClose = { onPanelChange(PanelMode.VOICE) },
                )
                PanelMode.PLUGINS -> FullBleedTool(
                    state,
                    title = (state.plugins as? PluginPanelUi.Running)?.plugin?.name
                        ?: stringResource(R.string.ime_tool_plugins),
                    // Back out one level at a time: from a running plugin to the
                    // installed list, and only from the list itself out of the
                    // panel. Closing outright left no way back to the list short
                    // of reopening the tool.
                    onClose = {
                        if (state.plugins is PluginPanelUi.Running) {
                            onPluginBack()
                        } else {
                            onPanelChange(PanelMode.PLUGINS)
                        }
                    },
                    // While a plugin's text box has the keys, the rows render
                    // below and the panel gives up the room for them. Taller
                    // than the search panels' default: a plugin draws its own
                    // header row of tabs above the box, and at 132dp the box
                    // being typed into was itself the thing that got cut off.
                    compact = state.pluginTypingActive,
                    compactHeight = 188.dp,
                ) {
                    PluginPanel(
                        state = state,
                        onOpenPlugin = onPluginOpen,
                        onEvent = onPluginEvent,
                        onInputFocus = onPluginInputFocus,
                        onToolInsert = onToolInsert,
                        onCopy = onPluginCopy,
                        onPaste = onPluginPaste,
                        onManage = { onOpenRoute("plugins") },
                    )
                }
                PanelMode.APP_LAUNCHER -> FullBleedTool(
                    state, title = "",
                    // Back out one level at a time, like the plugins panel:
                    // from an app's activity list to the grid, then out.
                    onClose = {
                        if (state.launcherDetail != null) launcher.onDetailClose()
                        else onPanelChange(PanelMode.APP_LAUNCHER)
                    },
                    compact = state.mediaSearchActive,
                    headerActions = {
                        MediaHeaderSearchBar(
                            state = state,
                            placeholder = stringResource(R.string.ime_launcher_search_hint),
                            onQueryTap = onMediaQueryTap,
                        )
                    },
                ) {
                    AppLauncherPanel(
                        state = state,
                        callbacks = launcher,
                        onQueryTap = onMediaQueryTap,
                    )
                }
                PanelMode.MEDIA_CONTROL -> FullBleedTool(
                    state,
                    title = stringResource(R.string.ime_tool_media_control),
                    onClose = { onPanelChange(PanelMode.MEDIA_CONTROL) },
                ) {
                    MediaControlPanel(
                        state = state,
                        onPlayPause = onMediaPlayPause,
                        onNext = onMediaNext,
                        onPrevious = onMediaPrevious,
                        onSeek = onMediaSeek,
                        onRequestAccess = onMediaAccessRequest,
                        onResume = onMediaResume,
                    )
                }
                PanelMode.DICTIONARY -> FullBleedTool(
                    state, title = "",
                    onClose = { onPanelChange(PanelMode.DICTIONARY) },
                    // While the query types on the key rows below, only the
                    // header (with its search bar) needs to stay visible.
                    compact = state.dictionarySearchActive,
                    compactHeight = 44.dp,
                    headerActions = {
                        DictionaryHeaderSearchBar(
                            state = state,
                            onSearchToggle = onDictionarySearchToggle,
                            onLookup = onDictionaryLookup,
                        )
                    },
                ) {
                    DictionaryPanel(
                        state = state,
                        onLookup = onDictionaryLookup,
                        onInsert = onDictionaryInsert,
                    )
                }
                PanelMode.TRANSLATE -> FullBleedTool(
                    state, title = "",
                    onClose = { onPanelChange(PanelMode.TRANSLATE) },
                    compact = state.mediaSearchActive,
                    // Translations run long; give the result more room to breathe
                    // than the media panels' default compact height.
                    compactHeight = 180.dp,
                    headerActions = {
                        PanelFocusTarget(
                            panel = PanelMode.TRANSLATE,
                            region = FocusRegion.SEARCH,
                            count = 1,
                            columns = 1,
                            onActivate = { onMediaQueryTap() },
                        )
                        MediaHeaderSearchBar(
                            state = state,
                            placeholder = stringResource(R.string.ime_translate_hint),
                            activePlaceholder = stringResource(R.string.ime_translate_hint),
                            onQueryTap = onMediaQueryTap,
                            focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
                        )
                    },
                ) {
                    TranslatePanel(
                        state = state,
                        onTarget = onTranslateTarget,
                        onReplace = onTranslateReplace,
                        onInsert = onTranslateInsert,
                    )
                }
                PanelMode.GRAMMAR -> if (BuildConfig.ENABLE_GRAMMAR) {
                    GrammarPanel(
                        state = state,
                        onFix = onGrammarFix,
                        onFixAll = onGrammarFixAll,
                        onDismiss = onGrammarDismiss,
                        onDialect = onGrammarDialect,
                        onFocus = onGrammarFocus,
                    )
                } else {
                    onPanelChange(PanelMode.SNIPPETS)
                }
                PanelMode.GIF, PanelMode.STICKER -> {
                    val stickers = state.panel == PanelMode.STICKER
                    if (state.settings.mediaFullBleed) {
                        // Search moves up into the reclaimed toolbar row, next
                        // to the back button — same shape as the dictionary.
                        FullBleedTool(
                            state,
                            title = "",
                            onClose = { onPanelChange(state.panel) },
                            // Search collapses the panel so the key rows fit
                            // below it, keeping a band of live results up.
                            compact = state.mediaSearchActive,
                            headerActions = {
                                GifHeaderSearchBar(state, stickers, onMediaQueryTap)
                            },
                        ) {
                            GifPanel(
                                state = state,
                                stickers = stickers,
                                onQueryTap = onMediaQueryTap,
                                onRetry = onMediaRetry,
                                onSelect = onGifSelect,
                                onSourceSelect = onGifSourceSelect,
                                onOpenToolSettings = onOpenToolSettings,
                                fullBleed = true,
                                onCategorySelect = onGifCategorySelect,
                                onLongPress = onMediaLongPress,
                                onPackFilter = onStickerPackFilter,
                                onSaveToPack = onStickerSaveToPack,
                                onCopy = onMediaCopy,
                                onReport = onMediaReport,
                                onDismissAction = onMediaActionDismiss,
                                onOpenRoute = onOpenRoute,
                            )
                        }
                    } else {
                        GifPanel(
                            state = state,
                            stickers = stickers,
                            onQueryTap = onMediaQueryTap,
                            onRetry = onMediaRetry,
                            onSelect = onGifSelect,
                            onSourceSelect = onGifSourceSelect,
                            onOpenToolSettings = onOpenToolSettings,
                            onCategorySelect = onGifCategorySelect,
                            onLongPress = onMediaLongPress,
                            onPackFilter = onStickerPackFilter,
                            onSaveToPack = onStickerSaveToPack,
                            onCopy = onMediaCopy,
                            onReport = onMediaReport,
                            onDismissAction = onMediaActionDismiss,
                            onOpenRoute = onOpenRoute,
                        )
                    }
                }
                PanelMode.WEB_SEARCH -> FullBleedTool(
                    state, title = "",
                    onClose = { onPanelChange(PanelMode.WEB_SEARCH) },
                    compact = state.mediaSearchActive,
                    headerActions = {
                        MediaHeaderSearchBar(
                            state = state,
                            placeholder = stringResource(R.string.ime_web_search_hint),
                            onQueryTap = onMediaQueryTap,
                            attribution = stringResource(R.string.ime_search_attribution_brave)
                                .takeIf { ToolApiKeys.hasSearchProvider(state.settings) },
                        )
                    },
                ) {
                    WebSearchPanel(
                        state = state,
                        onRetry = onMediaRetry,
                        onResult = onWebResult,
                        onOpen = onWebResultOpen,
                        onOpenToolSettings = onOpenToolSettings,
                    )
                }
                PanelMode.IMAGE_SEARCH -> FullBleedTool(
                    state, title = "",
                    onClose = { onPanelChange(PanelMode.IMAGE_SEARCH) },
                    compact = state.mediaSearchActive,
                    headerActions = {
                        MediaHeaderSearchBar(
                            state = state,
                            placeholder = stringResource(R.string.ime_image_search_hint),
                            onQueryTap = onMediaQueryTap,
                            attribution = stringResource(R.string.ime_search_attribution_brave)
                                .takeIf { ToolApiKeys.hasSearchProvider(state.settings) },
                        )
                    },
                ) {
                    ImageSearchPanel(
                        state = state,
                        onRetry = onMediaRetry,
                        onResult = onImageResult,
                        onResultLink = onImageResultLink,
                        onOpenToolSettings = onOpenToolSettings,
                    )
                }
                PanelMode.WIKIPEDIA -> WikipediaPanel(
                    state = state,
                    onQueryTap = onMediaQueryTap,
                    onRetry = onMediaRetry,
                    onOpen = onWikiOpen,
                    onBack = onWikiBack,
                    onLoadLinks = onWikiLoadLinks,
                    onLoadFull = onWikiLoadFull,
                    onInsert = onToolInsert,
                )
                PanelMode.SYMBOLS -> {
                    // Category selection lives up here so the header chips
                    // and the grid share it.
                    val recents = state.settings.symbolRecents
                    // Tracked by id, never by the drawn name: the chips resolve
                    // their own wording, and a translated name would not match.
                    var symbolCategory by rememberSaveable(recents.isNotEmpty()) {
                        mutableStateOf(
                            if (recents.isNotEmpty()) SYMBOL_RECENTS_ID
                            else SymbolCatalog.categories.first().id
                        )
                    }
                    FullBleedTool(
                        state, title = "",
                        onClose = { onPanelChange(PanelMode.SYMBOLS) },
                        headerActions = {
                            SymbolCategoryChips(
                                state = state,
                                selected = symbolCategory,
                                onSelect = { symbolCategory = it },
                            )
                        },
                    ) {
                        SymbolsPanel(
                            state,
                            onSymbolInsert,
                            symbolCategory,
                            onSelectCategory = { symbolCategory = it },
                        )
                    }
                }
                PanelMode.CALCULATOR -> FullBleedTool(
                    state, stringResource(R.string.ime_tool_calculator),
                    onClose = { onPanelChange(PanelMode.CALCULATOR) },
                ) { CalculatorPanel(state, onToolInsert, converter) }
                PanelMode.UNIT_CONVERT -> FullBleedTool(
                    state, stringResource(R.string.ime_unit_convert_title),
                    onClose = { onPanelChange(PanelMode.UNIT_CONVERT) },
                    extraHeight = 120.dp,
                ) { UnitConverterPanel(state, onToolInsert, converter, onToolPrefillConsumed) }
                PanelMode.CURRENCY -> FullBleedTool(
                    state, stringResource(R.string.ime_tool_currency),
                    onClose = { onPanelChange(PanelMode.CURRENCY) },
                    extraHeight = 120.dp,
                ) {
                    CurrencyPanel(
                        state = state,
                        callbacks = converter,
                        onInsert = onToolInsert,
                        onPrefillConsumed = onToolPrefillConsumed,
                    )
                }
                PanelMode.QR_GEN -> QrGeneratorPanel(state, onQrSend)
                PanelMode.PASSWORD_GEN -> FullBleedTool(
                    state, title = "",
                    onClose = { onPanelChange(PanelMode.PASSWORD_GEN) },
                    // Not full-bleed: the toolbar stays reachable above the
                    // panel, so it collapses to the key-rows height instead of
                    // swallowing the toolbar's rows.
                    compact = true,
                    compactHeight = keyRowsHeight(state),
                    // The toolbar above the panel already has the way back.
                    showBack = false,
                    headerActions = {
                        val passphraseMode = state.settings.passwordGenerator.pwPassphraseMode
                        // The ring's CHIPS region: the two mode tabs.
                        PanelFocusTarget(
                            panel = PanelMode.PASSWORD_GEN,
                            region = FocusRegion.CHIPS,
                            count = 2,
                            columns = 2,
                        ) { index ->
                            onPwSetting(PwSettingAction.PassphraseMode(index == 1))
                        }
                        val focusedTab = state.focusedIndex(FocusRegion.CHIPS)
                        Spacer(Modifier.width(4.dp))
                        ToolPanelChip(
                            stringResource(R.string.ime_tool_password_gen),
                            selected = !passphraseMode,
                            modifier = Modifier.focusRing(focusedTab == 0),
                        ) {
                            onPwSetting(PwSettingAction.PassphraseMode(false))
                        }
                        Spacer(Modifier.width(6.dp))
                        ToolPanelChip(
                            stringResource(R.string.ime_password_tab_passphrase),
                            selected = passphraseMode,
                            modifier = Modifier.focusRing(focusedTab == 1),
                        ) {
                            onPwSetting(PwSettingAction.PassphraseMode(true))
                        }
                        Spacer(Modifier.weight(1f))
                    },
                ) { PasswordPanel(state, onPwSetting, onToolInsert) }
                PanelMode.TYPING_TEST -> FullBleedTool(
                    state = state,
                    title = stringResource(R.string.ime_tool_typing_test),
                    onClose = { onPanelChange(PanelMode.TYPING_TEST) },
                    // A running test shares the window with the key rows —
                    // the user is typing on them — so the panel collapses
                    // the way the media search boxes do. The results screen
                    // needs no keys and takes the full height back.
                    compact = state.typingTest.result == null,
                    compactHeight = 156.dp,
                    headerActions = {
                        typingHeaderBest(LocalContext.current, state.settings)?.let { best ->
                            Text(
                                best,
                                color = LocalKbTheme.current.secondaryText,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // Only on the results screen: during a run the keys
                        // *are* the test, and an arrow summoning a ring there
                        // would score as a miss.
                        PanelFocusTarget(
                            panel = PanelMode.TYPING_TEST,
                            region = FocusRegion.ACTIONS,
                            count = if (state.typingTest.result != null) 1 else 0,
                            columns = 1,
                        ) { onTypingTestAction(TypingTestAction.Restart) }
                        ToolPanelChip(
                            stringResource(R.string.ime_typing_test_restart),
                            modifier = Modifier.focusRing(
                                state.focusedIndex(FocusRegion.ACTIONS) == 0 &&
                                    state.typingTest.result != null,
                            ),
                        ) {
                            onTypingTestAction(TypingTestAction.Restart)
                        }
                    },
                ) { TypingTestPanel(state, onTypingTestAction) }
                PanelMode.AI -> FullBleedTool(
                    state = state,
                    title = stringResource(R.string.ime_tool_ai),
                    onClose = { onPanelChange(PanelMode.AI) },
                    // Reasoning models stream their think block into the same
                    // box as the answer, so that mode — and only that mode —
                    // needs the taller window; otherwise the panel stays at
                    // the normal keyboard height.
                    extraHeight = if (state.settings.ai.showThinking) 160.dp else 0.dp,
                    // The Custom instruction types on the key rows, so the
                    // panel collapses to leave room for them below.
                    compact = state.aiCustomInputActive,
                    compactHeight = 132.dp,
                    headerActions = {
                        val ai = state.ai
                        val ready = ai is AiUi.Ready && !ai.generating
                        // The ring's ACTIONS region: Replace/Insert/Retry when
                        // there is a result, always the settings circle last.
                        PanelFocusTarget(
                            panel = PanelMode.AI,
                            region = FocusRegion.ACTIONS,
                            count = if (ready) 4 else 1,
                            columns = if (ready) 4 else 1,
                        ) { index ->
                            when {
                                !ready || index == 3 -> onOpenToolSettings(ToolbarTool.AI)
                                index == 0 -> onAiReplace()
                                index == 1 -> onAiInsert()
                                index == 2 -> onAiRetry()
                            }
                        }
                        val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
                        if (ready) {
                            ToolPanelChip(
                                stringResource(R.string.ime_ai_replace),
                                selected = true,
                                modifier = Modifier.focusRing(focusedAction == 0),
                            ) { onAiReplace() }
                            Spacer(Modifier.width(5.dp))
                            ToolPanelChip(
                                stringResource(R.string.ime_ai_insert),
                                modifier = Modifier.focusRing(focusedAction == 1),
                            ) { onAiInsert() }
                            Spacer(Modifier.width(5.dp))
                            ToolPanelChip(
                                "↻",
                                modifier = Modifier.focusRing(focusedAction == 2),
                            ) { onAiRetry() }
                            Spacer(Modifier.width(5.dp))
                        }
                        ToolCircle(
                            slot = IconSlots.forTool(ToolbarTool.SETTINGS),
                            description = stringResource(R.string.ime_ai_settings_desc),
                            active = false,
                            modifier = Modifier.focusRing(
                                focusedAction == if (ready) 3 else 0,
                                CircleShape,
                            ),
                        ) { onOpenToolSettings(ToolbarTool.AI) }
                    },
                ) {
                    AiPanel(
                        state = state,
                        onAction = onAiAction,
                        onRetry = onAiRetry,
                        onRunCustom = onAiRunCustom,
                        onPickModel = onAiPickModel,
                        onToggleStripMarkdown = onAiToggleStripMarkdown,
                        onSetShowDiff = onAiSetShowDiff,
                        onReport = onAiReport,
                        onOpenToolSettings = onOpenToolSettings,
                    )
                }
                PanelMode.MODES -> ModesPanel(
                    state, onModeSelect,
                    onOpenSettings = { onOpenToolSettings(ToolbarTool.MODES) },
                )
                // With a hardware keyboard and toolbar-only mode on, the keys
                // step aside and just the toolbar remains — tools stay one tap
                // away while the physical keyboard does the typing.
                PanelMode.NONE -> if (
                    !(state.hardwareKeyboardPresent && state.settings.toolbarBehavior.onlyWithHardwareKeyboard)
                ) {
                    KeyRows(
                        state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect,
                        onGestureWords = onGestureWords,
                        onKeyboardHandwritingStroke = onKeyboardHandwritingStroke,
                        onKeyTouch = onKeyTouch,
                        onTouchKeys = onTouchKeys,
                    )
                }
            }
            // In emoji search mode the letters stay visible for typing the query.
            if (state.panel == PanelMode.EMOJI && state.emojiSearchActive) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
            // The AI Custom instruction types on the key rows under its panel.
            if (state.aiCustomInputActive) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
            // Same for a dictionary search: the query types on the key rows.
            if (state.panel == PanelMode.DICTIONARY && state.dictionarySearchActive) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
            // And for the clipboard panel's search bar. Without this the search
            // pill captured the keystrokes with no keys on screen to make them
            // with, so the filter could never be typed.
            if (clipboardSearching) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
            // And for a plugin's own text box, which is the same trap again: the
            // panel already collapses to make room (FullBleedTool compact), and
            // every keystroke is routed into the box, so leaving the rows out
            // left a focused field with nothing on screen to type into it.
            if (state.pluginTypingActive) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
            // Same for a media panel's search box (translate is one now —
            // its query types into the panel), and always under the grammar
            // strip (it follows the field live).
            // A typing test is nothing but the key rows — they are what the
            // user is being timed on. They go away on the results screen.
            if ((state.panel.hasMediaSearch && state.mediaSearchActive) ||
                state.panel == PanelMode.GRAMMAR ||
                state.typingTestActive
            ) {
                KeyRows(state, onKey, onText, onGesture, onGesturePreview, onCursorMove, onLayoutSelect)
            }
        }
        drag.dragging?.let { tool ->
            val kb = LocalKbTheme.current
            val ghost = drag.position - bodyOrigin
            Box(
                modifier = Modifier
                    .offset { IntOffset((ghost.x - 22.dp.toPx()).roundToInt(), (ghost.y - 22.dp.toPx()).roundToInt()) }
                    .size(44.dp)
                    .background(kb.toolCircleActive, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SlotIcon(
                    IconSlots.forTool(tool),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = kb.toolCircleActiveIcon,
                )
            }
        }
        // Names the scope a tool drag lands in — a mode's own arrangement or
        // the one shared by every app. It never clashes with the picker overlay
        // below: a picker cannot be armed mid-drag.
        if (drag.dragging != null) {
            DragScopeLabel(
                state = state,
                drag = drag,
                bodyOrigin = bodyOrigin,
                bodyHeightPx = bodyHeightPx,
                // Horizontally centred; the pill picks its own vertical spot.
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // Last in the Box, so the legend and its pill float over the keyboard
        // instead of pushing the keys down — arming must not resize it.
        ToolPickerOverlay(
            state,
            onClose = onPickerDismiss,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        // Centred, not at the top: the browse is momentary and modal in
        // spirit, and the middle of the keys is where the eye already is.
        // Shares [onPickerDismiss] with the legend above — the service's
        // handler closes whichever hardware overlay is up, and only one
        // ever is.
        LanguageSwitchOverlay(
            state,
            onPick = { layoutId ->
                onLayoutSelect(layoutId)
                onPickerDismiss()
            },
            onDismiss = onPickerDismiss,
            modifier = Modifier.align(Alignment.Center),
        )
        // Down in the corner, not up by the strip: at the top it sat over the
        // first toolbar buttons and hid the very badges the arming had just put
        // there. The bottom-right of the key grid is the emptiest corner.
        PickerHelpPill(state, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** Gap the drag-scope pill keeps from the toolbar and from the keyboard's edge. */
private val DragScopeGap = 4.dp

/**
 * How close the finger may come to the pill before it moves out of the way.
 * Wide enough to clear the 44dp puck drawn under the finger.
 */
private val DragScopeDodgeMargin = 32.dp

/**
 * Floating pill shown for the whole of a tool drag, telling the user where
 * the new arrangement is saved: the active mode's own tool order (when mode
 * drag-edits are on and the mode carries one — the same rule as the
 * service's `toolOrderOwner`) or the global order every app shares.
 *
 * It parks itself immediately *below* the toolbar rather than over it. The
 * toolbar is the thing a tool drag is rearranging, and a pill sitting on top of
 * it hid the very slots the drop was aiming at — which tools were already
 * pinned, and in what order. So the row it names is the one row it never
 * covers. Below that it also dodges the finger: a drag working the top of the
 * toolbox grid would otherwise be dragging straight under the pill, so it
 * swaps to the bottom of the keyboard until the finger leaves.
 */
@Composable
private fun DragScopeLabel(
    state: KeyboardUiState,
    drag: ToolDragController,
    /** Keyboard-body origin in root coordinates; the drag reports in root. */
    bodyOrigin: Offset,
    bodyHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val mode = state.settings.keyboardModes
        .firstOrNull { it.id == state.activeModeId }
        ?.takeIf { state.settings.modeToolOrderEdits && it.ownsToolOrder }
    var labelHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val gapPx = with(density) { DragScopeGap.roundToPx() }
    val dodgePx = with(density) { DragScopeDodgeMargin.toPx() }
    // Resting spot: just clear of the toolbar's bottom edge. Measured rather
    // than assumed — the toolbar is not always the top row (see barOrder), and
    // its height is a theme setting.
    val restY = drag.toolbarBounds
        ?.let { (it.bottom - bodyOrigin.y).roundToInt() + gapPx }
        ?.coerceAtLeast(0)
        ?: 0
    val floorY = (bodyHeightPx - labelHeightPx - gapPx).coerceAtLeast(0)
    val fingerY = drag.position.y - bodyOrigin.y
    val crowded = labelHeightPx > 0 &&
        fingerY > restY - dodgePx && fingerY < restY + labelHeightPx + dodgePx
    val targetY = if (crowded) floorY else restY.coerceAtMost(floorY)
    // Which end the pill belongs at depends on its own height, and it cannot be
    // measured before it is laid out — so the first pass computes a spot with a
    // height of zero, and if the finger is up by the toolbar the pass after that
    // corrects it to the other end of the keyboard. Animating that correction is
    // what made the pill appear at the top and then travel down for no reason a
    // user could see. So it stays invisible and snaps until it has been measured
    // once, and fades in already where it belongs; the dodge animates only from
    // there on, when there is a real move to show.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(labelHeightPx > 0) {
        if (labelHeightPx > 0) placed = true
    }
    val y by animateIntAsState(
        targetValue = targetY,
        animationSpec = if (!placed || state.settings.reduceMotion) {
            snap()
        } else {
            tween(ToolbarMotionMs)
        },
        label = "dragScopeY",
    )
    val appear by animateFloatAsState(
        targetValue = if (placed) 1f else 0f,
        animationSpec = if (state.settings.reduceMotion) snap() else tween(ToolbarMotionMs),
        label = "dragScopeAppear",
    )
    Text(
        text = if (mode != null) {
            stringResource(R.string.ime_toolbox_drag_scope_mode, mode.name)
        } else {
            stringResource(R.string.ime_toolbox_drag_scope_global)
        },
        fontSize = 11.sp,
        color = kb.toolCircleActiveIcon,
        maxLines = 1,
        modifier = modifier
            .offset { IntOffset(0, y) }
            .onSizeChanged { labelHeightPx = it.height }
            // Above the background so the pill fades as one piece, and outside
            // the offset so the fade's buffer travels with it (see the note on
            // the toolbar's tool cells).
            .graphicsLayer { alpha = appear }
            .background(kb.toolCircleActive, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// ---- key grid ----

/**
 * The comet trail behind a gliding finger.
 *
 * The points live in plain arrays that Compose cannot observe, because a stroke
 * appends one every touch report — up to 240 a second. Holding them in snapshot
 * state and reading them from the composable body, which is what this replaced,
 * recomposed the whole key grid at that rate and allocated a fresh list per
 * sample. The rule the rest of the keyboard already follows applies here too:
 * gesture state is never read at composition scope.
 *
 * So the reads are split by phase:
 *
 *  - [revision] changes on every sample and every frame. Read it from a **draw**
 *    lambda and only the draw is invalidated. [sampleCount] takes it as an
 *    argument so a call site cannot forget to subscribe.
 *  - [headX]/[headY] follow the fingertip and are read from a **placement**
 *    lambda (`Modifier.offset`), so the floating word pill re-places without
 *    recomposing.
 *  - [visible] and [released] are ordinary snapshot state, but they flip twice
 *    a stroke rather than hundreds of times, so the body may read them to decide
 *    whether the trail and the pill exist at all.
 */
/**
 * The ambiguity picker's live state: which words are on offer, where they are on
 * screen, and which one the finger is currently over.
 *
 * Split the same way [GlideTrail] is, and for the same reason. [words] and
 * [hover] are snapshot state because they change a handful of times per stroke
 * and the popup has to redraw when they do. [rects] is a plain array written by
 * the targets' own layout and read from the pointer loop, because a rect that
 * lived in snapshot state would recompose the keyboard at touch-report rate for
 * a hit test that never needed the composition to know anything.
 */
@Stable
internal class GlidePickerState {

    /** The words on offer, best first. Empty when the picker is not showing. */
    var words by mutableStateOf<List<String>>(emptyList())
        private set

    /** Index of the target the finger is over, or -1. */
    var hover by mutableIntStateOf(-1)

    /** Where the targets landed, in the grid's coordinate space. */
    val rects = Array(MAX_TARGETS) { Rect.Zero }

    /** Where the picker is anchored: the trail head when it opened. */
    var anchorX = 0f
        private set
    var anchorY = 0f
        private set

    /** True once a stroke has opened the picker, so it opens at most once. */
    var offered = false
        private set

    fun open(choices: List<String>, x: Float, y: Float) {
        words = choices.take(MAX_TARGETS)
        anchorX = x
        anchorY = y
        hover = -1
        offered = true
        rects.fill(Rect.Zero)
    }

    /** Ends the picker and forgets the stroke, ready for the next one. */
    fun close() {
        if (words.isNotEmpty()) words = emptyList()
        hover = -1
        offered = false
        rects.fill(Rect.Zero)
    }

    /** Records where target [index] was laid out. */
    fun place(index: Int, rect: Rect) {
        if (index in 0 until MAX_TARGETS) rects[index] = rect
    }

    /** The target containing [x], [y], or -1. Called from the pointer loop. */
    fun targetAt(x: Float, y: Float): Int {
        for (i in words.indices) {
            if (rects[i].contains(Offset(x, y))) return i
        }
        return -1
    }

    /** The word the finger is over, or null — what a lift would commit. */
    fun picked(): String? = words.getOrNull(hover)

    companion object {
        const val MAX_TARGETS = 3
    }
}

@Stable
internal class GlideTrail {

    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)
    private var ts = LongArray(INITIAL_CAPACITY)
    private var count = 0

    /** Bumped by every append, expiry and frame tick. Draw-phase subscription. */
    var revision by mutableIntStateOf(0)
        private set

    /** Set while the trail has anything to draw; the body may read this. */
    var visible by mutableStateOf(false)
        private set

    /** The finger has lifted and the trail is fading in place. */
    var released by mutableStateOf(false)
        private set

    /** Latest frame time, for the age fade. Plain — [revision] carries the invalidation. */
    var nowMs = 0L
        private set

    var headX by mutableFloatStateOf(0f)
        private set

    var headY by mutableFloatStateOf(0f)
        private set

    /**
     * How many live samples there are. Takes [revision] so that reading the
     * count forces a read of the snapshot state that actually changes — the
     * arrays behind it are invisible to Compose, so a call site that skipped
     * the subscription would draw once and then freeze.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sampleCount(revision: Int): Int = count

    fun x(i: Int): Float = xs[i]

    fun y(i: Int): Float = ys[i]

    fun ageAt(i: Int): Long = nowMs - ts[i]

    /** A stroke has taken over from a tap; start a fresh trail. */
    fun begin() {
        count = 0
        released = false
        visible = true
        revision++
    }

    /** Appends the sample and drops whatever has aged past [keepMs]. */
    fun add(x: Float, y: Float, timeMs: Long, keepMs: Long) {
        if (count == xs.size) grow()
        xs[count] = x
        ys[count] = y
        ts[count] = timeMs
        count++
        nowMs = timeMs
        headX = x
        headY = y
        expire(timeMs, keepMs)
        revision++
    }

    fun release() {
        released = true
    }

    /**
     * Advances the fade clock to [now]. Returns false once a released trail has
     * fully expired, which is the frame loop's signal to stop.
     */
    fun tick(now: Long, keepMs: Long): Boolean {
        nowMs = now
        if (released) {
            expire(now, keepMs)
            if (count == 0) {
                visible = false
                revision++
                return false
            }
        }
        revision++
        return true
    }

    /** Abandons the trail outright — the feature switched off, or the layout changed. */
    fun clear() {
        count = 0
        released = true
        visible = false
        revision++
    }

    private fun expire(now: Long, keepMs: Long) {
        var drop = 0
        while (drop < count && now - ts[drop] > keepMs) drop++
        if (drop == 0) return
        val kept = count - drop
        if (kept > 0) {
            System.arraycopy(xs, drop, xs, 0, kept)
            System.arraycopy(ys, drop, ys, 0, kept)
            System.arraycopy(ts, drop, ts, 0, kept)
        }
        count = kept
    }

    private fun grow() {
        val bigger = xs.size * 2
        xs = xs.copyOf(bigger)
        ys = ys.copyOf(bigger)
        ts = ts.copyOf(bigger)
    }

    private companion object {
        /** ~350 ms of samples at 120 Hz; longer trails grow once and stay grown. */
        const val INITIAL_CAPACITY = 64
    }
}

/**
 * Takes the place of the `?123` layer's own digit row when the number row is
 * on and already supplies those digits one row above. Carries the symbols
 * that layer has nowhere else to put.
 */
private val SymbolsFillRow = listOf("=", "\\", "<", ">", "[", "]", "{", "}", "|", "~")
    .map { Key(it) }

/**
 * Replaces the digit number row while the symbols-2 (`=\<`) layer is showing.
 * The digits are one tap away on the symbols-1 layer, so this slot carries an
 * extra set of arrow and comparison symbols the symbol layers have no room for
 * rather than a second copy of the numbers.
 */
private val SymbolsShiftedFillRow = listOf(
    Key("←", longPress = listOf("⟵", "↔")),
    Key("→", longPress = listOf("⟶", "↦")),
    Key("↑", longPress = listOf("↕")),
    Key("↓"),
    Key("±", longPress = listOf("∓")),
    Key("∞"),
    Key("≈", longPress = listOf("≅", "≡")),
    Key("≠"),
    Key("≤", longPress = listOf("≪")),
    Key("≥", longPress = listOf("≫")),
)

/**
 * Marks the key grid so a UI test can ask whether it is on screen at all.
 *
 * Several panels take the keys away from the user's field and are expected to
 * draw a set of their own underneath — a plugin's text box shipped doing the
 * first half and not the second, leaving a focused box with nothing to type
 * into it. `KeyRowsVisibilityTest` renders the real screen and checks this tag.
 */
const val KeyRowsTestTag: String = "wm:key-rows"

/**
 * The key-face colours a key is painted with, lifted off [KbTheme] so the
 * resolved keys depend on these eight values and not on the whole theme — a
 * background image, a toolbar colour or a radius changing then costs no key work.
 */
@Immutable
internal data class KeyPalette(
    val key: Color,
    val keyText: Color,
    val modifierKey: Color,
    val modifierKeyText: Color,
    val enterKey: Color,
    val enterKeyText: Color,
    val pressedKey: Color,
    val accent: Color,
    /**
     * Single-key style overrides, carried on the palette so the resolved keys
     * still depend on this one object: the map changes only when the theme
     * does, exactly like the eight colours above.
     */
    val overrides: Map<String, KeyOverride> = emptyMap(),
)

internal fun KbTheme.keyPalette(): KeyPalette = KeyPalette(
    key = key,
    keyText = keyText,
    modifierKey = modifierKey,
    modifierKeyText = modifierKeyText,
    enterKey = enterKey,
    enterKeyText = enterKeyText,
    pressedKey = pressedKey,
    accent = accent,
    overrides = keyOverrides,
)

/**
 * Everything one key draws, resolved out of [KeyboardUiState] once per layout,
 * shift, modifier or palette change instead of read out of the state per key.
 *
 * This type exists for skipping. The service publishes a fresh
 * [KeyboardUiState] on every keystroke — the suggestions and the composing
 * preview really do change — and strong skipping compares an unstable parameter
 * by instance, so a key handed the whole state can never skip: one keypress
 * re-ran every key body on the board, modifier chains and lambdas included.
 * Nothing a key draws is per-keystroke except the Bengali vowel form, so keys
 * take their resolved values instead and a mid-word keypress leaves every one of
 * these identical.
 */
@Immutable
internal data class KeyVisual(
    /** The key as authored: what it commits, its width, popups, flicks, hints. */
    val key: Key,
    /** [displayLabel] for the live shift state, numeral system and vowel form. */
    val label: String,
    /** [spokenLabel]: the TalkBack description, and what PASSTHROUGH announces. */
    val spoken: SpokenLabel,
    /** Latch of the modifier this key toggles; null when it is not a modifier. */
    val latch: ModifierState?,
    /** The key's face, and the colour a press paints over it. */
    val background: Color,
    val pressedBackground: Color,
    /** Label colour, and the one it flips to while pressed (Enter only). */
    val contentColor: Color,
    val pressedContentColor: Color,
    /**
     * Icon slot for the shift and enter keys, whose glyph tracks state; null for
     * every other key. The fixed-glyph keys (delete, globe, emoji) name their
     * own slot where they are drawn, since it cannot change.
     */
    val iconSlot: String?,
    /** Shift is on or locked, so its icon draws in the accent colour. */
    val iconActive: Boolean,
    /** Enter with an app-supplied actionLabel draws that wording, not an icon. */
    val enterLabel: String?,
    /** Spacebar: its label, and whether the language-cycle arrows flank it. */
    val spaceText: String,
    val spaceArrows: Boolean,
    /** A per-key style's own border colour; null follows the theme border. */
    val borderColor: Color? = null,
    /** A per-key style's own bubble colours; null follows the theme. */
    val popupBackground: Color? = null,
    val popupText: Color? = null,
    /**
     * The label-size multiplier the grid this key belongs to asked for: the
     * layer's own, or the layout's where the layer sets none. 1.0 for every
     * shipped grid.
     *
     * It rides the key rather than the settings because it is per *layer*, and
     * the settings are resolved once for the whole board — a board-level number
     * could not have moved when the user pressed `?123`, which was the whole
     * complaint (issue #18).
     */
    val fontScale: Float = 1f,
)

/**
 * One row as it is drawn: its keys resolved, already cut into halves for split
 * mode (the right half is empty when the keyboard is not split), with the row's
 * centring pad and key height.
 */
@Immutable
private data class KeyRowVisual(
    val left: List<KeyVisual>,
    val right: List<KeyVisual>,
    /** Half the row's slack to the grid width, as a layout weight. */
    val sidePad: Float,
    /**
     * The centre gap between the halves, as a layout weight. Per row rather than
     * per board because it is a percentage of the width the row was measured
     * against, and the number row is measured against its own key count.
     */
    val splitGapWeight: Float,
    val heightDp: Int,
)

/**
 * A run of rows that has to be drawn as one block because a key spans across
 * them — see [spanSlots] for the geometry.
 *
 * Rows are normally independent `Row`s, and that is what [KeyRowVisual] is for.
 * A key covering two rows cannot be one of their children: drawn in the upper
 * row it hangs outside that row's bounds, and Compose does not hit-test a child
 * outside its parent, so the lower half of the key would look pressable and do
 * nothing. The band is the parent both rows' keys share instead.
 */
@Immutable
private data class KeyBandVisual(
    /** Every key of the band, resolved and placed, in row-major order. */
    val slots: List<KeyBandSlot>,
    /** Grid units across the band — the unit [KeyBandSlot.x] is measured in. */
    val weight: Float,
    /** Key height in dp for each row of the band, top to bottom. */
    val rowHeights: List<Int>,
)

/** One key of a [KeyBandVisual], with where it sits and how far it reaches. */
@Immutable
private data class KeyBandSlot(
    val visual: KeyVisual,
    /** Left edge in grid units, already centred and flowed around the spans. */
    val x: Float,
    val width: Float,
    /** First row of the band this key covers, and how many it covers. */
    val row: Int,
    val span: Int,
)

/**
 * One block of the body grid: an ordinary row, or a band of rows joined by a
 * spanning key. Almost every layout is all [Row]s.
 */
private sealed interface KeyGridBlock {
    @Immutable
    data class Row(val row: KeyRowVisual) : KeyGridBlock

    @Immutable
    data class Band(val band: KeyBandVisual) : KeyGridBlock
}

/**
 * Resolves one key against [state].
 *
 * A plain function rather than a composable so the invariant the whole split
 * rests on — a keystroke changes nothing a key draws — is unit-testable.
 */
internal fun keyVisual(
    key: Key,
    state: KeyboardUiState,
    palette: KeyPalette,
    /** The grid's label-size multiplier; see [KeyVisual.fontScale]. */
    fontScale: Float = 1f,
): KeyVisual {
    val action = key.action
    // A latched modifier has to look held: it changes what the *next* key does,
    // so with no visible state the user finds out by pressing one.
    val latch = (action as? KeyAction.Mod)?.let { state.modifiers[it.key] }
    // The theme's own style for this one key, if it carries one. Applied over
    // the class colours below, but never over a latch — an armed modifier has
    // to look armed whatever colour its face was given.
    val override = if (palette.overrides.isEmpty()) {
        null
    } else {
        keyOverrideId(key)?.let { palette.overrides[it] }
    }
    val overrideBackground = override?.background
        ?.takeIf { latch == null || latch == ModifierState.OFF }
        ?.let { Color(it.toInt()) }
    // Samsung-style contrast: letter keys clearly lighter than the board,
    // modifier keys a shade darker than the letters.
    val background = overrideBackground ?: when {
        latch == ModifierState.LOCKED -> palette.accent
        latch == ModifierState.ARMED -> palette.pressedKey
        action == KeyAction.Enter -> palette.enterKey
        action != KeyAction.Text -> palette.modifierKey
        else -> palette.key
    }
    val contentColor = override?.text?.let { Color(it.toInt()) } ?: when {
        action == KeyAction.Enter -> palette.enterKeyText
        action != KeyAction.Text -> palette.modifierKeyText
        else -> palette.keyText
    }
    return KeyVisual(
        key = key,
        label = displayLabel(key, state),
        spoken = spokenLabel(key, state),
        latch = latch,
        background = background,
        // An overridden face derives its own pressed shade the way specKbTheme
        // derives the theme's: a quarter of the way toward the label colour.
        pressedBackground = if (overrideBackground != null) {
            lerp(overrideBackground, contentColor, 0.25f)
        } else {
            palette.pressedKey
        },
        contentColor = contentColor,
        // Enter is the one key that recolours under the finger: its text colour
        // is picked for its own accented face, which the press paints over.
        pressedContentColor =
            if (action == KeyAction.Enter) palette.modifierKeyText else contentColor,
        iconSlot = when {
            action == KeyAction.Shift -> when (state.shiftState) {
                ShiftState.CAPS_LOCK -> IconSlots.KEY_SHIFT_LOCK
                ShiftState.ON -> IconSlots.KEY_SHIFT_ON
                ShiftState.OFF -> IconSlots.KEY_SHIFT
            }
            // Always the lock glyph, lit when the lock is on: the key's face says
            // what it does, not what state the board happens to be in.
            action == KeyAction.CapsLock -> IconSlots.KEY_SHIFT_LOCK
            // CUSTOM is the one enter action with no slot: the app supplied its
            // own wording, so there is no icon to replace.
            action == KeyAction.Enter ->
                IconDefaults.enterActionSlot(state.effectiveEnterAction) ?: IconSlots.KEY_ENTER
            else -> null
        },
        iconActive = when (action) {
            KeyAction.Shift -> state.shiftState != ShiftState.OFF
            KeyAction.CapsLock -> state.shiftState == ShiftState.CAPS_LOCK
            else -> false
        },
        enterLabel = state.enterActionLabel?.takeIf {
            action == KeyAction.Enter && state.effectiveEnterAction == EnterAction.CUSTOM
        },
        spaceText = if (action == KeyAction.Space) spacebarText(state) else "",
        spaceArrows = action == KeyAction.Space && spacebarArrowsShown(state),
        borderColor = override?.border?.let { Color(it.toInt()) },
        popupBackground = override?.popupBackground?.let { Color(it.toInt()) },
        popupText = override?.popupText?.let { Color(it.toInt()) },
        fontScale = fontScale,
    )
}

/**
 * The name a [ThemeSpec.keyOverrides] entry uses for this key: the lowercase
 * label for letter keys — so an override follows the letter across layouts —
 * and the action's name (`ENTER`, `SHIFT`, `SPACE`, `DELETE`, `SYMBOLS`,
 * `MOD_CTRL`, …) for everything else. Null for a letter key with no label.
 */
internal fun keyOverrideId(key: Key): String? = when (val action = key.action) {
    KeyAction.Text -> key.label.takeIf { it.isNotBlank() }?.lowercase()
    is KeyAction.Mod -> "MOD_${action.key.name}"
    else -> action::class.simpleName?.uppercase()
}

/**
 * Whether the spacebar's language-cycle arrows are drawn. They only mean
 * something when a swipe actually cycles languages and there is more than one
 * language to cycle.
 */
private fun spacebarArrowsShown(state: KeyboardUiState): Boolean {
    val swipeSwitchesLanguage = state.settings.spaceShortSwipe == SpaceSwipeAction.LANGUAGE ||
        state.settings.spaceLongSwipe == SpaceSwipeAction.LANGUAGE
    return state.settings.spacebarLanguageArrows &&
        swipeSwitchesLanguage &&
        state.settings.enabledLayoutIds.size > 1
}

/**
 * Every key on the board, resolved in one pass: the digit row (null when it is
 * not shown) and the body blocks, each with its own height and centring pad.
 *
 * Rebuilt only when something a key actually draws changes. Deliberately NOT
 * keyed on the suggestions, the composing preview or the open panel: those change
 * on every keystroke, and rebuilding here is exactly what used to re-run all ~40
 * key bodies per keypress. Every field this reads is a key below — read one more
 * without adding its key and the board goes stale.
 *
 * A block is one row unless the layout uses [Key.rowSpan], in which case the
 * rows a key reaches across come out as a single [KeyGridBlock.Band]. The blocks
 * still cover the body rows in order and still sum to the same height, so
 * `keyRowsHeight` and the reserved window are untouched by a span.
 */
@Composable
private fun rememberKeyGrid(
    state: KeyboardUiState,
    layout: KeyboardLayout,
    bodyRows: List<List<Key>>,
    extraRow: List<Key>?,
    palette: KeyPalette,
    gridWeight: Float,
): Pair<KeyRowVisual?, List<KeyGridBlock>> {
    val settings = state.settings
    val split = settings.splitKeyboard
    val splitGapPercent = settings.splitGapPercent
    // Optional taller (or shorter) bottom row — space / enter — set independently
    // of the other keys. Ignored when the layout carries its own per-row heights,
    // so a custom layout's bottom row wins and the height is never applied twice.
    // keyRowsHeight adds the same delta so the reserved height matches.
    val bottomRowHeightDp = settings.layoutBehavior.bottomRowHeightDp
    return remember(
        bodyRows, extraRow, layout, palette, settings, gridWeight,
        state.shiftState, state.modifiers, state.effectiveEnterAction,
        state.enterActionLabel, state.language, state.script,
        state.composer.isClusterShaping, state.vowelForm, state.layoutId,
        // The fancy style rewrites every letter label via displayLabel. The
        // persisted pick rides in through `settings` (already a key); this is
        // the session override the strip flips for instant response.
        state.activeFancyStyleId,
    ) {
        // This layer's label size, or the layout's where the layer sets none —
        // already resolved into the compiled grid by `compile`. `layout` is
        // already a key of this remember, so pressing ?123 rebuilds the grid
        // with the symbol page's own size.
        val fontScale = layout.appearance.drawnFontScale()
        val digits = extraRow?.let { row ->
            keyRowVisual(
                row, split, splitGapPercent, row.size.toFloat(),
                settings.numberRowHeightDp, state, palette, fontScale,
            )
        }
        val heights = bodyRows.indices.map { index ->
            // Per-row height multiplier from the layout, if any. Rounded to whole
            // dp so the rendered height matches keyRowsHeight exactly (which sums
            // the same rounded values).
            val perRowHeight = rowScaledKeyHeight(
                settings.keyHeightDp, layout.rowHeights?.getOrNull(index),
            )
            val bottomRow = index == bodyRows.lastIndex && layout.rowHeights == null
            if (bottomRowHeightDp > 0 && bottomRow) bottomRowHeightDp else perRowHeight
        }
        // Split mode cuts every row at its own midpoint, which a key belonging to
        // two rows cannot survive: the halves would part company under it. So a
        // split board draws spans as ordinary one-row keys — the arrangement the
        // layout had before spans existed — rather than drawing them wrong.
        val banded = !split && hasRowSpans(bodyRows)
        val bands = if (banded) spanBands(bodyRows) else bodyRows.indices.map { it..it }
        val slots = if (banded) spanSlots(bodyRows, gridWeight) else emptyList()
        digits to bands.map { band ->
            if (band.first == band.last) {
                KeyGridBlock.Row(
                    keyRowVisual(
                        bodyRows[band.first], split, splitGapPercent, gridWeight,
                        heights[band.first], state, palette, fontScale,
                    ),
                )
            } else {
                KeyGridBlock.Band(
                    keyBandVisual(band, slots, gridWeight, heights, state, palette, fontScale),
                )
            }
        }
    }
}

/**
 * Resolves one band of rows: the slots that fall in it, re-based on the band's
 * own first row so the renderer can index [KeyBandVisual.rowHeights] directly.
 *
 * The band is laid out against one width for all its rows rather than each row's
 * own, because a spanning key has to sit on the same column pitch as the rows it
 * reaches into — that is the whole point of it. Only an over-wide row inside the
 * band moves that width, and then it moves it for the band as a whole.
 */
private fun keyBandVisual(
    band: IntRange,
    slots: List<KeySlot>,
    gridWeight: Float,
    heights: List<Int>,
    state: KeyboardUiState,
    palette: KeyPalette,
    fontScale: Float,
): KeyBandVisual {
    val inBand = slots.filter { it.row in band }
    val weight = maxOf(gridWeight, inBand.maxOfOrNull { it.end } ?: 0f)
    return KeyBandVisual(
        slots = inBand.map { slot ->
            KeyBandSlot(
                visual = keyVisual(slot.key, state, palette, fontScale),
                x = slot.x,
                width = slot.key.width,
                row = slot.row - band.first,
                span = slot.span,
            )
        },
        weight = weight,
        rowHeights = band.map { heights[it] },
    )
}

/** A key whose preview bubble is up, and where that key sits. */
@Immutable
internal data class KeyPreview(
    /**
     * Identity of the key that asked for this bubble. Two fingers down means two
     * bubbles, so every lookup is by token — lifting one finger must leave the
     * other's bubble alone.
     */
    val token: Any,
    val label: String,
    /** The key's place in the compose root, and its size. */
    val position: Offset,
    val size: IntSize,
    /** A per-key style's own bubble colours; null follows the theme. */
    val popupBackground: Color? = null,
    val popupText: Color? = null,
)

/**
 * The preview bubbles the whole board shares.
 *
 * Every key used to raise its own [Popup] on press, and a Popup is a real
 * window: typing a word added and removed one through WindowManager per letter,
 * with two timers per press to run it. The keys now only publish what they want
 * shown, and a single overlay draws all of it inside one window that never
 * moves — see [KeyPreviewOverlay] for why that matters.
 *
 * Timing is held here rather than in the keys so one coroutine can run every
 * bubble: [expire] does the arithmetic, the overlay does the waiting.
 */
@Stable
internal class KeyPreviewState(
    /** Injected so the timing above can be driven by a test without a device. */
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** Bubbles on screen, in the order their keys were pressed. */
    val shown = mutableStateListOf<KeyPreview>()

    /** Bumped by every press and release, to wake the overlay's timer. */
    var revision by mutableIntStateOf(0)
        private set

    private val shownAt = HashMap<Any, Long>()
    private val releasedAt = HashMap<Any, Long>()

    fun press(preview: KeyPreview) {
        drop(preview.token)
        shown.add(preview)
        shownAt[preview.token] = now()
        revision++
    }

    fun release(token: Any) {
        // Only the first release counts: a second would push the minimum-duration
        // floor out and keep the bubble up longer than it asked for.
        if (token !in shownAt || token in releasedAt) return
        releasedAt[token] = now()
        revision++
    }

    /** The long-press alternates took over: that key's bubble goes at once. */
    fun cancel(token: Any) {
        if (drop(token)) revision++
    }

    /**
     * Drops every bubble whose time is up, and returns when the next one is due
     * (uptime millis), or null when nothing is waiting on a clock.
     *
     * A held key's bubble only answers to [maxMs]. A released one goes at
     * whichever comes first: that cap, or the minimum duration measured from
     * when it appeared — so a fast tap still shows a readable bubble instead of a
     * single-frame flash, and a release delivered late (commit lag, a new line
     * inserting) does not extend it.
     */
    fun expire(now: Long, minMs: Int, maxMs: Int): Long? {
        var next: Long? = null
        for (preview in shown.toList()) {
            val appeared = shownAt[preview.token] ?: continue
            val released = releasedAt[preview.token]
            val cap = appeared + maxMs
            val due = if (released == null) cap else minOf(cap, maxOf(released, appeared + minMs))
            if (due <= now) {
                drop(preview.token)
            } else {
                next = if (next == null) due else minOf(next, due)
            }
        }
        return next
    }

    private fun drop(token: Any): Boolean {
        shownAt.remove(token)
        releasedAt.remove(token)
        val index = shown.indexOfFirst { it.token === token }
        if (index < 0) return false
        shown.removeAt(index)
        return true
    }
}

/**
 * Pins the overlay window over the key grid, [headroomPx] above it.
 *
 * Deliberately ignores everything it is passed except the anchor: the window
 * must not move. A window that repositions per press slides visibly from the
 * last key to the next one, and starts wherever it was parked — which is the bug
 * this replaced. The bubbles move inside it instead, which is ordinary layout.
 */
private class GridOverlayPositionProvider(private val headroomPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.top - headroomPx)
}

/**
 * Draws every live key preview, and runs the one timer they share.
 *
 * One window for the keyboard's lifetime, fixed over the grid and extending
 * [headroom] above it so the floating style has room for the top row's bubble.
 * The bubbles are placed within it by the same two providers the per-key popup
 * used, handed the key's rectangle in the overlay's own space — so both styles
 * land where they always did, without the window itself ever moving.
 *
 * [gridOrigin] and [gridSize] are the grid's place and size in the compose root,
 * which turn the keys' root-space rectangles into offsets inside this window.
 */
@Composable
private fun KeyPreviewOverlay(
    state: KeyPreviewState,
    settings: KeyboardSettings,
    gridOrigin: Offset,
    gridSize: IntSize,
) {
    val popup = settings.popup
    // The height and the placement come off the theme rather than the
    // settings: a theme may carry its own, and both the headroom above the
    // grid and the bubble itself have to agree on which one won.
    val kbTheme = LocalKbTheme.current
    val bubbleHeightDp = kbTheme.popupHeightDp
    // One coroutine for the board, in place of the two LaunchedEffects every key
    // ran per press. It sleeps until the nearest bubble is due or something is
    // pressed, whichever lands first; `expire` owns the arithmetic.
    LaunchedEffect(state, popup.minDurationMs, popup.maxDurationMs) {
        while (true) {
            // Read the revision *before* expiring, and wait for it to differ. A
            // press landing between the two would otherwise be missed — the flow
            // would start from the already-bumped value and sleep through it.
            val seen = state.revision
            val due = state.expire(SystemClock.uptimeMillis(), popup.minDurationMs, popup.maxDurationMs)
            if (due == null) {
                // Nothing on a clock: sleep until a key is pressed or released.
                snapshotFlow { state.revision }.first { it != seen }
            } else {
                withTimeoutOrNull(due - SystemClock.uptimeMillis()) {
                    snapshotFlow { state.revision }.first { it != seen }
                }
            }
        }
    }

    val density = LocalDensity.current
    val gapPx = with(density) { KeyPopupGap.roundToPx() }
    // Room above the grid for a floating bubble over the top row: its own height
    // plus the gap it keeps from the key.
    val headroomPx = with(density) { (bubbleHeightDp.dp + KeyPopupGap * 2).roundToPx() }
    val onKeyStyle = kbTheme.popupOnKey ?: popup.onKey
    val bubbles = state.shown.toList()
    Popup(
        popupPositionProvider = remember(headroomPx) { GridOverlayPositionProvider(headroomPx) },
        properties = PreviewPopupProperties,
    ) {
        Layout(
            // Keyed by the pressing key, so a bubble expiring under a finger that
            // is still down removes that bubble rather than shuffling the rest up
            // into its slot.
            content = {
                for (preview in bubbles) {
                    key(preview.token) { KeyPreviewBubble(preview, popup, onKeyStyle) }
                }
            },
        ) { measurables, constraints ->
            val width = if (gridSize.width > 0) gridSize.width else constraints.maxWidth
            val height = gridSize.height + headroomPx
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    val preview = bubbles[index]
                    // The key, in this window's space: its offset inside the grid,
                    // pushed down by the headroom the window adds on top.
                    val keyBounds = IntRect(
                        IntOffset(
                            (preview.position.x - gridOrigin.x).roundToInt(),
                            (preview.position.y - gridOrigin.y).roundToInt() + headroomPx,
                        ),
                        preview.size,
                    )
                    val provider = if (onKeyStyle) {
                        OnKeyPopupPositionProvider
                    } else {
                        AboveAnchorPopupPositionProvider(gapPx)
                    }
                    placeable.place(
                        provider.calculatePosition(
                            keyBounds,
                            IntSize(width, height),
                            layoutDirection,
                            IntSize(placeable.width, placeable.height),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * One preview bubble. In on-key mode it is key-wide with a large label near the
 * top, clear of the finger (the stock-keyboard style where the bubble replaces
 * the key); otherwise it floats above the fingertip.
 */
@Composable
private fun KeyPreviewBubble(preview: KeyPreview, popup: KeyPopupSettings, onKeyStyle: Boolean) {
    val kb = LocalKbTheme.current
    val density = LocalDensity.current
    val shape = kb.popupShape()
    Surface(
        shape = shape,
        color = preview.popupBackground ?: kb.popup,
        border = kb.popupSurfaceBorder(),
        shadowElevation = elevationFor(kb.popupShapeKind, 6.dp),
    ) {
        Box(
            modifier = Modifier
                .height(kb.popupHeightDp.dp)
                .widthIn(
                    min = if (onKeyStyle) with(density) { preview.size.width.toDp() } + 8.dp else 0.dp,
                )
                .popupTexture(LocalKeyTextures.current, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = if (onKeyStyle) Alignment.TopCenter else Alignment.Center,
        ) {
            Text(
                text = preview.label,
                modifier = if (onKeyStyle) Modifier.padding(top = 8.dp) else Modifier,
                fontSize = ((if (onKeyStyle) 34 else 22) * popup.fontScale).sp,
                color = preview.popupText ?: kb.popupText,
            )
        }
    }
}

/**
 * Paints the theme's popup texture behind the bubble's label, clipped to the
 * bubble's shape. Built the way the key textures are: outline and paint in the
 * cache block, so the draw pass only issues the draw call.
 */
private fun Modifier.popupTexture(textures: KeyTextures, shape: Shape): Modifier {
    val bitmap = textures.popup ?: return this
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val paint = KeyTexturePaint.of(bitmap, textures, outline, size)
        onDrawBehind { paint.draw(this, textures.opacity) }
    }
}

/** Resolves a whole row, splitting it first so the halves are what gets drawn. */
private fun keyRowVisual(
    row: List<Key>,
    split: Boolean,
    splitGapPercent: Int,
    gridWeight: Float,
    heightDp: Int,
    state: KeyboardUiState,
    palette: KeyPalette,
    fontScale: Float,
): KeyRowVisual {
    // Split before resolving: the cut rewrites a straddling spacebar's width and
    // blanks the left half's label, so the halves are the keys to resolve.
    val (left, right) = if (split) splitKeys(row) else row to emptyList()
    return KeyRowVisual(
        left = left.map { keyVisual(it, state, palette, fontScale) },
        right = right.map { keyVisual(it, state, palette, fontScale) },
        sidePad = sidePadFor(row, gridWeight),
        splitGapWeight = gridWeight * splitGapPercent / 100f,
        heightDp = heightDp,
    )
}

@Composable
private fun KeyRows(
    state: KeyboardUiState,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onGesture: (List<GesturePoint>, List<KeyCenter>, Float, String?) -> Unit =
        { _, _, _, _ -> },
    onGesturePreview: (List<GesturePoint>, List<KeyCenter>, Float) -> Unit = { _, _, _ -> },
    onCursorMove: (Int) -> Unit = {},
    onLayoutSelect: (String) -> Unit = {},
    onGestureWords: (List<List<GesturePoint>>, List<KeyCenter>, Float) -> Unit = { _, _, _ -> },
    onKeyboardHandwritingStroke: (HwStroke, IntSize) -> Unit = { _, _ -> },
    /** Down position of the tap that committed a letter, in key-width units
     * (keyboard space). Fired just before the matching onKey. */
    onKeyTouch: (Float, Float) -> Unit = { _, _ -> },
    /** Letter-key centres of the live layout in key-width units, for the
     * engine's touch-likelihood model. */
    onTouchKeys: (List<KeyCenter>) -> Unit = {},
) {
    val layout = rememberCurrentLayout(state)
    // Letter-area swipes are drawing handwriting rather than gliding a word
    // (full builds only). Capture arms whenever the mode is selected; the
    // service decides whether the drawn ink recognizes or prompts a download.
    val handwriteSwipe = BuildConfig.ENABLE_ML_KIT_HANDWRITING &&
        state.settings.gestureTyping &&
        state.settings.letterSwipeAction == LetterSwipeAction.HANDWRITE &&
        state.layoutMode == LayoutMode.LETTERS &&
        state.panel == PanelMode.NONE
    val gestureEnabled = !handwriteSwipe &&
        state.settings.gestureTyping &&
        state.layoutMode == LayoutMode.LETTERS &&
        state.panel == PanelMode.NONE &&
        // Whether this language and layout can be glided at all — a word list
        // exists, the layout types letters rather than converting them, and its
        // keys cover enough of the language to decode honestly. Measured in the
        // service against the live word sources, because two of those three
        // questions need the dictionary to answer.
        state.glideReady

    // Letter-key centres and width, captured from layout in this Box's space.
    // Keyed on the layout: the map is written by onGloballyPositioned per key,
    // so a layout with fewer letters than the last one would otherwise keep the
    // previous grid's centres and anchor swipes on keys that are not on screen.
    // A LaunchedEffect that cleared it would race those positioning callbacks.
    val keyCenters = remember(layout) { mutableStateMapOf<Char, Offset>() }
    // Spacebar bounds in this Box's space, for the multi-word glide split.
    // Reset per layout alongside the key centres. A split keyboard positions
    // two half-spacebars into this one slot; the last one measured wins, which
    // covers the common (non-split) case and degrades to one half for split.
    val spaceRect = remember(layout) { mutableStateOf<Rect?>(null) }
    // The pointer loops below are keyed on the gesture settings, not on the
    // layout, so they outlive a layout change — and both the centres map and the
    // grid they read are per-layout values. Captured bare, a loop started under
    // the old layout keeps resolving letters against the map the old layout
    // filled, so a swipe drawn on a layout the user has just switched to decodes
    // against the previous layout's key positions: real words, none of them the
    // one that was drawn. Read them through a State, the way `keyWidth` is.
    val liveCenters = rememberUpdatedState(keyCenters)
    val liveLayouts = rememberUpdatedState(state.layouts)
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Rows narrower than the grid (e.g. the 9-key QWERTY home row) keep the
    // standard key width and are centred with side gaps, instead of stretching
    // their keys to fill the full width.
    //
    // The grid is the width the most rows share, not the first row's — see
    // gridWeightOf. That keeps a lone narrow row (one inserted at the top) from
    // hijacking the reference and filling the width, and a lone wide row
    // (Dvorak's third) from padding every other row.
    //
    // A layout can arrive with no rows at all — the editor allows deleting them
    // and an imported file is untrusted — so gridWeightOf returns 0 for empty.
    //
    // On a tablet the width is *declared* rather than inferred: the expansion
    // widens the letters layer and leaves the symbols layers alone, so inferring
    // per layer would give letters twelve columns and symbols ten and visibly
    // resize every key on the way into ?123. Declaring it also makes `keyWidth`
    // below — the glide decoder's distance normaliser — the real drawn column
    // width on every layer rather than an approximation of the current one.
    val gridWeight = state.layouts.gridWidth
        ?: gridWeightOf(layout.rows).takeIf { it > 0f } ?: 10f
    // One key's width, for the gesture decoder's distance normalisation.
    // Derived from the grid rather than recorded by whichever letter key
    // happened to measure last, which made decoding depend on where a wide key
    // sat in the layout. The column's own horizontal padding is taken off first
    // so this is the real cell width rather than an approximation of it.
    //
    // Held in a State because the gesture detector below is keyed on
    // `gestureEnabled` and would otherwise capture the first frame's value,
    // before the box has been measured at all.
    val rowInsetPx = with(LocalDensity.current) { KeyRowsPadHorizontal.toPx() * 2 }
    val keyWidth = rememberUpdatedState(
        if (boxSize.width > 0) ((boxSize.width - rowInsetPx) / gridWeight).coerceAtLeast(0f) else 0f,
    )
    // Smart key-hit detection: a boundary tap can be claimed by a likelier
    // neighbour. Only the letters layer, and only while the field is composing.
    val smartHit = state.settings.layoutBehavior.smartHitDetection &&
        state.layoutMode == LayoutMode.LETTERS &&
        state.panel == PanelMode.NONE
    // Live next-letter distribution; read fresh inside the down-observer, which
    // must not restart on every keystroke.
    val nextBias = rememberUpdatedState(if (smartHit) state.nextLetterBias else emptyMap())
    // Pointer → the letter its down chose to remap to, set at down time by the
    // observer and consumed by the owning key on release.
    val hitRemap = remember { HashMap<PointerId, Char>() }
    // Pointer → its down position in this Box's space, for the engine's
    // touch-likelihood model. Written by an always-on Initial-pass observer;
    // read when the owning key commits. Entries are not removed on lift (the
    // key's Main-pass handler reads after the observer has seen the up) —
    // the map is pruned wholesale instead, and ids never repeat within it.
    val downPositions = remember { HashMap<PointerId, Offset>() }
    val onKeyTouchNow = rememberUpdatedState(onKeyTouch)
    // Current-layer letter keys by lowercase char, so a remap resolves to the
    // correctly-cased Key to commit. Keyed on layout: rebuilt when it changes.
    val letterKeys = remember(layout) {
        buildMap<Char, Key> {
            for (row in layout.rows) {
                for (key in row) {
                    val ch = key.label.singleOrNull()
                        ?.takeIf { key.action == KeyAction.Text && it.isLetter() }
                        ?: continue
                    put(ch.lowercaseChar(), key)
                }
            }
        }
    }
    // Substitutes the committed key when the owning letter's down was remapped
    // toward a likelier neighbour, and reports the tap's down position to the
    // engine's touch model just before the commit. Stable across keystrokes
    // (depends only on the layout), so it never restarts a key's pointerInput.
    val smartResolve = remember(letterKeys) {
        { key: Key, id: PointerId ->
            downPositions[id]?.let { down ->
                val kw = keyWidth.value
                if (kw > 0f) onKeyTouchNow.value(down.x / kw, down.y / kw)
            }
            val target = hitRemap[id]
            val own = key.label.singleOrNull()
                ?.takeIf { key.action == KeyAction.Text && it.isLetter() }
                ?.lowercaseChar()
            if (target != null && own != null && target != own) {
                letterKeys[target] ?: key
            } else {
                key
            }
        }
    }
    // Publish the live layout's letter centres, normalised to key widths, so
    // the engine can turn tap positions into per-key likelihoods. snapshotFlow
    // coalesces the per-key positioning bursts during a layout change.
    LaunchedEffect(layout) {
        snapshotFlow { keyCenters.toMap() to keyWidth.value }
            .collect { (centers, kw) ->
                if (kw > 0f && centers.isNotEmpty()) {
                    // Letters only: the touch model turns a tap into per-letter
                    // likelihoods, and the punctuation keys the map also tracks
                    // (for the apostrophe setting) are not candidates for that.
                    onTouchKeys(
                        centers.entries
                            .filter { it.key !in GlidePunctuationChars }
                            .map { (char, c) -> KeyCenter(char, c.x / kw, c.y / kw) },
                    )
                }
            }
    }
    // Drop any in-flight remap when the feature switches off or the layout
    // changes: a release arriving after such a change must not apply a decision
    // made against the old grid (the down-observer that would have cleared it is
    // gone once smartHit is false).
    LaunchedEffect(smartHit, layout) { hitRemap.clear() }
    val trail = remember { GlideTrail() }
    val picker = remember { GlidePickerState() }
    // The keyboard's own haptic, which already routes through the service and
    // respects the user's feedback settings — not Compose's, whose name this
    // composition local deliberately shadows.
    val pickerHaptic = LocalHapticFeedback.current
    // Read inside the pointer loop, which outlives the composition that started
    // it — a captured value would be whatever the stroke began with.
    val glideChoices = rememberUpdatedState(state.glideChoices)
    val kbTheme = LocalKbTheme.current
    val trailColor = kbTheme.gestureTrail
    // The board's one preview bubble. Hoisted here so pressing a key publishes to
    // it instead of raising a window of the key's own.
    val keyPreview = remember { KeyPreviewState() }
    // The eight colours the keys themselves are painted with. Remembered so the
    // resolved rows below compare it by identity rather than by value.
    val palette = remember(kbTheme) { kbTheme.keyPalette() }
    // The key-press particle burst. The field and its glyphs exist only while
    // the theme carries an effect (reduce motion and power saving both zero
    // the count); the spawn lambda is what the keys see, and it reads the box
    // origin live so a burst lands under the finger wherever the grid sits.
    val particleField = remember { ParticleField() }
    val particleGlyphs = rememberEffectGlyphs(kbTheme)
    val particleBurst = burstCount(kbTheme)
    val onBurst: ((Rect) -> Unit)? =
        if (particleBurst > 0 && particleGlyphs.isNotEmpty()) {
            remember(particleField, particleBurst, particleGlyphs) {
                { bounds ->
                    particleField.spawn(
                        bounds.center.x - boxOrigin.x,
                        bounds.center.y - boxOrigin.y,
                        particleBurst,
                        particleGlyphs.size,
                        SystemClock.uptimeMillis(),
                    )
                }
            }
        } else {
            null
        }
    // Customisable glide-trail + start-sensitivity knobs (Settings → Gestures).
    val gesture = state.settings.gesture
    val trailMs = gesture.trailDurationMs.toLong()
    val trailOpacity = gesture.trailOpacity
    val trailHeadWidth = gesture.trailWidthDp
    val startSlop = gesture.startThresholdSlop
    val cooldownMs = gesture.postTypeCooldownMs
    // Which key a glide reads as an apostrophe. Read through a State because the
    // grid is built inside the pointer loop, which outlives this composition.
    val apostropheKey = rememberUpdatedState(gesture.apostropheKey)
    // The spacebar cannot both end a word mid-stroke and stand for an
    // apostrophe: one crossing, two readings. Choosing it as the apostrophe key
    // stands the multi-word split down for as long as that choice holds.
    val spaceGlide = gesture.spaceGlideMultiWord &&
        gesture.apostropheKey != GlideApostropheKey.SPACE
    // Stamp of the last tap-typed key (uptime ms). A glide starting within
    // [cooldownMs] of it has to travel further before it takes over, so a stray
    // slide off a key during fast tapping is not misread as a swipe-word. Held
    // in a State so the tap handlers below can write it without restarting the
    // gesture detector, which reads it live inside its pointer loop.
    val lastKeyPressTime = remember { mutableLongStateOf(0L) }
    val stampedOnKey = remember(onKey) {
        { k: Key -> lastKeyPressTime.longValue = SystemClock.uptimeMillis(); onKey(k) }
    }
    val stampedOnText = remember(onText) {
        { t: String -> lastKeyPressTime.longValue = SystemClock.uptimeMillis(); onText(t) }
    }
    val dotCooldownMs = gesture.handwriteDotCooldownMs
    // Uptime of the last *drawn* handwriting stroke. For [dotCooldownMs] after
    // it, a tap over the letters is grabbed as an ink dot (the mark on an i/j/t)
    // instead of typing, so a two-part character can be completed without the
    // dot committing a letter.
    val lastHwStrokeTime = remember { mutableLongStateOf(0L) }
    // Whether ink is still waiting to be recognized. The dot window only means
    // anything while a character is unfinished: once the strokes have been
    // recognized and committed there is nothing for a dot to join, so grabbing
    // the tap would swallow it — neither typing the key nor adding to a glyph.
    // Read live inside the gesture loop, which must not restart per stroke.
    val hwPendingInk = rememberUpdatedState(state.handwriting.strokes.isNotEmpty())
    // Points of the handwriting stroke being drawn right now (box space); the
    // finished strokes waiting for recognition come back from service state.
    var hwActiveStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // Touch-exploration pass-through: while a screen reader is running and the
    // user picked that mode, the app's own accessibility service hands the key
    // grid's rectangle back to the keyboard so its gestures (spacebar cursor
    // slide, backspace word swipe, glide, handwriting) still see real touches.
    //
    // Only the grid, never the whole window: the suggestion strip, the toolbar
    // and every panel stay outside it, so TalkBack keeps exploring those
    // normally. A panel replacing the keys takes this composable with it,
    // which retracts the carve-out on its own.
    val passthroughKeys = LocalTouchExploration.current &&
        state.settings.screenReaderMode == ScreenReaderMode.PASSTHROUGH &&
        LocalPassthroughService.current
    val hostView = LocalView.current
    LaunchedEffect(passthroughKeys, boxOrigin, boxSize, hostView) {
        if (!passthroughKeys || boxSize.width == 0 || boxSize.height == 0) {
            KeyboardPassthrough.publishRegion(null)
        } else {
            // boxOrigin is relative to the compose root (the IME's input
            // view); the framework wants display coordinates.
            val origin = IntArray(2).also { hostView.getLocationOnScreen(it) }
            val left = origin[0] + boxOrigin.x.roundToInt()
            val top = origin[1] + boxOrigin.y.roundToInt()
            KeyboardPassthrough.publishRegion(
                android.graphics.Rect(left, top, left + boxSize.width, top + boxSize.height),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { KeyboardPassthrough.publishRegion(null) }
    }

    // Drives the age fade. Keyed on `visible`, which flips twice a stroke, so
    // this restarts when a glide begins and ends — not when the finger moves.
    // After finger-up the trail is left in place to fade out on its own; tick
    // reports false once every point has expired.
    LaunchedEffect(trail.visible) {
        while (trail.visible) {
            withFrameMillis { now -> trail.tick(now, trailMs) }
        }
    }
    // A trail outlives the thing that drew it otherwise: switching the feature
    // off or changing the layout leaves the last stroke painted over the new
    // grid, because the pointer loop that would have released it is gone.
    DisposableEffect(gestureEnabled, layout) {
        onDispose { trail.clear() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(KeyRowsTestTag)
            .onGloballyPositioned {
                boxOrigin = it.positionInRoot()
                boxSize = it.size
            }
            .pointerInput(gestureEnabled, spaceGlide, startSlop, cooldownMs, trailMs) {
                if (!gestureEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    // Post-typing cooldown: right after a tap, hold the glide back
                    // by requiring more travel, fading out across the window. A
                    // finger that lands long after the last keypress swipes as
                    // normal (boost 1×).
                    val sinceTap = down.uptimeMillis - lastKeyPressTime.longValue
                    val cooldownBoost = if (cooldownMs > 0 && sinceTap in 0 until cooldownMs.toLong()) {
                        1f + POST_TYPE_SLOP_BOOST * (1f - sinceTap.toFloat() / cooldownMs)
                    } else {
                        1f
                    }
                    val effectiveSlop = startSlop * cooldownBoost
                    var isGesture = false
                    // Completed word segments (multi-word glide) plus the one
                    // being drawn now. With spaceGlide off there is only ever
                    // one segment — the whole stroke, spacebar points included.
                    val segments = ArrayList<List<GesturePoint>>()
                    // Reassigned wholesale whenever a word segment closes, so it
                    // is a `var` holding a mutable buffer on purpose.
                    @Suppress("DoubleMutabilityForCollection")
                    var seg = ArrayList<GesturePoint>()
                    var wasOverSpace = false
                    // Wall-clock stamp of the last preview. Sample counting was
                    // digitizer-rate dependent — every sixth report is ten
                    // decodes a second at 60 Hz and forty at 240 Hz, so the
                    // same swipe cost four times as much on a better screen.
                    var lastPreviewMs = 0L
                    // Where the finger last actually moved, and when — the
                    // picker's dwell trigger measures against this rather than
                    // against consecutive samples, so a slow drift never
                    // accumulates into a "hold".
                    var stillAt = down.position
                    var stillSince = down.uptimeMillis
                    // The letter grid, built once per stroke rather than per
                    // preview: it cannot change while a finger is down.
                    var keyList: List<KeyCenter>? = null
                    seg.add(GesturePoint(down.position.x, down.position.y, down.uptimeMillis))
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (isGesture) change.consume()
                            break
                        }
                        if (!isGesture && keyWidth.value > 0f &&
                            (change.position - down.position).getDistance() > slop * effectiveSlop &&
                            nearLetterKey(
                                down.position,
                                liveCenters.value,
                                keyWidth.value,
                                // The apostrophe key starts a stroke too, once it
                                // has that job: the possessive flick begins there.
                                allow = setOfNotNull(apostropheKey.value.sourceChar),
                            )
                        ) {
                            isGesture = true
                            stillAt = change.position
                            stillSince = change.uptimeMillis
                            trail.begin()
                            // Built once per stroke, from the layout rather than
                            // the measured map alone: a key's shifted and
                            // long-pressed characters have no centre of their
                            // own and take the one their base label reported.
                            keyList = liveLayouts.value.glideKeys(
                                apostropheCenter = apostropheCenter(
                                    apostropheKey.value,
                                    liveCenters.value,
                                    spaceRect.value,
                                    boxOrigin,
                                ),
                            ) { char ->
                                liveCenters.value[char]?.let { it.x to it.y }
                            }
                        }
                        if (isGesture) {
                            change.consume()
                            // Crossing the spacebar ends the current word and
                            // begins the next, so a stroke can chain words
                            // without lifting. Spacebar points anchor no letter,
                            // so they are dropped from the word's shape rather
                            // than added to either side.
                            // spaceRect is in root space; lift the box-local
                            // touch point into root space to test it.
                            val overSpace = spaceGlide &&
                                spaceRect.value?.contains(change.position + boxOrigin) == true
                            if (overSpace) {
                                if (!wasOverSpace && seg.size >= 3) {
                                    segments.add(seg)
                                    seg = ArrayList()
                                }
                            } else {
                                seg.add(
                                    GesturePoint(
                                        change.position.x,
                                        change.position.y,
                                        change.uptimeMillis,
                                    ),
                                )
                            }
                            wasOverSpace = overSpace
                            // The picker: a finger that stops moving while the
                            // decode is a close call is asking to be asked.
                            // Measured against where it stopped, not the last
                            // sample, so a slow drift never counts as still.
                            val travelled = (change.position - stillAt).getDistance()
                            if (travelled > keyWidth.value * PICKER_STILL_WIDTHS) {
                                stillAt = change.position
                                stillSince = change.uptimeMillis
                            } else if (
                                !picker.offered &&
                                glideChoices.value.size > 1 &&
                                // Not on a stroke that has already chained a
                                // word across the spacebar: the multi-word
                                // commit path decodes each segment on its own
                                // and has nowhere to put a hand-picked answer,
                                // so offering one would be offering a choice
                                // that gets quietly dropped.
                                segments.isEmpty() &&
                                change.uptimeMillis - stillSince >= PICKER_DWELL_MS
                            ) {
                                picker.open(
                                    glideChoices.value, change.position.x, change.position.y,
                                )
                                // Fired here rather than routed through the
                                // service: the picker is the composable's own
                                // event, and threading a callback for it would
                                // mean another KeyboardScreen parameter on a
                                // function already at the method-size limit.
                                if (state.settings.hapticFeedback) pickerHaptic()
                            }
                            if (picker.words.isNotEmpty()) {
                                // Only on a real change. Snapshot state already
                                // ignores an equal write, but this runs per
                                // touch report and the intent is worth stating:
                                // crossing between targets should recompose the
                                // popup, moving within one should not.
                                val over = picker.targetAt(change.position.x, change.position.y)
                                if (over != picker.hover) picker.hover = over
                            }
                            // Appends and drops the aged tail in one go; only
                            // the draw phase ever reads it back.
                            trail.add(
                                change.position.x,
                                change.position.y,
                                change.uptimeMillis,
                                trailMs,
                            )
                            // Live preview of the word being drawn now, at a
                            // fixed wall-clock cadence.
                            val sincePreview = change.uptimeMillis - lastPreviewMs
                            if (sincePreview >= PREVIEW_INTERVAL_MS && seg.size >= 3) {
                                lastPreviewMs = change.uptimeMillis
                                keyList?.let { keys ->
                                    onGesturePreview(seg.toList(), keys, keyWidth.value)
                                }
                            }
                        }
                    }
                    if (isGesture) {
                        if (seg.size >= 4) segments.add(seg)
                        val words = segments.filter { it.size >= 4 }
                        val keys = keyList
                        // Lifting on a target takes that word; lifting anywhere
                        // else takes the decoder's own first choice, so an
                        // ignored picker costs nothing.
                        val chosen = picker.picked()
                        picker.close()
                        if (words.isNotEmpty() && keys != null) {
                            if (words.size > 1) {
                                onGestureWords(words, keys, keyWidth.value)
                            } else {
                                onGesture(words.first(), keys, keyWidth.value, chosen)
                            }
                        }
                        trail.release()
                    }
                }
            }
            // Handwriting: a drag over the keys is one ink stroke instead of a
            // glide. Only one of the two detectors is ever live — glide's
            // `gestureEnabled` is false whenever `handwriteSwipe` is true. A
            // press that never travels past the slop stays unconsumed and
            // falls through to the key, so taps still type.
            .pointerInput(handwriteSwipe, dotCooldownMs) {
                if (!handwriteSwipe) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    // Dot window: for a short spell after a drawn stroke, a tap
                    // over the letters is taken as another stroke of the same
                    // character (the dot/cross) rather than typing a key. Only
                    // over a letter, so space/enter still work; captured from the
                    // down so even a stationary tap is grabbed as ink.
                    val dotWindow = dotCooldownMs > 0 && keyWidth.value > 0f &&
                        hwPendingInk.value &&
                        down.uptimeMillis - lastHwStrokeTime.longValue in 0 until dotCooldownMs.toLong() &&
                        nearLetterKey(down.position, liveCenters.value, keyWidth.value)
                    var isStroke = dotWindow
                    // Whether the finger actually travelled — only a real drawn
                    // stroke reopens the dot window, so a dot-tap does not keep
                    // swallowing later taps.
                    var moved = false
                    val pts = ArrayList<HwPoint>()
                    val live = ArrayList<Offset>()
                    pts.add(HwPoint(down.position.x, down.position.y, down.uptimeMillis))
                    live.add(down.position)
                    if (isStroke) down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (isStroke) change.consume()
                            break
                        }
                        if ((change.position - down.position).getDistance() > slop * 2) {
                            isStroke = true
                            moved = true
                        }
                        if (isStroke) {
                            change.consume()
                            pts.add(HwPoint(change.position.x, change.position.y, change.uptimeMillis))
                            live.add(change.position)
                            hwActiveStroke = live.toList()
                        }
                    }
                    // A dot tap is a single-point stroke; a drawn stroke needs at
                    // least two points to have a shape.
                    if (isStroke && pts.size >= (if (moved) 2 else 1)) {
                        onKeyboardHandwritingStroke(HwStroke(pts.toList()), boxSize)
                        // Only a drawn stroke arms the dot window for the next tap.
                        if (moved) lastHwStrokeTime.longValue = SystemClock.uptimeMillis()
                    }
                    hwActiveStroke = emptyList()
                }
            }
            // Smart key-hit detection: watch every pointer-down on the Initial
            // pass (before the keys see it), and if a likelier neighbour should
            // claim this touch, record the remap for the owning key to consume
            // on release. Never consumes — taps, glides and long-presses are all
            // untouched; only which letter finally commits can change.
            .pointerInput(smartHit) {
                if (!smartHit) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) {
                            when {
                                change.changedToDownIgnoreConsumed() -> {
                                    val target = smartHitTarget(
                                        change.position,
                                        liveCenters.value,
                                        keyWidth.value,
                                        nextBias.value,
                                    )
                                    if (target != null) {
                                        hitRemap[change.id] = target
                                    } else {
                                        hitRemap.remove(change.id)
                                    }
                                }
                                // Any lift OR cancel (glide steals the pointer):
                                // once it is no longer pressed the remap is spent.
                                !change.pressed -> hitRemap.remove(change.id)
                            }
                        }
                    }
                }
            }
            // Touch-position capture for the engine's typo model: record every
            // down, always on (unlike smart hit above). Never consumes; the
            // committed key reads the position via smartResolve on release.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) {
                            if (change.changedToDownIgnoreConsumed()) {
                                // Ids increase monotonically, so entries are
                                // never overwritten — prune wholesale instead
                                // of on lift (see downPositions above).
                                if (downPositions.size > 32) downPositions.clear()
                                downPositions[change.id] = change.position
                            }
                        }
                    }
                }
            },
    ) {
        // No spacing between cells: each key's touch target fills its whole
        // grid cell (gaps included) so a press landing between two keys
        // still hits the nearest one. The visual gap comes from per-key
        // padding inside KeyButton.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KeyRowsPadHorizontal, vertical = KeyRowsPadVertical),
        ) {
            // Remembered, not written inline: these two are parameters of every
            // row and every key, so a fresh instance per composition would be
            // enough on its own to stop the whole grid from skipping. Both read
            // their state (the centres map, the box origin) live when the layout
            // calls them, so holding one instance changes nothing they see.
            val onLetterPositioned: (Char, LayoutCoordinates) -> Unit = remember(keyCenters) {
                { letter, coords ->
                    val topLeft = coords.positionInRoot() - boxOrigin
                    keyCenters[letter] = Offset(
                        topLeft.x + coords.size.width / 2f,
                        topLeft.y + coords.size.height / 2f,
                    )
                }
            }
            // Records the spacebar's rect in *root* coordinates for the
            // multi-word split. Stored root-relative rather than box-relative on
            // purpose: this callback can fire before the Box's own
            // onGloballyPositioned has set `boxOrigin`, and unlike the letter
            // centres (a state map the keys keep re-reporting) it fires only
            // once, so subtracting a still-zero `boxOrigin` here would leave the
            // rect stuck in absolute space and the containment test — which runs
            // in box space — would never hit. The gesture loop adds the live
            // `boxOrigin` at test time instead, when the Box is laid out.
            val onSpacePositioned: (LayoutCoordinates) -> Unit = remember(spaceRect) {
                { coords -> spaceRect.value = coords.boundsInRoot() }
            }
            val settings = state.settings
            val split = settings.splitKeyboard
            val splitGapPercent = settings.splitGapPercent
            // The focused field wants a keypad, which suppresses the preview
            // bubble unless the user opted back in.
            val numericField = state.fieldKind.isNumericPad
            val mode = state.layoutMode
            // The digits keep the same slot on every layer, so switching
            // layers moves neither the row nor the pad below it. The `?123`
            // layer leads with its own digit row, which would be a second
            // copy directly underneath — `bodyRows` swaps that one out for
            // the symbols the layer otherwise has no room for.
            val numberRow = numberRowShown(state)
            val bodyRows = remember(layout, mode, numberRow) {
                // Only when that first row really is the digits. A custom
                // symbols layer that leads with something else would otherwise
                // lose its top row outright, with nothing on screen to explain
                // where it went.
                val leadsWithDigits = layout.rows.firstOrNull()
                    ?.all { it.action == KeyAction.Text && (it.output ?: it.label).isSingleDigit() }
                    ?: false
                if (numberRow && mode == LayoutMode.SYMBOLS && leadsWithDigits) {
                    listOf(SymbolsFillRow) + layout.rows.drop(1)
                } else {
                    layout.rows
                }
            }
            // Hoisted out of the render loop so the digit row resolves in the
            // same pass as the body rows below; null when it is not shown.
            val extraRow = if (numberRow) {
                // Follows the same guard as the pad itself, so a search box
                // opened over a number field gets its digit row back.
                val kind = if (numericPadActive(state)) state.fieldKind else FieldKind.TEXT
                // A layout can supply its own row for this layer; the built-in
                // choices below are the fallback rather than the rule.
                val authored = state.authoredNumberRow(state.layoutMode)
                // The digit row tracks the active layer (and, optionally, shift)
                // so the same slot serves more symbols the deeper the user goes:
                // digits on letters/symbols-1, extra symbols on symbols-2, and —
                // when the option is on — the symbol fill row while shift is held
                // on the letters layer.
                val shiftSymbols = state.settings.layoutBehavior.numberRowShiftSymbols
                // On an expanded tablet grid this row also carries backspace,
                // which the body gave up to make room for the mirrored shift —
                // so the two answers have to come from the same condition, or
                // the keyboard has no backspace on it anywhere. Never over a
                // numeric field: that path keeps the four-column keypad, so
                // nothing was given up and a stray ⌫ would just be litter.
                val tabletRow = state.layouts.gridWidth != null && !numericPadActive(state)
                remember(
                    kind,
                    authored,
                    state.layoutMode,
                    state.shiftState,
                    shiftSymbols,
                    tabletRow,
                ) {
                    val base = authored ?: when {
                        // A keypad already leads with digits, so the row
                        // carries what the pad lacks rather than a second set
                        // of the same numbers.
                        kind == FieldKind.PHONE ->
                            listOf("+", "*", "#", ",", ";", "(", ")", "-", "/", ".")
                                .map { Key(it) }
                        kind.isNumericPad ->
                            listOf("+", "-", "*", "/", "=", "(", ")", "%", ":", ".")
                                .map { Key(it) }
                        // Symbols-2 reuses the number-row slot for the arrow and
                        // comparison symbols it has nowhere else to put.
                        state.layoutMode == LayoutMode.SYMBOLS_SHIFTED -> SymbolsShiftedFillRow
                        // Opt-in: holding shift on the letters layer turns the
                        // digits into the symbol layer's bracket/math fill row,
                        // so symbols are reachable without switching layers.
                        state.layoutMode == LayoutMode.LETTERS &&
                            state.shiftState != ShiftState.OFF && shiftSymbols -> SymbolsFillRow
                        // Borrowed from the symbol layer so the digits carry
                        // their fraction and superscript long-presses here too.
                        else -> Layouts.SYMBOLS.rows.first()
                    }
                    if (tabletRow) base.expandNumberRowForTablet() else base
                }
            } else {
                null
            }
            val (numberRowVisual, bodyBlocks) =
                rememberKeyGrid(state, layout, bodyRows, extraRow, palette, gridWeight)
            if (numberRowVisual != null) {
                KeyRow(
                    row = numberRowVisual,
                    settings = settings,
                    split = split,
                    numericField = numericField,
                    layoutId = state.layoutId,
                    keyPreview = keyPreview,
                    onKey = stampedOnKey,
                    onText = stampedOnText,
                    onCursorMove = onCursorMove,
                    onLayoutSelect = onLayoutSelect,
                    onLetterPositioned = onLetterPositioned,
                    smartResolve = smartResolve,
                    onBurst = onBurst,
                )
            }
            // Layers shorter than the reserved span pad at the top rather than
            // stretching. The bottom row — space and enter — has to stay under
            // the thumb at the same height on every layer, and growing the keys
            // to fill instead would change a target size the user has learned,
            // mid-sentence. Without this the panels, sized to rowSpan, would be
            // taller than the keys.
            val padRows = state.layouts.rowSpan - bodyRows.size
            if (padRows > 0) {
                Spacer(
                    modifier = Modifier.height(
                        (state.settings.keyHeightDp.dp + keyGapV(state.settings) * 2) * padRows,
                    ),
                )
            }
            for (block in bodyBlocks) {
                when (block) {
                    is KeyGridBlock.Row -> KeyRow(
                        row = block.row,
                        settings = settings,
                        split = split,
                        numericField = numericField,
                        layoutId = state.layoutId,
                        keyPreview = keyPreview,
                        onKey = stampedOnKey,
                        onText = stampedOnText,
                        onCursorMove = onCursorMove,
                        onLayoutSelect = onLayoutSelect,
                        onLetterPositioned = onLetterPositioned,
                        onSpacePositioned = onSpacePositioned,
                        smartResolve = smartResolve,
                        onBurst = onBurst,
                    )

                    is KeyGridBlock.Band -> KeyBand(
                        band = block.band,
                        settings = settings,
                        numericField = numericField,
                        layoutId = state.layoutId,
                        keyPreview = keyPreview,
                        onKey = stampedOnKey,
                        onText = stampedOnText,
                        onCursorMove = onCursorMove,
                        onLayoutSelect = onLayoutSelect,
                        onLetterPositioned = onLetterPositioned,
                        onSpacePositioned = onSpacePositioned,
                        smartResolve = smartResolve,
                        onBurst = onBurst,
                    )
                }
            }
        }

        // The theme's stickers, laid over the keys and under the transient
        // layers (trail, ink, popups). A plain Canvas takes no touches, so
        // typing goes straight through a decal; it draws only when the board
        // draws, with no clock of its own.
        BoardDecalsOverlay(kbTheme)

        // The press bursts, over the decals and under the trail. Composed only
        // while particles live; the frame loop dies with them.
        KeyPressEffectsOverlay(particleField, particleGlyphs)

        // Anchored on the grid rather than on a key, and composed whether or not
        // anything is held — see [KeyPreviewOverlay].
        KeyPreviewOverlay(keyPreview, state.settings, boxOrigin, boxSize)

        // `visible` flips twice a stroke, so this composes and decomposes once
        // per glide. Everything that changes per sample is read inside the draw
        // lambda below, where it invalidates the draw alone.
        if (trail.visible) {
            Canvas(modifier = Modifier.matchParentSize()) {
                // Comet-style trail: each segment fades and thins with age,
                // so the tail dissolves behind the finger instead of leaving
                // the whole path on screen. Head width, life span and peak
                // opacity all come from the gesture settings.
                val count = trail.sampleCount(trail.revision)
                val headWidth = trailHeadWidth.dp.toPx()
                val tailWidth = headWidth * 0.3f
                for (i in 1 until count) {
                    val life =
                        (1f - trail.ageAt(i) / trailMs.toFloat()).coerceIn(0f, 1f)
                    if (life == 0f) continue
                    drawLine(
                        color = trailColor.copy(alpha = trailOpacity * life),
                        start = Offset(trail.x(i - 1), trail.y(i - 1)),
                        end = Offset(trail.x(i), trail.y(i)),
                        strokeWidth = tailWidth + (headWidth - tailWidth) * life,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        // Handwriting ink drawn over the keys: the finished strokes still
        // waiting to be recognized (service state) plus the one under the
        // finger. Unlike the glide trail these stay put until they commit,
        // so the user can see the whole letter or word taking shape.
        if (handwriteSwipe && state.handwriting.status == HandwritingStatus.READY) {
            val inkColor = LocalKbTheme.current.accent
            Canvas(modifier = Modifier.matchParentSize()) {
                val inkWidth = 5.dp.toPx()
                for (stroke in state.handwriting.strokes) {
                    val pts = stroke.points
                    // A tapped dot or cross is a one-point stroke: it has no
                    // segment to draw, so it needs a blob of its own or the
                    // mark on an i/j/t is invisible until the glyph commits.
                    if (pts.size == 1) {
                        drawCircle(
                            color = inkColor,
                            radius = inkWidth / 2f,
                            center = Offset(pts[0].x, pts[0].y),
                        )
                    }
                    for (i in 1 until pts.size) {
                        drawLine(
                            color = inkColor,
                            start = Offset(pts[i - 1].x, pts[i - 1].y),
                            end = Offset(pts[i].x, pts[i].y),
                            strokeWidth = inkWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                for (i in 1 until hwActiveStroke.size) {
                    drawLine(
                        color = inkColor,
                        start = hwActiveStroke[i - 1],
                        end = hwActiveStroke[i],
                        strokeWidth = inkWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        // The ambiguity picker, when a stroke stopped to ask. Lives in a Popup
        // because a top-row swipe has no room for it inside the grid, and the
        // window is the only thing that can draw above the keyboard.
        if (picker.words.isNotEmpty()) {
            GlidePickerTargets(picker, boxOrigin, boxSize)
        }

        // Floating preview of the word the swipe currently decodes to,
        // hovering above the finger like a key popup. Stood down while the
        // picker is up: the pill would be answering a question the picker is
        // still asking, and with the same word.
        val glideWord = state.glideWord
        if (glideWord != null && trail.visible && !trail.released && picker.words.isEmpty()) {
            val theme = LocalKbTheme.current
            val display = when (state.shiftState) {
                ShiftState.CAPS_LOCK -> glideWord.uppercase()
                ShiftState.ON -> glideWord.replaceFirstChar { it.uppercase() }
                ShiftState.OFF -> glideWord
            }
            var pillSize by remember { mutableStateOf(IntSize.Zero) }
            val gapPx = with(LocalDensity.current) { 56.dp.roundToPx() }
            Surface(
                modifier = Modifier
                    .offset {
                        // The fingertip is read here, in the placement lambda,
                        // rather than in the body: following the finger then
                        // costs a re-place instead of a recomposition.
                        val x = (trail.headX - pillSize.width / 2f).toInt()
                            .coerceIn(0, (boxSize.width - pillSize.width).coerceAtLeast(0))
                        val y = (trail.headY - gapPx - pillSize.height).toInt().coerceAtLeast(0)
                        IntOffset(x, y)
                    }
                    .onGloballyPositioned { pillSize = it.size },
                color = theme.popup,
                contentColor = theme.popupText,
                shape = theme.popupShape(),
                shadowElevation = elevationFor(theme.popupShapeKind, 4.dp),
            ) {
                Text(
                    text = display,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The words an ambiguous stroke is choosing between, laid under the fingertip.
 *
 * A row of targets rather than the single pill, anchored where the finger
 * stopped and clamped into the grid so none of them is off screen. The finger is
 * already held by the glide detector, so no key can fire underneath: sliding
 * onto a target highlights it and lifting there commits it.
 *
 * In a [Popup] with headroom above the grid for the same reason the key preview
 * is — a stroke that ends on the top row has nowhere inside the grid to put
 * this, and the pill it replaces could only ever clamp to the top edge and sit
 * on the keys.
 *
 * Each target reports its own rectangle into [picker] as it lands, in the
 * grid's coordinate space, which is what the pointer loop hit-tests against.
 */
@Composable
private fun GlidePickerTargets(
    picker: GlidePickerState,
    gridOrigin: Offset,
    gridSize: IntSize,
) {
    val theme = LocalKbTheme.current
    val density = LocalDensity.current
    val headroomPx = with(density) { (GlidePickerHeight + GlidePickerGap * 2).roundToPx() }
    val words = picker.words
    Popup(
        popupPositionProvider = remember(headroomPx) { GridOverlayPositionProvider(headroomPx) },
        properties = PreviewPopupProperties,
    ) {
        var rowSize by remember { mutableStateOf(IntSize.Zero) }
        Row(
            modifier = Modifier
                .offset {
                    // Centred on the fingertip and pushed clear of it, then
                    // clamped so the row never leaves the keyboard. Placement
                    // lambda, not the body: the anchor is a plain field.
                    val x = (picker.anchorX - rowSize.width / 2f).toInt()
                        .coerceIn(0, (gridSize.width - rowSize.width).coerceAtLeast(0))
                    val gap = with(density) { GlidePickerGap.roundToPx() }
                    val y = (picker.anchorY + headroomPx - gap - rowSize.height).toInt()
                        .coerceAtLeast(0)
                    IntOffset(x, y)
                }
                .onGloballyPositioned { rowSize = it.size },
            horizontalArrangement = Arrangement.spacedBy(GlidePickerGap),
        ) {
            words.forEachIndexed { index, word ->
                val hovered = picker.hover == index
                Surface(
                    modifier = Modifier
                        .height(GlidePickerHeight)
                        .onGloballyPositioned { coords ->
                            // Reported in the grid's own space, which is what
                            // the pointer loop measures touches in — the same
                            // root-minus-origin conversion the letter keys use,
                            // rather than arithmetic over the popup's offsets.
                            val topLeft = coords.positionInRoot() - gridOrigin
                            picker.place(
                                index,
                                Rect(topLeft, coords.size.toSize()),
                            )
                        },
                    color = if (hovered) theme.accent else theme.popup,
                    contentColor = if (hovered) theme.keyText else theme.popupText,
                    shape = theme.popupShape(),
                    shadowElevation = elevationFor(theme.popupShapeKind, if (hovered) 8.dp else 4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = word,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 18.sp,
                            fontWeight = if (hovered) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of keys. In split mode the row is cut near its midpoint and the
 * halves are pushed apart by a center gap sized as a percentage of the
 * keyboard width; a spacebar straddling the cut is divided between the halves.
 */
@Composable
private fun KeyRow(
    row: KeyRowVisual,
    settings: KeyboardSettings,
    split: Boolean,
    numericField: Boolean,
    layoutId: String,
    keyPreview: KeyPreviewState,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onCursorMove: (Int) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onLetterPositioned: (Char, LayoutCoordinates) -> Unit,
    onSpacePositioned: (LayoutCoordinates) -> Unit = {},
    smartResolve: (Key, PointerId) -> Key = { k, _ -> k },
    /** Spawns the theme's press burst at the key's bounds; null when off. */
    onBurst: ((Rect) -> Unit)? = null,
) {
    Row {
        if (row.sidePad > 0.01f) Spacer(modifier = Modifier.weight(row.sidePad))
        for (visual in row.left) {
            KeyCell(
                visual,
                Modifier.weight(visual.key.width),
                row.heightDp,
                settings,
                numericField,
                layoutId,
                keyPreview,
                onKey,
                onText,
                onCursorMove,
                onLayoutSelect,
                onLetterPositioned,
                onSpacePositioned,
                smartResolve,
                onBurst,
            )
        }
        // Split mode only: the halves are cut where the row was resolved, so an
        // unsplit row has nothing on the right and needs no gap.
        if (split) {
            Spacer(modifier = Modifier.weight(row.splitGapWeight))
            for (visual in row.right) {
                KeyCell(
                    visual,
                    Modifier.weight(visual.key.width),
                    row.heightDp,
                    settings,
                    numericField,
                    layoutId,
                    keyPreview,
                    onKey,
                    onText,
                    onCursorMove,
                    onLayoutSelect,
                    onLetterPositioned,
                    onSpacePositioned,
                    smartResolve,
                    onBurst,
                )
            }
        }
        if (row.sidePad > 0.01f) Spacer(modifier = Modifier.weight(row.sidePad))
    }
}

/**
 * A run of rows drawn as one block, because a key of it covers more than one —
 * see [KeyBandVisual].
 *
 * Placed by hand rather than by nested `Row`s: the band is the only parent every
 * key of it shares, so it is the only node that can hold a two-row key inside
 * its own bounds, which is what makes the whole key tappable rather than just
 * the half that overlaps its own row.
 *
 * Each edge is rounded independently off the same unit width, so neighbours stay
 * flush the way `Row`'s weights keep them — rounding widths instead would leave a
 * sub-pixel seam that accumulates across the row.
 */
@Composable
private fun KeyBand(
    band: KeyBandVisual,
    settings: KeyboardSettings,
    numericField: Boolean,
    layoutId: String,
    keyPreview: KeyPreviewState,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onCursorMove: (Int) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onLetterPositioned: (Char, LayoutCoordinates) -> Unit,
    onSpacePositioned: (LayoutCoordinates) -> Unit = {},
    smartResolve: (Key, PointerId) -> Key = { k, _ -> k },
    onBurst: ((Rect) -> Unit)? = null,
) {
    // Row pitch in px: the key height the row was given, plus the gap above and
    // below it, exactly as an ordinary KeyCell measures itself.
    val gap = keyGapV(settings)
    val rowPitch = with(LocalDensity.current) {
        band.rowHeights.map { (it.dp + gap * 2).roundToPx() }
    }
    // Row tops, and the band's own height as the last entry.
    val tops = IntArray(rowPitch.size + 1).also {
        for (i in rowPitch.indices) it[i + 1] = it[i] + rowPitch[i]
    }
    Layout(
        content = {
            for (slot in band.slots) {
                // heightDp is what this key would be on a row of its own; the
                // measure policy below hands it the real height, spanned rows
                // and the gaps between them included.
                KeyCell(
                    slot.visual,
                    Modifier,
                    band.rowHeights[slot.row],
                    settings,
                    numericField,
                    layoutId,
                    keyPreview,
                    onKey,
                    onText,
                    onCursorMove,
                    onLayoutSelect,
                    onLetterPositioned,
                    onSpacePositioned,
                    smartResolve,
                    onBurst,
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val unit = if (band.weight > 0f) width / band.weight else 0f
        val lefts = IntArray(measurables.size)
        val placeables = measurables.mapIndexed { index, measurable ->
            val slot = band.slots[index]
            val left = (unit * slot.x).roundToInt()
            val right = (unit * (slot.x + slot.width)).roundToInt()
            lefts[index] = left
            measurable.measure(
                Constraints.fixed(
                    width = (right - left).coerceIn(0, width),
                    height = tops[slot.row + slot.span] - tops[slot.row],
                ),
            )
        }
        layout(width, tops.last()) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(lefts[index], tops[band.slots[index].row])
            }
        }
    }
}

/**
 * One key in its grid cell. [sizeModifier] is how the cell gets its width: a
 * `Row`'s weight for an ordinary row, nothing at all inside a [KeyBand], whose
 * measure policy sizes its children itself. Taken as a parameter rather than
 * applied here so this is not tied to `RowScope`, which a band has no way to be.
 */
@Composable
private fun KeyCell(
    visual: KeyVisual,
    sizeModifier: Modifier,
    keyHeightDp: Int,
    settings: KeyboardSettings,
    numericField: Boolean,
    layoutId: String,
    keyPreview: KeyPreviewState,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onCursorMove: (Int) -> Unit,
    onLayoutSelect: (String) -> Unit,
    onLetterPositioned: (Char, LayoutCoordinates) -> Unit,
    onSpacePositioned: (LayoutCoordinates) -> Unit = {},
    smartResolve: (Key, PointerId) -> Key = { k, _ -> k },
    onBurst: ((Rect) -> Unit)? = null,
) {
    val key = visual.key
    // The key's centre is reported under the first character it writes, which
    // is the same character the glide grid anchors it by. Not `singleOrNull`:
    // a Bengali nukta key (ড়, ঢ়, য়) writes two characters, and keying it by
    // "exactly one" drops the key — and its shifted twin — off the grid.
    //
    // The comma, full stop and apostrophe keys report too, because the
    // apostrophe-in-a-glide setting needs to know where they are. They are not
    // letters and nothing treats them as letters: both consumers of the map that
    // are about letters filter them back out (see [GlidePunctuationChars]).
    val letter = key.label.takeIf { key.action == KeyAction.Text }
        ?.let { keySpelling(it) }?.first()
        ?: key.glidePunctuationChar()
    KeyButton(
        visual = visual,
        settings = settings,
        modifier = if (letter != null) {
            sizeModifier
                .onGloballyPositioned { onLetterPositioned(letter.lowercaseChar(), it) }
        } else if (key.action == KeyAction.Space) {
            sizeModifier
                .onGloballyPositioned { onSpacePositioned(it) }
        } else {
            sizeModifier
        },
        heightDp = keyHeightDp,
        numericField = numericField,
        layoutId = layoutId,
        keyPreview = keyPreview,
        onKey = onKey,
        onText = onText,
        onCursorMove = onCursorMove,
        onLayoutSelect = onLayoutSelect,
        smartResolve = smartResolve,
        onBurst = onBurst,
    )
}

/**
 * Cuts a row for split mode. A spacebar spanning the midpoint is divided
 * into a half per side (the left half loses its label so the language name
 * is not shown twice); otherwise the cut lands on the key boundary nearest
 * the midpoint, with ties going right so QWERTY splits asdfg | hjkl.
 */
internal fun splitKeys(keys: List<Key>): Pair<List<Key>, List<Key>> {
    // A custom layout can hand us an empty or one-key row: the cut search below
    // starts at index 1, and subList would throw on an empty one — a deleted row
    // would take the whole keyboard down in split mode.
    if (keys.size < 2) return keys to emptyList()
    val boundaries = FloatArray(keys.size + 1)
    for (i in keys.indices) boundaries[i + 1] = boundaries[i] + keys[i].width
    val mid = boundaries[keys.size] / 2f
    for (i in keys.indices) {
        if (keys[i].action == KeyAction.Space &&
            boundaries[i] < mid - 0.01f && boundaries[i + 1] > mid + 0.01f
        ) {
            val left = keys.subList(0, i) + keys[i].copy(label = "", width = mid - boundaries[i])
            val right = listOf(keys[i].copy(width = boundaries[i + 1] - mid)) +
                keys.subList(i + 1, keys.size)
            return left to right
        }
    }
    var cut = 1
    for (b in 2 until keys.size) {
        if (abs(boundaries[b] - mid) <= abs(boundaries[cut] - mid) + 0.001f) cut = b
    }
    return keys.subList(0, cut) to keys.subList(cut, keys.size)
}

/**
 * The punctuation keys that report a centre alongside the letters, for the
 * apostrophe-in-a-glide setting to find. Kept out of everything that means
 * "letter key" — the engine's touch model and [nearLetterKey] — so tracking them
 * cannot change where a tap lands or what starts a glide.
 */
private val GlidePunctuationChars = setOf(',', '.', '\'')

/** The character this key contributes to the centres map if it is punctuation. */
private fun Key.glidePunctuationChar(): Char? =
    if (action == KeyAction.Text) {
        (output ?: label).singleOrNull()?.takeIf { it in GlidePunctuationChars }
    } else {
        null
    }

/**
 * True when [position] falls within roughly one key of a tracked letter key.
 *
 * Letters only, which is why the punctuation keys the centres map also holds are
 * skipped: they are tracked for the apostrophe setting to find, and a slide off
 * the comma key must keep meaning exactly what it meant before.
 *
 * [allow] adds specific ones back. That is how the apostrophe key gets to start a
 * stroke once the user has given it that job, which the possessive flick needs
 * because it begins there rather than on a letter.
 */
private fun nearLetterKey(
    position: Offset,
    centers: Map<Char, Offset>,
    keyWidth: Float,
    allow: Set<Char> = emptySet(),
): Boolean = centers.any { (ch, center) ->
    (ch !in GlidePunctuationChars || ch in allow) &&
        (center - position).getDistance() < keyWidth
}

/**
 * Where the glide grid should put the apostrophe, in this box's space, or null
 * when the feature is off or the chosen key is not on the current layer.
 *
 * The punctuation keys report their centres under their own character, the same
 * way letters do, so three of the four choices are a map lookup. The spacebar is
 * the exception: it reports a rect in *root* space (the multi-word split needs
 * it there), so it is lifted back into box space here with the live origin.
 *
 * Null on a layout with no such key is the honest answer rather than a fallback
 * to some other key: a stroke aimed at a key that is not there would otherwise
 * spell an apostrophe the user never drew.
 */
private fun apostropheCenter(
    choice: GlideApostropheKey,
    centers: Map<Char, Offset>,
    spaceRect: Rect?,
    boxOrigin: Offset,
): Pair<Float, Float>? = when (choice) {
    GlideApostropheKey.OFF -> null
    GlideApostropheKey.SPACE -> spaceRect?.let {
        (it.center.x - boxOrigin.x) to (it.center.y - boxOrigin.y)
    }
    else -> choice.sourceChar
        ?.let { centers[it] }
        ?.let { it.x to it.y }
}

/** How hard a likely next letter pulls a boundary tap. Higher = wider steal. */
private const val SMART_HIT_STRENGTH = 0.5f

/** A favoured letter never claims a tap more than this many key-widths away. */
private const val SMART_HIT_MAX_REACH = 1.3f

/**
 * Smart key-hit detection. Given a touch [pos], the tracked letter [centers]
 * and the live next-letter [bias] (0..1 per letter), returns the letter whose
 * hitbox should claim this touch, or null to leave the plain-nearest key alone.
 *
 * Each centre's effective distance is shortened in proportion to how likely
 * that letter is next, so a likely neighbour can win a touch that landed just
 * inside the nominal key. The shortening is a fixed fraction of distance, so it
 * only ever flips the outcome near a shared edge — a press deep inside a key
 * stays with that key — and a favoured letter out of reach is ignored outright.
 */
private fun smartHitTarget(
    pos: Offset,
    centers: Map<Char, Offset>,
    keyWidth: Float,
    bias: Map<Char, Float>,
): Char? {
    if (keyWidth <= 0f || centers.isEmpty() || bias.isEmpty()) return null
    var nominal: Char? = null
    var nominalDist = Float.MAX_VALUE
    var best: Char? = null
    var bestScore = Float.MAX_VALUE
    for ((ch, center) in centers) {
        val d = (center - pos).getDistance()
        if (d < nominalDist) {
            nominalDist = d
            nominal = ch
        }
        val score = d / (1f + SMART_HIT_STRENGTH * (bias[ch] ?: 0f))
        if (score < bestScore) {
            bestScore = score
            best = ch
        }
    }
    // The plain-nearest key already wins: nothing to remap.
    if (best == null || best == nominal) return null
    // Never yank a tap onto a key the finger is nowhere near.
    val target = centers[best] ?: return null
    if ((target - pos).getDistance() > keyWidth * SMART_HIT_MAX_REACH) return null
    return best
}

/**
 * [currentLayout], memoized on everything it reads.
 *
 * Worth a memo because the rewrite pass below is not free — with any of the
 * key-rewriting preferences on (an emoji comma, the clipboard long-presses,
 * the full accent popups, a number row stripping duplicate digits) it copies
 * every key of every row — and it ran on each recomposition of the key grid,
 * which is to say on every keystroke. Handing back the *same* layout instance
 * also turns the `remember(layout)` keys downstream from a deep structural
 * comparison of the whole grid into a reference check.
 *
 * The keys are the whole read set of [currentLayout] and [numericPadActive]:
 * the layer objects, the layer being shown, the settings (every preference it
 * consults lives in there), the composer, the field's kind, and the flag for
 * the search boxes that suppress the numeric pad. **A read added to either
 * function has to be covered here**, or the grid will keep drawing the layout
 * from before it changed.
 */
@Composable
internal fun rememberCurrentLayout(state: KeyboardUiState): KeyboardLayout = remember(
    state.layouts,
    state.layoutMode,
    state.settings,
    state.composer,
    state.fieldKind,
    // The field's declared action, which is what `newlineAlternate` below reads
    // to decide whether the enter key carries the line break. The *field's*, not
    // [effectiveEnterAction] — that one moves with the shift key and would
    // rebuild the whole grid mid-word.
    state.enterAction,
    numericPadActive(state),
) {
    currentLayout(state)
}

internal fun currentLayout(state: KeyboardUiState): KeyboardLayout {
    if (numericPadActive(state)) {
        state.layouts.numeric?.let { return it }
    }
    val base = when (state.layoutMode) {
        LayoutMode.SYMBOLS -> state.layouts.symbols
        LayoutMode.SYMBOLS_SHIFTED -> state.layouts.symbolsShifted
        // Falls back to the letters when the layout has no Fn layer: a stored
        // Fn key can outlive the layer it points at, and onFn already refuses to
        // switch, so this only ever fires on a state built out of order.
        LayoutMode.FN -> state.layouts.fn ?: state.layouts.letters
        LayoutMode.LETTERS -> state.layouts.letters
    }
    // Email and URI fields keep the letter layouts but trade the bottom-row
    // comma — punctuation neither field uses — for the character they are
    // full of, and put domain endings on the period key's long press. Both
    // are otherwise a trip through the symbols layer for every address.
    val lettersLayer = state.layoutMode == LayoutMode.LETTERS
    // The character and its popup, not a finished key: the key is built by
    // copying the slot it replaces, so a comma the layout drew wide stays wide.
    // A fresh Key here reset the width, and the bottom row jumped on the way
    // into a URL field (issue #25) — the same trap `commaAsEmoji` fell into.
    val fieldKey: Pair<String, List<String>>? = when {
        !lettersLayer -> null
        state.fieldKind == FieldKind.EMAIL -> "@" to emptyList()
        state.fieldKind == FieldKind.URI -> "/" to listOf("?", "#", "&", "=")
        else -> null
    }
    val domainAlternates = when {
        !lettersLayer -> emptyList()
        state.fieldKind == FieldKind.EMAIL -> listOf(".com", ".net", ".org", ".edu", ".co")
        state.fieldKind == FieldKind.URI ->
            listOf(".com", ".org", ".net", "www.", "https://", "/")
        else -> emptyList()
    }
    // Bengali (and any other script that ends a sentence with something other
    // than a full stop) types its own mark from the key next to the spacebar,
    // with "." kept on the long press for numbers, file names and URLs. Only
    // where the layout has not already put the mark there itself — the fixed
    // Bengali layouts carry দাঁড়ি on their own keys.
    val fullStop = state.script.fullStop.takeIf { it != "." }
    // Both emoji-key preferences exist because a phone's bottom row has no spare
    // slot, so one of the keys already there has to give it up. An expanded
    // tablet grid has a real emoji key of its own, and applying either here would
    // draw a second one — and `globeAsEmoji`, which ships on, would take language
    // switching off the board entirely to do it.
    val tabletGrid = state.layouts.gridWidth != null
    // Optional Gboard-style emoji key: the letter layouts' comma key becomes
    // an emoji-panel key, with comma demoted to its long-press alternates.
    val commaAsEmoji = state.settings.commaAsEmoji && !tabletGrid &&
        state.layoutMode == LayoutMode.LETTERS
    // 🌐 → emoji key: language switching lives on spacebar swipes instead.
    val globeAsEmoji = state.settings.globeAsEmoji && !tabletGrid
    // The two keys either side of the spacebar trade places, so whichever one
    // is the emoji key sits in the outer slot and the comma next to the space.
    // Not scoped to the letter layers: the row would otherwise reshuffle on the
    // way into ?123, which is worse than either order.
    val swapCommaGlobe = state.settings.swapCommaAndGlobe
    // With the dedicated number row on, the digits duplicated on the top-row
    // letters' long press are redundant — drop them so those keys go straight
    // to their accents (or lose their popup entirely).
    val stripDigits = state.settings.numberRow && state.layoutMode == LayoutMode.LETTERS
    // A44: user-chosen currency glyphs replace the $ key's built-in popup.
    val currencyKeys = state.settings.layoutBehavior.currencyKeys
    // A43: merge the full accent set into each Latin letter's long-press popup.
    val allAccents = state.settings.layoutBehavior.showAllPopupKeys &&
        state.layoutMode == LayoutMode.LETTERS && !state.composer.isClusterShaping
    // The clipboard/undo/redo hold shortcuts, on whichever keys the user has
    // bound them to. The keys are settings rather than the literal a/c/v/x/z/y
    // they used to be: on a layout with no Latin letters there was no `a` to
    // hold, so all six switches did nothing and said nothing about why.
    val clipboardKeys: Map<String, ClipboardKeyAction> =
        if (state.layoutMode == LayoutMode.LETTERS && !state.composer.isClusterShaping) {
            val longPress = state.settings.longPressLetterActions
            buildMap {
                fun bind(on: Boolean, slot: Int, action: ClipboardKeyAction) {
                    if (!on) return
                    longPress.letterFor(slot)?.let { put(it.toString(), action) }
                }
                bind(longPress.selectAll, 0, ClipboardKeyAction.SELECT_ALL)
                bind(longPress.copy, 1, ClipboardKeyAction.COPY)
                bind(longPress.paste, 2, ClipboardKeyAction.PASTE)
                bind(longPress.cut, 3, ClipboardKeyAction.CUT)
                bind(longPress.undo, 4, ClipboardKeyAction.UNDO)
                bind(longPress.redo, 5, ClipboardKeyAction.REDO)
            }
        } else {
            emptyMap()
        }
    // A field that declares Send/Go/Search takes the enter key over, and the
    // line break it displaces has nowhere else to go — so it moves to the key's
    // long press, the way Gboard offers it. Only where the key is actually
    // showing the app's action: on an ordinary multi-line box Enter already
    // types the break and an alternate offering the same thing is noise.
    //
    // Keyed on the field's own action rather than [effectiveEnterAction], which
    // folds in the live shift: reading that here would rebuild the whole grid on
    // every shift press, and the entry belongs in the popup either way.
    val newlineAlternate = state.enterAction != EnterAction.DEFAULT
    if (!commaAsEmoji && !globeAsEmoji && !swapCommaGlobe && !stripDigits &&
        clipboardKeys.isEmpty() && fieldKey == null && domainAlternates.isEmpty() &&
        currencyKeys.isEmpty() && !allAccents && fullStop == null && !newlineAlternate
    ) {
        return base
    }
    val bottom = base.rows.lastIndex
    // copy rather than KeyboardLayout(name, rows): a positional rebuild
    // silently drops any field later added to the class.
    val rewritten = base.rows.mapIndexed { rowIndex, row ->
        row.map { rowKey ->
            val role = rowKey.roleIn(rowIndex, bottom)
            // Ahead of the field/emoji rewrites below, which may replace the
            // period key outright or hang domain endings off it — the script's
            // own mark and the "." it displaces travel together either way. A
            // layout that already types the mark is left alone.
            val key = if (
                fullStop != null && role == KeyRole.Period &&
                (rowKey.output ?: rowKey.label) == "."
            ) {
                rowKey.copy(
                    label = fullStop,
                    output = null,
                    longPress = listOf(".") + rowKey.longPress.filterNot { it == fullStop },
                )
            } else {
                rowKey
            }
            var mapped = when {
                // Field adaptation outranks the emoji-key preference: an
                // email box needs its @ more than a shortcut to emoji.
                // Everything the slot decides about itself — width, span, label
                // scale — survives; everything about the character it used to
                // type is replaced, icons and flicks included.
                fieldKey != null && role == KeyRole.Comma -> key.copy(
                    label = fieldKey.first,
                    output = null,
                    shiftLabel = null,
                    longPress = fieldKey.second,
                    actionAlternates = emptyList(),
                    icon = null,
                    iconHint = null,
                    flick = emptyMap(),
                )
                domainAlternates.isNotEmpty() && role == KeyRole.Period ->
                    key.copy(longPress = domainAlternates + key.longPress)
                commaAsEmoji && role == KeyRole.Comma ->
                    // copy, not a fresh Key: building one from scratch here
                    // discarded a custom width, so the bottom row jumped
                    // whenever the preference was on.
                    key.copy(
                        action = KeyAction.Emoji,
                        longPress = listOf(key.output ?: key.label) + key.longPress,
                    )
                globeAsEmoji && key.action == KeyAction.LanguageSwitch ->
                    key.copy(action = KeyAction.Emoji)
                else -> key
            }
            if (stripDigits && mapped.longPress.any { it.isSingleDigit() }) {
                mapped = mapped.copy(longPress = mapped.longPress.filterNot { it.isSingleDigit() })
            }
            // A44: the $ key takes the user's currency glyphs as its popup.
            if (currencyKeys.isNotEmpty() && (mapped.output ?: mapped.label) == "$") {
                mapped = mapped.copy(longPress = currencyKeys)
            }
            // A43: merge the full accent variant set for this Latin letter,
            // on top of whatever the layout already lists. Lowercase glyphs,
            // matching the built-in accent popups; non-letters have no entry.
            if (allAccents && mapped.action == KeyAction.Text) {
                val letter = mapped.output ?: mapped.label
                if (letter.length == 1) {
                    LatinAccents[letter.lowercase().first()]?.let { extra ->
                        mapped = mapped.copy(longPress = (mapped.longPress + extra).distinct())
                    }
                }
            }
            // Keyed on what the key types, not what it is labelled: a layout
            // that shows "A" and outputs "a" was silently skipped. A value
            // already set on the key wins, so a layout can put a clipboard
            // shortcut somewhere other than a/c/v/x.
            if (mapped.action == KeyAction.Text && mapped.clipboardAction == null) {
                clipboardKeys[mapped.output ?: mapped.label]?.let {
                    mapped = mapped.copy(clipboardAction = it)
                }
            }
            // Appended rather than prepended, so a layout that authored its own
            // alternates onto the enter key keeps them in the order it wrote
            // them and this one lands at the end. Skipped outright if the layout
            // already offers a newline of its own.
            if (newlineAlternate && mapped.action == KeyAction.Enter &&
                mapped.actionAlternates.none { it.action == KeyAction.Newline }
            ) {
                mapped = mapped.copy(
                    actionAlternates = mapped.actionAlternates +
                        KeyAlternate(action = KeyAction.Newline, icon = "enter"),
                )
            }
            mapped
        }
    }
    return base.copy(
        rows = if (!swapCommaGlobe) {
            rewritten
        } else {
            rewritten.mapIndexed { rowIndex, row ->
                swapCommaAndGlobe(base.rows[rowIndex], row, rowIndex, bottom)
            }
        },
    )
}

/**
 * Trades the comma key and the 🌐 key's places in one row, leaving every other
 * key where it was. No-op for a row that hasn't got both.
 *
 * The positions are read off [source] rather than [rewritten] because the pass
 * above may already have turned either one into the emoji key (or the comma
 * into an @ for an email field) — by then there is nothing left to identify
 * them by.
 */
private fun swapCommaAndGlobe(
    source: List<Key>,
    rewritten: List<Key>,
    rowIndex: Int,
    lastRow: Int,
): List<Key> {
    val comma = source.indexOfFirst { it.roleIn(rowIndex, lastRow) == KeyRole.Comma }
    val globe = source.indexOfFirst { it.action == KeyAction.LanguageSwitch }
    if (comma < 0 || globe < 0) return rewritten
    return rewritten.toMutableList().also {
        val held = it[comma]
        it[comma] = it[globe]
        it[globe] = held
    }
}

/**
 * Whether the focused field's keypad should be showing.
 *
 * A numeric field gets its keypad whatever the layout mode says — the pads
 * have no ?123 key to leave by, so the letter/symbol cycle does not apply.
 * The exception is anything that reroutes keystrokes away from the editor:
 * the emoji, media, dictionary and clipboard search boxes, a plugin's own text
 * box and the typing test all need letters, and a digits-only pad would leave
 * them impossible to type in.
 */
private fun numericPadActive(state: KeyboardUiState): Boolean =
    state.fieldKind.isNumericPad &&
        !state.emojiSearchActive &&
        !state.dictionarySearchActive &&
        !state.clipboardSearchActive &&
        !(state.mediaSearchActive && state.panel.hasMediaSearch) &&
        !state.pluginTypingActive &&
        !state.typingTestActive


private fun String.isSingleDigit(): Boolean = length == 1 && this[0].isDigit()

/**
 * Places a popup centered above its anchor with a clear gap, so the
 * character bubble and long-press alternates are not hidden under the
 * pressing finger.
 */
private class AboveAnchorPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Gap a popup keeps from the key it belongs to, so the bubble and the long-press
 * alternates are not hidden under the pressing finger. Named because the preview
 * overlay sizes its headroom from it — the two have to agree or the top row's
 * bubble is clipped.
 */
private val KeyPopupGap = 10.dp

@Composable
private fun rememberAboveAnchorPopup(): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        AboveAnchorPopupPositionProvider(with(density) { KeyPopupGap.roundToPx() })
    }
}

/**
 * Places the popup so its bottom edge lines up with the pressed key's
 * bottom, growing upward from the key itself — the tall stock-keyboard
 * style where the bubble visually replaces the key.
 */
private object OnKeyPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** Visual gap between keys, provided as padding inside each touch cell. */
private val KeyGapHorizontal = 2.5.dp
private val KeyGapVertical = 4.dp

/**
 * The gaps above, scaled by the user's key-spacing setting. Every gap-consuming
 * site reads these so the touch-cell padding and the height math in
 * [keyRowsHeight] scale together — the keyboard's height and the panels that
 * mirror it stay in step when the spacing changes.
 */
private fun keyGapH(settings: KeyboardSettings): Dp = KeyGapHorizontal * settings.keyGapScale
private fun keyGapV(settings: KeyboardSettings): Dp = KeyGapVertical * settings.keyGapScale

/**
 * Peak extra glide-start slop applied the instant after a tap, on top of the
 * user's start-distance multiplier, decaying to 0 across the post-typing
 * cooldown window. 2.5 means a glide starting right after a keypress must travel
 * 3.5× as far (1 + 2.5) as it normally would before it is read as a swipe-word.
 */
private const val POST_TYPE_SLOP_BOOST = 2.5f

/**
 * How often a glide in progress asks the decoder for a preview word.
 *
 * Counting samples instead ties the rate to the digitizer: every sixth touch
 * report is ten decodes a second on a 60 Hz panel and forty on a 240 Hz one, so
 * the better screen paid four times as much for the same swipe. 40 ms is about
 * two frames — fast enough that the floating word keeps up with the finger.
 */
private const val PREVIEW_INTERVAL_MS = 40L

/**
 * How long the finger has to hold still before an ambiguous stroke offers its
 * choices. Long enough that pausing to think mid-word does not trip it, short
 * enough to feel like an answer rather than a delay.
 */
private const val PICKER_DWELL_MS = 250L

/**
 * How far the finger may drift and still count as held, in key widths. A finger
 * resting on glass is never perfectly still, and a threshold that demanded it
 * would mean the picker never appeared for anybody.
 */
private const val PICKER_STILL_WIDTHS = 0.3f

/** The picker's target height and the gaps around it. */
private val GlidePickerHeight = 44.dp
private val GlidePickerGap = 10.dp

/** Vertical padding of the [KeyRows] column, mirrored into [keyRowsHeight]. */
private val KeyRowsPadVertical = 2.dp

/**
 * Horizontal padding of the [KeyRows] column. Named because the gesture
 * decoder's key-width derivation has to subtract it to get the real cell width.
 */
private val KeyRowsPadHorizontal = 1.5.dp

/**
 * Default height of [TopBar] (suggestions/toolbar row), and the fallback when
 * no settings are on hand. The live height is [topBarHeight], which honours
 * the user's `toolbarHeightDp` — prefer it wherever settings are available so
 * a taller bar and its full-bleed accounting stay in step.
 */
internal val TopBarHeight = 44.dp

/** Downward travel on the strip that counts as a "hide the keyboard" flick. */
private val ToolbarSwipeHideThreshold = 48.dp

/**
 * Whether the pinned tools should read right-to-left: the setting is on and
 * the active layout's script runs RTL. Both the display order and the drag
 * hit-testing key off this, so they stay in step during a reorder.
 */
private fun toolbarReadsRtl(state: KeyboardUiState): Boolean =
    state.settings.toolbarBehavior.reverseForRtl && state.script.direction == TextDirection.RTL

/**
 * The top strip's height for the current settings (see [TopBarHeight]).
 * Collapses to zero when the toolbar is disabled, so every height-accounting
 * caller (full-bleed absorption, emoji-search sizing) drops the strip with it.
 */
internal fun topBarHeight(settings: KeyboardSettings): Dp =
    if (settings.toolbarBehavior.enabled) settings.toolbarHeightDp.dp else 0.dp

/**
 * Exact height of [KeyRows]: [LayoutSet.rowSpan] key rows (each key height plus
 * its vertical gaps), the optional number row, and the column padding. Every
 * panel sizes itself with this so opening a tool or switching layers never
 * changes the keyboard's height under the user's fingers.
 *
 * Takes the whole state rather than the settings because the row count is a
 * property of the active layout set — a custom layout may be three rows or six.
 * Threading the resolved [LayoutSet] through as its own parameter was the
 * alternative and was rejected: every call site already has the state in scope,
 * so it would have touched every panel signature and bought nothing.
 */
/**
 * Whether the dedicated number row is drawn on the *current* layer. Honours the
 * global [KeyboardSettings.numberRow] and the opt-out
 * [LayoutBehaviorSettings.numberRowInSymbols], which drops the row from the
 * ?123 / symbols layers where the symbols already carry their own top row.
 * Shared by the render loop and [keyRowsHeight] so the reserved height and the
 * drawn row always agree — otherwise a suppressed row would leave a blank gap.
 */
internal fun numberRowShown(state: KeyboardUiState): Boolean =
    state.settings.numberRow &&
        (state.settings.layoutBehavior.numberRowInSymbols ||
            (state.layoutMode != LayoutMode.SYMBOLS &&
                state.layoutMode != LayoutMode.SYMBOLS_SHIFTED))

// ---- what is on screen: one answer, read by the renderer and the service ----
//
// The physical keyboard's hint badges pair a key with the button under it, and
// the service dispatches the same key against the same list. Both sides derive
// their list here rather than each working it out, because a disagreement is
// silent and vicious: the badge says one thing and another cell opens.

/**
 * Lock-screen privacy has dropped the whole top strip, taking the clipboard
 * tool and paste chip with it.
 */
internal fun barLockHidden(state: KeyboardUiState): Boolean =
    state.deviceLocked && state.settings.toolbarBehavior.hideWhenLocked

/** The clipboard panel has traded its full-bleed height for a search field. */
internal fun barClipboardSearching(state: KeyboardUiState): Boolean =
    state.panel == PanelMode.CLIPBOARD && state.clipboardSearchActive && !barLockHidden(state)

/** A panel is claiming the strip's height, so the rows above the keys are gone. */
internal fun barFullBleed(state: KeyboardUiState): Boolean =
    isFullBleedPanel(state.panel, state.settings) && !barClipboardSearching(state)

internal fun emojiRowVisible(state: KeyboardUiState): Boolean =
    !barFullBleed(state) &&
        state.settings.emojiBarMode == EmojiBarMode.ALWAYS &&
        state.panel != PanelMode.EMOJI

internal fun symbolRowVisible(state: KeyboardUiState): Boolean =
    !barFullBleed(state) &&
        state.settings.symbolRowEnabled &&
        state.panel != PanelMode.SYMBOLS

/**
 * The pinned tools in the order the bar draws them, RTL flip included, so a
 * digit badge counts the same way the eye does.
 */
internal fun visibleToolbarTools(state: KeyboardUiState): List<ToolbarTool> =
    state.settings.toolbarTools
        .filter {
            it in state.settings.enabledTools && isSupportedTool(it) &&
                isUsableTool(it, state.settings)
        }
        .let { if (toolbarReadsRtl(state)) it.reversed() else it }

/** What the toolbox has to show: everything enabled that is not already pinned. */
internal fun visibleToolboxTools(state: KeyboardUiState): List<ToolbarTool> =
    state.settings.toolboxOrder.filter {
        it !in state.settings.toolbarTools && it in state.settings.enabledTools &&
            isSupportedTool(it) && isUsableTool(it, state.settings)
    }

/** The symbol set the row is showing, resolved the way the row itself resolves it. */
internal fun activeSymbolSet(state: KeyboardUiState): SymbolSet {
    val all = resolveSymbolSets(state.settings.customSymbolSets)
    val enabled = state.settings.symbolRowSetIds
        .mapNotNull { id -> all.firstOrNull { it.id == id } }
        .ifEmpty { BuiltInSymbolSets.sets }
    val activeId = state.activeSymbolSetId ?: state.settings.symbolRowActiveSetId
    return enabled.firstOrNull { it.id == activeId } ?: enabled.first()
}

/** The emoji the bar is showing, already toned, in the order the cells draw them. */
internal fun visibleEmojiBarItems(state: KeyboardUiState): List<String> =
    when (state.settings.emojiBarContent) {
        EmojiBarContent.MOST_USED -> state.emojiFrequents
        EmojiBarContent.RECENTS -> state.emojiRecents
        EmojiBarContent.FAVOURITES -> state.emojiFavourites
    }.ifEmpty { DEFAULT_BAR_EMOJIS.map { emojiDisplay(state, it) } }

/**
 * Every hotkey the keyboard is currently offering.
 *
 * Cheap enough to call per composition and per keystroke: it walks four short
 * lists and allocates two maps. Caching it would mean inventing an invalidation
 * rule for a value that has to be exactly right at both call sites.
 */
internal fun keyboardHintPlan(state: KeyboardUiState): HintPlan {
    val hw = state.settings.hardwareKeyboard
    if (!hw.shortcutsEnabled) return HintPlan()
    val symbols = if (symbolRowVisible(state)) activeSymbolSet(state).chars.size else 0
    val emoji = if (emojiRowVisible(state)) {
        minOf(
            visibleEmojiBarItems(state).size,
            state.settings.emoji.barCount.coerceIn(EmojiBarCountRange),
        )
    } else {
        0
    }
    return buildHintPlan(
        toolbarTools = visibleToolbarTools(state),
        toolboxTools = if (state.panel == PanelMode.TOOLBOX) visibleToolboxTools(state) else emptyList(),
        toolLetters = resolvedToolLetters(hw.toolByLetter, usableTools(state.settings)),
        symbolCells = symbols,
        emojiCells = emoji,
        suggestions = state.suggestions.size,
        digitChord = hw.toolbarDigitChord,
        leaderDigitsPickSuggestions = hw.suggestionHotkeys == SuggestionHotkeyMode.LEADER_DIGIT,
        suggestionAltDigits = hw.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT,
        modifiers = HintModifiers.of(hw.hintModifierWords),
    )
}

/**
 * Which engine rank each suggestion slot is drawing, left to right.
 *
 * The centre-primary setting swaps the first two chips, so slot 0 shows rank 1.
 * The badges count slots, not ranks — a strip labelled 2 1 3 is exactly the
 * puzzle the badges exist to remove — which means the key must resolve back to
 * the rank before it commits. Shared with the service so the badge and the
 * keypress can never disagree about which word is which.
 *
 * Only the Latin chips reorder: the conversion strip is a scrolling row in the
 * composer's own order, and the inline-emoji row is not word candidates at all.
 */
internal fun suggestionDisplayOrder(state: KeyboardUiState): List<Int> {
    val reorders = !state.composer.isConversion && !state.inlineEmoji &&
        state.settings.suggestionStrip.suggestionPrimaryCenter
    return suggestionSlotOrder(state.suggestions.size, reorders)
}

/**
 * The hints to draw on the tools and rows, which is only ever while the picker
 * is armed. Badges on every icon at all times would be noise for the far larger
 * number of people who never plug a keyboard in.
 */
internal fun armedHintPlan(state: KeyboardUiState): HintPlan? =
    state.toolPicker?.let { keyboardHintPlan(state) }

/**
 * The hints to draw on the suggestion strip, which is the one surface that
 * shows them without being asked.
 *
 * The strip is where a hardware-keyboard user's eyes already are, and `Alt`+1
 * needs no leader, so its digits are worth standing ink. The leader-digit mode
 * is the opposite: a bare `1` does nothing until the picker is armed, and a
 * badge promising otherwise would be a lie.
 */
internal fun suggestionHintPlan(state: KeyboardUiState): HintPlan? {
    val hw = state.settings.hardwareKeyboard
    if (!hw.shortcutsEnabled) return null
    val standing = state.hardwareKeyboardPresent &&
        hw.suggestionHintsAlways &&
        hw.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT
    return if (state.toolPicker != null || standing) keyboardHintPlan(state) else null
}

internal fun keyRowsHeight(state: KeyboardUiState): Dp {
    val settings = state.settings
    val rowSpan = state.layouts.rowSpan
    val layout = currentLayout(state)
    val rowHeights = layout.rowHeights
    var height = if (rowHeights == null) {
        (settings.keyHeightDp.dp + keyGapV(settings) * 2) * rowSpan
    } else {
        // A layout with per-row heights: sum its body rows at their scaled
        // heights and pad the rest at the base height, mirroring the render
        // loop key for key so the reserved height matches what is drawn.
        val bodyRowCount = layout.rows.size.coerceAtMost(rowSpan)
        var sum = 0.dp
        for (i in 0 until bodyRowCount) {
            sum += rowScaledKeyHeight(settings.keyHeightDp, rowHeights.getOrNull(i)).dp +
                keyGapV(settings) * 2
        }
        sum + (settings.keyHeightDp.dp + keyGapV(settings) * 2) * (rowSpan - bodyRowCount)
    }
    height += KeyRowsPadVertical * 2
    if (numberRowShown(state)) {
        height += settings.numberRowHeightDp.dp + keyGapV(settings) * 2
    }
    // The bottom row's independent height (A45) rides on top: the render loop
    // swaps one base-height row for bottomRowHeightDp, so reserve the delta.
    // Only when the layout has no per-row heights, matching the render guard.
    val bottomRowHeightDp = settings.layoutBehavior.bottomRowHeightDp
    if (bottomRowHeightDp > 0 && rowHeights == null) {
        height += (bottomRowHeightDp - settings.keyHeightDp).dp
    }
    return height
}

/**
 * One key's tremor filter — the "ignore repeated presses" accessibility
 * setting, which drops a second contact on the same key inside a window.
 *
 * The verdict is taken on the way down rather than at the commit, because
 * everything a press costs is spent on the way down: the haptic, the click,
 * and only then the character. Judging late left the filter buzzing and
 * clicking for presses it went on to throw away, which is what it is there to
 * stop happening.
 *
 * Plain fields, not Compose state: the pointer handlers read and write these
 * outside composition, and nothing draws from them.
 */
private class KeyDebounceGate {
    private var lastAcceptedAt = 0L
    private var mutedUntil = 0L

    /** Whether the press this gate judged last is allowed to type. */
    var accepted = true
        private set

    /** Judges a contact. [debounceMs] of 0 or less turns the filter off. */
    fun press(debounceMs: Int) {
        val now = SystemClock.uptimeMillis()
        accepted = debounceMs <= 0 || now - lastAcceptedAt >= debounceMs
        if (accepted) lastAcceptedAt = now else mutedUntil = lastAcceptedAt + debounceMs
    }

    /**
     * Whether feedback fired *now* belongs to a press that was kept. Time-based
     * rather than latched to [accepted]: a dropped contact that the finger then
     * holds becomes a deliberate long press or repeat run, and by the time
     * those fire the window has passed, so they are heard.
     */
    fun audible(): Boolean = SystemClock.uptimeMillis() >= mutedUntil
}

/**
 * One key.
 *
 * Takes its resolved [KeyVisual] rather than the whole [KeyboardUiState] so a
 * keystroke that only moves the suggestions leaves every parameter here
 * untouched and this body — 15-odd modifier links and a pointer detector's worth
 * of lambdas — skips. [settings] is the one exception: it is compared by
 * instance (Compose reads it as unstable, being full of collections), which is
 * why [KeyboardScreen] holds its identity stable across keystrokes.
 */
@Composable
private fun KeyButton(
    visual: KeyVisual,
    settings: KeyboardSettings,
    modifier: Modifier = Modifier,
    onKey: (Key) -> Unit,
    onText: (String) -> Unit,
    onCursorMove: (Int) -> Unit = {},
    onLayoutSelect: (String) -> Unit = {},
    heightDp: Int? = null,
    /** The field wants a keypad: the preview bubble is off unless opted back in. */
    numericField: Boolean = false,
    /** Active layout, for the spacebar's language cycle and the picker highlight. */
    layoutId: String = BuiltInLayouts.DEFAULT_ID,
    /** The board's shared preview bubble; this key only publishes to it. */
    keyPreview: KeyPreviewState,
    smartResolve: (Key, PointerId) -> Key = { k, _ -> k },
    /** Spawns the theme's press burst at this key; null when the effect is off. */
    onBurst: ((Rect) -> Unit)? = null,
) {
    val key = visual.key
    // Held as the state object, never read through a `by` delegate: every read of
    // the press below is either inside a draw lambda or inside a composable of
    // its own, so a press repaints the key instead of recomposing it. Reading it
    // anywhere in this body puts composition and layout back on the press path.
    val pressed = remember { mutableStateOf(false) }
    var showAlternates by remember { mutableStateOf(false) }
    // The flick arm the finger is currently over on a kana-pad key, driving the
    // cross popup's highlight; null when centred (a plain tap) or released.
    val flickDirection = remember { mutableStateOf<FlickDirection?>(null) }
    // Full tappable language list: opened by a long-press on the globe key or
    // by holding the spacebar when more than two languages are enabled (a
    // swipe through a long ring is tedious). Independent of languagePreview.
    var showLanguagePicker by remember { mutableStateOf(false) }
    // Row of the open picker the spacebar hold-drag currently has selected;
    // null until the finger actually moves (a plain hold leaves the popup up
    // for tapping, exactly as before).
    var pickerDragIndex by remember { mutableStateOf<Int?>(null) }
    // Language the spacebar swipe currently has selected, shown in a tooltip
    // popup above the spacebar while the finger is still down.
    var languagePreview by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The role-carrying locals, because this is the key grid: a sound pack may
    // have recorded the spacebar separately from the letters.
    val rawKeyPress = LocalKeyRoleFeedback.current
    val rawKeySound = LocalKeyRoleSound.current
    // Buzz with no click. Only the repeat tick uses it, for the case where the
    // user wants the held key to keep vibrating without machine-gunning the
    // sound.
    val onKeyHaptic = LocalHapticFeedback.current
    val onClipboardKey = LocalClipboardKeyAction.current
    val canDelete = LocalCanDelete.current
    val canForwardDelete = LocalCanForwardDelete.current
    val onDeleteWord = LocalDeleteWord.current
    val onCursorMoveVertical = LocalCursorMoveVertical.current
    val onHideKeyboard = LocalHideKeyboard.current

    // What this key would put in the shared bubble, were it pressed right now.
    // Held as updated state rather than captured: the press handler lives inside
    // a pointerInput whose block only restarts when its keys change, so a value
    // read straight out of the composition would be the one from whenever that
    // last happened — the label a shift had since changed, for one.
    //
    // Numeric keypads (number/phone/date/time fields) suppress the bubble unless
    // the user opted back in: a floating digit over a PIN pad is noise, and easy
    // shoulder-surfing.
    val previewToken = remember { Any() }
    val previewWanted = rememberUpdatedState(
        settings.popup.enabled &&
            (settings.popup.inNumericFields || !numericField) &&
            key.action == KeyAction.Text,
    )
    val previewLabel = rememberUpdatedState(visual.label)
    // Same staleness rule as the label: the pointer input holds its lambdas
    // across recompositions, so a per-key popup colour has to be read through
    // an updated state or a theme edit would keep bubbling the old colour.
    val previewColors = rememberUpdatedState(visual.popupBackground to visual.popupText)
    // And the burst likewise: switching to an effect-less theme must null it
    // out under a finger that is already down.
    val burst = rememberUpdatedState(onBurst)
    // The key's place in the compose root, for the overlay to position against.
    val keyBounds = remember { mutableStateOf(Rect.Zero) }

    // The popups' own colours; the key's face is already resolved in [visual].
    val kb = LocalKbTheme.current
    // The theme's key textures. A static local, changing only on a theme
    // switch — reading it here adds nothing to the press path.
    val textures = LocalKeyTextures.current
    // The gap is handed to the shape as the room a leaning outline may spill
    // into: a slanted key then leans across the gap rather than out of its own
    // width, and neighbouring keys interlock instead of thinning out.
    val keyShape = kb.keyShape(bleedDp = keyGapH(settings).value)

    // Outer box = full grid cell and the touch target; inner box = the
    // visible key, inset by the gap. Presses in the gap between keys land
    // on whichever cell they fall in, so there are no dead zones.

    // Tremor filter: drop a second contact on the same key that lands
    // within the debounce window. Scoped per key, so alternating keys
    // (typing "aa" vs "ab") are never affected — only a bouncing repeat is.
    val debounceMs = rememberUpdatedState(settings.keyDebounceMs)
    val gate = remember { KeyDebounceGate() }
    val debounced: (Key) -> Unit = remember(onKey, gate) {
        { pressedKey -> if (gate.accepted) onKey(pressedKey) }
    }
    // The buzz and the click are spent on contact, before the key ever commits,
    // so a discarded press has to be silenced there too — a filter that still
    // buzzes for every dropped tap feels exactly like no filter at all. Only
    // the press's own feedback is muted: the long-press cue and the repeat
    // ticks under the same finger land past the window and ring normally.
    //
    // The role is bound once, here, rather than threaded further down: the
    // spacebar's swipe handler and the delete key's repeat tick both take these
    // lambdas as plain `() -> Unit` parameters, so binding at the top means the
    // held-repeat on backspace is already a delete-role sound with nothing else
    // to change.
    val soundRole = key.keySoundRole()
    val onKeyPress: () -> Unit = remember(rawKeyPress, gate, soundRole) {
        { if (gate.audible()) rawKeyPress(soundRole) }
    }
    val onKeySound: () -> Unit = remember(rawKeySound, gate, soundRole) {
        { if (gate.audible()) rawKeySound(soundRole, KeySoundPhase.PRESS) }
    }
    // The finger leaving. Gated on the same window as the press, and for a
    // sharper reason: a contact the tremor filter dropped is a bounce, and a
    // bounce lifts again within a millisecond or two — still inside the window.
    // Ungated, that lift would play a key coming back up that was never heard
    // going down, which is the one artefact the filter exists to prevent.
    val onKeyRelease: () -> Unit = remember(rawKeySound, gate, soundRole) {
        { if (gate.audible()) rawKeySound(soundRole, KeySoundPhase.RELEASE) }
    }

    // Under an explore-by-touch service the accessibility framework owns the
    // touch stream, so the custom press/long-press/swipe detector never sees
    // a coherent gesture. Hand the key over to semantics instead: TalkBack
    // announces on hover and commits on the activation tap.
    val touchExploration = LocalTouchExploration.current
    val screenReaderKeys = settings.screenReaderMode != ScreenReaderMode.OFF
    // Pass-through mode keeps the keyboard's own detector under a screen
    // reader — the touches really do arrive, because the app's accessibility
    // service carves this window out of touch exploration. Without that
    // service granted there is no carve-out, so it degrades to EXPLORE rather
    // than to a keyboard that types on contact and says nothing.
    val passthrough = touchExploration &&
        settings.screenReaderMode == ScreenReaderMode.PASSTHROUGH &&
        LocalPassthroughService.current
    val semanticsDriven = touchExploration && when (settings.screenReaderMode) {
        ScreenReaderMode.EXPLORE -> true
        ScreenReaderMode.PASSTHROUGH -> !passthrough
        else -> false
    }
    val label = visual.spoken.resolved()
    // Hoisted: the semantics block below is not a composable scope. Gated
    // because only the semantics-driven branch reads it, and formatting it
    // otherwise meant a Resources.getString with an argument — per key, per
    // grid build — for a string nobody was going to speak. Calling a
    // @ReadOnlyComposable conditionally is safe; it opens no group.
    val typeAction =
        if (semanticsDriven) stringResource(R.string.ime_key_type_action, label) else ""
    // Nothing announces the keys once the window is passed through — TalkBack
    // no longer sees the touches that would make it speak. The keyboard says
    // the key itself on press; the press only commits on release, so a key can
    // still be heard before it types.
    val view = LocalView.current
    val announce: (Boolean) -> Unit = remember(passthrough, label, view) {
        { down -> if (passthrough && down) view.announceForAccessibility(label) }
    }

    Box(
        modifier = modifier
            .height((heightDp ?: settings.keyHeightDp).dp + keyGapV(settings) * 2)
            .then(
                if (screenReaderKeys) {
                    Modifier.semantics {
                        contentDescription = label
                        role = Role.Button
                        if (semanticsDriven) {
                            // No pointer went down here, so the activation has
                            // to open the window itself.
                            onClick(label = typeAction) {
                                gate.press(debounceMs.value)
                                debounced(key)
                                true
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (semanticsDriven) Modifier
                else Modifier.pointerInputKey(
                    key, settings.longPressDelayMs, settings.keyRepeat, settings.textEditing,
                    spaceShortSwipe = settings.spaceShortSwipe,
                    spaceLongSwipe = settings.spaceLongSwipe,
                    enabledLayoutIds = settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) },
                    currentLayoutId = layoutId,
                    setPressed = { down ->
                        // Judged here rather than at the commit: every branch
                        // below reports the contact before it spends anything
                        // on it, which is the one point where a press can still
                        // be dropped silently.
                        if (down) gate.press(debounceMs.value)
                        pressed.value = down
                        announce(down)
                        // The burst spends nothing when off, and rides the same
                        // debounce as the sound: a dropped contact throws no
                        // confetti either.
                        if (down && gate.accepted) {
                            burst.value?.invoke(keyBounds.value)
                        }
                        if (down && previewWanted.value) {
                            val bounds = keyBounds.value
                            keyPreview.press(
                                KeyPreview(
                                    token = previewToken,
                                    label = previewLabel.value,
                                    position = bounds.topLeft,
                                    size = IntSize(
                                        bounds.width.roundToInt(),
                                        bounds.height.roundToInt(),
                                    ),
                                    popupBackground = previewColors.value.first,
                                    popupText = previewColors.value.second,
                                ),
                            )
                        } else if (!down) {
                            // Unconditional, unlike the press: the gate can turn
                            // off under a held finger (a field change swapping in
                            // a keypad), and a release that never ran would leave
                            // the bubble owned by a key nobody is touching.
                            keyPreview.release(previewToken)
                        }
                    },
                    onKeyPress = onKeyPress,
                    onKeySound = onKeySound,
                    onKeyRelease = onKeyRelease,
                    vibrateOnSpace = settings.feedback.vibrateOnSpace,
                    vibrateOnDeleteSwipe = settings.feedback.vibrateOnDeleteSwipe,
                    vibrateOnRepeat = settings.feedback.vibrateOnRepeat,
                    soundOnRepeat = settings.feedback.soundOnRepeat,
                    onKeyHaptic = onKeyHaptic,
                    hapticOnLongPress = settings.hapticOnLongPress,
                    hapticOnLongPressRelease = settings.hapticOnLongPressRelease,
                    // The alternates take the bubble's place outright, so it goes
                    // now rather than serving out its minimum duration under them.
                    openAlternates = { showAlternates = true; keyPreview.cancel(previewToken) },
                    setFlickDirection = { flickDirection.value = it },
                    onKey = debounced,
                    // Repeat ticks bypass the debounce (raw onKey), taps don't.
                    onKeyRepeat = onKey,
                    onClipboardKey = onClipboardKey,
                    onCursorMove = onCursorMove,
                    onCursorMoveVertical = onCursorMoveVertical,
                    onHideKeyboard = onHideKeyboard,
                    spaceCursor2d = settings.layoutBehavior.spaceCursor2d,
                    spaceSwipeDownHide = settings.layoutBehavior.spaceSwipeDownHide,
                    symbolsLongPressNumpad = settings.layoutBehavior.symbolsLongPressNumpad,
                    onLayoutSelect = onLayoutSelect,
                    openLanguagePicker = { pickerDragIndex = null; showLanguagePicker = true },
                    closeLanguagePicker = { showLanguagePicker = false; pickerDragIndex = null },
                    setPickerDragIndex = { pickerDragIndex = it },
                    setLanguagePreview = { languagePreview = it },
                    canDelete = canDelete,
                    canForwardDelete = canForwardDelete,
                    onDeleteWord = onDeleteWord,
                    backspaceSwipeDelete = settings.backspaceSwipeDelete,
                    scope = scope,
                    smartResolve = smartResolve,
                )
            )
            .padding(horizontal = keyGapH(settings), vertical = keyGapV(settings))
            // The face and its sheen are painted here rather than by
            // Modifier.background, because both depend on the press: read inside
            // the draw lambda, a press invalidates only the draw, so pressing a
            // key no longer recomposes or re-measures it. The outline and the
            // brush are built in the cache block, which the press does not touch.
            .drawWithCache {
                val outline = keyShape.createOutline(size, layoutDirection, this)
                // Sheen over letter keys only; pressed/enter/modifier states keep
                // their solid colors so state changes stay legible.
                val sheen = kb.keyGradient
                    ?.takeIf { key.action == KeyAction.Text }
                    ?.brush()
                // The theme's texture for this key class, prepared here so the
                // press-path draw below only picks between ready-made values.
                // The clip path and tile brush depend on size/shape alone —
                // the cache block's own invalidation keys.
                val texture = textures.forKey(key.action)
                val texturePaint = texture?.let {
                    KeyTexturePaint.of(it, textures, outline, size)
                }
                val pressedTexture = textures.pressed
                val pressedPaint = pressedTexture?.let {
                    KeyTexturePaint.of(it, textures, outline, size)
                }
                onDrawBehind {
                    val down = pressed.value
                    drawOutline(outline, if (down) visual.pressedBackground else visual.background)
                    val paint = if (down) pressedPaint ?: texturePaint else texturePaint
                    if (paint != null) {
                        paint.draw(this, textures.opacity)
                        // With no pressed texture of its own, the press stays
                        // visible as a tint over the textured face.
                        if (down && pressedPaint == null) {
                            drawOutline(outline, visual.pressedBackground.copy(alpha = 0.5f))
                        }
                    }
                    if (sheen != null && !down) drawOutline(outline, sheen)
                }
            }
            .then(
                // Left as a border modifier: it does not depend on the press, and
                // Modifier.border insets a rounded outline by half the stroke so
                // the whole width lands inside the key — hand-stroking the same
                // outline would straddle the edge and read thinner. A per-key
                // border colour wins over the theme's, and draws at the theme's
                // width or 1.5 dp when the theme has no border of its own.
                run {
                    val border = visual.borderColor ?: kb.keyBorder
                    val width = if (visual.borderColor != null) {
                        maxOf(kb.keyBorderWidthDp, 1.5f)
                    } else {
                        kb.keyBorderWidthDp
                    }
                    if (border != null && width > 0f) {
                        Modifier.border(width.dp, border, keyShape)
                    } else {
                        Modifier
                    }
                }
            )
            .onGloballyPositioned { keyBounds.value = it.boundsInRoot() },
        contentAlignment = Alignment.Center,
    ) {
        KeyLabel(visual, settings, pressed)
        val popupPosition = rememberAboveAnchorPopup()

        if (showAlternates && key.opensAlternatesPopup()) {
            AlternatesPopup(
                key = key,
                popupPosition = popupPosition,
                fontScale = settings.popup.fontScale,
                onDismiss = { showAlternates = false },
                onText = { text ->
                    showAlternates = false
                    onText(text)
                },
                onAction = { alternateKey ->
                    showAlternates = false
                    onKey(alternateKey)
                },
            )
        }

        KeyFlickPopup(
            key = key,
            fontScale = settings.popup.fontScale,
            alternatesOpen = showAlternates,
            pressed = pressed,
            flickDirection = flickDirection,
        )

        // Tooltip above the spacebar while a swipe is cycling languages: the
        // enabled modes in a row, the live selection highlighted. Capped at a
        // five-chip window sliding with the selection — drawing every enabled
        // layout ran off the screen the moment a handful were enabled.
        languagePreview?.let { previewMode ->
            val enabledLayoutIds = settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
            val previewWindow = if (enabledLayoutIds.size <= 5) {
                enabledLayoutIds
            } else {
                val sel = enabledLayoutIds.indexOf(previewMode).coerceAtLeast(0)
                val start = (sel - 2).coerceIn(0, enabledLayoutIds.size - 5)
                enabledLayoutIds.subList(start, start + 5)
            }
            Popup(
                popupPositionProvider = popupPosition,
                properties = PreviewPopupProperties,
            ) {
                Surface(
                    shape = kb.popupShape(),
                    color = kb.popup,
                    border = kb.popupSurfaceBorder(),
                    shadowElevation = elevationFor(kb.popupShapeKind, 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (chipLayoutId in previewWindow) {
                            val selected = chipLayoutId == previewMode
                            Text(
                                text = layoutSwitchLabel(
                                    chipLayoutId,
                                    enabledLayoutIds,
                                    settings.customLayouts,
                                    settings.layoutBehavior.spacebarDisplay,
                                ),
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .background(
                                        if (selected) kb.pressedKey else Color.Transparent,
                                        RoundedCornerShape(kb.popupRadiusDp.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                fontSize = (14 * settings.popup.fontScale).sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) kb.popupText else kb.popupText.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }

        if (showLanguagePicker) {
            LanguagePickerPopup(
                popupPosition = popupPosition,
                enabledLayoutIds = settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) },
                currentLayoutId = layoutId,
                customLayouts = settings.customLayouts,
                displayMode = settings.layoutBehavior.spacebarDisplay,
                highlightIndex = pickerDragIndex,
                onPick = {
                    showLanguagePicker = false
                    pickerDragIndex = null
                    if (it != layoutId) onLayoutSelect(it)
                },
                // Routed through the key dispatch rather than a callback of its
                // own: the service already answers this action for a key bound
                // to it, and KeyboardScreen cannot take another parameter.
                onOtherKeyboards = {
                    showLanguagePicker = false
                    pickerDragIndex = null
                    onKey(Key(label = "", action = KeyAction.InputMethodPicker))
                },
                onDismiss = { showLanguagePicker = false; pickerDragIndex = null },
            )
        }
    }
}

/**
 * The long-press alternates: the key's characters, then the entries that run an
 * action rather than typing (issue #21).
 *
 * A [FlowRow] rather than the single [Row] this was, because a single row is only
 * as wide as the screen by luck. A letter key with every accent merged in (the
 * "All accents on press and hold" setting does exactly that), or an ordinary one
 * at a raised popup font size, ran off the right-hand edge and took its last few
 * alternates with it — the popup is positioned by clamping *its* left edge into
 * the window, so what overflows is unreachable rather than merely ugly (issue
 * #20). Wrapping keeps every entry on screen and costs a popup that already fits
 * nothing: a FlowRow of one line measures exactly as the Row did.
 *
 * The scroll is a backstop, not the feature. It engages only past
 * [MaxPopupHeightFraction] of the display — some dozens of entries at the default
 * size, which no shipped layout comes near — and exists so a pathological popup
 * is scrollable rather than growing off the top of the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternatesPopup(
    key: Key,
    popupPosition: PopupPositionProvider,
    fontScale: Float,
    onDismiss: () -> Unit,
    onText: (String) -> Unit,
    /** An action alternate, as the key the service dispatches for it. */
    onAction: (Key) -> Unit,
) {
    val kb = LocalKbTheme.current
    val configuration = LocalConfiguration.current
    // The widest the wrap is allowed to grow: the display, less a margin at each
    // edge. Measured against the display rather than the keyboard because the
    // popup is a window of its own — it is free to be wider than a one-handed or
    // floating board, and stopping it at the board's width would wrap popups
    // that had room to spare.
    val maxWidth = (configuration.screenWidthDp - PopupSideMarginDp * 2).dp
    val maxHeight = (configuration.screenHeightDp * MaxPopupHeightFraction).dp
    Popup(
        popupPositionProvider = popupPosition,
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = kb.popupShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.popupShapeKind, 8.dp),
        ) {
            FlowRow(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                // A part-full last line sits under the middle of the ones above
                // it rather than hanging off the left, which is what makes a
                // wrapped popup read as one block instead of a ragged list.
                horizontalArrangement = Arrangement.Center,
            ) {
                for (alternate in key.longPress) {
                    Text(
                        text = alternate,
                        modifier = Modifier
                            .clickable { onText(alternate) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        fontSize = (18 * fontScale).sp,
                        color = kb.popupText,
                    )
                }
                for (alternate in key.actionAlternates) {
                    AlternateAction(alternate, fontScale, kb.popupText) {
                        onAction(Key(label = alternate.label, action = alternate.action))
                    }
                }
            }
        }
    }
}

/**
 * One action entry of the alternates popup: the tool's own icon, a named icon,
 * or the action's glyph — in that order, which is the order that answers "what
 * will this do" fastest.
 *
 * A tool wears the icon it wears on the toolbar, resolved through the slot
 * registry so an icon pack redresses it here too.
 */
@Composable
private fun AlternateAction(
    alternate: KeyAlternate,
    fontScale: Float,
    tint: Color,
    onClick: () -> Unit,
) {
    val action = alternate.action
    val tool = (action as? KeyAction.Tool)?.tool
    // Named, so both branches below can speak the entry rather than going silent
    // on an icon: the label the author gave it, else the tool's own name.
    val spoken = alternate.label.ifBlank { tool?.let { toolLabel(it) }.orEmpty() }
    val namedIcon = KeyIcons.byName(alternate.icon)
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            namedIcon != null -> Icon(
                namedIcon,
                contentDescription = spoken.ifBlank { null },
                tint = tint,
                modifier = Modifier.size((20 * fontScale).dp),
            )
            tool != null -> SlotIcon(
                IconSlots.forTool(tool),
                contentDescription = spoken.ifBlank { null },
                tint = tint,
                modifier = Modifier.size((20 * fontScale).dp),
            )
            else -> Text(
                text = alternate.drawnLabel(),
                fontSize = (18 * fontScale).sp,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Clearance the alternates popup keeps from each edge of the display. */
private const val PopupSideMarginDp = 8

/** How much of the display a wrapped alternates popup may fill before it scrolls. */
private const val MaxPopupHeightFraction = 0.6f

/**
 * The key's label, in a restart scope of its own.
 *
 * Enter is the one key whose label colour flips under the finger, and a text
 * colour cannot move to the draw phase the way the key's face did — it is baked
 * into the text layout. So the label gets its own composable: pressing Enter
 * recomposes this and nothing else, instead of the whole key. Every other key
 * reads no press state at all, because the two colours are the same value and
 * the `&&` never gets that far — those keys stay entirely off the press path.
 *
 * The alternative was dropping the Enter recolour outright. Kept because it is
 * the only feedback that key has: its face is already the accent colour, so a
 * press that only swapped the face would barely read.
 */
@Composable
private fun KeyLabel(visual: KeyVisual, settings: KeyboardSettings, pressed: State<Boolean>) {
    val recolours = visual.pressedContentColor != visual.contentColor
    KeyContent(
        visual,
        settings,
        if (recolours && pressed.value) visual.pressedContentColor else visual.contentColor,
    )
}

/**
 * The flick cross a press raises on a kana-pad key: the centre kana with its
 * arms laid out around it, the arm under the finger highlighted. Shown while the
 * key is held, unless the long-press alternates popup took over.
 *
 * Its own restart scope, for the same reason as [KeyLabel] — a Popup is composed
 * content, so unlike the key's face it cannot be moved to the draw phase.
 * Keeping the press read here means a press recomposes this small function
 * rather than [KeyButton]'s body, and only for a key that has flicks at all: the
 * `&&` reaches [pressed] on no other key. The long-press and spacebar popups stay
 * with the key, not being on the per-keystroke path.
 */
@Composable
private fun KeyFlickPopup(
    key: Key,
    fontScale: Float,
    /** The long-press alternates took over, so this is not shown. */
    alternatesOpen: Boolean,
    pressed: State<Boolean>,
    flickDirection: State<FlickDirection?>,
) {
    if (key.flick.isNotEmpty() && !alternatesOpen && pressed.value) {
        Popup(popupPositionProvider = FlickPopupPositionProvider) {
            FlickCrossPopup(key, flickDirection.value, fontScale)
        }
    }
}

/**
 * The flick preview shown while a kana-pad key is held: the centre kana with
 * its defined arms laid out in a plus, the arm the finger is over — or the
 * centre, when [active] is null — highlighted. Only arms the key actually
 * defines are drawn, so a key with two flicks shows two chips, not four blanks.
 */
@Composable
private fun FlickCrossPopup(key: Key, active: FlickDirection?, fontScale: Float) {
    Box(modifier = Modifier.size(148.dp)) {
        FlickCell(key.output ?: key.label, active == null, Alignment.Center, fontScale)
        FlickCell(key.flick[FlickDirection.UP], active == FlickDirection.UP, Alignment.TopCenter, fontScale)
        FlickCell(key.flick[FlickDirection.LEFT], active == FlickDirection.LEFT, Alignment.CenterStart, fontScale)
        FlickCell(key.flick[FlickDirection.RIGHT], active == FlickDirection.RIGHT, Alignment.CenterEnd, fontScale)
        FlickCell(key.flick[FlickDirection.DOWN], active == FlickDirection.DOWN, Alignment.BottomCenter, fontScale)
    }
}

/** One chip of the flick cross: an empty/absent arm draws nothing. */
@Composable
private fun BoxScope.FlickCell(
    text: String?,
    highlighted: Boolean,
    align: Alignment,
    fontScale: Float,
) {
    if (text.isNullOrEmpty()) return
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .align(align)
            .padding(3.dp)
            .background(
                if (highlighted) kb.accent else kb.popup,
                RoundedCornerShape(kb.popupRadiusDp.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = (20 * fontScale).sp,
            color = if (highlighted) kb.keyText else kb.popupText,
        )
    }
}

/** Centres the flick cross popup on the key it belongs to. */
private object FlickPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        return IntOffset(x, y)
    }
}

/**
 * A tappable list of every enabled layout, current one highlighted. Opened by
 * a long-press on the globe key, or a spacebar hold when more than four layouts
 * are enabled or the language swipe is off; picking one switches to it. Rows
 * read like the spacebar (language, with the layout in parentheses when a
 * language has several enabled layouts). Non-focusable like the other key
 * popups so it never steals the edited field's input connection.
 *
 * The last row leaves this keyboard entirely, through [onOtherKeyboards]. It is
 * pinned below the scrolling list rather than being row *n+1* of it, because
 * this is the only place in the keyboard that offers the system picker and a
 * user with eight layouts enabled would have to scroll past all of them to find
 * out it exists. The spacebar hold-drag walks the list by index and so never
 * lands on it, which is the other reason it sits outside the scroller.
 */
@Composable
private fun LanguagePickerPopup(
    popupPosition: PopupPositionProvider,
    enabledLayoutIds: List<String>,
    currentLayoutId: String,
    customLayouts: List<LayoutSpec>,
    displayMode: SpacebarDisplay,
    onPick: (String) -> Unit,
    onOtherKeyboards: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Row a spacebar hold-drag has walked to, or null when the popup is in
     * plain tap mode (globe long-press, or a hold that has not moved yet).
     * Kept scrolled into view so the finger never outruns the viewport.
     */
    highlightIndex: Int? = null,
) {
    val kb = LocalKbTheme.current
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(highlightIndex) {
        if (highlightIndex != null) {
            val rowPx = with(density) { PickerRowHeightDp.dp.toPx() }
            val centerPx = with(density) { 100.dp.toPx() }
            scrollState.animateScrollTo(
                (rowPx * highlightIndex - centerPx).toInt().coerceIn(0, scrollState.maxValue),
            )
        }
    }
    Popup(
        popupPositionProvider = popupPosition,
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 8.dp),
        ) {
            Column(modifier = Modifier.widthIn(min = 160.dp, max = 240.dp)) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState)
                        .padding(vertical = 4.dp),
                ) {
                    for ((index, layoutId) in enabledLayoutIds.withIndex()) {
                        val selected = layoutId == currentLayoutId
                        val dragged = index == highlightIndex
                        Text(
                            text = layoutSwitchLabel(layoutId, enabledLayoutIds, customLayouts, displayMode),
                            color = if (selected) kb.accent else kb.popupText,
                            fontWeight = if (selected || dragged) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Fixed row height — the hold-drag gesture steps its
                                // highlight by this exact amount of finger travel.
                                .height(PickerRowHeightDp.dp)
                                .background(if (dragged || (selected && highlightIndex == null)) kb.pressedKey else Color.Transparent)
                                .clickable { onPick(layoutId) }
                                .padding(horizontal = 16.dp)
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
                HorizontalDivider(color = kb.popupText.copy(alpha = 0.15f))
                Text(
                    text = stringResource(R.string.ime_language_picker_other_keyboards),
                    color = kb.popupText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PickerRowHeightDp.dp)
                        .clickable { onOtherKeyboards() }
                        .padding(horizontal = 16.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
    }
}

/**
 * Label for an enabled layout in the spacebar language switcher (tooltip and
 * picker). Follows the same rule as the spacebar label: the language name, the
 * layout name, or "Language (Layout)" — and always the combined form when more
 * than one enabled layout shares the language, so the rows stay distinct.
 */
internal fun layoutSwitchLabel(
    layoutId: String,
    enabledLayoutIds: List<String>,
    customLayouts: List<LayoutSpec>,
    mode: SpacebarDisplay,
): String {
    val spec = resolveLayout(customLayouts, layoutId)
    // The short half of the registry's "native · english" display name — the
    // full form overflows the preview row and picker with even three
    // languages enabled, and the native name is what the switcher's audience
    // reads fastest (it is also the colloquial name for the romanized
    // variants: Banglish, Hinglish, …).
    val lang = spec.language().displayName.substringBefore(" · ")
    val layout = spec.name
    val sameLangCount = enabledLayoutIds.count {
        resolveLayout(customLayouts, it).langId == spec.langId
    }
    return when {
        mode == SpacebarDisplay.LAYOUT -> layout
        // A layout named after its language ("Banglish (Banglish)") collapses.
        (mode == SpacebarDisplay.BOTH || sameLangCount > 1) && layout != lang -> "$lang ($layout)"
        else -> lang
    }
}

@Composable
private fun KeyContent(visual: KeyVisual, settings: KeyboardSettings, contentColor: Color) {
    val key = visual.key
    // The user's own size, then the grid's. Multiplied rather than replaced, so
    // a layout asking for smaller labels still leaves a larger accessibility
    // font size in force underneath it instead of silently discarding it.
    val fontScale = settings.fontScale * visual.fontScale
    when (key.action) {
        // The shift slot and its spoken name both track the live shift state, and
        // [spokenLabel] already words it the way this key wants read out.
        KeyAction.Shift -> SlotIcon(
            visual.iconSlot ?: IconSlots.KEY_SHIFT,
            contentDescription = visual.spoken.resolved(),
            tint = if (visual.iconActive) MaterialTheme.colorScheme.primary else contentColor,
        )
        KeyAction.CapsLock -> SlotIcon(
            visual.iconSlot ?: IconSlots.KEY_SHIFT_LOCK,
            contentDescription = visual.spoken.resolved(),
            tint = if (visual.iconActive) MaterialTheme.colorScheme.primary else contentColor,
        )
        KeyAction.Delete -> SlotIcon(
            IconSlots.KEY_BACKSPACE,
            contentDescription = stringResource(R.string.ime_key_delete),
            tint = contentColor,
        )
        KeyAction.ForwardDelete -> SlotIcon(
            IconSlots.KEY_FORWARD_DELETE,
            contentDescription = stringResource(R.string.ime_key_forward_delete),
            tint = contentColor,
        )
        // An app-supplied actionLabel is drawn as text — that is the whole
        // point of it, and no icon can stand in for wording the app chose.
        // It is clipped to one line so a long label cannot blow up the row.
        // CUSTOM is also the one enter action with no icon slot, for the same
        // reason: there is nothing to replace.
        KeyAction.Enter -> if (visual.enterLabel != null) {
            Text(
                text = visual.enterLabel,
                fontSize = (13 * fontScale).sp,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            SlotIcon(
                visual.iconSlot ?: IconSlots.KEY_ENTER,
                contentDescription = stringResource(R.string.ime_enter_default),
                tint = contentColor,
            )
        }
        // The enter glyph, never one of the action icons: this key types a line
        // break whatever the field declares, and drawing a paper plane on it
        // would promise the Send it exists to avoid.
        KeyAction.Newline -> SlotIcon(
            IconSlots.KEY_ENTER,
            contentDescription = stringResource(R.string.ime_key_newline),
            tint = contentColor,
        )
        KeyAction.LanguageSwitch -> SlotIcon(
            IconSlots.KEY_GLOBE,
            contentDescription = stringResource(R.string.ime_key_language_switch),
            tint = contentColor,
        )
        KeyAction.InputMethodPicker -> SlotIcon(
            IconSlots.KEY_INPUT_METHOD_PICKER,
            contentDescription = stringResource(R.string.ime_key_input_method_picker),
            tint = contentColor,
        )
        KeyAction.Emoji -> SlotIcon(
            IconSlots.KEY_EMOJI,
            contentDescription = stringResource(R.string.ime_key_emoji),
            tint = contentColor,
        )
        KeyAction.Space -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Split-spacebar left halves carry an empty label: no language name.
            if (key.label.isNotEmpty()) {
                val showArrows = visual.spaceArrows
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showArrows) Text(
                        text = "◀",
                        fontSize = (8 * fontScale).sp,
                        color = contentColor.copy(alpha = 0.35f),
                    )
                    // A custom label replaces the language name; %s inside it
                    // puts the name back, so "— %s —" keeps tracking the mode.
                    Text(
                        text = visual.spaceText,
                        fontSize = (11 * fontScale).sp,
                        color = contentColor.copy(alpha = 0.5f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showArrows) Text(
                        text = "▶",
                        fontSize = (8 * fontScale).sp,
                        color = contentColor.copy(alpha = 0.35f),
                    )
                }
            }
        }
        else -> Box(modifier = Modifier.fillMaxSize()) {
            // A key may draw a named icon in place of its glyph; an unknown name
            // resolves to null and falls through to the text label below.
            val mainIcon = KeyIcons.byName(key.icon)
            // A tool key with no label and no icon of its own wears the icon the
            // tool wears on the toolbar — through the slot registry, so an icon
            // pack redresses the key with the tool. A label the author typed wins:
            // a key that says "Voice" was asked for in words.
            val toolSlot = (key.action as? KeyAction.Tool)
                ?.takeIf { mainIcon == null && key.label.isBlank() }
                ?.let { IconSlots.forTool(it.tool) }
            if (mainIcon != null) {
                Icon(
                    mainIcon,
                    contentDescription = key.label.ifBlank { key.icon.orEmpty()},
                    tint = contentColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size((22f * fontScale).dp),
                )
            } else if (toolSlot != null) {
                SlotIcon(
                    toolSlot,
                    contentDescription = visual.spoken.resolved(),
                    tint = contentColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size((22f * fontScale).dp),
                )
            } else {
                // A blank label on an action key used to draw literally nothing,
                // so a Tab, Escape, Ctrl or Fn key made in the editor — where the
                // action picker never asks for a label — arrived here invisible.
                // The fallback lives in the layout module so the editor's preview
                // and this draw the same thing.
                val text = visual.label.ifBlank { key.action.fallbackLabel() }
                // Multi-character mode labels (?123, ABC, =\<, Ctrl) read as
                // labels, not characters — render them clearly smaller than
                // letters. Measured on the resolved text, or every fallback above
                // would come out at full letter size.
                //
                // A key carrying its own labelScale overrides that rule outright
                // rather than scaling its result: the whole point of the field is
                // to say what the automatic answer got wrong on this one key, and
                // it is what carries a HeliBoard label flag across (issue #18).
                val isModeLabel = key.action != KeyAction.Text && text.length > 1
                val keyScale = key.drawnLabelScale()
                val baseSize = when {
                    keyScale != null -> LetterLabelSp * keyScale
                    isModeLabel -> ModeLabelSp
                    else -> LetterLabelSp
                }
                // A custom layout may put any string on a key — ".com", or a word
                // like "SEND". Left alone, a long one wrapped onto a second line
                // and drew outside the key it belongs to, so it is held to one
                // line and stepped down until it fits.
                //
                // Measured rather than counted: a Bengali conjunct is three
                // UTF-16 units and one glyph, so a rule on `length` would shrink
                // exactly the labels that never needed it. Asking the finished
                // layout whether it overflowed is script-agnostic, costs the
                // ordinary one-glyph key nothing, and settles in a frame or two.
                var scale by remember(text, baseSize, fontScale) { mutableFloatStateOf(1f) }
                Text(
                    text = text,
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = (baseSize * fontScale * scale).sp,
                    fontWeight = if (settings.boldKeyLabels) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = {
                        // Floored, so a label nothing could fit ellipsises rather
                        // than shrinking to nothing.
                        if (it.hasVisualOverflow && scale > 0.5f) scale -= 0.08f
                    },
                )
            }
            // Corner hint: a named icon if the key carries one, otherwise the
            // key's first long-press alternate. The character hint is shown by
            // whichever keys the popup actually opens on — a clipboard shortcut
            // takes the long press over, and backspace and the spacebar hold to
            // repeat, so none of those annotate a popup that never appears. An
            // explicit icon hint is an authored annotation and stands regardless.
            //
            // Enter and the other action keys are in now that they can carry
            // alternates (issue #22): a key that does something on hold should
            // say so in the corner, the same as a letter does.
            //
            // Key.hideHint silences both: the author asked this one key for a
            // clean corner while keeping its alternates reachable.
            val hintIcon = if (key.hideHint) null else KeyIcons.byName(key.iconHint)
            val hint = if (key.hideHint) null else key.longPress.firstOrNull()
            when {
                settings.longPressHints && hintIcon != null -> Icon(
                    hintIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 4.dp)
                        .size((11f * fontScale * settings.layoutBehavior.hintFontScale).dp),
                )
                settings.longPressHints && key.opensAlternatesPopup() && hint != null -> Text(
                    text = hint,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 4.dp),
                    fontSize = (10 * fontScale * settings.layoutBehavior.hintFontScale).sp,
                    color = contentColor.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/**
 * The size an ordinary letter key's label is drawn at, before the user's
 * [KeyboardSettings.fontScale] and the layout's own multiplier. Also the unit a
 * [Key.labelScale] is a multiple of, which is why it is named rather than
 * inline.
 */
private const val LetterLabelSp = 23f

/** The smaller size a multi-character mode label (`?123`, `ABC`) falls back to. */
private const val ModeLabelSp = 15.6f

/**
 * Text drawn on the spacebar: the live language name, or the user's custom
 * [SettingsRepository.spacebarLabel] with %s standing in for that name.
 * Shared by the main keyboard's spacebar and the emoji panel's spacebar so
 * both read the same.
 */
private fun spacebarText(state: KeyboardUiState): String {
    val name = layoutSwitchLabel(
        state.layoutId,
        state.settings.enabledLayoutIds,
        state.settings.customLayouts,
        state.settings.layoutBehavior.spacebarDisplay,
    )
    val label = state.settings.spacebarLabel
    return if (label.isEmpty()) name else label.replace("%s", name)
}

private fun displayLabel(key: Key, state: KeyboardUiState): String {
    // Digit keys draw the chosen numeral system's glyphs (in every commit
    // scope, including display-only). The layout data stays ASCII; the swap
    // happens here at draw time. Non-digit labels pass through untouched.
    val digits = resolveNumeralDigits(
        state.settings.layoutBehavior.numeralSystemFor(state.language.id),
        state.language,
    )
    val shiftLabel = key.shiftLabel
    val raw = when {
        state.shiftState != ShiftState.OFF && shiftLabel != null -> shiftLabel
        // Cased-script letter labels track the live shift state: lowercase
        // normally, uppercase while shift or caps lock is active (Latin,
        // Cyrillic, Greek — not Bengali/Arabic/Hangul, which have no case).
        state.shiftState != ShiftState.OFF && key.action == KeyAction.Text &&
            !state.composer.isClusterShaping && state.script.hasLetterCase &&
            key.label.singleOrNull()?.isLetter() == true ->
            key.label.uppercase()
        else -> key.label
    }
    // Fixed Bengali layouts: vowel keys track the cursor context — the
    // independent letter (আ, ই …) at a word start, the kar (া, ি …) after a
    // consonant, the য়-glide (য়া) after a vowel — matching what the key
    // will actually commit.
    if (state.composer.isClusterShaping && key.action == KeyAction.Text &&
        state.vowelForm != BengaliGraphemes.VowelKeyForm.KAR
    ) {
        raw.singleOrNull()
            ?.let { BengaliGraphemes.vowelKeyText(it, state.vowelForm) }
            ?.let { return it }
    }
    // Fancy Text: the layout carries plain a–z; the selected style's glyph is
    // drawn here (and committed by the service's twin of this substitution),
    // so the keys are WYSIWYG and the compiled layout never varies. The
    // uppercase branch above already cased the plain letter, so shift shows
    // the style's capital via the upper map.
    fancyStyleFor(state)?.let { style -> return FancyStyles.transform(raw, style) }
    return mapDigits(raw, digits)
}

/**
 * The preview bubble is a separate window that lingers briefly after release
 * and, in on-key mode, covers the key itself plus part of the row above. It
 * must never intercept touches, or rapid re-taps land on the bubble window
 * and get dropped before the keyboard sees them.
 */
private val PreviewPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
)

/**
 * Hold time after which a spacebar press (with language switching on the
 * short-swipe slot) shows the language picker: longer than any normal tap,
 * shorter than the long-press delay so the picker feels immediate.
 */
private const val SpaceHoldPickerMs = 250

/**
 * Fixed height of one language-picker row, shared between the popup's layout
 * and the spacebar hold-drag gesture that steps through it — the two must
 * agree or the finger and the highlight drift apart.
 */
private const val PickerRowHeightDp = 40

/** The up half of a braille dot press: the same key with its release flag set. */
private fun brailleRelease(key: Key): Key {
    val action = key.action as KeyAction.BrailleDot
    return key.copy(action = action.copy(release = true))
}

/**
 * The backspace word-swipe curve, as fractions of the first word's distance
 * ([TextEditingSettings.backspaceWordStepDp]). The shipped 72 dp first pull
 * gives back the 56/6/28 dp the curve used to hard-code, and a user who
 * retunes the first pull moves the rest of the curve with it.
 */
private const val NEXT_WORD_STEP_RATIO = 56f / 72f
private const val WORD_STEP_SHRINK_RATIO = 6f / 72f
private const val MIN_WORD_STEP_RATIO = 28f / 72f

/**
 * What a held key's repeat tick plays.
 *
 * The buzz and the click are one call when both are wanted, because the sound
 * pack times its click against the haptic. Turning one off splits them: the
 * haptic-only path exists exactly so a held backspace can keep vibrating
 * without machine-gunning the click, which is the complaint that made
 * [FeedbackSettings.soundOnRepeat] a setting.
 */
private fun repeatFeedback(
    vibrate: Boolean,
    sound: Boolean,
    onKeyPress: () -> Unit,
    onKeySound: () -> Unit,
    onKeyHaptic: () -> Unit,
) {
    when {
        vibrate && sound -> onKeyPress()
        vibrate -> onKeyHaptic()
        sound -> onKeySound()
    }
}

/**
 * Press handling: tap commits, long-press opens alternates (or begins
 * repeating for delete), release cancels. The spacebar instead supports
 * horizontal swipes: a swipe that starts moving right away performs
 * [spaceShortSwipe], one that begins after holding the spacebar past the
 * long-press delay performs [spaceLongSwipe] — cursor movement steps the
 * text cursor, language switching cycles the enabled input modes with a
 * live tooltip preview and commits on release. Implemented with raw press
 * detection so repeat, popup and drag can share the gesture.
 */
private fun Modifier.pointerInputKey(
    key: Key,
    longPressDelayMs: Int,
    keyRepeat: KeyRepeatSettings,
    /** Carries the spacebar cursor step and the backspace word step. */
    textEditing: TextEditingSettings,
    spaceShortSwipe: SpaceSwipeAction,
    spaceLongSwipe: SpaceSwipeAction,
    enabledLayoutIds: List<String>,
    currentLayoutId: String,
    setPressed: (Boolean) -> Unit,
    onKeyPress: () -> Unit,
    onKeySound: () -> Unit,
    /**
     * The key coming back up, once per finger that lifts off it.
     *
     * Not folded into [setPressed], which is the *visual* press and so fires
     * only when the last finger leaves: a burst double-tap that lands a second
     * finger before the first lifts really is two keystrokes, and owes two
     * sounds at each end. Silent unless the sound in use recorded a key-up.
     */
    onKeyRelease: () -> Unit,
    vibrateOnSpace: Boolean,
    vibrateOnDeleteSwipe: Boolean,
    vibrateOnRepeat: Boolean,
    soundOnRepeat: Boolean,
    /** Buzz with no click, for a repeat tick that vibrates but stays quiet. */
    onKeyHaptic: () -> Unit,
    hapticOnLongPress: Boolean,
    hapticOnLongPressRelease: Boolean,
    openAlternates: () -> Unit,
    /** Live flick arm for the cross popup, or null when centred / released. */
    setFlickDirection: (FlickDirection?) -> Unit,
    onKey: (Key) -> Unit,
    /**
     * Un-debounced sink for auto-repeat ticks (held backspace/space). The
     * software repeat is deterministic, so it must bypass the tremor debounce
     * that [onKey] carries — otherwise repeats landing inside the debounce
     * window are dropped and the repeat rate is silently capped.
     */
    onKeyRepeat: (Key) -> Unit,
    onClipboardKey: (ClipboardKeyAction) -> Unit,
    onCursorMove: (Int) -> Unit,
    onCursorMoveVertical: (Int) -> Unit,
    onHideKeyboard: () -> Unit,
    spaceCursor2d: Boolean,
    spaceSwipeDownHide: Boolean,
    symbolsLongPressNumpad: Boolean,
    onLayoutSelect: (String) -> Unit,
    openLanguagePicker: () -> Unit,
    closeLanguagePicker: () -> Unit,
    /** Row of the open picker a hold-drag has reached; null clears the highlight. */
    setPickerDragIndex: (Int?) -> Unit,
    setLanguagePreview: (String?) -> Unit,
    canDelete: () -> Boolean,
    canForwardDelete: () -> Boolean,
    onDeleteWord: () -> Unit,
    backspaceSwipeDelete: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    smartResolve: (Key, PointerId) -> Key = { k, _ -> k },
): Modifier = this.then(
    if (key.action == KeyAction.Space &&
        (spaceShortSwipe != SpaceSwipeAction.NONE || spaceLongSwipe != SpaceSwipeAction.NONE ||
            spaceSwipeDownHide)
    ) {
        Modifier.pointerInput(
            key, spaceShortSwipe, spaceLongSwipe, enabledLayoutIds, currentLayoutId, longPressDelayMs,
            hapticOnLongPress, vibrateOnSpace, spaceCursor2d, spaceSwipeDownHide, textEditing,
        ) {
            val slopPx = 12.dp.toPx()
            val cursorStepPx = textEditing.spaceCursorStepDp.dp.toPx()
            val langStepPx = 44.dp.toPx()
            // One picker row of vertical travel moves the hold-drag selection
            // one row; must match the fixed row height LanguagePickerPopup lays
            // out, or the finger and the highlight drift apart.
            val pickerRowPx = PickerRowHeightDp.dp.toPx()
            // A swipe-down must clear this before it dismisses the keyboard —
            // well past the slop so a diagonal cursor drag never trips it.
            val hideThresholdPx = 40.dp.toPx()
            // Extra travel demanded before the language list wraps around at
            // either end — the boundary acts like a detent, not a wall.
            val langWrapPx = langStepPx * 2.5f
            awaitEachGesture {
                val down = awaitFirstDown()
                setPressed(true)
                // Space press feedback: sound stays, buzz is gated on its toggle.
                if (vibrateOnSpace) onKeyPress() else onKeySound()
                // Resolved on the first movement past the slop; null until
                // then (and forever for a plain tap).
                var action: SpaceSwipeAction? = null
                var accumulated = 0f
                var lastX = down.position.x
                // Vertical accumulator for the 2-D cursor pad, and a latch set
                // once a swipe-down has dismissed the keyboard (so release does
                // not also type a space).
                var accumulatedY = 0f
                var lastY = down.position.y
                var hidden = false
                var langIndex = enabledLayoutIds.indexOf(currentLayoutId).coerceAtLeast(0)
                // With exactly two languages a swipe direction means "the
                // other language", so a single run of travel toggles at most
                // once: runDir is the direction of the current run and
                // runSwitched whether it already toggled. Continued travel in
                // the same direction must never cycle back to the language the
                // user deliberately swiped away from; only reversing direction
                // switches back.
                val twoModes = enabledLayoutIds.size == 2
                var runDir = 0
                var runSwitched = false
                // With language switching on the short-swipe slot, holding
                // the spacebar just past a normal tap shows the language
                // picker without needing any initial swipe. The action is
                // not locked in — a drag afterwards still resolves short vs
                // long normally, so a hold + swipe cursor action survives —
                // but a release with the picker up must not type a space.
                var holdPreviewShown = false
                // With more than two languages the swipe ring is long, so a
                // hold opens the full tappable picker instead of the inline
                // preview. Once open, a vertical drag walks the list row by
                // row and release commits the highlighted layout; a hold that
                // never moves leaves the popup up for tapping, and release
                // types nothing either way.
                var pickerOpened = false
                var pickerIndex = langIndex
                var pickerMoved = false
                var pickerPrimed = false
                // Arm the hold-to-switch gesture whenever there is more than one
                // layout to switch between — independent of the swipe setting, so
                // the tappable picker stays reachable even when the language swipe
                // is off. A single-layout user gets nothing (holding space would
                // otherwise show a pointless one-item picker and swallow the
                // space). The picker only opens on a still-hold (action == null);
                // a drag sets action first and still runs the swipe/cursor gesture.
                val holdOpensSwitcher = enabledLayoutIds.size > 1
                val holdJob = if (holdOpensSwitcher) {
                    scope.launch {
                        delay(minOf(longPressDelayMs, SpaceHoldPickerMs).toLong())
                        if (action == null) {
                            // List for a long ring (> 4) or when the swipe can't
                            // cycle languages; otherwise the inline swipe preview.
                            val useList = enabledLayoutIds.size > 4 ||
                                spaceShortSwipe != SpaceSwipeAction.LANGUAGE
                            if (useList) {
                                pickerOpened = true
                                pickerIndex = langIndex
                                openLanguagePicker()
                            } else {
                                holdPreviewShown = true
                                setLanguagePreview(enabledLayoutIds[langIndex])
                            }
                            if (hapticOnLongPress) onKeyPress()
                        }
                    }
                } else {
                    null
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    // Picker is up: the finger now navigates its list. The
                    // first event only re-bases the vertical origin — the
                    // finger may have drifted (below slop) before the hold
                    // fired, and that drift must not count as a row step.
                    if (pickerOpened) {
                        if (!pickerPrimed) {
                            pickerPrimed = true
                            lastY = change.position.y
                            accumulatedY = 0f
                            change.consume()
                            continue
                        }
                        accumulatedY += change.position.y - lastY
                        lastY = change.position.y
                        var stepped = false
                        while (accumulatedY > pickerRowPx) {
                            if (pickerIndex < enabledLayoutIds.size - 1) { pickerIndex++; stepped = true }
                            accumulatedY -= pickerRowPx
                        }
                        while (accumulatedY < -pickerRowPx) {
                            if (pickerIndex > 0) { pickerIndex--; stepped = true }
                            accumulatedY += pickerRowPx
                        }
                        if (stepped) {
                            pickerMoved = true
                            setPickerDragIndex(pickerIndex)
                            onKeyPress()
                        }
                        change.consume()
                        continue
                    }
                    // The action this gesture would resolve to right now (short
                    // vs long by hold time). Used to decide whether the 2-D pad
                    // owns the vertical axis for this drag.
                    val candidate = if (change.uptimeMillis - down.uptimeMillis < longPressDelayMs) {
                        spaceShortSwipe
                    } else {
                        spaceLongSwipe
                    }
                    val cursorOwnsVertical = spaceCursor2d &&
                        (action == SpaceSwipeAction.CURSOR ||
                            (action == null && candidate == SpaceSwipeAction.CURSOR))
                    // Swipe straight down to dismiss the keyboard — unless the
                    // 2-D pad is claiming vertical for cursor movement.
                    if (spaceSwipeDownHide && !cursorOwnsVertical) {
                        val totalDy = change.position.y - down.position.y
                        val totalDx = change.position.x - down.position.x
                        if (totalDy > hideThresholdPx && totalDy > abs(totalDx)) {
                            change.consume()
                            hidden = true
                            onHideKeyboard()
                            break
                        }
                    }
                    if (action == null) {
                        val totalDx = change.position.x - down.position.x
                        val totalDy = change.position.y - down.position.y
                        // The pad may also resolve on a vertical drag, so a
                        // straight up/down slide starts moving the cursor; the
                        // language and horizontal-cursor paths still need
                        // horizontal slop, which keeps their flick direction sane.
                        val vertForCursor = spaceCursor2d &&
                            candidate == SpaceSwipeAction.CURSOR && abs(totalDy) > slopPx
                        if (abs(totalDx) > slopPx || vertForCursor) {
                            // Short vs long is decided by hold time, not travel
                            // distance — a fast flick covers more ground than a
                            // careful drag, so distance can't tell them apart.
                            // With the hold preview up the drag always
                            // navigates the language ring: the user is looking
                            // at a language chooser, so resolving the drag to
                            // the long-swipe action (cursor, usually) read as
                            // "swiping does nothing".
                            action = if (holdPreviewShown) SpaceSwipeAction.LANGUAGE else candidate
                            lastX = change.position.x
                            lastY = change.position.y
                            accumulated = 0f
                            accumulatedY = 0f
                            if (action == SpaceSwipeAction.NUMPAD) {
                                // Discrete action (A39): open the numeric panel once
                                // and go inert. The synthetic Numpad key routes
                                // through the same onKey dispatch the ?123 long-press
                                // uses. `hidden` latches so release types no space.
                                onKey(Key(label = " ", action = KeyAction.Numpad))
                                hidden = true
                                change.consume()
                                break
                            }
                            if (action == SpaceSwipeAction.LANGUAGE) {
                                // The movement that crossed the slop already
                                // counts: a quick flick switches one language.
                                // At a list end the flick parks on the boundary
                                // — wrapping needs a continued drag past the
                                // langWrapPx detent below. With two languages
                                // either direction simply toggles to the other.
                                val dir = if (totalDx > 0) 1 else -1
                                val flicked = if (twoModes) {
                                    1 - langIndex
                                } else {
                                    (langIndex + dir).coerceIn(0, enabledLayoutIds.size - 1)
                                }
                                if (flicked != langIndex) {
                                    langIndex = flicked
                                    onKeyPress()
                                }
                                runDir = dir
                                runSwitched = true
                                setLanguagePreview(enabledLayoutIds[langIndex])
                            }
                            change.consume()
                        }
                        continue
                    }
                    // 2-D touchpad: while sliding the cursor, a vertical drag
                    // steps the caret up and down as well. Runs alongside the
                    // horizontal step below, so a diagonal drag moves both axes.
                    if (spaceCursor2d && action == SpaceSwipeAction.CURSOR) {
                        accumulatedY += change.position.y - lastY
                        lastY = change.position.y
                        var movedV = false
                        while (accumulatedY > cursorStepPx) {
                            onCursorMoveVertical(1); accumulatedY -= cursorStepPx; movedV = true
                        }
                        while (accumulatedY < -cursorStepPx) {
                            onCursorMoveVertical(-1); accumulatedY += cursorStepPx; movedV = true
                        }
                        if (movedV) change.consume()
                    }
                    accumulated += change.position.x - lastX
                    lastX = change.position.x
                    when (action) {
                        SpaceSwipeAction.CURSOR -> {
                            var moved = false
                            while (accumulated > cursorStepPx) {
                                onCursorMove(1); accumulated -= cursorStepPx; moved = true
                            }
                            while (accumulated < -cursorStepPx) {
                                onCursorMove(-1); accumulated += cursorStepPx; moved = true
                            }
                            if (moved) change.consume()
                        }
                        SpaceSwipeAction.LANGUAGE -> {
                            if (twoModes) {
                                // One toggle per run of travel: piling on more
                                // distance in the same direction never wraps
                                // back to the starting language — the user
                                // swiped away from it on purpose. Reversing
                                // direction starts a new run and toggles back.
                                val dir = when {
                                    accumulated > langStepPx -> 1
                                    accumulated < -langStepPx -> -1
                                    else -> 0
                                }
                                if (dir != 0) {
                                    if (dir != runDir) {
                                        runDir = dir
                                        runSwitched = false
                                    }
                                    if (!runSwitched) {
                                        langIndex = 1 - langIndex
                                        runSwitched = true
                                        setLanguagePreview(enabledLayoutIds[langIndex])
                                        onKeyPress()
                                    }
                                    // Drain the overshoot so a reversal only
                                    // needs one step of travel to respond.
                                    accumulated = 0f
                                }
                                change.consume()
                                continue
                            }
                            // The list ends put up resistance instead of
                            // wrapping immediately: a wrap costs langWrapPx of
                            // travel (vs langStepPx per normal step), so the
                            // selection parks on the boundary language first
                            // and only cycles around on a deliberate pull.
                            val last = enabledLayoutIds.size - 1
                            var stepped = false
                            while (true) {
                                if (accumulated > langStepPx && langIndex < last) {
                                    langIndex++
                                    accumulated -= langStepPx
                                } else if (accumulated > langWrapPx && langIndex == last && last > 0) {
                                    langIndex = 0
                                    accumulated -= langWrapPx
                                } else if (accumulated < -langStepPx && langIndex > 0) {
                                    langIndex--
                                    accumulated += langStepPx
                                } else if (accumulated < -langWrapPx && langIndex == 0 && last > 0) {
                                    langIndex = last
                                    accumulated += langWrapPx
                                } else {
                                    break
                                }
                                stepped = true
                            }
                            if (stepped) {
                                setLanguagePreview(enabledLayoutIds[langIndex])
                                onKeyPress()
                            }
                            change.consume()
                        }
                        // NONE: the swipe is deliberately inert — swallow it
                        // so release does not type a space.
                        else -> change.consume()
                    }
                }
                holdJob?.cancel()
                setPressed(false)
                onKeyRelease()
                setLanguagePreview(null)
                when {
                    // A swipe-down already dismissed the keyboard: the finger
                    // lifting must not also type a space.
                    hidden -> {}
                    // The picker is up. A hold-drag that walked the list
                    // commits the highlighted row; a hold that never moved
                    // leaves the popup up for tapping. Neither types a space.
                    action == null && pickerOpened -> {
                        if (pickerMoved) {
                            val selected = enabledLayoutIds[pickerIndex]
                            closeLanguagePicker()
                            if (selected != currentLayoutId) onLayoutSelect(selected)
                        }
                    }
                    // Releasing with the hold preview up commits whatever it
                    // showed (usually the current language — a no-op) and
                    // must not type a space.
                    action == null && holdPreviewShown -> {
                        val selected = enabledLayoutIds[langIndex]
                        if (selected != currentLayoutId) onLayoutSelect(selected)
                    }
                    action == null -> onKey(key)
                    action == SpaceSwipeAction.LANGUAGE -> {
                        val selected = enabledLayoutIds[langIndex]
                        if (selected != currentLayoutId) onLayoutSelect(selected)
                    }
                    else -> {}
                }
            }
        }
    } else if (key.action == KeyAction.Delete && backspaceSwipeDelete) {
        // Backspace owns its whole gesture rather than bolting a drag onto
        // the shared press handler: tap, hold-to-repeat and word-swipe are
        // one state machine, so a drag can cleanly take over from the repeat
        // loop mid-press and the move events are consumed while it does.
        Modifier.pointerInput(key, longPressDelayMs, keyRepeat, textEditing, hapticOnLongPress,
            hapticOnLongPressRelease, vibrateOnRepeat, soundOnRepeat, vibrateOnDeleteSwipe) {
            val slopPx = 10.dp.toPx()
            // The first word costs a deliberate drag; later ones get cheaper,
            // down to a floor, so clearing a sentence is one long pull but a
            // flick can never take more than a word or two.
            //
            // The whole curve is derived from the one setting, in the same
            // proportions the fixed 72/56/6/28 dp had, so a user who shortens
            // the first pull shortens the rest with it rather than ending up
            // with a first word that costs less than the second.
            val firstStepDp = textEditing.backspaceWordStepDp.toFloat()
            val firstStepPx = firstStepDp.dp.toPx()
            val nextStepPx = (firstStepDp * NEXT_WORD_STEP_RATIO).dp.toPx()
            val stepShrinkPx = (firstStepDp * WORD_STEP_SHRINK_RATIO).dp.toPx()
            val minStepPx = (firstStepDp * MIN_WORD_STEP_RATIO).dp.toPx()
            fun wordStepPx(deleted: Int): Float = when (deleted) {
                0 -> firstStepPx
                else -> (nextStepPx - (deleted - 1) * stepShrinkPx).coerceAtLeast(minStepPx)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                setPressed(true)
                onKeyPress()
                var swiping = false
                var deleted = 0
                // X the next step is measured from: the press point until the
                // first word goes, then walked left one step at a time.
                var anchorX = down.position.x
                var longPressFired = false
                val repeat = scope.launch {
                    delay(keyRepeat.startDelayMs.toLong())
                    longPressFired = true
                    while (canDelete()) {
                        repeatFeedback(vibrateOnRepeat, soundOnRepeat, onKeyPress, onKeySound, onKeyHaptic)
                        onKeyRepeat(key)
                        delay(keyRepeat.deleteMs.toLong())
                    }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    if (!swiping && abs(change.position.x - down.position.x) > slopPx) {
                        swiping = true
                        repeat.cancel()
                        anchorX = down.position.x
                    }
                    if (swiping) {
                        // Claim the drag so nothing upstream reinterprets it.
                        change.consume()
                        while (anchorX - change.position.x >= wordStepPx(deleted)) {
                            anchorX -= wordStepPx(deleted)
                            if (!canDelete()) break
                            deleted++
                            if (vibrateOnDeleteSwipe) onKeyPress() else onKeySound()
                            onDeleteWord()
                        }
                        // Dragging back to the right re-anchors and resets the
                        // acceleration: a reversal stops the run, never replays it.
                        if (change.position.x > anchorX) {
                            anchorX = change.position.x
                            deleted = 0
                        }
                    }
                }
                repeat.cancel()
                setPressed(false)
                onKeyRelease()
                when {
                    // The swipe already did the deleting.
                    swiping -> Unit
                    !longPressFired -> onKey(key)
                    hapticOnLongPressRelease -> onKeyPress()
                }
            }
        }
    } else if (key.action == KeyAction.Text && key.flick.isNotEmpty()) {
        // A 12-key kana pad key: a tap commits the centre kana, a directional
        // flick past the slop commits that arm's kana instead. One pointer owns
        // the whole gesture (like space/backspace) so the cross popup can track
        // the live direction; a long press still opens the alternates popup.
        Modifier.pointerInput(key, longPressDelayMs, hapticOnLongPress, hapticOnLongPressRelease) {
            val slopPx = 22.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                setPressed(true)
                onKeyPress()
                var dir: FlickDirection? = null
                var longFired = false
                val longJob = if (key.opensAlternatesPopup()) {
                    scope.launch {
                        delay(longPressDelayMs.toLong())
                        if (dir == null) {
                            longFired = true
                            if (hapticOnLongPress) onKeyPress()
                            openAlternates()
                        }
                    }
                } else {
                    null
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) { change.consume(); break }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    // Dominant axis picks the arm; only directions the key
                    // actually defines count, so a flick toward an empty arm
                    // falls back to the centre tap rather than committing nothing.
                    val raw = when {
                        max(abs(dx), abs(dy)) < slopPx -> null
                        abs(dx) >= abs(dy) -> if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
                        else -> if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
                    }
                    val resolved = raw?.takeIf { key.flick.containsKey(it) }
                    if (resolved != dir) {
                        dir = resolved
                        setFlickDirection(dir)
                        // Committing to a flick arm cancels the pending long press.
                        if (dir != null) longJob?.cancel()
                    }
                    change.consume()
                }
                longJob?.cancel()
                setPressed(false)
                onKeyRelease()
                setFlickDirection(null)
                val chosen = dir?.let { key.flick[it] }
                when {
                    chosen != null -> onKey(key.copy(output = chosen))
                    // The long press already opened alternates; release must not
                    // also type the centre kana.
                    longFired -> if (hapticOnLongPressRelease) onKeyPress()
                    else -> onKey(key)
                }
            }
        }
    } else {
        // Settings are part of the pointerInput keys: pointerInput only
        // restarts when its keys change, so leaving them out would keep a
        // stale closure alive (e.g. release haptics still firing after the
        // toggle was turned off).
        Modifier.pointerInput(key, spaceShortSwipe, spaceLongSwipe, longPressDelayMs, keyRepeat,
            hapticOnLongPress, hapticOnLongPressRelease, vibrateOnSpace, vibrateOnRepeat,
            soundOnRepeat) {
            // Raw per-pointer tracking rather than detectTapGestures, which
            // handles one gesture at a time per key: a second finger landing
            // on the same key before the first lifts (burst double-taps) was
            // swallowed. Here every pointer gets its own press lifecycle.
            class Press {
                var longPressFired = false
                var job: Job? = null
            }
            val presses = HashMap<PointerId, Press>()
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        val press = presses[change.id]
                        when {
                            press == null && change.changedToDown() -> {
                                val p = Press()
                                presses[change.id] = p
                                setPressed(true)
                                // Space press buzz is gated on its own toggle;
                                // the key sound (if on) still plays either way.
                                if (key.action == KeyAction.Space && !vibrateOnSpace) {
                                    onKeySound()
                                } else {
                                    onKeyPress()
                                }
                                // A braille dot key chords: it reports the press
                                // itself (the chord engine gathers dots on the way
                                // down, commits when the last finger lifts) and has
                                // no tap-vs-long-press distinction, so no timer.
                                // Through the un-debounced sink: the tremor filter
                                // would eat the up half of a quick chord and leave
                                // the engine counting a finger that already left.
                                if (key.action is KeyAction.BrailleDot) {
                                    onKeyRepeat(key)
                                    continue
                                }
                                // One timer, two meanings: on a repeating key it
                                // is how long before the repeat starts, and on
                                // every other key it is how long before the
                                // accent popup opens. They were the same number
                                // until the repeat got its own, so pick here
                                // rather than after the wait.
                                val repeats = key.action == KeyAction.Delete ||
                                    key.action == KeyAction.ForwardDelete ||
                                    key.action == KeyAction.Space
                                p.job = scope.launch {
                                    delay(
                                        if (repeats) keyRepeat.startDelayMs.toLong()
                                        else longPressDelayMs.toLong(),
                                    )
                                    p.longPressFired = true
                                    if (repeats) {
                                        // Space and the two deletes each hold to
                                        // a different purpose, so each has its
                                        // own cadence.
                                        val intervalMs = when (key.action) {
                                            KeyAction.Space -> keyRepeat.spaceMs
                                            else -> keyRepeat.deleteMs
                                        }.toLong()
                                        // Held backspace stops once there is
                                        // nothing left to delete — no point
                                        // buzzing against an empty field. ⌦
                                        // runs out at the other end of the text,
                                        // so it polls its own predicate.
                                        while (
                                            when (key.action) {
                                                KeyAction.Delete -> canDelete()
                                                KeyAction.ForwardDelete -> canForwardDelete()
                                                else -> true
                                            }
                                        ) {
                                            repeatFeedback(
                                                vibrateOnRepeat, soundOnRepeat,
                                                onKeyPress, onKeySound, onKeyHaptic,
                                            )
                                            onKeyRepeat(key)
                                            delay(intervalMs)
                                        }
                                    } else if (key.clipboardAction != null) {
                                        // Clipboard shortcut replaces the alternates popup
                                        // on this key; the action fires immediately.
                                        if (hapticOnLongPress) onKeyPress()
                                        key.clipboardAction?.let(onClipboardKey)
                                    } else if (key.opensAlternatesPopup()) {
                                        // Characters, actions, or both: any key with
                                        // alternates opens the popup, which is what
                                        // puts them on the enter key (issue #22).
                                        //
                                        // Tactile cue that the long press registered and the
                                        // finger can be released (alternates are open / the
                                        // long-press action fired). Delete/space skip it:
                                        // their repeat loop already buzzes per repeat.
                                        if (hapticOnLongPress) onKeyPress()
                                        openAlternates()
                                    } else if (key.action == KeyAction.Symbols &&
                                        symbolsLongPressNumpad
                                    ) {
                                        // Opt-in: long-pressing ?123 opens the
                                        // numpad panel instead of acting like a tap.
                                        if (hapticOnLongPress) onKeyPress()
                                        onKey(key.copy(action = KeyAction.Numpad))
                                    } else if (key.action == KeyAction.LanguageSwitch) {
                                        // Tap cycles to the next language; the
                                        // long press opens the full picker.
                                        if (hapticOnLongPress) onKeyPress()
                                        openLanguagePicker()
                                    } else {
                                        // No alternates: long press behaves like a tap.
                                        if (hapticOnLongPress) onKeyPress()
                                        onKey(key)
                                    }
                                }
                            }
                            // Another handler claimed the pointer (glide typing
                            // consumed the move/up on the Initial pass): the
                            // press must not commit.
                            press != null && change.isConsumed -> {
                                press.job?.cancel()
                                presses.remove(change.id)
                                if (presses.isEmpty()) setPressed(false)
                                onKeyRelease()
                                // The chord engine counted this finger on the way
                                // down; a stolen pointer still has to count as a
                                // lift or the engine waits forever for it.
                                if (key.action is KeyAction.BrailleDot) {
                                    onKeyRepeat(brailleRelease(key))
                                }
                            }
                            press != null && change.changedToUp() -> {
                                change.consume()
                                press.job?.cancel()
                                presses.remove(change.id)
                                if (presses.isEmpty()) setPressed(false)
                                onKeyRelease()
                                if (key.action is KeyAction.BrailleDot) {
                                    // No bounds test: the dot was gathered on the
                                    // press, so the lift only closes the chord —
                                    // sliding off cannot un-press what already
                                    // counted, it just must not strand the engine.
                                    onKeyRepeat(brailleRelease(key))
                                    continue
                                }
                                // Forgiving bounds: a sloppy fast tap that drifts
                                // slightly off the cell still commits; a deliberate
                                // slide well away (≥ half a key beyond the edge)
                                // cancels, preserving slide-off-to-cancel.
                                val inBounds =
                                    change.position.x > -size.width * 0.5f &&
                                        change.position.x < size.width * 1.5f &&
                                        change.position.y > -size.height * 0.5f &&
                                        change.position.y < size.height * 1.5f
                                if (!press.longPressFired) {
                                    // Smart key-hit may swap in a likelier
                                    // neighbour chosen when this pointer went down.
                                    if (inBounds) onKey(smartResolve(key, change.id))
                                } else if (hapticOnLongPressRelease) {
                                    onKeyPress()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
)

// ---- emoji panel ----

/** Sentinel tab id for the history tab; ★ avoids clashing with catalog categories. */
private const val RECENT_TAB = "★recent"

/** Sentinel tab ids for the two optional text-art tabs. See [TextArt]. */
private const val KAOMOJI_TAB = "★kaomoji"
private const val EMOTICON_TAB = "★emoticon"

/**
 * Tab-strip label for the text-art tabs. They get a glyph instead of an icon:
 * two more smiley icons next to the existing ones would be indistinguishable,
 * and the glyph says exactly what the tab holds.
 */
private fun textArtTabLabel(tab: String): String? = when (tab) {
    KAOMOJI_TAB -> "^_^"
    EMOTICON_TAB -> ":)"
    else -> null
}

/**
 * Tab key → icon slot. The history tab is two slots, not one: "recent" and
 * "most used" are different orderings and wear different glyphs, so a pack can
 * replace them independently. The text-art tabs have no slot — they draw
 * [textArtTabLabel] instead of an icon.
 */
private fun emojiTabSlot(tab: String, mostUsed: Boolean): String = when (tab) {
    RECENT_TAB -> if (mostUsed) IconSlots.EMOJI_TAB_MOST_USED else IconSlots.EMOJI_TAB_RECENT
    else -> IconSlots.forEmojiCategory(tab)
}

/**
 * One compact emoji tab: a 20dp icon over a 2dp selection bar, in a plain
 * weighted cell so search + every category share the row evenly. [label]
 * substitutes a short glyph for the icon (the text-art tabs use it).
 */
@Composable
private fun RowScope.EmojiTab(
    slot: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
    focused: Boolean = false,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(32.dp)
            .focusRing(focused, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val tint = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            if (label != null) {
                Text(
                    text = label,
                    // Never wraps: the cell is one tab wide and a broken
                    // "^_^" would read as a rendering bug, not a label.
                    maxLines = 1,
                    softWrap = false,
                    // Smaller than the 20dp icons it sits beside: with a dozen
                    // categories every tab cell is barely 28dp wide.
                    fontSize = 11.sp,
                    color = tint,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            } else {
                SlotIcon(
                    slot,
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = tint,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

/**
 * The panel's search box: a tappable pill showing the live query, with a
 * hold-to-repeat backspace that edits the real text field while search mode
 * stays up. Shared by the in-panel layout and the full-bleed header.
 */
@Composable
private fun EmojiSearchField(
    state: KeyboardUiState,
    onEmojiQueryTap: () -> Unit,
    onSearchFieldDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = LocalKeyPressFeedback.current
    val keySound = LocalKeySound.current
    val canDeleteField = LocalCanDeleteField.current
    val scope = rememberCoroutineScope()
    val vibrateOnRepeat = state.settings.feedback.vibrateOnRepeat
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .clickable { onEmojiQueryTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.width(8.dp))
        SearchQueryText(
            query = state.emojiQuery,
            placeholder = stringResource(R.string.ime_emoji_search_hint),
            active = state.emojiSearchActive,
            textColor = MaterialTheme.colorScheme.onSurface,
            placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        // While searching, the keys type into the query — so an emoji just
        // inserted from the results can't be deleted from the field with
        // them. This backspace edits the real text field (with
        // hold-to-repeat), keeping search mode up.
        if (state.emojiSearchActive) {
            Icon(
                Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = stringResource(R.string.ime_emoji_search_delete_desc),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
                    .pointerInput(
                        state.settings.longPressDelayMs,
                        state.settings.keyRepeat.deleteMs,
                        vibrateOnRepeat,
                    ) {
                        detectTapGestures(
                            onPress = {
                                feedback()
                                onSearchFieldDelete()
                                val repeat = scope.launch {
                                    delay(state.settings.longPressDelayMs.toLong())
                                    while (canDeleteField()) {
                                        if (vibrateOnRepeat) feedback() else keySound()
                                        onSearchFieldDelete()
                                        delay(state.settings.keyRepeat.deleteMs.toLong())
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            },
                        )
                    },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The face to show for an emoji [base] in the panel grid and in search: the
 * global default skin tone, or the last-used variant when that override is
 * enabled. Mirrors the IME's `applyEmojiTone`, so what is drawn is what a tap
 * commits.
 */
private fun emojiDisplay(state: KeyboardUiState, base: String): String {
    val emoji = state.settings.emoji
    return state.emojiVariants.tonedDisplay(
        base = base,
        tone = emoji.defaultSkinTone.toneIndex,
        preferred = state.emojiVariantPrefs[base],
        overrideWithPreferred = emoji.toneOverrideByLastUsed,
    )
}

@Composable
private fun EmojiPanel(
    state: KeyboardUiState,
    onEmoji: (String) -> Unit,
    onEmojiVariant: (String, String) -> Unit,
    onEmojiFavourite: (String) -> Unit,
    onEmojiQueryTap: () -> Unit,
    onClearRecents: () -> Unit,
    onRecentRemove: (String) -> Unit,
    onFavouritesReorder: (List<String>) -> Unit,
    onLongPress: (String) -> Unit,
    onLongPressEnd: () -> Unit,
    onAnimatedSend: (String) -> Unit,
    onStickerSend: (String) -> Unit,
    onSearchFieldDelete: () -> Unit,
    onTextArt: (String) -> Unit,
    onKey: (Key) -> Unit,
    onClose: () -> Unit,
) {
    // Gender/role variants (🏃‍♀️, 👨‍⚕️…) collapse under their base emoji;
    // the popup offers them, the grid stays tidy.
    val variantChildren = remember(state.emojiCatalog) {
        state.emojiCatalog
            .mapNotNull { entry -> entry.parent?.let { parent -> parent to entry.emoji } }
            .groupBy({ it.first }, { it.second })
    }
    val historyMode = state.settings.emojiTabMode
    val history = (if (historyMode == EmojiTabMode.MOST_USED) state.emojiFrequents else state.emojiRecents)
        .let { if (state.hiddenEmoji.isEmpty()) it else it.filterNot { e -> e in state.hiddenEmoji } }
    // Reorder is reached from any favourited emoji's long-press popup, and is
    // only meaningful once there are two favourites to shuffle.
    var reorderOpen by remember { mutableStateOf(false) }
    val onReorderFavourite: (() -> Unit)? =
        if (state.emojiFavourites.size >= 2) ({ reorderOpen = true }) else null
    // The always-on emoji row hides while this panel is open; absorbing its
    // height here keeps the keyboard from resizing on panel switches.
    val barCompensation =
        if (state.settings.emojiBarMode == EmojiBarMode.ALWAYS) EmojiBarHeight else 0.dp
    // Full-bleed hides the toolbar and the symbol row as well, and spends
    // the reclaimed row on a back button plus the category tabs — the panel
    // absorbs all of it so the keyboard never resizes on a panel switch.
    // Search mode hides the toolbar row too (see KeyboardBody), so the same
    // accounting applies with fewer rows to reclaim.
    val fullBleed = state.settings.emojiFullBleed
    val height = when {
        state.emojiSearchActive && fullBleed -> 120.dp + fullBleedHiddenRows(state)
        state.emojiSearchActive -> 120.dp + topBarHeight(state.settings) + barCompensation
        fullBleed -> keyRowsHeight(state) + fullBleedHiddenRows(state)
        else -> keyRowsHeight(state) + barCompensation
    }
    // One category rendered at a time behind tabs: the full catalog in a
    // single grid was a composition/measure hog. Hoisted above everything
    // else so the full-bleed header can host the strip.
    val categories = remember(state.emojiCatalog) {
        state.emojiCatalog.map { it.category }.distinct()
    }
    val hasHistory = history.isNotEmpty()
    // Kaomoji and emoticons sit after the Unicode categories: opt-in extras,
    // and appending them leaves every existing tab where muscle memory
    // expects it.
    val textArtTabs = state.settings.emoji.kaomojiTabs
    val tabs = remember(categories, hasHistory, textArtTabs) {
        buildList {
            if (hasHistory) add(RECENT_TAB)
            addAll(categories)
            if (textArtTabs) {
                add(KAOMOJI_TAB)
                add(EMOTICON_TAB)
            }
        }
    }
    val scope = rememberCoroutineScope()
    // A pager, not a swapped-in single grid: horizontal swipes cross
    // categories and every tab switch slides across. currentPage drives the
    // underline, updating live as a drag passes the halfway point; each page
    // keeps its own scroll offset via the stable key on the pager below.
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val selectedTab = tabs.getOrElse(pagerState.currentPage) { tabs.firstOrNull().orEmpty() }
    // Compact icon strip: search plus every category, split evenly across
    // the width so everything fits with no scrolling — Material's Tab has a
    // 90dp min width that forced a ScrollableTabRow here before.
    // Tab (the key) reaches the category strip. The pager page is the selection,
    // so activating a chip is just scrolling the pager — no state to hoist.
    val goToTab: (Int) -> Unit = { index ->
        scope.launch {
            if (state.settings.reduceMotion) pagerState.scrollToPage(index)
            else pagerState.animateScrollToPage(index)
        }
    }
    PanelFocusTarget(
        panel = PanelMode.EMOJI,
        region = FocusRegion.CHIPS,
        count = tabs.size,
        columns = tabs.size.coerceAtLeast(1),
        onActivate = goToTab,
    )
    PanelFocusTarget(
        panel = PanelMode.EMOJI,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { onEmojiQueryTap() },
    )
    val focusedTab = state.focusedIndex(FocusRegion.CHIPS)
    val tabStrip: @Composable RowScope.() -> Unit = {
        EmojiTab(
            slot = IconSlots.EMOJI_TAB_SEARCH,
            description = stringResource(R.string.ime_emoji_tab_search_desc),
            selected = false,
            focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
            onClick = onEmojiQueryTap,
        )
        tabs.forEachIndexed { index, tab ->
            EmojiTab(
                slot = emojiTabSlot(tab, historyMode == EmojiTabMode.MOST_USED),
                description = when (tab) {
                    KAOMOJI_TAB -> stringResource(R.string.ime_emoji_tab_kaomoji)
                    EMOTICON_TAB -> stringResource(R.string.ime_emoji_tab_emoticons)
                    RECENT_TAB -> stringResource(
                        if (historyMode == EmojiTabMode.MOST_USED) {
                            R.string.ime_emoji_tab_most_used
                        } else {
                            R.string.ime_emoji_tab_recent
                        },
                    )
                    // An emoji group name, which comes from the catalog data.
                    else -> tab.replaceFirstChar { it.uppercase() }
                },
                label = textArtTabLabel(tab),
                selected = tab == selectedTab,
                focused = index == focusedTab,
                // Tapping a tab slides there too, matching the swipe;
                // reduce-motion jumps instead.
                onClick = { goToTab(index) },
            )
        }
    }
    val searching = state.emojiSearchActive || state.emojiQuery.isNotEmpty()
    // User-sized grid cells. The floor never drops below the glyph plus the
    // cell's own padding (6.dp a side in EmojiCell), so a large emoji size on
    // a small cell size cannot spill glyphs into their neighbours.
    val gridCell = with(state.settings.emoji) { maxOf(gridCellSize, gridEmojiSize + 12) }.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // Full-bleed header, standing in for the toolbar it replaced: back to
        // the keys, then whichever control the panel is currently driven by.
        if (fullBleed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolCircle(
                    slot = IconSlots.CHROME_PANEL_BACK,
                    description = stringResource(R.string.ime_panel_back_desc),
                    active = false,
                    onClick = onClose,
                )
                if (searching) {
                    EmojiSearchField(
                        state = state,
                        onEmojiQueryTap = onEmojiQueryTap,
                        onSearchFieldDelete = onSearchFieldDelete,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = 2.dp),
                    )
                } else if (tabs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = tabStrip,
                    )
                }
            }
        }
        // The grids fill whatever the bottom control bar leaves over.
        Column(modifier = Modifier.weight(1f)) {
        if (state.emojiQuery.isNotEmpty()) {
            // Memoized, and distinct so it is safe to key by: mapping inline
            // in the items() call rebuilt the list on every recomposition,
            // and every emoji tap emits fresh state, so the whole result grid
            // was thrown away and rebuilt on each keystroke.
            val results = remember(state.emojiResults) {
                state.emojiResults.map { it.emoji }.distinct()
            }
            val resultsGrid = rememberLazyGridState()
            val focusedResult = state.focusedIndex()
            PanelFocusTarget(
                panel = PanelMode.EMOJI,
                count = results.size,
                columns = adaptiveColumns(resultsGrid),
                onActivate = { index ->
                    results.getOrNull(index)?.let { onEmoji(emojiDisplay(state, it)) }
                },
            )
            ScrollFocusIntoView(focusedResult) { resultsGrid.animateScrollToItem(it) }
            LazyVerticalGrid(
                state = resultsGrid,
                columns = GridCells.Adaptive(minSize = gridCell),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(results, key = { _, emoji -> emoji }) { index, emoji ->
                    EmojiCell(
                        base = emoji,
                        // Search honours the global default skin tone (and the
                        // last-used variant when that override is on).
                        display = emojiDisplay(state, emoji),
                        state = state,
                        genderVariants = variantChildren[emoji].orEmpty(),
                        onTap = onEmoji,
                        onPick = { variant -> onEmojiVariant(emoji, variant) },
                        onFavourite = onEmojiFavourite,
                        onReorderFavourites = onReorderFavourite,
                        onLongPress = onLongPress,
                        onLongPressEnd = onLongPressEnd,
                        onAnimatedSend = onAnimatedSend,
                        onStickerSend = onStickerSend,
                        focused = index == focusedResult,
                    )
                }
            }
            return@Column
        }

        if (!fullBleed && !state.emojiSearchActive && tabs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = tabStrip,
            )
        }

        if (tabs.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // The default already limits composition to the visible page
                // plus whatever a swipe drags into view, so a deep catalog
                // never all mounts at once — the reason a single grid was too
                // heavy in the first place.
                beyondViewportPageCount = 0,
                // Stable per-tab key: a page keeps its own scroll offset even
                // as history appears/disappears and shifts the indices, and a
                // cell's open long-press popup rides with its tab, not a slot.
                key = { tabs[it] },
            ) { page ->
                val tab = tabs[page]
                if (tab == KAOMOJI_TAB || tab == EMOTICON_TAB) {
                    TextArtGrid(kaomoji = tab == KAOMOJI_TAB, onTap = onTextArt)
                } else if (tab == RECENT_TAB) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Kept inside the page so the pager height stays fixed
                        // across a swipe — a row that appeared or vanished
                        // mid-drag would jolt the grid.
                        if (state.settings.emojiClearRecentsButton &&
                            historyMode == EmojiTabMode.RECENTS
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(modifier = Modifier.weight(1f))
                                TextButton(onClick = onClearRecents) {
                                    Icon(
                                        Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Box(modifier = Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.ime_emoji_clear_recents),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        val historyGrid = rememberLazyGridState()
                        // Only the page in front owns the ring; the pager keeps
                        // its neighbours composed, and two pages publishing
                        // would race over one region.
                        val focusedHistory = state.focusedIndex()
                            .takeIf { page == pagerState.currentPage }
                        if (page == pagerState.currentPage) {
                            PanelFocusTarget(
                                panel = PanelMode.EMOJI,
                                count = history.size,
                                columns = adaptiveColumns(historyGrid),
                                onActivate = { index -> history.getOrNull(index)?.let(onEmoji) },
                            )
                        }
                        ScrollFocusIntoView(focusedHistory) { historyGrid.animateScrollToItem(it) }
                        LazyVerticalGrid(
                            state = historyGrid,
                            columns = GridCells.Adaptive(minSize = gridCell),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            // Keyed by emoji: this list reorders under the grid
                            // (favouriting pins to the front, removing closes a
                            // gap), so on a positional key a cell's open popup
                            // stayed behind with the slot and reappeared over a
                            // different emoji. EmojiUsage.pinned() is
                            // distinct(), so the key is unique.
                            itemsIndexed(history, key = { _, emoji -> emoji }) { index, emoji ->
                                // History cells are exact sequences: no variant
                                // pref to remember, taps in the popup commit
                                // directly.
                                EmojiCell(
                                    base = emoji,
                                    display = emoji,
                                    state = state,
                                    genderVariants = emptyList(),
                                    onTap = onEmoji,
                                    onPick = onEmoji,
                                    onFavourite = onEmojiFavourite,
                                    onReorderFavourites = onReorderFavourite,
                                    onLongPress = onLongPress,
                                    onLongPressEnd = onLongPressEnd,
                                    onAnimatedSend = onAnimatedSend,
                                    onStickerSend = onStickerSend,
                                    onRemove = onRecentRemove,
                                    focused = index == focusedHistory,
                                )
                            }
                        }
                    }
                } else {
                    val emojis = remember(state.emojiCatalog, tab, state.hiddenEmoji) {
                        state.emojiCatalog
                            .filter {
                                it.category == tab && it.parent == null &&
                                    it.emoji !in state.hiddenEmoji
                            }
                            .map { it.emoji }
                    }
                    val categoryGrid = rememberLazyGridState()
                    val focusedEmoji = state.focusedIndex()
                        .takeIf { page == pagerState.currentPage }
                    if (page == pagerState.currentPage) {
                        PanelFocusTarget(
                            panel = PanelMode.EMOJI,
                            count = emojis.size,
                            columns = adaptiveColumns(categoryGrid),
                            onActivate = { index ->
                                emojis.getOrNull(index)?.let { onEmoji(emojiDisplay(state, it)) }
                            },
                        )
                    }
                    ScrollFocusIntoView(focusedEmoji) { categoryGrid.animateScrollToItem(it) }
                    LazyVerticalGrid(
                        state = categoryGrid,
                        columns = GridCells.Adaptive(minSize = gridCell),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        // Keyed by emoji, not by slot: a cell owns the open
                        // state of its long-press popup, and on a positional
                        // key that state stays with the slot while the list
                        // under it changes — the popup jumped to whatever
                        // emoji landed in that position.
                        itemsIndexed(emojis, key = { _, emoji -> emoji }) { index, emoji ->
                            EmojiCell(
                                base = emoji,
                                // The grid honours the global default skin tone
                                // too (and the last-used variant when that
                                // override is on) — a default nothing draws is
                                // a setting that looks broken.
                                display = emojiDisplay(state, emoji),
                                state = state,
                                genderVariants = variantChildren[emoji].orEmpty(),
                                onTap = onEmoji,
                                onPick = { variant -> onEmojiVariant(emoji, variant) },
                                onFavourite = onEmojiFavourite,
                                onReorderFavourites = onReorderFavourite,
                                onLongPress = onLongPress,
                                onLongPressEnd = onLongPressEnd,
                                onAnimatedSend = onAnimatedSend,
                                onStickerSend = onStickerSend,
                                focused = index == focusedEmoji,
                            )
                        }
                    }
                }
            }
        }
        }
        // The search field sits under the grid, in the slot the control bar
        // would use — emoji stay at the top of the panel, nearest the thumb
        // that opened it, and the field is beside the keys typing into it.
        // It only shows while a search is underway; idle, the entry point is
        // the first icon of the tab strip, so the panel doesn't spend a whole
        // bar of vertical space on it.
        if (!fullBleed && searching) {
            EmojiSearchField(
                state = state,
                onEmojiQueryTap = onEmojiQueryTap,
                onSearchFieldDelete = onSearchFieldDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // In search mode the key rows sit right below the panel, so the
        // control bar would be redundant chrome.
        if (!state.emojiSearchActive) {
            EmojiBottomBar(state = state, onKey = onKey, onClose = onClose)
        }
    }
    // A Popup overlay, so opening it never reflows the fixed-height panel.
    if (reorderOpen) {
        FavouritesReorderPopup(
            favourites = state.emojiFavourites,
            onConfirm = {
                reorderOpen = false
                onFavouritesReorder(it)
            },
            onDismiss = { reorderOpen = false },
        )
    }
}

/**
 * A text-art tab's grid — kaomoji when [kaomoji], Western emoticons
 * otherwise. Grouped by mood behind full-width headers, because a flat 200-
 * entry wall of faces is unskimmable.
 *
 * Kaomoji get much wider cells than emoticons: ":-)" is three characters and
 * "(╯°□°）╯︵ ┻━┻" is thirteen, so one column width for both would either
 * waste most of the row or squeeze the long ones to nothing.
 */
@Composable
private fun TextArtGrid(kaomoji: Boolean, onTap: (String) -> Unit) {
    val groups = if (kaomoji) TextArt.kaomoji else TextArt.emoticons
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (kaomoji) 132.dp else 64.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        groups.forEach { group ->
            item(key = "§${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = group.name,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            // Keyed by group + entry: a handful of faces appear in more than
            // one mood, and a bare entry key would collide across groups.
            items(group.items, key = { "${group.name} $it" }) { art ->
                TextArtCell(art = art, onTap = onTap)
            }
        }
    }
}

/**
 * One text-art cell: a key-coloured chip whose glyph shrinks until it fits on
 * a single line. Wrapping or ellipsizing are both wrong here — half a kaomoji
 * is unrecognisable, and the user is picking by shape, not by name.
 */
@Composable
private fun TextArtCell(art: String, onTap: (String) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // Reset per entry: cells are recycled across scroll positions, so a size
    // shrunk to fit a long kaomoji would otherwise stick to a short one.
    var fontSize by remember(art) { mutableStateOf(15.sp) }
    var settled by remember(art) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(3.dp)
            .fillMaxWidth()
            .height(38.dp)
            .background(kb.key, kb.keyShape())
            .clickable {
                feedback()
                onTap(art)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = art,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                // Hidden until the shrink loop settles: the first frame is
                // laid out at full size and would visibly overflow the chip.
                .graphicsLayer { alpha = if (settled) 1f else 0f },
            maxLines = 1,
            softWrap = false,
            fontSize = fontSize,
            color = kb.keyText,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && fontSize.value > 8f) {
                    fontSize = (fontSize.value * 0.88f).sp
                } else {
                    settled = true
                }
            },
        )
    }
}

/**
 * Bottom control row of the emoji panel (Gboard style): back to the keys
 * on the left, a spacebar in the middle, and a repeating backspace on the
 * right — a quick emoji run never needs a detour through the letter keys.
 * Sized to the real bottom key row: same 10-unit grid (abc and ⌫ at the
 * ?123 key's 1.5 width), same key height and gaps.
 */
@Composable
private fun EmojiBottomBar(
    state: KeyboardUiState,
    onKey: (Key) -> Unit,
    onClose: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val keySound = LocalKeySound.current
    val canDelete = LocalCanDelete.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    val shape = kb.keyShape(bleedDp = keyGapH(settings).value)
    // Cell = touch target spanning the gap, like KeyButton: the input
    // modifier sits outside the padding so presses between keys still land.
    val cell: @Composable RowScope.(Float, Modifier, @Composable () -> Unit) -> Unit =
        { weight, input, content ->
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .then(input)
                    .padding(horizontal = keyGapH(settings), vertical = keyGapV(settings)),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(settings.keyHeightDp.dp + keyGapV(settings) * 2)
            .padding(horizontal = 1.5.dp),
    ) {
        cell(
            1.5f,
            Modifier.clickable {
                feedback()
                onClose()
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(kb.modifierKey, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "abc",
                    color = kb.modifierKeyText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        cell(
            7f,
            Modifier.clickable {
                if (settings.feedback.vibrateOnSpace) feedback() else keySound()
                onKey(Key(" ", action = KeyAction.Space))
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(kb.key, shape),
                contentAlignment = Alignment.Center,
            ) {
                // The emoji panel's spacebar shows "Space", not the language
                // name: emoji picking is language-agnostic. A custom label
                // still applies, with %s standing in for "Space".
                val custom = settings.spacebarLabel
                Text(
                    text = stringResource(R.string.ime_key_space).let { space ->
                        if (custom.isEmpty()) space else custom.replace("%s", space)
                    },
                    fontSize = 11.sp,
                    color = kb.keyText.copy(alpha = 0.5f),
                )
            }
        }
        cell(
            1.5f,
            Modifier.pointerInput(
                settings.longPressDelayMs,
                settings.keyRepeat.deleteMs,
                settings.feedback.vibrateOnRepeat,
            ) {
                detectTapGestures(
                    onPress = {
                        feedback()
                        onKey(Key("⌫", action = KeyAction.Delete))
                        // Same hold-to-repeat cadence as the real backspace,
                        // buzzing on every repeat — and the same stop once
                        // the field has nothing left to delete.
                        val repeat = scope.launch {
                            delay(settings.longPressDelayMs.toLong())
                            while (canDelete()) {
                                if (settings.feedback.vibrateOnRepeat) feedback() else keySound()
                                onKey(Key("⌫", action = KeyAction.Delete))
                                delay(settings.keyRepeat.deleteMs.toLong())
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                    },
                )
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(kb.modifierKey, shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = stringResource(R.string.ime_key_delete),
                    modifier = Modifier.size(20.dp),
                    tint = kb.modifierKeyText,
                )
            }
        }
    }
}

/**
 * One emoji in the grid. Tap commits [display] (the user's preferred
 * variant of [base]); long-press opens the variant popup with the
 * favourite toggle, gender variants, skin tones, and — for two-person
 * emojis like the handshake — a per-person tone selector.
 */
@Composable
private fun EmojiCell(
    base: String,
    display: String,
    state: KeyboardUiState,
    genderVariants: List<String>,
    onTap: (String) -> Unit,
    onPick: (String) -> Unit,
    onFavourite: (String) -> Unit,
    onReorderFavourites: (() -> Unit)? = null,
    onLongPress: (String) -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    onAnimatedSend: (String) -> Unit = {},
    onStickerSend: (String) -> Unit = {},
    onRemove: ((String) -> Unit)? = null,
    focused: Boolean = false,
) {
    var showVariants by remember { mutableStateOf(false) }
    val onHaptic = LocalHapticFeedback.current
    Box {
        Text(
            text = LocalEmojiShaper.current.shape(display),
            modifier = Modifier
                .focusRing(focused)
                .pointerInput(base, display) {
                    detectTapGestures(
                        onTap = { onTap(display) },
                        onLongPress = {
                            // Haptic only: the key sound would read as "emoji
                            // inserted", which a long press does not do.
                            if (state.settings.hapticOnLongPress) onHaptic()
                            showVariants = true
                            onLongPress(display)
                        },
                    )
                }
                .padding(6.dp),
            fontSize = state.settings.emoji.gridEmojiSize.sp,
            fontFamily = emojiFamilyFor(display),
        )
        if (showVariants) {
            EmojiVariantPopup(
                base = base,
                display = display,
                name = if (state.settings.emojiLongPressName) {
                    // Named in the language being typed when a pack for it is
                    // installed, and in the catalog's own (Unicode) name
                    // otherwise — never in whatever other language happens to
                    // have a pack on the device.
                    EmojiNames.of(
                        catalog = state.emojiCatalog,
                        emoji = display,
                        base = base,
                        localised = state.emojiNamesByLang[state.language.id].orEmpty(),
                    )
                } else {
                    null
                },
                index = state.emojiVariants,
                genderVariants = genderVariants,
                favourite = display in state.emojiFavourites,
                animated = state.animatedEmojiOffer(display),
                onAnimatedSend = { onAnimatedSend(display) },
                sticker = state.canSendEmojiSticker(),
                stickerSending = state.mediaDownloadingId == emojiStickerJobId(display),
                onStickerSend = { onStickerSend(display) },
                onDismiss = {
                    showVariants = false
                    onLongPressEnd()
                },
                onPick = {
                    showVariants = false
                    onLongPressEnd()
                    onPick(it)
                },
                onFavourite = onFavourite,
                onReorderFavourites = onReorderFavourites?.let { reorder ->
                    {
                        showVariants = false
                        onLongPressEnd()
                        reorder()
                    }
                },
                onRemove = onRemove?.let { remove ->
                    {
                        showVariants = false
                        onLongPressEnd()
                        remove(display)
                    }
                },
            )
        }
    }
}

/**
 * What the long-press popup knows about an emoji's animated version: the
 * preview file once it lands, whether it is still on its way, and whether the
 * GIF behind the send button is being fetched right now.
 */
internal data class AnimatedEmojiOffer(
    val file: File?,
    val loading: Boolean,
    val sending: Boolean,
    /** How far the GIF has come down, or null while that isn't known yet. */
    val progress: Float? = null,
)

/**
 * The animated version on offer for [emoji], or null when there is none to
 * offer: nothing is published for it, the setting is off, or the focused field
 * takes no images and a GIF would have nowhere to go.
 */
internal fun KeyboardUiState.animatedEmojiOffer(emoji: String): AnimatedEmojiOffer? {
    if (!settings.emoji.animated || !acceptsRichMedia) return null
    // Data saving turned this one off outright: no offer at all, since there
    // is nothing to say about it that a greyed-out button would say better.
    // "Ask each time" still offers it — the press is the answer, and only the
    // automatic preview is held back (see `onEmojiLongPressed`).
    if (dataSaver.decide(MeteredFeature.ANIMATED_EMOJI) == MeteredDecision.BLOCKED) return null
    val key = animatedEmoji.keyFor(emoji) ?: return null
    val sending = mediaDownloadingId == key
    return AnimatedEmojiOffer(
        file = animatedEmojiFile,
        loading = animatedEmojiLoading,
        sending = sending,
        progress = mediaDownloadProgress.takeIf { sending },
    )
}

/**
 * Whether the popup may offer to send [emoji] itself as a sticker. Unlike the
 * animation this needs nothing downloaded — the glyph is on the device — so it
 * turns only on the setting and on the field taking images at all.
 */
internal fun KeyboardUiState.canSendEmojiSticker(): Boolean =
    settings.emoji.sendAsSticker && acceptsRichMedia

/**
 * The id the service publishes while it draws [emoji] into a sticker, so the
 * row that started it can spin. Spelled in one place because both sides have
 * to agree on it.
 */
internal fun emojiStickerJobId(emoji: String): String = "emoji_sticker:$emoji"

/**
 * The animated-emoji block of the long-press popup: the animation itself,
 * looping, over a button that sends it as a GIF.
 *
 * The button only appears once the preview has, so there is never a control
 * that says it will send an animation nobody has seen yet. The credit line is
 * not decoration: the assets are Noto Animated Emoji, and CC BY 4.0 asks for
 * attribution wherever they are used.
 */
@Composable
private fun ColumnScope.AnimatedEmojiOffer(offer: AnimatedEmojiOffer, onSend: () -> Unit) {
    val loader = rememberMediaImageLoader()
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .size(96.dp)
            .align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) {
        if (offer.file != null) {
            AsyncImage(
                model = offer.file,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (offer.loading) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        }
    }
    if (offer.file == null) return
    PopupAction(
        icon = Icons.Outlined.PlayCircleOutline,
        label = stringResource(R.string.ime_emoji_send_animated),
        caption = stringResource(R.string.ime_emoji_animated_credit),
        busy = offer.sending,
        progress = offer.progress,
        onClick = onSend,
    )
}

/**
 * One tappable row of the long-press popup, with the same shape as the
 * favourite and remove rows above it. [busy] swaps the icon for a spinner,
 * which is what the send rows do while their file is being fetched or drawn —
 * the popup stays open through a send, so it has to show that something is
 * happening.
 *
 * [progress] fills that spinner in when there is a download behind it and its
 * size is known; a row that draws its file locally has nothing to count, and
 * keeps turning instead.
 */
@Composable
private fun PopupAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    caption: String? = null,
    busy: Boolean = false,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy && progress != null) {
            val fraction by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(durationMillis = 120, easing = LinearEasing),
                label = "animatedEmojiSendProgress",
            )
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
            )
        } else if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        } else {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Box(modifier = Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (caption != null) {
                Text(
                    caption,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * How wide the long-press popup gets. Wide enough for "Send animated emoji"
 * and for six tone swatches in a row, and fixed so every action row is the
 * same tappable width rather than the width of its own words.
 */
private val PopupActionWidth = 300.dp

/** Fitzpatrick swatches for the two-person tone selector: neutral + 🏻..🏿. */
private val TONE_SWATCHES = listOf(
    Color(0xFFFFCC4D), Color(0xFFF7DECE), Color(0xFFF3D2A2),
    Color(0xFFD5AB88), Color(0xFFAF7E57), Color(0xFF7C533E),
)

@Composable
private fun EmojiVariantPopup(
    base: String,
    display: String,
    name: String?,
    index: EmojiVariantIndex,
    genderVariants: List<String>,
    favourite: Boolean,
    animated: AnimatedEmojiOffer?,
    onAnimatedSend: () -> Unit,
    sticker: Boolean,
    stickerSending: Boolean,
    onStickerSend: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onFavourite: (String) -> Unit,
    onReorderFavourites: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            // Capped rather than wrapped: every row below fills this width, so
            // a tap anywhere along a row counts. Wrapping instead made each row
            // exactly as wide as its own label, and the empty space beside a
            // short one — most of the popup — did nothing.
            modifier = Modifier.widthIn(max = PopupActionWidth),
            shape = kb.popupShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.popupShapeKind, 8.dp),
        ) {
            // Roomy by design: these rows are the only way to favourite or
            // forget an emoji, and at the old 26dp height they were easy to
            // miss with the same finger that just long-pressed.
            Column(modifier = Modifier.padding(8.dp)) {
                if (name != null) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = LocalEmojiShaper.current.shape(display),
                            fontSize = 20.sp,
                            fontFamily = emojiFamilyFor(display),
                        )
                        Box(modifier = Modifier.width(10.dp))
                        // Long names wrap rather than stretch the popup: the
                        // whole thing is sized by its widest row, and a
                        // one-line "person with white cane facing right" would
                        // leave every row below it in empty space.
                        Text(
                            text = name.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.widthIn(max = 180.dp),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
                if (animated != null) {
                    AnimatedEmojiOffer(offer = animated, onSend = onAnimatedSend)
                }
                // Offered for every emoji, not only the animated ones: this is
                // the phone's own glyph, so there is always one to draw.
                if (sticker) {
                    PopupAction(
                        icon = Icons.Outlined.PhotoSizeSelectActual,
                        label = stringResource(R.string.ime_emoji_send_sticker),
                        busy = stickerSending,
                        onClick = onStickerSend,
                    )
                }
                // Favourite pins this emoji to the top of the history tab
                // and the favourites row.
                // Re-seeded when the real flag changes, not only when the
                // emoji does: keyed on display alone the local mirror went
                // stale the moment the store echoed back, so reopening the
                // popup could show an unstarred emoji as favourited.
                var starred by remember(display, favourite) { mutableStateOf(favourite) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            starred = !starred
                            onFavourite(display)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Box(modifier = Modifier.width(10.dp))
                    Text(
                        stringResource(
                            if (starred) R.string.ime_emoji_favourited else R.string.ime_emoji_favourite,
                        ),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // Only favourites can be reordered, and only when there are at
                // least two (the caller passes null otherwise).
                if (favourite && onReorderFavourites != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onReorderFavourites() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Box(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ime_emoji_reorder_favourites),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // History cells only: drop this emoji from recents/most-used.
                if (onRemove != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRemove() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Box(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.ime_emoji_remove_from_recents),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                val members = remember(base, genderVariants) { listOf(base) + genderVariants }
                if (index.hasDualTones(base) || genderVariants.any { index.hasDualTones(it) }) {
                    DualTonePicker(members = members, index = index, onPick = onPick)
                } else {
                    // One row per gender/role member, six cells when toned;
                    // toneless combination groups (families) just flow.
                    val cells = remember(members) { members.flatMap { index.popupVariants(it) } }
                    // A lone cell is the emoji itself — already shown in the
                    // name header, and a grid of one is just a second way to
                    // commit what a plain tap commits.
                    if (cells.size > 1 || name == null) Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        for (row in cells.chunked(6)) {
                            Row {
                                for (variant in row) {
                                    Text(
                                        text = LocalEmojiShaper.current.shape(variant),
                                        modifier = Modifier
                                            .clickable { onPick(variant) }
                                            .padding(horizontal = 9.dp, vertical = 9.dp),
                                        fontSize = 26.sp,
                                        fontFamily = emojiFamilyFor(variant),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fixed row height inside [FavouritesReorderPopup], so drags map to slots. */
private val FavouriteReorderRowHeight = 48.dp

/**
 * A modal drag-to-reorder list for the favourites, opened from a favourited
 * emoji's long-press popup. Rendered as a [Popup] over the whole IME window
 * (a scrim swallows stray taps and doubles as tap-to-dismiss) rather than a
 * Compose Dialog, which needs a window token the IME does not hand out.
 *
 * The drag mechanic mirrors the settings-side ReorderDialog: each row carries
 * a handle, and dragging one past the next row's height swaps the two so the
 * item tracks the finger. The working copy only reaches the caller through
 * [onConfirm]; cancelling leaves the stored order alone.
 */
@Composable
private fun FavouritesReorderPopup(
    favourites: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    var working by remember { mutableStateOf(favourites) }
    // -1 = nothing being dragged.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { FavouriteReorderRowHeight.toPx() }

    Popup(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                // Tap the scrim to dismiss; the surface below swallows its own.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = kb.menuShape(),
                color = kb.popup,
                border = kb.popupSurfaceBorder(),
                shadowElevation = elevationFor(kb.menuShapeKind, 8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.92f)
                    // Don't let taps inside the card fall through to the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.ime_emoji_reorder_favourites),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        stringResource(R.string.ime_emoji_reorder_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        working.forEachIndexed { index, emoji ->
                            val dragging = index == dragIndex
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FavouriteReorderRowHeight)
                                    // The dragged row rides above its neighbours.
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (dragging) dragOffset else 0f
                                    },
                            ) {
                                Text(
                                    stringResource(R.string.ime_emoji_reorder_index, index + 1),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(28.dp),
                                )
                                Text(
                                    text = LocalEmojiShaper.current.shape(emoji),
                                    fontSize = 24.sp,
                                    fontFamily = emojiFamilyFor(emoji),
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = stringResource(
                                        R.string.ime_emoji_reorder_handle_desc, emoji,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(28.dp)
                                        // Keyed on Unit so a swap mid-drag never
                                        // restarts the gesture: slot `index` is
                                        // fixed for the life of the row, only the
                                        // item in it moves. `dragIndex` is live.
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    dragIndex = index
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    dragIndex = -1
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    dragIndex = -1
                                                    dragOffset = 0f
                                                },
                                            ) { change, drag ->
                                                change.consume()
                                                dragOffset += drag.y
                                                val from = dragIndex
                                                val to = from + (dragOffset / rowPx).roundToInt()
                                                if (from >= 0 && to != from && to in working.indices) {
                                                    working = working.toMutableList().apply {
                                                        add(to, removeAt(from))
                                                    }
                                                    dragIndex = to
                                                    // Keep the offset relative to
                                                    // the row's new home, or the
                                                    // item would jump a full row.
                                                    dragOffset -= (to - from) * rowPx
                                                }
                                            }
                                        },
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(CommonR.string.common_cancel))
                        }
                        TextButton(onClick = { onConfirm(working) }) {
                            Text(stringResource(CommonR.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gboard-style two-slot skin-tone selector for emojis where each person
 * has an independent tone (🤝, couples, holding hands…). The top row picks
 * the gender/role combination; the two swatch rows pick each person's
 * tone; tapping the live preview commits the exact RGI sequence.
 */
@Composable
private fun DualTonePicker(
    members: List<String>,
    index: EmojiVariantIndex,
    onPick: (String) -> Unit,
) {
    var member by remember { mutableStateOf(members.first()) }
    var first by remember { mutableIntStateOf(0) }
    var second by remember { mutableIntStateOf(0) }
    // Not every combination is RGI (a toned person can't shake a neutral
    // hand), so a pick on one side seeds the other side too.
    val preview = index.tonedPair(member, first, second) ?: member

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (members.size > 1) {
            Row {
                for (candidate in members) {
                    Text(
                        text = LocalEmojiShaper.current.shape(candidate),
                        modifier = Modifier
                            .background(
                                if (candidate == member) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                } else {
                                    Color.Transparent
                                },
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { member = candidate }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        fontSize = 22.sp,
                        fontFamily = emojiFamilyFor(candidate),
                    )
                }
            }
        }
        Text(
            text = LocalEmojiShaper.current.shape(preview),
            modifier = Modifier
                .clickable { onPick(preview) }
                .padding(6.dp),
            fontSize = 34.sp,
            fontFamily = emojiFamilyFor(preview),
        )
        for (slot in 0..1) {
            Row(
                modifier = Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tone in 0..5) {
                    val selected = tone == if (slot == 0) first else second
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(26.dp)
                            .background(TONE_SWATCHES[tone], CircleShape)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                if (tone == 0) {
                                    first = 0
                                    second = 0
                                } else if (slot == 0) {
                                    first = tone
                                    if (second == 0) second = tone
                                } else {
                                    second = tone
                                    if (first == 0) first = tone
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * What a press and hold on a tool reaches, bundled into one [KeyboardScreen]
 * parameter for the reason [SnippetPanelCallbacks] is: its caller sits against
 * the JVM's 64K method-size ceiling, where every added parameter costs bytecode.
 * Selection mode needed a second callback, and the bundle pays for both.
 */
data class ToolHoldCallbacks(
    /** A stationary hold with nothing else bound: open that tool's settings page. */
    val onSettings: (ToolbarTool) -> Unit = {},
    /**
     * A stationary hold on a tool the user bound another tool to: run the bound
     * one. Its own callback rather than the tap dispatcher because the bound
     * tool need not be on the toolbar, and the tap path refuses those.
     */
    val onHoldAction: (ToolbarTool) -> Unit = {},
    /**
     * The Selection mode tool held down (true) and let go (false). Always paired:
     * every path out of the gesture releases what it armed.
     */
    val onSelectionHold: (Boolean) -> Unit = {},
)

// ---- snippets panel ----

/**
 * The snippets panel's service callbacks, bundled into one [KeyboardScreen]
 * parameter for the reason [ConverterCallbacks] is: its caller sits against the
 * JVM's 64K method-size ceiling, where every added parameter costs bytecode.
 * Folders needed two more callbacks, and the bundle pays for all three.
 */
data class SnippetPanelCallbacks(
    /** A snippet was tapped: insert it. */
    val onSnippet: (Snippet) -> Unit = {},
    /** Drill into a folder, or back out of one with null. */
    val onFolderOpen: (Long?) -> Unit = {},
    /** Long-press on a folder: arm or disarm its triggers, and persist that. */
    val onFolderToggle: (Long) -> Unit = {},
)

/**
 * The snippets panel, one level of folders deep.
 *
 * With no folders it is the flat grid it has always been. With folders it opens
 * on them — folders first, then whatever is filed in none of them — and a tap
 * goes inside. Which level is showing lives on [KeyboardUiState], not in this
 * composable, so that back leaves the folder before it closes the panel and the
 * hardware focus ring counts the right things.
 *
 * A long press toggles a folder's triggers. It is the one folder edit worth
 * having here rather than in settings: the point of switching a set of work
 * replies off is that you are mid-message when you realise you want them off.
 */
@Composable
private fun SnippetsPanel(
    state: KeyboardUiState,
    callbacks: SnippetPanelCallbacks,
    onOpenSettings: () -> Unit,
) {
    // Inside a [FullBleedTool], which owns the height — fill what it gives.
    Column(modifier = Modifier.fillMaxSize()) {
        val folders = state.snippetFolders
        val open = state.openSnippetFolder()
        // Only at the top level, and only when there are folders to draw: inside
        // a folder the tiles are all snippets, as they were before folders.
        val tiles = if (open == null) folders else emptyList()
        val shown = state.snippetsShown()
        if (shown.isEmpty() && tiles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(
                        if (open == null) {
                            R.string.ime_snippets_empty
                        } else {
                            R.string.ime_snippets_folder_empty
                        },
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ToolPanelChip(
                    stringResource(R.string.ime_snippets_settings_desc),
                    selected = true,
                    onClick = onOpenSettings,
                )
            }
            return@Column
        }
        // The gear rides in the full-bleed header now, so this is only the
        // reminder that a snippet's text can carry variables.
        Text(
            stringResource(R.string.ime_snippets_variables),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
        )
        // One region over both kinds of tile, folders first, in the order the
        // grid lays them out — so Tab walks the panel the way the eye does.
        PanelFocusTarget(
            panel = PanelMode.SNIPPETS,
            count = tiles.size + shown.size,
            columns = 2,
            onActivate = { index ->
                val folder = tiles.getOrNull(index)
                if (folder != null) {
                    callbacks.onFolderOpen(folder.id)
                } else {
                    shown.getOrNull(index - tiles.size)?.let(callbacks.onSnippet)
                }
            },
        )
        val gridState = rememberLazyGridState()
        val focused = state.focusedIndex()
        ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Keyed apart from the snippets: the two id spaces are independent,
            // so a folder and a snippet can both be 1 and the grid would reuse
            // one's slot for the other on the way into a folder.
            itemsIndexed(tiles, key = { _, folder -> "folder-${folder.id}" }) { index, folder ->
                SnippetFolderTile(
                    folder = folder,
                    count = state.snippets.count { it.folderId == folder.id },
                    focused = index == focused,
                    modifier = snippetTileMotion(),
                    onOpen = { callbacks.onFolderOpen(folder.id) },
                    onToggle = { callbacks.onFolderToggle(folder.id) },
                )
            }
            itemsIndexed(shown, key = { _, snippet -> snippet.id }) { index, snippet ->
                SnippetTile(
                    snippet,
                    focused = tiles.size + index == focused,
                    modifier = snippetTileMotion(),
                ) { callbacks.onSnippet(snippet) }
            }
        }
    }
}

/**
 * How a tile in the snippets grid moves when the list under it changes — the
 * same spring the panel has always used, lifted out so both kinds of tile get
 * it without either one having to be a lazy-grid item scope itself.
 */
private fun LazyGridItemScope.snippetTileMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(160),
        placementSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        ),
        fadeOutSpec = tween(140),
    )

/** One folder in the snippets panel: open it, or hold to arm/disarm it. */
@Composable
private fun SnippetFolderTile(
    folder: SnippetFolder,
    count: Int,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val cardShape = kb.cardShape()
    val haptic = LocalHapticFeedback.current
    val description = stringResource(R.string.ime_snippets_folder_desc, folder.name)
    Row(
        modifier = modifier
            .focusRing(focused, cardShape)
            .clip(cardShape)
            .background(kb.chip)
            .chipBorder(kb, cardShape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptic()
                    onToggle()
                },
            )
            .semantics { contentDescription = description }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A disarmed folder is dimmed rather than hidden or recoloured: it is
        // still a folder you can open and still inserts on a tap, and the badge
        // below says the one thing that changed.
        val fade = if (folder.enabled) 1f else 0.55f
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = fade),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = fade),
            )
            Text(
                text = if (folder.enabled) {
                    pluralStringResource(R.plurals.ime_snippets_folder_count, count, count)
                } else {
                    stringResource(R.string.ime_snippets_folder_off)
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One snippet card: its name, its text, and the rule that fires it. */
@Composable
private fun SnippetTile(
    snippet: Snippet,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val cardShape = kb.cardShape()
    Column(
        modifier = modifier
            .focusRing(focused, cardShape)
            .clip(cardShape)
            .background(kb.chip)
            .chipBorder(kb, cardShape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(
            text = snippet.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = snippet.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // A pattern snippet is listed with the rule that fires it, because
        // nobody remembers the patterns they wrote weeks ago — and a tap
        // inserts the text with its blanks empty, which only makes sense next
        // to the rule.
        val pattern = snippet.triggerPattern
        if (!pattern.isNullOrBlank() && snippet.trigger.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.ime_snippets_pattern_label, pattern),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- clipboard panel ----

@Composable
private fun ClipboardPanel(
    state: KeyboardUiState,
    onClipboardItem: (ClipItem) -> Unit,
    onClipboardSticker: (ClipItem) -> Unit,
    onClipboardPin: (ClipItem) -> Unit,
    onClipboardDelete: (ClipItem) -> Unit,
    onClipboardSearchToggle: () -> Unit,
    onClipboardEntity: (ClipEntity) -> Unit,
    onKey: (Key) -> Unit,
    onClose: () -> Unit,
    // Inside a [FullBleedTool], which owns the height — the content fills it.
    fullBleed: Boolean = false,
) {
    // Searching hands the key rows back (they are how the query gets typed), so
    // the panel shrinks to its search field plus a couple of result rows and the
    // bottom control row stands down — the real keys are right there.
    val searching = state.clipboardSearchActive
    val showBottomRow = state.settings.clipboard.bottomRow && !searching
    // The control row is carved out of the panel's own height (same size as the
    // emoji panel's), so the total stays exactly the key area's height and the
    // keyboard never grows when the row is on.
    val barHeight = state.settings.keyHeightDp.dp + keyGapV(state.settings) * 2
    // While searching the toolbar row is hidden too (see KeyboardBody), so the
    // panel absorbs its height the way the emoji panel's search mode does.
    val panelHeight = if (searching) {
        ClipboardSearchHeight + topBarHeight(state.settings)
    } else {
        keyRowsHeight(state)
    }
    val contentHeight = panelHeight - if (showBottomRow) barHeight else 0.dp
    Column(modifier = if (fullBleed) Modifier.fillMaxSize() else Modifier) {
        ClipboardPanelContent(
            state, onClipboardItem, onClipboardSticker, onClipboardPin, onClipboardDelete,
            onClipboardSearchToggle = onClipboardSearchToggle,
            onClipboardEntity = onClipboardEntity,
            modifier = if (fullBleed) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth().height(contentHeight)
            },
        )
        if (showBottomRow) {
            EmojiBottomBar(state = state, onKey = onKey, onClose = onClose)
        }
    }
}

/** Panel height while the clipboard search bar is capturing the keys. */
private val ClipboardSearchHeight = 132.dp

/**
 * Search pill at the top of the clipboard panel. Tapping it routes the keys
 * into [KeyboardUiState.clipboardQuery] (like emoji/dictionary search) so the
 * IME can filter its own history without a focusable text field.
 */
@Composable
private fun ClipboardSearchField(
    state: KeyboardUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state.clipboardSearchActive
    val kb = LocalKbTheme.current
    val fieldShape = kb.cardShape()
    Row(
        modifier = modifier
            .clip(fieldShape)
            .background(kb.chip)
            .chipBorder(kb, fieldShape)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        SearchQueryText(
            query = state.clipboardQuery,
            placeholder = stringResource(R.string.ime_clipboard_search_hint),
            active = active,
            textColor = MaterialTheme.colorScheme.onSurface,
            placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        // Only up while searching (typing is the only way to fill the query,
        // and closing clears it). Clears the filter and hands the keys back.
        if (active) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_clipboard_search_clear_desc),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
                    .clickable { onToggle() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClipboardPanelContent(
    state: KeyboardUiState,
    onClipboardItem: (ClipItem) -> Unit,
    onClipboardSticker: (ClipItem) -> Unit,
    onClipboardPin: (ClipItem) -> Unit,
    onClipboardDelete: (ClipItem) -> Unit,
    onClipboardSearchToggle: () -> Unit,
    onClipboardEntity: (ClipEntity) -> Unit,
    modifier: Modifier,
) {
    // The search bar is only offered once there is history to filter and the
    // feature is on; an empty panel just shows the placeholder.
    val showSearch = state.settings.clipboard.search && state.clipboardItems.isNotEmpty()
    if (state.clipboardItems.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.ime_clipboard_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val query = state.clipboardQuery.trim()
    val shownItems = if (query.isEmpty()) {
        state.clipboardItems
    } else {
        state.clipboardItems.filter { it.matchesQuery(query) }
    }
    // Scanning every clip with three regexes is not free, so it happens once per
    // history change rather than on every recomposition of the panel.
    val phoneFormats = state.settings.clipboard.phoneFormats
    val phoneMasks = remember(phoneFormats) { PhoneFormats.parseAll(phoneFormats) }
    val allEntities = if (state.settings.clipboard.detectEntities) {
        remember(state.clipboardItems, phoneMasks) {
            ClipEntities.entitiesIn(state.clipboardItems, phoneMasks)
        }
    } else {
        emptyList()
    }
    // While searching the panel is only a couple of rows tall — the keys have
    // taken the rest — so the fragment strip stands down rather than eating one.
    val entities = if (state.clipboardSearchActive) {
        emptyList()
    } else {
        allEntities.filter { query.isEmpty() || it.value.contains(query, ignoreCase = true) }
    }
    // Published even when empty: a stale count left behind by the last refresh
    // would let Tab land on a chip that is no longer drawn.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = entities.size,
        columns = entities.size.coerceAtLeast(1),
        onActivate = { index -> entities.getOrNull(index)?.let(onClipboardEntity) },
    )
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        count = shownItems.size,
        columns = 2,
        onActivate = { index -> shownItems.getOrNull(index)?.let(onClipboardItem) },
    )
    if (showSearch) {
        // The search pill is one "item": Tab reaches it, Enter toggles it.
        PanelFocusTarget(
            panel = PanelMode.CLIPBOARD,
            region = FocusRegion.SEARCH,
            count = 1,
            columns = 1,
            onActivate = { onClipboardSearchToggle() },
        )
    }
    val focused = state.focusedIndex()
    val gridState = rememberLazyStaggeredGridState()
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    Column(modifier = modifier) {
        if (showSearch) {
            ClipboardSearchField(
                state = state,
                onToggle = onClipboardSearchToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .focusRing(
                        state.focusedIndex(FocusRegion.SEARCH) == 0,
                        RoundedCornerShape(18.dp),
                    ),
            )
        }
        if (entities.isNotEmpty()) {
            ClipEntityStrip(
                entities = entities,
                focused = state.focusedIndex(FocusRegion.CHIPS),
                onPaste = onClipboardEntity,
            )
        }
        if (shownItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.ime_clipboard_no_match, query),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        // Staggered, not a fixed grid: a fixed grid gives every cell in a row
        // the height of the tallest one, so a single screenshot left a
        // card-sized hole beside it and short clips floated in whitespace.
        // Here each column packs independently — a tall image sits next to two
        // or three stacked text clips and the panel fills edge to edge.
        LazyVerticalStaggeredGrid(
            state = gridState,
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalItemSpacing = 6.dp,
        ) {
            staggeredItemsIndexed(shownItems, key = { _, item -> item.id }) { index, item ->
            // Deleting fades the card out and slides the survivors up into the
            // gap; pinning re-sorts the list, so the card glides to the front
            // instead of teleporting there.
            SwipeToDeleteCard(
                onDelete = { onClipboardDelete(item) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(160),
                    placementSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                    fadeOutSpec = tween(140),
                ),
            ) {
                var showInfo by remember { mutableStateOf(false) }
                val kb = LocalKbTheme.current
                val cardShape = kb.cardShape()
                Column(
                    modifier = Modifier
                        .clip(cardShape)
                        .background(kb.chip)
                        .chipBorder(kb, cardShape)
                        .focusRing(index == focused, cardShape)
                        .pointerInput(item.id) {
                            detectTapGestures(
                                onTap = { onClipboardItem(item) },
                                onLongPress = { showInfo = true },
                            )
                        }
                        // An image card insets less: the picture is the content,
                        // and a 10dp frame around it was pure dead space.
                        .padding(
                            if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) 5.dp
                            else 10.dp,
                        ),
                ) {
                    if (showInfo) {
                        ClipInfoPopup(
                            item,
                            onSendSticker = if (item.kind == ClipKind.IMAGE) {
                                { onClipboardSticker(item); showInfo = false }
                            } else null,
                            onDismiss = { showInfo = false },
                        )
                    }
                    when {
                        // A masked secret outranks every other body: the point
                        // is that its content is not on screen, and a link card
                        // or a preview would put it there.
                        item.sensitive && item.kind.isTextual -> ClipSensitiveBody(item)
                        item.kind == ClipKind.IMAGE -> ClipThumbnail(item)
                        item.kind == ClipKind.VIDEO -> ClipVideoBody(item)
                        item.kind == ClipKind.FILE || item.kind == ClipKind.FOLDER ->
                            ClipFileBody(item)
                        item.kind == ClipKind.LINK -> ClipLinkBody(item)
                        // Longer clips run to six lines rather than three now
                        // that a taller card costs its neighbour nothing — the
                        // other column packs its own cards independently.
                        else -> Text(
                            text = item.text,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (item.kind == ClipKind.HTML) {
                            Text(
                                stringResource(R.string.ime_clip_type_rich_text),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        ClipActionCircle(
                            icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            description = stringResource(
                                if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin,
                            ),
                            tint = if (item.pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { onClipboardPin(item) }
                        ClipActionCircle(
                            icon = Icons.Outlined.Delete,
                            description = stringResource(CommonR.string.common_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { onClipboardDelete(item) }
                    }
                }
            }
            }
        }
    }
}

/**
 * The strip of fragments — one-time codes, phone numbers, links — pulled out of
 * the clips below it.
 *
 * The visual language carries the whole idea: a history card is a solid filled
 * rectangle holding something the clipboard really contains, while these are
 * dashed cut-outs under a caption that says where they came from. Nothing here
 * is an entry of its own, and long-pressing one shows it highlighted inside the
 * clip it was lifted from, which is the same claim made a second way.
 */
@Composable
private fun ClipEntityStrip(
    entities: List<ClipEntity>,
    focused: Int?,
    onPaste: (ClipEntity) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ContentCut,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.ime_clip_entity_hint),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(entities, key = { _, entity -> entity.key }) { index, entity ->
                ClipEntityChip(entity, focused = index == focused, onPaste = onPaste)
            }
        }
    }
}

/** One dashed fragment chip: kind tag above the text that will be pasted. */
@Composable
private fun ClipEntityChip(
    entity: ClipEntity,
    focused: Boolean,
    onPaste: (ClipEntity) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    var showSource by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .focusRing(focused, shape)
            .dashedOutline(accent.copy(alpha = 0.6f), 10.dp)
            .pointerInput(entity.key) {
                detectTapGestures(
                    onTap = { onPaste(entity) },
                    onLongPress = { showSource = true },
                )
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSource) {
            ClipEntitySourcePopup(entity, onDismiss = { showSource = false })
        }
        Icon(
            when (entity.kind) {
                ClipEntityKind.OTP -> Icons.Outlined.Password
                ClipEntityKind.PHONE -> Icons.Outlined.Phone
                ClipEntityKind.URL -> Icons.Outlined.Link
            },
            contentDescription = stringResource(entity.kind.labelRes),
            modifier = Modifier.size(15.dp),
            tint = accent,
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                stringResource(entity.kind.tagRes),
                fontSize = 8.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.85f),
                maxLines = 1,
            )
            Text(
                entity.value,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 170.dp),
            )
        }
    }
}

/**
 * Press-and-hold proof that a chip is a cut-out: the clip it came from, shown
 * with the fragment highlighted in place. Windowed around the fragment so a
 * long clip does not turn the popup into a wall of text.
 */
@Composable
private fun ClipEntitySourcePopup(entity: ClipEntity, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    val source = entity.sourceText
    val from = (entity.start - SOURCE_CONTEXT_CHARS).coerceAtLeast(0)
    val to = (entity.end + SOURCE_CONTEXT_CHARS).coerceAtMost(source.length)
    val shown = remember(entity.key, source) {
        buildAnnotatedString {
            if (from > 0) append("…")
            append(source.substring(from, entity.start))
            withStyle(
                SpanStyle(
                    background = kb.accent.copy(alpha = 0.28f),
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                append(source.substring(entity.start, entity.end))
            }
            append(source.substring(entity.end, to))
            if (to < source.length) append("…")
        }
    }
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 260.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stringResource(
                        R.string.ime_clip_entity_popup_title,
                        stringResource(entity.kind.labelRes),
                    ),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = kb.popupText.copy(alpha = 0.6f),
                )
                Text(
                    shown,
                    fontSize = 11.sp,
                    color = kb.popupText,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Characters of surrounding clip shown either side of a fragment. */
private const val SOURCE_CONTEXT_CHARS = 60

/**
 * Dashed rounded outline with no fill. Deliberately unlike every filled card in
 * the panel — it is the signal that what it wraps was cut out of something else.
 */
private fun Modifier.dashedOutline(color: Color, radius: Dp, width: Dp = 1.dp) = drawBehind {
    val line = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(line / 2, line / 2),
        size = Size(size.width - line, size.height - line),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = line,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
        ),
    )
}

/** Small round action button on a clipboard card. */
@Composable
private fun ClipActionCircle(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = tint,
        )
    }
}

/**
 * Press-and-hold details for a clip: when it was copied (relative + exact),
 * which app it came from (when source tracking is on), its type, and a size or
 * length. Anchored above the card; dismissed by tapping elsewhere.
 */
@Composable
private fun ClipInfoPopup(
    item: ClipItem,
    onSendSticker: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val now = System.currentTimeMillis()
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
        item.timestamp, now, android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val exact = remember(item.timestamp) {
        java.text.SimpleDateFormat("MMM d, yyyy · h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(item.timestamp))
    }
    val typeLabel = stringResource(
        when (item.kind) {
            ClipKind.TEXT -> R.string.ime_clip_type_text
            ClipKind.HTML -> R.string.ime_clip_type_rich_text
            ClipKind.LINK -> R.string.ime_clip_type_link
            ClipKind.IMAGE -> R.string.ime_clip_type_image
            ClipKind.FILE -> R.string.ime_clip_type_file
            ClipKind.FOLDER -> R.string.ime_clip_type_folder
            ClipKind.VIDEO -> R.string.ime_clip_type_video
        },
    )
    val characterCount = pluralStringResource(
        R.plurals.ime_clip_character_count, item.text.length, item.text.length,
    )
    val sizeLabel = when (item.kind) {
        ClipKind.IMAGE -> item.mimeType.substringAfterLast('/').takeIf { it.isNotBlank() }?.uppercase()
        ClipKind.FILE -> formatFileSize(item.fileSize)
        ClipKind.FOLDER -> null
        ClipKind.VIDEO -> listOfNotNull(
            formatDuration(item.durationMs),
            formatFileSize(item.fileSize),
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        else -> characterCount
    }
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 240.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ClipInfoRow(
                    stringResource(R.string.ime_clip_info_copied),
                    "$relative\n$exact",
                    kb.popupText,
                )
                item.sourceApp?.let {
                    ClipInfoRow(stringResource(R.string.ime_clip_info_from), it, kb.popupText)
                }
                ClipInfoRow(stringResource(R.string.ime_clip_info_type), typeLabel, kb.popupText)
                sizeLabel?.let {
                    ClipInfoRow(stringResource(R.string.ime_clip_info_size), it, kb.popupText)
                }
                if (onSendSticker != null) {
                    TextButton(
                        onClick = onSendSticker,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.EmojiEmotions, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ime_clip_send_as_sticker))
                    }
                }
                if (item.sensitive) {
                    ClipInfoRow(
                        stringResource(R.string.ime_clip_info_private),
                        stringResource(R.string.ime_clip_info_private_detail),
                        kb.popupText,
                    )
                }
            }
        }
    }
}

/** One "Label: value" line in the clipboard info popup. */
@Composable
private fun ClipInfoRow(label: String, value: String, textColor: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor.copy(alpha = 0.6f),
            modifier = Modifier.width(44.dp),
        )
        Text(
            value,
            fontSize = 11.sp,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Horizontal swipe-to-dismiss for a grid card: the card follows the finger,
 * fades as it travels, and a release past 40% of its width deletes it —
 * otherwise it springs back. Vertical scrolling is untouched (only
 * horizontal drags are claimed).
 */
@Composable
private fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .onGloballyPositioned { width = it.size.width }
            .graphicsLayer {
                translationX = offset.value
                alpha = if (width == 0) 1f
                else (1f - abs(offset.value) / width).coerceIn(0.2f, 1f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + delta) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val threshold = width * 0.4f
                            if (width > 0 && abs(offset.value) > threshold) {
                                // Finish the slide off-screen, then delete.
                                offset.animateTo(
                                    if (offset.value > 0) width.toFloat() else -width.toFloat(),
                                    tween(120),
                                )
                                onDelete()
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f) }
                    },
                )
            },
    ) { content() }
}

/**
 * A copied file or folder: type icon, name, and either the file size or a
 * "Folder" label. Folders are marked plainly because tapping one can't attach
 * it anywhere — it types the name instead.
 */
@Composable
private fun ClipFileBody(item: ClipItem) {
    val isFolder = item.kind == ClipKind.FOLDER
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (isFolder) Icons.Outlined.Folder else fileIconFor(item.mimeType),
            contentDescription = stringResource(
                if (isFolder) R.string.ime_clip_type_folder else R.string.ime_clip_type_file,
            ),
            modifier = Modifier
                .size(28.dp)
                .padding(end = 6.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = item.fileName.orEmpty().ifBlank { item.text },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isFolder) stringResource(R.string.ime_clip_type_folder) else listOfNotNull(
                    formatFileSize(item.fileSize),
                    item.mimeType.substringAfterLast('/').takeIf { it.isNotBlank() }?.uppercase(),
                ).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A clip holding a secret: bullets instead of the text, with a lock and a
 * character count so the card still says *something* about what it is.
 *
 * Deliberately not revealable in place. Anyone who can see the panel can see
 * whatever a reveal button would show, and the card is already one tap from
 * pasting the real thing where it belongs — a peek affordance would add a way
 * to expose the password without adding a way to use it.
 */
@Composable
private fun ClipSensitiveBody(item: ClipItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = stringResource(R.string.ime_clip_hidden_desc),
            modifier = Modifier
                .size(22.dp)
                .padding(end = 6.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = "•".repeat(item.text.length.coerceIn(6, 16)),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                fontSize = 15.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.ime_clip_hidden_summary, item.text.length, item.text.length,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A copied video: a frame from it under a play badge, with the length and file
 * size beneath. The frame is pulled straight from the source URI — the bytes
 * belong to the app that did the copying and are never duplicated here — so it
 * simply falls back to a plain file row when the grant has lapsed or the
 * container will not decode.
 */
@Composable
private fun ClipVideoBody(item: ClipItem) {
    val context = LocalContext.current
    val frame by produceState<ImageBitmap?>(initialValue = null, item.uriString) {
        value = withContext(Dispatchers.IO) {
            val uri = item.uriString?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                ?: return@withContext null
            runCatching {
                // Not `use`: MediaMetadataRetriever only became AutoCloseable in
                // API 29, and this app runs back to 24.
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(0)?.asImageBitmap()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val caption = listOfNotNull(
        formatDuration(item.durationMs),
        formatFileSize(item.fileSize),
    ).joinToString(" · ")

    val thumbnail = frame
    if (thumbnail == null) {
        ClipFileBody(item)
        return
    }
    Column {
        Box(contentAlignment = Alignment.Center) {
            Image(
                bitmap = thumbnail,
                contentDescription = stringResource(R.string.ime_clip_video_desc),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        (thumbnail.width.toFloat() / thumbnail.height.coerceAtLeast(1))
                            .coerceIn(MIN_THUMBNAIL_RATIO, MAX_THUMBNAIL_RATIO),
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .clip(shape),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.White,
                )
            }
        }
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/** `1:04`, `12:03:41` — or null when the length could not be read. */
private fun formatDuration(millis: Long): String? {
    if (millis <= 0) return null
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/**
 * A copied link, tinted and underlined so it reads as one. When link previews
 * are on and the fetch found something, the page title and description replace
 * the raw URL, which drops to a host line underneath.
 */
@Composable
private fun ClipLinkBody(item: ClipItem) {
    val preview = item.linkPreview?.takeIf { !it.failed && !it.isEmpty }
    val linkColor = MaterialTheme.colorScheme.primary
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Link,
                contentDescription = stringResource(R.string.ime_clip_type_link),
                modifier = Modifier
                    .size(22.dp)
                    .padding(end = 4.dp),
                tint = linkColor,
            )
            Text(
                text = preview?.title?.takeIf { it.isNotBlank() } ?: item.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = if (preview != null) FontWeight.Medium else FontWeight.Normal,
                color = if (preview != null) MaterialTheme.colorScheme.onSurface else linkColor,
                textDecoration = if (preview != null) null else TextDecoration.Underline,
            )
        }
        if (preview != null && preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Title/description replace the raw URL above, so show what actually
        // gets pasted here — otherwise the real clip text is never visible.
        if (preview != null) {
            Text(
                text = item.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val host = ClipLinks.host(ClipLinks.asUrl(item.text) ?: item.text)
        if (host.isNotBlank()) {
            Text(
                text = preview?.siteName?.takeIf { it.isNotBlank() } ?: host,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = linkColor,
            )
        }
    }
}

private fun fileIconFor(mimeType: String) = when {
    mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
    mimeType.startsWith("video/") -> Icons.Outlined.VideoFile
    mimeType.startsWith("image/") -> Icons.Outlined.Image
    mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    mimeType.startsWith("text/") -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Human file size, or null when the provider didn't report one. */
private fun formatFileSize(bytes: Long): String? {
    if (bytes < 0) return null
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10) "${value.toInt()} ${units[unit]}"
    else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** Decodes a downsampled preview of an image clip off the main thread. */
@Composable
private fun ClipThumbnail(item: ClipItem) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.imagePath) {
        value = withContext(Dispatchers.IO) {
            val path = item.imagePath ?: return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= THUMBNAIL_TARGET_PX &&
                    bounds.outHeight / (sample * 2) >= THUMBNAIL_TARGET_PX
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    bitmap?.let {
        // The card takes the image's own aspect ratio, so there is no
        // letterboxing to leave dead space beside it — the staggered grid just
        // makes this cell taller or shorter and packs the other column around
        // it. Only the extremes are clamped (a panorama or a full-page
        // screenshot would otherwise be a sliver or swallow the panel), and
        // those are the one case that crops instead of fits.
        val natural = it.width.toFloat() / it.height.coerceAtLeast(1)
        val ratio = natural.coerceIn(MIN_THUMBNAIL_RATIO, MAX_THUMBNAIL_RATIO)
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.ime_clip_chip_image),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .clip(shape),
            contentScale = if (ratio == natural) ContentScale.Fit else ContentScale.Crop,
        )
    } ?: Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
    )
}

private const val THUMBNAIL_TARGET_PX = 256

/** Widest and tallest an image card may get before it crops instead. */
private const val MIN_THUMBNAIL_RATIO = 0.62f
private const val MAX_THUMBNAIL_RATIO = 2.2f
