package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.AppLockSecretPrompt
import com.wasimaster.wmkeyboard.app.lock.BiometricAppLock
import com.wasimaster.wmkeyboard.app.lock.LocalAppLock
import com.wasimaster.wmkeyboard.app.lock.LockedRoute
import com.wasimaster.wmkeyboard.core.accessibility.ScreenContextService
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import com.wasimaster.wmkeyboard.ime.aichat.AskAiContext
import com.wasimaster.wmkeyboard.ime.aichat.AskAiLaunch

/**
 * The AI chat as a sheet over whatever app the user was in (#697).
 *
 * Two ways in, one chat: the floating AI button, which hands over the text it
 * read off the screen (when screen reading is on), and "Ask AI" in another
 * app's text selection menu (`PROCESS_TEXT`), which hands over the selection.
 * Either way the text becomes the first message's attachment and nothing is
 * sent until the user asks something; the sheet shows the text, and it can be
 * removed. The conversation is an ordinary one in the chat store, so it is
 * there afterwards under Tools › AI › Chat.
 *
 * The sheet covers the bottom of the screen and leaves the top showing the
 * app it is about; a tap there closes it. It sits behind the same app lock as
 * the chat screen in settings.
 */
class AskAiActivity : FragmentActivity() {

    private lateinit var repository: SettingsRepository
    private lateinit var appLock: BiometricAppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        appLock = BiometricAppLock(this, repository)
        val ask = readAsk(intent)
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            val context = LocalContext.current
            val keep = settings.watch { it.ai.keepChats }
            LaunchedEffect(keep) { AiChatController.applyPersistSetting(context, keep) }
            AppTheme(settings) {
                CompositionLocalProvider(LocalAppLock provides appLock) {
                    AskAiSheet(onClose = ::finish) {
                        LockedRoute("ai_chat", onCancel = ::finish) {
                            AiChatScreen(
                                settings = settings,
                                conversationId = -1L,
                                onBack = ::finish,
                                onOpenAiSettings = ::openAiSettings,
                                initialAttachment = ask.attachment,
                                attachmentLabel = ask.label,
                                initialDraft = intent?.getStringExtra(EXTRA_DRAFT).orEmpty(),
                                inSheet = true,
                                notice = if (ask.screenMissing) {
                                    { ScreenReadingNotice() }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    AppLockSecretPrompt(appLock)
                }
            }
        }
    }

    companion object {
        /**
         * A question to start the message box with, unsent (#1143): the
         * assistant's "Explain this screen" and the words of "Ask by voice".
         */
        const val EXTRA_DRAFT = "com.wasimaster.wmkeyboard.askai.DRAFT"
    }

    private fun openAiSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TOOL, ToolbarTool.AI.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** What the sheet opens with. */
    private class Ask(
        /** The first message's attachment, framed for the model. */
        val attachment: String,
        /** The line above the composer that names it. */
        val label: String,
        /** Opened from the AI button with no screen text, and screen reading could fix that. */
        val screenMissing: Boolean,
    )

    private fun readAsk(intent: Intent?): Ask {
        val selected = if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        } else {
            ""
        }
        if (selected.isNotBlank()) {
            val app = appLabel(referrer?.host)
            return Ask(
                attachment = AskAiContext.selectedText(selected, app),
                label = selected.trim().length.let {
                    resources.getQuantityString(R.plurals.ask_ai_attachment_selection, it, it)
                },
                screenMissing = false,
            )
        }
        val screen = intent?.getStringExtra(AskAiLaunch.EXTRA_SCREEN_TEXT).orEmpty()
        if (screen.isNotBlank()) {
            val app = appLabel(intent?.getStringExtra(AskAiLaunch.EXTRA_SCREEN_PACKAGE))
            return Ask(
                attachment = AskAiContext.screen(app, screen),
                label = if (app.isBlank()) {
                    getString(R.string.ask_ai_attachment_screen_unnamed)
                } else {
                    getString(R.string.ask_ai_attachment_screen, app)
                },
                screenMissing = false,
            )
        }
        val fromButton = intent?.getBooleanExtra(AskAiLaunch.EXTRA_FROM_BUTTON, false) == true
        return Ask(
            attachment = "",
            label = "",
            screenMissing = fromButton && ChannelFeatures.SCREEN_CONTEXT && !ScreenContextService.isEnabled(this),
        )
    }

    /** An app's name for the model and the sheet, or blank when there is none to give. */
    private fun appLabel(packageName: String?): String {
        if (packageName.isNullOrBlank() || packageName == this.packageName) return ""
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("")
    }
}

/**
 * The sheet itself: most of the screen's height from the bottom, rounded at
 * the top, with the app it is about still showing above it under a light
 * scrim. A tap on that part closes it, as Back does.
 */
@Composable
private fun AskAiSheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT)
                // Taps inside the sheet are the sheet's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            content()
        }
    }
}

/** Under the model chips when the AI button found no screen to send: how to let it. */
@Composable
private fun ScreenReadingNotice() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ask_ai_screen_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { openScreenReadingDisclosure(context) }) {
            Text(stringResource(R.string.ask_ai_screen_off_action))
        }
    }
}

private fun openScreenReadingDisclosure(context: Context) {
    context.startActivity(
        SpecialAccessActivity.intent(context, SpecialAccess.SCREEN_CONTENT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** How much of the screen the sheet takes, from the bottom. */
private const val SHEET_HEIGHT = 0.85f

/** The dim over the app above the sheet. */
private const val SCRIM_ALPHA = 0.32f
