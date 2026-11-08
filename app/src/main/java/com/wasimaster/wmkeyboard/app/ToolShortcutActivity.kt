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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation

/**
 * A keyboard tool on the home screen (#970). The shortcut [ToolHomeScreenShortcutRow]
 * pins lands here: a text box of its own brings the keyboard up, and the box
 * names the tool in its private options, so WM Keyboard opens that tool's
 * panel as it starts on it. A level or a compass then works like an app, and
 * whatever a tool types (a sum, a translation, a generated password) lands in
 * the box, to copy or share.
 *
 * One task for each tool (#970): the shortcut's address names the tool, and
 * the manifest makes each address a document of its own, so every tool opened
 * this way is its own entry in Recents, named after it, and opening it again
 * brings that entry back.
 */
class ToolShortcutActivity : FragmentActivity() {

    private var tool by mutableStateOf<ToolbarTool?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tool = toolOf(intent)
        val opened = tool
        if (opened == null) {
            finish()
            return
        }
        nameTask(opened)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toolOf(intent)?.let {
            tool = it
            nameTask(it)
        }
    }

    /** Recents shows the tool's name on this task, not the app's. */
    @Suppress("DEPRECATION")
    private fun nameTask(tool: ToolbarTool) {
        setTaskDescription(ActivityManager.TaskDescription(getString(toolTitle(tool))))
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

        /** Only a tool with a panel: anything else in the extra or the address is ignored. */
        private fun toolOf(intent: Intent?): ToolbarTool? =
            (
                intent?.getStringExtra(EXTRA_TOOL)
                    ?: intent?.data?.takeIf { it.scheme == TOOL_SCHEME }?.schemeSpecificPart
                )?.let(KeyboardAutomation::panelTool)
    }
}

/** Whether WM Keyboard is the keyboard in use, which is who reads the box's request. */
private fun isCurrentKeyboard(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith("${context.packageName}/") == true

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
