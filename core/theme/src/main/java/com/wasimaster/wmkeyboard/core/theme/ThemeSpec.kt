package com.wasimaster.wmkeyboard.core.theme

import android.content.Context
import android.util.Base64
import com.wasimaster.wmkeyboard.core.share.RemixCredit
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.theme.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

/**
 * A complete keyboard theme. Colors are ARGB longs (0xAARRGGBB) so alpha is
 * first-class — a translucent board over a background image, ghosted keys,
 * and so on. Nullable fields fall back to a value derived from the required
 * ones (see KbTheme resolution), so a minimal theme only needs the core
 * colors; nullable radii fall back to the global appearance sliders.
 */
/** How a [GradientSpec] paints its color stops. */
enum class GradientType { LINEAR, RADIAL, SWEEP }

/**
 * A multi-stop gradient. Colors are ARGB longs like everything else in the
 * theme, so translucent stops work (a see-through gradient over a background
 * image acts as a tinted scrim). [angleDeg] rotates LINEAR and SWEEP
 * gradients; RADIAL ignores it.
 */
@Serializable
data class GradientSpec(
    val colors: List<Long>,
    val type: GradientType = GradientType.LINEAR,
    val angleDeg: Float = 45f,
)

/**
 * Outline used for every key. ROUNDED, CUT and TICKET follow the corner-radius
 * sliders; the rest set their own corners from the key's size, because their
 * whole look is the proportion (a half-height arch, a hexagon's points).
 *
 * NONE is no outline at all (issue #149): a key at rest draws no face, border
 * or texture, only its label. A transparent key colour only looks like that
 * until the theme has a border or a texture, and it fades the pressed colour
 * derived from it too. A key with no shape still lights up while it is pressed
 * or latched, in the rounded outline [keyShapeFor] hands back for it. Only keys
 * may have it: every other surface names its shape as a string, and
 * [keyShapeKindOrNull] turns NONE away there.
 *
 * Order is the order the shape picker lists them in: no shape first, then the
 * four plain outlines, then the decorative ones.
 *
 * A value added here is not readable by an older build — `ThemeCodec.decode`
 * drops a theme whose enum name it does not know — so a theme exported with
 * HEXAGON does not import into a build from before it existed. That is the
 * price of the field being an enum, which it already was.
 */
enum class KeyShapeKind {
    NONE,
    ROUNDED,
    SHARP,
    PILL,
    CUT,
    SQUIRCLE,
    ARCH,
    LEAF,
    SLANT,
    HEXAGON,
    SCALLOP,
    TICKET,
    CIRCLE,
}

/**
 * A [KeyShapeKind] by name, or null when this build has no such shape — for
 * the shape fields that travel as strings so an unknown name costs the field
 * rather than the whole theme.
 *
 * Those fields are all surfaces that have to be seen (popups, menus, tools,
 * chips, cards), so [KeyShapeKind.NONE] is turned away like an unknown name and
 * the field falls back to its default. Only the key shape may be none.
 */
fun keyShapeKindOrNull(name: String?): KeyShapeKind? =
    name?.let { wanted ->
        KeyShapeKind.entries.firstOrNull { it.name == wanted && it != KeyShapeKind.NONE }
    }

/**
 * The shape a container (list-menu popup, panel card) may safely inherit from
 * a decorative key, chip or popup shape: the four plain outlines pass
 * through, everything else falls back to rounded. A hexagon or a scallop on
 * a surface full of text clips its own content; a theme that truly wants one
 * says so through [ThemeSpec.menuShape] or [ThemeSpec.cardShape].
 */
fun safeContainerKind(kind: KeyShapeKind): KeyShapeKind = when (kind) {
    KeyShapeKind.ROUNDED, KeyShapeKind.SHARP, KeyShapeKind.CUT, KeyShapeKind.SQUIRCLE -> kind
    else -> KeyShapeKind.ROUNDED
}

/**
 * Whether a surface wearing [kind] may also cast a hardware elevation shadow.
 *
 * A shadow is tessellated from the outline, and Skia has two routines for it:
 * a fast one for a convex path and `computeConcaveShadow`, which stitches an
 * inner and an outer ring together. The concave one does not finish on a rim
 * of scallop bumps — the RenderThread sits in `stitchConcaveRings` burning
 * CPU, the main thread blocks in `DrawFrameTask::postAndWait` waiting for the
 * frame, and the system kills the IME for not answering the touch that opened
 * the popup within five seconds. Reported as issue #9: every popup crashed the
 * keyboard until the shape was changed back.
 *
 * So the two concave outlines lose the shadow and keep the shape, which is the
 * cheaper half to give up — the fill and the border still separate a popup
 * from the keys behind it. Keys never hit this because a key has no elevation;
 * it is only the raised surfaces (popup bubbles, menus, cards) that ask for a
 * shadow at all.
 *
 * Exhaustive on purpose: a new shape has to be classified here rather than
 * defaulting into the fast path and being found on somebody's phone.
 */
fun castsElevationShadow(kind: KeyShapeKind): Boolean = when (kind) {
    KeyShapeKind.SCALLOP, KeyShapeKind.TICKET -> false
    // Draws the rounded outline wherever it draws at all.
    KeyShapeKind.NONE,
    KeyShapeKind.ROUNDED,
    KeyShapeKind.SHARP,
    KeyShapeKind.PILL,
    KeyShapeKind.CUT,
    KeyShapeKind.SQUIRCLE,
    KeyShapeKind.ARCH,
    KeyShapeKind.LEAF,
    KeyShapeKind.SLANT,
    KeyShapeKind.HEXAGON,
    KeyShapeKind.CIRCLE,
    -> true
}

/**
 * Who took a background photo and where it came from, kept beside the image so
 * the credit survives an export, an import and going offline.
 *
 * Both photo services require the photographer to be named wherever their photo
 * is shown, with a link back, so this is not decoration: an image with no
 * attribution beside it is a licence problem rather than a cosmetic one.
 *
 * [provider] is a plain string, not an enum. kotlinx.serialization throws on an
 * enum constant it does not know, and [ThemeCodec.decode] swallows that in a
 * `runCatching` — so a theme made by a later build that added a third service
 * would silently fail to decode **in full**, losing the user's colours and not
 * merely the credit. A name this build does not recognise is shown as it stands.
 */
@Serializable
data class PhotoAttribution(
    val provider: String,
    val photoId: String,
    val photographer: String,
    /** Stored untagged; referral parameters are added where the link is used. */
    val photographerUrl: String = "",
    val photoUrl: String = "",
    val altText: String = "",
    val avgColor: String = "",
)

/**
 * Live theme animation. FLOW drifts the board gradient along its axis;
 * HUE_CYCLE slowly rotates the hue of the board gradient (or the solid board
 * color). Only runs while the keyboard is on screen.
 */
enum class ThemeAnimation { NONE, FLOW, HUE_CYCLE }

