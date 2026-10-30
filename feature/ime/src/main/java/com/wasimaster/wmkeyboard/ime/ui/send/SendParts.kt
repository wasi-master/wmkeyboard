package com.wasimaster.wmkeyboard.ime.ui.send

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.InsertDriveFile
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AudioFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderZip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Movie
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.send.SendError
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendLink
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.QrCodeGen
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.send.SendDraftFile
import com.wasimaster.wmkeyboard.ime.send.SendLinkOffer
import com.wasimaster.wmkeyboard.ime.send.SendNotice
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.kit.kitSpringGentle
import com.wasimaster.wmkeyboard.ime.ui.kit.popIn
import com.wasimaster.wmkeyboard.ime.ui.kit.pressScale
import java.util.Locale
import kotlin.math.max

// The pieces the Send files panel is built from (#729): file tiles and the
// pile they fan into, the source chips, the dashed trough, the progress ring,
// the QR image, the scrim and notice line, and the strip's "receive this
// link" chip. Everything reads the keyboard theme; nothing here holds state
// of its own beyond an animation.

/** "12 MB", in the user's locale. */
internal fun bytesText(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, max(0L, bytes))

/** "2 days", "3 hr", "5 min": the largest unit that is not zero, short form. */
internal fun durationText(seconds: Long): String {
    val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
    val (value, unit) = when {
        seconds >= 2 * 86_400 -> seconds / 86_400 to MeasureUnit.DAY
        seconds >= 3_600 -> seconds / 3_600 to MeasureUnit.HOUR
        seconds >= 60 -> seconds / 60 to MeasureUnit.MINUTE
        else -> seconds.coerceAtLeast(0) to MeasureUnit.SECOND
    }
    return format.format(Measure(value, unit))
}

/** The host shown for an instance or a link. */
internal fun hostText(base: String): String = SendInstances.displayHost(base)

internal fun errorText(error: SendError): Int = when (error) {
    SendError.OFFLINE -> R.string.ime_send_error_offline
    SendError.TOO_BIG -> R.string.ime_send_error_too_big
    SendError.BAD_LIMITS -> R.string.ime_send_error_bad_limits
    SendError.UNAUTHORIZED -> R.string.ime_send_error_unauthorized
    SendError.NOT_FOUND -> R.string.ime_send_error_not_found
    SendError.SERVER -> R.string.ime_send_error_server
    SendError.CANCELLED -> R.string.ime_send_error_cancelled
    SendError.WRONG_KEY -> R.string.ime_send_error_wrong_key
    SendError.WRONG_PASSWORD -> R.string.ime_send_error_wrong_password
    SendError.CORRUPT -> R.string.ime_send_error_corrupt
    SendError.BAD_LINK -> R.string.ime_send_error_bad_link
    SendError.UNREADABLE -> R.string.ime_send_error_unreadable
}

