package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Build
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Swipe
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.script.DeviceLocales
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.LanguageSuggestions
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.settingsDepthResolved
import com.wasimaster.wmkeyboard.core.util.PlayServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run wizard: enable the keyboard, answer a short persona quiz, then
 * walk through the choices that have no one-size-fits-all default — languages,
 * haptics, spacebar gestures and, for those who asked for everything, the theme
 * and a tour of the features nobody would guess at. Every page writes straight to DataStore, so backing out or
 * skipping keeps whatever was already chosen.
 *
 * The same screen doubles as a replay ("Run setup again" under About): on a
 * replay nothing is preset and nothing is seeded, so walking through with Next
 * changes no settings.
 */
@Composable
internal fun OnboardingScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A replay is any entry after the wizard was finished once. Frozen at
    // entry: the finish write must not re-brand the run mid-exit.
    val onboardingDone = settings.watch { it.onboardingDone }
    val replay = remember { onboardingDone }
    // Read once at entry too, so the welcome page doesn't pop in and out of
    // the page list while a replaying user flips the keyboard elsewhere.
    val replayImeReady = remember { imeEnabled(context) && imeSelected(context) }
    // Set once a backup is restored from the welcome page. Saveable, because
    // the page list reads it and a list that changes on rotation replays the
    // page transition.
    var restored by rememberSaveable { mutableStateOf(false) }
    val pages = settings.watch {
        onboardingPages(
            persona = it.onboarding,
            enabledTools = it.enabledTools,
            enabledLanguageCount = it.enabledLanguages.size,
            replay = replay,
            imeReady = replayImeReady,
            restored = restored,
        )
    }
    val reduceMotion = settings.watch { it.reduceMotion }
    var current by rememberSaveable { mutableStateOf(pages.first()) }
    val index = resolvePageIndex(pages, current)
    val page = pages[index]
    val onLastPage = index == pages.lastIndex
    // Whether the tools page (or the persona quiz on its behalf) has applied
    // its recommended starting selection. Hoisted here so leaving and
    // revisiting the page can't re-apply it over the user's choices.
    var toolsSeeded by rememberSaveable { mutableStateOf(replay) }
    // Guards the Welcome page's auto-advance so it fires once — otherwise
    // navigating Back to it while already set up would bounce straight
    // forward again, defeating the Back button.
    var welcomeAutoAdvanced by rememberSaveable { mutableStateOf(false) }
    // Whether the keyboard is both turned on and selected, as the Welcome page
    // last read it. The rest of the wizard is worth nothing until it is, so
    // that page's Next button waits for it; Skip is still there for anyone who
    // wants to set the keyboard up later. A replay that starts past Welcome
    // already checked.
    var keyboardReady by remember { mutableStateOf(replay && replayImeReady) }
    if (!replay) {
        SeedLanguagesFromDevice(repository, settings)
        SeedHapticStyleFromDevice(repository)
    }
    val accent = OnboardingPageAccents.getValue(page)
    val playServices = remember { PlayServices.available }
    val finish: () -> Unit = {
        finishOnboarding(scope, repository, settings.value, replay, playServices, restored)
        onFinished()
    }
    // A restore on a phone where the keyboard is already on and selected has
    // nothing left to do, so the wizard closes by itself.
    LaunchedEffect(restored, keyboardReady) {
        if (restored && keyboardReady) finish()
    }
    // Which way the turn went is read off the transition itself
    // (initialState vs targetState), so the move is only the page change.
    val goTo: (Int) -> Unit = { target ->
        current = pages[target.coerceIn(0, pages.lastIndex)]
    }

    Scaffold { padding ->
        // It pads itself by the keyboard, so the window must not pan as well.
        ResizeForKeyboard()
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Takes whatever the Skip button leaves, and spreads the steps
                // across it rather than bunching them at the left.
                OnboardingProgress(
                    pages = pages,
                    index = index,
                    accent = accent,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.weight(1f),
                    onGoTo = goTo,
                )
                // No Skip on the last page: the only thing left to skip there
                // is the Finish button next to it.
                if (!replay && !onLastPage) {
                    TextButton(onClick = finish) {
                        Text(stringResource(CommonR.string.common_skip))
                    }
                } else {
                    // Keeps the row's height with the button gone.
                    Spacer(Modifier.height(48.dp))
                }
            }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val forward = targetState >= initialState
                    if (reduceMotion) {
                        slideInHorizontally(tween(0)) togetherWith
                            slideOutHorizontally(tween(0))
                    } else {
                        val spec = tween<androidx.compose.ui.unit.IntOffset>(
                            NavTransitionMs, easing = NavTransitionEasing,
                        )
                        // Both pages travel a full width, and the outgoing one
                        // fades as it goes. The old parallax (a third of a
                        // width out) left the two stacked on top of each other
                        // for most of the turn, which read as a bug rather than
                        // as depth.
                        val fade = tween<Float>(NavTransitionMs / 2, easing = NavTransitionEasing)
                        if (forward) {
                            (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                                (slideOutHorizontally(spec) { -it } + fadeOut(fade))
                        } else {
                            (slideInHorizontally(spec) { -it } + fadeIn(fade)) togetherWith
                                (slideOutHorizontally(spec) { it } + fadeOut(fade))
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                label = "onboardingPage",
            ) { shownIndex ->
                val shown = pages[shownIndex.coerceIn(0, pages.lastIndex)]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    OnboardingHero(shown)
                    when (shown) {
                        OnboardingPage.WELCOME -> WelcomePage(
                            repository = repository,
                            canRestore = !replay && !restored,
                            restored = restored,
                            onRestored = { restored = true },
                            // Set here as well as through onSetupChanged: the
                            // auto-advance below takes the page out of the
                            // composition in the same pass, so its own effect
                            // would never get to report the state that caused it.
                            onReady = {
                                keyboardReady = true
                                if (!welcomeAutoAdvanced) {
                                    welcomeAutoAdvanced = true
                                    goTo(index + 1)
                                }
                            },
                            onSetupChanged = { ready -> keyboardReady = ready },
                        )
                        OnboardingPage.PERSONA -> PersonaPage(
                            repository, settings,
                            replay = replay,
                            onToolsSeeded = { toolsSeeded = true },
                        )
                        OnboardingPage.LANGUAGES -> LanguagesPage(repository, settings)
                        OnboardingPage.LOOK -> LookPage(repository, settings)
                        OnboardingPage.FEEDBACK -> FeedbackPage(repository, settings)
                        OnboardingPage.GESTURES -> GesturesPage(repository, settings)
                        OnboardingPage.DISCOVER -> DiscoverPage(repository, settings)
                        OnboardingPage.TOOLS -> ToolsPage(
                            repository, settings,
                            seeded = toolsSeeded,
                            onSeeded = { toolsSeeded = true },
                        )
                        OnboardingPage.TOOL_SETUP -> ToolSetupPage(repository, settings)
                        OnboardingPage.TRY -> TryPage(settings)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                if (index > 0) {
                    OutlinedButton(onClick = { goTo(index - 1) }) {
                        Text(stringResource(CommonR.string.common_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (onLastPage) finish() else goTo(index + 1) },
                    // After a restore the welcome page is the last page, and
                    // Finish stays open: the keyboard can be turned on later,
                    // as Skip allows on a run that was not restored.
                    enabled = page != OnboardingPage.WELCOME || keyboardReady || restored,
                ) {
                    Text(
                        stringResource(
                            if (onLastPage) R.string.onboarding_finish_action
                            else CommonR.string.common_next,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Closes the wizard. If the run never reached a point that replaced the
 * enable-everything tool default (skipping from the first page used to leave
 * all 70+ tools on), land the persona's starter set first; a replay and a
 * restore never touch anything.
 */
private fun finishOnboarding(
    scope: CoroutineScope,
    repository: SettingsRepository,
    settings: KeyboardSettings,
    replay: Boolean,
    playServices: Boolean,
    restored: Boolean,
) {
    scope.launch {
        if (!replay) {
            // A restored backup's tools are the user's own, even when they are
            // the everything-on default.
            if (!restored) {
                toolsAfterFinish(settings, playServices, ::isSupportedTool)?.let {
                    repository.setEnabledTools(it)
                }
                // The depth answer lands its extensions; a run that skipped
                // the question, or the whole wizard, never wrote any, and an
                // empty store reads as "an install from before Extensions",
                // which keeps every bundle on (#1575). Land the depth in
                // force instead, so a fresh install starts where the
                // Extensions page says it does.
                if (settings.appUi.extensions == null) {
                    repository.setExtensions(Extension.defaultsFor(settings.settingsDepthResolved()))
                }
            }
            repository.setOnboardingDone(true)
        }
    }
}

/**
 * The wizard's progress: every page's own icon, the current one filled with
 * that page's accent and the ones behind it faded.
 *
 * The icons rather than dots because every page already has one on its hero,
 * so the row says *which* steps these are — where you have been, where you
 * are, and what is still coming — instead of only how many are left.
 *
 * The whole row is measured against the width it is given and the icons shrink
 * to fit, because the page count is not fixed: the persona answers add and
 * remove steps, and eleven of them still have to fit next to a Skip button on
 * a small phone.
 */
@Composable
private fun OnboardingProgress(
    pages: List<OnboardingPage>,
    index: Int,
    accent: androidx.compose.ui.graphics.Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    onGoTo: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // The current step is drawn a size up, so budget for one of those and
        // plain cells for the rest. Every cell carries a trailing gap, the
        // last one included, so the gap is counted once per cell.
        val cell = ((maxWidth - ProgressGap * pages.size - ProgressGrowth) / pages.size)
            .coerceIn(ProgressMinCell, ProgressMaxCell)
        Row(
            // Each cell carries its own trailing gap rather than the row
            // spacing them: a step that is animating out has to be able to
            // take its gap with it as it collapses.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Walked over every page there is, not only the live ones, so a
            // page the quiz has just earned can animate in beside its
            // neighbours instead of appearing under them. AnimatedVisibility
            // keeps a departing step composed while it collapses, which is
            // what makes the row slide rather than jump.
            for (page in OnboardingPage.entries) {
                val at = pages.indexOf(page)
                AnimatedVisibility(
                    visible = at >= 0,
                    enter = if (reduceMotion) fadeIn(snap()) else fadeIn() + expandHorizontally(),
                    exit = if (reduceMotion) fadeOut(snap()) else fadeOut() + shrinkHorizontally(),
                ) {
                    ProgressCell(
                        page = page,
                        // A page mid-exit has no index of its own any more;
                        // hold it at "still to come" while it collapses.
                        state = when {
                            at < 0 -> ProgressState.AHEAD
                            at == index -> ProgressState.CURRENT
                            at < index -> ProgressState.DONE
                            else -> ProgressState.AHEAD
                        },
                        cell = cell,
                        accent = accent,
                        reduceMotion = reduceMotion,
                        onClick = { if (at >= 0) onGoTo(at) },
                    )
                }
            }
        }
    }
}

/** Where a step sits relative to the one on screen. */
private enum class ProgressState { DONE, CURRENT, AHEAD }

@Composable
private fun ProgressCell(
    page: OnboardingPage,
    state: ProgressState,
    cell: Dp,
    accent: androidx.compose.ui.graphics.Color,
    reduceMotion: Boolean,
    onClick: () -> Unit,
) {
    val current = state == ProgressState.CURRENT
    val size by animateDpAsState(
        targetValue = if (current) cell + ProgressGrowth else cell,
        animationSpec = if (reduceMotion) snap() else tween(NavTransitionMs),
        label = "progressCell",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = ProgressGap)
            .size(size)
            .clip(CircleShape)
            .background(
                when (state) {
                    ProgressState.CURRENT -> accent
                    ProgressState.DONE -> accent.copy(alpha = 0.25f)
                    ProgressState.AHEAD -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            // Every step is a shortcut to itself. The wizard writes each answer
            // as it is made, so there is nothing to lose by jumping — and
            // someone who wants to change one thing should not have to walk
            // back through the pages between.
            .clickable(onClick = onClick),
    ) {
        Icon(
            heroIcon(page),
            contentDescription = heroTitle(page),
            tint = when (state) {
                ProgressState.CURRENT -> MaterialTheme.colorScheme.surface
                ProgressState.DONE -> accent
                ProgressState.AHEAD -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(size * ProgressGlyphShare),
        )
    }
}

/** The progress row's geometry: cell bounds, the gap, and the current step's bump. */
private val ProgressMinCell: Dp = 16.dp
private val ProgressMaxCell: Dp = 26.dp
private val ProgressGap: Dp = 4.dp
private val ProgressGrowth: Dp = 6.dp

/** How much of a progress cell its glyph takes. */
private const val ProgressGlyphShare = 0.62f

private val HeroTileSize: Dp = 56.dp
private val CompactHeroTileSize: Dp = 40.dp

/**
 * The page's header: an accent icon tile, the title, and one line of intent.
 *
 * Two pages take a different shape. The welcome page centres its tile next to
 * the title on one line — it is the app's front door, and a left-aligned
 * column of three things reads as the first of many forms. The try page keeps
 * its header to a single short line, because the keyboard is about to cover
 * the bottom half of the screen and the text field has to be above it.
 */
@Composable
private fun OnboardingHero(page: OnboardingPage) {
    if (page == OnboardingPage.WELCOME || page == OnboardingPage.TRY) {
        val welcome = page == OnboardingPage.WELCOME
        Column(
            horizontalAlignment = if (welcome) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (welcome) 24.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroTile(
                    page,
                    size = if (welcome) HeroTileSize else CompactHeroTileSize,
                    glyph = if (welcome) 30.dp else 22.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    heroTitle(page),
                    style = if (welcome) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                heroSubtitle(page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (welcome) TextAlign.Center else TextAlign.Start,
            )
        }
        return
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        HeroTile(page, size = HeroTileSize, glyph = 30.dp)
        Spacer(Modifier.height(12.dp))
        Text(heroTitle(page), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            heroSubtitle(page),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The hero's accent tile, at whichever of the two sizes the page uses. */
@Composable
private fun HeroTile(page: OnboardingPage, size: Dp, glyph: Dp) {
    WmIconTile(OnboardingPageAccents.getValue(page), size = size, radius = size / 3) {
        Icon(heroIcon(page), contentDescription = null, modifier = Modifier.size(glyph))
    }
}

private fun heroIcon(page: OnboardingPage): ImageVector = when (page) {
    OnboardingPage.WELCOME -> Icons.Outlined.Keyboard
    OnboardingPage.PERSONA -> Icons.Outlined.Tune
    OnboardingPage.LANGUAGES -> Icons.Outlined.Language
    OnboardingPage.LOOK -> Icons.Outlined.Palette
    OnboardingPage.FEEDBACK -> Icons.Outlined.Vibration
    OnboardingPage.GESTURES -> Icons.Outlined.Swipe
    OnboardingPage.DISCOVER -> Icons.Outlined.AutoAwesome
    OnboardingPage.TOOLS -> Icons.Outlined.Widgets
    OnboardingPage.TOOL_SETUP -> Icons.Outlined.Build
    OnboardingPage.TRY -> Icons.Outlined.EditNote
}

@Composable
private fun heroTitle(page: OnboardingPage): String = stringResource(
    when (page) {
        OnboardingPage.WELCOME -> R.string.onboarding_welcome_title
        OnboardingPage.PERSONA -> R.string.onboarding_persona_title
        OnboardingPage.LANGUAGES -> R.string.onboarding_languages_title
        OnboardingPage.LOOK -> R.string.onboarding_look_title
        OnboardingPage.FEEDBACK -> R.string.onboarding_feedback_title
        OnboardingPage.GESTURES -> R.string.onboarding_gestures_title
        OnboardingPage.DISCOVER -> R.string.onboarding_discover_title
        OnboardingPage.TOOLS -> R.string.onboarding_tools_title
        OnboardingPage.TOOL_SETUP -> R.string.onboarding_tool_setup_title
        OnboardingPage.TRY -> R.string.onboarding_try_title
    },
)

@Composable
private fun heroSubtitle(page: OnboardingPage): String = when (page) {
    OnboardingPage.LANGUAGES -> pluralStringResource(
        R.plurals.onboarding_languages_subtitle,
        LanguageRegistry.all.size,
        LanguageRegistry.all.size,
    )
    else -> stringResource(
        when (page) {
            OnboardingPage.WELCOME -> R.string.onboarding_welcome_subtitle
            OnboardingPage.PERSONA -> R.string.onboarding_persona_subtitle
            OnboardingPage.LOOK -> R.string.onboarding_look_subtitle
            OnboardingPage.FEEDBACK -> R.string.onboarding_feedback_subtitle
            OnboardingPage.GESTURES -> R.string.onboarding_gestures_subtitle
            OnboardingPage.DISCOVER -> R.string.onboarding_discover_subtitle
            OnboardingPage.TOOLS -> R.string.onboarding_tools_subtitle
            OnboardingPage.TOOL_SETUP -> R.string.onboarding_tool_setup_subtitle
            OnboardingPage.TRY -> R.string.onboarding_try_subtitle
            else -> R.string.onboarding_look_subtitle
        },
    )
}

/**
 * Starts a fresh install in the languages the phone is already set to.
 *
 * Runs at the top of the wizard rather than on the languages page, so someone
 * who taps Skip on the very first screen still gets a keyboard that matches
 * their phone instead of a fixed pair of languages.
 *
 * Only ever fires when the enabled set is still *exactly* the built-in fallback:
 * that is the one state nobody can have chosen on purpose during onboarding, and
 * checking it means a phone whose keyboard was set up from the system settings
 * before the app was ever opened keeps what it was given.
 */
@Composable
private fun SeedLanguagesFromDevice(repository: SettingsRepository, settings: LiveSettings) {
    val context = LocalContext.current
    val untouched = settings.watch { it.enabledLayoutIds == BuiltInLayouts.defaultEnabledIds }
    // rememberSaveable, so a rotation mid-wizard can't re-seed over a language
    // the user has since removed on the page below.
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(untouched) {
        if (seeded || !untouched) return@LaunchedEffect
        seeded = true
        val ids = withContext(Dispatchers.IO) {
            LanguageSuggestions.seedLayoutIds(DeviceLocales.read(context))
        }
        if (ids.isNotEmpty()) repository.setEnabledLayoutIds(ids)
    }
}

/**
 * Picks the haptic style this device can actually play, once, at the top of a
 * first run — so nobody has to be asked which of five buzzes they want before
 * they have typed a key.
 *
 * Only ever on a fresh run (the caller gates it), where no stored answer can
 * exist to overwrite; the full list stays under Typing → Sound and haptics.
 */
@Composable
private fun SeedHapticStyleFromDevice(repository: SettingsRepository) {
    val context = LocalContext.current
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (seeded) return@LaunchedEffect
        seeded = true
        repository.setHapticStyle(HapticPlayer.bestSupportedStyle(context))
    }
}

/**
 * How long the wizard watches the system keyboard settings after it sends the
 * user there. Long enough for someone who reads the system's warning dialog,
 * short enough that a user who wandered off is not polled all day.
 */
private const val ENABLE_WATCH_MS = 5 * 60 * 1000L

/** How often that watch reads the enabled keyboard list. */
private const val ENABLE_POLL_MS = 500L

/**
 * Brings the wizard back to the front the moment the keyboard is turned on in
 * the system settings. The system settings give no way back, and a keyboard
 * that is turned on but never selected does nothing — so the wizard returns
 * and the setup card's own "Switch keyboard" button is the next thing under
 * the user's thumb.
 *
 * It stops there rather than opening the keyboard picker itself. A system
 * dialog appearing over an app nobody tapped anything in reads as a hijack —
 * and it covers the words that explain what the dialog is for.
 *
 * The poll here is deliberately not [rememberKeyboardSetup]'s. That one reads
 * through recomposition, which Compose stops while the activity is not started
 * — so it sees nothing at all for as long as the system settings are open,
 * which is exactly the window this has to watch. A read inside the coroutine
 * keeps working, because `delay` runs off the handler rather than the frame
 * clock.
 *
 * Starting the activity from the background is allowed here: this activity is
 * in the back stack of the task the user is looking at, which is one of the
 * standing exemptions.
 */
@Composable
internal fun ReturnAfterEnabling(watching: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(watching) {
        if (!watching) return@LaunchedEffect
        var waited = 0L
        while (!imeEnabled(context) && waited < ENABLE_WATCH_MS) {
            delay(ENABLE_POLL_MS)
            waited += ENABLE_POLL_MS
        }
        if (imeEnabled(context)) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
            )
        }
        // Last, so re-keying this effect cancels nothing that still matters.
        onDone()
    }
}
