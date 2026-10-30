package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.app.share.ShareSheetSettingsScreen
import com.wasimaster.wmkeyboard.app.share.ShareSheetRoute
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.app.storage.StorageCategories
import com.wasimaster.wmkeyboard.app.storage.StorageCategoryScreen
import com.wasimaster.wmkeyboard.app.netlog.NetworkActivityScreen
import com.wasimaster.wmkeyboard.app.statistics.StatisticsScreen
import com.wasimaster.wmkeyboard.app.storage.StorageScreen
import com.wasimaster.wmkeyboard.app.storage.storageRoute
import com.wasimaster.wmkeyboard.app.language.appLanguageSplitCompat
import com.wasimaster.wmkeyboard.app.launcher.LauncherCombos
import com.wasimaster.wmkeyboard.app.launcher.LauncherCombosScreen
import com.wasimaster.wmkeyboard.app.lock.AppLockSecretPrompt
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.app.lock.BiometricAppLock
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.app.lock.LockedRoute
import com.wasimaster.wmkeyboard.app.lock.LockTarget
import com.wasimaster.wmkeyboard.app.lock.AppLockSettingsScreen
import com.wasimaster.wmkeyboard.app.media.MusicApps
import com.wasimaster.wmkeyboard.app.media.MusicAppsScreen
import com.wasimaster.wmkeyboard.app.updates.LocalAppUpdater
import com.wasimaster.wmkeyboard.app.updates.MissingLink
import com.wasimaster.wmkeyboard.app.updates.NewerLinkDialog
import com.wasimaster.wmkeyboard.app.updates.UpdateCard
import com.wasimaster.wmkeyboard.app.updates.UpdatePromptDialog
import com.wasimaster.wmkeyboard.app.updates.UpdatedCard
import com.wasimaster.wmkeyboard.app.updates.rememberAppUpdater
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import com.wasimaster.wmkeyboard.core.ui.WmSlider
import com.wasimaster.wmkeyboard.core.ui.rememberLiveSlider
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import androidx.compose.ui.platform.LocalConfiguration
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.settingsDepthResolved
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.extensionsResolved
import com.wasimaster.wmkeyboard.core.settings.IconSettings
import com.wasimaster.wmkeyboard.core.settings.isTelevision
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowWidthSizeClass
import android.os.Build
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.ime.KeyboardControls
import com.wasimaster.wmkeyboard.ime.ui.LocalIconSet
import com.wasimaster.wmkeyboard.ime.ui.rememberIconSet
import kotlinx.coroutines.Dispatchers
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.layout.AlternateColumnsRange
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.perf.JankMonitor
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.CardDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Warning
import androidx.compose.material3.BottomSheetDefaults
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

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
        /**
         * Intent extra naming one row to scroll to and flash, by the resource
         * name of its title (e.g. "typing_autocorrect_title"). Optional
         * alongside [EXTRA_OPEN_ROUTE]; on its own it names both the row and,
         * through the search index, the screen holding it.
         */
        const val EXTRA_OPEN_SETTING = MainActivityContract.EXTRA_OPEN_SETTING

        /**
         * Intent extra (boolean) that opens the installed version's release
         * notes over whatever screen is showing. The "Updated to" notification
         * sends it; see [com.wasimaster.wmkeyboard.app.updates.PackageReplacedReceiver].
         */
        const val EXTRA_SHOW_RELEASE_NOTES = "com.wasimaster.wmkeyboard.extra.SHOW_RELEASE_NOTES"

        /** Saved-state key of an unanswered [PairLinkDialog][com.wasimaster.wmkeyboard.app.kdeconnect.PairLinkDialog]. */
        private const val STATE_PENDING_PAIR = "pending_pair"

        /** Saved-state key of the release notes being open. */
        private const val STATE_RELEASE_NOTES = "release_notes"
    }

    private lateinit var repository: SettingsRepository

    /** Per-frame jank logging, off unless `log.tag.WMJank` asks for it; see [JankMonitor]. */
    private val jankMonitor = JankMonitor("settings")

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

    /**
     * A settings link this build could not follow, until its dialog is closed.
     * Apart from [pendingNav] because it outlives the navigation: a link to a
     * screen this build has and a row it does not both opens the screen and
     * keeps the dialog up over it.
     */
    private val missingLink = MutableStateFlow<MissingLink?>(null)

    /**
     * A computer's pairing link waiting for the user's yes. A link alone never
     * pairs: any page or app can open one, naming any address (#702).
     */
    private val pendingPair = MutableStateFlow<String?>(null)

    /** Whether the installed version's release notes are open; see [EXTRA_SHOW_RELEASE_NOTES]. */
    private val showReleaseNotes = MutableStateFlow(false)

    /**
     * The app language (#322). On Android 12 and older nothing else applies
     * it, so the base context itself carries the chosen locale; on Android 13+
     * [AppLanguage.wrap] hands the context back as it came. On Play, the
     * SplitCompat call lets the screen read a language split fetched after
     * the process started.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
        appLanguageSplitCompat(this)
    }

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
        // The Google Home consent flow needs its launcher registered before
        // the activity is started; the smart home editor asks for it later.
        com.wasimaster.wmkeyboard.ime.homeassistant.googlehome.GoogleHomeSupport.register(this)
        repository = SettingsRepository(applicationContext)
        appLock = BiometricAppLock(this, repository)
        // The JSON asset layouts back the tail of the language list; the first
        // screen waits for their index (below) so an enabled asset layout
        // resolves to its real language. Only the index: the layouts parse one
        // at a time as screens ask for them (see AssetLayouts). Reading all of
        // them here held the first frame for seconds on a slow phone and kept
        // every one on the heap for the life of the process. Off the main
        // thread all the same, alongside DataStore's own first read.
        lifecycleScope.launch(Dispatchers.Default) {
            AssetLayouts.load(applicationContext.assets)
            assetLayoutsReady.value = true
        }
        // Modes added since this install was first seeded — the settings
        // screen should list them even if the keyboard has not run yet.
        lifecycleScope.launch { repository.seedNewDefaultModes() }
        val nav = navFor(intent)
        pendingNav.value = nav
        // A rotation rebuilds the activity from the same intent. The dialog
        // comes back from the saved state, so one the user already closed
        // stays closed.
        missingLink.value = if (savedInstanceState != null) MissingLink.restore(savedInstanceState) else nav?.missing
        if (savedInstanceState != null) pendingPair.value = savedInstanceState.getString(STATE_PENDING_PAIR)
        // Same rule as the link dialog: a rotation brings it back only if it
        // was still open.
        showReleaseNotes.value = if (savedInstanceState != null) {
            savedInstanceState.getBoolean(STATE_RELEASE_NOTES)
        } else {
            intent.getBooleanExtra(EXTRA_SHOW_RELEASE_NOTES, false)
        }
        setContent {
            val stored = repository.settings
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
            // Null until DataStore's first emission: rendering nothing for a
            // frame beats flashing onboarding at users who finished it. Held,
            // not read: nothing up here recomposes on a settings write, only
            // the rows that show what the write changed (see LiveSettings).
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            val pending by pendingNav.collectAsStateWithLifecycle()
            // Play In-App Updates, bound to this activity's lifecycle and its
            // result launcher. In a non-Play build this is a stub that reports
            // "unsupported" forever and the update UI draws nothing. Published
            // rather than passed down: the card on the home screen and the rows
            // on About are far apart in the tree and must not disagree about
            // the same download.
            val updater = rememberAppUpdater()
            AppTheme(settings) {
                CompositionLocalProvider(
                    LocalAppUpdater provides updater,
                    LocalAppLock provides appLock,
                    LocalOpenSettingLink provides remember { { link: String -> openSettingLink(link) } },
                ) {
                    // From Android 15 the window is edge to edge, the
                    // keyboard's inset reaches the screens, and the pan the
                    // fields below used to rely on lost races with a keyboard
                    // that grows after the field is focused: Trigger words and
                    // Try the rules stayed under the keys (#679). So the
                    // screens stop at the keyboard instead, the window is told
                    // not to pan, and a scrolling list keeps its focused field
                    // in view as it shrinks, which Compose's scroll containers
                    // do for the focused child. A screen that pads itself by the
                    // keyboard already gets nothing twice: the padding here
                    // consumes the inset. Before 15 the inset does not reach a
                    // window that fits the system bars, so the pan stays.
                    val stopAtKeyboard = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                    if (stopAtKeyboard) ResizeForKeyboard()
                    Box(if (stopAtKeyboard) Modifier.imePadding() else Modifier) {
                        SettingsNavHost(
                            repository = repository,
                            settings = settings,
                            pending = pending,
                            onPendingHandled = { pendingNav.value = null },
                        )
                    }
                    // Settings fields rely on the window panning; keep it in
                    // step with a keyboard that changes height under them.
                    RepanOnKeyboardResize()
                    // The app lock's own PIN, password or pattern sheet. Once,
                    // up here, like the system prompt it stands in for.
                    AppLockSecretPrompt(appLock)
                    // Here rather than beside the card on the home screen,
                    // which is the one screen the user may not be on when
                    // the offer arrives.
                    val missing by missingLink.collectAsStateWithLifecycle()
                    val link = missing
                    // A pairing link asks first, and nothing else stacks on it.
                    val pairState by pendingPair.collectAsStateWithLifecycle()
                    val pairLink = pairState
                    val pairCode = pairLink?.let { com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode.parse(it) }
                    val releaseNotes by showReleaseNotes.collectAsStateWithLifecycle()
                    if (releaseNotes) {
                        // Asked for by a tap on "Updated to", so it goes first;
                        // anything else waits for it to close.
                        com.wasimaster.wmkeyboard.app.updates.InstalledReleaseNotesDialog {
                            showReleaseNotes.value = false
                        }
                    } else if (pairLink != null && pairCode != null) {
                        com.wasimaster.wmkeyboard.app.kdeconnect.PairLinkDialog(
                            pairCode,
                            onPair = {
                                pendingPair.value = null
                                pairByLink(pairLink)
                            },
                            onDismiss = { pendingPair.value = null },
                        )
                    } else if (link != null) {
                        // Holds the update offer itself, so the ordinary
                        // prompt waits rather than stacking a second dialog.
                        NewerLinkDialog(link, settings) { missingLink.value = null }
                    } else {
                        UpdatePromptDialog(settings)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_RELEASE_NOTES, false)) showReleaseNotes.value = true
        navFor(intent)?.let { nav ->
            pendingNav.value = nav
            nav.missing?.let { missingLink.value = it }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        missingLink.value?.save(outState)
        pendingPair.value?.let { outState.putString(STATE_PENDING_PAIR, it) }
        outState.putBoolean(STATE_RELEASE_NOTES, showReleaseNotes.value)
    }

    /** Turns KDE Connect on and pairs with the computer [link] names; see KdeConnectHub.pairByCode. */
    private fun pairByLink(link: String) {
        val repository = com.wasimaster.wmkeyboard.core.settings.SettingsRepository(applicationContext)
        lifecycleScope.launch {
            repository.setKdeEnabled(true)
            com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub.attach(applicationContext)
            com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub.applySettings(repository.settings.first().kdeConnect)
            com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub.pairByCode(link)
        }
    }

    override fun onResume() {
        super.onResume()
        jankMonitor.start(window)
    }

    override fun onPause() {
        jankMonitor.stop()
        super.onPause()
    }

    /**
     * The settings search index, built on the first link that names a row.
     *
     * Six hundred entries read out of resources, so it is not built for the
     * links that don't need it — which is nearly all of them. When one does,
     * this is the same index the search screen ranks against, and the same
     * `route#resource_name` keys, so a link addresses exactly the rows search
     * can find and nothing else needs a second table.
     */
    private val searchIndex: List<SettingsSearchEntry> by lazy { settingsSearchIndex(resources) }

    /**
     * What an intent asks for: a `wmkeyboard://` deep link, or one of the
     * extras the keyboard and other apps use to name a screen directly.
     */
    private fun navFor(intent: Intent?): PendingNav? {
        if (intent == null) return null
        // An https /open/ address is one of ours wearing a web page's
        // clothes; unwrap it first and everything below treats it the same.
        val link = WebOpenLink.appLink(intent.data) ?: intent.data?.toString()
        // A computer's pairing code: the devices screen opens, and the phone
        // pairs once the user says yes in PairLinkDialog. The code only pairs
        // with the computer whose certificate it names, but the code itself can
        // come from anyone, so it never pairs on its own.
        if (link != null && com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode.parse(link) != null) {
            pendingPair.value = link
            return PendingNav(route = com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub.DEVICES_ROUTE)
        }
        // Home Assistant's browser sign-in coming back with its code: checked
        // against the state saved when Sign in was pressed, exchanged for
        // tokens, and the tool page shows the result.
        com.wasimaster.wmkeyboard.core.homeassistant.HaAuth.parseCallback(link)?.let { callback ->
            com.wasimaster.wmkeyboard.app.homeassistant.HaSignIn.finish(applicationContext, lifecycleScope, callback)
            return PendingNav(route = "tool/${ToolbarTool.HOME_ASSISTANT.name}")
        }
        // The same for SmartThings, through the hosted /st/ page.
        com.wasimaster.wmkeyboard.core.smarthome.smartthings.SmartThingsAuth.parseCallback(link)?.let { callback ->
            com.wasimaster.wmkeyboard.app.homeassistant.SmartThingsSignIn.finish(applicationContext, lifecycleScope, callback)
            return PendingNav(route = "tool/${ToolbarTool.HOME_ASSISTANT.name}")
        }
        AddonDeepLink.routeFor(link)?.let { return PendingNav(route = it) }
        SettingsDeepLink.parse(link)?.let { target -> return pendingFor(target) }
        // One of ours, naming a screen this build does not have: most likely
        // from a newer version, which the dialog then looks for.
        SettingsDeepLink.unknown(link)?.let { return PendingNav(missing = MissingLink(it.since, row = false)) }
        // The extras are the same two addresses in intent form, for a caller
        // holding an Intent rather than writing a URL — the keyboard's own
        // "open settings", and any app that names the activity explicitly.
        // Read through the very same allowlist rather than trusted, because
        // NavController.navigate throws on a route it does not know, and this
        // activity has just been resumed by somebody else's intent.
        val route = intent.getStringExtra(EXTRA_OPEN_ROUTE)?.takeIf { it.isNotEmpty() }
        val setting = intent.getStringExtra(EXTRA_OPEN_SETTING)?.takeIf { it.isNotEmpty() }
        if (route != null || setting != null) {
            val target = SettingsDeepLink.parse(
                buildString {
                    append(SettingsDeepLink.SCHEME).append("://").append(SettingsDeepLink.SCREEN_HOST)
                    if (route != null) append('/').append(route)
                    if (setting != null) append('?').append(SettingsDeepLink.SETTING_PARAM).append('=').append(setting)
                },
            )
            // A bad route is not silently swapped for the home screen: the
            // caller asked for something this build does not have, and landing
            // them somewhere else would read as the link having worked.
            if (target != null && (route == null || target.route.isNotEmpty())) {
                return pendingFor(target)
            }
        }
        val tool = intent.getStringExtra(EXTRA_OPEN_TOOL)
            ?.let { name -> ToolbarTool.entries.find { it.name == name } }
        return tool?.let { PendingNav(tool = it) }
    }

    /**
     * Opens a settings link from inside the app: a favourite (#898). Goes
     * the way an incoming link does, so a favourite whose row a later version
     * moved still opens. One that names nothing this build has opens nothing:
     * it is the user's own list, and the "needs a newer version" prompt is
     * for links somebody else sent.
     */
    private fun openSettingLink(link: String) {
        val nav = SettingsDeepLink.parse(link)?.let(::pendingFor) ?: return
        if (nav.route != null) pendingNav.value = nav.copy(missing = null)
    }

    /**
     * Fills in what a link left to the index: the screen holding a row it
     * named on its own, and the row's title resource, which is what the flash
     * matches on.
     */
    private fun pendingFor(target: SettingsDeepLink.Target): PendingNav? {
        val scoped = SettingsDeepLink.resolve(target) { searchIndex }
        val group = if (scoped == null) groupTitle(target) else 0
        // A row this build has on another screen is a link that went stale: a
        // favourite pinned before its hub was split, say. It opens where the
        // row is drawn now rather than on the screen it named, so the pin
        // keeps landing on the row (SETTINGS_REDESIGN.md §4). A heading on the
        // named screen still wins, since it answers the link as written.
        val moved = if (scoped == null && group == 0 && target.setting.isNotEmpty()) {
            SettingsDeepLink.resolve(target.copy(route = "")) { searchIndex }
        } else {
            null
        }
        val entry = scoped ?: moved
        // Only a row the index has nowhere, and no heading answers to, is one
        // this version is missing.
        val missing = if (target.setting.isNotEmpty() && entry == null && group == 0) {
            MissingLink(target.since, row = true)
        } else {
            null
        }
        val route = entry?.route?.takeIf { target.route.isEmpty() || moved != null } ?: target.route
        if (route.isEmpty()) return missing?.let { PendingNav(missing = it) }
        return PendingNav(route = route, highlight = entry?.titleRes ?: group, missing = missing)
    }

    /**
     * The title resource [target] names when the search index does not list
     * it, which on a named screen is a group: "Vision" on Accessibility,
     * "Passwords and codes" on Clipboard. Every named group answers to its own
     * title the way a row does, so a link can land on a whole section without
     * the index carrying an entry per heading, which would crowd every search
     * with section names. A name nothing on the screen answers to marks
     * nothing, as an unknown row did before.
     *
     * Only with a screen: the index is what finds the screen for a bare
     * `wmkeyboard://setting/<name>`, and a heading's name alone does not.
     */
    private fun groupTitle(target: SettingsDeepLink.Target): Int {
        if (target.route.isEmpty() || target.setting.isEmpty()) return 0
        // The name is the link's whole point, and SettingsDeepLink has
        // already held it to the shape aapt gives resource names.
        @Suppress("DiscouragedApi")
        return resources.getIdentifier(target.setting, "string", packageName)
    }
}

