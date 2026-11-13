package com.wasimaster.wmkeyboard.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wasimaster.wmkeyboard.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.isActive

/**
 * The keycap catcher: the mini game behind seven taps on the version row.
 *
 * Full screen and immersive: the system bars hide while it is open and come
 * back when it closes, and the screen stays on during a round. Keycaps fall
 * from the top; a spacebar at the bottom follows the finger anywhere on the
 * screen. A word to spell sits at the top, and catching its letters in order
 * pays a bonus when it is complete. Catches in a row build a multiplier, and
 * a few special keys fall among the letters:
 *
 * - ↵ is worth five letters,
 * - ♥ gives a life back (up to [MAX_LIVES]),
 * - ␣ widens the spacebar for a few seconds,
 * - ⌫ is the one to dodge: catching it costs a life.
 *
 * A letter that hits the floor costs a life; the special keys do not. The
 * level rises with the score and brings faster keys and more of them.
 *
 * Not a settings screen at all, so it draws its own chrome instead of the
 * scaffold: a game cannot live inside the scaffold's scrolling column, and a
 * title bar is exactly what full screen is meant to lose.
 */
@Composable
internal fun KeycapCatcherScreen(onBack: () -> Unit) {
    ImmersiveWhileShown()
    KeycapCatcherGame(onClose = onBack, modifier = Modifier.fillMaxSize())
}

/** Hides the system bars and keeps the screen on for as long as the game is composed. */
@Composable
private fun ImmersiveWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.hostActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

private enum class GamePhase { READY, PLAYING, PAUSED, OVER }

private enum class CapKind { LETTER, ENTER, HEART, SPACE, BACKSPACE }

/** One falling keycap. Plain vars: the frame clock, not the snapshot system, drives it. */
private class FallingCap(
    val kind: CapKind,
    val label: String,
    val xFrac: Float,
    var y: Float,
    val speed: Float,
    val spin: Float,
    val phase: Float,
)

/** A spark from a catch, or a chip of a lost key. */
private class Spark(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val color: Color,
)

/** A score that floats up from where it was earned. */
private class Popup(
    val text: String,
    val x: Float,
    var y: Float,
    var life: Float,
    val big: Boolean,
)

/** Everything one round changes, kept out of the snapshot system; [frame] is what tells Compose to redraw. */
private class GameWorld {
    val caps = ArrayList<FallingCap>()
    val sparks = ArrayList<Spark>()
    val popups = ArrayList<Popup>()
    var untilSpawn = FIRST_SPAWN_S
    var wideLeft = 0f
    var catcherWidthNow = 1f
    var glow = 0f
    var shake = 0f
    var time = 0f

    fun clear() {
        caps.clear()
        sparks.clear()
        popups.clear()
        untilSpawn = FIRST_SPAWN_S
        wideLeft = 0f
        glow = 0f
        shake = 0f
    }

    val busy get() = caps.isNotEmpty() || sparks.isNotEmpty() || popups.isNotEmpty() || shake > 0f || glow > 0f
}

