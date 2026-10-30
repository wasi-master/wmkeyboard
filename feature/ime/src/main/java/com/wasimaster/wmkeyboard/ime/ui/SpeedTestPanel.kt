package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLayout
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardPalette
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardRenderer
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestMath
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestPhase
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedUnit
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestAction
import com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestStage
import com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestUi
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.kit.ActionBar
import com.wasimaster.wmkeyboard.ime.ui.kit.HeroRing
import com.wasimaster.wmkeyboard.ime.ui.kit.KitAction
import com.wasimaster.wmkeyboard.ime.ui.kit.PagerDots
import com.wasimaster.wmkeyboard.ime.ui.kit.ProgressRail
import com.wasimaster.wmkeyboard.ime.ui.kit.RailStep
import com.wasimaster.wmkeyboard.ime.ui.kit.animateGlide
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale

/**
 * The speed test tool, full-bleed. The dial is the button: at rest it is a
 * dormant ring with Play in the middle, a tap starts the run, and the same
 * ring fills while the run goes with the live number inside and the phases
 * as a rail in the header. The result is a ticket: the card itself, drawn
 * as it will be sent, with a swipe across it changing its look and one bar
 * of the ways to send it.
 *
 * Nothing here owns the run. The controller holds it (see [SpeedTestUi])
 * and the panel draws what it publishes.
 */
@Composable
internal fun SpeedTestPanel(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit, onClose: () -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    // The theme's colours go to the controller, which draws the Theme style with them.
    val palette = remember(kb.board, kb.key, kb.accent, kb.keyText, kb.secondaryText) {
        SpeedCardRenderer.themePalette(
            background = kb.board.toArgb(),
            key = kb.key.toArgb(),
            accent = kb.accent.toArgb(),
            text = kb.keyText.toArgb(),
            muted = kb.secondaryText.toArgb(),
        )
    }
    LaunchedEffect(palette) { onAction(SpeedTestAction.Theme(palette)) }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = SpeedExtraHeight,
        headerActions = {
            when (ui.stage) {
                SpeedTestStage.RUNNING -> RunningHeader(state, onAction)
                SpeedTestStage.DONE -> DoneHeader(ui, onAction)
                else -> IdleHeader(state)
            }
        },
    ) {
        MotionCrossfade(
            target = ui.stage,
            animate = kb.motion.panels,
            modifier = Modifier.fillMaxSize(),
            label = "speed-stage",
        ) { stage ->
            Box(Modifier.fillMaxSize().blockTouches(stage != ui.stage)) {
                when (stage) {
                    SpeedTestStage.IDLE -> IdleFace(ui, onAction)
                    SpeedTestStage.ASK_METERED -> AskFace(ui, onAction)
                    SpeedTestStage.RUNNING -> RunningFace(state)
                    SpeedTestStage.DONE -> TicketFace(state, palette, onAction)
                    SpeedTestStage.ERROR -> ErrorFace(state, onAction)
                }
            }
        }
    }
}

// ---- headers ----

@Composable
private fun RowScope.IdleHeader(state: KeyboardUiState) {
    val kb = LocalKbTheme.current
    Text(
        stringResource(R.string.ime_tool_speed_test),
        color = kb.secondaryText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp).weight(1f),
    )
    ProviderChooser(ProviderTool.SPEED_TEST, state.settings)
    Spacer(Modifier.width(4.dp))
}

/** The phases as a rail: Ping, Download, Upload (when the run measures it), and Cancel. */
@Composable
private fun RowScope.RunningHeader(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    val slots = (state.settings.speedTest.duration * SAMPLES_PER_SECOND).coerceAtLeast(1)
    val phase = ui.phase ?: SpeedTestPhase.META
    fun fractionFor(target: SpeedTestPhase): Float = when {
        phase.ordinal > target.ordinal -> 1f
        phase.ordinal < target.ordinal -> 0f
        target == SpeedTestPhase.PING || target == SpeedTestPhase.META -> if (ui.pingMs != null) 1f else PING_UNDER_WAY
        else -> (ui.liveSamples.size.toFloat() / slots).coerceIn(0f, 0.98f)
    }
    val pingLabel = ui.pingMs?.let { stringResource(R.string.ime_speed_phase_ping) + " " + SpeedTestMath.formatMs(it) + " ms" }
        ?: stringResource(if (phase == SpeedTestPhase.META) R.string.ime_speed_phase_meta else R.string.ime_speed_phase_ping)
    val steps = buildList {
        add(RailStep(pingLabel, fractionFor(SpeedTestPhase.PING), phase == SpeedTestPhase.META || phase == SpeedTestPhase.PING))
        add(RailStep(stringResource(R.string.ime_speed_phase_download), fractionFor(SpeedTestPhase.DOWNLOAD), phase == SpeedTestPhase.DOWNLOAD))
        if (state.settings.speedTest.upload) {
            add(RailStep(stringResource(R.string.ime_speed_phase_upload), fractionFor(SpeedTestPhase.UPLOAD), phase == SpeedTestPhase.UPLOAD))
        }
    }
    Spacer(Modifier.width(6.dp))
    ProgressRail(steps, animate = kb.motion.any)
    Spacer(Modifier.width(8.dp))
    PillButton(stringResource(CommonR.string.common_cancel), height = 30.dp) { onAction(SpeedTestAction.Cancel) }
    Spacer(Modifier.width(4.dp))
}

