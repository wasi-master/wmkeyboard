package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Process
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AppShortcut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bookmarks
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiSymbols
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.plugins.displayName
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Handyman
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardCapslock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lightbulb
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MotionPhotosOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Numbers
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Subtitles
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TableRows
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewDay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Accessibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Backup
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BarChart
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Brush
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DarkMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dashboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Height
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SmartToy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SpaceBar
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Swipe
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VerticalSplit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_LAUNCHER_SHORTCUT_PREFIX
import com.wasimaster.wmkeyboard.core.settings.CustomButton
import com.wasimaster.wmkeyboard.core.settings.CustomButtonKind
import com.wasimaster.wmkeyboard.core.settings.CustomPage
import com.wasimaster.wmkeyboard.core.settings.CustomToggle
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.customButtonById
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.floating.FloatingAppIcons
import java.io.File

/**
 * The user's own buttons (#1213, #1236), handed down from the service with
 * the other toolbar callbacks. [onTap] runs one by its id: opens the
 * shortcut, flips the setting or opens the page. [onSettings] is a hold that
 * picks nothing up: the buttons' page in Settings.
 */
@Immutable
class CustomButtonCallbacks(
    val onTap: (String) -> Unit = {},
    val onSettings: (String) -> Unit = {},
)

/** The icon slot a custom button draws with, so an icon pack can dress it too. */
fun customButtonSlot(button: CustomButton): String = when (button.kind) {
    CustomButtonKind.SHORTCUT -> CUSTOM_SHORTCUT_SLOT
    CustomButtonKind.SETTING -> button.toggle?.let(::customToggleSlot) ?: CUSTOM_SHORTCUT_SLOT
    CustomButtonKind.PAGE -> button.page?.let(::customPageSlot) ?: CUSTOM_SHORTCUT_SLOT
    CustomButtonKind.PLUGIN -> CUSTOM_PLUGIN_SLOT
}

/** A plugin's tile. */
const val CUSTOM_PLUGIN_SLOT = "custom.plugin"

fun customToggleSlot(toggle: CustomToggle): String = "custom.toggle.${toggle.name.lowercase()}"

fun customPageSlot(page: CustomPage): String = "custom.page.${page.name.lowercase()}"

/** A shortcut whose own picture is gone, and the fallback for anything unknown. */
const val CUSTOM_SHORTCUT_SLOT = "custom.shortcut"

/** Where the slot of one shortcut button, drawn with its own picture, starts; see [customToolSlot]. */
private const val CUSTOM_PICTURE_SLOT_PREFIX = "custom.button."

/**
 * The slot custom button [id] draws through as a tool (#1345): a switch's or a
 * page's glyph slot, which an icon pack can dress, or for a shortcut a slot of
 * its own that [SlotIcon] draws with the shortcut's picture. Reads
 * [CustomButtonFaces], which is snapshot state, so a cell redraws when the
 * button changes.
 */
fun customToolSlot(id: String): String {
    val button = CustomButtonFaces.byId(id) ?: return CUSTOM_SHORTCUT_SLOT
    return if (button.kind == CustomButtonKind.SHORTCUT) CUSTOM_PICTURE_SLOT_PREFIX + id else customButtonSlot(button)
}

/** The shortcut button a slot from [customToolSlot] draws the picture of, or null for any other slot. */
internal fun customPictureOfSlot(slot: String): String? =
    if (slot.startsWith(CUSTOM_PICTURE_SLOT_PREFIX)) slot.removePrefix(CUSTOM_PICTURE_SLOT_PREFIX) else null

/** The built-in glyphs of the custom-button slots, for [IconDefaults]' table. */
internal fun customButtonSlotIcons(): Map<String, ImageVector> = buildMap {
    put(CUSTOM_SHORTCUT_SLOT, Icons.Outlined.AppShortcut)
    put(CUSTOM_PLUGIN_SLOT, Icons.Outlined.Extension)
    for (toggle in CustomToggle.entries) put(customToggleSlot(toggle), customToggleIcon(toggle))
    for (page in CustomPage.entries) put(customPageSlot(page), customPageIcon(page))
}