@Serializable
data class ThemeSpec(
    val id: String,
    /**
     * The stored name. A theme the user makes keeps the name the user typed. A
     * built-in theme holds its English name here, which is what an export and a
     * copy start from; the name on screen comes from [themeName] instead, so it
     * follows the language of the device.
     */
    val name: String,
    val dark: Boolean = true,
    // Board
    val boardBackground: Long = 0xFF17181C,
    /** Painted instead of [boardBackground] when set; stops may be translucent. */
    val boardGradient: GradientSpec? = null,
    /** Absolute path of a local background image; never set in exports. */
    val backgroundImage: String? = null,
    /**
     * Absolute path of a separate landscape background image; used only when
     * the screen is landscape, falling back to [backgroundImage] when null.
     * Lets a portrait photo and a wide crop each fit their orientation instead
     * of one image being letterboxed or over-cropped in the other. Never set in
     * exports (travels as [backgroundImageLandscapeBase64]).
     */
    val backgroundImageLandscape: String? = null,
    /** Alpha applied to the background image itself (shared by both orientations). */
    val backgroundImageOpacity: Float = 1f,
    /** Blur radius applied to the background image (0 = sharp, 25 = heavy). */
    val backgroundImageBlur: Float = 0f,
    /**
     * The background image(s) are animated (GIF / animated WebP) and should
     * play. Off, or on a build from before this field, the same file draws as
     * its first frame — which is what makes an animated theme degrade to a
     * still one instead of failing. Mutually exclusive with
     * [backgroundImageBlur] (blurring every frame re-renders the effect per
     * frame); the editor enforces it and the renderer double-checks.
     */
    val backgroundAnimated: Boolean = false,
    /** Image bytes for export/import payloads only; stripped after import. */
    val backgroundImageBase64: String? = null,
    /** Landscape image bytes for export/import payloads only; stripped after import. */
    val backgroundImageLandscapeBase64: String? = null,
    /** Who took [backgroundImage], when it came from a photo service. */
    val backgroundPhoto: PhotoAttribution? = null,
    /** Who took [backgroundImageLandscape], when it came from a photo service. */
    val backgroundPhotoLandscape: PhotoAttribution? = null,
    /**
     * Fill behind the bars above and below the keys: the suggestion strip, the
     * emoji, symbol, dictionary and fancy-text rows, the selection macros and
     * the toolbar's own row. They stack against each other with nothing
     * between them, so they take one colour rather than one each.
     *
     * Null inherits the board, which is what every theme drew before this
     * field existed and what keeps a board gradient, image or animation
     * running unbroken behind the words (issue #109).
     *
     * Alpha is honoured like everywhere else here, so a half-transparent bar
     * is a tint over the board rather than a lid on it.
     */
    val suggestionBarBackground: Long? = null,
    /**
     * Fill behind the toolbar's tools wherever they are drawn: the tools' own
     * row, and the suggestion strip while it is showing the tools instead of
     * words. Null follows [suggestionBarBackground], so the two bars stay one
     * colour until a theme pulls them apart (issue #504). Alpha is honoured.
     */
    val toolbarBackground: Long? = null,
    /**
     * Fill of the side rail shown in one-handed mode, and the colour of the two
     * buttons on it.
     *
     * Null leaves the fill transparent, so the board runs on behind the rail,
     * and draws the glyphs in [secondaryText] — which is what the rail already
     * did. The rail was never unthemed; it simply had no way to differ from the
     * board, and a stylesheet states it as its own element.
     */
    val oneHandedPanelBackground: Long? = null,
    val oneHandedPanelIcon: Long? = null,
    /**
     * Fill for the band the system navigation bar sits on, below the bottom key
     * row. Null inherits the board, as above. Only the gesture bar's own inset
     * is painted, not the extra breathing room the bottom-padding setting adds:
     * that padding belongs to the keyboard, this band belongs to the system.
     */
    val navigationBarBackground: Long? = null,
    // Keys
    val keyShape: KeyShapeKind = KeyShapeKind.ROUNDED,
    /**
     * Absolute path of an image drawn on every letter key, clipped to the key
     * shape, over the key colour. Never set in exports — the bytes travel in
     * [assets] under `"keyTexture"`. The class-specific slots below fall back
     * sensibly (space → this, pressed → the pressed colour over this), so a
     * theme with one texture already themes the whole board.
     */
    val keyTexture: String? = null,
    /** Texture for the modifier keys (shift, delete, mode…); null follows [keyTexture]. */
    val keyTextureModifier: String? = null,
    /** Texture for the enter key; null follows [keyTextureModifier]'s chain. */
    val keyTextureEnter: String? = null,
    /** Texture for the space bar; null follows [keyTexture]. */
    val keyTextureSpace: String? = null,
    /** Texture drawn while a key is held; null keeps the pressed colour overlay. */
    val keyTexturePressed: String? = null,
    /**
     * How a texture fits its key: `"crop"` (default — centre-crop, keeps
     * aspect), `"stretch"`, or `"tile"`. A string so a mode from a later build
     * costs the field, not the theme; read through [keyTextureScaleOrDefault].
     */
    val keyTextureScale: String? = null,
    /** Alpha the textures draw with over the key colour. */
    val keyTextureOpacity: Float = 1f,
    /** Painted over letter keys when set (subtle sheen); stops may be translucent. */
    val keyGradient: GradientSpec? = null,
    val keyBackground: Long = 0xFF303338,
    val keyText: Long = 0xFFE9E9EE,
    val modifierKeyBackground: Long = 0xFF222428,
    val modifierKeyText: Long? = null,
    /**
     * Colour of the corner hint: the small long-press alternate (or icon)
     * drawn in a key's top-right. Null draws the key's own label colour at
     * 55% alpha, which is what every theme did before the field existed
     * (issue #72). Alpha is honoured, so a theme can keep the hint
     * translucent in a hue of its own.
     */
    val hintText: Long? = null,
    val enterKeyBackground: Long = 0xFF4C8DF6,
    val enterKeyText: Long = 0xFF0B1220,
    val pressedKeyBackground: Long? = null,
    val keyBorderColor: Long? = null,
    val keyBorderWidthDp: Float = 0f,
    /**
     * How far each key is lifted off the board, in dp; 0 draws no shadow.
     *
     * The bordered variants of nearly every popular FlorisBoard theme lift
     * their keys 2 dp, and a converted theme without it reads as flatter than
     * the one the user picked. A shape that cannot cast a shadow without
     * hanging the RenderThread draws none whatever this says — see
     * [castsElevationShadow], and a key whose fill is see-through draws none
     * either, since a shadow under nothing is just a smudge.
     */
    val keyElevationDp: Float = 0f,
    /**
     * Colour of that shadow, alpha honoured. Null draws the platform's own
     * black. Gboard (`shadow_color` on the key top) and FlorisBoard
     * (`shadow-color`) themes both carry one, and a coloured glow under each
     * key is half of what some of them look like. The platform ignores it
     * below Android 9, where every shadow is black.
     */
    val keyShadowColor: Long? = null,
    // Accent (shift-on tint, gesture trail, active tools, links/buttons in panels)
    val accent: Long = 0xFF8AB4F8,
    /**
     * Colour of the glide-typing trail. Null follows [accent]; set it to give
     * the swipe trail its own colour independent of the accent tint.
     */
    val gestureTrailColor: Long? = null,
    // Popups (key preview bubble + long-press alternates)
    val popupBackground: Long? = null,
    val popupText: Long? = null,
    /**
     * Where the key-preview bubble sits, as a name: `"key"` (the bubble grows
     * out of the pressed key, stock style) or `"float"` (a detached bubble
     * hovering above it). Null follows the global popup setting. A string for
     * the usual forward-compat reason; read through [popupOnKeyOrNull].
     */
    val popupPlacement: String? = null,
    /** Outline around the preview bubble; null draws none, like [keyBorderColor]. */
    val popupBorderColor: Long? = null,
    val popupBorderWidthDp: Float = 0f,
    /**
     * How far the preview bubble and the long-press alternates are lifted, in
     * dp. Null keeps the 6 dp every popup drew before this field existed.
     */
    val popupElevationDp: Float? = null,
    /**
     * The highlight under the alternate your finger is on, and under the
     * selected row of a menu. Null follows [accent], which is what every popup
     * did before this field existed.
     *
     * Its own field because a stylesheet states it outright, as the focus state
     * of the popup's items, and a theme whose accent is loud often wants the
     * highlight quieter than it.
     */
    val popupSelectedBackground: Long? = null,
    /** Text on that highlight; null derives a legible colour from its fill. */
    val popupSelectedText: Long? = null,
    /**
     * Image painted inside the preview bubble, over [popupBackground] and
     * under the label, clipped to the popup shape. Fit and alpha follow
     * [keyTextureScale] and [keyTextureOpacity]. Never set in exports — the
     * bytes travel in [assets] under `"popupTexture"`.
     */
    val popupTexture: String? = null,
    /**
     * Shape of the list-menu popups — the language picker, the clipboard and
     * emoji menus — as a [KeyShapeKind] name. Null derives a safe shape from
     * [popupShape]: the plain outlines pass through, the decorative ones fall
     * back to rounded, because a slanted character bubble is charming while a
     * slanted menu clips its own rows. Set it only to overrule that judgement.
     */
    val menuShape: String? = null,
    // Toolbar
    val toolbarIcon: Long? = null,
    val toolCircleBackground: Long? = null,
    val toolCircleActiveBackground: Long? = null,
    /**
     * Colour of the glyph on an *active* toolbar tool. Null derives a legible
     * colour from that tool's fill, which is what every theme did before this
     * field existed.
     *
     * Its own field because the active fill and the glyph on it are separate
     * elements in a FlorisBoard stylesheet, and deriving one from the other
     * throws away a colour the theme stated outright.
     */
    val toolCircleActiveIcon: Long? = null,
    /**
     * Outline around the background of every toolbar tool. Null draws none,
     * exactly like [keyBorderColor], and [toolBorderWidthDp] still has to be
     * above 0 for it to show.
     */
    val toolBorderColor: Long? = null,
    val toolBorderWidthDp: Float = 0f,
    /** How far the background behind a toolbar tool is lifted, in dp; 0 is flat. */
    val toolElevationDp: Float = 0f,
    // Panels (clipboard/snippet cards, emoji search bar)
    val chipBackground: Long? = null,
    val suggestionText: Long? = null,
    /**
     * The suggestions beside the primary one on the strip: the runner-up on
     * its left and the rest on its right. Null draws them in [suggestionText],
     * which is what every theme did before the field existed (issue #504).
     */
    val secondarySuggestionText: Long? = null,
    /**
     * Their size, as a multiple of the primary suggestion's: null or 1 keeps
     * them the same size, 0.8 draws them a fifth smaller. A ratio rather than
     * a size so it rides on the user's own suggestion-size slider. Held to
     * [SECONDARY_SUGGESTION_SCALE_RANGE] where it is read.
     */
    val secondarySuggestionScale: Float? = null,
    /**
     * Fill of the pill under each word on the strip, drawn only while the
     * user has Coloured suggestions on (#510). Null is the [accent] at 16%
     * on a dark theme and 10% on a light one, which is all the pills ever
     * were; a theme that wants them in some other colour than its accent
     * names one here. Alpha is honoured.
     */
    val suggestionPillBackground: Long? = null,
    /**
     * The primary word's pill: the one a space commits, drawn deepest. Null
     * lays [suggestionPillBackground] over itself so it still reads deeper,
     * or, with that null too, the [accent] at 34% dark and 24% light.
     */
    val primarySuggestionPillBackground: Long? = null,
    /**
     * The quieter text beside the main one: a suggestion's secondary word, a
     * clipboard entry's kind and timestamp, a panel subheading. Null draws
     * [suggestionText] at 65% alpha, which is what this was before the field
     * existed.
     */
    val secondaryText: Long? = null,
    /**
     * Hairlines between the parts of a panel and between suggestions. Null
     * draws [suggestionText] at 25% alpha, as before. Alpha is honoured, so a
     * theme can keep the rule faint in a hue of its own.
     */
    val dividerColor: Long? = null,
    /**
     * Fill of the emoji panel's search bar (gap list item 28, HeliBoard's
     * emoji search role). Null keeps the shared card colour,
     * [chipBackground], which is what the bar always drew with.
     */
    val emojiSearchBackground: Long? = null,
    /** Text and glyphs on that bar; null keeps [suggestionText]. */
    val emojiSearchText: Long? = null,
    /**
     * Fill of the recently-copied chip on the suggestion strip (gap list item
     * 28, HeliBoard's clipboard suggestion role). Null keeps the accent tint
     * the chip always drew with.
     */
    val clipboardChipBackground: Long? = null,
    /** Text and glyphs on that chip; null keeps [suggestionText]. */
    val clipboardChipText: Long? = null,
    // Chips (tool-panel buttons, style strips, plugin buttons)
    /** Text on an unselected chip; null derives from the modifier-key text. */
    val chipText: Long? = null,
    /** A selected chip's fill; null follows [toolCircleActiveBackground]'s chain. */
    val chipActiveBackground: Long? = null,
    /** Text on a selected chip; null derives a legible colour from the fill. */
    val chipActiveText: Long? = null,
    /** Outline around every chip; null draws none, like [keyBorderColor]. */
    val chipBorderColor: Long? = null,
    val chipBorderWidthDp: Float = 0f,
    /** How far a panel card is lifted off the panel, in dp; 0 is flat. */
    val cardElevationDp: Float = 0f,
    /**
     * Chip outline shape, as a [KeyShapeKind] name; null keeps the soft
     * rectangle every chip has always drawn. A string for the same reason
     * [popupShape] is one.
     */
    val chipShape: String? = null,
    /** Chip corner radius for the rounded and cut shapes; null keeps 12. */
    val chipCornerRadiusDp: Int? = null,
    /**
     * Shape of the panel cards and search bars — clipboard and snippet cards,
     * plugin rows, the converters' displays — as a [KeyShapeKind] name. Null
     * derives a safe shape from [chipShape] under the same rule as
     * [menuShape]: a scalloped chip is a sticker, a scalloped card eats its
     * own first line.
     */
    val cardShape: String? = null,
    // Radii overrides; null = follow the global appearance sliders
    val keyCornerRadiusDp: Int? = null,
    /**
     * Corner radius of the function keys (Shift, Delete, ?123, the arrows and
     * the rest of the keys that type nothing), in dp; null follows
     * [keyCornerRadiusDp] (gap list item 29, LeanType's radius per key class).
     * A single-key style's own radius still wins over it.
     */
    val modifierKeyCornerRadiusDp: Int? = null,
    /** Corner radius of Enter and the space bar, in dp; null follows [keyCornerRadiusDp]. */
    val actionKeyCornerRadiusDp: Int? = null,
    val popupCornerRadiusDp: Int? = null,
    val toolCircleRadiusDp: Int? = null,
    /**
     * Outline for every popup surface, as a [KeyShapeKind] name; null follows
     * the global setting. A string rather than the enum for the reason spelled
     * out above [PhotoAttribution]: a name from a later build that added a
     * shape has to leave the rest of the theme intact. Read it through
     * [keyShapeKindOrNull].
     */
    val popupShape: String? = null,
    /**
     * Outline for the background behind every toolbar tool, as a [KeyShapeKind]
     * name; null follows the global setting. A string for the same reason
     * [popupShape] is one. The tool radius still decides how round the rounded
     * and cut shapes are, and 0 still means no background at all.
     */
    val toolShape: String? = null,
    // Layout/type overrides; null = follow the global appearance settings.
    // Primitives only — an enum here would drop the whole theme on older
    // builds (see the note above PhotoAttribution.provider).
    val toolWidthDp: Int? = null,
    val toolbarHeightDp: Int? = null,
    /**
     * Height of the key-preview bubble in its on-key style, in dp; null follows
     * the global slider's own on-key value. One field per style, the way the
     * setting itself is stored: an on-key bubble is measured from the bottom
     * of the key it covers and a floating one from above the key, so a single
     * height seeded while the bubble floated (65 dp) handed the on-key bubble
     * a height under the key's own and hid it under the finger (#87).
     */
    val popupHeightDp: Int? = null,
    /** The floating style's bubble height, in dp; see [popupHeightDp]. */
    val popupFloatingHeightDp: Int? = null,
    val keyHeightDp: Int? = null,
    val keyGapScale: Float? = null,
    /**
     * Legacy symmetric side padding. Still honoured, and still written when a
     * theme's two edges happen to be equal, so a theme shared with an older
     * build keeps its padding; [sidePadLeftScale] / [sidePadRightScale] win
     * wherever they are set (issue #41).
     */
    val sidePadScale: Float? = null,
    val sidePadLeftScale: Float? = null,
    val sidePadRightScale: Float? = null,
    val fontScale: Float? = null,
    val boldKeyLabels: Boolean? = null,
    val hintFontScale: Float? = null,
    val gestureTrailWidthDp: Float? = null,
    val gestureTrailOpacity: Float? = null,
    // Animation
    val animation: ThemeAnimation = ThemeAnimation.NONE,
    /** Multiplier on the animation cycle speed; 1 = one cycle every ~16 s. */
    val animationSpeed: Float = 1f,
    /**
     * Key-label font, as a font id the font system already understands
     * (`serif`, `google:<Name>`, `installed:<id>`, …). Null follows the global
     * font setting. A font the device does not have falls back to the global
     * setting rather than erroring: fonts travel as their own addon, listed as
     * a dependency of the theme in a repo, never embedded in the theme file.
     * Per-script faces still win over this, so a display face never blanks a
     * non-Latin board — [scriptFontIds] is how a theme keeps its look on those
     * boards anyway.
     */
    val fontId: String? = null,
    /**
     * Per-script key fonts, keyed by `ScriptId.name` (`BENGALI`, `ARABIC`, …)
     * with the same font ids [fontId] takes.
     *
     * [fontId] deliberately loses to a script's automatic Noto face, because a
     * Latin-only display font draws a Bengali board as empty boxes. That is the
     * right default and the wrong answer for a *pixel* theme: the theme is not
     * asking for the wrong glyphs, it is asking for a face that matches, and
     * one file rarely covers every script. So a theme names one font per script
     * it has an answer for, and every other script keeps its Noto face.
     *
     * Sits above the script's automatic face and below the user's own per-script
     * pick — the user chose that font deliberately and a theme does not get to
     * overrule it. A font id the device has nothing for resolves to null and
     * falls through as if it were absent, exactly like [fontId]: the fonts are
     * separate addons listed in the theme's `requires`, and skipping them still
     * leaves a working theme.
     *
     * A map rather than fields per script, and string keys rather than the enum,
     * for the reason above [PhotoAttribution]: an unknown script name costs its
     * entry, not the theme.
     */
    val scriptFontIds: Map<String, String> = emptyMap(),
    /**
     * Key sound, as a KeySoundStyle name. A string rather than the enum for
     * the reason spelled out above [PhotoAttribution]; an unknown name costs
     * the field, not the theme. Null follows the global sound setting.
     */
    val soundStyle: String? = null,
    /**
     * Which installed sound to play when [soundStyle] is `CUSTOM`. Sounds are
     * their own addon like fonts; a missing id falls back to the global sound.
     */
    val soundCustomId: String? = null,
    /**
     * Decorative stickers over the key grid; see [DecalSpec]. One unknown
     * JSON key to an older build.
     */
    val decals: List<DecalSpec> = emptyList(),
    /**
     * Particle burst on every key press, as a [KeyEffectKind] name; null for
     * none. A string for the usual forward-compat reason; read through
     * [keyEffectKindOrNull]. Never plays under reduce motion or power saving.
     */
    val keyEffect: String? = null,
    /** The emoji the `EMOJI` effect throws; each glyph is one particle kind. */
    val keyEffectParam: String? = null,
    /** Scales how many particles a press throws. */
    val keyEffectIntensity: Float = 1f,
    /**
     * The images the `CUSTOM_IMAGE` effect throws — up to [MAX_EFFECT_IMAGES]
     * local paths, each one a particle kind, the way each emoji in
     * [keyEffectParam] is one. Transparent PNGs look best. Never set in
     * exports; the bytes travel in [assets] under `effectImage:<index>`.
     */
    val keyEffectImages: List<String> = emptyList(),
    /**
     * What colour the particles draw in, as a [KeyEffectColorMode] name; null
     * is [KeyEffectColorMode.NATURAL], which is what every theme written
     * before this field existed means. A string for the usual forward-compat
     * reason; read through [keyEffectColorModeOrNull].
     */
    val keyEffectColor: String? = null,
    /** The colour the `CUSTOM` mode tints with; ignored by every other mode. */
    val keyEffectCustomColor: Long? = null,
    /** Scales a particle's size. See [EFFECT_SIZE_RANGE]. */
    val keyEffectSize: Float = 1f,
    /** Scales how fast a particle leaves the key. See [EFFECT_SPEED_RANGE]. */
    val keyEffectSpeed: Float = 1f,
    /**
     * How wide the burst fans out, 1 being the default cone and small values
     * a near-vertical jet. See [EFFECT_SPREAD_RANGE].
     */
    val keyEffectSpread: Float = 1f,
    /**
     * Scales gravity. Negative floats the particles upward instead of letting
     * them fall, which is the whole point of the range going below zero. See
     * [EFFECT_GRAVITY_RANGE].
     */
    val keyEffectGravity: Float = 1f,
    /** How long a particle lives, in milliseconds. See [EFFECT_DURATION_RANGE]. */
    val keyEffectDurationMs: Int = DEFAULT_EFFECT_DURATION_MS,
    /**
     * Per-key style overrides — a single key's own colours, keyed by the
     * key's lowercase label (letter keys) or its action name (special keys);
     * see [KeyOverride]. One unknown JSON key to an older build, which
     * imports the theme without them.
     */
    val keyOverrides: Map<String, KeyOverride> = emptyMap(),
    /**
     * Glyphs this theme draws on the action keys, as icon-slot id → local path.
     *
     * Keys are the ids in `core.icons.IconSlots` (`key.backspace`,
     * `key.shift_on`, `key.enter_send`, …), as plain strings: `:core:icons`
     * depends on `:core:settings`, which depends on this module, so the two
     * cannot see each other and `GboardIconSlotsTest` pins the spellings
     * together instead. An id this build has no slot for costs its entry.
     *
     * Here because a Gboard theme is as likely to replace the delete, shift and
     * enter glyphs as to recolour them — 264 of the 491 themes in the Rboard
     * repository do, always as PNGs — and dropping them was the one loss that
     * made a converted theme look like a different theme. The files are
     * app-private, written by [withExtractedImages] like every other image
     * here; the bytes travel in [assets] under [ASSET_KEY_ICON_PREFIX].
     *
     * They reach the keyboard through `applyThemeOverrides`, which lays them
     * onto `IconSettings.themeIcons` — under the user's own icon pack and their
     * per-slot picks, so a theme dresses the slots nobody has claimed and never
     * overrules a standing choice.
     */
    val keyIcons: Map<String, String> = emptyMap(),
    /**
     * Key pictures drawn nine-slice rather than fitted, keyed by the picture's
     * transport slot ([ASSET_KEY_TEXTURE], [ASSET_KEY_TEXTURE_ENTER],
     * `keyOverrideTexture:ENTER`, `keyOverridePressed:a`, …); see
     * [TextureSlice]. Empty for every theme but an imported FUTO one, whose
     * key borders are nine-slice art: a rounded frame whose corners must keep
     * their size on a wide space bar and a narrow letter alike.
     */
    val textureSlices: Map<String, TextureSlice> = emptyMap(),
    /**
     * The user's own rule sheet for this theme, as they typed it: FlorisBoard
     * snygg rules naming keys by code, output, style id or class, with
     * variables. Compiled onto the theme's key colours and single-key styles
     * each time it is drawn, never saved into them; see [KeyRuleSheet]. Null
     * or blank is no sheet.
     */
    val ruleSheet: String? = null,
    /**
     * Auxiliary image bytes for transport, keyed by slot name — the key
     * textures today (`"keyTexture"`, `"keyTextureModifier"`, …), whatever
     * needs to travel tomorrow. The generic sibling of
     * [backgroundImageBase64]: populated by [withEmbeddedImages] on the way
     * out, written to files and emptied by [withExtractedImages] on the way
     * in, never non-empty at rest. A map of strings so an older build ignores
     * the whole thing and a slot it doesn't know costs that slot alone.
     */
    val assets: Map<String, String> = emptyMap(),
    /**
     * Whether the colours follow the wallpaper, the way the Default theme's do
     * with Material You on. Off keeps the fixed colours every theme has always
     * had. Android 12 and later only: an older phone has no wallpaper palette,
     * and the theme draws its stored colours there. See [followingWallpaper]
     * for how a theme that was not written against the wallpaper is moved onto
     * it.
     */
    val followWallpaper: Boolean = false,
    /**
     * The Material You roles the theme was written against, each with the
     * colour it had when the theme was made: `dark:primary` to `0xFF…`. Set by
     * a FlorisBoard import, whose stylesheets name roles instead of colours;
     * empty everywhere else. With [followWallpaper] on, a colour still equal to
     * one of these values takes that role's value from the wallpaper now.
     * Keys are built by [wallpaperRoleKey].
     */
    val wallpaperRoles: Map<String, Long> = emptyMap(),
    /**
     * Frosted glass: which surfaces show what is behind them blurred, and how
     * the glass is dressed; see [FrostSpec]. Null is no glass at all, which is
     * every theme written before the field existed. A nested nullable class so
     * an older build drops the one key and imports a plain translucent theme.
     */
    val frost: FrostSpec? = null,
    /**
     * What the keys are made of: a raised cap, brushed metal, wood, neon and
     * the rest; see [KeyMaterialSpec]. Null draws the flat keys every theme
     * had before the field existed. Under a [keySkin] it still dresses the
     * labels but not the faces, which the skin draws.
     */
    val keyMaterial: KeyMaterialSpec? = null,
    /** Something moving on the board behind the keys; see [BoardEffectSpec]. */
    val boardEffect: BoardEffectSpec? = null,
    /** How the keys light up, on their own or as they are pressed; see [KeyLightingSpec]. */
    val keyLighting: KeyLightingSpec? = null,
    /** Light from the finger and keys that dip under it; see [TouchBloomSpec]. */
    val touchBloom: TouchBloomSpec? = null,
    /** Hand-drawn key art that replaces the key faces; see [KeySkinSpec]. */
    val keySkin: KeySkinSpec? = null,
    /**
     * The family's display name, when this theme heads one (see [variants]).
     * Null on every variant and on themes with no variants; built-in families
     * are named through [builtInThemeFamilyNameRes] instead, so their label
     * translates.
     */
    val familyName: String? = null,
    /**
     * Alternate looks of this theme — colorways, a light half, a gradient
     * dress. Each variant is a complete spec with its own globally-unique id
     * (selection, pins and auto-theme slots all address variants directly),
     * and the theme carrying them is itself the first, representative look.
     * One level deep: a variant's own variants are never read, and every path
     * that builds one strips them. An older build drops this key and imports
     * the base theme alone, which is the point of nesting over a container
     * format.
     */
    val variants: List<ThemeSpec> = emptyList(),
    /**
     * The repository addon this theme was installed from, and so the one an
     * edited copy is a remix of. Set by the addon installer, carried through
     * every copy, and sent along when the theme is published to the community
     * repository so the original author is credited. Null for a theme made
     * here.
     */
    val basedOn: RemixCredit? = null,
)