@Composable
internal fun noticeText(notice: SendNotice): String = when (notice.kind) {
    SendNotice.Kind.COPIED -> stringResource(R.string.ime_send_notice_copied)
    SendNotice.Kind.INSERTED -> stringResource(R.string.ime_send_notice_inserted)
    SendNotice.Kind.SHARED -> stringResource(R.string.ime_send_notice_shared)
    SendNotice.Kind.SAVED -> stringResource(R.string.ime_send_notice_saved)
    SendNotice.Kind.DELETED -> stringResource(R.string.ime_send_notice_deleted)
    SendNotice.Kind.SENT -> stringResource(R.string.ime_send_notice_sent, notice.text)
    SendNotice.Kind.FAILED -> stringResource(R.string.ime_send_notice_failed)
    SendNotice.Kind.NO_FILES -> stringResource(R.string.ime_send_notice_no_files)
    SendNotice.Kind.EMPTY_FIELD -> stringResource(R.string.ime_send_notice_empty_field)
    SendNotice.Kind.EMPTY_CLIPBOARD -> stringResource(R.string.ime_send_notice_empty_clipboard)
    SendNotice.Kind.TOO_MANY -> stringResource(R.string.ime_send_notice_too_many, notice.text)
    SendNotice.Kind.PASSWORD_GENERATED -> stringResource(R.string.ime_send_notice_password_generated)
    SendNotice.Kind.INSTANCE_SET -> stringResource(R.string.ime_send_notice_instance_set, notice.text)
    SendNotice.Kind.INSTANCE_BAD -> stringResource(R.string.ime_send_notice_instance_bad)
    SendNotice.Kind.INSTANCE_ADDED -> stringResource(R.string.ime_send_notice_instance_added, notice.text)
    SendNotice.Kind.INSTANCE_REMOVED -> stringResource(R.string.ime_send_notice_instance_removed)
    SendNotice.Kind.NOT_ACCEPTED -> stringResource(R.string.ime_send_notice_not_accepted)
    SendNotice.Kind.OPENED -> stringResource(R.string.ime_send_notice_opened)
    SendNotice.Kind.LINK_BAD -> stringResource(R.string.ime_send_notice_link_bad)
    SendNotice.Kind.DATA_SAVER -> stringResource(R.string.ime_send_notice_data_saver)
}

/** Which glyph stands for a file of [type]. */
internal fun fileIcon(type: String, name: String = ""): ImageVector = when {
    type.startsWith("image/") -> Icons.Outlined.Image
    type.startsWith("video/") -> Icons.Outlined.Movie
    type.startsWith("audio/") -> Icons.Outlined.AudioFile
    type == "application/pdf" || name.endsWith(".pdf", true) -> Icons.Outlined.PictureAsPdf
    type.contains("zip") || type.contains("compressed") || type.contains("tar") || name.endsWith(".zip", true) -> Icons.Outlined.FolderZip
    type.startsWith("text/") || type.contains("json") || type.contains("xml") -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** A place to add from: a small accent "+" and the name, on one compact chip. */
@Composable
internal fun SourceChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(SOURCE_CHIP_HEIGHT)
            .pressScale(source, kb.motion.any)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .clickable(interactionSource = source, indication = null) {
                feedback()
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = kb.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = kb.chipText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One file in the draft: a thumbnail or a glyph, its name under it, a ✕ in
 * the corner. [compact] is the thumbnail alone, as the pile stacks them,
 * with a rim in the board colour so overlapping tiles read apart.
 */
@Composable
internal fun FileTile(
    file: SendDraftFile,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)?,
    compact: Boolean = false,
    tileSize: Dp = 56.dp,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(if (compact) tileSize else tileSize + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(tileSize)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(kb.chip.copy(alpha = kb.chip.alpha * 0.8f))
                    .then(if (compact) Modifier.border(2.dp, kb.board, shape) else Modifier.chipBorder(kb, shape)),
                contentAlignment = Alignment.Center,
            ) {
                if (file.isImage || file.isVideo) {
                    AsyncImage(
                        model = file.uri ?: file.file,
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(fileIcon(file.type, file.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(tileSize * 0.43f))
                }
            }
            if (onRemove != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(kb.popup)
                        .chipBorder(kb, CircleShape)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ime_send_remove), tint = kb.popupText, modifier = Modifier.size(11.dp))
                }
            }
        }
        if (!compact) {
            Spacer(Modifier.height(2.dp))
            Text(file.name, color = kb.secondaryText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.size > 0) Text(bytesText(context, file.size), color = kb.secondaryText.copy(alpha = 0.7f), fontSize = 8.sp, maxLines = 1)
        }
    }
}

/**
 * The draft as a pile: the first few tiles fanned and overlapping, each
 * tilted a little more than its neighbour, popping in a beat apart; a count
 * on the last when there are more than fit. [animate] gates the pop.
 */
