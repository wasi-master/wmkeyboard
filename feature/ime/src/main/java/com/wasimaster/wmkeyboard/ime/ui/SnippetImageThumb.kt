package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.ime.R

/**
 * A picture snippet's picture, small: on its tile in the snippets panel and
 * on its chip on the strip.
 *
 * [image] is the snippet's file name, resolved under the app's own snippet
 * pictures and nowhere else ([SnippetStore.imageFile]). Drawn through the
 * keyboard's one media loader, so a strip that offers the same picture twice
 * decodes it once.
 */
@Composable
internal fun SnippetImageThumb(image: String, label: String, modifier: Modifier = Modifier) {
    val filesDir = LocalContext.current.filesDir
    val file = remember(image, filesDir) { SnippetStore.imageFile(filesDir, image) } ?: return
    AsyncImage(
        model = file,
        contentDescription = stringResource(R.string.ime_snippet_image_desc, label),
        imageLoader = rememberMediaImageLoader(),
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