fun customToggleIcon(toggle: CustomToggle): ImageVector = when (toggle) {
    CustomToggle.NUMBER_ROW -> Icons.Outlined.Numbers
    CustomToggle.KEY_HINTS -> Icons.Outlined.Subtitles
    CustomToggle.SYMBOL_ROW -> Icons.Outlined.EmojiSymbols
    CustomToggle.SUGGESTIONS -> Icons.Outlined.Lightbulb
    CustomToggle.GLIDE_TYPING -> Icons.Outlined.Gesture
    CustomToggle.AUTO_CAPITALIZE -> Icons.Outlined.KeyboardCapslock
    CustomToggle.KEY_SOUND -> Icons.Outlined.VolumeUp
    CustomToggle.VIBRATION -> Icons.Outlined.Vibration
    CustomToggle.KEY_POPUP -> Icons.Outlined.TextIncrease
    CustomToggle.TOOLBAR_LABELS -> Icons.Outlined.Label
    CustomToggle.REDUCE_MOTION -> Icons.Outlined.MotionPhotosOff
    CustomToggle.SECOND_TOOLBAR -> Icons.Outlined.TableRows
    CustomToggle.AUTOCORRECT -> Icons.Outlined.Spellcheck
    CustomToggle.DOUBLE_SPACE_PERIOD -> Icons.Outlined.SpaceBar
    CustomToggle.SPACE_AFTER_PUNCTUATION -> Icons.Outlined.TextFormat
    CustomToggle.ARROW_ROW -> Icons.Outlined.KeyboardDoubleArrowRight
    CustomToggle.SPLIT_KEYBOARD -> Icons.Outlined.VerticalSplit
    CustomToggle.INCOGNITO -> Icons.Outlined.VisibilityOff
    CustomToggle.DARK_THEME -> Icons.Outlined.DarkMode
}

fun customPageIcon(page: CustomPage): ImageVector = when (page) {
    CustomPage.FAVOURITES -> Icons.Outlined.Bookmarks
    CustomPage.HOME -> Icons.Outlined.Home
    CustomPage.SEARCH -> Icons.Outlined.Search
    CustomPage.THEMES -> Icons.Outlined.Palette
    CustomPage.LANGUAGES -> Icons.Outlined.Language
    CustomPage.TYPING -> Icons.Outlined.Keyboard
    CustomPage.DICTIONARY -> IconDefaults.forTool(ToolbarTool.DICTIONARY)
    CustomPage.CLIPBOARD -> Icons.Outlined.ContentPaste
    CustomPage.TOOLS -> Icons.Outlined.Handyman
    CustomPage.TOOLBAR -> Icons.Outlined.ViewDay
    CustomPage.KEYPRESS -> Icons.Outlined.TouchApp
    CustomPage.CUSTOM_BUTTONS -> Icons.Outlined.SmartButton
    CustomPage.APPEARANCE -> Icons.Outlined.Brush
    CustomPage.LAYOUT -> Icons.Outlined.Dashboard
    CustomPage.LAYOUT_SIZE -> Icons.Outlined.Height
    CustomPage.CORRECTIONS -> Icons.Outlined.Spellcheck
    CustomPage.SUGGESTIONS -> Icons.Outlined.AutoAwesome
    CustomPage.GESTURES -> Icons.Outlined.Swipe
    CustomPage.HAPTICS -> Icons.Outlined.Vibration
    CustomPage.EMOJI -> Icons.Outlined.EmojiEmotions
    CustomPage.VOICE -> Icons.Outlined.Mic
    CustomPage.AI -> Icons.Outlined.SmartToy
    CustomPage.PRIVACY -> Icons.Outlined.Shield
    CustomPage.BACKUP -> Icons.Outlined.Backup
    CustomPage.ACCESSIBILITY -> Icons.Outlined.Accessibility
    CustomPage.STATISTICS -> Icons.Outlined.BarChart
}

@StringRes
fun customToggleLabelRes(toggle: CustomToggle): Int = when (toggle) {
    CustomToggle.NUMBER_ROW -> R.string.ime_custom_toggle_number_row
    CustomToggle.KEY_HINTS -> R.string.ime_custom_toggle_key_hints
    CustomToggle.SYMBOL_ROW -> R.string.ime_custom_toggle_symbol_row
    CustomToggle.SUGGESTIONS -> R.string.ime_custom_toggle_suggestions
    CustomToggle.GLIDE_TYPING -> R.string.ime_custom_toggle_glide
    CustomToggle.AUTO_CAPITALIZE -> R.string.ime_custom_toggle_auto_caps
    CustomToggle.KEY_SOUND -> R.string.ime_custom_toggle_sound
    CustomToggle.VIBRATION -> R.string.ime_custom_toggle_vibration
    CustomToggle.KEY_POPUP -> R.string.ime_custom_toggle_key_popup
    CustomToggle.TOOLBAR_LABELS -> R.string.ime_custom_toggle_toolbar_labels
    CustomToggle.REDUCE_MOTION -> R.string.ime_custom_toggle_reduce_motion
    CustomToggle.SECOND_TOOLBAR -> R.string.ime_custom_toggle_second_toolbar
    CustomToggle.AUTOCORRECT -> R.string.ime_custom_toggle_autocorrect
    CustomToggle.DOUBLE_SPACE_PERIOD -> R.string.ime_custom_toggle_double_space
    CustomToggle.SPACE_AFTER_PUNCTUATION -> R.string.ime_custom_toggle_space_after_punctuation
    CustomToggle.ARROW_ROW -> R.string.ime_custom_toggle_arrow_row
    CustomToggle.SPLIT_KEYBOARD -> R.string.ime_custom_toggle_split
    CustomToggle.INCOGNITO -> R.string.ime_custom_toggle_incognito
    CustomToggle.DARK_THEME -> R.string.ime_custom_toggle_dark_theme
}