@Composable
internal fun FilePile(
    files: List<SendDraftFile>,
    animate: Boolean,
    modifier: Modifier = Modifier,
    tileSize: Dp = 44.dp,
    shown: Int = PILE_SHOWN,
    onClick: (() -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val visible = files.take(shown)
    val more = files.size - visible.size
    val overlap = tileSize * 0.36f
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .then(if (onClick != null) Modifier.pressScale(source, animate) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null) {
                        feedback()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(-overlap),
        verticalAlignment = Alignment.Bottom,
    ) {
        visible.forEachIndexed { i, file ->
            val tilt = (i - (visible.size - 1) / 2f) * PILE_TILT_DEG
            Box(
                Modifier
                    .popIn(i, animate, key = file.id)
                    .graphicsLayer { rotationZ = tilt },
            ) {
                FileTile(file, onRemove = null, compact = true, tileSize = tileSize)
                if (more > 0 && i == visible.lastIndex) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(kb.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+$more", color = com.wasimaster.wmkeyboard.ime.ui.maxContrastOn(kb.accent), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** A dashed outline in the card shape: the tray's trough. */
@Composable
internal fun Modifier.dashedTrough(kb: KbTheme): Modifier {
    val shape = kb.cardShape()
    val line = kb.divider
    val fill = kb.chip.copy(alpha = kb.chip.alpha * 0.28f)
    return this
        .clip(shape)
        .background(fill)
        .drawBehind {
            val r = kb.chipRadiusDp.dp.toPx().coerceAtMost(size.minDimension / 2)
            val inset = 0.75.dp.toPx()
            drawRoundRect(
                line,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(r),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
            )
        }
}

/** A thin bar: how much of the instance's cap the draft takes; red past it. */
@Composable
internal fun SizeMeter(fraction: Float, over: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), if (kb.reduceMotion) tween(0) else kitSpringGentle(), label = "send-meter")
    val color = if (over) FailedRed else kb.accent
    Canvas(modifier.height(3.dp)) {
        val r = size.height / 2
        drawRoundRect(kb.divider, size = size, cornerRadius = CornerRadius(r))
        drawRoundRect(color, size = Size(size.width * shown, size.height), cornerRadius = CornerRadius(r))
    }
}

/**
 * A full ring that fills with [fraction], [content] in the middle; null
 * spins: the handshake is out and nothing has gone yet. The default content
 * is the percentage.
 */
@Composable
internal fun ProgressRing(
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 6.dp,
    content: (@Composable BoxScope.(shown: Float) -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    val shown by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), if (kb.reduceMotion) tween(0) else kitSpringGentle(), label = "send-ring")
    val spin = if (fraction == null && !kb.reduceMotion) {
        rememberInfiniteTransition(label = "send-spin")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "send-spin-angle").value
    } else {
        -90f
    }
    val track = kb.divider
    val accent = kb.accent
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            if (fraction == null) {
                drawArc(accent, spin, 80f, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            } else {
                drawArc(accent, -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        if (content != null) {
            content(shown)
        } else if (fraction != null) {
            Text(
                "${(shown * 100).toInt()}%",
                color = kb.suggestionText,
                fontSize = (size.value * 0.22f).sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

/** A QR code of [text], white on white, as the generator tool draws it. */
@Composable
internal fun QrImage(text: String, modifier: Modifier = Modifier, sizePx: Int = 512) {
    val bitmap = remember(text) { QrCodeGen.bitmap(text, sizePx = sizePx, ecc = "M", maxChars = QR_MAX_CHARS) }
    if (bitmap == null) return
    Image(
        bitmap.asImageBitmap(),
        contentDescription = text,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(4.dp),
    )
}

/** A sweep, so "looking" looks like looking. Still under reduce-motion. */
@Composable
internal fun Radar(modifier: Modifier = Modifier, animate: Boolean = true) {
    val kb = LocalKbTheme.current
    val phase = if (animate && !kb.reduceMotion) {
        rememberInfiniteTransition(label = "send-radar")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "send-radar-phase").value
    } else {
        0.6f
    }
    val color = kb.accent
    Box(
        modifier.drawBehind {
            val r = size.minDimension / 2
            drawCircle(color.copy(alpha = 0.9f), r * 0.22f)
            drawCircle(color.copy(alpha = (1f - phase) * 0.8f), r * (0.3f + 0.7f * phase), style = Stroke(width = r * 0.14f))
        },
    )
}

/** A dim over the panel with [content] on it; a tap on the dim dismisses. The gallery's. */
@Composable
internal fun SendScrim(visible: Boolean, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 120)) + scaleIn(tween(if (kb.reduceMotion) 0 else 160), initialScale = 0.96f),
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 100)) + scaleOut(tween(if (kb.reduceMotion) 0 else 100), targetScale = 0.98f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            content = content,
        )
    }
}

/** A card in the middle of the scrim, which takes its own taps. */
@Composable
internal fun SheetCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.POPUPS)
            .background(kb.popup)
            .chipBorder(kb, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(12.dp),
        content = content,
    )
}