/**
 * Sets one edge of the theme's side padding, keeping the legacy symmetric
 * [ThemeSpec.sidePadScale] in step (issue #41).
 *
 * The legacy field carries the value while the two edges agree and goes null
 * the moment they diverge, which is the most an older build can honestly
 * render: an even margin when there is one, and the global setting when the
 * theme is asking for something that build has no field for.
 */
fun ThemeSpec.withSidePad(left: Float? = null, right: Float? = null): ThemeSpec {
    val newLeft = left ?: sidePadLeftScale ?: sidePadScale ?: 0f
    val newRight = right ?: sidePadRightScale ?: sidePadScale ?: 0f
    return copy(
        sidePadLeftScale = newLeft,
        sidePadRightScale = newRight,
        sidePadScale = newLeft.takeIf { it == newRight },
    )
}

/**
 * One key's own style, overriding the class-level look. Everything is
 * nullable: a key that only recolours its popup carries exactly that.
 *
 * The map key naming these (see [ThemeSpec.keyOverrides]) is the key's
 * lowercase label for letter keys — so the override follows the letter across
 * layouts and languages — and the action's name (`ENTER`, `SHIFT`, `SPACE`,
 * `DELETE`, `SYMBOLS`, …) for the special keys.
 *
 * Every field here is a *replacement*, never a scaling of the theme's answer:
 * the point of a single-key style is to say what this one key does instead of
 * what the rest of the board does (issue #107).
 */