@StringRes
fun customPageLabelRes(page: CustomPage): Int = when (page) {
    CustomPage.FAVOURITES -> R.string.ime_custom_page_favourites
    CustomPage.HOME -> R.string.ime_custom_page_home
    CustomPage.SEARCH -> R.string.ime_custom_page_search
    CustomPage.THEMES -> R.string.ime_custom_page_themes
    CustomPage.LANGUAGES -> R.string.ime_custom_page_languages
    CustomPage.TYPING -> R.string.ime_custom_page_typing
    CustomPage.DICTIONARY -> R.string.ime_custom_page_dictionary
    CustomPage.CLIPBOARD -> R.string.ime_custom_page_clipboard
    CustomPage.TOOLS -> R.string.ime_custom_page_tools
    CustomPage.TOOLBAR -> R.string.ime_custom_page_toolbar
    CustomPage.KEYPRESS -> R.string.ime_custom_page_keypress
    CustomPage.CUSTOM_BUTTONS -> R.string.ime_custom_page_custom_buttons
    CustomPage.APPEARANCE -> R.string.ime_custom_page_appearance
    CustomPage.LAYOUT -> R.string.ime_custom_page_layout
    CustomPage.LAYOUT_SIZE -> R.string.ime_custom_page_layout_size
    CustomPage.CORRECTIONS -> R.string.ime_custom_page_corrections
    CustomPage.SUGGESTIONS -> R.string.ime_custom_page_suggestions
    CustomPage.GESTURES -> R.string.ime_custom_page_gestures
    CustomPage.HAPTICS -> R.string.ime_custom_page_haptics
    CustomPage.EMOJI -> R.string.ime_custom_page_emoji
    CustomPage.VOICE -> R.string.ime_custom_page_voice
    CustomPage.AI -> R.string.ime_custom_page_ai
    CustomPage.PRIVACY -> R.string.ime_custom_page_privacy
    CustomPage.BACKUP -> R.string.ime_custom_page_backup
    CustomPage.ACCESSIBILITY -> R.string.ime_custom_page_accessibility
    CustomPage.STATISTICS -> R.string.ime_custom_page_statistics
}

/**
 * Names and pictures of the custom buttons, for the places that draw one
 * without the keyboard's state at hand: the floating button's rings and the
 * settings app's pickers. [buttons] is the latest list; the keyboard service
 * and the settings screens that show buttons write it as the settings arrive.
 */
object CustomButtonFaces {
    /**
     * Snapshot state, so the keyboard's tool cells (which find a button's
     * slot here, see [customToolSlot]) redraw when the list changes.
     */
    var buttons: List<CustomButton> by mutableStateOf(emptyList())

    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE)

    fun byId(id: String): CustomButton? = buttons.customButtonById(id)

    /** Where a picked shortcut's own picture is kept, under the app's files. */
    fun iconFile(context: Context, id: String): File = File(File(context.filesDir, ICON_DIR), "$id.png")

    /** The button's name: the one it was given, else its switch's, its page's or its app's. */
    fun name(context: Context, button: CustomButton): String =
        button.label.takeIf { it.isNotBlank() } ?: when (button.kind) {
            CustomButtonKind.SETTING -> button.toggle?.let { context.getString(customToggleLabelRes(it)) }
            CustomButtonKind.PAGE -> button.page?.let { context.getString(customPageLabelRes(it)) }
            CustomButtonKind.PLUGIN -> com.wasimaster.wmkeyboard.core.plugins.PluginStore.get(context).plugin(button.target)?.displayName
            CustomButtonKind.SHORTCUT -> FloatingAppIcons.label(context, button.packageName)
        } ?: context.getString(R.string.ime_custom_button_label)

    /**
     * A shortcut's picture at [sizePx]: the one saved when it was picked, else
     * its app's icon. Null for a switch or a page (they draw a glyph), and for
     * a shortcut whose app is gone. Reads a file, so off the main thread
     * where the caller can.
     */
    fun icon(context: Context, button: CustomButton, sizePx: Int): ImageBitmap? {
        if (button.kind != CustomButtonKind.SHORTCUT) return null
        val file = iconFile(context, button.id)
        val stamp = runCatching { file.lastModified() }.getOrDefault(0L)
        val key = "${button.id}@$stamp@$sizePx"
        icons.get(key)?.let { return it }
        val own = if (stamp > 0L) {
            runCatching {
                BitmapFactory.decodeFile(file.path)?.let { bitmap ->
                    android.graphics.Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true).asImageBitmap()
                }
            }.getOrNull()
        } else {
            null
        }
        val picture = own ?: button.packageName.takeIf { it.isNotEmpty() }
            ?.let { FloatingAppIcons.icon(context, it, sizePx) } ?: return null
        icons.put(key, picture)
        return picture
    }

    private const val ICON_DIR = "custom_buttons"
    private const val ICON_CACHE = 32
}

