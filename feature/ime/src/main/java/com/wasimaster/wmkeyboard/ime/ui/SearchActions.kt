package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.wasimaster.wmkeyboard.core.tools.ImageResult
import com.wasimaster.wmkeyboard.ime.SearchTab

/**
 * What the search panel asks the service for beyond the result taps that
 * KeyboardScreen already carries (#879). Provided by the service as a local
 * rather than passed down as new KeyboardScreen parameters, which sits under
 * the JVM's method-size ceiling. The defaults do nothing, for previews and the
 * theme sandbox.
 */
internal class SearchActions(
    /** A tab of the panel tapped. */
    val onTab: (SearchTab) -> Unit = {},
    /** A SearXNG category chip tapped (#934); null is All. */
    val onCategory: (String?) -> Unit = {},
    /** More results, at the end of the open tab's results. */
    val onMore: () -> Unit = {},
    /** Copies text to the clipboard: the engine's answer, a result's link. */
    val onCopy: (String) -> Unit = {},
    /** Opens an address in the browser: a result's page, or a picture's. */
    val onOpen: (String) -> Unit = {},
    /** Downloads a picture result into the gallery. */
    val onSaveImage: (ImageResult) -> Unit = {},
    /** Puts text in the field as it is: the answer, or one of its code blocks. */
    val onInsertText: (String) -> Unit = {},
    /** The credit readout held (#1382): the settings app's overview of every paid service. */
    val onCredits: () -> Unit = {},
)

internal val LocalSearchActions = staticCompositionLocalOf { SearchActions() }