@Serializable
data class KeyOverride(
    val background: Long? = null,
    /**
     * This key's face while held; null derives a shade from [background], or
     * follows the board's pressed colour when the key has no face of its own.
     * Set by a rule sheet's `key[…]:pressed` (see [KeyRuleSheet]).
     */
    val pressedBackground: Long? = null,
    val text: Long? = null,
    val border: Long? = null,
    val popupBackground: Long? = null,
    val popupText: Long? = null,
    /**
     * The corner hint's colour on this one key; null follows
     * [ThemeSpec.hintText], which itself falls back to the faded label.
     */
    val hint: Long? = null,
    /**
     * This key's own texture, as a local image path — the per-key twin of
     * [ThemeSpec.keyTexture], drawn with the theme's own fit and opacity so a
     * board's textures still read as one set. Never set in exports; the bytes
     * travel in [ThemeSpec.assets] under `keyOverrideTexture:<id>`.
     */
    val texture: String? = null,
    /**
     * The picture this key wears while held, as a local image path: the
     * per-key twin of [ThemeSpec.keyTexturePressed]. Null falls back to that
     * board-wide one, then to the pressed colour over [texture]. Set by the
     * FUTO importer, whose themes draw a pressed enter key and a pressed
     * letter from different pictures. Never set in exports; the bytes travel
     * in [ThemeSpec.assets] under `keyOverridePressed:<id>`.
     */
    val pressedTexture: String? = null,
    /**
     * The particle burst *this* key throws, as a [KeyEffectKind] name; null
     * follows the theme's own effect. A string for the usual forward-compat
     * reason; read through [keyEffectKindOrNull].
     *
     * `CUSTOM_IMAGE` is not offered per key: its particles are image files,
     * and a per-key image set is a transport and memory cost out of all
     * proportion to "the enter key throws hearts". An override naming it is
     * read as no effect of its own.
     */
    val effect: String? = null,
    /** The emoji this key's `EMOJI` effect throws; see [ThemeSpec.keyEffectParam]. */
    val effectParam: String? = null,
    /**
     * How big this key's label draws, as a multiple of an ordinary letter's
     * size — the theme-side twin of `Key.labelScale`, which a layout may set
     * for the same key. The key's own authored scale wins: the layout is the
     * more specific answer, and a theme is worn by every layout.
     */
    val labelScale: Float? = null,
    /**
     * Whether this key's label draws bold; null follows the board (the theme's
     * [ThemeSpec.boldKeyLabels] and the accessibility switch under it).
     */
    val bold: Boolean? = null,
    /**
     * This key's own outline, as a [KeyShapeKind] name; null follows the
     * board's [ThemeSpec.keyShape]. A string for the usual forward-compat
     * reason; read through [keyShapeKindOrNull].
     *
     * A single round enter key on a grid of soft rectangles is a signature a
     * whole family of themes is built on, and it is the one thing a per-key
     * style could not say. How round a rounded key is comes from
     * [cornerRadiusDp], and from the board's radius where that is null.
     */
    val shape: String? = null,
    /**
     * The key's resting face as a gradient, painted over [background]. A
     * press still paints the solid pressed colour, so the hit stays legible.
     * A red-to-crimson enter key, a space bar that fades at its ends: looks
     * one colour cannot give.
     */
    val gradient: GradientSpec? = null,
    /**
     * A halo of this colour behind the key, showing in the gaps round it.
     * Alpha is honoured, so a faint glow is a translucent colour.
     */
    val glow: Long? = null,
    /** How far that halo reaches past the key, in dp; null is [DEFAULT_KEY_GLOW_DP]. */
    val glowDp: Float? = null,
    /**
     * This key's own corner radius for the rounded and cut shapes, in dp;
     * null follows the board's. See [KEY_OVERRIDE_RADIUS_RANGE].
     */
    val cornerRadiusDp: Int? = null,
) {
    val isEmpty: Boolean
        get() = background == null && text == null && border == null &&
            popupBackground == null && popupText == null && hint == null &&
            texture == null && pressedTexture == null && effect == null && effectParam == null &&
            pressedBackground == null &&
            labelScale == null && bold == null && shape == null &&
            gradient == null && glow == null && glowDp == null && cornerRadiusDp == null

    /**
     * The kind of burst this key throws on its own, or null when it follows
     * the theme. `CUSTOM_IMAGE` is filtered here rather than at each reader,
     * so a hand-written theme naming it cannot reach the glyph loader.
     */
    val effectKind: KeyEffectKind?
        get() = keyEffectKindOrNull(effect)?.takeIf { it != KeyEffectKind.CUSTOM_IMAGE }
}

/**
 * How one key picture is cut nine-slice: four corners drawn at their own size,
 * four edges stretched one way, and the middle stretched both ways, so a frame
 * drawn once fits a key of any width.
 *
 * [left], [top], [right] and [bottom] bound the stretching middle, as fractions
 * of the picture's width and height (FUTO's `slicing`). [widthDp] and
 * [heightDp] are the picture's natural size on screen, which sets how big the
 * fixed corners draw; null fits the corners to the key's height instead. Where
 * the corners of a small key would overlap, they shrink together.
 *
 * [path] ties the cut to the one file it was made for. It is stamped when the
 * picture is written to disk and cleared when the theme is exported, so a
 * picture the user later swaps in the editor is drawn the ordinary way instead
 * of being cut along lines that belong to another image.
 */
@Serializable
data class TextureSlice(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val widthDp: Float? = null,
    val heightDp: Float? = null,
    /** Multiplied into the picture's colours (FUTO's `background_tint`); null leaves them. */
    val tint: Long? = null,
    val path: String? = null,
) {
    /** The cut with its fractions put in order and inside the picture. */
    fun normalized(): TextureSlice {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        return copy(left = minOf(l, r), right = maxOf(l, r), top = minOf(t, b), bottom = maxOf(t, b))
    }
}

/**
 * Bounds a per-key label scale is held to. The same numbers `KeyLabelScaleRange`
 * holds an authored `Key.labelScale` to — the two cannot be one constant
 * because `:core:theme` does not see the layout module, so they are pinned
 * together by `KeyOverrideLabelScaleRangeTest` instead.
 */
val KEY_OVERRIDE_LABEL_SCALE_RANGE = 0.3f..2f

/** Bounds of [KeyOverride.glowDp]; past a key gap or two a glow is a smear. */
val KEY_GLOW_RANGE = 1f..16f

/** What [KeyOverride.glowDp] means when the key names a glow and no reach. */
const val DEFAULT_KEY_GLOW_DP = 4f

/** Bounds of [KeyOverride.cornerRadiusDp]; the board's own radius slider shares them. */
val KEY_OVERRIDE_RADIUS_RANGE = 0..28

/**
 * One decorative sticker laid over the key grid — a character leaning on the
 * keys, hearts in a corner. Purely visual: the layer it draws on takes no
 * touches. Coordinates are normalized to the key grid's rect so a decal keeps
 * its place across widths and orientations.
 */
@Serializable
data class DecalSpec(
    /** Unique within the theme; names the image's transport slot (`decal:<id>`). */
    val id: String,
    /** Absolute local path of the sticker image; never set in exports. */
    val image: String? = null,
    /** Centre, as fractions of the grid's width and height. */
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    /** Width, as a fraction of the grid's width; height follows the image. */
    val scale: Float = 0.25f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
)

/** The most decals a theme may carry; past a handful they are just occlusion. */
const val MAX_DECALS = 6

/**
 * Bounds for [ThemeSpec.secondarySuggestionScale]. Below half the words are
 * unreadable beside the primary; a little above one is allowed so a theme can
 * make the runner-ups the loud ones if it wants to.
 */
val SECONDARY_SUGGESTION_SCALE_RANGE = 0.5f..1.2f

/** The most looks one theme may carry; the editor's Add stops here. */
const val MAX_THEME_VARIANTS = 12

/** A key-press particle burst's kind. Never serialized — travels as a string. */
enum class KeyEffectKind { STARS, HEARTS, SPARKLE, CONFETTI, EMOJI, CUSTOM_IMAGE }

/** The most images the CUSTOM_IMAGE press effect may carry. */
const val MAX_EFFECT_IMAGES = 6

/**
 * Where a particle's colour comes from. NATURAL leaves the glyph or image
 * alone — the only mode that keeps a multicoloured emoji multicoloured; every
 * other mode tints the particle flat, which turns a glyph into a silhouette.
 * Never serialized — travels as a string.
 */
enum class KeyEffectColorMode { NATURAL, KEY_TEXT, ACCENT, GESTURE_TRAIL, CUSTOM, RANDOM }

/**
 * The mode behind [ThemeSpec.keyEffectColor]. An absent or unknown name is
 * [KeyEffectColorMode.NATURAL], so a mode from a later build costs the tint,
 * not the effect.
 */
fun keyEffectColorMode(name: String?): KeyEffectColorMode =
    name?.let { wanted -> KeyEffectColorMode.entries.firstOrNull { it.name.equals(wanted, true) } }
        ?: KeyEffectColorMode.NATURAL