/**
 * Starts a shortcut button's target. A launcher shortcut goes through
 * [LauncherApps], which only answers while the keyboard may read shortcuts;
 * an intent goes out as an ordinary start, never at the keyboard itself and
 * never carrying a grant of the keyboard's own files. False when it could not
 * be started: the app is gone, or only the home screen may open it.
 */
object CustomShortcuts {
    fun launch(context: Context, button: CustomButton): Boolean = runCatching {
        val target = button.target
        if (target.startsWith(CUSTOM_LAUNCHER_SHORTCUT_PREFIX)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return@runCatching false
            val rest = target.removePrefix(CUSTOM_LAUNCHER_SHORTCUT_PREFIX)
            val serial = rest.substringBefore(':').toLongOrNull()
            val pkg = rest.substringAfter(':').substringBefore('/')
            val id = rest.substringAfter(':').substringAfter('/')
            val apps = context.getSystemService(LauncherApps::class.java) ?: return@runCatching false
            if (!apps.hasShortcutHostPermission()) return@runCatching false
            val user = serial?.let { AppCatalog.profileHandle(context, it) } ?: Process.myUserHandle()
            apps.startShortcut(pkg, id, null, null, user)
            true
        } else {
            val intent = safeIntent(context, target) ?: return@runCatching false
            context.startActivity(intent)
            true
        }
    }.getOrDefault(false)

    /**
     * [uri] as an intent the keyboard can start for the user: in a task of
     * its own, with no selector, clip or URI grant, and refused when it would
     * open one of the keyboard's own screens (a backup could carry any URI).
     */
    fun safeIntent(context: Context, uri: String): Intent? {
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        intent.selector = null
        intent.clipData = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val own = context.packageName
        val named = intent.component?.packageName ?: intent.`package`
        if (named == own) return null
        val resolved = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
        if (resolved?.activityInfo?.packageName == own) return null
        return intent
    }

    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}

/**
 * A shortcut button's own picture where a tool's glyph goes (#1345): on the
 * toolbar, in the toolbox grid, a folder's face, the dragged ghost. Drawn as
 * it is, not tinted, as an app's icon on the toolbar is (#1159); [alpha] keeps
 * a ghost faint. The shortcut glyph while the picture loads or when it is gone.
 */
@Composable
internal fun CustomShortcutPicture(
    id: String,
    contentDescription: String?,
    modifier: Modifier,
    tint: Color,
) {
    val context = LocalContext.current
    val button = CustomButtonFaces.byId(id)
    val px = with(LocalDensity.current) { CUSTOM_PICTURE_DP.dp.roundToPx() }.coerceAtLeast(1)
    val picture by produceState<ImageBitmap?>(null, button, px) {
        value = if (button == null) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CustomButtonFaces.icon(context, button, px) }
        }
    }
    val shown = picture
    if (shown == null) {
        SlotIcon(CUSTOM_SHORTCUT_SLOT, contentDescription = contentDescription, modifier = modifier, tint = tint)
        return
    }
    Image(
        shown,
        contentDescription = contentDescription,
        modifier = modifier,
        alpha = if (tint.isSpecified) tint.alpha else 1f,
    )
}

/** The name custom button [id] goes by as a tool, or the generic one for a button since deleted. */
fun customToolName(context: Context, buttons: List<CustomButton>, id: String): String =
    buttons.customButtonById(id)?.let { CustomButtonFaces.name(context, it) }
        ?: context.getString(R.string.ime_custom_button_label)

/** Sized once for every place a tool's icon is drawn; [Image] scales it to the cell. */
private const val CUSTOM_PICTURE_DP = 32
