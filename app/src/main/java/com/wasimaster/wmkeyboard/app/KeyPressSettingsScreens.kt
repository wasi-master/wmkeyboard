package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.feedback.R as FeedbackR
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.settings.ShiftCapsLockMsRange
import com.wasimaster.wmkeyboard.core.settings.DefaultCurrencyKeys
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.DEFAULT_LONG_PRESS_LETTERS
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.feedback.SoundFile
import com.wasimaster.wmkeyboard.core.feedback.SoundImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import kotlinx.coroutines.launch

// ---- key press ----

/**
 * Key-press sound controls, shared by the Key press settings screen and the
 * sound & haptics tool's detail page. Changes preview immediately through
 * [KeySoundPlayer].
 */
@Composable
internal fun KeySoundGroup(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val percentFormat = stringResource(R.string.typing_value_percent)
    SettingsGroup(stringResource(R.string.hardware_sound_group_title)) {
        item {
            ToggleSetting(
                R.string.hardware_sound_key_title,
                stringResource(R.string.hardware_sound_key_subtitle),
                settings.keySound,
                default = SettingsDefaults.keySound,
            ) {
                scope.launch { repository.setKeySound(it) }
                if (it) {
                    KeySoundPlayer.preview(context, settings.keySoundStyle, settings.keySoundVolume)
                }
            }
        }
        item {
            // Hand-built rather than a ChoiceSetting (the chips need their own
            // row), so the highlight wrapper every other control gets for free
            // is spelled out here — this is where the Sound addon's Use button
            // lands. The anchor is the row's own string resource, so the match
            // holds in every language.
            HighlightableRow(null, highlightKey = R.string.hardware_sound_style_title) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.hardware_sound_style_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    InfoButton(
                        stringResource(R.string.hardware_sound_style_title),
                        stringResource(R.string.hardware_sound_style_info),
                    )
                }
                // Custom is a segment like any other, so the styles read as one
                // choice rather than five here and a sixth hidden in a list. It
                // names a file rather than a fixed waveform, so it needs a sound
                // installed before it can be picked — the list below is where that
                // sound is chosen and where the "install one" note lives.
                val soundStore = remember { SoundStore.get(context) }
                val soundRevision by soundStore.revision.collectAsStateWithLifecycle()
                val installedSounds = remember(soundRevision) { soundStore.sounds() }
                // Chips rather than a segmented row. Six equal segments across a
                // phone leave ~55dp of label each, which truncated "Chime" to
                // "Chim" and "Custom" to "Custo"; a segmented row set to scroll is
                // worse still, since SegmentedButton has a wide minimum and only
                // three and a half fit. Chips size to their own text, so every
                // style keeps its real name, and the row scrolls only as far as it
                // has to. Same control the addon type filter uses.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in KeySoundStyle.entries) {
                        val custom = style == KeySoundStyle.CUSTOM
                        FilterChip(
                            selected = settings.keySoundStyle == style,
                            onClick = {
                                scope.launch {
                                    if (custom) {
                                        // Falls back to the first installed sound
                                        // when none has been chosen yet, so the
                                        // chip always makes a sound. With nothing
                                        // installed it still selects — the section
                                        // it reveals is where a sound is imported,
                                        // so a disabled chip would hide its own
                                        // remedy.
                                        val id = settings.keySoundCustom.customId
                                            .takeIf { id -> installedSounds.any { it.id == id } }
                                            ?: installedSounds.firstOrNull()?.id
                                        if (id == null) {
                                            repository.setKeySoundStyle(style)
                                        } else {
                                            repository.setKeySoundCustomId(id)
                                            KeySoundPlayer.preview(
                                                context, style, settings.keySoundVolume, id,
                                            )
                                        }
                                    } else {
                                        repository.setKeySoundStyle(style)
                                        // Sound the freshly picked style so the user
                                        // hears the choice immediately.
                                        KeySoundPlayer.preview(context, style, settings.keySoundVolume)
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (style) {
                                            KeySoundStyle.CLICK ->
                                                R.string.hardware_sound_style_click_label
                                            KeySoundStyle.STANDARD ->
                                                R.string.hardware_sound_style_standard_label
                                            KeySoundStyle.POP ->
                                                R.string.hardware_sound_style_pop_label
                                            KeySoundStyle.THOCK ->
                                                R.string.hardware_sound_style_thock_label
                                            KeySoundStyle.CHIME ->
                                                R.string.hardware_sound_style_chime_label
                                            KeySoundStyle.CUSTOM -> CommonR.string.common_custom
                                            KeySoundStyle.PACK ->
                                                R.string.hardware_sound_pack_style_label
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
        // Only under Custom. The sound library and its import button are what
        // Custom *means*; showing them under Click is offering a choice that
        // has no effect until the style changes too.
        if (settings.keySoundStyle == KeySoundStyle.CUSTOM) {
            item { InstalledSoundSection(repository, settings, onNavigate) }
        }
        if (settings.keySoundStyle == KeySoundStyle.PACK) {
            item { InstalledSoundPackSection(repository, settings, onNavigate) }
            item { KeyReleaseSoundToggle(repository, settings) }
        }
        item {
            SliderSetting(
                R.string.hardware_sound_volume_title,
                subtitle = stringResource(R.string.hardware_sound_volume_subtitle),
                value = settings.keySoundVolume,
                range = 0.05f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = SettingsDefaults.keySoundVolume,
            ) {
                scope.launch { repository.setKeySoundVolume(it) }
                // Debounced inside the player, so dragging previews smoothly.
                KeySoundPlayer.preview(context, settings.keySoundStyle, it)
            }
        }
    }
}
/**
 * The installed key sounds — whatever came from an addon repository, plus
 * anything imported here — and the import button.
 *
 * Picking one also switches the style to [KeySoundStyle.CUSTOM]; choosing a
 * sound and then finding the keyboard still clicking would be baffling.
 */
@Composable
private fun InstalledSoundSection(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val sounds = remember(revision) { store.sounds() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundFile.import(it, store, name = fontFileLabel(context, uri))
                    }
                }.getOrElse {
                    SoundImportResult.Failed(FeedbackR.string.core_feedback_sound_import_read_error)
                }
            }
            when (result) {
                is SoundImportResult.Imported -> {
                    repository.setKeySoundCustomId(result.sound.id)
                    KeySoundPlayer.preview(
                        context, KeySoundStyle.CUSTOM, settings.keySoundVolume, result.sound.id,
                    )
                }
                is SoundImportResult.NotASound -> message = context.getString(result.messageRes)
                SoundImportResult.TooManySounds ->
                    message = context.resources.getQuantityString(
                        R.plurals.hardware_sound_limit_error,
                        SoundStore.MAX_SOUNDS,
                        SoundStore.MAX_SOUNDS,
                    )
                // The refusal carries at most one argument, and "" means none.
                is SoundImportResult.Failed -> message = if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (sounds.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_empty))
        }
        for (sound in sounds) {
            val selected = settings.keySoundStyle == KeySoundStyle.CUSTOM &&
                settings.keySoundCustom.customId == sound.id
            HighlightableItem(sound.id) {
                WmRow(
                    title = sound.name,
                    supporting = sound.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(sound.id) }
                                    // The pool keeps its decoded copy independently
                                    // of the file, so it has to be told too.
                                    KeySoundPlayer.forgetCustom(sound.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        sound.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundCustomId(sound.id) }
                        KeySoundPlayer.preview(
                            context, KeySoundStyle.CUSTOM, settings.keySoundVolume, sound.id,
                        )
                    },
                )
            }
        }
        // Beside the file importer: the store is the other way to get a sound,
        // and it is the one that works when the user has no file to import.
        AddonStoreRow(AddonType.Sound, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_import_action)) }
    }
}
/**
 * The installed sound packs, and the import button.
 *
 * A pack differs from a single sound in the one way worth showing on the row:
 * how many recordings it holds, and which key roles it recorded separately.
 * Tapping one plays a variant, so tapping twice actually demonstrates the
 * variation rather than repeating itself.
 *
 * There is no file-picker mime type worth narrowing to — a `.wmsoundpack` is a
 * ZIP, and providers report a custom extension as `application/octet-stream` as
 * often as not — so the importer's own list is used and the real check is the
 * manifest inside.
 */
