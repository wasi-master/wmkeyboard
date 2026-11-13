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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewDay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.settings.CUSTOM_LAUNCHER_SHORTCUT_PREFIX
import com.wasimaster.wmkeyboard.core.settings.CustomButton
import com.wasimaster.wmkeyboard.core.settings.CustomButtonKind
import com.wasimaster.wmkeyboard.core.settings.CustomPage
import com.wasimaster.wmkeyboard.core.settings.CustomToggle
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.customButtonById
import com.wasimaster.wmkeyboard.core.settings.inToolbox
import com.wasimaster.wmkeyboard.core.settings.onToolbar
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.floating.FloatingAppIcons
import java.io.File

/**
 * The user's own buttons (#1213, #1236), handed down from the service with
 * the other toolbar callbacks. [onTap] runs one by its id: opens the
 * shortcut, flips the setting or opens the page.
 */
@Immutable
class CustomButtonCallbacks(
    val onTap: (String) -> Unit = {},
)

/** The custom buttons the toolbar shows after its tools, folders and apps. */
internal fun toolbarCustomButtons(state: KeyboardUiState): List<CustomButton> =
    state.settings.toolbox.customButtons.onToolbar()

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
}

/**
 * Names and pictures of the custom buttons, for the places that draw one
 * without the keyboard's state at hand: the floating button's rings and the
 * settings app's pickers. [buttons] is the latest list; the keyboard service
 * and the settings screens that show buttons write it as the settings arrive.
 */
object CustomButtonFaces {
    @Volatile
    var buttons: List<CustomButton> = emptyList()

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
 * One custom button on the toolbar or in the toolbox. A switch or a page is
 * a tool's round button, lit while its setting is on; a shortcut wears its
 * own picture, as an app on the toolbar does (#1159). [labelled] draws the
 * name under it, as the toolbox always does and the toolbar does when its
 * labels are on.
 */
@Composable
internal fun CustomButtonCell(
    button: CustomButton,
    state: KeyboardUiState,
    callbacks: CustomButtonCallbacks,
    modifier: Modifier = Modifier,
    labelled: Boolean = state.settings.toolbarLabels,
    wide: Boolean = true,
) {
    val context = LocalContext.current
    val name = remember(button, context) { CustomButtonFaces.name(context, button) }
    val kb = LocalKbTheme.current
    if (button.kind != CustomButtonKind.SHORTCUT) {
        ToolCircle(
            slot = customButtonSlot(button),
            description = name,
            active = button.toggle?.isOn(state.settings) == true,
            modifier = modifier,
            longPressLabel = name.takeUnless { labelled },
            label = name.takeIf { labelled },
            labelSizeSp = state.settings.toolbarLabelSize,
            wide = wide,
        ) { callbacks.onTap(button.id) }
        return
    }
    val iconDp = kb.toolIconSizeDp + CUSTOM_ICON_EXTRA_DP
    val px = with(LocalDensity.current) { iconDp.dp.roundToPx() }.coerceAtLeast(1)
    val icon by produceState<ImageBitmap?>(null, button, px) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CustomButtonFaces.icon(context, button, px)
        }
    }
    val description = context.getString(R.string.ime_custom_button_desc, name)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = description) { callbacks.onTap(button.id) }
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val picture = icon
        if (picture != null) {
            Image(picture, contentDescription = null, modifier = Modifier.size(iconDp.dp))
        } else {
            Box(Modifier.size(iconDp.dp), contentAlignment = Alignment.Center) {
                SlotIcon(
                    CUSTOM_SHORTCUT_SLOT,
                    contentDescription = null,
                    modifier = Modifier.size(kb.toolIconSizeDp.dp),
                    tint = kb.toolbarIcon,
                )
            }
        }
        if (labelled) {
            Text(
                name,
                fontSize = state.settings.toolbarLabelSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = kb.toolbarIcon,
                modifier = Modifier.widthIn(max = 72.dp),
            )
        }
    }
}

/**
 * The toolbox's row of custom buttons (#1213, #1236): the ones set to live
 * in the toolbox rather than on the bar, above the grid, named. Draws nothing
 * when there are none.
 */
@Composable
internal fun ToolboxCustomButtons(state: KeyboardUiState, callbacks: CustomButtonCallbacks) {
    val buttons = state.settings.toolbox.customButtons.inToolbox()
    if (buttons.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (button in buttons) {
            key("custom:${button.id}") {
                CustomButtonCell(button, state, callbacks, labelled = true, wide = false)
            }
        }
    }
}

/** A shortcut's picture is a filled one, a glyph has air round it, as for the toolbar's apps. */
private const val CUSTOM_ICON_EXTRA_DP = 4
