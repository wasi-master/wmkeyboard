package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.TelegramFeedbackRoute
import com.wasimaster.wmkeyboard.core.support.Support
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.URLEncoder
import kotlin.math.sqrt

/**
 * The settings app's feedback form (#1242): what kind of report, a few words,
 * and the screen it was about, sent as a GitHub issue or to the Telegram
 * group, or both one after the other.
 *
 * Opened from About › Feedback › Send feedback, and, when the user turns them
 * on there, by shaking the phone or taking a screenshot while this app is in
 * front. Nothing is sent from here: every route hands the report to another
 * app (the browser, Telegram) where the user reads it before it goes.
 */
internal object FeedbackForm {
    /** The form to open, with the screen it was opened over; null while closed. */
    val pending = MutableStateFlow<FeedbackRequest?>(null)

    fun open(screenshot: File? = null) {
        if (pending.value == null) pending.value = FeedbackRequest(screenshot)
    }
}

internal data class FeedbackRequest(val screenshot: File?)

/** What a report is, which decides its GitHub template and its Telegram topic. */
internal enum class FeedbackKind(
    @StringRes val labelRes: Int,
    /** The topic in the @wmkeyboard group this kind belongs in. */
    val topicId: Int,
    val hashtag: String,
) {
    BUG(R.string.feedback_kind_bug, topicId = 12, hashtag = "#bug"),
    IDEA(R.string.feedback_kind_idea, topicId = 11, hashtag = "#idea"),
    QUESTION(R.string.feedback_kind_question, topicId = 8, hashtag = "#question"),
}

/** Above this many g, a jolt counts towards a shake. */
private const val SHAKE_G = 2.6f

/** Two jolts this close together make a shake. */
private const val SHAKE_WINDOW_MS = 700L

/** After a shake opened the form, the next is ignored for this long. */
private const val SHAKE_COOLDOWN_MS = 3_000L

/**
 * Listens for the two shortcuts while the settings app is in front, and draws
 * the form when something asks for it. Once, at the top of the settings
 * activity's content.
 */
@Composable
internal fun FeedbackFormHost(settings: LiveSettings) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val shake = settings.watch { it.feedbackShortcuts.shake }
    val screenshot = settings.watch { it.feedbackShortcuts.screenshot }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    if (activity != null && shake) {
        DisposableEffect(activity, lifecycle) {
            val sensors = activity.getSystemService(SensorManager::class.java)
            val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            var lastJolt = 0L
            var lastShake = 0L
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                    if (g < SHAKE_G) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShake < SHAKE_COOLDOWN_MS) return
                    if (now - lastJolt <= SHAKE_WINDOW_MS) {
                        lastShake = now
                        lastJolt = 0L
                        openOverScreen(activity)
                    } else {
                        lastJolt = now
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            // Only while the app is in front: a sensor kept running behind
            // another app costs battery and opens nothing anyone can see.
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME ->
                        accel?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                    Lifecycle.Event.ON_PAUSE -> sensors?.unregisterListener(listener)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                sensors?.unregisterListener(listener)
            }
        }
    }

    // Android 14 tells an app that a screenshot was taken of it, not what it
    // shows; the form takes its own picture of the same screen.
    if (activity != null && screenshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        DisposableEffect(activity, lifecycle) {
            val callback = Activity.ScreenCaptureCallback { openOverScreen(activity) }
            var registered = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (!registered) {
                        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
                        registered = true
                    }
                    Lifecycle.Event.ON_STOP -> if (registered) {
                        activity.unregisterScreenCaptureCallback(callback)
                        registered = false
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                if (registered) activity.unregisterScreenCaptureCallback(callback)
            }
        }
    }

    val request by FeedbackForm.pending.collectAsStateWithLifecycle()
    request?.let { open ->
        FeedbackDialog(
            request = open,
            telegramRoute = settings.watch { it.feedbackShortcuts.telegramRoute },
            onDismiss = { FeedbackForm.pending.value = null },
        )
    }
}

/** Takes a picture of the app as it is, then opens the form over it. */
private fun openOverScreen(activity: Activity) {
    if (FeedbackForm.pending.value != null) return
    captureWindow(activity) { file -> FeedbackForm.open(file) }
}

/**
 * The activity's window as a PNG in the cache, or null when it could not be
 * drawn. PixelCopy where there is one, since it reads what is actually on the
 * screen, hardware layers and all; the view's own drawing before Android 8.
 */
