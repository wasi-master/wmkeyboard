package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipNext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.media.hasNotificationAccess
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.delay
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * Media-control tool: album art, track metadata, a seek bar and the play /
 * pause / skip transport for whatever app currently owns a media session —
 * the same thing the lock screen shows. The service owns the
 * [com.wasimaster.wmkeyboard.core.media.MediaControlManager]; this panel only
 * renders [KeyboardUiState.mediaControl] and reports taps.
 *
 * Reading another app's session needs a notification-listener grant, which
 * the panel checks itself (mirroring how [VoicePanel] checks the mic
 * permission) and prompts for when missing.
 */
@Composable
internal fun MediaControlPanel(
    state: KeyboardUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onRequestAccess: () -> Unit,
    onResume: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    // The grant is toggled in system Settings, in another task; re-check when
    // the keyboard comes back to the foreground, and re-bind the session then
    // (the service could not start listening while access was still off).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = hasNotificationAccess(context)
                if (hasAccess) onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val track = state.mediaControl
        // The ring's one region: the grant button, or the transport. The seek
        // bar stays touch-only. Activation honors canPrev/canNext, so Enter
        // is never stronger than a tap on a dimmed button.
        PanelFocusTarget(
            panel = PanelMode.MEDIA_CONTROL,
            region = FocusRegion.RESULTS,
            count = when {
                !hasAccess -> 1
                track == null -> 0
                else -> 3
            },
            columns = 3,
        ) { index ->
            val t = state.mediaControl
            when {
                !hasAccess -> onRequestAccess()
                t == null -> Unit
                index == 0 -> if (t.canPrev) onPrevious()
                index == 1 -> onPlayPause()
                index == 2 -> if (t.canNext) onNext()
            }
        }
        val focused = state.focusedIndex(FocusRegion.RESULTS)
        when {
            !hasAccess -> AccessPrompt(kb, onRequestAccess, focused == 0)
            track == null -> Notice(
                kb,
                Icons.Outlined.MusicNote,
                stringResource(R.string.ime_media_control_empty_title),
                stringResource(R.string.ime_media_control_empty_body),
            )
            else -> NowPlaying(kb, track, onPlayPause, onNext, onPrevious, onSeek, focused)
        }
    }
}

/**
 * One playing track, drawn: art, titles, seek bar, transport. Internal rather
 * than private because the KDE Connect panel draws a computer's player with it
 * too (#285) — a [MediaSnapshot] is plain data, and says nothing about where
 * the music is.
 */
