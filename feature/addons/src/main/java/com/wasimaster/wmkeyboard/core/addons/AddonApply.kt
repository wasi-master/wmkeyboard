package com.wasimaster.wmkeyboard.core.addons

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.addons.feature.R
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Turning an installed addon on — separately from installing it.
 *
 * Installing used to apply as it went: a theme became the keyboard's theme, an
 * icon pack redrew every glyph, a sound started firing on every keystroke, all
 * from one tap on a catalogue card. The reasoning was that an install with no
 * visible result reads as an install that didn't work, and it isn't wrong — but
 * it makes browsing a repository destructive. Tapping Install to *have* a theme
 * is not the same as tapping it to *wear* one, and the second reading is the
 * only one the old flow supported.
 *
 * So the two are split. [AddonInstaller] now only puts the thing on the device,
 * and this asks. Every type whose install has an obvious single slot to fill
 * gets a [questionRes]; the ones that don't — a text font, a dictionary, a
 * snippet pack, stickers — are already doing everything they can do the moment
 * they land and have nothing to switch to.
 *
 * [isActive] exists for the update path rather than the UI: updating replaces
 * the local object with a freshly-minted one, so an addon that *was* the active
 * theme has to be re-selected under its new id or the update silently drops the
 * user back to a built-in.
 */
object AddonApply {

    /**
     * What to ask after installing this type, or null when there is nothing to
     * ask — installing is the whole of it.
     *
     * A resource id rather than the words: the question is asked by whichever
     * addon screen is on top when the install lands, and that screen resolves
     * it in the language the user is reading at that moment.
     */
    @StringRes
    fun questionRes(type: AddonType): Int? = when (type) {
        AddonType.Theme -> R.string.faddons_apply_theme_question
        AddonType.IconPack -> R.string.faddons_apply_icon_pack_question
        AddonType.EmojiFont -> R.string.faddons_apply_emoji_font_question
        AddonType.Sound -> R.string.faddons_apply_sound_question
        AddonType.SoundPack -> R.string.faddons_apply_sound_pack_question
        AddonType.Layout -> R.string.faddons_apply_layout_question
        AddonType.Plugin -> R.string.faddons_apply_plugin_question
        // A text font goes in one of several pickers and guessing wrong is
        // worse than letting the user choose; a dictionary, a keyword pack, a
        // snippet pack and a sticker pack are live the moment they are
        // installed.
        AddonType.Font, AddonType.Dictionary, AddonType.EmojiKeywords, AddonType.Vocabulary,
        AddonType.Snippets, AddonType.Espanso, AddonType.Stickers, AddonType.Unknown,
        -> null
    }

    /** The label on the button that says yes to [questionRes]. */
    @StringRes
    fun confirmLabelRes(type: AddonType): Int = when (type) {
        AddonType.Layout, AddonType.Plugin -> CommonR.string.common_enable
        AddonType.Theme, AddonType.IconPack, AddonType.EmojiFont, AddonType.Sound,
        AddonType.SoundPack,
        AddonType.Font, AddonType.Dictionary, AddonType.EmojiKeywords, AddonType.Vocabulary,
        AddonType.Snippets, AddonType.Espanso, AddonType.Stickers, AddonType.Unknown,
        -> R.string.faddons_apply_switch_action
    }

    /**
     * Whether this addon is currently the thing its slot points at. Reads
     * DataStore; call off the main thread.
     */
    suspend fun isActive(context: Context, record: InstalledAddon): Boolean {
        val app = context.applicationContext
        val ref = record.localRef
        if (ref.isBlank()) return false
        if (record.type == AddonType.Plugin) {
            return PluginStore.get(app).plugin(ref)?.enabled == true
        }
        if (questionRes(record.type) == null) return false
        val settings = SettingsRepository(app).settings.first()
        return when (record.type) {
            AddonType.Theme -> settings.keyboardThemeId == ref
            AddonType.IconPack -> settings.icons.activePackId == ref
            AddonType.EmojiFont ->
                settings.emojiFont == EmojiFontChoice.INSTALLED &&
                    settings.emojiFontInstalled.installedId == ref
            AddonType.Sound ->
                settings.keySoundStyle == KeySoundStyle.CUSTOM &&
                    settings.keySoundCustom.customId == ref
            AddonType.SoundPack ->
                settings.keySoundStyle == KeySoundStyle.PACK &&
                    settings.keySoundCustom.packId == ref
            AddonType.Layout -> ref in settings.enabledLayoutIds
            // Plugin returned above without reading settings, and the rest were
            // filtered out by the questionRes() guard: they have no slot to be
            // in.
            AddonType.Plugin, AddonType.Font, AddonType.Dictionary,
            AddonType.EmojiKeywords, AddonType.Vocabulary, AddonType.Snippets, AddonType.Espanso,
            AddonType.Stickers, AddonType.Unknown,
            -> false
        }
    }

    /**
     * Points this addon's slot at it. A no-op for types with no slot, so the
     * caller doesn't have to check [question] first.
     */
    suspend fun apply(context: Context, record: InstalledAddon) {
        val app = context.applicationContext
        val ref = record.localRef
        if (ref.isBlank()) return
        val repository = SettingsRepository(app)
        when (record.type) {
            AddonType.Theme -> repository.setKeyboardThemeId(ref)
            AddonType.IconPack -> repository.setIconPack(ref)
            // Both of these also flip the choice that makes the selection take
            // effect — see setInstalledEmojiFont and setKeySoundCustomId.
            AddonType.EmojiFont -> repository.setInstalledEmojiFont(ref)
            AddonType.Sound -> repository.setKeySoundCustomId(ref)
            AddonType.SoundPack -> repository.setKeySoundPackId(ref)
            AddonType.Layout -> {
                val current = repository.settings.first().enabledLayoutIds
                if (ref !in current) repository.setEnabledLayoutIds((current + ref).distinct())
            }
            AddonType.Plugin -> PluginStore.get(app).setEnabled(ref, true)
            // Nothing to point anywhere: a text font waits in the picker, and a
            // dictionary, snippet pack or sticker pack is already in use.
            AddonType.Font, AddonType.Dictionary, AddonType.EmojiKeywords, AddonType.Vocabulary,
            AddonType.Snippets, AddonType.Espanso, AddonType.Stickers, AddonType.Unknown,
            -> Unit
        }
    }
}
