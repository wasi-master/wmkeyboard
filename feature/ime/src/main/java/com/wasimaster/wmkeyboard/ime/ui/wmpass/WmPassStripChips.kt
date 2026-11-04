package com.wasimaster.wmkeyboard.ime.ui.wmpass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.wmpass.FieldWire
import com.wasimaster.wmkeyboard.ime.wmpass.WmPassChipActions

/**
 * The buttons beside a field of WM Pass's own, offered on the strip while
 * that field has the keys: Scan the card, or a Wi-Fi network from a QR code
 * or from the phone. The words are this keyboard's; a tap goes through
 * [WmPassChipActions] to WM Pass, which presses the button itself.
 */
@Composable
internal fun WmPassStripChips(tag: FieldWire.Tag, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (action in FieldWire.ACTIONS.filter { it in tag.actions }) {
            val (label, icon) = when (action) {
                FieldWire.CARD_SCAN -> R.string.ime_wmpass_chip_scan_card to Icons.Outlined.PhotoCamera
                FieldWire.WIFI_QR -> R.string.ime_wmpass_chip_wifi_code to Icons.Outlined.QrCodeScanner
                else -> R.string.ime_wmpass_chip_wifi_saved to Icons.Outlined.Wifi
            }
            StripChip(stringResource(label), icon, Modifier.weight(1f)) { WmPassChipActions.handler(action) }
        }
    }
}

/** One button, in the accent-tinted look of the strip's other offers. */
@Composable
private fun StripChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val tint = kb.accent
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(tint.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, tint.copy(alpha = 0.32f), shape)
            .clickable {
                feedback()
                onClick()
            }
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        }
        BasicText(
            text = label,
            style = TextStyle(
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 1.15.em,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 13.sp, stepSize = 0.5.sp),
        )
    }
}