/** Bounds for the press effect's physics sliders; the editor and the field share them. */
val EFFECT_SIZE_RANGE = 0.4f..3f
val EFFECT_SPEED_RANGE = 0.3f..2.5f
val EFFECT_SPREAD_RANGE = 0.1f..1f
val EFFECT_GRAVITY_RANGE = -1f..3f
val EFFECT_DURATION_RANGE = 200..2500

/** A particle's lifetime before any theme says otherwise. */
const val DEFAULT_EFFECT_DURATION_MS = 650

/**
 * The effect behind [ThemeSpec.keyEffect]; null for an absent or unknown
 * name, which is also what a name from a later build resolves to — the field
 * costs itself, never the theme.
 */
fun keyEffectKindOrNull(name: String?): KeyEffectKind? =
    name?.let { wanted -> KeyEffectKind.entries.firstOrNull { it.name.equals(wanted, true) } }

/** How a key texture fits its key. Never serialized — travels as a string. */
enum class KeyTextureScale { CROP, STRETCH, TILE }

/**
 * The placement behind [ThemeSpec.popupPlacement]: true for `"key"`, false for
 * `"float"`, and null for an absent or unknown name — which follows the global
 * setting, so a placement from a later build costs the field, not the theme.
 */
fun popupOnKeyOrNull(name: String?): Boolean? = when {
    name == null -> null
    name.equals("key", ignoreCase = true) -> true
    name.equals("float", ignoreCase = true) -> false
    else -> null
}

/**
 * The scale mode behind [ThemeSpec.keyTextureScale]; an unknown or absent
 * name is CROP, the mode that never distorts.
 */
fun keyTextureScaleOrDefault(name: String?): KeyTextureScale =
    KeyTextureScale.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: KeyTextureScale.CROP

/** Follows system light/dark + Material You; not a stored [ThemeSpec]. */
const val DEFAULT_THEME_ID = "default"

private val themeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object ThemeCodec {

    /** Matches the settings backup's `wmsettings.json` shape. */
    const val FILE_EXTENSION = "wmtheme.json"

    /**
     * Plain JSON rather than a vendor type, for the reason spelled out in
     * [com.wasimaster.wmkeyboard.core.layout.LayoutFile.MIME_TYPE]: a custom
     * MIME would stop most file managers and chat apps from offering the file.
     */
    const val MIME_TYPE = "application/json"

    /**
     * What the import picker accepts. Permissive on purpose: providers report a
     * `.json` file as `text/plain` or `application/octet-stream` as often as not.
     */
    val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

    fun encodeList(themes: List<ThemeSpec>): String = themeJson.encodeToString(themes)

    fun decodeList(json: String): List<ThemeSpec> {
        // A blank value is what a user with no themes of their own has stored,
        // which is most of them; parsing it only to catch the exception cost a
        // filled stack trace per settings emission.
        if (json.isBlank()) return emptyList()
        return runCatching { themeJson.decodeFromString<List<ThemeSpec>>(json) }
            .getOrDefault(emptyList())
    }

    fun encode(theme: ThemeSpec): String = themeJson.encodeToString(theme)

    fun decode(json: String): ThemeSpec? {
        if (json.isBlank()) return null
        return runCatching { themeJson.decodeFromString<ThemeSpec>(json) }.getOrNull()
    }
}

/**
 * Reads the local background image file(s) into base64 for a transport payload
 * (single-theme export or full-config backup) and nulls the local paths, so the
 * image travels with the theme instead of a device-local path that only resolves
 * on the source phone. A path that no longer resolves becomes null.
 */
fun ThemeSpec.withEmbeddedImages(): ThemeSpec {
    fun encode(path: String?) = path?.let {
        runCatching { Base64.encodeToString(File(it).readBytes(), Base64.NO_WRAP) }.getOrNull()
    }
    val portrait = encode(backgroundImage)
    val landscape = encode(backgroundImageLandscape)
    // The generic slots: every auxiliary image the theme points at, keyed by
    // the field it came from. A path that no longer resolves is simply absent.
    val embedded = buildMap {
        for ((slot, path) in assetPaths()) {
            encode(path)?.let { put(slot, it) }
        }
        for (decal in decals) {
            encode(decal.image)?.let { put("$ASSET_DECAL_PREFIX${decal.id}", it) }
        }
        keyEffectImages.forEachIndexed { index, path ->
            encode(path)?.let { put("$ASSET_EFFECT_IMAGE_PREFIX$index", it) }
        }
        for ((key, override) in keyOverrides) {
            encode(override.texture)?.let { put("$ASSET_KEY_OVERRIDE_TEXTURE_PREFIX$key", it) }
            encode(override.pressedTexture)?.let { put("$ASSET_KEY_OVERRIDE_PRESSED_PREFIX$key", it) }
        }
        for ((slot, path) in keyIcons) {
            encode(path)?.let { put("$ASSET_KEY_ICON_PREFIX$slot", it) }
        }
    }
    return copy(
        backgroundImage = null,
        backgroundImageBase64 = portrait,
        backgroundImageLandscape = null,
        backgroundImageLandscapeBase64 = landscape,
        // A credit only travels with the image it belongs to. A path that no
        // longer resolves already becomes null above, and leaving the
        // attribution behind would name a photographer whose photo is not in
        // the file — which is worse than no credit at all.
        backgroundPhoto = backgroundPhoto?.takeIf { portrait != null },
        backgroundPhotoLandscape = backgroundPhotoLandscape?.takeIf { landscape != null },
        keyTexture = null,
        keyTextureModifier = null,
        keyTextureEnter = null,
        keyTextureSpace = null,
        keyTexturePressed = null,
        popupTexture = null,
        decals = decals.map { it.copy(image = null) },
        keyEffectImages = emptyList(),
        keyOverrides = keyOverrides.mapValues { (_, override) ->
            override.copy(texture = null, pressedTexture = null)
        },
        keyIcons = emptyMap(),
        // A cut names the file it was made for; on another device that file is
        // the one the import writes, which stamps it again.
        textureSlices = textureSlices.mapValues { (_, slice) -> slice.copy(path = null) },
        assets = embedded,
        // Each variant embeds its own images; the nested-variant strip keeps
        // the one-level contract even for hand-edited files.
        variants = variants.map { it.withEmbeddedImages().copy(variants = emptyList()) },
    )
}

/** Slot name → local path, for every auxiliary image field. */
private fun ThemeSpec.assetPaths(): List<Pair<String, String?>> = listOf(
    ASSET_KEY_TEXTURE to keyTexture,
    ASSET_KEY_TEXTURE_MODIFIER to keyTextureModifier,
    ASSET_KEY_TEXTURE_ENTER to keyTextureEnter,
    ASSET_KEY_TEXTURE_SPACE to keyTextureSpace,
    ASSET_KEY_TEXTURE_PRESSED to keyTexturePressed,
    ASSET_POPUP_TEXTURE to popupTexture,
)

const val ASSET_KEY_TEXTURE = "keyTexture"
const val ASSET_KEY_TEXTURE_MODIFIER = "keyTextureModifier"
const val ASSET_KEY_TEXTURE_ENTER = "keyTextureEnter"
const val ASSET_KEY_TEXTURE_SPACE = "keyTextureSpace"
const val ASSET_KEY_TEXTURE_PRESSED = "keyTexturePressed"
const val ASSET_POPUP_TEXTURE = "popupTexture"

/** Prefix of a decal image's transport slot; the decal's id follows it. */
const val ASSET_DECAL_PREFIX = "decal:"

/** Prefix of a press-effect image's transport slot; its list index follows. */
const val ASSET_EFFECT_IMAGE_PREFIX = "effectImage:"

/**
 * Prefix of a single-key texture's transport slot; the override's own map key
 * follows it (`keyOverrideTexture:a`, `keyOverrideTexture:ENTER`).
 */
const val ASSET_KEY_OVERRIDE_TEXTURE_PREFIX = "keyOverrideTexture:"

/**
 * Prefix of a single key's held-down picture's transport slot; the override's
 * own map key follows it (`keyOverridePressed:ENTER`). See
 * [KeyOverride.pressedTexture].
 */
const val ASSET_KEY_OVERRIDE_PRESSED_PREFIX = "keyOverridePressed:"

/**
 * Prefix of a theme key glyph's transport slot; the icon-slot id follows it
 * (`keyIcon:key.backspace`). See [ThemeSpec.keyIcons].
 */
const val ASSET_KEY_ICON_PREFIX = "keyIcon:"

/**
 * Inverse of [withEmbeddedImages]: writes any embedded base64 image(s) into
 * [dir] (filenames keyed off [id]) and returns a copy pointing at the local
 * files with the base64 stripped. When no base64 is present the existing path is
 * kept — old backups that carry only paths, and themes with no image, are
 * unchanged.
 */
fun ThemeSpec.withExtractedImages(dir: File): ThemeSpec {
    fun write(name: String, b64: String?): String? = b64?.let {
        runCatching {
            dir.mkdirs()
            File(dir, name).apply { writeBytes(Base64.decode(it, Base64.DEFAULT)) }.absolutePath
        }.getOrNull()
    }
    val extracted = copy(
        backgroundImage = write("$id.img", backgroundImageBase64) ?: backgroundImage,
        backgroundImageBase64 = null,
        backgroundImageLandscape = write("${id}_land.img", backgroundImageLandscapeBase64)
            ?: backgroundImageLandscape,
        backgroundImageLandscapeBase64 = null,
        keyTexture = write("${id}_tex.img", assets[ASSET_KEY_TEXTURE]) ?: keyTexture,
        keyTextureModifier = write("${id}_tex_mod.img", assets[ASSET_KEY_TEXTURE_MODIFIER])
            ?: keyTextureModifier,
        keyTextureEnter = write("${id}_tex_enter.img", assets[ASSET_KEY_TEXTURE_ENTER])
            ?: keyTextureEnter,
        keyTextureSpace = write("${id}_tex_space.img", assets[ASSET_KEY_TEXTURE_SPACE])
            ?: keyTextureSpace,
        keyTexturePressed = write("${id}_tex_press.img", assets[ASSET_KEY_TEXTURE_PRESSED])
            ?: keyTexturePressed,
        popupTexture = write("${id}_tex_popup.img", assets[ASSET_POPUP_TEXTURE])
            ?: popupTexture,
        decals = decals.map { decal ->
            decal.copy(
                image = write("${id}_decal_${decal.id}.img", assets["$ASSET_DECAL_PREFIX${decal.id}"])
                    ?: decal.image,
            )
        },
        keyEffectImages = run {
            // Extracted by index; a theme edited on-device keeps its paths.
            val extracted = (0 until MAX_EFFECT_IMAGES).mapNotNull { index ->
                write("${id}_fx_$index.img", assets["$ASSET_EFFECT_IMAGE_PREFIX$index"])
            }
            extracted.ifEmpty { keyEffectImages }
        },
        keyOverrides = keyOverrides.mapValues { (key, override) ->
            // The map key is a letter or an action name, so it is safe in a
            // filename once the non-word characters are out of it — the only
            // ids that carry any are hand-written ones.
            val tag = key.filter { it.isLetterOrDigit() }.ifEmpty { "k" }
            override.copy(
                texture = write(
                    "${id}_keytex_$tag.img",
                    assets["$ASSET_KEY_OVERRIDE_TEXTURE_PREFIX$key"],
                ) ?: override.texture,
                pressedTexture = write(
                    "${id}_keypress_$tag.img",
                    assets["$ASSET_KEY_OVERRIDE_PRESSED_PREFIX$key"],
                ) ?: override.pressedTexture,
            )
        },
        // Driven off the asset keys rather than off `keyIcons`, because on the
        // way in that map is empty and the slots are only named in `assets`.
        // The file name carries no extension: what the bytes are is settled by
        // sniffing them, the same way a pack's own icon is.
        keyIcons = run {
            val extracted = assets.keys
                .filter { it.startsWith(ASSET_KEY_ICON_PREFIX) }
                .mapNotNull { key ->
                    val slot = key.removePrefix(ASSET_KEY_ICON_PREFIX)
                    val tag = slot.filter { it.isLetterOrDigit() }.ifEmpty { "i" }
                    write("${id}_keyicon_$tag.img", assets[key])?.let { slot to it }
                }
            if (extracted.isEmpty()) keyIcons else extracted.toMap()
        },
        assets = emptyMap(),
        // Variant filenames key off each variant's own id, which is globally
        // unique, so a family extracts without collisions.
        variants = variants.map { it.withExtractedImages(dir) },
    )
    if (textureSlices.isEmpty()) return extracted
    // Each cut is tied to the file just written for its slot. A slot with no
    // bytes in this file keeps whatever tie it had.
    return extracted.copy(
        textureSlices = textureSlices.mapValues { (slot, slice) ->
            if (slot in assets) slice.copy(path = extracted.texturePathFor(slot)) else slice
        },
    )
}

