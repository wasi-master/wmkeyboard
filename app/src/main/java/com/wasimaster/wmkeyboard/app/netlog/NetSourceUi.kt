package com.wasimaster.wmkeyboard.app.netlog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FontDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ImageSearch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Photo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoSizeSelectLarge
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SystemUpdate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VolunteerActivism
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.SettingsRouteColors
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.WmIconTileGlyph
import com.wasimaster.wmkeyboard.app.WmIconTileSize
import com.wasimaster.wmkeyboard.app.toolRoute
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.ui.toolAccentColor
import com.wasimaster.wmkeyboard.core.ui.toolAccentPaint
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon

/**
 * How one [NetSource] is drawn: its name, what it sends, where its settings
 * live, and its tile.
 *
 * A source that belongs to a toolbar tool wears that tool's icon (icon packs
 * included) and that tool's colour, the user's own colour and gradient
 * overrides included, so a row in the log looks like the key that caused it.
 * The rest borrow the icon and colour of the settings screen that owns them.
 */
@Immutable
internal data class NetSourceLook(
    @StringRes val label: Int,
    @StringRes val sent: Int,
    /** The settings screen the sheet's "… settings" button opens. */
    val route: String?,
    /** The accent the charts and bars use. Always a colour, never "neutral". */
    val accent: Color,
    /** The gradient when "Gradient tool colours" is on, else null. */
    val brush: Brush?,
    val slot: String?,
    val icon: ImageVector,
    /** Whether Data saver has a row for this feature, so the sheet can link there. */
    val dataSaver: Boolean,
)

internal fun netSourceLook(source: NetSource, settings: KeyboardSettings): NetSourceLook {
    val tool = source.tool
    val paint = tool?.let { toolAccentPaint(it, settings) }
    val (label, sent) = texts(source)
    val route = tool?.let(::toolRoute) ?: ownerRoute(source)
    val accent = when {
        paint != null -> paint.color
        tool != null -> toolAccentColor(tool, settings.toolColorOverrides)
        else -> route?.let { SettingsRouteColors[it] } ?: NeutralAccent
    }
    return NetSourceLook(
        label = label,
        sent = sent,
        route = route,
        accent = accent,
        brush = paint?.brush,
        slot = tool?.let(IconSlots::forTool),
        icon = fallbackIcon(source),
        dataSaver = source in DataSaverSources,
    )
}

/** The tile for a source: the tool's own glyph when it has one. */
@Composable
internal fun NetSourceTile(look: NetSourceLook, modifier: Modifier = Modifier, size: Dp = WmIconTileSize) {
    WmIconTile(look.accent, modifier, brush = look.brush, size = size) {
        val glyph = Modifier.size(if (size == WmIconTileSize) WmIconTileGlyph else size * GLYPH_RATIO)
        val slot = look.slot
        if (slot != null) {
            SlotIcon(slot, contentDescription = null, modifier = glyph)
        } else {
            Icon(look.icon, contentDescription = null, modifier = glyph)
        }
    }
}

