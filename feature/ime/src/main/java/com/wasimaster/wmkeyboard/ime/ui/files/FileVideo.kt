package com.wasimaster.wmkeyboard.ime.ui.files

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeUp
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.gallery.MediaThumb
import com.wasimaster.wmkeyboard.ime.ui.gallery.OnPhoto
import com.wasimaster.wmkeyboard.ime.ui.gallery.PhotoBackdrop
import com.wasimaster.wmkeyboard.ime.ui.gallery.rememberGalleryImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One video's player. Everything that can block (opening the file, reading
 * its headers) runs on a background thread, so a slow or broken file never
 * holds the keyboard up; the picture goes to a [TextureView], which draws in
 * the keyboard's own layers like any other view.
 */
@Stable
private class VideoPlayer {
    var player: MediaPlayer? = null
    var surface: Surface? = null
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var started by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var position by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)
    var aspect by mutableFloatStateOf(0f)

    fun attach(texture: SurfaceTexture) {
        surface?.release()
        surface = Surface(texture).also { s -> runCatching { player?.setSurface(s) } }
    }

    fun detach() {
        runCatching { player?.setSurface(null) }
        surface?.release()
        surface = null
    }

    fun toggle() {
        val p = player ?: return
        if (!ready) return
        runCatching { if (p.isPlaying) p.pause() else p.start() }
        playing = runCatching { p.isPlaying }.getOrDefault(false)
        if (playing) started = true
    }

    fun seek(fraction: Float) {
        val p = player ?: return
        if (!ready || duration <= 0) return
        val to = (fraction.coerceIn(0f, 1f) * duration).toInt()
        position = to
        started = true
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                p.seekTo(to.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                p.seekTo(to)
            }
        }
    }

    fun toggleMute() {
        muted = !muted
        val v = if (muted) 0f else 1f
        runCatching { player?.setVolume(v, v) }
    }

    fun release() {
        val p = player
        player = null
        ready = false
        playing = false
        runCatching { p?.release() }
        surface?.release()
        surface = null
    }
}

/**
 * A video (4A): its first frame until it plays, then the film, at its own
 * shape. A tap plays or pauses; the bar under it seeks, and the sound can be
 * turned off. Nothing plays until asked, so opening a clip never starts sound
 * in the middle of a chat.
 */
@Composable
internal fun VideoBody(entry: FileEntry) {
    val context = LocalContext.current
    val kb = LocalKbTheme.current
    val video = remember(entry.path) { VideoPlayer() }
    val scope = rememberCoroutineScope()
    var controls by remember(entry.path) { mutableStateOf(true) }

    DisposableEffect(entry.path) {
        val job = scope.launch {
            // Made off the main thread: opening a document and reading its
            // headers can take a while, and a broken file can hang there.
            var made: MediaPlayer? = null
            try {
                made = withContext(Dispatchers.IO) {
                    runCatching {
                        MediaPlayer().also { p ->
                            made = p
                            p.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build(),
                            )
                            p.setDataSource(context.applicationContext, DocFiles.uriOf(context, entry.path))
                            p.prepare()
                        }
                    }.onFailure { runCatching { made?.release() } }.getOrNull()
                }
            } catch (gone: kotlinx.coroutines.CancellationException) {
                // Left before it was ready: nothing would release it later.
                runCatching { made?.release() }
                throw gone
            }
            val prepared = made
            if (prepared == null) {
                video.failed = true
                return@launch
            }
            video.player = prepared
            prepared.isLooping = true
            prepared.setOnCompletionListener { video.playing = false }
            prepared.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) video.aspect = w.toFloat() / h }
            prepared.setOnErrorListener { _, _, _ ->
                video.failed = true
                video.playing = false
                true
            }
            video.duration = prepared.duration.coerceAtLeast(0)
            if (prepared.videoWidth > 0 && prepared.videoHeight > 0) video.aspect = prepared.videoWidth.toFloat() / prepared.videoHeight
            video.surface?.let { runCatching { prepared.setSurface(it) } }
            video.ready = true
        }
        onDispose {
            job.cancel()
            video.release()
        }
    }

    // The clock while it plays; the controls fade after a few quiet seconds.
    LaunchedEffect(video.playing) {
        while (video.playing) {
            video.position = runCatching { video.player?.currentPosition ?: 0 }.getOrDefault(video.position)
            delay(POSITION_TICK_MS)
        }
    }
    LaunchedEffect(video.playing, controls) {
        if (video.playing && controls) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PhotoBackdrop)
            .pointerInput(entry.path) {
                detectTapGestures(onTap = {
                    if (!video.playing || controls) video.toggle()
                    controls = true
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = if (video.aspect > 0f) Modifier.aspectRatio(video.aspect) else Modifier.fillMaxSize()
        Box(shape, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = video.attach(texture)
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                video.detach()
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // The first frame stands in until the film has started.
            if (!video.started) {
                val thumb = remember(entry.path) { MediaThumb(DocFiles.uriOf(context, entry.path), true, POSTER_PX, entry.modifiedMs / 1000) }
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    imageLoader = rememberGalleryImageLoader(),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        when {
            video.failed -> Text(
                stringResource(R.string.ime_files_video_failed),
                color = OnPhoto,
                fontSize = 13.sp,
                modifier = Modifier.padding(24.dp),
            )
            !video.ready -> CircularProgressIndicator(Modifier.size(28.dp), color = OnPhoto, strokeWidth = 2.dp)
            !video.playing || controls -> Box(
                Modifier.size(60.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (video.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(if (video.playing) R.string.ime_files_pause else R.string.ime_files_play),
                    tint = OnPhoto,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        if (video.ready && (!video.playing || controls)) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(clockOf(video.position), color = OnPhoto, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                SeekBar(
                    fraction = if (video.duration > 0) video.position.toFloat() / video.duration else 0f,
                    colour = kb.accent,
                    track = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.weight(1f),
                    onSeek = { f ->
                        controls = true
                        video.seek(f)
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(clockOf(video.duration), color = OnPhoto, fontSize = 11.sp)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures { video.toggleMute(); controls = true } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (video.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                        contentDescription = stringResource(if (video.muted) R.string.ime_files_sound_on else R.string.ime_files_sound_off),
                        tint = OnPhoto,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * A bar to scrub through a recording or a film: tap a place or drag along
 * it. It takes the drag for itself, so sliding along it seeks rather than
 * swiping to the next file.
 */
@Composable
internal fun SeekBar(
    fraction: Float,
    colour: Color,
    track: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    onSeek: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val knob = 12.dp
    BoxWithConstraints(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = it.x / size.width },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track)) {
            Box(Modifier.width(width * shown).height(height).background(colour))
        }
        Box(
            Modifier
                .offset(x = width * shown - knob / 2)
                .size(knob)
                .clip(CircleShape)
                .background(colour),
        )
    }
}

internal fun clockOf(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private const val POSITION_TICK_MS = 200L
private const val CONTROLS_HIDE_MS = 2500L
private const val POSTER_PX = 720
