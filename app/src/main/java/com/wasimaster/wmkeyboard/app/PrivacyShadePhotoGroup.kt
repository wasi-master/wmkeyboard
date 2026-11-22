package com.wasimaster.wmkeyboard.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadePhoto
import com.wasimaster.wmkeyboard.core.settings.PrivacyShadeSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The long edge the shade's copy of a photo is kept at: enough for a phone screen, small in a backup. */
private const val SHADE_PHOTO_MAX_EDGE = 1600

/** JPEG quality of that copy. */
private const val SHADE_PHOTO_QUALITY = 85

/**
 * Tools › Privacy shade › Photo on the shade (#1417): one of the user's own
 * photos laid see-through into the shade, and how strongly it shows.
 *
 * The picked photo is decoded upright and no larger than a screen needs
 * ([MediaPrep.decode]) and kept as a fresh JPEG under
 * [PrivacyShadePhoto.dir], which leaves the original's EXIF (place, camera)
 * behind. The picker grants no lasting access, so the copy is what the
 * keyboard reads.
 */
@Composable
internal fun PrivacyShadePhotoGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val percentFormat = stringResource(R.string.typing_value_percent)
    val failed = stringResource(R.string.tooldetail_privacy_shade_photo_failed)
    val name = settings.watch { it.privacyShade.photo }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = MediaPrep.decode(context, uri, SHADE_PHOTO_MAX_EDGE) ?: return@runCatching null
                    val newName = PrivacyShadePhoto.newName()
                    val file = PrivacyShadePhoto.file(context.filesDir, newName) ?: return@runCatching null
                    file.parentFile?.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, SHADE_PHOTO_QUALITY, it) }
                    bitmap.recycle()
                    newName
                }.getOrNull()
            }
            if (saved == null) {
                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            } else {
                repository.setPrivacyShadePhoto(saved)
            }
        }
    }
    // A name whose file is gone (an undo past a newer pick) counts as no photo.
    val hasPhoto = name != null && PrivacyShadePhoto.file(context.filesDir, name)?.let(File::isFile) == true
    SettingsGroup(
        stringResource(R.string.tooldetail_privacy_shade_photo_group),
        info = stringResource(R.string.tooldetail_privacy_shade_photo_group_info),
    ) {
        item {
            WmRow(
                title = stringResource(
                    if (hasPhoto) {
                        R.string.tooldetail_privacy_shade_photo_change_title
                    } else {
                        R.string.tooldetail_privacy_shade_photo_pick_title
                    },
                ),
                subtitle = stringResource(
                    if (hasPhoto) R.string.tooldetail_privacy_shade_photo_set else R.string.tooldetail_privacy_shade_photo_none,
                ),
                icon = Icons.Outlined.PhotoLibrary,
                highlightKey = R.string.tooldetail_privacy_shade_photo_pick_title,
                trailing = if (hasPhoto) {
                    {
                        IconButton(onClick = { scope.launch { repository.setPrivacyShadePhoto(null) } }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.tooldetail_privacy_shade_photo_remove_desc),
                            )
                        }
                    }
                } else {
                    null
                },
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
        if (hasPhoto) {
            item {
                SliderSetting(
                    R.string.tooldetail_privacy_shade_photo_opacity_title,
                    subtitle = stringResource(R.string.tooldetail_privacy_shade_photo_opacity_subtitle),
                    value = settings.watch { it.privacyShade.photoOpacity } * 100f,
                    range = PrivacyShadeSettings.MIN_PHOTO_OPACITY * 100f..100f,
                    display = { percentFormat.format(it.toInt()) },
                    default = SettingsDefaults.privacyShade.photoOpacity * 100f,
                    info = stringResource(R.string.tooldetail_privacy_shade_photo_opacity_info),
                ) { scope.launch { repository.setPrivacyShadePhotoOpacity(it / 100f) } }
            }
        }
    }
}
