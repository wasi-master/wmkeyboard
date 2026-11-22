package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.json.JsonValueDomain
import com.wasimaster.wmkeyboard.core.layout.json.JsonValueHint
import com.wasimaster.wmkeyboard.core.layout.json.JsonValueSource
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.builtInThemeNameRes
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.ime.ui.KeyIcons
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts

/**
 * The values of a layout document that live on this device, for the JSON
 * editor's suggestions and checks: the themes, the fonts, the key icon names,
 * the user's own secondary layouts, and what each tool, editing operation and
 * panel component is called in the user's language.
 *
 * Read once per settings change by the screen, and lazily within that, so a
 * document that never mentions a theme never lists the themes.
 */
internal class AppJsonValues(private val context: Context, private val settings: KeyboardSettings) : JsonValueSource {

    private val themes: List<JsonValueHint> by lazy {
        val builtIn = BuiltInThemes.flattenedThemes().map { theme ->
            JsonValueHint(theme.id, builtInThemeNameRes(theme.id)?.let(context::getString) ?: theme.name)
        }
        val custom = settings.customThemes.flattenedThemes().map { JsonValueHint(it.id, it.name) }
        (listOf(JsonValueHint(DEFAULT_THEME_ID, context.getString(R.string.theme_default_name))) + builtIn + custom).distinctBy { it.value }
    }

    private val fonts: List<JsonValueHint> by lazy {
        (listOf(KeyboardFonts.DEFAULT_ID, KeyboardFonts.CUSTOM_ID) + KeyboardFonts.googleFonts.map(KeyboardFonts::googleId))
            .map { JsonValueHint(it, KeyboardFonts.displayName(context, it, settings.customFontName)) }
    }

    private val icons: List<JsonValueHint> by lazy { KeyIcons.names.map { JsonValueHint(it) } }

    private val layouts: List<JsonValueHint> by lazy {
        secondaryLayouts(settings.customLayouts).map { JsonValueHint(it.id, it.name) }
    }

    override fun values(domain: JsonValueDomain): List<JsonValueHint> = when (domain) {
        JsonValueDomain.LANGUAGE, JsonValueDomain.RULE_SET -> super.values(domain)
        JsonValueDomain.THEME -> themes
        JsonValueDomain.FONT -> fonts
        JsonValueDomain.ICON -> icons
        JsonValueDomain.SECONDARY_LAYOUT -> layouts
    }

    override fun knows(domain: JsonValueDomain, value: String): Boolean? = when (domain) {
        // A user with no secondary layouts yet still has a key that names nothing.
        JsonValueDomain.SECONDARY_LAYOUT -> layouts.any { it.value == value }
        else -> super.knows(domain, value)
    }

    override fun enumLabel(typeName: String, value: String): String? = runCatching {
        when (typeName) {
            "ToolbarTool" -> context.getString(toolTitle(ToolbarTool.valueOf(value)))
            "TextEditAction" -> context.getString(textEditActionTitle(TextEditAction.valueOf(value)))
            "PanelFieldKind" -> PanelFieldKind.entries.getOrNull(PanelFieldKind.serializer().descriptor.getElementIndex(value))
                ?.let { context.getString(fieldTitleRes(it)) }
            "PanelKind" -> PanelKind.entries.getOrNull(PanelKind.serializer().descriptor.getElementIndex(value))
                ?.let { context.getString(panelTitleRes(it)) }
            else -> null
        }
    }.getOrNull()
}
