package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.text.format.DateUtils
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.wasimaster.wmkeyboard.app.drive.driveAuthorizer
import com.wasimaster.wmkeyboard.app.oauth.BackupOAuth
import com.wasimaster.wmkeyboard.app.storage.StorageCategories
import com.wasimaster.wmkeyboard.app.storage.StorageCategoryScreen
import com.wasimaster.wmkeyboard.app.statistics.StatisticsScreen
import com.wasimaster.wmkeyboard.app.storage.StorageScreen
import com.wasimaster.wmkeyboard.app.storage.storageRoute
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.BiometricAppLock
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.app.lock.LockedRoute
import com.wasimaster.wmkeyboard.app.lock.LockTarget
import com.wasimaster.wmkeyboard.app.lock.AppLockSettingsScreen
import com.wasimaster.wmkeyboard.app.updates.LocalAppUpdater
import com.wasimaster.wmkeyboard.app.updates.UpdateCard
import com.wasimaster.wmkeyboard.app.updates.rememberAppUpdater
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.core.settings.AppSortOrder
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.tools.CheatSheetLetter
import com.wasimaster.wmkeyboard.core.tools.CryptoCatalog
import com.wasimaster.wmkeyboard.core.tools.CurrencyClient
import com.wasimaster.wmkeyboard.core.tools.DefaultLeader
import com.wasimaster.wmkeyboard.core.tools.DefaultToolLetters
import com.wasimaster.wmkeyboard.core.tools.KeyChord
import com.wasimaster.wmkeyboard.core.tools.LeaderTrigger
import com.wasimaster.wmkeyboard.core.tools.ReservedChords
import com.wasimaster.wmkeyboard.core.tools.ReservedLetters
import com.wasimaster.wmkeyboard.core.tools.TapModifier
import com.wasimaster.wmkeyboard.core.tools.ToolboxLetter
import com.wasimaster.wmkeyboard.core.tools.describeChord
import com.wasimaster.wmkeyboard.core.tools.formatChord
import com.wasimaster.wmkeyboard.core.tools.formatLeader
import com.wasimaster.wmkeyboard.core.tools.parseLeader
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.core.ui.toolAccentColorArgb
import com.wasimaster.wmkeyboard.core.ui.toolAccentEndColorArgb
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import androidx.compose.ui.platform.LocalConfiguration
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.applyDeviceForm
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.feedback.R as FeedbackR
import com.wasimaster.wmkeyboard.ime.R as ImeR
import com.wasimaster.wmkeyboard.core.tools.leaderLabel
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import android.provider.OpenableColumns
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.os.Build
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.settings.EmojiBarContent
import com.wasimaster.wmkeyboard.core.settings.EmojiBarCountRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridCellSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiGridEmojiSizeRange
import com.wasimaster.wmkeyboard.core.settings.EmojiRecentsRange
import com.wasimaster.wmkeyboard.core.settings.PickerTimeoutRange
import com.wasimaster.wmkeyboard.core.settings.BottomRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.settings.ShiftCapsLockMsRange
import com.wasimaster.wmkeyboard.core.settings.DefaultCurrencyKeys
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.EmojiSkinTone
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.ime.WMKeyboardService
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.LocalIconSet
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.ime.ui.rememberIconSet
import com.wasimaster.wmkeyboard.ime.ui.ModeIcons
import com.wasimaster.wmkeyboard.core.settings.EmojiInsertMode
import com.wasimaster.wmkeyboard.core.settings.EmojiTabMode
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.AiInputMode
import com.wasimaster.wmkeyboard.core.tools.AiInsertMode
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.core.tools.orderedAiActions
import com.wasimaster.wmkeyboard.core.tools.visibleAiActions
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.GifContentFilter
import com.wasimaster.wmkeyboard.core.settings.GifSourceMode
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import com.wasimaster.wmkeyboard.core.settings.QrEccLevel
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.Weekend
import com.wasimaster.wmkeyboard.core.tools.isSouthernHemisphere
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.GeoPlace
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.core.tools.MediaCategoryCache
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.TypingAchievements
import com.wasimaster.wmkeyboard.core.tools.TypingBests
import com.wasimaster.wmkeyboard.core.tools.TypingHistory
import com.wasimaster.wmkeyboard.core.tools.TypingTestMode
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.CursorTools
import com.wasimaster.wmkeyboard.core.settings.HoldRepeatCursorTools
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.NumeralCommitScope
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.LanguageDetectionStrength
import com.wasimaster.wmkeyboard.core.settings.DefaultKeyboardModes
import com.wasimaster.wmkeyboard.core.settings.DefaultToolbarTools
import com.wasimaster.wmkeyboard.core.settings.AutoBackupIntervals
import com.wasimaster.wmkeyboard.core.settings.AutoBackupKeepRange
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.settings.DEFAULT_LONG_PRESS_LETTERS
import com.wasimaster.wmkeyboard.core.settings.KeyFontScaleRange
import com.wasimaster.wmkeyboard.core.settings.ManualModeDuration
import com.wasimaster.wmkeyboard.core.settings.SymbolRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings
import com.wasimaster.wmkeyboard.core.settings.HoldToTalkRange
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import com.wasimaster.wmkeyboard.core.settings.destinationConfigured
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import com.wasimaster.wmkeyboard.core.settings.sectionSet
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.ModeField
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedSide
import com.wasimaster.wmkeyboard.core.settings.PowerSavingTrigger
import com.wasimaster.wmkeyboard.core.settings.ScreenVariant
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sizingValuesFor
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.ToolboxLayout
import com.wasimaster.wmkeyboard.core.settings.ToolboxPageSizeRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictStore
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPack
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.emoji.EmojiSearchExamples
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.PendingLearn
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.feedback.SoundFile
import com.wasimaster.wmkeyboard.core.feedback.SoundImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.fonts.InstalledFont
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.snippets.SnippetIndex
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.snippets.SnippetPayload
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoFile
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoHub
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoManifest
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * Settings app: setup wizard plus every keyboard option, Material 3 +
 * dynamic color, all state backed by DataStore via [SettingsRepository].
 */
// A FragmentActivity rather than a plain ComponentActivity, which it extends,
// for one reason: androidx.biometric hosts its prompt in a retained fragment,
// and that is what carries an open prompt across a rotation. See
// [com.wasimaster.wmkeyboard.app.lock.BiometricAppLock]. Nothing else in this
// app uses fragments, and appcompat already put them on the classpath.
class MainActivity : FragmentActivity() {

    companion object {
        /**
         * Intent extra with a [ToolbarTool] name: the keyboard uses it
         * (tool long-press, "needs an API key" panels) to jump straight
         * to that tool's settings page.
         */
        const val EXTRA_OPEN_TOOL = MainActivityContract.EXTRA_OPEN_TOOL
        /**
         * Intent extra with a specific settings route string (e.g., "themes").
         */
        const val EXTRA_OPEN_ROUTE = MainActivityContract.EXTRA_OPEN_ROUTE
    }

    private lateinit var repository: SettingsRepository

    /**
     * The fingerprint gate. Built in [onCreate] and not lazily: the library
     * keeps its prompt in a retained fragment, and only a prompt constructed
     * while the activity is being created finds its callback again after a
     * rotation.
     */
    private lateinit var appLock: BiometricAppLock

    /** True once [AssetLayouts] has parsed; the first frame waits for it. */
    private val assetLayoutsReady = MutableStateFlow(false)

    /**
     * Where the intent that started (or re-entered) this activity wants to go.
     *
     * A flow rather than a value read once in [onCreate]: the activity is
     * `singleTop`, so a second `wmkeyboard://` link — or a second "open
     * settings" from the keyboard — arrives at [onNewIntent] on the instance
     * that is already running. Reading `intent` once would silently drop it.
     */
    private val pendingNav = MutableStateFlow<PendingNav?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shares a process with the keyboard service when both are running, so
        // this is often a no-op — but the settings app can be opened first.
        DebugLog.attach(applicationContext)
        // Same story for ML Kit: when the shared process came up on the lock
        // screen its init provider was skipped, and the handwriting model
        // manager below is one of the screens that pays for it.
        MlKitInit.ensure(applicationContext)
        // Which of the two Play services features this device can offer. Read
        // without a Context by the tool lists below, so it has to be answered
        // before the first screen composes.
        PlayServices.prime(applicationContext)
        repository = SettingsRepository(applicationContext)
        appLock = BiometricAppLock(this, repository)
        // The JSON asset layouts back the tail of the language list; the first
        // screen waits for them (below) so an enabled asset layout resolves to
        // its real language. Parsed off the main thread: this is ~350 files
        // and blocking here held the whole cold start — the window couldn't
        // even animate open — for as long as a slow phone took to parse them.
        // Off it, the parse runs alongside DataStore's own first read.
        lifecycleScope.launch(Dispatchers.Default) {
            AssetLayouts.load(applicationContext.assets)
            assetLayoutsReady.value = true
        }
        // Modes added since this install was first seeded — the settings
        // screen should list them even if the keyboard has not run yet.
        lifecycleScope.launch { repository.seedNewDefaultModes() }
        pendingNav.value = navFor(intent)
        setContent {
            // Null until DataStore's first emission: rendering nothing for a
            // frame beats flashing onboarding at users who finished it.
            val settings by repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            // Same wait for the asset layouts, or the language screens compose
            // against a half-loaded shipped set and never hear that it filled
            // in — AssetLayouts.generation is a plain counter, not state.
            val layoutsReady by assetLayoutsReady.collectAsStateWithLifecycle()
            if (!layoutsReady) return@setContent
            // The same device-form defaults the keyboard applies, or the two
            // disagree: on a tablet the number-row toggle would read off while
            // the keyboard drew the row, and the one tap that fixed the display
            // would write the value it already had and look like a dead switch.
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val pending by pendingNav.collectAsStateWithLifecycle()
            // Play In-App Updates, bound to this activity's lifecycle and its
            // result launcher. In a non-Play build this is a stub that reports
            // "unsupported" forever and the update UI draws nothing. Published
            // rather than passed down: the card on the home screen and the rows
            // on About are far apart in the tree and must not disagree about
            // the same download.
            val updater = rememberAppUpdater()
            settings?.applyDeviceForm(deviceForm)?.let { loaded ->
                AppTheme(loaded) {
                    CompositionLocalProvider(
                        LocalAppUpdater provides updater,
                        LocalAppLock provides appLock,
                    ) {
                        SettingsNavHost(
                            repository = repository,
                            settings = loaded,
                            pending = pending,
                            onPendingHandled = { pendingNav.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navFor(intent)?.let { pendingNav.value = it }
    }

    /**
     * What an intent asks for: a `wmkeyboard://` deep link, or one of the
     * extras the keyboard uses to jump into a tool's page.
     */
    private fun navFor(intent: Intent?): PendingNav? {
        if (intent == null) return null
        AddonDeepLink.routeFor(intent.data)?.let { return PendingNav(route = it) }
        SettingsShortcuts.routeFor(intent.data)?.let { return PendingNav(route = it) }
        intent.getStringExtra(EXTRA_OPEN_ROUTE)?.takeIf { it.isNotEmpty() }
            ?.let { return PendingNav(route = it) }
        val tool = intent.getStringExtra(EXTRA_OPEN_TOOL)
            ?.let { name -> ToolbarTool.entries.find { it.name == name } }
        return tool?.let { PendingNav(tool = it) }
    }
}

/** A navigation an incoming intent asked for; exactly one field is set. */
internal data class PendingNav(
    val route: String? = null,
    val tool: ToolbarTool? = null,
)

@Composable
internal fun AppTheme(settings: KeyboardSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val supportsDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Remembered, and not only to save the forty resource reads a dynamic
    // scheme costs. MaterialTheme hands the scheme down through a *static*
    // composition local, and ColorScheme compares by instance — so a freshly
    // built one, identical to the last, still counted as a change and
    // recomposed every screen under this theme. Flipping any switch in
    // Settings re-emits the settings object and lands here, so that was the
    // whole app re-composing behind each toggle.
    val configuration = LocalConfiguration.current
    val scheme = remember(dark, supportsDynamic, settings.themeMode, context, configuration) {
        val base = when {
            supportsDynamic && dark -> dynamicDarkColorScheme(context)
            supportsDynamic -> dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
        if (settings.themeMode == ThemeMode.AMOLED) {
            base.copy(background = Color.Black, surface = Color.Black)
        } else {
            base
        }
    }
    // Every settings surface draws tool icons, so the user's icon set is
    // provided here rather than per screen — the Tools list and the keyboard
    // must not disagree about what a tool looks like.
    val iconSet by rememberIconSet(settings.icons)
    // The type scale is the app's, not Material's — see [WmTypography]. It is
    // scoped to this theme, so it dresses the settings app and the screens the
    // core modules contribute to it, and leaves the keyboard itself alone.
    MaterialTheme(colorScheme = scheme, typography = WmTypography) {
        CompositionLocalProvider(LocalIconSet provides iconSet, content = content)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SettingsNavHost(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    pending: PendingNav? = null,
    onPendingHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // The path strip's record of which screens the user walked through. Saved
    // with the graph, because navigation composes only the screen on top: after
    // a rotation or a process death a trail rebuilt from composition alone
    // would come back one step long. Bound to the controller on every
    // composition, so a pressed step pops against the live back stack.
    val crumbs = rememberSaveable(saver = SettingsCrumbTrail.Saver) { SettingsCrumbTrail() }
    SideEffect {
        crumbs.bind(
            topEntryId = { navController.currentBackStackEntry?.id },
            pop = { navController.popBackStack() },
        )
    }
    // A section's icon and name fly from its home row to the heading of the
    // screen it opens, so the two read as one object being opened rather than
    // as a list and an unrelated page. Published for the whole graph here;
    // each destination adds its own scope, and the rows and headings pick both
    // up without being handed anything.
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            // A shared element is a motion and has no still version, so
            // reduced motion switches it off at the source.
            LocalSharedTransition provides if (settings.reduceMotion) null else this,
            LocalSettingsCrumbTrail provides crumbs,
        ) {
            SettingsNavGraph(navController, repository, settings, pending, onPendingHandled)
        }
    }
}

@Composable
private fun SettingsNavGraph(
    navController: NavHostController,
    repository: SettingsRepository,
    settings: KeyboardSettings,
    pending: PendingNav?,
    onPendingHandled: () -> Unit,
) {
    // A pack downloaded from these screens has to reach the running keyboard,
    // which holds its merged emoji catalogue in memory. Bumping the counter it
    // watches is that message. Collected here rather than on the screen that
    // started the download, because navigating away mid-download must not lose
    // the notification.
    LaunchedEffect(Unit) {
        EmojiDictDownloadManager.completions.collect {
            repository.bumpEmojiKeywordPackVersion()
        }
    }
    // Where an incoming intent wants to go: a wmkeyboard:// link, or the
    // keyboard's own "open settings" (tool long-press, a panel's link). Cleared
    // once handled, so returning to this screen doesn't navigate again.
    LaunchedEffect(pending) {
        if (pending == null || !settings.onboardingDone) return@LaunchedEffect
        when {
            !pending.route.isNullOrEmpty() -> navController.navigate(pending.route)
            pending.tool != null -> {
                navController.navigate("tools")
                navController.navigate("tool/${pending.tool.name}")
            }
        }
        onPendingHandled()
    }
    // A screen push, not a cross-fade: the arriving screen comes the whole way
    // in from the right while the one behind it drifts a third of the way left,
    // so the two read as a stack being pushed rather than two things dissolving.
    // Both surfaces are opaque, so nothing fades — a fade over a full-width
    // slide only makes the overlap look muddy. Collapsed to an instant cut when
    // the user has asked for reduced motion.
    val navMs = if (settings.reduceMotion) 0 else NavTransitionMs
    val spec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = navMs,
        easing = NavTransitionEasing,
    )
    val parallax = 3
    // Frozen at first composition: completing onboarding navigates away
    // explicitly, it must not yank the graph out from under the NavHost.
    val startDestination = remember { if (settings.onboardingDone) "home" else "onboarding" }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(spec) { it } },
        exitTransition = { slideOutHorizontally(spec) { -it / parallax } },
        popEnterTransition = { slideInHorizontally(spec) { -it / parallax } },
        popExitTransition = { slideOutHorizontally(spec) { it } },
    ) {
        composable("onboarding") {
            OnboardingScreen(
                repository = repository,
                settings = settings,
                onFinished = {
                    // A replay was navigated to from inside the app (About);
                    // pop back there. A first run has no back stack to return
                    // to and lands on home.
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate("home") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                },
            )
        }
        composable("home") {
            HomeScreen(
                settings = settings,
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable("search") {
            SettingsSearchScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
                onOpen = { result ->
                    // Arm the flash before navigating: the destination's rows
                    // read it during their first composition.
                    SettingsHighlight.request(result.titleRes)
                    // The search screen itself is dropped from the back stack,
                    // so backing out of the setting lands on the home list.
                    navController.popBackStack()
                    navController.navigate(result.route)
                },
            )
        }
        composable("typing") {
            SettingsScreen(
                stringResource(R.string.home_typing_title),
                { navController.popBackStack() },
                route = "typing",
            ) {
                TypingSettings(
                    repository, settings,
                    onOpenDictionary = { navController.navigate("dictionary") },
                    onOpenCustomDictionaries = { navController.navigate("customdictionaries") },
                    onOpenBlacklist = { navController.navigate("blacklist") },
                    onOpenHardwareShortcuts = { navController.navigate("hwshortcuts") },
                )
            }
        }
        composable("keypress") {
            SettingsScreen(
                stringResource(R.string.home_keypress_title),
                { navController.popBackStack() },
                route = "keypress",
            ) {
                KeyPressSettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("dictionary") {
            SettingsScreen(
                stringResource(R.string.home_screen_dictionary_title),
                { navController.popBackStack() },
                route = "dictionary",
            ) {
                DictionarySettings(repository)
            }
        }
        composable("backup") {
            SettingsScreen(
                stringResource(R.string.home_backup_title),
                { navController.popBackStack() },
                route = "backup",
            ) {
                BackupSettings(repository, settings)
            }
        }
        composable("customdictionaries") {
            SettingsScreen(
                stringResource(R.string.home_screen_custom_dictionaries_title),
                { navController.popBackStack() },
                route = "customdictionaries",
            ) {
                CustomDictionarySettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("emojikeywords") {
            SettingsScreen(
                stringResource(R.string.home_screen_emoji_keywords_title),
                { navController.popBackStack() },
                route = "emojikeywords",
            ) {
                EmojiKeywordSettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("blacklist") {
            SettingsScreen(
                stringResource(R.string.home_screen_blacklist_title),
                { navController.popBackStack() },
                route = "blacklist",
            ) {
                BlacklistSettings(repository, settings)
            }
        }
        composable("phoneformats") {
            SettingsScreen(
                stringResource(R.string.home_screen_phoneformats_title),
                { navController.popBackStack() },
                route = "phoneformats",
            ) {
                PhoneFormatSettings(repository, settings)
            }
        }
        composable("hwshortcuts") {
            SettingsScreen(
                stringResource(R.string.home_screen_hwshortcuts_title),
                { navController.popBackStack() },
                route = "hwshortcuts",
            ) {
                HardwareShortcutsSettings(repository, settings)
            }
        }
        composable("appearance") {
            SettingsScreen(
                stringResource(R.string.home_appearance_title),
                { navController.popBackStack() },
                route = "appearance",
            ) {
                AppearanceSettings(
                    repository, settings,
                    onOpenThemes = { navController.navigate("themes") },
                    onOpenFonts = { navController.navigate("fonts") },
                    onOpenIcons = { navController.navigate("icons") },
                    onNavigate = { route -> navController.navigate(route) },
                )
            }
        }
        composable(ROUTE_TEXT_EDIT_LAYOUT) {
            SettingsScreen(
                stringResource(R.string.textedit_layout_title),
                { navController.popBackStack() },
                route = ROUTE_TEXT_EDIT_LAYOUT,
            ) {
                TextEditLayoutScreen(repository)
            }
        }
        composable(ROUTE_TOOLBAR_HOLD) {
            SettingsScreen(
                stringResource(R.string.appearance_toolbar_hold_title),
                { navController.popBackStack() },
                route = ROUTE_TOOLBAR_HOLD,
            ) {
                ToolbarHoldSettings(repository, settings)
            }
        }
        composable("layout") {
            SettingsScreen(
                stringResource(R.string.home_layout_title),
                { navController.popBackStack() },
                route = "layout",
            ) {
                LayoutSettings(repository, settings)
            }
        }
        composable("fonts") {
            SettingsScreen(
                stringResource(R.string.home_screen_fonts_title),
                { navController.popBackStack() },
                route = "fonts",
            ) {
                FontSettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("icons") {
            SettingsScreen(
                stringResource(R.string.home_screen_icons_title),
                { navController.popBackStack() },
                route = "icons",
            ) {
                IconsScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("themes") {
            SettingsScreen(
                stringResource(R.string.home_screen_themes_title),
                { navController.popBackStack() },
                route = "themes",
            ) {
                ThemesScreen(
                    repository,
                    settings,
                    onNavigate = { route -> navController.navigate(route) },
                ) { id -> navController.navigate("theme_edit/$id") }
            }
        }
        composable("theme_edit/{themeId}") { backStackEntry ->
            val themeId = backStackEntry.arguments?.getString("themeId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_theme_edit_title), { navController.popBackStack() }) {
                ThemeEditorScreen(repository, settings, themeId) { route ->
                    navController.navigate(route)
                }
            }
        }
        composable(PHOTO_HUB_ROUTE) {
            PhotoServicesScreen(
                repository = repository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "$PHOTO_BROWSE_ROUTE?theme={theme}&slot={slot}",
            arguments = listOf(
                navArgument("theme") { type = NavType.StringType; defaultValue = "" },
                navArgument("slot") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PhotoBrowseScreen(
                settings = settings,
                themeId = entry.arguments?.getString("theme").orEmpty(),
                onOpenPhoto = { photo ->
                    PhotoSelection.current = photo
                    navController.navigate(PHOTO_DETAIL_ROUTE)
                },
                onNavigate = { route ->
                    if (route == BACK_ROUTE) navController.popBackStack() else navController.navigate(route)
                },
            )
        }
        composable(PHOTO_DETAIL_ROUTE) {
            val photo = PhotoSelection.current
            // The browse screen sets this immediately before navigating; it is
            // only ever null after the process was killed on the back stack.
            if (photo == null) {
                navController.popBackStack()
            } else {
                val browse = navController.previousBackStackEntry?.arguments
                PhotoDetailScreen(
                    repository = repository,
                    settings = settings,
                    photo = photo,
                    themeId = browse?.getString("theme").orEmpty(),
                    slot = BackgroundSlot.of(browse?.getString("slot")),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
        }
        composable(
            route = "$PHOTO_LIBRARY_ROUTE?theme={theme}&slot={slot}",
            arguments = listOf(
                navArgument("theme") { type = NavType.StringType; defaultValue = "" },
                navArgument("slot") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PhotoLibraryScreen(
                repository = repository,
                settings = settings,
                themeId = entry.arguments?.getString("theme").orEmpty(),
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(PHOTO_ROTATION_ROUTE) {
            PhotoRotationScreen(
                repository = repository,
                settings = settings,
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
            )
        }
        composable("keymaps") {
            SettingsScreen(
                stringResource(R.string.home_keymaps_title),
                { navController.popBackStack() },
                route = "keymaps",
            ) {
                KeyLayoutsScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("sticker_packs") {
            SettingsScreen(
                stringResource(R.string.home_screen_sticker_packs_title),
                { navController.popBackStack() },
                route = "sticker_packs",
            ) {
                StickerPacksScreen { route -> navController.navigate(route) }
            }
        }
        composable("plugins") {
            SettingsScreen(
                stringResource(R.string.home_screen_plugins_title),
                { navController.popBackStack() },
                route = "plugins",
            ) {
                PluginsScreen { route -> navController.navigate(route) }
            }
        }
        composable("plugin/{pluginId}") { entry ->
            val pluginId = entry.arguments?.getString("pluginId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_plugin_title), { navController.popBackStack() }) {
                PluginDetailScreen(pluginId) { navController.popBackStack() }
            }
        }
        // The optional `add` argument carries a repository URL from a
        // wmkeyboard://repo link; it pre-fills the add dialog, which is still
        // where the user confirms. `type` comes from a settings screen's
        // "Download more …" row and narrows the store to one kind of addon.
        composable(
            "addons?add={add}&type={type}",
            arguments = listOf(
                navArgument("add") { defaultValue = ""; type = NavType.StringType },
                navArgument("type") { defaultValue = ""; type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val prefill = decodeRouteArg(backStackEntry.arguments?.getString("add"))
            val addonType = addonTypeArg(backStackEntry.arguments?.getString("type"))
            SettingsScreen(
                stringResource(R.string.home_addons_title),
                { navController.popBackStack() },
                route = "addons",
            ) {
                AddonsScreen(prefill, addonType) { route -> navController.navigate(route) }
            }
        }
        // The repository URL travels in the path, percent-encoded — a deep link
        // names a repository by address, not by its position in the user's list.
        composable(
            "addon_repo/{repoUrl}?type={type}",
            arguments = listOf(
                navArgument("type") { defaultValue = ""; type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val url = decodeRouteArg(backStackEntry.arguments?.getString("repoUrl"))
            val addonType = addonTypeArg(backStackEntry.arguments?.getString("type"))
            // Headed with the repository, not with the act of browsing it: its
            // name over its author, both centred, both carried into the strip.
            val repo = rememberRepoHeading(url)
            SettingsScreen(
                repo.name,
                { navController.popBackStack() },
                route = addonRepoFlightRoute(url),
                centerTitle = true,
                subtitle = repo.author.ifBlank { null },
                subtitleInBar = true,
            ) {
                AddonRepoScreen(url, addonType) { route -> navController.navigate(route) }
            }
        }
        composable("addon/{repoUrl}/{addonId}") { backStackEntry ->
            val url = decodeRouteArg(backStackEntry.arguments?.getString("repoUrl"))
            val addonId = decodeRouteArg(backStackEntry.arguments?.getString("addonId"))
            // Headed with what the addon is, not with the word "Addon": the
            // type is the one thing the catalogue card already showed, so it
            // is the word that can grow into the heading.
            val heading = rememberAddonHeading(url, addonId)
            SettingsScreen(
                stringResource(heading.titleRes),
                { navController.popBackStack() },
                route = addonFlightRoute(url, addonId),
                icon = {
                    Icon(
                        heading.icon,
                        contentDescription = null,
                        modifier = Modifier.size(WmIconTileGlyph),
                    )
                },
                accent = heading.accent,
                iconTile = false,
                iconInBar = true,
                barTint = heading.accent,
            ) {
                AddonDetailScreen(url, addonId) { route -> navController.navigate(route) }
            }
        }
        composable("sticker_pack/{packId}") { backStackEntry ->
            val packId = backStackEntry.arguments?.getString("packId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_sticker_pack_edit_title), { navController.popBackStack() }) {
                StickerPackScreen(packId) { route -> navController.navigate(route) }
            }
        }
        composable(STICKER_EDITOR_ROUTE) {
            // Captured once for this back-stack entry, so clearing the handoff
            // on the way out cannot make the exit animation pop a second time.
            val request = remember { StickerEditHandoff.current }
            if (request == null) {
                // Only reachable after the process was killed on the back stack.
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                SettingsScreen(
                    stringResource(R.string.import_sticker_editor_title),
                    { navController.popBackStack() },
                ) {
                    StickerEditorScreen(request) {
                        StickerEditHandoff.current = null
                        navController.popBackStack()
                    }
                }
            }
        }
        composable("keymap_edit/{layoutId}") { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_layout_edit_title), { navController.popBackStack() }) {
                KeyLayoutEditorScreen(repository, settings, layoutId) { route ->
                    navController.navigate(route)
                }
            }
        }
        composable("keymap_json/{layoutId}") { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_layout_json_title), { navController.popBackStack() }) {
                KeyLayoutJsonScreen(repository, settings, layoutId) { navController.popBackStack() }
            }
        }
        composable("languages") {
            SettingsScreen(
                stringResource(R.string.home_languages_title),
                { navController.popBackStack() },
                route = "languages",
            ) {
                LanguageSettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("add_language") {
            SettingsScreen(
                stringResource(R.string.home_screen_add_language_title),
                { navController.popBackStack() },
                route = "add_language",
            ) {
                AddLanguageScreen(repository, settings) { langId ->
                    navController.navigate("language/$langId")
                }
            }
        }
        composable("language/{langId}") { backStackEntry ->
            val langId = backStackEntry.arguments?.getString("langId").orEmpty()
            SettingsScreen(
                LanguageRegistry.byId(langId).displayName,
                { navController.popBackStack() },
                route = "language/$langId",
            ) {
                LanguageDetailScreen(
                    langId, repository, settings,
                    onNavigate = { route -> navController.navigate(route) },
                    onRemoved = { navController.popBackStack() },
                )
            }
        }
        // One segment longer than "language/{langId}", so the two patterns
        // cannot match each other's URLs.
        composable("language/{langId}/more") { backStackEntry ->
            val langId = backStackEntry.arguments?.getString("langId").orEmpty()
            SettingsScreen(
                stringResource(R.string.languages_more_layouts_title),
                { navController.popBackStack() },
                route = moreLayoutsRoute(langId),
                // The heading says which language's catalogue this is: "More
                // layouts" on its own is the same title under all 843 of them.
                subtitle = LanguageRegistry.byId(langId).displayName,
            ) {
                MoreLayoutsScreen(langId, repository, settings)
            }
        }
        composable("emoji") {
            SettingsScreen(
                stringResource(R.string.home_emoji_title),
                { navController.popBackStack() },
                route = "emoji",
            ) {
                EmojiSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("voice") {
            SettingsScreen(
                stringResource(R.string.home_voice_title),
                { navController.popBackStack() },
                route = "voice",
            ) {
                VoiceSettings(repository, settings)
            }
        }
        composable("clipboard") {
            SettingsScreen(
                stringResource(R.string.home_clipboard_title),
                { navController.popBackStack() },
                route = "clipboard",
            ) {
                ClipboardSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("expander") {
            SettingsScreen(
                stringResource(R.string.home_expander_title),
                { navController.popBackStack() },
                route = "expander",
            ) {
                SnippetSettings { navController.navigate(it) }
            }
        }
        composable("tools") {
            SettingsScreen(
                stringResource(R.string.home_tools_title),
                { navController.popBackStack() },
                route = "tools",
            ) {
                ToolsSettings(repository, settings) { tool -> navController.navigate("tool/${tool.name}") }
            }
        }
        composable("tool/{toolName}") { backStackEntry ->
            val tool = backStackEntry.arguments?.getString("toolName")
                ?.let { runCatching { ToolbarTool.valueOf(it) }.getOrNull() }
            if (tool != null) {
                // A tool's colour belongs to the tool, not to a place in the
                // settings tree, so it paints the glyph and leaves the bar
                // alone — a dozen tool pages each repainting the strip would
                // read as a dozen unrelated apps. No tile either: the glyph
                // bare is the same object the Tools row drew, so it can fly
                // from it. It stays through the collapse — it is the only
                // thing naming which tool this is.
                val paint = toolAccentPaint(tool, settings)
                SettingsScreen(
                    stringResource(toolTitle(tool)),
                    { navController.popBackStack() },
                    route = toolRoute(tool),
                    icon = { ToolGlyph(tool, paint?.brush) },
                    // The heading wears the tool's colour whether or not the
                    // colourful icons are on: it is the only thing naming which
                    // tool this page is.
                    accent = toolAccentColor(tool, settings.toolColorOverrides),
                    iconTile = false,
                    iconInBar = true,
                ) {
                    ToolDetailSettings(repository, settings, tool) { route ->
                        navController.navigate(route)
                    }
                }
            }
        }
        composable("accessibility") {
            SettingsScreen(
                stringResource(R.string.home_accessibility_title),
                { navController.popBackStack() },
                route = "accessibility",
            ) {
                AccessibilitySettings(
                    repository, settings,
                    onOpenFonts = { navController.navigate("fonts") },
                    onOpenLayout = { navController.navigate("layout") },
                    onOpenKeyPress = { navController.navigate("keypress") },
                )
            }
        }
        composable("privacy") {
            SettingsScreen(
                stringResource(R.string.home_privacy_title),
                { navController.popBackStack() },
                route = "privacy",
            ) {
                PrivacySettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("permissions") {
            SettingsScreen(
                stringResource(R.string.privacy_permissions_title),
                { navController.popBackStack() },
                route = "permissions",
            ) {
                PermissionsSettings()
            }
        }
        composable(AppLockTargets.ROUTE) {
            SettingsScreen(
                stringResource(R.string.privacy_lock_title),
                { navController.popBackStack() },
                route = AppLockTargets.ROUTE,
            ) {
                // Guarded like any other destination: SettingsScreen resolves
                // this route to AppLockTargets.SELF, so the screen holding the
                // off switch is itself behind the lock.
                AppLockSettingsScreen(repository)
            }
        }
        composable("datasaver") {
            SettingsScreen(
                stringResource(R.string.home_datasaver_title),
                { navController.popBackStack() },
                route = "datasaver",
            ) {
                DataSaverSettingsScreen(repository, settings)
            }
        }
        composable("rows") {
            SettingsScreen(
                stringResource(R.string.home_rows_title),
                { navController.popBackStack() },
                route = "rows",
            ) {
                RowsSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("ai_actions") {
            SettingsScreen(
                stringResource(R.string.home_screen_ai_actions_title),
                { navController.popBackStack() },
                route = "ai_actions",
            ) {
                AiActionsSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("ai_history") {
            SettingsScreen(
                stringResource(R.string.home_screen_ai_history_title),
                { navController.popBackStack() },
                route = "ai_history",
            ) {
                AiHistoryScreen(repository, settings)
            }
        }
        // The two chat destinations draw their own scaffold rather than going
        // through SettingsScreen, so they are the only places the fingerprint
        // gate has to be written out by hand. Both answer to the same target:
        // locking the list has to lock a deep link straight into one
        // conversation, or it locks nothing.
        composable("ai_chat") {
            LockedRoute("ai_chat", onCancel = { navController.popBackStack() }) {
                AiChatListScreen(
                    onOpenChat = { id -> navController.navigate("ai_chat/$id") },
                    onNewChat = { navController.navigate("ai_chat/new") },
                    // First-ever open: replace the empty list with the chat, so
                    // back leaves the feature instead of landing on a blank list.
                    onAutoNew = {
                        navController.navigate("ai_chat/new") {
                            popUpTo("ai_chat") { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable("ai_chat/{conversationId}") { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("conversationId").orEmpty()
            LockedRoute("ai_chat", onCancel = { navController.popBackStack() }) {
                AiChatScreen(
                    settings = settings,
                    conversationId = raw.toLongOrNull() ?: -1L,
                    onBack = { navController.popBackStack() },
                    onOpenAiSettings = { navController.navigate("tool/${ToolbarTool.AI.name}") },
                )
            }
        }
        composable("ai_action_edit/{actionId}") { backStackEntry ->
            val actionId = backStackEntry.arguments?.getString("actionId").orEmpty()
            SettingsScreen(
                stringResource(R.string.home_screen_ai_action_edit_title),
                { navController.popBackStack() },
            ) {
                AiActionEditor(repository, settings, actionId) { navController.popBackStack() }
            }
        }
        composable("symbol_set_edit/{setId}") { backStackEntry ->
            val setId = backStackEntry.arguments?.getString("setId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_symbol_set_edit_title), { navController.popBackStack() }) {
                SymbolSetEditor(repository, settings, setId) { navController.popBackStack() }
            }
        }
        composable("modes") {
            SettingsScreen(
                stringResource(R.string.home_modes_title),
                { navController.popBackStack() },
                route = "modes",
            ) {
                ModesSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("mode_edit/{modeId}") { backStackEntry ->
            val modeId = backStackEntry.arguments?.getString("modeId").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_mode_edit_title), { navController.popBackStack() }) {
                ModeEditor(repository, settings, modeId) { navController.popBackStack() }
            }
        }
        composable("about") {
            SettingsScreen(
                stringResource(R.string.home_about_title),
                { navController.popBackStack() },
                route = "about",
            ) {
                AboutSettings(
                    persona = settings.onboarding,
                    onOpenLicenses = { navController.navigate("licenses") },
                    onOpenLicenseText = { navController.navigate("license_text/$it") },
                    onOpenDebugLog = { navController.navigate("debug_log") },
                    onOpenStorage = { navController.navigate("storage") },
                    onOpenStatistics = { navController.navigate("statistics") },
                    onOpenEggGame = { navController.navigate("egg_game") },
                    onReplayOnboarding = { navController.navigate("onboarding") },
                )
            }
        }
        composable("egg_game") {
            // The screen behind seven taps on the version row. Deliberately
            // absent from the search index and the launcher shortcuts — a
            // secret that can be searched for is a menu entry.
            KeycapCatcherScreen(anim = this) { navController.popBackStack() }
        }
        composable("storage") {
            SettingsScreen(
                stringResource(R.string.about_storage_title),
                { navController.popBackStack() },
                route = "storage",
            ) {
                StorageScreen(repository) { navController.navigate(storageRoute(it)) }
            }
        }
        composable("statistics") {
            SettingsScreen(
                stringResource(R.string.statistics_title),
                { navController.popBackStack() },
                route = "statistics",
            ) {
                StatisticsScreen(repository, settings)
            }
        }
        composable("storage/{category}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("category").orEmpty()
            val category = StorageCategories.byId(id)
            // The category's own icon and hue, and its own route: the row on the
            // Storage screen flies its tile up into this heading, and a shared
            // "storage" route would land every one of them as the pie chart.
            val glyph: (@Composable () -> Unit)? = category?.let {
                // Sized like the glyphs the bar derives from a route, so a
                // category heading matches every other heading in the app.
                { Icon(it.icon, contentDescription = null, modifier = Modifier.size(WmIconTileGlyph)) }
            }
            SettingsScreen(
                stringResource(category?.title ?: R.string.about_storage_title),
                { navController.popBackStack() },
                route = storageRoute(id),
                icon = glyph,
                accent = category?.accent,
            ) {
                StorageCategoryScreen(id, repository) { route ->
                    // Straight across to the screen that owns the data, rather
                    // than deeper: the storage tree is a way in, not a place.
                    navController.popBackStack()
                    navController.navigate(route)
                }
            }
        }
        composable("debug_log") {
            SettingsScreen(
                stringResource(R.string.home_screen_debug_log_title),
                { navController.popBackStack() },
                route = "debug_log",
            ) {
                DebugLogScreen()
            }
        }
        composable("licenses") {
            SettingsScreen(
                stringResource(R.string.home_screen_licenses_title),
                { navController.popBackStack() },
                route = "licenses",
            ) {
                LicensesScreen { navController.navigate("license_text/$it") }
            }
        }
        composable("license_text/{asset}") { backStackEntry ->
            val asset = backStackEntry.arguments?.getString("asset").orEmpty()
            SettingsScreen(stringResource(R.string.home_screen_license_title), { navController.popBackStack() }) {
                LicenseTextScreen(asset)
            }
        }
    }
}

// ---- home / setup ----

@Composable
private fun AnimatedVisibilityScope.HomeScreen(
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val setup = rememberKeyboardSetup(context)
    // The install-anniversary easter egg. The card stays up all day; the toast
    // and the confetti fire once per year, whoever opens the screen first.
    val anniversaryYears = rememberAnniversaryYears(context)
    var confetti by remember { mutableStateOf(false) }
    val birthdayToast = stringResource(R.string.egg_anniversary_toast)
    LaunchedEffect(anniversaryYears) {
        if (anniversaryYears != null && AnniversaryEgg.claimCelebration(context)) {
            Toast.makeText(context, birthdayToast, Toast.LENGTH_LONG).show()
            // Confetti is unsolicited ambient motion, so reduce motion turns it
            // off and keeps the card and the toast, which hold still.
            if (!settings.reduceMotion) confetti = true
        }
    }
    // The one screen whose heading is the app's own name rather than a place
    // inside it, so it is centred rather than hung off the bar's left edge.
    // Once there is nothing to set up, the card saying so would be a whole
    // card spent on good news — it becomes a line under the heading instead.
    Box {
        WmScreen(
            title = stringResource(R.string.app_name),
            route = "home",
            centerTitle = true,
            subtitle = if (setup.ready) stringResource(R.string.home_active_subtitle) else null,
            subtitleIcon = if (setup.ready) Icons.Outlined.CheckCircle else null,
            subtitleIconTint = ActiveGreen,
            badge = { AppIconBadge() },
            badgeInBar = true,
            // The heading here is the app's name; in the path strip of the
            // screens below, this one is where the settings start.
            crumbTitle = stringResource(R.string.shell_breadcrumb_home),
            anim = this@HomeScreen,
            actions = {
                IconButton(onClick = { onNavigate("search") }) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.home_search_desc),
                    )
                }
            },
        ) {
            if (!setup.ready) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SetupCard(context, setup = setup)
                }
                Spacer(Modifier.height(8.dp))
            }
            // Directly under the "Your active keyboard" heading area, above the
            // update card: a birthday outranks being one version behind, one day
            // a year.
            if (anniversaryYears != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AnniversaryCard(anniversaryYears)
                }
                Spacer(Modifier.height(8.dp))
            }
            // Below the setup card on purpose: a keyboard that is not switched on
            // yet has a more pressing problem than being one version behind. Draws
            // nothing at all unless Play is offering something.
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                UpdateCard()
            }
            SettingsGroup(stringResource(R.string.home_group_typing_title)) {
                item {
                    HomeItem(
                        "typing", Icons.Outlined.Keyboard,
                        stringResource(R.string.home_typing_title),
                        stringResource(R.string.home_typing_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "keypress", Icons.Outlined.TouchApp,
                        stringResource(R.string.home_keypress_title),
                        stringResource(R.string.home_keypress_subtitle), onNavigate,
                    )
                }
                item {
                    // Named from what is actually enabled, not a fixed pair — the
                    // enabled set now starts from the phone's own languages, so
                    // there is no one right answer to hard-code here.
                    HomeItem(
                        "languages", Icons.Outlined.Language,
                        stringResource(R.string.home_languages_title),
                        enabledLanguagesSummary(settings), onNavigate,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.home_group_keyboard_title)) {
                item {
                    HomeItem(
                        "appearance", Icons.Outlined.Palette,
                        stringResource(R.string.home_appearance_title),
                        stringResource(R.string.home_appearance_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "layout", Icons.Outlined.AspectRatio,
                        stringResource(R.string.home_layout_title),
                        stringResource(R.string.home_layout_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "keymaps", Icons.Outlined.GridOn,
                        stringResource(R.string.home_keymaps_title),
                        stringResource(R.string.home_keymaps_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "rows", Icons.Outlined.ViewAgenda,
                        stringResource(R.string.home_rows_title),
                        stringResource(R.string.home_rows_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "modes", Icons.Outlined.Tune,
                        stringResource(R.string.home_modes_title),
                        stringResource(R.string.home_modes_subtitle), onNavigate,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.home_group_features_title)) {
                item {
                    HomeItem(
                        "emoji", Icons.Outlined.EmojiEmotions,
                        stringResource(R.string.home_emoji_title),
                        stringResource(R.string.home_emoji_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "voice", Icons.Outlined.Mic,
                        stringResource(R.string.home_voice_title),
                        stringResource(R.string.home_voice_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "clipboard", Icons.Outlined.ContentPaste,
                        stringResource(R.string.home_clipboard_title),
                        stringResource(R.string.home_clipboard_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "expander", Icons.AutoMirrored.Outlined.TextSnippet,
                        stringResource(R.string.home_expander_title),
                        stringResource(R.string.home_expander_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "tools", Icons.Outlined.Widgets,
                        stringResource(R.string.home_tools_title),
                        stringResource(R.string.home_tools_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "addons", Icons.Outlined.Extension,
                        stringResource(R.string.home_addons_title),
                        stringResource(R.string.home_addons_subtitle), onNavigate,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.home_group_accessibility_title)) {
                item {
                    HomeItem(
                        "accessibility", Icons.Outlined.Accessibility,
                        stringResource(R.string.home_accessibility_title),
                        stringResource(R.string.home_accessibility_subtitle), onNavigate,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.home_group_data_title)) {
                item {
                    HomeItem(
                        "privacy", Icons.Outlined.Security,
                        stringResource(R.string.home_privacy_title),
                        stringResource(R.string.home_privacy_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "datasaver", Icons.Outlined.NetworkCheck,
                        stringResource(R.string.home_datasaver_title),
                        stringResource(R.string.home_datasaver_subtitle), onNavigate,
                    )
                }
                item {
                    HomeItem(
                        "backup", Icons.Outlined.Save,
                        stringResource(R.string.home_backup_title),
                        stringResource(R.string.home_backup_subtitle), onNavigate,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.home_group_about_title)) {
                item {
                    HomeItem(
                        "about", Icons.Outlined.Info,
                        stringResource(R.string.home_about_title),
                        stringResource(R.string.home_about_subtitle), onNavigate,
                    )
                }
            }
        }
        if (confetti) {
            ConfettiOverlay(Modifier.matchParentSize(), onFinished = { confetti = false })
        }
    }
}

/** The launcher squircle's corner, as a share of the icon's own width. */
private const val AppIconCorner = 28

/** The tick beside "currently active" — a state, so it is green rather than themed. */
private val ActiveGreen = Color(0xFF43A047)

/**
 * The launcher icon, drawn above the root screen's heading.
 *
 * Composed by hand from the adaptive icon's own two layers rather than loaded
 * as `@mipmap/ic_launcher`: on API 26 and up that resource *is* the
 * `<adaptive-icon>` XML, which `painterResource` cannot inflate. The layers are
 * 108 units wide with the visible circle 72 of them across, so they are drawn
 * oversized by that ratio and masked back down — the same arithmetic the
 * launcher does.
 */
@Composable
private fun AppIconBadge() {
    val layer = HeaderBadgeSize * 108f / 72f
    Box(
        // The launcher's own squircle rather than a circle, so the icon here
        // and the icon on the home screen are recognisably the same object.
        modifier = Modifier.size(HeaderBadgeSize).clip(RoundedCornerShape(AppIconCorner)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(com.wasimaster.wmkeyboard.R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.size(layer),
        )
        Image(
            painterResource(com.wasimaster.wmkeyboard.R.mipmap.ic_launcher_fg),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(layer),
        )
    }
}

/** How far through enabling and selecting the keyboard the user has got. */
internal data class KeyboardSetupState(val enabled: Boolean, val selected: Boolean) {
    val ready: Boolean get() = enabled && selected
}

/**
 * Whether this keyboard is turned on in the system keyboard settings.
 *
 * A plain function rather than part of [rememberKeyboardSetup] alone, because
 * the onboarding wizard has to read this while its own window is gone and
 * recomposition is stopped — see `ReturnAfterEnabling`.
 */
internal fun imeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

/** Whether this keyboard is the one the system currently types with. */
internal fun imeSelected(context: Context): Boolean =
    Settings.Secure
        .getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/') == context.packageName

/**
 * Watches whether this keyboard is enabled and selected. The IME picker is a
 * system dialog, so the activity never pauses or resumes when the user
 * switches keyboards — polling while visible is what keeps the answer honest.
 *
 * [onReady] fires once per transition into the ready state, so onboarding can
 * advance without the user tapping Next after returning from Settings, the way
 * other keyboard apps do.
 */
@Composable
internal fun rememberKeyboardSetup(
    context: Context,
    onReady: (() -> Unit)? = null,
): KeyboardSetupState {
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            refresh++
            delay(1000)
        }
    }
    val state = remember(refresh) {
        KeyboardSetupState(enabled = imeEnabled(context), selected = imeSelected(context))
    }
    LaunchedEffect(state.ready) {
        if (state.ready) onReady?.invoke()
    }
    return state
}

/**
 * The enable-and-select prompt. Callers that have already read the state — the
 * home screen, which says the same thing in its heading instead — pass it in
 * rather than starting a second poll.
 *
 * [onEnableRequested] fires as the user leaves for the system keyboard
 * settings, for the caller that wants to watch what happens there. The wizard
 * uses it to bring itself back the moment the keyboard is turned on.
 */
@Composable
internal fun SetupCard(
    context: Context,
    onReady: (() -> Unit)? = null,
    setup: KeyboardSetupState = rememberKeyboardSetup(context, onReady),
    onEnableRequested: (() -> Unit)? = null,
) {
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val enabled = setup.enabled

    if (setup.ready) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.home_setup_active_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.home_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) stringResource(R.string.home_setup_enabled_body)
                else stringResource(R.string.home_setup_disabled_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            // Both steps, stacked, with only the one still to do drawn as a
            // button to press. Showing one at a time read as the same button
            // twice — "did I not already do this?" — and showing both as
            // equals put a live-looking control on a step that cannot run yet.
            SetupStep(
                label = stringResource(R.string.home_setup_enable_action),
                done = enabled,
                // The first step is the pending one until it is done.
                pending = !enabled,
                onClick = {
                    onEnableRequested?.invoke()
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            Spacer(Modifier.height(8.dp))
            SetupStep(
                label = stringResource(R.string.home_setup_switch_action),
                done = false,
                pending = enabled,
                onClick = { imm.showInputMethodPicker() },
            )
        }
    }
}

/**
 * One step of the setup card.
 *
 * Three states, and the styling is the whole point of the row: [pending] is
 * the one thing to press, so it is the only filled button; [done] has a tick
 * and goes quiet; a step that is neither is still ahead of the user and is
 * drawn as an outline they cannot press, so it reads as "later" rather than
 * as a second thing to try.
 */
@Composable
private fun SetupStep(label: String, done: Boolean, pending: Boolean, onClick: () -> Unit) {
    if (pending) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        return
    }
    OutlinedButton(
        onClick = onClick,
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (done) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }
}

/**
 * A destination on the settings home. The icon is drawn on the destination's
 * own accent tile — the home list is the app's front door, so it is the one
 * place that trades a uniform column of primary for something scannable.
 *
 * The tile and the name are also the take-off end of the flight into the
 * screen this row opens: the same tile becomes that screen's heading icon, and
 * the same word its heading.
 */
@Composable
private fun HomeItem(
    route: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onNavigate: (String) -> Unit,
) {
    WmRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = routeAccent(route),
        flightTo = route,
        onClick = { onNavigate(route) },
    )
}

// ---- shared scaffold & group card system ----

/**
 * A settings destination. Declared on [AnimatedVisibilityScope] so every call
 * inside a `composable { }` block picks the destination's own animation scope
 * up for free — that scope is half of what a shared element needs.
 *
 * [route] is set on the screens that have a row on the home list: it earns the
 * heading its icon, and flies both the icon and the name over from that row.
 */
@Composable
private fun AnimatedVisibilityScope.SettingsScreen(
    title: String,
    onBack: () -> Unit,
    route: String? = null,
    icon: (@Composable () -> Unit)? = null,
    accent: Color? = null,
    iconTile: Boolean = true,
    iconInBar: Boolean = false,
    barTint: Color? = null,
    centerTitle: Boolean = false,
    subtitle: String? = null,
    subtitleInBar: Boolean = false,
    content: @Composable () -> Unit,
) {
    // A highlight that found no matching row on this screen (the searched
    // entry was the screen itself, or its row is conditionally hidden) must
    // not survive to flash something unrelated on the next screen — unless it
    // was armed *from* this screen on the way out, which is what an addon's
    // Use button does.
    val highlightSerial = remember(title) { SettingsHighlight.serial }
    DisposableEffect(title) { onDispose { SettingsHighlight.clearIfUnchanged(highlightSerial) } }
    WmScreen(
        title = title,
        onBack = onBack,
        route = route,
        icon = icon,
        accent = accent,
        iconTile = iconTile,
        iconInBar = iconInBar,
        barTint = barTint,
        centerTitle = centerTitle,
        subtitle = subtitle,
        subtitleInBar = subtitleInBar,
        anim = this,
    ) {
        // The one place every settings destination passes through, and it
        // already knows its own route, so the fingerprint gate needs no new
        // parameter and no per-screen wiring. Only the body is replaced: the
        // bar, the title and the shared element that flew it in stay, so a
        // locked screen still says where you are.
        //
        // It also means a deep link or an EXTRA_OPEN_ROUTE lands on the gate
        // rather than around it, because the gate is at the destination and
        // not at the navigate() call.
        if (route == null) content() else LockedRoute(route, onCancel = onBack, content = content)
    }
}

/**
 * Collects the rows of one visually grouped card stack — the modern
 * Material settings look: each row sits on its own surface with tiny
 * gaps between rows, large rounded corners at the group's ends and
 * small ones inside.
 */
internal class SettingsGroupScope {
    val items = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

@Composable
internal fun SettingsGroup(
    title: String? = null,
    @StringRes highlightKey: Int = 0,
    builder: SettingsGroupScope.() -> Unit,
) {
    // The builder runs during composition, so rows may be added
    // conditionally on snapshot state (e.g. sliders that appear only
    // while their feature's toggle is on).
    val scope = SettingsGroupScope().apply(builder)
    if (scope.items.isEmpty()) return
    // Below the fold while the screen is still animating in: come back for
    // the rows once the entrance can spare them — see [rememberGroupRevealed].
    if (!rememberGroupRevealed(scope.items.size)) return
    // A named group is a scroll target in its own right. Some things the user
    // arrives at from search — or from an addon's Use button — are a whole
    // section rather than one row: "Icon pack", "Your packs", "Installed
    // fonts". Unnamed groups have nothing to match on and stay plain. Coarse,
    // because anything inside it that answers to the same request is a better
    // answer than the section around them.
    HighlightableRow(title, highlightKey, coarse = true) {
        if (title != null) SectionHeader(title)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            scope.items.forEachIndexed { index, row ->
                val top = if (index == 0) 24.dp else 6.dp
                val bottom = if (index == scope.items.lastIndex) 24.dp else 6.dp
                Surface(
                    shape = RoundedCornerShape(
                        topStart = top, topEnd = top,
                        bottomStart = bottom, bottomEnd = bottom,
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) { row() }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** ListItem colors that let the group card's surface show through. */
@Composable
internal fun transparentListColors(): ListItemColors =
    ListItemDefaults.colors(containerColor = Color.Transparent)

/** "?" affordance that opens a dialog with the full explanation of a setting. */
@Composable
internal fun InfoButton(title: String, detail: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.home_info_desc, title),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = { Text(detail) },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

/**
 * A permission's grant state, re-read every time the settings screen comes
 * back to the foreground. Both the runtime permissions and the special ones
 * (Usage Access) are granted on a system screen we leave the app for, so a
 * plain read during composition stays stale until something unrelated
 * recomposes — which is what made the "permission required" rows outlive the
 * grant.
 */
@Composable
internal fun rememberGrantState(check: (Context) -> Boolean): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(check(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = check(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/** The permission that lets the clipboard read the user's screenshots. */
private val ImagesPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasImagesPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, ImagesPermission) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Whether the user granted Usage Access — a special permission, so it is an
 * app-op rather than a runtime grant. Mirrors the IME's own check.
 */
internal fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        ?: return false
    val mode = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName,
            )
        }
    }.getOrDefault(AppOpsManager.MODE_ERRORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // Aligns with the text inside group rows: 16dp group margin
        // plus the rows' own 16dp content inset.
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    )
}

/** Free-standing explanatory text aligned with group content. */
@Composable
internal fun CaptionText(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/*
 * The row helpers come in pairs. The `@StringRes` form is the one to use: it
 * knows the row's own resource, so it can look the row's glyph up in
 * [SettingsRowIcons] and match a search highlight on the resource rather than
 * on the drawn words. The `String` form is for the handful of rows whose name
 * is not a fixed resource at all — a language, a layout, an installed pack —
 * and those get no icon, because there is no row to hang one on.
 */

/** [NavRow] for a row named by a string resource; see the note above. */
@Composable
internal fun NavRow(
    @StringRes title: Int,
    subtitle: String? = null,
    value: String? = null,
    route: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    onClick: () -> Unit,
) = NavRow(
    title = stringResource(title),
    subtitle = subtitle,
    value = value,
    route = route,
    icon = icon,
    highlightKey = title,
    onClick = onClick,
)

/**
 * A navigation row: title, optional subtitle, optional current value, chevron.
 *
 * [route] names the destination the row opens, which flies the row's name up
 * into that screen's heading. Only the name travels; the heading's icon fades
 * in with its screen rather than flying from the row's own tile.
 */
@Composable
internal fun NavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    route: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    onClick: () -> Unit,
) {
    HighlightableRow(title, highlightKey) {
        WmRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            flightTo = route,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value != null) {
                        // Capped, or a long value takes the width it asks for
                        // and the title is left wrapping one word per line.
                        // ListItem hands the trailing slot whatever it wants
                        // and gives the headline the remainder, so the limit
                        // has to be here.
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = NAV_ROW_VALUE_MAX_WIDTH),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onClick,
        )
    }
}

/**
 * How wide a [NavRow] value may get before it wraps and then ellipsizes.
 *
 * Roughly a third of a phone, which leaves the title the two thirds it needs to
 * stay on one or two lines. A value longer than two lines at this width was
 * never going to be read off a row anyway — it belongs in the screen the row
 * opens.
 */
private val NAV_ROW_VALUE_MAX_WIDTH = 132.dp

/** [ToggleSetting] for a row named by a string resource. */
@Composable
internal fun ToggleSetting(
    @StringRes title: Int,
    subtitle: String?,
    checked: Boolean,
    info: String? = null,
    switchKey: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    enabled: Boolean = true,
    default: Boolean? = null,
    onChange: (Boolean) -> Unit,
) = ToggleSetting(
    title = stringResource(title),
    subtitle = subtitle,
    checked = checked,
    info = info,
    switchKey = switchKey,
    icon = icon,
    highlightKey = title,
    enabled = enabled,
    default = default,
    onChange = onChange,
)

/**
 * [default] is the value the setting shipped with, from [SettingsDefaults].
 * Passing it gives the row a reset control while it is switched the other way;
 * see [ResetSetting]. Leave it null on the rows that have no one default — a
 * toggle over a list the user built, or one whose default depends on the
 * device rather than on the app.
 */
@Composable
internal fun ToggleSetting(
    title: String,
    subtitle: String?,
    checked: Boolean,
    info: String? = null,
    switchKey: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    // Off for a setting that cannot be turned on yet — the row still reads and
    // still opens, it just has nothing to switch. The subtitle is where the
    // caller says why.
    enabled: Boolean = true,
    default: Boolean? = null,
    onChange: (Boolean) -> Unit,
) {
    HighlightableRow(title, highlightKey) {
        WmRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info != null) InfoButton(title, info)
                    ResetSetting(title, default != null && checked != default) {
                        onChange(default == true)
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = onChange,
                        enabled = enabled,
                        // The same switch the row that opened this screen was
                        // showing, when the caller says so.
                        modifier = if (switchKey == null) Modifier
                        else Modifier.wmSharedElement(switchKey),
                    )
                }
            },
        )
    }
}

/**
 * How often a drag in progress is pushed through [SliderSetting]'s `onChange`,
 * i.e. written to DataStore. Live previews still follow the finger closely, but
 * a 100-pixel drag no longer queues 100 preference writes (each of which
 * recomposes the whole settings screen).
 */
private const val SLIDER_WRITE_INTERVAL_MS = 40L

/**
 * The live position of a settings slider, held locally so the thumb follows the
 * finger instead of the stored value: routing every touch event through a
 * DataStore write and waiting for the settings flow to come back made the thumb
 * visibly trail. Create one with [rememberLiveSlider], read [value] for both the
 * thumb and the readout, and hand [onDrag]/[onRelease] to the `Slider`.
 */
@Stable
internal class LiveSliderState(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    internal var dragging by mutableStateOf(false)
        private set

    /** Replaced on every composition so the latest lambda is always called. */
    internal var commit: (Float) -> Unit = {}

    internal fun adopt(external: Float) {
        if (!dragging) value = external
    }

    fun onDrag(next: Float) {
        dragging = true
        value = next
    }

    fun onRelease() {
        dragging = false
        commit(value)
    }
}

/**
 * A [LiveSliderState] wired to [value] and [onChange]. Writes are throttled to
 * one per [SLIDER_WRITE_INTERVAL_MS] while dragging — enough for anything
 * previewing the setting to keep up, without queueing a preference write (and a
 * recomposition of the whole screen) per touch event — with a final write when
 * the finger lifts. [value] is adopted only while no drag is in progress, so an
 * edit from elsewhere (a reset, another screen showing the same setting) still
 * moves the thumb but the user's own drag is never fought.
 */
@Composable
internal fun rememberLiveSlider(value: Float, onChange: (Float) -> Unit): LiveSliderState {
    val state = remember { LiveSliderState(value) }
    state.commit = onChange
    LaunchedEffect(value) { state.adopt(value) }
    LaunchedEffect(state) {
        snapshotFlow { state.value }
            .conflate()
            .collect {
                if (state.dragging) state.commit(it)
                delay(SLIDER_WRITE_INTERVAL_MS)
            }
    }
    return state
}

/** The gap between a row's icon tile and the words beside it. */
private val RowIconGap = 16.dp

/** How far the tile pushes a row's text in, so a subtitle lines up with the title. */
private val RowIconLane = WmIconTileSize + RowIconGap

/**
 * The frame a row that is not a [WmRow] sits in — a slider, a segmented choice.
 *
 * Those draw their own control under their own name, so they cannot use
 * `ListItem`. The tile goes where `ListItem` would put it and the [header] and
 * [subtitle] indent past it, so a group holding both kinds of row lines its
 * words up down one edge.
 *
 * [content] — the slider, the segmented row — deliberately does *not* indent.
 * It is a control, not a line of text, and 56 dp is a sixth of a phone's width:
 * taking that off a row of segmented buttons is the difference between "After
 * the shortcut key" and "After the".
 */
@Composable
private fun IconedRow(
    icon: ImageVector?,
    subtitle: String? = null,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                WmIconTile(icon, currentRouteAccent())
                Spacer(Modifier.width(RowIconGap))
            }
            header()
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (icon == null) 0.dp else RowIconLane),
            )
        }
        content()
    }
}

/** [SliderSetting] for a row named by a string resource. */
@Composable
internal fun SliderSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    info: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    default: Float? = null,
    onChange: (Float) -> Unit,
) = SliderSetting(
    title = stringResource(title),
    subtitle = subtitle,
    value = value,
    range = range,
    display = display,
    info = info,
    icon = icon,
    highlightKey = title,
    default = default,
    onChange = onChange,
)

/**
 * A labelled slider row. [display] formats the *live* value rather than taking a
 * pre-rendered string, so the readout tracks the thumb instead of the stored
 * setting; see [rememberLiveSlider] for the rest.
 *
 * [default] is the value the slider shipped at, from [SettingsDefaults]. The
 * reset control compares against the *stored* value rather than the live one,
 * so it neither flickers under a drag nor disappears the instant the thumb
 * passes over the default on its way somewhere else.
 */
@Composable
internal fun SliderSetting(
    title: String,
    subtitle: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    info: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    default: Float? = null,
    onChange: (Float) -> Unit,
) {
    val slider = rememberLiveSlider(value, onChange)
    HighlightableRow(title, highlightKey) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                // The name is the weighted half and the readout is not, so a
                // Row measures the readout first and the name wraps around
                // whatever is left. The other way round a long name eats the
                // width and the number it is describing is the part that
                // disappears.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (info != null) InfoButton(title, info)
                }
                Text(
                    display(slider.value),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                ResetSetting(title, default != null && value != default) { onChange(default ?: 0f) }
            },
        ) {
            Slider(
                value = slider.value,
                onValueChange = slider::onDrag,
                onValueChangeFinished = slider::onRelease,
                valueRange = range,
            )
        }
    }
}

/**
 * Restores the toolbar's default pins ([DefaultToolbarTools]) from Settings —
 * the global set. A mode's own pinned toolbar is reset from that mode's
 * editor (Keyboard modes → the mode → turn off "Custom pinned tools").
 * Confirms first, since it discards whatever the user dragged onto the bar.
 */
@Composable
private fun ResetPinnedToolsSetting(repository: SettingsRepository, scope: CoroutineScope) {
    var confirm by remember { mutableStateOf(false) }
    val title = stringResource(R.string.home_reset_pinned_tools_title)
    HighlightableRow(title) {
        WmRow(
            title = title,
            subtitle = stringResource(R.string.home_reset_pinned_tools_subtitle),
            trailing = {
                OutlinedButton(onClick = { confirm = true }) {
                    Text(stringResource(CommonR.string.common_reset))
                }
            },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.home_reset_pinned_tools_confirm_title)) },
            text = { Text(stringResource(R.string.home_reset_pinned_tools_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { repository.setToolbarTools(DefaultToolbarTools) }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * A settings row whose control is a button rather than a value: clear this,
 * reset that, forget the other.
 *
 * [confirm] is the body of a confirmation dialog. Pass it for anything that
 * throws away something the user cannot get back (a word list, a history, a
 * set of pins) and leave it null for anything that only restores a default,
 * which the user can simply set again.
 *
 * [lock] names this button in the fingerprint lock's registry, so the user can
 * choose to put a check in front of it. The check comes *after* the confirm
 * dialog, deliberately: nobody should scan a finger for a dialog they are
 * about to cancel, and the dialog is what says what is about to go away. That
 * makes the fingerprint the last thing before the write, which is where it is
 * worth anything.
 */
@Composable
internal fun ActionRow(
    @StringRes title: Int,
    subtitle: String?,
    action: String,
    confirm: String? = null,
    lock: LockTarget? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    val label = stringResource(title)
    val guarded = rememberLockGuard(lock, onAction)
    HighlightableRow(label, title) {
        WmRow(
            title = label,
            subtitle = subtitle,
            icon = icon,
            trailing = {
                OutlinedButton(
                    enabled = enabled,
                    onClick = { if (confirm == null) guarded() else asking = true },
                ) { Text(action) }
            },
        )
    }
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(label) },
        text = { Text(confirm.orEmpty()) },
        confirmButton = {
            TextButton(onClick = {
                asking = false
                guarded()
            }) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = { asking = false }) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

/**
 * Wraps [onAction] so that it runs only after the user answers the fingerprint
 * prompt, when [lock] is one of the things they chose to protect.
 *
 * Returns [onAction] unchanged when there is nothing to check, so a row with
 * no lock, or a lock the user left unticked, costs one map lookup and no
 * behaviour change at all.
 */
@Composable
internal fun rememberLockGuard(lock: LockTarget?, onAction: () -> Unit): () -> Unit {
    if (lock == null) return onAction
    val appLock = LocalAppLock.current
    // No flow subscription here, unlike the screen gate. That one has to
    // redraw when the answer changes; this one is asked at the moment of the
    // press and reads the current values then, so watching them would only
    // recompose a row whose behaviour has not changed.
    return {
        // Actions never ride the unlock session: [AppLock.isLocked] says so for
        // an ACTION target, and [AppLock.authenticate] neither reads nor opens
        // it. An irreversible write asks every time.
        if (appLock.isLocked(lock)) {
            appLock.authenticate { result -> if (result.succeeded) onAction() }
        } else {
            onAction()
        }
    }
}

/** [ChoiceSetting] for a row named by a string resource. */
@Composable
internal fun <T> ChoiceSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    info: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    icon: ImageVector? = SettingsRowIcons[title],
    default: T? = null,
    onChange: (T) -> Unit,
) = ChoiceSetting(
    title = stringResource(title),
    subtitle = subtitle,
    info = info,
    options = options,
    selected = selected,
    icon = icon,
    highlightKey = title,
    default = default,
    onChange = onChange,
)

/**
 * A titled single-choice row of segmented buttons over [options].
 *
 * [default] is the option the setting shipped on, from [SettingsDefaults]; it
 * gives the row a reset control while some other option is picked. Null on a
 * choice with no fixed default — one over a list the user assembled, or one
 * whose starting option is read off the device.
 */
@Composable
internal fun <T> ChoiceSetting(
    title: String,
    subtitle: String? = null,
    info: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    default: T? = null,
    onChange: (T) -> Unit,
) {
    HighlightableRow(title, highlightKey) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (info != null) InfoButton(title, info)
                Spacer(Modifier.weight(1f))
                // The same `default != null` that draws the control at all is what
                // makes the let non-empty; there is no fallback option to reset to
                // on a row that shipped without a default.
                ResetSetting(title, default != null && selected != default) {
                    default?.let(onChange)
                }
            },
        ) {
            ChoiceControl(options, selected, Modifier.padding(top = 8.dp), onChange)
        }
    }
}

/**
 * Everything a segmented button spends on width that is not its label:
 * `TextButtonContentPadding` (12 dp each side) plus the check-mark lane
 * (`SegmentedButtonDefaults.IconSize` 18 dp + an 8 dp gap). Material reserves
 * that lane whether or not the item is selected — `SegmentedButtonContent`
 * measures `maxOf(IconSize, iconWidth) + IconSpacing` — so it is a fixed 50 dp,
 * and the 2 dp on top is slack for rounding.
 *
 * None of those three is public, hence the copy here. It matters because a
 * segmented row divides its width evenly and then ellipsises whatever does not
 * fit, silently: "After the shortcut key" becomes "After the" and the setting
 * stops saying what it does. [ChoiceControl] measures against this.
 */
private val SegmentFurniture = 52.dp

/**
 * A one-of-N control: segmented buttons when every option's name fits one, a
 * chip row when one of them does not.
 *
 * Segmented buttons are the better control — they read as "one of these" and
 * they are all reachable without scrolling — but only while the words survive.
 * Two or three short options ("On"/"Off", "Tabs"/"Mixed") fit anywhere; "After
 * the shortcut key" does not fit a third of a phone, and no amount of layout
 * tuning makes it. Chips size themselves to their own text, so the fallback
 * shows every name in full.
 *
 * The chips wrap onto a second line rather than scrolling sideways: a settings
 * list has vertical room to spare, and an option parked off the right edge with
 * nothing to say it is there is its own kind of hidden.
 *
 * Every one-of-N row in settings goes through here rather than building its own
 * segmented row, because whether the words fit is not something the author can
 * know: it depends on the screen and on the language, and the failure is silent
 * (Material ellipsises and says nothing).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceControl(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onChange: (T) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = maxWidth
        val fits = remember(options, width, labelStyle, density) {
            val perSegment = with(density) { (width / options.size).toPx() }
            val furniture = with(density) { SegmentFurniture.toPx() }
            options.all { (_, label) ->
                measurer.measure(label, labelStyle, maxLines = 1).size.width + furniture <=
                    perSegment
            }
        }
        if (fits) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (option, label) ->
                    SegmentedButton(
                        selected = selected == option,
                        onClick = { onChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
            return@BoxWithConstraints
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((option, label) in options) {
                FilterChip(
                    selected = selected == option,
                    onClick = { onChange(option) },
                    label = { Text(label, maxLines = 1) },
                    leadingIcon = if (selected != option) null else {
                        {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** One spacebar-swipe slot (quick or hold+swipe): nothing / language / cursor. */
@Composable
private fun SpaceSwipeSetting(
    title: String,
    subtitle: String,
    info: String,
    value: SpaceSwipeAction,
    default: SpaceSwipeAction,
    onChange: (SpaceSwipeAction) -> Unit,
) {
    val nothing = stringResource(R.string.home_space_swipe_none_label)
    val language = stringResource(R.string.home_space_swipe_language_label)
    val cursor = stringResource(R.string.home_space_swipe_cursor_label)
    val numpad = stringResource(R.string.home_space_swipe_numpad_label)
    ChoiceSetting(
        title = title,
        subtitle = subtitle,
        info = info,
        options = SpaceSwipeAction.entries.map { action ->
            action to when (action) {
                SpaceSwipeAction.NONE -> nothing
                SpaceSwipeAction.LANGUAGE -> language
                SpaceSwipeAction.CURSOR -> cursor
                SpaceSwipeAction.NUMPAD -> numpad
            }
        },
        selected = value,
        default = default,
        onChange = onChange,
    )
}

// ---- typing ----

@Composable
private fun TypingSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onOpenDictionary: () -> Unit,
    onOpenCustomDictionaries: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenHardwareShortcuts: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsGroup(stringResource(R.string.typing_group_corrections_title)) {
        item {
            ToggleSetting(
                R.string.typing_autocorrect_title,
                stringResource(R.string.typing_autocorrect_subtitle),
                settings.autocorrect,
                info = stringResource(R.string.typing_autocorrect_info),
                default = SettingsDefaults.autocorrect,
            ) { scope.launch { repository.setAutocorrect(it) } }
        }
        if (settings.autocorrect) {
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_prefix)
                SliderSetting(
                    R.string.typing_autocorrect_confidence_title,
                    subtitle = stringResource(R.string.typing_autocorrect_confidence_subtitle),
                    value = settings.autocorrectConfidence,
                    range = 1.5f..10f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_autocorrect_confidence_info),
                    default = SettingsDefaults.autocorrectConfidence,
                ) { scope.launch { repository.setAutocorrectConfidence(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_adaptive_title,
                    stringResource(R.string.typing_autocorrect_adaptive_subtitle),
                    settings.autocorrectAdaptive,
                    info = stringResource(R.string.typing_autocorrect_adaptive_info),
                    default = SettingsDefaults.autocorrectAdaptive,
                ) { scope.launch { repository.setAutocorrectAdaptive(it) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_timing_signal_title,
                    subtitle = stringResource(R.string.typing_timing_signal_subtitle),
                    value = settings.suggestionStrip.timingSignalStrength,
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).toInt()) },
                    info = stringResource(R.string.typing_timing_signal_info),
                    default = SettingsDefaults.suggestionStrip.timingSignalStrength,
                ) { scope.launch { repository.setTimingSignalStrength(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_undo_autocorrect_title,
                    stringResource(R.string.typing_undo_autocorrect_subtitle),
                    settings.revertAutocorrectOnBackspace,
                    info = stringResource(R.string.typing_undo_autocorrect_info),
                    default = SettingsDefaults.revertAutocorrectOnBackspace,
                ) { scope.launch { repository.setRevertAutocorrectOnBackspace(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_skip_all_caps_title,
                    stringResource(R.string.typing_skip_all_caps_subtitle),
                    settings.autocorrectSkipAllCaps,
                    info = stringResource(R.string.typing_skip_all_caps_info),
                    default = SettingsDefaults.autocorrectSkipAllCaps,
                ) { scope.launch { repository.setAutocorrectSkipAllCaps(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_block_offensive_title,
                    stringResource(R.string.typing_block_offensive_subtitle),
                    settings.suggestionStrip.blockOffensiveWords,
                    info = stringResource(R.string.typing_block_offensive_info),
                    default = SettingsDefaults.suggestionStrip.blockOffensiveWords,
                ) { scope.launch { repository.setBlockOffensiveWords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_context_rerank_title,
                    stringResource(R.string.typing_context_rerank_subtitle),
                    settings.suggestionStrip.contextRerank,
                    info = stringResource(R.string.typing_context_rerank_info),
                    default = SettingsDefaults.suggestionStrip.contextRerank,
                ) { scope.launch { repository.setContextRerank(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_autocorrect_splits_title,
                    stringResource(R.string.typing_autocorrect_splits_subtitle),
                    settings.suggestionStrip.autocorrectSplits,
                    info = stringResource(R.string.typing_autocorrect_splits_info),
                    default = SettingsDefaults.suggestionStrip.autocorrectSplits,
                ) { scope.launch { repository.setAutocorrectSplits(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_language_detection_title,
                    stringResource(R.string.typing_language_detection_subtitle),
                    settings.suggestionStrip.languageDetection,
                    info = stringResource(R.string.typing_language_detection_info),
                    default = SettingsDefaults.suggestionStrip.languageDetection,
                ) { scope.launch { repository.setLanguageDetection(it) } }
            }
            if (settings.suggestionStrip.languageDetection) {
                item {
                    ChoiceSetting(
                        R.string.typing_language_detection_strength_title,
                        info = stringResource(R.string.typing_language_detection_strength_info),
                        options = listOf(
                            LanguageDetectionStrength.GENTLE to
                                stringResource(R.string.typing_language_detection_gentle),
                            LanguageDetectionStrength.BALANCED to
                                stringResource(R.string.typing_language_detection_balanced),
                            LanguageDetectionStrength.AGGRESSIVE to
                                stringResource(R.string.typing_language_detection_aggressive),
                        ),
                        selected = settings.suggestionStrip.languageDetectionStrength,
                        default = SettingsDefaults.suggestionStrip.languageDetectionStrength,
                    ) { scope.launch { repository.setLanguageDetectionStrength(it) } }
                }
            }
            if (settings.numberRow) {
                item {
                    ToggleSetting(
                        R.string.typing_number_row_corrections_title,
                        stringResource(R.string.typing_number_row_corrections_subtitle),
                        settings.suggestionStrip.numberRowCorrections,
                        info = stringResource(R.string.typing_number_row_corrections_info),
                        default = SettingsDefaults.suggestionStrip.numberRowCorrections,
                    ) { scope.launch { repository.setNumberRowCorrections(it) } }
                }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_register_priors_title,
                stringResource(R.string.typing_register_priors_subtitle),
                settings.suggestionStrip.registerPriors,
                info = stringResource(R.string.typing_register_priors_info),
                default = SettingsDefaults.suggestionStrip.registerPriors,
            ) { scope.launch { repository.setRegisterPriors(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_apostrophe_title,
                stringResource(R.string.typing_auto_apostrophe_subtitle),
                settings.autoApostrophe,
                info = stringResource(R.string.typing_auto_apostrophe_info),
                default = SettingsDefaults.autoApostrophe,
            ) { scope.launch { repository.setAutoApostrophe(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_capitalize_title,
                stringResource(R.string.typing_auto_capitalize_subtitle),
                settings.autoCapitalize,
                info = stringResource(R.string.typing_auto_capitalize_info),
                default = SettingsDefaults.autoCapitalize,
            ) { scope.launch { repository.setAutoCapitalize(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_double_space_period_title,
                stringResource(R.string.typing_double_space_period_subtitle),
                settings.doubleSpacePeriod,
                info = stringResource(R.string.typing_double_space_period_info),
                default = SettingsDefaults.doubleSpacePeriod,
            ) { scope.launch { repository.setDoubleSpacePeriod(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_double_space_tab_title,
                stringResource(R.string.typing_double_space_tab_subtitle),
                settings.doubleSpaceTab,
                info = stringResource(R.string.typing_double_space_tab_info),
                default = SettingsDefaults.doubleSpaceTab,
            ) { scope.launch { repository.setDoubleSpaceTab(it) } }
        }
        if (settings.doubleSpacePeriod || settings.doubleSpaceTab) {
            item {
                SliderSetting(
                    R.string.typing_double_space_window_title,
                    subtitle = stringResource(R.string.typing_double_space_window_subtitle),
                    value = settings.textEditing.doubleSpaceWindowMs.toFloat(),
                    range = 200f..800f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.typing_double_space_window_info),
                    default = SettingsDefaults.textEditing.doubleSpaceWindowMs.toFloat(),
                ) { scope.launch { repository.setDoubleSpaceWindowMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_auto_space_punctuation_title,
                stringResource(R.string.typing_auto_space_punctuation_subtitle),
                settings.autoSpaceAfterPunctuation,
                info = stringResource(R.string.typing_auto_space_punctuation_info),
                default = SettingsDefaults.autoSpaceAfterPunctuation,
            ) { scope.launch { repository.setAutoSpaceAfterPunctuation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_space_after_suggestion_title,
                stringResource(R.string.typing_space_after_suggestion_subtitle),
                settings.suggestionStrip.autoSpaceAfterSuggestion,
                info = stringResource(R.string.typing_space_after_suggestion_info),
                default = SettingsDefaults.suggestionStrip.autoSpaceAfterSuggestion,
            ) { scope.launch { repository.setAutoSpaceAfterSuggestion(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_wrap_selection_title,
                stringResource(R.string.typing_wrap_selection_subtitle),
                settings.textEditing.wrapSelectionWithPair,
                info = stringResource(R.string.typing_wrap_selection_info),
                default = SettingsDefaults.textEditing.wrapSelectionWithPair,
            ) { scope.launch { repository.setWrapSelectionWithPair(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_shift_recase_title,
                stringResource(R.string.typing_shift_recase_subtitle),
                settings.textEditing.recapitalizeSelectionWithShift,
                info = stringResource(R.string.typing_shift_recase_info),
                default = SettingsDefaults.textEditing.recapitalizeSelectionWithShift,
            ) { scope.launch { repository.setRecapitalizeSelectionWithShift(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_suggestions_title)) {
        item {
            ToggleSetting(
                R.string.typing_suggestions_title,
                stringResource(R.string.typing_suggestions_subtitle),
                settings.suggestions,
                info = stringResource(R.string.typing_suggestions_info),
                default = SettingsDefaults.suggestions,
            ) { scope.launch { repository.setSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_punctuation_suggestions_title,
                stringResource(R.string.typing_punctuation_suggestions_subtitle),
                settings.suggestionStrip.punctuation,
                info = stringResource(R.string.typing_punctuation_suggestions_info),
                default = SettingsDefaults.suggestionStrip.punctuation,
            ) { scope.launch { repository.setPunctuationSuggestions(it) } }
        }
        if (settings.suggestionStrip.punctuation) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.typing_punctuation_marks_title),
                    value = settings.suggestionStrip.punctuationChips,
                    hint = stringResource(R.string.typing_punctuation_marks_hint),
                    default = SettingsDefaults.suggestionStrip.punctuationChips,
                ) { repository.setPunctuationChips(it) }
            }
        }
        item {
            SliderSetting(
                R.string.typing_suggestion_slots_title,
                subtitle = stringResource(R.string.typing_suggestion_slots_subtitle),
                value = settings.suggestionStrip.slotCount.toFloat(),
                range = 2f..6f,
                display = { it.toInt().toString() },
                info = stringResource(R.string.typing_suggestion_slots_info),
                default = SettingsDefaults.suggestionStrip.slotCount.toFloat(),
            ) { scope.launch { repository.setSuggestionSlotCount(it.toInt()) } }
        }
        item {
            val once = stringResource(R.string.typing_learn_threshold_once)
            SliderSetting(
                R.string.typing_learn_threshold_title,
                subtitle = stringResource(R.string.typing_learn_threshold_subtitle),
                value = settings.suggestionStrip.learnedWordMinCount.toFloat(),
                range = 1f..5f,
                display = { if (it.toInt() <= 1) once else it.toInt().toString() },
                info = stringResource(R.string.typing_learn_threshold_info),
                default = SettingsDefaults.suggestionStrip.learnedWordMinCount.toFloat(),
            ) { scope.launch { repository.setLearnedWordMinCount(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.typing_offer_near_miss_title,
                stringResource(R.string.typing_offer_near_miss_subtitle),
                settings.suggestionStrip.offerNearMissCorrections,
                info = stringResource(R.string.typing_offer_near_miss_info),
                default = SettingsDefaults.suggestionStrip.offerNearMissCorrections,
            ) { scope.launch { repository.setOfferNearMissCorrections(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_ask_before_learning_title,
                stringResource(R.string.typing_ask_before_learning_subtitle),
                settings.suggestionStrip.askBeforeLearning,
                info = stringResource(R.string.typing_ask_before_learning_info),
                default = SettingsDefaults.suggestionStrip.askBeforeLearning,
            ) { scope.launch { repository.setAskBeforeLearning(it) } }
        }
        if (!settings.suggestionStrip.askBeforeLearning) {
            item {
                val immediately = stringResource(R.string.typing_new_word_sightings_once)
                SliderSetting(
                    R.string.typing_new_word_sightings_title,
                    subtitle = stringResource(R.string.typing_new_word_sightings_subtitle),
                    value = settings.suggestionStrip.newWordSightings.toFloat(),
                    range = 1f..10f,
                    display = { if (it.toInt() <= 1) immediately else it.toInt().toString() },
                    info = stringResource(R.string.typing_new_word_sightings_info),
                    default = SettingsDefaults.suggestionStrip.newWordSightings.toFloat(),
                ) { scope.launch { repository.setNewWordSightings(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestions_all_fields_title,
                stringResource(R.string.typing_suggestions_all_fields_subtitle),
                settings.showSuggestionsInAllFields,
                info = stringResource(R.string.typing_suggestions_all_fields_info),
                default = SettingsDefaults.showSuggestionsInAllFields,
            ) { scope.launch { repository.setShowSuggestionsInAllFields(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_suggestions_first_title,
                stringResource(R.string.typing_suggestions_first_subtitle),
                settings.suggestionStrip.suggestionsFirst,
                info = stringResource(R.string.typing_suggestions_first_info),
                default = SettingsDefaults.suggestionStrip.suggestionsFirst,
            ) { scope.launch { repository.setSuggestionsFirst(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_primary_center_title,
                stringResource(R.string.typing_primary_center_subtitle),
                settings.suggestionStrip.suggestionPrimaryCenter,
                info = stringResource(R.string.typing_primary_center_info),
                default = SettingsDefaults.suggestionStrip.suggestionPrimaryCenter,
            ) { scope.launch { repository.setSuggestionPrimaryCenter(it) } }
        }
        item {
            val permissionContext = LocalContext.current
            // Prominent disclosure before the system prompt, never the prompt on
            // its own: see PermissionDisclosure.
            val contactsPermission =
                rememberDisclosedPermissionRequest(PermissionDisclosures.CONTACT_NAMES) {
                    scope.launch { repository.setContactSuggestions(true) }
                }
            ToggleSetting(
                R.string.typing_contact_names_title,
                stringResource(R.string.typing_contact_names_subtitle),
                settings.contactSuggestions,
                info = stringResource(R.string.typing_contact_names_info),
                default = SettingsDefaults.contactSuggestions,
            ) { enabled ->
                when {
                    !enabled -> scope.launch { repository.setContactSuggestions(false) }
                    permissionContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED ->
                        scope.launch { repository.setContactSuggestions(true) }
                    else -> contactsPermission()
                }
            }
        }
        item {
            val permissionContext = LocalContext.current
            val emailPermission =
                rememberDisclosedPermissionRequest(PermissionDisclosures.CONTACT_EMAILS) {
                    scope.launch { repository.setContactEmailSuggestions(true) }
                }
            ToggleSetting(
                R.string.typing_contact_emails_title,
                stringResource(R.string.typing_contact_emails_subtitle),
                settings.contactEmailSuggestions,
                info = stringResource(R.string.typing_contact_emails_info),
                default = SettingsDefaults.contactEmailSuggestions,
            ) { enabled ->
                when {
                    !enabled -> scope.launch { repository.setContactEmailSuggestions(false) }
                    permissionContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED ->
                        scope.launch { repository.setContactEmailSuggestions(true) }
                    else -> emailPermission()
                }
            }
        }
        if (settings.contactEmailSuggestions) {
            item {
                ToggleSetting(
                    R.string.typing_contact_emails_in_email_fields_title,
                    stringResource(R.string.typing_contact_emails_in_email_fields_subtitle),
                    settings.contactEmailSuggestionsInEmailFields,
                    info = stringResource(R.string.typing_contact_emails_in_email_fields_info),
                    default = SettingsDefaults.contactEmailSuggestionsInEmailFields,
                ) { scope.launch { repository.setContactEmailSuggestionsInEmailFields(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_app_names_title,
                stringResource(R.string.typing_app_names_subtitle),
                settings.appNameSuggestions,
                info = stringResource(R.string.typing_app_names_info),
                default = SettingsDefaults.appNameSuggestions,
            ) { scope.launch { repository.setAppNameSuggestions(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_inline_emoji_search_title,
                stringResource(R.string.typing_inline_emoji_search_subtitle),
                settings.inlineEmojiSearch,
                info = stringResource(R.string.typing_inline_emoji_search_info),
                default = SettingsDefaults.inlineEmojiSearch,
            ) { scope.launch { repository.setInlineEmojiSearch(it) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item {
                ToggleSetting(
                    R.string.typing_inline_autofill_title,
                    stringResource(R.string.typing_inline_autofill_subtitle),
                    settings.inlineAutofill,
                    info = stringResource(R.string.typing_inline_autofill_info),
                    default = SettingsDefaults.inlineAutofill,
                ) { scope.launch { repository.setInlineAutofill(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_replies_title,
                    stringResource(R.string.typing_smart_replies_subtitle),
                    settings.suggestionStrip.systemSmartReplies,
                    info = stringResource(R.string.typing_smart_replies_info),
                    default = SettingsDefaults.suggestionStrip.systemSmartReplies,
                ) { scope.launch { repository.setSystemSmartReplies(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_smart_hit_detection_title,
                stringResource(R.string.typing_smart_hit_detection_subtitle),
                settings.layoutBehavior.smartHitDetection,
                info = stringResource(R.string.typing_smart_hit_detection_info),
                default = SettingsDefaults.layoutBehavior.smartHitDetection,
            ) { scope.launch { repository.setSmartHitDetection(it) } }
        }
        item {
            NavRow(
                R.string.typing_personal_dictionary_title,
                stringResource(R.string.typing_personal_dictionary_subtitle),
                route = "dictionary",
                onClick = onOpenDictionary,
            )
        }
        item {
            NavRow(
                R.string.typing_custom_dictionaries_title,
                stringResource(R.string.typing_custom_dictionaries_subtitle),
                route = "customdictionaries",
                onClick = onOpenCustomDictionaries,
            )
        }
        item {
            val count = settings.suggestionBlacklist.size
            NavRow(
                R.string.typing_blacklist_title,
                if (count == 0) {
                    stringResource(R.string.typing_blacklist_subtitle)
                } else {
                    pluralStringResource(R.plurals.typing_blacklist_count_subtitle, count, count)
                },
                route = "blacklist",
                onClick = onOpenBlacklist,
            )
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_smart_chips_title)) {
        item {
            ToggleSetting(
                R.string.typing_smart_chips_title,
                stringResource(R.string.typing_smart_chips_subtitle),
                settings.smartSuggestions,
                info = stringResource(R.string.typing_smart_chips_info),
                default = SettingsDefaults.smartSuggestions,
            ) { scope.launch { repository.setSmartSuggestions(it) } }
        }
        if (settings.smartSuggestions) {
            item {
                ToggleSetting(
                    R.string.typing_smart_calc_title,
                    stringResource(R.string.typing_smart_calc_subtitle),
                    settings.smartCalc,
                    default = SettingsDefaults.smartCalc,
                ) { scope.launch { repository.setSmartCalc(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_currency_title,
                    stringResource(R.string.typing_smart_currency_subtitle, settings.currencyTo),
                    settings.smartCurrency,
                    default = SettingsDefaults.smartCurrency,
                ) { scope.launch { repository.setSmartCurrency(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_units_title,
                    stringResource(R.string.typing_smart_units_subtitle),
                    settings.smartUnits,
                    default = SettingsDefaults.smartUnits,
                ) { scope.launch { repository.setSmartUnits(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_tool_keywords_title,
                    stringResource(R.string.typing_smart_tool_keywords_subtitle),
                    settings.smartToolKeywords,
                    info = stringResource(R.string.typing_smart_tool_keywords_info),
                    default = SettingsDefaults.smartToolKeywords,
                ) { scope.launch { repository.setSmartToolKeywords(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_dates_title,
                    stringResource(R.string.typing_smart_dates_subtitle),
                    settings.smartChips.dates,
                    default = SettingsDefaults.smartChips.dates,
                ) { scope.launch { repository.setSmartChipDates(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_weather_title,
                    stringResource(R.string.typing_smart_weather_subtitle),
                    settings.smartChips.weather,
                    default = SettingsDefaults.smartChips.weather,
                ) { scope.launch { repository.setSmartChipWeather(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_lookups_title,
                    stringResource(R.string.typing_smart_lookups_subtitle),
                    settings.smartChips.lookups,
                    default = SettingsDefaults.smartChips.lookups,
                ) { scope.launch { repository.setSmartChipLookups(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_intents_title,
                    stringResource(R.string.typing_smart_intents_subtitle),
                    settings.smartChips.intents,
                    default = SettingsDefaults.smartChips.intents,
                ) { scope.launch { repository.setSmartChipIntents(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_smart_gifs_title,
                    stringResource(R.string.typing_smart_gifs_subtitle),
                    settings.smartChips.gifs,
                    default = SettingsDefaults.smartChips.gifs,
                ) { scope.launch { repository.setSmartChipGifs(it) } }
            }
        }
    }

    val notificationCodesGranted = rememberGrantState(::hasNotificationAccess)
    val minutesFormat = stringResource(R.string.values_minutes)
    SettingsGroup(stringResource(R.string.typing_group_otp_title)) {
        item {
            val accessContext = LocalContext.current
            val codesAccess = rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
            ToggleSetting(
                R.string.typing_otp_chip_title,
                stringResource(R.string.typing_otp_chip_subtitle),
                settings.otp.enabled,
                info = stringResource(R.string.typing_otp_chip_info),
                default = SettingsDefaults.otp.enabled,
            ) { on ->
                scope.launch { repository.setOtpChipEnabled(on) }
                // Disclosure then the grant screen, the first time it goes on —
                // but not when access is already there, which is the common
                // case for a toggle flipped off and on again.
                if (on && !hasNotificationAccess(accessContext)) codesAccess()
            }
        }
        if (settings.otp.enabled) {
            if (!notificationCodesGranted) {
                item {
                    val codesAccessRow =
                        rememberDisclosedSpecialAccess(SpecialAccess.NOTIFICATION_CODES)
                    NavRow(
                        R.string.typing_otp_access_title,
                        stringResource(R.string.typing_otp_access_subtitle),
                    ) { codesAccessRow() }
                }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_number_fields_title,
                    stringResource(R.string.typing_otp_number_fields_subtitle),
                    settings.otp.numberFieldsOnly,
                    info = stringResource(R.string.typing_otp_number_fields_info),
                    default = SettingsDefaults.otp.numberFieldsOnly,
                ) { scope.launch { repository.setOtpNumberFieldsOnly(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_otp_expiry_title,
                    subtitle = stringResource(R.string.typing_otp_expiry_subtitle),
                    value = settings.otp.expiryMinutes.toFloat(),
                    range = 1f..10f,
                    display = { minutesFormat.format(it.toInt()) },
                    default = SettingsDefaults.otp.expiryMinutes.toFloat(),
                ) { scope.launch { repository.setOtpExpiryMinutes(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_otp_dismiss_title,
                    stringResource(R.string.typing_otp_dismiss_subtitle),
                    settings.otp.dismissNotification,
                    info = stringResource(R.string.typing_otp_dismiss_info),
                    default = SettingsDefaults.otp.dismissNotification,
                ) { scope.launch { repository.setOtpDismissNotification(it) } }
            }
        }
        // Outside the enabled block on purpose: this governs how *every* code
        // is typed, including one pasted from the clipboard, which needs no
        // notification access at all.
        item {
            ToggleSetting(
                R.string.typing_otp_per_digit_title,
                stringResource(R.string.typing_otp_per_digit_subtitle),
                settings.otp.perDigitEntry,
                info = stringResource(R.string.typing_otp_per_digit_info),
                default = SettingsDefaults.otp.perDigitEntry,
            ) { scope.launch { repository.setOtpPerDigitEntry(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_gestures_title)) {
        item {
            ToggleSetting(
                R.string.typing_glide_typing_title,
                stringResource(R.string.typing_glide_typing_subtitle),
                settings.gestureTyping,
                info = stringResource(R.string.typing_glide_typing_info),
                default = SettingsDefaults.gestureTyping,
            ) { scope.launch { repository.setGestureTyping(it) } }
        }
        // What a letter swipe does — glide a word or handwrite it. Full builds
        // only (needs the ML Kit handwriting model), and only relevant once
        // letter swipes are switched on above.
        if (BuildConfig.ENABLE_ML_KIT_HANDWRITING && settings.gestureTyping) {
            item {
                ChoiceSetting(
                    title = R.string.typing_letter_swipe_action_title,
                    subtitle = stringResource(R.string.typing_letter_swipe_action_subtitle),
                    info = stringResource(R.string.typing_letter_swipe_action_info),
                    options = listOf(
                        LetterSwipeAction.TYPE_WORDS to
                            stringResource(R.string.typing_letter_swipe_type_words_label),
                        LetterSwipeAction.HANDWRITE to
                            stringResource(R.string.typing_letter_swipe_handwrite_label),
                    ),
                    selected = settings.letterSwipeAction,
                    onChange = { scope.launch { repository.setLetterSwipeAction(it) } },
                    default = SettingsDefaults.letterSwipeAction,
                )
            }
        }
        if (settings.gestureTyping) {
            // Glide-word only: crossing the spacebar to chain words has no
            // meaning when a swipe draws handwriting instead.
            if (settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    ToggleSetting(
                        R.string.typing_space_glide_multiword_title,
                        stringResource(R.string.typing_space_glide_multiword_subtitle),
                        settings.gesture.spaceGlideMultiWord,
                        info = stringResource(R.string.typing_space_glide_multiword_info),
                        default = SettingsDefaults.gesture.spaceGlideMultiWord,
                    ) { scope.launch { repository.setGestureSpaceMultiWord(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.typing_glide_picker_title,
                        stringResource(R.string.typing_glide_picker_subtitle),
                        settings.gesture.ambiguityPicker,
                        info = stringResource(R.string.typing_glide_picker_info),
                        default = SettingsDefaults.gesture.ambiguityPicker,
                    ) { scope.launch { repository.setGestureAmbiguityPicker(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.typing_space_after_glide_title,
                        stringResource(R.string.typing_space_after_glide_subtitle),
                        settings.gesture.autoSpaceAfterGlide,
                        info = stringResource(R.string.typing_space_after_glide_info),
                        default = SettingsDefaults.gesture.autoSpaceAfterGlide,
                    ) { scope.launch { repository.setGestureAutoSpace(it) } }
                }
                // Which key a glide reads as an apostrophe, so "it's" can be
                // drawn rather than guessed at. One key, never several.
                item {
                    ChoiceSetting(
                        title = R.string.typing_glide_apostrophe_title,
                        subtitle = stringResource(R.string.typing_glide_apostrophe_subtitle),
                        info = stringResource(R.string.typing_glide_apostrophe_info),
                        options = listOf(
                            GlideApostropheKey.OFF to
                                stringResource(R.string.typing_glide_apostrophe_off_label),
                            GlideApostropheKey.COMMA to
                                stringResource(R.string.typing_glide_apostrophe_comma_label),
                            GlideApostropheKey.PERIOD to
                                stringResource(R.string.typing_glide_apostrophe_period_label),
                            GlideApostropheKey.SPACE to
                                stringResource(R.string.typing_glide_apostrophe_space_label),
                            GlideApostropheKey.APOSTROPHE to
                                stringResource(R.string.typing_glide_apostrophe_key_label),
                        ),
                        selected = settings.gesture.apostropheKey,
                        onChange = { scope.launch { repository.setGestureApostropheKey(it) } },
                        default = SettingsDefaults.gesture.apostropheKey,
                    )
                }
                // The possessive flick hangs off that key, and the spacebar
                // cannot be its starting point, so the row appears only for the
                // three choices it can actually work from.
                if (settings.gesture.apostropheKey != GlideApostropheKey.OFF &&
                    settings.gesture.apostropheKey != GlideApostropheKey.SPACE
                ) {
                    item {
                        ToggleSetting(
                            R.string.typing_glide_apostrophe_s_title,
                            stringResource(R.string.typing_glide_apostrophe_s_subtitle),
                            settings.gesture.apostropheS,
                            info = stringResource(R.string.typing_glide_apostrophe_s_info),
                            default = SettingsDefaults.gesture.apostropheS,
                        ) { scope.launch { repository.setGestureApostropheS(it) } }
                    }
                }
            }
            item {
                val valueFormat = stringResource(R.string.typing_value_multiplier_suffix)
                SliderSetting(
                    R.string.typing_swipe_start_distance_title,
                    subtitle = stringResource(R.string.typing_swipe_start_distance_subtitle),
                    value = settings.gesture.startThresholdSlop,
                    range = 0.5f..4f,
                    display = { valueFormat.format("%.1f".format(it)) },
                    info = stringResource(R.string.typing_swipe_start_distance_info),
                    default = SettingsDefaults.gesture.startThresholdSlop,
                ) { scope.launch { repository.setGestureStartThresholdSlop(it) } }
            }
            // Glide-word only: the guard raises the swipe-start bar, which never
            // runs in handwrite mode (there is no word glide to suppress).
            if (settings.letterSwipeAction == LetterSwipeAction.TYPE_WORDS) {
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val msFormat = stringResource(R.string.typing_value_milliseconds)
                    SliderSetting(
                        R.string.typing_gesture_cooldown_title,
                        subtitle = stringResource(R.string.typing_gesture_cooldown_subtitle),
                        value = settings.gesture.postTypeCooldownMs.toFloat(),
                        range = 0f..500f,
                        display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_gesture_cooldown_info),
                        default = SettingsDefaults.gesture.postTypeCooldownMs.toFloat(),
                    ) { scope.launch { repository.setGesturePostTypeCooldownMs(it.roundToInt()) } }
                }
            }
            // Handwrite-with-swipes only: window after a drawn stroke in which a
            // tap is grabbed as an ink dot rather than typing.
            if (BuildConfig.ENABLE_ML_KIT_HANDWRITING &&
                settings.letterSwipeAction == LetterSwipeAction.HANDWRITE
            ) {
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val msFormat = stringResource(R.string.typing_value_milliseconds)
                    SliderSetting(
                        R.string.typing_handwrite_dot_title,
                        subtitle = stringResource(R.string.typing_handwrite_dot_subtitle),
                        value = settings.gesture.handwriteDotCooldownMs.toFloat(),
                        range = 0f..1500f,
                        display = { if (it.roundToInt() == 0) offLabel else msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.typing_handwrite_dot_info),
                        default = SettingsDefaults.gesture.handwriteDotCooldownMs.toFloat(),
                    ) { scope.launch { repository.setGestureHandwriteDotCooldownMs(it.roundToInt()) } }
                }
            }
            item {
                val dpFormat = stringResource(R.string.typing_value_dp)
                SliderSetting(
                    R.string.typing_trail_width_title,
                    subtitle = stringResource(R.string.typing_trail_width_subtitle),
                    value = settings.gesture.trailWidthDp,
                    range = 2f..24f,
                    display = { dpFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.gesture.trailWidthDp,
                ) { scope.launch { repository.setGestureTrailWidthDp(it) } }
            }
            item {
                val msFormat = stringResource(R.string.typing_value_milliseconds)
                SliderSetting(
                    R.string.typing_trail_length_title,
                    subtitle = stringResource(R.string.typing_trail_length_subtitle),
                    value = settings.gesture.trailDurationMs.toFloat(),
                    range = 100f..1200f,
                    display = { msFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.gesture.trailDurationMs.toFloat(),
                ) { scope.launch { repository.setGestureTrailDurationMs(it.roundToInt()) } }
            }
            item {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    R.string.typing_trail_opacity_title,
                    value = settings.gesture.trailOpacity,
                    // Down to zero, which is the only way to glide with no
                    // trail at all. It used to floor at 0.1, so the one way to
                    // turn the trail off was power saving mode, which changes
                    // a dozen other things with it.
                    range = 0f..1f,
                    display = { percentFormat.format((it * 100).roundToInt()) },
                    default = SettingsDefaults.gesture.trailOpacity,
                ) { scope.launch { repository.setGestureTrailOpacity(it) } }
            }
        }
        item {
            SpaceSwipeSetting(
                title = stringResource(R.string.typing_space_short_swipe_title),
                subtitle = stringResource(R.string.typing_space_short_swipe_subtitle),
                info = stringResource(R.string.typing_space_short_swipe_info),
                value = settings.spaceShortSwipe,
                default = SettingsDefaults.spaceShortSwipe,
            ) { scope.launch { repository.setSpaceShortSwipe(it) } }
        }
        item {
            SpaceSwipeSetting(
                title = stringResource(R.string.typing_space_long_swipe_title),
                subtitle = stringResource(R.string.typing_space_long_swipe_subtitle),
                info = stringResource(R.string.typing_space_long_swipe_info),
                value = settings.spaceLongSwipe,
                default = SettingsDefaults.spaceLongSwipe,
            ) { scope.launch { repository.setSpaceLongSwipe(it) } }
        }
        // 2-D cursor pad only makes sense once a slide is set to cursor control.
        if (settings.spaceShortSwipe == SpaceSwipeAction.CURSOR ||
            settings.spaceLongSwipe == SpaceSwipeAction.CURSOR
        ) {
            item {
                ToggleSetting(
                    R.string.typing_space_cursor_2d_title,
                    stringResource(R.string.typing_space_cursor_2d_subtitle),
                    settings.layoutBehavior.spaceCursor2d,
                    info = stringResource(R.string.typing_space_cursor_2d_info),
                    default = SettingsDefaults.layoutBehavior.spaceCursor2d,
                ) { scope.launch { repository.setSpaceCursor2d(it) } }
            }
            item {
                SliderSetting(
                    R.string.typing_space_cursor_step_title,
                    subtitle = stringResource(R.string.typing_space_cursor_step_subtitle),
                    value = settings.textEditing.spaceCursorStepDp.toFloat(),
                    range = 8f..32f,
                    display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                    info = stringResource(R.string.typing_space_cursor_step_info),
                    default = SettingsDefaults.textEditing.spaceCursorStepDp.toFloat(),
                ) { scope.launch { repository.setSpaceCursorStepDp(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_space_swipe_down_hide_title,
                stringResource(R.string.typing_space_swipe_down_hide_subtitle),
                settings.layoutBehavior.spaceSwipeDownHide,
                info = stringResource(R.string.typing_space_swipe_down_hide_info),
                default = SettingsDefaults.layoutBehavior.spaceSwipeDownHide,
            ) { scope.launch { repository.setSpaceSwipeDownHide(it) } }
        }
        if (settings.spaceShortSwipe == SpaceSwipeAction.LANGUAGE ||
            settings.spaceLongSwipe == SpaceSwipeAction.LANGUAGE
        ) {
            item {
                ToggleSetting(
                    R.string.typing_spacebar_language_arrows_title,
                    stringResource(R.string.typing_spacebar_language_arrows_subtitle),
                    settings.spacebarLanguageArrows,
                    info = stringResource(R.string.typing_spacebar_language_arrows_info),
                    default = SettingsDefaults.spacebarLanguageArrows,
                ) { scope.launch { repository.setSpacebarLanguageArrows(it) } }
            }
        }
        item {
            ChoiceSetting(
                R.string.typing_spacebar_display_title,
                subtitle = stringResource(R.string.typing_spacebar_display_subtitle),
                info = stringResource(R.string.typing_spacebar_display_info),
                options = listOf(
                    SpacebarDisplay.LANGUAGE to
                        stringResource(R.string.typing_spacebar_display_language_label),
                    SpacebarDisplay.LAYOUT to
                        stringResource(R.string.typing_spacebar_display_layout_label),
                    SpacebarDisplay.BOTH to
                        stringResource(R.string.typing_spacebar_display_both_label),
                ),
                selected = settings.layoutBehavior.spacebarDisplay,
                default = SettingsDefaults.layoutBehavior.spacebarDisplay,
            ) { scope.launch { repository.setSpacebarDisplay(it) } }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.typing_spacebar_text_label),
                value = settings.spacebarLabel,
                // The %s token is text the user types, so it travels as an argument.
                hint = stringResource(R.string.typing_spacebar_text_hint, "%s"),
                default = SettingsDefaults.spacebarLabel,
            ) { repository.setSpacebarLabel(it) }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_backspace_title)) {
        item {
            ToggleSetting(
                R.string.typing_backspace_swipe_title,
                stringResource(R.string.typing_backspace_swipe_subtitle),
                settings.backspaceSwipeDelete,
                info = stringResource(R.string.typing_backspace_swipe_info),
                default = SettingsDefaults.backspaceSwipeDelete,
            ) { scope.launch { repository.setBackspaceSwipeDelete(it) } }
        }
        if (settings.backspaceSwipeDelete) {
            item {
                SliderSetting(
                    R.string.typing_backspace_step_title,
                    subtitle = stringResource(R.string.typing_backspace_step_subtitle),
                    value = settings.textEditing.backspaceWordStepDp.toFloat(),
                    range = 32f..120f,
                    display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                    info = stringResource(R.string.typing_backspace_step_info),
                    default = SettingsDefaults.textEditing.backspaceWordStepDp.toFloat(),
                ) { scope.launch { repository.setBackspaceWordStepDp(it.toInt()) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_enter_title)) {
        item {
            ToggleSetting(
                R.string.typing_shift_enter_title,
                stringResource(R.string.typing_shift_enter_subtitle),
                settings.layoutBehavior.shiftEnterNewline,
                info = stringResource(R.string.typing_shift_enter_info),
                default = SettingsDefaults.layoutBehavior.shiftEnterNewline,
            ) { scope.launch { repository.setShiftEnterNewline(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_volume_title)) {
        item {
            ToggleSetting(
                R.string.typing_volume_cursor_title,
                stringResource(R.string.typing_volume_cursor_subtitle),
                settings.volumeCursor,
                info = stringResource(R.string.typing_volume_cursor_info),
                default = SettingsDefaults.volumeCursor,
            ) { scope.launch { repository.setVolumeCursor(it) } }
        }
        if (settings.volumeCursor) {
            item {
                ToggleSetting(
                    R.string.typing_volume_cursor_media_title,
                    stringResource(R.string.typing_volume_cursor_media_subtitle),
                    settings.volumeCursorMediaAware,
                    info = stringResource(R.string.typing_volume_cursor_media_info),
                    default = SettingsDefaults.volumeCursorMediaAware,
                ) { scope.launch { repository.setVolumeCursorMediaAware(it) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.typing_group_hardware_title)) {
        item {
            ToggleSetting(
                R.string.typing_hardware_input_title,
                stringResource(R.string.typing_hardware_input_subtitle),
                settings.hardwareKeyboardInput,
                info = stringResource(R.string.typing_hardware_input_info),
                default = SettingsDefaults.hardwareKeyboardInput,
            ) { scope.launch { repository.setHardwareKeyboardInput(it) } }
        }
        val hw = settings.hardwareKeyboard
        item {
            ToggleSetting(
                R.string.typing_hw_shortcuts_title,
                stringResource(R.string.typing_hw_shortcuts_subtitle),
                hw.shortcutsEnabled,
                info = stringResource(R.string.typing_hw_shortcuts_info),
                default = SettingsDefaults.hardwareKeyboard.shortcutsEnabled,
            ) { scope.launch { repository.setHwShortcutsEnabled(it) } }
        }
        if (hw.shortcutsEnabled) {
            item {
                // A chord spells itself, so it arrives with no template around it.
                val leaderParts = leaderLabel(parseLeader(hw.leader) ?: DefaultLeader)
                val leaderText = if (leaderParts.templateRes == 0) {
                    leaderParts.text
                } else {
                    stringResource(leaderParts.templateRes, leaderParts.text)
                }
                NavRow(
                    R.string.typing_hw_shortcuts_list_title,
                    stringResource(R.string.typing_hw_shortcuts_list_subtitle),
                    value = leaderText,
                    route = "hwshortcuts",
                    onClick = onOpenHardwareShortcuts,
                )
            }
            item {
                ToggleSetting(
                    R.string.typing_hw_digit_chord_title,
                    stringResource(R.string.typing_hw_digit_chord_subtitle),
                    hw.toolbarDigitChord,
                    info = stringResource(R.string.typing_hw_digit_chord_info),
                    default = SettingsDefaults.hardwareKeyboard.toolbarDigitChord,
                ) { scope.launch { repository.setHwToolbarDigitChord(it) } }
            }
            item {
                ToggleSetting(
                    R.string.typing_hw_modifier_words_title,
                    stringResource(R.string.typing_hw_modifier_words_subtitle),
                    hw.hintModifierWords,
                    info = stringResource(R.string.typing_hw_modifier_words_info),
                    default = SettingsDefaults.hardwareKeyboard.hintModifierWords,
                ) { scope.launch { repository.setHwHintModifierWords(it) } }
            }
            item {
                // The readout tracks the live thumb, so its format string is
                // resolved out here: the display lambda is not composable.
                val secondsFormat = stringResource(R.string.typing_hw_picker_timeout_value)
                SliderSetting(
                    R.string.typing_hw_picker_timeout_title,
                    subtitle = stringResource(R.string.typing_hw_picker_timeout_subtitle),
                    value = hw.pickerTimeoutMs.toFloat(),
                    range = PickerTimeoutRange.first.toFloat()..PickerTimeoutRange.last.toFloat(),
                    display = { secondsFormat.format("%.1f".format(it / 1000f)) },
                    info = stringResource(R.string.typing_hw_picker_timeout_info),
                    default = SettingsDefaults.hardwareKeyboard.pickerTimeoutMs.toFloat(),
                ) { scope.launch { repository.setHwPickerTimeoutMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_panel_nav_title,
                stringResource(R.string.typing_hw_panel_nav_subtitle),
                hw.panelNavigation,
                info = stringResource(R.string.typing_hw_panel_nav_info),
                default = SettingsDefaults.hardwareKeyboard.panelNavigation,
            ) { scope.launch { repository.setHwPanelNavigation(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_esc_title,
                stringResource(R.string.typing_hw_esc_subtitle),
                hw.escClosesPanel,
                info = stringResource(R.string.typing_hw_esc_info),
                default = SettingsDefaults.hardwareKeyboard.escClosesPanel,
            ) { scope.launch { repository.setHwEscClosesPanel(it) } }
        }
        item {
            ChoiceSetting(
                R.string.typing_hw_suggestion_hotkeys_title,
                subtitle = stringResource(R.string.typing_hw_suggestion_hotkeys_subtitle),
                info = stringResource(R.string.typing_hw_suggestion_hotkeys_info),
                options = SuggestionHotkeyMode.entries.map { it to stringResource(it.labelRes) },
                selected = hw.suggestionHotkeys,
                default = SettingsDefaults.hardwareKeyboard.suggestionHotkeys,
            ) { scope.launch { repository.setHwSuggestionHotkeys(it) } }
        }
        if (hw.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT) {
            item {
                ToggleSetting(
                    R.string.typing_hw_suggestion_hints_title,
                    stringResource(R.string.typing_hw_suggestion_hints_subtitle),
                    hw.suggestionHintsAlways,
                    info = stringResource(R.string.typing_hw_suggestion_hints_info),
                    default = SettingsDefaults.hardwareKeyboard.suggestionHintsAlways,
                ) { scope.launch { repository.setHwSuggestionHintsAlways(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_mac_title,
                stringResource(R.string.typing_hw_mac_subtitle),
                hw.macShortcuts,
                info = stringResource(R.string.typing_hw_mac_info),
                default = SettingsDefaults.hardwareKeyboard.macShortcuts,
            ) { scope.launch { repository.setHwMacShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_lang_chord_title,
                stringResource(R.string.typing_hw_lang_chord_subtitle),
                hw.languageSwitchChord,
                info = stringResource(R.string.typing_hw_lang_chord_info),
                default = SettingsDefaults.hardwareKeyboard.languageSwitchChord,
            ) { scope.launch { repository.setHwLanguageSwitchChord(it) } }
        }
        item {
            ToggleSetting(
                R.string.typing_hw_auto_show_title,
                stringResource(R.string.typing_hw_auto_show_subtitle),
                hw.autoShowUi,
                info = stringResource(R.string.typing_hw_auto_show_info),
                default = SettingsDefaults.hardwareKeyboard.autoShowUi,
            ) { scope.launch { repository.setHwAutoShowUi(it) } }
        }
    }
}

/** Route of the toolbar press-and-hold screen, reached from Appearance. */
internal const val ROUTE_TOOLBAR_HOLD = "toolbar_hold"

/**
 * What a press and hold on each pinned tool does.
 *
 * Lists the tools that are actually on the bar, because that is the only surface
 * this setting changes and a list of all forty-odd would bury them. A tool with
 * nothing bound keeps the original behaviour and opens its own settings page,
 * which the row says in as many words — the alternative, an explicit "settings"
 * entry in the picker, made the default look like a choice the user had made.
 *
 * The caret tools are shown with their reason rather than hidden: their hold is
 * already spent repeating the move, and someone looking for them here should find
 * out why instead of wondering where they went. Selection mode reads the same
 * way, for the hold that turns it on while the finger is down.
 */
@Composable
private fun ToolbarHoldSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ToolbarTool?>(null) }
    val holdActions = settings.toolbarBehavior.holdActions
    val pinned = settings.toolbarTools.filter(::isSupportedTool)

    Column {
        CaptionText(stringResource(R.string.appearance_toolbar_hold_intro_body))
        if (pinned.isEmpty()) {
            CaptionText(stringResource(R.string.appearance_toolbar_hold_empty_body))
            return@Column
        }
        SettingsGroup(stringResource(R.string.appearance_toolbar_hold_tools_group_title)) {
            for (tool in pinned) {
                item {
                    val repeats = tool in HoldRepeatCursorTools &&
                        settings.textEditing.cursorToolsRepeatOnHold
                    // The other hold that is already spoken for: this one turns
                    // selection mode on for as long as it lasts.
                    val selects = tool == ToolbarTool.SELECT_MODE &&
                        settings.textEditing.selectionModeHold
                    val bound = holdActions[tool]
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        leading = {
                            SlotIcon(IconSlots.forTool(tool), contentDescription = null)
                        },
                        supporting = when {
                            repeats -> {
                                { CaptionText(stringResource(R.string.appearance_toolbar_hold_repeats_subtitle)) }
                            }
                            selects -> {
                                { CaptionText(stringResource(R.string.appearance_toolbar_hold_selects_subtitle)) }
                            }
                            bound == null -> {
                                { CaptionText(stringResource(R.string.appearance_toolbar_hold_settings_subtitle)) }
                            }
                            else -> null
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (bound == null) {
                                        stringResource(CommonR.string.common_none)
                                    } else {
                                        stringResource(toolTitle(bound))
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (bound == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                if (bound != null) {
                                    IconButton(onClick = {
                                        scope.launch { repository.setToolHoldAction(tool, null) }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(
                                                R.string.appearance_toolbar_hold_clear_desc,
                                                stringResource(toolTitle(tool)),
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        // A tool whose hold is already spoken for has none to
                        // give, so its row reads rather than opens.
                        onClick = if (repeats || selects) null else ({ editing = tool }),
                    )
                }
            }
        }
        if (holdActions.isNotEmpty()) {
            TextButton(
                onClick = { scope.launch { repository.clearToolHoldActions() } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(stringResource(CommonR.string.common_reset_defaults)) }
        }
    }

    editing?.let { tool ->
        ToolPickerDialog(
            title = stringResource(R.string.appearance_toolbar_hold_pick_title, stringResource(toolTitle(tool))),
            current = holdActions[tool],
            // Every tool but this one: holding a tool to run itself is a slow tap.
            options = ToolbarTool.entries.filter { isSupportedTool(it) && it != tool },
            noneSubtitle = stringResource(R.string.appearance_toolbar_hold_settings_subtitle),
            onDismiss = { editing = null },
            onPick = { picked ->
                editing = null
                scope.launch { repository.setToolHoldAction(tool, picked) }
            },
        )
    }
}

/**
 * Picks one tool out of every tool, or none. Its own dialog rather than a
 * [ChoiceSetting] because the list is forty entries long and has to scroll.
 *
 * [noneSubtitle] both words the "None" row and decides whether there is one: the
 * layout editor picks the tool a key opens, where "no tool" is not a key anyone
 * would want, so it passes null and the row goes.
 */
@Composable
internal fun ToolPickerDialog(
    title: String,
    current: ToolbarTool?,
    options: List<ToolbarTool>,
    onDismiss: () -> Unit,
    onPick: (ToolbarTool?) -> Unit,
    noneSubtitle: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (noneSubtitle != null) item {
                    WmRow(
                        title = stringResource(CommonR.string.common_none),
                        supporting = { CaptionText(noneSubtitle) },
                        trailing = { RadioButton(selected = current == null, onClick = { onPick(null) }) },
                        onClick = { onPick(null) },
                    )
                }
                items(options, key = { it.name }) { tool ->
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
                        trailing = { RadioButton(selected = current == tool, onClick = { onPick(tool) }) },
                        onClick = { onPick(tool) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The letter that opens each tool from a physical keyboard, plus the shortcut key
 * that arms them.
 *
 * The rows are every supported tool rather than a list the user builds, so there
 * is no "add" — a tool either has a letter or it does not, and the unbound ones
 * are still reachable through the toolbox.
 */
@Composable
private fun HardwareShortcutsSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val hw = settings.hardwareKeyboard
    val leader = parseLeader(hw.leader) ?: DefaultLeader
    var editingLeader by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ToolbarTool?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val tools = remember(hw.toolByLetter, settings.enabledTools) {
        val letterOf = hw.toolByLetter.entries.associate { (letter, tool) -> tool to letter }
        // Bound tools first, in letter order, so the table reads as "what my
        // keyboard does" before "what else could be bound".
        ToolbarTool.entries.filter(::isSupportedTool)
            .sortedWith(compareBy({ letterOf[it] == null }, { letterOf[it] ?: ' ' }, { it.name }))
    }
    val letterOf = hw.toolByLetter.entries.associate { (letter, tool) -> tool to letter }
    // A chord spells itself, so it arrives as plain text; a double tap needs the
    // wording around the modifier name, which only this layer can resolve.
    val leaderSpec = leaderLabel(leader)
    val leaderName = if (leaderSpec.templateRes == 0) {
        leaderSpec.text
    } else {
        stringResource(leaderSpec.templateRes, leaderSpec.text)
    }
    val leaderTitle = stringResource(R.string.hardware_shortcuts_leader_title)

    Column {
        CaptionText(
            stringResource(
                R.string.hardware_shortcuts_intro_body,
                ToolboxLetter,
                CheatSheetLetter,
            ),
        )
        SettingsGroup(leaderTitle) {
            item {
                NavRow(
                    leaderTitle,
                    stringResource(R.string.hardware_shortcuts_leader_subtitle),
                    value = leaderName,
                    onClick = { editingLeader = true },
                )
            }
        }
        SettingsGroup(stringResource(R.string.hardware_shortcuts_tools_group_title)) {
            for (tool in tools) {
                item {
                    val letter = letterOf[tool]
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        leading = {
                            SlotIcon(IconSlots.forTool(tool), contentDescription = null)
                        },
                        // A tool with no API key is off as far as the keyboard
                        // is concerned, whatever the Tools screen last stored.
                        supporting = if (
                            tool !in settings.enabledTools || !isUsableTool(tool, settings)
                        ) {
                            { CaptionText(stringResource(R.string.hardware_shortcuts_tool_off_subtitle)) }
                        } else null,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    letter?.toString() ?: stringResource(CommonR.string.common_none),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (letter == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                if (letter != null) {
                                    IconButton(onClick = {
                                        scope.launch { repository.setHwToolLetter(letter, null) }
                                    }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(
                                                R.string.hardware_shortcuts_unbind_desc,
                                                toolTitle(tool),
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        onClick = { editing = tool },
                    )
                }
            }
        }
        TextButton(
            onClick = { confirmReset = true },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Text(stringResource(CommonR.string.common_reset_defaults)) }
    }

    if (editingLeader) {
        LeaderCaptureDialog(
            current = leader,
            onDismiss = { editingLeader = false },
            onPick = { picked ->
                editingLeader = false
                scope.launch { repository.setHwLeader(formatLeader(picked)) }
            },
        )
    }
    editing?.let { tool ->
        LetterCaptureDialog(
            tool = tool,
            current = letterOf[tool],
            takenBy = { letter -> hw.toolByLetter[letter] },
            onDismiss = { editing = null },
            onPick = { letter ->
                editing = null
                scope.launch { repository.setHwToolLetter(letter, tool) }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.hardware_shortcuts_reset_title)) },
            text = { Text(stringResource(R.string.hardware_shortcuts_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        repository.setHwToolLetters(DefaultToolLetters)
                        repository.setHwLeader(formatLeader(DefaultLeader))
                    }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Picks the shortcut key: a double-tapped modifier, or a chord pressed on an
 * attached keyboard.
 *
 * The double-tap choices matter more than the capture field — most people
 * editing this screen are holding a phone with no keyboard plugged in, and a
 * "press a key" prompt would leave them stuck.
 */
@Composable
private fun LeaderCaptureDialog(
    current: LeaderTrigger,
    onDismiss: () -> Unit,
    onPick: (LeaderTrigger) -> Unit,
) {
    var captured by remember { mutableStateOf<KeyChord?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hardware_shortcuts_leader_title)) },
        text = {
            Column {
                CaptionText(stringResource(R.string.hardware_shortcuts_double_tap_body))
                for (modifier in TapModifier.entries) {
                    val trigger = LeaderTrigger.DoubleTap(modifier)
                    // The same wording the row on the settings screen shows, and
                    // a double tap always carries a template to fill.
                    val spec = leaderLabel(trigger)
                    WmRow(
                        title = stringResource(spec.templateRes, spec.text),
                        trailing = {
                            if (current == trigger && captured == null) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_shortcuts_current_desc,
                                    ),
                                )
                            }
                        },
                        onClick = { onPick(trigger) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                CaptionText(stringResource(R.string.hardware_shortcuts_capture_body))
                // A real focusable window, unlike the keyboard's own, so Compose
                // focus is the right tool here.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(requester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val native = event.nativeKeyEvent
                            // Wait for the key the modifiers are qualifying.
                            if (KeyEvent.isModifierKey(native.keyCode)) return@onPreviewKeyEvent true
                            val chord = KeyChord(
                                keyCode = native.keyCode,
                                ctrl = native.isCtrlPressed,
                                alt = native.isAltPressed,
                                shift = native.isShiftPressed,
                                meta = native.isMetaPressed,
                            )
                            // A bare key would swallow ordinary typing, and a
                            // chord this app cannot name cannot be stored.
                            captured = chord.takeIf { it.hasModifier && formatChord(it) != null }
                            true
                        },
                ) {
                    Text(
                        captured?.let(::describeChord)
                            ?: stringResource(R.string.hardware_shortcuts_waiting_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                captured?.let { chord ->
                    if (chord in ReservedChords) {
                        CaptionText(
                            stringResource(
                                R.string.hardware_shortcuts_reserved_error,
                                describeChord(chord),
                            ),
                            error = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = captured != null,
                onClick = { captured?.let { onPick(LeaderTrigger.Chord(it)) } },
            ) { Text(stringResource(R.string.hardware_shortcuts_use_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Picks the letter for one tool. Typed rather than captured: this is a single
 * character, and a text field works with or without a keyboard attached.
 */
@Composable
private fun LetterCaptureDialog(
    tool: ToolbarTool,
    current: Char?,
    takenBy: (Char) -> ToolbarTool?,
    onDismiss: () -> Unit,
    onPick: (Char) -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString().orEmpty()) }
    val letter = text.trim().uppercase().firstOrNull()
    val valid = letter != null && (letter in 'A'..'Z' || letter in '0'..'9') &&
        letter !in ReservedLetters
    val clash = letter?.let(takenBy)?.takeIf { it != tool }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(toolTitle(tool))) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.takeLast(1) },
                    label = { Text(stringResource(R.string.hardware_shortcuts_letter_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    letter in ReservedLetters -> CaptionText(
                        stringResource(
                            R.string.hardware_shortcuts_letter_reserved_error,
                            letter?.toString().orEmpty(),
                            ToolboxLetter,
                            CheatSheetLetter,
                        ),
                        error = true,
                    )
                    clash != null -> CaptionText(
                        stringResource(
                            R.string.hardware_shortcuts_letter_clash_body,
                            letter.toString(),
                            toolTitle(clash),
                        ),
                    )
                    else -> CaptionText(stringResource(R.string.hardware_shortcuts_letter_hint))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { letter?.let(onPick) }) {
                Text(
                    if (clash != null) {
                        stringResource(R.string.hardware_shortcuts_letter_move_action)
                    } else {
                        stringResource(CommonR.string.common_save)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

// ---- key press ----

/**
 * Key-press sound controls, shared by the Key press settings screen and the
 * sound & haptics tool's detail page. Changes preview immediately through
 * [KeySoundPlayer]. [trailing] appends extra rows to the same card group.
 */
@Composable
private fun KeySoundGroup(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
    trailing: (SettingsGroupScope.() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val percentFormat = stringResource(R.string.typing_value_percent)
    SettingsGroup(stringResource(R.string.hardware_sound_group_title)) {
        item {
            ToggleSetting(
                R.string.hardware_sound_key_title,
                stringResource(R.string.hardware_sound_key_subtitle),
                settings.keySound,
                default = SettingsDefaults.keySound,
            ) {
                scope.launch { repository.setKeySound(it) }
                if (it) {
                    KeySoundPlayer.preview(context, settings.keySoundStyle, settings.keySoundVolume)
                }
            }
        }
        item {
            // Hand-built rather than a ChoiceSetting (the chips need their own
            // row), so the highlight wrapper every other control gets for free
            // is spelled out here — this is where the Sound addon's Use button
            // lands. The anchor is the row's own string resource, so the match
            // holds in every language.
            HighlightableRow(null, highlightKey = R.string.hardware_sound_style_title) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.hardware_sound_style_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    InfoButton(
                        stringResource(R.string.hardware_sound_style_title),
                        stringResource(R.string.hardware_sound_style_info),
                    )
                }
                // Custom is a segment like any other, so the styles read as one
                // choice rather than five here and a sixth hidden in a list. It
                // names a file rather than a fixed waveform, so it needs a sound
                // installed before it can be picked — the list below is where that
                // sound is chosen and where the "install one" note lives.
                val soundStore = remember { SoundStore.get(context) }
                val soundRevision by soundStore.revision.collectAsStateWithLifecycle()
                val installedSounds = remember(soundRevision) { soundStore.sounds() }
                // Chips rather than a segmented row. Six equal segments across a
                // phone leave ~55dp of label each, which truncated "Chime" to
                // "Chim" and "Custom" to "Custo"; a segmented row set to scroll is
                // worse still, since SegmentedButton has a wide minimum and only
                // three and a half fit. Chips size to their own text, so every
                // style keeps its real name, and the row scrolls only as far as it
                // has to. Same control the addon type filter uses.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in KeySoundStyle.entries) {
                        val custom = style == KeySoundStyle.CUSTOM
                        FilterChip(
                            selected = settings.keySoundStyle == style,
                            onClick = {
                                scope.launch {
                                    if (custom) {
                                        // Falls back to the first installed sound
                                        // when none has been chosen yet, so the
                                        // chip always makes a sound. With nothing
                                        // installed it still selects — the section
                                        // it reveals is where a sound is imported,
                                        // so a disabled chip would hide its own
                                        // remedy.
                                        val id = settings.keySoundCustom.customId
                                            .takeIf { id -> installedSounds.any { it.id == id } }
                                            ?: installedSounds.firstOrNull()?.id
                                        if (id == null) {
                                            repository.setKeySoundStyle(style)
                                        } else {
                                            repository.setKeySoundCustomId(id)
                                            KeySoundPlayer.preview(
                                                context, style, settings.keySoundVolume, id,
                                            )
                                        }
                                    } else {
                                        repository.setKeySoundStyle(style)
                                        // Sound the freshly picked style so the user
                                        // hears the choice immediately.
                                        KeySoundPlayer.preview(context, style, settings.keySoundVolume)
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (style) {
                                            KeySoundStyle.CLICK ->
                                                R.string.hardware_sound_style_click_label
                                            KeySoundStyle.STANDARD ->
                                                R.string.hardware_sound_style_standard_label
                                            KeySoundStyle.POP ->
                                                R.string.hardware_sound_style_pop_label
                                            KeySoundStyle.THOCK ->
                                                R.string.hardware_sound_style_thock_label
                                            KeySoundStyle.CHIME ->
                                                R.string.hardware_sound_style_chime_label
                                            KeySoundStyle.CUSTOM -> CommonR.string.common_custom
                                            KeySoundStyle.PACK ->
                                                R.string.hardware_sound_pack_style_label
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
        // Only under Custom. The sound library and its import button are what
        // Custom *means*; showing them under Click is offering a choice that
        // has no effect until the style changes too.
        if (settings.keySoundStyle == KeySoundStyle.CUSTOM) {
            item { InstalledSoundSection(repository, settings, onNavigate) }
        }
        if (settings.keySoundStyle == KeySoundStyle.PACK) {
            item { InstalledSoundPackSection(repository, settings, onNavigate) }
            item { KeyReleaseSoundToggle(repository, settings) }
        }
        item {
            SliderSetting(
                R.string.hardware_sound_volume_title,
                subtitle = stringResource(R.string.hardware_sound_volume_subtitle),
                value = settings.keySoundVolume,
                range = 0.05f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = SettingsDefaults.keySoundVolume,
            ) {
                scope.launch { repository.setKeySoundVolume(it) }
                // Debounced inside the player, so dragging previews smoothly.
                KeySoundPlayer.preview(context, settings.keySoundStyle, it)
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * The installed key sounds — whatever came from an addon repository, plus
 * anything imported here — and the import button.
 *
 * Picking one also switches the style to [KeySoundStyle.CUSTOM]; choosing a
 * sound and then finding the keyboard still clicking would be baffling.
 */
@Composable
private fun InstalledSoundSection(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val sounds = remember(revision) { store.sounds() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundFile.import(it, store, name = fontFileLabel(context, uri))
                    }
                }.getOrElse {
                    SoundImportResult.Failed(FeedbackR.string.core_feedback_sound_import_read_error)
                }
            }
            when (result) {
                is SoundImportResult.Imported -> {
                    repository.setKeySoundCustomId(result.sound.id)
                    KeySoundPlayer.preview(
                        context, KeySoundStyle.CUSTOM, settings.keySoundVolume, result.sound.id,
                    )
                }
                is SoundImportResult.NotASound -> message = context.getString(result.messageRes)
                SoundImportResult.TooManySounds ->
                    message = context.resources.getQuantityString(
                        R.plurals.hardware_sound_limit_error,
                        SoundStore.MAX_SOUNDS,
                        SoundStore.MAX_SOUNDS,
                    )
                // The refusal carries at most one argument, and "" means none.
                is SoundImportResult.Failed -> message = if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (sounds.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_empty))
        }
        for (sound in sounds) {
            val selected = settings.keySoundStyle == KeySoundStyle.CUSTOM &&
                settings.keySoundCustom.customId == sound.id
            HighlightableItem(sound.id) {
                WmRow(
                    title = sound.name,
                    supporting = sound.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(sound.id) }
                                    // The pool keeps its decoded copy independently
                                    // of the file, so it has to be told too.
                                    KeySoundPlayer.forgetCustom(sound.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        sound.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundCustomId(sound.id) }
                        KeySoundPlayer.preview(
                            context, KeySoundStyle.CUSTOM, settings.keySoundVolume, sound.id,
                        )
                    },
                )
            }
        }
        // Beside the file importer: the store is the other way to get a sound,
        // and it is the one that works when the user has no file to import.
        AddonStoreRow(AddonType.Sound, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_import_action)) }
    }
}

/**
 * The installed sound packs, and the import button.
 *
 * A pack differs from a single sound in the one way worth showing on the row:
 * how many recordings it holds, and which key roles it recorded separately.
 * Tapping one plays a variant, so tapping twice actually demonstrates the
 * variation rather than repeating itself.
 *
 * There is no file-picker mime type worth narrowing to — a `.wmsoundpack` is a
 * ZIP, and providers report a custom extension as `application/octet-stream` as
 * often as not — so the importer's own list is used and the real check is the
 * manifest inside.
 */
@Composable
private fun InstalledSoundPackSection(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packs = remember(revision) { store.packs() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundPackFile.import(it, store, fallbackName = fontFileLabel(context, uri))
                    }
                }.getOrElse { SoundPackImportResult.Failed }
            }
            when (result) {
                is SoundPackImportResult.Imported -> {
                    repository.setKeySoundPackId(result.pack.id)
                    KeySoundPlayer.previewStroke(
                        context, KeySoundStyle.PACK, settings.keySoundVolume, result.pack.id,
                    )
                }
                SoundPackImportResult.NotASoundPack ->
                    message = context.getString(R.string.hardware_sound_pack_not_a_pack_error)
                SoundPackImportResult.TooManyPacks ->
                    message = context.resources.getQuantityString(
                        R.plurals.hardware_sound_pack_limit_error,
                        SoundPackStore.MAX_PACKS,
                        SoundPackStore.MAX_PACKS,
                    )
                // The refusal carries at most one argument, and "" means none.
                is SoundPackImportResult.Rejected -> message = if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
                SoundPackImportResult.Failed ->
                    message = context.getString(R.string.hardware_sound_pack_read_error)
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (packs.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_pack_empty))
        }
        for (pack in packs) {
            val selected = settings.keySoundStyle == KeySoundStyle.PACK &&
                settings.keySoundCustom.packId == pack.id
            HighlightableItem(pack.id) {
                WmRow(
                    title = pack.name,
                    supporting = {
                        val roles = pack.roles
                        val variants = pluralStringResource(
                            R.plurals.hardware_sound_pack_variants,
                            pack.variantCount,
                            pack.variantCount,
                        )
                        val counts = if (roles.isEmpty()) {
                            variants
                        } else {
                            stringResource(
                                R.string.hardware_sound_pack_variants_and_roles,
                                variants,
                                roles.joinToString(", "),
                            )
                        }
                        // Appended rather than given its own line: it is one
                        // more fact about the pack, and the row already has a
                        // subtitle that reads as a list.
                        Text(
                            if (pack.hasRelease == true) {
                                stringResource(
                                    R.string.hardware_sound_pack_with_release,
                                    counts,
                                )
                            } else {
                                counts
                            },
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(pack.id) }
                                    // The pool keeps its decoded samples
                                    // independently of the files.
                                    KeySoundPlayer.forgetPack(pack.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        pack.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundPackId(pack.id) }
                        // The whole keystroke, not just the way down: a pack
                        // that recorded the switch returning is being judged on
                        // both halves, and half of it is a different pack.
                        KeySoundPlayer.previewStroke(
                            context, KeySoundStyle.PACK, settings.keySoundVolume, pack.id,
                        )
                    },
                )
            }
        }
        AddonStoreRow(AddonType.SoundPack, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundPackFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_pack_import_action)) }
    }
}

/**
 * "Play the key coming back up", for packs that recorded it.
 *
 * Draws nothing at all when the selected pack has no key-up recordings, which
 * is most of them: a switch the user can flip and hear no difference from is
 * worse than an absent one — it reads as the feature being broken rather than
 * as the pack not having it. The toggle appearing *is* how a pack announces it
 * has both halves.
 */
@Composable
private fun KeyReleaseSoundToggle(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packId = settings.keySoundCustom.packId
    val hasRelease = remember(revision, packId) {
        store.resolve(packId)?.let { store.pack(it)?.hasRelease } == true
    }
    if (!hasRelease) return
    ToggleSetting(
        R.string.hardware_sound_pack_release_title,
        stringResource(R.string.hardware_sound_pack_release_subtitle),
        settings.keySoundCustom.playRelease,
        default = SettingsDefaults.keySoundCustom.playRelease,
    ) { on ->
        scope.launch { repository.setKeySoundPlayRelease(on) }
        // Turning it on previews the whole keystroke, which is the only way to
        // hear what the switch just bought; turning it off previews the press
        // alone, so the difference is the thing demonstrated either way.
        if (on) {
            KeySoundPlayer.previewStroke(
                context, KeySoundStyle.PACK, settings.keySoundVolume, packId,
            )
        } else {
            KeySoundPlayer.preview(context, KeySoundStyle.PACK, settings.keySoundVolume, packId)
        }
    }
}

@Composable
private fun KeyPressSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Lets the SYSTEM_* preview fire through the real platform key haptic.
    val view = LocalView.current
    var popupShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    if (popupShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.popup.shape,
            radiusDp = settings.popup.cornerRadiusDp,
            onPick = { kind ->
                scope.launch { repository.setKeyPopupShape(kind) }
                popupShapePickerOpen = false
            },
            onDismiss = { popupShapePickerOpen = false },
            title = R.string.keypress_popup_shape_title,
        )
    }
    SettingsGroup(stringResource(R.string.keypress_haptics_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_haptics_title,
                stringResource(R.string.keypress_haptics_subtitle),
                settings.hapticFeedback,
                info = stringResource(R.string.keypress_haptics_info),
                default = SettingsDefaults.hapticFeedback,
            ) {
                scope.launch { repository.setHapticFeedback(it) }
                if (it) {
                    HapticPlayer.preview(
                        context, settings.hapticStyle, settings.hapticAmplitude, settings.hapticStrengthMs,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.keypress_haptic_style_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                InfoButton(
                    stringResource(R.string.keypress_haptic_style_title),
                    stringResource(R.string.keypress_haptic_style_info),
                )
            }
            // Six styles overflow a segmented row; wrapping chips give each a
            // full, readable label. Ordered best-to-worst via HapticStyle.entries.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                HapticStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.hapticStyle == style,
                        onClick = {
                            scope.launch { repository.setHapticStyle(style) }
                            // Fire the motor with the freshly picked style so the
                            // user feels the choice immediately.
                            HapticPlayer.preview(
                                context, style, settings.hapticAmplitude, settings.hapticStrengthMs, view,
                            )
                        },
                        label = { Text(stringResource(style.labelRes), maxLines = 1) },
                    )
                }
            }
        }
        if (settings.hapticStyle == HapticStyle.CUSTOM) {
            item {
                SliderSetting(
                    R.string.keypress_haptic_strength_title,
                    subtitle = stringResource(R.string.keypress_haptic_strength_subtitle),
                    value = settings.hapticStrengthMs.toFloat(),
                    range = 5f..60f,
                    display = { context.getString(R.string.keypress_value_ms, it.roundToInt()) },
                    info = stringResource(R.string.keypress_haptic_strength_info),
                    default = SettingsDefaults.hapticStrengthMs.toFloat(),
                ) {
                    scope.launch { repository.setHapticStrengthMs(it.toInt()) }
                    // Debounced inside the player, so dragging previews smoothly.
                    HapticPlayer.preview(context, settings.hapticStyle, settings.hapticAmplitude, it.toInt(), view)
                }
            }
        }
        if (settings.hapticStyle == HapticStyle.CUSTOM || settings.hapticStyle == HapticStyle.SHARP) {
            item {
                SliderSetting(
                    R.string.keypress_haptic_intensity_title,
                    subtitle = stringResource(R.string.keypress_haptic_intensity_subtitle),
                    value = settings.hapticAmplitude.toFloat(),
                    range = 1f..255f,
                    display = {
                        context.getString(R.string.keypress_value_percent, it.roundToInt() * 100 / 255)
                    },
                    info = stringResource(R.string.keypress_haptic_intensity_info),
                    default = SettingsDefaults.hapticAmplitude.toFloat(),
                ) {
                    scope.launch { repository.setHapticAmplitude(it.toInt()) }
                    HapticPlayer.preview(context, settings.hapticStyle, it.toInt(), settings.hapticStrengthMs, view)
                }
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_long_press_haptics_title,
                stringResource(R.string.keypress_long_press_haptics_subtitle),
                settings.hapticOnLongPress,
                info = stringResource(R.string.keypress_long_press_haptics_info),
                default = SettingsDefaults.hapticOnLongPress,
            ) { scope.launch { repository.setHapticOnLongPress(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_long_press_release_title,
                stringResource(R.string.keypress_long_press_release_subtitle),
                settings.hapticOnLongPressRelease,
                info = stringResource(R.string.keypress_long_press_release_info),
                default = SettingsDefaults.hapticOnLongPressRelease,
            ) { scope.launch { repository.setHapticOnLongPressRelease(it) } }
        }
        // Per-event gates: only meaningful while the master switch above is on,
        // so they fold away when it is off.
        if (settings.hapticFeedback) {
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_space_title,
                    stringResource(R.string.keypress_vibrate_space_subtitle),
                    settings.feedback.vibrateOnSpace,
                    info = stringResource(R.string.keypress_vibrate_space_info),
                    default = SettingsDefaults.feedback.vibrateOnSpace,
                ) { scope.launch { repository.setVibrateOnSpace(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_delete_swipe_title,
                    stringResource(R.string.keypress_vibrate_delete_swipe_subtitle),
                    settings.feedback.vibrateOnDeleteSwipe,
                    info = stringResource(R.string.keypress_vibrate_delete_swipe_info),
                    default = SettingsDefaults.feedback.vibrateOnDeleteSwipe,
                ) { scope.launch { repository.setVibrateOnDeleteSwipe(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_repeat_title,
                    stringResource(R.string.keypress_vibrate_repeat_subtitle),
                    settings.feedback.vibrateOnRepeat,
                    info = stringResource(R.string.keypress_vibrate_repeat_info),
                    default = SettingsDefaults.feedback.vibrateOnRepeat,
                ) { scope.launch { repository.setVibrateOnRepeat(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_sound_repeat_title,
                    stringResource(R.string.keypress_sound_repeat_subtitle),
                    settings.feedback.soundOnRepeat,
                    info = stringResource(R.string.keypress_sound_repeat_info),
                    default = SettingsDefaults.feedback.soundOnRepeat,
                ) { scope.launch { repository.setSoundOnRepeat(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_system_touch_title,
                    stringResource(R.string.keypress_system_touch_subtitle),
                    settings.feedback.respectSystemTouchFeedback,
                    info = stringResource(R.string.keypress_system_touch_info),
                    default = SettingsDefaults.feedback.respectSystemTouchFeedback,
                ) { scope.launch { repository.setRespectSystemTouchFeedback(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_dnd_mute_title,
                    stringResource(R.string.keypress_dnd_mute_subtitle),
                    settings.feedback.hapticsRespectDnd,
                    info = stringResource(R.string.keypress_dnd_mute_info),
                    default = SettingsDefaults.feedback.hapticsRespectDnd,
                ) { scope.launch { repository.setHapticsRespectDnd(it) } }
            }
        }
    }

    KeySoundGroup(repository, settings, onNavigate)

    SettingsGroup(stringResource(R.string.keypress_popup_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_popup_title,
                stringResource(R.string.keypress_popup_subtitle),
                settings.popup.enabled,
                info = stringResource(R.string.keypress_popup_info),
                default = SettingsDefaults.popup.enabled,
            ) { scope.launch { repository.setKeyPopup(it) } }
        }
        if (settings.popup.enabled) {
            item {
                ToggleSetting(
                    R.string.keypress_popup_numeric_title,
                    stringResource(R.string.keypress_popup_numeric_subtitle),
                    settings.popup.inNumericFields,
                    info = stringResource(R.string.keypress_popup_numeric_info),
                    default = SettingsDefaults.popup.inNumericFields,
                ) { scope.launch { repository.setKeyPopupInNumericFields(it) } }
            }
            item {
                SliderSetting(
                    R.string.keypress_popup_min_duration_title,
                    subtitle = stringResource(R.string.keypress_popup_min_duration_subtitle),
                    value = settings.popup.minDurationMs.toFloat(),
                    range = 0f..300f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_min_duration_info),
                    default = SettingsDefaults.popup.minDurationMs.toFloat(),
                ) { scope.launch { repository.setKeyPopupMinDurationMs(it.toInt()) } }
            }
            item {
                SliderSetting(
                    R.string.keypress_popup_max_duration_title,
                    subtitle = stringResource(R.string.keypress_popup_max_duration_subtitle),
                    value = settings.popup.maxDurationMs.toFloat(),
                    range = 400f..2000f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_max_duration_info),
                    default = SettingsDefaults.popup.maxDurationMs.toFloat(),
                ) { scope.launch { repository.setKeyPopupMaxDurationMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_popup_on_key_title,
                stringResource(R.string.keypress_popup_on_key_subtitle),
                settings.popup.onKey,
                info = stringResource(R.string.keypress_popup_on_key_info),
                default = SettingsDefaults.popup.onKey,
            ) { scope.launch { repository.setKeyPopupOnKey(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_popup_font_size_title,
                subtitle = stringResource(R.string.keypress_popup_font_size_subtitle),
                value = settings.popup.fontScale,
                range = 0.7f..1.6f,
                display = { context.getString(R.string.keypress_value_multiplier, it) },
                info = stringResource(R.string.keypress_popup_font_size_info),
                default = SettingsDefaults.popup.fontScale,
            ) { scope.launch { repository.setPopupFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_popup_height_title,
                subtitle = stringResource(R.string.keypress_popup_height_subtitle),
                value = settings.popup.heightDp.toFloat(),
                range = 32f..160f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_height_info),
                default = SettingsDefaults.popup.heightDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupHeightDp(it.toInt()) } }
        }
        // Shape and radius govern every popup surface, not only the preview
        // bubble: the long-press alternates, the language picker and the panel
        // menus all draw with them.
        item {
            NavRow(
                R.string.keypress_popup_shape_title,
                subtitle = stringResource(R.string.keypress_popup_shape_subtitle),
                value = keyShapeName(settings.popup.shape),
                onClick = { popupShapePickerOpen = true },
            )
        }
        item {
            SliderSetting(
                R.string.keypress_popup_radius_title,
                subtitle = stringResource(R.string.keypress_popup_radius_subtitle),
                value = settings.popup.cornerRadiusDp.toFloat(),
                range = 0f..40f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_radius_info),
                default = SettingsDefaults.popup.cornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupCornerRadiusDp(it.toInt()) } }
        }
    }

    SettingsGroup(stringResource(R.string.keypress_timing_group_title)) {
        item {
            SliderSetting(
                R.string.keypress_long_press_delay_title,
                subtitle = stringResource(R.string.keypress_long_press_delay_subtitle),
                value = settings.longPressDelayMs.toFloat(),
                range = 150f..700f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_long_press_delay_info),
                default = SettingsDefaults.longPressDelayMs.toFloat(),
            ) { scope.launch { repository.setLongPressDelayMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_repeat_start_title,
                subtitle = stringResource(R.string.keypress_repeat_start_subtitle),
                value = settings.keyRepeat.startDelayMs.toFloat(),
                range = 150f..800f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_repeat_start_info),
                default = SettingsDefaults.keyRepeat.startDelayMs.toFloat(),
            ) { scope.launch { repository.setKeyRepeatStartDelayMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_delete_repeat_title,
                subtitle = stringResource(R.string.keypress_delete_repeat_subtitle),
                value = settings.keyRepeat.deleteMs.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_delete_repeat_info),
                default = SettingsDefaults.keyRepeat.deleteMs.toFloat(),
            ) { scope.launch { repository.setDeleteRepeatIntervalMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_space_repeat_title,
                subtitle = stringResource(R.string.keypress_space_repeat_subtitle),
                value = settings.keyRepeat.spaceMs.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_space_repeat_info),
                default = SettingsDefaults.keyRepeat.spaceMs.toFloat(),
            ) { scope.launch { repository.setSpaceRepeatIntervalMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_caps_lock_title,
                subtitle = stringResource(R.string.keypress_caps_lock_subtitle),
                value = settings.layoutBehavior.shiftCapsLockMs.toFloat(),
                range = ShiftCapsLockMsRange.first.toFloat()..ShiftCapsLockMsRange.last.toFloat(),
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_caps_lock_info),
                default = SettingsDefaults.layoutBehavior.shiftCapsLockMs.toFloat(),
            ) { scope.launch { repository.setShiftCapsLockMs(it.toInt()) } }
        }
    }

    SettingsGroup(stringResource(R.string.keypress_shortcuts_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_long_press_hints_title,
                stringResource(R.string.keypress_long_press_hints_subtitle),
                settings.longPressHints,
                info = stringResource(R.string.keypress_long_press_hints_info),
                default = SettingsDefaults.longPressHints,
            ) { scope.launch { repository.setLongPressHints(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_all_accents_title,
                stringResource(R.string.keypress_all_accents_subtitle),
                settings.layoutBehavior.showAllPopupKeys,
                info = stringResource(R.string.keypress_all_accents_info),
                default = SettingsDefaults.layoutBehavior.showAllPopupKeys,
            ) { scope.launch { repository.setShowAllPopupKeys(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_symbols_numpad_title,
                stringResource(R.string.keypress_symbols_numpad_subtitle),
                settings.layoutBehavior.symbolsLongPressNumpad,
                info = stringResource(R.string.keypress_symbols_numpad_info),
                default = SettingsDefaults.layoutBehavior.symbolsLongPressNumpad,
            ) { scope.launch { repository.setSymbolsLongPressNumpad(it) } }
        }
        item {
            // A44: the $ key's long-press currency glyphs, space-separated. Blank
            // restores the built-in set. Mirrors the layout editor's alternates field.
            var currencyText by remember(settings.layoutBehavior.currencyKeys) {
                mutableStateOf(
                    settings.layoutBehavior.currencyKeys
                        .ifEmpty { DefaultCurrencyKeys }
                        .joinToString(" "),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.keypress_currency_keys_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    InfoButton(
                        stringResource(R.string.keypress_currency_keys_title),
                        stringResource(R.string.keypress_currency_keys_info),
                    )
                }
                OutlinedTextField(
                    value = currencyText,
                    onValueChange = {
                        currencyText = it
                        scope.launch {
                            repository.setCurrencyKeys(it.trim().split(" ").filter { s -> s.isNotBlank() })
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_ctrl_raw_title,
                stringResource(R.string.keypress_ctrl_raw_subtitle),
                settings.rawClipboardShortcuts,
                info = stringResource(R.string.keypress_ctrl_raw_info),
                default = SettingsDefaults.rawClipboardShortcuts,
            ) { scope.launch { repository.setRawClipboardShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_a_title,
                stringResource(R.string.keypress_hold_a_subtitle),
                settings.longPressLetterActions.selectAll,
                info = stringResource(R.string.keypress_hold_a_info),
                default = SettingsDefaults.longPressLetterActions.selectAll,
            ) { scope.launch { repository.setLongPressASelectAll(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_c_title,
                stringResource(R.string.keypress_hold_c_subtitle),
                settings.longPressLetterActions.copy,
                info = stringResource(R.string.keypress_hold_c_info),
                default = SettingsDefaults.longPressLetterActions.copy,
            ) { scope.launch { repository.setLongPressCCopy(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_x_title,
                stringResource(R.string.keypress_hold_x_subtitle),
                settings.longPressLetterActions.cut,
                info = stringResource(R.string.keypress_hold_x_info),
                default = SettingsDefaults.longPressLetterActions.cut,
            ) { scope.launch { repository.setLongPressXCut(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_v_title,
                stringResource(R.string.keypress_hold_v_subtitle),
                settings.longPressLetterActions.paste,
                info = stringResource(R.string.keypress_hold_v_info),
                default = SettingsDefaults.longPressLetterActions.paste,
            ) { scope.launch { repository.setLongPressVPaste(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_z_title,
                stringResource(R.string.keypress_hold_z_subtitle),
                settings.longPressLetterActions.undo,
                info = stringResource(R.string.keypress_hold_z_info),
                default = SettingsDefaults.longPressLetterActions.undo,
            ) { scope.launch { repository.setLongPressZUndo(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_hold_y_title,
                stringResource(R.string.keypress_hold_y_subtitle),
                settings.longPressLetterActions.redo,
                info = stringResource(R.string.keypress_hold_y_info),
                default = SettingsDefaults.longPressLetterActions.redo,
            ) { scope.launch { repository.setLongPressYRedo(it) } }
        }
        val holdActions = settings.longPressLetterActions
        if (holdActions.selectAll || holdActions.copy || holdActions.paste ||
            holdActions.cut || holdActions.undo || holdActions.redo
        ) {
            item { HoldShortcutLettersSetting(repository, holdActions) }
        }
    }
}

/**
 * Which key each hold shortcut sits on.
 *
 * One row for all six rather than six rows, because the six letters are one
 * decision: the shipped `acvxzy` is the QWERTY answer, and someone typing
 * Bengali or Russian is rebinding the whole set at once or not at all. Shown
 * only once at least one of the six actions is on, since it has nothing to say
 * otherwise.
 */
@Composable
private fun HoldShortcutLettersSetting(
    repository: SettingsRepository,
    actions: LongPressLetterActions,
) {
    val scope = rememberCoroutineScope()
    val labels = listOf(
        R.string.keypress_hold_a_title,
        R.string.keypress_hold_c_title,
        R.string.keypress_hold_v_title,
        R.string.keypress_hold_x_title,
        R.string.keypress_hold_z_title,
        R.string.keypress_hold_y_title,
    )
    val enabled = listOf(
        actions.selectAll, actions.copy, actions.paste,
        actions.cut, actions.undo, actions.redo,
    )
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.keypress_hold_letters_title)
    // Only the keys that are actually bound, so the summary reads as what the
    // keyboard will do rather than as the whole six-character string.
    val summary = enabled.mapIndexedNotNull { slot, on ->
        actions.letterFor(slot)?.takeIf { on }?.uppercase()
    }.joinToString(" ")
    HighlightableRow(title) {
        WmRow(
            title = title,
            subtitle = summary.ifEmpty { stringResource(R.string.keypress_hold_letters_subtitle) },
            trailing = {
                ResetSetting(title, actions.letters != DEFAULT_LONG_PRESS_LETTERS) {
                    scope.launch { repository.setLongPressLetters(DEFAULT_LONG_PRESS_LETTERS) }
                }
            },
            onClick = { editing = true },
        )
    }
    if (!editing) return
    // Edited as one string and written once on Save: a per-character write
    // would push five malformed values through the setter on the way to a
    // valid one, and the setter refuses anything that is not six characters.
    var draft by remember(actions.letters) { mutableStateOf(actions.letters) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(title) },
        text = {
            Column {
                DialogNote(stringResource(R.string.keypress_hold_letters_dialog_note))
                Spacer(Modifier.height(8.dp))
                labels.forEachIndexed { slot, labelRes ->
                    if (!enabled[slot]) return@forEachIndexed
                    OutlinedTextField(
                        value = draft.getOrNull(slot)?.toString().orEmpty(),
                        onValueChange = { typed ->
                            val ch = typed.lastOrNull() ?: return@OutlinedTextField
                            draft = draft.mapIndexed { i, old ->
                                if (i == slot) ch.lowercaseChar() else old
                            }.joinToString("")
                        },
                        label = { Text(stringResource(labelRes)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = false
                scope.launch { repository.setLongPressLetters(draft) }
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

// ---- appearance ----

@Composable
private fun AppearanceSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onOpenThemes: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenIcons: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val dpFormat = stringResource(R.string.typing_value_dp)
    val spFormat = stringResource(R.string.values_sp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    // Turning the toolbar off is guarded — it hides suggestions and every tool.
    var confirmDisableToolbar by remember { mutableStateOf(false) }
    var toolShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    if (toolShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.toolShape,
            radiusDp = settings.toolCircleRadiusDp,
            onPick = { kind ->
                scope.launch { repository.setToolShape(kind) }
                toolShapePickerOpen = false
            },
            onDismiss = { toolShapePickerOpen = false },
            title = R.string.appearance_tool_shape_title,
        )
    }
    SettingsGroup(stringResource(R.string.appearance_style_section_title)) {
        item {
            val selected = com.wasimaster.wmkeyboard.core.theme.findThemeSpec(
                settings.keyboardThemeId,
                settings.customThemes,
            )
            NavRow(
                R.string.appearance_themes_title,
                stringResource(R.string.appearance_themes_subtitle),
                value = if (selected == null) {
                    stringResource(CommonR.string.common_default)
                } else {
                    com.wasimaster.wmkeyboard.core.theme.themeName(selected)
                },
                route = "themes",
                onClick = onOpenThemes,
            )
        }
        item {
            NavRow(
                R.string.appearance_font_title,
                stringResource(R.string.appearance_font_subtitle),
                value = KeyboardFonts.genericDisplayName(
                    LocalContext.current,
                    settings.keyFontId,
                    settings.customFontName,
                ),
                route = "fonts",
                onClick = onOpenFonts,
            )
        }
        item {
            val active = settings.icons.activePackId
            val changed = settings.icons.overrides.size
            val defaultLabel = stringResource(CommonR.string.common_default)
            NavRow(
                R.string.appearance_icons_title,
                stringResource(R.string.appearance_icons_subtitle),
                value = when {
                    active.isNotEmpty() ->
                        IconPackStore.get(LocalContext.current).pack(active)?.name ?: defaultLabel
                    changed > 0 -> pluralStringResource(
                        R.plurals.appearance_icons_changed_count,
                        changed,
                        changed,
                    )
                    else -> defaultLabel
                },
                route = "icons",
                onClick = onOpenIcons,
            )
        }
    }

    SettingsGroup(stringResource(R.string.appearance_keys_section_title)) {
        item {
            SliderSetting(
                R.string.appearance_key_corner_radius_title,
                subtitle = stringResource(R.string.appearance_key_corner_radius_subtitle),
                value = settings.keyCornerRadiusDp.toFloat(),
                range = 0f..28f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_key_corner_radius_info),
                default = SettingsDefaults.keyCornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyCornerRadiusDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.appearance_key_label_size_title,
                subtitle = stringResource(R.string.appearance_key_label_size_subtitle),
                value = settings.fontScale,
                range = KeyFontScaleRange,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_label_size_info),
                default = SettingsDefaults.fontScale,
            ) { scope.launch { repository.setFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.appearance_key_hint_size_title,
                subtitle = stringResource(R.string.appearance_key_hint_size_subtitle),
                value = settings.layoutBehavior.hintFontScale,
                range = 0.5f..2.0f,
                display = { multiplierFormat.format(it) },
                info = stringResource(R.string.appearance_key_hint_size_info),
                default = SettingsDefaults.layoutBehavior.hintFontScale,
            ) { scope.launch { repository.setHintFontScale(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.appearance_toolbar_section_title)) {
        item {
            ToggleSetting(
                R.string.appearance_toolbar_show_title,
                stringResource(R.string.appearance_toolbar_show_subtitle),
                settings.toolbarBehavior.enabled,
                info = stringResource(R.string.appearance_toolbar_show_info),
                default = SettingsDefaults.toolbarBehavior.enabled,
            ) { on ->
                // Enabling is harmless; disabling loses real features, so confirm.
                if (on) scope.launch { repository.setToolbarEnabled(true) }
                else confirmDisableToolbar = true
            }
        }
        // Where the tools live: sharing the suggestion strip, or on a row of
        // their own so they are in reach whatever the strip is showing.
        if (settings.toolbarBehavior.enabled) {
            item {
                ChoiceSetting(
                    title = R.string.appearance_toolbar_placement_title,
                    subtitle = stringResource(R.string.appearance_toolbar_placement_subtitle),
                    info = stringResource(R.string.appearance_toolbar_placement_info),
                    options = listOf(
                        ToolbarPlacement.STRIP to
                            stringResource(R.string.appearance_toolbar_placement_strip_label),
                        ToolbarPlacement.ON_DEMAND_ROW to
                            stringResource(R.string.appearance_toolbar_placement_button_label),
                        ToolbarPlacement.ALWAYS_ROW to
                            stringResource(R.string.appearance_toolbar_placement_always_label),
                    ),
                    selected = settings.toolbarBehavior.placement,
                    onChange = { scope.launch { repository.setToolbarPlacement(it) } },
                    default = SettingsDefaults.toolbarBehavior.placement,
                )
            }
            item {
                NavRow(
                    title = R.string.appearance_toolbar_hold_title,
                    subtitle = stringResource(R.string.appearance_toolbar_hold_subtitle),
                    value = pluralStringResource(
                        R.plurals.appearance_toolbar_hold_count,
                        settings.toolbarBehavior.holdActions.size,
                        settings.toolbarBehavior.holdActions.size,
                    ),
                ) { onNavigate(ROUTE_TOOLBAR_HOLD) }
            }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_swipe_down_title,
                stringResource(R.string.appearance_toolbar_swipe_down_subtitle),
                settings.toolbarBehavior.swipeDownHide,
                info = stringResource(R.string.appearance_toolbar_swipe_down_info),
                default = SettingsDefaults.toolbarBehavior.swipeDownHide,
            ) { scope.launch { repository.setToolbarSwipeDownHide(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_hardware_only_title,
                stringResource(R.string.appearance_toolbar_hardware_only_subtitle),
                settings.toolbarBehavior.onlyWithHardwareKeyboard,
                info = stringResource(R.string.appearance_toolbar_hardware_only_info),
                default = SettingsDefaults.toolbarBehavior.onlyWithHardwareKeyboard,
            ) { scope.launch { repository.setToolbarOnlyWithHardwareKeyboard(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_rtl_title,
                stringResource(R.string.appearance_toolbar_rtl_subtitle),
                settings.toolbarBehavior.reverseForRtl,
                info = stringResource(R.string.appearance_toolbar_rtl_info),
                default = SettingsDefaults.toolbarBehavior.reverseForRtl,
            ) { scope.launch { repository.setReverseToolbarForRtl(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_spread_title,
                stringResource(R.string.appearance_toolbar_spread_subtitle),
                settings.toolbarBehavior.greedy,
                info = stringResource(R.string.appearance_toolbar_spread_info),
                default = SettingsDefaults.toolbarBehavior.greedy,
            ) { scope.launch { repository.setToolbarGreedy(it) } }
        }
        item {
            SliderSetting(
                R.string.appearance_toolbar_height_title,
                subtitle = stringResource(R.string.appearance_toolbar_height_subtitle),
                value = settings.toolbarHeightDp.toFloat(),
                range = 32f..80f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_toolbar_height_info),
                default = SettingsDefaults.toolbarHeightDp.toFloat(),
            ) { scope.launch { repository.setToolbarHeightDp(it.roundToInt()) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_scroll_title,
                stringResource(R.string.appearance_toolbar_scroll_subtitle),
                settings.toolbarBehavior.scrollable,
                info = stringResource(R.string.appearance_toolbar_scroll_info),
                default = SettingsDefaults.toolbarBehavior.scrollable,
            ) { scope.launch { repository.setToolbarScrollable(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_lock_title,
                stringResource(R.string.appearance_toolbar_lock_subtitle),
                settings.toolbarBehavior.hideWhenLocked,
                info = stringResource(R.string.appearance_toolbar_lock_info),
                default = SettingsDefaults.toolbarBehavior.hideWhenLocked,
            ) { scope.launch { repository.setToolbarHideWhenLocked(it) } }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbar_labels_title,
                stringResource(R.string.appearance_toolbar_labels_subtitle),
                settings.toolbarLabels,
                info = stringResource(R.string.appearance_toolbar_labels_info),
                default = SettingsDefaults.toolbarLabels,
            ) { scope.launch { repository.setToolbarLabels(it) } }
        }
        if (settings.toolbarLabels) {
            item {
                SliderSetting(
                    R.string.appearance_toolbar_label_size_title,
                    subtitle = stringResource(R.string.appearance_toolbar_label_size_subtitle),
                    value = settings.toolbarLabelSize.toFloat(),
                    range = 7f..14f,
                    display = { spFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.toolbarLabelSize.toFloat(),
                ) { scope.launch { repository.setToolbarLabelSize(it.roundToInt()) } }
            }
        }
        item {
            SliderSetting(
                R.string.appearance_suggestion_text_size_title,
                subtitle = stringResource(R.string.appearance_suggestion_text_size_subtitle),
                value = settings.suggestionStrip.textScale,
                range = 0.8f..1.6f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                info = stringResource(R.string.appearance_suggestion_text_size_info),
                default = SettingsDefaults.suggestionStrip.textScale,
            ) { scope.launch { repository.setSuggestionTextScale(it) } }
        }
        item {
            ResetPinnedToolsSetting(repository, scope)
        }
        // The grid's own order. "Reset pinned tools" restored the bar and
        // nothing restored the grid, so a bad drag session there had no way
        // back. Drawn only once the order has actually been changed.
        if (settings.toolboxOrder != SettingsDefaults.toolboxOrder) {
            item {
                ActionRow(
                    title = R.string.appearance_reset_toolbox_order_title,
                    subtitle = stringResource(R.string.appearance_reset_toolbox_order_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.appearance_reset_toolbox_order_confirm),
                ) { scope.launch { repository.resetToolboxOrder() } }
            }
        }
        item {
            val offLabel = stringResource(CommonR.string.common_off)
            SliderSetting(
                R.string.appearance_tool_circle_title,
                subtitle = stringResource(R.string.appearance_tool_circle_subtitle),
                value = settings.toolCircleRadiusDp.toFloat(),
                range = 0f..20f,
                display = { if (it.toInt() == 0) offLabel else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.appearance_tool_circle_info),
                default = SettingsDefaults.toolCircleRadiusDp.toFloat(),
            ) { scope.launch { repository.setToolCircleRadiusDp(it.toInt()) } }
        }
        // The same shapes the keys and the popups use. It draws nothing while
        // the radius above is at 0, which is the setting for "no background".
        if (settings.toolCircleRadiusDp > 0) {
            item {
                NavRow(
                    R.string.appearance_tool_shape_title,
                    subtitle = stringResource(R.string.appearance_tool_shape_subtitle),
                    value = keyShapeName(settings.toolShape),
                    onClick = { toolShapePickerOpen = true },
                )
            }
        }
        item {
            SliderSetting(
                R.string.appearance_tool_width_title,
                subtitle = stringResource(R.string.appearance_tool_width_subtitle),
                value = settings.toolbarBehavior.toolWidthDp.toFloat(),
                range = 38f..64f,
                display = { dpFormat.format(it.roundToInt()) },
                info = stringResource(R.string.appearance_tool_width_info),
                default = SettingsDefaults.toolbarBehavior.toolWidthDp.toFloat(),
            ) { scope.launch { repository.setToolbarToolWidthDp(it.roundToInt()) } }
        }
        item {
            ChoiceSetting(
                R.string.appearance_toolbox_layout_title,
                subtitle = stringResource(R.string.appearance_toolbox_layout_subtitle),
                options = listOf(
                    ToolboxLayout.ICONS to
                        stringResource(R.string.appearance_toolbox_layout_icons_label),
                    ToolboxLayout.PILLS to
                        stringResource(R.string.appearance_toolbox_layout_pills_label),
                ),
                selected = settings.toolbox.layout,
                info = stringResource(R.string.appearance_toolbox_layout_info),
                default = SettingsDefaults.toolbox.layout,
            ) { scope.launch { repository.setToolboxLayout(it) } }
        }
        if (settings.toolbox.layout == ToolboxLayout.ICONS) {
            item {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_columns_subtitle),
                    value = settings.toolboxColumns.toFloat(),
                    range = 3f..6f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_columns_info),
                    default = SettingsDefaults.toolboxColumns.toFloat(),
                ) { scope.launch { repository.setToolboxColumns(it.roundToInt()) } }
            }
        } else {
            item {
                val perRow = stringResource(R.string.appearance_slider_per_row_value)
                SliderSetting(
                    R.string.appearance_toolbox_pill_columns_title,
                    subtitle = stringResource(R.string.appearance_toolbox_pill_columns_subtitle),
                    value = settings.toolbox.pillColumns.toFloat(),
                    range = 1f..3f,
                    display = { perRow.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_pill_columns_info),
                    default = SettingsDefaults.toolbox.pillColumns.toFloat(),
                ) { scope.launch { repository.setToolboxPillColumns(it.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.appearance_toolbox_pill_filled_title,
                    stringResource(R.string.appearance_toolbox_pill_filled_subtitle),
                    settings.toolbox.pillFilled,
                    info = stringResource(R.string.appearance_toolbox_pill_filled_info),
                    default = SettingsDefaults.toolbox.pillFilled,
                ) { scope.launch { repository.setToolboxPillFilled(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.appearance_toolbox_paginate_title,
                stringResource(R.string.appearance_toolbox_paginate_subtitle),
                settings.toolbox.paginate,
                info = stringResource(R.string.appearance_toolbox_paginate_info),
                default = SettingsDefaults.toolbox.paginate,
            ) { scope.launch { repository.setToolboxPaginate(it) } }
        }
        if (settings.toolbox.paginate) {
            item {
                val perPage = stringResource(R.string.appearance_slider_per_page_value)
                SliderSetting(
                    R.string.appearance_toolbox_page_size_title,
                    subtitle = stringResource(R.string.appearance_toolbox_page_size_subtitle),
                    value = settings.toolbox.pageSize.toFloat(),
                    range = ToolboxPageSizeRange.first.toFloat()..ToolboxPageSizeRange.last.toFloat(),
                    display = { perPage.format(it.roundToInt()) },
                    info = stringResource(R.string.appearance_toolbox_page_size_info),
                    default = SettingsDefaults.toolbox.pageSize.toFloat(),
                ) { scope.launch { repository.setToolboxPageSize(it.roundToInt()) } }
            }
        }
        item {
            val followToolbar = stringResource(R.string.appearance_toolbox_label_size_follow)
            SliderSetting(
                R.string.appearance_toolbox_label_size_title,
                subtitle = stringResource(R.string.appearance_toolbox_label_size_subtitle),
                value = settings.toolbox.labelSizeSp.toFloat(),
                // 0 is the "follow the toolbar" end of the slider rather than a
                // size, which is why the readout reads as a word there.
                range = 0f..16f,
                display = {
                    if (it.roundToInt() == 0) followToolbar else spFormat.format(it.roundToInt())
                },
                info = stringResource(R.string.appearance_toolbox_label_size_info),
                default = SettingsDefaults.toolbox.labelSizeSp.toFloat(),
            ) { scope.launch { repository.setToolboxLabelSize(it.roundToInt()) } }
        }
        // Drawn only once something has actually moved, like the group reset on
        // Layout & size. Theme, font and icons are excluded on both sides of
        // this: they lead to their own screens and are not what "reset the
        // sliders" means.
        val d = SettingsDefaults
        val appearanceMoved = settings.keyCornerRadiusDp != d.keyCornerRadiusDp ||
            settings.fontScale != d.fontScale ||
            settings.layoutBehavior.hintFontScale != d.layoutBehavior.hintFontScale ||
            settings.toolbarBehavior != d.toolbarBehavior ||
            settings.toolbarHeightDp != d.toolbarHeightDp ||
            settings.toolbarLabels != d.toolbarLabels ||
            settings.toolbarLabelSize != d.toolbarLabelSize ||
            settings.suggestionStrip.textScale != d.suggestionStrip.textScale ||
            settings.toolCircleRadiusDp != d.toolCircleRadiusDp ||
            settings.toolShape != d.toolShape ||
            settings.toolbox != d.toolbox ||
            settings.toolboxColumns != d.toolboxColumns
        if (appearanceMoved) {
            item {
                ActionRow(
                    title = R.string.appearance_reset_title,
                    subtitle = stringResource(R.string.appearance_reset_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.appearance_reset_confirm),
                ) { scope.launch { repository.resetAppearance() } }
            }
        }
    }

    if (confirmDisableToolbar) {
        AlertDialog(
            onDismissRequest = { confirmDisableToolbar = false },
            title = { Text(stringResource(R.string.appearance_toolbar_disable_dialog_title)) },
            text = { Text(stringResource(R.string.appearance_toolbar_disable_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisableToolbar = false
                    scope.launch { repository.setToolbarEnabled(false) }
                }) { Text(stringResource(CommonR.string.common_disable)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisableToolbar = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

// ---- layout & size ----

@Composable
private fun LayoutSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val dpFormat = stringResource(R.string.typing_value_dp)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val multiplierFormat = stringResource(R.string.keypress_value_multiplier)
    SettingsGroup(stringResource(R.string.layout_number_row_title)) {
        item {
            ToggleSetting(
                R.string.layout_number_row_title,
                stringResource(R.string.layout_number_row_subtitle),
                settings.numberRow,
                info = stringResource(R.string.layout_number_row_info),
                default = SettingsDefaults.numberRow,
            ) { scope.launch { repository.setNumberRow(it) } }
        }
        if (settings.numberRow) {
            item {
                SliderSetting(
                    R.string.layout_number_row_height_title,
                    subtitle = stringResource(R.string.layout_number_row_height_subtitle),
                    value = settings.numberRowHeightDp.toFloat(),
                    range = 32f..100f,
                    display = { dpFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_number_row_height_info),
                    default = SettingsDefaults.numberRowHeightDp.toFloat(),
                ) { scope.launch { repository.setNumberRowHeightDp(it.toInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.layout_number_row_shift_symbols_title,
                    stringResource(R.string.layout_number_row_shift_symbols_subtitle),
                    settings.layoutBehavior.numberRowShiftSymbols,
                    info = stringResource(R.string.layout_number_row_shift_symbols_info),
                    default = SettingsDefaults.layoutBehavior.numberRowShiftSymbols,
                ) { scope.launch { repository.setNumberRowShiftSymbols(it) } }
            }
            item {
                ToggleSetting(
                    R.string.layout_number_row_in_symbols_title,
                    stringResource(R.string.layout_number_row_in_symbols_subtitle),
                    settings.layoutBehavior.numberRowInSymbols,
                    info = stringResource(R.string.layout_number_row_in_symbols_info),
                    default = SettingsDefaults.layoutBehavior.numberRowInSymbols,
                ) { scope.launch { repository.setNumberRowInSymbols(it) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_symbols_title)) {
        item {
            ToggleSetting(
                R.string.layout_symbols_return_title,
                stringResource(R.string.layout_symbols_return_subtitle),
                settings.layoutBehavior.symbolsReturnToLetters,
                info = stringResource(R.string.layout_symbols_return_info),
                default = SettingsDefaults.layoutBehavior.symbolsReturnToLetters,
            ) { scope.launch { repository.setSymbolsReturnToLetters(it) } }
        }
        if (settings.layoutBehavior.symbolsReturnToLetters) {
            item {
                // Saves as it is typed, like the currency keys field; blank
                // restores the default set. Seeded once rather than re-read on
                // every keystroke: the repository drops spaces and duplicates,
                // and feeding that back would move the caret while typing.
                var returnChars by remember {
                    mutableStateOf(settings.layoutBehavior.symbolsReturnCharSet())
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.layout_symbols_return_chars_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        InfoButton(
                            stringResource(R.string.layout_symbols_return_chars_title),
                            stringResource(R.string.layout_symbols_return_chars_info),
                        )
                    }
                    OutlinedTextField(
                        value = returnChars,
                        onValueChange = {
                            returnChars = it
                            scope.launch { repository.setSymbolsReturnChars(it) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_numerals_title)) {
        item {
            ChoiceSetting(
                R.string.layout_numeral_scope_title,
                subtitle = stringResource(R.string.layout_numeral_scope_subtitle),
                info = stringResource(R.string.layout_numeral_scope_info),
                options = NumeralCommitScope.entries.map { it to stringResource(it.labelRes) },
                selected = settings.layoutBehavior.numeralCommitScope,
                default = SettingsDefaults.layoutBehavior.numeralCommitScope,
            ) { scope.launch { repository.setNumeralCommitScope(it) } }
        }
        item {
            CaptionText(stringResource(R.string.layout_numerals_caption))
        }
    }

    SettingsGroup(stringResource(R.string.layout_size_position_title)) {
        item {
            SliderSetting(
                R.string.layout_key_height_title,
                subtitle = stringResource(R.string.layout_key_height_subtitle),
                value = settings.keyHeightDp.toFloat(),
                range = 32f..100f,
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_key_height_info),
                default = SettingsDefaults.keyHeightDp.toFloat(),
            ) { scope.launch { repository.setKeyHeightDp(it.toInt()) } }
        }
        item {
            val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
            SliderSetting(
                R.string.layout_bottom_row_height_title,
                subtitle = stringResource(R.string.layout_bottom_row_height_subtitle),
                value = settings.layoutBehavior.bottomRowHeightDp.toFloat(),
                range = 0f..BottomRowHeightRange.last.toFloat(),
                display = { if (it < 1f) followKeys else dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_row_height_info),
                default = SettingsDefaults.layoutBehavior.bottomRowHeightDp.toFloat(),
            ) { scope.launch { repository.setBottomRowHeightDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.layout_side_padding_title,
                subtitle = stringResource(R.string.layout_side_padding_subtitle),
                value = settings.layoutBehavior.sidePadScale,
                range = SidePadScaleRange.start..SidePadScaleRange.endInclusive,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_side_padding_info),
                default = SettingsDefaults.layoutBehavior.sidePadScale,
            ) { scope.launch { repository.setSidePadScale(it) } }
        }
        item {
            SliderSetting(
                R.string.layout_key_spacing_title,
                subtitle = stringResource(R.string.layout_key_spacing_subtitle),
                value = settings.keyGapScale,
                range = 0f..2f,
                display = { percentFormat.format((it * 100).toInt()) },
                info = stringResource(R.string.layout_key_spacing_info),
                default = SettingsDefaults.keyGapScale,
            ) { scope.launch { repository.setKeyGapScale(it) } }
        }
        item {
            SliderSetting(
                R.string.layout_bottom_padding_title,
                subtitle = stringResource(R.string.layout_bottom_padding_subtitle),
                value = settings.bottomPaddingDp.toFloat(),
                range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                display = { dpFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_bottom_padding_info),
                default = SettingsDefaults.bottomPaddingDp.toFloat(),
            ) { scope.launch { repository.setBottomPaddingDp(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.layout_keyboard_width_title,
                subtitle = stringResource(R.string.layout_keyboard_width_subtitle),
                value = settings.keyboardWidthPercent.toFloat(),
                range = 50f..100f,
                display = { percentFormat.format(it.toInt()) },
                info = stringResource(R.string.layout_keyboard_width_info),
                default = SettingsDefaults.keyboardWidthPercent.toFloat(),
            ) { scope.launch { repository.setKeyboardWidthPercent(it.toInt()) } }
        }
        if (settings.keyboardWidthPercent < 100) {
            item {
                ChoiceSetting(
                    title = R.string.layout_keyboard_position_title,
                    info = stringResource(R.string.layout_keyboard_position_info),
                    options = KeyboardAlignment.entries.map { alignment ->
                        alignment to stringResource(layoutAlignmentLabelRes(alignment))
                    },
                    selected = settings.keyboardAlignment,
                    default = SettingsDefaults.keyboardAlignment,
                ) { scope.launch { repository.setKeyboardAlignment(it) } }
            }
        }
        // Drawn only once something in the group has actually moved, like the
        // per-row reset controls: on an untouched screen it would be a button
        // that does nothing.
        val sizingMoved = settings.keyHeightDp != SettingsDefaults.keyHeightDp ||
            settings.numberRowHeightDp != SettingsDefaults.numberRowHeightDp ||
            settings.bottomPaddingDp != SettingsDefaults.bottomPaddingDp ||
            settings.keyboardWidthPercent != SettingsDefaults.keyboardWidthPercent ||
            settings.keyboardAlignment != SettingsDefaults.keyboardAlignment ||
            settings.keyGapScale != SettingsDefaults.keyGapScale ||
            settings.keyCornerRadiusDp != SettingsDefaults.keyCornerRadiusDp ||
            settings.layoutBehavior.sidePadScale != SettingsDefaults.layoutBehavior.sidePadScale ||
            settings.layoutBehavior.bottomRowHeightDp !=
            SettingsDefaults.layoutBehavior.bottomRowHeightDp
        if (sizingMoved) {
            item {
                ActionRow(
                    title = R.string.layout_reset_sizing_title,
                    subtitle = stringResource(R.string.layout_reset_sizing_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                    confirm = stringResource(R.string.layout_reset_sizing_confirm),
                ) { scope.launch { repository.resetSizeAndPosition() } }
            }
        }
    }

    var expandedVariant by remember { mutableStateOf<ScreenVariant?>(null) }
    SettingsGroup(stringResource(R.string.layout_per_screen_title)) {
        item {
            CaptionText(stringResource(R.string.layout_per_screen_caption))
        }
        for (variant in ScreenVariant.entries.filter { it.isOverride }) {
            val override = settings.sizingOverrides[variant]
            val values = settings.sizingValuesFor(variant)
            item {
                NavRow(
                    stringResource(variant.labelRes),
                    if (override == null || override.isEmpty) {
                        stringResource(R.string.layout_variant_follows_portrait_label)
                    } else {
                        stringResource(
                            R.string.layout_variant_summary,
                            values.keyHeightDp ?: settings.keyHeightDp,
                            values.keyboardWidthPercent ?: settings.keyboardWidthPercent,
                        )
                    },
                    onClick = {
                        expandedVariant = if (expandedVariant == variant) null else variant
                    },
                )
            }
            if (expandedVariant == variant) {
                item {
                    SliderSetting(
                        R.string.layout_keyboard_scale_title,
                        subtitle = stringResource(R.string.layout_keyboard_scale_subtitle),
                        value = values.keyboardScale ?: 1f,
                        range = 0.5f..1.5f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyboardScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_key_height_title,
                        value = (values.keyHeightDp ?: settings.keyHeightDp).toFloat(),
                        range = 32f..100f,
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantKeyHeightDp(variant, it.toInt()) } }
                }
                if (settings.numberRow) {
                    item {
                        SliderSetting(
                            R.string.layout_number_row_height_title,
                            value = (values.numberRowHeightDp ?: settings.numberRowHeightDp).toFloat(),
                            range = 32f..100f,
                            display = { dpFormat.format(it.toInt()) },
                        ) {
                            scope.launch {
                                repository.setVariantNumberRowHeightDp(variant, it.toInt())
                            }
                        }
                    }
                }
                item {
                    SliderSetting(
                        R.string.layout_bottom_padding_title,
                        value = (values.bottomPaddingDp ?: settings.bottomPaddingDp).toFloat(),
                        range = 0f..SettingsRepository.MAX_BOTTOM_PADDING_DP.toFloat(),
                        display = { dpFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantBottomPaddingDp(variant, it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_keyboard_width_title,
                        value = (values.keyboardWidthPercent ?: settings.keyboardWidthPercent).toFloat(),
                        range = 50f..100f,
                        display = { percentFormat.format(it.toInt()) },
                    ) { scope.launch { repository.setVariantWidthPercent(variant, it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_font_size_title,
                        value = values.fontScale ?: settings.fontScale,
                        range = KeyFontScaleRange,
                        display = { multiplierFormat.format(it) },
                    ) { scope.launch { repository.setVariantFontScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_key_spacing_title,
                        value = values.keyGapScale ?: settings.keyGapScale,
                        range = 0f..2f,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantKeyGapScale(variant, it) } }
                }
                item {
                    SliderSetting(
                        R.string.layout_side_padding_title,
                        value = values.sidePadScale ?: settings.layoutBehavior.sidePadScale,
                        range = SidePadScaleRange,
                        display = { percentFormat.format((it * 100).toInt()) },
                    ) { scope.launch { repository.setVariantSidePadScale(variant, it) } }
                }
                item {
                    val followKeys = stringResource(R.string.layout_bottom_row_follow_keys_label)
                    SliderSetting(
                        R.string.layout_bottom_row_height_title,
                        value = (
                            values.bottomRowHeightDp
                                ?: settings.layoutBehavior.bottomRowHeightDp
                            ).toFloat(),
                        range = 0f..BottomRowHeightRange.last.toFloat(),
                        display = {
                            if (it.toInt() == 0) followKeys else dpFormat.format(it.toInt())
                        },
                    ) {
                        scope.launch { repository.setVariantBottomRowHeightDp(variant, it.toInt()) }
                    }
                }
                item {
                    // The one per-shape choice that is not a number. Landscape
                    // has the least room for a sixth row and the most need for
                    // the keys under it.
                    ToggleSetting(
                        R.string.layout_number_row_title,
                        null,
                        values.numberRow ?: settings.numberRow,
                    ) { scope.launch { repository.setVariantNumberRow(variant, it) } }
                }
                if ((values.keyboardWidthPercent ?: settings.keyboardWidthPercent) < 100) {
                    item {
                        ChoiceSetting(
                            title = R.string.layout_keyboard_position_title,
                            options = KeyboardAlignment.entries.map { alignment ->
                                alignment to stringResource(layoutAlignmentLabelRes(alignment))
                            },
                            selected = values.keyboardAlignment ?: settings.keyboardAlignment,
                        ) { scope.launch { repository.setVariantAlignment(variant, it) } }
                    }
                }
                if (override != null && !override.isEmpty) {
                    item {
                        NavRow(
                            R.string.layout_follow_portrait_title,
                            stringResource(
                                R.string.layout_follow_portrait_subtitle,
                                stringResource(variant.labelRes),
                            ),
                            onClick = { scope.launch { repository.clearVariantSizing(variant) } },
                        )
                    }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_one_handed_group_title)) {
        item {
            ChoiceSetting(
                title = R.string.layout_one_handed_title,
                subtitle = stringResource(R.string.layout_one_handed_subtitle),
                options = OneHandedMode.entries.map { mode ->
                    mode to stringResource(layoutOneHandedModeLabelRes(mode))
                },
                selected = settings.oneHandedMode,
                default = SettingsDefaults.oneHandedMode,
            ) { scope.launch { repository.setOneHandedMode(it) } }
        }
        item {
            CaptionText(stringResource(R.string.layout_one_handed_caption))
        }
        val orientations = listOf(
            false to R.string.layout_orientation_portrait_label,
            true to R.string.layout_orientation_landscape_label,
        )
        for ((landscape, orientationRes) in orientations) {
            val profile = settings.oneHanded.forLandscape(landscape)
            item {
                val orientationLabel = stringResource(orientationRes)
                SliderSetting(
                    stringResource(R.string.layout_one_handed_width_title, orientationLabel),
                    subtitle = stringResource(
                        R.string.layout_one_handed_width_subtitle,
                        orientationLabel,
                    ),
                    value = profile.widthPercent.toFloat(),
                    range = SettingsRepository.ONE_HANDED_WIDTH_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_WIDTH_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_width_info),
                    default = SettingsDefaults.oneHanded.forLandscape(landscape)
                        .widthPercent.toFloat(),
                ) { scope.launch { repository.setOneHandedWidthPercent(landscape, it.toInt()) } }
            }
            item {
                SliderSetting(
                    stringResource(
                        R.string.layout_one_handed_height_title,
                        stringResource(orientationRes),
                    ),
                    subtitle = stringResource(R.string.layout_one_handed_height_subtitle),
                    value = profile.heightScale.toFloat(),
                    range = SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MIN.toFloat()..
                        SettingsRepository.ONE_HANDED_HEIGHT_SCALE_MAX.toFloat(),
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_one_handed_height_info),
                    default = SettingsDefaults.oneHanded.forLandscape(landscape)
                        .heightScale.toFloat(),
                ) { scope.launch { repository.setOneHandedHeightScale(landscape, it.toInt()) } }
            }
            item {
                val orientationLabel = stringResource(orientationRes)
                ChoiceSetting(
                    title = stringResource(
                        R.string.layout_one_handed_side_title,
                        orientationLabel,
                    ),
                    subtitle = stringResource(
                        R.string.layout_one_handed_side_subtitle,
                        orientationLabel,
                    ),
                    options = OneHandedSide.entries.map { side ->
                        side to stringResource(layoutOneHandedSideLabelRes(side))
                    },
                    selected = profile.side,
                    default = SettingsDefaults.oneHanded.forLandscape(landscape).side,
                ) { scope.launch { repository.setOneHandedSide(landscape, it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_split_title,
                stringResource(R.string.layout_split_subtitle),
                settings.splitKeyboard,
                info = stringResource(R.string.layout_split_info),
                default = SettingsDefaults.splitKeyboard,
            ) { scope.launch { repository.setSplitKeyboard(it) } }
        }
        if (settings.splitKeyboard) {
            item {
                ToggleSetting(
                    R.string.layout_split_large_only_title,
                    stringResource(R.string.layout_split_large_only_subtitle),
                    settings.layoutBehavior.splitOnlyOnLargeScreens,
                    info = stringResource(R.string.layout_split_large_only_info),
                    default = SettingsDefaults.layoutBehavior.splitOnlyOnLargeScreens,
                ) { scope.launch { repository.setSplitOnlyOnLargeScreens(it) } }
            }
            item {
                SliderSetting(
                    R.string.layout_split_gap_title,
                    subtitle = stringResource(R.string.layout_split_gap_subtitle),
                    value = settings.splitGapPercent.toFloat(),
                    range = 5f..40f,
                    display = { percentFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_split_gap_info),
                    default = SettingsDefaults.splitGapPercent.toFloat(),
                ) { scope.launch { repository.setSplitGapPercent(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.layout_floating_title,
                stringResource(R.string.layout_floating_subtitle),
                settings.floatingKeyboard,
                info = stringResource(R.string.layout_floating_info),
                default = SettingsDefaults.floatingKeyboard,
            ) { scope.launch { repository.setFloatingKeyboard(it) } }
        }
        if (settings.floatingKeyboard) {
            item {
                SliderSetting(
                    R.string.layout_floating_width_title,
                    subtitle = stringResource(R.string.layout_floating_width_subtitle),
                    value = settings.floatingWidthDp.toFloat(),
                    range = 240f..500f,
                    display = { dpFormat.format(it.toInt()) },
                    info = stringResource(R.string.layout_floating_width_info),
                    default = SettingsDefaults.floatingWidthDp.toFloat(),
                ) { scope.launch { repository.setFloatingWidthDp(it.toInt()) } }
            }
            item {
                SliderSetting(
                    R.string.layout_floating_height_title,
                    subtitle = stringResource(R.string.layout_floating_height_subtitle),
                    value = settings.floatingHeightScale,
                    range = 0.6f..1.6f,
                    display = { percentFormat.format((it * 100).toInt()) },
                    info = stringResource(R.string.layout_floating_height_info),
                    default = SettingsDefaults.floatingHeightScale,
                ) { scope.launch { repository.setFloatingHeightScale(it) } }
            }
            item {
                val movedFloating = settings.floatingWidthDp != SettingsDefaults.floatingWidthDp ||
                    settings.floatingHeightScale != SettingsDefaults.floatingHeightScale ||
                    settings.floatingXFraction != SettingsDefaults.floatingXFraction ||
                    settings.floatingYFraction != SettingsDefaults.floatingYFraction
                if (movedFloating) {
                    ActionRow(
                        title = R.string.layout_floating_reset_title,
                        subtitle = stringResource(R.string.layout_floating_reset_subtitle),
                        action = stringResource(CommonR.string.common_reset),
                    ) { scope.launch { repository.resetFloatingGeometry() } }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.layout_bottom_row_keys_title)) {
        item {
            ToggleSetting(
                R.string.layout_comma_emoji_title,
                stringResource(R.string.layout_comma_emoji_subtitle),
                settings.commaAsEmoji,
                info = stringResource(R.string.layout_comma_emoji_info),
                default = SettingsDefaults.commaAsEmoji,
            ) { scope.launch { repository.setCommaAsEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_globe_emoji_title,
                stringResource(R.string.layout_globe_emoji_subtitle),
                settings.globeAsEmoji,
                info = stringResource(R.string.layout_globe_emoji_info),
                default = SettingsDefaults.globeAsEmoji,
            ) { scope.launch { repository.setGlobeAsEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.layout_swap_comma_globe_title,
                stringResource(R.string.layout_swap_comma_globe_subtitle),
                settings.swapCommaAndGlobe,
                info = stringResource(R.string.layout_swap_comma_globe_info),
                default = SettingsDefaults.swapCommaAndGlobe,
            ) { scope.launch { repository.setSwapCommaAndGlobe(it) } }
        }
    }
}

/** The name drawn on the segmented button for each [KeyboardAlignment]. */
@StringRes
private fun layoutAlignmentLabelRes(alignment: KeyboardAlignment): Int = when (alignment) {
    KeyboardAlignment.LEFT -> R.string.layout_edge_left_label
    KeyboardAlignment.CENTER -> R.string.layout_edge_centre_label
    KeyboardAlignment.RIGHT -> R.string.layout_edge_right_label
}

/** The name drawn on the segmented button for each [OneHandedMode]. */
@StringRes
private fun layoutOneHandedModeLabelRes(mode: OneHandedMode): Int = when (mode) {
    OneHandedMode.OFF -> CommonR.string.common_off
    OneHandedMode.LEFT -> R.string.layout_edge_left_label
    OneHandedMode.RIGHT -> R.string.layout_edge_right_label
}

/** The name drawn on the segmented button for each [OneHandedSide]. */
@StringRes
private fun layoutOneHandedSideLabelRes(side: OneHandedSide): Int = when (side) {
    OneHandedSide.LEFT -> R.string.layout_edge_left_label
    OneHandedSide.RIGHT -> R.string.layout_edge_right_label
}

// ---- languages ----

/** [ReturnAnchor] key for the Languages screen's "Your languages" list. */
private const val LANGUAGES_ANCHOR = "languages"

@Composable
private fun LanguageSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Same prompt the add-language list shows, so the shortlist below cannot be
    // the one path that downloads a language's data without asking.
    val dataPrompt = rememberLanguageDataPrompt()
    // The language this screen was last left from. Someone who went into Bangla
    // to fetch its dictionary comes back to Bangla, not to the top of a list of
    // eleven languages they then have to find it in again.
    val returnTo = remember { ReturnAnchor.take(LANGUAGES_ANCHOR) }
    CaptionText(stringResource(R.string.langemoji_lang_intro_body))
    // "Your languages" is the enabled set (deduped, in switch order); each opens
    // its detail. Adding one is a search over the whole registry.
    SettingsGroup(stringResource(R.string.langemoji_lang_your_languages_title)) {
        for (language in settings.enabledLanguages) {
            item {
                val names = settings.enabledLayoutIds
                    .filter { resolveLayout(settings.customLayouts, it).language().id == language.id }
                    .joinToString { resolveLayout(settings.customLayouts, it).name }
                ScrollAnchor(language.id == returnTo) {
                    NavRow(
                        language.displayName,
                        subtitle = names.ifBlank { null },
                        route = "language/${language.id}",
                    ) {
                        ReturnAnchor.arm(LANGUAGES_ANCHOR, language.id)
                        onNavigate("language/${language.id}")
                    }
                }
            }
        }
        item {
            NavRow(
                R.string.langemoji_lang_add_title,
                subtitle = pluralStringResource(
                    R.plurals.langemoji_lang_add_subtitle,
                    LanguageRegistry.all.size,
                    LanguageRegistry.all.size,
                ),
                route = "add_language",
            ) { onNavigate("add_language") }
        }
    }
    // A short device-derived shortlist, so the common case never has to go
    // through the full registry. The reasoning lives in LanguageSuggestions.
    val suggested = rememberSuggestedLanguages(settings, limit = LANGUAGE_SCREEN_SUGGESTIONS)
    if (suggested.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.langemoji_lang_suggested_title)) {
            for (suggestion in suggested) {
                item {
                    NavRow(
                        suggestion.language.displayName,
                        subtitle = suggestionReasonLabel(suggestion),
                    ) {
                        dataPrompt.ask(suggestion.language) {
                            addLanguage(scope, repository, settings, suggestion.language)
                            // It is about to be one of "your languages", and
                            // coming back is where the user will look for it.
                            ReturnAnchor.arm(LANGUAGES_ANCHOR, suggestion.language.id)
                            onNavigate("language/${suggestion.language.id}")
                        }
                    }
                }
            }
            item {
                CaptionText(stringResource(R.string.langemoji_lang_suggested_source_body))
            }
        }
    }
    // The master switch over every download the keyboard would otherwise start
    // on its own. Here rather than under Emoji because it covers prediction
    // data too, and this is the screen where languages arrive.
    SettingsGroup(stringResource(R.string.langemoji_lang_data_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_auto_download_title,
                stringResource(R.string.langemoji_lang_auto_download_subtitle),
                settings.autoDownloadLanguageData,
                info = stringResource(R.string.langemoji_lang_auto_download_info),
                default = SettingsDefaults.autoDownloadLanguageData,
            ) { scope.launch { repository.setAutoDownloadLanguageData(it) } }
        }
        item {
            // The metered-download confirmation moved to the data-saver
            // screen, where it is one answer among three rather than a switch,
            // and where it now covers the model downloads too. This row stays
            // as the signpost for anyone who came here looking for it.
            NavRow(
                R.string.langemoji_lang_metered_title,
                stringResource(R.string.langemoji_lang_metered_subtitle),
                onClick = { onNavigate("datasaver") },
            )
        }
        item {
            ToggleSetting(
                R.string.langemoji_lang_autopair_title,
                stringResource(R.string.langemoji_lang_autopair_subtitle),
                settings.autoPairRomanized,
                info = stringResource(R.string.langemoji_lang_autopair_info),
                default = SettingsDefaults.autoPairRomanized,
            ) { scope.launch { repository.setAutoPairRomanized(it) } }
        }
        if (settings.perAppLanguage.layoutByPackage.isNotEmpty()) {
            item {
                ActionRow(
                    title = R.string.langemoji_lang_forget_apps_title,
                    subtitle = pluralStringResource(
                        R.plurals.langemoji_lang_forget_apps_subtitle,
                        settings.perAppLanguage.layoutByPackage.size,
                        settings.perAppLanguage.layoutByPackage.size,
                    ),
                    action = stringResource(CommonR.string.common_clear),
                    confirm = stringResource(R.string.langemoji_lang_forget_apps_confirm),
                    lock = AppLockTargets["action_forget_app_languages"],
                ) { scope.launch { repository.clearPerAppLayouts() } }
            }
        }
    }
    // Reorder the switch ring (spacebar swipe / 🌐 cycle) across every enabled
    // layout, not just languages, so AZERTY and QWERTY keep distinct slots.
    if (settings.enabledLayoutIds.size > 1) {
        // Two layouts of one language, and two languages on one layout, both
        // read the same by layout name alone, so each row carries its language.
        val switchOrderItem = stringResource(R.string.langemoji_lang_switch_order_item_label)
        SettingsGroup {
            item {
                ReorderSetting(
                    stringResource(R.string.langemoji_lang_switch_order_title),
                    stringResource(R.string.langemoji_lang_switch_order_dialog_title),
                    settings.enabledLayoutIds,
                    label = {
                        val layout = resolveLayout(settings.customLayouts, it)
                        val language = layout.language().displayName
                        // A layout named after its own language would say it twice.
                        if (layout.name == language) language
                        else switchOrderItem.format(language, layout.name)
                    },
                    onReordered = { scope.launch { repository.setEnabledLayoutIds(it) } },
                )
            }
        }
    }
    // Custom layouts get their own group after the languages: they are the
    // user's own grids (edited on the Key layouts screen), not a language to add.
    // Only the user's own grids. An override of a shipped layout — built-in or
    // JSON asset — is an edit of that layout, not a layout of their own, and
    // listing it here would show the same name twice: once as the language's
    // layout above, once as if they had made it.
    val customs = settings.customLayouts
        .filter {
            com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.byId(it.id) == null &&
                com.wasimaster.wmkeyboard.core.layout.AssetLayouts.byId(it.id) == null
        }
        .sortedBy { it.name.lowercase() }
    // Turning a layout on is gated on it validating; switching one off never is,
    // or a layout broken while enabled would be impossible to put away.
    val enableGate = rememberLayoutEnableGate(settings)
    SettingsGroup(stringResource(R.string.langemoji_lang_your_layouts_title)) {
        for (layout in customs) {
            item {
                // An installed layout arrives switched off and this switch is
                // what finishes the install, so its addon's Use button lands
                // here — on the layout's own row, not on the group.
                HighlightableItem(layout.id) {
                    ToggleSetting(
                        layout.name,
                        stringResource(
                            R.string.langemoji_lang_custom_layout_subtitle,
                            baseModeTitle(layout),
                        ),
                        layout.id in settings.enabledLayoutIds,
                        default = layout.id in SettingsDefaults.enabledLayoutIds,
                    ) { enable ->
                        fun write() {
                            scope.launch {
                                val next =
                                    if (enable) settings.enabledLayoutIds + layout.id
                                    else settings.enabledLayoutIds - layout.id
                                if (next.isNotEmpty()) {
                                    repository.setEnabledLayoutIds(next.distinct())
                                }
                            }
                        }
                        if (enable) enableGate(layout.id) { write() } else write()
                    }
                }
            }
        }
        item {
            NavRow(
                R.string.langemoji_lang_keymaps_title,
                subtitle = if (customs.isEmpty()) {
                    stringResource(R.string.langemoji_lang_keymaps_empty_subtitle)
                } else {
                    stringResource(R.string.langemoji_lang_keymaps_subtitle)
                },
                route = "keymaps",
            ) { onNavigate("keymaps") }
        }
        item { AddonStoreRow(AddonType.Layout, onNavigate) }
    }
    SettingsGroup(stringResource(R.string.langemoji_lang_per_app_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_per_app_toggle_title,
                stringResource(R.string.langemoji_lang_per_app_toggle_subtitle),
                settings.perAppLanguage.enabled,
                info = stringResource(R.string.langemoji_lang_per_app_toggle_info),
                default = SettingsDefaults.perAppLanguage.enabled,
            ) { scope.launch { repository.setRememberLayoutPerApp(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_lang_system_switcher_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_lang_os_switcher_title,
                stringResource(R.string.langemoji_lang_os_switcher_subtitle),
                settings.osLanguageSwitcher,
                info = stringResource(R.string.langemoji_lang_os_switcher_info),
                default = SettingsDefaults.osLanguageSwitcher,
            ) { scope.launch { repository.setOsLanguageSwitcher(it) } }
        }
        if (settings.osLanguageSwitcher) {
            item {
                ToggleSetting(
                    R.string.langemoji_lang_app_name_first_title,
                    stringResource(R.string.langemoji_lang_app_name_first_subtitle),
                    settings.subtypeAppNameFirst,
                    info = stringResource(R.string.langemoji_lang_app_name_first_info),
                    default = SettingsDefaults.subtypeAppNameFirst,
                ) { scope.launch { repository.setSubtypeAppNameFirst(it) } }
            }
            item {
                NavRow(
                    R.string.langemoji_lang_subtype_enabler_title,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        stringResource(R.string.langemoji_lang_subtype_enabler_subtitle)
                    } else {
                        stringResource(R.string.langemoji_lang_subtype_enabler_legacy_subtitle)
                    },
                ) { openSubtypeEnabler(context) }
            }
        }
    }
    // Conjunct-aware backspace used to live here as one switch across every
    // cluster-forming script at once. It is per language now, on each language's
    // own screen, next to that language's other options — see
    // [ConjunctBackspaceSetting].
}

// ---- emoji ----

@Composable
private fun EmojiSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    // Examples in the user's own languages: "type জন্মদিন" only reads as proof
    // the feature works to someone who reads Bengali.
    val languageIds = settings.enabledLanguages.map { it.id }
    val birthdayWord = EmojiSearchExamples.one(EmojiSearchExamples.birthday, languageIds)
    SettingsGroup(stringResource(R.string.langemoji_emoji_access_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_emoji_toolbar_title,
                stringResource(R.string.langemoji_emoji_toolbar_subtitle),
                settings.emojiToolbar,
                info = stringResource(R.string.langemoji_emoji_toolbar_info),
                default = SettingsDefaults.emojiToolbar,
            ) { scope.launch { repository.setEmojiToolbar(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_full_bleed_title,
                stringResource(R.string.langemoji_emoji_full_bleed_subtitle),
                settings.emojiFullBleed,
                info = stringResource(R.string.langemoji_emoji_full_bleed_info),
                default = SettingsDefaults.emojiFullBleed,
            ) { scope.launch { repository.setEmojiFullBleed(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_suggestions_title)) {
        item {
            ToggleSetting(
                R.string.langemoji_emoji_prediction_title,
                stringResource(R.string.langemoji_emoji_prediction_subtitle),
                settings.emojiPrediction,
                info = stringResource(R.string.langemoji_emoji_prediction_info, birthdayWord),
                default = SettingsDefaults.emojiPrediction,
            ) { scope.launch { repository.setEmojiPrediction(it) } }
        }
        if (settings.emojiPrediction) {
            item {
                ChoiceSetting(
                    title = R.string.langemoji_emoji_insert_mode_title,
                    subtitle = stringResource(R.string.langemoji_emoji_insert_mode_subtitle),
                    info = stringResource(R.string.langemoji_emoji_insert_mode_info),
                    options = listOf(
                        EmojiInsertMode.REPLACE to
                            stringResource(R.string.langemoji_emoji_insert_replace_label),
                        EmojiInsertMode.APPEND to
                            stringResource(R.string.langemoji_emoji_insert_append_label),
                    ),
                    selected = settings.emojiInsertMode,
                    default = SettingsDefaults.emojiInsertMode,
                ) { scope.launch { repository.setEmojiInsertMode(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_skin_tone_group_title)) {
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_skin_tone_title,
                subtitle = stringResource(R.string.langemoji_emoji_skin_tone_subtitle),
                info = stringResource(R.string.langemoji_emoji_skin_tone_info),
                options = listOf(
                    EmojiSkinTone.NONE to "✋",
                    EmojiSkinTone.LIGHT to "✋🏻",
                    EmojiSkinTone.MEDIUM_LIGHT to "✋🏼",
                    EmojiSkinTone.MEDIUM to "✋🏽",
                    EmojiSkinTone.MEDIUM_DARK to "✋🏾",
                    EmojiSkinTone.DARK to "✋🏿",
                ),
                selected = settings.emoji.defaultSkinTone,
                default = SettingsDefaults.emoji.defaultSkinTone,
            ) { scope.launch { repository.setEmojiDefaultSkinTone(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_tone_override_title,
                stringResource(R.string.langemoji_emoji_tone_override_subtitle),
                settings.emoji.toneOverrideByLastUsed,
                info = stringResource(R.string.langemoji_emoji_tone_override_info),
                default = SettingsDefaults.emoji.toneOverrideByLastUsed,
            ) { scope.launch { repository.setEmojiToneOverrideByLastUsed(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_panel_title)) {
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_grid_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_grid_size_subtitle),
                value = settings.emoji.gridCellSize.toFloat(),
                range = EmojiGridCellSizeRange.first.toFloat()..
                    EmojiGridCellSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_grid_size_info),
                default = SettingsDefaults.emoji.gridCellSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridCellSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_size_title,
                subtitle = stringResource(R.string.langemoji_emoji_size_subtitle),
                value = settings.emoji.gridEmojiSize.toFloat(),
                range = EmojiGridEmojiSizeRange.first.toFloat()..
                    EmojiGridEmojiSizeRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_size_info),
                default = SettingsDefaults.emoji.gridEmojiSize.toFloat(),
            ) { scope.launch { repository.setEmojiGridEmojiSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                title = R.string.langemoji_emoji_recents_title,
                subtitle = stringResource(R.string.langemoji_emoji_recents_subtitle),
                value = settings.emoji.recentsLimit.toFloat(),
                range = EmojiRecentsRange.first.toFloat()..EmojiRecentsRange.last.toFloat(),
                display = { numberFormat.format(it.roundToInt()) },
                info = stringResource(R.string.langemoji_emoji_recents_info),
                default = SettingsDefaults.emoji.recentsLimit.toFloat(),
            ) { scope.launch { repository.setEmojiRecentsLimit(it.roundToInt()) } }
        }
        item {
            ActionRow(
                title = R.string.langemoji_emoji_clear_history_title,
                subtitle = stringResource(R.string.langemoji_emoji_clear_history_subtitle),
                action = stringResource(CommonR.string.common_clear),
                confirm = stringResource(R.string.langemoji_emoji_clear_history_confirm),
                lock = AppLockTargets["action_clear_emoji_history"],
            ) { scope.launch { repository.clearEmojiHistory() } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_close_after_insert_title,
                stringResource(R.string.langemoji_emoji_close_after_insert_subtitle),
                settings.emoji.closeAfterInsert,
                info = stringResource(R.string.langemoji_emoji_close_after_insert_info),
                default = SettingsDefaults.emoji.closeAfterInsert,
            ) { scope.launch { repository.setEmojiCloseAfterInsert(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_tab_mode_title,
                subtitle = stringResource(R.string.langemoji_emoji_tab_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_tab_mode_info),
                options = listOf(
                    EmojiTabMode.RECENTS to stringResource(R.string.langemoji_emoji_recent_label),
                    EmojiTabMode.MOST_USED to
                        stringResource(R.string.langemoji_emoji_most_used_label),
                ),
                selected = settings.emojiTabMode,
                default = SettingsDefaults.emojiTabMode,
            ) { scope.launch { repository.setEmojiTabMode(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_clear_recents_title,
                stringResource(R.string.langemoji_emoji_clear_recents_subtitle),
                settings.emojiClearRecentsButton,
                info = stringResource(R.string.langemoji_emoji_clear_recents_info),
                default = SettingsDefaults.emojiClearRecentsButton,
            ) { scope.launch { repository.setEmojiClearRecentsButton(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_kaomoji_title,
                stringResource(R.string.langemoji_emoji_kaomoji_subtitle),
                settings.emoji.kaomojiTabs,
                info = stringResource(R.string.langemoji_emoji_kaomoji_info),
                default = SettingsDefaults.emoji.kaomojiTabs,
            ) { scope.launch { repository.setEmojiKaomojiTabs(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_long_press_name_title,
                stringResource(R.string.langemoji_emoji_long_press_name_subtitle),
                settings.emojiLongPressName,
                info = stringResource(R.string.langemoji_emoji_long_press_name_info),
                default = SettingsDefaults.emojiLongPressName,
            ) { scope.launch { repository.setEmojiLongPressName(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_animated_title,
                stringResource(R.string.langemoji_emoji_animated_subtitle),
                settings.emoji.animated,
                info = stringResource(R.string.langemoji_emoji_animated_info),
                default = SettingsDefaults.emoji.animated,
            ) { scope.launch { repository.setAnimatedEmoji(it) } }
        }
        item {
            ToggleSetting(
                R.string.langemoji_emoji_sticker_title,
                stringResource(R.string.langemoji_emoji_sticker_subtitle),
                settings.emoji.sendAsSticker,
                info = stringResource(R.string.langemoji_emoji_sticker_info),
                default = SettingsDefaults.emoji.sendAsSticker,
            ) { scope.launch { repository.setSendEmojiAsSticker(it) } }
        }
        item {
            NavRow(
                R.string.langemoji_emoji_keywords_title,
                stringResource(R.string.langemoji_emoji_keywords_subtitle),
                route = "emojikeywords",
            ) { onNavigate("emojikeywords") }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_row_title)) {
        item {
            ChoiceSetting(
                title = R.string.langemoji_emoji_row_title,
                subtitle = stringResource(R.string.langemoji_emoji_bar_mode_subtitle),
                info = stringResource(R.string.langemoji_emoji_bar_mode_info),
                options = listOf(
                    EmojiBarMode.OFF to stringResource(CommonR.string.common_off),
                    EmojiBarMode.BUTTON to
                        stringResource(R.string.langemoji_emoji_bar_button_label),
                    EmojiBarMode.ALWAYS to
                        stringResource(R.string.langemoji_emoji_bar_always_label),
                ),
                selected = settings.emojiBarMode,
                default = SettingsDefaults.emojiBarMode,
            ) { scope.launch { repository.setEmojiBarMode(it) } }
        }
        if (settings.emojiBarMode != EmojiBarMode.OFF) {
            item {
                ChoiceSetting(
                    title = R.string.langemoji_emoji_bar_content_title,
                    subtitle = stringResource(R.string.langemoji_emoji_bar_content_subtitle),
                    options = listOf(
                        EmojiBarContent.MOST_USED to
                            stringResource(R.string.langemoji_emoji_most_used_label),
                        EmojiBarContent.RECENTS to
                            stringResource(R.string.langemoji_emoji_recent_label),
                        EmojiBarContent.FAVOURITES to
                            stringResource(R.string.langemoji_emoji_favourites_label),
                    ),
                    selected = settings.emojiBarContent,
                    default = SettingsDefaults.emojiBarContent,
                ) { scope.launch { repository.setEmojiBarContent(it) } }
            }
            item {
                SliderSetting(
                    title = R.string.langemoji_emoji_bar_count_title,
                    subtitle = stringResource(R.string.langemoji_emoji_bar_count_subtitle),
                    value = settings.emoji.barCount.toFloat(),
                    range = EmojiBarCountRange.first.toFloat()..EmojiBarCountRange.last.toFloat(),
                    display = { numberFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.langemoji_emoji_bar_count_info),
                    default = SettingsDefaults.emoji.barCount.toFloat(),
                ) { scope.launch { repository.setEmojiBarCount(it.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.langemoji_emoji_bar_scroll_title,
                    stringResource(R.string.langemoji_emoji_bar_scroll_subtitle),
                    settings.emoji.barScrollable,
                    info = stringResource(R.string.langemoji_emoji_bar_scroll_info),
                    default = SettingsDefaults.emoji.barScrollable,
                ) { scope.launch { repository.setEmojiBarScrollable(it) } }
            }
        }
    }
    if (settings.emojiBarMode == EmojiBarMode.ALWAYS) {
        CaptionText(stringResource(R.string.langemoji_emoji_row_position_body))
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_style_title)) {
        item {
            val context = LocalContext.current
            // Bumped after an import so the preview re-resolves the (same-named) file.
            var fontRefresh by remember { mutableIntStateOf(0) }
            // Where the Emoji font addon's Use button lands: the title resource
            // is the highlight key every resource-titled row now carries.
            ChoiceSetting(
                title = R.string.langemoji_emoji_font_title,
                subtitle = stringResource(R.string.langemoji_emoji_font_subtitle),
                info = stringResource(R.string.langemoji_emoji_font_info),
                options = buildList {
                    add(
                        EmojiFontChoice.SYSTEM to
                            stringResource(R.string.langemoji_emoji_font_system_label),
                    )
                    // Noto is not a file of the app's own: it comes from the
                    // same Play services font provider as the Google fonts, and
                    // without it the choice draws the system set. Kept when it
                    // is the standing choice, so a phone that lost Play
                    // services still shows what it is set to and can move off.
                    if (PlayServices.hasFontProvider(context) ||
                        settings.emojiFont == EmojiFontChoice.NOTO
                    ) {
                        add(
                            EmojiFontChoice.NOTO to
                                stringResource(R.string.langemoji_emoji_font_noto_label),
                        )
                    }
                    add(
                        EmojiFontChoice.INSTALLED to
                            stringResource(R.string.langemoji_emoji_font_installed_label),
                    )
                    add(EmojiFontChoice.CUSTOM to stringResource(CommonR.string.common_custom))
                },
                selected = settings.emojiFont,
                default = SettingsDefaults.emojiFont,
            ) { scope.launch { repository.setEmojiFont(it) } }
            EmojiFontPreviewRow(
                choice = settings.emojiFont,
                installedId = settings.emojiFontInstalled.installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                refresh = fontRefresh,
            )
            // The direct fetch of a current Noto, next to the choice it fixes:
            // "Google" above can only ask the system font provider, which
            // serves the build it has rather than the newest one.
            EmojiFontDownloadRow(
                installedId = settings.emojiFontInstalled.installedId,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { fontId ->
                scope.launch { repository.setInstalledEmojiFont(fontId) }
                fontRefresh++
            }
            if (settings.emojiFont == EmojiFontChoice.INSTALLED) {
                InstalledEmojiFontList(repository, settings)
            }
            if (settings.emojiFont == EmojiFontChoice.CUSTOM) {
                val importEmojiFont = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            importFontFile(context, uri, KeyboardFonts.customEmojiFontFile(context))
                        }
                        fontRefresh++
                    }
                }
                val imported = KeyboardFonts.customEmojiFontFile(context).exists()
                if (!imported) {
                    Text(
                        stringResource(R.string.langemoji_emoji_font_missing_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                val importLabel = if (imported) {
                    stringResource(R.string.langemoji_emoji_font_replace_action)
                } else {
                    stringResource(R.string.langemoji_emoji_font_import_action)
                }
                OutlinedButton(
                    onClick = { importEmojiFont.launch(FONT_MIME_TYPES) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(importLabel) }
                Spacer(Modifier.height(8.dp))
            }
        }
        // Straight after the picker, because "Installed" is the option that
        // needs a font to have arrived from somewhere first.
        item { AddonStoreRow(AddonType.EmojiFont, onNavigate) }
        item {
            // The phone is always the one to blame here: an emoji the chosen
            // font is missing is drawn in the phone's own emoji font instead,
            // so the only emoji that stay blank are the ones neither has.
            val ownFont = settings.emojiFont != EmojiFontChoice.SYSTEM
            val hideInfo = stringResource(R.string.langemoji_emoji_hide_unrenderable_info)
            val ownFontInfo =
                stringResource(R.string.langemoji_emoji_hide_unrenderable_own_font_info)
            ToggleSetting(
                R.string.langemoji_emoji_hide_unrenderable_title,
                stringResource(R.string.langemoji_emoji_hide_unrenderable_subtitle),
                settings.emoji.hideUnrenderable,
                info = if (ownFont) "$hideInfo\n\n$ownFontInfo" else hideInfo,
                default = SettingsDefaults.emoji.hideUnrenderable,
            ) { scope.launch { repository.setHideUnrenderableEmoji(it) } }
        }
    }
    CaptionText(stringResource(R.string.langemoji_emoji_tip_body))
}

/**
 * The emoji faces in the font library, with the one in use ticked.
 *
 * Emoji fonts live in the same [FontStore] as the text faces — same files, same
 * lifecycle — but are listed apart, since drawing key labels in a colour emoji
 * font is not a choice anyone makes on purpose.
 */
@Composable
private fun InstalledEmojiFontList(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { FontStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val fonts = remember(revision) { store.emojiFonts() }

    if (fonts.isEmpty()) {
        CaptionText(stringResource(R.string.langemoji_emoji_fonts_empty))
        return
    }
    for (font in fonts) {
        val selected = settings.emojiFontInstalled.installedId == font.id
        WmRow(
            title = font.name,
            supporting = font.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription =
                                stringResource(R.string.langemoji_emoji_font_selected_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            repository.forgetInstalledEmojiFont(font.id)
                            withContext(Dispatchers.IO) { store.delete(font.id) }
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(
                                R.string.langemoji_emoji_font_delete_desc,
                                font.name,
                            ),
                        )
                    }
                }
            },
            onClick = { scope.launch { repository.setInstalledEmojiFont(font.id) } },
        )
    }
}

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
private fun DictionarySettings(repository: SettingsRepository) {
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

    Text(
        stringResource(R.string.backup_dictionary_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    // Words seen exactly once. Older versions learned every word the first time
    // it was committed, so for anyone upgrading this is where the swipe
    // misfires and mistyped words are — the clean-out the dictionary needed and
    // had no way to do short of deleting entries one at a time. Words the user
    // added by hand carry a boost far above 1 and are never in here.
    val seenOnce = remember(words) { words.filter { it.second <= 1 }.map { it.first } }
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { showAdd = true }) {
            Text(stringResource(R.string.backup_add_word_action))
        }
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

// ---- suggestion blacklist ----

/**
 * The never-suggest word list, stored in settings. A blacklisted word is kept
 * out of the suggestion strip and never used as an autocorrect target, but can
 * still be typed and committed normally. Matched case-insensitively.
 */
@Composable
private fun BlacklistSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val words = remember(settings.suggestionBlacklist) {
        settings.suggestionBlacklist.sorted()
    }
    var showAdd by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.backup_blacklist_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Button(
        onClick = { showAdd = true },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text(stringResource(R.string.backup_add_word_action)) }
    Spacer(Modifier.height(12.dp))
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
private fun PhoneFormatSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val formats = remember(settings.clipboard.phoneFormats) {
        settings.clipboard.phoneFormats.sorted()
    }
    val masks = remember(formats) { PhoneFormats.parseAll(formats) }
    var showAdd by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf("") }

    Text(
        stringResource(R.string.phoneformats_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Button(
        onClick = { showAdd = true },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text(stringResource(R.string.phoneformats_add_action)) }
    Spacer(Modifier.height(12.dp))
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

// ---- backup ----

/** Human name for a bundle section, used in toggles and the import dialog. */
@StringRes
internal fun sectionLabelRes(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label
    ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label
    ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label
    ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label
    ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label
    ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label
    ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label
    ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label
    ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label
    ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label
    ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label
}

internal fun sectionLabel(context: Context, section: ConfigBackup.Section): String =
    context.getString(sectionLabelRes(section))

/**
 * The same name for the middle of a sentence ("Restored themes, snippets.").
 * A translation cannot be lowercased in code, so each name carries its own
 * lower-case value.
 */
internal fun sectionLabelLowercase(context: Context, section: ConfigBackup.Section): String =
    context.getString(
        when (section) {
            ConfigBackup.Section.SETTINGS -> R.string.backup_section_settings_label_lowercase
            ConfigBackup.Section.THEMES -> R.string.backup_section_themes_label_lowercase
            ConfigBackup.Section.DICTIONARY -> R.string.backup_section_dictionary_label_lowercase
            ConfigBackup.Section.CLIPBOARD -> R.string.backup_section_clipboard_label_lowercase
            ConfigBackup.Section.SNIPPETS -> R.string.backup_section_snippets_label_lowercase
            ConfigBackup.Section.STICKERS -> R.string.backup_section_stickers_label_lowercase
            ConfigBackup.Section.ICONS -> R.string.backup_section_icons_label_lowercase
            ConfigBackup.Section.WORDLISTS -> R.string.backup_section_wordlists_label_lowercase
            ConfigBackup.Section.ADDONS -> R.string.backup_section_addons_label_lowercase
            ConfigBackup.Section.EMOJI -> R.string.backup_section_emoji_label_lowercase
            ConfigBackup.Section.STATISTICS -> R.string.backup_section_statistics_label_lowercase
        },
    )

@PluralsRes
private fun sectionCountPlural(section: ConfigBackup.Section): Int = when (section) {
    ConfigBackup.Section.SETTINGS -> R.plurals.backup_section_settings_count
    ConfigBackup.Section.THEMES -> R.plurals.backup_section_themes_count
    ConfigBackup.Section.DICTIONARY -> R.plurals.backup_section_dictionary_count
    ConfigBackup.Section.CLIPBOARD -> R.plurals.backup_section_clipboard_count
    ConfigBackup.Section.SNIPPETS -> R.plurals.backup_section_snippets_count
    ConfigBackup.Section.STICKERS -> R.plurals.backup_section_stickers_count
    ConfigBackup.Section.ICONS -> R.plurals.backup_section_icons_count
    ConfigBackup.Section.WORDLISTS -> R.plurals.backup_section_wordlists_count
    ConfigBackup.Section.ADDONS -> R.plurals.backup_section_addons_count
    ConfigBackup.Section.EMOJI -> R.plurals.backup_section_emoji_count
    ConfigBackup.Section.STATISTICS -> R.plurals.backup_section_statistics_count
}

/** "3 themes", "1 snippet": the count line shown per section on import. */
internal fun sectionSummary(context: Context, section: ConfigBackup.Section, count: Int): String =
    context.resources.getQuantityString(sectionCountPlural(section), count, count)

/** A file picked for import, once we know which of the two formats it is. */
private sealed interface PendingImport {
    val text: String
    data class Config(override val text: String) : PendingImport
    data class Legacy(override val text: String) : PendingImport
}

/**
 * Where [hours] sits on [AutoBackupIntervals], for the slider's thumb.
 *
 * Nearest rather than exact: a value stored before the ladder existed, or by a
 * restored backup from a build with a different one, still has to put the thumb
 * somewhere sensible instead of snapping to the first stop.
 */
private fun intervalSliderIndex(hours: Int): Int =
    AutoBackupIntervals.indices.minBy { kotlin.math.abs(AutoBackupIntervals[it] - hours) }

/** The ladder value under a slider position. */
private fun intervalAt(index: Float): Int =
    AutoBackupIntervals[index.roundToInt().coerceIn(AutoBackupIntervals.indices)]

/** "Every 6 hours", "Every day", "Every 3 days". */
private fun backupIntervalLabel(context: Context, hours: Int): String =
    if (hours % 24 == 0) {
        context.resources.getQuantityString(
            R.plurals.backup_auto_interval_days,
            hours / 24,
            hours / 24,
        )
    } else {
        context.resources.getQuantityString(R.plurals.backup_auto_interval_hours, hours, hours)
    }

/** One sentence for a recorded failure, or null when the last run was fine. */
private fun autoBackupErrorText(context: Context, error: String): String? = when (error) {
    "" -> null
    SinkError.PERMISSION_LOST.name -> context.getString(R.string.backup_auto_error_permission)
    SinkError.TARGET_MISSING.name -> context.getString(R.string.backup_auto_error_target)
    SinkError.OUT_OF_SPACE.name -> context.getString(R.string.backup_auto_error_space)
    else -> context.getString(R.string.backup_auto_error_io)
}

/**
 * A text field backed by the settings store, for the handful of backup values
 * that are typed rather than picked.
 *
 * Same shape and same reason as the layout editor's `SheetField`: the value is
 * read back out of the repository a frame or more after the keystroke that
 * caused it, and fed straight back in it rewinds the text and the cursor
 * mid-word. The text lives here, and an incoming value is taken only while
 * nothing of ours is in flight.
 *
 * [password] masks the text and adds the reveal button. It also sets the field
 * to a password type, which matters more here than it usually would: this is
 * the keyboard, and an ordinary field would learn the passphrase into the very
 * dictionary the backup is about to carry.
 */
@Composable
private fun StoredTextField(
    label: String,
    value: String,
    supporting: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var pending by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    when {
        pending == null -> if (value != text) text = value
        value == pending -> pending = null
    }
    val masked = password && !visible
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            pending = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = if (supporting.isEmpty()) null else ({ Text(supporting) }),
        singleLine = true,
        visualTransformation =
        if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        trailingIcon = if (!password) {
            null
        } else {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (visible) {
                                R.string.backup_auto_passphrase_hide
                            } else {
                                R.string.backup_auto_passphrase_show
                            },
                        ),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The activity a composable is drawn in, by unwrapping the context.
 *
 * `:core:common` has one of these, and it is `internal` there, so it stops at
 * that module's boundary. Needed here for the one thing on this screen that has
 * to launch a system consent screen.
 */
private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * Which of the three destinations backups go to.
 *
 * Google Drive is left out of the list entirely on a build with no Play
 * services rather than shown and refused: on F-Droid there is nothing behind it
 * at all, and a choice that cannot be chosen is worse than no choice.
 */
@Composable
private fun DestinationRow(
    selected: BackupDestination,
    onChange: (BackupDestination) -> Unit,
) {
    val options = buildList {
        add(BackupDestination.FOLDER to stringResource(R.string.backup_auto_dest_folder))
        add(BackupDestination.WEBDAV to stringResource(R.string.backup_auto_dest_webdav))
        add(BackupDestination.S3 to stringResource(R.string.backup_auto_dest_s3))
        add(BackupDestination.FTP to stringResource(R.string.backup_auto_dest_ftp))
        if (driveAuthorizer().available) {
            add(BackupDestination.DRIVE to stringResource(R.string.backup_auto_dest_drive))
        }
        // Left out rather than shown and refused when this build has no client
        // id compiled in: there is nothing behind them, and a choice that
        // cannot be chosen is worse than no choice.
        if (BackupClients.dropboxAvailable) {
            add(BackupDestination.DROPBOX to stringResource(R.string.backup_auto_dest_dropbox))
        }
        if (BackupClients.oneDriveAvailable) {
            add(BackupDestination.ONEDRIVE to stringResource(R.string.backup_auto_dest_onedrive))
        }
    }
    ChoiceSetting(
        R.string.backup_auto_dest_title,
        subtitle = stringResource(R.string.backup_auto_dest_subtitle),
        options = options,
        selected = selected,
        default = SettingsDefaults.autoBackup.destination,
        onChange = onChange,
    )
}

/** Server, user and password for a WebDAV collection. */
@Composable
private fun WebDavRows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_url_label),
            value = auto.webDavUrl,
            supporting = stringResource(R.string.backup_auto_webdav_url_hint),
            keyboardType = KeyboardType.Uri,
        ) { entered -> scope.launch { repository.setAutoBackupWebDavUrl(entered) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_user_label),
            value = auto.webDavUser,
            supporting = "",
        ) { entered -> scope.launch { repository.setAutoBackupWebDavUser(entered) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_webdav_password_label),
            value = auto.webDavPassword,
            supporting = stringResource(R.string.backup_auto_webdav_password_hint),
            password = true,
        ) { entered -> scope.launch { repository.setAutoBackupWebDavPassword(entered) } }
        if (auto.webDavUrl.isNotEmpty() &&
            !auto.webDavUrl.startsWith("https://", ignoreCase = true)
        ) {
            // The credentials go in an Authorization header, which is the
            // password in base64. Over plain HTTP that is the password in the
            // clear, so the sink refuses it and this says why before the user
            // waits for a failed backup to find out.
            CaptionText(stringResource(R.string.backup_auto_webdav_needs_https))
        }
    }
}

/** Endpoint, bucket and key pair for an S3-compatible service. */
@Composable
private fun S3Rows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    val s3 = auto.s3
    fun update(change: S3Config) = scope.launch { repository.setAutoBackupS3(change) }

    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_endpoint_label),
            value = s3.endpoint,
            supporting = stringResource(R.string.backup_auto_s3_endpoint_hint),
            keyboardType = KeyboardType.Uri,
        ) { update(s3.copy(endpoint = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_bucket_label),
            value = s3.bucket,
            supporting = "",
        ) { update(s3.copy(bucket = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_region_label),
            value = s3.region,
            supporting = stringResource(R.string.backup_auto_s3_region_hint),
        ) { update(s3.copy(region = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_prefix_label),
            value = s3.prefix,
            supporting = stringResource(R.string.backup_auto_s3_prefix_hint),
        ) { update(s3.copy(prefix = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_key_label),
            value = s3.accessKeyId,
            supporting = "",
        ) { update(s3.copy(accessKeyId = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_s3_secret_label),
            value = s3.secretAccessKey,
            supporting = "",
            password = true,
        ) { update(s3.copy(secretAccessKey = it)) }
        ToggleSetting(
            R.string.backup_auto_s3_path_style_title,
            stringResource(R.string.backup_auto_s3_path_style_subtitle),
            s3.pathStyle,
            default = SettingsDefaults.autoBackup.s3.pathStyle,
        ) { on -> update(s3.copy(pathStyle = on)) }
        if (S3Sink.isCleartext(s3.endpoint)) {
            CaptionText(stringResource(R.string.backup_auto_s3_cleartext))
        }
    }
}

/** Host, login and directory for an FTP server. */
@Composable
private fun FtpRows(repository: SettingsRepository, auto: AutoBackupSettings) {
    val scope = rememberCoroutineScope()
    val ftp = auto.ftp
    fun update(change: FtpConfig) = scope.launch { repository.setAutoBackupFtp(change) }

    Column {
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_host_label),
            value = ftp.host,
            supporting = "",
            keyboardType = KeyboardType.Uri,
        ) { update(ftp.copy(host = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_port_label),
            value = ftp.port.toString(),
            supporting = "",
            keyboardType = KeyboardType.Number,
        ) { entered -> entered.toIntOrNull()?.let { update(ftp.copy(port = it)) } }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_user_label),
            value = ftp.user,
            supporting = "",
        ) { update(ftp.copy(user = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_password_label),
            value = ftp.password,
            supporting = "",
            password = true,
        ) { update(ftp.copy(password = it)) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_path_label),
            value = ftp.path,
            supporting = stringResource(R.string.backup_auto_ftp_path_hint),
        ) { update(ftp.copy(path = it)) }
        ToggleSetting(
            R.string.backup_auto_ftp_secure_title,
            stringResource(R.string.backup_auto_ftp_secure_subtitle),
            ftp.secure,
            default = SettingsDefaults.autoBackup.ftp.secure,
        ) { on -> update(ftp.copy(secure = on)) }
        if (!ftp.secure) {
            CaptionText(stringResource(R.string.backup_auto_ftp_cleartext))
        }
    }
}

/** Signing in to Dropbox or OneDrive, which is the same flow for both. */
@Composable
private fun OAuthRow(
    repository: SettingsRepository,
    destination: BackupDestination,
    token: String,
    clientId: String,
    @StringRes titleRes: Int,
    @StringRes infoRes: Int,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pending by BackupOAuth.result.collectAsStateWithLifecycle()

    // The browser comes back into a different activity, so the code arrives
    // here through a flow rather than an activity result.
    LaunchedEffect(pending) {
        val delivered = pending ?: return@LaunchedEffect
        if (delivered.destination != destination) return@LaunchedEffect
        BackupOAuth.consume()
        val code = delivered.code
        if (code == null) {
            onMessage(context.getString(R.string.backup_auto_oauth_cancelled))
            return@LaunchedEffect
        }
        val tokens = when (destination) {
            BackupDestination.DROPBOX -> BackupClients.dropbox()
            else -> BackupClients.oneDrive()
        }
        val refresh = withContext(Dispatchers.IO) {
            tokens?.exchangeCode(code, delivered.verifier, BackupOAuth.REDIRECT_URI)
        }
        if (refresh == null) {
            onMessage(context.getString(R.string.backup_auto_oauth_failed))
            return@LaunchedEffect
        }
        when (destination) {
            BackupDestination.DROPBOX -> repository.setAutoBackupDropboxToken(refresh)
            else -> repository.setAutoBackupOneDriveToken(refresh)
        }
    }

    Column {
        ListItem(
            headlineContent = { Text(stringResource(titleRes)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (token.isNotEmpty()) {
                            R.string.backup_auto_oauth_signed_in
                        } else {
                            R.string.backup_auto_oauth_signed_out
                        },
                    ),
                )
            },
        )
        OutlinedButton(
            onClick = {
                val activity = context.hostActivity() ?: return@OutlinedButton
                if (token.isNotEmpty()) {
                    scope.launch {
                        when (destination) {
                            BackupDestination.DROPBOX -> repository.setAutoBackupDropboxToken("")
                            else -> repository.setAutoBackupOneDriveToken("")
                        }
                    }
                } else if (!BackupOAuth.start(activity, destination, clientId)) {
                    onMessage(context.getString(R.string.backup_auto_oauth_no_browser))
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(
                    if (token.isNotEmpty()) {
                        R.string.backup_auto_oauth_sign_out
                    } else {
                        R.string.backup_auto_oauth_sign_in
                    },
                ),
            )
        }
        CaptionText(stringResource(infoRes))
    }
}

/** Authorizing this app's own hidden folder in the user's Google Drive. */
@Composable
private fun DriveRow(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val authorizer = remember { driveAuthorizer() }
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var asking by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // Whatever the result says, the only trustworthy answer is to ask
        // Google again.
        scope.launch { authorized = authorizer.authorized(context) }
    }

    LaunchedEffect(auto.destination) {
        authorized = authorizer.authorized(context)
    }

    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.backup_auto_drive_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        when (authorized) {
                            true -> R.string.backup_auto_drive_authorized
                            false -> R.string.backup_auto_drive_not_authorized
                            null -> R.string.backup_auto_drive_checking
                        },
                    ),
                )
            },
        )
        if (authorized != true) {
            OutlinedButton(
                enabled = !asking,
                onClick = {
                    val activity = context.hostActivity() ?: return@OutlinedButton
                    asking = true
                    scope.launch {
                        val granted = authorizer.authorize(activity) { sender ->
                            consent.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        asking = false
                        authorized = granted
                        if (granted) {
                            repository.setAutoBackupOutcome(ranAtMs = 0L, error = "")
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_auto_drive_authorize)) }
        }
        CaptionText(stringResource(R.string.backup_auto_drive_info))
    }
}

/**
 * The backup that runs without being asked.
 *
 * Every row here is inert until the chosen destination is usable, because there
 * is no default destination that would not be a place the user did not ask for.
 */
@Composable
private fun AutoBackupGroup(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onPickFolder: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    val configured = auto.destinationConfigured
    val encrypted = auto.encrypt && auto.passphrase.isNotEmpty()
    val personal = ConfigBackup.Section.DICTIONARY.id in auto.sections ||
        ConfigBackup.Section.CLIPBOARD.id in auto.sections

    SettingsGroup(stringResource(R.string.backup_auto_group_title)) {
        item {
            DestinationRow(auto.destination) { picked ->
                scope.launch {
                    repository.setAutoBackupDestination(picked)
                    AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                }
            }
        }
        when (auto.destination) {
            BackupDestination.FOLDER -> item {
                NavRow(
                    R.string.backup_auto_folder_title,
                    subtitle = stringResource(R.string.backup_auto_folder_subtitle),
                    value = if (auto.folderUri.isNotEmpty()) {
                        Uri.decode(auto.folderUri.substringAfterLast('/'))
                    } else {
                        stringResource(R.string.backup_auto_folder_none)
                    },
                    onClick = onPickFolder,
                )
            }
            BackupDestination.WEBDAV -> {
                item { WebDavRows(repository, auto) }
            }
            BackupDestination.DRIVE -> {
                item { DriveRow(repository, auto, onMessage) }
            }
            BackupDestination.S3 -> item { S3Rows(repository, auto) }
            BackupDestination.FTP -> item { FtpRows(repository, auto) }
            BackupDestination.DROPBOX -> item {
                OAuthRow(
                    repository = repository,
                    destination = BackupDestination.DROPBOX,
                    token = auto.dropboxRefreshToken,
                    clientId = BackupClients.dropboxClientId,
                    titleRes = R.string.backup_auto_dest_dropbox,
                    infoRes = R.string.backup_auto_dropbox_info,
                    onMessage = onMessage,
                )
            }
            BackupDestination.ONEDRIVE -> item {
                OAuthRow(
                    repository = repository,
                    destination = BackupDestination.ONEDRIVE,
                    token = auto.oneDriveRefreshToken,
                    clientId = BackupClients.oneDriveClientId,
                    titleRes = R.string.backup_auto_dest_onedrive,
                    infoRes = R.string.backup_auto_onedrive_info,
                    onMessage = onMessage,
                )
            }
        }
        item {
            ToggleSetting(
                R.string.backup_auto_enabled_title,
                stringResource(
                    if (configured) {
                        R.string.backup_auto_enabled_subtitle
                    } else {
                        R.string.backup_auto_enabled_needs_folder
                    },
                ),
                auto.enabled && configured,
                default = SettingsDefaults.autoBackup.enabled && configured,
            ) { on ->
                scope.launch {
                    repository.setAutoBackupEnabled(on)
                    AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                }
            }
        }
        if (auto.enabled && configured) {
            item {
                // The slider walks the ladder by index, so every stop is a value
                // somebody would choose and one a drag can actually land on. A
                // plain 1..168 range makes "once a day" a pixel-hunt.
                SliderSetting(
                    R.string.backup_auto_interval_title,
                    value = intervalSliderIndex(auto.intervalHours).toFloat(),
                    range = 0f..(AutoBackupIntervals.size - 1).toFloat(),
                    display = { backupIntervalLabel(context, intervalAt(it)) },
                    default = intervalSliderIndex(
                        SettingsDefaults.autoBackup.intervalHours,
                    ).toFloat(),
                ) { index ->
                    scope.launch {
                        repository.setAutoBackupIntervalHours(intervalAt(index))
                        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                    }
                }
            }
            item {
                SliderSetting(
                    R.string.backup_auto_keep_title,
                    subtitle = stringResource(R.string.backup_auto_keep_subtitle),
                    value = auto.keep.toFloat(),
                    range = AutoBackupKeepRange.first.toFloat()..
                        AutoBackupKeepRange.last.toFloat(),
                    display = { kept ->
                        context.resources.getQuantityString(
                            R.plurals.backup_auto_keep_value,
                            kept.roundToInt(),
                            kept.roundToInt(),
                        )
                    },
                    default = SettingsDefaults.autoBackup.keep.toFloat(),
                ) { kept -> scope.launch { repository.setAutoBackupKeep(kept.roundToInt()) } }
            }
            item {
                ToggleSetting(
                    R.string.backup_auto_charging_title,
                    stringResource(R.string.backup_auto_charging_subtitle),
                    auto.requireCharging,
                    info = stringResource(R.string.backup_auto_charging_info),
                    default = SettingsDefaults.autoBackup.requireCharging,
                ) { on ->
                    scope.launch {
                        repository.setAutoBackupRequireCharging(on)
                        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
                    }
                }
            }
            // A folder is storage on this device, so a network requirement there
            // would only ever stop a backup that costs nothing.
            if (auto.destination.needsNetwork) {
                item {
                    ToggleSetting(
                        R.string.backup_auto_unmetered_title,
                        stringResource(R.string.backup_auto_unmetered_subtitle),
                        auto.requireUnmetered,
                        info = stringResource(R.string.backup_auto_unmetered_info),
                        default = SettingsDefaults.autoBackup.requireUnmetered,
                    ) { on ->
                        scope.launch {
                            repository.setAutoBackupRequireUnmetered(on)
                            AutoBackupScheduler.sync(
                                context,
                                repository.settings.first().autoBackup,
                            )
                        }
                    }
                }
            }
        }
        item {
            ToggleSetting(
                R.string.backup_auto_encrypt_title,
                stringResource(R.string.backup_auto_encrypt_subtitle),
                auto.encrypt,
                info = stringResource(R.string.backup_auto_encrypt_info),
                default = SettingsDefaults.autoBackup.encrypt,
            ) { on -> scope.launch { repository.setAutoBackupEncrypt(on) } }
        }
        if (auto.encrypt) {
            item {
                StoredTextField(
                    label = stringResource(R.string.backup_auto_passphrase_label),
                    value = auto.passphrase,
                    supporting = "",
                    password = true,
                ) { entered -> scope.launch { repository.setAutoBackupPassphrase(entered) } }
            }
        }
    }

    // The one thing on this screen that has to be said out loud. Turning these
    // sections on for an automatic backup sends words the user typed, and things
    // they copied, off the device on a timer.
    if (personal && !encrypted) {
        CaptionText(stringResource(R.string.backup_auto_personal_warning))
    }

    SettingsGroup {
        item {
            OutlinedButton(
                enabled = configured && !running,
                onClick = {
                    running = true
                    scope.launch {
                        val outcome = AutoBackupRunner.run(context, repository, force = true)
                        running = false
                        onMessage(autoBackupOutcomeText(context, outcome))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(
                        if (running) R.string.backup_auto_running else R.string.backup_auto_now_action,
                    ),
                )
            }
        }
    }

    val error = autoBackupErrorText(context, auto.lastError)
    CaptionText(
        when {
            error != null -> error
            auto.lastRunAtMs > 0L -> stringResource(
                R.string.backup_auto_last_run,
                DateUtils.getRelativeTimeSpanString(auto.lastRunAtMs).toString(),
            )
            else -> stringResource(R.string.backup_auto_never)
        },
    )
}

/** One sentence for whatever a run turned out to be. */
private fun autoBackupOutcomeText(
    context: Context,
    outcome: AutoBackupRunner.Outcome,
): String = when (outcome) {
    is AutoBackupRunner.Outcome.Done -> if (outcome.skipped.isEmpty()) {
        context.getString(R.string.backup_auto_done, outcome.name)
    } else {
        context.getString(
            R.string.backup_auto_done_skipped,
            outcome.name,
            outcome.skipped.joinToString { sectionLabelLowercase(context, it) },
        )
    }
    AutoBackupRunner.Outcome.Locked -> context.getString(R.string.backup_auto_locked)
    AutoBackupRunner.Outcome.Skipped -> context.getString(R.string.backup_auto_skipped)
    is AutoBackupRunner.Outcome.Failed ->
        autoBackupErrorText(context, outcome.reason.name)
            ?: context.getString(R.string.backup_auto_error_io)
}

@Composable
private fun BackupSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // What a backup contains, stored rather than remembered. It used to be
    // eleven `remember`s that reset on every visit, which was tolerable while
    // the only way to make a backup was to press a button and pick a file. The
    // automatic backup has nobody there to set them, so they have to persist.
    val auto = settings.autoBackup
    val sections = auto.sectionSet
    val includeSecrets = auto.includeSecrets

    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<PendingImport?>(null) }

    fun setSection(section: ConfigBackup.Section, on: Boolean) {
        scope.launch {
            repository.setAutoBackupSections(
                if (on) sections + section else sections - section,
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCancellable {
                val text = repository.exportConfig(
                    sections = sections,
                    includeSecrets = includeSecrets,
                    appVersion = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(text.toByteArray())
                    }
                }
            }.isSuccess
            message = when {
                !ok -> context.getString(R.string.backup_export_write_error)
                ConfigBackup.Section.SETTINGS in sections && includeSecrets ->
                    context.getString(R.string.backup_export_done_with_keys)
                else -> context.getString(R.string.backup_export_done)
            }
        }
    }

    // Import reads the file first and asks before writing: restoring is not
    // something to discover you have done. Both the full-config bundle and the
    // older settings-only file are accepted.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()?.firstJsonDocument()
            }
            confirmImport = when {
                text == null -> {
                    message = context.getString(R.string.backup_import_read_error); null
                }
                ConfigBackup.decode(text) != null -> PendingImport.Config(text)
                SettingsBackup.decode(text) != null -> PendingImport.Legacy(text)
                else -> {
                    message = context.getString(R.string.backup_not_a_backup); null
                }
            }
        }
    }

    // Picking a folder is what arms the automatic backup, so the grant is taken
    // here and the switch is useless without it.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!taken) {
            message = context.getString(R.string.backup_auto_folder_denied)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            repository.setAutoBackupFolderUri(uri.toString())
            AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
        }
    }

    Text(
        stringResource(R.string.backup_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    AutoBackupGroup(
        repository = repository,
        auto = auto,
        onPickFolder = { folderLauncher.launch(null) },
        onMessage = { message = it },
    )

    SettingsGroup(stringResource(R.string.backup_include_group_title)) {
        item {
            ToggleSetting(
                R.string.backup_section_settings_label,
                stringResource(R.string.backup_include_settings_subtitle),
                ConfigBackup.Section.SETTINGS in sections,
                default = ConfigBackup.Section.SETTINGS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.SETTINGS, it) }
        }
        if (ConfigBackup.Section.SETTINGS in sections) {
            item {
                ToggleSetting(
                    R.string.backup_include_secrets_title,
                    stringResource(R.string.backup_include_secrets_subtitle),
                    includeSecrets,
                    info = stringResource(R.string.backup_include_secrets_info),
                    default = SettingsDefaults.autoBackup.includeSecrets,
                ) { on -> scope.launch { repository.setAutoBackupIncludeSecrets(on) } }
            }
        }
        item {
            ToggleSetting(
                R.string.backup_section_themes_label,
                stringResource(R.string.backup_include_themes_subtitle),
                ConfigBackup.Section.THEMES in sections,
                default = ConfigBackup.Section.THEMES.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_themes_info),
            ) { setSection(ConfigBackup.Section.THEMES, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_dictionary_label,
                stringResource(R.string.backup_include_dictionary_subtitle),
                ConfigBackup.Section.DICTIONARY in sections,
                default = ConfigBackup.Section.DICTIONARY.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_dictionary_info),
            ) { setSection(ConfigBackup.Section.DICTIONARY, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_clipboard_label,
                stringResource(R.string.backup_include_clipboard_subtitle),
                ConfigBackup.Section.CLIPBOARD in sections,
                default = ConfigBackup.Section.CLIPBOARD.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_clipboard_info),
            ) { setSection(ConfigBackup.Section.CLIPBOARD, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_snippets_label,
                stringResource(R.string.backup_include_snippets_subtitle),
                ConfigBackup.Section.SNIPPETS in sections,
                default = ConfigBackup.Section.SNIPPETS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.SNIPPETS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_stickers_label,
                stringResource(R.string.backup_include_stickers_subtitle),
                ConfigBackup.Section.STICKERS in sections,
                default = ConfigBackup.Section.STICKERS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_stickers_info),
            ) { setSection(ConfigBackup.Section.STICKERS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_icons_label,
                stringResource(R.string.backup_include_icons_subtitle),
                ConfigBackup.Section.ICONS in sections,
                default = ConfigBackup.Section.ICONS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_icons_info),
            ) { setSection(ConfigBackup.Section.ICONS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_wordlists_label,
                stringResource(R.string.backup_include_wordlists_subtitle),
                ConfigBackup.Section.WORDLISTS in sections,
                default = ConfigBackup.Section.WORDLISTS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.WORDLISTS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_addons_label,
                stringResource(R.string.backup_include_addons_subtitle),
                ConfigBackup.Section.ADDONS in sections,
                default = ConfigBackup.Section.ADDONS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
                info = stringResource(R.string.backup_include_addons_info),
            ) { setSection(ConfigBackup.Section.ADDONS, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_emoji_label,
                stringResource(R.string.backup_include_emoji_subtitle),
                ConfigBackup.Section.EMOJI in sections,
                default = ConfigBackup.Section.EMOJI.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.EMOJI, it) }
        }
        item {
            ToggleSetting(
                R.string.backup_section_statistics_label,
                stringResource(R.string.backup_include_statistics_subtitle),
                ConfigBackup.Section.STATISTICS in sections,
                default = ConfigBackup.Section.STATISTICS.id in
                    AutoBackupSettings.DEFAULT_SECTIONS,
            ) { setSection(ConfigBackup.Section.STATISTICS, it) }
        }
    }

    SettingsGroup {
        item {
            OutlinedButton(
                enabled = sections.isNotEmpty(),
                // The one control here that copies the user's data out of the
                // app, API keys included, so it is one of the things the
                // fingerprint lock can be pointed at.
                onClick = rememberLockGuard(AppLockTargets["action_export_settings"]) {
                    // Datestamp the default name so successive backups don't
                    // overwrite each other and each file self-labels when it was made.
                    // Locale.US, not the default: on a Thai-Buddhist locale the
                    // platform formatter stamps 2569 for 2026, and a filename that
                    // sorts by date has to mean the same thing everywhere.
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch(
                        "wmkeyboard-backup-$stamp.${ConfigBackup.FILE_EXTENSION}",
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_export_action)) }
        }
    }

    SettingsGroup(stringResource(R.string.backup_import_group_title)) {
        item {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.backup_import_action)) }
        }
    }
    CaptionText(stringResource(R.string.backup_import_note))
    Spacer(Modifier.height(16.dp))

    when (val pending = confirmImport) {
        is PendingImport.Config -> {
            val parsed = remember(pending.text) { ConfigBackup.decode(pending.text) }
            val counts = remember(pending.text) { parsed?.let { repository.describeConfig(it) }.orEmpty() }
            val hasSecrets = remember(pending.text) { parsed?.let { repository.configContainsSecrets(it) } ?: false }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            append(context.getString(R.string.backup_import_contains))
                            append("\n")
                            for ((section, count) in counts) {
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.backup_import_section_line,
                                        sectionLabel(context, section),
                                        sectionSummary(context, section, count),
                                    ),
                                )
                            }
                            append("\n\n")
                            append(context.getString(R.string.backup_import_merge_note))
                            if (hasSecrets) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importConfig(pending.text)) {
                                is SettingsRepository.ConfigImportResult.Applied -> buildString {
                                    if (result.restored.isEmpty()) {
                                        append(context.getString(R.string.backup_restore_nothing))
                                    } else {
                                        append(
                                            context.getString(
                                                R.string.backup_restore_done,
                                                result.restored.joinToString {
                                                    sectionLabelLowercase(context, it)
                                                },
                                            ),
                                        )
                                    }
                                    if (result.settingsFailed) {
                                        append("\n\n")
                                        append(
                                            context.getString(R.string.backup_restore_settings_failed),
                                        )
                                    }
                                }
                                SettingsRepository.ConfigImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        is PendingImport.Legacy -> {
            val parsed = remember(pending.text) { SettingsBackup.decode(pending.text) }
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                title = { Text(stringResource(R.string.backup_import_settings_confirm_title)) },
                text = {
                    Text(
                        buildString {
                            val entries = parsed?.entries?.size ?: 0
                            append(
                                context.resources.getQuantityString(
                                    R.plurals.backup_import_settings_overwrite,
                                    entries,
                                    entries,
                                ),
                            )
                            if (parsed?.containsSecrets == true) {
                                append("\n\n")
                                append(context.getString(R.string.backup_import_api_keys_note))
                            }
                            val skipped = parsed?.skipped ?: 0
                            if (skipped > 0) {
                                append("\n\n")
                                append(
                                    context.resources.getQuantityString(
                                        R.plurals.backup_import_settings_skipped,
                                        skipped,
                                        skipped,
                                    ),
                                )
                            }
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = null
                        scope.launch {
                            message = when (val result = repository.importSettings(pending.text)) {
                                is SettingsRepository.ImportResult.Applied ->
                                    context.resources.getQuantityString(
                                        R.plurals.backup_restore_settings_count,
                                        result.settings,
                                        result.settings,
                                    )
                                SettingsRepository.ImportResult.RolledBack ->
                                    context.getString(R.string.backup_restore_rolled_back)
                                SettingsRepository.ImportResult.NotABackup ->
                                    context.getString(R.string.backup_not_a_settings_backup)
                            }
                        }
                    }) { Text(stringResource(CommonR.string.common_import)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                },
            )
        }
        null -> {}
    }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

// ---- custom dictionaries ----

/** Human name for a language id, used as the word-list group header. */
private fun languageLabel(langId: String): String =
    LanguageRegistry.byId(langId).englishName

/** One imported list: the file plus how many words it parsed to. */
private data class WordListEntry(val file: java.io.File, val words: Int)

@Composable
private fun CustomDictionarySettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var lists by remember {
        mutableStateOf<Map<String, List<WordListEntry>>>(emptyMap())
    }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var urlDialogFor by remember { mutableStateOf<String?>(null) }

    // Counting words means reading every list, so it never runs on the main
    // thread — the screen draws empty for a moment and fills in.
    suspend fun refresh() {
        lists = withContext(Dispatchers.IO) {
            // The enabled languages plus any language that still has lists on
            // disk. Walking only the enabled ones meant that switching a
            // language off took its lists out of the one screen that manages
            // them, while the files stayed on disk and in Storage.
            val ids = LinkedHashSet<String>()
            settings.enabledLanguages.mapTo(ids) { it.id }
            ids.addAll(CustomDictionaries.languagesWithLists(context.filesDir))
            ids.associateWith { langId ->
                // allLists, not lists: a switched-off list still has to be
                // shown, or there is no way to switch it back on.
                CustomDictionaries.allLists(context.filesDir, langId).map { file ->
                    val words = runCatching {
                        file.inputStream().use { DictionaryLoader.loadEntries(it).size }
                    }.getOrDefault(0)
                    WordListEntry(file, words)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun importFromUrl(langId: String, url: String) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = android.net.Uri.parse(url.trim())
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        return@runCatching -2
                    }
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "wordlist"
                    val temp = java.io.File.createTempFile("dict_url_", ".tmp", context.cacheDir)
                    try {
                        ToolHttp.download(url.trim(), temp, maxBytes = CustomDictionaries.MAX_BYTES)
                        temp.inputStream().use { CustomDictionaries.import(context.filesDir, langId, name, it) }
                    } finally {
                        temp.delete()
                    }
                }.getOrElse { -1 }
            }
            busy = false
            message = when {
                result == -2 -> context.getString(R.string.customdict_url_scheme_error)
                result < 0 -> context.getString(R.string.customdict_url_download_error)
                result == 0 -> context.getString(R.string.customdict_import_empty_error)
                else -> context.resources.getQuantityString(
                    R.plurals.customdict_import_added_words,
                    result,
                    result,
                    languageLabel(langId),
                )
            }
            if (result > 0) {
                refresh()
                repository.bumpCustomDictVersion()
            }
        }
    }

    val importList = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val language = pending
        pending = null
        if (uri == null || language == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        } ?: "wordlist"
                    val size = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                        } ?: -1L
                    if (size > CustomDictionaries.MAX_BYTES) return@runCatching -1
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching 0
                    stream.use { CustomDictionaries.import(context.filesDir, language, name, it) }
                }.getOrDefault(0)
            }
            busy = false
            message = when {
                result < 0 -> context.getString(R.string.customdict_import_too_large_error)
                result == 0 -> context.getString(R.string.customdict_import_empty_error)
                else -> context.resources.getQuantityString(
                    R.plurals.customdict_import_added_words,
                    result,
                    result,
                    languageLabel(language),
                )
            }
            if (result > 0) {
                refresh()
                repository.bumpCustomDictVersion()
            }
        }
    }

    Text(
        stringResource(R.string.customdict_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    AddonStoreGroup(AddonType.Dictionary, onNavigate)

    // The enabled languages in their own order, then any language switched off
    // that still has lists on disk. Those used to disappear from this screen
    // entirely while their files stayed, so the only way to reach a list again
    // was to work out which language it belonged to and re-enable that.
    val enabledIds = settings.enabledLanguages.map { it.id }
    val strandedIds = lists.keys.filter { it !in enabledIds && lists[it]?.isNotEmpty() == true }
    val offHeader = stringResource(R.string.customdict_language_off_header)
    for (langId in enabledIds + strandedIds) {
        val entries = lists[langId].orEmpty()
        val languageOff = langId in strandedIds
        val header = languageLabel(langId)
        SettingsGroup(if (languageOff) offHeader.format(header) else header) {
            for (entry in entries) {
                item {
                    // A downloaded word list is recorded by its path, which is
                    // what an addon's Use button hands over.
                    HighlightableItem(entry.file.absolutePath) {
                        val enabled = CustomDictionaries.isEnabled(entry.file)
                        val listName = CustomDictionaries.displayName(entry.file)
                            .substringBeforeLast('.')
                        WmRow(
                            title = listName,
                            subtitle = if (!enabled) {
                                stringResource(R.string.customdict_list_off_subtitle)
                            } else {
                                pluralStringResource(
                                    R.plurals.customdict_word_count,
                                    entry.words,
                                    entry.words,
                                )
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                // Switching off renames the file rather than
                                // deleting it: working out whether a bad import
                                // is polluting suggestions used to cost a delete
                                // and a re-import.
                                Switch(
                                    checked = enabled,
                                    enabled = !busy,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CustomDictionaries.setEnabled(entry.file, on)
                                            }
                                            refresh()
                                            repository.bumpCustomDictVersion()
                                        }
                                    },
                                )
                                IconButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                CustomDictionaries.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpCustomDictVersion()
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.customdict_delete_list_desc,
                                            listName,
                                        ),
                                    )
                                }
                                }
                            },
                        )
                    }
                }
            }
            // No import buttons for a language that is switched off: a list
            // imported there would not be read by anything. The rows above stay
            // live, so the lists can still be switched off or deleted, which is
            // what someone reaching this group came for.
            if (languageOff) {
                item { CaptionText(stringResource(R.string.customdict_language_off_caption)) }
            } else {
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                pending = langId
                                importList.launch(arrayOf("*/*"))
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (entries.isEmpty()) R.string.customdict_import_action
                                    else R.string.customdict_import_another_action,
                                ),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { urlDialogFor = langId },
                        ) { Text(stringResource(R.string.customdict_from_url_action)) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val urlLanguage = urlDialogFor
    if (urlLanguage != null) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialogFor = null },
            title = { Text(stringResource(R.string.customdict_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") },
                    placeholder = { Text(stringResource(R.string.customdict_url_dialog_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        urlDialogFor = null
                        importFromUrl(urlLanguage, url)
                    },
                ) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDialogFor = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

// ---- emoji keyword packs ----

/** One imported emoji pack: the file plus how many emoji it names. */
private data class EmojiPackEntry(val file: java.io.File, val emoji: Int)

/**
 * Per-language emoji keyword packs: the downloadable dictionaries from the
 * data repo, and the user's own imports.
 *
 * Deliberately the same shape as [CustomDictionarySettings] — per-language
 * groups, a download row, import from a file or a URL, delete a row — because
 * it solves the same problem: the app can only bundle so many languages, and
 * everything past that has to arrive from somewhere else.
 */
@Composable
private fun EmojiKeywordSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var packs by remember { mutableStateOf<Map<String, List<EmojiPackEntry>>>(emptyMap()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var urlDialogFor by remember { mutableStateOf<String?>(null) }

    // Enabled languages are the ones worth *offering* an import for, but a pack
    // can arrive for a language that isn't enabled — an addon repository
    // installs by langId, and languages get turned off again. Those groups
    // still have to appear or the pack would be uninstallable from here.
    val languageIds = remember(settings.enabledLanguages, packs.keys) {
        (settings.enabledLanguages.map { it.id } + packs.keys).distinct()
    }

    // Counting emoji means parsing every pack, so it never runs on the main
    // thread — the screen draws empty for a moment and fills in.
    suspend fun refresh() {
        packs = withContext(Dispatchers.IO) {
            // Enabled languages are the ones worth offering a download for,
            // but a pack can outlive the language being on — an addon repo
            // installs by langId, and languages get turned off again. Those
            // groups still have to appear or the pack is unreachable.
            val ids = (
                settings.enabledLanguages.map { it.id } +
                    EmojiKeywordPacks.languages(context.filesDir) +
                    EmojiDictStore.downloadedLanguageIds(context.filesDir)
                ).distinct()
            ids.associateWith { id ->
                EmojiKeywordPacks.packs(context.filesDir, id).map { file ->
                    val count = runCatching {
                        file.inputStream().use { EmojiKeywordPack.load(it).size }
                    }.getOrDefault(0)
                    EmojiPackEntry(file, count)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    suspend fun finish(langId: String, result: Int) {
        busy = false
        message = when {
            result == -2 -> context.getString(R.string.customdict_url_scheme_error)
            result == -1 -> context.getString(R.string.customdict_import_too_large_error)
            result == 0 -> context.getString(R.string.customdict_emoji_import_empty_error)
            else -> context.resources.getQuantityString(
                R.plurals.customdict_emoji_import_added,
                result,
                result,
                languageLabel(langId),
            )
        }
        if (result > 0) {
            refresh()
            repository.bumpEmojiKeywordPackVersion()
        }
    }

    fun importFromUrl(langId: String, url: String) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = android.net.Uri.parse(url.trim())
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        return@runCatching -2
                    }
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "emoji"
                    val temp = java.io.File.createTempFile("emoji_url_", ".tmp", context.cacheDir)
                    try {
                        ToolHttp.download(url.trim(), temp, maxBytes = EmojiKeywordPack.MAX_BYTES)
                        temp.inputStream().use {
                            EmojiKeywordPacks.import(context.filesDir, langId, name, it)
                        }
                    } finally {
                        temp.delete()
                    }
                }.getOrElse { -1 }
            }
            finish(langId, result)
        }
    }

    val importPack = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val language = pending
        pending = null
        if (uri == null || language == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        } ?: "emoji"
                    val size = context.contentResolver
                        .query(uri, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else -1L
                        } ?: -1L
                    if (size > EmojiKeywordPack.MAX_BYTES) return@runCatching -1
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching 0
                    stream.use {
                        EmojiKeywordPacks.import(context.filesDir, language, name, it)
                    }
                }.getOrDefault(0)
            }
            finish(language, result)
        }
    }

    // The examples are drawn from the languages this user actually types, so
    // the line demonstrates the feature instead of demonstrating three scripts
    // they may not read.
    val packExamples = EmojiSearchExamples
        .pick(EmojiSearchExamples.money, settings.enabledLanguages.map { it.id }, limit = 3)
        .joinToString(", ")
    Text(
        stringResource(R.string.customdict_emoji_info, packExamples),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )

    SettingsGroup(stringResource(R.string.customdict_emoji_downloads_title)) {
        item {
            ToggleSetting(
                R.string.customdict_emoji_auto_download_title,
                stringResource(R.string.customdict_emoji_auto_download_subtitle),
                settings.emoji.autoDownloadKeywords,
                info = stringResource(R.string.customdict_emoji_auto_download_info),
                default = SettingsDefaults.emoji.autoDownloadKeywords,
            ) { scope.launch { repository.setEmojiAutoDownloadKeywords(it) } }
        }
        item { AddonStoreRow(AddonType.EmojiKeywords, onNavigate) }
    }

    for (languageId in languageIds) {
        val entries = packs[languageId].orEmpty()
        val dict = EmojiDictCatalog.forLanguage(languageId)
        SettingsGroup(languageLabel(languageId)) {
            if (dict != null) {
                item { EmojiDictRow(dict) }
            }
            for (entry in entries) {
                item {
                    HighlightableItem(entry.file.absolutePath) {
                        WmRow(
                            title = entry.file.nameWithoutExtension,
                            subtitle = pluralStringResource(
                                R.plurals.customdict_emoji_count,
                                entry.emoji,
                                entry.emoji,
                            ),
                            trailing = {
                                IconButton(
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                EmojiKeywordPacks.remove(entry.file)
                                            }
                                            refresh()
                                            repository.bumpEmojiKeywordPackVersion()
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.customdict_delete_pack_desc,
                                            entry.file.nameWithoutExtension,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            pending = languageId
                            importPack.launch(arrayOf("*/*"))
                        },
                    ) {
                        Text(
                            stringResource(
                                if (entries.isEmpty()) R.string.customdict_emoji_import_action
                                else R.string.customdict_import_another_action,
                            ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { urlDialogFor = languageId },
                    ) { Text(stringResource(R.string.customdict_from_url_action)) }
                }
            }
        }
    }

    Text(
        stringResource(R.string.customdict_emoji_format_info),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    Spacer(Modifier.height(16.dp))

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val urlLanguage = urlDialogFor
    if (urlLanguage != null) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialogFor = null },
            title = { Text(stringResource(R.string.customdict_emoji_url_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("https://…") },
                    placeholder = {
                        Text(stringResource(R.string.customdict_emoji_url_dialog_hint))
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank(),
                    onClick = {
                        urlDialogFor = null
                        importFromUrl(urlLanguage, url)
                    },
                ) { Text(stringResource(CommonR.string.common_download)) }
            },
            dismissButton = {
                TextButton(onClick = { urlDialogFor = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

// ---- fonts ----

/** Mime types SAF offers when picking a font; octet-stream covers file managers that don't tag fonts. */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/octet-stream",
)

/**
 * A refused font import, kept as resource ids rather than finished words: the
 * import runs off the main thread with no way to draw, so the dialog is what
 * resolves the wording against the language the app is running in.
 *
 * A message that counts fonts sets [pluralsRes] and [quantity] instead of
 * [stringRes]; [args] fills the placeholders of [stringRes].
 */
private data class FontMessage(
    @StringRes val stringRes: Int = 0,
    @PluralsRes val pluralsRes: Int = 0,
    val quantity: Int = 0,
    val args: List<Any> = emptyList(),
)

/**
 * Font picker: separate English and Bengali choices, each offering the
 * system default, the installed-font library, curated Google Fonts (every row
 * rendered in its own face as a live preview — faces download on first view and
 * are cached by the system provider), plus the legacy single imported file per
 * script.
 *
 * Importing a file here fills the library rather than overwriting one fixed
 * slot, so importing a second font no longer evicts the first. The two old
 * single-slot files still render and stay selectable for anyone who set one
 * before the library existed; nothing migrates and nothing is lost.
 */
@Composable
private fun FontSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fontStore = remember { FontStore.get(context) }
    val fontRevision by fontStore.revision.collectAsStateWithLifecycle()
    // Text faces only: an emoji font is chosen on the Emoji screen, and picking
    // one for the key labels would draw the alphabet as coloured pictograms.
    val installedFonts = remember(fontRevision) { fontStore.textFonts() }
    // The failure to show, still unresolved; see [FontMessage].
    var fontMessage by remember { mutableStateOf<FontMessage?>(null) }

    fun importIntoLibrary(uri: android.net.Uri, apply: suspend (String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        FontFile.import(it, fontStore, name = fontFileLabel(context, uri))
                    }
                }.getOrElse {
                    FontImportResult.Failed(ContentR.string.core_content_font_error_read)
                }
            }
            when (result) {
                is FontImportResult.Imported -> apply(FontStore.fontIdFor(result.font.id))
                is FontImportResult.NotAFont -> fontMessage = FontMessage(result.messageRes)
                FontImportResult.TooManyFonts -> fontMessage = FontMessage(
                    pluralsRes = R.plurals.fonts_import_limit_message,
                    quantity = FontStore.MAX_FONTS,
                )
                is FontImportResult.Failed ->
                    fontMessage = FontMessage(result.messageRes, args = result.messageArgs)
            }
        }
    }

    fun deleteInstalled(font: InstalledFont) {
        scope.launch {
            // Drop the selection first, so the keyboard never renders against a
            // file that is about to disappear. Covers the per-script overrides
            // too, which neither of the pickers on this screen can see.
            repository.forgetInstalledFont(FontStore.fontIdFor(font.id))
            withContext(Dispatchers.IO) { fontStore.delete(font.id) }
        }
    }

    fontMessage?.let { message ->
        // Spelled out rather than spread: no font message takes more than one
        // argument, and a spread copies the array on every recomposition.
        val text = when {
            message.pluralsRes != 0 -> pluralStringResource(
                message.pluralsRes,
                message.quantity,
                message.quantity,
            )
            message.args.isEmpty() -> stringResource(message.stringRes)
            else -> stringResource(message.stringRes, message.args.first())
        }
        AlertDialog(
            onDismissRequest = { fontMessage = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { fontMessage = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Text(
        stringResource(R.string.fonts_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    // Said once, at the top, so the short pickers below read as a missing
    // platform piece rather than a keyboard that forgot its fonts.
    if (!PlayServices.hasFontProvider(context)) {
        Text(
            stringResource(R.string.fonts_google_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
        )
    }
    AddonStoreGroup(AddonType.Font, onNavigate)
    FontPickerSection(
        header = stringResource(R.string.fonts_english_header),
        sample = "The quick brown fox jumps over the lazy dog",
        selectedId = settings.keyFontId,
        googleNames = KeyboardFonts.googleFonts,
        customId = KeyboardFonts.CUSTOM_ID,
        customFile = KeyboardFonts.customFontFile(context),
        customName = settings.customFontName,
        onSelect = { id -> scope.launch { repository.setKeyFontId(id) } },
        onImport = { uri -> importIntoLibrary(uri) { repository.setKeyFontId(it) } },
        installedFonts = installedFonts,
        installedTitle = stringResource(R.string.fonts_installed_header),
        // The English picker also drives Cyrillic and Greek, which have no
        // picker of their own — a font claiming any of the three belongs here.
        scripts = setOf(ScriptId.LATIN, ScriptId.CYRILLIC, ScriptId.GREEK),
        onDeleteInstalled = ::deleteInstalled,
    )
    // Curated font pickers for the non-Latin scripts, each shown only while a
    // language using that script is enabled. Every one offers the script's
    // automatic Noto face, a few alternatives, the import button and whatever in
    // the font library covers that script.
    // Latin/Cyrillic/Greek follow the English font above.
    val enabledScripts = settings.enabledLanguages.mapTo(mutableSetOf()) { it.script }
    for (choices in KeyboardFonts.scriptFontChoices) {
        if (choices.script !in enabledScripts) continue
        val script = choices.script.name
        // Non-null only for the scripts whose picker takes an imported file;
        // everything import-shaped below hangs off it.
        val customId = KeyboardFonts.customScriptFontId(choices.script)
        // Importing goes into the shared library, which every script can select
        // from, so it does not need the per-script file slot [customId] names
        // and is offered everywhere. Before this, a Devanagari or Thai font had
        // nowhere to be imported and nowhere to be picked even once installed
        // from an addon: only the two scripts with a legacy file slot showed
        // the library at all.
        val onImportFont: (Uri) -> Unit = { uri: Uri ->
            importIntoLibrary(uri) { id -> repository.setScriptFontId(script, id) }
        }
        // The name of the script, drawn into both headers of this picker.
        val scriptName = stringResource(choices.labelRes)
        FontPickerSection(
            header = stringResource(R.string.fonts_script_header, scriptName),
            sample = choices.sample,
            selectedId = settings.scriptFontIds[script] ?: KeyboardFonts.DEFAULT_ID,
            googleNames = choices.fonts,
            defaultLabel = stringResource(R.string.fonts_default_noto_label),
            customId = customId,
            customFile = KeyboardFonts.customScriptFontFile(context, choices.script),
            customName = settings.customScriptFontNames[script].orEmpty(),
            onSelect = { id -> scope.launch { repository.setScriptFontId(script, id) } },
            onImport = onImportFont,
            installedFonts = installedFonts,
            installedTitle = stringResource(R.string.fonts_installed_script_header, scriptName),
            scripts = setOf(choices.script),
            onDeleteInstalled = ::deleteInstalled,
        )
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * One script's font list: the default row, curated Google faces, and — for the
 * scripts that support it (English/Bengali) — the single imported file and an
 * import button. Scripts that only offer curated faces pass [customFile] null;
 * their default row is relabelled via [defaultLabel] since it is the script's
 * automatic Noto face rather than the raw system font.
 *
 * [installedFonts] is the library filled by the Addons screen and by importing a
 * file here. It gets its own section above the curated list: it is a short,
 * personal list next to twenty stock faces, and burying it inside them makes a
 * font the user deliberately installed harder to find than one they didn't.
 *
 * [scripts] is what the picker is for. A font that declares which languages it
 * covers is only offered where it covers something — plenty of display faces are
 * Latin-only, and offering one for Bengali offers a keyboard of empty boxes. A
 * font that declares nothing makes no claim and is offered everywhere.
 */
@Composable
private fun FontPickerSection(
    header: String,
    sample: String,
    selectedId: String,
    googleNames: List<String>,
    onSelect: (String) -> Unit,
    defaultLabel: String = stringResource(R.string.fonts_default_system_label),
    /** The imported-file id this picker writes, or null if it takes no import. */
    customId: String? = KeyboardFonts.CUSTOM_ID,
    customFile: java.io.File? = null,
    customName: String = "",
    onImport: ((android.net.Uri) -> Unit)? = null,
    installedFonts: List<InstalledFont> = emptyList(),
    installedTitle: String = stringResource(R.string.fonts_installed_header),
    scripts: Set<ScriptId> = emptySet(),
    onDeleteInstalled: ((InstalledFont) -> Unit)? = null,
) {
    val context = LocalContext.current
    val importFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport?.invoke(uri) }
    val relevant = remember(installedFonts, scripts) {
        installedFonts.filter { font ->
            font.langIds.isEmpty() || scripts.isEmpty() ||
                font.langIds.any { LanguageRegistry.byId(it).script in scripts }
        }
    }
    // Every Google Fonts row is a file fetched from the Play services font
    // provider. On a device without it each one would resolve to the system
    // face, so picking one would change nothing on screen — the rows come out
    // rather than sit there doing nothing. The system default, the imported
    // file and the font library are all this app's own and stay.
    val googleFontNames = if (PlayServices.hasFontProvider(context)) googleNames else emptyList()
    if (relevant.isNotEmpty()) {
        SettingsGroup(installedTitle) {
            for (font in relevant) {
                item {
                    val id = FontStore.fontIdFor(font.id)
                    // Matched on the library id, not the prefixed settings one:
                    // an install records the font exactly as the store knows it.
                    HighlightableItem(font.id) {
                        FontChoiceRow(
                            label = font.name,
                            family = remember(id) { KeyboardFonts.family(context, id) },
                            sample = sample,
                            selected = selectedId == id,
                            onDelete = onDeleteInstalled?.let { delete -> { delete(font) } },
                        ) { onSelect(id) }
                    }
                }
            }
        }
    }
    SettingsGroup(header) {
        item {
            FontChoiceRow(
                label = defaultLabel,
                family = null,
                sample = sample,
                selected = selectedId == KeyboardFonts.DEFAULT_ID,
            ) { onSelect(KeyboardFonts.DEFAULT_ID) }
        }
        for (name in googleFontNames) {
            item {
                val id = KeyboardFonts.googleId(name)
                FontChoiceRow(
                    label = name,
                    family = remember(id) { KeyboardFonts.family(context, id) },
                    sample = sample,
                    selected = selectedId == id,
                ) { onSelect(id) }
            }
        }
        // The single imported file this picker used to keep before fonts moved
        // into the shared library above. Shown only while that file is still
        // there, so an old import stays selectable and a fresh install never
        // sees the row.
        if (customId != null && customFile?.exists() == true) {
            item {
                val importedLabel = stringResource(R.string.fonts_imported_label)
                FontChoiceRow(
                    label = customName.ifBlank { importedLabel },
                    family = remember(customName) { KeyboardFonts.family(context, customId) },
                    sample = sample,
                    selected = selectedId == customId,
                ) { onSelect(customId) }
            }
        }
        if (onImport != null) {
            item {
                OutlinedButton(
                    onClick = { importFont.launch(FONT_MIME_TYPES) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.fonts_import_action)) }
            }
        }
    }
}

/** One selectable font row, its label and sample line drawn in the font itself. */
@Composable
private fun FontChoiceRow(
    label: String,
    family: FontFamily?,
    sample: String,
    selected: Boolean,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    WmRow(
        title = label,
        // The row is the preview: name and sample both drawn in the font
        // itself, so picking one shows what it will look like.
        titleContent = { Text(label, fontFamily = family, fontSize = 18.sp) },
        supporting = {
            Text(
                sample,
                fontFamily = family,
                maxLines = 1,
            )
        },
        trailing = if (selected || onDelete != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.fonts_selected_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.fonts_delete_desc,
                                    label,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        onClick = onClick,
    )
}

/**
 * Copies a picked font into private storage and returns its display name,
 * or null when the stream can't be read or the platform can't parse the
 * file (the bad copy is deleted so it never sticks as the "custom font").
 */
/**
 * A human-readable name for a picked font file: the provider's display name
 * with the extension stripped, since "Inter-Regular" reads better in the picker
 * than "Inter-Regular.ttf".
 */
private fun fontFileLabel(context: Context, uri: android.net.Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
    return name?.substringBeforeLast('.')?.trim().orEmpty()
        .ifBlank { context.getString(R.string.fonts_imported_label) }
}

private fun importFontFile(context: Context, uri: android.net.Uri, dest: java.io.File): String? {
    return runCatching {
        dest.parentFile?.mkdirs()
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied == null) return null
        val parsed = runCatching { android.graphics.Typeface.createFromFile(dest) }.getOrNull()
        if (parsed == null || parsed == android.graphics.Typeface.DEFAULT) {
            dest.delete()
            return null
        }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: dest.name
    }.getOrNull()
}

// ---- tools ----

/**
 * Whether a tool's settings page offers anything beyond the enable switch —
 * drives the "has more settings" marker on the tools list. Kept as the
 * caption-only exceptions so a new tool with options is marked by default.
 */
/**
 * Whether the tool's detail page offers anything beyond the enable switch —
 * gates the "has more settings" affordance in the tools list. Keep in sync
 * with [ToolDetailSettings]'s `when`: a tool whose page is just the toggle
 * (or a caption) doesn't earn the icon.
 */
private fun toolHasOptions(tool: ToolbarTool): Boolean = tool !in ToolsWithoutOptions

/** Asked once per row on a screen of sixty, so it is not built per call. */
private val ToolsWithoutOptions: Set<ToolbarTool> =
    setOf(
        ToolbarTool.SETTINGS,
        // Quick toggles/panels, not tools with settings of their own —
        // their options all live elsewhere (Appearance, Typing).
        ToolbarTool.THEMES, ToolbarTool.SOUND_HAPTICS, ToolbarTool.ONE_HANDED,
        // Just an enable toggle — the transport lives on the keyboard panel,
        // there is nothing to configure here.
        ToolbarTool.MEDIA_CONTROL,
        // Everything it changes is a Layout slider; the tool is only the
        // in-place way to drag them.
        ToolbarTool.RESIZE,
    )

/**
 * The name of a tool on its settings screen, as a string resource the caller
 * resolves while it draws.
 *
 * The keyboard toolbar names the same tools in `toolLabelRes`, and half of them
 * word it identically: those reuse the keyboard's own resource rather than
 * carry a second copy for translators. The rest are the settings-screen wording,
 * which has room for the longer name the toolbar cannot fit ("Bubble level"
 * against "Level"), and those live in this module.
 */
@StringRes
internal fun toolTitle(tool: ToolbarTool): Int = when (tool) {
    ToolbarTool.EMOJI -> ImeR.string.ime_tool_emoji
    ToolbarTool.CLIPBOARD -> ImeR.string.ime_tool_clipboard
    ToolbarTool.SNIPPETS -> ImeR.string.ime_tool_snippets
    ToolbarTool.TEXT_EDIT -> ImeR.string.ime_tool_text_edit
    ToolbarTool.ONE_HANDED -> R.string.fonts_tool_one_handed_title
    ToolbarTool.SPLIT -> R.string.fonts_tool_split_title
    ToolbarTool.FLOATING -> R.string.fonts_tool_floating_title
    ToolbarTool.RESIZE -> ImeR.string.ime_tool_resize
    ToolbarTool.SETTINGS -> R.string.fonts_tool_settings_title
    ToolbarTool.FLASHLIGHT -> ImeR.string.ime_tool_flashlight
    ToolbarTool.COMPASS -> ImeR.string.ime_tool_compass
    ToolbarTool.LEVEL -> R.string.fonts_tool_level_title
    ToolbarTool.UNDO -> ImeR.string.ime_tool_undo
    ToolbarTool.REDO -> ImeR.string.ime_tool_redo
    ToolbarTool.MOON_PHASE -> R.string.fonts_tool_moon_phase_title
    ToolbarTool.WEATHER -> ImeR.string.ime_tool_weather
    ToolbarTool.CALENDAR -> ImeR.string.ime_tool_calendar
    ToolbarTool.INCOGNITO -> ImeR.string.ime_tool_incognito
    ToolbarTool.POWER_SAVING -> ImeR.string.ime_tool_power_saving
    ToolbarTool.THEMES -> ImeR.string.ime_tool_themes
    ToolbarTool.AUTOCORRECT -> ImeR.string.ime_tool_autocorrect
    ToolbarTool.SOUND_HAPTICS -> ImeR.string.ime_tool_sound_haptics
    ToolbarTool.NUMPAD -> ImeR.string.ime_tool_numpad
    ToolbarTool.HANDWRITING -> ImeR.string.ime_tool_handwriting
    ToolbarTool.CAMERA -> ImeR.string.ime_tool_camera
    ToolbarTool.DICTIONARY -> ImeR.string.ime_tool_dictionary
    ToolbarTool.TRANSLATE -> ImeR.string.ime_tool_translate
    ToolbarTool.GIF -> ImeR.string.ime_tool_gif
    ToolbarTool.STICKER -> ImeR.string.ime_tool_sticker
    ToolbarTool.WEB_SEARCH -> R.string.fonts_tool_web_search_title
    ToolbarTool.IMAGE_SEARCH -> R.string.fonts_tool_image_search_title
    ToolbarTool.OCR -> R.string.fonts_tool_ocr_title
    ToolbarTool.QR_SCAN -> R.string.fonts_tool_qr_scan_title
    ToolbarTool.DOC_SCAN -> R.string.fonts_tool_doc_scan_title
    ToolbarTool.VOICE -> R.string.fonts_tool_voice_title
    ToolbarTool.GRAMMAR -> R.string.fonts_tool_grammar_title
    ToolbarTool.WIKIPEDIA -> ImeR.string.ime_tool_wikipedia
    ToolbarTool.SYMBOLS -> R.string.fonts_tool_symbols_title
    ToolbarTool.CALCULATOR -> ImeR.string.ime_tool_calculator
    ToolbarTool.UNIT_CONVERT -> R.string.fonts_tool_unit_convert_title
    ToolbarTool.CURRENCY -> R.string.fonts_tool_currency_title
    ToolbarTool.QR_GEN -> R.string.fonts_tool_qr_gen_title
    ToolbarTool.PASSWORD_GEN -> R.string.fonts_tool_password_gen_title
    ToolbarTool.TYPING_TEST -> R.string.fonts_tool_typing_test_title
    ToolbarTool.MEDIA_CONTROL -> R.string.fonts_tool_media_control_title
    ToolbarTool.PLUGINS -> ImeR.string.ime_tool_plugins
    ToolbarTool.APP_LAUNCHER -> R.string.fonts_tool_app_launcher_title
    ToolbarTool.AI -> R.string.fonts_tool_ai_title
    ToolbarTool.MODES -> R.string.fonts_tool_modes_title
    ToolbarTool.FANCY -> ImeR.string.ime_tool_fancy
    ToolbarTool.CURSOR_LEFT -> R.string.fonts_tool_cursor_left_title
    ToolbarTool.CURSOR_RIGHT -> R.string.fonts_tool_cursor_right_title
    ToolbarTool.CURSOR_WORD_LEFT -> ImeR.string.ime_tool_cursor_word_left
    ToolbarTool.CURSOR_WORD_RIGHT -> ImeR.string.ime_tool_cursor_word_right
    ToolbarTool.CURSOR_UP -> R.string.fonts_tool_cursor_up_title
    ToolbarTool.CURSOR_DOWN -> R.string.fonts_tool_cursor_down_title
    ToolbarTool.CURSOR_HOME -> ImeR.string.ime_tool_cursor_home
    ToolbarTool.CURSOR_END -> ImeR.string.ime_tool_cursor_end
    ToolbarTool.PAGE_UP -> ImeR.string.ime_tool_page_up
    ToolbarTool.PAGE_DOWN -> ImeR.string.ime_tool_page_down
    ToolbarTool.SELECT_WORD -> ImeR.string.ime_tool_select_word
    ToolbarTool.SELECT_LINE -> ImeR.string.ime_tool_select_line
    ToolbarTool.SELECT_MODE -> ImeR.string.ime_tool_select_mode
    ToolbarTool.HIDE_KEYBOARD -> R.string.fonts_tool_hide_keyboard_title
}

/** The one-line description under a tool's name, as a string resource. */
@StringRes
internal fun toolDescription(tool: ToolbarTool): Int = when (tool) {
    ToolbarTool.EMOJI -> R.string.fonts_tool_emoji_desc
    ToolbarTool.CLIPBOARD -> R.string.fonts_tool_clipboard_desc
    ToolbarTool.SNIPPETS -> R.string.fonts_tool_snippets_desc
    ToolbarTool.TEXT_EDIT -> R.string.fonts_tool_text_edit_desc
    ToolbarTool.ONE_HANDED -> R.string.fonts_tool_one_handed_desc
    ToolbarTool.SPLIT -> R.string.fonts_tool_split_desc
    ToolbarTool.FLOATING -> R.string.fonts_tool_floating_desc
    ToolbarTool.RESIZE -> R.string.fonts_tool_resize_desc
    ToolbarTool.SETTINGS -> R.string.fonts_tool_settings_desc
    ToolbarTool.FLASHLIGHT -> R.string.fonts_tool_flashlight_desc
    ToolbarTool.COMPASS -> R.string.fonts_tool_compass_desc
    ToolbarTool.LEVEL -> R.string.fonts_tool_level_desc
    ToolbarTool.UNDO -> R.string.fonts_tool_undo_desc
    ToolbarTool.REDO -> R.string.fonts_tool_redo_desc
    ToolbarTool.MOON_PHASE -> R.string.fonts_tool_moon_phase_desc
    ToolbarTool.WEATHER -> R.string.fonts_tool_weather_desc
    ToolbarTool.CALENDAR -> R.string.fonts_tool_calendar_desc
    ToolbarTool.INCOGNITO -> R.string.fonts_tool_incognito_desc
    ToolbarTool.POWER_SAVING -> R.string.fonts_tool_power_saving_desc
    ToolbarTool.THEMES -> R.string.fonts_tool_themes_desc
    ToolbarTool.AUTOCORRECT -> R.string.fonts_tool_autocorrect_desc
    ToolbarTool.SOUND_HAPTICS -> R.string.fonts_tool_sound_haptics_desc
    ToolbarTool.NUMPAD -> R.string.fonts_tool_numpad_desc
    ToolbarTool.HANDWRITING -> R.string.fonts_tool_handwriting_desc
    ToolbarTool.CAMERA -> R.string.fonts_tool_camera_desc
    ToolbarTool.DICTIONARY -> R.string.fonts_tool_dictionary_desc
    ToolbarTool.TRANSLATE -> R.string.fonts_tool_translate_desc
    ToolbarTool.GIF -> R.string.fonts_tool_gif_desc
    ToolbarTool.STICKER -> R.string.fonts_tool_sticker_desc
    ToolbarTool.WEB_SEARCH -> R.string.fonts_tool_web_search_desc
    ToolbarTool.IMAGE_SEARCH -> R.string.fonts_tool_image_search_desc
    ToolbarTool.OCR -> R.string.fonts_tool_ocr_desc
    ToolbarTool.QR_SCAN -> R.string.fonts_tool_qr_scan_desc
    ToolbarTool.DOC_SCAN -> R.string.fonts_tool_doc_scan_desc
    ToolbarTool.VOICE -> R.string.fonts_tool_voice_desc
    ToolbarTool.GRAMMAR -> R.string.fonts_tool_grammar_desc
    ToolbarTool.WIKIPEDIA -> R.string.fonts_tool_wikipedia_desc
    ToolbarTool.SYMBOLS -> R.string.fonts_tool_symbols_desc
    ToolbarTool.CALCULATOR -> R.string.fonts_tool_calculator_desc
    ToolbarTool.UNIT_CONVERT -> R.string.fonts_tool_unit_convert_desc
    ToolbarTool.CURRENCY -> R.string.fonts_tool_currency_desc
    ToolbarTool.QR_GEN -> R.string.fonts_tool_qr_gen_desc
    ToolbarTool.PASSWORD_GEN -> R.string.fonts_tool_password_gen_desc
    ToolbarTool.TYPING_TEST -> R.string.fonts_tool_typing_test_desc
    ToolbarTool.MEDIA_CONTROL -> R.string.fonts_tool_media_control_desc
    ToolbarTool.PLUGINS -> R.string.fonts_tool_plugins_desc
    ToolbarTool.APP_LAUNCHER -> R.string.fonts_tool_app_launcher_desc
    ToolbarTool.AI -> R.string.fonts_tool_ai_desc
    ToolbarTool.MODES -> R.string.fonts_tool_modes_desc
    ToolbarTool.FANCY -> R.string.fonts_tool_fancy_desc
    ToolbarTool.CURSOR_LEFT -> R.string.fonts_tool_cursor_left_desc
    ToolbarTool.CURSOR_RIGHT -> R.string.fonts_tool_cursor_right_desc
    ToolbarTool.CURSOR_WORD_LEFT -> R.string.fonts_tool_cursor_word_left_desc
    ToolbarTool.CURSOR_WORD_RIGHT -> R.string.fonts_tool_cursor_word_right_desc
    ToolbarTool.CURSOR_UP -> R.string.fonts_tool_cursor_up_desc
    ToolbarTool.CURSOR_DOWN -> R.string.fonts_tool_cursor_down_desc
    ToolbarTool.CURSOR_HOME -> R.string.fonts_tool_cursor_home_desc
    ToolbarTool.CURSOR_END -> R.string.fonts_tool_cursor_end_desc
    ToolbarTool.PAGE_UP -> R.string.fonts_tool_page_up_desc
    ToolbarTool.PAGE_DOWN -> R.string.fonts_tool_page_down_desc
    ToolbarTool.SELECT_WORD -> R.string.fonts_tool_select_word_desc
    ToolbarTool.SELECT_LINE -> R.string.fonts_tool_select_line_desc
    ToolbarTool.SELECT_MODE -> R.string.fonts_tool_select_mode_desc
    ToolbarTool.HIDE_KEYBOARD -> R.string.fonts_tool_hide_keyboard_desc
}

internal fun toolIconFor(tool: ToolbarTool): androidx.compose.ui.graphics.vector.ImageVector =
    IconDefaults.forTool(tool)

/**
 * The tool menu, grouped by what the tools do. Everything else — the
 * enable switch and the tool's own options — lives one level down.
 */
@Composable
private fun ToolsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onOpenTool: (ToolbarTool) -> Unit,
) {
    val scope = rememberCoroutineScope()
    CaptionText(stringResource(R.string.tools_intro_info))
    ToggleSetting(
        title = R.string.tools_colored_icons_title,
        subtitle = stringResource(R.string.tools_colored_icons_subtitle),
        checked = settings.coloredToolIcons,
        onChange = { scope.launch { repository.setColoredToolIcons(it) } },
        default = SettingsDefaults.coloredToolIcons,
    )
    // Nested under the switch above rather than shown greyed out: with the
    // colours off there is nothing for a gradient to be made of, so the row
    // would be asking about something that cannot happen.
    if (settings.coloredToolIcons) {
        ToggleSetting(
            title = R.string.tools_gradient_icons_title,
            subtitle = stringResource(R.string.tools_gradient_icons_subtitle),
            checked = settings.toolIconGradients,
            info = stringResource(R.string.tools_gradient_icons_info),
            onChange = { scope.launch { repository.setToolIconGradients(it) } },
            default = SettingsDefaults.toolIconGradients,
        )
    }
    val hasColourOverrides = settings.toolColorOverrides.isNotEmpty() ||
        settings.toolColorEndOverrides.isNotEmpty()
    if (settings.coloredToolIcons && hasColourOverrides) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { scope.launch { repository.clearToolColors() } }) {
                Text(stringResource(R.string.tools_reset_colors_action))
            }
        }
    }
    // Resolved once per screen, not once per row: this list is ~60 rows long,
    // and every one of them recomposes whenever any tool is switched on or off.
    val paints = remember(
        settings.coloredToolIcons,
        settings.toolIconGradients,
        settings.toolColorOverrides,
        settings.toolColorEndOverrides,
    ) {
        ToolbarTool.entries.associateWith { toolAccentPaint(it, settings) }
    }
    val optionsDesc = stringResource(R.string.tools_has_options_desc)
    // Only the group titles need a composition; the grouping itself is fixed.
    val allGroups = ToolGroups.map { (title, tools) -> stringResource(title) to tools }
    // Composing the sixty rows is deferred and staggered by SettingsGroup
    // itself now — see [rememberGroupRevealed] — so the screen no longer
    // needs its own gate on the opening animation.
    for ((groupTitle, tools) in allGroups) {
        SettingsGroup(groupTitle) {
            for (tool in tools) {
                item {
                    val paint = paints[tool]
                    // A tool with no key cannot be switched on at all: the
                    // keyboard would draw a button whose panel only apologises.
                    // The row still opens, because the key field is inside it.
                    val usable = isUsableTool(tool, settings)
                    WmRow(
                        title = stringResource(toolTitle(tool)),
                        subtitle = if (usable) {
                            stringResource(toolDescription(tool))
                        } else {
                            stringResource(R.string.tools_needs_key_subtitle)
                        },
                        leading = {
                            SlotIcon(
                                IconSlots.forTool(tool),
                                contentDescription = null,
                                modifier = Modifier
                                    .wmSharedElement(takeOffKey("icon", toolRoute(tool))),
                                tint = paint?.color
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                brush = paint?.brush,
                            )
                        },
                        flightTo = toolRoute(tool),
                        subtitleFlies = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (toolHasOptions(tool)) {
                                    Icon(
                                        Icons.Outlined.Tune,
                                        contentDescription = optionsDesc,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = usable && tool in settings.enabledTools,
                                    onCheckedChange = { enabled ->
                                        scope.launch { repository.setToolEnabled(tool, enabled) }
                                    },
                                    enabled = usable,
                                    modifier = Modifier
                                        .wmSharedElement(takeOffKey("switch", toolRoute(tool))),
                                )
                            }
                        },
                        onClick = { onOpenTool(tool) },
                    )
                }
            }
        }
    }
}

/**
 * The Tools screen's sections, as string resource and tools.
 *
 * A top-level value rather than a list built in composition: the grouping
 * never changes, and rebuilding it (and the sets behind the safety net below)
 * on every recomposition of a screen this size is work for nothing.
 *
 * The last group is the safety net: a tool added to the enum but forgotten
 * here still gets a settings entry, because this menu is the only path to a
 * tool's own options. Tools this build cannot provide (the lite flavor) are
 * filtered out, and a group left empty by that is dropped.
 */
private val ToolGroups: List<Pair<Int, List<ToolbarTool>>> = buildList {
    add(
        R.string.tools_group_panels_title to listOf(
            ToolbarTool.EMOJI, ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS,
            ToolbarTool.TEXT_EDIT, ToolbarTool.NUMPAD, ToolbarTool.HANDWRITING,
            ToolbarTool.VOICE, ToolbarTool.CAMERA, ToolbarTool.DICTIONARY,
            ToolbarTool.GRAMMAR, ToolbarTool.APP_LAUNCHER,
        ),
    )
    add(
        R.string.tools_group_scanners_title to listOf(
            ToolbarTool.OCR, ToolbarTool.QR_SCAN, ToolbarTool.DOC_SCAN,
        ),
    )
    add(
        R.string.tools_group_online_title to listOf(
            ToolbarTool.TRANSLATE, ToolbarTool.GIF, ToolbarTool.STICKER,
            ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH,
            ToolbarTool.WIKIPEDIA, ToolbarTool.CURRENCY, ToolbarTool.AI,
        ),
    )
    add(
        R.string.tools_group_create_title to listOf(
            ToolbarTool.SYMBOLS, ToolbarTool.CALCULATOR, ToolbarTool.UNIT_CONVERT,
            ToolbarTool.QR_GEN, ToolbarTool.PASSWORD_GEN, ToolbarTool.TYPING_TEST,
        ),
    )
    add(
        R.string.tools_group_modes_title to listOf(
            ToolbarTool.MODES, ToolbarTool.ONE_HANDED, ToolbarTool.SPLIT, ToolbarTool.FLOATING,
            ToolbarTool.RESIZE,
        ),
    )
    add(R.string.tools_group_cursor_title to (CursorTools + ToolbarTool.HIDE_KEYBOARD))
    add(
        R.string.tools_group_quick_actions_title to listOf(
            ToolbarTool.UNDO, ToolbarTool.REDO, ToolbarTool.AUTOCORRECT,
            ToolbarTool.FANCY, ToolbarTool.INCOGNITO, ToolbarTool.SOUND_HAPTICS,
            ToolbarTool.THEMES, ToolbarTool.POWER_SAVING, ToolbarTool.SETTINGS,
        ),
    )
    add(
        R.string.tools_group_utilities_title to listOf(
            ToolbarTool.FLASHLIGHT, ToolbarTool.COMPASS, ToolbarTool.LEVEL,
            ToolbarTool.CALENDAR, ToolbarTool.WEATHER, ToolbarTool.MOON_PHASE,
        ),
    )
    val grouped = flatMapTo(HashSet()) { it.second }
    val ungrouped = ToolbarTool.entries.filterNot { it in grouped }
    if (ungrouped.isNotEmpty()) add(R.string.tools_group_other_title to ungrouped)
}.map { (title, tools) -> title to tools.filter(::isSupportedTool) }
    .filter { it.second.isNotEmpty() }

/**
 * A tool's own settings page, as flights name it. Not the navigation route
 * (`tool/{toolName}`), which is the same string for every tool and would put
 * one key on all of them.
 */
internal fun toolRoute(tool: ToolbarTool): String = "tool/${tool.name}"

/** A tool's glyph at heading size — the icon pack's, if the user installed one. */
@Composable
private fun ToolGlyph(tool: ToolbarTool, brush: Brush? = null) {
    SlotIcon(
        IconSlots.forTool(tool),
        contentDescription = null,
        modifier = Modifier.size(HeaderGlyphSize),
        brush = brush,
    )
}

/** One tool's screen: the enable switch plus every setting the tool has. */
@Composable
private fun ToolDetailSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    tool: ToolbarTool,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val percentFormat = stringResource(R.string.typing_value_percent)
    val dpFormat = stringResource(R.string.typing_value_dp)
    val msFormat = stringResource(R.string.typing_value_milliseconds)
    val minutesFormat = stringResource(R.string.values_minutes)
    val hoursFormat = stringResource(R.string.values_hours)
    val pixelsFormat = stringResource(R.string.values_pixels)
    val daysFormat = stringResource(R.string.values_days)
    val daysAheadFormat = stringResource(R.string.values_days_ahead)
    CaptionText(
        stringResource(toolDescription(tool)),
        modifier = Modifier.wmSharedBounds(landingKey("subtitle")),
    )
    SettingsGroup {
        item {
            // The search tools need a key before they can be switched on; the
            // field that takes one is further down this same screen.
            val usable = isUsableTool(tool, settings)
            ToggleSetting(
                CommonR.string.common_enable,
                if (usable) {
                    stringResource(R.string.tooldetail_enabled_subtitle)
                } else {
                    stringResource(R.string.tooldetail_enabled_needs_key_subtitle)
                },
                usable && tool in settings.enabledTools,
                switchKey = landingKey("switch"),
                enabled = usable,
                default = usable && tool in SettingsDefaults.enabledTools,
            ) { scope.launch { repository.setToolEnabled(tool, it) } }
        }
        // Recolour just this tool's icon. Only meaningful while the global
        // "Colorful tool icons" switch is on, since it's what paints them.
        if (settings.coloredToolIcons) {
            val gradient = settings.toolIconGradients
            item {
                ToolColourRow(
                    // With the gradients off there is one colour and it needs
                    // no qualifier; with them on the two rows have to say which
                    // end of the gradient each one is.
                    title = stringResource(
                        if (gradient) R.string.tooldetail_icon_colour_start_title
                        else R.string.tooldetail_icon_colour_title,
                    ),
                    toolName = stringResource(toolTitle(tool)),
                    override = settings.toolColorOverrides[tool],
                    default = toolAccentColorArgb(tool),
                    onPick = { scope.launch { repository.setToolColor(tool, it) } },
                )
            }
            if (gradient) {
                item {
                    ToolColourRow(
                        title = stringResource(R.string.tooldetail_icon_colour_end_title),
                        toolName = stringResource(toolTitle(tool)),
                        override = settings.toolColorEndOverrides[tool],
                        // Derived from whichever colour the near end currently
                        // is, so the pair moves together until it is pinned.
                        default = toolAccentEndColorArgb(tool, settings.toolColorOverrides),
                        onPick = { scope.launch { repository.setToolColorEnd(tool, it) } },
                    )
                }
            }
        }
    }
    ToolKeywordSetting(repository, settings, tool)
    when (tool) {
        ToolbarTool.APP_LAUNCHER ->
            SettingsGroup(stringResource(R.string.tooldetail_launcher_group)) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_launcher_sort_title,
                        subtitle = stringResource(R.string.tooldetail_launcher_sort_subtitle),
                        options = listOf(
                            AppSortOrder.ALPHABETICAL to
                                stringResource(R.string.tooldetail_launcher_sort_alpha_label),
                            AppSortOrder.RECENT_FIRST to
                                stringResource(R.string.tooldetail_launcher_sort_recent_label),
                        ),
                        selected = settings.launcher.sortOrder,
                        default = SettingsDefaults.launcher.sortOrder,
                    ) { scope.launch { repository.setLauncherSortOrder(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_labels_title,
                        stringResource(R.string.tooldetail_launcher_labels_subtitle),
                        settings.launcher.showLabels,
                        default = SettingsDefaults.launcher.showLabels,
                    ) { scope.launch { repository.setLauncherShowLabels(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_recents_title,
                        stringResource(R.string.tooldetail_launcher_recents_subtitle),
                        settings.launcher.recentsEnabled,
                        info = stringResource(R.string.tooldetail_launcher_recents_info),
                        default = SettingsDefaults.launcher.recentsEnabled,
                    ) { scope.launch { repository.setLauncherRecentsEnabled(it) } }
                }
                if (settings.launcher.recentsEnabled) {
                    item {
                        val appsFormat = stringResource(R.string.values_number)
                        SliderSetting(
                            R.string.tooldetail_launcher_recents_count_title,
                            subtitle = stringResource(
                                R.string.tooldetail_launcher_recents_count_subtitle,
                            ),
                            value = settings.launcher.maxRecents.toFloat(),
                            range = LauncherToolSettings.RECENTS_RANGE.first.toFloat()..
                                LauncherToolSettings.RECENTS_RANGE.last.toFloat(),
                            display = { appsFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.tooldetail_launcher_recents_count_info),
                            default = SettingsDefaults.launcher.maxRecents.toFloat(),
                        ) { scope.launch { repository.setLauncherMaxRecents(it.roundToInt()) } }
                    }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_drilldown_title,
                        stringResource(R.string.tooldetail_launcher_drilldown_subtitle),
                        settings.launcher.activityDrilldown,
                        info = stringResource(R.string.tooldetail_launcher_drilldown_info),
                        default = SettingsDefaults.launcher.activityDrilldown,
                    ) { scope.launch { repository.setLauncherActivityDrilldown(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_launcher_non_exported_title,
                        stringResource(R.string.tooldetail_launcher_non_exported_subtitle),
                        settings.launcher.showNonExported,
                        info = stringResource(R.string.tooldetail_launcher_non_exported_info),
                        default = SettingsDefaults.launcher.showNonExported,
                    ) { scope.launch { repository.setLauncherShowNonExported(it) } }
                }
            }
        ToolbarTool.PLUGINS -> SettingsGroup(stringResource(R.string.tooldetail_plugins_group)) {
            item {
                WmRow(
                    title = stringResource(R.string.tooldetail_plugins_manage_title),
                    subtitle = stringResource(R.string.tooldetail_plugins_manage_subtitle),
                    onClick = { onNavigate("plugins") },
                )
            }
        }
        ToolbarTool.EMOJI -> SettingsGroup(stringResource(R.string.tooldetail_emoji_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_emoji_toolbar_title,
                    stringResource(R.string.tooldetail_emoji_toolbar_subtitle),
                    settings.emojiToolbar,
                    default = SettingsDefaults.emojiToolbar,
                ) { scope.launch { repository.setEmojiToolbar(it) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_emoji_all_title,
                    stringResource(R.string.tooldetail_emoji_all_subtitle),
                    onClick = { onNavigate("emoji") },
                )
            }
        }
        ToolbarTool.SNIPPETS -> SettingsGroup(stringResource(R.string.tooldetail_snippets_group)) {
            item {
                NavRow(
                    R.string.tooldetail_snippets_all_title,
                    stringResource(R.string.tooldetail_snippets_all_subtitle),
                    onClick = { onNavigate("expander") },
                )
            }
        }
        ToolbarTool.CLIPBOARD -> SettingsGroup(stringResource(R.string.tooldetail_clipboard_group)) {
            item {
                NavRow(
                    R.string.tooldetail_clipboard_all_title,
                    stringResource(R.string.tooldetail_clipboard_all_subtitle),
                    onClick = { onNavigate("clipboard") },
                )
            }
        }
        ToolbarTool.SPLIT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                SliderSetting(
                    R.string.tooldetail_split_gap_title,
                    subtitle = stringResource(R.string.tooldetail_split_gap_subtitle),
                    value = settings.splitGapPercent.toFloat(),
                    range = 5f..40f,
                    display = { percentFormat.format(it.toInt()) },
                    default = SettingsDefaults.splitGapPercent.toFloat(),
                ) { scope.launch { repository.setSplitGapPercent(it.toInt()) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_split_subtitle),
                    onClick = { onNavigate("layout") },
                )
            }
        }
        ToolbarTool.FLOATING -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                SliderSetting(
                    R.string.tooldetail_floating_width_title,
                    subtitle = stringResource(R.string.tooldetail_floating_width_subtitle),
                    value = settings.floatingWidthDp.toFloat(),
                    range = 240f..500f,
                    display = { dpFormat.format(it.toInt()) },
                    default = SettingsDefaults.floatingWidthDp.toFloat(),
                ) { scope.launch { repository.setFloatingWidthDp(it.toInt()) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_floating_subtitle),
                    onClick = { onNavigate("layout") },
                )
            }
        }
        ToolbarTool.FLASHLIGHT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_flashlight_auto_off_title,
                    stringResource(R.string.tooldetail_flashlight_auto_off_subtitle),
                    settings.flashlightAutoOff,
                    info = stringResource(R.string.tooldetail_flashlight_auto_off_info),
                    default = SettingsDefaults.flashlightAutoOff,
                ) { scope.launch { repository.setFlashlightAutoOff(it) } }
            }
        }
        ToolbarTool.COMPASS -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_compass_degrees_title,
                        stringResource(R.string.tooldetail_compass_degrees_subtitle),
                        settings.compassShowDegrees,
                        default = SettingsDefaults.compassShowDegrees,
                    ) { scope.launch { repository.setCompassShowDegrees(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_compass_qibla_title,
                        stringResource(R.string.tooldetail_compass_qibla_subtitle),
                        settings.compassShowQibla,
                        info = stringResource(R.string.tooldetail_compass_qibla_info),
                        default = SettingsDefaults.compassShowQibla,
                    ) { scope.launch { repository.setCompassShowQibla(it) } }
                }
            }
            if (settings.compassShowQibla && settings.weatherLatitude == null) {
                CaptionText(
                    stringResource(R.string.tooldetail_compass_no_location_error),
                    error = true,
                )
            }
        }
        ToolbarTool.LEVEL -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_level_angles_title,
                    stringResource(R.string.tooldetail_level_angles_subtitle),
                    settings.levelShowAngles,
                    default = SettingsDefaults.levelShowAngles,
                ) { scope.launch { repository.setLevelShowAngles(it) } }
            }
        }
        ToolbarTool.UNDO, ToolbarTool.REDO ->
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_redo_ctrl_y_title,
                        stringResource(R.string.tooldetail_redo_ctrl_y_subtitle),
                        settings.redoUsesCtrlY,
                        info = stringResource(R.string.tooldetail_redo_ctrl_y_info),
                        default = SettingsDefaults.redoUsesCtrlY,
                    ) { scope.launch { repository.setRedoUsesCtrlY(it) } }
                }
            }
        ToolbarTool.MOON_PHASE -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_moon_southern_title,
                    stringResource(R.string.tooldetail_moon_southern_subtitle),
                    settings.moonSouthernHemisphere,
                    // Not SettingsDefaults: this one starts from the device's
                    // region, so reset has to land back on that and not on
                    // the northern hemisphere the data class declares.
                    default = isSouthernHemisphere(repository.deviceRegion),
                ) { scope.launch { repository.setMoonSouthernHemisphere(it) } }
            }
        }
        ToolbarTool.WEATHER -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item { WeatherLocationSetting(repository, settings) }
                item {
                    ToggleSetting(
                        R.string.tooldetail_weather_fahrenheit_title,
                        stringResource(R.string.tooldetail_weather_fahrenheit_subtitle),
                        settings.weatherFahrenheit,
                        default = SettingsDefaults.weatherFahrenheit,
                    ) { scope.launch { repository.setWeatherFahrenheit(it) } }
                }
                item {
                    val weatherMinutesFormat = stringResource(R.string.values_minutes)
                    SliderSetting(
                        R.string.tooldetail_weather_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_weather_refresh_subtitle),
                        value = settings.toolLimits.weatherRefreshMinutes.toFloat(),
                        range = 1f..180f,
                        display = { weatherMinutesFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_weather_refresh_info),
                        default = SettingsDefaults.toolLimits.weatherRefreshMinutes.toFloat(),
                    ) { picked ->
                        scope.launch { repository.setWeatherRefreshMinutes(picked.roundToInt()) }
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_weather_info))
        }
        ToolbarTool.CALENDAR -> {
            val showsHijri = settings.calendarAltOne == AltCalendar.HIJRI ||
                settings.calendarAltTwo == AltCalendar.HIJRI
            SettingsGroup(stringResource(R.string.tooldetail_calendar_group)) {
                item {
                    AltCalendarSetting(
                        title = stringResource(R.string.tooldetail_calendar_first_title),
                        subtitle = stringResource(R.string.tooldetail_calendar_first_subtitle),
                        selected = settings.calendarAltOne,
                        onChange = { scope.launch { repository.setCalendarAltOne(it) } },
                    )
                }
                item {
                    AltCalendarSetting(
                        title = stringResource(R.string.tooldetail_calendar_second_title),
                        subtitle = stringResource(R.string.tooldetail_calendar_second_subtitle),
                        selected = settings.calendarAltTwo,
                        onChange = { scope.launch { repository.setCalendarAltTwo(it) } },
                    )
                }
                item {
                    WeekendSetting(settings.calendarWeekend) {
                        scope.launch { repository.setCalendarWeekend(it) }
                    }
                }
                if (showsHijri) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_calendar_hijri_title,
                            subtitle = stringResource(R.string.tooldetail_calendar_hijri_subtitle),
                            value = settings.hijriAdjustDays.toFloat(),
                            range = -2f..2f,
                            display = { days ->
                                val d = days.roundToInt()
                                if (d > 0) daysAheadFormat.format(d) else daysFormat.format(d)
                            },
                            info = stringResource(R.string.tooldetail_calendar_hijri_info),
                            default = SettingsDefaults.hijriAdjustDays.toFloat(),
                        ) { scope.launch { repository.setHijriAdjustDays(it.roundToInt()) } }
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_calendar_info))
            CaptionText(stringResource(R.string.tooldetail_calendar_events_info))
        }
        ToolbarTool.CAMERA -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_front_title,
                        stringResource(R.string.tooldetail_camera_front_subtitle),
                        settings.camera.preferFront,
                        default = SettingsDefaults.camera.preferFront,
                    ) { scope.launch { repository.setCameraPreferFront(it) } }
                }
                item {
                    val offLabel = stringResource(CommonR.string.common_off)
                    val secondsFormat = stringResource(R.string.values_seconds)
                    ChoiceSetting(
                        R.string.tooldetail_camera_timer_title,
                        subtitle = stringResource(R.string.tooldetail_camera_timer_subtitle),
                        options = listOf(
                            0 to offLabel,
                            3 to secondsFormat.format(3),
                            10 to secondsFormat.format(10),
                        ),
                        selected = settings.camera.timerSeconds,
                        info = stringResource(R.string.tooldetail_camera_timer_info),
                        default = SettingsDefaults.camera.timerSeconds,
                    ) { scope.launch { repository.setCameraTimerSeconds(it) } }
                }
                item {
                    val pxFormat = stringResource(R.string.values_pixels)
                    ChoiceSetting(
                        R.string.tooldetail_camera_resolution_title,
                        subtitle = stringResource(R.string.tooldetail_camera_resolution_subtitle),
                        options = listOf(
                            1000 to pxFormat.format(1000),
                            1600 to pxFormat.format(1600),
                            2400 to pxFormat.format(2400),
                            3200 to pxFormat.format(3200),
                        ),
                        selected = settings.camera.captureMaxPx,
                        info = stringResource(R.string.tooldetail_camera_resolution_info),
                        default = SettingsDefaults.camera.captureMaxPx,
                    ) { scope.launch { repository.setCameraCaptureMaxPx(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_mirror_title,
                        stringResource(R.string.tooldetail_camera_mirror_subtitle),
                        settings.camera.mirrorFront,
                        info = stringResource(R.string.tooldetail_camera_mirror_info),
                        default = SettingsDefaults.camera.mirrorFront,
                    ) { scope.launch { repository.setCameraMirrorFront(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_fullframe_title,
                        stringResource(R.string.tooldetail_camera_fullframe_subtitle),
                        settings.camera.fullFrame,
                        info = stringResource(R.string.tooldetail_camera_fullframe_info),
                        default = SettingsDefaults.camera.fullFrame,
                    ) { scope.launch { repository.setCameraFullFrame(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_gallery_title,
                        stringResource(R.string.tooldetail_camera_gallery_subtitle),
                        settings.camera.saveToGallery,
                        info = stringResource(R.string.tooldetail_camera_gallery_info),
                        default = SettingsDefaults.camera.saveToGallery,
                    ) { scope.launch { repository.setCameraSaveToGallery(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_camera_feedback_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_shutter_title,
                        stringResource(R.string.tooldetail_camera_shutter_subtitle),
                        settings.camera.shutterSound,
                        default = SettingsDefaults.camera.shutterSound,
                    ) { scope.launch { repository.setCameraShutterSound(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_camera_haptics_title,
                        stringResource(R.string.tooldetail_camera_haptics_subtitle),
                        settings.camera.haptics,
                        info = stringResource(R.string.tooldetail_camera_haptics_info),
                        default = SettingsDefaults.camera.haptics,
                    ) { scope.launch { repository.setCameraHaptics(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_camera_info))
        }
        ToolbarTool.DICTIONARY -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_dictionary_auto_title,
                        stringResource(R.string.tooldetail_dictionary_auto_subtitle),
                        settings.dictionaryAutoLookup,
                        default = SettingsDefaults.dictionaryAutoLookup,
                    ) { scope.launch { repository.setDictionaryAutoLookup(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_dictionary_info))
        }
        ToolbarTool.TEXT_EDIT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                SliderSetting(
                    R.string.tooldetail_text_edit_repeat_title,
                    subtitle = stringResource(R.string.tooldetail_text_edit_repeat_subtitle),
                    value = settings.textEditing.repeatMs.toFloat(),
                    range = 30f..200f,
                    display = { msFormat.format(it.toInt()) },
                    default = SettingsDefaults.textEditing.repeatMs.toFloat(),
                ) { scope.launch { repository.setTextEditRepeatMs(it.toInt()) } }
            }
            // The panel's own grid, edited on its own screen the way a key layout
            // is: rows, widths, spans, and a second action per key.
            item {
                NavRow(
                    title = R.string.textedit_layout_title,
                    subtitle = stringResource(R.string.textedit_layout_subtitle),
                    value = stringResource(
                        if (settings.textEditing.layout == null) {
                            R.string.textedit_layout_value_default
                        } else {
                            R.string.textedit_layout_value_custom
                        },
                    ),
                ) { onNavigate(ROUTE_TEXT_EDIT_LAYOUT) }
            }
        }
        ToolbarTool.SELECT_MODE -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_hold_title,
                        stringResource(R.string.tooldetail_select_mode_hold_subtitle),
                        settings.textEditing.selectionModeHold,
                        info = stringResource(R.string.tooldetail_select_mode_hold_info),
                        default = SettingsDefaults.textEditing.selectionModeHold,
                    ) { scope.launch { repository.setSelectionModeHold(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_select_mode_taps_title,
                        stringResource(R.string.tooldetail_select_mode_taps_subtitle),
                        settings.textEditing.selectionModeMultiTap,
                        info = stringResource(R.string.tooldetail_select_mode_taps_info),
                        default = SettingsDefaults.textEditing.selectionModeMultiTap,
                    ) { scope.launch { repository.setSelectionModeMultiTap(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_select_mode_info))
        }
        // The caret movers, which are the only tools a hold repeats. Home, End
        // and the two select tools are not in the set — a second press of those
        // lands exactly where the first one did — so their pages stay plain.
        in HoldRepeatCursorTools -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_title,
                        stringResource(R.string.tooldetail_cursor_repeat_subtitle),
                        settings.textEditing.cursorToolsRepeatOnHold,
                        info = stringResource(R.string.tooldetail_cursor_repeat_info),
                        default = SettingsDefaults.textEditing.cursorToolsRepeatOnHold,
                    ) { scope.launch { repository.setCursorToolsRepeatOnHold(it) } }
                }
                item {
                    // This one is per tool, unlike the switch above: turning it
                    // on costs *this* tool the toolbox hold that opens its
                    // settings page, so it is answered a tool at a time.
                    ToggleSetting(
                        R.string.tooldetail_cursor_repeat_toolbox_title,
                        stringResource(R.string.tooldetail_cursor_repeat_toolbox_subtitle),
                        tool in settings.textEditing.toolboxRepeatTools,
                        info = stringResource(R.string.tooldetail_cursor_repeat_toolbox_info),
                        default = tool in SettingsDefaults.textEditing.toolboxRepeatTools,
                    ) { scope.launch { repository.setToolboxRepeat(tool, it) } }
                }
                // The speed is shared, so it shows while either surface repeats.
                if (settings.textEditing.cursorToolsRepeatOnHold ||
                    tool in settings.textEditing.toolboxRepeatTools
                ) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_text_edit_repeat_title,
                            subtitle = stringResource(
                                R.string.tooldetail_cursor_repeat_speed_subtitle,
                            ),
                            value = settings.textEditing.repeatMs.toFloat(),
                            range = 30f..200f,
                            display = { msFormat.format(it.toInt()) },
                            default = SettingsDefaults.textEditing.repeatMs.toFloat(),
                        ) { scope.launch { repository.setTextEditRepeatMs(it.toInt()) } }
                    }
                }
            }
        }
        ToolbarTool.NUMPAD -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_numpad_calc_title,
                    stringResource(R.string.tooldetail_numpad_calc_subtitle),
                    settings.numpadCalculatorLayout,
                    default = SettingsDefaults.numpadCalculatorLayout,
                ) { scope.launch { repository.setNumpadCalculatorLayout(it) } }
            }
        }
        ToolbarTool.INCOGNITO -> {
            SettingsGroup(stringResource(R.string.tooldetail_incognito_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_learning_title,
                        stringResource(R.string.tooldetail_incognito_learning_subtitle),
                        settings.incognitoPausesLearning,
                        default = SettingsDefaults.incognitoPausesLearning,
                    ) { scope.launch { repository.setIncognitoPausesLearning(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_clipboard_title,
                        stringResource(R.string.tooldetail_incognito_clipboard_subtitle),
                        settings.incognitoPausesClipboard,
                        default = SettingsDefaults.incognitoPausesClipboard,
                    ) { scope.launch { repository.setIncognitoPausesClipboard(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_incognito_auto_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_incognito_auto_title,
                        stringResource(R.string.tooldetail_incognito_auto_subtitle),
                        settings.autoIncognito,
                        info = stringResource(AUTO_INCOGNITO_INFO),
                        default = SettingsDefaults.autoIncognito,
                    ) { scope.launch { repository.setAutoIncognito(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_incognito_info))
        }
        ToolbarTool.POWER_SAVING -> {
            val ps = settings.powerSaving
            SettingsGroup(stringResource(R.string.tooldetail_power_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_now_title,
                        stringResource(R.string.tooldetail_power_now_subtitle),
                        ps.manual,
                        info = stringResource(R.string.tooldetail_power_now_info),
                        default = SettingsDefaults.powerSaving.manual,
                    ) { scope.launch { repository.setPowerSavingManual(it) } }
                }
                item {
                    ChoiceSetting(
                        R.string.tooldetail_power_trigger_title,
                        subtitle = stringResource(R.string.tooldetail_power_trigger_subtitle),
                        info = stringResource(R.string.tooldetail_power_trigger_info),
                        options = PowerSavingTrigger.entries.map { it to stringResource(it.labelRes) },
                        selected = ps.trigger,
                        default = SettingsDefaults.powerSaving.trigger,
                    ) { scope.launch { repository.setPowerSavingTrigger(it) } }
                }
                if (ps.trigger == PowerSavingTrigger.LOW_BATTERY ||
                    ps.trigger == PowerSavingTrigger.EITHER
                ) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_power_battery_title,
                            subtitle = stringResource(R.string.tooldetail_power_battery_subtitle),
                            value = ps.batteryPercent.toFloat(),
                            range = 5f..50f,
                            display = { percentFormat.format(it.toInt()) },
                            default = SettingsDefaults.powerSaving.batteryPercent.toFloat(),
                        ) { scope.launch { repository.setPowerSavingBatteryPercent(it.toInt()) } }
                    }
                }
                if (ps.trigger != PowerSavingTrigger.OFF) {
                    item {
                        ToggleSetting(
                            R.string.tooldetail_power_charging_title,
                            stringResource(R.string.tooldetail_power_charging_subtitle),
                            ps.offWhileCharging,
                            info = stringResource(R.string.tooldetail_power_charging_info),
                            default = SettingsDefaults.powerSaving.offWhileCharging,
                        ) { scope.launch { repository.setPowerSavingOffWhileCharging(it) } }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_power_drop_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_haptics_title,
                        stringResource(R.string.tooldetail_power_drop_haptics_subtitle),
                        ps.dropHaptics,
                        default = SettingsDefaults.powerSaving.dropHaptics,
                    ) { scope.launch { repository.setPowerSavingDropHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_sound_title,
                        stringResource(R.string.tooldetail_power_drop_sound_subtitle),
                        ps.dropKeySound,
                        default = SettingsDefaults.powerSaving.dropKeySound,
                    ) { scope.launch { repository.setPowerSavingDropKeySound(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_anim_title,
                        stringResource(R.string.tooldetail_power_drop_anim_subtitle),
                        ps.dropAnimations,
                        default = SettingsDefaults.powerSaving.dropAnimations,
                    ) { scope.launch { repository.setPowerSavingDropAnimations(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_trail_title,
                        stringResource(R.string.tooldetail_power_drop_trail_subtitle),
                        ps.dropGlideTrail,
                        info = stringResource(R.string.tooldetail_power_drop_trail_info),
                        default = SettingsDefaults.powerSaving.dropGlideTrail,
                    ) { scope.launch { repository.setPowerSavingDropGlideTrail(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_popup_title,
                        stringResource(R.string.tooldetail_power_drop_popup_subtitle),
                        ps.dropKeyPopup,
                        default = SettingsDefaults.powerSaving.dropKeyPopup,
                    ) { scope.launch { repository.setPowerSavingDropKeyPopup(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_glide_title,
                        stringResource(R.string.tooldetail_power_drop_glide_subtitle),
                        ps.dropGestureTyping,
                        info = stringResource(R.string.tooldetail_power_drop_glide_info),
                        default = SettingsDefaults.powerSaving.dropGestureTyping,
                    ) { scope.launch { repository.setPowerSavingDropGestureTyping(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_emoji_title,
                        stringResource(R.string.tooldetail_power_drop_emoji_subtitle),
                        ps.dropEmojiPrediction,
                        default = SettingsDefaults.powerSaving.dropEmojiPrediction,
                    ) { scope.launch { repository.setPowerSavingDropEmojiPrediction(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_chips_title,
                        stringResource(R.string.tooldetail_power_drop_chips_subtitle),
                        ps.dropSmartChips,
                        default = SettingsDefaults.powerSaving.dropSmartChips,
                    ) { scope.launch { repository.setPowerSavingDropSmartChips(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_network_title,
                        stringResource(R.string.tooldetail_power_drop_network_subtitle),
                        ps.dropBackgroundNetwork,
                        info = stringResource(R.string.tooldetail_power_drop_network_info),
                        default = SettingsDefaults.powerSaving.dropBackgroundNetwork,
                    ) { scope.launch { repository.setPowerSavingDropBackgroundNetwork(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_screenshot_title,
                        stringResource(R.string.tooldetail_power_drop_screenshot_subtitle),
                        ps.dropScreenshotWatch,
                        default = SettingsDefaults.powerSaving.dropScreenshotWatch,
                    ) { scope.launch { repository.setPowerSavingDropScreenshotWatch(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_models_title,
                        stringResource(R.string.tooldetail_power_drop_models_subtitle),
                        ps.dropOnDeviceModels,
                        info = stringResource(R.string.tooldetail_power_drop_models_info),
                        default = SettingsDefaults.powerSaving.dropOnDeviceModels,
                    ) { scope.launch { repository.setPowerSavingDropOnDeviceModels(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_power_drop_stats_title,
                        stringResource(R.string.tooldetail_power_drop_stats_subtitle),
                        ps.dropTypingStats,
                        default = SettingsDefaults.powerSaving.dropTypingStats,
                    ) { scope.launch { repository.setPowerSavingDropTypingStats(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_power_info))
        }
        ToolbarTool.AUTOCORRECT -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_autocorrect_title,
                    stringResource(R.string.tooldetail_autocorrect_subtitle),
                    settings.autocorrect,
                    default = SettingsDefaults.autocorrect,
                ) { scope.launch { repository.setAutocorrect(it) } }
            }
            item {
                NavRow(
                    R.string.tooldetail_typing_nav_title,
                    stringResource(R.string.tooldetail_typing_nav_subtitle),
                    onClick = { onNavigate("typing") },
                )
            }
        }
        ToolbarTool.FANCY -> {
            val behavior = settings.layoutBehavior
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    // "Follow the strip" is the empty pick, so the tool can go
                    // back to starting from whatever style was last used.
                    val follow = stringResource(R.string.tooldetail_fancy_style_follow)
                    ChoiceSetting(
                        R.string.tooldetail_fancy_style_title,
                        subtitle = stringResource(R.string.tooldetail_fancy_style_subtitle),
                        info = stringResource(R.string.tooldetail_fancy_style_info),
                        options = listOf<Pair<String?, String>>(null to follow) +
                            FancyStyles.all.map { it.id to it.sample },
                        // No reset control: this row's default *is* the "follow
                        // the strip" option, which is already one press away in
                        // the list, and a null default is how [ChoiceSetting]
                        // spells "no one right answer".
                        selected = behavior.fancyToolStyleId
                            ?.takeIf { FancyStyles.byId(it) != null },
                    ) { scope.launch { repository.setFancyToolStyle(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_fancy_keep_title,
                        stringResource(R.string.tooldetail_fancy_keep_subtitle),
                        behavior.fancyToolKeepsLanguage,
                        info = stringResource(R.string.tooldetail_fancy_keep_info),
                        default = SettingsDefaults.layoutBehavior.fancyToolKeepsLanguage,
                    ) { scope.launch { repository.setFancyToolKeepsLanguage(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_fancy_auto_off_title,
                        stringResource(R.string.tooldetail_fancy_auto_off_subtitle),
                        behavior.fancyToolAutoOff,
                        info = stringResource(R.string.tooldetail_fancy_auto_off_info),
                        default = SettingsDefaults.layoutBehavior.fancyToolAutoOff,
                    ) { scope.launch { repository.setFancyToolAutoOff(it) } }
                }
                item {
                    NavRow(
                        R.string.tooldetail_fancy_language_nav_title,
                        stringResource(R.string.tooldetail_fancy_language_nav_subtitle),
                        onClick = { onNavigate("language/${FancyStyles.LANG_ID}") },
                    )
                }
            }
            CaptionText(stringResource(R.string.tooldetail_fancy_info))
        }
        ToolbarTool.SOUND_HAPTICS -> {
            KeySoundGroup(repository, settings, onNavigate) {
                item {
                    NavRow(
                        R.string.tooldetail_keypress_nav_title,
                        stringResource(R.string.tooldetail_keypress_nav_subtitle),
                        onClick = { onNavigate("keypress") },
                    )
                }
            }
            CaptionText(stringResource(R.string.tooldetail_sound_haptics_info))
        }
        ToolbarTool.HANDWRITING -> {
            SettingsGroup(stringResource(R.string.tooldetail_handwriting_input_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_stylus_title,
                        stringResource(R.string.tooldetail_handwriting_stylus_subtitle),
                        settings.handwritingStylusOnly,
                        info = stringResource(R.string.tooldetail_handwriting_stylus_info),
                        default = SettingsDefaults.handwritingStylusOnly,
                    ) { scope.launch { repository.setHandwritingStylusOnly(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_handwriting_auto_space_title,
                        stringResource(R.string.tooldetail_handwriting_auto_space_subtitle),
                        settings.handwritingAutoSpace,
                        default = SettingsDefaults.handwritingAutoSpace,
                    ) { scope.launch { repository.setHandwritingAutoSpace(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_handwriting_pause_title,
                        subtitle = stringResource(R.string.tooldetail_handwriting_pause_subtitle),
                        value = settings.handwritingCommitDelayMs.toFloat(),
                        range = 300f..2000f,
                        display = { msFormat.format(it.roundToInt()) },
                        info = stringResource(R.string.tooldetail_handwriting_pause_info),
                        default = SettingsDefaults.handwritingCommitDelayMs.toFloat(),
                    ) { scope.launch { repository.setHandwritingCommitDelayMs(it.roundToInt()) } }
                }
            }
            SectionHeader(stringResource(R.string.tooldetail_handwriting_models_header))
            CaptionText(stringResource(R.string.tooldetail_handwriting_models_info))
            HandwritingModelManager(settings)
            SettingsGroup {
                item {
                    NavRow(
                        R.string.tooldetail_handwriting_languages_title,
                        stringResource(R.string.tooldetail_handwriting_languages_subtitle),
                        onClick = { onNavigate("languages") },
                    )
                }
            }
        }
        ToolbarTool.THEMES -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_themes_nav_title,
                    stringResource(R.string.tooldetail_themes_nav_subtitle),
                    onClick = { onNavigate("themes") },
                )
            }
        }
        ToolbarTool.ONE_HANDED -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                NavRow(
                    R.string.tooldetail_layout_nav_title,
                    stringResource(R.string.tooldetail_layout_nav_one_handed_subtitle),
                    onClick = { onNavigate("layout") },
                )
            }
        }
        ToolbarTool.TRANSLATE -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item { TranslateLanguageSetting(repository, settings) }
            }
            SettingsGroup(stringResource(R.string.tooldetail_translate_key_group)) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_translate_key_label),
                        value = settings.translateApiKey,
                        builtInAvailable = ToolApiKeys.builtInTranslate,
                        emptyHint = stringResource(R.string.tooldetail_translate_key_hint),
                    ) { repository.setTranslateApiKey(it) }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_translate_info))
        }
        ToolbarTool.GIF, ToolbarTool.STICKER -> {
            if (tool == ToolbarTool.STICKER) {
                SettingsGroup(stringResource(R.string.tooldetail_sticker_packs_group)) {
                    item {
                        NavRow(
                            R.string.tooldetail_sticker_packs_title,
                            stringResource(R.string.tooldetail_sticker_packs_subtitle),
                            route = "sticker_packs",
                            onClick = { onNavigate("sticker_packs") },
                        )
                    }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_media_layout_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_media_full_bleed_title,
                        stringResource(R.string.tooldetail_media_full_bleed_subtitle),
                        settings.mediaFullBleed,
                        info = stringResource(R.string.tooldetail_media_full_bleed_info),
                        default = SettingsDefaults.mediaFullBleed,
                    ) { scope.launch { repository.setMediaFullBleed(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_media_keys_group)) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_media_klipy_label),
                        value = settings.klipyApiKey,
                        builtInAvailable = ToolApiKeys.builtInKlipy,
                        emptyHint = stringResource(R.string.tooldetail_media_klipy_hint),
                    ) {
                        repository.setKlipyApiKey(it)
                        // Categories were fetched with the old key; they are
                        // not this key's answer.
                        MediaCategoryCache.clear()
                    }
                }
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_media_giphy_label),
                        value = settings.giphyApiKey,
                        builtInAvailable = ToolApiKeys.builtInGiphy,
                        emptyHint = stringResource(R.string.tooldetail_media_giphy_hint),
                    ) {
                        repository.setGiphyApiKey(it)
                        MediaCategoryCache.clear()
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_media_info))
            // Resolved out here: the group builder lambda is not composable.
            val stickerOption = stringResource(R.string.tooldetail_media_send_sticker_option)
            val imageOption = stringResource(R.string.tooldetail_media_send_image_option)
            // One send mode per tool: a sticker tapped in the sticker panel is
            // sent with the sticker setting and a GIF tapped in the GIF panel
            // with the GIF one (see WMKeyboardService.onGifSelect), so showing
            // both on both pages only invited the user to change the one that
            // could not affect the panel they came from.
            SettingsGroup(stringResource(R.string.tooldetail_media_sending_group)) {
                item {
                    if (tool == ToolbarTool.STICKER) {
                        ChoiceSetting(
                            title = R.string.tooldetail_media_sticker_send_title,
                            subtitle = stringResource(R.string.tooldetail_media_sticker_send_subtitle),
                            info = stringResource(R.string.tooldetail_media_sticker_send_info),
                            options = listOf(
                                MediaSendMode.STICKER to stickerOption,
                                MediaSendMode.IMAGE to imageOption,
                            ),
                            selected = settings.stickerSendMode,
                            default = SettingsDefaults.stickerSendMode,
                        ) { scope.launch { repository.setStickerSendMode(it) } }
                    } else {
                        ChoiceSetting(
                            title = R.string.tooldetail_media_gif_send_title,
                            subtitle = stringResource(R.string.tooldetail_media_gif_send_subtitle),
                            info = stringResource(R.string.tooldetail_media_gif_send_info),
                            options = listOf(
                                MediaSendMode.IMAGE to imageOption,
                                MediaSendMode.STICKER to stickerOption,
                            ),
                            selected = settings.gifSendMode,
                            default = SettingsDefaults.gifSendMode,
                        ) { scope.launch { repository.setGifSendMode(it) } }
                    }
                }
            }
            SectionHeader(stringResource(R.string.tooldetail_media_sources_header))
            ChoiceControl(
                options = GifSourceMode.entries.map { mode ->
                    mode to when (mode) {
                        GifSourceMode.TABS -> stringResource(R.string.tooldetail_media_source_tabs)
                        GifSourceMode.MIX -> stringResource(R.string.tooldetail_media_source_mixed)
                    }
                },
                selected = settings.gifSourceMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { mode -> scope.launch { repository.setGifSourceMode(mode) } }
            CaptionText(stringResource(R.string.tooldetail_media_sources_info))
            SectionHeader(stringResource(R.string.tooldetail_media_filter_header))
            ChoiceControl(
                options = GifContentFilter.entries.map { filter ->
                    filter to when (filter) {
                        GifContentFilter.OFF -> stringResource(CommonR.string.common_off)
                        GifContentFilter.LOW -> stringResource(R.string.tooldetail_media_filter_low)
                        GifContentFilter.MEDIUM ->
                            stringResource(R.string.tooldetail_media_filter_medium)
                        GifContentFilter.HIGH -> stringResource(R.string.tooldetail_media_filter_high)
                    }
                },
                selected = settings.gifContentFilter,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { filter -> scope.launch { repository.setGifContentFilter(filter) } }
            CaptionText(stringResource(R.string.tooldetail_media_filter_info))
            SettingsGroup(stringResource(R.string.tooldetail_media_limit_group)) {
                item {
                    SliderSetting(
                        R.string.tooldetail_media_limit_title,
                        subtitle = stringResource(R.string.tooldetail_media_limit_subtitle),
                        value = settings.gifResultLimit.toFloat(),
                        range = 6f..48f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.gifResultLimit.toFloat(),
                    ) { scope.launch { repository.setGifResultLimit(it.roundToInt()) } }
                }
            }
        }
        ToolbarTool.WEB_SEARCH, ToolbarTool.IMAGE_SEARCH -> {
            SettingsGroup(stringResource(R.string.tooldetail_search_group)) {
                item {
                    ApiKeyField(
                        label = stringResource(R.string.tooldetail_search_key_label),
                        value = settings.braveApiKey,
                        builtInAvailable = ToolApiKeys.builtInBrave,
                        emptyHint = stringResource(R.string.tooldetail_search_key_hint),
                    ) { repository.setBraveApiKey(it) }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_search_info))
            SettingsGroup(stringResource(R.string.tooldetail_search_results_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_search_safe_title,
                        stringResource(R.string.tooldetail_search_safe_subtitle),
                        settings.searchSafe,
                        default = SettingsDefaults.searchSafe,
                    ) { scope.launch { repository.setSearchSafe(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_search_count_title,
                        subtitle = stringResource(R.string.tooldetail_search_count_subtitle),
                        value = settings.searchResultCount.toFloat(),
                        range = 1f..10f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.searchResultCount.toFloat(),
                    ) { scope.launch { repository.setSearchResultCount(it.roundToInt()) } }
                }
                if (tool == ToolbarTool.IMAGE_SEARCH) {
                    item {
                        SliderSetting(
                            R.string.tooldetail_image_columns_title,
                            subtitle = stringResource(R.string.tooldetail_image_columns_subtitle),
                            value = settings.emoji.mediaGridColumns.toFloat(),
                            range = 2f..5f,
                            display = { numberFormat.format(it.roundToInt()) },
                            info = stringResource(R.string.tooldetail_image_columns_info),
                            default = SettingsDefaults.emoji.mediaGridColumns.toFloat(),
                        ) { scope.launch { repository.setMediaGridColumns(it.roundToInt()) } }
                    }
                }
            }
        }
        ToolbarTool.OCR -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_ocr_select_all_title,
                        stringResource(R.string.tooldetail_ocr_select_all_subtitle),
                        settings.ocrAutoSelectWords,
                        default = SettingsDefaults.ocrAutoSelectWords,
                    ) { scope.launch { repository.setOcrAutoSelectWords(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_ocr_info))
        }
        ToolbarTool.QR_SCAN -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_auto_title,
                        stringResource(R.string.tooldetail_qr_scan_auto_subtitle),
                        settings.qrScanAutoInsert,
                        default = SettingsDefaults.qrScanAutoInsert,
                    ) { scope.launch { repository.setQrScanAutoInsert(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_haptics_title,
                        stringResource(R.string.tooldetail_qr_scan_haptics_subtitle),
                        settings.qrScanHaptics,
                        default = SettingsDefaults.qrScanHaptics,
                    ) { scope.launch { repository.setQrScanHaptics(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_scan_preview_title,
                        stringResource(R.string.tooldetail_qr_scan_preview_subtitle),
                        settings.qrScanLinkPreviews,
                        default = SettingsDefaults.qrScanLinkPreviews,
                    ) { scope.launch { repository.setQrScanLinkPreviews(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_qr_scan_info))
        }
        ToolbarTool.DOC_SCAN -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_doc_scan_gallery_title,
                        stringResource(R.string.tooldetail_doc_scan_gallery_subtitle),
                        settings.docScanSaveToGallery,
                        default = SettingsDefaults.docScanSaveToGallery,
                    ) { scope.launch { repository.setDocScanSaveToGallery(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_doc_scan_info))
        }
        ToolbarTool.VOICE -> SettingsGroup(stringResource(R.string.tooldetail_voice_group)) {
            item {
                NavRow(
                    R.string.tooldetail_voice_all_title,
                    stringResource(R.string.tooldetail_voice_all_subtitle),
                    onClick = { onNavigate("voice") },
                )
            }
        }
        ToolbarTool.GRAMMAR -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ChoiceSetting(
                        R.string.tooldetail_grammar_dialect_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_dialect_subtitle),
                        options = GrammarDialect.entries.map { it to stringResource(it.labelRes) },
                        selected = settings.grammarDialect,
                        default = SettingsDefaults.grammarDialect,
                    ) { scope.launch { repository.setGrammarDialect(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_grammar_debounce_title,
                        subtitle = stringResource(R.string.tooldetail_grammar_debounce_subtitle),
                        value = settings.grammarDebounceMs.toFloat(),
                        range = 100f..1500f,
                        display = { msFormat.format(it.toInt()) },
                        default = SettingsDefaults.grammarDebounceMs.toFloat(),
                    ) { scope.launch { repository.setGrammarDebounceMs(it.toInt()) } }
                }
            }
            if (BuildConfig.ENABLE_GRAMMAR) {
                val context = LocalContext.current
                SettingsGroup(stringResource(R.string.tooldetail_grammar_system_group)) {
                    item {
                        NavRow(
                            R.string.tooldetail_grammar_system_title,
                            stringResource(R.string.tooldetail_grammar_system_subtitle),
                            onClick = { openSpellCheckerSettings(context) },
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        item {
                            ToggleSetting(
                                stringResource(
                                    R.string.tooldetail_grammar_no_suggestions_title,
                                ),
                                stringResource(
                                    R.string.tooldetail_grammar_no_suggestions_subtitle,
                                ),
                                settings.spellCheckerNoSuggestions,
                                default = SettingsDefaults.spellCheckerNoSuggestions,
                            ) {
                                scope.launch {
                                    repository.setSpellCheckerNoSuggestions(it)
                                }
                            }
                        }
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_grammar_info))
        }
        ToolbarTool.WIKIPEDIA -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_wiki_language_label),
                        value = settings.wikiLanguage,
                        hint = stringResource(R.string.tooldetail_wiki_language_hint),
                        default = SettingsDefaults.wikiLanguage,
                    ) { repository.setWikiLanguage(it) }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_wiki_markdown_title,
                        stringResource(R.string.tooldetail_wiki_markdown_subtitle),
                        settings.wikiLinksMarkdown,
                        default = SettingsDefaults.wikiLinksMarkdown,
                    ) { scope.launch { repository.setWikiLinksMarkdown(it) } }
                }
                item {
                    val linksFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_wiki_link_limit_title,
                        subtitle = stringResource(R.string.tooldetail_wiki_link_limit_subtitle),
                        value = settings.toolLimits.wikiLinkLimit.toFloat(),
                        range = 50f..500f,
                        display = { linksFormat.format((it / 10f).roundToInt() * 10) },
                        info = stringResource(R.string.tooldetail_wiki_link_limit_info),
                        default = SettingsDefaults.toolLimits.wikiLinkLimit.toFloat(),
                    ) { picked ->
                        scope.launch { repository.setWikiLinkLimit((picked / 10f).roundToInt() * 10) }
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_wiki_info))
        }
        ToolbarTool.SYMBOLS -> {
            SettingsGroup(stringResource(R.string.tooldetail_symbols_recents_group)) {
                item {
                    val remembered = settings.symbolRecents.size
                    WmRow(
                        title = stringResource(R.string.tooldetail_symbols_clear_title),
                        subtitle = if (remembered == 0) {
                            stringResource(R.string.tooldetail_symbols_clear_empty)
                        } else {
                            pluralStringResource(
                                R.plurals.tooldetail_symbols_remembered_count,
                                remembered,
                                remembered,
                            )
                        },
                        onClick = { scope.launch { repository.clearSymbolRecents() } },
                    )
                }
            }
            CaptionText(stringResource(R.string.tooldetail_symbols_info))
        }
        ToolbarTool.CALCULATOR -> SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
            item {
                ToggleSetting(
                    R.string.tooldetail_calc_smart_title,
                    stringResource(R.string.tooldetail_calc_smart_subtitle),
                    settings.smartCalc,
                    info = stringResource(R.string.tooldetail_calc_smart_info),
                    default = SettingsDefaults.smartCalc,
                ) { scope.launch { repository.setSmartCalc(it) } }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_calc_degrees_title,
                    stringResource(R.string.tooldetail_calc_degrees_subtitle),
                    settings.calcDegrees,
                    default = SettingsDefaults.calcDegrees,
                ) { scope.launch { repository.setCalcDegrees(it) } }
            }
            item {
                SliderSetting(
                    R.string.tooldetail_calc_precision_title,
                    subtitle = stringResource(R.string.tooldetail_calc_precision_subtitle),
                    value = settings.calcPrecision.toFloat(),
                    range = 0f..12f,
                    display = { numberFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.calcPrecision.toFloat(),
                ) { scope.launch { repository.setCalcPrecision(it.roundToInt()) } }
            }
        }
        ToolbarTool.UNIT_CONVERT -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_units_smart_title,
                        stringResource(R.string.tooldetail_units_smart_subtitle),
                        settings.smartUnits,
                        info = stringResource(R.string.tooldetail_units_smart_info),
                        default = SettingsDefaults.smartUnits,
                    ) { scope.launch { repository.setSmartUnits(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_units_compound_title,
                        stringResource(R.string.tooldetail_units_compound_subtitle),
                        settings.compoundUnits,
                        info = stringResource(R.string.tooldetail_units_compound_info),
                        default = SettingsDefaults.compoundUnits,
                    ) { scope.launch { repository.setCompoundUnits(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_units_info))
        }
        ToolbarTool.CURRENCY -> {
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_currency_smart_title,
                        stringResource(
                            R.string.tooldetail_currency_smart_subtitle,
                            settings.currencyTo,
                        ),
                        settings.smartCurrency,
                        info = stringResource(R.string.tooldetail_currency_smart_info),
                        default = SettingsDefaults.smartCurrency,
                    ) { scope.launch { repository.setSmartCurrency(it) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_currency_decimals_title,
                        subtitle = stringResource(R.string.tooldetail_currency_decimals_subtitle),
                        value = settings.currencyDecimals.toFloat(),
                        range = 0f..6f,
                        display = { numberFormat.format(it.toInt()) },
                        default = SettingsDefaults.currencyDecimals.toFloat(),
                    ) { scope.launch { repository.setCurrencyDecimals(it.toInt()) } }
                }
                item {
                    SliderSetting(
                        R.string.tooldetail_currency_refresh_title,
                        subtitle = stringResource(R.string.tooldetail_currency_refresh_subtitle),
                        value = settings.currencyCacheHours.toFloat(),
                        range = 1f..48f,
                        display = { hoursFormat.format(it.toInt()) },
                        info = stringResource(R.string.tooldetail_currency_refresh_info),
                        default = SettingsDefaults.currencyCacheHours.toFloat(),
                    ) { scope.launch { repository.setCurrencyCacheHours(it.toInt()) } }
                }
                item {
                    RateSourceSetting(
                        title = R.string.tooldetail_currency_source_title,
                        subtitle = stringResource(R.string.tooldetail_currency_source_subtitle),
                        providers = settings.rateSources.fiatProviders,
                        defaultProviders = SettingsDefaults.rateSources.fiatProviders,
                        candidates = CurrencyClient.Provider.entries.filter { it.fiat },
                    ) { scope.launch { repository.setFiatProviders(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_currency_info))
            SettingsGroup(stringResource(R.string.tooldetail_crypto_group_title)) {
                item {
                    ToggleSetting(
                        R.string.tooldetail_crypto_enable_title,
                        stringResource(R.string.tooldetail_crypto_enable_subtitle),
                        settings.rateSources.cryptoEnabled,
                        info = stringResource(R.string.tooldetail_crypto_enable_info),
                        default = SettingsDefaults.rateSources.cryptoEnabled,
                    ) { scope.launch { repository.setCryptoEnabled(it) } }
                }
                if (settings.rateSources.cryptoEnabled) {
                    item {
                        val auto = stringResource(R.string.tooldetail_crypto_decimals_auto)
                        SliderSetting(
                            R.string.tooldetail_crypto_decimals_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_decimals_subtitle),
                            value = settings.rateSources.cryptoDecimals.toFloat(),
                            range = 0f..12f,
                            display = {
                                if (it.toInt() == 0) auto else numberFormat.format(it.toInt())
                            },
                            default = SettingsDefaults.rateSources.cryptoDecimals.toFloat(),
                        ) { scope.launch { repository.setCryptoDecimals(it.toInt()) } }
                    }
                    item {
                        SliderSetting(
                            R.string.tooldetail_crypto_refresh_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_refresh_subtitle),
                            value = settings.rateSources.cryptoCacheMinutes.toFloat(),
                            range = 1f..60f,
                            display = { minutesFormat.format(it.toInt()) },
                            default = SettingsDefaults.rateSources.cryptoCacheMinutes.toFloat(),
                        ) { scope.launch { repository.setCryptoCacheMinutes(it.toInt()) } }
                    }
                    item {
                        RateSourceSetting(
                            title = R.string.tooldetail_crypto_source_title,
                            subtitle = stringResource(R.string.tooldetail_crypto_source_subtitle),
                            providers = settings.rateSources.cryptoProviders,
                            defaultProviders = SettingsDefaults.rateSources.cryptoProviders,
                            candidates = CurrencyClient.Provider.entries.filter { it.crypto },
                        ) { scope.launch { repository.setCryptoProviders(it) } }
                    }
                    item { CryptoCoinPicker(repository, settings) }
                }
            }
            if (settings.rateSources.cryptoEnabled) {
                CaptionText(stringResource(R.string.tooldetail_crypto_info))
            }
        }
        ToolbarTool.QR_GEN -> {
            val qrImageOption = stringResource(R.string.tooldetail_media_send_image_option)
            val qrStickerOption = stringResource(R.string.tooldetail_media_send_sticker_option)
            SettingsGroup(stringResource(R.string.tooldetail_options_group)) {
                item {
                    SliderSetting(
                        R.string.tooldetail_qr_gen_size_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_size_subtitle),
                        value = settings.qrSizePx.toFloat(),
                        range = 256f..2048f,
                        display = { pixelsFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.qrSizePx.toFloat(),
                    ) { scope.launch { repository.setQrSizePx(it.roundToInt()) } }
                }
                item {
                    ChoiceSetting(
                        title = R.string.tooldetail_qr_gen_send_title,
                        subtitle = stringResource(R.string.tooldetail_qr_gen_send_subtitle),
                        info = stringResource(R.string.tooldetail_qr_gen_send_info),
                        options = listOf(
                            MediaSendMode.IMAGE to qrImageOption,
                            MediaSendMode.STICKER to qrStickerOption,
                        ),
                        selected = settings.qrSendMode,
                        default = SettingsDefaults.qrSendMode,
                    ) { scope.launch { repository.setQrSendMode(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_qr_gen_gallery_title,
                        stringResource(R.string.tooldetail_qr_gen_gallery_subtitle),
                        settings.qrSaveToGallery,
                        default = SettingsDefaults.qrSaveToGallery,
                    ) { scope.launch { repository.setQrSaveToGallery(it) } }
                }
            }
            SectionHeader(stringResource(R.string.tooldetail_qr_gen_ecc_header))
            ChoiceControl(
                // The names are the standard's own single letters (L/M/Q/H),
                // not words, so they are not translated.
                options = QrEccLevel.entries.map { it to it.name },
                selected = settings.qrEcc,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { level -> scope.launch { repository.setQrEcc(level) } }
            SettingsGroup {
                item {
                    val charsFormat = stringResource(R.string.values_number)
                    SliderSetting(
                        R.string.tooldetail_qr_max_chars_title,
                        subtitle = stringResource(R.string.tooldetail_qr_max_chars_subtitle),
                        value = settings.toolLimits.qrMaxChars.toFloat(),
                        range = 500f..4000f,
                        display = { charsFormat.format((it / 100f).roundToInt() * 100) },
                        info = stringResource(R.string.tooldetail_qr_max_chars_info),
                        default = SettingsDefaults.toolLimits.qrMaxChars.toFloat(),
                    ) { picked ->
                        scope.launch {
                            repository.setQrMaxChars((picked / 100f).roundToInt() * 100)
                        }
                    }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_qr_gen_ecc_info))
        }
        ToolbarTool.PASSWORD_GEN -> {
            SettingsGroup(stringResource(R.string.tooldetail_password_group)) {
                item {
                    SliderSetting(
                        R.string.tooldetail_password_length_title,
                        value = settings.passwordGenerator.pwLength.toFloat(),
                        range = 4f..64f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.pwLength.toFloat(),
                    ) { scope.launch { repository.setPwLength(it.roundToInt()) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_uppercase_title,
                        stringResource(R.string.tooldetail_password_uppercase_subtitle),
                        settings.passwordGenerator.pwUppercase,
                        default = SettingsDefaults.passwordGenerator.pwUppercase,
                    ) { scope.launch { repository.setPwUppercase(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_digits_title,
                        stringResource(R.string.tooldetail_password_digits_subtitle),
                        settings.passwordGenerator.pwDigits,
                        default = SettingsDefaults.passwordGenerator.pwDigits,
                    ) { scope.launch { repository.setPwDigits(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_symbols_title,
                        stringResource(R.string.tooldetail_password_symbols_subtitle),
                        settings.passwordGenerator.pwSymbols,
                        default = SettingsDefaults.passwordGenerator.pwSymbols,
                    ) { scope.launch { repository.setPwSymbols(it) } }
                }
                if (settings.passwordGenerator.pwSymbols) {
                    item {
                        TextFieldSetting(
                            label = stringResource(R.string.tooldetail_password_pool_label),
                            value = settings.toolLimits.passwordSymbols,
                            hint = stringResource(R.string.tooldetail_password_pool_hint),
                            default = SettingsDefaults.toolLimits.passwordSymbols,
                        ) { repository.setPasswordSymbols(it) }
                    }
                    item { CaptionText(stringResource(R.string.tooldetail_password_pool_info)) }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_password_ambiguous_title,
                        stringResource(R.string.tooldetail_password_ambiguous_subtitle),
                        settings.passwordGenerator.pwExcludeAmbiguous,
                        default = SettingsDefaults.passwordGenerator.pwExcludeAmbiguous,
                    ) { scope.launch { repository.setPwExcludeAmbiguous(it) } }
                }
            }
            SettingsGroup(stringResource(R.string.tooldetail_passphrase_group)) {
                item {
                    SliderSetting(
                        R.string.tooldetail_passphrase_words_title,
                        value = settings.passwordGenerator.ppWordCount.toFloat(),
                        range = 2f..10f,
                        display = { numberFormat.format(it.roundToInt()) },
                        default = SettingsDefaults.passwordGenerator.ppWordCount.toFloat(),
                    ) { scope.launch { repository.setPpWordCount(it.roundToInt()) } }
                }
                item {
                    TextFieldSetting(
                        label = stringResource(R.string.tooldetail_passphrase_separator_label),
                        value = settings.passwordGenerator.ppSeparator,
                        hint = stringResource(R.string.tooldetail_passphrase_separator_hint),
                        default = SettingsDefaults.passwordGenerator.ppSeparator,
                    ) { repository.setPpSeparator(it) }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_capitalize_title,
                        stringResource(R.string.tooldetail_passphrase_capitalize_subtitle),
                        settings.passwordGenerator.ppCapitalize,
                        default = SettingsDefaults.passwordGenerator.ppCapitalize,
                    ) { scope.launch { repository.setPpCapitalize(it) } }
                }
                item {
                    ToggleSetting(
                        R.string.tooldetail_passphrase_digit_title,
                        stringResource(R.string.tooldetail_passphrase_digit_subtitle),
                        settings.passwordGenerator.ppIncludeDigit,
                        default = SettingsDefaults.passwordGenerator.ppIncludeDigit,
                    ) { scope.launch { repository.setPpIncludeDigit(it) } }
                }
            }
            CaptionText(stringResource(R.string.tooldetail_password_info))
        }
        ToolbarTool.TYPING_TEST -> TypingTestToolSettings(repository, settings)
        ToolbarTool.AI -> AiToolSettings(repository, settings, onNavigate)
        ToolbarTool.MODES -> SettingsGroup(stringResource(R.string.tooldetail_modes_group)) {
            item {
                NavRow(
                    R.string.tooldetail_modes_edit_title,
                    stringResource(R.string.tooldetail_modes_edit_subtitle),
                    value = "${settings.keyboardModes.size}",
                ) { onNavigate("modes") }
            }
        }
        else -> {}
    }
}

/** The display name of a rate provider. Ids are stored, names are shown. */
@Composable
private fun providerLabel(provider: CurrencyClient.Provider): String = stringResource(
    when (provider) {
        CurrencyClient.Provider.ER_API -> R.string.tooldetail_rate_source_er_api
        CurrencyClient.Provider.FRANKFURTER -> R.string.tooldetail_rate_source_frankfurter
        CurrencyClient.Provider.COINBASE -> R.string.tooldetail_rate_source_coinbase
        CurrencyClient.Provider.CURRENCY_API -> R.string.tooldetail_rate_source_currency_api
        CurrencyClient.Provider.COINGECKO -> R.string.tooldetail_rate_source_coingecko
    },
)

/**
 * Which source to fetch from, and whether the rest stand behind it. Both
 * answers are one stored list: the head is the source that is tried and the
 * tail is the fallback chain, so switching fallbacks off simply drops the
 * tail.
 */
@Composable
private fun RateSourceSetting(
    @StringRes title: Int,
    subtitle: String,
    providers: List<String>,
    defaultProviders: List<String>,
    candidates: List<CurrencyClient.Provider>,
    onChange: (List<String>) -> Unit,
) {
    val primary = providers.firstNotNullOfOrNull { CurrencyClient.Provider.of(it) }
        ?: candidates.first()
    val fallback = providers.size > 1
    // Both rows are views onto the one stored list, so both read their default
    // off the list the app shipped with rather than off a constant of their own.
    val defaultPrimary = defaultProviders.firstNotNullOfOrNull { CurrencyClient.Provider.of(it) }
        ?: candidates.first()
    fun write(head: CurrencyClient.Provider, withFallback: Boolean) {
        val rest = if (withFallback) candidates.filter { it != head }.map { it.name } else emptyList()
        onChange(listOf(head.name) + rest)
    }
    ChoiceSetting(
        title = title,
        subtitle = subtitle,
        options = candidates.map { it to providerLabel(it) },
        selected = primary,
        default = defaultPrimary,
    ) { write(it, fallback) }
    ToggleSetting(
        R.string.tooldetail_rate_fallback_title,
        stringResource(R.string.tooldetail_rate_fallback_subtitle),
        fallback,
        default = defaultProviders.size > 1,
    ) { write(primary, it) }
}

/**
 * The coins the keyboard reads and offers. An empty stored set means the
 * catalogue's defaults, so the last coin cannot be switched off — turning
 * it off would silently bring all of them back.
 */
@Composable
private fun CryptoCoinPicker(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val enabled = remember(settings.rateSources.cryptoTickers) {
        CryptoCatalog.enabled(settings.rateSources.cryptoTickers)
    }
    val extra = remember(enabled) { enabled.filterNot { CryptoCatalog.isKnown(it) }.sorted() }
    var showAdd by remember { mutableStateOf(false) }
    fun save(next: Set<String>) {
        if (next.isNotEmpty()) scope.launch { repository.setCryptoTickers(next) }
    }

    WmRow(
        title = stringResource(R.string.tooldetail_crypto_coins_title),
        subtitle = stringResource(R.string.tooldetail_crypto_coins_subtitle, enabled.size),
        icon = SettingsRowIcons[R.string.tooldetail_crypto_coins_title],
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (coin in CryptoCatalog.coins) {
            FilterChip(
                selected = coin.code in enabled,
                onClick = {
                    save(if (coin.code in enabled) enabled - coin.code else enabled + coin.code)
                },
                label = { Text(coin.code, maxLines = 1) },
            )
        }
        for (ticker in extra) {
            FilterChip(
                selected = true,
                onClick = { save(enabled - ticker) },
                label = { Text(ticker, maxLines = 1) },
            )
        }
    }
    Button(
        onClick = { showAdd = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.tooldetail_crypto_add_action)) }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tooldetail_crypto_add_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tooldetail_crypto_add_hint)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tooldetail_crypto_add_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        save(enabled + input.trim().uppercase().filter { it.isLetterOrDigit() })
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
 * The typing test's settings. These are the same values the panel's own
 * chip row edits — this screen is the slower way round to them, plus the
 * records the panel only shows one config of at a time.
 */
@Composable
private fun TypingTestToolSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val secondsFormat = stringResource(R.string.values_seconds)
    val numberFormat = stringResource(R.string.values_number)
    val bests = remember(settings.typingTestBests) { TypingBests.decode(settings.typingTestBests) }
    val history = remember(settings.typingTestHistory) {
        TypingHistory.decode(settings.typingTestHistory)
    }

    SectionHeader(stringResource(R.string.toolai_typing_default_test_title))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (mode in TypingTestMode.entries) {
            FilterChip(
                selected = settings.typingTestMode == mode,
                onClick = { scope.launch { repository.setTypingTestMode(mode) } },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                TypingTestMode.TIME -> R.string.toolai_typing_mode_time_label
                                TypingTestMode.WORDS -> R.string.toolai_typing_mode_words_label
                                TypingTestMode.QUOTE -> R.string.toolai_typing_mode_quote_label
                            },
                        ),
                    )
                },
            )
        }
    }

    SettingsGroup(stringResource(R.string.toolai_typing_length_title)) {
        when (settings.typingTestMode) {
            TypingTestMode.TIME -> item {
                SliderSetting(
                    R.string.toolai_typing_seconds_label,
                    value = settings.typingTestDuration.toFloat(),
                    range = 15f..120f,
                    display = { secondsFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTestDuration.toFloat(),
                ) { scope.launch { repository.setTypingTestDuration(it.roundToInt()) } }
            }
            TypingTestMode.WORDS -> item {
                SliderSetting(
                    R.string.toolai_typing_words_label,
                    value = settings.typingTestWordCount.toFloat(),
                    range = 10f..100f,
                    display = { numberFormat.format(it.roundToInt()) },
                    default = SettingsDefaults.typingTestWordCount.toFloat(),
                ) { scope.launch { repository.setTypingTestWordCount(it.roundToInt()) } }
            }
            // Quotes come at whatever length they were written.
            TypingTestMode.QUOTE -> item {
                CaptionText(stringResource(R.string.toolai_typing_quote_info))
            }
        }
    }

    if (settings.typingTestMode != TypingTestMode.QUOTE) {
        SettingsGroup(stringResource(R.string.toolai_typing_difficulty_title)) {
            item {
                ToggleSetting(
                    R.string.toolai_typing_punctuation_title,
                    stringResource(R.string.toolai_typing_punctuation_subtitle),
                    settings.typingTestPunctuation,
                    default = SettingsDefaults.typingTestPunctuation,
                ) { scope.launch { repository.setTypingTestPunctuation(it) } }
            }
            item {
                ToggleSetting(
                    R.string.toolai_typing_numbers_title,
                    stringResource(R.string.toolai_typing_numbers_subtitle),
                    settings.typingTestNumbers,
                    default = SettingsDefaults.typingTestNumbers,
                ) { scope.launch { repository.setTypingTestNumbers(it) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_typing_records_title)) {
        item {
            WmRow(
                title = stringResource(R.string.toolai_typing_tests_completed_title),
                trailing = { Text("${settings.typingTestsCompleted}") },
            )
        }
        if (history.isNotEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.toolai_typing_recent_average_title),
                    subtitle = pluralStringResource(
                        R.plurals.toolai_typing_recent_average_subtitle,
                        history.size,
                        history.size,
                    ),
                    trailing = {
                        Text(
                            stringResource(
                                R.string.toolai_typing_wpm_value,
                                history.average().roundToInt(),
                            ),
                        )
                    },
                )
            }
        }
        // One row per config the user has actually run, best first.
        for ((key, wpm) in bests.entries.sortedByDescending { it.value }) {
            item {
                WmRow(
                    title = typingBestLabel(key),
                    trailing = {
                        Text(stringResource(R.string.toolai_typing_wpm_value, wpm.roundToInt()))
                    },
                )
            }
        }
        if (bests.isNotEmpty() || settings.typingTestsCompleted > 0) {
            item {
                NavRow(
                    R.string.toolai_typing_clear_records_title,
                    stringResource(R.string.toolai_typing_clear_records_subtitle),
                ) {
                    scope.launch { repository.clearTypingStats() }
                }
            }
        }
    }

    // The full badge list, locked ones greyed — the keyboard's results screen
    // only shows what is already earned; this is where the goals are visible.
    val unlockedBadges = remember(settings.typingTestAchievements) {
        TypingAchievements.decode(settings.typingTestAchievements)
    }
    SettingsGroup(stringResource(R.string.toolai_typing_achievements_title)) {
        for (id in TypingAchievements.ALL) {
            item {
                val unlocked = id in unlockedBadges
                val (title, subtitle) = when (id) {
                    TypingAchievements.WPM_100 ->
                        R.string.toolai_typing_achievement_wpm100_title to
                            R.string.toolai_typing_achievement_wpm100_subtitle
                    TypingAchievements.PERFECT ->
                        R.string.toolai_typing_achievement_perfect_title to
                            R.string.toolai_typing_achievement_perfect_subtitle
                    TypingAchievements.PANGRAM ->
                        R.string.toolai_typing_achievement_pangram_title to
                            R.string.toolai_typing_achievement_pangram_subtitle
                    else ->
                        R.string.toolai_typing_achievement_tests50_title to
                            R.string.toolai_typing_achievement_tests50_subtitle
                }
                WmRow(
                    title = stringResource(title),
                    subtitle = stringResource(subtitle),
                    trailing = {
                        if (unlocked) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = stringResource(
                                    R.string.toolai_typing_achievement_unlocked_desc,
                                ),
                                tint = ActiveGreen,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = stringResource(
                                    R.string.toolai_typing_achievement_locked_desc,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    },
                )
            }
        }
    }

    CaptionText(stringResource(R.string.toolai_typing_info))
}

/** Turns a stored best's key ("time30", "quote") back into a heading. */
@Composable
private fun typingBestLabel(key: String): String = when {
    key == "quote" -> stringResource(R.string.toolai_typing_mode_quote_label)
    key.startsWith("time") ->
        stringResource(R.string.toolai_typing_best_seconds_label, key.removePrefix("time"))
    key.startsWith("words") ->
        stringResource(R.string.toolai_typing_best_words_label, key.removePrefix("words"))
    else -> key
}

/** The AI tool's settings: provider, credentials, output and prompts. */
@Composable
private fun AiToolSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readout is a plain lambda, so its format string is resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    SectionHeader(stringResource(R.string.toolai_ai_provider_title))
    // Nine providers no longer fit a segmented row; chips wrap instead. The
    // order is displayOrder, not the enum's own: new entries can only be
    // appended there, which would put them after "On your device".
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val providers = AiProvider.displayOrder.filter {
            it != AiProvider.ON_DEVICE || BuildConfig.ENABLE_LOCAL_LLM
        }
        for (provider in providers) {
            FilterChip(
                selected = settings.ai.provider == provider,
                onClick = { scope.launch { repository.setAiProvider(provider) } },
                label = { Text(stringResource(provider.labelRes), maxLines = 1) },
            )
        }
    }
    when (settings.ai.provider) {
        AiProvider.ANTHROPIC -> SettingsGroup(
            stringResource(R.string.toolai_ai_anthropic_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_anthropic_key_label),
                    value = settings.ai.anthropicKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_anthropic_key_hint),
                ) { repository.setAiAnthropicKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.anthropicModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.ANTHROPIC,
                    ),
                    default = SettingsDefaults.ai.anthropicModel,
                ) { repository.setAiAnthropicModel(it) }
            }
        }
        AiProvider.OPENAI -> SettingsGroup(
            stringResource(R.string.toolai_ai_openai_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_openai_key_label),
                    value = settings.ai.openAiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_openai_key_hint),
                ) { repository.setAiOpenAiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.openAiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.OPENAI,
                    ),
                    default = SettingsDefaults.ai.openAiModel,
                ) { repository.setAiOpenAiModel(it) }
            }
        }
        AiProvider.GEMINI -> SettingsGroup(
            stringResource(R.string.toolai_ai_gemini_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_gemini_key_label),
                    value = settings.ai.geminiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_gemini_key_hint),
                ) { repository.setAiGeminiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.geminiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.GEMINI,
                    ),
                    default = SettingsDefaults.ai.geminiModel,
                ) { repository.setAiGeminiModel(it) }
            }
        }
        AiProvider.OLLAMA -> SettingsGroup(
            stringResource(R.string.toolai_ai_ollama_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_server_address_label),
                    value = settings.ai.ollamaUrl,
                    hint = stringResource(R.string.toolai_ai_ollama_url_hint),
                    default = SettingsDefaults.ai.ollamaUrl,
                ) { repository.setAiOllamaUrl(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.ollamaModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.OLLAMA,
                    ),
                    default = SettingsDefaults.ai.ollamaModel,
                ) { repository.setAiOllamaModel(it) }
            }
        }
        AiProvider.LM_STUDIO -> SettingsGroup(
            stringResource(R.string.toolai_ai_lm_studio_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_server_address_label),
                    value = settings.ai.lmStudioUrl,
                    hint = stringResource(R.string.toolai_ai_lm_studio_url_hint),
                    default = SettingsDefaults.ai.lmStudioUrl,
                ) { repository.setAiLmStudioUrl(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.lmStudioModel,
                    hint = stringResource(R.string.toolai_ai_lm_studio_model_hint),
                    default = SettingsDefaults.ai.lmStudioModel,
                ) { repository.setAiLmStudioModel(it) }
            }
        }
        AiProvider.XAI -> SettingsGroup(
            stringResource(R.string.toolai_ai_xai_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_xai_key_label),
                    value = settings.ai.xaiKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_xai_key_hint),
                ) { repository.setAiXaiKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.xaiModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.XAI,
                    ),
                    default = SettingsDefaults.ai.xaiModel,
                ) { repository.setAiXaiModel(it) }
            }
        }
        AiProvider.DEEPSEEK -> SettingsGroup(
            stringResource(R.string.toolai_ai_deepseek_group_title),
        ) {
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_deepseek_key_label),
                    value = settings.ai.deepSeekKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_deepseek_key_hint),
                ) { repository.setAiDeepSeekKey(it) }
            }
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.deepSeekModel,
                    hint = stringResource(
                        R.string.toolai_ai_model_hint,
                        AiClient.DefaultModels.DEEPSEEK,
                    ),
                    default = SettingsDefaults.ai.deepSeekModel,
                ) { repository.setAiDeepSeekModel(it) }
            }
        }
        AiProvider.OPENAI_COMPATIBLE -> SettingsGroup(
            stringResource(R.string.toolai_ai_compatible_group_title),
        ) {
            item {
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_compatible_url_label),
                    value = settings.ai.compatibleUrl,
                    hint = stringResource(R.string.toolai_ai_compatible_url_hint),
                    default = SettingsDefaults.ai.compatibleUrl,
                ) { repository.setAiCompatibleUrl(it) }
            }
            item {
                // No default model: there is nothing sensible to guess for a
                // service the app knows nothing about.
                TextFieldSetting(
                    label = stringResource(R.string.toolai_ai_model_label),
                    value = settings.ai.compatibleModel,
                    hint = stringResource(R.string.toolai_ai_compatible_model_hint),
                    default = SettingsDefaults.ai.compatibleModel,
                ) { repository.setAiCompatibleModel(it) }
            }
            item {
                ApiKeyField(
                    label = stringResource(R.string.toolai_ai_compatible_key_label),
                    value = settings.ai.compatibleKey,
                    builtInAvailable = false,
                    emptyHint = stringResource(R.string.toolai_ai_compatible_key_hint),
                ) { repository.setAiCompatibleKey(it) }
            }
        }
        AiProvider.ON_DEVICE -> LocalLlmModelManager(repository, settings)
    }
    if (settings.ai.provider == AiProvider.OLLAMA || settings.ai.provider == AiProvider.LM_STUDIO) {
        CaptionText(stringResource(R.string.toolai_ai_local_server_info))
    }
    if (settings.ai.provider == AiProvider.OPENAI_COMPATIBLE) {
        CaptionText(stringResource(R.string.toolai_ai_compatible_info))
    }
    SettingsGroup(stringResource(R.string.toolai_ai_output_title)) {
        if (settings.ai.provider != AiProvider.ON_DEVICE) {
            item {
                TokenPresetSetting(
                    title = stringResource(R.string.toolai_ai_max_tokens_title),
                    subtitle = stringResource(R.string.toolai_ai_max_tokens_subtitle),
                    value = settings.ai.maxTokens,
                    presets = MaxTokenPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_max_tokens_provider_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiMaxTokens(it) } }
            }
        } else {
            item {
                TokenPresetSetting(
                    title = stringResource(R.string.toolai_ai_local_context_title),
                    subtitle = stringResource(R.string.toolai_ai_local_context_subtitle),
                    value = settings.ai.localContextTokens,
                    presets = LocalContextPresets,
                    unlimitedLabel = stringResource(R.string.toolai_ai_local_context_model_label),
                    numberFormat = numberFormat,
                ) { scope.launch { repository.setAiLocalContextTokens(it) } }
            }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.toolai_ai_translate_to_label),
                value = settings.ai.translateTo,
                hint = stringResource(R.string.toolai_ai_translate_to_hint),
                default = SettingsDefaults.ai.translateTo,
            ) { repository.setAiTranslateTo(it) }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_show_thinking_title,
                stringResource(R.string.toolai_ai_show_thinking_subtitle),
                settings.ai.showThinking,
                default = SettingsDefaults.ai.showThinking,
            ) { scope.launch { repository.setAiShowThinking(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_model_picker_title,
                stringResource(R.string.toolai_ai_model_picker_subtitle),
                settings.ai.panelModelPicker,
                default = SettingsDefaults.ai.panelModelPicker,
            ) { scope.launch { repository.setAiPanelModelPicker(it) } }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_diff_title,
                stringResource(R.string.toolai_ai_diff_subtitle),
                settings.ai.diffView,
                default = SettingsDefaults.ai.diffView,
            ) { scope.launch { repository.setAiDiffView(it) } }
        }
        if (settings.ai.diffView) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_diff_first_title,
                    stringResource(R.string.toolai_ai_diff_first_subtitle),
                    settings.ai.diffOpensFirst,
                    default = SettingsDefaults.ai.diffOpensFirst,
                ) { scope.launch { repository.setAiDiffOpensFirst(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_chat_group_title)) {
        item {
            NavRow(
                title = R.string.toolai_ai_chat_nav_title,
                subtitle = stringResource(R.string.toolai_ai_chat_nav_subtitle),
                route = "ai_chat",
                onClick = { onNavigate("ai_chat") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_actions_group_title)) {
        item {
            val visible = visibleAiActions(
                settings.ai.customActions,
                settings.ai.actionOrder,
                settings.ai.hiddenActions,
            )
            NavRow(
                title = R.string.toolai_ai_actions_title,
                subtitle = stringResource(R.string.toolai_ai_actions_subtitle),
                value = numberFormat.format(visible.size),
                onClick = { onNavigate("ai_actions") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_history_group_title)) {
        item {
            // Always reachable, turned on or not, so "Delete all history" does
            // not disappear along with the switch that filled it.
            NavRow(
                title = R.string.toolai_ai_history_nav_title,
                subtitle = stringResource(R.string.toolai_ai_history_nav_subtitle),
                onClick = { onNavigate("ai_history") },
            )
        }
    }
    CaptionText(
        stringResource(
            if (settings.ai.provider == AiProvider.ON_DEVICE) {
                R.string.toolai_ai_on_device_info
            } else {
                R.string.toolai_ai_cloud_info
            },
        ),
    )
}

/**
 * One end of a tool's icon colour: a swatch, what it currently is, and the
 * picker behind it. [onPick] takes null when the user resets, which puts the
 * colour back to [default].
 */
@Composable
private fun ToolColourRow(
    title: String,
    toolName: String,
    override: Long?,
    default: Long,
    onPick: (Long?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val resolved = override ?: default
    WmRow(
        title = title,
        subtitle = if (override != null) {
            stringResource(R.string.tooldetail_icon_colour_custom_subtitle)
        } else {
            stringResource(R.string.tooldetail_icon_colour_default_subtitle)
        },
        leading = { Swatch(resolved) },
        onClick = { showPicker = true },
    )
    if (showPicker) {
        ColorPickerDialog(
            title = stringResource(R.string.tooldetail_icon_colour_dialog_title, toolName),
            initial = resolved,
            supportsAlpha = false,
            showReset = override != null,
            onPick = {
                onPick(it)
                showPicker = false
            },
            onReset = {
                onPick(null)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * The words that make this tool offer itself on the suggestion strip.
 * Only tools that ship a default get the row — a keyword for "Undo" would
 * fire on prose and there is nothing to open anyway.
 */
@Composable
private fun ToolKeywordSetting(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    tool: ToolbarTool,
) {
    val defaults = SmartSuggest.defaultKeywords[tool] ?: return
    val scope = rememberCoroutineScope()
    val saved = SmartSuggest.keywordsFor(tool, settings.toolKeywords)
    val caseSensitive = SmartSuggest.caseSensitiveKeyword(tool, settings.toolKeywordCase)
    var text by remember(tool) { mutableStateOf(saved.joinToString(", ")) }
    SettingsGroup(stringResource(R.string.toolai_keyword_group_title)) {
        item {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    scope.launch { repository.setToolKeywords(tool, it.split(',')) }
                },
                label = { Text(stringResource(R.string.toolai_keyword_field_label)) },
                singleLine = true,
                supportingText = {
                    Text(
                        if (saved.isEmpty()) {
                            stringResource(R.string.toolai_keyword_empty_hint)
                        } else {
                            stringResource(
                                R.string.toolai_keyword_hint,
                                stringResource(toolTitle(tool)),
                            )
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            ToggleSetting(
                R.string.toolai_keyword_case_title,
                stringResource(
                    if (caseSensitive) R.string.toolai_keyword_case_on_subtitle
                    else R.string.toolai_keyword_case_off_subtitle,
                ),
                caseSensitive,
                info = stringResource(R.string.toolai_keyword_case_info),
                default = SmartSuggest.caseSensitiveKeyword(
                    tool,
                    SettingsDefaults.toolKeywordCase,
                ),
            ) { scope.launch { repository.setToolKeywordCaseSensitive(tool, it) } }
        }
        if (saved != defaults) {
            item {
                WmRow(
                    title = stringResource(CommonR.string.common_reset_defaults),
                    subtitle = defaults.joinToString(", "),
                    onClick = {
                        text = defaults.joinToString(", ")
                        scope.launch { repository.setToolKeywords(tool, defaults) }
                    },
                )
            }
        }
    }
    if (!settings.smartSuggestions || !settings.smartToolKeywords) {
        CaptionText(stringResource(R.string.toolai_keyword_off_info))
    }
}

/**
 * Response-length steps for a cloud or self-hosted service. They rise by
 * doubling rather than in even steps, because what the user is choosing between
 * is "a paragraph" and "a whole document", not 4,000 versus 5,000 tokens.
 */
private val MaxTokenPresets =
    listOf(1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072)

/**
 * Context-window steps for an on-device model. Far smaller: this is the window
 * the model is loaded with, and a phone pays for every token of it in memory.
 */
private val LocalContextPresets = listOf(1024, 2048, 4096, 8192, 16_384)

/**
 * A token count picked from steps rather than dragged on a slider. `0` selects
 * [unlimitedLabel], which sends no number at all.
 *
 * A slider cannot do this job any more: the range now spans 1,024 to 131,072,
 * and no thumb lands on a useful value across that span. The steps also make
 * "no limit" selectable, which a numeric slider has nowhere to put.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenPresetSetting(
    title: String,
    subtitle: String,
    value: Int,
    presets: List<Int>,
    unlimitedLabel: String,
    numberFormat: String,
    onPick: (Int) -> Unit,
) {
    HighlightableRow(title) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                for (preset in presets) {
                    FilterChip(
                        selected = value == preset,
                        onClick = { onPick(preset) },
                        label = { Text(numberFormat.format(preset), maxLines = 1) },
                    )
                }
                FilterChip(
                    selected = value <= 0,
                    onClick = { onPick(0) },
                    label = { Text(unlimitedLabel, maxLines = 1) },
                )
            }
        }
    }
}

/** A plain saved-as-you-type text setting (same mechanics as ApiKeyField). */
@Composable
internal fun TextFieldSetting(
    label: String,
    value: String,
    hint: String,
    default: String? = null,
    onSave: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(label) { mutableStateOf(value) }
    HighlightableRow(label) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                scope.launch { onSave(it) }
            },
            label = { Text(label) },
            singleLine = true,
            supportingText = { Text(hint) },
            trailingIcon = {
                ResetSetting(label, default != null && text != default) {
                    text = default.orEmpty()
                    scope.launch { onSave(default.orEmpty()) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * One API-key input. Saves as you type (it's a paste, in practice). The
 * user's key always beats any key baked into the build via
 * local.properties — leaving the field blank falls back to the built-in
 * key when the build has one.
 */
@Composable
internal fun ApiKeyField(
    label: String,
    value: String,
    builtInAvailable: Boolean,
    emptyHint: String,
    onSave: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember(label) { mutableStateOf(value) }
    HighlightableRow(label) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                scope.launch { onSave(it) }
            },
            label = { Text(label) },
            singleLine = true,
            supportingText = {
                Text(
                    when {
                        text.isNotBlank() -> stringResource(R.string.toolai_api_key_yours_hint)
                        builtInAvailable -> stringResource(R.string.toolai_api_key_builtin_hint)
                        else -> emptyHint
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * One of the calendar tool's two alternate-calendar slots. A dialog rather
 * than a segmented row: nine choices never fit side by side, and the tool's
 * settings and the onboarding page both ask the same question.
 */
@Composable
internal fun AltCalendarSetting(
    title: String,
    subtitle: String,
    selected: AltCalendar,
    icon: ImageVector? = null,
    onChange: (AltCalendar) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    // The label of every calendar but NONE reads "English name · own name";
    // the row has room for the first half only. NONE's label is already short.
    NavRow(
        title,
        subtitle = subtitle,
        value = stringResource(selected.labelRes).substringBefore(" ·"),
        icon = icon,
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(AltCalendar.entries) { calendar ->
                        ListItem(
                            headlineContent = { Text(stringResource(calendar.labelRes)) },
                            trailingContent = if (calendar == selected) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                onChange(calendar)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}

/**
 * The weekend picker, as a row plus dialog so it works both on the tool's
 * settings screen and in the onboarding wizard, which has no [SettingsGroup].
 */
@Composable
internal fun WeekendSetting(selected: Weekend, onChange: (Weekend) -> Unit) {
    var dialogOpen by remember { mutableStateOf(false) }
    NavRow(
        R.string.toolai_weekend_title,
        subtitle = stringResource(R.string.toolai_weekend_subtitle),
        value = stringResource(selected.labelRes),
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.toolai_weekend_title)) },
            text = {
                LazyColumn {
                    items(Weekend.entries) { weekend ->
                        ListItem(
                            headlineContent = { Text(stringResource(weekend.labelRes)) },
                            trailingContent = if (weekend == selected) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                onChange(weekend)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}

/** "Translate into" row with a full-language-list dialog. */
@Composable
private fun TranslateLanguageSetting(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    NavRow(
        R.string.toolai_translate_into_title,
        subtitle = stringResource(R.string.toolai_translate_into_subtitle),
        value = TranslateClient.languageName(settings.translateTargetLang),
        onClick = { dialogOpen = true },
    )
    if (dialogOpen) {
        val selectedDesc = stringResource(R.string.toolai_selected_desc)
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.toolai_translate_into_title)) },
            text = {
                LazyColumn {
                    items(TranslateClient.languages) { (code, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            trailingContent = if (code == settings.translateTargetLang) {
                                { Icon(Icons.Outlined.Check, contentDescription = selectedDesc) }
                            } else null,
                            modifier = Modifier.clickable {
                                dialogOpen = false
                                scope.launch { repository.setTranslateTargetLang(code) }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(CommonR.string.common_close))
                }
            },
        )
    }
}

/**
 * Open Android's Spell checker settings screen — where WM Keyboard's Harper
 * service can be picked as the system checker.
 *
 * There is no public [Settings] action for this screen, so we aim the direct
 * AOSP Settings component first and only fall back to the input-method
 * settings page (its parent) when that component is missing or hidden, as it
 * is on some OEM builds. Resolving before launching keeps a stock ROM that
 * renamed the activity from throwing an [android.content.ActivityNotFoundException].
 */
private fun openSpellCheckerSettings(context: Context) {
    val direct = Intent(Intent.ACTION_MAIN).setComponent(
        ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$SpellCheckersSettingsActivity",
        )
    )
    val resolves = context.packageManager.resolveActivity(direct, 0) != null
    val launched = resolves && runCatching { context.startActivity(direct) }.isSuccess
    if (!launched) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}

/**
 * Opens Android's subtype enabler for this keyboard — the screen where the user
 * ticks which of our registered languages the system switcher may list. Needed
 * on Android 13 and older, where an IME cannot enable its own subtypes and the
 * framework otherwise picks one from the phone's language list.
 *
 * The extra is what scopes the screen to us; without it (or on an OEM build
 * that dropped the activity) we fall back to the input-method settings page,
 * which is one tap away from the same place.
 */
private fun openSubtypeEnabler(context: Context) {
    val imeId = ComponentName(context, WMKeyboardService::class.java).flattenToShortString()
    val direct = Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
        .putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId)
    if (runCatching { context.startActivity(direct) }.isFailure) {
        runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    }
}

/**
 * Download/delete state for the handwriting model of every language the user
 * types in — drawn from ML Kit's full ink catalogue, then narrowed to the
 * enabled languages so the list is only ever as long as it is useful. Status
 * is re-read from ML Kit's model manager after every action.
 */
@Composable
private fun HandwritingModelManager(settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val languages = remember(settings.enabledLanguages) {
        HandwritingModels.modelsFor(settings.enabledLanguages)
    }
    val missing = remember(settings.enabledLanguages, languages) {
        settings.enabledLanguages.distinctBy { it.id }
            .filter { HandwritingModels.tagFor(it) == null }
    }
    // tag -> "checking" | "missing" | "downloaded" | "downloading" | "error"
    val statuses = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(languages) {
        for (language in languages) {
            statuses[language.tag] =
                if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
        }
    }
    if (languages.isEmpty()) {
        CaptionText(stringResource(R.string.privacy_handwriting_none_info))
        return
    }
    SettingsGroup {
        for (language in languages) {
            item {
                val status = statuses[language.tag] ?: "checking"
                WmRow(
                    title = language.displayName,
                    subtitle = when (status) {
                            "checking" -> stringResource(R.string.privacy_handwriting_status_checking)
                            "downloaded" -> stringResource(R.string.privacy_handwriting_status_downloaded)
                            "downloading" -> stringResource(CommonR.string.common_downloading)
                            "error" -> stringResource(R.string.privacy_handwriting_status_failed)
                            else -> stringResource(R.string.privacy_handwriting_status_missing)
                        },
                    trailing = {
                        when (status) {
                            "downloading", "checking" -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            "downloaded" -> IconButton(onClick = {
                                scope.launch {
                                    HandwritingModels.delete(language.tag)
                                    statuses[language.tag] =
                                        if (HandwritingModels.isDownloaded(language.tag)) "downloaded" else "missing"
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.privacy_handwriting_delete_desc,
                                        language.displayName,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> TextButton(onClick = {
                                statuses[language.tag] = "downloading"
                                scope.launch {
                                    val ok = runCancellable { HandwritingModels.download(language.tag) }.isSuccess
                                    statuses[language.tag] = if (ok) "downloaded" else "error"
                                }
                            }) { Text(stringResource(CommonR.string.common_download)) }
                        }
                    },
                )
            }
        }
    }
    if (missing.isNotEmpty()) {
        CaptionText(
            stringResource(
                R.string.privacy_handwriting_missing_info,
                missing.joinToString(", ") { it.englishName },
            ),
        )
    }
}

/**
 * Weather location: place label plus coordinates, edited in a dialog. Shared
 * with the onboarding tool-setup page, which asks the same question.
 */
@Composable
internal fun WeatherLocationSetting(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    val unnamedPlace = stringResource(R.string.privacy_weather_place_unnamed)
    val savedLatitude = settings.weatherLatitude
    val savedLongitude = settings.weatherLongitude
    val summary = if (savedLatitude != null && savedLongitude != null) {
        stringResource(
            R.string.privacy_weather_location_summary,
            settings.weatherPlaceName.ifBlank { unnamedPlace },
            savedLatitude,
            savedLongitude,
        )
    } else {
        stringResource(R.string.privacy_weather_location_empty)
    }
    WmRow(
        title = stringResource(R.string.privacy_weather_location_title),
        subtitle = summary,
        trailing = {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.privacy_weather_edit_desc),
            )
        },
        onClick = { editing = true },
    )
    if (!editing) return

    var place by remember { mutableStateOf(settings.weatherPlaceName) }
    var lat by remember { mutableStateOf(settings.weatherLatitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(settings.weatherLongitude?.toString().orEmpty()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val parsedLat = lat.trim().toFloatOrNull()?.takeIf { it in -90f..90f }
    val parsedLon = lon.trim().toFloatOrNull()?.takeIf { it in -180f..180f }

    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        searchFailed = false
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { WeatherClient.geocode(query) }.getOrNull()
            }
            searching = false
            if (found == null) {
                searchFailed = true
            } else {
                results = found
                searchFailed = found.isEmpty()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { editing = false },
        // Typing in the search/coordinate fields moves the dialog around the
        // keyboard, so a tap can land on the scrim where the dialog just was
        // and silently swallow the half-entered location. Explicit
        // Cancel/Save only; back still dismisses.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.privacy_weather_dialog_title)) },
        text = {
            val unknownRegion = stringResource(R.string.privacy_weather_region_unknown)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.privacy_weather_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { search() }, enabled = query.isNotBlank() && !searching) {
                        Text(if (searching) "…" else stringResource(CommonR.string.common_search))
                    }
                }
                if (searchFailed) {
                    Text(
                        if (results.isEmpty() && !searching) {
                            stringResource(R.string.privacy_weather_no_matches_error)
                        } else {
                            stringResource(R.string.privacy_weather_search_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                for (result in results) {
                    ListItem(
                        headlineContent = { Text(result.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.privacy_weather_result_summary,
                                    result.region.ifBlank { unknownRegion },
                                    result.latitude,
                                    result.longitude,
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                place = result.name
                                lat = result.latitude.toString()
                                lon = result.longitude.toString()
                                results = emptyList()
                            },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_weather_manual_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text(stringResource(R.string.privacy_weather_name_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text(stringResource(R.string.privacy_weather_latitude_hint)) },
                    singleLine = true,
                    isError = lat.isNotBlank() && parsedLat == null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text(stringResource(R.string.privacy_weather_longitude_hint)) },
                    singleLine = true,
                    isError = lon.isNotBlank() && parsedLon == null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedLat != null && parsedLon != null,
                onClick = {
                    scope.launch {
                        repository.setWeatherLocation(parsedLat, parsedLon, place.trim())
                    }
                    editing = false
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (settings.weatherLatitude != null) {
                    TextButton(onClick = {
                        scope.launch { repository.setWeatherLocation(null, null, "") }
                        editing = false
                    }) { Text(stringResource(CommonR.string.common_clear)) }
                }
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        },
    )
}

// ---- privacy ----

/**
 * The explanation of "Follow private browsing". Two screens show it: the
 * Privacy screen below, and the incognito tool's own settings. It is a
 * resource id, not the text, so it is read where it is drawn.
 */
@StringRes
private val AUTO_INCOGNITO_INFO = R.string.privacy_auto_incognito_info

@Composable
private fun PrivacySettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // An unnamed group has no SectionHeader to hold it off the top bar, so the
    // breathing room a named group gets for free is spelled out here.
    Spacer(Modifier.height(12.dp))
    SettingsGroup {
        item {
            NavRow(
                R.string.privacy_permissions_title,
                stringResource(R.string.privacy_permissions_subtitle),
                route = "permissions",
            ) { onNavigate("permissions") }
        }
        item {
            val lock = LocalAppLock.current
            val lockStatus by lock.status.collectAsStateWithLifecycle()
            val lockConfig by lock.config.collectAsStateWithLifecycle()
            NavRow(
                R.string.privacy_lock_title,
                stringResource(R.string.privacy_lock_subtitle),
                value = stringResource(
                    when {
                        // What the phone can do beats what the flag says; see
                        // [AppLockSettings]. A row reading "On" next to gates
                        // that are standing aside would be a lie.
                        !lockStatus.canEnable -> R.string.privacy_lock_state_unavailable
                        lockConfig?.enabled == true -> R.string.privacy_lock_state_on
                        else -> R.string.privacy_lock_state_off
                    },
                ),
                route = AppLockTargets.ROUTE,
            ) { onNavigate(AppLockTargets.ROUTE) }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_learning_group_title)) {
        item {
            ToggleSetting(
                R.string.privacy_learn_typing_title,
                stringResource(R.string.privacy_learn_typing_subtitle),
                settings.learnFromTyping,
                info = stringResource(R.string.privacy_learn_typing_info),
                default = SettingsDefaults.learnFromTyping,
            ) { scope.launch { repository.setLearnFromTyping(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_system_dictionary_title,
                stringResource(R.string.privacy_system_dictionary_subtitle),
                settings.addWordsToSystemDictionary,
                info = stringResource(R.string.privacy_system_dictionary_info),
                default = SettingsDefaults.addWordsToSystemDictionary,
            ) { scope.launch { repository.setAddWordsToSystemDictionary(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_dict_shortcuts_title,
                stringResource(R.string.privacy_dict_shortcuts_subtitle),
                settings.suggestionStrip.expandUserDictShortcuts,
                info = stringResource(R.string.privacy_dict_shortcuts_info),
                default = SettingsDefaults.suggestionStrip.expandUserDictShortcuts,
            ) { scope.launch { repository.setExpandUserDictShortcuts(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_incognito_title,
                stringResource(R.string.privacy_incognito_subtitle),
                settings.incognito,
                info = stringResource(R.string.privacy_incognito_info),
                default = SettingsDefaults.incognito,
            ) { scope.launch { repository.setIncognito(it) } }
        }
        item {
            ToggleSetting(
                R.string.privacy_auto_incognito_title,
                stringResource(R.string.privacy_auto_incognito_subtitle),
                settings.autoIncognito,
                info = stringResource(AUTO_INCOGNITO_INFO),
                default = SettingsDefaults.autoIncognito,
            ) { scope.launch { repository.setAutoIncognito(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_backup_group_title)) {
        item {
            ToggleSetting(
                R.string.privacy_backup_title,
                stringResource(R.string.privacy_backup_subtitle),
                settings.cloudBackup,
                info = stringResource(R.string.privacy_backup_info),
                default = SettingsDefaults.cloudBackup,
            ) { scope.launch { repository.setCloudBackup(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.privacy_data_group_title)) {
        item {
            ActionRow(
                title = R.string.privacy_delete_learned_words_title,
                subtitle = stringResource(R.string.privacy_delete_learned_words_subtitle),
                action = stringResource(R.string.privacy_delete_learned_words_action),
                confirm = stringResource(R.string.privacy_delete_learned_words_confirm),
                lock = AppLockTargets["action_delete_learned_words"],
            ) {
                scope.launch {
                    repository.clearLearnedData()
                    // The Chinese, Japanese and Cantonese picks are one of the
                    // files the repository deletes, but its live store is a
                    // `:core:input` object that `:core:settings` cannot reach,
                    // so the in-memory copy in this process is dropped here.
                    CjkLearning.store?.clear()
                }
            }
        }
    }
    CaptionText(stringResource(R.string.privacy_on_device_info))
}

// ---- voice typing ----

/**
 * The Voice typing screen: the engine, the microphone view, and dictation.
 *
 * A Features row rather than the Voice typing tool page, which now holds one
 * row that opens this — the same split the Emoji tool has. Dictation is a way
 * of typing, and the microphone can be reached from a key or a hardware
 * shortcut with the tool nowhere on the toolbar, so these are not the tool's
 * settings.
 */
@Composable
private fun VoiceSettings(repository: SettingsRepository, settings: KeyboardSettings) {
    val scope = rememberCoroutineScope()
    val whisperEnabled = com.wasimaster.wmkeyboard.core.settings.isWhisperEnabled()
    val usingWhisper = whisperEnabled && settings.whisper.engine == "whisper"
    if (whisperEnabled) {
        val systemEngine = stringResource(R.string.voice_engine_system)
        val whisperEngine = stringResource(R.string.voice_engine_whisper)
        SettingsGroup(stringResource(R.string.voice_engine_group)) {
            item {
                ChoiceSetting(
                    R.string.voice_engine_title,
                    subtitle = stringResource(R.string.voice_engine_subtitle),
                    info = stringResource(R.string.voice_engine_info),
                    options = listOf(
                        "system" to systemEngine,
                        "whisper" to whisperEngine,
                    ),
                    selected = settings.whisper.engine,
                    default = SettingsDefaults.whisper.engine,
                ) { scope.launch { repository.setVoiceEngine(it) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.voice_dictation_group)) {
        item {
            ChoiceSetting(
                R.string.voice_ui_title,
                subtitle = stringResource(R.string.voice_ui_subtitle),
                info = stringResource(R.string.voice_ui_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_PANEL to
                        stringResource(R.string.voice_ui_panel),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_STRIP to
                        stringResource(R.string.voice_ui_strip),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.MODE_BAR to
                        stringResource(R.string.voice_ui_bar),
                ),
                selected = settings.voiceBar.mode,
                default = SettingsDefaults.voiceBar.mode,
            ) { scope.launch { repository.setVoiceUiMode(it) } }
        }
        item {
            ChoiceSetting(
                R.string.voice_typing_title,
                subtitle = stringResource(R.string.voice_typing_subtitle),
                info = stringResource(R.string.voice_typing_info),
                options = listOf(
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_BLOCK to
                        stringResource(R.string.voice_typing_block),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_INTERACTIVE to
                        stringResource(R.string.voice_typing_interactive),
                    com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings.TYPING_PLAIN to
                        stringResource(R.string.voice_typing_plain),
                ),
                selected = settings.voiceBar.typingMode,
                default = SettingsDefaults.voiceBar.typingMode,
            ) { scope.launch { repository.setVoiceTypingMode(it) } }
        }
        item {
            val holdMsFormat = stringResource(R.string.typing_value_milliseconds)
            SliderSetting(
                R.string.voice_hold_title,
                subtitle = stringResource(R.string.voice_hold_subtitle),
                value = settings.voiceBar.holdToTalkMs.toFloat(),
                range = HoldToTalkRange.first.toFloat()..HoldToTalkRange.last.toFloat(),
                display = { holdMsFormat.format((it / 50f).roundToInt() * 50) },
                info = stringResource(R.string.voice_hold_info),
                default = SettingsDefaults.voiceBar.holdToTalkMs.toFloat(),
            ) { picked ->
                scope.launch {
                    repository.setHoldToTalkMs((picked / 50f).roundToInt() * 50)
                }
            }
        }
        item {
            ToggleSetting(
                R.string.voice_continuous_title,
                stringResource(R.string.voice_continuous_subtitle),
                settings.voiceContinuous,
                default = SettingsDefaults.voiceContinuous,
            ) { scope.launch { repository.setVoiceContinuous(it) } }
        }
        item {
            ToggleSetting(
                R.string.voice_punctuation_title,
                stringResource(R.string.voice_punctuation_subtitle),
                settings.voiceSpokenPunctuation,
                default = SettingsDefaults.voiceSpokenPunctuation,
            ) { scope.launch { repository.setVoiceSpokenPunctuation(it) } }
        }
    }
    if (usingWhisper) {
        SettingsGroup(stringResource(R.string.voice_offline_group)) {
            item {
                ToggleSetting(
                    R.string.voice_translate_title,
                    stringResource(R.string.voice_translate_subtitle),
                    settings.whisper.translate,
                    default = SettingsDefaults.whisper.translate,
                ) { scope.launch { repository.setWhisperTranslate(it) } }
            }
        }
        WhisperModelManager(repository, settings)
    } else {
        CaptionText(stringResource(R.string.voice_system_info))
    }
}

// ---- clipboard ----

/**
 * The Clipboard screen: the history, the panel, and the sensitive-clip rules.
 *
 * A Features row rather than the Clipboard tool page, which now holds one row
 * that opens this — the same split the Emoji tool has. The history is filled
 * by every copy you make, whether or not the panel's button is on the toolbar,
 * so these are not the tool's settings.
 */
@Composable
private fun ClipboardSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The slider readouts are plain lambdas, so their format strings are
    // resolved here and captured. The format also puts the number through the
    // locale, which is what gives Bengali or Arabic digits.
    val numberFormat = stringResource(R.string.values_number)
    val minutesFormat = stringResource(R.string.values_minutes)
    val hoursFormat = stringResource(R.string.values_hours)
    // Both grants happen on a system screen, so they are read through
    // rememberGrantState: the rows below disappear as soon as we come back
    // with the permission in hand, instead of on the next unrelated redraw.
    val screenshotsGranted = rememberGrantState(::hasImagesPermission)
    val usageAccessGranted = rememberGrantState(::hasUsageAccess)
    SettingsGroup(stringResource(R.string.clipboard_history_group)) {
        item {
            ToggleSetting(
                R.string.clipboard_history_title,
                stringResource(R.string.clipboard_history_subtitle),
                settings.clipboard.history,
                default = SettingsDefaults.clipboard.history,
            ) { scope.launch { repository.setClipboardHistory(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_suggest_recent_title,
                stringResource(R.string.clipboard_suggest_recent_subtitle),
                settings.clipboard.suggestRecent,
                default = SettingsDefaults.clipboard.suggestRecent,
            ) { scope.launch { repository.setClipboardSuggestRecent(it) } }
        }
        if (settings.clipboard.suggestRecent) {
            item {
                val untilDismissed =
                    stringResource(R.string.clipboard_chip_until_dismissed)
                val chipMinutesFormat = stringResource(R.string.values_minutes)
                val secondsFormat = stringResource(R.string.values_seconds)
                SliderSetting(
                    R.string.clipboard_chip_life_title,
                    subtitle = stringResource(R.string.clipboard_chip_life_subtitle),
                    value = settings.clipboard.pasteChipSeconds.toFloat(),
                    // Steps of 30 s to 30 min, with 0 at the top of the
                    // range reading as a word rather than a duration.
                    range = 0f..1800f,
                    display = { value ->
                        val secs = (value / 30f).roundToInt() * 30
                        when {
                            secs <= 0 -> untilDismissed
                            secs < 60 -> secondsFormat.format(secs)
                            else -> chipMinutesFormat.format(secs / 60)
                        }
                    },
                    info = stringResource(R.string.clipboard_chip_life_info),
                    default = SettingsDefaults.clipboard.pasteChipSeconds.toFloat(),
                ) { value ->
                    val secs = (value / 30f).roundToInt() * 30
                    scope.launch { repository.setPasteChipSeconds(secs) }
                }
            }
        }
        if (settings.clipboard.suggestRecent) {
            item {
                ToggleSetting(
                    R.string.clipboard_suggest_codes_title,
                    stringResource(R.string.clipboard_suggest_codes_subtitle),
                    settings.clipboard.suggestCodesInCodeFields,
                    info = stringResource(R.string.clipboard_suggest_codes_info),
                    default = SettingsDefaults.clipboard.suggestCodesInCodeFields,
                ) { scope.launch { repository.setClipboardSuggestCodesInCodeFields(it) } }
            }
        }
        item {
            ToggleSetting(
                R.string.clipboard_toast_title,
                stringResource(R.string.clipboard_toast_subtitle),
                settings.feedback.toastOnCopy,
                info = stringResource(R.string.clipboard_toast_info),
                default = SettingsDefaults.feedback.toastOnCopy,
            ) { scope.launch { repository.setToastOnCopy(it) } }
        }
        item {
            // The readout lambda is not composable, so the "never" word
            // is resolved here and captured, like the hours format.
            val never = stringResource(R.string.clipboard_expiry_never)
            SliderSetting(
                R.string.clipboard_expiry_title,
                subtitle = stringResource(R.string.clipboard_expiry_subtitle),
                value = settings.clipboard.expiryHours.toFloat(),
                range = 0f..168f,
                display = { if (it.toInt() == 0) never else hoursFormat.format(it.toInt()) },
                default = SettingsDefaults.clipboard.expiryHours.toFloat(),
            ) { scope.launch { repository.setClipboardExpiryHours(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.clipboard_max_title,
                subtitle = stringResource(R.string.clipboard_max_subtitle),
                value = settings.clipboard.maxItems.toFloat(),
                range = 5f..500f,
                display = { numberFormat.format(it.toInt()) },
                info = stringResource(R.string.clipboard_max_info),
                default = SettingsDefaults.clipboard.maxItems.toFloat(),
            ) { scope.launch { repository.setClipboardMaxItems(it.toInt()) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_bottom_row_title,
                stringResource(R.string.clipboard_bottom_row_subtitle),
                settings.clipboard.bottomRow,
                default = SettingsDefaults.clipboard.bottomRow,
            ) { scope.launch { repository.setClipboardBottomRow(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_full_bleed_title,
                stringResource(R.string.clipboard_full_bleed_subtitle),
                settings.clipboard.fullBleed,
                info = stringResource(R.string.clipboard_full_bleed_info),
                default = SettingsDefaults.clipboard.fullBleed,
            ) { scope.launch { repository.setClipboardFullBleed(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_pinned_last_title,
                stringResource(R.string.clipboard_pinned_last_subtitle),
                settings.clipboard.pinnedLast,
                default = SettingsDefaults.clipboard.pinnedLast,
            ) { scope.launch { repository.setClipboardPinnedLast(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_search_title,
                stringResource(R.string.clipboard_search_subtitle),
                settings.clipboard.search,
                default = SettingsDefaults.clipboard.search,
            ) { scope.launch { repository.setClipboardSearch(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_entities_title,
                stringResource(R.string.clipboard_entities_subtitle),
                settings.clipboard.detectEntities,
                info = stringResource(R.string.clipboard_entities_info),
                default = SettingsDefaults.clipboard.detectEntities,
            ) { scope.launch { repository.setClipboardDetectEntities(it) } }
        }
        // The number chips are the ones that go wrong, because a phone
        // number is the one fragment with no shape of its own. This row
        // is where the user gives it one.
        if (settings.clipboard.detectEntities) {
            item {
                val count = settings.clipboard.phoneFormats.size
                NavRow(
                    R.string.clipboard_phone_formats_title,
                    subtitle = if (count == 0) {
                        stringResource(R.string.clipboard_phone_formats_subtitle)
                    } else {
                        pluralStringResource(
                            R.plurals.clipboard_phone_formats_count_subtitle,
                            count,
                            count,
                        )
                    },
                    route = "phoneformats",
                    onClick = { onNavigate("phoneformats") },
                )
            }
        }
        item {
            ToggleSetting(
                R.string.clipboard_password_paste_title,
                stringResource(R.string.clipboard_password_paste_subtitle),
                settings.clipboard.clearAfterPasswordPaste,
                info = stringResource(R.string.clipboard_password_paste_info),
                default = SettingsDefaults.clipboard.clearAfterPasswordPaste,
            ) { scope.launch { repository.setClipboardClearAfterPasswordPaste(it) } }
        }
        item {
            ToggleSetting(
                R.string.clipboard_link_previews_title,
                stringResource(R.string.clipboard_link_previews_subtitle),
                settings.clipboard.linkPreviews,
                default = SettingsDefaults.clipboard.linkPreviews,
            ) { scope.launch { repository.setClipboardLinkPreviews(it) } }
        }
        item {
            val context = LocalContext.current
            ToggleSetting(
                R.string.clipboard_screenshots_title,
                stringResource(R.string.clipboard_screenshots_subtitle),
                settings.clipboard.userScreenshots,
                default = SettingsDefaults.clipboard.userScreenshots,
            ) { on ->
                scope.launch { repository.setClipboardUserScreenshots(on) }
                if (on && !hasImagesPermission(context)) {
                    runCatching {
                        context.startActivity(Intent(context, ImagesPermissionActivity::class.java))
                    }
                }
            }
        }
        // The guard sits outside item {} on purpose: an item whose body
        // draws nothing still gets its own card, which showed up as a
        // sliver of empty surface once the permission was granted.
        if (settings.clipboard.userScreenshots && !screenshotsGranted) {
            item {
                val context = LocalContext.current
                NavRow(
                    R.string.clipboard_storage_permission_title,
                    stringResource(R.string.clipboard_storage_permission_subtitle),
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        item {
            val context = LocalContext.current
            val usageAccess = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
            ToggleSetting(
                R.string.clipboard_track_source_title,
                stringResource(R.string.clipboard_track_source_subtitle),
                settings.clipboard.trackSource,
                info = stringResource(R.string.clipboard_track_source_info),
                default = SettingsDefaults.clipboard.trackSource,
            ) { on ->
                scope.launch { repository.setClipboardTrackSource(on) }
                // Disclosure then the grant screen, the first time they
                // switch it on — but not when it is already granted, which
                // is the common case for a toggle flipped off and on again.
                if (on && !hasUsageAccess(context)) usageAccess()
            }
        }
        if (settings.clipboard.trackSource && !usageAccessGranted) {
            item {
                val usageAccessRow = rememberDisclosedSpecialAccess(SpecialAccess.USAGE)
                NavRow(
                    R.string.clipboard_usage_permission_title,
                    stringResource(R.string.clipboard_usage_permission_subtitle),
                ) { usageAccessRow() }
            }
        }
    }
    SettingsGroup(stringResource(R.string.clipboard_sensitive_group)) {
        item {
            ChoiceSetting(
                title = R.string.clipboard_sensitive_title,
                subtitle = stringResource(R.string.clipboard_sensitive_subtitle),
                info = stringResource(R.string.clipboard_sensitive_info),
                options = SensitiveClipHandling.entries.map { it to stringResource(it.labelRes) },
                selected = settings.clipboard.sensitiveHandling,
                default = SettingsDefaults.clipboard.sensitiveHandling,
            ) { scope.launch { repository.setClipboardSensitiveHandling(it) } }
        }
        if (settings.clipboard.sensitiveHandling != SensitiveClipHandling.KEEP) {
            item {
                ToggleSetting(
                    R.string.clipboard_detect_sensitive_title,
                    stringResource(R.string.clipboard_detect_sensitive_subtitle),
                    settings.clipboard.detectSensitive,
                    info = stringResource(R.string.clipboard_detect_sensitive_info),
                    default = SettingsDefaults.clipboard.detectSensitive,
                ) { scope.launch { repository.setClipboardDetectSensitive(it) } }
            }
        }
        if (settings.clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
            item {
                SliderSetting(
                    R.string.clipboard_sensitive_expiry_title,
                    subtitle = stringResource(
                        R.string.clipboard_sensitive_expiry_subtitle,
                    ),
                    value = settings.clipboard.sensitiveExpiryMinutes.toFloat(),
                    range = 1f..120f,
                    display = { minutesFormat.format(it.toInt()) },
                    default = SettingsDefaults.clipboard.sensitiveExpiryMinutes.toFloat(),
                ) {
                    scope.launch { repository.setClipboardSensitiveExpiryMinutes(it.toInt()) }
                }
            }
        }
    }
}

// ---- text expander ----

/**
 * The Text Expander screen: every snippet setting there is.
 *
 * It is a Features row rather than the Snippets tool page, which now holds one
 * row that opens this — the same split the Emoji tool has. A snippet expands
 * while you type whether or not the tool is on the toolbar, so its settings
 * are not the tool's.
 */
@Composable
private fun SnippetSettings(onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, "snippets/snippets.json") }
    // SnippetStore's constructor reads and JSON-parses the file, so it (and
    // every save) runs on Dispatchers.IO, not during composition or on a click.
    var store by remember { mutableStateOf<SnippetStore?>(null) }
    var snippets by remember { mutableStateOf<List<Snippet>>(emptyList()) }
    var folders by remember { mutableStateOf<List<SnippetFolder>>(emptyList()) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The folder being renamed, or a blank one standing for "add a folder".
    var namingFolder by remember { mutableStateOf<SnippetFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<SnippetFolder?>(null) }

    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) { SnippetStore(file) }
        snippets = s.items()
        folders = s.folders()
        store = s
    }

    fun mutate(block: (SnippetStore) -> Unit) {
        val s = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                block(s)
                s.save()
            }
            snippets = s.items()
            folders = s.folders()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SnippetFile.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        out.write(
                            SnippetFile.encode(
                                current,
                                appVersion = BuildConfig.VERSION_CODE,
                                appVersionName = BuildConfig.VERSION_NAME,
                                folders = currentFolders,
                            ).toByteArray(),
                        )
                    } ?: error("no stream")
                }.isSuccess
            }
            message = if (ok) {
                context.resources.getQuantityString(
                    R.plurals.expander_saved_count, current.size, current.size,
                )
            } else {
                context.getString(R.string.expander_export_error)
            }
        }
    }

    // An Espanso file is somebody else's, in a format that cannot say
    // everything this app's own can, so it is described and confirmed before
    // anything is written. The app's own format applies straight away, exactly
    // as it always has.
    var pendingImport by remember { mutableStateOf<SnippetPayload.Parsed?>(null) }
    var pendingExport by remember { mutableStateOf<List<ContentText>>(emptyList()) }
    var importSource by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf(false) }
    var urlPrompt by remember { mutableStateOf(false) }
    var packagePrompt by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf(EspansoManifest("", "", "")) }
    var busy by remember { mutableStateOf(false) }

    /** Writes [parsed] into the store, under a folder when it names one. */
    fun applyImport(parsed: SnippetPayload.Parsed, folderName: String?) {
        val s = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                // Whole snippets, not a handful of named fields: rebuilding them
                // would quietly drop whatever the format gained last. An Espanso
                // file has no folders of its own, so it lands in one named after
                // the package, which is what gives it a single off switch.
                val target = folderName?.trim()?.takeIf { it.isNotEmpty() }?.let { s.addFolder(it).id } ?: 0L
                s.addAll(parsed.snippets, parsed.folders, fallbackFolderId = target)
                // The adds are in-memory only; save() writes the file.
                s.save()
            }
            snippets = s.items()
            folders = s.folders()
            message = buildString {
                append(
                    context.resources.getQuantityString(
                        R.plurals.expander_imported_count,
                        parsed.snippets.size,
                        parsed.snippets.size,
                    ),
                )
                if (parsed.notes.isNotEmpty()) {
                    append("\n\n")
                    append(context.getString(R.string.expander_import_repairs_title))
                    // The reader hands back a resource and its arguments, so the
                    // note is worded here.
                    for (line in parsed.notes) append("\n• ${line.resolve(context)}")
                }
            }
        }
    }

    /** Reads whatever was picked, then either applies it or asks first. */
    fun offerImport(uri: Uri) {
        if (store == null) return
        scope.launch {
            val name = WMFileTypes.displayName(context, uri)
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SnippetPayload.read(it.readBytes(), name)
                    }
                }.getOrNull()
            }
            when {
                parsed == null -> message = context.getString(R.string.expander_import_error)
                parsed.isEspanso -> pendingImport = parsed
                else -> applyImport(parsed, folderName = null)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::offerImport) }

    val espansoExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EspansoWriter.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                // Encoding is inside the runCatching, not in front of it: a
                // throw out here would escape the coroutine and take the
                // process with it rather than reaching the error dialog.
                runCancellable {
                    val export = EspansoWriter.encodeMatchFile(current, currentFolders)
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(export.text.toByteArray())
                    }
                    export.notes
                }.getOrNull()
            }
            if (written == null) {
                message = context.getString(R.string.expander_export_error)
            } else {
                pendingExport = written
                if (written.isEmpty()) {
                    message = context.resources.getQuantityString(
                        R.plurals.expander_saved_count, current.size, current.size,
                    )
                }
            }
        }
    }

    val packageExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EspansoWriter.PACKAGE_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        val currentManifest = manifest
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                // Encoding inside the runCatching, for the reason spelled out
                // on the match-file launcher above.
                runCancellable {
                    val (bytes, notes) = EspansoWriter.encodePackage(current, currentFolders, currentManifest)
                    context.contentResolver.requireOutputStream(uri).use { it.write(bytes) }
                    notes
                }.getOrNull()
            }
            if (written == null) {
                message = context.getString(R.string.expander_export_error)
            } else {
                pendingExport = written
                if (written.isEmpty()) {
                    message = context.resources.getQuantityString(
                        R.plurals.expander_saved_count, current.size, current.size,
                    )
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Text(
        stringResource(R.string.expander_intro_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    AddonStoreGroup(AddonType.Snippets, onNavigate)
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.expander_variables_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            // Live examples: expand the actual templates so the preview always
            // matches what an insertion would produce right now. The variables
            // the IME alone can fill in get a stand-in example instead.
            for (variable in SnippetVariable.entries) {
                VariableRow(
                    variable.token,
                    stringResource(variable.descriptionRes),
                    sampleFor(variable),
                )
            }
            VariableRow(
                "{date:…}", stringResource(R.string.expander_var_date_pattern_info),
                SnippetStore.expand("{date:EEE d MMM}"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.expander_variables_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.expander_pattern_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.expander_pattern_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { showAdd = true }) {
            Text(stringResource(R.string.expander_add_action))
        }
        OutlinedButton(
            onClick = { importSource = true },
        ) { Text(stringResource(CommonR.string.common_import)) }
        OutlinedButton(
            onClick = { exportTarget = true },
            enabled = snippets.isNotEmpty(),
        ) { Text(stringResource(CommonR.string.common_export)) }
    }
    Spacer(Modifier.height(12.dp))
    // Both this list and the snippets panel draw in stored order, and the panel
    // has no search, so a snippet used daily sank under a year of one-off ones.
    // The row disables itself below two snippets, where order means nothing.
    if (snippets.isNotEmpty()) {
        SettingsGroup {
            item {
                ReorderSetting(
                    title = stringResource(R.string.expander_reorder_title),
                    dialogTitle = stringResource(R.string.expander_reorder_title),
                    items = snippets,
                    label = { it.label },
                    onReordered = { ordered -> mutate { s -> s.reorder(ordered.map { it.id }) } },
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.expander_folders_title)) {
        item { CaptionText(stringResource(R.string.expander_folders_info)) }
        if (folders.size > 1) {
            item {
                ReorderSetting(
                    title = stringResource(R.string.expander_folder_order_title),
                    dialogTitle = stringResource(R.string.expander_folder_order_title),
                    items = folders,
                    label = { it.name },
                    onReordered = { ordered ->
                        mutate { s -> s.reorderFolders(ordered.map { it.id }) }
                    },
                )
            }
        }
        for (folder in folders) {
            item {
                val count = snippets.count { it.folderId == folder.id }
                WmRow(
                    title = folder.name,
                    icon = Icons.Outlined.Folder,
                    subtitle = buildString {
                        append(
                            pluralStringResource(R.plurals.expander_folder_count, count, count),
                        )
                        if (!folder.enabled) {
                            append(" • ")
                            append(stringResource(R.string.expander_folder_off_label))
                        }
                    },
                    // The row itself renames; the switch is the one action worth
                    // its own target, and delete asks before it does anything.
                    onClick = { namingFolder = folder },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val switchDesc = stringResource(
                                R.string.expander_folder_switch_desc, folder.name,
                            )
                            Switch(
                                checked = folder.enabled,
                                onCheckedChange = { on ->
                                    mutate { it.setFolderEnabled(folder.id, on) }
                                },
                                modifier = Modifier.semantics { contentDescription = switchDesc },
                            )
                            IconButton(onClick = { deletingFolder = folder }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(CommonR.string.common_delete),
                                )
                            }
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.expander_folder_new_action),
                icon = Icons.Outlined.Add,
                onClick = { namingFolder = SnippetFolder(id = 0, name = "") },
            )
        }
    }
    // One section per folder, then whatever is in none of them. A folder with
    // nothing in it is not drawn here — it is already listed above, and an empty
    // headed section reads as a section that failed to load.
    for (folder in folders) {
        val inFolder = snippets.filter { it.folderId == folder.id }
        if (inFolder.isEmpty()) continue
        SettingsGroup(folder.name) {
            for (snippet in inFolder) {
                item {
                    SnippetRow(
                        snippet,
                        onEdit = { editing = snippet },
                        onDelete = { mutate { it.remove(snippet.id) } },
                    )
                }
            }
        }
    }
    val loose = snippets.filter { it.folderId == 0L }
    SettingsGroup(
        // Only worth a heading once there is something to tell it apart from.
        title = if (folders.isEmpty()) null else stringResource(R.string.expander_no_folder_title),
    ) {
        for (snippet in loose) {
            item {
                SnippetRow(
                    snippet,
                    onEdit = { editing = snippet },
                    onDelete = { mutate { it.remove(snippet.id) } },
                )
            }
        }
    }

    if (showAdd || editing != null) {
        SnippetDialog(
            initial = editing,
            folders = folders,
            onDismiss = { showAdd = false; editing = null },
            onSave = { draft ->
                val current = editing
                mutate { s ->
                    if (current == null) {
                        s.add(draft)
                    } else {
                        s.update(
                            current.id,
                            draft.label,
                            draft.text,
                            draft.trigger,
                            draft.triggerPattern,
                            draft.triggerWords,
                            draft.confirm,
                            draft.folderId,
                            draft.aliases,
                            draft.propagateCase,
                            draft.uppercaseStyle,
                        )
                    }
                }
                showAdd = false
                editing = null
            },
        )
    }

    namingFolder?.let { folder ->
        SnippetFolderNameDialog(
            initial = folder,
            onDismiss = { namingFolder = null },
            onSave = { name ->
                mutate { s ->
                    if (folder.id == 0L) s.addFolder(name) else s.renameFolder(folder.id, name)
                }
                namingFolder = null
            },
        )
    }

    deletingFolder?.let { folder ->
        SnippetFolderDeleteDialog(
            folder = folder,
            count = snippets.count { it.folderId == folder.id },
            onDismiss = { deletingFolder = null },
            onDelete = { withSnippets ->
                mutate { it.removeFolder(folder.id, withSnippets) }
                deletingFolder = null
            },
        )
    }

    if (importSource) {
        SnippetSourceDialog(
            title = R.string.expander_import_source_title,
            options = listOf(
                R.string.expander_source_native to R.string.expander_source_native_body,
                R.string.expander_source_espanso to R.string.expander_source_espanso_body,
                R.string.expander_source_url to R.string.expander_source_url_body,
            ),
            onDismiss = { importSource = false },
            onPick = { index ->
                importSource = false
                when (index) {
                    0 -> importLauncher.launch(SnippetFile.IMPORT_MIME_TYPES)
                    1 -> importLauncher.launch(SnippetPayload.IMPORT_MIME_TYPES)
                    else -> urlPrompt = true
                }
            },
        )
    }

    if (exportTarget) {
        SnippetSourceDialog(
            title = R.string.expander_export_target_title,
            options = listOf(
                R.string.expander_target_native to R.string.expander_target_native_body,
                R.string.expander_target_espanso to R.string.expander_target_espanso_body,
                R.string.expander_target_package to R.string.expander_target_package_body,
            ),
            onDismiss = { exportTarget = false },
            onPick = { index ->
                exportTarget = false
                when (index) {
                    0 -> exportLauncher.launch(SnippetFile.fileName())
                    1 -> espansoExportLauncher.launch(EspansoWriter.FILE_NAME)
                    else -> packagePrompt = true
                }
            },
        )
    }

    if (urlPrompt) {
        SnippetUrlDialog(
            busy = busy,
            onDismiss = { if (!busy) urlPrompt = false },
            onFetch = { pasted ->
                busy = true
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) { fetchEspanso(pasted) }
                    busy = false
                    urlPrompt = false
                    when {
                        parsed == null -> message = context.getString(R.string.expander_url_error)
                        parsed.isEspanso -> pendingImport = parsed
                        else -> applyImport(parsed, folderName = null)
                    }
                }
            },
        )
    }

    pendingImport?.let { parsed ->
        SnippetEspansoImportDialog(
            parsed = parsed,
            onDismiss = { pendingImport = null },
            onImport = { folderName ->
                pendingImport = null
                applyImport(parsed, folderName)
            },
        )
    }

    if (packagePrompt) {
        SnippetPackageDialog(
            onDismiss = { packagePrompt = false },
            onExport = { built ->
                packagePrompt = false
                manifest = built
                packageExportLauncher.launch("${EspansoManifest.sanitizeName(built.name)}.zip")
            },
        )
    }

    if (pendingExport.isNotEmpty()) {
        val lines = pendingExport
        AlertDialog(
            onDismissRequest = { pendingExport = emptyList() },
            title = { Text(stringResource(R.string.expander_export_notes_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = DialogScrollMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (line in lines) Text("• ${line.resolve(context)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingExport = emptyList() }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

/**
 * Fetches an Espanso file from a pasted address and reads it.
 *
 * A Hub package page names a package without saying where its files are, so
 * that shape takes two requests: list the package's versions, take the newest,
 * then fetch that version's `package.yml`. Everything else is one.
 *
 * Blocking, so call it on IO. Every failure is null; the dialog says the same
 * thing whichever step it was, because none of the differences is something the
 * person pasting a link can act on.
 */
private fun fetchEspanso(pasted: String): SnippetPayload.Parsed? {
    val target = EspansoHub.resolve(pasted) ?: return null
    val url = when (target) {
        is EspansoHub.Target.Direct -> target.url
        is EspansoHub.Target.HubPackage -> {
            val listing = runCancellable { ToolHttp.get(target.contentsUrl) }.getOrNull() ?: return null
            val version = EspansoHub.newestVersion(listing) ?: return null
            target.packageUrl(version)
        }
    }
    val temp = java.io.File.createTempFile("espanso", null)
    return try {
        runCancellable {
            ToolHttp.download(url, temp, maxBytes = EspansoFile.MAX_BYTES.toLong())
        }.getOrNull() ?: return null
        SnippetPayload.read(temp, url.substringAfterLast('/'))
    } finally {
        temp.delete()
    }
}

/** A short list of ways to do the thing, one row each. */
@Composable
private fun SnippetSourceDialog(
    @StringRes title: Int,
    options: List<Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEachIndexed { index, (label, body) ->
                    WmRow(
                        title = stringResource(label),
                        supporting = { Text(stringResource(body)) },
                        onClick = { onPick(index) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Asks for an address to fetch a snippet file from. */
@Composable
private fun SnippetUrlDialog(busy: Boolean, onDismiss: () -> Unit, onFetch: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_url_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.expander_url_label)) },
                    singleLine = true,
                    enabled = !busy,
                )
                DialogNote(stringResource(R.string.expander_url_body))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = { onFetch(url) },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

/**
 * What an Espanso file holds and what it loses, before any of it is written.
 *
 * The notes are the point of the dialog. An Espanso file can say things this app
 * has no equivalent for, and finding that out after the import is worse than
 * being told and deciding.
 */
@Composable
private fun SnippetEspansoImportDialog(
    parsed: SnippetPayload.Parsed,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    val context = LocalContext.current
    var folderName by remember { mutableStateOf(parsed.suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                pluralStringResource(
                    R.plurals.expander_espanso_import_title,
                    parsed.snippets.size,
                    parsed.snippets.size,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DialogScrollMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.expander_espanso_folder_label)) },
                    singleLine = true,
                )
                DialogNote(stringResource(R.string.expander_espanso_folder_body))
                if (parsed.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.expander_espanso_changes_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    for (line in parsed.notes) DialogNote("• ${line.resolve(context)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.snippets.isNotEmpty(),
                onClick = { onImport(folderName) },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** The handful of things an Espanso package's manifest has to declare. */
@Composable
private fun SnippetPackageDialog(onDismiss: () -> Unit, onExport: (EspansoManifest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val cleaned = remember(name) { EspansoManifest.sanitizeName(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_package_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DialogScrollMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.expander_package_name_label)) },
                    singleLine = true,
                )
                // The specification allows lowercase letters, digits and hyphens
                // only, so show what the name will actually become.
                DialogNote(stringResource(R.string.expander_package_name_body, cleaned))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.expander_package_author_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.expander_package_description_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onExport(
                        EspansoManifest(
                            name = cleaned,
                            title = name.trim(),
                            description = description.trim(),
                            author = author.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(CommonR.string.common_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** One snippet in the Text Expander list: what it inserts, and what fires it. */
@Composable
private fun SnippetRow(snippet: Snippet, onEdit: () -> Unit, onDelete: () -> Unit) {
    // Snippet ids are numbers; an install records the batch it added as one
    // comma-joined list of them.
    HighlightableItem(snippet.id.toString()) {
        WmRow(
            title = snippet.label,
            supporting = {
                Column {
                    Text(snippet.text, maxLines = 2)
                    val preview = SnippetStore.expandWithCursor(
                        snippet.text,
                        context = SNIPPET_PREVIEW_CONTEXT,
                    ).text
                    if (snippet.text != preview) {
                        Text(
                            stringResource(R.string.expander_inserts_as_label, preview),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Aliases sit on the same line as the trigger: they are the
                    // same rule spelled more than once, not a second thing the
                    // row has to explain.
                    val trigger = snippet.spellings().takeIf { it.isNotEmpty() }?.joinToString(", ")
                    val pattern = snippet.triggerPattern
                    // Which of the two lines a trigger gets is only about
                    // wording: one asks first, the other rewrites what you
                    // typed, and the row has to say which.
                    if (trigger != null) {
                        Text(
                            stringResource(
                                if (snippet.confirm) {
                                    R.string.expander_trigger_asks_label
                                } else {
                                    R.string.expander_trigger_label
                                },
                                trigger,
                            ),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (pattern != null) {
                        Text(
                            stringResource(
                                if (snippet.confirm) {
                                    R.string.expander_pattern_asks_label
                                } else {
                                    R.string.expander_pattern_label
                                },
                                pattern,
                            ),
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailing = {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(CommonR.string.common_edit),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(CommonR.string.common_delete),
                        )
                    }
                }
            },
        )
    }
}

/** Names a new folder, or renames one. A blank [initial] name means new. */
@Composable
private fun SnippetFolderNameDialog(
    initial: SnippetFolder,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id == 0L) {
                        R.string.expander_folder_new_title
                    } else {
                        R.string.expander_folder_rename_title
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.expander_folder_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Asks what should happen to a folder's snippets before the folder goes.
 *
 * The switch, not a second button: Cancel has to stay reachable, and three
 * buttons in an alert is how the destructive one gets tapped by accident. It
 * starts off, so the plain answer — the folder goes, the writing stays — is the
 * one a hurried tap gives. An empty folder is not asked about at all.
 */
@Composable
private fun SnippetFolderDeleteDialog(
    folder: SnippetFolder,
    count: Int,
    onDismiss: () -> Unit,
    onDelete: (withSnippets: Boolean) -> Unit,
) {
    var withSnippets by remember(folder.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_folder_delete_title, folder.name)) },
        text = if (count == 0) {
            null
        } else {
            {
                Column {
                    Text(pluralStringResource(R.plurals.expander_folder_delete_body, count, count))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.expander_folder_delete_all_action),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = withSnippets, onCheckedChange = { withSnippets = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(withSnippets) }) {
                Text(stringResource(CommonR.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * Stand-in values so the settings preview shows a realistic expansion.
 *
 * The getter is composable because one of the stand-ins is text the user
 * reads. Every read of this property has to sit in a composable body.
 */
private val SNIPPET_PREVIEW_CONTEXT: SnippetStore.Companion.Context
    @Composable get() = SnippetStore.Companion.Context(
        clipboard = "…",
        appName = stringResource(R.string.rows_snippet_preview_app_name),
        packageName = "com.example.app",
        selection = "…",
    )

/**
 * Example value for the reference card. Most variables can be expanded for
 * real; the ones that depend on the keyboard's live context (clipboard, app,
 * selection) get a description of what they'd produce instead.
 */
@Composable
private fun sampleFor(variable: SnippetVariable): String = when (variable) {
    SnippetVariable.CLIP -> stringResource(R.string.rows_snippet_sample_clip)
    SnippetVariable.SELECTION -> stringResource(R.string.rows_snippet_sample_selection)
    SnippetVariable.APP -> "Messages"
    SnippetVariable.PACKAGE -> "com.google.android.apps.messaging"
    SnippetVariable.CURSOR -> stringResource(R.string.rows_snippet_sample_cursor)
    else -> SnippetStore.expand(variable.token)
}

/** One row in the template-variable reference card. */
@Composable
private fun VariableRow(variable: String, meaning: String, example: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            variable,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp),
        )
        Column {
            Text(meaning, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.rows_snippet_variable_example, example),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How a snippet expands as the user types: on one word, or on a pattern. */
private enum class SnippetTriggerMode { WORD, PATTERN }

/**
 * Adds or edits one snippet.
 *
 * Hands back a whole [Snippet] rather than the fields it edits. There are five
 * of them now, and the last two arrived together; a callback that names them
 * one by one has to grow every time, and every caller with it.
 */
@Composable
private fun SnippetDialog(
    initial: Snippet?,
    folders: List<SnippetFolder>,
    onDismiss: () -> Unit,
    onSave: (Snippet) -> Unit,
) {
    var folderId by remember { mutableLongStateOf(initial?.folderId ?: 0L) }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var trigger by remember { mutableStateOf(initial?.trigger.orEmpty()) }
    var pattern by remember {
        mutableStateOf(TextFieldValue(initial?.triggerPattern.orEmpty()))
    }
    var words by remember {
        mutableIntStateOf(
            initial?.triggerWords?.takeIf { it in 1..SnippetMatcher.MAX_WORDS }
                ?: SnippetMatcher.DEFAULT_WORDS,
        )
    }
    var mode by remember {
        mutableStateOf(
            if (initial?.triggerPattern.isNullOrBlank()) {
                SnippetTriggerMode.WORD
            } else {
                SnippetTriggerMode.PATTERN
            },
        )
    }
    var confirm by remember { mutableStateOf(initial?.confirm == true) }
    var aliases by remember { mutableStateOf(initial?.aliases.orEmpty().joinToString(", ")) }
    var propagateCase by remember { mutableStateOf(initial?.propagateCase == true) }
    var uppercaseStyle by remember {
        mutableStateOf(initial?.uppercaseStyle ?: UppercaseStyle.CAPITALIZE)
    }
    val fault = remember(pattern.text) { SnippetMatcher.validate(pattern.text) }
    val patternOk = mode == SnippetTriggerMode.WORD || fault == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) {
                        R.string.rows_snippet_new_title
                    } else {
                        R.string.rows_snippet_edit_title
                    },
                ),
            )
        },
        text = {
            // Seven controls do not fit a phone dialog. They did not quite fit
            // as three either.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.rows_snippet_label_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rows_snippet_text_label)) },
                    minLines = 3,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceControl(
                    options = listOf(
                        SnippetTriggerMode.WORD to stringResource(R.string.rows_snippet_mode_word_label),
                        SnippetTriggerMode.PATTERN to
                            stringResource(R.string.rows_snippet_mode_pattern_label),
                    ),
                    selected = mode,
                    onChange = { mode = it },
                )
                Spacer(Modifier.height(8.dp))
                if (mode == SnippetTriggerMode.WORD) {
                    OutlinedTextField(
                        value = trigger,
                        onValueChange = { trigger = it },
                        label = { Text(stringResource(R.string.rows_snippet_trigger_label)) },
                        singleLine = true,
                    )
                    DialogNote(stringResource(R.string.rows_snippet_trigger_body))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text(stringResource(R.string.rows_snippet_aliases_label)) },
                        singleLine = true,
                    )
                    DialogNote(stringResource(R.string.rows_snippet_aliases_body))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.rows_snippet_propagate_case_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = propagateCase, onCheckedChange = { propagateCase = it })
                    }
                    DialogNote(stringResource(R.string.rows_snippet_propagate_case_body))
                    // The style only means anything for a trigger typed with one
                    // leading capital. An all-caps trigger always shouts back.
                    if (propagateCase) {
                        Spacer(Modifier.height(8.dp))
                        ChoiceControl(
                            options = listOf(
                                UppercaseStyle.CAPITALIZE to
                                    stringResource(R.string.rows_snippet_case_first_label),
                                UppercaseStyle.CAPITALIZE_WORDS to
                                    stringResource(R.string.rows_snippet_case_words_label),
                                UppercaseStyle.UPPERCASE to
                                    stringResource(R.string.rows_snippet_case_all_label),
                            ),
                            selected = uppercaseStyle,
                            onChange = { uppercaseStyle = it },
                        )
                    }
                } else {
                    SnippetPatternFields(
                        pattern = pattern,
                        onPatternChange = { pattern = it },
                        words = words,
                        onWordsChange = { words = it },
                        text = text,
                        fault = fault,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.rows_snippet_confirm_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = confirm, onCheckedChange = { confirm = it })
                }
                DialogNote(stringResource(R.string.rows_snippet_confirm_body))
                // Only once there is a folder to pick. A picker whose one
                // choice is "None" teaches nothing and costs a row.
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.rows_snippet_folder_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    ChoiceControl(
                        options = listOf(
                            0L to stringResource(R.string.rows_snippet_folder_none_label),
                        ) + folders.map { it.id to it.name },
                        selected = folderId,
                        onChange = { folderId = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && text.isNotBlank() && patternOk,
                onClick = {
                    val word = mode == SnippetTriggerMode.WORD
                    onSave(
                        Snippet(
                            id = initial?.id ?: 0,
                            label = label.trim(),
                            text = text,
                            trigger = if (word) trigger.trim().ifBlank { null } else null,
                            aliases = if (word) splitAliases(aliases) else emptyList(),
                            propagateCase = word && propagateCase,
                            uppercaseStyle = uppercaseStyle,
                            triggerPattern = if (word) null else pattern.text.trim().ifBlank { null },
                            triggerWords = if (word) 0 else words,
                            confirm = confirm,
                            folderId = folderId,
                        ),
                    )
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The alias field's comma-separated text as a list of triggers.
 *
 * Split on commas *and* whitespace: a trigger can hold neither, so a user who
 * types "brb, omw ttyl" meant three of them however they separated them.
 */
private fun splitAliases(text: String): List<String> =
    text.split(',', ' ', '\t', '\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }

/**
 * Tallest a scrolling dialog body may grow before it starts scrolling.
 *
 * A number rather than "whatever the dialog allows". `verticalScroll` throws
 * outright when it is measured with an infinite maximum height, and a dialog is
 * one of the places that can happen: the window measures its content to wrap,
 * and a slot that wraps hands its child no ceiling. Clamping the height with
 * `heightIn(max = …)` *before* the scroll in the chain makes that impossible
 * rather than unlikely, and it is also better behaviour — an unbounded body
 * with a long list grows until it pushes the dialog's own buttons off screen.
 */
private val DialogScrollMaxHeight = 400.dp

/**
 * A small explanatory line under a field in a dialog.
 *
 * Not [CaptionText]: that one insets itself by 32dp to line up with the
 * content of a settings group, which inside a dialog reads as a mistake.
 */
@Composable
private fun DialogNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/**
 * The pattern half of the snippet dialog: the rule, how far back it may reach,
 * and a place to try it out.
 *
 * The tester is the important part. A pattern that does not fire gives no clue
 * why from inside the keyboard, and the settings app is the one place where a
 * runaway one is safe to meet — it is a different process, and a stopped
 * pattern here costs a moment rather than the keyboard.
 */
@Composable
private fun SnippetPatternFields(
    pattern: TextFieldValue,
    onPatternChange: (TextFieldValue) -> Unit,
    words: Int,
    onWordsChange: (Int) -> Unit,
    text: String,
    fault: SnippetMatcher.PatternError?,
) {
    var sample by remember { mutableStateOf("") }
    OutlinedTextField(
        value = pattern,
        onValueChange = onPatternChange,
        label = { Text(stringResource(R.string.rows_snippet_pattern_label)) },
        singleLine = true,
        isError = fault != null && pattern.text.isNotBlank(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
    // Inserted at the caret, not appended: a chip that only ever adds to the
    // end is useless once there is anything in the field.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (piece in PATTERN_PIECES) {
            AssistChip(
                onClick = { onPatternChange(pattern.insert(piece)) },
                label = { Text(piece, fontFamily = FontFamily.Monospace) },
            )
        }
    }
    if (pattern.text.isNotBlank() && fault != null) {
        DialogNote(
            stringResource(R.string.rows_snippet_pattern_error),
            color = MaterialTheme.colorScheme.error,
        )
        // The words java.util.regex uses for what is wrong are more use than
        // anything this screen could say, and they are not worth translating.
        fault.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else if (pattern.text.isNotBlank() && SnippetMatcher.headOf(pattern.text) == null) {
        DialogNote(stringResource(R.string.rows_snippet_pattern_slow_info))
    } else {
        DialogNote(stringResource(R.string.rows_snippet_pattern_body))
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.rows_snippet_words_label),
        style = MaterialTheme.typography.bodyMedium,
    )
    ChoiceControl(
        options = (1..SnippetMatcher.MAX_WORDS).map { it to it.toString() },
        selected = words,
        onChange = onWordsChange,
    )
    DialogNote(stringResource(R.string.rows_snippet_words_body))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = sample,
        onValueChange = { sample = it },
        label = { Text(stringResource(R.string.rows_snippet_test_label)) },
        singleLine = true,
    )
    val previewContext = SNIPPET_PREVIEW_CONTEXT
    // The expansion, and whether the pattern had to be stopped for taking too
    // long. The keyboard would go quiet about the second; this is the one place
    // it can be found out safely, since a stopped pattern here costs a moment
    // in the settings app rather than a frame of typing.
    val attempt = remember(pattern.text, text, words, sample, previewContext) {
        if (sample.isBlank() || fault != null) {
            null
        } else {
            val index = SnippetIndex.of(
                listOf(
                    Snippet(
                        id = 1,
                        label = "",
                        text = text,
                        triggerPattern = pattern.text.trim(),
                        triggerWords = words,
                    ),
                ),
            )
            val hit = index.matchPattern(sample, atFieldStart = true, context = previewContext)
            hit to index.stopped().isNotEmpty()
        }
    }
    val hit = attempt?.first
    when {
        attempt == null -> DialogNote(stringResource(R.string.rows_snippet_test_body))
        attempt.second -> DialogNote(
            stringResource(R.string.rows_snippet_pattern_stopped_error),
            color = MaterialTheme.colorScheme.error,
        )
        hit != null -> Text(
            stringResource(R.string.rows_snippet_test_result_label, hit.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> DialogNote(stringResource(R.string.rows_snippet_test_no_match_label))
    }
}

/** Chips that write the pieces of a pattern nobody wants to type by hand. */
private val PATTERN_PIECES = listOf("(.+)", "$1", "^", "$")

/** [piece] written in at the caret, with the caret left after it. */
private fun TextFieldValue.insert(piece: String): TextFieldValue {
    val at = selection.end.coerceIn(0, text.length)
    return TextFieldValue(
        text = text.substring(0, at) + piece + text.substring(at),
        selection = TextRange(at + piece.length),
    )
}

// ---- rows & bars ----

@StringRes
private fun barRowTitle(row: BarRow): Int = when (row) {
    BarRow.TOPBAR -> R.string.rows_bar_topbar_title
    BarRow.EMOJI -> R.string.rows_bar_emoji_title
    BarRow.SYMBOL -> R.string.rows_symbol_row_title
}

@StringRes
private fun barRowSubtitle(row: BarRow, settings: KeyboardSettings): Int = when (row) {
    BarRow.TOPBAR -> R.string.rows_bar_topbar_subtitle
    BarRow.EMOJI -> when (settings.emojiBarMode) {
        EmojiBarMode.OFF -> R.string.rows_bar_emoji_off_subtitle
        EmojiBarMode.BUTTON -> R.string.rows_bar_emoji_button_subtitle
        EmojiBarMode.ALWAYS -> R.string.rows_bar_emoji_always_subtitle
    }
    BarRow.SYMBOL -> if (settings.symbolRowEnabled) {
        CommonR.string.common_on
    } else {
        CommonR.string.common_off
    }
}

/** Row layout above the keys: symbol row, row order and symbol sets. */
@Composable
private fun RowsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.rows_symbol_row_title)) {
        item {
            ToggleSetting(
                R.string.rows_symbol_row_title,
                stringResource(R.string.rows_symbol_row_subtitle),
                settings.symbolRowEnabled,
                info = stringResource(R.string.rows_symbol_row_info),
                default = SettingsDefaults.symbolRowEnabled,
            ) { scope.launch { repository.setSymbolRowEnabled(it) } }
        }
        if (settings.symbolRowEnabled) {
            item {
                val dpFormat = stringResource(R.string.typing_value_dp)
                SliderSetting(
                    R.string.rows_symbol_row_height_title,
                    subtitle = stringResource(R.string.rows_symbol_row_height_subtitle),
                    value = settings.rows.symbolRowHeightDp.toFloat(),
                    range = SymbolRowHeightRange.first.toFloat()..SymbolRowHeightRange.last.toFloat(),
                    display = { dpFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.rows_symbol_row_height_info),
                    default = SettingsDefaults.rows.symbolRowHeightDp.toFloat(),
                ) { scope.launch { repository.setSymbolRowHeightDp(it.roundToInt()) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.rows_row_order_title)) {
        val order = settings.barOrder
        order.forEachIndexed { index, row ->
            item {
                WmRow(
                    title = stringResource(barRowTitle(row)),
                    subtitle = stringResource(barRowSubtitle(row, settings)),
                    trailing = {
                        if (row != BarRow.TOPBAR) {
                            Row {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val next = order.toMutableList()
                                        next[index] = next[index - 1].also { next[index - 1] = next[index] }
                                        scope.launch { repository.setBarOrder(next) }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowUpward,
                                        contentDescription = stringResource(R.string.rows_move_up_desc),
                                    )
                                }
                                IconButton(
                                    enabled = index < order.lastIndex,
                                    onClick = {
                                        val next = order.toMutableList()
                                        next[index] = next[index + 1].also { next[index + 1] = next[index] }
                                        scope.launch { repository.setBarOrder(next) }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowDownward,
                                        contentDescription = stringResource(R.string.rows_move_down_desc),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
        // Reordering is two arrow buttons per row, so getting back to the
        // shipped order by hand is a guessing game once the rows have been
        // shuffled twice.
        if (order != SettingsDefaults.barOrder) {
            item {
                ActionRow(
                    title = R.string.rows_reset_order_title,
                    subtitle = stringResource(R.string.rows_reset_order_subtitle),
                    action = stringResource(CommonR.string.common_reset),
                ) { scope.launch { repository.setBarOrder(SettingsDefaults.barOrder) } }
            }
        }
    }
    CaptionText(stringResource(R.string.rows_row_order_caption))
    SettingsGroup(stringResource(R.string.rows_symbol_sets_title)) {
        val allSets = resolveSymbolSets(settings.customSymbolSets)
        for (set in allSets) {
            item {
                val enabled = set.id in settings.symbolRowSetIds
                val edited = settings.customSymbolSets.any { it.id == set.id }
                val builtIn = BuiltInSymbolSets.byId(set.id) != null
                // A shipped set the user has not renamed draws its translated
                // name; anything the user named draws that name as typed.
                val shippedNameRes = BuiltInSymbolSets.nameRes(set)
                val setName = if (shippedNameRes != null) {
                    stringResource(shippedNameRes)
                } else {
                    set.name
                }
                WmRow(
                    title = setName,
                    supporting = {
                        Text(
                            set.chars.take(8).joinToString(" ") + if (set.chars.size > 8) " …" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leading = {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { on ->
                                val next = if (on) {
                                    settings.symbolRowSetIds + set.id
                                } else {
                                    settings.symbolRowSetIds - set.id
                                }
                                // At least one set stays enabled — an empty row
                                // would have nothing to show.
                                if (next.isNotEmpty()) {
                                    scope.launch { repository.setSymbolRowSetIds(next) }
                                }
                            },
                        )
                    },
                    // Every set is editable now, built-ins included: editing
                    // one stores an override under the same id, so modes that
                    // reference it keep working and "Reset" brings it back.
                    trailing = {
                        IconButton(onClick = { onNavigate("symbol_set_edit/${set.id}") }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    if (builtIn && !edited) {
                                        R.string.rows_symbol_set_edit_builtin_desc
                                    } else {
                                        R.string.rows_symbol_set_edit_desc
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.rows_symbol_set_new_title),
                subtitle = stringResource(R.string.rows_symbol_set_new_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    onNavigate("symbol_set_edit/custom_${System.currentTimeMillis()}")
                },
            )
        }
    }
    CaptionText(stringResource(R.string.rows_symbol_sets_caption))
}

/**
 * Create or edit one symbol set, built-ins included. Editing a built-in
 * saves an override stored under the same id, so anything referencing that
 * id (a mode's pinned sets, the row's active set) keeps pointing at it and
 * "Reset" simply drops the override to bring the shipped set back.
 */
@Composable
private fun SymbolSetEditor(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    setId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.customSymbolSets.firstOrNull { it.id == setId }
    val builtIn = BuiltInSymbolSets.byId(setId)
    val existing = override ?: builtIn
    var name by remember(setId) { mutableStateOf(existing?.name.orEmpty()) }
    var charsText by remember(setId) { mutableStateOf(existing?.chars?.joinToString(" ").orEmpty()) }
    if (builtIn != null) {
        // The stored English name is what a shipped set is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        val shippedNameRes = BuiltInSymbolSets.nameRes(builtIn)
        val shippedName = if (shippedNameRes != null) {
            stringResource(shippedNameRes)
        } else {
            builtIn.name
        }
        CaptionText(stringResource(R.string.rows_symbol_set_builtin_caption, shippedName))
    }
    val defaultSetName = stringResource(R.string.rows_symbol_set_default_name)
    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rows_symbol_set_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            OutlinedTextField(
                value = charsText,
                onValueChange = { charsText = it },
                label = { Text(stringResource(R.string.rows_symbol_set_chars_label)) },
                supportingText = {
                    Text(stringResource(R.string.rows_symbol_set_chars_hint))
                },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Only an existing stored set can be removed — and for a built-in
        // that removal is a reset, not a delete.
        if (override != null) {
            TextButton(onClick = {
                scope.launch {
                    repository.deleteSymbolSet(setId)
                }
                onDone()
            }) {
                Icon(
                    if (builtIn != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (builtIn != null) {
                            R.string.rows_symbol_set_reset_action
                        } else {
                            R.string.rows_symbol_set_delete_action
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            enabled = charsText.isNotBlank(),
            onClick = {
                val chars = charsText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                scope.launch {
                    repository.upsertSymbolSet(
                        SymbolSet(
                            setId,
                            name.trim().ifEmpty { builtIn?.name ?: defaultSetName },
                            chars,
                        ),
                    )
                    // A new set should show up in the row right away.
                    if (setId !in settings.symbolRowSetIds) {
                        repository.setSymbolRowSetIds(settings.symbolRowSetIds + setId)
                    }
                }
                onDone()
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
}

// ---- AI actions ----

/**
 * The buttons on the AI panel: reorder them, turn them off, edit one, or write
 * a new one.
 *
 * A shipped action is never deleted. Editing one stores a spec under the same
 * id that shadows it, so "Reset" drops that spec and the shipped version comes
 * back; turning one off only takes it off the panel.
 */
@Composable
private fun AiActionsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ordered = orderedAiActions(settings.ai.customActions, settings.ai.actionOrder)
    val hidden = settings.ai.hiddenActions.toSet()
    val visibleCount = ordered.count { it.id !in hidden }

    // ReorderSetting takes a plain (T) -> String, which cannot resolve a string
    // resource, so the shipped names are looked up here first.
    val names = ordered.associate { it.id to aiActionName(it) }

    CaptionText(stringResource(R.string.toolai_ai_actions_caption))
    ReorderSetting(
        title = stringResource(R.string.toolai_ai_actions_reorder_title),
        dialogTitle = stringResource(R.string.toolai_ai_actions_reorder_title),
        items = ordered,
        label = { names[it.id].orEmpty() },
        onReordered = { next -> scope.launch { repository.setAiActionOrder(next.map { it.id }) } },
    )
    SettingsGroup {
        for (action in ordered) {
            item {
                val on = action.id !in hidden
                WmRow(
                    title = names[action.id].orEmpty(),
                    supporting = {
                        Text(
                            aiActionSummary(action),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leading = {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                // The panel needs at least one button, or it is
                                // an empty box with no way back to a full one.
                                if (checked || visibleCount > 1) {
                                    scope.launch {
                                        repository.setAiActionHidden(action.id, !checked)
                                    }
                                }
                            },
                        )
                    },
                    trailing = {
                        IconButton(onClick = { onNavigate("ai_action_edit/${action.id}") }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    R.string.toolai_ai_action_edit_desc,
                                ),
                            )
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.toolai_ai_action_new_title),
                subtitle = stringResource(R.string.toolai_ai_action_new_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    val id = BuiltInAiActions.CUSTOM_PREFIX + System.currentTimeMillis()
                    onNavigate("ai_action_edit/$id")
                },
            )
        }
    }
}

/** A shipped action's translated name, or the name the user gave it. */
@Composable
private fun aiActionName(spec: AiActionSpec): String =
    BuiltInAiActions.labelRes(spec)?.let { stringResource(it) } ?: spec.name

/** The one-line recap under an action's name on the list screen. */
@Composable
private fun aiActionSummary(spec: AiActionSpec): String = when {
    // With a prefill there is a prompt worth showing, even though the action
    // asks each run: it is what the instruction box will open with.
    spec.askEachRun && spec.prefillPrompt && spec.task.isNotBlank() -> spec.task
    spec.askEachRun -> stringResource(R.string.toolai_ai_action_ask_summary)
    spec.task.isBlank() -> stringResource(R.string.toolai_ai_action_no_prompt_summary)
    else -> spec.task
}

/**
 * Write a new action, or edit one. This is also where a prompt is written: the
 * field fills the screen rather than the four lines it used to get, because a
 * prompt is a paragraph and scrolling one through four lines is unusable.
 */
@Composable
private fun AiActionEditor(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    actionId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.ai.customActions.firstOrNull { it.id == actionId }
    val builtIn = BuiltInAiActions.byId(actionId)
    val existing = override ?: builtIn
    val defaultName = stringResource(R.string.toolai_ai_action_default_name)

    var name by remember(actionId) { mutableStateOf(existing?.name.orEmpty()) }
    var task by remember(actionId) { mutableStateOf(existing?.task.orEmpty()) }
    var rawPrompt by remember(actionId) { mutableStateOf(existing?.rawPrompt ?: false) }
    var outputOnly by remember(actionId) { mutableStateOf(existing?.outputOnly ?: true) }
    var askEachRun by remember(actionId) { mutableStateOf(existing?.askEachRun ?: false) }
    var prefillPrompt by remember(actionId) { mutableStateOf(existing?.prefillPrompt ?: false) }
    var worksWithoutText by remember(actionId) {
        mutableStateOf(existing?.worksWithoutText ?: false)
    }
    var beforeCursor by remember(actionId) {
        mutableStateOf(existing?.inputMode == AiInputMode.BEFORE_CURSOR)
    }
    var append by remember(actionId) {
        mutableStateOf(existing?.insertMode == AiInsertMode.APPEND)
    }

    fun draft() = AiActionSpec(
        id = actionId,
        name = name.trim().ifEmpty { builtIn?.name ?: defaultName },
        task = task,
        rawPrompt = rawPrompt,
        outputOnly = outputOnly,
        inputMode = if (beforeCursor) AiInputMode.BEFORE_CURSOR else AiInputMode.FIELD,
        insertMode = if (append) AiInsertMode.APPEND else AiInsertMode.REPLACE,
        askEachRun = askEachRun,
        prefillPrompt = prefillPrompt,
        worksWithoutText = worksWithoutText,
    )

    // The prompt is stored either as the whole task, or as the text the
    // instruction box opens with. Both are the same field, so it shows for
    // either job and hides only when the action keeps no prompt at all.
    val showPromptField = !askEachRun || prefillPrompt

    if (builtIn != null) {
        // The stored English name is what a shipped action is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        CaptionText(
            stringResource(R.string.toolai_ai_action_builtin_caption, aiActionName(builtIn)),
        )
    }
    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.toolai_ai_action_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (showPromptField) {
            item {
                OutlinedTextField(
                    value = task,
                    onValueChange = { task = it },
                    label = {
                        Text(
                            stringResource(
                                if (askEachRun) {
                                    R.string.toolai_ai_action_prefill_label
                                } else {
                                    R.string.toolai_ai_action_task_label
                                },
                            ),
                        )
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                when {
                                    askEachRun -> R.string.toolai_ai_action_prefill_hint
                                    rawPrompt -> R.string.toolai_ai_action_task_raw_hint
                                    else -> R.string.toolai_ai_action_task_hint
                                },
                                AiPrompts.TRANSLATE_TOKEN,
                            ),
                        )
                    },
                    // No maxLines on purpose: the field grows with the prompt
                    // instead of scrolling a paragraph through four lines.
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.toolai_ai_action_behaviour_title)) {
        item {
            ToggleSetting(
                R.string.toolai_ai_action_ask_title,
                stringResource(R.string.toolai_ai_action_ask_subtitle),
                askEachRun,
            ) { askEachRun = it }
        }
        if (askEachRun) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_prefill_title,
                    stringResource(R.string.toolai_ai_action_prefill_subtitle),
                    prefillPrompt,
                ) { prefillPrompt = it }
            }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_empty_field_title,
                stringResource(R.string.toolai_ai_action_empty_field_subtitle),
                worksWithoutText,
            ) { worksWithoutText = it }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_before_cursor_title,
                stringResource(R.string.toolai_ai_action_before_cursor_subtitle),
                beforeCursor,
            ) { beforeCursor = it }
        }
        item {
            ToggleSetting(
                R.string.toolai_ai_action_append_title,
                stringResource(R.string.toolai_ai_action_append_subtitle),
                append,
            ) { append = it }
        }
        if (!askEachRun) {
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_output_only_title,
                    stringResource(R.string.toolai_ai_action_output_only_subtitle),
                    outputOnly,
                ) { outputOnly = it }
            }
            item {
                ToggleSetting(
                    R.string.toolai_ai_action_raw_title,
                    stringResource(R.string.toolai_ai_action_raw_subtitle),
                    rawPrompt,
                ) { rawPrompt = it }
            }
        }
    }
    if (showPromptField) {
        // The safety wording is part of every prompt and is not editable, so
        // the only way to make it visible is to show the assembled result. An
        // action that asks each run is framed differently, so with a prefill
        // this previews the instruction path rather than the stored-prompt one.
        SettingsGroup(stringResource(R.string.toolai_ai_action_preview_title)) {
            item {
                Text(
                    if (askEachRun) {
                        AiPrompts.customPrompt(
                            AiPrompts.resolvedTask(draft(), settings.ai.translateTo),
                        )
                    } else {
                        AiPrompts.systemPrompt(draft(), settings.ai.translateTo)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        // For a shipped action this is a reset, not a delete: dropping the
        // stored spec brings the shipped one back.
        if (override != null) {
            TextButton(onClick = {
                scope.launch { repository.deleteAiAction(actionId) }
                onDone()
            }) {
                Icon(
                    if (builtIn != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (builtIn != null) {
                            R.string.toolai_ai_action_reset_action
                        } else {
                            R.string.toolai_ai_action_delete_action
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            // An action with no prompt and no way to ask for one would do
            // nothing at all.
            enabled = askEachRun || task.isNotBlank(),
            onClick = {
                scope.launch { repository.upsertAiAction(draft()) }
                onDone()
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
}

// ---- keyboard modes ----

/**
 * One-line recap of a mode's bindings for the list screen.
 *
 * The parts are joined, so nothing here may be re-cased afterwards: the first
 * letter of a translated word is not ours to change. The lower-case field
 * names are their own resources for the same reason.
 */
@Composable
private fun modeBindingsSummary(mode: KeyboardMode): String {
    val resources = LocalContext.current.resources
    val parts = mutableListOf<String>()
    if (mode.apps.isNotEmpty()) {
        parts += resources.getQuantityString(
            R.plurals.rows_mode_bindings_apps, mode.apps.size, mode.apps.size,
        )
    }
    if (mode.fieldKinds.isNotEmpty()) {
        parts += resources.getString(
            R.string.rows_mode_bindings_fields,
            mode.fieldKinds.joinToString(", ") {
                resources.getString(modeFieldLowercaseLabel(it))
            },
        )
    }
    // " + " rather than " · ": with both set, both have to match.
    return if (parts.isEmpty()) {
        resources.getString(R.string.rows_mode_bindings_manual)
    } else {
        resources.getString(R.string.rows_mode_bindings_auto, parts.joinToString(" + "))
    }
}

@Composable
private fun modeFieldLabel(field: ModeField): String = stringResource(
    when (field) {
        ModeField.PASSWORD -> R.string.rows_mode_field_password_label
        ModeField.EMAIL -> R.string.rows_mode_field_email_label
        ModeField.URL -> R.string.rows_mode_field_url_label
        ModeField.NUMBER -> R.string.rows_mode_field_number_label
        ModeField.PHONE -> R.string.rows_mode_field_phone_label
        ModeField.TEXT -> R.string.rows_mode_field_text_label
        ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_label
    },
)

/** The same names, written the way they read inside a sentence. */
@StringRes
private fun modeFieldLowercaseLabel(field: ModeField): Int = when (field) {
    ModeField.PASSWORD -> R.string.rows_mode_field_password_lowercase_label
    ModeField.EMAIL -> R.string.rows_mode_field_email_lowercase_label
    ModeField.URL -> R.string.rows_mode_field_url_lowercase_label
    ModeField.NUMBER -> R.string.rows_mode_field_number_lowercase_label
    ModeField.PHONE -> R.string.rows_mode_field_phone_lowercase_label
    ModeField.TEXT -> R.string.rows_mode_field_text_lowercase_label
    ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_lowercase_label
}

/** Row height inside [ReorderDialog] — fixed, so drags map to index shifts. */
private val ReorderRowHeight = 52.dp

/**
 * Drags a list into the order the user wants. Rows carry a handle on the
 * right; dragging one past the next row's height swaps the two, so the item
 * tracks the finger and the list settles as it goes.
 *
 * The working copy only reaches the caller through [onConfirm] — backing out
 * leaves the stored order alone.
 */
@Composable
internal fun <T> ReorderDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onConfirm: (List<T>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(items) }
    // -1 = nothing being dragged.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ReorderRowHeight.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CaptionText(stringResource(R.string.rows_reorder_caption))
                // Deliberately not a LazyColumn: every row has to stay
                // composed for a drag to swap past it, and these lists are
                // short enough that laying them all out is free.
                working.forEachIndexed { index, item ->
                    val dragging = index == dragIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ReorderRowHeight)
                            // The dragged row rides above its neighbours.
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
                    ) {
                        Text(
                            stringResource(R.string.rows_reorder_position_label, index + 1),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            label(item),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Outlined.DragHandle,
                            contentDescription = stringResource(
                                R.string.rows_reorder_handle_desc, label(item),
                            ),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(28.dp)
                                // Keyed on Unit so a swap mid-drag never
                                // restarts the gesture: slot `index` is fixed
                                // for the life of the row, only the item in it
                                // moves. `dragIndex` is the live position.
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
                                            // Keep the offset relative to the
                                            // row's new home, or the item
                                            // would jump a full row.
                                            dragOffset -= (to - from) * rowPx
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * A "Reorder…" row that opens a [ReorderDialog]. Disabled with a nudge when
 * there is nothing to reorder yet.
 */
@Composable
internal fun <T> ReorderSetting(
    title: String,
    dialogTitle: String,
    items: List<T>,
    label: (T) -> String,
    onReordered: (List<T>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val enabled = items.size > 1
    WmRow(
        title = title,
        subtitle = if (enabled) {
                items.joinToString(" · ", limit = 4) { label(it) }
            } else {
                stringResource(R.string.rows_reorder_empty_subtitle)
            },
        trailing = { Icon(Icons.Outlined.DragHandle, contentDescription = null) },
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        ReorderDialog(
            title = dialogTitle,
            items = items,
            label = label,
            onConfirm = {
                open = false
                onReordered(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** A wrapping row of tool chips, used for a mode's pins and toolbox order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChips(
    tools: List<ToolbarTool>,
    selected: List<ToolbarTool>,
    onToggle: (ToolbarTool) -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tool in tools) {
            FilterChip(
                selected = tool in selected,
                onClick = { onToggle(tool) },
                label = { Text(stringResource(toolTitle(tool)), maxLines = 1) },
            )
        }
    }
}

/** The modes list: tap to edit, plus creating a new mode. */
@Composable
private fun ModesSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    CaptionText(stringResource(R.string.modes_intro_body))
    val deleteModeDesc = stringResource(R.string.modes_delete_action)
    // A mode is a screenful of bindings and overrides that took real effort to
    // set up, and the delete button sits on the row you tap to open it. Both
    // delete paths ask first; the editor's own button does the same below.
    var confirmDelete by remember { mutableStateOf<KeyboardMode?>(null) }
    SettingsGroup {
        item {
            ChoiceSetting(
                R.string.modes_manual_duration_title,
                subtitle = stringResource(R.string.modes_manual_duration_subtitle),
                options = listOf(
                    ManualModeDuration.UNTIL_APP_CHANGES to
                        stringResource(R.string.modes_manual_duration_app_label),
                    ManualModeDuration.UNTIL_CHANGED to
                        stringResource(R.string.modes_manual_duration_changed_label),
                ),
                selected = settings.rows.manualModeDuration,
                info = stringResource(R.string.modes_manual_duration_info),
                default = SettingsDefaults.rows.manualModeDuration,
            ) { scope.launch { repository.setManualModeDuration(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.modes_group_title)) {
        for (mode in settings.keyboardModes) {
            item {
                WmRow(
                    title = mode.name,
                    subtitle = modeBindingsSummary(mode),
                    leading = {
                        Icon(ModeIcons.icon(mode.icon), contentDescription = null)
                    },
                    trailing = {
                        IconButton(onClick = { confirmDelete = mode }) {
                            Icon(Icons.Outlined.Delete, contentDescription = deleteModeDesc)
                        }
                    },
                    onClick = { onNavigate("mode_edit/${mode.id}") },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.modes_new_title),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { onNavigate("mode_edit/mode_custom_${System.currentTimeMillis()}") },
            )
        }
    }
    SettingsGroup(stringResource(R.string.modes_rearrange_group_title)) {
        item {
            ToggleSetting(
                R.string.modes_drag_edits_title,
                stringResource(R.string.modes_drag_edits_subtitle),
                settings.modeToolOrderEdits,
                info = stringResource(R.string.modes_drag_edits_info),
                default = SettingsDefaults.modeToolOrderEdits,
            ) { scope.launch { repository.setModeToolOrderEdits(it) } }
        }
    }
    CaptionText(stringResource(R.string.modes_tool_order_body))
    confirmDelete?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, mode.name)) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { repository.deleteKeyboardMode(mode.id) }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/** Everything one mode overrides, and when it activates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeEditor(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    modeId: String,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Resolved up here: the name lands in stored settings from a plain lambda,
    // which is no place for stringResource().
    val newModeName = stringResource(R.string.modes_new_default_name)
    val unnamedModeName = stringResource(R.string.modes_unnamed_name)
    val mode = settings.keyboardModes.firstOrNull { it.id == modeId }
        ?: KeyboardMode(modeId, newModeName)
    // A brand-new mode is only persisted on its first edit — backing out of
    // an untouched editor leaves nothing behind.
    val save: (KeyboardMode) -> Unit = { scope.launch { repository.upsertKeyboardMode(it) } }
    // Only the shipped modes can be reset — a user-made mode has no default to
    // fall back to. Matched by id so an edited built-in still offers it.
    val builtInDefault = DefaultKeyboardModes.firstOrNull { it.id == modeId }
    var confirmReset by remember { mutableStateOf(false) }
    // Deleting a mode throws away a screenful of bindings and overrides, and
    // the button sits beside the reset one, which does not. Ask first.
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsGroup {
        item {
            TextFieldSetting(
                label = stringResource(R.string.modes_name_label),
                value = mode.name,
                hint = stringResource(R.string.modes_name_hint),
            ) {
                repository.upsertKeyboardMode(
                    mode.copy(name = it.trim().ifEmpty { unnamedModeName }),
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_icon_title),
                subtitle = stringResource(R.string.modes_icon_subtitle),
                leading = {
                    Icon(ModeIcons.icon(mode.icon), contentDescription = null)
                },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                ModeIconPickerDialog(
                    selected = mode.icon,
                    onPick = { id ->
                        pickerOpen = false
                        save(mode.copy(icon = id))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.modes_changes_group_title)) {
        item {
            ChoiceSetting(
                title = R.string.modes_emoji_row_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    EmojiBarMode.OFF to stringResource(CommonR.string.common_off),
                    EmojiBarMode.BUTTON to stringResource(R.string.modes_emoji_row_button_label),
                    EmojiBarMode.ALWAYS to stringResource(R.string.modes_emoji_row_row_label),
                ),
                selected = mode.emojiBarMode,
            ) { save(mode.copy(emojiBarMode = it)) }
        }
        item {
            ChoiceSetting(
                title = R.string.modes_symbol_row_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = mode.symbolRowEnabled,
            ) { save(mode.copy(symbolRowEnabled = it)) }
        }
        // Typing behaviour. A mode dressed the keyboard but never changed what
        // it did to the text, so Coding mode still corrected identifiers into
        // English words — the one thing a mode for a code editor is for.
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocorrect_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.autocorrect,
            ) { save(mode.copy(autocorrect = it)) }
        }
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocapitalize_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.autoCapitalize,
            ) { save(mode.copy(autoCapitalize = it)) }
        }
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_suggestions_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = mode.suggestions,
            ) { save(mode.copy(suggestions = it)) }
        }
        // Only the layouts the user actually has switched on: a mode naming one
        // they have since removed would pin the keyboard to something that
        // cannot be drawn, which applyMode also guards against at read time.
        item {
            val layoutOptions = listOf(
                null to stringResource(R.string.modes_inherit_label),
            ) + settings.enabledLayoutIds.map { id ->
                id to resolveLayout(settings.customLayouts, id).name
            }
            ChoiceSetting(
                title = R.string.modes_layout_title,
                subtitle = stringResource(R.string.modes_layout_subtitle),
                options = layoutOptions,
                selected = mode.layoutId?.takeIf { it in settings.enabledLayoutIds },
                info = stringResource(R.string.modes_layout_info),
            ) { save(mode.copy(layoutId = it)) }
        }
        item {
            var themePickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_theme_title),
                subtitle = mode.themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.modes_theme_inherit_subtitle),
                trailing = {
                    if (mode.themeId != null) {
                        TextButton(onClick = { save(mode.copy(themeId = null)) }) {
                            Text(stringResource(CommonR.string.common_clear))
                        }
                    }
                },
                onClick = { themePickerOpen = true },
            )
            if (themePickerOpen) {
                ModeThemePickerDialog(
                    settings = settings,
                    selectedId = mode.themeId,
                    onPick = { id ->
                        themePickerOpen = false
                        save(mode.copy(themeId = id))
                    },
                    onDismiss = { themePickerOpen = false },
                )
            }
        }
        if (mode.themeId != null) {
            item {
                CaptionText(stringResource(R.string.modes_theme_override_body))
            }
        }
        item {
            ToggleSetting(
                R.string.modes_pinned_tools_title,
                stringResource(R.string.modes_pinned_tools_subtitle),
                mode.toolbarTools != null,
            ) { on ->
                save(
                    mode.copy(
                        // Appending starts from nothing (the user's own pins
                        // are already there); replacing starts from a copy of
                        // the current toolbar to edit down.
                        toolbarTools = if (on) {
                            if (mode.toolbarToolsAppend) emptyList() else settings.toolbarTools
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        val pinned = mode.toolbarTools
        if (pinned != null) {
            item {
                ChoiceSetting(
                    title = R.string.modes_pinned_behaviour_title,
                    subtitle = if (mode.toolbarToolsAppend) {
                        stringResource(R.string.modes_pinned_behaviour_append_subtitle)
                    } else {
                        stringResource(R.string.modes_pinned_behaviour_replace_subtitle)
                    },
                    options = listOf(
                        true to stringResource(R.string.modes_pinned_behaviour_append_label),
                        false to stringResource(R.string.modes_pinned_behaviour_replace_label),
                    ),
                    selected = mode.toolbarToolsAppend,
                ) { append ->
                    // Switching to append: the copied-in global pins would
                    // duplicate what is already on the toolbar, so drop them.
                    save(
                        mode.copy(
                            toolbarToolsAppend = append,
                            toolbarTools = if (append) pinned - settings.toolbarTools.toSet() else pinned,
                        ),
                    )
                }
            }
            item {
                ToolChips(
                    tools = ToolbarTool.entries.filter {
                        it in settings.enabledTools && isSupportedTool(it) &&
                            isUsableTool(it, settings)
                    },
                    selected = pinned,
                ) { tool ->
                    save(
                        mode.copy(
                            toolbarTools = if (tool in pinned) pinned - tool else pinned + tool,
                        ),
                    )
                }
            }
            item {
                // toolTitle() hands back a resource id, and the reorder dialog
                // takes a plain (T) -> String, so the names are resolved here.
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in pinned) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderSetting(
                    title = stringResource(R.string.modes_pinned_order_title),
                    dialogTitle = stringResource(
                        R.string.modes_pinned_order_dialog_title_mode, mode.name,
                    ),
                    items = pinned,
                    label = { toolNames[it].orEmpty() },
                ) { save(mode.copy(toolbarTools = it)) }
            }
        }
        item {
            ToggleSetting(
                R.string.modes_toolbox_order_title,
                stringResource(R.string.modes_toolbox_order_subtitle),
                mode.toolboxOrder != null,
            ) { on ->
                save(mode.copy(toolboxOrder = if (on) emptyList() else null))
            }
        }
        val order = mode.toolboxOrder
        if (order != null) {
            item {
                ToolChips(
                    tools = settings.toolboxOrder.filter {
                        it in settings.enabledTools && isSupportedTool(it) &&
                            isUsableTool(it, settings)
                    },
                    selected = order,
                ) { tool ->
                    save(
                        mode.copy(
                            toolboxOrder = if (tool in order) order - tool else order + tool,
                        ),
                    )
                }
            }
            item {
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in order) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderSetting(
                    title = stringResource(R.string.modes_toolbox_order_reorder_title),
                    dialogTitle = stringResource(
                        R.string.modes_toolbox_order_dialog_title_mode, mode.name,
                    ),
                    items = order,
                    label = { toolNames[it].orEmpty() },
                ) { save(mode.copy(toolboxOrder = it)) }
            }
            item {
                CaptionText(stringResource(R.string.modes_toolbox_order_body))
            }
        }
        item {
            ToggleSetting(
                R.string.modes_symbol_sets_title,
                stringResource(R.string.modes_symbol_sets_subtitle),
                mode.symbolSetIds != null,
            ) { on ->
                save(
                    mode.copy(
                        symbolSetIds = if (on) {
                            settings.symbolRowSetIds.ifEmpty { BuiltInSymbolSets.defaultEnabledIds }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        val modeSets = mode.symbolSetIds
        if (modeSets != null) {
            item {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (set in resolveSymbolSets(settings.customSymbolSets)) {
                        // A shipped set that still carries its shipped name is
                        // drawn from resources; a renamed one keeps the name
                        // the user typed.
                        val setLabel = BuiltInSymbolSets.nameRes(set)
                            ?.let { stringResource(it) } ?: set.name
                        FilterChip(
                            selected = set.id in modeSets,
                            onClick = {
                                val next =
                                    if (set.id in modeSets) modeSets - set.id else modeSets + set.id
                                if (next.isNotEmpty()) save(mode.copy(symbolSetIds = next))
                            },
                            label = { Text(setLabel, maxLines = 1) },
                        )
                    }
                }
            }
            item {
                val setNames = mutableMapOf<String, String>()
                for (set in resolveSymbolSets(settings.customSymbolSets)) {
                    setNames[set.id] = BuiltInSymbolSets.nameRes(set)
                        ?.let { stringResource(it) } ?: set.name
                }
                val setName = { id: String -> setNames[id] ?: id }
                ReorderSetting(
                    title = stringResource(R.string.modes_symbol_set_order_title),
                    dialogTitle = stringResource(
                        R.string.modes_symbol_set_order_dialog_title_mode, mode.name,
                    ),
                    items = modeSets,
                    label = setName,
                ) { save(mode.copy(symbolSetIds = it)) }
            }
            item {
                CaptionText(stringResource(R.string.modes_symbol_set_order_body))
            }
        }
    }
    SettingsGroup(stringResource(R.string.modes_auto_group_title)) {
        item {
            Text(
                stringResource(R.string.modes_field_types_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (field in ModeField.entries) {
                    FilterChip(
                        selected = field in mode.fieldKinds,
                        onClick = {
                            save(
                                mode.copy(
                                    fieldKinds =
                                        if (field in mode.fieldKinds) mode.fieldKinds - field
                                        else mode.fieldKinds + field,
                                ),
                            )
                        },
                        label = { Text(modeFieldLabel(field), maxLines = 1) },
                    )
                }
            }
            if (mode.apps.isNotEmpty() && mode.fieldKinds.isNotEmpty()) {
                CaptionText(stringResource(R.string.modes_auto_both_match_body))
            }
        }
        for (pkg in mode.apps) {
            item {
                val context = LocalContext.current
                val label = remember(pkg) {
                    runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0),
                        ).toString()
                    }.getOrDefault(pkg)
                }
                WmRow(
                    title = label,
                    supporting = if (label != pkg) {
                        { Text(pkg) }
                    } else null,
                    trailing = {
                        IconButton(onClick = { save(mode.copy(apps = mode.apps - pkg)) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.modes_app_remove_desc),
                            )
                        }
                    },
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_add_app_title),
                subtitle = if (mode.fieldKinds.isEmpty()) {
                        stringResource(R.string.modes_add_app_subtitle_any)
                    } else {
                        stringResource(R.string.modes_add_app_subtitle_fields)
                    },
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                AppPickerDialog(
                    exclude = mode.apps,
                    onPick = { pkg ->
                        pickerOpen = false
                        save(mode.copy(apps = mode.apps + pkg))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
    CaptionText(stringResource(R.string.modes_matching_body))
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (builtInDefault != null) {
            TextButton(onClick = { confirmReset = true }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.modes_reset_default_action))
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.modes_delete_action))
        }
    }
    if (confirmReset && builtInDefault != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = {
                Text(stringResource(R.string.modes_reset_confirm_title, builtInDefault.name))
            },
            text = { Text(stringResource(R.string.modes_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { repository.resetKeyboardModeToDefault(modeId) }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, mode.name)) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repository.deleteKeyboardMode(modeId) }
                    onDeleted()
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Picks a mode's icon from [ModeIcons.catalog]. Chips rather than a grid of
 * bare icons: the selected state comes styled and the touch targets land on
 * the same size the rest of the settings use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeIconPickerDialog(
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_icon_picker_title)) },
        text = {
            FlowRow(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((id, vector) in ModeIcons.catalog) {
                    FilterChip(
                        selected = id == selected,
                        onClick = { onPick(id) },
                        label = {
                            Icon(vector, contentDescription = id, modifier = Modifier.size(22.dp))
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** Picks one installed app (launcher activities) for a mode binding. */
@Composable
private fun AppPickerDialog(
    exclude: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val shown = apps.filter { (pkg, label) ->
        pkg !in exclude &&
            (query.isBlank() || label.contains(query, ignoreCase = true) ||
                pkg.contains(query, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_app_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(CommonR.string.common_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.first }) { (pkg, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(pkg) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pkg) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