/**
 * A navigation an incoming intent asked for.
 *
 * Either a [route] or a [tool], never both. [highlight] rides along with a
 * route: the string resource of the one row on the arriving screen that should
 * scroll itself into view and pulse, or 0 for the whole screen. [missing] is a
 * settings link this build could not follow in full, alone or beside the
 * [route] it could.
 */
internal data class PendingNav(
    val route: String? = null,
    val tool: ToolbarTool? = null,
    @StringRes val highlight: Int = 0,
    val missing: MissingLink? = null,
)

@Composable
internal fun AppTheme(settings: KeyboardSettings, content: @Composable () -> Unit) =
    AppTheme(settings.themeMode, settings.dynamicColor, settings.icons, content)

/**
 * [AppTheme] for the settings activity, which holds its settings live: only a
 * write to one of the three things the theme is made of reaches it.
 */
@Composable
internal fun AppTheme(settings: LiveSettings, content: @Composable () -> Unit) =
    AppTheme(
        settings.watch { it.themeMode },
        settings.watch { it.dynamicColor },
        settings.watch { it.icons },
        content,
    )

@Composable
private fun AppTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    icons: IconSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val supportsDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Remembered, and not only to save the forty resource reads a dynamic
    // scheme costs. MaterialTheme hands the scheme down through a *static*
    // composition local, and ColorScheme compares by instance — so a freshly
    // built one, identical to the last, still counted as a change and
    // recomposed every screen under this theme. Flipping any switch in
    // Settings re-emits the settings object and lands here, so that was the
    // whole app re-composing behind each toggle.
    val configuration = LocalConfiguration.current
    val scheme = remember(dark, supportsDynamic, themeMode, context, configuration) {
        val base = when {
            supportsDynamic && dark -> dynamicDarkColorScheme(context)
            supportsDynamic -> dynamicLightColorScheme(context)
            dark -> WmDarkColors
            else -> WmLightColors
        }
        // Every surface role, not the page alone — see [amoled]. Applied to a
        // Monet scheme too: the tones it swaps in are dark enough that their
        // hue is below the threshold the eye resolves, so a wallpaper-coloured
        // scheme keeps its character everywhere the user can actually see it.
        if (themeMode == ThemeMode.AMOLED) base.amoled() else base
    }
    // Every settings surface draws tool icons, so the user's icon set is
    // provided here rather than per screen — the Tools list and the keyboard
    // must not disagree about what a tool looks like.
    val iconSet by rememberIconSet(icons)
    // The type scale is the app's, not Material's — see [WmTypography]. It is
    // scoped to this theme, so it dresses the settings app and the screens the
    // core modules contribute to it, and leaves the keyboard itself alone.
    // On a television the D-pad is the only pointer there is, so the app says
    // where it is pointing (see [TvFocusIndication]). Nothing is provided
    // anywhere else, so a phone keeps the ripple and pays one boolean.
    val television = remember(context) { context.isTelevision() }
    val indication = remember(television, scheme.primary) {
        if (television) arrayOf(LocalIndication provides TvFocusIndication(scheme.primary))
        else emptyArray()
    }
    MaterialTheme(colorScheme = scheme, typography = WmTypography, shapes = WmShapes) {
        CompositionLocalProvider(LocalIconSet provides iconSet, *indication, content = content)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SettingsNavHost(
    repository: SettingsRepository,
    settings: LiveSettings,
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
            // Only ever a seeded step — a screen nobody opened, so there is
            // nothing to pop back to. Everything the jump put on the stack goes
            // with it: the user is stepping up out of a search result, and what
            // they want above them is the tree, not the result they left.
            open = { route ->
                navController.navigate(route) {
                    popUpTo(HomeRoute)
                    launchSingleTop = true
                }
            },
        )
    }
    // Which folds are open, published for every screen: a group reads it by
    // its own key and asks here to change it, so no screen threads the
    // repository through for the sake of one chevron.
    val foldScope = rememberCoroutineScope()
    val folds = remember(settings) {
        AdvancedFolds(settings) { key, open ->
            foldScope.launch { repository.setAdvancedFoldOpen(key, open) }
        }
    }
    // How much of the app is showing (settings depth), resolved once here and
    // read by every group; changed from Home, a page's footer or Accessibility.
    val depthResolved = settings.watch { it.settingsDepthResolved() }
    val hiddenRows = settings.watch { it.appUi.hiddenSettingsRows }
    val depthControl = remember(depthResolved, hiddenRows) {
        DepthControl(depthResolved, hiddenRows) { depth -> foldScope.launch { repository.setSettingsDepth(depth) } }
    }
    // Which feature bundles are on (Extensions), read by Home, Advanced, the
    // Tools list and every picker; a route a switched-off bundle owns is
    // turned towards the Extensions screen before navigate() sees it.
    val extensionsOn = settings.watch { it.extensionsResolved() }
    // A section's icon and name fly from its home row to the heading of the
    // screen it opens, so the two read as one object being opened rather than
    // as a list and an unrelated page. Published for the whole graph here;
    // each destination adds its own scope, and the rows and headings pick both
    // up without being handed anything.
    // Wide enough for the home list and a screen out of it to sit side by side,
    // and past onboarding, which owns the whole window while it runs.
    val twoPane = rememberWideWindow() && settings.watch { it.onboardingDone }
    // Which home row the screen in the detail pane came from, so the list can
    // show where the user is. Held rather than derived from the back stack: the
    // stack's top is the *current* screen, which three steps into Tools is a
    // tool's own page and not the row that opened it.
    var openedFrom by rememberSaveable { mutableStateOf<String?>(null) }
    val topRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // Which screen a janky frame belongs to, for JankMonitor's log. A no-op
    // unless a developer has switched that log on.
    val rootView = LocalView.current
    LaunchedEffect(topRoute) { JankMonitor.screen(rootView, topRoute.orEmpty()) }
    // A shared element is a motion and has no still version, so reduced
    // motion switches the flights off at the source, and so can the user
    // (Accessibility › Settings app › Screen transitions), for a slow phone.
    // Either takes the SharedTransitionLayout away as well rather than only
    // the flights: the layout is a lookahead scope, and it lays the whole tree
    // out twice on every pass whether anything flies or not.
    //
    // Two panes switch the flights off too, for a different reason (a flight
    // needs one end visible and the other not, and here the home row a screen
    // flew from is still on screen beside it), but keep the layout: the window
    // changes width on a fold or a rotation, and that must not rebuild the
    // screens.
    val layoutWanted = settings.watch { it.appUi.screenTransitions && !it.reduceMotion }
    // Decided once per activity. The screens cannot be moved into or out of
    // the layout while composed (Compose crashes measuring a node taken out of
    // a lookahead scope, in LookaheadDelegate.getAlignmentLinesOwner), and
    // rebuilding them in place lost the scroll. So a flip recreates the
    // activity instead: the same path as a rotation, which already brings back
    // the back stack, the scroll and the fields. The settings are loaded
    // before this is first composed, so the new activity starts on the right
    // side and does not flip again.
    val layoutShown = remember { layoutWanted }
    // The keyboard test every screen carries (#412). One for the graph, so the
    // field stays open, text and all, as the user moves between screens.
    val preview = remember { KeyboardPreviewState() }
    val previewWanted = settings.watch { it.appUi.keyboardPreviewButton }
    LaunchedEffect(previewWanted) { if (!previewWanted) preview.open = false }
    // The keyboard came up over these screens with no field to type into:
    // pinned, or summoned by the floating button or the shade. Open the test
    // field as its button would, so the keys have somewhere to type and the
    // screen is padded clear of them rather than hiding its bottom rows.
    // Once per visit, though: the field closes with the keyboard, and a
    // pinned keyboard that an app hides comes straight back, so reopening on
    // every return turned into a loop of the field and the keyboard going up
    // and down that froze the phone's recent apps button (#665). Coming back
    // to the app is a new visit.
    val upWithoutField by KeyboardControls.upOverOwnApp.collectAsStateWithLifecycle()
    var previewAutoOpened by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { previewAutoOpened = false }
    LaunchedEffect(upWithoutField, previewWanted) {
        if (upWithoutField && previewWanted && !previewAutoOpened) {
            previewAutoOpened = true
            preview.open = true
        }
    }
    CloseOrphanedKeyboardPreview(preview)
    val context = LocalContext.current
    LaunchedEffect(layoutWanted) {
        if (layoutWanted != layoutShown) context.findActivity()?.recreate()
    }
    val openChat: () -> Unit = remember(navController) {
        { navController.navigate("ai_chat") { launchSingleTop = true } }
    }
    val navigate: (String) -> Unit = remember(navController) {
        { route -> navController.navigate(routeWithExtensions(route, settings.value.extensionsResolved())) }
    }
    // The rows an update brought (#970), worked out once per launch. Off the
    // main thread: it builds the whole search index.
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.Default) { NewSettings.load(context) } }
    // As a search result opens one: flash the row, then go.
    val openNewSetting: (SettingsSearchEntry) -> Unit = remember(navController) {
        { entry ->
            SettingsHighlight.request(entry.titleRes)
            navController.navigate(routeWithExtensions(entry.route, settings.value.extensionsResolved())) {
                launchSingleTop = true
            }
        }
    }
    val screens: @Composable (SharedTransitionScope?) -> Unit = { shared ->
        val reduceMotion = settings.watch { it.reduceMotion }
        CompositionLocalProvider(
            LocalSettingsNavigate provides navigate,
            LocalSharedTransition provides if (twoPane) null else shared,
            LocalSettingsCrumbTrail provides crumbs,
            LocalAdvancedFolds provides folds,
            LocalSettingsDepth provides depthControl,
            LocalExtensions provides extensionsOn,
            LocalTwoPane provides twoPane,
            // Published here rather than threaded through, so that the small
            // motions deep in a row can be still without every row being
            // handed the settings.
            LocalReduceMotion provides reduceMotion,
            LocalKeyboardPreview provides preview.takeIf { previewWanted },
            // The AI chat button on every screen, when asked for: the frame
            // draws it from this, so no screen has to know about it.
            // Gone with the AI bundle: the button is the chat's door.
            LocalAiChatFab provides openChat.takeIf {
                settings.watch { it.appUi.aiChatButtonEverywhere }
            },
            // About › Documentation › Setting links (#646): how a row hands
            // out a link to itself, read by every row and heading.
            LocalSettingLinks provides rememberSettingLinks(settings.watch { it.appUi.settingLinks }),
            // Favourite screens and rows (#898), behind the button in every
            // top bar, and gone with it.
            LocalSettingsFavourites provides rememberSettingsFavourites(
                settings.watch { it.appUi.settingsFavourites },
                repository,
            ).takeIf { settings.watch { it.appUi.favouritesButton } },
            // "Icons in settings", animated once here for every row and
            // heading, so the screen the switch is on sees its tiles leave.
            LocalIconReveal provides rememberIconReveal(
                shown = settings.watch { it.appUi.rowIcons },
                still = reduceMotion,
            ),
        ) {
            // The bar over every screen that walks through what an update
            // brought (#970), once onboarding is out of the way.
            Box(modifier = Modifier.fillMaxSize()) {
                if (twoPane) {
                    SettingsTwoPane(
                        list = {
                            // One keyboard test button on the window: the list's
                            // while the pane beside it is only the welcome panel,
                            // the open screen's once there is one.
                            CompositionLocalProvider(
                                LocalKeyboardPreview provides
                                    LocalKeyboardPreview.current?.takeIf { topRoute == HomeRoute },
                            ) {
                                HomeScreen(
                                    settings = settings,
                                    selectedRoute = if (topRoute == HomeRoute) null else openedFrom,
                                    onNavigate = { wanted ->
                                        val route = routeWithExtensions(wanted, settings.value.extensionsResolved())
                                        openedFrom = route
                                        // From the list pane the detail always replaces
                                        // what is in it rather than stacking on top:
                                        // the pane beside it *is* the step back, so a
                                        // stack of home rows would be one the user
                                        // never took.
                                        navController.navigate(route) {
                                            popUpTo(HomeRoute)
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                        },
                        detail = {
                            SettingsNavGraph(
                                navController,
                                repository,
                                settings,
                                pending,
                                onPendingHandled,
                            )
                        },
                    )
                } else {
                    SettingsNavGraph(navController, repository, settings, pending, onPendingHandled)
                }
                if (settings.watch { it.onboardingDone }) {
                    NewSettingsBar(openNewSetting, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
    if (layoutShown) {
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) { screens(this) }
    } else {
        Box(modifier = Modifier.fillMaxSize()) { screens(null) }
    }
}

/**
 * Whether this window is wide enough to hold two settings screens at once.
 *
 * Expanded in Material's own terms — 840 dp and up, which is a tablet either
 * way up, a foldable opened, or a free-form window dragged out. Medium windows
 * (a 7" tablet, a phone on its side) stay one screen and lean on the width cap
 * in `WmScreenFrame` instead: 600 dp split in two is two columns too narrow to
 * put a settings row in.
 */
@Composable
private fun rememberWideWindow(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass ==
        WindowWidthSizeClass.EXPANDED

/**
 * Whether the settings app is currently drawn as two panes.
 *
 * Read by the home destination, which has to draw something other than the home
 * list when the list is already on screen in the pane beside it.
 */
internal val LocalTwoPane = compositionLocalOf { false }

/**
 * The home list and the screen opened from it, side by side.
 *
 * The navigation graph is unchanged and still owns every route, the back stack
 * and the deep links — it simply draws into the detail pane, and the home
 * destination inside it becomes a welcome panel (see [HomeWelcome]) because the
 * list it would otherwise draw is in the pane to its left. That is what keeps
 * this to a layout: `popUpTo(HomeRoute)`, the path strip, search and every
 * `wmkeyboard://` link go on working without knowing there are two panes.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SettingsTwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    // The scaffold is used for its arrangement, not its navigation: at this
    // width both panes are always up, and moving between screens is the graph's
    // job. The navigator is here because it is what computes the directive and
    // the value the scaffold needs.
    val navigator = rememberListDetailPaneScaffoldNavigator<Unit>()
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(ListPaneWidth)) { list() }
        },
        detailPane = { AnimatedPane { detail() } },
    )
}

/**
 * How much of a wide window the home list takes.
 *
 * A phone's worth, so the rows in it keep the proportions they were written
 * for, and everything past it goes to the screen the user is actually reading.
 */
private val ListPaneWidth = 360.dp

@Composable
private fun SettingsNavGraph(
    navController: NavHostController,
    repository: SettingsRepository,
    settings: LiveSettings,
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
        if (pending == null || !settings.value.onboardingDone) return@LaunchedEffect
        when {
            !pending.route.isNullOrEmpty() -> {
                // Armed before the navigation, exactly as a search result arms
                // it: the destination's rows read the flash during their very
                // first composition, and a request made after would be a frame
                // too late for the row to scroll itself into view.
                if (pending.highlight != 0) SettingsHighlight.request(pending.highlight)
                // A link to a screen a switched-off bundle owns lands on the
                // Extensions screen with that bundle flashed, not on the page.
                navController.navigate(routeWithExtensions(pending.route, settings.value.extensionsResolved()))
            }
            pending.tool != null -> {
                val target = routeWithExtensions("tool/${pending.tool.name}", settings.value.extensionsResolved())
                if (target != ExtensionsRoute) navController.navigate("tools")
                navController.navigate(target)
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
    val navMs = if (settings.watch { it.reduceMotion }) 0 else NavTransitionMs
    val spec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = navMs,
        easing = NavTransitionEasing,
    )
    val parallax = 3
    // Frozen at first composition: completing onboarding navigates away
    // explicitly, it must not yank the graph out from under the NavHost.
    val onboardingDone = settings.watch { it.onboardingDone }
    val startDestination = remember { if (onboardingDone) "home" else "onboarding" }
    // The graph below is built once, not once per settings write. NavHost
    // keeps its graph in `remember(route, startDestination, builder)`, so the
    // builder must not capture anything a write replaces: it captures the
    // live holder, which is the same instance for the life of the activity,
    // and every destination reads what it shows through that.
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(spec) { it } },
        exitTransition = { slideOutHorizontally(spec) { -it / parallax } },
        popEnterTransition = { slideInHorizontally(spec) { -it / parallax } },
        popExitTransition = { slideOutHorizontally(spec) { it } },
        // A back gesture plays its own pair, which navigation-compose
        // defaults to a shrink and a fade: the same slides here, so going
        // back by gesture reads as the way in played in reverse.
        predictivePopEnterTransition = { slideInHorizontally(spec) { -it / parallax } },
        predictivePopExitTransition = { slideOutHorizontally(spec) { it } },
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
            // Beside the list, "home" is the pane the user has not chosen
            // anything for yet; the list itself is already on screen to the
            // left. On a phone it is the list.
            if (LocalTwoPane.current) {
                HomeWelcome()
            } else {
                HomeScreen(
                    settings = settings,
                    onNavigate = { route ->
                        navController.navigate(routeWithExtensions(route, settings.value.extensionsResolved()))
                    },
                    anim = this,
                )
            }
        }
        composable("search") {
            // The search screen brings its own scaffold rather than going
            // through SettingsScreen, so the two locals a flight needs are
            // published here by hand. Without them a result's glyph is drawn
            // twice — once in the list, once in the heading it opens — with
            // nothing in between.
            CompositionLocalProvider(
                LocalNavAnimatedScope provides this,
                LocalScreenRoute provides "search",
            ) {
                SettingsSearchScreen(
                    settings = settings,
                    onBack = { navController.popBackStack() },
                    onAsk = { question ->
                        com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantSession.pendingQuestion = question
                        navController.navigate("settings_assistant")
                    },
                    onOpen = { result ->
                        // Arm the flash before navigating: the destination's
                        // rows read it during their first composition.
                        SettingsHighlight.request(result.titleRes)
                        // The search screen stays on the back stack, so backing
                        // out of a setting that was not the one lands on the
                        // same results rather than on the home list (#92).
                        navController.navigate(routeWithExtensions(result.route, settings.value.extensionsResolved()))
                    },
                )
            }
        }
        composable("typing") {
            SettingsScreen(
                stringResource(R.string.home_typing_title),
                { navController.popBackStack() },
                route = "typing",
            ) {
                TypingSettings(
                    repository, settings,
                    onNavigate = { navController.navigate(it) },
                    onOpenDictionary = { navController.navigate("dictionary") },
                    onOpenCustomDictionaries = { navController.navigate("customdictionaries") },
                    onOpenBlacklist = { navController.navigate("blacklist") },
                    onOpenHardwareShortcuts = { navController.navigate("hwshortcuts") },
                )
            }
        }
        composable("typing/corrections") {
            SettingsScreen(
                stringResource(R.string.typing_group_corrections_title),
                { navController.popBackStack() },
                route = "typing/corrections",
            ) {
                TypingCorrectionsSettings(
                    repository,
                    settings,
                    onOpenLearnedCorrections = { navController.navigate("learnedcorrections") },
                    onNavigate = { navController.navigate(it) },
                )
            }
        }
        composable("typing/writing") {
            SettingsScreen(
                stringResource(R.string.typing_group_writing_title),
                { navController.popBackStack() },
                route = "typing/writing",
            ) {
                TypingWritingSettings(repository, settings)
            }
        }
        composable("typing/suggestions") {
            SettingsScreen(
                stringResource(R.string.typing_group_suggestions_title),
                { navController.popBackStack() },
                route = "typing/suggestions",
            ) {
                TypingSuggestionsSettings(
                    repository,
                    settings,
                    onOpenDictionary = { navController.navigate("dictionary") },
                    onOpenCustomDictionaries = { navController.navigate("customdictionaries") },
                    onOpenBlacklist = { navController.navigate("blacklist") },
                    onOpenAutopilot = { navController.navigate("typing/autopilot") },
                    onOpenOctopus = { navController.navigate("typing/octopus") },
                )
            }
        }
        composable("typing/autopilot") {
            SettingsScreen(
                stringResource(R.string.typing_group_autopilot_title),
                { navController.popBackStack() },
                route = "typing/autopilot",
            ) {
                TypingAutopilotSettings(repository, settings)
            }
        }
        composable("typing/octopus") {
            SettingsScreen(
                stringResource(R.string.typing_group_octopus_title),
                { navController.popBackStack() },
                route = "typing/octopus",
            ) {
                TypingOctopusSettings(repository, settings)
            }
        }
        composable("typing/chips") {
            SettingsScreen(
                stringResource(R.string.typing_group_smart_chips_title),
                { navController.popBackStack() },
                route = "typing/chips",
            ) {
                TypingSmartChipsSettings(repository, settings)
            }
        }
        composable("typing/codes") {
            SettingsScreen(
                stringResource(R.string.typing_group_otp_title),
                { navController.popBackStack() },
                route = "typing/codes",
            ) {
                TypingCodesSettings(repository, settings)
            }
        }
        composable("typing/gestures") {
            SettingsScreen(
                stringResource(R.string.typing_group_gestures_title),
                { navController.popBackStack() },
                route = "typing/gestures",
            ) {
                TypingGesturesSettings(repository, settings, onNavigate = { navController.navigate(it) })
            }
        }
        composable("typing/gestures/glide") {
            SettingsScreen(
                stringResource(R.string.typing_group_glide_title),
                { navController.popBackStack() },
                route = "typing/gestures/glide",
            ) {
                TypingGlideSettings(repository, settings, onNavigate = { navController.navigate(it) })
            }
        }
        composable("typing/gestures/glide/checkup") {
            SettingsScreen(
                stringResource(R.string.typing_glide_checkup_title),
                { navController.popBackStack() },
                route = "typing/gestures/glide/checkup",
            ) {
                TypingGlideCheckupSettings(repository, settings)
            }
        }
        composable("typing/gestures/spacebar") {
            SettingsScreen(
                stringResource(R.string.typing_page_spacebar_title),
                { navController.popBackStack() },
                route = "typing/gestures/spacebar",
            ) {
                TypingSpacebarSettings(repository, settings)
            }
        }
        composable("typing/gestures/flicks") {
            SettingsScreen(
                stringResource(R.string.typing_page_flicks_title),
                { navController.popBackStack() },
                route = "typing/gestures/flicks",
            ) {
                TypingFlicksSettings(repository, settings)
            }
        }
        composable("typing/hardware") {
            SettingsScreen(
                stringResource(R.string.typing_group_hardware_title),
                { navController.popBackStack() },
                route = "typing/hardware",
            ) {
                TypingHardwareSettings(repository, settings, onOpenHardwareShortcuts = { navController.navigate("hwshortcuts") })
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
        composable("keypress/haptics") {
            SettingsScreen(
                stringResource(R.string.keypress_haptics_page_title),
                { navController.popBackStack() },
                route = "keypress/haptics",
            ) {
                KeyPressHapticsSettings(
                    repository, settings,
                ) { navController.navigate(it) }
            }
        }
        composable("keypress/popup") {
            SettingsScreen(
                stringResource(R.string.keypress_popup_group_title),
                { navController.popBackStack() },
                route = "keypress/popup",
            ) {
                KeyPressPopupSettings(
                    repository, settings,
                )
            }
        }
        composable("keypress/shortcuts") {
            SettingsScreen(
                stringResource(R.string.keypress_shortcuts_group_title),
                { navController.popBackStack() },
                route = "keypress/shortcuts",
            ) {
                KeyPressShortcutsSettings(
                    repository, settings,
                )
            }
        }
        composable("dictionary") {
            SettingsScreen(
                stringResource(R.string.home_screen_dictionary_title),
                { navController.popBackStack() },
                route = "dictionary",
                subtitle = stringResource(R.string.backup_dictionary_info),
            ) {
                DictionarySettings(repository, settings)
            }
        }
        composable("backup/auto") {
            SettingsScreen(
                stringResource(R.string.backup_auto_group_title),
                { navController.popBackStack() },
                route = "backup/auto",
            ) {
                BackupAutoSettings(repository, settings)
            }
        }
        composable("backup/sync") {
            SettingsScreen(
                stringResource(R.string.backup_sync_title),
                { navController.popBackStack() },
                route = "backup/sync",
                subtitle = stringResource(R.string.backup_sync_screen_subtitle),
            ) {
                BackupSyncSettings(repository, settings)
            }
        }
        composable("backup/contents") {
            SettingsScreen(
                stringResource(R.string.backup_files_contents_title),
                { navController.popBackStack() },
                route = "backup/contents",
            ) {
                BackupContentsSettings(repository, settings)
            }
        }
        composable("fonts/{script}") { backStackEntry ->
            val script = backStackEntry.arguments?.getString("script").orEmpty()
            SettingsScreen(
                stringResource(R.string.home_screen_fonts_title),
                { navController.popBackStack() },
                route = "fonts",
            ) {
                FontPickerScreen(repository, settings, script)
            }
        }
        composable("backup") {
            SettingsScreen(
                stringResource(R.string.home_backup_title),
                { navController.popBackStack() },
                route = "backup",
            ) {
                BackupSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("customdictionaries") {
            SettingsScreen(
                stringResource(R.string.home_screen_custom_dictionaries_title),
                { navController.popBackStack() },
                route = "customdictionaries",
                subtitle = stringResource(R.string.customdict_info),
            ) {
                CustomDictionarySettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("emojicategories") {
            SettingsScreen(
                stringResource(R.string.langemoji_emoji_categories_title),
                { navController.popBackStack() },
                route = "emojicategories",
                subtitle = stringResource(R.string.langemoji_emoji_categories_subtitle),
                subtitleMaxLines = 2,
            ) {
                EmojiCategorySettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("emojiadded") {
            SettingsScreen(
                stringResource(R.string.langemoji_emoji_added_title),
                { navController.popBackStack() },
                route = "emojiadded",
                subtitle = stringResource(R.string.langemoji_emoji_added_subtitle),
                subtitleMaxLines = 2,
            ) {
                AddedEmojiSettings(repository, settings)
            }
        }
        composable(
            route = "$EMOJI_ORDER_ROUTE/{category}",
            arguments = listOf(navArgument("category") { type = NavType.StringType }),
        ) { entry ->
            EmojiOrderScreen(
                anim = this,
                repository = repository,
                settings = settings,
                category = entry.arguments?.getString("category").orEmpty(),
                onBack = { navController.popBackStack() },
            )
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
                subtitle = stringResource(R.string.backup_blacklist_info),
            ) {
                BlacklistSettings(repository, settings)
            }
        }
        composable("learnedcorrections") {
            SettingsScreen(
                stringResource(R.string.typing_learned_corrections_title),
                { navController.popBackStack() },
                route = "learnedcorrections",
                subtitle = stringResource(R.string.typing_learned_corrections_subtitle),
            ) {
                LearnedCorrectionsSettings(repository, settings)
            }
        }
        composable(MusicApps.ROUTE) {
            SettingsScreen(
                stringResource(R.string.musicapps_title),
                { navController.popBackStack() },
                route = MusicApps.ROUTE,
            ) {
                MusicAppsScreen(repository, settings)
            }
        }
        composable(LauncherCombos.ROUTE) {
            SettingsScreen(
                stringResource(R.string.launchercombos_title),
                { navController.popBackStack() },
                route = LauncherCombos.ROUTE,
            ) {
                LauncherCombosScreen(repository, settings)
            }
        }
        composable("kdeconnect/devices") {
            SettingsScreen(
                stringResource(R.string.kdeconnect_devices_title),
                { navController.popBackStack() },
                route = "kdeconnect/devices",
                subtitle = stringResource(R.string.kdeconnect_devices_screen_subtitle),
            ) {
                com.wasimaster.wmkeyboard.app.kdeconnect.KdeDevicesScreen(repository, settings)
            }
        }
        composable("send/instances") {
            SettingsScreen(
                stringResource(R.string.send_instances_screen_title),
                { navController.popBackStack() },
                route = "send/instances",
                subtitle = stringResource(R.string.send_instances_screen_subtitle),
            ) {
                com.wasimaster.wmkeyboard.app.send.SendInstancesScreen(repository, settings)
            }
        }
        composable("send/history") {
            SettingsScreen(
                stringResource(R.string.send_history_screen_title),
                { navController.popBackStack() },
                route = "send/history",
                subtitle = stringResource(R.string.send_history_screen_subtitle),
            ) {
                com.wasimaster.wmkeyboard.app.send.SendHistoryScreen(repository, settings)
            }
        }
        composable("cliprules") {
            SettingsScreen(
                stringResource(R.string.clipboard_copy_rules_title),
                { navController.popBackStack() },
                route = "cliprules",
                subtitle = stringResource(R.string.copy_rules_info),
            ) {
                CopyRuleSettings(repository, settings)
            }
        }
        composable("phoneformats") {
            SettingsScreen(
                stringResource(R.string.home_screen_phoneformats_title),
                { navController.popBackStack() },
                route = "phoneformats",
                subtitle = stringResource(R.string.phoneformats_info),
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
        composable("appearance/toolbar") {
            SettingsScreen(
                stringResource(R.string.appearance_toolbar_section_title),
                { navController.popBackStack() },
                route = "appearance/toolbar",
            ) {
                AppearanceToolbarSettings(
                    repository, settings,
                    onNavigate = { navController.navigate(it) },
                )
            }
        }
        composable("appearance/toolbox") {
            SettingsScreen(
                stringResource(R.string.appearance_toolbox_section_title),
                { navController.popBackStack() },
                route = "appearance/toolbox",
            ) {
                AppearanceToolboxSettings(
                    repository, settings,
                )
            }
        }
        composable("layout") {
            SettingsScreen(
                stringResource(R.string.home_layout_title),
                { navController.popBackStack() },
                route = "layout",
            ) {
                LayoutSettings(repository, settings, onNavigate = { navController.navigate(it) })
            }
        }
        composable("layout/size") {
            SettingsScreen(
                stringResource(R.string.layout_size_position_title),
                { navController.popBackStack() },
                route = "layout/size",
            ) {
                LayoutSizeSettings(
                    repository, settings,
                )
            }
        }
        composable("layout/onehanded") {
            SettingsScreen(
                stringResource(R.string.layout_one_handed_group_title),
                { navController.popBackStack() },
                route = "layout/onehanded",
            ) {
                LayoutOneHandedSettings(
                    repository, settings,
                )
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
        // Not in the Play build at all, so nothing can reach it there: see
        // the Themes screen's Gboard button.
        if (!BuildConfig.ENABLE_PLAY_STORE) {
            composable(RBOARD_THEMES_ROUTE) {
                SettingsScreen(
                    stringResource(R.string.rboard_screen_title),
                    { navController.popBackStack() },
                    route = RBOARD_THEMES_ROUTE,
                ) {
                    RboardThemesScreen(repository, settings) { navController.popBackStack() }
                }
            }
        }
        composable("theme_edit/{themeId}") { backStackEntry ->
            val themeId = backStackEntry.arguments?.getString("themeId").orEmpty()
            // The look's own name, not "Edit theme": it is what the gallery
            // card said, and the card's name flies up into it. A theme that is
            // no longer there keeps the generic heading, which is all the
            // screen under it can say anyway.
            SettingsScreen(
                themeEditTitle(settings, themeId)
                    ?: stringResource(R.string.home_screen_theme_edit_title),
                { navController.popBackStack() },
                route = themeEditRoute(themeId),
            ) {
                ThemeEditorScreen(repository, settings, themeId) { route ->
                    navController.navigate(route)
                }
            }
        }
        composable("theme_assets/{themeId}") { backStackEntry ->
            val themeId = backStackEntry.arguments?.getString("themeId").orEmpty()
            SettingsScreen(
                stringResource(R.string.theme_assets_title),
                { navController.popBackStack() },
            ) {
                ThemeAssetGuideScreen(settings, themeId)
            }
        }
        composable(PHOTO_HUB_ROUTE) {
            PhotoServicesScreen(
                anim = this,
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
                anim = this,
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
                    anim = this,
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
                anim = this,
                repository = repository,
                settings = settings,
                themeId = entry.arguments?.getString("theme").orEmpty(),
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(PHOTO_ROTATION_ROUTE) {
            PhotoRotationScreen(
                anim = this,
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
        composable("vocab/packs") {
            SettingsScreen(stringResource(R.string.vocab_packs_title), { navController.popBackStack() }, route = "vocab/packs") {
                VocabPacksScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("vocab/lists") {
            SettingsScreen(stringResource(R.string.vocab_lists_title), { navController.popBackStack() }, route = "vocab/lists") {
                VocabListsScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("vocab/list/{packId}") { backStackEntry ->
            val packId = backStackEntry.arguments?.getString("packId").orEmpty()
            SettingsScreen(
                stringResource(R.string.vocab_list_edit_title),
                { navController.popBackStack() },
                route = vocabListRoute(packId),
            ) {
                VocabListEditorScreen(packId, repository, settings) { route ->
                    if (route == "vocab/lists") navController.popBackStack() else navController.navigate(route)
                }
            }
        }
        composable("vocab/review") {
            SettingsScreen(stringResource(R.string.vocab_review_title), { navController.popBackStack() }, route = "vocab/review") {
                VocabReviewScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("vocab/browse?pack={pack}") { backStackEntry ->
            val pack = backStackEntry.arguments?.getString("pack")?.takeIf { it.isNotEmpty() }
            SettingsScreen(stringResource(R.string.vocab_browse_title), { navController.popBackStack() }, route = "vocab/browse") {
                VocabBrowseScreen(settings, pack) { route -> navController.navigate(route) }
            }
        }
        composable("vocab/word/{packId}/{word}") { backStackEntry ->
            val packId = backStackEntry.arguments?.getString("packId").orEmpty()
            val word = android.net.Uri.decode(backStackEntry.arguments?.getString("word").orEmpty())
            SettingsScreen(word, { navController.popBackStack() }, route = vocabWordRoute(packId, word)) {
                VocabWordScreen(packId, word, settings) { route -> navController.navigate(route) }
            }
        }
        composable("homeassistant/entities") {
            SettingsScreen(stringResource(R.string.ha_entities_title), { navController.popBackStack() }, route = "homeassistant/entities") {
                com.wasimaster.wmkeyboard.app.homeassistant.HaEntitiesScreen(repository, settings)
            }
        }
        composable(MUSIC_HISTORY_ROUTE) {
            SettingsScreen(stringResource(R.string.music_history_title), { navController.popBackStack() }, route = MUSIC_HISTORY_ROUTE) {
                MusicHistoryScreen(settings)
            }
        }
        composable("clock") {
            // A timer's or the stopwatch's notification opens its own section.
            val tab = (LocalContext.current as? android.app.Activity)?.intent
                ?.getStringExtra(com.wasimaster.wmkeyboard.core.clock.ClockNotifications.EXTRA_TAB)
            SettingsScreen(stringResource(R.string.clock_title), { navController.popBackStack() }, route = "clock") {
                ClockScreen(tab) { route -> navController.navigate(route) }
            }
        }
        composable("notes") {
            SettingsScreen(stringResource(R.string.notes_title), { navController.popBackStack() }, route = "notes") {
                NotesListScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("notes/edit/{noteId}") { backStackEntry ->
            // 0 is "a note that does not exist yet", which the Add button opens.
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull() ?: 0L
            SettingsScreen(
                stringResource(if (noteId == 0L) R.string.notes_new_title else R.string.notes_edit_title),
                { navController.popBackStack() },
                route = noteEditRoute(noteId),
            ) {
                NoteEditorScreen(settings, noteId) { navController.popBackStack() }
            }
        }
        composable("notes/tags") {
            SettingsScreen(stringResource(R.string.notes_tags_title), { navController.popBackStack() }, route = "notes/tags") {
                NoteTagsScreen(settings)
            }
        }
        composable("notes/trash") {
            SettingsScreen(stringResource(R.string.notes_trash_title), { navController.popBackStack() }, route = "notes/trash") {
                NotesTrashScreen(settings)
            }
        }
        composable(TODO_ROUTE) {
            SettingsScreen(stringResource(R.string.todo_title), { navController.popBackStack() }, route = TODO_ROUTE) {
                TodoListScreen(repository, settings)
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
        composable(SIGNAL_STICKERS_ROUTE) {
            SettingsScreen(
                stringResource(R.string.import_signal_row_title),
                { navController.popBackStack() },
                route = SIGNAL_STICKERS_ROUTE,
            ) {
                SignalStickersScreen { route -> navController.navigate(route) }
            }
        }
        composable(TELEGRAM_STICKERS_ROUTE) {
            SettingsScreen(
                stringResource(R.string.import_telegram_row_title),
                { navController.popBackStack() },
                route = TELEGRAM_STICKERS_ROUTE,
            ) {
                TelegramStickersScreen(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("telegram_pack/{setName}") { backStackEntry ->
            val setName = backStackEntry.arguments?.getString("setName").orEmpty()
            SettingsScreen(
                stringResource(R.string.import_telegram_pack_title),
                { navController.popBackStack() },
                route = telegramPackRoute(setName),
            ) {
                TelegramPackScreen(
                    setName = setName,
                    settings = settings,
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(WHATSAPP_STICKERS_ROUTE) {
            SettingsScreen(
                stringResource(R.string.import_whatsapp_row_title),
                { navController.popBackStack() },
                route = WHATSAPP_STICKERS_ROUTE,
            ) {
                WhatsAppStickersScreen { route -> navController.navigate(route) }
            }
        }
        composable("signal_pack/{packId}/{packKey}") { backStackEntry ->
            val packId = backStackEntry.arguments?.getString("packId").orEmpty()
            val packKey = backStackEntry.arguments?.getString("packKey").orEmpty()
            SettingsScreen(
                stringResource(R.string.import_signal_pack_title),
                { navController.popBackStack() },
                route = signalPackRoute(packId, packKey),
            ) {
                SignalPackScreen(
                    packId = packId,
                    packKey = packKey,
                    settings = settings,
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() },
                )
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
            SettingsScreen(
                stringResource(R.string.home_screen_plugin_title),
                { navController.popBackStack() },
                route = pluginRoute(pluginId),
            ) {
                PluginDetailScreen(pluginId, onNavigate = { route -> navController.navigate(route) }) {
                    navController.popBackStack()
                }
            }
        }
        composable("plugin_ide") {
            SettingsScreen(
                stringResource(R.string.plugin_ide_projects_title),
                { navController.popBackStack() },
                route = "plugin_ide",
            ) {
                PluginIdeProjectsScreen { route -> navController.navigate(route) }
            }
        }
        // Its own Scaffold rather than SettingsScreen, for the room the code
        // needs; AiChatScreen is the precedent.
        composable("plugin_ide/{draftId}") { entry ->
            PluginIdeScreen(
                draftId = entry.arguments?.getString("draftId").orEmpty(),
                onBack = { navController.popBackStack() },
                reduceMotion = settings.watch { it.reduceMotion },
            )
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
                AddonsScreen(prefill, addonType, repository, settings) { route -> navController.navigate(route) }
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
            SettingsScreen(
                stringResource(R.string.home_screen_sticker_pack_edit_title),
                { navController.popBackStack() },
                route = stickerPackRoute(packId),
            ) {
                StickerPackScreen(packId) { route -> navController.navigate(route) }
            }
        }
        composable("sticker_pack/{packId}/add") { backStackEntry ->
            // The keyboard's add button: the same page, with the photo picker
            // already up, so adding one sticker is a pick and not a hunt.
            val packId = backStackEntry.arguments?.getString("packId").orEmpty()
            SettingsScreen(
                stringResource(R.string.home_screen_sticker_pack_edit_title),
                { navController.popBackStack() },
                route = stickerPackRoute(packId),
            ) {
                StickerPackScreen(packId, openPicker = true) { route -> navController.navigate(route) }
            }
        }
        composable(
            route = "$STICKER_GENERATOR_ROUTE?pack={pack}",
            arguments = listOf(navArgument("pack") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            // Opened from a pack (its id comes along) or from the keyboard's
            // sticker panel, which passes the pack it is showing.
            val packId = backStackEntry.arguments?.getString("pack")?.takeIf { it.isNotEmpty() }
            SettingsScreen(
                stringResource(R.string.stickergen_title),
                { navController.popBackStack() },
                route = STICKER_GENERATOR_ROUTE,
            ) {
                StickerGeneratorScreen(repository, settings, packId) { route -> navController.navigate(route) }
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
        composable(
            route = "keymap_edit/{layoutId}?layer={layer}",
            arguments = listOf(navArgument("layer") { type = NavType.StringType; defaultValue = "" }),
        ) { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId").orEmpty()
            // The tab to open on, from a row of the gallery's layer list.
            val layer = backStackEntry.arguments?.getString("layer")?.takeIf { it.isNotEmpty() }
            SettingsScreen(
                stringResource(R.string.home_screen_layout_edit_title),
                { navController.popBackStack() },
                route = keyLayoutEditRoute(layoutId, layer),
            ) {
                KeyLayoutEditorScreen(repository, settings, layoutId, initialLayer = layer) { route ->
                    navController.navigate(route)
                }
            }
        }
        // Its own Scaffold rather than SettingsScreen, like the plugin editor:
        // the code needs the window's height, and brings its own top bar.
        composable("keymap_json/{layoutId}") { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId").orEmpty()
            KeyLayoutJsonScreen(repository, settings, layoutId) { navController.popBackStack() }
        }
        // The panel layouts (issue #63): the emoji, clipboard and text-editing
        // panels in the layout editor's own controls. The argument is the
        // PanelKind's name; an unknown one falls back to the emoji panel rather
        // than crashing on a stale deep link.
        composable("panel_edit/{panel}") { backStackEntry ->
            val kind = PanelKind.entries.firstOrNull { it.name == backStackEntry.arguments?.getString("panel") }
                ?: PanelKind.EMOJI
            // Named after the panel rather than "Panel layout": the gallery
            // row said "Emoji", and its name flies up into this heading.
            SettingsScreen(
                stringResource(panelTitleRes(kind)),
                { navController.popBackStack() },
                route = panelEditRoute(kind),
            ) {
                PanelLayoutEditorScreen(repository, settings, kind) { route -> navController.navigate(route) }
            }
        }
        composable("panel_json/{panel}") { backStackEntry ->
            val kind = PanelKind.entries.firstOrNull { it.name == backStackEntry.arguments?.getString("panel") }
                ?: PanelKind.EMOJI
            PanelLayoutJsonScreen(repository, settings, kind) { navController.popBackStack() }
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
        // The custom Double Pinyin scheme (#502). Its own Scaffold, like the
        // layout JSON editor: the scheme is a page of text and needs the height.
        composable(DOUBLE_PINYIN_CUSTOM_ROUTE) {
            DoublePinyinSchemeScreen(repository, settings) { navController.popBackStack() }
        }
        // One segment longer than "language/{langId}", so the two patterns
        // cannot match each other's URLs.
        composable("language/{langId}/more") { backStackEntry ->
            // Its own frame rather than SettingsScreen's column: the page is a
            // lazy grid of keyboard cards (see MoreLayoutsScreen).
            MoreLayoutsScreen(
                anim = this,
                langId = backStackEntry.arguments?.getString("langId").orEmpty(),
                repository = repository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
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
        composable("emoji/panel") {
            SettingsScreen(
                stringResource(R.string.langemoji_emoji_panel_title),
                { navController.popBackStack() },
                route = "emoji/panel",
            ) {
                EmojiPanelSettings(
                    repository, settings,
                    onNavigate = { navController.navigate(it) },
                )
            }
        }
        composable("emoji/mixes") {
            SettingsScreen(
                stringResource(R.string.emojimix_row_title),
                { navController.popBackStack() },
                route = "emoji/mixes",
            ) {
                EmojiMixSettings(repository, settings)
            }
        }
        composable("kaomoji") {
            SettingsScreen(
                stringResource(R.string.langemoji_kaomoji_title),
                { navController.popBackStack() },
                route = "kaomoji",
                subtitle = stringResource(R.string.langemoji_kaomoji_subtitle),
                subtitleMaxLines = 2,
            ) {
                KaomojiSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("kaomoji/faces") {
            SettingsScreen(
                stringResource(R.string.langemoji_kaomoji_faces_title),
                { navController.popBackStack() },
                route = "kaomoji/faces",
                subtitle = stringResource(R.string.langemoji_kaomoji_faces_subtitle),
                subtitleMaxLines = 2,
            ) {
                KaomojiFacesScreen(repository)
            }
        }
        composable("kaomoji/tags") {
            SettingsScreen(
                stringResource(R.string.langemoji_kaomoji_tags_title),
                { navController.popBackStack() },
                route = "kaomoji/tags",
                subtitle = stringResource(R.string.langemoji_kaomoji_tags_subtitle),
                subtitleMaxLines = 2,
            ) {
                KaomojiTagsScreen(repository)
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
        composable("clipboard/history") {
            SettingsScreen(
                stringResource(R.string.clipboard_history_title),
                { navController.popBackStack() },
                route = "clipboard/history",
                subtitle = stringResource(R.string.clipboard_page_history_subtitle),
            ) {
                ClipboardHistorySettings(repository, settings)
            }
        }
        composable("clipboard/chips") {
            SettingsScreen(
                stringResource(R.string.clipboard_page_chips_title),
                { navController.popBackStack() },
                route = "clipboard/chips",
            ) {
                ClipboardChipsSettings(repository, settings)
            }
        }
        composable("clipboard/rules") {
            SettingsScreen(
                stringResource(R.string.clipboard_page_rules_title),
                { navController.popBackStack() },
                route = "clipboard/rules",
            ) {
                ClipboardRulesSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("expander") {
            SettingsScreen(
                stringResource(R.string.home_expander_title),
                { navController.popBackStack() },
                route = "expander",
                subtitle = stringResource(R.string.expander_intro_info),
            ) {
                SnippetSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("expander/folder/{folderId}") { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")?.toLongOrNull() ?: 0L
            SettingsScreen(
                stringResource(R.string.expander_folder_title),
                { navController.popBackStack() },
                route = snippetFolderRoute(folderId),
            ) {
                SnippetFolderScreen(
                    folderId,
                    onNavigate = { navController.navigate(it) },
                    onGone = { navController.popBackStack() },
                )
            }
        }
        // The Add button on a folder's page. A route rather than an argument on
        // the editor's own, so the folder a new snippet lands in survives a
        // link, process death and the back stack exactly as the folder id does.
        composable("expander/folder/{folderId}/new") { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId")?.toLongOrNull() ?: 0L
            // Back through the dispatcher, so the editor's unsaved-changes check
            // hears the toolbar arrow as well as the system back (#471).
            val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SettingsScreen(
                stringResource(R.string.rows_snippet_new_title),
                { backDispatcher?.onBackPressed() ?: navController.popBackStack() },
            ) {
                SnippetEditor(settings, 0L, folderId) { navController.popBackStack() }
            }
        }
        composable("expander/edit/{snippetId}") { backStackEntry ->
            // 0 is "a snippet that does not exist yet", which is what the Add
            // button navigates to.
            val snippetId = backStackEntry.arguments?.getString("snippetId")?.toLongOrNull() ?: 0L
            val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            SettingsScreen(
                stringResource(
                    if (snippetId == 0L) R.string.rows_snippet_new_title else R.string.rows_snippet_edit_title,
                ),
                { backDispatcher?.onBackPressed() ?: navController.popBackStack() },
                route = snippetEditRoute(snippetId),
            ) {
                SnippetEditor(settings, snippetId) { navController.popBackStack() }
            }
        }
        composable("ai") {
            SettingsScreen(
                stringResource(R.string.home_ai_title),
                { navController.popBackStack() },
                route = "ai",
            ) {
                AiHubSettings(repository, settings) { route -> navController.navigate(route) }
            }
        }
        composable("tools") {
            var query by rememberSaveable { mutableStateOf("") }
            SettingsScreen(
                stringResource(R.string.home_tools_title),
                { navController.popBackStack() },
                route = "tools",
                pinned = { ToolsSearchField(query) { query = it } },
            ) {
                ToolsSettings(repository, settings, query) { tool -> navController.navigate("tool/${tool.name}") }
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
                val paint = settings.watch { toolAccentPaint(tool, it) }
                SettingsScreen(
                    stringResource(toolTitle(tool)),
                    { navController.popBackStack() },
                    route = toolRoute(tool),
                    icon = { ToolGlyph(tool, paint?.brush) },
                    // The list row's subtitle lands here, under the heading,
                    // rather than as a loose line at the top of the page. It
                    // is the one place the tool is described, so it wraps
                    // rather than ending in an ellipsis.
                    subtitle = stringResource(toolDescription(tool)),
                    subtitleMaxLines = 3,
                    // The heading wears the tool's colour whether or not the
                    // colourful icons are on: it is the only thing naming which
                    // tool this page is.
                    accent = toolAccentColor(tool, settings.watch { it.toolColorOverrides }),
                    iconTile = false,
                    iconInBar = true,
                ) {
                    ToolDetailSettings(repository, settings, tool) { route ->
                        navController.navigate(route)
                    }
                }
            }
        }
        // One provider of a tool's list: its switch, its setup and whatever
        // else it has (models, packs, its own options), off the tool's page.
        composable(PROVIDER_PAGE_ROUTE) { backStackEntry ->
            val tool = backStackEntry.arguments?.getString("tool")?.let(ProviderTool::of)
            val key = backStackEntry.arguments?.getString("key")
            if (tool != null && !key.isNullOrEmpty()) {
                val head = providerPageHead(settings, tool, key)
                SettingsScreen(
                    head.title,
                    { navController.popBackStack() },
                    route = providerRoute(tool, key),
                    icon = { ProviderTile(head.type) },
                    accent = providerHostAccent(tool, settings),
                    iconTile = false,
                    subtitle = head.subtitle,
                    subtitleMaxLines = 3,
                ) {
                    ProviderPage(
                        repository,
                        settings,
                        tool,
                        key,
                        onBack = { navController.popBackStack() },
                    ) { route -> navController.navigate(route) }
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
                    onOpenAppearance = { navController.navigate("appearance") },
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
        composable("network_activity") {
            SettingsScreen(
                stringResource(R.string.netlog_title),
                { navController.popBackStack() },
                route = "network_activity",
            ) {
                NetworkActivityScreen(repository, settings) { navController.navigate(it) }
            }
        }
        composable(AUTOMATION_ROUTE) {
            SettingsScreen(
                stringResource(R.string.automation_actions_title),
                { navController.popBackStack() },
                subtitle = stringResource(R.string.automation_screen_subtitle),
                route = AUTOMATION_ROUTE,
            ) {
                AutomationSettingsScreen(repository)
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
        composable("notifications") {
            SettingsScreen(
                stringResource(R.string.home_notifications_title),
                { navController.popBackStack() },
                route = "notifications",
            ) {
                NotificationSettingsScreen(settings)
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
        composable("servers") {
            SettingsScreen(
                stringResource(R.string.servers_title),
                { navController.popBackStack() },
                route = "servers",
            ) {
                ServersSettingsScreen(repository, settings)
            }
        }
        composable(SelectionMacroRoute) {
            SettingsScreen(
                stringResource(R.string.selection_macros_title),
                { navController.popBackStack() },
                route = SelectionMacroRoute,
            ) {
                SelectionMacroSettingsScreen(repository, settings) { navController.navigate(it) }
            }
        }
        composable(SelectionMacroActionsRoute) {
            SettingsScreen(
                stringResource(R.string.selection_macros_actions_title),
                { navController.popBackStack() },
                route = SelectionMacroActionsRoute,
            ) {
                SelectionMacroActionsScreen(repository, settings) { navController.navigate(it) }
            }
        }
        composable(SelectionMacroAiRoute) {
            SettingsScreen(
                stringResource(R.string.selection_macros_ai_title),
                { navController.popBackStack() },
                route = SelectionMacroAiRoute,
            ) {
                SelectionMacroAiScreen(repository, settings) { navController.navigate(it) }
            }
        }
        composable(SelectionMacroIconsRoute) {
            SettingsScreen(
                stringResource(R.string.selection_macros_icons_title),
                { navController.popBackStack() },
                route = SelectionMacroIconsRoute,
            ) {
                SelectionMacroIconsScreen(repository, settings)
            }
        }
        composable(SelectionMacroZonesRoute) {
            SettingsScreen(
                stringResource(R.string.selection_macros_zones_title),
                { navController.popBackStack() },
                route = SelectionMacroZonesRoute,
            ) {
                SelectionMacroZonesScreen(repository, settings)
            }
        }
        composable("advanced") {
            SettingsScreen(
                stringResource(R.string.home_advanced_title),
                { navController.popBackStack() },
                route = "advanced",
            ) {
                AdvancedSettings { route -> navController.navigate(route) }
            }
        }
        composable(ExtensionsRoute) {
            SettingsScreen(
                stringResource(R.string.extensions_title),
                { navController.popBackStack() },
                route = ExtensionsRoute,
            ) {
                ExtensionsSettings(repository, settings) { extension ->
                    navController.navigate(extensionRoute(extension))
                }
            }
        }
        composable(ExtensionRoutePattern) { backStackEntry ->
            val extension = backStackEntry.arguments?.getString("extension")
                ?.let { name -> Extension.entries.firstOrNull { it.name == name } }
            if (extension != null) {
                // The bundle's own glyph and hue, as a tool page wears its
                // tool's: the heading is the one thing naming which bundle
                // this page is, and the row on the Extensions screen flies it.
                SettingsScreen(
                    stringResource(extensionTitle(extension)),
                    { navController.popBackStack() },
                    route = extensionRoute(extension),
                    icon = {
                        Icon(
                            extensionIcon(extension),
                            contentDescription = null,
                            modifier = Modifier.size(HeaderGlyphSize),
                        )
                    },
                    accent = extensionAccent(extension),
                    subtitle = stringResource(extensionDescription(extension)),
                    subtitleMaxLines = 3,
                ) {
                    ExtensionDetailSettings(repository, settings, extension) { route ->
                        navController.navigate(route)
                    }
                }
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(FloatingButtonRoute) {
            SettingsScreen(
                stringResource(R.string.floating_button_title),
                { navController.popBackStack() },
                route = FloatingButtonRoute,
            ) {
                FloatingButtonSettingsScreen(repository, settings)
            }
        }
        composable(ShareSheetRoute) {
            SettingsScreen(
                stringResource(R.string.share_sheet_title),
                { navController.popBackStack() },
                route = ShareSheetRoute,
            ) {
                ShareSheetSettingsScreen(repository, settings)
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
        composable("rows/symbol") {
            SettingsScreen(
                stringResource(R.string.rows_symbol_row_title),
                { navController.popBackStack() },
                route = "rows/symbol",
            ) {
                SymbolRowSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("ai_actions") {
            SettingsScreen(
                stringResource(R.string.home_screen_ai_actions_title),
                { navController.popBackStack() },
                route = "ai_actions",
            ) {
                AiActionsSettings(repository, settings, onNavigate = { navController.navigate(it) })
            }
        }
        composable("ai_reply_tones") {
            SettingsScreen(
                stringResource(R.string.toolai_reply_tones_title),
                { navController.popBackStack() },
                route = "ai_reply_tones",
            ) {
                AiActionsSettings(
                    repository,
                    settings,
                    onNavigate = { navController.navigate(it) },
                    surface = com.wasimaster.wmkeyboard.core.tools.AiActionSurface.REPLY,
                )
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
        composable("ai_memory") {
            SettingsScreen(
                stringResource(R.string.toolai_memory_screen_title),
                { navController.popBackStack() },
                route = "ai_memory",
            ) {
                AiMemoryScreen(repository, settings)
            }
        }
        // The two chat destinations draw their own scaffold rather than going
        // through SettingsScreen, so they are the only places the fingerprint
        // gate has to be written out by hand. Both answer to the same target:
        // locking the list has to lock a deep link straight into one
        // conversation, or it locks nothing.
        composable("settings_assistant") {
            // Turned off (#830): a link or a back stack that still names the
            // route opens nothing, and the assistant sends nothing.
            if (!settings.watch { com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantGate.offered(it) }) {
                LaunchedEffect(Unit) {
                    // However it was turned off (#877), a run still out ends here.
                    com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantSession.stop()
                    navController.popBackStack()
                }
                return@composable
            }
            LockedRoute("ai_chat", onCancel = { navController.popBackStack() }) {
                com.wasimaster.wmkeyboard.app.assistant.SettingsAssistantScreen(
                    settings = settings,
                    repository = repository,
                    onBack = { navController.popBackStack() },
                    onOpen = { entry ->
                        // As a search result opens: flash the row, then go.
                        SettingsHighlight.request(entry.titleRes)
                        navController.navigate(entry.route)
                    },
                    onOpenAiSettings = { navController.navigate("tool/${ToolbarTool.AI.name}") },
                )
            }
        }
        composable("ai_chat") {
            LockedRoute("ai_chat", onCancel = { navController.popBackStack() }) {
                AiChatListScreen(
                    anim = this,
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
                route = aiActionEditRoute(actionId),
            ) {
                AiActionEditor(repository, settings, actionId) { navController.popBackStack() }
            }
        }
        composable(AI_BUILT_IN_TOOLS_ROUTE) {
            SettingsScreen(
                stringResource(R.string.toolai_ai_builtin_screen_title),
                { navController.popBackStack() },
                route = AI_BUILT_IN_TOOLS_ROUTE,
            ) {
                AiBuiltInToolSettings(repository, settings)
            }
        }
        composable("ai_mcp") {
            SettingsScreen(
                stringResource(R.string.toolai_ai_mcp_screen_title),
                { navController.popBackStack() },
                route = "ai_mcp",
            ) {
                AiMcpSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("ai_mcp_edit/{serverId}") { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            SettingsScreen(
                stringResource(R.string.toolai_ai_mcp_edit_screen_title),
                { navController.popBackStack() },
                route = aiMcpEditRoute(serverId),
            ) {
                AiMcpEditor(repository, settings, serverId) { navController.popBackStack() }
            }
        }
        composable("symbol_set_edit/{setId}") { backStackEntry ->
            val setId = backStackEntry.arguments?.getString("setId").orEmpty()
            SettingsScreen(
                stringResource(R.string.home_screen_symbol_set_edit_title),
                { navController.popBackStack() },
                route = symbolSetEditRoute(setId),
            ) {
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
            // The mode's own name and glyph, both flown up from its row. A
            // mode the Add button has not saved yet has neither, and keeps the
            // generic heading.
            SettingsScreen(
                settings.watch { modeEditTitle(it, modeId) }
                    ?: stringResource(R.string.home_screen_mode_edit_title),
                { navController.popBackStack() },
                route = modeEditRoute(modeId),
                icon = modeEditIcon(settings, modeId),
                iconTile = false,
            ) {
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
                    settings = settings,
                    repository = repository,
                    persona = settings.watch { it.onboarding },
                    onOpenLicenses = { navController.navigate("licenses") },
                    onOpenSupporters = { navController.navigate("supporters") },
                    onOpenLicenseText = { navController.navigate("license_text/$it") },
                    onOpenDebugLog = { navController.navigate("debug_log") },
                    onOpenStorage = { navController.navigate("storage") },
                    onOpenImportHub = { navController.navigate("import_hub") },
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
        composable("import_hub") {
            SettingsScreen(
                stringResource(R.string.import_hub_title),
                { navController.popBackStack() },
                route = "import_hub",
            ) {
                ImportHubScreen { navController.navigate(it) }
            }
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
                StatisticsScreen(
                    repository,
                    settings,
                    onOpenLearnedCorrections = { navController.navigate("learnedcorrections") },
                )
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
        composable("supporters") {
            SettingsScreen(
                stringResource(R.string.about_supporters_title),
                { navController.popBackStack() },
                route = "supporters",
            ) {
                SupportersScreen()
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
private fun HomeScreen(
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    /**
     * The destination's own animation scope, or null in the list pane of the
     * two-pane layout, where the home list is not a destination being pushed
     * and has no entrance to stay out of the way of.
     */
    anim: AnimatedVisibilityScope? = null,
    /**
     * The home row whose screen is showing in the pane beside this one. Null on
     * a phone, where nothing is beside it, and on a wide window sitting on the
     * welcome panel.
     */
    selectedRoute: String? = null,
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
            if (!settings.value.reduceMotion) confetti = true
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
            anim = anim,
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
            // nothing at all unless there is an update to act on.
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Shown once, in the first launch after this app installed an
                // update of itself. On Android 12 and up that install can
                // finish without a screen of its own, so this is the only sign
                // the user gets that it happened at all.
                UpdatedCard()
                UpdateCard(settings)
            }
            // The word of the day, when the vocabulary tool is on and asked for
            // it. The card owns its own gap and padding: put away for the day,
            // it takes no room at all.
            if (settings.watch { ToolbarTool.VOCABULARY in it.enabledTools && it.vocabulary.wordOfTheDayCard }) {
                VocabDailyCard(settings, onNavigate)
            }
            // One list drives this screen and the search index's root entries,
            // so a row cannot exist on one and not the other.
            val depthControl = LocalSettingsDepth.current
            val depth = depthControl.depth
            // A row a switched-off extension owns is not on the list at all;
            // the Extensions row is where it comes back.
            val entries = visibleRootEntries(LocalExtensions.current)
            for (group in RootGroup.entries) {
                val rows = entries.filter { it.group == group && depth.shows(it.level) }
                if (rows.isEmpty()) continue
                SettingsGroup(stringResource(group.title)) {
                    for (row in rows) {
                        item {
                            HomeItem(
                                row.route, row.icon,
                                stringResource(row.title),
                                // Named from what is actually enabled: there is
                                // no one right answer to hard-code here.
                                if (row.route == "languages") enabledLanguagesSummary(settings)
                                else stringResource(row.subtitle),
                                onNavigate,
                                selected = row.route == selectedRoute,
                            )
                        }
                    }
                }
            }
            // The rows above the depth, in one closed group at the end: still
            // one press away, and named on the fold so they can be found.
            val tucked = entries.filter { !depth.shows(it.level) }
            // Pointed at only when asked (About › App); otherwise not there.
            if (tucked.isNotEmpty() && depthControl.hiddenRows) {
                SettingsGroup(
                    stringResource(R.string.home_group_more_title),
                    foldKey = "more",
                    foldSummary = { tucked.map { stringResource(it.title) }.joinToString(", ") },
                ) {
                    for (row in tucked) {
                        item {
                            HomeItem(
                                row.route, row.icon,
                                stringResource(row.title), stringResource(row.subtitle),
                                onNavigate,
                                selected = row.route == selectedRoute,
                            )
                        }
                    }
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
internal val ActiveGreen = Color(0xFF43A047)

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
/**
 * What the detail pane holds before the user has opened anything.
 *
 * No bar of its own: a heading here would be the second one on screen saying
 * the same thing, under the list's. What it needs to do is say that the panel
 * is waiting rather than broken, which is the icon and one line.
 */
@Composable
private fun HomeWelcome() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIconBadge()
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.home_welcome_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

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
                    // Some TV builds ship no screen for this action, and an
                    // unguarded call crashes the app on the first button of
                    // setup. The top of Settings is the nearest place left.
                    if (runCatching {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }.isFailure
                    ) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
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
/** The destinations a beginner should never meet on day one, one press away. */
@Composable
internal fun AdvancedSettings(onNavigate: (String) -> Unit) {
    val extensions = LocalExtensions.current
    SettingsGroup {
        // The far-fetched features, on or off as one thing (SETTINGS_REDESIGN.md
        // §3). First: Keyboard modes below is one of its bundles, and leaves
        // when switched off.
        item {
            NavRow(R.string.extensions_title, stringResource(R.string.extensions_subtitle), route = ExtensionsRoute) {
                onNavigate(ExtensionsRoute)
            }
        }
        item(visible = Extension.MODES in extensions) {
            NavRow(R.string.home_modes_title, stringResource(R.string.home_modes_subtitle), route = "modes") {
                onNavigate("modes")
            }
        }
        item {
            NavRow(R.string.home_addons_title, stringResource(R.string.home_addons_subtitle), route = "addons") {
                onNavigate("addons")
            }
        }
        item {
            NavRow(
                R.string.home_datasaver_title, stringResource(R.string.home_datasaver_subtitle), route = "datasaver",
            ) { onNavigate("datasaver") }
        }
        // Only the F-Droid build reads the server settings, so only it links them.
        item(visible = BuildConfig.ENABLE_FDROID) {
            NavRow(R.string.servers_title, stringResource(R.string.servers_subtitle), route = "servers") {
                onNavigate("servers")
            }
        }
        item {
            NavRow(
                R.string.home_notifications_title,
                stringResource(R.string.home_notifications_subtitle),
                route = "notifications",
            ) { onNavigate("notifications") }
        }
        item {
            NavRow(
                R.string.selection_macros_title,
                stringResource(R.string.selection_macros_subtitle),
                route = SelectionMacroRoute,
            ) { onNavigate(SelectionMacroRoute) }
        }
        item {
            NavRow(
                R.string.floating_button_title,
                stringResource(R.string.floating_button_subtitle),
                route = FloatingButtonRoute,
            ) { onNavigate(FloatingButtonRoute) }
        }
        item {
            NavRow(
                R.string.share_sheet_title,
                stringResource(R.string.share_sheet_subtitle),
                route = ShareSheetRoute,
            ) { onNavigate(ShareSheetRoute) }
        }
    }
}

@Composable
private fun HomeItem(
    route: String,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onNavigate: (String) -> Unit,
    /**
     * Whether this row's screen is the one showing in the detail pane. Only
     * ever true in the two-pane layout: on a phone the screen the row opens is
     * the whole window, and there is nothing to say "you are here" about.
     */
    selected: Boolean = false,
) {
    WmRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = routeAccent(route),
        flightTo = route,
        // The row's own accent at container strength rather than the theme's
        // one selection colour, so the marked row and the heading of the screen
        // it opened are visibly the same section. The group's card clips it to
        // the run's corners, so a selected row at either end of a group keeps
        // the rounding of the slab it is part of.
        modifier = if (!selected) Modifier
        else Modifier.background(routeAccent(route).copy(alpha = SelectedRowTint)),
        onClick = { onNavigate(route) },
    )
}

/**
 * How much of a home row's accent the selected row is washed with.
 *
 * Enough to find without looking for it, and light enough that the row's name
 * keeps `onSurface`'s contrast on top of it in both themes — the accents are
 * mid-tone colours, so anything heavier needs its own foreground colour and a
 * per-accent check that it passes.
 */
private const val SelectedRowTint = 0.18f

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
    subtitleMaxLines: Int = 1,
    /** Held under the bar while the body scrolls; see [WmScreen]'s own. */
    pinned: (@Composable () -> Unit)? = null,
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
        subtitleMaxLines = subtitleMaxLines,
        anim = this,
        pinned = pinned,
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
        val body: @Composable () -> Unit = content
        // Behind the lock, the extension check: a screen a switched-off
        // bundle owns says so and points at the Extensions screen, whichever
        // of the graph's navigate() calls reached it.
        if (route == null) body() else LockedRoute(route, onCancel = onBack) { ExtensionGate(route, body) }
    }
}

/**
 * Collects the rows of one visually grouped card stack — the modern
 * Material settings look: each row sits on its own surface with tiny
 * gaps between rows, large rounded corners at the group's ends and
 * small ones inside.
 */
internal class SettingsGroupScope {
    /**
     * One row of the group.
     *
     * [conditional] is what separates a row that is simply there from one the
     * caller hands a [visible] to. Only the second kind is wrapped in a
     * reveal, so the ordinary group — which is nearly all of them — pays
     * nothing for the machinery.
     */
    class Entry(
        val visible: Boolean,
        val conditional: Boolean,
        /** Who the row is, for a row in a list the user edits; see [item]. */
        val key: Any?,
        /** How far down the settings depth the row sits; see [SettingLevel]. */
        val level: SettingLevel = SettingLevel.BASIC,
        val content: @Composable () -> Unit,
    )

    val items = mutableListOf<Entry>()

    /**
     * A row that is always part of the group.
     *
     * Give a row of a list the user adds to and deletes from — a word, a
     * snippet, a pack — a [key] that names what it shows (its id, its
     * spelling). The row then keeps its own state when the rows above it
     * change, opens into its place when it is added and closes out of it when
     * it goes, instead of the rows under it jumping. See [GroupRows]. A keyed
     * row is still composed for the moment it takes to close, so its body
     * must not depend on the item still being in the list.
     *
     * [level] is how far down the settings depth the row sits. At a depth
     * above it the row is not drawn in its place but behind the group's
     * "more settings" row; see [SettingsDepth.kt]. Untagged rows are basic.
     */
    fun item(key: Any? = null, level: SettingLevel = SettingLevel.BASIC, content: @Composable () -> Unit) {
        items += Entry(visible = true, conditional = false, key = key, level = level, content = content)
    }

    /**
     * A row that belongs to the group but is only showing while [visible].
     *
     * Prefer this over wrapping the `item` call in an `if`. Both draw the same
     * thing, but a row that is never added simply is not there, so the row
     * a switch reveals appears and disappears in a single frame and the rows
     * under it jump. Declared this way it expands into place and pushes them
     * down instead, and the card corners either side of it follow.
     *
     * The body is not composed while the row is hidden, exactly as it is not
     * inside an `if`. It *is* composed while the row is on its way out, so a
     * body that would crash once [visible] has gone false — one that reads
     * through a null check the condition is doing, say — wants the `if` after
     * all.
     */
    fun item(
        visible: Boolean,
        key: Any? = null,
        level: SettingLevel = SettingLevel.BASIC,
        content: @Composable () -> Unit,
    ) {
        items += Entry(visible = visible, conditional = true, key = key, level = level, content = content)
    }
}

// Only for reading the shared-transition local as the reduced-motion switch.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SettingsGroup(
    title: String? = null,
    @StringRes highlightKey: Int = 0,
    /**
     * Makes the group a fold: closed by default, opened by a press on its
     * heading, and remembered per screen so a power user who opened it once
     * finds it open next time. The key is joined to the screen's route, so
     * "advanced" on Typing and "advanced" on Key press are two folds.
     *
     * A search result or an addon's Use button that lands inside a closed
     * fold would flash nothing, so while a highlight is pending the fold is
     * drawn open regardless of what is remembered.
     */
    foldKey: String? = null,
    /** The section's explanation, behind the heading's "?" — see [SectionHeader]. */
    info: String? = null,
    /**
     * What a closed fold holds, named rather than counted: the count alone
     * says a group has thirteen things in it and nothing about whether the
     * one being looked for is among them. Read only while the fold is closed,
     * so a group that is open pays nothing for it — which matters where the
     * names cost a resource lookup each.
     *
     * Ignored without [foldKey]: an unfolded group already shows its rows.
     */
    foldSummary: (@Composable () -> String)? = null,
    /** A control on the heading's right — the pencil that puts a list into reorder mode. */
    action: (@Composable () -> Unit)? = null,
    /**
     * How far down the settings depth the whole group sits. Above its depth
     * a titled group draws closed under its heading, like a fold; one with no
     * heading tucks every row behind its "more settings" row. Rows keep their
     * own [SettingsGroupScope.item] level inside a group that is shown.
     */
    level: SettingLevel = SettingLevel.BASIC,
    builder: SettingsGroupScope.() -> Unit,
) {
    // The builder runs during composition, so rows may be added
    // conditionally on snapshot state (e.g. sliders that appear only
    // while their feature's toggle is on).
    val scope = SettingsGroupScope().apply(builder)
    val route = LocalScreenRoute.current.orEmpty()
    val depthControl = LocalSettingsDepth.current
    val depth = depthControl.depth
    val groupAbove = !depth.shows(level)
    val folds = LocalAdvancedFolds.current
    val highlighted = SettingsHighlight.target != 0 || SettingsHighlight.targetItems.isNotEmpty()
    // A row above the depth is not drawn at all; that is what the depth is
    // for. Two things bring it back: a pending highlight (a search result or
    // an addon's Use button has to land on it, so it is drawn in its place as
    // if the depth allowed it), and About › App › "Show where settings are
    // hidden", which puts a "N more settings" row at the end of the group
    // and folds a whole titled group closed under its heading instead.
    val reveal = highlighted || depthControl.hiddenRows
    if (groupAbove && !reveal) return
    val foldsWhole = groupAbove && title != null && depthControl.hiddenRows
    val tucked: (SettingsGroupScope.Entry) -> Boolean =
        { e -> !foldsWhole && (groupAbove || !depth.shows(e.level)) }
    val tuckedCount = scope.items.count { it.visible && tucked(it) }
    val entries = when {
        tuckedCount == 0 -> scope.items
        // Not pointed at: the hidden rows are simply not there.
        !depthControl.hiddenRows && !highlighted -> scope.items.filterNot { tucked(it) }
        // A highlight pending and nothing to point with: every row in its place.
        !depthControl.hiddenRows -> scope.items
        else -> {
            // Remembered per group like a fold, and drawn open while a
            // highlight is pending so a search result can land inside.
            val moreId = "$route/${foldKey ?: title ?: "group"}#more"
            val moreOpen = highlighted || (folds?.isOpen(moreId) ?: false)
            buildList<SettingsGroupScope.Entry> {
                scope.items.filterNotTo(this) { tucked(it) }
                add(
                    SettingsGroupScope.Entry(visible = true, conditional = false, key = null) {
                        MoreSettingsRow(tuckedCount, moreOpen) { folds?.toggle(moreId, !moreOpen) }
                    },
                )
                // Conditional on the fold, so they grow in under the row
                // rather than appear. Unkeyed on purpose: a keyed row's own
                // arrive-and-leave motion is for a list the user edits.
                for (entry in scope.items) {
                    if (!tucked(entry)) continue
                    add(
                        SettingsGroupScope.Entry(
                            visible = entry.visible && moreOpen,
                            conditional = true,
                            key = null,
                            level = entry.level,
                            content = entry.content,
                        ),
                    )
                }
            }
        }
    }
    // What the group is showing right now, which is what every count below is
    // about: a hidden conditional row is not a row the reader can see, so it
    // is not a slab in the skeleton and not one of the rows a closed fold
    // says it is holding.
    val shown = entries.count { it.visible }
    if (shown == 0) return
    // Below the fold while the screen is still animating in: come back for
    // the rows once the entrance can spare them — see [rememberGroupRevealed].
    // Until then the group holds its place as slabs the size of its rows, so
    // the screen opens at its full height instead of growing under the reader.
    if (!rememberGroupRevealed(shown)) {
        GroupSkeleton(shown, hasTitle = title != null)
        return
    }
    // A group folded by its level is named by its title: two such groups on
    // one screen must not share a remembered state.
    val foldId = (foldKey ?: if (foldsWhole) "level:$title" else null)?.let { "$route/$it" }
    val open = foldId == null || highlighted || (folds?.isOpen(foldId) ?: false)
    // A named group is a scroll target in its own right. Some things the user
    // arrives at from search — or from an addon's Use button — are a whole
    // section rather than one row: "Icon pack", "Your packs", "Installed
    // fonts". Unnamed groups have nothing to match on and stay plain. Coarse,
    // because anything inside it that answers to the same request is a better
    // answer than the section around them.
    HighlightableRow(title, highlightKey, coarse = true) {
        if (foldId != null && title != null) {
            FoldHeader(
                title,
                count = shown,
                open = open,
                info = info,
                summary = if (open) null else foldSummary?.invoke(),
            ) {
                folds?.toggle(foldId, !open)
            }
        } else if (title != null) {
            SectionHeader(title, info = info, action = action, modifier = GroupHeadingPadding, linkable = true)
        }
        val cards: @Composable () -> Unit = {
            // The 3 dp between two cards is drawn by the card below rather
            // than by the column's arrangement (#136): an arrangement puts a
            // gap between every pair of children, including a conditional row
            // that has collapsed to nothing, and the group would keep a sliver
            // of air where a row used to be. Carried by the row, the gap goes
            // away with it.
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Which rows are drawn, and how keyed ones arrive and leave,
                // is GroupRows' business (SettingsGroupMotion.kt); the card
                // each one sits on is this file's.
                GroupRows(entries) { modifier, gapAbove, first, last, content ->
                    Column(modifier = modifier) {
                        if (gapAbove) Spacer(Modifier.height(3.dp))
                        GroupCard(
                            top = if (first) GroupEndCorner else GroupInnerCorner,
                            bottom = if (last) GroupEndCorner else GroupInnerCorner,
                            content = content,
                        )
                    }
                }
            }
        }
        when {
            // Not a fold at all: the rows are simply there, and wrapping them
            // in a visibility that is always true costs a layer on every
            // group on every screen.
            foldId == null -> cards()
            // Reduced motion has no still version of a reveal, so the body
            // is or is not there — the switch [ExpandableCard] already reads,
            // from the same place.
            LocalSharedTransition.current == null -> if (open) cards()
            else -> AnimatedVisibility(
                visible = open,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                cards()
            }
        }
        Spacer(Modifier.height(GroupTail))
    }
}

/** The radius at the two ends of a group's stack of cards. */
private val GroupEndCorner = 24.dp

/** The radius where one card meets the next. */
private val GroupInnerCorner = 6.dp

/**
 * One row's card.
 *
 * The corners animate, because which card is the group's first and which its
 * last changes when a conditional row appears above or below them, and a
 * corner that snapped from 6 dp to 24 dp would undo the reveal that moved it.
 */
@Composable
private fun GroupCard(top: Dp, bottom: Dp, content: @Composable () -> Unit) {
    val topCorner by animateDpAsState(top, label = "groupCardTop")
    val bottomCorner by animateDpAsState(bottom, label = "groupCardBottom")
    Surface(
        shape = RoundedCornerShape(
            topStart = topCorner, topEnd = topCorner,
            bottomStart = bottomCorner, bottomEnd = bottomCorner,
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // No padding of its own: a row brings its own (ListItem's 8 dp, a
        // slider row's 8 dp), and 4 dp on top of that made every row 80 dp
        // where 72 is the height the list is designed around.
        Column { content() }
    }
}

/**
 * What a group leaves under its last card. Most of the gap between two groups
 * lives here rather than above the next heading, so the first group on a screen
 * sits close under the bar's own air — see `ScreenTopGap` — instead of
 * doubling it.
 */
private val GroupTail = 16.dp

/** A group heading's inset: level with the text in the rows under it. */
private val GroupHeadingPadding = Modifier.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)

/**
 * A fold's heading: the title, how many rows it holds, and a chevron. With a
 * [summary] it also names what is inside, which is what makes a fold that
 * starts closed browsable — see `foldSummary` on [SettingsGroup].
 */
@Composable
private fun FoldHeader(
    title: String,
    count: Int,
    open: Boolean,
    info: String? = null,
    summary: String? = null,
    onToggle: () -> Unit,
) {
    val numberFormat = stringResource(R.string.values_number)
    val hold = rememberRowHold()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .settingLinkHold(hold)
            .clickable(onClick = onToggle)
            .then(GroupHeadingPadding),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Two lines and then an ellipsis: this is a hint at what is in the
            // fold, not the list itself. The list is one press away.
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                )
            }
        }
        Text(
            numberFormat.format(count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        HeadingControls {
            if (info != null) InfoButton(title = title, detail = info)
            SettingLinkButton(title)
            ExpandChevron(open)
        }
        RowHoldMenu(hold)
    }
}

/**
 * The lane a heading's controls sit in: as tall as the heading's own line, so
 * a "?" or a chevron does not make the heading a button's height. The touch
 * targets keep their full size and simply overhang the line, which is what
 * lets a heading with a "?" on it be as tall as one without.
 */
@Composable
private fun HeadingControls(content: @Composable RowScope.() -> Unit) {
    Box(modifier = Modifier.height(HeadingControlLane), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.requiredHeight(HeadingControlHitHeight),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A heading's line: what `titleSmall` comes to at the default font scale. */
private val HeadingControlLane = 20.dp

/** The tap height a heading's controls keep while overhanging the lane. */
private val HeadingControlHitHeight = 48.dp

/** How a [StateBanner] reads: a fact, or something the user should fix. */
internal enum class BannerTone { INFO, WARNING }

/**
 * A line of live state that changes what the controls near it do — "auto
 * theme is on, so picking a theme here does nothing" — drawn as a card with
 * the button that resolves it, not as a sentence telling the user where to
 * go. Explanation belongs in a row's "?" or a heading's; this is for the
 * case a subtitle cannot carry, because it is about the screen, not a row.
 */
@Composable
internal fun StateBanner(
    text: String,
    action: String? = null,
    tone: BannerTone = BannerTone.INFO,
    onAction: (() -> Unit)? = null,
) {
    val container = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.secondaryContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                if (tone == BannerTone.WARNING) Icons.Outlined.Warning else Icons.Outlined.Info,
                contentDescription = null,
                tint = content,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/**
 * Reference material that most visits never need — a table of template
 * variables, a syntax note — behind one row that opens it. Collapsed unless
 * asked: a page is for the settings on it, and a block of prose that is
 * always open is a block of prose everyone scrolls past.
 *
 * Drawn as one group card so it sits in the list like everything else; the
 * body is whatever the caller puts in [content], on the card's own surface.
 */
// Only for reading the shared-transition local as the reduced-motion switch.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            WmRow(
                title = title,
                subtitle = subtitle,
                trailing = { ExpandChevron(expanded) },
                onClick = { expanded = !expanded },
            )
            // Reduced motion has no still version of a reveal, so the body
            // simply is or is not there — the same switch the shared elements
            // use, read from the same place.
            if (LocalSharedTransition.current == null) {
                if (expanded) Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
            } else {
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(bottom = 8.dp), content = content)
                }
            }
        }
    }
}

/**
 * The chevron that says "this opens", pointing down closed and up open, and
 * turning between the two while the body it belongs to opens or closes.
 */
// Only for reading the shared-transition local as the reduced-motion switch.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ExpandChevron(expanded: Boolean) {
    val target = if (expanded) 180f else 0f
    val angle = if (LocalSharedTransition.current == null) {
        target
    } else {
        animateFloatAsState(target, label = "expandChevron").value
    }
    Icon(
        Icons.Outlined.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(angle),
    )
}

/** ListItem colors that let the group card's surface show through. */
@Composable
internal fun transparentListColors(): ListItemColors =
    ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * "?" affordance that opens a dialog with the full explanation of a setting.
 * Draws nothing inside a [RowExtras] at the Simple depth, where the row's
 * long press offers the same explanation as "What is this?" (see
 * [RowHoldMenu]); a heading's "?" is outside any RowExtras and stays.
 */
@Composable
internal fun InfoButton(title: String, detail: String) {
    if (LocalRowExtrasBare.current) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(RowExtraSize)) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.home_info_desc, title),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
    if (open) InfoDialog(title, detail) { open = false }
}

/** The explanation [InfoButton] opens: the setting's name over its [detail], and OK. */
@Composable
internal fun InfoDialog(title: String, detail: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_ok))
            }
        },
    )
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

/**
 * A group's heading. [info] is the section's explanation, drawn as the "?"
 * that rows already use rather than as a paragraph under the heading: the
 * explanation is there for whoever wants it and takes no room from anyone
 * who does not.
 *
 * [trailing] is a figure that belongs to the whole group — the total size of
 * the models or files listed under it — drawn muted on the heading's right,
 * level with the right edge of the rows' content. It answers "which of these
 * is the big one" at the group before the rows are read.
 */
@Composable
internal fun SectionHeader(
    text: String,
    info: String? = null,
    // Aligns with the text inside group rows: 16dp group margin plus the
    // rows' own 16dp content inset.
    modifier: Modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    action: (@Composable () -> Unit)? = null,
    trailing: String? = null,
    /**
     * True for a [SettingsGroup]'s own heading, which can hand out a link to
     * its group (#646). A free-standing heading is not the row the link would
     * flash, so it never offers one.
     */
    linkable: Boolean = false,
) {
    val link = linkable && settingLinkButtonShown() && currentRowLink() != null
    val hold = if (linkable) rememberRowHold() else null
    val outer = Modifier.settingLinkHold(hold).then(modifier)
    if (trailing != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = outer.fillMaxWidth()) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (info != null || action != null || link) {
                HeadingControls {
                    if (info != null) InfoButton(title = text, detail = info)
                    if (link) SettingLinkButton(text)
                    action?.invoke()
                }
            }
            Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The heading ends 16dp short of the card; the rows' own
                // content ends 16dp further in, and so does this.
                modifier = Modifier.padding(start = 8.dp, end = 16.dp),
            )
            RowHoldMenu(hold)
        }
        return
    }
    if (info == null && action == null && !link) {
        Box(outer) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            RowHoldMenu(hold)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = outer) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        HeadingControls {
            if (info != null) InfoButton(title = text, detail = info)
            if (link) SettingLinkButton(text)
            action?.invoke()
        }
        RowHoldMenu(hold)
    }
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
    swipeActions: List<SwipeAction> = emptyList(),
    onClick: () -> Unit,
) {
    // A value only sits beside the title while it fits there; a longer one
    // goes under the title, where it has the width to be read. The chevron and
    // the gap before it are what the row's end costs whatever the value is.
    val beside = value != null && fitsBesideTitle(
        value,
        MaterialTheme.typography.labelLarge,
        furniture = TRAILING_ICON_WIDTH + 4.dp + (if (rowLinkButtonShown()) SettingLinkTargetSize else 0.dp),
        hasIcon = rowHasTile(icon),
    )
    val below = value.takeIf { it != null && !beside }
    HighlightableRow(title, highlightKey) {
        WmRow(
            title = title,
            subtitle = subtitle.takeIf { below == null },
            supporting = supportingWith(subtitle, below?.let { { RowValueText(it) } }),
            icon = icon,
            flightTo = route,
            swipeActions = swipeActions,
            // A value beside the title sits on the title's own line. In the
            // trailing slot it took a column the whole height of the row, and
            // a long subtitle was squeezed to three words a line beside it.
            trailingOnTitleLine = beside,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowExtras { SettingLinkButton(title) }
                    if (beside && value != null) {
                        RowValueText(value, textAlign = TextAlign.End, maxLines = 1)
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

/** A row's current value, wherever the row ended up putting it. */
@Composable
private fun RowValueText(
    value: String,
    textAlign: TextAlign? = null,
    maxLines: Int = 2,
) {
    Text(
        value,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = if (textAlign == null) Modifier else Modifier.widthIn(max = RowTrailingTextMaxWidth),
    )
}

/** An outlined button's content padding, which its label does not get to use. */
private val BUTTON_FURNITURE = 48.dp

/**
 * The slider a short-named row draws on its title line: long enough to drag
 * by thumb, short enough to leave the name its room (SETTINGS_REDESIGN.md,
 * section 5).
 */
private val SliderOnTitleLineWidth = 140.dp

/** The gap either side of that slider. */
private val SliderPillGap = 8.dp

/** The readout pill's own horizontal padding, both sides. */
private val SliderPillPadding = 16.dp

/**
 * The narrowest a row's title may be left. Below this it stops reading as
 * words and becomes a letter or two a line, which is the failure this whole
 * measurement exists to prevent.
 */
private val ROW_TITLE_FLOOR = 120.dp

/** `ListItem`'s own padding: 16 dp at each end of the row. */
private val ROW_PADDING = 32.dp

/** The leading slot of a row that has one: the tile, and the gap after it. */
private val ROW_ICON_LANE = WmIconTileSize + 16.dp

/** A trailing chevron or drop-down arrow, at the default `Icon` size. */
private val TRAILING_ICON_WIDTH = 24.dp

/** The inset a group card keeps from each edge of the screen. */
private val GROUP_CARD_INSET = 32.dp

/**
 * How wide a settings row is: the screen, less the inset its group card keeps
 * from the edges. A row is otherwise as wide as the list it sits in, so this
 * is the number the rows that budget their own width start from.
 */
@Composable
private fun settingsRowWidth(): Dp =
    LocalConfiguration.current.screenWidthDp.dp - GROUP_CARD_INSET

/**
 * Whether [text], drawn in [style], fits beside a row's title once the row has
 * paid for everything else it draws: [furniture] is what the trailing controls
 * take, [hasIcon] whether there is a leading tile, and [rowWidth] how much
 * there was to divide.
 *
 * `ListItem` hands its trailing slot whatever width it asks for and gives the
 * headline whatever is left. So a long value or a long button label does not
 * shrink itself — it shrinks the title, down to one letter a line, and the row
 * becomes unreadable while the thing that squeezed it looks fine. A row that
 * knows its own trailing text measures it here and puts it under the title
 * instead of beside it when it will not fit.
 *
 * What fits is what is left of [rowWidth] once the title keeps [floor]
 * ([ROW_TITLE_FLOOR]), capped at [cap] ([RowTrailingTextMaxWidth]). Measuring
 * against that cap alone — which is what this did — passes a value that fits
 * the cap and still leaves the title nothing, because the cap knows nothing
 * about the "?", the reset, the glyph and the arrow sharing the row's end
 * with it. A row measuring its *title* against the rest of the line (the
 * slider row) passes a floor of zero and no cap: the title is the text.
 */
@Composable
private fun fitsBesideTitle(
    text: String,
    style: TextStyle,
    furniture: Dp,
    hasIcon: Boolean,
    rowWidth: Dp = settingsRowWidth(),
    floor: Dp = ROW_TITLE_FLOOR,
    cap: Dp = RowTrailingTextMaxWidth,
): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lane = if (hasIcon) ROW_ICON_LANE else 0.dp
    val max = (rowWidth - ROW_PADDING - lane - furniture - floor).coerceAtMost(cap)
    return remember(text, style, max, density) {
        max > 0.dp &&
            measurer.measure(text, style, maxLines = 1).size.width <=
            with(density) { max.toPx() }
    }
}

/** The widest of [texts] drawn in [style] on one line, for a row budgeting a slot whose text changes. */
@Composable
private fun widestText(texts: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, style, density) {
        with(density) { texts.maxOf { measurer.measure(it, style, maxLines = 1).size.width }.toDp() }
    }
}

/**
 * A row's supporting line, plus [extra] under it when the trailing slot could
 * not hold it. Null when there is nothing to draw, which leaves the row's own
 * [subtitle] to it.
 */
private fun supportingWith(
    subtitle: String?,
    extra: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? {
    if (extra == null) return null
    return {
        Column {
            if (subtitle != null) Text(subtitle)
            extra()
        }
    }
}

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
    disabledReason: DisabledReason? = null,
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
    disabledReason = disabledReason,
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
    // caller says why, and [disabledReason] what a long press on the row says,
    // with the way to the setting that unblocks it.
    enabled: Boolean = true,
    disabledReason: DisabledReason? = null,
    default: Boolean? = null,
    onChange: (Boolean) -> Unit,
) {
    // Every switch in the app latches through here, so this is the one place
    // the latch has to be given a feel — see [SettingsHaptics].
    val haptics = rememberSettingsHaptics()
    val change: (Boolean) -> Unit = { on ->
        haptics.toggle(on)
        onChange(on)
    }
    HighlightableRow(title, highlightKey, disabledReason = disabledReason.takeUnless { enabled }, info = info) {
        WmRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            // The switch, the "?" and the reset are the title's furniture, not
            // a second column beside the whole row: see [trailingOnTitleLine].
            trailingOnTitleLine = true,
            // Only a row that can grow a reset control has a title line that
            // changes width, so only that row pays for the word-by-word name.
            titleReflows = default != null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowExtras {
                        if (info != null) InfoButton(title, info)
                        SettingLinkButton(title)
                        ResetSetting(title, default != null && checked != default, possible = default != null) {
                            change(default == true)
                        }
                    }
                    // A little air before the switch (#970): with the "?" right
                    // against it, a tap meant for one landed on the other.
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = checked,
                        onCheckedChange = change,
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
 * A row that is both a switch and a door (#136): the switch turns the feature
 * on or off in place, and the rest of the row opens [route], the page holding
 * everything else the feature has. The tools list is the same shape, and like
 * it the switch flies into the destination's own enable row when that row
 * takes `switchKey = landingKey("switch")`.
 *
 * [default] is what [ToggleSetting] takes it for: the per-setting reset.
 */
@Composable
internal fun ToggleNavRow(
    @StringRes title: Int,
    subtitle: String?,
    checked: Boolean,
    route: String,
    info: String? = null,
    default: Boolean? = null,
    onChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val name = stringResource(title)
    HighlightableRow(name, title, info = info) {
        WmRow(
            title = name,
            subtitle = subtitle,
            icon = SettingsRowIcons[title],
            flightTo = route,
            trailingOnTitleLine = true,
            titleReflows = default != null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowExtras {
                        if (info != null) InfoButton(name, info)
                        SettingLinkButton(name)
                        ResetSetting(name, default != null && checked != default, possible = default != null) {
                            onChange(default == true)
                        }
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = onChange,
                        modifier = Modifier.wmSharedElement(takeOffKey("switch", route)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = onOpen,
        )
    }
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
 *
 * The tile and the subtitle's indent leave together when "Icons in settings"
 * is turned off, the lane closing as the tile shrinks — see [IconReveal].
 */
@Composable
private fun IconedRow(
    icon: ImageVector?,
    subtitle: String? = null,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal = LocalIconReveal.current.takeIf { icon != null && it.present }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && reveal != null) {
                Row(Modifier.iconReveal(reveal)) {
                    WmIconTile(icon, currentRouteAccent())
                    Spacer(Modifier.width(RowIconGap))
                }
            }
            header()
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (reveal == null) Modifier else Modifier.revealInset(reveal),
            )
        }
        content()
    }
}

/** A subtitle's indent past the tile, closing as the tile leaves. */
private fun Modifier.revealInset(reveal: IconReveal): Modifier = layout { measurable, constraints ->
    val inset = (RowIconLane.toPx() * reveal.progress.value).roundToInt()
    val placeable = measurable.measure(constraints.offset(horizontal = -inset))
    layout(placeable.width + inset, placeable.height) { placeable.placeRelative(inset, 0) }
}

/** [ControlSetting] for a row named by a string resource. */
@Composable
internal fun ControlSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    info: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    content: @Composable ColumnScope.() -> Unit,
) = ControlSetting(
    title = stringResource(title),
    subtitle = subtitle,
    info = info,
    icon = icon,
    highlightKey = title,
    content = content,
)

/**
 * A titled row whose control sits under the title and spans the row: a text
 * field, a run of chips that each act as they are tapped, a slider with presets
 * beside it — anything the other `*Setting` helpers have no shape for.
 *
 * The name, "?", subtitle and tile lane are [SliderSetting]'s and
 * [ChoiceSetting]'s, so a bespoke control still reads as one of the page's rows
 * rather than a label someone set above a widget. [content] starts right under
 * the subtitle; it brings its own top gap.
 */
@Composable
internal fun ControlSetting(
    title: String,
    subtitle: String? = null,
    info: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    HighlightableRow(title, highlightKey, info = info) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                ReflowingText(title, style = MaterialTheme.typography.bodyLarge)
                RowExtras(stack = false) {
                    if (info != null) InfoButton(title, info)
                    SettingLinkButton(title)
                }
                Spacer(Modifier.weight(1f))
            },
            content = content,
        )
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
    enabled: Boolean = true,
    disabledReason: DisabledReason? = null,
    default: Float? = null,
    onReset: (() -> Unit)? = null,
    preview: ((Float) -> Unit)? = null,
    typed: Boolean = true,
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
    enabled = enabled,
    disabledReason = disabledReason,
    default = default,
    onReset = onReset,
    preview = preview,
    typed = typed,
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
    // Off for a slider whose value cannot take effect yet — the row still
    // reads, and the subtitle is where the caller says why. Matches
    // [ToggleSetting]'s own `enabled`, and so is [disabledReason].
    enabled: Boolean = true,
    disabledReason: DisabledReason? = null,
    default: Float? = null,
    /**
     * For a setting whose default is "unset", not a number: the reset shows
     * whenever this is non-null and runs it instead of writing [default], which
     * would pin the value the automatic one happens to be here. [default] is
     * then only where the slider sits.
     */
    onReset: (() -> Unit)? = null,
    /**
     * Acts on the value under the finger on every step, for a row whose whole
     * point is heard or felt rather than stored — the key sound's volume, a
     * haptic's strength. [onChange] still runs once, on release.
     */
    preview: ((Float) -> Unit)? = null,
    /**
     * Writes [onChange] on a throttle while the finger is down, for the theme
     * and sticker editors, whose screens draw the stored value they edit. See
     * `rememberLiveSlider`; everywhere else one write on release is the point.
     */
    live: Boolean = false,
    /**
     * Whether tapping the readout opens a field to type the value in. Off only
     * for a readout that changes unit along the track ("12 hours", then
     * "1 day"), where a bare number cannot say which one it means.
     */
    typed: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val slider = rememberLiveSlider(value, onChange, live = live, preview = preview)
    var typing by rememberSaveable { mutableStateOf(false) }
    // The readout is the slider's detent: this row's values are continuous, so
    // the steps the user is actually aiming at are the ones the number they can
    // read changes on. Keyed on the string rather than the float, so a drag
    // across a range that formats to whole percent ticks a hundred times and
    // not once per touch event. Silent when the value moved without a finger on
    // it — a reset, or another screen writing the same setting.
    val haptics = rememberSettingsHaptics()
    val readout = display(slider.value)
    LaunchedEffect(readout) { if (slider.dragging) haptics.tick() }
    // A short name shares its line with the slider (SETTINGS_REDESIGN.md,
    // section 5): the row is then one line and its control, about 72 dp
    // rather than 110. What else the line has to hold is measured first — the
    // slider, the widest the pill gets, and the "?", link and reset the row
    // could grow — so a long name keeps the two-storey row rather than being
    // squeezed to a word a line beside a slider. The pill is measured at the
    // range's ends and middle rather than at the live readout, so the answer
    // holds still under a drag.
    val extras = listOf(
        info != null && rowInfoButtonShown(),
        rowLinkButtonShown(),
        onReset != null || default != null,
    ).count { it }
    val pillWidth = widestText(
        listOf(display(range.start), display((range.start + range.endInclusive) / 2f), display(range.endInclusive)),
        MaterialTheme.typography.labelLarge,
    ) + SliderPillPadding
    val onTitleLine = fitsBesideTitle(
        title,
        MaterialTheme.typography.bodyLarge,
        furniture = SliderOnTitleLineWidth + SliderPillGap * 2 + pillWidth + RowExtraSize * extras,
        hasIcon = rowHasTile(icon),
        floor = 0.dp,
        cap = Dp.Infinity,
    )
    HighlightableRow(title, highlightKey, disabledReason = disabledReason.takeUnless { enabled }, info = info) {
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
                    // Weighted so that the reset and the "?" are measured
                    // first and a long name wraps around what they leave. The
                    // other way round the name takes the whole line and the
                    // controls it is sharing it with are squeezed to nothing.
                    ReflowingText(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RowExtras(stack = false) {
                        if (info != null) InfoButton(title, info)
                        SettingLinkButton(title)
                        if (onReset != null) {
                            ResetSetting(title, true, possible = true, onReset)
                        } else {
                            ResetSetting(title, default != null && value != default, possible = default != null) {
                                onChange(default ?: 0f)
                            }
                        }
                    }
                }
                if (onTitleLine) {
                    WmSlider(
                        value = slider.value,
                        onValueChange = slider::onDrag,
                        modifier = Modifier.width(SliderOnTitleLineWidth),
                        onValueChangeFinished = slider::onRelease,
                        valueRange = range,
                        enabled = enabled,
                    )
                    Spacer(Modifier.width(SliderPillGap))
                }
                // A slider cannot be landed on one exact millisecond by thumb,
                // so the number is a button that takes it typed (#415). The
                // pill is what says so; a bare number reads as a label.
                Text(
                    readout,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = if (typed && enabled) {
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(
                                onClickLabel = stringResource(CommonR.string.common_type_setting_value_desc, title),
                                role = Role.Button,
                            ) { typing = true }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    } else {
                        Modifier
                    },
                )
            },
        ) {
            if (!onTitleLine) {
                WmSlider(
                    value = slider.value,
                    onValueChange = slider::onDrag,
                    onValueChangeFinished = slider::onRelease,
                    valueRange = range,
                    enabled = enabled,
                )
            }
        }
    }
    if (typing) {
        SliderEntryDialog(
            title = title,
            readout = readout,
            range = range,
            display = display,
            onDismiss = { typing = false },
        ) {
            typing = false
            // Through the slider rather than to onChange directly, so the
            // thumb moves at once, a sound or haptic row plays the new value,
            // and a live editor writes it the same way a drag would.
            slider.onDrag(it)
            slider.onRelease()
        }
    }
}

/** [StepperSetting] for a row named by a string resource. */
@Composable
internal fun StepperSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    value: Int,
    range: List<Int>,
    display: (Int) -> String,
    info: String? = null,
    icon: ImageVector? = SettingsRowIcons[title],
    default: Int? = null,
    onChange: (Int) -> Unit,
) = StepperSetting(
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
 * A whole-number row: the value between a minus and a plus, stepping through
 * [range].
 *
 * A stepper rather than a [SliderSetting] where the value is a small count and
 * every step of it matters. A slider spends a full row on a track that a count
 * of nine values cannot use — and it cannot be landed on exactly by thumb, which
 * is the whole of what someone picking a column count is trying to do.
 *
 * [range] is the steps in order rather than a numeric range, so a setting whose
 * values are not contiguous — an automatic 0 in front of 3..11, say — is still
 * one press per step in each direction.
 */
@Composable
internal fun StepperSetting(
    title: String,
    subtitle: String? = null,
    value: Int,
    range: List<Int>,
    display: (Int) -> String,
    info: String? = null,
    icon: ImageVector? = null,
    @StringRes highlightKey: Int = 0,
    default: Int? = null,
    onChange: (Int) -> Unit,
) {
    // A stored value off the ladder falls back to the first step. The repository
    // clamps every write onto it, so this is a guard against a hand-edited
    // preference rather than a path the settings screen can take.
    val index = range.indexOf(value).coerceAtLeast(0)
    // One step of the ladder, which is exactly what a tick means.
    val haptics = rememberSettingsHaptics()
    val step: (Int) -> Unit = { next ->
        haptics.tick()
        onChange(next)
    }
    HighlightableRow(title, highlightKey, info = info) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReflowingText(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RowExtras(stack = false) {
                        if (info != null) InfoButton(title, info)
                        SettingLinkButton(title)
                        ResetSetting(title, default != null && value != default, possible = default != null) {
                            onChange(default ?: 0)
                        }
                    }
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { step(range[index - 1]) },
                    enabled = index > 0,
                ) {
                    Icon(
                        Icons.Outlined.Remove,
                        contentDescription = stringResource(
                            CommonR.string.common_decrease_setting_desc,
                            title,
                        ),
                    )
                }
                Text(
                    display(range.getOrElse(index) { value }),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 88.dp),
                )
                IconButton(
                    onClick = { step(range[index + 1]) },
                    enabled = index < range.lastIndex,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(
                            CommonR.string.common_increase_setting_desc,
                            title,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The steps the two column pickers walk: 0 first, then [AlternateColumnsRange].
 *
 * What 0 means depends on which picker it is. On the global setting it is the
 * automatic wrap that shipped. On a key in the layout editor it is "no count of
 * my own", which defers to that setting. The ladder is the same either way, so
 * both read it from here.
 */
internal val AlternatesColumnsSteps: List<Int> = listOf(0) + AlternateColumnsRange

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
    /** What a long press says while [enabled] is off; see [DisabledReason]. */
    disabledReason: DisabledReason? = null,
    onAction: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    val label = stringResource(title)
    val guarded = rememberLockGuard(lock, onAction)
    val press = { if (confirm == null) guarded() else asking = true }
    val button: @Composable () -> Unit = {
        OutlinedButton(enabled = enabled, onClick = press) { Text(action) }
    }
    // "Clear" sits at the end of the row. "Delete the learned words" cannot:
    // the button would take the row and leave the title a letter a line.
    val beside = fitsBesideTitle(
        action,
        MaterialTheme.typography.labelLarge,
        furniture = BUTTON_FURNITURE,
        hasIcon = rowHasTile(icon),
    )
    HighlightableRow(label, title, disabledReason = disabledReason.takeUnless { enabled }) {
        WmRow(
            title = label,
            subtitle = subtitle.takeIf { beside },
            supporting = supportingWith(subtitle, if (beside) null else button),
            icon = icon,
            trailing = if (beside) button else null,
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
    detail: (@Composable (T) -> ChoiceDetail?)? = null,
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
    detail = detail,
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
    detail: (@Composable (T) -> ChoiceDetail?)? = null,
    onChange: (T) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val tiers = segmentTypeTiers()
    val density = LocalDensity.current
    // Measured here rather than left to the control, because the answer picks
    // the whole row: the segmented form puts the control under the title, and
    // the sheet form is an ordinary value row. [IconedRow] insets its content
    // by 16dp on each side, so that is what the segments have to live in.
    // The control below re-measures the same options against the same width
    // and reaches the same answer; only "is there one" is needed here.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth - 32.dp
        val fits = remember(options, available, tiers, density) {
            segmentStyle(options, available, measurer, tiers, density) != null
        }
        if (fits) {
            HighlightableRow(title, highlightKey, info = info) {
                IconedRow(
                    icon = icon,
                    subtitle = subtitle,
                    header = {
                        ReflowingText(title, style = MaterialTheme.typography.bodyLarge)
                        // The same `default != null` that draws the control at all is what
                        // makes the let non-empty; there is no fallback option to reset to
                        // on a row that shipped without a default.
                        RowExtras(stack = false) {
                            if (info != null) InfoButton(title, info)
                            SettingLinkButton(title)
                            ResetSetting(title, default != null && selected != default, possible = default != null) {
                                default?.let(onChange)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    },
                ) {
                    ChoiceControl(
                        options = options,
                        selected = selected,
                        modifier = Modifier.padding(top = 8.dp),
                        title = title,
                        detail = detail,
                        onChange = onChange,
                    )
                }
            }
            return@BoxWithConstraints
        }
        var open by rememberSaveable { mutableStateOf(false) }
        val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
        val glyph = choiceIcon(selected, detail)
        // The row already spends its trailing width on the "?", the reset, the
        // option's glyph and the arrow, so what the option name may take is
        // what those four leave. The reset is counted whenever the row could
        // grow one, not only while it has one, or the value would jump under
        // the title the moment the setting stopped matching its default.
        val beside = fitsBesideTitle(
            current,
            MaterialTheme.typography.labelLarge,
            furniture = TRAILING_ICON_WIDTH +
                rowExtrasWidth(
                    listOf(info != null && rowInfoButtonShown(), default != null, rowLinkButtonShown()).count { it },
                ) +
                (if (glyph != null) ChoiceValueGlyphSize + ChoiceValueGlyphGap else 0.dp),
            hasIcon = rowHasTile(icon),
            rowWidth = maxWidth,
        )
        HighlightableRow(title, highlightKey, info = info) {
            WmRow(
                title = title,
                subtitle = subtitle.takeIf { beside },
                supporting = supportingWith(
                    subtitle,
                    if (beside) null else {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChoiceValueGlyph(glyph)
                                RowValueText(current)
                            }
                        }
                    },
                ),
                icon = icon,
                titleReflows = default != null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowExtras {
                            if (info != null) InfoButton(title, info)
                            SettingLinkButton(title)
                            ResetSetting(title, default != null && selected != default, possible = default != null) {
                                default?.let(onChange)
                            }
                        }
                        // The same glyph the sheet puts on this option, small
                        // enough to sit in a value's lane: the row and the
                        // sheet then agree at a glance about what is chosen.
                        if (beside) ChoiceValueGlyph(glyph)
                        if (beside) RowValueText(current, textAlign = TextAlign.End, maxLines = 1)
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { open = true },
            )
        }
        if (open) {
            ChoiceSheet(
                title = title,
                subtitle = subtitle,
                options = options,
                selected = selected,
                detail = detail,
                onPick = onChange,
                onDismiss = { open = false },
            )
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
 * What a picker says about one option beyond its name: a line explaining what
 * it does, and something to look at.
 *
 * [leading] is a slot for something drawn — a colour swatch, a preview — and
 * wins over everything else. It stays the last parameter on purpose: the theme
 * editor passes it as a trailing lambda, and anything declared after it would
 * take those lambdas instead, quietly.
 *
 * [icon] is the ordinary case, a glyph in the row's tile, and is only needed
 * where the option is not an enum constant: [ChoiceOptionIcons] already
 * answers for those, so most call sites give neither and still get an icon.
 */
internal class ChoiceDetail(
    val description: String? = null,
    val icon: ImageVector? = null,
    val leading: (@Composable () -> Unit)? = null,
)

/**
 * The glyph for one option of a one-of-N setting: whatever the call site named
 * on its [ChoiceDetail], and otherwise whatever [ChoiceOptionIcons] holds for
 * the value itself.
 */
@Composable
private fun <T> choiceIcon(option: T, detail: (@Composable (T) -> ChoiceDetail?)?): ImageVector? =
    detail?.invoke(option)?.icon ?: ChoiceOptionIcons[option]

/** The size an option's glyph takes beside a value on the row behind a sheet. */
private val ChoiceValueGlyphSize = 18.dp

/** The air that glyph keeps between itself and the value it belongs to. */
private val ChoiceValueGlyphGap = 6.dp

/** The lane an option's glyph occupies on a sheet row, tile or no tile. */
private val ChoiceSheetGlyphLane = 40.dp

/** That glyph's own size inside the lane. */
private val ChoiceSheetGlyphSize = 24.dp

/**
 * One option's glyph on a sheet: the mark itself, in the text colour, with no
 * tile and no accent behind it. It keeps the width a [WmIconTile] would have
 * taken so the labels still line up with every other row in the app.
 */
@Composable
private fun ChoiceSheetGlyph(icon: ImageVector) {
    Box(
        modifier = Modifier.size(ChoiceSheetGlyphLane),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(ChoiceSheetGlyphSize),
        )
    }
}

/**
 * The chosen option's glyph, drawn small beside its name on the row that opens
 * the sheet. Draws nothing for an option that has no glyph, so a row whose
 * options are languages or layouts looks exactly as it did.
 */
@Composable
private fun ChoiceValueGlyph(icon: ImageVector?) {
    if (icon == null) return
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = ChoiceValueGlyphGap).size(ChoiceValueGlyphSize),
    )
}

/**
 * The options of a one-of-N setting on a sheet that slides up, one row each.
 *
 * This is what the wrapping chip cloud used to be. A cloud that runs onto two
 * or three lines costs a card of height on every visit, and it moves under the
 * finger as the selection changes, because the chips are different widths and
 * the check mark on the selected one makes it wider still. It also has nowhere
 * to say what any option means. A row of rows has room for a description and a
 * swatch, and it costs one line on the screen behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSheet(
    title: String?,
    subtitle: String?,
    options: List<Pair<T, String>>,
    selected: T,
    detail: (@Composable (T) -> ChoiceDetail?)?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opened at its full height rather than half way. A sheet that stops after
    // three rows is a list whose fourth option the user has no reason to think
    // exists: the handle says "there is more here" only to someone who already
    // suspects it. The column below scrolls, so a long list still fits.
    val rail = rememberScrollRailState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The rail is the other half of opening at full height: the sheet says
        // the list is long, and the rail says where in it you are.
        ScrollRail(state = rail, fadeColor = BottomSheetDefaults.ContainerColor) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            for ((option, label) in options) {
                val extra = detail?.invoke(option)
                // A drawn slot wins: a colour swatch says more about a colour
                // than any glyph could. Otherwise the option's own glyph,
                // which most sheets get without asking.
                val glyph = extra?.icon ?: ChoiceOptionIcons[option]
                WmRow(
                    title = label,
                    subtitle = extra?.description,
                    leading = extra?.leading ?: glyph?.let { vector ->
                        // Bare, not a WmIconTile: a sheet is a list of
                        // options rather than a list of destinations, and a
                        // column of accent tiles reads as a menu of places to
                        // go. The glyph is here to tell the options apart.
                        { ChoiceSheetGlyph(vector) }
                    },
                    trailing = { RadioButton(selected = option == selected, onClick = null) },
                    onClick = {
                        onPick(option)
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * A one-of-N control: segmented buttons when every option's name fits one, and
 * the current choice on a button that opens [ChoiceSheet] when they do not.
 *
 * Segmented buttons are the better control while the words survive: they read
 * as "one of these" and they are all reachable without a press. Two or three
 * short options ("On"/"Off", "Tabs"/"Mixed") fit anywhere; "After the shortcut
 * key" does not fit a third of a phone, and no amount of layout tuning makes
 * it.
 *
 * Every one-of-N row in settings goes through here rather than building its own
 * segmented row, because whether the words fit is not something the author can
 * know: it depends on the screen and on the language, and the failure is silent
 * (Material ellipsises and says nothing).
 *
 * [label] is the question the control answers, drawn above it. A control that
 * stands on its own needs one: a segmented row of three words, or worse a lone
 * button reading "System light or dark", says what the answers are and never
 * says what was asked. Leave it out only where the words directly above the
 * control already ask — a [ChoiceSetting] row, or a section heading with
 * nothing between it and the control.
 */
@Composable
internal fun <T> ChoiceControl(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    title: String? = null,
    label: String? = null,
    detail: (@Composable (T) -> ChoiceDetail?)? = null,
    onChange: (T) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val tiers = segmentTypeTiers()
    // A segment taking the selection is a step along a row of them, so it gets
    // the same tick a stepper's arrow does. Silent when the press lands on the
    // option that is already selected: nothing moved.
    val haptics = rememberSettingsHaptics()
    val pick: (T) -> Unit = { option ->
        if (option != selected) haptics.tick()
        onChange(option)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            // Not the muted colour a subtitle gets: this is the name of the
            // control under it, not a footnote about it.
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val width = maxWidth
            val style = remember(options, width, tiers, density) {
                segmentStyle(options, width, measurer, tiers, density)
            }
            if (style != null) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (option, name) ->
                        SegmentedButton(
                            selected = selected == option,
                            onClick = { pick(option) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            // The size the labels were measured at, which is
                            // not always the default one.
                            Text(name, style = style, maxLines = 1)
                        }
                    }
                }
                return@BoxWithConstraints
            }
            // The sheet gets the same question the control is drawn under, so
            // the compact form does not lose it on the way to the options.
            ChoiceValueButton(title ?: label, options, selected, detail, pick)
        }
    }
}

/**
 * The type size at which every option's name survives its share of [width] as a
 * segmented button, or null when none of [styles] fits and the sheet has to
 * take the choice instead. Shared by [ChoiceControl] and [ChoiceSetting], which
 * has to know before it draws anything: the answer decides whether the row
 * carries the control underneath it or opens a sheet instead.
 *
 * More than one size because the alternative to a slightly smaller label is not
 * a bigger one, it is losing the segments altogether and putting three short
 * words behind a press. Three seven-letter names have about 57 dp of label lane
 * each on a 360 dp screen (see [SegmentFurniture] for where the rest goes), and
 * that is under `labelLarge` and over `labelMedium`.
 */
private fun <T> segmentStyle(
    options: List<Pair<T, String>>,
    width: Dp,
    measurer: TextMeasurer,
    styles: List<TextStyle>,
    density: Density,
): TextStyle? {
    if (width <= 0.dp || options.isEmpty()) return null
    val perSegment = with(density) { (width / options.size).toPx() }
    val furniture = with(density) { SegmentFurniture.toPx() }
    return styles.firstOrNull { style ->
        options.all { (_, label) ->
            measurer.measure(label, style, maxLines = 1).size.width + furniture <= perSegment
        }
    }
}

/**
 * The sizes [segmentStyle] tries, largest first. Remembered on the typography
 * rather than rebuilt, because it is a `remember` key at both call sites.
 */
@Composable
private fun segmentTypeTiers(): List<TextStyle> {
    val typography = MaterialTheme.typography
    return remember(typography) { listOf(typography.labelLarge, typography.labelMedium) }
}

/**
 * The compact form for a control with no row of its own to put a value on: the
 * current choice on a full-width button that opens the sheet.
 */
@Composable
private fun <T> ChoiceValueButton(
    title: String?,
    options: List<Pair<T, String>>,
    selected: T,
    detail: (@Composable (T) -> ChoiceDetail?)?,
    onChange: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        ChoiceValueGlyph(choiceIcon(selected, detail))
        Text(current, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
    }
    if (open) {
        ChoiceSheet(
            title = title,
            subtitle = null,
            options = options,
            selected = selected,
            detail = detail,
            onPick = onChange,
            onDismiss = { open = false },
        )
    }
}

/**
 * Several yes/no choices that are one decision, drawn as one row of filter
 * chips: the six hold shortcuts read as one setting rather than six. [default]
 * draws the restore icon while the set differs from it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> MultiChoiceSetting(
    @StringRes title: Int,
    subtitle: String? = null,
    info: String? = null,
    options: List<Pair<T, String>>,
    selected: Set<T>,
    icon: ImageVector? = SettingsRowIcons[title],
    default: Set<T>? = null,
    onChange: (Set<T>) -> Unit,
) {
    val name = stringResource(title)
    HighlightableRow(name, title, info = info) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                ReflowingText(name, style = MaterialTheme.typography.bodyLarge)
                RowExtras(stack = false) {
                    if (info != null) InfoButton(name, info)
                    SettingLinkButton(name)
                    ResetSetting(name, default != null && selected != default, possible = default != null) {
                        default?.let(onChange)
                    }
                }
                Spacer(Modifier.weight(1f))
            },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                for ((option, label) in options) {
                    val on = option in selected
                    FilterChip(
                        selected = on,
                        onClick = { onChange(if (on) selected - option else selected + option) },
                        label = { Text(label) },
                        leadingIcon = if (on) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
