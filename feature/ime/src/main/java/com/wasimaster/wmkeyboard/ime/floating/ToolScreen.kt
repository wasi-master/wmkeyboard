package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureInPictureAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import com.wasimaster.wmkeyboard.core.settings.AppletTools
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.floats
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.PwSettingAction
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarCallbacks
import com.wasimaster.wmkeyboard.ime.ui.CaptureCaretHandle
import com.wasimaster.wmkeyboard.ime.ui.CompassPanel
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LevelPanel
import com.wasimaster.wmkeyboard.ime.ui.LocalCaptureCaret
import com.wasimaster.wmkeyboard.ime.ui.LocalInFloatingBox
import com.wasimaster.wmkeyboard.ime.ui.LocalSelectionOverlay
import com.wasimaster.wmkeyboard.ime.ui.LocalSelectionTools
import com.wasimaster.wmkeyboard.ime.ui.LocationCallbacks
import com.wasimaster.wmkeyboard.ime.ui.MoonPhasePanel
import com.wasimaster.wmkeyboard.ime.ui.PasswordPanel
import com.wasimaster.wmkeyboard.ime.ui.SelectionOverlay
import com.wasimaster.wmkeyboard.ime.ui.SelectionOverlayState
import com.wasimaster.wmkeyboard.ime.ui.SelectionTools
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.WeatherPanel
import com.wasimaster.wmkeyboard.ime.ui.selectionDismissObserver
import com.wasimaster.wmkeyboard.ime.ui.toolLabelRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tool full screen, in a window of the app's own rather than in the
 * keyboard or its floating box: the shortcut activity in the settings app
 * draws it, from the app list, the home screen, the app icon's menu or a
 * settings search. The keyboard's service runs it exactly as it runs the
 * floating box (#1155), with the same state and the same callbacks, so the
 * calculator or the notes are the same tool here as in the keyboard, only
 * with the whole screen to draw on.
 *
 * A text box of the tool's (a note, a task, Translate's) types from the
 * keyboard's keys, which come up over the screen when it is tapped, through
 * the capture routing the box uses. There is no field behind the screen, so
 * whatever the tool would put into one (an Insert, a picked clip) goes onto
 * the clipboard, as the box does with the keyboard down.
 *
 * Only while WM Keyboard is the keyboard in use: Android runs a keyboard's
 * service only then, and this is that service's state. [ready] says when.
 */
object ToolScreens {

    /**
     * The tools that open full screen: the floating box's, and the few that
     * need neither a field nor the keys (the sensors, the weather, the moon,
     * the password generator), in the order the pickers list them.
     */
    val tools: List<ToolbarTool> = AppletTools + listOf(
        ToolbarTool.COMPASS,
        ToolbarTool.LEVEL,
        ToolbarTool.MOON_PHASE,
        ToolbarTool.WEATHER,
        ToolbarTool.PASSWORD_GEN,
    )

    /** Whether [tool] opens full screen; any other tool opens over a text box of its own, in the keyboard. */
    fun runs(tool: ToolbarTool): Boolean = tool in tools

    /** The panel the keyboard runs [tool] as, on screen. */
    internal fun panelOf(tool: ToolbarTool): PanelMode? =
        PanelMode.entries.firstOrNull { it.name == tool.name }?.takeIf { runs(tool) }

    /** What a tool on screen needs of the running keyboard. */
    internal interface Host {
        val state: StateFlow<KeyboardUiState>
        val callbacks: AppletCallbacks
        val screenCallbacks: ScreenToolCallbacks

        /** Puts [tool] on screen, taking it from the box or the keyboard's panel. False when it cannot run now. */
        fun openScreen(tool: ToolbarTool): Boolean

        /** The screen showing [tool] went out of sight: the tool leaves, as leaving its panel does. */
        fun closeScreen(tool: ToolbarTool)

        /** The screen's pop-out button: [tool] moves into the floating box over the app behind. */
        fun floatScreen(tool: ToolbarTool): Boolean
    }

    private val _ready = MutableStateFlow(false)

    /** True while the keyboard runs, so a tool can open full screen. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Set by the keyboard's service as it starts and cleared as it ends, as [com.wasimaster.wmkeyboard.ime.KeyboardControls.host] is. */
    @Volatile
    internal var host: Host? = null
        set(value) {
            field = value
            _ready.value = value != null
        }

    /** A handle on [tool] on screen for one window; nothing is opened until [ToolScreenSession.resume]. */
    fun session(tool: ToolbarTool): ToolScreenSession? = if (runs(tool)) ToolScreenSession(tool) else null
}

/**
 * One window's tool on screen. The window resumes it as it comes into view
 * and pauses it as it goes, so a tool nobody can see never takes the keys
 * typed into another app, and the screen that is in front has its tool.
 */
class ToolScreenSession internal constructor(val tool: ToolbarTool) {
    /** Puts the tool on screen; false while the keyboard is not running, or the tool cannot run. */
    fun resume(): Boolean = ToolScreens.host?.openScreen(tool) == true

    /** The window went out of sight. */
    fun pause() {
        ToolScreens.host?.closeScreen(tool)
    }

    /** Moves the tool into the floating box over whatever is behind; false when it cannot float. */
    fun float(): Boolean = tool.floats && ToolScreens.host?.floatScreen(tool) == true

    /** Whether the tool can move into the floating box. */
    val canFloat: Boolean get() = tool.floats
}

/**
 * The keyboard's callbacks for the tools that run on screen and not in the
 * box: the compass's and the weather's location, the weather's refresh and
 * the calendar's hourly forecast, the password generator's options.
 */
@Immutable
internal class ScreenToolCallbacks(
    val location: LocationCallbacks,
    val onWeatherRefresh: () -> Unit,
    val calendar: Lazy<CalendarCallbacks>,
    val onPwSetting: (PwSettingAction) -> Unit,
)