@Composable
private fun InstalledSoundPackSection(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packs = remember(revision) { store.packs() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundPackFile.import(it, store, fallbackName = fontFileLabel(context, uri))
                    }
                }.getOrElse { SoundPackImportResult.Failed }
            }
            when (result) {
                is SoundPackImportResult.Imported -> {
                    repository.setKeySoundPackId(result.pack.id)
                    KeySoundPlayer.previewStroke(
                        context, KeySoundStyle.PACK, settings.keySoundVolume, result.pack.id,
                    )
                }
                SoundPackImportResult.NotASoundPack ->
                    message = context.getString(R.string.hardware_sound_pack_not_a_pack_error)
                SoundPackImportResult.TooManyPacks ->
                    message = context.resources.getQuantityString(
                        R.plurals.hardware_sound_pack_limit_error,
                        SoundPackStore.MAX_PACKS,
                        SoundPackStore.MAX_PACKS,
                    )
                // The refusal carries at most one argument, and "" means none.
                is SoundPackImportResult.Rejected -> message = if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
                SoundPackImportResult.Failed ->
                    message = context.getString(R.string.hardware_sound_pack_read_error)
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (packs.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_pack_empty))
        }
        for (pack in packs) {
            val selected = settings.keySoundStyle == KeySoundStyle.PACK &&
                settings.keySoundCustom.packId == pack.id
            HighlightableItem(pack.id) {
                WmRow(
                    title = pack.name,
                    supporting = {
                        val roles = pack.roles
                        val variants = pluralStringResource(
                            R.plurals.hardware_sound_pack_variants,
                            pack.variantCount,
                            pack.variantCount,
                        )
                        val counts = if (roles.isEmpty()) {
                            variants
                        } else {
                            stringResource(
                                R.string.hardware_sound_pack_variants_and_roles,
                                variants,
                                roles.joinToString(", "),
                            )
                        }
                        // Appended rather than given its own line: it is one
                        // more fact about the pack, and the row already has a
                        // subtitle that reads as a list.
                        Text(
                            if (pack.hasRelease == true) {
                                stringResource(
                                    R.string.hardware_sound_pack_with_release,
                                    counts,
                                )
                            } else {
                                counts
                            },
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(pack.id) }
                                    // The pool keeps its decoded samples
                                    // independently of the files.
                                    KeySoundPlayer.forgetPack(pack.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        pack.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundPackId(pack.id) }
                        // The whole keystroke, not just the way down: a pack
                        // that recorded the switch returning is being judged on
                        // both halves, and half of it is a different pack.
                        KeySoundPlayer.previewStroke(
                            context, KeySoundStyle.PACK, settings.keySoundVolume, pack.id,
                        )
                    },
                )
            }
        }
        AddonStoreRow(AddonType.SoundPack, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundPackFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_pack_import_action)) }
    }
}
/**
 * "Play the key coming back up", for packs that recorded it.
 *
 * Draws nothing at all when the selected pack has no key-up recordings, which
 * is most of them: a switch the user can flip and hear no difference from is
 * worse than an absent one — it reads as the feature being broken rather than
 * as the pack not having it. The toggle appearing *is* how a pack announces it
 * has both halves.
 */
