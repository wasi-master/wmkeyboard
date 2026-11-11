package com.wasimaster.wmkeyboard.ime.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ShareSheetSettings
import com.wasimaster.wmkeyboard.core.share.ShareTarget
import com.wasimaster.wmkeyboard.core.share.ShareTargets
import com.wasimaster.wmkeyboard.ime.ClipboardWriteGate
import com.wasimaster.wmkeyboard.ime.KeyboardClipboard
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard half of the share sheet (#970): every share the keyboard
 * makes comes here, and opens a panel of the apps that take it (pinned and
 * recent first, a search over the rest) with Copy, Open and Insert beside
 * them, the way Firefox's sheet works. Android's own chooser is one button
 * away, and the only route when the setting is off or the keyboard is not
 * on screen to draw a panel in.
 *
 * A panel and not an activity: the keyboard is a window over someone else's
 * app, and an activity of its own would take the field's focus away and put
 * the keyboard down before the user had picked anything.
 */
internal class ShareController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val settings: () -> ShareSheetSettings,
    private val host: Host,
) {
    /** The keyboard doings the sheet needs. */
    interface Host {
        /** The keyboard is on screen and can open a panel. */
        fun canOpenPanel(): Boolean

        /** Opens the share panel; true when it is up. */
        fun openPanel(): Boolean

        /** Closes the share panel, back onto the panel the share came from when [back]. */
        fun closePanel(back: Boolean)

        fun insertText(text: String)

        fun haptic()

        fun toast(@StringRes message: Int)
    }

    private val context = context.applicationContext

    private val _state = MutableStateFlow(ShareUi())
    val state: StateFlow<ShareUi> = _state

    val callbacks = ShareCallbacks(state = state, onAction = ::dispatch, iconFor = ::iconFor)

    /** The share the panel is open for; null once it went somewhere or the panel closed. */
    private var pending: Intent? = null
    private var queryJob: Job? = null

    // Thirty-odd icons at 48 dp is well under a megabyte, and the cache goes
    // with the panel (see [onPanelClose]).
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE_SIZE)

    /**
     * Shares [send]: through the panel, or Android's chooser when the sheet
     * is off or there is no keyboard on screen to draw it in. [insertable]
     * when the share is text the field could take instead. True when
     * something opened.
     */
    fun share(send: Intent, insertable: Boolean = false): Boolean {
        if (!settings().enabled || !host.canOpenPanel()) return systemChooser(send)
        pending = send
        val preview = ShareTargets.preview(send)
        _state.value = ShareUi(
            preview = preview,
            loading = true,
            insertable = insertable && preview.text != null && preview.files == 0,
        )
        if (!host.openPanel()) {
            pending = null
            return systemChooser(send)
        }
        queryJob?.cancel()
        queryJob = scope.launch {
            val targets = withContext(Dispatchers.IO) { ShareTargets.query(context, send) }
            if (pending === send) _state.update { it.copy(targets = targets, loading = false) }
        }
        return true
    }

    /** The panel went away, by any way out: the share it was for goes with it. */
    fun onPanelClose() {
        queryJob?.cancel()
        queryJob = null
        pending = null
        icons.evictAll()
        _state.update { it.copy(held = null, targets = emptyList(), loading = false) }
    }

    private fun dispatch(action: ShareAction) {
        val send = pending
        when (action) {
            is ShareAction.Hold -> _state.update { it.copy(held = action.target) }
            is ShareAction.TogglePin -> {
                host.haptic()
                _state.update { it.copy(held = null) }
                scope.launch { repository.toggleSharePin(action.target.key) }
            }
            is ShareAction.ForgetRecent -> {
                host.haptic()
                _state.update { it.copy(held = null) }
                scope.launch { repository.removeShareRecent(action.target.key) }
            }
            ShareAction.Dismiss -> {
                host.haptic()
                host.closePanel(back = true)
            }
            is ShareAction.Pick -> if (send != null) pick(send, action.target)
            ShareAction.System -> if (send != null) {
                host.haptic()
                host.closePanel(back = false)
                systemChooser(send)
            }
            ShareAction.Copy -> if (send != null) copy(send)
            ShareAction.Open -> if (send != null) open(send)
            ShareAction.Insert -> {
                val text = _state.value.preview.text ?: return
                host.haptic()
                host.closePanel(back = false)
                host.insertText(text)
            }
        }
    }

    private fun pick(send: Intent, target: ShareTarget) {
        host.haptic()
        val started = runCatching { context.startActivity(ShareTargets.launchIntent(send, target)) }.isSuccess
        if (!started) {
            host.toast(R.string.ime_share_failed_toast)
            return
        }
        if (settings().recentsEnabled) scope.launch { repository.addShareRecent(target.key) }
        host.closePanel(back = false)
    }

    private fun copy(send: Intent) {
        val preview = _state.value.preview
        val text = preview.text
        val uri = preview.firstUri
        host.haptic()
        when {
            text != null -> KeyboardClipboard.copy(context, text)
            uri != null -> ClipboardWriteGate.writeUri(context, uri, "share")
            else -> return
        }
        host.toast(R.string.ime_share_copied_toast)
        host.closePanel(back = true)
    }

    /** The link in a browser, or the one file in whatever views its type. */
    private fun open(send: Intent) {
        val preview = _state.value.preview
        val link = preview.link
        val uri = preview.firstUri
        val view = when {
            link != null -> Intent(Intent.ACTION_VIEW, Uri.parse(link))
            uri != null && preview.files == 1 -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, send.type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(null, uri) }
            else -> return
        }
        host.haptic()
        val started = runCatching { context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (started) host.closePanel(back = false) else host.toast(R.string.ime_share_failed_toast)
    }

    /** Android's own share sheet for [send], the way every share went before the panel. */
    private fun systemChooser(send: Intent): Boolean {
        val chooser = Intent.createChooser(send, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private suspend fun iconFor(target: ShareTarget): ImageBitmap? {
        icons.get(target.key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val px = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                context.packageManager.getActivityIcon(ComponentName(target.packageName, target.activityName))
                    .toBitmap(px, px)
                    .asImageBitmap()
            }.getOrNull()
        } ?: return null
        icons.put(target.key, bitmap)
        return bitmap
    }

    private companion object {
        const val ICON_CACHE_SIZE = 64
        const val ICON_SIZE_DP = 48
    }
}