/**
 * The local path the picture in transport [slot] lives at, or null when the
 * theme has none there. The inverse of the slot names [withEmbeddedImages]
 * writes, for the key pictures only.
 */
fun ThemeSpec.texturePathFor(slot: String): String? = when {
    slot == ASSET_KEY_TEXTURE -> keyTexture
    slot == ASSET_KEY_TEXTURE_MODIFIER -> keyTextureModifier
    slot == ASSET_KEY_TEXTURE_ENTER -> keyTextureEnter
    slot == ASSET_KEY_TEXTURE_SPACE -> keyTextureSpace
    slot == ASSET_KEY_TEXTURE_PRESSED -> keyTexturePressed
    slot == ASSET_POPUP_TEXTURE -> popupTexture
    slot.startsWith(ASSET_KEY_OVERRIDE_TEXTURE_PREFIX) ->
        keyOverrides[slot.removePrefix(ASSET_KEY_OVERRIDE_TEXTURE_PREFIX)]?.texture
    slot.startsWith(ASSET_KEY_OVERRIDE_PRESSED_PREFIX) ->
        keyOverrides[slot.removePrefix(ASSET_KEY_OVERRIDE_PRESSED_PREFIX)]?.pressedTexture
    else -> null
}

/**
 * The nine-slice cut for the picture at [path] in transport [slot], or null to
 * draw it fitted: no cut was made for the slot, or the cut was made for a
 * different file than the one there now.
 */
fun Map<String, TextureSlice>.sliceFor(slot: String, path: String?): TextureSlice? {
    if (path == null) return null
    val slice = this[slot] ?: return null
    return slice.takeIf { it.path == path }?.normalized()
}

// ---- theme families ----

/** This theme and its [ThemeSpec.variants], the theme itself first. */
fun ThemeSpec.selfAndVariants(): List<ThemeSpec> = listOf(this) + variants

/**
 * Every theme in the list, with each family's variants laid out flat after
 * their parent — the shape every "list all themes" surface wants.
 */
fun List<ThemeSpec>.flattenedThemes(): List<ThemeSpec> = flatMap { it.selfAndVariants() }

/**
 * The list entry that is, or carries, the theme with this [id] — the family a
 * variant belongs to, or the theme itself when it heads one (or none).
 */
fun List<ThemeSpec>.findThemeFamily(id: String): ThemeSpec? =
    firstOrNull { family -> family.id == id || family.variants.any { it.id == id } }

/**
 * The stored spec behind a theme id, variants included — customs first, then
 * built-ins, the same precedence `activeThemeSpec` has always had.
 */
fun findThemeSpec(id: String, customThemes: List<ThemeSpec>): ThemeSpec? =
    customThemes.flattenedThemes().find { it.id == id }
        ?: BuiltInThemes.flattenedThemes().find { it.id == id }

/**
 * A copy with the theme's id replaced by [baseId] and every variant reminted
 * off it, for import paths and duplication: extracted image filenames key off
 * ids, so ids from another device (or a built-in) must never survive into the
 * custom store. Also enforces the one-level contract by stripping any
 * variants-of-variants a hand-edited file might carry.
 */
fun ThemeSpec.withFreshIds(baseId: String): ThemeSpec = copy(
    id = baseId,
    variants = variants.mapIndexed { index, variant ->
        variant.copy(id = "${baseId}_v$index", variants = emptyList())
    },
)

/**
 * A copy of the family with the member (itself or one of its variants) whose
 * id is [id] passed through [transform] — the single write shape for editing
 * one look of a family in place.
 */
fun ThemeSpec.replacingMember(id: String, transform: (ThemeSpec) -> ThemeSpec): ThemeSpec =
    if (this.id == id) {
        transform(this)
    } else {
        copy(variants = variants.map { if (it.id == id) transform(it) else it })
    }

/**
 * One family out of several sibling themes — what a multi-theme import (a
 * FlorisBoard extension, say) becomes. The first theme heads it; the rest are
 * its variants, stripped to one level. [familyName] only sticks when there is
 * a family to name — a single theme keeps its own name and no group label.
 */
fun groupAsFamily(themes: List<ThemeSpec>, familyName: String?): ThemeSpec =
    themes.first().copy(
        familyName = familyName?.takeIf { it.isNotBlank() && themes.size > 1 },
        variants = themes.drop(1).map { it.copy(variants = emptyList()) },
    )

/**
 * A change to which looks share a family: the families it replaces, as they
 * stood, and what goes in their place. Every look keeps its id, so the theme in
 * use, the auto-theme slots and anything else pointing at a look still find it;
 * undoing is writing [before] back over [after].
 */
data class ThemeRegroup(val before: List<ThemeSpec>, val after: List<ThemeSpec>)

/**
 * The looks [lookIds] gathered into one family, in that order, the first one
 * heading it and [familyName] its label. A family that loses only some of its
 * looks keeps the rest, headed by the first one left. Null when fewer than two
 * looks are found, which is no family at all.
 */
fun List<ThemeSpec>.gatheringLooks(lookIds: List<String>, familyName: String?): ThemeRegroup? {
    val wanted = lookIds.toSet()
    val byId = flattenedThemes().associateBy { it.id }
    val gathered = lookIds.distinct().mapNotNull { byId[it] }
    if (gathered.size < 2) return null
    val touched = filter { family -> family.selfAndVariants().any { it.id in wanted } }
    val left = touched.mapNotNull { it.without(wanted) }
    return ThemeRegroup(touched, left + groupAsFamily(gathered.map { it.copy(variants = emptyList()) }, familyName))
}

/**
 * The looks [lookIds] taken out of their families to stand alone, each renamed
 * by [nameFor] (a look called "Dark" means little once it is not under its
 * family's label). What stays of a family stays together. Null when none of
 * the looks is in a family of more than one.
 */
fun List<ThemeSpec>.separatingLooks(
    lookIds: Collection<String>,
    nameFor: (family: ThemeSpec, look: ThemeSpec) -> String,
): ThemeRegroup? {
    val wanted = lookIds.toSet()
    val touched = filter { family -> family.variants.isNotEmpty() && family.selfAndVariants().any { it.id in wanted } }
    if (touched.isEmpty()) return null
    val after = touched.flatMap { family ->
        val alone = family.selfAndVariants()
            .filter { it.id in wanted }
            .map { it.copy(name = nameFor(family, it), variants = emptyList(), familyName = null) }
        listOfNotNull(family.without(wanted)) + alone
    }
    return ThemeRegroup(touched, after)
}

/** The family less the looks in [ids], headed by its first look left; null when none is. */
private fun ThemeSpec.without(ids: Set<String>): ThemeSpec? {
    val left = selfAndVariants().filter { it.id !in ids }.map { it.copy(variants = emptyList()) }
    return if (left.isEmpty()) null else groupAsFamily(left, familyName)
}

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** Readable text color (near-black or near-white) for a solid background. */
fun onColorFor(background: Long): Long =
    if (Color(background).luminance() > 0.5f) 0xFF15161A else 0xFFF4F4F8

/**
 * The same ARGB colour at [fraction] opacity, 0..1. Used for the board scrim,
 * where the alpha of the board colour is what dims a background photo.
 */
fun Long.withAlphaFraction(fraction: Float): Long {
    val alpha = (fraction.coerceIn(0f, 1f) * 255f).roundToInt().toLong()
    return (this and 0x00FFFFFFL) or (alpha shl 24)
}

/** How opaque an ARGB colour is, 0..1. */
fun Long.alphaFraction(): Float = ((this ushr 24) and 0xFFL) / 255f

/**
 * Rebuilds the palette from [seed] and [dark] while keeping everything about
 * the theme that is not a generated colour: gradients, the background image and
 * its credit, key shape and border, the gesture-trail colour, radii, animation.
 *
 * Written as "start from this theme and overwrite what the seed decides",
 * rather than "start from a generated theme and copy back a list of fields to
 * keep". The two produce the same result today, but the list version drops
 * anything added to [ThemeSpec] later — silently, and with no compiler
 * complaint — which is how attribution would have gone missing on every reseed.
 *
 * The nullable overrides below are deliberately cleared rather than kept:
 * they are per-theme exceptions to a palette that no longer exists, so a reseed
 * returns them to following the new colours.
 */
fun ThemeSpec.reseeded(seed: Long, dark: Boolean): ThemeSpec {
    val generated = themeFromSeed(id, name, seed, dark)
    // A generated palette is fully opaque, which over a background photo means
    // the photo disappears — and "match the colours to this photo" erasing the
    // photo is the least useful thing it could do. So when there is an image,
    // the see-through-ness the user set is carried across even though the
    // colours themselves are replaced.
    val hasImage = backgroundImage != null || backgroundImageLandscape != null
    fun keepAlpha(fresh: Long, previous: Long): Long =
        if (hasImage) fresh.withAlphaFraction(previous.alphaFraction()) else fresh
    return copy(
        dark = generated.dark,
        boardBackground = keepAlpha(generated.boardBackground, boardBackground),
        keyBackground = keepAlpha(generated.keyBackground, keyBackground),
        keyText = generated.keyText,
        modifierKeyBackground = keepAlpha(generated.modifierKeyBackground, modifierKeyBackground),
        enterKeyBackground = generated.enterKeyBackground,
        enterKeyText = generated.enterKeyText,
        pressedKeyBackground = generated.pressedKeyBackground,
        accent = generated.accent,
        popupBackground = generated.popupBackground,
        toolCircleBackground = generated.toolCircleBackground,
        chipBackground = generated.chipBackground,
        modifierKeyText = null,
        hintText = null,
        popupText = null,
        toolbarIcon = null,
        toolCircleActiveBackground = null,
        suggestionText = null,
        secondarySuggestionText = null,
        suggestionPillBackground = null,
        primarySuggestionPillBackground = null,
        suggestionBarBackground = null,
        toolbarBackground = null,
        navigationBarBackground = null,
        // The recorded roles described the palette that has just been replaced,
        // so nothing on the theme matches them any more. Clearing them lets
        // [followWallpaper] map the new palette by tone instead of doing nothing.
        wallpaperRoles = emptyMap(),
    )
}