@Composable
private fun KeyReleaseSoundToggle(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packId = settings.keySoundCustom.packId
    val hasRelease = remember(revision, packId) {
        store.resolve(packId)?.let { store.pack(it)?.hasRelease } == true
    }
    if (!hasRelease) return
    ToggleSetting(
        R.string.hardware_sound_pack_release_title,
        stringResource(R.string.hardware_sound_pack_release_subtitle),
        settings.keySoundCustom.playRelease,
        default = SettingsDefaults.keySoundCustom.playRelease,
    ) { on ->
        scope.launch { repository.setKeySoundPlayRelease(on) }
        // Turning it on previews the whole keystroke, which is the only way to
        // hear what the switch just bought; turning it off previews the press
        // alone, so the difference is the thing demonstrated either way.
        if (on) {
            KeySoundPlayer.previewStroke(
                context, KeySoundStyle.PACK, settings.keySoundVolume, packId,
            )
        } else {
            KeySoundPlayer.preview(context, KeySoundStyle.PACK, settings.keySoundVolume, packId)
        }
    }
}
@Composable
internal fun KeyPressSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Lets the SYSTEM_* preview fire through the real platform key haptic.
    SettingsGroup {
        item {
            NavRow(
                R.string.keypress_haptics_group_title,
                stringResource(R.string.keypress_haptics_group_subtitle),
                route = "keypress/haptics",
            ) {
                onNavigate("keypress/haptics")
            }
        }
        item {
            NavRow(
                R.string.keypress_popup_group_title,
                stringResource(R.string.keypress_popup_group_subtitle),
                route = "keypress/popup",
            ) {
                onNavigate("keypress/popup")
            }
        }
        item {
            NavRow(
                R.string.keypress_shortcuts_group_title,
                stringResource(R.string.keypress_shortcuts_group_subtitle),
                route = "keypress/shortcuts",
            ) {
                onNavigate("keypress/shortcuts")
            }
        }
    }

    KeySoundGroup(repository, settings, onNavigate)


    // The alternates ("more keys") get their own group rather than joining the
    // bubble's: they are a different popup, sized against a grid instead of a
    // fixed box, and sharing the bubble's font slider was what kept them small
    // (issue #64).
    SettingsGroup(stringResource(R.string.keypress_alternates_group_title)) {
        item {
            SliderSetting(
                R.string.keypress_alternates_size_title,
                subtitle = stringResource(R.string.keypress_alternates_size_subtitle),
                value = settings.popup.alternatesFontScale,
                range = 0.7f..3.2f,
                display = { context.getString(R.string.keypress_value_multiplier, it) },
                info = stringResource(R.string.keypress_alternates_size_info),
                default = SettingsDefaults.popup.alternatesFontScale,
            ) { scope.launch { repository.setAlternatesFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_alternates_padding_title,
                subtitle = stringResource(R.string.keypress_alternates_padding_subtitle),
                value = settings.popup.alternatesPaddingDp.toFloat(),
                range = 0f..32f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_alternates_padding_info),
                default = SettingsDefaults.popup.alternatesPaddingDp.toFloat(),
            ) { scope.launch { repository.setAlternatesPaddingDp(it.toInt()) } }
        }
        item {
            StepperSetting(
                R.string.keypress_alternates_columns_title,
                subtitle = stringResource(R.string.keypress_alternates_columns_subtitle),
                value = settings.popup.alternatesColumns,
                range = AlternatesColumnsRange,
                // 0 is not a column count but the automatic wrap, so it steps in
                // from 3 rather than 1 and reads as a word instead of a number.
                display = {
                    if (it == 0) {
                        context.getString(R.string.keypress_alternates_columns_auto)
                    } else {
                        it.toString()
                    }
                },
                info = stringResource(R.string.keypress_alternates_columns_info),
                default = SettingsDefaults.popup.alternatesColumns,
            ) { scope.launch { repository.setAlternatesColumns(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_alternates_nearest_title,
                stringResource(R.string.keypress_alternates_nearest_subtitle),
                settings.popup.alternatesNearestFirst,
                info = stringResource(R.string.keypress_alternates_nearest_info),
                default = SettingsDefaults.popup.alternatesNearestFirst,
            ) { scope.launch { repository.setAlternatesNearestFirst(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.keypress_timing_group_title)) {
        item {
            SliderSetting(
                R.string.keypress_long_press_delay_title,
                subtitle = stringResource(R.string.keypress_long_press_delay_subtitle),
                value = settings.longPressDelayMs.toFloat(),
                range = 150f..800f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_long_press_delay_info),
                default = SettingsDefaults.longPressDelayMs.toFloat(),
            ) { scope.launch { repository.setLongPressDelayMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_repeat_start_title,
                subtitle = stringResource(R.string.keypress_repeat_start_subtitle),
                value = settings.keyRepeat.startDelayMs.toFloat(),
                range = 150f..800f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_repeat_start_info),
                default = SettingsDefaults.keyRepeat.startDelayMs.toFloat(),
            ) { scope.launch { repository.setKeyRepeatStartDelayMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_delete_repeat_title,
                subtitle = stringResource(R.string.keypress_delete_repeat_subtitle),
                value = settings.keyRepeat.deleteMs.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_delete_repeat_info),
                default = SettingsDefaults.keyRepeat.deleteMs.toFloat(),
            ) { scope.launch { repository.setDeleteRepeatIntervalMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_space_repeat_title,
                subtitle = stringResource(R.string.keypress_space_repeat_subtitle),
                value = settings.keyRepeat.spaceMs.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_space_repeat_info),
                default = SettingsDefaults.keyRepeat.spaceMs.toFloat(),
            ) { scope.launch { repository.setSpaceRepeatIntervalMs(it.toInt()) } }
        }
        item {
            SliderSetting(
                R.string.keypress_caps_lock_title,
                subtitle = stringResource(R.string.keypress_caps_lock_subtitle),
                value = settings.layoutBehavior.shiftCapsLockMs.toFloat(),
                range = ShiftCapsLockMsRange.first.toFloat()..ShiftCapsLockMsRange.last.toFloat(),
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_caps_lock_info),
                default = SettingsDefaults.layoutBehavior.shiftCapsLockMs.toFloat(),
            ) { scope.launch { repository.setShiftCapsLockMs(it.toInt()) } }
        }
    }

}

@Composable
internal fun KeyPressHapticsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    SettingsGroup(stringResource(R.string.keypress_haptics_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_haptics_title,
                stringResource(R.string.keypress_haptics_subtitle),
                settings.hapticFeedback,
                info = stringResource(R.string.keypress_haptics_info),
                default = SettingsDefaults.hapticFeedback,
            ) {
                scope.launch { repository.setHapticFeedback(it) }
                if (it) {
                    HapticPlayer.preview(
                        context, settings.hapticStyle, settings.hapticAmplitude, settings.hapticStrengthMs,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.keypress_haptic_style_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                InfoButton(
                    stringResource(R.string.keypress_haptic_style_title),
                    stringResource(R.string.keypress_haptic_style_info),
                )
            }
            // Six styles overflow a segmented row; wrapping chips give each a
            // full, readable label. Ordered best-to-worst via HapticStyle.entries.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                HapticStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.hapticStyle == style,
                        onClick = {
                            scope.launch { repository.setHapticStyle(style) }
                            // Fire the motor with the freshly picked style so the
                            // user feels the choice immediately.
                            HapticPlayer.preview(
                                context, style, settings.hapticAmplitude, settings.hapticStrengthMs, view,
                            )
                        },
                        label = { Text(stringResource(style.labelRes), maxLines = 1) },
                    )
                }
            }
        }
        if (settings.hapticStyle == HapticStyle.CUSTOM) {
            item {
                SliderSetting(
                    R.string.keypress_haptic_strength_title,
                    subtitle = stringResource(R.string.keypress_haptic_strength_subtitle),
                    value = settings.hapticStrengthMs.toFloat(),
                    range = 5f..60f,
                    display = { context.getString(R.string.keypress_value_ms, it.roundToInt()) },
                    info = stringResource(R.string.keypress_haptic_strength_info),
                    default = SettingsDefaults.hapticStrengthMs.toFloat(),
                ) {
                    scope.launch { repository.setHapticStrengthMs(it.toInt()) }
                    // Debounced inside the player, so dragging previews smoothly.
                    HapticPlayer.preview(context, settings.hapticStyle, settings.hapticAmplitude, it.toInt(), view)
                }
            }
        }
        if (settings.hapticStyle == HapticStyle.CUSTOM || settings.hapticStyle == HapticStyle.SHARP) {
            item {
                SliderSetting(
                    R.string.keypress_haptic_intensity_title,
                    subtitle = stringResource(R.string.keypress_haptic_intensity_subtitle),
                    value = settings.hapticAmplitude.toFloat(),
                    range = 1f..255f,
                    display = {
                        context.getString(R.string.keypress_value_percent, it.roundToInt() * 100 / 255)
                    },
                    info = stringResource(R.string.keypress_haptic_intensity_info),
                    default = SettingsDefaults.hapticAmplitude.toFloat(),
                ) {
                    scope.launch { repository.setHapticAmplitude(it.toInt()) }
                    HapticPlayer.preview(context, settings.hapticStyle, it.toInt(), settings.hapticStrengthMs, view)
                }
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_long_press_haptics_title,
                stringResource(R.string.keypress_long_press_haptics_subtitle),
                settings.hapticOnLongPress,
                info = stringResource(R.string.keypress_long_press_haptics_info),
                default = SettingsDefaults.hapticOnLongPress,
            ) { scope.launch { repository.setHapticOnLongPress(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_long_press_release_title,
                stringResource(R.string.keypress_long_press_release_subtitle),
                settings.hapticOnLongPressRelease,
                info = stringResource(R.string.keypress_long_press_release_info),
                default = SettingsDefaults.hapticOnLongPressRelease,
            ) { scope.launch { repository.setHapticOnLongPressRelease(it) } }
        }
        // Per-event gates: only meaningful while the master switch above is on,
        // so they fold away when it is off.
        if (settings.hapticFeedback) {
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_space_title,
                    stringResource(R.string.keypress_vibrate_space_subtitle),
                    settings.feedback.vibrateOnSpace,
                    info = stringResource(R.string.keypress_vibrate_space_info),
                    default = SettingsDefaults.feedback.vibrateOnSpace,
                ) { scope.launch { repository.setVibrateOnSpace(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_delete_swipe_title,
                    stringResource(R.string.keypress_vibrate_delete_swipe_subtitle),
                    settings.feedback.vibrateOnDeleteSwipe,
                    info = stringResource(R.string.keypress_vibrate_delete_swipe_info),
                    default = SettingsDefaults.feedback.vibrateOnDeleteSwipe,
                ) { scope.launch { repository.setVibrateOnDeleteSwipe(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_vibrate_repeat_title,
                    stringResource(R.string.keypress_vibrate_repeat_subtitle),
                    settings.feedback.vibrateOnRepeat,
                    info = stringResource(R.string.keypress_vibrate_repeat_info),
                    default = SettingsDefaults.feedback.vibrateOnRepeat,
                ) { scope.launch { repository.setVibrateOnRepeat(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_sound_repeat_title,
                    stringResource(R.string.keypress_sound_repeat_subtitle),
                    settings.feedback.soundOnRepeat,
                    info = stringResource(R.string.keypress_sound_repeat_info),
                    default = SettingsDefaults.feedback.soundOnRepeat,
                ) { scope.launch { repository.setSoundOnRepeat(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_system_touch_title,
                    stringResource(R.string.keypress_system_touch_subtitle),
                    settings.feedback.respectSystemTouchFeedback,
                    info = stringResource(R.string.keypress_system_touch_info),
                    default = SettingsDefaults.feedback.respectSystemTouchFeedback,
                ) { scope.launch { repository.setRespectSystemTouchFeedback(it) } }
            }
            item {
                ToggleSetting(
                    R.string.keypress_dnd_mute_title,
                    stringResource(R.string.keypress_dnd_mute_subtitle),
                    settings.feedback.hapticsRespectDnd,
                    info = stringResource(R.string.keypress_dnd_mute_info),
                    default = SettingsDefaults.feedback.hapticsRespectDnd,
                ) { scope.launch { repository.setHapticsRespectDnd(it) } }
            }
        }
    }
}

@Composable
internal fun KeyPressPopupSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var popupShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    if (popupShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.popup.shape,
            radiusDp = settings.popup.cornerRadiusDp,
            onPick = { kind ->
                scope.launch { repository.setKeyPopupShape(kind) }
                popupShapePickerOpen = false
            },
            onDismiss = { popupShapePickerOpen = false },
            title = R.string.keypress_popup_shape_title,
        )
    }
    SettingsGroup(stringResource(R.string.keypress_popup_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_popup_title,
                stringResource(R.string.keypress_popup_subtitle),
                settings.popup.enabled,
                info = stringResource(R.string.keypress_popup_info),
                default = SettingsDefaults.popup.enabled,
            ) { scope.launch { repository.setKeyPopup(it) } }
        }
        if (settings.popup.enabled) {
            item {
                ToggleSetting(
                    R.string.keypress_popup_numeric_title,
                    stringResource(R.string.keypress_popup_numeric_subtitle),
                    settings.popup.inNumericFields,
                    info = stringResource(R.string.keypress_popup_numeric_info),
                    default = SettingsDefaults.popup.inNumericFields,
                ) { scope.launch { repository.setKeyPopupInNumericFields(it) } }
            }
            item {
                SliderSetting(
                    R.string.keypress_popup_min_duration_title,
                    subtitle = stringResource(R.string.keypress_popup_min_duration_subtitle),
                    value = settings.popup.minDurationMs.toFloat(),
                    range = 0f..300f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_min_duration_info),
                    default = SettingsDefaults.popup.minDurationMs.toFloat(),
                ) { scope.launch { repository.setKeyPopupMinDurationMs(it.toInt()) } }
            }
            item {
                SliderSetting(
                    R.string.keypress_popup_max_duration_title,
                    subtitle = stringResource(R.string.keypress_popup_max_duration_subtitle),
                    value = settings.popup.maxDurationMs.toFloat(),
                    range = 400f..2000f,
                    display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_max_duration_info),
                    default = SettingsDefaults.popup.maxDurationMs.toFloat(),
                ) { scope.launch { repository.setKeyPopupMaxDurationMs(it.toInt()) } }
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_popup_on_key_title,
                stringResource(R.string.keypress_popup_on_key_subtitle),
                settings.popup.onKey,
                info = stringResource(R.string.keypress_popup_on_key_info),
                default = SettingsDefaults.popup.onKey,
            ) { scope.launch { repository.setKeyPopupOnKey(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_popup_font_size_title,
                subtitle = stringResource(R.string.keypress_popup_font_size_subtitle),
                value = settings.popup.fontScale,
                range = 0.7f..1.6f,
                display = { context.getString(R.string.keypress_value_multiplier, it) },
                info = stringResource(R.string.keypress_popup_font_size_info),
                default = SettingsDefaults.popup.fontScale,
            ) { scope.launch { repository.setPopupFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_popup_height_title,
                subtitle = stringResource(R.string.keypress_popup_height_subtitle),
                value = settings.popup.heightDp.toFloat(),
                range = 32f..160f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_height_info),
                default = SettingsDefaults.popup.heightDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupHeightDp(it.toInt()) } }
        }
        // Only the floating bubble is placed by these. The on-key one grows out
        // of the key it covers, so there is no distance to set: its height is
        // what carries it past the finger, and that slider is above.
        if (!settings.popup.onKey) {
            item {
                SliderSetting(
                    R.string.keypress_popup_offset_y_title,
                    subtitle = stringResource(R.string.keypress_popup_offset_y_subtitle),
                    value = settings.popup.floatingOffsetYDp.toFloat(),
                    range = 0f..96f,
                    display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_offset_y_info),
                    default = SettingsDefaults.popup.floatingOffsetYDp.toFloat(),
                ) { scope.launch { repository.setKeyPopupFloatingOffsetYDp(it.toInt()) } }
            }
            item {
                SliderSetting(
                    R.string.keypress_popup_offset_x_title,
                    subtitle = stringResource(R.string.keypress_popup_offset_x_subtitle),
                    value = settings.popup.floatingOffsetXDp.toFloat(),
                    range = -64f..64f,
                    display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                    info = stringResource(R.string.keypress_popup_offset_x_info),
                    default = SettingsDefaults.popup.floatingOffsetXDp.toFloat(),
                ) { scope.launch { repository.setKeyPopupFloatingOffsetXDp(it.toInt()) } }
            }
        }
        // Shape and radius govern every popup surface, not only the preview
        // bubble: the long-press alternates, the language picker and the panel
        // menus all draw with them.
        item {
            NavRow(
                R.string.keypress_popup_shape_title,
                subtitle = stringResource(R.string.keypress_popup_shape_subtitle),
                value = keyShapeName(settings.popup.shape),
                onClick = { popupShapePickerOpen = true },
            )
        }
        item {
            SliderSetting(
                R.string.keypress_popup_radius_title,
                subtitle = stringResource(R.string.keypress_popup_radius_subtitle),
                value = settings.popup.cornerRadiusDp.toFloat(),
                range = 0f..40f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_radius_info),
                default = SettingsDefaults.popup.cornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupCornerRadiusDp(it.toInt()) } }
        }
        // Unlike shape and radius, these two stop at the preview bubble: the
        // alternates, the language picker and the panel menus keep the theme's
        // popup colour. Seeded from the settings app's own surface, which is
        // only where the wheel opens — the keyboard's palette cannot be read
        // here without repainting this screen in it.
        item {
            ColorSetting(
                R.string.keypress_popup_background_title,
                subtitle = stringResource(R.string.keypress_popup_background_subtitle),
                color = settings.popup.backgroundColor,
                fallback = MaterialTheme.colorScheme.surfaceVariant.argbLong(),
                info = stringResource(R.string.keypress_popup_background_info),
            ) { scope.launch { repository.setKeyPopupBackgroundColor(it) } }
        }
        item {
            ColorSetting(
                R.string.keypress_popup_text_color_title,
                subtitle = stringResource(R.string.keypress_popup_text_color_subtitle),
                color = settings.popup.textColor,
                fallback = MaterialTheme.colorScheme.onSurfaceVariant.argbLong(),
                info = stringResource(R.string.keypress_popup_text_color_info),
            ) { scope.launch { repository.setKeyPopupTextColor(it) } }
        }
    }
}

