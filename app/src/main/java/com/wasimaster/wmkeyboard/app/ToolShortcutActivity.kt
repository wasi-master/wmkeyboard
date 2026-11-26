package com.wasimaster.wmkeyboard.app

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation
import com.wasimaster.wmkeyboard.ime.floating.ToolScreenContent
import com.wasimaster.wmkeyboard.ime.floating.ToolScreenSession
import com.wasimaster.wmkeyboard.ime.floating.ToolScreens
import kotlinx.coroutines.launch

/**
 * A keyboard tool opened from outside the keyboard: from its icon on the home
 * screen, its entry in the app list, the app icon's menu, a settings search
 * or the tool's page (see [ToolLaunch]). One task for each tool (#970): the
 * address names the tool, and the manifest makes each address a document of
 * its own, so every tool opened this way is its own entry in Recents, named
 * after it, and opening it again brings that entry back. An app-list entry
 * carries no address, so it is handed on to the one that does.
 *
 * A tool the keyboard can run full screen ([ToolScreens.runs]) opens full
 * screen, the keyboard's service running it as it runs the floating box: the
 * calculator, a translator, the notes as a page rather than a panel. The
 * screen holds the tool only while it is in view ([onStart], [onStop]), so a
 * tool left in Recents never takes the keys typed into another app.
 *
 * Any other tool opens as it did before (#970): a text box of its own brings
 * the keyboard up, and the box names the tool in its private options, so WM
 * Keyboard opens that tool's panel as it starts on it. A level or a compass
 * then works like an app, and whatever a tool types (a sum, a translation, a
 * generated password) lands in the box, to copy or share.
 *
 * The clock, the notes and the to-do list have screens of their own in this
 * app, which their app-list entries open instead.
 */
class ToolShortcutActivity : FragmentActivity() {

    private var tool by mutableStateOf<ToolbarTool?>(null)

    /** The tool on screen, for a tool that runs full screen; null for the box route. */
    private var session: ToolScreenSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val opened = toolOf(intent)
        if (opened == null) {
            finish()
            return
        }
        // An app-list entry: every one starts this activity, with no address.
        // Left as it is, Android would take a second tool's entry for the task
        // the first one opened and bring that back. Handed on to the address
        // a home-screen icon carries, each tool is its own document again, and
        // the clock, notes and to-do list open their own screens.
        if (intent?.data == null && ToolAppList.toolOf(intent?.component?.className) != null) {
            startActivity(ToolLaunch.intent(this, opened))
            finish()
            return
        }
        tool = opened
        nameTask(opened)
        val screen = ToolScreens.session(opened)
        if (screen != null) {
            session = screen
            // The keys come up only for a tool's text box, and the screen
            // makes room for them rather than going under them.
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            setContent { FullScreenTool(screen) }
            // The keyboard may start after the screen did (just picked as the
            // keyboard, or brought back after Android ended it): the tool comes
            // up as soon as it can, while the screen is in view.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ToolScreens.ready.collect { ready -> if (ready) screen.resume() }
                }
            }
            return
        }
        val repository = SettingsRepository(applicationContext)
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                tool?.let { ToolShortcutScreen(it, onClose = ::finish) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        session?.pause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = toolOf(intent) ?: return
        // A document per tool, so a new intent names the same tool, unless an
        // older build's task was reused; the full-screen route stays as it opened.
        if (session != null) return
        tool = next
        nameTask(next)
    }

    /** Recents shows the tool's name on this task, not the app's. */
    @Suppress("DEPRECATION")
    private fun nameTask(tool: ToolbarTool) {
        setTaskDescription(ActivityManager.TaskDescription(getString(toolTitle(tool))))
    }

    /** The tool full screen, or why it cannot be yet. */
    @Composable
    private fun FullScreenTool(screen: ToolScreenSession) {
        val ready by ToolScreens.ready.collectAsStateWithLifecycle()
        if (ready) {
            ToolScreenContent(
                session = screen,
                onClose = ::finish,
                onSettings = {
                    startActivity(MainActivityContract.intent(this, toolRoute(screen.tool)))
                },
                onFloat = { if (screen.float()) finish() },
                onLightBars = { light ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                },
            )
        } else {
            val repository = remember { SettingsRepository(applicationContext) }
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return
            AppTheme(settings) { KeyboardNotRunning(screen.tool, onClose = ::finish) }
        }
    }

    companion object {
        private const val EXTRA_TOOL = "tool"

        /** What a home screen icon for [tool] opens. */
        fun intent(context: Context, tool: ToolbarTool): Intent =
            Intent(context, ToolShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // The address is what makes each tool its own document, and
                // so its own task: extras play no part in telling two apart.
                .setData(Uri.parse("$TOOL_SCHEME:${tool.name}"))
                .putExtra(EXTRA_TOOL, tool.name)

        private const val TOOL_SCHEME = "wmkeyboard-tool"

        /**
         * Only a tool with a panel: anything else in the extra, the address or
         * the app-list entry that started this is ignored.
         */
        private fun toolOf(intent: Intent?): ToolbarTool? =
            (
                intent?.getStringExtra(EXTRA_TOOL)
                    ?: intent?.data?.takeIf { it.scheme == TOOL_SCHEME }?.schemeSpecificPart
                )?.let(KeyboardAutomation::panelTool)
                ?: ToolAppList.toolOf(intent?.component?.className)
    }
}

/** Whether WM Keyboard is the keyboard in use, which is who reads the box's request. */
private fun isCurrentKeyboard(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith("${context.packageName}/") == true

/**
 * A full-screen tool with no keyboard to run it: WM Keyboard is not the
 * keyboard in use, or Android has not started it yet. The tool comes up by
 * itself once it is.
 */
@Composable
private fun KeyboardNotRunning(tool: ToolbarTool, onClose: () -> Unit) {
    val context = LocalContext.current
    val current = rememberGrantState(::isCurrentKeyboard)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(toolTitle(tool)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_close))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(bottom = 48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (current) R.string.tool_screen_starting else R.string.tool_screen_not_current),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (!current) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }) { Text(stringResource(R.string.tool_shortcut_switch_action)) }
            }
        }
    }
}

@Composable
private fun ToolShortcutScreen(tool: ToolbarTool, onClose: () -> Unit) {
    val context = LocalContext.current
    val current = rememberGrantState(::isCurrentKeyboard)
    var field by remember { mutableStateOf<EditText?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val hint = stringResource(R.string.tool_shortcut_field_hint)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(toolTitle(tool)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_close))
            }
        }
        if (!current) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tool_shortcut_not_current),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }) { Text(stringResource(R.string.tool_shortcut_switch_action)) }
            }
        }
        // A fresh box per tool: the keyboard reads the request when it starts
        // on a field, and a new field is a new start, not a restart.
        key(tool) {
            AndroidView(
                factory = { ctx ->
                    EditText(ctx).apply {
                        privateImeOptions = KeyboardAutomation.privateOptionsFor(tool)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        gravity = Gravity.TOP or Gravity.START
                        background = null
                        setTextColor(textColor)
                        setHintTextColor(hintColor)
                        this.hint = hint
                        field = this
                        post {
                            requestFocus()
                            ctx.getSystemService(InputMethodManager::class.java)
                                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                val text = field?.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(null, text))
                }
            }) { Text(stringResource(CommonR.string.common_copy)) }
            TextButton(onClick = {
                val text = field?.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }) { Text(stringResource(CommonR.string.common_share)) }
        }
    }
}
