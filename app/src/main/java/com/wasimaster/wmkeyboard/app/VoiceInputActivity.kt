package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.sysvoice.AppDictation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The voice input window other apps open with
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (#1142): the microphone button
 * of a browser's search box, a notes app's dictation, a launcher's search. It
 * comes up over the app, starts listening at once, and hands the words back
 * when the speaker pauses, heard by the keyboard's own engines
 * ([AppDictation]).
 *
 * A small card at the bottom of the screen, as the system's own voice input
 * window is: what the app asked for (its prompt, else "Speak now"), the
 * microphone, which a tap finishes, and the words so far for engines that can
 * tell. Cancel and Back return nothing. An activity alias would not help
 * here: the **Voice input for other apps** setting switches this component
 * itself, with the recognition service.
 */
class VoiceInputActivity : ComponentActivity() {

    private var phase by mutableStateOf(Phase.STARTING)
    private var level by mutableStateOf(0f)
    private var partial by mutableStateOf("")
    private var error by mutableStateOf<AppDictation.Error?>(null)

    private var dictation: AppDictation? = null
    private lateinit var repository: SettingsRepository

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else fail(AppDictation.Error.PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        setResult(Activity.RESULT_CANCELED)
        val prompt = intent?.getStringExtra(RecognizerIntent.EXTRA_PROMPT).orEmpty()
        setContent {
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val settings = stored.value ?: return@setContent
            AppTheme(settings) {
                VoiceInputCard(
                    prompt = prompt,
                    phase = phase,
                    level = level,
                    partial = partial,
                    error = error,
                    onMic = ::onMic,
                    onCancel = ::finish,
                    onRetry = ::start,
                    onSetUp = ::openVoiceSettings,
                )
            }
        }
        // A rotation ends the request with the old window ([onDestroy]); the
        // new one starts its own.
        start()
    }

    override fun onStop() {
        super.onStop()
        // The window was left: the microphone does not stay open behind it.
        // Not while the permission prompt is up, which some phones draw as a
        // screen of its own.
        if (!isChangingConfigurations && (phase == Phase.LISTENING || phase == Phase.WORKING)) {
            dictation?.cancel()
            dictation = null
            finish()
        }
    }

    override fun onDestroy() {
        dictation?.cancel()
        dictation = null
        super.onDestroy()
    }

    private fun start() {
        error = null
        partial = ""
        level = 0f
        phase = Phase.STARTING
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun listen() {
        lifecycleScope.launch {
            val settings = repository.settings.first()
            if (isFinishing) return@launch
            val requestedTag = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            val pause = intent
                ?.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1L)
                ?.takeIf { it > 0 }
            dictation?.cancel()
            val session = AppDictation(
                this@VoiceInputActivity,
                settings,
                AppDictation.languagesFor(settings, requestedTag),
                requestedPauseMs = pause,
                forwardOnly = !settings.voiceBar.enabled,
                listener = object : AppDictation.Listener {
                    override fun onReady() {
                        phase = Phase.LISTENING
                    }

                    override fun onLevel(level: Float) {
                        this@VoiceInputActivity.level = level
                    }

                    override fun onPartial(text: String) {
                        partial = text
                    }

                    override fun onEndOfSpeech() {
                        phase = Phase.WORKING
                        level = 0f
                    }

                    override fun onResult(text: String) {
                        dictation = null
                        deliver(text)
                    }

                    override fun onError(error: AppDictation.Error) {
                        dictation = null
                        fail(error)
                    }
                },
            )
            dictation = session
            session.start()
        }
    }

    private fun onMic() {
        when (phase) {
            Phase.LISTENING -> dictation?.stop()
            Phase.FAILED -> start()
            else -> Unit
        }
    }

    private fun fail(reason: AppDictation.Error) {
        error = reason
        phase = Phase.FAILED
        level = 0f
    }

    /**
     * The words go back as the app asked for them: in the result, and through
     * its pending intent when it sent one (`EXTRA_RESULTS_PENDINGINTENT`,
     * which voice search uses to open its results).
     */
    private fun deliver(text: String) {
        val results = arrayListOf(text)
        val data = Intent()
            .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results)
            .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(1f))
        val pending = pendingResults()
        if (pending != null) {
            val fill = Intent()
            intent?.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)?.let(fill::putExtras)
            fill.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results)
            runCatching { pending.send(this, Activity.RESULT_OK, fill) }
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun pendingResults(): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT)
        }

    private fun openVoiceSettings() {
        startActivity(MainActivityContract.intent(this, "voice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Where the voice input window is in its one request. */
private enum class Phase { STARTING, LISTENING, WORKING, FAILED }

@Composable
private fun VoiceInputCard(
    prompt: String,
    phase: Phase,
    level: Float,
    partial: String,
    error: AppDictation.Error?,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSetUp: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val listening = phase == Phase.LISTENING
    val working = phase == Phase.WORKING
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = noRipple, indication = null, onClick = onCancel),
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
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    prompt.ifBlank { stringResource(R.string.voice_input_speak_now) },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                val scale by animateFloatAsState(if (listening) 1f + level * MIC_PULSE else 1f, label = "mic")
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(88.dp))
                    }
                    FilledIconButton(
                        onClick = onMic,
                        enabled = !working,
                        shape = CircleShape,
                        modifier = Modifier.size(72.dp).scale(scale),
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = stringResource(R.string.voice_input_mic))
                    }
                }
                Spacer(Modifier.height(16.dp))
                val line = when {
                    error != null -> stringResource(voiceInputErrorRes(error))
                    partial.isNotBlank() -> partial
                    working -> stringResource(R.string.voice_input_working)
                    listening -> stringResource(R.string.voice_input_listening)
                    else -> ""
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    when (error) {
                        null -> Unit
                        AppDictation.Error.NO_ENGINE -> TextButton(onClick = onSetUp) {
                            Text(stringResource(R.string.voice_input_set_up))
                        }
                        else -> TextButton(onClick = onRetry) { Text(stringResource(R.string.voice_input_retry)) }
                    }
                }
            }
        }
    }
}

private fun voiceInputErrorRes(error: AppDictation.Error): Int = when (error) {
    AppDictation.Error.NO_SPEECH -> R.string.voice_input_error_no_speech
    AppDictation.Error.NO_ENGINE -> R.string.voice_input_error_no_engine
    AppDictation.Error.PERMISSION -> R.string.voice_input_error_permission
    AppDictation.Error.MIC -> R.string.voice_input_error_mic
    AppDictation.Error.NETWORK -> R.string.voice_input_error_network
    AppDictation.Error.BUSY -> R.string.voice_input_error_busy
    AppDictation.Error.TRANSCRIBE -> R.string.voice_input_error_other
}

/** How much the microphone grows at full level. */
private const val MIC_PULSE = 0.18f

/** The dim over the app behind the card. */
private const val SCRIM_ALPHA = 0.32f
