package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.softenedForPhoto
import com.wasimaster.wmkeyboard.core.theme.Readability
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.readabilityOver
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import com.wasimaster.wmkeyboard.core.theme.reseeded
import com.wasimaster.wmkeyboard.core.theme.selfAndVariants
import com.wasimaster.wmkeyboard.core.theme.withAlphaFraction
import com.wasimaster.wmkeyboard.core.tools.PhotoApplyStatus
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.tools.PhotoItem
import com.wasimaster.wmkeyboard.core.tools.PhotoLinks
import com.wasimaster.wmkeyboard.core.tools.PhotoMeasurements
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.toAttribution
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait

/**
 * One photo, previewed behind real keys, with everything that can be done
 * with it.
 *
 * The preview is the same composable the theme gallery draws, given this
 * photo's URL — the only honest way to answer "how would this look", since it
 * uses the theme's own key shapes and colours.
 */
@Composable
fun PhotoDetailScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    photo: PhotoItem,
    themeId: String,
    slot: BackgroundSlot,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by PhotoBackgroundManager.status.collectAsState()

    // The id can name a variant. The member spec drives the preview; writes
    // and the undo snapshot go through the family entry that stores it.
    val themeFamily = settings.customThemes.findThemeFamily(themeId)
    val theme = themeFamily?.selfAndVariants()?.find { it.id == themeId }
    var chosenSlot by remember { mutableStateOf(slot) }
    var applied by remember { mutableStateOf(false) }
    var measurements by remember { mutableStateOf<PhotoMeasurements?>(null) }
    var saved by remember { mutableStateOf(false) }
    var undoSpec by remember { mutableStateOf<ThemeSpec?>(null) }
    var previewLandscape by remember { mutableStateOf(false) }

    val busy = status[photo.key].let {
        it is PhotoApplyStatus.Downloading || it is PhotoApplyStatus.Applying
    }
    // Applying a photo makes the board see-through so the photo shows. The
    // preview has to do the same, or a theme with an opaque board previews as
    // a flat colour and the photo appears to do nothing.
    val previewTheme = remember(theme, applied) {
        if (theme == null || applied) {
            theme
        } else {
            theme.copy(
                boardBackground = theme.boardBackground.withAlphaFraction(0f),
                keyBackground = theme.keyBackground.softenedForPhoto(settings.photoBackground.keyOpacity),
            )
        }
    }

    WmScreen(
        title = stringResource(R.string.photo_detail_title),
        onBack = onBack,
        subtitle = theme?.name,
        subtitleInBar = true,
    ) {
        // The photo as the service serves it, hotlinked.
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(HERO_ASPECT)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(
                model = photo.thumbUrl,
                contentDescription = photo.altText.ifBlank { null },
                imageLoader = rememberMediaImageLoader(),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (theme != null && previewTheme != null) {
            SectionHeaderPublic(stringResource(R.string.photo_use_section_title))
            ChoiceControl(
                options = listOf(
                    false to stringResource(R.string.photo_preview_upright_label),
                    true to stringResource(R.string.photo_preview_sideways_label),
                ),
                selected = previewLandscape,
                modifier = Modifier.padding(horizontal = 16.dp),
                detail = { landscape ->
                    ChoiceDetail(
                        icon = if (landscape) Icons.Outlined.StayCurrentLandscape
                        else Icons.Outlined.StayCurrentPortrait,
                    )
                },
            ) { landscape -> previewLandscape = landscape }
            Spacer(Modifier.height(8.dp))
            // The live mock-up: this theme's keys over *this* photo. Before
            // the photo is applied it is not on disk yet, so the preview is
            // given the service's own URL -- showing the theme's existing
            // image here answered a question nobody asked.
            Box(Modifier.padding(horizontal = 16.dp)) {
                ThemePreview(
                    theme = previewTheme,
                    imageOverride = if (applied) null else photo.thumbUrl,
                    landscape = previewLandscape,
                )
            }
            ReadabilityBanner(
                theme = theme,
                measurements = measurements,
                onScrim = { alpha ->
                    scope.launch {
                        repository.upsertCustomTheme(
                            theme.copy(boardBackground = theme.boardBackground.withAlphaFraction(alpha)),
                        )
                    }
                },
                onBlur = {
                    scope.launch {
                        repository.upsertCustomTheme(theme.copy(backgroundImageBlur = BUSY_BLUR))
                    }
                },
            )
        }

        // Credit. Both services require the photographer to be named wherever
        // their photo is shown, with a link back.
        PhotoCreditRow(photo.toAttribution()) { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
        CaptionText(stringResource(R.string.photo_licence_body))

        if (busy) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.photo_download_progress))
            }
        }

        if (theme != null && !applied) {
            SlotChoice(chosenSlot) { chosenSlot = it }
            Button(
                onClick = {
                    scope.launch {
                        undoSpec = themeFamily
                        val key = ToolApiKeys.unsplash(settings)
                        val pexels = ToolApiKeys.pexels(settings)
                        val apiKey = if (photo.source.name == "UNSPLASH") key else pexels
                        val slots = when (chosenSlot) {
                            BackgroundSlot.PORTRAIT -> listOf(false)
                            BackgroundSlot.LANDSCAPE -> listOf(true)
                            BackgroundSlot.BOTH -> listOf(false, true)
                        }
                        var measured: PhotoMeasurements? = null
                        for (landscape in slots) {
                            measured = PhotoBackgroundManager.applyToTheme(
                                context = context,
                                repository = repository,
                                photo = photo,
                                themeId = themeId,
                                landscape = landscape,
                                apiKey = apiKey,
                            ) ?: measured
                        }
                        measurements = measured
                        applied = measured != null
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.photo_apply_action))
            }
        }

        if (applied && theme != null) {
            // Deliberately does not pop: the sliders somebody wants five
            // seconds after applying are right here rather than back in the
            // editor they would have to find again.
            SectionHeaderPublic(
                stringResource(R.string.photo_applied_title, theme.name),
                info = stringResource(R.string.photo_applied_body),
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                undoSpec?.let { previous ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            repository.upsertCustomTheme(previous)
                            applied = false
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(CommonR.string.common_undo))
                    }
                    Spacer(Modifier.size(8.dp))
                }
                Button(onClick = onDone) { Text(stringResource(CommonR.string.common_done)) }
            }
            val seed = measurements?.seedColor ?: 0L
            if (seed != 0L) {
                WmRow(
                    title = stringResource(R.string.photo_seed_title),
                    subtitle = stringResource(R.string.photo_seed_subtitle),
                    icon = Icons.Outlined.Palette,
                    onClick = {
                        scope.launch {
                            val current = settings.customThemes.findThemeFamily(themeId)
                            if (current != null) {
                                // Kept so the whole-theme rewrite below stays
                                // undoable: reseeding replaces every colour.
                                undoSpec = current
                                repository.upsertCustomTheme(
                                    current.replacingMember(themeId) { it.reseeded(seed, it.dark) },
                                )
                            }
                        }
                    },
                )
                CaptionText(stringResource(R.string.photo_seed_dialog_body))
            }
        }

        WmRow(
            title = stringResource(
                if (saved) R.string.photo_saved_action else R.string.photo_save_action,
            ),
            icon = Icons.Outlined.Bookmark,
            enabled = !saved && !busy,
            onClick = {
                scope.launch {
                    val apiKey = ToolApiKeys.unsplash(settings)
                    saved = PhotoBackgroundManager.saveToLibrary(context, photo, apiKey)
                }
            },
        )
        if (photo.pageUrl.isNotBlank()) {
            WmRow(
                title = stringResource(R.string.photo_view_source_action),
                icon = Icons.Outlined.Wallpaper,
                onClick = {
                    val url = PhotoLinks.credited(photo.pageUrl, photo.source)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                },
            )
        }
        if (theme == null) {
            CaptionText(stringResource(R.string.photo_apply_no_themes_body))
        }
    }
}

