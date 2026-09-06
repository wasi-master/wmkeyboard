package com.wasimaster.wmkeyboard.ime

import androidx.annotation.StringRes
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import android.content.ClipDescription
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.graphics.createBitmap
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.app.CalendarPermissionActivity
import com.wasimaster.wmkeyboard.app.CameraPermissionActivity
import com.wasimaster.wmkeyboard.app.DocScanActivity
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.app.MicPermissionActivity
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.SpecialAccessActivity
import com.wasimaster.wmkeyboard.app.StoragePermissionActivity
import com.wasimaster.wmkeyboard.core.media.GallerySaver
import com.wasimaster.wmkeyboard.core.media.MediaMime
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import android.provider.DocumentsContract
import android.provider.Settings
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipLinks
import com.wasimaster.wmkeyboard.core.clipboard.ClipSensitivity
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.settings.AutoThemeTrigger
import com.wasimaster.wmkeyboard.core.settings.ManualModeDuration
import com.wasimaster.wmkeyboard.core.settings.CopiedCodeChip
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.settings.activeThemeSpec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.tools.SolarCalculator
import com.wasimaster.wmkeyboard.core.emoji.AnimatedEmoji
import com.wasimaster.wmkeyboard.core.emoji.EmojiCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPack
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.emoji.EmojiFontShaping
import com.wasimaster.wmkeyboard.core.emoji.EmojiRenderCheck
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearch
import com.wasimaster.wmkeyboard.core.emoji.EmojiShortcodes
import com.wasimaster.wmkeyboard.core.emoji.EmojiSuggester
import com.wasimaster.wmkeyboard.core.emoji.EmojiTriggers
import com.wasimaster.wmkeyboard.core.emoji.EmojiUsage
import com.wasimaster.wmkeyboard.core.emoji.EmojiVariantIndex
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPhase
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundRole
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingRecognizerCache
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.wasimaster.wmkeyboard.core.prediction.Apostrophes
import com.wasimaster.wmkeyboard.core.input.BrailleChord
import com.wasimaster.wmkeyboard.core.input.BrailleGrade1
import com.wasimaster.wmkeyboard.core.input.DeadKeys
import com.wasimaster.wmkeyboard.core.input.MorseInput
import com.wasimaster.wmkeyboard.core.prediction.AppNames
import com.wasimaster.wmkeyboard.core.prediction.ContactEmails
import com.wasimaster.wmkeyboard.core.prediction.ContactNames
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryStore
import com.wasimaster.wmkeyboard.core.prediction.CompositeWordSource
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.MappedNgramPack
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.BengaliSpellingMap
import com.wasimaster.wmkeyboard.core.prediction.KeyProximity
import com.wasimaster.wmkeyboard.core.prediction.KeystrokeTiming
import com.wasimaster.wmkeyboard.core.prediction.Register
import com.wasimaster.wmkeyboard.core.prediction.RevisionAdvisor
import com.wasimaster.wmkeyboard.core.prediction.CandidateReranker
import com.wasimaster.wmkeyboard.core.prediction.CorrectionStats
import com.wasimaster.wmkeyboard.core.prediction.CorrectionWatch
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.prediction.NgramPack
import com.wasimaster.wmkeyboard.core.prediction.NgramReranker
import com.wasimaster.wmkeyboard.core.prediction.KeyTouchModel
import com.wasimaster.wmkeyboard.core.prediction.WordContext
import com.wasimaster.wmkeyboard.core.prediction.TouchPoint
import com.wasimaster.wmkeyboard.core.prediction.LanguageMixConfidence
import com.wasimaster.wmkeyboard.core.prediction.LearningBuffer
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.topWords
import com.wasimaster.wmkeyboard.core.prediction.PendingLearn
import com.wasimaster.wmkeyboard.core.prediction.SecondaryDictionary
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.SystemUserDictionary
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.accessibility.KeyboardPassthrough
import com.wasimaster.wmkeyboard.core.settings.HardwareKeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.LanguageDetectionStrength
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import android.net.ConnectivityManager
import com.wasimaster.wmkeyboard.core.settings.PhotoNetworkConditions
import com.wasimaster.wmkeyboard.core.settings.RotationState
import com.wasimaster.wmkeyboard.core.settings.isRotationDue
import com.wasimaster.wmkeyboard.core.settings.isThemeShuffleDue
import com.wasimaster.wmkeyboard.core.settings.rotates
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.theme.BackgroundBitmapCache
import com.wasimaster.wmkeyboard.core.settings.ModeField
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginRuntime
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.plugins.RenderedUi
import com.wasimaster.wmkeyboard.core.plugins.inputIds
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import com.wasimaster.wmkeyboard.core.settings.interactiveTyping
import com.wasimaster.wmkeyboard.core.settings.plainTyping
import com.wasimaster.wmkeyboard.core.settings.restrictedToDirectBoot
import com.wasimaster.wmkeyboard.core.settings.withoutModes
import com.wasimaster.wmkeyboard.core.settings.PowerSavingSettings
import com.wasimaster.wmkeyboard.core.settings.SystemMotion
import com.wasimaster.wmkeyboard.core.settings.underPowerSaving
import com.wasimaster.wmkeyboard.core.settings.withSystemMotion
import com.wasimaster.wmkeyboard.core.settings.DataSaverSettings
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.DevicePowerState
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.settings.onMeteredNetwork
import com.wasimaster.wmkeyboard.core.power.PowerSaver
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.applyDeviceForm
import com.wasimaster.wmkeyboard.core.settings.applyMode
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.keywordsEnabledFor
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.usableTools
import com.wasimaster.wmkeyboard.core.settings.resolveKeyboardMode
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetCandidate
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatch
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.stickers.StickerAddResult
import com.wasimaster.wmkeyboard.core.support.Support
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.text.EmojiGraphemes
import com.wasimaster.wmkeyboard.core.text.WordDelete
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.tools.BraveSearchClient
import com.wasimaster.wmkeyboard.core.tools.CheatSheetLetter
import com.wasimaster.wmkeyboard.core.tools.DefaultLeader
import com.wasimaster.wmkeyboard.core.tools.DoubleTapDetector
import com.wasimaster.wmkeyboard.core.tools.HintAction
import com.wasimaster.wmkeyboard.core.tools.LeaderTrigger
import com.wasimaster.wmkeyboard.core.tools.MacAction
import com.wasimaster.wmkeyboard.core.tools.MacBinding
import com.wasimaster.wmkeyboard.core.tools.TapModifier
import com.wasimaster.wmkeyboard.core.tools.ToolbarHintDigits
import com.wasimaster.wmkeyboard.core.tools.ToolboxLetter
import com.wasimaster.wmkeyboard.core.tools.languageCycleStart
import com.wasimaster.wmkeyboard.core.tools.languageCycleStep
import com.wasimaster.wmkeyboard.core.tools.languageSwitchDelta
import com.wasimaster.wmkeyboard.core.tools.macBindingFor
import com.wasimaster.wmkeyboard.core.tools.matches
import com.wasimaster.wmkeyboard.core.tools.parseLeader
import com.wasimaster.wmkeyboard.core.tools.pickerLetter
import com.wasimaster.wmkeyboard.core.tools.toolbarHintButtons
import com.wasimaster.wmkeyboard.ime.ui.activeSymbolSet
import com.wasimaster.wmkeyboard.ime.ui.keyboardHintPlan
import com.wasimaster.wmkeyboard.ime.ui.suggestionDisplayOrder
import com.wasimaster.wmkeyboard.ime.ui.visibleEmojiBarItems
import com.wasimaster.wmkeyboard.ime.ui.visibleToolbarTools
import com.wasimaster.wmkeyboard.core.tools.page
import com.wasimaster.wmkeyboard.core.tools.step
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusController
import com.wasimaster.wmkeyboard.core.tools.DictionaryClient
import com.wasimaster.wmkeyboard.core.tools.GifItem
import com.wasimaster.wmkeyboard.core.grammar.GrammarChecker
import com.wasimaster.wmkeyboard.core.grammar.GrammarEdit
import com.wasimaster.wmkeyboard.core.grammar.GrammarFix
import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.tools.GifSource
import com.wasimaster.wmkeyboard.core.tools.LinkPreviewClient
import com.wasimaster.wmkeyboard.core.tools.GifSources
import com.wasimaster.wmkeyboard.core.tools.GiphyClient
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.core.tools.KlipyClient
import com.wasimaster.wmkeyboard.core.tools.MediaCategories
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import com.wasimaster.wmkeyboard.core.tools.MediaCategoryCache
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryEntry
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryGuard
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryStore
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiInputMode
import com.wasimaster.wmkeyboard.core.tools.AiInsertMode
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.aiInitialInstruction
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.AiMarkdown
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.CryptoCatalog
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.core.tools.CalcEngine
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.ToolPrefill
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import com.wasimaster.wmkeyboard.core.tools.CharState
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TypedWord
import com.wasimaster.wmkeyboard.core.tools.TypingAchievements
import com.wasimaster.wmkeyboard.core.tools.TypingBests
import com.wasimaster.wmkeyboard.core.tools.TypingHistory
import com.wasimaster.wmkeyboard.core.tools.TypingResult
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.tools.TypingWordPool
import com.wasimaster.wmkeyboard.core.tools.TypingWordPools
import com.wasimaster.wmkeyboard.core.tools.WpmSample
import com.wasimaster.wmkeyboard.core.tools.buildTypingPrompt
import com.wasimaster.wmkeyboard.core.tools.compareWord
import com.wasimaster.wmkeyboard.core.tools.scoreTypingTest
import com.wasimaster.wmkeyboard.core.tools.settledKeystrokes
import com.wasimaster.wmkeyboard.core.tools.typingConfigKey
import com.wasimaster.wmkeyboard.core.tools.typingConfigLabel
import com.wasimaster.wmkeyboard.core.tools.WikipediaClient
import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import com.wasimaster.wmkeyboard.core.tools.WeatherInfo
import com.wasimaster.wmkeyboard.core.tools.WebResult
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.media.MediaControlManager
import com.wasimaster.wmkeyboard.core.media.MediaNotificationListener
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpBus
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpCapture
import com.wasimaster.wmkeyboard.core.voice.VoiceInputEngine
import com.wasimaster.wmkeyboard.core.voice.VoicePunctuation
import com.wasimaster.wmkeyboard.core.voice.VoiceSpacing
import com.wasimaster.wmkeyboard.core.voice.WhisperRecorder
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperException
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperModel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperScript
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperStore
import com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.ClipboardKeyAction
import com.wasimaster.wmkeyboard.core.keyman.KeymanRuleStore
import com.wasimaster.wmkeyboard.core.keyman.ProcessorKey
import com.wasimaster.wmkeyboard.core.keyman.ProcessorResult
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.numberRowFor
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.input.composer.Kana
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.input.composer.CjkUserHistory
import com.wasimaster.wmkeyboard.core.input.composer.JyutpingSyllables
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.input.composer.T9Pinyin
import com.wasimaster.wmkeyboard.core.input.composer.ZhuyinSyllables
import com.wasimaster.wmkeyboard.core.input.composer.CodeTableDictionary
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.script.FancyStyle
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.mapDigits
import com.wasimaster.wmkeyboard.core.script.resolveNumeralDigits
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.layoutAfterFancy
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.ime.ui.currentLayout
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.emojiStickerJobId
import com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen
import android.inputmethodservice.InputMethodService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The WM Keyboard input method service.
 *
 * Owns the engines (prediction, transliteration, emoji, clipboard), a
 * single [KeyboardUiState] flow, and the InputConnection plumbing. The
 * Compose view is pure presentation: it renders the state and calls back
 * into [onKey]/[onSuggestion]/[onEmoji]/etc.
 */
open class WMKeyboardService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var lifecycleOwner: KeyboardViewLifecycleOwner

    private val _uiState = MutableStateFlow(KeyboardUiState())
    val uiState = _uiState.asStateFlow()

    private lateinit var settingsRepository: SettingsRepository
    private var suggestionEngine: SuggestionEngine? = null
    private var emojiSearch: EmojiSearch? = null
    private var emojiSuggester: EmojiSuggester? = null
    private var emojiShortcodes: EmojiShortcodes = EmojiShortcodes.EMPTY
    private var emojiTriggers: EmojiTriggers = EmojiTriggers.EMPTY
    private var animatedEmoji: AnimatedEmoji = AnimatedEmoji.EMPTY
    private var emojiEntries: List<EmojiEntry> = emptyList()

    /**
     * Emoji display names from the installed keyword packs, by language then
     * emoji. Kept apart from the merged catalog because only one name can be
     * shown and the right one depends on the language being typed — see
     * [KeyboardUiState.emojiNamesByLang].
     */
    private var emojiPackNames: Map<String, Map<String, String>> = emptyMap()
    private lateinit var userLexicon: UserLexicon

    /**
     * Sighting counts for words no dictionary knows, which have to be typed
     * and left alone a few times before [userLexicon] will take them.
     */
    private var pendingLearn = PendingLearn(null)

    /**
     * This field's committed-but-unsettled words. Nothing here has been
     * counted yet: the user may still go back and fix any of it.
     */
    private val learningBuffer = LearningBuffer()

    /**
     * Autocorrects that have fired in this field and are waiting to be judged
     * against the text they landed in.
     */
    private val correctionWatch = CorrectionWatch()

    /**
     * The word the "add to dictionary?" chip is currently asking about, when
     * `askBeforeLearning` is on. Held here as well as in the UI state so the
     * chip can be re-offered after a panel covers the strip.
     */
    private var learnOfferWord: String? = null
    /** Whether [learnOfferWord]'s capitals are the user's own; see [learn]. */
    private var learnOfferCaseTrusted = false

    private lateinit var languageMixConfidence: LanguageMixConfidence
    private lateinit var emojiUsage: EmojiUsage
    /**
     * A tap has moved the usage ranking since [KeyboardUiState.emojiRecents] /
     * [KeyboardUiState.emojiFrequents] were last published.
     *
     * The published lists deliberately lag [emojiUsage] while the user can see
     * them: emoji re-sorting under the finger mid-run makes the picker's
     * history grid and the emoji row unusable. The snapshot catches up the
     * next time a history surface comes into view — see [publishEmojiHistory].
     */
    private var emojiHistoryStale = false
    private lateinit var clipboardStore: ClipboardStore

    /** Chord state of the six-key braille layout; see [onBrailleDot]. */
    private val brailleChord = BrailleChord()
    private val brailleGrade1 = BrailleGrade1()

    /** Pending morse sequence; the commit pause is [morseJob]. */
    private val morse = MorseInput()
    private var morseJob: Job? = null
    /** The SOS easter egg fires once per service lifetime; this is the once. */
    private var morseSosEggShown = false
    private var morseSosEggJob: Job? = null

    /** In-flight link-metadata fetch; one at a time (see [fetchLinkPreviews]). */
    private var linkPreviewJob: Job? = null
    /** Auto-hide timer for the recently-copied strip chip (see [showClipboardSuggestion]). */
    private var clipboardSuggestionJob: Job? = null
    /** Expiry timer for the one-time-code chip (see [maybeShowOtpSuggestion]). */
    private var otpSuggestionJob: Job? = null

    /**
     * The character-by-character run that types a code (see
     * [commitCodeToField]). Deliberately not one of the jobs a field restart
     * cancels: the restarts *are* the run, one per box the focus moves to.
     */
    private var codeEntryJob: Job? = null

    /**
     * The clip whose code has already been typed, so
     * [maybeShowCopiedCodeSuggestion] stops offering it. A spent code is done —
     * and unlike the notification chip, which clears its bus, this offer is
     * derived from clipboard history, which still holds the clip afterwards.
     */
    private var pastedCodeClipId: Long? = null
    private lateinit var snippetStore: SnippetStore
    private lateinit var aiHistoryStore: AiHistoryStore
    private lateinit var stickerPackStore: com.wasimaster.wmkeyboard.core.stickers.StickerPackStore

    /**
     * How the open panel reports what the hardware focus ring can move over.
     * Written by the panels during composition, read here on every arrow key.
     */
    private val panelFocus = PanelFocusController()

    /** Latest settings straight from DataStore, before mode overrides. */
    private var baseSettings: KeyboardSettings? = null

    /**
     * Battery level, charger and system battery-saver, combined with the
     * settings so the whole keyboard sees one already-reduced settings object
     * while power saving is in force (see [underPowerSaving]).
     */
    private val powerSaver = PowerSaver(this)

    /**
     * What the connection costs, combined with the settings the same way the
     * battery is: the background fetches are taken out of the settings object
     * itself (see [onMeteredNetwork]), and everything the user starts by hand
     * is decided against [dataSaverStatus] at the moment they start it.
     */
    private val networkWatcher = NetworkWatcher(this)

    /**
     * Data saving as it stands right now, including what the user has already
     * said yes to this session. Published into the ui state so the panels can
     * explain themselves; the grants are cleared whenever the network changes,
     * since a yes is an answer about one connection.
     */
    private var dataSaverStatus = DataSaverStatus()

    /** The network the [dataSaverStatus] grants were given on. */
    private var grantedNetwork: DeviceNetworkState? = null

    /**
     * The four things the settings pipeline combines, in one value.
     *
     * A named class rather than nested pairs because there are now four of
     * them: `Triple(stored, power, form)` was already at the edge of what a
     * destructuring in the collector explains, and a fifth flow would have
     * meant a pair of a triple.
     */
    private data class SettingsInputs(
        val stored: KeyboardSettings,
        val power: DevicePowerState,
        val form: DeviceForm,
        val network: DeviceNetworkState,
        /** The panel layouts (issue #63), resolved and repaired; their own flow, see [SettingsRepository.panelLayouts]. */
        val panelLayouts: Map<PanelKind, PanelLayoutSpec>,
    )

    /** Manual pick from the Modes tool; wins until the user switches app. */
    private var manualModeId: String? = null
    /** Package name of the app the focused field belongs to. */
    private var currentPackage: String? = null

    /**
     * The last words committed per app, newest last — an in-memory recency
     * overlay so each app's own vocabulary ranks a little higher there
     * (usernames in the terminal app, slang in the messenger). Deliberately
     * never persisted: a package-keyed store of typed words is an app-usage
     * record, and the ranking win doesn't justify one on disk.
     */
    private val perAppRecent = object : LinkedHashMap<String, ArrayDeque<String>>(
        16, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<String>>) =
            size > PER_APP_LRU_APPS
    }

    /** Push one committed word into the current app's recency ring and
     * refresh the engine's overlay set. */
    private fun notePerAppWord(word: String) {
        val pkg = currentPackage ?: return
        val ring = perAppRecent.getOrPut(pkg) { ArrayDeque() }
        ring.remove(word)
        ring.addLast(word)
        while (ring.size > PER_APP_RECENT_WORDS) ring.removeFirst()
        suggestionEngine?.contextWords = ring.toSet()
    }

    /** Refresh the engine's overlay for the app now in focus. */
    private fun refreshPerAppContext() {
        suggestionEngine?.contextWords =
            currentPackage?.let { perAppRecent[it]?.toSet() }.orEmpty()
    }
    /** Mode-binding kinds of the focused field (password, email, url…). */
    private var currentModeFields: Set<ModeField> = emptySet()

    /**
     * Input mode the focused field itself asked for — IME_FLAG_FORCE_ASCII
     * or a hintLocales match. Field-scoped and never persisted: it overrides
     * the saved mode while the field has focus, and the user's own language
     * switch (spacebar swipe, 🌐) clears it, because an explicit switch is
     * always a stronger signal than the app's request.
     */
    private var fieldLayoutOverride: String? = null

    /** This IME's framework id, used to register subtypes and mirror OS switches. */
    private val imeId: String by lazy { ComponentName(this, javaClass).flattenToShortString() }
    /**
     * Signature of the last subtype set pushed to the framework (enabled ids +
     * label style, or "off"); skips redundant writes, since the settings flow
     * emits on every unrelated change and re-registering thrashes the switcher.
     */
    private var registeredSubtypeSig: String? = null

    /** Autocorrect's revert memory; swapped by attachPersonalStores and
     * handed to the engine (a memory-only default lives there until then). */
    private var correctionStats = CorrectionStats(null)

    /** The typing counters behind About › Statistics; swapped by
     * attachPersonalStores, [TypingStats.enabled] folds the settings toggle,
     * incognito and power saving into the one gate the hooks check. */
    private var typingStats = TypingStats(null)

    /**
     * The context reranker, built once the seed bigrams are loaded. Pure
     * Kotlin over on-device data, so it ships in every channel; the setting
     * is the only gate, and off means CandidateReranker.NONE and the plain
     * frequency order.
     */
    private var ngramReranker: NgramReranker? = null

    /** Post-commit confusable detector behind the revision chip; built
     * alongside the engine with the same read-through pack lambda. */
    private var revisionAdvisor: RevisionAdvisor? = null

    private fun resolveReranker(settings: KeyboardSettings): CandidateReranker =
        if (settings.suggestionStrip.contextRerank) {
            ngramReranker ?: CandidateReranker.NONE
        } else {
            CandidateReranker.NONE
        }

    /**
     * Pushes the field's register into the engine: derived from the current
     * app and field kind when register priors are on, NEUTRAL (no effect)
     * when off. Called on every settings emission and every field entry.
     */
    private fun pushRegister(settings: KeyboardSettings) {
        suggestionEngine?.register = if (settings.suggestionStrip.registerPriors) {
            FieldRegister.resolve(currentPackage, _uiState.value.fieldKind)
        } else {
            Register.NEUTRAL
        }
    }

    private var composing = StringBuilder()
        set(value) {
            field = value
            // Any wholesale replacement (commit, field change, re-arm from the
            // field) invalidates the per-character tap positions: a re-armed
            // word was never tapped in this session, so it degrades to the
            // adjacency model via an all-null frame.
            composingTouch.clear()
            // Same boundary for the typing-rhythm signal: a fresh (or
            // re-armed) word starts with no rhythm history.
            keystrokeTiming.reset()
            publishComposingRoman()
        }
    private var previousWord: String? = null

    /** The word before [previousWord], for trigram context. Null whenever a
     * boundary intervenes or recovery is ambiguous — trigram prediction then
     * silently degrades to bigram. */
    private var previousWord2: String? = null

    /**
     * Tap position (key-width units, keyboard space) for each character in
     * [composing], null where unknown — hardware keys, dead-key output,
     * multi-char inserts. Only trusted when its size matches the buffer;
     * see [composingTouchFrame].
     */
    private val composingTouch = ArrayList<TouchPoint?>()

    /** The most recent letter tap's position, consumed by the next append. */
    private var pendingTouch: TouchPoint? = null

    /**
     * Typing rhythm of the word being composed, for the timing-signal
     * setting: fast bursts ease the autocorrect gate, deliberate typing
     * tightens it. Fed one timestamp per single-character append and reset
     * at every composing-buffer boundary.
     */
    private val keystrokeTiming = KeystrokeTiming()

    /** The timing signal's factor on the autocorrect gate, 1.0 when off. */
    private fun timingMultiplier(): Double =
        keystrokeTiming.multiplier(
            _uiState.value.settings.suggestionStrip.timingSignalStrength
        )

    /** The tap frame for the current composing buffer, or null to fall back
     * to the discrete adjacency model. Snapshot copy: the engine caches by
     * content and the live list mutates per keystroke. */
    private fun composingTouchFrame(): List<TouchPoint?>? =
        if (composingTouch.size == composing.length && composingTouch.any { it != null }) {
            ArrayList(composingTouch)
        } else {
            null
        }

    /** KeyboardScreen: the down position of the tap committing a letter. */
    private fun onKeyTouch(x: Float, y: Float) {
        pendingTouch = TouchPoint(x, y)
    }

    /**
     * Whether the composing word's capitals are the user's own.
     *
     * Decided once, when the word's first character lands, because that is the
     * only moment the answer exists: shift is spent by the keystroke, and by
     * the time the word commits there is nothing left to ask. A capital the
     * keyboard armed by itself — auto-capitalize at a sentence start, a field
     * flagged all-caps — says nothing about how the word is spelled, and
     * neither does caps lock, which is a statement about the letters rather
     * than about the word. Only those two teach the lexicon a spelling (#44).
     *
     * A word re-armed from text already in the field is untrusted for the same
     * reason: nobody knows where its capital came from.
     */
    private var composingCaseTrusted = false

    /** Appends to the composing buffer, pairing the pending tap position with
     * a single appended character (multi-char inserts get null slots). */
    private fun appendComposing(text: String) {
        if (composing.isEmpty()) {
            val state = _uiState.value
            composingCaseTrusted = state.shiftState == ShiftState.OFF ||
                (state.shiftState == ShiftState.ON && state.shiftPressedByUser)
        }
        composing.append(text)
        if (composingTouch.size == composing.length - text.length) {
            if (text.length == 1) {
                composingTouch.add(pendingTouch)
            } else {
                repeat(text.length) { composingTouch.add(null) }
            }
        }
        // Only single characters carry rhythm; a multi-char insert (dead
        // keys, pasted fragments) is not a keystroke.
        if (text.length == 1) keystrokeTiming.onKeystroke(SystemClock.uptimeMillis())
        pendingTouch = null
    }

    /** KeyboardScreen: letter-key centres of the live layout, normalised. */
    private fun onTouchKeys(keys: List<KeyCenter>) {
        suggestionEngine?.touchModel =
            KeyTouchModel(keys.associate { it.char to TouchPoint(it.x, it.y) })
    }

    /**
     * The last few completed words, newest last — the free half of the pattern
     * snippet gate.
     *
     * A pattern reaches back over several words, and finding out what they are
     * means a blocking read into the focused app. This is the same information
     * [previousWord] holds, kept a few words deeper, so the common answer ("no
     * pattern could possibly start here") costs nothing at all.
     *
     * It is a **hint, and it is allowed to be wrong**. Nothing is ever expanded
     * on its word: [tryPatternExpansion] reads the field and measures the match
     * against that, so a stale ring costs at most one wasted read. That is why
     * it only has to be maintained where [previousWord] already is, and why
     * [recentWordsValid] falls back to reading rather than to doing nothing.
     */
    private val recentWords = ArrayDeque<String>()

    /** False when [recentWords] cannot be trusted, so the gate reads instead. */
    private var recentWordsValid = false

    /**
     * Text a pattern expansion was just taken back from, so the space that
     * re-commits the restored word does not expand it straight back. Same
     * one-shot shape as [smartMutedAfter]; any typed character clears it.
     */
    private var patternMutedAfter: String? = null

    /**
     * True when the commit that just ran left the caret inside an expansion,
     * at a `{cursor}` marker. The key that ended the word — space, enter, a
     * full stop — must then be swallowed: committed at the caret it would drop
     * a stray character into the middle of the text the snippet inserted.
     */
    private var swallowTerminatorAfterCommit = false

    /** The snippets file, watched for edits made by the settings app. */
    private var snippetsFile: File? = null

    /** [snippetsFile]'s modification time at the last reload. */
    private var snippetsStamp = 0L
    /**
     * shortcut → expansion from Android's personal dictionary, for
     * [SuggestionStripSettings.expandUserDictShortcuts] (A28). Loaded off the
     * main thread in [loadDictionariesAndEmoji]; empty until then and whenever
     * the setting is off.
     */
    @Volatile private var userDictShortcuts: Map<String, String> = emptyMap()
    /**
     * Every word in Android's personal dictionary, for
     * [SuggestionStripSettings.useSystemDictionary] (#45). Handed to
     * [SuggestionEngine.systemDictionary]; loaded with [userDictShortcuts] and
     * re-read whenever [userDictObserver] reports a change, so a word added
     * in System settings is known the moment the user comes back.
     */
    @Volatile private var systemDictionaryWords: SystemUserDictionary.Entries =
        SystemUserDictionary.Entries.EMPTY
    /** Watches [android.provider.UserDictionary.Words] for [reloadSystemDictionary]. */
    private var userDictObserver: android.database.ContentObserver? = null
    /**
     * The [SuggestionStripSettings.useSystemDictionary] value the last
     * [readSystemDictionary] ran under, so the settings collector can tell a
     * flip from a first delivery whichever of the two lands first.
     */
    @Volatile private var systemDictLoadedWith: Boolean? = null
    private var lastSpaceTime = 0L
    /** uptime of the last spacebar/volume caret-scrub step; see [CARET_SCRUB_WINDOW_MS]. */
    private var lastCaretScrubMs = 0L
    private var lastShiftTapTime = 0L
    private var suggestionJob: Job? = null
    private var smartJob: Job? = null

    /**
     * Re-reads the context once a spacebar caret scrub stops moving.
     *
     * [CARET_SCRUB_WINDOW_MS] suppresses the re-read while the finger is still
     * dragging, and nothing used to run afterwards — the editor reports no
     * further selection update once the caret stops, so the strip stayed empty
     * until the next tap or keystroke. Scrubbing onto a word gave no
     * suggestions at all (#32). Each drag step cancels and re-arms this, so
     * only the settle survives.
     */
    private var caretSettleJob: Job? = null

    /**
     * The read behind an asking pattern's chip. Its own job rather than
     * [smartJob]: the two chips are gated on different settings and read
     * different amounts of text, and either may be the only one running.
     */
    private var snippetOfferJob: Job? = null

    /**
     * Gesture decode-and-commit runs in its own job, NOT [suggestionJob]: the
     * strip refresh of the very next keystroke cancels [suggestionJob], and
     * when the commit lived there a tap ~50 ms after a swipe cancelled the
     * decode mid-flight and silently dropped the swiped word.
     */
    private var gestureJob: Job? = null

    /**
     * Caret anchor for the immediate-revert states ([lastRevertible],
     * [lastGestureWord], [pendingAutoSpace]). Each is armed right after one of
     * our own commits and is only valid while the caret still sits exactly
     * where that commit left it — the text-equality probes alone would match
     * an identical word anywhere in the document (tap after another "the ",
     * press backspace → the wrong occurrence got reverted). -1 means the
     * commit's own onUpdateSelection echo hasn't arrived yet; the first echo
     * records the position, any later mismatch disarms all three.
     */
    private var revertAnchor = -1

    /**
     * When [revertAnchor] was last armed. One keystroke can make several edits
     * — the corrected word, then the space that triggered it — and an editor
     * that reports each one separately would otherwise anchor on the first echo
     * and then disarm on our own second one, so backspace-to-revert only worked
     * on editors that coalesced the two. Every update within
     * [REVERT_SETTLE_MS] of arming re-records the anchor instead of disarming;
     * nothing the user can do moves the caret inside that window, and the
     * revert paths still check the text they are about to replace.
     */
    private var revertArmedAt = 0L

    /** Called right after arming any of the immediate-revert states. */
    private fun armRevertGuard() {
        revertAnchor = -1
        revertArmedAt = SystemClock.uptimeMillis()
    }

    /**
     * Selection the editor last reported, seeded from EditorInfo on field start
     * and kept current by [onUpdateSelection]. Lets the per-keystroke paths
     * answer "is anything selected?" without a synchronous InputConnection
     * round-trip — that binder call blocks the UI thread for as long as the
     * target app's main thread is busy, which on a freshly-opened field (the
     * app still animating the keyboard into place) was long enough to make the
     * first keypress visibly lag and strand its preview bubble. -1 means
     * unknown, and unknown falls back to asking the editor.
     */
    private var expectedSelStart = -1
    private var expectedSelEnd = -1

    /** Where a converted Keyman layout's rules are read from. */
    private val keymanRules by lazy { KeymanRuleStore(this) }

    /**
     * The rule engine for the active layout, or null when the layout is not a
     * converted Keyman one or its rules are not on this device.
     *
     * Null is the common case and an ordinary one: the layout then types its own
     * key caps, which is a usable keyboard and exactly what a device that never
     * downloaded the rules gets.
     */
    private var keymanSession: KeymanSession? = null

    /** Rolling average of one suggestion computation, drives the debounce. */
    private var suggestionCostMs = 0L

    /**
     * The space/enter commit resolution the async suggestion job computed off
     * the main thread for the word currently being composed — the English
     * autocorrect target or the Bengali transliteration top. [commitComposing]
     * reads it instead of running the edit-distance search on the UI thread,
     * but only when [CommitResolution.typed] still equals the word being
     * committed; otherwise it recomputes synchronously, so a commit never uses
     * a stale result. Written from the suggestion coroutine, read on main —
     * hence [Volatile].
     */
    @Volatile
    private var commitResolution: CommitResolution? = null

    private class CommitResolution(
        val typed: String,
        val isBengali: Boolean,
        /** Transliteration top for Bengali phonetic mode; null otherwise. */
        val bengaliTop: String?,
        /** English autocorrect target, or null when the word stands as typed. */
        val correction: String?,
        /** Near miss: not applied, but worth a chip. See [correctionOffer]. */
        val offer: String? = null,
    )

    /**
     * The word the correction chip is offering, and the word it would replace.
     *
     * Held beside [KeyboardUiState.correctionOffer] the way [revisionContext]
     * is held beside its chip: the strip needs the replacement to draw, and the
     * tap needs the original to find the span in the field.
     */
    private var correctionOfferFor: String? = null

    /**
     * The offer waiting to be published by the next strip refresh, which is
     * where every other chip on that row is decided. Set at the commit, since
     * that is the only moment the decision exists.
     */
    private var pendingCorrectionOffer: String? = null

    /**
     * The last commit one backspace can take back. Any other input clears it.
     *
     * [original] is what the user actually typed; [committed] is what went into
     * the field, and is probed before anything is deleted. The kind decides
     * what else the undo means: an autocorrect the user rejects is also retired
     * and taught to the lexicon, while a snippet expansion is a plain text swap
     * that the keyboard should learn nothing from.
     */
    private class RevertibleCommit(val kind: Kind, val original: String, val committed: String) {

        enum class Kind { AUTOCORRECT, SNIPPET, JOIN, REVISION }
    }

    private var lastRevertible: RevertibleCommit? = null

    /**
     * True when the last keystroke auto-inserted a space right after
     * punctuation (the double-space ". "), so the very next shift press can
     * cancel that space instead of arming caps. Any other key clears it.
     */
    private var pendingAutoSpace = false

    /**
     * Narrower than [pendingAutoSpace]: the space was typed by the
     * auto-space-after-punctuation rule rather than by a double space, so a
     * space press right afterwards is the user's habit catching up with a
     * space that is already there and is swallowed instead of doubling it.
     * Survives a space press (which consumes it) and a shift press (which
     * cancels the whole insert); any other key clears it.
     */
    private var pendingPunctuationSpace = false

    /** Contact-name words for suggestions, when the setting + permission allow. */
    private var contactNames: ContactNames = ContactNames.EMPTY

    /** Contact email addresses for completion, when the setting + permission allow. */
    private var contactEmails: ContactEmails = ContactEmails.EMPTY

    /** Installed-app label words for suggestions, when the setting allows. */
    private var appNames: AppNames = AppNames.EMPTY

    /**
     * Accent armed by a dead key, waiting for the letter to combine with.
     * Mirrored into [KeyboardUiState.pendingDeadKey] for the strip chip.
     */
    private var pendingDeadKey: Char? = null

    /** Word lists the user imported, one trie per language (empty when none). */
    private var customDictionaries: Map<String, WordSource> = emptyMap()

    /** Bundled Bengali entries, kept so the phonetic index can be rebuilt. */
    private var bengaliAssetEntries: List<Pair<String, Int>> = emptyList()

    /**
     * Whether the last dictionary load included Bengali. The transliteration
     * map is a constructor argument of the engine and can't be swapped in, so
     * adding Bengali after the fact needs the whole load run again — this is
     * what notices.
     */
    private var loadedBengali = false

    /**
     * Whether the last dictionary load included the spelling map. Same story as
     * [loadedBengali]: the map reaches the engine through its constructor, so
     * turning it on or off has to run the load again to take effect.
     */
    private var loadedSpellingMap = false

    /**
     * The languages the last dictionary load was told to read from imported
     * word lists alone. Same story as [loadedBengali]: which tries the engine
     * is built over is decided during the load, so moving a language in or out
     * of the set has to run the load again to take effect.
     */
    private var loadedImportedOnly: Set<String> = emptySet()

    /**
     * The languages whose emoji keyword packs were left out of the merged
     * catalogue when it was last built (issue #51); a change rebuilds it.
     */
    private var loadedEmojiKeywordsOff: Set<String> = emptySet()

    /**
     * The languages the offensive-word filter was last built for. The list is
     * per language and only the enabled ones are read, so adding a language has
     * to rebuild the set or its words go unfiltered while the setting still
     * reads as on.
     */
    private var loadedOffensiveLangs: Set<String> = emptySet()

    /** Ids of the languages the user has switched on. */
    private fun enabledLanguageIds(): Set<String> =
        _uiState.value.settings.enabledLanguages.mapTo(HashSet()) { it.id }

    /**
     * The offensive-word set for [langIds], unioned.
     *
     * One asset per language under `dictionaries/offensive/`, and a language
     * with no list contributes nothing rather than failing the read. Unioned
     * rather than kept per language because the strip mixes languages — a
     * secondary language's words reach it too — and a candidate is offered or
     * it is not; there is no per-candidate language to consult at the point
     * [SuggestionEngine.suppressed] asks.
     *
     * Assets, so this works at a locked boot and with no network. The lists
     * hold only entries spelled in letters; see the header on any of them.
     */
    private fun readOffensiveWords(langIds: Set<String>): Set<String> {
        val words = HashSet<String>()
        for (langId in langIds) {
            runCatching {
                assets.open("dictionaries/offensive/$langId.txt").bufferedReader()
                    .useLines { lines ->
                        for (line in lines) {
                            val word = line.trim()
                            if (word.isEmpty() || word.startsWith("#")) continue
                            words.add(word.lowercase())
                        }
                    }
            }
        }
        return words
    }

    /** Languages reading the user's imported lists alone (issue #28). */
    private fun importedOnly(): Set<String> =
        _uiState.value.settings.suggestionStrip.importedOnlyLangs

    /**
     * Whether [langId] still reads the vocabulary that ships with the app and
     * the one the user downloaded, as opposed to their own imported lists
     * alone (issue #28).
     */
    private fun shippedDictionaryEnabled(langId: String): Boolean =
        _uiState.value.settings.suggestionStrip.shippedDictionaryEnabledFor(langId)

    /** Whether any enabled language routes through the Bengali machinery. */
    private fun bengaliEnabled(): Boolean =
        _uiState.value.settings.enabledLanguages.any { it.id == "bn" }

    /** Whether Bengali is on *and* has kept its fixed-spelling map switched on. */
    private fun spellingMapEnabled(): Boolean =
        bengaliEnabled() &&
            _uiState.value.settings.suggestionStrip.spellingMapEnabledFor("bn")

    /** Last word committed by a swipe, so tapping an alternate replaces it. */
    private var lastGestureWord: String? = null

    /**
     * The word the caret is sitting *inside* — [head] behind it, [tail] ahead
     * — when it is not parked at that word's end. Null the rest of the time.
     *
     * Deliberately not armed as a composing region, unlike the word a caret
     * lands at the end of ([restartSuggestionsAtCursor]). A region with the
     * caret in the middle of it is rewritten end-first by the next
     * `setComposingText`, which snaps the caret to the word's end and drops
     * the letter there — the trap [onUpdateSelection] already drops mid-word
     * compositions to avoid. So this is a read-only view of the word: the
     * strip answers about it ([publishCaretWordSuggestions]), a tap splices
     * the replacement over it ([onSuggestionTapped]), and typing goes through
     * the ordinary path as if the strip were not there.
     *
     * Held rather than re-read at tap time because the strip is about the word
     * as it was when the caret settled; the splice re-reads the field anyway
     * and stands down if the text has moved on under it.
     */
    private class CaretWord(val head: String, val tail: String) {
        val word: String get() = head + tail
    }

    private var caretWord: CaretWord? = null

    /**
     * Takes down the mid-word strip. Called from every path that changes the
     * text or the buffer before its own [refreshSuggestions], so a stale word
     * never gets a frame on screen; the caret's own settle re-derives it.
     */
    private fun clearCaretWord() {
        caretWord = null
    }

    /**
     * True when the space sitting right behind the caret was typed by the
     * keyboard to end a word rather than by the user: the glide commit's own
     * (see the gesture settings' `autoSpaceAfterGlide`) or the one that follows
     * a word picked from the suggestion strip (`autoSpaceAfterSuggestion`).
     * Punctuation typed next takes it back so the mark hugs the word, and a
     * space press right after is spent confirming it instead of doubling it —
     * the same one-shot contract [pendingPunctuationSpace] has, and cleared in
     * the same places.
     *
     * Both sources are one flag because every site that reads it wants the same
     * answer to the same question: "was the space behind the caret ours?". The
     * strip's pick used not to arm anything, which is what left a colon typed
     * after a picked word sitting behind a space it never asked for (issue #34).
     *
     * Handwriting also sets [lastGestureWord] but types no trailing space, so
     * this is what tells the two apart.
     */
    private var pendingWordSpace = false

    /**
     * Live-preview requests from a glide in progress. Conflated: a preview that
     * has not started yet is worth nothing once a newer one exists, and a swipe
     * issues one every 40 ms. One long-lived consumer drains it, in place of
     * cancelling and relaunching a job per preview.
     */
    private val gesturePreviews = Channel<GesturePreviewRequest>(Channel.CONFLATED)

    /**
     * Bumped when a swipe commits. A preview decoded from an earlier stroke must
     * not overwrite the strip afterwards — with a cancellable job that was the
     * cancel's job, and a conflated channel needs it stated explicitly.
     */
    private val gestureGeneration = AtomicInteger(0)

    /**
     * Bumped whenever the glide decoder's word sources change, so the
     * readiness watcher re-measures coverage after a dictionary download
     * lands — the language and layout it otherwise keys on did not move.
     */
    private val glideSourcesEpoch = MutableStateFlow(0)

    /**
     * How a swipe over a phonetic layout is read: Latin keys in, Bengali out.
     * Built with the Bengali dictionaries and handed to the engine only while
     * such a layout is showing — see [startGlideReadinessWatcher].
     */
    private var romanizedGlide: RomanizedIndex = RomanizedIndex.EMPTY

    /**
     * One resolved letter grid per (key list, key width). Building it sorts the
     * characters and squares up a key-to-key distance table, which a swipe was
     * otherwise paying for on every preview event.
     */
    private var cachedKeyMap: GlideKeyMap? = null
    private var cachedKeyMapKeys: List<KeyCenter> = emptyList()
    private var cachedKeyMapWidth = 0f

    // ---- network tool state (translate, gif/sticker, web/image search) ----
    private var translateJob: Job? = null
    /** Offline grammar tool (Harper); job debounces re-lints while typing. */
    private var grammarJob: Job? = null
    private var mediaFetchJob: Job? = null
    private var mediaLiveSearchJob: Job? = null
    private var mediaInsertJob: Job? = null
    /** Fetch of the GIF/sticker category row; see [refreshMediaCategories]. */
    private var mediaCategoryJob: Job? = null

    /**
     * The last search each media panel was showing, kept across panel closes
     * and field switches so reopening the GIF or sticker tool lands back on
     * it rather than on trending. In-memory on purpose: a query is session
     * ephemera, not a setting. See [stashMediaSearch].
     */
    private data class MediaSearchMemory(val query: String = "", val category: String? = null)
    private var savedGifSearch = MediaSearchMemory()
    private var savedStickerSearch = MediaSearchMemory()

    /** Saves [state]'s query for whichever media panel it is showing. */
    private fun stashMediaSearch(state: KeyboardUiState) {
        val memory = MediaSearchMemory(state.mediaQuery, state.mediaCategory)
        when (state.panel) {
            PanelMode.GIF -> savedGifSearch = memory
            PanelMode.STICKER -> savedStickerSearch = memory
            else -> {}
        }
    }

    /** The stashed search to restore when [panel] opens. */
    private fun savedMediaSearch(panel: PanelMode): MediaSearchMemory = when (panel) {
        PanelMode.GIF -> savedGifSearch
        PanelMode.STICKER -> savedStickerSearch
        else -> MediaSearchMemory()
    }

    /** The in-flight fetch of an animated emoji preview; one at a time. */
    private var animatedEmojiJob: Job? = null
    private var webSearchJob: Job? = null
    private var imageSearchJob: Job? = null

    // ---- media control state ----
    private val mediaController = MediaControlManager(this)
    /**
     * Whether the keyboard window is on screen. Only the media auto-pin reads
     * it: watching for playback is worth a live session listener while someone
     * is typing, and worth nothing at all once the keyboard is gone.
     */
    private var keyboardVisible = false

    // ---- voice input state ----
    private val voiceEngine = VoiceInputEngine(this)
    /** Bumped when a session ends/aborts so late recognizer callbacks drop. */
    private var voiceGeneration = 0
    /** Leading space needed when dictation begins mid-word/text. */
    private var voiceNeedsLeadingSpace = false
    /** Trailing space needed when dictation begins before a word. */
    private var voiceNeedsTrailingSpace = false
    /** User tapped stop: the pending final must not chain another utterance. */
    private var voiceStopRequested = false
    /** Consecutive empty utterances in continuous mode; give up after a few. */
    private var voiceSilentRetries = 0
    /** Last dictated commit, so the undo chip can take it back whole. */
    private var lastVoiceCommit: String? = null
    /** Active offline-Whisper capture, when the Whisper engine is in use. */
    private var whisperRecorder: WhisperRecorder? = null
    /**
     * True while a key from the voice panel's own action rail is being
     * dispatched, so it does not end the dictation session the way typing on the
     * keyboard does. See [onVoiceRailKey].
     */
    private var voiceRailKeyInFlight = false

    // ---- handwriting recognition state ----
    private val hwRecognizer = HandwritingRecognizerCache()
    private var hwJob: Job? = null
    /** Bumped on every stroke/undo/clear so in-flight recognitions go stale. */
    private var hwGeneration = 0
    private var hwCanvasSize = IntSize.Zero
    /** True while letter-area swipes are armed for handwriting (full builds). */
    private var hwKeyboardArmed = false

    /** The blacklist as last purged from the learning stores. */
    private var purgedBlacklist: Set<String> = emptySet()

    /**
     * Drops every newly blacklisted word from the personal dictionary and the
     * waiting room in front of it.
     *
     * The blacklist used to be a filter over the strip and nothing more, so a
     * word the user typed, then blacklisted, stayed learned — and showed up in
     * the personal dictionary as if the keyboard had ignored them (#48). It
     * runs against the whole list on the first emission, which is what makes
     * an entry added while the keyboard was not running take effect too.
     */
    private suspend fun purgeBlacklisted(blacklist: Set<String>) {
        val added = blacklist - purgedBlacklist
        purgedBlacklist = blacklist
        if (added.isEmpty() || !::userLexicon.isInitialized) return
        withContext(Dispatchers.Default) {
            userLexicon.forgetAll(added)
            for (word in added) pendingLearn.forget(word)
        }
    }
    /** Show the "download a model" hint at most once per keyboard session. */
    private var hwModelHintShown = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val state = _uiState.value
        if (!isClipboardAccessible() ||
            !state.settings.clipboard.history ||
            (state.incognitoOn && state.settings.incognitoPausesClipboard) ||
            state.secureField
        ) return@OnPrimaryClipChangedListener
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        val item = clip.getItemAt(0) ?: return@OnPrimaryClipChangedListener

        val uri = item.uri
        // Skip clips we set ourselves (pasting an image re-copies it to the
        // system clipboard as a fallback); re-adding would duplicate it.
        if (uri != null && uri.authority == clipboardFileProviderAuthority) return@OnPrimaryClipChangedListener

        // Which app the user copied from, resolved now while it's still the
        // foreground app (best-effort; null unless opted in and permitted).
        val source = if (state.settings.clipboard.trackSource) resolveClipSource() else null

        // The copying app's own claim that this clip holds a secret. Nothing
        // else in the system acts on it, so honoring it is entirely up to us —
        // and under KEEP the user has said not to, so the flag is dropped here
        // rather than at each of the places that would otherwise act on it.
        val handling = state.settings.clipboard.sensitiveHandling
        val flaggedSensitive = handling != SensitiveClipHandling.KEEP && clipMarkedSensitive(clip)
        if (flaggedSensitive && handling == SensitiveClipHandling.NEVER_SAVE) {
            return@OnPrimaryClipChangedListener
        }

        val imageMime = uri?.let { u ->
            runCatching { contentResolver.getType(u) }.getOrNull()?.takeIf { it.startsWith("image/") }
        }
        // Non-image URIs are files, folders or videos copied from a file
        // manager or gallery; record them by reference (see [addFileClips])
        // rather than copying — a copied movie is routinely gigabytes.
        if (uri != null && imageMime == null && item.text == null) {
            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
            if (uris.isNotEmpty()) {
                serviceScope.launch(Dispatchers.IO) { addFileClips(uris, source, flaggedSensitive) }
                return@OnPrimaryClipChangedListener
            }
        }
        if (uri != null && imageMime != null) {
            // Copy the image out of the source app's content provider while the
            // clip's URI grant is valid; the store owns the file afterwards.
            serviceScope.launch(Dispatchers.IO) {
                val copied = runCatching {
                    val dir = File(filesDir, "clipboard/images").apply { mkdirs() }
                    val extension = when (imageMime) {
                        "image/png" -> "png"
                        "image/gif" -> "gif"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val target = File(dir, "clip_${System.currentTimeMillis()}.$extension")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    target
                }.getOrNull()
                if (copied != null) {
                    val added = clipboardStore.addImage(
                        copied, imageMime, source, sensitive = flaggedSensitive,
                    )
                    clipboardStore.save()
                    _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
                    if (added != null && state.settings.clipboard.suggestRecent) {
                        showClipboardSuggestion(added)
                    }
                }
            }
            return@OnPrimaryClipChangedListener
        }

        val text = item.coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
        // Our own read of the text, for the password managers that predate the
        // flag and the codes copied by hand out of a message.
        val sensitive = flaggedSensitive || (
            handling != SensitiveClipHandling.KEEP &&
                state.settings.clipboard.detectSensitive &&
                ClipSensitivity.looksSensitive(text)
            )
        if (sensitive && handling == SensitiveClipHandling.NEVER_SAVE) {
            return@OnPrimaryClipChangedListener
        }
        val html = item.htmlText
        val added = if (html != null) {
            clipboardStore.addHtml(text, html, source, sensitive = sensitive)
        } else {
            clipboardStore.add(text, source, sensitive = sensitive)
        }
        clipboardStore.save()
        _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
        // A secret is never offered as a strip chip: the whole point of the chip
        // is that it sits in view above the keys while you type something else.
        if (added != null && added.kind.isTextual && !added.sensitive &&
            state.settings.clipboard.suggestRecent
        ) {
            showClipboardSuggestion(added)
        } else if (added != null) {
            // A code copied with the code box already focused — the case the
            // rule above would otherwise leave with nothing on the strip.
            maybeShowCopiedCodeSuggestion()
        }
        if (added?.kind == ClipKind.LINK) fetchLinkPreviews()
    }

    /**
     * Whether the copying app marked this clip as holding sensitive content.
     *
     * The extra was added in Android 13 as `ClipDescription.EXTRA_IS_SENSITIVE`,
     * but the key string it is defined as predates it and password managers set
     * it on older releases too — so the literal is read on every version rather
     * than gated behind a version check that would ignore it exactly where the
     * platform offers no protection of its own.
     */
    private fun clipMarkedSensitive(clip: android.content.ClipData): Boolean =
        clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    private var lastScreenshotId = -1L
    private var screenshotObserver: android.database.ContentObserver? = null

    private fun updateScreenshotObserver(enabled: Boolean) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_IMAGES,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (enabled && hasPermission) {
            if (screenshotObserver == null) {
                val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                        super.onChange(selfChange, uri)
                        handleScreenshotAdded()
                    }
                }
                screenshotObserver = observer
                contentResolver.registerContentObserver(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
            }
        } else {
            screenshotObserver?.let {
                contentResolver.unregisterContentObserver(it)
                screenshotObserver = null
            }
        }
    }

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out so it can be read on
     * every API level rather than only where the constant exists.
     */
    private val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private fun handleScreenshotAdded() {
        val state = _uiState.value
        if (!state.settings.clipboard.history ||
            (state.incognitoOn && state.settings.incognitoPausesClipboard) ||
            state.secureField
        ) return

        serviceScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DATE_ADDED,
                android.provider.MediaStore.Images.Media.DATA,
                android.provider.MediaStore.Images.Media.MIME_TYPE
            )
            val cursor = runCatching {
                contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            }.getOrNull() ?: return@launch

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val dateAdded = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED))
                    if (System.currentTimeMillis() / 1000 - dateAdded > 15) return@launch

                    val path = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)).orEmpty()
                    if (!path.contains("Screenshot", ignoreCase = true)) return@launch

                    val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                    if (id == lastScreenshotId) return@launch
                    lastScreenshotId = id

                    val mimeType = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.MIME_TYPE)) ?: "image/png"
                    val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    val copied = runCatching {
                        val dir = File(filesDir, "clipboard/images").apply { mkdirs() }
                        val extension = when (mimeType) {
                            "image/png" -> "png"
                            "image/gif" -> "gif"
                            "image/webp" -> "webp"
                            else -> "jpg"
                        }
                        val target = File(dir, "clip_${System.currentTimeMillis()}.$extension")
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@runCatching null
                        target
                    }.getOrNull()

                    if (copied != null) {
                        val added = clipboardStore.addImage(copied, mimeType, "System UI")
                        clipboardStore.save()
                        _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
                        if (added != null && state.settings.clipboard.suggestRecent) {
                            showClipboardSuggestion(added)
                        }
                    }
                }
            }
        }
    }

    private val clipboardFileProviderAuthority: String
        get() = "$packageName.clipboard"

    /**
     * Best-effort label of the app that produced the current clip: the app that
     * was in the foreground in the moments before the clipboard changed, per
     * [UsageStatsManager]. Returns null when the Usage Access permission isn't
     * granted or no recent foreground app can be found — the copy still lands in
     * history, just without a source. Own package is treated as "no source".
     */
    private fun resolveClipSource(): String? {
        if (!hasUsageAccess()) return null
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pkg = runCatching {
            val events = usage.queryEvents(now - 10_000L, now)
            val event = android.app.usage.UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == fgType) {
                    last = event.packageName
                }
            }
            last
        }.getOrNull()
        if (pkg == null || pkg == packageName) return null
        return runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    /** Whether the user granted the Usage Access special permission. */
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Records copied files and folders as clips. Only the URI, display name
     * and size are stored — the bytes stay with the app that owns them, so a
     * copied 4 GB video costs us nothing.
     *
     * Clipboard URI grants are short-lived, so we try to persist them; most
     * providers refuse, in which case inserting later falls back to putting
     * the URI back on the system clipboard.
     */
    private fun addFileClips(
        uris: List<Uri>,
        sourceApp: String? = null,
        sensitive: Boolean = false,
    ) {
        var added = false
        for (uri in uris.take(MAX_FILE_CLIPS_PER_COPY)) {
            val info = resolveClipFile(uri) ?: continue
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            clipboardStore.addUri(
                uriString = uri.toString(),
                displayName = info.name,
                mimeType = info.mimeType,
                isDirectory = info.isDirectory,
                size = info.size,
                sourceApp = sourceApp,
                durationMs = if (info.mimeType.startsWith("video/")) videoDuration(uri) else -1,
                sensitive = sensitive,
            )
            added = true
        }
        if (!added) return
        clipboardStore.save()
        _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
    }

    private data class ClipFileInfo(
        val name: String,
        val mimeType: String,
        val size: Long,
        val isDirectory: Boolean,
    )

    /**
     * Play length of a copied video in ms, or -1 when it can't be read. Runs on
     * the IO dispatcher with the rest of [addFileClips]: the retriever opens
     * and parses the container, which is not a main-thread operation.
     */
    private fun videoDuration(uri: Uri): Long = runCatching {
        // Not `use`: MediaMetadataRetriever only became AutoCloseable in API 29,
        // and this app runs back to 24.
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: -1L
        } finally {
            retriever.release()
        }
    }.getOrDefault(-1L)

    /** Display name, size and directory-ness of a copied file URI. */
    private fun resolveClipFile(uri: Uri): ClipFileInfo? {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null
            return ClipFileInfo(
                name = file.name.ifBlank { uri.toString() },
                mimeType = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                else contentResolver.getType(uri) ?: "application/octet-stream",
                size = if (file.isDirectory) -1 else file.length(),
                isDirectory = file.isDirectory,
            )
        }
        if (uri.scheme != "content") return null
        // A tree URI describes a folder but can't be queried directly; its
        // document URI can.
        val queryUri = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri, DocumentsContract.getTreeDocumentId(uri),
                )
            } else {
                uri
            }
        }.getOrDefault(uri)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var name: String? = null
        var size = -1L
        var mime: String? = null
        runCatching {
            contentResolver.query(queryUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                fun column(key: String) = cursor.getColumnIndex(key).takeIf { it >= 0 }
                column(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?.let { if (!cursor.isNull(it)) name = cursor.getString(it) }
                column(DocumentsContract.Document.COLUMN_SIZE)
                    ?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
                column(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    ?.let { if (!cursor.isNull(it)) mime = cursor.getString(it) }
            }
        }
        val resolvedMime = mime
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: return null
        val isDirectory = resolvedMime == DocumentsContract.Document.MIME_TYPE_DIR ||
            runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        return ClipFileInfo(
            name = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: getString(R.string.ime_service_clip_file_unnamed_label),
            mimeType = resolvedMime,
            size = if (isDirectory) -1 else size,
            isDirectory = isDirectory,
        )
    }

    /**
     * Fills in Open Graph metadata for copied links, newest first, when the
     * user has link previews on. Runs one link at a time so a panel full of
     * links doesn't fire a dozen simultaneous requests.
     */
    private fun fetchLinkPreviews() {
        if (!_uiState.value.settings.clipboard.linkPreviews) return
        if (linkPreviewJob?.isActive == true) return
        val pending = clipboardStore.linksNeedingPreview().take(MAX_LINK_PREVIEWS)
        if (pending.isEmpty()) return
        linkPreviewJob = serviceScope.launch(Dispatchers.IO) {
            for (clip in pending) {
                val url = ClipLinks.asUrl(clip.text) ?: continue
                val preview = LinkPreviewClient.fetch(url)
                clipboardStore.setLinkPreview(clip.id, preview)
                _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
            }
            clipboardStore.save()
        }
    }

    /**
     * Whether credential-encrypted storage is readable — false only during
     * direct boot, between the phone powering on and the first unlock. The
     * service is `directBootAware` (see the manifest), so it really does get
     * created and drawn in that window, and every path that would touch
     * `filesDir` or the settings DataStore has to check this first.
     *
     * Distinct from [KeyboardUiState.deviceLocked], which is the keyguard being
     * up — a much more common state, and one where all storage works.
     */
    private var userUnlocked = true

    private var unlockReceiverRegistered = false

    /**
     * The user finished unlocking while the keyboard was already running. Their
     * real settings, word lists and personal stores exist from this moment on,
     * so everything that was stubbed out gets re-established in place — the
     * alternative is a keyboard that stays in its reduced state until the
     * process happens to be killed.
     */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // First thing in the process: an IME that dies is replaced by another
        // keyboard without a word to the user, so the crash record has to be
        // armed before anything that could crash runs.
        DebugLog.attach(this)
        lifecycleOwner = KeyboardViewLifecycleOwner()
        lifecycleOwner.onCreate()

        userUnlocked = DirectBoot.isUserUnlocked(this)
        DebugLog.i("ime", "service created (unlocked=$userUnlocked)")
        // Two PackageManager lookups, before the first toolbar is laid out:
        // isSupportedTool reads the answer with no Context of its own, and a
        // tool that appears and then vanishes a frame later looks like a bug.
        PlayServices.prime(this)
        // Parks on an empty channel until the first glide; costs nothing until
        // then, and saves a job launch per preview once a finger is down.
        startGesturePreviewConsumer()
        startGlideReadinessWatcher()
        // A process that started on the lock screen never ran ML Kit's init
        // provider, and every ML Kit tool in it — handwriting, OCR, QR, doc
        // scan — stays broken until it is initialized by hand.
        if (userUnlocked) MlKitInit.ensure(this)
        settingsRepository = SettingsRepository(this)
        // Decode the synthesized key sounds up front so the first press plays,
        // and resolve the audio/vibrator services here rather than from the
        // pointer-down handler of whichever key the user hits first.
        //
        // Off the main thread, because "up front" does not have to mean
        // "before the keyboard can be drawn". The first run after an install
        // or a cleared cache synthesises three waveforms and writes them to
        // disk, and every run after that opens a SoundPool and loads them —
        // all of it in the window where the user is waiting for a keyboard to
        // appear, and most of it for a sound that is off by default. There is
        // a whole view inflation, a layout pass and a finger travelling to a
        // key between here and the first press.
        // Three separate launches rather than one: they are independent, and
        // the icon table is the one racing the first frame — it must not queue
        // behind a first-run waveform synthesis.
        serviceScope.launch(Dispatchers.Default) {
            // Guarded: this used to run inside onCreate, where a full disk
            // taking the sound cache down with it would have crashed the
            // keyboard anyway. On a coroutine it would still crash it — via
            // the scope's uncaught handler — and a keyboard that will not open
            // because it could not cache a click is a bad trade. The sounds
            // fall back to the system effects on their own.
            runCatching {
                KeySoundPlayer.warmUp(this@WMKeyboardService)
                // A pack is up to sixty-four samples; without decoding them
                // here the first several keystrokes after the IME opens fall
                // back to the system click, which reads as the pack not
                // working rather than as the pool still catching up.
                val sound = _uiState.value.settings
                if (sound.keySoundStyle == com.wasimaster.wmkeyboard.core.settings.KeySoundStyle.PACK) {
                    KeySoundPlayer.preload(this@WMKeyboardService, sound.keySoundCustom.packId)
                }
                HapticPlayer.warmUp(this@WMKeyboardService)
            }
        }
        // The default icon table, for the same reason. [IconDefaults.warm] was
        // written to be called off the main thread before the first frame, but
        // its only caller was the background icon-pack build, which is a
        // composition effect — it cannot run until the composition that
        // scheduled it has already been applied, and that composition draws
        // the shift, backspace, enter, globe and emoji keys, each of which
        // forces the table. So the keyboard's own first frame always got there
        // first and built eighty-odd Material vectors on the main thread.
        // Service create strictly precedes the first onStartInputView, so from
        // here it wins the race. The lazy behind it is synchronized, so a frame
        // that does arrive early simply waits for the same map.
        serviceScope.launch(Dispatchers.Default) { IconDefaults.warm() }
        // Whether the Harper grammar library is present, resolved here so no
        // keyboard show has to: reading it maps an 11 MB native library. See
        // [grammarAvailable].
        grammarProbe = serviceScope.launch {
            // IO, not Default: this is a `System.loadLibrary` — a file mapped
            // and relocated — and on the Default pool it queued behind the two
            // CPU-bound warm-ups above, which on a two-core phone is exactly
            // when it would still be unresolved as the keyboard appeared.
            val available = withContext(Dispatchers.IO) { GrammarChecker.available }
            if (available) {
                grammarAvailable = true
                _uiState.update { it.copy(grammar = it.grammar.copy(available = true)) }
            }
        }
        startDndWatch()
        startUserDictionaryWatch()
        attachPersonalStores()
        // Keeps the app-launcher tool's list honest across installs/removals;
        // cheap (it only drops caches), so registered unconditionally.
        registerPackageChangeReceiver()
        // Direct boot: nothing below can read credential-encrypted storage yet,
        // so wait for the unlock rather than for the next process start — the
        // keyboard is very often the app that is *on screen* when it happens.
        if (!userUnlocked) {
            ContextCompat.registerReceiver(
                this, unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            unlockReceiverRegistered = true
        }

        // Tops up an upgraded install's stored mode list with modes added
        // since it was first seeded. No-op once it has run.
        if (userUnlocked) serviceScope.launch { settingsRepository.seedNewDefaultModes() }

        // Installs that enabled a romanized pair (English + Banglish) before
        // auto-pairing shipped get the cross-suggestions wired up once here;
        // from then on only adding a language pairs anything.
        if (userUnlocked) {
            serviceScope.launch { settingsRepository.reconcileRomanizedSecondariesOnce() }
        }

        // The automatic backup's job is not persisted across reboots, so
        // something has to put it back, and the keyboard starts long before the
        // settings app does. Leaves an already-correct job alone, which matters:
        // rescheduling restarts the period, and this runs often.
        if (userUnlocked) {
            serviceScope.launch {
                AutoBackupScheduler.sync(this@WMKeyboardService, settingsRepository.settings.first().autoBackup)
            }
        }

        powerSaver.start()
        networkWatcher.start()

        // A downloaded emoji dictionary is inert until the merged catalogue is
        // rebuilt. Bumping the counter rather than reloading directly is what
        // makes a download started in the settings app reach this process too:
        // both watch the same setting, and only the one that downloaded emits
        // here.
        serviceScope.launch {
            EmojiDictDownloadManager.completions.collect {
                if (userUnlocked) settingsRepository.bumpEmojiKeywordPackVersion()
            }
        }

        // A freshly landed n-gram pack goes live without waiting for a
        // language switch — but only when it is the active language's.
        serviceScope.launch {
            NgramPackDownloadManager.completions.collect { langId ->
                if (userUnlocked && _uiState.value.language.id == langId) {
                    suggestionEngine?.ngramPack = loadNgramPack(langId)
                }
            }
        }

        serviceScope.launch {
            var lexiconVersion = -1
            var statsVersion = -1
            var customDictVersion = -1
            var emojiPackVersion = -1
            var emojiUsageVersion = -1
            var contactsEnabled: Boolean? = null
            var contactEmailsEnabled: Boolean? = null
            var appNamesEnabled: Boolean? = null
            var linkPreviewsEnabled: Boolean? = null
            var pinnedLastEnabled: Boolean? = null
            var userScreenshotsEnabled: Boolean? = null
            var otpCaptureEnabled: Boolean? = null
            var voiceBarPersisted: Pair<Boolean, Boolean>? = null
            // Recompute the hidden-emoji set only when the toggle or the font
            // behind it actually changes, not on every unrelated settings save.
            var hiddenEmojiKey: Triple<Boolean, EmojiFontChoice, String>? = null
            var coinPickKey: Pair<Boolean, Set<String>>? = null
            // Power saving is folded in here rather than downstream for the same
            // reason as direct boot: it is a *view* of the settings, so the
            // renderer, the engine and the tools all see one already-reduced
            // object and none of them has to know it exists. Combined rather
            // than collected separately because the battery changes on its own
            // schedule — plugging the charger in has to re-emit the settings.
            combine(
                settingsRepository.settings,
                powerSaver.state,
                settingsRepository.photoRotationStates,
                deviceForm,
                networkWatcher.state,
                settingsRepository.panelLayouts,
            ) { inputs ->
                @Suppress("UNCHECKED_CAST")
                val stored = inputs[0] as KeyboardSettings
                val power = inputs[1] as DevicePowerState
                @Suppress("UNCHECKED_CAST")
                val rotation = inputs[2] as Map<String, RotationState>
                val form = inputs[3] as DeviceForm
                val network = inputs[4] as DeviceNetworkState
                @Suppress("UNCHECKED_CAST")
                val panelLayouts = inputs[5] as Map<PanelKind, PanelLayoutSpec>
                // Published rather than folded into the settings object: the
                // rotating photo is laid over a theme where it is drawn, not
                // written into the theme the user saved.
                PhotoBackgroundManager.publishRotationStates(rotation)
                SettingsInputs(stored, power, form, network, panelLayouts)
            }.collect { (stored, power, form, network, panelLayouts) ->
                // Screen size first, because these are *defaults* — the least
                // specific thing in the chain, and every overlay below is
                // entitled to beat them. It also has to precede direct boot: a
                // tablet's wider default toolbar includes credential-backed
                // tools, and re-pinning them after that filter would put them
                // back on a lock screen that cannot read their data.
                // Modes first of the views: switching the feature off empties
                // the mode list, and every overlay below reads a keyboard that
                // simply has no modes rather than one that has to remember not
                // to apply them (issue #41).
                val formed = stored.withoutModes().applyDeviceForm(form)
                // Direct boot: everything backed by credential-encrypted
                // storage is switched off once, here, so that nothing below —
                // nor anything reading the ui state afterwards — has to know
                // why the fonts, contacts and half the tools are missing.
                val unlockedSettings =
                    if (userUnlocked) formed else formed.restrictedToDirectBoot()
                val saving = unlockedSettings.powerSaving.appliesTo(power)
                val savedSettings =
                    if (saving) unlockedSettings.underPowerSaving() else unlockedSettings
                // Data saving last of the three views: it is the one keyed to
                // something outside the phone, and it should be able to take
                // away a fetch that power saving was still happy to make.
                val metered = savedSettings.dataSaver.appliesTo(network)
                updateDataSaverStatus(savedSettings.dataSaver, network, metered)
                // Re-read per emission rather than once at startup, so turning
                // the device's animation scale off in developer options or
                // accessibility reaches the keyboard at the next settings
                // change instead of at the next process start.
                val settings = (
                    if (metered) savedSettings.onMeteredNetwork() else savedSettings
                    ).withSystemMotion(SystemMotion.animationsOff(this@WMKeyboardService))
                val nextHiddenKey = Triple(
                    settings.emoji.hideUnrenderable,
                    settings.emojiFont,
                    settings.emojiFontInstalled.installedId,
                )
                if (hiddenEmojiKey != nextHiddenKey) {
                    hiddenEmojiKey = nextHiddenKey
                    recomputeHiddenEmoji(settings)
                }
                // Turning a coin on or off only changes which of the rates
                // already in hand are used, so it re-merges rather than
                // refetching.
                val nextCoinKey =
                    settings.rateSources.cryptoEnabled to settings.rateSources.cryptoTickers
                if (coinPickKey != nextCoinKey) {
                    coinPickKey = nextCoinKey
                    remergeCurrencyRates()
                }
                clipboardStore.expiryMillis = settings.clipboard.expiryHours * 60L * 60 * 1000
                clipboardStore.maxItems = settings.clipboard.maxItems
                clipboardStore.sensitiveExpiryMillis =
                    if (settings.clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
                        settings.clipboard.sensitiveExpiryMinutes * 60L * 1000
                    } else {
                        0L
                    }
                // Flipping pinned-first/last re-sorts the store, so refresh the
                // panel's snapshot when the choice actually changes.
                if (pinnedLastEnabled != settings.clipboard.pinnedLast) {
                    clipboardStore.pinnedLast = settings.clipboard.pinnedLast
                    pinnedLastEnabled = settings.clipboard.pinnedLast
                    _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
                }
                // Turning the strip chip off hides any chip already showing.
                if (!settings.clipboard.suggestRecent) clearClipboardSuggestion()
                // Mirror the code-chip switch to the notification listener's
                // device-protected flag — the listener cannot read settings,
                // and the flag is what stops it reading notifications at all.
                if (otpCaptureEnabled != settings.otp.enabled) {
                    otpCaptureEnabled = settings.otp.enabled
                    NotificationOtpCapture.setEnabled(
                        this@WMKeyboardService,
                        settings.otp.enabled,
                    )
                    if (!settings.otp.enabled) clearOtpSuggestion()
                }
                // Turning previews off throws away what was already fetched, so
                // the panel stops showing metadata the user opted out of.
                if (linkPreviewsEnabled == true && !settings.clipboard.linkPreviews) {
                    linkPreviewJob?.cancel()
                    clipboardStore.clearLinkPreviews()
                    clipboardStore.save()
                    _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
                }
                linkPreviewsEnabled = settings.clipboard.linkPreviews
                if (userScreenshotsEnabled != settings.clipboard.userScreenshots) {
                    userScreenshotsEnabled = settings.clipboard.userScreenshots
                    updateScreenshotObserver(settings.clipboard.userScreenshots)
                }
                if (!settings.floatingKeyboard) floatingPanelBounds = null
                if (settings.contactSuggestions != contactsEnabled) {
                    contactsEnabled = settings.contactSuggestions
                    if (settings.contactSuggestions) {
                        loadContactNames()
                    } else {
                        contactNames = ContactNames.EMPTY
                        suggestionEngine?.contacts = ContactNames.EMPTY
                    }
                }
                if (settings.contactEmailSuggestions != contactEmailsEnabled) {
                    contactEmailsEnabled = settings.contactEmailSuggestions
                    if (settings.contactEmailSuggestions) {
                        loadContactEmails()
                    } else {
                        contactEmails = ContactEmails.EMPTY
                        suggestionEngine?.contactEmails = ContactEmails.EMPTY
                    }
                }
                if (settings.appNameSuggestions != appNamesEnabled) {
                    appNamesEnabled = settings.appNameSuggestions
                    if (settings.appNameSuggestions) {
                        loadAppNames()
                    } else {
                        appNames = AppNames.EMPTY
                        suggestionEngine?.apps = AppNames.EMPTY
                    }
                }
                // The platform personal dictionary as a known-word source
                // (#45): the reload reads the setting, so one call serves
                // both directions. Compared against what the last read ran
                // under rather than the previous delivery, so it is right
                // whether settings or the dictionaries land first.
                if (systemDictLoadedWith != null &&
                    settings.suggestionStrip.useSystemDictionary != systemDictLoadedWith
                ) {
                    reloadSystemDictionary()
                }
                // The settings app edited the learned-words file (personal
                // dictionary): drop the in-memory copy for the disk state,
                // otherwise the next save here would clobber those edits.
                if (lexiconVersion != -1 && settings.lexiconVersion != lexiconVersion) {
                    withContext(Dispatchers.Default) {
                        userLexicon.reload()
                        // The Storage screen deletes all the learning files at
                        // once, so the same signal has to drop all the copies:
                        // any one of them saves the old data back otherwise.
                        pendingLearn.reload()
                        emojiUsage.reload()
                        languageMixConfidence.reload()
                    }
                    // Words the user just deleted from their personal
                    // dictionary must not be sitting in the buffer waiting to
                    // be written straight back.
                    learningBuffer.clear()
                }
                lexiconVersion = settings.lexiconVersion
                // Same contract for the typing counters: the Statistics
                // screen's delete (and the Storage screen's) bumps the
                // version so the in-memory copy here does not save the old
                // numbers straight back over the emptied file.
                if (statsVersion != -1 && settings.statsVersion != statsVersion) {
                    withContext(Dispatchers.Default) { typingStats.reload() }
                }
                statsVersion = settings.statsVersion
                baseSettings = settings
                val mode = resolveKeyboardMode(
                    settings.keyboardModes, currentPackage, currentModeFields, manualModeId,
                )
                // A field-scoped override (FORCE_ASCII, hintLocales) outlives
                // settings emissions — otherwise saving any unrelated setting
                // would drop the field back to a layout it cannot accept.
                val activeSpec = activeLayoutSpec(settings)
                // Hoisted so the grid is expanded against the same digit-row
                // answer the renderer will draw with. They must agree: on a
                // tablet the digit row is where backspace goes, and a
                // disagreement means the keyboard has none at all.
                val modeSettings = settings.applyMode(mode)
                // The collapsed voice bar's armed flag is persisted settings
                // ([VoiceBarSettings.active]); the ui-state flag follows it so
                // the bar survives the IME process dying between fields, and
                // so a mode change in the settings app takes effect live.
                // Synced only when the persisted value *changes*: open/close
                // update the ui state first and persist after, so an unrelated
                // emission in that gap still carries the old value, and
                // re-applying it would flash the bar back mid-close.
                val voiceBarArmed = modeSettings.voiceBar.armed()
                val voiceBarInline = modeSettings.voiceBar.inline
                val voiceBarSync = voiceBarPersisted != (voiceBarArmed to voiceBarInline)
                voiceBarPersisted = voiceBarArmed to voiceBarInline
                // Keeps the rule engine paired with the grid on screen: a
                // language switch must not leave the old keyboard's rules
                // running against the new grid.
                syncKeymanSession(activeSpec)
                _uiState.update {
                    it.copy(
                        settings = modeSettings,
                        panelLayouts = panelLayouts,
                        voice = it.voice.withBarSynced(
                            sync = voiceBarSync,
                            armed = voiceBarArmed,
                            inline = voiceBarInline,
                        ),
                        // The settings above are already reduced, so this is
                        // only for the indicator and the tool's lit state —
                        // nothing gates on it.
                        powerSavingOn = saving,
                        // Unlike power saving, this one *is* gated on: the
                        // features it holds back are moments rather than
                        // settings, so the panels read the status to know
                        // whether to fetch, to explain, or to offer.
                        dataSaver = dataSaverStatus,
                        language = activeSpec.language(),
                        script = activeSpec.script(),
                        composer = composerFor(activeSpec.script(), activeSpec.composerType()),
                        layoutId = activeSpec.id,
                        layoutName = activeSpec.name,
                        layouts = resolveLayoutSet(
                            activeSpec,
                            it.fieldKind,
                            form,
                            modeSettings.numberRow,
                            modeSettings.customLayouts,
                        ),
                        activeModeId = mode?.id,
                    )
                }
                // Keep the OS switcher's subtype list in step with the enabled
                // layouts. Diffed inside, so unrelated settings emissions here
                // don't thrash the framework.
                registerSubtypes(settings)
                // The auto-pin's switch, its allowlist and the tool's own
                // enable state all live in the settings that just landed.
                syncMediaTracking()
                // Switching the swipe action to handwriting (or turning the
                // gesture on) while the keyboard is up checks the model now, so
                // the first swipe writes rather than nagging.
                val nowArmed = keyboardHandwriteActive(_uiState.value)
                if (nowArmed && !hwKeyboardArmed) refreshHandwritingStatus()
                hwKeyboardArmed = nowArmed
                // Everything below keys off the mode actually being typed, so
                // a field-forced mode gets its own proximity grid and word
                // lists rather than the saved mode's.
                val activeLang = activeSpec.language()
                // Typo weighting follows the grid actually on screen, so a
                // rearranged custom layout weights its own neighbours.
                suggestionEngine?.proximity = KeyProximity.forLayout(
                    activeSpec,
                    numberRow = settings.numberRow &&
                        settings.suggestionStrip.numberRowCorrections,
                )
                suggestionEngine?.autocorrectConfidence =
                    settings.autocorrectConfidence.toDouble()
                suggestionEngine?.adaptiveConfidence = settings.autocorrectAdaptive
                correctionStats.memory = settings.autocorrectUndoMemory
                suggestionEngine?.reranker = resolveReranker(settings)
                suggestionEngine?.blacklist = settings.suggestionBlacklist
                purgeBlacklisted(settings.suggestionBlacklist)
                suggestionEngine?.blockOffensiveWords =
                    settings.suggestionStrip.blockOffensiveWords
                suggestionEngine?.skipAllCapsAutocorrect = settings.autocorrectSkipAllCaps
                suggestionEngine?.learnedWordMinCount =
                    settings.suggestionStrip.learnedWordMinCount
                emojiUsage.maxRecents = settings.emoji.recentsLimit
                // The snippet index partitions on this — which snippets rewrite
                // text and which only offer to — so the store has to be told
                // before the first trigger fires, not when a chip is drawn.
                snippetStore.setMultiExpandMode(settings.suggestionStrip.snippetMultiExpand)
                suggestionEngine?.autocorrectSplits = settings.suggestionStrip.autocorrectSplits
                suggestionEngine?.digitSlipCorrections =
                    settings.numberRow && settings.suggestionStrip.numberRowCorrections
                pushRegister(settings)
                // Chinese Pinyin options the composer reads at call time (it stays a
                // parameter-less singleton). Pushed from the same block, like above.
                CjkConfig.fuzzyPinyin = settings.cjk.pinyinFuzzy
                CjkConfig.fuzzyPinyinPairs = settings.cjk.pinyinFuzzyPairs
                CjkConfig.doublePinyin = settings.cjk.pinyinDoublePinyin
                CjkConfig.traditionalOutput = settings.cjk.traditionalOutput
                CjkConfig.lazyJyutping = settings.cjk.jyutpingLazy
                HanVariant.region = settings.cjk.hanRegion
                // The settings half of the learning gate; the per-field half
                // (incognito, fields that forbid typing intelligence) is checked
                // at the commit itself, where the field is known.
                CjkLearning.enabled = settings.learnFromTyping
                // Learning switched off — or incognito switched on with it
                // paused — throws away the words waiting to settle as well as
                // stopping new ones. They were typed while it was allowed, but
                // "stop learning from me" means the queue too, and none of it
                // has been written anywhere yet.
                if (!settings.learnFromTyping ||
                    (settings.incognito && settings.incognitoPausesLearning)
                ) {
                    learningBuffer.clear()
                    clearLearnOffer()
                }
                // Adding or removing Bengali changes what the engine was built
                // with, not just what it looks up, so it is rebuilt rather than
                // patched. Switching the spelling map counts for the same
                // reason — it is a constructor argument too. Only fires on an
                // actual change.
                if (suggestionEngine != null &&
                    (bengaliEnabled() != loadedBengali || spellingMapEnabled() != loadedSpellingMap)
                ) {
                    loadDictionariesAndEmoji()
                }
                // Switching a language to its imported lists alone (#28)
                // changes which tries the engine is built over, so it needs the
                // same rebuild — and the imported-list map too, which is where
                // every language but English and Bengali keeps its download.
                if (suggestionEngine != null && importedOnly() != loadedImportedOnly) {
                    withContext(Dispatchers.Default) {
                        customDictionaries = loadCustomDictionaries()
                    }
                    loadDictionariesAndEmoji()
                }
                // How much of the dictionary a swipe may answer with. Cheap to
                // set — the engine ignores a value it already has.
                suggestionEngine?.glideVocabularyRank = settings.gesture.vocabulary.rank
                // The offensive-word filter is per language and reads only the
                // enabled ones, so switching a language on has to widen it.
                // Cheap enough to do here rather than through a full reload:
                // one asset read per language, off the main thread.
                val offensiveLangs = enabledLanguageIds()
                if (suggestionEngine != null && offensiveLangs != loadedOffensiveLangs) {
                    val widened = withContext(Dispatchers.Default) {
                        readOffensiveWords(offensiveLangs)
                    }
                    suggestionEngine?.offensiveWords = widened
                    loadedOffensiveLangs = offensiveLangs
                }
                // Only English drives the bundled English word list; every other
                // language (with no bundled dictionary) drops it so autocorrect
                // and completions never offer English for their words. Bengali
                // routes through its own transliteration path either way.
                suggestionEngine?.englishSources = activeLang.isEnglish
                // Imported word lists are per language, so the active one
                // follows the mode: a French list never reaches English.
                if (customDictVersion != -1 && settings.customDictVersion != customDictVersion) {
                    withContext(Dispatchers.Default) {
                        customDictionaries = loadCustomDictionaries()
                        suggestionEngine?.bengaliIndex = buildBengaliIndex()
                    }
                }
                customDictVersion = settings.customDictVersion
                // Same trick for imported emoji keyword packs: the settings app
                // bumps a counter, and the merged catalog is rebuilt here.
                if (emojiPackVersion != -1 &&
                    settings.emoji.keywordPackVersion != emojiPackVersion
                ) {
                    reloadEmojiCatalog()
                }
                emojiPackVersion = settings.emoji.keywordPackVersion
                // A language's emoji keywords switched off or on (issue #51):
                // the merge is what applies it, so the catalogue is rebuilt.
                if (suggestionEngine != null &&
                    settings.emoji.disabledKeywordLangs != loadedEmojiKeywordsOff
                ) {
                    reloadEmojiCatalog()
                }
                // And again for the emoji history, which a restored backup
                // rewrites under the running keyboard.
                if (emojiUsageVersion != -1 && settings.emoji.usageVersion != emojiUsageVersion) {
                    withContext(Dispatchers.Default) { emojiUsage.reload() }
                    publishEmojiHistory(force = true)
                }
                emojiUsageVersion = settings.emoji.usageVersion
                refreshLanguageDataDownloads(settings)
                refreshDictionaryBar(settings)
                suggestionEngine?.primaryLanguageId = activeLang.id
                suggestionEngine?.customDictionary =
                    customDictionaries[activeLang.id] ?: PackedTrie.EMPTY
                suggestionEngine?.ngramPack = loadNgramPack(activeLang.id)
                // Secondary languages feed the strip alongside the primary. English
                // rides its bundled list (englishAsSecondary); every other language
                // its imported list. Each is tagged with its id so its share of the
                // strip adapts to how much the user actually types it.
                val secondaryIds = settings.secondaryLanguages[activeLang.id].orEmpty()
                suggestionEngine?.secondaryDictionaries =
                    secondaryIds.filter { it != "en" }
                        .mapNotNull { id -> customDictionaries[id]?.let { SecondaryDictionary(id, it) } }
                suggestionEngine?.englishAsSecondary =
                    "en" in secondaryIds && !activeLang.isEnglish
                suggestionEngine?.fieldDetectionShift = fieldDetectionShift(settings)
                glideSourcesEpoch.update { it + 1 }
            }
        }

        loadDictionariesAndEmoji()

        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .addPrimaryClipChangedListener(clipboardListener)

        // A code arriving while the keyboard is up raises its chip live — the
        // field the user is staring at is usually the one the code is for.
        serviceScope.launch {
            NotificationOtpBus.latest.collect { otp ->
                if (otp != null) maybeShowOtpSuggestion() else clearOtpSuggestion()
            }
        }

        serviceScope.launch {
            uiState
                .map { it.panel != PanelMode.NONE || it.voice.strip }
                .distinctUntilChanged()
                .collect { open ->
                    updatePanelBackCallback(open)
                    // The same signal restores the input view a shortcut forced
                    // open. One collector, one definition of "something is open",
                    // and it already accounts for the dictation strip.
                    if (!open) releaseForcedInputView()
                }
        }

        // Mirror the torch state so the flashlight tool lights up even when
        // the torch is toggled from outside the keyboard.
        //
        // Finding the camera with a flash means a call into the camera service
        // for every camera on the device, which on a cheap phone is tens of
        // milliseconds of blocked main thread — spent, at keyboard start, to
        // decide how one tool's icon should look. It moves to a worker; the
        // callback still registers on the main thread, which is the looper
        // `null` here asks for. Everything that reads [torchCameraId] already
        // handles it being absent, since a device with no flash never sets it.
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        torchProbe = serviceScope.launch {
            val flashCamera = withContext(Dispatchers.IO) {
                runCatching {
                    cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                }.getOrNull()
            }
            torchCameraId = flashCamera
            if (flashCamera != null) {
                runCatching { cameraManager.registerTorchCallback(torchCallback, null) }
            }
        }
    }

    /**
     * (Re)points the personal stores — learned words, CJK candidate history,
     * language mix, emoji history, clipboard, snippets, sticker packs — at
     * their files.
     *
     * During direct boot every one of them gets a null file, which each store
     * already reads as "in memory, never persisted". So a locked session types
     * with no learned vocabulary, no clipboard history and no snippets, and
     * nothing it does is written anywhere: the in-memory copies are thrown away
     * by [onUserUnlocked], which re-attaches the real files.
     */
    private fun attachPersonalStores() {
        fun store(path: String): File? = if (userUnlocked) File(filesDir, path) else null
        userLexicon = UserLexicon(store("learning/user_lexicon.json"))
        // Its own file for the same reason [CorrectionStats] has one: the
        // settings app rewrites the lexicon wholesale when the user edits their
        // personal dictionary, and half-earned sightings must survive that.
        pendingLearn = PendingLearn(store("learning/pending_learn.json"))
        correctionStats = CorrectionStats(store("learning/correction_stats.json"))
        // The swapped-in store starts on the default level; carry the user's
        // setting across, or it stays at NORMAL until the next settings emit.
        correctionStats.memory = _uiState.value.settings.autocorrectUndoMemory
        suggestionEngine?.correctionStats = correctionStats
        // Its own file, so clearing one store never silently clears the other.
        CjkLearning.store = CjkUserHistory(store("learning/cjk_history.json"))
        languageMixConfidence = LanguageMixConfidence(store("learning/language_mix.json"))
        emojiUsage = EmojiUsage(store("learning/emoji_usage.json")).also {
            it.maxRecents = _uiState.value.settings.emoji.recentsLimit
        }
        // Under stats/, not learning/: "Delete learned words" must not take
        // the typing statistics with it. The enabled gate re-arms on the next
        // settings emission.
        typingStats = TypingStats(store(TypingStats.FILE_PATH)).also {
            it.enabled = typingStats.enabled
        }
        clipboardStore = ClipboardStore(
            store("clipboard/history.json"),
            imagesDir = store("clipboard/images"),
        )
        snippetsFile = store("snippets/snippets.json")
        snippetStore = SnippetStore(snippetsFile)
        snippetsStamp = snippetsFile?.lastModified() ?: 0L
        // A null file here means "in memory, never written", so a locked
        // session records nothing without a check anywhere else.
        aiHistoryStore = AiHistoryStore(store(AiHistoryStore.FILE_PATH))
        // These are process singletons that the settings app shares, so they
        // know about direct boot themselves: [attach] re-points them at
        // filesDir once it exists, and the icon store's revision flow is what
        // makes the keyboard redraw with the user's own icons.
        com.wasimaster.wmkeyboard.core.stickers.StickerPackStore.attach(this)
        com.wasimaster.wmkeyboard.core.icons.IconPackStore.attach(this)
        com.wasimaster.wmkeyboard.core.fonts.FontStore.attach(this)
        com.wasimaster.wmkeyboard.core.feedback.SoundStore.attach(this)
        com.wasimaster.wmkeyboard.core.feedback.SoundPackStore.attach(this)
        com.wasimaster.wmkeyboard.core.addons.AddonStore.attach(this)
        stickerPackStore = com.wasimaster.wmkeyboard.core.stickers.StickerPackStore.get(this)
    }

    /**
     * Credential-encrypted storage just became readable. Swaps the stubbed
     * stores for the real ones, rebuilds the suggestion engine around them
     * (it holds the lexicon by reference), and flips the settings repository
     * back to the real DataStore — which re-emits, so the UI picks up the
     * user's own theme, fonts and full tool set on the next frame.
     */
    private fun onUserUnlocked() {
        if (userUnlocked) return
        userUnlocked = true
        DebugLog.i("ime", "credential storage unlocked; re-attaching personal stores")
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        attachPersonalStores()
        // This process started on the lock screen, so ML Kit's init provider
        // was skipped; now that storage is up it can be initialized by hand.
        MlKitInit.ensure(this)
        // The bundled lists were inflated into device-protected storage, which
        // stays valid; only the user's own lists were unreachable. Forcing the
        // token stale makes the next field focus re-mmap everything.
        loadedDictToken = Int.MIN_VALUE
        loadDictionariesAndEmoji()
        serviceScope.launch { settingsRepository.seedNewDefaultModes() }
        // An enabled-subtype list written before the unlock only lived until
        // this moment, so the switcher would quietly fall back to one language.
        // Forcing the signature stale re-writes it — once now, and again if the
        // settings that just became readable hold a different set of layouts.
        registeredSubtypeSig = null
        registerSubtypes(_uiState.value.settings)
        settingsRepository.onUserUnlocked()
        _uiState.update {
            it.copy(
                clipboardItems = clipboardStore.items(),
                snippets = snippetStore.items(),
                snippetFolders = snippetStore.folders(),
                snippetCandidateCounts = snippetCandidateCounts(),
            )
        }
    }

    /**
     * Dictionaries and the emoji catalog load off the main thread; the
     * keyboard is usable immediately and suggestions appear when ready. The
     * JSON asset layouts load alongside them (idempotent) so a language
     * whose grid is a file resolves once the user switches to it.
     *
     * Run again after a direct-boot unlock ([onUserUnlocked]): the word sources
     * that were unreachable while locked — the downloaded lists, the imported
     * ones — become readable, and the suggestion engine has to be rebuilt
     * around the personal stores that came back with them.
     */
    private fun loadDictionariesAndEmoji() {
        serviceScope.launch {
            val bengaliEnabled = bengaliEnabled()
            loadedBengali = bengaliEnabled
            loadedImportedOnly = importedOnly()
            val loaded = withContext(Dispatchers.Default) {
                AssetLayouts.load(assets)
                // Older installs inflated the bundled lists into
                // credential-encrypted storage; they live in the
                // device-protected area now (see [openLanguageDictionary]).
                if (userUnlocked) {
                    DictionaryStore.deleteLegacyBundled(filesDir)
                    // Downloaded lists written by a superseded .wmdict format
                    // cannot be mapped, so they are pure dead weight — and the
                    // largest thing on disk. Dropping them here is also what
                    // makes Settings offer the language for download again.
                    DictionaryStore.sweepUnreadable(filesDir)
                }
                // Bundled lists ship as compiled .wmdict binaries and are
                // memory-mapped, not parsed: the trie stays out of the Java
                // heap and "loading" is one mmap call. A downloaded list for
                // the same language (bigger, user-chosen size) wins over the
                // bundled copy.
                val english = openLanguageDictionary("en")
                // Bengali's bundled list, its phonetic index and its loanword
                // map only exist to serve Bengali. Someone typing French and
                // Japanese pays ~20k words of mmap and an index build for
                // machinery they will never reach, so it loads with the
                // language and not before.
                val bengali = if (bengaliEnabled) openLanguageDictionary("bn") else null
                val bundled = assets.open("emoji/catalog.tsv").use { EmojiCatalog.load(it) }
                // Imported language packs name the same emoji in a language the
                // bundled catalog has no keywords for, so they are folded in
                // here — before anything indexes the catalog. Locked-boot skips
                // them: they live in credential-encrypted storage.
                val packs = if (userUnlocked) emojiPacks() else emptyList()
                emojiPackNames = EmojiKeywordPack.namesByLanguage(packs)
                Triple(english, bengali, EmojiKeywordPack.merge(bundled, packs))
            }
            val (english, bengali, catalog) = loaded
            // Flat entry lists for the consumers that build their own indexes
            // (gesture lexicon, Bengali phonetic index) — a one-off DFS walk.
            val (englishEntries, bengaliEntries) = withContext(Dispatchers.Default) {
                english?.entries().orEmpty() to bengali?.entries().orEmpty()
            }
            val spellingMapOn = bengaliEnabled &&
                _uiState.value.settings.suggestionStrip.spellingMapEnabledFor("bn")
            loadedSpellingMap = spellingMapOn
            val (loanwords, variants, seedBigrams) = withContext(Dispatchers.Default) {
                // Curated list first: where the two disagree, the hand-written
                // loanword spelling outranks the generated romanized one.
                val lw = if (spellingMapOn) {
                    assets.open("dictionaries/en_bn.tsv").use { en ->
                        assets.open("dictionaries/bn_rom.tsv").use { rom ->
                            BengaliSpellingMap.load(en, rom)
                        }
                    }
                } else {
                    BengaliSpellingMap.EMPTY
                }
                val v = runCatching {
                    assets.open("emoji/variants.tsv").use { EmojiVariantIndex.load(it) }
                }.getOrDefault(EmojiVariantIndex.empty())
                emojiShortcodes = runCatching {
                    assets.open("emoji/shortcodes.tsv").use { EmojiShortcodes.load(it) }
                }.getOrDefault(EmojiShortcodes.EMPTY)
                emojiTriggers = runCatching {
                    assets.open("emoji/triggers.tsv").use { EmojiTriggers.load(it) }
                }.getOrDefault(EmojiTriggers.EMPTY)
                animatedEmoji = runCatching {
                    assets.open("emoji/animated.txt").use { AnimatedEmoji.load(it) }
                }.getOrDefault(AnimatedEmoji.EMPTY)
                val seeds = runCatching {
                    assets.open("dictionaries/en_bigrams.txt").use { SeedBigrams.load(it) }
                }.getOrDefault(SeedBigrams.EMPTY)
                Triple(lw, v, seeds)
            }
            // Personal-dictionary words (#45) and shortcut expansions (A28):
            // two content queries, off the main thread, refreshed each time the
            // dictionaries reload and whenever the platform reports an edit.
            withContext(Dispatchers.Default) { readSystemDictionary() }
            bengaliAssetEntries = bengaliEntries
            val customTries = withContext(Dispatchers.Default) { loadCustomDictionaries() }
            customDictionaries = customTries
            val offensiveLangs = enabledLanguageIds()
            val offensiveSet = withContext(Dispatchers.Default) {
                readOffensiveWords(offensiveLangs)
            }
            loadedOffensiveLangs = offensiveLangs
            // Chinese/Japanese conversion tables (pinyin→Hanzi, kana→Kanji). These
            // assets are optional: an absent or unreadable file leaves the composer
            // typing the raw reading (pinyin letters, or kana) with no candidates.
            loadCjkConversionTables()
            loadedDictToken = withContext(Dispatchers.Default) {
                if (userUnlocked) DictionaryStore.stateToken(filesDir) else Int.MIN_VALUE
            }
            suggestionEngine = SuggestionEngine(
                english ?: PackedTrie.EMPTY,
                buildBengaliIndex(),
                userLexicon,
                loanwords,
                seedBigrams,
                languageMixConfidence,
            ).apply {
                contacts = contactNames
                contactEmails = this@WMKeyboardService.contactEmails
                apps = appNames
                systemWordCases = systemDictionaryWords.shapes
                systemDictionary = systemDictionaryWords.source
                proximity = KeyProximity.forLayout(
                    activeLayoutSpec(_uiState.value.settings),
                    numberRow = _uiState.value.settings.numberRow &&
                        _uiState.value.settings.suggestionStrip.numberRowCorrections,
                )
                autocorrectConfidence =
                    _uiState.value.settings.autocorrectConfidence.toDouble()
                adaptiveConfidence = _uiState.value.settings.autocorrectAdaptive
                correctionStats = this@WMKeyboardService.correctionStats.apply {
                    memory = _uiState.value.settings.autocorrectUndoMemory
                }
                blacklist = _uiState.value.settings.suggestionBlacklist
                offensiveWords = offensiveSet
                blockOffensiveWords = _uiState.value.settings.suggestionStrip.blockOffensiveWords
                skipAllCapsAutocorrect = _uiState.value.settings.autocorrectSkipAllCaps
                learnedWordMinCount =
                    _uiState.value.settings.suggestionStrip.learnedWordMinCount
                autocorrectSplits = _uiState.value.settings.suggestionStrip.autocorrectSplits
                digitSlipCorrections = _uiState.value.settings.numberRow &&
                    _uiState.value.settings.suggestionStrip.numberRowCorrections
                val lang = _uiState.value.language
                englishSources = lang.isEnglish
                primaryLanguageId = lang.id
                customDictionary = customTries[lang.id] ?: PackedTrie.EMPTY
                ngramPack = loadNgramPack(lang.id)
                val secondaryIds = _uiState.value.settings.secondaryLanguages[lang.id].orEmpty()
                secondaryDictionaries = secondaryIds.filter { it != "en" }
                    .mapNotNull { id -> customTries[id]?.let { SecondaryDictionary(id, it) } }
                englishAsSecondary = "en" in secondaryIds && !lang.isEnglish
                fieldDetectionShift = fieldDetectionShift(_uiState.value.settings)
                glideVocabularyRank = _uiState.value.settings.gesture.vocabulary.rank
                ngramReranker = NgramReranker(
                    userLexicon,
                    seedBigrams,
                    // Read-through: dictionary downloads swap the engine's
                    // list at runtime and the reranker must follow.
                    dictionaryFrequency = { word ->
                        suggestionEngine?.dictionary?.frequencyOf(word) ?: 0
                    },
                    ngramPack = { suggestionEngine?.ngramPack ?: NgramPack.EMPTY },
                )
                revisionAdvisor = RevisionAdvisor(
                    userLexicon,
                    seedBigrams,
                    ngramPack = { suggestionEngine?.ngramPack ?: NgramPack.EMPTY },
                )
                reranker = resolveReranker(_uiState.value.settings)
            }
            // Avro's grid is Latin and its output is Bengali, so a swipe over
            // it needs the romanized vocabulary rather than the Bengali one.
            // Empty when neither source is present — the spelling map is a
            // setting the user can turn off, and the romanized word list is a
            // download they may not have — in which case Avro simply does not
            // glide rather than guessing.
            romanizedGlide = withContext(Dispatchers.Default) {
                RomanizedIndex.bengali(
                    spellings = loanwords,
                    phonetic = suggestionEngine?.bengaliIndex ?: buildBengaliIndex(),
                    downloadedRomanized = customTries["bn_rom"] ?: PackedTrie.EMPTY,
                    nativeFrequency = { word ->
                        suggestionEngine?.bengaliIndex?.frequencyOf(word) ?: 0
                    },
                )
            }
            // A new engine means new word sources; re-ask whether this
            // language and layout can be glided.
            glideSourcesEpoch.update { it + 1 }
            emojiEntries = catalog
            emojiSearch = EmojiSearch(catalog, emojiShortcodes)
            emojiSuggester = EmojiSuggester(catalog, emojiTriggers, emojiShortcodes)
            _uiState.update {
                it.copy(
                    emojiRecents = emojiUsage.recents(),
                    emojiFrequents = emojiUsage.frequents(),
                    emojiFavourites = emojiUsage.favourites(),
                    emojiVariantPrefs = emojiUsage.variantPrefs(),
                    emojiCatalog = catalog,
                    emojiNamesByLang = emojiPackNames,
                    emojiVariants = variants,
                    animatedEmoji = animatedEmoji,
                )
            }
            // The catalog is what the hidden-emoji check runs over; now that it
            // is loaded, populate the set if the feature is already on.
            recomputeHiddenEmoji(_uiState.value.settings)
        }
    }

    /**
     * Rebuilds the emoji catalog and everything indexed off it, after a
     * keyword pack was imported, downloaded or removed.
     *
     * Kept separate from [loadDictionaries] because it is a different order of
     * cost: this is one TSV parse plus the packs, where a dictionary reload
     * re-opens memory-mapped word lists and rebuilds the tries behind them.
     */
    /**
     * Every emoji keyword pack on the device: the downloaded dictionaries
     * first, the user's own imports after.
     *
     * Order matters within one language — the last pack naming an emoji wins
     * — and an import is a deliberate act where a download is automatic, so
     * the import is the one that gets to override.
     */
    private fun emojiPacks(): List<EmojiKeywordPack> {
        // A language switched off in settings or on the dictionary bar (issue
        // #51) keeps its files and loses its say: left out here, before the
        // merge, so neither search nor suggestions see its keywords.
        val emoji = _uiState.value.settings.emoji
        loadedEmojiKeywordsOff = emoji.disabledKeywordLangs
        val packs = EmojiDictStore.loadAll(filesDir) +
            EmojiKeywordPacks.languages(filesDir).flatMap { EmojiKeywordPacks.load(filesDir, it) }
        return packs.filter { pack -> pack.langId?.let { emoji.keywordsEnabledFor(it) } ?: true }
    }

    /**
     * Fetches the data an enabled language is missing — its emoji dictionary,
     * so emoji search answers in the language being typed rather than only in
     * English, and its n-gram pack, so predictions know which word follows
     * which.
     *
     * Runs off the enabled-language set rather than a one-shot at startup:
     * that covers both "the user just added Tamil" and "this install predates
     * the feature". The managers themselves skip what is already on disk and
     * remember failures, so calling this on every settings emission is free
     * after the first pass.
     *
     * Both halves are behind [KeyboardSettings.autoDownloadLanguageData]: with
     * it off nothing arrives unless the user asked for it, either on the
     * prompt shown as the language was added or from the rows under Settings ›
     * Languages. Word lists are never fetched here at all — they are the big
     * ones, and they stay a deliberate choice.
     */
    private fun refreshLanguageDataDownloads(settings: KeyboardSettings) {
        if (!userUnlocked || !settings.autoDownloadLanguageData) return
        val languages = settings.enabledLanguages.map { it.id }
        if (settings.emoji.autoDownloadKeywords) {
            EmojiDictDownloadManager.ensure(filesDir, languages)
        }
        NgramPackDownloadManager.ensure(filesDir, languages)
    }

    private suspend fun reloadEmojiCatalog() {
        if (!userUnlocked) return
        // IO, not Default: this reads an asset and walks the packs folder, and
        // the merge afterwards is a map over two thousand entries — nothing
        // that wants a CPU-bound thread taken away from the composer.
        val catalog = withContext(Dispatchers.IO) {
            runCatching {
                val bundled = assets.open("emoji/catalog.tsv").use { EmojiCatalog.load(it) }
                val packs = emojiPacks()
                emojiPackNames = EmojiKeywordPack.namesByLanguage(packs)
                EmojiKeywordPack.merge(bundled, packs)
            }.getOrNull()
        } ?: return
        emojiEntries = catalog
        emojiSearch = EmojiSearch(catalog, emojiShortcodes)
        emojiSuggester = EmojiSuggester(catalog, emojiTriggers, emojiShortcodes)
        _uiState.update { it.copy(emojiCatalog = catalog, emojiNamesByLang = emojiPackNames) }
        recomputeHiddenEmoji(_uiState.value.settings)
    }

    /**
     * The root input view, kept so [doVibrate] can route the SYSTEM_* haptic
     * styles through `View.performHapticFeedback` (the platform's tuned key
     * click). Cleared when the input view goes away.
     */
    private var inputRootView: View? = null

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
        inputRootView = view
        lifecycleOwner.attachTo(requireNotNull(window.window).decorView)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // A named composable, not an inline lambda: the argument list below
        // compiles to one method, and inside setContent's lambda it crossed
        // the JVM's 64K method-size ceiling.
        view.setContent { ServiceKeyboardContent() }
        return view
    }

    @androidx.compose.runtime.Composable
    private fun ServiceKeyboardContent() {
            KeyboardScreen(
                stateFlow = uiState,
                panelFocus = panelFocus,
                onKey = ::onKey,
                onKeyPressed = ::vibrate,
                onHaptic = ::vibrateOnly,
                onKeySound = { role, phase -> playKeySound(role = role, phase = phase) },
                onText = ::onText,
                onGesture = ::onGesture,
                onGesturePreview = ::onGesturePreview,
                onGestureWords = ::onGestureWords,
                onKeyTouch = ::onKeyTouch,
                onTouchKeys = ::onTouchKeys,
                onCursorMove = ::onCursorMove,
                onCursorMoveVertical = ::onCursorMoveVertical,
                onLayoutSelect = ::onLayoutSelected,
                onClipboardKey = ::onClipboardKey,
                canDelete = ::canDelete,
                canDeleteField = ::canDeleteField,
                canForwardDelete = ::canForwardDelete,
                deleteSwipe = deleteSwipeCallbacks,
                onSuggestion = ::onSuggestionTapped,
                onSuggestionHold = ::onSuggestionHeld,
                onJoinSuggestion = ::onJoinSuggestionTapped,
                onRevisionSuggestion = ::onRevisionSuggestionTapped,
                onCandidate = ::onCandidateTapped,
                onCandidatesExpand = ::onCandidatesExpand,
                onEmoji = ::onEmojiTapped,
                onEmojiVariant = ::onEmojiVariantPicked,
                onEmojiFavourite = ::onEmojiFavouriteToggled,
                onEmojiSuggestion = ::onEmojiSuggestionTapped,
                onPunctuation = ::onPunctuationSuggestionTapped,
                onEmojiQueryTap = ::onEmojiSearchToggled,
                onEmojiRecentsClear = ::onEmojiRecentsClear,
                onEmojiRecentRemove = ::onEmojiRecentRemoved,
                onEmojiFavouritesReorder = ::onEmojiFavouritesReordered,
                onEmojiLongPress = ::onEmojiLongPressed,
                onEmojiLongPressEnd = ::onEmojiLongPressDismissed,
                onAnimatedEmojiSend = ::onAnimatedEmojiSend,
                onEmojiStickerSend = ::onEmojiStickerSend,
                onEmojiSearchFieldDelete = ::onEmojiSearchFieldDelete,
                onEmojiRowShown = { publishEmojiHistory() },
                onTextArt = ::onTextArtTapped,
                onToolTap = ::onToolTap,
                onPanelChange = ::onPanelChange,
                onClipboardItem = ::onClipboardItemTapped,
                onClipboardSticker = ::onClipboardSticker,
                onClipboardPin = ::onClipboardPin,
                onClipboardDelete = ::onClipboardDelete,
                onClipboardSearchToggle = ::onClipboardSearchToggle,
                onClipboardSuggestionDismiss = ::onClipboardSuggestionDismiss,
                onClipboardEntity = ::onClipboardEntityTapped,
                onOtpAccept = ::onOtpSuggestionTapped,
                onOtpDismiss = ::onOtpSuggestionDismiss,
                snippetPanel = snippetPanelCallbacks,
                onOneHanded = ::onOneHandedChange,
                onOneHandedSide = ::onOneHandedSideChange,
                onFloatingChange = ::onFloatingChange,
                onFloatingMoved = ::onFloatingMoved,
                onSizingAction = ::onSizingAction,
                onFloatingBounds = ::onFloatingBounds,
                onToolbarToolsChange = ::onToolbarToolsChange,
                onToolboxOrderChange = ::onToolboxOrderChange,
                toolHold = toolHoldCallbacks,
                onToolboxHintDismiss = {
                    serviceScope.launch { settingsRepository.setToolboxHintDismissed(true) }
                },
                onWeatherRefresh = { refreshWeather(force = true) },
                onCameraSend = ::onCameraSend,
                onCameraPermissionRequest = ::onCameraPermissionRequest,
                onCalendarPermissionRequest = ::onCalendarPermissionRequest,
                onScannedInsert = ::onScannedTextInsert,
                onScannedUrlOpen = ::onScannedUrlOpen,
                onVoiceToggle = ::onVoiceToggle,
                onVoicePermissionRequest = ::onVoicePermissionRequest,
                onVoiceUndo = ::onVoiceUndo,
                onVoiceModelDownload = ::onVoiceModelDownload,
                onWhisperTranslateToggle = ::onWhisperTranslateToggle,
                onOpenVoiceSettings = ::onOpenVoiceSettings,
                onVoiceUseSystemEngine = ::onVoiceUseSystemEngine,
                onVoiceRailKey = ::onVoiceRailKey,
                onMediaPlayPause = ::onMediaPlayPause,
                onMediaNext = ::onMediaNext,
                onMediaPrevious = ::onMediaPrevious,
                onMediaSeek = ::onMediaSeek,
                onMediaAccessRequest = ::onMediaAccessRequest,
                onMediaResume = ::onMediaResume,
                onDictionaryLookup = ::onDictionaryLookup,
                onDictionarySearchToggle = ::onDictionarySearchToggle,
                onDictionaryInsert = ::onDictionaryInsert,
                onThemeSelect = ::onThemeSelect,
                onIconPackSelect = ::onIconPackSelect,
                onSoundHaptic = ::onSoundHaptic,
                onHandwritingStroke = ::onHandwritingStroke,
                onKeyboardHandwritingStroke = ::onKeyboardHandwritingStroke,
                onHandwritingUndo = ::onHandwritingUndo,
                onHandwritingDownload = ::onHandwritingDownload,
                onMediaQueryTap = ::onMediaQueryTap,
                onMediaRetry = ::onMediaRetry,
                onGifSelect = ::onGifSelect,
                onGifSourceSelect = ::onGifSourceSelect,
                onGifCategorySelect = ::onGifCategorySelect,
                onMediaLongPress = ::onMediaLongPress,
                onStickerPackFilter = ::onStickerPackFilter,
                onStickerSaveToPack = ::onStickerSaveToPack,
                onMediaCopy = ::onMediaCopy,
                onMediaReport = ::onMediaReport,
                onMediaActionDismiss = ::onMediaActionDismiss,
                onWebResult = ::onWebResultSelect,
                onWebResultOpen = ::onWebResultOpen,
                onImageResult = ::onImageResultSelect,
                onImageResultLink = ::onImageResultLink,
                onTranslateTarget = ::onTranslateTargetChange,
                onTranslateReplace = ::onTranslateReplace,
                onTranslateInsert = ::onTranslateInsert,
                onGrammarFix = ::onGrammarFix,
                onGrammarFixAll = ::onGrammarFixAll,
                onGrammarDismiss = ::onGrammarDismiss,
                onGrammarDialect = ::onGrammarDialectChange,
                onGrammarFocus = ::onGrammarFocus,
                onWikiOpen = ::onWikiOpen,
                onWikiBack = ::onWikiBack,
                onWikiLoadLinks = ::onWikiLoadLinks,
                onWikiLoadFull = ::onWikiLoadFull,
                onSymbolInsert = ::onSymbolInsert,
                onSymbolSetSelect = ::onSymbolSetSelect,
                onFancyStyleSelect = ::onFancyStyleSelect,
                onModeSelect = ::onModeSelect,
                onToolInsert = ::onToolTextInsert,
                converter = converterCallbacks,
                onPwSetting = ::onPwSetting,
                onTypingTestAction = ::onTypingTestAction,
                onQrSend = ::onQrSend,
                onAiAction = ::onAiAction,
                onAiReplace = ::onAiReplace,
                onAiInsert = ::onAiInsert,
                onAiRetry = ::onAiRetry,
                onAiRunCustom = ::onAiRunCustom,
                onAiPickModel = ::onAiPickModel,
                onAiToggleStripMarkdown = ::onAiToggleStripMarkdown,
                onAiSetShowDiff = ::onAiSetShowDiff,
                onAiReport = ::onAiReport,
                onOpenToolSettings = ::openToolSettings,
                onOpenRoute = ::openRoute,
                onPluginOpen = ::onPluginOpen,
                onPluginBack = ::onPluginBack,
                onPluginEvent = ::onPluginEvent,
                onPluginInputFocus = ::onPluginInputFocus,
                onPluginPaste = ::onPluginPaste,
                onPluginCopy = ::onPluginCopy,
                launcher = launcherCallbacks,
                onDismissInlineSuggestions = ::onDismissInlineSuggestions,
                onPickerDismiss = ::dismissHardwareOverlay,
                onSmartAccept = ::onSmartSuggestionTapped,
                onSmartOpen = ::onSmartSuggestionOpen,
                onStripOfferAction = ::onStripOfferAction,
                onToolPrefillConsumed = ::onToolPrefillConsumed,
                onHideKeyboard = ::onHideKeyboard,
            )
    }

    // ---- floating mode ----

    /** Panel bounds in IME-window coordinates, for the touchable region. */
    private var floatingPanelBounds: android.graphics.Rect? = null

    fun onFloatingBounds(bounds: IntRect) {
        val rect = android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (rect != floatingPanelBounds) {
            floatingPanelBounds = rect
            // Insets are only re-queried on a window layout pass; the panel
            // can move without the view tree changing size, so force one.
            window?.window?.decorView?.requestLayout()
        }
    }

    fun onFloatingChange(enabled: Boolean) {
        vibrate()
        if (!enabled) floatingPanelBounds = null
        serviceScope.launch { settingsRepository.setFloatingKeyboard(enabled) }
    }

    fun onFloatingMoved(xFraction: Float, yFraction: Float) {
        serviceScope.launch { settingsRepository.setFloatingPosition(xFraction, yFraction) }
    }

    /**
     * Every geometry change the UI hands back: the floating grip and the
     * inline resize tool share this slot (see [SizingAction] for why one slot).
     */
    fun onSizingAction(action: SizingAction) {
        when (action) {
            is SizingAction.Floating -> serviceScope.launch {
                settingsRepository.setFloatingSize(action.widthDp, action.heightScale)
            }
            is SizingAction.ResizeCommit -> {
                serviceScope.launch {
                    settingsRepository.setVariantSizing(
                        variant = action.variant,
                        keyHeightDp = action.keyHeightDp,
                        numberRowHeightDp = action.numberRowHeightDp,
                        bottomPaddingDp = action.bottomPaddingDp,
                        sidePadLeftScale = action.sidePadLeftScale,
                        sidePadRightScale = action.sidePadRightScale,
                    )
                }
                _uiState.update { it.copy(resize = false) }
            }
            SizingAction.ResizeCancel -> _uiState.update { it.copy(resize = false) }
        }
    }

    /**
     * Floating mode: the compose root covers the whole IME window, but the
     * app behind must neither resize nor lose touches. Content insets say
     * "the keyboard occupies nothing"; the touchable region shrinks to the
     * floating panel so all other touches pass through.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // The collapsed voice bar wins over floating mode because it is what
        // the screen actually shows — KeyboardScreen branches to it first.
        if (voiceBarShowing()) {
            val decorHeight = window?.window?.decorView?.height ?: return
            val bounds = voiceBarBounds
            // A horizontal bar resting at the bottom lets the app resize to
            // just above it, keeping the text field in view while dictating.
            // A bar dragged anywhere else floats over a full-sized app.
            // Judged from the published rectangle, not the stored bias — the
            // rectangle is always current, the bias is a DataStore round
            // trip behind a drag.
            val docked = bounds != null && !_uiState.value.settings.voiceBar.vertical &&
                bounds.bottom >= decorHeight - voiceBarDockSlopPx()
            val topInset = if (docked && bounds != null) bounds.top else decorHeight
            outInsets.contentTopInsets = topInset
            outInsets.visibleTopInsets = topInset
            // Until the bar has published a rectangle the whole window stays
            // touchable — an empty region would make the bar itself untappable.
            if (bounds != null) {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                outInsets.touchableRegion.setEmpty()
                outInsets.touchableRegion.set(bounds)
            }
            return
        }
        if (!_uiState.value.settings.floatingKeyboard) return
        val decorHeight = window?.window?.decorView?.height ?: return
        outInsets.contentTopInsets = decorHeight
        outInsets.visibleTopInsets = decorHeight
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        floatingPanelBounds?.let { outInsets.touchableRegion.set(it) }
    }

    /**
     * How close to the window's bottom the horizontal bar must sit to count
     * as docked (nav bar plus margin, generously): everything below the
     * gesture area reads as "at the bottom".
     */
    private fun voiceBarDockSlopPx(): Int =
        (VOICE_BAR_DOCK_SLOP_DP * resources.displayMetrics.density).toInt()

    /** Never use the fullscreen (extract) editor while floating or collapsed to the bar. */
    override fun onEvaluateFullscreenMode(): Boolean =
        if (_uiState.value.settings.floatingKeyboard || voiceBarShowing()) {
            false
        } else {
            super.onEvaluateFullscreenMode()
        }

    /** A physical keyboard is attached and not folded away. */
    private fun hasHardwareKeyboard(): Boolean {
        val config = resources.configuration
        return config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    }

    /**
     * Keep the input view on screen even with a hardware keyboard when the
     * user wants the toolbar-only view — otherwise the platform hides it, and
     * the toolbar (with the keys gated off in Compose) would never show.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        // A physical-keyboard shortcut asked for a tool, so there has to be
        // somewhere to draw it — even though a hardware keyboard would normally
        // mean no input view at all. Dropped again when the tool closes.
        if (forcedInputView) return true
        val toolbar = _uiState.value.settings.toolbarBehavior
        // Nothing to force-show when the toolbar itself is off — that would be a
        // blank sliver (no toolbar, and the keys are gated off too).
        if (toolbar.enabled && toolbar.onlyWithHardwareKeyboard && hasHardwareKeyboard()) {
            return true
        }
        return super.onEvaluateInputViewShown()
    }

    /**
     * The screen size the keyboard is drawing on, as a flow so it can join the
     * settings pipeline rather than being read at each use site.
     *
     * Lives here rather than in [SettingsRepository] because the repository is
     * constructed ad hoc in a dozen places with no shared lifecycle — a
     * ComponentCallbacks registered there would leak one per construction — and
     * because its preference mapping deliberately touches no Context so it can
     * run off the main thread.
     *
     * Lazy, and it must stay lazy: a service is constructed before
     * attachBaseContext runs, so a plain initialiser here reads `resources`
     * through a ContextWrapper whose base is still null and takes the whole
     * keyboard down with an NPE in <init> — the IME never starts at all. Every
     * reader below runs after onCreate, so the first touch is always safe.
     */
    private val deviceForm by lazy {
        MutableStateFlow(DeviceForm.of(resources.configuration.smallestScreenWidthDp))
    }

    /** Docking or undocking a hardware keyboard flips the toolbar-only view. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshHardwareKeyboardState()
        // Folding, unfolding, or moving to another display. Rotation never
        // reaches here in a way that matters: smallestScreenWidthDp is the
        // smaller dimension either way up, which is the whole point of reading
        // it rather than the current width.
        deviceForm.value = DeviceForm.of(newConfig.smallestScreenWidthDp)
        // Any configuration change can swap the ScreenVariant out from under
        // an open resize session, and its preview would then belong to the
        // wrong variant. Drop the session; nothing was persisted.
        if (_uiState.value.resize) {
            _uiState.update { it.copy(resize = false) }
        }
    }

    /** Push the current hardware-keyboard presence into the UI state. */
    private fun refreshHardwareKeyboardState() {
        val present = hasHardwareKeyboard()
        if (present != _uiState.value.hardwareKeyboardPresent) {
            _uiState.update { it.copy(hardwareKeyboardPresent = present) }
        }
    }

    /**
     * Clipboard data is credential-protected and must not be exposed through
     * the IME while either direct boot or the keyguard is active.
     */
    private fun isClipboardAccessible(): Boolean =
        userUnlocked && !isDeviceLocked()

    /**
     * True while the device lock screen (keyguard) is showing — secure or
     * swipe-only. Read on each field start to drive the "hide toolbar &
     * clipboard on lock screen" privacy setting.
     */
    private fun isDeviceLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Runs for every field even while the soft view stays hidden
        // (hardware-keyboard typing), where onStartInputView never fires:
        // the previous field's cached selection and half-typed word must not
        // leak into this one. `restarting` (same editor, e.g. a programmatic
        // text change) keeps the word being typed — web views restart input
        // liberally and dropping it there would chop words mid-typing.
        expectedSelStart = attribute?.initialSelStart ?: -1
        expectedSelEnd = attribute?.initialSelEnd ?: -1
        if (restarting) {
            // Same field, new connection: the composing span went with the old
            // one. See [reattachComposing] — and note this runs on its own when
            // the soft view is hidden (hardware-keyboard typing), where
            // onStartInputView never fires to do it.
            reattachComposing(expectedSelStart, expectedSelEnd)
        } else {
            composing = StringBuilder()
            previousWord = null
            invalidateRecentWords()
            lastGestureWord = null
            lastRevertible = null
            clearSwapOffer()
            clearCaretWord()
            pendingAutoSpace = false
            pendingPunctuationSpace = false
            pendingWordSpace = false
            // The last field's text is behind us and nobody is going back to
            // edit it, so the unknown words still waiting in it have settled.
            // This is also how a message field that was *sent* gets counted
            // when the app restarts input instead of clearing the text. No
            // correction verification: the editor answering reads is this new
            // field, and its text says nothing about the old one's.
            flushLearningBuffer(verifyCorrections = false)
            // A different field is a different run of typing, and the blocks an
            // undo puts on a correction are scoped to the run that earned them.
            // What should outlive it is in the persisted pair counts by now.
            correctionStats.endFieldSession()
            clearLearnOffer()
            clearCorrectionOffer()
            // A genuinely different field. Whatever a plugin was collecting
            // belonged to the last one, and the keys belong to this one.
            stopPlugins()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Set before the lifecycle is resumed: a panel that reacts to ON_RESUME
        // (the media one re-checks notification access there) would otherwise
        // ask about a keyboard this flag still calls hidden.
        keyboardVisible = true
        lifecycleOwner.onResume()
        // Starts the media-session listener the toolbar's auto-pin needs, and
        // catches music that began while the keyboard was away.
        syncMediaTracking()
        refreshHardwareKeyboardState()
        // The battery level is read here rather than subscribed to: this is the
        // moment it can start mattering, and a keyboard that wakes on every
        // percentage point to decide whether to save power is self-defeating.
        powerSaver.refresh()
        // Same reasoning for the network: the callback may have fired while
        // this process was gone, and this is the moment the answer starts
        // mattering again.
        networkWatcher.refresh()
        // A rotating background moves on at the start of a session and never
        // during one: a photo changing under the keys while somebody is typing
        // reads as a glitch rather than a feature, and an in-session timer
        // would be a leak waiting to happen. The swap itself is a local file
        // read and one preference write, so it costs nothing here.
        maybeRotateBackground()
        // Same rule, same moment, for an auto theme half that selects at random.
        maybeShuffleTheme()
        // Covers the OEMs where `zen_mode` is unreadable and the observer never
        // fires — see refreshDndState.
        refreshDndState()
        expectedSelStart = info?.initialSelStart ?: -1
        expectedSelEnd = info?.initialSelEnd ?: -1
        // A job debounced against the previous field must not land its strip
        // (or commit resolution) on this one — the secure-field gates in
        // refreshSuggestions return before they ever cancel.
        suggestionJob?.cancel()
        smartJob?.cancel()
        gestureJob?.cancel()
        commitResolution = null
        // `restarting` is the same field reporting itself again — a programmatic
        // text change, or a web view resetting its connection, both of which
        // happen mid-word and neither of which the user did. The word being
        // typed is still in the field, so blanking the buffer here split it in
        // two: the strip lost the prefix and the next space autocorrected the
        // tail on its own ("keyboard" typed through a restart committing as
        // "keybcard"). The restart does end the editor's composing span though,
        // so the buffer has to be re-attached to a fresh one or dropped — kept
        // with no span behind it, the next setComposingText *inserts* it at the
        // caret and the half-typed word appears twice.
        if (restarting) {
            reattachComposing(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        } else {
            composing = StringBuilder()
            previousWord = null
            invalidateRecentWords()
            lastGestureWord = null
            lastRevertible = null
            clearSwapOffer()
            clearCaretWord()
            // The last field's language mix must not color this one; the
            // entry re-read below re-seeds it from this field's own words.
            suggestionEngine?.clearFieldContext()
        }
        // The Fancy tool's automatic pass, run here rather than when the
        // keyboard closed: this is the first moment the work is safe again (the
        // buffer above is settled, the mic is released, no panel is open), and
        // the user sees the same thing either way — the keyboard comes back
        // typing plainly. A restart is the same field reporting itself, so the
        // request waits for a real new session instead of ending this one.
        if (fancyToolAutoOffPending && !restarting) {
            fancyToolAutoOffPending = false
            val state = _uiState.value
            if (state.language.id == FancyStyles.LANG_ID) turnFancyOff(state, quiet = true)
        }
        smartMutedAfter = null
        patternMutedAfter = null
        // A new field is a fresh audience for the intent chips; a restart is
        // the same field talking, where a retired hint stays retired.
        if (!restarting) intentChipsRetired.clear()
        // A chip offering to rewrite a span of the last field has no business
        // being up over this one.
        clearSnippetOffer()
        // The settings app edits snippets in the same file this reads, so a
        // pattern added there has to reach the keyboard without waiting for
        // the snippet panel to be opened.
        reloadSnippetsIfChanged()
        resetHardwareKeyState()
        // Covers the permission being granted after the setting was on.
        if (_uiState.value.settings.contactSuggestions && contactNames.isEmpty) {
            loadContactNames()
        }
        if (_uiState.value.settings.contactEmailSuggestions && contactEmails.isEmpty) {
            loadContactEmails()
        }
        // A dictionary downloaded (or deleted) in Settings goes live here, on
        // the next field focus — token-guarded, so this is a cheap no-op when
        // nothing on disk changed.
        serviceScope.launch { reloadDownloadedDictionaries() }
        // A CJK dictionary pack finished (or was deleted) in Settings since the
        // tables were last parsed: reload so it goes live on this focus. The
        // token compare is two file-existence checks — cheap enough per focus,
        // and the re-parse only runs when a pack actually changed.
        if (loadedCjkPackToken != CjkDictStore.stateToken(filesDir)) {
            serviceScope.launch { loadCjkConversionTables() }
        }
        hwJob?.cancel()
        hwGeneration++
        val secure = info.isSecureField()
        val fieldKind = info.fieldKind()
        // Keyboard-mode resolution: a manual pick from the Modes tool lives as
        // long as the user stays in the same app, unless they asked for it to
        // last until they change it. Clearing on the next app was right for a
        // mode picked in passing and wrong for one picked deliberately, and the
        // keyboard could not tell the two apart.
        val pkg = info?.packageName
        val manualSticks = _uiState.value.settings.rows.manualModeDuration ==
            ManualModeDuration.UNTIL_CHANGED
        if (pkg != null && pkg != currentPackage && !manualSticks) manualModeId = null
        // A pick still waiting to be stored belongs to the app it was made in:
        // per-app memory reads a different id in the next app, which the pending
        // one would otherwise outrank for as long as the write took to land.
        if (pkg != null && pkg != currentPackage) pendingLayoutId = null
        if (pkg != null) currentPackage = pkg
        refreshPerAppContext()
        currentModeFields = buildSet {
            if (secure) add(ModeField.PASSWORD)
            when (fieldKind) {
                FieldKind.EMAIL -> add(ModeField.EMAIL)
                FieldKind.URI -> add(ModeField.URL)
                FieldKind.NUMBER -> add(ModeField.NUMBER)
                FieldKind.PHONE -> add(ModeField.PHONE)
                // Plain prose. A password box reports TEXT too, so it is
                // excluded — modes bound to TEXT (chat composers, document
                // bodies) must never take over a login form.
                FieldKind.TEXT -> if (!secure) {
                    add(ModeField.TEXT)
                    // A text box hosted by the system UI is the inline
                    // notification reply — the one field whose package can
                    // never name the app the user is actually replying to.
                    if (pkg in notificationShellPackages) {
                        add(ModeField.NOTIFICATION_REPLY)
                    }
                }
                else -> {}
            }
        }
        val base = baseSettings
        // Read the field-detection settings from the base so a per-app mode
        // can't quietly switch either of them off.
        val fieldSettings = base ?: _uiState.value.settings
        // Incognito the field asked for, e.g. a Chrome incognito tab.
        val fieldIncognito = fieldSettings.autoIncognito &&
            info.requestsNoPersonalizedLearning()
        val fieldNoSuggestions =
            info.suppressesSuggestions(fieldSettings.showSuggestionsInAllFields)
        val activeMode = base?.let {
            resolveKeyboardMode(it.keyboardModes, currentPackage, currentModeFields, manualModeId)
        }
        // Language the field asks for, layered over the base for this app (the
        // per-app remembered layout when that is on, else the global pick).
        // FORCE_ASCII is a hard constraint (the app cannot store what a Bengali
        // mode types) so it outranks a hintLocales preference, which is only ever
        // advisory. Both are compared against — and fall back to — that base.
        val current = base ?: _uiState.value.settings
        val baseSpec = resolveLayout(current.customLayouts, baseLayoutId(current))
        fieldLayoutOverride = when {
            info.forcesAscii() && baseSpec.script().id != ScriptId.LATIN ->
                current.enabledLayoutIds.firstOrNull {
                    resolveLayout(current.customLayouts, it).script().id == ScriptId.LATIN
                } ?: BuiltInLayouts.DEFAULT_ID
            // hintLocales names a language, not a layout, so it picks the first
            // enabled layout that types that language.
            else -> info.hintedLanguage(current.enabledLanguages)
                ?.takeIf { it.id != baseSpec.language().id }
                ?.let { hinted ->
                    current.enabledLayoutIds.firstOrNull {
                        resolveLayout(current.customLayouts, it).language().id == hinted.id
                    }
                }
        }
        val fieldSpec = activeLayoutSpec(current)
        val deviceLocked = isDeviceLocked()
        val clipboardAccessible = userUnlocked && !deviceLocked
        // A field switch closes any open panel below; a GIF/sticker search
        // that was up survives it for the next open.
        stashMediaSearch(_uiState.value)
        // Hoisted out of the copy below so the grid expands against the same
        // digit-row answer the renderer draws with — on a tablet that row is
        // where backspace lives.
        val modeSettings = base?.applyMode(activeMode) ?: _uiState.value.settings
        // Keeps the rule engine paired with the grid on screen: a
        // language switch must not leave the old keyboard's rules
        // running against the new grid.
        syncKeymanSession(fieldSpec)
        _uiState.update {
            it.copy(
                settings = modeSettings,
                language = fieldSpec.language(),
                script = fieldSpec.script(),
                composer = composerFor(fieldSpec.script(), fieldSpec.composerType()),
                // A locked Ctrl crossing an app boundary is the worst failure
                // this feature can have: every letter after it becomes a
                // shortcut in an app the user never armed it for.
                modifiers = Modifiers.None,
                layoutId = fieldSpec.id,
                layoutName = fieldSpec.name,
                layouts = resolveLayoutSet(
                    fieldSpec,
                    fieldKind,
                    deviceForm.value,
                    modeSettings.numberRow,
                    modeSettings.customLayouts,
                ),
                activeModeId = activeMode?.id,
                activeSymbolSetId = null,
                // Harmless reset: the strip persists the pick on tap, so the
                // persisted style takes over seamlessly on the next field.
                activeFancyStyleId = null,
                panel = PanelMode.NONE,
                // A fresh field starts on the letter layer; a restart of the
                // same field keeps whatever layer the user was on. So does a
                // layer its author marked persistent (issue #60): that one
                // stays until a key or a tool takes the user off it, and a
                // close-and-reopen or a change of app is neither. Judged on
                // the grid that *was* showing, before the set is swapped.
                layoutMode = if (restarting || currentLayout(it).persistent) {
                    it.layoutMode
                } else {
                    LayoutMode.LETTERS
                },
                // Selection mode belongs to the text it was armed over. A
                // restart is the same field reporting itself (a programmatic
                // edit, a web view resetting its connection), where the mode is
                // still about the text the user is working on, so it stays.
                selectionMode = if (restarting) it.selectionMode else false,
                // The finger is not on the tool any more either way: this runs
                // between sessions, not during a hold.
                selectionHold = false,
                fieldKind = fieldKind,
                fieldNoSuggestions = fieldNoSuggestions,
                fieldIncognito = fieldIncognito,
                emojiSearchActive = false,
                emojiQuery = "",
                dictionarySearchActive = false,
                clipboardSearchActive = false,
                clipboardQuery = "",
                mediaSearchActive = false,
                mediaQuery = "",
                mediaDownloadingId = null,
                mediaDownloadProgress = null,
                launcherDetail = null,
                // A run belongs to the field it was started over; moving to
                // another one abandons it rather than resuming half-typed.
                typingTest = TypingTestUi(),
                translate = TranslateUi(),
                grammar = GrammarUi(available = grammarAvailable || grammarProbePending()),
                composingPreview = "",
                suggestions = emptyList(),
                emojiSuggestions = emptyList(),
                morsePending = "",
                // A tool-keyword chip ("wiki") belongs to the field it was
                // typed in; a fresh (often empty) field must not inherit it.
                // onUpdateSelection re-derives it once the new field settles,
                // but that can lag the switch, leaving a stale chip up.
                smart = null,
                // What this editor takes through commitContent, read once here
                // rather than at send time so the media panels can show up
                // front that a GIF has nowhere to land (see [acceptsRichMedia]).
                fieldContentMimeTypes = info
                    ?.let { editorInfo -> EditorInfoCompat.getContentMimeTypes(editorInfo).toList() }
                    .orEmpty(),
                secureField = secure,
                deviceLocked = deviceLocked,
                shiftState = autoCapitalizeShift(),
                shiftPressedByUser = false,
                clipboardItems = if (clipboardAccessible) clipboardStore.items() else emptyList(),
                clipboardSuggestion = if (clipboardAccessible) it.clipboardSuggestion else null,
                enterAction = info.enterAction(),
                enterActionLabel = info?.actionLabel?.toString()?.takeIf { label -> label.isNotBlank() },
                handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false),
                voice = it.voice.copy(
                    status = VoiceStatus.IDLE, partial = "", level = 0f,
                    strip = false, canUndo = false,
                ),
            )
        }
        // Chord and morse state belongs to the field it was typed over.
        resetChordInputs()
        refreshKarContext()
        // Register follows the field: a chat composer and an email body want
        // differently-ranked strips (no-op unless the setting is on).
        pushRegister(_uiState.value.settings)
        // A code captured before this field opened: offer it — or hide it —
        // for the field the keyboard just landed in.
        maybeShowOtpSuggestion()
        // Same question for a code the user copied by hand: a code box is the
        // one field where a sensitive clip is what you came to paste.
        maybeShowCopiedCodeSuggestion()
        // Read the text already sitting around the caret now, on entry. A field
        // that comes back with its text and caret unchanged (returning to a
        // search bar after a search, re-opening a draft) never fires
        // onUpdateSelection, so waiting for it left the keyboard blind to the
        // existing text: no resumed word, no context, and edits fighting a
        // state built for an empty field.
        currentInputConnection?.let { ic ->
            // Same collapsed-caret gate as the onUpdateSelection call site: a
            // range selection has no word to resume.
            if (info != null && info.initialSelStart >= 0 &&
                info.initialSelStart == info.initialSelEnd
            ) {
                restartSuggestionsAtCursor(ic, info.initialSelStart)
            } else {
                syncPreviousWordFromField(ic)
                refreshSuggestions()
            }
        }
        // A fresh field is a fresh view of the emoji row, so taps from the last
        // one can reorder it now (see [publishEmojiHistory]).
        publishEmojiHistory()
        // Pages from a just-finished document scan: the scanner activity
        // ran while the keyboard was down, so they can only insert now,
        // as the target field regains the input connection.
        for (page in DocScanActivity.consumePendingPages()) {
            saveToGalleryIfEnabled(
                page,
                MediaMime.JPEG,
                _uiState.value.settings.docScanSaveToGallery,
                "SCAN",
            )
            commitImageFile(page, MediaMime.JPEG)
        }
        // Fresh field: re-arm the on-keyboard writing hint and check the model
        // up front so the first swipe writes rather than nagging.
        hwModelHintShown = false
        hwKeyboardArmed = keyboardHandwriteActive(_uiState.value)
        if (hwKeyboardArmed) refreshHandwritingStatus()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        expectedSelStart = newSelStart
        expectedSelEnd = newSelEnd
        // Marks the engine's context stale unless this is the echo of its own
        // edit. No text is read here: this runs on every keystroke, and a read
        // would undo what the expected-selection cache exists to save.
        keymanSession?.onSelectionReported(newSelStart, newSelEnd)
        noteCaretForLearning(newSelStart, newSelEnd)
        // Immediate-revert states are only valid at the caret position their
        // commit left behind; see [revertAnchor]. The first update after
        // arming is the commit's own echo and records the anchor, anything
        // that moves the caret afterwards disarms them.
        if (lastRevertible != null || lastGestureWord != null || pendingAutoSpace ||
            pendingWordSpace
        ) {
            val settling = SystemClock.uptimeMillis() - revertArmedAt < REVERT_SETTLE_MS
            fun disarm() {
                lastRevertible = null
                // The chips describe text at a caret position that has just
                // stopped being where the caret is.
                clearSwapOffer()
                lastGestureWord = null
                pendingAutoSpace = false
                pendingPunctuationSpace = false
                pendingWordSpace = false
            }
            when {
                // A range selection is never one of our own commit echoes.
                newSelStart != newSelEnd -> disarm()
                revertAnchor == -1 || settling -> revertAnchor = newSelStart
                newSelStart != revertAnchor -> disarm()
            }
        }
        val wasComposing = composing.isNotEmpty()
        val cursorOutsideCandidates =
            cursorLeftComposingRegion(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // A caret placed *inside* the composing word (a tap mid-word) also ends
        // the composition. Keeping it meant the next keystroke rewrote the
        // whole region via setComposingText — which snaps the cursor to the
        // word's end, so the tap appeared to not move the cursor at all and
        // the letter landed at the end of the word instead of where it was
        // put. The keyboard's own composing edits always leave the collapsed
        // caret exactly at candidatesEnd, so a caret strictly before it can
        // only be the user's. Dropping composition here lets the fall-through
        // below re-derive the context at the new caret, mid-word taps landing
        // in the no-resume branch of restartSuggestionsAtCursor.
        val cursorInsideCandidates = candidatesStart >= 0 && candidatesEnd > candidatesStart &&
            newSelStart == newSelEnd &&
            newSelStart >= candidatesStart && newSelStart < candidatesEnd

        val composingDropped = wasComposing && (cursorOutsideCandidates || cursorInsideCandidates)
        if (composingDropped) {
            composing = StringBuilder()
            currentInputConnection?.finishComposingText()
            suggestionJob?.cancel()
            _uiState.update { it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList()) }
        }
        // The editor can also finish a composition on its own: a TextWatcher
        // that restyles the text (chat apps marking mentions/markdown) drops
        // the composing span without moving the caret or telling the IME. The
        // buffer then has no region behind it, so the commit on space *inserts*
        // the word instead of replacing it — the word doubles. An update that
        // reports no candidates while a composition is supposedly alive, with
        // the collapsed caret still exactly where it was left, sitting right
        // after text that reads back as the buffer's own output, is that state:
        // quietly re-arm the region. Editors that simply never report
        // candidates re-set the identical region, a no-op. Same readback guard
        // as [reattachComposing]; conversion composers are excluded because
        // their prefix commits manage the region themselves.
        if (!composingDropped && composing.isNotEmpty() && candidatesStart < 0 &&
            newSelStart == newSelEnd && !_uiState.value.composer.isConversion
        ) {
            val ic = currentInputConnection
            val text = composedPreview(_uiState.value, composing.toString())
            if (ic != null && text.isNotEmpty() && newSelStart >= text.length &&
                caretStillAt(ic, newSelStart) &&
                ic.getTextBeforeCursor(text.length, 0)?.toString() == text
            ) {
                ic.setComposingRegion(newSelStart - text.length, newSelStart)
            }
        }
        // Partial dictation results are cumulative per utterance, so they
        // can't follow a cursor jump without duplicating what's already
        // committed — end the session instead, keeping the partial.
        val voiceStatus = _uiState.value.voice.status
        if ((voiceStatus == VoiceStatus.LISTENING || voiceStatus == VoiceStatus.FINISHING) &&
            _uiState.value.voice.partial.isNotEmpty() &&
            cursorOutsideCandidates &&
            // Interactive voice typing puts no partial in the field, so a
            // cursor jump costs it nothing: the next phrase lands wherever
            // the caret now is. Moving the caret mid-sentence is a normal
            // part of using the keys while the microphone is open.
            !interactiveVoice()
        ) {
            cancelVoice()
        }
        refreshShiftForContext()
        refreshKarContext()
        // Tool chips and the word strip both read the text around the cursor, so
        // they have to be re-derived after a cursor jump or an edit made from
        // outside the keyboard, not only after a keystroke. A settled plain
        // caret re-reads the whole context — the word it landed on and the strip
        // — via restartSuggestionsAtCursor (which folds in the chip refresh). An
        // active word still being composed in place, or a range selection being
        // dragged out, only refreshes the chips.
        if ((!wasComposing || composingDropped) && composing.isEmpty() && newSelStart == newSelEnd) {
            currentInputConnection?.let { restartSuggestionsAtCursor(it, newSelStart) }
        } else {
            // The mid-word strip goes the same way for the same reason: a
            // selection dragged out over the word it was about, or a buffer
            // now being typed, is no longer a caret parked inside that word.
            clearCaretWord()
            refreshSmartSuggestion()
            // The snippet chip reads the same text and goes stale the same way
            // — a selection dragged out over the span it names is no longer a
            // caret sitting after it.
            refreshSnippetOffer(_uiState.value)
        }
        // The grammar strip follows the field: any text or cursor change
        // while it is open re-extracts and re-lints (offline, so cheap).
        // Translate deliberately does NOT — it translates its own typed
        // query, never the field.
        if (_uiState.value.panel == PanelMode.GRAMMAR) scheduleGrammarCheck()
        // The AI panel's action chips are enabled by there being text to act
        // on, so they follow the field the same way.
        if (_uiState.value.panel == PanelMode.AI) refreshAiHasText()
    }

    /**
     * Tells the system autofill service how much room the strip has, which
     * is what makes password-manager chips appear there at all.
     *
     * Declining (returning null) is the documented way to opt out, and the
     * cases that decline are the ones where showing saved credentials would
     * be wrong: the feature switched off, or an incognito session, where the
     * user has asked for this typing not to be remembered or surfaced.
     */
    /**
     * Same question as [KeyboardUiState.incognitoOn], asked before the state
     * exists: the platform builds the autofill request during onStartInput,
     * ahead of onStartInputView, so the field flag has to come straight off
     * [currentInputEditorInfo] rather than the cached UI state.
     */
    private fun autofillBlockedByIncognito(): Boolean {
        val settings = _uiState.value.settings
        return settings.incognito ||
            (settings.autoIncognito && currentInputEditorInfo.requestsNoPersonalizedLearning())
    }

    /**
     * How many chips each lane may show right now: credentials from the
     * autofill service first, platform smart replies second. Zero means the
     * lane is switched off, and (0, 0) means nothing may be requested at all.
     *
     * Incognito closes both lanes. The autofill one because credentials for a
     * private session should not be offered; the reply one because a smart
     * reply is the system reading the conversation on screen, which is the
     * same thing incognito exists to stop everywhere else.
     */
    private fun inlineChipBudgets(): Pair<Int, Int> {
        val settings = _uiState.value.settings
        if (autofillBlockedByIncognito()) return 0 to 0
        val autofill = if (settings.inlineAutofill) InlineAutofill.MAX_AUTOFILL_CHIPS else 0
        val platform =
            if (settings.suggestionStrip.systemSmartReplies) InlineAutofill.MAX_PLATFORM_CHIPS else 0
        return autofill to platform
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!InlineAutofill.supported) return null
        val (autofillBudget, platformBudget) = inlineChipBudgets()
        val density = resources.displayMetrics
        val stripHeightPx = (INLINE_CHIP_HEIGHT_DP * density.density).toInt()
        return runCatching {
            InlineAutofill.request(
                uiExtras = uiExtras,
                stripHeightPx = stripHeightPx,
                maxWidthPx = density.widthPixels,
                autofillBudget = autofillBudget,
                platformBudget = platformBudget,
            )
        }.getOrNull()
    }

    /**
     * The answer, mixing the manager's credential chips with whatever the
     * platform decided to send. Returning true claims the suggestions so the
     * platform does not fall back to its own dropdown over the keyboard.
     */
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!InlineAutofill.supported) return false
        val (autofillBudget, platformBudget) = inlineChipBudgets()
        if (autofillBudget + platformBudget == 0) return false
        val lanes = InlineAutofill.split(
            suggestions = response.inlineSuggestions,
            autofillBudget = autofillBudget,
            platformBudget = platformBudget,
        )
        val density = resources.displayMetrics
        InlineAutofill.inflateAll(
            context = this,
            lanes = lanes,
            stripHeightPx = (INLINE_CHIP_HEIGHT_DP * density.density).toInt(),
            maxWidthPx = density.widthPixels,
        ) { chips ->
            _uiState.update {
                it.copy(autofillChips = chips.autofill, smartReplyChips = chips.platform)
            }
        }
        return true
    }

    /**
     * Dismiss chip on the strip: drop them until the next response. Both lanes
     * go, since the ✕ says "stop offering me things about this field" and the
     * two lanes are answering the same one.
     */
    fun onDismissInlineSuggestions() {
        vibrate()
        _uiState.update { it.copy(autofillChips = emptyList(), smartReplyChips = emptyList()) }
    }

    /** The hide-keyboard tool and the toolbar swipe-down: close the keyboard. */
    fun onHideKeyboard() {
        requestHideSelf(0)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardVisible = false
        lifecycleOwner.onPause()
        // The window is gone, so the media-session listener goes with it.
        syncMediaTracking()
        // Refilling the pool waits until the keyboard is going away, so a
        // download can never race the first frame of a session.
        topUpBackgroundPool()
        // The key grid publishes its own pass-through rectangle while it is on
        // screen (see KeyRows); the window going away is the one case Compose
        // can miss, and a stale carve-out would keep a slab of the screen from
        // reaching TalkBack.
        KeyboardPassthrough.publishRegion(null)
        // A word still composing settles into the field as typed. Leaving the
        // editor's region active while our mirror is wiped on the next
        // onStartInputView meant the first keystroke after a hide→reshow
        // replaced the stranded region — deleting the half-typed word.
        if (composing.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            composing = StringBuilder()
            _uiState.update {
                it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
            }
        }
        // A pending morse commit timer must not fire into whatever field the
        // user lands on next; a half-typed chord dies with the view too.
        resetChordInputs()
        // Latches die with the keyboard, locked ones included.
        clearModifiers()
        // So does a held Selection mode: the button went with the window, so no
        // release is coming to end it. The sticky mode survives, the same as
        // every other switched-on tool.
        if (_uiState.value.selectionHold) {
            _uiState.update { it.copy(selectionHold = false) }
        }
        selectionTaps.reset()
        selectKeyTaps.reset()
        // And a held trackpad: no release is coming for it either.
        trackpadHeld = false
        // A backspace swipe caught mid-drag by the window going away: the
        // preview it left in the field is the editor's business now, but the
        // offsets it measured describe a session that has ended.
        resetDeleteSwipe()
        resetHardwareKeyState()
        // An open resize session dies with the view, unsaved by design: Done
        // is the only path that persists.
        if (_uiState.value.resize) {
            _uiState.update { it.copy(resize = false) }
        }
        // The keyboard is leaving the screen, so any plugin drawn on it stops
        // here too -- along with the routing that was sending keys to its box.
        stopPlugins()
        // Credentials — and replies to the conversation — for the field just
        // left must not linger over the next one, which may belong to another
        // app entirely.
        if (_uiState.value.autofillChips.isNotEmpty() ||
            _uiState.value.smartReplyChips.isNotEmpty()
        ) {
            _uiState.update { it.copy(autofillChips = emptyList(), smartReplyChips = emptyList()) }
        }
        // The keyboard is going away mid-dictation: release the mic (the
        // privacy indicator must never outlive the keyboard) and keep the
        // partial that was already on screen.
        cancelVoice()
        // The language the field asked for dies with the field; leaving it
        // set would apply an ASCII lock or a locale hint to whatever the
        // user types in next.
        fieldLayoutOverride = null
        // Fancy Text is a one-off for most people: one nickname, one message.
        // The switch back is only armed here and runs on the next
        // onStartInputView, which is where a layout change is safe to make.
        if (_uiState.value.settings.layoutBehavior.fancyToolAutoOff &&
            fancyToolReturnLayoutId != null
        ) {
            fancyToolAutoOffPending = true
        }
        // The keyboard going away is the plainest "this text is finished"
        // there is, and the one the request for this asked for by name. Before
        // the saves, so anything it promotes lands in the same write.
        clearLearnOffer()
        clearCorrectionOffer()
        flushLearningBuffer()
        userLexicon.save()
        pendingLearn.save()
        correctionStats.save()
        CjkLearning.store?.save()
        languageMixConfidence.save()
        emojiUsage.save()
        typingStats.save()
        if (_uiState.value.settings.flashlightAutoOff && _uiState.value.torchOn) {
            setTorch(false)
        }
    }

    override fun onDestroy() {
        DebugLog.i("ime", "service destroyed")
        pluginRuntime?.shutdown()
        pluginRuntime = null
        KeyboardPassthrough.publishRegion(null)
        powerSaver.stop()
        networkWatcher.stop()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .removePrimaryClipChangedListener(clipboardListener)
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        if (torchCameraId != null) {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .unregisterTorchCallback(torchCallback)
        }
        zenObserver?.let { contentResolver.unregisterContentObserver(it) }
        zenObserver = null
        userDictObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        userDictObserver = null
        // The deferred buzz used to be cancelled by serviceScope.cancel() below;
        // on a Handler it has to be taken off the queue by hand.
        feedbackHandler.removeCallbacks(deferredVibrate)
        flushLearningBuffer()
        userLexicon.save()
        pendingLearn.save()
        correctionStats.save()
        CjkLearning.store?.save()
        emojiUsage.save()
        typingStats.save()
        clipboardStore.save()
        voiceEngine.cancel()
        whisperRecorder?.let { rec -> whisperRecorder = null; runCatching { rec.stop() } }
        mediaController.stop()
        hwRecognizer.close()
        LocalLlmEngine.release()
        WhisperEngine.release()
        serviceScope.cancel()
        // Before the owner is destroyed: super.onDestroy runs a full input
        // teardown, and onFinishInputView pauses the owner on the way out.
        super.onDestroy()
        lifecycleOwner.onDestroy()
    }

    /**
     * Moves a rotating theme on to its next photo, if one is due.
     *
     * Everything here is local: the decision is a pure function over stored
     * timestamps, and applying it is a file that is already on disk plus one
     * preference write. Nothing touches the network, so this is safe at the
     * moment the keyboard is coming up.
     */
    private fun maybeRotateBackground() {
        val current = _uiState.value.settings
        val photos = current.photoBackground
        if (!photos.rotateEnabled || !userUnlocked) return
        val themeId = current.keyboardThemeId
        if (!photos.rotates(themeId, themeId)) return
        val state = PhotoBackgroundManager.rotationStates.value[themeId]
        val due = isRotationDue(
            interval = photos.interval,
            state = state ?: RotationState(),
            nowEpochMs = System.currentTimeMillis(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
            sessionStarted = true,
        )
        if (!due) return
        serviceScope.launch {
            PhotoBackgroundManager.rotate(
                context = this@WMKeyboardService,
                repository = settingsRepository,
                themeId = themeId,
                current = state,
                wantWide = photos.landscapeOnly,
                sources = photos.sources,
            )
        }
    }

    /**
     * Selects the next theme for an auto theme half that runs at random, if one
     * is due.
     *
     * Called at the same moment as [maybeRotateBackground] and for the same
     * reason: the theme changing under the keys mid-sentence reads as a fault,
     * so a random half moves between sessions and never during one. The whole
     * decision is a pure function over two stored clocks, and applying it is one
     * preference write that the theme provider then crossfades.
     */
    private fun maybeShuffleTheme() {
        val auto = _uiState.value.settings.autoTheme
        val due = isThemeShuffleDue(
            auto = auto,
            nowEpochMs = System.currentTimeMillis(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
            sessionStarted = true,
        )
        if (!due) return
        serviceScope.launch { settingsRepository.shuffleAutoThemeNow() }
    }

    /**
     * Recomputes [dataSaverStatus] for one settings or network emission.
     *
     * Grants are dropped whenever the network itself changes, and only then:
     * a yes given on one connection is an answer about that connection, but
     * re-asking every time a settings save happens to re-emit would make the
     * offer worthless.
     */
    private fun updateDataSaverStatus(
        config: DataSaverSettings,
        network: DeviceNetworkState,
        active: Boolean,
    ) {
        val networkChanged = grantedNetwork != network
        grantedNetwork = network
        dataSaverStatus = dataSaverStatus.copy(
            active = active,
            settings = config,
            grants = if (networkChanged) emptySet() else dataSaverStatus.grants,
        )
    }

    /**
     * The user tapped "use mobile data": [feature] is allowed for the rest of
     * this session, and the ui state is republished so the panel that asked
     * stops asking.
     */
    private fun grantMetered(feature: MeteredFeature) {
        if (dataSaverStatus.allows(feature)) return
        dataSaverStatus = dataSaverStatus.granting(feature)
        _uiState.update { it.copy(dataSaver = dataSaverStatus) }
    }

    /**
     * Turns a retry on a data-saver notice into the grant it means.
     *
     * The notice reuses the panels' existing retry action rather than growing
     * a callback of its own: [ui.KeyboardScreen]'s parameter list is already
     * at the JVM's 64K method ceiling, and a retry on a panel that is only
     * empty because of data saving can mean nothing else.
     */
    private fun grantMeteredForPanel(state: KeyboardUiState) {
        val feature = when (state.panel) {
            PanelMode.GIF -> MeteredFeature.MEDIA_SEARCH
                .takeIf { (state.gif as? MediaUi.Metered)?.canAllow == true }
            PanelMode.STICKER -> MeteredFeature.MEDIA_SEARCH
                .takeIf { (state.sticker as? MediaUi.Metered)?.canAllow == true }
            PanelMode.WEB_SEARCH -> MeteredFeature.WEB_SEARCH
                .takeIf { (state.webSearch as? WebSearchUi.Metered)?.canAllow == true }
            PanelMode.IMAGE_SEARCH -> MeteredFeature.WEB_SEARCH
                .takeIf { (state.imageSearch as? ImageSearchUi.Metered)?.canAllow == true }
            else -> null
        } ?: return
        grantMetered(feature)
    }

    /** Refills the rotation pool, if the settings and the network allow it. */
    private fun topUpBackgroundPool() {
        val current = _uiState.value.settings
        val photos = current.photoBackground
        if (!photos.rotateEnabled || !userUnlocked) return
        serviceScope.launch {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            PhotoBackgroundManager.topUpPool(
                context = this@WMKeyboardService,
                settings = photos,
                keys = ToolApiKeys.photoKeys(current),
                sources = ToolApiKeys.photoSources(current),
                conditions = PhotoNetworkConditions(
                    unlocked = userUnlocked,
                    online = true,
                    metered = connectivity?.isActiveNetworkMetered ?: true,
                    powerSaving = current.powerSaving.dropBackgroundNetwork,
                    highContrastKeys = current.highContrastKeys,
                ),
            )
            PhotoBackgroundManager.prunePool(this@WMKeyboardService, photos)
            // Housekeeping rides along with the top-up, which already only
            // happens as the keyboard is going away.
            PhotoBackgroundManager.sweep(
                context = this@WMKeyboardService,
                repository = settingsRepository,
                themes = current.customThemes,
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Decoded background photos: a few MB that the next draw can rebuild
        // from the file. Trimmed at every level, since holding them through an
        // idle hour is part of what gets a keyboard killed.
        BackgroundBitmapCache.trim(level)
        // A cached local model pins hundreds of MB to a few GB — free it the
        // moment the system signals pressure; the next AI action reloads it.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            LocalLlmEngine.release()
            WhisperEngine.release()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BackgroundBitmapCache.evictAll()
    }

    // ---- key handling ----

    // No vibrate() here: press-time haptics fire from the UI's pointer-down
    // callback (onKeyPressed) so feedback lands on touch, not on release.
    fun onKey(key: Key) {
        stopVoiceForManualInput()
        // Typing deliberately does NOT dismiss the recent-copy strip chip. It
        // used to (Gboard style), but the common case is typing a few words and
        // *then* wanting the copy — a username before a pasted password, a
        // sentence before a pasted link. The chip has its own dismiss button and
        // shares the strip with candidates, so it costs nothing to leave up.
        // Shift keeps the gesture word so alternates can be re-cased;
        // Delete keeps it so one backspace can undo the whole swipe.
        if (key.action != KeyAction.Shift && key.action != KeyAction.Delete) {
            lastGestureWord = null
        }
        // The auto-space cancel is a one-shot for the shift press immediately
        // after the ". " — any other key means the user typed on past it.
        if (key.action != KeyAction.Shift) pendingAutoSpace = false
        // Spent by the key that ended the word it was armed for. A commit made
        // from somewhere else — a panel opening, text inserted by a tool — must
        // not leave one behind for whatever key comes next.
        swallowTerminatorAfterCommit = false
        // Three keys have something to say about a just-inserted punctuation
        // space: Space consumes it rather than adding a second one, Shift takes
        // it back, and Text hugs a closing mark to the mark before it — `"hi."`
        // and not `"hi. "` (issue #34). Every other key spends it.
        if (key.action != KeyAction.Shift && key.action != KeyAction.Space &&
            key.action != KeyAction.Text
        ) {
            pendingPunctuationSpace = false
        }
        // The space that ended a word survives exactly two keys: a Text key,
        // where punctuation takes it back and anything else spends it (see
        // [processTypedText]), and Space, which is swallowed rather than
        // doubled. Both of these are cleared by [processTypedText] itself as
        // well, since letters and marks are the same action and only the text
        // says which one this is.
        if (key.action != KeyAction.Text && key.action != KeyAction.Space) {
            pendingWordSpace = false
        }
        // A pending Ctrl/Alt/Meta turns the next key into a shortcut, so it is
        // intercepted ahead of the normal dispatch: KeyAction.Text would
        // otherwise push the letter through the composing buffer and Ctrl+C
        // would type a "c". Modifier keys fall through so Ctrl and Alt can be
        // latched together, and so does Shift, which composes rather than fires.
        val modifiers = _uiState.value.modifiers
        val isShortcut = !modifiers.isEmpty &&
            key.action !is KeyAction.Mod &&
            key.action != KeyAction.Shift
        if (isShortcut) {
            // The result is deliberately ignored: a character with no keycode
            // has no event to send, and the latch is spent either way so the
            // user can see the modifier was used up rather than left armed.
            sendShortcut(key, modifiers)
            consumeModifiers()
            consumeShift()
            return
        }
        // A pending morse sequence commits before any non-morse key acts, so
        // "··· <space>" spells "s " rather than losing the s — the same
        // flush-before contract the composing buffer has. Delete stays out of
        // it: it edits the pending sequence itself (see [onDelete]).
        if (morse.isPending &&
            key.action != KeyAction.MorseDot && key.action != KeyAction.MorseDash &&
            key.action != KeyAction.Delete && key.action != KeyAction.Shift
        ) {
            commitMorse()
        }
        when (key.action) {
            KeyAction.Text -> onTextKey(key)
            // A key of a converted Keyman layout, with no rule engine attached
            // yet. It types its own cap, which is what it would do anyway on a
            // device that has the layout but not the keyboard's rules — the
            // grid stays an ordinary usable keyboard rather than going dead.
            is KeyAction.KeymanKey -> onTextKey(key)
            KeyAction.Shift -> onShift()
            KeyAction.CapsLock -> onCapsLock()
            KeyAction.Delete -> onDelete()
            KeyAction.ForwardDelete -> onForwardDelete()
            KeyAction.Space -> onSpace()
            KeyAction.Enter -> onEnter()
            // The enter key's long-press alternate, and any layout that binds a
            // newline key of its own: a line break, never the field's action.
            KeyAction.Newline -> onNewline()
            KeyAction.Symbols -> toggleSymbols()
            KeyAction.Letters -> _uiState.update {
                it.copy(layoutMode = LayoutMode.LETTERS, fnLocked = false, fnReturn = null)
            }
            KeyAction.LanguageSwitch -> switchLanguage()
            KeyAction.InputMethodPicker -> showInputMethodPicker()
            KeyAction.Emoji -> onPanelChange(PanelMode.EMOJI, haptic = false)
            // Produced only by a long-press on ?123 when the opt-in is set.
            KeyAction.Numpad -> onPanelChange(PanelMode.NUMPAD, haptic = false)
            // A key — or a long-press alternate — bound to a tool: voice, the
            // edit pad, the clipboard. Runs whether or not the tool is on the
            // toolbar; see [runToolFromKey].
            is KeyAction.Tool -> runToolFromKey((key.action as KeyAction.Tool).tool)
            // One of the user's secondary layouts, shown over the letters the
            // way ?123 shows the symbols; a second press takes it down again.
            is KeyAction.Layout -> openSecondaryLayout((key.action as KeyAction.Layout).id)
            is KeyAction.Mod -> onModifier((key.action as KeyAction.Mod).key)
            KeyAction.KanaVariant -> cycleKanaVariant()
            KeyAction.Fn -> onFn()
            is KeyAction.BrailleDot -> onBrailleDot(key.action as KeyAction.BrailleDot)
            KeyAction.MorseDot -> onMorseSignal(dash = false)
            KeyAction.MorseDash -> onMorseSignal(dash = true)
            // A key carrying its own modifiers, so it fires with no latch.
            is KeyAction.SendKey -> sendShortcut(key, Modifiers.None)
            // Fire the user's broadcast; types nothing.
            is KeyAction.Broadcast -> sendKeyBroadcast((key.action as KeyAction.Broadcast).action)
            // A text-editing key on a panel layout (or on a typing grid). The
            // key has already buzzed on the way down, so the handler must not.
            // Select goes the long way round for its tap ladder (issue #41);
            // everything else is one operation per press.
            is KeyAction.Edit -> {
                val op = (key.action as KeyAction.Edit).op
                if (op == TextEditAction.SELECT) onSelectKeyTap() else onTextEdit(op, haptic = false)
            }
            // A panel component's cell. The panel grid never dispatches one;
            // this is the exhaustive `when` insisting the case be decided.
            is KeyAction.Field -> Unit
            // A deliberate gap in the grid, and a key from a build that knows an
            // action this one does not. Both swallow the tap: a custom layout is
            // repaired before it can be enabled, so neither should reach a
            // keyboard the user is typing on.
            KeyAction.None, is KeyAction.Unknown -> Unit
        }
        // A one-shot Fn springs back after the key it modified, the same way an
        // armed shift is spent. After dispatch, not before, so the key that
        // fires is the Fn layer's key and not the one it replaced.
        if (key.action != KeyAction.Fn) consumeFn()
    }

    private var lastFnTapTime = 0L

    /**
     * Tap switches to the Fn layer for one key and springs back; a quick second
     * tap sticks. Springing back is what makes a one-off Esc or F5 cheap — the
     * common case is a single function key, not a run of them.
     */
    private fun onFn() {
        val now = System.currentTimeMillis()
        val doubleTap = now - lastFnTapTime < SHIFT_DOUBLE_TAP_MS
        lastFnTapTime = now
        _uiState.update {
            when {
                // A layout can be shared without its Fn layer, or the layer
                // deleted while a key pointing at it survives. Do nothing rather
                // than switch to a grid that is really a copy of the letters.
                it.layouts.fn == null -> it
                it.layoutMode == LayoutMode.FN && doubleTap -> it.copy(fnLocked = true)
                it.layoutMode == LayoutMode.FN -> it.copy(
                    layoutMode = it.fnReturn ?: LayoutMode.LETTERS,
                    fnLocked = false,
                    fnReturn = null,
                )
                else -> it.copy(
                    layoutMode = LayoutMode.FN,
                    fnReturn = it.layoutMode,
                    fnLocked = false,
                )
            }
        }
    }

    private fun consumeFn() {
        _uiState.update {
            if (it.layoutMode != LayoutMode.FN || it.fnLocked) {
                it
            } else {
                it.copy(layoutMode = it.fnReturn ?: LayoutMode.LETTERS, fnReturn = null)
            }
        }
    }

    private val modifierTapTimes = EnumMap<ModifierKey, Long>(ModifierKey::class.java)

    /**
     * The same three-state gesture as [onShift], reusing its double-tap window.
     *
     * A timer-free OFF → ARMED → LOCKED → OFF cycle was the alternative and was
     * rejected: arming Ctrl and immediately changing your mind would leave it
     * *locked*, which is the one state where every following letter silently
     * becomes a shortcut.
     */
    private fun onModifier(key: ModifierKey) {
        val now = System.currentTimeMillis()
        val doubleTap = now - (modifierTapTimes[key] ?: 0L) < SHIFT_DOUBLE_TAP_MS
        modifierTapTimes[key] = now
        _uiState.update { state ->
            val current = state.modifiers[key]
            val next = when {
                doubleTap && current != ModifierState.LOCKED -> ModifierState.LOCKED
                current == ModifierState.OFF -> ModifierState.ARMED
                else -> ModifierState.OFF
            }
            state.copy(modifiers = state.modifiers.with(key, next))
        }
    }

    /** Twin of [consumeShift]: drops the armed latches, keeps the locked ones. */
    private fun consumeModifiers() {
        _uiState.update {
            if (it.modifiers.isEmpty) it else it.copy(modifiers = it.modifiers.consumed())
        }
    }

    /** Clears every latch, locked ones included. */
    private fun clearModifiers() {
        modifierTapTimes.clear()
        _uiState.update {
            if (it.modifiers == Modifiers.None) it else it.copy(modifiers = Modifiers.None)
        }
    }

    /**
     * Sends [key] as a hardware-style key event with [modifiers] and any pending
     * shift folded in. Returns false when the key has no keycode to send, so the
     * caller decides what to do with the keystroke rather than this guessing.
     *
     * Modifiers are wrapped as real KEYCODE_CTRL_LEFT down/up pairs rather than
     * sent as bare meta flags — the same lesson [sendEditorKey] already learned
     * for shift, since TextView reads modifier state off the modifier key's own
     * events rather than off getMetaState().
     */
    /**
     * Fire a [KeyAction.Broadcast] key's intent (A62). Wrapped defensively: a
     * malformed action or a locked-down OEM must never take the keyboard down,
     * and a blank action is a no-op. The keyboard's own package is set as the
     * target when a receiver in it exists is irrelevant here — the intent is
     * left implicit so any app's registered receiver can observe it, which is
     * the automation use case (Tasker et al.).
     */
    private fun sendKeyBroadcast(action: String) {
        val trimmed = action.trim()
        if (trimmed.isEmpty()) return
        runCatching { sendBroadcast(android.content.Intent(trimmed)) }
    }

    private fun sendShortcut(key: Key, modifiers: Modifiers): Boolean {
        val ic = currentInputConnection ?: return false
        val state = _uiState.value
        val shift = state.shiftState != ShiftState.OFF

        val action = key.action
        val explicit = action as? KeyAction.SendKey

        // Ctrl+A/C/V/X have a first-class InputConnection route that works in
        // WebViews and Compose text fields, where a raw Ctrl+C reaches nothing.
        // The choice has to be made here rather than after the send:
        // InputConnection.sendKeyEvent reports that an event was queued, never
        // that anything acted on it, so "send it and check" cannot be written.
        //
        // Ctrl arrives two ways: latched by a press on the modifier key, or
        // written into the key itself — a layout's own Ctrl+C key, and the chord
        // a modifier drag builds (issue #67). Both take this route, or the drag
        // would be the one Ctrl+C on the keyboard that copies nothing in a
        // Compose field.
        val ctrlHeld = modifiers.ctrl != ModifierState.OFF ||
            ((explicit?.meta ?: 0) and KeyEvent.META_CTRL_ON) != 0
        if (ctrlHeld && !state.settings.rawClipboardShortcuts) {
            clipboardShortcutFor(key)?.let { onClipboardKey(it); return true }
        }

        // KEYCODE_UNKNOWN means "work it out from the label". That is how a
        // chord built in the composable names a text key: the character map that
        // answers "which key writes a c" is loaded here, not up there. A layout
        // that stored a zero keycode meant nothing by it either — the event it
        // sent was inert — so nothing that used to work changes.
        val code = explicit?.keyCode?.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
            ?: keyCodeForChar((key.output ?: key.label).singleOrNull() ?: return false)
            ?: return false

        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        var meta = modifiers.metaFlags() or (explicit?.meta ?: 0)
        // A layout's own arrow key (or Home/End/PageUp/PageDown) extends the
        // selection under selection mode or a shift the user put up, the same as
        // the toolbar's cursor tools and the spacebar scrub. A shift nobody
        // pressed is excluded there, and only there: on an arrow it would turn a
        // tap at a sentence start into a selection the next keystroke overwrites,
        // while on a letter it is the auto-capital that was asked for.
        val caret = code in CARET_KEY_CODES
        val shifted = if (caret) state.caretExtendsSelection else shift
        if (shifted) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        // Press order mirrors a hardware keyboard: modifiers down outermost and
        // released in reverse, so an editor that pairs down and up events never
        // ends up with a modifier still held after the shortcut.
        val holds = buildList {
            if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
            if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
            if (meta and KeyEvent.META_META_ON != 0) add(KeyEvent.KEYCODE_META_LEFT)
            if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        val time = SystemClock.uptimeMillis()
        for (hold in holds) ic.sendKeyEvent(shortcutEvent(time, KeyEvent.ACTION_DOWN, hold, meta))
        ic.sendKeyEvent(shortcutEvent(time, KeyEvent.ACTION_DOWN, code, meta))
        ic.sendKeyEvent(shortcutEvent(time, KeyEvent.ACTION_UP, code, meta))
        for (hold in holds.asReversed()) {
            ic.sendKeyEvent(shortcutEvent(time, KeyEvent.ACTION_UP, hold, meta))
        }
        return true
    }

    /** The clipboard action Ctrl plus this key stands for, if any. */
    private fun clipboardShortcutFor(key: Key): ClipboardKeyAction? =
        when ((key.output ?: key.label).lowercase()) {
            "a" -> ClipboardKeyAction.SELECT_ALL
            "c" -> ClipboardKeyAction.COPY
            "v" -> ClipboardKeyAction.PASTE
            "x" -> ClipboardKeyAction.CUT
            else -> null
        }

    /**
     * FLAG_SOFT_KEYBOARD keeps apps from dropping out of touch mode — which
     * moves focus and hides the caret — the way a hardware keypress does, and
     * the virtual device id matches the character map the keycodes came from.
     * The two older senders ([onUndoRedo], [sendEditorKey]) omit these and are
     * deliberately left alone rather than changed as a side effect of this.
     */
    private fun shortcutEvent(time: Long, action: Int, code: Int, meta: Int) = KeyEvent(
        time, time, action, code, 0, meta,
        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
    )

    /**
     * Keycode for a character, or null when the virtual keyboard's map has none.
     *
     * ASCII letters and digits are answered by arithmetic — KEYCODE_A..Z and
     * KEYCODE_0..9 are contiguous blocks, and they cover essentially every real
     * shortcut — which keeps a JNI call off the keystroke path. Everything else
     * goes through KeyCharacterMap, whose getEvents() documents itself as
     * unsuitable for text entry; it is used here purely as a character-to-keycode
     * lookup, which is what it is actually good at.
     *
     * Characters it cannot map (Bengali letters, ৳, the combining accents) return
     * null and the keystroke is dropped. Committing the text anyway was the
     * alternative and was rejected: a Ctrl press that quietly types "ব" into a
     * document is worse than one that visibly does nothing.
     */
    private fun keyCodeForChar(char: Char): Int? {
        val lower = char.lowercaseChar()
        if (lower in 'a'..'z') return KeyEvent.KEYCODE_A + (lower - 'a')
        if (lower in '0'..'9') return KeyEvent.KEYCODE_0 + (lower - '0')
        val events = runCatching { virtualKeyMap.getEvents(charArrayOf(lower)) }
            .getOrNull() ?: return null
        return events.firstOrNull {
            it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode)
        }?.keyCode
    }

    /**
     * Loaded once: KeyCharacterMap.load crosses into native code, and the
     * BUILT_IN_KEYBOARD device's map can legitimately be empty — which is the
     * case the virtual device id exists to avoid.
     */
    private val virtualKeyMap: KeyCharacterMap by lazy {
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    }

    /**
     * Called from the popup with an alternate character. Routed through [onKey]
     * rather than straight to [onTextKey] so an alternate picked under a latched
     * Ctrl behaves like the base key instead of typing itself.
     */
    fun onText(text: String) {
        vibrate()
        onKey(Key(label = text))
    }

    private fun onTextKey(key: Key) {
        val keyman = key.action as? KeyAction.KeymanKey
        if (keyman != null && onKeymanKey(key, keyman)) {
            // The engine typed instead of [processTypedText], which is where a
            // Text key normally spends these. Leaving them armed would hand a
            // later key a space it did not earn.
            pendingWordSpace = false
            pendingPunctuationSpace = false
            return
        }
        val output = keyOutput(key, _uiState.value)
        // A converted Keyman layout owns its own dead keys, in its own context,
        // where its rules can match them. Running ours as well would apply an
        // accent twice.
        processTypedText(output, applyDeadKeys = keymanSession == null)
        returnFromSymbolsAfter(output)
    }

    /**
     * Runs one key of a converted Keyman layout through its rule engine.
     *
     * Returns false when the engine did not handle it — no session, a keyboard
     * buffer owns the keys, or the engine declined — and the caller then types
     * the key the ordinary way.
     */
    private fun onKeymanKey(key: Key, keyman: KeyAction.KeymanKey): Boolean {
        val session = keymanSession ?: return false
        val state = _uiState.value
        // While a search box or a typing test owns the keys, what the user types
        // must not reach the app behind the keyboard, so the engine stays out of
        // it and the text goes down the ordinary local-buffer path.
        if (state.keysTakenByKeyboard) return false
        val ic = currentInputConnection ?: return false

        // One read per caret move, none per keystroke: the flag is set by
        // onUpdateSelection and resolved here, lazily.
        session.syncIfNeeded(expectedSelStart) {
            ic.getTextBeforeCursor(KEYMAN_CONTEXT_UNITS, 0) ?: ""
        }

        val modifiers = keyman.modifiers or
            KeymanSeam.modifiersFor(
                shifted = state.shiftState != ShiftState.OFF,
                capsLocked = state.shiftState == ShiftState.CAPS_LOCK,
            )
        val result = session.process(ProcessorKey(keyman.vkey, modifiers)) ?: return false

        return when (result) {
            is ProcessorResult.Declined -> false
            is ProcessorResult.Failed -> false
            is ProcessorResult.Edit -> {
                applyKeymanEdit(ic, session, result)
                keyman.nextLayer?.let { switchToNamedLayer(it) }
                    ?: result.nextLayer?.let { switchToNamedLayer(it) }
                true
            }
        }
    }

    /**
     * Offers a frame key to the rule engine before the keyboard's own handler
     * takes it. Returns true when the engine typed something.
     *
     * Space and backspace convert to [KeyAction.Space] and [KeyAction.Delete]
     * rather than to engine keys, so long-press repeat, auto-space and
     * double-space-period keep working. That leaves the keyboards whose rules
     * *do* fire on those keys — a syllable finalised on space, a cluster
     * deleted whole on backspace — with nothing, which is what this restores:
     * the engine is asked first and only a decline falls through.
     *
     * The prefilter is what keeps it cheap. On the overwhelming majority of
     * keyboards no rule mentions either key, [KeyProcessor.matches] says so
     * without touching the context, and the ordinary path runs as before.
     */
    private fun runKeymanFrameKey(vkey: Int): Boolean {
        val session = keymanSession ?: return false
        val state = _uiState.value
        if (state.keysTakenByKeyboard) return false
        // A pending composition belongs to our own composer, and the engine
        // keeps its own context. Letting both edit one keystroke deletes twice.
        if (composing.isNotEmpty()) return false
        val ic = currentInputConnection ?: return false

        val modifiers = KeymanSeam.modifiersFor(
            shifted = state.shiftState != ShiftState.OFF,
            capsLocked = state.shiftState == ShiftState.CAPS_LOCK,
        )
        if (!session.processor.matches(vkey, modifiers)) return false

        session.syncIfNeeded(expectedSelStart) {
            ic.getTextBeforeCursor(KEYMAN_CONTEXT_UNITS, 0) ?: ""
        }
        val result = session.process(ProcessorKey(vkey, modifiers)) ?: return false
        val edit = result as? ProcessorResult.Edit ?: return false
        applyKeymanEdit(ic, session, edit)
        edit.nextLayer?.let { switchToNamedLayer(it) }
        return true
    }

    /**
     * Applies a rule engine edit as one editor transaction.
     *
     * Not through the composing region. `setComposingText` replaces the whole
     * region and snaps the caret to its end, it underlines what it holds — and
     * engine output is committed text, not a candidate — and every piece of
     * machinery that hangs off "a region is open" (autocorrect, learning,
     * snippet triggers, revert) is wrong for rule output. The delete count also
     * routinely reaches text typed before this keyboard was even active, which
     * no region could cover.
     */
    private fun applyKeymanEdit(
        ic: InputConnection,
        session: KeymanSession,
        edit: ProcessorResult.Edit,
    ) {
        if (edit.isNoOp) {
            // A pure dead key: the marker lives in the engine's context and
            // nothing reaches the field. Issuing an empty transaction here makes
            // some editors scroll.
            session.onEdited(edit)
            return
        }
        ic.beginBatchEdit()
        if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
        if (edit.insert.isNotEmpty()) ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        session.onEdited(edit)
    }

    /** Shows a layer by name, for the layers a Keyman grid brought with it. */
    private fun switchToNamedLayer(name: String) {
        when (name) {
            LayoutLayer.LETTERS.key -> _uiState.update {
                it.copy(layoutMode = LayoutMode.LETTERS, fnLocked = false, fnReturn = null)
            }
            LayoutLayer.SYMBOLS.key, LayoutLayer.SYMBOLS_SHIFTED.key ->
                _uiState.update { it.copy(layoutMode = LayoutMode.SYMBOLS) }
            // A layer our grid has no slot for. Leaving the display alone beats
            // switching to something arbitrary; the key still typed its output.
            else -> Unit
        }
    }

    /**
     * Attaches or drops the rule engine when the active layout changes.
     *
     * Called wherever the layout is resolved, so a language switch cannot leave
     * the previous keyboard's rules running against the new grid.
     */
    private fun syncKeymanSession(spec: LayoutSpec) {
        val binding = spec.keyman
        if (binding == null) {
            keymanSession = null
            return
        }
        if (keymanSession?.processor?.let { true } == true &&
            activeKeymanKeyboardId == binding.keyboardId
        ) {
            return
        }
        activeKeymanKeyboardId = binding.keyboardId
        keymanSession = keymanRules.processorFor(binding)?.let { KeymanSession(it) }
    }

    private var activeKeymanKeyboardId: String? = null

    /** Keyman's own virtual keys for the two frame keys the engine may claim. */
    private val VK_BACKSPACE = 8
    private val VK_SPACE = 32

    /**
     * Springs ?123 back to the letters after one of the user's listed
     * characters, when the symbols-return setting is on. The emoji panel's
     * "return to keyboard after inserting" is the same bargain: a full stop is
     * a detour into the other grid, not a move to it.
     *
     * Only single-character keys count. A `.com` or an emoticon key is a phrase
     * the user went to ?123 to type, and it usually is not the last one.
     */
    private fun returnFromSymbolsAfter(output: String) {
        if (output.length != 1) return
        val state = _uiState.value
        if (state.layoutMode != LayoutMode.SYMBOLS &&
            state.layoutMode != LayoutMode.SYMBOLS_SHIFTED
        ) {
            return
        }
        val behavior = state.settings.layoutBehavior
        if (!behavior.symbolsReturnToLetters) return
        if (output[0] !in behavior.symbolsReturnCharSet()) return
        _uiState.update { it.copy(layoutMode = LayoutMode.LETTERS) }
    }

    /**
     * One braille dot key event — the press when [KeyAction.BrailleDot.release]
     * is false, the lift when true (the pointer handler synthesizes the lift as
     * a copy; see the chord branch in the keyboard view). Dots gather on the
     * way down; the cell decodes and commits when the last held finger lifts,
     * which is what makes simultaneous "dots 1+4+5" one `d` and not three
     * letters. Decoded text runs through [processTypedText] so it behaves
     * exactly like typed text (word buffer, autospace, field intercepts);
     * dead keys are skipped because braille has none.
     */
    private fun onBrailleDot(action: KeyAction.BrailleDot) {
        if (!action.release) {
            brailleChord.down(action.dot)
            return
        }
        val cell = brailleChord.up() ?: return
        val text = brailleGrade1.decode(cell)
        if (text.isNotEmpty()) processTypedText(text, applyDeadKeys = false)
    }

    /**
     * One morse signal. The sequence accumulates in [morse] and the decoded
     * character commits after [KeyboardSettings.morseCommitMs] of silence,
     * Gboard's model;
     * every signal restarts the pause. A non-morse key flushes the pending
     * sequence first (see [onKey]) and backspace edits it (see [onDelete]),
     * so the timer never races the user's next intent.
     */
    private fun onMorseSignal(dash: Boolean) {
        morse.signal(dash)
        _uiState.update { it.copy(morsePending = morse.display) }
        morseJob?.cancel()
        morseJob = serviceScope.launch {
            delay(_uiState.value.settings.morseCommitMs.toLong())
            commitMorse()
        }
    }

    /**
     * Decodes and commits the pending morse sequence. A sequence that spells
     * nothing is dropped — a bad chord should cost its own letter, not poison
     * the next one. Shift applies to the decoded letter the way it would to a
     * typed one (the morse grid has no shift key, but autocapitalize and a
     * hardware shift still arm it).
     */
    private fun commitMorse() {
        morseJob?.cancel()
        morseJob = null
        val decoded = morse.take()
        _uiState.update { it.copy(morsePending = "") }
        if (decoded == null) return
        val state = _uiState.value
        val cased = if (state.shiftState != ShiftState.OFF) decoded.uppercase() else decoded
        processTypedText(cased, applyDeadKeys = false)
        // Easter egg: three letters spelling SOS light a short-lived note in
        // the strip, once per service lifetime. Purely additive — the decoded
        // letter above is already committed either way.
        if (morse.recordDecoded(decoded) && !morseSosEggShown) {
            morseSosEggShown = true
            _uiState.update { it.copy(morseSosEgg = true) }
            morseSosEggJob?.cancel()
            morseSosEggJob = serviceScope.launch {
                delay(MORSE_SOS_EGG_MS)
                _uiState.update { it.copy(morseSosEgg = false) }
            }
        }
    }

    /**
     * Drops any half-typed chord or morse sequence: field switches, language
     * or layout switches, and panels taking the keys out from under a chord
     * all pass through here. Cheap enough to call unconditionally.
     */
    private fun resetChordInputs() {
        brailleChord.reset()
        brailleGrade1.reset()
        morseJob?.cancel()
        morseJob = null
        // reset() also drops the SOS watch window: letters keyed into two
        // different fields are not one distress call.
        val hadPending = morse.isPending
        morse.reset()
        if (hadPending) {
            _uiState.update { it.copy(morsePending = "") }
        }
        morseSosEggJob?.cancel()
        morseSosEggJob = null
        if (_uiState.value.morseSosEgg) {
            _uiState.update { it.copy(morseSosEgg = false) }
        }
    }

    /**
     * Shared path for one typed character, whether it came from a soft key
     * ([onTextKey]) or a physical keyboard ([handleHardwareKeyDown]).
     *
     * [applyDeadKeys] is true only for soft keys. A physical key's character
     * already carries the hardware layout's own shift and AltGr, and its dead
     * keys are composed by the framework before the IME sees them, so running
     * our dead-key state machine over it as well would double-apply accents.
     */
    private fun processTypedText(input: String, applyDeadKeys: Boolean) {
        val state = _uiState.value
        var text = input
        // A typed character is new text, so the span a pattern was told to
        // leave alone is no longer the span in front of the caret.
        patternMutedAfter = null
        // Any new input ends the window in which backspace reverts the
        // previous autocorrect.
        lastRevertible = null
        clearSwapOffer()
        // The mid-word strip is about the field as it was before this
        // keystroke; the caret's own settle re-derives it afterwards.
        clearCaretWord()
        // One-shot, spent by this keystroke whatever it turns out to be: a mark
        // takes the keyboard's own space back, and anything else simply types
        // past it. Both kinds of space count — the one that ended a word (glide
        // or strip pick) and the one that followed a punctuation mark, which is
        // what makes the closing quote of `"hi."` hug the full stop instead of
        // landing a space past it (issue #34).
        val followsWordSpace = pendingWordSpace || pendingPunctuationSpace
        pendingWordSpace = false
        pendingPunctuationSpace = false

        if (applyDeadKeys) {
            // Dead keys: the accent arms and waits, then fuses with the next
            // letter. Pressing the same accent twice types it literally, which
            // is the standard escape hatch for wanting the accent on its own.
            val pressedMark = DeadKeys.markOf(text)
            val armedMark = pendingDeadKey
            when {
                pressedMark != null && pressedMark == armedMark -> {
                    setPendingDeadKey(null)
                    text = DeadKeys.standalone(pressedMark)
                }
                pressedMark != null -> {
                    setPendingDeadKey(pressedMark)
                    consumeShift()
                    return
                }
                armedMark != null -> {
                    setPendingDeadKey(null)
                    text = DeadKeys.apply(armedMark, text)
                }
            }
        }

        // Three buffers that live on the keyboard itself and take the character
        // before any suggestion or field machinery sees it:
        //  - the typing test scores keystrokes instead of committing them, so
        //    nothing typed during a run reaches the user's text;
        //  - the AI Custom instruction composes on the key rows;
        //  - a plugin's own text box has the keys.
        // Returning here, before the field is touched, is the whole guarantee:
        // what the user types into one of these never reaches the app behind
        // the keyboard.
        val takenByKeyboardBuffer = when {
            state.typingTestActive -> { typingTestType(text); true }
            state.aiCustomInputActive -> { aiCustomInputEdit { it + text }; true }
            state.pluginTypingActive -> { pluginInputEdit { it + text }; true }
            // The calculator's display is a buffer too: a physical keyboard
            // types the expression instead of arrow-driving the keypad.
            state.calcTypingActive -> { calcEdit { it + mapCalcChars(text) }; true }
            state.converterTypingActive -> { converterEdit { appendConverterDigits(it, text) }; true }
            else -> false
        }
        if (takenByKeyboardBuffer) {
            consumeShift()
            return
        }

        if (state.emojiSearchActive) {
            text = fixedLayoutContextualVowel(text, state.emojiQuery.lastOrNull())
            updateQuery { it.copy(emojiQuery = it.emojiQuery + text) }
            refreshKarContext()
            refreshEmojiResults()
            return
        }
        if (state.mediaSearchActive && state.panel.hasMediaSearch) {
            text = fixedLayoutContextualVowel(text, state.mediaQuery.lastOrNull())
            updateQuery { it.copy(mediaQuery = it.mediaQuery + text) }
            refreshKarContext()
            // QR encodes locally as you type — no network search to schedule.
            if (state.panel != PanelMode.QR_GEN) scheduleMediaLiveSearch()
            return
        }

        if (state.dictionarySearchActive) {
            updateQuery { it.copy(dictionaryQuery = it.dictionaryQuery + text) }
            consumeShift()
            return
        }
        if (state.clipboardSearchActive) {
            updateQuery { it.copy(clipboardQuery = it.clipboardQuery + text) }
            consumeShift()
            return
        }

        val ic = currentInputConnection ?: return
        // Past every keyboard-buffer and search intercept: this keystroke's
        // text is going to the field, so it is typing worth counting.
        recordStat { onTyped(text, System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        // Fancy Text: swap plain letters for the styled glyphs at the last
        // moment — after every keyboard-buffer and search intercept above
        // (those want plain, searchable letters) and before the field sees
        // anything. Hardware keys and typing over a selection funnel through
        // here too, so they style for free.
        val fancyStyle = fancyStyleFor(state)
        text = applyFancyStyle(text, fancyStyle)
        // contextualForm is the identity for every composer except the
        // cluster-shaping (fixed Indic) ones, so only they are worth the
        // synchronous getTextBeforeCursor round-trip on the keypress path.
        // A non-empty buffer already *is* the text in front of the caret — a
        // resume mirrors the field's word into it and updateComposingText writes
        // it back verbatim — so while one is open the round-trip is skipped and
        // the answer comes from the buffer instead.
        if (state.composer.isClusterShaping) {
            val previous = composing.lastOrNull() ?: ic.getTextBeforeCursor(1, 0)?.lastOrNull()
            text = fixedLayoutContextualVowel(text, previous)
        }

        // Typing over a selection replaces it and puts the cursor after the
        // new character, like every other keyboard. Never route through the
        // composing buffer in that case.
        if (hasSelection(ic)) {
            dropComposingForSelectionEdit(ic)
            // Bracket/brace/quote over a selection wraps it in the pair
            // ("foo" → "(foo)") instead of replacing it, and leaves the inner
            // text selected so it can be wrapped or re-cased again.
            val closer = if (state.settings.textEditing.wrapSelectionWithPair && text.length == 1) {
                WRAP_PAIRS[text[0]]
            } else {
                null
            }
            if (closer != null) {
                val selected = ic.getSelectedText(0)?.toString().orEmpty()
                invalidateExpectedSelection()
                ic.beginBatchEdit()
                ic.commitText("$text$selected$closer", 1)
                val end = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionEnd
                if (end != null) {
                    val innerEnd = end - closer.length
                    val innerStart = innerEnd - selected.length
                    if (innerStart in 0..innerEnd) ic.setSelection(innerStart, innerEnd)
                }
                ic.endBatchEdit()
                consumeShift()
                _uiState.update {
                    it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
                }
                return
            }
            invalidateExpectedSelection()
            commitTypedCharacter(ic, text)
            consumeShift()
            _uiState.update { it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList()) }
            if (text.length == 1 && text[0] in SENTENCE_ENDERS) {
                previousWord = WordContext.SENTENCE_START
                previousWord2 = null
                maybeAutoCapitalize()
            }
            return
        }

        // A cluster-shaping layout composes only what a resume put in the buffer
        // (see composingMode below), and what continues *that* word is not always
        // a single letter: a Bengali vowel key becomes a kar — a combining mark,
        // which no letter test accepts — after a consonant, and the য়-glide form
        // it takes after another vowel is two characters. Both belong to the word
        // being composed, so both have to extend the buffer rather than break it
        // and commit the resumed word after a single keypress.
        val clusterContinuation = state.composer.isClusterShaping && composing.isNotEmpty() &&
            text.isNotEmpty() && text.all { isComposingWordChar(it) }
        // VNI spells Vietnamese tones/marks with digits, so a digit typed *while a
        // syllable is composing* feeds the buffer (the transducer eats it) instead
        // of committing. A digit on an empty buffer is a literal digit as usual —
        // except for a composer whose whole alphabet is digits (T9 pinyin), where
        // that rule would make the first key of every word commit as a number.
        val singleWordChar = text.length == 1 && (
            text[0].isLetter() || text[0] == '\'' ||
                state.composer.buffersChar(text[0]) ||
                (
                    state.composer.bufferDigits && text[0].isDigit() &&
                        (composing.isNotEmpty() || state.composer.digitsStartBuffer)
                    ) ||
                (
                    // Number-row slip capture: a digit typed *inside* a word
                    // joins the composing buffer so autocorrect can read it as
                    // the letter below it ("as3" → "ase"). Word-initial digits
                    // still commit literally ("3pm"), and without the number
                    // row on screen a digit was a long-press or symbols-layer
                    // choice — deliberate, never buffered.
                    text[0].isDigit() && composing.isNotEmpty() &&
                        state.settings.numberRow &&
                        state.settings.suggestionStrip.numberRowCorrections &&
                        state.allowsTypingIntelligence
                    )
            )
        val isWordChar = singleWordChar || clusterContinuation
        // Avro is a transliterating input method: its composing must run even
        // in password fields and with the strip off, or the roman keys commit
        // untransliterated and no Bengali is produced. English composing only
        // exists to feed suggestions, so it stays gated on those.
        //
        // Fancy Text never composes: the styled glyphs have no dictionary, and
        // half of them are astral pairs that would fail isWordChar anyway —
        // forcing every style down the direct-commit branch keeps the BMP
        // styles (small caps, fullwidth) consistent with the astral ones.
        //
        // A cluster-shaping layout (Probhat, Jatiya, fixed Devanagari…) types
        // its script straight into the field, so it does not compose *words* —
        // but it does have to keep composing one that a caret landing on it
        // re-armed ([restartSuggestionsAtCursor]), or the resume buys a single
        // keypress and drops the region again. So: never starts a buffer, always
        // continues the one it was handed, and the next word boundary ends it.
        val composingMode = fancyStyle == null &&
            (!state.composer.isClusterShaping || composing.isNotEmpty()) && (
            state.composer.isTransliterating ||
                (state.allowsTypingIntelligence && state.settings.suggestions)
            )

        // ":" on a word boundary opens inline emoji search: the colon and the
        // letters after it go into the composing buffer, and refreshSuggestions
        // turns that buffer into emoji instead of words. Nothing else needs to
        // track a mode — "composing starts with a colon" *is* the mode, so
        // backspacing the colon away ends it on its own.
        if (state.settings.inlineEmojiSearch && text == ":" &&
            composing.isEmpty() && composingMode
        ) {
            commitComposing(ic, autocorrect = false)
            appendComposing(text)
            updateComposingText(ic)
            refreshSuggestions()
            consumeShift()
            return
        }

        // ...and the closing ":" finishes it the way GitHub, Discord and Slack
        // do: ":tada:" becomes 🎉 outright. Exact shortcodes only — a partial
        // or unknown name stays the literal text the user typed, so the colon
        // never eats something it couldn't name.
        if (state.settings.inlineEmojiSearch && text == ":" && composing.startsWith(":")) {
            val emoji = emojiShortcodes.exact(composing.substring(1))
            if (emoji != null) {
                composing = StringBuilder()
                ic.commitText(applyEmojiTone(emoji), 1)
                learnEmoji(emoji)
                recordEmojiUse(emoji)
                _uiState.update {
                    it.copy(
                        composingPreview = "",
                        suggestions = emptyList(),
                        emojiSuggestions = emptyList(),
                        inlineEmoji = false,
                    )
                }
                consumeShift()
                return
            }
        }

        if (isWordChar && composingMode) {
            appendComposing(text)
            updateComposingText(ic)
            refreshSuggestions()
            consumeShift()
        } else {
            // A pattern may fire here too, but only for the marks that really
            // end a word. This branch also takes every symbol-layer insert,
            // every slash and every digit, and a pattern eating text behind one
            // of those is not what anybody typed.
            val endsWord = text.length == 1 &&
                (text[0] in SENTENCE_ENDERS || text[0] in AUTO_SPACE_PUNCTUATION)
            // The space in front of the caret was the keyboard's, not the
            // user's: a mark landing on it belongs to the word ("hello." not
            // "hello ."), so it comes back out before the mark commits. Done
            // ahead of the pattern expansion below, which reads the text behind
            // the caret. A user's own space is left alone — theirs to keep.
            if (followsWordSpace && swallowsAutoSpace(text, state.fieldKind) { quoteContext(ic) }) {
                if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            commitComposing(ic, autocorrect = false, expandPatterns = endsWord)
            if (swallowTerminatorAfterCommit) {
                swallowTerminatorAfterCommit = false
                consumeShift()
                return
            }
            val autoSpace = shouldAutoSpaceAfterPunctuation(state, text)
            if (autoSpace) {
                // A run of marks ("...", "?!") must not be pulled apart by the
                // spaces this rule types, so the one from the previous mark is
                // taken back before the new mark lands.
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (before.length == 2 && before[1] == ' ' && before[0] in AUTO_SPACE_PUNCTUATION) {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            commitTypedCharacter(ic, text)
            if (autoSpace) insertPunctuationSpace(ic)
            // Consume one-shot shift before evaluating auto-capitalize, so a
            // sentence ender can turn shift back on for the next sentence.
            consumeShift()
            if (text.length == 1 && text[0] in SENTENCE_ENDERS) {
                // The next word starts a sentence: its bigram context is the
                // sentinel, not the word before the full stop.
                previousWord = WordContext.SENTENCE_START
                previousWord2 = null
                maybeAutoCapitalize()
            }
            // Email fields commit straight through (no composing buffer), so the
            // contact-email strip has to be refreshed off the committed text.
            if (emailFieldForceActive(state)) refreshEmailFieldSuggestions()
        }
    }

    /**
     * Whether the just-typed [text] should be followed by a space typed for the
     * user (see [KeyboardSettings.autoSpaceAfterPunctuation]).
     *
     * Structured fields are excluded through [KeyboardUiState.allowsTypingIntelligence],
     * which is exactly the URL / email / password / keypad set: a space inserted
     * into an address or a password is a typo, not a courtesy. Scripts that
     * write without spaces (Chinese, Japanese, Thai…) are excluded too — their
     * punctuation is followed by nothing, and the wide forms (。、！) are not in
     * [AUTO_SPACE_PUNCTUATION] for the same reason.
     */
    private fun shouldAutoSpaceAfterPunctuation(state: KeyboardUiState, text: String): Boolean =
        state.settings.autoSpaceAfterPunctuation &&
            state.allowsTypingIntelligence &&
            !state.composer.isConversion &&
            text.length == 1 && text[0] in AUTO_SPACE_PUNCTUATION

    /**
     * Types the space that follows an auto-spaced punctuation mark, unless the
     * text already continues with one — pressing "," in the middle of "a, b"
     * must not push a second space in.
     *
     * Arms the same one-shot cancel the ". " from a double space uses: a shift
     * press right afterwards takes the space back (see [onShift]), a space
     * press is swallowed rather than doubled (see [onSpace]), and a closing
     * mark takes it back the way it takes back a glide's (see [swallowsAutoSpace]).
     */
    private fun insertPunctuationSpace(ic: InputConnection) {
        if (spacedAfterCaret(ic.getTextAfterCursor(1, 0))) return
        ic.commitText(" ", 1)
        pendingAutoSpace = true
        pendingPunctuationSpace = true
        // The caret anchor has to be re-taken from this commit's own echo, or
        // the selection update it triggers reads as "the user moved the caret"
        // and disarms the cancel before it can ever be used.
        armRevertGuard()
    }

    /**
     * The text in front of the caret that tells an opening quote from a closing
     * one — see [closesQuote]. Read only when the typed character is a quote,
     * because this is an editor round trip and every other keystroke would pay
     * for it and throw the answer away.
     *
     * [QUOTE_CONTEXT_CHARS] is the window. The count only has to reach back to
     * the start of the line, and a quotation that opened more than that many
     * characters ago is one nobody is still closing by hand.
     */
    private fun quoteContext(ic: InputConnection): String =
        ic.getTextBeforeCursor(QUOTE_CONTEXT_CHARS, 0)?.toString().orEmpty()

    /**
     * The Fancy Text style in force, or null everywhere outside the fancy
     * layout. The session override (the strip's tap, for instant response)
     * wins over the persisted pick.
     */
    private fun fancyStyleFor(state: KeyboardUiState): FancyStyle? =
        if (state.language.id == FancyStyles.LANG_ID) {
            FancyStyles.byId(
                state.activeFancyStyleId ?: state.settings.layoutBehavior.fancyStyleId,
            )
        } else {
            null
        }

    /** [text] restyled, or unchanged when no fancy style is in force. */
    private fun applyFancyStyle(text: String, style: FancyStyle?): String =
        if (style != null) FancyStyles.transform(text, style) else text

    /** A style chip on the fancy strip: instant in-session, persisted behind. */
    fun onFancyStyleSelect(id: String) {
        vibrate()
        _uiState.update { it.copy(activeFancyStyleId = id) }
        serviceScope.launch { settingsRepository.setFancyStyle(id) }
    }

    /**
     * The layout the Fancy tool switched away from, and the marker that the tool
     * — rather than the 🌐 key — is what put Fancy Text on screen. Session-only
     * on purpose: it says where to go back to, which is a fact about this
     * sitting at the keyboard and not a setting to carry into the next one.
     */
    private var fancyToolReturnLayoutId: String? = null

    /** Set when the keyboard closed with Fancy Text on and the setting asks for the switch back. */
    private var fancyToolAutoOffPending = false

    /**
     * The Fancy tool: put the fancy layout on the keyboard, or take it back off.
     *
     * Turning it on adds the layout to the enabled cycle when it isn't there
     * already, so a user who never opened the language settings still gets it in
     * one press. Turning it off returns to the layout it came from and takes the
     * cycle back to what it was, unless
     * [LayoutBehaviorSettings.fancyToolKeepsLanguage] says to leave it.
     */
    fun onFancyToggle() {
        vibrate()
        val state = _uiState.value
        if (state.language.id == FancyStyles.LANG_ID) turnFancyOff(state) else turnFancyOn(state)
    }

    private fun turnFancyOn(state: KeyboardUiState) {
        val behavior = state.settings.layoutBehavior
        fancyToolReturnLayoutId = state.layoutId
        // A pinned style applies to the session only. Writing it through would
        // make the tool quietly replace whatever style the strip last chose.
        val style = behavior.fancyToolStyleId?.let { FancyStyles.byId(it) }
        if (style != null) _uiState.update { it.copy(activeFancyStyleId = style.id) }
        val enabled = state.settings.enabledLayoutIds
        if (AssetLayouts.FANCY_ID !in enabled) {
            serviceScope.launch {
                settingsRepository.setEnabledLayoutIds(enabled + AssetLayouts.FANCY_ID)
            }
        }
        onLayoutSelected(AssetLayouts.FANCY_ID)
        val active = style ?: fancyStyleFor(_uiState.value)
        Toast.makeText(
            this,
            if (active != null) {
                getString(R.string.ime_service_fancy_on_style_toast, active.name)
            } else {
                getString(R.string.ime_service_fancy_on_toast)
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** [turnFancyOn]'s opposite. [quiet] is the automatic pass, which says nothing. */
    private fun turnFancyOff(state: KeyboardUiState, quiet: Boolean = false) {
        val remaining = state.settings.enabledLayoutIds.filter { it != AssetLayouts.FANCY_ID }
        val target = layoutAfterFancy(fancyToolReturnLayoutId, remaining)
        fancyToolReturnLayoutId = null
        // The session style dies with the layout, so the next time the tool runs
        // it starts from the pinned or persisted style rather than this one.
        _uiState.update { it.copy(activeFancyStyleId = null) }
        // Never empty the cycle: one layout has to stay enabled, and a user with
        // only Fancy Text enabled asked for exactly that.
        if (!state.settings.layoutBehavior.fancyToolKeepsLanguage && remaining.isNotEmpty()) {
            serviceScope.launch { settingsRepository.setEnabledLayoutIds(remaining) }
        }
        onLayoutSelected(target)
        if (!quiet) {
            Toast.makeText(this, getString(R.string.ime_service_fancy_off_toast), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun keyOutput(key: Key, state: KeyboardUiState): String {
        val base = key.output ?: key.label
        val shiftLabel = key.shiftLabel
        val out = when {
            state.shiftState != ShiftState.OFF && shiftLabel != null -> shiftLabel
            state.shiftState != ShiftState.OFF && !state.composer.isClusterShaping ->
                base.uppercase()
            else -> base
        }
        return applyNumerals(out, state)
    }

    /**
     * Rewrites ASCII digits in a committed key output to the chosen numeral
     * system. Numeric/phone/date/time keypads keep ASCII under the default
     * [NumeralCommitScope.TEXT_ONLY] so those fields stay machine-parseable;
     * [NumeralCommitScope.EVERYWHERE] types native digits there too, and
     * [NumeralCommitScope.DISPLAY_ONLY] never rewrites. A no-op for Latin and
     * for text with no digits.
     */
    private fun applyNumerals(text: String, state: KeyboardUiState): String {
        val apply = when (state.settings.layoutBehavior.numeralCommitScope) {
            NumeralCommitScope.DISPLAY_ONLY -> false
            NumeralCommitScope.EVERYWHERE -> true
            NumeralCommitScope.TEXT_ONLY -> !state.fieldKind.isNumericPad
        }
        if (!apply) return text
        val digits = resolveNumeralDigits(
            state.settings.layoutBehavior.numeralSystemFor(state.language.id),
            state.language,
        )
        return mapDigits(text, digits)
    }

    /**
     * Fixed Bengali layouts (Probhat, Jatiya): a vowel-sign key yields the
     * kar form (া, ি, …) after a consonant it can attach to, the য়-glide
     * (য়া, য়ে) after another vowel — so কা + আ gives কায়া, never the
     * invalid কাআ — and the independent vowel (আ, ই, …) at a word start.
     */
    private fun fixedLayoutContextualVowel(text: String, previous: Char?): String =
        _uiState.value.composer.contextualForm(text, previous)

    /**
     * Recomputes [KeyboardUiState.vowelForm] from the character before the
     * cursor (or the emoji query) so the fixed-layout vowel keys track the
     * word position both in output and on the key labels.
     */
    private fun refreshKarContext() {
        if (!_uiState.value.composer.isClusterShaping) return
        val previous = if (_uiState.value.emojiSearchActive) {
            _uiState.value.emojiQuery.lastOrNull()
        } else {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        }
        val form = BengaliGraphemes.vowelFormAfter(previous)
        _uiState.update { if (it.vowelForm == form) it else it.copy(vowelForm = form) }
    }

    private fun onShift() {
        // With a selection, shift re-cases the selected text (lower → Title →
        // UPPER) rather than arming shift for the next character. Falls through
        // to normal shift when nothing is selected or the feature is off.
        //
        // Not while that same shift is the modifier holding the selection open
        // ([KeyboardUiState.shiftSelectsText]): every selection dragged out with
        // shift+arrow — the toolbar's cursor tools, a layout's arrow keys, the
        // spacebar scrub — ends with a live selection and a shift still up, and
        // re-casing there would leave the user no way to put the shift back
        // down. Press it once to release the modifier; press it again, with the
        // selection still standing and the shift now down, to re-case.
        if (_uiState.value.settings.textEditing.recapitalizeSelectionWithShift &&
            !_uiState.value.shiftSelectsText
        ) {
            val ic = currentInputConnection
            if (ic != null && hasSelection(ic) && recapitalizeSelection(ic)) return
        }
        // Cancel a just-inserted auto-space after punctuation — the ". " from a
        // double space, or the space the auto-space-after-punctuation rule
        // types. Either leaves a trailing space, and one shift press removes it
        // rather than arming caps, so a sentence can be continued without it.
        if (pendingAutoSpace) {
            pendingAutoSpace = false
            pendingPunctuationSpace = false
            val ic = currentInputConnection
            if (ic != null) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                if (before.length == 2 && before[1] == ' ' && before[0] in AUTO_SPACE_PUNCTUATION) {
                    ic.deleteSurroundingText(1, 0)
                    // The ". " also armed auto-cap for a new sentence; cancelling
                    // the break cancels that too, so typing continues in case.
                    consumeShift()
                    return
                }
            }
        }
        val now = System.currentTimeMillis()
        // The caps-lock double-tap window is user-tunable (A47); Fn and the
        // modifier keys keep the fixed SHIFT_DOUBLE_TAP_MS.
        val capsLockMs = _uiState.value.settings.layoutBehavior.shiftCapsLockMs
        val doubleTap = now - lastShiftTapTime < capsLockMs
        lastShiftTapTime = now
        _uiState.update {
            val next = when {
                doubleTap && it.shiftState != ShiftState.CAPS_LOCK -> ShiftState.CAPS_LOCK
                it.shiftState == ShiftState.OFF -> ShiftState.ON
                else -> ShiftState.OFF
            }
            it.copy(
                shiftState = next,
                // The one place a shift is the user's own doing, which is what
                // Shift+Enter's newline override keys off.
                shiftPressedByUser = next != ShiftState.OFF,
            )
        }
    }

    /**
     * The dedicated ⇪ key: caps lock on or off, nothing in between.
     *
     * Deliberately not [onShift] with the double-tap pre-armed. This key exists
     * only on grids wide enough to also carry a real shift key, so the two split
     * the job — shift arms for one letter, this one sticks — and routing it
     * through the tap-timing path would make a single press mean ON, which is the
     * other key's answer.
     */
    private fun onCapsLock() {
        _uiState.update {
            val next =
                if (it.shiftState == ShiftState.CAPS_LOCK) ShiftState.OFF else ShiftState.CAPS_LOCK
            it.copy(shiftState = next, shiftPressedByUser = next != ShiftState.OFF)
        }
    }

    private fun consumeShift() {
        _uiState.update {
            if (it.shiftState == ShiftState.ON) {
                it.copy(shiftState = ShiftState.OFF, shiftPressedByUser = false)
            } else {
                it
            }
        }
    }

    /**
     * Re-cases the current selection to the next form in the cycle
     * lower → Title → UPPER → lower and keeps it selected, so repeated shift
     * presses walk the cycle. Returns false (leaving the selection alone) when
     * there is nothing to change — e.g. a caseless script like Bengali.
     */
    private fun recapitalizeSelection(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isEmpty()) return false
        val next = nextCaseForm(selected)
        if (next == selected) return false
        dropComposingForSelectionEdit(ic)
        ic.beginBatchEdit()
        ic.commitText(next, 1)
        val end = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionEnd
        if (end != null) ic.setSelection((end - next.length).coerceAtLeast(0), end)
        ic.endBatchEdit()
        return true
    }

    /** Advances [s] one step through lower → Title → UPPER → lower. */
    private fun nextCaseForm(s: String): String {
        val lower = s.lowercase()
        val upper = s.uppercase()
        val title = toTitleCase(s)
        return when {
            s == lower -> title
            s == title && title != upper -> upper
            s == upper -> lower
            else -> lower // mixed case → normalize to lower to restart the cycle
        }
    }

    /** "hELLO wORLD" → "Hello World": first letter of each word up, rest down. */
    private fun toTitleCase(s: String): String {
        val sb = StringBuilder(s.length)
        var prevLetter = false
        for (c in s) {
            sb.append(if (!prevLetter && c.isLetter()) c.uppercaseChar() else c.lowercaseChar())
            prevLetter = c.isLetter()
        }
        return sb.toString()
    }

    private fun onDelete() {
        // Backspace is the answer "no" to an add-word chip: the user is taking
        // the word back rather than keeping it, so the offer goes with it. The
        // near-miss chip goes for the plainer reason that the word it is about
        // is being deleted.
        if (learnOfferWord != null) clearLearnOffer()
        if (correctionOfferFor != null) clearCorrectionOffer()
        // Backspace with a morse sequence pending edits the sequence, not the
        // field — Gboard's contract. It only falls through to a real delete
        // once the sequence is empty.
        if (morse.isPending) {
            morse.backspace()
            _uiState.update { it.copy(morsePending = morse.display) }
            morseJob?.cancel()
            morseJob = if (morse.isPending) {
                serviceScope.launch {
                    delay(_uiState.value.settings.morseCommitMs.toLong())
                    commitMorse()
                }
            } else {
                null
            }
            return
        }
        val state = _uiState.value
        // Backspace while handwritten ink is waiting for recognition throws
        // the ink away instead of deleting committed text — the natural
        // "no, not that" while writing. Applies to the panel and to
        // handwriting drawn straight on the keys.
        if ((state.panel == PanelMode.HANDWRITING || keyboardHandwriteActive(state)) &&
            state.handwriting.strokes.isNotEmpty()
        ) {
            hwJob?.cancel()
            hwGeneration++
            _uiState.update {
                it.copy(handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false))
            }
            return
        }
        if (state.typingTestActive) {
            typingTestBackspace()
            return
        }
        if (state.aiCustomInputActive) {
            aiCustomInputEdit { it.dropLast(1) }
            return
        }
        if (state.pluginTypingActive) {
            pluginInputEdit { it.dropLast(1) }
            return
        }
        if (state.calcTypingActive) {
            calcEdit { it.dropLast(1) }
            return
        }
        if (state.converterTypingActive) {
            converterEdit { it.dropLast(1) }
            return
        }
        if (state.emojiSearchActive) {
            if (state.emojiQuery.isNotEmpty()) {
                updateQuery { it.copy(emojiQuery = it.emojiQuery.dropLast(1)) }
                refreshKarContext()
                refreshEmojiResults()
            }
            return
        }
        if (state.dictionarySearchActive) {
            if (state.dictionaryQuery.isNotEmpty()) {
                updateQuery { it.copy(dictionaryQuery = it.dictionaryQuery.dropLast(1)) }
            }
            return
        }
        if (state.clipboardSearchActive) {
            if (state.clipboardQuery.isNotEmpty()) {
                updateQuery { it.copy(clipboardQuery = it.clipboardQuery.dropLast(1)) }
            }
            return
        }
        if (state.mediaSearchActive && state.panel.hasMediaSearch) {
            if (state.mediaQuery.isNotEmpty()) {
                updateQuery { it.copy(mediaQuery = it.mediaQuery.dropLast(1)) }
                refreshKarContext()
                scheduleMediaLiveSearch()
            }
            return
        }
        deleteFromField()
    }

    /**
     * How much of [before] one character-sized delete takes: a whole
     * multi-code-point emoji (☠️, 👍🏽, 👨‍👩‍👧) rather than a piece of one, a
     * whole Bengali-style conjunct where the language asks for it, a surrogate
     * pair rather than half of one, and otherwise a single code unit. 0 for
     * empty text.
     *
     * Shared by the backspace key and the character-mode backspace swipe, so
     * the two cannot disagree about what one character is.
     */
    private fun charDeleteLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val state = _uiState.value
        val emojiLength = EmojiGraphemes.deleteLength(before)
        return when {
            emojiLength > 0 -> emojiLength
            state.language.id in state.settings.conjunctBackspaceLanguages ->
                state.composer.deleteLength(before).coerceAtLeast(1)
            before.length >= 2 &&
                Character.isSurrogatePair(before[before.length - 2], before[before.length - 1]) -> 2
            else -> 1
        }
    }

    /**
     * One backspace against the real text field, regardless of any active
     * panel search: selection first, then gesture-word / autocorrect undo,
     * then composing, then a full grapheme cluster. Split from [onDelete]
     * so the emoji search bar's field-backspace can reach it while the
     * backspace key is busy editing the query.
     */
    private fun deleteFromField() {
        // Inside rather than in onDelete, so the emoji bar's field-backspace
        // gets the same offer.
        if (runKeymanFrameKey(VK_BACKSPACE)) return
        val state = _uiState.value
        val ic = currentInputConnection ?: return
        recordStat { onBackspace(System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        // The mid-word strip describes the field as it was before this delete.
        clearCaretWord()
        // Deleting with an active selection removes the selected text only.
        if (hasSelection(ic)) {
            dropComposingForSelectionEdit(ic)
            invalidateExpectedSelection()
            ic.commitText("", 1)
            return
        }
        // Backspace straight after a glide removes the whole swiped word —
        // a wrong swipe shouldn't cost a letter-by-letter cleanup.
        lastGestureWord?.let { word ->
            lastGestureWord = null
            pendingWordSpace = false
            if (composing.isEmpty()) {
                // The space the glide typed goes with the word: undoing the
                // swipe must leave the caret where the swipe found it.
                val len = glideCommitLength(ic, word)
                if (len > 0) {
                    ic.deleteSurroundingText(len, 0)
                    // The undone word is gone as bigram context; whatever now
                    // precedes the caret is the real one.
                    syncPreviousWordFromField(ic)
                    // Both lists: the bar is up while either has content, so
                    // clearing only the words left a stale emoji row holding
                    // it open.
                    _uiState.update {
                        it.copy(suggestions = emptyList(), emojiSuggestions = emptyList())
                    }
                    return
                }
            }
        }
        // Backspace straight after an autocorrect or a snippet expansion takes
        // it back: what the keyboard put in the field becomes what the user
        // typed again. An undone autocorrect also joins the personal dictionary
        // so it is never auto-"fixed" again — deleting the fix is the strongest
        // "I meant what I typed".
        lastRevertible?.let { revert ->
            lastRevertible = null
            clearSwapOffer()
            val allowed = when (revert.kind) {
                RevertibleCommit.Kind.AUTOCORRECT -> state.settings.revertAutocorrectOnBackspace
                // A pattern snippet eats several typed words at once, so being
                // able to take it back is not a preference. There is also no
                // settings field left to hang one on.
                RevertibleCommit.Kind.SNIPPET -> true
                // Undoing a tapped join is not a preference either.
                RevertibleCommit.Kind.JOIN -> true
                // Nor is undoing a tapped revision chip.
                RevertibleCommit.Kind.REVISION -> true
            }
            if (composing.isEmpty() && allowed) {
                // A correction is always followed by the space that triggered
                // it, but an expansion can be followed by a newline, a full
                // stop, or nothing at all when a {cursor} marker swallowed the
                // key. Reading one character past the commit and putting
                // whatever it is back verbatim beats guessing which it was.
                val probe = ic.getTextBeforeCursor(revert.committed.length + 1, 0)?.toString()
                val tail = if (probe != null && probe.length > revert.committed.length) {
                    probe.takeLast(1)
                } else {
                    ""
                }
                if (probe != null && probe.dropLast(tail.length) == revert.committed) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(probe.length, 0)
                    ic.commitText(revert.original + tail, 1)
                    ic.endBatchEdit()
                    revertCommitted(ic, revert, state)
                    return
                }
            }
        }
        if (composing.isNotEmpty()) {
            // The buffer is the field's own text, so a backspace has to take the
            // same thing off it that the branch below would take off the field —
            // for a cluster-shaping layout, a whole conjunct rather than one code
            // unit, which would shed a base and leave its hasant dangling. Gated
            // on the same per-language setting for the same reason: whichever way
            // the user set backspace, it must not change under a resumed word.
            val length = if (state.language.id in state.settings.conjunctBackspaceLanguages) {
                state.composer.deleteLength(composing).coerceIn(1, composing.length)
            } else {
                1
            }
            composing.setLength(composing.length - length)
            repeat(length) { composingTouch.removeLastOrNull() }
            updateComposingText(ic)
            refreshSuggestions()
        } else {
            // Delete a full surrogate pair / grapheme; optionally a whole
            // Bengali conjunct cluster as one unit. The lookback has to
            // outrun the longest emoji ZWJ/tag sequence, not just a pair.
            val before = ic.getTextBeforeCursor(64, 0)
            // An editor that will not say what is behind the cursor still owes
            // the press a delete, so an unknown answer is one code unit.
            val deleteLength = charDeleteLength(before ?: "").coerceAtLeast(1)
            ic.deleteSurroundingText(deleteLength, 0)
            // Backspacing through committed text is the one way the word
            // behind the cursor changes without passing through learn(), so
            // the strip used to keep predicting from a word the user had
            // already erased. In an empty field that left it offering bigrams
            // for text that was no longer there — and since the bar and its
            // chevron are shown exactly while the strip has content, neither
            // would go away. Re-derive the context, then refresh: with no word
            // behind the cursor the engine returns nothing and the bar folds.
            //
            // Derived from the text already in hand rather than asking the
            // editor for the same 64 characters a second time: that read is a
            // blocking round-trip into the focused app, and holding backspace
            // repeats this whole path many times a second, which is exactly
            // when the app on the other side is least able to answer promptly.
            // Every branch of the `when` above bounds deleteLength by what
            // `before` holds, so the guard only falls through if that ever
            // stops being true.
            if (before != null && deleteLength <= before.length) {
                val left = before.subSequence(0, before.length - deleteLength)
                setContextFrom(left)
                rebuildRecentWords(left)
            } else {
                syncPreviousWordFromField(ic)
            }
            refreshSuggestions()
        }
    }

    /**
     * Cleans up after one backspace took a commit back.
     *
     * This is where the two kinds part company. Rejecting an autocorrect is a
     * statement about a word, so the correction is retired and the word taught;
     * taking back a snippet expansion says nothing about anybody's vocabulary,
     * so it teaches nothing and only puts the context back where it was.
     */
    private fun revertCommitted(ic: InputConnection, revert: RevertibleCommit, state: KeyboardUiState) {
        // A stale precompute for this word would hand the old answer to the
        // next commit before the strip catches up.
        commitResolution = null
        when (revert.kind) {
            RevertibleCommit.Kind.AUTOCORRECT -> {
                // Undoing the correction retires that exact pair: without
                // this the very next space corrected the word straight back.
                // Unconditional, unlike the personal dictionary entry below —
                // a rejection has to hold in incognito and with learning off
                // too. Other corrections of the same typed word stay live.
                suggestionEngine?.rejectCorrection(revert.original, revert.committed)
                // Judged here and now, so the settle pass must not judge it
                // again on the way out and count the rejection twice.
                correctionWatch.drop(revert.original, revert.committed)
                if (state.settings.learnFromTyping &&
                    !(state.incognitoOn && state.settings.incognitoPausesLearning) &&
                    !state.secureField
                ) {
                    // Counted, not learned outright. Reaching for backspace is
                    // strong evidence — worth several ordinary sightings — but
                    // it is not proof: undoing a correction is also what you do
                    // when the correction was simply not the word you wanted,
                    // and this used to be the shortest path from one stray
                    // swipe to a misspelling nailed into the dictionary
                    // forever. Nothing is lost by waiting either, because
                    // [rejectCorrection] above has already stopped this exact
                    // correction from firing again.
                    noteRevertedWord(revert.original, state)
                }
                previousWord = revert.original.trim { !WordContext.isWordChar(it) }
                    .lowercase().ifEmpty { null }
                invalidateRecentWords()
            }
            RevertibleCommit.Kind.SNIPPET -> {
                // The restored words are back to being exactly what the user
                // typed, and the caret may be sitting at the end of the last
                // one — where the keyboard re-arms it as a composing word. The
                // next space would then expand it straight back, so that one
                // span is muted until something else is typed.
                patternMutedAfter = revert.original
                syncPreviousWordFromField(ic)
            }
            RevertibleCommit.Kind.JOIN -> {
                // The two original words are back; re-derive context from the
                // field. No rejectCorrection (the user asked for the join by
                // tapping) and no unlearning of a real dictionary word.
                syncPreviousWordFromField(ic)
                invalidateRecentWords()
            }
            RevertibleCommit.Kind.REVISION -> {
                // Same shape as a join: the original words are back, context
                // re-derives from the field, and nothing needs unlearning.
                syncPreviousWordFromField(ic)
                invalidateRecentWords()
            }
        }
    }

    /**
     * The ⌦ key: deletes forward, over the character *after* the cursor.
     *
     * A panel search owns the keys while it is open, and its query is a plain
     * string with no caret inside it — there is no "after the cursor" to act
     * on, and reaching past the panel would edit field text the user cannot
     * see. So the key stands down in those contexts rather than doing
     * something surprising.
     */
    private fun onForwardDelete() {
        val state = _uiState.value
        if (state.typingTestActive || state.aiCustomInputActive || state.pluginTypingActive ||
            state.calcTypingActive || state.converterTypingActive ||
            state.emojiSearchActive || state.dictionarySearchActive ||
            state.clipboardSearchActive ||
            (state.mediaSearchActive && state.panel.hasMediaSearch)
        ) {
            return
        }
        deleteForwardFromField()
    }

    /**
     * One forward delete against the real text field: a selection if there is
     * one, otherwise the whole grapheme cluster after the cursor.
     *
     * Composing text sits *before* the caret, so it is never what forward
     * delete removes — but editing around a live composing region strands its
     * underline, so the buffer is committed as-is (never autocorrected: the
     * user did not signal the word was finished) before the deletion lands.
     */
    private fun deleteForwardFromField() {
        val ic = currentInputConnection ?: return
        // The tail this delete eats into is half of what the mid-word strip is
        // about, so that strip goes with it.
        clearCaretWord()
        // A selection is what gets deleted, exactly as backspace does.
        if (hasSelection(ic)) {
            dropComposingForSelectionEdit(ic)
            invalidateExpectedSelection()
            ic.commitText("", 1)
            return
        }
        if (composing.isNotEmpty()) commitComposing(ic, autocorrect = false)
        // The lookahead has to outrun the longest emoji ZWJ/tag sequence, the
        // same way backspace's lookback does.
        val after = ic.getTextAfterCursor(64, 0)
        if (after.isNullOrEmpty()) return
        invalidateExpectedSelection()
        ic.deleteSurroundingText(0, EmojiGraphemes.forwardDeleteLength(after).coerceAtLeast(1))
        // Nothing before the cursor moved, so the bigram context still holds;
        // only the strip's completion view of the field changed.
        refreshSuggestions()
    }

    /**
     * Whether a forward delete would still remove anything, so the held-repeat
     * loop stops at the end of the text instead of buzzing against nothing.
     */
    fun canForwardDelete(): Boolean {
        val state = _uiState.value
        if (state.typingTestActive || state.aiCustomInputActive || state.pluginTypingActive ||
            state.calcTypingActive || state.converterTypingActive ||
            state.emojiSearchActive || state.dictionarySearchActive ||
            state.clipboardSearchActive ||
            (state.mediaSearchActive && state.panel.hasMediaSearch)
        ) {
            return false
        }
        val ic = currentInputConnection ?: return false
        if (hasSelection(ic)) return true
        // A null answer means the editor can't say — keep deleting rather than
        // stopping a working key; only a definite "" stops it.
        val after = ic.getTextAfterCursor(1, 0) ?: return true
        return after.isNotEmpty()
    }

    /**
     * Re-reads the completed word before the cursor and makes it the
     * prediction context, or clears it when there is none.
     *
     * A cursor sitting mid-word has no *completed* previous word, so it
     * predicts from nothing rather than from the fragment it is inside.
     */
    private fun syncPreviousWordFromField(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(64, 0)
        setContextFrom(before)
        rebuildRecentWords(before)
        seedFieldLanguageMix()
    }

    /**
     * Re-derives the engine's per-field language mix from [recentWords] —
     * the tokens [rebuildRecentWords] just read off the field — so the mix
     * always describes the words in front of the user. Runs wherever the
     * field is re-read: entry, caret jumps, post-edit re-syncs. Between
     * re-reads the engine keeps itself current from each committed word.
     */
    private fun seedFieldLanguageMix() {
        val engine = suggestionEngine ?: return
        if (engine.fieldDetectionShift <= 0.0) {
            engine.clearFieldContext()
            return
        }
        val words = ArrayList<String>(recentWords.size)
        for (token in recentWords) {
            val word = token.trim { !it.isLetter() }
            if (word.isNotEmpty()) words.add(word)
        }
        engine.seedFieldContext(words)
    }

    /**
     * The engine's calibrated detection shift for the current settings: 0
     * (off) unless the user has left field-language detection on, else the
     * strength they chose.
     */
    private fun fieldDetectionShift(settings: KeyboardSettings): Double =
        if (!settings.suggestionStrip.languageDetection) {
            SuggestionEngine.FIELD_SHIFT_OFF
        } else {
            when (settings.suggestionStrip.languageDetectionStrength) {
                LanguageDetectionStrength.GENTLE -> SuggestionEngine.FIELD_SHIFT_GENTLE
                LanguageDetectionStrength.BALANCED -> SuggestionEngine.FIELD_SHIFT_BALANCED
                LanguageDetectionStrength.AGGRESSIVE -> SuggestionEngine.FIELD_SHIFT_AGGRESSIVE
            }
        }

    /**
     * Adds a freshly committed [word] to the pattern gate's memory.
     *
     * Called wherever [previousWord] takes a word the keyboard just put in the
     * field, so the two never disagree about what was typed.
     */
    private fun pushRecentWord(word: String) {
        if (word.isEmpty()) return
        recentWords.addLast(word)
        while (recentWords.size > RECENT_WORDS) recentWords.removeFirst()
        recentWordsValid = true
    }

    /**
     * Refills the pattern gate's memory from text a caller has already read.
     *
     * Always from real field text, never from what the keyboard believes it
     * committed. A gate that drifts is worse than one that is simply empty, and
     * every caller here is somewhere [previousWord] is being re-derived from
     * the same characters — so this costs nothing extra.
     */
    private fun rebuildRecentWords(text: CharSequence?) {
        recentWords.clear()
        recentWordsValid = true
        if (text == null) return
        var end = text.length
        while (end > 0 && recentWords.size < RECENT_WORDS) {
            while (end > 0 && text[end - 1].isWhitespace()) end--
            if (end == 0) break
            var start = end
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            recentWords.addFirst(text.subSequence(start, end).toString())
            end = start
        }
    }

    /** Forgets the recent words, so the gate reads the field rather than guess. */
    private fun invalidateRecentWords() {
        recentWords.clear()
        recentWordsValid = false
    }

    /**
     * The completed word ending [text] — the bigram context for whatever comes
     * next — or null when [text] ends inside a word (a fragment is no context)
     * or holds no word at all.
     */
    private fun completedWordBefore(text: CharSequence?): String? =
        WordContext.completedWordBefore(text, SENTENCE_ENDERS)

    /** Sets both context words from the text before the caret. */
    private fun setContextFrom(text: CharSequence?) {
        val (prev1, prev2) = WordContext.lastTwoWords(text, SENTENCE_ENDERS)
        previousWord = prev1
        previousWord2 = prev2
    }

    /**
     * Re-reads the context around a cursor that moved without going through a
     * keystroke — a tap elsewhere, a selection-handle drag, a spacebar-swipe
     * caret move, or an edit the app itself made — so the strip reflects where
     * the caret *now* sits instead of the word last typed.
     *
     * When the caret lands at the end of a word, that word is re-entered as the
     * composing region: the strip offers its completions and corrections, and
     * typing on extends it, exactly as if it were being typed fresh (with the
     * word before it restored as the bigram context). Otherwise only the
     * preceding-word context is re-derived — a caret mid-word or after a
     * separator has no word to resume, so it predicts the next one (or clears).
     *
     * Any language whose composing buffer is the field's own text and that has
     * something to complete from — see [composingResumable], which is where the
     * transliterating layouts drop out. The cluster-shaping ones (Probhat,
     * Jatiya, fixed Devanagari…) are in: they do not compose while typing, but a
     * word handed to them here is their own script and they keep composing it
     * until the next boundary (see [processTypedText]'s `composingMode`).
     * [newSelStart] is the caret offset the field just reported, used to place
     * the composing region.
     */
    private fun restartSuggestionsAtCursor(ic: InputConnection, newSelStart: Int) {
        val state = _uiState.value
        // Whatever the caret was sitting in before this update, it is being
        // answered again from scratch below.
        clearCaretWord()
        caretSettleJob?.cancel()
        caretSettleJob = null
        // The caret settling right after a swipe is the commit's own echo:
        // keep the gesture alternates on the strip (tap-to-replace) instead
        // of re-deriving completions for the word just committed. Any real
        // caret move disarms lastGestureWord first (see revertAnchor), so
        // this never suppresses a genuine context re-read.
        if (lastGestureWord != null) {
            refreshSmartSuggestion()
            return
        }
        // Same for the caret settling after a snippet expansion. An expansion
        // that ends in a letter would otherwise have its last word re-armed as
        // composing, and backspace edits a composing buffer instead of taking
        // the expansion back — which is the one thing backspace has to do here.
        if (lastRevertible?.kind == RevertibleCommit.Kind.SNIPPET) {
            refreshSmartSuggestion()
            return
        }
        val scrubbing = SystemClock.uptimeMillis() - lastCaretScrubMs < CARET_SCRUB_WINDOW_MS
        // A drag is still in progress, so this landing spot is not the one the
        // user means. The editor sends no update when the finger finally stops,
        // so the re-read has to be scheduled here or it never happens at all
        // (see [caretSettleJob]).
        if (scrubbing) {
            caretSettleJob = serviceScope.launch {
                delay(CARET_SCRUB_WINDOW_MS)
                caretSettleJob = null
                val settled = currentInputConnection
                if (settled != null && expectedSelStart >= 0 && expectedSelStart == expectedSelEnd) {
                    restartSuggestionsAtCursor(settled, expectedSelStart)
                }
            }
        }
        // No resume while a panel owns the screen: a word re-armed as
        // composing behind an open Grammar/AI/Translate panel hijacks that
        // panel's replace-field commit onto the composing region. Write-on-
        // keys handwriting and an in-flight Whisper transcription commit
        // their own text the same way, so they must not race a resume either.
        // Interactive voice typing is the exception to the dictation clause
        // above: it puts nothing of its own in the composing region, it
        // flushes whatever is being composed before its phrase lands, and
        // correcting a word by hand mid-session is what the mode is for.
        val voiceBlocksResume = when (state.voice.status) {
            VoiceStatus.LISTENING, VoiceStatus.FINISHING, VoiceStatus.TRANSCRIBING ->
                !state.settings.voiceBar.interactiveTyping()
            else -> false
        }
        val canResume = !scrubbing && state.settings.suggestions &&
            state.panel == PanelMode.NONE &&
            !keyboardHandwriteActive(state) &&
            !state.secureField && !state.fieldNoSuggestions &&
            state.allowsTypingIntelligence &&
            !state.typingTestActive && !state.emojiSearchActive &&
            !state.dictionarySearchActive && !state.mediaSearchActive &&
            !state.clipboardSearchActive && !state.pluginTypingActive &&
            !voiceBlocksResume &&
            // Last, so the one term that asks the engine anything is only
            // reached once the screen and the field have already said yes.
            composingResumable(state.composer, suggestionEngine?.hasWordSources == true)

        // Held outside the block so the fall-through below can reuse it rather
        // than asking the editor for the same 64 characters again — that read
        // is a blocking round-trip into the focused app, and this runs from
        // onUpdateSelection.
        var beforeText: CharSequence? = null
        if (canResume && newSelStart >= 0) {
            val before = ic.getTextBeforeCursor(64, 0)
            beforeText = before
            // Enough to carry the rest of the word the caret may be sitting
            // inside; the resume test below only ever looks at the first
            // character, so the wider window costs nothing extra (one read
            // either way).
            val after = ic.getTextAfterCursor(CARET_WORD_AHEAD, 0)
            // The word the caret is parked at the end of, or null — see
            // [resumableWordAt], which is also where "what counts as part of a
            // word" lives now that the answer is not "a letter".
            val word = resumableWordAt(before, after)
            if (word != null && before != null) {
                // Mark the existing word as composing without disturbing it,
                // then mirror it into the buffer so a keystroke extends it
                // and a backspace shortens it. previousWord comes from the
                // text ahead of the word, not the caret (which is inside it).
                // Mirror ONLY if the editor accepted the region: some editors
                // (web views, odd search boxes) refuse setComposingRegion, and
                // a buffer with no region behind it makes the next
                // setComposingText insert the whole word again at the caret —
                // the word "reappears" and the existing text can't be edited.
                // And only if newSelStart is still the live caret: the word was
                // read at the *current* cursor, so pairing it with a stale echo
                // offset puts the region over the wrong span (see caretStillAt).
                if (caretStillAt(ic, newSelStart) &&
                    ic.setComposingRegion(newSelStart - word.length, newSelStart)
                ) {
                    composing = StringBuilder(word)
                    composingCaseTrusted = false
                    val ahead = before.subSequence(0, before.length - word.length)
                    setContextFrom(ahead)
                    rebuildRecentWords(ahead)
                    _uiState.update { it.copy(composingPreview = word) }
                    refreshSuggestions()
                    return
                }
            }
            // Nothing to resume, but the caret may still be *touching* a word:
            // dropped into the middle of one, or parked right in front of it.
            // That word gets the strip too — a caret on a word is the user
            // looking at that word (#32) — read-only, because a composing
            // region cannot hold a caret in its middle (see [caretWord]).
            val touching = caretWordAt(before, after)
            if (touching != null) {
                val (head, tail) = touching
                // Context comes from the text ahead of the word, not from the
                // caret, which is inside it — same rule as the resume above.
                val ahead = before?.let { it.subSequence(0, it.length - head.length) }
                setContextFrom(ahead)
                rebuildRecentWords(ahead)
                caretWord = CaretWord(head, tail)
                refreshSuggestions()
                return
            }
        }
        // No word to resume: predict from the completed word behind the caret,
        // or clear when there is none. refreshSuggestions self-gates on the
        // field flags, so this stays correct in secure / no-suggestion fields.
        // `beforeText` is null exactly when the branch above never read it.
        val cached = beforeText
        if (cached != null) {
            setContextFrom(cached)
            rebuildRecentWords(cached)
        } else {
            syncPreviousWordFromField(ic)
        }
        refreshSuggestions()
    }

    /**
     * Whether [reportedSelStart] is still where the editor's caret actually
     * sits. Selection updates queue behind fast keystrokes and backspaces, so
     * an echo can describe a caret the field has since moved past — and a
     * composing region placed from those stale offsets lands on the wrong
     * characters (the visible symptom is the underline hugging only the last
     * letter of a word). The extracted text is read live, so a mismatch means
     * "skip and wait for the next echo". Editors that don't support extraction
     * return null and are taken at their word, which is today's behavior.
     */
    private fun caretStillAt(ic: InputConnection, reportedSelStart: Int): Boolean {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return true
        if (extracted.selectionStart < 0) return true
        return extracted.startOffset + extracted.selectionStart == reportedSelStart
    }

    /**
     * Deletes the word before the cursor — one step of the backspace swipe.
     * Trailing whitespace goes with the word, so repeated steps chew back
     * through a sentence the way ctrl+backspace does on a desktop.
     */
    private fun onDeleteWord() {
        // A panel search owns the backspace key while it is open; word-deleting
        // the real field behind it would edit text the user cannot see. The
        // same goes for every other box that eats keystrokes — a plugin's text
        // box, the AI instruction, the typing test — which onDelete already
        // handles and this had drifted out of step with.
        if (backspaceEditsBuffer()) {
            onDelete()
            return
        }
        val ic = currentInputConnection ?: return
        // One swipe, one event — however many characters it takes with it.
        recordStat { onBackspace(System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        clearCaretWord()
        if (hasSelection(ic)) {
            dropComposingForSelectionEdit(ic)
            invalidateExpectedSelection()
            ic.commitText("", 1)
            return
        }
        // A word in progress lives in the composing buffer, not the field.
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            composingTouch.clear()
            keystrokeTiming.reset()
            updateComposingText(ic)
            refreshSuggestions()
            return
        }
        val before = ic.getTextBeforeCursor(96, 0) ?: return
        val length = WordDelete.lengthBefore(before)
        if (length > 0) {
            ic.deleteSurroundingText(length, 0)
            lastGestureWord = null
            lastRevertible = null
            clearSwapOffer()
            // The word that was deleted is gone as context, but whatever now
            // sits behind the cursor is the real one — nulling it outright
            // meant a swipe-delete mid-sentence stopped predicting until the
            // next word was typed.
            syncPreviousWordFromField(ic)
            // Both lists; see the gesture-undo path above.
            _uiState.update {
                it.copy(suggestions = emptyList(), emojiSuggestions = emptyList())
            }
        }
    }

    /**
     * Whether the backspace key is currently editing one of the keyboard's own
     * text boxes — a panel search, a plugin input, the typing test — rather
     * than the field behind it. Every one of them is a buffer the service owns
     * in [KeyboardUiState], with no cursor of its own in the editor, so nothing
     * that works through the input connection applies while one is up.
     */
    private fun backspaceEditsBuffer(): Boolean {
        val state = _uiState.value
        return state.emojiSearchActive || state.dictionarySearchActive ||
            state.clipboardSearchActive || state.pluginTypingActive ||
            state.aiCustomInputActive || state.typingTestActive ||
            state.calcTypingActive || state.converterTypingActive ||
            (state.mediaSearchActive && state.panel.hasMediaSearch) ||
            ((state.panel == PanelMode.HANDWRITING || keyboardHandwriteActive(state)) &&
                state.handwriting.strokes.isNotEmpty())
    }

    // ---- backspace swipe with preview (issue #36) ----

    /**
     * How much text behind the cursor a backspace swipe reads when it starts.
     *
     * Read once and kept, so dragging back to the right costs nothing and the
     * editor is not asked the same question on every pointer event. It is also
     * the swipe's reach: at the shipped step sizes even the character mode
     * would need metres of dragging to walk past it.
     */
    private val deleteSwipeLookback = 1024

    /** True once a backspace swipe has taken the field's selection over. */
    private var deleteSwipeActive = false

    /** Right edge of the preview: where the selection ended when the swipe began. */
    private var deleteSwipeEnd = -1

    /** Left edge before the swipe grew it — the old selection start, or the caret. */
    private var deleteSwipeBase = -1

    /** Units the preview currently covers, so a repeat of the same step is free. */
    private var deleteSwipeUnits = 0

    /** The text behind [deleteSwipeBase], read once when the swipe started. */
    private var deleteSwipeBefore: CharSequence = ""

    /** How far behind [deleteSwipeBase] 1, 2, 3... units reach, in UTF-16 units. */
    private val deleteSwipeSteps = ArrayList<Int>()

    /**
     * One step of a backspace swipe that deletes as it goes (the preview
     * turned off, or an editor that could not show one).
     */
    fun onDeleteSwipeUnit(byWord: Boolean) {
        if (byWord) {
            onDeleteWord()
            return
        }
        // A swipe is not a tap: its first step must delete the character the
        // finger asked for, not spend itself undoing an autocorrect or a
        // glide the way a deliberate backspace press does. The word path
        // drops both for the same reason.
        lastGestureWord = null
        lastRevertible = null
        onDelete()
    }

    /**
     * Selects the [units] units before where the swipe started, previewing
     * what a release would delete. See [DeleteSwipeCallbacks.onSelect] for the
     * return value.
     */
    fun onDeleteSwipeSelect(units: Int, byWord: Boolean): Int {
        // Nothing to preview against a buffer the keyboard draws itself: it
        // has no selection, so the gesture falls back to deleting as it goes.
        if (backspaceEditsBuffer()) return -1
        val ic = currentInputConnection ?: return -1
        if (!deleteSwipeActive && !beginDeleteSwipe(ic)) return -1
        val covered = minOf(units.coerceAtLeast(0), growDeleteSwipeSteps(units, byWord))
        if (covered == deleteSwipeUnits) return covered
        val length = if (covered <= 0) 0 else deleteSwipeSteps[covered - 1]
        val start = (deleteSwipeBase - length).coerceAtLeast(0)
        ic.setSelection(start, deleteSwipeEnd)
        // Recorded directly for the reason selectWordAtCursor does it: the
        // editor's echo is behind, and until it lands a backspace would go by
        // the stale collapsed caret instead of the range now selected.
        expectedSelStart = start
        expectedSelEnd = deleteSwipeEnd
        deleteSwipeUnits = covered
        return covered
    }

    /** The finger lifted over a preview: delete what it selected. */
    fun onDeleteSwipeCommit() {
        val ic = currentInputConnection
        if (!deleteSwipeActive || ic == null || deleteSwipeUnits <= 0) {
            resetDeleteSwipe()
            return
        }
        // One swipe, one event — however many characters it takes with it.
        recordStat { onBackspace(System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        clearCaretWord()
        dropComposingForSelectionEdit(ic)
        invalidateExpectedSelection()
        ic.commitText("", 1)
        lastGestureWord = null
        lastRevertible = null
        clearSwapOffer()
        // What was deleted is gone as context; whatever now sits behind the
        // cursor is the real previous word. Same reasoning as [onDeleteWord].
        syncPreviousWordFromField(ic)
        _uiState.update {
            it.copy(suggestions = emptyList(), emojiSuggestions = emptyList())
        }
        resetDeleteSwipe()
    }

    /** The swipe ended with nothing selected: put the field back, delete nothing. */
    fun onDeleteSwipeCancel() {
        val ic = currentInputConnection
        if (deleteSwipeActive && ic != null && deleteSwipeUnits > 0) {
            ic.setSelection(deleteSwipeBase, deleteSwipeEnd)
            expectedSelStart = deleteSwipeBase
            expectedSelEnd = deleteSwipeEnd
        }
        resetDeleteSwipe()
    }

    /**
     * Takes the selection over for a new swipe. False when this editor cannot
     * say where its cursor is, which is the one case the preview cannot work
     * in at all.
     */
    private fun beginDeleteSwipe(ic: InputConnection): Boolean {
        // A word in progress has to become ordinary text before a selection
        // can cover it: a selection inside a composing region is honored
        // differently by every editor, and the preview has to show exactly
        // what the release will take.
        dropComposingForSelectionEdit(ic)
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (start < 0 || end < 0) return false
        deleteSwipeBase = extracted.startOffset + minOf(start, end)
        deleteSwipeEnd = extracted.startOffset + maxOf(start, end)
        if (deleteSwipeBase < 0 || deleteSwipeEnd < deleteSwipeBase) return false
        // Reads the text before the selection, which is exactly the text
        // before [deleteSwipeBase] — the edge the swipe walks left from.
        deleteSwipeBefore = ic.getTextBeforeCursor(deleteSwipeLookback, 0) ?: ""
        deleteSwipeSteps.clear()
        deleteSwipeUnits = 0
        deleteSwipeActive = true
        return true
    }

    /**
     * Walks another unit back for every one [units] asks for that has not been
     * measured yet, and returns how many are known. Fewer than asked means the
     * swipe has reached the start of what it read.
     */
    private fun growDeleteSwipeSteps(units: Int, byWord: Boolean): Int {
        val text = deleteSwipeBefore
        while (deleteSwipeSteps.size < units) {
            val taken = deleteSwipeSteps.lastOrNull() ?: 0
            if (taken >= text.length) break
            val head = text.subSequence(0, text.length - taken)
            val step = if (byWord) WordDelete.lengthBefore(head) else charDeleteLength(head)
            if (step <= 0) break
            deleteSwipeSteps.add(taken + step)
        }
        return deleteSwipeSteps.size
    }

    /** Forgets a swipe, without touching the field. */
    private fun resetDeleteSwipe() {
        deleteSwipeActive = false
        deleteSwipeUnits = 0
        deleteSwipeBase = -1
        deleteSwipeEnd = -1
        deleteSwipeBefore = ""
        deleteSwipeSteps.clear()
    }

    private fun onSpace() {
        // Ahead of the input-connection check: a typing test scores space as
        // the word separator and never touches the field, so it must still
        // work in a window that has no editor focused.
        if (_uiState.value.typingTestActive) {
            typingTestSpace()
            return
        }
        // Ahead of the editor check too: the Custom instruction must accept a
        // space even in a window with no focused field.
        if (_uiState.value.aiCustomInputActive) {
            aiCustomInputEdit { it + " " }
            return
        }
        // Also ahead of the editor check: a plugin's box must take a space even
        // in a window with no field focused at all.
        if (_uiState.value.pluginTypingActive) {
            pluginInputEdit { it + " " }
            return
        }
        // The calculator's `mod ` operator is spelled with a space; the
        // converters' amount has no use for one, so the press is just spent.
        if (_uiState.value.calcTypingActive) {
            calcEdit { it + " " }
            return
        }
        if (_uiState.value.converterTypingActive) return
        val ic = currentInputConnection ?: return
        val state = _uiState.value
        val now = System.currentTimeMillis()
        // One-shot, spent by this press whatever it ends up doing.
        val followsPunctuationSpace = pendingPunctuationSpace
        pendingPunctuationSpace = false
        val followsWordSpace = pendingWordSpace
        pendingWordSpace = false
        // Same as a typed character: the mid-word strip described the field
        // before this press.
        clearCaretWord()

        if (state.emojiSearchActive) {
            updateQuery { it.copy(emojiQuery = it.emojiQuery + " ") }
            refreshEmojiResults()
            return
        }
        if (state.mediaSearchActive && state.panel.hasMediaSearch) {
            updateQuery { it.copy(mediaQuery = it.mediaQuery + " ") }
            if (state.panel != PanelMode.QR_GEN) scheduleMediaLiveSearch()
            return
        }

        // Multi-word dictionary entries ("give up") are legitimate lookups.
        if (state.dictionarySearchActive) {
            updateQuery { it.copy(dictionaryQuery = it.dictionaryQuery + " ") }
            return
        }

        // Space over a selection replaces it; skip autocorrect/double-space.
        if (hasSelection(ic)) {
            dropComposingForSelectionEdit(ic)
            invalidateExpectedSelection()
            ic.commitText(" ", 1)
            recordStat { onSeparator(now, SystemClock.uptimeMillis()) }
            lastSpaceTime = 0
            maybeAutoCapitalize()
            return
        }

        // The space after "hello," was typed a keystroke ago by the auto-space
        // rule. Habit still reaches for the spacebar, and obeying it here would
        // give "hello,  world" — so the press is spent confirming the space
        // that is already there. Pressing space again inserts a real one, since
        // the one-shot is gone by then.
        if (followsPunctuationSpace) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && before[0] in AUTO_SPACE_PUNCTUATION) {
                lastSpaceTime = now
                maybeAutoCapitalize()
                return
            }
        }

        // Same story for the space a glide typed: the finger lifts and the
        // thumb reaches for the spacebar out of habit. Spending the press on
        // the space already there keeps [lastSpaceTime] armed, so a second
        // press still turns it into ". " the way it would after typing.
        if (followsWordSpace) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && !before[0].isWhitespace()) {
                lastSpaceTime = now
                maybeAutoCapitalize()
                // The press also settles the word behind that space, the way a
                // space settles a typed one: a swipe's alternates stop being the
                // answer and the strip moves on to what comes next. Nothing is
                // committed here, so no selection update arrives to re-derive
                // any of it — without this the alternates stayed up and the
                // next-word predictions only appeared on a second press (#35).
                // The context is re-read from the field because a swipe commits
                // without going through the paths that keep [previousWord]
                // current.
                lastGestureWord = null
                syncPreviousWordFromField(ic)
                refreshSuggestions()
                return
            }
        }

        if (runKeymanFrameKey(VK_SPACE)) {
            lastSpaceTime = now
            return
        }

        // Conversion IME (Pinyin, Japanese): space commits the top candidate for
        // the leading syllable(s) and re-converts the tail — no trailing literal
        // space (CJK runs words together). An empty buffer falls through to a
        // plain space.
        if (composing.isNotEmpty() && state.composer.isConversion) {
            val buf = composing.toString()
            val top = state.composer.candidates(buf).firstOrNull()
            if (top != null) {
                commitConversionPrefix(ic, top, index = 0)
            } else {
                commitConversionPrefix(ic, state.composer.composeBuffer(buf))
            }
            lastSpaceTime = now
            return
        }

        // The word and the space that ends it are one edit as far as the app is
        // concerned: batching them means one selection update instead of two,
        // which is what keeps the backspace-to-revert window armed (see
        // [revertArmedAt]) and stops the corrected word flashing on its own for
        // a frame. Only the composing paths below can have started a batch —
        // every early return past this point runs with an empty buffer.
        val batched = composing.isNotEmpty()
        if (batched) ic.beginBatchEdit()
        val committed = commitComposing(
            ic,
            autocorrect = state.settings.autocorrect,
            fixApostrophes = state.settings.autoApostrophe,
            expandPatterns = true,
        )
        // The expansion left the caret inside itself, at its {cursor} marker.
        // A space committed there lands in the middle of the text the snippet
        // inserted, so this press is spent on the expansion instead.
        if (swallowTerminatorAfterCommit) {
            swallowTerminatorAfterCommit = false
            if (batched) ic.endBatchEdit()
            lastSpaceTime = now
            return
        }

        // How close together the two spaces have to be. One number for both
        // rules below, so the tab and the full stop never disagree about what
        // counts as a double space.
        val doubleSpaceWindow = state.settings.textEditing.doubleSpaceWindowMs
        // Double-tap space inserts a tab. Checked before the period rule so
        // enabling it wins, and unlike the period it works anywhere a space
        // was just typed (indenting at a line start has no word before it).
        if (!committed && state.settings.doubleSpaceTab && now - lastSpaceTime < doubleSpaceWindow) {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (before == " ") {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("\t", 1)
                lastSpaceTime = 0
                return
            }
        }

        // Double-space inserts ". "
        // Only in plain text fields: a double space in an email, URI or
        // number box must stay two spaces, not become ". ".
        if (!committed && state.settings.doubleSpacePeriod &&
            state.fieldKind == FieldKind.TEXT && now - lastSpaceTime < doubleSpaceWindow
        ) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.endsWith(" ") && before.length == 2 && !before[0].isWhitespace()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0
                // Arm the shift-to-cancel: a shift press now drops this space.
                pendingAutoSpace = true
                armRevertGuard()
                maybeAutoCapitalize()
                return
            }
        }
        ic.commitText(" ", 1)
        // The one plain-space landing: the confirm-a-space-already-there and
        // double-space returns above add no character worth counting.
        recordStat { onSeparator(now, SystemClock.uptimeMillis()) }
        if (batched) ic.endBatchEdit()
        lastSpaceTime = now
        maybeAutoCapitalize()
        // Next-word predictions (learned bigrams, including word → emoji)
        // appear once the word is committed.
        refreshSuggestions()
    }

    /**
     * [hardwareShift] is the physical keyboard's own shift, read from the key
     * event's meta state. Null for a tap on the on-screen Enter, which asks the
     * ui state instead. They are deliberately separate: a physical shift is
     * held, not latched, so it never appears in [KeyboardUiState.shiftState].
     */
    private fun onEnter(hardwareShift: Boolean? = null) {
        val state = _uiState.value
        // Enter is not part of a typing test, and letting it through would
        // put a newline in the field behind the panel.
        if (state.typingTestActive) return
        // Enter runs the Custom action rather than dropping a newline into the
        // app behind the panel.
        if (state.aiCustomInputActive) {
            onAiRunCustom()
            return
        }
        // Enter finishes typing into a plugin's box: it gives the keys back to
        // the field rather than putting a newline in the app behind the panel.
        if (state.pluginTypingActive) {
            onPluginInputFocus(null)
            return
        }
        if (state.dictionarySearchActive) {
            onDictionaryLookup(state.dictionaryQuery)
            return
        }
        // Enter in the calculator is the `=` key. Only when no focus ring is
        // up — [handlePanelNavKey] consumes Enter first while one is.
        if (state.calcTypingActive) {
            calcEvaluate()
            return
        }
        // The converters convert live; Enter has nothing to run and must not
        // reach the field behind the panel.
        if (state.converterTypingActive) return
        // QR builds its content as you type; Enter adds a newline to the
        // buffer (WiFi/vCard payloads span lines) rather than searching.
        if (state.mediaSearchActive && state.panel == PanelMode.QR_GEN) {
            updateQuery { it.copy(mediaQuery = it.mediaQuery + "\n") }
            return
        }
        // Enter in a media search box runs the search instead of typing a
        // newline into the app behind the keyboard.
        if (state.mediaSearchActive && state.panel.hasMediaSearch) {
            runMediaSearch()
            return
        }
        val ic = currentInputConnection ?: return
        // Whether this ends up a newline or an editor action, it ends the
        // word the same way a space does.
        recordStat { onSeparator(System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        commitComposing(ic, autocorrect = false, expandPatterns = true)
        // Same as the spacebar: a newline typed at a caret parked inside an
        // expansion would break the text the snippet just inserted.
        if (swallowTerminatorAfterCommit) {
            swallowTerminatorAfterCommit = false
            return
        }
        // Shift held over Enter overrides the field's action: in a chat app the
        // box declares Send, so this is the only way to put a line break in a
        // message without sending it. The key draws the newline glyph while the
        // override is live (see enterActionFor), so it still does what it shows.
        val forceNewline = state.settings.layoutBehavior.shiftEnterNewline &&
            (hardwareShift ?: state.softShiftForcesNewline)
        // Same decoder that labels the key, so Enter always does what the
        // key is drawing — including an app's own actionId behind a custom
        // actionLabel. Null means "no action": type a real newline.
        val action = if (forceNewline) null else currentInputEditorInfo.editorActionId()
        if (action != null) {
            ic.performEditorAction(action)
        } else if (forceNewline) {
            // Committed rather than sent as a key event, which is what the
            // override needs and what a plain newline does not. A field that
            // declares an action is a single-line TextView, and TextView answers
            // KEYCODE_ENTER on one of those by firing the editor action — so the
            // raw event sent the WhatsApp message the override had just decided
            // not to send. commitText has nothing to intercept it.
            typeNewline(ic)
            // The armed shift was spent on the override, exactly as a letter
            // would have spent it — otherwise the next Enter overrides too.
            consumeShift()
            maybeAutoCapitalize()
        } else {
            // No action declared at all: a genuinely multi-line field, a web
            // page, a terminal. These want the key event — a committed "\n"
            // reaches a page behind keyCode 229 with no Enter for its handlers
            // to see, which is the mirror image of the digit case above.
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            maybeAutoCapitalize()
        }
    }

    /**
     * Puts a line break in the field without going near the editor action, for
     * the two paths that mean it: Shift+Enter's override and a [KeyAction.Newline]
     * key (which is what the enter key's long-press alternate is).
     *
     * See [KeyAction.Newline] for why this is a commit and not a key event.
     */
    private fun typeNewline(ic: InputConnection) {
        ic.commitText("\n", 1)
    }

    /**
     * A key — in practice the enter key's long-press alternate — that types a
     * line break whatever the field declares.
     *
     * Ends the word the same way [onEnter] does, because it is the same
     * character landing: the composing buffer flushes first, and a snippet
     * expansion that has parked the caret inside itself swallows the break
     * rather than breaking the text it just inserted.
     */
    private fun onNewline() {
        val ic = currentInputConnection ?: return
        recordStat { onSeparator(System.currentTimeMillis(), SystemClock.uptimeMillis()) }
        commitComposing(ic, autocorrect = false, expandPatterns = true)
        if (swallowTerminatorAfterCommit) {
            swallowTerminatorAfterCommit = false
            return
        }
        typeNewline(ic)
        maybeAutoCapitalize()
    }

    private fun toggleSymbols() {
        // Leaving the letter layer ends the on-keyboard writing surface.
        if (_uiState.value.layoutMode == LayoutMode.LETTERS) dropKeyboardHandwritingInk()
        _uiState.update {
            it.copy(
                layoutMode = when (it.layoutMode) {
                    LayoutMode.LETTERS -> LayoutMode.SYMBOLS
                    LayoutMode.SYMBOLS -> LayoutMode.SYMBOLS_SHIFTED
                    LayoutMode.SYMBOLS_SHIFTED -> LayoutMode.SYMBOLS
                    // ?123 from the Fn layer leaves it, lock and all: the user
                    // asked for a different grid, not for Fn to persist under it.
                    LayoutMode.FN -> LayoutMode.SYMBOLS
                    // Same from a secondary layout: its ?123 key is a way out.
                    LayoutMode.SECONDARY -> LayoutMode.SYMBOLS
                },
                fnLocked = false,
                fnReturn = null,
            )
        }
    }

    /**
     * Shows a secondary layout (issue #62) — or, when it is the one already
     * showing, goes back to the letters, so the key that opened it is also the
     * key that closes it. A key naming a layout this state cannot show (deleted,
     * or no longer secondary) does nothing rather than switching to something
     * arbitrary, the same dead-key outcome a tool key for a missing tool gets.
     */
    private fun openSecondaryLayout(id: String) {
        val state = _uiState.value
        if (id !in state.layouts.secondaries) return
        if (state.layoutMode == LayoutMode.SECONDARY && state.secondaryLayoutId == id) {
            closeSecondaryLayout()
            return
        }
        // Leaving the letter layer ends the on-keyboard writing surface.
        if (state.layoutMode == LayoutMode.LETTERS) dropKeyboardHandwritingInk()
        _uiState.update {
            it.copy(
                layoutMode = LayoutMode.SECONDARY,
                secondaryLayoutId = id,
                fnLocked = false,
                fnReturn = null,
            )
        }
    }

    private fun closeSecondaryLayout() {
        _uiState.update {
            it.copy(layoutMode = LayoutMode.LETTERS, fnLocked = false, fnReturn = null)
        }
    }

    /**
     * The Custom layout tool: puts the configured secondary layout on screen
     * (the first one, until the tool's page picks another) and takes it off
     * again. "Off" covers a secondary layout a key opened as well — the tool is
     * lit whenever one is up, and a lit toggle has to be the way down.
     */
    private fun onCustomLayoutToggle() {
        vibrate()
        val state = _uiState.value
        val grids = state.layouts.secondaries
        if (grids.isEmpty()) return
        if (state.layoutMode == LayoutMode.SECONDARY) {
            closeSecondaryLayout()
            return
        }
        val id = state.settings.layoutBehavior.customLayoutToolId?.takeIf { it in grids }
            ?: grids.keys.first()
        openSecondaryLayout(id)
    }

    /**
     * The user's secondary layouts, compiled, cached against the identity of
     * the custom-layout list: the settings object holds one instance until a
     * layout changes, so this is a map lookup on the hot path and a rebuild of
     * a handful of grids when the editor saves.
     */
    private fun secondaryGrids(customs: List<LayoutSpec>): Map<String, KeyboardLayout> {
        secondaryGridCache?.let { (cached, grids) -> if (cached === customs) return grids }
        val grids = secondaryLayouts(customs).associate { spec ->
            spec.id to spec.repair().spec.compile(LayoutLayer.LETTERS)
        }
        secondaryGridCache = customs to grids
        return grids
    }

    private var secondaryGridCache: Pair<List<LayoutSpec>, Map<String, KeyboardLayout>>? = null

    /**
     * The grids reachable from the focused field, compiled once here rather than
     * per recomposition.
     *
     * Resolution lives in the service because it is the side that owns the
     * layout store, and because [keyRowsHeight] has to be a pure function of
     * state: the reserved row span is the maximum over *every* reachable layer,
     * which the rendering code — looking at one layer at a time — could never
     * compute for itself.
     *
     * The result is memoised so the returned instance is reference-stable.
     * [KeyboardUiState]'s generated `equals` walks its fields, so handing out a
     * fresh set per emission would make every state comparison walk every key.
     */
    /**
     * The layout being typed on: the field's override if it asked for one,
     * otherwise the user's choice. Resolved rather than raw, so an id whose
     * layout was deleted heals to the default instead of selecting nothing.
     */
    private fun activeLayoutSpec(settings: KeyboardSettings): LayoutSpec =
        resolveLayout(settings.customLayouts, fieldLayoutOverride ?: baseLayoutId(settings))

    /**
     * The layout to type on before any field override: the one remembered for the
     * focused app when per-app memory is on, otherwise the global choice. A
     * remembered id whose layout has since been deleted heals back to the global
     * pick rather than snapping to the default.
     *
     * An explicit pick that has not been persisted yet ([pendingLayoutId]) wins
     * over the stored one, and clears itself the moment the store catches up.
     */
    private fun baseLayoutId(settings: KeyboardSettings): String {
        val stored = storedLayoutId(settings)
        val pending = pendingLayoutId ?: return stored
        if (stored == pending) {
            pendingLayoutId = null
            return stored
        }
        return pending
    }

    /** [baseLayoutId] as the settings alone see it, before any pending pick. */
    private fun storedLayoutId(settings: KeyboardSettings): String {
        if (!settings.perAppLanguage.enabled) return settings.activeLayoutId
        val remembered = settings.perAppLanguage.layoutByPackage[currentPackage]
            ?.takeIf { id -> resolveLayout(settings.customLayouts, id).id == id }
        return remembered ?: settings.activeLayoutId
    }

    /**
     * The layout [onLayoutSelected] has just moved to, held until the settings
     * flow reports it back.
     *
     * The settings collector re-derives the active layout from the stored ids on
     * *every* emission, and the writes behind a pick are asynchronous — so any
     * other settings write racing one lands with the new layout on screen but the
     * old id still in the store, and the collector snaps the keyboard back to the
     * old layout for a frame.
     *
     * The Fancy tool does exactly that: it enables the fancy layout (one write)
     * and then selects it (another). The first write's emission rolled the pick
     * back, so the fancy style strip appeared, vanished for a frame and came back
     * — a 40dp row flickering in and out, taking the whole keyboard's height with
     * it. Holding the pick here until it is stored keeps the two writes looking
     * like the one action they are.
     */
    private var pendingLayoutId: String? = null

    /**
     * The grids the keyboard draws for [spec], repaired.
     *
     * Repaired *here* because this is the last gate before a layout becomes the
     * thing you type on, and it is the only one that catches an edit made after
     * the layout was turned on. `setActiveLayoutId` repairs what it stores, but
     * the editor saves on every keystroke — deliberately, so it does not fight
     * you — and nothing re-checked an already-enabled layout afterwards. Delete
     * the row that carries ⌫ from a layout that is on and the live keyboard lost
     * its backspace, which is the one outcome the repair pass exists to prevent.
     * The editor still reads the layout unrepaired, so the grid it shows is what
     * the user is building.
     *
     * Cached against the spec rather than its id alone: keyed on the id, an edit
     * never reached a keyboard whose process was still alive, so the grid stayed
     * at whatever it was when the layout was first drawn. Structural equality
     * short-circuits on identity, and the settings flow hands back the same
     * instance until something actually changes.
     */
    private fun resolveLayoutSet(
        spec: LayoutSpec,
        fieldKind: FieldKind,
        form: DeviceForm,
        numberRowShown: Boolean,
        customs: List<LayoutSpec>,
    ): LayoutSet {
        val key = LayoutSetKey(spec.id, fieldKind, form, numberRowShown)
        // The secondary grids ride along by reference, so an edit to one of
        // them — which re-decodes the custom list — misses the cache too.
        val secondaries = secondaryGrids(customs)
        layoutSetCache[key]?.let { (cached, set) ->
            if (cached == spec && set.secondaries === secondaries) return set
        }
        val safe = spec.repair().spec
        val letters = safe.compile(LayoutLayer.LETTERS)
        // Only the letters layer widens. The symbols and Fn layers have no shift
        // key and so decline on their own, and the numeric keypads must never be
        // stretched to twelve columns — a four-column PIN pad at that width is
        // not a keypad any more.
        val expand = form.isTablet && safe.tabletExpand
        val gridWidth = if (expand) tabletGridWidth(letters, form) else null
        val set = LayoutSet(
            letters = if (gridWidth != null) {
                letters.expandForTablet(form, numberRowShown)
            } else {
                letters
            },
            symbols = safe.compile(LayoutLayer.SYMBOLS),
            symbolsShifted = safe.compile(LayoutLayer.SYMBOLS_SHIFTED),
            // Only when the layout actually defines one: compile() falls back
            // to the shipped grid for a missing layer, which would give every
            // layout an Fn layer that is really a second copy of the letters.
            fn = safe.layer(LayoutLayer.FN)?.let { safe.compile(LayoutLayer.FN) },
            numeric = fieldKind.numericLayer?.let(safe::compile),
            // Same "only when authored" rule as Fn: the Numpad panel draws its
            // own hardcoded pad otherwise, with the calculator-order setting.
            number = safe.layer(LayoutLayer.NUMBER)?.let { safe.compile(LayoutLayer.NUMBER) },
            numberRows = buildMap {
                safe.numberRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
                safe.numberRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
                safe.numberRowFor(LayoutLayer.SYMBOLS_SHIFTED)
                    ?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
                safe.numberRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
            },
            gridWidth = gridWidth,
            secondaries = secondaries,
        )
        layoutSetCache[key] = spec to set
        return set
    }

    /**
     * What a cached [LayoutSet] was resolved *for*.
     *
     * The screen form and the digit-row setting are part of the key, not just the
     * id and the field: both reshape the grid, and keyed without them an unfold
     * would keep serving the phone layout for as long as the process lived —
     * a bug that survives a green test run because nothing else re-resolves.
     */
    private data class LayoutSetKey(
        val layoutId: String,
        val fieldKind: FieldKind,
        val form: DeviceForm,
        val numberRowShown: Boolean,
    )

    private val layoutSetCache = HashMap<LayoutSetKey, Pair<LayoutSpec, LayoutSet>>()

    private fun switchLanguage() {
        val state = _uiState.value
        // Cycles layout ids, not modes: three custom layouts all based on
        // English are three distinct stops, where cycling modes would collapse
        // them into one and make them unreachable from the keyboard.
        val ids = state.settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
        onLayoutSelected(ids[(ids.indexOf(state.layoutId) + 1).mod(ids.size)])
    }

    /**
     * Opens the system's keyboard picker, the "Choose input method" list that
     * holds every keyboard turned on for the device. Reached from a key bound
     * to [KeyAction.InputMethodPicker] and from the last row of the language
     * list.
     *
     * The buffer is committed first. The picker hands the field to another
     * keyboard, and text left composing at that moment belongs to a keyboard
     * that is about to stop owning the input connection, which is how a
     * half-typed word disappears on the switch.
     *
     * The platform only grants this to the keyboard that owns the current input
     * connection, which is exactly what we are, so unlike the crash screen's
     * copy of this there is no dance to work out whether it appeared. It is
     * still wrapped: an OEM build that refuses outright must not take the
     * keyboard down with it, and the input-method settings screen reaches the
     * same place in two more presses.
     */
    private fun showInputMethodPicker() {
        currentInputConnection?.let { commitComposing(it, autocorrect = false) }
        val imm = getSystemService(InputMethodManager::class.java)
        if (imm != null && runCatching { imm.showInputMethodPicker() }.isSuccess) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Spacebar swipe (or 🌐 cycle): switch to an explicit layout. */
    fun onLayoutSelected(layoutId: String) {
        val spec = resolveLayout(_uiState.value.settings.customLayouts, layoutId)
        currentInputConnection?.let { commitComposing(it, autocorrect = false) }
        // An explicit switch beats what the field asked for: the user can
        // see the box they are typing in, FORCE_ASCII and hintLocales are
        // only the app's guess.
        fieldLayoutOverride = null
        // Claimed before the state update, so a settings emission arriving
        // between here and the store catching up does not roll the pick back.
        pendingLayoutId = spec.id
        // Keeps the rule engine paired with the grid on screen: a
        // language switch must not leave the old keyboard's rules
        // running against the new grid.
        syncKeymanSession(spec)
        _uiState.update {
            it.copy(
                language = spec.language(),
                script = spec.script(),
                composer = composerFor(spec.script(), spec.composerType()),
                layoutId = spec.id,
                layoutName = spec.name,
                layouts = resolveLayoutSet(
                    spec,
                    it.fieldKind,
                    deviceForm.value,
                    it.settings.numberRow,
                    it.settings.customLayouts,
                ),
                layoutMode = LayoutMode.LETTERS,
            )
        }
        // A chord or morse sequence half-typed on the old layout must not leak
        // into the new one (or worse, keep a dot counted as held forever).
        resetChordInputs()
        refreshKarContext()
        // The typing test follows the language: a prompt dealt in one
        // language cannot be typed on another's keys, so the switch re-deals.
        // The panel's own language chip lands here too, via the same layout
        // switch the spacebar makes.
        _uiState.value.let { switched ->
            if (switched.panel == PanelMode.TYPING_TEST &&
                switched.typingTest.languageId != switched.language.id
            ) {
                startTypingTest()
            }
        }
        // The handwriting model follows the input language; a switch while
        // the panel is open — or while writing on the keys — re-checks the new
        // model and drops pending ink.
        if (_uiState.value.panel == PanelMode.HANDWRITING ||
            keyboardHandwriteActive(_uiState.value)
        ) {
            refreshHandwritingStatus()
        }
        // Same for dictation: restart the session in the new language. The
        // collapsed bar's language switch lands here too — but only mid-
        // session: the bar idles with the mic closed, and a language change
        // must not open it uninvited.
        if (_uiState.value.panel == PanelMode.VOICE ||
            (voiceBarShowing() && voiceActive())
        ) {
            startVoice()
        }
        serviceScope.launch { settingsRepository.setActiveLayoutId(spec.id) }
        // Per-app memory: an explicit pick is what this app should reopen on.
        // The global write above still moves, so apps with no stored pick keep
        // following the last-used layout.
        if (_uiState.value.settings.perAppLanguage.enabled) {
            currentPackage?.let { pkg ->
                serviceScope.launch { settingsRepository.setAppLayout(pkg, spec.id) }
            }
        }
        mirrorSubtypeToOs(spec)
    }

    /** Resource id for the subtype label the switcher shows, per the label setting. */
    private fun subtypeNameResId(settings: KeyboardSettings): Int =
        if (settings.subtypeAppNameFirst) R.string.subtype_app_label else 0

    /**
     * Mirrors the enabled layouts to the OS as additional input-method subtypes,
     * so the system language switcher lists them and can switch between them.
     * Off (the [KeyboardSettings.osLanguageSwitcher] toggle) registers an empty
     * set, clearing any previously exposed subtypes. method.xml declares no
     * static subtype either, so off leaves the keyboard with none at all and the
     * switcher lists it under its own name rather than under a language.
     * Diffed against the last write via [registeredSubtypeSig].
     */
    // The String-id overload is deprecated on new SDKs but is the only one that
    // exists at minSdk 24 — the typed replacement is API 36+.
    @Suppress("DEPRECATION")
    private fun registerSubtypes(settings: KeyboardSettings) {
        val ids = settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
        val nameResId = subtypeNameResId(settings)
        val sig = if (!settings.osLanguageSwitcher) "off"
        else "${ids.joinToString(",")}#$nameResId"
        if (sig == registeredSubtypeSig) return
        val subtypes = if (settings.osLanguageSwitcher) {
            ids.map { subtypeFor(resolveLayout(settings.customLayouts, it), nameResId) }.toTypedArray()
        } else {
            emptyArray()
        }
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        runCatching { imm.setAdditionalInputMethodSubtypes(imeId, subtypes) }
            .onSuccess {
                registeredSubtypeSig = sig
                enableSubtypes(imm, if (settings.osLanguageSwitcher) ids else emptyList())
            }
    }

    /**
     * Registering a subtype is not the same as it being *enabled*, and only
     * enabled ones reach the switcher. With nothing explicitly enabled — the
     * default for every IME — the framework derives the enabled set from the
     * system locales, which on a normal single-locale phone is exactly one
     * keyboard subtype. That is why the switcher listed one language however
     * many we registered.
     *
     * API 34+ lets an IME write its own entry in
     * `Settings.Secure.ENABLED_INPUT_METHODS`; an empty array resets to that
     * locale-derived default, which is what the switcher-off path wants — and
     * with no registered subtypes left to derive from, that default is empty. The
     * framework keys the entry on [InputMethodSubtype.hashCode], which is the
     * explicit [stableSubtypeId] we hand the builder in [subtypeFor].
     *
     * On 24–33 there is no such API: the languages have to be ticked by hand in
     * the system subtype enabler, which the settings screen links to.
     *
     * Written before the first unlock this is volatile (the framework says so),
     * hence the re-register in [onUserUnlocked].
     */
    private fun enableSubtypes(imm: InputMethodManager, layoutIds: List<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val hashes = layoutIds.map { stableSubtypeId(it) }.toIntArray()
        runCatching { imm.setExplicitlyEnabledInputMethodSubtypes(imeId, hashes) }
    }

    /**
     * Best-effort nudge of the OS switcher to match an in-app language switch so
     * the system UI's current subtype stays in sync. Skipped when the switcher
     * is turned off. API 28+ only; on 24–27 the OS→app direction still works, we
     * just don't push the other way. Swallowed: the subtype may not be
     * registered yet on a cold switch, and this is cosmetic — [onLayoutSelected]
     * has already moved the keyboard.
     */
    private fun mirrorSubtypeToOs(spec: LayoutSpec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val settings = _uiState.value.settings
        if (!settings.osLanguageSwitcher) return
        runCatching { switchInputMethod(imeId, subtypeFor(spec, subtypeNameResId(settings))) }
    }

    /**
     * The OS switcher (or system language shortcut) picked one of our subtypes:
     * follow it. Ignored when the switcher is off (we register nothing then).
     * Guarded against the echo from [mirrorSubtypeToOs] — when we are already on
     * that layout there is nothing to do, which also stops the in-app→OS→in-app
     * loop.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (!_uiState.value.settings.osLanguageSwitcher) return
        val layoutId = layoutIdOf(newSubtype) ?: return
        if (layoutId != _uiState.value.layoutId) onLayoutSelected(layoutId)
    }

    // ---- composing & suggestions ----

    /**
     * What the field holds for the buffer currently being typed.
     *
     * For Avro that is the transliteration, except where the fixed-spelling
     * map has an entry for exactly this buffer: "tmr" reads তোমার as the r
     * lands rather than waiting for the space. Showing it early is safe
     * precisely because the map is what a space would have committed anyway —
     * it wins [SuggestionEngine.suggest]'s Bengali path outright — so this is
     * the preview catching up with the commit, not a second opinion that might
     * disagree with it. Every caller that has to recognise the field's own text
     * as this buffer's output derives it from here for that reason.
     */
    private fun composedPreview(state: KeyboardUiState, buffer: String): String {
        if (!state.composer.isTransliterating) return buffer
        if (state.composer.isBengaliPhonetic) {
            suggestionEngine?.bengaliSpelling(buffer)?.let { return it }
        }
        return state.composer.composeBuffer(buffer)
    }

    private fun updateComposingText(ic: InputConnection) {
        val preview = composedPreview(_uiState.value, composing.toString())
        ic.setComposingText(preview, 1)
        _uiState.update { it.copy(composingPreview = preview) }
        publishComposingRoman()
    }

    /**
     * Mirrors the roman buffer into [KeyboardUiState.composingRoman], which is
     * what the transliteration key hints are drawn from.
     *
     * Called from the two places the buffer can change: this property's setter
     * for a wholesale replacement (commit, field change, re-arm), and
     * [updateComposingText] for the in-place edits (append, backspace), which
     * every one of those edits already calls.
     *
     * Empty unless the hints are actually on. The mirror is a `remember` key of
     * the whole key grid, so publishing it where nothing draws it would rebuild
     * all ~40 key bodies on every keystroke for nothing.
     */
    private fun publishComposingRoman() {
        val state = _uiState.value
        val roman = if (state.transliterationHintsShown()) composing.toString() else ""
        if (state.composingRoman != roman) {
            _uiState.update { it.copy(composingRoman = roman) }
        }
    }

    /**
     * Inserts panel-produced text (an emoji, a paste, a translation, an AI
     * result) at the cursor. Any word still being composed is committed as
     * typed first: a bare commitText would *replace* the active composing
     * region, eating the word — and skipping the flush leaves a stale buffer
     * that the next setComposingText re-inserts wherever the cursor has
     * moved to since, making the "previous word" reappear out of nowhere.
     */
    private fun commitToField(text: String) {
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        ic.commitText(text, 1)
    }

    /**
     * Puts a single typed character into the field, as a real key event when it
     * is an ASCII digit and as a commit for everything else.
     *
     * A committed character reaches a web page as an `input` event behind a
     * `keydown` that carries no key at all (`keyCode` 229 — the code every
     * browser uses for "the IME is composing"). Pages that police their own
     * fields on `keydown` — one-character-per-box verification forms above all,
     * which typically test the pressed key against `/[0-9]/` and cancel
     * anything else — throw that event away, and the digit never lands: the
     * user types and the box stays empty, with nothing on screen to say why.
     * A key event carries the real key, so those handlers see a digit and let
     * it through, and every ordinary editor treats it exactly like the commit
     * it replaces.
     *
     * The same fix AOSP's own keyboard carries, for the same reason, and the
     * reason digits are the only characters it applies to: `KEYCODE_0`..`_9`
     * are the only ones whose key event is unambiguous on every layout.
     */
    private fun commitTypedCharacter(ic: InputConnection, text: String) {
        val digit = text.singleOrNull()?.takeIf { it in '0'..'9' }
        if (digit != null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (digit - '0'))
        } else {
            ic.commitText(text, 1)
        }
    }

    /**
     * Types a one-time code into the field, one character at a time when
     * [OtpSettings.perDigitEntry] is on.
     *
     * The box a code goes into is very often a *row* of boxes, each holding one
     * character and moving the focus on by itself once it has one. A code
     * committed whole lands entirely in the first box, where all but the first
     * character is discarded by its own length limit — the user sees one digit
     * of six appear and the rest vanish. Typed character by character, each box
     * gets the one character it wants and hands over to the next, which is what
     * the form was built for; a plain single field sees the same code arrive,
     * just over a few frames instead of one.
     *
     * The input connection is re-read on every character rather than captured
     * once, because the focus moving between boxes is exactly what replaces it.
     * The gap between characters is what gives the page's own focus handler
     * time to run — without it every character races the same box.
     */
    private fun commitCodeToField(code: String) {
        if (code.length <= 1 || !_uiState.value.settings.otp.perDigitEntry) {
            commitToField(code)
            return
        }
        currentInputConnection?.let { commitComposing(it, autocorrect = false) }
        codeEntryJob?.cancel()
        codeEntryJob = serviceScope.launch {
            for (character in code) {
                val ic = currentInputConnection ?: break
                commitTypedCharacter(ic, character.toString())
                delay(CODE_ENTRY_STEP_MS)
            }
        }
    }

    /**
     * Re-establishes the editor-side composing span for the word still in
     * [composing] after the input connection restarted, or drops the buffer when
     * the field no longer backs it. [start] and [end] are the selection the
     * editor reported with the restart.
     *
     * A restart leaves the text but takes the span, so the buffer and the field
     * disagree until one of them gives. Keeping the buffer alone is the worse
     * half: with no span to replace, the next [updateComposingText] inserts the
     * whole buffer at the caret and the word being typed shows up twice.
     * Re-attaching keeps both — the word carries on being typed as if nothing
     * happened.
     *
     * Only ever attaches over text that still reads back as the buffer's own
     * output, so a stale or bogus [start] can't put the span over somebody
     * else's characters; anything short of an exact match drops the buffer,
     * which costs the word's prefix on the strip but never corrupts the field.
     */
    private fun reattachComposing(start: Int, end: Int) {
        if (composing.isEmpty()) return
        val ic = currentInputConnection
        // What the field actually holds for this buffer: a transliterating
        // composer's text is the composed form, not the roman source mirrored
        // in the buffer. Same derivation as [updateComposingText].
        val text = composedPreview(_uiState.value, composing.toString())
        val reattached = ic != null && text.isNotEmpty() &&
            start == end && start >= text.length &&
            ic.getTextBeforeCursor(text.length, 0)?.toString() == text &&
            ic.setComposingRegion(start - text.length, start)
        if (reattached) {
            _uiState.update { it.copy(composingPreview = text) }
            return
        }
        composing = StringBuilder()
        suggestionJob?.cancel()
        // The word behind the caret is the bigram context now that it is no
        // longer being composed, so predictions carry on from it.
        if (ic != null) syncPreviousWordFromField(ic)
        _uiState.update {
            it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
        }
    }

    /**
     * Ends any active composition ahead of an edit that must target the
     * *selection*. With a composing region alive, commitText targets the
     * region instead (InputConnection contract) — replacing the whole word
     * when only part of it is selected — and the stale local mirror would
     * re-insert the word at the caret on the next keystroke. finishComposingText
     * turns the region into ordinary text without touching the selection, so
     * the caller's commitText then affects exactly the selected range.
     */
    private fun dropComposingForSelectionEdit(ic: InputConnection) {
        ic.finishComposingText()
        if (composing.isEmpty()) return
        composing = StringBuilder()
        suggestionJob?.cancel()
        _uiState.update {
            it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
        }
    }

    /**
     * Commits the composing region. In Avro mode the top phonetic
     * suggestion wins (dictionary sibling over literal); in English mode
     * autocorrect may replace the typed word. Returns true if anything
     * was committed.
     */
    private fun commitComposing(
        ic: InputConnection,
        autocorrect: Boolean,
        fixApostrophes: Boolean = false,
        /**
         * Whether a *pattern* snippet may fire. A pattern consumes committed
         * text behind the caret, so only the callers where the user has just
         * ended a word — space, enter, a full stop — may set it. Every other
         * call here is incidental: a panel opening, a forward delete, a layout
         * switch, a cursor scrub. A pattern firing from one of those would eat
         * text the user never touched. The word trigger is unaffected, since it
         * only ever consumes the composing buffer.
         */
        expandPatterns: Boolean = false,
    ): Boolean {
        // Spent by whichever key ended this word; a commit that does not park
        // the caret must not leave a stale one behind for the next.
        swallowTerminatorAfterCommit = false
        if (composing.isEmpty()) return false
        // A strip refresh still debounced for this word must not land after
        // the commit and repaint candidates for text that is no longer being
        // composed (the tail of this function publishes the next-word strip).
        suggestionJob?.cancel()
        val typed = composing.toString()
        val state = _uiState.value

        // An abandoned inline emoji query (":smi" then space) is literal text:
        // never transliterated, autocorrected, or learned as a word.
        if (inlineEmojiQuery() != null) {
            ic.commitText(typed, 1)
            composing = StringBuilder()
            _uiState.update {
                it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
            }
            return true
        }
        // A word that exactly matches a snippet trigger expands to that
        // snippet's text instead of committing literally, and a pattern
        // snippet may then match the words behind it. Most specific first: a
        // trigger that reaches back past the buffer (`:shrug`, `gr db`) asks
        // for everything a plain one does and more, so it goes first or a
        // plain "db" would shadow "gr db"; a literal trigger then beats a
        // pattern, so a careless `^(.+)$` cannot shadow the whole list.
        //
        // All three are skipped for transliterating/conversion composers
        // (Pinyin, Vietnamese, …) — their buffer holds an input spelling, not
        // the trigger the user meant to type. All three also come before
        // apostrophes and autocorrect, so a trigger is matched as it was
        // actually typed.
        if (!state.composer.isTransliterating && !state.composer.isConversion) {
            if (tryPrefixExpansion(ic, typed)) return true
            // A trigger that asks first has already put its chip on the strip
            // (see [refreshSnippetOffer]); the word it matched commits as
            // ordinary text, and the offer goes with it.
            val snippet = snippetStore.matchTrigger(typed)?.takeIf { !snippetStore.offers(it) }
            if (snippet != null) {
                val expanded = SnippetStore.expandWithCursor(
                    snippet.text,
                    context = snippetContext(ic),
                    casing = SnippetStore.casingFor(snippet, typed),
                )
                commitSplitAtCaret(ic, expanded.text, expanded.cursorOffset)
                afterSnippetExpansion(
                    inserted = expanded.text,
                    original = null,
                    caretParked = expanded.cursorOffset < expanded.text.length,
                )
                publishSwapSet(
                    snippet = snippet,
                    typed = typed,
                    inserted = expanded.text,
                    insertedCaret = expanded.cursorOffset,
                    original = null,
                )
                return true
            }
            if (expandPatterns && tryPatternExpansion(ic, typed, state)) return true
        }
        // Conversion IME (Pinyin, Japanese): flush the whole reading as a
        // sequence of best candidates, each consuming its own syllables, so the
        // callers that aren't the space bar — cursor scrub, layout switch,
        // defocus — never drop the unconverted tail. The space bar takes the
        // interactive one-step path (commitConversionPrefix) instead.
        if (state.composer.isConversion) {
            flushConversion(ic)
            return true
        }
        // A buffer glued to word characters already in the field is the tail
        // of a bigger word, not a word of its own: the caret was parked at a
        // word's end and the composition started before the resume could arm
        // the rest of it (a tap's selection echo races the next keystroke).
        // Correcting the fragment corrupts the word it hangs off — "i" typed
        // onto "hind" must not become "I" — and a suffix is neither a typo to
        // guess at nor a word worth learning.
        val gluedToWord = (fixApostrophes || autocorrect) &&
            state.allowsTypingIntelligence && !state.composer.isTransliterating &&
            ic.getTextBeforeCursor(typed.length + 1, 0)
                ?.takeIf { it.length > typed.length }
                ?.let { isComposingWordChar(it[0]) } == true
        // Apostrophe restoration outranks autocorrect: "dont" is a known
        // contraction slip, not a typo for "font"/"done" to be guessed at.
        val apostrophized =
            if (fixApostrophes && state.allowsTypingIntelligence &&
                state.language.isEnglish && !gluedToWord
            ) {
                Apostrophes.fix(typed)
            } else {
                null
            }
        // The async suggestion job precomputes this word's commit resolution off
        // the main thread; use it only while it still matches [typed] (a
        // mismatch means the job hasn't caught up), else compute synchronously.
        // Either way the result is fresh — the commit never uses a stale strip.
        val pre = commitResolution?.takeIf { it.typed == typed }
        var corrected: String? = null
        // A candidate that came close to being applied without getting there.
        // Published as a chip below, once the word is actually in the field.
        var offered: String? = null
        val output = when {
            state.composer.isBengaliPhonetic ->
                (if (pre != null && pre.isBengali) pre.bengaliTop
                else suggestionEngine?.suggest(typed, previousWord = null, avroMode = true)?.firstOrNull())
                    ?: state.composer.composeBuffer(typed)
            // Other transliterators (Hangul, Vietnamese) commit the composed text
            // directly, with no dictionary pass.
            state.composer.isTransliterating -> state.composer.composeBuffer(typed)
            apostrophized != null -> apostrophized
            autocorrect && state.allowsTypingIntelligence && !gluedToWord -> {
                val decision = if (pre != null && !pre.isBengali) {
                    SuggestionEngine.CorrectionDecision(pre.correction, pre.offer)
                } else {
                    suggestionEngine?.decideCorrection(
                        typed, touch = composingTouchFrame(), timingMultiplier = timingMultiplier(),
                    ) ?: SuggestionEngine.NO_CORRECTION
                }
                corrected = decision.apply?.takeIf { it != typed }
                offered = decision.offer?.takeIf { it != typed }
                corrected ?: typed
            }
            else -> typed
        }
        val revertible = corrected?.let {
            RevertibleCommit(RevertibleCommit.Kind.AUTOCORRECT, original = typed, committed = it)
        }
        lastRevertible = revertible
        if (revertible != null) {
            // The adaptive gate learns from the fired/reverted ratio, but not
            // yet: firing is not a verdict. The correction waits in the watch
            // until the text around it settles, and is counted then.
            judgeCorrections(correctionWatch.push(revertible.original, revertible.committed))
            armRevertGuard()
        }
        // Armed before the commit lands so the strip refresh that follows it
        // publishes the chip; cleared here too, so a commit with no near miss
        // takes the previous word's offer down with it.
        correctionOfferFor = offered?.let { typed }
        pendingCorrectionOffer = offered
        ic.commitText(output, 1)
        // An autocorrected word was the engine's choice, not the user's —
        // it earns no personal-dictionary reinforcement, only the bigram.
        // Conversion-IME output (Hanzi/Kanji) is never learned into the lexicon,
        // and neither is a fragment glued onto an existing word.
        if (!state.composer.isConversion && !gluedToWord) {
            // An autocorrected word is the engine's spelling, not the user's,
            // so it teaches no casing either — it arrives at reinforcement 0,
            // which already keeps it out of the lexicon.
            learn(
                output,
                reinforcement = if (corrected != null) 0 else 1,
                caseTrusted = composingCaseTrusted && corrected == null,
            )
        }
        composing = StringBuilder()
        // Refill the strip in the same frame the word commits. Blanking it
        // and waiting for the async refresh left it empty for a frame or
        // two after every space, which read as a flicker.
        val (nextWords, nextEmojis) = nextWordStrip()
        _uiState.update {
            it.copy(composingPreview = "", suggestions = nextWords, emojiSuggestions = nextEmojis)
        }
        return true
    }

    /**
     * Commits [text] and leaves the caret at [caret] inside it.
     *
     * Committing the head, then the tail with `newCursorPosition = 0`, parks
     * the caret between them: the contract puts a non-positive position at the
     * start of the text just inserted. Asking the editor where the text landed
     * instead costs a second blocking read and relies on arithmetic the
     * contract does not promise — a single-line field strips the newlines out
     * of a multi-line snippet on the way in, and the answer lands elsewhere.
     */
    private fun commitSplitAtCaret(ic: InputConnection, text: String, caret: Int) {
        val split = caret.coerceIn(0, text.length)
        ic.commitText(text.substring(0, split), 1)
        val tail = text.substring(split)
        if (tail.isNotEmpty()) ic.commitText(tail, 0)
    }

    /**
     * The state every snippet expansion leaves behind.
     *
     * An expansion is not typing. Nothing about it is learned — the text is
     * boilerplate, and [learn] would split a whole signature into words and
     * bigrams — and the strip is cleared rather than refilled, because with a
     * `{cursor}` marker the caret may be sitting in the middle of it. What it
     * must do is move the prediction context onto the text it inserted: the
     * word the strip was predicting from is no longer in the field at all.
     *
     * [original] is the text the expansion replaced, when one backspace should
     * be able to put it back, and null when the expansion cannot be undone.
     */
    private fun afterSnippetExpansion(inserted: String, original: String?, caretParked: Boolean) {
        composing = StringBuilder()
        setContextFrom(inserted)
        invalidateRecentWords()
        commitResolution = null
        lastGestureWord = null
        // The caret moved with no onUpdateSelection echo yet, so the cached
        // selection would still answer for where it used to be.
        invalidateExpectedSelection()
        swallowTerminatorAfterCommit = caretParked
        lastRevertible = original
            ?.takeIf { inserted.length <= SNIPPET_REVERT_MAX }
            ?.let { RevertibleCommit(RevertibleCommit.Kind.SNIPPET, original = it, committed = inserted) }
        if (lastRevertible != null) armRevertGuard()
        _uiState.update {
            it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
        }
    }

    /**
     * Expands the snippet whose trigger ends in [typed] but starts before it —
     * `:shrug` behind a run of punctuation, `gr db` behind an earlier word.
     * Returns true when it committed something.
     *
     * This is the path every Espanso package needs, and the path a multi-word
     * trigger needs. Espanso's convention puts a `:` or `;` in front of an
     * ordinary word, and the keyboard's composing buffer never holds one: the
     * punctuation was committed to the field the moment it was typed, and only
     * "shrug" is in the buffer when the space lands. The earlier words of
     * "gr db" are in the field for exactly the same reason. So the lookup is on
     * the buffer, and what comes in front of it is confirmed by reading back a
     * short span of the field.
     *
     * The gate is the same shape as [patternGateOpen]'s. A user with no such
     * trigger pays one map lookup on a string already in memory, and the read
     * only happens once that lookup has said a match is possible at all.
     */
    private fun tryPrefixExpansion(ic: InputConnection, typed: String): Boolean {
        if (typed.isEmpty() || !snippetStore.hasPrefixTriggers()) return false
        if (!snippetStore.couldFinishPrefix(typed)) return false
        // One character more than the longest lead-in, so the boundary check in
        // [SnippetIndex.matchPrefix] can see what sits in front of a lead-in
        // that fills the whole span.
        val want = SnippetMatcher.MAX_PREFIX + typed.length + 1
        val read = ic.getTextBeforeCursor(want, 0) ?: return false
        // The buffer is still composing, so the read ends with it. What sits in
        // front of that is what the lead-in has to match.
        val before = read.toString().removeSuffix(typed)
        if (before.length == read.length) return false
        val hit = snippetStore.matchPrefix(typed, before) ?: return false
        stopVoiceForManualInput()
        // Taken from the field rather than from the trigger: the lead-in
        // matched case-insensitively, so what has to be deleted — and what a
        // revert has to put back — is what the user actually typed.
        val consumed = before.takeLast(hit.prefix.length) + typed
        val expanded = SnippetStore.expandWithCursor(
            hit.snippet.text,
            context = snippetContext(ic),
            // The whole trigger as typed, not just its last word: "GR DB" is a
            // shout and "gr db" is not, and the buffer alone cannot tell them
            // apart from "Gr db".
            casing = SnippetStore.casingFor(hit.snippet, consumed),
        )
        ic.beginBatchEdit()
        // Same reason as [tryPatternExpansion]: a live composing region and
        // deleteSurroundingText disagree about which characters they mean.
        ic.finishComposingText()
        composing = StringBuilder()
        ic.deleteSurroundingText(consumed.length, 0)
        commitSplitAtCaret(ic, expanded.text, expanded.cursorOffset)
        ic.endBatchEdit()
        afterSnippetExpansion(
            inserted = expanded.text,
            original = consumed,
            caretParked = expanded.cursorOffset < expanded.text.length,
        )
        publishSwapSet(
            snippet = hit.snippet,
            typed = consumed,
            inserted = expanded.text,
            insertedCaret = expanded.cursorOffset,
            original = consumed,
        )
        return true
    }

    /**
     * Expands the pattern snippet that fits the words behind the cursor, if
     * there is one. Returns true when it committed something.
     *
     * The costly half of the gate lives here. Only once [patternGateOpen] has
     * said a match is even possible does this read the field, and that one read
     * does both jobs: it is what the match is measured against, and it refills
     * the gate's memory of recent words.
     *
     * The read is also the probe. Nothing before the batch edit has any effect
     * on the field, so a dead connection, an editor that answers null, or a
     * window that turns out not to match all fall through to the ordinary
     * literal commit.
     */
    private fun tryPatternExpansion(ic: InputConnection, typed: String, state: KeyboardUiState): Boolean {
        // Only the patterns that expand on their own are this path's business.
        // The ones that ask first are offered by [refreshSnippetOffer] while the
        // words are still being typed, and firing one here would answer for the
        // user instead of asking.
        if (!snippetStore.hasAutoPatterns()) return false
        // A pattern rewrites text the user already committed, and it runs a
        // regular expression the user (or an installed add-on) wrote over a
        // window of the field. Both are reasons to stay out of the fields the
        // keyboard keeps its hands off. The composing gate already excludes
        // them, but that is an accident of how composing works, not a decision.
        if (!state.allowsTypingIntelligence || state.secureField || state.fieldNoSuggestions) {
            return false
        }
        if (!patternGateOpen(typed)) return false
        val before = ic.getTextBeforeCursor(SnippetMatcher.MAX_WINDOW, 0) ?: return false
        // Composing text is part of the field's own text, so the window
        // normally ends with the word being committed. An editor that
        // disagrees gets it appended rather than losing the match.
        val window = if (before.endsWith(typed)) before.toString() else before.toString() + typed
        rebuildRecentWords(window.dropLast(typed.length))
        val hit = snippetStore.matchPattern(
            window = window,
            // A window that came back short is the whole field, so a match may
            // start at its first character. A full one may have been cut mid
            // word, and matching that half would delete text whose start the
            // user cannot see.
            atFieldStart = before.length < SnippetMatcher.MAX_WINDOW,
            context = snippetContext(ic, withSelection = false),
        ) ?: return false
        // Text a backspace just restored must not expand straight back.
        if (hit.consumedText == patternMutedAfter) return false
        ic.beginBatchEdit()
        // The composing region has to be finished before the delete. With one
        // alive, deleteSurroundingText skips the composing text and takes the
        // characters in front of it instead (BaseInputConnection), while
        // Compose's own editor does not skip it at all — the same call deletes
        // different characters in an EditText and in a BasicTextField.
        // Finishing first turns the word into plain committed text for both.
        ic.finishComposingText()
        composing = StringBuilder()
        ic.deleteSurroundingText(hit.consumedChars, 0)
        commitSplitAtCaret(ic, hit.text, hit.cursorOffset)
        ic.endBatchEdit()
        afterSnippetExpansion(
            inserted = hit.text,
            original = hit.consumedText,
            caretParked = hit.cursorOffset < hit.text.length,
        )
        publishSwapSet(
            snippet = hit.snippet,
            typed = null,
            inserted = hit.text,
            insertedCaret = hit.cursorOffset,
            original = hit.consumedText,
            match = hit,
        )
        return true
    }

    /**
     * Whether a pattern snippet could possibly match here, answered without
     * touching the input connection.
     *
     * A false answer is final. A true answer only buys the right to read the
     * field, so an untrustworthy ring answers true: a missed update then costs
     * one read rather than a feature that quietly stops working.
     */
    private fun patternGateOpen(typed: String): Boolean {
        if (!recentWordsValid) return true
        // There need not be a word in the buffer at all. The offer path asks
        // this question on every field entry and after every commit, when
        // nothing is composing, and a pattern that matches the words already in
        // the field is still a match — so an empty buffer only skips its own
        // half of the question rather than answering the whole of it.
        if (typed.isNotEmpty() && snippetStore.couldStartPattern(typed[0])) return true
        return recentWords.any { snippetStore.couldStartPattern(it[0]) }
    }

    /** Takes down whatever the strip was offering, if anything. */
    private fun clearSnippetOffer() {
        snippetOfferJob?.cancel()
        if (_uiState.value.snippetOffers != null) _uiState.update { it.copy(snippetOffers = null) }
    }

    /**
     * Takes down a set of swap chips, if that is what is up.
     *
     * Swap chips are about text already in the field rather than about the
     * composing buffer, so nothing re-derives them: they have to be taken down
     * by whatever changes the text they were talking about. Called from every
     * place that ends the backspace-revert window, which is the same set of
     * events for the same reason.
     */
    private fun clearSwapOffer() {
        if (_uiState.value.snippetOffers?.kind == SnippetOfferKind.SWAP) clearSnippetOffer()
    }

    /** Publishes [offers], or takes the chips down when it is null. */
    private fun publishSnippetOffer(offers: SnippetOfferSet?) {
        // The add-word chip answers through the same callback, so a snippet
        // offer arriving takes it down: whichever chip is up owns the answer.
        if (offers != null) clearLearnOffer()
        val current = _uiState.value.snippetOffers
        // A walk into a linked snippet survives the offer being derived again
        // for the same trigger. Without this, every refresh that lands on the
        // identical match — a selection echo, a debounced pattern job finishing
        // — would throw the user back to the top level mid-tap.
        val next = if (
            offers != null && current != null && current.path.isNotEmpty() &&
            current.kind == offers.kind && current.rootId == offers.rootId &&
            current.consumed == offers.consumed && current.rootChips == offers.rootChips
        ) {
            offers.copy(path = current.path, chips = current.chips)
        } else {
            offers
        }
        if (current != next) _uiState.update { it.copy(snippetOffers = next) }
    }

    /** A candidate as the strip draws it. */
    private fun chipOf(candidate: SnippetCandidate): SnippetChip = SnippetChip(
        snippetId = candidate.snippetId,
        label = candidate.label,
        text = candidate.text,
        cursorOffset = candidate.cursorOffset,
        drillable = candidate.drillable,
    )

    /**
     * What [snippet] has to offer here: its own expansions and the default
     * expansion of everything it links to, expanded against this field.
     *
     * [match] is a pattern hit, whose captures every expansion is templated
     * with and whose own text is kept verbatim for the first candidate; [typed]
     * is the trigger as it was actually typed, which decides the casing.
     */
    private fun snippetCandidates(
        snippet: Snippet,
        typed: String? = null,
        match: SnippetMatch? = null,
        withSelection: Boolean = false,
    ): List<SnippetCandidate> {
        val context = snippetContext(currentInputConnection, withSelection = withSelection)
        return if (match != null) {
            snippetStore.candidates(match, context = context)
        } else {
            snippetStore.candidates(snippet, typed = typed, context = context)
        }
    }

    /**
     * The chips for a snippet that matched but inserted nothing, or null when
     * it has nothing to say.
     *
     * [consumed] is what taking a chip takes back out of the field, and
     * [composed] whether that is exactly the word still being composed. See
     * [SnippetOfferSet].
     */
    private fun pickSet(
        snippet: Snippet,
        typed: String?,
        consumed: String,
        composed: Boolean,
        match: SnippetMatch? = null,
    ): SnippetOfferSet? {
        val chips = snippetCandidates(snippet, typed, match).map(::chipOf)
        if (chips.isEmpty()) return null
        return SnippetOfferSet(
            kind = SnippetOfferKind.PICK,
            rootId = snippet.id,
            rootLabel = snippet.label,
            consumed = consumed,
            composed = composed,
            rootChips = chips,
        )
    }

    /**
     * Offers the rest of what a snippet had to say, after its first expansion
     * has already replaced the trigger.
     *
     * Only when there is more than one thing: a snippet with a single expansion
     * behaves exactly as it always has, right down to leaving the strip alone.
     * The same gates as the ask-first chip apply — a field that wanted a quiet
     * strip does not get chips just because an expansion fired in it.
     */
    @Suppress("LongParameterList")
    private fun publishSwapSet(
        snippet: Snippet,
        typed: String?,
        inserted: String,
        insertedCaret: Int,
        original: String?,
        match: SnippetMatch? = null,
    ) {
        val state = _uiState.value
        if (!state.allowsTypingIntelligence || state.secureField || state.fieldNoSuggestions ||
            state.panel != PanelMode.NONE
        ) {
            return
        }
        val candidates = snippetCandidates(snippet, typed, match)
        if (candidates.size < 2) return
        publishSnippetOffer(
            SnippetOfferSet(
                kind = SnippetOfferKind.SWAP,
                rootId = snippet.id,
                rootLabel = snippet.label,
                inserted = inserted,
                insertedCaret = insertedCaret.coerceIn(0, inserted.length),
                original = original,
                current = candidates.indexOfFirst { it.text == inserted },
                rootChips = candidates.map(::chipOf),
            ),
        )
        // The chips outlive the commit's own selection echo, so the window that
        // tells an echo from a real caret move has to be open for them too.
        armRevertGuard()
    }

    /**
     * Offers whatever snippet the text in front of the cursor matches, when
     * that snippet asks before it expands.
     *
     * This is derived state, recomputed from the field rather than remembered
     * from a commit, and that is what makes the chip arrive when the trigger is
     * typed instead of when the space is. Type "hello Jo" and the offer is
     * already there, reading "Hello, Jo!"; the next letter re-derives it. There
     * is nothing to keep in sync and nothing to go stale — an edit, a caret
     * jump, a change made from outside the keyboard all end up back here.
     *
     * Two gates keep it off the typing path for everyone else. A user with no
     * asking snippets does no work at all, and a plain trigger is answered by a
     * map lookup with no round-trip. Only an asking *pattern* costs a read of
     * the field, and that one is debounced onto a worker the way the smart chip
     * is, because it happens on every keystroke rather than once a word.
     */
    private fun refreshSnippetOffer(state: KeyboardUiState) {
        // Swap chips are about text that is already in the field, so they are
        // not derived from the buffer and must not be taken down by a pass over
        // it — the space that fired the expansion calls straight through here a
        // line later. [clearSwapOffer] is what ends them.
        if (_uiState.value.snippetOffers?.kind == SnippetOfferKind.SWAP) return
        snippetOfferJob?.cancel()
        // The chip needs a strip to sit on, so a field that asked for a quiet
        // one gets neither the offer nor the expansion behind it. Same fields
        // [tryPatternExpansion] stays out of, for the same reason. A composing
        // buffer holding an input spelling (Avro, Pinyin) is not a trigger and
        // not a word a pattern can read.
        val allowed = state.allowsTypingIntelligence && !state.secureField &&
            !state.fieldNoSuggestions && state.panel == PanelMode.NONE &&
            !state.composer.isTransliterating && !state.composer.isConversion
        if (!allowed) {
            publishSnippetOffer(null)
            return
        }
        val typed = composing.toString()
        val trigger = typed
            .takeIf { it.isNotEmpty() && snippetStore.hasConfirmTriggers() }
            ?.let { snippetStore.matchTrigger(it) }
            ?.takeIf { snippetStore.offers(it) }
        // A prefix trigger sits between the two: the lookup is free like a plain
        // trigger's, but confirming what sits in front of the buffer costs the
        // same read a pattern does. So it rides the same debounced job.
        val wantPrefix = typed.isNotEmpty() && snippetStore.hasConfirmPrefixTriggers() &&
            snippetStore.couldFinishPrefix(typed)
        // Most specific first, the same order the commit path uses: a trigger
        // that reaches back past the buffer outranks a plain one that happens
        // to be its last word. Only once no such trigger is possible can a
        // plain match answer without a read.
        if (trigger != null && !wantPrefix) {
            // The selection is not worth a blocking round-trip on the typing
            // path: a word only composes with the caret collapsed.
            publishSnippetOffer(pickSet(trigger, typed, consumed = typed, composed = true))
            return
        }
        val wantPattern = snippetStore.hasConfirmPatterns() && patternGateOpen(typed)
        if (!wantPrefix && !wantPattern) {
            publishSnippetOffer(null)
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            // No read is possible, so nothing can outrank the plain match that
            // was held back above. It still stands on the buffer alone.
            publishSnippetOffer(
                trigger?.let { pickSet(it, typed, consumed = typed, composed = true) },
            )
            return
        }
        // A trigger's offer is about the buffer, and the buffer has just
        // changed into something that is not that trigger, so it goes now
        // rather than after the read. A pattern's offer is left up until the
        // read answers: it usually still matches, and blanking it first is a
        // chip that blinks on every keystroke. Not when a plain match is
        // waiting on the read to see whether something beats it: that chip is
        // about the buffer that is still there, and taking it down would make
        // it blink once a keystroke.
        if (_uiState.value.snippetOffers?.composed == true && trigger == null) {
            publishSnippetOffer(null)
        }
        snippetOfferJob = serviceScope.launch {
            // Debounced ahead of the read, for the reason spelled out in
            // [refreshSmartSuggestion]: one keystroke reaches here twice, and
            // sleeping first means the second scheduling cancels the first
            // while it is still asleep rather than after it has already paid
            // for a round-trip into the app being typed into.
            delay(SNIPPET_OFFER_DEBOUNCE_MS)
            val window = withContext(Dispatchers.Default) {
                ic.getTextBeforeCursor(SnippetMatcher.MAX_WINDOW, 0)?.toString()
            }
            if (window == null) {
                publishSnippetOffer(
                    trigger?.let { pickSet(it, typed, consumed = typed, composed = true) },
                )
                return@launch
            }
            // The gate was read before the sleep; a panel opening or a field
            // turning out to be secure does not cancel this job.
            val still = _uiState.value
            if (still.panel != PanelMode.NONE || still.secureField || still.fieldNoSuggestions) {
                publishSnippetOffer(null)
                return@launch
            }
            // An expansion may have fired while this was asleep; its chips are
            // about text in the field and this job's answer is about a buffer
            // that no longer exists.
            if (still.snippetOffers?.kind == SnippetOfferKind.SWAP) return@launch
            // The prefix trigger goes first, for the same reason it does on the
            // commit path: it is the more specific rule.
            if (wantPrefix) {
                val before = window.removeSuffix(typed)
                val prefix = if (before.length == window.length) {
                    null
                } else {
                    snippetStore.matchPrefix(typed, before, confirm = true)
                }
                if (prefix != null) {
                    // From the field, not from the trigger: the lead-in matched
                    // case-insensitively, so this is what accepting deletes.
                    val consumed = before.takeLast(prefix.prefix.length) + typed
                    publishSnippetOffer(
                        pickSet(
                            snippet = prefix.snippet,
                            typed = consumed,
                            // Not `composed`: the span reaches back past the
                            // buffer over text the editor already has, so
                            // accepting has to find and delete it the way a
                            // pattern's offer does rather than just replace the
                            // composing region.
                            consumed = consumed,
                            composed = false,
                        ),
                    )
                    return@launch
                }
            }
            // No trigger reached back past the buffer after all, so the plain
            // match held back above gets its turn.
            if (trigger != null) {
                publishSnippetOffer(pickSet(trigger, typed, consumed = typed, composed = true))
                return@launch
            }
            if (!wantPattern) {
                publishSnippetOffer(null)
                return@launch
            }
            // The space that ends a word is already in the field by the time
            // anyone can tap, and `(.+)` would swallow it into the capture. The
            // match is made against the text without it; [onSnippetOfferAccept]
            // finds the same trailing run again and puts it back.
            val hit = snippetStore.matchPattern(
                window = window.trimEnd(),
                atFieldStart = window.length < SnippetMatcher.MAX_WINDOW,
                context = snippetContext(ic, withSelection = false),
                confirm = true,
            )
            publishSnippetOffer(
                hit?.let {
                    pickSet(
                        snippet = it.snippet,
                        typed = null,
                        consumed = it.consumedText,
                        composed = false,
                        match = it,
                    )
                },
            )
        }
    }

    /**
     * An ask-first chip on the strip was answered.
     *
     * One entry point for both of them — the snippet offer and the add-word
     * offer — picked apart by state here rather than by a second parameter on
     * [ui.KeyboardScreen], whose argument list already compiles to a method at
     * the JVM's 64K ceiling. They never share the strip: publishing either one
     * clears the other.
     */
    fun onStripOfferAction(action: StripOfferAction) {
        if (_uiState.value.learnOffer != null) {
            when (action) {
                is StripOfferAction.Accept -> acceptLearnOffer()
                StripOfferAction.Decline -> declineLearnOffer()
                // The add-word chip has nothing to walk into.
                else -> Unit
            }
            return
        }
        when (action) {
            is StripOfferAction.Accept -> onSnippetOfferPick(action.index)
            is StripOfferAction.Drill -> onSnippetOfferDrill(action.index)
            StripOfferAction.Back -> onSnippetOfferBack()
            StripOfferAction.Decline -> clearSnippetOffer()
        }
    }

    /**
     * Puts the add-word chip on the strip for [word].
     *
     * Only ever reached with `askBeforeLearning` on. The offer is about the
     * word that was just committed, so a newer one replaces it outright: the
     * chip follows the typing rather than queueing up behind it.
     */
    private fun offerToLearn(word: String, caseTrusted: Boolean) {
        if (learnOfferWord == word) return
        learnOfferWord = word
        learnOfferCaseTrusted = caseTrusted
        // The two ask-first chips answer through the same callback, so only one
        // of them may be up at a time.
        clearSnippetOffer()
        _uiState.update { it.copy(learnOffer = word) }
    }

    /** Takes the add-word chip down, whether or not it was answered. */
    private fun clearLearnOffer() {
        learnOfferWord = null
        learnOfferCaseTrusted = false
        if (_uiState.value.learnOffer != null) _uiState.update { it.copy(learnOffer = null) }
    }

    /** "Yes, that is a word": into the dictionary at full strength. */
    private fun acceptLearnOffer() {
        val word = _uiState.value.learnOffer ?: return
        val trusted = learnOfferCaseTrusted
        vibrate()
        clearLearnOffer()
        pendingLearn.forget(word)
        // addWord, not learnWord: the user was asked outright and said yes, so
        // the word competes with real vocabulary immediately and is never
        // corrected away.
        // The chip shows the word as it was committed, which at a sentence
        // start means auto-capitalize's capital rather than the user's. Saying
        // yes to that is a vote for the word, never for the capital — so an
        // untrusted spelling is filed in lower case (#44), where a later
        // deliberate "Boston" can still teach it.
        val spelling = if (trusted) word else word.lowercase()
        userLexicon.addWord(spelling, caseEvidence = trusted)
        if (_uiState.value.settings.addWordsToSystemDictionary) {
            serviceScope.launch(Dispatchers.IO) {
                SystemUserDictionary.add(applicationContext, spelling)
            }
        }
        refreshSuggestions()
    }

    /** "No": drop it and stop asking about that word. */
    private fun declineLearnOffer() {
        val word = _uiState.value.learnOffer ?: return
        vibrate()
        clearLearnOffer()
        pendingLearn.decline(word)
    }

    /**
     * Inserts the offer the strip was showing.
     *
     * A trigger's offer replaces the composing region, which is what an
     * ordinary [InputConnection.commitText] does anyway. A pattern's offer has
     * to find its span again, since it reaches back over words the editor has
     * already taken, and the key that ended the last one may have landed since.
     * Both are put back on a backspace, exactly as an expansion that fired on
     * its own is.
     *
     * Either way the offer is checked against the field before anything is
     * edited. It is derived state, refreshed a frame behind the keystrokes, so
     * a tap can always land just after the text it was about stopped being
     * there — and an expansion that deletes the wrong span is far worse than
     * one that does nothing.
     */
    fun onSnippetOfferPick(index: Int) {
        val offers = _uiState.value.snippetOffers ?: return
        val chip = offers.visibleChips().getOrNull(index) ?: return
        when (offers.kind) {
            SnippetOfferKind.PICK -> onSnippetOfferAccept(offers, chip)
            SnippetOfferKind.SWAP -> onSnippetSwap(offers, chip)
        }
    }

    /**
     * Shows what the linked snippet behind the chip at [index] has to offer.
     *
     * Bounded by the walk itself: a snippet already on the path is not entered
     * again, so a loop in the graph is a dead end rather than an endless one.
     */
    private fun onSnippetOfferDrill(index: Int) {
        val offers = _uiState.value.snippetOffers ?: return
        val chip = offers.visibleChips().getOrNull(index) ?: return
        if (!chip.drillable || !offers.canDrill(chip.snippetId)) return
        val chips = snippetStore
            .drillIn(chip.snippetId, context = snippetContext(currentInputConnection, false))
            .map(::chipOf)
        if (chips.isEmpty()) return
        vibrate()
        _uiState.update {
            it.copy(
                snippetOffers = offers.copy(path = offers.path + chip.snippetId, chips = chips),
            )
        }
    }

    /** Goes back up one level of a walk into linked snippets. */
    private fun onSnippetOfferBack() {
        val offers = _uiState.value.snippetOffers ?: return
        if (offers.path.isEmpty()) return
        val path = offers.path.dropLast(1)
        val chips = if (path.isEmpty()) {
            offers.rootChips
        } else {
            snippetStore
                .drillIn(path.last(), context = snippetContext(currentInputConnection, false))
                .map(::chipOf)
                .ifEmpty { offers.rootChips }
        }
        vibrate()
        _uiState.update { it.copy(snippetOffers = offers.copy(path = path, chips = chips)) }
    }

    /**
     * Replaces the expansion already in the field with another of the ones the
     * same trigger offered.
     *
     * The text is verified before anything is edited, exactly as the ask-first
     * path verifies its span and for the same reason: the chips outlive the
     * commit by design, so a tap can land after the field has moved on. What
     * this puts in inherits the undo the expansion had, so backspace still
     * restores the trigger rather than the expansion it replaced.
     */
    private fun onSnippetSwap(offers: SnippetOfferSet, chip: SnippetChip) {
        val ic = currentInputConnection ?: return
        val head = offers.inserted.take(offers.insertedCaret)
        val rest = offers.inserted.drop(offers.insertedCaret)
        val tail = if (rest.isEmpty()) {
            val before = ic
                .getTextBeforeCursor(SNIPPET_REVERT_MAX + SNIPPET_OFFER_TAIL_MAX, 0)
                ?.toString()
                .orEmpty()
            val at = before.lastIndexOf(offers.inserted)
            // Nothing may sit between the expansion and the cursor but the key
            // that ended the word it replaced.
            before
                .takeIf { at >= 0 }
                ?.substring(at + offers.inserted.length)
                ?.takeIf { it.length <= SNIPPET_OFFER_TAIL_MAX && it.none(Char::isLetterOrDigit) }
                ?: run {
                    clearSnippetOffer()
                    return
                }
        } else {
            // The caret is parked inside the expansion, so the text straddles
            // it and both halves have to still be there.
            val before = ic.getTextBeforeCursor(head.length, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(rest.length, 0)?.toString().orEmpty()
            if (before != head || after != rest) {
                clearSnippetOffer()
                return
            }
            ""
        }
        stopVoiceForManualInput()
        vibrate()
        val inserted = chip.text + tail
        val marker = chip.cursorOffset.takeIf { it < chip.text.length }
        ic.beginBatchEdit()
        ic.finishComposingText()
        composing = StringBuilder()
        ic.deleteSurroundingText(head.length + tail.length, rest.length)
        commitSplitAtCaret(ic, inserted, marker ?: inserted.length)
        ic.endBatchEdit()
        afterSnippetExpansion(
            inserted = inserted,
            original = offers.original?.let { it + tail },
            caretParked = false,
        )
        // Republished rather than taken down: the other expansions are still
        // what this trigger had to say, and one wrong tap should be one tap
        // from being right again.
        publishSnippetOffer(
            offers.copy(
                inserted = inserted,
                insertedCaret = marker ?: inserted.length,
                original = offers.original,
                current = offers.rootChips.indexOfFirst { it.text == chip.text },
                path = emptyList(),
                chips = offers.rootChips,
            ),
        )
        armRevertGuard()
    }

    private fun onSnippetOfferAccept(offers: SnippetOfferSet, chip: SnippetChip) {
        val offer = offers
        val ic = currentInputConnection ?: return
        val tail = if (offer.composed) {
            // The buffer is the span. Nothing follows it — a terminator would
            // have committed it — so the only question is whether it is still
            // the word the chip was offering for.
            if (composing.toString() != offer.consumed) {
                clearSnippetOffer()
                return
            }
            ""
        } else {
            val before = ic
                .getTextBeforeCursor(SnippetMatcher.MAX_WINDOW + SNIPPET_OFFER_TAIL_MAX, 0)
                ?.toString()
                .orEmpty()
            val at = before.lastIndexOf(offer.consumed)
            // The span has to still be in front of the cursor with nothing
            // behind it but the key that ended the word: the space the match
            // was made without, or a full stop and its space.
            before
                .takeIf { at >= 0 }
                ?.substring(at + offer.consumed.length)
                ?.takeIf { it.length <= SNIPPET_OFFER_TAIL_MAX && it.none(Char::isLetterOrDigit) }
                ?: run {
                    clearSnippetOffer()
                    return
                }
        }
        stopVoiceForManualInput()
        vibrate()
        val inserted = chip.text + tail
        val original = offer.consumed + tail
        // With no marker in it the caret belongs after everything this puts in,
        // the trailing space included; a marker is an instruction about where
        // inside the snippet's own text to stop.
        val marker = chip.cursorOffset.takeIf { it < chip.text.length }
        ic.beginBatchEdit()
        if (!offer.composed) {
            // Same reason as [tryPatternExpansion]: a live composing region and
            // deleteSurroundingText disagree about which characters they mean.
            // A pattern's span ends in the word still being typed, so there
            // usually is one.
            ic.finishComposingText()
            composing = StringBuilder()
            ic.deleteSurroundingText(offer.consumed.length + tail.length, 0)
        }
        commitSplitAtCaret(ic, inserted, marker ?: inserted.length)
        ic.endBatchEdit()
        afterSnippetExpansion(
            inserted = inserted,
            original = original,
            // Nothing to swallow: this expansion was tapped, so no terminator
            // key is on its way in behind it.
            caretParked = false,
        )
        clearSnippetOffer()
    }

    /**
     * Flushes a conversion buffer whole: repeatedly commits the top candidate
     * for what remains and drops the input chars it consumed, until the buffer
     * is empty. A reading with no dictionary match commits raw (consuming all),
     * so the loop always terminates; the guard is a belt-and-braces backstop.
     * Used by every non-space commit path — the space bar goes one step at a
     * time through [commitConversionPrefix].
     */
    private fun flushConversion(ic: InputConnection) {
        val composer = _uiState.value.composer
        var guard = 0
        while (composing.isNotEmpty() && guard++ < 64) {
            val buf = composing.toString()
            val top = composer.candidates(buf).firstOrNull()
            val chosen = top ?: composer.composeBuffer(buf)
            val consumed = if (top != null) {
                composer.consumedForIndex(buf, 0)
            } else {
                composer.consumedFor(buf, chosen)
            }.coerceIn(1, composing.length)
            ic.commitText(chosen, 1)
            composing.delete(0, consumed)
        }
        composing = StringBuilder()
        val (nextWords, nextEmojis) = nextWordStrip()
        _uiState.update {
            it.copy(composingPreview = "", suggestions = nextWords, emojiSuggestions = nextEmojis)
        }
    }

    /**
     * Commits a single chosen conversion candidate and keeps the tail: deletes
     * only the input chars [chosen] consumed, then re-converts whatever is left
     * so the next syllable's candidates appear immediately. Tapping 你 for
     * `nihao` commits 你 and leaves `hao` composing. Never learns (Hanzi/Kanji
     * are not lexicon words) and never adds a trailing space.
     */
    private fun commitConversionPrefix(ic: InputConnection, chosen: String, index: Int = -1) {
        if (composing.isEmpty()) return
        val composer = _uiState.value.composer
        val buf = composing.toString()
        // By strip position when the caller knows it, which is exact even where a
        // candidate's text is reachable at two different spans; by text otherwise,
        // which is what the raw-reading fallback needs since it is in no list.
        val consumed = if (index >= 0) {
            composer.consumedForIndex(buf, index)
        } else {
            composer.consumedFor(buf, chosen)
        }.coerceIn(1, buf.length)
        // Only a real pick from the strip or grid, and never the raw-reading
        // fallback: index is -1 there, and there is nothing to learn from a
        // reading the dictionary could not convert. Not hooked into
        // flushConversion either — that auto-commits whatever already ranked
        // first, so learning it would only entrench the existing order.
        if (index >= 0 && learningAllowed) composer.learnChoice(buf, index)
        ic.commitText(chosen, 1)
        composing.delete(0, consumed)
        consumeShift()
        if (composing.isEmpty()) {
            val (nextWords, nextEmojis) = nextWordStrip()
            _uiState.update {
                it.copy(
                    composingPreview = "",
                    suggestions = nextWords,
                    emojiSuggestions = nextEmojis,
                    // The reading is fully committed, so there is nothing left to
                    // choose. Leaving the grid up would strand the user staring at
                    // an empty panel with the keyboard hidden behind it.
                    panel = if (it.panel == PanelMode.CANDIDATES) PanelMode.NONE else it.panel,
                    expandedCandidates = emptyList(),
                )
            }
        } else {
            // Re-show the remaining pinyin/kana as the composing region and
            // convert it — the next chunk's candidates fill the strip.
            updateComposingText(ic)
            refreshSuggestions()
        }
    }

    /**
     * The Japanese flick pad's 小゛゜ key: cycles the last kana in the composing
     * buffer through its small / dakuten / handakuten forms (か→が, は→ば→ぱ). The
     * kana lives in the composing buffer while the reading is still being typed,
     * so this rewrites the last char in place and re-converts; a no-op when the
     * buffer is empty or the last char has no variant.
     */
    private fun cycleKanaVariant() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        val last = composing[composing.length - 1]
        val cycled = Kana.cycleVariant(last)
        if (cycled == last) return
        composing.setCharAt(composing.length - 1, cycled)
        updateComposingText(ic)
        refreshSuggestions()
    }

    /** Pack-state token the conversion tables were last loaded from; see [CjkDictStore.stateToken]. */
    @Volatile
    private var loadedCjkPackToken = Int.MIN_VALUE

    /**
     * (Re)loads the Chinese/Japanese conversion tables from their downloaded
     * [CjkDictCatalog] packs. The tables are large and useful to a minority, so
     * they are not bundled — with no pack on disk the composer types the raw
     * reading with no candidates, and deleting a pack resets it to empty. Records
     * the pack-state token so [onStartInputView] re-parses only when a pack
     * actually changes. All parsing runs off the main thread.
     */
    private suspend fun loadCjkConversionTables() {
        withContext(Dispatchers.Default) {
            val token = CjkDictStore.stateToken(filesDir)
            CjkDictionaries.pinyin = CjkDictStore.downloadedFileFor(filesDir, "pinyin")
                ?.let { file -> runCatching { file.bufferedReader().useLines(ConversionDictionary::parse) }.getOrNull() }
                ?: ConversionDictionary.EMPTY
            CjkDictionaries.japanese = CjkDictStore.downloadedFileFor(filesDir, "ja_kana")
                ?.let { file -> runCatching { file.bufferedReader().useLines(ConversionDictionary::parse) }.getOrNull() }
                ?: ConversionDictionary.EMPTY
            CjkDictionaries.stroke = CjkDictStore.downloadedFileFor(filesDir, "stroke")
                ?.let { file ->
                    runCatching {
                        file.bufferedReader().useLines {
                            CodeTableDictionary.parse(it, CodeTableDictionary.STROKE_CODE)
                        }
                    }.getOrNull()
                }
                ?: CodeTableDictionary.EMPTY
            CjkDictionaries.jyutping = CjkDictStore.downloadedFileFor(filesDir, "jyutping")
                ?.let { file -> runCatching { file.bufferedReader().useLines(ConversionDictionary::parse) }.getOrNull() }
                ?: ConversionDictionary.EMPTY
            CjkDictionaries.cangjie = CjkDictStore.downloadedFileFor(filesDir, "cangjie")
                ?.let { file ->
                    runCatching {
                        file.bufferedReader().useLines {
                            CodeTableDictionary.parse(it, CodeTableDictionary.CANGJIE_CODE)
                        }
                    }.getOrNull()
                }
                ?: CodeTableDictionary.EMPTY
            // The pinyin syllable inventory is tiny static reference data (~1.8 KB),
            // the only bundled CJK asset — segmentation is ready without a download,
            // though candidates still need the pinyin pack above.
            runCatching {
                assets.open("dictionaries/pinyin_syllables.txt").bufferedReader().useLines {
                    PinyinSyllables.valid = PinyinSyllables.parse(it)
                }
            }
            // T9's digit-code index and Zhuyin's bopomofo table are both derived
            // from that inventory rather than loaded — the 9-key pad, the 注音 pad
            // and the full keyboard share one syllable set and one conversion pack.
            // Simplified→Traditional map for the output toggle; optional, and the
            // conversion is the identity until it loads.
            runCatching {
                assets.open("dictionaries/s2t.txt").bufferedReader().useLines {
                    HanVariant.s2t = HanVariant.parse(it)
                }
            }
            // Regional vocabulary on top of it: Taipei writes 計程車 where the
            // mainland writes 出租車, and no character map can reach that. All
            // three are small enough to bundle, and each is independently
            // optional — a missing one just leaves that layer inert.
            runCatching {
                assets.open("dictionaries/tw_phrases.txt").bufferedReader().useLines {
                    HanVariant.twPhrases = HanVariant.parsePhrases(it)
                }
            }
            runCatching {
                assets.open("dictionaries/tw_variants.txt").bufferedReader().useLines {
                    HanVariant.twVariants = HanVariant.parse(it)
                }
            }
            runCatching {
                assets.open("dictionaries/hk_variants.txt").bufferedReader().useLines {
                    HanVariant.hkVariants = HanVariant.parse(it)
                }
            }
            // Cantonese has its own inventory: the readings share no syllable set
            // with Mandarin, so it cannot be derived like the two above. Optional —
            // absent, Jyutping segments nothing and commits the raw roman letters.
            runCatching {
                assets.open("dictionaries/jyutping_syllables.txt").bufferedReader().useLines {
                    JyutpingSyllables.valid = JyutpingSyllables.parse(it)
                }
            }
            T9Pinyin.index = T9Pinyin.buildIndex(PinyinSyllables.valid)
            ZhuyinSyllables.table = ZhuyinSyllables.buildTable(PinyinSyllables.valid)
            loadedCjkPackToken = token
        }
    }

    /**
     * Next-word predictions for the word just committed, computed inline.
     *
     * Only safe because the empty-composing path is a handful of bigram map
     * lookups — no dictionary completion, no edit-distance search — so it
     * costs less than the dispatch it replaces. Returns words to emojis,
     * matching the split [refreshSuggestions] does.
     */
    /**
     * The (previousWord, typed) pair the offered join chip was computed for,
     * so a stale tap (field changed underneath) can verify before rewriting.
     */
    private var joinContext: Pair<String, String>? = null

    /** The join chip: replace "prev typed" in the field with the joined word. */
    fun onJoinSuggestionTapped() {
        val state = _uiState.value
        val joined = state.joinSuggestion ?: return
        val (prev, typedAt) = joinContext ?: return
        val typed = composing.toString()
        // The chip was computed for this exact composing text.
        if (!typed.equals(typedAt, ignoreCase = true)) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            // Verify the field really ends "prev typed" (plus derive the next
            // bigram context from what precedes it), or do nothing at all.
            val span = prev.length + 1 + typed.length
            val window = ic.getTextBeforeCursor(span + 48, 0)?.toString()
            val tail = window?.takeLast(span)
            if (tail == null || !tail.equals("$prev $typed", ignoreCase = true) ||
                tail[prev.length] != ' '
            ) {
                return
            }
            val beforeContext = window.dropLast(span)
            val display = joinedCase(tail, joined)
            ic.deleteSurroundingText(span, 0)
            ic.commitText(display, 1)
            composing = StringBuilder()
            setContextFrom(beforeContext)
            lastRevertible = RevertibleCommit(
                RevertibleCommit.Kind.JOIN, original = tail, committed = display,
            )
            armRevertGuard()
            learn(joined, reinforcement = 2)
            invalidateExpectedSelection()
        } finally {
            ic.endBatchEdit()
        }
        _uiState.update {
            it.copy(
                composingPreview = "",
                suggestions = emptyList(),
                emojiSuggestions = emptyList(),
                joinSuggestion = null,
            )
        }
        joinContext = null
        val (words, emojis) = nextWordStrip()
        _uiState.update { it.copy(suggestions = words, emojiSuggestions = emojis) }
    }

    /**
     * What the revision chip was computed against: (the word to replace, the
     * follower that incriminated it), so a stale tap (field changed
     * underneath) can verify before rewriting.
     */
    private var revisionContext: Pair<String, String>? = null

    /** Slack past the two words for trailing separators and punctuation. */
    private val REVISION_LOOKBEHIND = 16

    /**
     * The revision chip: replace the confusable word before the last commit
     * ("their going" → "they're going"). Verifies the field still ends with
     * exactly that pair (plus whatever separators followed) and rewrites
     * nothing otherwise.
     */
    fun onRevisionSuggestionTapped() {
        val state = _uiState.value
        // The near-miss chip shares this slot and this callback, the way the
        // join chip does, and is picked apart by state here rather than by
        // another [ui.KeyboardScreen] parameter — that argument list already
        // compiles to a method at the JVM's 64K ceiling.
        if (state.revisionSuggestion == null && state.correctionOffer != null) {
            applyCorrectionOffer()
            return
        }
        val replacement = state.revisionSuggestion ?: return
        val (wrong, follower) = revisionContext ?: return
        if (composing.isNotEmpty()) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val window = ic
                .getTextBeforeCursor(wrong.length + follower.length + REVISION_LOOKBEHIND, 0)
                ?.toString() ?: return
            // Peel the field tail apart: trailing separators, the follower,
            // the separator run, then the word to replace — each verified
            // against what the chip was computed for.
            val trailing = window.takeLastWhile { !it.isLetter() }
            val tail = window.dropLast(trailing.length)
            if (!tail.endsWith(follower, ignoreCase = true)) return
            val beforeFollower = tail.dropLast(follower.length)
            val sep = beforeFollower.takeLastWhile { !it.isLetter() }
            if (sep.isEmpty()) return
            val core = beforeFollower.dropLast(sep.length)
            if (!core.endsWith(wrong, ignoreCase = true)) return
            // Word boundary: "s o their" must match, "sother" must not.
            if (core.length > wrong.length && core[core.length - wrong.length - 1].isLetter()) {
                return
            }
            val wrongActual = core.takeLast(wrong.length)
            val followerActual = tail.takeLast(follower.length)
            val original = wrongActual + sep + followerActual + trailing
            val display = if (wrongActual.firstOrNull()?.isUpperCase() == true) {
                replacement.replaceFirstChar { it.uppercase() }
            } else {
                replacement
            }
            val committed = display + sep + followerActual + trailing
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(committed, 1)
            // Context: the revised word replaces the old one two words back.
            previousWord2 = replacement.lowercase()
            lastRevertible = RevertibleCommit(
                RevertibleCommit.Kind.REVISION, original = original, committed = committed,
            )
            armRevertGuard()
            invalidateRecentWords()
            invalidateExpectedSelection()
        } finally {
            ic.endBatchEdit()
        }
        revisionContext = null
        _uiState.update { it.copy(revisionSuggestion = null) }
    }

    /**
     * The near-miss chip: put the correction that did not quite fire into the
     * field after all, over the word the user typed.
     *
     * Verified against the field before anything is edited, like every other
     * chip that rewrites committed text: the offer is derived state, a frame
     * behind the keystrokes, so a tap can land just after the word it was
     * about stopped being there. The word is followed by whatever key
     * committed it, which is read back and put down again verbatim rather than
     * guessed at.
     *
     * Tapping is also a statement about the correction, so the result joins
     * the watch and reaches the adaptive gate as an accept once it settles —
     * evidence that the gate was too tight for this pair.
     */
    private fun applyCorrectionOffer() {
        val replacement = _uiState.value.correctionOffer ?: return
        val typed = correctionOfferFor ?: return
        if (composing.isNotEmpty()) return
        val ic = currentInputConnection ?: return
        vibrate()
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val window = ic.getTextBeforeCursor(typed.length + CORRECTION_OFFER_TAIL, 0)
                ?.toString() ?: return
            val trailing = window.takeLastWhile { !WordContext.isWordChar(it) }
            if (trailing.length > CORRECTION_OFFER_TAIL) return
            val core = window.dropLast(trailing.length)
            if (!core.endsWith(typed, ignoreCase = true)) return
            // Word boundary: the chip is about a whole word, not a tail of a
            // longer one the user has since typed into.
            if (core.length > typed.length &&
                WordContext.isWordChar(core[core.length - typed.length - 1])
            ) {
                return
            }
            val typedActual = core.takeLast(typed.length)
            val display = if (typedActual.firstOrNull()?.isUpperCase() == true) {
                replacement.replaceFirstChar { it.uppercase() }
            } else {
                replacement
            }
            val original = typedActual + trailing
            val committed = display + trailing
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(committed, 1)
            previousWord = display.lowercase()
            lastRevertible = RevertibleCommit(
                RevertibleCommit.Kind.AUTOCORRECT, original = original, committed = committed,
            )
            armRevertGuard()
            invalidateRecentWords()
            invalidateExpectedSelection()
            // Watched from here on like any correction that fired on its own,
            // rather than credited outright: the user has accepted the offer,
            // not yet the result, and backspacing it away a second later is a
            // rejection like any other.
            judgeCorrections(correctionWatch.push(typed, replacement))
        } finally {
            ic.endBatchEdit()
        }
        clearCorrectionOffer()
    }

    /** Takes the near-miss chip down, answered or not. */
    private fun clearCorrectionOffer() {
        pendingCorrectionOffer = null
        correctionOfferFor = null
        if (_uiState.value.correctionOffer != null) {
            _uiState.update { it.copy(correctionOffer = null) }
        }
    }

    /** Leading capital carries over from either original part. */
    private fun joinedCase(originalSpan: String, joined: String): String =
        if (originalSpan.firstOrNull()?.isUpperCase() == true) {
            joined.replaceFirstChar { it.uppercase() }
        } else {
            joined
        }

    private fun nextWordStrip(): Pair<List<String>, List<String>> {
        val engine = suggestionEngine
        val state = _uiState.value
        if (engine == null || !state.settings.suggestions || state.secureField ||
            state.fieldNoSuggestions
        ) {
            return emptyList<String>() to emptyList()
        }
        val (emojis, words) = engine
            .suggest(composing = "", previousWord = previousWord, previousWord2 = previousWord2)
            .partition { isEmojiCandidate(it) }
        return words to if (state.settings.emojiPrediction) {
            (emojis + triggerEmojiForPreviousWord()).distinct()
        } else {
            emptyList()
        }
    }

    /**
     * Word→emoji chips for an idle strip. The word just committed keeps
     * offering its emoji after the space, so "happy birthday " still shows 🎂
     * — before this the chip died the moment the word was no longer being
     * composed, which is exactly when someone reaches for it.
     *
     * Learned bigrams come first where both fire: an emoji this user actually
     * types after this word beats a generic trigger.
     */
    private fun triggerEmojiForPreviousWord(): List<String> {
        if (!_uiState.value.settings.emojiPrediction) return emptyList()
        // After an emoji commit the "previous word" is that emoji; suggesting
        // from it would be a lookup on a glyph, and the chip that was just
        // tapped would come straight back.
        val word = previousWord
            ?.takeUnless { isEmojiCandidate(it) || WordContext.isSentinel(it) }
            ?: return emptyList()
        return emojiSuggester?.suggest(word).orEmpty()
    }

    /**
     * Whether anything the user types may be remembered: the setting, incognito,
     * and whether the field allows typing intelligence at all. Shared by the
     * Latin lexicon and the CJK pick history so the two go quiet together.
     */
    private val learningAllowed: Boolean
        get() {
            val state = _uiState.value
            return state.settings.learnFromTyping &&
                !(state.incognitoOn && state.settings.incognitoPausesLearning) &&
                state.allowsTypingIntelligence
        }

    /**
     * Re-derives the statistics gate, then forwards one event to the
     * counters. The gate is computed at the hook rather than subscribed
     * because [KeyboardUiState.incognitoOn] can flip per field, between
     * settings emissions; power saving needs no mention here because the
     * reduced settings object already has [KeyboardSettings.typingStatsEnabled]
     * switched off. The [TypingStats.enabled] setter itself keeps a paused
     * stretch out of the active-time sums.
     */
    private inline fun recordStat(block: TypingStats.() -> Unit) {
        val state = _uiState.value
        typingStats.enabled = state.settings.typingStatsEnabled &&
            !(state.incognitoOn && state.settings.incognitoPausesLearning)
        typingStats.block()
    }

    /**
     * Records one commit against everything that learns from typing.
     *
     * [reinforcement] grades how deliberate the commit was: 2 for a tapped
     * suggestion, 1 for a plainly typed word, 0 for an autocorrect (the word
     * still anchors bigrams but doesn't join the personal lexicon).
     * Multi-word commits ("of the" from a split suggestion) learn each word
     * and the bigrams linking them.
     *
     * Words split two ways here. One the keyboard already recognises is
     * counted straight away, as it always was. One nothing recognises is not
     * learned at all yet — see [noteUnknownWord].
     *
     * [caseTrusted] says whether [word]'s capitals are the user's own. Only a
     * trusted spelling teaches the lexicon how the word is written (#44); an
     * auto-capitalized one is still learned as a word, just not as a spelling.
     * It defaults to false so a new call site has to think about it, which is
     * the safe way round: the cost of not trusting a real capital is that the
     * user types it again, while the cost of trusting a false one is a word
     * that comes back wrong for as long as the vote holds.
     */
    private fun learn(word: String, reinforcement: Int = 1, caseTrusted: Boolean = false) {
        // The pattern gate follows what went into the field, not what the
        // lexicon was allowed to keep, so it is fed on both paths.
        for (part in word.split(' ')) pushRecentWord(part)
        // A chip asking about the *previous* word has been overtaken: the user
        // typed on rather than answering, which is the answer. Cleared before
        // the gate below so it never outlives the word it was about.
        if (learnOfferWord != null) clearLearnOffer()
        if (!learningAllowed) {
            previousWord2 = previousWord
            previousWord = word
            return
        }
        val state = _uiState.value
        var previous = previousWord
        var beforePrevious = previousWord2
        var lastLearned: String? = null
        // Whether the two words a bigram/trigram would hang off are ones the
        // keyboard actually knows. An n-gram with an unrecognised word at
        // either end is a habit built out of a possible typo, and it surfaces
        // in the strip as a next-word suggestion — exactly the rubbish this
        // gate exists to keep out.
        var previousKnown = previous == null || isKnownWord(previous)
        var beforePreviousKnown = beforePrevious == null || isKnownWord(beforePrevious)
        for (part in word.split(' ')) {
            // Combining marks are part of the word, not a boundary: trimming
            // on isLetter alone learns Bengali হয়েছে as হয়েছ. See WordContext.
            val cleaned = part.trim { !WordContext.isWordChar(it) }
            if (cleaned.isEmpty()) continue
            notePerAppWord(cleaned)
            // Attribute the word to whichever mixed language owns it, so the
            // secondary-dictionary weighting tracks the user's real habit.
            suggestionEngine?.recordUsage(cleaned)
            // A word on the never-suggest list is neither counted nor parked
            // in the waiting room: learning it would only put it back in the
            // personal dictionary the user just took it out of (#48).
            val blacklisted = cleaned.lowercase() in state.settings.suggestionBlacklist
            val known = !blacklisted && isKnownWord(cleaned)
            if (known) {
                // Tagged with the active language so a habit learned under one
                // language can be damped when it crowds another's strip.
                userLexicon.learnWord(
                    cleaned,
                    reinforcement,
                    langId = state.language.id,
                    caseEvidence = caseTrusted,
                )
                // Mirror genuinely typed words (not autocorrect targets, which
                // are reinforcement 0 and already dictionary words) into
                // Android's shared personal dictionary when the user has opted
                // in.
                if (reinforcement > 0 && state.settings.addWordsToSystemDictionary) {
                    // Mirrored in a spelling we can stand behind: an
                    // auto-capitalized word would otherwise put a bogus proper
                    // noun into the dictionary every other keyboard reads.
                    val mirrored = if (caseTrusted) cleaned else cleaned.lowercase()
                    serviceScope.launch(Dispatchers.IO) {
                        SystemUserDictionary.add(applicationContext, mirrored)
                    }
                }
                if (previousKnown) {
                    previous?.let { prev ->
                        userLexicon.learnBigram(prev, cleaned)
                        if (beforePreviousKnown) {
                            beforePrevious?.let { userLexicon.learnTrigram(it, prev, cleaned) }
                        }
                    }
                }
            } else if (!blacklisted) {
                // Nothing recognises this word. It goes into the waiting room
                // instead of the dictionary, and only earns its way in once
                // the user has typed it — and left it alone — enough times.
                noteUnknownWord(cleaned, reinforcement, state, caseTrusted)
            }
            beforePrevious = previous
            beforePreviousKnown = previousKnown
            previous = cleaned
            previousKnown = known
            lastLearned = cleaned
        }
        previousWord2 = beforePrevious
        previousWord = lastLearned
    }

    /**
     * Whether the keyboard already recognises [word] — from a dictionary, the
     * personal lexicon, contacts or installed apps.
     *
     * Falls back to the lexicon alone before the engine exists, which is the
     * cautious answer: an unrecognised word is only held back, never lost.
     */
    private fun isKnownWord(word: String): Boolean =
        suggestionEngine?.isKnownWord(word) ?: userLexicon.contains(word)

    /**
     * A word no dictionary knows has been committed.
     *
     * Two ways to deal with it, and the user picks which. Asking outright puts
     * an "add to dictionary?" chip on the strip and learns nothing unless it
     * is tapped. Counting — the default — says nothing and waits: the word is
     * parked in [learningBuffer] until the text around it settles, and only
     * after `newWordSightings` settled uses does it join the lexicon.
     *
     * Either way the word is not learned *now*, which is the whole change: a
     * word learned on first sight is a word autocorrect will never fix again,
     * and most first sightings of an unknown word are typos and sloppy swipes.
     */
    private fun noteUnknownWord(
        word: String,
        reinforcement: Int,
        state: KeyboardUiState,
        caseTrusted: Boolean,
    ) {
        // An autocorrect target is a dictionary word by construction, so a
        // reinforcement of 0 here means the correction landed on something we
        // do not recognise — no evidence about the user's vocabulary at all.
        if (reinforcement <= 0) return
        if (pendingLearn.isDeclined(word)) return
        if (state.settings.suggestionStrip.askBeforeLearning) {
            offerToLearn(word, caseTrusted)
            return
        }
        settleLearned(
            learningBuffer.push(word, state.language.id, reinforcement, caseTrusted),
        )
    }

    /**
     * Counts words whose text has settled, promoting any that have now been
     * seen enough times.
     */
    private fun settleLearned(entries: List<LearningBuffer.Entry>) {
        if (entries.isEmpty()) return
        val threshold = _uiState.value.settings.suggestionStrip.newWordSightings
            .coerceAtLeast(1)
        for (entry in entries) {
            // The word may have been learned, imported or added by hand while
            // it sat in the buffer; there is nothing left to count.
            if (isKnownWord(entry.word)) continue
            // Graded by how deliberate the commit was, the same way
            // [UserLexicon.learnWord] grades its own counts: a candidate the
            // user reached up and tapped says more than one that went past.
            val seen = pendingLearn.sight(entry.word, entry.langId, weight = entry.weight)
            if (seen >= threshold) {
                promoteLearned(entry.word, entry.langId, seen, entry.caseTrusted)
            }
        }
    }

    /**
     * The user backspaced an autocorrect away, putting [word] back.
     *
     * Worth [REVERT_SIGHTINGS] ordinary sightings, and counted immediately
     * rather than parked in the buffer: the buffer answers "did the user leave
     * this text alone", and they have just told us directly. A word already
     * known needs none of this — it was never the autocorrect's target to
     * begin with, or it has been learned since.
     */
    private fun noteRevertedWord(word: String, state: KeyboardUiState) {
        val cleaned = word.trim { !WordContext.isWordChar(it) }
        if (cleaned.isEmpty() || isKnownWord(cleaned)) return
        // The spelling is whatever was standing in the field before the
        // correction fired, and the shift that produced it is long spent, so
        // it teaches the word but not its casing.
        if (state.settings.suggestionStrip.askBeforeLearning) {
            if (!pendingLearn.isDeclined(cleaned)) offerToLearn(cleaned, caseTrusted = false)
            return
        }
        val seen = pendingLearn.sight(cleaned, state.language.id, weight = REVERT_SIGHTINGS)
        val threshold = state.settings.suggestionStrip.newWordSightings.coerceAtLeast(1)
        if (seen >= threshold) {
            promoteLearned(cleaned, state.language.id, seen, caseTrusted = false)
        }
    }

    /**
     * The text stopped moving: everything still queued has settled.
     *
     * [verifyCorrections] is false where the field this all belongs to is no
     * longer the one the keyboard is attached to. Reading text then would
     * judge these corrections against somebody else's text, so the ones that
     * needed a read simply go unjudged.
     */
    private fun flushLearningBuffer(verifyCorrections: Boolean = true) {
        settleLearned(learningBuffer.drain())
        judgeCorrections(correctionWatch.drain(), verify = verifyCorrections)
    }

    /**
     * Delivers the verdict on corrections whose text has settled.
     *
     * An entry the caret never went back in front of is an accept, and needs
     * no evidence beyond that: the user typed straight past it. One the caret
     * *did* go back in front of is only a suspect, because going back covers
     * everything from rewriting that word to adding a comma three words
     * earlier. Those are settled by reading the field once and asking which
     * spelling is standing there now.
     *
     * Anything the read cannot answer is dropped rather than guessed. That
     * covers the word being deleted outright, the correction having scrolled
     * out of the window, and a message field that was sent (the text is gone
     * by then, so there is nothing left to check). A missing verdict costs the
     * adaptive gate one sample; a wrong one teaches it something false.
     */
    private fun judgeCorrections(entries: List<CorrectionWatch.Entry>, verify: Boolean = true) {
        if (entries.isEmpty()) return
        val undisturbed = entries.filterNot { it.disturbed }
        for (entry in undisturbed) correctionStats.recordKept(entry.typed, entry.corrected)
        val suspects = entries.filter { it.disturbed }
        if (suspects.isEmpty() || !verify) return
        val window = correctionJudgementWindow() ?: return
        for (entry in suspects) {
            val kept = containsWord(window, entry.corrected)
            val undone = containsWord(window, entry.typed)
            when {
                // Both readings present somewhere in the window: the user has
                // used each word, and nothing here says which one replaced
                // this correction. No verdict.
                kept && undone -> Unit
                kept -> correctionStats.recordKept(entry.typed, entry.corrected)
                // What the user typed is standing where the fix was. This is
                // the case the immediate-backspace path could never see, and
                // it is read off the field rather than pressed, so it goes in
                // as an indirect verdict: weaker than a backspace, and thrown
                // away entirely when the surviving spelling is not a word (the
                // user reproducing their own typo, not defending it). See
                // [SuggestionEngine.rejectCorrection].
                undone -> suggestionEngine?.rejectCorrection(
                    entry.typed, entry.corrected, deliberate = false,
                )
                else -> Unit
            }
        }
    }

    /**
     * The text a correction verdict is read against, or null when the field
     * cannot answer.
     *
     * Deliberately one read of a bounded window rather than the whole field:
     * this runs at a flush (the keyboard closing, the field changing), never
     * on the typing path, and a correction further back than this has scrolled
     * out of the watch anyway.
     */
    private fun correctionJudgementWindow(): String? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(CORRECTION_JUDGEMENT_WINDOW, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(CORRECTION_JUDGEMENT_WINDOW, 0)?.toString().orEmpty()
        val window = before + after
        return window.takeIf { it.isNotBlank() }
    }

    /** Whether [word] stands in [text] as a word, not inside a longer one. */
    private fun containsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + word.length)
            if (before?.let(WordContext::isWordChar) != true &&
                after?.let(WordContext::isWordChar) != true
            ) {
                return true
            }
            from = at + 1
        }
    }

    /**
     * Hands the reported caret to the settle buffer, which uses it to notice
     * the user going back into text it is holding.
     *
     * The empty-field check is the one thing here that costs anything, and it
     * is what makes this work in a chat app: hitting send empties the field
     * and parks the caret at 0, which is indistinguishable from jumping to the
     * top of a draft until you look. Sent text is as settled as text gets, and
     * a draft the user has jumped to the top of is text they are about to
     * edit — opposite answers, so the one read is worth it. It only ever runs
     * with words queued *and* the caret at 0, so it is not on the typing path.
     */
    private fun noteCaretForLearning(selStart: Int, selEnd: Int) {
        if (learningBuffer.isEmpty() && correctionWatch.isEmpty()) return
        // A range selection is a selection, not a resting place: anchoring to
        // it would point entries at text that is about to be replaced.
        if (selStart != selEnd) return
        if (selStart == 0 &&
            currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty()
        ) {
            // Before the caret is handed on, so a send does not read as the
            // user going back in front of every word in the message.
            flushLearningBuffer()
            return
        }
        learningBuffer.onCaret(selStart)
        correctionWatch.onCaret(selStart)
    }

    /**
     * [word] has been typed and left alone enough times: it is the user's now.
     *
     * Learned with its sighting count rather than a flat 1, so a word that
     * took five uses to get here starts out weighted like the five uses it
     * was.
     */
    private fun promoteLearned(
        word: String,
        langId: String,
        sightings: Int,
        caseTrusted: Boolean,
    ) {
        pendingLearn.forget(word)
        userLexicon.learnWord(
            word,
            count = sightings,
            langId = langId,
            caseEvidence = caseTrusted,
        )
        if (_uiState.value.settings.addWordsToSystemDictionary) {
            val mirrored = if (caseTrusted) word else word.lowercase()
            serviceScope.launch(Dispatchers.IO) {
                SystemUserDictionary.add(applicationContext, mirrored)
            }
        }
    }

    /**
     * A committed emoji learns the word→emoji bigram ("you" → ❤️ after
     * "I love you ❤️"), so next-word prediction can offer the emoji the
     * next time the phrase is typed. The emoji then becomes the previous
     * "word" so emoji→word habits are learned too.
     */
    private fun learnEmoji(emoji: String) {
        // An emoji ends whatever span a pattern was reaching across, so the
        // gate has to see it land the same way a word does.
        pushRecentWord(emoji)
        val state = _uiState.value
        if (!state.settings.learnFromTyping ||
            (state.incognitoOn && state.settings.incognitoPausesLearning) ||
            !state.allowsTypingIntelligence
        ) {
            previousWord = emoji
            return
        }
        // Same gate as the word bigrams in [learn]: a habit hung off a word
        // nothing recognises is a habit hung off a possible typo, and it comes
        // back as an emoji suggestion after that misspelling forever.
        previousWord?.let { if (isKnownWord(it)) userLexicon.learnBigram(it, emoji) }
        previousWord = emoji
    }

    /**
     * Counts one use of [emoji] towards recents, frequents and the emoji row.
     *
     * Skipped in incognito, which is the one thing "Pause learning" already
     * promised and did not do: its own subtitle says no emoji habits are
     * learned, while every path that commits an emoji recorded one regardless.
     * A private message leaving its emoji at the front of the history tab is
     * exactly what incognito exists to stop.
     *
     * Separate from [learnEmoji], which is about word→emoji prediction and also
     * answers to `learnFromTyping`. The history tab is a list of what the user
     * pressed, not something inferred from it, so turning off learning does not
     * empty it.
     */
    private fun recordEmojiUse(emoji: String) {
        val state = _uiState.value
        if (state.incognitoOn && state.settings.incognitoPausesLearning) return
        emojiUsage.record(emoji)
        emojiHistoryStale = true
    }

    /**
     * Reads contact display names into [contactNames] (memory only, never
     * persisted). No-op without the permission — the settings app requests
     * it, and [onStartInputView] retries once it has been granted.
     */
    private fun loadContactNames() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val names = ArrayList<String>()
                    contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val nameColumn = cursor.getColumnIndexOrThrow(
                            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                        )
                        while (cursor.moveToNext()) {
                            cursor.getString(nameColumn)?.let { names.add(it) }
                        }
                    }
                    ContactNames.fromNames(names)
                }.getOrDefault(ContactNames.EMPTY)
            }
            contactNames = loaded
            suggestionEngine?.contacts = loaded
        }
    }

    /**
     * Reads contact email addresses into [contactEmails] (memory only, never
     * persisted). No-op without the permission — the settings app requests it,
     * and [onStartInputView] retries once it has been granted.
     */
    private fun loadContactEmails() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val emails = ArrayList<String>()
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val addressColumn = cursor.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Email.ADDRESS
                        )
                        while (cursor.moveToNext()) {
                            cursor.getString(addressColumn)?.let { emails.add(it) }
                        }
                    }
                    ContactEmails.fromAddresses(emails)
                }.getOrDefault(ContactEmails.EMPTY)
            }
            contactEmails = loaded
            suggestionEngine?.contactEmails = loaded
        }
    }

    /**
     * Reads the labels of launchable apps into [appNames] (memory only,
     * never persisted). Needs no permission: the launcher-intent query is
     * covered by the <queries> manifest entry.
     */
    private fun loadAppNames() {
        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                    val pm = packageManager
                    val labels = pm.queryIntentActivities(intent, 0)
                        .map { it.loadLabel(pm).toString() }
                        .distinct()
                    AppNames.fromNames(labels)
                }.getOrDefault(AppNames.EMPTY)
            }
            appNames = loaded
            suggestionEngine?.apps = loaded
        }
    }

    /**
     * Reads Android's personal dictionary into [systemDictionaryWords] and
     * [userDictShortcuts]. Content-provider I/O: call off the main thread.
     * The words honour [SuggestionStripSettings.useSystemDictionary] here,
     * so turning the setting off empties the source; the shortcut map is
     * gated where it is consulted instead, as it always was.
     */
    private fun readSystemDictionary() {
        val settings = _uiState.value.settings.suggestionStrip
        systemDictLoadedWith = settings.useSystemDictionary
        systemDictionaryWords = if (settings.useSystemDictionary) {
            runCatching { SystemUserDictionary.words(applicationContext) }
                .getOrDefault(SystemUserDictionary.Entries.EMPTY)
        } else {
            SystemUserDictionary.Entries.EMPTY
        }
        userDictShortcuts = runCatching { SystemUserDictionary.shortcuts(applicationContext) }
            .getOrDefault(emptyMap())
    }

    /**
     * Re-reads the personal dictionary and hands the words to the engine.
     * Called when the platform reports the list changed and when the setting
     * flips; a no-op before the dictionaries have loaded, since that load
     * reads the list itself.
     */
    private fun reloadSystemDictionary() {
        if (suggestionEngine == null) return
        serviceScope.launch {
            withContext(Dispatchers.IO) { readSystemDictionary() }
            suggestionEngine?.let {
                it.systemWordCases = systemDictionaryWords.shapes
                it.systemDictionary = systemDictionaryWords.source
            }
        }
    }

    /**
     * Keeps [systemDictionaryWords] current across edits made in System
     * settings (or by another keyboard) while this one runs. Registered
     * unconditionally: the observer is free, and the reload it triggers is
     * what honours the setting.
     */
    private fun startUserDictionaryWatch() {
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper()),
        ) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                reloadSystemDictionary()
            }
        }
        userDictObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.UserDictionary.Words.CONTENT_URI,
                true,
                observer,
            )
        }
    }

    // ---- app launcher tool ----

    /**
     * The launchable-app list, loaded once per process and reused across panel
     * opens; a package install/remove drops it (see [packageChangeReceiver])
     * so the next open re-enumerates.
     */
    private var launcherAppsCache: List<LauncherApp>? = null

    /**
     * Decoded app icons, keyed by flattened component. Bounded: an adaptive
     * icon decodes to a fixed 48dp square, so the whole cache stays around a
     * megabyte and lives for the process — a panel close is not a reason to
     * re-decode a hundred icons.
     */
    private val launcherIconCache = android.util.LruCache<String, ImageBitmap>(128)

    private var launcherDetailJob: Job? = null

    /** One stable bundle for the panel; see [LauncherPanelCallbacks]'s doc. */
    private val launcherCallbacks by lazy {
        com.wasimaster.wmkeyboard.ime.ui.LauncherPanelCallbacks(
            onAppTap = ::onLauncherAppTap,
            onOpenDetail = ::onLauncherOpenDetail,
            onActivityTap = ::onLauncherActivityTap,
            onPinToggle = ::onLauncherPinToggle,
            onAppInfo = ::onLauncherAppInfo,
            onDetailClose = ::onLauncherDetailClose,
            iconFor = ::launcherIconFor,
        )
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            launcherAppsCache = null
            launcherIconCache.evictAll()
            _uiState.update { it.copy(launcherApps = emptyList(), launcherDetail = null) }
        }
    }

    /**
     * Stays registered for the rest of the process's life, like the DND
     * observer ([startDndWatch]): an IME process dies with its receivers, and
     * an install/removal while the keyboard is up is exactly the case the
     * cache invalidation has to catch.
     */
    internal fun registerPackageChangeReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageChangeReceiver, filter)
    }

    /** Fills [KeyboardUiState.launcherApps] from the cache or a fresh query. */
    private fun loadLauncherApps() {
        val cached = launcherAppsCache
        if (cached != null) {
            _uiState.update { it.copy(launcherApps = cached, launcherLoading = false) }
            return
        }
        _uiState.update { it.copy(launcherLoading = true) }
        serviceScope.launch {
            // Failure handling lives inside loadApps (empty list), so nothing
            // here swallows a coroutine cancellation by accident.
            val apps = AppCatalog.loadApps(packageManager)
            launcherAppsCache = apps
            _uiState.update { it.copy(launcherApps = apps, launcherLoading = false) }
        }
    }

    /** One app's icon for the panel grid, through the LRU, decoded on IO. */
    internal suspend fun launcherIconFor(app: LauncherApp): ImageBitmap? {
        val key = "${app.packageName}/${app.activityName}"
        launcherIconCache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val px = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                packageManager.getActivityIcon(app.component)
                    .toBitmap(px, px)
                    .asImageBitmap()
            }.getOrNull()
        } ?: return null
        launcherIconCache.put(key, bitmap)
        return bitmap
    }

    fun onLauncherAppTap(app: LauncherApp) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent().setComponent(app.component)
        launchFromLauncherPanel(intent, app.packageName)
    }

    fun onLauncherActivityTap(activity: LauncherActivity) {
        launchFromLauncherPanel(Intent().setComponent(activity.component), activity.packageName)
    }

    /**
     * Starts the target and closes the panel — the foreground app is about to
     * change, so a keyboard left showing the launcher would be stale. Failures
     * (a non-exported activity, an OEM background-start rule) just keep the
     * panel up.
     */
    private fun launchFromLauncherPanel(intent: Intent, packageName: String) {
        val started = runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!started) return
        serviceScope.launch { settingsRepository.addLauncherRecent(packageName) }
        if (_uiState.value.panel == PanelMode.APP_LAUNCHER) {
            onPanelChange(PanelMode.APP_LAUNCHER)
        }
    }

    fun onLauncherOpenDetail(app: LauncherApp) {
        _uiState.update { it.copy(launcherDetail = LauncherDetailUi(app)) }
        launcherDetailJob?.cancel()
        launcherDetailJob = serviceScope.launch {
            val activities = AppCatalog.loadActivities(packageManager, app.packageName)
            _uiState.update { state ->
                val detail = state.launcherDetail
                if (detail?.app?.packageName == app.packageName) {
                    state.copy(
                        launcherDetail = detail.copy(activities = activities, loading = false),
                    )
                } else {
                    state
                }
            }
        }
    }

    fun onLauncherDetailClose() {
        launcherDetailJob?.cancel()
        _uiState.update { it.copy(launcherDetail = null) }
    }

    fun onLauncherPinToggle(packageName: String) {
        serviceScope.launch { settingsRepository.toggleLauncherPin(packageName) }
    }

    fun onLauncherAppInfo(packageName: String) {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Arms or clears the dead-key accent, keeping the strip chip in step. */
    private fun setPendingDeadKey(mark: Char?) {
        pendingDeadKey = mark
        val label = mark?.let { DeadKeys.standalone(it) }
        if (_uiState.value.pendingDeadKey != label) {
            _uiState.update { it.copy(pendingDeadKey = label) }
        }
    }

    /** Heuristic: a learned bigram successor that is an emoji, not a word. */
    private fun isEmojiCandidate(text: String): Boolean =
        text.isNotBlank() && text.none { it.isLetterOrDigit() } && text.any { it.code > 0x2000 }

    /**
     * The inline emoji query when the composing buffer is one — ":smi" gives
     * "smi". Null whenever inline search is off or the buffer is an ordinary
     * word, so callers can branch on it directly.
     */
    private fun inlineEmojiQuery(): String? {
        if (!_uiState.value.settings.inlineEmojiSearch) return null
        val typed = composing.toString()
        return if (typed.startsWith(":")) typed.drop(1) else null
    }

    // ---- smart suggestions (inline tool answers) ----

    /**
     * Re-scans the text before the cursor for something a tool can answer —
     * a sum, an amount in a currency, a measurement, a tool keyword — and
     * parks the result in [KeyboardUiState.smart] for the strip to draw.
     *
     * Cheap enough to run inline on every keystroke: one short
     * `getTextBeforeCursor` plus a handful of anchored regexes over at most
     * [SmartSuggest.LOOKBEHIND] characters. It deliberately does not follow
     * `settings.suggestions` — someone who turned word prediction off may
     * still want "12*4" answered — but it does respect the field's own
     * refusal to take suggestions, and never runs in a password field.
     */
    /**
     * Text before the cursor as it stood right after a chip was accepted.
     * An inserted answer is often itself a trigger ("18,300.00 BDT" reads as
     * an amount to convert back), so the chip stays down until the field
     * changes again — matching on the text rather than a flag means any
     * edit at all, from anywhere, lifts the mute.
     */
    private var smartMutedAfter: String? = null

    /**
     * Intent chips this field has already shown and moved past. An intent
     * chip is a hint, and a hint repeated is a nag: once the chip stops
     * matching (the user typed on, opened the tool, or a panel came up), the
     * same tool does not get to hint again until the next field.
     */
    private val intentChipsRetired = mutableSetOf<ToolbarTool>()

    /** Records [next] replacing (or clearing) a shown intent or lookup chip. */
    private fun retireIntentChip(next: SmartSuggest.SmartHit?) {
        val prev = _uiState.value.smart ?: return
        if (prev.kind != SmartSuggest.Kind.INTENT && prev.kind != SmartSuggest.Kind.LOOKUP) return
        if (next != null && next.kind == prev.kind && next.tool == prev.tool) return
        intentChipsRetired += prev.tool
    }

    private fun clearSmartChip() {
        retireIntentChip(null)
        if (_uiState.value.smart != null) _uiState.update { it.copy(smart = null) }
    }

    private fun refreshSmartSuggestion() {
        val state = _uiState.value
        val enabled = state.settings.smartSuggestions &&
            !state.secureField && !state.fieldNoSuggestions &&
            state.panel == PanelMode.NONE &&
            // A smart chip takes the whole strip, and in a conversion IME that
            // strip is the candidate list — losing it mid-reading would leave
            // the user with a composing buffer and nothing to commit it with.
            !state.composer.isConversion
        if (!enabled) {
            clearSmartChip()
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            clearSmartChip()
            return
        }
        // The lookbehind is a synchronous editor round-trip and this runs on
        // every keystroke, so it goes to a worker thread: with the target app
        // busy (first key into a just-opened field, especially) the blocking
        // read was the visible keypress lag — and the chip is advisory UI
        // that can afford to land a frame later.
        smartJob?.cancel()
        smartJob = serviceScope.launch {
            // Debounced before the read, not after. One keystroke reaches here
            // twice — once from refreshSuggestions and once from the
            // onUpdateSelection the commit provokes — and cancelling the first
            // job did not help, because by the time the selection update
            // arrives that job has already issued its round-trip into the
            // target app and been answered. Sleeping first means the second
            // scheduling cancels the first while it is still asleep, so a
            // keystroke costs one cross-process read instead of two. The chip
            // is advisory and lands about a frame later than it did.
            delay(SMART_SUGGEST_DEBOUNCE_MS)
            // The gate above was read before the sleep. A panel opening, or the
            // field turning out to be secure, does not cancel this job — so
            // re-read it, or the chip publishes into a state that refuses to
            // show one. The window is only as long as the debounce, but it is
            // the debounce that created it.
            val still = _uiState.value
            if (still.panel != PanelMode.NONE || still.secureField ||
                still.fieldNoSuggestions || !still.settings.smartSuggestions ||
                // Same list as the gate above, conversion composer included: a
                // layout switch inside the debounce window would otherwise let
                // a chip take the row the candidate list needs.
                still.composer.isConversion
            ) {
                clearSmartChip()
                return@launch
            }
            val before = withContext(Dispatchers.Default) {
                ic.getTextBeforeCursor(SmartSuggest.LOOKBEHIND, 0)?.toString().orEmpty()
            }
            if (before == smartMutedAfter) {
                clearSmartChip()
                return@launch
            }
            smartMutedAfter = null
            var hit = SmartSuggest.detect(before, smartContext(_uiState.value))
            // A retired hint stays down; answers (calc, dates, weather) are
            // immune — they carry information, not advice.
            if (hit != null &&
                (hit.kind == SmartSuggest.Kind.INTENT || hit.kind == SmartSuggest.Kind.LOOKUP) &&
                hit.tool in intentChipsRetired
            ) {
                hit = null
            }
            retireIntentChip(hit)
            if (hit != _uiState.value.smart) _uiState.update { it.copy(smart = hit) }
            // Recognised but missing data: fetch it, and the completion
            // redraws the chip.
            when {
                hit?.pendingWeather == true -> refreshWeather()
                hit?.pending == true -> refreshCurrencyRates(wantCrypto = hit.pendingCrypto)
            }
        }
    }

    private fun smartContext(state: KeyboardUiState): SmartSuggest.Context =
        SmartSuggest.Context(
            calcEnabled = state.settings.smartCalc,
            currencyEnabled = state.settings.smartCurrency,
            unitsEnabled = state.settings.smartUnits,
            keywordsEnabled = state.settings.smartToolKeywords,
            degrees = state.settings.calcDegrees,
            precision = state.settings.calcPrecision,
            rates = (state.currency as? CurrencyUi.Ready)?.rates,
            currencyFrom = state.settings.currencyFrom,
            currencyTo = state.settings.currencyTo,
            currencyDecimals = state.settings.currencyDecimals,
            cryptoEnabled = state.settings.rateSources.cryptoEnabled,
            cryptoTickers = state.settings.rateSources.cryptoTickers,
            cryptoDecimals = state.settings.rateSources.cryptoDecimals,
            cryptoUnavailable = (state.currency as? CurrencyUi.Ready)?.cryptoFailed == true,
            unitLast = state.settings.unitConvertLast,
            compoundUnits = state.settings.compoundUnits,
            enabledTools = usableTools(state.settings),
            keywordOverrides = state.settings.toolKeywords,
            caseSensitiveKeywords = state.settings.toolKeywordCase,
            dateChips = state.settings.smartChips.dates,
            todayJdn = todayJdn(),
            weatherChips = state.settings.smartChips.weather,
            weather = freshWeather(state),
            // A failed fetch must not leave the chip spinning forever, so a
            // weather error counts as "not available" until something else
            // refreshes it.
            weatherAvailable = state.settings.weatherLatitude != null &&
                state.settings.weatherLongitude != null &&
                state.weather !is WeatherUi.Error,
            weatherFahrenheit = state.settings.weatherFahrenheit,
            lookupChips = state.settings.smartChips.lookups,
            intentChips = state.settings.smartChips.intents,
            gifChips = state.settings.smartChips.gifs,
        )

    private fun todayJdn(): Long = Calendar.getInstance().let {
        CalendarSystems.gregorianToJdn(
            it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** The fetched conditions, only while still within the cache window. */
    private fun freshWeather(state: KeyboardUiState): WeatherInfo? =
        (state.weather as? WeatherUi.Ready)?.info?.takeIf {
            System.currentTimeMillis() - it.fetchedAtMillis < weatherCacheMs()
        }

    /**
     * Chip tapped: swap the recognised text for the answer. The span is
     * whatever the trigger occupied, so "150usd" is replaced outright while
     * a trailing "=" keeps what was typed and appends the result.
     */
    fun onSmartSuggestionTapped() {
        val hit = _uiState.value.smart ?: return
        val insert = hit.insert ?: return
        stopVoiceForManualInput()
        vibrate()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        commitComposing(ic, autocorrect = false)
        if (hit.replaceSpan > 0) ic.deleteSurroundingText(hit.replaceSpan, 0)
        ic.commitText(insert, 1)
        ic.endBatchEdit()
        smartMutedAfter = ic.getTextBeforeCursor(SmartSuggest.LOOKBEHIND, 0)?.toString()
        composing = StringBuilder()
        lastGestureWord = null
        _uiState.update {
            it.copy(
                composingPreview = "", smart = null,
                suggestions = emptyList(), emojiSuggestions = emptyList(),
            )
        }
        refreshSuggestions()
    }

    /**
     * Chip's open button: drop the recognised text (the tool is about to
     * type its own result there) and load it into the tool as a prefill.
     * The caller then taps the tool the normal way, so panel routing stays
     * in one place.
     */
    fun onSmartSuggestionOpen() {
        val hit = _uiState.value.smart ?: return
        // A hint that was taken has done its job for this field.
        if (hit.kind == SmartSuggest.Kind.INTENT || hit.kind == SmartSuggest.Kind.LOOKUP) {
            intentChipsRetired += hit.tool
        }
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            commitComposing(ic, autocorrect = false)
            if (hit.replaceSpan > 0) ic.deleteSurroundingText(hit.replaceSpan, 0)
            ic.endBatchEdit()
        }
        composing = StringBuilder()
        _uiState.update {
            it.copy(
                composingPreview = "", smart = null, toolPrefill = hit.prefill,
                suggestions = emptyList(), emojiSuggestions = emptyList(),
            )
        }
    }

    /** A panel has loaded its prefill; drop it so reopening starts clean. */
    fun onToolPrefillConsumed() {
        if (_uiState.value.toolPrefill != null) _uiState.update { it.copy(toolPrefill = null) }
    }

    /**
     * True when the email-field contact-email path should drive the strip:
     * an email field, the feature and its email-field override both on, the
     * master strip on, not a password box, and some addresses to offer. This
     * deliberately ignores [KeyboardUiState.fieldNoSuggestions] — the whole
     * point is to complete addresses even where the field asked for a silent
     * strip (which email fields do).
     */
    private fun emailFieldForceActive(state: KeyboardUiState = _uiState.value): Boolean =
        state.fieldKind == FieldKind.EMAIL &&
            !state.secureField &&
            state.settings.suggestions &&
            state.settings.contactEmailSuggestions &&
            state.settings.contactEmailSuggestionsInEmailFields &&
            !contactEmails.isEmpty

    /** The email-address token immediately before the cursor (may be empty). */
    private fun emailTokenBeforeCursor(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(EMAIL_FIELD_LOOKBEHIND, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetterOrDigit() || it in EMAIL_TOKEN_EXTRA }
    }

    /**
     * Email-field completion: contact emails whose address starts with the
     * token before the cursor, pushed into the strip even though the field
     * suppresses the normal one. Email fields keep no composing buffer, so the
     * token is read straight from the connection.
     */
    private fun refreshEmailFieldSuggestions() {
        suggestionJob?.cancel()
        val ic = currentInputConnection
        val token = ic?.let { emailTokenBeforeCursor(it) }.orEmpty().lowercase()
        if (token.length < EMAIL_FIELD_MIN_PREFIX) {
            _uiState.update {
                if (it.suggestions.isEmpty()) it
                else it.copy(suggestions = emptyList(), emojiSuggestions = emptyList())
            }
            return
        }
        suggestionJob = serviceScope.launch {
            val results = withContext(Dispatchers.Default) {
                contactEmails.complete(token, EMAIL_FIELD_SUGGESTION_LIMIT)
            }
            _uiState.update {
                it.copy(suggestions = results, emojiSuggestions = emptyList(), inlineEmoji = false)
            }
        }
    }

    private fun refreshSuggestions() {
        val state = _uiState.value
        if (emailFieldForceActive(state)) {
            refreshEmailFieldSuggestions()
            return
        }
        refreshSmartSuggestion()
        // Ahead of the engine check: a snippet offer is the user's own text
        // waiting on their own trigger, not a guess the dictionary made, so it
        // does not wait for a lexicon to have finished loading.
        refreshSnippetOffer(state)
        val engine = suggestionEngine ?: return
        if (!state.settings.suggestions || state.secureField || state.fieldNoSuggestions) return

        // Inline emoji search takes over the strip entirely: word suggestions
        // for ":smi" would be noise. A bare ":" shows nothing until there is
        // something to search for.
        inlineEmojiQuery()?.let { query ->
            suggestionJob?.cancel()
            suggestionJob = serviceScope.launch {
                delay(EMOJI_SEARCH_DEBOUNCE_MS)
                val results = if (query.length < 2) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        emojiSearch?.search(query, limit = INLINE_EMOJI_LIMIT)
                            .orEmpty()
                            .map { it.emoji }
                    }
                }
                _uiState.update {
                    it.copy(
                        suggestions = results,
                        emojiSuggestions = emptyList(),
                        punctuationSuggestions = emptyList(),
                        inlineEmoji = true,
                    )
                }
            }
            return
        }

        val typed = composing.toString()

        // A caret touching a word it is not parked at the end of: the strip
        // answers about that word instead of predicting the next one (#32).
        // Nothing is composing, so none of the buffer machinery below — the
        // commit resolution, the next-letter bias, the join and revision chips
        // — has anything to say about it.
        val caret = caretWord
        if (typed.isEmpty() && caret != null && !state.composer.isTransliterating) {
            publishCaretWordSuggestions(engine, caret)
            return
        }

        // Conversion IMEs (Chinese Pinyin, Japanese) show the composer's own
        // reading→character candidates in the strip, not dictionary word
        // suggestions. The lookup is a cheap map read, so it runs inline.
        if (state.composer.isConversion) {
            suggestionJob?.cancel()
            commitResolution = null
            val cands = state.composer.candidates(typed)
            // The grid is a widening of the same ranking, so it only costs
            // anything while it is actually open.
            val expanded = if (state.panel == PanelMode.CANDIDATES) {
                state.composer.candidates(typed, CANDIDATE_GRID_LIMIT)
            } else {
                emptyList()
            }
            _uiState.update {
                it.copy(
                    suggestions = cands,
                    expandedCandidates = expanded,
                    emojiSuggestions = emptyList(),
                    punctuationSuggestions = emptyList(),
                    inlineEmoji = false,
                )
            }
            return
        }

        suggestionJob?.cancel()
        suggestionJob = serviceScope.launch {
            // Short adaptive debounce: fast bursts of keystrokes cancel the
            // job while it still sleeps here, so only the final state is
            // computed. The window tracks half the average compute cost,
            // clamped so it never becomes perceptible.
            delay((suggestionCostMs / 2).coerceIn(16L, 40L))
            val started = SystemClock.uptimeMillis()
            val touchFrame = composingTouchFrame()
            // Snapshot like the touch frame: the rhythm belongs to the word
            // as typed now, not to whatever the buffer holds when the async
            // precompute actually runs.
            val timingMultiplier = timingMultiplier()
            val recentSnapshot = recentWords.toList()
            val (results, emojis, bias) = withContext(Dispatchers.Default) {
                val suggested = engine.suggest(
                    composing = typed,
                    previousWord = previousWord,
                    avroMode = state.composer.isBengaliPhonetic,
                    touch = touchFrame,
                    previousWord2 = previousWord2,
                    recentWords = recentSnapshot,
                    allowRerank = true,
                )
                // A28: a personal-dictionary shortcut typed in full offers its
                // expansion as the top chip (e.g. "omw" → "on my way"). Prepended
                // so it wins the primary slot; deduped against the word list.
                val words = if (
                    state.settings.suggestionStrip.expandUserDictShortcuts && typed.isNotEmpty()
                ) {
                    userDictShortcuts[typed.lowercase()]
                        ?.let { listOf(it) + suggested.filterNot { w -> w == it } }
                        ?: suggested
                } else {
                    suggested
                }
                // Next-letter distribution for smart key-hit detection. Only for
                // plain Latin composing — conversion/transliteration IMEs commit
                // through their own composer, where a Latin-letter nudge is wrong.
                val bias = if (
                    state.settings.layoutBehavior.smartHitDetection &&
                    typed.isNotEmpty() &&
                    !state.composer.isTransliterating
                ) {
                    engine.nextLetterWeights(typed)
                } else {
                    emptyMap()
                }
                // Precompute what a space/enter commit of this exact word would
                // resolve to, so the commit need not run the edit-distance
                // search (English) or transliteration ranking (Bengali) on the
                // main thread. commitComposing consumes it only on a typed match.
                commitResolution = when {
                    typed.isEmpty() -> null
                    state.composer.isBengaliPhonetic -> CommitResolution(
                        typed = typed,
                        isBengali = true,
                        bengaliTop = words.firstOrNull(),
                        correction = null,
                    )
                    state.settings.autocorrect && state.allowsTypingIntelligence -> {
                        val decision = engine.decideCorrection(
                            typed, touch = touchFrame, timingMultiplier = timingMultiplier,
                        )
                        CommitResolution(
                            typed = typed,
                            isBengali = false,
                            bengaliTop = null,
                            correction = decision.apply?.takeIf { it != typed },
                            offer = decision.offer?.takeIf { it != typed },
                        )
                    }
                    else -> null
                }
                if (typed.isNotEmpty()) {
                    val emojis = if (state.settings.emojiPrediction) {
                        // The word before is passed for the two-word shortcodes
                        // ("alarm clock" → ⏰); one word can never reach them.
                        emojiSuggester?.suggest(typed, previousWord.orEmpty()).orEmpty()
                    } else {
                        emptyList()
                    }
                    Triple(words, emojis, bias)
                } else {
                    // Next-word prediction: learned bigrams can end in an
                    // emoji ("you" → ❤️). Those belong in the emoji slot of
                    // the strip, not among the word chips — and so does the
                    // trigger emoji of the word that just committed.
                    val (emojiNext, wordNext) = words.partition { isEmojiCandidate(it) }
                    Triple(
                        wordNext,
                        if (state.settings.emojiPrediction) {
                            (emojiNext + triggerEmojiForPreviousWord()).distinct()
                        } else {
                            emptyList()
                        },
                        bias,
                    )
                }
            }
            suggestionCostMs = (suggestionCostMs + (SystemClock.uptimeMillis() - started)) / 2
            // Drop emoji the device can't draw, then apply the default skin tone
            // (filter first — the hidden set is keyed by the neutral base).
            val hidden = _uiState.value.hiddenEmoji
            val shownEmojis = emojis
                .let { list -> if (hidden.isEmpty()) list else list.filterNot { it in hidden } }
                .map { applyEmojiTone(it) }
            // Quick-punctuation rides the tail beside the word candidates, but
            // only when there are candidates to ride (otherwise the strip is
            // idle and flips to the toolbar) and no emoji prediction is actually
            // drawn in the tail (gate on the shown set, not the raw one).
            val punct = if (
                state.settings.suggestionStrip.punctuation &&
                results.isNotEmpty() &&
                shownEmojis.isEmpty()
            ) {
                // The user's own marks, one chip per character, so a Bengali
                // danda or a Spanish inverted mark can sit here instead of the
                // English five. Blank has already fallen back to the shipped
                // set on the way out of the repository.
                state.settings.suggestionStrip.punctuationChips.map { it.toString() }
            } else {
                emptyList()
            }
            // Join chip: "some" committed, "thing" composing, "something" a
            // clearly better word. A handful of map hits — fine on main.
            val join = if (
                typed.isNotEmpty() && !state.composer.isTransliterating &&
                !state.composer.isConversion && state.allowsTypingIntelligence
            ) {
                engine.joinCandidate(previousWord, typed)
            } else {
                null
            }
            joinContext = join?.let { previousWord.orEmpty() to typed }
            // Revision chip: the word just committed sometimes proves the one
            // before it wrong ("their going" → "they're"). Empty-composing
            // only — the moment of the space is when the evidence lands — and
            // chip-only: it never rewrites anything by itself.
            val revision = if (
                typed.isEmpty() && state.allowsTypingIntelligence &&
                engine.englishSources && previousWord2 != null && previousWord != null
            ) {
                revisionAdvisor?.advise(previousWord2, previousWord)
            } else {
                null
            }
            revisionContext = revision?.let { previousWord2.orEmpty() to previousWord.orEmpty() }
            // The near-miss chip belongs to the word that was just committed,
            // so it only survives while the composing buffer is still empty:
            // start the next word and the offer about the last one is gone.
            val offer = pendingCorrectionOffer
                ?.takeIf { typed.isEmpty() && state.settings.suggestionStrip.offerNearMissCorrections }
            if (offer == null) {
                pendingCorrectionOffer = null
                correctionOfferFor = null
            }
            _uiState.update {
                it.copy(
                    suggestions = results,
                    emojiSuggestions = shownEmojis,
                    punctuationSuggestions = punct,
                    nextLetterBias = bias,
                    inlineEmoji = false,
                    joinSuggestion = join,
                    revisionSuggestion = revision,
                    correctionOffer = offer,
                )
            }
        }
    }

    /**
     * The strip for a caret sitting inside a word: completions and corrections
     * for the whole word, the word itself dropped because tapping it would
     * replace it with itself.
     *
     * Its own small path rather than a detour through the composing one. There
     * is no keystroke behind this, so there is no touch frame to rank against
     * and no typing rhythm to weigh; and there is nothing to commit, so the
     * space/enter resolution is cleared rather than computed.
     */
    private fun publishCaretWordSuggestions(engine: SuggestionEngine, caret: CaretWord) {
        val word = caret.word
        suggestionJob?.cancel()
        commitResolution = null
        val recentSnapshot = recentWords.toList()
        suggestionJob = serviceScope.launch {
            val results = withContext(Dispatchers.Default) {
                engine.suggest(
                    composing = word,
                    previousWord = previousWord,
                    previousWord2 = previousWord2,
                    recentWords = recentSnapshot,
                    allowRerank = true,
                ).filterNot { it.equals(word, ignoreCase = true) }
            }
            _uiState.update {
                it.copy(
                    suggestions = results,
                    emojiSuggestions = emptyList(),
                    punctuationSuggestions = emptyList(),
                    inlineEmoji = false,
                    joinSuggestion = null,
                    revisionSuggestion = null,
                    correctionOffer = null,
                )
            }
        }
    }

    /**
     * A quick-punctuation chip in the suggestion strip was tapped. Routed
     * through the ordinary text path so it is indistinguishable from typing
     * that punctuation key — the composing word commits, auto-capitalise and
     * pending auto-space fire, and contextual-vowel handling all apply.
     */
    fun onPunctuationSuggestionTapped(mark: String) {
        onText(mark)
    }

    /**
     * How deep the expanded grid goes. Well under the composers' own lookup
     * depth, so every candidate it shows can still be resolved back to the input
     * length it consumes.
     */
    private val CANDIDATE_GRID_LIMIT = 100

    /**
     * A conversion candidate tapped in the strip or the expanded grid. Resolved
     * by position rather than by text — see [Composer.consumedForIndex].
     */
    fun onCandidateTapped(candidate: String, index: Int) {
        val ic = currentInputConnection ?: return
        if (!_uiState.value.composer.isConversion) {
            onSuggestionTapped(candidate)
            return
        }
        commitConversionPrefix(ic, candidate, index)
    }

    /** The candidate strip's chevron: opens the overflow grid, or closes it. */
    fun onCandidatesExpand() {
        val state = _uiState.value
        if (state.panel == PanelMode.CANDIDATES) {
            _uiState.update { it.copy(panel = PanelMode.NONE, expandedCandidates = emptyList()) }
            return
        }
        if (!state.composer.isConversion || composing.isEmpty()) return
        _uiState.update {
            it.copy(
                panel = PanelMode.CANDIDATES,
                expandedCandidates = state.composer.candidates(composing.toString(), CANDIDATE_GRID_LIMIT),
            )
        }
    }

    fun onSuggestionTapped(suggestion: String) {
        stopVoiceForManualInput()
        vibrate()
        val ic = currentInputConnection ?: return
        // Email-field completion: no composing region backs the tapped address,
        // so the partial token the user typed is removed by hand before the full
        // address is committed. Not learned — an address is not a dictionary word,
        // and no trailing space, since an email is usually the whole field.
        if (emailFieldForceActive() && '@' in suggestion) {
            // A transliterating composer (Avro) composes even in email fields;
            // finish it so the token delete counts real committed text and no
            // stale buffer survives the commit below.
            if (composing.isNotEmpty()) {
                ic.finishComposingText()
                composing = StringBuilder()
            }
            val token = emailTokenBeforeCursor(ic)
            if (token.isNotEmpty()) ic.deleteSurroundingText(token.length, 0)
            ic.commitText(suggestion, 1)
            lastGestureWord = null
            lastRevertible = null
            clearSwapOffer()
            _uiState.update {
                it.copy(
                    composingPreview = "",
                    suggestions = emptyList(),
                    emojiSuggestions = emptyList(),
                )
            }
            return
        }
        // A caret sitting inside a word: the pick replaces that word where it
        // is rather than landing at the caret and splitting it in two (#32).
        // No composing region backs it (see [caretWord]), so the span is spliced
        // by hand — and re-read first, since the chip may have outlived the word
        // it was about. When it has, the tap does nothing: the strip was talking
        // about text that is no longer there, and committing the word at the
        // caret instead would be a worse guess than none.
        caretWord?.let { caret ->
            clearCaretWord()
            val head = caret.head
            val tail = caret.tail
            val stillThere =
                ic.getTextBeforeCursor(head.length, 0)?.toString().orEmpty() == head &&
                    ic.getTextAfterCursor(tail.length, 0)?.toString().orEmpty() == tail
            if (!stillThere) {
                refreshSuggestions()
                return
            }
            // Cased like the chip the user is looking at, the same as a pick
            // that lands at the caret.
            val replacement = displayCaseForShift(suggestion, _uiState.value.shiftState)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(head.length, tail.length)
            ic.commitText(replacement, 1)
            ic.endBatchEdit()
            invalidateExpectedSelection()
            recordStat { onWordsCommitted(1, System.currentTimeMillis()) }
            consumeShift()
            // Deliberately picked, so it is learned like any other pick — the
            // base word, never the shift-cased form, and spelled the way the
            // engine offered it.
            learn(suggestion, reinforcement = 2, caseTrusted = true)
            lastRevertible = null
            clearSwapOffer()
            _uiState.update {
                it.copy(suggestions = emptyList(), emojiSuggestions = emptyList())
            }
            return
        }
        // After a swipe, the alternates replace the committed gesture word —
        // together with the space the swipe typed after it, so the trailing
        // space below lands in the same place rather than after a gap.
        val gestureWord = lastGestureWord
        if (composing.isEmpty() && gestureWord != null) {
            val len = glideCommitLength(ic, gestureWord)
            if (len > 0) {
                ic.deleteSurroundingText(len, 0)
                // The bigram for the pick below must chain off the word
                // *before* the replaced one, not off the word being replaced.
                syncPreviousWordFromField(ic)
                // The decode the user just overruled is not evidence of
                // anything. Explicit rather than left to the caret rule: the
                // replacement lands at the same place the old word ended, so
                // no caret move says it happened.
                learningBuffer.drop(gestureWord)
            }
        }
        lastGestureWord = null
        lastRevertible = null
        clearSwapOffer()
        pendingWordSpace = false

        // An emoji picked from inline search replaces the ":query" buffer
        // outright: no trailing space (emoji rarely start a new word) and
        // nothing learned, since the emoji is not a word the user typed.
        if (inlineEmojiQuery() != null) {
            ic.commitText(suggestion, 1)
            composing = StringBuilder()
            _uiState.update {
                it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList())
            }
            return
        }

        // Conversion IMEs (Chinese Pinyin, Japanese kana→kanji): the tapped chip
        // is a character choice for the current reading. Commit it with no
        // trailing space (CJK runs words together) and no learning, then clear the
        // reading buffer so the next syllable starts fresh.
        if (_uiState.value.composer.isConversion) {
            commitConversionPrefix(ic, suggestion)
            return
        }

        // Normally the pick lands at the end of the text and earns a trailing
        // space to start the next word. But a word resumed mid-sentence (the
        // caret moved back onto it) already has a space after it — appending
        // another would leave a double gap, so skip it when one is there. The
        // trailing space itself is opt-out (A26): off commits the word bare.
        val autoSpace = _uiState.value.settings.suggestionStrip.autoSpaceAfterSuggestion
        val tail = if (autoSpace && !spacedAfterCaret(ic.getTextAfterCursor(1, 0))) " " else ""
        // Commit in the case the strip is showing: a shift held over the strip
        // capitalizes the word the user is about to pick, matching the chip.
        val committed = displayCaseForShift(suggestion, _uiState.value.shiftState)
        ic.commitText(committed + tail, 1)
        // That space is the keyboard's, so a mark typed next takes it back and
        // hugs the word — "word:" and not "word :" (issue #34). Same one-shot a
        // glide's space gets, and it needs the same guard: without it the
        // commit's own selection echo reads as a caret move and disarms it
        // before the next keystroke can spend it.
        if (tail.isNotEmpty()) {
            pendingWordSpace = true
            armRevertGuard()
        }
        // Whole words landed without being typed out; this also disarms the
        // half-typed word so the next separator cannot count it again.
        recordStat { onWordsCommitted(suggestion.split(' ').size, System.currentTimeMillis()) }
        // A one-shot shift is spent by the pick, the same as by a typed letter.
        consumeShift()
        // Deliberately picked from the strip — a stronger signal than a
        // word that merely got committed. Learn the base word, not the
        // shift-cased form, so caps lock never teaches "HELLO" to the lexicon.
        // A contact email is the exception: it is never learned, so it stays
        // memory-only and off the disk-backed personal dictionary even when
        // tapped from an ordinary text field.
        if ('@' !in suggestion) learn(suggestion, reinforcement = 2, caseTrusted = true)
        composing = StringBuilder()
        _uiState.update { it.copy(composingPreview = "", suggestions = emptyList(), emojiSuggestions = emptyList()) }
        maybeAutoCapitalize()
        refreshSuggestions()
    }

    /**
     * Spacebar drag: move the cursor one position left (-1) or right (+1). The
     * volume keys land here too when they are set to move the caret.
     *
     * With selection mode on — or a shift the user put up, lock included — the
     * step carries shift and drags the selection out instead, which is the
     * pairing the mode exists for: one finger holds the mode (or a tap left it
     * on) and the other scrubs the spacebar.
     */
    fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return
        vibrate()
        // Mark the scrub so the caret's landing spot doesn't resume-compose the
        // word under it mid-drag (this same commit would then churn it).
        lastCaretScrubMs = SystemClock.uptimeMillis()
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        sendEditorKey(
            if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
            shift = _uiState.value.caretExtendsSelection,
        )
    }

    /**
     * 2-D spacebar touchpad: move the cursor one line up (-1) or down (+1).
     * Mirrors [onCursorMove] but on the vertical axis, selection mode and a held
     * shift included.
     */
    fun onCursorMoveVertical(delta: Int) {
        val ic = currentInputConnection ?: return
        vibrate()
        lastCaretScrubMs = SystemClock.uptimeMillis()
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        sendEditorKey(
            if (delta < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
            shift = _uiState.value.caretExtendsSelection,
        )
    }

    // ---- gesture typing ----

    /**
     * The letter grid for this layout, rebuilt only when the grid itself
     * changes. The UI hands the same key list back for every preview within a
     * stroke, so the equality check below is an identity check in the common
     * case.
     */
    @Synchronized
    private fun keyMapFor(keys: List<KeyCenter>, keyWidthPx: Float): GlideKeyMap {
        val cached = cachedKeyMap
        if (cached != null && cachedKeyMapWidth == keyWidthPx && cachedKeyMapKeys == keys) {
            return cached
        }
        return GlideKeyMap.of(keys, keyWidthPx).also {
            cachedKeyMap = it
            cachedKeyMapKeys = keys
            cachedKeyMapWidth = keyWidthPx
        }
    }

    /** A decoded stroke: the words it could be, and whether it is a close call. */
    private class GlideReading(val words: List<String>, val ambiguous: Boolean) {
        companion object {
            val NONE = GlideReading(emptyList(), false)
        }
    }

    /**
     * How many words one stroke may come back with.
     *
     * The strip's own slot count, so a user who asked for six alternates gets
     * six rather than the four a hardcoded default used to give them (#54).
     * Floored at [GLIDE_CHOICES] so narrowing the strip to two or three slots
     * cannot starve the ambiguity picker, which shows its own three under the
     * fingertip and does not share the strip's width.
     *
     * Free: the decoder searches to `GLIDE_RERANK_POOL` whatever the caller
     * asks for, so this only decides how many survive the rerank.
     */
    private fun glideCandidateLimit(): Int =
        maxOf(_uiState.value.settings.suggestionStrip.slotCount, GLIDE_CHOICES)

    /** Decodes one stroke against the active language's word sources. */
    private fun glideDecode(
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
    ): GlideReading {
        val engine = suggestionEngine ?: return GlideReading.NONE
        val decoded = engine.glide(
            path = points,
            keys = keyMapFor(keys, keyWidthPx),
            keyWidth = keyWidthPx,
            limit = glideCandidateLimit(),
            previousWord = previousWord,
            previousWord2 = previousWord2,
            recentWords = recentWords.toList(),
        )
        if (decoded.isEmpty()) return GlideReading.NONE
        val words = decoded.map { restoreApostrophe(it.word) ?: it.word }
        return GlideReading(
            words = declareApostrophe(words, points, keys, keyWidthPx),
            ambiguous = _uiState.value.settings.gesture.ambiguityPicker &&
                engine.glideIsAmbiguous(decoded),
        )
    }

    /**
     * The other half of the apostrophe key: a stroke that went through it spells
     * a contraction even when the word list holds only the plain form.
     *
     * With the key on the grid the decoder can read "it's" straight off the trie,
     * and where the list has the entry that is what happens and this does
     * nothing. Most word lists do not have it. So when the finger demonstrably
     * passed over the apostrophe key and the best reading came back without one,
     * the declared spelling is put in front — and the plain one is kept right
     * behind it on the strip, because a rewrite the user can't undo in one tap is
     * worse than no rewrite.
     *
     * English only, like [Apostrophes] itself. Every other language reaches the
     * apostrophe the honest way, through its own word list.
     */
    private fun declareApostrophe(
        words: List<String>,
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
    ): List<String> {
        val state = _uiState.value
        if (state.settings.gesture.apostropheKey == GlideApostropheKey.OFF) return words
        if (!state.language.isEnglish) return words
        val top = words.firstOrNull() ?: return words
        if (top.contains('\'') || top.contains('’')) return words
        if (!crossedApostrophe(points, keys, keyWidthPx)) return words
        val declared = Apostrophes.fixExplicit(top) ?: return words
        return buildList(words.size + 1) {
            add(declared)
            addAll(words.filterNot { it == declared })
        }
    }

    /**
     * Whether [points] passed over the key the glide grid has put the apostrophe
     * on. Only asked once the setting is on, so the `'` entry in [keys] is that
     * key and not the long-press alternate a layout may also hide one behind.
     *
     * A half-key radius, and never the first or last sample: a word starting or
     * ending on the punctuation key is a stroke that merely began there, and the
     * words this can rewrite are all drawn on the letter rows, well away from the
     * bottom row the comma and full stop sit on.
     */
    private fun crossedApostrophe(
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
    ): Boolean {
        if (keyWidthPx <= 0f || points.size < 3) return false
        val center = keys.firstOrNull { it.char == '\'' } ?: return false
        val reach = keyWidthPx * APOSTROPHE_CROSS_WIDTHS
        for (i in 1 until points.size - 1) {
            val dx = points[i].x - center.x
            val dy = points[i].y - center.y
            if (dx * dx + dy * dy <= reach * reach) return true
        }
        return false
    }

    /**
     * "dont" swiped is "don't" committed, on the same terms as "dont" typed.
     *
     * The letters layer has no apostrophe key, so a contraction can only ever
     * be *drawn* without one — which makes this less of a correction than a
     * transcription. Deliberately applied here rather than inside the engine:
     * the setting and the language check live at this level, and the offline
     * harness decodes through the engine, where a word coming back spelled
     * differently from the one the corpus drew would read as a miss.
     */
    private fun restoreApostrophe(word: String): String? {
        val state = _uiState.value
        if (!state.settings.autoApostrophe || !state.allowsTypingIntelligence) return null
        if (!state.language.isEnglish) return null
        return Apostrophes.fix(word)
    }

    /**
     * `'s` on the end of the word just glided, drawn as a flick from the
     * apostrophe key to `s`. True when this stroke was that flick and the
     * possessive has been committed, so the caller skips the decode entirely.
     *
     * Nothing else in the gesture path can express this: a possessive is not a
     * word a stroke can spell (the finger would have to draw the whole stem
     * again), and the apostrophe key on its own puts an apostrophe *inside* a
     * word rather than after one.
     *
     * Requires a word from the immediately preceding glide. [lastGestureWord] is
     * exactly that flag — every manual edit, caret move and typed key clears it —
     * so a flick with nothing behind it falls through and decodes as the two-key
     * stroke it is.
     */
    private fun appendPossessive(
        state: KeyboardUiState,
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
    ): Boolean {
        val gesture = state.settings.gesture
        if (!gesture.apostropheS) return false
        // The spacebar is not a starting point: a stroke off it is how a glide is
        // split, and OFF has no apostrophe key at all.
        if (gesture.apostropheKey == GlideApostropheKey.OFF ||
            gesture.apostropheKey == GlideApostropheKey.SPACE
        ) {
            return false
        }
        // `'s` is English. Every other language forms its possessive elsewhere.
        if (!state.language.isEnglish) return false
        val word = lastGestureWord ?: return false
        if (word.endsWith("'s") || word.endsWith("’s")) return false
        if (!isPossessiveFlick(points, keys, keyWidthPx)) return false
        val ic = currentInputConnection ?: return false

        // The glide's own trailing space belongs to the finished word, and the
        // possessive goes inside it. Only the keyboard's own space is taken back:
        // one the user typed is theirs.
        if (pendingWordSpace) {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (before == " ") ic.deleteSurroundingText(1, 0)
            pendingWordSpace = false
        }
        ic.commitText(POSSESSIVE, 1)
        val possessive = word + POSSESSIVE
        // Backspace still takes the whole thing back in one press, stem included,
        // which is what the flick built.
        lastGestureWord = possessive
        learn(possessive)
        commitGestureSpace(ic, state)
        armRevertGuard()
        return true
    }

    private fun isPossessiveFlick(
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
    ): Boolean {
        val from = keys.firstOrNull { it.char == '\'' } ?: return false
        val to = keys.firstOrNull { it.char == 's' } ?: return false
        return possessiveFlick(points, from, to, keyWidthPx)
    }

    /**
     * Whether a glide may run right now. During a typing test the test's own
     * switch decides, not the keyboard's gesture setting or the field: the
     * test exists to compare tapping against gliding, and the word never
     * reaches the field behind the panel, so the field's say does not apply.
     * Readiness — a word list, a letter layout, enough keys — applies always.
     */
    private fun glideAllowed(state: KeyboardUiState): Boolean = when {
        !state.glideReady -> false
        state.typingTestActive -> state.settings.typingTest.glide
        else -> state.settings.gestureTyping && state.allowsGestureTyping
    }

    /**
     * What decides whether the active language and layout can be glided. A
     * value class rather than three fields so the watcher below is one
     * `distinctUntilChanged` rather than a hand-rolled comparison that would
     * quietly stop noticing a new input the day one gets added.
     */
    private data class GlideGate(
        val languageId: String,
        /** The layout turns keystrokes into something other than the letters
         * pressed — Avro, Hangul, Vietnamese, every CJK method. A stroke over
         * such a grid spells a reading, not a word. */
        val converts: Boolean,
        /** Avro: it converts, but into a script the keyboard has a romanization
         * for, so the reading a stroke spells can be turned back into words. */
        val phonetic: Boolean,
        val alphabet: Set<Char>,
        /** Bumped when the word sources change under us, so a finished
         * dictionary download re-asks the coverage question. */
        val sources: Int,
    )

    /**
     * Keeps [KeyboardUiState.glideReady] in step with the language, the layout
     * and the word lists.
     *
     * The three conditions it enforces replace two older ones that disagreed
     * with each other: a `gestureLexicon` flag set on exactly one language, and
     * an `isEnglish` check in each of the three gesture handlers. Neither was
     * about anything real — glide works wherever there is a word list, a layout
     * that types letters rather than converting them, and enough keys to spell
     * the language.
     */
    private fun startGlideReadinessWatcher() {
        serviceScope.launch {
            combine(_uiState, glideSourcesEpoch) { it, epoch ->
                GlideGate(
                    languageId = it.language.id,
                    converts = it.composer.isTransliterating || it.composer.isConversion,
                    phonetic = it.composer.isBengaliPhonetic,
                    alphabet = it.layouts.letterAlphabet,
                    sources = epoch,
                )
            }
            .distinctUntilChanged()
                .collect { gate ->
                    // Handed over before coverage is asked, because on a
                    // phonetic layout coverage is a question about the
                    // romanization rather than about the Bengali word list.
                    val engine = suggestionEngine
                    engine?.glideRomanization =
                        if (gate.phonetic) romanizedGlide else RomanizedIndex.EMPTY
                    val allowed = gate.phonetic || !gate.converts
                    val ready = allowed && engine != null && withContext(Dispatchers.Default) {
                        engine.glideCoverage(gate.alphabet) >= GlideCoverage.THRESHOLD
                    }
                    _uiState.update { if (it.glideReady == ready) it else it.copy(glideReady = ready) }
                }
        }
    }

    /** Mid-swipe: show the current best candidates without committing. */
    fun onGesturePreview(points: List<GesturePoint>, keys: List<KeyCenter>, keyWidthPx: Float) {
        val state = _uiState.value
        if (!glideAllowed(state)) return
        if (keys.isEmpty()) return
        gesturePreviews.trySend(
            GesturePreviewRequest(points, keys, keyWidthPx, gestureGeneration.get()),
        )
    }

    /**
     * Drains [gesturePreviews] for the life of the service. One coroutine
     * instead of a cancel-and-relaunch per preview, and the generation check is
     * what the cancel used to buy: a preview decoded before the swipe committed
     * must not put its own candidates back on the strip afterwards.
     */
    private fun startGesturePreviewConsumer() {
        serviceScope.launch {
            for (request in gesturePreviews) {
                if (request.generation != gestureGeneration.get()) continue
                val reading = withContext(Dispatchers.Default) {
                    // Same sources as the final decode, so the previewed word
                    // never differs from the one that commits on finger-up.
                    glideDecode(request.points, request.keys, request.keyWidthPx)
                }
                if (reading.words.isEmpty()) continue
                // Re-checked after the decode: the finger may have lifted and
                // the word committed while this was running.
                if (request.generation != gestureGeneration.get()) continue
                _uiState.update {
                    it.copy(
                        suggestions = reading.words,
                        glideWord = reading.words.first(),
                        // Only while the stroke is still in doubt: publishing
                        // choices for a confident decode would arm the picker
                        // for a stroke that has nothing to ask.
                        glideChoices = if (reading.ambiguous) {
                            reading.words.take(GLIDE_CHOICES)
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }
    }

    /**
     * Decodes a swipe drawn over the letter keys and commits the best word.
     * Alternates go to the suggestion bar; tapping one replaces the word.
     */
    fun onGesture(
        points: List<GesturePoint>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
        /**
         * The word the user picked out of the ambiguity picker, if they did.
         * Committed in place of the decoder's own first choice, and otherwise
         * treated identically — it is still learned, still spaced, still
         * revertible, and its alternates still reach the strip.
         */
        chosen: String? = null,
    ) {
        stopVoiceForManualInput()
        val state = _uiState.value
        if (!glideAllowed(state)) return
        if (keys.isEmpty()) return
        // A test run takes the word: the field behind the panel never sees it.
        if (state.typingTestActive) {
            typingTestGlide(listOf(points), keys, keyWidthPx, chosen)
            return
        }

        val shiftAtGesture = state.shiftState
        // Retires every preview from this stroke, in flight or queued.
        gestureGeneration.incrementAndGet()
        suggestionJob?.cancel()
        gestureJob?.cancel()
        _uiState.update { it.copy(glideWord = null, glideChoices = emptyList()) }
        // A flick from the apostrophe key to s is not a word: it is `'s` for the
        // word already committed. Answered before the decode rather than after,
        // because there is nothing to decode and no candidate to override.
        if (appendPossessive(state, points, keys, keyWidthPx)) return
        gestureJob = serviceScope.launch {
            val candidates = withContext(Dispatchers.Default) {
                glideDecode(points, keys, keyWidthPx)
            }.words
            // Debug builds only: typed content must never be logged in release.
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.util.Log.d(
                    "WMKeyboard",
                    "gesture shift=$shiftAtGesture candidates=$candidates",
                )
            }
            if (candidates.isEmpty()) return@launch
            val ic = currentInputConnection ?: return@launch

            // The tapped word this glide is finishing gets the same treatment
            // a space would have given it: without this a tapped "i" followed
            // by a glided word committed in lower case, because the glide's own
            // space never goes through onSpace (#46).
            commitComposing(ic, autocorrect = false, fixApostrophes = state.settings.autoApostrophe)
            val picked = chosen?.takeIf { it in candidates } ?: candidates.first()
            val word = when (shiftAtGesture) {
                ShiftState.CAPS_LOCK -> picked.uppercase()
                ShiftState.ON -> picked.replaceFirstChar { it.uppercase() }
                ShiftState.OFF -> picked
            }
            // Auto-space between consecutive swiped words.
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            if (before.isNotEmpty() && !before.last().isWhitespace()) {
                ic.commitText(" ", 1)
            }
            ic.commitText(word, 1)
            recordStat { onWordsCommitted(1, System.currentTimeMillis()) }
            // Caps lock never teaches a spelling — it says the letters are
            // upper case, not that the word is — and neither does the shift
            // auto-capitalize armed at a sentence start.
            learn(
                word,
                caseTrusted = shiftAtGesture == ShiftState.OFF ||
                    (shiftAtGesture == ShiftState.ON && state.shiftPressedByUser),
            )
            lastGestureWord = word
            commitGestureSpace(ic, state)
            armRevertGuard()
            consumeShift()
            _uiState.update { it.copy(suggestions = candidates) }
        }
    }

    /**
     * Types the space that follows a glided word, so the next word — glided or
     * tapped — does not run into it. Skipped when the text already continues
     * with one: a word glided back into the middle of a sentence has a space
     * after it already, and a second would leave a double gap. A line break
     * after the caret is not one of those — see [spacedAfterCaret].
     *
     * Arms [pendingWordSpace], which is what lets punctuation typed straight
     * afterwards take the space back and a space press be swallowed rather than
     * doubled.
     */
    private fun commitGestureSpace(ic: InputConnection, state: KeyboardUiState) {
        if (!state.settings.gesture.autoSpaceAfterGlide) return
        if (spacedAfterCaret(ic.getTextAfterCursor(1, 0))) return
        ic.commitText(" ", 1)
        pendingWordSpace = true
    }

    /**
     * How many characters the last glided [word] occupies right behind the
     * caret, space included — see the top-level [glideCommitLength] for what
     * the measurement means and why both callers need it.
     */
    private fun glideCommitLength(ic: InputConnection, word: String): Int =
        glideCommitLength(
            ic.getTextBeforeCursor(word.length + 1, 0)?.toString().orEmpty(),
            word,
        )

    /**
     * Multi-word glide: one continuous stroke that crossed the spacebar was
     * split into a word segment per crossing. Decodes and commits them in
     * order, spacing between them, so the whole phrase lands from one swipe.
     * Only the first word honours a held shift; the alternates of the last
     * word go to the suggestion bar, so tapping one fixes the final word — the
     * same as a single glide.
     */
    fun onGestureWords(segments: List<List<GesturePoint>>, keys: List<KeyCenter>, keyWidthPx: Float) {
        stopVoiceForManualInput()
        val state = _uiState.value
        if (!glideAllowed(state)) return
        if (keys.isEmpty() || segments.isEmpty()) return
        // A test run takes the words: the field behind the panel never sees them.
        if (state.typingTestActive) {
            typingTestGlide(segments, keys, keyWidthPx, chosen = null)
            return
        }

        val shiftAtGesture = state.shiftState
        // Retires every preview from this stroke, in flight or queued.
        gestureGeneration.incrementAndGet()
        suggestionJob?.cancel()
        gestureJob?.cancel()
        _uiState.update { it.copy(glideWord = null, glideChoices = emptyList()) }
        gestureJob = serviceScope.launch {
            val ic = currentInputConnection ?: return@launch
            // Flush any composing text before the first glided word, finished
            // the way a space would have finished it (see onGesture).
            commitComposing(ic, autocorrect = false, fixApostrophes = state.settings.autoApostrophe)
            var lastWords: List<String> = emptyList()
            var committedAny = false
            segments.forEachIndexed { index, segment ->
                // Decoded inside the loop, not before it: each word is committed
                // and learned as it lands, so the next segment is decoded with
                // the one before it as context.
                val candidates = withContext(Dispatchers.Default) {
                    glideDecode(segment, keys, keyWidthPx)
                }.words
                if (candidates.isEmpty()) return@forEachIndexed
                val word = if (index == 0) {
                    when (shiftAtGesture) {
                        ShiftState.CAPS_LOCK -> candidates.first().uppercase()
                        ShiftState.ON -> candidates.first().replaceFirstChar { it.uppercase() }
                        ShiftState.OFF -> candidates.first()
                    }
                } else {
                    candidates.first()
                }
                // Auto-space between consecutive words.
                val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                if (before.isNotEmpty() && !before.last().isWhitespace()) {
                    ic.commitText(" ", 1)
                }
                ic.commitText(word, 1)
                recordStat { onWordsCommitted(1, System.currentTimeMillis()) }
                learn(
                    word,
                    caseTrusted = index > 0 || shiftAtGesture == ShiftState.OFF ||
                        (shiftAtGesture == ShiftState.ON && state.shiftPressedByUser),
                )
                lastGestureWord = word
                armRevertGuard()
                lastWords = candidates
                committedAny = true
            }
            if (committedAny) {
                // Only the last word earns one: the words before it were spaced
                // by the leading rule above as each new segment landed.
                commitGestureSpace(ic, state)
                armRevertGuard()
                consumeShift()
                _uiState.update { it.copy(suggestions = lastWords) }
            }
        }
    }

    // ---- panels ----

    /**
     * A tool the user asked for from the toolbar: the strip's button, the
     * toolbox cell, and the physical keyboard's picker all land here, so a
     * shortcut can never drift from what the button does.
     *
     * Most tools open a panel; the rest are one-shot actions with no panel of
     * their own (a toggle, a cursor move, the settings app).
     */
    fun onToolTap(tool: ToolbarTool) {
        // The toolbar never renders a tool the user has switched off, but a
        // shortcut can still name one: a stored binding outlives the tool
        // leaving the strip. The device-level tests are in [runTool], which the
        // key-bound path ([runToolFromKey]) reaches without this one.
        if (tool !in _uiState.value.settings.enabledTools) return
        runTool(tool)
    }

    /**
     * A tool fired by something that is not the toolbar: a key bound to
     * [KeyAction.Tool], or one of its long-press alternates.
     *
     * Skips the "is it on the toolbar" test [onToolTap] makes, and only that
     * one. A key an author put on their layout is its own reason for the tool to
     * run — the toolbar's set is about what the strip shows, and a layout that
     * had to keep a tool on the strip to reach it from a key would be paying
     * twice for the same thing. Everything the *device* decides still applies:
     * a lite build and an unusable tool both come out as a key that does
     * nothing, exactly as they do on the toolbar.
     */
    private fun runToolFromKey(tool: ToolbarTool) {
        runTool(tool)
        ensureInputViewShown()
        seedPanelFocus()
    }

    /**
     * A tool fired by a press and hold on a toolbar button the user bound it to.
     *
     * Skips [onToolTap]'s "is it on the toolbar" test for the reason
     * [runToolFromKey] does: the binding is its own reason for the tool to run.
     * It used to go through [onToolTap], so a hold bound to any tool the user
     * had not also put on the bar — Select line, say, on a fresh install whose
     * setup left most tools off — was dropped without a word (#31). The device's
     * own gates in [runTool] still apply.
     */
    private fun runToolFromHold(tool: ToolbarTool) {
        runTool(tool)
    }

    /**
     * The dispatch itself, once the caller's own gating has passed, plus the two
     * tests every caller shares: a lite build ships fewer tools than the enum
     * lists, and a search tool loses its key the moment it is cleared.
     */
    private fun runTool(tool: ToolbarTool) {
        if (!isSupportedTool(tool)) return
        val settings = _uiState.value.settings
        if (!isUsableTool(tool, settings)) return
        when (tool) {
            ToolbarTool.EMOJI -> onPanelChange(PanelMode.EMOJI)
            ToolbarTool.CLIPBOARD -> {
                if (isClipboardAccessible()) onPanelChange(PanelMode.CLIPBOARD)
            }

            ToolbarTool.SNIPPETS -> onPanelChange(PanelMode.SNIPPETS)
            ToolbarTool.TEXT_EDIT -> onPanelChange(PanelMode.TEXT_EDIT)
            ToolbarTool.TRACKPAD -> onPanelChange(PanelMode.TRACKPAD)
            ToolbarTool.SETTINGS -> openSettings()
            ToolbarTool.ONE_HANDED -> onOneHandedChange(
                if (settings.oneHandedMode == OneHandedMode.OFF) {
                    // Enable on this orientation's preferred side.
                    val landscape =
                        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    settings.oneHanded.forLandscape(landscape).side.toMode()
                } else OneHandedMode.OFF
            )
            ToolbarTool.SPLIT -> onToggleSplit()
            ToolbarTool.FLOATING -> onFloatingChange(!settings.floatingKeyboard)
            ToolbarTool.RESIZE -> onResizeToggle()
            ToolbarTool.FLASHLIGHT -> onFlashlightToggle()
            ToolbarTool.COMPASS -> onPanelChange(PanelMode.COMPASS)
            ToolbarTool.LEVEL -> onPanelChange(PanelMode.LEVEL)
            ToolbarTool.UNDO -> onUndoRedo(false)
            ToolbarTool.REDO -> onUndoRedo(true)
            ToolbarTool.MOON_PHASE -> onPanelChange(PanelMode.MOON_PHASE)
            ToolbarTool.WEATHER -> onPanelChange(PanelMode.WEATHER)
            ToolbarTool.CALENDAR -> onPanelChange(PanelMode.CALENDAR)
            ToolbarTool.INCOGNITO -> onIncognitoToggle()
            ToolbarTool.POWER_SAVING -> onPowerSavingToggle()
            ToolbarTool.THEMES -> onPanelChange(PanelMode.THEMES)
            ToolbarTool.AUTOCORRECT -> onAutocorrectToggle()
            ToolbarTool.FANCY -> onFancyToggle()
            ToolbarTool.CUSTOM_LAYOUT -> onCustomLayoutToggle()
            ToolbarTool.SOUND_HAPTICS -> onPanelChange(PanelMode.SOUND_HAPTICS)
            ToolbarTool.NUMPAD -> onPanelChange(PanelMode.NUMPAD)
            ToolbarTool.HANDWRITING -> onPanelChange(PanelMode.HANDWRITING)
            ToolbarTool.CAMERA -> onPanelChange(PanelMode.CAMERA)
            ToolbarTool.DICTIONARY -> onPanelChange(PanelMode.DICTIONARY)
            ToolbarTool.TRANSLATE -> onPanelChange(PanelMode.TRANSLATE)
            ToolbarTool.GIF -> onPanelChange(PanelMode.GIF)
            ToolbarTool.STICKER -> onPanelChange(PanelMode.STICKER)
            ToolbarTool.WEB_SEARCH -> onPanelChange(PanelMode.WEB_SEARCH)
            ToolbarTool.IMAGE_SEARCH -> onPanelChange(PanelMode.IMAGE_SEARCH)
            ToolbarTool.OCR -> onPanelChange(PanelMode.OCR)
            ToolbarTool.QR_SCAN -> onPanelChange(PanelMode.QR_SCAN)
            // Not a panel: the scanner is a full-screen Google activity.
            ToolbarTool.DOC_SCAN -> onDocScanStart()
            ToolbarTool.VOICE -> onPanelChange(PanelMode.VOICE)
            ToolbarTool.GRAMMAR -> onPanelChange(PanelMode.GRAMMAR)
            ToolbarTool.WIKIPEDIA -> onPanelChange(PanelMode.WIKIPEDIA)
            ToolbarTool.SYMBOLS -> onPanelChange(PanelMode.SYMBOLS)
            ToolbarTool.CALCULATOR -> onPanelChange(PanelMode.CALCULATOR)
            ToolbarTool.UNIT_CONVERT -> onPanelChange(PanelMode.UNIT_CONVERT)
            ToolbarTool.CURRENCY -> onPanelChange(PanelMode.CURRENCY)
            ToolbarTool.QR_GEN -> onPanelChange(PanelMode.QR_GEN)
            ToolbarTool.PASSWORD_GEN -> onPanelChange(PanelMode.PASSWORD_GEN)
            ToolbarTool.TYPING_TEST -> onPanelChange(PanelMode.TYPING_TEST)
            ToolbarTool.MEDIA_CONTROL -> onPanelChange(PanelMode.MEDIA_CONTROL)
            ToolbarTool.PLUGINS -> onPanelChange(PanelMode.PLUGINS)
            ToolbarTool.APP_LAUNCHER -> onPanelChange(PanelMode.APP_LAUNCHER)
            ToolbarTool.AI -> onPanelChange(PanelMode.AI)
            ToolbarTool.MODES -> onPanelChange(PanelMode.MODES)
            // Same moves the text-editing panel offers, one tap deep instead
            // of two. Selection still extends when the panel's select mode is
            // on, since onTextEdit reads that state itself — and also while
            // shift is up, which [onCursorTool] folds in.
            ToolbarTool.CURSOR_LEFT -> onCursorTool(TextEditAction.LEFT)
            ToolbarTool.CURSOR_RIGHT -> onCursorTool(TextEditAction.RIGHT)
            ToolbarTool.CURSOR_WORD_LEFT -> onCursorTool(TextEditAction.WORD_LEFT)
            ToolbarTool.CURSOR_WORD_RIGHT -> onCursorTool(TextEditAction.WORD_RIGHT)
            ToolbarTool.CURSOR_UP -> onCursorTool(TextEditAction.UP)
            ToolbarTool.CURSOR_DOWN -> onCursorTool(TextEditAction.DOWN)
            ToolbarTool.CURSOR_HOME -> onCursorTool(TextEditAction.HOME)
            ToolbarTool.CURSOR_END -> onCursorTool(TextEditAction.END)
            ToolbarTool.PAGE_UP -> onCursorTool(TextEditAction.PAGE_UP)
            ToolbarTool.PAGE_DOWN -> onCursorTool(TextEditAction.PAGE_DOWN)
            ToolbarTool.SELECT_WORD -> onTextEdit(TextEditAction.SELECT_WORD)
            ToolbarTool.SELECT_LINE -> onTextEdit(TextEditAction.SELECT_LINE)
            ToolbarTool.SELECT_MODE -> onSelectModeTap()
            // The same three operations the text-edit panel's keys run, so the
            // selection bookkeeping (copy and cut end select mode, paste checks
            // the clipboard is readable) is the one implementation (issue #41).
            ToolbarTool.COPY -> onTextEdit(TextEditAction.COPY)
            ToolbarTool.CUT -> onTextEdit(TextEditAction.CUT)
            ToolbarTool.PASTE -> onTextEdit(TextEditAction.PASTE)
            ToolbarTool.HIDE_KEYBOARD -> onHideKeyboard()
        }
    }

    /**
     * Edits an open panel's search buffer. Every query change drops the hardware
     * focus ring: once the results are filtered differently, index 3 is a
     * different item, and Enter on a stale ring would insert something the user
     * never looked at. Cheaper and safer than trying to follow an item across a
     * refiltered list.
     *
     * Typing also ends a category: the grid is about to answer the query, not
     * the chip, so no chip should keep reading as the selected one.
     */
    private inline fun updateQuery(crossinline block: (KeyboardUiState) -> KeyboardUiState) {
        _uiState.update { block(it).copy(panelFocus = null, mediaCategory = null) }
    }

    /**
     * Opens (or closes) a panel. [haptic] is false when a key on the grid did
     * it: the UI's pointer-down callback already buzzed for that press, and a
     * second buzz on release read as one long double tick on the emoji key.
     * The toolbar, the chrome shortcut and the hardware shortcuts have no
     * press-time feedback of their own, so they keep it.
     */
    fun onPanelChange(panel: PanelMode, haptic: Boolean = true) {
        if (panel == PanelMode.CLIPBOARD && !isClipboardAccessible()) return
        if (rerouteVoiceTool(panel)) return
        dismissVoiceSurfacesFor(panel)
        // Panels have their own key semantics — the panel would eat the
        // modified key — so a pending latch does not survive opening one.
        clearModifiers()
        // Same for a half-typed braille chord or morse sequence: the panel
        // takes the keys out from under it.
        resetChordInputs()
        if (haptic) vibrate()
        // The settings app edits snippets in the same file; re-read on open.
        if (panel == PanelMode.SNIPPETS) reloadSnippetsIfChanged()
        // Same for sticker packs, which the settings app owns outright.
        if (panel == PanelMode.STICKER) stickerPackStore.reloadIfChanged()
        // Same again: the Storage screen can delete the history out from under
        // this list, and saving a stale one would undo that.
        if (panel == PanelMode.CLIPBOARD) {
            clipboardStore.reload()
            fetchLinkPreviews()
        }
        // Whatever the GIF/sticker panel was showing survives it closing.
        stashMediaSearch(_uiState.value)
        _uiState.update {
            val closing = it.panel == panel
            val next = if (closing) PanelMode.NONE else panel
            val restored = savedMediaSearch(next)
            it.copy(
                panel = next,
                // The strip is hidden behind the panel; a stale chip would
                // reappear on close pointing at text that has since moved.
                smart = null,
                // A ring indexed into the panel being left would point at
                // whatever happens to sit at that index in the new one; the
                // keyboard path re-seeds it after this (see [openToolByKey]).
                panelFocus = null,
                // Arming is spent the moment a tool opens, however it opened.
                toolPicker = null,
                textEditSelecting = false,
                emojiSearchActive = false,
                emojiQuery = "",
                emojiResults = emptyList(),
                clipboardItems = clipboardStore.items(),
                snippets = snippetStore.items(),
                snippetFolders = snippetStore.folders(),
                snippetCandidateCounts = snippetCandidateCounts(),
                // Every open of the snippets panel starts at the folders. A
                // panel that reopened three levels into where it was last week
                // is a panel that looks broken. Same for a held tile's list.
                snippetFolderOpen = null,
                snippetPicker = null,
                // The strip is behind the panel, so its chips go with it for
                // the reason [smart] does.
                snippetOffers = null,
                dictionarySearchActive = false,
                clipboardSearchActive = false,
                clipboardQuery = "",
                // GIF/sticker reopen on their last search — unless a
                // celebration chip staged one, which outranks it; everything
                // else starts blank. A Wikipedia lookup chip seeds the search
                // box the same way.
                mediaQuery = when (next) {
                    PanelMode.GIF, PanelMode.STICKER ->
                        (it.toolPrefill as? ToolPrefill.Gif)?.query ?: restored.query
                    PanelMode.WIKIPEDIA ->
                        (it.toolPrefill as? ToolPrefill.Lookup)?.term ?: restored.query
                    else -> restored.query
                },
                // Web/image search and translate open straight into their
                // search box (there is nothing to show yet); gif/sticker
                // open on their previous search, or trending. Wikipedia
                // keeps a previous article/results if it has one.
                mediaSearchActive = next == PanelMode.WEB_SEARCH || next == PanelMode.IMAGE_SEARCH ||
                    next == PanelMode.TRANSLATE || next == PanelMode.QR_GEN ||
                    (next == PanelMode.WIKIPEDIA && it.wiki !is WikiUi.Article && it.wiki !is WikiUi.SearchResults),
                mediaDownloadingId = null,
                mediaDownloadProgress = null,
                stickerPacks = stickerPackStore.packs(),
                stickerPackId = null,
                // The service owns these buffers now, so the seed happens here:
                // the calculator consumes its chip prefill outright, while the
                // converters take only the amount — the panel still reads the
                // category and unit pair from the prefill it consumes itself.
                calcExpression = if (next == PanelMode.CALCULATOR) {
                    (it.toolPrefill as? ToolPrefill.Calc)?.expression.orEmpty()
                } else {
                    ""
                },
                converterValue = when (next) {
                    PanelMode.UNIT_CONVERT -> (it.toolPrefill as? ToolPrefill.Units)?.value ?: "1"
                    PanelMode.CURRENCY -> (it.toolPrefill as? ToolPrefill.Currency)?.amount ?: "1"
                    else -> "1"
                },
                toolPrefill = when (next) {
                    // Consumed above, into the expression or the search box.
                    PanelMode.CALCULATOR, PanelMode.GIF, PanelMode.STICKER -> null
                    else -> it.toolPrefill
                },
                mediaAction = null,
                mediaCategories = emptyList(),
                mediaCategory = restored.category,
                translate = TranslateUi(),
                grammar = GrammarUi(available = grammarAvailable || grammarProbePending()),
                // Leaving the Plugins panel must hand the keys straight back to
                // the field. This is the reset that guarantees it: whatever the
                // panel was routing, the next state has nowhere to route to.
                pluginFocusedInput = null,
                pluginInputs = if (next == PanelMode.PLUGINS) it.pluginInputs else emptyMap(),
                plugins = if (next == PanelMode.PLUGINS) it.plugins else PluginPanelUi.List(emptyList()),
                // The drill-down never outlives the panel: reopening the tool
                // lands on the grid, not on whatever app was open last time.
                launcherDetail = if (next == PanelMode.APP_LAUNCHER) it.launcherDetail else null,
            )
        }
        // Leaving the panel ends the plugin session outright. Not paused, not
        // backgrounded: the Globals are dropped and the thread is shut down, so
        // after this there is no plugin left in the process to receive
        // anything the user types.
        if (_uiState.value.panel != PanelMode.PLUGINS) pluginRuntime?.close()
        // Either direction is a history surface changing hands — the emoji
        // panel's grids coming up, or the emoji row it hid coming back — so
        // this is where a ranking moved by earlier taps lands.
        publishEmojiHistory()
        translateJob?.cancel()
        grammarJob?.cancel()
        mediaFetchJob?.cancel()
        mediaLiveSearchJob?.cancel()
        mediaCategoryJob?.cancel()
        when (_uiState.value.panel) {
            PanelMode.WEATHER -> refreshWeather()
            PanelMode.DICTIONARY -> {
                openDictionary()
                // A "define serendipity" chip: straight to the word.
                (_uiState.value.toolPrefill as? ToolPrefill.Lookup)?.let {
                    onDictionaryLookup(it.term)
                    onToolPrefillConsumed()
                }
            }
            PanelMode.GIF, PanelMode.STICKER -> {
                refreshMedia(_uiState.value.mediaQuery.trim())
                refreshMediaCategories()
            }
            // A "who is …" chip: the search runs as the panel opens. The
            // query is already in the search box (see the copy above).
            PanelMode.WIKIPEDIA -> (_uiState.value.toolPrefill as? ToolPrefill.Lookup)?.let {
                runWikiSearch(it.term)
                onToolPrefillConsumed()
            }
            PanelMode.WEB_SEARCH -> _uiState.update {
                it.copy(webSearch = if (hasSearchKey()) WebSearchUi.Idle else WebSearchUi.NeedKey)
            }
            PanelMode.IMAGE_SEARCH -> _uiState.update {
                it.copy(imageSearch = if (hasSearchKey()) ImageSearchUi.Idle else ImageSearchUi.NeedKey)
            }
            PanelMode.GRAMMAR -> {
                currentInputConnection?.let { commitComposing(it, autocorrect = false) }
                scheduleGrammarCheck(immediate = true)
            }
            // The coin table is only worth fetching when the pair the panel
            // opens on actually holds a coin.
            PanelMode.CURRENCY -> refreshCurrencyRates(wantCrypto = pairHasCoin())
            PanelMode.QR_GEN -> {
                currentInputConnection?.let { commitComposing(it, autocorrect = false) }
                // Seed the editable buffer with the field text as a convenience,
                // but from here the user edits it freely — the QR no longer
                // tracks the field.
                _uiState.update { it.copy(mediaQuery = extractFieldText().trim()) }
            }
            PanelMode.AI -> {
                // A half-typed word is part of what the actions would run on,
                // so it has to be in the field before the chips are judged
                // enabled or not.
                currentInputConnection?.let { commitComposing(it, autocorrect = false) }
                _uiState.update { it.copy(ai = aiInitialState(it.settings)) }
                refreshAiHasText()
            }
            PanelMode.PLUGINS -> openPluginList()
            PanelMode.APP_LAUNCHER -> loadLauncherApps()
            PanelMode.TYPING_TEST -> {
                // Flush the half-typed word first: the test swallows every
                // key from here, so a composing word would otherwise hang
                // uncommitted until the panel closed.
                currentInputConnection?.let { commitComposing(it, autocorrect = false) }
                startTypingTest()
            }
            else -> {}
        }
        if (_uiState.value.panel != PanelMode.TYPING_TEST) stopTypingTest()
        if (_uiState.value.panel == PanelMode.HANDWRITING) {
            // Flush any half-typed word so handwriting appends after it.
            currentInputConnection?.let { commitComposing(it, autocorrect = false) }
            refreshHandwritingStatus()
        } else if (hwJob != null || _uiState.value.handwriting.strokes.isNotEmpty()) {
            // Leaving the panel abandons pending ink and recognition.
            hwJob?.cancel()
            hwJob = null
            hwGeneration++
            _uiState.update {
                it.copy(handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false))
            }
        }
        if (_uiState.value.panel == PanelMode.VOICE) {
            // Opening the tool is the intent to speak: listen right away.
            startVoice()
        } else {
            cancelVoice()
        }
        syncMediaTracking()
    }

    // ---- media control ----

    /**
     * Whether a media session is being watched right now, and why.
     *
     * Two independent reasons want the tracking on: the panel being open, and
     * the toolbar's auto-pin watching for an allowlisted player to start. This
     * is the one place that decides, so neither reason can switch the other's
     * tracking off. Called from the panel switch, from both ends of the
     * keyboard's visible life, and whenever the settings change.
     */
    private fun syncMediaTracking() {
        val state = _uiState.value
        val forPanel = state.panel == PanelMode.MEDIA_CONTROL
        val forPin = state.settings.mediaControl.pinWhilePlaying &&
            ToolbarTool.MEDIA_CONTROL in state.settings.enabledTools
        // Nothing on screen is worth a session listener, panel included: the
        // panel is not being looked at either, and onStartInputView re-syncs
        // (and so rebinds) before the keyboard is shown again.
        if (!keyboardVisible || (!forPanel && !forPin)) {
            stopMedia()
            return
        }
        startMedia()
        // Settings can change under a live session — the allowlist edited, the
        // switch turned off, power saving arriving — so re-decide the pin here
        // instead of waiting for the player to do something.
        val pinned = pinsFor(_uiState.value.mediaControl)
        if (pinned != _uiState.value.mediaPinned) {
            _uiState.update { it.copy(mediaPinned = pinned) }
        }
    }

    /** Begin mirroring the active media session into [KeyboardUiState.mediaControl]. */
    private fun startMedia() {
        mediaController.start { snapshot ->
            // Decided before the update: [pinsFor] reads the *previous* frame
            // to hold the latch across a pause, so it must not see the new one.
            val pinned = pinsFor(snapshot)
            _uiState.update { it.copy(mediaControl = snapshot, mediaPinned = pinned) }
        }
    }

    /** Stop tracking and clear the now-playing snapshot. */
    private fun stopMedia() {
        mediaController.stop()
        if (_uiState.value.mediaControl != null || _uiState.value.mediaPinned) {
            _uiState.update { it.copy(mediaControl = null, mediaPinned = false) }
        }
    }

    /**
     * Whether [snapshot] should hold the media tool on the toolbar.
     *
     * Arms on playback from an app the user counts as a music player, then
     * stays armed while that same app keeps its session — so pausing to reply
     * to a message leaves the transport where the thumb last saw it, and the
     * pin only goes when the player does. A session handed to a different app
     * has to earn the pin again by playing.
     */
    private fun pinsFor(snapshot: MediaSnapshot?): Boolean {
        val settings = _uiState.value.settings
        if (!settings.mediaControl.pinWhilePlaying) return false
        if (ToolbarTool.MEDIA_CONTROL !in settings.enabledTools) return false
        if (snapshot == null) return false
        if (snapshot.packageName !in settings.mediaControl.musicApps) return false
        if (snapshot.playing) return true
        // Paused: hold the pin only for the app that armed it.
        return _uiState.value.mediaPinned &&
            _uiState.value.mediaControl?.packageName == snapshot.packageName
    }

    fun onMediaPlayPause() {
        vibrate()
        mediaController.playPause()
    }

    fun onMediaNext() {
        vibrate()
        mediaController.next()
    }

    fun onMediaPrevious() {
        vibrate()
        mediaController.previous()
    }

    fun onMediaSeek(positionMs: Long) {
        mediaController.seekTo(positionMs)
    }

    /**
     * The panel re-checks notification access when the keyboard returns to the
     * foreground; if it was just granted, this is what binds to the active
     * session — [MediaControlManager.start] refuses to do anything without it,
     * so every earlier attempt was a no-op.
     */
    fun onMediaResume() {
        syncMediaTracking()
    }

    /**
     * IMEs cannot show the grant dialog, and notification access is granted on a
     * system screen rather than by a dialog at all — so this goes through the
     * disclosure trampoline, which explains what the listener does (and does
     * not) read before opening the per-app notification-access page.
     */
    fun onMediaAccessRequest() {
        val component = ComponentName(this, MediaNotificationListener::class.java)
        runCatching {
            startActivity(
                SpecialAccessActivity.intent(this, SpecialAccess.NOTIFICATIONS, component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- voice input ----

    /** Recognition language follows the input mode, like handwriting. */
    private fun voiceLanguageTag(): String =
        _uiState.value.language.localeTag

    /** Mic button on the voice panel/strip: start, or finish the session. */
    fun onVoiceToggle() {
        vibrate()
        when (_uiState.value.voice.status) {
            VoiceStatus.LISTENING -> {
                voiceStopRequested = true
                if (whisperRecorder != null) {
                    // Whisper: stop recording and transcribe this clip.
                    finishWhisper(userStopped = true)
                } else {
                    _uiState.update { it.copy(voice = it.voice.copy(status = VoiceStatus.FINISHING)) }
                    voiceEngine.finish()
                }
            }
            // Ignore taps while finishing or transcribing — the result is coming.
            VoiceStatus.FINISHING, VoiceStatus.TRANSCRIBING -> {}
            else -> {
                voiceSilentRetries = 0
                startVoice()
            }
        }
    }

    /** IMEs cannot show permission dialogs; bounce through the trampoline. */
    fun onVoicePermissionRequest() {
        startActivity(
            Intent(this, MicPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Starts one dictation session. In block voice typing, partial results
     * stream into the editor as composing text; interactive voice typing shows
     * them on the surface only and puts nothing in the field until the phrase
     * is finished. Either way the final result commits and is learned like a
     * typed word. Dictated text arrives in final script, so the Avro
     * transliteration pipeline is bypassed entirely.
     */
    /** Whether a dictation surface is still up to receive the next utterance. */
    private fun voiceSessionAlive(): Boolean =
        _uiState.value.panel == PanelMode.VOICE || _uiState.value.voice.strip ||
            voiceBarShowing()

    private fun startVoice() {
        cancelVoice()
        voiceStopRequested = false
        val tag = voiceLanguageTag()
        fun fail(status: VoiceStatus, message: String? = null) {
            _uiState.update {
                it.copy(
                    voice = it.voice.copy(
                        status = status, languageTag = tag, errorMessage = message,
                        partial = "", level = 0f,
                    ),
                )
            }
        }
        if (_uiState.value.secureField) {
            // The panel shows its own notice; never open the mic here.
            fail(VoiceStatus.IDLE)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail(VoiceStatus.NEED_PERMISSION)
            return
        }
        val whisperModel = whisperModel()
        val whisperSelected = isWhisperEnabled() && _uiState.value.settings.whisper.engine == "whisper"
        if (whisperSelected && whisperModel == null) {
            // Whisper is chosen but no model is downloaded — prompt for one
            // instead of opening the mic to no purpose.
            _uiState.update {
                it.copy(
                    voice = it.voice.copy(
                        status = VoiceStatus.IDLE, languageTag = tag, whisper = true,
                        whisperNeedsModel = true, partial = "", level = 0f, errorMessage = null,
                    ),
                )
            }
            return
        }
        if (whisperModel == null && !voiceEngine.isAvailable()) {
            fail(VoiceStatus.UNAVAILABLE)
            return
        }
        val ic = currentInputConnection ?: return
        // Flush the half-typed word so dictation appends after it.
        commitComposing(ic, autocorrect = false)
        refreshVoiceSpacing()
        val generation = ++voiceGeneration
        // Offline-model chip: check once per language, not per utterance
        // (continuous mode restarts sessions constantly).
        val modelKnown = _uiState.value.voice.modelState != VoiceModelState.UNKNOWN &&
            _uiState.value.voice.languageTag == tag
        _uiState.update {
            it.copy(
                voice = it.voice.copy(
                    status = VoiceStatus.LISTENING, languageTag = tag,
                    partial = "", level = 0f, errorMessage = null,
                    whisper = whisperModel != null,
                    translate = _uiState.value.settings.whisper.translate,
                    whisperNeedsModel = false,
                ),
            )
        }
        if (whisperModel != null) {
            startWhisperCapture(whisperModel, generation)
            return
        }
        if (!modelKnown) refreshVoiceModelState(tag)
        voiceEngine.start(
            tag,
            // Plain voice typing wants the words and nothing else, so the
            // recognizer is asked for no punctuation and no capital letters.
            formatting = !plainVoice(),
            listener = object : VoiceInputEngine.Listener {
                override fun onListening() {}

                override fun onLevel(level: Float) {
                    if (generation != voiceGeneration) return
                    // Quantized so the pulse ring doesn't force a state
                    // update (and recomposition) per rms callback.
                    val quantized = (level * 8).toInt() / 8f
                    _uiState.update {
                        if (it.voice.level == quantized) it
                        else it.copy(voice = it.voice.copy(level = quantized))
                    }
                }

                override fun onPartial(text: String) {
                    if (generation != voiceGeneration) return
                    // Interactive voice typing never writes a partial into the
                    // field. A partial is cumulative — the next one rewrites
                    // the whole composing region — so keeping one there is
                    // exactly what stops the keys being used at the same time.
                    // The phrase lands whole at the next pause; until then it
                    // is only in the status line.
                    if (!interactiveVoice()) {
                        currentInputConnection?.setComposingText(spacedVoiceText(text), 1)
                    }
                    _uiState.update { it.copy(voice = it.voice.copy(partial = text)) }
                }

                override fun onFinal(text: String) {
                    if (generation != voiceGeneration) return
                    voiceGeneration++
                    commitVoiceUtterance(text, tag)
                    voiceSilentRetries = 0
                    if (voiceChains() && !voiceStopRequested && voiceSessionAlive()) {
                        // Continuous dictation: chain straight into the next
                        // utterance until the user stops or leaves. Deferred
                        // to the next looper tick — starting a new recognizer
                        // synchronously from inside the old one's onResults
                        // callback races its teardown and spuriously fires
                        // onError (ERROR_CLIENT) on some OEM builds even
                        // though this utterance already succeeded.
                        _uiState.update { it.copy(voice = it.voice.copy(partial = "", level = 0f, canUndo = true)) }
                        serviceScope.launch(Dispatchers.Main) { startVoice() }
                    } else {
                        _uiState.update {
                            it.copy(
                                voice = it.voice.copy(
                                    status = VoiceStatus.IDLE, partial = "", level = 0f, canUndo = true,
                                ),
                            )
                        }
                    }
                }

                override fun onError(kind: VoiceInputEngine.ErrorKind) {
                    if (generation != voiceGeneration) return
                    voiceGeneration++
                    // A network drop mid-utterance keeps whatever was heard.
                    // Interactive voice typing owns no composing region, and
                    // the one in the field may be a word the user is typing
                    // right now — leave it alone.
                    if (!interactiveVoice()) currentInputConnection?.finishComposingText()
                    // Silence in continuous mode restarts quietly — but not
                    // forever, so an abandoned open mic winds down.
                    if (kind == VoiceInputEngine.ErrorKind.NO_SPEECH &&
                        voiceChains() &&
                        !voiceStopRequested && voiceSessionAlive() &&
                        voiceSilentRetries < voiceSilentRetryLimit()
                    ) {
                        voiceSilentRetries++
                        serviceScope.launch(Dispatchers.Main) { startVoice() }
                        return
                    }
                    val (status, message) = when (kind) {
                        VoiceInputEngine.ErrorKind.NO_SPEECH -> VoiceStatus.IDLE to null
                        VoiceInputEngine.ErrorKind.PERMISSION -> VoiceStatus.NEED_PERMISSION to null
                        VoiceInputEngine.ErrorKind.NETWORK ->
                            VoiceStatus.ERROR to getString(CommonR.string.common_error_network)
                        VoiceInputEngine.ErrorKind.BUSY ->
                            VoiceStatus.ERROR to getString(R.string.ime_service_voice_error_busy)
                        VoiceInputEngine.ErrorKind.LANGUAGE ->
                            VoiceStatus.ERROR to getString(R.string.ime_service_voice_error_language)
                        VoiceInputEngine.ErrorKind.OTHER ->
                            VoiceStatus.ERROR to getString(R.string.ime_service_voice_error_other)
                    }
                    _uiState.update {
                        it.copy(
                            voice = it.voice.copy(
                                status = status, languageTag = tag, errorMessage = message,
                                partial = "", level = 0f,
                            ),
                        )
                    }
                }
            },
        )
    }

    /**
     * The offline Whisper model to run for the language currently being typed in,
     * or null when Whisper isn't the selected engine, isn't built into this
     * flavor, or has no model downloaded yet.
     *
     * Resolving per language rather than globally is what lets dictation follow
     * the layout the way the system recognizer's locale does: switching to the
     * German layout switches to whatever model handles German.
     */
    private fun whisperModel(): WhisperModel? {
        val s = _uiState.value.settings
        if (!isWhisperEnabled() || s.whisper.engine != "whisper") return null
        return WhisperStore.modelForLanguage(
            filesDir,
            _uiState.value.language.id,
            s.whisper.modelId,
            s.whisper.modelByLang,
        )
    }

    /**
     * Records audio for offline Whisper. Unlike the streaming system recognizer,
     * Whisper transcribes a whole clip, so nothing commits until recording stops
     * (a mic tap, or the 30-second window filling). The pulse ring follows the
     * mic level while recording.
     */
    private fun startWhisperCapture(model: WhisperModel, generation: Int) {
        val recorder = WhisperRecorder(
            onLevel = { level ->
                if (generation != voiceGeneration) return@WhisperRecorder
                val quantized = (level * 8).toInt() / 8f
                _uiState.update {
                    if (it.voice.level == quantized) it
                    else it.copy(voice = it.voice.copy(level = quantized))
                }
            },
            onMaxReached = {
                // Buffer full (30 s): transcribe this clip; continuous mode then
                // starts the next one on its own.
                finishWhisper(userStopped = false)
            },
        )
        if (!recorder.start()) {
            _uiState.update {
                it.copy(
                    voice = it.voice.copy(
                        status = VoiceStatus.ERROR, partial = "", level = 0f,
                        errorMessage = getString(R.string.ime_service_voice_mic_error),
                    ),
                )
            }
            return
        }
        whisperRecorder = recorder
    }

    /**
     * Stops the Whisper recording and transcribes it off the main thread, then
     * commits exactly like a system final (spoken punctuation, spacing, learn,
     * undo). [userStopped] suppresses the continuous-mode chain. Safe to call
     * from any thread; no-op if no recording is active.
     */
    private fun finishWhisper(userStopped: Boolean) {
        val recorder = whisperRecorder ?: return
        whisperRecorder = null
        val gen = voiceGeneration
        val tag = _uiState.value.voice.languageTag
        val model = whisperModel()
        val languageId = _uiState.value.language.id
        // Grouped graphs take the language as an input, so hand them the language
        // being typed in rather than letting them guess from a short clip.
        val langToken = model?.langTokenFor(languageId)
        // Only ask for the translate task where the model was actually trained for
        // it. A graph can carry the signature without it being any good: turbo was
        // exported with one and trained for transcription alone, and running that
        // task returns confident nonsense instead of failing.
        val translate = _uiState.value.settings.whisper.translate && model?.supportsTranslate == true
        if (model == null) {
            serviceScope.launch(Dispatchers.IO) { runCatching { recorder.stop() } }
            _uiState.update { it.copy(voice = it.voice.copy(status = VoiceStatus.IDLE, level = 0f)) }
            return
        }
        _uiState.update {
            it.copy(voice = it.voice.copy(status = VoiceStatus.TRANSCRIBING, partial = "", level = 0f))
        }
        serviceScope.launch(Dispatchers.Default) {
            val pcm = runCatching { recorder.stop() }.getOrDefault(FloatArray(0))
            if (gen != voiceGeneration) return@launch
            val result = runCatching {
                WhisperEngine.transcribe(
                    WhisperStore.modelFile(filesDir, model),
                    WhisperStore.vocabFile(filesDir, model),
                    pcm,
                    translate,
                    langToken,
                )
            }
            // A graph told which language to use, or built for exactly one, cannot
            // answer in the wrong script. Only the auto-detecting ones can, and
            // Bangla misread as Hindi is the case worth repairing.
            val detected = langToken == null && model.fixedLang == null
            withContext(Dispatchers.Main) {
                if (gen != voiceGeneration) return@withContext
                result
                    .map { if (detected) WhisperScript.rescue(it.trim(), languageId) else it.trim() }
                    .onSuccess { commitWhisperResult(it, tag, userStopped) }
                    .onFailure { e ->
                        // A WhisperException carries a resource id instead of a
                        // message, so its own message is null on purpose.
                        val text = if (e is WhisperException) {
                            getString(e.messageRes, e.messageArg)
                        } else {
                            e.message ?: getString(R.string.ime_service_voice_transcribe_error)
                        }
                        _uiState.update {
                            it.copy(
                                voice = it.voice.copy(
                                    status = VoiceStatus.ERROR, partial = "", level = 0f,
                                    errorMessage = text,
                                ),
                            )
                        }
                    }
            }
        }
    }

    /** Commits a finished Whisper transcription and continues or ends the session. */
    private fun commitWhisperResult(text: String, tag: String, userStopped: Boolean) {
        val chain = !userStopped && voiceChains() &&
            !voiceStopRequested && voiceSessionAlive()
        if (text.isBlank()) {
            // Nothing heard. In continuous mode keep listening; otherwise idle.
            if (chain) {
                _uiState.update { it.copy(voice = it.voice.copy(partial = "", level = 0f)) }
                serviceScope.launch(Dispatchers.Main) { startVoice() }
            } else {
                _uiState.update { it.copy(voice = it.voice.copy(status = VoiceStatus.IDLE, partial = "", level = 0f)) }
            }
            return
        }
        commitVoiceUtterance(text, tag)
        if (chain) {
            _uiState.update { it.copy(voice = it.voice.copy(partial = "", level = 0f, canUndo = true)) }
            serviceScope.launch(Dispatchers.Main) { startVoice() }
        } else {
            _uiState.update {
                it.copy(voice = it.voice.copy(status = VoiceStatus.IDLE, partial = "", level = 0f, canUndo = true))
            }
        }
    }

    /**
     * Voice typing shares the field with the keys: the microphone stays open
     * while the user types, so nothing is composed and each phrase lands as
     * finished text ([VoiceBarSettings.TYPING_INTERACTIVE] and
     * [VoiceBarSettings.TYPING_PLAIN]).
     */
    private fun interactiveVoice(): Boolean =
        _uiState.value.settings.voiceBar.interactiveTyping()

    /** Dictated text goes in exactly as it was heard ([VoiceBarSettings.TYPING_PLAIN]). */
    private fun plainVoice(): Boolean = _uiState.value.settings.voiceBar.plainTyping()

    /**
     * Whether the next utterance follows this one without another press of the
     * microphone. Interactive voice typing always chains: a session that ended
     * at the first pause would leave the user typing into a closed microphone,
     * which is the one thing the mode exists to avoid.
     */
    private fun voiceChains(): Boolean =
        _uiState.value.settings.voiceContinuous || interactiveVoice()

    /** See [VOICE_SILENT_RETRIES]. */
    private fun voiceSilentRetryLimit(): Int =
        if (interactiveVoice()) VOICE_SILENT_RETRIES_INTERACTIVE else VOICE_SILENT_RETRIES

    /**
     * Puts one finished utterance into the field, however it was recognised.
     *
     * Interactive voice typing reads the spacing here instead of at the start
     * of the session: the cursor has been moving under the open microphone the
     * whole time, so where the words go is only known now. Plain voice typing
     * takes neither the spoken-punctuation pass nor the spacing, because there
     * the point is the words exactly as they were said.
     */
    private fun commitVoiceUtterance(text: String, tag: String) {
        val settings = _uiState.value.settings
        val plain = settings.voiceBar.plainTyping()
        val processed = if (!plain && settings.voiceSpokenPunctuation) {
            VoicePunctuation.apply(text, tag)
        } else {
            text
        }
        val ic = currentInputConnection ?: return
        // A word still being composed — typed under the microphone, or resumed
        // by a caret tap while a transcription was in flight — has to commit
        // first, or the dictated text replaces it.
        commitComposing(ic, autocorrect = false)
        if (settings.voiceBar.interactiveTyping()) refreshVoiceSpacing()
        val spaced = if (plain) processed else spacedVoiceText(processed)
        ic.commitText(spaced, 1)
        learn(processed)
        lastVoiceCommit = spaced
    }

    /** Intelligent leading and trailing spaces when dictation starts mid-text or replaces selection. */
    private fun spacedVoiceText(text: String): String =
        VoiceSpacing.format(text, voiceNeedsLeadingSpace, voiceNeedsTrailingSpace)

    /**
     * Reads the characters around the cursor to decide whether dictated text
     * needs a space in front of it or behind it. Run at the start of a session,
     * and again after an edit made from the panel's own rail — a space typed
     * there must not turn into two once the transcription lands.
     */
    private fun refreshVoiceSpacing() {
        val ic = currentInputConnection ?: return
        val beforeChar = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val afterChar = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        voiceNeedsLeadingSpace = VoiceSpacing.needsLeadingSpace(beforeChar, afterChar)
        voiceNeedsTrailingSpace = VoiceSpacing.needsTrailingSpace(beforeChar, afterChar)
    }

    /**
     * Space, backspace or enter pressed on the voice panel's action rail. Those
     * keys belong to the dictation surface, not to the keyboard: the keys are not
     * even on screen, so reaching for space there means "put a space in", never
     * "I have finished talking". They leave the session running.
     *
     * The exception is a partial already sitting in the editor as composing text.
     * The system recognizer's partials are cumulative and each one rewrites the
     * whole composing region, so a character typed inside it is either duplicated
     * or swallowed; that case still ends the utterance and keeps what was heard.
     * Whisper has no partial — its audio is in the recorder, not the editor — so
     * a rail key never costs a recording.
     */
    fun onVoiceRailKey(action: VoiceBarAction) {
        when (action) {
            is VoiceBarAction.RailKey -> onVoiceSurfaceKey(action.key)
            is VoiceBarAction.SetVertical -> {
                vibrate()
                // The old orientation's rectangle must not shape the new
                // one's insets — one clean whole-window pass until the
                // reshaped pill publishes, instead of two app reflows.
                voiceBarBounds = null
                window?.window?.decorView?.requestLayout()
                serviceScope.launch { settingsRepository.setVoiceBarVertical(action.vertical) }
            }
            is VoiceBarAction.SetRest ->
                serviceScope.launch {
                    settingsRepository.setVoiceBarRest(
                        action.snap, action.rightEdge, action.yBias, action.dockBias,
                    )
                }
            is VoiceBarAction.SwitchSurface -> switchVoiceSurface(action.mode)
            is VoiceBarAction.Bounds -> onVoiceBarBounds(action)
        }
    }

    private fun onVoiceSurfaceKey(key: Key) {
        if (_uiState.value.voice.partial.isNotEmpty()) {
            onKey(key)
            return
        }
        voiceRailKeyInFlight = true
        try {
            onKey(key)
        } finally {
            voiceRailKeyInFlight = false
        }
        if (voiceActive()) refreshVoiceSpacing()
    }

    /** A dictation session is mid-flight: recording, finishing, or transcribing. */
    private fun voiceActive(): Boolean = when (_uiState.value.voice.status) {
        VoiceStatus.LISTENING, VoiceStatus.FINISHING, VoiceStatus.TRANSCRIBING -> true
        else -> false
    }

    /**
     * Abandons any running dictation: the mic is released and the partial
     * already on screen stays as committed text (the user said it — losing
     * it on a panel switch would be worse than keeping it).
     */
    private fun cancelVoice() {
        val status = _uiState.value.voice.status
        // Bumping first invalidates any in-flight Whisper transcription.
        voiceGeneration++
        whisperRecorder?.let { rec ->
            whisperRecorder = null
            serviceScope.launch(Dispatchers.IO) { runCatching { rec.stop() } }
        }
        if (status != VoiceStatus.LISTENING && status != VoiceStatus.FINISHING &&
            status != VoiceStatus.TRANSCRIBING
        ) {
            return
        }
        voiceEngine.cancel()
        // The composing region is the dictated partial in block voice typing,
        // so ending the session settles it. In interactive voice typing it is
        // whatever word the user is typing this second, and finishing it here
        // would strand the keyboard's own buffer against a field that is no
        // longer composing.
        if (!interactiveVoice()) currentInputConnection?.finishComposingText()
        _uiState.update {
            it.copy(voice = it.voice.copy(status = VoiceStatus.IDLE, partial = "", level = 0f))
        }
    }

    /**
     * Manual input (keys, swipes, suggestion taps) during block voice typing
     * ends the utterance: partial results are cumulative, so keystrokes woven
     * into the composing region would corrupt it. The partial is kept; the
     * panel/strip stays open to resume with a mic tap.
     *
     * Interactive voice typing keeps the session instead. See the body.
     */
    private fun stopVoiceForManualInput() {
        // The panel's own rail keys are part of the dictation surface and are
        // handled in [onVoiceRailKey], which dispatches through here.
        if (voiceRailKeyInFlight) return
        // Interactive voice typing is the whole point of this check being
        // conditional: the microphone stays open through typing, glides,
        // suggestions and layout switches, because nothing of the utterance
        // is in the field to be corrupted. Spacing is read again when the
        // phrase lands ([commitVoiceUtterance]), not now — this runs before
        // the key that called it has been applied.
        if (interactiveVoice()) return
        val status = _uiState.value.voice.status
        // A Whisper clip already off the mic and inside the decoder is not
        // something a keystroke should throw away: the audio is captured, the mic
        // is shut, and the words land after whatever was typed. Only the
        // continuous-mode chain is called off, so the mic does not reopen on top
        // of typing that has started.
        if (status == VoiceStatus.TRANSCRIBING) {
            voiceStopRequested = true
            return
        }
        if (status == VoiceStatus.LISTENING || status == VoiceStatus.FINISHING) {
            voiceStopRequested = true
            cancelVoice()
        }
    }

    /**
     * Strip and bar modes reroute the voice tool: no panel — the compact bar
     * over the keys, or the collapsed bar in the keyboard's place. A voice
     * panel already open (setting flipped mid-session) still closes normally
     * through [onPanelChange]. True when the tap was taken.
     */
    /**
     * [switchVoiceSurface] is mid-flight: the surfaces are being swapped by
     * hand, so [rerouteVoiceTool] must not bounce its onPanelChange call back
     * to the bar the settings flow still says is the mode.
     */
    private var voiceSurfaceSwitch = false

    private fun rerouteVoiceTool(panel: PanelMode): Boolean {
        if (voiceSurfaceSwitch) return false
        if (panel != PanelMode.VOICE || _uiState.value.panel == PanelMode.VOICE) return false
        return when (_uiState.value.settings.voiceBar.mode) {
            VoiceBarSettings.MODE_STRIP -> {
                toggleVoiceStrip()
                true
            }
            VoiceBarSettings.MODE_BAR -> {
                toggleVoiceBar()
                true
            }
            else -> false
        }
    }

    /**
     * A panel is opening: the dictation strip closes outright, and the
     * collapsed bar — which the keyboard needs the window back from (hardware
     * shortcuts can do this) — ends its session but stays armed, so it
     * returns when the panel closes.
     */
    private fun dismissVoiceSurfacesFor(panel: PanelMode) {
        if (_uiState.value.voice.strip) closeVoiceStrip()
        if (panel != PanelMode.VOICE && voiceBarShowing()) cancelVoice()
    }

    /** Voice tool tap in strip mode: dictate over the keys, no panel. */
    private fun toggleVoiceStrip() {
        if (_uiState.value.voice.strip) {
            closeVoiceStrip()
            return
        }
        vibrate()
        if (_uiState.value.secureField) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_voice_secure_field_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        _uiState.update { it.copy(voice = it.voice.copy(strip = true)) }
        voiceSilentRetries = 0
        startVoice()
    }

    private fun closeVoiceStrip() {
        if (!_uiState.value.voice.strip) return
        vibrate()
        cancelVoice()
        _uiState.update { it.copy(voice = it.voice.copy(strip = false, canUndo = false)) }
    }

    // ---- collapsed voice bar (Gboard-style toolbar) ----

    /**
     * The collapsed bar is on screen right now. Armed but hidden does not
     * count: a panel a hardware shortcut opened, or a password field, puts the
     * keyboard back up without disarming the bar.
     */
    private fun voiceBarShowing(): Boolean {
        val state = _uiState.value
        return state.voice.bar && state.panel == PanelMode.NONE && !state.secureField
    }

    /** Voice tool tap in bar mode: collapse the keyboard to the bar, or restore it. */
    private fun toggleVoiceBar() {
        val state = _uiState.value
        if (state.secureField) {
            vibrate()
            Toast.makeText(
                this,
                getString(R.string.ime_service_voice_secure_field_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (state.voice.bar) {
            if (voiceBarShowing()) {
                closeVoiceBar()
            } else if (state.panel != PanelMode.NONE) {
                // Armed but hidden behind a panel (a hardware shortcut can
                // open one over the bar): the tap asks for the bar back, not
                // for disarming it. Closing the panel through onPanelChange
                // keeps that path's cleanup; the reroute cannot recurse here
                // because the closing panel is never VOICE.
                onPanelChange(state.panel)
                voiceSilentRetries = 0
                startVoice()
            }
            return
        }
        vibrate()
        // The state flag first so the bar is up this frame; the persisted flag
        // is what brings it back on the next field ([VoiceUi.bar] doc). The
        // exit button follows the stored origin: a tool tap with bar as the
        // settings-chosen mode keeps the keyboard button.
        _uiState.update {
            it.copy(
                voice = it.voice.copy(bar = true, barInline = it.settings.voiceBar.inline),
            )
        }
        serviceScope.launch { settingsRepository.setVoiceBarActive(true) }
        voiceSilentRetries = 0
        startVoice()
    }

    /** The bar's keyboard button (or the voice tool again): bring the keys back. */
    private fun closeVoiceBar() {
        if (!_uiState.value.voice.bar) return
        vibrate()
        cancelVoice()
        voiceBarBounds = null
        _uiState.update { it.copy(voice = it.voice.copy(bar = false, canUndo = false)) }
        serviceScope.launch { settingsRepository.setVoiceBarActive(false) }
    }

    /**
     * The bar published its rectangle. Kept apart from [floatingPanelBounds]:
     * the settings collector clears that one whenever floating mode is off,
     * which is exactly when the bar needs its own region to survive.
     */
    private var voiceBarBounds: android.graphics.Rect? = null

    /**
     * Inline switch between the voice surfaces, from the panel's and strip's
     * collapse buttons and the bar's expand button. Persists the choice as
     * the new default in one write; the ui state moves first so the swap is
     * this frame, not a DataStore round trip later.
     */
    private fun switchVoiceSurface(target: String) {
        val state = _uiState.value
        if (state.secureField) return
        if (target == VoiceBarSettings.MODE_BAR) {
            val from = state.settings.voiceBar.mode
            val returnMode = if (from == VoiceBarSettings.MODE_STRIP) {
                VoiceBarSettings.MODE_STRIP
            } else {
                VoiceBarSettings.MODE_PANEL
            }
            vibrate()
            _uiState.update {
                it.copy(
                    panel = PanelMode.NONE,
                    panelFocus = null,
                    voice = it.voice.copy(strip = false, bar = true, barInline = true),
                )
            }
            serviceScope.launch {
                settingsRepository.setVoiceSurface(
                    mode = VoiceBarSettings.MODE_BAR,
                    barActive = true,
                    returnMode = returnMode,
                )
            }
            voiceSilentRetries = 0
            startVoice()
            return
        }
        // Expanding back to the surface the bar replaced.
        voiceBarBounds = null
        serviceScope.launch {
            settingsRepository.setVoiceSurface(mode = target, barActive = false)
        }
        if (target == VoiceBarSettings.MODE_STRIP) {
            vibrate()
            // One update for the whole swap: dropping the bar first put a
            // bare keyboard on screen for a frame before the strip arrived.
            _uiState.update {
                it.copy(voice = it.voice.copy(bar = false, barInline = false, strip = true))
            }
            voiceSilentRetries = 0
            startVoice()
        } else {
            // The panel opens through onPanelChange so its side effects run;
            // the flag stops the reroute from reading the stale "bar" mode
            // out of the not-yet-updated settings and bouncing straight back.
            // The panel goes up BEFORE the bar flag drops for the same
            // one-frame reason as the strip above: `panel != NONE` already
            // hands the window back to the keyboard, so the intermediate
            // frame shows the panel, never bare keys.
            voiceSurfaceSwitch = true
            try {
                onPanelChange(PanelMode.VOICE)
            } finally {
                voiceSurfaceSwitch = false
            }
            _uiState.update { it.copy(voice = it.voice.copy(bar = false, barInline = false)) }
        }
    }

    private fun onVoiceBarBounds(bounds: VoiceBarAction.Bounds) {
        val rect = android.graphics.Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (rect != voiceBarBounds) {
            voiceBarBounds = rect
            // Same trick as [onFloatingBounds]: insets are only re-queried on
            // a window layout pass, so force one.
            window?.window?.decorView?.requestLayout()
        }
    }

    /**
     * Asks the on-device recognizer where [tag]'s model stands, for the
     * panel's offline-model chip. UNKNOWN (chip hidden) below API 33 or
     * when the language can't run on-device at all.
     */
    private fun refreshVoiceModelState(tag: String) {
        voiceEngine.checkOnDeviceModel(tag) { result ->
            val state = when (result) {
                VoiceInputEngine.ModelCheckResult.INSTALLED -> VoiceModelState.INSTALLED
                VoiceInputEngine.ModelCheckResult.DOWNLOADABLE -> VoiceModelState.DOWNLOADABLE
                VoiceInputEngine.ModelCheckResult.PENDING -> VoiceModelState.DOWNLOADING
                VoiceInputEngine.ModelCheckResult.UNSUPPORTED -> VoiceModelState.UNKNOWN
            }
            _uiState.update {
                if (it.voice.languageTag != tag) it
                else it.copy(voice = it.voice.copy(modelState = state, modelProgress = -1))
            }
        }
    }

    /** Offline-model chip on the voice panel: download the active language. */
    fun onVoiceModelDownload() {
        vibrate()
        val tag = _uiState.value.voice.languageTag
        _uiState.update {
            it.copy(voice = it.voice.copy(modelState = VoiceModelState.DOWNLOADING, modelProgress = -1))
        }
        voiceEngine.downloadModel(
            tag,
            object : VoiceInputEngine.ModelDownloadCallback {
                override fun onProgress(percent: Int) {
                    _uiState.update {
                        if (it.voice.languageTag != tag) it
                        else it.copy(voice = it.voice.copy(modelProgress = percent))
                    }
                }

                override fun onSuccess() {
                    _uiState.update {
                        if (it.voice.languageTag != tag) it
                        else it.copy(voice = it.voice.copy(modelState = VoiceModelState.INSTALLED, modelProgress = -1))
                    }
                }

                override fun onScheduled() {
                    // Queued by the system (Wi-Fi / idle) or fire-and-forget
                    // on API 33. Stays "downloading"; reopening the panel
                    // re-checks and settles the state.
                }

                override fun onError() {
                    Toast.makeText(
                        this@WMKeyboardService,
                        getString(R.string.ime_service_voice_model_download_error),
                        Toast.LENGTH_SHORT,
                    ).show()
                    _uiState.update {
                        if (it.voice.languageTag != tag) it
                        else it.copy(voice = it.voice.copy(modelState = VoiceModelState.DOWNLOADABLE, modelProgress = -1))
                    }
                }
            },
        )
    }

    /**
     * "Use the system recognizer" chip, shown when offline Whisper is selected but
     * has no model downloaded. Switches the engine back to the system recognizer
     * and starts listening, so dictation works from the same tap instead of
     * sending the user to settings for a download they may not want.
     *
     * The change is persisted, the same as picking the engine in settings: a
     * silent one-session override would leave the mic dead again next time.
     */
    fun onVoiceUseSystemEngine() {
        vibrate()
        serviceScope.launch {
            settingsRepository.setVoiceEngine("system")
            // Settings arrive through the state flow, so starting has to wait for
            // the new value to land or startVoice would read "whisper" again and
            // put the same prompt back up. Bounded, so a write that never surfaces
            // leaves the panel as it is now rather than a coroutine parked here.
            withTimeoutOrNull(ENGINE_SWITCH_TIMEOUT_MS) {
                _uiState.first { it.settings.whisper.engine == "system" }
            }
            // The panel could have been closed while that landed, and a mic opened
            // with no dictation surface on screen is a privacy dot nobody asked for.
            if (!voiceSessionAlive()) return@launch
            voiceSilentRetries = 0
            startVoice()
        }
    }

    /** Translate chip on the Whisper voice panel: flip translate-to-English. */
    fun onWhisperTranslateToggle() {
        vibrate()
        val next = !_uiState.value.settings.whisper.translate
        serviceScope.launch { settingsRepository.setWhisperTranslate(next) }
    }

    /** "Get a voice model" chip: open the Voice tool settings to download one. */
    fun onOpenVoiceSettings() = openToolSettings(ToolbarTool.VOICE)

    /** Undo chip: removes the last dictated utterance if still at the cursor. */
    fun onVoiceUndo() {
        vibrate()
        val last = lastVoiceCommit
        lastVoiceCommit = null
        _uiState.update { it.copy(voice = it.voice.copy(canUndo = false)) }
        if (last == null) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        if (before == last) ic.deleteSurroundingText(last.length, 0)
    }

    // ---- handwriting ----

    /**
     * The ink model to recognize against. ML Kit covers most but not all of the
     * keyboard's languages, so a language it cannot recognize hands over to the
     * first enabled one it can — writing something is better than a panel that
     * offers a download which could never succeed.
     */
    private fun hwLanguageTag(): String {
        val state = _uiState.value
        HandwritingModels.tagFor(state.language)?.let { return it }
        state.settings.enabledLanguages.firstNotNullOfOrNull { HandwritingModels.tagFor(it) }
            ?.let { return it }
        return HandwritingModels.tagForLangId("en")
    }

    /**
     * Letter-area swipes are drawing handwriting (rather than gliding a word):
     * full build, gesture typing on with the swipe action set to HANDWRITE, on
     * the letter layer with no panel open. Model readiness is checked
     * separately so this can also gate the "download the model" hint.
     */
    private fun keyboardHandwriteActive(state: KeyboardUiState): Boolean =
        BuildConfig.ENABLE_ML_KIT_HANDWRITING &&
            state.settings.gestureTyping &&
            state.settings.letterSwipeAction == LetterSwipeAction.HANDWRITE &&
            state.layoutMode == LayoutMode.LETTERS &&
            state.panel == PanelMode.NONE

    /**
     * A swipe finished on the key grid while [keyboardHandwriteActive]. With
     * the model ready it feeds the same pipeline as the handwriting panel;
     * otherwise it points the user at the model download (once) instead of
     * silently gliding a word they didn't ask for.
     */
    fun onKeyboardHandwritingStroke(stroke: HwStroke, canvasSize: IntSize) {
        val state = _uiState.value
        if (!keyboardHandwriteActive(state)) return
        if (state.handwriting.status != HandwritingStatus.READY) {
            // CHECKING/DOWNLOADING resolve on their own; only nag once the
            // absence is confirmed.
            if (state.handwriting.status == HandwritingStatus.NEED_MODEL ||
                state.handwriting.status == HandwritingStatus.ERROR
            ) {
                if (!hwModelHintShown) {
                    hwModelHintShown = true
                    Toast.makeText(
                        this,
                        getString(R.string.ime_service_handwriting_need_model_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                refreshHandwritingStatus()
            }
            return
        }
        onHandwritingStroke(stroke, canvasSize)
    }

    /**
     * Throw away handwriting ink drawn on the keys — used when the letter
     * layer (and with it the on-keyboard writing surface) goes away, so the
     * strokes don't reappear when the user comes back to the letters.
     */
    private fun dropKeyboardHandwritingInk() {
        if (_uiState.value.handwriting.strokes.isEmpty()) return
        hwJob?.cancel()
        hwGeneration++
        _uiState.update {
            it.copy(handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false))
        }
    }

    /**
     * Re-checks whether the active language's recognition model is on the
     * device, resetting the panel (ink, errors) in the process.
     */
    private fun refreshHandwritingStatus() {
        hwJob?.cancel()
        hwGeneration++
        val tag = hwLanguageTag()
        _uiState.update {
            it.copy(handwriting = HandwritingUi(status = HandwritingStatus.CHECKING, languageTag = tag))
        }
        serviceScope.launch {
            val downloaded = HandwritingModels.isDownloaded(tag)
            _uiState.update {
                if (it.handwriting.languageTag != tag) return@update it
                it.copy(
                    handwriting = it.handwriting.copy(
                        status = if (downloaded) HandwritingStatus.READY else HandwritingStatus.NEED_MODEL,
                    ),
                )
            }
        }
    }

    /** Download button on the panel: fetch the active language's model. */
    fun onHandwritingDownload() {
        vibrate()
        val tag = _uiState.value.handwriting.languageTag
        _uiState.update {
            it.copy(handwriting = it.handwriting.copy(status = HandwritingStatus.DOWNLOADING, errorMessage = null))
        }
        serviceScope.launch {
            val result = runCancellable { HandwritingModels.download(tag) }
            _uiState.update {
                if (it.handwriting.languageTag != tag) return@update it
                it.copy(
                    handwriting = if (result.isSuccess) {
                        it.handwriting.copy(status = HandwritingStatus.READY)
                    } else {
                        it.handwriting.copy(
                            status = HandwritingStatus.ERROR,
                            errorMessage = getString(R.string.ime_service_handwriting_download_error),
                        )
                    },
                )
            }
        }
    }

    /** A stroke was finished on the canvas; recognize after a short pause. */
    fun onHandwritingStroke(stroke: HwStroke, canvasSize: IntSize) {
        val state = _uiState.value
        if ((state.panel != PanelMode.HANDWRITING && !keyboardHandwriteActive(state)) ||
            state.handwriting.status != HandwritingStatus.READY
        ) {
            return
        }
        hwCanvasSize = canvasSize
        _uiState.update {
            it.copy(
                handwriting = it.handwriting.copy(strokes = it.handwriting.strokes + stroke, recognizing = false),
                // Stale candidates from the previous word must not be
                // tappable while new ink is on the canvas.
                suggestions = emptyList(),
                emojiSuggestions = emptyList(),
            )
        }
        scheduleHandwritingRecognition()
    }

    /** Undo button: drop the last stroke and re-recognize what remains. */
    fun onHandwritingUndo() {
        vibrate()
        hwJob?.cancel()
        hwGeneration++
        _uiState.update {
            it.copy(handwriting = it.handwriting.copy(strokes = it.handwriting.strokes.dropLast(1), recognizing = false))
        }
        if (_uiState.value.handwriting.strokes.isNotEmpty()) scheduleHandwritingRecognition()
    }

    private fun scheduleHandwritingRecognition() {
        hwJob?.cancel()
        val generation = ++hwGeneration
        hwJob = serviceScope.launch {
            delay(handwritingRecognitionDelayMs())
            recognizeAndCommitHandwriting(generation)
        }
    }

    /**
     * Quiet time after the last stroke before recognizing and committing.
     * Bengali glyphs are built from several strokes — conjuncts, the matra,
     * vowel signs — and the writer lifts the finger between them; the global
     * default is short enough that a natural mid-glyph pause commits a
     * half-written character. Give Bengali a higher floor so a comfortable
     * inter-stroke pause never triggers an early commit, while still honouring
     * a longer pause the user set for themselves.
     */
    private fun handwritingRecognitionDelayMs(): Long {
        val state = _uiState.value
        var delay = state.settings.handwritingCommitDelayMs.toLong()
        if (state.handwriting.languageTag == "bn") {
            delay = maxOf(delay, BENGALI_HW_MIN_COMMIT_DELAY_MS)
        }
        // Writing on the keys: for the dot leeway after a stroke, a tap over
        // the letters is grabbed as the dot on an i/j/t. Committing while that
        // window is still open would leave the dot with no character to join —
        // it would neither type the key (the leeway already claimed the touch)
        // nor land on the glyph. So the ink waits at least as long as the
        // leeway the user set for it.
        if (keyboardHandwriteActive(state)) {
            delay = maxOf(delay, state.settings.gesture.handwriteDotCooldownMs.toLong())
        }
        return delay
    }

    /**
     * Runs ML Kit recognition over the accumulated ink and commits the top
     * candidate. Alternates go to the suggestion strip; tapping one
     * replaces the committed word (same mechanics as gesture typing).
     * A [generation] mismatch afterwards means new ink arrived or the
     * panel closed while recognizing — the result is stale, drop it.
     */
    private suspend fun recognizeAndCommitHandwriting(generation: Int) {
        val state = _uiState.value
        val strokes = state.handwriting.strokes
        if (strokes.isEmpty()) return
        val tag = state.handwriting.languageTag
        _uiState.update { it.copy(handwriting = it.handwriting.copy(recognizing = true)) }
        val ic = currentInputConnection
        val preContext = ic?.getTextBeforeCursor(20, 0)?.toString().orEmpty()
        val result = runCancellable {
            hwRecognizer.recognize(
                tag = tag,
                strokes = strokes,
                preContext = preContext,
                writingAreaWidth = hwCanvasSize.width.toFloat(),
                writingAreaHeight = hwCanvasSize.height.toFloat(),
            )
        }
        if (generation != hwGeneration ||
            (_uiState.value.panel != PanelMode.HANDWRITING && !keyboardHandwriteActive(_uiState.value))
        ) {
            return
        }

        val candidates = result.getOrNull()
        if (candidates == null) {
            // Model gone mid-session (deleted from settings) or ML Kit
            // failure: re-check instead of silently eating ink forever.
            refreshHandwritingStatus()
            return
        }
        if (candidates.isEmpty()) {
            _uiState.update {
                it.copy(handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false))
            }
            return
        }

        var word = candidates.first()
        val settings = state.settings
        // Sentence-start capitalization, English only — Bengali has no case.
        if (tag == "en-US" && settings.autoCapitalize && !state.secureField &&
            word.firstOrNull()?.isLowerCase() == true && shouldAutoCapitalize()
        ) {
            word = word.replaceFirstChar { it.uppercase() }
        }
        // Space between consecutively written words, but never before
        // punctuation ("," "." "?" …).
        val needsSpace = settings.handwritingAutoSpace &&
            word.firstOrNull()?.isLetterOrDigit() == true &&
            preContext.isNotEmpty() && !preContext.last().isWhitespace()
        val connection = currentInputConnection ?: run {
            // Input connection lost between recognition and commit. Clear the
            // spinner and drop the ink instead of leaving the panel stuck in
            // "recognizing" with stale strokes that would be re-recognized
            // together with the next glyph.
            _uiState.update {
                it.copy(handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false))
            }
            return
        }
        // A half-typed word (write-on-keys mode interleaves taps and ink) or a
        // word the caret settle re-armed as composing commits first, so the
        // recognized word appends instead of replacing the composing region.
        commitComposing(connection, autocorrect = false)
        connection.commitText(if (needsSpace) " $word" else word, 1)
        learn(word)
        lastGestureWord = word
        armRevertGuard()
        _uiState.update {
            it.copy(
                handwriting = it.handwriting.copy(strokes = emptyList(), recognizing = false),
                suggestions = if (state.secureField) emptyList() else candidates,
            )
        }
    }

    // ---- tools: flashlight, undo/redo, weather ----

    /** Camera with a flash unit, or null when the device has none. */
    private var torchCameraId: String? = null

    /**
     * The camera enumeration that resolves [torchCameraId], so the flashlight
     * tool can wait for it instead of racing it.
     *
     * Finding the camera with a flash is a call into the camera service per
     * camera, so it runs off the main thread at service create — which leaves
     * a window where [torchCameraId] is null because the answer is not in yet,
     * not because the device has no flash. The tool's own "no flash on this
     * device" message would be a lie during that window, so it joins this
     * first.
     */
    private var torchProbe: Job? = null

    /** Whether a flashlight tap is already parked on [torchProbe]. */
    private var flashlightWaitingOnProbe = false

    /**
     * Whether the Harper grammar library loaded, cached off the hot path.
     *
     * Asking [GrammarChecker] directly is not a field read: the answer comes
     * from a `System.loadLibrary`, and libharper_jni.so is 11 MB. It was being
     * asked on every keyboard show — from the state [onStartInputView] builds
     * and again from the panel-change path — so the first show in each process
     * mapped and relocated the whole library between the field being focused
     * and the keyboard appearing, whether or not the Grammar tool was ever
     * opened. Resolved once at service create instead, on a worker.
     *
     * Only ever set to true, and only the tool's greyed-out state reads it. The
     * tool's own handlers still ask [GrammarChecker] directly — by then the
     * library has to load anyway — so this flag decides chrome, never whether
     * a check can run. See [grammarProbePending] for the window before it
     * settles.
     */
    @Volatile
    private var grammarAvailable = false

    /**
     * The probe that resolves [grammarAvailable].
     *
     * Unlike [torchProbe] nothing joins this one: the flashlight has to know
     * the answer before it can act, whereas the grammar flag only decides
     * whether the tool draws itself as unavailable, and [grammarProbePending]
     * answers that without blocking. Kept so that question can be asked.
     */
    private var grammarProbe: Job? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _uiState.update { it.copy(torchOn = enabled) }
            }
        }
    }

    fun onFlashlightToggle() {
        vibrate()
        val probe = torchProbe
        if (probe != null && !probe.isCompleted) {
            // Still working out which camera has the flash — see [torchProbe].
            // Answering now would report "no flash on this device" for a device
            // that has one.
            //
            // At most one tap waits. Two would both resume in the same main-
            // thread drain, before the torch callback had reported the first,
            // so both would read the same `torchOn` and ask for the same state
            // — a double tap would turn the torch on and leave it on.
            if (flashlightWaitingOnProbe) return
            flashlightWaitingOnProbe = true
            serviceScope.launch {
                try {
                    probe.join()
                    toggleFlashlightResolved()
                } finally {
                    flashlightWaitingOnProbe = false
                }
            }
            return
        }
        toggleFlashlightResolved()
    }

    /**
     * Whether the grammar library's availability is still being worked out.
     *
     * The tool's chrome treats "still working it out" as available, rather than
     * waiting: the alternative is telling the user the feature is not in this
     * build when it is, and the wait would be the 11 MB map this all exists to
     * keep off the show path. Safe to be optimistic because the tool's own
     * handlers read [GrammarChecker] directly, so an over-eager answer here
     * cannot make it try to lint without a library — and the probe's own update
     * settles [grammarAvailable] either way.
     */
    private fun grammarProbePending(): Boolean = grammarProbe?.isCompleted == false

    /** [onFlashlightToggle] once [torchCameraId] is known to be settled. */
    private fun toggleFlashlightResolved() {
        if (torchCameraId == null) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_flashlight_missing_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        setTorch(!_uiState.value.torchOn)
    }

    private fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        // Fails if another app holds the camera; the torch callback keeps
        // the icon truthful either way.
        runCatching {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
        }
    }

    /**
     * Sends the editor's undo/redo keyboard shortcut (Ctrl+Z / Ctrl+Shift+Z
     * or Ctrl+Y per settings). Editors without shortcut support ignore it —
     * the IME has no way to reach their private undo stacks.
     */
    fun onUndoRedo(redo: Boolean) {
        val ic = currentInputConnection ?: return
        vibrate()
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        val settings = _uiState.value.settings
        val code = if (redo && settings.redoUsesCtrlY) KeyEvent.KEYCODE_Y else KeyEvent.KEYCODE_Z
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (redo && !settings.redoUsesCtrlY) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        val time = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** Incognito tool: pause learning + clipboard capture with one tap. */
    fun onIncognitoToggle() {
        vibrate()
        val state = _uiState.value
        // The field itself asked for incognito, so the switch has nothing to
        // turn off — say so instead of leaving the tool looking stuck on.
        if (state.fieldIncognito) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_incognito_field_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val next = !state.settings.incognito
        Toast.makeText(
            this,
            if (next) {
                getString(R.string.ime_service_incognito_on_toast)
            } else {
                getString(R.string.ime_service_incognito_off_toast)
            },
            Toast.LENGTH_SHORT,
        ).show()
        serviceScope.launch { settingsRepository.setIncognito(next) }
    }

    /**
     * The toolbar's power-saving switch. Flips the manual override, which is
     * what [PowerSavingSettings.appliesTo] reads first — so tapping it on works
     * on a full battery, and tapping it off while an automatic trigger is
     * live only clears the manual half.
     */
    fun onPowerSavingToggle() {
        vibrate()
        val state = _uiState.value
        val config = state.settings.powerSaving
        val next = !config.manual
        // Turning the manual switch *off* while the battery is still low leaves
        // the automatic trigger holding it on, which would read as a dead
        // button. Say which one is in charge instead.
        val stillOn = !next && config.copy(manual = false).appliesTo(powerSaver.state.value)
        Toast.makeText(
            this,
            when {
                next && !config.dropsAnything ->
                    getString(R.string.ime_service_power_saving_on_empty_toast)
                next -> getString(R.string.ime_service_power_saving_on_toast)
                stillOn -> getString(R.string.ime_service_power_saving_still_on_toast)
                else -> getString(R.string.ime_service_power_saving_off_toast)
            },
            Toast.LENGTH_SHORT,
        ).show()
        serviceScope.launch { settingsRepository.setPowerSavingManual(next) }
    }

    /**
     * Enters or leaves the inline resize mode. A second tap while the mode is
     * open is a cancel — the hardware shortcut path can re-trigger the tool,
     * and "tap again to leave without saving" is the least surprising reading.
     */
    fun onResizeToggle() {
        vibrate()
        if (_uiState.value.resize) {
            onSizingAction(SizingAction.ResizeCancel)
            return
        }
        val settings = _uiState.value.settings
        if (settings.floatingKeyboard) {
            // Floating has its own drag-to-resize grip; the inline handles are
            // laid out against the docked frame and mean nothing here.
            Toast.makeText(
                this,
                getString(R.string.ime_service_resize_floating_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (voiceBarShowing()) return
        if (settings.oneHandedMode != OneHandedMode.OFF) onOneHandedChange(OneHandedMode.OFF)
        // Through the full close path, not a bare `panel = NONE`: a panel
        // leaves media searches, focus rings and edit buffers behind it.
        if (_uiState.value.panel != PanelMode.NONE) {
            onPanelChange(PanelMode.NONE, haptic = false)
        }
        _uiState.update {
            it.copy(resize = true, toolPicker = null, languageSwitch = null)
        }
    }

    fun onAutocorrectToggle() {
        vibrate()
        val next = !_uiState.value.settings.autocorrect
        Toast.makeText(
            this,
            if (next) {
                getString(R.string.ime_service_autocorrect_on_toast)
            } else {
                getString(R.string.ime_service_autocorrect_off_toast)
            },
            Toast.LENGTH_SHORT,
        ).show()
        serviceScope.launch { settingsRepository.setAutocorrect(next) }
    }

    fun onThemeSelect(id: String) {
        vibrate()
        serviceScope.launch { settingsRepository.setKeyboardThemeId(id) }
    }

    /** Blank id means the built-in icons; the board redraws on its own. */
    fun onIconPackSelect(id: String) {
        vibrate()
        serviceScope.launch { settingsRepository.setIconPack(id) }
    }

    /** Sound & haptics quick panel writes straight into the shared settings. */
    fun onSoundHaptic(action: SoundHapticAction) {
        serviceScope.launch {
            when (action) {
                is SoundHapticAction.Haptics -> settingsRepository.setHapticFeedback(action.on)
                is SoundHapticAction.HapticStyleChange -> settingsRepository.setHapticStyle(action.style)
                is SoundHapticAction.HapticAmplitude -> settingsRepository.setHapticAmplitude(action.amplitude)
                is SoundHapticAction.HapticDuration -> settingsRepository.setHapticStrengthMs(action.durationMs)
                is SoundHapticAction.Sound -> settingsRepository.setKeySound(action.on)
                is SoundHapticAction.SoundStyleChange -> settingsRepository.setKeySoundStyle(action.style)
                is SoundHapticAction.SoundVolume -> settingsRepository.setKeySoundVolume(action.volume)
            }
        }
        // Preview the result right away so the user can dial it in by feel.
        // The DataStore write above lands asynchronously, so previews pass
        // the new value explicitly instead of re-reading settings. The
        // player's debounce keeps slider drags from buzz-sawing the motor.
        val settings = _uiState.value.settings
        when (action) {
            is SoundHapticAction.Haptics -> if (action.on) {
                HapticPlayer.preview(
                    this, settings.hapticStyle, settings.hapticAmplitude, settings.hapticStrengthMs,
                    inputRootView,
                )
            }
            is SoundHapticAction.HapticStyleChange -> HapticPlayer.preview(
                this, action.style, settings.hapticAmplitude, settings.hapticStrengthMs, inputRootView,
            )
            is SoundHapticAction.HapticAmplitude -> HapticPlayer.preview(
                this, settings.hapticStyle, action.amplitude, settings.hapticStrengthMs, inputRootView,
            )
            is SoundHapticAction.HapticDuration -> HapticPlayer.preview(
                this, settings.hapticStyle, settings.hapticAmplitude, action.durationMs, inputRootView,
            )
            is SoundHapticAction.Sound -> if (action.on) playKeySound(force = true)
            is SoundHapticAction.SoundStyleChange -> playKeySound(style = action.style, force = true)
            is SoundHapticAction.SoundVolume -> playKeySound(volume = action.volume, force = true)
        }
    }

    private var weatherJob: Job? = null

    /**
     * Fetches current conditions when the weather panel opens; cached for
     * 15 minutes unless [force]d from the refresh button.
     */
    private fun refreshWeather(force: Boolean = false) {
        val settings = _uiState.value.settings
        val latitude = settings.weatherLatitude
        val longitude = settings.weatherLongitude
        if (latitude == null || longitude == null) {
            _uiState.update { it.copy(weather = WeatherUi.NoLocation) }
            return
        }
        val cached = (_uiState.value.weather as? WeatherUi.Ready)?.info
        if (!force && cached != null &&
            System.currentTimeMillis() - cached.fetchedAtMillis < weatherCacheMs()
        ) {
            return
        }
        weatherJob?.cancel()
        _uiState.update { it.copy(weather = WeatherUi.Loading) }
        weatherJob = serviceScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { WeatherClient.fetch(latitude, longitude) }.getOrNull()
            }
            _uiState.update {
                it.copy(weather = if (info != null) WeatherUi.Ready(info) else WeatherUi.Error)
            }
            // A weather chip may be sitting on the strip waiting for exactly
            // this — redraw it with the numbers (or drop it on an error).
            if (_uiState.value.smart?.pendingWeather == true) refreshSmartSuggestion()
        }
    }

    // ---- wikipedia tool ----

    private var wikiJob: Job? = null

    /** The results an open article came from, for the back arrow. */
    private var wikiLastResults: WikiUi.SearchResults? = null

    /**
     * The line a panel shows when a tool request fails. A [ToolHttpException]
     * holds a resource id and, when the provider sent one, its own words, so it
     * goes through [ToolHttp.friendlyMessage]; anything else keeps its own
     * message, or [fallbackRes] when it has none.
     */
    private fun requestErrorText(t: Throwable, @StringRes fallbackRes: Int): String =
        if (t is ToolHttpException) {
            ToolHttp.friendlyMessage(this, t)
        } else {
            t.message?.takeIf { it.isNotBlank() } ?: getString(fallbackRes)
        }

    private fun runWikiSearch(query: String) {
        if (query.isBlank()) return
        wikiJob?.cancel()
        _uiState.update { it.copy(wiki = WikiUi.Loading) }
        wikiJob = serviceScope.launch {
            val lang = _uiState.value.settings.wikiLanguage
            val result = withContext(Dispatchers.IO) {
                runCatching { WikipediaClient.search(query, lang) }
            }
            _uiState.update {
                it.copy(
                    wiki = result.fold(
                        onSuccess = { r -> WikiUi.SearchResults(r, query) },
                        onFailure = { e ->
                            WikiUi.Error(requestErrorText(e, R.string.ime_service_search_error))
                        },
                    ),
                )
            }
        }
    }

    /** Search result or article link tapped: load that article's summary. */
    fun onWikiOpen(title: String) {
        vibrate()
        (_uiState.value.wiki as? WikiUi.SearchResults)?.let { wikiLastResults = it }
        wikiJob?.cancel()
        _uiState.update { it.copy(wiki = WikiUi.Loading) }
        wikiJob = serviceScope.launch {
            val lang = _uiState.value.settings.wikiLanguage
            val result = withContext(Dispatchers.IO) {
                runCatching { WikipediaClient.summary(title, lang) }
            }
            _uiState.update {
                it.copy(
                    wiki = result.fold(
                        onSuccess = { s ->
                            WikiUi.Article(s, canGoBack = wikiLastResults != null)
                        },
                        onFailure = { e ->
                            WikiUi.Error(
                                requestErrorText(e, R.string.ime_service_wiki_article_error),
                            )
                        },
                    ),
                )
            }
        }
    }

    fun onWikiBack() {
        vibrate()
        _uiState.update { it.copy(wiki = wikiLastResults ?: WikiUi.Idle) }
    }

    /** Links tab opened for the first time: fetch the article's links. */
    fun onWikiLoadLinks() {
        val article = _uiState.value.wiki as? WikiUi.Article ?: return
        if (article.links != null || article.loadingExtra) return
        _uiState.update { it.copy(wiki = article.copy(loadingExtra = true)) }
        serviceScope.launch {
            val lang = _uiState.value.settings.wikiLanguage
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WikipediaClient.links(
                        article.summary.title,
                        lang,
                        _uiState.value.settings.toolLimits.wikiLinkLimit,
                    )
                }
            }
            _uiState.update { state ->
                val current = state.wiki as? WikiUi.Article ?: return@update state
                if (current.summary.title != article.summary.title) return@update state
                state.copy(
                    wiki = current.copy(
                        links = result.getOrDefault(emptyList()),
                        loadingExtra = false,
                    ),
                )
            }
        }
    }

    /** Full-article tab opened for the first time: fetch the plain text. */
    fun onWikiLoadFull() {
        val article = _uiState.value.wiki as? WikiUi.Article ?: return
        if (article.fullText != null || article.loadingExtra) return
        _uiState.update { it.copy(wiki = article.copy(loadingExtra = true)) }
        serviceScope.launch {
            val lang = _uiState.value.settings.wikiLanguage
            val result = withContext(Dispatchers.IO) {
                runCatching { WikipediaClient.fullText(article.summary.title, lang) }
            }
            _uiState.update { state ->
                val current = state.wiki as? WikiUi.Article ?: return@update state
                if (current.summary.title != article.summary.title) return@update state
                state.copy(
                    wiki = current.copy(
                        fullText = result.getOrDefault(""),
                        loadingExtra = false,
                    ),
                )
            }
        }
    }

    // ---- currency tool ----

    private var currencyJob: Job? = null

    /** The fiat table on its own, so coins can be re-merged without refetching it. */
    private var fiatRates: CurrencyClient.Rates? = null

    /** The coin table as the provider sent it, before the user's picks are applied. */
    private var cryptoRaw: Map<String, Double> = emptyMap()

    /**
     * Rates refresh at most every cache-TTL setting (they update daily
     * upstream anyway). Coins are a separate fetch on a separate, much
     * shorter clock, and only when something actually needs them — a user
     * who never types a ticker never touches the coin provider.
     */
    private fun refreshCurrencyRates(force: Boolean = false, wantCrypto: Boolean = false) {
        val settings = _uiState.value.settings
        val ready = _uiState.value.currency as? CurrencyUi.Ready
        val now = System.currentTimeMillis()
        val fiatTtlMs = settings.currencyCacheHours * 60L * 60L * 1000L
        val sources = settings.rateSources
        val cryptoTtlMs = sources.cryptoCacheMinutes * 60L * 1000L
        val needFiat = force || ready == null || now - ready.fetchedAtMs >= fiatTtlMs
        val needCrypto = sources.cryptoEnabled && (force || wantCrypto) &&
            (force || ready == null || now - ready.cryptoFetchedAtMs >= cryptoTtlMs)
        if (!needFiat && !needCrypto) return
        // Data saving, when the user has asked for it here: the rate table is
        // small enough to be allowed by default, and a refusal costs nothing
        // visible — whatever was last fetched stays on the panel and the chip
        // keeps converting with it.
        if (!dataSaverStatus.allows(MeteredFeature.CURRENCY_RATES)) return
        currencyJob?.cancel()
        // Only a fiat fetch with nothing cached blanks the panel: a coin
        // refresh must never take a working rate table off the screen.
        if (needFiat && ready == null) _uiState.update { it.copy(currency = CurrencyUi.Loading) }
        currencyJob = serviceScope.launch {
            val fiat = if (needFiat) {
                withContext(Dispatchers.IO) {
                    runCatching { CurrencyClient.fetchRates(sources.fiatProviders) }
                }
            } else {
                null
            }
            val coins = if (needCrypto) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        CurrencyClient.fetchCryptoRates(
                            sources.cryptoProviders,
                            CryptoCatalog.enabled(sources.cryptoTickers),
                        )
                    }
                }
            } else {
                null
            }
            fiat?.getOrNull()?.let { fiatRates = it }
            coins?.getOrNull()?.let { cryptoRaw = it }
            val merged = mergedRates()
            val stamp = System.currentTimeMillis()
            _uiState.update { state ->
                val previous = state.currency as? CurrencyUi.Ready
                val next = if (merged == null) {
                    CurrencyUi.Error(getString(R.string.ime_service_currency_error))
                } else {
                    CurrencyUi.Ready(
                        rates = merged,
                        // A failed fiat fetch keeps the old table and the old
                        // clock, so the next open tries again rather than
                        // dropping the panel back to an error.
                        fetchedAtMs = if (fiat?.isSuccess == true) stamp else previous?.fetchedAtMs ?: stamp,
                        // A failed coin fetch still stamps its clock: that is
                        // the backoff that stops a dead source being asked
                        // again on every keystroke.
                        cryptoFetchedAtMs = if (coins != null) stamp else previous?.cryptoFetchedAtMs ?: 0L,
                        cryptoFailed = coins?.isFailure == true,
                    )
                }
                state.copy(currency = next)
            }
            // A "150 usd" chip may be sitting on the strip waiting for these
            // rates to arrive before it can show an amount.
            if (_uiState.value.smart?.pending == true) refreshSmartSuggestion()
        }
    }

    /** The fiat table with the coins the user has turned on folded into it. */
    private fun mergedRates(): CurrencyClient.Rates? {
        val fiat = fiatRates ?: return null
        val sources = _uiState.value.settings.rateSources
        if (!sources.cryptoEnabled || cryptoRaw.isEmpty()) return fiat.copy(crypto = emptySet())
        return CurrencyClient.withCrypto(fiat, cryptoRaw, sources.cryptoTickers)
    }

    /** Re-applies the coin picks to the cached tables, with no network at all. */
    private fun remergeCurrencyRates() {
        val merged = mergedRates() ?: return
        _uiState.update { state ->
            val ready = state.currency as? CurrencyUi.Ready ?: return@update state
            if (ready.rates == merged) state else state.copy(currency = ready.copy(rates = merged))
        }
    }

    fun onCurrencyPairChange(from: String, to: String) {
        vibrate()
        serviceScope.launch { settingsRepository.setCurrencyPair(from, to) }
        val sources = _uiState.value.settings.rateSources
        if (sources.cryptoEnabled) {
            val coins = CryptoCatalog.enabled(sources.cryptoTickers)
            if (from in coins || to in coins) refreshCurrencyRates(wantCrypto = true)
        }
    }

    /** True when either side of the saved pair is a coin. */
    private fun pairHasCoin(): Boolean {
        val settings = _uiState.value.settings
        val sources = settings.rateSources
        if (!sources.cryptoEnabled) return false
        val coins = CryptoCatalog.enabled(sources.cryptoTickers)
        return settings.currencyFrom in coins || settings.currencyTo in coins
    }

    // ---- QR generator tool ----

    /** Renders the panel's typed QR content at the configured size and commits the PNG. */
    fun onQrSend() {
        val state = _uiState.value
        val content = state.mediaQuery
        if (content.isBlank()) return
        vibrate()
        serviceScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = QrCodeGen.bitmap(
                        content, state.settings.qrSizePx, state.settings.qrEcc.name,
                    ) ?: error("Too much text for one QR code")
                    val dir = File(cacheDir, "media").apply { mkdirs() }
                    val target = File(dir, "qr_${content.hashCode().toUInt()}.png")
                    target.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    target
                }.getOrNull()
            }
            if (file == null) {
                Toast.makeText(
                    this@WMKeyboardService,
                    getString(R.string.ime_service_qr_error),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            saveToGalleryIfEnabled(
                file,
                MediaMime.PNG,
                state.settings.qrSaveToGallery,
                "QR",
            )
            commitImageFile(file, MediaMime.PNG, state.settings.qrSendMode)
        }
    }

    /**
     * Copies a produced image into Pictures/WM Keyboard when the tool's
     * save option is on. Runs off the main thread; a failure only costs the
     * gallery copy, never the send that follows.
     */
    private fun saveToGalleryIfEnabled(
        file: File,
        mimeType: String,
        enabled: Boolean,
        namePrefix: String,
    ) {
        if (!enabled) return
        if (!GallerySaver.canSave(this)) {
            // Pre-Q needs WRITE_EXTERNAL_STORAGE, which an IME cannot ask
            // for itself — bounce through the trampoline and let the user
            // retry once it is granted.
            startActivity(
                Intent(this, StoragePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "${namePrefix}_$stamp.${MediaMime.extension(mimeType)}"
        serviceScope.launch(Dispatchers.IO) {
            val saved = GallerySaver.save(this@WMKeyboardService, file, mimeType, name) != null
            if (!saved) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WMKeyboardService,
                        getString(R.string.ime_service_gallery_save_error),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // ---- symbols / calculator / converter inserts ----

    /**
     * Modes panel: apply a mode manually (null = back to automatic). The
     * pick sticks for the current app and resets on the next app switch.
     */
    fun onModeSelect(id: String?) {
        vibrate()
        manualModeId = id
        val base = baseSettings ?: return
        val mode = resolveKeyboardMode(
            base.keyboardModes, currentPackage, currentModeFields, manualModeId,
        )
        _uiState.update {
            it.copy(
                settings = base.applyMode(mode),
                activeModeId = mode?.id,
                // The mode brings its own default set; drop the session pick.
                activeSymbolSetId = null,
            )
        }
    }

    /** Symbol row's picker chip: switch the visible set. */
    fun onSymbolSetSelect(id: String) {
        vibrate()
        val state = _uiState.value
        _uiState.update { it.copy(activeSymbolSetId = id) }
        // While a mode prescribes its own set list the pick is session-only —
        // it shouldn't rewrite the global row's default set.
        val modeSets = baseSettings?.keyboardModes
            ?.firstOrNull { it.id == state.activeModeId }?.symbolSetIds
        if (modeSets == null) {
            serviceScope.launch { settingsRepository.setSymbolRowActiveSet(id) }
        }
    }

    /** Symbol cell tapped: type it and remember it under Recents. */
    fun onSymbolInsert(symbol: String) {
        vibrate()
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        ic.commitText(symbol, 1)
        serviceScope.launch { settingsRepository.addSymbolRecent(symbol) }
    }

    /** Insert chip on the calculator/converter/generator panels. */
    fun onToolTextInsert(text: String) {
        if (text.isEmpty()) return
        vibrate()
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        ic.commitText(text, 1)
    }

    // ---- password generator tool ----

    /** Panel controls persist straight into settings (they're the defaults). */
    fun onPwSetting(action: PwSettingAction) {
        vibrate()
        serviceScope.launch {
            when (action) {
                is PwSettingAction.PassphraseMode -> settingsRepository.setPwPassphraseMode(action.on)
                is PwSettingAction.Length -> settingsRepository.setPwLength(action.value)
                is PwSettingAction.Upper -> settingsRepository.setPwUppercase(action.on)
                is PwSettingAction.Digits -> settingsRepository.setPwDigits(action.on)
                is PwSettingAction.Symbols -> settingsRepository.setPwSymbols(action.on)
                is PwSettingAction.ExcludeAmbiguous ->
                    settingsRepository.setPwExcludeAmbiguous(action.on)
                is PwSettingAction.Words -> settingsRepository.setPpWordCount(action.value)
                is PwSettingAction.Separator -> settingsRepository.setPpSeparator(action.value)
                is PwSettingAction.Capitalize -> settingsRepository.setPpCapitalize(action.on)
                is PwSettingAction.IncludeDigit -> settingsRepository.setPpIncludeDigit(action.on)
            }
        }
    }

    /**
     * The backspace swipe's four calls, bundled for the reason
     * [converterCallbacks] is: [ServiceKeyboardContent] is at the JVM's 64K
     * ceiling, so the gesture had to grow from one callback to four without
     * costing a parameter.
     */
    private val deleteSwipeCallbacks by lazy {
        com.wasimaster.wmkeyboard.ime.ui.DeleteSwipeCallbacks(
            onDeleteUnit = ::onDeleteSwipeUnit,
            onSelect = ::onDeleteSwipeSelect,
            onCommit = ::onDeleteSwipeCommit,
            onCancel = ::onDeleteSwipeCancel,
        )
    }

    // ---- calculator tool ----

    /**
     * One stable bundle for the calculator/converter panels, built outside
     * [ServiceKeyboardContent] — that method sits against the JVM's 64K
     * size ceiling, and six inline lambdas at the call site put it over.
     */
    private val converterCallbacks by lazy {
        com.wasimaster.wmkeyboard.ime.ui.ConverterCallbacks(
            onCalcEdit = ::onCalcExpressionChange,
            onCalcToggleDegrees = ::onCalcDegreesToggle,
            onConverterEdit = ::onConverterValueChange,
            // Selection memory, not a user action — persist silently.
            onUnitSelection = { selection ->
                serviceScope.launch { settingsRepository.setUnitConvertLast(selection) }
            },
            onCurrencyPairChange = ::onCurrencyPairChange,
            onCurrencyRefresh = { refreshCurrencyRates(force = true) },
        )
    }

    /** The panel's deg/rad chip — same persisted setting as the tool's page. */
    fun onCalcDegreesToggle() {
        vibrate()
        val next = !_uiState.value.settings.calcDegrees
        serviceScope.launch { settingsRepository.setCalcDegrees(next) }
    }

    /**
     * Edits to the calculator's expression, from the soft keypad and the
     * physical keys alike. The ring is dropped on every edit, the same rule as
     * [updateQuery]: the Insert chip appears and disappears with the result's
     * validity, and a stale index would activate the wrong thing.
     */
    private fun calcEdit(transform: (String) -> String) {
        _uiState.update {
            it.copy(calcExpression = transform(it.calcExpression), panelFocus = null)
        }
    }

    fun onCalcExpressionChange(value: String) {
        calcEdit { value }
    }

    /**
     * The calculator engine's spellings for the keys a physical keyboard has:
     * ASCII operators arrive as the keypad's typographic ones. Everything else
     * passes through — `sin(`, digits, brackets — and a genuinely wrong
     * character surfaces in the display's own error line, where it can be seen
     * and backspaced, rather than being silently eaten.
     */
    private fun mapCalcChars(text: String): String =
        text.map { c ->
            when (c) {
                '*' -> '×'
                '/' -> '÷'
                '-' -> '−'
                else -> c
            }
        }.joinToString("")

    /** The converters accept digits and one dot, capped like their keypad. */
    private fun appendConverterDigits(current: String, text: String): String {
        var value = current
        for (c in text) {
            value = when {
                c.isDigit() -> (value + c).take(CONVERTER_VALUE_MAX)
                c == '.' && '.' !in value -> value + c
                else -> value
            }
        }
        return value
    }

    private fun converterEdit(transform: (String) -> String) {
        _uiState.update {
            it.copy(converterValue = transform(it.converterValue), panelFocus = null)
        }
    }

    fun onConverterValueChange(value: String) {
        converterEdit { value }
    }

    /** Enter in the calculator is the `=` key: collapse to the result. */
    private fun calcEvaluate() {
        val state = _uiState.value
        val expression = state.calcExpression
        if (expression.isBlank()) return
        val result = runCatching {
            CalcEngine.format(
                CalcEngine.evaluate(expression, state.settings.calcDegrees),
                state.settings.calcPrecision,
            )
        }
        result.onSuccess { collapsed -> calcEdit { collapsed } }
    }

    // ---- plugins ----

    /** Cap on one plugin text box, so a paste cannot be unbounded. */
    private val PLUGIN_INPUT_MAX = 8 * 1024

    /**
     * The Lua runtime, created the first time the Plugins panel opens.
     *
     * Callbacks come back on the plugin thread, so every one of them is posted
     * to [serviceScope] before it touches state — the keyboard's UI is only ever
     * built on the main thread, and a plugin must never be able to change that.
     */
    private var pluginRuntime: PluginRuntime? = null

    private fun requirePluginRuntime(): PluginRuntime =
        pluginRuntime ?: PluginRuntime(
            store = PluginStore.get(this),
            listener = pluginListener,
            post = { body -> serviceScope.launch { body() } },
        ).also { pluginRuntime = it }

    private val pluginListener = object : PluginRuntime.Listener {
        override fun onUi(pluginId: String, ui: RenderedUi) {
            _uiState.update { state ->
                val running = state.plugins as? PluginPanelUi.Running ?: return@update state
                if (running.plugin.id != pluginId) return@update state
                // Buffers for boxes the plugin no longer draws are dropped, so a
                // renamed widget cannot leave typed text hanging around.
                val live = ui.inputIds()
                state.copy(
                    plugins = running.copy(ui = ui, error = null),
                    pluginInputs = state.pluginInputs.filterKeys { it in live },
                    pluginFocusedInput = state.pluginFocusedInput?.takeIf { it in live },
                )
            }
        }

        override fun onBusy(pluginId: String, busy: Boolean) {
            _uiState.update { state ->
                val running = state.plugins as? PluginPanelUi.Running ?: return@update state
                if (running.plugin.id != pluginId) state else state.copy(plugins = running.copy(busy = busy))
            }
        }

        override fun onError(pluginId: String, message: PluginText) {
            // The runtime has no Context, so it hands the reason over as a
            // resource id. The service is a Context, so it does the wording.
            val text = message.resolve(this@WMKeyboardService)
            _uiState.update { state ->
                val running = state.plugins as? PluginPanelUi.Running ?: return@update state
                if (running.plugin.id != pluginId) state else state.copy(plugins = running.copy(error = text))
            }
        }

        override fun onInputWrite(pluginId: String, inputId: String, text: String) {
            _uiState.update { it.copy(pluginInputs = it.pluginInputs + (inputId to text)) }
        }

        override fun onStopped(pluginId: String, message: PluginText, disabled: Boolean) {
            // Back to the list, with the reason. Anything the user was typing
            // into the stopped plugin goes with it.
            val text = message.resolve(this@WMKeyboardService)
            _uiState.update {
                it.copy(
                    plugins = PluginPanelUi.List(PluginStore.get(this@WMKeyboardService).plugins(), text),
                    pluginInputs = emptyMap(),
                    pluginFocusedInput = null,
                )
            }
        }
    }

    /** Shows the installed list. The panel's home screen. */
    private fun openPluginList() {
        pluginRuntime?.close()
        val store = PluginStore.get(this)
        store.reconcile()
        // The subsystem is off until the user asks for it, so the panel says so
        // rather than looking like an empty feature.
        // runnablePlugins, not plugins: a plugin switched off is one the user
        // asked not to see here, and the runtime refuses to open it anyway — so
        // listing it only offers a row that leads to "That plugin is turned off."
        val runnable = if (store.subsystemEnabled()) store.runnablePlugins() else emptyList()
        val notice = when {
            !store.subsystemEnabled() ->
                getString(R.string.ime_service_plugins_off_notice)
            // Otherwise the empty state would read "No plugins yet" at someone
            // who has several and switched them all off.
            runnable.isEmpty() && store.plugins().isNotEmpty() ->
                getString(R.string.ime_service_plugins_all_off_notice)
            else -> null
        }
        _uiState.update {
            it.copy(
                plugins = PluginPanelUi.List(
                    runnable,
                    notice,
                ),
                pluginInputs = emptyMap(),
                pluginFocusedInput = null,
            )
        }
    }

    /**
     * Leaves the running plugin for the installed list — the panel header's
     * back button one level in. Closing the runtime is [openPluginList]'s
     * job, so a plugin never keeps running behind the list.
     */
    fun onPluginBack() {
        vibrate()
        openPluginList()
    }

    /** Loads and runs a plugin, replacing whatever was open. */
    fun onPluginOpen(pluginId: String) {
        vibrate()
        val store = PluginStore.get(this)
        if (!store.subsystemEnabled()) return openPluginList()
        val plugin = store.plugin(pluginId) ?: return openPluginList()
        _uiState.update {
            it.copy(
                plugins = PluginPanelUi.Running(plugin),
                pluginInputs = emptyMap(),
                pluginFocusedInput = null,
            )
        }
        requirePluginRuntime().open(pluginId)
    }

    /** Hands a tap, toggle or tab change to the running script. */
    fun onPluginEvent(event: PluginEvent) {
        vibrate()
        pluginRuntime?.dispatch(event)
    }

    /**
     * Points the keys at one of the plugin's text boxes, or back at the field.
     *
     * The one switch that decides where what the user types goes, so it is
     * deliberately the only way [KeyboardUiState.pluginFocusedInput] is ever
     * set, and it refuses while anything but a plugin is on screen.
     */
    fun onPluginInputFocus(inputId: String?) {
        vibrate()
        _uiState.update { state ->
            if (inputId != null && state.plugins !is PluginPanelUi.Running) return@update state
            state.copy(pluginFocusedInput = inputId)
        }
    }

    /**
     * Puts the clipboard's text into one of the plugin's boxes.
     *
     * This is how a plugin gets text the user already had, and it is a host
     * action rather than an API: the user taps Paste, sees exactly what landed,
     * and the plugin is told the contents of its own box. Nothing here is
     * reachable from Lua.
     */
    fun onPluginPaste(inputId: String) {
        if (!isClipboardAccessible()) return
        vibrate()
        val clip = clipboardStore.latestText().orEmpty()
        if (clip.isEmpty()) return
        pluginInputSet(inputId, clip.take(PLUGIN_INPUT_MAX))
    }

    /** Copies a plugin's output to the clipboard. */
    fun onPluginCopy(text: String) {
        vibrate()
        if (text.isEmpty()) return
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("", text))
        }
    }

    /**
     * Ends any plugin session and takes the keys back.
     *
     * Called when the keyboard leaves the screen and when the focused field
     * changes. Both matter: a plugin input that stayed focused across either one
     * would send the user's next sentence to a script instead of to their app,
     * which is the single failure this whole design exists to prevent.
     */
    private fun stopPlugins() {
        pluginRuntime?.close()
        if (_uiState.value.pluginFocusedInput == null && _uiState.value.pluginInputs.isEmpty()) return
        _uiState.update { it.copy(pluginFocusedInput = null, pluginInputs = emptyMap()) }
    }

    private fun pluginInputEdit(transform: (String) -> String) {
        val id = _uiState.value.pluginFocusedInput ?: return
        val current = _uiState.value.pluginInputs[id].orEmpty()
        pluginInputSet(id, transform(current).take(PLUGIN_INPUT_MAX))
    }

    private fun pluginInputSet(id: String, text: String) {
        _uiState.update { it.copy(pluginInputs = it.pluginInputs + (id to text)) }
        pluginRuntime?.dispatch(PluginEvent.InputChanged(id, text))
    }

    // ---- typing speed test ----

    /**
     * Drives the elapsed clock and the once-a-second sampler while a run is
     * live. The panel renders [TypingTestUi.elapsedMs] rather than reading
     * the system clock itself, so a recomposition can never disagree with
     * the score.
     */
    private var typingTestJob: Job? = null

    /** Deals the prompt off the main thread; see [startTypingTest]. */
    private var typingPromptJob: Job? = null

    /** Identifies the latest deal, so a slow one cannot land on a newer run. */
    private var typingDealSeq = 0

    /** The debounced suggestion lookup for the word being typed. */
    private var typingSuggestJob: Job? = null

    /**
     * The last prompt pool built out of a dictionary, keyed by language id
     * and the dictionary state it was built from. One entry is enough: the
     * user runs tests in one language at a time, and a download or import
     * changes the token and so rebuilds it.
     */
    private var typingPoolCache: Triple<String, Int, TypingWordPool?>? = null

    /**
     * Deals a fresh prompt from the current settings and arms the run.
     *
     * The prompt is dealt in the language the keyboard is in — the user types
     * it on the keys on screen, so there is no other language it could be in.
     * English and Bengali ship their word lists; every other language builds
     * its pool out of its dictionary, which is a walk over a memory-mapped
     * trie and so happens off the main thread. The panel shows an empty
     * prompt for the beat that takes, and nothing typed in that beat counts.
     */
    private fun startTypingTest() {
        typingTestJob?.cancel()
        typingTestJob = null
        typingPromptJob?.cancel()
        typingSuggestJob?.cancel()
        val state = _uiState.value
        val language = state.language
        // A conversion layout (pinyin, kana) spells readings the prompt's
        // characters cannot be compared against keystroke by keystroke, so
        // the test stands down rather than scoring every key as a miss.
        val converts = state.composer.isConversion
        // Force shift off: the prompt is lowercase, and a field's auto-cap
        // would otherwise uppercase the first keystroke into a miss.
        _uiState.update {
            it.copy(
                typingTest = TypingTestUi(languageId = language.id, unavailable = converts),
                shiftState = ShiftState.OFF,
                shiftPressedByUser = false,
            )
        }
        if (converts) return
        val options = state.settings.typingTest
        val digits = resolveNumeralDigits(
            state.settings.layoutBehavior.numeralSystemFor(language.id),
            language,
        )
        val fullStop = state.script.fullStop
        val seq = ++typingDealSeq
        typingPromptJob = serviceScope.launch {
            val pool = withContext(Dispatchers.Default) { typingWordPool(language.id) }
            val words = pool?.let {
                buildTypingPrompt(
                    mode = options.mode,
                    duration = options.duration,
                    wordCount = options.wordCount,
                    punctuation = options.punctuation,
                    numbers = options.numbers,
                    pool = it,
                    digits = digits,
                    fullStop = fullStop,
                )
            }.orEmpty()
            if (seq != typingDealSeq) return@launch
            _uiState.update { s ->
                if (s.typingTest.result != null) {
                    s
                } else {
                    s.copy(typingTest = s.typingTest.copy(words = words, unavailable = words.isEmpty()))
                }
            }
            refreshTypingSuggestions()
        }
    }

    /**
     * The prompt material for [languageId]: the shipped list where there is
     * one, otherwise the most common words of the language's downloaded and
     * imported dictionaries — or null when there is nothing to deal from.
     * Runs off the main thread; the dictionary walk is the expensive part.
     */
    private fun typingWordPool(languageId: String): TypingWordPool? {
        TypingWordPools.bundled(languageId)?.let { return it }
        val token = loadedDictToken
        typingPoolCache?.let { (id, cachedToken, pool) ->
            if (id == languageId && cachedToken == token) return pool
        }
        val words = customDictionaries[languageId]
            ?.topWords(TypingWordPools.DICTIONARY_POOL_SIZE, accept = TypingWordPools::acceptsPromptWord)
            .orEmpty()
        val pool = if (words.size >= TypingWordPools.DICTIONARY_POOL_MIN) TypingWordPool(words) else null
        typingPoolCache = Triple(languageId, token, pool)
        return pool
    }

    /**
     * What the raw keystrokes of a test word read as on the current layout:
     * the composed text on a transliterating one (Avro spells Bengali out of
     * Latin keys), the keystrokes themselves everywhere else.
     */
    private fun typingComposed(state: KeyboardUiState, buffer: String): String =
        if (state.composer.isTransliterating) state.composer.composeBuffer(buffer) else buffer

    /** Cancels the clock; used when the panel closes mid-run. */
    private fun stopTypingTest() {
        typingTestJob?.cancel()
        typingTestJob = null
        typingPromptJob?.cancel()
        typingPromptJob = null
        typingSuggestJob?.cancel()
        typingSuggestJob = null
    }

    /**
     * Starts the clock on the first keystroke — not when the panel opens.
     * Otherwise the seconds spent reading the prompt would count against
     * the score.
     */
    private fun armTypingClock() {
        if (typingTestJob != null) return
        val startedAt = System.currentTimeMillis()
        _uiState.update { it.copy(typingTest = it.typingTest.copy(startedAtMs = startedAt)) }
        typingTestJob = serviceScope.launch {
            var nextSecond = 1
            while (isActive) {
                delay(100)
                val state = _uiState.value
                val test = state.typingTest
                if (state.panel != PanelMode.TYPING_TEST || test.result != null) return@launch
                val elapsed = System.currentTimeMillis() - startedAt
                val limit = state.settings.typingTest.duration * 1000L
                val timed = state.settings.typingTest.mode == TypingTestMode.TIME
                val capped = if (timed) elapsed.coerceAtMost(limit) else elapsed

                // One sample per whole second, catching up if a frame was
                // dropped, so the result graph never has holes in it.
                val samples = test.samples.toMutableList()
                while (capped >= nextSecond * 1000L) {
                    samples += typingSample(test, nextSecond)
                    nextSecond++
                }
                _uiState.update {
                    it.copy(typingTest = it.typingTest.copy(elapsedMs = capped, samples = samples))
                }
                if (timed && elapsed >= limit) {
                    finishTypingTest()
                    return@launch
                }
            }
        }
    }

    /**
     * A speed reading for the second that just ended: cumulative correct
     * characters over cumulative time. Cumulative rather than per-interval
     * because a one-second window is too short to be anything but noise.
     */
    private fun typingSample(test: TypingTestUi, second: Int): WpmSample {
        var correct = 0
        var typedWrong = 0
        var missed = 0
        for (word in test.typedWords) {
            for (state in compareWord(word.expected, word.typed, live = false)) {
                when (state) {
                    CharState.CORRECT -> correct++
                    CharState.WRONG, CharState.EXTRA -> typedWrong++
                    CharState.MISSING -> missed++
                    CharState.PENDING -> Unit
                }
            }
            if (word.typed == word.expected) correct++
        }
        val minutes = second / 60.0
        // Raw counts characters that ended up in the prompt, the same set
        // scoreTypingTest measures. Using the keystroke counter instead
        // would include corrected typing and leave the graph disagreeing
        // with the headline figure it sits under.
        val typed = correct + typedWrong
        return WpmSample(
            second = second,
            wpm = if (minutes > 0) (correct / 5.0) / minutes else 0.0,
            raw = if (minutes > 0) (typed / 5.0) / minutes else 0.0,
            errors = typedWrong + missed,
        )
    }

    /** One character key, scored against the letter the prompt expects. */
    private fun typingTestType(text: String) {
        if (text.isEmpty()) return
        // Nothing to type against yet: the prompt is still being dealt, or
        // the language has none. The keystroke is dropped rather than scored.
        if (_uiState.value.typingTest.words.isEmpty()) return
        armTypingClock()
        _uiState.update { state ->
            val test = state.typingTest
            val expected = test.words.getOrNull(test.wordIndex).orEmpty()
            val buffer = test.buffer + text
            val current = typingComposed(state, buffer)
            // Right first time only if it lands on the position it was typed
            // at; anything past the end of the word is an overshoot.
            //
            // A transliterating layout cannot be scored that way, and was:
            // one keystroke reshapes the letter before it (k then h is খ, not
            // কh; kkh is ক্ষ and neither of its first two keys spells any part
            // of it), so asking whether the composed text is already on course
            // marked the first key of every aspirate and every conjunct wrong.
            // On Bengali that is most of the word. The keys are held here and
            // judged in typingTestSpace, once the word they were building is
            // whole.
            val pending = state.composer.isTransliterating
            val hit = !pending && expected.getOrNull(test.current.length)?.toString() == text
            state.copy(
                typingTest = test.copy(
                    buffer = buffer,
                    current = current,
                    totalKeystrokes = test.totalKeystrokes + 1,
                    correctKeystrokes = test.correctKeystrokes + if (hit) 1 else 0,
                    pendingKeystrokes = if (pending) test.pendingKeystrokes + 1 else 0,
                ),
            )
        }
        refreshTypingSuggestions()
        finishIfPromptTyped()
    }

    /**
     * Word and quote runs finish on the last word without waiting for a
     * trailing space: once its final letter lands and the word matches,
     * there is nothing left to type. The closing space never counted for
     * the last word anyway (scoreTypingTest only credits it for earlier
     * words), so ending here costs the run nothing.
     */
    private fun finishIfPromptTyped() {
        val state = _uiState.value
        val test = state.typingTest
        if (state.settings.typingTest.mode != TypingTestMode.TIME &&
            test.wordIndex == test.words.lastIndex &&
            test.current == test.words.getOrNull(test.wordIndex)
        ) {
            finishTypingTest()
        }
    }

    /**
     * Backspace inside the current word. It deliberately does not walk back
     * into a finished word: reopening one would mean re-scoring keystrokes
     * already counted, and the accuracy figure is meant to remember the
     * mistakes rather than let them be edited away.
     *
     * Removes one keystroke, not one composed character: on Avro that is
     * how backspace behaves in a field too, and it is what lets খ go back
     * to ক rather than to nothing.
     */
    private fun typingTestBackspace() {
        _uiState.update { state ->
            val test = state.typingTest
            if (test.buffer.isEmpty()) {
                state
            } else {
                val buffer = test.buffer.dropLast(1)
                state.copy(
                    typingTest = test.copy(buffer = buffer, current = typingComposed(state, buffer)),
                )
            }
        }
        refreshTypingSuggestions()
    }

    /** Space closes the current word and moves the caret to the next one. */
    private fun typingTestSpace() {
        val state = _uiState.value
        val test = state.typingTest
        // Leading spaces would silently score an empty word as wrong.
        if (test.current.isEmpty()) return
        armTypingClock()

        val expected = test.words.getOrNull(test.wordIndex).orEmpty()
        // The space is a correct keystroke only when it closed a word that
        // was actually right — matching how scoreTypingTest credits it.
        // Comparing lengths instead would score "teh" for "the" as a hit.
        val hit = test.current == expected
        // A transliterating layout's keys were held rather than scored as
        // they landed; the word is whole now, so they settle here.
        val settled = settledKeystrokes(
            expected = expected,
            composed = test.current,
            standing = test.buffer.length.coerceAtMost(test.pendingKeystrokes),
        )
        val typedWords = test.typedWords + TypedWord(expected, test.current)
        _uiState.update {
            it.copy(
                typingTest = it.typingTest.copy(
                    typedWords = typedWords,
                    current = "",
                    buffer = "",
                    pendingKeystrokes = 0,
                    totalKeystrokes = it.typingTest.totalKeystrokes + 1,
                    correctKeystrokes = it.typingTest.correctKeystrokes + settled +
                        if (hit) 1 else 0,
                ),
            )
        }
        // Word and quote runs end on the last word rather than on a clock.
        if (state.settings.typingTest.mode != TypingTestMode.TIME &&
            typedWords.size >= test.words.size
        ) {
            finishTypingTest()
        } else {
            refreshTypingSuggestions()
        }
    }

    /**
     * A whole word landing at once — a glide, or a suggestion chip — and the
     * space that follows it, the same as the gesture or the chip would give
     * a field. Scored as [keystrokes] presses (one per letter for a glide,
     * one tap for a chip), credited in proportion to how many of the word's
     * letters match the prompt's, so a glide that decoded to the wrong word
     * costs what mistyping it would have.
     *
     * A half-tapped word is closed first, which is what happens in a field
     * too: the glide's leading space ends whatever was being typed.
     */
    private fun typingTestWholeWord(word: String, keystrokes: Int) {
        if (word.isEmpty() || keystrokes <= 0) return
        if (_uiState.value.typingTest.words.isEmpty()) return
        if (_uiState.value.typingTest.current.isNotEmpty()) typingTestSpace()
        if (!_uiState.value.typingTestActive) return
        armTypingClock()
        _uiState.update { state ->
            val test = state.typingTest
            val expected = test.words.getOrNull(test.wordIndex).orEmpty()
            val matched = word.indices.count { expected.getOrNull(it) == word[it] }
            val hits = keystrokes * matched / word.length
            state.copy(
                typingTest = test.copy(
                    buffer = word,
                    current = word,
                    totalKeystrokes = test.totalKeystrokes + keystrokes,
                    correctKeystrokes = test.correctKeystrokes + hits,
                ),
            )
        }
        typingTestSpace()
    }

    /**
     * A glide drawn during a run, when the test's glide option is on. Decoded
     * exactly as a field glide is — same engine, same word list — and then
     * typed into the test as a whole word. A multi-word stroke lands one
     * word per segment. [chosen] is the ambiguity picker's answer for a
     * single-word stroke.
     */
    private fun typingTestGlide(
        segments: List<List<GesturePoint>>,
        keys: List<KeyCenter>,
        keyWidthPx: Float,
        chosen: String?,
    ) {
        // Retires every preview from this stroke, in flight or queued.
        gestureGeneration.incrementAndGet()
        gestureJob?.cancel()
        _uiState.update { it.copy(glideWord = null, glideChoices = emptyList()) }
        gestureJob = serviceScope.launch {
            for ((index, segment) in segments.withIndex()) {
                val candidates = withContext(Dispatchers.Default) {
                    glideDecode(segment, keys, keyWidthPx)
                }.words
                if (candidates.isEmpty()) continue
                val word = if (index == 0 && segments.size == 1) {
                    chosen?.takeIf { it in candidates } ?: candidates.first()
                } else {
                    candidates.first()
                }
                typingTestWholeWord(word, keystrokes = word.length)
            }
        }
    }

    /**
     * Refreshes the test's own suggestion row for the word being typed, or
     * the next-word predictions between words. The same engine and the same
     * lookup the strip uses, so the test measures the real thing; only the
     * destination differs, because the strip belongs to the field and this
     * word must never reach it.
     */
    private fun refreshTypingSuggestions() {
        val state = _uiState.value
        val test = state.typingTest
        typingSuggestJob?.cancel()
        if (!state.typingTestActive || !state.settings.typingTest.suggestions || test.words.isEmpty()) {
            if (test.suggestions.isNotEmpty()) {
                _uiState.update { it.copy(typingTest = it.typingTest.copy(suggestions = emptyList())) }
            }
            return
        }
        val engine = suggestionEngine ?: return
        val avro = state.composer.isBengaliPhonetic
        // The engine takes the romanised keystrokes on Avro and the word
        // itself everywhere else — the same split the field path makes.
        val typed = if (avro) test.buffer else test.current
        val previous = test.typedWords.lastOrNull()?.typed
        val previous2 = test.typedWords.getOrNull(test.typedWords.size - 2)?.typed
        val wordIndex = test.wordIndex
        val buffer = test.buffer
        val suggestionSlots = state.settings.suggestionStrip.slotCount
            .coerceAtLeast(TYPING_TEST_SUGGESTION_SLOTS)
        typingSuggestJob = serviceScope.launch {
            delay(TYPING_TEST_SUGGEST_DEBOUNCE_MS)
            val words = withContext(Dispatchers.Default) {
                engine.suggest(
                    composing = typed,
                    previousWord = previous,
                    avroMode = avro,
                    limit = suggestionSlots,
                    previousWord2 = previous2,
                )
            }
            _uiState.update { s ->
                val now = s.typingTest
                // Only for the word it was asked about.
                if (now.wordIndex != wordIndex || now.buffer != buffer || now.result != null) {
                    s
                } else {
                    s.copy(typingTest = now.copy(suggestions = words))
                }
            }
        }
    }

    /**
     * Scores the run, stores the result, and files it against the personal
     * bests. A run with no keystrokes is thrown away instead of recorded —
     * an accidental panel open should not land a zero in the history.
     */
    private fun finishTypingTest() {
        typingTestJob?.cancel()
        typingTestJob = null
        typingSuggestJob?.cancel()
        val state = _uiState.value
        val test = state.typingTest
        if (test.result != null) return

        // Whatever is half-typed still counts; the clock stopped mid-word.
        val unfinished = test.words.getOrNull(test.wordIndex).orEmpty()
        val words = if (test.current.isEmpty()) {
            test.typedWords
        } else {
            test.typedWords + TypedWord(unfinished, test.current)
        }
        // …and so do the keystrokes a transliterating layout was still
        // holding for it: without this an Avro run that ran out the clock
        // mid-word threw that word's keys away as misses.
        val correctKeystrokes = test.correctKeystrokes + settledKeystrokes(
            expected = unfinished,
            composed = test.current,
            standing = test.buffer.length.coerceAtMost(test.pendingKeystrokes),
        )
        val elapsed = test.startedAtMs?.let { test.elapsedMs.coerceAtLeast(1) } ?: 0L
        if (words.isEmpty() || elapsed <= 0) {
            _uiState.update { it.copy(panel = PanelMode.NONE, typingTest = TypingTestUi()) }
            return
        }

        val options = state.settings.typingTest
        val configKey = typingConfigKey(
            options.mode, options.duration, options.wordCount, test.languageId,
        )
        val result = scoreTypingTest(
            words = words,
            elapsedMs = elapsed,
            totalKeystrokes = test.totalKeystrokes,
            correctKeystrokes = correctKeystrokes,
            samples = test.samples,
            mode = options.mode,
            configKey = configKey,
        )
        val improved = TypingBests.improve(options.bests, configKey, result.wpm)
        // Badges the run earns; only the never-before-seen ones get the "New"
        // accent on the results screen. The prompt text decides the pangram.
        val earned = TypingAchievements.evaluate(
            result,
            testsCompleted = options.completed + 1,
            isPangram = TypingAchievements.isPangram(test.words.joinToString(" ")),
        )
        val newBadges = earned - TypingAchievements.decode(options.achievements)
        _uiState.update {
            it.copy(
                typingTest = it.typingTest.copy(
                    result = result,
                    personalBest = improved != null,
                    earnedAchievements = newBadges,
                    suggestions = emptyList(),
                ),
            )
        }
        serviceScope.launch {
            settingsRepository.recordTypingResult(
                history = TypingHistory.append(options.history, result.wpm),
                bests = improved?.let { TypingBests.encode(it) },
                achievements = earned,
            )
        }
    }

    /** Panel controls. Everything here persists — the panel is the settings. */
    fun onTypingTestAction(action: TypingTestAction) {
        vibrate()
        when (action) {
            TypingTestAction.Restart -> {
                startTypingTest()
                return
            }
            TypingTestAction.InsertResult -> {
                val result = _uiState.value.typingTest.result ?: return
                onToolTextInsert(typingResultText(result))
                // Closing the panel puts the user back in the field they
                // just wrote the score into.
                onPanelChange(PanelMode.TYPING_TEST)
                return
            }
            is TypingTestAction.Suggestion -> {
                typingTestWholeWord(action.word, keystrokes = 1)
                return
            }
            is TypingTestAction.Language -> {
                // The layout switch re-deals the prompt itself (see
                // onLayoutSelected): the test follows the keyboard's
                // language, and this is the same switch the spacebar makes.
                onLayoutSelected(action.layoutId)
                return
            }
            else -> Unit
        }
        // A settings change invalidates the prompt in front of the user, so
        // persist first and re-deal from the saved value rather than racing
        // the settings flow back into the panel.
        serviceScope.launch {
            when (action) {
                is TypingTestAction.Mode -> settingsRepository.setTypingTestMode(action.value)
                is TypingTestAction.Duration -> settingsRepository.setTypingTestDuration(action.seconds)
                is TypingTestAction.WordCount -> settingsRepository.setTypingTestWordCount(action.value)
                is TypingTestAction.Punctuation -> settingsRepository.setTypingTestPunctuation(action.on)
                is TypingTestAction.Numbers -> settingsRepository.setTypingTestNumbers(action.on)
                is TypingTestAction.Glide -> settingsRepository.setTypingTestGlide(action.on)
                is TypingTestAction.Suggestions -> settingsRepository.setTypingTestSuggestions(action.on)
                else -> return@launch
            }
            // settingsRepository.settings has already pushed the new value
            // into _uiState by the time the edit completes.
            settingsRepository.settings.first()
            startTypingTest()
        }
    }

    /** The shareable one-liner the "Insert" chip writes into the field. */
    private fun typingResultText(result: TypingResult): String {
        val state = _uiState.value
        val options = state.settings.typingTest
        var config = typingConfigLabel(this, result.mode, options.duration, options.wordCount)
        // English is the test's home language and goes unnamed, as in the
        // personal-best keys; any other language is part of the score.
        val languageId = state.typingTest.languageId
        if (languageId != "en") {
            config = getString(
                R.string.ime_service_typing_config_language,
                config,
                LanguageRegistry.byId(languageId).displayName,
            )
        }
        return getString(
            R.string.ime_service_typing_result_text,
            result.wpm.roundToInt(),
            result.accuracy.roundToInt(),
            config,
        )
    }

    // ---- AI tool ----

    private var aiJob: Job? = null

    /**
     * Identifies the latest [runAi] call. On-device streaming callbacks
     * arrive from a blocking native call that outlives job cancellation, so
     * stale runs must be ignored rather than relied on to stop.
     */
    private var aiRunSeq = 0

    /**
     * The last instruction each ask-each-run action was given, so reopening its
     * input box starts from what the user typed last time.
     *
     * Only a prefill. What a run *used* rides on the [AiUi] state itself, which
     * is what makes a retry rebuild the right prompt: holding it here as well
     * used to mean that running any action after a Custom generate left the
     * generate flag set, and retrying then rebuilt the wrong prompt entirely.
     */
    private val aiLastInstruction = mutableMapOf<String, String>()

    /**
     * The on-device model to run: the explicit selection, or — when nothing
     * (valid) is selected — the only model on disk. So the first download
     * just works without a selection step, and a deleted selection heals
     * itself while a sole alternative exists.
     */
    private fun effectiveLocalModelId(settings: KeyboardSettings): String? =
        settings.ai.localModelId
            .takeIf { LocalLlmStore.selectedModelFile(filesDir, it) != null }
            ?: LocalLlmStore.soleDownloadedId(filesDir)

    private fun effectiveLocalModelFile(settings: KeyboardSettings): java.io.File? =
        effectiveLocalModelId(settings)?.let { LocalLlmStore.selectedModelFile(filesDir, it) }

    /**
     * Whether the model streams Qwen3-style implicit reasoning (no opening
     * tag, just bare thought ending in `</think>`). Catalog models declare
     * it; imported files fall back to a name sniff.
     */
    private fun isReasoningModel(modelId: String?): Boolean {
        if (modelId == null) return false
        LocalLlmCatalog.byId(modelId)?.let { return it.reasoning }
        val name = modelId.removePrefix(LocalLlmStore.CUSTOM_PREFIX).lowercase()
        return "qwen3" in name || "deepseek" in name
    }

    /**
     * Is there anything for an AI action to run on — a selection, or any text
     * in the field? Drives [KeyboardUiState.aiHasText], which greys out the
     * action chips; an action on an empty field has nothing to rewrite and
     * would only make the model invent something.
     */
    private fun aiFieldHasText(): Boolean {
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrBlank()) return true
        return extractFieldText().isNotBlank()
    }

    /**
     * Re-reads the field for [KeyboardUiState.aiHasText]. Called when the AI
     * panel opens and on every cursor/text change while it is open — typing
     * the first character has to enable the chips without a panel reopen.
     */
    private fun refreshAiHasText() {
        val hasText = aiFieldHasText()
        _uiState.update { if (it.aiHasText == hasText) it else it.copy(aiHasText = hasText) }
    }

    /** What the AI panel should show before any action runs. */
    private fun aiInitialState(settings: KeyboardSettings): AiUi = when {
        settings.ai.provider == AiProvider.ON_DEVICE && BuildConfig.ENABLE_LOCAL_LLM &&
            effectiveLocalModelFile(settings) == null -> AiUi.NeedModel
        settings.ai.provider == AiProvider.ON_DEVICE && !BuildConfig.ENABLE_LOCAL_LLM ->
            AiUi.NeedSetup
        !AiClient.isConfigured(settings.ai) -> AiUi.NeedSetup
        else -> AiUi.Idle
    }

    /** Model-picker row on the AI panel: switch provider (and local model). */
    fun onAiPickModel(provider: AiProvider, localModelId: String?) {
        vibrate()
        serviceScope.launch {
            if (localModelId != null) settingsRepository.setAiLocalModelId(localModelId)
            settingsRepository.setAiProvider(provider)
            // Re-derive the panel state for the new choice; the settings flow
            // update races this tap, so compute from the edited values.
            val current = _uiState.value.settings
            val updated = current.copy(
                ai = current.ai.copy(
                    provider = provider,
                    localModelId = localModelId ?: current.ai.localModelId,
                ),
            )
            _uiState.update { it.copy(ai = aiInitialState(updated)) }
        }
    }

    /**
     * The text an action runs on.
     *
     * A selection always wins, whatever the action's input mode says: the user
     * has pointed at exactly what they mean. Only with no selection does the
     * mode decide, and "carry this on" then reads what is *before* the cursor,
     * because the words after it are not part of what came before.
     */
    /** How long a weather reading counts as fresh, from the user's setting. */
    private fun weatherCacheMs(): Long =
        _uiState.value.settings.toolLimits.weatherRefreshMinutes * 60_000L

    private fun aiInputText(spec: AiActionSpec): String {
        val ic = currentInputConnection ?: return ""
        ic.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return if (spec.inputMode == AiInputMode.BEFORE_CURSOR) {
            ic.getTextBeforeCursor(_uiState.value.settings.ai.beforeCursorChars, 0)
                ?.toString().orEmpty()
        } else {
            extractFieldText()
        }
    }

    fun onAiAction(spec: AiActionSpec) {
        vibrate()
        val initial = aiInitialState(_uiState.value.settings)
        if (initial is AiUi.NeedModel || initial is AiUi.NeedSetup) {
            _uiState.update { it.copy(ai = initial) }
            return
        }
        // No stored prompt: open the input box and let the key rows compose the
        // instruction; the run happens on Enter or the Run chip.
        if (spec.askEachRun) {
            val opening = aiInitialInstruction(
                spec,
                lastInstruction = aiLastInstruction[spec.id].orEmpty(),
                translateTo = _uiState.value.settings.ai.translateTo,
            )
            _uiState.update { it.copy(ai = AiUi.CustomInput(spec, opening)) }
            return
        }
        currentInputConnection?.let { commitComposing(it, autocorrect = false) }
        val source = aiInputText(spec).trim()
        if (source.isNotEmpty()) {
            runAi(spec, source)
            return
        }
        if (!spec.worksWithoutText) {
            // Backstop for the chips being disabled: an empty field gives the
            // model nothing to work from, so it would invent text rather than
            // rewrite any. Never send the request.
            refreshAiHasText()
            _uiState.update {
                it.copy(ai = AiUi.Error(spec, getString(R.string.ime_ai_error_no_text)))
            }
            return
        }
        // An action that works on nothing writes from its own task instead. The
        // task becomes the user message, because several providers reject an
        // empty user turn outright.
        val task = AiPrompts.resolvedTask(spec, _uiState.value.settings.ai.translateTo).trim()
        if (task.isEmpty()) {
            _uiState.update {
                it.copy(ai = AiUi.Error(spec, getString(R.string.ime_ai_error_no_text)))
            }
            return
        }
        runAi(spec, task, generated = true)
    }

    /** Backspace/character edits to the typed-instruction buffer. */
    private fun aiCustomInputEdit(transform: (String) -> String) {
        val ai = _uiState.value.ai as? AiUi.CustomInput ?: return
        _uiState.update { it.copy(ai = AiUi.CustomInput(ai.action, transform(ai.instruction))) }
    }

    /** Runs an ask-each-run action with the instruction the user just typed. */
    fun onAiRunCustom() {
        val ai = _uiState.value.ai as? AiUi.CustomInput ?: return
        val instruction = ai.instruction.trim()
        if (instruction.isEmpty()) return
        vibrate()
        currentInputConnection?.let { commitComposing(it, autocorrect = false) }
        val source = aiInputText(ai.action).trim()
        aiLastInstruction[ai.action.id] = instruction
        if (source.isNotEmpty()) {
            runAi(ai.action, source, instruction)
            return
        }
        if (!ai.action.worksWithoutText) {
            _uiState.update {
                it.copy(ai = AiUi.Error(ai.action, getString(R.string.ime_ai_error_no_text)))
            }
            return
        }
        // Nothing to transform, so the instruction is the whole request and the
        // model writes from nothing. Gated on the field being genuinely empty,
        // not on the flag alone: the prompt this takes carries no injection
        // guard, so field text must never reach it.
        runAi(ai.action, instruction, instruction, generated = true)
    }

    private fun runAi(
        action: AiActionSpec,
        source: String,
        instruction: String = "",
        generated: Boolean = false,
    ) {
        aiJob?.cancel()
        // Data saving, for the providers that are a request over the network.
        // An on-device model costs nothing to reach, so it is never held.
        val aiSettings = _uiState.value.settings.ai
        if (aiSettings.provider != AiProvider.ON_DEVICE) {
            val decision = dataSaverStatus.decide(MeteredFeature.CLOUD_AI)
            if (decision != MeteredDecision.ALLOWED) {
                _uiState.update {
                    it.copy(
                        ai = AiUi.Error(
                            action,
                            getString(
                                if (decision == MeteredDecision.ASK) {
                                    R.string.ime_ai_error_metered_ask
                                } else {
                                    R.string.ime_ai_error_metered_blocked
                                },
                            ),
                        ),
                    )
                }
                return
            }
        }
        val seq = ++aiRunSeq
        val startedAt = SystemClock.uptimeMillis()
        _uiState.update {
            it.copy(ai = AiUi.Loading(action, startedAtMs = startedAt))
        }
        aiJob = serviceScope.launch {
            val settings = _uiState.value.settings
            val system = when {
                // Writing from nothing: the request rides in the user message,
                // so the system prompt only has to frame it. No injection guard
                // here by design, which is why the callers only reach this with
                // an empty field.
                generated -> AiPrompts.generatePrompt()
                action.askEachRun -> AiPrompts.customPrompt(instruction)
                else -> AiPrompts.systemPrompt(action, settings.ai.translateTo)
            }
            val config = AiClient.config(settings.ai)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (config.provider == AiProvider.ON_DEVICE) {
                        runAiOnDevice(seq, action, source, system, settings, generated)
                    } else {
                        runAiRemote(seq, action, source, system, settings, config, startedAt, generated)
                    }
                }
            }
            // A superseded or cancelled run never gets past here, so it is
            // never recorded either.
            if (seq != aiRunSeq) return@launch
            val next = result.fold(
                onSuccess = { completion ->
                    val raw = completion.text
                    val text =
                        if (settings.ai.showThinking) raw.trim()
                        else AiThinking.stripped(raw)
                    when {
                        text.isNotBlank() -> AiUi.Ready(
                            action, text, source,
                            instruction = instruction,
                            generated = generated,
                            stripMarkdown = aiStripMarkdownDefault(),
                            truncated = completion.truncated,
                            showDiff = settings.ai.diffView &&
                                settings.ai.diffOpensFirst &&
                                aiDiffable(action, generated),
                            diffable = aiDiffable(action, generated),
                        )
                        raw.isBlank() -> AiUi.Error(
                            action,
                            getString(R.string.ime_ai_error_empty_result),
                        )
                        else -> AiUi.Error(
                            action,
                            getString(R.string.ime_ai_error_only_reasoning),
                        )
                    }
                },
                onFailure = { e ->
                    AiUi.Error(action, requestErrorText(e, R.string.ime_ai_error_request_failed))
                },
            )
            _uiState.update { it.copy(ai = next) }
            recordAiHistory(
                action = action,
                source = source,
                instruction = instruction,
                settings = settings,
                config = config,
                state = next,
                reasoningChars = result.getOrNull()?.let { completion ->
                    (completion.text.length - AiThinking.stripped(completion.text).length)
                        .coerceAtLeast(0)
                } ?: 0,
                durationMs = SystemClock.uptimeMillis() - startedAt,
            )
        }
    }

    /**
     * Id of the record for the run on screen, so committing its answer can be
     * noted against it. Zero when the run was not recorded.
     */
    private var lastAiHistoryId = 0L

    /**
     * Writes one finished run to the history, if the user turned it on and this
     * field allows it.
     *
     * A failed run is recorded too, with the message the panel showed: "the AI
     * tool keeps failing" is the most likely reason anyone opens the screen, and
     * the message carries nothing the record does not already hold.
     */
    private fun recordAiHistory(
        action: AiActionSpec,
        source: String,
        instruction: String,
        settings: KeyboardSettings,
        config: AiClient.Config,
        state: AiUi,
        reasoningChars: Int,
        durationMs: Long,
    ) {
        lastAiHistoryId = 0L
        val allowed = AiHistoryGuard.shouldRecord(
            enabled = settings.ai.historyEnabled,
            unlocked = userUnlocked,
            secureField = _uiState.value.secureField,
            incognito = _uiState.value.incognitoOn,
        )
        if (!allowed) return
        val ready = state as? AiUi.Ready
        val error = (state as? AiUi.Error)?.message.orEmpty()
        val entry = AiHistoryEntry(
            id = 0,
            timestamp = System.currentTimeMillis(),
            actionId = action.id,
            actionName = aiActionLabel(action),
            provider = settings.ai.provider.name,
            // The model name only. The connection details that carry the key
            // are never part of a record.
            model = config.model,
            input = source,
            output = ready?.result.orEmpty(),
            durationMs = durationMs,
            instruction = instruction,
            reasoningChars = reasoningChars,
            streamed = config.provider != AiProvider.ON_DEVICE,
            error = error,
            truncated = ready?.truncated == true,
        )
        serviceScope.launch(Dispatchers.IO) {
            // The settings app holds its own instance of this store, so a
            // "Delete all history" over there has to be seen before writing or
            // it would come straight back.
            aiHistoryStore.reload()
            aiHistoryStore.trimTo(settings.ai.historyMax)
            lastAiHistoryId = aiHistoryStore.add(entry).id
            aiHistoryStore.save()
        }
    }

    /** Notes that the user put this answer into the field. */
    private fun noteAiCommitted(how: String) {
        val id = lastAiHistoryId
        if (id == 0L) return
        serviceScope.launch(Dispatchers.IO) {
            aiHistoryStore.markCommitted(id, how)
            aiHistoryStore.save()
        }
    }

    /**
     * Blocking on-device generation, streaming partial text into
     * [AiUi.Ready] with [AiUi.Ready.generating] set so the panel can render
     * the response as it forms. Call under [Dispatchers.IO].
     */
    /** A shipped action's translated name, or the name the user gave it. */
    private fun aiActionLabel(spec: AiActionSpec): String =
        BuiltInAiActions.labelRes(spec)?.let { getString(it) } ?: spec.name

    private fun runAiOnDevice(
        seq: Int,
        action: AiActionSpec,
        source: String,
        system: String,
        settings: KeyboardSettings,
        generated: Boolean,
    ): AiClient.Completion {
        val modelId = effectiveLocalModelId(settings)
        val modelFile = effectiveLocalModelFile(settings)
            ?: throw IOException(getString(R.string.ime_ai_error_model_missing))
        val implicitThink = isReasoningModel(modelId)
        val startedAt = SystemClock.uptimeMillis()
        var lastPartialAt = 0L
        val text = LocalLlmEngine.generate(
            context = applicationContext,
            modelFile = modelFile,
            backend = settings.ai.localBackend,
            contextTokens = settings.ai.localContextTokens,
            system = system,
            user = source,
        ) { raw ->
            val now = SystemClock.uptimeMillis()
            if (seq != aiRunSeq || now - lastPartialAt < AI_PARTIAL_INTERVAL_MS) return@generate
            lastPartialAt = now
            applyAiPartial(seq, action, source, raw, settings, implicitThink, startedAt, generated)
        }
        // The on-device engine reports no stop reason, so an answer that ran out
        // of context window looks the same as one that finished. Never claim it
        // was cut off, rather than claiming it wrongly either way.
        return AiClient.Completion(text)
    }

    /**
     * Streaming cloud/server generation. Same shape as [runAiOnDevice] — the
     * response is rendered as it forms — plus the connection phases, which
     * only a network request has. Call under [Dispatchers.IO].
     */
    private fun runAiRemote(
        seq: Int,
        action: AiActionSpec,
        source: String,
        system: String,
        settings: KeyboardSettings,
        config: AiClient.Config,
        startedAt: Long,
        generated: Boolean,
    ): AiClient.Completion {
        var lastPartialAt = 0L
        return AiClient.completeStreaming(
            config = config,
            system = system,
            user = source,
            maxTokens = AiClient.effectiveMaxTokens(settings.ai),
            onPhase = { phase ->
                if (seq == aiRunSeq) {
                    _uiState.update {
                        // Only advance a still-loading run: a partial may
                        // already have promoted this to Ready, and a late
                        // phase report must not drag it back to a spinner.
                        val loading = it.ai as? AiUi.Loading
                        if (loading == null) it else it.copy(ai = loading.copy(phase = phase))
                    }
                }
            },
            onPartial = { raw ->
                val now = SystemClock.uptimeMillis()
                if (seq == aiRunSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    // Cloud reasoning is wrapped in explicit <think> tags by
                    // AiClient, so it never needs the implicit-think fallback.
                    applyAiPartial(
                        seq, action, source, raw, settings,
                        implicitThink = false, startedAt, generated,
                    )
                }
            },
            // A superseded or closed run stops reading here rather than holding
            // the socket — and paying for tokens — until the model finishes.
            isActive = { seq == aiRunSeq },
        )
    }

    /**
     * Renders one streamed partial: reasoning keeps the progress view (with a
     * live character count, the only sign a silent reasoning model is alive),
     * and the first answer text switches the panel to the streaming result.
     */
    private fun applyAiPartial(
        seq: Int,
        action: AiActionSpec,
        source: String,
        raw: String,
        settings: KeyboardSettings,
        implicitThink: Boolean,
        startedAt: Long,
        generated: Boolean,
    ) {
        // Reasoning models: keep the progress view (marked "thinking") until
        // real output starts, unless the user wants the raw stream.
        val shown = if (settings.ai.showThinking) {
            AiThinking.Split(raw, thinking = false)
        } else {
            AiThinking.split(raw, implicitThink)
        }
        _uiState.update {
            if (seq != aiRunSeq) return@update it
            it.copy(
                ai = when {
                    shown.output.isBlank() && shown.thinking -> AiUi.Loading(
                        action,
                        phase = AiPhase.THINKING,
                        thinkingChars = raw.length,
                        startedAtMs = startedAt,
                    )
                    shown.output.isBlank() -> it.ai // nothing visible yet
                    // showDiff stays false while this streams: a half-finished
                    // result compares as "the whole tail was deleted", which
                    // would flash a block of red that then vanishes.
                    else -> AiUi.Ready(
                        action, shown.output, source,
                        generating = true,
                        stripMarkdown = (it.ai as? AiUi.Ready)?.stripMarkdown ?: true,
                        diffable = aiDiffable(action, generated),
                    )
                },
            )
        }
    }

    /** Re-runs the last action on the text it originally saw. */
    fun onAiRetry() {
        when (val ai = _uiState.value.ai) {
            // Everything the prompt was built from rides on the state, so a
            // retry rebuilds exactly the same request.
            is AiUi.Ready -> {
                vibrate()
                runAi(ai.action, ai.sourceText, ai.instruction, ai.generated)
            }
            // A failed ask-each-run action reopens its input prefilled so the
            // user can adjust the instruction; onAiAction does exactly that.
            // A retry on the data-saver message means the same thing it means
            // on a media panel: yes, use the connection.
            is AiUi.Error -> {
                if (dataSaverStatus.decide(MeteredFeature.CLOUD_AI) == MeteredDecision.ASK) {
                    grantMetered(MeteredFeature.CLOUD_AI)
                }
                onAiAction(ai.action)
            }
            else -> {}
        }
    }

    /**
     * Puts the result into the field: in place of the text the action ran on,
     * or after it for an action that adds to the text rather than replacing it.
     * "Carry this on" is the case that needs the second one, where replacing
     * would delete the very text the user asked to have continued.
     */
    fun onAiReplace() {
        val ai = _uiState.value.ai as? AiUi.Ready ?: return
        vibrate()
        noteAiCommitted(AiHistoryEntry.COMMITTED_REPLACE)
        if (ai.action.insertMode == AiInsertMode.APPEND) {
            commitToField(aiInsertableText(ai))
            return
        }
        replaceFieldText(aiInsertableText(ai))
    }

    fun onAiInsert() {
        val ai = _uiState.value.ai as? AiUi.Ready ?: return
        vibrate()
        noteAiCommitted(AiHistoryEntry.COMMITTED_INSERT)
        commitToField(aiInsertableText(ai))
    }

    /**
     * "Report" on a result: opens a mail draft to the maintainer holding the
     * action, the model behind it, and the text either side of the generation.
     *
     * Nothing leaves the device here. The draft lands in the user's mail app,
     * where they can read every line of it — including their own text, which a
     * useful report has to quote — and edit or abandon it before sending. A
     * keyboard that can produce text on demand needs somewhere for the bad
     * results to go, and Play asks for exactly this on generated content.
     */
    fun onAiReport() {
        val state = _uiState.value
        val ai = state.ai as? AiUi.Ready ?: return
        vibrate()
        val sent = Support.email(
            this,
            "WM Keyboard: AI generation report",
            Support.aiGenerationReport(
                action = aiActionLabel(ai.action),
                provider = getString(state.settings.ai.provider.labelRes),
                model = AiClient.config(state.settings.ai).model,
                input = ai.sourceText,
                output = ai.result,
                instruction = ai.instruction,
            ),
        )
        if (!sent) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_no_email_app_toast, Support.EMAIL),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Whether comparing this run's result with what it read means anything.
     *
     * An action that adds to the text read only what came before it, and a run
     * that wrote from nothing had no source text at all — its "source" is the
     * instruction. Comparing either would draw a wall of insertions.
     */
    private fun aiDiffable(action: AiActionSpec, generated: Boolean): Boolean =
        !generated &&
            action.insertMode == AiInsertMode.REPLACE &&
            action.inputMode == AiInputMode.FIELD

    /**
     * Carries the panel's "plain text" checkbox across runs: on by default,
     * but a user who turned it off means it for the next result too.
     */
    private fun aiStripMarkdownDefault(): Boolean =
        (_uiState.value.ai as? AiUi.Ready)?.stripMarkdown ?: true

    /** Panel's Result / Changes switch. */
    fun onAiSetShowDiff(show: Boolean) {
        val ai = _uiState.value.ai as? AiUi.Ready ?: return
        if (ai.showDiff == show) return
        vibrate()
        _uiState.update { it.copy(ai = ai.copy(showDiff = show)) }
    }

    /** Panel's "plain text" checkbox. */
    fun onAiToggleStripMarkdown() {
        val ai = _uiState.value.ai as? AiUi.Ready ?: return
        vibrate()
        _uiState.update { it.copy(ai = ai.copy(stripMarkdown = !ai.stripMarkdown)) }
    }

    /**
     * What Replace/Insert actually commit: even when the panel shows a
     * reasoning model's think block (verbose mode), only the trimmed answer
     * belongs in the text field — with markdown syntax removed unless the
     * user unchecked it.
     */
    private fun aiInsertableText(ai: AiUi.Ready): String {
        val answer = AiThinking.stripped(ai.result).ifBlank { ai.result.trim() }
        return if (ai.stripMarkdown) AiMarkdown.strip(answer) else answer
    }

    // ---- tools: dictionary & camera ----

    private var dictionaryJob: Job? = null

    /**
     * Dictionary panel just opened: look up the selected word (or the word
     * around the cursor) per the auto-lookup setting; with nothing to look
     * up, drop straight into the search field so typing starts a query.
     */
    private fun openDictionary() {
        val word = if (_uiState.value.settings.dictionaryAutoLookup) currentWordForLookup() else null
        if (word != null) {
            onDictionaryLookup(word)
        } else if (_uiState.value.dictionary is DictionaryUi.Ready) {
            // No word at the cursor but a previous lookup is still on
            // screen — keep it; the search chip is one tap away.
        } else {
            updateQuery { it.copy(dictionarySearchActive = true, dictionaryQuery = "") }
        }
    }

    /** Selection first; else the run of word characters around the cursor. */
    private fun currentWordForLookup(): String? {
        val ic = currentInputConnection ?: return null
        fun sanitize(raw: String): String? {
            val word = raw.trim().trim('\'', '-', '“', '”', '"')
            return word.takeIf {
                it.isNotEmpty() && it.length <= 40 &&
                    it.any(Char::isLetter) &&
                    // The API is English-only; skip Bengali (or any
                    // non-Latin) words instead of showing "not found".
                    it.all { ch -> ch.code < 0x250 || ch == '’' }
            }
        }
        ic.getSelectedText(0)?.toString()?.let { return sanitize(it) }
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(48, 0)?.toString().orEmpty()
        fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'
        return sanitize(before.takeLastWhile(::isWordChar) + after.takeWhile(::isWordChar))
    }

    fun onDictionaryLookup(rawWord: String) {
        val word = rawWord.trim()
        if (word.isEmpty()) {
            _uiState.update { it.copy(dictionarySearchActive = false) }
            return
        }
        dictionaryJob?.cancel()
        _uiState.update {
            it.copy(
                dictionaryQuery = word,
                dictionarySearchActive = false,
                dictionary = DictionaryUi.Loading(word),
            )
        }
        dictionaryJob = serviceScope.launch {
            val ui = try {
                val entries = withContext(Dispatchers.IO) { DictionaryClient.lookup(word) }
                if (entries.isEmpty()) DictionaryUi.NotFound(word) else DictionaryUi.Ready(entries)
            } catch (_: DictionaryClient.NotFoundException) {
                DictionaryUi.NotFound(word)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                DictionaryUi.Error(word)
            }
            _uiState.update { it.copy(dictionary = ui) }
        }
    }

    fun onDictionarySearchToggle() {
        vibrate()
        _uiState.update { it.copy(dictionarySearchActive = !it.dictionarySearchActive) }
    }

    /** Clipboard panel search bar tapped: route keys into [clipboardQuery]. */
    fun onClipboardSearchToggle() {
        vibrate()
        _uiState.update {
            val active = !it.clipboardSearchActive
            // Closing search clears the filter so the full history is back.
            it.copy(
                clipboardSearchActive = active,
                clipboardQuery = if (active) it.clipboardQuery else "",
                panelFocus = null,
            )
        }
    }

    /** Insert chip on a dictionary entry: type the word into the editor. */
    fun onDictionaryInsert(word: String) {
        vibrate()
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        ic.commitText(word, 1)
    }

    /** Camera tool captured a photo: send it into the editor as an image. */
    fun onCameraSend(file: File) {
        vibrate()
        saveToGalleryIfEnabled(
            file,
            MediaMime.JPEG,
            _uiState.value.settings.camera.saveToGallery,
            "IMG",
        )
        commitImageFile(file, MediaMime.JPEG)
        // The photo is on its way (or on the clipboard) — the tool's job is
        // done, give the keys back.
        _uiState.update { it.copy(panel = PanelMode.NONE) }
    }

    /** IMEs cannot show permission dialogs; bounce through the trampoline. */
    fun onCameraPermissionRequest() {
        startActivity(
            Intent(this, CameraPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Calendar tool's READ_CALENDAR request, via the same trampoline pattern. */
    fun onCalendarPermissionRequest() {
        startActivity(
            Intent(this, CalendarPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Doc-scan tool: hand off to ML Kit's full-screen scanner activity
     * (edge detection, crop, filters — all Google's UI). The scanned pages
     * come back through [DocScanActivity.consumePendingPages] in
     * [onStartInputView], once the target app has focus again.
     */
    fun onDocScanStart() {
        if (!BuildConfig.ENABLE_ML_KIT_SCANNERS) return
        vibrate()
        _uiState.update { it.copy(panel = PanelMode.NONE) }
        startActivity(
            Intent(this, DocScanActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** OCR/QR tools: insert recognized text like a (long) key press. */
    fun onScannedTextInsert(text: String) {
        vibrate()
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        ic.commitText(text, 1)
    }

    /** Open a scanned QR/barcode URL in the browser (leaves the keyboard). */
    fun onScannedUrlOpen(url: String) {
        vibrate()
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    // ---- translate / gif / sticker / web & image search tools ----

    /** Whether the web/image search backend (Brave) is keyed. */
    private fun hasSearchKey(): Boolean =
        ToolApiKeys.hasSearchProvider(_uiState.value.settings)

    /** Search-bar tap on a media panel: toggle typing-into-the-query mode. */
    fun onMediaQueryTap() {
        vibrate()
        _uiState.update { it.copy(mediaSearchActive = !it.mediaSearchActive, mediaAction = null) }
    }

    /**
     * GIF/sticker searches are cheap on KLIPY/GIPHY, so results follow the
     * query live. Web/image search waits for enter — the free Programmable
     * Search tier is 100 queries a day.
     */
    private fun scheduleMediaLiveSearch() {
        val state = _uiState.value
        // Translate is free-tier friendly too: its result follows the typed
        // query live, on its own (400 ms) debounce.
        if (state.panel == PanelMode.TRANSLATE) {
            scheduleTranslate()
            return
        }
        if (state.panel != PanelMode.GIF && state.panel != PanelMode.STICKER) return
        mediaLiveSearchJob?.cancel()
        val query = state.mediaQuery.trim()
        mediaLiveSearchJob = serviceScope.launch {
            delay(450)
            refreshMedia(query)
        }
    }

    /** Enter (or the search action) in a media panel's search box. */
    private fun runMediaSearch() {
        val state = _uiState.value
        val query = state.mediaQuery.trim()
        _uiState.update { it.copy(mediaSearchActive = false) }
        when (state.panel) {
            PanelMode.GIF, PanelMode.STICKER -> {
                mediaLiveSearchJob?.cancel()
                refreshMedia(query)
            }
            PanelMode.WEB_SEARCH -> runWebSearch(query)
            PanelMode.IMAGE_SEARCH -> runImageSearch(query)
            PanelMode.WIKIPEDIA -> runWikiSearch(query)
            PanelMode.TRANSLATE -> scheduleTranslate(immediate = true)
            else -> {}
        }
    }

    /** Retry button on media/search panels: re-run whatever failed. */
    fun onMediaRetry() {
        vibrate()
        // A retry on a data-saver notice is the user saying yes to the fetch,
        // so it grants first and then falls through to make it.
        grantMeteredForPanel(_uiState.value)
        when (_uiState.value.panel) {
            PanelMode.GIF, PanelMode.STICKER -> refreshMedia(_uiState.value.mediaQuery.trim())
            PanelMode.WEB_SEARCH -> runWebSearch(_uiState.value.mediaQuery.trim())
            PanelMode.IMAGE_SEARCH -> runImageSearch(_uiState.value.mediaQuery.trim())
            PanelMode.WIKIPEDIA -> {
                val query = _uiState.value.mediaQuery.trim()
                if (query.isNotEmpty()) runWikiSearch(query)
                else _uiState.update { it.copy(wiki = WikiUi.Idle, mediaSearchActive = true) }
            }
            PanelMode.TRANSLATE -> scheduleTranslate(immediate = true)
            else -> {}
        }
    }

    /**
     * Fetches GIFs or stickers for [query] from every active provider
     * (blank query = trending).
     */
    private fun refreshMedia(query: String) {
        val state = _uiState.value
        val sticker = state.panel == PanelMode.STICKER
        if (!sticker && state.panel != PanelMode.GIF) return
        val setUi: (MediaUi) -> Unit = { ui ->
            _uiState.update { if (sticker) it.copy(sticker = ui) else it.copy(gif = ui) }
        }
        val settings = state.settings
        // Stickers add the user's own packs, which need no key — so only the
        // GIF tool can end up with nothing to ask.
        val sources = if (sticker) ToolApiKeys.stickerSources(settings) else ToolApiKeys.gifSources(settings)
        if (sources.isEmpty()) {
            setUi(MediaUi.NeedKey)
            return
        }
        val tabs = settings.gifSourceMode == GifSourceMode.TABS
        val targets = GifSources.targets(sources, state.mediaSource, tabs)
        if (tabs) {
            val selected = targets.first()
            if (selected != state.mediaSource) _uiState.update { it.copy(mediaSource = selected) }
        }
        // Data saving. Only the online providers count: the user's own sticker
        // packs are files on disk, and a panel that refused to show them on
        // mobile data would be refusing to open a folder.
        if (targets.any { it != GifSource.LOCAL }) {
            val decision = dataSaverStatus.decide(MeteredFeature.MEDIA_SEARCH)
            if (decision != MeteredDecision.ALLOWED) {
                mediaFetchJob?.cancel()
                setUi(MediaUi.Metered(canAllow = decision == MeteredDecision.ASK))
                return
            }
        }
        mediaFetchJob?.cancel()
        setUi(MediaUi.Loading)
        val panel = state.panel
        mediaFetchJob = serviceScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { source ->
                    async { runCatching { fetchGifs(source, query, sticker, settings) } }
                }.awaitAll()
            }
            if (_uiState.value.panel != panel) return@launch
            val successes = results.mapNotNull { it.getOrNull() }
            setUi(
                if (successes.isEmpty()) {
                    MediaUi.Error(
                        results.firstNotNullOfOrNull { it.exceptionOrNull() }
                            ?.let { ToolHttp.friendlyMessage(this@WMKeyboardService, it) }
                            ?: getString(R.string.ime_service_media_fetch_error),
                    )
                } else {
                    val merged = GifSources.interleave(successes)
                    // The limit is per fetch: in mixed mode each provider
                    // returns up to the limit, so cap the merged grid back
                    // down to it. The user's own packs are never truncated.
                    val limited = if (targets == listOf(GifSource.LOCAL)) {
                        merged
                    } else {
                        merged.take(settings.gifResultLimit)
                    }
                    MediaUi.Ready(limited, query)
                },
            )
        }
    }

    /** Blocking provider dispatch; call on an IO dispatcher. */
    private fun fetchGifs(
        source: GifSource,
        query: String,
        sticker: Boolean,
        settings: com.wasimaster.wmkeyboard.core.settings.KeyboardSettings,
    ): List<GifItem> = when (source) {
        GifSource.KLIPY -> KlipyClient.search(
            query, ToolApiKeys.klipy(settings), sticker, settings.gifContentFilter,
            limit = settings.gifResultLimit,
        )
        GifSource.GIPHY -> GiphyClient.search(
            query, ToolApiKeys.giphy(settings), sticker, settings.gifContentFilter,
            limit = settings.gifResultLimit,
        )
        GifSource.LOCAL ->
            stickerPackStore.searchAsGifItems(query, _uiState.value.stickerPackId)
    }

    /** Provider chip on the GIF/sticker panel (tabs mode). */
    fun onGifSourceSelect(source: GifSource) {
        vibrate()
        if (_uiState.value.mediaSource == source) return
        _uiState.update { it.copy(mediaSource = source, mediaAction = null, stickerPackId = null) }
        refreshMedia(_uiState.value.mediaQuery.trim())
        // Categories belong to a provider, so the row follows the chip.
        refreshMediaCategories()
    }

    /**
     * Fills the category row for the panel's default view.
     *
     * A function of the provider and the panel, never of the query: that is
     * what keeps a keystroke from costing a request. Called on panel open and
     * on a provider switch, and nowhere else — not from the live-search
     * debounce, the enter search, retry, or the pack filter.
     *
     * The bundled list goes up straight away, before anything is fetched. The
     * row has to be there in the first frame; a row that pops in a second
     * later reflows the grid under the user's finger.
     */
    private fun refreshMediaCategories() {
        val state = _uiState.value
        val sticker = state.panel == PanelMode.STICKER
        if (!sticker && state.panel != PanelMode.GIF) return
        val settings = state.settings
        val sources =
            if (sticker) ToolApiKeys.stickerSources(settings) else ToolApiKeys.gifSources(settings)
        val tabs = settings.gifSourceMode == GifSourceMode.TABS
        // One provider, not every target: two taxonomies interleaved are a
        // row of near-duplicates for twice the requests. Local packs have no
        // categories at all — the pack chips are their equivalent.
        val target = GifSources.targets(sources, state.mediaSource, tabs)
            .firstOrNull { it != GifSource.LOCAL }
        if (target == null) {
            _uiState.update { it.copy(mediaCategories = emptyList(), mediaCategory = null) }
            return
        }
        _uiState.update { it.copy(mediaCategories = MediaCategories.bundled(sticker)) }
        mediaCategoryJob?.cancel()
        val panel = state.panel
        mediaCategoryJob = serviceScope.launch {
            val cached = MediaCategoryCache.get(target, sticker, System.currentTimeMillis())
            val fetched = cached ?: withContext(Dispatchers.IO) {
                runCatching { fetchCategories(target, sticker, settings) }
            }.onSuccess {
                // Cache the empty answer too: a provider with no categories
                // endpoint is then asked once a day, not once per open. Only
                // on success — a cancelled fetch must not poison the entry.
                MediaCategoryCache.put(target, sticker, it, System.currentTimeMillis())
            }.getOrNull()
            if (_uiState.value.panel != panel) return@launch
            _uiState.update {
                it.copy(mediaCategories = MediaCategories.normalise(fetched.orEmpty(), sticker))
            }
        }
    }

    /** Blocking provider dispatch; call on an IO dispatcher. */
    private fun fetchCategories(
        source: GifSource,
        sticker: Boolean,
        settings: com.wasimaster.wmkeyboard.core.settings.KeyboardSettings,
    ): List<MediaCategory> = when (source) {
        GifSource.KLIPY -> KlipyClient.categories(ToolApiKeys.klipy(settings), sticker)
        GifSource.GIPHY -> GiphyClient.categories(ToolApiKeys.giphy(settings), sticker)
        GifSource.LOCAL -> emptyList()
    }

    /** Category chip in the GIF/sticker default view: runs it as a search. */
    fun onGifCategorySelect(term: String) {
        vibrate()
        val state = _uiState.value
        if (state.panel != PanelMode.GIF && state.panel != PanelMode.STICKER) return
        // A category is a search, so it cancels a debounce that would land
        // 450 ms later and overwrite the grid with a half-typed query.
        mediaLiveSearchJob?.cancel()
        val next = term.trim().takeIf { it.isNotEmpty() && it != state.mediaCategory }
        _uiState.update {
            it.copy(
                mediaCategory = next,
                // The search pill reads back what the grid is showing, so a
                // category shows as the search that it is.
                mediaQuery = next.orEmpty(),
                mediaSearchActive = false,
                mediaAction = null,
                panelFocus = null,
            )
        }
        refreshMedia(next.orEmpty())
    }

    private fun runWebSearch(query: String) {
        if (query.isBlank()) return
        val settings = _uiState.value.settings
        if (!ToolApiKeys.hasSearchProvider(settings)) {
            _uiState.update { it.copy(webSearch = WebSearchUi.NeedKey) }
            return
        }
        val decision = dataSaverStatus.decide(MeteredFeature.WEB_SEARCH)
        if (decision != MeteredDecision.ALLOWED) {
            webSearchJob?.cancel()
            _uiState.update {
                it.copy(
                    webSearch = WebSearchUi.Metered(
                        canAllow = decision == MeteredDecision.ASK,
                    ),
                )
            }
            return
        }
        webSearchJob?.cancel()
        _uiState.update { it.copy(webSearch = WebSearchUi.Loading) }
        webSearchJob = serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BraveSearchClient.webSearch(
                        query,
                        ToolApiKeys.brave(settings),
                        settings.searchResultCount,
                        settings.searchSafe,
                    )
                }
            }
            _uiState.update {
                it.copy(
                    webSearch = result.fold(
                        onSuccess = { r -> WebSearchUi.Ready(r, query) },
                        onFailure = { e ->
                            WebSearchUi.Error(
                                requestErrorText(e, R.string.ime_service_search_error),
                            )
                        },
                    ),
                )
            }
        }
    }

    private fun runImageSearch(query: String) {
        if (query.isBlank()) return
        val settings = _uiState.value.settings
        if (!ToolApiKeys.hasSearchProvider(settings)) {
            _uiState.update { it.copy(imageSearch = ImageSearchUi.NeedKey) }
            return
        }
        // Image results carry thumbnails, so this is the heavier of the two
        // searches; both answer to one setting, since a user who does not want
        // search over mobile data does not want half of it either.
        val decision = dataSaverStatus.decide(MeteredFeature.WEB_SEARCH)
        if (decision != MeteredDecision.ALLOWED) {
            imageSearchJob?.cancel()
            _uiState.update {
                it.copy(
                    imageSearch = ImageSearchUi.Metered(
                        canAllow = decision == MeteredDecision.ASK,
                    ),
                )
            }
            return
        }
        imageSearchJob?.cancel()
        _uiState.update { it.copy(imageSearch = ImageSearchUi.Loading) }
        imageSearchJob = serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BraveSearchClient.imageSearch(
                        query,
                        ToolApiKeys.brave(settings),
                        settings.searchResultCount,
                        settings.searchSafe,
                    )
                }
            }
            _uiState.update {
                it.copy(
                    imageSearch = result.fold(
                        onSuccess = { r -> ImageSearchUi.Ready(r, query) },
                        onFailure = { e ->
                            ImageSearchUi.Error(
                                requestErrorText(e, R.string.ime_service_search_error),
                            )
                        },
                    ),
                )
            }
        }
    }

    /**
     * Tapped a GIF/sticker cell: download the full file and commit it. The
     * two panels share this path, so which send mode applies depends on
     * which one is open.
     */
    fun onGifSelect(item: GifItem) {
        val settings = _uiState.value.settings
        // The field advertises what it can receive; when it advertises nothing
        // the send has nowhere to land. Stop here rather than downloading the
        // file and dead-ending in the clipboard fallback and its "copied,
        // paste it instead" toast — the panel already shows a standing notice
        // (see GifPanel), so a silent no-op is the honest answer.
        if (!_uiState.value.acceptsRichMedia) {
            vibrate()
            return
        }
        val sendMode = if (_uiState.value.panel == PanelMode.STICKER) {
            settings.stickerSendMode
        } else {
            settings.gifSendMode
        }
        if (item.source == GifSource.LOCAL) {
            insertLocalSticker(item, sendMode)
            return
        }
        insertDownloadedImage(item.id, item.fullUrl, item.mime, sendMode)
    }

    /**
     * Commits a sticker the user owns. Nothing to download, so this skips the
     * cache and the cell spinner entirely. A file that went missing behind the
     * store's back (manual wipe, restore) heals the manifest instead of
     * failing silently.
     */
    private fun insertLocalSticker(item: GifItem, sendMode: MediaSendMode) {
        vibrate()
        val found = stickerPackStore.findByItemId(item.id)
        val file = found?.let { (pack, sticker) -> stickerPackStore.fileFor(pack.id, sticker) }
        if (found == null || file == null || !file.exists()) {
            found?.let { (pack, sticker) -> stickerPackStore.removeSticker(pack.id, sticker.id) }
            Toast.makeText(
                this,
                getString(R.string.ime_service_sticker_missing_toast),
                Toast.LENGTH_SHORT,
            ).show()
            _uiState.update { it.copy(stickerPacks = stickerPackStore.packs()) }
            refreshMedia(_uiState.value.mediaQuery.trim())
            return
        }
        commitImageFile(file, item.mime, sendMode)
    }

    /** Pack chip on the "My stickers" tab; null means every pack. */
    fun onStickerPackFilter(packId: String?) {
        vibrate()
        if (_uiState.value.stickerPackId == packId) return
        _uiState.update { it.copy(stickerPackId = packId, mediaAction = null) }
        refreshMedia(_uiState.value.mediaQuery.trim())
    }

    /** Long-pressed a GIF or sticker cell: open its action sheet. */
    fun onMediaLongPress(item: GifItem) {
        val panel = _uiState.value.panel
        if (panel != PanelMode.STICKER && panel != PanelMode.GIF) return
        vibrate()
        _uiState.update { it.copy(mediaAction = item) }
    }

    fun onMediaActionDismiss() {
        _uiState.update { it.copy(mediaAction = null) }
    }

    /**
     * "Copy" on a GIF or sticker: puts the file on the system clipboard
     * instead of into the field.
     *
     * The tap path already falls back to the clipboard when a field refuses
     * the file, but that only happens *after* choosing a field that won't take
     * it. This is the deliberate version — grab the file now, paste it
     * somewhere the keyboard isn't focused on.
     */
    fun onMediaCopy(item: GifItem) {
        if (_uiState.value.mediaDownloadingId != null) return
        vibrate()
        _uiState.update { it.copy(mediaAction = null) }
        if (item.source == GifSource.LOCAL) {
            val found = stickerPackStore.findByItemId(item.id)
            val file = found?.let { (pack, sticker) -> stickerPackStore.fileFor(pack.id, sticker) }
            if (found == null || file == null || !file.exists()) {
                // Same self-heal as insertLocalSticker: a file that vanished
                // behind the store's back leaves the manifest lying.
                found?.let { (pack, sticker) -> stickerPackStore.removeSticker(pack.id, sticker.id) }
                Toast.makeText(
                    this,
                    getString(R.string.ime_service_sticker_missing_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                _uiState.update { it.copy(stickerPacks = stickerPackStore.packs()) }
                refreshMedia(_uiState.value.mediaQuery.trim())
                return
            }
            copyImageFileToClipboard(file)
            return
        }
        startMediaDownload(item.id)
        mediaInsertJob = serviceScope.launch {
            val file = withContext(Dispatchers.IO) {
                downloadMediaFile(item.fullUrl, item.mime, trackProgress = true)
            }
            endMediaDownload()
            if (file == null) {
                Toast.makeText(
                    this@WMKeyboardService,
                    getString(R.string.ime_service_media_download_error_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            copyImageFileToClipboard(file)
        }
    }

    /**
     * "Report" on a GIF or sticker: opens a mail draft naming the result and
     * where it came from.
     *
     * Klipy and GIPHY answer a query out of their own catalogues, so the
     * keyboard shows content nobody here has vetted. Play wants a reporting
     * route on a surface like that, and this is it. As with the AI report,
     * nothing is sent from the device — the draft lands in the user's mail
     * app for them to read, edit, or drop.
     */
    fun onMediaReport(item: GifItem) {
        val state = _uiState.value
        vibrate()
        _uiState.update { it.copy(mediaAction = null) }
        val kind = if (state.panel == PanelMode.STICKER) "sticker" else "GIF"
        val sent = Support.email(
            this,
            "WM Keyboard: $kind report",
            Support.mediaReport(
                kind = kind,
                provider = getString(GifSources.displayNameRes(item.source)),
                query = state.mediaQuery.trim(),
                id = item.id,
                url = item.fullUrl,
            ),
        )
        if (!sent) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_no_email_app_toast, Support.EMAIL),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Hands a media file to the system clipboard, saying so out loud. */
    private fun copyImageFileToClipboard(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, clipboardFileProviderAuthority, file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_file_copy_error_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        copyUriToSystemClipboard(uri, "image")
        Toast.makeText(
            this,
            getString(R.string.ime_service_copied_paste_toast),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * "Save to pack" on a provider sticker: download it, normalise it to the
     * sticker spec, and file it under [packId] (null creates a first pack).
     */
    fun onStickerSaveToPack(item: GifItem, packId: String?) {
        if (_uiState.value.mediaDownloadingId != null) return
        vibrate()
        _uiState.update { it.copy(mediaAction = null) }
        startMediaDownload(item.id)
        mediaInsertJob = serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                val target = packId
                    ?: stickerPackStore.packs().firstOrNull()?.id
                    ?: stickerPackStore
                        .createPack(getString(R.string.ime_service_sticker_pack_default_name))?.id
                    ?: return@withContext null
                val bytes = runCatching {
                    val dir = File(cacheDir, "media").apply { mkdirs() }
                    val temp = File(dir, "sticker_${item.fullUrl.hashCode().toUInt()}")
                    ToolHttp.download(
                        item.fullUrl,
                        temp,
                        maxBytes = StickerImage.MAX_SOURCE_BYTES,
                        onProgress = ::publishMediaProgress,
                    )
                    temp.readBytes().also { temp.delete() }
                }.getOrNull() ?: return@withContext null
                when (val processed = StickerImage.process(bytes)) {
                    is StickerImage.Result.Ok ->
                        stickerPackStore.addSticker(target, processed.sticker)
                    StickerImage.Result.TooLarge -> StickerAddResult.WriteFailed
                    StickerImage.Result.NotAnImage -> StickerAddResult.WriteFailed
                }
            }
            endMediaDownload()
            _uiState.update { it.copy(stickerPacks = stickerPackStore.packs()) }
            val message = when (result) {
                is StickerAddResult.Added ->
                    getString(R.string.ime_service_sticker_saved_toast)
                StickerAddResult.PackFull ->
                    getString(R.string.ime_service_sticker_pack_full_toast)
                StickerAddResult.PackMissing ->
                    getString(R.string.ime_service_sticker_pack_missing_toast)
                else -> getString(R.string.ime_service_sticker_save_error_toast)
            }
            Toast.makeText(this@WMKeyboardService, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Tapped an image-search cell: download the full image and commit it. */
    fun onImageResultSelect(result: ImageResult) {
        val mime = when (result.mime) {
            "image/gif", "image/png", "image/webp", "image/jpeg" -> result.mime
            else -> "image/jpeg"
        }
        insertDownloadedImage(result.imageUrl, result.imageUrl, mime)
    }

    /** Long-pressed an image-search cell: insert the image's URL as text. */
    fun onImageResultLink(result: ImageResult) {
        vibrate()
        commitToField(result.imageUrl)
    }

    /** Tapped a web result: insert its URL at the cursor. */
    fun onWebResultSelect(result: WebResult) {
        vibrate()
        commitToField(result.url)
    }

    /** Open a web result in the browser (leaves the keyboard). */
    fun onWebResultOpen(result: WebResult) {
        vibrate()
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(result.url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /**
     * Downloads a remote image into the media cache and commits it through
     * the same commitContent path as clipboard images. One insert at a
     * time; the panel shows a spinner over the tapped cell meanwhile.
     */
    private fun insertDownloadedImage(
        id: String,
        url: String,
        mime: String,
        sendMode: MediaSendMode = MediaSendMode.IMAGE,
    ) {
        if (_uiState.value.mediaDownloadingId != null) return
        vibrate()
        startMediaDownload(id)
        mediaInsertJob = serviceScope.launch {
            val file = withContext(Dispatchers.IO) {
                downloadMediaFile(url, mime, trackProgress = true)
            }
            endMediaDownload()
            if (file == null) {
                Toast.makeText(
                    this@WMKeyboardService,
                    getString(R.string.ime_service_media_download_error_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            commitImageFile(file, mime, sendMode)
        }
    }

    /**
     * Downloads a GIF/sticker/image into the media cache, or null if it
     * failed. Blocking — call it off the main thread.
     *
     * The cache name is stable per URL, so picking the same result twice
     * (insert, then copy) skips the second download.
     *
     * [trackProgress] publishes how far the transfer has got for the cell the
     * user tapped; it belongs to whoever also published [KeyboardUiState.mediaDownloadingId],
     * so the preview fetches that spin on their own flags leave it off.
     */
    private fun downloadMediaFile(
        url: String,
        mime: String,
        trackProgress: Boolean = false,
    ): File? = runCatching {
        val extension = MediaMime.extension(mime)
        val dir = File(cacheDir, "media").apply { mkdirs() }
        pruneMediaCache(dir)
        val target = File(dir, "media_${url.hashCode().toUInt()}.$extension")
        if (!target.exists() || target.length() == 0L) {
            ToolHttp.download(
                url,
                target,
                onProgress = if (trackProgress) ::publishMediaProgress else null,
            )
        }
        target
    }.getOrNull()

    /**
     * The last whole percent published for the running media download, so the
     * ~200 buffer reads of a 1.5 MB GIF turn into at most 100 state updates
     * instead of one per 8 KB.
     */
    private var mediaDownloadPercent = -1

    /**
     * Publishes download progress for the spinning cell. Called from the
     * download thread; [MutableStateFlow.update] is what makes that safe.
     *
     * A server that declared no size gets null rather than a made-up fraction —
     * the cell then keeps the indeterminate spinner it had before.
     */
    private fun publishMediaProgress(read: Long, expected: Long) {
        if (expected <= 0L) return
        val percent = (read * 100L / expected).coerceIn(0L, 100L).toInt()
        if (percent == mediaDownloadPercent) return
        mediaDownloadPercent = percent
        _uiState.update { it.copy(mediaDownloadProgress = percent / 100f) }
    }

    /**
     * Marks the start of a media download for [id]: the cell spins from here,
     * with no progress until the first bytes say how far there is to go.
     */
    private fun startMediaDownload(id: String) {
        mediaDownloadPercent = -1
        _uiState.update { it.copy(mediaDownloadingId = id, mediaDownloadProgress = null) }
    }

    /** The download ended, one way or another: the cell stops spinning. */
    private fun endMediaDownload() {
        mediaDownloadPercent = -1
        _uiState.update { it.copy(mediaDownloadingId = null, mediaDownloadProgress = null) }
    }

    /** Keeps the media cache bounded (newest ~30 files). */
    private fun pruneMediaCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(30).forEach { it.delete() }
    }

    // ---- translate tool ----

    /**
     * Where index 0 of the last [extractFieldText] result sits in the field,
     * or null when that could not be worked out.
     *
     * Null is the stitched fallback below: the string starts an unknown
     * distance into the field, so an offset into it maps onto nothing the
     * InputConnection understands. Callers that address the field by offset
     * (the grammar fixes) fall back to rewriting the whole field then.
     */
    private var fieldTextOrigin: Int? = null

    /** Everything in the focused field, for the grammar strip. */
    private fun extractFieldText(): String {
        val ic = currentInputConnection ?: return ""
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        if (extracted?.text != null) {
            // startOffset is where the editor began extracting. It is 0 for
            // every editor that hands over the whole field, and -1 from the
            // ones that don't track it — which is a "don't know", not a 0.
            fieldTextOrigin = extracted.startOffset.takeIf { it >= 0 }
            return extracted.text.toString()
        }
        // Some editors don't implement extraction; stitch around the cursor.
        val before = ic.getTextBeforeCursor(TranslateClient.MAX_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(TranslateClient.MAX_CHARS, 0)?.toString().orEmpty()
        fieldTextOrigin = null
        return before + after
    }

    /**
     * Translates the panel's typed query after a short debounce, so the
     * result follows the typing without a request per keystroke. The query
     * lives in [KeyboardUiState.mediaQuery] — the panel is its own window
     * and never reads the focused field.
     */
    private fun scheduleTranslate(immediate: Boolean = false, targetOverride: String? = null) {
        translateJob?.cancel()
        translateJob = serviceScope.launch {
            if (!immediate) delay(400)
            val state = _uiState.value
            if (state.panel != PanelMode.TRANSLATE) return@launch
            val source = state.mediaQuery.trim()
            if (source.isEmpty()) {
                _uiState.update { it.copy(translate = TranslateUi()) }
                return@launch
            }
            if (source == state.translate.sourceText &&
                state.translate.translated.isNotEmpty() && state.translate.error == null
            ) {
                return@launch
            }
            _uiState.update {
                it.copy(translate = it.translate.copy(sourceText = source, translating = true, error = null))
            }
            val target = targetOverride ?: state.settings.translateTargetLang
            val key = ToolApiKeys.translate(state.settings)
            val result = withContext(Dispatchers.IO) {
                runCatching { TranslateClient.translate(source, target, key) }
            }
            if (_uiState.value.panel != PanelMode.TRANSLATE) return@launch
            _uiState.update {
                it.copy(
                    translate = result.fold(
                        onSuccess = { t ->
                            TranslateUi(sourceText = source, translated = t.text, detectedSource = t.detectedSource)
                        },
                        onFailure = { e ->
                            it.translate.copy(
                                translating = false,
                                error = requestErrorText(e, R.string.ime_service_translate_error),
                            )
                        },
                    ),
                )
            }
        }
    }

    fun onTranslateTargetChange(code: String) {
        vibrate()
        serviceScope.launch { settingsRepository.setTranslateTargetLang(code) }
        _uiState.update { it.copy(translate = TranslateUi()) }
        // The settings flow updates asynchronously; pass the new target
        // directly so this retranslate can't race it.
        scheduleTranslate(immediate = true, targetOverride = code)
    }

    /** Replaces the whole field with the translation. */
    fun onTranslateReplace() {
        val translated = _uiState.value.translate.translated
        if (translated.isEmpty()) return
        vibrate()
        val ic = currentInputConnection ?: return
        // End any editor-side composition too: with a region alive, the
        // commitText below targets the region instead of the select-all,
        // splicing the translation over one word.
        ic.finishComposingText()
        composing = StringBuilder()
        ic.beginBatchEdit()
        val length = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length
        }.getOrNull()
        if (length != null) {
            ic.setSelection(0, length)
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
        ic.commitText(translated, 1)
        ic.endBatchEdit()
    }

    /** Inserts the translation at the cursor, keeping the original text. */
    fun onTranslateInsert() {
        val translated = _uiState.value.translate.translated
        if (translated.isEmpty()) return
        vibrate()
        commitToField(translated)
    }

    // ---- grammar tool (offline, Harper via JNI) ----

    /**
     * Re-extracts the field and lints it after a short debounce. Linting is
     * local and fast, but the debounce keeps the strip from churning on
     * every keystroke mid-word.
     */
    private fun scheduleGrammarCheck(immediate: Boolean = false) {
        grammarJob?.cancel()
        grammarJob = serviceScope.launch {
            if (!immediate) delay(_uiState.value.settings.grammarDebounceMs.toLong())
            val state = _uiState.value
            if (state.panel != PanelMode.GRAMMAR) return@launch
            if (!GrammarChecker.available) {
                _uiState.update { it.copy(grammar = GrammarUi(available = false)) }
                return@launch
            }
            val source = extractFieldText()
            if (source.isBlank()) {
                _uiState.update { it.copy(grammar = GrammarUi()) }
                return@launch
            }
            if (source == state.grammar.sourceText && state.grammar.checkedOnce) return@launch
            _uiState.update {
                it.copy(grammar = it.grammar.copy(sourceText = source, checking = true))
            }
            val dialect = _uiState.value.settings.grammarDialect
            val lints = GrammarChecker.check(source, dialect.ordinal)
            if (_uiState.value.panel != PanelMode.GRAMMAR) return@launch
            _uiState.update {
                it.copy(
                    grammar = GrammarUi(
                        sourceText = source,
                        lints = lints,
                        checking = false,
                        checkedOnce = true,
                    ),
                )
            }
        }
    }

    /** Replaces the whole field with [newText] (same mechanics as translate). */
    private fun replaceFieldText(newText: String) {
        val ic = currentInputConnection ?: return
        // See onTranslateReplace: an active composing region would hijack the
        // commitText away from the select-all and splice instead of replace.
        ic.finishComposingText()
        composing = StringBuilder()
        ic.beginBatchEdit()
        val length = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.length
        }.getOrNull()
        if (length != null) {
            ic.setSelection(0, length)
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    /**
     * Applies [edits] to the focused field in place, touching only the spans
     * they name and leaving the rest of the field exactly as the editor has
     * it — every style span intact, nothing re-committed.
     *
     * This is what a grammar fix uses instead of [replaceFieldText]. Select-all
     * plus commit is a rewrite of the whole note: in an editor that carries
     * formatting (Google Keep, any rich-text field) it lands as one flat run of
     * plain text, and even in a plain field it churns text the fix never
     * touched. Selecting the span and committing over it is the minimal edit.
     *
     * [edits] must be back-to-front, which is what [GrammarChecker.editsAll]
     * returns: every edit shifts the offsets after it, so working right to left
     * keeps the remaining offsets valid.
     *
     * Returns false when the field's offsets cannot be addressed at all (see
     * [fieldTextOrigin]), so the caller can fall back to the whole-field
     * rewrite rather than splicing at the wrong place.
     */
    private fun replaceFieldSpans(edits: List<GrammarEdit>): Boolean {
        val origin = fieldTextOrigin ?: return false
        val ic = currentInputConnection ?: return false
        if (edits.isEmpty()) return true
        // See replaceFieldText: a live composing region would hijack the
        // commitText away from the selection and splice at the cursor instead.
        ic.finishComposingText()
        composing = StringBuilder()
        ic.beginBatchEdit()
        for (edit in edits) {
            ic.setSelection(origin + edit.start, origin + edit.end)
            ic.commitText(edit.text, 1)
        }
        ic.endBatchEdit()
        // The last edit applied is the leftmost one, so the caret ends at the
        // first thing that changed. Mirror it into the cache, or a backspace
        // before the editor echoes the new selection targets the old spot.
        val last = edits.last()
        val caret = origin + last.start + last.text.length
        expectedSelStart = caret
        expectedSelEnd = caret
        return true
    }

    /**
     * Re-lints [text] directly, without the InputConnection round-trip.
     * Used right after a fix is applied: extracting the field again races the
     * batch edit (the editor may still report the old text, which matches
     * [GrammarUi.sourceText] and skips the check), so lint the string we just
     * committed instead. checkedOnce stays false until this completes so the
     * selection-update recheck re-lints rather than skipping if this job gets
     * cancelled mid-check.
     */
    private fun relintAfterFix(text: String) {
        grammarJob?.cancel()
        grammarJob = serviceScope.launch {
            _uiState.update {
                it.copy(
                    grammar = it.grammar.copy(
                        sourceText = text,
                        checking = true,
                        checkedOnce = false,
                    ),
                )
            }
            val lints = GrammarChecker.check(text, _uiState.value.settings.grammarDialect.ordinal)
            if (_uiState.value.panel != PanelMode.GRAMMAR) return@launch
            _uiState.update {
                it.copy(
                    grammar = GrammarUi(
                        sourceText = text,
                        lints = lints,
                        checking = false,
                        checkedOnce = true,
                    ),
                )
            }
        }
    }

    /** Tapped one fix chip: apply it and re-lint the result. */
    fun onGrammarFix(lint: GrammarLint, fix: GrammarFix) {
        vibrate()
        val source = _uiState.value.grammar.sourceText
        val edit = GrammarChecker.edit(source, lint, fix) ?: return
        val fixed = source.replaceRange(edit.start, edit.end, edit.text)
        if (fixed == source) return
        // The fix rewrites one span, so that is all the editor is asked to
        // change; the whole-field rewrite is only for fields whose offsets we
        // cannot address.
        if (!replaceFieldSpans(listOf(edit))) replaceFieldText(fixed)
        relintAfterFix(fixed)
    }

    /**
     * Tapped a card body (not a fix chip): jump the field cursor to the issue.
     * A multi-word span (sentence-level lint) parks the cursor at its start; a
     * word that needs swapping gets selected ready to overtype; a small fix
     * (add punctuation, recase) parks the cursor at the word's end. Offsets are
     * UTF-16 into [GrammarUi.sourceText], which [fieldTextOrigin] shifts onto
     * the field's own coordinates (a no-op for every editor that hands over the
     * whole field, which is nearly all of them).
     */
    fun onGrammarFocus(lint: GrammarLint) {
        vibrate()
        val ic = currentInputConnection ?: return
        val source = _uiState.value.grammar.sourceText
        val from = lint.start.coerceIn(0, source.length)
        val to = lint.end.coerceIn(from, source.length)
        val span = source.substring(from, to)
        val hasReplacement = lint.suggestions.any { fix ->
            fix.kind == "replace" && !fix.text.isNullOrEmpty() &&
                !fix.text.equals(span, ignoreCase = true)
        }
        val multiWord = span.trim().any { it.isWhitespace() }
        val origin = fieldTextOrigin ?: 0
        val start = origin + from
        val end = origin + to
        // Mirror each placement into the cache too, so a backspace before
        // the editor echoes the new selection hits the right target.
        when {
            multiWord -> {
                ic.setSelection(start, start)
                expectedSelStart = start
                expectedSelEnd = start
            }
            hasReplacement -> {
                ic.setSelection(start, end)
                expectedSelStart = start
                expectedSelEnd = end
            }
            else -> {
                ic.setSelection(end, end)
                expectedSelStart = end
                expectedSelEnd = end
            }
        }
    }

    /** Tapped "Fix all": apply every lint's top suggestion. */
    fun onGrammarFixAll() {
        vibrate()
        val source = _uiState.value.grammar.sourceText
        val edits = GrammarChecker.editsAll(source, _uiState.value.grammar.lints)
        var fixed = source
        for (edit in edits) fixed = fixed.replaceRange(edit.start, edit.end, edit.text)
        if (fixed == source) return
        // Back-to-front, in one batch edit: the untouched text between the
        // issues is never re-committed, so it keeps its styling.
        if (!replaceFieldSpans(edits)) replaceFieldText(fixed)
        relintAfterFix(fixed)
    }

    /** Dismissed a card: hide the lint until the field text changes again. */
    fun onGrammarDismiss(lint: GrammarLint) {
        vibrate()
        _uiState.update { it.copy(grammar = it.grammar.copy(lints = it.grammar.lints - lint)) }
    }

    fun onGrammarDialectChange(dialect: GrammarDialect) {
        vibrate()
        serviceScope.launch { settingsRepository.setGrammarDialect(dialect) }
        // Force a fresh lint: clear checkedOnce so the same text re-checks
        // under the new dialect (the settings flow updates asynchronously,
        // so check directly with the new value).
        _uiState.update { it.copy(grammar = it.grammar.copy(checkedOnce = false, checking = true)) }
        grammarJob?.cancel()
        grammarJob = serviceScope.launch {
            val source = extractFieldText()
            if (source.isBlank()) {
                _uiState.update { it.copy(grammar = GrammarUi()) }
                return@launch
            }
            val lints = GrammarChecker.check(source, dialect.ordinal)
            if (_uiState.value.panel != PanelMode.GRAMMAR) return@launch
            _uiState.update {
                it.copy(
                    grammar = GrammarUi(
                        sourceText = source,
                        lints = lints,
                        checking = false,
                        checkedOnce = true,
                    ),
                )
            }
        }
    }

    /**
     * The toolbar's caret-moving tools, which are the panel's moves one tap deep
     * instead of two. Holding shift turns them into shift+arrow: the move
     * extends the selection instead of collapsing it, exactly as a physical
     * keyboard's would.
     *
     * The shift is read but not spent. Repeated taps have to keep extending —
     * one character of selection is no use — and a shift the user pressed is
     * theirs to end, by pressing it again or by putting the caret somewhere
     * else. Nor does it touch the panel's own select mode: that toggle belongs
     * to the panel, and quietly leaving it on would keep these tools selecting
     * long after the shift went away.
     *
     * Only the toolbar reads shift this way. Inside the text-editing panel the
     * shift key isn't even on screen, so a shift left armed there is a leftover,
     * not an instruction, and the panel's select toggle stays the only answer.
     */
    private fun onCursorTool(action: TextEditAction) {
        onTextEdit(action, extendSelection = _uiState.value.shiftSelectsText)
    }

    /** Counts presses of the Selection mode tool into a tap, a double or a triple. */
    private val selectionTaps = SelectionTapCounter(SHIFT_DOUBLE_TAP_MS)

    /**
     * A press of the toolbar's Selection mode tool.
     *
     * One tap turns the mode on, and the next one turns it off: while it is on
     * every caret move extends the selection instead of collapsing it, wherever
     * the move comes from — the arrow tools, the text-editing panel, the
     * spacebar cursor swipe, the volume keys, a layout's own arrow keys. That is
     * the whole point of the mode over a held shift: the hand that moves the
     * caret is free.
     *
     * Two quick taps select the word at the cursor and three select the line,
     * both of which leave the mode on so the selection can be adjusted from
     * there. The first tap of a double has already toggled by then, which is
     * what the shift key's double tap does too and is the price of a mode that
     * comes on the instant it is asked for.
     *
     * Turning the mode off does not touch the selection. Only the extending
     * stops; what is selected stays selected, ready for copy or for typing over.
     */
    private fun onSelectModeTap() {
        val multiTap = _uiState.value.settings.textEditing.selectionModeMultiTap
        when (selectionTaps.tap(SystemClock.uptimeMillis(), multiTap)) {
            SelectionTap.TOGGLE -> {
                vibrate()
                if (_uiState.value.selectingText) clearSelectionArm() else armSelectionMode()
            }
            // These two buzz and commit through onTextEdit, the same path the
            // Select word and Select line tools take.
            SelectionTap.WORD -> {
                onTextEdit(TextEditAction.SELECT_WORD)
                armSelectionMode()
            }
            SelectionTap.LINE -> {
                onTextEdit(TextEditAction.SELECT_LINE)
                armSelectionMode()
            }
        }
    }

    /** The same ladder for a panel layout's Select key, counted separately. */
    private val selectKeyTaps = SelectionTapCounter(SHIFT_DOUBLE_TAP_MS)

    /**
     * A press of a Select key on a panel layout — the text-edit pad's own
     * toggle (issue #41).
     *
     * The Selection mode tool's ladder, on its own counter: two buttons that
     * share one would read a tap on each as a double and select a word nobody
     * asked for. One tap toggles, two select the word at the cursor, three the
     * line, and the two selecting rungs leave the arm on so the selection can
     * be nudged from there.
     *
     * Deliberately not [onSelectModeTap]: the plain toggle stays on the panel's
     * own [KeyboardUiState.textEditSelecting], which dies with the panel, while
     * the tool's sticky mode outlives it. Only the rung changes here, never
     * which flag the key owns.
     *
     * Silent: the key buzzed on the way down like every other panel key.
     */
    private fun onSelectKeyTap() {
        val multiTap = _uiState.value.settings.textEditing.selectionModeMultiTap
        when (selectKeyTaps.tap(SystemClock.uptimeMillis(), multiTap)) {
            SelectionTap.TOGGLE -> onTextEdit(TextEditAction.SELECT, haptic = false)
            SelectionTap.WORD -> {
                onTextEdit(TextEditAction.SELECT_WORD, haptic = false)
                _uiState.update { it.copy(textEditSelecting = true) }
            }
            SelectionTap.LINE -> {
                onTextEdit(TextEditAction.SELECT_LINE, haptic = false)
                _uiState.update { it.copy(textEditSelecting = true) }
            }
        }
    }

    /**
     * The Selection mode tool held down (true) and let go (false): selection
     * mode for exactly as long as the finger stays on the button.
     *
     * The release leaves the selection alone and the sticky mode alone. It ends
     * this hold and nothing else, so a hold inside a mode the user had already
     * switched on gives the mode back on release rather than taking it away.
     */
    fun onSelectionHold(down: Boolean) {
        // A hold is not a tap. Without this, the tap after a hold would count as
        // the second of a double and select a word nobody asked for. Both
        // counters, because either button's hold reaches this.
        selectionTaps.reset()
        selectKeyTaps.reset()
        _uiState.update { it.copy(selectionHold = down) }
    }

    /**
     * What a press and hold on a tool reaches, built outside
     * [ServiceKeyboardContent] for the reason [converterCallbacks] is: that
     * method sits against the JVM's 64K size ceiling, so the two callbacks
     * travel as one parameter and are constructed once, here.
     */
    private val toolHoldCallbacks by lazy {
        com.wasimaster.wmkeyboard.ime.ui.ToolHoldCallbacks(
            onSettings = ::openToolSettings,
            onHoldAction = ::runToolFromHold,
            onSelectionHold = ::onSelectionHold,
            onTrackpadHold = ::onTrackpadHold,
            dictionaryBar = com.wasimaster.wmkeyboard.ime.ui.DictionaryBarCallbacks(
                onToggle = ::onDictionaryChipToggle,
                onFilter = ::onDictionaryFilterSelect,
            ),
        )
    }

    /**
     * Whether the trackpad panel is open because the toolbar's Trackpad tool is
     * being held, so the release closes exactly what the press opened and
     * leaves a panel the user opened with a tap alone.
     */
    private var trackpadHeld = false

    /**
     * The Trackpad tool held down (true) and let go (false): the trackpad panel
     * for as long as the finger stays on the button, for a quick nudge with the
     * other thumb (issue #39). A hold over a panel a tap already opened does
     * nothing on either end, so the tap's panel outlives it.
     */
    fun onTrackpadHold(down: Boolean) {
        if (down) {
            if (_uiState.value.panel != PanelMode.TRACKPAD) {
                trackpadHeld = true
                onPanelChange(PanelMode.TRACKPAD)
            }
        } else if (trackpadHeld) {
            trackpadHeld = false
            // onPanelChange toggles: asking for the open panel closes it. Only
            // while it is still the one the hold opened, so a tool tapped mid-
            // hold keeps its own panel.
            if (_uiState.value.panel == PanelMode.TRACKPAD) {
                onPanelChange(PanelMode.TRACKPAD, haptic = false)
            }
        }
    }

    /** Arms the toolbar's selection mode, whatever the panel's own toggle says. */
    private fun armSelectionMode() {
        _uiState.update { it.copy(selectionMode = true) }
    }

    /**
     * Disarms selection mode from every source at once.
     *
     * Both surfaces that switch it off — the tool and the panel's Select key —
     * clear all three flags rather than their own, because the button the user
     * pressed reads as "selecting: off" and leaving another flag holding it on
     * would make the next arrow key extend a selection they just ended.
     */
    private fun clearSelectionArm() {
        _uiState.update {
            it.copy(textEditSelecting = false, selectionMode = false, selectionHold = false)
        }
    }

    /**
     * Text-editing panel buttons. Cursor moves go through the editor as key
     * events so apps handle them natively; while selection mode is on the
     * moves carry shift and extend the selection.
     *
     * [extendSelection] forces that on for a single call, for callers that have
     * their own reason to extend — see [onCursorTool]. It never turns extending
     * *off*: selection mode still wins when it is on, from whichever surface
     * armed it (see [KeyboardUiState.selectingText]).
     */
    fun onTextEdit(action: TextEditAction, extendSelection: Boolean = false, haptic: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (haptic) vibrate()
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        val selecting = extendSelection || _uiState.value.selectingText
        when (action) {
            TextEditAction.LEFT -> sendEditorKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            TextEditAction.RIGHT -> sendEditorKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            TextEditAction.UP -> sendEditorKey(KeyEvent.KEYCODE_DPAD_UP, selecting)
            TextEditAction.DOWN -> sendEditorKey(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            TextEditAction.HOME -> sendEditorKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            TextEditAction.END -> sendEditorKey(KeyEvent.KEYCODE_MOVE_END, selecting)
            TextEditAction.PAGE_UP -> sendEditorKey(KeyEvent.KEYCODE_PAGE_UP, selecting)
            TextEditAction.PAGE_DOWN -> sendEditorKey(KeyEvent.KEYCODE_PAGE_DOWN, selecting)
            // Ctrl+Arrow is the editor's move-by-word shortcut; with the panel's
            // select mode on it carries shift too and extends word by word.
            TextEditAction.WORD_LEFT ->
                sendEditorKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting, ctrl = true)
            TextEditAction.WORD_RIGHT ->
                sendEditorKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting, ctrl = true)
            TextEditAction.SELECT_WORD -> selectWordAtCursor(ic)
            TextEditAction.SELECT_LINE -> selectLineAtCursor(ic)
            // Reads the armed state rather than `selecting`, which a caller may
            // have forced on for this one call. Switching it on is the panel's
            // own flag, which dies with the panel; switching it off clears every
            // flag, so the key means what it says even when the toolbar's mode
            // is the one holding selection on.
            TextEditAction.SELECT ->
                if (_uiState.value.selectingText) {
                    clearSelectionArm()
                } else {
                    _uiState.update { it.copy(textEditSelecting = true) }
                }
            // Selects, and nothing else. This used to arm the panel's select
            // mode as well, which lit the Select key and the toolbar's mode
            // tool for a press that never asked for them, and left a mode on
            // that the user then had to find and switch off (#40).
            TextEditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            TextEditAction.COPY -> {
                ic.performContextMenuAction(android.R.id.copy)
                maybeToastCopied()
                _uiState.update { it.copy(textEditSelecting = false) }
            }
            TextEditAction.PASTE -> {
                if (!isClipboardAccessible()) return
                ic.performContextMenuAction(android.R.id.paste)
                purgeAfterPasswordPaste()
            }
            TextEditAction.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            // Ctrl+Home / Ctrl+End are the editor's whole-text moves, the same
            // way Ctrl+Arrow is its word move; with select mode on they carry
            // shift too and extend to the end.
            TextEditAction.DOC_START ->
                sendEditorKey(KeyEvent.KEYCODE_MOVE_HOME, selecting, ctrl = true)
            TextEditAction.DOC_END ->
                sendEditorKey(KeyEvent.KEYCODE_MOVE_END, selecting, ctrl = true)
            // Like copy, it ends the panel's select mode: the selection is gone.
            TextEditAction.CUT -> {
                ic.performContextMenuAction(android.R.id.cut)
                _uiState.update { it.copy(textEditSelecting = false) }
            }
        }
    }

    /**
     * Toast confirming text landed on the clipboard, for the users who opt in
     * (some fields give no copy feedback of their own). Off by default.
     */
    private fun maybeToastCopied() {
        if (_uiState.value.settings.feedback.toastOnCopy) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_copied_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Clipboard/undo/redo shortcuts fired by long-pressing A/C/V/X/Z/Y. Copy
     * and cut act on the current selection when one exists; with nothing
     * selected they select all first, so a bare long press copies or cuts the
     * whole field. Undo/redo delegate to [onUndoRedo], the same path the
     * toolbar's Undo/Redo tools use.
     *
     * [selectAllIfEmpty] is what makes that select-all convenience opt-out. It
     * suits a long press, which is a deliberate one-off gesture, and not a Mac
     * user's `Cmd+C`, where it would silently copy the whole message instead of
     * doing nothing the way every other keyboard on that machine does.
     */
    fun onClipboardKey(action: ClipboardKeyAction, selectAllIfEmpty: Boolean = true) {
        if (action == ClipboardKeyAction.UNDO || action == ClipboardKeyAction.REDO) {
            onUndoRedo(redo = action == ClipboardKeyAction.REDO)
            return
        }
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        val hasSelection = ic.getSelectedText(0)?.isNotEmpty() == true
        when (action) {
            ClipboardKeyAction.SELECT_ALL -> {
                ic.performContextMenuAction(android.R.id.selectAll)
                _uiState.update { it.copy(textEditSelecting = true) }
            }
            ClipboardKeyAction.COPY -> {
                if (!hasSelection && selectAllIfEmpty) ic.performContextMenuAction(android.R.id.selectAll)
                ic.performContextMenuAction(android.R.id.copy)
                maybeToastCopied()
                _uiState.update { it.copy(textEditSelecting = false) }
            }
            ClipboardKeyAction.CUT -> {
                if (!hasSelection && selectAllIfEmpty) ic.performContextMenuAction(android.R.id.selectAll)
                ic.performContextMenuAction(android.R.id.cut)
                _uiState.update { it.copy(textEditSelecting = false) }
            }
            ClipboardKeyAction.PASTE -> {
                if (!isClipboardAccessible()) return
                ic.performContextMenuAction(android.R.id.paste)
                purgeAfterPasswordPaste()
            }
            ClipboardKeyAction.UNDO, ClipboardKeyAction.REDO -> Unit
        }
    }

    /**
     * DPAD/home/end navigation; with [shift] the move extends the selection,
     * and with [ctrl] it moves by whole words (the editor's Ctrl+Arrow binding).
     */
    private fun sendEditorKey(code: Int, shift: Boolean, ctrl: Boolean = false) {
        if (!shift && !ctrl) {
            sendDownUpKeyEvents(code)
            return
        }
        val ic = currentInputConnection ?: return
        val time = android.os.SystemClock.uptimeMillis()
        var meta = 0
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // A bare meta flag isn't enough for every editor: TextView tracks
        // modifier state from the modifier keys' own down/up events, so wrap
        // the arrow in real shift/ctrl presses like a hardware keyboard would.
        if (shift) {
            ic.sendKeyEvent(
                KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta)
            )
        }
        if (ctrl) {
            ic.sendKeyEvent(
                KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, meta)
            )
        }
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0, meta))
        if (ctrl) {
            ic.sendKeyEvent(
                KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, meta)
            )
        }
        if (shift) {
            ic.sendKeyEvent(
                KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta)
            )
        }
    }

    /**
     * Selects the word straddling the cursor: walks out to the word boundaries
     * on either side of the caret and sets the selection to span them. A caret
     * on whitespace or punctuation (no word to grab) is left untouched. The
     * offsets from [getExtractedText] are used directly as document positions,
     * matching how the rest of this service treats them.
     *
     * Selects, and nothing else: it does not arm selection mode. It used to,
     * which meant the trackpad's double tap and the Select word tool switched
     * on a mode nobody asked for and left it on (#78). The two callers that do
     * want the mode after selecting — the Selection mode tool's ladder and a
     * panel's Select key — arm it themselves, the same split [TextEditAction.SELECT_ALL]
     * got in #40.
     */
    private fun selectWordAtCursor(ic: InputConnection) {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = et.text ?: return
        val n = text.length
        // Anchor at the caret (selection end); clamp defensively.
        val caret = et.selectionEnd.let { if (it in 0..n) it else et.selectionStart }
        if (caret !in 0..n) return
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '_'
        var start = caret
        var end = caret
        while (start > 0 && isWord(text[start - 1])) start--
        while (end < n && isWord(text[end])) end++
        if (start == end) return
        ic.setSelection(start, end)
        // The range is known; recording it directly closes the window where
        // the cache still said "collapsed" and a fast backspace would have
        // deleted the character before the selection instead of it.
        expectedSelStart = start
        expectedSelEnd = end
    }

    /**
     * Selects the entire line the cursor sits on: walks out to the nearest
     * newline on either side and sets the selection to span the line content
     * (newlines themselves are excluded). An empty line leaves the caret
     * untouched, matching [selectWordAtCursor]'s no-op on bare whitespace, and
     * like it this arms no mode of its own (#78).
     */
    private fun selectLineAtCursor(ic: InputConnection) {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = et.text ?: return
        val n = text.length
        val caret = et.selectionEnd.let { if (it in 0..n) it else et.selectionStart }
        if (caret !in 0..n) return
        fun isLineBreak(c: Char) = c == '\n' || c == '\r'
        var start = caret
        var end = caret
        while (start > 0 && !isLineBreak(text[start - 1])) start--
        while (end < n && !isLineBreak(text[end])) end++
        if (start == end) return
        ic.setSelection(start, end)
        expectedSelStart = start
        expectedSelEnd = end
    }

    /**
     * One stable bundle for the snippets panel, built outside
     * [ServiceKeyboardContent] for the reason [converterCallbacks] is: that
     * method sits against the JVM's 64K size ceiling, and folders needed two
     * callbacks more than the one parameter it used to spend.
     */
    private val snippetPanelCallbacks by lazy {
        com.wasimaster.wmkeyboard.ime.ui.SnippetPanelCallbacks(
            onSnippet = ::onSnippetTapped,
            onSnippetHold = ::onSnippetHeld,
            onFolderOpen = ::onSnippetFolderOpen,
            onFolderToggle = ::onSnippetFolderToggle,
            onPickerPick = ::onSnippetPickerPick,
            onPickerDrill = ::onSnippetPickerDrill,
            onPickerBack = ::onSnippetPickerBack,
        )
    }

    /** Drills the snippets panel into a folder, or backs out of one with null. */
    fun onSnippetFolderOpen(folderId: Long?) {
        vibrate()
        _uiState.update { it.copy(snippetFolderOpen = folderId, snippetPicker = null) }
    }

    /**
     * Long press on a folder in the panel: arms or disarms its triggers.
     *
     * Written straight through to the shared file, because the settings app
     * reads it as its own source of truth and would otherwise show the folder
     * still on. [snippetsStamp] is caught up in the same breath so the write
     * this process just made does not read back as somebody else's edit and
     * cost a needless reload.
     */
    fun onSnippetFolderToggle(folderId: Long) {
        val folder = snippetStore.folder(folderId) ?: return
        snippetStore.setFolderEnabled(folderId, !folder.enabled)
        _uiState.update { it.copy(snippetFolders = snippetStore.folders()) }
        serviceScope.launch {
            withContext(Dispatchers.IO) { snippetStore.save() }
            snippetsStamp = snippetsFile?.lastModified() ?: snippetsStamp
        }
    }

    /**
     * Hold on a snippet tile: shows everything that snippet has to offer,
     * instead of inserting the first thing.
     *
     * A tap stays the fast path — one tile, one insertion, no decision — and
     * this is where the rest of a snippet's expansions and the snippets it
     * links to live. Nothing happens for a tile with only one thing to say.
     */
    fun onSnippetHeld(snippet: Snippet) {
        val rows = snippetPickerRows(snippet)
        if (rows.size < 2) return
        vibrate()
        _uiState.update {
            it.copy(
                snippetPicker = SnippetPickerUi(
                    rootId = snippet.id,
                    title = snippet.label,
                    rows = rows,
                ),
            )
        }
    }

    /**
     * What the panel's picker lists for [snippet].
     *
     * A pattern snippet's captures are blanks here, exactly as a tap on its
     * tile leaves them: there is no typed text for them to have caught.
     */
    private fun snippetPickerRows(snippet: Snippet): List<SnippetChip> {
        val patternOnly = snippet.trigger.isNullOrBlank() && !snippet.triggerPattern.isNullOrBlank()
        return snippetStore
            .candidates(snippet, context = snippetContext(currentInputConnection), blank = patternOnly)
            .map(::chipOf)
    }

    /** Inserts the row the picker was holding, and closes the panel. */
    fun onSnippetPickerPick(index: Int) {
        val picker = _uiState.value.snippetPicker ?: return
        val chip = picker.rows.getOrNull(index) ?: return
        insertSnippetText(chip.text, chip.cursorOffset)
    }

    /** Shows what the linked snippet in row [index] offers. */
    fun onSnippetPickerDrill(index: Int) {
        val picker = _uiState.value.snippetPicker ?: return
        val chip = picker.rows.getOrNull(index) ?: return
        if (!chip.drillable || !picker.canDrill(chip.snippetId)) return
        val child = snippetStore.item(chip.snippetId) ?: return
        val rows = snippetPickerRows(child)
        if (rows.isEmpty()) return
        vibrate()
        _uiState.update {
            it.copy(
                snippetPicker = picker.copy(
                    title = child.label,
                    rows = rows,
                    path = picker.path + chip.snippetId,
                ),
            )
        }
    }

    /** Back out of the picker: one level up, then back to the tiles. */
    fun onSnippetPickerBack() {
        val picker = _uiState.value.snippetPicker ?: return
        if (picker.path.isEmpty()) {
            _uiState.update { it.copy(snippetPicker = null) }
            return
        }
        val path = picker.path.dropLast(1)
        val root = snippetStore.item(path.lastOrNull() ?: picker.rootId) ?: run {
            _uiState.update { it.copy(snippetPicker = null) }
            return
        }
        _uiState.update {
            it.copy(
                snippetPicker = picker.copy(
                    title = root.label,
                    rows = snippetPickerRows(root),
                    path = path,
                ),
            )
        }
    }

    fun onSnippetTapped(snippet: Snippet) {
        vibrate()
        val ic = currentInputConnection
        val context = snippetContext(ic)
        // A pattern snippet is written around what its capture groups will
        // hold, and a tap has nothing to put in them. It goes in with the
        // blanks left empty and the caret in the first one, so the user types
        // the missing part where it belongs. A plain snippet takes the ordinary
        // path, where a "$1" in its text is a dollar sign and a one.
        val text: String
        val caret: Int
        if (snippet.trigger.isNullOrBlank() && !snippet.triggerPattern.isNullOrBlank()) {
            val blank = SnippetMatcher.blankTemplate(snippet.text, context = context)
            text = blank.text
            caret = blank.blankCaret
        } else {
            val expanded = SnippetStore.expandWithCursor(snippet.text, context = context)
            text = expanded.text
            caret = expanded.cursorOffset
        }
        insertSnippetText(text, caret)
    }

    /** Puts an already-expanded snippet in the field and closes the panel. */
    private fun insertSnippetText(text: String, caret: Int) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            // A word still composing commits first — a bare commitText would
            // replace the composing region with the snippet and leave the
            // stale buffer to resurrect the word at the next keystroke.
            commitComposing(ic, autocorrect = false)
            commitSplitAtCaret(ic, text, caret)
            ic.endBatchEdit()
            invalidateExpectedSelection()
            invalidateRecentWords()
        }
        _uiState.update { it.copy(panel = PanelMode.NONE, snippetPicker = null) }
    }

    /**
     * The app, clipboard and selection a snippet's variables expand against.
     *
     * [withSelection] is false on the typing path, where reading the selection
     * costs a blocking round-trip to answer a question already settled: a word
     * only reaches a trigger with the caret collapsed, since space and the
     * punctuation keys replace a selection and return long before this.
     */
    private fun snippetContext(
        ic: InputConnection?,
        withSelection: Boolean = true,
    ): SnippetStore.Companion.Context {
        val pkg = currentPackage
        return SnippetStore.Companion.Context(
            clipboard = clipboardStore.latestText().takeIf { isClipboardAccessible() },
            appName = pkg?.let(::appLabel),
            packageName = pkg,
            selection = if (withSelection) ic?.getSelectedText(0)?.toString() else null,
        )
    }

    /**
     * Re-reads the snippets file when the settings app has written to it.
     *
     * The two processes share one file with no change feed between them, so the
     * keyboard watches the modification time. Cheap enough to check whenever a
     * field opens, which is what stops a snippet saved a moment ago from
     * looking broken until the panel happens to be opened.
     */
    /**
     * How many things each snippet has to offer, for the tiles that say so.
     *
     * Only the ones with more than one are listed: the map is published into
     * the UI state on every reload, and a snippet with a single expansion —
     * which is nearly all of them — has nothing for a tile to draw.
     */
    private fun snippetCandidateCounts(): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (snippet in snippetStore.items()) {
            if (!snippet.hasChoices()) continue
            val count = snippetStore.candidateCount(snippet)
            if (count > 1) out[snippet.id] = count
        }
        return out
    }

    private fun reloadSnippetsIfChanged() {
        val file = snippetsFile ?: return
        val stamp = file.lastModified()
        if (stamp == snippetsStamp) return
        snippetsStamp = stamp
        snippetStore.reload()
        _uiState.update {
            it.copy(
                snippets = snippetStore.items(),
                snippetFolders = snippetStore.folders(),
                snippetCandidateCounts = snippetCandidateCounts(),
                snippetPicker = null,
            )
        }
    }

    /** Human-readable label for [pkg], falling back to the package name. */
    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    /**
     * Resolves the face to show for a suggested/searched emoji: the global
     * default skin tone, unless the "override with last used" option is on and
     * a variant was picked for this base (see [KeyboardSettings.emoji]).
     */
    private fun applyEmojiTone(emoji: String): String {
        val emojiSettings = _uiState.value.settings.emoji
        return _uiState.value.emojiVariants.tonedDisplay(
            base = emoji,
            tone = emojiSettings.defaultSkinTone.toneIndex,
            preferred = if (emojiSettings.toneOverrideByLastUsed) {
                emojiUsage.preferredVariant(emoji)
            } else {
                null
            },
            overrideWithPreferred = emojiSettings.toneOverrideByLastUsed,
        )
    }

    /**
     * Recomputes [KeyboardUiState.hiddenEmoji] — the emoji nothing on the
     * device can draw — for the current font and toggle, and reads the chosen
     * font's coverage tables while it is off the main thread anyway.
     *
     * The warm-up is not conditional on the toggle: every emoji drawn goes
     * through [EmojiFontShaping], so the tables are needed either way, and the
     * only question is whether the frame that draws the first emoji pays for
     * them.
     */
    private fun recomputeHiddenEmoji(settings: KeyboardSettings) {
        val catalog = emojiEntries
        val fontFile = KeyboardFonts.emojiFontFile(
            this,
            settings.emojiFont,
            settings.emojiFontInstalled.installedId,
        )
        if (!settings.emoji.hideUnrenderable || catalog.isEmpty()) {
            if (_uiState.value.hiddenEmoji.isNotEmpty()) {
                _uiState.update { it.copy(hiddenEmoji = emptySet()) }
            }
            serviceScope.launch {
                withContext(Dispatchers.Default) { EmojiFontShaping.warm(fontFile) }
            }
            return
        }
        serviceScope.launch {
            val hidden = withContext(Dispatchers.Default) {
                EmojiFontShaping.warm(fontFile)
                EmojiRenderCheck.unrenderable(catalog.map { it.emoji }, fontFile)
            }
            _uiState.update { it.copy(hiddenEmoji = hidden) }
        }
    }

    /**
     * Copies [emojiUsage]'s history into the UI state — the recents and
     * most-used grids plus the favourites they pin.
     *
     * Only ever called when a history surface is off screen or about to come
     * into view (a fresh field, a panel opening or closing, the emoji row
     * being unfolded), so the grids never re-sort while the user is tapping
     * through them; a tap only marks the snapshot stale (see
     * [emojiHistoryStale]). [force] is for the panel's own history edits —
     * favouriting, removing, clearing — where the whole point of the tap is to
     * see the list change.
     */
    private fun publishEmojiHistory(force: Boolean = false) {
        if (!force && !emojiHistoryStale) return
        emojiHistoryStale = false
        _uiState.update {
            it.copy(
                emojiRecents = emojiUsage.recents(),
                emojiFrequents = emojiUsage.frequents(),
                emojiFavourites = emojiUsage.favourites(),
            )
        }
    }

    fun onEmojiTapped(emoji: String) {
        vibrate()
        commitToField(emoji)
        learnEmoji(emoji)
        recordEmojiUse(emoji)
        // "Return to keyboard after emoji": one insertion from the panel drops
        // straight back to the keys instead of keeping the panel open for a run.
        val closeAfter = _uiState.value.settings.emoji.closeAfterInsert &&
            _uiState.value.panel == PanelMode.EMOJI
        _uiState.update {
            it.copy(
                panel = if (closeAfter) PanelMode.NONE else it.panel,
                emojiSearchActive = if (closeAfter) false else it.emojiSearchActive,
                emojiQuery = if (closeAfter) "" else it.emojiQuery,
                emojiResults = if (closeAfter) emptyList() else it.emojiResults,
                // The ring belongs to the panel it indexes into; the panel
                // folding away takes it along.
                panelFocus = if (closeAfter) null else it.panelFocus,
            )
        }
        // The panel just uncovered the emoji row, so the row is free to take
        // the new ranking now instead of waiting for the next field.
        if (closeAfter) publishEmojiHistory()
    }

    /**
     * A kaomoji or emoticon tapped in the emoji panel's text-art tabs.
     *
     * Committed as plain text and deliberately kept out of the emoji history
     * and the bigram lexicon: "(╯°□°）╯︵ ┻━┻" is not an emoji, and both the
     * recents grid and the emoji row size their cells for a single glyph.
     */
    fun onTextArtTapped(art: String) {
        vibrate()
        commitToField(art)
        // Same "return to keyboard after emoji" courtesy as a real emoji tap.
        if (_uiState.value.settings.emoji.closeAfterInsert &&
            _uiState.value.panel == PanelMode.EMOJI
        ) {
            _uiState.update {
                it.copy(
                    panel = PanelMode.NONE,
                    emojiSearchActive = false,
                    emojiQuery = "",
                    emojiResults = emptyList(),
                )
            }
        }
    }

    /**
     * A variant picked from the long-press popup: commit it and remember it
     * as the preferred face of [base] so the grid shows it from now on.
     * Picking the plain base resets the preference.
     */
    fun onEmojiVariantPicked(base: String, variant: String) {
        emojiUsage.setPreferredVariant(base, variant)
        _uiState.update { it.copy(emojiVariantPrefs = emojiUsage.variantPrefs()) }
        onEmojiTapped(variant)
    }

    /**
     * A long-press popup opened on [emoji]: fetch its animated version, if
     * Google publishes one, so the popup can loop it.
     *
     * The preview is the WebP, which is a third of the GIF for the same
     * animation — a long press is also how skin tones and favourites are
     * reached, so most of them never send anything and shouldn't cost 800 KB.
     * The GIF is fetched only if the send button is actually pressed.
     */
    fun onEmojiLongPressed(emoji: String) {
        val key = animatedEmojiKey(emoji) ?: return
        // The preview is the part nobody asked for — most long presses are
        // after a skin tone — so data saving holds it unless it is outright
        // allowed. The send button still works: pressing it is the answer.
        if (!dataSaverStatus.allows(MeteredFeature.ANIMATED_EMOJI)) return
        val url = animatedEmoji.webpUrl(key)
        animatedEmojiJob?.cancel()
        _uiState.update { it.copy(animatedEmojiFile = null, animatedEmojiLoading = true) }
        animatedEmojiJob = serviceScope.launch {
            val file = withContext(Dispatchers.IO) { downloadMediaFile(url, MediaMime.WEBP) }
            _uiState.update { it.copy(animatedEmojiFile = file, animatedEmojiLoading = false) }
        }
    }

    /** The popup closed: stop the fetch and drop the preview. */
    fun onEmojiLongPressDismissed() {
        animatedEmojiJob?.cancel()
        animatedEmojiJob = null
        _uiState.update { it.copy(animatedEmojiFile = null, animatedEmojiLoading = false) }
    }

    /**
     * The animated key for [emoji], honouring the setting and the field: a
     * field that takes no images has nowhere to put a GIF.
     */
    private fun animatedEmojiKey(emoji: String): String? {
        val state = _uiState.value
        if (!state.settings.emoji.animated || !state.acceptsRichMedia) return null
        return state.animatedEmoji.keyFor(emoji)
    }

    /**
     * "Send animated emoji" in the long-press popup: fetches the 512×512 GIF
     * and commits it through the same content path as a sticker. Counts as
     * using the emoji, so it lands in recents like a plain tap would.
     *
     * The GIF rather than the WebP already in hand: an app that accepts
     * `image/webp` may still draw only its first frame, and a still emoji is
     * not what the button promised.
     */
    fun onAnimatedEmojiSend(emoji: String) {
        val key = animatedEmojiKey(emoji) ?: return
        when (dataSaverStatus.decide(MeteredFeature.ANIMATED_EMOJI)) {
            // Pressing send on a metered connection is the yes the setting
            // asked for, and it holds for the rest of the session.
            MeteredDecision.ASK -> grantMetered(MeteredFeature.ANIMATED_EMOJI)
            // The button is not drawn in this case; guarded anyway, since a
            // hardware shortcut could still reach here.
            MeteredDecision.BLOCKED -> return
            MeteredDecision.ALLOWED -> Unit
        }
        recordEmojiUse(emoji)
        // The popup deliberately stays open, preview and all: sending one
        // animation is usually not the last thing someone wants to do with it,
        // and a popup that empties itself on the way out reads as a glitch.
        // The ordinary media path from here: it vibrates, publishes the id the
        // popup spins on while the file comes down, and toasts if it doesn't.
        insertDownloadedImage(
            key,
            animatedEmoji.gifUrl(key),
            MediaMime.GIF,
            _uiState.value.settings.gifSendMode,
        )
    }

    /**
     * "Send as sticker" in the long-press popup: draws [emoji] itself at
     * 512×512 in the keyboard's own emoji font and commits it as a WebP
     * sticker, so a face the receiving app has no font for still arrives as
     * the picture the sender saw.
     *
     * Rendered rather than downloaded — the glyph is already on the device —
     * so this works offline and sends nothing anywhere.
     */
    fun onEmojiStickerSend(emoji: String) {
        val state = _uiState.value
        if (!state.settings.emoji.sendAsSticker || !state.acceptsRichMedia) return
        if (state.mediaDownloadingId != null) return
        vibrate()
        recordEmojiUse(emoji)
        startMediaDownload(emojiStickerJobId(emoji))
        mediaInsertJob = serviceScope.launch {
            // Off the main thread as far as the provider call, which can be a
            // download the very first time Noto is asked for on a device.
            val typeface = KeyboardFonts.emojiTypeface(
                this@WMKeyboardService,
                state.settings.emojiFont,
                state.settings.emojiFontInstalled.installedId,
            )
            val file = withContext(Dispatchers.Default) { renderEmojiSticker(emoji, typeface) }
            endMediaDownload()
            if (file == null) {
                Toast.makeText(
                    this@WMKeyboardService,
                    getString(R.string.ime_service_media_download_error_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            // The sticker tool's own send mode, so one preference governs
            // every sticker the keyboard sends and WhatsApp keeps getting real
            // ones. Anybody an app treats badly for it has a lever already.
            commitImageFile(file, MediaMime.WEBP, state.settings.stickerSendMode)
        }
    }

    /**
     * Draws one emoji into a square transparent WebP, in whichever font the
     * keyboard draws it with — an imported or installed face by its own file,
     * anything else by the phone's own emoji font, which is what "System" and
     * "Google" both come down to once there is no file to load.
     *
     * Blocking; call it off the main thread. Cached per emoji *and* font, so
     * changing the font doesn't keep sending the old face.
     */
    private fun renderEmojiSticker(emoji: String, chosen: Typeface?): File? = runCatching {
        val settings = _uiState.value.settings
        val fontFile = KeyboardFonts.emojiFontFile(
            this,
            settings.emojiFont,
            settings.emojiFontInstalled.installedId,
        )
        // The same spelling the panel draws: a font with no variation-selector
        // table cannot resolve ❤️, and the shaper's answer is to strip the
        // selector or hand the glyph back to the system font. Only a font with
        // a file has tables to read; Noto and the system font are trusted with
        // the emoji as written.
        val spelling = EmojiFontShaping.forFontFile(fontFile).spelling(emoji)
        val typeface = chosen?.takeIf { !spelling.systemFont }

        val dir = File(cacheDir, "media").apply { mkdirs() }
        pruneMediaCache(dir)
        val stamp = "$STICKER_RENDER_VERSION|${spelling.text}|" +
            "${settings.emojiFont}|${settings.emojiFontInstalled.installedId}"
        // Digested, not hashCode()'d. A 32-bit hash over a couple of thousand
        // emoji collides in practice, not in theory: 😘 and 🧹 land on the same
        // number, so whoever sent the broom second got a kiss out of the cache.
        val target = File(dir, "emoji_${digest(stamp)}.webp")
        if (target.exists() && target.length() > 0L) return@runCatching target

        val bitmap = createBitmap(StickerImage.TARGET_SIZE, StickerImage.TARGET_SIZE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            textSize = STICKER_GLYPH_PX
        }
        // Emoji fonts vary in how much of the em square they fill, so the size
        // is measured rather than assumed: draw once at a nominal size, then
        // scale by what came back so every sticker fills its frame the same.
        val bounds = Rect()
        paint.getTextBounds(spelling.text, 0, spelling.text.length, bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val scale = STICKER_GLYPH_PX / maxOf(bounds.width(), bounds.height()).toFloat()
            paint.textSize *= scale
            paint.getTextBounds(spelling.text, 0, spelling.text.length, bounds)
        }
        Canvas(bitmap).drawText(
            spelling.text,
            StickerImage.TARGET_SIZE / 2f,
            StickerImage.TARGET_SIZE / 2f - bounds.exactCenterY(),
            paint,
        )
        // Encoded by the sticker tool's own encoder rather than by hand, so
        // what leaves here has the same shape as a sticker from a pack: same
        // size, same lossy WebP, same 100 KB ceiling. A difference between the
        // two is a difference in how apps treat them, and there is no reason
        // for one to exist.
        val png = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        val processed = (StickerImage.process(png) as? StickerImage.Result.Ok)?.sticker
            ?: return@runCatching null
        target.writeBytes(processed.bytes)
        target
    }.getOrNull()

    /** A short, collision-free name for a cache key of any shape. */
    private fun digest(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }




    fun onEmojiFavouriteToggled(emoji: String) {
        vibrate()
        emojiUsage.toggleFavourite(emoji)
        emojiUsage.save()
        publishEmojiHistory(force = true)
    }

    fun onEmojiFavouritesReordered(order: List<String>) {
        vibrate()
        emojiUsage.reorderFavourites(order)
        emojiUsage.save()
        publishEmojiHistory(force = true)
    }

    fun onEmojiRecentsClear() {
        vibrate()
        emojiUsage.clearRecents()
        emojiUsage.save()
        publishEmojiHistory(force = true)
    }

    /** Long-press "remove" on a history cell: the emoji leaves recents,
     * most-used counts, and favourites in one go. */
    fun onEmojiRecentRemoved(emoji: String) {
        vibrate()
        emojiUsage.removeFromHistory(emoji)
        emojiUsage.save()
        publishEmojiHistory(force = true)
    }

    /**
     * The emoji search bar's backspace: while search is active the keys
     * edit the query, so this is the only way to delete the emoji (or any
     * text) just committed to the real field without leaving search.
     */
    fun onEmojiSearchFieldDelete() {
        vibrate()
        deleteFromField()
    }

    /**
     * Whether a backspace press would still delete anything, honoring
     * whatever the backspace key currently edits (an active search query,
     * or the field). Held-repeat loops poll this to stop at empty.
     */
    fun canDelete(): Boolean {
        val state = _uiState.value
        // Branch order mirrors [onDelete] exactly — a case missing here makes
        // the held-repeat loop poll against a different target than the one
        // backspace actually edits (spinning forever, or stopping early).
        return when {
            (state.panel == PanelMode.HANDWRITING || keyboardHandwriteActive(state)) &&
                state.handwriting.strokes.isNotEmpty() -> true
            state.typingTestActive -> state.typingTest.current.isNotEmpty()
            state.aiCustomInputActive ->
                (state.ai as? AiUi.CustomInput)?.instruction?.isNotEmpty() == true
            state.calcTypingActive -> state.calcExpression.isNotEmpty()
            state.converterTypingActive -> state.converterValue.isNotEmpty()
            state.emojiSearchActive -> state.emojiQuery.isNotEmpty()
            state.dictionarySearchActive -> state.dictionaryQuery.isNotEmpty()
            state.clipboardSearchActive -> state.clipboardQuery.isNotEmpty()
            state.mediaSearchActive && state.panel.hasMediaSearch -> state.mediaQuery.isNotEmpty()
            state.pluginTypingActive ->
                state.pluginInputs[state.pluginFocusedInput].orEmpty().isNotEmpty()
            else -> canDeleteField()
        }
    }

    /** [canDelete] scoped to the real field only, for field-direct controls. */
    fun canDeleteField(): Boolean {
        if (composing.isNotEmpty()) return true
        val ic = currentInputConnection ?: return false
        if (hasSelection(ic)) return true
        // A null answer means the editor can't say — keep deleting rather
        // than stopping a working backspace; only a definite "" stops it.
        val before = ic.getTextBeforeCursor(1, 0) ?: return true
        return before.isNotEmpty()
    }

    /**
     * An emoji candidate from the suggestion strip. In [EmojiInsertMode.REPLACE]
     * (Gboard semantics) committing over the active composing region swaps
     * the typed word for the emoji; in [EmojiInsertMode.APPEND] the word is
     * kept ("birthday 🎂") and learned like a normal commit.
     *
     * [held] flips that for one insert: holding a candidate runs whichever of
     * the two the setting did *not* pick, so the less-usual one is a long press
     * away instead of a trip to settings. With nothing composing the two modes
     * do the same thing, so a hold there is an ordinary insert.
     */
    /**
     * A word on the suggestion strip was held and "never suggest" chosen.
     *
     * Adds it to the blacklist, which is the whole of the job: the settings
     * collector hands the new set to the engine and [purgeBlacklisted] takes
     * the word back out of the personal lexicon and the waiting room, so a word
     * the keyboard learned from the user stops being offered as well as one it
     * shipped with. The word stays typeable, and the blacklist screen in
     * settings is where it can be taken back off (issue #28).
     */
    fun onSuggestionHeld(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        vibrate()
        serviceScope.launch { settingsRepository.addSuggestionBlacklistWord(trimmed) }
    }

    fun onEmojiSuggestionTapped(emoji: String, held: Boolean = false) {
        vibrate()
        val ic = currentInputConnection ?: return
        lastGestureWord = null
        val word = composing.toString()
        val append = (_uiState.value.settings.emojiInsertMode == EmojiInsertMode.APPEND) != held
        if (append && word.isNotEmpty()) {
            ic.finishComposingText()
            ic.commitText(" $emoji", 1)
            learn(word, caseTrusted = composingCaseTrusted)
        } else {
            ic.commitText(emoji, 1)
        }
        learnEmoji(emoji)
        recordEmojiUse(emoji)
        composing = StringBuilder()
        _uiState.update {
            it.copy(
                composingPreview = "",
                suggestions = emptyList(),
                emojiSuggestions = emptyList(),
                morsePending = "",
            )
        }
        refreshSuggestions()
    }

    fun onEmojiSearchToggled() {
        _uiState.update { it.copy(emojiSearchActive = !it.emojiSearchActive) }
    }

    private fun refreshEmojiResults() {
        val search = emojiSearch ?: return
        val query = _uiState.value.emojiQuery
        serviceScope.launch {
            val hidden = _uiState.value.hiddenEmoji
            val results = withContext(Dispatchers.Default) { search.search(query) }
            val shown = if (hidden.isEmpty()) results else results.filterNot { it.emoji in hidden }
            updateQuery { it.copy(emojiResults = shown) }
        }
    }

    /**
     * Fold the current panel away and return to the keys after a single insert,
     * when "Return to keyboard after inserting" ([EmojiSettings.closeAfterInsert])
     * is on. Shared by the emoji panel (see [onEmojiTapped]) and the clipboard
     * panel, so one paste drops back to typing instead of leaving the panel up.
     * A no-op when nothing is open — a strip chip tapped with no panel showing
     * has nothing to close.
     */
    private fun closePanelAfterInsertIfEnabled() {
        if (!_uiState.value.settings.emoji.closeAfterInsert) return
        _uiState.update {
            if (it.panel == PanelMode.NONE) {
                it
            } else {
                it.copy(
                    panel = PanelMode.NONE,
                    emojiSearchActive = false,
                    emojiQuery = "",
                    emojiResults = emptyList(),
                    panelFocus = null,
                )
            }
        }
    }

    /** Converts a held clipboard image to the same 512px transparent WebP sticker
     * format used by the existing sticker flow, then commits it to the field. */
    fun onClipboardSticker(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        if (!isClipboardAccessible() || item.kind != ClipKind.IMAGE ||
            !_uiState.value.acceptsRichMedia) return
        val source = item.imagePath?.let(::File)?.takeIf { it.exists() } ?: return
        vibrate()
        serviceScope.launch {
            val sticker = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = source.readBytes()
                    (StickerImage.process(bytes) as? com.wasimaster.wmkeyboard.core.stickers.StickerImage.Result.Ok)
                        ?.sticker?.bytes
                }.getOrNull()
            }
            if (sticker == null) {
                Toast.makeText(
                    this@WMKeyboardService,
                    getString(R.string.ime_service_media_download_error_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val dir = File(cacheDir, "media").apply { mkdirs() }
            val file = File(dir, "clipboard_sticker_${item.id}.webp")
            runCatching { file.writeBytes(sticker) }.onSuccess {
                commitImageFile(file, MediaMime.WEBP, _uiState.value.settings.stickerSendMode)
            }
        }
    }

    fun onClipboardItemTapped(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        if (!isClipboardAccessible()) return
        vibrate()
        when (item.kind) {
            ClipKind.IMAGE -> commitImageClip(item)
            // A video is a file whose bytes another app owns — the same
            // commitContent handoff, with the video MIME doing the negotiating.
            ClipKind.FILE, ClipKind.VIDEO -> commitFileClip(item)
            // A folder is a container, not content — there is nothing to attach
            // to a text field, so insert its name and hand the URI back to the
            // system clipboard for a file manager to paste.
            ClipKind.FOLDER -> {
                commitToField(item.fileName.orEmpty())
                item.uriString?.let { copyUriToSystemClipboard(Uri.parse(it), item.fileName.orEmpty()) }
                Toast.makeText(
                    this,
                    getString(R.string.ime_service_folder_insert_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            // A clip that is nothing but a code is pasted into a code box far
            // more often than anywhere else, so it goes in character by
            // character like the chips do.
            else -> if (ClipSensitivity.isBareCode(item.text.trim())) {
                pastedCodeClipId = item.id
                commitCodeToField(item.text.trim())
            } else {
                commitToField(item.text)
            }
        }
        // Whether tapped from the panel or the strip chip, the recent-copy chip
        // has served its purpose once something was pasted.
        clearClipboardSuggestion()
        purgeAfterPasswordPaste(item)
        closePanelAfterInsertIfEnabled()
    }

    /**
     * A fragment chip was tapped: paste just that part of the clip.
     *
     * The clip itself stays in history — only a piece of it was used. The
     * password sweep still runs against the parent clip, because a code pasted
     * into a password field leaves the whole SMS sitting on the system
     * clipboard for every app to read.
     */
    fun onClipboardEntityTapped(entity: com.wasimaster.wmkeyboard.core.clipboard.ClipEntity) {
        if (!isClipboardAccessible()) return
        vibrate()
        // A code out of a clip goes in the same way a code off the chip does:
        // character by character, for the boxes that take one each.
        if (entity.kind == ClipEntityKind.OTP) {
            commitCodeToField(entity.value)
        } else {
            commitToField(entity.value)
        }
        clearClipboardSuggestion()
        clipboardStore.items().firstOrNull { it.id == entity.sourceId }
            ?.let(::purgeAfterPasswordPaste)
        closePanelAfterInsertIfEnabled()
    }

    /**
     * Privacy sweep after a paste into a password field: drop what was pasted
     * from clipboard history and wipe it off the system clipboard.
     *
     * A password pasted out of a manager is the most sensitive thing the
     * clipboard ever holds, and every app on the device can read the primary
     * clip — leaving it there until the expiry timer runs out is the wrong
     * default. Opt out with [ClipboardSettings.clearAfterPasswordPaste].
     *
     * [item] is the history entry the user tapped, or null for a paste that
     * went through the editor's own paste action (Ctrl+V, hold-V, the
     * text-editing panel) — there the pasted content *is* the primary clip, so
     * the matching history row is looked up from it.
     */
    private fun purgeAfterPasswordPaste(
        item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem? = null,
    ) {
        val state = _uiState.value
        if (!state.secureField || !state.settings.clipboard.clearAfterPasswordPaste) return
        val manager = runCatching {
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }.getOrNull() ?: return
        val current = runCatching { manager.primaryClip?.getItemAt(0) }.getOrNull()
        val currentText = current?.coerceToText(this)?.toString()?.trim()
        val currentUri = current?.uri?.toString()

        // Which history row just left the clipboard, and whether the primary
        // clip still holds it — a tap on an older entry must not wipe whatever
        // the user copied since.
        val pasted: com.wasimaster.wmkeyboard.core.clipboard.ClipItem?
        val clearPrimary: Boolean
        if (item != null) {
            pasted = item
            clearPrimary = when {
                item.kind.isTextual -> currentText == item.text
                item.uriString != null -> currentUri == item.uriString
                // An image clip's bytes live in our own store; a URI still on
                // the clipboard at this point is the copy it came from.
                else -> currentUri != null
            }
        } else {
            pasted = clipboardStore.items().firstOrNull {
                (it.kind.isTextual && currentText != null && it.text == currentText) ||
                    (it.uriString != null && it.uriString == currentUri)
            }
            clearPrimary = currentText != null || currentUri != null
        }

        if (pasted != null) {
            clipboardStore.remove(pasted.id)
            clipboardStore.save()
            _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
        }
        if (!clearPrimary) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }
        clearClipboardSuggestion()
    }

    /**
     * Shows [item] as the recently-copied paste chip on the suggestion strip and
     * arms its auto-hide timer, replacing any chip already up.
     */
    private fun showClipboardSuggestion(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        clipboardSuggestionJob?.cancel()
        _uiState.update { it.copy(clipboardSuggestion = item) }
        // 0 is "until pasted or dismissed": no timer at all rather than a very
        // long one, so the chip cannot outlive the process quietly.
        val ttlSeconds = _uiState.value.settings.clipboard.pasteChipSeconds
        clipboardSuggestionJob = if (ttlSeconds <= 0) null else serviceScope.launch {
            delay(ttlSeconds * 1000L)
            _uiState.update { it.copy(clipboardSuggestion = null) }
        }
    }

    /**
     * Offers a *just-copied one-time code* as the paste chip.
     *
     * This is the single hole in the rule that a sensitive clip never becomes a
     * strip chip — see [CopiedCodeChip] for why the rule bends here and nowhere
     * else. What keeps it a code chip rather than a way for a copied password
     * to appear over the keys is the *shape* gate: only a bare code qualifies,
     * never the generated-secret shape a password manager puts on the
     * clipboard. The freshness window keeps it to the code the user is
     * actually holding, and [CopiedCodeChip.CODE_FIELDS] narrows it further to
     * fields that ask for digits for anyone who wants that.
     *
     * Called on every field entry, and from the copy listener for a code copied
     * while the field already has the focus.
     */
    private fun maybeShowCopiedCodeSuggestion() {
        val state = _uiState.value
        val settings = state.settings
        if (!settings.clipboard.suggestRecent) return
        if (settings.clipboard.copiedCodeChip == CopiedCodeChip.OFF) return
        // Typing a code moves the focus from box to box, and every one of those
        // is a field entry that lands back here. Without these two the chip the
        // user just pressed climbs back onto the strip between digits, and
        // again over the finished code.
        if (codeEntryJob?.isActive == true) return
        if (settings.incognito || state.fieldIncognito) return
        if (!isClipboardAccessible()) return
        val clip = clipboardStore.items()
            .filter { it.kind.isTextual }
            .maxByOrNull { it.timestamp } ?: return
        if (clip.id == pastedCodeClipId) return
        if (!ClipSensitivity.isBareCode(clip.text.trim())) return
        val allowed = offersCopiedCode(
            mode = settings.clipboard.copiedCodeChip,
            fieldKind = state.fieldKind,
            clipTimestamp = clip.timestamp,
            showingTimestamp = state.clipboardSuggestion?.timestamp,
            now = System.currentTimeMillis(),
            maxAgeMs = COPIED_CODE_MAX_AGE_MS,
        )
        if (!allowed) return
        showClipboardSuggestion(clip)
    }

    /** Drops the recently-copied strip chip (pasted, dismissed, or feature off). */
    private fun clearClipboardSuggestion() {
        clipboardSuggestionJob?.cancel()
        clipboardSuggestionJob = null
        if (_uiState.value.clipboardSuggestion != null) {
            _uiState.update { it.copy(clipboardSuggestion = null) }
        }
    }

    /** The user swiped away the recently-copied strip chip. */
    fun onClipboardSuggestionDismiss() {
        // A dismissed code chip must stay dismissed: the next field entry would
        // otherwise put the same clip straight back on the strip.
        _uiState.value.clipboardSuggestion?.let { pastedCodeClipId = it.id }
        clearClipboardSuggestion()
    }

    // ---- one-time code chip ----

    /**
     * Raises or hides the one-time-code chip for the current field: called
     * when a code lands on the bus, on every field start, and stays cheap
     * because both callers are hot paths.
     *
     * A code that fails a *field* gate (number-fields-only, incognito) is
     * hidden but stays on the bus — the user is often one focus change away
     * from the box it belongs in. Only expiry and consumption take it off.
     */
    private fun maybeShowOtpSuggestion() {
        val otp = NotificationOtpBus.latest.value
        val state = _uiState.value
        val settings = state.settings
        if (otp == null || !settings.otp.enabled) {
            clearOtpSuggestion()
            return
        }
        val remainingMs = settings.otp.expiryMinutes * 60_000L -
            (System.currentTimeMillis() - otp.postedAt)
        if (remainingMs <= 0) {
            NotificationOtpBus.clear()
            clearOtpSuggestion()
            return
        }
        val hidden =
            (settings.otp.numberFieldsOnly && state.fieldKind != FieldKind.NUMBER) ||
                settings.incognito || state.fieldIncognito
        if (hidden) {
            clearOtpSuggestion()
            return
        }
        otpSuggestionJob?.cancel()
        otpSuggestionJob = serviceScope.launch {
            delay(remainingMs)
            NotificationOtpBus.clear()
            clearOtpSuggestion()
        }
        if (state.otpSuggestion != otp) {
            _uiState.update { it.copy(otpSuggestion = otp) }
        }
    }

    /** Drops the chip but not the bus — the code may fit the next field. */
    private fun clearOtpSuggestion() {
        otpSuggestionJob?.cancel()
        otpSuggestionJob = null
        if (_uiState.value.otpSuggestion != null) {
            _uiState.update { it.copy(otpSuggestion = null) }
        }
    }

    /** The code chip was tapped: type the code where the cursor is. */
    fun onOtpSuggestionTapped(otp: NotificationOtp) {
        vibrate()
        commitCodeToField(otp.code)
        if (_uiState.value.settings.otp.dismissNotification) {
            otp.notificationKey?.let(MediaNotificationListener::dismissNotification)
        }
        NotificationOtpBus.clear()
        clearOtpSuggestion()
    }

    /** The ✕ on the code chip: this code is done being offered anywhere. */
    fun onOtpSuggestionDismiss() {
        NotificationOtpBus.clear()
        clearOtpSuggestion()
    }

    /**
     * Attaches a copied file via commitContent when the editor accepts its
     * MIME type. Unlike image clips we don't own these bytes, so the URI grant
     * may already be gone; either way the fallback puts the file back on the
     * system clipboard so a long-press paste still works.
     */
    private fun commitFileClip(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        val uri = item.uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        val label = item.fileName.orEmpty()
        val ic = currentInputConnection
        val editorInfo = currentInputEditorInfo
        val supported = editorInfo != null &&
            EditorInfoCompat.getContentMimeTypes(editorInfo)
                .any { ClipDescription.compareMimeTypes(item.mimeType, it) }

        if (ic != null && editorInfo != null && supported) {
            val committed = runCatching {
                InputConnectionCompat.commitContent(
                    ic,
                    editorInfo,
                    InputContentInfoCompat(
                        uri,
                        ClipDescription(label, arrayOf(item.mimeType)),
                        null,
                    ),
                    0,
                    null,
                )
            }.getOrDefault(false)
            if (committed) return
        }
        DebugLog.w(
            "clipboard",
            "field would not take ${item.mimeType} via commitContent; fell back to the system clipboard",
        )
        copyUriToSystemClipboard(uri, label)
        Toast.makeText(
            this,
            getString(R.string.ime_service_file_not_accepted_toast),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun copyUriToSystemClipboard(uri: Uri, label: String) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newUri(contentResolver, label, uri))
        }
    }

    /**
     * Inserts an image clip with the commitContent API. Editors advertise the
     * MIME types they accept in EditorInfo; when the current one doesn't take
     * images, put the image back on the system clipboard so a long-press
     * paste still works.
     */
    private fun commitImageClip(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        val file = item.imagePath?.let(::File)?.takeIf { it.exists() } ?: run {
            clipboardStore.remove(item.id)
            clipboardStore.save()
            _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
            return
        }
        commitImageFile(file, item.mimeType)
    }

    /**
     * Commits any image file (clipboard clip, camera capture, downloaded
     * GIF/sticker/search result) via commitContent.
     *
     * The target field advertises what it takes in EditorInfo, so rather
     * than guessing per app we offer [MediaMime.candidates] in preference
     * order and send the first one it accepts — WhatsApp gets a real
     * sticker, everything else degrades to a plain image on its own. When
     * nothing matches we try a PNG re-encode (WhatsApp accepts image/png
     * but not image/webp, so a WebP would otherwise never arrive), and
     * only then fall back to the system clipboard.
     */
    private fun commitImageFile(
        file: File,
        mimeType: String,
        sendMode: MediaSendMode = MediaSendMode.IMAGE,
    ) {
        val editorInfo = currentInputEditorInfo
        val accepted = editorInfo
            ?.let { EditorInfoCompat.getContentMimeTypes(it).toList() }
            .orEmpty()

        val chosen = MediaMime.candidates(mimeType, sendMode).firstOrNull { candidate ->
            accepted.any { ClipDescription.compareMimeTypes(candidate, it) }
        }
        // What the field said it takes, and what we picked out of it. The one
        // thing a bug report about "the app did something odd with my image"
        // needs, and impossible to work out afterwards from the outside.
        DebugLog.d(
            "ime",
            "commit ${file.name} as ${chosen ?: "(no match)"} " +
                "mode=$sendMode field=${accepted.joinToString()}",
        )
        if (chosen != null && tryCommit(file, chosen)) return

        // Nothing matched. A WebP the field won't take can usually go
        // through as PNG; animated WebP would lose its animation that way,
        // so leave those for the clipboard instead of silently flattening.
        if (chosen == null && mimeType == MediaMime.WEBP &&
            accepted.any { ClipDescription.compareMimeTypes(MediaMime.PNG, it) }
        ) {
            val png = transcodeToPng(file)
            if (png != null && tryCommit(png, MediaMime.PNG)) return
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(this, clipboardFileProviderAuthority, file)
        }.getOrNull() ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newUri(contentResolver, "image", contentUri))
        Toast.makeText(
            this,
            getString(R.string.ime_service_image_not_accepted_toast),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** One commitContent attempt with a settled MIME type. */
    private fun tryCommit(file: File, mimeType: String): Boolean {
        val ic = currentInputConnection ?: return false
        val editorInfo = currentInputEditorInfo ?: return false
        val contentUri = runCatching {
            FileProvider.getUriForFile(this, clipboardFileProviderAuthority, file)
        }.getOrNull() ?: return false

        return runCatching {
            InputConnectionCompat.commitContent(
                ic,
                editorInfo,
                InputContentInfoCompat(
                    contentUri,
                    ClipDescription("image", arrayOf(mimeType)),
                    null,
                ),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        }.getOrDefault(false)
    }

    /**
     * Re-encodes a still image as PNG in the media cache. Returns null for
     * animated sources (their animation would be lost) and anything that
     * won't decode.
     */
    private fun transcodeToPng(file: File): File? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(file)
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) return null
        }
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val dir = File(cacheDir, "media").apply { mkdirs() }
        val target = File(dir, "${file.nameWithoutExtension}_png.png")
        target.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        target
    }.getOrNull()

    fun onClipboardPin(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        clipboardStore.setPinned(item.id, !item.pinned)
        clipboardStore.save()
        _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
    }

    fun onClipboardDelete(item: com.wasimaster.wmkeyboard.core.clipboard.ClipItem) {
        clipboardStore.remove(item.id)
        clipboardStore.save()
        _uiState.update { it.copy(clipboardItems = clipboardStore.items()) }
    }

    fun onOneHandedChange(mode: OneHandedMode) {
        vibrate()
        serviceScope.launch { settingsRepository.setOneHandedMode(mode) }
    }

    fun onOneHandedSideChange(landscape: Boolean, side: OneHandedSide) {
        serviceScope.launch { settingsRepository.setOneHandedSide(landscape, side) }
    }

    fun onToggleSplit() {
        vibrate()
        val current = _uiState.value.settings.splitKeyboard
        serviceScope.launch { settingsRepository.setSplitKeyboard(!current) }
    }

    /**
     * The mode whose tool arrangement a drag should be written into, or null
     * to write the global one. A mode that prescribes its own tools wins over
     * the global lists while it is active, so storing the drag globally would
     * be silently overwritten — the tool would spring straight back.
     */
    private fun toolOrderOwner(): KeyboardMode? {
        val settings = _uiState.value.settings
        if (!settings.modeToolOrderEdits) return null
        return settings.keyboardModes
            .firstOrNull { it.id == _uiState.value.activeModeId }
            ?.takeIf { it.ownsToolOrder }
    }

    /**
     * One-off heads-up the first time a drag is stored against a mode: the
     * same keyboard will look different in an app that resolves to another
     * mode, and without this that reads as the change having been lost.
     */
    private fun noteModeToolOrder(mode: KeyboardMode) {
        if (_uiState.value.settings.modeToolOrderHintSeen) return
        Toast.makeText(
            this,
            getString(R.string.ime_service_mode_tool_order_toast, mode.name),
            Toast.LENGTH_LONG,
        ).show()
        serviceScope.launch { settingsRepository.setModeToolOrderHintSeen(true) }
    }

    fun onToolbarToolsChange(tools: List<ToolbarTool>) {
        vibrate()
        val mode = toolOrderOwner()
        serviceScope.launch {
            if (mode != null) settingsRepository.setModeToolbarTools(mode.id, tools)
            else settingsRepository.setToolbarTools(tools)
        }
        if (mode != null) noteModeToolOrder(mode)
    }

    fun onToolboxOrderChange(order: List<ToolbarTool>) {
        vibrate()
        val mode = toolOrderOwner()
        serviceScope.launch {
            if (mode != null) settingsRepository.setModeToolboxOrder(mode.id, order)
            else settingsRepository.setToolboxOrder(order)
        }
        if (mode != null) noteModeToolOrder(mode)
    }

    /**
     * On Android 13+ the IME's back handling goes through the
     * OnBackInvokedDispatcher, never [onKeyDown] — register a callback while
     * a panel is open so back closes the panel; unregister when none is,
     * letting the system's default callback hide the keyboard as usual.
     */
    private var panelBackCallback: android.window.OnBackInvokedCallback? = null

    private fun updatePanelBackCallback(panelOpen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = window?.window?.onBackInvokedDispatcher ?: return
        if (panelOpen && panelBackCallback == null) {
            val callback = android.window.OnBackInvokedCallback { dismissTopLayer() }
            dispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            panelBackCallback = callback
        } else if (!panelOpen && panelBackCallback != null) {
            panelBackCallback?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
            panelBackCallback = null
        }
    }

    /**
     * Closes the topmost thing the keyboard is showing, innermost first, and
     * reports whether there was anything to close.
     *
     * The one definition of "go back" for all three routes into it: the pre-T
     * [onKeyUp] path, the Android 13+ back callback, and Escape on a physical
     * keyboard. It used to be written out twice, and the two copies had already
     * drifted on which layer they checked first.
     */
    private fun dismissTopLayer(): Boolean {
        val state = _uiState.value
        return when {
            // The picker is armed over everything else and costs nothing to drop.
            state.toolPicker != null -> {
                disarmToolPicker()
                true
            }
            // The GIF/sticker action sheet is a layer above the panel, so back
            // closes it first rather than the panel underneath it.
            state.mediaAction != null -> {
                onMediaActionDismiss()
                true
            }
            state.voice.strip -> {
                closeVoiceStrip()
                true
            }
            // Inner layers close before their panel, like the media sheet: a
            // half-typed AI instruction backs out to the action list, and a
            // focused plugin box gives the keys back, both leaving the panel up.
            state.aiCustomInputActive -> {
                dismissAiCustomInput()
                true
            }
            state.pluginTypingActive -> {
                onPluginInputFocus(null)
                true
            }
            // Same shape one level down, twice: back leaves a snippet's own
            // list of expansions, then the folder that snippet sits in, then
            // the panel both are drawn in.
            state.panel == PanelMode.SNIPPETS && state.snippetPicker != null -> {
                onSnippetPickerBack()
                true
            }
            state.panel == PanelMode.SNIPPETS && state.snippetFolderOpen != null -> {
                onSnippetFolderOpen(null)
                true
            }
            state.panel != PanelMode.NONE -> {
                onPanelChange(state.panel)
                true
            }
            else -> false
        }
    }

    /** Backs the AI panel's typed-instruction mode out to the action list. */
    private fun dismissAiCustomInput() {
        _uiState.update {
            if (it.ai is AiUi.CustomInput) it.copy(ai = AiUi.Idle) else it
        }
    }

    /**
     * Back with a tool panel (emoji, clipboard, snippets, toolbox) open
     * returns to the plain keyboard instead of hiding the IME (pre-T path).
     * Consume the DOWN too so the app underneath never sees half an event
     * stream.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown &&
            (_uiState.value.panel != PanelMode.NONE || _uiState.value.voice.strip)
        ) {
            return true
        }
        // Before the volume keys, so a leader remapped onto one still wins, and
        // before [handleHardwareKeyDown], whose composing gate and input-connection
        // check must not apply to opening a tool.
        if (handleHardwareNav(event)) return true
        if (volumeCursorDelta(keyCode) != 0) {
            // Auto-repeat rides along for free: holding the key repeats DOWN.
            onCursorMove(volumeCursorDelta(keyCode))
            return true
        }
        if (handleHardwareKeyDown(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the UP of any DOWN this IME consumed, so the focused app
        // never sees half a physical keypress. Checked before the BACK/volume
        // handling, which never registers its keys here.
        if (consumedHardwareKeys.remove(keyCode)) return true
        // Releasing Ctrl lands a language browse on the highlighted layout.
        // Never consumed: the app saw the bare Ctrl DOWN, and eating half a
        // modifier press breaks its meta-state tracking.
        if (keyCode in TapModifier.CTRL.keyCodes &&
            _uiState.value.languageSwitch?.browsing == true
        ) {
            commitLanguageBrowse()
        }
        // A double-tap leader completes on the *release* of its second tap, so
        // that the letter after it arrives with no modifier held. Never consumed:
        // a lone Ctrl means nothing on its own, and eating it would break the
        // chord it might still have been starting.
        if (_uiState.value.settings.hardwareKeyboard.shortcutsEnabled &&
            feedLeaderDetector(event)
        ) {
            armToolPicker()
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown &&
            (_uiState.value.panel != PanelMode.NONE || _uiState.value.voice.strip)
        ) {
            dismissTopLayer()
            return true
        }
        // Swallow the UP too, so the system never sees half a volume event.
        if (volumeCursorDelta(keyCode) != 0) return true
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Physical keycodes whose DOWN this IME consumed, so the matching UP is
     * swallowed too. A set rather than a flag because auto-repeat and modifier
     * chords can leave several keys down at once.
     */
    private val consumedHardwareKeys = HashSet<Int>()

    /**
     * Records that this IME ate [keyCode]'s DOWN, so [onKeyUp] swallows the
     * matching UP. Every consuming branch returns through here — the bookkeeping
     * is too easy to forget, and forgetting it hands the app half a keypress.
     */
    private fun consumeHardwareKey(keyCode: Int): Boolean {
        consumedHardwareKeys.add(keyCode)
        return true
    }

    // ---- physical keyboard: opening and driving the tools ----

    /**
     * Recognises the double-tap leader. Rebuilt whenever the setting changes,
     * since the modifier it watches is part of its identity.
     */
    private var leaderDetector: DoubleTapDetector? = null
    private var leaderDetectorFor: String? = null

    /** The input view was forced visible for a shortcut and owes a restore. */
    private var forcedInputView = false

    /** Nothing physical is half-pressed and nothing is armed. */
    private fun resetHardwareKeyState() {
        consumedHardwareKeys.clear()
        leaderDetector?.reset()
        if (_uiState.value.toolPicker != null) disarmToolPicker()
        // A browse whose Ctrl-up went to another window can never commit.
        if (_uiState.value.languageSwitch != null) cancelLanguageBrowse()
        releaseForcedInputView()
    }

    private fun hardwareShortcutSettings(): HardwareKeyboardSettings =
        _uiState.value.settings.hardwareKeyboard

    private fun leaderTrigger(): LeaderTrigger {
        val stored = hardwareShortcutSettings().leader
        return parseLeader(stored) ?: DefaultLeader
    }

    /**
     * Feeds a modifier press to the double-tap detector. Called from both
     * [onKeyDown] and [onKeyUp] because a tap is only a tap once released, and
     * called for *every* key because anything else pressed in between is what
     * tells a double tap apart from someone using Ctrl+C.
     */
    private fun feedLeaderDetector(event: KeyEvent): Boolean {
        val trigger = leaderTrigger()
        if (trigger !is LeaderTrigger.DoubleTap) {
            leaderDetector = null
            leaderDetectorFor = null
            return false
        }
        val key = hardwareShortcutSettings().leader
        val detector = leaderDetector?.takeIf { leaderDetectorFor == key }
            ?: DoubleTapDetector(trigger.modifier).also {
                leaderDetector = it
                leaderDetectorFor = key
            }
        return detector.onEvent(
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeat = event.repeatCount,
            eventTime = event.eventTime,
        )
    }

    /**
     * Everything a physical keyboard can do that isn't typing: the leader, the
     * armed picker, Escape, the focus ring, and the suggestion hotkeys.
     *
     * A sibling of [handleHardwareKeyDown] rather than a branch inside it,
     * because none of its two gates should apply here — this must work with no
     * input connection, and with [KeyboardSettings.hardwareKeyboardInput] off
     * (that setting is about *typed characters*, and its own documentation
     * promises shortcuts are none of its business).
     */
    private fun handleHardwareNav(event: KeyEvent): Boolean {
        val config = hardwareShortcutSettings()
        // Every key goes to the detector, not just the modifier it watches:
        // anything else pressed in between is exactly what tells a double tap
        // apart from someone holding Ctrl to use a shortcut. Arming itself
        // happens on the release, in [onKeyUp].
        if (config.shortcutsEnabled) feedLeaderDetector(event)
        val keyCode = event.keyCode

        // Bare modifiers are never consumed and never cancel: reaching '?' means
        // holding Shift, and a chord the app owns starts with a lone modifier.
        if (KeyEvent.isModifierKey(keyCode)) return false

        expireToolPicker()
        // Before the leader block: Ctrl is held for the whole of a browse, so a
        // chord-style leader could otherwise fire in the middle of one.
        handleLanguageSwitchKey(event)?.let { return it }
        if (config.shortcutsEnabled) {
            if (leaderTrigger().let { it is LeaderTrigger.Chord && it.chord.matches(keyCode, event.metaState) }) {
                if (_uiState.value.toolPicker != null) disarmToolPicker() else armToolPicker()
                return consumeHardwareKey(keyCode)
            }
            if (_uiState.value.toolPicker != null) return handleArmedKey(event)
        }

        if (keyCode == KeyEvent.KEYCODE_ESCAPE && config.escClosesPanel) {
            // Only ours when we actually have something open. A bare Escape stops
            // a page loading, leaves insert mode, cancels a dialog.
            return if (dismissTopLayer()) consumeHardwareKey(keyCode) else false
        }

        // Ctrl+1 … Ctrl+9 open the toolbar buttons with no leader first. Exact
        // modifiers: Ctrl+Alt is AltGr and produces characters, and Ctrl+Shift+1
        // belongs to whatever the app does with it.
        if (config.shortcutsEnabled && config.toolbarDigitChord &&
            event.metaState and KeyEvent.META_CTRL_ON != 0 &&
            event.metaState and
            (KeyEvent.META_ALT_ON or KeyEvent.META_META_ON or KeyEvent.META_SHIFT_ON) == 0
        ) {
            // Resolved off the bar rather than out of the plan's strokes: in the
            // leader-digit suggestion mode the bare digits belong to the strip,
            // and the chord still has to open the tool it is drawn under.
            // Zero is the tenth button, not the noughth, so the slot comes from
            // the digit's place in the sequence rather than from keycode maths.
            val slot = if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                ToolbarHintDigits.indexOf('0' + (keyCode - KeyEvent.KEYCODE_0))
            } else {
                -1
            }
            val action = toolbarHintButtons(visibleToolbarTools(_uiState.value)).getOrNull(slot)
            if (action != null && runHintAction(action)) return consumeHardwareKey(keyCode)
        }

        if (config.macShortcuts) {
            val binding = macBindingFor(keyCode, event.metaState)
            if (binding != null && runMacBinding(binding)) return consumeHardwareKey(keyCode)
        }

        if (config.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT &&
            event.metaState and KeyEvent.META_ALT_ON != 0 &&
            event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) == 0
        ) {
            val digit = keyCode - KeyEvent.KEYCODE_1
            if (digit in 0..8 && pickSuggestion(digit)) return consumeHardwareKey(keyCode)
        }

        return handlePanelNavKey(event)
    }

    /** Arrow, Enter and Tab inside an open panel: move, activate, change region. */
    private fun handlePanelNavKey(event: KeyEvent): Boolean {
        val state = _uiState.value
        if (!state.settings.hardwareKeyboard.panelNavigation) return false
        if (state.panel == PanelMode.NONE || !panelFocus.matches(state.panel)) return false
        val keyCode = event.keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> movePanelFocus(-1, 0) && consumeHardwareKey(keyCode)
            KeyEvent.KEYCODE_DPAD_RIGHT -> movePanelFocus(1, 0) && consumeHardwareKey(keyCode)
            KeyEvent.KEYCODE_DPAD_UP -> movePanelFocus(0, -1) && consumeHardwareKey(keyCode)
            KeyEvent.KEYCODE_DPAD_DOWN -> movePanelFocus(0, 1) && consumeHardwareKey(keyCode)
            KeyEvent.KEYCODE_TAB -> {
                // Tab only belongs to the keyboard once the ring is up. Before
                // that it is still field navigation, and in a terminal it is
                // completion — an IME that eats it is broken.
                if (state.panelFocus == null) false
                else tabPanelFocus(event.isShiftPressed) && consumeHardwareKey(keyCode)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // With no ring, Enter keeps its existing meaning: run the panel's
                // search, or the field's editor action.
                if (state.panelFocus == null) false
                else {
                    activatePanelFocus()
                    consumeHardwareKey(keyCode)
                }
            }
            // Like Tab: before a ring exists these are still the field's own
            // cursor and scroll keys, and an IME that eats them is broken.
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> {
                if (state.panelFocus == null) false
                else {
                    movePanelFocusEdge(toEnd = keyCode == KeyEvent.KEYCODE_MOVE_END) &&
                        consumeHardwareKey(keyCode)
                }
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (state.panelFocus == null) false
                else {
                    movePanelFocusPage(if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -1 else 1) &&
                        consumeHardwareKey(keyCode)
                }
            }
            else -> false
        }
    }

    /** Home/End inside the ring's region: first or last item. */
    private fun movePanelFocusEdge(toEnd: Boolean): Boolean {
        val current = _uiState.value.panelFocus ?: return false
        val grid = panelFocus.grid(current.region)
        if (grid.count <= 0) return false
        val target = if (toEnd) grid.count - 1 else 0
        if (target == current.index) return false
        _uiState.update { it.copy(panelFocus = current.copy(index = target)) }
        return true
    }

    /** PageUp/PageDown inside the ring's region: several rows at a time. */
    private fun movePanelFocusPage(dy: Int): Boolean {
        val current = _uiState.value.panelFocus ?: return false
        val target = panelFocus.grid(current.region).page(current.index, dy) ?: return false
        _uiState.update { it.copy(panelFocus = current.copy(index = target)) }
        return true
    }

    /**
     * The key after the leader. Anything printable is consumed whether or not it
     * is bound: the user is in picker mode, so the letter is a choice, not text.
     */
    private fun handleArmedKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                disarmToolPicker()
                return consumeHardwareKey(keyCode)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Swallowed rather than passed on: an armed Enter must not submit
                // the form the user was typing into.
                disarmToolPicker()
                return consumeHardwareKey(keyCode)
            }
        }
        // A chord the app owns wins outright, arming or not.
        if (event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) {
            disarmToolPicker()
            return false
        }
        val letter = pickerLetter(event.getUnicodeChar(0), keyCode)
        if (letter == null) {
            // An arrow or function key: hand it back, and deliberately do not
            // register it as consumed — never wedge the user's keyboard.
            disarmToolPicker()
            return false
        }
        // Shift is a tier of its own here, and it has to be read off the event:
        // [pickerLetter] decodes with no meta state on purpose, so that a binding
        // can never depend on which shift level a layout puts a character on.
        val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
        // The two reserved keys come first, ahead of the plan: '?' is Shift+/ on
        // most layouts, so a shift-tier lookup would swallow the request for help.
        when {
            letter == CheatSheetLetter -> {
                // Stays armed: the legend is there to be read and then acted on.
                _uiState.update { it.copy(toolPicker = it.toolPicker?.copy(cheatSheet = true)) }
                vibrate()
                return consumeHardwareKey(keyCode)
            }
            letter == ToolboxLetter && !shift -> {
                disarmToolPicker()
                openToolPanelByKey(PanelMode.TOOLBOX)
                return consumeHardwareKey(keyCode)
            }
        }
        val action = keyboardHintPlan(_uiState.value).action(letter, shift)
        disarmToolPicker()
        if (action == null || !runHintAction(action)) vibrate()
        return consumeHardwareKey(keyCode)
    }

    /**
     * Fires one resolved hint, whichever route found it — the leader's letter,
     * the `Ctrl`+digit chord, or a second-tier `Shift` key. False when there was
     * nothing to act on, so the caller can buzz instead of pretending.
     */
    private fun runHintAction(action: HintAction): Boolean = when (action) {
        is HintAction.OpenToolbox -> {
            openToolPanelByKey(PanelMode.TOOLBOX)
            true
        }
        is HintAction.OpenTool -> {
            openToolByKey(action.tool)
            true
        }
        is HintAction.PickSuggestion -> pickSuggestion(action.index)
        // The same handlers the tapped cells are wired to, so a key and a tap
        // cannot drift apart (see the row wiring in [KeyboardBody]).
        is HintAction.InsertSymbol -> {
            val symbol = activeSymbolSet(_uiState.value).chars.getOrNull(action.index)
            if (symbol != null) onToolTextInsert(symbol)
            symbol != null
        }
        is HintAction.InsertEmoji -> {
            val emoji = visibleEmojiBarItems(_uiState.value).getOrNull(action.index)
            if (emoji != null) onEmojiTapped(emoji)
            emoji != null
        }
    }

    /**
     * Runs a Mac chord through the handlers the soft keyboard already uses, so
     * `Cmd+C` and a long-pressed C do exactly the same thing.
     *
     * Copy and cut deliberately do *not* select the whole field first the way
     * the long-press path does: on a Mac, Cmd+C with nothing selected is a no-op,
     * and quietly copying the entire message the user was writing is the kind of
     * surprise that costs a clipboard entry.
     */
    private fun runMacBinding(binding: MacBinding): Boolean {
        if (currentInputConnection == null) return false
        val selecting = binding.selecting
        when (binding.action) {
            MacAction.COPY -> onClipboardKey(ClipboardKeyAction.COPY, selectAllIfEmpty = false)
            MacAction.CUT -> onClipboardKey(ClipboardKeyAction.CUT, selectAllIfEmpty = false)
            MacAction.PASTE -> onClipboardKey(ClipboardKeyAction.PASTE)
            MacAction.SELECT_ALL -> onClipboardKey(ClipboardKeyAction.SELECT_ALL)
            MacAction.UNDO -> onUndoRedo(redo = false)
            MacAction.REDO -> onUndoRedo(redo = true)
            MacAction.LINE_START -> macEditorKey(KeyEvent.KEYCODE_MOVE_HOME, selecting)
            MacAction.LINE_END -> macEditorKey(KeyEvent.KEYCODE_MOVE_END, selecting)
            // Ctrl+Home and Ctrl+End are the editor's document ends, which is
            // what Cmd+Up and Cmd+Down mean on a Mac.
            MacAction.DOC_START -> macEditorKey(KeyEvent.KEYCODE_MOVE_HOME, selecting, ctrl = true)
            MacAction.DOC_END -> macEditorKey(KeyEvent.KEYCODE_MOVE_END, selecting, ctrl = true)
            MacAction.WORD_LEFT -> macEditorKey(KeyEvent.KEYCODE_DPAD_LEFT, selecting, ctrl = true)
            MacAction.WORD_RIGHT -> macEditorKey(KeyEvent.KEYCODE_DPAD_RIGHT, selecting, ctrl = true)
            MacAction.DELETE_WORD -> macDelete(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true)
            MacAction.DELETE_TO_LINE_START -> macDelete(KeyEvent.KEYCODE_MOVE_HOME, ctrl = false)
        }
        return true
    }

    /**
     * A cursor move on the Mac path. Commits the composing buffer first, exactly
     * as [onTextEdit] does — a caret jump with a live buffer strands it.
     */
    private fun macEditorKey(keyCode: Int, selecting: Boolean, ctrl: Boolean = false) {
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        sendEditorKey(keyCode, selecting, ctrl = ctrl)
    }

    /**
     * Select-then-delete, which is how the framework's own editors implement
     * delete-by-word: extend the selection with the same shifted move the caret
     * would have made, then send one backspace over it. Deleting a counted span
     * from `getTextBeforeCursor` was the alternative and gets grapheme clusters,
     * a live selection and an out-of-date cursor cache all subtly wrong.
     */
    private fun macDelete(keyCode: Int, ctrl: Boolean) {
        val ic = currentInputConnection ?: return
        commitComposing(ic, autocorrect = false)
        lastGestureWord = null
        // Nothing to extend over: an existing selection is what backspace should
        // eat, so leave it alone and let the plain delete run.
        if (ic.getSelectedText(0).isNullOrEmpty()) {
            sendEditorKey(keyCode, shift = true, ctrl = ctrl)
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun armToolPicker() {
        vibrate()
        val armedAt = SystemClock.uptimeMillis()
        _uiState.update { it.copy(toolPicker = ToolPickerState(armedAt = armedAt)) }
        ensureInputViewShown()
        // Cosmetic only: hides the hint on time instead of leaving it up until
        // the next keystroke. The authoritative check is [expireToolPicker], so a
        // missed cancellation here costs nothing.
        serviceScope.launch {
            delay(hardwareShortcutSettings().pickerTimeoutMs.toLong())
            val picker = _uiState.value.toolPicker
            if (picker != null && !picker.cheatSheet && picker.armedAt == armedAt) disarmToolPicker()
        }
    }

    /**
     * The close button shared by the hardware overlays (the shortcut legend
     * and the language list — never up at once). One callback into the UI
     * because [ServiceKeyboardContent] sits against the JVM's 64K method-size
     * ceiling, where each extra [KeyboardScreen] parameter costs bytecode.
     */
    private fun dismissHardwareOverlay() {
        if (_uiState.value.languageSwitch != null) cancelLanguageBrowse() else disarmToolPicker()
    }

    private fun disarmToolPicker() {
        _uiState.update { if (it.toolPicker == null) it else it.copy(toolPicker = null) }
        // Nothing was opened, so the view forced up for the hint is owed back.
        if (_uiState.value.panel == PanelMode.NONE) releaseForcedInputView()
    }

    /**
     * Drops an armed picker the user has walked away from. Checked on each key
     * rather than driven by a timer: a posted job would need cancelling on every
     * one of the six ways out of picker mode, and one missed cancel leaves the
     * next keystroke opening a tool.
     */
    private fun expireToolPicker() {
        val picker = _uiState.value.toolPicker ?: return
        // Someone reading the legend is not idle.
        if (picker.cheatSheet) return
        val timeout = hardwareShortcutSettings().pickerTimeoutMs.toLong()
        if (SystemClock.uptimeMillis() - picker.armedAt > timeout) disarmToolPicker()
    }

    // ---- physical keyboard: switching the input language ----

    /**
     * The hardware language switch. Ctrl+Space starts an Alt-Tab-style browse:
     * further taps step through the enabled layouts while Ctrl stays held, and
     * the release commits — so [onLayoutSelected], which writes DataStore and
     * mirrors the subtype to the OS, runs exactly once however far the user
     * steps. A quick tap-and-release is just a one-step browse. The dedicated
     * language key has no modifier to anchor a browse on, so it commits at
     * once and only flashes the overlay.
     *
     * Returns true/false when the key was a language key (consumed or not),
     * null to keep dispatching. The double-tap leader needs no guarding here:
     * the Space between the two Ctrl events resets [DoubleTapDetector], which
     * insists on two taps and *nothing else*.
     */
    private fun handleLanguageSwitchKey(event: KeyEvent): Boolean? {
        val keyCode = event.keyCode
        val session = _uiState.value.languageSwitch

        if (session?.browsing == true) {
            // A Ctrl-up this service never saw (focus steal) leaves a browse
            // with no way to commit; a key arriving without Ctrl is its trace.
            if (event.metaState and KeyEvent.META_CTRL_ON == 0) {
                cancelLanguageBrowse()
                return null
            }
            languageSwitchDelta(keyCode, event.metaState)?.let { delta ->
                stepLanguageBrowse(delta)
                return consumeHardwareKey(keyCode)
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                cancelLanguageBrowse()
                return consumeHardwareKey(keyCode)
            }
            // Any other chord (Ctrl+C…) abandons the browse and stays the app's.
            cancelLanguageBrowse()
            return null
        }

        // The dedicated key is claimed bare and unconditionally — no app wants
        // it. With fewer than two layouts it falls through, so the framework's
        // own next-IME rotation still means something on a one-language setup.
        if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH &&
            event.metaState and
            (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0
        ) {
            return if (cycleLanguageWithHud()) consumeHardwareKey(keyCode) else null
        }

        if (hardwareShortcutSettings().languageSwitchChord) {
            languageSwitchDelta(keyCode, event.metaState)?.let { delta ->
                // One layout enabled: the chord stays the app's (IDE autocomplete).
                return if (startLanguageBrowse(delta)) consumeHardwareKey(keyCode) else null
            }
        }
        return null
    }

    private fun startLanguageBrowse(delta: Int): Boolean {
        val state = _uiState.value
        val ids = state.settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
        val candidate = languageCycleStart(ids, state.layoutId, delta) ?: return false
        disarmToolPicker()
        vibrate()
        _uiState.update {
            it.copy(
                languageSwitch = LanguageSwitchState(
                    layoutIds = ids,
                    candidate = candidate,
                    browsing = true,
                    shownAt = SystemClock.uptimeMillis(),
                ),
            )
        }
        ensureInputViewShown()
        return true
    }

    private fun stepLanguageBrowse(delta: Int) {
        vibrate()
        _uiState.update { s ->
            val session = s.languageSwitch ?: return@update s
            s.copy(
                languageSwitch = session.copy(
                    candidate = languageCycleStep(session.candidate, delta, session.layoutIds.size),
                ),
            )
        }
    }

    /** Ctrl came back up: the highlighted layout becomes the input language. */
    private fun commitLanguageBrowse() {
        val session = _uiState.value.languageSwitch ?: return
        val target = session.layoutIds.getOrNull(session.candidate)
        _uiState.update { it.copy(languageSwitch = null) }
        if (target != null && target != _uiState.value.layoutId) onLayoutSelected(target)
        if (_uiState.value.panel == PanelMode.NONE) releaseForcedInputView()
    }

    private fun cancelLanguageBrowse() {
        _uiState.update { if (it.languageSwitch == null) it else it.copy(languageSwitch = null) }
        if (_uiState.value.panel == PanelMode.NONE) releaseForcedInputView()
    }

    /**
     * The dedicated key's immediate switch: commit now, keep the overlay up
     * briefly so the user sees where they landed. [armToolPicker]'s dismissal
     * pattern — the coroutine only clears the session it started.
     */
    private fun cycleLanguageWithHud(): Boolean {
        val state = _uiState.value
        val ids = state.settings.enabledLayoutIds.ifEmpty { listOf(BuiltInLayouts.DEFAULT_ID) }
        val candidate = languageCycleStart(ids, state.layoutId, 1) ?: return false
        vibrate()
        onLayoutSelected(ids[candidate])
        val shownAt = SystemClock.uptimeMillis()
        _uiState.update {
            it.copy(
                languageSwitch = LanguageSwitchState(
                    layoutIds = ids,
                    candidate = candidate,
                    browsing = false,
                    shownAt = shownAt,
                ),
            )
        }
        ensureInputViewShown()
        serviceScope.launch {
            delay(LANGUAGE_HUD_FLASH_MS)
            val session = _uiState.value.languageSwitch
            if (session != null && !session.browsing && session.shownAt == shownAt) {
                cancelLanguageBrowse()
            }
        }
        return true
    }

    /** Opens a tool from the keyboard, then puts the ring on its first item. */
    private fun openToolByKey(tool: ToolbarTool) {
        // Gated on the toolbar's set, unlike a key bound to the tool: a hardware
        // shortcut is a binding to a button, and a button that is not there is
        // not one to press. See [runToolFromKey].
        if (tool !in _uiState.value.settings.enabledTools) return
        runToolFromKey(tool)
    }

    private fun openToolPanelByKey(panel: PanelMode) {
        onPanelChange(panel)
        ensureInputViewShown()
        seedPanelFocus()
    }

    /**
     * Puts the ring on the first item of a panel opened by keyboard. Deferred a
     * frame: the panel has not composed yet, so nothing has published how many
     * items it has.
     */
    private fun seedPanelFocus() {
        if (!hardwareShortcutSettings().panelNavigation) return
        val panel = _uiState.value.panel
        if (panel == PanelMode.NONE || panelFocusRegions(panel).isEmpty()) return
        serviceScope.launch {
            // Two frames at 60Hz: enough for the panel to publish its geometry.
            delay(32)
            if (_uiState.value.panel != panel || !panelFocus.matches(panel)) return@launch
            val region = preferredFocusRegion() ?: return@launch
            _uiState.update {
                if (it.panelFocus != null) it else it.copy(panelFocus = PanelFocus(region, 0))
            }
        }
    }

    /**
     * Where the ring should appear first: the results, if the panel has any.
     * Tab still runs search → chips → results, but landing on the search pill
     * would mean two more keys before reaching what the user came for.
     */
    private fun preferredFocusRegion(): FocusRegion? =
        FocusRegion.RESULTS.takeIf { panelFocus.grid(it).count > 0 }
            ?: panelFocus.firstUsableRegion()

    /** True when the ring moved (or first appeared), so the key was ours. */
    private fun movePanelFocus(dx: Int, dy: Int): Boolean {
        val state = _uiState.value
        val current = state.panelFocus
        if (current == null) {
            // The first arrow press summons the ring rather than moving it —
            // except in the panels that ride along with live field editing,
            // where the arrows must keep moving the caret and only a
            // keyboard-open seeds a ring.
            if (panelFocusSeedOnly(state.panel)) return false
            val region = preferredFocusRegion() ?: return false
            _uiState.update { it.copy(panelFocus = PanelFocus(region, 0)) }
            return true
        }
        val next = panelFocus.grid(current.region).step(current.index, dx, dy)
        if (next != null) {
            _uiState.update { it.copy(panelFocus = current.copy(index = next)) }
            return true
        }
        // Off the top or bottom of a region: step to the neighbouring one, so
        // Up out of the results lands on the search box.
        return if (dy != 0) moveFocusRegion(if (dy < 0) -1 else 1) else false
    }

    private fun tabPanelFocus(backwards: Boolean): Boolean =
        moveFocusRegion(if (backwards) -1 else 1)

    private fun moveFocusRegion(delta: Int): Boolean {
        val regions = panelFocus.regions.filter { panelFocus.grid(it).count > 0 }
        if (regions.isEmpty()) return false
        val current = _uiState.value.panelFocus
        val at = regions.indexOf(current?.region)
        val next = when {
            at < 0 -> 0
            else -> at + delta
        }
        val target = regions.getOrNull(next) ?: return false
        _uiState.update { it.copy(panelFocus = PanelFocus(target, 0)) }
        vibrate()
        return true
    }

    private fun activatePanelFocus() {
        val focus = _uiState.value.panelFocus ?: return
        if (!panelFocus.matches(_uiState.value.panel)) return
        val grid = panelFocus.grid(focus.region)
        if (focus.index !in 0 until grid.count) return
        vibrate()
        panelFocus.activate(focus.region, focus.index)
    }

    /**
     * Commits the suggestion in the numbered *slot*, counting from the left, or
     * reports false so the key goes back to the app — with an empty strip, Alt+1
     * belongs to whatever is switching tabs with it.
     *
     * [slot] is a position on the strip, not an engine rank. With the primary
     * candidate centred those differ, and the badges count what the eye sees.
     */
    private fun pickSuggestion(slot: Int): Boolean {
        val state = _uiState.value
        if (!hardwareIntercepts(state)) return false
        val index = suggestionDisplayOrder(state).getOrNull(slot) ?: return false
        val word = state.suggestions.getOrNull(index) ?: return false
        // Through the *tapped-candidate* handler, not the tapped-suggestion one:
        // a conversion IME (Pinyin, kana to kanji) resolves a candidate by its
        // position, because the position is what says how much of the reading
        // buffer the choice consumes. Handing it only the text commits the right
        // characters against the wrong span. For everything else this delegates
        // straight to onSuggestionTapped, which already vibrates, stops dictation
        // and handles the email and gesture cases.
        onCandidateTapped(word, index)
        return true
    }

    /**
     * Shows the keyboard for a shortcut when a physical keyboard has hidden it.
     * [updateInputViewShown] is what makes the framework re-ask
     * [onEvaluateInputViewShown]; nothing here shows the IME window itself,
     * which is already up whenever these key events arrive.
     */
    private fun ensureInputViewShown() {
        if (!hardwareShortcutSettings().autoShowUi) return
        if (isInputViewShown || forcedInputView) return
        forcedInputView = true
        updateInputViewShown()
    }

    /**
     * Gives back a view that was forced up. Mandatory rather than tidy: left set,
     * a hardware-keyboard user is stuck with a full on-screen keyboard they never
     * asked for as soon as the tool closes.
     */
    private fun releaseForcedInputView() {
        if (!forcedInputView) return
        forcedInputView = false
        updateInputViewShown()
    }

    /**
     * Routes a physical-keyboard press through the same pipeline as the
     * on-screen keys — transliteration, the composing buffer, suggestions,
     * autocorrect — so a hardware keyboard is a first-class input source rather
     * than a raw bypass of the IME.
     *
     * Returns true when the event was consumed. False hands the key back to the
     * system unchanged — cursor and function keys, shortcuts, and every key at
     * all when the field or the [KeyboardSettings.hardwareKeyboardInput] setting
     * doesn't want IME processing — after first committing any composing text so
     * it is never stranded by the cursor move or shortcut about to run.
     */
    private fun handleHardwareKeyDown(event: KeyEvent): Boolean {
        val ic = currentInputConnection ?: return false
        val keyCode = event.keyCode

        // Bare modifier presses latch in the system (Shift for a capital, Ctrl
        // to open a chord). Never consumed, and never a commit trigger — a
        // Ctrl+Shift+Arrow selection opens with a lone modifier down.
        if (KeyEvent.isModifierKey(keyCode)) return false

        val state = _uiState.value

        // A modifier-driven shortcut belongs to the app (Ctrl+C, Meta+Space).
        // AltGr arrives as Ctrl+Alt *together* and is not a shortcut — it
        // produces characters (German AltGr+Q = @), so it falls through to the
        // text path where getUnicodeChar decodes it against the held meta state.
        val ctrl = event.metaState and KeyEvent.META_CTRL_ON != 0
        val alt = event.metaState and KeyEvent.META_ALT_ON != 0
        val meta = event.metaState and KeyEvent.META_META_ON != 0
        val shortcut = meta || (ctrl && !alt)

        if (!state.settings.hardwareKeyboardInput || shortcut || !hardwareIntercepts(state)) {
            // Handing the key back: finish composing first so a moving cursor or
            // a shortcut never strands the buffer. Skipped for the bare modifier
            // case above, which returned before reaching here.
            if (composing.isNotEmpty()) commitComposing(ic, autocorrect = false)
            return false
        }

        stopVoiceForManualInput()
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                clearForHardwareTyping()
                onSpace()
                consumeHardwareKey(keyCode)
            }
            KeyEvent.KEYCODE_DEL -> {
                // Routed through onDelete so it edits the composing buffer, undoes
                // a swipe/autocorrect, and deletes whole grapheme clusters — all
                // of which a raw backspace against the field would get wrong.
                onDelete()
                consumeHardwareKey(keyCode)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // The soft Enter's own handler: commits the buffer, then runs the
                // field's editor action (or a real newline), and — the reason it
                // can't just pass through — runs an active panel/dictionary search
                // instead of dropping a newline into the app behind the panel.
                // A physical shift is held rather than latched, so it comes from
                // the event's meta state rather than from the ui state.
                onEnter(hardwareShift = event.metaState and KeyEvent.META_SHIFT_ON != 0)
                consumeHardwareKey(keyCode)
            }
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_FORWARD_DEL,
            -> {
                // The system owns these (field navigation, forward delete). Commit
                // the buffer so it lands before them.
                if (composing.isNotEmpty()) commitComposing(ic, autocorrect = false)
                false
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    // A non-printing key (arrows, F-keys, Home/End) or a physical
                    // dead key the framework will compose: hand it back, buffer
                    // committed first.
                    if (composing.isNotEmpty()) commitComposing(ic, autocorrect = false)
                    false
                } else {
                    clearForHardwareTyping()
                    // Literal: the char already carries the physical layout's
                    // shift/AltGr, so the soft shift state must not re-case it.
                    processTypedText(unicode.toChar().toString(), applyDeadKeys = false)
                    consumeHardwareKey(keyCode)
                }
            }
        }
    }

    /**
     * The IME should own physical input right now: a transliterating or
     * suggestion-composing field, or an open panel search / typing test whose
     * keys feed a query rather than the app behind. Mirrors the soft keyboard's
     * own composing gate ([onTextKey]'s `composingMode`) so the two stay in step
     * — a field that composes taps composes hardware keys the same way.
     */
    private fun hardwareIntercepts(state: KeyboardUiState): Boolean {
        val composingMode = (!state.composer.isClusterShaping || composing.isNotEmpty()) &&
            (
                state.composer.isTransliterating ||
                    (state.allowsTypingIntelligence && state.settings.suggestions)
                )
        return composingMode || state.emojiSearchActive ||
            (state.mediaSearchActive && state.panel.hasMediaSearch) ||
            state.dictionarySearchActive || state.clipboardSearchActive ||
            state.typingTestActive || state.pluginTypingActive ||
            state.aiCustomInputActive ||
            state.calcTypingActive || state.converterTypingActive
    }

    /**
     * The [onKey] preamble a hardware character or space needs: drop the
     * swipe-undo word and any armed auto-space, exactly as a soft key does.
     * Backspace is deliberately excluded — it keeps the swipe word so one press
     * can undo a whole glide. The recent-copy chip survives typing here too
     * (see [onKey]).
     *
     * [pendingWordSpace] and [pendingPunctuationSpace] are left alone on
     * purpose: both callers are the two paths allowed to spend them ([onSpace]
     * and [processTypedText]), and each reads and clears them itself, so a
     * physical keyboard hugs punctuation to the word before it exactly as the
     * soft one does.
     */
    private fun clearForHardwareTyping() {
        lastGestureWord = null
        pendingAutoSpace = false
    }

    /**
     * Cursor step a volume key should produce right now, or 0 to let the key
     * through to the system. Down is left and up is right, matching the way
     * the keys sit on the phone when it is held upright.
     *
     * The media-aware option re-checks playback on every event rather than
     * latching, so starting or stopping a song swaps the behaviour instantly.
     */
    private fun volumeCursorDelta(keyCode: Int): Int {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            else -> return 0
        }
        val settings = _uiState.value.settings
        if (!settings.volumeCursor || !isInputViewShown) return 0
        if (settings.volumeCursorMediaAware &&
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        ) {
            return 0
        }
        return delta
    }

    /**
     * Re-reads every language's word sources from disk: the user's imported
     * lists, unioned with that language's downloaded dictionary when one is on
     * disk. English and Bengali downloads are deliberately NOT unioned here —
     * they already flow through the primary/phonetic path (see
     * [openLanguageDictionary]), and repeating them in the custom slot would
     * double-weight their words.
     */
    private fun loadCustomDictionaries(): Map<String, WordSource> {
        // Direct boot: the user's imported and downloaded lists are behind
        // their credential. The bundled list still loads (see
        // [openLanguageDictionary]), so prediction works — it just knows only
        // the words that shipped with the app.
        if (!userUnlocked) return emptyMap()
        CustomDictionaries.migrateLegacyFolders(filesDir)
        return LanguageRegistry.all.associate { lang ->
            val imported = CustomDictionaries.trie(filesDir, lang.id)
            // Dropped for a language set to read imported lists alone (#28) —
            // that is the whole of the setting for every language but English
            // and Bengali, whose downloads travel the primary path and are
            // dropped by [openLanguageDictionary] instead.
            val downloaded = if (lang.id == "en" || lang.id == "bn" ||
                !shippedDictionaryEnabled(lang.id)
            ) {
                null
            } else {
                MappedTrie.open(DictionaryStore.downloadedFile(filesDir, lang.id))
            }
            lang.id to CompositeWordSource.of(listOfNotNull(downloaded, imported))
        }
    }

    /**
     * The primary dictionary for a language with a bundled list: the
     * downloaded `.wmdict` (bigger, user-chosen size) when present, else the
     * bundled binary inflated out of the APK. Memory-mapped either way.
     */
    private fun openLanguageDictionary(langId: String): MappedTrie? {
        // "Only my word lists" (#28): both the shipped vocabulary and the
        // downloaded one are dropped, leaving the language to whatever the user
        // imported. Checked before either is opened rather than after, so the
        // mapping is not paid for a file nothing will read.
        if (!shippedDictionaryEnabled(langId)) return null
        // The bundled copy is inflated into device-protected storage, so the
        // same file serves a locked boot and a normal one. A *downloaded* list
        // is the user's own and stays credential-encrypted.
        val bundled = { DictionaryStore.ensureBundled(dictionaryContext, langId)?.let { MappedTrie.open(it) } }
        if (!userUnlocked) return bundled()
        return MappedTrie.open(DictionaryStore.downloadedFile(filesDir, langId)) ?: bundled()
    }

    /**
     * Where the bundled dictionaries are inflated: always the device-protected
     * area, so the copy made on a normal run is the same copy a direct boot
     * reads. They come out of the APK, so nothing of the user's is exposed by
     * putting them there.
     */
    private val dictionaryContext: Context by lazy { DirectBoot.deviceContext(this) }

    /** Downloaded-dictionary state the word sources were last (re)built from. */
    private var loadedDictToken = Int.MIN_VALUE

    /**
     * Re-mmaps and re-wires every source that can change when a dictionary
     * download finishes or is deleted. Cheap no-op when nothing changed;
     * called off the main thread from [onStartInputView], so a download
     * completed in Settings goes live the next time a field is focused.
     */
    private suspend fun reloadDownloadedDictionaries() = withContext(Dispatchers.Default) {
        // Nothing to reload while locked: no download can have finished, and
        // the directory the token is computed over is unreadable.
        if (!userUnlocked) return@withContext
        val token = DictionaryStore.stateToken(filesDir)
        if (token == loadedDictToken || suggestionEngine == null) return@withContext
        loadedDictToken = token
        val english = openLanguageDictionary("en")
        val bengali = if (loadedBengali) openLanguageDictionary("bn") else null
        bengaliAssetEntries = bengali?.entries().orEmpty()
        customDictionaries = loadCustomDictionaries()
        suggestionEngine?.let { engine ->
            engine.dictionary = english ?: PackedTrie.EMPTY
            engine.bengaliIndex = buildBengaliIndex()
            val lang = _uiState.value.language
            engine.customDictionary = customDictionaries[lang.id] ?: PackedTrie.EMPTY
            engine.ngramPack = loadNgramPack(lang.id)
            val secondaryIds = _uiState.value.settings.secondaryLanguages[lang.id].orEmpty()
            engine.secondaryDictionaries = secondaryIds.filter { it != "en" }
                .mapNotNull { id -> customDictionaries[id]?.let { SecondaryDictionary(id, it) } }
            // A romanized-Bengali download is what turns Avro from unglidable
            // into glidable, so the romanization is rebuilt alongside.
            romanizedGlide = RomanizedIndex.bengali(
                spellings = engine.spellingMap,
                phonetic = engine.bengaliIndex,
                downloadedRomanized = customDictionaries["bn_rom"] ?: PackedTrie.EMPTY,
                nativeFrequency = engine.bengaliIndex::frequencyOf,
            )
            // A download can be the thing that makes a language glidable, and
            // neither the language nor the layout moved to say so.
            glideSourcesEpoch.update { it + 1 }
        }
        // A list that just arrived (or left) is a chip the bar should show (or
        // drop), and the settings did not move to say so.
        refreshDictionaryBar(_uiState.value.settings)
    }

    // ---- dictionary bar (issue #51) ----

    /**
     * Rebuilds [KeyboardUiState.dictionaryBar] from disk and settings: one chip
     * per word list, emoji pack and imported list, for every enabled language.
     * Cheap while the bar is off (nothing is walked), and off the main thread
     * otherwise — it is a few `listFiles` and a header read per language.
     */
    private suspend fun refreshDictionaryBar(settings: KeyboardSettings) {
        if (!settings.rows.dictionaryBarEnabled || !userUnlocked) {
            if (_uiState.value.dictionaryBar.isNotEmpty()) {
                _uiState.update { it.copy(dictionaryBar = emptyList()) }
            }
            return
        }
        val chips = withContext(Dispatchers.IO) { dictionaryInventory(settings) }
        if (chips != _uiState.value.dictionaryBar) {
            _uiState.update { it.copy(dictionaryBar = chips) }
        }
    }

    private fun dictionaryInventory(settings: KeyboardSettings): List<DictionaryChip> = buildList {
        for (lang in settings.enabledLanguages) {
            // The shipped or downloaded word list, as one unit: which of the
            // two is read is the engine's business, and the switch behind the
            // chip (#28's "only my lists") already covers both.
            val hasWords = lang.bundledDictionary || DictionaryStore.isDownloaded(filesDir, lang.id)
            if (hasWords) {
                add(
                    DictionaryChip(
                        langId = lang.id,
                        kind = DictionaryKind.WORDS,
                        enabled = settings.suggestionStrip.shippedDictionaryEnabledFor(lang.id),
                    ),
                )
            }
            for (file in CustomDictionaries.allLists(filesDir, lang.id)) {
                add(
                    DictionaryChip(
                        langId = lang.id,
                        kind = DictionaryKind.IMPORTED,
                        fileName = file.name,
                        label = CustomDictionaries.displayName(file).removeSuffix(".txt"),
                        enabled = CustomDictionaries.isEnabled(file),
                    ),
                )
            }
            val hasEmoji = EmojiDictStore.isDownloaded(filesDir, lang.id) ||
                EmojiKeywordPacks.packs(filesDir, lang.id).isNotEmpty()
            if (hasEmoji) {
                add(
                    DictionaryChip(
                        langId = lang.id,
                        kind = DictionaryKind.EMOJI,
                        enabled = settings.emoji.keywordsEnabledFor(lang.id),
                    ),
                )
            }
        }
    }

    /**
     * A chip on the dictionary bar was tapped: flip that dictionary wherever
     * its switch lives. Each write lands in the settings flow, whose collector
     * already rebuilds the right thing — the engine for a word list, the
     * emoji catalogue for a pack, the imported map for a list — and refreshes
     * the bar, so the chip recolours from the same emission.
     */
    fun onDictionaryChipToggle(chip: DictionaryChip) {
        vibrate()
        val on = !chip.enabled
        serviceScope.launch {
            when (chip.kind) {
                DictionaryKind.WORDS -> settingsRepository.setShippedDictionaryEnabled(chip.langId, on)
                DictionaryKind.EMOJI -> settingsRepository.setEmojiKeywordsEnabled(chip.langId, on)
                DictionaryKind.IMPORTED -> {
                    withContext(Dispatchers.IO) {
                        val file = CustomDictionaries.allLists(filesDir, chip.langId)
                            .firstOrNull { it.name == chip.fileName }
                        if (file != null) CustomDictionaries.setEnabled(file, on)
                    }
                    settingsRepository.bumpCustomDictVersion()
                }
            }
        }
    }

    /** The bar's language filter picked; null is every language. Persisted, so it is remembered. */
    fun onDictionaryFilterSelect(langId: String?) {
        vibrate()
        serviceScope.launch { settingsRepository.setDictionaryBarFilter(langId) }
    }

    /**
     * The downloaded n-gram pack for [langId], or EMPTY while locked or not
     * yet downloaded. mmap-backed: opening is one map call, no heap.
     */
    private fun loadNgramPack(langId: String): NgramPack {
        if (!userUnlocked) return NgramPack.EMPTY
        return NgramPack.of(
            MappedNgramPack.open(NgramPackDownloadManager.packFile(filesDir, langId)),
        )
    }

    /**
     * Bengali index over the bundled list plus any imported Bengali list, so
     * imported words are reachable by transliteration and not only by prefix.
     */
    private fun buildBengaliIndex(): BengaliPhoneticIndex =
        BengaliPhoneticIndex(
            if (userUnlocked) {
                bengaliAssetEntries + CustomDictionaries.entries(filesDir, "bn")
            } else {
                bengaliAssetEntries
            },
        )

    fun openSettings() {
        vibrate()
        if (currentInputEditorInfo?.packageName == packageName) {
            Toast.makeText(
                this,
                getString(R.string.ime_service_already_in_settings_toast),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        startActivity(
            MainActivityContract.intent(this)
        )
    }

    /** Long-press on a tool during customization: its settings page. */
    fun openToolSettings(tool: ToolbarTool) {
        vibrate()
        startActivity(
            MainActivityContract.intent(this)
                .putExtra(MainActivityContract.EXTRA_OPEN_TOOL, tool.name)
        )
    }

    /** Jump directly to a specific settings page route (e.g. "themes"). */
    fun openRoute(route: String) {
        vibrate()
        startActivity(
            MainActivityContract.intent(this)
                .putExtra(MainActivityContract.EXTRA_OPEN_ROUTE, route)
        )
    }

    // ---- helpers ----

    private fun hasSelection(ic: InputConnection): Boolean =
        if (expectedSelStart >= 0 && expectedSelEnd >= 0) {
            expectedSelStart != expectedSelEnd
        } else {
            !ic.getSelectedText(0).isNullOrEmpty()
        }

    /**
     * Called right after an edit that consumed the tracked selection (typing
     * or deleting over it). The editor will report the collapsed caret via
     * [onUpdateSelection] shortly; until then the answer is unknown, so
     * [hasSelection] falls back to asking the editor instead of replaying
     * the stale range.
     */
    private fun invalidateExpectedSelection() {
        expectedSelStart = -1
        expectedSelEnd = -1
        // Every caller of this is a place the field changed under us, so the
        // engine's context is suspect too. Folding it in here means a future
        // caller cannot forget to say so.
        keymanSession?.markStale()
    }

    /**
     * Re-evaluates the one-shot shift state after the cursor moved or text
     * changed: turns shift on at a sentence start, off when no longer at
     * one. Caps lock is never touched.
     */
    private fun refreshShiftForContext() {
        // Both of these end in "leave the shift alone", so the target is worked
        // out and thrown away — and working it out costs
        // [InputConnection.getCursorCapsMode], a blocking round-trip into the
        // focused app's own thread. This runs from onUpdateSelection, which is
        // to say on every keystroke, so in an app whose main thread is busy
        // that call *is* the keypress lag. With auto-capitalise off
        // [autoCapitalizeShift] answers OFF without asking, which no branch
        // below acts on; caps lock outranks whatever the field wants.
        val state = _uiState.value
        if (!state.settings.autoCapitalize) return
        if (state.shiftState == ShiftState.CAPS_LOCK) return
        // A shift the user pressed themselves, with a range selection live, is a
        // shift being held to extend that selection (see
        // [KeyboardUiState.shiftSelectsText]). Auto-capitalize must not pull it
        // out from under the next move: getCursorCapsMode answers for the
        // selection's *start*, which mid-sentence means OFF, so the very first
        // shift+arrow would disarm the shift and the second would collapse the
        // selection it just made. A shift the keyboard armed by itself is not
        // held by anyone and is still re-evaluated below, so a selection dragged
        // out by hand behaves exactly as before. The caller is onUpdateSelection,
        // which has just written both offsets, so this reads them fresh rather
        // than paying for getSelectedText.
        if (state.shiftPressedByUser && expectedSelStart >= 0 && expectedSelEnd >= 0 &&
            expectedSelStart != expectedSelEnd
        ) {
            return
        }
        val target = autoCapitalizeShift()
        _uiState.update {
            when {
                it.shiftState == ShiftState.CAPS_LOCK -> it
                it.shiftState == ShiftState.OFF && target != ShiftState.OFF ->
                    it.copy(shiftState = target, shiftPressedByUser = false)
                it.shiftState == ShiftState.ON && it.settings.autoCapitalize &&
                    target == ShiftState.OFF ->
                    it.copy(shiftState = ShiftState.OFF, shiftPressedByUser = false)
                else -> it
            }
        }
    }

    /**
     * The shift state the focused field's caps mode calls for right now.
     *
     * TYPE_TEXT_FLAG_CAP_CHARACTERS means the whole field is upper case
     * (licence plates, coupon codes), so it maps to CAPS_LOCK — a one-shot
     * shift would capitalize the first letter and drop off. The word- and
     * sentence-level modes are one-shot by nature and come back through
     * [InputConnection.getCursorCapsMode], which weighs the flags against
     * the text actually before the cursor.
     *
     * EditorInfo.initialCapsMode is the fallback for the window between
     * onStartInput and a live connection: the framework computed it for
     * exactly this purpose.
     */
    private fun autoCapitalizeShift(): ShiftState {
        val state = _uiState.value
        if (!state.settings.autoCapitalize) return ShiftState.OFF
        // Sentence capitalization applies to every Latin-script language;
        // Bengali has no letter case.
        if (!state.script.hasLetterCase) return ShiftState.OFF
        val info = currentInputEditorInfo ?: return ShiftState.OFF
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
            return ShiftState.OFF
        }
        if (info.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) {
            return ShiftState.CAPS_LOCK
        }
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType)
            ?: info.initialCapsMode
        return if (caps != 0) ShiftState.ON else ShiftState.OFF
    }

    private fun shouldAutoCapitalize(): Boolean = autoCapitalizeShift() != ShiftState.OFF

    private fun maybeAutoCapitalize() {
        val target = autoCapitalizeShift()
        _uiState.update {
            if (target != ShiftState.OFF && it.shiftState == ShiftState.OFF) {
                it.copy(shiftState = target, shiftPressedByUser = false)
            } else it
        }
    }

    // A vibrate() call while the previous effect is still playing cancels it,
    // so two presses landing within a few tens of ms (rollover typing, burst
    // double-taps) collapse into what feels like a single buzz. Enforce a
    // minimum spacing: the second buzz is deferred just enough to be felt as
    // its own click. Bursts coalesce to at most one pending buzz.
    private var lastVibrateAt = 0L
    private var vibratePending = false

    /**
     * Drives the deferred buzz. A plain [Handler] rather than a coroutine: the
     * old `serviceScope.launch { delay(wait) }` allocated a Job, a continuation
     * and a timer registration on every press that landed inside the gap —
     * which, during a fast typing burst, is most of them. One Runnable reposted
     * costs nothing and the coalescing is unchanged.
     */
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val deferredVibrate = Runnable {
        vibratePending = false
        lastVibrateAt = SystemClock.uptimeMillis()
        doVibrate()
    }

    private fun vibrate(role: KeySoundRole = KeySoundRole.DEFAULT) {
        // Key sound rides along with every feedback point; it has no
        // interference problem, so it skips the haptic coalescing below.
        playKeySound(role = role)
        vibrateOnly()
    }

    /**
     * Haptic without the key sound — for cues that are not a keypress, like
     * an emoji long-press opening its popup. The click sound there would say
     * "emoji inserted", which is exactly what did not happen.
     */
    private fun vibrateOnly() {
        val now = SystemClock.uptimeMillis()
        val wait = MIN_HAPTIC_GAP_MS - (now - lastVibrateAt)
        if (wait <= 0) {
            lastVibrateAt = now
            doVibrate()
        } else if (!vibratePending) {
            vibratePending = true
            feedbackHandler.postDelayed(deferredVibrate, wait)
        }
    }

    /**
     * Plays the key-press sound via [KeySoundPlayer]. [force] previews even
     * while the setting is off (the quick panel's toggle fires before the
     * DataStore write lands). A theme that carries its own sound
     * ([ThemeSpec.soundStyle]) beats the global pick, the same precedence its
     * colours get; an explicit [style] (a settings preview) beats both.
     */
    private fun playKeySound(
        style: com.wasimaster.wmkeyboard.core.settings.KeySoundStyle? = null,
        volume: Float? = null,
        force: Boolean = false,
        role: KeySoundRole = KeySoundRole.DEFAULT,
        phase: KeySoundPhase = KeySoundPhase.PRESS,
    ) {
        val settings = _uiState.value.settings
        if (!force && !settings.keySound) return
        // The key-up half is a preference of its own, and the cheapest place to
        // honour it is before anything is resolved: every key on the board asks
        // this question twice as often as it asks the press one.
        if (phase == KeySoundPhase.RELEASE && !settings.keySoundCustom.playRelease) return
        val theme = themeKeySound(settings)
        val resolved = style ?: theme?.first ?: settings.keySoundStyle
        // Custom and Pack read their id from different fields, and a theme
        // carrying a sound names whichever kind it chose in the same slot.
        val id = theme?.second ?: if (resolved == com.wasimaster.wmkeyboard.core.settings.KeySoundStyle.PACK) {
            settings.keySoundCustom.packId
        } else {
            settings.keySoundCustom.customId
        }
        KeySoundPlayer.play(
            this,
            resolved,
            volume ?: settings.keySoundVolume,
            id,
            role,
            phase,
        )
    }

    // The resolved theme sound is cached per settings instance and clock
    // minute: resolving it walks the theme lists, and under the schedule/sun
    // auto-theme triggers the active slot is a function of the time — a
    // keystroke is too hot a path to re-answer either on.
    private var themeSoundSettings: KeyboardSettings? = null
    private var themeSoundMinute: Int = -1
    private var themeSoundValue: Pair<com.wasimaster.wmkeyboard.core.settings.KeySoundStyle, String>? =
        null

    /**
     * The sound the active theme asks for — (style, custom sound id) — or null
     * to follow the global setting. Mirrors the composition side's
     * darkSlot resolution ([KeyboardSettings.usesDarkSlot] is the shared
     * definition) so the sounding theme is the one being painted. An unknown
     * style name, or a theme with no sound, resolves to null: the sound field
     * costs itself, never the theme.
     */
    private fun themeKeySound(
        settings: KeyboardSettings,
    ): Pair<com.wasimaster.wmkeyboard.core.settings.KeySoundStyle, String>? {
        val minute = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }
        if (settings === themeSoundSettings && minute == themeSoundMinute) return themeSoundValue
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val auto = settings.autoTheme
        val sun = if (auto.enabled && auto.trigger == AutoThemeTrigger.SUN) {
            val latitude = settings.weatherLatitude
            val longitude = settings.weatherLongitude
            if (latitude == null || longitude == null) {
                null
            } else {
                SolarCalculator.forDate(
                    latitude.toDouble(),
                    longitude.toDouble(),
                    System.currentTimeMillis(),
                )
            }
        } else {
            null
        }
        val darkSlot = auto.usesDarkSlot(systemDark, minute, sun)
        val spec = settings.activeThemeSpec(darkSlot)
        val styleName = spec?.soundStyle
        val resolved = styleName
            ?.let { wanted ->
                com.wasimaster.wmkeyboard.core.settings.KeySoundStyle.entries
                    .firstOrNull { it.name == wanted }
            }
            ?.let { it to spec.soundCustomId.orEmpty() }
        themeSoundSettings = settings
        themeSoundMinute = minute
        themeSoundValue = resolved
        return resolved
    }

    private fun doVibrate() {
        val settings = _uiState.value.settings
        if (!settings.hapticFeedback) return
        if (settings.feedback.hapticsRespectDnd && dndActive) return
        HapticPlayer.play(
            this,
            settings.hapticStyle,
            settings.hapticAmplitude,
            settings.hapticStrengthMs,
            inputRootView,
            respectSystemSetting = settings.feedback.respectSystemTouchFeedback,
        )
    }

    /**
     * Last known Do Not Disturb state, kept current by [zenObserver] rather
     * than read when a key is pressed. [readDndActive] is two binder round
     * trips — a ContentResolver query into system_server and possibly a
     * NotificationManager call — and `hapticsRespectDnd` asked for it from
     * inside the pointer-down handler, so it ran on the main thread on every
     * single keystroke.
     */
    @Volatile
    private var dndActive = false
    private var zenObserver: android.database.ContentObserver? = null

    /**
     * Watches `zen_mode` for the rest of the process's life. The setting is
     * global and needs no permission to observe, so this stays registered
     * rather than following the input view — a DND toggle while the keyboard
     * is up is exactly the case the cache has to get right.
     *
     * Deliberately does not seed [dndActive] here: that would put the two
     * binder reads on the cold-start path, and `onStartInputView` refreshes it
     * before any key can be pressed anyway.
     */
    private fun startDndWatch() {
        val observer = object : android.database.ContentObserver(feedbackHandler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                refreshDndState()
            }
        }
        zenObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.Settings.Global.getUriFor("zen_mode"),
                false,
                observer,
            )
        }
    }

    /**
     * Re-reads the state the observer cannot see. On the OEMs where `zen_mode`
     * is unreadable, [readDndActive] falls through to the interruption filter
     * and no ContentObserver ever fires for it — so the value is also refreshed
     * whenever the keyboard comes up, which is off the touch path and the point
     * at which a stale answer starts to matter.
     */
    private fun refreshDndState() {
        dndActive = readDndActive()
    }

    /**
     * Whether the system is currently in Do Not Disturb. Reads the `zen_mode`
     * global first — it's non-zero for every DND flavour and needs no
     * permission, unlike [NotificationManager.getCurrentInterruptionFilter]
     * which reports UNKNOWN without notification-policy access on some OEMs.
     * Falls back to the interruption filter where the global is unreadable.
     */
    private fun readDndActive(): Boolean {
        val zen = runCatching {
            android.provider.Settings.Global.getInt(contentResolver, "zen_mode", 0)
        }.getOrDefault(0)
        if (zen != 0) return true
        // No SDK_INT guard: currentInterruptionFilter is API 23 and minSdk is 24.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val filter = nm.currentInterruptionFilter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    companion object {
        /** Minimum spacing between haptic clicks so rapid presses stay distinct. */
        private const val MIN_HAPTIC_GAP_MS = 45L

        /** How long the dedicated language key's confirmation overlay stays up. */
        private const val LANGUAGE_HUD_FLASH_MS = 1200L

        /** Cap on the converters' typed amount — mirrors their soft keypad. */
        private const val CONVERTER_VALUE_MAX = 14

        /**
         * Packages that host input fields on behalf of other apps — in
         * practice the inline notification reply box, which reports the
         * system UI's package rather than the app being replied to.
         */
        private val notificationShellPackages = setOf("com.android.systemui")

        /**
         * How much of the sticker canvas one emoji fills. The canvas itself is
         * [StickerImage.TARGET_SIZE], shared with the sticker tool.
         */
        private const val STICKER_GLYPH_PX = 448f

        /**
         * Part of a rendered sticker's cache key. Bump it whenever the drawing
         * changes, or an emoji sent before the change keeps coming back in the
         * old form: the key is otherwise (emoji, font choice), and neither of
         * those moves when the *renderer* does. That is exactly what happened
         * when Noto learned to draw these — anything sent beforehand stayed on
         * the phone's own font, from cache, while everything new came out in
         * Noto.
         */
        private const val STICKER_RENDER_VERSION = 2

        /**
         * A caret still being dragged along by a spacebar/volume scrub does not
         * resume the word it passes over: each drag step commits the composing
         * buffer first, so resuming a word one step only to re-commit it the
         * next would churn the field (and re-learn the word) on every step.
         * Longer than the gap between drag steps, so a continuous scrub stays
         * suppressed; the settle re-read is scheduled this far out instead
         * (see [caretSettleJob]).
         */
        private const val CARET_SCRUB_WINDOW_MS = 250L

        /**
         * How much text past the caret is read when deciding what word it is
         * touching. Only the first character decides whether a word may be
         * resumed; the rest is the tail of a word the caret landed inside, so
         * this is a long-word budget rather than a context window.
         */
        private const val CARET_WORD_AHEAD = 48

        /**
         * How much text behind the caret is read to tell an opening quote from
         * a closing one (see [quoteContext]). A line's worth, and then some:
         * the count stops at the last line break anyway.
         */
        private const val QUOTE_CONTEXT_CHARS = 240

        /**
         * How long after arming a revert window its anchor keeps following the
         * editor's selection updates instead of treating them as a caret move;
         * see [revertArmedAt]. Long enough to cover an editor that reports each
         * commit of one keystroke separately (and the binder hop each report
         * takes), far short of a deliberate tap elsewhere.
         */
        private const val REVERT_SETTLE_MS = 200L

        /**
         * What undoing an autocorrect is worth towards learning the word that
         * was put back, in ordinary sightings. Two, so the default threshold
         * of three needs one reverted correction plus one plainly typed use —
         * a clear pattern rather than a single unlucky swipe.
         */
        private const val REVERT_SIGHTINGS = 2

        /**
         * Characters read either side of the caret when settling autocorrect
         * verdicts. One read at a flush, never on the typing path, and wide
         * enough to cover the corrections the watch is still holding.
         */
        private const val CORRECTION_JUDGEMENT_WINDOW = 512

        /**
         * How much non-word text may sit between the caret and the word the
         * near-miss chip is about. Room for the key that committed it and a
         * closing mark ("word." plus its space), and no more: anything longer
         * means the user has typed on and the chip is stale.
         */
        private const val CORRECTION_OFFER_TAIL = 3

        /** Height offered to autofill chips, matching the suggestion strip. */
        private const val INLINE_CHIP_HEIGHT_DP = 44

        /**
         * How long switching the dictation engine from the voice panel waits for
         * the new setting to come back through the state flow before starting to
         * listen anyway. A DataStore write plus one flow emission, not a user-
         * visible delay.
         */
        private const val ENGINE_SWITCH_TIMEOUT_MS = 1_000L

        /** See [voiceBarDockSlopPx]. */
        private const val VOICE_BAR_DOCK_SLOP_DP = 72

        /**
         * How many silent recognizer sessions in a row wind an open microphone
         * down. Block voice typing takes the low count: silence there means
         * the user has stopped and walked away.
         *
         * Interactive voice typing takes the high one, because silence is the
         * normal state of the mode. The user speaks a phrase, then types for
         * half a minute to fix it, and the microphone has to still be there
         * afterwards. Roughly a minute and a half of quiet, and the surface
         * says the whole time that it is listening.
         */
        private const val VOICE_SILENT_RETRIES = 2
        private const val VOICE_SILENT_RETRIES_INTERACTIVE = 12

        /** Inline emoji search is a local index lookup — no network wait. */
        private const val EMOJI_SEARCH_DEBOUNCE_MS = 24L

        /** The typing test's suggestion row: how long a keystroke burst is left to settle. */
        private const val TYPING_TEST_SUGGEST_DEBOUNCE_MS = 24L

        /**
         * …and how many words it asks for: the strip's own slot count, since
         * the test draws the strip itself, with a floor so a one-slot strip
         * still has a runner-up behind the word it shows.
         */
        private const val TYPING_TEST_SUGGESTION_SLOTS = 3

        /** See the delay in [refreshSmartSuggestion]; one frame, near enough. */
        private const val SMART_SUGGEST_DEBOUNCE_MS = 24L

        /** Same job, same reasoning, in [refreshSnippetOffer]. */
        private const val SNIPPET_OFFER_DEBOUNCE_MS = 24L
        private const val INLINE_EMOJI_LIMIT = 12

        /** How many contact-email completions the email-field strip may show. */
        private const val EMAIL_FIELD_SUGGESTION_LIMIT = 5

        /** Shortest token before the cursor that triggers an email completion. */
        private const val EMAIL_FIELD_MIN_PREFIX = 2

        /** How far back to read the token being completed in an email field. */
        private const val EMAIL_FIELD_LOOKBEHIND = 96

        /**
         * Words the pattern snippet gate keeps in memory. One deeper than a
         * pattern may reach ([SnippetMatcher.MAX_WORDS]), so the gate can
         * always see the word a match would have to start on.
         */
        private const val RECENT_WORDS = 8

        /**
         * How many words the ambiguity picker offers. Three fits under a
         * fingertip without the targets becoming too small to land on, and a
         * swipe that is choosing between more than three things is a swipe
         * nobody is going to resolve by looking at a list.
         */
        private const val GLIDE_CHOICES = 3

        /**
         * How near the apostrophe key a glide has to pass, in key widths, for the
         * stroke to count as having gone through it.
         *
         * Half a key: aimed at the key rather than merely near it. The contraction
         * this can turn on ("its", "were", "lets") are all drawn on the letter
         * rows, so a stroke that reaches the punctuation key went there on purpose.
         */
        private const val APOSTROPHE_CROSS_WIDTHS = 0.5f

        /** What the apostrophe-to-s flick appends. The shape test is in GlideSpace. */
        private const val POSSESSIVE = "'s"

        /**
         * Longest snippet expansion that arms backspace-to-restore.
         *
         * The undo probe reads the committed text back out of the field, and a
         * snippet may hold 20 000 characters. A read that size on the backspace
         * path — which auto-repeats — is a transaction big enough to fail. Past
         * this, an expansion the user did not want is obvious on sight anyway,
         * and the app's own undo is the right tool.
         */
        private const val SNIPPET_REVERT_MAX = 256

        /**
         * Characters that may sit between an offered pattern's span and the
         * cursor when its chip is tapped.
         *
         * The match was made just before the key that ended the word landed, so
         * by the time anyone can tap there is a space, or a full stop and the
         * space after it, in the way. Room for both, and for nothing that could
         * be a word — past that the field has moved on and the offer is stale.
         */
        private const val SNIPPET_OFFER_TAIL_MAX = 2

        /** Non-alphanumeric characters that are part of an email token. */
        private const val EMAIL_TOKEN_EXTRA = "._%+-@"

        /**
         * Floor for the handwriting recognition pause in Bengali. Its
         * multi-stroke conjuncts need more finger-up time between strokes than
         * the Latin default, so recognition doesn't fire mid-glyph.
         */
        private const val BENGALI_HW_MIN_COMMIT_DELAY_MS = 1200L
        /** Only the shipped default now; the live value is a setting. */
        private const val WEATHER_CACHE_MS = 15L * 60 * 1000

        /**
         * Floor between AI partial renders. Both backends emit far faster than
         * anyone can read, and every partial recomposes the panel.
         */
        private const val AI_PARTIAL_INTERVAL_MS = 120L
        private const val PER_APP_LRU_APPS = 20
        private const val PER_APP_RECENT_WORDS = 50

        private val SENTENCE_ENDERS = charArrayOf('.', '!', '?', '।')

        /**
         * Marks that get a space typed after them when
         * [KeyboardSettings.autoSpaceAfterPunctuation] is on — the sentence
         * enders plus the clause separators.
         *
         * Deliberately ASCII-and-danda only. The CJK wide forms (。、！？) are
         * followed by no space in their own typography, and the closing
         * brackets and quotes are left out because the space belongs after
         * whatever *they* close, not after the mark itself.
         */
        private val AUTO_SPACE_PUNCTUATION = charArrayOf('.', '!', '?', '।', ',', ';', ':')
        private const val SHIFT_DOUBLE_TAP_MS = 350L

        /**
         * The keys that move the caret and nothing else, so selection mode can
         * add shift to them and leave every other key a layout sends alone.
         *
         * The panel's own moves are not in here: they name their action rather
         * than a keycode, and [onTextEdit] reads the mode itself.
         */
        private val CARET_KEY_CODES = intArrayOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )

        /**
         * Silence after the last morse signal before the pending sequence
         * commits as its decoded character. Gboard's default pace: long enough
         * to finish a five-signal digit, short enough that typing doesn't feel
         * like waiting for the keyboard.
         */

        /** How long the SOS easter-egg note stays on the strip. */
        private const val MORSE_SOS_EGG_MS = 5000L

        /**
         * Opening bracket/brace/quote → its closer. Typing one of these with a
         * selection wraps the selected text in the pair. Symmetric quotes map
         * to themselves; closers are deliberately absent so pressing ")" over a
         * selection still just replaces it, as every keyboard does.
         */
        private val WRAP_PAIRS: Map<Char, String> = mapOf(
            '(' to ")", '[' to "]", '{' to "}", '<' to ">",
            '"' to "\"", '\'' to "'", '`' to "`",
            '“' to "”", '‘' to "’", '«' to "»", '｢' to "｣",
        )
        /** Cap on files recorded from one multi-select copy. */
        private const val MAX_FILE_CLIPS_PER_COPY = 20
        /**
         * How long the recently-copied paste chip lingers on the strip before
         * auto-hiding. Generous because typing no longer dismisses it: the chip
         * is meant to survive writing the message it will be pasted into, and
         * the user can dismiss it outright at any point.
         */
        /** Only the shipped default now; the live value is a setting. */
        private const val CLIPBOARD_SUGGESTION_TIMEOUT_MS = 5L * 60 * 1000
        /**
         * Gap between the characters of a code typed into the field (see
         * [commitCodeToField]). Long enough for a form to move the focus to its
         * next box — that handler runs off the previous character's own input
         * event — and short enough that a six-digit code is in the field
         * before the user could have read it off the chip.
         */
        private const val CODE_ENTRY_STEP_MS = 40L
        /**
         * How recently a code must have been copied to be offered as a chip in
         * a code field (see [maybeShowCopiedCodeSuggestion]). A code goes stale
         * fast, and one copied yesterday resurfacing on a login screen is
         * noise at best and the wrong code at worst.
         */
        private const val COPIED_CODE_MAX_AGE_MS = 5L * 60 * 1000
        /** Links fetched per panel open, so a history of links isn't a request storm. */
        private const val MAX_LINK_PREVIEWS = 8

        /**
         * What the enter key should do and show. IME_FLAG_NO_ENTER_ACTION
         * means the app wants a literal newline no matter what action it
         * declared, so it wins outright — that is the flag multi-line fields
         * carry, and honouring it is what keeps Enter from sending a
         * half-written message.
         *
         * A non-null actionLabel is the app asking for its own wording
         * ("Reply", "Post"); it is paired with [EditorInfo.actionId] rather
         * than a standard action, so it is reported separately.
         */
        private fun EditorInfo?.enterAction(): EnterAction {
            val info = this ?: return EnterAction.DEFAULT
            val options = info.imeOptions
            if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterAction.DEFAULT
            if (!info.actionLabel.isNullOrBlank()) return EnterAction.CUSTOM
            return when (options and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                EditorInfo.IME_ACTION_GO -> EnterAction.GO
                EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                else -> EnterAction.DEFAULT
            }
        }

        /**
         * The editor action to fire, or null when Enter should type a
         * newline instead. Mirrors [enterAction] so the key does what it
         * draws: a custom label fires the app's own [EditorInfo.actionId],
         * everything else the masked standard action.
         */
        private fun EditorInfo?.editorActionId(): Int? {
            val info = this ?: return null
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
            if (!info.actionLabel.isNullOrBlank()) return info.actionId
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action == EditorInfo.IME_ACTION_NONE ||
                action == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                return null
            }
            return action
        }

        /**
         * IME_FLAG_FORCE_ASCII: the field can only take ASCII (a server-side
         * username, a coupon code). Bengali modes — including Avro, whose
         * roman keys still commit Bengali — would put characters in it the
         * app cannot use, so the field is typed in a Latin mode instead.
         * Prefers one the user actually enabled over hard-coding English.
         */
        private fun EditorInfo?.forcesAscii(): Boolean =
            this != null && imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0

        /**
         * EditorInfo.hintLocales: the app naming the language it expects
         * (a "translate to French" box, a per-language form field). Honoured
         * only when the user has a mode for that language enabled — it is a
         * hint, not a licence to switch to a layout they never set up.
         */
        private fun EditorInfo?.hintedLanguage(enabled: List<LanguageDef>): LanguageDef? {
            val hints = this?.hintLocales ?: return null
            for (i in 0 until hints.size()) {
                val lang = LanguageRegistry.byLocale(hints[i].language) ?: continue
                enabled.firstOrNull { it.id == lang.id }?.let { return it }
            }
            return null
        }

        private fun EditorInfo?.fieldKind(): FieldKind {
            val inputType = this?.inputType ?: return FieldKind.TEXT
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
                InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
                InputType.TYPE_CLASS_DATETIME ->
                    when (inputType and InputType.TYPE_MASK_VARIATION) {
                        InputType.TYPE_DATETIME_VARIATION_DATE -> FieldKind.DATE
                        InputType.TYPE_DATETIME_VARIATION_TIME -> FieldKind.TIME
                        else -> FieldKind.DATETIME
                    }
                else -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    -> FieldKind.EMAIL
                    InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI
                    else -> FieldKind.TEXT
                }
            }
        }

        /**
         * The field asked the IME not to personalize from it. Chrome sets
         * this on every input in an incognito tab, and Firefox, Samsung
         * Internet and a few password managers do the same for their private
         * surfaces; it is the only signal Android gives us, since an IME
         * cannot see what tab or mode the host app is in.
         *
         * [EditorInfo.privateImeOptions] is checked too because some apps
         * still only send the pre-Oreo Gboard-era string.
         */
        private fun EditorInfo?.requestsNoPersonalizedLearning(): Boolean {
            val info = this ?: return false
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
            return info.privateImeOptions
                ?.split(',')
                ?.any { it.trim().endsWith("noPersonalizedLearning", ignoreCase = true) }
                ?: false
        }

        /**
         * Whether to hide the *suggestion strip* for this field. This governs
         * the strip only — autocorrect, phonetic composing and learning are
         * gated separately on the field kind
         * ([KeyboardUiState.allowsTypingIntelligence], and gesture typing on
         * [KeyboardUiState.allowsGestureTyping]), so silencing the strip never
         * disables them.
         *
         * @param overrideAppRequest the "Suggestions in every field" setting.
         * When on, the field's plea for a silent strip (the NO_SUGGESTIONS
         * flag, email/URI/filter variations) is ignored and the strip shows
         * anyway. Two things are never overridden: password variations (always
         * secret) and non-text classes, whose keypads have no words to offer.
         */
        private fun EditorInfo?.suppressesSuggestions(overrideAppRequest: Boolean): Boolean {
            val inputType = this?.inputType ?: return false
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return true
            val isPassword = when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> true
                else -> false
            }
            if (isPassword) return true
            if (overrideAppRequest) return false
            if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return true
            return when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_FILTER,
                -> true
                else -> false
            }
        }

        private fun EditorInfo?.isSecureField(): Boolean {
            val inputType = this?.inputType ?: return false
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val typeClass = inputType and InputType.TYPE_MASK_CLASS
            return typeClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) || (
                typeClass == InputType.TYPE_CLASS_NUMBER &&
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                )
        }

    }
}

/**
 * Whether a selection update means the user moved the caret away from the
 * composing region, so the composition should be abandoned.
 *
 * Extracted because it is the exact predicate a CJK prefix commit depends on and
 * it has been got wrong before. Demanding the caret sit *at* `candidatesEnd`
 * mistakes two ordinary events for a cursor jump: a field that reports no
 * composing region at all (-1), and the update that trails the keyboard's own
 * `commitText` — which arrives after a prefix commit has already re-composed the
 * tail, so the abandon path would `finishComposingText()` that fresh region and
 * drop `hao` from `nihao` as raw latin. Only a caret strictly outside the
 * reported range counts.
 */
// Public so CjkServiceIntegrationTest (in :app's androidTest) can exercise it;
// the IME's InputConnection state rules live in this function.
fun cursorLeftComposingRegion(
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Boolean = candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
    (newSelStart < candidatesStart || newSelEnd > candidatesEnd)

/**
 * One live-preview ask from a glide in progress. [generation] is the stroke it
 * belongs to: a preview that finishes decoding after its swipe has committed is
 * stale, and putting its candidates on the strip would undo the commit's own.
 */
private class GesturePreviewRequest(
    val points: List<GesturePoint>,
    val keys: List<KeyCenter>,
    val keyWidthPx: Float,
    val generation: Int,
)
