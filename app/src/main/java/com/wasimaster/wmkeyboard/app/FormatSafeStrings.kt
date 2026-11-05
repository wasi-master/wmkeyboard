package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.IllegalFormatException
import java.util.Locale

/**
 * Format strings read so that a broken translation falls back to English
 * instead of crashing the screen.
 *
 * Play's automatic translations are injected into the bundle server-side at
 * upload, for every locale the repo leaves a string untranslated in, and they
 * never pass through this repo's checks. Gemini has rewritten a conversion
 * there before (`%1$s` became `%1$d` on `hardware_sound_pack_variants_and_roles`),
 * and `String.format` throws `IllegalFormatConversionException` on the first
 * frame that shows the row. The English string is the one this code was
 * written against, so it is what a failed format is read from.
 */
@Composable
@ReadOnlyComposable
internal fun formatSafeStringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    LocalConfiguration.current
    val context = LocalContext.current
    return try {
        context.resources.getString(id, *formatArgs)
    } catch (_: IllegalFormatException) {
        defaultResources(context).getString(id, *formatArgs)
    }
}

/** [formatSafeStringResource] for a plural. */
@Composable
@ReadOnlyComposable
internal fun formatSafePluralStringResource(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    LocalConfiguration.current
    val context = LocalContext.current
    return try {
        context.resources.getQuantityString(id, count, *formatArgs)
    } catch (_: IllegalFormatException) {
        defaultResources(context).getQuantityString(id, count, *formatArgs)
    }
}

/** The unqualified `values/` strings: English, the ones every format was written for. */
private fun defaultResources(context: Context): Resources {
    val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ROOT) }
    return context.createConfigurationContext(config).resources
}
