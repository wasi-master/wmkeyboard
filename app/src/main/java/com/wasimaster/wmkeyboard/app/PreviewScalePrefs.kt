package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.core.ui.PreviewScaleMin

/**
 * How small the user has dragged an editor's keyboard preview (#967), kept on
 * this device only. A view preference of the editor, like the scroll position,
 * so it stays out of the keyboard's settings.
 */
internal enum class PreviewScaleSlot(val key: String, val default: Float = 1f) {
    THEME_EDITOR("theme_editor"),
    LAYOUT_EDITOR("layout_editor"),

    /** A square sticker at full width is most of a phone's height: it starts smaller. */
    DYNSTICKER_BUILDER("dynsticker_builder", default = 0.62f),
}

/** The slot's stored scale, read once; [storePreviewScale] writes it back. */
@Composable
internal fun rememberPreviewScale(slot: PreviewScaleSlot): MutableFloatState {
    val context = LocalContext.current
    return remember(slot) {
        val stored = prefs(context).getFloat(slot.key, slot.default)
        mutableFloatStateOf(stored.coerceIn(PreviewScaleMin, 1f))
    }
}

internal fun storePreviewScale(context: Context, slot: PreviewScaleSlot, scale: Float) {
    prefs(context).edit { putFloat(slot.key, scale.coerceIn(PreviewScaleMin, 1f)) }
}

private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

private const val FILE_NAME = "preview_scale"