/** Where and when the result came from, and Run again. */
@Composable
private fun RowScope.DoneHeader(ui: SpeedTestUi, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val result = ui.result
    val where = listOf(result?.serverName.orEmpty(), result?.serverCity.orEmpty()).filter { it.isNotEmpty() }
    val at = result?.at ?: 0L
    val whenText = if (at > 0L) DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() else ""
    Text(
        (where + listOf(whenText)).filter { it.isNotEmpty() }.joinToString(" · "),
        color = kb.secondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp).weight(1f),
    )
    PillButton(stringResource(R.string.ime_speed_again), icon = Icons.Outlined.Replay, height = 30.dp) { onAction(SpeedTestAction.Again) }
    Spacer(Modifier.width(4.dp))
}

// ---- idle: the dial is the button ----

@Composable
private fun IdleFace(ui: SpeedTestUi, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val animate = kb.motion.any
    val source = remember { MutableInteractionSource() }
    // A slow breath around the ring while it waits: the tool is alive, not asleep.
    val breath = if (animate) {
        rememberInfiniteTransition(label = "speed-breath")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse), label = "speed-breath-phase").value
    } else {
        0.5f
    }
    val halo = kb.accent
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ring = minOf(maxHeight - 24.dp, maxWidth - 48.dp, DIAL_MAX)
        Box(
            Modifier
                .size(ring + 30.dp)
                .drawBehind {
                    val r = size.minDimension / 2f * (0.93f + 0.07f * breath)
                    drawCircle(halo.copy(alpha = 0.12f + 0.18f * breath), r, style = Stroke(1.dp.toPx()))
                },
            contentAlignment = Alignment.Center,
        ) {
            HeroRing(
                fraction = { null },
                ticks = true,
                modifier = Modifier
                    .size(ring)
                    .pressScale(source, animate)
                    .clip(CircleShape)
                    .clickable(interactionSource = source, indication = null) {
                        feedback()
                        onAction(SpeedTestAction.Start)
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(ring * 0.27f).clip(CircleShape).background(kb.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(R.string.ime_speed_start),
                            tint = maxContrastOn(kb.accent),
                            modifier = Modifier.size(ring * 0.14f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ime_speed_tap_to_test), color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ime_speed_data_note, ui.estimatedMb), color = kb.secondaryText, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---- the data saver's question ----

@Composable
private fun AskFace(ui: SpeedTestUi, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = kb.accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (ui.canAllow) stringResource(R.string.ime_speed_metered_ask, ui.estimatedMb) else stringResource(R.string.ime_speed_metered_blocked),
            color = kb.suggestionText,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.canAllow) {
                PillButton(stringResource(R.string.ime_speed_run_anyway), primary = true) { onAction(SpeedTestAction.AllowMetered) }
            }
            PillButton(stringResource(CommonR.string.common_cancel)) { onAction(SpeedTestAction.Cancel) }
        }
    }
}

// ---- running: the ring fills ----