private fun texts(source: NetSource): Pair<Int, Int> = when (source) {
    NetSource.TRANSLATE -> R.string.netlog_source_translate to R.string.netlog_sent_translate
    NetSource.DEEPL_WRITE -> R.string.netlog_source_deepl_write to R.string.netlog_sent_deepl_write
    NetSource.LANGUAGETOOL -> R.string.netlog_source_languagetool to R.string.netlog_sent_languagetool
    NetSource.GIF -> R.string.netlog_source_gif to R.string.netlog_sent_gif
    NetSource.STICKER -> R.string.netlog_source_sticker to R.string.netlog_sent_sticker
    NetSource.IMAGE_GEN -> R.string.netlog_source_image_gen to R.string.netlog_sent_image_gen
    NetSource.EMOJI_MIX -> R.string.netlog_source_emoji_mix to R.string.netlog_sent_emoji_mix
    NetSource.WEB_SEARCH -> R.string.netlog_source_web_search to R.string.netlog_sent_web_search
    NetSource.IMAGE_SEARCH -> R.string.netlog_source_image_search to R.string.netlog_sent_image_search
    NetSource.PHOTO_SEARCH -> R.string.netlog_source_photo_search to R.string.netlog_sent_photo_search
    NetSource.OCR_ONLINE -> R.string.netlog_source_ocr_online to R.string.netlog_sent_ocr_online
    NetSource.AI -> R.string.netlog_source_ai to R.string.netlog_sent_ai
    NetSource.AI_CHAT -> R.string.netlog_source_ai_chat to R.string.netlog_sent_ai_chat
    NetSource.AI_TOOLS -> R.string.netlog_source_ai_tools to R.string.netlog_sent_ai_tools
    NetSource.AI_MCP -> R.string.netlog_source_ai_mcp to R.string.netlog_sent_ai_mcp
    NetSource.TRANSCRIPTION -> R.string.netlog_source_transcription to R.string.netlog_sent_transcription
    NetSource.MUSIC_AUDD -> R.string.netlog_source_music_audd to R.string.netlog_sent_music_clip
    NetSource.MUSIC_ACRCLOUD -> R.string.netlog_source_music_acrcloud to R.string.netlog_sent_music_clip
    NetSource.MUSIC_AUDIOTAG -> R.string.netlog_source_music_audiotag to R.string.netlog_sent_music_clip
    NetSource.MUSIC_RAPIDAPI -> R.string.netlog_source_music_rapidapi to R.string.netlog_sent_music_clip
    NetSource.MUSIC_SERVER -> R.string.netlog_source_music_server to R.string.netlog_sent_music_clip
    NetSource.MUSIC_METADATA -> R.string.netlog_source_music_metadata to R.string.netlog_sent_music_metadata
    NetSource.SPEED_TEST -> R.string.netlog_source_speed_test to R.string.netlog_sent_speed_test
    NetSource.WIKIPEDIA -> R.string.netlog_source_wikipedia to R.string.netlog_sent_wikipedia
    NetSource.NOTES -> R.string.netlog_source_notes to R.string.netlog_sent_notes
    NetSource.DICTIONARY -> R.string.netlog_source_dictionary to R.string.netlog_sent_dictionary
    NetSource.VOCABULARY -> R.string.netlog_source_vocabulary to R.string.netlog_sent_vocabulary
    NetSource.SYNONYMS -> R.string.netlog_source_synonyms to R.string.netlog_sent_synonyms
    NetSource.WEATHER -> R.string.netlog_source_weather to R.string.netlog_sent_weather
    NetSource.SEND -> R.string.netlog_source_send to R.string.netlog_sent_send
    NetSource.LOCATION -> R.string.netlog_source_location to R.string.netlog_sent_location
    NetSource.CURRENCY -> R.string.netlog_source_currency to R.string.netlog_sent_currency
    NetSource.PHOTOS -> R.string.netlog_source_photos to R.string.netlog_sent_photos
    NetSource.LINK_PREVIEW -> R.string.netlog_source_link_preview to R.string.netlog_sent_link_preview
    NetSource.MEDIA_IMAGES -> R.string.netlog_source_media_images to R.string.netlog_sent_media_images
    NetSource.DOWNLOAD_WORDLIST -> R.string.netlog_source_download_wordlist to R.string.netlog_sent_download
    NetSource.DOWNLOAD_NGRAM -> R.string.netlog_source_download_ngram to R.string.netlog_sent_download
    NetSource.DOWNLOAD_CJK -> R.string.netlog_source_download_cjk to R.string.netlog_sent_download
    NetSource.DOWNLOAD_EMOJI -> R.string.netlog_source_download_emoji to R.string.netlog_sent_download
    NetSource.DOWNLOAD_WHISPER -> R.string.netlog_source_download_whisper to R.string.netlog_sent_download
    NetSource.DOWNLOAD_GLIDE -> R.string.netlog_source_download_glide to R.string.netlog_sent_download
    NetSource.DOWNLOAD_LLM -> R.string.netlog_source_download_llm to R.string.netlog_sent_download
    NetSource.DOWNLOAD_TRANSLATE -> R.string.netlog_source_download_translate to R.string.netlog_sent_download
    NetSource.DOWNLOAD_VOCAB -> R.string.netlog_source_download_vocab to R.string.netlog_sent_download
    NetSource.DOWNLOAD_DICTIONARY -> R.string.netlog_source_download_dictionary to R.string.netlog_sent_download
    NetSource.DOWNLOAD_FONT -> R.string.netlog_source_download_font to R.string.netlog_sent_download
    NetSource.DOWNLOAD_CUTOUT -> R.string.netlog_source_download_cutout to R.string.netlog_sent_download
    NetSource.DOWNLOAD_OCR -> R.string.netlog_source_download_ocr to R.string.netlog_sent_download
    NetSource.DOWNLOAD_HANDWRITING -> R.string.netlog_source_download_handwriting to R.string.netlog_sent_download
    NetSource.DOWNLOAD_SEMANTIC -> R.string.netlog_source_download_semantic to R.string.netlog_sent_download
    NetSource.DOWNLOAD_IMAGEGEN -> R.string.netlog_source_download_imagegen to R.string.netlog_sent_download
    NetSource.DOWNLOAD_UPSCALE -> R.string.netlog_source_download_upscale to R.string.netlog_sent_download
    NetSource.DOWNLOAD_XLIT -> R.string.netlog_source_download_xlit to R.string.netlog_sent_download
    NetSource.DOWNLOAD_VOICE_OUT -> R.string.netlog_source_download_voice_out to R.string.netlog_sent_download
    NetSource.DOWNLOAD_NATIVE -> R.string.netlog_source_download_native to R.string.netlog_sent_download
    NetSource.DOWNLOAD_LANGUAGE -> R.string.netlog_source_download_language to R.string.netlog_sent_download
    NetSource.ADDONS -> R.string.netlog_source_addons to R.string.netlog_sent_addons
    NetSource.KEYMAN -> R.string.netlog_source_keyman to R.string.netlog_sent_keyman
    NetSource.SIGNAL_STICKERS -> R.string.netlog_source_signal_stickers to R.string.netlog_sent_signal_stickers
    NetSource.TELEGRAM_STICKERS -> R.string.netlog_source_telegram_stickers to R.string.netlog_sent_telegram_stickers
    NetSource.RBOARD_THEMES -> R.string.netlog_source_rboard_themes to R.string.netlog_sent_rboard_themes
    NetSource.LINK_IMPORT -> R.string.netlog_source_link_import to R.string.netlog_sent_link_import
    NetSource.BACKUP -> R.string.netlog_source_backup to R.string.netlog_sent_backup
    NetSource.UPDATES -> R.string.netlog_source_updates to R.string.netlog_sent_updates
    NetSource.SUPPORTERS -> R.string.netlog_source_supporters to R.string.netlog_sent_supporters
    NetSource.KDE_CONNECT -> R.string.netlog_source_kde_connect to R.string.netlog_sent_kde_connect
    NetSource.HOME_ASSISTANT -> R.string.netlog_source_home_assistant to R.string.netlog_sent_home_assistant
    NetSource.SMARTTHINGS -> R.string.netlog_source_smartthings to R.string.netlog_sent_smartthings
    NetSource.HUE -> R.string.netlog_source_hue to R.string.netlog_sent_hue
    NetSource.OPENHAB -> R.string.netlog_source_openhab to R.string.netlog_sent_openhab
    NetSource.TUYA -> R.string.netlog_source_tuya to R.string.netlog_sent_tuya
    NetSource.SWITCHBOT -> R.string.netlog_source_switchbot to R.string.netlog_sent_switchbot
    NetSource.IMMICH -> R.string.netlog_source_immich to R.string.netlog_sent_immich
    NetSource.PAPERLESS -> R.string.netlog_source_paperless to R.string.netlog_sent_paperless
    NetSource.INVITES -> R.string.netlog_source_invites to R.string.netlog_sent_invites
    NetSource.OTHER -> R.string.netlog_source_other to R.string.netlog_sent_other
}