@Composable
private fun KeycapCatcherGame(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalView.current.context
    val eggPrefs = remember { EggPrefs(context) }
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    var best by remember { mutableIntStateOf(eggPrefs.gameHighScore) }
    var phase by remember { mutableStateOf(GamePhase.READY) }
    var score by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(START_LIVES) }
    var combo by remember { mutableIntStateOf(0) }
    var word by remember { mutableStateOf(WORDS.random()) }
    var spelled by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }
    var targetX by remember { mutableFloatStateOf(0.5f) }
    var catcherX by remember { mutableFloatStateOf(0.5f) }
    var frame by remember { mutableLongStateOf(0L) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val world = remember { GameWorld() }

    val level = 1 + score / POINTS_PER_LEVEL
    val multiplier = (1 + combo / COMBO_STEP).coerceAtMost(MAX_MULTIPLIER)

    val density = LocalDensity.current
    val capPx = with(density) { CapSize.toPx() }
    val catcherW = with(density) { CatcherWidth.toPx() }
    val catcherH = with(density) { CatcherHeight.toPx() }
    val bottomMargin = with(density) { 40.dp.toPx() }
    val depthPx = with(density) { 4.dp.toPx() }

    fun start() {
        world.clear()
        score = 0
        lives = START_LIVES
        combo = 0
        spelled = 0
        word = WORDS.random()
        newBest = false
        phase = GamePhase.PLAYING
    }

    fun burst(x: Float, y: Float, color: Color, count: Int, power: Float) {
        repeat(count) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val v = power * (0.4f + Random.nextFloat() * 0.6f)
            world.sparks += Spark(x, y, cos(a) * v, sin(a) * v - power * 0.6f, 0.5f + Random.nextFloat() * 0.4f, color)
        }
        while (world.sparks.size > MAX_SPARKS) world.sparks.removeAt(0)
    }

    fun loseLife() {
        lives--
        combo = 0
        world.shake = 1f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (lives <= 0) {
            // Every key still in the air shatters, so the round ends with a bang
            // and the loop can settle once the sparks are gone.
            for (cap in world.caps) {
                burst(cap.xFrac * areaSize.width, cap.y, scheme.outline, 6, areaSize.height * 0.3f)
            }
            world.caps.clear()
            phase = GamePhase.OVER
            if (score > best) {
                best = score
                newBest = true
                eggPrefs.gameHighScore = score
            }
        }
    }

    // Leaving the app, or Back mid-round, pauses rather than throwing the round away.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (phase == GamePhase.PLAYING) phase = GamePhase.PAUSED
    }
    BackHandler(enabled = phase == GamePhase.PLAYING) { phase = GamePhase.PAUSED }

    val letterFill = scheme.surfaceVariant
    val nextFill = scheme.primaryContainer
    val sparkColors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, GoldKey)

    // The game loop. Deliberately triggered motion — seven taps asked for it —
    // so unlike the ambient anniversary confetti it does not check reduceMotion.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            // Clamped so a dropped frame does not teleport every key at once.
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            val w = areaSize.width.toFloat()
            val h = areaSize.height.toFloat()
            if (w <= 0f || h <= 0f) continue
            val playing = phase == GamePhase.PLAYING
            if (!playing && !world.busy) continue
            if (phase == GamePhase.PAUSED) continue

            // Effects keep settling after the last life goes; only play spawns and moves keys.
            world.time += dt
            world.glow = (world.glow - dt * 3f).coerceAtLeast(0f)
            world.shake = (world.shake - dt * 3.5f).coerceAtLeast(0f)
            world.sparks.removeAll { s ->
                s.vy += h * 1.4f * dt
                s.x += s.vx * dt
                s.y += s.vy * dt
                s.life -= dt
                s.life <= 0f
            }
            world.popups.removeAll { p ->
                p.y -= h * 0.12f * dt
                p.life -= dt
                p.life <= 0f
            }

            if (playing) {
                catcherX += (targetX - catcherX) * (dt * CATCHER_EASE).coerceAtMost(1f)
                world.wideLeft = (world.wideLeft - dt).coerceAtLeast(0f)
                val wantWidth = if (world.wideLeft > 0f) WIDE_FACTOR else 1f
                world.catcherWidthNow += (wantWidth - world.catcherWidthNow) * (dt * 8f).coerceAtMost(1f)

                val lvl = 1 + score / POINTS_PER_LEVEL
                world.untilSpawn -= dt
                if (world.untilSpawn <= 0f) {
                    world.caps += spawnCap(lvl, score, lives, word.getOrNull(spelled), capPx, h)
                    world.untilSpawn = (SPAWN_EVERY_S - (lvl - 1) * SPAWN_RAMP_S).coerceAtLeast(SPAWN_FLOOR_S) *
                        (0.75f + Random.nextFloat() * 0.5f)
                }

                val speedMult = (1f + (lvl - 1) * 0.12f).coerceAtMost(MAX_SPEED_MULT)
                val halfCatcher = catcherW * world.catcherWidthNow / 2
                val catchTop = h - bottomMargin - catcherH
                val iterator = world.caps.iterator()
                while (iterator.hasNext() && phase == GamePhase.PLAYING) {
                    val cap = iterator.next()
                    cap.y += cap.speed * speedMult * dt
                    val capX = cap.xFrac * w
                    val caught = cap.y + capPx / 2 >= catchTop && cap.y - capPx / 2 <= catchTop + catcherH &&
                        abs(capX - catcherX * w) <= halfCatcher + capPx / 2
                    when {
                        caught -> {
                            iterator.remove()
                            when (cap.kind) {
                                CapKind.BACKSPACE -> {
                                    burst(capX, catchTop, scheme.error, 18, h * 0.5f)
                                    world.popups += Popup("⌫", capX, catchTop - capPx, 0.8f, big = true)
                                    loseLife()
                                }
                                CapKind.HEART -> {
                                    combo++
                                    if (lives < MAX_LIVES) {
                                        lives++
                                        world.popups += Popup("+♥", capX, catchTop - capPx, 0.9f, big = true)
                                    } else {
                                        score += 3
                                        world.popups += Popup("+3", capX, catchTop - capPx, 0.8f, big = false)
                                    }
                                    burst(capX, catchTop, scheme.error, 14, h * 0.4f)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                CapKind.SPACE -> {
                                    combo++
                                    world.wideLeft = WIDE_SECONDS
                                    world.popups += Popup("␣ ↔", capX, catchTop - capPx, 0.9f, big = true)
                                    burst(capX, catchTop, scheme.tertiary, 14, h * 0.4f)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                CapKind.ENTER, CapKind.LETTER -> {
                                    combo++
                                    val mult = (1 + combo / COMBO_STEP).coerceAtMost(MAX_MULTIPLIER)
                                    var gained = (if (cap.kind == CapKind.ENTER) 5 else 1) * mult
                                    if (cap.kind == CapKind.LETTER && cap.label == word.getOrNull(spelled)?.toString()) {
                                        spelled++
                                        if (spelled == word.length) {
                                            val bonus = WORD_BONUS * lvl
                                            gained += bonus
                                            world.popups += Popup(word, w / 2, h * 0.42f, 1.4f, big = true)
                                            repeat(4) { i ->
                                                burst(w * (0.2f + 0.2f * i), h * 0.45f, sparkColors[i % sparkColors.size], 16, h * 0.6f)
                                            }
                                            word = WORDS.filter { it != word }.random()
                                            spelled = 0
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                    score += gained
                                    world.popups += Popup("+$gained", capX, catchTop - capPx, 0.7f, big = gained >= 5)
                                    burst(
                                        capX, catchTop,
                                        if (cap.kind == CapKind.ENTER) GoldKey else sparkColors.random(),
                                        if (cap.kind == CapKind.ENTER) 16 else 8,
                                        h * 0.35f,
                                    )
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            world.glow = 1f
                        }
                        cap.y - capPx / 2 > h -> {
                            iterator.remove()
                            if (cap.kind == CapKind.LETTER) {
                                burst(capX, h - depthPx, scheme.outline, 10, h * 0.3f)
                                loseLife()
                            }
                        }
                    }
                }
            }
            frame = now
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val capStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val popupStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
    val bigPopupStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black, color = scheme.primary)
    val layouts = remember(textMeasurer, scheme) { HashMap<String, TextLayoutResult>() }
    fun layout(key: String, text: String, style: TextStyle) =
        layouts.getOrPut(key) { textMeasurer.measure(AnnotatedString(text), style) }

    Box(
        modifier
            .background(Brush.verticalGradient(listOf(scheme.surface, scheme.surfaceVariant.copy(alpha = 0.6f))))
            .onSizeChanged { areaSize = it }
            .pointerInput(Unit) {
                // The spacebar follows the finger wherever it is, from the first touch.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (size.width > 0) targetX = (down.position.x / size.width).coerceIn(0.04f, 0.96f)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (size.width > 0) targetX = (change.position.x / size.width).coerceIn(0.04f, 0.96f)
                        change.consume()
                    }
                }
            },
    ) {
        // Keeps the back gesture off the band the spacebar moves in, so a drag
        // that starts at the screen's edge steers instead of leaving.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(180.dp)
                .systemGestureExclusion(),
        )

        Canvas(Modifier.fillMaxSize()) {
            // Read on purpose: this is what ties the draw pass to the loop.
            frame
            val shakeAmp = world.shake * world.shake * 10.dp.toPx()
            translate(
                left = if (shakeAmp > 0f) (Random.nextFloat() - 0.5f) * shakeAmp else 0f,
                top = if (shakeAmp > 0f) (Random.nextFloat() - 0.5f) * shakeAmp else 0f,
            ) {
                val corner = CornerRadius(capPx * 0.22f)
                val nextLetter = word.getOrNull(spelled)?.toString()
                for (cap in world.caps) {
                    val cx = cap.xFrac * size.width
                    val topLeft = Offset(cx - capPx / 2, cap.y - capPx / 2)
                    val (fill, ink) = when (cap.kind) {
                        CapKind.LETTER -> (if (cap.label == nextLetter) nextFill else letterFill) to scheme.onSurface
                        CapKind.ENTER -> GoldKey to GoldInk
                        CapKind.HEART -> scheme.errorContainer to scheme.error
                        CapKind.SPACE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                        CapKind.BACKSPACE -> scheme.inverseSurface to scheme.inverseOnSurface
                    }
                    rotate(sin(world.time * cap.spin + cap.phase) * 8f, pivot = Offset(cx, cap.y)) {
                        // The keycap's side, then its face: a key with some depth to it.
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.22f),
                            topLeft = topLeft + Offset(0f, depthPx),
                            size = Size(capPx, capPx),
                            cornerRadius = corner,
                        )
                        drawRoundRect(fill, topLeft = topLeft, size = Size(capPx, capPx), cornerRadius = corner)
                        if (cap.kind == CapKind.LETTER && cap.label == nextLetter) {
                            drawRoundRect(
                                scheme.primary, topLeft = topLeft, size = Size(capPx, capPx),
                                cornerRadius = corner, style = Stroke(width = 2.5.dp.toPx()),
                            )
                        } else if (cap.kind == CapKind.BACKSPACE) {
                            drawRoundRect(
                                scheme.error, topLeft = topLeft, size = Size(capPx, capPx),
                                cornerRadius = corner, style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                        val text = layout("${cap.kind}:${cap.label}:${ink.value}", cap.label, capStyle.copy(color = ink))
                        drawText(
                            text,
                            topLeft = Offset(cx - text.size.width / 2f, cap.y - text.size.height / 2f),
                        )
                    }
                }

                // The spacebar.
                val barW = catcherW * world.catcherWidthNow
                val barLeft = catcherX * size.width - barW / 2
                val barTop = size.height - bottomMargin - catcherH
                val barCorner = CornerRadius(catcherH / 2)
                drawRoundRect(
                    Color.Black.copy(alpha = 0.25f),
                    topLeft = Offset(barLeft, barTop + depthPx),
                    size = Size(barW, catcherH),
                    cornerRadius = barCorner,
                )
                drawRoundRect(
                    if (world.wideLeft > 0f) scheme.tertiary else scheme.primary,
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barW, catcherH),
                    cornerRadius = barCorner,
                )
                if (world.glow > 0f) {
                    drawRoundRect(
                        Color.White.copy(alpha = 0.45f * world.glow),
                        topLeft = Offset(barLeft, barTop),
                        size = Size(barW, catcherH),
                        cornerRadius = barCorner,
                    )
                }
                if (world.wideLeft > 0f) {
                    // How long the wide bar has left, drawn as a line shrinking under it.
                    val left = world.wideLeft / WIDE_SECONDS
                    drawLine(
                        scheme.tertiary,
                        start = Offset(barLeft, barTop + catcherH + depthPx * 2.5f),
                        end = Offset(barLeft + barW * left, barTop + catcherH + depthPx * 2.5f),
                        strokeWidth = 3.dp.toPx(),
                    )
                }

                val sparkSize = 5.dp.toPx()
                for (s in world.sparks) {
                    drawRect(
                        s.color.copy(alpha = (s.life / 0.6f).coerceIn(0f, 1f)),
                        topLeft = Offset(s.x - sparkSize / 2, s.y - sparkSize / 2),
                        size = Size(sparkSize, sparkSize),
                    )
                }
                for (p in world.popups) {
                    val text = layout(
                        "popup:${p.big}:${p.text}",
                        p.text,
                        if (p.big) bigPopupStyle else popupStyle,
                    )
                    drawText(
                        text,
                        topLeft = Offset(p.x - text.size.width / 2f, p.y - text.size.height / 2f),
                        alpha = p.life.coerceIn(0f, 1f),
                    )
                }
            }
        }

        GameHud(
            score = score,
            best = best,
            lives = lives,
            level = level,
            multiplier = multiplier,
            word = word,
            spelled = spelled,
            showPause = phase == GamePhase.PLAYING,
            onPause = { phase = GamePhase.PAUSED },
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout),
        )

        when (phase) {
            GamePhase.READY -> GameCard(
                title = stringResource(R.string.egg_game_title),
                action = stringResource(R.string.egg_game_start_action),
                onAction = ::start,
            ) {
                Text(
                    stringResource(R.string.egg_game_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                LegendLine("↵", GoldKey, GoldInk, stringResource(R.string.egg_game_legend_enter))
                LegendLine("♥", scheme.errorContainer, scheme.error, stringResource(R.string.egg_game_legend_heart))
                LegendLine("␣", scheme.tertiaryContainer, scheme.onTertiaryContainer, stringResource(R.string.egg_game_legend_space))
                LegendLine("⌫", scheme.inverseSurface, scheme.inverseOnSurface, stringResource(R.string.egg_game_legend_backspace))
                if (best > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.egg_game_best_label, best),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            GamePhase.PAUSED -> GameCard(
                title = stringResource(R.string.egg_game_paused_title),
                action = stringResource(R.string.egg_game_resume_action),
                onAction = { phase = GamePhase.PLAYING },
            ) {
                Text(
                    stringResource(R.string.egg_game_score_label, score),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            GamePhase.OVER -> GameCard(
                title = stringResource(R.string.egg_game_over_title),
                action = stringResource(R.string.egg_game_play_again_action),
                onAction = ::start,
            ) {
                if (newBest) {
                    Text(
                        stringResource(R.string.egg_game_new_best),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    stringResource(R.string.egg_game_score_label, score),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.egg_game_best_label, best),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.egg_game_level_label, level),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            GamePhase.PLAYING -> Unit
        }
    }
}

private fun spawnCap(level: Int, score: Int, lives: Int, next: Char?, capPx: Float, h: Float): FallingCap {
    // Backspaces only once the player has had a moment to find their feet.
    val bomb = if (score >= 6) (0.06f + level * 0.012f).coerceAtMost(0.18f) else 0f
    val enter = bomb + 0.05f
    val heart = enter + if (lives < MAX_LIVES) 0.035f else 0f
    val space = heart + 0.04f
    val r = Random.nextFloat()
    val kind = when {
        r < bomb -> CapKind.BACKSPACE
        r < enter -> CapKind.ENTER
        r < heart -> CapKind.HEART
        r < space -> CapKind.SPACE
        else -> CapKind.LETTER
    }
    val label = when (kind) {
        // The next letter of the word turns up often enough to be chased.
        CapKind.LETTER -> if (next != null && Random.nextFloat() < 0.4f) next.toString() else ('A' + Random.nextInt(26)).toString()
        CapKind.ENTER -> "↵"
        CapKind.HEART -> "♥"
        CapKind.SPACE -> "␣"
        CapKind.BACKSPACE -> "⌫"
    }
    return FallingCap(
        kind = kind,
        label = label,
        xFrac = 0.07f + Random.nextFloat() * 0.86f,
        y = -capPx,
        speed = h * (0.18f + Random.nextFloat() * 0.12f) * if (kind == CapKind.ENTER) 1.25f else 1f,
        spin = 1.5f + Random.nextFloat() * 2.5f,
        phase = Random.nextFloat() * 6.28f,
    )
}

@Composable
private fun GameHud(
    score: Int,
    best: Int,
    lives: Int,
    level: Int,
    multiplier: Int,
    word: String,
    spelled: Int,
    showPause: Boolean,
    onPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.egg_game_close_desc))
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(
                    score.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.egg_game_best_label, maxOf(best, score)) + " · " +
                        stringResource(R.string.egg_game_level_label, level),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (multiplier > 1) {
                Text(
                    "×$multiplier",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = scheme.tertiary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            val livesDesc = stringResource(R.string.egg_game_lives_desc, lives)
            Text(
                "♥".repeat(lives.coerceAtLeast(0)),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.error,
                modifier = Modifier.semantics { contentDescription = livesDesc },
            )
            if (showPause) {
                IconButton(onClick = onPause) {
                    Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.egg_game_pause_desc))
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
        // The word to spell: caught letters filled, the next one outlined.
        val wordDesc = stringResource(R.string.egg_game_word_desc, word)
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp)
                .semantics { contentDescription = wordDesc },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            word.forEachIndexed { i, c ->
                val done = i < spelled
                val shape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(shape)
                        .background(
                            when {
                                done -> scheme.primary
                                i == spelled -> scheme.primaryContainer
                                else -> scheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (done) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    action: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    // A scrim that swallows touches, so steering underneath stops while a card is up.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.widthIn(max = 360.dp).padding(24.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun LegendLine(key: String, fill: Color, ink: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                key,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ink,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Words to spell: all keyboard talk, all capital A–Z so every letter can fall. */
private val WORDS = listOf(
    "KEYBOARD", "QWERTY", "GLIDE", "SWIPE", "EMOJI", "SHIFT", "ENTER", "SPACE",
    "TYPING", "KEYCAP", "THEME", "LAYOUT", "CLIPBOARD", "DICTATE", "CURSOR",
    "SPELL", "ACCENT", "SYMBOL", "STICKER", "TOOLBAR", "UNDO", "PASTE",
)

private val GoldKey = Color(0xFFFFC94D)
private val GoldInk = Color(0xFF3A2A00)
private val CapSize = 46.dp
private val CatcherWidth = 132.dp
private val CatcherHeight = 22.dp
private const val START_LIVES = 3
private const val MAX_LIVES = 5
private const val FIRST_SPAWN_S = 0.8f
private const val SPAWN_EVERY_S = 1.05f
private const val SPAWN_RAMP_S = 0.07f
private const val SPAWN_FLOOR_S = 0.36f
private const val MAX_SPEED_MULT = 2.6f
private const val POINTS_PER_LEVEL = 30
private const val COMBO_STEP = 8
private const val MAX_MULTIPLIER = 4
private const val WORD_BONUS = 10
private const val WIDE_FACTOR = 1.7f
private const val WIDE_SECONDS = 8f
private const val CATCHER_EASE = 22f
private const val MAX_SPARKS = 220