/**
 * Builds a full theme from one seed color — the "pick a color, get a
 * sensible theme, then tweak" flow. All colors are opaque; the editor can
 * add alpha afterwards.
 */
fun themeFromSeed(id: String, name: String, seed: Long, dark: Boolean): ThemeSpec {
    val seedColor = Color(seed)
    return if (dark) {
        val board = lerp(Color(0xFF121318), seedColor, 0.08f)
        val key = lerp(Color(0xFF2B2D34), seedColor, 0.12f)
        ThemeSpec(
            id = id,
            name = name,
            dark = true,
            boardBackground = board.argb(),
            keyBackground = key.argb(),
            keyText = 0xFFE9E9EE,
            modifierKeyBackground = lerp(Color(0xFF1E2026), seedColor, 0.12f).argb(),
            enterKeyBackground = seed,
            enterKeyText = onColorFor(seed),
            pressedKeyBackground = lerp(key, seedColor, 0.45f).argb(),
            accent = lerp(seedColor, Color.White, 0.30f).argb(),
            popupBackground = lerp(Color(0xFF34363E), seedColor, 0.12f).argb(),
            toolCircleBackground = lerp(Color(0xFF2A2C33), seedColor, 0.14f).argb(),
            chipBackground = lerp(Color(0xFF24262C), seedColor, 0.10f).argb(),
        )
    } else {
        val board = lerp(Color(0xFFE8EAF0), seedColor, 0.06f)
        ThemeSpec(
            id = id,
            name = name,
            dark = false,
            boardBackground = board.argb(),
            keyBackground = 0xFFFFFFFF,
            keyText = 0xFF1B1C20,
            modifierKeyBackground = lerp(Color(0xFFD7DAE2), seedColor, 0.10f).argb(),
            enterKeyBackground = seed,
            enterKeyText = onColorFor(seed),
            pressedKeyBackground = lerp(Color.White, seedColor, 0.30f).argb(),
            accent = lerp(seedColor, Color.Black, 0.12f).argb(),
            popupBackground = 0xFFFFFFFF,
            toolCircleBackground = 0xFFFFFFFF,
            chipBackground = lerp(Color(0xFFDDE0E7), seedColor, 0.08f).argb(),
        )
    }
}

/**
 * The Ocean family's other looks: the flat colorways, then Pitch black —
 * which is the same idea taken all the way down rather than a design of its
 * own. Every id predates the family grouping and stays stable.
 */
private val OceanVariants: List<ThemeSpec> = listOf(
    themeFromSeed("builtin_forest", "Forest", 0xFF3E8E5A, dark = true),
    themeFromSeed("builtin_sunset", "Sunset", 0xFFE07B39, dark = true),
    themeFromSeed("builtin_berry", "Berry", 0xFFB84A8E, dark = true),
    themeFromSeed("builtin_crimson", "Crimson", 0xFFCE4257, dark = true),
    themeFromSeed("builtin_slate", "Slate", 0xFF7A8699, dark = true),
    // Pitch black: AMOLED-friendly, near-black keys on true black.
    themeFromSeed("builtin_pitch", "Pitch black", 0xFF4C8DF6, dark = true).copy(
        boardBackground = 0xFF000000,
        keyBackground = 0xFF1A1C21,
        modifierKeyBackground = 0xFF101216,
        toolCircleBackground = 0xFF1E2025,
        chipBackground = 0xFF15171B,
        popupBackground = 0xFF24262C,
    ),
)

/**
 * The gradient family's other looks; Deep sea heads it. Keys are translucent
 * so the gradient shows through; their flattened (opaque) versions still
 * contrast with the key text, which matters because panel surfaces flatten
 * alpha (see schemeFor).
 */
private val GradientVariants: List<ThemeSpec> = listOf(
    themeFromSeed("builtin_sunset_drift", "Sunset drift", 0xFFE07B39, dark = true).copy(
        boardGradient = GradientSpec(
            listOf(0xFF3B1035, 0xFF7A1E3C, 0xFFC2542B), GradientType.LINEAR, 100f,
        ),
        keyBackground = 0x59561F33,
        modifierKeyBackground = 0x403D1428,
        keyText = 0xFFFFEFE4,
        accent = 0xFFFFB07C,
        popupBackground = 0xFF57203A,
        toolCircleBackground = 0x59561F33,
        chipBackground = 0x403D1428,
        animation = ThemeAnimation.FLOW,
        animationSpeed = 1f,
    ),
    themeFromSeed("builtin_aurora", "Aurora", 0xFF3E8E5A, dark = true).copy(
        boardGradient = GradientSpec(
            listOf(0xFF0E3B43, 0xFF14543B, 0xFF14324F), GradientType.SWEEP, 0f,
        ),
        keyBackground = 0x59204A44,
        modifierKeyBackground = 0x40163830,
        keyText = 0xFFE6FFF4,
        accent = 0xFF7CE8B5,
        popupBackground = 0xFF1C4A42,
        toolCircleBackground = 0x59204A44,
        chipBackground = 0x40163830,
        animation = ThemeAnimation.HUE_CYCLE,
        animationSpeed = 0.6f,
    ),
    themeFromSeed("builtin_nebula", "Nebula", 0xFF8E5AC8, dark = true).copy(
        boardGradient = GradientSpec(
            listOf(0xFF2A1758, 0xFF15306B, 0xFF0B1B3A), GradientType.LINEAR, 135f,
        ),
        keyBackground = 0x59453076,
        modifierKeyBackground = 0x40311F5C,
        keyText = 0xFFF2ECFF,
        enterKeyBackground = 0xFF8E5AC8,
        enterKeyText = 0xFFF6F0FF,
        accent = 0xFFB79CFF,
        popupBackground = 0xFF352759,
        toolCircleBackground = 0x59453076,
        chipBackground = 0x40311F5C,
    ),
)

/** The Snow family's other looks; Glacier is Snow's gradient cousin. */
private val SnowVariants: List<ThemeSpec> = listOf(
    themeFromSeed("builtin_mint", "Mint", 0xFF4FA98F, dark = false),
    themeFromSeed("builtin_rose", "Rose", 0xFFC96A85, dark = false),
    themeFromSeed("builtin_sand", "Sand", 0xFFA98052, dark = false),
    themeFromSeed("builtin_glacier", "Glacier", 0xFF5B7DB1, dark = false).copy(
        boardGradient = GradientSpec(
            listOf(0xFFDDE9F7, 0xFFEAF3EE, 0xFFF6EEE7), GradientType.LINEAR, 20f,
        ),
    ),
)

/**
 * The Frost family's glass: every surface, a strong rim, colours lifted a
 * little. Window blur on, so on Android 12 and later the app behind shows
 * through the board blurred; where it cannot, the board is drawn almost
 * opaque and the keys keep their rims.
 */
private val FrostGlass = FrostSpec(
    windowBlur = true,
    windowBlurRadiusDp = 28,
    fallbackOpacity = 0.94f,
    surfaces = FrostSurface.ALL_NAMES,
    backdropBlur = 16f,
    saturation = 1.3f,
    brightness = 0f,
    rim = 0.6f,
    grain = 0.06f,
)