/** Where a source that is not a tool keeps its settings. */
private fun ownerRoute(source: NetSource): String? = when (source) {
    NetSource.PHOTOS -> "photos"
    NetSource.SYNONYMS -> "typing/suggestions"
    NetSource.MEDIA_IMAGES -> "emoji"
    NetSource.DOWNLOAD_WORDLIST, NetSource.DOWNLOAD_NGRAM, NetSource.DOWNLOAD_CJK, NetSource.DOWNLOAD_XLIT -> "languages"
    NetSource.DOWNLOAD_EMOJI -> "emojikeywords"
    NetSource.DOWNLOAD_FONT -> "fonts"
    NetSource.ADDONS, NetSource.LINK_IMPORT -> "addons"
    NetSource.KEYMAN -> "keymaps"
    NetSource.RBOARD_THEMES -> "themes"
    NetSource.BACKUP -> "backup"
    NetSource.UPDATES -> "about"
    NetSource.DOWNLOAD_SEMANTIC -> "ai"
    NetSource.DOWNLOAD_IMAGEGEN -> "sticker_generator"
    NetSource.SUPPORTERS -> "supporters"
    else -> null
}

private fun fallbackIcon(source: NetSource): ImageVector = when (source) {
    NetSource.PHOTOS -> Icons.Outlined.Photo
    NetSource.MEDIA_IMAGES -> Icons.Outlined.Image
    NetSource.DOWNLOAD_WORDLIST, NetSource.DOWNLOAD_NGRAM -> Icons.Outlined.Language
    NetSource.DOWNLOAD_CJK, NetSource.DOWNLOAD_XLIT -> Icons.Outlined.Translate
    NetSource.DOWNLOAD_FONT -> Icons.Outlined.FontDownload
    NetSource.DOWNLOAD_VOCAB -> Icons.Outlined.AutoStories
    NetSource.DOWNLOAD_CUTOUT -> Icons.Outlined.ContentCut
    NetSource.DOWNLOAD_SEMANTIC -> Icons.Outlined.ImageSearch
    NetSource.DOWNLOAD_IMAGEGEN -> Icons.Outlined.AutoAwesome
    NetSource.DOWNLOAD_UPSCALE -> Icons.Outlined.PhotoSizeSelectLarge
    NetSource.ADDONS -> Icons.Outlined.Extension
    NetSource.LINK_IMPORT, NetSource.LINK_PREVIEW -> Icons.Outlined.Link
    NetSource.KEYMAN -> Icons.Outlined.Keyboard
    NetSource.RBOARD_THEMES -> Icons.Outlined.Palette
    NetSource.BACKUP -> Icons.Outlined.Save
    NetSource.SYNONYMS -> Icons.Outlined.SwapHoriz
    NetSource.UPDATES -> Icons.Outlined.SystemUpdate
    NetSource.SUPPORTERS -> Icons.Outlined.VolunteerActivism
    NetSource.OTHER -> Icons.Outlined.Public
    else -> Icons.Outlined.CloudDownload
}