@Composable
private fun RunningFace(state: KeyboardUiState) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    val unit = state.settings.speedTest.unit
    val transferring = ui.phase == SpeedTestPhase.DOWNLOAD || ui.phase == SpeedTestPhase.UPLOAD
    // The engine reports five times a second; the ring and its number glide
    // from one report to the next rather than snapping, which at that rate
    // reads as flicker. Read only inside the ring's draw and the number's own
    // scope, so a frame of the glide redraws those and not the face.
    val shown = animateGlide(ui.liveMbps.toFloat(), kb.motion.any)
    val smoothed = SpeedTestMath.smooth(ui.liveSamples)
    val peak = smoothed.maxOrNull()?.takeIf { it > 0f }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The samples as a skyline along the bottom, behind the ring.
        Skyline(
            smoothed,
            slots = state.settings.speedTest.duration * SAMPLES_PER_SECOND,
            modifier = Modifier.fillMaxWidth().height(maxHeight * SKYLINE_SHARE).align(Alignment.BottomCenter),
        )
        val ring = minOf(maxHeight - 34.dp, maxWidth - 48.dp, DIAL_MAX)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HeroRing(
                fraction = { if (transferring) SpeedTestMath.gaugeFraction(shown.value.toDouble()) else 0f },
                ticks = true,
                modifier = Modifier.size(ring),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (transferring) {
                        LiveSpeed(shown, unit, sizeSp = (ring.value * 0.22f).coerceIn(26f, 44f))
                        Text(SpeedTestMath.unitLabel(unit), color = kb.secondaryText, fontSize = 11.sp)
                    } else {
                        Text(
                            ui.pingMs?.let { SpeedTestMath.formatMs(it) + " ms" } ?: "…",
                            color = kb.keyText,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                        Text(ui.serverLabel, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (peak != null) {
                    MetaPair(stringResource(R.string.ime_speed_peak), SpeedTestMath.formatSpeed(peak.toDouble(), unit))
                }
                ui.pingMs?.let { MetaPair(stringResource(R.string.ime_speed_phase_ping), SpeedTestMath.formatMs(it) + " ms") }
                if (transferring) {
                    val dot = kb.accent
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                    Text(ui.serverLabel, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MetaPair(label: String, value: String) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.lowercase(), color = kb.secondaryText, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(value, color = kb.suggestionText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/** The number in the ring, in its own scope: the glide changes it every frame and nothing else need recompose. */
@Composable
private fun LiveSpeed(mbps: State<Float>, unit: SpeedUnit, sizeSp: Float) {
    val kb = LocalKbTheme.current
    val label = remember(mbps, unit) { derivedStateOf { SpeedTestMath.formatSpeed(mbps.value.toDouble(), unit) } }
    Text(
        label.value,
        color = kb.keyText,
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        style = TextStyle(fontFeatureSettings = "tnum"),
    )
}

/**
 * The running phase's samples as a filled line along the bottom of the
 * face, on a fixed time axis: one slot per expected sample, so what is
 * drawn stays put as new points arrive. The newest point glides in and the
 * vertical scale glides with the peak, both read only in the draw pass.
 */
@Composable
private fun Skyline(samples: List<Float>, slots: Int, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val motion = kb.motion.any
    // A new phase starts from nothing: snap there rather than sweep back.
    val count = animateGlide(samples.size.toFloat(), motion && samples.size > 1)
    val peakTarget = samples.maxOrNull()?.takeIf { it > 0f } ?: 0f
    val peak = androidx.compose.animation.core.animateFloatAsState(
        targetValue = peakTarget,
        animationSpec = if (motion && peakTarget > 0f) tween(com.wasimaster.wmkeyboard.ime.ui.kit.GLIDE_MS) else snap(),
        label = "speed-peak",
    )
    val line = kb.accent
    val fill = kb.accent.copy(alpha = 0.12f)
    Canvas(modifier) {
        val base = size.height
        val shown = count.value.coerceIn(0f, samples.size.toFloat())
        val top = peak.value
        if (shown < 2f || top <= 0f) return@Canvas
        val step = size.width / (maxOf(slots, samples.size) - 1).coerceAtLeast(1)
        val span = size.height - 6.dp.toPx()
        fun yOf(v: Float) = base - (v / top).coerceIn(0f, 1f) * span
        val whole = shown.toInt().coerceAtMost(samples.size)
        val points = ArrayList<Offset>(whole + 1)
        for (i in 0 until whole) points += Offset(step * i, yOf(samples[i]))
        val fraction = shown - whole
        if (whole < samples.size && fraction > 0f) {
            val from = samples[whole - 1]
            val to = samples[whole]
            points += Offset(step * (whole - 1 + fraction), yOf(from + (to - from) * fraction))
        }
        if (points.size < 2) return@Canvas
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
        } else {
            // Through the midpoints, each sample as the control point: smooth,
            // and the curve never overshoots a sample.
            for (i in 1 until points.size - 1) {
                val mid = Offset((points[i].x + points[i + 1].x) / 2f, (points[i].y + points[i + 1].y) / 2f)
                path.quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
            }
            path.lineTo(points.last().x, points.last().y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(points.last().x, base)
            lineTo(points[0].x, base)
            close()
        }
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---- done: the ticket ----

/**
 * The card as it will be sent, big, with its neighbours peeking at the sides;
 * a swipe across it is the next look. Under it the dots, then one row of the
 * size, layout and unit chips, then the bar of ways to send it.
 */
@Composable
private fun TicketFace(state: KeyboardUiState, themePalette: SpeedCardPalette, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    val s = state.settings.speedTest
    val result = ui.result ?: return
    val styles = SpeedCardStyle.entries
    val styleIndex = styles.indexOf(s.cardStyle).coerceAtLeast(0)
    val motion = kb.motion.any
    // A run that just finished gets a burst; one remembered from last time does not.
    val fresh = remember(result.at) { System.currentTimeMillis() - result.at < FRESH_MS }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val reserved = DOTS_ROW + CHIP_ROW + ACTION_ROW + 22.dp
        val cardMaxH = (maxHeight - reserved).coerceAtLeast(80.dp)
        val aspect = s.cardSize.aspect.coerceIn(0.5f, 1.8f)
        val cardW = minOf(cardMaxH * aspect, maxWidth * CARD_WIDTH_SHARE)
        val cardH = cardW / aspect
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .swipeTabs(styleIndex, styles.size, enabled = !ui.rendering) { onAction(SpeedTestAction.SetStyle(styles[it])) },
                contentAlignment = Alignment.Center,
            ) {
                // The looks either side, as plain colour: a hint there is more.
                val peek = cardW * PEEK_SCALE
                val gap = cardW / 2 + peek / 2 + 14.dp
                styles.getOrNull(styleIndex - 1)?.let { PeekCard(it, themePalette, Modifier.offset(x = -gap).size(peek, cardH * PEEK_SCALE)) }
                styles.getOrNull(styleIndex + 1)?.let { PeekCard(it, themePalette, Modifier.offset(x = gap).size(peek, cardH * PEEK_SCALE)) }
                AnimatedContent(
                    targetState = s.cardStyle,
                    transitionSpec = {
                        if (!motion) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            val dir = if (styles.indexOf(targetState) > styles.indexOf(initialState)) 1 else -1
                            (slideInHorizontally(tween(SLIDE_MS)) { it / 3 * dir } + fadeIn(tween(SLIDE_MS))) togetherWith
                                (slideOutHorizontally(tween(SLIDE_MS)) { -it / 3 * dir } + fadeOut(tween(SLIDE_MS / 2)))
                        }
                    },
                    label = "speed-card",
                ) { style ->
                    CardFace(ui, style, s.cardSize, s.cardLayout, s.unit, themePalette, Modifier.size(cardW, cardH))
                }
                if (motion && kb.motion.toolResults && fresh) {
                    ConfettiBurst(key = result.at, accent = kb.accent, modifier = Modifier.fillMaxSize())
                }
            }
            Row(
                Modifier.fillMaxWidth().height(DOTS_ROW).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PagerDots(styles.size, styleIndex, animate = motion)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ime_speed_look_hint, stringResource(styleLabel(s.cardStyle))),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth().height(CHIP_ROW).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (size in SpeedCardSize.entries) {
                    ToolPanelChip(stringResource(sizeLabel(size)), selected = s.cardSize == size) { onAction(SpeedTestAction.SetSize(size)) }
                }
                ChipGap()
                for (layout in SpeedCardLayout.entries) {
                    ToolPanelChip(stringResource(layoutLabel(layout)), selected = s.cardLayout == layout) { onAction(SpeedTestAction.SetLayout(layout)) }
                }
                ChipGap()
                for (unit in SpeedUnit.entries) {
                    ToolPanelChip(SpeedTestMath.unitLabel(unit), selected = s.unit == unit) { onAction(SpeedTestAction.SetUnit(unit)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            val busy = ui.rendering
            ActionBar(
                listOf(
                    KitAction(stringResource(R.string.ime_speed_send), Icons.AutoMirrored.Outlined.Send, enabled = !busy, primary = true) { onAction(SpeedTestAction.Send) },
                    KitAction(stringResource(R.string.ime_speed_save), Icons.Outlined.Save, enabled = !busy) { onAction(SpeedTestAction.Save) },
                    KitAction(stringResource(R.string.ime_speed_share), Icons.Outlined.Share, enabled = !busy) { onAction(SpeedTestAction.Share) },
                    KitAction(stringResource(R.string.ime_speed_insert_text), Icons.Outlined.TextFields) { onAction(SpeedTestAction.InsertText) },
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A dot of space between the chip groups on the one row. */
@Composable
private fun ChipGap() {
    val kb = LocalKbTheme.current
    Box(Modifier.padding(horizontal = 2.dp).size(4.dp).clip(CircleShape).background(kb.divider))
}

/**
 * The card: the controller's preview when it matches what the chips say,
 * else the last one dimmed under a spinner while the next is drawn, and
 * before any has been drawn a plain face with the headline on it.
 */
@Composable
private fun CardFace(
    ui: SpeedTestUi,
    style: SpeedCardStyle,
    size: SpeedCardSize,
    layout: SpeedCardLayout,
    unit: SpeedUnit,
    themePalette: SpeedCardPalette,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val result = ui.result
    val spec = ui.previewSpec
    val current = spec != null && spec.style == style && spec.size == size && spec.layout == layout && spec.unit == unit
    val palette = SpeedCardPalette.fixed(style) ?: themePalette
    val shape = kb.cardShape()
    Box(
        modifier
            .clip(shape)
            .background(Color(palette.background))
            .chipBorder(kb, shape),
        contentAlignment = Alignment.Center,
    ) {
        val preview = ui.preview
        if (preview != null) {
            Image(
                preview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (current) 1f else 0.55f },
                contentScale = ContentScale.Fit,
            )
        } else if (result != null) {
            Text(
                SpeedTestMath.formatSpeed(result.downMbps, unit) + " " + SpeedTestMath.unitLabel(unit),
                color = Color(palette.text),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!current || ui.rendering) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(palette.accent), trackColor = Color.Transparent)
        }
    }
}

/** A neighbouring look, as a tile of its own colours: its ground, and a ring in its accent. */
@Composable
private fun PeekCard(style: SpeedCardStyle, themePalette: SpeedCardPalette, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val palette = SpeedCardPalette.fixed(style) ?: themePalette
    val shape = kb.cardShape()
    val accent = Color(palette.accent)
    Box(
        modifier
            .graphicsLayer { alpha = 0.55f }
            .clip(shape)
            .background(Color(palette.background))
            .chipBorder(kb, shape)
            .drawBehind {
                val r = size.minDimension * 0.18f
                drawCircle(accent, r, Offset(size.width / 2f, size.height / 2f), style = Stroke(r * 0.35f))
            },
    )
}

private fun styleLabel(style: SpeedCardStyle): Int = when (style) {
    SpeedCardStyle.THEME -> R.string.ime_speed_style_theme
    SpeedCardStyle.DARK -> R.string.ime_speed_style_dark
    SpeedCardStyle.LIGHT -> R.string.ime_speed_style_light
    SpeedCardStyle.GRADIENT -> R.string.ime_speed_style_gradient
    SpeedCardStyle.MONO -> R.string.ime_speed_style_mono
}

private fun sizeLabel(size: SpeedCardSize): Int = when (size) {
    SpeedCardSize.SQUARE -> R.string.ime_speed_size_square
    SpeedCardSize.PORTRAIT -> R.string.ime_speed_size_portrait
    SpeedCardSize.STORY -> R.string.ime_speed_size_story
    SpeedCardSize.WIDE -> R.string.ime_speed_size_wide
    SpeedCardSize.STICKER -> R.string.ime_speed_size_sticker
}

private fun layoutLabel(layout: SpeedCardLayout): Int = when (layout) {
    SpeedCardLayout.FULL -> R.string.ime_speed_layout_full
    SpeedCardLayout.GAUGE -> R.string.ime_speed_layout_gauge
    SpeedCardLayout.COMPACT -> R.string.ime_speed_layout_compact
    SpeedCardLayout.MINIMAL -> R.string.ime_speed_layout_minimal
}

// ---- error ----

@Composable
private fun ErrorFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = kb.accent, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            state.speedTest.error ?: stringResource(R.string.ime_speed_error),
            color = kb.suggestionText,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(CommonR.string.common_retry), primary = true) { onAction(SpeedTestAction.Again) }
            ProviderChooser(ProviderTool.SPEED_TEST, state.settings)
        }
    }
}

/** The engine samples every 200 ms, so a phase of N seconds is 5N points on the line. */
private const val SAMPLES_PER_SECOND = 5

/** The ping step's bar while pings are still out: moving, not done. */
private const val PING_UNDER_WAY = 0.3f

/** The ring's largest size; on a short keyboard it is the height instead. */
private val DIAL_MAX: Dp = 170.dp

/** Room the ticket face grows the keyboard by, as the clock does: the card is worth it. */
private val SpeedExtraHeight: Dp = 36.dp

/** How much of the running face the skyline takes, from the bottom. */
private const val SKYLINE_SHARE = 0.42f

/** How much of the ticket face's width the card may take; the neighbours peek beside it. */
private const val CARD_WIDTH_SHARE = 0.78f
private const val PEEK_SCALE = 0.9f
private const val SLIDE_MS = 240
private const val BREATH_MS = 1_600
private const val FRESH_MS = 3_000L
private val DOTS_ROW = 18.dp
private val CHIP_ROW = 32.dp
private val ACTION_ROW = 48.dp
