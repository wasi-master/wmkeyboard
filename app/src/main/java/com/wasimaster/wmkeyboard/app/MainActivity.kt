package com.wasimaster.wmkeyboard.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
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
import com.wasimaster.wmkeyboard.app.media.MusicApps
import com.wasimaster.wmkeyboard.app.media.MusicAppsScreen
import com.wasimaster.wmkeyboard.app.updates.LocalAppUpdater
import com.wasimaster.wmkeyboard.app.updates.UpdateCard
import com.wasimaster.wmkeyboard.app.updates.rememberAppUpdater
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import androidx.compose.ui.platform.LocalConfiguration
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.applyDeviceForm
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.os.Build
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.ime.ui.LocalIconSet
import com.wasimaster.wmkeyboard.ime.ui.rememberIconSet
import kotlinx.coroutines.Dispatchers
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ThemeMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.outlined.Warning

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
    // Which folds are open, published for every screen: a group reads it by
    // its own key and asks here to change it, so no screen threads the
    // repository through for the sake of one chevron.
    val foldScope = rememberCoroutineScope()
    val folds = remember(settings.appUi.advancedOpen) {
        AdvancedFolds(settings.appUi.advancedOpen) { key, open ->
            foldScope.launch { repository.setAdvancedFoldOpen(key, open) }
        }
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
            LocalAdvancedFolds provides folds,
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
        composable(MusicApps.ROUTE) {
            SettingsScreen(
                stringResource(R.string.musicapps_title),
                { navController.popBackStack() },
                route = MusicApps.ROUTE,
            ) {
                MusicAppsScreen(repository, settings)
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
        // The panel layouts (issue #63): the emoji, clipboard and text-editing
        // panels in the layout editor's own controls. The argument is the
        // PanelKind's name; an unknown one falls back to the emoji panel rather
        // than crashing on a stale deep link.
        composable("panel_edit/{panel}") { backStackEntry ->
            val kind = PanelKind.entries.firstOrNull { it.name == backStackEntry.arguments?.getString("panel") }
                ?: PanelKind.EMOJI
            SettingsScreen(stringResource(R.string.panel_layout_row_title), { navController.popBackStack() }) {
                PanelLayoutEditorScreen(repository, settings, kind) { route -> navController.navigate(route) }
            }
        }
        composable("panel_json/{panel}") { backStackEntry ->
            val kind = PanelKind.entries.firstOrNull { it.name == backStackEntry.arguments?.getString("panel") }
                ?: PanelKind.EMOJI
            SettingsScreen(stringResource(R.string.panel_layout_json_title), { navController.popBackStack() }) {
                PanelLayoutJsonScreen(repository, kind) { navController.popBackStack() }
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
                SnippetSettings(repository, settings) { navController.navigate(it) }
            }
        }
        composable("expander/edit/{snippetId}") { backStackEntry ->
            // 0 is "a snippet that does not exist yet", which is what the Add
            // button navigates to.
            val snippetId = backStackEntry.arguments?.getString("snippetId")?.toLongOrNull() ?: 0L
            SettingsScreen(
                stringResource(
                    if (snippetId == 0L) R.string.rows_snippet_new_title else R.string.rows_snippet_edit_title,
                ),
                { navController.popBackStack() },
            ) {
                SnippetEditor(settings, snippetId) { navController.popBackStack() }
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
                    // The list row's subtitle lands here, under the heading,
                    // rather than as a loose line at the top of the page.
                    subtitle = stringResource(toolDescription(tool)),
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
        composable("advanced") {
            SettingsScreen(
                stringResource(R.string.home_advanced_title),
                { navController.popBackStack() },
                route = "advanced",
            ) {
                AdvancedSettings { route -> navController.navigate(route) }
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
            // One list drives this screen and the search index's root entries,
            // so a row cannot exist on one and not the other.
            for (group in RootGroup.entries) {
                val rows = RootEntries.filter { it.group == group }
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
/** The destinations a beginner should never meet on day one, one press away. */
@Composable
internal fun AdvancedSettings(onNavigate: (String) -> Unit) {
    SettingsGroup {
        item {
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
        item {
            NavRow(
                R.string.typing_hw_shortcuts_list_title,
                stringResource(R.string.typing_hw_shortcuts_list_subtitle),
                route = "hwshortcuts",
            ) { onNavigate("hwshortcuts") }
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
    /** A control on the heading's right — the pencil that puts a list into reorder mode. */
    action: (@Composable () -> Unit)? = null,
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
    val folds = LocalAdvancedFolds.current
    val foldId = foldKey?.let { "${LocalScreenRoute.current.orEmpty()}/$it" }
    val highlighted = SettingsHighlight.target != 0 || SettingsHighlight.targetItems.isNotEmpty()
    val open = foldId == null || highlighted || (folds?.open?.contains(foldId) ?: false)
    // A named group is a scroll target in its own right. Some things the user
    // arrives at from search — or from an addon's Use button — are a whole
    // section rather than one row: "Icon pack", "Your packs", "Installed
    // fonts". Unnamed groups have nothing to match on and stay plain. Coarse,
    // because anything inside it that answers to the same request is a better
    // answer than the section around them.
    HighlightableRow(title, highlightKey, coarse = true) {
        if (foldId != null && title != null) {
            FoldHeader(title, count = scope.items.size, open = open, info = info) {
                folds?.toggle(foldId, !open)
            }
        } else if (title != null) {
            SectionHeader(title, info = info, action = action)
        }
        if (!open) {
            Spacer(Modifier.height(8.dp))
            return@HighlightableRow
        }
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

/** A fold's heading: the title, how many rows it holds, and a chevron. */
@Composable
private fun FoldHeader(
    title: String,
    count: Int,
    open: Boolean,
    info: String? = null,
    onToggle: () -> Unit,
) {
    val numberFormat = stringResource(R.string.values_number)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            numberFormat.format(count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (info != null) InfoButton(title = title, detail = info)
        ExpandChevron(open)
    }
}

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

/** The chevron that says "this opens", pointing down closed and up open. */
@Composable
internal fun ExpandChevron(expanded: Boolean) {
    Icon(
        Icons.Outlined.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(if (expanded) 180f else 0f),
    )
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
 */
@Composable
internal fun SectionHeader(
    text: String,
    info: String? = null,
    // Aligns with the text inside group rows: 16dp group margin plus the
    // rows' own 16dp content inset.
    modifier: Modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    action: (@Composable () -> Unit)? = null,
) {
    if (info == null && action == null) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(title = text, detail = info)
        action?.invoke()
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
    HighlightableRow(title, highlightKey) {
        IconedRow(
            icon = icon,
            subtitle = subtitle,
            header = {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (info != null) InfoButton(title, info)
                }
                ResetSetting(title, default != null && value != default) { onChange(default ?: 0) }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onChange(range[index - 1]) },
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
                    onClick = { onChange(range[index + 1]) },
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
 * The column counts the alternates popup offers: the automatic wrap that shipped,
 * then 3 to 11. Fewer than three columns is a list rather than a grid, and past
 * eleven the entries are narrower than a fingertip on a phone.
 */
internal val AlternatesColumnsRange: List<Int> = listOf(0) + (3..11)

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
 * Above this many options a chip cloud is a wall, so the control becomes one
 * row showing the current choice that opens a scrolling list.
 */
private const val CHOICE_DIALOG_THRESHOLD = 6

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
        if (options.size > CHOICE_DIALOG_THRESHOLD) {
            ChoiceDialogButton(options, selected, onChange)
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

/**
 * The long-list form of [ChoiceControl]: the current choice on a button, the
 * rest in a dialog that scrolls. Picking one closes it.
 */
@Composable
private fun <T> ChoiceDialogButton(
    options: List<Pair<T, String>>,
    selected: T,
    onChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second.orEmpty()
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(current, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((option, label) in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChange(option)
                                open = false
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
