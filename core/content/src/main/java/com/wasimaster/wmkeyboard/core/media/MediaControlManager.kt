package com.wasimaster.wmkeyboard.core.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat

/**
 * One frame of "what's playing" for the media-control panel. Everything the
 * UI needs is pre-digested here so the panel never touches a framework
 * [MediaController]. [positionMs] is only valid as of [positionUpdateTimeMs]
 * (an [android.os.SystemClock.elapsedRealtime] stamp); while [playing], the
 * panel extrapolates the live position from those two plus [speed].
 */
data class MediaSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    /**
     * The app that owns the session. Kept alongside the label because the
     * toolbar's auto-pin matches against a package allowlist, and a label is
     * localised, renameable and not unique.
     */
    val packageName: String,
    val appLabel: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val positionUpdateTimeMs: Long,
    val speed: Float,
    val canNext: Boolean,
    val canPrev: Boolean,
    val canSeek: Boolean,
)

/**
 * Reads and drives whatever app currently owns a media session — the same
 * transport the lock screen and quick-settings tile show. One instance lives
 * in the service; [start] streams a [MediaSnapshot] (or null, meaning nothing
 * is playing) to [onUpdate] whenever the chosen session's metadata or
 * playback state changes, and the transport methods steer it.
 *
 * All of this is gated on the app holding an enabled notification listener
 * ([MediaNotificationListener]); without it the platform refuses to hand over
 * the sessions. Callbacks are delivered on the main thread.
 */
class MediaControlManager(private val context: Context) {

    private val sessionManager: MediaSessionManager? by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    private val component by lazy { ComponentName(context, MediaNotificationListener::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var onUpdate: ((MediaSnapshot?) -> Unit)? = null
    private var controller: MediaController? = null

    /**
     * Whether the sessions listener is registered right now.
     *
     * [start] is called from two independent reasons to be tracking — the
     * panel being open, and the toolbar's auto-pin watching for playback — so
     * it can arrive while already running. Registering the same listener twice
     * would leave one behind on [stop].
     */
    private var listening = false

    /** True once the user has ticked this app in Settings › Notification access. */
    fun hasAccess(): Boolean = hasNotificationAccess(context)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { rebind(it.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onSessionDestroyed() = rebind(activeSessions())
    }

    /**
     * Begins tracking the active session. Safe to call repeatedly — it rebinds
     * to the current session each time (used when the user returns from
     * granting notification access). No-op without access.
     */
    fun start(onUpdate: (MediaSnapshot?) -> Unit) {
        this.onUpdate = onUpdate
        val manager = sessionManager ?: run { onUpdate(null); return }
        if (!hasAccess()) { onUpdate(null); return }
        if (listening) {
            // Already tracking — hand the new callback the current frame
            // rather than registering a second listener.
            emit()
            return
        }
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            listening = true
            rebind(activeSessions())
        } catch (_: SecurityException) {
            // Access revoked between the check and the call.
            onUpdate(null)
        }
    }

    /** Stops tracking and drops the callback. */
    fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        controller?.unregisterCallback(controllerCallback)
        controller = null
        onUpdate = null
    }

    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext() ?: Unit
    fun previous() = controller?.transportControls?.skipToPrevious() ?: Unit
    fun seekTo(positionMs: Long) = controller?.transportControls?.seekTo(positionMs) ?: Unit

    private fun activeSessions(): List<MediaController> =
        try {
            sessionManager?.getActiveSessions(component).orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** Choose the session to steer: whatever is playing, else the top-priority one. */
    private fun rebind(sessions: List<MediaController>) {
        val chosen = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: sessions.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) {
            emit()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        chosen?.registerCallback(controllerCallback, handler)
        emit()
    }

    private fun emit() {
        onUpdate?.invoke(controller?.let(::snapshot))
    }

    private fun snapshot(c: MediaController): MediaSnapshot? {
        val metadata = c.metadata
        val state = c.playbackState
        // A live controller with neither is a session that has registered but
        // published nothing yet — treat it as "nothing playing".
        if (metadata == null && state == null) return null
        val actions = state?.actions ?: 0L
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return MediaSnapshot(
            title = metadata?.text(
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
            ).orEmpty(),
            artist = metadata?.text(
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
            ).orEmpty(),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            packageName = c.packageName.orEmpty(),
            appLabel = appLabel(c.packageName),
            art = metadata?.bitmap(
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
            ),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = duration.coerceAtLeast(0L),
            positionUpdateTimeMs = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed ?: 1f,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSeek = duration > 0L && actions and PlaybackState.ACTION_SEEK_TO != 0L,
        )
    }

    private fun appLabel(packageName: String?): String {
        packageName ?: return ""
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * Whether this app currently holds an enabled notification listener — the
 * grant the media-control tool needs. Shared by the engine and the panel
 * (which re-checks it after the user returns from Settings).
 */
fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)

/** First of [keys] that holds a non-blank string. */
private fun MediaMetadata.text(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { getString(it)?.takeIf(String::isNotBlank) }

/** First of [keys] that carries a bitmap. */
private fun MediaMetadata.bitmap(vararg keys: String): Bitmap? =
    keys.firstNotNullOfOrNull { getBitmap(it) }