/** The sheen the Frost keys wear: a catch of light across the top of each pane. */
private val FrostSheen = GradientSpec(listOf(0x2EFFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f)

/**
 * The Frost family's other looks. Frost wallpaper stands on the wallpaper
 * rather than the app behind — a steady backdrop that never changes with the
 * app, and the look every phone and the gallery can show; the light look is
 * the same glass for a light phone.
 */
private val FrostVariants: List<ThemeSpec> = listOf(
    ThemeSpec(
        id = "builtin_frost_wallpaper",
        name = "Frost wallpaper",
        dark = true,
        // A scrim over the wallpaper: the wallpaper's band is the board, the
        // keys are the frosted panes over it.
        boardBackground = 0x800A1020,
        keyBackground = 0x33FFFFFF,
        keyText = 0xFFF4F7FF,
        // The same pane as a letter, so the glass reads as one material.
        modifierKeyBackground = 0x33FFFFFF,
        enterKeyBackground = 0xE68AB4F8,
        enterKeyText = 0xFF0B1220,
        pressedKeyBackground = 0x73FFFFFF,
        accent = 0xFF9CC9FF,
        popupBackground = 0xCC223047,
        popupText = 0xFFF4F7FF,
        toolCircleBackground = 0x33FFFFFF,
        chipBackground = 0x33FFFFFF,
        suggestionText = 0xFFF4F7FF,
        keyGradient = FrostSheen,
        keyCornerRadiusDp = 12,
        frost = FrostGlass.copy(windowBlur = false, backdrop = FrostBackdropSource.WALLPAPER.name),
    ),
    ThemeSpec(
        id = "builtin_frost_light",
        name = "Frost light",
        dark = false,
        boardBackground = 0xB3F2F5FA,
        keyBackground = 0x99FFFFFF,
        keyText = 0xFF1A2130,
        modifierKeyBackground = 0x99FFFFFF,
        enterKeyBackground = 0xFF3B6FD6,
        enterKeyText = 0xFFFFFFFF,
        pressedKeyBackground = 0xD9FFFFFF,
        accent = 0xFF2F62C4,
        popupBackground = 0xD9FFFFFF,
        popupText = 0xFF1A2130,
        toolCircleBackground = 0x80FFFFFF,
        chipBackground = 0x80FFFFFF,
        suggestionText = 0xFF1A2130,
        keyGradient = GradientSpec(listOf(0x59FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
        keyCornerRadiusDp = 12,
        frost = FrostGlass.copy(fallbackOpacity = 0.95f),
    ),
    // The author's own daily look, tuned on a CPH2481: liquid panes over the
    // blurred app, a board barely there, a bright rim and no grain, the
    // action keys a quieter pane than the letters and Enter a pale grey.
    // Backdrop NONE: where the blur cannot run, the board goes near opaque
    // rather than standing on the wallpaper.
    ThemeSpec(
        id = "builtin_frost_liquid",
        name = "Frost liquid",
        dark = true,
        boardBackground = 0x27000000,
        keyBackground = 0x2EFFFFFF,
        keyText = 0xFFF4F7FF,
        modifierKeyBackground = 0x1FFFFFFF,
        enterKeyBackground = 0xE6CBCBCB,
        enterKeyText = 0xFF0B1220,
        pressedKeyBackground = 0x73FFFFFF,
        accent = 0xFFCBCBCB,
        popupBackground = 0x9A00172F,
        popupText = 0xFFF4F7FF,
        popupSelectedBackground = 0xFFCCCCCC,
        toolCircleBackground = 0x2EFFFFFF,
        chipBackground = 0x2EFFFFFF,
        suggestionText = 0xFFF4F7FF,
        keyGradient = GradientSpec(listOf(0x26FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f),
        keyCornerRadiusDp = 12,
        frost = FrostGlass.copy(
            windowBlurRadiusDp = 20,
            fallbackOpacity = 0.96f,
            backdrop = FrostBackdropSource.NONE.name,
            backdropBlur = 5f,
            saturation = 1.15f,
            brightness = 0.03f,
            rim = 0.71f,
            grain = 0f,
            style = FrostStyle.LIQUID.name,
            refraction = 1f,
            dispersion = 0.2f,
        ),
    ),
)

/**
 * Frost: the look FrostKeys ships as its frosted glass, so the two read the
 * same side by side. Its night defaults, from its `KeyboardTheme.kt`: a 65 px
 * window blur (25 dp on a 2.6x phone), the board the wallpaper's accent
 * container at 80/255, every letter the wallpaper's primary at about 28%, the
 * action keys (shift, delete, ?123, enter) a solid mid accent blue, rounder
 * keys, no borders, no grain.
 *
 * The thin board is the point of the look and its cost: over a white app the
 * labels sit on a pale blur, as they do in FrostKeys. Where the blur cannot
 * run the board is drawn nearly opaque, and `FrostThemeContrastTest` holds
 * that case and the dark-app case to 4.5:1.
 */
private val FrostTheme: ThemeSpec = ThemeSpec(
    id = "builtin_frost",
    name = "Frost",
    dark = true,
    boardBackground = 0x50243653,
    keyBackground = 0x476E7B9A,
    keyText = 0xFFF1F0F7,
    // FrostKeys' action keys (shift, delete, ?123, enter) are a solid,
    // saturated accent blue; sampled side by side on a CPH2481 it was
    // (70, 104, 163) against the letters' pale glass.
    modifierKeyBackground = 0xE63F5F96,
    enterKeyBackground = 0xE63F5F96,
    enterKeyText = 0xFFF1F0F7,
    pressedKeyBackground = 0x806E7B9A,
    accent = 0xFFD9E2FF,
    popupBackground = 0xCC223047,
    popupText = 0xFFF1F0F7,
    toolCircleBackground = 0x476E7B9A,
    chipBackground = 0x476E7B9A,
    suggestionText = 0xFFF1F0F7,
    keyCornerRadiusDp = 16,
    // FrostKeys draws its frosted theme in the wallpaper's colours, and in
    // the wallpaper's *own* colours: the board is its accent container, not
    // a navy moved onto the wallpaper's hue, which kept the navy's darkness
    // and came out a muddy grey beside FrostKeys' saturated blue. So each
    // part names the Material You role FrostKeys takes it from, and the
    // values above are what a phone with no wallpaper palette draws.
    followWallpaper = true,
    wallpaperRoles = mapOf(
        wallpaperRoleKey(dark = true, role = "primaryContainer") to 0xFF243653,
        wallpaperRoleKey(dark = true, role = "primary") to 0xFF6E7B9A,
        wallpaperRoleKey(dark = true, role = "inversePrimary") to 0xFF3F5F96,
        wallpaperRoleKey(dark = true, role = "onPrimaryContainer") to 0xFFD9E2FF,
        wallpaperRoleKey(dark = true, role = "onSurface") to 0xFFF1F0F7,
        wallpaperRoleKey(dark = true, role = "surfaceContainerHigh") to 0xFF223047,
    ),
    frost = FrostGlass.copy(
        windowBlurRadiusDp = 25,
        rim = 0f,
        grain = 0f,
    ),
    variants = FrostVariants,
)

/**
 * Built-in gallery themes. Users can't edit these in place — the editor
 * duplicates one into a custom theme instead — so ids stay stable.
 *
 * The ten seed-only colorways and the four gradient cousins are grouped into
 * two families (Ocean, Snow) rather than listed flat: they were always one
 * design in different colors. Their ids are unchanged, so a selection, pin or
 * auto-theme slot from before the grouping still resolves — through the
 * variant search in `findThemeSpec`.
 *
 * [ExpressiveThemes] (designs after Expressive Keyboard's) and
 * [PaletteThemes] (ports of well-known editor colour schemes) are appended
 * rather than inlined: they are transcriptions of external palettes with
 * their own attribution rules, so they live in their own file.
 */
val BuiltInThemes: List<ThemeSpec> = listOf(
    themeFromSeed("builtin_ocean", "Ocean", 0xFF3B82C4, dark = true).copy(
        variants = OceanVariants,
    ),
    themeFromSeed("builtin_snow", "Snow", 0xFF5B7DB1, dark = false).copy(
        variants = SnowVariants,
    ),
    themeFromSeed("builtin_deep_sea", "Deep sea", 0xFF3B82C4, dark = true).copy(
        boardGradient = GradientSpec(
            listOf(0xFF0E2A4A, 0xFF071523), GradientType.RADIAL, 0f,
        ),
        keyBackground = 0x591E4568,
        modifierKeyBackground = 0x4014304A,
        popupBackground = 0xFF1B3C5C,
        toolCircleBackground = 0x591E4568,
        chipBackground = 0x4014304A,
        variants = GradientVariants,
    ),
    // Shape showcases: pill keys with a soft radial glow, cut-corner steel.
    themeFromSeed("builtin_bubble", "Bubble", 0xFF00897B, dark = true).copy(
        keyShape = KeyShapeKind.PILL,
        boardGradient = GradientSpec(
            listOf(0xFF16302E, 0xFF0D1517), GradientType.RADIAL, 0f,
        ),
        keyBackground = 0x5926504B,
        modifierKeyBackground = 0x401B3B37,
        toolCircleBackground = 0x5926504B,
        chipBackground = 0x401B3B37,
    ),
    themeFromSeed("builtin_facet", "Facet", 0xFF7A8699, dark = true).copy(
        keyShape = KeyShapeKind.CUT,
        boardGradient = GradientSpec(
            listOf(0xFF23262C, 0xFF14161A), GradientType.LINEAR, 90f,
        ),
        keyGradient = GradientSpec(
            listOf(0x14FFFFFF, 0x00FFFFFF), GradientType.LINEAR, 90f,
        ),
        keyBorderColor = 0x338A94A6,
        keyBorderWidthDp = 1f,
    ),
    FrostTheme,
) + ExpressiveThemes + PaletteThemes

/**
 * The name resource of every theme in [BuiltInThemes], keyed by the id of the
 * theme. The ids stay exactly as they are stored and as they are written into a
 * `.wmtheme.json` file; only the label on screen comes from the resource.
 *
 * The entries for [PaletteThemes] live next to those themes, in the same file.
 */
private val BuiltInThemeNameRes: Map<String, Int> = mapOf(
    "builtin_ocean" to R.string.core_theme_builtin_ocean_label,
    "builtin_forest" to R.string.core_theme_builtin_forest_label,
    "builtin_sunset" to R.string.core_theme_builtin_sunset_label,
    "builtin_berry" to R.string.core_theme_builtin_berry_label,
    "builtin_crimson" to R.string.core_theme_builtin_crimson_label,
    "builtin_slate" to R.string.core_theme_builtin_slate_label,
    "builtin_pitch" to R.string.core_theme_builtin_pitch_label,
    "builtin_snow" to R.string.core_theme_builtin_snow_label,
    "builtin_mint" to R.string.core_theme_builtin_mint_label,
    "builtin_rose" to R.string.core_theme_builtin_rose_label,
    "builtin_sand" to R.string.core_theme_builtin_sand_label,
    "builtin_nebula" to R.string.core_theme_builtin_nebula_label,
    "builtin_sunset_drift" to R.string.core_theme_builtin_sunset_drift_label,
    "builtin_aurora" to R.string.core_theme_builtin_aurora_label,
    "builtin_deep_sea" to R.string.core_theme_builtin_deep_sea_label,
    "builtin_glacier" to R.string.core_theme_builtin_glacier_label,
    "builtin_bubble" to R.string.core_theme_builtin_bubble_label,
    "builtin_facet" to R.string.core_theme_builtin_facet_label,
    "builtin_frost" to R.string.core_theme_builtin_frost_label,
    "builtin_frost_wallpaper" to R.string.core_theme_builtin_frost_wallpaper_label,
    "builtin_frost_light" to R.string.core_theme_builtin_frost_light_label,
    "builtin_frost_liquid" to R.string.core_theme_builtin_frost_liquid_label,
) + ExpressiveThemeNameRes + PaletteThemeNameRes

/**
 * The name resource of the built-in theme with this [id], or null when the id
 * belongs to a theme the user made. Use [themeName] to get the text itself.
 */
@StringRes
fun builtInThemeNameRes(id: String): Int? = BuiltInThemeNameRes[id]

/**
 * The family label of every built-in theme that heads one, keyed by the
 * parent's id — the counterpart of [ThemeSpec.familyName], which only custom
 * themes carry, so a built-in family's label can translate.
 */
private val BuiltInThemeFamilyNameRes: Map<String, Int> = mapOf(
    "builtin_ocean" to R.string.core_theme_family_ocean_label,
    "builtin_snow" to R.string.core_theme_family_snow_label,
    "builtin_deep_sea" to R.string.core_theme_family_gradient_label,
    "builtin_frost" to R.string.core_theme_family_frost_label,
) + ExpressiveThemeFamilyNameRes + PaletteThemeFamilyNameRes

/**
 * The family-label resource of the built-in family headed by [parentId], or
 * null for variants, customs and built-ins with no variants.
 */
@StringRes
fun builtInThemeFamilyNameRes(parentId: String): Int? = BuiltInThemeFamilyNameRes[parentId]

/**
 * The label of the family headed by [parent]: a built-in family's translated
 * name, a custom family's typed [ThemeSpec.familyName], or — when neither
 * exists — the parent's own name, which is what a family that was never named
 * should read as.
 */
@Composable
fun themeFamilyName(parent: ThemeSpec): String {
    val nameRes = builtInThemeFamilyNameRes(parent.id)
    if (nameRes != null) return stringResource(nameRes)
    return parent.familyName ?: themeName(parent)
}

/** [themeFamilyName] for code that has a [Context] and is not a composable. */
fun themeFamilyName(context: Context, parent: ThemeSpec): String {
    val nameRes = builtInThemeFamilyNameRes(parent.id)
    if (nameRes != null) return context.getString(nameRes)
    return parent.familyName ?: themeName(context, parent)
}

/**
 * The name to show for [theme]. A built-in theme gets its translated name. A
 * theme the user made keeps the name the user typed, which is never translated.
 */
@Composable
fun themeName(theme: ThemeSpec): String {
    val nameRes = builtInThemeNameRes(theme.id)
    return if (nameRes == null) theme.name else stringResource(nameRes)
}

/** [themeName] for code that has a [Context] and is not a composable. */
fun themeName(context: Context, theme: ThemeSpec): String {
    val nameRes = builtInThemeNameRes(theme.id)
    return if (nameRes == null) theme.name else context.getString(nameRes)
}

/** Seed swatches offered by the editor's "start from a color" row. */
val SeedSwatches: List<Long> = listOf(
    0xFF4C8DF6, 0xFF3B82C4, 0xFF00897B, 0xFF3E8E5A, 0xFF7CB342,
    0xFFF9A825, 0xFFE07B39, 0xFFCE4257, 0xFFB84A8E, 0xFF8E5AC8,
    0xFF5C6BC0, 0xFF7A8699, 0xFF6D4C41, 0xFF546E7A, 0xFF37393F,
)
