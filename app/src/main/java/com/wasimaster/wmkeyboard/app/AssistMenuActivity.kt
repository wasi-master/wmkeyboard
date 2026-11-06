package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Chat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.aichat.AskAiLaunch
import com.wasimaster.wmkeyboard.ime.sysvoice.AppDictation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the assistant gesture opens (#1143): a short menu at the bottom of the
 * screen, over the app it was opened from. Every choice ends in the AI chat
 * sheet ([AskAiActivity]), which is the same chat as Tools › AI › Chat, so
 * its models, tools and history are the ones set up there:
 *
 * - **Ask about this screen**, **Explain this screen** and **Translate this
 *   screen** attach the screen's text, the last two with a question already
 *   written in the message box. Nothing is sent until the user sends it.
 * - **Ask by voice** listens here with the keyboard's voice engines
 *   ([AppDictation]) and puts the words in the message box, with the screen
 *   attached when there is one.
 * - **New chat** opens the chat with nothing attached.
 *
 * The screen actions show only when Android sent the screen's text, which
 * it does when **Use text from screen** is on in its assistant settings; a
 * line says so otherwise, with the way there.
 */
class AssistMenuActivity : ComponentActivity() {

    private lateinit var repository: SettingsRepository

    private var listening by mutableStateOf(false)
    private var level by mutableStateOf(0f)
    private var heard by mutableStateOf("")
    private var voiceError by mutableStateOf<AppDictation.Error?>(null)
    private var dictation: AppDictation? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else voiceError = AppDictation.Error.PERMISSION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        val screen = intent?.getStringExtra(AskAiLaunch.EXTRA_SCREEN_TEXT).orEmpty()
        val language = Locale.getDefault().getDisplayLanguage(Locale.getDefault())
        setContent {
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val settings = stored.value ?: return@setContent
            AppTheme(settings) {
                AssistMenu(
                    hasScreen = screen.isNotBlank(),
                    listening = listening,
                    level = level,
                    heard = heard,
                    voiceError = voiceError,
                    onAsk = { draft, withScreen -> openChat(draft, withScreen) },
                    explainDraft = stringResource(R.string.assist_explain_draft),
                    translateDraft = stringResource(R.string.assist_translate_draft, language),
                    onVoice = ::startVoice,
                    onStopVoice = { dictation?.stop() },
                    onScreenSettings = {
                        openVoiceInputSettings(this)
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The microphone never stays open behind another app.
        if (!isChangingConfigurations && listening) {
            dictation?.cancel()
            dictation = null
            listening = false
        }
    }

    override fun onDestroy() {
        dictation?.cancel()
        dictation = null
        super.onDestroy()
    }

    private fun startVoice() {
        voiceError = null
        heard = ""
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listen() {
        listening = true
        lifecycleScope.launch {
            val settings = repository.settings.first()
            if (isFinishing) return@launch
            dictation?.cancel()
            val session = AppDictation(
                this@AssistMenuActivity,
                settings,
                AppDictation.languagesFor(settings, requestedTag = null),
                forwardOnly = !settings.voiceBar.enabled,
                listener = object : AppDictation.Listener {
                    override fun onReady() = Unit

                    override fun onLevel(level: Float) {
                        this@AssistMenuActivity.level = level
                    }

                    override fun onPartial(text: String) {
                        heard = text
                    }

                    override fun onResult(text: String) {
                        dictation = null
                        listening = false
                        openChat(text, withScreen = true)
                    }

                    override fun onError(error: AppDictation.Error) {
                        dictation = null
                        listening = false
                        level = 0f
                        voiceError = error
                    }
                },
            )
            dictation = session
            session.start()
        }
    }

    /** The AI chat sheet, with [draft] in its message box and the screen attached when [withScreen]. */
    private fun openChat(draft: String, withScreen: Boolean) {
        val chat = Intent(this, AskAiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withScreen) {
            intent?.getStringExtra(AskAiLaunch.EXTRA_SCREEN_TEXT)?.let { chat.putExtra(AskAiLaunch.EXTRA_SCREEN_TEXT, it) }
            intent?.getStringExtra(AskAiLaunch.EXTRA_SCREEN_PACKAGE)?.let { chat.putExtra(AskAiLaunch.EXTRA_SCREEN_PACKAGE, it) }
        }
        if (draft.isNotBlank()) chat.putExtra(AskAiActivity.EXTRA_DRAFT, draft)
        startActivity(chat)
        finish()
    }
}

@Composable
private fun AssistMenu(
    hasScreen: Boolean,
    listening: Boolean,
    level: Float,
    heard: String,
    voiceError: AppDictation.Error?,
    onAsk: (draft: String, withScreen: Boolean) -> Unit,
    explainDraft: String,
    translateDraft: String,
    onVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onScreenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = ASSIST_SCRIM_ALPHA))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding().padding(vertical = 12.dp)) {
                if (listening) {
                    VoiceAsk(level = level, heard = heard, onStop = onStopVoice)
                    return@Column
                }
                if (hasScreen) {
                    AssistItem(Icons.Outlined.Visibility, stringResource(R.string.assist_ask_screen)) { onAsk("", true) }
                    AssistItem(Icons.Outlined.Description, stringResource(R.string.assist_explain_screen)) {
                        onAsk(explainDraft, true)
                    }
                    AssistItem(Icons.Outlined.Translate, stringResource(R.string.assist_translate_screen)) {
                        onAsk(translateDraft, true)
                    }
                }
                AssistItem(
                    Icons.Outlined.Mic,
                    stringResource(R.string.assist_ask_voice),
                    supporting = voiceError?.let { stringResource(assistVoiceErrorRes(it)) },
                    onClick = onVoice,
                )
                AssistItem(Icons.Outlined.Chat, stringResource(R.string.assist_new_chat)) { onAsk("", false) }
                if (!hasScreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.assist_no_screen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onScreenSettings) { Text(stringResource(R.string.assist_no_screen_action)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistItem(icon: ImageVector, title: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (supporting != null) {
            { Text(supporting, color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The menu while it listens for a question: the microphone, which a tap finishes, and the words so far. */
@Composable
private fun VoiceAsk(level: Float, heard: String, onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val scale by animateFloatAsState(1f + level * ASSIST_MIC_PULSE, label = "mic")
        FilledIconButton(onClick = onStop, shape = CircleShape, modifier = Modifier.size(72.dp).scale(scale)) {
            Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.voice_input_mic))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            heard.ifBlank { stringResource(R.string.voice_input_listening) },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.assist_voice_done)) }
        }
    }
}

private fun assistVoiceErrorRes(error: AppDictation.Error): Int = when (error) {
    AppDictation.Error.NO_SPEECH -> R.string.voice_input_error_no_speech
    AppDictation.Error.NO_ENGINE -> R.string.voice_input_error_no_engine
    AppDictation.Error.PERMISSION -> R.string.voice_input_error_permission
    AppDictation.Error.MIC -> R.string.voice_input_error_mic
    AppDictation.Error.NETWORK -> R.string.voice_input_error_network
    AppDictation.Error.BUSY -> R.string.voice_input_error_busy
    AppDictation.Error.TRANSCRIBE -> R.string.voice_input_error_other
}

private const val ASSIST_MIC_PULSE = 0.18f
private const val ASSIST_SCRIM_ALPHA = 0.32f