/**
 * The features [com.wasimaster.wmkeyboard.core.settings.DataSaverSettings] has a
 * row for: every one except these, which it deliberately leaves alone.
 */
private val DataSaverSources = NetSource.entries.toSet() - setOf(
    NetSource.TRANSLATE, NetSource.DEEPL_WRITE, NetSource.BACKUP, NetSource.UPDATES, NetSource.KDE_CONNECT,
    // The user's own server on their own network; a data saver has nothing to save there.
    NetSource.HOME_ASSISTANT, NetSource.HUE, NetSource.OPENHAB,
    // The user's own photo server, asked only while its album is open (#1205).
    NetSource.IMMICH,
    // The user's own document server; its own Wi-Fi only switch is on the scanner's page (#1198).
    NetSource.PAPERLESS,
    // The user's own event server, on a press; while data saving is on the card asks before every publish (#1274).
    NetSource.INVITES,
    // A few small polls while the panel is open, on the user's say-so.
    NetSource.SMARTTHINGS, NetSource.TUYA, NetSource.SWITCHBOT,
    // One small list, fetched only when its button is pressed.
    NetSource.SUPPORTERS,
    // One small request per share the user pressed; its own switch is on the Location page.
    NetSource.LOCATION,
    NetSource.LINK_IMPORT, NetSource.SYNONYMS, NetSource.OCR_ONLINE, NetSource.OTHER,
    // One page per link the user shared to the notes (#997).
    NetSource.NOTES,
)

private val NeutralAccent = Color(0xFF90A4AE)
private const val GLYPH_RATIO = 0.55f