/**
 * The tool on screen: a bar with the way back, the tool's name, its pop-out
 * and its settings, over the tool's own panel. In the keyboard's theme, as
 * the box is, so a tool looks the same wherever it opens. [onLightBars]
 * hears whether the bars' icons should be dark, for the window to set.
 */
@Composable
fun ToolScreenContent(
    session: ToolScreenSession,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onFloat: () -> Unit,
    onLightBars: (Boolean) -> Unit,
) {
    val host = ToolScreens.host ?: return
    val state by host.state.collectAsState()
    val tool = session.tool
    val panel = ToolScreens.panelOf(tool) ?: return
    KeyboardThemeProvider(state.settings) {
        val colors = MaterialTheme.colorScheme
        val light = colors.surface.luminance() > 0.5f
        SideEffect { onLightBars(light) }
        val title = stringResource(toolLabelRes(tool))
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surface)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.ime_applet_back),
                        tint = colors.onSurface,
                    )
                }
                Icon(
                    IconDefaults.forTool(tool),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (session.canFloat) {
                    IconButton(onClick = onFloat) {
                        Icon(
                            Icons.Outlined.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.ime_applet_pop_out),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.ime_screen_tool_settings, title),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            // Clear of the navigation bar, and of the keys while they are up
            // for one of the tool's text boxes, so what is typed stays in view.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                // The box or the keyboard may have taken the tool since this
                // screen opened it: the two share one copy of its state.
                val mine = state.appletPanel == panel && state.applet.onScreen
                if (mine) {
                    ScreenBody(panel, state, host, onClose)
                } else {
                    Elsewhere(title) { session.resume() }
                }
            }
        }
    }
}

/** The tool itself, with the text selection and the caret handle the box gives it. */
@Composable
private fun ScreenBody(panel: PanelMode, state: KeyboardUiState, host: ToolScreens.Host, onClose: () -> Unit) {
    val callbacks = host.callbacks
    val selectionOverlay = remember { SelectionOverlayState() }
    val selectionDismissable by remember(selectionOverlay) {
        derivedStateOf { selectionOverlay.session?.dismissOnOutsideTap == true }
    }
    val selectionTools = remember(callbacks.onAiChat) { SelectionTools(callbacks.onAiChat, askAi = false) }
    Box(
        Modifier
            .fillMaxSize()
            .selectionDismissObserver(selectionOverlay, selectionDismissable),
    ) {
        val caret = if (state.toolUp(panel)) {
            CaptureCaretHandle(
                state.captureCaretIndex(),
                callbacks.onCaretTap,
                anchor = state.captureAnchorIndex(),
                onSelect = callbacks.onSelect,
                onSelectionAction = callbacks.onSelectionAction,
            )
        } else {
            CaptureCaretHandle(0, {})
        }
        CompositionLocalProvider(
            LocalCaptureCaret provides caret,
            // Outside the keyboard, as in the box: the tool fills what it is
            // given and offers no height bar of the keyboard's.
            LocalInFloatingBox provides true,
            LocalSelectionOverlay provides selectionOverlay,
            LocalSelectionTools provides selectionTools,
        ) {
            if (panel.name in AppletToolNames) {
                AppletPanel(panel, state, callbacks, onClose)
            } else {
                ScreenOnlyPanel(panel, state, host.screenCallbacks, callbacks, onClose)
            }
        }
        SelectionOverlay(selectionOverlay, Modifier.matchParentSize())
    }
}

private val AppletToolNames: Set<String> = AppletTools.mapTo(HashSet()) { it.name }

/** The tools that run on screen and not in the box; see [ToolScreens.tools]. */
@Composable
private fun ScreenOnlyPanel(
    panel: PanelMode,
    state: KeyboardUiState,
    screen: ScreenToolCallbacks,
    callbacks: AppletCallbacks,
    onClose: () -> Unit,
) {
    when (panel) {
        // The sensors draw at whatever size they are given; a whole screen
        // is a bigger dial and a bigger bubble.
        PanelMode.COMPASS -> CompassPanel(state, location = screen.location)
        PanelMode.LEVEL -> LevelPanel(state)
        PanelMode.MOON_PHASE -> MoonPhasePanel(state)
        PanelMode.WEATHER -> WeatherPanel(
            state = state,
            onRefresh = screen.onWeatherRefresh,
            onOpenSettings = { callbacks.box.onOpenToolSettings(ToolbarTool.WEATHER) },
            onUseMyLocation = screen.location.onUseMyLocationForTools,
            calendar = screen.calendar.value,
        )
        PanelMode.PASSWORD_GEN -> Column(Modifier.fillMaxSize()) {
            val settings = state.settings.passwordGenerator
            val pin = settings.pinMode
            val passphrase = !pin && settings.pwPassphraseMode
            // The keyboard's header tabs, which the panel leaves to its host.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_tool_password_gen), selected = !passphrase && !pin) {
                    screen.onPwSetting(PwSettingAction.PassphraseMode(false))
                }
                ToolPanelChip(stringResource(R.string.ime_password_tab_passphrase), selected = passphrase) {
                    screen.onPwSetting(PwSettingAction.PassphraseMode(true))
                }
                ToolPanelChip(stringResource(R.string.ime_password_tab_pin), selected = pin) {
                    screen.onPwSetting(PwSettingAction.PinMode)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PasswordPanel(state, screen.onPwSetting, callbacks.onInsert)
            }
        }
        else -> Unit
    }
}

/** The tool has moved to the floating box or the keyboard since: a way to have it back here. */
@Composable
private fun Elsewhere(title: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.ime_screen_tool_elsewhere, title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.ime_screen_tool_bring_back)) }
    }
}