@Composable
private fun SlotChoice(slot: BackgroundSlot, onSlot: (BackgroundSlot) -> Unit) {
    Text(
        stringResource(R.string.photo_use_slot_title),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    ChoiceControl(
        options = BackgroundSlot.entries.map { entry ->
            entry to stringResource(
                when (entry) {
                    BackgroundSlot.PORTRAIT -> R.string.photo_use_slot_portrait_label
                    BackgroundSlot.LANDSCAPE -> R.string.photo_use_slot_landscape_label
                    BackgroundSlot.BOTH -> R.string.photo_use_slot_both_label
                },
            )
        },
        selected = slot,
        modifier = Modifier.padding(horizontal = 16.dp),
        onChange = onSlot,
    )
}

/**
 * Says when the keys will be hard to read, and offers the one change that
 * fixes it.
 *
 * Advisory rather than blocking, except that the caller acts on its own when
 * the contrast is genuinely poor: a keyboard nobody can type on is broken, and
 * everything above that line is taste.
 */
@Composable
private fun ReadabilityBanner(
    theme: ThemeSpec,
    measurements: PhotoMeasurements?,
    onScrim: (Float) -> Unit,
    onBlur: () -> Unit,
) {
    val measured = measurements ?: return
    val verdict = remember(measured, theme) {
        readabilityOver(
            photoLuminance = measured.luminance,
            photoVariation = 0f,
            imageOpacity = theme.backgroundImageOpacity,
            boardArgb = theme.boardBackground,
            keyBackgroundArgb = theme.keyBackground,
            keyTextArgb = theme.keyText,
            blur = theme.backgroundImageBlur,
        )
    }
    when {
        verdict.poor || verdict.marginal -> {
            val light = !theme.dark
            PhotoNotice(
                icon = Icons.Outlined.Crop,
                title = stringResource(R.string.photo_readability_warn_title),
                body = stringResource(
                    if (light) R.string.photo_readability_warn_light_body
                    else R.string.photo_readability_warn_dark_body,
                ),
                actionLabel = stringResource(
                    if (light) R.string.photo_readability_fix_light_action
                    else R.string.photo_readability_fix_dark_action,
                ),
                onAction = { onScrim(measured.scrimAlpha.coerceAtMost(Readability.MAX_SCRIM_ALPHA)) },
            )
        }
        verdict.busy -> PhotoNotice(
            icon = Icons.Outlined.Crop,
            title = stringResource(R.string.photo_readability_busy_title),
            body = stringResource(R.string.photo_readability_busy_body),
            actionLabel = stringResource(R.string.photo_readability_busy_action),
            onAction = onBlur,
        )
        else -> CaptionText(stringResource(R.string.photo_readability_ok_body))
    }
}

/** Enough of the photo to judge it by, in the shape the keyboard uses. */
private const val HERO_ASPECT = 16f / 9f

/** What the busy-photo fix sets, on the editor's 0..25 scale. */
private const val BUSY_BLUR = 8f