/** A short line over the panel that slides in and fades after a moment. */
@Composable
internal fun SendNoticeLine(notice: SendNotice?, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(if (kb.reduceMotion) 0 else 150)) + slideInVertically(tween(if (kb.reduceMotion) 0 else 180)) { -it },
        exit = fadeOut(tween(if (kb.reduceMotion) 0 else 150)),
        modifier = modifier,
    ) {
        var last by remember { mutableStateOf(notice) }
        if (notice != null) last = notice
        val n = last ?: return@AnimatedVisibility
        Text(
            noticeText(n),
            color = kb.popupText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(kb.chipShape())
                .frostGlass(kb, kb.chipShape(), FrostSurface.POPUPS)
                .background(kb.popup)
                .chipBorder(kb, kb.chipShape())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A row of text in the theme's secondary colour, for meta lines. */
@Composable
internal fun MetaText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val kb = LocalKbTheme.current
    Text(text, color = color ?: kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A small caps label with a wide tracking: TO, RECENT. */
@Composable
internal fun TinyLabel(text: String, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Text(text.uppercase(), color = kb.secondaryText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1, modifier = modifier)
}

/**
 * The strip's chip for a Send link that just reached the clipboard (#729):
 * the tool's icon, "Receive the file", the instance it is on, and a ✕. The
 * face opens the panel on receiving it. Drawn like the smart chips (an
 * accent fill), since it is an answer to something the user just did.
 */
@Composable
internal fun SendLinkOfferChip(
    offer: SendLinkOffer,
    reduceMotion: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val appear = remember(offer.url) { Animatable(if (reduceMotion) 1f else 0.92f) }
    LaunchedEffect(offer.url) {
        if (!reduceMotion) appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val tint = kb.accent
    val fill = tint.copy(alpha = if (kb.dark) 0.20f else 0.11f)
    val shape = kb.chipShape()
    val host = SendLink.parse(offer.url)?.let { hostText(it.base) }.orEmpty()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .graphicsLayer {
                scaleX = appear.value
                scaleY = appear.value
                alpha = appear.value
            }
            .clip(shape)
            .background(fill)
            .chipBorder(kb, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .clickable {
                    feedback()
                    onOpen()
                }
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            Text(
                stringResource(R.string.ime_send_offer_chip),
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (host.isNotEmpty()) {
                Text(
                    stringResource(R.string.ime_send_offer_from, host),
                    color = kb.suggestionText.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable {
                    feedback()
                    onDismiss()
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ime_send_offer_dismiss), tint = tint.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
        }
    }
}

/** A small round dot: green reachable, grey unknown, red failed. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

internal fun KbTheme.dimmed(color: Color): Color = color.copy(alpha = color.alpha * 0.5f)

internal val ConnectedGreen = Color(0xFF43A047)
internal val FailedRed = Color(0xFFE53935)
internal val SOURCE_CHIP_HEIGHT = 30.dp

/** Tiles the pile shows before it counts the rest. */
private const val PILE_SHOWN = 4

/** Degrees between neighbours in the fan. */
private const val PILE_TILT_DEG = 6f
private const val QR_MAX_CHARS = 2000