@Composable
internal fun KeyPressShortcutsSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
) {
    val scope = rememberCoroutineScope()
    SettingsGroup(stringResource(R.string.keypress_shortcuts_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_long_press_hints_title,
                stringResource(R.string.keypress_long_press_hints_subtitle),
                settings.longPressHints,
                info = stringResource(R.string.keypress_long_press_hints_info),
                default = SettingsDefaults.longPressHints,
            ) { scope.launch { repository.setLongPressHints(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_all_accents_title,
                stringResource(R.string.keypress_all_accents_subtitle),
                settings.layoutBehavior.showAllPopupKeys,
                info = stringResource(R.string.keypress_all_accents_info),
                default = SettingsDefaults.layoutBehavior.showAllPopupKeys,
            ) { scope.launch { repository.setShowAllPopupKeys(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_symbols_numpad_title,
                stringResource(R.string.keypress_symbols_numpad_subtitle),
                settings.layoutBehavior.symbolsLongPressNumpad,
                info = stringResource(R.string.keypress_symbols_numpad_info),
                default = SettingsDefaults.layoutBehavior.symbolsLongPressNumpad,
            ) { scope.launch { repository.setSymbolsLongPressNumpad(it) } }
        }
        item {
            // A44: the $ key's long-press currency glyphs, space-separated. Blank
            // restores the built-in set. Mirrors the layout editor's alternates field.
            var currencyText by remember(settings.layoutBehavior.currencyKeys) {
                mutableStateOf(
                    settings.layoutBehavior.currencyKeys
                        .ifEmpty { DefaultCurrencyKeys }
                        .joinToString(" "),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.keypress_currency_keys_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    InfoButton(
                        stringResource(R.string.keypress_currency_keys_title),
                        stringResource(R.string.keypress_currency_keys_info),
                    )
                }
                OutlinedTextField(
                    value = currencyText,
                    onValueChange = {
                        currencyText = it
                        scope.launch {
                            repository.setCurrencyKeys(it.trim().split(" ").filter { s -> s.isNotBlank() })
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_ctrl_raw_title,
                stringResource(R.string.keypress_ctrl_raw_subtitle),
                settings.rawClipboardShortcuts,
                info = stringResource(R.string.keypress_ctrl_raw_info),
                default = SettingsDefaults.rawClipboardShortcuts,
            ) { scope.launch { repository.setRawClipboardShortcuts(it) } }
        }
        item {
            val actions = settings.longPressLetterActions
            MultiChoiceSetting(
                R.string.keypress_hold_actions_title,
                subtitle = stringResource(R.string.keypress_hold_actions_subtitle),
                info = stringResource(R.string.keypress_hold_actions_info),
                options = HoldAction.entries.map { it to stringResource(it.labelRes) },
                selected = HoldAction.entries.filterTo(mutableSetOf()) { it.isOn(actions) },
                default = DefaultHoldActions,
            ) { chosen ->
                scope.launch {
                    for (action in HoldAction.entries) {
                        val on = action in chosen
                        if (on != action.isOn(actions)) action.set(repository, on)
                    }
                }
            }
        }
        val holdActions = settings.longPressLetterActions
        if (holdActions.selectAll || holdActions.copy || holdActions.paste ||
            holdActions.cut || holdActions.undo || holdActions.redo
        ) {
            item { HoldShortcutLettersSetting(repository, holdActions) }
        }
    }
}
/**
 * Which key each hold shortcut sits on.
 *
 * One row for all six rather than six rows, because the six letters are one
 * decision: the shipped `acvxzy` is the QWERTY answer, and someone typing
 * Bengali or Russian is rebinding the whole set at once or not at all. Shown
 * only once at least one of the six actions is on, since it has nothing to say
 * otherwise.
 */
@Composable
private fun HoldShortcutLettersSetting(
    repository: SettingsRepository,
    actions: LongPressLetterActions,
) {
    val scope = rememberCoroutineScope()
    val labels = listOf(
        R.string.keypress_hold_action_select_all_label,
        R.string.keypress_hold_action_copy_label,
        R.string.keypress_hold_action_paste_label,
        R.string.keypress_hold_action_cut_label,
        R.string.keypress_hold_action_undo_label,
        R.string.keypress_hold_action_redo_label,
    )
    val enabled = listOf(
        actions.selectAll, actions.copy, actions.paste,
        actions.cut, actions.undo, actions.redo,
    )
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.keypress_hold_letters_title)
    // Only the keys that are actually bound, so the summary reads as what the
    // keyboard will do rather than as the whole six-character string.
    val summary = enabled.mapIndexedNotNull { slot, on ->
        actions.letterFor(slot)?.takeIf { on }?.uppercase()
    }.joinToString(" ")
    HighlightableRow(title) {
        WmRow(
            title = title,
            subtitle = summary.ifEmpty { stringResource(R.string.keypress_hold_letters_subtitle) },
            trailing = {
                ResetSetting(title, actions.letters != DEFAULT_LONG_PRESS_LETTERS) {
                    scope.launch { repository.setLongPressLetters(DEFAULT_LONG_PRESS_LETTERS) }
                }
            },
            onClick = { editing = true },
        )
    }
    if (!editing) return
    // Edited as one string and written once on Save: a per-character write
    // would push five malformed values through the setter on the way to a
    // valid one, and the setter refuses anything that is not six characters.
    var draft by remember(actions.letters) { mutableStateOf(actions.letters) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(title) },
        text = {
            Column {
                DialogNote(stringResource(R.string.keypress_hold_letters_dialog_note))
                Spacer(Modifier.height(8.dp))
                labels.forEachIndexed { slot, labelRes ->
                    if (!enabled[slot]) return@forEachIndexed
                    OutlinedTextField(
                        value = draft.getOrNull(slot)?.toString().orEmpty(),
                        onValueChange = { typed ->
                            val ch = typed.lastOrNull() ?: return@OutlinedTextField
                            draft = draft.mapIndexed { i, old ->
                                if (i == slot) ch.lowercaseChar() else old
                            }.joinToString("")
                        },
                        label = { Text(stringResource(labelRes)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = false
                scope.launch { repository.setLongPressLetters(draft) }
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

/**
 * The six hold shortcuts as one set, since they are one decision: which
 * letter keys give up their accent popup for an edit action.
 */
private enum class HoldAction(
    @StringRes val labelRes: Int,
    val isOn: (LongPressLetterActions) -> Boolean,
    val set: suspend (SettingsRepository, Boolean) -> Unit,
) {
    SELECT_ALL(R.string.keypress_hold_action_select_all_label, { it.selectAll }, { r, on -> r.setLongPressASelectAll(on) }),
    COPY(R.string.keypress_hold_action_copy_label, { it.copy }, { r, on -> r.setLongPressCCopy(on) }),
    CUT(R.string.keypress_hold_action_cut_label, { it.cut }, { r, on -> r.setLongPressXCut(on) }),
    PASTE(R.string.keypress_hold_action_paste_label, { it.paste }, { r, on -> r.setLongPressVPaste(on) }),
    UNDO(R.string.keypress_hold_action_undo_label, { it.undo }, { r, on -> r.setLongPressZUndo(on) }),
    REDO(R.string.keypress_hold_action_redo_label, { it.redo }, { r, on -> r.setLongPressYRedo(on) }),
}

private val DefaultHoldActions: Set<HoldAction> =
    HoldAction.entries.filterTo(mutableSetOf()) { it.isOn(SettingsDefaults.longPressLetterActions) }