@Composable
internal fun NowPlaying(
    kb: KbTheme,
    track: MediaSnapshot,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    focused: Int? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // The art is square, so on a full-bleed (tall) panel taking the whole
    // height left the metadata and the transport a sliver of width — the
    // title ellipsised after two words and the skip buttons ran off the
    // edge. Cap the square against the width as well, and against a flat
    // maximum so a tall keyboard doesn't become an album cover.
    val artSide = minOf(maxHeight, maxWidth * 0.4f, 176.dp)
    val motion = LocalKbTheme.current.motion.any
    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art (or a placeholder note).
        Box(
            modifier = Modifier
                .size(artSide)
                .clip(RoundedCornerShape(12.dp))
                .background(kb.chip),
            contentAlignment = Alignment.Center,
        ) {
            // Cross-faded on the track, not on the bitmap: a session hands
            // over a fresh Bitmap with every playback-state change, and the
            // art must not fade into itself on each one. Art arriving after
            // the title is a change too.
            MotionCrossfade(
                target = TrackArt(TrackArtKey(track.title, track.artist, track.album, track.art != null), track.art),
                animate = motion,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                contentKey = { it.key },
                label = "trackArt",
            ) { shown ->
                val art = shown.art
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = kb.secondaryText,
                        modifier = Modifier.size(artSide * 0.3f),
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val unknownTitle = stringResource(R.string.ime_media_control_unknown_title)
            // Third line: album, or the source app when there's no album so the
            // user still sees where the audio is coming from.
            val lines = TrackLines(
                title = track.title.ifBlank { unknownTitle },
                subtitle = track.artist.ifBlank { track.appLabel },
                third = track.album.ifBlank {
                    if (track.artist.isNotBlank()) track.appLabel else ""
                },
            )
            // A new track's names fade in over the old; the seek bar and the
            // transport below stay put.
            MotionCrossfade(lines, animate = motion, label = "trackLines") { shown ->
                Column {
                    Text(
                        shown.title,
                        // The title is on the board, not on a key: a theme whose keys
                        // invert the board draws keyText invisible here.
                        color = kb.suggestionText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (shown.subtitle.isNotBlank()) {
                        Text(
                            shown.subtitle,
                            color = kb.secondaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (shown.third.isNotBlank()) {
                        Text(
                            shown.third,
                            color = kb.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(0.8f),
                        )
                    }
                }
            }

            if (track.durationMs > 0L) {
                Spacer(Modifier.height(8.dp))
                SeekBar(kb, track, onSeek, smooth = motion)
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaButton(
                    kb, Icons.Outlined.SkipPrevious,
                    stringResource(R.string.ime_media_control_previous_desc),
                    size = 44.dp, enabled = track.canPrev, focused = focused == 0,
                    disabledReason = DisabledReason(stringResource(R.string.ime_disabled_media_app_blocks)),
                    onClick = onPrevious,
                )
                MediaButton(
                    kb,
                    if (track.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (track.playing) {
                        stringResource(R.string.ime_media_control_pause_desc)
                    } else {
                        stringResource(R.string.ime_media_control_play_desc)
                    },
                    size = 56.dp, accent = true, focused = focused == 1, onClick = onPlayPause,
                )
                MediaButton(
                    kb, Icons.Outlined.SkipNext,
                    stringResource(R.string.ime_media_control_next_desc),
                    size = 44.dp, enabled = track.canNext, focused = focused == 2,
                    disabledReason = DisabledReason(stringResource(R.string.ime_disabled_media_app_blocks)),
                    onClick = onNext,
                )
            }
        }
    }
    }
}

/** The names a track is drawn with; a change in any is a new track to fade to. */
private data class TrackLines(val title: String, val subtitle: String, val third: String)

/** What the art cross-fades on: the track, and whether it has art yet. */
private data class TrackArtKey(val title: String, val artist: String, val album: String, val hasArt: Boolean)

/** The art to draw, carrying its own key so an outgoing state keeps the key it came with. */
private data class TrackArt(val key: TrackArtKey, val art: android.graphics.Bitmap?)

@Composable
private fun SeekBar(kb: KbTheme, track: MediaSnapshot, onSeek: (Long) -> Unit, smooth: Boolean) {
    // Live position: extrapolate from the last stamp while playing so the bar
    // creeps forward between the sparse state updates the session sends.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(track.playing, track.positionMs, track.positionUpdateTimeMs) {
        now = SystemClock.elapsedRealtime()
        while (track.playing) {
            delay(seekTickMs(track, barWidthPx, smooth))
            now = SystemClock.elapsedRealtime()
        }
    }
    val livePosition = if (track.playing) {
        track.positionMs + ((now - track.positionUpdateTimeMs) * track.speed).toLong()
    } else {
        track.positionMs
    }.coerceIn(0L, track.durationMs)
    val playedFraction = (livePosition.toFloat() / track.durationMs).coerceIn(0f, 1f)
    val shown = if (scrubbing) scrubFraction else playedFraction

    Column(Modifier.fillMaxWidth()) {
        // Held while greyed out, says why: the playing app turned seeking off.
        val seekReason = DisabledReason(stringResource(R.string.ime_disabled_media_seek)).takeUnless { track.canSeek }
        IconHint(null, Modifier.fillMaxWidth(), disabledReason = seekReason) {
            Slider(
                value = shown,
                onValueChange = { scrubbing = true; scrubFraction = it },
                onValueChangeFinished = {
                    onSeek((scrubFraction * track.durationMs).toLong())
                    scrubbing = false
                },
                enabled = track.canSeek,
                colors = SliderDefaults.colors(
                    thumbColor = kb.accent,
                    activeTrackColor = kb.accent,
                    inactiveTrackColor = kb.divider,
                    disabledThumbColor = kb.secondaryText,
                    disabledActiveTrackColor = kb.secondaryText,
                    disabledInactiveTrackColor = kb.divider,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .onSizeChanged { barWidthPx = it.width },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime((shown * track.durationMs).toLong()),
                color = kb.secondaryText, fontSize = 10.sp,
            )
            Text(formatTime(track.durationMs), color = kb.secondaryText, fontSize = 10.sp)
        }
    }
}

/**
 * How often the bar's position is recomputed while playing. With motion on,
 * once per pixel the thumb would travel, so a short clip glides at the frame
 * rate and a long track does not recompose sixty times a second to stand
 * still; off, the old half-second step.
 */
private fun seekTickMs(track: MediaSnapshot, barWidthPx: Int, smooth: Boolean): Long {
    if (!smooth || barWidthPx <= 0 || track.durationMs <= 0L) return SEEK_STEP_MS
    val speed = kotlin.math.abs(track.speed).coerceAtLeast(MIN_SPEED)
    val msPerPixel = (track.durationMs / (barWidthPx * speed)).toLong()
    return msPerPixel.coerceIn(SEEK_FRAME_MS, SEEK_STEP_MS)
}

private const val SEEK_STEP_MS = 500L
private const val SEEK_FRAME_MS = 16L
private const val MIN_SPEED = 0.1f

@Composable
private fun MediaButton(
    kb: KbTheme,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    accent: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = false,
    // Held while greyed out, says why (and names the button); see [IconHint].
    disabledReason: DisabledReason? = null,
    onClick: () -> Unit,
) {
    val reason = disabledReason.takeUnless { enabled }
    IconHint(description.takeIf { reason != null }, disabledReason = reason) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (accent) kb.accent else kb.toolCircle)
                .focusRing(focused, CircleShape)
                .alpha(if (enabled) 1f else 0.35f)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (accent) kb.toolCircleActiveIcon else kb.toolbarIcon
            if (LocalKbTheme.current.motion.any) {
                // Play turning into pause shrinks one glyph out as the other grows
                // in; the button itself, and its tap, never move.
                AnimatedContent(
                    targetState = icon,
                    transitionSpec = {
                        (fadeIn(tween(ICON_SWAP_MS)) + scaleIn(tween(ICON_SWAP_MS), initialScale = ICON_SWAP_SCALE)) togetherWith
                            (fadeOut(tween(ICON_SWAP_OUT_MS)) + scaleOut(tween(ICON_SWAP_OUT_MS), targetScale = ICON_SWAP_SCALE)) using
                            null
                    },
                    contentAlignment = Alignment.Center,
                    label = "mediaIcon",
                ) { shown ->
                    Icon(shown, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
                }
            } else {
                Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

private const val ICON_SWAP_MS = 150
private const val ICON_SWAP_OUT_MS = 100
private const val ICON_SWAP_SCALE = 0.6f

@Composable
private fun AccessPrompt(kb: KbTheme, onRequestAccess: () -> Unit, focused: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ime_media_control_access_body),
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(kb.keyRadiusDp.dp))
                .background(kb.accent)
                .focusRing(focused, RoundedCornerShape(kb.keyRadiusDp.dp))
                .clickable { onRequestAccess() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.ime_media_control_access_action),
                color = kb.toolCircleActiveIcon,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Notice(
    kb: KbTheme,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(title, color = kb.keyText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            color = kb.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** Milliseconds → m:ss (or h:mm:ss for long content). */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