private fun captureWindow(activity: Activity, onDone: (File?) -> Unit) {
    val decor = activity.window?.decorView
    if (decor == null || decor.width <= 0 || decor.height <= 0) {
        onDone(null)
        return
    }
    fun save(bitmap: Bitmap?): File? = bitmap?.let {
        runCatching {
            val dir = File(activity.cacheDir, "feedback").apply { mkdirs() }
            // One picture at a time is ever in use; older ones go.
            dir.listFiles()?.forEach(File::delete)
            File(dir, "screen-${System.currentTimeMillis()}.png").also { file ->
                file.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
            }
        }.getOrNull()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(activity.window, bitmap, { result ->
                onDone(if (result == PixelCopy.SUCCESS) save(bitmap) else null)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { onDone(save(runCatching { decor.drawToBitmap() }.getOrNull())) }
    } else {
        onDone(save(runCatching { decor.drawToBitmap() }.getOrNull()))
    }
}

@Composable
private fun FeedbackDialog(
    request: FeedbackRequest,
    telegramRoute: TelegramFeedbackRoute,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(FeedbackKind.BUG) }
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(request.screenshot != null) }
    val preview = remember(request.screenshot) {
        request.screenshot?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }
    }
    val currentRoute by rememberUpdatedState(telegramRoute)
    val picture = request.screenshot?.takeIf { attach }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in FeedbackKind.entries) {
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(stringResource(k.labelRes)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.feedback_text_label)) },
                    placeholder = {
                        Text(
                            stringResource(
                                when (kind) {
                                    FeedbackKind.BUG -> R.string.feedback_text_hint_bug
                                    FeedbackKind.IDEA -> R.string.feedback_text_hint_idea
                                    FeedbackKind.QUESTION -> R.string.feedback_text_hint_question
                                },
                            ),
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preview != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = attach, onCheckedChange = { attach = it })
                        Text(
                            stringResource(R.string.feedback_attach_screenshot),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (attach) {
                        Image(
                            preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.feedback_screenshot_desc),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        )
                    }
                }
                CaptionText(stringResource(R.string.feedback_send_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sendToGitHub(context, kind, text, picture) }) {
                        Text(stringResource(R.string.feedback_send_github))
                    }
                    OutlinedButton(onClick = { sendToTelegram(context, kind, text, picture, currentRoute) }) {
                        Text(stringResource(R.string.feedback_send_telegram))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** The report's first line, cut to a title's length. */
private fun titleOf(text: String): String =
    text.trim().lineSequence().firstOrNull().orEmpty().take(70)

/**
 * A new issue in the right template, with the fields filled in through the
 * address (GitHub fills an issue form's fields from query parameters named
 * after their ids). A picture cannot travel in an address, so it is saved to
 * the gallery to attach from there.
 */
private fun sendToGitHub(context: Context, kind: FeedbackKind, text: String, screenshot: File?) {
    val environment = Support.environment() + "\n\nSent from the app's feedback form."
    val url = when (kind) {
        FeedbackKind.BUG -> "${Support.ISSUES_URL}/new?template=bug_report.yml" +
            "&title=" + enc("[BUG]: " + titleOf(text)) +
            "&bug-description=" + enc(text) +
            "&additional-information=" + enc(environment)
        FeedbackKind.IDEA -> "${Support.ISSUES_URL}/new?template=feature_request.yml" +
            "&title=" + enc("[FEAT]: " + titleOf(text)) +
            "&description=" + enc(text) +
            "&additional-information=" + enc(environment)
        FeedbackKind.QUESTION -> "${Support.ISSUES_URL}/new" +
            "?title=" + enc("Question: " + titleOf(text)) +
            "&body=" + enc(text + "\n\n---\n" + environment)
    }
    if (screenshot != null) {
        val saved = saveToGallery(context, screenshot)
        Toast.makeText(
            context,
            context.getString(if (saved) R.string.feedback_screenshot_saved else R.string.feedback_screenshot_not_saved),
            Toast.LENGTH_LONG,
        ).show()
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.feedback_no_browser), Toast.LENGTH_LONG).show()
    }
}

/**
 * To the group, the way [route] says: Telegram's own picker with the text and
 * the picture, or the topic for the report's kind with the text copied.
 */
private fun sendToTelegram(
    context: Context,
    kind: FeedbackKind,
    text: String,
    screenshot: File?,
    route: TelegramFeedbackRoute,
) {
    val message = buildString {
        append(kind.hashtag)
        append('\n')
        append(text.trim())
        append("\n\n")
        append(Support.environment())
    }
    when (route) {
        TelegramFeedbackRoute.SHARE -> {
            val send = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, message)
                if (screenshot != null) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.clipboard", screenshot)
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(null, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
            }
            // Telegram's own app first; any other Telegram client through the chooser.
            val telegram = Intent(send).setPackage(TELEGRAM_PACKAGE)
            val target = if (telegram.resolveActivity(context.packageManager) != null) {
                telegram
            } else {
                Intent.createChooser(send, context.getString(R.string.feedback_send_telegram))
            }
            runCatching { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure {
                    Toast.makeText(context, context.getString(R.string.feedback_no_telegram), Toast.LENGTH_LONG).show()
                }
        }
        TelegramFeedbackRoute.TOPIC -> {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.feedback_title), message))
            val saved = screenshot != null && saveToGallery(context, screenshot)
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.feedback_topic_copied_with_picture else R.string.feedback_topic_copied),
                Toast.LENGTH_LONG,
            ).show()
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("${Support.TELEGRAM_URL}/${kind.topicId}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.feedback_no_browser), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private const val TELEGRAM_PACKAGE = "org.telegram.messenger"

/**
 * A copy of the picture in Pictures/WM Keyboard, for a browser or Telegram's
 * own attach button to pick. Android 10 and later only: before that, writing
 * to the shared pictures folder needs a storage permission this app does not
 * ask for.
 */
private fun saveToGallery(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WM Keyboard")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
        true
    }.getOrDefault(false)
}
