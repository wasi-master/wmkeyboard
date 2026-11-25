package com.wasimaster.wmkeyboard.ime.dynstickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.stickers.dynamic.SlotRole
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PermissionRecheck
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.rememberCentredRow

/**
 * The Text grid's filter row: All, stickers or memes, favourites, one chip a
 * pack once there are two, and the builder at the end.
 */
@Composable
internal fun DynFilterRow(ui: DynPanelUi, onAction: (DynAction) -> Unit) {
    val entries = buildList<Pair<String?, String>> {
        add(null to stringResource(R.string.ime_dynsticker_filter_all))
        if (ui.hasMemes) {
            add(DynStickerController.FILTER_STICKERS to stringResource(R.string.ime_dynsticker_filter_stickers))
            add(DynStickerController.FILTER_MEMES to stringResource(R.string.ime_dynsticker_filter_memes))
        }
        if (ui.hasFavourites || ui.filter == DynStickerController.FILTER_FAVOURITES) {
            add(DynStickerController.FILTER_FAVOURITES to stringResource(R.string.ime_dynsticker_filter_favourites))
        }
        if (ui.packs.size > 1) ui.packs.forEach { add(DynStickerController.FILTER_PACK + it.id to it.name) }
    }
    val kb = LocalKbTheme.current
    val centred = rememberCentredRow()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(centred.viewport)
            .horizontalScroll(centred.scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = kb.chipShape()
        // The builder first, so a long row of packs cannot scroll it away.
        Box(
            modifier = Modifier
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .clickable { onAction(DynAction.Create) }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(R.string.ime_dynsticker_create_desc),
                tint = kb.chipText,
                modifier = Modifier.size(16.dp),
            )
        }
        for ((id, label) in entries) {
            val active = id == ui.filter
            Text(
                label,
                color = if (active) kb.chipActiveText else kb.chipText,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .then(if (active) centred.selected else Modifier)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .clickable { onAction(DynAction.Filter(id)) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** The faces the sheet offers over a template's own: the downloadable two, then the system's. */
private val SheetFonts = listOf(
    "" to R.string.ime_dynsticker_font_own,
    "DynaPuff" to 0,
    "Anton" to 0,
    "sans-serif-black" to R.string.ime_dynsticker_font_bold,
    "serif" to R.string.ime_dynsticker_font_serif,
    "monospace" to R.string.ime_dynsticker_font_mono,
    "cursive" to R.string.ime_dynsticker_font_script,
)

/** Text colours over the template's own (null). */
private val SheetColours: List<Long?> = listOf(
    null, 0xFFFFFFFF, 0xFF111111, 0xFFFFD60A, 0xFFFF453A, 0xFFFF6FB5, 0xFF0A84FF, 0xFF30D158, 0xFFBF5AF2,
)

/**
 * The fill sheet: one template large, the texts going into each of its
 * slots, a photo for each photo slot, a face and a colour for its words, and
 * the ways to send it. A scrim and a surface rather than a dialog, which an
 * input method cannot show.
 *
 * The words come from the search box: with several slots, `top | bottom`
 * fills them in order, which the sheet says when it has more than one.
 */
@Composable
internal fun DynFillSheet(
    sheet: DynSheet,
    loader: ImageLoader,
    onAction: (DynAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    // The photo picker is an activity of its own: the pick waits for the keyboard.
    PermissionRecheck { GalleryPickActivity.consumeDynSticker()?.let { onAction(DynAction.PhotoPicked(it)) } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onAction(DynAction.CloseSheet)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                // Taps inside the card stay in it.
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sheet.name,
                    color = kb.popupText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = stringResource(
                        if (sheet.favourite) R.string.ime_dynsticker_favourite_remove else R.string.ime_dynsticker_favourite_add,
                    ),
                    tint = if (sheet.favourite) kb.accent else kb.secondaryText,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onAction(DynAction.Favourite(sheet.item)) }
                        .padding(6.dp),
                )
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_dynsticker_sheet_close),
                    tint = kb.secondaryText,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onAction(DynAction.CloseSheet) }
                        .padding(6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(kb.secondaryText.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = sheet.preview.previewUrl,
                        imageLoader = loader,
                        contentDescription = sheet.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (sheet.busy) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = kb.accent)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (slot in sheet.slots) {
                        Text(
                            slot.label.ifBlank { stringResource(roleLabel(slot.role)) },
                            color = kb.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        Text(
                            slot.text.ifBlank { stringResource(R.string.ime_dynsticker_slot_empty) },
                            color = kb.popupText,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (sheet.slots.size > 1) {
                        Text(
                            stringResource(R.string.ime_dynsticker_slots_hint),
                            color = kb.secondaryText,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            for (photo in sheet.photos) {
                SheetButton(
                    label = if (photo.picked) {
                        stringResource(R.string.ime_dynsticker_photo_change, photo.label.ifBlank { stringResource(R.string.ime_dynsticker_photo) })
                    } else {
                        stringResource(R.string.ime_dynsticker_photo_pick, photo.label.ifBlank { stringResource(R.string.ime_dynsticker_photo) })
                    },
                    primary = !photo.picked,
                    onClick = { onAction(DynAction.PickPhoto) },
                )
            }
            Text(stringResource(R.string.ime_dynsticker_font_label), color = kb.secondaryText, fontSize = 11.sp)
            val fontRow = rememberCentredRow()
            Row(
                modifier = fontRow.viewport.horizontalScroll(fontRow.scroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val shape = kb.chipShape()
                for ((family, label) in SheetFonts) {
                    val active = (sheet.fontOverride ?: "") == family
                    Text(
                        if (label != 0) stringResource(label) else family,
                        color = if (active) kb.chipActiveText else kb.chipText,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier
                            .then(if (active) fontRow.selected else Modifier)
                            .clip(shape)
                            .background(if (active) kb.chipActive else kb.chip)
                            .chipBorder(kb, shape)
                            .clickable { onAction(DynAction.Font(family)) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(stringResource(R.string.ime_dynsticker_colour_label), color = kb.secondaryText, fontSize = 11.sp)
            val colourRow = rememberCentredRow()
            Row(
                modifier = colourRow.viewport.horizontalScroll(colourRow.scroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (colour in SheetColours) {
                    val active = sheet.textColor == colour
                    Box(
                        modifier = Modifier
                            .then(if (active) colourRow.selected else Modifier)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colour?.let { Color(it.toInt()) } ?: kb.chip)
                            .border(if (active) 3.dp else 1.dp, if (active) kb.accent else kb.secondaryText.copy(alpha = 0.4f), CircleShape)
                            .clickable { onAction(DynAction.TextColor(colour)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (colour == null) Text("A", color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(
                    label = stringResource(R.string.ime_dynsticker_send),
                    primary = true,
                    enabled = !sheet.busy,
                    onClick = { onAction(DynAction.Send(still = false)) },
                    modifier = Modifier.weight(1f),
                )
                if (sheet.moving) {
                    SheetButton(
                        label = stringResource(R.string.ime_dynsticker_send_still),
                        enabled = !sheet.busy,
                        onClick = { onAction(DynAction.Send(still = true)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (sheet.busy) {
                // An animated sticker takes a few seconds to draw: say so, and how far.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ime_dynsticker_drawing), color = kb.secondaryText, fontSize = 12.sp)
                    val progress = sheet.progress
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = kb.accent, trackColor = kb.chip)
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = kb.accent, trackColor = kb.chip)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(
                    label = stringResource(R.string.ime_dynsticker_save_to_stickers),
                    onClick = { onAction(DynAction.SaveToStickers(sheet.preview)) },
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    label = stringResource(R.string.ime_dynsticker_edit),
                    onClick = { onAction(DynAction.Edit(sheet.item)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        color = if (primary) kb.chipActiveText else kb.popupText,
        fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(shape)
            .background(if (primary) kb.chipActive else kb.chip)
            .chipBorder(kb, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

private fun roleLabel(role: SlotRole): Int = when (role) {
    SlotRole.MAIN -> R.string.ime_dynsticker_slot_main
    SlotRole.TOP -> R.string.ime_dynsticker_slot_top
    SlotRole.BOTTOM -> R.string.ime_dynsticker_slot_bottom
    SlotRole.LABEL -> R.string.ime_dynsticker_slot_label
    SlotRole.CAPTION -> R.string.ime_dynsticker_slot_caption
    SlotRole.NAME -> R.string.ime_dynsticker_slot_name
}

/** The Text grid with no templates to show: why, and the way to some. */
@Composable
internal fun DynEmptyNotice(filtered: Boolean, onAction: (DynAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(if (filtered) R.string.ime_dynsticker_empty_filtered else R.string.ime_dynsticker_empty),
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        SheetButton(
            label = stringResource(R.string.ime_dynsticker_empty_action),
            primary = true,
            onClick = { onAction(DynAction.OpenSettings) },
        )
    }
}
